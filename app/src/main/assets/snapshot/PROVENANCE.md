# 内置快照来源说明（channels.m3u）

<!-- snapshot-channels: 154 -->
<!-- snapshot-groups: 央视=25,卫视=22,港澳台=4,地方/其他=103 -->

卡：`SNAP-REFRESH-1`（快照修订 + 刷新流程工具化 + 文案校正）。生成：`tools/snapshot/build_snapshot.py`，
实测数据：`tools/snapshot/probe.sh`（电视本机逐条起播 + hold）。同目录的 `channels.m3u` 是 APK
**出厂即带**的频道清单；首启（频道表为空）由 `RoomCatalogSeeder` 经 `DataModule` 播种进 Room，
用户手动导入或联网刷新**不会被它覆盖**。

## 1. 内容

| 项 | 值 |
|---|---|
| 频道数 | **154**（一行一频道、一条流；按 `(显示名, 分组)` 去重、无合并） |
| 分组 | 央视 25 · 卫视 22 · 港澳台 4 · 地方/其他 103 |
| 行属性 | 每行 `tvg-id` / `tvg-name` / `tvg-chno`（1..154）/ `group-title` / 显示名 |
| 排序 | 与生成时的候选清单一致（央视 → 卫视 → 港澳台 → 地方/其他），组内保持人工排序 |

## 2. 收录判据（两条，都在电视上实测）

| 判据 | 阈值 |
|---|---|
| 首帧（`PLAY_FIRST_FRAME` 同口径：prepare → 首个渲染帧） | **≤ 3000 ms** |
| 连续播放 | **≥ 30 s 无 stall**（首帧后播放/缓冲位置连续 ≥2 s 不变化即判定为 stall） |

一条候选**两条都过**才收录。测法：`tools/snapshot/probe.sh` 驱动一次性测量夹具
`ilab.iptv.player.spike/HoldProbeActivity`（与产品同引擎族：Media3 `ExoPlayer` + `media3-exoplayer-hls`，
跑在电视本机解码），逐 URL 起播、记录首帧成本、再 hold 30 s 观察是否卡死。

## 3. 实测记录

| 项 | 值 |
|---|---|
| 设备 | Sony BRAVIA 4K VH21 (Android 12 / API 31) |
| 日期 | 2026-09-23 |
| 候选池 | `上一版快照的 189 条 ∪ 本网段可达的补位候选（全部来自 P2 采集的 `tools/fixtures/large/validated.m3u`）`（P2 采集；本文件是它的**子集**） |
| 本轮电视实测样本 | **245 条 URL**（逐条起播 + hold） |
| 通过两条判据 | **156 条** |
| 收录 | **154 条**（受候选顺序与每台一条流约束） |
| 首帧（通过样本） | 中位 **662 ms**，p90 **1860 ms** |

## 4. 未收录 / 已知失效

本轮被剔除的原因分布（同一批候选）：

- probe outcome=no_first_frame：59 条
- probe outcome=error_after_first_frame：24 条
- probe outcome=ended：4 条
- probe outcome=stall：2 条
- dropped by --drop-url：1 条
- duplicate (name_key, group_key)：1 条

电视侧**反复失败**的宿主（≥2 条样本失败；这些是"源/CDN/路径"问题，不是播放器问题）：

- `ali-m-l.cztv.com`：6 条失败
- `epg.pw`：4 条失败
- `live.yantaitv.cn`：4 条失败
- `genglei.8866.org`：3 条失败
- `gmxw.7766.org`：3 条失败
- `live.france24.com`：2 条失败
- `m3u8-channel.lytv.tv`：2 条失败
- `vip.ffzy-play.com`：2 条失败
- `vip.ffzy-play7.com`：2 条失败
- `vip.ffzy-online.com`：2 条失败
- `l.cztvcloud.com`：2 条失败
- `tmpstream.hyrtv.cn`：2 条失败

## 5. 没验证的事（不要当已验证）

- **快照会过期**：这些多是地方台直链，源站改动、令牌过期、换网络出口都会让条目失效。
  过期判据就是上面两条（≤3000 ms 首帧 + 30 s 无 stall）。
- 每条流只测了**一次** 30 s 窗口，没有跑小时级长稳，也没有测夜间时段与多台并发。
- 地理位置/运营商变化：换网络（尤其换出口）后可用性会变。
- EPG 覆盖率、换台/故障转移成功率不在本文件的判据内。

## 6. 重生成入口（一条命令）

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
tools/snapshot/probe.sh --candidates tools/fixtures/large/validated.m3u --out /tmp/snapshot_probe
tools/snapshot/build_snapshot.py --candidates tools/fixtures/large/validated.m3u \
    --probe /tmp/snapshot_probe/probe.jsonl \
    --out-m3u app/src/main/assets/snapshot/channels.m3u \
    --out-provenance app/src/main/assets/snapshot/PROVENANCE.md
tools/snapshot/verify.sh app/src/main/assets/snapshot/channels.m3u
```

详见 `tools/snapshot/README.md`。

## 7. 合规提示（给集成者）

本文件把**第三方直播流 URL**打进了公开仓库与 APK（人工 2026-09-22 明确要求"在 App 里内置一份快照"，
卡 `SNAP-REFRESH-1` 延续该口径）。这些 URL 均为上游公开聚合清单中的地址；若要对外分发，请自行确认
再分发的授权与合规口径。生成脚本会剔除带凭据查询键与私网地址的条目。
