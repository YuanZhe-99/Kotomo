#include "kotomo/kotomo_mozc.h"

#include <cstddef>
#include <iterator>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "base/system_util.h"
#include "base/util.h"
#include "composer/composer.h"
#include "composer/table.h"
#include "config/config_handler.h"
#include "converter/converter.h"
#include "converter/segments.h"
#include "data_manager/data_manager.h"
#include "dictionary/dictionary_interface.h"
#include "dictionary/dictionary_token.h"
#include "engine/engine.h"
#include "protocol/commands.pb.h"
#include "protocol/config.pb.h"
#include "request/conversion_request.h"
#include "session/session_handler.h"

namespace kotomo::mozc {
namespace {

namespace commands = ::mozc::commands;
using ::mozc::ConversionRequest;
using ::mozc::ConversionRequestBuilder;
using ::mozc::Segments;

/// Sends one command to the session handler and returns its output.
/// Returns: nullopt when Mozc rejects the command.
std::optional<commands::Output> Eval(::mozc::SessionHandler& handler,
                                     commands::Input input) {
  commands::Command command;
  *command.mutable_input() = std::move(input);
  if (!handler.EvalCommand(&command) ||
      command.output().error_code() != commands::Output::SESSION_SUCCESS) {
    return std::nullopt;
  }
  return std::move(*command.mutable_output());
}

/// Builds a session-level input of `type` for session `id`.
commands::Input SessionInput(commands::Input::CommandType type, uint64_t id) {
  commands::Input input;
  input.set_type(type);
  input.set_id(id);
  return input;
}

/// Collects the first `max_candidates` candidates of each conversion segment.
/// Notes: a FIXED_VALUE segment keeps its pinned value as candidate 0; the
/// converter may add alternatives after it, which the caller can still offer.
Conversion ToConversion(const Segments& segments, size_t max_candidates) {
  Conversion conversion;
  for (const ::mozc::Segment& segment : segments.conversion_segments()) {
    Segment out{.key = std::string(segment.key()),
                .pinned = segment.segment_type() ==
                          ::mozc::Segment::FIXED_VALUE};
    for (size_t i = 0;
         i < segment.candidates_size() && out.candidates.size() < max_candidates;
         ++i) {
      const ::mozc::converter::Candidate& c = segment.candidate(i);
      out.candidates.push_back(
          {.value = c.value, .key = c.key, .lid = c.lid, .rid = c.rid,
           .cost = c.cost});
    }
    if (out.candidates.empty()) {
      out.candidates.push_back({.value = out.key, .key = out.key});
    }
    conversion.segments.push_back(std::move(out));
  }
  return conversion;
}

/// Keeps the lowest-cost dictionary token whose value equals the surface.
class ExactValueCallback final
    : public ::mozc::dictionary::DictionaryInterface::Callback {
 public:
  explicit ExactValueCallback(std::string_view surface) : surface_(surface) {}

  ResultType OnToken(absl::string_view, absl::string_view,
                     ::mozc::dictionary::Token token) override {
    if (token.value == surface_ && (!best_ || token.cost < best_->cost)) {
      best_ = std::move(token);
    }
    return TRAVERSE_CONTINUE;
  }

  const std::optional<::mozc::dictionary::Token>& best() const { return best_; }

 private:
  std::string_view surface_;
  std::optional<::mozc::dictionary::Token> best_;
};

/// Keeps the lowest cost among a key's dictionary tokens.
class LowestCostCallback final
    : public ::mozc::dictionary::DictionaryInterface::Callback {
 public:
  ResultType OnToken(absl::string_view, absl::string_view,
                     ::mozc::dictionary::Token token) override {
    if (cost_ < 0 || token.cost < cost_) cost_ = token.cost;
    return TRAVERSE_CONTINUE;
  }

  int32_t cost() const { return cost_; }

 private:
  int32_t cost_ = -1;
};

}  // namespace

std::string Conversion::Sentence() const {
  std::string sentence;
  for (const Segment& segment : segments) {
    sentence += segment.candidates.front().value;
  }
  return sentence;
}

std::unique_ptr<MozcEngine> MozcEngine::Open(const std::string& data_path,
                                             const std::string& profile_dir) {
  ::mozc::SystemUtil::SetUserProfileDirectory(profile_dir);
  auto data_manager = ::mozc::DataManager::CreateFromFile(data_path);
  if (!data_manager.ok()) {
    return nullptr;
  }
  auto engine = ::mozc::Engine::CreateEngine(*std::move(data_manager));
  if (!engine.ok()) {
    return nullptr;
  }
  std::unique_ptr<MozcEngine> self(new MozcEngine());
  self->engine_ = engine->get();
  self->session_handler_ =
      std::make_unique<::mozc::SessionHandler>(*std::move(engine));
  auto table = std::make_shared<::mozc::composer::Table>();
  if (!table->InitializeWithRequestAndConfig(
          commands::Request(), ::mozc::config::ConfigHandler::DefaultConfig())) {
    return nullptr;
  }
  self->romaji_table_ = std::move(table);
  return self;
}

MozcEngine::~MozcEngine() = default;

std::string MozcEngine::DataVersion() const {
  return std::string(engine_->GetDataVersion());
}

std::optional<Conversion> MozcEngine::SessionConvert(
    std::string_view typed, bool incognito, size_t max_candidates) const {
  ::mozc::SessionHandler& handler = *session_handler_;
  std::optional<commands::Output> created =
      Eval(handler, SessionInput(commands::Input::CREATE_SESSION, 0));
  if (!created) {
    return std::nullopt;
  }
  const uint64_t id = created->id();

  std::optional<commands::Output> output;
  {
    commands::Input input = SessionInput(commands::Input::SET_REQUEST, id);
    input.mutable_request()->set_is_incognito_mode(incognito);
    output = Eval(handler, std::move(input));
  }
  if (output) {
    commands::Input input = SessionInput(commands::Input::SEND_COMMAND, id);
    input.mutable_command()->set_type(commands::SessionCommand::TURN_ON_IME);
    input.mutable_command()->set_composition_mode(commands::HIRAGANA);
    output = Eval(handler, std::move(input));
  }
  for (const char32_t codepoint : ::mozc::Util::Utf8ToUtf32(typed)) {
    if (!output) {
      break;
    }
    commands::Input input = SessionInput(commands::Input::SEND_KEY, id);
    input.mutable_key()->set_key_code(codepoint);
    output = Eval(handler, std::move(input));
  }
  if (output) {
    commands::Input input = SessionInput(commands::Input::SEND_KEY, id);
    input.mutable_key()->set_special_key(commands::KeyEvent::SPACE);
    output = Eval(handler, std::move(input));
  }

  std::optional<Conversion> conversion;
  if (output && output->has_preedit()) {
    conversion.emplace();
    for (const auto& preedit : output->preedit().segment()) {
      Segment segment{.key = preedit.key()};
      segment.candidates.push_back({.value = preedit.value(),
                                    .key = preedit.key()});
      conversion->segments.push_back(std::move(segment));
    }
    // The candidate window lists the focused segment's candidates.
    if (!conversion->segments.empty()) {
      Segment& focused = conversion->segments.front();
      for (const auto& word : output->all_candidate_words().candidates()) {
        if (focused.candidates.size() >= max_candidates) {
          break;
        }
        if (word.value() != focused.candidates.front().value) {
          focused.candidates.push_back({.value = word.value(),
                                        .key = word.key()});
        }
      }
    }
  }

  // Revert so nothing is committed or learned, then drop the session.
  commands::Input revert = SessionInput(commands::Input::SEND_COMMAND, id);
  revert.mutable_command()->set_type(commands::SessionCommand::REVERT);
  Eval(handler, std::move(revert));
  Eval(handler, SessionInput(commands::Input::DELETE_SESSION, id));
  if (conversion && conversion->segments.empty()) {
    return std::nullopt;
  }
  return conversion;
}

std::optional<Conversion> MozcEngine::PinnedConvert(
    const std::vector<Span>& spans, bool incognito,
    size_t max_candidates) const {
  if (spans.empty()) {
    return std::nullopt;
  }
  Segments segments;
  std::string reading;
  for (const Span& span : spans) {
    if (span.reading.empty()) {
      return std::nullopt;
    }
    reading += span.reading;
    ::mozc::Segment* segment = segments.add_segment();
    segment->set_key(span.reading);
    if (span.surface.empty()) {
      segment->set_segment_type(span.fixed ? ::mozc::Segment::FIXED_BOUNDARY
                                           : ::mozc::Segment::FREE);
      continue;
    }
    // ImmutableConverter::Resegment inserts candidate 0 of a FIXED_VALUE
    // segment as the lowest-cost lattice node with its value, lid and rid.
    const PosIds ids = LookupPosIds(span.reading, span.surface);
    segment->set_segment_type(::mozc::Segment::FIXED_VALUE);
    ::mozc::converter::Candidate* candidate = segment->add_candidate();
    candidate->key = span.reading;
    candidate->content_key = span.reading;
    candidate->value = span.surface;
    candidate->content_value = span.surface;
    candidate->lid = ids.lid;
    candidate->rid = ids.rid;
  }

  ::mozc::config::Config config = ::mozc::config::ConfigHandler::DefaultConfig();
  config.set_incognito_mode(incognito);
  commands::Request request;
  request.set_is_incognito_mode(incognito);
  const ConversionRequest conversion_request =
      ConversionRequestBuilder()
          .SetRequestView(request)
          .SetConfigView(config)
          .SetOptions({.request_type = ConversionRequest::CONVERSION})
          .SetKey(reading)
          .Build();

  const auto converter =
      std::dynamic_pointer_cast<const ::mozc::converter::Converter>(
          engine_->GetConverter());
  if (!converter) {
    return std::nullopt;  // the engine fell back to its minimal converter
  }
  converter->ApplyConversion(&segments, conversion_request);
  if (segments.conversion_segments_size() == 0) {
    return std::nullopt;
  }
  return ToConversion(segments, max_candidates);
}

std::vector<Candidate> MozcEngine::Predict(std::string_view reading,
                                           bool incognito,
                                           size_t max_candidates) const {
  std::vector<Candidate> out;
  const auto converter =
      std::dynamic_pointer_cast<const ::mozc::converter::Converter>(
          engine_->GetConverter());
  if (reading.empty() || !converter) {
    return out;
  }
  ::mozc::config::Config config = ::mozc::config::ConfigHandler::DefaultConfig();
  config.set_incognito_mode(incognito);
  // A software keyboard's request, as Mozc's own mobile clients send it: mixed
  // conversion turns on dictionary prediction (completions, not only the
  // reading's conversions).
  commands::Request request;
  request.set_is_incognito_mode(incognito);
  request.set_zero_query_suggestion(true);
  request.set_mixed_conversion(true);
  const ConversionRequest conversion_request =
      ConversionRequestBuilder()
          .SetRequestView(request)
          .SetConfigView(config)
          // The reading is already hiragana, so the dictionary looks it up as
          // given rather than through a composer's romaji expansion.
          .SetOptions({.request_type = ConversionRequest::PREDICTION,
                       .use_already_typing_corrected_key = true})
          .SetKey(reading)
          .Build();
  Segments segments;
  if (!converter->StartPrediction(conversion_request, &segments) ||
      segments.conversion_segments_size() == 0) {
    return out;
  }
  const ::mozc::Segment& segment = segments.conversion_segment(0);
  for (size_t i = 0; i < segment.candidates_size() && out.size() < max_candidates;
       ++i) {
    const ::mozc::converter::Candidate& c = segment.candidate(i);
    out.push_back({.value = c.value, .key = c.key, .lid = c.lid, .rid = c.rid,
           .cost = c.cost});
  }
  return out;
}

int32_t MozcEngine::WordCost(std::string_view reading) const {
  const auto converter =
      std::dynamic_pointer_cast<const ::mozc::converter::Converter>(
          engine_->GetConverter());
  if (reading.empty() || !converter) {
    return -1;
  }
  LowestCostCallback callback;
  converter->modules().GetDictionary().LookupExact(
      reading, ConversionRequest::Options(), &callback);
  return callback.cost();
}

PosIds MozcEngine::LookupPosIds(std::string_view reading,
                                std::string_view surface) const {
  const auto converter =
      std::dynamic_pointer_cast<const ::mozc::converter::Converter>(
          engine_->GetConverter());
  if (!converter) {
    return {};
  }
  const ::mozc::engine::Modules& modules = converter->modules();
  ExactValueCallback callback(surface);
  modules.GetDictionary().LookupExact(reading, ConversionRequest::Options(),
                                      &callback);
  if (const auto& token = callback.best()) {
    return {.lid = token->lid, .rid = token->rid, .from_dictionary = true};
  }
  const uint16_t noun = modules.GetPosMatcher().GetGeneralNounId();
  return {.lid = noun, .rid = noun, .from_dictionary = false};
}

namespace {

struct PosNameEntry {
  uint16_t id;
  const char* pos;
};

constexpr PosNameEntry kPosNames[] = {
#include "kotomo/kotomo_pos_names.inc"
};

// Returns the byte length of the last UTF-8 code point of a non-empty `text`.
size_t LastCodepointLength(std::string_view text) {
  size_t n = 1;
  while (n < text.size() && (static_cast<unsigned char>(text[text.size() - n]) & 0xC0) == 0x80) {
    ++n;
  }
  return n;
}

}  // namespace

std::string_view MozcEngine::PosName(uint16_t id) {
  // id.def lists the ids densely from 0, in order.
  if (id < std::size(kPosNames) && kPosNames[id].id == id) {
    return kPosNames[id].pos;
  }
  return {};
}

std::optional<WordInfo> MozcEngine::Describe(std::string_view surface,
                                             std::string_view reading) const {
  while (!surface.empty() && !reading.empty()) {
    const PosIds ids = LookupPosIds(reading, surface);
    if (ids.from_dictionary) {
      return WordInfo{.value = std::string(surface), .key = std::string(reading),
                      .pos = std::string(PosName(ids.lid))};
    }
    // Okurigana are the same kana in the surface and the reading: drop one shared
    // trailing code point and look again (行きます → 行き).
    const size_t n = LastCodepointLength(surface);
    if (n > reading.size() || surface.substr(surface.size() - n) != reading.substr(reading.size() - n) ||
        n == surface.size() || n == reading.size()) {
      break;
    }
    surface.remove_suffix(n);
    reading.remove_suffix(n);
  }
  return std::nullopt;
}

std::string MozcEngine::ToHiragana(std::string_view romaji) const {
  ::mozc::composer::Composer composer(
      romaji_table_, commands::Request(),
      ::mozc::config::ConfigHandler::DefaultConfig());
  for (const char32_t codepoint : ::mozc::Util::Utf8ToUtf32(romaji)) {
    std::string key;
    ::mozc::Util::CodepointToUtf8Append(codepoint, &key);
    composer.InsertCharacter(std::move(key));
  }
  return composer.GetQueryForConversion();
}

}  // namespace kotomo::mozc
