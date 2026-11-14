// Runs the conversion corpus (tests/conversion/corpus.tsv, PLAN.md §13.2, §13.3) through the
// mixed-input coordinator on the host, for spike S6: the Mixed lines as they are, and the
// single-language lines as a baseline of what the coordinator makes of the same input.
//
// Usage: kotomo_mixed_corpus <repository root> [--baseline <file>]
//        kotomo_mixed_corpus <repository root> --convert <composition>
//          prints the candidates of one composition with target, cost and pieces, and writes
//          nothing
//        kotomo_mixed_corpus <repository root> --refine <composition>
//          the same for the full list a session's kRefine answers with, and the time it took
// Reads: tests/conversion/corpus.tsv and the build outputs under build/ (prebuilt librime data,
//   the English dictionary, the host Mozc service and data, the cross-language lexicon).
// Writes: build/conversion/mixed-results.tsv and build/conversion/mixed-report.txt.
// Exit status: 0 when no Mixed line that passed in the baseline fails now; 1 on a regression;
//   2 when the corpus, an engine or the lexicon cannot be opened.

#include <cstdlib>
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <map>
#include <optional>
#include <sstream>
#include <string>
#include <vector>

#include "coordinator.h"
#include "fuzzy_kana.h"
#include "host_engines.h"
#include "session.h"

namespace fs = std::filesystem;
using namespace kotomo::mixed;

namespace {

/// Candidates listed per line in the results and the report.
constexpr size_t kShown = 5;

/// Holds one corpus line.
struct Line {
  std::string id, mode, input;
  std::vector<std::string> wanted;  // acceptable outputs, or the forbidden ones of a negative line
  bool negative = false;            // expectations start with `!`: none may be within `rank`
  int rank = 1;                     // 0: anywhere in the list
};

/// Holds one line's result.
struct Outcome {
  bool pass = false;
  int position = 0;  // 1-based place of the first wanted (or forbidden) text; 0 when absent
  std::string shown;
  std::string gap;   // for a failed line: what kind of gap it is
};

std::vector<std::string> Split(const std::string& text, char separator) {
  std::vector<std::string> out;
  std::stringstream stream(text);
  for (std::string part; std::getline(stream, part, separator);) out.push_back(part);
  return out;
}

/// Reads the corpus. Returns: false when the file is missing or a line is malformed.
bool ReadCorpus(const fs::path& file, std::vector<Line>* lines) {
  std::ifstream in(file);
  if (!in) return false;
  for (std::string row; std::getline(in, row);) {
    if (row.empty() || row[0] == '#') continue;
    const std::vector<std::string> f = Split(row, '\t');
    if (f.size() < 6) return false;
    if (f[0] == "id") continue;
    Line line{.id = f[0], .mode = f[1], .input = f[3], .wanted = {}};
    for (std::string want : Split(f[4], '|')) {
      if (!want.empty() && want[0] == '!') {
        line.negative = true;
        want.erase(0, 1);
      }
      line.wanted.push_back(want);
    }
    line.rank = std::atoi(f[5].c_str());
    if (line.wanted.empty() || line.rank < 0) return false;
    lines->push_back(std::move(line));
  }
  return true;
}

/// Judges one line against the candidates of `target` only, or of every target for Mixed.
Outcome Judge(const Line& line, const Snapshot& snapshot, const Lang* target) {
  std::vector<const Candidate*> list;
  for (const Candidate& candidate : snapshot.candidates) {
    if (target == nullptr || candidate.target == *target) list.push_back(&candidate);
  }
  Outcome outcome;
  for (size_t i = 0; i < list.size(); ++i) {
    if (i < kShown) outcome.shown += (i ? " / " : "") + list[i]->text;
    if (outcome.position == 0 &&
        std::find(line.wanted.begin(), line.wanted.end(), list[i]->text) != line.wanted.end()) {
      outcome.position = static_cast<int>(i) + 1;
    }
  }
  const bool within =
      outcome.position != 0 && (line.rank == 0 || outcome.position <= line.rank);
  outcome.pass = line.negative ? !within : within;
  if (!outcome.pass) {
    outcome.gap = line.negative           ? "a forbidden result ranks too high"
                  : outcome.position == 0 ? "not produced"
                                          : "produced but ranked too low";
  }
  return outcome;
}

/// Reads a results file into id → status. Returns: empty when there is no such file.
std::map<std::string, std::string> ReadBaseline(const fs::path& file) {
  std::map<std::string, std::string> out;
  std::ifstream in(file);
  for (std::string row; std::getline(in, row);) {
    const std::vector<std::string> f = Split(row, '\t');
    if (f.size() >= 2 && f[0] != "id") out[f[0]] = f[1];
  }
  return out;
}

/// Prints a snapshot's candidates, one per line: place, target, cost, text and pieces.
void Print(const Snapshot& snapshot) {
  const char* const targets[] = {"zh", "ja", "en"};
  for (size_t i = 0; i < snapshot.candidates.size(); ++i) {
    const Candidate& c = snapshot.candidates[i];
    std::printf("%2zu %s %6d %s  [", i + 1, targets[static_cast<int>(c.target)], c.cost,
                c.text.c_str());
    for (const Piece& piece : c.pieces) {
      std::printf(" %s<%s>%s", piece.text.c_str(), piece.reading.c_str(), piece.pinned ? "*" : "");
    }
    std::printf(" ]\n");
  }
}

}  // namespace

