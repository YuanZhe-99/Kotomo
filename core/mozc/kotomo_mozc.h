// Kotomo's glue over Mozc: plain conversion through a Mozc session and
// whole-sentence conversion with pinned spans at the converter layer.
//
// Built inside Mozc's Bazel workspace (scripts/build-mozc.sh copies this
// directory to src/kotomo), so Mozc headers are included from Mozc's src/ root.

#ifndef KOTOMO_CORE_MOZC_KOTOMO_MOZC_H_
#define KOTOMO_CORE_MOZC_KOTOMO_MOZC_H_

#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace mozc {
class Engine;
class SessionHandler;
}  // namespace mozc

namespace kotomo::mozc {

/// Holds one conversion candidate of a segment.
struct Candidate {
  std::string value;   // surface, e.g. 池袋
  std::string key;     // reading the candidate covers, in hiragana
  uint16_t lid = 0;    // left part-of-speech id from Mozc's dictionary
  uint16_t rid = 0;    // right part-of-speech id
};

/// Holds one segment (bunsetsu) of a conversion with its ranked candidates.
struct Segment {
  std::string key;  // the segment's reading, in hiragana
  bool pinned = false;  // true for a span the caller pinned
  std::vector<Candidate> candidates;  // best first; never empty
};

/// Holds a whole-sentence conversion: the segments in reading order.
struct Conversion {
  std::vector<Segment> segments;

  /// Joins the first candidate of every segment into the best sentence.
  std::string Sentence() const;
};

/// Describes one span of a reading for PinnedConvert.
struct Span {
  std::string reading;  // hiragana
  std::string surface;  // empty: Mozc converts the span; otherwise pinned
};

/// Holds the part-of-speech ids for a pinned surface.
struct PosIds {
  uint16_t lid = 0;
  uint16_t rid = 0;
  bool from_dictionary = false;  // false: Mozc has no such entry; general noun
};

/// Owns one Mozc engine and the session handler that drives it.
///
/// Notes: not thread-safe. Kotomo's engine thread is the only caller (AGENTS.md,
/// "The key path"). Nothing here learns: conversions are never committed, so
/// Mozc's user history stays untouched, and `incognito` additionally switches
/// off every mutable operation inside Mozc for sensitive fields.
class MozcEngine {
 public:
  /// Opens Mozc's data set and creates the engine.
  /// Inputs: `data_path` is a Mozc data file (mozc.data); `profile_dir` is a
  /// writable directory for Mozc's user dictionary and history.
  /// Returns: nullptr when the data file cannot be read or is not Mozc data.
  /// Side effects: sets Mozc's process-wide user profile directory.
  static std::unique_ptr<MozcEngine> Open(const std::string& data_path,
                                          const std::string& profile_dir);

  ~MozcEngine();
  MozcEngine(const MozcEngine&) = delete;
  MozcEngine& operator=(const MozcEngine&) = delete;

  /// Returns the data set's version string.
  std::string DataVersion() const;

  /// Converts typed input as a Mozc session would: each code point is one key
  /// (romaji goes through Mozc's romaji table, kana passes through), then the
  /// conversion key.
  /// Inputs: `typed` is UTF-8 romaji and/or hiragana; `max_candidates` caps
  /// each segment's list.
  /// Returns: segments from the preedit; only the focused (first) segment has
  /// more than one candidate, as in a session. nullopt when nothing converts.
  /// Side effects: creates and deletes a Mozc session; nothing is committed.
  std::optional<Conversion> SessionConvert(std::string_view typed,
                                           bool incognito,
                                           size_t max_candidates = 10) const;

  /// Converts a hiragana reading in which some spans are pinned to a surface.
  /// Pinned spans enter Mozc's converter as FIXED_VALUE segments with the
  /// lid/rid of the same entry in Mozc's dictionary (PLAN.md §5.4); Mozc
  /// segments and converts the free spans around them with its dictionary,
  /// connection costs and conjugation rules.
  /// Inputs: `spans` in reading order, at least one, none with an empty reading.
  /// Returns: segments with candidates for every segment; nullopt when the
  /// spans are invalid or Mozc returns no segments.
  std::optional<Conversion> PinnedConvert(const std::vector<Span>& spans,
                                          bool incognito,
                                          size_t max_candidates = 10) const;

  /// Looks up the part-of-speech ids of `surface` read as `reading` in Mozc's
  /// dictionary, preferring the lowest-cost entry.
  /// Returns: the entry's ids, or the general-noun ids with
  /// `from_dictionary == false` when Mozc has no such entry.
  PosIds LookupPosIds(std::string_view reading, std::string_view surface) const;

 private:
  MozcEngine() = default;

  ::mozc::Engine* engine_ = nullptr;  // owned by session_handler_
  std::unique_ptr<::mozc::SessionHandler> session_handler_;
};

}  // namespace kotomo::mozc

#endif  // KOTOMO_CORE_MOZC_KOTOMO_MOZC_H_
