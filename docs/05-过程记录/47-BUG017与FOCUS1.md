# 47 · BUG-017 信息条截断 + FOCUS-1 浏览页冷启动焦点

> 关卡：**P3-G 出口前置修复** ｜ 责任人：dev-B（`worker-dev-b-15`）
> 卡：`BUG-017（S2 信息条文本列被挤到 209 px）` + `FOCUS-1（浏览页冷启动焦点落在页头按钮）`
> 设计基线：[02 技术架构设计](../02-技术架构设计.md) §7.4（播放界面）/ §8.1（导航与返回契约）/ §8.2（焦点与键位冻结契约）
> 证据来源：[42-VERIFY1真机验证报告](./42-VERIFY1真机验证报告.md) §2（信息条截断，`uiautomator` bounds）、§9（BUG-017 登记）；[43](./43-EPG匹配层候选化.md)/[45](./45-P3-7焦点与返回键打磨.md) 的布局与焦点记录
> 状态：**待 god 集成**（工作区未提交；本轮只用源码、单测与静态尺寸计算，**未用电视**）

**基线**：worktree `worktrees/worker-dev-b-15`，HEAD `e20e033`（含 VERIFY-1 报告与 BUG-016/017/018 台账）。

**边界遵守**：不改键位映射与播放链路（§8.2 冻结：上下换台、左右画幅/重试、数字跳台）；**未用电视**；不 commit/push；不改 `docs/01–04`。

---

## 1. 交付物清单

| # | 交付物 | 位置 |
|---|---|---|
| 1 | 信息条改两行布局（文本列独占首行，四控件独占次行） | `feature/player/src/main/res/layout/activity_player.xml` |
| 2 | 信息条几何常量 + 尺寸计算纯函数 | `feature/player/.../feature/player/InfoBarLayout.kt` |
| 3 | 信息条尺寸 token | `feature/player/src/main/res/values/dimens.xml`（新增） |
| 4 | 尺寸证据（960 dp / 640 dp 两布局）与「一行放不下」的换行规则 | `InfoBarLayoutTest`（**新增 5 条**） |
| 5 | 浏览页冷启动焦点改到首个频道行 | `feature/channels/.../BrowseActivity.kt`（`submitList` 首帧回调） |
| 6 | 焦点目标选择单测（历史焦点 → 首个频道行 → 兜底） | `ChannelFocusTargetTest`（**新增 1 条**，合计 6 条） |
| 7 | 「10:02 渲染 → 10:07 `View.GONE`」定性 | 本文件 §4 |

---

## 2. BUG-017：信息条两行布局

### 2.1 缺陷与改法

**缺陷（42 号 §2 的真机证据）**：P3-3 把音轨/字幕/过扫描三个控件加进原本只放画幅的信息条后，信息条仍是**单行横向 LinearLayout**：左侧文本列 `layout_weight=1`，右侧四个控件 `wrap_content` 且各有 `minWidth`，谁也压不动谁，文本列只剩 **209 px**。真机 `uiautomator dump` 里 `info_name` 读到 `text="CCTV-10科教"` 而 `bounds=[240,856][449,937]`（宽 209 px），屏幕上只剩 `CCTV-…`；`info_quality`、`info_now_next` 同样被截断。

**改法（两行布局）**：`info_bar` 由横向改**纵向**，分两行——

- **首行** `info_bar_main`：`info_logo` + 文本列 `info_text_column`（`0dp` + `weight=1`）。文本列是首行**唯一**的加权子项，所以它拿到整行宽度，而不是和四个控件抢一行的剩余宽度。
- **次行** `info_controls`：四个控件（画幅/音轨/字幕/过扫描）各自 `0dp` + `weight=1`，**均分**次行。均分是 640 dp 布局下唯一能保证「四个都留在屏内」的排法；固定宽度（旧的 `minWidth` 排法）在 640 dp 会溢出屏幕。

