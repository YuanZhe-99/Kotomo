// Kotomo's bridge to librime; see rime_bridge.h.

#include "rime_bridge.h"

#include "rime_spellings.h"

#include <rime_api.h>
#include <rime/candidate.h>
#include <rime/composition.h>
#include <rime/context.h>
#include <rime/menu.h>
#include <rime/service.h>

#include <sys/stat.h>

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iterator>

namespace kotomo::rime_bridge {
namespace {

constexpr const char* kSchema = "luna_pinyin_simp";
constexpr const char* kPrivateSchema = "luna_pinyin_simp_private";
// The same two for the Chinese 9-key layout: spellings in keypad digits (PLAN §10.9).
constexpr const char* kNineKeySchema = "kotomo_t9";
constexpr const char* kNineKeyPrivateSchema = "kotomo_t9_private";

// The service modules plus the deployer's, so Deploy() can run synchronously on the engine thread.
const char* kModules[] = {"default", "deployer", nullptr};

RimeApi* api = nullptr;
RimeSessionId session = 0;
bool learning = true;
bool nine_key = false;
// RimeTraits keeps pointers, and librime reads the directories again when it reinitializes.
std::string shared_dir;
std::string user_dir;
// The deploy tasks that failed in the last Deploy().
std::string failed_tasks;

/// Selects the schema that matches `learning` and `nine_key` in the open session.
bool ApplyLearning() {
  const char* schema = nine_key ? (learning ? kNineKeySchema : kNineKeyPrivateSchema)
                                : (learning ? kSchema : kPrivateSchema);
  return session != 0 && api->select_schema(session, schema);
}

/// Maps the spelling table of the schema the session runs: the deployed one in the user
/// directory when there is one (fuzzy-pinyin rules live there), else the prebuilt one.
/// Notes: both schemas (learning and private) share this table.
void OpenSchemaSpellings() {
  const std::string file = std::string("/build/") + kSchema + ".prism.bin";
  if (!OpenSpellings(user_dir + file) && !OpenSpellings(shared_dir + file)) CloseSpellings();
}

/// Opens the session and applies the learning choice.
bool OpenSession() {
  session = api->create_session();
  if (session == 0) return false;
  ApplyLearning();
  OpenSchemaSpellings();
  return true;
}

/// Closes the session, if any.
void CloseSession() {
  if (session != 0) api->destroy_session(session);
  session = 0;
  CloseSpellings();
}

}  // namespace

bool Initialize(const std::string& shared_data_dir, const std::string& user_data_dir) {
  if (api != nullptr) Shutdown();
  api = rime_get_api();
  shared_dir = shared_data_dir;
  user_dir = user_data_dir;

  RIME_STRUCT(RimeTraits, traits);
  traits.shared_data_dir = shared_dir.c_str();
  traits.user_data_dir = user_dir.c_str();
  traits.distribution_name = "Kotomo";
  traits.distribution_code_name = "kotomo";
  traits.distribution_version = RIME_VERSION;
  traits.app_name = "rime.kotomo";
  traits.modules = kModules;
  traits.min_log_level = 3;
  traits.log_dir = "";
  api->setup(&traits);
  api->initialize(&traits);
  return OpenSession();
}

bool Deploy() {
  if (api == nullptr) return false;
  CloseSession();
  failed_tasks.clear();
  for (const char* task : {"installation_update", "workspace_update", "user_dict_upgrade", "cleanup_trash"}) {
    if (!api->run_task(task)) {
      if (!failed_tasks.empty()) failed_tasks += ", ";
      failed_tasks += task;
    }
  }
  return OpenSession() && failed_tasks.empty();
}

std::string DeployFailures() { return failed_tasks; }

std::string SelfCheck() {
  if (api == nullptr) return "librime not initialized";
  if (session == 0) return "no session";
  std::string out;
  // Files as the C++ runtime sees them, and configs as librime loads them.
  namespace fs = std::filesystem;
  const fs::path schema_file = fs::path(shared_dir) / "build" / "luna_pinyin_simp.schema.yaml";
  std::error_code ec;
  const bool exists = fs::exists(schema_file, ec);
  out += std::string("fs_exists=") + (exists ? "yes" : "no") + (ec ? "(" + ec.message() + ")" : "");
  std::ifstream in(schema_file, std::ios::binary);
  std::string bytes((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
  out += " ifstream=" + std::to_string(bytes.size());
  // The path exactly as librime got it, and what the C library says about it.
  out += " shared='" + shared_dir + "'";
  errno = 0;
  if (FILE* f = std::fopen(schema_file.c_str(), "rb")) {
    std::fclose(f);
    out += " fopen=ok";
  } else {
    out += std::string(" fopen=") + std::strerror(errno);
  }
  struct stat dir_stat;
  out += std::string(" shared_dir=") + (::stat(shared_dir.c_str(), &dir_stat) == 0 ? "ok" : std::strerror(errno));
  RimeConfig config = {};
  if (api->config_open("default", &config)) {
    out += " default.schema_list=" + std::to_string(api->config_list_size(&config, "schema_list"));
    api->config_close(&config);
  } else {
    out += " default=unopened";
  }
  if (api->schema_open(kSchema, &config)) {
    out += " schema.processors=" + std::to_string(api->config_list_size(&config, "engine/processors"));
    out += " translators=" + std::to_string(api->config_list_size(&config, "engine/translators"));
    api->config_close(&config);
  } else {
    out += " schema=unopened";
  }
  out += " | ";
  char schema[128] = {};
  out += api->get_current_schema(session, schema, sizeof schema) ? std::string("schema=") + schema : "schema=?";
  out += api->get_option(session, "ascii_mode") ? " ascii_mode=on" : " ascii_mode=off";
  api->clear_composition(session);
  api->set_input(session, "nihao");
  const auto candidates = Candidates(5);
  out += " nihao=" + std::to_string(candidates.size()) + (candidates.empty() ? "" : "/" + candidates[0].text);
  api->clear_composition(session);
  const bool handled = api->process_key(session, 'n', 0);
  out += std::string(" key_n=") + (handled ? "handled" : "not_handled") + " preedit='" + Composition() + "'";
  api->clear_composition(session);
  return out;
}

bool SetInput(const std::string& input) {
  return session != 0 && api->set_input(session, input.c_str());
}

std::string Input() {
  if (session == 0) return {};
  const char* input = api->get_input(session);
  return input ? input : "";
}

bool ProcessKey(int keycode, int mask) {
  return session != 0 && api->process_key(session, keycode, mask);
}

std::string Composition() {
  if (session == 0) return {};
  RIME_STRUCT(RimeContext, context);
  if (!api->get_context(session, &context)) return {};
  std::string preedit = context.composition.preedit ? context.composition.preedit : "";
  api->free_context(&context);
  return preedit;
}

std::vector<Candidate> Candidates(std::size_t limit) {
  std::vector<Candidate> result;
  if (session == 0 || limit == 0) return result;
  RimeCandidateListIterator iterator = {};
  if (!api->candidate_list_begin(session, &iterator)) return result;
  while (result.size() < limit && api->candidate_list_next(&iterator)) {
    const RimeCandidate& c = iterator.candidate;
    result.push_back({c.text ? c.text : "", c.comment ? c.comment : "", {}});
  }
  api->candidate_list_end(&iterator);
  // The C API carries no candidate type; read it from the session's menu (the same candidates, in
  // the same order). A filter's wrapper (the simplifier's) is looked through to the translation.
  if (auto s = rime::Service::instance().GetSession(session)) {
    rime::Context* context = s->context();
    if (context != nullptr && !context->composition().empty()) {
      if (auto menu = context->composition().back().menu) {
        for (std::size_t i = 0; i < result.size(); ++i) {
          if (auto cand = menu->GetCandidateAt(i)) {
            result[i].type = rime::Candidate::GetGenuineCandidate(cand)->type();
            result[i].whole = cand->end() == context->input().size();
          }
        }
      }
    }
  }
  return result;
}

bool DeleteCandidate(std::size_t index) {
  return session != 0 && api->delete_candidate(session, index);
}

bool SelectCandidate(std::size_t index) {
  return session != 0 && api->select_candidate(session, index);
}

std::string TakeCommit() {
  if (session == 0) return {};
  RIME_STRUCT(RimeCommit, commit);
  if (!api->get_commit(session, &commit)) return {};
  std::string text = commit.text ? commit.text : "";
  api->free_commit(&commit);
  return text;
}

void Clear() {
  if (session != 0) api->clear_composition(session);
}

void SetOption(const std::string& name, bool value) {
  if (session != 0) api->set_option(session, name.c_str(), value);
}

bool SetLearning(bool enabled) {
  learning = enabled;
  return ApplyLearning();
}

bool SetNineKey(bool enabled) {
  if (enabled == nine_key) return true;
  nine_key = enabled;
  return ApplyLearning();
}

void Shutdown() {
  if (api == nullptr) return;
  CloseSession();
  api->finalize();
  api = nullptr;
}

}  // namespace kotomo::rime_bridge
