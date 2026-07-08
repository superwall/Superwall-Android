#!/usr/bin/env python3
"""Aggregate, report and gate paywall preload benchmark results.

Reads the result files produced by the PaywallPreloadBenchmark instrumented
test (preload-benchmark-<tier>-run<N>.json, one file per
connectedAndroidTest invocation), merges each tier's runs into stats
computed across ALL iterations of all runs, compares the merged mean
time-to-all-Ready against the committed baseline, writes a markdown
report, and exits non-zero when any tier regressed beyond the configured
delta.

Exit codes:
  0 - OK (improvements, within delta, or no baseline yet)
  3 - at least one tier regressed beyond the delta limit
  4 - no result files found

Usage:
  benchmark_compare.py --results DIR --baseline DIR --config FILE --report FILE
                       [--normalized-out DIR] [--update-baseline]
"""

import argparse
import glob
import json
import os
import sys

TIERS = ["low", "mid", "high"]


def load_json(path):
    with open(path) as f:
        return json.load(f)


def find_results(results_dir):
    """Map tier -> list of run result dicts for every preload-benchmark-*.json found."""
    results = {}
    pattern = os.path.join(results_dir, "**", "preload-benchmark-*.json")
    for path in sorted(glob.glob(pattern, recursive=True)):
        data = load_json(path)
        tier = str(data.get("tier", "unknown")).lower()
        results.setdefault(tier, []).append(data)
    return results


def mean(values):
    return sum(values) / len(values) if values else None


def merge_runs(tier, runs):
    """Merge N run files (one per connectedAndroidTest invocation) into a single
    result whose stats are computed across ALL iterations of all runs."""
    samples = []
    for run in sorted(runs, key=lambda r: r.get("runIndex", 0)):
        for it in run.get("iterations", []):
            samples.append({**it, "run": run.get("runIndex", 1)})

    all_ready = [s["allReadyMs"] for s in samples]
    cold = [s["allReadyMs"] for s in samples if s.get("cold")]
    warm = [s["allReadyMs"] for s in samples if not s.get("cold")]
    m = mean(all_ready)
    if len(all_ready) > 1:
        std = (sum((v - m) ** 2 for v in all_ready) / (len(all_ready) - 1)) ** 0.5
    else:
        std = 0.0
    ordered = sorted(all_ready)
    mid = len(ordered) // 2
    median = (
        (ordered[mid - 1] + ordered[mid]) / 2.0 if len(ordered) % 2 == 0 else float(ordered[mid])
    )

    return {
        "schemaVersion": runs[0].get("schemaVersion"),
        "benchmark": runs[0].get("benchmark"),
        "tier": tier.upper(),
        "device": runs[0].get("device"),
        "runCount": len(runs),
        "iterationsPerRun": len(runs[0].get("iterations", [])),
        "samples": samples,
        "stats": {
            "meanMs": m,
            "medianMs": median,
            "minMs": min(all_ready),
            "maxMs": max(all_ready),
            "stdDevMs": std,
            "coefficientOfVariationPct": (std / m * 100.0) if m else 0.0,
            "coldMeanMs": mean(cold),
            "warmMeanMs": mean(warm),
            "sampleCount": len(samples),
        },
    }


def delta_for_tier(config, tier):
    per_tier = (config.get("deltaPercentPerTier") or {}).get(tier)
    if per_tier is not None:
        return float(per_tier)
    return float(config.get("deltaPercent", 10.0))


def fmt_ms(value):
    if value is None:
        return "—"
    return f"{value / 1000.0:.2f}s" if value >= 1000 else f"{value:.0f}ms"


def compare_tier(tier, result, baseline, config):
    """Return a per-tier summary row dict with status/deltas filled in."""
    metric = config.get("metric", "meanMs")
    stats = result["stats"]
    current = float(stats[metric])
    delta_limit = delta_for_tier(config, tier)

    device = result.get("device") or {}
    row = {
        "tier": tier.upper(),
        "device": device,
        "sdkClassifiedTier": device.get("sdkClassifiedTier"),
        "paywallCount": (result.get("samples") or [{}])[0].get("paywallCount"),
        "current": current,
        "coldMeanMs": stats.get("coldMeanMs"),
        "warmMeanMs": stats.get("warmMeanMs"),
        "medianMs": stats.get("medianMs"),
        "stdDevMs": stats.get("stdDevMs"),
        "cvPct": stats.get("coefficientOfVariationPct"),
        "runCount": result.get("runCount"),
        "iterationsPerRun": result.get("iterationsPerRun"),
        "sampleCount": stats.get("sampleCount"),
        "deltaLimitPct": delta_limit,
        "baseline": None,
        "deltaPct": None,
    }

    if baseline is None:
        row["status"] = "NO BASELINE"
        row["failed"] = False
        return row

    base = float(baseline["stats"][metric])
    row["baseline"] = base
    delta_pct = (current - base) / base * 100.0 if base > 0 else 0.0
    row["deltaPct"] = delta_pct
    if delta_pct > delta_limit:
        row["status"] = "REGRESSION"
        row["failed"] = True
    elif delta_pct < 0:
        row["status"] = "IMPROVED"
        row["failed"] = False
    else:
        row["status"] = "OK"
        row["failed"] = False
    return row


