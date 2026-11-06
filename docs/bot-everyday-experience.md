# Bot 日常体验：历史、恢复、交付与提醒

2026-11-06。用户要求继续按设计实现 Bot，Life、Media 等项目接入后置。代码版本为 0.8.0（18）；本轮只开发、验证和提交，不部署、不构建 APK。原 0.7.x 发布记录不代表这批功能已装到手机。

## 逐项参考与取舍

| 部分 | 一手参考 | 本轮采用 |
| --- | --- | --- |
| 历史阅读与检索 | [Telegram 搜索过滤](https://telegram.org/blog/filters-anonymous-admins-comments) | 在当前聊天内搜索全部有效历史，分页读取，日期与原文一起展示。先不复制复杂分类筛选。 |
| 草稿与归档 | [Telegram Drafts](https://telegram.org/blog/drafts)、[Archive](https://telegram.org/blog/folders) | 输入即时保存，首页提示草稿；归档与恢复可直接操作，聊天、人格与事项保留。草稿先落手机加密存储，不宣称已有云端草稿同步。 |
| 成果交付 | [Grok Bot 文件与成果](https://docs.x.ai/grok-bot/files-and-results)、[Muse 产品设计](https://muse.ai/blog/how-we-designed-muse) | 成果留在原事项，文本可读，文件可保存／分享；工作在服务端继续。外部成果链接暂不作为下载文件处理。 |
| 文件选择与分享 | [Android Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files) | 系统选择保存位置，分享使用仅限成果目录的 FileProvider，不申请整个存储访问权限。 |
| 记忆修改与来源 | Losna [手机方案 §6](https://github.com/Cylunex/shadow-losna/blob/main/docs/mobile-personal-agent-design.md)与既有 Memory / 来源链实现 | 逐条展开来源、更正、少提、遗忘；更正直接保存，遗忘在当前卡片说明关联影响并确认，不建审核中心。 |
| 生成预览 | 已锁定的 DSH `llm/stream` 一手类型和真实 Agent 循环 | 仅投影 `text-delta`，不展示 reasoning 或工具参数；临时内容不成为正式消息与记忆。 |
| 基础语音 | [Android RecognizerIntent](https://developer.android.com/reference/android/speech/RecognizerIntent)、[TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech) | 系统识别先进入草稿，回复按需朗读；音频焦点变化、输入、离开页面或后台时停播。服务端伙伴音色与跨设备续播未接入。 |
| 后台提醒 | [Android WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)、[通知权限](https://developer.android.com/develop/ui/compose/notifications/notification-permission) | 用户开启后定期补同步，只提醒新回复／事项变化；通知不含私密正文。周期最短 15 分钟且可被系统延迟，不冒充实时推送。 |

## 实现契约

### 历史与生成

- `GET history` 按 `entryId`、搜索词和稳定消息位置分页；时间相同的消息也按角色和 ID 保持一致排序，不用易漂移的 offset。
- 游标绑定入口、搜索词与 memory epoch。遗忘／更正后旧游标拒绝；客户端清除历史和搜索结果后重新读取。新消息到来保留已加载历史的边界，不漏掉从最近 200 条窗口滑出的消息。
- 搜索结果显示完整的匹配消息，支持继续加载与选中文字；0.8.0 当时未提供上下文定位，后续 0.10.0 已补齐，见[历史定位设计](bot-history-context.md)。
- DSH 通过可释放的订阅提供当前生成文本；服务端只保留每个入口的临时预览。入口不匹配、来源失效、上下文修订、工具调用、取消或结束时隐藏／撤回。
- 具备 `liveReplies` 时手机在前台每秒读取当前预览与事件；其他前台场景仍每 4 秒检查。这里是生成文本的定期投影，不是 WebSocket／SSE 推送；正式回复仍等原 outbox 落盘。

### 恢复与存储

- 状态格式 4 兼容 1–3，加入文字、事项补充和附件草稿。主状态与原件均用 Android Keystore / AES-GCM，原子写入且不参加系统备份。
- 草稿进入待发队列时，在同一次磁盘提交中消费对应草稿；丢回执或进程终止后不会又恢复一份可重复发送的副本。不影响另一伙伴的草稿。
- 附件读取完成后先保存加密原件，再上传；失败／重启后由用户沿原 ID 重试，不重新读取可能已变化的来源文件。临时 URI 不写入状态文件。读取尚未完成就退出的附件提示重新选择，不能声称任意时刻杀进程都已保存完整原件。
- 最近一次打开的聊天另存最多 200 条加密离线副本，包含联系人列表，不缓存文件内容、事项详情或记忆详情。离线启动明确标注；重连检查 memory epoch，授权失败、换服务或断开连接时清理缓存。
- 用户保存或分享出去的原件属于已导出的文件，不能远程撤回；应用分享缓存限制在独立目录，24 小时以上的缓存在下一次导出时清理，换服务／断开时也清理。

### 联系人与记忆

- 归档是原服务端联系人上的展示属性，有版本检查；不删除联系人，不取消事项，不改变人格或权限。App 首页可恢复，后台检查跳过归档联系人。
- `memory.correct / memory.quiet / memory.forget` 使用原 command ID、generation 与目标版本，沿同一持久队列恢复，不自动换版本重放。
- 更正保留原记忆种类、虚构标记、有效期和别名，由原 Memory 替代旧事实；少提允许明确回查；遗忘沿原来源链失效派生内容。手机没有第二个记忆库。
- 来源详情最多展示 20 个来源，每个前 4000 字；有效记忆列表仍最多 100 条。

### 成果、语音与通知

- 原件下载先校验所属入口、工作可见性、长度与 SHA-256，读完再检查来源；支持空文件，最多 20 MiB。手机保存前或分享前重新请求，拒绝已撤回文件。
- 语音识别结果绑定发起时的服务和联系人，回到另一个聊天时不误填。朗读按句／长度分块，不截断 Unicode 代理对，音频焦点丢失即停止。基础语音依赖手机已有系统服务。
- 后台检查默认关闭，开启时申请系统通知权限。首次检查只建立基线；自己发送的消息、已读回复、已看过的事项版本与重复检查不重复提醒。断开连接取消检查和通知；通知点击回到原联系人。
- 后台定期检查并不保证锁屏即时到达，也不能绕过强制停止／省电策略。实时推送仍需选择并接入可用渠道。

## 后续增量

0.9.0 已补齐人格卡复制／移除与部分 JSON 文件交换，支持边界见[人格卡文件说明](bot-persona-files.md)。Losna 升级至 DSH 0.2.2-rc.1 后，临时正文改订阅原生 `agent/assistant-stream` 帧，仍只展示 text-delta；下面验证数字与未完成清单是 0.8.0 当时状态。

0.12.0 已补齐共享记忆检索、类型筛选和分页，见[记忆检索](bot-memory-browser.md)。最近 100 条的限制仅适用于旧服务；下述早期范围保留为历史。

0.11.0 已实现可选统一 OIDC 与自动续期；生产仍待配置与真实登录验收，见[统一登录](bot-unified-login.md)。

## 验证与未完成范围

- Kotlin 核心与实际 Losna HTTP／Life CLI 合成集成共 25 项通过；Android 单元／布局 14 项通过，Kotlin 编译与 Debug Lint 通过。
- Losna `npm run check` 与全量 691 项测试通过；后续 Product / DSH / 临时预览 30 项针对性复核通过。
- 新用例覆盖分页同时间排序、入口／搜索词隔离、游标失效、历史窗口连续、草稿和队列原子交接、离线撤回、归档版本、记忆更正与重放、成果下载与撤回、真实 DSH 文本片段、通知已读抑制、Unicode 朗读分块。
- 320dp 记忆操作与聊天、393dp 搜索／归档／成果／附件等原生合成截图已检查。截图使用合成资料，不代表新包已安装。
- 尚待真实设备验收：系统识别与 TTS 服务、来电／蓝牙焦点、系统保存与分享完整流程、后台通知权限与省电策略、进程退出后的 Keystore 原件恢复，以及真实模型连续对话。
- 后续状态：移动端 OIDC 长期授权／续期代码已由 0.11.0 实现，生产仍关闭；设备撤销、真正的后台实时推送仍未实现、复杂人设导入与联系人永久删除、伙伴音色与跨设备播放接续。前两项需要宿主认证与可用推送渠道的接入；当前生产登录继续沿用 DSH Cookie。
- 旁支话题、独立角色执行权限、电脑设备桥和 Life／Media 等业务整合继续按后续范围推进。
