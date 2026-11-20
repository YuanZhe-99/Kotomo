// Host test of the librime bridge for spike S2: deploys luna_pinyin_simp from a fresh user
// directory, converts full pinyin (T01) and checks that sensitive mode learns nothing (T12).
//
// Usage: kotomo_rime_test <shared data dir> <user data dir>
// The user data directory is deleted and recreated; CMake passes one inside the build tree.

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <filesystem>
#include <string>
#include <vector>

#include "kotomo_rime_api.h"
#include "rime_bridge.h"
#include "rime_spellings.h"

namespace rb = kotomo::rime_bridge;
using Clock = std::chrono::steady_clock;

namespace {

int failures = 0;

/// Records one named check and prints its result.
void Check(bool ok, const char* id, const std::string& detail) {
  std::printf("%s %s: %s\n", ok ? "PASS" : "FAIL", id, detail.c_str());
  if (!ok) ++failures;
}

/// Returns: the 0-based rank of `text` among the first 50 candidates for `input`, or -1.
int RankOf(const std::string& input, const std::string& text) {
  rb::Clear();
  rb::SetInput(input);
  const auto candidates = rb::Candidates(50);
  for (size_t i = 0; i < candidates.size(); ++i) {
    if (candidates[i].text == text) return static_cast<int>(i);
  }
  return -1;
}

/// Returns: milliseconds elapsed since `start`.
double MillisSince(Clock::time_point start) {
  return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
}

/// Types `input`, selects the candidate `text` and returns what librime committed.
std::string Commit(const std::string& input, const std::string& text) {
  rb::Clear();
  rb::SetInput(input);
  const auto candidates = rb::Candidates(50);
  for (size_t i = 0; i < candidates.size(); ++i) {
    if (candidates[i].text == text) {
      rb::SelectCandidate(i);
      return rb::TakeCommit();
    }
  }
  return {};
}

}  // namespace

