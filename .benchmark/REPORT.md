# Paywall Preload Benchmark Report

Time from `preloadAllPaywalls()` until every paywall reaches `PaywallLoadingState.Ready`, per device tier (metric: `meanMs` averaged over 4 runs × 1 iterations per emulator; each run reinstalls the app so its first iteration is cold).

| Tier | Paywalls | Mean | Baseline | Δ | Limit | Cold mean | Warm mean | Median | StdDev | CV | Samples | Status |
|------|----------|------|----------|---|-------|-----------|-----------|--------|--------|----|---------|--------|
| LOW | 2 | 4.52s | — | — | +10% | 4.52s | — | 4.53s | 630ms | 13.9% | 4 (4×1) | ⚪ no baseline |
| MID | 2 | 2.29s | — | — | +10% | 2.29s | — | 2.12s | 492ms | 21.5% | 4 (4×1) | ⚪ no baseline |
| HIGH | 2 | 3.77s | — | — | +10% | 3.77s | — | 3.78s | 240ms | 6.4% | 4 (4×1) | ⚪ no baseline |

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
