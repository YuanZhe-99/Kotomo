// Host test of the cross-language lexicon's run-time lookup (spike S6; PLAN.md §5.9) and of the
// cross-language proposer.

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
  const auto xian = lexicon->PinyinJa("xian");
  Check(xian.size() == 2 && xian[0].surface == "線" && xian[0].reading == "せん" &&
            xian[1].surface == "県",
        "S6-cross-cheapest-first");
  const auto book = lexicon->EnJa("book");
  Check(book.size() == 2 && book[0].surface == "本" && !book[0].transliteration, "S6-cross-en-ja");
  Check(lexicon->EnJa("xq").size() == 1 && lexicon->EnJa("xq")[0].transliteration,
        "S6-cross-transliteration-class");
  // Negative: a prefix, a longer key and the other table's key find nothing.
  Check(lexicon->PinyinJa("xia").empty() && lexicon->PinyinJa("xiang").empty() &&
            lexicon->PinyinJa("book").empty() && lexicon->EnJa("shu").empty(),
        "S6-cross-exact-key-only");
  // Negative: malformed tables are refused.
  Check(!CrossLexicon::FromText("key\nshu\t書\n", "key\n"), "S6-cross-short-line-refused");
  Check(!CrossLexicon::FromText("h\na\tb\tc\td\t1\t1\tcheap\tx\n", "h\n"),
        "S6-cross-bad-cost-refused");
  Check(!CrossLexicon::Load("/nonexistent/pinyin-ja.tsv", "/nonexistent/en-ja.tsv"),
        "S6-cross-missing-file-refused");
  // Unsorted input is sorted on load.
  const auto unsorted = CrossLexicon::FromText(
      "h\nzz\ts\tb\tび\t1\t1\t5\tx\naa\ts\ta\tあ\t1\t1\t9\tx\naa\ts\tc\tし\t1\t1\t2\tx\n", "h\n");
  Check(unsorted && unsorted->PinyinJa("aa").size() == 2 &&
            unsorted->PinyinJa("aa")[0].surface == "c" && unsorted->PinyinJa("zz").size() == 1,
        "S6-cross-sorted-on-load");

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
    off.english_to_japanese = false;
    Lattice none(input.size());
    ProposeCross(input, *lexicon, off, &none);
    Check(none.size() == 0, "S6-propose-cross-all-off");
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
