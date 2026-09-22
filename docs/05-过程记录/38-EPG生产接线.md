# 38 · EPG 生产接线（触发点 + 在播避让 + 手动入口）验证

**卡片**：EPG 生产触发点（功能性缺口，来自 `37-P3-5EPG覆盖率.md` §7 第 6 条与 §8 第 4 条："**生产触发点仍未接线**（没人调用 `LoadEpgUseCase`）"）。
**执行**：dev-A 第 26 轮（temp `worker-dev-a-26`，worktree `worktrees/worker-dev-a-26`，分支 `agent/worker-dev-a-26`）。
**基线**：`git merge --ff-only main` → Already up to date，HEAD `b93fc3b`（含 P2-7 EPG 管线、P3-1 网格、P2-8 设置/诊断、P3-5 覆盖率提升、BUG-013/STALE-STREAM 修复）。
**范围**：非设备任务（**未用电视**，QA 占用）；不 commit/push；不改 `docs/01–04`；**不改播放链路与 EPG 匹配逻辑**。

---

## 0. 结论（先看这五条）

1. **EPG 现在有三个生产触发点，且只有一个执行路径**：应用冷启动（`FIRST_RUN`）、每次源刷新成功之后（`SCHEDULED`）、诊断面板「立即更新 EPG」（`MANUAL`）。三者 → 同一个 `EpgRefreshScheduler`/`EpgRefreshWorker` → 同一个 `LoadEpgUseCase`，**没有第二条拉取路径**。
2. **在播避让分两半，都是复用既有信号**：调度半边在 `EpgRefreshPolicy`（纯 Kotlin，可测）里 `DEFER`，上限 3 次、退避 30 min（与 P2-5 `PlaybackAvoidancePolicy` 同一组常量）；运行半边在 `LoadEpgUseCase` 里**每换一个源之前**重读同一个 `PlaybackPrioritySignal`，在播时**不再开新的源**，已落库的部分保留（§6.3 的降级语义）。触发点、时延、退避都不与播放抢；EPG 报 `interrupted=playback_priority`。
3. **幂等有两层**：触发层——6 小时内的重复触发**一次网络都不发、一行都不写**（`SKIP reason=fresh`）；存储层——`UNIQUE(epg_channel_id, start_ms)` + `INSERT OR REPLACE`，重跑同一份 guide 是更新同一槽位（本轮补了实测）。
4. **可观测沿用已注册事件码**（`docs/03 §3.3` 冻结 50 条，未新增）：`WORK_SCHEDULE`/`WORK_RUN` 带 `job=epg`，`EPG_COVERAGE` 新增 `interrupted` 字段（无在播打断时为 `null`）。真网络实测：`EPG_COVERAGE matched=209 total=658 ratio=0.318 mainstreamMatched=153 mainstreamTotal=156 mainstreamRatio=0.981 providers=4 programmes=177447 elapsedMs=8218 interrupted=null`。
5. **接线本身暴露了旧缺口**：把 `EpgRunner` 接进 Hilt 图后首次编译即 `[Dagger/MissingBinding] XmltvPullParser` —— 说明在此之前**整个 DI 图里没有任何东西依赖 `LoadEpgUseCase`**，正是本卡说的"没人调用"。已补 `@Provides`（§3.1）。

---

## 1. 触发点设计

### 1.1 三个触发点，一个漏斗

| # | 触发点 | 何时 | 触发器 | 队列 | 入口代码 |
| --- | --- | --- | --- | --- | --- |
| ① | **冷启动/首启** | 每次 `Application.onCreate`，异步 | `FIRST_RUN` | `epg-refresh`（`KEEP`） | `IptvApplication.scheduleEpg()` |
| ② | **每日源刷新之后** | P2-5 的刷新 run 结束且为 `Completed` 时 | `SCHEDULED` | `epg-refresh`（`KEEP`） | `RefreshWorker.requestEpgFollowUp()` |
| ③ | **手动「立即更新」** | 用户点诊断面板按钮 | `MANUAL` | `epg-refresh-manual`（`REPLACE`） | `EpgRefreshGateway.requestNow()` → `DiagnosticsActivity` |

三者共用：同一个 `EpgRefreshPolicy`（决策）、同一个 `EpgRefreshCoordinator`（预算 + 日志）、同一个 `LoadEpgUseCase`（抓取/解析/匹配/落库）、同一组 WorkManager 约束（网络；后台触发加"电量不低"）。

### 1.2 为什么这样切（论证）

