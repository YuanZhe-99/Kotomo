#include "finish.h"

#include <algorithm>
#include <limits>

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
                   std::vector<Candidate>* out, bool* cut) {
  Candidate sentence{.text = {}, .target = Lang::kChinese, .cost = path.cost,
                     .flags = PathFlags(path), .pieces = {}};
  std::vector<ChineseWord> whole_input_run;  // librime's list when one run is the whole input
  for (size_t i = 0; i < path.edges.size();) {
    const Edge& first = *path.edges[i];
    if (first.locked != nullptr) {
      sentence.pieces.push_back(*first.locked);
      sentence.text += first.text;
      ++i;
      continue;
    }
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
    std::vector<ChineseWord> conversions = engines.Chinese(syllables, options.alternatives);
    if (conversions.empty()) return;
    sentence.pieces.push_back(MakePiece(input, first.begin, path.edges[end - 1]->end,
                                        conversions.front().text, Lang::kChinese, Origin::kRime,
                                        false));
    sentence.text += conversions.front().text;
    if (conversions.front().composed) sentence.cost += options.costs.chinese_composed;
    if (i == 0 && end == path.edges.size()) whole_input_run = std::move(conversions);
    i = end;
  }
  out->push_back(sentence);
  if (whole_input_run.size() >= options.alternatives) *cut = true;
  const int32_t composed = options.costs.chinese_composed;
  for (size_t rank = 1; rank < whole_input_run.size(); ++rank) {
    Candidate other = sentence;
    other.text = whole_input_run[rank].text;
    other.pieces.front().text = other.text;
    other.cost += static_cast<int32_t>(rank) * options.costs.chinese_rank +
                  (whole_input_run[rank].composed ? composed : 0) -
                  (whole_input_run.front().composed ? composed : 0);
    out->push_back(std::move(other));
  }
}

void FinishChinesePrefixes(const Input& input, const Lattice& lattice, std::span<const Path> paths,
                           Engines& engines, const Options& options, std::vector<Candidate>* out,
                           bool* cut) {
  if (options.paths == 1 && input.size() >= 16) return;
  constexpr size_t kMaxPrefixSyllables = 3;
  std::vector<const Edge*> leading;
  if (!paths.empty()) {
    for (const Edge* edge : paths.front().edges) {
      if (edge->kind != EdgeKind::kPinyin || edge->fuzzy || edge->partial) break;
      leading.push_back(edge);
      if (leading.size() == kMaxPrefixSyllables) break;
    }
  } else {
    size_t pos = 0;
    while (pos < input.size() && leading.size() < kMaxPrefixSyllables) {
      const Edge* best = nullptr;
      for (const Edge& edge : lattice.From(pos)) {
        if (edge.kind != EdgeKind::kPinyin || edge.fuzzy || edge.partial) continue;
        if (best == nullptr || edge.cost < best->cost ||
            (edge.cost == best->cost && edge.end > best->end)) {
          best = &edge;
        }
      }
      if (best == nullptr) break;
      leading.push_back(best);
      pos = best->end;
    }
  }
  if (leading.empty()) return;

  int32_t zh_best = std::numeric_limits<int32_t>::max();
  int32_t any_best = std::numeric_limits<int32_t>::max();
  std::vector<std::pair<int32_t, std::string_view>> whole_sentences;
  for (const Candidate& c : *out) {
    if (!(c.flags & kSentence)) continue;
    any_best = std::min(any_best, c.cost);
    if (c.target == Lang::kChinese) zh_best = std::min(zh_best, c.cost);
    whole_sentences.emplace_back(c.cost, c.text);
  }
  int32_t shield = 0;
  if (any_best != std::numeric_limits<int32_t>::max()) {
    std::stable_sort(whole_sentences.begin(), whole_sentences.end(),
                     [](const auto& a, const auto& b) { return a.first < b.first; });
    size_t kept = 0;
    std::string_view prev[3];
    for (const auto& [cost, text] : whole_sentences) {
      if (cost > any_best + 5500) break;
      bool dup = false;
      for (size_t k = 0; k < kept; ++k) dup = dup || prev[k] == text;
      if (dup) continue;
      shield = cost;
      prev[kept++] = text;
      if (kept == 3) break;
    }
  }
  if (zh_best == std::numeric_limits<int32_t>::max()) {
    if (any_best != std::numeric_limits<int32_t>::max()) {
      zh_best = any_best;
    } else if (!paths.empty()) {
      zh_best = paths.front().cost + options.costs.chinese_composed;
    } else {
      zh_best = static_cast<int32_t>(leading.size()) * options.costs.pinyin_syllable +
                options.costs.chinese_composed;
    }
  }
  const int32_t base = std::max(
      {shield > 0 ? shield + 200 : 0, zh_best + options.costs.chinese_prefix_margin,
       options.costs.chinese_prefix});

  for (size_t count = leading.size(); count >= 1; --count) {
    const size_t end = leading[count - 1]->end;
    if (end >= input.size()) continue;
    std::string syllables;
    for (size_t i = 0; i < count; ++i) {
      if (!syllables.empty()) syllables += '\'';
      syllables += leading[i]->text;
    }
    const size_t limit =
        count == 1 ? std::min<size_t>(options.alternatives, 60) : std::min<size_t>(options.alternatives, 6);
    if (limit == 0) continue;
    std::vector<ChineseWord> conversions = engines.Chinese(syllables, limit);
    if (conversions.size() >= options.alternatives) *cut = true;
    const int32_t length_step = static_cast<int32_t>(kMaxPrefixSyllables - count) * 400;
    for (size_t rank = 0; rank < conversions.size(); ++rank) {
      if (count >= 2 && conversions[rank].composed) continue;
      const int32_t cost = base + length_step + static_cast<int32_t>(rank) * 1200 +
                           (conversions[rank].composed ? options.costs.chinese_composed : 0);
      std::string text = std::move(conversions[rank].text);
      Candidate candidate{.text = text,
                          .target = Lang::kChinese,
                          .cost = cost,
                          .flags = 0,
                          .pieces = {MakePiece(input, 0, end, text, Lang::kChinese, Origin::kRime,
                                               false)}};
      out->push_back(std::move(candidate));
    }
  }
}

void FinishEnglish(const Input& input, const Path& path, std::vector<Candidate>* out) {
  Candidate sentence{.text = {}, .target = Lang::kEnglish, .cost = path.cost,
                     .flags = PathFlags(path), .pieces = {}};
  for (const Edge* edge : path.edges) {
    if (!sentence.text.empty()) sentence.text += ' ';
    sentence.text += edge->text;
    sentence.pieces.push_back(edge->locked != nullptr
                                  ? *edge->locked
                                  : MakePiece(input, edge->begin, edge->end, edge->text,
                                              Lang::kEnglish, Origin::kEnglish, false));
  }
  out->push_back(std::move(sentence));
}

}  // namespace kotomo::mixed
