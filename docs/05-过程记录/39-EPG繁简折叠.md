# 39 · EPG 繁简折叠（繁体→简体归一化 + 覆盖率缺口收尾）验证

**卡片**：`docs/05-过程记录/37-P3-5EPG覆盖率.md` §7 第 4 条（P3-5 遗留：港澳台 guide 用繁体，只靠别名表衔接 → 建议单开一卡）＋ §8 第 2 条（"繁体字与简体字折叠：不做…建议单开一卡"）。
**执行**：dev-B 第 11 轮（temp `worker-dev-b-11`，worktree `worktrees/worker-dev-b-11`，分支 `agent/worker-dev-b-11`）。
**基线**：HEAD `b93fc3b`（含 P3-5 的四源 + 归一化 + 别名表；全量 209/658、主流 153/156）。
**范围**：非设备任务（**未用电视**）；不 commit/push；不改 `docs/01–04`；**不动 EPG 源清单与别名维护规则**。

---

## 0. 结论（先看这五条）

1. **折叠方案 = 一张内置的「繁体→简体」单字表**，只在生成表时用到 OpenCC（Apache-2.0），**没有引入任何运行时依赖**：130 个字、源码约 800 B、纯 Kotlin 常量，`verifyPureKotlin` / 依赖矩阵不受影响（§5）。
2. **覆盖率头数没有变**：全量 209/658 = 31.8%（折叠前 209/658），主流 153/156 = 98.1%（折叠前相同）。变的是**匹配层级**：`NAME_FUZZY` 65 → 70（+5 个「频道×源」对），`NAME_EXACT` 137、`ALIAS` 23 不变（§3）。
3. **折叠真正修好的东西是「节目深度」而不是「覆盖率头数」**：5 个频道的最终绑定从内地源换到港澳源，保留窗内的节目行数从 `0 / 6 / 10 / 12` 变成 `7 / 60 / 101 / 129`（§3.2）。其中 `三沙卫视` 折叠前绑到的是一个**一条节目都没有**的内地 id（app 里就是空的）——覆盖率算它已覆盖，用户看不到任何节目。
4. **三个港澳台缺口（`TVB星河频道` / `澳视澳门` / `中天新闻`）逐条验证：四个源里都没有这些频道**，繁体写法也没有（折叠后逐一比对 4 个 guide 的全部 `<display-name>`，0 命中；近邻见 §4）。这不是匹配链漏配，**折叠也补不上**，只能靠 P3-4 手动绑定或换源。折叠的价值在这三个台上是"源收录的那天自动接上"（已用 `澳視澳門` 写单测钉住）。
5. **不误伤**：折叠只产出查找键，**从不改写 guide 显示名**（`EPG_MATCH_HIT.guideKey` 仍是 `鳳凰衛視中文台`）；单字替换不删字符，因此不会让不同长度的名字变相等；表内唯一的"两繁体字归一"是 `綫`/`線`→`线`（同一个字的两种写法），有单测与反例（§6）。

---

## 1. 交付清单

新增 2 个文件：

| 文件 | 作用 |
| --- | --- |
| `core/epg/…/EpgTraditionalFold.kt` | 繁体→简体单字表（130 条，两条等长常量对齐）＋ `fold()` / `hasTraditional()` / `size`；KDoc 写明表的来源（四个 guide 的 `<display-name>` ＋ 出厂夹具，用 OpenCC `TSCharacters.txt` 求差）、余量字符与安全边界 |
| `core/epg/src/test/…/EpgTraditionalFoldTest.kt` | 8 条：两半对齐 / 表规模钉死 / 无恒等映射 / 唯一归一冲突是 `綫`·`線` / 幂等 / 长度与顺序不变 / 简体名原样返回 / 反例（不同频道不折叠到同一个） |

改动 6 个文件：

