# 40 · EPG 绑定择优 + 覆盖率口径修正验证

**卡片**：`docs/05-过程记录/39-EPG繁简折叠.md` §7 第 1 条（`LoadEpgUseCase` 逐源覆盖绑定、后命中者赢 —— "这是 P3-5 之前就有的语义…值得被知道"，建议单开一卡）＋ EPG-TRAD-1 暴露的第二条（覆盖率只统计"匹配到 id"，不检查该 id 在窗口内是否真有节目）。
**执行**：dev-A 第 27 轮（temp `worker-dev-a-27`，worktree `worktrees/worker-dev-a-27`，分支 `agent/worker-dev-a-27`）。
**基线**：HEAD `3d3f2a3`（含 39 号繁简折叠 `3d3f2a3` 与 38 号生产接线 `972f75a`）。
**范围**：非设备任务（**未用电视**）；**不 commit / 不 push**；**不改 `docs/01–04`**（口径变化记进本文件，由 arch 回写）；**不动 EPG 源清单 / 别名表 / 繁简折叠表**。

---

## 0. 结论（先看这六条）

1. **绑定择优落地为纯函数** `EpgBindingPreference.choose`（`:core:epg`）：同一频道被多个 guide 命中时，选**窗内节目数最多**的绑定；并列时先沿用**既有**裁决（**后命中的源赢**，与旧覆盖循环一致），再按层级、再按 guide id，构成**全序** ⇒ 任何输入下赢家唯一，重跑不会翻。`LoadEpgUseCase` 从"逐源覆盖写库"改成"读完全部源 → 一次定夺 → 一次写库"。
2. **覆盖率三口径同时上报**：`matched`（有 id）／`withProgrammes`（窗内 ≥1 条节目）／`emptyBinding`（有 id 但窗内 0 条），全量与**主流切片**各一套；**旧字段一个没删**（`matched`/`ratio`/`byGroup`/`mainstreamMatched`/`mainstreamRatio` 原义保留），新增字段全带默认值。**低于阈值告警改用 `programmedRatio`** —— 空绑不再计入主力口径。
3. **真网实测：择优只改了 1 条绑定**（`CCTV-13新闻`：港澳 `CCTV-13.hk` 63 行 → 内地 `545945` ≈77 行）。EPG-TRAD-1 记录的 5 个台（`深圳卫视`/`深圳卫视 高清`/`三沙卫视`/`凤凰卫视中文台`/`凤凰卫视资讯台`）绑定与节目数**一个没降**。
4. **三口径在择优前后 ±0**：`matched 209/658`、`withProgrammes 173/658`、`emptyBinding 36`；主流 `matched 153/156`、`withProgrammes 126/156`、`emptyBinding 27`。原因见第 5 条 —— 择优只在"两边都非空"时改变**深度**，而 36 个空绑**没有任何更深候选**。
5. **真收获是口径：主流"可看率"是 126/156 = 80.8%，不是 98.1%。** 98.1% 是"有 id"的读法，其中 27 个主流频道的 id **一条节目都没有**（界面全空却算成功）。这 27 个空绑的根因**不是"源没有数据"**：`epg.pw CN` 的 663 个 `<channel>` 里有 **225 个一条 `<programme>` 都没有**，夹具的 `CCTV2`/`CCTV3`/`CCTV5`/`CCTV5+`/`CCTV6`/`CCTV8`… 长的正是这些"**声明即为空**"的存根（`561309–561325`/`561405` 一批）。该频道的真数据就在同一份 guide 里，只是换了 display-name（`CCTV-2 财经`=545933，274 条）。**择优救不了它，别名表今天也够不着** —— tier 2 一命中就不再往下查别名，所以这是个**匹配层**问题，建议单开一卡（§6 第 1 条给了解法与证据）。
6. **顺手修掉一个既有缺陷**：`RoomEpgRepository.coverage()` 此前不填 `byGroupTotal`，诊断面板的"主流覆盖"分母恒为 0（`153 / 0 = 0.0%`）。本轮补齐，并把面板也切到同一口径，空绑非零时显示为 `149 / 156 = 95.5%（空绑 4）`（空绑为 0 时字符串与改动前**逐字相同**）。

---

## 1. 交付清单（新增 2 文件 / 改动 16 文件）

新增：

