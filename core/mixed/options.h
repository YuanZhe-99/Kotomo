// The coordinator's options and prior costs (PLAN.md §5.3). The costs are global constants for
// every input, never set for one corpus line (PLAN.md §13.3).

#ifndef KOTOMO_CORE_MIXED_OPTIONS_H_
#define KOTOMO_CORE_MIXED_OPTIONS_H_

#include <cstddef>
#include <cstdint>

namespace kotomo::mixed {

/// The prior costs, in Mozc's cost units (500 × −ln p), so Mozc's own costs add to them directly.
///
/// Notes: provisional values for spike S6, set once against the whole conversion corpus. PLAN.md
/// §5.3's per-language calibration (fitted, stored in the lexicon metadata) replaces them in P2.
struct Costs {
  int32_t pinyin_syllable = 1000;   // one syllable of a Chinese run
  int32_t fuzzy = 1500;             // on top, a syllable reached only through a rule or correction
  int32_t chinese_rank = 1800;      // each place further down librime's list for a run
  int32_t kana = 1500;              // decoding estimate for one kana edge; Mozc's cost replaces it
  int32_t doubled_n = 200;          // on top, ん typed nn (so n + ni wins over nn + i)
  int32_t japanese_bias = 0;        // added to Mozc's cost of a free run
  int32_t english_base = 800;       // an English word of the highest probability
  int32_t english_step = 25;        // each probability step below 255
  int32_t english_min_probability = 80;  // rarer words are not proposed
  int32_t pinyin_to_japanese = 2000;     // a pinyin span read as a Japanese word
  int32_t english_to_japanese = 2000;    // an English word read as a Japanese word
  int32_t transliteration = 3000;        // on top, a made-up katakana spelling
  int32_t english_in_chinese = 1500;     // an English word kept in a Chinese sentence
  int32_t english_in_japanese = 4000;    // an English word kept in a Japanese sentence
  int32_t particle = 1200;               // は, を or へ typed wa, o or e
  int32_t language_switch = 500;         // between spans typed in different languages
  int32_t crossed_hint = 2500;           // a span that runs across a delimiter the person typed
};

/// Chooses how strongly cross-language candidates compete (PLAN.md §2.3).
enum class Level : uint8_t { kConservative, kBalanced, kAggressive };

/// Holds the coordinator's options; every cross-language conversion has its own switch.
struct Options {
  Level level = Level::kBalanced;
  bool pinyin_to_japanese = true;
  bool english_to_japanese = true;
  bool transliteration = true;
  size_t beam = 12;         // partial paths kept per position
  size_t paths = 5;         // whole paths finished per target language
  size_t cross_words = 3;   // cross-language words proposed per span and direction
  size_t alternatives = 6;  // engine alternatives listed when one run covers the whole input
  size_t candidates = 20;   // candidates in a snapshot
  Costs costs;

  /// Returns: `cost` scaled by the level: doubled when conservative, halved when aggressive.
  /// Inputs: one of the cross-language penalties.
  int32_t Cross(int32_t cost) const {
    return level == Level::kConservative ? cost * 2 : level == Level::kAggressive ? cost / 2 : cost;
  }
};

}  // namespace kotomo::mixed

#endif  // KOTOMO_CORE_MIXED_OPTIONS_H_
