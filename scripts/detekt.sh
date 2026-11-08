#!/usr/bin/env bash
# Detekt as CI runs it (.github/workflows/quality.yml), plus --parallel (40 s -> 15 s) and a 2 GB heap.
set -euo pipefail
cd "$(dirname "$0")/.."
SCANLY_MIN_FREE_GB=${SCANLY_MIN_FREE_GB:-4} . "$(dirname "$0")/mem-preflight.sh"  # Detekt: 2 GB heap
V=2.0.0-alpha.6
D="$HOME/.cache/detekt/$V"
if [ ! -f "$D/detekt-cli-$V-all.jar" ]; then
  mkdir -p "$D"
  B="https://github.com/detekt/detekt/releases/download/v$V"
  curl -sSfL -o "$D/detekt-cli-$V-all.jar" "$B/detekt-cli-$V-all.jar"
  curl -sSfL -o "$D/detekt-rules-ktlint-wrapper-$V.jar" "$B/detekt-rules-ktlint-wrapper-$V.jar"
fi
# Same memory budget and lock as scripts/gw, so Detekt and a resident Gradle daemon can't add up past it.
# Holding the lock means no build is running, so an idle daemon is only holding memory Detekt needs in the
# shared slice (it was throttled from 17 s to over 5 min): stop it first.
if command -v systemd-run >/dev/null && [ -z "${CI:-}" ]; then
  exec 9>"${XDG_RUNTIME_DIR:-/tmp}/scanly-build.lock"
  flock 9
  ./gradlew --stop -q >/dev/null 2>&1 || true
  RUN=(systemd-run --user --scope --quiet --slice=scanly-build.slice --)
else
  RUN=()
fi
exec "${RUN[@]}" nice -n 5 java -Xmx2g -jar "$D/detekt-cli-$V-all.jar" --parallel \
  --config detekt.yml --build-upon-default-config \
  --plugins "$D/detekt-rules-ktlint-wrapper-$V.jar" \
  --baseline detekt-baseline.xml --input app/src/main/java "$@"