- **为什么要"统一"而不是各写一套**：`37` 号文件 §8.4 的后果是"信息条 now/next 会是空的"。三处各自拉取会带来三份约束、三份重试、三份日志口径，且**互相不知道对方在拉**（同时拉 4 个源 × 2 = 8 个 HTTP 流）。漏斗式设计让"要不要拉"只有一个答案函数（`EpgRefreshPolicy`），"怎么拉"只有一份实现。
- **为什么 ① 放在 `onCreate` 的异步分支**：S6 的冷启动预算（≤5 s，release 取 5 次 max）不允许在启动路径上读库/发网络。触发点只做一次小查询（`epg_source` 状态）+ 一次 `enqueueUniqueWork`；真正的下载由 WorkManager 在满足约束时执行。决策协程跑在注入的 `AppScopeProvider.appScope`（§4.5 C6：禁止 `GlobalScope`）——该接口此前**只有声明没有绑定**，本轮在 `:core:log` 补了平台默认实现（与 `Clock`/`Redactor`/`DispatcherProvider` 同一归属）。
- **为什么 ② 不是"独立周期任务"**：§6.1 的 06:00 已经由 `refresh-daily` 占着，再加一条周期任务就是两份调度、两个 06:00 锚点、两次唤醒。挂在刷新之后，语义也更准：**频道表刚变过，此时重匹配最有价值**（新频道可能正好被 guide 覆盖）。EPG **失败绝不影响刷新的结果**：follow-up 被 try/catch 包住，且只在 `Completed` 后请求。
- **为什么要 ③**：前两个触发点都可能被"数据还新鲜"（6 h 内）合法地跳过，而"我现在就想看节目单"只有用户能决定。手动入口**不经过新鲜度闸门**（`RefreshTrigger.MANUAL` 在策略里规则 2），但它仍然走同一个用例、同一份预算、同一套落库。
- **为什么"新鲜就跳过"是必需的**（而不是"每次都拉"）：4 个源合计 **4.5 MiB / 17.7 万条节目 / 6–12 s**（P3-5 实测）。一天二十次冷启动如果每次都拉，就是二十次下载 + 二十次全表 `INSERT OR REPLACE`。6 小时的窗口把"真实使用中自动可得"和"不打扰不拖慢"同时成立。

### 1.3 决策表（`EpgRefreshPolicy`，纯函数）

| 顺序 | 条件 | 动作 | reason |
| --- | --- | --- | --- |
| 1 | `settings.enabled = false` | `SKIP` | `disabled` |
| 2 | 触发器 = `MANUAL` | `RUN`（忽略 3、4） | `manual` |
| 3 | 上次抓取距今 < `minIntervalMs`（默认 **6 h**） | `SKIP` | `fresh` |
| 4 | 在播 + `respectPlayback` + 触发器 ≠ `MANUAL` + 已退避 < **3** 次 | `DEFER` | `playing` |
| 5 | 其余 | `RUN` | `stale` |

两处刻意的取舍：
- **冷启动也会 `DEFER`**（规则 4 对 `FIRST_RUN` 同样生效）：冷启动不是"用户在等"，在播时推迟一次（30 min 后重试）比抢带宽更符合 R7；而 `MAX_DEFERRALS=3` 保证它最迟 90 min 内一定跑（`3×30` min 线性退避）。
- **闸门（`gate`）与决策（`decide`）分开**：`gate` 只看"开/关 + 新鲜度"，由调度器在**入队前**调用，所以新鲜数据连一次 WorkManager 唤醒都不产生；"在播"只在运行时判断（`deferrals` 计数器属于 WorkManager 的 `runAttemptCount`，入队时还不存在）。

---

## 2. 在播避让与并发

| 半 | 位置 | 依据 | 行为 |
| --- | --- | --- | --- |
| 调度半 | `EpgRefreshPolicy.decide` | `PlaybackActivity.isActive()`（进程级单写者，§4.5 C3） | 后台触发在播 → `DEFER`，退避 30 min，最多 3 次；用户点按钮不受影响 |
| 运行半 | `LoadEpgUseCase` 源循环开头 | 同一个 `PlaybackPrioritySignal`（`:core:source` 里已有的 `@Provides`） | 在播时**不再开始下一个源**，本轮已写库的行保留，`interrupted=playback_priority` |

两个"不这么做"的理由写进代码注释了，这里再点一次：

