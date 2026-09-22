# 43 · EPG 匹配层候选化（修 27 台空绑的根因）验证

**卡片**：`docs/05-过程记录/40-EPG绑定择优与覆盖率口径.md` §6 第 1 条（"空绑根因要单开一卡"，给了三条候选修法与建议顺序）＋ 同文件 §5.4 的 27 个主流空绑清单。
**执行**：dev-B 第 13 轮（temp `worker-dev-b-13`，worktree `worktrees/worker-dev-b-13`，分支 `agent/worker-dev-b-13`）。
**基线**：HEAD `b533c3e`（含 41 号频道管理器、40 号绑定择优、39 号繁简折叠）。
**范围**：非设备任务（**未用电视**）；**不 commit / 不 push**；**不改 `docs/01–04`**；**不动源清单 / 别名表 / 繁简折叠表**；不改 `LoadEpgUseCase` 的写库语义（只改匹配候选的产出）。

---

## 0. 结论（先看这五条）

1. **"先命中先赢"改成了"各层级命中都是候选"。** `EpgMatcher` 不再在某层命中后 `continue`：tier 1/2/3/4 的命中全部按 guide id 去重后交给 `EpgBindingPreference`（40 号已就位的决策器，窗内节目数优先）。**tier 4（别名）不再是死代码** —— 这正是 40 号 §5.4 说的"补别名也修不好"的那层短路。
2. **guide 索引允许同名多 id。** `EpgChannelIndex.byNameKey` 从 `Map<String,String>` 改成 `Map<String,List<String>>`，`EpgNameVariants.index` 的每个变体也保留它代表的**全部** id。真网 CN guide 有 **52 组**名字被声明 2–3 次（`东方卫视` 三个 id），旧实现只留第一个（先出现者恰好有节目，所以今天无症状，是**运气**）。
3. **零节目 `<channel>` 用"决策侧自然落败"，不剪索引**（40 号 §6 给出的①/②/③三条里选"②的完整版"，理由见 §2.3）。`EpgBindingPreference` 的"窗内节目数最多者胜"本来就是这条规则，**不新增第二处**。
4. **真网三口径前后**（`epg.pw CN/HK/TW` + `epgshare01 HK`，同 40 号命令）：主流 **withProgrammes 126/156 = 80.8% → 153/156 = 98.1%**（目标 ≥90% ✅）、主流 emptyBinding **27 → 0**；全量 withProgrammes **173 → 200**、emptyBinding **36 → 9**。**id 侧 matched 209/658 前后不变**，即既有绑定一条没丢、一条没降（机制上保证，见 §3.3）。
5. **没有动别名表就达到了目标。** 40 号 §5.4 建议的两步（改匹配层 + 补 `CCTVn → CCTV-n 栏目名` 别名）**只需第一步**：新增的第 5 条归一化规则"编号手柄"把 `CCTV2`（播放列表写法）与 `CCTV-2 财经`（guide 的栏目名写法）同时约化成 `cctv2`，两条 id 一起进候选、由深度定夺。**别名表 29 条一条未改**（边界保持）。

---

## 1. 交付清单（新增 1 文件 / 改动 6 文件）

新增：

| 文件 | 作用 |
| --- | --- |
| `docs/05-过程记录/43-EPG匹配层候选化.md` | 本文件 |

改动（全部在 `:core:epg` 与 `:core:model`，无 API 破坏）：

| 文件 | 改动 |
| --- | --- |
| `core/model/…/Epg.kt` | `EpgChannelIndex.byNameKey: Map<String, List<String>>`（同名多 id） |
| `core/epg/…/EpgMatcher.kt` | `match()` 改为**逐层提议、按 guide id 去重**（`LinkedHashMap`，不用 API 24 的 `putIfAbsent`）；`variantHit` → `variantHits`（返回该键**所有**变体命中的全部 id）；`resolveAlias` 返回**全部**解析结果；`epgChannelIds()` 摊平；`epgChannelIndex()` 按 guide 顺序累积每个名字的全部 id；`EpgMatchReport.attempted` 改为**按频道**计数（不再被"多提议"抬高） |
| `core/epg/…/EpgNameVariants.kt` | 新增第 5 条规则 `handle()`（编号手柄，见 §2.2）；`index()` 签名/返回改为多 id；KDoc 记录规则顺序与两条**拒绝**原则 |
| `core/epg/src/test/…/EpgMatcherTest.kt` | 索引字面量改多 id；新增 3 条（零节目存根仍进候选并由深度落败、前层命中不再挡别名层、同名多 id 全部进候选） |
| `core/epg/src/test/…/EpgNameVariantsTest.kt` | 索引字面量/断言改多 id；新增 2 条（编号手柄约化、编号手柄的两条拒绝原则） |
| `core/epg/src/test/…/XmltvPullParserTest.kt` | `byNameKey` 断言改列表 |

