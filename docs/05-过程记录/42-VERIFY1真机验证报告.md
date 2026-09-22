# 42 · VERIFY-1 真机验证报告（EPG 网格 / now-next / 绑定 / 诊断包 / 频道管理 / 媒体键 / 搜索）

> 关卡：**P2-G / P3-G 前置的真机验证轮** ｜ 角色：QA（temp `worker-qa-verify`）
> 基线：主线 `b533c3e`（`git merge --ff-only main` = Already up to date）
> 设备：Sony BRAVIA 4K VH21 / Android 12 (API 31) / 1920×1080 override（`<TV-LAN-IP>:5555`，独占）
> 结论速览：**7 项中 2 项通过、1 项有条件、4 项不通过**；新增 3 条缺陷（2×S2 / 1×S2 口径类）。

## 0. 环境与出包

| 项 | 值 |
|---|---|
| 出包 | `./gradlew --offline :app:assembleRelease`（`JAVA_HOME=…/Android Studio.app/Contents/jbr/Contents/Home`，`local.properties` 从主仓拷入） |
| APK | `app/build/outputs/apk/release/app-release.apk`，**8,641,728 B**，sha256 `fc756d36da6ff555f6c7e2c2e4e5a5e4405a6ca736ba7be6495718c7d3b38b99` |
| 版本 | `0.1.0 (1)`（诊断面板「应用版本」实测） |
| 装机 | `adb install -r` → Success；`pm clear` → 冷启动 |
| 频道表 | **走正式入口**：`adb push tools/fixtures/large/validated.m3u`（658 行 / 147.7 KB）到 `files/playlists/` → 频道页「导入播放列表 → 从投放目录选择」 |
| 导入结果 | 头行 `571 频道 / 4 分组 / 658 流`；日志 `DB_UPSERT{channels=571, streams=658, removed=585, streamsRemoved=82}`（与 P1-G v2 逐项一致） |
| 设备设置 | `svc power stayon true` + `screen_off_timeout 1800000`；收尾已还原（见 §9） |
| 证据 | `$AGENT_DIR/evidence/VERIFY1/`（本报告所有截图/输出/诊断包；绝对路径见 §10） |

## 1. EPG 时间网格 fps（P3-1）—— ✅ 通过（口径有缺口）

**命令与采样**：频道页「节目单」→ `EpgGridActivity` 打开后
`adb shell dumpsys gfxinfo ilab.iptv.player reset` → 用 DPAD 连续滚动 **32 s**（上/下/左/右交替，见 `evidence/VERIFY1/scroll_grid.sh`）→ `adb shell dumpsys gfxinfo ilab.iptv.player`。

| 指标 | 实测 | 判据 |
|---|---|---|
| 采样窗口 / 帧数 | 32 s / **1738 帧** | — |
| 50 / 90 / 95 / 99 百分位（CPU 绘制） | **7 / 9 / 10 / 11 ms** | p95 ≤ 17 ms → ✅ |
| Janky 帧 | **1 帧（0.06%）** | 无掉帧 |
| Missed Vsync / Slow UI thread / Slow bitmap / Slow draw | 0 / 1 / 0 / 0 | — |
| GPU 50/90/95/99 百分位 | 2 / 3 / 3 / 3 ms | — |

**证据**：`evidence/VERIFY1/item1-gfxinfo.txt`、`item1-after-scroll.png`。

**缺口（需 god 决定）**：`docs/02 §8.5` 的口径是 **release + `Choreographer` 采样**，落到 `PERF_EPG_GRID`。本次**拿不到该事件**：release 版按 1/10 抽样，且 `PERF_EPG_GRID` 是 DEBUG 级（`LogBus.minLevel=INFO` 时不出）。另跑 12 次「开网格→滚动 6 s→退出」会话（`evidence/VERIFY1/grid_sessions.sh`）**一次都没命中**。建议：给验收轮一个「强制采样」开关，或把 `PERF_EPG_GRID` 提到 INFO。

