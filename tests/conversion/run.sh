#!/usr/bin/env bash
# Runs the conversion corpus (PLAN.md §13.3) on the host: tests/conversion/corpus.tsv through the
# app's own engine facades and candidate assembly (ConversionCorpusTest), compared line by line
# with tests/conversion/baseline.tsv; also the host tests of fuzzy pinyin (FuzzyRimeTest, T08), the
# dictionary tables (DictionaryRimeTest, T17) and downloaded packs (DictionaryPacksTest, T17-packs;
# with KOTOMO_PACKS=1 it also converts the pinned dictionary sources in .deps/), which need the same
# host librime.
#
# Then the same corpus through the mixed-input coordinator's host prototype (core/mixed, spike S6):
# its unit tests, the Mixed lines and the single-language lines as a baseline, compared with
# tests/conversion/baseline-mixed.tsv. The app does not use the coordinator yet (PLAN.md §0.3, Q7),
# so the two runs are reported side by side.
#
# Usage: tests/conversion/run.sh [--update]
#   --update  accepts this run as the new baselines (copies build/conversion/results.tsv over
#             tests/conversion/baseline.tsv and mixed-results.tsv over baseline-mixed.tsv); review
#             the reports before committing them
# Needs: the outputs of scripts/prebuild-rime.sh, scripts/build-mozc.sh --abi host,
#   java tool/BuildEnglishDict.java, java lexicon/EmojiData.java and
#   java -Xmx4g lexicon/CrossLexicon.java; CMake, Ninja, a C++ compiler and the Android SDK
#   (ANDROID_HOME) for Gradle.
# Side effects: builds the host librime and English libraries and the coordinator into
#   build/host/; writes build/conversion/results.tsv, report.txt, mixed-results.tsv and
#   mixed-report.txt, and with --update the baselines.
# Exit status: 0 when no line regressed against either baseline and the host tests passed, non-zero
#   otherwise.
set -euo pipefail

update=0
case "${1:-}" in
  "") ;;
  --update) update=1 ;;
  *) echo "corpus: unknown argument $1" >&2; exit 2 ;;
esac

cd "$(dirname "${BASH_SOURCE[0]}")/../.."
die() { echo "corpus: $*" >&2; exit 1; }
need() { [[ -e "$1" ]] || die "missing $1: run $2"; }

need build/rime-prebuilt/build/default.yaml scripts/prebuild-rime.sh
need build/mozc/out/host/libkotomo_mozc.so "scripts/build-mozc.sh --abi host"
need build/mozc/out/host/mozc.data "scripts/build-mozc.sh --abi host"
need build/lexicon/english/main_en_us.dict "java tool/BuildEnglishDict.java"
need build/emoji/emoji.tsv "java lexicon/EmojiData.java"
need build/mozc/out/host/kotomo_mozc_service "scripts/build-mozc.sh --abi host"
need build/lexicon/pinyin-ja.tsv "java -Xmx4g lexicon/CrossLexicon.java"
need build/lexicon/en-ja.tsv "java -Xmx4g lexicon/CrossLexicon.java"

cmake -S core/rime -B build/host/rime -G Ninja -DCMAKE_BUILD_TYPE=Release >/dev/null 2>&1 || die "librime configure failed"
cmake --build build/host/rime --target kotomo_rime >/dev/null || die "librime build failed"
cmake -S core/english -B build/host/english -G Ninja -DCMAKE_BUILD_TYPE=Release >/dev/null || die "English configure failed"
cmake --build build/host/english >/dev/null || die "English build failed"

rm -f build/conversion/report.txt
status=0
KOTOMO_CORPUS=1 ./gradlew --no-daemon -q :app:testDebugUnitTest \
  --tests com.yuanzhe.kotomo.ime.ConversionCorpusTest --tests com.yuanzhe.kotomo.ime.FuzzyRimeTest --tests com.yuanzhe.kotomo.dictionary.DictionaryRimeTest --tests com.yuanzhe.kotomo.dictionary.DictionaryPacksTest --rerun || status=$?
[[ -f build/conversion/report.txt ]] || die "the corpus did not run (Gradle exit $status)"
cat build/conversion/report.txt

# The coordinator's host prototype: unit tests, then the corpus through the three real engines.
cmake -S core/mixed -B build/host/mixed -G Ninja -DCMAKE_BUILD_TYPE=Release >/dev/null 2>&1 || die "coordinator configure failed"
cmake --build build/host/mixed >/dev/null || die "coordinator build failed"
ctest --test-dir build/host/mixed --output-on-failure >/dev/null || die "coordinator unit tests failed (ctest --test-dir build/host/mixed)"
echo
mixed_status=0
build/host/mixed/kotomo_mixed_corpus "$PWD" --baseline tests/conversion/baseline-mixed.tsv || mixed_status=$?
(( mixed_status < 2 )) || die "the coordinator's corpus run did not start (exit $mixed_status)"

if (( update )); then
  cp build/conversion/results.tsv tests/conversion/baseline.tsv
  cp build/conversion/mixed-results.tsv tests/conversion/baseline-mixed.tsv
  echo "corpus: baselines updated from this run"
  exit 0
fi
(( status == 0 )) || exit "$status"
exit "$mixed_status"
