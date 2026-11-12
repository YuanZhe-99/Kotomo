#include "request.h"

#include "snapshot.h"
#include "wire.h"

namespace kotomo::mixed {

std::string Encode(const Request& request) {
  std::string out = "KR";
  Writer w(&out);
  w.Int(kContractVersion);
  w.Int(request.generation);
  w.Int(static_cast<uint8_t>(request.command));
  switch (request.command) {
    case Command::kSet:
    case Command::kContext:
      w.Str(request.text);
      break;
    case Command::kKey:
      w.Int(request.code_point);
      w.Int(request.x);
      w.Int(request.y);
      break;
    case Command::kOptions:
      w.Int(request.level);
      w.Int(request.switches);
      w.Int(request.targets);
      for (const int8_t weight : request.weight) w.Int(weight);
      break;
    case Command::kSelect:
      w.Int(request.shown);
      w.Int(request.index);
      break;
    case Command::kBackspace:
    case Command::kClear:
      break;
  }
  return out;
}

std::optional<Request> DecodeRequest(std::string_view bytes) {
  if (bytes.substr(0, 2) != "KR") return std::nullopt;
  Reader r(bytes.substr(2));
  uint16_t version = 0;
  uint8_t command = 0;
  Request request;
  if (!r.Int(&version) || version != kContractVersion || !r.Int(&request.generation) ||
      !r.Int(&command) || command > static_cast<uint8_t>(Command::kSelect)) {
    return std::nullopt;
  }
  request.command = static_cast<Command>(command);
  bool ok = true;
  switch (request.command) {
    case Command::kSet:
    case Command::kContext:
      ok = r.Str(&request.text);
      break;
    case Command::kKey:
      ok = r.Int(&request.code_point) && r.Int(&request.x) && r.Int(&request.y);
      break;
    case Command::kOptions:
      ok = r.Int(&request.level) && request.level <= 2 && r.Int(&request.switches) &&
           (request.switches & ~kSwitchAll) == 0 && r.Int(&request.targets) &&
           (request.targets & ~kTargetAll) == 0;
      for (int8_t& weight : request.weight) {
        ok = ok && r.Int(&weight) && weight >= -100 && weight <= 100;
      }
      break;
    case Command::kSelect:
      ok = r.Int(&request.shown) && r.Int(&request.index);
      break;
    case Command::kBackspace:
    case Command::kClear:
      break;
  }
  if (!ok || !r.Done()) return std::nullopt;
  return request;
}

}  // namespace kotomo::mixed
