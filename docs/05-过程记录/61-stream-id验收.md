# 61 · stream id 验收（卡 STREAM-ID-QA-1 / 独立验收）

> 卡：**STREAM-ID-QA-1**（独立验收 Kevin 的 `STREAM-ID-1` 修复；作者不自签，本卡当裁判）
> 执行：temp `worker-meredith-streamid-qa`（Meredith，codex，**独占电视**）｜ 日期：2026-09-22 → 09-23（取证跨零点）
> 验收对象：主线 **`3afbc31`**（`fix(refresh): STREAM-ID-1 —— 浅校验/评分按内存 stream id 取行的错位`，god 集成）
> 设备：Sony BRAVIA 4K VH21 / Android 12（API 31）｜ `<TV-LAN-IP>:5555`（开工探活 `device`，全程在线，无掉线）
> 边界：**未改功能代码**（含未改 `alignStreamIds()`、未做「关掉修复」的对照包）、未改 `docs/01–04`、未 commit / push；电视与 Mac 地址按 `<TV-LAN-IP>` / `<MAC-LAN-IP>` 脱敏
> 证据：`$AGENT_DIR/evidence/`（`r1.db` / `r2.db` / `r3pre.db` / `r3.db`、`app-debug-20260922.jsonl`、`app-debug-20260923.jsonl`、`device-full.log`、`lansrc/`、`shots/`）

---

## 0. 结论（先看这段）

**通过。** 在主线 `3afbc31` 上，真机三轮刷新的 Room 对账逐条成立：**每个本轮被取过的 stream 恰有 1 条 `play_history` 且指向它自己那一行；本轮没被取过的行零新戳；用户数据不丢；刷新仍落库；刷新后可起播。** 三轮的「判据行」分别是 `190..219`、`220..222`、`223..224` —— 而每一轮候选表里的 stream 数只有 30 / 6 / 8 台，**内存 id 只可能是 1 起的小数字**；戳却一条不差地落在库 id 上、`1..8` 行零戳。这就是本卡要证的那件事。

| 判据（派单口径） | 结果 | 一句话证据 |
|---|---|---|
| 1 构建 / 装包 / `pm clear` | ✅ | `./gradlew --offline assembleRelease` BUILD SUCCESSFUL（637 tasks）；自出 release 包 `8,756,189 B` sha256 `e3813f657281322d…`；装包 + `pm clear` + 冷启动到向导第 1 步 |
| 2 自持源（真实 HLS） | ✅ | Mac `python3 -m http.server 8123 --directory …/lansrc`；ffmpeg 生成**真 HLS**（主清单 → 子清单 → 2 个真分片，H.264 baseline 640×360 + AAC）；Mac 服务器日志留下电视 `GET /round1.m3u 200` + 30×`HEAD/GET <sNN>/master.m3u8 200` + `GET /media/variant.m3u8 200` + `GET /media/seg1.ts 200` |
| 3 两轮刷新制造 id 差异 | ✅ | 同一订阅 URL（`/qa-source.m3u`）换内容：R1 = 30 台新频道；R2 = 3 台**新**（源里排在前面）+ 3 台**R1 已有**；R3（回归用）= 2 台新 + 6 台已有。三轮 `SRC_REFRESH_DONE` 全 `phase=DONE` |
| 4 对账（核心） | ✅ | ① 被取过的行**每行恰 1 条** `play_history` 且 `stream_id` 就是它自己（`≠1` 的行数 = **0**）；② 本轮未取过的行**零新戳零新判据**（R2/R3 与上一份库逐行比对，改动行 = **0**）；③ `health(id).attempts` = 该行 `play_history` 条数（同一条 SQL 口径），检查行全 = 1；④ 孤儿 stream **0**、孤儿 `play_history` **0**、`PRAGMA foreign_key_check` **空**、`integrity_check` **ok** |
| 5 回归 | ✅ | 落库仍在增长（channels/streams `189 → 219 → 222 → 224`）；用户数据（收藏 / 改名 / 隐藏 / 频道号）刷新前后**逐条未变**；刷新后起播 `PLAY_FIRST_FRAME{costMs:359, engine=media3, vcodec=video/avc, 640x360}` |
| 6 收尾还原 | ✅ | 见 §10：代理三项空、`pm clear`、重装主线 release 包、输入法还原、`stayon false`、回桌面、停 Mac `http.server` 与 `adb logcat` |

