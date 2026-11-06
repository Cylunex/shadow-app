# Shadow

连接 Losna / DSH 的个人 Bot，陪你聊天、记住共同经历，也一起把事情做好。

首页展示伙伴、最近消息和在办事项，进入联系人即可聊天。支持添加伙伴、编辑人格卡、查看事项计划、继续或取消执行、阅读文本成果和共享记忆，也可从 Android 分享文字到聊天草稿。已有 Losna / doll 身份继续沿用，服务端统一承接对话、工作与记忆。

## 开发

Android Kotlin / Compose；JDK 17、Android SDK 36。`app` 提供原生界面和本地加密存储，`bot-core` 提供独立可测的协议与同步逻辑。

```sh
./gradlew :bot-core:test :app:compileDebugKotlin :app:lintDebug
```

在连接设置中填写 DSH 根地址（含代理前缀）和当前启动口令。实际地址、凭据、签名资料不写入仓库。

## 文档

- [初期方案与实现顺序](docs/initial-bot-plan.md)
- [Bot / Agent 事项与成果迭代](docs/bot-agent-iteration.md)
- [协议与开发验证](docs/protocol-and-testing.md)
- [本轮交付与未完成项](docs/implementation-status.md)