| 文件 | 作用 |
| --- | --- |
| `core/epg/…/EpgBindingPreference.kt` | `EpgBindingCandidate`（一次提议：channelId / guideId / 层级 / matchedOn / guideKey / 源序）＋ `EpgBindingPreference.choose(candidates, programmesInWindow)` —— 纯函数、无副作用、无 I/O |
| `core/epg/src/test/…/EpgBindingPreferenceTest.kt` | 7 条：深度优先（含"更深的先被提议"）、深度相同 → 后源、再相同 → 层级 → id（全序/可复现）、空绑在唯一候选时仍胜出、空绑输给"只有 1 条节目"的、无候选返回 null |

改动：

| 文件 | 改动 |
| --- | --- |
| `core/model/…/Epg.kt` | `EpgCoverage` +`withProgrammes`（默认 = `matched`）、+`byGroupWithProgrammes`（默认空）；派生 `emptyBinding`、`programmedRatio`；`ratio` 原义不动 |
| `core/epg/…/EpgMatcher.kt` | `EpgCoverageCalculator.of` 加 `channelsWithProgrammes` 参数（默认 = 已匹配集合）；`Slice` +`withProgrammes`/`emptyBinding`/`programmedRatio`；`mainstream()` 按组给三口径。**关键约定**：有已匹配频道的分组**一定**写一条（含 0），否则"这一组全是空绑"与"生产者没报这个口径"就都是缺键（见 §3） |
| `core/database/…/dao/ProgrammeDao.kt` | +`countByChannelInWindow(from, to)`：`stop_ms >= from AND start_ms <= to`，与网格窗口同一条谓词（跨左边界算、整段在过去不算） |
| `core/database/…/dao/ChannelDao.kt` | +`countWithEpgProgrammes` / `countWithEpgProgrammesByGroupKey`（同一谓词），供端口读路径 |
| `core/data/…/LoadEpgUseCase.kt` | 决策点重写：候选按频道收集 → 跑完所有源 → 一次 `countByChannelInWindow` → `EpgBindingPreference.choose` → 一次 `setEpgBindings`；`EPG_MATCH_HIT` 改为**决策之后**逐提议打出（同一码、同一行数 230），带 `programmesInWindow` 与 `chosen`；`EPG_COVERAGE` +10 个字段；告警门槛改 `programmedRatio`；构造参数 +`programmeDao` |
| `core/data/…/RoomEpgRepository.kt` | 构造参数 +`Clock`；`coverage()` 补 `byGroupTotal`（既有缺陷）、+`withProgrammes`/`byGroupWithProgrammes`（窗口由 Clock 现算） |
| `feature/settings/…/DiagOverview.kt` | +`CoverageReading`、`coverageOf`、`coverageText`（`（空绑 N）` 仅在 N>0 时追加）；`mainstreamOf` 改返回三口径 |
| `feature/settings/…/DiagnosticsViewModel.kt` | "主流覆盖"/"全量覆盖"改用 `DiagOverview.coverageText(...)`（同一口径） |
| `app/…/EpgRefreshCoordinator.kt` | `WORK_RUN` 日志 +`coverageWithProgrammes`/`coverageEmptyBinding` |
| `app/…/EpgRefreshWorkOutcome.kt` | +`KEY_COVERAGE_PROGRAMMED`（`coverageRatio` 原样保留，向后兼容） |
| 测试 6 个 | `EpgMatcherTest`（+5 三口径/空绑/兼容/反例）、`LoadEpgUseCaseTest`（+5 择优/空绑/窗口边界）、`RoomEpgRepositoryTest`（+2 服务 vs 空绑/窗口）、`DiagOverviewTest`（+3 渲染/零条目/兼容）、`EpgRefreshWorkOutcomeTest`（+1）、`EpgTestSupport`（report 加 `withProgrammes`）、`EpgNetworkSampleTest`（三口径行 + 空绑清单 + 竞争表） |

---

## 2. 决策规则（代码 KDoc 同步，纯函数）

```
choose(candidates, programmesInWindow):
  1) 窗内节目数最多者胜                       ← 本卡要的规则
  2) 并列 → 源序靠后者胜（= 既有"后命中者赢"，把行为变化面缩到最小）
  3) 再并列 → 匹配层级靠前者胜（TVG_ID < NAME_EXACT < NAME_FUZZY < ALIAS）
  4) 再并列 → guide id 字典序小者胜        ← 保证全序：赢家不依赖输入顺序
```

