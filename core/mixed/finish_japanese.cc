#include <algorithm>

#include "finish.h"

namespace kotomo::mixed {
namespace {

/// Returns: where a pinned edge's text came from.
Origin PinnedOrigin(EdgeKind kind) {
  return kind == EdgeKind::kPinyinJa ? Origin::kPinyinJa
         : kind == EdgeKind::kEnJa   ? Origin::kEnJa
                                     : Origin::kRule;
}

/// Converts the edges [first, last) of a path, none of them English, in one Mozc conversion and
/// appends one piece per segment.
/// Returns: Mozc's cost of the free segments, or a negative number when Mozc converts nothing.
/// Notes: a segment boundary Mozc puts inside a kana edge moves to the end of that edge, and a
///   segment left without letters joins the piece before it, so pieces always tile the input.
int32_t ConvertStretch(const Input& input, const Path& path, size_t first, size_t last,
                       Engines& engines, std::vector<Piece>* pieces, std::vector<JapaneseWord>* only) {
  std::vector<JapaneseSpan> spans;
  bool free_run = false;
  for (size_t i = first; i < last; ++i) {
    const Edge& edge = *path.edges[i];
    if (edge.kind == EdgeKind::kKana) {
      if (!free_run) spans.push_back({});
      spans.back().reading += edge.reading;
      free_run = true;
    } else {
      spans.push_back({.reading = edge.reading, .surface = edge.text});
      free_run = false;
    }
  }
  const auto segments = engines.Japanese(spans);
  if (!segments || segments->empty()) return -1;

  int32_t cost = 0;
  size_t edge_index = first;      // the first edge not yet given to a piece
  size_t reading_covered = 0;     // reading bytes of the edges before edge_index
  size_t reading_wanted = 0;      // reading bytes of the segments so far
  const size_t pieces_before = pieces->size();
  for (size_t s = 0; s < segments->size(); ++s) {
    const JapaneseSegment& segment = (*segments)[s];
    if (segment.words.empty()) return -1;
    reading_wanted += segment.reading.size();
    const size_t begin_edge = edge_index;
    while (edge_index < last &&
           (reading_covered < reading_wanted || s + 1 == segments->size())) {
      reading_covered += path.edges[edge_index]->reading.size();
      ++edge_index;
    }
    if (!segment.pinned) cost += segment.words.front().cost;
    if (begin_edge == edge_index) {  // no letters left for this segment
      if (pieces->size() == pieces_before) return -1;
      pieces->back().text += segment.words.front().text;
      continue;
    }
    const Edge& head = *path.edges[begin_edge];
    const bool pinned = segment.pinned && edge_index == begin_edge + 1 && Pinned(head.kind);
    Piece piece = MakePiece(input, head.begin, path.edges[edge_index - 1]->end,
                            segment.words.front().text, pinned ? Typed(head.kind) : Lang::kJapanese,
                            pinned ? PinnedOrigin(head.kind) : Origin::kMozc, pinned);
    piece.reading = segment.reading;
    pieces->push_back(std::move(piece));
  }
  if (only != nullptr && segments->size() == 1 && !segments->front().pinned) {
    *only = segments->front().words;
  }
  return cost;
}

}  // namespace

void FinishJapanese(const Input& input, const Path& path, Engines& engines, const Options& options,
                    std::vector<Candidate>* out) {
  const Costs& costs = options.costs;
  Candidate sentence{.text = {}, .target = Lang::kJapanese, .cost = path.cost,
                     .flags = PathFlags(path), .pieces = {}};
  const bool one_free_run = std::all_of(path.edges.begin(), path.edges.end(), [](const Edge* e) {
    return e->kind == EdgeKind::kKana;
  });
  std::vector<JapaneseWord> alternatives;
  for (size_t i = 0; i < path.edges.size();) {
    const Edge& edge = *path.edges[i];
    if (edge.kind == EdgeKind::kEnglish) {
      sentence.pieces.push_back(MakePiece(input, edge.begin, edge.end, edge.text, Lang::kEnglish,
                                          Origin::kEnglish, false));
      ++i;
      continue;
    }
    size_t end = i;
    bool has_free = false;
    for (; end < path.edges.size() && path.edges[end]->kind != EdgeKind::kEnglish; ++end) {
      if (path.edges[end]->kind == EdgeKind::kKana) {
        sentence.cost -= costs.kana;  // Mozc's cost replaces the decoder's estimate
        has_free = true;
      }
    }
    const int32_t mozc_cost = ConvertStretch(input, path, i, end, engines, &sentence.pieces,
                                             one_free_run ? &alternatives : nullptr);
    if (mozc_cost < 0) return;
    sentence.cost += mozc_cost + (has_free ? costs.japanese_bias : 0);
    i = end;
  }
  for (const Piece& piece : sentence.pieces) sentence.text += piece.text;
  out->push_back(sentence);
  int32_t floor = sentence.cost;
  for (size_t rank = 1; rank < alternatives.size() && rank < options.alternatives; ++rank) {
    Candidate other = sentence;
    other.text = alternatives[rank].text;
    other.pieces.front().text = alternatives[rank].text;
    // Mozc's rewriters reorder its list without changing costs: keep the list's order.
    floor = std::max(floor + 1, sentence.cost - alternatives.front().cost + alternatives[rank].cost);
    other.cost = floor;
    out->push_back(std::move(other));
  }
}

}  // namespace kotomo::mixed