| 文件 | 改动 |
| --- | --- |
| `core/epg/…/EpgNameVariants.kt` | `canonical()` 与 `variants()` 接入脚本折叠；**规则顺序写进 KDoc**（①繁简 ②`+`→plus ③去标点 ④去 feed 标记），`variants()` 改为对四个规则的 16 个子集枚举，脚本折叠占最高位 ⇒ 无繁体字的键**变体集合与顺序与 P3-5 逐字相同** |
| `core/epg/…/EpgMatcher.kt` | 仅注释：tier 3 描述补脚本折叠；`matchedOn` / `guideKey` 的说明写明"折叠是查找键，guide 原文不改写"（**逻辑零改动**） |
| `core/epg/src/test/…/EpgNameVariantsTest.kt` | +3：折叠先于 feed 标记（`cctv1標清`→`cctv1`）；guide 侧也折叠且原文在最前；无繁体字的键变体不膨胀 |
| `core/epg/src/test/…/EpgMatcherTest.kt` | +3：`澳視澳門`（繁体 guide）× `澳视澳门`（简体清单）在**空别名表**下命中 tier 3 并同时报出两侧拼写；`鳳凰衛視中文台` 同理（P3-5 的两条别名条目不再是唯一出路）；反例：`中天綜合台`/`中天娛樂台`/`黃金翡翠台` 不互串，`中天新闻` 仍是 miss |
| `core/data/src/test/…/epg/EpgNetworkSampleTest.kt` | 采样输出加一行 `coverage-spotlight`：指定频道的最终绑定（guide id + 命中层级 + 该 id 在保留窗内的节目行数）——本轮的「3 个缺口」与「换源」两类结论都由它出数 |
| `docs/05-过程记录/00-索引与模板.md` | 追加第 39 行 |

> 边界遵守：`BuiltInEpgSources.kt`（源清单）与 `EpgAliases.kt`（别名表与 5 条维护规则）**一行未改**。

---

## 2. 命令与结果

环境：`export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`（`ANDROID_HOME` 已在 env）。命令都在本 worktree 根执行。

| 命令 | 结果 |
| --- | --- |
| `./gradlew --offline check` | **BUILD SUCCESSFUL**（1167 tasks，1m35s；含纯 Kotlin 守护 + 依赖守护 + 全模块单测 + lint） |
| `./gradlew --offline verifyModuleDependencies` | `OK (19 modules; … no forbidden direct dependency, no api exposure, no cycles)` |
| `./gradlew --offline :core:epg:testDebugUnitTest` | **65** tests / 0 失败（本轮 **+14**：`EpgTraditionalFoldTest` 8、`EpgMatcherTest` 3、`EpgNameVariantsTest` 3） |
| `bash tools/ci/sensitive-info-guard.sh` | `OK — 0 unallowlisted hit(s)`（退出码 0） |
| 全仓 debug 单测（XML 汇总） | **801** / 0 失败 / 1 跳过（另有纯 Kotlin `test` 133 条）；release 同样 801 / 0 / 1 |
| `./gradlew --offline :core:epg:lintDebug` | BUILD SUCCESSFUL（`EpgTraditionalFold` 无新增告警） |

真网络采样（与 P3-5 同一条命令、同一夹具、同一台机器）：

```bash
./gradlew --offline :core:data:testDebugUnitTest -Piptv.epgSample=1 \
    --tests '*EpgNetworkSampleTest*' --rerun -i | grep -E 'coverage|fixture:'
```

A/B 的取法（可复现、无残留）：把 `EpgTraditionalFold.fold()` 临时改成 `return key`，跑一次采样得到"折叠前"，再改回来；改动前后 `shasum -a 256 core/epg/…/EpgTraditionalFold.kt` 相同（`05ae1437…7a9d6`），工作区无残留。真实文件的覆盖率数字与下方离线镜像**逐台一致**。

---

## 3. 覆盖率回归（折叠前 vs 折叠后）

### 3.1 总表

