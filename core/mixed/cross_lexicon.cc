#include "cross_lexicon.h"

#include <algorithm>
#include <charconv>
#include <fstream>
#include <iterator>

namespace kotomo::mixed {
namespace {

/// The columns of both tables: key, syllables or class, surface, reading, lid, rid, cost, source.
constexpr size_t kColumns = 8;

/// Reads a whole file. Returns: false when it cannot be opened.
bool ReadFile(const std::string& path, std::string* text) {
  std::ifstream in(path, std::ios::binary);
  if (!in) return false;
  text->assign(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
  return true;
}

/// Orders entries by key, then cost; the surface breaks ties so the order is stable across runs.
bool Less(const CrossEntry& a, const CrossEntry& b) {
  if (a.key != b.key) return a.key < b.key;
  if (a.cost != b.cost) return a.cost < b.cost;
  return a.surface < b.surface;
}

}  // namespace

bool CrossLexicon::Parse(std::string_view text, bool english, std::vector<CrossEntry>* out) {
  size_t line_start = text.find('\n');  // the header line is skipped
  if (line_start == std::string_view::npos) return false;
  ++line_start;
  while (line_start < text.size()) {
    size_t line_end = text.find('\n', line_start);
    if (line_end == std::string_view::npos) line_end = text.size();
    const std::string_view line = text.substr(line_start, line_end - line_start);
    line_start = line_end + 1;
    if (line.empty()) continue;
    std::string_view field[kColumns];
    size_t at = 0;
    for (size_t i = 0; i < kColumns; ++i) {
      const size_t tab = i + 1 < kColumns ? line.find('\t', at) : line.size();
      if (tab == std::string_view::npos) return false;
      field[i] = line.substr(at, tab - at);
      at = tab + 1;
    }
    CrossEntry entry{.key = field[0], .surface = field[2], .reading = field[3]};
    const auto parsed =
        std::from_chars(field[6].data(), field[6].data() + field[6].size(), entry.cost);
    if (parsed.ec != std::errc() || parsed.ptr != field[6].data() + field[6].size() ||
        entry.key.empty() || entry.surface.empty() || entry.reading.empty()) {
      return false;
    }
    entry.transliteration = english && field[1] == "translit";
    longest_key_ = std::max(longest_key_, entry.key.size());
    out->push_back(entry);
  }
  if (!std::is_sorted(out->begin(), out->end(), Less)) std::sort(out->begin(), out->end(), Less);
  return true;
}

std::span<const CrossEntry> CrossLexicon::Find(const std::vector<CrossEntry>& table,
                                               std::string_view key) {
  const auto range = std::equal_range(
      table.begin(), table.end(), CrossEntry{.key = key},
      [](const CrossEntry& a, const CrossEntry& b) { return a.key < b.key; });
  return {range.first, range.second};
}

std::unique_ptr<CrossLexicon> CrossLexicon::FromText(std::string pinyin_ja, std::string en_ja) {
  std::unique_ptr<CrossLexicon> lexicon(new CrossLexicon());
  lexicon->pinyin_text_ = std::move(pinyin_ja);
  lexicon->english_text_ = std::move(en_ja);
  if (!lexicon->Parse(lexicon->pinyin_text_, false, &lexicon->pinyin_) ||
      !lexicon->Parse(lexicon->english_text_, true, &lexicon->english_)) {
    return nullptr;
  }
  return lexicon;
}

std::unique_ptr<CrossLexicon> CrossLexicon::Load(const std::string& pinyin_ja,
                                                 const std::string& en_ja) {
  std::string pinyin_text, english_text;
  if (!ReadFile(pinyin_ja, &pinyin_text) || !ReadFile(en_ja, &english_text)) return nullptr;
  return FromText(std::move(pinyin_text), std::move(english_text));
}

}  // namespace kotomo::mixed
