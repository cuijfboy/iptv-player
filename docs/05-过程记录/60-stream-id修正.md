# 60 · stream 自身 id 错位修正（卡 STREAM-ID-1）

> 卡：**STREAM-ID-1**（58 号 §5.1 的遗留项：「浅校验/评分仍按**内存 stream id** 取行」；作者不自签，验收归后续独立 QA 轮）
> 执行：temp `worker-kevin-stream-id`（Kevin，codex，**独占电视**）｜ 日期：2026-09-22
> 基线 worktree `/Users/jeffrey/temp/iptv-player-workspace/worktrees/worker-kevin-stream-id`，HEAD `9a9b6ae`（= main，含 `73d19fd` 刷新落库修复）
> 设备：Sony BRAVIA 4K VH21 / Android 12（API 31）｜ `<TV-LAN-IP>:5555`（开工探活 `device`）
> 边界：未 commit / push；未改 `docs/01–04`；未动快照资产；电视与 Mac 地址按 `<TV-LAN-IP>` / `<MAC-LAN-IP>` 脱敏
> 证据：`$AGENT_DIR/evidence/`（`pre.db` / `post.db` / `post2.db` / `post3.db` / `prefix.db`、`device-full.log`、`lansrc/`、截图）

---

## 0. 结论（先看这段）

**修好了，真机两轮对照都拍到了。** 刷新管线的 stream id 是 `ChannelMapper` 按**本次装载序号**发的（每次从 1 起），而落库是按 `(channel_id, url_hash)` upsert、**id 由库发**；浅校验的 `recordOutcome(stream.id)` 与评分的 `health(stream.id)` 拿的是内存 id ⇒ 库里已有行时**打到别人那行**（健康戳 + `play_history` 归属错行），内存 id 越界时静默空操作。修法**不动冻结端口**（`docs/02 §4.3` 一行未改）：在 `:core:data` 的唯一写入接缝 `CatalogSink` 上加**只读**的 `resolveStreamIds()`，刷新写完 stream 后把每个流的 id 换成库 id，再进浅校验/评分。

| 项 | 结果 |
|---|---|
| 根因（一句话） | 浅校验/评分按**内存 stream id** 取行，而库里那行的 id 是 upsert 时由 Room 发的 ⇒ 健康戳与 `play_history` 记到别的流上 |
| 修法 | `CatalogSink` 加只读 `resolveStreamIds(streams) -> Map<内存id, 库id>`（键 = §5.1 `(channel_id, url_hash)`）；`RefreshSourcesUseCase` 在 `persistMergingHealth` 之后 `alignStreamIds()` 换 id 再进浅校验/评分 |
| 冻结区 | **未改**（`docs/02 §4.3` 端口签名、§5.1 表结构都不动；接缝属 `:core:data` 内部实现，与 58 号加 `upsertChannels()` 同性质） |
| 离线复现 | `RefreshPersistRoomTest`「库里先有一批 stream，再刷一批」⇒ 断言内存 id = 1 而 1 是**快照那一行**，按内存 id 记录会把戳打到快照流上（真 Room + 生产 `RoomStreamRepository`） |
| 离线证明 | 新增 3 条：成功路径（每个新流各 1 条 `play_history` 且指向**自己那行**、`health(id).attempts==1`、旧行零污染）、失败路径（失败判据落在自己那行，命 58 号 §5.1 的「失败不改表」）、错位复现。**把修复临时关掉，成功/失败两条会红** |
| 真机（自持源，修复版） | `SRC_REFRESH_DONE{channels:3, selectedPrimary:3, backup:0, unavailable:0, deepOk:3, deepFail:0, elapsedMs:151794}`；导出 Room：新 stream 行 **286/287/288** 带 `last_ok_at`/`score=95`，`play_history` **97/98/99 → 286/287/288（channel 282/283/284）**；孤儿 0、`foreign_key_check` 空、**旧行 1–189 零污染** |
| 真机（旧实现对照） | 同一条链把修复关掉再跑：**561 条判据的 `stream_id` 全在 1..562（内存 id）**，其中 **287 条落在本次刷新之前就存在的行**上；我在 Mac 上的两批 LAN 行（283/284/285，本轮**根本没取过**的 URL）也被打上戳与 `play_history`；**本次写出的 196 行（id>562）有戳却没有自己的判据**（修复版同一查询 = 0） |
| 质量门 | `./gradlew --offline check` **BUILD SUCCESSFUL**；三道静态守卫：`tv-focus-audit` OK（10 屏）、`notification-channel-audit` OK（284 源文件）、`sensitive-info-guard` 只剩 **2 条既有**未 allowlist 命中（`docs/05/05-*.md`、`20-*.md` 的私网示例地址），我的文件 0 命中 |

