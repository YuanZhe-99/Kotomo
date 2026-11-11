#include "propose.h"

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

bool Vowel(char c) { return c == 'a' || c == 'i' || c == 'u' || c == 'e' || c == 'o'; }

}  // namespace

void ProposeJapanese(const Input& input, Engines& engines, const Options& options,
                     Lattice* lattice) {
  const Costs& costs = options.costs;
  const std::string_view letters = input.letters;
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