| 指标 | 折叠前（A/B 实测） | 折叠后 | 说明 |
| --- | --- | --- | --- |
| 覆盖率（全部频道） | **209/658 = 31.8%** | **209/658 = 31.8%** | **±0** |
| 覆盖率（主流频道，P3-5 §5 口径） | **153/156 = 98.1%** | **153/156 = 98.1%** | **±0** |
| 分组 | 央视 80/80、卫视 69/69、港澳台 4/7、地方 55/500、其他 1/2 | 完全相同 | 五个分组逐组无变化 |
| 命中层级（按源累计） | `NAME_EXACT 137 / NAME_FUZZY 65 / ALIAS 23` | `NAME_EXACT 137 / NAME_FUZZY 70 / ALIAS 23` | **`NAME_FUZZY` +5** |
| `EPG_MATCH_HIT` / `EPG_MATCH_MISS` | 225 / 2407 | **230 / 2402** | +5 个"频道×源"对 |
| 端到端 / 入库节目行 | 7.5 s / 63479 | 8.2 s / 63481 | 两次都是 4 源 17.7 万条节目；行数差（±2）是保留窗随时钟滑动的抖动，不是折叠效果 |

**为什么头数不动**：这 5 个台在折叠前就已经被**内地源**覆盖了（tier 1/2/别名），折叠只是让港澳源**也**命中，而 `LoadEpgUseCase` 的绑定是**逐源覆盖、后命中的源说了算**。所以折叠的效果先落在"绑定换到哪个源"，其次才是"覆没覆盖"。

### 3.2 换源带来的节目深度（`coverage-spotlight`，保留窗 `[now−6h, now+48h]` 内的行数）

| 频道 | 折叠前绑定（层级） | 前·行数 | 折叠后绑定（层级） | 后·行数 |
| --- | --- | --- | --- | --- |
| `凤凰卫视中文台` | `epg.pw CN` 561392（ALIAS） | 10 | `epg.pw HK` 410378（NAME_FUZZY） | **101** |
| `凤凰卫视资讯台` | `epg.pw CN` 561393（ALIAS） | 12 | `epg.pw HK` 410355（NAME_FUZZY） | **129** |
| `三沙卫视` | `epg.pw CN` 561368（NAME_EXACT） | **0** | `epg.pw HK` 410381（NAME_FUZZY） | 7 |
| `深圳卫视` | `epg.pw CN` 539856（NAME_EXACT） | 6 | `epg.pw HK` 540037（NAME_FUZZY） | **60** |
| `深圳卫视 高清` | `epg.pw CN` 539856（NAME_FUZZY） | 6 | `epg.pw HK` 540037（NAME_FUZZY） | **60** |
| `翡翠台` / `明珠台` | `epg.pw HK` 368366 / 368369（NAME_EXACT） | 86 / 72 | 不变 | 86 / 72 |
| `TVB星河频道` / `澳视澳门` / `中天新闻` | `null`（NONE） | 0 | `null`（NONE） | 0 |

要点：**港澳源对这几个台的数据比内地源厚一个数量级**（101 vs 10、129 vs 12、60 vs 6），而 `三沙卫视` 是个极端例子——内地 id 声明了 `<channel>` 却**一条 `<programme>` 都没有**，折叠前它是"覆盖率算 100%、界面全空"的假覆盖。

### 3.3 归因（离线镜像，端点校准）

像 P3-5 §5.1 一样，用一份 JS/Python 镜像复刻同一套 `EpgNameKey` + `EpgNameVariants` + `EpgMatcher` + 同源序（CN → HK → TW → HK1），分别开关折叠：

| 配置 | 全量 | 主流 | 层级 |
| --- | --- | --- | --- |
| 镜像 R0（折叠关） | 209/658 | 153/156 | `NAME_EXACT 137 / NAME_FUZZY 65 / ALIAS 23` |
| 镜像 R1（折叠开） | 209/658 | 153/156 | `NAME_EXACT 137 / NAME_FUZZY 70 / ALIAS 23` |

