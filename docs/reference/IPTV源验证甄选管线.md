# 国内直播源「验证甄选」管线 — 操作手册

本目录是一套可重复运行的 IPTV 源验证与甄选管线。它把"网上找到的一堆源"变成
"一份**当场验证过、按分数排序、每台带备胎**的播放列表"。

## 目录结构

```
iptv/
├── build_candidates.py     # 采集+归一化+去重  -> data/candidates.json / .m3u
├── probe.py                # 核心验证：清单/分片/ffprobe/60s稳定性 -> out/probe_results.jsonl
├── score_export.py         # 评分+甄选+导出 -> out/validated*.m3u / report.md
├── tools/                  # 静态 ffmpeg(6.1.1) 与 ffprobe(4.0.2)
├── data/                   # 候选数据
└── out/                    # 结果
```

## 一键运行

```bash
cd ~/dsh/iptv

python3 build_candidates.py     # 1) 生成候选（默认拉 iptv-org 中国频道）
python3 probe.py                # 2) 验证（32 并发，每流约 60-90s）
python3 score_export.py         # 3) 评分、甄选、导出
```

运行中可随时中断（结果按行增量写入），再次运行会**自动跳过已完成**的流。

## 验证了什么（probe.py）

| 检查 | 说明 |
|---|---|
| 清单可达 | HTTP 状态 + 是否 #EXTM3U；带/不带 UA·Referer 各试 |
| 多码率 | master 列表逐个尝试前 4 个 variant |
| 分片可取 | 取**直播边沿**（最后一片）再退回首片，记录状态与字节 |
| 可解码 | ffprobe 解出视频/音频编码、分辨率、声道、码率 |
| 稳定性 | 60s 内每 5s 轮询清单，统计失败次数与媒体序号前进量 |
| 可判定 | samples>0 且无失败且序号前进 -> 判为可播放 |

## 评分模型（score_export.py，100 分制）

| 维度 | 满分 | 规则 |
|---|---|---|
| 可用性 | 35 | 判定可播放即得 |
| 稳定性 | 25 | 按轮询失败率线性扣分；序号不前进再扣 10 |
| 设备兼容 | 20 | H.264=20；H.265=12；AV1=4；AC3/EAC3 再扣 5；>1080p 扣 3 |
| 画质/码率 | 10 | 1080p=10；720p=8；480p=5 |
| 防盗链 | 5 | 无需特殊头=5；需要则记录并给 3 |
| 分片成功 | 2 | 边沿分片 200 加分 |

每台保留**最高分**为主源，次高分（>=55）为备胎。

## 如何加入你自己的源

1. 把 M3U 文件放到 `data/`，或把 URL 写进一个文本文件（每行一个）。
2. 在 `build_candidates.py` 末尾追加解析逻辑，统一输出到 `data/candidates.json`，
   字段： `channel_id / name / group / url / quality / user_agent / referrer / source` 。
3. 重新跑 `probe.py` 与 `score_export.py`。

> 只要字段齐，管线不关心源从哪来；`user_agent` / `referrer` 会被自动用于探测，
> 并在导出时写成 `#EXTVLCOPT` 随条目携带。

## 导入电视

两份列表都能被 **TiviMate** 与 **Kodi PVR IPTV Simple Client** 直接消费：

| 客户端 | 导入 |
|---|---|
| TiviMate | 设置 -> 播放列表 -> 添加 M3U 播放列表（填文件/URL）；设置 -> EPG -> XMLTV |
| Kodi | 设置 -> 插件 -> PVR IPTV Simple Client -> 配置 -> M3U Play List URL；PVR 和直播电视 -> 启用 |

推荐：
- **TiviMate** 用 `validated.m3u`（功能最全，必要时在播放列表设置里再填 UA/Referer）
- **Kodi** 用 `validated.m3u`（`#EXTVLCOPT` 会被自动识别，无需额外设置）
- 对 32 位电视的保守选择用 `validated-lite.m3u`（仅 H.264/AAC/<=1080p）

## 周期复检

公共源会失效，建议定时重跑（结果追加/跳过已完成）：

```bash
# 每天 06:00 重置并复检（示例；Debian 与 macOS 的 cron 语法相同）
0 6 * * * cd /path/to/iptv && rm -f out/probe_results.jsonl && python3 probe.py >> out/recheck.log 2>&1 && python3 score_export.py
```

## 注意事项

- 公共源多为**未经授权转播**，请只用于学习或合法授权内容。
- 验证只证明"通不通"，不证明"能不能合法用"。
- 单点验证（同一出口 IP）不能完全代表电视端解码能力，故用"设备兼容"维度兜底。

## 联网采集（collect_sources.py）

从 GitHub 公开聚合列表采集候选（当前 17 个源）。两阶段运行：

```bash
python3 collect_sources.py     # 采集+归一化+合并去重 -> data/candidates.json
python3 pick_survivors.py      # 从快速结果里挑出可播放的 -> data/survivors.json

# 阶段1：全量快速甄别（不做稳定性采样，快）
SAMPLE=0 WORKERS=64 PROBE_OUT=out/probe_fast.jsonl python3 probe.py

# 阶段2：仅对存活源做 45s 稳定性复测
SAMPLE=45 WORKERS=64 CAND_FILE=data/survivors.json PROBE_OUT=out/probe_stable.jsonl python3 probe.py

# 汇总评分（stable 覆盖 fast）
PROBE_FILES=probe_fast.jsonl,probe_stable.jsonl python3 score_export.py
```

本次实测：候选 4372 -> 可播放 898 -> 入选 579 个频道（央视 60 / 卫视 81 / 港澳台 9 / 地方 429）。
