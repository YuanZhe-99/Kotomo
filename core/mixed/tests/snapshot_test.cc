// Host test of the snapshot encoding (spike S6; the engine ↔ app contract,
// doc/en-us/engine-contract.md): round trip, rejection of damaged buffers, stale updates.

#include "check.h"
#include "snapshot.h"

using namespace kotomo::mixed;
using kotomo::mixed::test::Check;

namespace {

/// Builds a small snapshot with one two-piece Japanese candidate and one Chinese candidate.
Snapshot Sample() {
  Snapshot s;
  s.generation = 7;
  s.stage = 1;
  s.input = "hon wo";
  s.cursor = 6;
  Candidate ja;
  ja.text = "本を";
  ja.target = Lang::kJapanese;
  ja.cost = 4200;
  ja.flags = kSentence;
  ja.pieces.push_back({0, 3, "本", "ほん", Lang::kJapanese, Origin::kMozc, false});
  ja.pieces.push_back({4, 6, "を", "を", Lang::kJapanese, Origin::kRule, true});
  Candidate zh;
  zh.text = "红";
  zh.target = Lang::kChinese;
  zh.cost = -5;
  zh.pieces.push_back({0, 3, "红", "hon", Lang::kChinese, Origin::kRime, false});
  s.candidates = {ja, zh};
  return s;
}

}  // namespace

int main() {
  const Snapshot sample = Sample();
  const std::string bytes = Encode(sample);
  const auto back = Decode(bytes);
  Check(back.has_value() && *back == sample, "S6-snapshot-roundtrip");
  Check(Decode(Encode(Snapshot{})).has_value(), "S6-snapshot-empty");

  // Negative: every truncation and a trailing byte are refused, never half-read.
  bool truncated_refused = true;
  for (size_t n = 0; n < bytes.size(); ++n) {
    if (Decode(std::string_view(bytes).substr(0, n))) truncated_refused = false;
  }
  Check(truncated_refused, "S6-snapshot-truncated-refused");
  Check(!Decode(bytes + "x"), "S6-snapshot-trailing-refused");

  // Negative: another contract version, an unknown language, a piece outside the input.
  std::string other_version = bytes;
  other_version[2] = static_cast<char>(kContractVersion + 1);
  Check(!Decode(other_version), "S6-snapshot-version-refused");
  Snapshot outside = sample;
  outside.candidates[0].pieces[1].end = 99;
  Check(!Decode(Encode(outside)), "S6-snapshot-range-refused");
  Snapshot cursor = sample;
  cursor.cursor = 99;
  Check(!Decode(Encode(cursor)), "S6-snapshot-cursor-refused");

  // A stale update never replaces a newer one.
  Check(Replaces(8, 0, 7, 3), "S6-replaces-newer-generation");
  Check(Replaces(7, 2, 7, 1), "S6-replaces-later-stage");
  Check(!Replaces(7, 1, 7, 1), "S6-replaces-same-refused");
  Check(!Replaces(6, 5, 7, 0), "S6-replaces-stale-refused");
  return kotomo::mixed::test::Result();
}
