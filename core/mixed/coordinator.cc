#include "coordinator.h"

#include <algorithm>
#include <unordered_set>

#include "decode.h"
#include "finish.h"
#include "input.h"
#include "lattice.h"
#include "propose.h"

namespace kotomo::mixed {

namespace {

/// Applies "original language first" (AGENTS.md, behavior contract): when the cheapest candidate
/// is cross-language and another candidate is written in a language its cross-language pieces
/// were typed in, the best such candidate moves to the front.
/// Notes: a cross-language sentence with no reading in the typed language stays first: there is
///   no original-language candidate to prefer.
void OriginalLanguageFirst(std::vector<Candidate>* candidates) {
  if (candidates->empty() || !(candidates->front().flags & kCross)) return;
  bool typed[3] = {false, false, false};
  for (const Piece& piece : candidates->front().pieces) {
    if (piece.origin == Origin::kPinyinJa || piece.origin == Origin::kEnJa) {
      typed[static_cast<int>(piece.typed)] = true;
    }
  }
  const auto original =
      std::find_if(candidates->begin(), candidates->end(), [&typed](const Candidate& c) {
        return !(c.flags & kCross) && typed[static_cast<int>(c.target)];
      });
  if (original != candidates->end()) std::rotate(candidates->begin(), original, original + 1);
}

}  // namespace

Snapshot Coordinator::Convert(std::string_view composition, uint64_t generation) {
  Snapshot snapshot{.generation = generation, .stage = 0, .input = {}, .cursor = 0,
                    .candidates = {}};
  const auto input = ParseInput(composition);
  if (!input) {
    // Still the app's composition, cut to what the snapshot can address.
    snapshot.input = std::string(composition.substr(0, kMaxInput));
    snapshot.cursor = static_cast<uint16_t>(snapshot.input.size());
    return snapshot;
  }
  snapshot.input = input->raw;
  snapshot.cursor = static_cast<uint16_t>(input->raw.size());

  Lattice lattice(input->size());
  ProposePinyin(*input, engines_, options_, &lattice);
  ProposeJapanese(*input, engines_, options_, &lattice);
  ProposeEnglish(*input, engines_, options_, &lattice);
  ProposeCross(*input, lexicon_, options_, &lattice);

  std::vector<Candidate> found;
  const auto wanted = [this](Lang lang) { return options_.targets[static_cast<int>(lang)]; };
  if (wanted(Lang::kChinese)) {
    for (const Path& path : Decode(lattice, Lang::kChinese, options_)) {
      FinishChinese(*input, path, engines_, options_, &found);
    }
  }
  if (wanted(Lang::kJapanese)) {
    for (const Path& path : Decode(lattice, Lang::kJapanese, options_)) {
      FinishJapanese(*input, path, engines_, options_, &found);
    }
  }
  if (wanted(Lang::kEnglish)) {
    for (const Path& path : Decode(lattice, Lang::kEnglish, options_)) {
      FinishEnglish(*input, path, &found);
    }
  }

  for (Candidate& candidate : found) candidate.cost += options_.bias[static_cast<int>(candidate.target)];
  std::stable_sort(found.begin(), found.end(),
                   [](const Candidate& a, const Candidate& b) { return a.cost < b.cost; });
  std::unordered_set<std::string> seen;
  for (Candidate& candidate : found) {
    if (snapshot.candidates.size() == options_.candidates) break;
    if (!seen.insert(candidate.text).second) continue;
    snapshot.candidates.push_back(std::move(candidate));
  }
  if (options_.level != Level::kAggressive) OriginalLanguageFirst(&snapshot.candidates);
  return snapshot;
}

}  // namespace kotomo::mixed
