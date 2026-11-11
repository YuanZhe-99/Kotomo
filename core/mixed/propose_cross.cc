#include <algorithm>

#include "propose.h"

namespace kotomo::mixed {
namespace {

/// Adds up to `Options::cross_words` edges of `kind` for one span's entries.
/// Inputs: `whole` is true when the span is the whole input.
/// Notes: inside a longer input a one-syllable pinyin key (one kanji by one of its readings) is
///   far more often a chance split than the word meant, so only the whole input may be one.
void AddEntries(std::span<const CrossEntry> entries, EdgeKind kind, size_t begin, size_t end,
                bool whole, int32_t hint_cost, const Options& options, Lattice* lattice) {
  const Costs& costs = options.costs;
  size_t added = 0;
  for (const CrossEntry& entry : entries) {
    if (added == options.cross_words) break;
    if (entry.transliteration && !options.transliteration) continue;
    if (kind == EdgeKind::kPinyinJa && entry.syllables == 1 && !whole) continue;
    const int32_t penalty =
        (kind == EdgeKind::kPinyinJa ? costs.pinyin_to_japanese : costs.english_to_japanese) +
        (entry.transliteration ? costs.transliteration : 0);
    lattice->Add({.begin = static_cast<uint16_t>(begin),
                  .end = static_cast<uint16_t>(end),
                  .kind = kind,
                  .cost = entry.cost + costs.japanese_word + options.Cross(penalty) + hint_cost,
                  .transliteration = entry.transliteration,
                  .text = std::string(entry.surface),
                  .reading = std::string(entry.reading)});
    ++added;
  }
}

}  // namespace

void ProposeCross(const Input& input, const CrossLexicon& lexicon, const Options& options,
                  Lattice* lattice) {
  if (!options.pinyin_to_japanese && !options.english_to_japanese) return;
  const std::string_view letters = input.letters;
  for (size_t begin = 0; begin < input.size(); ++begin) {
    const size_t longest = std::min(input.size() - begin, lexicon.longest_key());
    for (size_t length = 1; length <= longest; ++length) {
      const size_t end = begin + length;
      const std::string_view key = letters.substr(begin, length);
      const int32_t hint_cost = options.costs.crossed_hint * input.HintsInside(begin, end);
      const bool whole = begin == 0 && end == input.size();
      if (options.pinyin_to_japanese) {
        AddEntries(lexicon.PinyinJa(key), EdgeKind::kPinyinJa, begin, end, whole, hint_cost,
                   options, lattice);
      }
      if (options.english_to_japanese && length > 1) {
        AddEntries(lexicon.EnJa(key), EdgeKind::kEnJa, begin, end, whole, hint_cost, options,
                   lattice);
      }
    }
  }
}

}  // namespace kotomo::mixed
