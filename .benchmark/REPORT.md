# Paywall Preload Benchmark Report

Time from `preloadAllPaywalls()` until every paywall reaches `PaywallLoadingState.Ready`, per device tier (metric: `medianMs` averaged over 10 runs × 1 iterations per emulator; each run reinstalls the app so its first iteration is cold).

| Tier | Paywalls | Mean | Baseline | Δ | Limit | Cold mean | Warm mean | Median | StdDev | CV | Samples | Status |
|------|----------|------|----------|---|-------|-----------|-----------|--------|--------|----|---------|--------|
| LOW | 10 | 5.68s | 6.34s | -10.3% | +30% | 6.38s | — | 5.68s | 1.69s | 26.5% | 10 (10×1) | 🟢 improved |
| MID | 10 | 6.51s | 8.78s | -25.8% | +15% | 7.72s | — | 6.51s | 3.00s | 38.8% | 10 (10×1) | 🟢 improved |
| HIGH | 10 | 6.76s | 6.72s | +0.7% | +20% | 7.90s | — | 6.76s | 3.62s | 45.8% | 10 (10×1) | ✅ OK |

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
