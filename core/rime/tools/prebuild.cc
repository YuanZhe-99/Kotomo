// Prebuilds librime's data for the app: deploys the staged shared data on the build host and
// writes the result as `<shared>/build`, which librime reads as its prebuilt data directory, so the
// phone opens it without deploying (PLAN §4.2).
//
// Usage: kotomo_rime_prebuild <shared data dir> <scratch user dir>
// The scratch directory is emptied and used for the deploy; its build/ then moves to
// <shared data dir>/build (an older one there is replaced). Exit status 0 on success.
//
// Notes: the compiled files are memory-mapped structures; the build host and the phone must
// share byte order and integer widths, which every 64-bit little-endian target (arm64-v8a,
// x86_64, Linux hosts) does.

#include <cstdio>
#include <filesystem>
#include <string>

#include "rime_bridge.h"

namespace rb = kotomo::rime_bridge;
namespace fs = std::filesystem;

int main(int argc, char** argv) {
  if (argc != 3) {
    std::fprintf(stderr, "usage: %s <shared data dir> <scratch user dir>\n", argv[0]);
    return 2;
  }
  const fs::path shared = argv[1];
  const fs::path scratch = argv[2];
  if (!fs::exists(shared / "default.yaml")) {
    std::fprintf(stderr, "prebuild: %s has no default.yaml; stage the data first\n", argv[1]);
    return 1;
  }
  // A previous prebuild in the shared directory would be read as prebuilt data and skip the
  // compile, so it goes first.
  fs::remove_all(shared / "build");
  fs::remove_all(scratch);
  fs::create_directories(scratch / "build");

  rb::Initialize(shared.string(), scratch.string());
  const bool deployed = rb::Deploy();
  const std::string failed = rb::DeployFailures();
  rb::Shutdown();
  if (!fs::exists(scratch / "build" / "luna_pinyin_simp.schema.yaml")) {
    std::fprintf(stderr, "prebuild: no compiled luna_pinyin_simp (failed tasks: %s)\n", failed.c_str());
    return 1;
  }
  if (!deployed) std::fprintf(stderr, "prebuild: warning: failed tasks: %s\n", failed.c_str());
  // Copy rather than rename: the scratch directory may sit on another file system (WSL's home
  // against the Windows checkout).
  fs::copy(scratch / "build", shared / "build", fs::copy_options::recursive);
  fs::remove_all(scratch);
  std::printf("prebuild: %s/build ready\n", argv[1]);
  return 0;
}
