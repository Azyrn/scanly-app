#!/usr/bin/env bash
# Detekt as CI runs it (.github/workflows/quality.yml), plus --parallel (40 s -> 15 s) and a 2 GB heap.
set -euo pipefail
cd "$(dirname "$0")/.."
V=2.0.0-alpha.6
D="$HOME/.cache/detekt/$V"
if [ ! -f "$D/detekt-cli-$V-all.jar" ]; then
  mkdir -p "$D"
  B="https://github.com/detekt/detekt/releases/download/v$V"
  curl -sSfL -o "$D/detekt-cli-$V-all.jar" "$B/detekt-cli-$V-all.jar"
  curl -sSfL -o "$D/detekt-rules-ktlint-wrapper-$V.jar" "$B/detekt-rules-ktlint-wrapper-$V.jar"
fi
exec nice -n 5 java -Xmx2g -jar "$D/detekt-cli-$V-all.jar" --parallel \
  --config detekt.yml --build-upon-default-config \
  --plugins "$D/detekt-rules-ktlint-wrapper-$V.jar" \
  --baseline detekt-baseline.xml --input app/src/main/java "$@"
