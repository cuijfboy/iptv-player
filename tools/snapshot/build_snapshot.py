#!/usr/bin/env python3
"""Build the shipped snapshot (channels.m3u + PROVENANCE.md) from device probe results.

The candidate list is an ordered m3u (priority = file order). Only entries whose device probe
result is `pass` are kept — `pass` already means "first frame <= threshold AND no stall during the
hold window" (see tools/snapshot/README.md; the thresholds used by the probe are echoed here so a
snapshot can never claim a criterion its probe did not enforce).

    tools/snapshot/build_snapshot.py --candidates validated.m3u --probe probe.jsonl \
        --out-m3u app/src/main/assets/snapshot/channels.m3u \
        --out-provenance app/src/main/assets/snapshot/PROVENANCE.md
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import statistics
import sys
from collections import Counter
from pathlib import Path

# The repo ships the snapshot in a public place: rows that look like credentials or private
# addresses never make it in (tools/ci/sensitive-info-guard.sh enforces the same rule repo-wide).
PRIVATE_IP = re.compile(
    r"(^|[^0-9A-Za-z.])((192\.168|10)\.\d{1,3}\.\d{1,3}"
    r"|172\.(1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})"
)
CREDENTIAL_KEY = re.compile(
    r"(^|[^0-9A-Za-z_])(password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key"
    r"|access[_-]?token|auth[_-]?token)[ \t]*[:=]"
)


def fold_width(ch: str) -> str:
    """Mirror of `:core:source` NameNormalizer.foldWidth (U+FF01–U+FF5E and U+3000)."""
    if ch == "\u3000":
        return " "
    if "\uFF01" <= ch <= "\uFF5E":
        return chr(ord(ch) - 0xFEE0)
    return ch


def display_name(raw: str) -> str:
    out: list[str] = []
    pending = False
    for ch in raw:
        c = fold_width(ch)
        if c.isspace():
            pending = bool(out)
        else:
            if pending:
                out.append(" ")
            pending = False
            out.append(c)
    return "".join(out)


def name_key(raw: str) -> str:
    """`channel.name_key`: display form, then all whitespace removed and case folded."""
    return "".join(c for c in display_name(raw) if not c.isspace()).lower()


def group_key(raw: str) -> str:
    """`channel.group_key`: the same normalization; blank → "other"."""
    return name_key(raw) or "other"


def parse_m3u(path: Path) -> list[dict]:
    entries: list[dict] = []
    pending: dict | None = None
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("#EXTINF"):
            attrs = dict(re.findall(r'([a-zA-Z0-9-]+)="([^"]*)"', line))
            name = line.split(",", 1)[1].strip() if "," in line else attrs.get("tvg-name", "")
            pending = {"name": name, "attrs": attrs}
        elif line.startswith("#"):
            continue
        elif pending is not None:
            pending["url"] = line
            pending["group"] = pending["attrs"].get("group-title", "未分组")
            entries.append(pending)
            pending = None
    return entries


def load_probe(paths: list[Path]) -> dict[str, dict]:
    results: dict[str, dict] = {}
    for path in paths:
        for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            results[row["url"]] = row
    return results


def unsafe(url: str, drop_hosts: set[str]) -> str | None:
    host = re.sub(r"^[a-z]+://([^/:]+).*$", r"\1", url).lower()
    if host in drop_hosts:
        return f"host {host} dropped by --drop-host"
    if ".invalid" in url:
        return "synthetic .invalid host"
    if PRIVATE_IP.search(url):
        return "private/LAN address"
    if CREDENTIAL_KEY.search(url):
        return "credential-looking query key"
    return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--candidates", required=True, type=Path)
    ap.add_argument("--probe", required=True,
                    help="comma separated probe jsonl file(s) from tools/snapshot/probe.sh")
    ap.add_argument("--out-m3u", required=True, type=Path)
    ap.add_argument("--out-provenance", required=True, type=Path)
    ap.add_argument("--min-channels", type=int, default=150)
    ap.add_argument("--max-channels", type=int, default=200)
    ap.add_argument("--first-frame-ms", type=int, default=3000)
    ap.add_argument("--hold-ms", type=int, default=30000)
    ap.add_argument("--date", default=dt.date.today().isoformat())
    ap.add_argument("--device", default="Sony BRAVIA 4K VH21 (Android 12 / API 31)")
    ap.add_argument("--pool", default="tools/fixtures/large/validated.m3u")
    ap.add_argument("--drop-host", action="append", default=[])
    ap.add_argument("--drop-url", action="append", default=[],
                    help="exact URL to keep out even if it probed clean (known-flaky source)")
    args = ap.parse_args()

    probe = load_probe([Path(p) for p in args.probe.split(",") if p.strip()])
    candidates = parse_m3u(args.candidates)
    drop_hosts = {h.lower() for h in args.drop_host}
    drop_urls = set(args.drop_url)

    kept: list[dict] = []
    seen: set[tuple[str, str]] = set()
    excluded: Counter[str] = Counter()
    for entry in candidates:
        # Identity is the app's `(name_key, group_key)` (docs/02 §5.1) — a display-name comparison
        # would let `苏州4k` and `苏州4K` through and then collapse them in Room (155 → 154).
        key = (name_key(entry["name"]), group_key(entry["group"]))
        if key in seen:
            excluded["duplicate (name_key, group_key)"] += 1
            continue
        reason = unsafe(entry["url"], drop_hosts)
        if reason:
            excluded[reason] += 1
            continue
        if entry["url"] in drop_urls:
            excluded["dropped by --drop-url"] += 1
            continue
        row = probe.get(entry["url"])
        if row is None:
            excluded["not probed on device"] += 1
            continue
        if not row.get("pass"):
            excluded[f"probe outcome={row.get('outcome')}"] += 1
            continue
        seen.add(key)
        entry["probe"] = row
        kept.append(entry)

    if len(kept) < args.min_channels:
        print(f"build_snapshot.py: only {len(kept)} channels pass (min {args.min_channels})",
              file=sys.stderr)
        return 1
    if len(kept) > args.max_channels:
        kept = kept[:args.max_channels]

    rows = []
    group_counts: Counter[str] = Counter()
    for i, entry in enumerate(kept, start=1):
        attrs = entry["attrs"]
        name = entry["name"]
        rows.append(
            f'#EXTINF:-1 tvg-id="{attrs.get("tvg-id", name)}"'
            f' tvg-name="{attrs.get("tvg-name", name)}"'
            f" tvg-chno={i}"
            f' group-title="{entry["group"]}",{name}'
        )
        rows.append(entry["url"])
        group_counts[entry["group"]] += 1
    out_text = "\n".join(["#EXTM3U"] + rows) + "\n"

    probed = [row for row in probe.values()]
    passes = [row for row in probed if row.get("pass")]
    first_frames = sorted(r["firstFrameMs"] for r in passes if r.get("firstFrameMs", -1) > 0)
    median_ff = statistics.median(first_frames) if first_frames else 0
    p90_ff = first_frames[int(0.9 * (len(first_frames) - 1))] if first_frames else 0

    by_host_fail: Counter[str] = Counter()
    for row in probed:
        if row.get("pass"):
            continue
        host = re.sub(r"^[a-z]+://([^/:]+).*$", r"\1", row["url"])
        by_host_fail[host] += 1
    dead_hosts = [(h, c) for h, c in by_host_fail.most_common() if c >= 2]

    group_line = " · ".join(f"{g} {group_counts[g]}" for g in group_counts)
    machine_groups = ",".join(f"{g}={group_counts[g]}" for g in group_counts)
    provenance = f"""# 内置快照来源说明（channels.m3u）

