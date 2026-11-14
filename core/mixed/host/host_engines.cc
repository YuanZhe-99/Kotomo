#include "host_engines.h"

#include <algorithm>
#include <filesystem>
#include <system_error>
#include <unordered_map>

#include "dictionary/structure/dictionary_structure_with_buffer_policy_factory.h"
#include "english_engine.h"
#include "kotomo_rime_api.h"
#include "mozc_client.h"
#include "rime_bridge.h"

namespace kotomo::mixed::host {
namespace {

namespace rb = kotomo::rime_bridge;
using Policy = latinime::DictionaryStructureWithBufferPolicy;

/// The most syllables one prefix can spell; the schema's longest syllable has six letters.
constexpr size_t kMaxSpellings = 16;

/// The host's engines; see Engines for what each method answers. librime and the English
/// dictionary answer through the same code the device calls (kotomo_rime_api.h, english_engine.h).
class HostEngines : public Engines {
 public:
  HostEngines(Policy::StructurePolicyPtr english, std::unique_ptr<MozcClient> mozc)
      : english_(std::move(english)), mozc_(std::move(mozc)) {}

  ~HostEngines() override { rb::Shutdown(); }

  void Spellings(std::string_view letters, std::vector<Spelling>* out) override {
    KotomoRimeSpelling found[kMaxSpellings];
    const size_t count = rime_->spellings(letters.data(), letters.size(), found, kMaxSpellings);
    out->clear();
    for (size_t i = 0; i < count; ++i) {
      out->push_back({.length = found[i].length, .fuzzy = found[i].fuzzy != 0,
                      .partial = found[i].partial != 0});
    }
  }

  std::vector<ChineseWord> Chinese(std::string_view syllables, size_t limit) override {
    std::vector<ChineseWord> out;
    rime_->convert(syllables.data(), syllables.size(), limit, &out,
                   [](void* context, const char* text, size_t size, int composed) {
                     static_cast<std::vector<ChineseWord>*>(context)->push_back(
                         {.text = std::string(text, size), .composed = composed != 0});
                   });
    return out;
  }

  /// Notes: remembers every answer; the proposer asks about the same short spellings again and
  ///   again, and each question is a round trip to the service.
  std::string Hiragana(std::string_view romaji) override {
    const std::string key(romaji);
    const auto found = kana_.find(key);
    if (found != kana_.end()) return found->second;
    return kana_[key] = mozc_->Hiragana(key);
  }

  int32_t Known(std::string_view reading) override { return mozc_->Known(std::string(reading)); }

  std::optional<std::vector<JapaneseSegment>> Japanese(
      const std::vector<JapaneseSpan>& spans, bool /*incognito*/) override {
    return mozc_->Convert(spans);
  }

  std::vector<EnglishForm> English(std::string_view word) override {
    std::vector<EnglishForm> forms;
    kotomo::english::Lookup(
        english_.get(), word, &forms,
        [](void* context, const char* text, size_t size, int probability, int sensitive) {
          static_cast<std::vector<EnglishForm>*>(context)->push_back(
              {.text = std::string(text, size), .probability = probability,
               .sensitive = sensitive != 0});
        });
    return forms;
  }

 private:
  const KotomoRimeApi* rime_ = KotomoRimeGetApi();
  Policy::StructurePolicyPtr english_;
  std::unique_ptr<MozcClient> mozc_;
  std::unordered_map<std::string, std::string> kana_;
};

}  // namespace

std::unique_ptr<Engines> Open(const Paths& paths, std::string* error) {
  if (!rb::Initialize(paths.rime_shared, paths.rime_user) || !rb::SetLearning(false)) {
    *error = "librime: no session on " + paths.rime_shared + " (run scripts/prebuild-rime.sh)";
    return nullptr;
  }
  rb::SetOption("zh_hans", true);
  std::error_code missing;
  const auto dict_size = std::filesystem::file_size(paths.english_dict, missing);
  Policy::StructurePolicyPtr english;
  if (!missing && dict_size > 0 && dict_size < (1u << 30)) {
    english = latinime::DictionaryStructureWithBufferPolicyFactory::newPolicyForExistingDictFile(
        paths.english_dict.c_str(), 0, static_cast<int>(dict_size), false /* isUpdatable */);
  }
  if (!english) {
    *error = "English: cannot read " + paths.english_dict + " (run java tool/BuildEnglishDict.java)";
    return nullptr;
  }
  auto mozc = MozcClient::Start(paths.mozc_service, paths.mozc_data, paths.mozc_profile);
  if (!mozc) {
    *error = "Mozc: cannot start " + paths.mozc_service + " (run scripts/build-mozc.sh --abi host)";
    return nullptr;
  }
  return std::make_unique<HostEngines>(std::move(english), std::move(mozc));
}

}  // namespace kotomo::mixed::host