**缺陷：本轮未新立 S0–S2 缺陷。1×S3 观察**（§9-a：浅校验阶段的 fresh-skip 不落事件 ⇒「本轮跳过了哪些」只能从计数差反推，取证时容易被当成「少了判据」）。作者 `60` 报告的旧实现对照片段，本卡**未复跑**（不动功能代码，见 §6.2），改用「内存 id 上界 + 库 id 落点」的直接论证。

---

## 1. 环境与前置

| 检查 | 结果 |
|---|---|
| 电视探活 | `adb connect <TV-LAN-IP>:5555` → `already connected` + `device`（`BRAVIA_4K_VH21`）；全程无掉线 |
| 代理（开工第一件事） | `http_proxy=`**`:0`**、`global_http_proxy_host=`**空**、`global_http_proxy_port=`**`0`** ✅ |
| 内置源可达性 | 17 个内置源本轮**全部 `SRC_FETCH_FAIL{TIMEOUT, costMs≈30300}`**（与 56/57/59 同因）⇒ 我的订阅源是**唯一**贡献频道的源，隔离干净 |
| 首启播种 | `pm clear` 后冷启动：`SRC_PARSE_OK{provider=snapshot-1, entries:189, channels:189, streams:189}` ⇒ 库里**先有 189 行**（id 1..189）——这正是「内存 id 与库 id 天然不重合」的真机常态 |
| 输入法坑 | 电视的索尼 T9 输入法会把 `adb shell input text` 拆成预测词（`http://192.168…` 被改成 `http://.w.a.m.t…`）⇒ 用 `adb shell ime disable com.sony.dtv.ime.chww/com.sony.dtv.ime.SIMEService` 后再 `input text` 才是字面量；收尾已还原（§10） |

---

## 2. 构建与装包

| 项 | 值 |
|---|---|
| 基线 | worktree `worker-meredith-streamid-qa`，`HEAD 3afbc31`（= main），`git status` 仅 `local.properties`（gitignore，从主仓复制） |
| release | `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home ANDROID_HOME=~/Library/Android/sdk ./gradlew --offline assembleRelease` → **BUILD SUCCESSFUL**；`app-release.apk` **8,756,189 B** sha256 `e3813f657281322d…` |
| debug | 同 commit `assembleDebug` → `app-debug.apk` **11,541,235 B** sha256 `c0afee1b2df82c58…` |
| 两包都含修复 | 两个 APK 的 `classes*.dex` 字符串表里都能查到 `alignStreamIds`（release 3 处 / debug 5 处）⇒ 验收对象与真机取证对象**同一份功能代码** |
| 装包 | release 包 `adb install -r` + `pm clear`（判据 1 原文口径）；随后为了 DB 取证换 debug 包（`adb uninstall` → install debug → `pm clear`），收尾换回 release 包 |

**DB 取证为什么用 debug 包（口径说明，与 59 号一致）**：release 包不可 `run-as`，本机 `ro.build.type=user`、`ro.debuggable=0`、`adb root` 不可用 ⇒ 读不到 `/data/data/ilab.iptv.player/databases/iptv.db`。本卡的三轮对账跑在 **debug 包（同 commit、同功能代码）** 上；release 包另有**整轮刷新 + 起播**实测（§7）。两个包的功能代码同一份（上行 dex 证据），差异只有可调试性。

---

## 3. 自持源（真实 HLS，不是占位字节）

```text
$AGENT_DIR/evidence/lansrc/
  media/variant.m3u8        媒体清单（#EXTINF 4.0 ×2 + #EXT-X-ENDLIST）
  media/seg0.ts seg1.ts     真分片（ffmpeg hls muxer）
  s01..s30/master.m3u8      30 台「QA流A01..A30」（主清单：#EXT-X-STREAM-INF CODECS/RESOLUTION → media/variant.m3u8）
  t01..t03/master.m3u8      3 台「QA新B01..B03」
  u01..u02/master.m3u8      2 台「QA新C01..C02」
  qa-source.m3u             订阅源（每轮换内容，URL 不变）
```

分片是真视频：`ffmpeg -f lavfi -i testsrc2=size=640x360:rate=25 -f lavfi -i sine=frequency=1000 -c:v libx264 -profile:v baseline -pix_fmt yuv420p -c:a aac -f hls -hls_time 4`（约 450 KB/片）。

Mac 服务器实测访问（`python3 -m http.server` 实时 stdout 摘录，R1/release 轮）：

