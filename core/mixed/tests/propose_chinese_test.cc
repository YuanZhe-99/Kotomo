// Host test of the pinyin proposer (spike S6; PLAN.md §5.2) against the fake engines.

#include "check.h"
#include "fake_engines.h"
#include "propose.h"

using namespace kotomo::mixed;
using kotomo::mixed::test::Check;

namespace {

/// Returns: the edge of `kind` over [begin, end), or nullptr.
const Edge* Find(const Lattice& lattice, EdgeKind kind, size_t begin, size_t end) {
  for (const Edge& edge : lattice.From(begin)) {
    if (edge.kind == kind && edge.end == end) return &edge;
  }
  return nullptr;
}

}  // namespace

int main() {
  test::FakeEngines engines;
  const Options options;
  const Costs& costs = options.costs;

  // Chinese: syllables from the engine's spelling table.
  {
    const Input input = *ParseInput("xian");
    Lattice lattice(input.size());
    ProposePinyin(input, engines, options, &lattice);
    Check(Find(lattice, EdgeKind::kPinyin, 0, 2) && Find(lattice, EdgeKind::kPinyin, 0, 4) &&
              Find(lattice, EdgeKind::kPinyin, 2, 4) && lattice.size() == 3,
          "S6-propose-pinyin-xi-an-xian");
    Check(Find(lattice, EdgeKind::kPinyin, 0, 4)->cost == costs.pinyin_syllable,
          "S6-propose-pinyin-cost");
  }
  {
    const Input input = *ParseInput("xi'an");
    Lattice lattice(input.size());
    ProposePinyin(input, engines, options, &lattice);
    Check(Find(lattice, EdgeKind::kPinyin, 0, 4)->cost == costs.pinyin_syllable + costs.crossed_hint &&
              Find(lattice, EdgeKind::kPinyin, 0, 2)->cost == costs.pinyin_syllable,
          "S6-propose-pinyin-hint-penalty");
  }
  {
    const Input input = *ParseInput("hoa");
    Lattice lattice(input.size());
    ProposePinyin(input, engines, options, &lattice);
    const Edge* edge = Find(lattice, EdgeKind::kPinyin, 0, 3);
    Check(edge && edge->fuzzy && edge->cost == costs.pinyin_syllable + costs.fuzzy,
          "S6-propose-pinyin-fuzzy");
  }
  {
    // Negative: letters that spell no syllable get no edge.
    const Input input = *ParseInput("kv");
    Lattice lattice(input.size());
    ProposePinyin(input, engines, options, &lattice);
    Check(lattice.size() == 0, "S6-propose-pinyin-none");
  }

  {
    // An unfinished syllable is an edge only where typing stopped (feedback A7).
    const Input input = *ParseInput("shur");
    Lattice lattice(input.size());
    ProposePinyin(input, engines, options, &lattice);
    const Edge* edge = Find(lattice, EdgeKind::kPinyin, 3, 4);
    Check(edge && edge->cost == costs.pinyin_syllable + costs.unfinished,
          "A7-propose-pinyin-unfinished-last");
    const Input inside = *ParseInput("rshu");
    Lattice other(inside.size());
    ProposePinyin(inside, engines, options, &other);
    Check(Find(other, EdgeKind::kPinyin, 0, 1) == nullptr, "A7-propose-pinyin-unfinished-not-inside");
    const Input whole = *ParseInput("tushugua");
    Lattice third(whole.size());
    ProposePinyin(whole, engines, options, &third);
    Check(Find(third, EdgeKind::kPinyin, 5, 8) != nullptr, "A7-propose-pinyin-unfinished-prefix");
  }

  return kotomo::mixed::test::Result();
}