## 2. EPG now/next 真机出现（EPG-WIRE-1 关键未做项）—— ❌ 不通过

**(a) 全新安装的冷启动：now/next 永远不会出现。** 装包 + `pm clear` 后首次启动，`FIRST_RUN` 的 EPG 任务在**频道表还是空**的时候就把频道列表读走了，日志证据：

```
seq=3  WORK_SCHEDULE epg refresh requested {trigger=FIRST_RUN, decision=RUN, reason=stale}
seq=18 EPG_COVERAGE {matched=0, withProgrammes=0, total=0, providers=4, elapsedMs=26316}
```

下一次冷启动起，**6 小时新鲜度闸门**把自动触发全部挡掉：

```
seq=3 WORK_SCHEDULE epg refresh not scheduled {trigger=FIRST_RUN, decision=SKIP, reason=fresh,
      lastFetchAtMs=…, ageMs=113428, minIntervalMs=21600000}
```

⇒ 用户新装后 6 小时内**看不到任何节目单 / now-next**，只能靠诊断面板手动刷新（`MANUAL` 绕过新鲜度）。

**(b) 手动刷新后匹配是好的**：诊断面板「立即更新 EPG」→ 4 源全 200、耗时 ~26.7 s，
`EPG_COVERAGE{matched=140/571, withProgrammes=107, emptyBinding=33, mainstreamMatched=91/107=85.0%, mainstreamProgrammedRatio=71.0%}`。

**(c) 信息条上的 now/next 不可读，而且会消失。**

- 10:02 截图：第三行渲染了 `player_now_next_now`（`正在播出：…`）；
- 10:07 同一会话同一频道：`uiautomator dump` 里 **`info_now_next` 节点消失**（`View.GONE`，即 `NowNextLabel.NONE`），第三行变成橙色 `正在重试…`；
- 无论哪一帧，节目名都读不出来——左侧文本列只有 **209 px** 宽（见 §4 缺陷 BUG-20260922-017）。

**证据**：`evidence/VERIFY1/25-infobar-kepu.png`、`27-infobar.png` / `27-infobar-crop.png`、`00-launch.png…04-imported.png`。

## 3. 空绑修复效果（EPG-BIND-1）—— ❌ 不通过

抽查「上一轮报的『匹配到但窗内 0 节目』」的央视频道，网格里**依然没有节目**：

| 频道（网格行） | 网格 6 h 窗（09:00–15:00） | 扩到 05:59–18:29 仍为空？ | 日志里该频道 chosen id 的 `programmesInWindow` |
|---|---|---|---|
| `1 CCTV1` | 空 | 空 | `CCTV-1.hk`（epgshare01.hk）：**84** |
| `11 CCTV10` | 空 | 空 | `561319`：0（真空绑） |
| `31 CCTV-12社会与法` | 空（详情弹层：**「该时段无节目」**） | 空 | `545944`：**71** |
| `41 CGTN俄语` | 空 | 空 | `540038`：10 |
| `CCTV-10科教` | **有节目**（探索·发现 09:05 / 健康之路 09:40 / 百家讲坛 10:25 / 自然传奇 14:30 …） | 有 | `545942`：77 |

即：**覆盖率的分子（`programmesInWindow`）与网格能画的节目对不上**。代码侧对得上的解释是口径不同：
`LoadEpgUseCase` 用 `ProgrammeWindows.around(now)` = **[now−6 h, now+48 h]（54 小时）** 去数，而网格只画 **6 小时**窗口；`docs/02 §6.3` 的「覆盖 = 窗内有节目」被当成网格能看。
⇒ 「主流可看率 71%」这个数字在本设备上**不成立**（前 8 行只有 1 行有节目）。

**证据**：`evidence/VERIFY1/15-grid-with-epg.png`、`20-grid-after-refresh.png`、`21-grid-detail.png`（详情弹层「该时段无节目」）、`22-grid-later.png`。

