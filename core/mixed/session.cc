#include "session.h"

#include "input.h"

#include <algorithm>

namespace kotomo::mixed {
namespace {

/// The cost one step of a language's weight is worth (PLAN.md §2.3): the whole range of the
/// setting moves a language by about one cross-language penalty either way.
constexpr int32_t kWeightStep = 40;

/// Decides whether a key belongs in a composition: a letter or a boundary hint (PLAN.md §5.1).
bool Composes(uint32_t c) {
  return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == ' ' || c == '\'';
}

}  // namespace

void Session::DropPrefix(size_t bytes) {
  while (bytes < composition_.size() && (composition_[bytes] == ' ' || composition_[bytes] == '\'')) ++bytes;
  composition_.erase(0, bytes);
  touches_.erase(touches_.begin(), touches_.begin() + static_cast<std::ptrdiff_t>(bytes));
  std::vector<Piece> kept;
  for (Piece lock : locks_) {
    if (lock.begin < bytes) continue;
    lock.begin = static_cast<uint16_t>(lock.begin - bytes);
    lock.end = static_cast<uint16_t>(lock.end - bytes);
    kept.push_back(std::move(lock));
  }
  locks_ = std::move(kept);
}

Snapshot Session::Handle(const Request& request) {
  std::string commit;
  bool alternatives = false;
  const bool answers_last = request.shown == last_generation_;
  switch (request.command) {
    case Command::kSet:
      composition_ = request.text.substr(0, kMaxInput);
      touches_.assign(composition_.size(), {kNoCoordinate, kNoCoordinate});
      locks_.clear();
      undo_.reset();
      break;
    case Command::kKey:
      if (Composes(request.code_point) && composition_.size() < kMaxInput) {
        composition_.push_back(static_cast<char>(request.code_point));
        touches_.emplace_back(request.x, request.y);
        undo_.reset();
      }
      break;
    case Command::kBackspace:
      if (!composition_.empty()) {
        composition_.pop_back();
        touches_.pop_back();
        // A lock that lost a letter is no longer what the person chose.
        if (!locks_.empty() && locks_.back().end > composition_.size()) locks_.pop_back();
      }
      break;
    case Command::kClear:
      composition_.clear();
      touches_.clear();
      locks_.clear();
      break;
    case Command::kOptions:
      options_.level = static_cast<Level>(request.level);
      options_.pinyin_to_japanese = request.switches & kSwitchPinyinToJapanese;
      options_.english_to_kanji = request.switches & kSwitchEnglishToKanji;
      options_.english_to_katakana = request.switches & kSwitchEnglishToKatakana;
      options_.whole_sentence = request.switches & kSwitchWholeSentence;
      options_.incognito = request.switches & kSwitchIncognito;
      for (int i = 0; i < 3; ++i) {
        options_.targets[i] = request.targets & (1 << i);
        options_.bias[i] = -kWeightStep * request.weight[i];
      }
      break;
    case Command::kContext:
      context_ = request.text;
      break;
    case Command::kSelect:
      if (answers_last && last_focus_end_ == 0 && request.index < last_.size()) {
        commit = last_[request.index].text;
        undo_ = State{composition_, touches_, locks_};
        composition_.clear();
        touches_.clear();
        locks_.clear();
      }
      break;
    case Command::kAlternatives: {
      // The span must hold letters and may not cut a lock (it may be one, to change it).
      bool whole = request.end <= composition_.size();
      for (const Piece& lock : locks_) {
        const bool overlaps = request.begin < lock.end && request.end > lock.begin;
        if (overlaps && !(request.begin <= lock.begin && request.end >= lock.end)) whole = false;
      }
      alternatives = whole;
      break;
    }
    case Command::kLock:
      if (answers_last && last_focus_end_ != 0 &&
          (request.index == kAsTyped || request.index < last_.size())) {
        Piece lock{.begin = last_focus_begin_, .end = last_focus_end_, .text = {}, .reading = {},
                   .typed = Lang::kEnglish, .origin = Origin::kEnglish, .pinned = false,
                   .locked = true};
        if (request.index == kAsTyped) {
          lock.text = composition_.substr(lock.begin, lock.end - lock.begin);
        } else {
          const Candidate& chosen = last_[request.index];
          lock.text = chosen.text;
          if (!chosen.pieces.empty()) {
            lock.typed = chosen.pieces.front().typed;
            lock.origin = chosen.pieces.front().origin;
          }
          if (chosen.target == Lang::kJapanese) {
            // Hiragana for Mozc; an English word kept inside leaves the piece without one.
            for (const Piece& piece : chosen.pieces) {
              if (piece.origin == Origin::kEnglish) {
                lock.reading.clear();
                break;
              }
              lock.reading += piece.reading;
            }
          }
        }
        std::erase_if(locks_, [&lock](const Piece& old) {
          return old.begin < lock.end && old.end > lock.begin;
        });
        locks_.insert(std::find_if(locks_.begin(), locks_.end(),
                                   [&lock](const Piece& old) { return old.begin > lock.begin; }),
                      std::move(lock));
      }
      break;
    case Command::kRestore:
      locks_.clear();
      break;
    case Command::kCommitPrefix:
      if (answers_last && last_focus_end_ == 0 && request.index < last_.size()) {
        std::string text;
        bool boundary = false;
        for (const Piece& piece : last_[request.index].pieces) {
          if (piece.end > request.end) break;
          text += piece.text;
          boundary = piece.end == request.end;
        }
        if (boundary && request.end < composition_.size()) {
          commit = std::move(text);
          undo_ = State{composition_, touches_, locks_};
          DropPrefix(request.end);
        }
      }
      break;
    case Command::kUndo:
      if (undo_) {
        composition_ = std::move(undo_->composition);
        touches_ = std::move(undo_->touches);
        locks_ = std::move(undo_->locks);
        undo_.reset();
      }
      break;
  }
  Snapshot snapshot;
  if (alternatives) {
    // The span alone, in every language the options allow; its pieces are placed in the input.
    snapshot = Coordinator(engines_, lexicon_, options_)
                   .Convert(std::string_view(composition_).substr(request.begin, request.end - request.begin),
                            request.generation);
    for (Candidate& candidate : snapshot.candidates) {
      for (Piece& piece : candidate.pieces) {
        piece.begin = static_cast<uint16_t>(piece.begin + request.begin);
        piece.end = static_cast<uint16_t>(piece.end + request.begin);
      }
    }
    snapshot.input = composition_;
    snapshot.cursor = static_cast<uint16_t>(composition_.size());
    snapshot.focus_begin = request.begin;
    snapshot.focus_end = request.end;
  } else {
    snapshot = Coordinator(engines_, lexicon_, options_).Convert(composition_, request.generation, locks_);
  }
  snapshot.commit = std::move(commit);
  last_generation_ = snapshot.generation;
  last_ = snapshot.candidates;
  last_focus_begin_ = snapshot.focus_begin;
  last_focus_end_ = snapshot.focus_end;
  return snapshot;
}

}  // namespace kotomo::mixed