- **不中途杀正在解析的 guide**：一次流式 GET 已经发出去了，中断它既救不了带宽、又可能留下半份 guide（虽然 `INSERT OR REPLACE` 幂等，但"半份"仍然是没必要的状态）。所以判定点是**源与源之间**。
- **第一个源永远执行**：`RUN` 这个决定已经在调度层做过，此时再拒绝执行只会让手动按钮变成空操作。代价是一次有界的流式 GET。
- **不再加第二个并发旋钮**：EPG 是**单源顺序**拉取（P2-7 的语义），本身没有 `Fetch/Shallow/Deep` 那种并发度；沿用 §4.5 C3 的"服务端只有一个并发治理对象"的做法，不新造一个 `EpgConcurrencyGovernor`。

**预算**：`EpgRefreshBudget.DEFAULT_BUDGET_MS = 10 min`，由 `EpgRefreshCoordinator` 用 `withTimeout` 施加。10 min ≈ 实测 6–12 s 的 50 倍，且落在 WorkManager 对无前台 `CoroutineWorker` 的上限内；超时被判为**失败**（`reason=budget_exceeded`）交给重试策略，而不是挂住任务。`CancellationException` 单独重抛（§4.5 C5）。

**不打扰**：EPG 任务**不发通知、不起前台服务**——P2-5 的刷新是 45 min 的重任务且用户需要看阶段进度，EPG 是秒级后台任务，为它弹通知正是卡片禁止的打扰。

---

## 3. 落库与修剪

**没有绕过 P2-7 的实现**：`LoadEpgUseCase` 仍是"流式解析 → 时间窗 `[now-6h, now+48h]` 过滤 → 匹配 → `INSERT OR REPLACE` 批 500 → 按 `epg_channel_id` 修剪"（`docs/02 §5.1`、§4.5 C4）。本轮对它的改动只有两条：

1. 源循环开头加在播判定（§2），
2. `EPG_COVERAGE` 增加 `interrupted` 字段，`EpgLoadReport` 增加带默认值的 `interrupted`（老调用不受影响，与 P3-5 加 `byGroupTotal` / `guideKey` 同一形状）。

### 3.1 Hilt 图里被证实缺失的一环

`LoadEpgUseCase` 自 P2-7 起就有 `@Inject constructor`，但**从来没有任何东西依赖它**，所以 Dagger 从没校验过它的构造参数。本轮把它接进 `EpgRunner` 后立刻报：

```
错误: [Dagger/MissingBinding] ilab.iptv.player.core.epg.XmltvPullParser cannot be provided
      without an @Inject constructor or an @Provides-annotated method.
   ... LoadEpgUseCase(…, parser) ← EpgRefreshModule.provideEpgRunner(useCase)
```

已在 `EpgModule` 补 `@Provides @Singleton provideXmltvPullParser()`。**这条编译错误就是"生产路径没人调用"的机器证据**，比读代码更硬。

---

## 4. 可观测

**没有新增事件码**（`docs/03 §3.3` 与 `EventCodesTest` 钉在 50 条），EPG 的三种状态都落在已注册码上，靠 `job=epg` 区分：

| 事件码 | 位置 | 关键字段 |
| --- | --- | --- |
| `WORK_SCHEDULE` | `EpgRefreshScheduler` | `job=epg, trigger, decision=SKIP/RUN, reason, lastFetchAtMs, ageMs, minIntervalMs`（入队时再加 `name/backoffMs/maxAttempts/requiresNetwork/requiresBatteryNotLow`） |
| `WORK_RUN` | `EpgRefreshWorker`（start）与 `EpgRefreshCoordinator`（结论） | start：`job=epg, trigger, attempt, playing`；结论：`decision, reason, result=success/interrupted/skipped/failed, providers, programmes, coverageMatched/Total, interrupted, elapsedMs` |
| `EPG_COVERAGE` | `LoadEpgUseCase` | P3-5 的 `byGroup/byGroupTotal/mainstream*/target/alert` + **新增 `interrupted`** |
| `EPG_FETCH_OK` | `LoadEpgUseCase` | 在播提前收工时记一条 `interrupted=playback_priority` + `remainingSources` |

`EPG_COVERAGE` 仍是"低于阈值升 WARN + `alert=coverage_below_target`"（P3-5 §7 的待裁决点不变，本轮不动）。

**面板**（P2-8 的诊断面板，复用不重建）：新增 ①「立即更新 EPG」按钮；② 运行概览末尾一个 `EPG` 块——`自动更新（间隔 6 小时）`、`上次拉取（+ 多少分钟前）`、`源（共 4 / 启用 4 · 上次 OK:177447）`、`主流覆盖 153 / 156 = 98.1%`、`全量覆盖 209 / 658 = 31.8%`。数据只来自既有两处：`epg_source` 状态列（`EpgSourceStatusReader`）与 `EpgRepository.coverage()`。

