# Shadow App 模块接入规范 v2

本文定义 Web 应用接入 Shadow App 的最小约定。目标是让新增模块只修改模块清单和反向代理配置，不修改通用 WebView 壳。

## 1. 部署模型

壳只配置两个稳定的服务环境：

```text
NAS    http://192.168.1.100
云端   https://shadow.example.com
```

模块清单只描述应用在各环境中的端口和目录。例如健康、股票仅部署在 NAS，Garden 仅部署在云端：

```json
{
  "id": "garden",
  "routes": [
    {
      "server": "cloud",
      "startPath": "/garden/",
      "probePath": "/garden/healthz"
    }
  ]
}
```

同一模块可以同时声明 `nas`、`cloud` 两条路由。壳会记住每个模块自己的活动路由，当前路由探活失败时只切换该模块，不影响其他应用。只声明一条路由的模块不会被错误地切到另一台服务器。

## 2. 模块清单

清单位于：

```text
app/src/main/assets/modules.json
```

顶层格式：

```json
{
  "schemaVersion": 2,
  "modules": []
}
```

单个模块字段：

| 字段 | 必填 | 说明 |
|---|---:|---|
| `id` | 是 | 稳定标识，格式 `[a-z][a-z0-9-]{1,31}`，发布后不得改名 |
| `name` | 是 | 应用中心显示名称 |
| `description` | 是 | 一行功能说明 |
| `routes` | 是 | 一至两条模块部署路由，同一 `server` 不得重复 |
| `icon` | 是 | 壳内置图标语义名，当前支持 `heart`、`chart`、`web` |
| `color` | 是 | `#RRGGBB` 主题色 |
| `enabled` | 是 | 是否在应用中心展示 |
| `capabilities` | 是 | 能力声明，仅声明实际需要的能力 |

单条 `routes` 路由字段：

| 字段 | 必填 | 说明 |
|---|---:|---|
| `server` | 是 | `nas` 或 `cloud` |
| `port` | 否 | 覆盖环境根地址端口，适合 NAS 上每个应用使用独立端口 |
| `startPath` | 是 | 入口目录，以 `/` 开头；目录入口保留尾斜杠 |
| `probePath` | 是（可空） | 无需登录、返回 HTTP 200 的通用探活路径；留空时只能打开，不能自动判断可达性 |

NAS 独立端口示例：

```json
{
  "server": "nas",
  "port": 55080,
  "startPath": "/shealth/",
  "probePath": "/shealth/healthz"
}
```

如果服务直接位于端口根目录，可把 `startPath` 写成 `/`、`probePath` 写成 `/healthz`。

当前能力名：

| 能力 | 含义 |
|---|---|
| `web` | 标准 WebView 页面 |
| `health.scale` | 允许健康模块调用体脂秤桥接 |
| `health.samsung` | 使用三星健康只读同步 |
| `notification` | 模块具有本地通知任务 |

## 3. Web 应用要求

模块必须满足：

1. 支持移动端 viewport 和触摸操作。
2. 支持部署在子路径，不能假设自己一定运行在 `/`。
3. 页面、静态文件、表单、重定向和异步请求都必须保留部署前缀。
4. `startPath` 必须可通过 GET 打开；未登录时应跳转到模块自己的登录页。
5. 登录 Cookie 应把 `Path` 收窄到模块前缀，避免泄漏给同域其他模块。
6. 导出文件应使用正确的 `Content-Disposition` 和 MIME 类型。
7. 外部网站、电话、邮件等链接由系统应用打开，不应依赖 WebView 内跳转。
8. 页面不能依赖壳注入的私密 Token；业务鉴权仍由模块自己的 Cookie/会话负责。

## 4. 反向代理约定

反向代理应保留客户端可见前缀，后端需要知道前缀时使用统一请求头：

```text
X-Forwarded-Prefix: /<module-id>
X-Forwarded-Proto: $scheme
X-Forwarded-Host: $host
```

目录入口应把无尾斜杠地址重定向到有尾斜杠地址，例如：

```text
/stock  -> /stock/
```

`probePath` 应快速、无副作用、无需业务登录，仅用于判断对应部署入口是否可达。该机制属于通用壳能力，与健康模块没有绑定关系。

## 5. 原生桥接约定

普通模块默认没有原生权限。新增原生能力时必须同时完成：

1. 在 `capabilities` 声明能力名。
2. 在独立 feature 包实现，不能把领域代码写进 `MainActivity`。
3. 调用前同时校验当前模块 ID、页面 URL 和可信 Origin/路径。
4. Android 敏感权限必须在用户主动开启功能时请求。
5. 服务端写入接口使用单独的受限 Token，并保证幂等。
6. 文档写清数据范围、后台频率、关闭方式和失败重试语义。

现有 `shadow-health` 页面使用历史契约：

```javascript
window.ShellBridge?.startScaleScan?.()
```

壳只在当前模块为 `health` 且页面位于健康模块路径时执行，其他页面调用会被忽略。

后续新增桥接应使用领域前缀，例如 `startVerseSession`，并保持相同的来源校验。不要提供通用文件、Shell、数据库或任意 HTTP 调用桥接。

## 6. 版本与兼容

- `schemaVersion` 只在清单结构不兼容时递增。
- v2 将原来的全局 `startPath`、`healthPath` 改为模块级 `routes`；旧版 `server_urls` 第一、第二项会分别作为 NAS 和云端地址读取，用户保存新设置后完成迁移。
- 模块 `id` 是持久标识，不跟显示名称变化。
- 新字段应优先采用可选字段和安全默认值。
- 删除模块前，应先发布不再展示该模块的 APP 版本。
- 服务端更新必须兼容至少一个已发布 APP 版本。

## 7. 接入验收清单

- [ ] 应用中心能打开模块入口
- [ ] 登录、退出和 Cookie Path 正确
- [ ] 所有静态文件和 API 请求保留子路径
- [ ] Android 返回键先返回网页历史，再回应用中心
- [ ] 上传、下载、确认对话框正常
- [ ] 外部链接交给系统应用
- [ ] 服务不可达时显示本地错误页并能重试
- [ ] 不声明原生能力时无法调用敏感能力
- [ ] 每条非空 `probePath` 都返回 HTTP 200
- [ ] 只部署在 NAS 或云端的模块不会被解析到另一环境
- [ ] 双路由模块故障切换时不影响其他模块的活动路由
- [ ] 与健康、股票等已有模块的 Cookie 和路由互不干扰
