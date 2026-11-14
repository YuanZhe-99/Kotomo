// Host test of the cross-language lexicon's run-time lookup (spike S6; PLAN.md §5.9) and of the
// cross-language proposer.

#include <cstdio>
#include <fstream>

#include "check.h"
#include "fake_engines.h"
#include "propose.h"

using namespace kotomo::mixed;
using kotomo::mixed::test::Check;

namespace {

/// Counts the lattice's edges of `kind` over the letters [begin, end).
int Count(const Lattice& lattice, EdgeKind kind, size_t begin, size_t end) {
  int n = 0;
  for (const Edge& edge : lattice.From(begin)) n += edge.kind == kind && edge.end == end ? 1 : 0;
  return n;
}

}  // namespace

int main() {
  const auto lexicon = test::FakeLexicon();
  Check(lexicon && lexicon->size() == 8 && lexicon->longest_key() == 9, "S6-cross-load");
  std::vector<CrossEntry> found, other;
  lexicon->PinyinJa("xian", &found);
  Check(found.size() == 2 && found[0].surface == "線" && found[0].reading == "せん" &&
            found[0].cost == 3200 && found[0].syllables == 1 && found[1].surface == "県",
        "S6-cross-cheapest-first");
  lexicon->EnJa("book", &found);
  Check(found.size() == 2 && found[0].surface == "本" && found[0].kind == CrossClass::kSemantic &&
            found[1].kind == CrossClass::kLoanword,
        "S6-cross-en-ja");
  lexicon->EnJa("xq", &found);
  Check(found.size() == 1 && found[0].kind == CrossClass::kTransliteration,
        "S6-cross-transliteration-class");
  // Negative: a prefix, a longer key and the other table's key find nothing.
  const auto none = [&](bool english, const char* key) {
    english ? lexicon->EnJa(key, &other) : lexicon->PinyinJa(key, &other);
    return other.empty();
  };
  Check(none(false, "xia") && none(false, "xiang") && none(false, "book") && none(true, "shu") &&
            none(false, "") && none(true, "zzzz"),
        "S6-cross-exact-key-only");
  // Negative: malformed tables are refused by the compiler, with the line named.
  std::string error;
  Check(!CompileLexicon("key\nshu\t書\n", "key\n", &error) && error == "pinyin-ja line 2: fewer than eight columns",
        "S6-cross-short-line-refused");
  Check(!CompileLexicon("h\na\tb\tc\td\t1\t1\tcheap\tx\n", "h\n", &error),
        "S6-cross-bad-cost-refused");
  Check(!CompileLexicon("h\na\tb\tc\td\t1\t1\t70000\tx\n", "h\n", &error),
        "Q7-lexicon-cost-range-refused");
  Check(!CompileLexicon("h\n", "h\na\tslang\tc\td\t1\t1\t5\tx\n", &error),
        "Q7-lexicon-unknown-class-refused");
  Check(!CrossLexicon::Map("/nonexistent/cross.klx"), "S6-cross-missing-file-refused");
  // Unsorted input is sorted by the compiler.
  const auto unsorted = CrossLexicon::FromBytes(*CompileLexicon(
      "h\nzz\ts\tb\tび\t1\t1\t5\tx\naa\ts\ta\tあ\t1\t1\t9\tx\naa\ts\tc\tし\t1\t1\t2\tx\n", "h\n",
      &error));
  unsorted->PinyinJa("aa", &found);
  unsorted->PinyinJa("zz", &other);
  Check(found.size() == 2 && found[0].surface == "c" && other.size() == 1, "S6-cross-sorted-on-load");

  // Q7: the compiled file (doc/en-us/lexicon-format.md). A file read from disk is a boundary.
  {
    const std::string bytes = *CompileLexicon(
        "h\naa\ts\ta\tあ\t1\t1\t9\tx\n", "h\nbb\tsemantic\ta\tあ\t1\t1\t7\tx\n", &error);
    // The string あ and the string a are stored once each.
    Check(bytes.size() == 32 + 4 * 8 + 2 * 12 + (1 + 2) + (1 + 1) + (1 + 3) + (1 + 2),
          "Q7-lexicon-size-and-shared-strings");
    Check(!CrossLexicon::FromBytes(bytes.substr(0, bytes.size() - 1)) &&
              !CrossLexicon::FromBytes(bytes + "x") && !CrossLexicon::FromBytes("KOLX") &&
              !CrossLexicon::FromBytes(""),
          "Q7-lexicon-wrong-size-refused");
    std::string version = bytes;
    version[4] = 2;
    std::string magic = bytes;
    magic[0] = 'X';
    Check(!CrossLexicon::FromBytes(version) && !CrossLexicon::FromBytes(magic),
          "Q7-lexicon-other-version-refused");
    // A damaged offset finds nothing instead of reading outside the file.
    std::string damaged = bytes;
    damaged[32] = damaged[33] = damaged[34] = damaged[35] = '\x7f';  // the first key's string
    const auto broken = CrossLexicon::FromBytes(damaged);
    broken->PinyinJa("aa", &found);
    Check(broken && found.empty(), "Q7-lexicon-damaged-offset-finds-nothing");
    std::string entry = bytes;
    entry[32 + 4 * 8 + 11] = 9;  // the first entry's class
    CrossLexicon::FromBytes(entry)->PinyinJa("aa", &found);
    Check(found.empty(), "Q7-lexicon-damaged-class-finds-nothing");
    // Mapped from a file at an offset, as from an uncompressed APK asset.
    const std::string path = "kotomo_lexicon_test.klx";
    {
      std::ofstream out(path, std::ios::binary | std::ios::trunc);
      out << std::string(5000, '#') << bytes << "tail";
    }
    const auto mapped = CrossLexicon::Map(path, 5000, bytes.size());
    if (mapped) mapped->EnJa("bb", &found);
    Check(mapped && found.size() == 1 && found[0].surface == "a" && found[0].cost == 7,
          "Q7-lexicon-mapped-at-offset");
    Check(!CrossLexicon::Map(path, 5000, bytes.size() + 100) && !CrossLexicon::Map(path, 4999, bytes.size()),
          "Q7-lexicon-wrong-range-refused");
    std::remove(path.c_str());
    const auto empty = CrossLexicon::Empty();
    empty->PinyinJa("aa", &found);
    Check(empty->size() == 0 && empty->longest_key() == 0 && found.empty(), "Q7-lexicon-empty");
  }

  // The proposer: tushuguan and its inner shu; booknote as two English keys.
  Options options;
  {
    const Input input = *ParseInput("tushuguan");
    Lattice lattice(input.size());
    ProposeCross(input, *lexicon, options, &lattice);
    // Negative: the one-syllable shu inside tushuguan is not proposed.
    Check(Count(lattice, EdgeKind::kPinyinJa, 0, 9) == 1 &&
              Count(lattice, EdgeKind::kPinyinJa, 2, 5) == 0 && lattice.size() == 1,
          "S6-propose-cross-pinyin");
    const Input alone = *ParseInput("shu");
    Lattice single(alone.size());
    ProposeCross(alone, *lexicon, options, &single);
    Check(Count(single, EdgeKind::kPinyinJa, 0, 3) == 1, "S6-propose-cross-one-syllable-whole-input");
    const Edge& edge = lattice.From(0)[0];
    Check(edge.text == "図書館" && edge.reading == "としょかん" &&
              edge.cost == 3000 + options.costs.japanese_word + options.costs.pinyin_to_japanese,
          "S6-propose-cross-cost");
  }
  {
    const Input input = *ParseInput("book note");
    Lattice lattice(input.size());
    ProposeCross(input, *lexicon, options, &lattice);
    Check(Count(lattice, EdgeKind::kEnJa, 0, 4) == 2 && Count(lattice, EdgeKind::kEnJa, 4, 8) == 1,
          "S6-propose-cross-english");
  }
  {
    // The switches (negative): each direction and transliteration can be turned off.
    const Input input = *ParseInput("tushuguanxqbook");
    Options off = options;
    off.pinyin_to_japanese = false;
    off.transliteration = false;
    Lattice lattice(input.size());
    ProposeCross(input, *lexicon, off, &lattice);
    Check(Count(lattice, EdgeKind::kPinyinJa, 0, 9) == 0 &&
              Count(lattice, EdgeKind::kEnJa, 9, 11) == 0 &&
              Count(lattice, EdgeKind::kEnJa, 11, 15) == 2,
          "S6-propose-cross-switches");
    // Kanji and katakana from English are separate switches (PLAN.md §9.10).
    Options kanji = options;
    kanji.english_to_katakana = false;
    Lattice semantic(input.size());
    ProposeCross(input, *lexicon, kanji, &semantic);
    Options katakana = options;
    katakana.english_to_kanji = false;
    Lattice loan(input.size());
    ProposeCross(input, *lexicon, katakana, &loan);
    Check(Count(semantic, EdgeKind::kEnJa, 11, 15) == 1 && Count(semantic, EdgeKind::kEnJa, 9, 11) == 0 &&
              Count(loan, EdgeKind::kEnJa, 11, 15) == 1,
          "Q7-propose-cross-kanji-katakana-switches");
    // A two-letter English key is read as Japanese only as the whole input (feedback A8).
    const Input two_letters = *ParseInput("xq");
    Lattice only(two_letters.size());
    ProposeCross(two_letters, *lexicon, katakana, &only);
    Lattice made_up_off(two_letters.size());
    ProposeCross(two_letters, *lexicon, off, &made_up_off);
    Check(Count(only, EdgeKind::kEnJa, 0, 2) == 1 && Count(loan, EdgeKind::kEnJa, 9, 11) == 0 &&
              Count(made_up_off, EdgeKind::kEnJa, 0, 2) == 0,
          "A8-propose-cross-short-english-key-whole-input");
    off.english_to_kanji = off.english_to_katakana = false;
    Lattice nothing(input.size());
    ProposeCross(input, *lexicon, off, &nothing);
    Check(nothing.size() == 0, "S6-propose-cross-all-off");
    // Without whole-sentence recognition a cross-language word must be the whole input.
    Options word = options;
    word.whole_sentence = false;
    Lattice inside(input.size());
    ProposeCross(input, *lexicon, word, &inside);
    const Input alone = *ParseInput("tushuguan");
    Lattice whole(alone.size());
    ProposeCross(alone, *lexicon, word, &whole);
    Check(inside.size() == 0 && Count(whole, EdgeKind::kPinyinJa, 0, 9) == 1,
          "Q7-propose-cross-word-level-only");
    // The level scales the penalty.
    Options careful = options;
    careful.level = Level::kConservative;
    Lattice strict(input.size());
    ProposeCross(input, *lexicon, careful, &strict);
    Check(strict.From(0)[0].cost ==
              3000 + options.costs.japanese_word + 2 * options.costs.pinyin_to_japanese,
          "S6-propose-cross-level");
  }
  return kotomo::mixed::test::Result();
}
