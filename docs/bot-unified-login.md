# Bot 统一 OIDC 登录

2026-11-06，源码 0.11.0（21）。可接入现有 Authelia，复用统一账号和浏览器的 SSO 登录状态；App 使用独立的原生客户端与 Losna 专属 audience。代码和合成验证已完成，生产配置、真实账号登录、部署和 APK 构建尚未执行。

## 参考与选择

- [RFC 8252 原生应用 OAuth](https://www.rfc-editor.org/rfc/rfc8252)：使用外部系统浏览器及 Authorization Code + PKCE，手机不保存客户端秘密，也不收集身份中心密码。
- [AppAuth Android](https://github.com/openid/AppAuth-Android)：复用 0.11.1 的浏览器回跳、随机 state／nonce、S256、授权码交换与 AuthState 续期。没有自写 OAuth 协议栈。
- [Authelia 客户端配置](https://www.authelia.com/configuration/identity-providers/openid-connect/clients/)：独立 public client，精确回调、专属 API audience、RS256 access token 与 offline_access。原生客户端无 client secret；不照搬平台早期网页机密客户端的配置。
- [jose](https://github.com/panva/jose)：服务端复用固定版本 6.2.15 校验 JWT 与远程 JWKS，不手工实现签名算法。
- 工作区 [Life 鉴权](https://github.com/Cylunex/shadow-life/blob/main/server/api/src/auth.ts)、[Media 原生登录](https://github.com/Cylunex/shadow-media/blob/main/core/library/src/main/kotlin/com/shadow/media/library/NovelLoginActivity.kt)和 [Novel 原生访问令牌](https://github.com/Cylunex/shadow-novel/blob/main/app/native_auth.py)：沿用访问令牌与 ID Token 分离、独立资源受众、稳定身份绑定与刷新竞争保护的做法。本批没有改变这些项目的运行代码。

## 用户流程

连接设置输入原服务根地址，点击“使用统一账号登录”，在系统浏览器完成登录后返回伙伴。已登录时显示账号名称与重新登录入口；原启动口令连接折叠保留。取消登录直接返回，原连接不变；服务未启用 OIDC 时明确说明可使用口令连接。

手机请求前由 AppAuth 检查令牌有效期，必要时续期；前台轮询、后台提醒、JSON 接口、附件和成果原件使用同一套鉴权。临时网络错误保留会话以供重试；永久续期失效要求重新登录，保留待发记录。接口已经返回 401 时不会自动重发写请求；命令沿原 ID 由既有恢复机制处理。

仍使用既有“断开本机连接”：清除本机凭据、缓存和登录尝试，不退出系统浏览器的统一账号，也不声称撤销身份中心的全部授权。远端授权撤销后，userinfo 校验会在成功身份缓存过期后拒绝请求，缓存最多 30 秒。设备会话列表、单设备远端撤销与全局 SSO 退出尚未实现。

## 会话与数据

加密状态版本 5 兼容版本 1–4。OIDC AuthState（含令牌）保存在原 Android Keystore / AES-GCM 原子文件，增加 issuer、subject、clientId 与随机登录实例 ID；不写入日志、Activity 返回结果或普通偏好设置。自有登录 Activity 只在 savedInstanceState 保存尝试 ID；AppAuth 自行管理其内部临时授权请求状态。待交换请求另存私有加密文件，用于核对 state、nonce、PKCE 请求、十分钟期限和一次性消费。

浏览器阶段可通过加密尝试恢复核对；授权码已进入交换阶段后若进程终止，用户重新登录，不重复交换已消费的授权码。配置发现、token 与 API HTTP 请求禁止自动跨地址重定向；发现文档的 issuer 必须精确一致，授权／token 端点必须为该 issuer 同源 HTTPS。

续期通过进程级互斥串行执行，先保存新凭据再使用；即使原页面取消，也完成已经开始的刷新提交。写盘失败不释放新 access token。写入时比对登录实例与当前会话，退出／换连接后的迟到响应不能恢复旧会话。草稿与待发队列使用原子更新，不被后台续期覆盖。

同 issuer／subject 的重新登录保留草稿和待发记录。旧 Cookie 可在同一服务升级到已绑定本人 OIDC 会话；已有 OIDC 会话不能携带待发记录降级为未标识账号的口令会话。换身份／服务且仍有待发时要求先处理原记录；没有待发时清理旧缓存与草稿。后台提醒校验稳定登录实例，不因正常 token 轮换误判为换账号。离线副本也绑定登录实例；旧 Repository 的迟到刷新与队列保存不能覆盖新登录状态，新会话不读取旧实例的离线正文。

## 服务端边界

- 公开 `GET losna/agent/api/auth/native/config` 仅返回 issuer、client ID、resource、回调与根地址，不返回 ownerSubject 或秘密。仍先经过 DSH Host／Origin 检查。
- `GET losna/agent/api/auth/me` 返回经过验证的稳定身份和展示名。只有 bearer 身份可调用，旧 Cookie 不伪造 OIDC 身份。
- bearer 仅准入 `/losna/agent/api/`。DSH 管理接口、RPC、WebSocket 和 Losna 管理页面仍使用原有认证；不创建 DSH Peer 或通用管理员登录。
- 继续使用 DSH 的 `requestRejection`：保留 403 的 Host／Origin 拒绝，只在 401 的未登录场景允许有效 OIDC bearer 进入 Product。DSH 版本升级后必须重新验证这一拒绝顺序。
- 校验签名、RS256、`typ=at+jwt`、iss、aud、sub、client_id、exp、iat、jti 和 openid scope；同时存在 scope／scp 时都必须包含 openid。ID Token、其他应用令牌与过期令牌不能用于 API。
- 固定 `(issuer, ownerSubject)` 映射到既有 `owner`，无首次登录自动认领，也不按可修改的用户名或邮箱授予权限。服务端 userinfo 的 sub 必须与签名令牌一致。
- 只缓存成功的身份摘要与 token 哈希，最多 128 项、30 秒；网络故障返回 503，授权失败返回 401／403。请求头带无效 bearer 时不能借同时携带的 Cookie 降级通过。

## 接入准备与验证

Losna 提供[私有配置结构示例](https://github.com/Cylunex/shadow-losna/blob/main/config/native-oidc.example.json)和 [Authelia 原生客户端示例](https://github.com/Cylunex/shadow-losna/blob/main/config/authelia-native.example.yml)。需要注册独立客户端、确定该客户端对应的本人 subject、核对原生回调与 audience，并将配置放到既有运维目录。不能复用 Life／Media 的 client ID、token 或固定业务身份。

现有客户端支持标准授权码交换，不支持强制 PAR／JARM；Authelia 实际版本、现有登录策略、刷新令牌期限与轮换策略应在部署时核对并运行其配置校验。回调使用 `com.shadow.app:/oauth2redirect`；尚未添加经域名关联验证的 HTTPS App Link。外部浏览器与 PKCE 已实现，回调冲突、MFA、进程重建和真实刷新仍需真机验收。

验证：41 项核心／HTTP 合成集成、22 项 Android 单元／布局通过，编译与 Debug Lint 通过；Losna 语法检查与全量 702 项通过。新增用例覆盖真实 RSA 签名验证、跨客户端／身份／受众拒绝、撤销缓存、管理接口隔离、并发刷新、取消后持久化、写盘失败、回调请求绑定、期限与兼容登录。320dp 登录截图已检查。合成身份验证不代表已完成真实 Authelia 登录或生产切换。
