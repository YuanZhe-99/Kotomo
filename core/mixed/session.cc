#include "session.h"

#include "input.h"

#include <algorithm>
#include <cmath>

namespace kotomo::mixed {
namespace {

/// The cost one step of a language's weight is worth (PLAN.md §2.3): the whole range of the
/// setting moves a language by about one cross-language penalty either way.
constexpr int32_t kWeightStep = 40;

/// Decides whether a key belongs in a composition: a letter or a boundary hint (PLAN.md §5.1).
bool Composes(uint32_t c) {
  return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == ' ' || c == '\'';
}

/// The letter rows of the layout the touches are measured in (the English engine's 1000 × 600
/// space, `English.touchPoint` in the app): each row's keys and where its first key starts.
constexpr std::pair<std::string_view, int> kRows[] = {{"qwertyuiop", 0}, {"asdfghjkl", 50}, {"zxcvbnm", 150}};
constexpr int kKeyWidth = 100, kKeyHeight = 150;

/// How much nearer its own key's centre than a neighbour's a touch may be and still count as
/// possibly meant for the neighbour (a touch 17 units from the border between two keys).
constexpr int kNearKey = 35;

/// From this many letters on, a composition is answered in two stages (PLAN.md §5.5).
constexpr size_t kStagedLetters = 16;

/// Returns: the number of letters in a composition (delimiters are not letters).
size_t Letters(std::string_view composition) {
  return static_cast<size_t>(std::count_if(composition.begin(), composition.end(), [](char c) {
    return c != ' ' && c != '\'';
  }));
}

/// The most alternative readings of the keys converted per request.
constexpr size_t kKeyVariants = 2;

/// Holds one key that may have been meant for its neighbour.
struct NearKey {
  size_t at;    // byte of the composition
  char key;     // the neighbouring letter, in lower case
  int margin;   // how much nearer the touch was to the typed key, 0…kNearKey
};

/// Finds the neighbouring key a touch nearly hit (PLAN.md §9.7, layer 1).
/// Inputs: `typed` a lower-case letter; `x`, `y` the touch in the layout space.
/// Returns: the nearest other letter and its margin, or nullopt when the touch has no position,
///   fell outside the typed key's own area or was clearly on the key.
std::optional<std::pair<char, int>> Neighbour(char typed, int x, int y) {
  if (x < 0 || y < 0) return std::nullopt;
  int own = -1, best = -1;
  char other = 0;
  for (int row = 0; row < 3; ++row) {
    for (size_t i = 0; i < kRows[row].first.size(); ++i) {
      const int dx = x - (kRows[row].second + static_cast<int>(i) * kKeyWidth + kKeyWidth / 2);
      const int dy = y - (row * kKeyHeight + kKeyHeight / 2);
      const int distance = static_cast<int>(std::lround(std::hypot(dx, dy)));
      if (kRows[row].first[i] == typed) {
        if (std::abs(dx) > kKeyWidth * 6 / 10 || std::abs(dy) > kKeyHeight * 6 / 10) return std::nullopt;
        own = distance;
      } else if (best < 0 || distance < best) {
        best = distance;
        other = kRows[row].first[i];
      }
    }
  }
  if (own < 0 || best - own > kNearKey) return std::nullopt;
  return std::pair{other, std::max(best - own, 0)};
}

/// Names the language the text before the cursor is written in (PLAN.md §9.7, layer 4).
/// Returns: Japanese when its last letter is kana, or a Han character with kana before it;
///   Chinese for a Han character without; English for a Latin letter; nullopt when it ends in
///   anything else or is empty.
std::optional<Lang> ContextLanguage(std::string_view context) {
  // Decode backwards; malformed bytes end the scan (the app sends valid UTF-8).
  std::optional<Lang> last;
  for (size_t end = context.size(), seen = 0; end > 0 && seen < 32; ++seen) {
    size_t begin = end - 1;
    while (begin > 0 && (static_cast<unsigned char>(context[begin]) & 0xc0) == 0x80) --begin;
    const unsigned char lead = static_cast<unsigned char>(context[begin]);
    uint32_t c = lead < 0x80 ? lead : lead < 0xe0 ? lead & 0x1f : lead < 0xf0 ? lead & 0x0f : lead & 0x07;
    for (size_t i = begin + 1; i < end; ++i) c = (c << 6) | (static_cast<unsigned char>(context[i]) & 0x3f);
    end = begin;
    const bool kana = c >= 0x3040 && c <= 0x30ff;
    const bool han = c >= 0x4e00 && c <= 0x9fff;
    const bool latin = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    if (!last) {
      if (kana) return Lang::kJapanese;
      if (latin) return Lang::kEnglish;
      if (!han) return std::nullopt;
      last = Lang::kChinese;
    } else if (kana) {
      return Lang::kJapanese;
    }
  }
  return last;
}

}  // namespace

Options Session::Biased() const {
  Options options = options_;
  if (const auto lang = ContextLanguage(context_)) {
    options.bias[static_cast<int>(*lang)] -= options.costs.context;
  }
  return options;
}

void Session::AddKeyCorrections(const Options& options, Snapshot* snapshot) const {
  std::vector<NearKey> near;
  for (size_t i = 0; i < composition_.size(); ++i) {
    const char c = composition_[i];
    if (c < 'a' || c > 'z') continue;
    const bool locked = std::any_of(locks_.begin(), locks_.end(), [i](const Piece& lock) {
      return i >= lock.begin && i < lock.end;
    });
    if (locked) continue;
    if (const auto found = Neighbour(c, touches_[i].first, touches_[i].second)) {
      near.push_back({i, found->first, found->second});
    }
  }
  std::stable_sort(near.begin(), near.end(), [](const NearKey& a, const NearKey& b) { return a.margin < b.margin; });
  if (near.size() > kKeyVariants) near.resize(kKeyVariants);
  std::vector<Candidate> corrected;
  for (const NearKey& key : near) {
    std::string variant = composition_;
    variant[key.at] = key.key;
    Snapshot other = Coordinator(engines_, lexicon_, options, &learned_).Convert(variant, snapshot->generation, locks_);
    if (other.candidates.empty()) continue;
    Candidate& best = other.candidates.front();
    best.cost += options.costs.key_correction + options.costs.key_correction_step * key.margin;
    best.flags |= kFuzzy;
    corrected.push_back(std::move(best));
  }
  for (Candidate& candidate : corrected) {
    const bool known = std::any_of(snapshot->candidates.begin(), snapshot->candidates.end(),
                                   [&candidate](const Candidate& c) { return c.text == candidate.text; });
    if (known) continue;
    // By cost, but never first: a correction is a suggestion (PLAN.md §9.7).
    auto at = std::find_if(snapshot->candidates.begin(), snapshot->candidates.end(),
                           [&candidate](const Candidate& c) { return c.cost > candidate.cost; });
    if (at == snapshot->candidates.begin() && !snapshot->candidates.empty()) ++at;
    snapshot->candidates.insert(at, std::move(candidate));
  }
  if (snapshot->candidates.size() > options.candidates) snapshot->candidates.resize(options.candidates);
}

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
              const bool letters = !piece.reading.empty() && static_cast<unsigned char>(piece.reading.back()) < 0x80;
              if (piece.origin == Origin::kEnglish || letters) {  // pending letters have no kana either
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
    case Command::kLearn:
      if (request.text.empty() && request.chosen.empty()) {
        learned_.clear();
      } else if (request.adjust == 0) {
        learned_.erase(request.text + '\t' + request.chosen);
      } else {
        learned_[request.text + '\t' + request.chosen] = request.adjust;
      }
      break;
    case Command::kRefine:
      break;  // nothing changes: the composition converts in full below
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
  const Options options = Biased();
  if (alternatives) {
    // The span alone, in every language the options allow; its pieces are placed in the input.
    snapshot = Coordinator(engines_, lexicon_, options, &learned_)
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
  } else if (request.command != Command::kRefine && commit.empty() && Letters(composition_) >= kStagedLetters) {
    // Stage 0 of a long composition (PLAN.md §5.5): the best path of each target language,
    // finished once, and no key corrections; kRefine brings the rest.
    Options quick = options;
    quick.paths = 1;
    snapshot = Coordinator(engines_, lexicon_, quick, &learned_).Convert(composition_, request.generation, locks_);
    snapshot.more = !snapshot.candidates.empty();
  } else {
    snapshot = Coordinator(engines_, lexicon_, options, &learned_).Convert(composition_, request.generation, locks_);
    AddKeyCorrections(options, &snapshot);
    // A refinement is the later stage of the generation it was asked for.
    if (request.command == Command::kRefine) snapshot.stage = 1;
  }
  snapshot.commit = std::move(commit);
  if (composition_.empty()) engines_.Clear();  // the next composition asks afresh
  last_generation_ = snapshot.generation;
  last_ = snapshot.candidates;
  last_focus_begin_ = snapshot.focus_begin;
  last_focus_end_ = snapshot.focus_end;
  return snapshot;
}

}  // namespace kotomo::mixed
