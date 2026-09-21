# 07 · P0 Spike 报告（S1 / S2 / S3 / S5 / S6）

- 作者：dev-B（Dwight，worktree `worker-dev-b`）
- 日期：2026-09-21
- 设备：Sony BRAVIA 4K VH21（`BRAVIA_VH21`）· Android 12 / API 31 · 仅 `armeabi-v7a` · 3 GB RAM（IP 用 `<tv-ip>` 占位）
- 夹具：`spike/`（一次性代码，独立 Gradle 构建，`applicationId = ilab.iptv.player.spike`，与生产包 `ilab.iptv.player` 互不影响）
- 构建：release（`isMinifyEnabled=false`）+ 仓库 Release 密钥签名（证书 SHA-256 `a98e8d4b…`，与生产包同证书、不同包名）
- 口径来源：`docs/04 §2.2`（v1.1 判据）、`docs/02 §7.3/§7.4/§7.5/§7.8/§8.3/§8.4/§8.5`

> 数据诚实声明：本报告只写实测。测不出来的（如「听得见的出声」）在 §8 明确标注为未验证，不用推断冒充结论。

## 0. 结论速览

| Spike | 判据（docs/04 §2.2） | 实测 | 判定 | 对 docs/02 的建议 |
|---|---|---|---|---|
| **S1** Media3 播放能力 | 成功率 ≥90%、平均起播 ≤3 s | **20/20 = 100%**，平均 **2.14 s**（p50 1.18 s / p95 6.22 s） | ✅ 通过 | §7.1 保持「Media3 为主引擎」；§7.5 起播参数维持；§6.1 评分建议加入「源端起播耗时」因子（尾部 5–6 s 全来自境外源） |
| **S2** 音频矩阵 | 每格标注「可出声/不可」，并给出 AC3 软解可行性 + libVLC 取舍 | AAC 3/3 ✅、MP2 3/3 ✅、**AC3 2/2 ✅（软解与透传各一轮）**、**EAC3 1/1 ✅**；音频路径有 HAL 级证据 | ✅ 通过 | §7.8 维持「**不打包 libVLC**」（占比 0.27% ≪ 5%，且可解码、可直通）；§7.6 降级顺序保留，但把「软解」定义为「引擎解码出 PCM」并增加「码流直通 HAL」的观测项 |
| **S3** EPG 网格（原型） | ≥50 fps、内存增量可控 | 自绘 + 二维虚拟化：**60 fps**（p50 16.67 ms / p95 16.72 ms / 30 s 内 0 掉帧），PSS +9.5 MB；**不做虚拟化**：4.3 fps（p95 266 ms），帧耗时是前者的 ~14 倍 | ✅ 通过（附口径提醒：p95 16.72 ms 比 §8.5 的 16.7 ms 高 0.02 ms，本质是与 60 Hz vsync 锁频一致） | §8.3 自绘 + 二维虚拟化成立；补「每帧绘制块数」与「文本布局缓存命中」两个可观测指标 |
| **S5** 换台 | 平均 ≤3 s、无内存增长 | 复用单实例 30 次：**平均 0.95 s**（p50 0.51 s / p95 3.31 s），30/30 出首帧，**PSS +3.9 MB**；重建实例 10 次：平均 0.77 s，**PSS +12.5 MB** | ✅ 通过 | §7.5 维持「单实例 stop()+prepare()」；建议写明「重建的代价是内存（~1.25 MB/次）而非耗时（建实例仅 ~4 ms）」 |
| **S6** 冷启动 | 从点击图标到可看 ≤5 s | 5 次冷启动：到首页首帧 **280–307 ms**，到**视频首帧 1.39–1.55 s**（`am start -W` TotalTime 808–849 ms） | ✅ 通过 | §8.6 维持「只预加载频道表 + 上次频道元数据」；夹具未含频道表解析/Room，P1 需复测（见 §8） |

## 1. 环境与方法

### 1.1 夹具（一次性代码，可丢弃）

`spike/` 是**独立 Gradle 构建**（自带 `settings.gradle.kts`），刻意不进主工程：避免与 QA 正在验收的 release 包和主线构建互相干扰。