两个端点与两次真网络实测**逐台一致**（含未覆盖清单：两次的差集为空），所以"折叠新增的 5 个命中对全部落在 `epg.pw HK`"这条归因可用。镜像脚本不入仓（一次性核对工具，与 P3-5 同一做法）。

---

## 4. 三个港澳台缺口：逐条结论与证据

方法：把四个出厂 guide 原件（2026-09-22 抓取）的全部 `<display-name>`（CN 577 / HK 221 / TW 450 / HK1 143 个不同名）取出，对每个缺口名做**折叠后相等**比较（不是子串猜），并另做原文子串探测找近邻。

| 缺口频道（出厂夹具写法） | 折叠后键 | 四个 guide 里折叠后相等的名字 | 近邻（原文子串探测） | 结论 |
| --- | --- | --- | --- | --- |
| `TVB星河频道` | `tvb星河频道` | **0** | `TVB Plus`（HK/HK1）、`TVBS`/`TVBS 新聞台`/`TVBS新聞`/`TVBS歡樂台`/`TVBS精采台`（TW）；`星河` 四源 0 条 | 源没有数据 |
| `澳视澳门` | `澳视澳门` | **0** | `澳視`/`澳門`（繁体）与 `澳视`/`澳门`（简体）在四个 guide 里**全部 0 条** | 源没有数据 |
| `中天新闻` | `中天新闻` | **0** | 只有 `中天亞洲台`（HK）、`中天綜合台`/`中天娛樂台`（TW），没有 `中天新聞` | 源没有数据 |

对比参照：`凤凰卫视中文台`/`凤凰卫视资讯台` 在 HK 源里**有**（`鳳凰衛視中文台`/`鳳凰衛視資訊台`），折叠前靠别名、折叠后靠 tier 3 —— 这正是"有数据就该命中"的正样本。

**结论**：三个缺口与 P3-5 §4 的判断一致（源没有数据），本轮把"繁体写法也没有"补成了硬证据，并顺带证明了**规则本身对这三个台是有效的**：`EpgMatcherTest` 里用 `澳視澳門` 做 guide、`澳视澳门` 做清单、**空别名表**，命中 tier 3 且 `matchedOn=澳视澳门` / `guideKey=澳視澳門`。所以源一旦收录，不需要再写别名。

---

## 5. 折叠方案：表、体积、许可证、顺序

**方案**：`EpgTraditionalFold` —— 一张繁体→简体**单字**表，两条等长常量索引对齐（`TRADITIONAL_CHARS[i]` → `SIMPLIFIED_CHARS[i]`），`fold()` 逐字符替换；`hasTraditional()` 供调用方快速判空。**130 个字**：

- **126 个来自语料**：四个 guide 的全部 `<display-name>` ＋ 出厂夹具 658 个频道名，取"繁体→简体映射与原文不同"的那些字（例：`視門聞衛鳳綫經綜娛樂華亞臺灣電頻體`）。
- **4 个余量字**：`標`（`標清`，feed 标记）、`導`（`導視`）、`縣`（`某縣台`）、`臺`（`臺視`/`臺中`——今天两个 guide 恰好都写 `台`）。代码里单独一行注明是余量。
- 生成方法写在 KDoc 里（三步：抽 display-name → 用 OpenCC `TSCharacters.txt` 求差 → 保留差异字），未来某个 guide 出现新字，按同样三步重生成即可。

**体积**：源码约 **800 B**（130×2 个字，UTF-8 每字 3 B ⇒ 780 B 数据）。不新增依赖、不新增 asset、不新增生成物 —— 因此**不需要评估库的体积与许可证**，`verifyPureKotlin`（`:core:epg` 是 Android 库但源码纯 Kotlin，`EpgNameKey`/`EpgNameVariants` 同理）与依赖矩阵都不动。仓库里"某个源的一份 guide"都比这张表大三个数量级。

