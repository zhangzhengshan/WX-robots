# 项目词汇表

本文件定义项目核心术语含义。实现决策见 `docs/adr/`，状态机/字段映射见代码。

## 出站消息 (Outbound Message)

机器人向群聊发送的任何消息。来源包括 @提及触发的回复、定时宣传任务、人工触发等。

状态：待发送 → 发送中 → 已发送 / 失败。

## 入站消息 (Inbound Message)

机器人关注到的群聊消息，目前仅 @ 提及和机器人自己的回复。

## 成员名册 (Member Roster)

`(roomid, userid)` → 显示名映射。@用名 = 群昵称优先、否则企微名；手工改名打 `manual_override`，同步不覆盖。

## 去重 (Deduplication)

防止"同一来源"的出站消息被重复入队（典型场景：进程重启后 ArchiveClient 重新拉到同一 seq）。

**指纹 = source_id**（每个出站消息的全局唯一来源标识，如 `at:{seq}:0`、`scheduled:{task_id}:{planned_date}`、`manual:{ts}`）。5 分钟时间窗内同一 source_id 只入队一次。

**不按 content 维度去重**：同群不同用户 @ 机器人问相同问题不去重。**同群同用户连续多次 @（包括连续问相同问题）每次都回答**——每次 @ 有独立 seq、独立 source_id，不应互相去重。跨来源的"相同内容"去重由定时任务的 `unique(task_id, planned_date)` 约束单独负责。

## 一次 @ 的 AI 回复 (AI Reply)

云端 LLM 对一次 @ 的**单轮问答**结果，整段作为一条出站消息发送（P3 起取消早期的多段切分设计，见 ADR-0006）。

- 这条回复带 @ 提及发起者（名册查无此人时降级为纯文本）
- 不记忆上一轮对话，每条 @ 独立识别、独立回答
- 只有 @ 名、没有问题正文（空 @）时不调用 LLM，回固定引导话术
- LLM 调用失败时回固定兜底话术，不让客户的 @ 无响应

## 知识库 (Knowledge Base)

企业提供的客服素材（菜单、价格、团建方案、操作流程、FAQ 等），是 LLM 回答本店问题的唯一权威依据。

- 事实源是机器人本地目录中的素材文件（.docx/.xlsx/.txt/.md），数据库中只保存转换后的纯文本索引
- 全部内容随每次提问整体注入提示词，不做检索；总量上限 10 万字，素材更新后同步即生效
- 素材只由企业方在自己的办公软件里维护并重新上传，机器人不提供在线编辑

## 重试 (Retry)

发送失败后按指数退避（30s / 2min / 10min）重新发送；耗尽 3 次转失败状态。

## 出站发送器 (Outbound Sender)

发送动作的抽象。生产环境走真机（ADB UI 自动化），测试环境走桩实现，便于不连真机时验证队列行为。

## 截图验证 (Screenshot Verification)

发送后保存截图作为审计证据。"发送成功"判定以 ADB 命令链完成 + 截图落盘为准；**不做像素级内容比对**。

## 盲发 (Blind Send)

ADB 命令返回成功即视为发送成功、不做后续验证。本项目摒弃纯盲发，改为盲发 + 截图审计。

## 熔断 (Circuit Breaker)

检测到不可恢复状态时主动 `System.exit(1)` 退出，**看门狗不自动重启**，等人工确认。触发条件（P7，见 ADR-0012）：
1. ADB dump 命中风控关键词（`Config.RISK_KEYWORDS`：身份验证/重新登录/安全验证 等）—— 企微风控弹窗，继续会封号
2. 未捕获 `Error`（OOM / StackOverflow 等）
3. SQLite 数据库初始化失败

行为：写 `data/circuit_breaker` 哨兵文件（含原因+时间戳）→ 发 `circuit_breaker` 告警邮件（绕过节流，立即发）→ `System.exit(1)`。看门狗检测到哨兵文件 → 不重启、发告警、自身退出。人工处理后删除哨兵文件并重启 watchdog。

## 定时宣传任务 (Scheduled Promo Task)

按 once/daily/weekly 节奏在指定时间向 room_map 启用群发送的宣传内容。一条任务 = 一条企微「企业群发」任务（API 创建 + 群主在「群发助手」内确认）。文本可选、最多 1 个媒体附件（图/视频/文件）；二者不能同时为空。每群每天最多 1 条，由管理端表单约束。

## 媒体附件 (Media Attachment)

定时宣传任务可选携带的单个媒体文件（jpg/png 图片、mp4 视频、或任意非音频文件）。源文件存 `data/media/`，触发时调 `media/upload` 换取 3 天有效的 media_id 后入 `add_msg_template` 的 `attachments` 数组发出。**不缓存** media_id：每次触发重传。**最多 1 个**：API 支持最多 9 个，但本店业务与 P5 管理端 UI 都只允许 1 个。语音（.amr/.mp3 等）不支持，因 `add_msg_template` 不接受 voice 类型附件。

