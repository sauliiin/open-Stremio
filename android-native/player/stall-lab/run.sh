#!/usr/bin/env bash
# Runs fault scenarios against StallLabDeviceTest on an attached emulator.
#
#   stall-lab/run.sh <output-dir> <scenario> [<scenario> ...]
#
# Needs the server running and the test APK installed: see player/PLAYBACK_STALLS.md.
# A name of the form <scenario>@<file> plays that fixture, and the LAB_* variables
# below pass straight through to the test.
set -u
OUT=$1; shift
mkdir -p "$OUT"
HOST=${LAB_HOST:-10.0.2.2:8765}
SERIAL=${ANDROID_SERIAL:-emulator-5554}
RUNNER=com.mdblisthub.tv.player.test/androidx.test.runner.AndroidJUnitRunner
CLASS=com.mdblisthub.tv.player.StallLabDeviceTest
MB=$((1024 * 1024))
FAULT_AT=$((60 * MB))

query_for() {
  case "$1" in
    healthy)        echo "" ;;
    blackhole)      echo "blackhole_at=$FAULT_AT" ;;
    trickle-conn)   echo "trickle_at=$FAULT_AT&trickle_bps=$((256 * 1024))" ;;
    slow-conn)      echo "trickle_at=$FAULT_AT&trickle_bps=$((1400 * 1024))" ;;
    slow-link)      echo "global_bps=$((2200 * 1024))" ;;
    rst)            echo "rst_at=$FAULT_AT" ;;
    corrupt)        echo "corrupt_at=$FAULT_AT&corrupt_len=$((4 * MB))" ;;
    one-conn)       echo "max_conns=1" ;;
    outage-30)      echo "outage_after=20&outage_secs=30" ;;
    outage-90)      echo "outage_after=20&outage_secs=90" ;;
    outage-180)     echo "outage_after=20&outage_secs=180" ;;
    expired)        echo "deny_at=$FAULT_AT" ;;
    # The same cut, answered differently from then on.
    unavailable)    echo "deny_at=$FAULT_AT&deny_mode=503" ;;
    decoy)          echo "deny_at=$FAULT_AT&deny_mode=html" ;;
    # A file that stops short of what its index promises.
    truncated)      echo "truncate_at=$((80 * MB))" ;;
    no-range)       echo "no_range=1" ;;
    no-range-blackhole) echo "no_range=1&blackhole_at=$FAULT_AT" ;;
    # One bad answer from the host, then a dead socket further on: the stall that comes
    # after must still be handled as a stall.
    damage-transient) echo "corrupt_at=$FAULT_AT&corrupt_len=$((4 * MB))&corrupt_once=1&blackhole_at=$((200 * MB))" ;;
    flaky-silent)   echo "flaky_every=$((40 * MB))&flaky_mode=silent" ;;
    flaky-slow)     echo "flaky_every=$((40 * MB))&flaky_mode=slow&trickle_bps=$((256 * 1024))" ;;
    flaky-reset)    echo "flaky_every=$((8 * MB))&flaky_mode=reset" ;;
    # Nothing listening at all: see the URL below.
    dead-host)      echo "" ;;
    # cold-<seconds> before the host answers anything.
    cold-*)         echo "cold_s=${1#cold-}" ;;
    # corrupt-<offset MB>-<length KB>
    corrupt-*)      IFS=- read -r _ at len <<< "$1"
                    echo "corrupt_at=$((at * MB))&corrupt_len=$((len * 1024))" ;;
    # Several small wounds, each its own episode.
    damage-many)    echo "corrupt_at=$((40 * MB)),$((120 * MB)),$((200 * MB)),$((280 * MB))&corrupt_len=$((4 * MB))" ;;
    # Damaged for minutes on end: not worth crossing.
    damage-hopeless) echo "corrupt_at=$((60 * MB))&corrupt_len=$((1500 * MB))" ;;
    # A kilobyte of garbage every 2MB: too small to break the container, so the frames
    # themselves are what reach the decoder damaged.
    damage-speckled) marks=$(seq -s, $((40 * MB)) $((2 * MB)) $((240 * MB)))
                    echo "corrupt_at=$marks&corrupt_len=1024" ;;
    # In the first bytes, before anything has played.
    damage-start)   echo "corrupt_at=$((1 * MB))&corrupt_len=$((4 * MB))" ;;
    *) echo "unknown scenario $1" >&2; exit 2 ;;
  esac
}

for entry in "$@"; do
  scenario=${entry%@*}
  file=movie.mkv
  [ "$entry" != "$scenario" ] && file=${entry#*@}
  name=${entry//[@.]/-}
  query=$(query_for "$scenario")
  stamp=$(date +%s)
  url="http://$HOST/$file?run=$name-$stamp&$query"
  [ "$scenario" = dead-host ] && url="http://${HOST%:*}:9/$file"
  seconds=${LAB_SECONDS:-150}
  echo "=== $name ($seconds s)"
  adb -s "$SERIAL" logcat -c
  adb -s "$SERIAL" logcat -v time -s Playback:* StallLab:* DefaultLoadControl:* ExoPlayerImplInternal:* \
    AndroidRuntime:E ActivityManager:W lmkd:* > "$OUT/$name.log" &
  logger=$!
  adb -s "$SERIAL" shell am instrument -w \
    -e class "$CLASS" -e lab_name "$name" -e lab_seconds "$seconds" \
    ${LAB_RUNTIME_MIN:+-e lab_runtime_min "$LAB_RUNTIME_MIN"} \
    ${LAB_FORWARD_AT_S:+-e lab_forward_at_s "$LAB_FORWARD_AT_S"} \
    ${LAB_PAUSE_AT_S:+-e lab_pause_at_s "$LAB_PAUSE_AT_S"} \
    ${LAB_PAUSE_FOR_S:+-e lab_pause_for_s "$LAB_PAUSE_FOR_S"} \
    ${LAB_START_MS:+-e lab_start_ms "$LAB_START_MS"} \
    ${LAB_BALLAST_MB:+-e lab_ballast_mb "$LAB_BALLAST_MB"} \
    ${LAB_SEEK_STORM_S:+-e lab_seek_storm_s "$LAB_SEEK_STORM_S"} \
    ${LAB_NEXT_TITLE_AT_S:+-e lab_next_title_at_s "$LAB_NEXT_TITLE_AT_S" -e lab_next_url "'http://$HOST/fast.mp4?run=next-$stamp'"} \
    ${LAB_REOPEN_AT_S:+-e lab_reopen_at_s "$LAB_REOPEN_AT_S"} \
    ${LAB_FALLBACK:+-e lab_fallback_url "'http://$HOST/$file?run=fallback-$stamp'"} \
    -e lab_url "'$url'" "$RUNNER" > "$OUT/$name.instrument.txt" 2>&1
  kill "$logger" 2>/dev/null
  wait "$logger" 2>/dev/null
  grep -h "RESULT" "$OUT/$name.log" | sed 's/.*RESULT /  /'
  grep -E "FAILURES|Process crashed|INSTRUMENTATION_FAILED" "$OUT/$name.instrument.txt" | sed 's/^/  /'
done
