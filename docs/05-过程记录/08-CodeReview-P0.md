# 05 · CodeReview 记录 · P0 工程骨架（关卡 G4）

> 评审对象：提交 `3d3d33d`（`feat(p0): 工程骨架——Gradle 多模块、约定插件、Hilt/Room/Media3、签名与 CI`），变更 48 个文件 / +1550 行，关联卡 **P0-1 / P0-2 / P0-3 / P0-4**（`P0-7` 的 CI 部分同评）
> 作者：dev-A（Jim）；第 2 轮构建修复：dev-A-2（Jim） ｜ 评审人：arch（Oscar / `worker-arch-3`） ｜ 日期：2026-09-21 ｜ 方式：源码评审 + 实机构建 + 反证实验

---

## 1. 评审范围与方法

| 项 | 内容 |
|---|---|
| 评审对象 | `settings.gradle.kts`、`build.gradle.kts`、`gradle.properties`、`gradle/libs.versions.toml`、`build-logic/`（5 个约定插件 + 自身构建）、`app/`、`core/*`×13、`feature/*`×5、`.github/workflows/ci.yml`、`tools/bootstrap-gradle.sh`、`gradle/wrapper/`、`.gitignore`、签名配置 |
| 评审基准 | docs/02 §3.1 模块清单、§3.2 依赖规则与守护、§4 接口契约（v1）、§10 并发、§12 安全、§14 构建约定；docs/03 §3 事件码、§4 事件模型、§5 Sink 体系；docs/04 §2.1 |
| 证据（全部实跑，见下表） | Gradle 8.13 + JBR 21；`./gradlew` 只读命令 + `/tmp` 副本里的反证实验（主树不改） |
| 未做 | 不修代码（含 lint warning）、不改 `docs/01`–`04`、不 commit / 不 push、不 adb 装机；真机 P0-5 归 QA；S1–S6 Spike 不在本轮 |

### 1.1 实跑证据

| # | 命令 | 结果 |
|---|---|---|
| 1 | `./gradlew --offline :app:assembleDebug` | **成功**（7 s，暖缓存：106 执行 / 168 复用缓存 / 9 up-to-date）|
| 2 | `./gradlew --offline check lint testDebugUnitTest` | **成功**（13 s）；`verifyPureKotlin: OK` × 3（common / model / domain）|
| 3 | `./gradlew :app:lint --rerun-tasks`（联网） | **0 error / 27 warning**（离线跑是 0 error / 3 warning；差的 24 条是「依赖有新版」类提示，需联网才判）|
| 4 | 反证·规则 1：`core/domain` 加 `import android.os.Bundle` | `:core:domain:verifyPureKotlin` **FAILED**，文案正确（`Purity guard failed: :core:domain …`）→ **规则 1 守护真实有效** |
| 5 | 反证·规则 1 覆盖范围：`core/domain/build.gradle.kts` 加 `implementation(project(":core:log"))` | `verifyPureKotlin: OK (:core:domain)` → **守护不看依赖声明**（见 CR-02）|
| 6 | 反证·规则 3/4：`:feature:channels` 与 `:app` 各加 `implementation(project(":core:player"))` | `:feature:channels:assembleDebug :app:assembleDebug testDebugUnitTest lint` **BUILD SUCCESSFUL（54 s，零提示）** → **规则 3/4 没有构建期守护**（见 CR-01）|
| 7 | 依赖矩阵抽查：`:app` releaseRuntimeClasspath 顶层 project 依赖 | `core:common/log/model/domain/data/ui/design` + 5 个 `feature:*`，**无** database/network/source/epg/player → 规则 3 现状达标 |
| 8 | CI 覆盖验证：`./gradlew --no-daemon -Dorg.gradle.java.home=/tmp/… help` | 报 `Value '/tmp/…' given for org.gradle.java.home Gradle property is invalid` ⇒ **命令行 `-D` 确实覆盖 `gradle.properties` 的 macOS JBR 路径**，CI 的 Linux 改法有效 |
| 9 | 脱敏自查 | 全仓（已跟踪文件）`git grep` 不到签名口令；`local.properties` 未入库且被 `.gitignore:5` 忽略 ✓ |

> 反证实验在 `/tmp` 的仓库副本中做（源文件与 `3d3d33d` 工作区逐字节一致），评审对象本身**未被修改**。

---

## 2. 必查清单结果