## 媒体 ID (media_id)

企微 `media/upload` 返回的临时素材标识，3 天有效、企业内跨应用可共享。本项目**不持久化** media_id：每次任务触发重新上传，避免缓存过期判断与跨进程一致性的复杂度。

## 群发助手 (Group Send Assistant / 群发助手)

企微客户端内「企业群发」任务的待办入口（消息列表 `rid=i4o`）。`add_msg_template` 创建的任务会推送到 sender（群主 userid）的群发助手，由 sender 在客户端内点「发送」才真正发出。本项目用 ADB 自动完成此确认（四层导航：消息列表→历史页→详情页→发送按钮 `rid=cre`→返回历史页长按删除卡片，见 ADR-0011）。

## 管理端 (Admin Server)

机器人进程内嵌的 HTTP 服务（JDK 原生 `com.sun.net.httpserver.HttpServer`，零新 jar），绑 `127.0.0.1:9173`，无认证无登录。仅在机器人所在机器上用浏览器访问，用于：

- 定时任务管理（列表 / 新建 / 编辑 / 启停 / 立即触发 / 硬删除 + 级联 / 运行历史）
- 知识库管理（上传文件 / 列表删除 / 同步结果展示）
- 追加群聊映射（room_map 新增条目）

不做：在线内容编辑、日志/消息/队列/名册/群映射的全量展示——这些数据落文件或由 DB 工具直接查。Db 初始化后立即启动，不依赖 ADB/SDK。

## 按天滚动日志 (Daily Rolling Log)

`logs/bot-yyyy-MM-dd.log`，按天切割，保留最近 14 天。System.out/err 重定向到此文件 + 同时输出控制台。P7 看门狗崩溃重启后续写当天文件，不丢失当天日志。零新 jar（自写 `DailyLogRotator`）。

## 同群当天配额 (Per-Room Daily Quota)

企微「企业群发」规则：同一群一天只能发一条。本项目三层兜底：

1. 表单层：创建/编辑任务时按 rhythm 计算未来触发日期集合与现有任务目标群取交集，冲突直接拒绝。
2. 运行时层：`TaskScheduler.tick` 触发前查当天该群是否已有 `send_clicked` 状态的 task_run（`dry_run_verified` 不算占用配额），有则不创建新 run + WARN + 写 alert_throttle。
3. 企微侧：「群发助手」规则侧也设每天一条。

## 失败终态告警 (Failed Final Alert)

task_run 转 `failed_final`（3 次退避耗尽）或 `failed_missed`（超补发窗口）时写 `alert_throttle(event_type='task_failed_final'/'task_failed_missed')` + WARN 日志。这两类**仅埋点不发邮件**（可恢复/预期内，避免刷屏）；不做手动重试按钮，失败走自动退避 5min/30min/60min。需发邮件的 6 类见「邮件告警」词条。

## 邮件告警 (Email Alert)

P7 实现（见 ADR-0012；MuMu 静默自愈策略见 ADR-0013）。6 类事件触发邮件，同一事件 30 分钟内只发一封（`alert_throttle` 表节流）：

| 事件类型 | 触发点 | 节流 |
|---|---|---|
| `circuit_breaker` | 熔断（风控/Error/DB 失败/MuMu 崩溃环超限） | 绕过，立即发 |
| `heartbeat_lost` | 心跳丢失/进程假死/进程崩溃（watchdog 发） | 30 min |
| `sdk_pull_failed` | 存档 SDK 拉取连续失败 10 次 | 30 min |
| `adb_failed` | ADB 服务异常（shell 连续失败 5 次） | 30 min |
| `outbound_failed_final` | 出站消息重试耗尽转 failed | 30 min |
| `task_cleanup_failed` | 群发助手历史卡片删除失败 | 30 min |

**MuMu 崩溃不发独立邮件**（`mumu_crashed` 类型已撤销）：约定次数内自动重启成功或启动超时均全程静默，仅 watchdog 留痕 `data/mumu_events.log`；超过崩溃环限制才走 `circuit_breaker` 人工介入。

非邮件埋点类（仅 `AlertThrottle.record` + WARN）：`task_failed_final` / `task_failed_missed` / `task_run_skipped_sent_today` / `ai_failed`。

技术：`jakarta.mail`（`com.sun.mail:jakarta.mail:2.0.1` 单 jar）SMTP 发送；163 邮箱 `smtp.163.com:25`（无 SSL）+ 授权码。SMTP 账号/授权码在 `config.properties`（gitignore）；收件人可在管理端 `/mail` 页面修改，存 `data/mail.properties`，JVM 与 watchdog 发信时读取（无需重启）。

