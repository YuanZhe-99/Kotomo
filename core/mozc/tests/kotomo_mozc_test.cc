// Host tests for Kotomo's Mozc glue (spike S3, PLAN.md §7).
//
// Usage: kotomo_mozc_test <path to mozc.data>
// Prints each conversion with its host latency, so the spike report can quote
// it; the latencies are host numbers, never device numbers.

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <optional>
#include <string>
#include <vector>

#include "gtest/gtest.h"
#include "kotomo/kotomo_mozc.h"

namespace kotomo::mozc {
namespace {

std::string g_data_path;

/// Prints a conversion as segments, with the candidates of each, and timing.
void Print(const char* label, const std::optional<Conversion>& conversion,
           double millis) {
  std::printf("[%s] %.2f ms\n", label, millis);
  if (!conversion) {
    std::printf("  (no conversion)\n");
    return;
  }
  std::printf("  sentence: %s\n", conversion->Sentence().c_str());
  for (const Segment& segment : conversion->segments) {
    std::printf("  %s%s:", segment.key.c_str(), segment.pinned ? " [pinned]" : "");
    for (const Candidate& candidate : segment.candidates) {
      std::printf(" %s(%u/%u)", candidate.value.c_str(), candidate.lid,
                  candidate.rid);
    }
    std::printf("\n");
  }
}

/// Runs `convert` and prints its result with the elapsed time.
template <typename F>
std::optional<Conversion> Timed(const char* label, F convert) {
  const auto start = std::chrono::steady_clock::now();
  std::optional<Conversion> conversion = convert();
  const std::chrono::duration<double, std::milli> elapsed =
      std::chrono::steady_clock::now() - start;
  Print(label, conversion, elapsed.count());
  return conversion;
}

class MozcEngineTest : public ::testing::Test {
 protected:
  static void SetUpTestSuite() {
    const char* tmp = std::getenv("TEST_TMPDIR");
    engine_ = MozcEngine::Open(g_data_path, tmp ? tmp : "/tmp").release();
    ASSERT_NE(engine_, nullptr) << "cannot open " << g_data_path;
    std::printf("Mozc data version: %s\n", engine_->DataVersion().c_str());
  }
  static void TearDownTestSuite() { delete engine_; }

  static MozcEngine* engine_;
};

MozcEngine* MozcEngineTest::engine_ = nullptr;

// S3: a session converts a hiragana reading as a whole sentence.
TEST_F(MozcEngineTest, SessionConvertsHiraganaSentence) {
  const auto conversion = Timed("session わたしはにほんじん", [] {
    return engine_->SessionConvert("わたしはにほんじん", /*incognito=*/true);
  });
  ASSERT_TRUE(conversion);
  EXPECT_EQ(conversion->Sentence(), "私は日本人");
}

// S3, T03-001's reading typed as romaji goes through Mozc's romaji table.
TEST_F(MozcEngineTest, SessionConvertsRomaji) {
  const auto conversion = Timed("session watashihanihonjin", [] {
    return engine_->SessionConvert("watashihanihonjin", /*incognito=*/true);
  });
  ASSERT_TRUE(conversion);
  EXPECT_EQ(conversion->Sentence(), "私は日本人");
  // T03-001 proper (`wa` for the particle) is reported, not asserted: the
  // corpus decides its rank once the coordinator exists.
  Timed("session watashiwanihonjin (T03-001)", [] {
    return engine_->SessionConvert("watashiwanihonjin", /*incognito=*/true);
  });
}

// S3, PLAN.md §5.4: the lid/rid of a pinned span come from Mozc's dictionary.
TEST_F(MozcEngineTest, LooksUpPosIdsOfDictionaryEntry) {
  const PosIds ids = engine_->LookupPosIds("いけぶくろ", "池袋");
  std::printf("池袋/いけぶくろ: lid=%u rid=%u from_dictionary=%d\n", ids.lid,
              ids.rid, ids.from_dictionary);
  EXPECT_TRUE(ids.from_dictionary);
  EXPECT_NE(ids.lid, 0);
}

// S3, T07-001 at the converter stage: [池袋/いけぶくろ pinned] + にかいにいこう.
TEST_F(MozcEngineTest, PinnedSpanConvertsWithTheRest) {
  const auto conversion = Timed("pinned 池袋 + にかいにいこう", [] {
    return engine_->PinnedConvert(
        {{.reading = "いけぶくろ", .surface = "池袋"},
         {.reading = "にかいにいこう"}},
        /*incognito=*/true);
  });
  ASSERT_TRUE(conversion);
  EXPECT_EQ(conversion->Sentence(), "池袋に買いに行こう");
  ASSERT_FALSE(conversion->segments.empty());
  const Segment& first = conversion->segments.front();
  EXPECT_TRUE(first.pinned);
  EXPECT_EQ(first.candidates.front().value, "池袋");
  for (size_t i = 1; i < conversion->segments.size(); ++i) {
    EXPECT_FALSE(conversion->segments[i].pinned) << i;
  }
}

// Negative: a pinyin spelling is not a Japanese reading, so Mozc has no entry
// and the glue falls back to the general noun (PLAN.md §5.9) and says so.
TEST_F(MozcEngineTest, PinyinIsNotAMozcReading) {
  const PosIds ids = engine_->LookupPosIds("ちだい", "池袋");
  EXPECT_FALSE(ids.from_dictionary);
}

// Negative: Mozc must not replace a pinned surface it has no entry for with its
// own choice (池袋); the pin holds with the general-noun fallback ids.
TEST_F(MozcEngineTest, UnknownPinnedSurfaceStaysPinned) {
  const auto conversion = Timed("pinned イケブクロ + にかいにいこう", [] {
    return engine_->PinnedConvert(
        {{.reading = "いけぶくろ", .surface = "イケブクロ"},
         {.reading = "にかいにいこう"}},
        /*incognito=*/true);
  });
  ASSERT_TRUE(conversion);
  EXPECT_EQ(conversion->segments.front().candidates.front().value, "イケブクロ");
  EXPECT_NE(conversion->Sentence().find("イケブクロ"), std::string::npos);
}

// Negative: a span without a reading is rejected rather than converted.
TEST_F(MozcEngineTest, RejectsEmptySpan) {
  EXPECT_FALSE(engine_->PinnedConvert({{.reading = "", .surface = "池袋"}},
                                      /*incognito=*/true));
  EXPECT_FALSE(engine_->PinnedConvert({}, /*incognito=*/true));
}

}  // namespace
}  // namespace kotomo::mozc

int main(int argc, char** argv) {
  ::testing::InitGoogleTest(&argc, argv);
  if (argc < 2) {
    std::fprintf(stderr, "usage: %s <mozc.data>\n", argv[0]);
    return 2;
  }
  kotomo::mozc::g_data_path = argv[1];
  return RUN_ALL_TESTS();
}
