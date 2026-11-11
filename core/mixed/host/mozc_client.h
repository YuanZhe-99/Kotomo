// The host's way to Mozc: a client of the line service scripts/build-mozc.sh builds from
// core/mozc/kotomo_mozc_service.cc. Mozc builds only inside its own Bazel workspace, so a CMake
// host program cannot link it; on the device the coordinator calls the glue's C functions
// directly (doc/en-us/engine-contract.md).

#ifndef KOTOMO_CORE_MIXED_HOST_MOZC_CLIENT_H_
#define KOTOMO_CORE_MIXED_HOST_MOZC_CLIENT_H_

#include <sys/types.h>

#include <cstdio>
#include <memory>
#include <optional>
#include <string>
#include <vector>

#include "engines.h"

namespace kotomo::mixed::host {

/// Owns one running kotomo_mozc_service process and its two pipes.
class MozcClient {
 public:
  /// Starts the service.
  /// Inputs: the service binary, Mozc's data file and a writable profile directory.
  /// Returns: nullptr when the process cannot be started or does not answer a first request.
  /// Side effects: forks and execs the service; it lives until the client is destroyed.
  static std::unique_ptr<MozcClient> Start(const std::string& service, const std::string& data,
                                           const std::string& profile_dir);

  /// Side effects: sends `quit`, closes the pipes and waits for the process.
  ~MozcClient();
  MozcClient(const MozcClient&) = delete;
  MozcClient& operator=(const MozcClient&) = delete;

  /// Returns: the hiragana for `romaji`, letters the table cannot map left as ASCII; empty when
  ///   the service fails.
  std::string Hiragana(const std::string& romaji);

  /// Returns: Mozc's segments for the spans, or nullopt when it converts nothing or the service
  ///   fails. Inputs: readings and surfaces without tabs, colons or newlines.
  std::optional<std::vector<JapaneseSegment>> Convert(const std::vector<JapaneseSpan>& spans);

 private:
  MozcClient() = default;
  bool Send(const std::string& line);
  bool Receive(std::string* line);

  pid_t pid_ = -1;
  int to_service_ = -1;
  FILE* from_service_ = nullptr;
};

}  // namespace kotomo::mixed::host

#endif  // KOTOMO_CORE_MIXED_HOST_MOZC_CLIENT_H_