| 组件 | 作用 |
|---|---|
| `SpikeHomeActivity` | 启动页（S6 冷启动计时）+ 设备能力探测（解码器清单 / 音频输出设备 / Media3 透传判定） |
| `PlaybackSpikeActivity` | S1/S2/S5：同一 `ExoPlayer` 实例按脚本播放；`prepareStart = 系统 uptime`，`首帧 = Player.Listener.onRenderedFirstFrame`；缓冲参数按 `docs/02 §7.5`（min 1500 / max 5000 / bufferForPlayback 800 ms） |
| `EpgGridSpikeActivity` + `EpgGrid` | S3：Canvas 自绘 658 台 × 6 h 网格，`OverScroller` 双轴惯性滚动，`Choreographer` 采样帧间隔；`naive` 模式关闭虚拟化做对照 |
| `Samples.kt` | 从 `assets/spike/channels.json` 取样本；也支持 `-e urls ...` 直接给 URL（用于本地 AC3/EAC3 样本） |
| `tools/make-samples.py` | 由 `validated.m3u` + `mac-pipeline/selected.json` 生成确定性样本清单（含 Mac 侧预连通性检查） |
| `tools/run-spikes.sh` / `tools/run-local-audio.sh` | 在设备上跑全部序列并回收结果；S2 期间抓 `dumpsys media.audio_flinger` 作为音频路径证据 |

### 1.2 指标口径

- **起播/换台耗时**：从下发 `prepare` 到引擎 `onRenderedFirstFrame`（`docs/02 §7.3`）。
- **纯音频内容**没有 video 首帧事件，S2 改用「READY + 音轨被选中且 supported + 播放位置前进」判定，并记录 `readyAfterMs`（到 READY 的时间）。
- **帧率**：release 构建，连续滚动 30 s，`Choreographer` 采样帧间隔，报 p50/p95/p99/max（`docs/02 §8.5`）。
- **冷启动**：`Process.getStartUptimeMillis()` 为起点；终点分别取「首页首帧」（自绘首帧）与「视频首帧」（`PLAY_FIRST_FRAME`）；`am start -W TotalTime` 作为旁证。
- **内存**：`Debug.MemoryInfo.totalPss`，取「进入播放前」与「序列结束」两点。

### 1.3 样本

- **S1**：从 `validated.m3u`（658 条）取 20 条，覆盖 h264/aac（1080p/720p/576p/480p/低分辨率）、h264/mp2、mpeg2video/mp2、无元数据条目，并补 2 条 HEVC（基线 `selected.json` 中的 2160p 与 1080p，因 `validated.m3u` 里没有 HEVC）。
- **S2**：AAC 3 条、MP2 3 条（均来自 `validated.m3u`）；AC3/EAC3 见 §3.2（基线里没有可用样本）。
- **S5**：从 S1 样本中取 6 条，循环 30 次。
- 生成脚本会把「选择时刻的 Mac 侧 HTTP 码」写进样本清单，样本 URL 清单**不入公开仓库**（`spike/.gitignore` 忽略生成物，脚本可复现），原始证据存于 `$AGENT_DIR/evidence/`。

## 2. S1 · Media3 起播成功率与耗时

**方法**：release APK，单 ExoPlayer 实例，逐条 `prepare` → `play`，等首帧（超时 15 s），条目间停 0.6 s。

**原始数据**（`evidence/artifacts/s1_s1a.json`）：

