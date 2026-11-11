// The host tests' shared check helper: prints one PASS/FAIL line per named check, as the other
// engines' host tests do, and counts failures for the exit status.

#ifndef KOTOMO_CORE_MIXED_TESTS_CHECK_H_
#define KOTOMO_CORE_MIXED_TESTS_CHECK_H_

#include <cstdio>
#include <string>

namespace kotomo::mixed::test {

inline int failures = 0;

/// Records one named check and prints its result.
/// Inputs: `id` names the spike or test ID the check covers (`S6-snapshot-roundtrip`).
inline void Check(bool ok, const char* id, const std::string& detail = "") {
  std::printf("%s %s%s%s\n", ok ? "PASS" : "FAIL", id, detail.empty() ? "" : ": ", detail.c_str());
  if (!ok) ++failures;
}

/// Returns: the process exit status for main().
inline int Result() {
  std::printf("%d failed\n", failures);
  return failures == 0 ? 0 : 1;
}

}  // namespace kotomo::mixed::test

#endif  // KOTOMO_CORE_MIXED_TESTS_CHECK_H_
