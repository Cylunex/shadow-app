# Shadow App

Shadow App 是 Shadow 系列服务的独立 Android 壳。它接入 Shadow Platform Catalog 与
Identity，在同一个安全 WebView 容器里打开各个独立部署的 Web 应用。

首版内置：

- 健康：`/shealth/`
- 股票：`/stock/`

Android 包名为 `com.shadow.app`，应用显示名为 `Shadow`，最低支持 Android 10（API 29）。

## 功能

- 原生应用中心和统一的返回、主页、刷新、设置导航
- Platform Catalog 规范入口与 NAS DNS 别名自动路由和故障切换
- Platform Identity OIDC / Forward Auth 登录跳转与 Cookie 持久化
- Web 登录 Cookie 持久化
- 网页文件上传、下载和外部链接处理
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
4. 从模板创建不入库的 Platform 部署配置：

   ```bash
   cp config/platform.local.properties.example config/platform.local.properties
   ```

   把示例 Identity、健康、股票和 NAS URL 替换为当前环境地址。也可以使用模板中列出的
   `SHADOW_*` 环境变量，供 CI 或其他构建机注入。

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

## Platform 接入

应用地址不再由用户输入。`config/modules.template.json` 是可提交的脱敏 Platform App
Catalog 模板；真实部署地址只保存在被 Git 忽略的
`config/platform.local.properties`，构建时生成 APK 内使用的 `modules.json` 和网络安全配置。
清单声明：

- Platform Identity issuer；
- 每个应用的 HTTPS 规范入口；
- NAS 等 DNS 别名；
- OIDC/Forward Auth 模式和准入组；
- 无需登录的健康检查路径；
- 移动端展示与原生能力允许列表。

壳默认打开规范入口，并按模块记忆可用别名；入口失败时只切换当前模块。设置页仅保留健康
同步 Token、体脂秤、三星健康和提醒，不再出现服务器地址。完整约定见
[模块接入规范](docs/module-integration.md)。

## 目录

```text
app/src/main/
├── assets/                 # 应用中心与错误页
├── java/com/shadow/app/
│   ├── MainActivity.java   # 通用 WebView 壳
│   ├── core/               # Platform Catalog 投影与入口选择
│   └── health/             # BLE 与健康原生适配
├── kotlin/.../health/      # 健康提醒
app/src/samsung/            # 有 Samsung AAR 时编译
app/src/noSamsung/          # 无 Samsung AAR 时的兼容实现
docs/                       # 接入规范
config/                     # Catalog 模板与本地部署配置示例
```

## 当前边界

- 壳不合并各业务项目代码，只承载和调度网页入口。
- 各模块继续负责自己的登录、业务路由和发布。
- 原生能力必须声明在模块清单中，并在 Android 端有明确的权限边界。
- 壳接入 Platform Identity，但不保存 OIDC Token，也不承载 Agent 或后台服务运维能力。
