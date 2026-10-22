#!/usr/bin/env bash
# Release builds must refuse a backend URL a phone cannot reach.
#
# The failure this guards: local.properties pointed SCANLY_API_BASE_URL at `wrangler dev`
# (http://127.0.0.1:8787/), a release APK was built from it, and on the phone every Worker call —
# password reset included — was refused and reported as "No internet connection."
#
# Runs the real Gradle build. The URL is passed with -PSCANLY_API_BASE_URL, which takes precedence
# over local.properties. Set FULL=1 to also run the complete https release build, which takes
# minutes.
#
# Run: app/test-release-url-guard.sh
set -u
cd "$(dirname "$0")/.."

failures=0

expect_refused() {
  local url=$1
  local log
  log=$(./gradlew :app:assembleRelease "-PSCANLY_API_BASE_URL=$url" --console=plain 2>&1)
  if [ $? -eq 0 ]; then
    echo "FAIL  $url: release build succeeded; it must be refused"
    failures=$((failures + 1))
  elif ! grep -q "Release builds must use the deployed Worker" <<<"$log"; then
    echo "FAIL  $url: build failed, but not because of the URL guard:"
    grep -E "What went wrong|error:|FAILED" <<<"$log" | head -5
    failures=$((failures + 1))
  else
    echo "ok    $url: refused — $(grep -m1 -o 'SCANLY_API_BASE_URL (.*) [A-Za-z ,]*\. Release' <<<"$log" | sed 's/\. Release$//')"
  fi
}

expect_built() {
  local url=$1
  if ./gradlew :app:assembleRelease "-PSCANLY_API_BASE_URL=$url" --console=plain >/tmp/release-url-guard.log 2>&1 &&
    grep -q "\"$url\"" app/build/generated/source/buildConfig/release/com/skeler/scanely/BuildConfig.java; then
    echo "ok    $url: release build succeeded and BuildConfig carries it"
  else
    echo "FAIL  $url: release build failed or BuildConfig does not carry the URL"
    tail -20 /tmp/release-url-guard.log
    failures=$((failures + 1))
  fi
}

expect_refused "http://127.0.0.1:8787/"
expect_refused "https://127.0.0.1/"
expect_refused "https://localhost:8787/"
expect_refused "http://10.0.2.2:8787/"
expect_refused "https://[::1]/"
expect_refused "http://scanly-api.webforge-skeler.workers.dev/"
expect_refused "not a url"

if [ "${FULL:-0}" = 1 ]; then
  expect_built "https://scanly-api.webforge-skeler.workers.dev/"
fi

echo "$failures failure(s)"
[ "$failures" -eq 0 ]
