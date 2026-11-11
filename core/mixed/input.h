// The coordinator's input model (PLAN.md §5.1): the letters typed, with the delimiters kept as
// boundary hints rather than as characters to convert.

#ifndef KOTOMO_CORE_MIXED_INPUT_H_
#define KOTOMO_CORE_MIXED_INPUT_H_

#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace kotomo::mixed {

/// Holds one composition split into letters and boundary hints.
///
/// Notes: lattice positions count letters, 0 to `size()`; `raw` offsets map them back to the
/// composition the app shows, so pieces of a candidate name ranges of what was typed.
struct Input {
  std::string raw;      // the composition as typed: letters, spaces and apostrophes
  std::string letters;  // the letters only, in lower case
  std::vector<uint16_t> offset;  // offset[i]: byte of letter i in `raw`; offset[size()] = raw.size()
  std::vector<bool> hint;        // hint[i]: a delimiter was typed just before letter i (0 < i < size())

  /// Returns: the number of letters, which is the last lattice position.
  size_t size() const { return letters.size(); }

  /// Returns: the range of `raw` that the letters [begin, end) cover, without the delimiters
  ///   around it. Inputs: begin < end <= size().
  std::pair<uint16_t, uint16_t> Range(size_t begin, size_t end) const {
    return {offset[begin], static_cast<uint16_t>(offset[end - 1] + 1)};
  }

  /// Returns: how many boundary hints fall strictly inside the letters [begin, end): a span that
  ///   crosses a delimiter goes against what the person marked.
  int HintsInside(size_t begin, size_t end) const;
};

/// The longest composition the coordinator converts, in bytes.
inline constexpr size_t kMaxInput = 256;

/// Splits a composition into letters and boundary hints.
/// Inputs: `raw` holds ASCII letters, spaces and apostrophes; capitals are read as lower case.
/// Returns: nullopt for an empty composition, one without letters, one longer than kMaxInput or
///   one with any other character (digits and punctuation are not part of a composition yet).
std::optional<Input> ParseInput(std::string_view raw);

}  // namespace kotomo::mixed

#endif  // KOTOMO_CORE_MIXED_INPUT_H_