理由（都写进了 KDoc）：第 1 条是"什么都别靠运气"；第 2 条让"并列"这种无信息量的情形**保持原行为**，本轮真网实测因此只动了 1 条绑定；第 3/4 条让决策成为**全序函数**，同一份 guide 重跑必然绑同一个 id（与 §5.1 的幂等承诺一致）。
另一个刻意的选择：**唯一候选即使窗内 0 条节目也照样绑定** —— "这个 id 现在没节目"不是解绑的理由（没有更好的），是覆盖率必须说出来的事实（`emptyBinding`）。

---

## 3. 覆盖率口径：三口径怎么算、为什么必须显式写 0

`EPG_COVERAGE`（全量与主流各一套，旧字段原义保留）：

| 字段 | 含义 |
| --- | --- |
| `matched` / `byGroup` / `ratio` | 有 id（**旧口径，不动**） |
| `withProgrammes` / `byGroupWithProgrammes` / `programmedRatio` | 该 id 在保留窗 `[now-6h, now+48h]` 内 ≥1 条节目（**新口径**） |
| `emptyBinding` | `matched - withProgrammes`（有 id 但窗内 0 条） |
| `mainstreamMatched` / `mainstreamTotal` / `mainstreamRatio` | 主流切片（央视+卫视+港澳台）的旧口径 |
| `mainstreamWithProgrammes` / `mainstreamEmptyBinding` / `mainstreamProgrammedRatio` | 主流切片的三口径（目标 0.60 现在按 `mainstreamProgrammedRatio` 判） |

**实现中踩到并修掉的一个真陷阱**：`byGroupWithProgrammes` 的**缺键**有两种含义 —— "这一组全是空绑" 与 "生产者根本没算这个口径"。第一版用 `?: 组内已匹配数` 兜底，结果**全空的一组被兜成了全覆盖**（单测当场红：`expected WARN but was INFO`）。定稿约定：

- **有已匹配频道的分组一定要写一条，哪怕是 0**（`EpgCoverageCalculator.of` 与 `RoomEpgRepository.coverage()` 都如此）；
- 读者（`mainstream()`、`DiagOverview.mainstreamOf`）只对**真的缺键**的组回退到"假设都有节目"（老口径兼容）。

单测钉死：`a list where every binding is empty…`（断言 `byGroupWithProgrammes == {SATELLITE: 0}`）、`a caller that only knows the id side keeps the old reading`、`the panel's number is … empty binding is called out`。

---

## 4. 命令与结果

环境：`export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`（`ANDROID_HOME` 已在 env）。命令都在本 worktree 根执行。

| 命令 | 结果 |
| --- | --- |
| `./gradlew --offline check` | **BUILD SUCCESSFUL**（1167 tasks，3m38s；含 lint、纯 Kotlin 守护、依赖守护、全模块单测） |
| `./gradlew --offline verifyModuleDependencies` | `OK (19 modules; … no forbidden direct dependency, no api exposure, no cycles)` |
| `bash tools/ci/sensitive-info-guard.sh` | `OK — 0 unallowlisted hit(s)`（退出码 0） |
| 全仓单测（XML 汇总） | debug **853 / 0 失败 / 2 跳过**；release **853 / 0 / 2**；纯 Kotlin `test` **144**（domain 133 + model 7 + common 4） |
| 本轮模块数字 | `:core:epg` 65→**77**（+12）、`:core:data` 147→**154**（+7）、`:feature:settings` 34→**37**（+3）、`app` 58→**59**（+1），**合计 +23**；`core:database` 46（DAO 是接口，靠 `:core:data` 的 Room 单测覆盖） |

真网络采样（与 P3-5 / EPG-TRAD-1 同一条命令、同一夹具、同一台机器）：

```bash
./gradlew --offline :core:data:testDebugUnitTest -Piptv.epgSample=1 \
    --tests '*EpgNetworkSampleTest*' --rerun -i | grep -E 'coverage|fixture:|events:'
# 关注行：coverage-verdicts / coverage-mainstream / coverage-by-group /
#         coverage-empty-binding / coverage-competition / coverage-spotlight / coverage-hit-tiers
```

