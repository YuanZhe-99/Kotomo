// Host test of the input model and the lattice (spike S6; PLAN.md §5.1, §5.2).

#include "check.h"
#include "input.h"
#include "lattice.h"

using namespace kotomo::mixed;
using kotomo::mixed::test::Check;

int main() {
  // Delimiters are hints, not letters: both spellings give the same letters.
  const auto spaced = ParseInput("hon 'wo Yomu");
  const auto plain = ParseInput("honwoyomu");
  Check(spaced && plain && spaced->letters == plain->letters, "S6-input-delimiters-are-hints");
  Check(spaced && spaced->size() == 9 && spaced->hint[3] && spaced->hint[5] && !spaced->hint[1],
        "S6-input-hint-positions");
  Check(spaced && spaced->Range(3, 5) == std::pair<uint16_t, uint16_t>(5, 7), "S6-input-range");
  Check(spaced && spaced->HintsInside(0, 3) == 0 && spaced->HintsInside(2, 6) == 2,
        "S6-input-hints-inside");
  Check(plain && plain->HintsInside(0, 9) == 0, "S6-input-no-hints");
  const auto leading = ParseInput(" a");
  Check(leading && leading->size() == 1 && !leading->hint[0], "S6-input-leading-delimiter");

  // Negative: nothing to convert, or characters outside a composition.
  Check(!ParseInput(""), "S6-input-empty-refused");
  Check(!ParseInput(" ' "), "S6-input-no-letters-refused");
  Check(!ParseInput("abc1"), "S6-input-digit-refused");
  Check(!ParseInput("caf\xc3\xa9"), "S6-input-non-ascii-refused");
  Check(!ParseInput(std::string(kMaxInput + 1, 'a')), "S6-input-too-long-refused");

  Lattice lattice(5);
  Check(lattice.Add({.begin = 0, .end = 3, .kind = EdgeKind::kPinyin, .text = "hon"}) &&
            lattice.Add({.begin = 0, .end = 5, .kind = EdgeKind::kEnglish, .text = "honwo"}) &&
            lattice.Add({.begin = 3, .end = 5, .kind = EdgeKind::kParticle, .text = "wo"}),
        "S6-lattice-add");
  Check(lattice.From(0).size() == 2 && lattice.From(3).size() == 1 && lattice.From(5).empty() &&
            lattice.size() == 3 && lattice.end() == 5,
        "S6-lattice-from");
  // Negative: empty spans and spans past the end are refused.
  Check(!lattice.Add({.begin = 2, .end = 2}) && !lattice.Add({.begin = 4, .end = 6}) &&
            lattice.size() == 3,
        "S6-lattice-bad-span-refused");

  // Target rules (PLAN.md §5.3): cross-language edges only in Japanese sentences, English anywhere.
  Check(Allowed(EdgeKind::kPinyin, Lang::kChinese) && !Allowed(EdgeKind::kPinyin, Lang::kJapanese),
        "S6-target-pinyin");
  Check(Allowed(EdgeKind::kPinyinJa, Lang::kJapanese) &&
            !Allowed(EdgeKind::kPinyinJa, Lang::kChinese) &&
            !Allowed(EdgeKind::kEnJa, Lang::kEnglish),
        "S6-target-cross-only-japanese");
  Check(Allowed(EdgeKind::kEnglish, Lang::kChinese) && Allowed(EdgeKind::kEnglish, Lang::kJapanese),
        "S6-target-english-anywhere");
  Check(Typed(EdgeKind::kPinyinJa) == Lang::kChinese && Typed(EdgeKind::kEnJa) == Lang::kEnglish &&
            Pinned(EdgeKind::kParticle) && !Pinned(EdgeKind::kKana),
        "S6-edge-typed-and-pinned");
  return kotomo::mixed::test::Result();
}
