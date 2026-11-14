#include "decode.h"

#include <algorithm>

namespace kotomo::mixed {
namespace {

/// Holds a partial path's last step: the edge taken and the partial path it extends.
struct Node {
  int32_t cost = 0;
  const Edge* edge = nullptr;  // null only for the start node
  int32_t parent = -1;         // index among the nodes at edge->begin
};

}  // namespace

int32_t Transition(const Edge* previous, const Edge& next, Lang target, const Costs& costs) {
  int32_t cost = 0;
  if (next.kind == EdgeKind::kParticle) {
    if (previous == nullptr || previous->kind == EdgeKind::kParticle) return -1;
  }
  if (next.kind == EdgeKind::kPending) {
    // Letters that begin a kana continue Japanese, not an English word or nothing.
    if (previous == nullptr || previous->kind == EdgeKind::kEnglish || previous->kind == EdgeKind::kPending) return -1;
  }
  if (next.kind == EdgeKind::kEnglish) {
    if (target == Lang::kChinese) cost += costs.english_in_chinese;
    if (target == Lang::kJapanese) cost += costs.english_in_japanese;
  }
  const bool locked = next.kind == EdgeKind::kLocked ||
                      (previous != nullptr && previous->kind == EdgeKind::kLocked);
  if (!locked && previous != nullptr && Typed(previous->kind) != Typed(next.kind)) {
    cost += costs.language_switch;
  }
  return cost;
}

std::vector<Path> Decode(const Lattice& lattice, Lang target, const Options& options) {
  std::vector<std::vector<Node>> nodes(lattice.end() + 1);
  nodes[0].push_back({});
  const auto cheaper = [](const Node& a, const Node& b) { return a.cost < b.cost; };
  for (size_t position = 0; position <= lattice.end(); ++position) {
    std::vector<Node>& here = nodes[position];
    // Every edge into this position starts earlier, so its nodes are complete: keep the beam.
    const size_t keep = position == lattice.end() ? options.paths : options.beam;
    std::stable_sort(here.begin(), here.end(), cheaper);
    if (here.size() > keep) here.resize(keep);
    if (position == lattice.end()) break;
    for (size_t index = 0; index < here.size(); ++index) {
      for (const Edge& edge : lattice.From(position)) {
        if (!Allowed(edge.kind, target)) continue;
        const int32_t transition = Transition(here[index].edge, edge, target, options.costs);
        if (transition < 0) continue;
        nodes[edge.end].push_back({.cost = here[index].cost + edge.cost + transition,
                                   .edge = &edge,
                                   .parent = static_cast<int32_t>(index)});
      }
    }
  }
  std::vector<Path> paths;
  for (const Node& last : nodes[lattice.end()]) {
    Path path{.edges = {}, .cost = last.cost};
    for (const Node* node = &last; node->edge != nullptr;
         node = &nodes[node->edge->begin][node->parent]) {
      path.edges.push_back(node->edge);
    }
    std::reverse(path.edges.begin(), path.edges.end());
    if (!path.edges.empty()) paths.push_back(std::move(path));
  }
  return paths;
}

}  // namespace kotomo::mixed
