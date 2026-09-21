#!/usr/bin/env python3
"""Build the deterministic spike sample set (S1 / S2 / S5) from the dsh baseline.

Inputs (read only):
  /Users/jeffrey/temp/dsh/iptv-repo/out/validated.m3u                658 validated channels
  /Users/jeffrey/temp/dsh/iptv-repo/out/mac-pipeline/selected.json   per-URL codec metadata

Output:
  spike/app/src/main/assets/spike/channels.json

The output holds REAL stream URLs, so it is git-ignored: it is reproducible from this script and
never committed to the public repo (docs/03 §11 desensitisation rule).

Usage: python3 tools/make-samples.py [--no-precheck]
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor

DSH = "/Users/jeffrey/temp/dsh/iptv-repo/out"
M3U = os.path.join(DSH, "validated.m3u")
SELECTED = os.path.join(DSH, "mac-pipeline", "selected.json")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main",
                   "assets", "spike", "channels.json")

# Live streams from the dsh baseline that are NOT in validated.m3u but are needed to cover HEVC and
# AC3 (validated.m3u contains neither). Reachability is re-checked at generation time.
EXTRA = [
    {"url": "http://ali-xwl.cztv.com/live/channel4k2160p.m3u8", "vcodec": "hevc", "height": 2160,
     "acodec": "aac", "source": "selected.json", "id": "Zhejiang4K", "name": "Zhejiang4K"},
    {"url": "http://jwplay.hebyun.com.cn/live/LPTV001/1500k/tzwj_video.m3u8", "vcodec": "hevc",
     "height": 1080, "acodec": "aac", "source": "selected.json", "id": "HebeiTV",
     "name": "HebeiTV"},
    {"url": "http://120.76.248.139/live/bfgd/4200000246.m3u8", "vcodec": "h264", "height": 1080,
     "acodec": "ac3", "source": "selected.json", "id": "AC3-A", "name": "AC3-A"},
    {"url": "http://120.76.248.139/live/bfgd/4200000473.m3u8", "vcodec": "h264", "height": 1080,
     "acodec": "ac3", "source": "selected.json", "id": "AC3-B", "name": "AC3-B"},
]


def parse_m3u(path: str):
    entries, cur = [], None
    with open(path, encoding="utf-8") as fh:
        for raw in fh:
            line = raw.strip()
            if line.startswith("#EXTINF"):
                attrs, _, name = line.partition(",")
                cur = {"id": "", "name": name.strip(), "group": ""}
                for key in ("tvg-id", "group-title"):
                    marker = key + '="'
                    if marker in attrs:
                        value = attrs.split(marker, 1)[1].split('"', 1)[0]
                        cur["id" if key == "tvg-id" else "group"] = value
            elif line.startswith("http") and cur is not None:
                cur["url"] = line
                cur["source"] = "validated.m3u"
                entries.append(cur)
                cur = None
    return entries


def load_selected():
    with open(SELECTED, encoding="utf-8") as fh:
        data = json.load(fh)
    by_url = {}
    for channel, streams in data.items():
        for s in streams:
            by_url[s["url"]] = {"vcodec": s.get("vcodec"), "height": s.get("height"),
                                "acodec": s.get("acodec"), "score": s.get("score"),
                                "baseline_channel": channel}
    return by_url


def host_code(url: str, timeout: int = 6) -> int:
    try:
        out = subprocess.run(
            ["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "--max-time", str(timeout),
             "-A", "okhttp/4.12", url],
            capture_output=True, text=True, timeout=timeout + 4)
        return int(out.stdout.strip() or 0)
    except Exception:
        return 0


def main():
    precheck = "--no-precheck" not in sys.argv
    selected = load_selected()
    entries = []
    for e in parse_m3u(M3U):
        meta = selected.get(e["url"], {})
        e["vcodec"] = meta.get("vcodec")
        e["height"] = meta.get("height")
        e["acodec"] = meta.get("acodec")
        e["host"] = e["url"].split("//", 1)[-1].split("/", 1)[0].split(":")[0]
        entries.append(e)
    for extra in EXTRA:
        e = dict(extra)
        e["group"] = "extended-sample"
        e["host"] = e["url"].split("//", 1)[-1].split("/", 1)[0].split(":")[0]
        entries.append(e)

    # (label, predicate, how many) — the order defines the S1 sample mix
    cats = [
        ("h264/aac 1080p", lambda e: e["vcodec"] == "h264" and e["acodec"] == "aac" and e["height"] == 1080, 2),
        ("h264/aac 720p", lambda e: e["vcodec"] == "h264" and e["acodec"] == "aac" and e["height"] == 720, 2),
        ("h264/aac 576p", lambda e: e["vcodec"] == "h264" and e["acodec"] == "aac" and e["height"] == 576, 2),
        ("h264/aac 480p", lambda e: e["vcodec"] == "h264" and e["acodec"] == "aac" and e["height"] == 480, 2),
        ("h264/aac low", lambda e: e["vcodec"] == "h264" and e["acodec"] == "aac" and (e["height"] or 999) <= 360, 2),
        ("h264/mp2 1080p", lambda e: e["vcodec"] == "h264" and e["acodec"] == "mp2" and e["height"] == 1080, 2),
        ("h264/mp2 576p", lambda e: e["vcodec"] == "h264" and e["acodec"] == "mp2" and e["height"] == 576, 1),
        ("mpeg2/mp2 576p", lambda e: e["vcodec"] == "mpeg2video" and e["acodec"] == "mp2", 2),
        ("metadata-missing", lambda e: e["vcodec"] is None and e["source"] == "validated.m3u", 3),
        ("hevc 2160p", lambda e: e["vcodec"] == "hevc" and e["height"] == 2160, 1),
        ("hevc 1080p", lambda e: e["vcodec"] == "hevc" and e["height"] == 1080, 1),
    ]

    chosen, used_hosts, used_urls, cat_of = [], set(), set(), {}
    for label, pred, want in cats:
        pool = [e for e in entries if pred(e) and e["url"] not in used_urls]
        pool.sort(key=lambda e: (e["name"], e["url"]))
        picks = [e for e in pool if e["host"] not in used_hosts][: want * 3]
        if len(picks) < want:
            picks += [e for e in pool if e not in picks][: want - len(picks)]
        if precheck and picks:
            with ThreadPoolExecutor(max_workers=8) as pool_ex:
                codes = list(pool_ex.map(lambda e: host_code(e["url"]), picks))
            for e, code in zip(picks, codes):
                e["host_http_code_at_selection"] = code
            live = [e for e, code in zip(picks, codes) if code == 200]
            dead = [e for e, code in zip(picks, codes) if code != 200]
            picks = live[:want] + dead[: max(0, want - len(live[:want]))]
        picks = picks[:want]
        if len(picks) < want:
            print("WARN: category %s produced %d/%d samples" % (label, len(picks), want))
        for e in picks:
            used_hosts.add(e["host"])
            used_urls.add(e["url"])
            cat_of[e["url"]] = label
            chosen.append(e)

    for i, e in enumerate(chosen, 1):
        e["idx"] = i
        e["category"] = cat_of[e["url"]]

    s1 = chosen
    # S2 samples are drawn from the whole candidate pool, not from the S1 mix: AC3/EAC3 are absent
    # from validated.m3u, so they can only come from the EXTRA list.
    def s2_pick(acodec, want):
        pool = [e for e in entries if e["acodec"] == acodec and e["url"] not in used_urls]
        pool.sort(key=lambda e: (e["name"], e["url"]))
        picks = [e for e in pool if e["host"] not in used_hosts][:want]
        picks += [e for e in pool if e not in picks][: want - len(picks)]
        for e in picks:
            used_hosts.add(e["host"])
            e.setdefault("idx", 100 + entries.index(e))
            e.setdefault("category", "s2/" + acodec)
        return picks

    s2 = {
        "aac": [e for e in chosen if e["acodec"] == "aac" and e["source"] == "validated.m3u"][:3],
        "mp2": [e for e in chosen if e["acodec"] == "mp2"][:3],
        "ac3": s2_pick("ac3", 2),
        "eac3": s2_pick("eac3", 2),
    }
    s5 = [e for e in chosen if e["category"].startswith(("h264/aac", "h264/mp2", "metadata"))][:6]

    doc = {
        "generated_by": "spike/tools/make-samples.py",
        "inputs": {"validated_m3u": M3U, "selected_json": SELECTED},
        "note": "validated.m3u has 658 entries; codec metadata comes from joining selected.json by "
                "URL (330 entries have no join and are reported as metadata-missing)",
        "s1": s1, "s2": s2, "s5": s5,
    }
    out_path = os.path.abspath(OUT)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, ensure_ascii=False, indent=1)
    print("wrote %s" % out_path)
    for e in s1:
        print("  S1 #%02d %-16s %-22s host_code=%s %s" %
              (e["idx"], e["category"], e["name"][:20],
               e.get("host_http_code_at_selection", "-"), e["url"][:60]))
    print("S2 aac=%d mp2=%d ac3=%d eac3=%d" % (len(s2["aac"]), len(s2["mp2"]), len(s2["ac3"]), 0))
    print("S5 = %d channels" % len(s5))


if __name__ == "__main__":
    main()
