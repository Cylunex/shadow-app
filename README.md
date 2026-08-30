# Shadow App

Shadow App 是 Shadow 的 Android 原生运行壳。它默认把 Nexus 作为完整产品首页，而不是先展示
一层应用入口；各领域仍拥有数据、规则和写入接口，App 负责统一会话、生命周期、分享入口与
受限设备能力。

## 理念

- 一个连续的 Nexus 工作台，多个边界清晰的领域底座；
- 业务和数据仍归各领域项目，Nexus 聚合信息与动作，壳负责交付、导航和设备适配；
- 应用地址由受审查的 Platform Catalog 投影提供，不让用户手工填写；
- 原生桥按模块与可信 URL 双重授权，默认不给普通网页设备权限。

## 主要功能

- 启动后直接进入 Nexus 统一工作台；应用中心保留为设置和故障降级入口；
- Nexus 数据面板聚合领域指标，并在首页直接呈现领域声明的高频动作；
- Health 称重、睡眠、心情和 Ledger 支出、收入可在 Nexus 内完成，不必先进入领域页面；
- Platform Notifications 部署后的统一收件箱入口；
- 统一 WebView 会话、文件上传下载和外链处理；Nexus 页面接管完整产品导航，领域页面仍显示
  原生返回工具栏；
- 模块级规范入口/备用入口切换；
- Health 体脂秤、三星健康、提醒与离线队列适配；
- Android 16 与三星设备系统栏适配。
- Android 系统分享文本、链接和单个文件到 Nexus；只预填当前会话，用户确认后才进入处理流程。

## 本地开发

需要 JDK 17 和 Android SDK。正式开发使用 Platform 编译后的 App 投影；旧版逐模块模板仅供
兼容：

```bash
SHADOW_APP_RUNTIME_FILE=/path/to/shadow-app-runtime.json \
  JAVA_HOME=/path/to/jdk17 ./gradlew --no-daemon validateModules testDebugUnitTest
```

实际域名、端口和签名材料只放在被忽略的本地配置或仓库外运维目录。

## 文档

- [模块接入规范](docs/module-integration.md)
- [品牌规范](docs/brand.md)
- [模块清单 Schema](docs/module.schema.json)
