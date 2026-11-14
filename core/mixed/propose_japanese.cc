#include "propose.h"

#include <algorithm>

#include "fuzzy_kana.h"

namespace kotomo::mixed {
namespace {

/// The longest romaji spelling of one kana edge (`ssha`, `xtsu`).
constexpr size_t kMaxRomaji = 4;

/// Holds a particle typed as it sounds (PLAN.md §5.4).
struct Particle {
  std::string_view romaji;
  const char* kana;
};
constexpr Particle kParticles[] = {{"wa", "は"}, {"o", "を"}, {"e", "へ"}};

/// Returns: true when `text` is not empty and holds no ASCII, i.e. the romaji mapped completely.
bool AllKana(std::string_view text) {
  if (text.empty()) return false;
  for (const char c : text) {
    if (static_cast<unsigned char>(c) < 0x80) return false;
  }
  return true;
}

/// Returns: true when `romaji` is two shorter kana spellings in a row (`kai` = ka + i), so the
///   lattice already holds it as two edges and one more would only duplicate the path.
bool Splits(std::string_view romaji, std::string_view kana, Engines& engines) {
  for (size_t cut = 1; cut < romaji.size(); ++cut) {
    const std::string head = engines.Hiragana(romaji.substr(0, cut));
    if (!AllKana(head)) continue;
    const std::string tail = engines.Hiragana(romaji.substr(cut));
    if (AllKana(tail) && head + tail == kana) return true;
  }
  return false;
}

/// The longest run of letters that begins a kana without finishing one (`ssh`, `xts`).
constexpr size_t kMaxPending = 3;

bool Vowel(char c) { return c == 'a' || c == 'i' || c == 'u' || c == 'e' || c == 'o'; }

/// Returns: true when `letters` are consonants that are no kana yet and become one kana
///   spelling with a vowel more (`ik` is not: its i is a kana already).
bool BeginsKana(std::string_view letters, Engines& engines) {
  for (const char c : letters) {
    if (Vowel(c)) return false;
  }
  if (AllKana(engines.Hiragana(letters))) return false;
  for (const char vowel : {'a', 'u', 'o', 'i', 'e'}) {
    std::string longer(letters);
    longer.push_back(vowel);
    if (AllKana(engines.Hiragana(longer))) return true;
  }
  return false;
}

/// The most letters and kana of one word a fuzzy rule is tried on, and the most readings of it
/// looked up per rule: longer words are rare, and each reading is one dictionary question.
constexpr size_t kMaxFuzzyLetters = 16;
constexpr size_t kMaxFuzzyKana = 6;
constexpr size_t kMaxFuzzyReadings = 48;

/// Returns: how many kana edges a reading takes when typed: its kana without the small ones
///   and っ, which share an edge with their neighbour; at least 1.
uint8_t KanaUnits(std::string_view reading) {
  size_t units = 0;
  for (size_t i = 0; i + 3 <= reading.size(); i += 3) {
    const std::string_view kana = reading.substr(i, 3);
    const bool joined = kana == "ゃ" || kana == "ゅ" || kana == "ょ" || kana == "っ" || kana == "ぁ" ||
                        kana == "ぃ" || kana == "ぅ" || kana == "ぇ" || kana == "ぉ";
    if (!joined) ++units;
  }
  return static_cast<uint8_t>(std::max<size_t>(units, 1));
}

/// Adds the fuzzy readings of the spans that begin at `begin` (feedback B4): a kana edge for a
/// romaji spelling a romaji rule respells (`cu` → つ), and a word edge for every reading a kana
/// rule makes of a span that Mozc's dictionary has (`tokyo` → とうきょう).
/// Notes: one rule per reading, so rules that share a kana never chain; a romaji rule's
///   respelling may be what a kana rule starts from (`lamen` → らめん → らーめん). Each edge
///   costs what the span costs as typed plus Costs::japanese_fuzzy; Mozc's cost of the sentence
///   replaces the estimate, so a fuzzy word ranks by how common it is.
void ProposeFuzzy(const Input& input, size_t begin, Engines& engines, const Options& options,
                  Lattice* lattice) {
  const Costs& costs = options.costs;
  const std::string_view letters = input.letters;
  const uint16_t rules = options.japanese_fuzzy;
  for (size_t length = 1; length <= kMaxFuzzyLetters && begin + length <= input.size(); ++length) {
    const size_t end = begin + length;
    const std::string_view romaji = letters.substr(begin, length);
    // As for the exact edges: a lone n before a vowel or y starts the next kana.
    const bool open_n = romaji.back() == 'n' && (length < 2 || romaji[length - 2] != 'n') &&
                        end < input.size() && (Vowel(letters[end]) || letters[end] == 'y');
    if (open_n) continue;
    std::vector<std::string> typed;  // the span's readings as typed, respelled ones included
    if (std::string kana = engines.Hiragana(romaji); AllKana(kana)) typed.push_back(std::move(kana));
    for (const uint16_t rule : {kFuzzyLAsR, kFuzzyCu}) {
      if (!(rules & rule)) continue;
      const std::string respelled = FuzzyRomaji(romaji, rule);
      if (respelled.empty()) continue;
      std::string kana = engines.Hiragana(respelled);
      if (!AllKana(kana)) continue;
      if (length <= kMaxRomaji && !Splits(respelled, kana, engines)) {
        lattice->Add({.begin = static_cast<uint16_t>(begin),
                      .end = static_cast<uint16_t>(end),
                      .kind = EdgeKind::kKana,
                      .cost = costs.kana + costs.japanese_fuzzy +
                              costs.crossed_hint * input.HintsInside(begin, end),
                      .fuzzy = true,
                      .text = std::string(romaji),
                      .reading = kana});
      }
      typed.push_back(std::move(kana));
    }
    if (!(rules & kFuzzyKanaRules) || length < 2 || input.HintsInside(begin, end) > 0) continue;
    std::vector<std::string> seen = typed;
    for (const std::string& reading : typed) {
      if (reading.size() < 2 * 3 || reading.size() > kMaxFuzzyKana * 3) continue;
      for (uint16_t rule = 1; rule & kFuzzyKanaRules; rule <<= 1) {
        if (!(rules & rule)) continue;
        for (std::string& variant : FuzzyReadings(reading, rule, kMaxFuzzyReadings)) {
          if (std::find(seen.begin(), seen.end(), variant) != seen.end()) continue;
          seen.push_back(variant);
          if (engines.Known(variant) < 0) continue;
          const uint8_t units = KanaUnits(reading);
          lattice->Add({.begin = static_cast<uint16_t>(begin),
                        .end = static_cast<uint16_t>(end),
                        .kind = EdgeKind::kKana,
                        .cost = costs.kana * units + costs.japanese_fuzzy,
                        .fuzzy = true,
                        .units = units,
                        .text = std::string(romaji),
                        .reading = std::move(variant)});
        }
      }
    }
  }
}

}  // namespace

void ProposeJapanese(const Input& input, Engines& engines, const Options& options,
                     Lattice* lattice) {
  const Costs& costs = options.costs;
  const std::string_view letters = input.letters;
  // What is still being typed stays as typed after the sentence before it.
  for (size_t length = 1; length <= kMaxPending && length < input.size(); ++length) {
    const size_t begin = input.size() - length;
    if (input.HintsInside(begin, input.size()) > 0 || !BeginsKana(letters.substr(begin), engines)) continue;
    lattice->Add({.begin = static_cast<uint16_t>(begin),
                  .end = static_cast<uint16_t>(input.size()),
                  .kind = EdgeKind::kPending,
                  .cost = costs.pending,
                  .text = std::string(letters.substr(begin)),
                  .reading = {}});
  }
  for (size_t begin = 0; begin < input.size(); ++begin) {
    for (size_t length = 1; length <= kMaxRomaji && begin + length <= input.size(); ++length) {
      const size_t end = begin + length;
      const std::string_view romaji = letters.substr(begin, length);
      // A lone n before a vowel or y is the start of な or にゃ, not ん.
      if (romaji == "n" && end < input.size() && (Vowel(letters[end]) || letters[end] == 'y')) {
        continue;
      }
      std::string kana = engines.Hiragana(romaji);
      if (!AllKana(kana) || Splits(romaji, kana, engines)) continue;
      lattice->Add({.begin = static_cast<uint16_t>(begin),
                    .end = static_cast<uint16_t>(end),
                    .kind = EdgeKind::kKana,
                    .cost = costs.kana + (romaji == "nn" ? costs.doubled_n : 0) +
                            costs.crossed_hint * input.HintsInside(begin, end),
                    .text = std::string(romaji),
                    .reading = std::move(kana)});
    }
    if (options.japanese_fuzzy != 0) {
      ProposeFuzzy(input, begin, engines, options, lattice);
    }
    if (begin == 0) continue;  // a particle follows something
    for (const Particle& particle : kParticles) {
      if (letters.substr(begin, particle.romaji.size()) != particle.romaji) continue;
      const size_t end = begin + particle.romaji.size();
      lattice->Add({.begin = static_cast<uint16_t>(begin),
                    .end = static_cast<uint16_t>(end),
                    .kind = EdgeKind::kParticle,
                    .cost = costs.particle + costs.crossed_hint * input.HintsInside(begin, end),
                    .text = particle.kana,
                    .reading = particle.kana});
    }
  }
}

}  // namespace kotomo::mixed
