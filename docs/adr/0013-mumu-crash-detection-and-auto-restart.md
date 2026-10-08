# MuMu 崩溃检测与自动重启

2026-09-17 设计拷问锁定。同日修订：告警策略改为「约定次数内静默自愈，仅超限熔断才发邮件」，`mumu_crashed` 独立告警类型撤销，邮件告警表回到 ADR-0012 的 6 类。本 ADR 扩展看门狗职责至 MuMu 模拟器进程监控，并将 `adb_failed` 语义收窄为 ADB 工具级故障。

## Decision

### 1. MuMu 故障的告警策略：静默自愈，超限才告警（修订）

初版设计为「每次崩溃自动重启后发 `mumu_crashed` 邮件（30 min 节流）」，已撤销。修订后策略：

- **约定次数内（<3 次/小时）成功拉起 → 全程静默，不发邮件**。MuMu 崩溃可由自动重启恢复，每次都发邮件只会让用户对告警脱敏。
- **启动超时（120s 未 start_finished）同样静默**：只计一次重启次数，watchdog 下周期继续重试；单次恢复失败不是终态，不构成打扰用户的理由。
- **自愈留痕**：每次 MuMu 崩溃处理完成后，watchdog 追加一行到 `data/mumu_events.log`（时间 + player_state + 崩溃签名 + 恢复结果）。控制台输出关窗即失，留痕保证"昨晚崩过 2 次又自愈了"这类信息可排查。
- **超过限制（≥3 次/小时）→ 写熔断哨兵 + 发 `circuit_breaker` 邮件（绕过节流）+ watchdog 退出等人工**。这是 MuMu 故障唯一发邮件的时机，复用现有熔断通道，原因文本已含 MuMu 崩溃签名（errcode/errmsg）。
- `mumu_crashed` 类型撤销：上述策略下它没有任何触发场景（成功不发、超时不发、超限走 circuit_breaker），永不触发的告警类型直接删除。

### 1.1 检测方与检测信号（不变）

- **检测方：watchdog.ps1**（非 bot JVM——MuMu 崩溃时 JVM 仍活着，只是 ADB 命令失败，无法从 JVM 侧可靠区分"MuMu 没了"与"adb server 异常"）。
- **检测信号**：`MuMuManager.exe info -v 0` 返回的 JSON——`player_state != "start_finished"` 或 `headless_pid` 对应进程不存在 → 判定崩溃。
- **不区分 OOM 与其他崩溃**：Windows 事件日志/MuMu crash log 格式不稳；检测"进程消失"覆盖 OOM 这一常见原因即可，崩溃签名（`error_code` / `launch_err_msg`）写入留痕与熔断邮件作为线索。

### 2. adb_failed 语义收窄

原 `adb_failed` CN 名"ADB/模拟器失联"与 `mumu_crashed` 重叠。收窄为：
- `adb_failed` CN 名改为 **"ADB服务异常"**，专指 ADB server/工具级故障（adb kill-server 后未重连、adb 二进制异常等）。
- MuMu 进程级故障归 `mumu_crashed` 独占。

### 3. mumu_down 哨兵：抑制 adb_failed 双告警

MuMu 崩溃后 bot 的 ADB 命令开始失败，可能在 watchdog 检测到 MuMu 进程消失前攒够 5 次失败触发 `adb_failed`，与 `mumu_crashed` 形成双告警。抑制方案：
- watchdog 检测到 MuMu 崩溃 → 写 `data/mumu_down` 哨兵。
- bot 侧 `Adb.shell` 开头检查哨兵存在 → 跳过 `adb_failed` 连续失败计数（仍抛异常，但不计入告警）。
- watchdog 完成 MuMu 重启 + ADB 设备就绪后删除哨兵。
- bot 启动 `Adb.connectDevice()` 成功后自愈清理残留哨兵（防止 watchdog 自身中途崩溃留下哨兵永久抑制 adb_failed）。

### 4. 看门狗自动重启 MuMu（职责扩展）

watchdog 职责从"管 bot 进程"扩展到"管 bot + MuMu 两个进程"。MuMu 崩溃恢复序列：
1. 写 `data/mumu_down` 哨兵。
2. `MuMuManager.exe control -v 0 launch`。
3. 轮询 `MuMuManager.exe info -v 0` 直到 `player_state == "start_finished"`（或 `adb get-state == device`），超时 120s。
4. 删除 `mumu_down` 哨兵。
5. 调 `Restart-Bot` 重启 bot（复用现有入口 `bot/run-only.bat`）。
6. 追加一行到 `data/mumu_events.log` 留痕（约定次数内不发邮件）。

