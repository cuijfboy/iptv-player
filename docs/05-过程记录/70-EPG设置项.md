# 70 · EPG 设置项（开关 + 新鲜度阈值落库 + 设置页入口） · 记录

> 卡：**EPG-SETTINGS-1**（P2：EPG 的两个常用控制面做成持久化设置，设置页可见可用；此前只有诊断面板的手动按钮）
> 执行：temp `worker-pam2-epg-settings`（Pam2，codex；按派工单**未用电视**）｜ 日期：2026-09-23
> 基线：`0c033a7`（= 分支点）｜ 分支 `agent/worker-pam2-epg-settings`｜ 提交：**未 commit**（god 集成）
> 说明：**验收归后续独立 QA 轮**；本记录只交代码 + 单测 +（未用电视的）口径与测试结论。

---

## 0. 结论

EPG 的**总开关**与**新鲜度阈值**现在是**持久化设置**（`SharedPreferences`，重启后生效），并在**设置页 → 刷新**组里可遥控操作：

- **总开关关** ⇒ 不调度（入队闸门先判）、不拉取（运行期决策 `SKIP(disabled)`）、不写库（runner 0 次调用），**已有节目数据保留**（关这个动作不删任何行）。
- **新鲜度阈值**（复用 `minIntervalMs` 语义）可调，**用遥控「确定」在预设间轮换**：30 分钟 → 1 h → 2 h → 3 h → 6 h，写穿持久层，下一次触发/运行即读新值。
- **语义逐字不破坏**：空 guide 的 `emptyRetryMs` 退避、目录空的 `DEFER(catalog_empty)`、以及 §6.3 的「地板 30 min / 天花板 6 h」形状全部保持不变——阈值只在 **[30 min, 6 h]** 之间夹取，不会把天花板抬高。

`./gradlew --offline check` 绿（1281 tasks，0 失败）+ 三道静态守卫绿。

## 1. 现状（改前）

`EpgRefreshSettings(enabled, minIntervalMs, emptyRetryMs)` 是 `:core:domain` 的**纯数据类**，在 `:app`
`EpgRefreshModule` 里**以默认值注入为单例**（`provideEpgRefreshSettings() = EpgRefreshSettings()`），
调度器 `EpgRefreshScheduler`、协调器 `EpgRefreshCoordinator`、面板端口 `EpgRefreshGateway` 各持一份
**构造期固定**的引用。后果：开关只在内存、无设置页入口、改值必须重启进程，且设置页完全没有 EPG 行
（只有诊断面板的「立即更新 EPG」按钮）——即派工单说的「当前只有诊断面板里的手动按钮」。

## 2. 改法（分层，最小面）

### 2.1 端口（`:core:domain`）

- 新增 `EpgSettingsStore`（`refresh/EpgSettingsStore.kt`）：`read(): EpgRefreshSettings` /
  `write(settings)`。**同步**接口（不是 suspend）——值与 `FirstRunStore` / `OverscanSettings` 同类：
  读发生在**冷启动路径**（入队闸门），suspend 会在决定前插一个 `await`。
- `EpgRefreshSettings` 加 `sanitized()` 与一组常量：`MIN_INTERVAL_MS = 30 min`、
  `MAX_INTERVAL_MS = 6 h`、`FRESHNESS_PRESETS_MS`、`sanitizeMinInterval()`、`nextMinInterval()`。
  `sanitized()` 把 `minIntervalMs` 夹进 `[MIN, MAX]`，再把 `emptyRetryMs` 夹到 `≤ minIntervalMs`
  （把既有 `coerceIn` 口径**前移到边界**，而不是等 policy 在每次决策时兜）。

### 2.2 持久化（`:app`）

- 新增 `SharedPrefsEpgSettingsStore`（文件 `epg-settings`，键 `enabled` / `min-interval-ms`）：
  沿用 `SharedPrefsRefreshRunLedger` / `SharedPrefsFirstRunStore` 的「一文件几个标量」取舍——无 Room
  schema 变更、无 DataStore 往返。**读写都过 `sanitized()`**，`apply()` 写盘（同进程下一次 `read`
  立即从内存拿到新值，磁盘异步刷）。
- `EpgRefreshModule`：`provideEpgSettingsStore()` 单例；`provideEpgRefreshSettings()`（旧的默认值单例）
  **删除**；scheduler / coordinator / gateway 改注 `EpgSettingsStore`。

### 2.3 生效路径（`:app`）

- `EpgRefreshScheduler.request()` **每次触发读一次 store**（不再构造期固定）：`gate(...)` 因此每次都用
  当前开关与阈值——关 ⇒ `SKIP(disabled)` 且**不入队**；阈值变小 ⇒ 下一次触发即转 `RUN(stale)`。
- `EpgRefreshCoordinator.run()` **每次运行读一次 store**：关 ⇒ 决策 `SKIP(disabled)`，**runner 不被调用**
  （runner 是唯一写节目表的东西 ⇒「0 次调用」等价于「没写库」），无删除动作 ⇒ 已有数据保留。
- `EpgRefreshGateway.status()` 也读 store（开关 + 阈值一并回给面板，且只读一次保持一致）。

### 2.4 设置页（`:feature:settings`）