---

## 5. 单测（本轮 +40）

| 模块 | 文件 | +条数 | 钉住什么 |
| --- | --- | --- | --- |
| `:core:domain` | `EpgRefreshPolicyTest`（**纯 Kotlin，无 Android**） | 11 | §1.3 决策表：关闭/手动优先/新鲜度边界（正好 6 h 算陈旧）/退避 1→3 后必跑/避让关闭/`gate` 与 `decide` 一致/常量（6 h、3 次、30 min） |
| `:app` | `EpgRefreshSchedulerTest` | 5 | 入队形状：冷启动与 follow-up 共用一个后台队列、手动**独立队列 + `REPLACE` + 不受电量约束**、新鲜数据**不入队**且记 `SKIP/fresh` |
| `:app` | `EpgRefreshCoordinatorTest` | 9 | 跑/跳/退三态与"跳过和退避**不碰管线**"、退避上限后照跑、按钮在播也跑、抛异常 → `Failed`、超预算 → `Failed(budget_exceeded)`、被打断 → `Completed(interrupted)` |
| `:app` | `EpgRefreshWorkOutcomeTest` | 6 | 与 WorkManager 的对话：完成/打断/跳过都是 `success`，退避与失败 `retry` 到上限后 `giveUp`（不用 `failure()`，免得留下 FAILED 挡后续触发） |
| `:app` | `EpgRefreshGatewayTest` | 3 | 面板那一面：按钮**无视新鲜度**也要排队、EPG 关闭时如实说"没排队"、状态把源新鲜度与覆盖率拼在一起 |
| `:core:data` | `LoadEpgUseCaseTest` | 3 | ① 在播中途停源：第二个源 `fetches=0`、第一个源的行保留、`interrupted=playback_priority` 进事件；② `respectPlayback=false` 时两个源都跑；③ **幂等**：同一槽位重跑仍 1 行且内容被更新（`第一版` → `第二版`） |
| `:feature:settings` | `DiagOverviewTest` | 3 | EPG 块只在有数据时出现且在最后、无 EPG 时页面与原来逐字一致、主流切片 = 央视+卫视+港澳台（地方/其他不计入） |

---

## 6. 命令与结果

环境：`export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`（`ANDROID_HOME` 已在 env）。所有命令在 worktree 根执行。

| 命令 | 结果 |
| --- | --- |
| `./gradlew --offline check` | **BUILD SUCCESSFUL**（1167 tasks；lint 0 error） |
| `./gradlew --offline verifyModuleDependencies` | `OK (19 modules; no forbidden direct dependency, no api exposure, no cycles)` |
| `./gradlew --offline :core:domain:test` | **133** tests / 0 failed（本轮 +11，纯 Kotlin 模块的 purity 守卫在 `check` 内通过） |
| `./gradlew --offline :app:testDebugUnitTest` | **58** tests / 0 failed（本轮 +23） |
| `./gradlew --offline :core:data:testDebugUnitTest` | **147** tests / 0 failed（本轮 +3） |
| `./gradlew --offline :feature:settings:testDebugUnitTest` | **34** tests / 0 failed（本轮 +3） |
| 全仓 debug 单测汇总（XML 统计，含纯 Kotlin `test` 144 条） | **960 条 / 0 失败 / 2 跳过**（跳过为 `:core:source` 既有 2 条） |
| `bash tools/ci/sensitive-info-guard.sh` | `OK — 0 unallowlisted hit(s)`（562 tracked files，38 条白名单逐条带理由） |

---

## 7. 真网络覆盖率实测（P3-5 事件在真实数据上的复验）

```bash
./gradlew --offline :core:data:testDebugUnitTest -Piptv.epgSample=1 \
    --tests '*EpgNetworkSampleTest*' --rerun -i
```

（`-Piptv.epgSample=1` 默认关闭；**重跑必须加 `--rerun`**，否则 Gradle 判 `UP-TO-DATE` 不执行——P3-5 已记的坑。）

夹具与 P3-5 相同：随包 `p1-2-baseline.m3u`，**658 频道 / 670 流**（央视 80 / 卫视 69 / 港澳台 7 / 地方 500 / 其他 2）。

