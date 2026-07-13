# Paywall Preload Benchmark Report

Time from `preloadAllPaywalls()` until every paywall reaches `PaywallLoadingState.Ready`, per device tier (metric: `medianMs` averaged over 10 runs × 1 iterations per emulator; each run reinstalls the app so its first iteration is cold).

| Tier | Paywalls | Mean | Baseline | Δ | Limit | Cold mean | Warm mean | Median | StdDev | CV | Samples | Status |
|------|----------|------|----------|---|-------|-----------|-----------|--------|--------|----|---------|--------|
| LOW | 10 | 4.33s | 6.34s | -31.7% | +30% | 4.40s | — | 4.33s | 497ms | 11.3% | 10 (10×1) | 🟢 improved |
| MID | 10 | 5.08s | 8.78s | -42.1% | +15% | 5.26s | — | 5.08s | 732ms | 13.9% | 10 (10×1) | 🟢 improved |
| HIGH | 10 | 10.89s | 6.72s | +62.1% | +20% | 11.86s | — | 10.89s | 5.61s | 47.3% | 10 (10×1) | ❌ regression |

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
