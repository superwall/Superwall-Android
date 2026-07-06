#!/usr/bin/env bash
# Runs the paywall preload benchmark against a booted emulator/device.
#
# Invoked by .github/workflows/preload-benchmark.yml as a SINGLE line because
# android-emulator-runner executes each line of its `script` input in a
# separate `sh -c` — multi-line shell constructs there fail to parse.
#
# Usage: scripts/run_preload_benchmark.sh <tier> [runs] [iterations] [timeout_sec]
set -euo pipefail

TIER="${1:?usage: run_preload_benchmark.sh <tier> [runs] [iterations] [timeout_sec]}"
RUNS="${2:-3}"
ITERATIONS="${3:-3}"
TIMEOUT_SEC="${4:-300}"

# Let the emulator settle after boot before touching it.
sleep 5

mkdir -p app/build/outputs/benchmark
adb shell rm -rf /sdcard/Download/superwall-benchmark || true

# Each invocation reinstalls the app and clears its data, so every run's first
# iteration is a true cold start. The compare script averages across all runs.
for run in $(seq 1 "$RUNS"); do
  echo "::group::Benchmark run $run/$RUNS ($TIER tier)"
  ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.example.superapp.benchmark.PaywallPreloadBenchmark \
    -Pandroid.testInstrumentationRunnerArguments.benchmarkDeviceTier="$TIER" \
    -Pandroid.testInstrumentationRunnerArguments.benchmarkRunIndex="$run" \
    -Pandroid.testInstrumentationRunnerArguments.benchmarkIterations="$ITERATIONS" \
    -Pandroid.testInstrumentationRunnerArguments.benchmarkTimeoutSec="$TIMEOUT_SEC" \
    -x lint -x lintDebug -x lintVitalRelease
  echo "::endgroup::"
done

adb pull /sdcard/Download/superwall-benchmark/. app/build/outputs/benchmark/
