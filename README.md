# Shadow App

Shadow App 是 Shadow 系列的 Android 统一入口。它不合并各项目业务代码，而是在一个安全、
一致的移动壳中承载独立 Web 应用，并为确有必要的场景提供受限原生能力。

## 理念

- 一个入口，多个边界清晰的个人应用；
- 业务和数据仍归各领域项目，壳只负责发现、导航和设备适配；
- 应用地址由受审查的 Platform Catalog 投影提供，不让用户手工填写；
- 原生桥按模块与可信 URL 双重授权，默认不给普通网页设备权限。

## 主要功能

- Garden、Health、Ledger、Foliant、Travel、Archive 应用入口；
- Platform Notifications 部署后的统一收件箱入口；
- 统一工具栏、WebView 会话、文件上传下载和外链处理；
- 模块级规范入口/备用入口切换；
- Health 体脂秤、三星健康、提醒与离线队列适配；
- Android 16 与三星设备系统栏适配。

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
