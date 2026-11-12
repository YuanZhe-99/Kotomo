#include "session.h"

#include "input.h"

namespace kotomo::mixed {
namespace {

/// The cost one step of a language's weight is worth (PLAN.md §2.3): the whole range of the
/// setting moves a language by about one cross-language penalty either way.
constexpr int32_t kWeightStep = 40;

/// Decides whether a key belongs in a composition: a letter or a boundary hint (PLAN.md §5.1).
bool Composes(uint32_t c) {
  return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == ' ' || c == '\'';
}

}  // namespace

Snapshot Session::Handle(const Request& request) {
  std::string commit;
  switch (request.command) {
    case Command::kSet:
      composition_ = request.text.substr(0, kMaxInput);
      touches_.assign(composition_.size(), {kNoCoordinate, kNoCoordinate});
      break;
    case Command::kKey:
      if (Composes(request.code_point) && composition_.size() < kMaxInput) {
        composition_.push_back(static_cast<char>(request.code_point));
        touches_.emplace_back(request.x, request.y);
      }
      break;
    case Command::kBackspace:
      if (!composition_.empty()) {
        composition_.pop_back();
        touches_.pop_back();
      }
      break;
    case Command::kClear:
      composition_.clear();
      touches_.clear();
      break;
    case Command::kOptions:
      options_.level = static_cast<Level>(request.level);
      options_.pinyin_to_japanese = request.switches & kSwitchPinyinToJapanese;
      options_.english_to_kanji = request.switches & kSwitchEnglishToKanji;
      options_.english_to_katakana = request.switches & kSwitchEnglishToKatakana;
      options_.whole_sentence = request.switches & kSwitchWholeSentence;
      options_.incognito = request.switches & kSwitchIncognito;
      for (int i = 0; i < 3; ++i) {
        options_.targets[i] = request.targets & (1 << i);
        options_.bias[i] = -kWeightStep * request.weight[i];
      }
      break;
    case Command::kContext:
      context_ = request.text;
      break;
    case Command::kSelect:
      if (request.shown == last_generation_ && request.index < last_texts_.size()) {
        commit = last_texts_[request.index];
        composition_.clear();
        touches_.clear();
      }
      break;
  }
  Snapshot snapshot = Coordinator(engines_, lexicon_, options_).Convert(composition_, request.generation);
  snapshot.commit = std::move(commit);
  last_generation_ = snapshot.generation;
  last_texts_.clear();
  for (const Candidate& candidate : snapshot.candidates) last_texts_.push_back(candidate.text);
  return snapshot;
}

}  // namespace kotomo::mixed
