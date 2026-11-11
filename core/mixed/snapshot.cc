#include "snapshot.h"

#include <algorithm>
#include <limits>

namespace kotomo::mixed {
namespace {

/// Appends little-endian integers and length-prefixed strings to a buffer.
class Writer {
 public:
  explicit Writer(std::string* out) : out_(out) {}

  template <typename T>
  void Int(T value) {
    const auto bits = static_cast<std::make_unsigned_t<T>>(value);
    for (size_t i = 0; i < sizeof(T); ++i) {
      out_->push_back(static_cast<char>((bits >> (8 * i)) & 0xff));
    }
  }

  void Str(std::string_view text) {
    const size_t size = std::min<size_t>(text.size(), std::numeric_limits<uint16_t>::max());
    Int(static_cast<uint16_t>(size));
    out_->append(text.substr(0, size));
  }

 private:
  std::string* out_;
};

/// Reads what Writer wrote; every read fails once the buffer is exhausted.
class Reader {
 public:
  explicit Reader(std::string_view in) : in_(in) {}

  template <typename T>
  bool Int(T* value) {
    if (in_.size() - pos_ < sizeof(T)) return false;
    std::make_unsigned_t<T> bits = 0;
    for (size_t i = 0; i < sizeof(T); ++i) {
      bits |= static_cast<std::make_unsigned_t<T>>(static_cast<uint8_t>(in_[pos_ + i])) << (8 * i);
    }
    pos_ += sizeof(T);
    *value = static_cast<T>(bits);
    return true;
  }

  bool Str(std::string* text) {
    uint16_t size = 0;
    if (!Int(&size) || in_.size() - pos_ < size) return false;
    text->assign(in_.substr(pos_, size));
    pos_ += size;
    return true;
  }

  bool Done() const { return pos_ == in_.size(); }

 private:
  std::string_view in_;
  size_t pos_ = 0;
};

/// Clamps a count to the u16 the encoding stores.
uint16_t Count(size_t n) {
  return static_cast<uint16_t>(std::min<size_t>(n, std::numeric_limits<uint16_t>::max()));
}

}  // namespace

std::string Encode(const Snapshot& snapshot) {
  std::string out = "KS";
  Writer w(&out);
  w.Int(kContractVersion);
  w.Int(snapshot.generation);
  w.Int(snapshot.stage);
  w.Str(snapshot.input);
  w.Int(snapshot.cursor);
  const uint16_t candidates = Count(snapshot.candidates.size());
  w.Int(candidates);
  for (uint16_t c = 0; c < candidates; ++c) {
    const Candidate& candidate = snapshot.candidates[c];
    w.Str(candidate.text);
    w.Int(static_cast<uint8_t>(candidate.target));
    w.Int(candidate.cost);
    w.Int(candidate.flags);
    const uint16_t pieces = Count(candidate.pieces.size());
    w.Int(pieces);
    for (uint16_t p = 0; p < pieces; ++p) {
      const Piece& piece = candidate.pieces[p];
      w.Int(piece.begin);
      w.Int(piece.end);
      w.Str(piece.text);
      w.Str(piece.reading);
      w.Int(static_cast<uint8_t>(piece.typed));
      w.Int(static_cast<uint8_t>(piece.origin));
      w.Int(static_cast<uint8_t>(piece.pinned ? 1 : 0));
    }
  }
  return out;
}

std::optional<Snapshot> Decode(std::string_view bytes) {
  if (bytes.substr(0, 2) != "KS") return std::nullopt;
  Reader r(bytes.substr(2));
  uint16_t version = 0;
  Snapshot s;
  uint16_t candidates = 0;
  if (!r.Int(&version) || version != kContractVersion || !r.Int(&s.generation) ||
      !r.Int(&s.stage) || !r.Str(&s.input) || !r.Int(&s.cursor) || s.cursor > s.input.size() ||
      !r.Int(&candidates)) {
    return std::nullopt;
  }
  s.candidates.resize(candidates);
  for (Candidate& candidate : s.candidates) {
    uint8_t target = 0;
    uint16_t pieces = 0;
    if (!r.Str(&candidate.text) || !r.Int(&target) || target > 2 || !r.Int(&candidate.cost) ||
        !r.Int(&candidate.flags) || !r.Int(&pieces)) {
      return std::nullopt;
    }
    candidate.target = static_cast<Lang>(target);
    candidate.pieces.resize(pieces);
    for (Piece& piece : candidate.pieces) {
      uint8_t typed = 0, origin = 0, pinned = 0;
      if (!r.Int(&piece.begin) || !r.Int(&piece.end) || piece.begin > piece.end ||
          piece.end > s.input.size() || !r.Str(&piece.text) || !r.Str(&piece.reading) ||
          !r.Int(&typed) || typed > 2 || !r.Int(&origin) || origin > 5 || !r.Int(&pinned) ||
          pinned > 1) {
        return std::nullopt;
      }
      piece.typed = static_cast<Lang>(typed);
      piece.origin = static_cast<Origin>(origin);
      piece.pinned = pinned == 1;
    }
  }
  if (!r.Done()) return std::nullopt;
  return s;
}

bool Replaces(uint64_t generation, uint8_t stage, uint64_t shown_generation, uint8_t shown_stage) {
  return generation > shown_generation || (generation == shown_generation && stage > shown_stage);
}

}  // namespace kotomo::mixed
