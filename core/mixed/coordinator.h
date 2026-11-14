// Kotomo's mixed-input coordinator (PLAN.md §5, spike S6): one lattice over the typed letters with
// Chinese, Japanese, English and cross-language edges, a k-best decode per target language, and
// finishing through the engines, which keeps every candidate's pieces.
//
// The coordinator links no engine: it asks them through `Engines` (PLAN.md §2.5 decision 4 (b),
// doc/en-us/engine-contract.md).

#ifndef KOTOMO_CORE_MIXED_COORDINATOR_H_
#define KOTOMO_CORE_MIXED_COORDINATOR_H_

#include <cstdint>
#include <map>
#include <span>
#include <string>
#include <string_view>

#include "cross_lexicon.h"
#include "engines.h"
#include "options.h"
#include "snapshot.h"

namespace kotomo::mixed {

/// Holds what the person's history adds to the cost of readings (PLAN.md §9.9): the key is the
/// letters typed (lower case, no delimiters), a tab and the text they were read as; the value is
/// negative for a reading chosen before, positive for a demoted one, kBlocked for a hidden one.
using Learned = std::map<std::string, int16_t, std::less<>>;

/// Converts typed letters into a snapshot of whole-input candidates in three target languages.
///
/// Notes: not thread-safe; the engine thread owns it, as it owns the engines. The prototype
/// rebuilds the lattice for every call and delivers one stage; incremental decoding and the
/// second stage (PLAN.md §5.5), the emoji and symbol proposers, language-model state, frequency
/// calibration and user history come with P2 (PLAN.md §7, S6).
class Coordinator {
 public:
  /// Inputs: `engines` and `lexicon` must outlive the coordinator.
  ///   `learned` may be null; it is not read when `options.incognito` is set, so a field that
  ///   must leave no trace gets no personalized order (PLAN.md §14).
  Coordinator(Engines& engines, const CrossLexicon& lexicon, Options options = {},
              const Learned* learned = nullptr)
      : engines_(engines), lexicon_(lexicon), options_(options),
        learned_(options.incognito ? nullptr : learned) {}

  /// Converts one composition.
  /// Inputs: `composition` holds letters, with spaces and apostrophes as boundary hints (PLAN.md
  ///   §5.1); `generation` is the app's count of keys and commands, returned in the snapshot.
  /// Returns: a snapshot (stage 0) with the composition, the cursor at its end and up to
  ///   `Options::candidates` candidates in the languages of `Options::targets`, cheapest first after `Options::bias` of their target
  ///   language, each covering the whole input and no text twice. Below the aggressive level a cross-language candidate is never first while a
  ///   candidate in the language its cross-language pieces were typed in exists, unless the
  ///   person's history put it there. A learned reading of the whole input and of each piece moves a candidate's cost; a blocked one removes the candidate. A composition
  ///   ParseInput refuses gets no candidates. `more` is set when the list was cut short: more
  ///   candidates were found than `Options::candidates`, or librime may hold more conversions of
  ///   a whole-input pinyin run than `Options::alternatives`.
  /// Side effects: asks the engines (librime's spelling table and conversion, Mozc's romaji table
  ///   and converter, the English dictionary); nothing learns.
  ///   `locks` are the pieces the person chose (PLAN.md §5.6), by their byte ranges in
  ///   `composition`; every candidate keeps them as they are and converts the rest around them.
  ///   A lock that does not start and end at letters of the composition is ignored. They must
  ///   outlive the call.
  Snapshot Convert(std::string_view composition, uint64_t generation,
                   std::span<const Piece> locks = {});

 private:
  Engines& engines_;
  const CrossLexicon& lexicon_;
  Options options_;
  const Learned* learned_;
};

}  // namespace kotomo::mixed

#endif  // KOTOMO_CORE_MIXED_COORDINATOR_H_
