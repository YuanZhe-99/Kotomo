#!/usr/bin/env bash
# Sweeps the coordinator's prior costs over the whole conversion corpus (PLAN.md §5.3, §13.3).
#
# Usage: tests/conversion/calibrate.sh [name=value,... ...]
#   Without arguments: each cost in turn at 0.5, 0.75, 1.25, 1.5 and 2 times its default, the
#   others at their defaults. With arguments: exactly those settings (KOTOMO_COSTS syntax).
# Prints one line per setting: Mixed lines passed, single-language lines passed, Mixed lines
# regressed and fixed against tests/conversion/baseline-mixed.tsv. A setting is a candidate
# only when it regresses nothing; the constants in core/mixed/options.h change by hand, with the
# result recorded in PLAN.md §5.3.
# Needs: the host build that tests/conversion/run.sh makes (run it once first).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
runner=build/host/mixed/kotomo_mixed_corpus
[[ -x $runner ]] || { echo "calibrate: missing $runner: run tests/conversion/run.sh" >&2; exit 1; }

measure() {
  local out
  out=$(KOTOMO_COSTS="$1" "$runner" . --baseline tests/conversion/baseline-mixed.tsv || true)
  printf '%-34s mixed %s  single %s  %s\n' "${1:-defaults}" \
    "$(sed -n 's/^Mixed lines through the coordinator: \([0-9/]*\) pass.*/\1/p' <<<"$out")" \
    "$(sed -n 's/^Single-language lines.*language: \([0-9/]*\) pass.*/\1/p' <<<"$out")" \
    "$(sed -n 's/^Against .*(Mixed lines): //p' <<<"$out")"
}

if (($# > 0)); then
  for setting in "$@"; do measure "$setting"; done
  exit 0
fi
measure ""
# The defaults of core/mixed/options.h; a name missing here is not swept.
while read -r name value; do
  for factor in 50 75 125 150 200; do
    measure "$name=$((value * factor / 100))"
  done
done <<'COSTS'
pinyin_syllable 1000
chinese_rank 2500
chinese_composed 4000
english_base 800
english_step 25
japanese_word 1500
pinyin_to_japanese 500
english_to_japanese 500
english_in_chinese 1500
english_in_japanese 4000
particle 1200
language_switch 500
crossed_hint 2500
COSTS
