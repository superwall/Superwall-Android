#!/usr/bin/env bash
# Runs the paywall preload benchmark against a booted emulator/device using
# prebuilt APKs — no gradle involved, so per-run overhead is just
# `pm clear` + `am instrument`.
#
# Invoked by .github/workflows/preload-benchmark.yml as a SINGLE line because
# android-emulator-runner executes each line of its `script` input in a
# separate `sh -c` — multi-line shell constructs there fail to parse.
#
# Usage: scripts/run_preload_benchmark.sh <tier> <runs> <iterations> <timeout_sec> <app_apk> <test_apk>
set -euo pipefail

TIER="${1:?usage: run_preload_benchmark.sh <tier> <runs> <iterations> <timeout_sec> <app_apk> <test_apk>}"
RUNS="${2:-3}"
ITERATIONS="${3:-2}"
TIMEOUT_SEC="${4:-300}"
APP_APK="${5:?path to app-debug.apk}"
TEST_APK="${6:?path to app-debug-androidTest.apk}"

APP_PKG="com.superwall.superapp"
RUNNER="$APP_PKG.test/androidx.test.runner.AndroidJUnitRunner"

[ -f "$APP_APK" ] || { echo "app APK not found: $APP_APK"; exit 1; }
[ -f "$TEST_APK" ] || { echo "test APK not found: $TEST_APK"; exit 1; }

# Let the emulator settle after boot before touching it.
sleep 5

adb install -r "$APP_APK"
adb install -r "$TEST_APK"
adb shell rm -rf /sdcard/Download/superwall-benchmark || true

# Connectivity pre-flight (informational): raw IP vs DNS from inside the guest,
# and the Superwall API from the host. Separates emulator-DNS failures from
# API-side blocks when the benchmark can't configure.
echo "::group::Connectivity pre-flight"
adb shell ping -c 2 -W 3 8.8.8.8 || echo "guest: raw IP connectivity FAILED"
adb shell ping -c 2 -W 3 api.superwall.me || echo "guest: DNS resolution of api.superwall.me FAILED"
curl -sS -m 10 -o /dev/null -w "host -> api.superwall.me: HTTP %{http_code}\n" https://api.superwall.me/ || echo "host: api.superwall.me unreachable"
echo "::endgroup::"

# Each run clears the app's data first, so every run's first iteration is a
# true cold start. The compare script averages across all runs.
for run in $(seq 1 "$RUNS"); do
  echo "::group::Benchmark run $run/$RUNS ($TIER tier)"
  adb shell pm clear "$APP_PKG"
  start_s=$(date +%s)
  # No tee here: /dev/stderr is not a usable device in the CI runner's shell,
  # and a dead tee (with pipefail) both kills the script and swallows the
  # instrumentation output. Capture, then print.
  out=$(adb shell am instrument -w \
    -e class com.example.superapp.benchmark.PaywallPreloadBenchmark \
    -e benchmarkDeviceTier "$TIER" \
    -e benchmarkRunIndex "$run" \
    -e benchmarkIterations "$ITERATIONS" \
    -e benchmarkTimeoutSec "$TIMEOUT_SEC" \
    "$RUNNER" 2>&1) || true
  printf '%s\n' "$out"
  echo "Run $run took $(( $(date +%s) - start_s ))s"
  echo "::endgroup::"
  # `am instrument` exits 0 through adb even on failure — parse the output.
  if ! echo "$out" | grep -q "OK (" || echo "$out" | grep -qE "FAILURES!!!|INSTRUMENTATION_ABORTED|INSTRUMENTATION_FAILED|Process crashed"; then
    echo "Benchmark run $run failed — recent device log (filtered):"
    adb logcat -d -t 600 | grep -E "SWPreloadBenchmark|Superwall|superapp|AndroidRuntime|FATAL" | tail -120 || true
    echo "Benchmark run $run failed — recent device log (unfiltered tail):"
    adb logcat -d -t 150 || true
    exit 1
  fi
done

mkdir -p app/build/outputs/benchmark
adb pull /sdcard/Download/superwall-benchmark/. app/build/outputs/benchmark/