| 检查项 | 结果 | 备注 |
|---|---|---|
| C1 模块边界与依赖 | ❌ | 现状**合规**（19 个模块的依赖与 §3.2 矩阵一致，规则 1 有真实守护）；但规则 2/3/4/5 **无构建期守护**，文档却写「CI 强制」→ CR-01/02/03 |
| C2 错误与并发模型 | ❌ | `AppResult`/`AppError`/`FailureClass`/`DispatcherProvider`（含 `engine`/`single`）与 v1 一致 ✓；但 `AppError` 工厂缺 4 个分类、`Logger` 便捷方法伪造空事件 → CR-04/05 |
| C3 日志与事件码 | ➖ 不适用 | `EventCodes` 与 P0-7 的两条 CI 规则（事件码注册、禁裸 Log）属 P0-7，本卡未开工，不判缺陷；提醒见 CR-09 |
| C4 脱敏 | ✅ | 口令只在 `local.properties`（未入库、已 ignore）；仓库无内网 IP/MAC/序列号；keystore 文件入库是本项目 ADR-003 的有意决定 |
| C5 单测与覆盖率 | ➖ 骨架期 | 只有 1 条 `PlaceholderTest`（`app/src/test/.../PlaceholderTest.kt:9`）；P0 无覆盖率要求，P1-1 起按 §13 ≥80% → CR-10 |
| C6 用例挂钩 | ➖ 不在本轮 | P0-5 真机装机由 QA 执行（`worker-qa-2`，卡 `P0-5` 进行中）|
| C7 性能口径 | ➖ 不适用 | 本轮改动不涉及启动/滚动/换台路径 |

---

## 3. 问题清单

### CR-01 依赖守护只覆盖规则 1，规则 2/3/4/5 完全没有构建期守护

- **级别：重要**
- **位置**：`.github/workflows/ci.yml:29-41`；`build-logic/src/main/kotlin/iptv.kotlin.library.gradle.kts:39-70`；各模块 `build.gradle.kts`
- **依据**：docs/02 §3.2 守护规则 1–5（该节标题写「**CI 强制**」；规则 5 明确要求「用 Gradle 约定插件 + `dependency-analysis` 任务在 CI 校验」）
- **实测**：给 `:feature:channels`、`:app` 各加一条 `implementation(project(":core:player"))`（同时违反规则 3 与规则 4）后，`assembleDebug + testDebugUnitTest + lint` **全绿**，没有任何任务报错或提示。
- **影响**：架构文档承诺的「唯一例外是 `:feature:player`」目前只是口头约定。任何 feature 都能直接摸 `:core:database`/`:core:player`，且 CI 不会红——这正是 P0 骨架最该锁住的东西。
- **建议修法**：在 `build-logic` 增约定任务 `verifyModuleDependencies`：以一张「允许矩阵」为唯一数据源，扫各模块的 `project(":…")` 声明并比对，挂到 `check`；同时把 CI 的裸 `grep` 步骤换成 `./gradlew --no-daemon … check lint`（规则 1 的 Gradle 版已在跑）。备选：引入 `com.autonomousapps.dependency-analysis`。责任方 dev-A，收口卡 P0-7。

### CR-02 规则 1 守护只扫 `src/` 的 import，不看依赖声明

- **级别：建议**
- **位置**：`build-logic/src/main/kotlin/iptv.kotlin.library.gradle.kts:43-45`（`fileTree(layout.projectDirectory.dir("src"))`）；`.github/workflows/ci.yml:33-39`（`find core/… -name '*.kt' -o -name '*.java'`）
- **依据**：docs/02 §3.2 规则 1 的措辞是「纯 Kotlin 模块」
- **实测**：`core/domain/build.gradle.kts` 里加 `implementation(project(":core:log"))`（Android 模块）后，`verifyPureKotlin: OK (:core:domain)`。
- **影响**：把 Android 依赖塞进「纯 Kotlin」模块的构建脚本，守护给不出信号；症状会推迟到解析期以别的报错形式出现（实测该依赖在 `compileClasspath` 里表现为 `project :core:log FAILED`）。
- **建议修法**：把模块根 `build.gradle.kts` 纳入扫描（CI 侧 `find` 加 `-name build.gradle.kts`），或直接由 CR-01 的矩阵守护统一覆盖。与 CR-01 同一处修改，建议一并做。

### CR-03 docs/02 §3.2 的矩阵与守护规则 3/4 自相矛盾，且矩阵缺 `:core:testing`