| 项 | 本轮实测 |
| --- | --- |
| 源可达 | `epg.pw.cn` 307,377 B · `epg.pw.hk` 1,698,041 B · `epg.pw.tw` 2,122,945 B · `epgshare01.hk` 591,868 B（**4/4 HTTP 200，合计 4.5 MiB**） |
| 解析 | 4 源 channels 663+228+518+143=1,552；programmes 25,234+46,032+92,501+13,680=**177,447**；`malformed=false`；skipped 113,966（时间窗外的行） |
| 覆盖率（全量） | **209/658 = 31.8%** |
| 覆盖率（主流 = 央视+卫视+港澳台） | **153/156 = 98.1%**（目标 0.60） |
| 分组 | `cctv 80/80 · sat 69/69 · hmt 4/7 · local 55/500 · other 1/2` |
| 命中层级 | `NAME_EXACT=137 NAME_FUZZY=65 ALIAS=23` |
| 端到端耗时 | **8.2 s**（P3-5 记录 6.0–12.3 s，同量级） |
| `interrupted` | `null`（本轮采样无在播打断，字段存在且为 null —— 这正是"没被打断"的可读证据） |
| 未覆盖 | 地方 445（合成台，guide 里没有）、港澳台 3（`TVB星河频道`/`澳视澳门`/`中天新闻`）、其他 1（`导视资讯`）——与 P3-5 §4 的结论逐条一致 |

结论：**触发点接线没有改变覆盖率**（数字与 P3-5 完全一致），它改变的是"这些数据会不会在生产上被拉下来"。

---

## 8. 与 P2-5 的关系

| 维度 | P2-5 源刷新 | 本轮 EPG 任务 | 是否共用 |
| --- | --- | --- | --- |
| 调度 | `refresh-daily`（周期，06:00）+ `refresh-manual`（一次性） | `epg-refresh`（一次性，冷启动/follow-up）+ `epg-refresh-manual`（一次性） | 同一 WorkManager、同一命名/重试风格；**EPG 不新增周期任务** |
| 约束 | 网络 + 电量不低 | 网络 + 电量不低（手动去掉电量） | 是 |
| 重试 | LINEAR 30 min × ≤3 | LINEAR 30 min × ≤3（`EpgRefreshPolicy.DEFER_BACKOFF_MS` 直接引用 `PlaybackAvoidancePolicy.DEFER_BACKOFF_MS`） | 是（同一组常量，不是同一段代码） |
| 在播避让 | `PlaybackAvoidancePolicy`（调度）+ `ConcurrencyGovernor`（运行，并发减半） | `EpgRefreshPolicy`（调度，`DEFER`）+ 源循环在播停源（运行） | 信号同一个（`PlaybackActivity`/`PlaybackPrioritySignal`），策略各自表；**成功续跑**：`RefreshWorker` 完成刷新后触发 EPG |
| 预算 | 45 min（每次尝试） | 10 min（`withTimeout`） | 各自定义，都写清了数字与理由 |
| 前端可见性 | 前台服务 + 通知（阶段进度） | 无通知（秒级后台任务，"不打扰"） | 否，刻意不同 |
| 日志 | `WORK_SCHEDULE`/`WORK_RUN`/`SERVICE_REFRESH_*` | `WORK_SCHEDULE`/`WORK_RUN` + `job=epg` 区分 | 同一批事件码 |

---

## 9. 未做项 / 边界

1. **真机/电视验证未做**（QA 占用电视）：本轮的验证全部在 JVM/Robolectric + 真网络层；没有 logcat 截图、没有"信息条真出 now/next"的电视取证。**这是本卡最重要的未做项**：逻辑链路已通、数据已实测，但"装到电视上信息条不空"仍需一次真机验收。
2. **冷启动触发在"启动后多久"没有下界**：目前是 `onCreate` 里 `launch`，立即执行（一次小查询）。没有做"等首帧之后再触发"的延迟策略；如果有实测显示它影响 S6 冷启动，可加一个延迟/约束（成本很低，一行）。
3. **EPG 源的并发拉取仍未做**（P3-5 §8 也记了）：4 源是顺序循环，端到端 6–12 s。本轮没有改成并发，因为并发会与"在播避让"的直接语义冲突（在播时减半的前提是有一个显式并发度）。
4. **`EpgRefreshSettings` 只是内存默认值**（6 h / 开）：没有落 Room、设置页也没有开关行（只有诊断面板的按钮）。策略是纯函数，将来接设置只改一个 `@Provides`。
5. **设置页没有加"立即更新 EPG"行**：只挂了诊断面板（卡片允许"设置/诊断面板里"）。判断依据：面板已经有 EPG 块，按钮和数据在一起，用户/测试一眼能对上。
6. **繁简折叠、前缀模糊、手动绑定（P3-4）**：仍未做，与 P3-5 §8 相同。
7. **`EpgSourceStatus.lastResult` 的语义**：取"最新 `last_fetch_at` 那一行的 `last_result`"，不是聚合；多源同时失败/成功时以最新一行为准（面板显示的也是这一条）。
8. **未做 "在播时把已入队的后台任务取消"**：入队后任务由 WorkManager 决定何时跑，在播时它在 `run()` 里 `DEFER`（重试），不会开着拉；但没有 `cancelUniqueWork` 的主动取消路径。

