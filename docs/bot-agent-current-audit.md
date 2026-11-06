# Bot / Agent 当前缺口与证据索引

2026-11-06，接续基线 App `0eb9cdb`、Losna `3157823`。本轮源码为 App 0.16.0（26），手机安装基线仍为 0.12.0（22）。本文覆盖现行 Bot、常驻 Agent、陪伴和共享记忆；不将历史超级 App 设想恢复成本期需求。

## 核对结果

| 设计范围 | 实现事实与代码入口 | 当前状态 |
| --- | --- | --- |
| 独立 Bot、联系人与人格分离 | App MainActivity / BotRepository；Losna Product / Personas；人格文件往返 | 已实现；不按角色复制 Agent 或领域权限 |
| 连续聊天、恢复、历史回查 | 待发编号、加密草稿／附件、history / message-context、搜索定位、LiveReplies | 已实现；真机系统分享、语音、后台省电和跨进程附件恢复仍需使用验收 |
| 共享记忆、来源和遗忘 | Memory 唯一权威、product-memory、搜索分页、逐条修改；成长手记引用真实原话／阅读依据 | 已实现；0.14 成长手记尚未部署 |
| 原工作、计划和成果 | Work / product-work / product-work-page；原事项接续、取消、保存分享 | 已实现；0.13 全量事项检索尚未部署；详情仍有执行／成果窗口 |
| 持续事项 | Continuity / product-commitments；目标、等待、关联 work、允许动作及终态约束 | 0.15 管理已实现；本批补齐完整集合搜索／分页，保持旧接口兼容；均未部署 |
| 人工浏览器接手 | Browser / Product / Web；原生 BrowserScreen 和瞬时状态 | 本批补齐原生入口并修正动态控件、入口／来源、未知结果和生命周期边界；未部署 |
| P0–P5 插件化与常驻工作 | Losna contracts/features、host、plugins，Work / Delivery / Continuity，执行记录 nas-plugin-p0-p1 至 p5 | 已有实现和隔离验证，非待从零建设；rc.5 等旧记录为历史，依赖当前锁定 0.2.2-rc.1 |
| 日常陪伴、轻量 RP、共同阅读与自主跟进 | companion-behavior、scenes、reading-views、page-watches、contacts、experience、methods | 已有实现；自然长期陪伴质量、具体网站全流程和长期提醒需真实运行证据 |
| 领域工具与外部插件 | Life / Foliant 等现有 skill + CLI/API，已有稳定插件和 DSH 原生装配 | 继续复用；本期不新增 MCP、第二循环或记忆库，不迁移生产配置 |
| OIDC、长期登录与设备控制 | AppAuth / RefreshingBearerTokens；Losna native-auth | OIDC／续期代码已实现，生产仍关闭；设备会话列表和单设备撤销尚未实现，不能用本机退出冒充远端撤销 |
| 后台通知 | App BackgroundChecks 的 WorkManager 周期检查 | 用户可选周期提醒已实现；真正实时推送尚未实现，需要选定可用推送通道、设备注册与失效生命周期 |
| 伙伴音色与跨端语音 | App DeviceSpeech；Losna Voice / Speech 已有服务端合成和 QQ 路径，Web 路径明确不走 Voice.desired | 系统输入／朗读已实现；Product 音频传输、播放回执与跨设备打断协同尚未实现，不能把 QQ 音色当作手机已接通 |
| Hermes 迁移 | hermes-migration、assistant-takeover 与实际运维回执 | 本次未接管职责或停用生产者；历史导入、来源归属与真实切换不能由合成测试替代 |

## 取舍与后续边界

本轮选择浏览器接手与持续事项完整检索，因为它们能补齐既有权威的实际手机路径，不需要新增外部服务或改变生产身份。先核对一手机制，再实现并测试；细节与采用／不采用理由见[本轮设计](bot-browser-handoff.md)。

仍有三组真实代码缺口：实时后台推送、设备会话及单设备撤销、服务端伙伴音色及跨端播放／打断。它们需要各自完整的服务契约和端到端验证，本轮没有以占位按钮、客户端随机 ID、定时轮询或本机停止播放冒充完成。生产启用还需要明确执行任务；代码缺口和待生产验收分别列出，不能互相替代。

旁支话题、设备桥、复杂人设格式／头像、联系人永久删除、内部子 Agent 协作和受控自动更新仍是明确后置。旧“今日”、多业务空间、新建 shadow-superapp、固定职业、角色权限沙箱和跨领域调度不属于现行范围。旧文档里的“尚未部署”只说明记录当时的状态；发布状态统一以 implementation-status 和仓库外运维回执为准。

没有运行 APK assemble、签名、安装、部署、生产模型测试或身份／通知／语音切换；本轮也没有新增后台自动化。
