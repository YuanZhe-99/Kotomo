// Remembers the engines' answers while one composition is being typed (PLAN.md §5.5): a key
// more only asks about the spans that end at the new letter; everything before it was asked
// already.

#ifndef KOTOMO_CORE_MIXED_CACHED_ENGINES_H_
#define KOTOMO_CORE_MIXED_CACHED_ENGINES_H_

#include <map>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "engines.h"

namespace kotomo::mixed {

/// Answers the coordinator's questions from memory when the same question was asked before.
///
/// Notes: engine thread only, like the engines behind it. The engines keep no state between
/// calls and learn nothing (engines.h), so an answer holds for as long as their data does; the
/// session drops the memory whenever the composition is empty, which also bounds its size by one
/// composition. Nothing here outlives the session or is written anywhere.
class CachedEngines : public Engines {
 public:
  /// Inputs: `engines` must outlive this object.
  explicit CachedEngines(Engines& engines) : engines_(engines) {}

  /// Forgets every answer.
  void Clear() {
    spellings_.clear();
    chinese_.clear();
    hiragana_.clear();
    japanese_.clear();
    english_.clear();
  }

  void Spellings(std::string_view letters, std::vector<Spelling>* out) override {
    // Only the first few letters matter to the engine, so they are the whole question.
    const std::string key(letters.substr(0, kSpellingLetters));
    auto it = spellings_.find(key);
    if (it == spellings_.end()) {
      engines_.Spellings(key, out);
      it = spellings_.emplace(key, *out).first;
    }
    *out = it->second;
  }

  std::vector<ChineseWord> Chinese(std::string_view syllables, size_t limit) override {
    const std::string key = std::to_string(limit) + '\t' + std::string(syllables);
    auto it = chinese_.find(key);
    if (it == chinese_.end()) it = chinese_.emplace(key, engines_.Chinese(syllables, limit)).first;
    return it->second;
  }

  std::string Hiragana(std::string_view romaji) override {
    auto it = hiragana_.find(romaji);
    if (it == hiragana_.end()) it = hiragana_.emplace(std::string(romaji), engines_.Hiragana(romaji)).first;
    return it->second;
  }

  std::optional<std::vector<JapaneseSegment>> Japanese(const std::vector<JapaneseSpan>& spans,
                                                       bool incognito) override {
    std::string key(1, incognito ? '1' : '0');
    for (const JapaneseSpan& span : spans) {
      key += '\t' + span.reading + '\n' + span.surface;
    }
    auto it = japanese_.find(key);
    if (it == japanese_.end()) it = japanese_.emplace(std::move(key), engines_.Japanese(spans, incognito)).first;
    return it->second;
  }

  std::vector<EnglishForm> English(std::string_view word) override {
    auto it = english_.find(word);
    if (it == english_.end()) it = english_.emplace(std::string(word), engines_.English(word)).first;
    return it->second;
  }

 private:
  /// The longest pinyin syllable is six letters (`zhuang`); a prefix of eight asks the same.
  static constexpr size_t kSpellingLetters = 8;

  Engines& engines_;
  std::map<std::string, std::vector<Spelling>, std::less<>> spellings_;
  std::map<std::string, std::vector<ChineseWord>, std::less<>> chinese_;
  std::map<std::string, std::string, std::less<>> hiragana_;
  std::map<std::string, std::optional<std::vector<JapaneseSegment>>, std::less<>> japanese_;
  std::map<std::string, std::vector<EnglishForm>, std::less<>> english_;
};

}  // namespace kotomo::mixed

#endif  // KOTOMO_CORE_MIXED_CACHED_ENGINES_H_
