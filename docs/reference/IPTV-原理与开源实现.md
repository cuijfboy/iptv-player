# 电视 IPTV 观看：原理、架构与开源实现方案

> 适用对象：客厅 Sony BRAVIA 4K VH21（Android 12，32 位 armeabi-v7a，无 root）
> 编写日期：2026-09-21　｜　所有 GitHub 项目星级/许可/状态均于当日核对
> 本文档只讲方案与配置，不包含任何具体频道源地址。

---

## 0. 一页速览（TL;DR）

- **IPTV = 用 IP 网络分发电视直播**。落地只需三件套：**播放列表（M3U）+ 节目单（EPG / XMLTV）+ 播放器**。
- 绝大多数公共源都是 **HLS（.m3u8）**，走 HTTP，能穿透 NAT；运营商盒子用的是 **UDP/IGMP 组播**，只能在专网内用。
- 电视端最省事：**TiviMate**（已装，闭源）；全开源：**Kodi + PVR IPTV Simple Client**（已装 Kodi）或 **Jellyfin Live TV**（已装客户端 + 局域网服务器）。
- 进阶：在局域网 **Debian 12（192.168.x.x）** 上跑 **Threadfin / Dispatcharr / TVHeadend** 做聚合与代理，电视端只负责播放。
- 公共 IPTV 列表大多包含**未经授权的转播**，请只用于学习或合法授权内容（见第 7 节）。

---

## 1. 原理与概念

### 1.1 IPTV 是什么

传统电视通过 DVB-C/S/T、有线或卫星把频道推给机顶盒；IPTV 把同样的直播信号改为**在 IP 网络上按需拉取**。对播放器而言，一个"频道"本质就是**一个网络流地址**；"换台"就是**换一个 URL 并重新建立解码管线**。

### 1.2 传输协议

| 协议 | 承载 | 典型延迟 | 能否跨公网 | 备注 |
|---|---|---|---|---|
| **HLS**（.m3u8 + TS/fMP4 分片） | HTTP(S) | 6–30 s+ | 能 | 公共源绝对主流；兼容性最好；可自适应码率 |
| **HTTP-TS** | HTTP(S) 连续 TS | 2–10 s | 能 | 单文件流，常见于国内源 |
| **RTMP** | TCP 1935 | 2–5 s | 一般 | Flash 时代遗留，部分源仍用 |
| **RTSP** | TCP/UDP | 1–3 s | 一般 | 监控/机顶盒常见，Android TV 客户端支持有限 |
| **UDP / IGMP 组播** | 局域网组播 | 小于 1 s | 否 | 运营商 IPTV 主力，**不能跨路由/公网**，需 IGMP Proxy |
| **SRT** | UDP | 1–3 s | 能 | 抗丢包，多用于回传 |
| **WebRTC** | UDP | 小于 1 s | 能 | 低延迟新方案，生态仍在成熟 |

> 结论：想用"网上找的源"，基本都是在处理 **HLS / HTTP-TS**；运营商组播源拿不到、也播不了。

### 1.3 播放列表：M3U / M3U8

**M3U 播放列表**（频道清单）长这样：

```m3u
#EXTM3U
#EXTINF:-1 tvg-id="CCTV1.cn" tvg-name="CCTV1" tvg-logo="https://example.com/logo.png" group-title="央视",CCTV-1 综合
#EXTVLCOPT:http-user-agent=Mozilla/5.0
#EXTVLCOPT:http-referrer=https://example.com/
https://example.com/live/cctv1.m3u8
```

关键要素：

- `#EXTM3U`：文件头，必须在第一行。
- `#EXTINF:-1 属性,显示名`：一条频道记录；`-1` 表示未知时长（直播）。
- **常用属性**
  - `tvg-id`：**与 EPG 绑定的唯一键**（最重要）
  - `tvg-name` / `tvg-logo`：名称与台标
  - `group-title`：分组（央视/卫视/港澳/体育…）
  - `tvg-chno`：预设频道号
  - `catchup` / `catchup-source` / `timeshift`：**回看（时移）**能力声明
- `#EXTVLCOPT:`：VLC 及部分 Android 播放器识别，常用于绕过防盗链：`http-user-agent`、`http-referrer`
- `#KODIPROP:`：Kodi 专用（如 `inputstream.adaptive` 参数、`mimetype`）
- `#EXTGRP:`：另一种分组写法