- `SettingsFacts` 纯增 `epgEnabled` / `epgMinIntervalMs`；`SettingsEntry.kt` 的 `SettingsDestination`
  纯增 `TOGGLE_EPG` / `CYCLE_EPG_FRESHNESS`；`SettingsCatalog.build` 在**刷新**组加两行
  （`refresh.epg.enabled` 开关、`refresh.epg.freshness` 阈值），并加 `formatInterval()`（`6 小时`/`30 分钟`）。
- `SettingsViewModel` 注 `EpgSettingsStore`：`toggleEpg()` / `cycleEpgFreshness()` 写穿 store 并回吐新值；
  `SettingsActivity.activate` 两个新目的地用 Toast 回报新状态（与既有「记录级别」行同形）。

## 3. 口径（关键决策与理由）

1. **阈值的夹取范围 = [30 min, 6 h]**（新增，god 未预先指定范围）。理由：§6.3 对空 guide 退避写明
   「地板 30 min / 天花板 6 h」，把**同一对数字**用在可调阈值上，用户旋钮就**不可能**把天花板抬高
   （即派工单「6 h 天花板不得被破坏」）。低于 30 min 会让健康 guide 被反复重下；高于 6 h 会破坏空
   guide 的耐心上界。非法值**夹取**（`coerceIn`）而非拒绝。
2. **阈值用预设轮换而非自由输入**：遥控器没有键盘，列表短（5 档），与既有 `CYCLE_LOG_LEVEL` 同形。
3. **不新增事件码**：关闭只改 `WORK_SCHEDULE` / `WORK_RUN` 的既有 `reason=disabled`（早已存在），
   阈值只改既有 `minIntervalMs` 字段。`EventCodes.ALL` 仍 50。
4. **每触发/每运行读 store，而非监听到即热替换**：读是内存级（`SharedPreferences`），代价可忽略；
   语义上「下一次决策即新值」，不需要额外观察者或进程重启。写发生在 UI 线程（一次内存 + 异步盘）。
5. **`emptyRetryMs` 本轮不入 UI**：派工单只要两个控制面；它保持在 store 默认（30 min），
   以免把 §6.3 的三档退避故事一并暴露给用户。

## 4. 测试（全绿）

| 文件 | 条数 | 覆盖要点 |
|---|---|---|
| `core/domain/.../EpgSettingsTest.kt` | 5 | 默认值；夹取范围 = 30 min..6 h；`sanitized()` 同时夹 `emptyRetryMs`；预设轮换首尾与回绕；非预设值走向其**上方**预设 |
| `app/.../SharedPrefsEpgSettingsStoreTest.kt`（Robolectric） | 3 | 新装=默认；**改值存活到「下一个进程实例」**；越界值写入/读出都被夹取 |
| `app/.../EpgRefreshSchedulerTest.kt`（改 + 增 2） | 10 | 关 ⇒ 不入队 + `SKIP(disabled)`；**阈值每次触发读**（1 h 前 guide：默认 6 h 下 SKIP，改 30 min 后下一次即 RUN）；原有触发/闸门不变 |
| `app/.../EpgRefreshCoordinatorTest.kt`（改 + 增 1） | 13 | 关 ⇒ `Skipped(disabled)` 且 **runner 0 次调用**（= 不写库、数据保留）；原有 defer/预算/失败不变 |
| `app/.../EpgRefreshGatewayTest.kt`（改） | 3 | 面板按钮仍直连 manual 队列；关 ⇒ 按钮如实「没做事」；status 合并（含新阈值）|
| `feature/settings/.../SettingsCatalogTest.kt`（改 + 增 2） | 7 | 新增两行在**刷新**组且可达；开关/阈值两行摘要随事实变化；`formatInterval()` 小时/分钟 |

合计新增/改动单测 **约 25 条**，`testDebugUnitTest` 全过。

## 5. 质量门

- `./gradlew --offline check`：**BUILD SUCCESSFUL**（1281 actionable tasks，130 executed，0 失败）。
- 三道静态守卫：`tools/ci/sensitive-info-guard.sh` 绿、`notification-channel-audit.sh` 绿、
  `tv-focus-audit.sh` 绿（新增行沿用 `item_settings_entry` 既有可聚焦组件，未新增布局）。

## 6. 未做 / 边界

- **未用电视**（按派工单）：真机证据（设置页两行可遥控、改值后 `WORK_SCHEDULE` 出现新
  `minIntervalMs`、关后无 `WORK_RUN` 拉取）留**后续独立 QA 轮**。
- **未改冻结接口 / docs 01–04**：`EpgSettingsStore` 是新增端口（`EpgRefreshPolicy.kt` 的公共类型
  `EpgRefreshSettings` 只**纯增**方法与常量，字段签名不变）。如 arch 认为需把「EPG 设置持久化」写进
  `docs/02 §6.3`，请 god 走文档批。
- 未做「`emptyRetryMs` 可调」「按次数阶梯退避」——均属 §6.3 已记的「未做」项，非本卡范围。

## 7. 回退

单点回退：恢复 `EpgRefreshModule` 的 `provideEpgRefreshSettings()` 并把三处构造函数参数换回
`EpgRefreshSettings`、删 `SharedPrefsEpgSettingsStore` 与设置页两行即可；本卡改动**纯增**（新增端口/
文件/行），不触碰刷新写入链、播放引擎、快照资产、Room schema。