未改任何 `id`、未改任何焦点代码：四个控件的 `nextFocusLeft/Right` 链（`infoAspect ↔ infoAudio ↔ infoSubtitle ↔ infoOverscan`）与「信息条可见时 `root` 左右进入 `infoAspect`」都按 `id` 工作，改布局不动它们。**键位不变**。

### 2.2 尺寸证据（静态尺寸计算；两种布局）

密度口径：真机 1920×1080 上报 xhdpi（2.0）⇒ **960 dp**（与 42 号 §2 的 240 px 左边距一致：`(32+64+24) dp × 2 = 240 px`）；1280×720 同密度 ⇒ **640 dp**，这是必须扛住的窄布局。

常量与公式在 `InfoBarLayout`，测试 `InfoBarLayoutTest` 逐条钉住（`x` 为屏宽 dp）：

| 量 | 公式 | 960 dp | 640 dp |
|---|---|---|---|
| 文本列宽（两行布局） | `x − 2×32 − 64 − 24` | **808 dp** | **488 dp** |
| 文本列下限 | `TEXT_COLUMN_MIN_WIDTH_DP` | 360 dp | 360 dp |
| 控件行宽 | `x − 2×32` | 896 dp | 576 dp |
| 单个控件宽（均分，扣 4×8 边距） | `(控件行 − 32) / 4` | 216 dp | **136 dp** |
| 单个控件文字框 | `控件宽 − 2×6` | 204 dp | **124 dp** |
| 若沿用一行布局，文本列只剩 | `文本列 − 4×(96+8)` | 392 dp | **72 dp** |

**不截断判据**（保守字宽模型：全宽字 1 em、ASCII 0.55 em；测试 `textWidthDp`）：

| 文本 | 字号 | 计算宽 | ≤ 488 dp（640 布局） |
|---|---|---|---|
| 频道名 `CCTV-10科教`（42 号实测名） | 30sp | 186 dp | ✅ |
| 频道名 `CCTV-13新闻频道高清`（更长的改名） | 30sp | 296 dp | ✅ |
| now/next `正在播出：新闻联播` | 18sp | 162 dp | ✅ |
| now/next `接下来：新闻联播特别报道` | 18sp | 234 dp | ✅ |

对照：**缺陷时文本列只有 209 px（104 dp）**，上表第一行（186 dp）就放不下——这正是真机截图只剩 `CCTV-…` 的原因。测试里有一条断言专门钉这个：`textWidthDp("CCTV-10科教", 30) > 104`。

**控件仍遥控可达**：四个控件 `minHeight=48dp`、均留在屏内（136 dp ≥ 自身最大文字 112 dp），焦点链与键位不变（45 号 §2.1 的键位冻结未动）。

### 2.3 换行规则（为什么是两行，而不是少放控件）

`InfoBarLayout.inlinePlacementFits(x)`：若四个控件与文本列共处一行，文本列还剩 `文本列 − 4×(minWidth+边距)`；只有这个值 ≥ 下限 360 dp 才允许单行。

- **960 dp**：`808 − 416 = 392 ≥ 360` ⇒ 单行**勉强可行**；
- **640 dp**：`488 − 416 = 72 < 360` ⇒ 单行**必然截断**，控件必须换行。

⇒ 两行是**宽度驱动**的结论，不是口味：只要 1280×720 布局存在，单行信息条就不可能同时满足「频道名不截断」和「四个控件都在屏内」。测试同时钉住 392/72 两个数与 `false` 判定。

---

## 3. FOCUS-1：浏览页冷启动焦点

**缺陷**：浏览页首帧渲染的回调里写的是 `list.post { list.getChildAt(0)?.requestFocus() }`。列表**第 0 行是分组标题 `GroupHeader`，不可聚焦**，所以这句是**空操作**；框架随后把焦点给了屏幕上第一个可聚焦项——页头的「导入播放列表」按钮（42 号 §0 的「焦点真相」、45 号 §7/§8 裁决点 2 均有记录）。冷启动落焦在页头按钮，不在任何频道上。

**改法**：首帧回调改调 `restoreFocusOrFirstRow(focused?.channelId)`——与「播放返回」同一条链：

