// The decoder (PLAN.md §5.3): a k-best beam search over the lattice for one target language.

#ifndef KOTOMO_CORE_MIXED_DECODE_H_
#define KOTOMO_CORE_MIXED_DECODE_H_

#include <cstdint>
#include <vector>

#include "lattice.h"
#include "options.h"

namespace kotomo::mixed {

/// Holds one way to read the whole input: edges that cover it end to end.
struct Path {
  std::vector<const Edge*> edges;  // in input order; they point into the lattice
  int32_t cost = 0;                // the edges' costs plus the transition costs between them
};

/// Returns: what it costs to let `next` follow `previous` in a `target` sentence, or a negative
///   number when it may not. Inputs: `previous` is null at the start of the input.
/// Notes: an English word kept in a Chinese or Japanese sentence pays for it here; a change of
///   the language typed pays Costs::language_switch; a particle follows a Japanese word or kana,
///   never the start or another particle.
int32_t Transition(const Edge* previous, const Edge& next, Lang target, const Costs& costs);

/// Finds the cheapest whole paths for one target language.
/// Returns: up to `Options::paths` paths, cheapest first, each made only of edges allowed in
///   `target`; empty when the lattice has no such path.
/// Notes: keeps `Options::beam` partial paths per position, so the work is bounded by positions
///   × beam × edges per position; it never follows a greedy left-to-right match.
std::vector<Path> Decode(const Lattice& lattice, Lang target, const Options& options);

}  // namespace kotomo::mixed

#endif  // KOTOMO_CORE_MIXED_DECODE_H_
