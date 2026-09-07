# App 接入 Nexus 的详细设计

设计版本：2026-09-07 / UA-1。状态：目标设计，尚未实现。公共身份、鉴权、Agent、模型、命令与回执以 [Platform 统一规范](https://github.com/Cylunex/shadow-platform/blob/main/docs/nexus-unified-access-design.md) 为准；本文仅定义本领域差异。旧接口安全限制在对应能力通过迁移验收前继续生效。

## 1. 当前基线与范围

基线 `e0b3bd0`。App 已使用 Platform Catalog/Runtime v5、Nexus 首页、Origin/module/document nonce 原生桥、Keystore 加密 Nexus 队列和 Health 开秤 attempt。它是终端与设备适配，不成为新的身份服务器、Agent loop 或领域凭据管理器。

Platform 统一登录、Session、device 注册/撤销、能力目录和原生 auth client；App 保留 WebView 生命周期、受信导航、原生权限、BLE/设备采集、离线持久化和用户当前操作回执。

## 2. WebView 和原生身份

各域页面通过 Platform SDK + host-only app Session 自动使用同一 Identity SSO；App 不复制 Cookie 到别的 Origin、不从代理头读取 owner、不在前端保存用户 OIDC Token。用户当前中央 user_id 由受信桥接握手/已验证后端响应绑定，不能由网页参数自报。

原生设备注册通过中央受限流程生成 device principal 和单设备凭据，首次关联 user/用途时原位授权；App Keystore 保存设备专属材料。后台同步只获得 health ingest 等明确 audience/capability 的短期票据；不共享安装包内统一长效 Token，不借 WebView Cookie 当机器认证。

清单只含 auth SDK/合同版本、app/module ID、允许 Origin/原生能力和公开配置，秘密不进入 APK。若中央暂无原生注册/票据能力，旧设备链路仅维持明确 legacy 模式，不伪称已完成新认证。

## 3. 桥接合同

| 调用 | 输入 | 返回与限制 |
| --- | --- | --- |
| `auth.sessionInfo` | 当前 module/document context | 当前 user ref/auth epoch 的最小状态，无 Token |
| `operations.offline.enqueue` | command ID、domain/instance/action、类型化字段、effective date/timezone | queued ref；校验与当前 user/能力绑定 |
| `operations.offline.list/status` | 当前 user namespace | 自己的队列和领域终态；无跨账号读取 |
| `operations.offline.complete` | command ID + 可验证领域结果关联 | 仅 committed/最终失败处理后变更状态；不能网页随便删除未知项 |
| `health.scale.start/status` | 当前 module/一次性请求/attempt | started/capturing/uploading/parsed/duplicate 等阶段 |
| `web.openModule` | 已编译 module ID、受限相对路径 | 只开已登记入口，不执行任意 URL |

名称为拟议扩展，不能直接认为原桥已经提供。Platform 维护桥 Schema 与 TS/Android fixture，App 执行本机 nonce、主 frame、Origin、模块、capability、request generation 检查；Nexus 页面不能越权调用设备权限。

当前 Web 入队的字段与 `NexusNative.enqueueAction()` 接受字段需全链核验；统一命令 envelope 避免两端独立定义 sessionId/id，不能只各测自己的解析器。

## 4. 离线操作

队列按 user_id + device_id + domain_instance 分区，存 command/group/item ID、schema/contract version、原发生日期/时区、created_at、参数 hash 和加密 payload。Nexus/领域重放沿用同 command，不把原生 offline ID 换成新的服务端随机 ID。

恢复网络先确认当前账号和 auth epoch，再申请新短时票据，查询领域状态，确认未完成才按同键执行。queued 表示仅保存到手机，accepted 表示领域任务已受理，committed 才表示事实成功；网络恢复提醒本身不算业务执行。

跨日不重新计算“今天”；权限撤回不提交旧队列；账号切换停止旧 namespace worker 并清内存数据，保留加密待办供原用户处理。旧未记录 owner 的队列不能自动归给当前登录用户，迁移为隔离待核对项，不能靠用户名或最近登录猜归属。

高影响动作不离线自动提交，不保存过期确认；普通设备事实可在没有网络鉴权时先本地采集/排队，但服务端写入仍必须通过中央权限。重试期长于领域幂等有效期时先人工可见核对，不能换新键重发。

## 5. Health 生命周期

每次开秤 attempt UUID 贯穿扫描、解析、上传、入队和读回；request generation 控制旧异步结果失效。用户看到采集成功不等于 Health parsed，重复记录也应返回实际原记录关联，不能擦除 first_attempt_id。

OS 权限与中央授权独立：系统不给蓝牙则提示本机权限，中央 device 被撤销则停止上传；不把缺服务端数据一律说蓝牙故障。旧 APK 或网关不带 attempt 时只显示可证实的时间窗信息。

## 6. 迁移和验收

先引入 Platform 原生 SDK/桥 fixture，绑定当前 user/epoch；再贯通队列命令 ID 与 Health 结果；中央 device 注册/短票据可用后切对应路由，最终停止维护各域 token 设置/重复登录设置。

验收：Bridge 跨 Origin/frame/module/nonce 拒绝，不能向 JS 返回票据；真实断网重启/跨日补发；同 command 双击一条；切账号旧 worker/回调失效；Access 故障队列不丢；accepted 不删除；过期合同不盲重放；旧队列隔离；设备撤销与 BLE lifecycle。单元测试之外需真实 APK/设备验收，构建 APK 和部署必须另有用户明确要求。
