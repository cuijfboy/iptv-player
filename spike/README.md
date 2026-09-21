# spike/ — P0 一次性播放/绘制夹具（可丢弃）

给 `docs/04 §2.2` 的 S1/S2/S3/S5/S6 采数据用，**不是产品代码**，结论见 `docs/05-过程记录/07-Spike报告.md`。

- 独立 Gradle 构建（自带 `settings.gradle.kts`），**不进入主工程构建**，避免干扰生产模块与 QA 验收包。
- `applicationId = ilab.iptv.player.spike`（与生产包 `ilab.iptv.player` 不同，可同机共存）。
- 只依赖 Media3（`media3-exoplayer` + `media3-exoplayer-hls`），不依赖 `core/*`、`feature/*`，不改任何生产契约。
- 三个入口：
  - `SpikeHomeActivity`：启动页 + S6 冷启动计时 + 设备能力探测（`-e probe true`）。
  - `PlaybackSpikeActivity`：S1/S2/S5。`-e mode s1|s2|s5`，`-e s2key aac|mp2|ac3|eac3`，`-e passthrough true|false`，`-e switches N`，`-e recreate true`，`-e urls <逗号分隔>`（本地样本用）。
  - `EpgGridSpikeActivity`：S3。`-e mode virtual|naive -e channels 658 -e hours 6 -e durationMs 30000`。
- 结果：写 `getExternalFilesDir()` 下的 JSON（`/sdcard/Android/data/ilab.iptv.player.spike/files/`），并打 `SPIKE` 标签日志。

```bash
# 生成样本（含真实 URL，产物被 .gitignore 忽略，可复现）
python3 spike/tools/make-samples.py

# 构建 + 签名 + 安装
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
$(ls -d ~/.gradle/wrapper/dists/gradle-8.13-bin/*/gradle-8.13/bin/gradle) -p spike assembleRelease
spike/tools/sign-release.sh
adb install -r spike/app/build/outputs/apk/release/app-release-spike.apk

# 跑全部序列 / 只跑本地音频样本
spike/tools/run-spikes.sh all
spike/tools/run-local-audio.sh <samples-dir>
```