> 注意名词坑：`.m3u8` 既可能是"频道清单"（M3U 文本），也可能是"HLS 媒体播放列表"（TS 分片列表）。播放器靠内容而非扩展名判断。

### 1.4 EPG（电子节目单）/ XMLTV

```xml
<?xml version="1.0" encoding="UTF-8"?>
<tv generator-info-name="demo">
  <channel id="CCTV1.cn">
    <display-name>CCTV-1 综合</display-name>
    <icon src="https://example.com/logo.png"/>
  </channel>
  <programme start="20260921120000 +0800" stop="20260921123000 +0800" channel="CCTV1.cn">
    <title lang="zh">新闻 30 分</title>
    <desc lang="zh">午间新闻</desc>
  </programme>
</tv>
```

- 播放器按 **`tvg-id` 对 `<channel id>`** 做匹配，所以**两边 ID 必须一致**，否则"有台无单"。
- 时间格式固定为 `YYYYMMDDHHMMSS +ZZZZ`。
- 来源：① API/源站自带；② **iptv-org/epg** 抓取；③ **WebGrab+Plus**。

### 1.5 播放器工作流程

```
解析 M3U ──► 建频道表(名称/分组/台标/URL/属性)
   │
载入 XMLTV ──► 按 tvg-id 关联节目单
   │
用户选台 ──► 带 UA/Referer 发起 HTTP 请求
   │
拉取 .m3u8 ──► 解析分片列表 ──► 循环下载 TS/fMP4 分片
   │
解复用 ──► 视频解码(H.264/H.265) + 音频解码(AAC/AC3/EAC3)
   │
缓冲 ──► 渲染输出；失败则重试 / 切备用源
```

### 1.6 常见障碍（这决定你要不要上"代理"）

| 障碍 | 说明 | 对策 |
|---|---|---|
| **CORS** | 只有**网页/Web 端**才受限；原生 App 不受影响 | 自建反代加 CORS 头 |
| **UA / Referer 防盗链** | 源站校验请求头 | `#EXTVLCOPT`、或代理改写头部 |
| **Token 时效** | URL 带 token 参数会过期 | 用会定期刷新清单的源/中间层 |
| **地理限制** | 按 IP 国家/地区限制 | 一般无法在电视端解决 |
| **组播不可路由** | UDP/IGMP 只在运营商专网 | 放弃或自建网关 |
| **编码不兼容** | 32 位老设备可能不支持 H.265/AV1，或 AC3/EAC3 无声 | 选 H.264/AAC 的源，或服务器转码 |
| **并发数限制** | 同一账号多设备播放被踢 | 控制并发，或用代理统一出口 |

---

## 2. 整体架构与数据流

```
┌─────────────────────────────────────────────────────────────┐
│ ① 源层   源站/运营商/公开列表 (M3U · Xtream Codes · JSON API) │
└───────────────┬─────────────────────────────────────────────┘
                ▼
┌─────────────────────────────────────────────────────────────┐
│ ② 采集/聚合   iptv-org/api · 自写脚本 · TVBox 接口            │
└───────────────┬─────────────────────────────────────────────┘
                ▼
┌─────────────────────────────────────────────────────────────┐
│ ③ 清洗/去重/分组/过滤   tuliprox · m3ufilter · 脚本           │
└───────────────┬─────────────────────────────────────────────┘
                ▼
┌─────────────────────────────────────────────────────────────┐
│ ④ 代理/网关   Threadfin · Dispatcharr · iptv-proxy ·         │
│               TVHeadend                                      │
│   • 改写 UA/Referer   • 转封装/转码   • 统一 EPG 与频道号     │
│   • 输出 M3U / HDHomeRun / Xtream / M3U8                     │
└───────────────┬─────────────────────────────────────────────┘
                ▼
┌─────────────────────────────────────────────────────────────┐
│ ⑤ 播放器   TiviMate · Kodi(PVR) · Jellyfin · TVBox            │
└───────────────┬─────────────────────────────────────────────┘
                ▼
┌─────────────────────────────────────────────────────────────┐
│ ⑥ 解码渲染   解复用 → 音视频解码 → 显示/输出                  │
└─────────────────────────────────────────────────────────────┘
```

**两种部署模式**

- **直连模式**：播放器直接拉源 URL。最简单，但防盗链/失效无法统一管理。对应路线 A/B。
- **代理模式**：播放器只拉局域网服务器，由服务器去面对源。更稳，可统一 EPG、加头部、转码、给多设备共用。对应路线 C/D。