1. **有历史焦点** ⇒ 回该频道行（`focused` 是上一次聚焦的 `ChannelItem`）；
2. **无历史焦点**（冷启动，`focused == null`）⇒ **首个频道行**（`ChannelFocusTarget.positionOf` 用 `rows.indexOfFirst { it is ChannelItem }`，跳过不可聚焦的分组标题）；
3. **列表为空/只有标题** ⇒ `NO_ROW`，什么都不做（不调焦点 API，避免无焦点假动作）。

`restoreFocusOrFirstRow` 的既有语义原样保留（`scrollToPosition` + `doOnLayout` + 找 holder 再 `requestFocus`），只是多了一个冷启动入口；`onResume` 的再武装逻辑不动。**未改键位、未改返回键层级。**

**给 QA 的提醒（写进验证文件）**：冷启动焦点从「页头第 1 个按钮」移到「列表第 1 行频道」后，**QA 脚本的按键计数可能 +1**——以前从页头按钮进入列表要先按若干次 `DOWN`/`OK`，现在直接落在频道行上，同一段操作少（或多）一次按键。凡 42 号 §5/§7 里「从页头走到列表」的脚本步骤，复测时按新落点重数，不要沿用旧计数。

---

## 4. 「同一会话 10:02 渲染 → 10:07 变 View.GONE」：设计，不是缺陷

42 号 §2(c) 记录：10:02 截图第三行有 `player_now_next_now`；10:07 同一会话 `uiautomator dump` 里 `info_now_next` 节点消失（`View.GONE`），第三行变成橙色 `正在重试…`。**结论：这是设计分支（`NowNextLabel.Kind.NONE`），不是 5 s 淡出造成的缺陷。** 代码依据：

1. **淡出只动整条信息条，从不动单个子视图。** `showInfoBar`/`hideInfoBar` 走的是 `infoBar.animate().alpha(...)`，淡出结束才 `infoBar.visibility = View.GONE`（`PlayerActivity.kt` 的 info bar lifecycle 段）。若淡出真的把信息条收掉，`info_status` 也会一起消失——而 10:07 的 dump 里 `info_status` 正显示橙色「正在重试…」，说明**信息条当时是可见的**，淡出根本没发生。
2. **`info_now_next` 唯一被置 `GONE` 的地方是 `renderInfoBar` 的 `NowNextLabel.Kind.NONE` 分支**（`infoNowNext.text = ""; infoNowNext.visibility = View.GONE`）。`Kind.NONE` 的含义是「此刻状态里没有 now、也没有 next 标题」，是 `docs/02 §6.3` 的口径（无 EPG / 无当档节目 ⇒ 收起该行，不印占位）。所以 10:07 那一刻，播放状态里的 now/next 变成了空，与淡出无关。
3. 触发点在同一会话内：`PlayerViewModel.loadNowNext` 每次开台/重试都会重查 EPG（`epg.nowNext(channelId, now)`，失败或空都走 `getOrNull()` ⇒ `null`）。10:07 正在 `正在重试…`（故障转移/重试路径），重查结果为空，于是 `onNowNext(null)` ⇒ `NONE` ⇒ 该行收起。

**未定项（留真机）**：本次证据无法判断 10:07 的空结果，是「该时刻确实没有当档节目」（正常降级）还是「重试把已在屏上的节目行清掉」（数据侧缺陷）。两者都**不是淡出缺陷**，本卡只做定性；若复测确认「同一档节目仍在播却清行」，应另开一条数据侧缺陷（`LoadEpgUseCase`/`EpgMatcher` 的判定）而不是回改信息条。

---

## 5. 单测与 check 结果

**新增单测**：

