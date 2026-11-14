#include "coordinator.h"

#include <algorithm>
#include <unordered_set>

#include "decode.h"
#include "finish.h"
#include "input.h"
#include "lattice.h"
#include "propose.h"
#include "request.h"

namespace kotomo::mixed {

namespace {

/// Applies "original language first" (AGENTS.md, behavior contract): a cross-language candidate
/// never stands before every candidate written in the language its cross-language pieces were
/// typed in. The best such candidate moves just in front of it, wherever in the list that is:
/// `change` read as a Japanese word does not outrank the English word itself.
/// Notes: a cross-language candidate with no reading in the typed language stays where it is:
///   there is no original-language candidate to prefer. So does one the person's history moved
///   up (kLearned). The level decides how far cross-language goes; this runs below aggressive.
void OriginalLanguageFirst(std::vector<Candidate>* candidates) {
  // One pass per typed language: its best own candidate moves in front of the first
  // cross-language candidate typed in it.
  for (int lang = 0; lang < 3; ++lang) {
    const auto crossed = std::find_if(candidates->begin(), candidates->end(), [lang](const Candidate& c) {
      if (!(c.flags & kCross) || (c.flags & kLearned)) return false;
      return std::any_of(c.pieces.begin(), c.pieces.end(), [lang](const Piece& piece) {
        return (piece.origin == Origin::kPinyinJa || piece.origin == Origin::kEnJa) &&
               static_cast<int>(piece.typed) == lang;
      });
    });
    if (crossed == candidates->end()) continue;
    const auto original = std::find_if(crossed, candidates->end(), [lang](const Candidate& c) {
      return !(c.flags & kCross) && static_cast<int>(c.target) == lang;
    });
    const bool before = std::any_of(candidates->begin(), crossed, [lang](const Candidate& c) {
      return !(c.flags & kCross) && static_cast<int>(c.target) == lang;
    });
    if (!before && original != candidates->end()) std::rotate(crossed, original, original + 1);
  }
}

}  // namespace

Snapshot Coordinator::Convert(std::string_view composition, uint64_t generation,
                              std::span<const Piece> locks) {
  Snapshot snapshot{.generation = generation, .stage = 0, .input = {}, .cursor = 0,
                    .candidates = {}};
  const auto input = ParseInput(composition);
  if (!input) {
    // Still the app's composition, cut to what the snapshot can address.
    snapshot.input = std::string(composition.substr(0, kMaxInput));
    snapshot.cursor = static_cast<uint16_t>(snapshot.input.size());
    return snapshot;
  }
  snapshot.input = input->raw;
  snapshot.cursor = static_cast<uint16_t>(input->raw.size());

  Lattice lattice(input->size());
  for (const Piece& lock : locks) {
    // Byte offsets back to letter positions; the proposers then add nothing over the span.
    const auto begin = std::find(input->offset.begin(), input->offset.end() - 1, lock.begin);
    const auto last = std::find(input->offset.begin(), input->offset.end() - 1, lock.end - 1);
    if (lock.begin >= lock.end || begin == input->offset.end() - 1 || last == input->offset.end() - 1) continue;
    lattice.Lock(begin - input->offset.begin(), last - input->offset.begin() + 1, lock);
  }
  ProposePinyin(*input, engines_, options_, &lattice);
  ProposeJapanese(*input, engines_, options_, &lattice);
  ProposeEnglish(*input, engines_, options_, &lattice);
  ProposeCross(*input, lexicon_, options_, &lattice);

  std::vector<Candidate> found;
  bool cut = false;  // whether a fuller conversion (Options::Full) would list more
  const auto wanted = [this](Lang lang) { return options_.targets[static_cast<int>(lang)]; };
  if (wanted(Lang::kChinese)) {
    for (const Path& path : Decode(lattice, Lang::kChinese, options_)) {
      FinishChinese(*input, path, engines_, options_, &found, &cut);
    }
  }
  if (wanted(Lang::kJapanese)) {
    for (const Path& path : Decode(lattice, Lang::kJapanese, options_)) {
      FinishJapanese(*input, path, engines_, options_, &found);
    }
  }
  if (wanted(Lang::kEnglish)) {
    for (const Path& path : Decode(lattice, Lang::kEnglish, options_)) {
      FinishEnglish(*input, path, &found);
    }
  }

  for (Candidate& candidate : found) candidate.cost += options_.bias[static_cast<int>(candidate.target)];
  if (learned_ != nullptr && !learned_->empty()) {
    // The whole input's reading, and each piece's when there are several.
    const auto adjust = [this](std::string_view typed, std::string_view text) -> int32_t {
      std::string key;
      for (const char c : typed) {
        if (c >= 'A' && c <= 'Z') key.push_back(static_cast<char>(c - 'A' + 'a'));
        else if (c >= 'a' && c <= 'z') key.push_back(c);
      }
      key.push_back('\t');
      key.append(text);
      const auto it = learned_->find(key);
      return it == learned_->end() ? 0 : it->second;
    };
    std::erase_if(found, [&](Candidate& candidate) {
      int32_t total = adjust(input->raw, candidate.text);
      bool blocked = total == kBlocked;
      if (candidate.pieces.size() > 1) {
        for (const Piece& piece : candidate.pieces) {
          if (piece.locked) continue;  // the person chose it here; history has no say
          const int32_t one = adjust(std::string_view(input->raw).substr(piece.begin, piece.end - piece.begin), piece.text);
          blocked = blocked || one == kBlocked;
          total += one;
        }
      }
      if (blocked) return true;
      candidate.cost += total;
      if (total < 0) candidate.flags |= kLearned;
      return false;
    });
  }
  std::stable_sort(found.begin(), found.end(),
                   [](const Candidate& a, const Candidate& b) { return a.cost < b.cost; });
  std::unordered_set<std::string> seen;
  for (Candidate& candidate : found) {
    if (!seen.insert(candidate.text).second) continue;
    if (snapshot.candidates.size() == options_.candidates) {
      cut = true;
      break;
    }
    snapshot.candidates.push_back(std::move(candidate));
  }
  if (options_.level != Level::kAggressive) OriginalLanguageFirst(&snapshot.candidates);
  snapshot.more = cut;
  return snapshot;
}

}  // namespace kotomo::mixed
