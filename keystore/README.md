# 签名密钥说明

- `release.jks`：本项目的 Release 签名密钥，**随仓库保存**（便于多机/CI 统一签名、支持 `adb install -r` 覆盖升级）。
- **口令不入库**：请在仓库根目录或本目录创建 `local.properties`（已在 `.gitignore` 中）：

```properties
# keystore/local.properties
RELEASE_STORE_FILE=keystore/release.jks
RELEASE_STORE_PASSWORD=******
RELEASE_KEY_ALIAS=iptv
RELEASE_KEY_PASSWORD=******
```

- CI 场景：把上述四项配置为 GitHub Actions Secrets。
- 轮换密钥：更换 `release.jks` 会导致已安装设备无法覆盖升级，需先卸载（会清除应用数据）。

## 当前密钥档案（2026-09-21 生成）

- 文件：`keystore/release.jks`，类型 PKCS12，条目 1 个。
- Alias：`iptv`；算法 RSA 2048；有效期 10950 天（约 30 年，至 2056-09-21）。
- 口令：随机生成，写在**仓库根目录** `local.properties` 的 `RELEASE_STORE_PASSWORD` /
  `RELEASE_KEY_PASSWORD`（该文件已被 `.gitignore` 忽略，未入库，也未在任何报告里出现）。
- 生成方式（离线，用 Android Studio JBR 的 keytool）：

```bash
"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" \
  -genkeypair -keystore keystore/release.jks -storetype PKCS12 -alias iptv \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -storepass "$RELEASE_STORE_PASSWORD" -keypass "$RELEASE_KEY_PASSWORD" \
  -dname "CN=IPTV Player, OU=Android, O=ilab, L=Shanghai, ST=Shanghai, C=CN"
```
