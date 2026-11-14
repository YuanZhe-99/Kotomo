#include "fuzzy_kana.h"

#include <algorithm>
#include <utility>

namespace kotomo::mixed {
namespace {

using Kana = std::u32string;
using namespace std::literals;

/// The kana of each vowel row, small ones included; ん, っ and ー are in none.
constexpr std::u32string_view kRowA = U"あかさたなはまやらわがざだばぱゃぁ";
constexpr std::u32string_view kRowI = U"いきしちにひみりぎじぢびぴぃ";
constexpr std::u32string_view kRowU = U"うくすつぬふむゆるぐずづぶぷゅぅゔ";
constexpr std::u32string_view kRowE = U"えけせてねへめれげぜでべぺぇ";
constexpr std::u32string_view kRowO = U"おこそとのほもよろをごぞどぼぽょぉ";

/// Returns: the vowel a kana ends in (a, i, u, e, o), or 0 for ん, っ, ー and anything else.
char Vowel(char32_t c) {
  if (kRowA.find(c) != kRowA.npos) return 'a';
  if (kRowI.find(c) != kRowI.npos) return 'i';
  if (kRowU.find(c) != kRowU.npos) return 'u';
  if (kRowE.find(c) != kRowE.npos) return 'e';
  if (kRowO.find(c) != kRowO.npos) return 'o';
  return 0;
}

/// Decodes UTF-8 hiragana.
/// Returns: false for anything outside U+3041…U+3096 and ー (three bytes each).
bool Decode(std::string_view text, Kana* out) {
  if (text.empty() || text.size() % 3 != 0) return false;
  for (size_t i = 0; i < text.size(); i += 3) {
    const auto b = [&](size_t k) { return static_cast<unsigned char>(text[i + k]); };
    if ((b(0) & 0xf0) != 0xe0 || (b(1) & 0xc0) != 0x80 || (b(2) & 0xc0) != 0x80) return false;
    const char32_t c = ((b(0) & 0x0f) << 12) | ((b(1) & 0x3f) << 6) | (b(2) & 0x3f);
    if (!((c >= 0x3041 && c <= 0x3096) || c == U'ー')) return false;
    out->push_back(c);
  }
  return true;
}

/// Returns: the kana as UTF-8.
std::string Encode(const Kana& kana) {
  std::string out;
  for (const char32_t c : kana) {
    out.push_back(static_cast<char>(0xe0 | (c >> 12)));
    out.push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3f)));
    out.push_back(static_cast<char>(0x80 | (c & 0x3f)));
  }
  return out;
}

/// Holds one place a rule applies to: `length` kana at `at` may become any of `options`
/// (length 0 inserts; an empty option deletes).
struct Site {
  size_t at;
  size_t length;
  std::vector<Kana> options;
};

/// Adds sites that swap single kana: `from[i]` may become `to[i]`.
void Swap(const Kana& r, std::u32string_view from, std::u32string_view to, std::vector<Site>* sites) {
  for (size_t i = 0; i < r.size(); ++i) {
    const size_t found = from.find(r[i]);
    if (found == from.npos) continue;
    const auto same = std::find_if(sites->begin(), sites->end(), [i](const Site& s) { return s.at == i && s.length == 1; });
    if (same != sites->end()) {
      same->options.push_back(Kana(1, to[found]));
    } else {
      sites->push_back({i, 1, {Kana(1, to[found])}});
    }
  }
}

/// Adds sites that swap strings of kana: each pair's first may become its second.
void Replace(const Kana& r, const std::vector<std::pair<std::u32string_view, std::u32string_view>>& pairs,
             std::vector<Site>* sites) {
  for (size_t i = 0; i < r.size(); ++i) {
    for (const auto& [from, to] : pairs) {
      if (r.compare(i, from.size(), from) != 0) continue;
      // The first kana of a longer spelling is not the shorter one (ふぁ is not ふ + ぁ → は).
      if (from.size() == 1 && i + 1 < r.size() && U"ぁぃぅぇぉ"sv.find(r[i + 1]) != std::u32string_view::npos) continue;
      sites->push_back({i, from.size(), {Kana(to)}});
    }
  }
}

