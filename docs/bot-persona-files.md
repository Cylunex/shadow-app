# Bot 人格卡管理与文件交换

2026-11-06；源码版本 0.9.0（19），未部署、构建 APK 或安装。本轮衔接 0.8.0 日常体验与另一会话的长对话改进，继续只做 Bot，Life／Media 等领域接入后置。

## 参考与选择

- [SillyTavern 角色设计](https://docs.sillytavern.app/usage/core-concepts/characterdesign/)将名字、描述、性格、情境和表达示例分开。本轮支持 Character Card V2 JSON 的这些常用资料，保持轻量 RP；示例明确标记为“非真实历史”，情境标记为虚构，不生成开场白或导入世界书。
- [Character Card V2 定义](https://github.com/malfoyslastname/character-card-spec-v2)作为 JSON 字段依据。系统提示、post-history instructions、扩展、作者元数据和头像不迁入；不宣称完整 Tavern 兼容。
- [Android Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)负责选文件与保存位置，复用系统授权，不申请整个存储权限。文件只在手机解析，点击“保存人设”才通过已有 Product API 提交。
- 原 Losna Personas 是唯一人格权威；参考现有 [Web 鱼设交换](https://github.com/Cylunex/shadow-losna/blob/main/docs/fish-ecosystem.md)的“先进入可编辑卡片、保存生效”流程。本轮手机没有搬入鱼缸状态、账号文件或成长日志。

## 使用与协议

在聊天菜单打开“角色与人设”，可以新增、复制当前卡、移除非默认卡、切换、编辑并保存。卡片名称自动避让重名，最多 20 张；默认卡不能移除，移除当前卡后选回默认卡。所有卡片一起校验，不能带着另一个未选中卡片的空名字或重复名字提交。

导入支持 UTF-8 JSON，最大 256 KiB；超长或字段类型错误明确报错，不截断后静默保存。显示名字最多 100 个 UTF-16 单元，卡片名称最多 60，设定最多 12000。导入生成全新卡片 ID，保留原联系人、会话、主动聊天选项与 revision；选中新增卡供编辑，保存后才切换服务端人格。不会以外部文件中的 ID 覆盖已有卡片。

导出当前编辑中的单卡为 `shadow_persona` v1.0 JSON，仅包含 name、displayName、instructions、inheritIdentity；没有 entryId、revision、聊天记录、记忆、权限或登录资料。示例：

```json
{
  "spec": "shadow_persona",
  "spec_version": "1.0",
  "data": {
    "name": "自然交流",
    "displayName": "示例伙伴",
    "instructions": "简洁自然，保留自己的观点。",
    "inheritIdentity": false
  }
}
```

保留原 `POST persona` revision 检查。另一设备先保存后，本机旧版本保存会被拒绝，当前编辑仍可导出；“放弃修改并重新读取”读取最新版本，不自动覆盖远端。修改在 ViewModel 内保留，屏幕重建不丢；离开编辑页或进程终止会丢弃未保存修改。文件选择回调绑定原编辑页与服务，离开后返回的旧结果不写入另一个伙伴。文件内容不进入 Android saved state。

## 验证与限制

29 项 Kotlin 核心／合成 HTTP 集成和 15 项 Android 单元／布局通过，Kotlin 编译与 Debug Lint 通过。新增测试覆盖格式往返、错误编码／版本／字段、超限、重名与默认卡保护、跨端 revision 冲突、原会话保持。320dp 原生合成截图已检查，导入／导出、复制／移除和滚动后的保存按钮可达。未验证真机文件选择器与厂商系统行为。

本轮没有 PNG 内嵌角色卡、鱼设备份、SOUL.md、世界书、角色头像、联系人永久删除或独立权限。长期登录／设备撤销、真正后台实时推送、伙伴音色与跨设备播放仍未实现；后台提醒继续为 WorkManager 周期检查。人格卡不构成独立 Agent 权限。

服务端 DSH 同步升级至候选版 0.2.2-rc.1，详情见 [Losna 升级记录](https://github.com/Cylunex/shadow-losna/blob/main/docs/execution/dsh-022rc1-20261106.md)。依赖升级不代表生产服务已切换。