**未改**：`EpgAliases`（别名表 29 条）、`EpgTraditionalFold`（130 字表）、`BuiltInEpgSources`（源清单）、`LoadEpgUseCase`（写库语义一行未动）、`docs/01–04`。

---

## 2. 改动细节

### 2.1 各层级都产出候选（工作项 1）

旧逻辑对每个频道是"tier 1 → 2 → 3 → 4，命中即 `continue`"，所以一个频道在**同一份 guide 内**最多只有一个候选。现在：

```
for channel:
  candidates = LinkedHashMap<guideId, EpgMatchResult>()   // 按 guide id 去重，保留最早（最好）的层级
  ① tvg-id 命中        → offer(...)                      TVG_ID
  ② byNameKey[key] 全部 id → offer(...)                  NAME_EXACT
  ③ 该键所有变体命中的全部 id → offer(...)                NAME_FUZZY（matchedOn=该变体, guideKey=guide 原名）
  ④ 别名目标解析出的全部 id → offer(...)                 ALIAS
  空 → miss（`EPG_MATCH_MISS` 只在真的无候选时产生）
```

**层级顺序仍决定"解释"**：同一 guide id 被两层命中时保留层级更高（更早）的那条作为 `type`，所以既有测试里 `TVG_ID` 仍压过 `NAME_EXACT`、`NAME_FUZZY` 仍压过 `ALIAS`。"哪个 id 最终被写库"则完全交给 `EpgBindingPreference`。

### 2.2 编号手柄规则（工作项 2 的使能件）

`EpgNameVariants` 的第 5 条规则 `handle()`，作用在 `cctv<数字>[+]` 这一形状上：

```
handle("cctv2")        = "cctv2"      （已是最简，去重后不新增变体）
handle("cctv-2财经")    = "cctv2"      ← 与上一行同一个查找键
handle("cctv-16奥林匹克") = "cctv16"
handle("cctv-5+体育赛事") = "cctv5+"
handle("cctv4k")       = "cctv4k"     ← 拉丁尾巴：拒绝（CCTV4K ≠ CCTV4）
handle("cctv-4(亚洲)")  = "cctv-4(亚洲)" ← 括号尾巴：拒绝（CCTV-4 亚洲/欧洲/美洲 是不同 feed）
```

三条实现约定，都是为了不悄悄改已有行为：

- **从原始键（及其繁简折叠）计算**，不参与前面四条规则的 16 种子集枚举。若从"去标点后的形式"计算，`cctv-4(亚洲)` 会先丢掉括号、再被判成 `cctv4` —— 拒绝原则就失效了。
- **追加在 16 种变体之后**（最重的改动排最后），且"自己就是手柄"时去重为 no-op ⇒ 一条手柄规则碰不到的键，变体集合与顺序与上一轮**逐字相同**（`EpgTraditionalFold` 回归单测因此不动）。
- **`canonical()` 不含手柄**：它是"解释一次命中"用的四种规则的最强形式，被 `EpgNameVariantsTest` 钉住；手柄是**第二个身份**，只在 `variants()` 里作为查找键出现。这条差异写进了 KDoc。

### 2.3 零节目 `<channel>`：不剪索引，让其在决策里落败（工作项 3）

40 号 §6 给了③"把整份 guide 内零节目的 `<channel>` 排除出索引"与②"多候选让决策挑"。本轮选**决策侧**，理由：

1. **语义重复**：`EpgBindingPreference` 的规则 1 就是"窗内节目数最多者胜"，剪索引等于把同一条规则实现两处，两处迟早不一致。
2. **不丢"guide 补齐后自动接上"**：剪掉的 id 将来补齐节目也接不回来；留在候选里、由深度落败，则下一次刷新自动就位。
3. **口径更诚实**：剪索引会把"有 id 但空"变成"未覆盖"，`emptyBinding` 这一栏就永远测不出来；它的价值正在于把"覆盖在纸上"和"真的有节目"分开（40 号 §0.5）。
4. **绑定不回退**：唯一候选即使 0 条节目也照样绑定（40 号的既有承诺），所以本轮 27 台是"换到更深的 id"，不是"解绑"。

---

## 3. 三口径前后对比（真网采样，`-Piptv.epgSample=1`，2026-09-22）

### 3.1 全量与主流

| 口径 | 前（40 号 / 本轮基线复核） | 后（本轮） |
| --- | --- | --- |
| 全量 matched（有 id） | 209 / 658 = 31.8% | 209 / 658 = 31.8%（**±0**） |
| 全量 withProgrammes（可看） | 173 / 658 = 26.3% | **200 / 658 = 30.4%** |
| 全量 emptyBinding | 36 | **9** |
| 主流 matched | 153 / 156 = 98.1% | 153 / 156 = 98.1%（**±0**） |
| **主流 withProgrammes（门槛口径）** | **126 / 156 = 80.8%** | **153 / 156 = 98.1%** ✅（目标 ≥90%） |
| 主流 emptyBinding | 27 | **0** |

