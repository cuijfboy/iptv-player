#!/usr/bin/env bash
# S2, second half: AC-3 / E-AC-3 test vectors served from this Mac over plain HTTP.
#
# Every AC-3 stream in the dsh baseline is dead (segment host unreachable), and macOS has no AC-3
# encoder, so the AC-3 / E-AC-3 cells of the S2 matrix use public test vectors:
#   https://samples.ffmpeg.org/A-codecs/AC3/            (AC-3)
#   https://samples.ffmpeg.org/A-codecs/AC3/eac3/       (E-AC-3)
# The device fetches them from http://<mac-lan-ip>:8765/, i.e. a real network + real decoder path.
#
#   tools/run-local-audio.sh [samples-dir]
set -uo pipefail

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG=ilab.iptv.player.spike
PLAY="$PKG/ilab.iptv.spike.PlaybackSpikeActivity"
DIR="${1:-$HOME/iptv-spike-evidence/local-samples}"
EV="${EVIDENCE_DIR:-$HOME/iptv-spike-evidence}"
PORT="${PORT:-8765}"
IP="$(ipconfig getifaddr en0 || ipconfig getifaddr en1)"
BASE="http://$IP:$PORT"
mkdir -p "$EV"

log() { echo "[$(date +%H:%M:%S)] $*"; }

if ! curl -sf -o /dev/null "$BASE/$(ls "$DIR" | head -1)"; then
  log "starting http.server on $PORT in $DIR"
  (cd "$DIR" && nohup python3 -m http.server "$PORT" --bind 0.0.0.0 >/tmp/spike-http.log 2>&1 &)
  sleep 2
fi

snapshot() { # tag idx
  {
    echo "== S2 evidence $1 idx=$2 at $(date -u +%FT%TZ) =="
    echo "-- active audio tracks (audio_flinger) --"
    "$ADB" shell dumpsys media.audio_flinger | grep -E "^ *Track|^ *Output|format|Format|Direct|Offload|sample rate" | head -60
    echo "-- allocated codec instances (media.codec) --"
    "$ADB" shell dumpsys media.codec | grep -E "OMX|codec|c2\." | head -60
  } > "$EV/s2_$1_idx$2.txt" 2>&1
}

run() { # tag key passthrough urls acodecs names
  local tag="$1" key="$2" pt="$3" urls="$4" acodecs="$5" names="$6" last_hold=""
  log "start $tag (passthrough=$pt)"
  "$ADB" logcat -c
  "$ADB" shell am start -n "$PLAY" -e mode s2 -e s2key "$key" -e tag "$tag" \
    -e passthrough "$pt" -e holdMs 6000 -e gapMs 1500 -e probeMs 5000 \
    -e urls "$urls" -e acodecs "$acodecs" -e names "$names" >/dev/null 2>&1
  local deadline=$(( $(date +%s) + 300 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    local dump; dump="$("$ADB" logcat -d -s SPIKE:I)"
    if echo "$dump" | grep -qF "RUN_DONE mode=s2 tag=$tag"; then
      echo "$dump" | grep -F "RESULT mode=s2 tag=$tag" | cut -c40-260
      return 0
    fi
    local hold; hold="$(echo "$dump" | grep -F "S2_HOLD_START" | tail -1)"
    if [ -n "$hold" ] && [ "$hold" != "$last_hold" ]; then
      last_hold="$hold"
      snapshot "$tag" "$(echo "$hold" | sed -n 's/.*idx=\([0-9]*\).*/\1/p')"
    fi
    sleep 1
  done
  log "TIMEOUT $tag"
}

AC3_2CH="$BASE/TomorrowNeverDies-2.1-48khz-192kbit.ac3"
AC3_51="$BASE/monsters_inc_5.1_448.ac3"
EAC3_51="$BASE/matrix2_english_5.1_640.eac3"

run s2ac3local_sw ac3 false "$AC3_2CH,$AC3_51" "ac3,ac3" "AC3-2.1-192k,AC3-5.1-448k"
run s2ac3local_pt ac3 true  "$AC3_2CH,$AC3_51" "ac3,ac3" "AC3-2.1-192k,AC3-5.1-448k"
run s2eac3local_sw ac3 false "$EAC3_51" "eac3" "EAC3-5.1-640k"
run s2eac3local_pt ac3 true  "$EAC3_51" "eac3" "EAC3-5.1-640k"

"$ADB" pull "/sdcard/Android/data/$PKG/files/." "$EV/artifacts" 2>&1 | tail -1
