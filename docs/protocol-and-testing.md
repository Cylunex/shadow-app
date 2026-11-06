# 协议与开发验证

## 工程

- JDK 17，Gradle 8.13，AGP 8.13.2，Kotlin / Compose 编译插件 2.2.21，SDK 36，minSdk 29。
- 固定现有缓存与本机其他 Android 项目已经采用的依赖组合；不在初期重建中追逐最新版本。
- `local.properties` 仅包含本机 SDK 路径，已忽略。运行地址由设置输入，无构建期真实地址、账号和凭据。
- `app` 使用系统 `org.json`；纯 JVM `bot-core` 测试使用固定版本依赖，避免把重复的平台 JSON 类打入应用。
- 保留应用 ID `com.shadow.app`、提升源码 versionCode。当前没有发布签名配置、APK 或覆盖安装验证；旧签名材料未删除，后续发布前再按既有约定配置。

## 已接入的 Product v1

设 DSH 根地址为 `https://example.com/dsh/`。登录请求为根地址加 `token` 参数，接收既有 DSH 签名 Cookie；不跟随跳转，不调用新账户接口。接口前缀为该根地址下 `losna/agent/api/`。

| 方法与路径 | 用途 |
| --- | --- |
| GET handshake | 协议版本、代次、能力、联系人与会话 ID |
| GET snapshot?entryId=… | 最近 200 条消息和真实事项状态；entryWorks 按入口先过滤再取最近 100 项 |
| GET inbox | 各伙伴最近可见消息与在办事项数量 |
| GET work-detail/{id}?entryId=… | 事项计划、结果、执行记录、成果与当前允许操作 |
| GET artifact-text/{id}?entryId=… | 原成果快照的只读文本预览 |
| GET events?after=… | 失效通知与游标；有变化后回读快照 |
| POST commands | 消息或 work.resume / work.cancel，稳定命令 ID 与 generation；工作命令附 workId / expectedVersion |
| POST roles | 新 Bot 联系人，客户端生成 `bot-UUID`；同 ID 同资料重试幂等 |
| GET persona?entryId=… | 现有 Personas 权威的人格卡与 revision |
| POST persona | 按 revision 保存人格；冲突刷新后处理，不盲目覆盖 |
| GET memories | 当前本人最近 100 条有效共享记忆，仅返回 id / kind / text |

所有写入使用 JSON 与 `X-Losna-Request: 1`。服务端继续经过既有 DSH admission / Host / Origin 检查。新角色仅保存 id 与名字，复用主模型配置，QQ 禁用；不生成凭据、职业模板、独立主脑或记忆库。人格卡独立于 entry，会话 ID 不随卡片切换而变化。

本机待发送队列最多 20 条，单条最多 4000 UTF-16 字符；写盘成功后才发请求。回执未知时保留原 id、entryId、action、text、workId 和 expectedVersion，不自动重放旧意图。409 保留原记录，核对后移除再提交新意图，不自动刷新版本重试。状态文件写入版本 2，兼容读取版本 1 的待发消息。换服务器或断开本机前先处理待发记录。内存草稿可跨旋转保留，未点击发送的草稿不持久化到系统 saved state；进程退出后不保证恢复。待确认消息和事项命令保存在加密文件。

Android SEND / PROCESS_TEXT 只接收最多 4000 字符的 text/plain，选择伙伴后追加到已有草稿，合并超长则提示，不截断、不自动发送。

401 停止轮询，重连保留待发命令；本机断开只清除本机会话，不声称吊销服务端 Cookie。新角色与人格的跨端变更在刷新／回前台时回读。前台每 4 秒检查事件，后台停止轮询；服务端 Agent 不依赖手机在线。

## 验证

```sh
./gradlew :bot-core:test :app:compileDebugKotlin :app:lintDebug
```

核心测试涵盖接收后丢回执、原 ID 重试、服务代次变化、游标失效、遗忘后快照替换、跨角色切换、持久化失败阻断发送、401 保留待发、Cookie 登录与不跟随重定向、协议参数与错误处理，以及工作版本冲突、遗忘后撤回详情和预览、旧状态迁移、首页摘要与分享草稿。

本机有 Losna 源码及其依赖时可执行跨仓库合成集成测试：

```sh
./gradlew :bot-core:test -PlosnaRepo=/path/to/shadow-losna
```

该测试启动只监听回环的临时 Node HTTP 服务，运行真实 Losna Product / Runtime / SQLite / Conversation，替换模型为合成回复；Kotlin HTTP 客户端实际创建角色、编辑人设、收发消息、重试、切换会话和重新连接，以及继续原工作、读取执行记录与实际保存的文本成果。临时状态退出时删除，不加载线上配置、不连接 QQ、不调用真实模型。它验证真实应用协议，不代表设备、反向代理或生产模型验收。

服务端修改还须在 Losna 运行 `npm run check` 与 `npm test`。所有上述命令不执行 APK assemble、签名、安装或生产部署。
