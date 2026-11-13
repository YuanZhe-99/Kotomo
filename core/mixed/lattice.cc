#include "lattice.h"

namespace kotomo::mixed {

Lang Typed(EdgeKind kind) {
  switch (kind) {
    case EdgeKind::kPinyin:
    case EdgeKind::kPinyinJa:
      return Lang::kChinese;
    case EdgeKind::kKana:
    case EdgeKind::kParticle:
      return Lang::kJapanese;
    case EdgeKind::kEnglish:
    case EdgeKind::kEnJa:
    case EdgeKind::kLocked:
      return Lang::kEnglish;
  }
  return Lang::kEnglish;
}

bool Allowed(EdgeKind kind, Lang target) {
  switch (kind) {
    case EdgeKind::kPinyin:
      return target == Lang::kChinese;
    case EdgeKind::kEnglish:
    case EdgeKind::kLocked:
      return true;
    case EdgeKind::kKana:
    case EdgeKind::kParticle:
    case EdgeKind::kPinyinJa:
    case EdgeKind::kEnJa:
      return target == Lang::kJapanese;
  }
  return false;
}

bool Pinned(EdgeKind kind) {
  return kind == EdgeKind::kPinyinJa || kind == EdgeKind::kEnJa || kind == EdgeKind::kParticle ||
         kind == EdgeKind::kLocked;
}

bool Lattice::Add(Edge edge) {
  if (edge.begin >= edge.end || edge.end > end()) return false;
  if (edge.kind != EdgeKind::kLocked) {
    for (const auto& [begin, lock_end] : locks_) {
      if (edge.begin < lock_end && edge.end > begin) return false;
    }
  }
  ++size_;
  from_[edge.begin].push_back(std::move(edge));
  return true;
}

bool Lattice::Lock(size_t begin, size_t end, const Piece& piece) {
  if (begin >= end || end > this->end()) return false;
  for (const auto& [other, other_end] : locks_) {
    if (begin < other_end && end > other) return false;
  }
  locks_.emplace_back(begin, end);
  // A Japanese piece carries its reading, so Mozc converts the sentence around it.
  return Add({.begin = static_cast<uint16_t>(begin), .end = static_cast<uint16_t>(end),
              .kind = EdgeKind::kLocked, .cost = 0, .fuzzy = false, .transliteration = false,
              .text = piece.text, .reading = piece.reading, .locked = &piece});
}

}  // namespace kotomo::mixed