步骤 3 超时 → 静默留痕（结果=启动超时），仍继续删哨兵 + 重启 bot（让 bot 自行重试 ADB 连接兜底）；watchdog 下周期继续重试，直到崩溃环超限熔断才发邮件。

`MuMuManager.exe` 路径从 `config.properties` 的 `adb.path` 同目录（`shell\`）推导；实例索引 0（对应 ADB 端口 16384）。无需新配置键。

### 5. MuMu 崩溃环（独立于 bot 崩溃环，唯一发邮件的时机）

MuMu 反复 OOM（如每 20 分钟一次）不应无限重启。独立计数器：
- 1 小时内 MuMu 重启 ≥3 次 → 写 `circuit_breaker` 哨兵 → 发 `circuit_breaker` 邮件（绕过节流，原因含 MuMu 崩溃签名）→ watchdog 自身退出（等人工，大概率 MuMu 内存分配过小需调配置）。
- 与现有 bot 崩溃环（5 次/10 分钟）分离，互不干扰。

### 6. 告警节流（修订）

`mumu_crashed` 类型已撤销（见决策 1），MuMu 故障只走 `circuit_breaker`（绕过节流）——熔断意味着自动恢复已放弃、必须人工介入，属于"立即停摆"级紧急，不能等 30 分钟。

## Alternatives Considered

- **bot 侧通过 ADB 失败模式推断 MuMu 崩溃**：ADB 失败原因很多（adb server 死、设备授权失效、网络），无法可靠区分"MuMu 进程没了"。否决，watchdog 直查进程更准。
- **查 Windows 事件日志区分 OOM**：事件日志格式随 Windows 版本变、MuMu 自身 crash log 未文档化，OOM 专项检测脆弱。否决，检测进程消失覆盖面更广。
- **MuMu 重启计入 bot 崩溃环**：两种故障模式不同（bot 反复崩 vs 模拟器内存不足），合并计数会误触熔断。否决，独立计数器更清晰。
- **不写 mumu_down 哨兵，靠 CN 名区分两封邮件**：运维仍需看两封邮件拼凑同一事件，体验差。哨兵方案从根上抑制，复用现有 circuit_breaker 哨兵模式。否决 CN 名方案。
- **固定 sleep 等 MuMu 启动**：MuMu 冷启动时间方差大（30-90s+），固定值不可靠。轮询 `player_state` 最准。否决。
- **MuMu 崩溃即熔断（不自动重启）**：MuMu OOM 可恢复，与风控/DB 失败这类不可逆故障不同；停摆等人工造成不必要停机。否决，自动重启。
- **每次崩溃自动重启后都发 `mumu_crashed` 邮件（初版设计，同日撤销）**：崩溃可自愈，每次都发邮件只会让用户对告警脱敏；且节流意味着连崩时信息既晚又碎。改为静默自愈 + `data/mumu_events.log` 留痕 + 超限熔断才发 `circuit_breaker`。
- **启动超时立即发邮件**：超时不是终态——watchdog 下周期继续重试，崩溃环超限后熔断兜底（最坏约 8 分钟后必有 `circuit_breaker` 邮件）。为保持"超限才打扰"的一致原则而否决。
- **只更新 ADR-0012 不写新 ADR**：watchdog 职责扩展到模拟器是架构级变化，未来读者会问"为什么 watchdog 还管 MuMu"。单独 ADR 更清晰。否决合并。

## Consequences

- 优势：约定次数内自愈不打扰用户，告警信噪比显著提升；自动重启 MuMu+bot 把停机压到最短；mumu_down 哨兵避免与 adb_failed 双告警；独立崩溃环防止模拟器配置问题无限重启；熔断邮件含崩溃签名，人工介入有据。
- 代价：自愈期间用户完全无感，排查只能靠 `data/mumu_events.log` 与当天 bot 日志；watchdog 职责扩展到模拟器进程，新增 `MuMuManager.exe` CLI 依赖（路径从 `adb.path` 推导，无需新配置）；watchdog 核心循环复杂度上升（MuMu 检测分支 + 重启序列 + 独立崩溃环计数器 + mumu_down 哨兵生命周期 + events 留痕）。
- 影响：`watchdog.ps1`（MuMu 检测/启动/轮询/崩溃环/哨兵/留痕，无 `mumu_crashed` 发信）；`MailAlerter`/`AlertThrottle` 回到 6 类、删除 `MUMU_CRASHED` 常量；`Adb.shell` 哨兵检查；`Main` 启动哨兵自愈清理；CONTEXT.md 邮件告警/看门狗词条更新。
