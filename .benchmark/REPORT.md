# Paywall Preload Benchmark Report

Time from `preloadAllPaywalls()` until every paywall reaches `PaywallLoadingState.Ready`, per device tier (metric: `medianMs` averaged over 10 runs × 1 iterations per emulator; each run reinstalls the app so its first iteration is cold).

| Tier | Paywalls | Mean | Baseline | Δ | Limit | Cold mean | Warm mean | Median | StdDev | CV | Samples | Status |
|------|----------|------|----------|---|-------|-----------|-----------|--------|--------|----|---------|--------|
| LOW | 10 | 5.96s | 5.95s | +0.2% | +30% | 6.75s | — | 5.96s | 1.59s | 23.6% | 10 (10×1) | ✅ OK |
| MID | 10 | 5.50s | 7.08s | -22.3% | +15% | 5.71s | — | 5.50s | 603ms | 10.6% | 10 (10×1) | 🟢 improved |
| HIGH | 10 | 3.25s | 6.39s | -49.2% | +20% | 3.28s | — | 3.25s | 395ms | 12.0% | 10 (10×1) | 🟢 improved |

### Devices

| CI tier | Model | API | CPU cores | SDK-classified tier |
|---------|-------|-----|-----------|---------------------|
| LOW | sdk_gphone64_x86_64 | 34 | 2 | mid |
| MID | sdk_gphone64_x86_64 | 34 | 3 | mid |
| HIGH | sdk_gphone64_x86_64 | 34 | 4 | mid |

- A tier fails when its mean exceeds the baseline by more than the delta limit.
- The SDK-classified tier column shows what `DeviceClassifier` thinks each emulator is — if it drifts from the CI tier label, adjust the emulator cores/RAM in the workflow matrix.
- Tiers without a baseline are informational; record baselines by running the workflow manually with `update_baseline=true`.
- Use the CV column (variance across iterations) to tune `deltaPercent` in `.benchmark/config.json`.
