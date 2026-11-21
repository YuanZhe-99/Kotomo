// The engine ↔ app contract's input (doc/en-us/engine-contract.md): one request per key or
// command, encoded by the app's facade and decoded here on the engine thread.

#ifndef KOTOMO_CORE_MIXED_REQUEST_H_
#define KOTOMO_CORE_MIXED_REQUEST_H_

#include <cstdint>
#include <optional>
#include <string>
#include <string_view>

namespace kotomo::mixed {

/// Names what a request asks for; the values are the contract's command codes.
enum class Command : uint8_t {
  kSet = 0,        // replace the whole composition (a mode switch hands its letters over)
  kKey = 1,        // one character typed, with where the key was touched
  kBackspace = 2,  // delete the last character of the composition
  kClear = 3,      // drop the composition without committing
  kOptions = 4,    // the level, the cross-language switches, the weights, the field's privacy
  kContext = 5,    // the text before the cursor
  kSelect = 6,     // commit one candidate of a snapshot
  kAlternatives = 7,  // list the readings of one span of the composition (PLAN.md §5.6)
  kLock = 8,          // lock the span of the last kAlternatives answer to one of its candidates
  kRestore = 9,       // drop every lock: the raw input converts afresh
  kCommitPrefix = 10, // commit a candidate's pieces up to a boundary; the rest stays composed
  kUndo = 11,         // take back the last commit: the composition and its locks return
  kLearn = 12,        // set what the person's history adds to one reading's cost (PLAN.md §9.9)
  kRefine = 13,       // the later stage of the snapshot of this generation (PLAN.md §5.5)
  kLockText = 14,     // lock a span to an explicit surface and optional hiragana reading
};

/// Request::index of kLock for "keep the letters as typed".
inline constexpr uint16_t kAsTyped = 0xffff;

/// Request::switches bits.
inline constexpr uint8_t kSwitchPinyinToJapanese = 1;
inline constexpr uint8_t kSwitchEnglishToKanji = 2;
inline constexpr uint8_t kSwitchEnglishToKatakana = 4;
inline constexpr uint8_t kSwitchWholeSentence = 8;
inline constexpr uint8_t kSwitchIncognito = 16;
inline constexpr uint8_t kSwitchAbbreviation = 32;  // a syllable by its initial (`tq`)
inline constexpr uint8_t kSwitchAll = 63;

/// Request::targets bits: the languages candidates may be written in, bit i for Lang i.
inline constexpr uint8_t kTargetAll = 7;

/// Request::adjust of kLearn for a reading the person blocked: it is never offered.
inline constexpr int16_t kBlocked = 32767;

/// Marks a key without a touch position.
inline constexpr int16_t kNoCoordinate = -1;

/// Holds one decoded request; only the fields of its command are meaningful.
struct Request {
  uint64_t generation = 0;  // the app's count of keys and commands; the snapshot returns it
  Command command = Command::kClear;
  std::string text;         // kSet: the composition; kContext: the text before the cursor;
                            // kLearn: the letters typed, lower case, without delimiters;
                            // kLockText: the locked surface text
  std::string chosen;       // kLearn: the text those letters were read as
  std::string reading;      // kLockText: optional hiragana reading for Japanese pieces
  int16_t adjust = 0;       // kLearn: added to the reading's cost; 0 forgets it, kBlocked hides it
  uint32_t code_point = 0;  // kKey
  int16_t x = kNoCoordinate, y = kNoCoordinate;  // kKey: the touch in the English engine's
                                                 // 1000 × 600 layout space, or kNoCoordinate
  uint8_t level = 1;        // kOptions: 0 conservative, 1 balanced, 2 aggressive
  uint8_t switches = kSwitchAll & ~kSwitchIncognito;  // kOptions
  uint8_t targets = kTargetAll;  // kOptions: the target languages to convert into
  uint8_t target = 0;       // kLockText: typed language (0 Chinese, 1 Japanese, 2 English)
  int8_t weight[3] = {0, 0, 0};  // kOptions: how far each language's weight was moved from its
                                 // default, −100…100, indexed by Lang
  uint16_t fuzzy = 0;       // kOptions: the Japanese fuzzy rules in force, JapaneseFuzzy bits
  uint64_t shown = 0;       // kSelect, kLock, kCommitPrefix: the generation of the snapshot the
                            // index counts in
  uint16_t index = 0;       // kSelect, kCommitPrefix: the candidate; kLock: the candidate or kAsTyped
  uint16_t begin = 0, end = 0;  // kAlternatives, kLockText: the span, byte offsets in the composition;
                                // kCommitPrefix: `end` is where the committed pieces stop

  bool operator==(const Request&) const = default;
};

/// Encodes a request: "KR", the contract version, the generation, the command code, then the
/// command's fields (doc/en-us/engine-contract.md). The app's facade writes the same bytes; host
/// tests and tools use this.
std::string Encode(const Request& request);

/// Decodes a request from the app.
/// Returns: nullopt for another contract version, an unknown command, level, switch or rule bit, a
///   weight outside −100…100, a truncated or overlong buffer.
/// Notes: a JNI entry point's input, so everything is checked.
std::optional<Request> DecodeRequest(std::string_view bytes);

}  // namespace kotomo::mixed

#endif  // KOTOMO_CORE_MIXED_REQUEST_H_
