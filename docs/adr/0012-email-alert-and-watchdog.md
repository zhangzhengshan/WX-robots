# P7 邮件告警 + 看门狗

2026-09-16 P7 设计拷问锁定。本 ADR 定义 6 类邮件告警的触发与节流、熔断哨兵机制、看门狗崩溃重启/熔断不重启策略，以及 SMTP 发信技术选型。

## Decision

### 1. 6 类邮件告警（其余仅埋点+WARN）

**发邮件的 6 类**（`MailAlerter.alert`）：

| 事件类型 | 触发点 | 节流 |
|---|---|---|
| `circuit_breaker` | 熔断（风控关键词 / 未捕获 Error / DB 初始化失败） | **绕过**，立即发 |
| `heartbeat_lost` | 心跳丢失 / 进程假死 / 进程崩溃（由 watchdog.ps1 发） | 30 min |
| `sdk_pull_failed` | 存档 SDK 拉取连续失败 10 次（`ArchiveClient.listenLoop`） | 30 min |
| `adb_failed` | `Adb.shell` 连续失败 5 次 | 30 min |
| `outbound_failed_final` | 出站消息重试耗尽转 `failed`（`OutboundQueue.markFailed`） | 30 min |
| `task_cleanup_failed` | 群发助手历史卡片删除失败（`ApiMsgSender.confirmByAdbClick`） | 30 min |

**仅埋点+WARN，不发邮件**：`task_failed_final` / `task_failed_missed` / `task_run_skipped_sent_today` / `ai_failed`。理由：这些是可恢复或预期内的状态（退避耗尽、配额跳过、LLM 抖动），发邮件会刷屏；埋点留在 `alert_throttle` 表供管理端/日志排查。

### 2. 节流：30 分钟固定窗口

- `alert_throttle(event_type PRIMARY KEY, last_sent_at INTEGER)` 表存最近一次「通过节流」的时刻。
- `AlertThrottle.shouldSend(eventType)`：读 `last_sent_at`，若 `now - last < 30min` 返回 false 且**不刷新时间戳**（保持原锚点）；否则 UPSERT `last_sent_at=now` 并返回 true。
- `circuit_breaker` 绕过节流（熔断必须立即通知人工），但仍 UPSERT 埋点。
- watchdog.ps1 侧用 `data/watchdog_throttle.json` 做同样的 30 分钟节流（JVM 已死时无法读 SQLite）。

### 3. SMTP 发信：jakarta.mail 单 jar，163 邮箱 SSL

- 选型：`com.sun.mail:jakarta.mail:2.0.1`（~689KB 单 jar，含 API+impl+activation），放 `bot/lib/`。
- 服务商：163 邮箱，`smtp.163.com:465` SSL，登录用**授权码**（非登录密码）。
- 自发自收：发件人 = `mail.smtp.account`；收件人优先 `data/mail.properties` 的 `recipient`（管理端可改），缺省取 `mail.smtp.account`。
- 配置位置：SMTP host/port/ssl/account/auth-code 在 `config.properties`（已 gitignore，授权码不暴露）；收件人在管理端 `/mail` 页面编辑，存 `data/mail.properties`，JVM 与 watchdog 发信时读取（无需重启）。
- JVM 用 `jakarta.mail` 发；watchdog 用 PowerShell 5 内置 `Send-MailMessage -UseSsl` 发（JVM 已死时）。

### 4. 熔断哨兵文件（区分「崩溃重启」与「熔断不重启」）

- 退出码无法区分「`System.exit(1)` 熔断」与「未捕获异常崩溃」，改用哨兵文件：
- 熔断时 `CircuitBreaker.trip(reason)` 写 `data/circuit_breaker`（含原因+时间戳）→ 发 `circuit_breaker` 告警 → `System.exit(1)`。
- watchdog 检测到哨兵文件 → **不重启、发告警、watchdog 自身退出**（等人工处理后删除哨兵 + 手动重启 watchdog）。
- 崩溃（无哨兵）→ watchdog 自动拉起。

### 5. 看门狗 watchdog.ps1

`watchdog/watchdog.ps1`，每 30s 检测：
1. **哨兵优先**：`data/circuit_breaker` 存在 → 告警 + 退出（不重启）。
2. **心跳检测**：进程存活但 `data/heartbeat` mtime > 80s → 假死 → kill + 告警 + 重启。
3. **进程存活**：`java.exe com.mumu.bot.Main` 消失 → 崩溃 → 告警 + 重启 `bot/run-only.bat`。
4. **崩溃环防护**：10 分钟内重启 ≥5 次 → 停止重启 + 告警 + watchdog 退出。
5. **重启入口**：`bot/run-only.bat`（不编译、无 `pause`，透传 JVM exit code）；人工启动仍用 `run.bat`（编译+运行+pause）。

### 6. 心跳

`Main` 启动 daemon 线程每 10s 写 `data/heartbeat`（epoch ms）。80s 超时容忍 ADB 长导航 / LLM 调用（≤60s）期间的假阳性。

## Alternatives Considered

- **HTTP 事务邮件 API（阿里云邮件推送/SendCloud）**：零 jar 且 JVM/PS 都能发，但需要额外申请服务商账号。用户已有 163 邮箱，SMTP 授权码开箱即用，且单 jar 体积小。否决 HTTP API。
- **JDK 原生 socket 手写 SMTP**：零 jar 但 ~200 行 TLS/SMTP 协议代码，脆弱难维护。否决。
- **task_* 事件也发邮件**：退避耗尽、配额跳过在定时任务中属于预期状态，发邮件会刷屏（每天可能多次）。否决，仅埋点。
- **熔断用 exit code 区分**：`System.exit(1)` 与未捕获异常崩溃都产生非零 exit code，watchdog 无法区分。改用哨兵文件可靠。否决 exit code 方案。
- **收件人存 config.properties**：管理端编辑 config.properties 风险高（可能损坏含密钥的文件）。单独 `data/mail.properties` 安全且 watchdog 易读。否决。

## Consequences

- 优势：6 类关键故障 30 分钟内必有一封邮件通知人工；熔断不自动重启避免风控状态下反复拉起封号；心跳+哨兵双保险区分假死与主动熔断。
- 代价：引入 1 个 jakarta.mail jar（违反零 jar 原则，但单 jar 689KB 可接受）；watchdog 需常驻 PowerShell 进程；163 授权码需用户自行生成填入 config.properties。
- 影响：`AlertThrottle` 新增 `shouldSend`；新建 `MailAlerter` / `CircuitBreaker`；`ArchiveClient` / `Adb` / `OutboundQueue` / `ApiMsgSender` / `AiService` / `WeChatUi` / `Main` 接线；`watchdog.ps1` 重写；拆分 `build.bat` / `run-only.bat`；管理端新增 `/mail` 页；`config.properties` 新增 `mail.*` 7 字段。
