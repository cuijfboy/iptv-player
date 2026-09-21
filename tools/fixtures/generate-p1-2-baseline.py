#!/usr/bin/env python3
"""Generate the P1-2 channel-list fixture (a 658-channel M3U shaped like the real baseline).

Why a *generated* list instead of the real one: `tools/fixtures/README.md` deliberately does not
vendor `/Users/jeffrey/temp/dsh/iptv-repo/out/validated.m3u` (658 channels, ~150 KB of third-party
hosts and token URLs, in a public repo). This script reproduces the parts P1-2 needs — the row
count, the group mix, channels with and without `tvg-chno`/`tvg-logo`, and a few same-name rows per
group that must merge into one channel — with synthetic hosts and no tokens.

Shape of the real baseline (measured 2026-09-21 from the un-vendored file):
  670 `#EXTINF` rows / 658 channels after `(name_key, group_key)` merge, groups:
  央视 80, 卫视 69, 港澳台 7, 地方/其他 502, 其他频道 2; no `tvg-chno`, no `tvg-logo`.
This fixture keeps that mix and adds `tvg-chno` on a slice (the D12 middle tier) plus
`tvg-logo` on a slice (the台标 placeholder path), which the real baseline never exercises.

Usage: python3 tools/fixtures/generate-p1-2-baseline.py > core/data/src/main/assets/playlists/p1-2-baseline.m3u
Deterministic: no RNG, no timestamps, same bytes on every run.
"""

import sys

CCTV = [
    "CCTV1", "CCTV2", "CCTV3", "CCTV4", "CCTV5", "CCTV5+", "CCTV6", "CCTV7", "CCTV8", "CCTV9",
    "CCTV10", "CCTV11", "CCTV12", "CCTV13", "CCTV14", "CCTV15", "CCTV16", "CCTV17",
    "CCTV-1综合", "CCTV-2财经", "CCTV-3综艺", "CCTV-4 中文国际", "CCTV-5体育", "CCTV-5+体育赛事",
    "CCTV-6电影", "CCTV-7国防军事", "CCTV-8电视剧", "CCTV-9纪录", "CCTV-10科教", "CCTV-11戏曲",
    "CCTV-12社会与法", "CCTV-13新闻", "CCTV-14少儿", "CCTV-15音乐", "CCTV-16奥林匹克", "CCTV-17农业农村",
    "CGTN", "CGTN法语", "CGTN西语", "CGTN阿语", "CGTN俄语", "CGTN纪录", "CCTV4K", "CCTV8K",
    "央视新闻", "央视财经", "央视综艺", "央视体育", "央视电影", "央视军事", "央视电视剧", "央视纪录",
    "央视科教", "央视戏曲", "央视社会与法", "央视少儿", "央视音乐", "央视农业", "央视奥运",
    "CCTV1 高清", "CCTV2 高清", "CCTV3 高清", "CCTV5 高清", "CCTV6 高清", "CCTV8 高清",
    "CCTV9 高清", "CCTV10 高清", "CCTV12 高清", "CCTV13 高清", "CCTV14 高清", "CCTV15 高清",
    "CCTV1 标清", "CCTV2 标清", "CCTV3 标清", "CCTV4 标清", "CCTV5 标清", "CCTV6 标清",
    "CCTV7 标清", "CCTV8 标清", "CCTV9 标清", "CCTV10 标清",
]

SATELLITE = [
    "北京卫视", "湖南卫视", "浙江卫视", "江苏卫视", "东方卫视", "安徽卫视", "山东卫视", "广东卫视",
    "深圳卫视", "湖北卫视", "四川卫视", "河南卫视", "辽宁卫视", "天津卫视", "黑龙江卫视", "吉林卫视",
    "河北卫视", "山西卫视", "陕西卫视", "甘肃卫视", "青海卫视", "宁夏卫视", "新疆卫视", "西藏卫视",
    "云南卫视", "贵州卫视", "广西卫视", "福建东南卫视", "江西卫视", "内蒙古卫视", "海南卫视", "重庆卫视",
    "北京卫视 高清", "湖南卫视 高清", "浙江卫视 高清", "江苏卫视 高清", "东方卫视 高清", "安徽卫视 高清",
    "山东卫视 高清", "广东卫视 高清", "深圳卫视 高清", "湖北卫视 高清", "四川卫视 高清", "河南卫视 高清",
    "辽宁卫视 高清", "天津卫视 高清", "黑龙江卫视 高清", "吉林卫视 高清", "河北卫视 高清", "山西卫视 高清",
    "陕西卫视 高清", "甘肃卫视 高清", "青海卫视 高清", "宁夏卫视 高清", "新疆卫视 高清", "西藏卫视 高清",
    "云南卫视 高清", "贵州卫视 高清", "广西卫视 高清", "福建东南卫视 高清", "江西卫视 高清",
    "内蒙古卫视 高清", "海南卫视 高清", "重庆卫视 高清", "延边卫视", "厦门卫视", "兵团卫视", "三沙卫视",
    "卡酷少儿", "优漫卡通", "金鹰卡通", "哈哈炫动", "嘉佳卡通",
]

