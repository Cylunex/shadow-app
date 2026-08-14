# Shadow App

Shadow App 是 Shadow 系列服务的独立 Android 壳。它提供一个原生应用中心，并在同一个安全、可配置的 WebView 容器里打开各个独立部署的 Web 应用。

首版内置：

- 健康：`/shealth/`
- 股票：`/stock/`

Android 包名为 `com.shadow.app`，应用显示名为 `Shadow`，最低支持 Android 10（API 29）。

## 功能

- 原生应用中心和统一的返回、主页、刷新、设置导航
- NAS 与云端双环境配置，模块按端口或目录独立路由和故障切换
- Web 登录 Cookie 持久化
- 网页文件上传、下载和外部链接处理
- HTTP Basic 入口认证
- 页面加载失败时的本地错误页
- 健康模块断网记录、自动补发和最近页面快照回放
- 保留 `ShellBridge.startScaleScan()`，兼容现有 shadow-health 网页
- 小米体脂秤 2 / S400 BLE 监听与失败队列补发
- 可选三星健康数据同步
- 每日健康提醒

应用清单和统一接入规则见 [模块接入规范](docs/module-integration.md)。
品牌色、图标和界面约定见 [品牌规范](docs/brand.md)。

## 构建

前置：

1. JDK 17
2. Android SDK Platform 35
3. 在项目根目录创建不入库的 `local.properties`：

   ```properties
   sdk.dir=/path/to/Android/sdk
   ```

构建：

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew --no-daemon testDebugUnitTest assembleDebug
```

APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 三星健康 SDK

三星 Health Data SDK 是专有依赖，不进入 Git。把
`samsung-health-data-api-1.1.0.aar` 放到 `app/libs/` 后重新构建，三星同步能力会自动启用。

没有 AAR 时项目仍可正常构建，设置页会显示“未安装 SDK”，其他能力不受影响。详见 [app/libs/README.md](app/libs/README.md)。

本地已有 AAR 时，可以用下面的命令验证无三星 SDK 构建：

```bash
./gradlew -PwithoutSamsung=true assembleDebug
```

## 发布签名

正式 APK 固定使用 NAS 中的同一份发布密钥：

```text
nas:/data/project/.secrets/shadow-app/shadow-release.jks
```

不要把 JKS、密码文件或 `keystore.properties` 提交到 Git。已配置 `nas` SSH 主机的电脑统一使用：

```bash
./scripts/build-release.sh
```

脚本只在构建期间把签名材料复制到权限受限的临时目录，结束后立即删除。需要使用其他 SSH 主机或远端目录时，可设置 `SHADOW_SECRETS_HOST`、`SHADOW_REMOTE_SECRETS_DIR`。发布新版本前必须递增 `versionCode`。

签名后的 APK 输出到：

```text
app/build/outputs/apk/release/app-release.apk
```

签名位置、证书指纹和恢复约定见 [发布签名规范](docs/release-signing.md)。

## 服务器配置

首次启动会要求填写 NAS 地址，并可选填写云端域名。默认 NAS 地址：

```text
http://192.168.1.100
```

模块路由由 `app/src/main/assets/modules.json` 拼接。当前健康和股票都指向 NAS 的 `55080` 端口：

```text
http://192.168.1.100:55080/shealth/
http://192.168.1.100:55080/stock/
```

云端 FRP 把 NAS 门户 `55080` 映射为远端 `20001`，因此填写云端域名后，健康和股票也具有以下备用入口：

```text
http://shadow.example.com:20001/shealth/
http://shadow.example.com:20001/stock/
```

云端地址的协议必须与 FRP 入口一致；直接 TCP 转发 HTTP 时应填写 `http://域名`。

模块可仅声明 `nas` 或 `cloud`，也可同时声明两条路由实现模块级自动切换。Garden 这类仅部署在云端的应用只需配置云端目录，不会影响健康和股票的 NAS 地址。

带 HTTP Basic 的入口可以写为：

```text
https://user:password@example.com
```

凭据只保存在 Android SharedPreferences 中，传给 WebView 前会从显示 URL 中移除。

## 目录

```text
app/src/main/
├── assets/                 # 应用中心、错误页、模块清单
├── java/com/shadow/app/
│   ├── MainActivity.java   # 通用 WebView 壳
│   ├── core/               # 模块注册表、地址配置
│   └── health/             # BLE 与健康原生适配
├── kotlin/.../health/      # 健康提醒
app/src/samsung/            # 有 Samsung AAR 时编译
app/src/noSamsung/          # 无 Samsung AAR 时的兼容实现
docs/                       # 接入规范
```

## 当前边界

- 壳不合并各业务项目代码，只承载和调度网页入口。
- 各模块继续负责自己的登录、业务路由和发布。
- 原生能力必须声明在模块清单中，并在 Android 端有明确的权限边界。
- 当前不包含 Agent、统一账号或后台服务运维能力。