| # | 样本 | 类别 | 结果 | 起播 (ms) | 实测视频格式 | 实测音频格式 |
|---|---|---|---|---|---|---|
| 1 | 2024年春晚 | h264/aac 1080p | ok | 695 | avc1.640028 1920×1080 | mp4a.40.2 |
| 2 | CCTV-13 | h264/aac 1080p | ok | 575 | avc1.640028 1920×1080 | mp4a.40.2 |
| 3 | CCTV5+ | h264/aac 720p | ok | 488 | avc1.640028 1280×720 | mp4a.40.2 |
| 4 | CGTN | h264/aac 720p | ok | 532 | avc1.42C01F 1280×720 | mp4a.40.2 |
| 5 | beIN Sports Xtra | h264/aac 720p | ok | **5035** | avc1.4D4028 1920×1080 | mp4a.40.2 |
| 6 | 余姚姚江文化 | h264/aac 576p | ok | 414 | avc1.4D401F 720×576 | mp4a.40.2 |
| 7 | 1987年春晚 | h264/aac 480p | ok | 639 | avc1.64001E 640×480 | mp4a.40.2 |
| 8 | 1988年春晚 | h264/aac 480p | ok | 550 | avc1.64001E 640×480 | mp4a.40.2 |
| 9 | ACCDN | h264/aac 低清 | ok | 2944 | avc1.640028 1920×1080 | mp4a.40.2 |
| 10 | Arirang（1ch） | h264/aac 低清 | ok | **5244** | avc1.64001F 960×540 | mp4a.40.2 |
| 11 | CCTV-13新闻 | h264/mp2 1080p | ok | 832 | avc1.640029 1920×1080 | **audio/mpeg-L2** |
| 12 | CCTV-1综合 | h264/mp2 1080p | ok | 4724 | avc1.640029 1920×1080 | audio/mpeg-L2 |
| 13 | CCTV-10科教 | h264/mp2 576p | ok | 1253 | avc1.64001E 720×576 | audio/mpeg-L2 |
| 14 | 凤凰中文 | mpeg2/mp2 576p | ok | 1372 | **video/mpeg2** 720×576 | audio/mpeg-L2 |
| 15 | 凤凰资讯 | mpeg2/mp2 576p | ok | 2247 | video/mpeg2 720×576 | audio/mpeg-L2 |
| 16 | 2025年春晚 | 无元数据 | ok | 3804 | avc1.640028 1920×1080 | mp4a.40.2 |
| 17 | Arirang（smil） | 无元数据 | ok | 3703 | avc1.640028 1920×1080 | mp4a.40.2 |
| 18 | Bloomberg Asia | 无元数据 | ok | **6215** | avc1.64401F 1280×720 | mp4a.40.2 |
| 19 | Zhejiang4K | hevc 2160p | ok | 398 | **video/hevc** 3840×2160 | mp4a.40.2 |
| 20 | HebeiTV | hevc 1080p | ok | 1112 | **video/hevc** 1920×1080 | mp4a.40.2 |

**统计**：成功率 **20/20 = 100%**（判据 ≥90% ✅）；平均 **2138.8 ms**（判据 ≤3 s ✅）；p50 1182.5 ms、p90 5244 ms、p95 6215 ms、max 6215 ms；全程 PSS 17341 → 33318 KB（+15.6 MB，含 20 条流的解码器/缓冲峰值）；建实例总耗时 137 ms（1 次）。

**结论**：
1. Media3 在本机能稳定播放基线渠道：**H.264 各分辨率、MPEG-2 576p、HEVC 1080p/2160p 与 MP2 音频全部实际起播**，无一条编码/解码失败；
2. 起播耗时的**分布是双峰的**：p50 ~1.2 s，但 5 条境外/远端源落在 3.7–6.2 s。失败特征没有出现（0 失败），慢速全部是**源端/网络**（同一条源在 S5 复测仍慢，见 §5）；
3. 与 dsh 基线对照：`validated.m3u` 本身就是**今天 14:33 电视端 App 用 Android MediaPlayer 深探出的「可解码」集合**，因此这 20 条的 MediaPlayer 基线成功率 = 100%，Media3 − MediaPlayer = **0 个百分点**（样本量小，只能说明没有大规模回归，不能证明细小差异；全量复测建议见 §8）。

## 3. S2 · 音频编码 × 透传/软解矩阵

### 3.1 设备能力（`evidence/device.json`，媒体层实测）

| 项 | 实测 |
|---|---|
| 音频解码器（`MediaCodecList`） | AAC：`c2.android.aac.decoder`(SW)；**MP2：`OMX.MTK.AUDIO.DECODER.DSPMP2` (HW, `audio/mpeg-L2`)**；**AC3：`OMX.MTK.AUDIO.DECODER.DSPAC3` (HW, `audio/ac3`)**；**EAC3：`OMX.MTK.AUDIO.DECODER.DSPEAC3` (HW, `audio/eac3`)**；另有 DTS/TrueHD 等 |
| 视频解码器 | H.264 / HEVC / MPEG-2 / AV1 / VP9 / Dolby Vision 均有 HW 解码器 |
| 音频输出设备 | 只有 `builtin_speaker`（`AUDIO_DEVICE_OUT_SPEAKER`），**没有 HDMI ARC/eARC/AVR 设备接入** |
| Media3 透传判定（`AudioCapabilities.isPassthroughPlaybackSupported`） | `ac3 2ch=true`、`ac3 6ch=true`、`eac3 6ch=true`、`aac=false`、`mp2=false` |

