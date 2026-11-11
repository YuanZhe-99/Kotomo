#include <algorithm>

#include "propose.h"

namespace kotomo::mixed {
namespace {

/// The longest English word looked up, in letters.
constexpr size_t kMaxWord = 24;

}  // namespace

void ProposeEnglish(const Input& input, Engines& engines, const Options& options,
                    Lattice* lattice) {
  const Costs& costs = options.costs;
  const std::string_view letters = input.letters;
  for (size_t begin = 0; begin < input.size(); ++begin) {
    const size_t longest = std::min(input.size() - begin, kMaxWord);
    for (size_t length = 2; length <= longest; ++length) {
      const size_t end = begin + length;
      const std::vector<EnglishForm> forms = engines.English(letters.substr(begin, length));
      if (forms.empty() || forms.front().probability < costs.english_min_probability) continue;
      const EnglishForm& form = forms.front();
      lattice->Add({.begin = static_cast<uint16_t>(begin),
                    .end = static_cast<uint16_t>(end),
                    .kind = EdgeKind::kEnglish,
                    .cost = costs.english_base +
                            costs.english_step * (255 - std::min(form.probability, 255)) +
                            costs.crossed_hint * input.HintsInside(begin, end),
                    .text = form.text,
                    .reading = {}});
    }
  }
}

}  // namespace kotomo::mixed
