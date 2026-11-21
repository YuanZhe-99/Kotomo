// Host test of finishing and of the coordinator end to end (spike S6; PLAN.md §5.3, §5.4)
// against the fake engines: what each target language yields, the pieces, the order.

#include "check.h"
#include "coordinator.h"
#include "fake_engines.h"

using namespace kotomo::mixed;
using kotomo::mixed::test::Check;

namespace {

/// Returns: the 0-based place of `text` in the snapshot, or -1.
int Place(const Snapshot& snapshot, const std::string& text) {
  for (size_t i = 0; i < snapshot.candidates.size(); ++i) {
    if (snapshot.candidates[i].text == text) return static_cast<int>(i);
  }
  return -1;
}

/// Returns: the candidate with `text`; the tests ask only for candidates they checked exist.
const Candidate& Get(const Snapshot& snapshot, const std::string& text) {
  return snapshot.candidates[Place(snapshot, text)];
}

/// Lists a snapshot's candidate texts for a failure message.
std::string Shown(const Snapshot& snapshot) {
  std::string out;
  for (const Candidate& c : snapshot.candidates) out += c.text + "(" + std::to_string(c.cost) + ") ";
  return out;
}

/// Returns: true when the candidate's pieces tile the composition's letters in order.
bool Tiles(const Snapshot& snapshot, const Candidate& candidate) {
  uint16_t at = 0;
  for (const Piece& piece : candidate.pieces) {
    if (piece.begin < at || piece.end <= piece.begin) return false;
    at = piece.end;
  }
  return !candidate.pieces.empty() && at <= snapshot.input.size();
}

}  // namespace

