// The cross-language lexicon at run time (PLAN.md §5.9): the compiled, read-only file the build
// makes from lexicon/CrossLexicon.java's two tables (cross_lexicon_build.h), mapped into memory
// and searched by key. The format is described in doc/en-us/lexicon-format.md.

#ifndef KOTOMO_CORE_MIXED_CROSS_LEXICON_H_
#define KOTOMO_CORE_MIXED_CROSS_LEXICON_H_

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <string_view>
#include <vector>

namespace kotomo::mixed {

/// Names the version of the compiled format; a reader refuses any other.
inline constexpr uint32_t kLexiconVersion = 1;

/// Names what kind of mapping an entry is (the generator's `class` column for English keys).
enum class CrossClass : uint8_t {
  kPinyin = 0,           // pinyin → Japanese: a kanji word under its Chinese reading
  kSemantic = 1,         // English → Japanese: a word that means the same (`school` → 学校)
  kLoanword = 2,         // English → Japanese: the katakana loanword (`coffee` → コーヒー)
  kTransliteration = 3,  // English → Japanese: a made-up katakana spelling, no dictionary word
};

/// Holds one cross-language entry: a Japanese word under the key it can be typed by.
struct CrossEntry {
  std::string_view surface;  // the Japanese word
  std::string_view reading;  // its hiragana reading
  int32_t cost = 0;          // Mozc's word cost of the entry; lower is more common
  uint8_t syllables = 0;     // pinyin → Japanese only: the syllables of the key
  CrossClass kind = CrossClass::kPinyin;
};

/// The two tables of one compiled lexicon, keys sorted, entries of a key cheapest first.
///
/// Notes: a lookup is a binary search over the keys, never a scan, and touches only the pages it
/// reads; nothing is parsed or copied at start. The file comes from disk, so the header is
/// checked when it is opened and every offset when it is followed: a damaged file finds nothing
/// rather than reading outside the map. The part-of-speech ids of the generator's tables are not
/// in the file: Mozc's glue looks the ids of a pinned word up by reading and surface itself.
class CrossLexicon {
 public:
  /// Maps a compiled lexicon read-only.
  /// Inputs: the file holding it at byte `offset` for `length` bytes (the rest of the file when
  ///   `length` is 0), so an uncompressed APK asset is read in place.
  /// Returns: nullptr when the file cannot be mapped or its header is not a lexicon of this
  ///   version whose sizes add up to `length`.
  static std::unique_ptr<CrossLexicon> Map(const std::string& path, uint64_t offset = 0,
                                           uint64_t length = 0);

  /// Wraps compiled bytes held in memory (CompileLexicon's output), for tests and host tools.
  /// Returns: nullptr for a header that fails the same checks as Map.
  static std::unique_ptr<CrossLexicon> FromBytes(std::string bytes);

  /// Returns: an empty lexicon, for a build without the compiled file: nothing is ever found.
  static std::unique_ptr<CrossLexicon> Empty();

  ~CrossLexicon();
  CrossLexicon(const CrossLexicon&) = delete;
  CrossLexicon& operator=(const CrossLexicon&) = delete;

  /// Finds the pinyin → Japanese entries whose key is `pinyin`, cheapest first.
  /// Side effects: replaces the content of `out`; the views point into the lexicon.
  void PinyinJa(std::string_view pinyin, std::vector<CrossEntry>* out) const {
    Find(0, pinyin_keys_, pinyin, out);
  }

  /// Finds the English → Japanese entries whose key is `word`, cheapest first.
  /// Side effects: replaces the content of `out`; the views point into the lexicon.
  void EnJa(std::string_view word, std::vector<CrossEntry>* out) const {
    Find(pinyin_keys_ + 1, english_keys_, word, out);
  }

  /// Returns: the length of the longest key, so a proposer knows how far a span can reach.
  size_t longest_key() const { return longest_key_; }

  /// Returns: the number of entries in both tables.
  size_t size() const { return entries_; }

 private:
  CrossLexicon() = default;
  bool Open(const uint8_t* data, size_t size);
  void Find(size_t first_key, size_t keys, std::string_view key,
            std::vector<CrossEntry>* out) const;
  bool String(uint32_t offset, std::string_view* text) const;

  std::string bytes_;            // FromBytes: the owner of the data
  void* map_ = nullptr;          // Map: the mapping, page-aligned
  size_t map_size_ = 0;
  const uint8_t* keys_ = nullptr;     // both key tables, the pinyin one first
  const uint8_t* records_ = nullptr;  // the entries
  const char* strings_ = nullptr;
  size_t pinyin_keys_ = 0, english_keys_ = 0, entries_ = 0, strings_size_ = 0, longest_key_ = 0;
};

}  // namespace kotomo::mixed

#endif  // KOTOMO_CORE_MIXED_CROSS_LEXICON_H_