**A/B 的取法**（可复现、无残留）：把 `EpgBindingPreference.beats()` 里那一行 `if (challengerDepth != incumbentDepth) …` **注释掉**，跑一次采样即得"择优前"（此时决策退化为"后命中者赢"，与旧覆盖循环等价）；改回后 `shasum -a 256 core/epg/…/EpgBindingPreference.kt` = `e65088c4…49b2a`，与改前一致，工作区无残留。

---

## 5. 真网采样：三口径前后 + 竞争绑定 + 空绑清单

夹具（两次运行一致）：`channels=658 streams=670 groups={CCTV=80, SATELLITE=69, HK_MO_TW=7, LOCAL=500, OTHER=2}`；`fetchOk=4 fetchFail=0 parseOk=4 matchHit=230 matchMiss=2402 coverage=1`；层级 `NAME_EXACT 137 / NAME_FUZZY 70 / ALIAS 23`（与 39 号逐项一致 —— 本轮**不动匹配**，只动绑定选择）。

### 5.1 三口径：择优前 vs 择优后（同一夹具、同一条命令）

| 口径 | 择优前 | 择优后 | 差 |
| --- | --- | --- | --- |
| `matched`／`total`（有 id） | **209 / 658 = 31.8%** | **209 / 658 = 31.8%** | **±0** |
| `withProgrammes`（窗内有节目） | **173 / 658 = 26.3%** | **173 / 658 = 26.3%** | **±0** |
| `emptyBinding`（有 id 无节目） | **36** | **36** | **±0** |
| 主流 `matched / total` | **153 / 156 = 98.1%** | **153 / 156 = 98.1%** | **±0** |
| 主流 `withProgrammes` | **126 / 156 = 80.8%** | **126 / 156 = 80.8%** | **±0** |
| 主流 `emptyBinding` | **27** | **27** | **±0** |
| 分组（matched / 有节目） | cctv 80/53、sat 69/69、hmt 4/4、local 55/46、other 1/1 | 同左 | ±0 |

**为什么 ±0**：择优在"两个候选都非空"时改变的是**深度**，在"一个候选为空"时改变的是**空绑归属** —— 而本夹具的 36 个空绑**每个都只有一个候选**（该 guide 只以这一个 id 声明了那个名字），没有更深的选择可做。三口径是把这件事**照出来**的口径，不是能靠择优**修好**的东西。

### 5.2 竞争绑定（≥2 个源的 16 个频道，A/B 全表）

| 频道 | 择优前赢家（行数） | 择优后赢家（行数） | 变化 |
| --- | --- | --- | --- |
| `CCTV-13新闻` | `CCTV-13.hk` **63** | `545945`（epg.pw CN）**≈77–78** | **改了（+14 行）** |
| `CCTV1` / `CCTV1 高清` / `CCTV1 标清` | `CCTV-1.hk` 84 | 同左 | 不变 |
| `CCTV4` / `CCTV4 标清` | `CCTV-4.hk` 92–93 | 同左 | 不变 |
| `CCTV13` / `CCTV13 高清` | `CCTV-13.hk` 63 | 同左 | 不变 |
| `CCTV-1综合` | `CCTV-1.hk` 84 | 同左 | 不变 |
| `深圳卫视` / `深圳卫视 高清` | `540037`（HK）60 | 同左 | 不变 |
| `三沙卫视` | `410381`（HK）7 | 同左 | 不变 |
| `凤凰卫视中文台` | `410378`（HK）101–102 | 同左 | 不变 |
| `凤凰卫视资讯台` | `410355`（HK）129–131 | 同左 | 不变 |
| `翡翠台` | `368366`（HK）86–87 | 同左 | 不变 |

**16 个竞争频道里只有 1 个的绑定被纠正**（`CCTV-13新闻`：旧规则让后命中的港澳源覆盖了更厚的内地源，63 < 77）。这正是本卡要证伪的"运气"：同一份数据里，**15 次是运气对、1 次是运气错**，而旧的覆盖循环对两者一视同仁。

### 5.3 EPG-TRAD-1 的 5 个台：绑定与节目数不下降

| 频道 | 39 号记录的绑定（行数） | 本轮（择优后） | 结论 |
| --- | --- | --- | --- |
| `深圳卫视` | `540037`（60） | `540037`（60） | 不变 |
| `深圳卫视 高清` | `540037`（60） | `540037`（60） | 不变 |
| `三沙卫视` | `410381`（7） | `410381`（7） | 不变 |
| `凤凰卫视中文台` | `410378`（101） | `410378`（101–102） | 不变 |
| `凤凰卫视资讯台` | `410355`（129） | `410355`（129–131） | 不变 |
| `TVB星河频道`/`澳视澳门`/`中天新闻` | `null` | `null` | 仍无源（39 号已定性） |