/// Returns: the places of `r` one rule applies to, with what each may become.
std::vector<Site> Sites(const Kana& r, uint16_t rule) {
  std::vector<Site> sites;
  switch (rule) {
    case kFuzzyLongVowel:
      for (size_t i = 0; i < r.size(); ++i) {
        const char32_t c = r[i];
        const char before = i > 0 ? Vowel(r[i - 1]) : 0;
        // Typed too much: the mark of the long vowel goes.
        if (i > 0 && (c == U'ー' || (c == U'う' && (before == 'o' || before == 'u')) ||
                      (c == U'い' && (before == 'e' || before == 'i')) || (c == U'お' && before == 'o') ||
                      (c == U'あ' && before == 'a') || (c == U'え' && before == 'e'))) {
          sites.push_back({i, 1, {Kana()}});
        }
        // Left out: the vowel may have been long.
        const char vowel = Vowel(c);
        if (vowel == 0) continue;
        const char32_t next = i + 1 < r.size() ? r[i + 1] : 0;
        Site site{i + 1, 0, {}};
        const auto offer = [&](char32_t mark) {
          if (next != mark && next != U'ー') site.options.push_back(Kana(1, mark));
        };
        if (vowel == 'a') offer(U'あ');
        if (vowel == 'i') offer(U'い');
        if (vowel == 'u') offer(U'う');
        if (vowel == 'e') { offer(U'い'); offer(U'え'); }
        if (vowel == 'o') { offer(U'う'); offer(U'お'); }
        offer(U'ー');
        if (!site.options.empty()) sites.push_back(std::move(site));
      }
      break;
    case kFuzzySokuon:
      for (size_t i = 1; i < r.size(); ++i) {
        if (r[i] == U'っ') {
          sites.push_back({i, 1, {Kana()}});
        } else if (U"かきくけこさしすせそたちつてとぱぴぷぺぽ"sv.find(r[i]) != std::u32string_view::npos &&
                   r[i - 1] != U'っ' && r[i - 1] != U'ん') {
          sites.push_back({i, 0, {Kana(1, U'っ')}});
        }
      }
      break;
    case kFuzzyMoraicN: {
      constexpr std::u32string_view na = U"なにぬねの", vowels = U"あいうえお", ya = U"やゆよ", small = U"ゃゅょ";
      for (size_t i = 0; i < r.size(); ++i) {
        const size_t row = na.find(r[i]);
        const char32_t next = i + 1 < r.size() ? r[i + 1] : 0;
        if (row != na.npos && (i == 0 || r[i - 1] != U'ん')) {
          // ん left out before な行 (こにちは), or ん + vowel typed without the second n (きねん).
          if (i > 0) sites.push_back({i, 0, {Kana(1, U'ん')}});
          const size_t glide = next != 0 ? small.find(next) : small.npos;
          if (r[i] == U'に' && glide != small.npos) {
            sites.push_back({i, 2, {Kana{U'ん', ya[glide]}}});
          } else if (i > 0) {
            sites.push_back({i, 1, {Kana{U'ん', vowels[row]}}});
          }
        }
        if (r[i] != U'ん' || next == 0) continue;
        if (const size_t v = vowels.find(next); v != vowels.npos) sites.push_back({i, 2, {Kana(1, na[v])}});
        if (const size_t y = ya.find(next); y != ya.npos) sites.push_back({i, 2, {Kana{U'に', small[y]}}});
        if (na.find(next) != na.npos) sites.push_back({i, 1, {Kana()}});
      }
      break;
    }
    case kFuzzyVoiced: {
      constexpr std::u32string_view plain = U"かきくけこさしすせそたちつてとはひふへほ";
      constexpr std::u32string_view voiced = U"がぎぐげござじずぜぞだぢづでどばびぶべぼ";
      Swap(r, plain, voiced, &sites);
      Swap(r, voiced, plain, &sites);
      break;
    }
    case kFuzzySemiVoiced:
      Swap(r, U"はひふへほ", U"ぱぴぷぺぽ", &sites);
      Swap(r, U"ばびぶべぼ", U"ぱぴぷぺぽ", &sites);
      Swap(r, U"ぱぴぷぺぽ", U"はひふへほ", &sites);
      Swap(r, U"ぱぴぷぺぽ", U"ばびぶべぼ", &sites);
      break;
    case kFuzzyYoon: {
      constexpr std::u32string_view full = U"やゆよ", small = U"ゃゅょ";
      for (size_t i = 1; i < r.size(); ++i) {
        if (Vowel(r[i - 1]) != 'i' || r[i - 1] == U'い' || r[i - 1] == U'ぃ') continue;
        if (const size_t f = full.find(r[i]); f != full.npos) sites.push_back({i, 1, {Kana(1, small[f])}});
        if (const size_t s = small.find(r[i]); s != small.npos) sites.push_back({i, 1, {Kana(1, full[s])}});
      }
      break;
    }
    case kFuzzyYotsugana:
      Swap(r, U"じずぢづ", U"ぢづじず", &sites);
      break;
    case kFuzzyRaNaDa:
      Swap(r, U"らりるれろ", U"なにぬねの", &sites);
      Swap(r, U"なにぬねの", U"らりるれろ", &sites);
      Swap(r, U"られろ", U"だでど", &sites);
      Swap(r, U"だでど", U"られろ", &sites);
      break;
    case kFuzzyHiShi:
      Swap(r, U"ひし", U"しひ", &sites);
      break;
    case kFuzzyLoanword: {
      static const std::vector<std::pair<std::u32string_view, std::u32string_view>> kPairs = {
          {U"ゔぁ", U"ば"}, {U"ゔぃ", U"び"}, {U"ゔぇ", U"べ"}, {U"ゔぉ", U"ぼ"}, {U"ゔ", U"ぶ"},
          {U"ば", U"ゔぁ"}, {U"び", U"ゔぃ"}, {U"べ", U"ゔぇ"}, {U"ぼ", U"ゔぉ"}, {U"ぶ", U"ゔ"},
          {U"てぃ", U"ち"}, {U"ち", U"てぃ"}, {U"でぃ", U"じ"}, {U"じ", U"でぃ"},
          {U"ふぁ", U"は"}, {U"ふぃ", U"ひ"}, {U"ふぇ", U"へ"}, {U"ふぉ", U"ほ"},
          {U"は", U"ふぁ"}, {U"ひ", U"ふぃ"}, {U"へ", U"ふぇ"}, {U"ほ", U"ふぉ"}};
      Replace(r, kPairs, &sites);
      break;
    }
    default:
      break;
  }
  return sites;
}

