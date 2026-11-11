// Compiles the cross-language lexicon for the app (PLAN.md §5.9, doc/en-us/lexicon-format.md).
//
// Usage: kotomo_lexicon_compile <pinyin-ja.tsv> <en-ja.tsv> <out.klx>
// Reads the two tables lexicon/CrossLexicon.java wrote, writes the run-time file, maps it back
// and looks every key count up again, then prints the counts and the size. Exit status 1 for a
// table that cannot be read or holds a bad line, or an output that does not open.

#include <cstdio>
#include <fstream>
#include <iterator>
#include <string>

#include "cross_lexicon.h"
#include "cross_lexicon_build.h"

namespace {

/// Reads a whole file. Returns: false when it cannot be opened.
bool ReadFile(const char* path, std::string* text) {
  std::ifstream in(path, std::ios::binary);
  if (!in) return false;
  text->assign(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
  return true;
}

}  // namespace

int main(int argc, char** argv) {
  if (argc != 4) {
    std::fprintf(stderr, "usage: %s <pinyin-ja.tsv> <en-ja.tsv> <out.klx>\n", argv[0]);
    return 2;
  }
  std::string pinyin, english, error;
  if (!ReadFile(argv[1], &pinyin) || !ReadFile(argv[2], &english)) {
    std::fprintf(stderr, "lexicon: cannot read the tables (run java -Xmx4g lexicon/CrossLexicon.java)\n");
    return 1;
  }
  const auto bytes = kotomo::mixed::CompileLexicon(pinyin, english, &error);
  if (!bytes) {
    std::fprintf(stderr, "lexicon: %s\n", error.c_str());
    return 1;
  }
  const std::string part = std::string(argv[3]) + ".part";
  {
    std::ofstream out(part, std::ios::binary | std::ios::trunc);
    out.write(bytes->data(), static_cast<std::streamsize>(bytes->size()));
    if (!out.flush()) {
      std::fprintf(stderr, "lexicon: cannot write %s\n", part.c_str());
      return 1;
    }
  }
  const auto mapped = kotomo::mixed::CrossLexicon::Map(part);
  if (!mapped || std::rename(part.c_str(), argv[3]) != 0) {
    std::fprintf(stderr, "lexicon: the compiled file does not open\n");
    return 1;
  }
  std::printf("lexicon: %zu entries, longest key %zu, %zu bytes -> %s\n", mapped->size(),
              mapped->longest_key(), bytes->size(), argv[3]);
  return 0;
}
