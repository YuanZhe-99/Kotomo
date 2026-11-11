#include <algorithm>

#include "propose.h"

namespace kotomo::mixed {
namespace {

/// The longest English word looked up, in letters.
constexpr size_t kMaxWord = 24;

}  // namespace

namespace {

/// Returns: what an English word costs, by the probability of its most common spelling.
int32_t EnglishCost(const std::vector<EnglishForm>& forms, const Costs& costs) {
  return costs.english_base +
         costs.english_step * (255 - std::min(forms.front().probability, 255));
}

}  // namespace

void ProposeEnglish(const Input& input, Engines& engines, const Options& options,
                    Lattice* lattice) {
  const Costs& costs = options.costs;
  const std::string_view letters = input.letters;
  for (size_t begin = 0; begin < input.size(); ++begin) {
    const size_t longest = std::min(input.size() - begin, kMaxWord);
    for (size_t length = 2; length <= longest; ++length) {
      const size_t end = begin + length;
      const std::string_view typed = letters.substr(begin, length);
      std::vector<EnglishForm> forms = engines.English(typed);
      // Inside a longer input a short or possibly offensive word is more likely a chance split
      // of something else: only the whole input may be one.
      const bool whole = begin == 0 && end == input.size();
      const int floor = !whole && length <= 3 ? costs.english_short_probability
                                              : costs.english_min_probability;
      std::erase_if(forms, [whole, floor](const EnglishForm& f) {
        return f.probability < floor || (f.sensitive && !whole);
      });
      if (forms.empty()) continue;
      // The word as typed comes first when the dictionary has that spelling; otherwise its most
      // common spelling stands for it (`ai` is AI). Other spellings pay for the change of case.
      const bool has_typed = std::any_of(forms.begin(), forms.end(), [typed](const EnglishForm& f) {
        return f.text == typed;
      });
      const int32_t cost = EnglishCost(forms, costs) +
                           costs.crossed_hint * input.HintsInside(begin, end);
      for (size_t i = 0; i < forms.size(); ++i) {
        const bool as_typed = has_typed ? forms[i].text == typed : i == 0;
        lattice->Add({.begin = static_cast<uint16_t>(begin),
                      .end = static_cast<uint16_t>(end),
                      .kind = EdgeKind::kEnglish,
                      .cost = cost + (as_typed ? 0 : costs.english_recased),
                      .text = forms[i].text,
                      .reading = {}});
      }
    }
  }
}

}  // namespace kotomo::mixed