## 4. 诊断面板与导出包（P2-8）—— ✅ 通过（含两个小注）

- **面板**：设置 → 诊断面板；「运行概览」各字段与实测一致：`频道 571 / 分组 4 / 流 658 / 隐藏 0 / 收藏 0`、`版本 0.1.0 (1)`、`日志级别 INFO→DEBUG`、`文件日志 开`、`内存环 2000/2000`、`主流覆盖 76/107 = 71.0%（空绑 15）`、`全量 107/571 = 18.7%（空绑 33）`；右侧实时日志（内存环）可读。
- **导出**：`导出诊断包` → `/sdcard/Android/data/ilab.iptv.player/files/export/iptv-diag-20260922-100446.zip`，**63,753 B**；`adb pull` 成功。
- **内容**：`meta.json`(476 B) / `stats.json`(313 B) / `overview.txt`(922 B) / `logs/iptv-20260922.log`(868,801 B) / `README.txt`(609 B) —— 概览 + 日志 + 设备信息齐备。
- **脱敏抽检（3 类）**：日志里 **0 处**内网 IP 字面量、**0 处** URL 查询参数原文（凭据类字段看不到原值）；URL 一律呈现为「主机 + 8 位 hash 路径」，例如 `http://p12.demo.invalid/e775756b`、`https://epg.pw/090ab3d7`。
- 注：README 写「logs/ 最近 7 天」，本次包内只有 1 个日志文件；`timeline.json` / `db-dump/` 未含（P3-6 范围）。

**证据**：`evidence/VERIFY1/10-diag-overview.png`、`28-diag-export.png`、`iptv-diag-20260922-100446.zip`。

## 5. 频道管理交互（P2-2 / P3-4）—— ✅ 通过

| 动作 | 实测 | 证据 |
|---|---|---|
| 单频道 MENU | 行上按 MENU → 动作弹窗（加入收藏 / 隐藏此频道 / 上移 / 下移 / 编辑频道号 / 清除频道号 / 重命名 / 解除 EPG 绑定） | `31-menu-short.png` |
| 收藏 | 行尾出现金色 ★ | `32-favorite.png` |
| 重命名 | 弹 T9 输入框 → 保存后行名变、加「名」标记，meta 仍显示源名（`name_key` 未被改） | `33/34-renamed.png` |
| 隐藏 | 头行 `571 频道 / 658 流` → **`570 / 657`**，行从列表消失；「显示隐藏：开」后回来并带「隐」标记 | `36-hidden2.png`、`37-show-hidden.png` |
| 批量（管理 → OK 标记 → MENU） | 弹窗：隐藏选中 / 取消隐藏选中 / 移动到分组… / 删除选中 / 全选 / 清空选择 / 撤销：<上一步> | `38-batch-menu.png` |
| 删除 + 撤销 | 删 2 台 → `569 / 655`；撤销（撤销：删除 2 台）→ 回到 `571 / 658`，两条频道回到列表 | `39-deleted.png`、`41-undo-done.png` |
| 移动分组 | 选中 → 移动到分组（4 台）→ 卫视；行 meta 变「卫视」并排进卫视段 | `42/44/45-moved.png` |
| 手动绑 EPG | 解除绑定后动作项翻成「**手动绑定 EPG**」→ 选择器「当前 guide 共 **1552** 个频道」；无匹配时提示「**该 guide 无此频道**：…请先在设置里更新节目单，或改选其它频道」 | `56-cgtn-bind.png`、`57-unbound.png`、`58-epg-picker.png`、`59-picker-empty.png` |
| 重启持久化 | `am force-stop` + 冷启动后：`570 / 657`（隐藏仍生效）、「显示隐藏：开」后 `11 CCTVV` 带「隐 名」双标记；CCTV-13 仍在卫视段 | `60-restart-persist.png`、`61-persist-hidden.png` |