- **级别：重要（文档）**
- **位置**：`docs/02-技术架构设计.md:113`（矩阵 `app` 行，`database` 与 `player` 列均标 ✅）vs `docs/02-技术架构设计.md:119-120`（规则 3：`:app` 不得依赖 `database`/`player`；规则 4：只有 `:feature:player` 可依赖 `:core:player`）
- **附带**：矩阵没有 `:core:testing` 列/行，但 `:app` 已有 `debugImplementation(project(":core:testing"))`（`app/build.gradle.kts:98`），`:core:testing` 也已建。
- **依据**：docs/02 §3.2 表 + 同节规则 3/4；docs/02 §3.1 模块清单含 `:core:testing`
- **影响**：下一个写模块的人**照矩阵写会违反规则**（反之亦然）。CR-01 的守护若以矩阵为数据源，会先把矛盾固化进去。
- **建议修法**：arch 提案 → god 批准后回写 docs/02 §3.2：删掉 `app` 行的 `database`/`player` 勾，补 `testing` 列并明确 `:app` 与 `:core:testing` 的关系（建议 `debugImplementation` 允许）。**本记录只登记，不改文档**；P0 可并入主线，但 CR-01 的守护落地前必须先定准这张矩阵。

### CR-04 `AppError` 工厂缺 4 个 `FailureClass`，与 §4.6 映射表冲突

- **级别：重要（契约缺口，需 v1.1）**
- **位置**：`core/common/src/main/kotlin/ilab/iptv/player/core/common/AppResult.kt:56-92`（工厂只有 `http/timeout/network/parse/decode/storage/permission/capability/unknown`）
- **依据**：docs/02 §4.6 映射表要求平台层产出 `TLS`（握手失败）、`EMPTY_MEDIA`（清单为空）、`PLAYLIST_GONE`（候选全失效）、`CANCELLED`（协程取消）；docs/02 §4.1 与代码注释同时写「禁止在业务代码里手工挑分类」。
- **影响**：`:core:network`（P1-1）、`:core:source`、`:core:player` 落地时必须二选一：手写 `AppError(code, FailureClass.TLS, false, …)`（违反约束），或错标成 `UNKNOWN`（让分类体系失效）。两条路都会污染故障转移策略的判据。
- **建议修法**：arch 提案 v1.1，补 `tls(code, cause)` / `emptyMedia(code)` / `playlistGone(code)` / `cancelled(code)` 四个工厂（或在文档里明确「这四个分类允许在平台层手写」并注明理由）；god 批准后由 dev-A 落地，最迟在 P1-1/P1-3 前。

### CR-05 `Logger` 的 `v/d/i/w/e` 默认实现会写出「时间、序号、线程、会话全空」的事件

- **级别：重要**
- **位置**：`core/common/src/main/kotlin/ilab/iptv/player/core/common/Logger.kt:32-55`
- **依据**：docs/02 §4.1（冻结门面）、docs/03 §4（`seq` 排序/去重、`ts` 墙钟、`elapsedMs` 定位卡顿、`thread`、`sessionId` 会话折叠）、docs/03 §5（Sink 与写入管线）
- **问题**：接口给便捷方法写了默认体，构造的是 `LogEvent(seq=0, ts=0, elapsedMs=0, …, thread="", screen=null, sessionId="")`。任何调用 `logger.i(...)` 的代码，日志落地就是无效事件——诊断页按 `sessionId` 折叠、按 `seq` 排序都会失效。
- **影响**：P0 无实现，所以现在不报错；P0-6 日志骨架一上线，坏数据会立刻进 `MemoryRing`/`FileSink`，且很难在代码评审里再发现。
- **建议修法**：把这 5 个方法改为**抽象**（签名不变，不破坏 v1 冻结），由 `:core:log` 的实现负责填信封；或保留默认体但改为委托一个填充器（内部用 `Clock`/`SessionIdFactory`/线程名）。责任方 dev-A，收口点 P0-6。

### CR-06 清单缺 `android:usesCleartextTraffic="true"`，明文 HTTP 源在目标机上会被系统拦掉

- **级别：重要（P1 起播前必修，不挡 P0 合并）**
- **位置**：`app/src/main/AndroidManifest.xml:15-22`
- **依据**：docs/02 §12「`usesCleartextTraffic="true"`（IPTV 源大量明文 HTTP）」；`targetSdk = 35`（`app/build.gradle.kts:30`）≥ 28 → 明文默认禁用
- **影响**：电视是 Android 12 / API 31，P1 起播任何 `http://` 直播源都会直接失败（Play 源绝大多数是明文）。
- **建议修法**：加 `android:usesCleartextTraffic="true"`；要更严可以改 `android:networkSecurityConfig` 只放行明文域。责任方 dev-A，收口点 P1-3（S1 复测前，否则 S1 成功率会被误判）。

### CR-07 签名口令文件位置：文档说「根目录或 `keystore/`」，代码只读根目录 → 静默产出未签名包

