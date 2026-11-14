// Host test of the kana and particle proposer (spike S6; PLAN.md §5.2) against the fake engines.

#include "check.h"
#include "fake_engines.h"
#include "fuzzy_kana.h"
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

  // Japanese: kana edges from the engine's romaji table, and particles.
  {
    const Input input = *ParseInput("honwo");
    Lattice lattice(input.size());
    ProposeJapanese(input, engines, options, &lattice);
    const Edge* ho = Find(lattice, EdgeKind::kKana, 0, 2);
    Check(ho && ho->reading == "ほ" && Find(lattice, EdgeKind::kKana, 2, 3) &&
              Find(lattice, EdgeKind::kKana, 3, 5),
          "S6-propose-kana");
    const Edge* particle = Find(lattice, EdgeKind::kParticle, 4, 5);
    Check(particle && particle->text == "を" && particle->cost == costs.particle,
          "S6-propose-particle-o");
  }
  {
    const Input input = *ParseInput("nani");
    Lattice lattice(input.size());
    ProposeJapanese(input, engines, options, &lattice);
    // Negative: n before a vowel is not ん; a particle never starts the input.
    Check(!Find(lattice, EdgeKind::kKana, 0, 1) && !Find(lattice, EdgeKind::kKana, 2, 3) &&
              Find(lattice, EdgeKind::kKana, 0, 2) && Find(lattice, EdgeKind::kKana, 2, 4),
          "S6-propose-kana-n-before-vowel");
  }
  {
    const Input input = *ParseInput("ekq");
    Lattice lattice(input.size());
    ProposeJapanese(input, engines, options, &lattice);
    Check(Find(lattice, EdgeKind::kKana, 0, 1) && !Find(lattice, EdgeKind::kParticle, 0, 1) &&
              lattice.size() == 1,
          "S6-propose-kana-unmapped-letters-none");
  }
  {
    const Input input = *ParseInput("kattanne");
    Lattice lattice(input.size());
    ProposeJapanese(input, engines, options, &lattice);
    const Edge* sokuon = Find(lattice, EdgeKind::kKana, 2, 5);
    const Edge* doubled = Find(lattice, EdgeKind::kKana, 5, 7);
    Check(sokuon && sokuon->reading == "った" && doubled &&
              doubled->cost == costs.kana + costs.doubled_n,
          "S6-propose-kana-sokuon-and-nn");
    // Negative: ka + tta is two edges, never one more edge over both.
    Check(!Find(lattice, EdgeKind::kKana, 0, 4) && !Find(lattice, EdgeKind::kKana, 5, 8),
          "S6-propose-kana-shortest-spellings");
  }

  {
    // Letters that begin a kana and stop there are pending at the end only (feedback A7).
    const Input input = *ParseInput("hont");
    Lattice lattice(input.size());
    ProposeJapanese(input, engines, options, &lattice);
    const Edge* pending = Find(lattice, EdgeKind::kPending, 3, 4);
    Check(pending && pending->text == "t" && pending->cost == costs.pending, "A7-propose-pending-last");
    const Input start = *ParseInput("t");
    Lattice alone(start.size());
    ProposeJapanese(start, engines, options, &alone);
    Check(alone.size() == 0, "A7-propose-pending-not-whole-input");
    // Negative: a finished kana is not pending, and letters inside are not either.
    const Input done = *ParseInput("thon");
    Lattice other(done.size());
    ProposeJapanese(done, engines, options, &other);
    Check(!Find(other, EdgeKind::kPending, 0, 1) && !Find(other, EdgeKind::kPending, 3, 4),
          "A7-propose-pending-only-unfinished");
  }

  {
    // Japanese fuzzy rules (feedback B4): a word a rule finds in the dictionary is one edge.
    Options fuzzy;
    fuzzy.japanese_fuzzy = kFuzzyVoiced | kFuzzyLongVowel;
    const Input input = *ParseInput("igukauu");
    Lattice lattice(input.size());
    ProposeJapanese(input, engines, fuzzy, &lattice);
    const Edge* voiced = Find(lattice, EdgeKind::kKana, 0, 3);
    Check(voiced && voiced->reading == "いく" && voiced->fuzzy && voiced->units == 2 &&
              voiced->cost == 2 * costs.kana + costs.japanese_fuzzy,
          "B4-propose-voiced-word");
    const Edge* long_vowel = Find(lattice, EdgeKind::kKana, 3, 7);
    Check(long_vowel && long_vowel->reading == "かう" && long_vowel->fuzzy && long_vowel->units == 3,
          "B4-propose-long-vowel-word");
    // Negative: without the rule there is no such edge; a reading the dictionary lacks gets
    // none either (いぐか → いくか is no word), and one kana alone is never rewritten.
    Lattice exact(input.size());
    ProposeJapanese(input, engines, options, &exact);
    Options other;
    other.japanese_fuzzy = kFuzzySokuon;
    Lattice unrelated(input.size());
    ProposeJapanese(input, engines, other, &unrelated);
    Check(!Find(exact, EdgeKind::kKana, 0, 3) && !Find(unrelated, EdgeKind::kKana, 0, 3) &&
              !Find(lattice, EdgeKind::kKana, 0, 5) && lattice.size() == exact.size() + 2,
          "B4-N-propose-fuzzy-only-known-words");
  }
  {
    // A romaji rule respells one kana: cu is つ, with the rule only.
    Options fuzzy;
    fuzzy.japanese_fuzzy = kFuzzyCu;
    const Input input = *ParseInput("cuki");
    Lattice lattice(input.size());
    ProposeJapanese(input, engines, fuzzy, &lattice);
    const Edge* tsu = Find(lattice, EdgeKind::kKana, 0, 2);
    Check(tsu && tsu->reading == "つ" && tsu->fuzzy && tsu->cost == costs.kana + costs.japanese_fuzzy,
          "B4-propose-cu");
    Lattice exact(input.size());
    ProposeJapanese(input, engines, options, &exact);
    Check(!Find(exact, EdgeKind::kKana, 0, 2), "B4-N-propose-cu-off");
  }

  return kotomo::mixed::test::Result();
}