/// Returns: the default options, with the prior costs named in the environment variable
///   KOTOMO_COSTS replaced (`english_step=20,language_switch=400`), for tests/conversion/calibrate.sh:
///   the sweep runs the whole corpus for each setting (PLAN.md §5.3), never one line.
/// Notes: an unknown name or a value that is not a number ends the run, so a sweep cannot
///   silently measure the defaults.
Options CalibratedOptions() {
  Options options;
  const char* spec = std::getenv("KOTOMO_COSTS");
  if (spec == nullptr) return options;
  const std::pair<std::string_view, int32_t Costs::*> names[] = {
      {"pinyin_syllable", &Costs::pinyin_syllable}, {"chinese_rank", &Costs::chinese_rank},
      {"chinese_composed", &Costs::chinese_composed}, {"japanese_bias", &Costs::japanese_bias},
      {"english_base", &Costs::english_base}, {"english_step", &Costs::english_step},
      {"japanese_word", &Costs::japanese_word}, {"pinyin_to_japanese", &Costs::pinyin_to_japanese},
      {"english_to_japanese", &Costs::english_to_japanese},
      {"english_in_chinese", &Costs::english_in_chinese},
      {"english_in_japanese", &Costs::english_in_japanese}, {"particle", &Costs::particle},
      {"language_switch", &Costs::language_switch}, {"crossed_hint", &Costs::crossed_hint}};
  std::string_view rest = spec;
  while (!rest.empty()) {
    const std::string_view item = rest.substr(0, rest.find(','));
    rest.remove_prefix(std::min(rest.size(), item.size() + 1));
    const size_t equals = item.find('=');
    const auto name = std::find_if(std::begin(names), std::end(names), [&](const auto& n) {
      return n.first == item.substr(0, equals);
    });
    char* end = nullptr;
    const std::string value(equals == std::string_view::npos ? "" : item.substr(equals + 1));
    const long number = std::strtol(value.c_str(), &end, 10);
    if (name == std::end(names) || value.empty() || *end != 0) {
      std::fprintf(stderr, "mixed corpus: KOTOMO_COSTS: cannot read '%.*s'\n",
                   static_cast<int>(item.size()), item.data());
      std::exit(2);
    }
    options.costs.*(name->second) = static_cast<int32_t>(number);
  }
  return options;
}

/// Reads the Japanese fuzzy rules of a line's mode (`japanese+jfuzzy:voiced,cu`, feedback B4).
/// Inputs: `mode` is cut back to the mode itself.
/// Returns: the rules' bits, 0 without any; nullopt for an option or a rule it does not know.
std::optional<uint16_t> FuzzyRules(std::string* mode) {
  const size_t plus = mode->find('+');
  if (plus == std::string::npos) return 0;
  const std::string option = mode->substr(plus + 1);
  if (option.rfind("jfuzzy:", 0) != 0) return std::nullopt;
  mode->resize(plus);
  const std::pair<std::string_view, uint16_t> names[] = {
      {"long_vowel", kFuzzyLongVowel}, {"sokuon", kFuzzySokuon}, {"moraic_n", kFuzzyMoraicN},
      {"voiced", kFuzzyVoiced}, {"semi_voiced", kFuzzySemiVoiced}, {"yoon", kFuzzyYoon},
      {"yotsugana", kFuzzyYotsugana}, {"ra_na_da", kFuzzyRaNaDa}, {"hi_shi", kFuzzyHiShi},
      {"loanword", kFuzzyLoanword}, {"l_as_r", kFuzzyLAsR}, {"cu", kFuzzyCu}, {"all", kFuzzyAll}};
  uint16_t rules = 0;
  std::string_view rest = std::string_view(option).substr(7);
  while (!rest.empty()) {
    const std::string_view item = rest.substr(0, rest.find(','));
    rest.remove_prefix(std::min(rest.size(), item.size() + 1));
    const auto name = std::find_if(std::begin(names), std::end(names), [&](const auto& n) { return n.first == item; });
    if (name == std::end(names)) return std::nullopt;
    rules |= name->second;
  }
  return rules;
}

