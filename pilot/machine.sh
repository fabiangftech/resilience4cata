#!/usr/bin/env bash
# Runs the pilot from a bundle (built by run.sh): the backend, then each service in turn under the k6 load of
# load.js, sampling the service every 5 s. Results in $OUT. On the AWS machine the service, the backend and k6 each
# get their own cores (taskset); locally nothing is pinned.
#
# Environment: OUT (default ./results), MINUTE (seconds per minute of load.js, default 60), APPS (default
# "spring micronaut quarkus"), JAVA, JCMD, K6, APP_CPUS/BACKEND_CPUS/K6_CPUS (empty = not pinned).
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${OUT:-$HERE/results}
MINUTE=${MINUTE:-60}
APPS=${APPS:-spring micronaut quarkus}
JAVA=${JAVA:-java}
JCMD=${JCMD:-jcmd}
K6=${K6:-k6}
APP_CPUS=${APP_CPUS-}
BACKEND_CPUS=${BACKEND_CPUS-}
K6_CPUS=${K6_CPUS-}
mkdir -p "$OUT"
log() { echo "$(date +%T) $*"; }
now_ms() { python3 -c 'import time; print(int(time.time() * 1000))'; }
pin() { # <cpus> <command...>: replaces the calling (sub)shell, so that $! of `( pin ... ) &` is the process itself
  local cpus=$1; shift
  if [ -n "$cpus" ] && command -v taskset > /dev/null; then exec taskset -c "$cpus" "$@"; else exec "$@"; fi
}
wait_up() { # <url> <seconds>
  for _ in $(seq $(($2 * 4))); do curl -sf -o /dev/null "$1" && return 0; sleep 0.25; done
  return 1
}
heap() { # <pid>: full GC, then the heap's numbers
  "$JCMD" "$1" GC.run > /dev/null
  "$JCMD" "$1" GC.heap_info
}
sample() { # <pid> <file>: every 5 s, one JSON line: time, CPU ticks, RSS, threads, the service's stats
  while kill -0 "$1" 2> /dev/null; do
    local ticks=null rss=null threads=null
    if [ -r "/proc/$1/stat" ]; then
      ticks=$(awk '{print $14 + $15}' "/proc/$1/stat")
      rss=$(awk '/VmRSS/ {print $2}' "/proc/$1/status")
      threads=$(awk '/Threads/ {print $2}' "/proc/$1/status")
    fi
    local stats
    stats=$(curl -s -m 2 http://127.0.0.1:8080/pilot/stats || echo null)
    echo "{\"t\":$(( $(now_ms) ))e-3,\"ticks\":$ticks,\"rssKb\":$rss,\"threads\":$threads,\"stats\":${stats:-null}}" >> "$2"
    sleep 5
  done
}

log "java: $("$JAVA" -version 2>&1 | head -1)"
( pin "$BACKEND_CPUS" "$JAVA" -Xms1g -Xmx1g -cp "$HERE/backend/lib/*" pilot.backend.Backend 9000 ) > "$OUT/backend.log" 2>&1 &
BACKEND=$!
wait_up http://127.0.0.1:9000/admin/stats 30 || { log "the backend did not start"; exit 1; }

for app in $APPS; do
  log "== $app"
  curl -s -XPOST -o /dev/null http://127.0.0.1:9000/admin/mode/normal
  curl -s http://127.0.0.1:9000/admin/stats > "$OUT/$app-backend-before.json"
  JVM=(-Xms512m -Xmx512m -Xlog:gc:file="$OUT/$app-gc.log" -XX:StartFlightRecording=filename="$OUT/$app.jfr",dumponexit=true)
  case $app in
    spring) CMD=("$JAVA" "${JVM[@]}" -cp "$HERE/spring/lib/*" pilot.spring.App) ;;
    micronaut) CMD=("$JAVA" "${JVM[@]}" -cp "$HERE/micronaut/lib/*" pilot.micronaut.App) ;;
    quarkus) CMD=("$JAVA" "${JVM[@]}" -jar "$HERE/quarkus/quarkus-run.jar") ;;
  esac
  started=$(now_ms)
  ( pin "$APP_CPUS" "${CMD[@]}" ) > "$OUT/$app.log" 2>&1 &
  APP=$!
  if ! wait_up http://127.0.0.1:8080/pilot/stats 90; then log "$app did not start"; kill "$APP"; continue; fi
  echo "{\"startupMillis\":$(( $(now_ms) - started ))}" > "$OUT/$app-startup.json"
  sample "$APP" "$OUT/$app-samples.jsonl" &
  SAMPLER=$!
  # The baseline heap at the end of the warm-up minute, which load.js does not judge.
  ( sleep $((MINUTE - 5)); heap "$APP" > "$OUT/$app-heap-warm.txt" 2>&1 ) &
  log "load"
  ( pin "$K6_CPUS" "$K6" run -q -e MINUTE="$MINUTE" -e SUMMARY="$OUT/$app-k6.json" "$HERE/load.js" ) > "$OUT/$app-k6.log" 2>&1
  log "load done; settling"
  sleep 15 # open breakers, retry delays and timers finish
  curl -s http://127.0.0.1:8080/pilot/stats > "$OUT/$app-final.json"
  heap "$APP" > "$OUT/$app-heap-end.txt" 2>&1
  "$JCMD" "$APP" Thread.print > "$OUT/$app-threads.txt" 2>&1
  curl -s http://127.0.0.1:9000/admin/stats > "$OUT/$app-backend-after.json"
  kill "$SAMPLER" 2> /dev/null
  kill "$APP"; wait "$APP" 2> /dev/null
  # The next service needs the port.
  for _ in $(seq 40); do curl -s -o /dev/null http://127.0.0.1:8080/ || break; sleep 0.25; done
done
kill "$BACKEND"; wait "$BACKEND" 2> /dev/null
log "all done"
