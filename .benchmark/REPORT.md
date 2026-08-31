# Paywall Preload Benchmark Report

Time from `preloadAllPaywalls()` until every paywall reaches `PaywallLoadingState.Ready`, per device tier (metric: `medianMs` averaged over 10 runs × 1 iterations per emulator; each run reinstalls the app so its first iteration is cold).

| Tier | Paywalls | Mean | Baseline | Δ | Limit | Cold mean | Warm mean | Median | StdDev | CV | Samples | Status |
|------|----------|------|----------|---|-------|-----------|-----------|--------|--------|----|---------|--------|
| LOW | 10 | 6.55s | 5.95s | +10.2% | +30% | 7.70s | — | 6.55s | 2.53s | 32.8% | 10 (10×1) | ✅ OK |
| MID | 10 | 6.29s | 7.08s | -11.1% | +15% | 7.77s | — | 6.29s | 3.98s | 51.2% | 10 (10×1) | 🟢 improved |
| HIGH | 10 | 5.65s | 6.39s | -11.6% | +20% | 5.73s | — | 5.65s | 408ms | 7.1% | 10 (10×1) | 🟢 improved |

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
