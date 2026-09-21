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
