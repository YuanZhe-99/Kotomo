// Host test of the English proposer (spike S6; PLAN.md §5.2) against the fake engines.

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

  // English: dictionary words as the dictionary writes them.
  {
    const Input input = *ParseInput("aibook");
    Lattice lattice(input.size());
    ProposeEnglish(input, engines, options, &lattice);
    const Edge* ai = Find(lattice, EdgeKind::kEnglish, 0, 2);
    const Edge* book = Find(lattice, EdgeKind::kEnglish, 2, 6);
    Check(ai && ai->text == "AI" && book && book->text == "book" && lattice.size() == 2,
          "S6-propose-english-words");
    Check(book->cost == costs.english_base + costs.english_step * 55, "S6-propose-english-cost");
  }
  {
    // Negative: too rare a word, and a single letter, are not proposed.
    const Input input = *ParseInput("xqa");
    Lattice lattice(input.size());
    ProposeEnglish(input, engines, options, &lattice);
    Check(lattice.size() == 0, "S6-propose-english-rare-none");
  }
  return kotomo::mixed::test::Result();
}