---

## 1. 根因

### 1.1 现象（58 号 §5.1 记的，本卡立项依据）

一次 18 源刷新 `streams_checked=1822`、深探通过 331、不可用 642，可库里的 `play_history` 却有 1600+ 行，且评分的 `stability` 维度明显偏保守 —— 健康数据「有，但归属不对」。

### 1.2 代码链

1. `ChannelMapper.toDomain()`：`stream.id = nextStreamId++`（**每次装载从 1 起**，类注释自己写明「ids are not identity across refreshes」）；
2. `StreamRepository.upsertAll()` → `StreamDao.upsertAll()`：按 `UNIQUE(channel_id, url_hash)` 找行 —— 命中就 `update(stream.copy(id = existing))`，没命中就 `insert(stream.copy(id = 0))`。**写入用的是库 id，但调用方手里那份 `Stream` 的 id 一个字都没改**；
3. `RefreshSourcesUseCase.shallowValidate()`：`streamRepository.recordOutcome(stream.id, …)` → `streamDao.getById(内存id)` ⇒ 命中「某个别的流」时把 `last_ok_at`/`last_check_at`/`fail_count` 与 `play_history` 行写上去；越界时 `?: return` 静默丢弃；
4. `runBackHalf()` 评分：`streamRepository.health(stream.id)` 同理 ⇒ `stability` 因子的输入是别人的健康度。

**一句话**：58 号修的是 **channel 侧**同一处错位（`upsertChannels` 把内存 channel id 换成库 id）；本卡修 **stream 侧**。

### 1.3 为什么最终 Persist 没救回来

`runBackHalf` 的最后一次 `upsertAll(updated)` 是**按 `(channel_id, url_hash)` 键**写的，落行本身正确 —— 所以「刷新→落库→起播」这条链（58 号的判据）一直成立。错的是**浅校验那一次**：`recordOutcome` 是**按 id** 的，它的健康列写入与 `play_history` 一并错位，且不会被最后那次键写修正（键写只覆盖库 id 无关的列，`play_history` 更是只增不改）。

---

## 2. 修法（不动冻结端口）

### 2.1 接缝加一个**只读**方法

`core/data/.../store/CatalogSink.kt`（58 号加 `upsertChannels()` 的那个接缝）新增：

```kotlin
suspend fun resolveStreamIds(streams: List<Stream>): Map<Long, Long>   // 内存 stream id -> 库 stream id
```

键就是 §5.1 的 upsert 身份 `(channel_id, url_hash)`，所以「刚 upsert 过的流」必然能解回它落的那一行（既有行保 id，新行拿到 Room 发的 id）。**只读、不写、不删**。

- `RoomCatalogWriter.resolveStreamIds`：一次 `streamDao.allIdentities()` 建 `(channel_id,url_hash) -> id`，再在 Kotlin 里映射（不是每行一次查询 —— 1–2k 流的刷新那样要查 1–2k 次）；存储异常按 `docs/02 §11` 降级：记 `DB_FAIL` 返空 map；`CancellationException` 原样上抛。
- `ChannelStore.resolveStreamIds`（测试双）：内存存储保留的是原对象，所以是**恒等映射** —— 这也解释了为什么 58 号那批内存测试看不到这个 bug（`StateFlow` 里 id 就是原 id）。

### 2.2 管线里换 id

`RefreshSourcesUseCase` 在 `persistMergingHealth(resolved)` **之后**插一步：

```kotlin
val persisted = alignStreamIds(persistMergingHealth(resolved))   // 内存 id -> 库 id
```

`alignStreamIds()`：解析不到的流**丢弃**（库里没这行 ⇒ 没有可戳的目标，留着内存 id 恰好就是这次要消灭的错位）；接缝返空（存储读失败/写失败，已由接缝记 `DB_FAIL`）⇒ 本轮不写任何健康，而不是猜一行。

浅校验 / 深探 / 评分 / 甄选 / 去重 / `isFresh` 断点续跑 / 预算 / 取消 / 单频道 `ON_DEMAND_SINGLE_CHANNEL` 路径**一行未动**（单频道路径的候选来自 `streamRepository.candidates()`，本来就是库 id，不受影响）。

