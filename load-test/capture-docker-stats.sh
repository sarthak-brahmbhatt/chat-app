#!/usr/bin/env bash
#
# Samples chat-service's resource usage every INTERVAL seconds and appends a
# timestamped row to a CSV — meant to run IN THE BACKGROUND, in parallel with
# ws-ramp-test.js (build-order step 11), so a specific CPU%/memory reading can
# be lined up against whichever connection-count step k6 was on at that same
# wall-clock moment.
#
# `docker stats --no-stream` in a loop, not `docker stats`'s own default
# streaming mode — the streaming mode redraws in place for a live terminal,
# which is useless for a log file meant to be grepped/plotted afterward.
#
# Usage:
#   ./load-test/capture-docker-stats.sh [container] [outputFile] [intervalSeconds]
#   ./load-test/capture-docker-stats.sh chat-app-chat-service load-test/docker-stats.csv 2
#
# Stop with Ctrl+C once the k6 run has finished.
set -euo pipefail

CONTAINER="${1:-chat-app-chat-service}"
OUTPUT="${2:-load-test/docker-stats.csv}"
INTERVAL="${3:-2}"

echo "timestamp,cpu_percent,mem_usage,mem_percent" > "$OUTPUT"
echo "Sampling $CONTAINER every ${INTERVAL}s -> $OUTPUT (Ctrl+C to stop)"

while true; do
  STATS=$(docker stats --no-stream --format '{{.CPUPerc}},{{.MemUsage}},{{.MemPerc}}' "$CONTAINER" 2>/dev/null || echo "N/A,N/A,N/A")
  echo "$(date -u +%Y-%m-%dT%H:%M:%SZ),$STATS" >> "$OUTPUT"
  sleep "$INTERVAL"
done