---

## 10. 给 god / arch 的裁决点

1. **`:core:log` 新增 `AppScopeProvider` 绑定**（`DefaultAppScopeProvider`）：该接口在 `:core:common` 里早已冻结，但一直没有生产绑定，而 §4.5 C6 又要求"禁止 `GlobalScope`、用注入的 `appScope`"。放在 `:core:log` 是沿用"平台默认实现归 `:core:log`"的既有归属（`Clock`/`Redactor`/`DispatcherProvider` 同处）。若 arch 认为该归 `:app`，移动一行即可。
2. **`LoadEpgUseCase` 新增构造参数 `playback: PlaybackPrioritySignal`**（默认 `{ false }`）与 `invoke(respectPlayback = true)`：这是"运行半在播避让"的落点。它不改签名语义（默认值保持老行为），但确实动了 P2-7 的类；若 arch 要求把"在播停源"放到别的层（例如 `EpgRunner` 包一层），说明理由即可改。
3. **`EpgLoadReport.interrupted`（新增带默认值字段）** 与 **`EPG_COVERAGE.interrupted`** 字段：沿用 P3-5 给 `EpgCoverage.byGroupTotal`、`EpgMatchResult.guideKey` 的"带默认值、纯增"做法，`docs/02 §4.2` 未写这两个字段。
4. **EPG 不注册周期任务而挂在刷新之后**（§1.2）：与 `docs/04` P2-5"每日 06:00 一次刷新"配合，但 `docs/02 §6.3` 只写了流程没写触发时机。若 arch 想要独立周期（例如每日两次），改 `EpgRefreshScheduler` 增加一个 `periodic` 分支即可，漏斗以下不用动。
5. **6 小时新鲜度窗口**是拍的值（`EpgRefreshSettings.DEFAULT_MIN_INTERVAL_MS`）：依据只有"4.5 MiB / 6–12 s"和"一天约 4 次"的估算，没有实测的"节目单可接受陈旧度"。若有产品口径（例如"跨天必须重拉"），改常量即可。
6. **`docs/03 §3.3` 仍未登记 EPG 专属事件码**：本轮全部复用 `WORK_*` + `job=epg`。若 arch 希望有 `EPG_REFRESH_*` 独立码，这属于 `docs/03` 回写（与 P3-5 §7 建议的 `EPG_COVERAGE_LOW` 可一并裁决）。

---

## 11. 复现清单

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd <worktree>

./gradlew --offline check                                        # 全绿（含 purity 守卫、lint、verifyModuleDependencies 单独跑）
./gradlew --offline verifyModuleDependencies                     # OK (19 modules)
./gradlew --offline :core:domain:test                            # 触发点决策表（纯 Kotlin）133 条
./gradlew --offline :app:testDebugUnitTest                       # 调度/协调/WorkManager 对话/面板端口 + refresh 既有 58 条
./gradlew --offline :core:data:testDebugUnitTest                 # 端到端 + 在播停源 + 幂等 147 条
./gradlew --offline :feature:settings:testDebugUnitTest          # 面板 EPG 块与主流切片 34 条
bash tools/ci/sensitive-info-guard.sh                            # 脱敏守护：OK — 0 unallowlisted

# 真网络覆盖率采样（默认关闭；重跑要加 --rerun，否则 UP-TO-DATE 不执行）
./gradlew --offline :core:data:testDebugUnitTest -Piptv.epgSample=1 \
    --tests '*EpgNetworkSampleTest*' --rerun -i | grep -E 'coverage|EPG_|fixture:'
```

**证据**：上面每个数字都能由这些命令重放；`WORK_SCHEDULE`/`WORK_RUN`（`job=epg`）给出触发点决策，`EPG_COVERAGE` 给出覆盖率与 `interrupted`。
