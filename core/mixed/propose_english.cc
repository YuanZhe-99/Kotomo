#include <algorithm>

#include "propose.h"

namespace kotomo::mixed {
namespace {

/// The longest English word looked up, in letters.
constexpr size_t kMaxWord = 24;

/// Words that may stand inside a longer input although the rules below would keep them to the
/// whole input. Each entry needs its reason here; the upstream word list itself is never edited.
///   AI: the upstream list (aosp-dictionaries en_US) marks it possibly offensive, which is a
///       mislabel, and lists it rarer than the floor for two-letter words; it is an everyday
///       word in all three languages' text (user decision, 2026-11-11).
constexpr std::string_view kAllowedInside[] = {"AI"};

/// Returns: whether `text`, as the dictionary writes it, is on the list above.
bool AllowedInside(std::string_view text) {
  return std::find(std::begin(kAllowedInside), std::end(kAllowedInside), text) !=
         std::end(kAllowedInside);
}

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
      // of something else: only the whole input may be one, except the words kAllowedInside
      // names. No word rarer than the general floor is ever proposed.
      const bool whole = begin == 0 && end == input.size();
      const int floor = !whole && length <= 3 ? costs.english_short_probability
                                              : costs.english_min_probability;
      std::erase_if(forms, [whole, floor, &costs](const EnglishForm& f) {
        if (f.probability < costs.english_min_probability) return true;
        if (whole || AllowedInside(f.text)) return false;
        return f.probability < floor || f.sensitive;
      });
      if (forms.empty()) continue;
      // Keep the cased spelling the person typed (`Power` / `POWER`) without recase penalty;
      // otherwise prefer the dictionary form matching the typed lowercase word (`apple` before
      // `Apple`), or its most common spelling (`ai` → `AI`). Other spellings pay `english_recased`.
      const auto [raw_begin, raw_end] = input.Range(begin, end);
      const std::string_view raw_word =
          input.HintsInside(begin, end) == 0
              ? std::string_view(input.raw).substr(raw_begin, raw_end - raw_begin)
              : typed;
      const bool user_cased = raw_word != typed;
      const bool has_raw = std::any_of(forms.begin(), forms.end(), [raw_word](const EnglishForm& f) {
        return f.text == raw_word;
      });
      const bool has_typed_lower = std::any_of(forms.begin(), forms.end(), [typed](const EnglishForm& f) {
        return f.text == typed;
      });
      const bool all_upper = user_cased && std::all_of(raw_word.begin(), raw_word.end(), [](unsigned char c) {
                               return c >= 'A' && c <= 'Z';
                             });
      const int32_t cost = EnglishCost(forms, costs) +
                           costs.crossed_hint * input.HintsInside(begin, end);
      const bool synthesize_raw = !has_raw && user_cased && (has_typed_lower || all_upper);
      if (synthesize_raw) {
        lattice->Add({.begin = static_cast<uint16_t>(begin),
                      .end = static_cast<uint16_t>(end),
                      .kind = EdgeKind::kEnglish,
                      .cost = cost,
                      .text = std::string(raw_word),
                      .reading = {}});
      }
      for (size_t i = 0; i < forms.size(); ++i) {
        const bool as_typed = !synthesize_raw && (has_raw ? forms[i].text == raw_word : i == 0);
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
