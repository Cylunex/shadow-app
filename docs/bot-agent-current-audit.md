# Bot / Agent 当前缺口与证据索引

2026-11-06，接续基线 App `0eb9cdb`、Losna `3157823`。本轮源码为 App 0.17.0（27），手机安装基线仍为 0.12.0（22）。本文覆盖现行 Bot、常驻 Agent、陪伴和共享记忆；不将历史超级 App 设想恢复成本期需求。

## 核对结果

| 设计范围 | 实现事实与代码入口 | 当前状态 |
| --- | --- | --- |
| 独立 Bot、联系人与人格分离 | App MainActivity / BotRepository；Losna Product / Personas；人格文件往返 | 已实现；不按角色复制 Agent 或领域权限 |
| 连续聊天、恢复、历史回查 | 待发编号、加密草稿／附件、history / message-context、搜索定位、LiveReplies | 已实现；真机系统分享、语音、后台省电和跨进程附件恢复仍需使用验收 |
| 共享记忆、来源和遗忘 | Memory 唯一权威、product-memory、搜索分页、逐条修改；成长手记引用真实原话／阅读依据 | 服务端已部署；0.14 客户端尚未发布 |
| 原工作、计划和成果 | Work / product-work / product-work-page；原事项接续、取消、保存分享 | 服务端已部署；0.13 客户端尚未发布；详情仍有执行／成果窗口 |
| 持续事项 | Continuity / product-commitments；目标、等待、关联 work、允许动作及终态约束 | 0.15 管理已实现；本批补齐完整集合搜索／分页，保持旧接口兼容；服务端已部署，原生入口待新版 APK |
| 人工浏览器接手 | Browser / Product / Web；原生 BrowserScreen 和瞬时状态 | 本批补齐原生入口并修正动态控件、入口／来源、未知结果和生命周期边界；服务端已部署，原生入口待新版 APK |
| P0–P5 插件化与常驻工作 | Losna contracts/features、host、plugins，Work / Delivery / Continuity，执行记录 nas-plugin-p0-p1 至 p5 | 已有实现和隔离验证，非待从零建设；rc.5 等旧记录为历史，依赖当前锁定 0.2.2-rc.1 |
| 日常陪伴、轻量 RP、共同阅读与自主跟进 | companion-behavior、scenes、reading-views、page-watches、contacts、experience、methods | 已有实现；自然长期陪伴质量、具体网站全流程和长期提醒需真实运行证据 |
| 领域工具与外部插件 | Life / Foliant 等现有 skill + CLI/API，已有稳定插件和 DSH 原生装配 | 继续复用；本期不新增 MCP、第二循环或记忆库，不迁移生产配置 |
| OIDC、长期登录与设备控制 | AppAuth / RefreshingBearerTokens；Losna native-auth | OIDC／续期代码已实现，生产仍关闭；本批补齐独立 Bot 设备凭证、列表、单设备撤销与到期重连；区别于 IdP 全局注销；服务端已启用，原生入口待新版 APK |
| 后台通知 | App BackgroundChecks 的 WorkManager 周期检查 | 用户可选周期提醒已实现；本批补齐 UnifiedPush 官方连接库、Web Push 加密唤醒、端点版本／重试／失效和通知去重；用户选择暂不启用实时推送，保留定期提醒；真实分发器待验收 |
| 伙伴音色与跨端语音 | App DeviceSpeech；Losna Voice / Speech 已有服务端合成和 QQ 路径，Web 路径明确不走 Voice.desired | 系统输入／朗读已实现；本批补齐 Product 音频传输、播放位置回执、双设备接续／打断与来源撤回；服务端已启用并完成真实提供方合成检查；实体设备播放待验收 |
| Hermes 迁移 | hermes-migration、assistant-takeover 与实际运维回执 | 本次未接管职责或停用生产者；历史导入、来源归属与真实切换不能由合成测试替代 |

## 取舍与后续边界

浏览器接手与持续事项完整检索已在上一批实现；本批继续完成设备会话、实时推送与伙伴语音三组代码缺口。参考、契约和取舍见[本轮设计](bot-mobile-sessions-push-voice.md)。新能力默认关闭，旧服务与既有登录／通知／朗读路径兼容；服务端已按明确部署任务启用设备会话与伙伴音色，实时推送按用户要求关闭。

三组功能现已有完整代码路径和隔离测试；真实分发器、两台实体设备播放、省电限制、实际 OIDC 与真机音色效果仍待客户端发布后验收。合成协议通过不等于这些运行结果已经成立。后台推送仅唤醒读取；设备撤销只作用于 Bot 独立凭证；音频接续限定当前内存缓存与播放租期。

旁支话题、设备桥、复杂人设格式／头像、联系人永久删除、内部子 Agent 协作和受控自动更新仍是明确后置。旧“今日”、多业务空间、新建 shadow-superapp、固定职业、角色权限沙箱和跨领域调度不属于现行范围。旧文档里的“尚未部署”只说明记录当时的状态；发布状态统一以 implementation-status 和仓库外运维回执为准。

服务端 `78a6115` 已部署；未运行 APK assemble、签名或安装，未测试真实模型或新增后台自动化，手机仍为 0.12.0（22）。部署验收与限制见 implementation-status 和仓库外运维回执。
