# P4 定时宣传支持媒体附件（图片/视频/文件）

2026-09-16 P4 第二刀设计拷问锁定。在 ADR-0008 第一刀「文本-only」基础上，将原 P6 批次的图片/视频/文件发送能力并入 P4，但**改走企业群发 API 素材管理**（`media/upload` 换 `media_id` + `add_msg_template` 的 `attachments` 数组），**不再走 ADB 相册流程**。第一刀的状态机、隔离原则、dry-run 闸门、幂等约束全部沿用，本 ADR 只锁第二刀新增决策。

## Decision

P4 第二刀在第一刀链路上追加一步上传，所有改动向后兼容：

1. **单附件而非多附件**：复用 `scheduled_task.media_path` 单数列（第一刀已预留）。`add_msg_template` API 支持最多 9 个附件，但本店业务与 P5 管理端 UI 都只允许 1 个。不开 `task_attachment` 子表。
2. **media_id 不缓存，每次触发重传**：`media/upload` 返回的 media_id 3 天有效、企业内可跨应用复用，但本项目**不持久化** media_id——每次任务触发都重新上传。理由：每日 1-3 个任务 × 1-3 群，单次上传成本可忽略；缓存表会引入「过期检测、跨进程一致性、上传失败时缓存清理」等复杂度，违反最小实现原则。仅当业务方反馈 API 限额不够时才升级到任务级缓存（`scheduled_task.cached_media_id / cached_media_at`）。
3. **multipart 上传加在 ContactApiClient**：新增 `ContactApiClient.uploadMedia(File file, String type)` 方法，复用既有 access_token 缓存（与 `add_msg_template` 同源 token）。**不新建 MediaClient 类**——会造成 token 缓存割裂或重复拉取。JDK `HttpClient` 原生拼 `multipart/form-data` body（手动构造 boundary 分隔的字节流），**零新 jar**，符合本项目依赖约束。
4. **扩展名白名单 + 音频黑名单**：上传前在 `ApiMsgSender` 入口硬校验：
   - `image` = `.jpg/.jpeg/.png`（≤10MB）
   - `video` = `.mp4`（≤10MB）
   - 其余扩展名一律当 `file`（≤20MB，API 不限制 file 扩展名）
   - **音频扩展名**（`.amr/.mp3/.m4a/.aac/.wav/.flac`）显式 fatal：「add_msg_template 不支持语音附件，请改用文本或图片」——`media/upload` 虽支持 voice，但 `add_msg_template` 的 `attachments.msgtype` 不接受 voice。
   - 不通过 → `CreateResult.fatal`，直接 `failed_final`，不浪费 api_attempts。
5. **media/upload 失败复用 failed_api + api_attempts**：上传失败 99% 是网络/临时 token 问题（文件问题已在入口 fatal 拦截），与 `add_msg_template` 失败同列重试 30s/2min/10min，3 次后转 `failed_final`。**不新增 failed_upload 状态、不新增 upload_attempts 列**——为它单独开状态机会让状态机、ADR、测试场景、管理端展示都加一份复杂度，无收益。
6. **dry-run 时真上传**：与第一刀「dry-run 真建任务 + 真导航 + 不点发送」语义一致——dry-run 真调 `media/upload`（不占客户群接收配额，仅占企业临时素材存储空间，3 天自动过期）、真调 `add_msg_template`、真 ADB 导航、不点最后一步。这才能完整演练「带附件任务」链路；跳过上传会让 ADB 详情页无法看到附件预览。
7. **纯图任务 ADB 详情页校验跳过 epd 内容比对**：第一刀 `openPendingTaskAndVerify` 用 `textByRid(xml, "epd")` 前 20 字符前缀比对。当 `expectedText=null`（纯图任务，content 为空）时，跳过 epd 内容校验，只校验「待发送」状态（`rid=epi`=待发送）+ 发送按钮 `rid=cre` 可见。理由：业务侧每群每天只发 1 条（管理端表单约束），同日纯图任务不可能有 2 条，前 3 张卡片范围内取最新一张「待发送」即可。dry-run 可安全演练；若真机演练发现误点历史卡片，再升级到找图片节点 rid。

