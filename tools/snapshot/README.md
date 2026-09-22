# tools/snapshot — 内置快照的探针 / 生成 / 校验

内置快照（`app/src/main/assets/snapshot/channels.m3u`）是**开机即播**的频道表：它必须在**电视上**
逐条验证过，而不是"Mac 上能打开就算数"。本目录把这件事做成一条命令可重复的流程。

## 收录判据（两条，都在电视上实测）

| 判据 | 阈值 | 工具里的参数 |
| --- | --- | --- |
| 首帧 | **≤ 3000 ms** | `--first-frame-ms` / `-e firstFrameTimeoutMs` |
| 连续播放 | **≥ 30 000 ms 无 stall** | `--hold-ms` / `-e holdMs` |

stall 的定义：首帧之后，播放位置与缓冲位置**连续 ≥ 2000 ms 都不再变化**（`-e stallMs`）。
这与 App 自己 `PLAY_STALL` 的口径同向（`EngineTuning.stallThresholdMs = 8000`，本流程更严）。
一次样本只有在**首帧达标且 hold 窗口内一次 stall 都没有**时才算 `pass`。

## 一条命令跑完整条链

```bash
# 0) 前置：adb 连上电视（独占），Gradle 用 JDK 17（Android Studio 自带 JBR）
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
adb connect <TV-LAN-IP>:5555

# 1) 电视实测：候选清单逐条起播 + hold 30 s
tools/snapshot/probe.sh --candidates tools/fixtures/large/validated.m3u \
    --device <TV-LAN-IP>:5555 --out /tmp/snapshot_probe

# 2) 按判据筛出快照（顺序 = 候选清单顺序，先央视/卫视再地方）
tools/snapshot/build_snapshot.py \
    --candidates tools/fixtures/large/validated.m3u \
    --probe /tmp/snapshot_probe/probe.jsonl \
    --out-m3u app/src/main/assets/snapshot/channels.m3u \
    --out-provenance app/src/main/assets/snapshot/PROVENANCE.md

# 3) 静态校验（不需要设备、不需要网络）
tools/snapshot/verify.sh app/src/main/assets/snapshot/channels.m3u
```

`tools/fixtures/large/validated.m3u` 是 P2 采集的候选池（658 条流），**没有入库**（gitignore）。
没有它时用任何 m3u 当候选池即可；`probe.sh` 也接受一份 `名称<TAB>URL` 的纯文本清单。

## 三个脚本

### `probe.sh` — 电视实测（探针）
构建并安装**探针夹具**（`spike/` 里的一次性测量 App `ilab.iptv.player.spike`，与产品同引擎族：
Media3 `ExoPlayer` + `media3-exoplayer-hls`，跑在电视本机解码），把候选清单推到电视，逐条起播并
hold，最后把结果拉回本地。

```bash
tools/snapshot/probe.sh --candidates <m3u| list> [--device SERIAL] [--out DIR] \
    [--hold-ms 30000] [--first-frame-ms 3000] [--stall-ms 2000]
```

- 输出 `DIR/probe.jsonl`（**一条 URL 一行**，随测随写，中途被打断也有数据）与 `DIR/probe.json`（汇总）。
- 每条记录的字段：`name` / `url` / `outcome` / `pass` / `firstFrameMs` / `heldMs` / `stallCount` /
  `stallTotalMs` / `stallMaxMs` / `advancedMs` / `videoFormat` / `audioFormat` / `error`。
- `outcome`：`pass`（两条判据都过）· `stall`（首帧后卡住）· `no_first_frame`（3 s 内没出画面）·
  `error_before_first_frame` / `error_after_first_frame`（Media3 报错，含 `ERROR_CODE_*`）· `ended`。
- 成本：约 **35 s / 条**（达标样本要跑满 30 s hold），失败样本只花到首帧超时。200 条 ≈ 2 h。
  探针跑在电视上，**跑动时不要再动电视**（别的 activity/按键会打断它）。
- 探针 App 是测量夹具，**不是产品代码**：它不依赖 `core/*`/`feature/*`，不改任何生产契约。

### `build_snapshot.py` — 生成快照
按候选清单顺序保留 `pass=true` 的条目，写 `channels.m3u` 与 `PROVENANCE.md`。

```bash
tools/snapshot/build_snapshot.py --candidates <m3u> --probe <jsonl>[,<jsonl>...] \
    --out-m3u <path> --out-provenance <path> [--min-channels 150] [--first-frame-ms 3000]
    [--date YYYY-MM-DD] [--drop-host HOST]... [--drop-url URL]...
```

- 逐条带 `tvg-id` / `tvg-name` / `tvg-chno`（1..N，按输出顺序重排）/ `group-title` / 显示名，
  一条频道一条流（不合并），与 App 的 `SnapshotPlaylistTest` 断言一致。
- 硬性剔除（即使探针通过也不收）：私网/LAN 地址、`.invalid` 占位域名、
  `token` / `secret` / `password` 一类**凭据查询键**、`--drop-host` 指定的宿主、`--drop-url` 指定的单条 URL
  （用于**已知会偶发卡死**的源：一次 30 s 探针过不代表它稳）。
- `PROVENANCE.md` 由数据生成：分组计数按**实际输出**统计，判据、日期、样本量、失效宿主、
  "快照会过期 + 怎么重生成"都写进去。

### `verify.sh` — 静态校验
不需要设备/网络，检查快照是不是一份**可用的快照**：条数 ≥150、一条频道一条流、每行元数据齐全、
`tvg-chno` 从 1 连续到 N、分组覆盖央视/卫视/地方、无私网地址、无 `.invalid`、无凭据键、
`PROVENANCE.md` 存在且分组计数与文件一致。

```bash
tools/snapshot/verify.sh [path/to/channels.m3u]
```

退出码 0 = 全绿。

## 快照会过期

这些是地方台直链，源站改动、令牌过期、换网络出口都会让条目失效。**重生成就是重跑上面三步**；
`PROVENANCE.md` 里记着生成日期与样本量，机器到期/换网后请重跑并更新日期。
