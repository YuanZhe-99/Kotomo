// Host test of the librime bridge for spike S2: deploys luna_pinyin_simp from a fresh user
// directory, converts full pinyin (T01) and checks that sensitive mode learns nothing (T12).
//
// Usage: kotomo_rime_test <shared data dir> <user data dir>
// The user data directory is deleted and recreated; CMake passes one inside the build tree.

#include <chrono>
#include <cstdio>
#include <filesystem>
#include <string>
#include <vector>

#include "rime_bridge.h"

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
  rb::Shutdown();

  std::printf("%s: %d failure(s)\n", failures == 0 ? "PASS" : "FAIL", failures);
  return failures == 0 ? 0 : 1;
}