## 附件类型范围

第二刀支持 `attachments[].msgtype` = `image` / `video` / `file`。

**不支持**：`link`（超链接卡片）、`miniprogram`（小程序卡片）——这两类不需要 `media/upload`，但需要业务方手填 url/appid/page 字段，属于另一类「卡片任务」而非「媒体文件任务」。P3 已经用 LLM 提示词处理购买引导链接（白名单），P4 定时宣传无需复用。**留作未来扩展，不在本刀引入。**

## 文件类型 → msgtype 映射

| 扩展名 | msgtype | API type 参数 | 大小上限 |
|---|---|---|---|
| .jpg/.jpeg/.png | image | image | 10MB |
| .mp4 | video | video | 10MB |
| .amr/.mp3/.m4a/.aac/.wav/.flac | — | — | fatal 拒绝 |
| 其余 | file | file | 20MB |

## Alternatives Considered

- **多附件子表（最多 9 个）**：API 上限 9 个，但本店场景一张图/一份文件足够；多附件需要 `task_attachment` 子表、P5 前端多上传控件、attachments 数组顺序管理。否决——过度设计。
- **media_id DB 缓存（3 天复用）**：能省 1 次 API 调用，但要新建 `media_cache` 表、过期判断、跨进程一致性、上传失败时缓存清理。否决——单次 API 成本可忽略，复杂度收益比差。
- **新建 MediaClient 类**：把 multipart 能力独立。否决——token 由 `contact.secret` 换取，复用同一 ContactApiClient 的 access_token 缓存是关键；新建类会造成 token 缓存割裂或重复拉取。
- **新增 failed_upload 状态 + upload_attempts 计数**：让上传失败独立计数。否决——上传失败与 `add_msg_template` 失败都是 API 阶段失败，复用 `api_attempts` + 30s/2min/10min 退避即可；新增状态机分支会让状态机、ADR、测试场景、管理端展示都加一份复杂度，无收益。
- **dry-run 时跳过上传 + 构造假 media_id**：dry-run 不调 `media/upload`，传 `DRY_RUN_PLACEHOLDER`。否决——`add_msg_template` 会拒绝，整条链路无法演练；dry-run 的语义就是「真实链路 + 不点最后一步」。
- **纯图任务强制要求 1 字文本**：让 `epd` 始终有内容可比对。否决——Q4 已锁纯图是合理业务场景（新品图无文案），强制加字是扭曲业务满足技术约束，方向错。
- **link / miniprogram 附件同刀引入**：与 image/video/file 一起支持。否决——这两类不需要 `media/upload`，需要业务方手填卡片字段，属于另一类任务；P3 LLM 已处理购买引导链接，P4 定时宣传无需复用；留作未来扩展。

## Consequences

- 优势：第一刀的状态机、隔离、dry-run、幂等全部沿用，第二刀只在 `createTemplate` 入口前置一步上传 + 一个 attachments 字段；schema 零改动（`media_path` 已在第一刀预留）；零新 jar（JDK HttpClient 拼 multipart）。
- 代价：`ContactApiClient` 从纯 JSON 客户端变成混合 JSON + multipart 客户端，但 multipart 集中在一个方法内，不污染既有方法；纯图任务的 ADB 校验弱化（跳过 epd 比对），需要靠「每群每天 1 条」业务约束兜底，若未来该约束放松需升级到找图片节点 rid。
- 影响：
  - ADR-0008 末尾加一句「第二刀见 ADR-0009」；
  - `batched-development-plan.md` P4 段加「第二刀：媒体附件」子节；
  - `CONTEXT.md` 加 4 条词条（定时宣传任务、媒体附件、媒体 ID、群发助手）；
  - `scheduled_task` 表零改动（`media_path` 列已存在）；`task_run` 表零改动；
  - 代码改动：`ContactApiClient.uploadMedia` 新增、`ApiMsgSender.createTemplate` 加 `mediaPath` 参数、`WeChatUi.openPendingTaskAndVerify` 支持 `expectedText=null`、`TaskScheduler` 透传 `media_path`、`P4Test` 加 7 个第二刀场景、`run_p4_test.bat` 改为 `text|media` 合并入口。
