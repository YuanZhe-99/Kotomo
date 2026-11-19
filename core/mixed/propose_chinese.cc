#include "propose.h"

#include <algorithm>

namespace kotomo::mixed {

void ProposePinyin(const Input& input, Engines& engines, const Options& options,
                   Lattice* lattice) {
  const Costs& costs = options.costs;
  std::vector<Spelling> spellings;
  for (size_t begin = 0; begin < input.size(); ++begin) {
    engines.Spellings(std::string_view(input.letters).substr(begin), &spellings);
    // The longest whole syllable from here: an initial is not read as an abbreviation where
    // its letters go on to spell one (`n` of `nai`), or every syllable would also split.
    size_t whole = 0;
    for (const Spelling& spelling : spellings) {
      if (!spelling.partial && begin + spelling.length <= input.size()) {
        whole = std::max<size_t>(whole, spelling.length);
      }
    }
    for (const Spelling& spelling : spellings) {
      const size_t end = begin + spelling.length;
      if (spelling.length == 0 || end > input.size()) continue;
      // An unfinished syllable is read where typing stopped: librime completes it. Before
      // that, only as an abbreviation (`t` of `tq`), and only when the person allows those.
      if (spelling.partial && end != input.size() &&
          (!options.abbreviation || spelling.length < whole)) {
        continue;
      }
      lattice->Add({.begin = static_cast<uint16_t>(begin),
                    .end = static_cast<uint16_t>(end),
                    .kind = EdgeKind::kPinyin,
                    .cost = costs.pinyin_syllable + (spelling.fuzzy ? costs.fuzzy : 0) +
                            (spelling.partial ? costs.unfinished : 0) +
                            costs.crossed_hint * input.HintsInside(begin, end),
                    .fuzzy = spelling.fuzzy,
                    .partial = spelling.partial,
                    .text = input.letters.substr(begin, spelling.length),
                    .reading = {}});
    }
  }
}

}  // namespace kotomo::mixed
