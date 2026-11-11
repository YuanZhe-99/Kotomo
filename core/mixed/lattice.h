// The candidate lattice (PLAN.md §5.2): nodes are positions between the typed letters, edges are
// "this span of letters, read this way". Proposers add edges; the decoder walks them.

#ifndef KOTOMO_CORE_MIXED_LATTICE_H_
#define KOTOMO_CORE_MIXED_LATTICE_H_

#include <cstdint>
#include <span>
#include <string>
#include <vector>

#include "snapshot.h"

namespace kotomo::mixed {

/// Names how an edge reads its span.
enum class EdgeKind : uint8_t {
  kPinyin,    // one pinyin syllable; librime converts the run it belongs to
  kKana,      // romaji for one or more kana; Mozc converts the run it belongs to
  kEnglish,   // an English word of the English dictionary, kept as written
  kPinyinJa,  // a Japanese word typed by its Chinese reading (cross-language lexicon)
  kEnJa,      // a Japanese word typed as its English counterpart (cross-language lexicon)
  kParticle,  // は, を or へ typed wa, o or e (PLAN.md §5.4)
};

/// Holds one edge of the lattice.
struct Edge {
  uint16_t begin = 0;  // first letter position
  uint16_t end = 0;    // one past the last letter
  EdgeKind kind = EdgeKind::kPinyin;
  int32_t cost = 0;    // the proposer's cost of this reading; lower is likelier
  bool fuzzy = false;  // reached through fuzzy pinyin or a spelling correction
  bool transliteration = false;  // kEnJa only: a made-up katakana spelling, not a dictionary word
  std::string text;     // the syllable or romaji as spelled, the English word as the dictionary
                        // writes it, or the Japanese surface
  std::string reading;  // hiragana, for the edges that enter a Japanese sentence
};

/// Returns: the language the edge's span was typed in.
Lang Typed(EdgeKind kind);

/// Decides whether an edge may be part of a sentence in `target` (PLAN.md §5.3): pinyin only in
/// Chinese; kana, particles and both cross-language kinds only in Japanese; an English word in
/// any sentence, kept as written.
bool Allowed(EdgeKind kind, Lang target);

/// Returns: true for the edges that enter Mozc as a fixed value (cross-language words, particles).
bool Pinned(EdgeKind kind);

/// Holds the edges of one composition, grouped by the position they start at.
class Lattice {
 public:
  /// Inputs: `letters` is the number of letters, so positions run from 0 to `letters`.
  explicit Lattice(size_t letters) : from_(letters + 1) {}

  /// Adds an edge. Returns: false, adding nothing, for an empty span or one outside the lattice.
  bool Add(Edge edge);

  /// Returns: the edges that start at `position`, in the order they were added.
  std::span<const Edge> From(size_t position) const { return from_[position]; }

  /// Returns: the last position, which every whole path ends at.
  size_t end() const { return from_.size() - 1; }

  /// Returns: the number of edges.
  size_t size() const { return size_; }

 private:
  std::vector<std::vector<Edge>> from_;
  size_t size_ = 0;
};

}  // namespace kotomo::mixed

#endif  // KOTOMO_CORE_MIXED_LATTICE_H_
