#!/usr/bin/env bash
# Snapshot probe: build the measurement fixture, install it on the TV, play every candidate URL on
# the device and hold it, then pull the per-URL verdicts back. See README.md for the criteria.
#
#   tools/snapshot/probe.sh --candidates <m3u|name<TAB>url list> [--device SERIAL] [--out DIR]
#                           [--hold-ms 30000] [--first-frame-ms 3000] [--stall-ms 2000]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ADB="${ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
PKG="ilab.iptv.player.spike"
ACTIVITY="$PKG/ilab.iptv.spike.HoldProbeActivity"
DEVICE_FILES="/sdcard/Android/data/$PKG/files"
IN_NAME="probe-input.txt"

CANDIDATES=""
DEVICE=""
OUT_DIR=""
HOLD_MS=30000
FIRST_FRAME_MS=3000
STALL_MS=2000
OUT_NAME="probe"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --candidates) CANDIDATES="$2"; shift 2 ;;
    --device) DEVICE="$2"; shift 2 ;;
    --out) OUT_DIR="$2"; shift 2 ;;
    --hold-ms) HOLD_MS="$2"; shift 2 ;;
    --first-frame-ms) FIRST_FRAME_MS="$2"; shift 2 ;;
    --stall-ms) STALL_MS="$2"; shift 2 ;;
    --out-name) OUT_NAME="$2"; shift 2 ;;
    -h|--help) sed -n '1,12p' "$0"; exit 0 ;;
    *) echo "probe.sh: unknown argument: $1" >&2; exit 2 ;;
  esac
done

[[ -n "$CANDIDATES" ]] || { echo "probe.sh: --candidates is required" >&2; exit 2; }
[[ -f "$CANDIDATES" ]] || { echo "probe.sh: no such file: $CANDIDATES" >&2; exit 2; }
OUT_DIR="${OUT_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/snapshot-probe.XXXXXX")}"
mkdir -p "$OUT_DIR"

adb_dev() { "$ADB" ${DEVICE:+-s "$DEVICE"} "$@"; }

if [[ -n "$DEVICE" ]]; then
  "$ADB" connect "$DEVICE" >/dev/null 2>&1 || true
fi
state="$(adb_dev get-state 2>/dev/null || true)"
[[ "$state" == "device" ]] || { echo "probe.sh: no device (adb get-state=$state)" >&2; exit 3; }
serial="$(adb_dev get-serialno)"
echo "probe.sh: device $serial"

# 1) candidate list -> "<name>\t<url>" lines
input="$OUT_DIR/$IN_NAME"
python3 - "$CANDIDATES" > "$input" <<'PY'
import re, sys
path = sys.argv[1]
pending = None
count = 0
with open(path, "r", encoding="utf-8", errors="replace") as fh:
    for raw in fh:
        line = raw.strip()
        if not line:
            continue
        if line.startswith("#EXTINF"):
            name = line.split(",", 1)[1].strip() if "," in line else ""
            pending = name or "unnamed"
        elif line.startswith("#"):
            continue
        elif pending or re.match(r"^(https?|rtmp)://", line):
            # plain "<name>\t<url>" lists (no #EXTINF) are accepted too
            if "\t" in line and pending is None:
                name, url = line.split("\t", 1)
            else:
                name, url = pending or "unnamed", line
            print(f"{name}\t{url.strip()}")
            pending = None
            count += 1
if count == 0:
    sys.exit("probe.sh: candidate list produced 0 rows")
PY
echo "probe.sh: $(wc -l < "$input" | tr -d ' ') candidates"

# 2) build + install the fixture
echo "probe.sh: building spike probe fixture"
"$REPO_ROOT/gradlew" --offline -p "$REPO_ROOT/spike" assembleDebug -q
apk="$REPO_ROOT/spike/app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$apk" ]] || { echo "probe.sh: missing $apk" >&2; exit 4; }
adb_dev install -r "$apk" | tail -1

# 3) push the input list (the app's external files dir may need one headless run to exist)
if ! adb_dev push "$input" "$DEVICE_FILES/$IN_NAME" 2>/dev/null | tail -1; then
  adb_dev shell am start -n "$ACTIVITY" >/dev/null 2>&1 || true
  sleep 2
  adb_dev push "$input" "$DEVICE_FILES/$IN_NAME" | tail -1
fi

# 4) run
adb_dev shell rm -f "$DEVICE_FILES/$OUT_NAME.jsonl" "$DEVICE_FILES/$OUT_NAME.json" || true
adb_dev shell am start -n "$ACTIVITY" \
  -e in "$IN_NAME" -e out "$OUT_NAME" \
  -e holdMs "$HOLD_MS" -e firstFrameTimeoutMs "$FIRST_FRAME_MS" -e stallMs "$STALL_MS" >/dev/null
echo "probe.sh: running ($HOLD_MS ms hold per URL); poll: adb shell cat $DEVICE_FILES/$OUT_NAME.jsonl"

expected="$(wc -l < "$input" | tr -d ' ')"
deadline=$(( $(date +%s) + expected * (HOLD_MS / 1000 + 12) + 300 ))
while [[ "$(date +%s)" -lt "$deadline" ]]; do
  if adb_dev shell "test -f $DEVICE_FILES/$OUT_NAME.json" 2>/dev/null; then
    break
  fi
  sleep 20
done

# 5) pull (jsonl always; json when the run finished)
adb_dev pull "$DEVICE_FILES/$OUT_NAME.jsonl" "$OUT_DIR/$OUT_NAME.jsonl" >/dev/null 2>&1 || true
if adb_dev shell "test -f $DEVICE_FILES/$OUT_NAME.json" 2>/dev/null; then
  adb_dev pull "$DEVICE_FILES/$OUT_NAME.json" "$OUT_DIR/$OUT_NAME.json" >/dev/null 2>&1 || true
fi

got="$(wc -l < "$OUT_DIR/$OUT_NAME.jsonl" 2>/dev/null | tr -d ' ' || echo 0)"
passed="$(grep -c '"pass":true' "$OUT_DIR/$OUT_NAME.jsonl" 2>/dev/null || true)"
echo "probe.sh: pulled $got/$expected rows, pass=$passed -> $OUT_DIR/$OUT_NAME.jsonl"
[[ "$got" == "$expected" ]] || echo "probe.sh: WARNING — run ended early; re-run to fill the gaps" >&2
