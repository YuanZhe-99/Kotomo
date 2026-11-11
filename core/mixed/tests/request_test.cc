// Host test of the request encoding (the engine ↔ app contract's input, version 2,
// doc/en-us/engine-contract.md): round trip of every command and refusal of damaged buffers.

#include <vector>

#include "check.h"
#include "request.h"
#include "snapshot.h"

using namespace kotomo::mixed;
using kotomo::mixed::test::Check;

int main() {
  std::vector<Request> samples(7);
  samples[0] = {.generation = 1, .command = Command::kSet, .text = "ni hao"};
  samples[1] = {.generation = 2, .command = Command::kKey, .code_point = 'n', .x = 650, .y = 375};
  samples[2] = {.generation = 3, .command = Command::kBackspace};
  samples[3] = {.generation = 4, .command = Command::kClear};
  samples[4] = {.generation = 5, .command = Command::kOptions, .level = 2,
                .switches = kSwitchEnglishToKanji | kSwitchIncognito, .weight = {-100, 0, 100}};
  samples[5] = {.generation = 6, .command = Command::kContext, .text = "前文"};
  samples[6] = {.generation = 1ull << 40, .command = Command::kSelect, .shown = 9, .index = 3};
  bool round_trip = true, truncated_refused = true, trailing_refused = true;
  for (const Request& sample : samples) {
    const std::string bytes = Encode(sample);
    const auto back = DecodeRequest(bytes);
    round_trip = round_trip && back && *back == sample;
    for (size_t n = 0; n < bytes.size(); ++n) {
      if (DecodeRequest(std::string_view(bytes).substr(0, n))) truncated_refused = false;
    }
    if (DecodeRequest(bytes + "x")) trailing_refused = false;
  }
  Check(round_trip, "Q7-request-roundtrip");
  // Negative: every truncation and a trailing byte are refused, never half-read.
  Check(truncated_refused, "Q7-request-truncated-refused");
  Check(trailing_refused, "Q7-request-trailing-refused");

  // Negative: another version, another magic, an unknown command, level, switch bit or weight.
  const std::string options = Encode(samples[4]);
  const size_t body = 2 + 2 + 8 + 1;  // magic, version, generation, command
  const auto damaged = [&options](size_t at, char value) {
    std::string bytes = options;
    bytes[at] = value;
    return bytes;
  };
  Check(!DecodeRequest(damaged(2, static_cast<char>(kContractVersion + 1))),
        "Q7-request-version-refused");
  Check(!DecodeRequest(damaged(0, 'X')), "Q7-request-magic-refused");
  Check(!DecodeRequest(damaged(body - 1, 7)), "Q7-request-unknown-command-refused");
  Check(!DecodeRequest(damaged(body, 3)), "Q7-request-level-refused");
  Check(!DecodeRequest(damaged(body + 1, 32)), "Q7-request-switch-bit-refused");
  Check(!DecodeRequest(damaged(body + 2, 101)) && !DecodeRequest(damaged(body + 4, -101)),
        "Q7-request-weight-refused");
  Check(DecodeRequest(damaged(body + 2, 100)).has_value(), "Q7-request-weight-limit-accepted");
  return kotomo::mixed::test::Result();
}
