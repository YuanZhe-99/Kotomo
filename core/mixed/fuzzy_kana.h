// The Japanese fuzzy rules (feedback B4): the readings a person who mistypes or mishears a
// word may have meant. Each rule has its own switch and all are off by default; a rule never
// implies another one that shares a kana.

#ifndef KOTOMO_CORE_MIXED_FUZZY_KANA_H_
#define KOTOMO_CORE_MIXED_FUZZY_KANA_H_

#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace kotomo::mixed {

/// Names the rules; the values are the bits of Options::japanese_fuzzy and of the options
/// request (doc/en-us/engine-contract.md).
enum JapaneseFuzzy : uint16_t {
  kFuzzyLongVowel = 1 << 0,   // a long vowel left out or typed too much (とうきょう / ときょ)
  kFuzzySokuon = 1 << 1,      // the small っ left out or typed too much (きって / きて)
  kFuzzyMoraicN = 1 << 2,     // ん before a vowel, や行 or な行 (こんにちは / こにちは)
  kFuzzyVoiced = 1 << 3,      // か/が, さ/ざ, た/だ, は/ば (わたし / わだし)
  kFuzzySemiVoiced = 1 << 4,  // は/ぱ, ば/ぱ
  kFuzzyYoon = 1 << 5,        // きょ / きよ: a small or a full や, ゆ, よ
  kFuzzyYotsugana = 1 << 6,   // じ/ぢ, ず/づ
  kFuzzyRaNaDa = 1 << 7,      // ら行 for な行 or だ, で, ど
  kFuzzyHiShi = 1 << 8,       // ひ/し
  kFuzzyLoanword = 1 << 9,    // ヴァ/バ, ティ/チ, ファ/ハ: two spellings of a loanword
  kFuzzyLAsR = 1 << 10,       // romaji: l typed for r (`lamen`)
  kFuzzyCu = 1 << 11,         // romaji: cu typed for つ
};

/// The rules that rewrite a kana reading, the ones that respell romaji, and every rule.
inline constexpr uint16_t kFuzzyKanaRules = (1 << 10) - 1;
inline constexpr uint16_t kFuzzyRomajiRules = kFuzzyLAsR | kFuzzyCu;
inline constexpr uint16_t kFuzzyAll = (1 << 12) - 1;

/// Lists the readings one rule turns a reading into.
/// Inputs: `reading` in hiragana; `rule` one bit of kFuzzyKanaRules; `limit` caps the list.
/// Returns: the readings that differ from `reading` at one or two places the rule applies to,
///   one place first, without duplicates; empty for a reading that is not kana.
/// Notes: nothing is looked up here; the proposer keeps the readings Mozc's dictionary has.
std::vector<std::string> FuzzyReadings(std::string_view reading, uint16_t rule, size_t limit);

/// Respells romaji by one romaji rule.
/// Inputs: lower-case letters; `rule` kFuzzyLAsR or kFuzzyCu.
/// Returns: the letters with l written r, or a c before u written t (`ccu` → `ttu`); empty when
///   the rule changes nothing.
std::string FuzzyRomaji(std::string_view romaji, uint16_t rule);

}  // namespace kotomo::mixed

#endif  // KOTOMO_CORE_MIXED_FUZZY_KANA_H_
