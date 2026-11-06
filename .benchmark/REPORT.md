# Paywall Preload Benchmark Report

Time from `preloadAllPaywalls()` until every paywall reaches `PaywallLoadingState.Ready`, per device tier (metric: `medianMs` averaged over 10 runs × 1 iterations per emulator; each run reinstalls the app so its first iteration is cold).

| Tier | Paywalls | Mean | Baseline | Δ | Limit | Cold mean | Warm mean | Median | StdDev | CV | Samples | Status |
|------|----------|------|----------|---|-------|-----------|-----------|--------|--------|----|---------|--------|
| LOW | 10 | 3.41s | 5.95s | -42.7% | +30% | 3.42s | — | 3.41s | 179ms | 5.2% | 3 (3×1) | 🟢 improved |
| MID | 10 | 4.92s | 7.08s | -30.5% | +15% | 5.14s | — | 4.92s | 967ms | 18.8% | 10 (10×1) | 🟢 improved |
| HIGH | 10 | 3.95s | 6.39s | -38.2% | +20% | 3.95s | — | 3.95s | 326ms | 8.3% | 10 (10×1) | 🟢 improved |

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
