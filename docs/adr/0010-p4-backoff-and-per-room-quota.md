# P4 退避时序反转 + 同群配额跳过机制

2026-09-16 P5 设计拷问锁定。本 ADR 反转 [ADR-0008](0008-scheduled-promo-via-group-msg-api.md) 第 9 条「退避 30s/2min/10min」与「每群每天一条仅靠表单约束」两项，并新增"同群当天已发送则跳过新触发"的运行时硬约束。

## Decision

### 1. task_run 退避时序从 30s/2min/10min 改为 5min/30min/60min

- **api_attempts 与 adb_attempts 仍然各 3 次独立**，但退避间隔统一改为 5 分钟 / 30 分钟 / 60 分钟。
- 配置项 `p4.retry-backoff-ms=300000,1800000,3600000`，覆盖原硬编码的 `{30_000, 120_000, 600_000}`。
- 总尝试上限：5+30+60 = 95 分钟 × 2（api/adb 各一次）= 约 3 小时，超过 `p4.makeup-window-hours=4`（默认 4h）后由 failed_missed 兜底。
- **范围限定**：仅 P4 task_run 状态机使用新退避；P2 outbound_message 出站队列（@提及 AI 回复的失败重试）保持 30s/2min/10min 不动——P2 是即时对话，长退避会让客户等 1 小时看不到回复。

### 2. 同群当天已发送则跳过新触发（运行时硬约束）

ADR-0008 原本只靠"管理端表单层禁止同群一天配第二次"软约束。本 ADR 加一层运行时硬判定：

- 触发前 `TaskStore.hasRoomSentToday(roomid, today)` 检测当天该群是否已有 state=`send_clicked` 的 run（**dry_run_verified 不算占用配额，因为没真发**；cancelled_paused 不算）。
- 有则**不创建新 run**，写 WARN 日志"群 X 因当天已发送跳过任务 Y 触发" + 写 `alert_throttle(event_type='task_run_skipped_sent_today')`（P7 邮件接）。
- SQL：JOIN scheduled_task + task_run，用 `target_rooms LIKE '%,' || ? || ',%'`（前后加逗号防子串误匹配，要求 target_rooms 存储规范为"无空格、前后不加分隔符"，匹配时两端临时拼逗号）。

### 3. 失败终态告警埋点

- task_run 转 `failed_final` 时写 `alert_throttle(event_type='task_failed_final')`。
- task_run 转 `failed_missed`（超补发窗口）时写 `alert_throttle(event_type='task_failed_missed')`。
- P5 不实现邮件发送，仅埋点 + WARN 日志；P7 阶段实现 6 类邮件告警时把上述两类纳入。
- **不做手动重试按钮**：失败走自动退避；failed_missed / cancelled_paused 不重试。

### 4. 同群一天一条的业务约束仍是双层

1. **表单层**（管理端 POST/PUT handler）：创建/编辑任务时按 rhythm 计算未来会触发的日期集合，与现有任务的目标群取交集，冲突则 HTTP 400 + 红字提示"与任务《XXX》在 X 月 X 日冲突，请先删除该任务再提交"。
2. **运行时层**（TaskScheduler.tick）：即使表单校验放行（如先建后删导致当天该群已有 send_clicked），触发前再做一次 `hasRoomSentToday` 兜底，硬跳过。

## Alternatives Considered

- **保持 30s/2min/10min 退避**：P4 是定时宣传，发送时刻可宽限几分钟；长退避给运维更多介入时间。但客户场景希望"5 分钟内看不到结果就当失败，别反复重试打扰群成员"。5/30/60 介于"立刻重试"和"放弃"之间，且总时长 < 4h 补发窗口，逻辑闭环。否决 30s/2min/10min。
- **P2 出站队列也改 5/30/60**：P2 是 @提及即时对话，客户 @完机器人等 1 分钟还能接受，等 1 小时绝对不可接受。否决，保持 30s/2min/10min。
- **同群一天一条只靠表单约束**：先建任务 A 触发已发送，删除 A，建任务 B 同群同天，表单层看到"无冲突"（A 已删），但企微侧当天配额已用完。运行时硬判定是兜底，否则 B 会触发 API 失败浪费重试。否决"仅表单"。
- **运行时硬判定查企微 API 配额**：企微没有"查询群当天已发条数"的 API；只能查自己 task_run 表的 send_clicked 状态。否决查 API。

## Consequences

- 优势：退避时长更符合"定时宣传可延迟、但别反复打扰"的业务预期；运行时硬判定杜绝企微侧二次建任务被拒的失败重试。
- 代价：P4 重试总时长上限从 30s+2min+10min ≈ 12.5 分钟拉长到 95 分钟 ×2 ≈ 3 小时，期间任务一直占用 task_run 行；failed_final 后告警延迟最大 95 分钟。
- 影响：ADR-0008 第 9 条「重试分离...退避复用 30s/2min/10min」改写为「退避 5min/30min/60min」；plan P4 设计决策 #9 同步更新；TaskScheduler 退避常量改为读 Config.P4_RETRY_BACKOFF_MS；TaskStore 新增 hasRoomSentToday 方法；config.properties 新增 p4.retry-backoff-ms 字段。