- **级别：建议**
- **位置**：`app/build.gradle.kts:15-18`（只读 `rootProject.file("local.properties")`）、`keystore/README.md`（「请在仓库根目录**或本目录**创建 `local.properties`」）、`.gitignore:18`（忽略 `keystore/local.properties`）
- **影响**：照 README 把口令放进 `keystore/` 的人会得到一个**未签名**的 `app-release.apk`，而构建照样「成功」（`:app:build.gradle.kts:55-57` 只在 `storeFile != null` 时挂签名）。CI 若无 Secrets 同理。
- **建议修法**：两处都读（根目录优先）；或 `assembleRelease` 在缺 `RELEASE_STORE_*` 时打 warning，并提供 `-PrequireSigning=true` 让它直接失败。责任方 dev-A。

### CR-08 约定插件重复样板；命名空间由路径隐式推导

- **级别：建议**
- **位置**：`build-logic/build.gradle.kts:10` 与 5 个约定插件里的 `extensions.getByType<VersionCatalogsExtension>().named("libs")`（重复 6 次）；`build-logic/src/main/kotlin/iptv.android.library.gradle.kts:17` 与 `app/build.gradle.kts:24` 重复硬编码 `"ilab.iptv.player"`
- **依据**：docs/02 §14（版本目录唯一来源）、§4.0 F1（类型唯一归属）
- **说明**：`VersionCatalogsExtension` 的写法是 Gradle 8.13 下必需的正确修法（`libs.findLibrary` 在 included build 里不可用），不是缺陷；问题只是同一段样板抄了 6 遍。
- **建议修法**：抽 `build-logic/src/main/kotlin/IptvConvention.kt`（catalog 获取 + 命名空间前缀常量），各插件复用。另外「命名空间 = `ilab.iptv.player` + 模块路径」意味着**改目录名会静默改命名空间**，建议把这条口径写进设计记录以免后续有人重命名模块。

### CR-09 注释里的卡号过期；`EventCodes` 与实际卡位不一致

- **级别：建议**
- **位置**：`core/common/src/main/kotlin/ilab/iptv/player/core/common/Logger.kt:8`（写「随日志骨架 P0-2 落地」，日志骨架实为 **P0-6**）；`app/src/main/kotlin/ilab/iptv/player/MainActivity.kt:8`（写「for P0-1」，空 Activity 实为 **P0-3**）
- **依据**：docs/04 §2.1 卡号；docs/03 §3.3（事件码集中在 `EventCodes.kt`）
- **建议修法**：改注释卡号；P0-7 收口时把「事件码必须在 `EventCodes.kt` 注册」「禁止裸 `Log.x`」两条规则真做出来（`EventCodes` 目前尚不存在，属 P0-7 范围，本轮不判缺陷）。

### CR-10 11 个 `core:*` 与 5 个 `feature:*` 是空壳，且全仓库没有任何 TODO/占位标记

- **级别：建议**
- **位置**：`:core:log/database/network/source/epg/player/data/ui/design/testing/domain` 与 `:feature:*`（只有 `build.gradle.kts`）；全仓 `rg TODO|FIXME` 无命中
- **依据**：docs/02 §3.1（各模块的「关键内容」列）、docs/04 §2.1（P0 只要求骨架）
- **影响**：骨架期这没问题，但空壳没有任何「未实现」标记，容易被后续读者/脚本当成已完成模块。
- **建议修法**：空模块放一行 package 占位文件或 `README.md`，注明落地卡号；顺带清理 `gradle/libs.versions.toml:46-47` 里已声明但无 `androidTest/` 源码的 `androidx-test-ext-junit`、`espresso-core`（或建好 `androidTest` 目录）。

### CR-11 图标是系统 drawable；`allowBackup=true`

- **级别：建议**
- **位置**：`app/src/main/AndroidManifest.xml:19`（`android:icon="@android:drawable/ic_media_play"`）、`app/src/main/AndroidManifest.xml:17`（`android:allowBackup="true"`）
- **依据**：docs/02 §3.1（`:core:design` 管主题/图标）、§12（安全与合规）
- **建议修法**：正式图标随 P1 设计系统补（`app_banner.xml` 已是登记在案的占位）；`allowBackup` 建议关掉或配 `dataExtractionRules`——库里有源地址与可能的 token，云备份会把它们带出设备。

### CR-12 `gradle.properties` 把 macOS JBR 路径写死入库（有 CI 兜底，但要知道代价）