int main(int argc, char** argv) {
  const std::string option = argc == 4 ? argv[2] : "";
  if (argc != 2 && option != "--baseline" && option != "--convert" && option != "--refine") {
    std::fprintf(stderr,
                 "usage: %s <repository root> [--baseline <file> | --convert <composition> | --refine <composition>]\n",
                 argv[0]);
    return 2;
  }
  const fs::path root = argv[1];
  const fs::path build = root / "build";
  const fs::path scratch = build / "conversion" / "mixed-work";
  std::error_code ignored;
  fs::remove_all(scratch, ignored);
  fs::create_directories(scratch / "rime-user");
  fs::create_directories(scratch / "mozc-profile");

  std::vector<Line> lines;
  if (!ReadCorpus(root / "tests" / "conversion" / "corpus.tsv", &lines)) {
    std::fprintf(stderr, "mixed corpus: cannot read tests/conversion/corpus.tsv\n");
    return 2;
  }
  std::string error;
  const auto engines = host::Open({.rime_shared = (build / "rime-prebuilt").string(),
                                   .rime_user = (scratch / "rime-user").string(),
                                   .english_dict = (build / "lexicon/english/main_en_us.dict").string(),
                                   .mozc_service = (build / "mozc/out/host/kotomo_mozc_service").string(),
                                   .mozc_data = (build / "mozc/out/host/mozc.data").string(),
                                   .mozc_profile = (scratch / "mozc-profile").string()},
                                  &error);
  if (!engines) {
    std::fprintf(stderr, "mixed corpus: %s\n", error.c_str());
    return 2;
  }
  const auto lexicon = CrossLexicon::Map((build / "lexicon/cross.klx").string());
  if (!lexicon) {
    std::fprintf(stderr, "mixed corpus: no compiled cross-language lexicon in build/lexicon "
                         "(run java -Xmx4g lexicon/CrossLexicon.java, then kotomo_lexicon_compile)\n");
    return 2;
  }
  Coordinator coordinator(*engines, *lexicon, CalibratedOptions());
  if (option == "--convert") {
    // `--convert "jfuzzy:voiced,cu wadashi"` converts with Japanese fuzzy rules on.
    std::string composition = argv[3], rules = "x+" + composition.substr(0, composition.find(' '));
    Options options = CalibratedOptions();
    if (const auto fuzzy = FuzzyRules(&rules); fuzzy && *fuzzy != 0) {
      options.japanese_fuzzy = *fuzzy;
      composition.erase(0, composition.find(' ') + 1);
    }
    const auto start = std::chrono::steady_clock::now();
    Print(Coordinator(*engines, *lexicon, options).Convert(composition, 1));
    std::printf("%.1f ms\n", std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count());
    return 0;
  }
  if (option == "--refine") {
    // What kRefine lists for the composition, with the time the full conversion took.
    Session session(*engines, *lexicon);
    session.Handle({.generation = 1, .command = Command::kSet, .text = argv[3]});
    const auto start = std::chrono::steady_clock::now();
    const Snapshot full = session.Handle({.generation = 1, .command = Command::kRefine});
    const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count();
    Print(full);
    std::printf("%zu candidates, %lld ms\n", full.candidates.size(), static_cast<long long>(ms));
    return 0;
  }
  const bool compare = option == "--baseline";
  const std::map<std::string, std::string> baseline =
      compare ? ReadBaseline(argv[3]) : std::map<std::string, std::string>();

  std::string results = "id\tstatus\tposition\tshown\n";
  std::string mixed_report, single_report;
  std::vector<std::string> regressed, fixed;
  std::vector<double> millis;
  int mixed_pass = 0, mixed_total = 0, single_pass = 0, single_total = 0, skipped = 0;
  uint64_t generation = 0;
  for (Line& line : lines) {
    // A line with Japanese fuzzy rules runs with them; any other option (fuzzy pinyin, key
    // correction off) needs a deployed librime build and is skipped below.
    const std::string full_mode = line.mode;
    const uint16_t fuzzy = FuzzyRules(&line.mode).value_or(0);
    if (fuzzy == 0) line.mode = full_mode;
    const bool mixed = line.mode == "mixed";
    // Fuzzy-pinyin lines need a deployed rule file and lines without input are predictions:
    // neither is part of the prototype.
    const std::map<std::string, Lang> single = {{"chinese", Lang::kChinese},
                                                {"japanese", Lang::kJapanese},
                                                {"english", Lang::kEnglish}};
    if (line.input.empty() || (!mixed && !single.count(line.mode))) {
      ++skipped;
      continue;
    }
    const auto start = std::chrono::steady_clock::now();
    Options with_rules = CalibratedOptions();
    with_rules.japanese_fuzzy = fuzzy;
    Snapshot snapshot = fuzzy != 0 ? Coordinator(*engines, *lexicon, with_rules).Convert(line.input, ++generation)
                                   : coordinator.Convert(line.input, ++generation);
    // As the app does: a list that was cut short is asked for in full, and that one is ranked.
    if (snapshot.more) {
      snapshot = Coordinator(*engines, *lexicon, with_rules.Full()).Convert(line.input, generation);
    }
    millis.push_back(std::chrono::duration<double, std::milli>(
                         std::chrono::steady_clock::now() - start).count());
    const Outcome outcome = Judge(line, snapshot, mixed ? nullptr : &single.at(line.mode));
    char row[1024];
    std::snprintf(row, sizeof row, "%s %s %s '%s' want %s%s within %d: at %s [%s]%s%s\n",
                  outcome.pass ? "PASS" : "FAIL", line.id.c_str(), full_mode.c_str(),
                  line.input.c_str(), line.negative ? "!" : "", line.wanted.front().c_str(),
                  line.rank, outcome.position ? std::to_string(outcome.position).c_str() : "-",
                  outcome.shown.c_str(), outcome.gap.empty() ? "" : " -- gap: ",
                  outcome.gap.c_str());
    (mixed ? mixed_report : single_report) += row;
    (mixed ? mixed_total : single_total) += 1;
    (mixed ? mixed_pass : single_pass) += outcome.pass ? 1 : 0;
    results += line.id + "\t" + (outcome.pass ? "PASS" : "FAIL") + "\t" +
               (outcome.position ? std::to_string(outcome.position) : "-") + "\t" + outcome.shown +
               "\n";
    const auto before = baseline.find(line.id);
    if (before != baseline.end() && mixed) {
      if (before->second == "PASS" && !outcome.pass) regressed.push_back(line.id);
      if (before->second == "FAIL" && outcome.pass) fixed.push_back(line.id);
    }
  }
  std::sort(millis.begin(), millis.end());
  char summary[512];
  std::snprintf(summary, sizeof summary,
                "Mixed lines through the coordinator: %d/%d pass\n"
                "Single-language lines through the coordinator, judged among the candidates of "
                "their language: %d/%d pass (%d lines skipped: fuzzy rules or no input)\n"
                "Host time per line: median %.1f ms, max %.1f ms (host only; never device latency)\n",
                mixed_pass, mixed_total, single_pass, single_total, skipped,
                millis.empty() ? 0.0 : millis[millis.size() / 2],
                millis.empty() ? 0.0 : millis.back());
  std::string report = "S6: the conversion corpus through the mixed-input coordinator (host)\n\n" +
                       mixed_report + "\n" + single_report + "\n" + summary;
  if (compare) {
    report += "Against " + std::string(argv[3]) + " (Mixed lines): " +
              std::to_string(regressed.size()) + " regressed, " + std::to_string(fixed.size()) +
              " fixed\n";
    for (const std::string& id : regressed) report += "REGRESSED " + id + "\n";
    for (const std::string& id : fixed) report += "FIXED " + id + "\n";
  }
  std::ofstream(build / "conversion" / "mixed-results.tsv") << results;
  std::ofstream(build / "conversion" / "mixed-report.txt") << report;
  std::fputs(report.c_str(), stdout);
  return regressed.empty() ? 0 : 1;
}
