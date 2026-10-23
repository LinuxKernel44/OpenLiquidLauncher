#!/usr/bin/env bash
# Pulls the diagnostic captures of the "diag" build from a plugged-in phone.
# usage: tools/diag-pull.sh [hours=6] [serial]   -> ./diag-pull/<timestamp>/
set -euo pipefail
HOURS=${1:-6}
SER=${2:-}
ADB="adb ${SER:+-s $SER}"
PKG=com.linuxkernel44.openliquidlauncher
SRC=/sdcard/Android/data/$PKG/files/diag
OUT=diag-pull/$(date +%Y%m%d_%H%M%S)
mkdir -p "$OUT"
$ADB shell "du -sh $SRC; ls $SRC | wc -l; cat $SRC/marks.txt 2>/dev/null" | tee "$OUT/summary.txt"
# only what changed in the last N hours (log chunks are 64 MB each)
for f in $($ADB shell "find $SRC -type f -mmin -$((HOURS * 60))" | tr -d '\r'); do $ADB pull -q "$f" "$OUT/"; done
$ADB pull -q $SRC/marks.txt "$OUT/" 2>/dev/null || true
echo "pulled to $OUT"; ls -la "$OUT" | head -50
