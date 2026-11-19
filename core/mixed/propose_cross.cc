#include <algorithm>
#include <string>
#include <vector>

#include "propose.h"

namespace kotomo::mixed {
namespace {

/// The longest English key that is read as Japanese only when it is the whole input.
constexpr size_t kShortKey = 3;

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

/// Returns: true if `seen` already contains `surface`, otherwise records it and returns false.
bool SeenSurface(std::string_view surface, std::vector<std::string>* seen) {
  for (const std::string& prev : *seen) {
    if (prev == surface) return true;
  }
  seen->emplace_back(surface);
  return false;
}

/// Adds cross-language edges of `kind` for one span's entries, deduplicated by surface within
/// `seen`. Returns: whether any `CrossClass::kLoanword` entry was added for this span.
/// Inputs: `whole` is true when the span is the whole input; `extra_cost` adds to each entry
///   (e.g. plural-stem fallback penalty).
/// Notes: for kEnJa, semantic entries (`cross_words`), loanwords (`cross_loanwords`) and at most
///   one fallback transliteration (when no loanword is present) hold independent budgets so
///   multiple semantic kanji meanings do not starve katakana loanwords (`power` → パワー).
///   Inside a longer input a one-syllable pinyin key is far more often a chance split, so only
///   the whole input may be one.
bool AddEntries(const std::vector<CrossEntry>& entries, EdgeKind kind, size_t begin, size_t end,
                bool whole, int32_t hint_cost, int32_t extra_cost, const Options& options,
                std::vector<std::string>* seen, size_t* semantic, size_t* loanword,
                size_t* translit, size_t* pinyin, Lattice* lattice) {
  const Costs& costs = options.costs;
  for (const CrossEntry& entry : entries) {
    if (!Enabled(entry, options)) continue;
    if (kind == EdgeKind::kPinyinJa) {
      if (*pinyin >= options.cross_words) break;
      if (entry.syllables == 1 && !whole) continue;
    } else {
      switch (entry.kind) {
        case CrossClass::kSemantic:
          if (*semantic >= options.cross_words) continue;
          break;
        case CrossClass::kLoanword:
          if (*loanword >= options.cross_loanwords) continue;
          break;
        case CrossClass::kTransliteration:
          if (*loanword > 0 || *translit >= 1) continue;
          break;
        case CrossClass::kPinyin:
          continue;
      }
    }
    if (SeenSurface(entry.surface, seen)) continue;
    const bool made_up = entry.kind == CrossClass::kTransliteration;
    const int32_t penalty =
        (kind == EdgeKind::kPinyinJa ? costs.pinyin_to_japanese : costs.english_to_japanese) +
        (made_up ? costs.transliteration : 0);
    lattice->Add({.begin = static_cast<uint16_t>(begin),
                  .end = static_cast<uint16_t>(end),
                  .kind = kind,
                  .cost = entry.cost + costs.japanese_word + options.Cross(penalty) + hint_cost +
                          extra_cost,
                  .transliteration = made_up,
                  .text = std::string(entry.surface),
                  .reading = std::string(entry.reading)});
    if (kind == EdgeKind::kPinyinJa) {
      ++(*pinyin);
    } else if (entry.kind == CrossClass::kSemantic) {
      ++(*semantic);
    } else if (entry.kind == CrossClass::kLoanword) {
      ++(*loanword);
    } else if (entry.kind == CrossClass::kTransliteration) {
      ++(*translit);
    }
  }
  return *loanword > 0;
}

/// Appends candidate singular stems for common English plurals (length >= 4) without allocating
/// when none applies.
void PluralStems(std::string_view key, std::vector<std::string>* out) {
  out->clear();
  if (key.size() < 4 || key.back() != 's') return;
  if (key.size() >= 5 && key.ends_with("ies")) {
    std::string stem(key.substr(0, key.size() - 3));
    stem.push_back('y');
    out->push_back(std::move(stem));
    return;
  }
  if (key.ends_with("ss") || key.ends_with("us") || key.ends_with("is")) return;
  if (key.ends_with("es") && key.size() >= 4) {
    out->emplace_back(key.substr(0, key.size() - 1));
    if (key.size() >= 4) out->emplace_back(key.substr(0, key.size() - 2));
    return;
  }
  out->emplace_back(key.substr(0, key.size() - 1));
}

}  // namespace

void ProposeCross(const Input& input, const CrossLexicon& lexicon, const Options& options,
                  Lattice* lattice) {
  const bool english = options.english_to_kanji || options.english_to_katakana;
  if (!options.pinyin_to_japanese && !english) return;
  const std::string_view letters = input.letters;
  std::vector<CrossEntry> entries;
  std::vector<std::string> seen;
  std::vector<std::string> stems;
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
        seen.clear();
        size_t semantic = 0, loanword = 0, translit = 0, pinyin = 0;
        AddEntries(entries, EdgeKind::kPinyinJa, begin, end, whole, hint_cost, 0, options, &seen,
                   &semantic, &loanword, &translit, &pinyin, lattice);
      }
      // Two or three letters inside a longer input are far more often a chance split than an
      // English word meant as Japanese (`fune` is not fun + へ): only the whole input may be
      // one, as for the English proposer's short words and the one-syllable pinyin keys.
      if (english && length > 1 && (whole || length > kShortKey)) {
        lexicon.EnJa(key, &entries);
        seen.clear();
        size_t semantic = 0, loanword = 0, translit = 0, pinyin = 0;
        AddEntries(entries, EdgeKind::kEnJa, begin, end, whole, hint_cost, 0, options, &seen,
                   &semantic, &loanword, &translit, &pinyin, lattice);
        if (loanword == 0 && length >= 4 && options.english_to_katakana) {
          PluralStems(key, &stems);
          for (const std::string& stem : stems) {
            lexicon.EnJa(stem, &entries);
            AddEntries(entries, EdgeKind::kEnJa, begin, end, whole, hint_cost,
                       options.costs.english_plural_stem, options, &seen, &semantic, &loanword,
                       &translit, &pinyin, lattice);
            if (loanword > 0) break;
          }
        }
      }
    }
  }
}

}  // namespace kotomo::mixed