```text
<TV-LAN-IP> - - [22/Sep/2026 23:47:23] "GET /round1.m3u HTTP/1.1" 200 -
<TV-LAN-IP> - - [22/Sep/2026 23:47:54] "HEAD /s01/master.m3u8 HTTP/1.1" 200 -      ← 浅校验 30 台全 8 路并发
… 30× HEAD + 30× GET master.m3u8 200 …
<TV-LAN-IP> - - [22/Sep/2026 23:47:54] "GET /media/variant.m3u8 HTTP/1.1" 200 -    ← 深探：主清单 → 子清单
<TV-LAN-IP> - - [22/Sep/2026 23:47:54] "GET /media/seg1.ts HTTP/1.1" 200 -         ← 深探取到末片
```

---

## 4. 三轮刷新（debug 包时间线）

| 轮 | 源内容 | 会话 | 窗口（设备 epoch ms） | `SRC_REFRESH_DONE` |
|---|---|---|---|---|
| R1 | 30 台新（`QA流A01..A30`） | `refresh-c589` | `1790092249277 → 1790092405262` | `channels:30, selectedPrimary:30, selectedBackup:0, unavailable:0, deepOk:30, deepFail:0, deepEnabled:true, elapsedMs:155986` |
| R2 | 3 台新（`QA新B01..B03`，源里**在前**）+ 3 台 R1 已有 | `refresh-61c5` | `1790092440164 → 1790092592190` | `channels:6, selectedPrimary:6, unavailable:0, deepOk:3, deepFail:0, elapsedMs:152029` |
| R3 | 2 台新（`QA新C01..C02`）+ 6 台已有 | `refresh-a400` | `1790092787641 → 1790092940155` | `channels:8, selectedPrimary:8, unavailable:0, deepOk:2, deepFail:0, elapsedMs:152514` |

**自洽核对**：`channels` = `deepOk + 被新鲜度跳过的台数`（R2：6 = 3 + 3；R3：8 = 2 + 6），`unavailable` 全 0、`interrupted` 全 null。R2/R3 里「已有那几台」是 24 h healthy TTL 内的重复行 ⇒ 按 `docs/02 §6.1` 增量语义**跳过**（不重探、不重戳），R3 的返回也验证了这点。

`SRC_SELECT`（库 id）：R1 = `190..219`；R2 = `220,221,222`（新）+ `190,191,192`（已有，**保住原 id**）；R3 = `223,224`（新）+ `220..222,190..192`（已有）。`device-full.log` 全量统计 74 条 `SRC_SELECT`，**`primary == channelId` 100% 成立**（`min 190 / max 224`）。

---

## 5. 对账：逐条判据与 SQL 结果

导出方式（含 `-wal`/`-shm`，本地同基名）：

```bash
for f in iptv.db iptv.db-wal iptv.db-shm; do adb exec-out run-as ilab.iptv.player cat databases/$f > rN.${f#iptv.}; done
```

### 5.1 R1（判据①，基线：库里只有快照 189 行）

```text
channels=219  streams=219  play_history=30            （快照 189 + 本轮 30）
本轮被戳的行（last_check_at ≥ 窗口起） = 30          全部是 id 190..219（= Mac 源那 30 条 url）
play_history（窗口内） = 30   min(stream_id)=190  max(stream_id)=219
每行「自己的判据 ≠ 1」的行数 = 0                       ← 判据①
快照 1..189：被戳行 = 0，play_history = 0              ← 判据②
被戳但 url 不是 Mac 源的行 = 0；Mac 源行未被戳的 = 0
play_history.stream_id ≠ stream.id = 0；play_history.channel_id ≠ stream.channel_id = 0
orphan_streams=0  orphan_play_history=0  foreign_key_check=空  integrity_check=ok
```

### 5.2 R2（核心：内存 id 与库 id 必然不重合）

```text
channels=222  streams=222  play_history=33            （+3 行 / +3 判据）
本轮被戳的行 = 3                                       id 220/221/222 = QA新B01/B02/B03（t01..t03）
play_history 31/32/33 → stream_id 220/221/222，channel_id 220/221/222，result=OK   ← 判据①
本轮新增且落在 stream_id ≤ 219 的 play_history = 0    ← 判据②（旧 30 行一条没被写）
与 r1.db 逐行比对：id ≤ 219 的 last_check_at/last_ok_at/fail_count/score/last_error 改动行 = 0
orphan_streams=0  orphan_play_history=0  foreign_key_check=空
```

