# Shadow

连接 Losna / DSH 的个人 Bot，陪你聊天、记住共同经历，也一起把事情做好。

首页展示伙伴、最近消息和在办事项，进入联系人即可聊天。支持添加伙伴、编辑人格卡、检索历史事项、查看计划、继续或取消执行、阅读文本成果和共享记忆，支持历史检索、草稿恢复、联系人归档、共享记忆检索与分类、来源与修改、成果保存和分享、系统语音，以及可选后台提醒。支持图片和文件附件，复用 Life 通用原件上传；也可从 Android 分享文字、图片或文件到聊天草稿。已有 Losna / doll 身份继续沿用，服务端统一承接对话、工作与记忆。

## 开发

Android Kotlin / Compose；JDK 17、Android SDK 36。`app` 提供原生界面和本地加密存储，`bot-core` 提供独立可测的协议与同步逻辑。

```sh
./gradlew :bot-core:test :app:testDebugUnitTest :app:compileDebugKotlin :app:lintDebug
```

在连接设置中填写 DSH 根地址（含代理前缀）和当前启动口令。实际地址、凭据、签名资料不写入仓库。

## 文档

- [Bot / Agent 缺口核对与事项检索](docs/bot-work-browser.md)

- [Bot 日常体验与逐项设计参考](docs/bot-everyday-experience.md)
- [历史搜索定位与前后文](docs/bot-history-context.md)
- [共享记忆检索与分页](docs/bot-memory-browser.md)
- [统一 OIDC 登录与接入边界](docs/bot-unified-login.md)

- [Bot 黑色界面与 Life 通用附件](docs/bot-dark-ui-and-attachments.md)

- [初期方案与实现顺序](docs/initial-bot-plan.md)
- [Bot / Agent 事项与成果迭代](docs/bot-agent-iteration.md)
- [协议与开发验证](docs/protocol-and-testing.md)
- [本轮交付与未完成项](docs/implementation-status.md)
