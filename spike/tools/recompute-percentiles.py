#!/usr/bin/env python3
"""Recompute every percentile the spike report quotes, straight from the raw artefacts.

One rule, used everywhere in 07-Spike报告.md:

    nearest-rank — for a sorted sample of N values, the p-th percentile is the
    value at 1-based index ceil(p/100 * N); no interpolation, so every reported
    percentile is a value that was actually observed.

    usage: python3 spike/tools/recompute-percentiles.py [evidence-dir]
           (default: $SPIKE_EVIDENCE, else ~/iptv-spike-evidence)

The S3 fixture (EpgGridSpikeActivity.pct) already uses the same rule, so its own
envelope / frame-time percentiles need no correction.
"""
import json
import math
import os
import sys


def nearest_rank(values, pct):
    """p-th percentile (nearest-rank); None for an empty sample."""
    if not values:
        return None
    ordered = sorted(values)
    k = max(1, math.ceil(pct / 100.0 * len(ordered)))
    return ordered[k - 1]


def costs(path):
    with open(path, encoding="utf-8") as fh:
        return [e["costMs"] for e in json.load(fh)["entries"]]


def report(evidence_dir):
    a = os.path.join(evidence_dir, "artifacts")
    runs = [
        ("S1 首次起播（20 条）", "s1_s1a.json",
         "docs/05-过程记录/07-Spike报告.md §2"),
        ("S5 换台·复用单实例（30 次）", "s5_s5reuse.json",
         "docs/05-过程记录/07-Spike报告.md §5"),
        ("S5 换台·重建实例（10 次）", "s5_s5recreate.json",
         "docs/05-过程记录/07-Spike报告.md §5"),
    ]
    for label, name, where in runs:
        path = os.path.join(a, name)
        values = costs(path)
        print(f"{label}  [{name}] -> {where}")
        print(f"  n={len(values)} 平均={sum(values)/len(values):.1f} ms")
        for pct in (50, 90, 95):
            print(f"  p{pct}={nearest_rank(values, pct)} ms", end="")
        print(f"  max={max(values)} ms")
        print(f"  排序后={sorted(values)}")
    # S3 stores the percentiles the fixture computed (same nearest-rank rule).
    for name in ("s3_s3virtual.json", "s3_s3naive.json"):
        with open(os.path.join(a, name), encoding="utf-8") as fh:
            s3 = json.load(fh)
        print(f"S3 {s3['mode']}  [{name}]   帧间隔 p50={s3['frameIntervalP50Ms']} "
              f"p90={s3['frameIntervalP90Ms']} p95={s3['frameIntervalP95Ms']} "
              f"p99={s3['frameIntervalP99Ms']} max={s3['frameIntervalMaxMs']} ms "
              f"（夹具用同一 nearest-rank 规则算出）")


if __name__ == "__main__":
    default = os.environ.get("SPIKE_EVIDENCE",
                             os.path.expanduser("~/iptv-spike-evidence"))
    report(sys.argv[1] if len(sys.argv) > 1 else default)
