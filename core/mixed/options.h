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
  int32_t fuzzy = 3000;             // on top, a syllable reached only through a rule or correction
  int32_t chinese_rank = 2500;      // each place further down librime's list for a run
  int32_t chinese_composed = 4000;  // a run librime has no entry for and composes from words
  int32_t unfinished = 2500;        // on top, a last syllable still being typed (`shur`)
  int32_t pending = 3000;           // letters that begin a kana, kept as typed after a Japanese
                                    // sentence (`shur` → しゅ + r)
  int32_t kana = 2200;              // decoding estimate for one kana edge; Mozc's cost replaces it
  int32_t doubled_n = 200;          // on top, ん typed nn (so n + ni wins over nn + i)
  int32_t japanese_bias = 0;        // added to Mozc's cost of a free run
  int32_t english_base = 800;       // an English word of the highest probability
  int32_t english_step = 25;        // each probability step below 255
  int32_t english_min_probability = 80;  // rarer words are not proposed
  int32_t english_short_probability = 150;  // the floor for two or three letters inside an input
  int32_t english_recased = 800;         // on top, a spelling in another case than typed
  // A cross-language word costs Mozc's word cost of it, plus what Mozc's connection costs add
  // to a word on average (a pinned word reports none), plus the direction's penalty.
  int32_t japanese_word = 1500;          // the connection share of a pinned Japanese word
  int32_t pinyin_to_japanese = 500;      // the penalty for a pinyin span read as a Japanese word
  int32_t english_to_japanese = 500;     // the penalty for an English word read as a Japanese word
  int32_t transliteration = 3000;        // on top, a made-up katakana spelling
  int32_t english_in_chinese = 1500;     // an English word kept in a Chinese sentence
  int32_t english_in_japanese = 4000;    // an English word kept in a Japanese sentence
  int32_t japanese_fuzzy = 1500;         // on top, a reading reached through a Japanese fuzzy rule
  int32_t particle = 1200;               // は, を or へ typed wa, o or e
  int32_t language_switch = 500;         // between spans typed in different languages
  int32_t crossed_hint = 2500;           // a span that runs across a delimiter the person typed
  int32_t key_correction = 2500;         // a reading that takes one key for its neighbour
  int32_t key_correction_step = 20;      // on top, each unit the touch was nearer its own key
  int32_t context = 300;                 // off the language the text before the cursor is in
};

/// Chooses how strongly cross-language candidates compete (PLAN.md §2.3).
enum class Level : uint8_t { kConservative, kBalanced, kAggressive };

/// Holds the coordinator's options; every cross-language conversion has its own switch.
struct Options {
  Level level = Level::kBalanced;
  bool pinyin_to_japanese = true;   // a kanji word typed by its Chinese reading
  bool english_to_kanji = true;     // an English word read as the Japanese word that means it
  bool english_to_katakana = true;  // an English word read as its katakana loanword
  bool transliteration = true;      // with english_to_katakana: made-up katakana spellings too
  bool whole_sentence = true;       // false: a cross-language word only for the whole input
  bool abbreviation = true;         // a syllable typed by its initial alone (`tq` → 天气)
  uint16_t japanese_fuzzy = 0;      // the Japanese fuzzy rules in force, JapaneseFuzzy bits
                                    // (fuzzy_kana.h); none by default
  bool incognito = false;           // the field must leave no trace: Mozc converts incognito
  // The languages candidates are written in, indexed by Lang. Mixed mode asks for all three;
  // Japanese mode asks for Japanese only and takes the cross-language candidates (PLAN.md §2.3).
  bool targets[3] = {true, true, true};
  // What each target language's candidates cost on top (the per-language weights of PLAN.md
  // §2.3): 0 at the default weight, negative for a language the person wants more of.
  int32_t bias[3] = {0, 0, 0};      // indexed by Lang
  size_t beam = 16;         // partial paths kept per position
  size_t paths = 8;         // whole paths finished per target language
  size_t cross_words = 3;   // cross-language words proposed per span and direction
  size_t alternatives = 6;  // engine alternatives listed when one run covers the whole input
  size_t candidates = 20;   // candidates in a snapshot
  Costs costs;

  /// Returns: these options with the list sizes of the full answer (kRefine, feedback A9): every
  ///   candidate found, and as many conversions of a whole-input pinyin run as librime has, so
  ///   Mixed mode reaches every character Chinese mode does.
  /// Notes: the first answer keeps the small numbers above; the full one is never on the key path.
  Options Full() const {
    Options full = *this;
    full.alternatives = 1500;
    full.candidates = 2000;
    return full;
  }

  /// Returns: `cost` scaled by the level: doubled when conservative, halved when aggressive.
  /// Inputs: one of the cross-language penalties.
  int32_t Cross(int32_t cost) const {
    return level == Level::kConservative ? cost * 2 : level == Level::kAggressive ? cost / 2 : cost;
  }
};

}  // namespace kotomo::mixed

#endif  // KOTOMO_CORE_MIXED_OPTIONS_H_
