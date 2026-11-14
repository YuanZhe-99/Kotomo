// The three engines behind the coordinator's Engines interface, as the app runs them: each
// question is one call through a table of C functions that the engine's own library handed over
// (kotomo_rime_api.h, kotomo_mozc_api.h, english_api.h). No symbol of another library is linked.

#ifndef KOTOMO_CORE_MIXED_DEVICE_API_ENGINES_H_
#define KOTOMO_CORE_MIXED_DEVICE_API_ENGINES_H_

#include <memory>
#include <string>
#include <unordered_map>

#include "engines.h"
#include "english_api.h"
#include "kotomo_mozc_api.h"
#include "kotomo_rime_api.h"

namespace kotomo::mixed::device {

/// Names the engines' tables and handles; a null table means that engine is not there (a build
/// without Mozc, a dictionary that did not open), and its questions then find nothing.
struct Tables {
  const KotomoRimeApi* rime = nullptr;
  const KotomoMozcApi* mozc = nullptr;
  void* mozc_engine = nullptr;      // the handle Mozc.kt holds
  const KotomoEnglishApi* english = nullptr;
  void* english_engine = nullptr;   // the handle English.kt holds
};

/// Answers the coordinator's questions through the engines' tables.
///
/// Notes: engine thread only. The tables and handles must stay valid for its lifetime: the app
/// closes the coordinator before it closes an engine.
class ApiEngines : public Engines {
 public:
  /// Returns: nullptr when a table that is present has another version than this library was
  ///   built against, or a table comes without its engine handle.
  static std::unique_ptr<ApiEngines> Create(const Tables& tables);

  void Spellings(std::string_view letters, std::vector<Spelling>* out) override;
  std::vector<ChineseWord> Chinese(std::string_view syllables, size_t limit) override;
  /// Notes: remembers its answers (the proposer asks about the same short spellings with every
  ///   key); the memory is dropped whole when it holds kKanaMemory spellings.
  std::string Hiragana(std::string_view romaji) override;
  int32_t Known(std::string_view reading) override;
  std::optional<std::vector<JapaneseSegment>> Japanese(const std::vector<JapaneseSpan>& spans,
                                                       bool incognito) override;
  std::vector<EnglishForm> English(std::string_view word) override;

  /// The most romaji spellings remembered.
  static constexpr size_t kKanaMemory = 4096;

 private:
  explicit ApiEngines(const Tables& tables) : tables_(tables) {}

  Tables tables_;
  std::unordered_map<std::string, std::string> kana_;
};

}  // namespace kotomo::mixed::device

#endif  // KOTOMO_CORE_MIXED_DEVICE_API_ENGINES_H_
