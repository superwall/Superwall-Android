#!/usr/bin/env bash
set -euo pipefail

TIER="${1:?usage: run_preload_benchmark.sh <tier> <runs> <iterations> <timeout_sec> <app_apk> <test_apk> [placements]}"
RUNS="${2:-3}"
ITERATIONS="${3:-2}"
TIMEOUT_SEC="${4:-300}"
APP_APK="${5:?path to app-debug.apk}"
TEST_APK="${6:?path to app-debug-androidTest.apk}"
PLACEMENTS="${7:-}"

APP_PKG="com.superwall.superapp"
RUNNER="$APP_PKG.test/androidx.test.runner.AndroidJUnitRunner"

[ -f "$APP_APK" ] || { echo "app APK not found: $APP_APK"; exit 1; }
[ -f "$TEST_APK" ] || { echo "test APK not found: $TEST_APK"; exit 1; }

sleep 5

adb install -r "$APP_APK"
adb install -r "$TEST_APK"
adb shell rm -rf /sdcard/Download/superwall-benchmark || true

GMS_STABLE_SEC=45
GMS_MAX_WAIT_SEC=240
echo "::group::Wait for GMS to stabilize"
stable_since=$(date +%s)
wait_start=$stable_since
last_pid=""
while :; do
  now=$(date +%s)
  pid=$(adb shell pidof com.google.android.gms.persistent 2>/dev/null | tr -d '[:space:]' || true)
  if [ -z "$pid" ] || [ "$pid" != "$last_pid" ]; then
    last_pid="$pid"
    stable_since=$now
  fi
  if [ -n "$pid" ] && [ $((now - stable_since)) -ge "$GMS_STABLE_SEC" ]; then
    echo "GMS persistent pid $pid stable for ${GMS_STABLE_SEC}s after $((now - wait_start))s"
    break
  fi
  if [ $((now - wait_start)) -ge "$GMS_MAX_WAIT_SEC" ]; then
    echo "GMS still churning after ${GMS_MAX_WAIT_SEC}s — proceeding anyway"
    break
  fi
  sleep 5
done
echo "::endgroup::"

echo "::group::Network bring-up + pre-flight"
adb shell svc data enable || true
adb shell svc wifi disable || true
sleep 2
adb shell svc wifi enable || true
for i in $(seq 1 30); do
  if adb shell dumpsys connectivity | grep "Active default network" | grep -qv none; then
    echo "guest: active default network up after ~$((i * 2))s"
    break
  fi
  sleep 2
done
adb shell dumpsys connectivity | grep -iE "active default|validated" | head -10 || true
adb shell ping -c 1 -W 2 api.superwall.me || true
curl -sS -m 10 -o /dev/null -w "host -> api.superwall.me: HTTP %{http_code}\n" https://api.superwall.me/ || echo "host: api.superwall.me unreachable"
echo "::endgroup::"

for run in $(seq 1 "$RUNS"); do
  echo "::group::Benchmark run $run/$RUNS ($TIER tier)"
  attempt=1
  while :; do
    adb shell pm clear "$APP_PKG"
    adb logcat -c || true
    start_s=$(date +%s)
    extra_args=()
    if [ -n "$PLACEMENTS" ]; then
      extra_args+=(-e benchmarkPlacements "$PLACEMENTS")
    fi
    out=$(adb shell am instrument -w \
      -e class com.example.superapp.benchmark.PaywallPreloadBenchmark \
      -e benchmarkDeviceTier "$TIER" \
      -e benchmarkRunIndex "$run" \
      -e benchmarkIterations "$ITERATIONS" \
      -e benchmarkTimeoutSec "$TIMEOUT_SEC" \
      "${extra_args[@]}" \
      "$RUNNER" 2>&1) || true
    printf '%s\n' "$out"
    echo "Run $run attempt $attempt took $(( $(date +%s) - start_s ))s"
    if echo "$out" | grep -q "OK (" && ! echo "$out" | grep -qE "FAILURES!!!|INSTRUMENTATION_ABORTED|INSTRUMENTATION_FAILED"; then
      break
    fi
    if echo "$out" | grep -q "Process crashed" && ! echo "$out" | grep -q "FAILURES!!!" && [ "$attempt" -lt 2 ]; then
      echo "Process was killed externally (Play services churn) — retrying run $run once"
      attempt=$((attempt + 1))
      continue
    fi
    echo "::endgroup::"
    echo "Benchmark run $run failed — SDK/app device log:"
    adb logcat -d -s SWPreloadBenchmark:V System.out:V System.err:V TestRunner:V AndroidRuntime:E StrictMode:V DEBUG:V CRASH:V libc:V ActivityManager:V Zygote:V | tail -450 || true
    exit 1
  done
  echo "::endgroup::"
done

mkdir -p app/build/outputs/benchmark
adb pull /sdcard/Download/superwall-benchmark/. app/build/outputs/benchmark/