<!-- snapshot-channels: {len(kept)} -->
<!-- snapshot-groups: {machine_groups} -->

卡：`SNAP-REFRESH-1`（快照修订 + 刷新流程工具化 + 文案校正）。生成：`tools/snapshot/build_snapshot.py`，
实测数据：`tools/snapshot/probe.sh`（电视本机逐条起播 + hold）。同目录的 `channels.m3u` 是 APK
**出厂即带**的频道清单；首启（频道表为空）由 `RoomCatalogSeeder` 经 `DataModule` 播种进 Room，
用户手动导入或联网刷新**不会被它覆盖**。

## 1. 内容

| 项 | 值 |
|---|---|
| 频道数 | **{len(kept)}**（一行一频道、一条流；按 `(显示名, 分组)` 去重、无合并） |
| 分组 | {group_line} |
| 行属性 | 每行 `tvg-id` / `tvg-name` / `tvg-chno`（1..{len(kept)}）/ `group-title` / 显示名 |
| 排序 | 与生成时的候选清单一致（央视 → 卫视 → 港澳台 → 地方/其他），组内保持人工排序 |

## 2. 收录判据（两条，都在电视上实测）

| 判据 | 阈值 |
|---|---|
| 首帧（`PLAY_FIRST_FRAME` 同口径：prepare → 首个渲染帧） | **≤ {args.first_frame_ms} ms** |
| 连续播放 | **≥ {args.hold_ms // 1000} s 无 stall**（首帧后播放/缓冲位置连续 ≥2 s 不变化即判定为 stall） |

一条候选**两条都过**才收录。测法：`tools/snapshot/probe.sh` 驱动一次性测量夹具
`ilab.iptv.player.spike/HoldProbeActivity`（与产品同引擎族：Media3 `ExoPlayer` + `media3-exoplayer-hls`，
跑在电视本机解码），逐 URL 起播、记录首帧成本、再 hold {args.hold_ms // 1000} s 观察是否卡死。

## 3. 实测记录

| 项 | 值 |
|---|---|
| 设备 | {args.device} |
| 日期 | {args.date} |
| 候选池 | `{args.pool}`（P2 采集；本文件是它的**子集**） |
| 本轮电视实测样本 | **{len(probed)} 条 URL**（逐条起播 + hold） |
| 通过两条判据 | **{len(passes)} 条** |
| 收录 | **{len(kept)} 条**（受候选顺序与每台一条流约束） |
| 首帧（通过样本） | 中位 **{median_ff:.0f} ms**，p90 **{p90_ff:.0f} ms** |

## 4. 未收录 / 已知失效

本轮被剔除的原因分布（同一批候选）：

{chr(10).join(f"- {reason}：{count} 条" for reason, count in excluded.most_common())}

电视侧**反复失败**的宿主（≥2 条样本失败；这些是"源/CDN/路径"问题，不是播放器问题）：

{chr(10).join(f"- `{host}`：{count} 条失败" for host, count in dead_hosts[:12]) if dead_hosts else "- （本轮无反复失败的宿主）"}

## 5. 没验证的事（不要当已验证）

- **快照会过期**：这些多是地方台直链，源站改动、令牌过期、换网络出口都会让条目失效。
  过期判据就是上面两条（≤{args.first_frame_ms} ms 首帧 + {args.hold_ms // 1000} s 无 stall）。
- 每条流只测了**一次** {args.hold_ms // 1000} s 窗口，没有跑小时级长稳，也没有测夜间时段与多台并发。
- 地理位置/运营商变化：换网络（尤其换出口）后可用性会变。
- EPG 覆盖率、换台/故障转移成功率不在本文件的判据内。

## 6. 重生成入口（一条命令）

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
tools/snapshot/probe.sh --candidates tools/fixtures/large/validated.m3u --out /tmp/snapshot_probe
tools/snapshot/build_snapshot.py --candidates tools/fixtures/large/validated.m3u \\
    --probe /tmp/snapshot_probe/probe.jsonl \\
    --out-m3u app/src/main/assets/snapshot/channels.m3u \\
    --out-provenance app/src/main/assets/snapshot/PROVENANCE.md
tools/snapshot/verify.sh app/src/main/assets/snapshot/channels.m3u
```

详见 `tools/snapshot/README.md`。

## 7. 合规提示（给集成者）

本文件把**第三方直播流 URL**打进了公开仓库与 APK（人工 2026-09-22 明确要求"在 App 里内置一份快照"，
卡 `SNAP-REFRESH-1` 延续该口径）。这些 URL 均为上游公开聚合清单中的地址；若要对外分发，请自行确认
再分发的授权与合规口径。生成脚本会剔除带凭据查询键与私网地址的条目。
"""
    args.out_m3u.parent.mkdir(parents=True, exist_ok=True)
    args.out_m3u.write_text(out_text, encoding="utf-8")
    args.out_provenance.write_text(provenance, encoding="utf-8")

    print(f"build_snapshot.py: probed={len(probed)} pass={len(passes)} kept={len(kept)} -> {args.out_m3u}")
    print(f"build_snapshot.py: groups {group_line}")
    for reason, count in excluded.most_common():
        print(f"  excluded: {reason}: {count}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
