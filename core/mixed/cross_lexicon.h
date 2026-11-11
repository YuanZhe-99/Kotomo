// The cross-language lexicon at run time (PLAN.md §5.9): the two tables lexicon/CrossLexicon.java
// writes into build/lexicon/, read once and searched by key.

#ifndef KOTOMO_CORE_MIXED_CROSS_LEXICON_H_
#define KOTOMO_CORE_MIXED_CROSS_LEXICON_H_

#include <cstdint>
#include <memory>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace kotomo::mixed {

/// Holds one cross-language entry: a Japanese word under the key it can be typed by.
struct CrossEntry {
  std::string_view key;      // pinyin without tones or delimiters, or an English word
  std::string_view surface;  // the Japanese word
  std::string_view reading;  // its hiragana reading
  int32_t cost = 0;          // Mozc's word cost of the entry; lower is more common
  uint8_t syllables = 0;     // pinyin → Japanese only: the syllables of the key
  bool transliteration = false;  // English → Japanese class `translit`: a made-up spelling
};

/// The two tables, sorted by key and then cost.
///
/// Notes: a lookup is a binary search, never a scan. The prototype parses the generator's TSV at
/// start; the compiled run-time format every lexicon shares (PLAN.md §5.2) comes with the device
/// integration (Q7). The part-of-speech ids in the tables are not read: Mozc's glue looks the
/// ids of a pinned word up by reading and surface itself.
class CrossLexicon {
 public:
  /// Reads `pinyin-ja.tsv` and `en-ja.tsv`.
  /// Returns: nullptr when a file cannot be read or a line is malformed (files read from disk are
  ///   a boundary).
  static std::unique_ptr<CrossLexicon> Load(const std::string& pinyin_ja, const std::string& en_ja);

  /// Builds a lexicon from TSV text (header line included), for tests.
  /// Returns: nullptr for a malformed line.
  static std::unique_ptr<CrossLexicon> FromText(std::string pinyin_ja, std::string en_ja);

  /// Returns: the pinyin → Japanese entries whose key is `pinyin`, cheapest first.
  std::span<const CrossEntry> PinyinJa(std::string_view pinyin) const { return Find(pinyin_, pinyin); }

  /// Returns: the English → Japanese entries whose key is `word`, cheapest first.
  std::span<const CrossEntry> EnJa(std::string_view word) const { return Find(english_, word); }

  /// Returns: the length of the longest key, so a proposer knows how far a span can reach.
  size_t longest_key() const { return longest_key_; }

  /// Returns: the number of entries in both tables.
  size_t size() const { return pinyin_.size() + english_.size(); }

 private:
  CrossLexicon() = default;
  bool Parse(std::string_view text, bool english, std::vector<CrossEntry>* out);
  static std::span<const CrossEntry> Find(const std::vector<CrossEntry>& table,
                                          std::string_view key);

  std::string pinyin_text_, english_text_;  // the entries' strings point into these
  std::vector<CrossEntry> pinyin_, english_;
  size_t longest_key_ = 0;
};

}  // namespace kotomo::mixed

#endif  // KOTOMO_CORE_MIXED_CROSS_LEXICON_H_
