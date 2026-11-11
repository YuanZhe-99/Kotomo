#include "finish.h"

namespace kotomo::mixed {

Piece MakePiece(const Input& input, size_t begin, size_t end, std::string text, Lang typed,
                Origin origin, bool pinned) {
  const auto [raw_begin, raw_end] = input.Range(begin, end);
  return {.begin = raw_begin,
          .end = raw_end,
          .text = std::move(text),
          .reading = input.letters.substr(begin, end - begin),
          .typed = typed,
          .origin = origin,
          .pinned = pinned};
}

uint8_t PathFlags(const Path& path) {
  uint8_t flags = kSentence;
  for (const Edge* edge : path.edges) {
    if (edge->fuzzy) flags |= kFuzzy;
    if (edge->kind == EdgeKind::kPinyinJa || edge->kind == EdgeKind::kEnJa) flags |= kCross;
  }
  return flags;
}

void FinishChinese(const Input& input, const Path& path, Engines& engines, const Options& options,
                   std::vector<Candidate>* out) {
  Candidate sentence{.text = {}, .target = Lang::kChinese, .cost = path.cost,
                     .flags = PathFlags(path), .pieces = {}};
  std::vector<std::string> whole_input_run;  // librime's list when one run is the whole input
  for (size_t i = 0; i < path.edges.size();) {
    const Edge& first = *path.edges[i];
    if (first.kind != EdgeKind::kPinyin) {
      sentence.pieces.push_back(MakePiece(input, first.begin, first.end, first.text,
                                          Lang::kEnglish, Origin::kEnglish, false));
      sentence.text += first.text;
      ++i;
      continue;
    }
    std::string syllables;
    size_t end = i;
    for (; end < path.edges.size() && path.edges[end]->kind == EdgeKind::kPinyin; ++end) {
      if (!syllables.empty()) syllables += '\'';
      syllables += path.edges[end]->text;
    }
    std::vector<std::string> conversions = engines.Chinese(syllables, options.alternatives);
    if (conversions.empty()) return;
    sentence.pieces.push_back(MakePiece(input, first.begin, path.edges[end - 1]->end,
                                        conversions.front(), Lang::kChinese, Origin::kRime, false));
    sentence.text += conversions.front();
    if (i == 0 && end == path.edges.size()) whole_input_run = std::move(conversions);
    i = end;
  }
  out->push_back(sentence);
  for (size_t rank = 1; rank < whole_input_run.size(); ++rank) {
    Candidate other = sentence;
    other.text = whole_input_run[rank];
    other.pieces.front().text = whole_input_run[rank];
    other.cost += static_cast<int32_t>(rank) * options.costs.chinese_rank;
    out->push_back(std::move(other));
  }
}

void FinishEnglish(const Input& input, const Path& path, std::vector<Candidate>* out) {
  Candidate sentence{.text = {}, .target = Lang::kEnglish, .cost = path.cost,
                     .flags = PathFlags(path), .pieces = {}};
  for (const Edge* edge : path.edges) {
    if (!sentence.text.empty()) sentence.text += ' ';
    sentence.text += edge->text;
    sentence.pieces.push_back(MakePiece(input, edge->begin, edge->end, edge->text, Lang::kEnglish,
                                        Origin::kEnglish, false));
  }
  out->push_back(std::move(sentence));
}

}  // namespace kotomo::mixed