---

## 3. 测试

### 3.1 `core/data/.../refresh/RefreshPersistRoomTest.kt` 新增 3 条（Robolectric + 真 Room + 生产 `RoomStreamRepository`）

| 用例 | 断言 |
|---|---|
| `the id the pipeline mints in memory is not the stored stream id` | **复现**：库里先有 1 行 stream（id=1）；后续一次刷新对**无关频道**装载出的第一个流，内存 id 也是 **1**；按内存 id `recordOutcome(1)` ⇒ 戳落在**快照那行**、`play_history.channel_id` 也是快照频道 |
| `a shallow verdict lands on the refreshed stream's own row, not on a pre-existing one` | 刷新后 2 条新流各 1 条 `play_history`，且 `stream_id`/`channel_id` 就是它们自己的行；`health(新id).attempts == 1`（= 评分读到的 health 与写入一致）；旧行的 `last_check_at`/`last_ok_at` 仍为 null、`health(旧id).attempts == 0` |
| `a shallow failure marks the refreshed stream's own row and leaves the stored rows alone` | 失败判据落在自己那行（`fail_count=1`、`play_history.result != OK`）；旧行 `fail_count` 与戳**一个字未动**（失败不改表） |

**判别力验证**：把 `alignStreamIds()` 临时改成直通（= 旧实现），后两条立刻变红、复现那条仍绿 —— 说明这两条**真的**在测这个 bug，而不是跟着实现跳舞。

### 3.2 回归

- `:core:data:testDebugUnitTest`：**198 条 / 0 失败**（58 号那批 + 本卡 3 条；`RefreshPersistRoomTest` 7 条全绿）。
- `./gradlew --offline check`：**BUILD SUCCESSFUL**（1281 tasks：506 executed / 650 from cache / 125 up-to-date；含 lint 与全模块单测）。
- 三道守卫：`tv-focus-audit` → **OK**（10 屏）；`notification-channel-audit` → **OK**（284 main 源文件）；`sensitive-info-guard` → 2 条未 allowlist 命中，**均为既有**（`docs/05/05-*.md`、`20-*.md` 的私网示例地址，与 59 号同一条），本卡改动文件 0 命中。

---

## 4. 真机取证（电视 + 自持局域网源）

### 4.1 环境

| 检查 | 结果 |
|---|---|
| 电视探活 | `adb connect <TV-LAN-IP>:5555` → `device`（BRAVIA_4K_VH21），全程在线 |
| 代理（开工第一件事，收尾复核） | `http_proxy=:0`、`global_http_proxy_host` 空、`global_http_proxy_port=0`、exclusion/pac 空 ✅（58 号 §4.1 的清理保持住了） |
| 自持源 | Mac `<MAC-LAN-IP>:8123`（`python3 -m http.server`）供 `qa-source.m3u` = 3 台新频道（`QA流A/B/C`，分组 `QA流ID`）指向 Mac 上的 HLS（**主清单 → 子清单 → 分片**，三级都真实可取） |
| 装包 | debug 包（可 `run-as`，用于导出 Room）；收尾换回主线 release 包 |
| 内置源 | 17 个内置源本轮仍全 30.3 s `TIMEOUT`；**但其中偶有一条会返回真实清单**（见 §4.4），真机取证要预留时间 |

### 4.2 触发与事件码（修复版，自持源）

入口 = 向导第 2 步（`am start -a ilab.iptv.player.action.WIZARD` → 第 1 步「使用内置源并继续」→ 第 2 步「重试更新」）：

```text
WORK_SCHEDULE   refresh run requested {name=refresh-manual, trigger=MANUAL, requiresNetwork=true}
WORK_RUN        refresh worker started  {trigger=MANUAL, attempt=0, constraints=network+batteryNotLow}
SERVICE_REFRESH_START  refresh foreground service started {notificationId=12023, result=ok}
SRC_REFRESH_START      refresh started {trigger=MANUAL, sources=18, budgetMs=2700000, session=refresh-b667}
SRC_SELECT      channel select {channelId=282, candidates=1, primary=286, backup=[], unavailable=false}
SRC_SELECT      channel select {channelId=283, candidates=1, primary=287, backup=[], unavailable=false}
SRC_SELECT      channel select {channelId=284, candidates=1, primary=288, backup=[], unavailable=false}
SRC_REFRESH_DONE refresh done {phase=DONE, channels=3, selectedPrimary=3, selectedBackup=0, unavailable=0,
                               deepOk=3, deepFail=0, deepEnabled=true, elapsedMs=151794, interrupted=null}
WORK_RUN        refresh worker finished {result=success, phase=DONE, okCount=3, failCount=0}
SERVICE_REFRESH_STOP   refresh foreground service stopped {result=success}
```