告警邮件携带诊断包附件；附件生成失败时邮件仍以纯文本发出——告警永不被附件阻塞。

## 诊断包 (Diagnostics Bundle)

故障排查时在客户机上按固定清单收集的证据压缩包：运行日志、留痕日志、脱敏后的配置、本地数据库、近期截图、系统快照。经邮件回传或人工转交到达开发者。生成诊断包不要求机器人或管理端存活（它们挂掉本身就是要排查的故障）。

## 脱敏 (Redaction)

任何证据离开客户机前，将凭据类配置值（密码、授权码、API 密钥、令牌）替换为占位符的强制步骤。SDK 私钥永不允许进入诊断包或任何回传物。

## 看门狗 (Watchdog)

`watchdog/watchdog.ps1`，独立于 Java 进程。职责（P7，见 ADR-0012）：
1. **进程存活检测**：`java.exe com.mumu.bot.Main` 消失 → 崩溃 → 重启 `bot/run-only.bat`
2. **心跳检测**：`data/heartbeat` mtime 超过 80s → 进程假死 → kill + 告警 + 重启
3. **熔断哨兵**：`data/circuit_breaker` 存在 → 只告警不重启，watchdog 自身退出（等人工）
4. **崩溃环防护**：10 分钟内重启 ≥5 次 → 停止重启 + 告警 + watchdog 退出
5. **代发邮件**：JVM 已死时用 `Send-MailMessage` 发 `heartbeat_lost` / `circuit_breaker` 告警（节流 30 min，circuit_breaker 绕过）
6. **MuMu 模拟器监控**（见 ADR-0013）：`MuMuManager.exe info -v 0` 检测 `player_state != start_finished` 或 VM 进程消失 → 写 `data/mumu_down` 哨兵（抑制 bot 侧 `adb_failed`）→ `control -v 0 launch` → 轮询至 `start_finished`（120s 超时）→ 删哨兵 → 重启 bot。**约定次数内自愈全程静默不发邮件**，仅追加一行到 `data/mumu_events.log` 留痕。独立崩溃环：1 小时内重启 ≥3 次 → 写 `circuit_breaker` 哨兵 + 发 `circuit_breaker` 邮件（MuMu 故障唯一发邮件的时机）→ watchdog 退出等人工。
7. **SSH 隧道托管**（见 ADR-0014）：本地隧道端口无监听 → 重拉 plink（窗口隐藏、幂等跳过已有监听、连续拉起失败 ≥5 次退避 5 分钟）。静默自愈不发邮件（兜底是 `sdk_pull_failed`），仅留痕 `data/tunnel_events.log`。检测顺序：熔断哨兵 → 隧道 → MuMu → bot。

## SSH 隧道 (SSH Tunnel)

机器人所在机器到中转服务器之间的加密转发，为存档 SDK 提供本地 SOCKS5 代理入口（`proxy` 键指向的本地端口）。由看门狗托管（第 7 项职责，见 ADR-0014）：断线自动重拉、静默自愈仅留痕；**幂等**——端口已被占用（如人工临时开的隧道）时跳过不抢占。连接参数（服务器/端口/用户/密码）在 `config.properties` 的 `ssh.proxy.*` 键，host 留空 = 隧道托管功能关闭。

启动：`powershell -ExecutionPolicy Bypass -File watchdog.ps1`。每 30s 检测一次。

## 心跳 (Heartbeat)

`Main` 启动一个 daemon 线程，每 10s 写 `data/heartbeat`（内容为 epoch ms）。看门狗检测该文件 mtime 超过 80s → 判定进程假死（线程死锁/长阻塞）→ kill + 重启。80s 容忍避免 ADB 长导航/LLM 调用（≤60s）期间误判。

# 可移植性 (Portability)

## 依赖闭包 (Dependency Closure)

程序在目标机器上运行所需的全部外部条件之和：运行时、外部二进制、路径、配置、网络身份、宿主程序状态。
_Avoid_: 环境依赖、部署需求清单

## 环境前提 (Environment Prerequisite)

按 ADR-0016 不随包分发、由客户机自行满足的依赖（MuMu 模拟器、企微客户端及登录态、VT 虚拟化、网络可达性）。闭包策略：可携带物全部随包（JRE/plink/adb），环境前提只做探测、引导与告警，不做自动安装。
_Avoid_: 第三方依赖、外部依赖

## 闭包缺口 (Closure Gap)

既未随包携带、又无探测/引导/兜底机制覆盖的环境前提。发布前逐条对照分级清单归零。
_Avoid_: 潜在问题、风险点

## 收敛 (Convergence)

把某个环境前提的人工准备步骤压到最少的工程动作（探测、预检、引导、自动配置）；不改变"客户机必须自备"的边界。
_Avoid_: 自动化安装、零配置