int main() {
  test::FakeEngines engines;
  const auto lexicon = test::FakeLexicon();
  Coordinator coordinator(engines, *lexicon);

  // Original language first: pinyin typed, Chinese first, the Japanese reading of it after.
  {
    const Snapshot s = coordinator.Convert("shu", 3);
    Check(s.generation == 3 && s.stage == 0 && s.input == "shu" && s.cursor == 3,
          "S6-coordinator-snapshot-header");
    Check(Place(s, "书") == 0 && Place(s, "書") > 0, "S6-coordinator-original-language-first",
          Shown(s));
    Check(Place(s, "树") > 0 && Get(s, "树").cost == Get(s, "书").cost + Options().costs.chinese_rank,
          "S6-finish-chinese-alternatives");
    const Candidate& cross = Get(s, "書");
    Check(cross.target == Lang::kJapanese && (cross.flags & kCross) && cross.pieces.size() == 1 &&
              cross.pieces[0].pinned && cross.pieces[0].origin == Origin::kPinyinJa &&
              cross.pieces[0].typed == Lang::kChinese && cross.pieces[0].reading == "しょ",
          "S6-finish-japanese-pinned-piece");
    // Negative: Chinese candidates carry no cross-language flag.
    Check(!(Get(s, "书").flags & kCross) && Get(s, "书").target == Lang::kChinese,
          "S6-coordinator-chinese-not-cross");
  }
  // One input, three languages (the behavior contract's `ai`).
  {
    const Snapshot s = coordinator.Convert("ai", 1);
    Check(Place(s, "爱") == 0 && Place(s, "AI") > 0 && Place(s, "愛") > 0,
          "S6-coordinator-three-languages", Shown(s));
    Check(Place(s, "あい") > Place(s, "愛"), "S6-finish-japanese-alternatives", Shown(s));
    int ai = 0;
    for (const Candidate& c : s.candidates) ai += c.text == "AI" ? 1 : 0;
    Check(ai == 1, "S6-coordinator-no-text-twice");
  }
  // A whole sentence: a pinyin word read as Japanese, a particle, kana. Pieces stay separate.
  {
    const Snapshot s = coordinator.Convert("tushuguan e iku", 1);
    Check(Place(s, "図書館へ行く") == 0, "S6-coordinator-cross-sentence", Shown(s));
    if (Place(s, "図書館へ行く") == 0) {
      const Candidate& c = s.candidates[0];
      Check(c.pieces.size() == 3 && c.pieces[0].text == "図書館" && c.pieces[0].pinned &&
                c.pieces[0].begin == 0 && c.pieces[0].end == 9 && c.pieces[1].text == "へ" &&
                c.pieces[1].origin == Origin::kRule && c.pieces[1].begin == 10 &&
                c.pieces[2].origin == Origin::kMozc && !c.pieces[2].pinned &&
                c.pieces[2].begin == 12 && c.pieces[2].end == 15 && Tiles(s, c),
            "S6-finish-japanese-pieces");
    }
  }
  // An English word read as Japanese, then kana; and the same word kept in a Chinese sentence.
  {
    const Snapshot s = coordinator.Convert("bookwokau", 1);
    Check(Place(s, "本を買う") >= 0 && Place(s, "本を買う") < 3, "S6-coordinator-english-to-japanese",
          Shown(s));
    const Snapshot zh = coordinator.Convert("henhaobook", 1);
    Check(Place(zh, "很好book") == 0, "S6-finish-chinese-keeps-english", Shown(zh));
    if (Place(zh, "很好book") == 0) {
      const Candidate& c = zh.candidates[0];
      Check(c.pieces.size() == 2 && c.pieces[0].origin == Origin::kRime &&
                c.pieces[1].origin == Origin::kEnglish && c.pieces[1].typed == Lang::kEnglish &&
                Tiles(zh, c),
            "S6-finish-chinese-pieces");
    }
  }
  // English words only: an English sentence, written with spaces.
  {
    const Snapshot s = coordinator.Convert("thebook", 1);
    Check(Place(s, "the book") == 0 && s.candidates[0].target == Lang::kEnglish &&
              s.candidates[0].pieces.size() == 2,
          "S6-finish-english", Shown(s));
  }
  // Original language first is a rule, not a matter of costs: even when the Japanese reading of
  // a pinyin word is cheaper, the Chinese candidate leads; only the aggressive level lets the
  // cheaper cross-language candidate lead.
  {
    Options dear;
    dear.costs.pinyin_syllable = 9000;
    Coordinator balanced(engines, *lexicon, dear);
    const Snapshot s = balanced.Convert("xian", 1);
    Check(Place(s, "先") == 0 && Place(s, "線") == 1 && Get(s, "線").cost < Get(s, "先").cost,
          "S6-coordinator-original-first-rule", Shown(s));
    dear.level = Level::kAggressive;
    Coordinator aggressive(engines, *lexicon, dear);
    const Snapshot a = aggressive.Convert("xian", 1);
    Check(Place(a, "線") == 0, "S6-coordinator-aggressive-level", Shown(a));
  }
  // The switches: with pinyin → Japanese off, no cross-language candidate (negative).
  {
    Options off;
    off.pinyin_to_japanese = false;
    Coordinator plain(engines, *lexicon, off);
    const Snapshot s = plain.Convert("shu", 1);
    Check(Place(s, "書") == -1 && Place(s, "书") == 0, "S6-coordinator-switch-off", Shown(s));
  }
  // Negative: input no engine reads, and characters outside a composition, get no candidates.
  {
    const Snapshot none = coordinator.Convert("kq", 9);
    Check(none.candidates.empty() && none.generation == 9 && none.input == "kq",
          "S6-coordinator-no-reading-no-candidates");
    const Snapshot digits = coordinator.Convert("shu1", 9);
    Check(digits.candidates.empty() && digits.input == "shu1", "S6-coordinator-refused-input");
    // A run librime cannot convert drops that path instead of showing letters as Chinese.
    const Snapshot unknown = coordinator.Convert("anan", 1);
    bool chinese = false;
    for (const Candidate& c : unknown.candidates) chinese |= c.target == Lang::kChinese;
    Check(!chinese, "S6-finish-chinese-no-conversion-no-candidate", Shown(unknown));
  }
  // An unfinished last syllable (feedback A7): Chinese completes it as Chinese mode does and
  // stands before any cross-language reading; Japanese keeps the letters after its sentence.
  {
    const Snapshot s = coordinator.Convert("shur", 1);
    Check(Place(s, "输入") == 0 && Get(s, "输入").target == Lang::kChinese, "A7-coordinator-unfinished-chinese-first", Shown(s));
    const Snapshot j = coordinator.Convert("shut", 1);
    Check(Place(j, "しゅt") >= 0 && Tiles(j, Get(j, "しゅt")) && Get(j, "しゅt").pieces.back().text == "t",
          "A7-coordinator-pending-japanese", Shown(j));
    // Negative: an English word is not followed by pending letters.
    const Snapshot e = coordinator.Convert("bookt", 1);
    Check(Place(e, "bookt") == -1 && Place(e, "book t") == -1, "A7-coordinator-pending-not-after-english", Shown(e));
  }
  // A delimiter the person typed is a boundary Mozc keeps (feedback A3): the spans on both
  // sides are fixed. Negative: the same letters without it are one free span.
  {
    const auto fixed_spans = [&engines](std::string_view typed) {
      engines.asked.clear();
      Coordinator(engines, *test::FakeLexicon()).Convert(typed, 1);
      for (const auto& spans : engines.asked) {
        if (spans.size() == 2 && spans[0].reading == "ねこ" && spans[1].reading == "よむ") {
          return spans[0].fixed && spans[1].fixed;
        }
      }
      return false;
    };
    Check(fixed_spans("neko yomu"), "A3-finish-japanese-typed-boundary-fixed");
    engines.asked.clear();
    const Snapshot s = coordinator.Convert("nekoyomu", 1);
    bool one_free = false;
    for (const auto& spans : engines.asked) {
      one_free |= spans.size() == 1 && spans[0].reading == "ねこよむ" && !spans[0].fixed;
    }
    Check(one_free && Place(s, "猫読む") >= 0, "A3-finish-japanese-no-boundary-free", Shown(s));
  }
  // Every candidate of every snapshot above encodes and decodes unchanged.
  {
    const Snapshot s = coordinator.Convert("tushuguan e iku", 5);
    const auto back = Decode(Encode(s));
    Check(back && *back == s, "S6-coordinator-snapshot-roundtrip");
  }
  // Echoed unknown kana (Mozc cost <= 1) pays Costs::kana_echo per echoed kana so a real
  // English word or cross-language loanword beats an echoed non-word (`book` vs `ぼおく`), and
  // capitalized English (`Book`) keeps its typed casing without losing Japanese `本`/`ブック`.
  {
    class EchoingEngines : public test::FakeEngines {
     public:
      std::optional<std::vector<JapaneseSegment>> Japanese(
          const std::vector<JapaneseSpan>& spans, bool incognito) override {
        if (spans.size() == 1 && spans[0].surface.empty() && spans[0].reading == "のか") {
          return std::vector<JapaneseSegment>{{.reading = "のか", .pinned = false, .words = {{"のか", "のか", 0}}}};
        }
        return test::FakeEngines::Japanese(spans, incognito);
      }
      std::vector<EnglishForm> English(std::string_view word) override {
        if (word == "noka") return {{"noka", 120}};
        return test::FakeEngines::English(word);
      }
    } echo_engines;
    Coordinator echo_coord(echo_engines, *lexicon);
    const Snapshot echo = echo_coord.Convert("noka", 1);
    Check(Place(echo, "noka") == 0 && Place(echo, "のか") > 0 &&
              Get(echo, "のか").cost >= Options().costs.kana_echo * 2,
          "Q7-finish-japanese-kana-echo-penalized", Shown(echo));
    const Snapshot cased = coordinator.Convert("Book", 1);
    Check(Place(cased, "Book") == 0 && Place(cased, "本") > 0 && Place(cased, "ブック") > 0,
          "Q7-coordinator-cased-english-keeps-cross", Shown(cased));
  }
  // Mixed-mode Pinyin prefix matching: 2–3 syllable non-composed phrases and 1st-syllable
  // characters strictly shorter than the composition appear with kSentence unset, never cutting
  // inside a syllable, and prefix learning keys use only the consumed prefix.
  {
    class PrefixEngines : public test::FakeEngines {
     public:
      void Spellings(std::string_view prefix, std::vector<Spelling>* out) override {
        test::FakeEngines::Spellings(prefix, out);
        static const std::pair<std::string_view, bool> extra[] = {
            {"yuan", false}, {"zhe", false}, {"shi", false}, {"jian", false}, {"ji", false},
        };
        for (const auto& [s, p] : extra) {
          if (prefix.starts_with(s)) out->push_back({static_cast<uint8_t>(s.size()), p, false});
        }
      }
      std::vector<ChineseWord> Chinese(std::string_view syllables, size_t limit) override {
        if (syllables == "yuan'zhe") return {{"元这", true}};
        if (syllables == "yuan") return {{"元", false}, {"袁", false}};
        if (syllables == "ni'hao'shi'jian") return {{"你好时间", true}};
        if (syllables == "ni'hao'shi") return {{"你好市", true}};
        if (syllables == "ni'hao") return {{"你好", false}};
        if (syllables == "shi'jian") return {{"时间", false}};
        if (syllables == "shi'ji") return {{"实际", false}};
        if (syllables == "shi") return {{"是", false}};
        return test::FakeEngines::Chinese(syllables, limit);
      }
    } prefix_engines;
    Learned learned;
    Coordinator pref_coord(prefix_engines, *lexicon, Options{}, &learned);

    const Snapshot yz = pref_coord.Convert("yuanzhe", 1);
    const int whole_yz = Place(yz, "元这");
    const int p_yuan = Place(yz, "元");
    Check(whole_yz == 0 && (yz.candidates[whole_yz].flags & kSentence) && p_yuan > 0 &&
              !(yz.candidates[p_yuan].flags & kSentence) &&
              yz.candidates[p_yuan].pieces.size() == 1 && yz.candidates[p_yuan].pieces[0].begin == 0 &&
              yz.candidates[p_yuan].pieces[0].end == 4 && Place(yz, "袁") > p_yuan,
          "Q7-prefix-first-syllable-chars", Shown(yz));

    const Snapshot nhsj = pref_coord.Convert("nihaoshijian", 1);
    Check(Place(nhsj, "你好时间") == 0 && Place(nhsj, "你好") > 0 && Place(nhsj, "你") > 0 &&
              Place(nhsj, "你好市") < 0,
          "Q7-prefix-noncomposed-phrase-skips-composed", Shown(nhsj));

    const Snapshot sj = pref_coord.Convert("shijian", 1);
    Check(Place(sj, "时间") == 0 && Place(sj, "实际") < 0 && Place(sj, "是") > 0,
          "Q7-prefix-never-cuts-inside-syllable", Shown(sj));

    learned["ni\t你"] = -2000;
    const Snapshot boosted = pref_coord.Convert("nihaoshijian", 2);
    Check(Place(boosted, "你") >= 0 && Place(boosted, "你") < Place(nhsj, "你") &&
              (boosted.candidates[Place(boosted, "你")].flags & kLearned),
          "Q7-prefix-learning-slices-consumed-reading", Shown(boosted));
  }
  // Contract v10: candidates produced identically by both Chinese and Japanese carry kSharedZhJa,
  // while distinct candidates (e.g. 书 vs 書, 你好) do not.
  {
    class SharedEngines : public test::FakeEngines {
     public:
      void Spellings(std::string_view prefix, std::vector<Spelling>* out) override {
        test::FakeEngines::Spellings(prefix, out);
        for (const std::string_view s : {"ri", "ben"}) {
          if (prefix.starts_with(s)) out->push_back({static_cast<uint8_t>(s.size()), false, false});
        }
      }
      std::vector<ChineseWord> Chinese(std::string_view syllables, size_t limit) override {
        if (syllables == "ri'ben") return {{"日本", false}};
        return test::FakeEngines::Chinese(syllables, limit);
      }
    } shared_engines;
    std::string error;
    const auto shared_lex = CrossLexicon::FromBytes(*CompileLexicon(
        "key\tsyllables\tsurface\treading\tlid\trid\tcost\tsource\n"
        "riben\tri4 ben3\t日本\tにほん\t1\t1\t3000\ttest\n",
        "key\tclass\tsurface\treading\tlid\trid\tcost\tsource\n",
        &error));
    Coordinator shared_coord(shared_engines, *shared_lex);
    const Snapshot riben = shared_coord.Convert("riben", 1);
    const int at = Place(riben, "日本");
    int count = 0;
    for (const Candidate& c : riben.candidates) count += c.text == "日本" ? 1 : 0;
    Check(at == 0 && count == 1 && (riben.candidates[at].flags & kSharedZhJa),
          "V10-shared-zh-ja-merged-and-flagged", Shown(riben));
    const Snapshot nihao = shared_coord.Convert("nihao", 2);
    Check(Place(nihao, "你好") == 0 && !(Get(nihao, "你好").flags & kSharedZhJa),
          "V10-chinese-only-not-shared", Shown(nihao));
    const Snapshot shu = coordinator.Convert("shu", 3);
    Check(!(Get(shu, "书").flags & kSharedZhJa) && !(Get(shu, "書").flags & kSharedZhJa),
          "V10-distinct-zh-ja-not-shared", Shown(shu));
  }
  return kotomo::mixed::test::Result();
}
