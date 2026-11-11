// Read access to the spelling table (prism) of a deployed librime schema, for the mixed-input
// coordinator (core/mixed): which pinyin syllables the start of some letters spells. It is the
// table librime's own speller segments with, so the schema's fuzzy-pinyin rules and spelling
// corrections apply here exactly as in Chinese mode.

#ifndef KOTOMO_CORE_RIME_JNI_RIME_SPELLINGS_H_
#define KOTOMO_CORE_RIME_JNI_RIME_SPELLINGS_H_

#include <cstddef>
#include <string>
#include <string_view>
#include <vector>

namespace kotomo::rime_bridge {

/// Holds one syllable spelled by a prefix of the letters asked about.
struct SpellingMatch {
  std::size_t length = 0;  // letters of the prefix
  bool fuzzy = false;      // no exact spelling: a fuzzy rule or a correction derived it
};

/// Maps a prism file read-only.
/// Inputs: `prism_file` is `<data dir>/build/<schema>.prism.bin` of a deployed or prebuilt schema.
/// Returns: false when the file is missing or is not a prism.
/// Side effects: replaces the prism opened before.
bool OpenSpellings(const std::string& prism_file);

/// Lists the syllables the start of `letters` spells in full: abbreviations and completions of the
/// schema are left out.
/// Inputs: `letters` in lower case.
/// Side effects: replaces the content of `out`; empty before OpenSpellings succeeded.
/// Notes: one walk down the prism's double-array trie, no scan; engine thread only.
void Spellings(std::string_view letters, std::vector<SpellingMatch>* out);

/// Unmaps the prism.
void CloseSpellings();

}  // namespace kotomo::rime_bridge

#endif  // KOTOMO_CORE_RIME_JNI_RIME_SPELLINGS_H_
