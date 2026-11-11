// Finishing (PLAN.md §5.4): a decoded path becomes candidates in its target language. The
// engines convert the runs typed in their language; every candidate keeps its pieces, so a later
// edit can lock or change one piece without rewriting the others (PLAN.md §5.6).

#ifndef KOTOMO_CORE_MIXED_FINISH_H_
#define KOTOMO_CORE_MIXED_FINISH_H_

#include <vector>

#include "decode.h"
#include "engines.h"
#include "input.h"
#include "options.h"
#include "snapshot.h"

namespace kotomo::mixed {

/// Finishes a Chinese path: each run of pinyin syllables goes to librime as one sentence, and
/// English words stay as written.
/// Side effects: appends to `out` the sentence and, when one run covers the whole input, up to
///   `Options::alternatives` conversions of it, each `Costs::chinese_rank` dearer than the one
///   before; appends nothing when librime converts a run to nothing. One Engines::Chinese call
///   per run.
void FinishChinese(const Input& input, const Path& path, Engines& engines, const Options& options,
                   std::vector<Candidate>* out);

/// Finishes a Japanese path: runs of kana go to Mozc as free spans, cross-language words and
/// particles as pinned spans of the same conversion, and English words stay as written between
/// conversions.
/// Side effects: appends the sentence with one piece per Mozc segment; its cost is the path's
///   with Mozc's cost of the free segments in place of the decoder's kana estimate. When the
///   whole input is one free run that Mozc keeps as one segment, the segment's other words follow
///   as alternatives. Appends nothing when Mozc converts nothing. One Engines::Japanese call per
///   stretch between English words.
void FinishJapanese(const Input& input, const Path& path, Engines& engines, const Options& options,
                    std::vector<Candidate>* out);

/// Finishes an English path: the words as the dictionary writes them, separated by spaces.
/// Side effects: appends one candidate.
void FinishEnglish(const Input& input, const Path& path, std::vector<Candidate>* out);

/// Builds the piece for the letters [begin, end) of the input.
Piece MakePiece(const Input& input, size_t begin, size_t end, std::string text, Lang typed,
                Origin origin, bool pinned);

/// Returns: the candidate flags a path's edges imply (kSentence always; kCross, kFuzzy).
uint8_t PathFlags(const Path& path);

}  // namespace kotomo::mixed

#endif  // KOTOMO_CORE_MIXED_FINISH_H_
