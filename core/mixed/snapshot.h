// The engine ↔ app contract's output (doc/en-us/engine-contract.md): one versioned snapshot of
// the composition and its candidates per update, and its compact binary encoding. The engine
// thread encodes; the app decodes and shows the newest snapshot only.

#ifndef KOTOMO_CORE_MIXED_SNAPSHOT_H_
#define KOTOMO_CORE_MIXED_SNAPSHOT_H_

#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace kotomo::mixed {

/// Names the contract version; Encode writes it and Decode refuses any other.
/// Notes: bumped with every change to the fields below or their encoding, on both sides at once.
inline constexpr uint16_t kContractVersion = 3;

/// Names a language: the one a span was typed in, or the one a candidate is written in.
enum class Lang : uint8_t { kChinese = 0, kJapanese = 1, kEnglish = 2 };

/// Names where a piece's text came from (the unified candidate's dictionary source).
enum class Origin : uint8_t {
  kRime = 0,      // librime's conversion of a pinyin run
  kMozc = 1,      // Mozc's conversion of a free kana run
  kEnglish = 2,   // the English dictionary (the word as written there)
  kPinyinJa = 3,  // the cross-language lexicon, pinyin → Japanese
  kEnJa = 4,      // the cross-language lexicon, English → Japanese
  kRule = 5,      // a coordinator rule (the particles は, を, へ)
};

/// Candidate flags (bits of Candidate::flags).
inline constexpr uint8_t kCross = 1;     // holds a cross-language piece
inline constexpr uint8_t kFuzzy = 2;     // matched through fuzzy pinyin or a correction
inline constexpr uint8_t kEmoji = 4;     // an emoji, never part of a sentence
inline constexpr uint8_t kSentence = 8;  // covers the whole input

/// Holds one piece of a candidate: the span of the input it stands for and how it was read, so a
/// later edit can lock or change one piece without rewriting the others (PLAN.md §5.6).
struct Piece {
  uint16_t begin = 0;   // byte offset in Snapshot::input
  uint16_t end = 0;     // one past the last byte
  std::string text;     // what the piece commits
  std::string reading;  // hiragana for Japanese pieces; the letters typed otherwise
  Lang typed = Lang::kChinese;  // the language the span was typed in
  Origin origin = Origin::kRime;
  bool pinned = false;  // entered Mozc as a fixed value (cross-language word or particle)

  bool operator==(const Piece&) const = default;
};

/// Holds one candidate of the candidate bar.
struct Candidate {
  std::string text;              // what choosing it commits
  Lang target = Lang::kChinese;  // the language it is written in
  int32_t cost = 0;              // the decoder's cost, lower first; comparable within a snapshot
  uint8_t flags = 0;             // kCross, kFuzzy, kEmoji, kSentence
  std::vector<Piece> pieces;     // in input order; empty for an emoji

  bool operator==(const Candidate&) const = default;
};

/// Holds what the app shows after one update: the composition and its candidates.
struct Snapshot {
  uint64_t generation = 0;  // grows by one with every key or command
  uint8_t stage = 0;        // 0: first candidates; 1 and up: later stages of the same generation
  std::string input;        // the letters and delimiters typed (the composition)
  uint16_t cursor = 0;      // byte offset of the cursor in `input`
  std::string commit;       // text this call commits to the field before anything else; often empty
  std::vector<Candidate> candidates;  // best first

  bool operator==(const Snapshot&) const = default;
};

/// Encodes a snapshot for the one JNI crossing back to the app.
/// Returns: the bytes: "KS", the contract version, then the fields in declaration order,
///   little-endian, each string as a u16 byte length and UTF-8 bytes (doc/en-us/engine-contract.md).
/// Notes: strings longer than 65535 bytes and more than 65535 candidates or pieces are cut; the
///   coordinator never produces them.
std::string Encode(const Snapshot& snapshot);

/// Decodes bytes written by Encode.
/// Returns: nullopt for another contract version, a truncated or overlong buffer, an unknown
///   language or origin, or a piece range outside the input.
/// Notes: the app's side of the boundary, so everything is checked; host tests use it for the
///   round trip.
std::optional<Snapshot> Decode(std::string_view bytes);

/// Decides whether an update may replace the one on screen.
/// Returns: true only for a newer generation, or a later stage of the same generation; a stale
///   update never replaces a newer one (AGENTS.md, "The key path").
bool Replaces(uint64_t generation, uint8_t stage, uint64_t shown_generation, uint8_t shown_stage);

}  // namespace kotomo::mixed

#endif  // KOTOMO_CORE_MIXED_SNAPSHOT_H_
