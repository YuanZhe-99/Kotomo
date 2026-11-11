#include "coordinator.h"

#include <algorithm>
#include <unordered_set>

#include "decode.h"
#include "finish.h"
#include "input.h"
#include "lattice.h"
#include "propose.h"

namespace kotomo::mixed {

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
  for (const Path& path : Decode(lattice, Lang::kChinese, options_)) {
    FinishChinese(*input, path, engines_, options_, &found);
  }
  for (const Path& path : Decode(lattice, Lang::kJapanese, options_)) {
    FinishJapanese(*input, path, engines_, options_, &found);
  }
  for (const Path& path : Decode(lattice, Lang::kEnglish, options_)) {
    FinishEnglish(*input, path, &found);
  }

  std::stable_sort(found.begin(), found.end(),
                   [](const Candidate& a, const Candidate& b) { return a.cost < b.cost; });
  std::unordered_set<std::string> seen;
  for (Candidate& candidate : found) {
    if (snapshot.candidates.size() == options_.candidates) break;
    if (!seen.insert(candidate.text).second) continue;
    snapshot.candidates.push_back(std::move(candidate));
  }
  return snapshot;
}

}  // namespace kotomo::mixed