---

## 3. GitHub 开源方案全景（2026-09 核对）

### 3.1 源 / 播放列表聚合

| 项目 | 星标 | 许可 | 用途 |
|---|---|---|---|
| [iptv-org/iptv](https://github.com/iptv-org/iptv) | 139k | Unlicense | 全球公开频道 M3U 汇总（公共领域数据） |
| [iptv-org/api](https://github.com/iptv-org/api) | 818 | Unlicense | 结构化数据 API：频道/流/分类/国家/节目单索引 |

**iptv-org API 端点（实测 HTTP 200）**

```
https://iptv-org.github.io/api/channels.json      # 频道元数据（含 tvg-id、国家、分类）
https://iptv-org.github.io/api/streams.json       # 频道 → 流地址
https://iptv-org.github.io/api/guides.json        # 频道 → EPG 源
https://iptv-org.github.io/api/categories.json
https://iptv-org.github.io/api/countries.json
https://iptv-org.github.io/api/languages.json
```

### 3.2 EPG（节目单）

| 项目 | 星标 | 许可 | 用途 |
|---|---|---|---|
| [iptv-org/epg](https://github.com/iptv-org/epg) | 3.3k | Unlicense | 批量抓取数千频道的 XMLTV 节目单 |
| [XMLTV/xmltv](https://github.com/XMLTV/xmltv) | 418 | GPL-2.0 | XMLTV 格式工具链 |
| [WebGrab+Plus](https://www.webgrabplus.com/) | — | 免费闭源 | 老牌抓取器；配套 [siteini.pack](https://github.com/SilentButeo2/webgrabplus-siteinipack) 196 星 |

### 3.3 代理 / 网关

| 项目 | 星标 | 许可 | 用途 |
|---|---|---|---|
| [Threadfin/Threadfin](https://github.com/Threadfin/Threadfin) | 1.7k | MIT | xTeVe 续作；M3U/Xtream 入，HDHomeRun/M3U 出给 Plex/Jellyfin/Emby |
| [xteve-project/xTeVe](https://github.com/xteve-project/xTeVe) | 2.3k | MIT | 经典 M3U 代理，仍更新 |
| [Dispatcharr/Dispatcharr](https://github.com/Dispatcharr/Dispatcharr) | 4.1k | AGPL-3.0 | 新一代 IPTV/流管理平台（Web 界面、多源聚合、EPG） |
| [euzu/tuliprox](https://github.com/euzu/tuliprox) | 564 | MIT | Rust 写的播放列表处理器与代理（M3U/Xtream） |
| [pierre-emmanuelJ/iptv-proxy](https://github.com/pierre-emmanuelJ/iptv-proxy) | 717 | GPL-3.0 | 对 M3U/M3U8/Xtream 做反向代理 |
| [tellytv/telly](https://github.com/tellytv/telly) | 827 | MIT | **已归档(2023)**，用 Threadfin/Dispatcharr 替代 |

### 3.4 服务端（媒体服务器 / 流服务器）

| 项目 | 星标 | 许可 | 用途 |
|---|---|---|---|
| [tvheadend/tvheadend](https://github.com/tvheadend/tvheadend) | 3.5k | GPL-3.0 | 最强电视服务器；支持 **IPTV 网络类型**（吃 M3U）+ DVB；可做 Kodi PVR 后端 |
| [jellyfin/jellyfin](https://github.com/jellyfin/jellyfin) | 57k | GPL-2.0 | 媒体服务器，内置 **Live TV**（M3U Tuner + XMLTV Guide） |
| [ErsatzTV/legacy](https://github.com/ErsatzTV/legacy) | 3.0k | Zlib | 把本地媒体库编排成"自定义直播频道"（注意仓库名已变 legacy） |

### 3.5 播放器客户端（Android TV 可用）

| 项目 | 星标 | 许可 | 说明 |
|---|---|---|---|
| [kodi-pvr/pvr.iptvsimple](https://github.com/kodi-pvr/pvr.iptvsimple) | 942 | GPL-2.0 | Kodi 的 PVR IPTV Simple Client，**全开源首选** |
| [4gray/iptvnator](https://github.com/4gray/iptvnator) | 7.2k | MIT | 跨平台 IPTV 播放器（Electron，含 EPG） |
| [FongMi/TV](https://github.com/FongMi/TV) | 9.5k | GPL-3.0 | TVBox 生态主流 Android 客户端 |
| [j4Uq/TVBoxOSC](https://github.com/j4Uq/TVBoxOSC) | 17.6k | — | TVBox 开源社区版（聚合播放器） |
| [takagen99/Box](https://github.com/takagen99/Box) | 3.0k | AGPL-3.0 | TVBox 另一活跃分支 |
| Jellyfin Android TV | 见主库 | GPL-2.0 | 已装 0.19.10，自带 Live TV 入口 |

> 闭源但好用（仅作对照）：**TiviMate**（已装 5.3.2）、Perfect Player、IPTV Pro。

### 3.6 辅助工具

| 项目 | 星标 | 许可 | 用途 |
|---|---|---|---|
| [jfarseneau/antennas](https://github.com/jfarseneau/antennas) | 376 | MIT | 把 TVHeadend 模拟成 HDHomeRun，供 Plex DVR 接入 |

---

## 4. 五条落地路线（针对这台电视）

> 电视现状：Kodi 21.3、Jellyfin Android TV 0.19.10、TiviMate 5.3.2、VLC 3.7.1 已装；无 root；仅 32 位 ABI；局域网有 Debian 12 (192.168.x.x)。

### 路线 A：TiviMate（已装，闭源，最省事）

**组成**：TiviMate + 一个 M3U URL + 一个 XMLTV URL。

**步骤**
1. 打开 TiviMate → 设置 → **播放列表** → 添加播放列表 → **M3U 播放列表** → 填 URL（或选本地文件）。
2. 设置 → **EPG** → 添加节目源 → XMLTV URL（或"使用播放列表中的 EPG"）。
3. 设置 → 外观/频道 → 分组、隐藏不需要的台、设置频道号。
4. 需要时开 **录制 / 回看**（Premium）。

**优点**：体验最好、遥控器友好、频道管理强、支持多播放列表/录制/回看。
**缺点**：闭源；免费版仅 1 个播放列表、功能受限，完整功能需付费。
**适合**：想要"开箱即用、少折腾"。

### 路线 B：Kodi + PVR IPTV Simple Client（全开源，已装）

**组成**：Kodi 21.3 + pvr.iptvsimple 插件 + M3U + XMLTV。

**步骤**
1. Kodi → 设置 → 插件 → **从库安装** → PVR 客户端 → **PVR IPTV Simple Client** → 安装。
2. 进入插件 **配置**：
   - General → **M3U Play List URL**（或本地路径）
   - EPG → **XMLTV URL**、**EPG 时间偏移**（时区不对时用）
   - General → 缓存时间、连接超时
3. Kodi → 设置 → **PVR 和直播电视** → 常规 → **启用**。
4. 主界面出现 **TV / 电台** 菜单。

**优点**：全开源、可深度定制、支持 catchup/录制、与 Kodi 皮肤生态结合。
**缺点**：配置项多，首次上手比 TiviMate 麻烦；UI 依 Kodi。
**适合**：喜欢全开源、愿意折腾。

### 路线 C：Jellyfin Live TV（全开源，已装客户端）

**组成**：局域网 Jellyfin 服务器（可跑在 192.168.x.x 的 Docker）+ 电视端 Jellyfin App。

**步骤**
1. 在服务器部署 Jellyfin（jellyfin/jellyfin 镜像），初始化后进入控制台。
2. **Live TV** → 添加调谐器 → **M3U Tuner** → 填 M3U URL。
3. 添加 **Guide Provider** → **XMLTV** → 填 XMLTV URL（可设刷新间隔）。
4. 电视端打开 Jellyfin App → **Live TV** 即可观看。

官方文档：<https://jellyfin.org/docs/general/server/live-tv/>

**优点**：全开源；与现有 Jellyfin 媒体库/用户体系/多端统一；频道与 EPG 在服务器集中管理。
**缺点**：需要一台常开服务器；频道切换与画质体验略逊 TiviMate；EPG 频道映射要花时间对齐。
**适合**：已有/打算有 Jellyfin 生态。

### 路线 D：局域网 Debian 服务器（Threadfin / Dispatcharr / TVHeadend，进阶）

**组成**：192.168.x.x（Debian 12）跑代理服务，电视端用任意播放器直连局域网。

**三种服务选型**

| 服务 | 定位 | 输出 | 适合 |
|---|---|---|---|
| **Threadfin** | M3U/Xtream 入 → HDHomeRun/M3U 出 | 给 Plex/Jellyfin/Emby | 想接现有媒体服务器 |
| **Dispatcharr** | 新一代 IPTV 管理平台（Web UI） | M3U / HDHomeRun / 代理 | 多源聚合、频道管理、EPG 统一 |
| **TVHeadend** | 完整电视服务器（IPTV 网络类型） | HTSP / M3U / 流 | 想要最强功能、给 Kodi 做 PVR 后端 |

**通用步骤**
1. 服务器装 Docker + Docker Compose。
2. 部署服务（示例见附录 C）。
3. Web 界面添加 M3U/Xtream 源；设置 **UA / Referer**；映射 EPG。
4. 输出本地 URL（如 http://192.168.x.x:34400/m3u/...），填入电视端播放器。

**优点**：多源聚合去重、统一 EPG/频道号、**自动改写防盗链头**、可转码、多客户端共享、电视端零配置。
**缺点**：要维护服务器；转发占带宽/CPU；有学习曲线。
**适合**：源多、想长期稳定、多设备共用。

### 路线 E：TVBox 系（FongMi/TV、TVBoxOSC，需安装）

**组成**：TVBox 客户端 APK（选 armeabi-v7a）+ 一个第三方"接口"（JSON 配置 URL）。

**步骤**
1. 下载 FongMi/TV 或 TVBoxOSC 的 **armeabi-v7a** APK。
2. 用 adb install 安装（本机仅 32 位 ABI，勿装 arm64 包）。
3. 打开 → 设置 → 配置地址 → 填接口 URL → 首页出现点播/直播聚合。

**优点**：聚合点播+直播，界面友好，扩展性强。
**缺点**：**生态混乱、半开源、第三方"接口"来源不明**，含合规与安全（恶意域名/隐私）风险；接口随时失效。
**适合**：了解并愿意承担风险、自备合法接口。

### 4.6 五条路线横向对比

| 维度 | A. TiviMate | B. Kodi PVR | C. Jellyfin Live TV | D. Debian 代理 | E. TVBox |
|---|---|---|---|---|---|
| 开源 | 闭源 | 是 | 是 | 是 | 部分 |
| 电视端安装 | 已装 | 已装 | 已装 | 已装播放器 | **需装 APK** |
| 需要服务器 | 否 | 否 | 是 | 是 | 否 |
| M3U 支持 | 是 | 是 | 是 | 是 | 是 |
| EPG 支持 | 最好 | 是 | 是 | 是 | 看接口 |
| 防盗链代理 | 否 | 有限 | 有限 | **强** | 否 |
| 录制/回看 | 是 | 是 | 是 | 视上游 | 否 |
| 多设备共享 | 否 | 否 | 是 | 是 | 否 |
| 上手难度 | 低 | 中 | 中高 | 高 | 中 |
| 主要风险 | 闭源+付费 | 配置繁琐 | 需服务器 | 维护成本 | **合规/安全** |
| 推荐场景 | 图省事 | 全开源单机 | 已有 Jellyfin | 长期/多设备 | 不推荐首选 |

---

## 5. 通用实操

### 5.1 准备播放列表

1. **获取合法源**（自建、官方授权、公共领域如 iptv-org 的公共频道）。
2. 存为 .m3u 文件，或托管成 HTTP URL（电视端只能填 URL 时必需）。
3. **规范化**：
   - 补全 tvg-id（决定能否有节目单）
   - 按 URL 去重、按 group-title 分组
   - 无效源剔除（连续超时）
4. 模板见 **附录 A**。

### 5.2 配置 EPG

1. 取得 XMLTV：用 iptv-org/epg 抓取（附录 D），或源站自带。
2. 托管到 HTTP：局域网 python3 -m http.server 8080、Nginx，或直接放服务器。
3. **对齐 tvg-id**：用 iptv-org 的 channels.json + guides.json 交叉查找，确保 M3U 的 tvg-id 与 XMLTV 的 channel id 完全一致。
4. 时区不对 → 播放器里设 **EPG 时间偏移**。

### 5.3 用代理解决防盗链 / CORS

- Threadfin / Dispatcharr / TVHeadend 均可为单个源设置 **User-Agent** 与 **Referer**。
- 或用 Nginx 反代统一加头：

```nginx
location /proxy/ {
    proxy_pass https://upstream.example.com/;
    proxy_set_header User-Agent "Mozilla/5.0";
    proxy_set_header Referer "https://upstream.example.com/";
    add_header Access-Control-Allow-Origin *;
}
```

### 5.4 导入各客户端

| 客户端 | M3U 入口 | EPG 入口 |
|---|---|---|
| TiviMate | 设置 → 播放列表 → 添加 | 设置 → EPG → 添加节目源 |
| Kodi | 插件配置 → M3U Play List URL | 插件配置 → EPG → XMLTV URL |
| Jellyfin | 控制台 → Live TV → 调谐器 → M3U | Live TV → Guide Provider → XMLTV |
| TVBox | 设置 → 配置地址（接口 JSON） | 随接口 |

### 5.5 进阶

- **回看（catchup）**：M3U 中声明 catchup="append" / catchup-source="...&start=utc"，需源端支持。
- **录制**：TiviMate/Kodi/Jellyfin 均支持，注意电视存储（本机 /data 可用约 42 GB）。
- **多播放列表合并**：用 tuliprox / Dispatcharr 聚合后再给播放器单一入口。
- **转码**：源是 H.265 而电视 32 位解码吃力时，在服务器转 H.264（吃 CPU）。

---

## 6. 排查手册

| 症状 | 可能原因 | 处理 |
|---|---|---|
| 频道列表为空 | M3U URL 不通/格式错 | 浏览器打开验证；确认首行 #EXTM3U |
| 有台无节目单 | tvg-id 与 XMLTV 不匹配 | 对齐 ID；检查时区偏移 |
| 黑屏/一直转圈 | UA/Referer 被拦、源失效 | 加 #EXTVLCOPT 或走代理；换源 |
| 播放几秒后断 | Token 过期 / 并发限制 | 动态刷新清单；降低并发 |
| 只有画面没声音 | AC3/EAC3 不支持 | 换 AAC 源或服务器转码 |
| 花屏/卡顿 | 带宽不足 / 分片丢失 | 换低码率源；增大缓冲 |
| 电视端装不上 APK | 装成了 arm64 包 | 用 armeabi-v7a 或 universal 包 |
| 局域网服务器播放器连不上 | 防火墙/端口 | 放行端口；确认同网段 |

---

## 7. 合规、版权与安全提示

> 这一节请务必阅读。

1. **版权**：网上大量"公开 IPTV 列表"实为**未经授权转播**央视/卫视/境外频道，使用与传播可能侵权。请只使用**自建、官方授权、或明确公共领域**的源。本文档不提供也不推荐任何侵权源。
2. **稳定性**：公共源随时失效、画质参差、可能夹带广告或恶意跳转，不适合作为长期方案。
3. **安全**：第三方"接口/JSON 配置"可能指向恶意域名、采集隐私或注入内容；来源不明者不要导入。
4. **内容**：公共列表可能含不适合儿童的内容（如成人频道），注意过滤。
5. **隐私**：使用公共源意味着把观看行为暴露给第三方；敏感场景请用自建源。

---

## 8. 参考项目清单

| 项目 | 角色 | 语言 | 许可 | 星标 |
|---|---|---|---|---|
| [iptv-org/iptv](https://github.com/iptv-org/iptv) | 源聚合 | — | Unlicense | 139k |
| [iptv-org/api](https://github.com/iptv-org/api) | 源 API | — | Unlicense | 818 |
| [iptv-org/epg](https://github.com/iptv-org/epg) | EPG 抓取 | JS | Unlicense | 3.3k |
| [XMLTV/xmltv](https://github.com/XMLTV/xmltv) | EPG 工具 | Perl | GPL-2.0 | 418 |
| [Threadfin/Threadfin](https://github.com/Threadfin/Threadfin) | 代理 | Go | MIT | 1.7k |
| [Dispatcharr/Dispatcharr](https://github.com/Dispatcharr/Dispatcharr) | 管理平台 | Python | AGPL-3.0 | 4.1k |
| [euzu/tuliprox](https://github.com/euzu/tuliprox) | 清单处理/代理 | Rust | MIT | 564 |
| [pierre-emmanuelJ/iptv-proxy](https://github.com/pierre-emmanuelJ/iptv-proxy) | 反代 | Go | GPL-3.0 | 717 |
| [tvheadend/tvheadend](https://github.com/tvheadend/tvheadend) | 电视服务器 | C | GPL-3.0 | 3.5k |
| [jellyfin/jellyfin](https://github.com/jellyfin/jellyfin) | 媒体服务器 | C# | GPL-2.0 | 57k |
| [kodi-pvr/pvr.iptvsimple](https://github.com/kodi-pvr/pvr.iptvsimple) | 播放器插件 | C++ | GPL-2.0 | 942 |
| [4gray/iptvnator](https://github.com/4gray/iptvnator) | 播放器 | TS | MIT | 7.2k |
| [FongMi/TV](https://github.com/FongMi/TV) | 播放器 | Java | GPL-3.0 | 9.5k |
| [jfarseneau/antennas](https://github.com/jfarseneau/antennas) | HDHomeRun 模拟 | JS | MIT | 376 |

---

## 附录 A：标准 M3U 模板

```m3u
#EXTM3U x-tvg-url="https://example.com/epg.xml.gz"

#EXTINF:-1 tvg-id="ChannelA.cn" tvg-name="频道A" tvg-logo="https://example.com/a.png" group-title="示例分组" tvg-chno="1",频道A
#EXTVLCOPT:http-user-agent=Mozilla/5.0
#EXTVLCOPT:http-referrer=https://example.com/
https://example.com/live/a.m3u8

#EXTINF:-1 tvg-id="ChannelB.cn" tvg-name="频道B" group-title="示例分组" catchup="append" catchup-source="?utc={utc}&lutc={lutc}",频道B
https://example.com/live/b.m3u8
```

## 附录 B：XMLTV 片段

```xml
<?xml version="1.0" encoding="UTF-8"?>
<tv generator-info-name="demo" generator-info-url="https://example.com">
  <channel id="ChannelA.cn">
    <display-name>频道A</display-name>
    <icon src="https://example.com/a.png"/>
  </channel>
  <programme start="20260921120000 +0800" stop="20260921130000 +0800" channel="ChannelA.cn">
    <title lang="zh">示例节目</title>
    <desc lang="zh">节目描述</desc>
    <category lang="zh">新闻</category>
  </programme>
</tv>
```

## 附录 C：Threadfin / Dispatcharr Docker Compose 示例

```yaml
# Threadfin —— M3U 代理，向 Plex/Jellyfin/Emby 输出 HDHomeRun
services:
  threadfin:
    image: ghcr.io/threadfin/threadfin:latest
    container_name: threadfin
    ports:
      - "34400:34400"
    environment:
      - PUID=1000
      - PGID=1000
      - TZ=Asia/Shanghai
    volumes:
      - ./threadfin/conf:/home/threadfin/conf
      - ./threadfin/temp:/tmp/threadfin
    restart: unless-stopped
```

```yaml
# Dispatcharr —— 新一代 IPTV 管理平台（Web UI）
services:
  dispatcharr:
    image: ghcr.io/dispatcharr/dispatcharr:latest
    container_name: dispatcharr
    ports:
      - "9191:9191"
    environment:
      - TZ=Asia/Shanghai
    volumes:
      - ./dispatcharr/data:/data
    restart: unless-stopped
```

> 镜像名与端口以各自官方 README 为准，部署前请核对。

## 附录 D：iptv-org 命令行示例

```bash
# 1) 取频道元数据（含 tvg-id / 国家 / 分类）
curl -s https://iptv-org.github.io/api/channels.json | jq '.[0:3]'

# 2) 取"频道 → 流地址"并筛选
curl -s https://iptv-org.github.io/api/streams.json | jq -r '.[] | select(.channel != null) | [.channel, .url] | @tsv' | head

# 3) 抓 EPG（iptv-org/epg）
git clone --depth 1 https://github.com/iptv-org/epg.git
cd epg && npm install
npm run grab --- --channels=channels-custom.xml --output=guide.xml
# 抓完得到 guide.xml，托管成 HTTP 后填入播放器
```

---

## 附录 E：与本文档相关的本机现状

| 项目 | 现状 |
|---|---|
| 电视 | Sony BRAVIA 4K VH21，Android 12，armeabi-v7a（仅 32 位） |
| 已装播放器 | TiviMate 5.3.2、Kodi 21.3、Jellyfin Android TV 0.19.10、VLC 3.7.1 |
| 可作服务器 | Debian 12 @ 192.168.x.x（OpenSSH 9.2，端口 22） |
| 电视存储 | /data 可用约 42 GB（可用于录制） |