### 5.3 R3（回归 + 用户数据 + 再证判据①②）

```text
channels=224  streams=224  play_history=35            （+2 行 / +2 判据）
本轮被戳的行 = 2                                       id 223/224 = QA新C01/C02（u01/u02）
play_history 34/35 → stream_id 223/224，result=OK      ← 判据①
本轮新增且落在 stream_id ≤ 222 的 play_history = 0；与 r2.db 逐行比对改动行 = 0   ← 判据②
每行「自己的判据 ≠ 1」的行数 = 0（本窗口）
orphan_streams=0  orphan_play_history=0  foreign_key_check=空
```

### 5.4 判据③：`health(id).attempts` 与浅校验自洽

代码链（只读核对，未改）：`RefreshSourcesUseCase.shallowValidate` 对每个被探的 stream 调 `streamRepository.recordOutcome(stream.id, …)`；`RoomStreamRepository.recordOutcome` 既写健康列（`last_ok_at`/`last_check_at`/`fail_count`/`last_error`）又 `playHistoryDao().insert(...)`；`RoomStreamRepository.health(id)` 的 `attempts/failures` 直接来自 `streamDao.healthCounts(streamId)`（`play_history` 按 `stream_id` 计数）。

⇒ 可判定的自洽式是 **「被探行：`play_history` 条数 = 1；`last_ok_at = last_check_at`；`fail_count = 0`」** 与 **「未被探行：条数 = 0 且健康列不变」**。三轮逐行断言：`play_history` 条数 ≠ 1 的行 = **0**；被探的 35 行 `last_ok_at == last_check_at`、`fail_count = 0`、`score = 95`；未探行全 0 改动。失败的浅校验本卡**未制造**（自持源全 200），失败路径由作者单测覆盖（§8），本轮不臆断。

---

## 6. 判别力：为什么这不是「跟着实现跳舞」

### 6.1 直接论证（真机数据本身）

`ChannelMapper.toDomain()` 的 `nextStreamId` 每次装载**从 1 起**（`ChannelMapper.kt:28` `var nextStreamId = 1L`，`:35 id = nextStreamId++`）。于是：

- R2 的候选表只有 **6 台** ⇒ 任何「按装载序号」的 id 必然落在 `1..6`；
- R3 只有 **8 台** ⇒ 落在 `1..8`。

而 R2/R3 的判据实际落在 **220..222 / 223..224**，且 `1..8` 行（快照头几台）在 R3 结束时的 `last_check_at/last_ok_at/score` **全为空/0**。⇒ 本次写入用的是**库 id**，不是内存序号 id；若还是旧实现，这 3+2 条判据会精确打到 `1..6` / `1..8` 那几台快照频道上，而 `220..224` 会是「有行、没判据」。这正是 60 号在旧实现对照轮里拍到的形态（判据 `stream_id` 全在 1..562、195 行有戳无判据），本卡用**同一条 SQL** 在修复版上得到 0。

### 6.2 为什么本卡没再出「关掉修复」的对照包

派单边界写明「不改功能代码，发现缺陷→记录 + 报 god，不顺手修」。临时改回 `alignStreamIds()` 直通属于改功能代码，且 60 号已用该手法出过对照（`evidence/prefix.db`）。本卡改用 §6.1 的**上界论证 + 逐行比对**：它不依赖任何代码改动，判别方向与旧实现相反，且失败时会立刻显形（判据落到 `1..8`、`220..224` 无判据）。

---

## 7. 回归（判据 5）

| 项 | 结果 |
|---|---|
| 刷新仍落库 | `189 → 219 → 222 → 224`（channels / streams 同步增长，`DB_UPSERT` ×2/轮；无 `DB_FAIL`，`foreign_key_check` 空） |
| 用户数据不丢 | 在 **debug 包**上对 `QA新B03` 做「加入收藏 + 重命名 `QARENAMED` + 改频道号 `555`」、对 `QA新B02` 做「隐藏」，导出 `r3pre.db` 记录，再跑 R3 刷新后逐条比对：`220` `{display_name:NULL, channel_no:101, favorite:0, hidden:0}`、`221` `{…channel_no:102, hidden:1}`、`222` `{display_name:QARENAMED, channel_no:555, favorite:1}` **三项+隐藏全部原样**（界面行同时显示 ★ 与「名」标记） |
| 刷新后可起播 | **release 包**轮（判据 1 的包）：搜索 `qa` → 打开 `QA流A01` → `PLAY_FIRST_FRAME{costMs:359, engine=media3, vcodec=video/avc, acodec=audio/mp4a-latm, w=640, h=360, audioPath=PCM_DECODED}`，截图里 testsrc2 时间码在走（`shots/play-release-QA流A01.png`）；同一轮向导第 3 步报「当前有 219 个频道，其中 219 个可播放」 |