HMT = [
    "凤凰卫视中文台", "凤凰卫视资讯台", "翡翠台", "明珠台", "TVB星河频道", "澳视澳门", "中天新闻",
]

OTHER_GROUP = ["导视资讯", "电视指南"]

PROVINCES = [
    "河北", "山西", "辽宁", "吉林", "黑龙江", "江苏", "浙江", "安徽", "福建", "江西", "山东",
    "河南", "湖北", "湖南", "广东", "广西", "海南", "四川", "贵州", "云南", "陕西", "甘肃",
    "青海", "宁夏", "新疆", "西藏", "内蒙古", "北京", "天津", "上海", "重庆", "深圳", "青岛",
    "大连", "宁波", "厦门", "南京", "杭州", "广州", "成都", "武汉", "西安", "沈阳", "哈尔滨",
    "济南", "郑州", "长沙", "福州", "昆明",
]

CITY_SUFFIX = [
    "新闻综合", "公共频道", "影视", "都市", "经济生活", "少儿", "科教", "体育", "农业农村",
    "电视剧", "纪录", "文艺", "生活", "文旅",
]


def synth_host(index: int) -> str:
    """Synthetic host: `.invalid` is reserved by RFC 2606, so nothing here can leak a real one."""
    return "http://p12.demo.invalid/province%03d/index.m3u8" % index


def emit(rows, name, group, channel_no=None, logo=None, tvg_id=None):
    attrs = ['-1']
    attrs.append('tvg-id="%s"' % (tvg_id or name))
    if logo:
        attrs.append('tvg-logo="%s"' % logo)
    if channel_no:
        attrs.append("tvg-chno=%d" % channel_no)
    attrs.append('group-title="%s"' % group)
    rows.append("#EXTINF:%s,%s" % (" ".join(attrs), name))
    rows.append(synth_host(len(rows)))


def build():
    rows = ["#EXTM3U"]
    # --- 央视: 80 distinct channels; every 10th carries a tvg-chno (the D12 middle tier) ---
    for i, name in enumerate(CCTV[:80]):
        emit(rows, name, "央视", channel_no=(i + 1) if i % 10 == 0 else None,
             logo="http://p12.demo.invalid/logo/cctv.png" if i % 5 == 0 else None)
    # --- 卫视: 69 ---
    for i, name in enumerate(SATELLITE[:69]):
        emit(rows, name, "卫视", channel_no=(901 + (i // 10)) if i % 10 == 1 else None)
    # --- 港澳台: 7 ---
    for i, name in enumerate(HMT[:7]):
        emit(rows, name, "港澳台", logo="http://p12.demo.invalid/logo/hmt.png" if i % 2 == 0 else None)
    # --- 地方/其他: 500 distinct (province × suffix, 48 × 14 = 672 candidates) ---
    local = 0
    for suffix in CITY_SUFFIX:
        for province in PROVINCES:
            if local >= 500:
                break
            emit(rows, "%s%s" % (province, suffix), "地方/其他")
            local += 1
        if local >= 500:
            break
    # --- 其他频道: 2 ---
    for name in OTHER_GROUP[:2]:
        emit(rows, name, "其他频道")

    # --- 12 extra rows whose (name, group) already exists: they must MERGE into the channel above
    #     (docs/02 §5.1 `UNIQUE(name_key, group_key)`), so channels stay 658 while streams grow.
    for name, group in [
        ("CCTV1", "央视"), ("CCTV2", "央视"), ("CCTV3", "央视"), ("CCTV5", "央视"),
        ("CCTV13", "央视"), ("CCTV-1综合", "央视"),
        ("北京卫视", "卫视"), ("湖南卫视", "卫视"), ("浙江卫视", "卫视"),
        ("凤凰卫视中文台", "港澳台"), ("翡翠台", "港澳台"), ("河北新闻综合", "地方/其他"),
    ]:
        emit(rows, name, group)
    return rows


if __name__ == "__main__":
    out = "\n".join(build())
    sys.stdout.write(out + "\n")