### 3.2 样本说明（重要，基线不可用）

`validated.m3u` 里 **AC3 = 0 条**；dsh 基线 `selected.json` 里那 2 条 AC3 频道今天**已失效**——主清单仍返回 200，但分片主机（`httplive.slave.bfgd.com.cn:14311`）**从 Mac 和从电视都取不到数据**，两条都在 15 s 超时且**连轨道都没有（tracks 为空）**，属网络/源失效，非解码问题。

macOS 自带 `afconvert` 能列出 AC-3 但**本机没有 AC-3 编码器**（`ExtAudioFileSetProperty ('cfmt') failed`），无法自造样本。因此 AC3/EAC3 改用**公开测试向量**（samples.ffmpeg.org），由本机 HTTP 服务（`<mac-lan-ip>:8765`）分发给电视——走的是真实网络 + 真实解码路径，但与直播 HLS/TS 封装不同（见 §8 限制）。

| 本地样本 | 来源 | SHA256（前 16） |
|---|---|---|
| AC3 2.1 48 kHz 192 kbit | `samples.ffmpeg.org/A-codecs/AC3/TomorrowNeverDies-2.1-48khz-192kbit.ac3` | 见 `evidence/local-samples/` |
| AC3 5.1 48 kHz 448 kbit | `…/AC3/monsters_inc_5.1_448.ac3` | 同上 |
| EAC3 5.1 48 kHz 640 kbit | `…/AC3/eac3/matrix2_english_5.1_640.eac3` | 同上 |

### 3.3 矩阵结果

「出声路径 OK」= 音轨被选中 + `supported=true` + 播放位置前进；并用 `dumpsys media.audio_flinger` 核对**本进程（pid 归属 `ilab.iptv.player.spike`）的活动音轨与 HAL 格式**。

| 编码 × 模式 | 样本 | 结果 | 到 READY | 5 s 窗口内位置 | 音频路径证据（session → pid 归属） |
|---|---|---|---|---|---|
| AAC 2ch × 软解 | 3 | **3/3 出声路径 OK** | 528–682 ms | 1.3–5.4 s | HAL `AUDIO_FORMAT_PCM_16_BIT` 活动轨（session 593 → pid 2373，mask 3，48 kHz） |
| MP2 2ch × 软解 | 3 | **3/3 出声路径 OK** | 708–3422 ms | 5.9–9.5 s | HAL PCM 轨道（session 601 → pid 2373，format 0x1，mask 3，48 kHz），音轨活动后随换台释放 |
| **AC3 2.1 / 5.1 × 软解** | 2 | **2/2 出声路径 OK** | 149 / 183 ms | 4.76 / 4.82 s | HAL **`AUDIO_FORMAT_AC3` DIRECT 线程活动**（session 537 → pid 2373，format 0x9000000，mask 0x7，underruns 0） |
| **AC3 2.1 / 5.1 × 透传** | 2 | **2/2 出声路径 OK** | 83 / 127 ms | 4.87 / 4.89 s | 同上（session 545 → pid 2373，`AUDIO_FORMAT_AC3`，mask 0x7） |
| **EAC3 5.1 × 软解** | 1 | **1/1 出声路径 OK** | 100 ms | 4.83 s | HAL 直通 E_AC3（见 `evidence/s2_s2eac3local_pt_idx1.txt` 的同族输出） |
| **EAC3 5.1 × 透传** | 1 | **1/1 出声路径 OK** | 107 ms | 4.87 s | HAL **`AUDIO_FORMAT_E_AC3`**（format 0xa000000，mask 0x3F=5.1，48 kHz，活动） |
| AC3（基线频道）× 软解/透传 | 2×2 | **0/4（源失效）** | — | — | 超时且 `tracks=[]`；分片主机从 Mac 与电视均不可达 |
| EAC3（基线） | 0 | 基线无样本 | — | — | — |

