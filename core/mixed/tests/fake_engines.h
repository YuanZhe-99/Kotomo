// Stand-ins for the three engines in the coordinator's unit tests: a handful of syllables, kana
// and words written for these tests, so the tests run without librime, Mozc or a dictionary and
// say exactly what each engine answered.

#ifndef KOTOMO_CORE_MIXED_TESTS_FAKE_ENGINES_H_
#define KOTOMO_CORE_MIXED_TESTS_FAKE_ENGINES_H_

#include <map>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "cross_lexicon.h"
#include "engines.h"

namespace kotomo::mixed::test {

/// Answers the coordinator's questions from small tables.
class FakeEngines : public Engines {
 public:
  void Spellings(std::string_view letters, std::vector<Spelling>* out) override {
    out->clear();
    for (const auto& [syllable, fuzzy] : syllables_) {
      if (letters.substr(0, syllable.size()) == syllable) {
        out->push_back({static_cast<uint8_t>(syllable.size()), fuzzy});
      }
    }
  }

  std::vector<std::string> Chinese(std::string_view syllables, size_t limit) override {
    ++chinese_calls;
    const auto found = chinese_.find(std::string(syllables));
    if (found == chinese_.end()) return {};
    std::vector<std::string> out = found->second;
    if (out.size() > limit) out.resize(limit);
    return out;
  }

  std::string Hiragana(std::string_view romaji) override {
    std::string out;
    size_t at = 0;
    while (at < romaji.size()) {
      size_t taken = 0;
      for (size_t length = std::min<size_t>(4, romaji.size() - at); length > 0; --length) {
        const auto found = kana_.find(std::string(romaji.substr(at, length)));
        if (found == kana_.end()) continue;
        out += found->second;
        taken = length;
        break;
      }
      if (taken == 0) out += romaji[at++];
      at += taken;
    }
    return out;
  }

  std::optional<std::vector<JapaneseSegment>> Japanese(
      const std::vector<JapaneseSpan>& spans) override {
    ++japanese_calls;
    std::vector<JapaneseSegment> out;
    for (const JapaneseSpan& span : spans) {
      JapaneseSegment segment{.reading = span.reading, .pinned = !span.surface.empty()};
      if (segment.pinned) {
        segment.words.push_back({span.surface, span.reading, 0});
        out.push_back(std::move(segment));
        continue;
      }
      // A free span: the longest known words in turn, and each unknown kana (three UTF-8 bytes)
      // as a costly segment of its own, much as a converter treats words it does not know.
      for (size_t at = 0; at < span.reading.size();) {
        size_t taken = 0;
        for (size_t length = span.reading.size() - at; length > 0; --length) {
          const auto found = japanese_.find(span.reading.substr(at, length));
          if (found == japanese_.end()) continue;
          out.push_back({.reading = found->first, .pinned = false, .words = found->second});
          taken = length;
          break;
        }
        if (taken == 0) {
          taken = std::min<size_t>(3, span.reading.size() - at);
          const std::string kana = span.reading.substr(at, taken);
          out.push_back({.reading = kana, .pinned = false, .words = {{kana, kana, 3000}}});
        }
        at += taken;
      }
    }
    return out;
  }

  std::vector<EnglishForm> English(std::string_view word) override {
    const auto found = english_.find(std::string(word));
    return found == english_.end() ? std::vector<EnglishForm>{} : found->second;
  }

  int chinese_calls = 0;
  int japanese_calls = 0;

 private:
  std::vector<std::pair<std::string, bool>> syllables_ = {
      {"shu", false}, {"hen", false}, {"hao", false}, {"ni", false}, {"xi", false},
      {"an", false},  {"xian", false}, {"tu", false}, {"guan", false}, {"ai", false},
      {"hoa", true}};
  std::map<std::string, std::vector<std::string>> chinese_ = {
      {"shu", {"书", "树", "数"}},        {"ni'hao", {"你好", "拟好"}},
      {"hen'hao", {"很好"}},              {"xi'an", {"西安"}},
      {"xian", {"先", "线"}},             {"tu'shu'guan", {"图书馆"}},
      {"ai", {"爱", "矮"}},               {"hoa", {"好"}},
      {"ni", {"你", "泥"}}};
  std::map<std::string, std::string> kana_ = {
      {"a", "あ"},   {"i", "い"},   {"u", "う"},   {"e", "え"},   {"o", "お"},   {"ka", "か"},
      {"ki", "き"},  {"ko", "こ"},  {"shi", "し"}, {"si", "し"},  {"su", "す"},  {"ta", "た"},
      {"to", "と"},  {"na", "な"},  {"ni", "に"},  {"ne", "ね"},  {"no", "の"},  {"ha", "は"},
      {"ho", "ほ"},  {"hu", "ふ"},  {"ma", "ま"},  {"mi", "み"},  {"mu", "む"},  {"yo", "よ"},
      {"wa", "わ"},  {"wo", "を"},  {"n", "ん"},   {"nn", "ん"},  {"tta", "った"}, {"he", "へ"},
      {"shu", "しゅ"}, {"tu", "つ"}, {"gu", "ぐ"}, {"ku", "く"}};
  std::map<std::string, std::vector<JapaneseWord>> japanese_ = {
      {"ほん", {{"本", "ほん", 3000}, {"ほん", "ほん", 5000}}},
      {"あい", {{"愛", "あい", 3500}, {"あい", "あい", 4500}}},
      {"よむ", {{"読む", "よむ", 3200}}},
      {"かう", {{"買う", "かう", 3300}}},
      {"いく", {{"行く", "いく", 3000}}},
      {"ねこ", {{"猫", "ねこ", 3100}, {"ねこ", "ねこ", 5200}}}};
  std::map<std::string, std::vector<EnglishForm>> english_ = {
      {"book", {{"book", 200}}}, {"ai", {{"AI", 150}, {"ai", 90}}}, {"the", {{"the", 255}}},
      {"note", {{"note", 180}}}, {"xq", {{"xq", 10}}},              {"shu", {{"shu", 85}}}};
};

/// Builds the tests' cross-language lexicon: three pinyin keys and three English keys.
inline std::unique_ptr<CrossLexicon> FakeLexicon() {
  return CrossLexicon::FromText(
      "key\tsyllables\tsurface\treading\tlid\trid\tcost\tsource\n"
      "shu\tshu1\t書\tしょ\t1\t1\t4000\ttest\n"
      "tushuguan\ttu2 shu1 guan3\t図書館\tとしょかん\t1\t1\t3000\ttest\n"
      "xian\txian4\t県\tけん\t1\t1\t3500\ttest\n"
      "xian\txian4\t線\tせん\t1\t1\t3200\ttest\n",
      "key\tclass\tsurface\treading\tlid\trid\tcost\tsource\n"
      "book\tsemantic\t本\tほん\t1\t1\t3000\ttest\n"
      "book\tgairaigo\tブック\tぶっく\t1\t1\t4200\ttest\n"
      "note\tgairaigo\tノート\tのーと\t1\t1\t3400\ttest\n"
      "xq\ttranslit\tエックスキュー\tえっくすきゅー\t1\t1\t5000\ttest\n");
}

}  // namespace kotomo::mixed::test

#endif  // KOTOMO_CORE_MIXED_TESTS_FAKE_ENGINES_H_
