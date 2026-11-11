// Compiles the generator's two TSV tables (lexicon/CrossLexicon.java) into the run-time format
// CrossLexicon reads (doc/en-us/lexicon-format.md). Host only: the build tool and the tests use
// it; the device library never parses a table.

#ifndef KOTOMO_CORE_MIXED_CROSS_LEXICON_BUILD_H_
#define KOTOMO_CORE_MIXED_CROSS_LEXICON_BUILD_H_

#include <optional>
#include <string>
#include <string_view>

namespace kotomo::mixed {

/// Compiles `pinyin-ja.tsv` and `en-ja.tsv` text (header line included).
/// Inputs: eight tab-separated columns per line: key, syllables (pinyin: the key's syllables
///   separated by spaces) or class (English: `semantic`, `gairaigo`, `translit`), surface,
///   reading, lid, rid, cost, source. The ids and the source are not kept.
/// Returns: the file's bytes, keys sorted bytewise and each key's entries by cost and then
///   surface; nullopt with `error` naming the first bad line for a line with fewer columns, an
///   empty key, surface or reading, a string over 255 bytes, a cost outside 0–65535 or an
///   unknown class (tables read from disk are a boundary).
std::optional<std::string> CompileLexicon(std::string_view pinyin_ja, std::string_view en_ja,
                                          std::string* error);

}  // namespace kotomo::mixed

#endif  // KOTOMO_CORE_MIXED_CROSS_LEXICON_BUILD_H_
