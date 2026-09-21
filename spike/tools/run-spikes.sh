#!/usr/bin/env bash
# Drive every spike run on the connected device and collect the JSON artefacts.
#
#   tools/run-spikes.sh [s1|s2|s5|s3|s6|all]
#
# Results land in $EVIDENCE_DIR (default ~/iptv-spike-evidence): the app's own JSON plus, for S2,
# audio_flinger / media.codec snapshots taken while each sample is on screen (the audible-output
# evidence, since nobody can listen to the TV from a script).
set -uo pipefail

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
PKG=ilab.iptv.player.spike
PLAY="$PKG/ilab.iptv.spike.PlaybackSpikeActivity"
HOME_ACT="$PKG/ilab.iptv.spike.SpikeHomeActivity"
EV="${EVIDENCE_DIR:-$HOME/iptv-spike-evidence}"
REMOTE="/sdcard/Android/data/$PKG/files"
mkdir -p "$EV"

log() { echo "[$(date +%H:%M:%S)] $*"; }

snapshot() { # tag idx
  local tag="$1" idx="$2" file="$EV/s2_${1}_idx${2}.txt"
  {
    echo "== S2 evidence $tag idx=$idx at $(date -u +%FT%TZ) =="
    echo "-- active audio tracks (audio_flinger) --"
    "$ADB" shell dumpsys media.audio_flinger | grep -E "^ *Track|^ *Output|format|Format|Direct|Offload|sample rate" | head -60
    echo "-- allocated codec instances (media.codec) --"
    "$ADB" shell dumpsys media.codec | grep -E "OMX|codec|c2\." | head -60
  } > "$file" 2>&1
}

wait_run() { # mode tag
  local mode="$1" tag="$2" deadline=$(( $(date +%s) + 900 )) last_hold=""
  while [ "$(date +%s)" -lt "$deadline" ]; do
    local dump; dump="$("$ADB" logcat -d -s SPIKE:I)"
    if echo "$dump" | grep -qF "RUN_DONE mode=$mode tag=$tag"; then
      echo "$dump" | grep -F "RUN_DONE mode=$mode tag=$tag" | tail -1
      return 0
    fi
    local hold; hold="$(echo "$dump" | grep -F "S2_HOLD_START" | tail -1)"
    if [ -n "$hold" ] && [ "$hold" != "$last_hold" ]; then
      last_hold="$hold"
      snapshot "$tag" "$(echo "$hold" | sed -n 's/.*idx=\([0-9]*\).*/\1/p')"
    fi
    sleep 1
  done
  log "TIMEOUT waiting for $mode/$tag"
  return 1
}

play_run() { # tag mode extra...
  local tag="$1" mode="$2"; shift 2
  log "start $mode tag=$tag $*"
  "$ADB" logcat -c
  "$ADB" shell am start -n "$PLAY" -e mode "$mode" -e tag "$tag" "$@" >/dev/null 2>&1
  wait_run "$mode" "$tag"
}

cold_start() { # n
  local n="$1"
  # Every number the S6 table prints must come from a saved file, so each run writes
  # its own $EV/colds6/run-$n.txt (am start -W output + the fixture's COLD_* logcat lines).
  local outfile="$EV/colds6/run-$n.txt"
  mkdir -p "$EV/colds6"
  "$ADB" shell am force-stop "$PKG"
  sleep 3
  "$ADB" logcat -c
  # tag stays "s6-$n": the fixture prefixes it with "cold", so the artefact lands as s1_colds6-$n.json.
  local out; out="$("$ADB" shell am start -W -n "$HOME_ACT" -e autoplayIdx 2 -e tag "s6-$n" | tr -d '\r')"
  {
    echo "== S6 cold start #$n at $(date -u +%FT%TZ) =="
    echo "-- am start -W --"
    echo "$out" | grep -E "TotalTime|WaitTime|Status"
  } > "$outfile"
  echo "$out" | grep -E "TotalTime|WaitTime|Status" | tr '\n' ' '
  local deadline=$(( $(date +%s) + 60 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    local line; line="$("$ADB" logcat -d -s SPIKE:I | grep -E "COLD_HOME_FIRST_FRAME|COLD_FULLY_DRAWN|COLD_VIDEO_FIRST_FRAME")"
    if echo "$line" | grep -q "COLD_VIDEO_FIRST_FRAME"; then
      {
        echo "-- fixture logcat (SPIKE:I) --"
        echo "$line"
      } >> "$outfile"
      echo "$line" | tr '\n' ' '
      return 0
    fi
    sleep 1
  done
  echo "-- TIMEOUT waiting for COLD_VIDEO_FIRST_FRAME --" >> "$outfile"
  log "TIMEOUT waiting for cold start $n"
}

cd "$(dirname "$0")/.."

case "${1:-all}" in
  s1|all) play_run s1a s1 ;;
esac
case "${1:-all}" in
  s2|all)
    play_run s2aac_sw s2 -e s2key aac -e holdMs 6000 -e gapMs 1500
    play_run s2mp2_sw s2 -e s2key mp2 -e holdMs 6000 -e gapMs 1500
    play_run s2ac3_sw s2 -e s2key ac3 -e holdMs 6000 -e gapMs 1500
    play_run s2ac3_pt s2 -e s2key ac3 -e passthrough true -e holdMs 6000 -e gapMs 1500
    ;;
esac
case "${1:-all}" in
  s5|all)
    play_run s5reuse s5 -e switches 30
    play_run s5recreate s5 -e switches 10 -e recreate true
    ;;
esac
case "${1:-all}" in
  s3|all)
    "$ADB" logcat -c
    "$ADB" shell am start -n "$PKG/ilab.iptv.spike.EpgGridSpikeActivity" -e mode virtual -e tag s3virtual -e durationMs 30000 >/dev/null
    for i in $(seq 1 90); do
      "$ADB" logcat -d -s SPIKE:I | grep -q "S3_DONE mode=virtual" && break
      sleep 2
    done
    "$ADB" logcat -d -s SPIKE:I | grep "S3_DONE" | tail -1
    "$ADB" logcat -c
    "$ADB" shell am start -n "$PKG/ilab.iptv.spike.EpgGridSpikeActivity" -e mode naive -e tag s3naive -e durationMs 15000 >/dev/null
    for i in $(seq 1 90); do
      "$ADB" logcat -d -s SPIKE:I | grep -q "S3_DONE mode=naive" && break
      sleep 2
    done
    "$ADB" logcat -d -s SPIKE:I | grep "S3_DONE" | tail -1
    ;;
esac
case "${1:-all}" in
  s6|all)
    for n in 1 2 3 4 5; do cold_start "$n"; done
    ;;
esac

log "pulling artefacts"
"$ADB" pull "$REMOTE/." "$EV/artifacts" 2>&1 | tail -1
log "done; evidence in $EV"