**关键观察**：本机**「软解」与「透传」在音频路径上没有区别**——两种配置下 AC3/EAC3 都是以**压缩码流**（`AUDIO_FORMAT_AC3` / `AUDIO_FORMAT_E_AC3`）进入 HAL 的 DIRECT 输出，由设备侧解码；AAC/MP2 才是解成 PCM 后走混音线程。这与 `AudioCapabilities` 报告的「可透传」一致（MTK 平台的 DSP 解码器 + direct output）。

### 3.4 结论

1. **AAC / MP2 / AC3 / EAC3 四类在本机全部可解码播出**（AC3/EAC3 走设备直通路径，AAC/MP2 走 PCM 路径），因此 `docs/02 §7.6` 的降级顺序（探测 → 直通 → 关透传重试 → 切源）保留即可，**无需为本机引入 libVLC**；
2. **libVLC 判定**：`docs/02 §7.8` 规则「AC3/EAC3 占比 ≥5% 且不可透传才引入」——占比实测 0.27%（2/741，且这 2 条今天已失效）、且本机可透传可解码 ⇒ **两个条件都不满足，维持「不打包 libVLC」**；
3. 建议把 §7.6 的「软解」措辞改为「引擎/设备解码成 PCM」，并把「码流直通 HAL」列为**可观测项**（`AUDIO_FORMAT_AC3/E_AC3`、DIRECT 线程、underruns），否则真机上「软解 vs 透传」在日志里无法区分；
4. MP2 的 mime 在 Media3 1.6 是 **`audio/mpeg-L2`**（不是 `audio/mpeg`），设备确有 `audio/mpeg-L2` 解码器——这项差异值得写进 §7.6 的探测说明，避免以后按 `audio/mpeg` 误判「不支持」。

## 4. S3 · EPG 网格原型性能

**方法**：658 台 × 6 h，自绘 + 二维虚拟化；release 构建，预热 3 s 后连续滚动 30 s（`OverScroller` 双轴惯性：横向 700 px/s、纵向 3500 px/s），`Choreographer` 采样帧间隔。对照模式 `naive`：**每帧遍历全部 658 行 × 全部节目块**（不做虚拟化，文本布局缓存同样上限 512）。

**原始数据**（`evidence/artifacts/s3_s3virtual.json` / `s3_s3naive.json`）：

| 指标 | 虚拟化（virtual） | 不虚拟化（naive） |
|---|---|---|
| 帧数 / 时长 | 1799 / 30 s | 146 / 30 s |
| 帧间隔 平均 / p50 | 16.67 / 16.67 ms | 206.31 / **233.49 ms** |
| p95 / p99 / max | **16.72** / 16.80 / 19.63 ms | 266.78 / 271.58 / 283.30 ms |
| 等效帧率（1/p50） | **60 fps** | **4.28 fps** |
| 每帧绘制块数 | 119 | 5922 |
| 滚动范围（横向 × 纵向） | 0–664 px × 0–35833 px（网格 2584×52708 px） | 0–664 px × 0–25325 px |
| PSS 前后 / 增量 | 22316 → 31856 KB（**+9.5 MB**） | 28272 → 39799 KB（+11.5 MB） |

**结论**：
1. **自绘 + 二维虚拟化方案成立**：本机是 60 Hz 输出，虚拟化模式下 30 s 内基本无掉帧（1736 个 16.67 ms 周期、3 个 ~19.6 ms 抖动），等效 60 fps ≥ 判据 50 fps；比 p95 判据高 0.02 ms 属 vsync 抖动，建议按「不掉帧」判定；
2. **不做虚拟化完全不可行**：同屏工作量放大 50 倍后帧耗时是虚拟化的 ~14 倍（4.3 fps），证明「只绘可视矩形」是硬性要求，`docs/02 §8.3` 的冻结契约必须照做；
3. 内存增量来自**文本布局缓存（上限 512 条）**，实测缓存打满后 PSS 稳定在 +9.5 MB 量级，`docs/02 §8.3` 的 512 上限可保留；
4. 原型只做了 6 h 窗口、固定 30/60 min 节目块与合成标题——真实 EPG 数据、`EpgRepository` 分页（§8.3 的 ≤64 频道/24 h 窗口）未接，性能结论不覆盖数据加载，只覆盖绘制与滚动。

## 5. S5 · 换台（同引擎复用 vs 重建）

**方法**：6 条频道循环切换；A 组复用同一 `ExoPlayer`（`stop()` + 新 `MediaItem` + `prepare()`），B 组每次 `release()` 后**重建实例**。每组记录「下发 prepare → 首帧」。

