# Shadow 发布签名规范

Shadow 正式 APK 使用固定发布签名，以保证 `com.shadow.app` 可以跨设备、跨构建机覆盖安装。

## 签名身份

- 密钥别名：`shadow`
- 算法：RSA 4096
- 有效期：2026-08-14 至 2053-12-30
- SHA-256：`77:E3:32:BC:57:EE:75:40:02:6D:A3:7D:0B:CD:74:BB:B2:5D:43:8D:F3:56:FB:28:DE:27:8C:AD:22:7A:14:EB`

证书指纹是公开信息，用于确认构建机拿到的是正确密钥；它不能用于签名 APK。

## NAS 存储

```text
nas:/data/project/.secrets/shadow-app/shadow-release.jks
nas:/data/project/.secrets/shadow-app/shadow-release.password
```

目录权限为 `700`，两个文件权限为 `600`。不得把密钥、密码文件或它们的明文副本提交到 Git。

## 统一构建

已配置 `nas` SSH 主机的构建机执行：

```bash
./scripts/build-release.sh
```

脚本会：

1. 从 NAS 拉取 JKS 和密码到权限受限的临时目录。
2. 校验证书 SHA-256 指纹，不一致时拒绝构建。
3. 通过环境变量把临时路径交给 Gradle。
4. 构建结束或中断后删除临时签名材料。

只验证远端签名材料、不执行构建：

```bash
./scripts/build-release.sh --verify-signing
```

发布前必须递增 `app/build.gradle.kts` 中的 `versionCode`。只有包名、签名证书一致且版本代码不降低时，Android 才能覆盖已有安装。

## 恢复要求

NAS 不是唯一备份。应另外保存至少一份离线加密备份，且备份密码不要和文件放在同一介质。若签名私钥丢失或损坏，现有安装将无法继续接收同包名更新。
