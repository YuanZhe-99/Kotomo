#include "host_engines.h"

#include <algorithm>
#include <filesystem>
#include <system_error>
#include <unordered_map>

#include "dictionary/property/word_attributes.h"
#include "dictionary/structure/dictionary_structure_with_buffer_policy_factory.h"
#include "mozc_client.h"
#include "rime_bridge.h"
#include "rime_spellings.h"
#include "utils/int_array_view.h"

namespace kotomo::mixed::host {
namespace {

namespace rb = kotomo::rime_bridge;
using Policy = latinime::DictionaryStructureWithBufferPolicy;

/// Counts the code points of UTF-8 text.
size_t CodePoints(std::string_view text) {
  size_t n = 0;
  for (const char c : text) n += (static_cast<unsigned char>(c) & 0xc0) != 0x80 ? 1 : 0;
  return n;
}

/// The host's engines; see Engines for what each method answers.
class HostEngines : public Engines {
 public:
  HostEngines(Policy::StructurePolicyPtr english, std::unique_ptr<MozcClient> mozc)
      : english_(std::move(english)), mozc_(std::move(mozc)) {}

  ~HostEngines() override {
    rb::CloseSpellings();
    rb::Shutdown();
  }

  void Spellings(std::string_view letters, std::vector<Spelling>* out) override {
    rb::Spellings(letters, &matches_);
    out->clear();
    for (const rb::SpellingMatch& match : matches_) {
      out->push_back({.length = static_cast<uint8_t>(match.length), .fuzzy = match.fuzzy});
    }
  }

  /// Notes: librime lists whole-run sentences and words together with words for a prefix of the
  ///   run; the ones with one character per syllable convert the whole run.
  std::vector<ChineseWord> Chinese(std::string_view syllables, size_t limit) override {
    const size_t count =
        1 + static_cast<size_t>(std::count(syllables.begin(), syllables.end(), '\''));
    rb::Clear();
    std::vector<ChineseWord> out;
    if (!rb::SetInput(std::string(syllables))) return out;
    for (const rb::Candidate& candidate : rb::Candidates(limit * 8)) {
      if (out.size() == limit) break;
      if (CodePoints(candidate.text) != count) continue;
      out.push_back({.text = candidate.text, .composed = candidate.type == "sentence"});
    }
    rb::Clear();
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

  std::optional<std::vector<JapaneseSegment>> Japanese(
      const std::vector<JapaneseSpan>& spans) override {
    return mozc_->Convert(spans);
  }

  std::vector<EnglishForm> English(std::string_view word) override {
    std::vector<EnglishForm> forms;
    std::string spelling(word);
    Add(spelling, &forms);
    if (spelling.empty() || spelling[0] < 'a' || spelling[0] > 'z') return forms;
    spelling[0] = static_cast<char>(spelling[0] - 'a' + 'A');
    Add(spelling, &forms);
    for (char& c : spelling) c = c >= 'a' && c <= 'z' ? static_cast<char>(c - 'a' + 'A') : c;
    if (spelling.size() > 1) Add(spelling, &forms);
    std::stable_sort(forms.begin(), forms.end(), [](const EnglishForm& a, const EnglishForm& b) {
      return a.probability > b.probability;
    });
    return forms;
  }

 private:
  /// Appends `spelling` when the dictionary holds exactly it as a word.
  void Add(const std::string& spelling, std::vector<EnglishForm>* forms) {
    const std::vector<int> points(spelling.begin(), spelling.end());
    const latinime::CodePointArrayView view(points);
    const int id = english_->getWordId(view, false /* forceLowerCaseSearch */);
    if (id == NOT_A_WORD_ID) return;
    const auto attributes = english_->getWordAttributesInContext(
        latinime::WordIdArrayView(), id, nullptr /* multiBigramMap */);
    if (attributes.isNotAWord() || attributes.isBlacklisted()) return;
    forms->push_back({.text = spelling,
                      .probability = attributes.getProbability(),
                      .sensitive = attributes.isPossiblyOffensive()});
  }

  Policy::StructurePolicyPtr english_;
  std::unique_ptr<MozcClient> mozc_;
  std::vector<rb::SpellingMatch> matches_;
  std::unordered_map<std::string, std::string> kana_;
};

}  // namespace

std::unique_ptr<Engines> Open(const Paths& paths, std::string* error) {
  if (!rb::Initialize(paths.rime_shared, paths.rime_user) || !rb::SetLearning(false)) {
    *error = "librime: no session on " + paths.rime_shared + " (run scripts/prebuild-rime.sh)";
    return nullptr;
  }
  rb::SetOption("zh_hans", true);
  if (!rb::OpenSpellings(paths.rime_shared + "/build/luna_pinyin_simp.prism.bin")) {
    *error = "librime: no spelling table in " + paths.rime_shared + "/build";
    return nullptr;
  }
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