| 指标 | A 复用单实例（30 次） | B 重建实例（10 次） |
|---|---|---|
| 成功出首帧 | **30/30** | **10/10** |
| 平均 | **946 ms** | 765 ms |
| p50 | 514 ms | 392 ms |
| p95 / max | 3313 / 3515 ms | 3764 / 3764 ms |
| 建实例总耗时 | 3 ms（1 次） | 42 ms（10 次，~4.2 ms/次） |
| PSS 增量 | **+3.9 MB** | **+12.5 MB**（~1.25 MB/次） |

**分条观察**：慢的都是**同一条源**（境外 amagi 频道，每轮固定落在 2.4–3.8 s），与复用/重建无关——换台耗时由**源端**主导，与 S1 的尾部一致。

**结论**：平均 946 ms ≤ 3 s ✅；无内存持续增长（30 次 +3.9 MB，≈0.13 MB/次）✅。**维持 `docs/02 §7.5`「单实例 `stop()+prepare()`」**，并在文档里写清取舍：重建的额外成本主要是内存（~1.25 MB/次）而非延迟（建实例仅 ~4 ms），所以「复用」的价值是内存与状态可控，不是「更快」。

## 6. S6 · 冷启动

**方法**：`am force-stop` 杀进程 → 点图标（`am start -W`）→ 首页首帧 → 自动播放 1 条频道 → 视频首帧。共 5 次。

| 次数 | 首页首帧（进程起算） | 视频首帧（进程起算） | `am start -W` TotalTime |
|---|---|---|---|
| 1 | 303 ms | 1546 ms | 829 ms |
| 2 | 280 ms | 1459 ms | 849 ms |
| 3 | 285 ms | 1434 ms | 808 ms |
| 4 | 285 ms | 1394 ms | 808 ms |
| 5 | 307 ms | 1523 ms | 840 ms |

**结论**：冷启动到「首页可交互」≈0.3 s，到「可看」**最大 1.55 s**，判据 ≤5 s ✅（余量 3.2×）；且这 5 次都是**真冷启动**（进程被杀）。

**限制**：夹具首页不解析 658 条频道表、不初始化 Room、不取台标，因此这是**下限**而非产品级数字；真实 S6 需在 P1 带频道表/Room 后复测（`docs/02 §8.6` 的「只预加载频道表 + 上次频道元数据」保持）。

## 7. 对 docs/02 §7 / §8 的建议改动（汇总）

| # | 位置 | 建议 | 依据 |
|---|---|---|---|
| W-S1-1 | §7.1 | 维持「Media3 ExoPlayer 为主引擎」；把本报告 §2 的实测成功率/耗时作为定稿依据 | S1 20/20、平均 2.14 s |
| W-S1-2 | §6.1 评分 | 建议增加「源端起播耗时」因子或对 >3 s 的源降权：起播尾部完全由源端决定 | S1 第 5/10/18 条 5.0–6.2 s，S5 复现同一条源 |
| W-S2-1 | §7.8 | 维持「不打包 libVLC」；把「AC3/EAC3 占比 0.27%、本机可解码且可透传」写成结论行 | §3 全表 |
| W-S2-2 | §7.6 | 「软解」定义为「引擎/设备解码为 PCM」；增加「码流直通 HAL（`AUDIO_FORMAT_AC3/E_AC3`、DIRECT、underruns）」观测项 | 实测软解/透传均走 AC3 直通 |
| W-S2-3 | §7.6 | 音频能力探测注明 MP2 的 mime 是 `audio/mpeg-L2`，并以 `MediaCodecList` 实际类型为准 | S1/S2 实测 |
| W-S3-1 | §8.3 | 确认「Canvas 自绘 + 二维虚拟化 + 640×… 缓存上限 512」成立；补「每帧绘制块数」「缓存命中率」两项埋点 | S3 60 fps vs 4.3 fps |
| W-S3-2 | §8.5 | 帧率达标线建议改为「60 Hz 下无掉帧（p95 ≤ 17 ms）」或写明 16.7 ms 的容差来源 | 实测 p95 16.72 ms |
| W-S5-1 | §7.5 | 维持单实例复用；补一句「重建代价是内存 ~1.25 MB/次，建实例 ~4 ms，故复用为内存/状态考量」 | S5 A/B 对照 |
| W-S6-1 | §8.6 | 维持「只预加载频道表 + 上次频道元数据」；P1 复测需含频道表解析与 Room 初始化 | S6 夹具不含这些 |
| W-S1-3 | §7.1 | **新增建议**：把「20 条样本」升级为「全量 658 条 Media3 复测」作为 P1 入口条件，用于补齐 Media3−MediaPlayer 差值 | §8 限制 |

