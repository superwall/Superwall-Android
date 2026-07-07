# Paywall Preload Benchmark

Benchmarks how long the SDK takes to preload the paywalls for a **pinned set of
placements** (`placements` in `config.json` — ten stable UI-test placements
covering product-less, product-bearing, video, localized and long-scroll
paywalls) on the dev app's embedded API key (`Keys.CONSTANT_API_KEY` in
`:app`). Ten paywalls is the deliberate sweet spot: at 20+ placements (~25
paywalls) the concurrent WebView load saturates every tier, tier ordering
inverts and run variance roughly triples, drowning the regression signal the
delta gate exists to catch. Note the SDK dedupes placements that resolve to the same paywall, so
the measured paywall count (recorded in the report) can be lower than the
placement count. Product-bearing paywalls
work because the benchmark runs the SDK in test mode
(`-PbenchmarkTestMode=true` → `TestModeBehavior.ALWAYS`): CI emulators have no
Google account, so outside test mode Play Billing is permanently unavailable
and the SDK (by design) never finishes preloading a paywall whose product
fetch fails. A paywall
counts as loaded only once its `PaywallView` reaches
`PaywallLoadingState.Ready` (webview loaded + `onReady` received + templates
accepted).

The placement set is pinned rather than preloading everything because the dev
key has ~47 paywalls: preloading all of them swamps low-tier emulators with
concurrent WebViews, and the metric would shift whenever someone edits
unrelated campaigns on the dashboard. Set `placements` to `[]` to measure
`preloadAllPaywalls()` instead.

Runs automatically on **release PRs** (pull requests targeting `main`), on
**merges to `main` or `develop`**, and on demand via `workflow_dispatch` — see
`.github/workflows/preload-benchmark.yml`.

## How it works

1. The instrumented test `com.example.superapp.benchmark.PaywallPreloadBenchmark`
   (in `:app`) configures the SDK with preloading disabled, waits for config,
   then calls `Superwall.preloadAllPaywalls()` and measures the time until every
   paywall announced by `paywallPreload_start` is `Ready`.
2. CI invokes the test `runsPerEmulator` times per emulator (each
   `connectedAndroidTest` invocation reinstalls the app and clears its data, so
   every run starts cold). Within a run the test performs `iterations`
   iterations (iteration 1 cold, the rest after `Superwall.teardown()` — warm)
   and writes one JSON result per run to
   `/sdcard/Download/superwall-benchmark/`. The compare script averages across
   all runs × iterations.
3. CI runs this on **3 emulators in parallel**, one per device tier:

   | Tier | CPU cores | RAM    |
   |------|-----------|--------|
   | LOW  | 2         | 2048MB |
   | MID  | 3         | 4096MB |
   | HIGH | 4         | 6144MB |

   All tiers are pinned to the **same device image** so results are comparable
   across runs: `pixel_6` profile, API 34, `google_apis_playstore` (Play Store
   enabled), x86_64. CI waits 5 seconds after the emulator boots before running
   the tests. Only cores/RAM differ between tiers.

   Each result also records what the SDK's own `DeviceClassifier` thinks the
   emulator is (`device.sdkClassifiedTier`, shown in the report's Devices
   table). If that drifts from the intended LOW/MID/HIGH label, tune the
   cores/RAM in the workflow matrix.
4. `scripts/benchmark_compare.py` aggregates the three results into
   `.benchmark/results/` + `.benchmark/REPORT.md` and compares each tier's
   `stats.meanMs` against `.benchmark/baseline/<tier>.json`.

## Failure criteria

The workflow **fails** when a tier's mean time-to-all-Ready increases by more
than `deltaPercent` (default **10%**, see `config.json`) over that tier's
baseline. Per-tier overrides are supported via `deltaPercentPerTier`.

The report includes the standard deviation and coefficient of variation across
iterations — use these (across several `workflow_dispatch` runs) to decide
whether 10% is the right delta for each tier and tune `config.json`
accordingly.

## Baselines

Baselines live in `.benchmark/baseline/<tier>.json`. When a tier has no
baseline yet, the run reports the numbers but does not fail.

Baselines **roll forward automatically on every merge** to `main`/`develop`
(the push-triggered run commits its results as the new baselines), so release
PRs always gate against the latest mainline numbers. A manual refresh is also
available: `Actions → Paywall Preload Benchmark → Run workflow` with
**update_baseline = true**.

Note the trade-off of rolling baselines: gradual regressions that stay under
the per-merge delta can creep in unnoticed — watch the absolute numbers in the
committed reports over time.

## Running locally

```bash
# Emulator (Play Store image recommended to match CI) must be running.
# Build with -PbenchmarkTestMode=true (as CI does) to run the SDK in test mode,
# which keeps BILLING_UNAVAILABLE from failing product paywalls on emulators
# without a Play account.
./gradlew :app:clearBenchmarkResults
# Repeat with benchmarkRunIndex=2,3,... for multiple runs (CI does 3).
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.superapp.benchmark.PaywallPreloadBenchmark \
  -Pandroid.testInstrumentationRunnerArguments.benchmarkDeviceTier=MID \
  -Pandroid.testInstrumentationRunnerArguments.benchmarkRunIndex=1 \
  -Pandroid.testInstrumentationRunnerArguments.benchmarkIterations=3
./gradlew :app:pullBenchmarkResults   # -> app/build/outputs/benchmark/

python3 scripts/benchmark_compare.py \
  --results app/build/outputs/benchmark \
  --baseline .benchmark/baseline \
  --config .benchmark/config.json \
  --report /tmp/REPORT.md
```
