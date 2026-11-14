#include "api_engines.h"

namespace kotomo::mixed::device {
namespace {

/// The most syllables one prefix can spell; the schema's longest syllable has six letters.
constexpr size_t kMaxSpellings = 16;

}  // namespace

std::unique_ptr<ApiEngines> ApiEngines::Create(const Tables& tables) {
  if (tables.rime != nullptr && tables.rime->version != KOTOMO_RIME_API_VERSION) return nullptr;
  if (tables.mozc != nullptr &&
      (tables.mozc->version != KOTOMO_MOZC_API_VERSION || tables.mozc_engine == nullptr)) {
    return nullptr;
  }
  if (tables.english != nullptr &&
      (tables.english->version != KOTOMO_ENGLISH_API_VERSION || tables.english_engine == nullptr)) {
    return nullptr;
  }
  return std::unique_ptr<ApiEngines>(new ApiEngines(tables));
}

void ApiEngines::Spellings(std::string_view letters, std::vector<Spelling>* out) {
  out->clear();
  if (tables_.rime == nullptr) return;
  KotomoRimeSpelling found[kMaxSpellings];
  const size_t count =
      tables_.rime->spellings(letters.data(), letters.size(), found, kMaxSpellings);
  for (size_t i = 0; i < count; ++i) {
    out->push_back({.length = found[i].length, .fuzzy = found[i].fuzzy != 0,
                      .partial = found[i].partial != 0});
  }
}

std::vector<ChineseWord> ApiEngines::Chinese(std::string_view syllables, size_t limit) {
  std::vector<ChineseWord> out;
  if (tables_.rime == nullptr) return out;
  tables_.rime->convert(syllables.data(), syllables.size(), limit, &out,
                        [](void* context, const char* text, size_t size, int composed) {
                          static_cast<std::vector<ChineseWord>*>(context)->push_back(
                              {.text = std::string(text, size), .composed = composed != 0});
                        });
  return out;
}

std::string ApiEngines::Hiragana(std::string_view romaji) {
  // Without Mozc the letters stay letters, which the proposer reads as "no kana".
  if (tables_.mozc == nullptr) return std::string(romaji);
  std::string key(romaji);
  const auto found = kana_.find(key);
  if (found != kana_.end()) return found->second;
  std::string kana(64, '\0');
  size_t size = tables_.mozc->hiragana(tables_.mozc_engine, romaji.data(), romaji.size(),
                                       kana.data(), kana.size());
  if (size > kana.size()) {
    kana.resize(size);
    size = tables_.mozc->hiragana(tables_.mozc_engine, romaji.data(), romaji.size(), kana.data(),
                                  kana.size());
  }
  kana.resize(std::min(size, kana.size()));
  if (kana_.size() >= kKanaMemory) kana_.clear();
  kana_.emplace(std::move(key), kana);
  return kana;
}

std::optional<std::vector<JapaneseSegment>> ApiEngines::Japanese(
    const std::vector<JapaneseSpan>& spans, bool incognito) {
  if (tables_.mozc == nullptr) return std::nullopt;
  std::vector<KotomoMozcSpan> request;
  request.reserve(spans.size());
  for (const JapaneseSpan& span : spans) {
    request.push_back({span.reading.data(), span.reading.size(), span.surface.data(),
                       span.surface.size()});
  }
  std::vector<JapaneseSegment> segments;
  const KotomoMozcSink sink = {
      .context = &segments,
      .segment =
          [](void* context, const char* reading, size_t size, int pinned) {
            static_cast<std::vector<JapaneseSegment>*>(context)->push_back(
                {.reading = std::string(reading, size), .pinned = pinned != 0, .words = {}});
          },
      .word =
          [](void* context, const char* text, size_t text_size, const char* reading,
             size_t reading_size, int32_t cost) {
            static_cast<std::vector<JapaneseSegment>*>(context)->back().words.push_back(
                {.text = std::string(text, text_size),
                 .reading = std::string(reading, reading_size),
                 .cost = cost});
          },
  };
  if (tables_.mozc->convert(tables_.mozc_engine, request.data(), request.size(),
                            incognito ? 1 : 0, &sink) == 0) {
    return std::nullopt;
  }
  return segments;
}

std::vector<EnglishForm> ApiEngines::English(std::string_view word) {
  std::vector<EnglishForm> forms;
  if (tables_.english == nullptr) return forms;
  tables_.english->lookup(
      tables_.english_engine, word.data(), word.size(), &forms,
      [](void* context, const char* text, size_t size, int probability, int sensitive) {
        static_cast<std::vector<EnglishForm>*>(context)->push_back(
            {.text = std::string(text, size), .probability = probability,
             .sensitive = sensitive != 0});
      });
  return forms;
}

}  // namespace kotomo::mixed::device