分组（`byGroupWithProgrammes` / `byGroupTotal`）：

| 组 | 前 | 后 |
| --- | --- | --- |
| `cctv` | 53 / 80 | **80 / 80** |
| `sat` | 69 / 69 | 69 / 69 |
| `hmt` | 4 / 7 | 4 / 7 |
| `local` | 46 / 500 | 46 / 500 |
| `other` | 1 / 2 | 1 / 2 |

采样本轮其它数字（供下一位比）：`channels=1552`（四 guide `<channel>` 并集）、`programmes=177447`、`skipped=113887`、`elapsedMs≈7.6 s`；匹配层级 **`NAME_EXACT 137 → 206`、`NAME_FUZZY 70 → 197`、`ALIAS 23 → 25`**；`EPG_MATCH_HIT` 行数 **230 → 428**（该行现在是**每个候选一行**，行数上升是设计结果，见 §7 第 2 条）。

### 3.2 27 个主流空绑：逐台换到了哪个 id

winner 取"窗内节目数最多"，括号内是保留窗内行数（`win` = 胜出）：

```
CCTV2  = [561310(0,lose), 545933(86,win)]        CCTV3  = [561311(0,lose), 545934(56,win)]
CCTV5  = [561313(0,lose), 545936(31,win)]         CCTV5+ = [561314(0,lose), 545937(23,win)]
CCTV6  = [561315(0,lose), 545938(43,win)]         CCTV8  = [561317(0,lose), 545940(90,win)]
CCTV10 = [561319(0,lose), 545942(77,win)]         CCTV11 = [561320(0,lose), 545943(42,win)]
CCTV12 = [561321(0,lose), 545944(71,win)]         CCTV14 = [561323(0,lose), 545946(41,win)]
CCTV15 = [561324(0,lose), 545947(40,win)]         CCTV16 = [561405(0,lose), 545948(28,win)]
CCTV17 = [561325(0,lose), 545949(59,win)]
（另 14 台是它们的「高清」「标清」写法，同一对候选，同样的赢家）
```

顺带修正的**深度**变化（原本就非空，只是绑得浅）：`CCTV1`/`CCTV1 高清`/`标清` 由港澳 `CCTV-1.hk`(83)（`CCTV-1 综合`(78) 落败）；`CCTV4` 由 `CCTV-4.hk`(93)；`深圳卫视`/`深圳卫视 高清` 由 `540037`(60) 换到 **`545979`(67)**；`三沙卫视` 仍为港澳 `410381`(7)（内地两候选 0 条）。卫视 30 台全部由内地 `5459xx` 系列胜出（`东方卫视` 92、`湖北卫视` 106 …），旧的"先出现者"候选仍列在竞争表里。

### 3.3 "既有正确绑定不下降"是机制保证

候选集合是本轮改动前候选集合的**超集**：每个层级的返回从"一个 id"变成"一个 id 列表"（tier 2 的 `byNameKey`、tier 3 的变体表、tier 4 的别名目标），去重只按 guide id。因此改动前胜出的那个 id **仍然在**候选里，而决策规则是"窗内节目数最多者胜（并列再比源序/层级/id）"⇒ 胜出者的深度**不可能变小**。实测三口径与 `matched ±0` 与之一致。

---

## 4. 剩余空绑清单（9 条，全部 `local`）与原因

```
local:9  上海新闻综合=539884, 广东影视=539661, 云南影视=539671, 河南都市=539875, 上海都市=539846,
         广东少儿=539770, 海南少儿=539723, 贵州科教=544577, 广东体育=539748
```

**原因：源没有数据（且没有更厚候选）。** 逐条回查本轮抓取的 `epg.pw CN` 原件：这 9 个 id 在**整份 guide 内**（不只是保留窗内）`<programme>` 计数都是 **0**，且其 display-name 只声明一次、无同名重复声明 ⇒ 候选集里只有它自己，"自然落败"无从发生，绑定按 40 号承诺**保留**（空绑而非解绑）。它们不在主流切片里，不影响本轮 ≥90% 的门槛。修法只有换源或 P3-4 手动绑定，属源清单质量，不在本卡边界。

三个**主流**缺口仍是老样子（P3-11/P3-12 已定性为源没有数据）：`TVB星河频道`、`澳视澳门`、`中天新闻` —— 四 guide 的 display-name 逐台比对 0 命中；只能 P3-4 手动绑定或换源。

---

## 5. 单测与 check 结果

