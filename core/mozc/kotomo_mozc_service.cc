// A line service over Kotomo's Mozc glue for host programs that cannot link
// Mozc (it builds only inside its own Bazel workspace): the mixed-input
// coordinator's host prototype (core/mixed, spike S6) starts it and talks to it
// over standard input and output. Never part of the app.
//
// Usage: kotomo_mozc_service <mozc.data> <profile directory>
//
// One request per line, fields separated by tabs, UTF-8:
//   kana <TAB> romaji              → ok <TAB> hiragana (MozcEngine::ToHiragana)
//   convert <TAB> span [<TAB> span …]
//       span: F:<reading> (free), B:<reading> (fixed boundary) or
//             P:<reading>:<surface> (pinned), readings in hiragana
//                                  → ok <TAB> n, then n segment lines:
//       <key> <TAB> 0|1 (pinned) [<TAB> value <TAB> reading <TAB> cost]…
//   quit                           → ends the service (so does end of input)
// Anything else answers `err <TAB> reason`. Every conversion is incognito and
// nothing is committed, so the service learns nothing.

#include <cstdio>
#include <iostream>
#include <memory>
#include <string>
#include <string_view>
#include <vector>

#include "absl/strings/str_split.h"
#include "kotomo/kotomo_mozc.h"

namespace kotomo::mozc {
namespace {

/// The longest request line accepted; longer ones are rejected unread.
constexpr size_t kMaxLine = 4096;
/// Candidates returned per segment.
constexpr size_t kMaxCandidates = 10;

/// Parses one `F:`, `B:` or `P:` span of a convert request.
/// Returns: false for a malformed span (unknown prefix, empty reading, a
/// pinned span without a surface).
bool ParseSpan(std::string_view field, Span* span) {
  if (field.size() < 3 || field[1] != ':') return false;
  const char kind = field[0];
  std::string_view rest = field.substr(2);
  if (kind == 'P') {
    const size_t colon = rest.find(':');
    if (colon == std::string_view::npos || colon == 0 ||
        colon + 1 == rest.size()) {
      return false;
    }
    span->reading = std::string(rest.substr(0, colon));
    span->surface = std::string(rest.substr(colon + 1));
    return true;
  }
  if (kind != 'F' && kind != 'B') return false;
  span->reading = std::string(rest);
  span->fixed = kind == 'B';
  return !span->reading.empty();
}

/// Answers one request line.
/// Returns: the response lines, each without its newline.
std::vector<std::string> Answer(const MozcEngine& engine, std::string_view line) {
  const std::vector<std::string_view> fields = absl::StrSplit(line, '\t');
  if (fields[0] == "kana" && fields.size() == 2) {
    return {"ok\t" + engine.ToHiragana(fields[1])};
  }
  if (fields[0] == "convert" && fields.size() >= 2) {
    std::vector<Span> spans(fields.size() - 1);
    for (size_t i = 1; i < fields.size(); ++i) {
      if (!ParseSpan(fields[i], &spans[i - 1])) return {"err\tbad span"};
    }
    const auto conversion =
        engine.PinnedConvert(spans, /*incognito=*/true, kMaxCandidates);
    if (!conversion) return {"err\tno conversion"};
    std::vector<std::string> out;
    out.push_back("ok\t" + std::to_string(conversion->segments.size()));
    for (const Segment& segment : conversion->segments) {
      std::string row = segment.key + (segment.pinned ? "\t1" : "\t0");
      for (const Candidate& c : segment.candidates) {
        row += "\t" + c.value + "\t" + c.key + "\t" + std::to_string(c.cost);
      }
      out.push_back(std::move(row));
    }
    return out;
  }
  return {"err\tunknown request"};
}

}  // namespace
}  // namespace kotomo::mozc

int main(int argc, char** argv) {
  if (argc != 3) {
    std::fprintf(stderr, "usage: %s <mozc.data> <profile directory>\n", argv[0]);
    return 2;
  }
  const auto engine = kotomo::mozc::MozcEngine::Open(argv[1], argv[2]);
  if (!engine) {
    std::fprintf(stderr, "kotomo_mozc_service: cannot open %s\n", argv[1]);
    return 1;
  }
  std::ios::sync_with_stdio(false);
  std::string line;
  while (std::getline(std::cin, line) && line != "quit") {
    if (line.size() > kotomo::mozc::kMaxLine) {
      std::cout << "err\tline too long\n" << std::flush;
      continue;
    }
    for (const std::string& out : kotomo::mozc::Answer(*engine, line)) {
      std::cout << out << '\n';
    }
    std::cout << std::flush;
  }
  return 0;
}
