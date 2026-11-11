#include "propose.h"

namespace kotomo::mixed {

void ProposePinyin(const Input& input, Engines& engines, const Options& options,
                   Lattice* lattice) {
  const Costs& costs = options.costs;
  std::vector<Spelling> spellings;
  for (size_t begin = 0; begin < input.size(); ++begin) {
    engines.Spellings(std::string_view(input.letters).substr(begin), &spellings);
    for (const Spelling& spelling : spellings) {
      const size_t end = begin + spelling.length;
      if (spelling.length == 0 || end > input.size()) continue;
      lattice->Add({.begin = static_cast<uint16_t>(begin),
                    .end = static_cast<uint16_t>(end),
                    .kind = EdgeKind::kPinyin,
                    .cost = costs.pinyin_syllable + (spelling.fuzzy ? costs.fuzzy : 0) +
                            costs.crossed_hint * input.HintsInside(begin, end),
                    .fuzzy = spelling.fuzzy,
                    .text = input.letters.substr(begin, spelling.length),
                    .reading = {}});
    }
  }
}

}  // namespace kotomo::mixed