**许可证**：只借用 OpenCC（`https://github.com/BYVoid/OpenCC`，**Apache-2.0**）的 `TSCharacters.txt` 在**建表时**做差集，**没有复制它的代码或数据文件进仓库**，最终落库的是 130 个字（字符与映射本身不受版权保护），并在 KDoc 里注明来源与许可证。

**规则顺序（写进 `EpgNameVariants` 的 KDoc，也是契约）**：

1. **繁→简**（`EpgTraditionalFold`）——先做。它是逐字符替换，与其他三条互不干扰；先做的好处是繁体写法里的 feed 标记（`標清`）已经变成 `标清`，第 4 条就能按同一张后缀表剥掉。
2. **`+` → `plus`** ——在去标点之前；`+` 故意**不在**标点集里，删掉会把 `CCTV5+` 变成 `CCTV5`（串台）。
3. **去标点**。
4. **去 feed 标记**，反复、长后缀优先。

`variants()` 是这四条规则的 **16 个子集**（脚本折叠占最高位），"最少改动者在前"⇒ `EPG_MATCH_HIT.matchedOn` 报的是**最小的那次改动**；没有繁体字的键，16 个子集塌回 P3-5 的同一组、同一顺序（有单测 `a name with nothing to fold keeps exactly the variants it had before the script rule` 钉住）。

---

## 6. 不误伤：三条不变式与被钉住的反例

1. **不改显示名**：折叠只用于查找键。`EpgChannelIndex` 存的仍是 guide 自己的键，命中时 `guideKey` 报的是**原文**（实测 `凤凰卫视中文台` 命中报 `guideKey=鳳凰衛視中文台`），用户看到的永远是 guide 的原样名字；单测 `a traditional guide name binds a simplified playlist name with no alias entry` 断言两侧拼写同时出现在解释里。
2. **不删字、不改长度**：逐字符替换 ⇒ 名字长度与字序不变，不同长度的名字不可能因此相等。表内唯一的"两繁体归一"是 `綫`/`線`→`线`（同一个字的两种写法；TVB 的 `無綫新聞台` 与 `無線新聞台` 本就是同一个台），由单测 `only the documented character pair folds onto the same simplified character` 钉住——**再出现第二对就会红**。
3. **不跨频道**：反例单测 `folding a script never binds a channel to a different one in the same family`：guide 里放 `中天綜合台`/`中天娛樂台`/`中天亞洲台`/`黃金翡翠台`/`翡翠台`，清单放 `中天综合台`/`中天娱乐台`/`中天新闻`/`翡翠台` ⇒ 前两个各归各位、`翡翠台` 归 `翡翠台`（不吞 `黃金翡翠台`）、**`中天新闻` 仍是 miss**（缺口的正确行为是"不匹配"，不是"猜一个"）。另有 `EpgTraditionalFoldTest` 5 组"不同频道不折叠到一起"的反例。

---

## 7. 与文档的偏差 / 需 god（arch）裁决的点