- **级别：建议**
- **位置**：`gradle.properties:3`
- **实测**：命令行 `-Dorg.gradle.java.home=…` **优先于** `gradle.properties`（见 §1.1 #8），所以 CI 的 `-Dorg.gradle.java.home="$JAVA_HOME"` 写法成立，**不是阻断项**。
- **建议修法**：保留（本机确实无系统 Java）但在 README 里写清「非 macOS 机器必须用 `-D` 覆盖或改本行」；将来开 `org.gradle.configuration-cache` 时注意 `app/build.gradle.kts:15-21` 读 `local.properties` / `System.getenv` 的写法会被当作配置输入。

### CR-13 CI 从未真实运行；SDK 组件依赖 AGP 自动补装

- **级别：重要（P0 出口条件之一）**
- **位置**：`.github/workflows/ci.yml:24-25`（`android-actions/setup-android@v3` 不预装 `platforms;android-36` / `build-tools;36.x`）、`:44-51`（三条 Gradle 命令）
- **事实**：作者已自述 CI 未验证（`docs/05-过程记录/05-P0构建验证.md` §6.1）；评审时仓库里 CI 仍然一次都没跑过。本机实测 AGP 能自动补装 `platforms/android-36` + `build-tools/35.0.0`（本机 licenses 已接受），CI 上这一步取决于 runner 的许可与自动下载是否放行——任一环不成立，`assembleDebug` 直接红。
- **另外两点**：① CI 只跑 `assembleDebug/testDebugUnitTest/lint`，不跑 `check`（规则 1 的 Gradle 守护因此不在 CI 路径上）；② 分支过滤 `on.push.branches: [main, "agent/**"]` 与当前分支命名一致 ✓，JDK 17 + `-D` 覆盖 JBR 路径 ✓（实测）。
- **建议修法**：CI 里显式 `sdkmanager "platforms;android-36" "build-tools;36.0.0"`（或固定 `setup-android` 的 packages），把 lint 步骤改成 `check lint`；god 提交 `3d3d33d` 后**立刻跑一次真实 CI**并把结果补进 `05-P0构建验证.md`。责任方 dev-A，收口卡 P0-7。

---

## 4. 结论

| 严重级 | 数量 | 编号 |
|---|---|---|
| 阻断 | **0** | — |
| 重要 | 6 | CR-01、CR-03、CR-04、CR-05、CR-06、CR-13 |
| 建议 | 7 | CR-02、CR-07、CR-08、CR-09、CR-10、CR-11、CR-12 |

- **结论：有条件通过**（可并入主线）。
- **放行依据**：评审对象能复现绿色构建（`assembleDebug` / `testDebugUnitTest` / `lint` / `check` 实跑通过，签名 APK 由 dev-A-2 实测产出）；模块依赖现状与 docs/02 §3.2 一致（`:app` 顶层无 database/player；feature 无 player）；接口 v1 的 7 项要点（`AppError`/`FailureClass`/`Logger.flush`/`DispatcherProvider.engine`/`SessionIdFactory`/`Redactor`/`AppScopeProvider`）逐条落地；规则 1 的守护**实测有效**；脱敏自查通过。
- **放行条件（均为「不挡本次合并、必须在指定卡前闭环」）**：
  1. CR-01 + CR-02 → 在 **P0-7（CI 卡）** 收口前落地依赖矩阵守护，并把 CI 的 grep 换成 `check`；
  2. CR-03 → arch 提案 + god 批准，回写 docs/02 §3.2 矩阵（含补 `testing`），必须在 CR-01 的守护之前定准；
  3. CR-04 → arch 提案 v1.1（`TLS`/`EMPTY_MEDIA`/`PLAYLIST_GONE`/`CANCELLED` 工厂），god 批准后 dev-A 落地，不迟于 P1-1/P1-3；
  4. CR-05 → 在 **P0-6（日志骨架）** 前定死 `Logger` 便捷方法的信封填充方式；
  5. CR-06 → 在 **P1-3（起播）** 前补 `usesCleartextTraffic`，否则 S1 复测口径会被污染；
  6. CR-13 → god 提交后跑一次真实 CI，P0 出口才算闭环。
- **未闭环阻断项**：无。
- **最担心的一条**：CR-01 —— 架构文档写「CI 强制」，而构建期实际只守着「纯 Kotlin 模块不许 import Android」这一条文本规则；「只有 `:feature:player` 能碰 `:core:player`」「`:app` 只做组装」目前全靠人自觉，实测可无声违反。这是 P0 骨架的核心价值所在，建议优先于其他建议项处理。
- **签字**：评审人 arch（Oscar / `worker-arch-3`） ｜ 作者 dev-A / dev-A-2 **待确认** ｜ god 集成 **待签**
- **后续**：本记录只登记不修；作者按上表修复后在 `03-测试计划与用例.md` §8 触发式回归矩阵跑对应回归，再由 arch 复评闭环项。
