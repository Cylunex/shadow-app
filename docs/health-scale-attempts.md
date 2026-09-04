# 开秤会话与阶段反馈

Health Agent 工作流配套改动，2026-09-04。

- health.scale.start 仍接受空 payload，返回新 UUID attempt_id。
- health.scale.status 接受该 attempt_id，返回 stage 与 updated_at_ms；需要当前 Health 文档的 health.scale 能力、nonce 和主框架身份。
- ScaleAttempt 只保存最新会话 ID/阶段/时间，不存体重或凭据；旧回调不能覆盖新会话。
- 每条 Measurement 绑定采集时的 attempt，上传和离线队列携带该 ID，后续补发不会改成新 ID。
- 重新开秤会丢弃旧扫描回调（包括已经投递到主线程的回调），但旧测量仍可按原会话上传。
- 阶段包括请求、权限、扫描、采集、上传、重试、排队及服务端接收；服务端 HTTP 成功不等于标准化入库成功。
- 最终落账由 Health Web 查询服务端确认，手机不会伪造入库结果。

兼容性：旧 Health Web 忽略返回字段；旧 APK 仍可开秤，但新版 Web 只能显示时间窗关联。
完整的原生阶段反馈需要发布并安装本次客户端改动，当前没有增加新的 Android 权限。

验证：testDebugUnitTest 编译与 23 个 JVM 单测通过；未进行真机 BLE、省电、断网重试或 APK 发布。
发布前用真机验证连续两次开秤、扫描拒权、蓝牙关闭、联网/离线补发，以及旧会话不会覆盖新会话。
