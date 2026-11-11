#include <algorithm>

#include "propose.h"

namespace kotomo::mixed {
namespace {

/// Decides whether the switches let an entry in.
bool Enabled(const CrossEntry& entry, const Options& options) {
  switch (entry.kind) {
    case CrossClass::kPinyin: return options.pinyin_to_japanese;
    case CrossClass::kSemantic: return options.english_to_kanji;
    case CrossClass::kLoanword: return options.english_to_katakana;
    case CrossClass::kTransliteration: return options.english_to_katakana && options.transliteration;
  }
  return false;
}

/// Adds up to `Options::cross_words` edges of `kind` for one span's entries.
/// Inputs: `whole` is true when the span is the whole input.
/// Notes: inside a longer input a one-syllable pinyin key (one kanji by one of its readings) is
///   far more often a chance split than the word meant, so only the whole input may be one.
void AddEntries(const std::vector<CrossEntry>& entries, EdgeKind kind, size_t begin, size_t end,
                bool whole, int32_t hint_cost, const Options& options, Lattice* lattice) {
  const Costs& costs = options.costs;
  size_t added = 0;
  for (const CrossEntry& entry : entries) {
    if (added == options.cross_words) break;
    if (!Enabled(entry, options)) continue;
    if (kind == EdgeKind::kPinyinJa && entry.syllables == 1 && !whole) continue;
    const bool made_up = entry.kind == CrossClass::kTransliteration;
    const int32_t penalty =
        (kind == EdgeKind::kPinyinJa ? costs.pinyin_to_japanese : costs.english_to_japanese) +
        (made_up ? costs.transliteration : 0);
    lattice->Add({.begin = static_cast<uint16_t>(begin),
                  .end = static_cast<uint16_t>(end),
                  .kind = kind,
                  .cost = entry.cost + costs.japanese_word + options.Cross(penalty) + hint_cost,
                  .transliteration = made_up,
                  .text = std::string(entry.surface),
                  .reading = std::string(entry.reading)});
    ++added;
  }
}

}  // namespace

void ProposeCross(const Input& input, const CrossLexicon& lexicon, const Options& options,
                  Lattice* lattice) {
  const bool english = options.english_to_kanji || options.english_to_katakana;
  if (!options.pinyin_to_japanese && !english) return;
  const std::string_view letters = input.letters;
  std::vector<CrossEntry> entries;
  for (size_t begin = 0; begin < input.size(); ++begin) {
    const size_t longest = std::min(input.size() - begin, lexicon.longest_key());
    for (size_t length = 1; length <= longest; ++length) {
      const size_t end = begin + length;
      const bool whole = begin == 0 && end == input.size();
      if (!whole && !options.whole_sentence) continue;
      const std::string_view key = letters.substr(begin, length);
      const int32_t hint_cost = options.costs.crossed_hint * input.HintsInside(begin, end);
      if (options.pinyin_to_japanese) {
        lexicon.PinyinJa(key, &entries);
        AddEntries(entries, EdgeKind::kPinyinJa, begin, end, whole, hint_cost, options, lattice);
      }
      if (english && length > 1) {
        lexicon.EnJa(key, &entries);
        AddEntries(entries, EdgeKind::kEnJa, begin, end, whole, hint_cost, options, lattice);
      }
    }
  }
}

}  // namespace kotomo::mixed