1. **「后命中的源覆盖绑定」的语义被折叠放大了**：`LoadEpgUseCase` 逐源覆盖 `bindings[channelId]`，折叠让 5 个台从内地源换到港澳源（实测数据更厚，见 §3.2）。这是 **P3-5 之前就有的语义**（不是本轮引入），但"多源越多、越晚的源越容易赢"值得被知道：若改成"先到先得"或"按节目数/新鲜度择优"，那是 `LoadEpgUseCase` 的一张新卡（本轮不改）。
2. **脚本折叠归属哪一级**：本轮放在 **tier 3（`NAME_FUZZY`）**，不动 `EpgNameKey`（tier 2 仍是宽字符/空白/大小写折叠）。理由：tier 3 是"可解释、可回退"的折叠层，进 tier 2 会让 `name_key`（有 UNIQUE 约束、入库字段）的语义变化。请 arch 在 `docs/02 §6.3` 回写"脚本折叠属于第三级"。
3. **表规模与来源要登记**：130 个字（126 语料 + 4 余量）＋"OpenCC TSCharacters 求差"的生成方法，建议写进 `docs/02 §6.3` 作为维护规则（否则下一个人不知道表从哪来、什么时候该重生成）。
4. **别名表的两条凤凰条目**：折叠后它们仍会在内地源命中（是"港澳源失败时的兜底"），所以**保留**；但"用别名兜住的繁简差异"从此不必再写。是否清理历史条目留给 P3-4/下一轮（本卡边界：不动别名维护规则）。
5. **三个缺口需要产品决策**：源侧没有数据（§4），出路只有 **P3-4 手动绑定** 或换源（`epgshare01` 没有 CN/TW 目录，iptv-org 的 guide 主机仍是 404）。建议在卡片上明确"这三个台由 P3-4 承接"。
6. **未接生产触发点**（同 P3-5）：本轮只改匹配层，`LoadEpgUseCase` 仍无生产调用者；接上 P2-5 定时刷新或设置页"立即更新"后，本轮的换源效果才会在电视上可见。

---

## 8. 未做项 / 边界

1. **未扩表到"全量繁体字"**：刻意不扩。表随语料走，超出语料的字需要一次重生成与一次 review（KDoc 写了三步）；只有 4 个余量字是凭常识加的，且都用单测点过。
2. **未做反向（简→繁）**：只做繁→简，简体名不会被改写；`EpgNameKey` 与 `channel.name_key` 一律不动。
3. **未改源清单 / 别名表 / 别名维护规则**（本卡边界）。
4. **未做 P3-4 手动绑定 UI**、未换源（§7 第 5 条）。
5. **未真机/电视验证**：本卡为非设备任务（未用电视），无 logcat/截图；"换源后节目更厚"是数据库行数与 guide 原件的数，未在电视上眼看。
6. **未做并发拉取**（4 源仍是顺序，端到端 6–12 s，与 P3-5 相同）。

---

## 9. 复现清单

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd <worktree>

./gradlew --offline check                       # 全绿（含纯 Kotlin 守护、依赖守护、lint、全模块单测）
./gradlew --offline verifyModuleDependencies     # OK (19 modules)
./gradlew --offline :core:epg:testDebugUnitTest  # 65 条（本轮 +14）
bash tools/ci/sensitive-info-guard.sh            # OK — 0 unallowlisted

# 真网络覆盖率采样（默认关闭；重跑必须加 --rerun，否则 Gradle 判 UP-TO-DATE）
./gradlew --offline :core:data:testDebugUnitTest -Piptv.epgSample=1 \
    --tests '*EpgNetworkSampleTest*' --rerun -i | grep -E 'coverage|fixture:'
# 关注行：coverage-by-group / coverage-mainstream / coverage-hit-tiers / coverage-spotlight

# A/B（折叠前）复现：把 EpgTraditionalFold.fold() 的第一行改成 `return key`，再跑上一条命令

# 四个内置源是否可达（只读，不落盘）
for u in https://epg.pw/xmltv/epg_CN.xml.gz https://epg.pw/xmltv/epg_HK.xml.gz \
         https://epg.pw/xmltv/epg_TW.xml.gz \
         https://epgshare01.online/epgshare01/epg_ripper_HK1.xml.gz; do
  curl -s -o /dev/null -w "%{http_code} %{size_download} $u\n" --max-time 60 -L "$u"
done
```

**证据**：§2 的命令重放即可得到 §3/§4 的全部数字；`EPG_MATCH_HIT`（DEBUG）给出每条命中的 `strategy` / `matchedOn` / `guideKey`，`coverage-spotlight` 给出"哪个源在服务这个频道、它到底有多少节目"。

---

_本记录由 dev-B 第 11 轮产出于卡 `P3-5-繁简折叠` 收口时；结论变更走 G1/G2，不改本文件的既有结论。_
