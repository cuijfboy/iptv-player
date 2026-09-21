# IPTV Player for Android TV

面向客厅 Android TV 的**开源 IPTV 播放器**：自动收集并定期刷新直播源、流畅播放、遥控器友好的 TV 交互。

- **包名**：`ilab.iptv.player`
- **目标设备**：Sony BRAVIA 4K VH21（Android 12 / API 31 / **armeabi-v7a 32 位** / 3 GB RAM）
- **技术栈**：Kotlin · Android Views + Leanback · Media3/ExoPlayer · Room · OkHttp · Hilt · WorkManager
- **许可证**：Apache-2.0

> 当前状态：**设计阶段**（需求与选型已确认，尚未开始编码）。进度见 [04 开发计划](./docs/04-开发计划与验收.md)。

---

## 文档

| 文档 | 内容 |
|---|---|
| [01 需求与可行性](./docs/01-需求与可行性.md) | 需求复述、设备约束、现状盘点、可行性评估、**决策记录**、合规声明 |
| [02 技术架构设计](./docs/02-技术架构设计.md) | 分层模型、模块边界与依赖规则、接口契约、数据库、关键流程、扩展点、并发与错误模型 |
| [03 日志与诊断设计](./docs/03-日志与诊断设计.md) | 事件码规范、多 Sink 日志、轮转落盘、诊断面板、一键导出诊断包、崩溃捕获、排查手册 |
| [04 开发计划与验收](./docs/04-开发计划与验收.md) | P0–P4 阶段任务、Spike 方案、验收指标、测试与发布流程、风险登记册 |
| [docs/reference](./docs/reference/) | 背景资料（IPTV 原理与开源实现、源验证管线、设备审计【已脱敏】、可行性方案） |

---

## 核心设计要点

1. **App 运行期完全自给自足（ADR-001）**：源采集、校验、EPG、播放、存储全部在电视本机完成，**不依赖任何服务器**作为运行前提。
2. **分层与解耦**：`feature → domain ← data → platform`；`core:domain`/`core:model`/`core:common` 为纯 Kotlin（CI 强制无 Android 依赖）。
3. **决策逻辑纯函数化**：选源、评分、故障转移、EPG 匹配都是纯 Kotlin 策略，可脱离 Android 单测。
4. **一切可插拔**：源、校验器、评分规则、播放引擎、EPG 源、日志出口均以 Hilt `@IntoSet` 注册，新增实现不改既有代码。
5. **可观测内建**：关键路径全部带**事件码**；日志同时输出到 logcat / 内存环 / 文件 / 崩溃文件，并提供 App 内诊断面板与一键导出诊断包。
6. **失败不跨边界**：模块间用 `AppResult<T>` 传递错误；长任务可中断、可续跑。

---

## 计划阶段

| 阶段 | 主题 | 关键验收 |
|---|---|---|
| P0 | 骨架与 Spike | 可装机 APK；播放成功率 ≥90%、起播 ≤3 s；AC3 实测结论；EPG 网格 ≥50 fps |
| P1 | 能看 | 换台 ≤3 s；故障转移 5 s 内生效 |
| P2 | 好用 | 落库 + 本机刷新 + 诊断面板；冷启动 ≤5 s |
| P3 | 完整 | EPG 时间网格；EPG 覆盖率 ≥60% |
| P4 | 进阶（可选） | 录制 / 回看 / PiP / 局域网分发 |

---

## 目录结构（规划）

```
app/                 组装、前台服务、导航
feature/             channels · player · epg · settings · wizard
core/                common · model · domain（纯 Kotlin）
                     log · database · network · source · epg · player · data · ui · design · testing
build-logic/         Gradle 约定插件
keystore/            签名密钥（口令不入库）
tools/               dev-pipeline（可选，非运行时依赖）· fixtures（测试夹具）
docs/                设计文档与背景资料
```

---

## 构建与安装（P0 之后可用）

```bash
./gradlew testDebugUnitTest lint assembleRelease
adb connect <电视IP>:5555
adb install -r app/build/outputs/apk/release/app-release.apk
```

> 本机开发环境：Android Studio 2025.3.4 自带 JBR（JDK 21），**系统无独立 Java**，Gradle 通过 `org.gradle.java.home` 指向 JBR。

---

## 合规声明

> 本项目为**技术工具**，不内置、不托管、不推荐任何具体频道源。所有源均来自用户自行配置的公开或自建地址。
> 公共 IPTV 列表多包含**未经授权**的转播内容，请仅用于学习或已获授权的内容，使用风险自负。
> 本项目不提供、不集成任何绕过 DRM 或付费墙的能力。
