// Host test of the Japanese fuzzy rules (feedback B4): what each rule makes of a reading, and
// what it leaves alone.

#include <algorithm>

#include "check.h"
#include "fuzzy_kana.h"

using namespace kotomo::mixed;
using kotomo::mixed::test::Check;

namespace {

/// Returns: true when `rule` turns `from` into `to`.
bool Makes(uint16_t rule, const char* from, const char* to) {
  const std::vector<std::string> readings = FuzzyReadings(from, rule, 1000);
  return std::find(readings.begin(), readings.end(), to) != readings.end();
}

}  // namespace

int main() {
  // One positive per rule, in both directions where the rule has two.
  Check(Makes(kFuzzyLongVowel, "ときょ", "とうきょう") && Makes(kFuzzyLongVowel, "せんせ", "せんせい") &&
            Makes(kFuzzyLongVowel, "ここ", "こうこう") && Makes(kFuzzyLongVowel, "らめん", "らーめん") &&
            Makes(kFuzzyLongVowel, "おきい", "おおきい") && Makes(kFuzzyLongVowel, "おばさん", "おばあさん") &&
            Makes(kFuzzyLongVowel, "おじさん", "おじいさん") && Makes(kFuzzyLongVowel, "おねさん", "おねえさん") && Makes(kFuzzyLongVowel, "とうきょう", "ときょ"),
        "B4-rule-long-vowel");
  Check(Makes(kFuzzySokuon, "きて", "きって") && Makes(kFuzzySokuon, "ざし", "ざっし") &&
            Makes(kFuzzySokuon, "がっこう", "がこう"),
        "B4-rule-sokuon");
  Check(Makes(kFuzzyMoraicN, "こにちは", "こんにちは") && Makes(kFuzzyMoraicN, "きねん", "きんえん") &&
            Makes(kFuzzyMoraicN, "きんえん", "きねん") && Makes(kFuzzyMoraicN, "こにゃく", "こんやく") &&
            Makes(kFuzzyMoraicN, "こんにちは", "こにちは"),
        "B4-rule-moraic-n");
  Check(Makes(kFuzzyVoiced, "わだし", "わたし") && Makes(kFuzzyVoiced, "ありかと", "ありがと") &&
            Makes(kFuzzyVoiced, "かき", "がぎ"),
        "B4-rule-voiced");
  Check(Makes(kFuzzySemiVoiced, "いっはい", "いっぱい") && Makes(kFuzzySemiVoiced, "いっばい", "いっぱい") &&
            Makes(kFuzzySemiVoiced, "ぱん", "はん") && Makes(kFuzzySemiVoiced, "ぱん", "ばん"),
        "B4-rule-semi-voiced");
  Check(Makes(kFuzzyYoon, "きよう", "きょう") && Makes(kFuzzyYoon, "びょういん", "びよういん"),
        "B4-rule-yoon");
  Check(Makes(kFuzzyYotsugana, "つずく", "つづく") && Makes(kFuzzyYotsugana, "はなぢ", "はなじ"),
        "B4-rule-yotsugana");
  Check(Makes(kFuzzyRaNaDa, "なーめん", "らーめん") && Makes(kFuzzyRaNaDa, "らめ", "だめ") &&
            Makes(kFuzzyRaNaDa, "からだ", "かなだ"),
        "B4-rule-ra-na-da");
  Check(Makes(kFuzzyHiShi, "しと", "ひと") && Makes(kFuzzyHiShi, "ひつもん", "しつもん"), "B4-rule-hi-shi");
  Check(Makes(kFuzzyLoanword, "ばいおりん", "ゔぁいおりん") && Makes(kFuzzyLoanword, "ゔぁいおりん", "ばいおりん") &&
            Makes(kFuzzyLoanword, "ぱーちー", "ぱーてぃー") && Makes(kFuzzyLoanword, "ふぁいる", "はいる"),
        "B4-rule-loanword");
  Check(FuzzyRomaji("cuki", kFuzzyCu) == "tuki" && FuzzyRomaji("kiccu", kFuzzyCu) == "kittu" &&
            FuzzyRomaji("lamen", kFuzzyLAsR) == "ramen",
        "B4-rule-romaji");

  // Negative: rules that share a kana do not chain, and a rule leaves other kana alone.
  Check(!Makes(kFuzzyVoiced, "はん", "ぱん") && !Makes(kFuzzySemiVoiced, "はん", "ばん") &&
            !Makes(kFuzzyYotsugana, "つずく", "つすく") && !Makes(kFuzzyVoiced, "ときょ", "とうきょう") &&
            !Makes(kFuzzyLongVowel, "わだし", "わたし") && !Makes(kFuzzyHiShi, "しと", "じと"),
        "B4-N-rules-do-not-chain");
  // Negative: nothing for what is not kana, no reading twice, the limit holds, the reading
  // itself is never listed, and a romaji rule that changes nothing says so.
  const std::vector<std::string> many = FuzzyReadings("かきくけこさし", kFuzzyVoiced, 5);
  std::vector<std::string> unique = FuzzyReadings("ここ", kFuzzyLongVowel, 1000);
  const size_t listed = unique.size();
  std::sort(unique.begin(), unique.end());
  unique.erase(std::unique(unique.begin(), unique.end()), unique.end());
  Check(FuzzyReadings("neko", kFuzzyVoiced, 10).empty() && FuzzyReadings("", kFuzzyVoiced, 10).empty() &&
            FuzzyReadings("ねこa", kFuzzyVoiced, 10).empty() && many.size() == 5 && unique.size() == listed &&
            !Makes(kFuzzyVoiced, "ねこ", "ねこ") && FuzzyRomaji("tuki", kFuzzyCu).empty() &&
            FuzzyRomaji("cake", kFuzzyCu).empty() && FuzzyRomaji("ramen", kFuzzyLAsR).empty(),
        "B4-N-rule-bounds");
  return kotomo::mixed::test::Result();
}
