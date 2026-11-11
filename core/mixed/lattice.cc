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
      return Lang::kEnglish;
  }
  return Lang::kEnglish;
}

bool Allowed(EdgeKind kind, Lang target) {
  switch (kind) {
    case EdgeKind::kPinyin:
      return target == Lang::kChinese;
    case EdgeKind::kEnglish:
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
  return kind == EdgeKind::kPinyinJa || kind == EdgeKind::kEnJa || kind == EdgeKind::kParticle;
}

bool Lattice::Add(Edge edge) {
  if (edge.begin >= edge.end || edge.end > end()) return false;
  ++size_;
  from_[edge.begin].push_back(std::move(edge));
  return true;
}

}  // namespace kotomo::mixed