**自持源真的被取到**：Mac 服务器日志留下电视 `GET /qa-source.m3u 200`，以及三套 `HEAD /qa2/{a,b,c}.m3u8 200` → `GET 主清单 200` → `GET 子清单 200` → `GET 分片 200`（浅校验走 HEAD/GET，深探走主→子→分片）。

### 4.3 Room 对账（`evidence/post3.db`）

```text
channels=284   streams=288   play_history=99      （做过多轮，含 §4.4 的一轮；本卡的写入见下三行）
channel 282/283/284 = QA流A/B/C，**保住了原 id**（身份 = §5.1 (name_key, group_key)）
stream  286/287/288 = qa2/*，last_ok_at = last_check_at = 1790089953973，fail_count=0，score=95，disabled=0
play_history 97/98/99 → stream_id 286/287/288、channel_id 282/283/284
orphan_streams=0   foreign_key_check=0 违规   旧行 1–189 被打戳的行数 = 0
```

**自洽核对**：`streams_checked=3`（`okCount 3 + failCount 0`）= 浅校验 3 台 = 深探 `deepOk 3 + deepFail 0` = `selectedPrimary 3` ⇒ `unavailable 0`。**每一行判据都落在它自己那一行上，一行都没打到旧数据上。**

### 4.4 旧实现对照（同一台电视、同一条链，把修复临时关掉）

为把「错位」在真机上直接拍下来，我做了一个**只把 `alignStreamIds()` 改成直通**的 debug 包（其余一字未改）再跑一次刷新。这一轮内置源恰好有一条返回了真实清单（302 频道），浅校验 561 条：

```text
SRC_REFRESH_DONE {channels:302, selectedPrimary:115, selectedBackup:23, unavailable:187,
                  deepOk:137, deepFail:271, elapsedMs:869578}
WORK_RUN refresh worker finished {result=success, okCount=408, failCount=153}     ← 561 条判据
```

导出 `evidence/prefix.db` 对账（`START` = 本轮开始时刻 `1790090308000`）：

| 检查 | 旧实现 | 修复版（§4.3 同一查询） |
|---|---|---|
| `play_history` 的 `stream_id` 范围 | **1..562**（= 内存装载序号；库行是 289..757） | 就是本次写入的那几行 |
| 判据落在**本次刷新之前就存在**的行（id ≤ 288）上 | **287 条** | 0 |
| 本次写出（id > 562）、**有检查戳却没有自己判据**的行 | **195 条** | **0** |
| 我在 Mac 上、本轮**根本没取过**的 URL 行 283/284/285 | 被打上戳与 `play_history`：`283 → fail_count=2, last_error=NET_UNREACHABLE`；`284/285 → last_ok_at + OK`（**都是别的流的判据**） | 没被碰（它们只在真被探到的那一轮才变） |

一行一行看就清楚了：旧实现里 283/284/285 是**我这台 Mac 上的测试流**（`http://<MAC-LAN-IP>:8123/qa/*.m3u8`），本轮根本没去取它们，可它们拿到了三个「别的频道」的判据 —— 这正是「健康戳/`play_history` 归属错行」的真机形态；修复版同一场景为 0。

---

## 5. 变更文件

| 文件 | 变更 |
|---|---|
| `core/data/.../store/CatalogSink.kt` | 新增只读 `resolveStreamIds()`（含契约 KDoc：为什么按 id 取行会错位、为什么丢解不出的流） |
| `core/data/.../store/RoomCatalogWriter.kt` | 实现 `resolveStreamIds()`（一次 `allIdentities()` + Kotlin 映射；存储失败降级记 `DB_FAIL`） |
| `core/data/.../store/ChannelStore.kt` | 实现 `resolveStreamIds()`（内存路径恒等映射，行为与 Room 对齐） |
| `core/data/.../refresh/RefreshSourcesUseCase.kt` | 新增 `alignStreamIds()`；写入后把内存 stream id 换成库 id 再进浅校验/评分（浅/深/评分/甄选/预算逻辑未动） |
| `core/data/src/test/.../refresh/RefreshPersistRoomTest.kt` | **新增 3 条**（错位复现 / 成功路径 / 失败路径），文件 KDoc 补本卡的说明 |