**方法说明**：`长按 MENU` 进入的是**管理模式**（多选），单频道动作弹窗用**短按 MENU**；adb `input text` 经电视 T9 输入法会**吞掉数字**（本轮把 `CCTV10V` 输成了 `CCTVV`），属注入方法问题，不是应用缺陷。

## 6. 媒体键真遥控器复测（P1-G 残留风险③）—— ⚠️ 有条件 / 需人工

**agent 无法按物理遥控器**，本轮只能用 adb 注入（`input keyevent 85` = `KEYCODE_MEDIA_PLAY_PAUSE`），并以 `dumpsys media_session` 判读：

| 场景 | 实测 | 判定 |
|---|---|---|
| 前台播放中按 85 | `state=3(PLAYING)` → **`state=2(PAUSED)`** → 再按 **`state=3`**（可靠双向） | ✅ |
| HOME 回桌面后按 85（两次） | `state` **恒为 3**，`position` 继续推进 | ❌ 注入键**未送达** |

⇒ **上一轮「后台可控」的方法学疑点成立**：v1 记录的「HOME 后 85 使 6→3」应判为**缓冲结束**，不是按键生效。真遥控器 / CEC 路径本轮仍未验（需人工，见 §8）。

另注（同一方法学）：诊断面板上的按钮用 `DPAD_CENTER`（`keyevent 23`）**多次未触发**（「立即更新 EPG」「导出诊断包」均无反应），改 `input tap` 后立即生效；同一遥控键在设置页/浏览页正常。是否为真遥控器下的可用性问题，需人工确认。

## 7. 搜索（P3-2）—— ✅ 通过

| 路径 | 命令 | 结果 |
|---|---|---|
| 数字键直输 | `keyevent 8` + `keyevent 7`（=「10」） | 头行「搜索：10 → **找到 27 条**」，首帧截图（~0.7 s）里结果已完整呈现：`10 CCTV-4 中文国际` / `100 凤凰香港` / `101 福建漳州…` |
| 字母网格 | 点 C/C/T/V → 「cctv」 | **49 条**（`1 CCTV1` / `2 CCTV-10科教` / `3 CCTV-11戏曲`…）；再试「asm」「cetm」→ 无结果（拼音种子表覆盖有限，属已知） |
| 进结果 + 起播 | `↓`×5 进结果列表 → OK | 进入 `PlayerActivity`，`PLAY_PREPARE_START{channelId=22}`（CCTV-4 中文国际） |

**证据**：`46-search.png`、`47-search-t07.png`、`49-search-results-focus.png`、`50-search-play.png`、`51-search-letters.png`、`58-epg-picker.png`。

## 8. 判定汇总与未验项

| # | 项 | 判定 |
|---|---|---|
| 1 | EPG 网格 fps（p95 ≤ 17 ms） | ✅ 通过（gfxinfo 口径；`PERF_EPG_GRID` 未取到，见 §1） |
| 2 | 信息条 now/next 真有节目名+时间 | ❌ 不通过（新装 6 h 内不出现；出现时被截断成「…」，且会消失） |
| 3 | 空绑修复效果 | ❌ 不通过（抽查 4 台「有节目」的频道网格全空） |
| 4 | 诊断面板 + 导出包 + 脱敏 | ✅ 通过（两条小注） |
| 5 | 频道管理交互 + 手动绑 EPG + 重启持久化 | ✅ 通过 |
| 6 | 真遥控器媒体键（前/后台） | ⚠️ 有条件（前台 ✅ / 后台 ❌）；**真遥控器需人工** |
| 7 | 搜索（数字键 + 字母网格，≤1 s，可直接播） | ✅ 通过 |

**未验 / 需人工**：① 物理遥控器与 CEC 的媒体键路径（本项 6）；② 诊断面板按钮在真遥控器下是否可激活；③ 24 h 导引滚动与惯性手感（dev-b-9 的遗留项）；④ 真实 TS 字幕轨 / 音轨切换手感（dev-b-10 的遗留项）。

