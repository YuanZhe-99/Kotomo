// The engine side of the engine ↔ app contract (doc/en-us/engine-contract.md): one composition,
// changed by the app's requests, answered with snapshots. It owns what the coordinator needs
// between keys: the letters typed, where each key was touched, the options and the context.

#ifndef KOTOMO_CORE_MIXED_SESSION_H_
#define KOTOMO_CORE_MIXED_SESSION_H_

#include <cstdint>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#include "coordinator.h"
#include "request.h"

namespace kotomo::mixed {

/// Holds one Mixed-mode composition and answers the app's requests about it.
///
/// Notes: not thread-safe; the engine thread owns it. Nothing here learns by itself: a selection only says
/// what to commit, and the app decides what is remembered and tells it with kLearn (in memory
/// only; the app keeps the versioned file). It keeps the pieces the person locked (PLAN.md §5.6) and what the last commit
/// took away, so one commit can be undone. The touch positions
/// feed key correction and the text before the cursor a small language bias (PLAN.md §9.7).
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
  ///   kAlternatives answers with the readings of one span (`focus_begin`, `focus_end`) and
  ///   changes nothing; kLock fixes that span to one of them (or to its letters) and converts
  ///   the rest around it; kRestore drops every lock; kCommitPrefix commits a candidate's pieces
  ///   up to a piece boundary and keeps the rest composed; kUndo brings back what the last
  ///   kSelect or kCommitPrefix took, until a key is typed. A span that cuts a lock, a boundary
  ///   that is not one, and an answer to another snapshot change nothing.
  ///   kLearn sets, forgets (adjust 0) or clears (both strings empty) what history adds to a
  ///   reading's cost.
  /// Side effects: asks the engines through the coordinator.
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
  /// Holds what a commit can bring back.
  struct State {
    std::string composition;
    std::vector<std::pair<int16_t, int16_t>> touches;
    std::vector<Piece> locks;
  };

  /// Returns: the options in force with the language of the text before the cursor made a
  ///   little cheaper (PLAN.md §9.7, layer 4): it moves near ties and never decides alone.
  Options Biased() const;

  /// Adds readings that take a key touched near its edge for the neighbouring key (PLAN.md
  /// §9.7, layer 1): the best candidate of at most two such variants of the composition, marked
  /// kFuzzy, placed by cost with a penalty and never first.
  /// Notes: a key without a touch position, a touch clearly on its key and a locked piece are
  ///   left alone, so input without positions converts exactly as before.
  void AddKeyCorrections(const Options& options, Snapshot* snapshot) const;

  /// Removes the first `bytes` of the composition with their touches and locks, and the
  /// delimiters that then lead it.
  void DropPrefix(size_t bytes);

  uint64_t last_generation_ = 0;         // of the last snapshot returned
  std::vector<Candidate> last_;          // its candidates, for kSelect, kLock and kCommitPrefix
  uint16_t last_focus_begin_ = 0, last_focus_end_ = 0;  // its span when it answered kAlternatives
  std::vector<Piece> locks_;             // in input order, never overlapping
  std::optional<State> undo_;            // the state before the last commit
  Learned learned_;                      // what the person's history adds to readings' costs
};

}  // namespace kotomo::mixed

#endif  // KOTOMO_CORE_MIXED_SESSION_H_
