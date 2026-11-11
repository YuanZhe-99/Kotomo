#include "input.h"

namespace kotomo::mixed {

int Input::HintsInside(size_t begin, size_t end) const {
  int hints = 0;
  for (size_t i = begin + 1; i < end; ++i) hints += hint[i] ? 1 : 0;
  return hints;
}

std::optional<Input> ParseInput(std::string_view raw) {
  if (raw.empty() || raw.size() > kMaxInput) return std::nullopt;
  Input input;
  input.raw = std::string(raw);
  bool delimiter = false;
  for (size_t i = 0; i < raw.size(); ++i) {
    char c = raw[i];
    if (c == ' ' || c == '\'') {
      delimiter = true;
      continue;
    }
    if (c >= 'A' && c <= 'Z') c = static_cast<char>(c - 'A' + 'a');
    if (c < 'a' || c > 'z') return std::nullopt;
    input.hint.push_back(delimiter && !input.letters.empty());
    input.offset.push_back(static_cast<uint16_t>(i));
    input.letters.push_back(c);
    delimiter = false;
  }
  if (input.letters.empty()) return std::nullopt;
  input.offset.push_back(static_cast<uint16_t>(raw.size()));
  input.hint.push_back(false);
  return input;
}

}  // namespace kotomo::mixed