（行数 ±1 的抖动来自两次运行之间保留窗随时钟滑动，39 号已记录同一现象。）

### 5.4 36 个空绑清单（这才是"覆盖率说真话"照出来的东西）

```
cctv:27  CCTV2=561310, CCTV3=561311, CCTV5=561313, CCTV5+=561314, CCTV6=561315, CCTV8=561317,
         CCTV10=561319, CCTV11=561320, CCTV12=561321, CCTV14=561323, CCTV15=561324,
         CCTV16=561405, CCTV17=561325, 以及 CCTV2/3/5/6/8/10/12/14/15 的「高清」「标清」各一条
local:9  上海新闻综合=539884, 广东影视=539661, 云南影视=539671, 河南都市=539875,
         上海都市=539846, 广东少儿=539770, 海南少儿=539723, 贵州科教=544577, 广东体育=539748
```

**根因取证**（只读，未入库；`epg.pw CN` 原件，2026-09-22 抓取）：

```
declared <channel>: 663    programme rows: 25234    distinct refs: 438
declared with zero programmes: 225
start stamps: 20260921000000 → 20260927235925（+0000）  ← 覆盖到保留窗，不是"guide 过期"
CCTV1→561309(0), CCTV2→561310(0), CCTV5+→561314(0), CCTV13→561322(0)   ← 名字只声明一次，且 0 条节目
同名重复声明 52 组，例：东方卫视→539717(35) / 545965(219) / 561342(0)  ← 先出现的那个有节目，所以卫视 69/69 完好
```

即：CN guide 为 `CCTV2`/`CCTV3`/… 提供了**只声明、不带节目**的 `<channel>` 存根（`561309–561325`/`561405` 一批），而它真正的节目在**别的 display-name**（如 `CCTV-1 综合`=`545932`，78 行）下。夹具里这些名字是独立频道，于是"匹配成功 → 绑定到空存根 → 界面全空"，而旧口径把它算成已覆盖。`epg.pw CN` 的节目时间范围覆盖本周，所以这不是"源没数据"或"源过期"，是**同名多 id 的取法**问题。

---

## 6. 未做项 / 给 god（arch）的裁决点

1. **空绑根因要单开一卡（最重要，本轮只照出来 + 留清单）**：36 个空绑（其中 27 个主流）**每个都只有一个候选**，所以择优无从选择 —— 而"另一个候选该存在"这件事，今天**根本到不了决策点**：
   - 夹具频道 `CCTV2` 的 key 是 `cctv2`；`epg.pw CN` 用这个名字声明了**一个不带任何节目的存根**（`561310`）。同一份 guide 里它的真数据在**另一个 display-name** 下：`CCTV-2 财经`（`545933`，274 条）—— 名字不同，所以 tier 2/3 都不会命中它。
   - 别名表里对应的是 **`央视财经 → CCTV-2 财经`**（P3-5 的"央视X"那 16 条），**没有 `CCTV2 → CCTV-2 财经`**；更要紧的是：`EpgMatcher` 的链**一旦某一层命中就 `continue`**，tier 4（别名）**根本不会被查**。所以"补一条别名"今天也修不好 —— 短路的正是这一层。
   - 因此可行的修法都在**匹配层**（本卡边界之外）：① 让匹配层把**各层级命中都作为候选**交给 `EpgBindingPreference`（本轮已就位的决策器就能选厚的那个）；或 ② 让 `EpgChannelIndex` 的 `byNameKey` 保留**同名多个 id**，把同名重复声明（本夹具 52 组，例：`东方卫视` 的三个 id `539717`/`545965`/`561342`，先出现的那个恰好有节目，所以今天无症状）也变成候选；或 ③ 把"整份 guide 内零节目的 `<channel>`"排除出索引（实现最小，代价是频道变"未覆盖"而不是"空绑"，且失去"guide 将来补上就自动接上"的性质）。
   三条都会动 `EpgChannelIndex`/`EpgMatcher` 与 `EPG_MATCH_HIT/MISS` 的计数口径，需要 arch 定，故本轮不做。
   **建议的下一步顺序**：先按 ① 改匹配层（决策器已经支持多候选），再按 §5.4 的 27 个 id 补 `CCTVn → CCTV-n 栏目名` 别名（别名表是 P3-5 维护规则 1 的正规入口，本卡边界外）；两步做完，这 27 个台应当落到 `545933` 一类厚 id 上（机制与本次实测的 `CCTV-13新闻` 63→77 同一套）。
