// The proposers (PLAN.md §5.2): each reads the typed letters one way and adds its edges to the
// lattice. The emoji and the symbol proposers are not part of spike S6.

#ifndef KOTOMO_CORE_MIXED_PROPOSE_H_
#define KOTOMO_CORE_MIXED_PROPOSE_H_

#include "cross_lexicon.h"
#include "engines.h"
#include "input.h"
#include "lattice.h"
#include "options.h"

namespace kotomo::mixed {

/// Adds one kPinyin edge for every syllable librime's spelling table finds at every position,
/// and for an unfinished syllable (`r`, `xio`) at the end of the input, at `Costs::unfinished`
/// more: what is still being typed converts as it does in Chinese mode.
/// Side effects: one Engines::Spellings call per position.
void ProposePinyin(const Input& input, Engines& engines, const Options& options, Lattice* lattice);

/// Adds kKana edges for the shortest romaji spellings Mozc's table maps to kana (one to four
/// letters; `kai` is ka + i, not an edge of its own), and kParticle edges for wa, o and e after
/// the first position. The last one to three letters that begin a kana without finishing one
/// (`r`, `ky`, `sh`) get a kPending edge, unless the input starts with them.
/// Side effects: Engines::Hiragana calls, a bounded number per position.
void ProposeJapanese(const Input& input, Engines& engines, const Options& options,
                     Lattice* lattice);

/// Adds kEnglish edges for every span of two or more letters that is a word of the English
/// dictionary above the probability floor (a higher one for two and three letters inside a longer
/// input, where possibly offensive words are not proposed either): the spelling
/// as typed when the dictionary has it, otherwise the most common one, and the other spellings
/// (`Apple` for apple) at `Costs::english_recased` more.
/// Side effects: Engines::English calls.
void ProposeEnglish(const Input& input, Engines& engines, const Options& options,
                    Lattice* lattice);

/// Adds kPinyinJa and kEnJa edges for the spans that are keys of the cross-language lexicon, the
/// Options::cross_words cheapest words per span and direction. An edge costs Mozc's word cost of
/// the entry, plus `Costs::japanese_word`, plus the direction's penalty scaled by the level.
/// Notes: honors the pinyin → Japanese, English → Japanese and transliteration switches; a
///   one-syllable pinyin key and an English key of two or three letters are proposed only as
///   the whole input.
void ProposeCross(const Input& input, const CrossLexicon& lexicon, const Options& options,
                  Lattice* lattice);

}  // namespace kotomo::mixed

#endif  // KOTOMO_CORE_MIXED_PROPOSE_H_