int main(int argc, char** argv) {
  if (argc != 3) {
    std::fprintf(stderr, "usage: %s <shared data dir> <user data dir>\n", argv[0]);
    return 2;
  }
  const std::filesystem::path user_dir = argv[2];
  std::filesystem::remove_all(user_dir);
  std::filesystem::create_directories(user_dir);

  rb::Initialize(argv[1], user_dir.string());
  auto start = Clock::now();
  const bool deployed = rb::Deploy();
  const double deploy_ms = MillisSince(start);
  Check(deployed, "S2-deploy", "fresh deploy of luna_pinyin_simp in " +
                                   std::to_string(static_cast<int>(deploy_ms)) + " ms");
  start = Clock::now();
  Check(rb::Deploy(), "S2-redeploy", "unchanged deploy in " +
                                         std::to_string(static_cast<int>(MillisSince(start))) +
                                         " ms");
  Check(rb::SetLearning(true), "S2-schema", "luna_pinyin_simp selected");

  // Q7: the function table the coordinator's library receives (kotomo_rime_api.h). The session mapped
  // the deployed spelling table by itself.
  {
    const KotomoRimeApi* table = KotomoRimeGetApi();
    Check(table->version == KOTOMO_RIME_API_VERSION, "Q7-rime-api-version", "table version");
    KotomoRimeSpelling spelled[8];
    const size_t n = table->spellings("xian", 4, spelled, 8);
    bool xi = false, xian = false;
    for (size_t i = 0; i < n; ++i) {
      xi |= spelled[i].length == 2 && spelled[i].fuzzy == 0;
      xian |= spelled[i].length == 4 && spelled[i].fuzzy == 0;
    }
    Check(xi && xian, "Q7-rime-api-spellings", "xian starts with xi and xian, through the table");
    Check(table->spellings("xian", 4, spelled, 1) == 1, "Q7-rime-api-capacity",
          "no more spellings than the caller has room for");
    const size_t initials = table->spellings("kq", 2, spelled, 8);
    Check(initials == 1 && spelled[0].length == 1 && spelled[0].partial == 1, "Q7-rime-api-no-spelling",
          "kq spells no syllable: only the initial k, marked unfinished");
    const size_t begun = table->spellings("xio", 3, spelled, 8);
    bool whole = false, xi_full = false;
    for (size_t i = 0; i < begun; ++i) {
      whole |= spelled[i].length == 3 && spelled[i].partial == 1;
      xi_full |= spelled[i].length == 2 && spelled[i].partial == 0;
    }
    Check(whole && xi_full, "A7-rime-api-unfinished",
          "xio is the unfinished start of a syllable, after the full syllable xi");
    struct Words {
      std::vector<std::string> texts;
      std::vector<int> composed;
    } words;
    const KotomoRimeWordSink sink = [](void* context, const char* text, size_t size, int composed) {
      auto* out = static_cast<Words*>(context);
      out->texts.emplace_back(text, size);
      out->composed.push_back(composed);
    };
    table->convert("ni'hao", 6, 3, &words, sink);
    Check(!words.texts.empty() && words.texts[0] == "你好" && words.composed[0] == 0 &&
              words.texts.size() <= 3,
          "Q7-rime-api-convert", "ni'hao -> 你好 first, a dictionary entry, at most 3");
    Check(rb::Composition().empty(), "Q7-rime-api-convert-clears",
          "the session's composition is empty after a conversion");
    words = {};
    table->convert("kq", 2, 3, &words, sink);
    Check(words.texts.empty(), "Q7-rime-api-convert-none", "kq converts to nothing");
    // Feedback A9: a long list holds the run's rarer words and never a word for a prefix of it
    // (实际 is shi'ji, as long as the run in characters).
    words = {};
    table->convert("shi'jian", 8, 1000, &words, sink);
    const auto listed = [&words](const char* text) {
      return std::find(words.texts.begin(), words.texts.end(), text) != words.texts.end();
    };
    Check(listed("时间") && listed("试件"), "A9-rime-api-convert-all", "shi'jian lists 时间 and the rare 试件");
    Check(!listed("实际") && !listed("世纪"), "A9-N-rime-api-convert-no-prefix-word",
          "shi'jian does not list words for shi'ji");
    words = {};
    table->convert("yu", 2, 1000, &words, sink);
    Check(words.texts.size() > 100 && listed("语"),
          "A9-rime-api-convert-every-character", "yu lists more than a hundred characters, 语 among them");
  }

  // S6: the coordinator reads syllables from the deployed schema's spelling table.
  {
    const std::string prism = (user_dir / "build" / "luna_pinyin_simp.prism.bin").string();
    Check(rb::OpenSpellings(prism), "S6-spellings-open", "prism of luna_pinyin_simp mapped");
    std::vector<rb::SpellingMatch> found;
    const auto has = [&found](std::size_t length, bool fuzzy) {
      for (const rb::SpellingMatch& m : found) {
        if (m.length == length && m.fuzzy == fuzzy && !m.partial) return true;
      }
      return false;
    };
    const auto full = [&found] {
      for (const rb::SpellingMatch& m : found) {
        if (!m.partial) return true;
      }
      return false;
    };
    rb::Spellings("zhongguo", &found);
    Check(has(5, false), "S6-spellings-zhong", "zhongguo starts with the syllable zhong");
    rb::Spellings("xian", &found);
    Check(has(2, false) && has(4, false), "S6-spellings-xi-xian", "xian starts with xi and xian");
    rb::Spellings("hoa", &found);
    Check(has(3, true) && !has(3, false), "S6-spellings-correction",
          "hoa is a syllable only through the schema's correction");
    // Negative: abbreviations and non-syllables are not syllables.
    rb::Spellings("zg", &found);
    Check(!full() && found.size() == 1 && found[0].length == 1 && found[0].partial,
          "S6-spellings-no-abbreviation", "zg spells no full syllable, only the initial z");
    rb::Spellings("kq", &found);
    Check(!full(), "S6-spellings-none", "kq spells no full syllable");
    rb::Spellings("vq", &found);
    Check(found.empty(), "S6-spellings-nothing", "vq spells nothing at all");
    Check(!rb::OpenSpellings(prism + ".missing"), "S6-spellings-missing-file",
          "a missing prism is refused");
    rb::CloseSpellings();
    rb::Spellings("zhong", &found);
    Check(found.empty(), "S6-spellings-closed", "nothing after CloseSpellings");
  }

  // T01: full pinyin, Simplified output.
  const int nihao = RankOf("nihao", "你好");
  Check(nihao == 0, "T01-S2-nihao", "nihao -> 你好 at rank " + std::to_string(nihao));
  const int zhongguo = RankOf("zhongguo", "中国");
  Check(zhongguo == 0, "T01-S2-zhongguo", "zhongguo -> 中国 at rank " + std::to_string(zhongguo));
  // Negative: with zh_hans on, the Traditional form must not be offered.
  Check(RankOf("zhongguo", "中國") == -1, "T01-S2-zhongguo-simplified",
        "zhongguo offers no Traditional 中國");
  rb::SetOption("zh_hans", false);
  Check(RankOf("zhongguo", "中國") == 0, "T01-S2-zhongguo-traditional",
        "with zh_hans off, zhongguo -> 中國 at rank 0");
  rb::SetOption("zh_hans", true);

  // T11: regional output. Taiwan uses its own phrase (軟體), Hong Kong and the Mainland do not; the
  // app sets these options again after every schema re-select (SetLearning resets them).
  Check(RankOf("ruanjian", "软件") == 0, "T11-mainland", "ruanjian -> 软件 at rank 0");
  rb::SetOption("zh_hans", false);
  rb::SetOption("zh_hant_tw", true);
  Check(RankOf("ruanjian", "軟體") == 0, "T11-taiwan", "with zh_hant_tw, ruanjian -> 軟體 at rank 0");
  Check(RankOf("ruanjian", "軟件") == -1, "T11-taiwan-no-mainland-phrase", "with zh_hant_tw, no 軟件");
  rb::SetOption("zh_hant_tw", false);
  rb::SetOption("zh_hant_hk", true);
  Check(RankOf("ruanjian", "軟件") == 0, "T11-hongkong", "with zh_hant_hk, ruanjian -> 軟件 at rank 0");
  Check(RankOf("ruanjian", "軟體") == -1, "T11-hongkong-no-taiwan-phrase", "with zh_hant_hk, no 軟體");
  rb::SetOption("zh_hant_hk", false);
  rb::SetOption("zh_hans", true);
  // T14: candidate details (long press): the spelling and the translation type.
  {
    rb::SetInput("nihao");
    const auto details = rb::Candidates(1);
    Check(!details.empty() && details[0].comment.find("ni") != std::string::npos && !details[0].type.empty(),
          "T14-candidate-details", "first candidate has spelling and type (" +
                                       (details.empty() ? std::string("none") : details[0].type) + ")");
    rb::Clear();
  }

  Check(rb::SetInput("nihao") && rb::Composition() == "ni hao", "S2-composition",
        "preedit for nihao is \"" + rb::Composition() + "\"");
  Check(rb::ProcessKey(0xff08, 0) && rb::Composition() == "ni ha", "S2-backspace",
        "BackSpace leaves \"" + rb::Composition() + "\"");
  rb::Clear();
  for (const char c : std::string("zhongguo")) rb::ProcessKey(c, 0);
  Check(rb::ProcessKey(0x20, 0) && rb::TakeCommit() == "中国", "S2-keys",
        "keys z h o n g g u o + space commit 中国");

  // Per-key cost on this host (set_input + first 5 candidates), not a device latency.
  const std::string sentence = "woxiangqurijuanxuexiriyu";
  start = Clock::now();
  for (size_t i = 1; i <= sentence.size(); ++i) {
    rb::SetInput(sentence.substr(0, i));
    rb::Candidates(5);
  }
  std::printf("INFO host per-key cost: %.2f ms over %zu prefixes\n",
              MillisSince(start) / sentence.size(), sentence.size());

  // T12: in sensitive mode, commits must not change ranking; with learning on they must.
  const std::string input = "shi";
  const auto baseline = (rb::Clear(), rb::SetInput(input), rb::Candidates(10));
  const std::string word = baseline.size() > 6 ? baseline[6].text : "";
  const int before = RankOf(input, word);
  Check(rb::SetLearning(false), "T12-S2-private-schema", "luna_pinyin_simp_private selected");
  for (int i = 0; i < 3; ++i) Commit(input, word);
  const int private_rank = RankOf(input, word);
  rb::SetLearning(true);
  const int learning_rank_after_private = RankOf(input, word);
  Check(before == 6 && private_rank == before && learning_rank_after_private == before,
        "T12-S2-no-learning",
        "rank of the 7th candidate for shi stays " + std::to_string(before) + " (private " +
            std::to_string(private_rank) + ", normal " +
            std::to_string(learning_rank_after_private) + ") after 3 private commits");
  for (int i = 0; i < 3; ++i) Commit(input, word);
  const int learned_rank = RankOf(input, word);
  Check(learned_rank >= 0 && learned_rank < before, "T12-S2-learning-control",
        "with learning on, 3 commits move it from rank " + std::to_string(before) + " to " +
            std::to_string(learned_rank));

  rb::Shutdown();

  // PLAN §4.2, as the app does it: a separate process deploys into a staging directory, the
  // keyboard moves its build/ into the user directory and reopens librime there without deploying
  // (in the same process that had librime open before).
  const std::filesystem::path staging = user_dir.string() + "-staging";
  const std::filesystem::path swapped = user_dir.string() + "-swapped";
  std::filesystem::remove_all(staging);
  std::filesystem::remove_all(swapped);
  std::filesystem::create_directories(staging);
  const bool opened_empty = rb::Initialize(argv[1], staging.string());
  std::printf("INFO a session %s before the first deploy\n", opened_empty ? "opens" : "does not open");
  start = Clock::now();
  const bool staged = rb::Deploy();
  rb::Shutdown();
  Check(staged && std::filesystem::is_directory(staging / "build"), "S2-staged-deploy",
        "deploy into a staging directory in " + std::to_string(static_cast<int>(MillisSince(start))) +
            " ms");
  std::filesystem::create_directories(swapped);
  std::filesystem::rename(staging / "build", swapped / "build");
  start = Clock::now();
  Check(rb::Initialize(argv[1], swapped.string()), "S2-swap-open",
        "session opens on the moved build without a deploy in " +
            std::to_string(static_cast<int>(MillisSince(start))) + " ms");
  const int swapped_rank = RankOf("nihao", "你好");
  Check(swapped_rank == 0, "S2-swap-convert", "after the swap nihao -> 你好 at rank " + std::to_string(swapped_rank));
  rb::Shutdown();

  // Prebuilt data, as the app ships it: the build host deploys and puts build/ into a copy of the
  // shared data; the phone opens that with an empty user directory and never deploys.
  const std::filesystem::path prebuilt_shared = user_dir.string() + "-prebuilt-shared";
  const std::filesystem::path fresh_user = user_dir.string() + "-fresh";
  std::filesystem::remove_all(prebuilt_shared);
  std::filesystem::remove_all(fresh_user);
  std::filesystem::copy(argv[1], prebuilt_shared, std::filesystem::copy_options::recursive);
  std::filesystem::rename(swapped / "build", prebuilt_shared / "build");
  std::filesystem::create_directories(fresh_user);
  start = Clock::now();
  Check(rb::Initialize(prebuilt_shared.string(), fresh_user.string()), "S2-prebuilt-open",
        "session opens on prebuilt data with an empty user directory in " +
            std::to_string(static_cast<int>(MillisSince(start))) + " ms");
  const int prebuilt_rank = RankOf("nihao", "你好");
  Check(prebuilt_rank == 0, "S2-prebuilt-convert", "on prebuilt data nihao -> 你好 at rank " + std::to_string(prebuilt_rank));
  Check(RankOf("zhongguo", "中国") == 0, "S2-prebuilt-simplified", "on prebuilt data zhongguo -> 中国 at rank 0");
  Check(!std::filesystem::exists(fresh_user / "build" / "luna_pinyin.table.bin"), "S2-prebuilt-no-compile",
        "nothing was compiled into the user directory");
  // The keyboard's own path: keys one by one on a freshly opened session (no SetLearning call).
  const std::string self_check = rb::SelfCheck();
  Check(self_check.find("key_n=handled") != std::string::npos && self_check.find("ascii_mode=off") != std::string::npos,
        "S2-prebuilt-keys", "self-check: " + self_check);
  rb::Clear();
  for (const char c : std::string("nihao")) rb::ProcessKey(c, 0);
  Check(rb::ProcessKey(0x20, 0) && rb::TakeCommit() == "你好", "S2-prebuilt-space",
        "keys n i h a o + space commit 你好 on prebuilt data");

  // T17: Kotomo's run-time dictionaries (luna_pinyin_simp.custom.yaml). Made-up words on codes no
  // built-in word uses, plus one low-priority entry on a common code.
  Check(RankOf("kaotuomo", "考拓莫") == -1 && RankOf("koutuomo", "蔻拓摩") == -1, "T17-rime-absent",
        "before the files exist the test words are not offered");
  const auto write = [&](const char* name, const std::string& lines) {
    std::FILE* f = std::fopen((fresh_user / name).string().c_str(), "wb");
    std::fputs(lines.c_str(), f);
    std::fclose(f);
  };
  write("custom_phrase.txt", "考拓莫\tkaotuomo\t100\n");
  write("kotomo_packs.txt", "蔻拓摩\tkoutuomo\t50\n");
  write("kotomo_low.txt", "泥好\tnihao\t20\n");
  Check(rb::SetLearning(true), "T17-rime-reload", "re-selecting the schema reloads the tables");
  const int manual = RankOf("kaotuomo", "考拓莫");
  Check(manual == 0, "T17-rime-manual", "a hand-added entry is first: rank " + std::to_string(manual));
  const int pack = RankOf("koutuomo", "蔻拓摩");
  Check(pack >= 0, "T17-rime-pack", "a downloaded entry is offered: rank " + std::to_string(pack));
  const int low = RankOf("nihao", "泥好");
  const int common = RankOf("nihao", "你好");
  Check(low > 0 && common == 0, "T17-rime-low",
        "a low-priority entry is offered (rank " + std::to_string(low) + ") but 你好 stays first (rank " +
            std::to_string(common) + ")");
  // Mixed mode sends syllable-separated keys (`kao'tuo'mo`); user_table entries must still appear.
  {
    struct ApiWords {
      std::vector<std::string> texts;
      std::vector<int> composed;
    };
    const auto convert_api = [](const std::string& input, size_t limit) {
      ApiWords out;
      KotomoRimeGetApi()->convert(
          input.data(), input.size(), limit, &out,
          [](void* context, const char* text, size_t size, int composed) {
            auto* w = static_cast<ApiWords*>(context);
            w->texts.emplace_back(text, size);
            w->composed.push_back(composed);
          });
      return out;
    };
    const ApiWords manual_api = convert_api("kao'tuo'mo", 10);
    Check(!manual_api.texts.empty() && manual_api.texts[0] == "考拓莫" && manual_api.composed[0] == 0,
          "T17-rime-api-manual", "Mixed API exposes manual user_table entry on kao'tuo'mo");
    const ApiWords pack_api = convert_api("kou'tuo'mo", 10);
    Check(!pack_api.texts.empty() && pack_api.texts[0] == "蔻拓摩" && pack_api.composed[0] == 0,
          "T17-rime-api-pack", "Mixed API exposes pack user_table entry on kou'tuo'mo");
    const ApiWords low_api = convert_api("ni'hao", 20);
    const auto it_nihao = std::find(low_api.texts.begin(), low_api.texts.end(), "你好");
    const auto it_ni = std::find(low_api.texts.begin(), low_api.texts.end(), "泥好");
    Check(it_nihao == low_api.texts.begin() && it_ni != low_api.texts.end() && it_nihao < it_ni &&
              low_api.composed[static_cast<size_t>(it_ni - low_api.texts.begin())] == 0,
          "T17-rime-api-low", "Mixed API exposes low user_table entry after 你好 on ni'hao");
  }
  Check(rb::SetLearning(false) && RankOf("kaotuomo", "考拓莫") == 0, "T17-rime-private",
        "sensitive fields see the same dictionaries");
  write("custom_phrase.txt", "");
  rb::SetLearning(true);
  Check(RankOf("kaotuomo", "考拓莫") == -1, "T17-rime-removed", "an entry removed from the file is gone after a reload");
  rb::Shutdown();

  // Feedback B2: Chinese mode's key correction, as the app turns it on: the rule file names
  // librime's `enable_correction`, the schema is deployed, and the bridge lists a reading that
  // takes a key for its neighbour after the exact ones.
  const std::filesystem::path correcting = user_dir.string() + "-correction";
  std::filesystem::remove_all(correcting);
  std::filesystem::create_directories(correcting);
  {
    std::FILE* f = std::fopen((correcting / "kotomo_fuzzy.yaml").string().c_str(), "wb");
    std::fputs("patch:\n  translator/enable_correction: true\n", f);
    std::fclose(f);
  }
  rb::Initialize(argv[1], correcting.string());
  const bool corrected_build = rb::Deploy();
  rb::Shutdown();
  Check(corrected_build && rb::Initialize(argv[1], correcting.string()), "B2-rime-deploy",
        "the schema deploys with key correction on");
  rb::SetOption("zh_hans", true);
  const int slip = RankOf("nohao", "你好");
  Check(slip > 0 && slip < 5, "B2-rime-neighbour-key",
        "nohao offers 你好 (o for i), not first: rank " + std::to_string(slip));
  {
    rb::Clear();
    rb::SetInput("nu");
    const auto listed = rb::Candidates(50);
    bool exact_first = !listed.empty() && listed[0].text == "怒", seen = false, ordered = true;
    for (const auto& candidate : listed) {
      if (candidate.corrected) seen = true;
      else if (seen && candidate.whole) ordered = false;
    }
    Check(exact_first && seen && ordered, "B2-N-rime-exact-first",
          "nu: 怒 stays first and every corrected reading (你) follows the exact ones");
    Check(RankOf("nihao", "你好") == 0, "B2-N-rime-exact-unchanged", "nihao -> 你好 still first");
  }
  {
    // The list is reordered, so a pick counts in the list the bridge returned.
    const int at = RankOf("nohao", "你好");
    Check(at >= 0 && rb::SelectCandidate(static_cast<size_t>(at)) && rb::TakeCommit() == "你好",
          "B2-rime-select", "picking the corrected candidate commits it");
    // The coordinator's Chinese conversions leave corrected readings out (it corrects by touch).
    struct Texts { std::vector<std::string> texts; } words;
    KotomoRimeGetApi()->convert("no'hao", 6, 50, &words,
                               [](void* context, const char* text, size_t size, int) {
                                 static_cast<Texts*>(context)->texts.emplace_back(text, size);
                               });
    Check(std::find(words.texts.begin(), words.texts.end(), "你好") == words.texts.end(),
          "B2-N-rime-api-no-correction", "the coordinator's conversion of no'hao has no 你好");
  }
  rb::Shutdown();

  std::printf("%s: %d failure(s)\n", failures == 0 ? "PASS" : "FAIL", failures);
  return failures == 0 ? 0 : 1;
}