| 套件 | 条数 | 覆盖 |
|---|---|---|
| `InfoBarLayoutTest`（新） | 5 | 960/640 文本列 ≥ 下限（808/488）；频道名与 now/next 字宽 ≤ 488 且 > 旧 104 dp；换行规则（392 / 72、`inlinePlacementFits` 640=false）；四控件均分（216/136）且最长标签 `过扫描：100%` 112 ≤ 124；`dimens.xml` 与 `InfoBarLayout` 常量逐值一致（XML 读回） |
| `ChannelFocusTargetTest` | +1（共 6） | 冷启动（历史焦点空）⇒ 首个**频道行**（`ChannelItem`），且首行确为 `GroupHeader`（不可聚焦） |

**命令与结果**（`JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home`）：

- `./gradlew --offline :feature:player:testDebugUnitTest :feature:channels:testDebugUnitTest` ⇒ **BUILD SUCCESSFUL**；`InfoBarLayoutTest` tests=5/failures=0/errors=0、`ChannelFocusTargetTest` tests=6/failures=0/errors=0（读自 `build/test-results`）。
- `./gradlew --offline check` ⇒ **BUILD SUCCESSFUL**（1177 tasks；含 lint，新增 `dimens.xml` 无未用资源告警）。
- 全仓 **debug 单测 916 / 0 失败 / 2 跳过**（上一轮 910，本轮 **+6**：`InfoBarLayoutTest` 5 + `ChannelFocusTargetTest` 1）。
- `./gradlew --offline verifyModuleDependencies` ⇒ **OK（19 模块）**（无纯 Kotlin 模块的 Android 依赖、无禁用直连、无 api 暴露、无环）。
- 脱敏守护 `tools/ci/sensitive-info-guard.sh` ⇒ **OK — 0 unallowlisted hit(s)**（617 tracked 文件；新增文件无 IP/凭据）。
- `tools/ci/tv-focus-audit.sh` ⇒ **OK — 9 屏都能落焦**（`activity_player` 焦点控件 6，`activity_browse` 8）。

改动面：8 个路径（新增 4：`InfoBarLayout.kt`、`dimens.xml`、`InfoBarLayoutTest.kt`、本文件；改动 4：`activity_player.xml`、`BrowseActivity.kt`、`ChannelFocusTargetTest.kt`、`00-索引与模板.md`）。无 API、无依赖、无 schema 变更。

---

## 6. 未做项 / 待验

- **真机全留（未用电视）**：① uiautomator 复测信息条：`info_name`/`info_now_next` 的 `bounds` 宽度应 ≥ ~700 px（1920×1080 下 488 dp × 2），截图应能看到完整 `CCTV-10科教`；② 1280×720 布局复看（四控件均分后是否观感可接受、`过扫描：100%` 是否偶尔省略号）；③ 冷启动落焦实测（进浏览页后头行「焦点：<号> <名>」应指向第 1 台，而不是页头按钮）；④ 遥控左/右在四控件间行走是否与改前一致。
- **控件字号从 22sp 降到 18sp**（为在 640 dp 单行放下四控件）；10 英尺可读性由真机确认，若偏小可回 20sp 并把标签缩短（`画幅/音轨/字幕/过扫描` 的 `：` 前缀可省）。
- **§4 的数据侧定性**：需真机日志（`EPG_MATCH_HIT`/NOW-NEXT 查询结果）确认 10:07 是正常降级还是清行，本卡未定性、未改代码。

---

## 7. 报 god / arch 的裁决点

1. `InfoBarLayout` 的 `TEXT_COLUMN_MIN_WIDTH_DP=360` 是**本轮自定**的下限（取「最长常见频道名 + 一行标题」的保守字宽）。若要冻结为设计常量，建议回写进 `docs/02 §7.4`（信息条几何）。
2. 信息条改两行后**变高**（上下 padding 40 dp + 首行 64 dp + 行间距 16 dp + 控件行 48 dp ≈ **168 dp**），1080 屏上占底部约 1/6 高度。若 `docs/02 §7.4` 对信息条高度有隐含预期，需 arch 确认这是可接受的变化。
3. FOCUS-1 会**动 QA 脚本的按键计数**（§3 末）。42 号 §5/§7 的脚本若要复用，请 QA 在复测时按新落点重数。
