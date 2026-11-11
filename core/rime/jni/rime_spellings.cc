#include "rime_spellings.h"

#include <memory>

#include <rime/algo/spelling.h>
#include <rime/dict/prism.h>

namespace kotomo::rime_bridge {
namespace {

/// The longest spelling asked about; luna_pinyin's longest syllable has six letters.
constexpr std::size_t kMaxSpelling = 8;

std::unique_ptr<rime::Prism> prism;

}  // namespace

bool OpenSpellings(const std::string& prism_file) {
  auto opened = std::make_unique<rime::Prism>(rime::path(std::filesystem::path(prism_file)));
  if (!opened->Load()) return false;
  prism = std::move(opened);
  return true;
}

void Spellings(std::string_view letters, std::vector<SpellingMatch>* out) {
  out->clear();
  if (!prism || letters.empty()) return;
  std::vector<rime::Prism::Match> matches;
  prism->CommonPrefixSearch(std::string(letters.substr(0, kMaxSpelling)), &matches);
  for (const rime::Prism::Match& match : matches) {
    bool exact = false;
    bool derived = false;
    for (rime::SpellingAccessor accessor = prism->QuerySpelling(match.value);
         !accessor.exhausted(); accessor.Next()) {
      const rime::SpellingProperties properties = accessor.properties();
      if (properties.type == rime::kNormalSpelling && !properties.is_correction) {
        exact = true;
      } else if (properties.type == rime::kNormalSpelling ||
                 properties.type == rime::kFuzzySpelling) {
        derived = true;
      }
    }
    if (exact || derived) out->push_back({.length = match.length, .fuzzy = !exact});
  }
}

void CloseSpellings() { prism.reset(); }

}  // namespace kotomo::rime_bridge
