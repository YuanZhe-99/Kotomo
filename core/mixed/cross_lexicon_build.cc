#include "cross_lexicon_build.h"

#include <algorithm>
#include <charconv>
#include <cstdint>
#include <unordered_map>
#include <vector>

#include "cross_lexicon.h"

namespace kotomo::mixed {
namespace {

/// The columns of both tables: key, syllables or class, surface, reading, lid, rid, cost, source.
constexpr size_t kColumns = 8;

/// Holds one parsed line.
struct Row {
  std::string_view key, surface, reading;
  uint16_t cost = 0;
  uint8_t syllables = 0;
  CrossClass kind = CrossClass::kPinyin;
};

/// Orders rows by key, then cost; the surface and reading break ties so the output is stable.
bool Less(const Row& a, const Row& b) {
  if (a.key != b.key) return a.key < b.key;
  if (a.cost != b.cost) return a.cost < b.cost;
  if (a.surface != b.surface) return a.surface < b.surface;
  return a.reading < b.reading;
}

/// Parses one table. Returns: false with `error` set for the first bad line.
bool Parse(std::string_view text, bool english, std::vector<Row>* rows, std::string* error) {
  size_t line_start = text.find('\n');  // the header line is skipped
  size_t number = 1;
  if (line_start == std::string_view::npos) {
    *error = "no header line";
    return false;
  }
  ++line_start;
  while (line_start < text.size()) {
    size_t line_end = text.find('\n', line_start);
    if (line_end == std::string_view::npos) line_end = text.size();
    const std::string_view line = text.substr(line_start, line_end - line_start);
    line_start = line_end + 1;
    ++number;
    if (line.empty()) continue;
    const auto fail = [&](const char* why) {
      *error = std::string(english ? "en-ja" : "pinyin-ja") + " line " + std::to_string(number) +
               ": " + why;
      return false;
    };
    std::string_view field[kColumns];
    size_t at = 0;
    for (size_t i = 0; i < kColumns; ++i) {
      const size_t tab = i + 1 < kColumns ? line.find('\t', at) : line.size();
      if (tab == std::string_view::npos) return fail("fewer than eight columns");
      field[i] = line.substr(at, tab - at);
      at = tab + 1;
    }
    Row row{.key = field[0], .surface = field[2], .reading = field[3]};
    if (row.key.empty() || row.surface.empty() || row.reading.empty()) return fail("empty field");
    if (row.key.size() > 255 || row.surface.size() > 255 || row.reading.size() > 255) {
      return fail("a string over 255 bytes");
    }
    const char* cost_end = field[6].data() + field[6].size();
    const auto parsed = std::from_chars(field[6].data(), cost_end, row.cost);
    if (parsed.ec != std::errc() || parsed.ptr != cost_end) return fail("cost outside 0-65535");
    if (!english) {
      row.syllables = static_cast<uint8_t>(
          std::min<size_t>(255, 1 + std::count(field[1].begin(), field[1].end(), ' ')));
    } else if (field[1] == "semantic") {
      row.kind = CrossClass::kSemantic;
    } else if (field[1] == "gairaigo") {
      row.kind = CrossClass::kLoanword;
    } else if (field[1] == "translit") {
      row.kind = CrossClass::kTransliteration;
    } else {
      return fail("unknown class");
    }
    rows->push_back(row);
  }
  std::sort(rows->begin(), rows->end(), Less);
  return true;
}

/// Appends a little-endian integer of `bytes` bytes.
void Put(std::string* out, uint32_t value, int bytes) {
  for (int i = 0; i < bytes; ++i) out->push_back(static_cast<char>((value >> (8 * i)) & 0xff));
}

/// Holds the string pool: every distinct string once, length byte first.
class Pool {
 public:
  uint32_t Offset(std::string_view text) {
    const auto [found, added] = offsets_.try_emplace(text, static_cast<uint32_t>(bytes_.size()));
    if (added) {
      bytes_.push_back(static_cast<char>(text.size()));
      bytes_.append(text);
    }
    return found->second;
  }
  const std::string& bytes() const { return bytes_; }

 private:
  std::unordered_map<std::string_view, uint32_t> offsets_;  // views into the tables' text
  std::string bytes_;
};

}  // namespace

std::optional<std::string> CompileLexicon(std::string_view pinyin_ja, std::string_view en_ja,
                                          std::string* error) {
  std::vector<Row> tables[2];
  if (!Parse(pinyin_ja, false, &tables[0], error) || !Parse(en_ja, true, &tables[1], error)) {
    return std::nullopt;
  }
  Pool pool;
  std::string keys, records;
  uint32_t key_counts[2] = {0, 0}, entries = 0, longest = 0;
  for (int t = 0; t < 2; ++t) {
    std::string_view previous;
    for (const Row& row : tables[t]) {
      if (row.key != previous) {
        Put(&keys, pool.Offset(row.key), 4);
        Put(&keys, entries, 4);
        ++key_counts[t];
        longest = std::max<uint32_t>(longest, static_cast<uint32_t>(row.key.size()));
        previous = row.key;
      }
      Put(&records, pool.Offset(row.surface), 4);
      Put(&records, pool.Offset(row.reading), 4);
      Put(&records, row.cost, 2);
      Put(&records, row.syllables, 1);
      Put(&records, static_cast<uint8_t>(row.kind), 1);
      ++entries;
    }
    Put(&keys, 0, 4);  // the sentinel: where the last key's entries end
    Put(&keys, entries, 4);
  }
  std::string out = "KOLX";
  Put(&out, kLexiconVersion, 4);
  Put(&out, key_counts[0], 4);
  Put(&out, key_counts[1], 4);
  Put(&out, entries, 4);
  Put(&out, static_cast<uint32_t>(pool.bytes().size()), 4);
  Put(&out, longest, 4);
  Put(&out, 0, 4);
  out += keys;
  out += records;
  out += pool.bytes();
  return out;
}

}  // namespace kotomo::mixed
