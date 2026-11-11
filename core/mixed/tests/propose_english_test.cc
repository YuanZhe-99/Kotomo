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
    // As typed first: apple is a word, so Apple pays for the change of case.
    const Input input = *ParseInput("apple");
    Lattice lattice(input.size());
    ProposeEnglish(input, engines, options, &lattice);
    const Edge* first = lattice.From(0).empty() ? nullptr : &lattice.From(0)[0];
    Check(lattice.size() == 2 && first->text == "Apple" &&
              first->cost == costs.english_base + costs.english_step * 55 + costs.english_recased &&
              lattice.From(0)[1].text == "apple" &&
              lattice.From(0)[1].cost == costs.english_base + costs.english_step * 55,
          "S6-propose-english-as-typed-first");
  }
  {
    // Negative: inside an input a short word needs a higher probability (shu is at 85).
    const Input input = *ParseInput("shubook");
    Lattice lattice(input.size());
    ProposeEnglish(input, engines, options, &lattice);
    Check(!Find(lattice, EdgeKind::kEnglish, 0, 3) && Find(lattice, EdgeKind::kEnglish, 3, 7),
          "S6-propose-english-short-word-floor");
  }
  {
    // A short word or a possibly offensive one is proposed as the whole input only (negative
    // inside a longer one).
    const Input alone = *ParseInput("tv");
    Lattice one(alone.size());
    ProposeEnglish(alone, engines, options, &one);
    const Input inside = *ParseInput("tvbook");
    Lattice longer(inside.size());
    ProposeEnglish(inside, engines, options, &longer);
    Check(one.size() == 1 && one.From(0)[0].text == "TV" && !Find(longer, EdgeKind::kEnglish, 0, 2) &&
              Find(longer, EdgeKind::kEnglish, 2, 6),
          "S6-propose-english-short-word-whole-input-only");
    const Input word = *ParseInput("damn");
    Lattice typed(word.size());
    ProposeEnglish(word, engines, options, &typed);
    const Input split = *ParseInput("damnbook");
    Lattice parts(split.size());
    ProposeEnglish(split, engines, options, &parts);
    Check(typed.size() == 1 && !Find(parts, EdgeKind::kEnglish, 0, 4),
          "S6-propose-english-sensitive-whole-input-only");
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