---

## 8. 单测与质量门（本卡自己跑过）

| 项 | 结果 |
|---|---|
| `:core:data:testDebugUnitTest --tests '*RefreshPersistRoomTest*'` | **7 / 7 通过、0 失败**（含本卡的 3 条：`the id the pipeline mints in memory is not the stored stream id`、`a shallow verdict lands on the refreshed stream's own row, not on a pre-existing one`、`a shallow failure marks the refreshed stream's own row and leaves the stored rows alone`，以及 `a repeated refresh is idempotent and keeps the stored ids`） |
| `tools/ci/sensitive-info-guard.sh` | `result: OK — 0 unallowlisted hit(s)`（37 条既有 allowlist 命中，与本轮无关；本卡新增文档按占位符书写） |

---

## 9. 缺陷与观察

**9-a（S3，观察 / 可观测性）**：浅校验阶段的「新鲜度跳过」**不落事件**。`shallowValidate` 里 `if (isFresh(stream, nowMs)) { skipped += stream; continue }` 没有日志；只有预算耗尽与深探阶段的跳过才发 `SRC_REFRESH_SKIP`。后果：R2/R3 日志里没有任何一行说明「哪几台被跳过」，取证时只能从 `SRC_REFRESH_DONE.channels` 与 `deepOk` 的差值反推（本卡就是这么推的），容易被误读成「判据丢了」。建议（不属本卡，未改代码）：fresh-skip 也发一条 `SRC_REFRESH_SKIP{reason:fresh, ids:[…]}`，或在 `SRC_REFRESH_DONE` 加 `freshSkipped` 计数。

**9-b（非缺陷，口径确认）**：R2/R3 里「已在库且新鲜」的频道被跳过 ⇒ 它们的 `play_history` 不会每轮增加，库里**全局每行最多 1 条**判据（本次样本 `stream_id` 分布：`190..224` 各 1 条）。这与 `docs/02 §6.1` 的增量语义一致，也与 59 号「连刷不翻倍」的结论一致。

**9-c（记录）**：本卡真机取证为隔离变量动过库与源（三轮不同订阅内容、手工编辑 2 台频道的用户字段）；收尾用主线 release 包 + `pm clear` 全部清除（§10）。

---

## 10. 设备状态还原清单（收尾实测）

| 项 | 动作 | 收尾实测 |
|---|---|---|
| 代理 | 开工即空，本轮未改；收尾复核 | `http_proxy=:0`、`global_http_proxy_host=`（空）、`global_http_proxy_port=0` |
| 应用包 | 卸 debug 包 → **重装本 worktree 自出的主线 release 包**（`3afbc31`，8,756,189 B）→ `pm clear` | `package:ilab.iptv.player` 在机、数据目录已清空 |
| 输入法 | `ime enable com.sony.dtv.ime.chww/com.sony.dtv.ime.SIMEService` + `ime set …` | `default_input_method = com.sony.dtv.ime.chww/com.sony.dtv.ime.SIMEService` |
| 常亮 | `svc power stayon false` | 已关（电视恢复自动休眠） |
| 桌面 | `input keyevent KEYCODE_HOME` | 前台 = 电视主屏 |
| 临时进程 | 停 Mac `python3 -m http.server 8123` 与 `adb logcat` 抓取 | 已停（端口不再监听；`device-full.log` 已落盘到 `$AGENT_DIR/evidence/`） |
| 试验数据 | 订阅 1 条、QA 频道 35 台、35 条 `play_history`、临时 HLS 源 | 随 `pm clear` 消失（`lansrc/` 留在 `$AGENT_DIR/evidence/` 供复跑） |

---

## 11. 变更文件

本卡是**验收**卡：除本记录与 `00-索引与模板.md` 的一行外，**未改任何代码/测试/资产**（`git status` 只有本文件与索引的新增行，加上 gitignore 的 `local.properties`）。未 commit / 未 push。