## 9. 缺陷登记（按台账格式；未并入 `04-缺陷台账.md`）

**BUG-20260922-016｜S2｜EPG 冷启动触发早于频道表播种，之后被 6 h 新鲜度闸门锁死**
- 复现：`pm clear` → 冷启动 → 打开频道页（播种）→ 不再手动刷新。
- 现象：`FIRST_RUN` 的 EPG 在 `channel` 表为空时读表（`LOG: EPG_COVERAGE{matched=0,total=0}`，白跑 26.3 s），随后每次冷启动 `WORK_SCHEDULE … decision=SKIP, reason=fresh`；6 h 内电视上没有任何节目单 / now-next。
- 定位方向：`IptvApplication.scheduleEpg()` 的 `FIRST_RUN` 与 `RoomChannelSeeder.ensureSeeded()` 无先后约束；`EpgRefreshPolicy` 的 `gate()` 只看新鲜度，不看「上次跑的结果是否有效（total=0 应视为失败）」。
- 影响：新装用户体验 = 无节目单（即便 4 个源都拉成功）。

**BUG-20260922-017｜S2｜信息条左侧文本列被 P3-3 的 4 个控件挤到 209 px，频道名与 now/next 全被截断**
- 证据：`uiautomator dump` 中 `info_name` 的 `text="CCTV-10科教"` 而 `bounds=[240,856][449,937]`（宽 209 px）；`info_quality` 同理；截图里只显示 `CCTV-…`、`1080p avc …`、`正在播出：…`。
- 定位方向：`activity_player.xml` 的 `info_bar` 是横向 LinearLayout，左侧文本列 `weight=1`，四个控件（画幅/音轨/字幕/过扫描）宽而未压缩 → 文本列只剩 ~1/4 宽度。P1-4 只有「画幅」一个控件时不存在该问题，属 P3-3 引入的观感回归。

**BUG-20260922-018｜S2｜EPG「有节目」口径与网格不一致（覆盖率虚高）**
- 现象：`EPG_MATCH_HIT{channelId=31, epgId=545944, programmesInWindow=71, chosen=true}`，而同一频道在网格 6 h 窗口（甚至 12.5 h）内**一个节目块都没有**，详情弹层直接说「该时段无节目」。
- 定位方向：绑定择优与覆盖率都用 `ProgrammeWindows.around(now)` = **[now−6 h, now+48 h]** 计数（`LoadEpgUseCase` 第 285 行），网格只画 6 h；代码注释却写着「pick 的依据就是网格会显示的东西」。修法二选一：把计数窗口改成网格窗口（并同步 `docs/02 §6.3`），或先把「空存根 ↔ 真数据同名 id」的匹配层缺口补上（EPG-BIND-1 报告 §6① 已提）。

**观察（不单独立缺陷）**：`PERF_EPG_GRID` 在 release 抽样 1/10 + DEBUG 级 ⇒ 验收轮取不到口径内的数字；诊断面板按钮对 `DPAD_CENTER` 无响应（见 §6）。

## 10. 证据与收尾

- 证据目录：`/Users/jeffrey/temp/iptv-player-workspace/hive/agents/worker-qa-verify/evidence/VERIFY1/`（75 个文件：截图 `00…61-*.png`、`item1-gfxinfo.txt`、`iptv-diag-20260922-100446.zip`（诊断包原件）、`logcat-epg-from-diag.txt`（包内 2315 行 EPG/WORK 日志，含 §2 的 `matched=0` 与 `SKIP(fresh)` 证据）、`scroll_grid.sh`、`grid_sessions.sh`）。
- 代码边界：**未改功能代码、未 commit、未 push**；只新增本文件与 `00-索引与模板.md` 一行索引。
- 设备收尾：重装**同一 as-shipped 包**（sha256 `fc756d36…`）+ `pm clear` + 回桌面 + `svc power stayon false` + `screen_off_timeout 600000`。电视上不留夹具/QA 数据。
