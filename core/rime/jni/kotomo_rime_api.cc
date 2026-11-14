// The coordinator's questions to librime, answered over the bridge; see kotomo_rime_api.h.

#include "kotomo_rime_api.h"

#include <algorithm>
#include <string>
#include <string_view>
#include <vector>

#include "rime_bridge.h"
#include "rime_spellings.h"

namespace {

namespace rb = kotomo::rime_bridge;

/// Counts the code points of UTF-8 text.
size_t CodePoints(std::string_view text) {
  size_t n = 0;
  for (const char c : text) n += (static_cast<unsigned char>(c) & 0xc0) != 0x80 ? 1 : 0;
  return n;
}

size_t Spellings(const char* letters, size_t size, KotomoRimeSpelling* out, size_t capacity) {
  static std::vector<rb::SpellingMatch> matches;  // engine thread only; reused across keys
  rb::Spellings(std::string_view(letters, size), &matches);
  const size_t count = std::min(matches.size(), capacity);
  for (size_t i = 0; i < count; ++i) {
    out[i] = {.length = static_cast<uint8_t>(matches[i].length),
              .fuzzy = static_cast<uint8_t>(matches[i].fuzzy ? 1 : 0),
              .partial = static_cast<uint8_t>(matches[i].partial ? 1 : 0)};
  }
  return count;
}

/// Notes: librime lists whole-run sentences and words together with words for a prefix of the
///   run; the ones that end where the run ends, with one character per syllable, convert the
///   whole run. (A prefix word may have as many characters as the run has syllables: after the
///   words of `shi'jian` librime lists 实际 for `shi'ji`.)
void Convert(const char* syllables, size_t size, size_t limit, void* context,
             KotomoRimeWordSink sink) {
  const std::string run(syllables, size);
  const size_t count = 1 + static_cast<size_t>(std::count(run.begin(), run.end(), '\''));
  rb::Clear();
  if (!rb::SetInput(run)) return;
  size_t passed = 0;
  for (const rb::Candidate& candidate : rb::Candidates(limit * 8)) {
    if (passed == limit) break;
    // Corrected readings stay out: the coordinator has its own key correction, placed by cost.
    if (!candidate.whole || candidate.corrected || CodePoints(candidate.text) != count) continue;
    sink(context, candidate.text.data(), candidate.text.size(), candidate.type == "sentence");
    ++passed;
  }
  rb::Clear();
}

const KotomoRimeApi kApi = {
    .version = KOTOMO_RIME_API_VERSION,
    .spellings = Spellings,
    .convert = Convert,
};

}  // namespace

extern "C" const KotomoRimeApi* KotomoRimeGetApi(void) { return &kApi; }
