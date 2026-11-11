// Host test of the decoder (spike S6; PLAN.md §5.3) on lattices built by hand and by the
// proposers over the fake engines.

#include "check.h"
#include "decode.h"
#include "fake_engines.h"
#include "propose.h"

using namespace kotomo::mixed;
using kotomo::mixed::test::Check;

namespace {

/// Joins a path's edge texts with `|`.
std::string Texts(const Path& path) {
  std::string out;
  for (const Edge* edge : path.edges) out += (out.empty() ? "" : "|") + edge->text;
  return out;
}

/// Builds the full lattice for `raw` with every proposer.
Lattice Build(const Input& input, test::FakeEngines& engines, const CrossLexicon& lexicon,
              const Options& options) {
  Lattice lattice(input.size());
  ProposePinyin(input, engines, options, &lattice);
  ProposeJapanese(input, engines, options, &lattice);
  ProposeEnglish(input, engines, options, &lattice);
  ProposeCross(input, lexicon, options, &lattice);
  return lattice;
}

}  // namespace

int main() {
  const Options options;
  const Costs& costs = options.costs;

  // Not greedy: the long first edge leads nowhere cheap, the short one wins.
  {
    Lattice lattice(6);
    lattice.Add({.begin = 0, .end = 4, .kind = EdgeKind::kPinyin, .cost = 100, .text = "long"});
    lattice.Add({.begin = 4, .end = 6, .kind = EdgeKind::kPinyin, .cost = 9000, .text = "xx"});
    lattice.Add({.begin = 0, .end = 2, .kind = EdgeKind::kPinyin, .cost = 500, .text = "ab"});
    lattice.Add({.begin = 2, .end = 6, .kind = EdgeKind::kPinyin, .cost = 500, .text = "cdef"});
    const auto paths = Decode(lattice, Lang::kChinese, options);
    Check(paths.size() == 2 && Texts(paths[0]) == "ab|cdef" && paths[0].cost == 1000 &&
              Texts(paths[1]) == "long|xx" && paths[1].cost == 9100,
          "S6-decode-k-best-not-greedy");
    // Negative: no edge of the lattice is allowed in a Japanese sentence.
    Check(Decode(lattice, Lang::kJapanese, options).empty(), "S6-decode-target-filter");
  }
  // Negative: a gap no edge covers leaves no path.
  {
    Lattice lattice(5);
    lattice.Add({.begin = 0, .end = 2, .kind = EdgeKind::kPinyin, .cost = 1, .text = "ab"});
    lattice.Add({.begin = 3, .end = 5, .kind = EdgeKind::kPinyin, .cost = 1, .text = "de"});
    Check(Decode(lattice, Lang::kChinese, options).empty(), "S6-decode-gap-no-path");
  }
  // Transition costs.
  {
    const Edge pinyin{.kind = EdgeKind::kPinyin};
    const Edge english{.kind = EdgeKind::kEnglish};
    const Edge kana{.kind = EdgeKind::kKana};
    const Edge particle{.kind = EdgeKind::kParticle};
    const Edge cross{.kind = EdgeKind::kPinyinJa};
    Check(Transition(&pinyin, pinyin, Lang::kChinese, costs) == 0 &&
              Transition(nullptr, english, Lang::kEnglish, costs) == 0,
          "S6-transition-same-language-free");
    Check(Transition(&pinyin, english, Lang::kChinese, costs) ==
              costs.english_in_chinese + costs.language_switch,
          "S6-transition-english-in-chinese");
    Check(Transition(&cross, kana, Lang::kJapanese, costs) == costs.language_switch,
          "S6-transition-switch");
    Check(Transition(&kana, particle, Lang::kJapanese, costs) == 0 &&
              Transition(&cross, particle, Lang::kJapanese, costs) == costs.language_switch,
          "S6-transition-particle-after-word");
    Check(Transition(nullptr, particle, Lang::kJapanese, costs) < 0 &&
              Transition(&particle, particle, Lang::kJapanese, costs) < 0,
          "S6-transition-particle-refused");
  }

  test::FakeEngines engines;
  const auto lexicon = test::FakeLexicon();
  // A pinyin word read as Japanese, a particle, then kana: one Japanese path through all three.
  {
    const Input input = *ParseInput("tushuguan e iku");
    const Lattice lattice = Build(input, engines, *lexicon, options);
    const auto japanese = Decode(lattice, Lang::kJapanese, options);
    Check(!japanese.empty() && Texts(japanese[0]) == "図書館|へ|i|ku", "S6-decode-cross-particle-kana",
          japanese.empty() ? "no path" : Texts(japanese[0]));
    // Negative: no Chinese or English sentence covers iku.
    Check(Decode(lattice, Lang::kChinese, options).empty() &&
              Decode(lattice, Lang::kEnglish, options).empty(),
          "S6-decode-no-chinese-or-english-path");
  }
  // English kept in a Chinese sentence; the English target takes only English words.
  {
    const Input input = *ParseInput("henhaobook");
    const Lattice lattice = Build(input, engines, *lexicon, options);
    const auto chinese = Decode(lattice, Lang::kChinese, options);
    Check(!chinese.empty() && Texts(chinese[0]) == "hen|hao|book" &&
              chinese[0].cost == 2 * costs.pinyin_syllable + costs.english_base +
                                     55 * costs.english_step + costs.english_in_chinese +
                                     costs.language_switch,
          "S6-decode-english-in-chinese");
    Check(Decode(lattice, Lang::kEnglish, options).empty(), "S6-decode-english-needs-all-words");
  }
  // The delimiter hint: xi'an prefers two syllables, xian prefers one.
  {
    const Input hinted = *ParseInput("xi'an");
    const Input plain = *ParseInput("xian");
    const auto two = Decode(Build(hinted, engines, *lexicon, options), Lang::kChinese, options);
    const Lattice plain_lattice = Build(plain, engines, *lexicon, options);
    const auto one = Decode(plain_lattice, Lang::kChinese, options);
    Check(!two.empty() && two[0].edges.size() == 2 && !one.empty() && one[0].edges.size() == 1,
          "S6-decode-delimiter-hint");
  }
  // The beam bounds the answer: never more than Options::paths.
  {
    Options narrow = options;
    narrow.paths = 1;
    narrow.beam = 2;
    const Input input = *ParseInput("xianxianxian");
    const Lattice lattice = Build(input, engines, *lexicon, narrow);
    Check(Decode(lattice, Lang::kChinese, narrow).size() == 1, "S6-decode-paths-bound");
  }
  return kotomo::mixed::test::Result();
}