| 项 | 结果 |
| --- | --- |
| `:core:epg` debug 单测 | **82**（上一轮 77，本轮 **+5**） |
| 全仓 debug 单测 | **880 / 0 失败 / 0 错误 / 2 跳过**（本分支已含 41 号 P3-4；本轮相对本分支基线 +5，全在 `:core:epg`） |
| `./gradlew --offline check` | **SUCCESSFUL**（1167 tasks，一次绿；含 lint + 依赖守卫 + 全模块单测） |
| `./gradlew --offline verifyModuleDependencies` | **OK (19 modules)** |
| `./gradlew --offline :app:assembleDebug` | **SUCCESSFUL**（`app-debug.apk` 11,400,614 B，sha256 `9baf4052…55b228`） |
| `bash tools/ci/sensitive-info-guard.sh` | **OK — 0 unallowlisted**（新文件也手工按守卫规则查过） |

新增/改写的断言（工作项 5）：

- `EpgMatcherTest.a zero-programme stub is offered as a candidate and loses to the column id` —— 多候选排序 + 零节目落败（端到端：matcher → `EpgBindingPreference`）。
- `EpgMatcherTest.a name hit no longer hides the alias tier` —— "别名命中不再被前层短路"的回归断言（旧实现里该别名层是死代码）。
- `EpgMatcherTest.a guide name declared twice offers both ids` —— 同名多 id。
- `EpgNameVariantsTest.the numbered handle folds a guide column name onto the playlist's bare number`、`…never folds a different channel onto the plain number` —— 手柄规则与两条拒绝原则（`CCTV4K`、`CCTV-4 (亚洲)`、`CCTV5+` 不并入 `CCTV5`）。

---

## 6. 复现命令

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd <worktree>

./gradlew --offline check                          # 全绿（lint + 纯 Kotlin 守护 + 依赖守护 + 全模块单测）
./gradlew --offline :core:epg:testDebugUnitTest    # 82 条
./gradlew --offline verifyModuleDependencies        # OK (19)
bash tools/ci/sensitive-info-guard.sh              # OK — 0 unallowlisted

# 真网络采样（默认关闭；重跑必须加 --rerun，否则 Gradle 判 UP-TO-DATE）
./gradlew --offline :core:data:testDebugUnitTest -Piptv.epgSample=1 \
    --tests '*EpgNetworkSampleTest*' --rerun -i | grep -E 'coverage|fixture:|events:'
# 看三行：coverage-verdicts / coverage-mainstream / coverage-empty-binding
```

---

## 7. 未做项 / 给 god（arch）的裁决点

1. **编号手柄是一条新的归一化规则，需要回写 `docs/02 §6.3`**（本轮只改代码与 KDoc）。它在边界内（不是别名表、不是繁简折叠表），但"哪些形状算机械等价"是 §6.3 的口径题。**风险已收敛**：拉丁尾巴（`CCTV4K`/`CCTV-8K`）与括号尾巴（`CCTV-4 (亚洲)`）明确拒绝。**残留暴露面**：无括号的中文地区后缀会折叠（`CCTV4美洲` → `cctv4`，与 `CCTV-4 (亚洲)` 同族）。实测在夹具上无害（`CCTV-4 中文国际` 取到 96 行的内地 id；`CCTV4` 由港澳 93 行胜出），但"读**哪个**中文词"是知识不是机械，故不实现 —— 若 arch 认为必须区分，应改走别名表（本卡边界外）。
2. **`EPG_MATCH_HIT` 行数口径变了：230 → 428**。同一事件码、同一字段集（`programmesInWindow`/`chosen` 语义不变），但现在是**每个候选一行**（一个频道可能多行）。`docs/03 §3.3` 的 50 个事件码**未新增**，行数不是文档承诺；若诊断面板或任何按行数写的断言依赖"每频道一行"，需要回写。本卡未发现此类依赖。
3. **`EpgChannelIndex.byNameKey` 的类型变了**（`:core:model` 公共类型）：`Map<String,String>` → `Map<String,List<String>>`。仓库内消费方只有 `:core:epg` 与其测试（已同步），无其他模块引用。若外部约定依赖旧形状，是一处破坏性变更。
4. **未做真机验证**（本卡非设备任务，按要求**未用电视**）："界面真的不空了"仍需一次真机验收（最直观的是任意一个 `CCTV2`/`CCTV10` 台的节目单从空变为有内容）。日志层证据：`EPG_COVERAGE` 的 `mainstreamProgrammedRatio=0.981`、`mainstreamEmptyBinding=0`。
5. **未做**：并发拉取（4 源仍顺序，端到端 6–12 s）；`EpgRefreshSettings` 落库；本地 9 条空绑（同上，属源清单）。

---

_本记录由 dev-B 第 13 轮产出于卡 EPG-BIND 收口时；结论变更走 G1/G2，不改本文件的既有结论。_