`docs/02 §4.3`（冻结端口）、`§4.0 F6`、`docs/02 §5.1`（表结构）**均未改** —— 接缝是 `:core:data` 内部实现（58 号加 `upsertChannels()` 时也是同一条口径），因此**没有走冻结区提案**。

---

## 6. 未做 / 判断点

1. **内置源偶发返回真实清单**：17 个内置源通常全 TIMEOUT，但本轮遇到过一次成功（92 频道 / 93 流），使刷新从 ~150 s 变到 ~870 s，并让「本轮到底写了哪些行」的隔离变复杂。建议后续真机取证**固定用自持源**（本报告 §4.1 的 `lansrc/` 可直接复用），并把内置源的这类波动当作环境噪声记录。
2. **`alignStreamIds()` 丢弃解不出的流**：这是有意的（无行可戳），代价是「存储写失败」那一轮会报告 `streams_checked=0` 而不是把健康写到别人身上；存储失败本身已由接缝记 `DB_FAIL`（`docs/02 §11`）。若 god/arch 认为该轮应改用「整轮中止」语义，是一行改动 + 一条单测的事。
3. **未做**：定时刷新（`refresh-daily`/`SCHEDULED`）与长稳、`ON_DEMAND_SINGLE_CHANNEL` 真机复跑（该路径候选来自库，逻辑上不受本卡影响）；真机只覆盖 MANUAL。
4. **本轮真机为隔离变量动过库/源**：手工加过 1 条订阅行、也在对照轮里装过一版关掉修复的包；收尾用主线 release 包 + `pm clear` 清除，机上一律不留。
5. **验收归后续独立 QA 轮**：作者不自签；本报告只给复现、修法、单测与真机对账。

---

## 7. 设备状态还原清单（收尾实测）

| 项 | 动作 | 收尾实测 |
|---|---|---|
| 代理 | 开工即空，本轮未改；收尾复核 | `http_proxy=:0`、`global_http_proxy_host=`（空）、`global_http_proxy_port=0`、exclusion 空、pac 空 |
| 应用包 | 卸载 debug 包 → **重装主线 release 包**（本 worktree 自出，含本卡修复）→ `pm clear` | `package:ilab.iptv.player` 在机、flags 无 `DEBUGGABLE`、数据目录已清空 |
| 输入法 | `ime enable` + `ime set` 索尼输入法 | `default_input_method=com.sony.dtv.ime.chww/com.sony.dtv.ime.SIMEService` |
| 常亮 | `svc power stayon false` | `global stay_on_while_plugged_in=0` |
| 桌面 | HOME | 前台 = 电视主屏 |
| 临时进程 | 停掉 Mac 的 `http.server 8123` 与 `adb logcat` 抓取 | 已停（服务器 `Keyboard interrupt`，端口不再监听） |
| 试验数据 | 订阅 1 条、QA 频道 3 台、对照轮的 469 行等 | 全部随 `pm clear` 消失 |

---

## 8. 证据清单（`$AGENT_DIR/evidence/`，不入库）

- `pre.db`（刷新前：channels 189 / streams 189 / max stream id 189）、`post.db`（第 1 轮后 281/282/93 条 history）、`post2.db`（自持源坏路径那轮：新行 283–285，判据 94–96）、`post3.db`（**修复版干净那一轮**：新行 286–288，判据 97–99）、`prefix.db`（**旧实现对照轮**：561 条判据 / 195 行无自己判据 / LAN 行被误戳）
- `device-full.log`（全量 `adb logcat -v time`：四轮刷新的 `SRC_REFRESH_START/DONE`、`SRC_SELECT`、`WORK_RUN`、`SERVICE_REFRESH_*`）
- `lansrc/`（自持源：`qa-source.m3u` + `qa/`、`qa2/`、`qa3/` 三套 HLS 主/子清单与分片）+ Mac `http.server` 访问日志（电视 `GET /qa-source.m3u 200`、主/子清单/分片 200）
- `shot-wizard.png` / `shot-wizard2.png`（向导第 2 步）

_执行人：temp `worker-kevin-stream-id`（Kevin）。产出：修复 + 3 条单测 + 本报告。未 commit / 未 push，交 god 集成；验收归后续独立 QA 轮。_
