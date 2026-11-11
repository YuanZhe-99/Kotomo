// Host test of the session (the engine side of the engine ↔ app contract, version 2) against
// the fake engines: keys, deletion, replacing and clearing the composition, options, selection.

#include "check.h"
#include "fake_engines.h"
#include "input.h"
#include "session.h"

using namespace kotomo::mixed;
using kotomo::mixed::test::Check;

namespace {

uint64_t generation = 0;

/// Sends one key with the next generation.
Snapshot Key(Session* session, char c) {
  return session->Handle({.generation = ++generation, .command = Command::kKey,
                          .code_point = static_cast<uint32_t>(c), .x = 100, .y = 75});
}

/// Sends a command without fields with the next generation.
Snapshot Send(Session* session, Command command) {
  return session->Handle({.generation = ++generation, .command = command});
}

/// Returns: the 0-based place of `text` in the snapshot, or -1.
int Place(const Snapshot& snapshot, const std::string& text) {
  for (size_t i = 0; i < snapshot.candidates.size(); ++i) {
    if (snapshot.candidates[i].text == text) return static_cast<int>(i);
  }
  return -1;
}

}  // namespace

int main() {
  test::FakeEngines engines;
  const auto lexicon = test::FakeLexicon();
  Session session(engines, *lexicon);

  // Keys build the composition; every answer carries the request's generation.
  Key(&session, 's');
  Key(&session, 'h');
  Snapshot s = Key(&session, 'u');
  Check(s.input == "shu" && s.cursor == 3 && s.generation == generation && s.stage == 0 &&
            s.commit.empty() && Place(s, "书") == 0 && Place(s, "書") > 0,
        "Q7-session-keys");
  // Negative: a digit is not part of a composition and changes nothing.
  Snapshot digit = Key(&session, '7');
  Check(digit.input == "shu" && digit.generation == generation && Place(digit, "书") == 0,
        "Q7-session-other-key-ignored");
  // A space is a boundary hint inside the composition, not a commit (ADR-010).
  s = Key(&session, ' ');
  Check(s.input == "shu " && s.commit.empty() && Place(s, "书") == 0, "Q7-session-space-separates");
  s = Send(&session, Command::kBackspace);
  s = Send(&session, Command::kBackspace);
  Check(s.input == "sh" && session.composition() == "sh", "Q7-session-backspace");

  // Set replaces the composition (a mode switch hands its letters over); Clear drops it.
  s = session.Handle({.generation = ++generation, .command = Command::kSet, .text = "book"});
  Check(s.input == "book" && Place(s, "book") == 0 && Place(s, "本") > 0, "Q7-session-set");
  s = Send(&session, Command::kClear);
  Check(s.input.empty() && s.candidates.empty() && s.commit.empty(), "Q7-session-clear");
  // Negative: deleting from an empty composition is harmless.
  s = Send(&session, Command::kBackspace);
  Check(s.input.empty() && s.candidates.empty(), "Q7-session-backspace-empty");

  // Options: each cross-language switch (PLAN.md §9.10) and the weights reach the coordinator.
  session.Handle({.generation = ++generation, .command = Command::kSet, .text = "book"});
  s = session.Handle({.generation = ++generation, .command = Command::kOptions, .level = 1,
                      .switches = kSwitchPinyinToJapanese | kSwitchEnglishToKatakana |
                                  kSwitchWholeSentence});
  Check(s.input == "book" && Place(s, "本") < 0 && Place(s, "ブック") > 0,
        "Q7-session-switch-english-to-kanji-off");
  s = session.Handle({.generation = ++generation, .command = Command::kOptions, .level = 1,
                      .switches = kSwitchPinyinToJapanese | kSwitchEnglishToKanji |
                                  kSwitchWholeSentence});
  Check(Place(s, "本") > 0 && Place(s, "ブック") < 0, "Q7-session-switch-english-to-katakana-off");
  session.Handle({.generation = ++generation, .command = Command::kSet, .text = "shu"});
  s = session.Handle({.generation = ++generation, .command = Command::kOptions, .level = 1,
                      .switches = kSwitchEnglishToKanji | kSwitchEnglishToKatakana |
                                  kSwitchWholeSentence});
  Check(Place(s, "书") == 0 && Place(s, "書") < 0, "Q7-session-switch-pinyin-to-japanese-off");
  Check(!session.options().incognito, "Q7-session-incognito-off-by-default");
  const Snapshot plain = session.Handle({.generation = ++generation, .command = Command::kOptions});
  s = session.Handle({.generation = ++generation, .command = Command::kOptions,
                      .weight = {-100, 0, 0}});
  // A lower Chinese weight makes Chinese candidates cost more; the order of a language's own
  // candidates stays, and the original language still comes first below the aggressive level.
  Check(s.candidates[Place(s, "书")].cost ==
                plain.candidates[Place(plain, "书")].cost + 4000 &&
            s.candidates[Place(s, "書")].cost == plain.candidates[Place(plain, "書")].cost,
        "Q7-session-weights");
  s = session.Handle({.generation = ++generation, .command = Command::kOptions,
                      .switches = kSwitchAll});
  Check(session.options().incognito, "Q7-session-incognito");
  session.Handle({.generation = ++generation, .command = Command::kOptions});

  // Context is kept and changes nothing yet.
  s = session.Handle({.generation = ++generation, .command = Command::kContext, .text = "前"});
  Check(s.input == "shu" && Place(s, "书") == 0, "Q7-session-context");

  // Selection commits a candidate of the snapshot on screen and empties the composition.
  const uint64_t shown = s.generation;
  const uint16_t cross = static_cast<uint16_t>(Place(s, "書"));
  // Negative: an index the snapshot does not have, and a stale snapshot, commit nothing.
  Snapshot bad = session.Handle({.generation = ++generation, .command = Command::kSelect,
                                 .shown = shown, .index = 99});
  Check(bad.commit.empty() && bad.input == "shu", "Q7-session-select-bad-index");
  bad = session.Handle({.generation = ++generation, .command = Command::kSelect, .shown = shown,
                        .index = cross});
  Check(bad.commit.empty() && bad.input == "shu", "Q7-session-select-stale-snapshot-refused");
  s = session.Handle({.generation = ++generation, .command = Command::kSelect,
                      .shown = bad.generation, .index = cross});
  Check(s.commit == "書" && s.input.empty() && s.candidates.empty() &&
            session.composition().empty(),
        "Q7-session-select-commits");

  // Negative: a composition cannot grow past the limit.
  for (size_t i = 0; i < kMaxInput + 10; ++i) Key(&session, 'a');
  Check(session.composition().size() == kMaxInput, "Q7-session-length-limit");
  return kotomo::mixed::test::Result();
}
