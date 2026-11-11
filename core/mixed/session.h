// The engine side of the engine ↔ app contract (doc/en-us/engine-contract.md): one composition,
// changed by the app's requests, answered with snapshots. It owns what the coordinator needs
// between keys: the letters typed, where each key was touched, the options and the context.

#ifndef KOTOMO_CORE_MIXED_SESSION_H_
#define KOTOMO_CORE_MIXED_SESSION_H_

#include <cstdint>
#include <string>
#include <utility>
#include <vector>

#include "coordinator.h"
#include "request.h"

namespace kotomo::mixed {

/// Holds one Mixed-mode composition and answers the app's requests about it.
///
/// Notes: not thread-safe; the engine thread owns it. Nothing here learns: a selection only says
/// what to commit. The touch positions and the context are kept for key correction and context
/// ranking (PLAN.md §9.7, §9.8), which do not read them yet.
class Session {
 public:
  /// Inputs: `engines` and `lexicon` must outlive the session.
  Session(Engines& engines, const CrossLexicon& lexicon) : engines_(engines), lexicon_(lexicon) {}

  /// Applies one request and converts the composition it leaves.
  /// Returns: the snapshot for the request's generation (stage 0). A key that is not a letter, a
  ///   space or an apostrophe, a key beyond kMaxInput and a selection that names another
  ///   snapshot than the last one or a candidate it does not have change nothing: the snapshot
  ///   then shows the composition as it was. A selection that holds sets `commit` to the
  ///   candidate's text and empties the composition.
  /// Side effects: asks the engines through the coordinator; nothing is learned.
  Snapshot Handle(const Request& request);

  /// Returns: the composition as typed.
  const std::string& composition() const { return composition_; }

  /// Returns: the options in force (the defaults until a kOptions request).
  const Options& options() const { return options_; }

 private:
  Engines& engines_;
  const CrossLexicon& lexicon_;
  Options options_;
  std::string composition_;
  std::vector<std::pair<int16_t, int16_t>> touches_;  // one per character of the composition
  std::string context_;
  uint64_t last_generation_ = 0;         // of the last snapshot returned
  std::vector<std::string> last_texts_;  // its candidates' texts, for kSelect
};

}  // namespace kotomo::mixed

#endif  // KOTOMO_CORE_MIXED_SESSION_H_
