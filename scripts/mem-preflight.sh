#!/usr/bin/env bash
# Sourced by scripts/gw and scripts/detekt.sh: refuse to start unless the PC has room for the job —
# 6 GB for a Gradle build (the scanly-build.slice budget), 4 GB for Detekt. A cap alone did not stop the
# freezes: twice the PC ran out of memory system-wide while the build was still under its own limit.
need_kb=$(( ${SCANLY_MIN_FREE_GB:-6} * 1024 * 1024 ))
avail_kb=$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo)
# Memory the slice already holds (an idle daemon) is reused by the next build, so it counts as room.
slice_bytes=$(systemctl --user show scanly-build.slice -p MemoryCurrent --value 2>/dev/null)
case "$slice_bytes" in ''|*[!0-9]*) slice_bytes=0 ;; esac
avail_kb=$(( avail_kb + slice_bytes / 1024 ))
if [ -z "${CI:-}" ] && [ "${avail_kb:-0}" -lt "$need_kb" ]; then
  echo "Not starting: $(( avail_kb / 1024 )) MB available, builds need $(( need_kb / 1024 )) MB." >&2
  echo "Close some browser tabs or apps and try again (override: SCANLY_MIN_FREE_GB=4)." >&2
  exit 75
fi
