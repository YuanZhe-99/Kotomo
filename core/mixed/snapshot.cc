#include "snapshot.h"

#include <algorithm>
#include <limits>

#include "wire.h"

namespace kotomo::mixed {
namespace {

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
  w.Str(snapshot.commit);
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
      !r.Str(&s.commit) || !r.Int(&candidates)) {
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
