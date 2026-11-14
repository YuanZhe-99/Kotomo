// The coordinator's questions to Mozc, answered by the glue; see
// kotomo_mozc_api.h.

#include "kotomo/kotomo_mozc_api.h"

#include <algorithm>
#include <cstring>
#include <string>
#include <string_view>
#include <vector>

#include "kotomo/kotomo_mozc.h"

namespace kotomo::mozc {
namespace {

size_t Hiragana(void* engine, const char* romaji, size_t size, char* out,
                size_t capacity) {
  if (engine == nullptr) return 0;
  const std::string kana = static_cast<const MozcEngine*>(engine)->ToHiragana(
      std::string_view(romaji, size));
  std::memcpy(out, kana.data(), std::min(kana.size(), capacity));
  return kana.size();
}

int Convert(void* engine, const KotomoMozcSpan* spans, size_t count,
            int incognito, const KotomoMozcSink* sink) {
  if (engine == nullptr) return 0;
  std::vector<Span> request;
  request.reserve(count);
  for (size_t i = 0; i < count; ++i) {
    request.push_back(
        {.reading = std::string(spans[i].reading, spans[i].reading_size),
         .surface = std::string(spans[i].surface, spans[i].surface_size),
         .fixed = spans[i].fixed != 0});
  }
  const auto conversion = static_cast<const MozcEngine*>(engine)->PinnedConvert(
      request, incognito != 0);
  if (!conversion) return 0;
  for (const Segment& segment : conversion->segments) {
    sink->segment(sink->context, segment.key.data(), segment.key.size(),
                  segment.pinned ? 1 : 0);
    for (const Candidate& candidate : segment.candidates) {
      sink->word(sink->context, candidate.value.data(), candidate.value.size(),
                 candidate.key.data(), candidate.key.size(), candidate.cost);
    }
  }
  return 1;
}

const KotomoMozcApi kApi = {
    .version = KOTOMO_MOZC_API_VERSION,
    .hiragana = Hiragana,
    .convert = Convert,
};

}  // namespace
}  // namespace kotomo::mozc

extern "C" const KotomoMozcApi* KotomoMozcGetApi(void) {
  return &kotomo::mozc::kApi;
}
