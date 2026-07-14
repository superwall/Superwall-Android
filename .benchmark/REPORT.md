# Paywall Preload Benchmark Report

Time from `preloadAllPaywalls()` until every paywall reaches `PaywallLoadingState.Ready`, per device tier (metric: `medianMs` averaged over 10 runs × 1 iterations per emulator; each run reinstalls the app so its first iteration is cold).

| Tier | Paywalls | Mean | Baseline | Δ | Limit | Cold mean | Warm mean | Median | StdDev | CV | Samples | Status |
|------|----------|------|----------|---|-------|-----------|-----------|--------|--------|----|---------|--------|
| LOW | 10 | 7.28s | 6.34s | +14.9% | +30% | 8.23s | — | 7.28s | 2.64s | 32.1% | 10 (10×1) | ✅ OK |
| MID | 10 | 5.34s | 8.78s | -39.1% | +15% | 6.25s | — | 5.34s | 2.22s | 35.6% | 10 (10×1) | 🟢 improved |
| HIGH | 10 | 4.38s | 6.72s | -34.8% | +20% | 4.65s | — | 4.38s | 820ms | 17.6% | 10 (10×1) | 🟢 improved |

### Devices

| CI tier | Model | API | CPU cores | SDK-classified tier |
|---------|-------|-----|-----------|---------------------|
| LOW | sdk_gphone64_x86_64 | 34 | 2 | mid |
| MID | sdk_gphone64_x86_64 | 34 | 3 | low |
| HIGH | sdk_gphone64_x86_64 | 34 | 4 | low |

- A tier fails when its mean exceeds the baseline by more than the delta limit.
- The SDK-classified tier column shows what `DeviceClassifier` thinks each emulator is — if it drifts from the CI tier label, adjust the emulator cores/RAM in the workflow matrix.
- Tiers without a baseline are informational; record baselines by running the workflow manually with `update_baseline=true`.
- Use the CV column (variance across iterations) to tune `deltaPercent` in `.benchmark/config.json`.
