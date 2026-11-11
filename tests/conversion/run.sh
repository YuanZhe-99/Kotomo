#!/usr/bin/env bash
# Runs the conversion corpus (PLAN.md §13.3) on the host: tests/conversion/corpus.tsv through the
# app's own engine facades and candidate assembly (ConversionCorpusTest), compared line by line
# with tests/conversion/baseline.tsv; also the host fuzzy-pinyin test (FuzzyRimeTest, T08), which
# needs the same host librime.
#
# Usage: tests/conversion/run.sh [--update]
#   --update  accepts this run as the new baseline (copies build/conversion/results.tsv over
#             tests/conversion/baseline.tsv); review the report before committing it
# Needs: the outputs of scripts/prebuild-rime.sh, scripts/build-mozc.sh --abi host,
#   java tool/BuildEnglishDict.java and java lexicon/EmojiData.java; CMake, Ninja, a C++ compiler
#   and the Android SDK (ANDROID_HOME) for Gradle.
# Side effects: builds the host librime and English libraries into build/host/; writes
#   build/conversion/results.tsv and report.txt, and with --update the baseline.
# Exit status: 0 when no line regressed against the baseline and FuzzyRimeTest passed, non-zero otherwise.
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

cmake -S core/rime -B build/host/rime -G Ninja -DCMAKE_BUILD_TYPE=Release >/dev/null 2>&1 || die "librime configure failed"
cmake --build build/host/rime --target kotomo_rime >/dev/null || die "librime build failed"
cmake -S core/english -B build/host/english -G Ninja -DCMAKE_BUILD_TYPE=Release >/dev/null || die "English configure failed"
cmake --build build/host/english >/dev/null || die "English build failed"

rm -f build/conversion/report.txt
status=0
KOTOMO_CORPUS=1 ./gradlew --no-daemon -q :app:testDebugUnitTest \
  --tests com.yuanzhe.kotomo.ime.ConversionCorpusTest --tests com.yuanzhe.kotomo.ime.FuzzyRimeTest --rerun || status=$?
[[ -f build/conversion/report.txt ]] || die "the corpus did not run (Gradle exit $status)"
cat build/conversion/report.txt

if (( update )); then
  cp build/conversion/results.tsv tests/conversion/baseline.tsv
  echo "corpus: baseline updated from this run"
  exit 0
fi
exit "$status"