/// Returns: `r` with one option of a site applied.
Kana Apply(const Kana& r, const Site& site, const Kana& option) {
  Kana out = r.substr(0, site.at);
  out += option;
  out += r.substr(site.at + site.length);
  return out;
}

}  // namespace

std::vector<std::string> FuzzyReadings(std::string_view reading, uint16_t rule, size_t limit) {
  std::vector<std::string> out;
  Kana r;
  if (!Decode(reading, &r)) return out;
  const std::vector<Site> sites = Sites(r, rule);
  const auto add = [&](const Kana& variant) {
    if (out.size() >= limit || variant == r || variant.empty()) return;
    std::string text = Encode(variant);
    if (std::find(out.begin(), out.end(), text) == out.end()) out.push_back(std::move(text));
  };
  for (const Site& site : sites) {
    for (const Kana& option : site.options) add(Apply(r, site, option));
  }
  // Two places at once (ときょ → とうきょう); the later one first, so the earlier offsets hold.
  for (size_t a = 0; a < sites.size() && out.size() < limit; ++a) {
    for (size_t b = a + 1; b < sites.size() && out.size() < limit; ++b) {
      const Site& first = sites[a].at <= sites[b].at ? sites[a] : sites[b];
      const Site& second = sites[a].at <= sites[b].at ? sites[b] : sites[a];
      if (first.at + std::max<size_t>(first.length, 1) > second.at) continue;  // they overlap
      for (const Kana& late : second.options) {
        const Kana half = Apply(r, second, late);
        for (const Kana& early : first.options) add(Apply(half, first, early));
      }
    }
  }
  return out;
}

std::string FuzzyRomaji(std::string_view romaji, uint16_t rule) {
  std::string out(romaji);
  if (rule == kFuzzyLAsR) {
    std::replace(out.begin(), out.end(), 'l', 'r');
  } else if (rule == kFuzzyCu) {
    // From the u back over its c's: `cu` → `tu`, `ccu` → `ttu`.
    for (size_t i = out.size(); i-- > 0;) {
      if (out[i] == 'c' && i + 1 < out.size() && (out[i + 1] == 'u' || (out[i + 1] == 't' && romaji[i + 1] == 'c'))) {
        out[i] = 't';
      }
    }
  }
  return out == romaji ? std::string() : out;
}

}  // namespace kotomo::mixed
