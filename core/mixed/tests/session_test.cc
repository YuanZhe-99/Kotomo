// Host test of the session (the engine side of the engine ↔ app contract, version 5) against
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

  // Targets: Japanese mode asks for Japanese candidates only (contract version 3).
  session.Handle({.generation = ++generation, .command = Command::kSet, .text = "book"});
  s = session.Handle({.generation = ++generation, .command = Command::kOptions, .targets = 2});
  bool japanese_only = !s.candidates.empty();
  for (const Candidate& c : s.candidates) japanese_only = japanese_only && c.target == Lang::kJapanese;
  Check(Place(s, "本") >= 0 && japanese_only, "Q7B-session-targets-japanese-only");
  s = session.Handle({.generation = ++generation, .command = Command::kOptions});
  Check(Place(s, "book") == 0 && s.candidates[0].target == Lang::kEnglish, "Q7B-session-targets-default-all");
  session.Handle({.generation = ++generation, .command = Command::kSet, .text = "shu"});

  // T10 (PLAN.md §9.7, layer 4): the text before the cursor makes its language a little
  // cheaper and nothing else; the original language still comes first.
  const Snapshot no_context = s = session.Handle({.generation = ++generation, .command = Command::kSet, .text = "shu"});
  s = session.Handle({.generation = ++generation, .command = Command::kContext, .text = "これは本"});
  Check(s.input == "shu" && Place(s, "书") == 0 &&
            s.candidates[Place(s, "書")].cost == no_context.candidates[Place(no_context, "書")].cost - 300 &&
            s.candidates[Place(s, "书")].cost == no_context.candidates[Place(no_context, "书")].cost,
        "T10-context-japanese");
  s = session.Handle({.generation = ++generation, .command = Command::kContext, .text = "前。"});
  Check(s.candidates[Place(s, "书")].cost == no_context.candidates[Place(no_context, "书")].cost,
        "T10-context-negative-punctuation-says-nothing");
  s = session.Handle({.generation = ++generation, .command = Command::kContext, .text = ""});

  // T10 (PLAN.md §9.8, §9.9): what the person chose before comes first, even across languages;
  // a demoted reading sinks, a blocked one is not offered, forgetting restores the order.
  const auto learn = [&session](const char* letters, const char* text, int16_t adjust) {
    return session.Handle({.generation = ++generation, .command = Command::kLearn, .text = letters,
                           .chosen = text, .adjust = adjust});
  };
  s = learn("shu", "書", -6000);
  Check(s.input == "shu" && Place(s, "書") == 0 && (s.candidates[0].flags & kLearned) &&
            s.candidates[0].cost == no_context.candidates[Place(no_context, "書")].cost - 6000,
        "T10-learned-reading-first");
  // Negative: a field that must leave no trace gets no personalized order.
  s = session.Handle({.generation = ++generation, .command = Command::kOptions, .switches = kSwitchAll});
  Check(Place(s, "书") == 0 && !(s.candidates[Place(s, "書")].flags & kLearned), "T12-incognito-ignores-history");
  session.Handle({.generation = ++generation, .command = Command::kOptions});
  s = learn("shu", "書", 0);
  Check(Place(s, "书") == 0 && Place(s, "書") == Place(no_context, "書"), "T10-forgotten");
  s = learn("shu", "书", 3000);
  Check(Place(s, "书") > 0, "T10-demoted");
  s = learn("shu", "书", kBlocked);
  Check(Place(s, "书") < 0 && !s.candidates.empty(), "T10-blocked");
  s = learn("", "", 0);
  Check(Place(s, "书") == 0, "T10-cleared");
  // A piece's reading is learned too: the sentence that holds it moves.
  const Snapshot before_piece = session.Handle({.generation = ++generation, .command = Command::kSet, .text = "tushuguan book"});
  s = learn("book", "本", -2500);
  Check(s.candidates[Place(s, "図書館本")].cost == before_piece.candidates[Place(before_piece, "図書館本")].cost - 2500 &&
            s.candidates[Place(s, "图书馆book")].cost == before_piece.candidates[Place(before_piece, "图书馆book")].cost,
        "T10-learned-piece");
  learn("", "", 0);
  session.Handle({.generation = ++generation, .command = Command::kSet, .text = "shu"});

  // T08 (PLAN.md §9.7, layer 1): `l` touched at the edge towards `k` also offers the reading
  // with `k`, marked as a correction; a touch on the middle of the key offers none.
  const auto typed = [&session](const char* word, int16_t last_x) {
    Send(&session, Command::kClear);
    Snapshot out;
    for (const char* c = word; *c; ++c) {
      out = session.Handle({.generation = ++generation, .command = Command::kKey,
                            .code_point = static_cast<uint32_t>(*c),
                            .x = static_cast<int16_t>(c[1] ? -1 : last_x), .y = static_cast<int16_t>(c[1] ? -1 : 225)});
    }
    return out;
  };
  s = typed("bool", 855);
  Check(s.input == "bool" && Place(s, "book") >= 0 && (s.candidates[Place(s, "book")].flags & kFuzzy),
        "T08-key-correction");
  s = typed("bool", 900);
  Check(Place(s, "book") < 0, "T08-key-correction-negative-touch-on-the-key");
  s = typed("book", 745);  // `k` touched at its edge towards `j`: the word as typed stays first
  Check(Place(s, "book") == 0, "T08-key-correction-never-first");
  session.Handle({.generation = ++generation, .command = Command::kSet, .text = "shu"});
  s = Send(&session, Command::kBackspace);
  s = Key(&session, 'u');

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
  // T09 (PLAN.md §5.6, §9.5): local changes. `tushuguan book` reads as 图书馆 + book or
  // 図書館 + 本; the person changes one piece at a time.
  const auto set = [&session](const char* text) {
    return session.Handle({.generation = ++generation, .command = Command::kSet, .text = text});
  };
  const auto alternatives = [&session](uint16_t begin, uint16_t end) {
    return session.Handle({.generation = ++generation, .command = Command::kAlternatives,
                           .begin = begin, .end = end});
  };
  const auto lock = [&session](const Snapshot& shown, int index) {
    return session.Handle({.generation = ++generation, .command = Command::kLock,
                           .shown = shown.generation, .index = static_cast<uint16_t>(index)});
  };
  const auto all_hold = [](const Snapshot& snapshot, const std::string& text, uint16_t begin) {
    if (snapshot.candidates.empty()) return false;
    for (const Candidate& c : snapshot.candidates) {
      bool found = false;
      for (const Piece& piece : c.pieces) {
        found = found || (piece.locked && piece.text == text && piece.begin == begin);
      }
      if (!found || c.text.find(text) == std::string::npos) return false;
    }
    return true;
  };
  const Snapshot free_run = set("tushuguan book");
  Check(Place(free_run, "图书馆book") >= 0 && Place(free_run, "図書館本") >= 0, "T09-free");
  Snapshot span = alternatives(10, 14);
  Check(span.focus_begin == 10 && span.focus_end == 14 && span.input == "tushuguan book" &&
            Place(span, "book") >= 0 && Place(span, "本") >= 0 && Place(span, "ブック") >= 0 &&
            span.candidates[0].pieces[0].begin == 10,
        "T09-alternatives-of-a-span");
  s = lock(span, Place(span, "本"));
  Check(s.focus_end == 0 && all_hold(s, "本", 10) && Place(s, "图书馆本") >= 0 &&
            Place(s, "図書館本") >= 0 && Place(s, "图书馆book") < 0,
        "T09-lock-and-convert-around");
  // The other piece changes; the locked one is not rewritten.
  span = alternatives(0, 9);
  Check(Place(span, "图书馆") >= 0 && Place(span, "図書館") >= 0, "T09-alternatives-of-another-span");
  s = lock(span, Place(span, "図書館"));
  Check(all_hold(s, "本", 10) && all_hold(s, "図書館", 0) && s.candidates[0].text == "図書館本",
        "T09-confirmed-piece-not-rewritten");
  // Typing on keeps the locks.
  s = Key(&session, ' ');
  s = Key(&session, 'a');
  s = Key(&session, 'i');
  Check(all_hold(s, "本", 10) && all_hold(s, "図書館", 0), "T09-locks-stay-while-typing");
  // Deleting into a lock drops it, and only it.
  for (int i = 0; i < 4; ++i) s = Send(&session, Command::kBackspace);
  Check(s.input == "tushuguan boo", "T09-backspace-into-a-lock");
  s = Key(&session, 'k');
  Check(s.input == "tushuguan book" && all_hold(s, "図書館", 0) && !all_hold(s, "本", 10),
        "T09-backspace-into-a-lock-drops-it");
  // Restore: the raw input converts as if nothing had been chosen.
  set("tushuguan book");
  span = alternatives(10, 14);
  lock(span, Place(span, "本"));
  s = Send(&session, Command::kRestore);
  Check(s.candidates.size() == free_run.candidates.size() &&
            s.candidates[0].text == free_run.candidates[0].text && !all_hold(s, "本", 10),
        "T09-restore-raw-input");
  // Keep the English as typed.
  span = alternatives(10, 14);
  s = lock(span, kAsTyped);
  Check(all_hold(s, "book", 10) && Place(s, "図書館本") < 0, "T09-keep-as-typed");
  // Negative: a span that cuts a lock, a stale answer and a lock without a span change nothing.
  Snapshot cut = alternatives(8, 12);
  Check(cut.focus_end == 0 && all_hold(cut, "book", 10), "T09-span-cutting-a-lock-refused");
  span = alternatives(0, 9);
  Send(&session, Command::kContext);
  s = lock(span, 0);
  Check(!all_hold(s, span.candidates[0].text, 0), "T09-stale-lock-refused");
  s = lock(s, 0);
  Check(!all_hold(s, s.candidates[0].text, 0) && all_hold(s, "book", 10), "T09-lock-needs-a-span");
  // Partial commit: the first piece goes to the field, the rest stays composed with its lock.
  const int chinese = Place(s, "图书馆book");
  Check(chinese >= 0, "T09-sentence-before-partial-commit");
  Snapshot partial = session.Handle({.generation = ++generation, .command = Command::kCommitPrefix,
                                     .shown = s.generation, .index = static_cast<uint16_t>(chinese),
                                     .end = 9});
  Check(partial.commit == "图书馆" && partial.input == "book" && all_hold(partial, "book", 0),
        "T09-partial-commit");
  // Undo brings the composition and its locks back, once.
  s = Send(&session, Command::kUndo);
  Check(s.commit.empty() && s.input == "tushuguan book" && all_hold(s, "book", 10), "T09-undo");
  s = Send(&session, Command::kUndo);
  Check(s.input == "tushuguan book", "T09-undo-only-once");
  // Negative: a boundary inside a piece commits nothing.
  partial = session.Handle({.generation = ++generation, .command = Command::kCommitPrefix,
                            .shown = s.generation, .index = 0, .end = 5});
  Check(partial.commit.empty() && partial.input == "tushuguan book", "T09-partial-commit-needs-a-boundary");
  // Undo after a whole commit; a key typed after the commit ends it.
  s = session.Handle({.generation = ++generation, .command = Command::kSelect,
                      .shown = partial.generation, .index = 0});
  Check(!s.commit.empty() && s.input.empty(), "T09-select-with-a-lock");
  s = Send(&session, Command::kUndo);
  Check(s.input == "tushuguan book" && all_hold(s, "book", 10), "T09-undo-a-whole-commit");
  s = session.Handle({.generation = ++generation, .command = Command::kSelect,
                      .shown = s.generation, .index = 0});
  Key(&session, 'a');
  s = Send(&session, Command::kUndo);
  Check(s.input == "a", "T09-a-key-ends-undo");
  Send(&session, Command::kClear);

  return kotomo::mixed::test::Result();
}
