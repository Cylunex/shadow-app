# 手机设备会话、实时通知与伙伴音色

2026-11-06，App 0.17.0（27），接续 `df2d135`。完整服务端协议、参考依据与取舍见 [Losna 实施记录](https://github.com/Cylunex/shadow-losna/blob/main/docs/execution/bot-mobile-sessions-push-voice-20261106.md)。

## 本批行为

- 服务端开启设备会话后，用户下一次主动连接会取得独立、可撤销的 Bot 凭证。手机不保留可自动回退的旧 Cookie／OIDC authState；到期或撤销后重新登录，草稿和待发记录保留。设置里可查看各设备并撤销单台。未启用的旧服务继续原登录方式。
- 实时通知通过官方 UnifiedPush connector 3.3.5 接入用户选择的分发器。只接受库已解密的 `wake`，通过 WorkManager 取回真实消息；手机权限、端点登记、网络重试、注销和账号切换有各自状态。后台周期检查可同时保留，二者共用通知账本与已读去重。无需假定 Google 服务可用。
- 已发送回复菜单增加伙伴音色播放／接续与跨设备停止。服务端沿用原 Speech 提供方，客户端校验原消息、设备所有权、Host／epoch／修订和音频 SHA-256。内存播放器在输入、导航、后台、失去焦点、失联或接手冲突时停止并释放。

设备会话有效期默认 30 天，是独立 Bot 凭证；上游统一账号／浏览器登录不随之撤销。跨设备音频接续限同一缓存的有效期（10 分钟），服务端重启或缓存失效后用户重新播放会从头开始。其他设备停止有在线检测延迟。停止声音不取消业务事项，播放位置不等于用户已听见。

## 参考

采用 [UnifiedPush 官方连接库](https://unifiedpush.org/kdoc/connector/) 的注册与解密生命周期；服务端使用 [Web Push 标准](https://www.rfc-editor.org/rfc/rfc8030.html) 和 [web-push](https://github.com/web-push-libs/web-push)。对照 [FCM Android 要求](https://firebase.google.com/docs/cloud-messaging/android/get-started) 后，没有默认引入 Firebase 项目。设备撤销语义对照 [RFC 7009](https://www.rfc-editor.org/rfc/rfc7009.html)，明确区别于 IdP 令牌撤销。Android 播放资源释放按 [MediaPlayer 文档](https://developer.android.com/media/platform/mediaplayer/state-resources)，一次性后台读取按 [WorkManager 文档](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)。

## 实现与验证入口

核心协议在 `bot-core/Mobile.kt`、`ProductApi.kt`、StateCodec 7；Android 入口为 `MobileSettings`、`RealtimePush`、`CompanionPlayback`。设备秘密、推送 capability URL 和 auth 使用现有加密私有存储，官方库数据库与偏好也明确排除备份／迁移。音频只在内存中随机读取，释放时清零。

`LosnaIntegrationTest.realMobileDeviceGrantPlaybackTakeoverAndRevocation` 使用隔离真实 HTTP 服务和合成账号／音频，覆盖两个独立设备令牌、禁止派生登记、原音频下载校验、跨端接续、旧版本拒绝与单设备撤销。Android 测试覆盖音频清零、播放身份变化、账号／分发器回调范围和 320dp 设备列表；沿用原全部功能回归。测试数量与最终结果见[实现状态](implementation-status.md)。

服务端 `78a6115` 已按用户要求部署，独立设备会话及伙伴音色已开启；公网凭证登记／撤销和原语音提供方真实 MP3 合成验证通过，生产 OIDC 仍关闭。用户选择暂不启用实时推送，保留定期提醒。未构建／安装 APK，手机仍为 0.12.0（22）；真实分发器到达率、系统省电、两台真机播放与实际 OIDC 仍待后续发布验收。源码默认配置继续关闭这些可选能力。