2. **口径回写**：`docs/04` 的 P3-5 出口"≥60%（主流频道）"现在按 `mainstreamProgrammedRatio` 判（本夹具 80.8%，**仍达标**，但不再是 98.1%）；`docs/02 §6.3` 建议补一句"覆盖率有三口径，'覆盖'默认指窗内有节目"。请 arch 回写。
3. **`EpgRepository.coverage()` 端口**仍未带窗口参数：本轮在实现侧（`RoomEpgRepository`）注入 `Clock` 现算窗口，**端口签名一行未改**。若 arch 认为窗口该是端口参数，那是一处接口变更。
4. **事件码**：未新增（告警继续复用 `EPG_COVERAGE` + `alert=coverage_below_target`，`docs/03 §3.3` 的 50 条不动）。
5. **诊断面板文案**：新增「（空绑 N）」后缀（空绑为 0 时不出现）。属 §8.1 冻面，请 arch 记一笔。
6. **`EPG_MATCH_HIT` 的时序变了**：现在在**决策之后**逐提议打出（同一码、同一行数 230，多两个字段 `programmesInWindow`/`chosen`），因此日志里 `EPG_MATCH_HIT` 会排在 `EPG_MATCH_MISS` 之后。按行数可比的结论不受影响（§5 的层级表与 39 号逐项一致）。
7. **未做**：真机/电视验证（本卡非设备任务，无 logcat/截图，"界面真的不空了"仍需一次真机验收）；并发拉取（4 源仍顺序，端到端 6–12 s）；`EpgRefreshSettings` 落库。

---

## 7. 复现清单

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd <worktree>

./gradlew --offline check                        # 全绿（lint + 纯 Kotlin 守护 + 依赖守护 + 全模块单测）
./gradlew --offline verifyModuleDependencies      # OK (19 modules)
./gradlew --offline :core:epg:testDebugUnitTest   # 77 条（本轮 +12：EpgBindingPreferenceTest 7 + EpgMatcherTest 5）
bash tools/ci/sensitive-info-guard.sh             # OK — 0 unallowlisted

# 真网络采样（默认关闭；重跑必须加 --rerun，否则 Gradle 判 UP-TO-DATE）
./gradlew --offline :core:data:testDebugUnitTest -Piptv.epgSample=1 \
    --tests '*EpgNetworkSampleTest*' --rerun -i | grep -E 'coverage|fixture:|events:'

# A/B（择优前）复现：注释掉 EpgBindingPreference.beats() 里的
#   if (challengerDepth != incumbentDepth) return challengerDepth > incumbentDepth
# 改回后 shasum -a 256 core/epg/…/EpgBindingPreference.kt 应为 e65088c4279e748dbd58753d56e7e49cd67ab37daa2aa2e8046e649d4b449b2a

# 空绑根因只读取证（不落盘、不写库）
curl -s -o /tmp/epg_CN.xml.gz -L https://epg.pw/xmltv/epg_CN.xml.gz && gunzip -c /tmp/epg_CN.xml.gz > /tmp/epg_CN.xml
python3 - <<'PY'
import re
from collections import Counter
doc = open('/tmp/epg_CN.xml', encoding='utf-8', errors='replace').read()
chans = re.findall(r'<channel id="([^"]+)">(.*?)</channel>', doc, re.S)
refs = Counter(re.findall(r'<programme[^>]*channel="([^"]+)"', doc))
print('declared', len(chans), 'with zero programmes',
      sum(1 for cid, _ in chans if refs.get(cid, 0) == 0))
PY
```

**证据**：§4 的命令重放得到 §5 的全部数字；每条命中的 `EPG_MATCH_HIT` 现在自带 `programmesInWindow` 与 `chosen`，"这个频道为什么绑在这个 guide 上"由日志单独回答。

---

_本记录由 dev-A 第 27 轮产出于卡 `EPG-BIND` 收口时；结论变更走 G1/G2，不改本文件的既有结论。_