def write_report(path, rows, missing_tiers, config):
    lines = [
        "# Paywall Preload Benchmark Report",
        "",
        "Time from `preloadAllPaywalls()` until every paywall reaches "
        "`PaywallLoadingState.Ready`, per device tier "
        f"(metric: `{config.get('metric', 'meanMs')}` averaged over "
        f"{config.get('runsPerEmulator')} runs × {config.get('iterations')} iterations "
        "per emulator; each run reinstalls the app so its first iteration is cold).",
        "",
        "| Tier | Paywalls | Mean | Baseline | Δ | Limit | Cold mean | Warm mean | Median | StdDev | CV | Samples | Status |",
        "|------|----------|------|----------|---|-------|-----------|-----------|--------|--------|----|---------|--------|",
    ]
    for row in rows:
        delta = "—" if row["deltaPct"] is None else f"{row['deltaPct']:+.1f}%"
        cv = "—" if row["cvPct"] is None else f"{row['cvPct']:.1f}%"
        samples = f"{row['sampleCount']} ({row['runCount']}×{row['iterationsPerRun']})"
        status_icon = {
            "OK": "✅ OK",
            "IMPROVED": "🟢 improved",
            "REGRESSION": "❌ regression",
            "NO BASELINE": "⚪ no baseline",
        }[row["status"]]
        lines.append(
            "| {tier} | {count} | {cur} | {base} | {delta} | +{limit:.0f}% | {cold} | {warm} | {median} | {std} | {cv} | {samples} | {status} |".format(
                tier=row["tier"],
                count=row["paywallCount"] if row["paywallCount"] is not None else "—",
                cur=fmt_ms(row["current"]),
                base=fmt_ms(row["baseline"]),
                delta=delta,
                limit=row["deltaLimitPct"],
                cold=fmt_ms(row["coldMeanMs"]),
                warm=fmt_ms(row["warmMeanMs"]),
                median=fmt_ms(row["medianMs"]),
                std=fmt_ms(row["stdDevMs"]),
                cv=cv,
                samples=samples,
                status=status_icon,
            )
        )
    for tier in missing_tiers:
        lines.append(f"| {tier.upper()} | — | — | — | — | — | — | — | — | — | — | — | ⚠️ missing results |")

    lines += [
        "",
        "### Devices",
        "",
        "| CI tier | Model | API | CPU cores | SDK-classified tier |",
        "|---------|-------|-----|-----------|---------------------|",
    ]
    for row in rows:
        d = row["device"]
        sdk_tier = row["sdkClassifiedTier"] or "—"
        lines.append(
            f"| {row['tier']} | {d.get('model', '—')} | {d.get('sdkInt', '—')} | "
            f"{d.get('cpuCores', '—')} | {sdk_tier} |"
        )

    lines += [
        "",
        "- A tier fails when its mean exceeds the baseline by more than the delta limit.",
        "- The SDK-classified tier column shows what `DeviceClassifier` thinks each "
        "emulator is — if it drifts from the CI tier label, adjust the emulator "
        "cores/RAM in the workflow matrix.",
        "- Tiers without a baseline are informational; record baselines by running the "
        "workflow manually with `update_baseline=true`.",
        "- Use the CV column (variance across iterations) to tune `deltaPercent` in "
        "`.benchmark/config.json`.",
        "",
    ]
    with open(path, "w") as f:
        f.write("\n".join(lines))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results", required=True, help="Directory containing preload-benchmark-*.json (searched recursively)")
    parser.add_argument("--baseline", required=True, help="Directory with per-tier baseline JSON files")
    parser.add_argument("--config", required=True, help="Path to .benchmark/config.json")
    parser.add_argument("--report", required=True, help="Markdown report output path")
    parser.add_argument("--normalized-out", help="Directory to copy normalized per-tier results into")
    parser.add_argument("--update-baseline", action="store_true", help="Write current results as the new baselines")
    args = parser.parse_args()

    config = load_json(args.config)
    results = find_results(args.results)
    if not results:
        print(f"ERROR: no preload-benchmark-*.json files found under {args.results}", file=sys.stderr)
        return 4

    rows, failed = [], False
    for tier in TIERS:
        runs = results.get(tier)
        if not runs:
            continue
        result = merge_runs(tier, runs)
        baseline_path = os.path.join(args.baseline, f"{tier}.json")
        baseline = load_json(baseline_path) if os.path.exists(baseline_path) else None
        row = compare_tier(tier, result, baseline, config)
        rows.append(row)
        failed = failed or (row["failed"] and not args.update_baseline)

        if args.normalized_out:
            os.makedirs(args.normalized_out, exist_ok=True)
            with open(os.path.join(args.normalized_out, f"preload-benchmark-{tier}.json"), "w") as f:
                json.dump(result, f, indent=2, sort_keys=True)
                f.write("\n")

        if args.update_baseline:
            os.makedirs(args.baseline, exist_ok=True)
            with open(baseline_path, "w") as f:
                json.dump(result, f, indent=2, sort_keys=True)
                f.write("\n")
            print(f"Updated baseline for {tier}: {baseline_path}")

    missing = [t for t in TIERS if t not in results]
    write_report(args.report, rows, missing, config)
    print(f"Report written to {args.report}")
    for row in rows:
        print(f"  {row['tier']}: {row['status']} (mean={fmt_ms(row['current'])}, baseline={fmt_ms(row['baseline'])})")
    if missing:
        print(f"  WARNING: missing results for tiers: {', '.join(missing)}")

    return 3 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
