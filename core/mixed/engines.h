// The engines as the coordinator calls them (PLAN.md §2.5 decision 4, option (b)): a narrow set
// of questions, each answered by one engine. The coordinator links no engine library; on the
// device each method is a C function an engine library exports next to its JNI entry points, and
// on the host core/mixed/host answers them with librime and the LatinIME core linked in and Mozc
// behind its line service.

#ifndef KOTOMO_CORE_MIXED_ENGINES_H_
#define KOTOMO_CORE_MIXED_ENGINES_H_

#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace kotomo::mixed {

/// Holds one way a prefix of some letters spells a pinyin syllable.
struct Spelling {
  uint8_t length = 0;  // letters the syllable takes
  bool fuzzy = false;  // only through a fuzzy-pinyin rule or a spelling correction of the schema
  bool partial = false;  // not a whole syllable: an abbreviation (`zh`, `t`) or the unfinished
                         // start of one (`xio`); librime completes it when it converts the run
};

/// Holds one span handed to Mozc for the Japanese finish (PLAN.md §5.4).
struct JapaneseSpan {
  std::string reading;  // hiragana
  std::string surface;  // empty: Mozc converts the span; otherwise pinned to this surface
};

/// Holds one candidate of a Japanese segment.
struct JapaneseWord {
  std::string text;
  std::string reading;  // hiragana
  int32_t cost = 0;     // Mozc's cost in its context; lower is likelier
};

/// Holds one segment of Mozc's answer; `words` is best first and never empty.
struct JapaneseSegment {
  std::string reading;
  bool pinned = false;
  std::vector<JapaneseWord> words;
};

/// Holds one conversion of a pinyin run.
struct ChineseWord {
  std::string text;
  bool composed = false;  // librime made it up from shorter words; false: a dictionary entry
};

/// Holds one spelling of an English word in the dictionary.
struct EnglishForm {
  std::string text;     // as the dictionary writes it (`AI`, `apple`)
  int probability = 0;  // the dictionary's unigram probability, 0–255; higher is more common
  bool sensitive = false;  // the dictionary marks it possibly offensive
};

/// The questions the coordinator asks the engines, on the engine thread only.
///
/// Notes: no method learns anything or keeps state between calls.
class Engines {
 public:
  virtual ~Engines() = default;

  /// Lists the pinyin syllables that the start of `letters` spells, from librime's own spelling
  /// table of the deployed schema, so fuzzy-pinyin rules and corrections apply as they do in
  /// Chinese mode. Unfinished syllables are listed too, marked `partial`: the schema's
  /// abbreviations, and `letters` as a whole when they only begin a syllable.
  /// Inputs: `letters` in lower case; only its first few letters matter.
  /// Side effects: replaces the content of `out`.
  virtual void Spellings(std::string_view letters, std::vector<Spelling>* out) = 0;

  /// Converts one pinyin run with librime.
  /// Inputs: `syllables` are spellings joined by apostrophes (`ni'hao`), so librime segments
  ///   exactly there.
  /// Returns: up to `limit` conversions of the whole run, best first; empty when librime has none.
  virtual std::vector<ChineseWord> Chinese(std::string_view syllables, size_t limit) = 0;

  /// Maps romaji to hiragana with Mozc's romaji table.
  /// Returns: hiragana, with the letters the table cannot map left as ASCII.
  virtual std::string Hiragana(std::string_view romaji) = 0;

  /// Converts a reading in which some spans are pinned, with Mozc's converter.
  /// Inputs: `incognito` for a field that must leave no trace (Mozc then switches every mutable
  ///   operation off; nothing is committed in either case).
  /// Returns: the segments in reading order, or nullopt when Mozc converts nothing.
  virtual std::optional<std::vector<JapaneseSegment>> Japanese(
      const std::vector<JapaneseSpan>& spans, bool incognito) = 0;

  /// Looks an English word up in the English dictionary, exactly (no correction).
  /// Inputs: `word` in lower case.
  /// Returns: the dictionary's spellings of it (lower case, capitalized, upper case), most
  ///   probable first, each marked when the dictionary calls it possibly offensive; empty when
  ///   it is not a word.
  virtual std::vector<EnglishForm> English(std::string_view word) = 0;
};

}  // namespace kotomo::mixed

#endif  // KOTOMO_CORE_MIXED_ENGINES_H_
