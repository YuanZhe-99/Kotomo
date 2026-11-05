// Opens librime on a shared and a user data directory, as the keyboard does, and prints the
// bridge's self-check: a host tool for reproducing what a phone reports on its diagnostics page.
//
// Usage: kotomo_rime_check <shared data dir> <user data dir>
// Exit status: 0 when a session opened, 1 otherwise.

#include <cstdio>

#include "rime_bridge.h"

namespace rb = kotomo::rime_bridge;

int main(int argc, char** argv) {
  if (argc != 3) {
    std::fprintf(stderr, "usage: %s <shared data dir> <user data dir>\n", argv[0]);
    return 2;
  }
  const bool open = rb::Initialize(argv[1], argv[2]);
  std::printf("open=%s | %s\n", open ? "true" : "false", rb::SelfCheck().c_str());
  rb::Shutdown();
  return open ? 0 : 1;
}