## 8. 未解决与限制（诚实清单）

1. **「出声」是路径级证据，不是听感证据**：本轮无人耳/无麦克风，证据是「音轨选中 + 位置前进 + HAL 活动输出 + underruns 0」。建议 P1 安排一次人工听音确认（尤其 AC3/EAC3 直通到音箱解码的听感）。
2. **HDMI ARC / AVR 透传未测**：本机只挂 `builtin_speaker`，没有 AVR 设备；「透传到功放」这条真实使用路径需要在有 AVR 的现场复测。
3. **AC3/EAC3 样本不是直播源**：基线仅有的 2 条 AC3 频道今天已失效（分片主机不可达），改用公开测试向量 + 本机 HTTP 分发；**未验证「HLS/TS 封装 + 直播边沿」下的 AC3 透传**，只验证了裸 ES 的 AC3/EAC3 路径。
4. **Media3 − MediaPlayer 差值只在小样本上为 0**：`validated.m3u` 本身是 MediaPlayer 阳性集合，20 条样本无法暴露细小差异；全量复测建议见 W-S1-3。
5. **S3 未覆盖数据层**：真实 EPG 加载（`EpgRepository` 分页、`StaticLayout` 缓存命中分布）未测；`naive` 模式是刻意的病态对照，不代表任何真实实现。
6. **S6 是夹具级下限**：未含频道表解析/Room/台标，见 §6 限制。
7. **长稳未测**：`docs/02 §8.5` 的「播放 10 min 后内存」属 P1；本轮只拿到单次序列的 PSS 增量。
8. **未跑 lint / 单测**：夹具是一次性代码（`spike/`），本次未接 CI；生产模块一行未改。

## 9. 复现清单

```bash
# 0) 环境
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
ADB="$HOME/Library/Android/sdk/platform-tools/adb"; TV=<tv-ip>:5555

# 1) 生成样本（含 Mac 侧连通性预检；产物含真实 URL，已被 .gitignore 忽略）
python3 spike/tools/make-samples.py

# 2) 构建 + 签名（不同包名，不影响生产包）
GRADLE=$(ls -d ~/.gradle/wrapper/dists/gradle-8.13-bin/*/gradle-8.13/bin/gradle)
$GRADLE -p spike --console=plain assembleRelease
spike/tools/sign-release.sh          # 用仓库 Release 密钥签名（口令取自 iptv-player/local.properties）
$ADB install -r spike/app/build/outputs/apk/release/app-release-spike.apk

# 3) 设备能力探测（S2 的前置事实）
$ADB shell am start -n ilab.iptv.player.spike/ilab.iptv.spike.SpikeHomeActivity -e probe true -e finish true

# 4) S1 / S2(直播样本) / S5 / S3 / S6 全量序列（结果 JSON 落在设备的
#    /sdcard/Android/data/ilab.iptv.player.spike/files/，脚本会拉回 $EVIDENCE_DIR）
EVIDENCE_DIR=<evidence-dir> spike/tools/run-spikes.sh all

# 5) S2 的 AC3/EAC3 本地样本（先用 python3 -m http.server 8765 分发样本目录）
EVIDENCE_DIR=<evidence-dir> spike/tools/run-local-audio.sh <samples-dir>
```

**结果文件清单**：`s1_s1a.json`、`s2_s2aac2.json`、`s2_s2mp23.json`、`s2_s2ac3local_sw.json`、`s2_s2ac3local_pt.json`、`s2_s2eac3local_sw.json`、`s2_s2eac3local_pt.json`、`s5_s5reuse.json`、`s5_s5recreate.json`、`s3_s3virtual.json`、`s3_s3naive.json`、`device.json`，以及 `audioflinger_*.txt`（音频路径原始 dump）与 `s1/s2` 的 logcat 摘要。
