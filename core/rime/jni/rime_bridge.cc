// Kotomo's bridge to librime; see rime_bridge.h.

#include "rime_bridge.h"

#include "rime_spellings.h"

#include <rime_api.h>
#include <rime/candidate.h>
#include <rime/composition.h>
#include <rime/context.h>
#include <rime/gear/translator_commons.h>
#include <rime/menu.h>
#include <rime/schema.h>
#include <rime/service.h>

#include <sys/stat.h>

#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <numeric>
#include <string_view>
#include <unordered_set>

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
// Lower-case toneless keys indexed by the three run-time plain-text user_table dictionaries.
std::unordered_set<std::string> user_table_keys;

/// Counts UTF-8 code points in `text`.
std::size_t CodePoints(std::string_view text) {
  std::size_t n = 0;
  for (const char c : text) n += (static_cast<unsigned char>(c) & 0xc0) != 0x80 ? 1 : 0;
  return n;
}

/// Refreshes `user_table_keys` from `custom_phrase.txt`, `kotomo_packs.txt` and `kotomo_low.txt`
/// inside `user_dir` so Mixed-mode `Convert` skips extra un-apostrophed lookups when absent.
void LoadUserTableKeys() {
  user_table_keys.clear();
  if (user_dir.empty()) return;
  for (const char* name : {"custom_phrase.txt", "kotomo_packs.txt", "kotomo_low.txt"}) {
    std::ifstream in(std::filesystem::path(user_dir) / name);
    if (!in) continue;
    for (std::string line; std::getline(in, line);) {
      if (!line.empty() && line.back() == '\r') line.pop_back();
      if (line.empty() || line[0] == '#') continue;
      const std::size_t tab1 = line.find('\t');
      if (tab1 == std::string::npos) continue;
      const std::size_t tab2 = line.find('\t', tab1 + 1);
      std::string key =
          tab2 == std::string::npos ? line.substr(tab1 + 1) : line.substr(tab1 + 1, tab2 - tab1 - 1);
      if (!key.empty()) user_table_keys.insert(std::move(key));
    }
  }
}

/// Selects the schema that matches `learning` and `nine_key` in the open session.
bool ApplyLearning() {
  const char* schema = nine_key ? (learning ? kNineKeySchema : kNineKeyPrivateSchema)
                                : (learning ? kSchema : kPrivateSchema);
  const bool ok = session != 0 && api->select_schema(session, schema);
  if (ok) LoadUserTableKeys();
  return ok;
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
  user_table_keys.clear();
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

namespace {

/// Where each candidate of the last Candidates() answer stands in librime's own list.
std::vector<std::size_t> order;

/// Returns: librime's index of the candidate listed at `index` by the last Candidates() call.
std::size_t Listed(std::size_t index) { return index < order.size() ? order[index] : index; }

/// Returns: whether the schema the session runs has librime's key correction on
///   (`translator/enable_correction`, written by the app's rule file).
bool Correcting() {
  bool on = false;
  if (auto s = rime::Service::instance().GetSession(session)) {
    if (s->schema() != nullptr && s->schema()->config() != nullptr) {
      s->schema()->config()->GetBool("translator/enable_correction", &on);
    }
  }
  return on;
}

/// Returns: whether a candidate reads some letters as a syllable they do not spell, that is,
///   whether librime's key correction produced it (`no` read as ni).
/// Inputs: the candidate behind any filter's wrapper; the session's raw input.
/// Notes: librime marks corrections only inside its translator, so the reading is checked
///   against the spelling table syllable by syllable. Anything that is not a syllabified phrase
///   (punctuation, the run-time tables) counts as exact.
bool Corrected(const rime::an<rime::Candidate>& candidate, const std::string& input) {
  const auto phrase = rime::As<rime::Phrase>(candidate);
  if (!phrase) return false;
  const rime::Spans spans = phrase->spans();
  const rime::Code code = phrase->matching_code();
  if (spans.Count() != code.size()) return false;
  std::size_t begin = spans.start();
  for (const rime::SyllableId id : code) {
    const std::size_t end = spans.NextStop(begin);
    if (end <= begin || end > input.size()) return false;
    std::size_t first = begin, last = end;
    while (first < last && (input[first] == '\'' || input[first] == ' ')) ++first;
    while (last > first && (input[last - 1] == '\'' || input[last - 1] == ' ')) --last;
    if (!Spells(std::string_view(input).substr(first, last - first), id)) return true;
    begin = end;
  }
  return false;
}

}  // namespace

std::vector<Candidate> Candidates(std::size_t limit) {
  std::vector<Candidate> result;
  order.clear();
  if (session == 0 || limit == 0) return result;
  const bool correcting = Correcting();
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
            const auto genuine = rime::Candidate::GetGenuineCandidate(cand);
            result[i].type = genuine->type();
            result[i].whole = cand->end() == context->input().size();
            result[i].corrected = correcting && Corrected(genuine, context->input());
          }
        }
      }
    }
  }
  // Exact readings first (feedback B2): librime ranks a corrected reading by its word's
  // frequency, which would put 你 before 怒 for `nu`. Within each group librime's order stays.
  order.resize(result.size());
  std::iota(order.begin(), order.end(), std::size_t{0});
  if (correcting) {
    const auto tier = [&result](std::size_t i) {
      return (result[i].whole ? 0 : 2) + (result[i].corrected ? 1 : 0);
    };
    std::stable_sort(order.begin(), order.end(),
                     [&tier](std::size_t a, std::size_t b) { return tier(a) < tier(b); });
    std::vector<Candidate> sorted;
    sorted.reserve(result.size());
    for (const std::size_t i : order) sorted.push_back(std::move(result[i]));
    result = std::move(sorted);
  }
  return result;
}

std::vector<Candidate> WholeCandidates(std::size_t max_scan, std::size_t stop_after_exact,
                                       std::size_t code_points) {
  std::vector<Candidate> result;
  if (session == 0 || max_scan == 0) return result;
  auto s = rime::Service::instance().GetSession(session);
  if (!s) return result;
  rime::Context* context = s->context();
  if (context == nullptr || !context->HasMenu()) return result;
  const auto menu = context->composition().back().menu;
  if (!menu) return result;
  const std::string& input = context->input();
  const std::size_t input_size = input.size();
  const bool correcting = Correcting();
  std::size_t exact = 0;
  for (std::size_t i = 0; i < max_scan; ++i) {
    const auto cand = menu->GetCandidateAt(i);
    if (!cand || cand->end() != input_size) break;
    const auto genuine = rime::Candidate::GetGenuineCandidate(cand);
    std::string text = cand->text();
    std::string type = genuine->type();
    const bool corrected = correcting && Corrected(genuine, input);
    const bool count_match =
        !corrected && type != "sentence" && (code_points == 0 || CodePoints(text) == code_points);
    result.push_back({std::move(text), {}, std::move(type), /*whole=*/true, corrected});
    if (stop_after_exact > 0 && count_match && ++exact >= stop_after_exact) break;
  }
  return result;
}

bool HasUserTableKey(const std::string& key) {
  return user_table_keys.find(key) != user_table_keys.end();
}

bool DeleteCandidate(std::size_t index) {
  return session != 0 && api->delete_candidate(session, Listed(index));
}

bool SelectCandidate(std::size_t index) {
  return session != 0 && api->select_candidate(session, Listed(index));
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
