# Shadow App 模块接入规范 v3

Shadow App 使用 Shadow Platform App Catalog 的移动端投影，不再让用户输入 NAS 地址、云端
域名、端口或子目录。应用入口、别名、认证方式和探活路径均由审查过的构建配置声明。

## 1. 边界

- Platform Catalog 是应用身份、规范入口、认证模式和健康检查的来源。
- Shadow App 只补充移动端展示和原生能力字段，不代理业务请求。
- 浏览器页面使用各应用自己的 OIDC/Forward Auth 会话；原生后台同步继续使用项目级 Bearer。
- Platform Identity 是 WebView 的受信导航目标，但不是业务模块，也不能调用原生桥接。
- 清单随 APK 发布，不在运行时下载未签名的远程目录，避免入口被远程篡改。
- 真实部署域名和端口只存在于被 Git 忽略的本地属性文件或 CI 环境变量中。

## 2. 清单结构

可提交的清单模板位于 `config/modules.template.json`，真实入口由不入库的
`config/platform.local.properties` 提供。Gradle 构建时将二者合成为生成目录中的
`assets/modules.json`：

```json
{
  "schemaVersion": 3,
  "platform": {
    "catalogVersion": 1,
    "identityIssuer": "https://auth.example.com"
  },
  "modules": []
}
```

模块同时包含 Platform 字段和移动端字段：

| 字段 | 来源 | 说明 |
|---|---|---|
| `id` | Platform | 稳定 `app_id` |
| `canonical_url` | Platform | 公网规范入口，必须为 HTTPS |
| `aliases` | Platform/部署 | NAS 等备用入口；首版允许局域网 HTTP |
| `auth.mode` | Platform | `oidc`、`forward-auth` 等认证模式 |
| `auth.groups` | Platform | 应用最小准入组 |
| `health_path` | Platform | 无需登录、返回 200 的探活路径 |
| `name`、`description` | Mobile | 应用中心文案 |
| `icon`、`color` | Mobile | 移动端展示 |
| `enabled` | Mobile | 是否展示 |
| `capabilities` | Mobile | 原生能力允许列表 |

`health_path` 相对每个入口解析。例如规范入口 `https://health.example.com/` 与别名
`http://nas.example.com/shealth/` 共用 `/healthz`，得到：

```text
https://health.example.com/healthz
http://nas.example.com/shealth/healthz
```

规范入口始终排在第一位。壳记住每个模块最后可用的入口；当前入口失败时只探测并切换该
模块的别名，不影响其他应用。旧版保存的 NAS/云端地址和 `nas`/`cloud` 活动路由会被忽略。

## 3. 登录与 WebView

WebView 允许以下来源留在壳内：

1. 所有模块规范入口与别名的 Origin；
2. `platform.identityIssuer` 的 Origin。

其他 HTTP(S) 链接交给系统浏览器。跳转到 Identity 时保留当前模块上下文和 Cookie，完成
OIDC/Forward Auth 后返回原模块。Identity 页面不能获得健康桥接权限；桥接仍需同时满足
当前模块 ID 与模块 URL。

## 4. 新应用接入

1. 先在 `shadow-platform/catalog/apps.yml` 登记稳定 `app_id`、规范入口、认证模式、组和
   `health_path`。
2. 完成 DNS、TLS、OIDC 回调和无需登录的 `/healthz`。
3. 在 `config/modules.template.json` 添加移动端文案、图标和能力声明，并在本地部署配置或
   CI 环境变量中提供已审查的生产入口。
4. 若有 NAS 别名，确保路径、静态资源、重定向和 Cookie Path 均保留内部前缀。
5. 运行 `./gradlew validateModules`，并分别验证公网和局域网探活。

不要把真实部署域名、Token、密码、OIDC client secret 或带 user-info 的 URL 写入 Git。
本地部署配置必须保持在 `.gitignore` 中；构建产物中的 URL 仍应视为公开信息。

## 5. 原生能力

普通 Web 模块默认只有 `web`。新增原生能力必须：

1. 在 `capabilities` 声明；
2. 在独立 feature 包实现；
3. 同时校验当前模块和可信 URL；
4. 在用户主动启用时请求 Android 权限；
5. 后台写入使用独立 Service Bearer，并保证幂等与审计。

当前能力包括 `web`、`health.scale`、`health.samsung`、`notification`、`map`、`media`、
`finance`、`inbox` 和 `operations`。其中能力字段只用于展示和原生桥授权，不等于业务 API
权限。

没有可用备用入口的模块可以把 `aliasUrl` 留空。尚未部署但已完成壳接入的模块应使用
`enabled=false`，这样清单仍会经过构建校验，但不会显示在应用中心。

## 6. 验收

- [ ] 首次启动不要求输入服务器地址
- [ ] 规范入口和每个别名的 `health_path` 返回 200
- [ ] OIDC/Forward Auth 在 WebView 内往返并回到原模块
- [ ] Identity 和其他模块不能调用 Health 原生桥接
- [ ] 公网入口不可达时可切换到 NAS 别名
- [ ] NAS 不可达时可恢复到规范入口
- [ ] 外部链接仍交给系统浏览器
- [ ] 清单不包含密钥、密码或私有 Token
