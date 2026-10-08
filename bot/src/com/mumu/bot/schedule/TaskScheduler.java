package com.mumu.bot.schedule;

import com.mumu.bot.config.Config;
import com.mumu.bot.contact.ContactApiClient;
import com.mumu.bot.outbound.adb.WeChatUi;
import com.mumu.bot.outbound.api.ApiMsgSender;
import com.mumu.bot.store.RoomMap;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 定时宣传调度器（P4，见 ADR-0008 / ADR-0009）。
 *
 * 由 QueueConsumer 消费线程每 500ms 调一次 tick()，内部按 P4_TICK_INTERVAL_MS(30s) 节流，
 * 不另起线程（与出站队列天然串行）。每次 tick：
 *   1. createDueRuns：扫启用任务，到期（含 4h 内错过补发）则用 UNIQUE(task_id,planned_date) 占位
 *   2. advanceRuns：推进所有非终态且退避到期的 run：
 *        pending/failed_api → 调 add_msg_template（第二刀：先 media/upload 再 attachments）
 *        template_created/ADB 失败态 → ADB 进群发助手确认（复用 msgid，不重建任务）
 *
 * 幂等：重启后 run 行还在 → 不会二次建任务/二次发送；API 与 ADB 尝试次数分列计数。
 * 第二刀：task.mediaPath 透传给 ApiMsgSender.createTemplate；纯图任务（content 空）也透传。
 */
public final class TaskScheduler {

    private final TaskStore store = new TaskStore();
    private final ApiMsgSender sender;
    private volatile long lastTickAt = 0L;

    public TaskScheduler(ContactApiClient api, WeChatUi ui) {
        this.sender = new ApiMsgSender(api, ui);
    }

    /** QueueConsumer 挂点：30s 节流，异常不扩散（不能打死消费循环） */
    public void tick() {
        long now = System.currentTimeMillis();
        if (now - lastTickAt < Config.P4_TICK_INTERVAL_MS) return;
        lastTickAt = now;
        try {
            createDueRuns(now);
            advanceRuns(now);
        } catch (Exception e) {
            log("❌ [P4] tick 异常: " + e.getMessage());
        }
    }

    /** P4Test 入口：不等节流，立即推进指定任务一次 */
    public TaskRun fireNow(long taskId) {
        ScheduledTask task = store.getTask(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: id=" + taskId);
        }
        String plannedDate = LocalDate.now().toString();
        Long runId = store.createRunIfAbsent(taskId, plannedDate);
        TaskRun run = store.getRunByDate(taskId, plannedDate);
        if (runId == null && TaskRun.isTerminal(run.state)) {
            log("任务 " + taskId + " 今日已终态（" + run.state + "），幂等跳过");
            return run;
        }
        advance(task, run, System.currentTimeMillis());
        return store.getRunByDate(taskId, plannedDate);
    }

    // ===== 第 1 步：到期扫描 + 幂等占位 =====

    void createDueRuns(long now) {
        for (ScheduledTask task : store.listEnabledTasks()) {
            try {
                Due due = evaluateDue(task, now);
                if (due == null) continue;

                long cutoff = due.plannedInstant
                    + Config.P4_MAKEUP_WINDOW_HOURS * 3600_000L;
                if (now > cutoff) {
                    // 错过补发窗口：一次性任务留 failed_missed 审计行；周期任务不写行（明天照常）
                    if ("once".equals(task.rhythm)) {
                        Long id = store.createRunIfAbsent(task.id, due.plannedDate);
                        if (id != null) {
                            TaskRun r = store.getRunByDate(task.id, due.plannedDate);
                            r.state = TaskRun.FAILED_MISSED;
                            r.lastError = "超过补发窗口（" + Config.P4_MAKEUP_WINDOW_HOURS + "h）";
                            r.firedAt = now;
                            store.updateRun(r);
                            com.mumu.bot.alert.AlertThrottle.record("task_failed_missed");
                            log("⚠️ [P4] 一次性任务「" + task.name + "」错过补发窗口，failed_missed");
                        }
                    }
                    continue;
                }

                // ADR-0010：运行时同群当天配额硬判定（表单校验放行后的兜底）
                // 检查目标群 ∩ room_map 启用 ∩ test.target-roomid 白名单内每个群当天是否已 send_clicked
                List<String> plannedRooms = resolveTargetRooms(task);
                String blockedRoom = null;
                for (String roomid : plannedRooms) {
                    if (store.hasRoomSentToday(roomid, due.plannedDate)) {
                        blockedRoom = roomid;
                        break;
                    }
                }
                if (blockedRoom != null) {
                    com.mumu.bot.alert.AlertThrottle.record("task_run_skipped_sent_today");
                    log("⚠️ [P4] 任务「" + task.name + "」触发前检测到群 " + blockedRoom
                        + " 当天已 send_clicked（企微规则：同群每天一条），跳过本次触发（P7 邮件告警）");
                    continue;
                }

                Long runId = store.createRunIfAbsent(task.id, due.plannedDate);
                if (runId != null) {
                    log("📅 [P4] 任务「" + task.name + "」到期，创建 run id=" + runId
                        + " planned_date=" + due.plannedDate);
                }
            } catch (Exception e) {
                log("❌ [P4] 到期扫描异常 task=" + task.id + ": " + e.getMessage());
            }
        }
    }

    /** 到期判定结果 */
    private static final class Due {
        final String plannedDate;     // yyyy-MM-dd
        final long plannedInstant;
        Due(String d, long ts) { this.plannedDate = d; this.plannedInstant = ts; }
    }

    private Due evaluateDue(ScheduledTask t, long now) {
        ZoneId zone = ZoneId.systemDefault();
        switch (t.rhythm == null ? "" : t.rhythm) {
            case "once": {
                if (t.fireOnceAt == null || now < t.fireOnceAt) return null;
                String date = Instant.ofEpochMilli(t.fireOnceAt).atZone(zone).toLocalDate().toString();
                return new Due(date, t.fireOnceAt);
            }
            case "daily":
            case "weekly": {
                LocalDate today = LocalDate.now(zone);
                if ("weekly".equals(t.rhythm) && !weekdayMatches(t.weekdays, today)) {
                    return null;
                }
                LocalTime hm;
                try {
                    hm = LocalTime.parse(t.plannedTime);
                } catch (Exception e) {
                    log("❌ [P4] 任务「" + t.name + "」planned_time 非法: " + t.plannedTime);
                    return null;
                }
                LocalDateTime planned = today.atTime(hm);
                long plannedMs = planned.atZone(zone).toInstant().toEpochMilli();
                if (now < plannedMs) return null;
                return new Due(today.toString(), plannedMs);
            }
            default:
                return null;
        }
    }

    /** weekdays 形如 "1,3,5"（1=周一…7=周日，与 java.time.DayOfWeek 一致） */
    private boolean weekdayMatches(String weekdays, LocalDate today) {
        if (weekdays == null || weekdays.isBlank()) return false;
        int dow = today.getDayOfWeek().getValue();
        for (String s : weekdays.split(",")) {
            try {
                if (Integer.parseInt(s.trim()) == dow) return true;
            } catch (NumberFormatException ignore) { }
        }
        return false;
    }

    // ===== 第 2 步：状态机推进 =====

    private void advanceRuns(long now) {
        for (TaskStore.Candidate c : store.findRunnableRuns(now)) {
            try {
                advance(c.task, c.run, now);
            } catch (Exception e) {
                log("❌ [P4] run " + c.run.id + " 推进异常: " + e.getMessage());
            }
        }
    }

    private void advance(ScheduledTask task, TaskRun run, long now) {
        switch (run.state) {
            case TaskRun.PENDING:
            case TaskRun.FAILED_API:
                callApiPhase(task, run, now);
                if (TaskRun.TEMPLATE_CREATED.equals(run.state)) {
                    callAdbPhase(task, run, now); // API 成功后同 tick 继续 ADB
                }
                break;
            case TaskRun.TEMPLATE_CREATED:
            case TaskRun.FAILED_NO_ENTRY:
            case TaskRun.FAILED_NO_BTN:
            case TaskRun.FAILED_CLICK:
                callAdbPhase(task, run, now);
                break;
            default:
                // 终态/其他态不处理
        }
    }

    private void callApiPhase(ScheduledTask task, TaskRun run, long now) {
        List<String> roomids = resolveTargetRooms(task);
        if (roomids.isEmpty()) {
            run.state = TaskRun.FAILED_FINAL;
            run.lastError = "无有效目标群（room_map 启用状态 / test.target-roomid 白名单 / 任务 target_rooms 交集为空）";
            run.firedAt = now;
            store.updateRun(run);
            warnFinal(task, run, run.lastError);
            return;
        }

        run.apiAttempts++;
        ApiMsgSender.CreateResult r = sender.createTemplate(
            task.effectiveSender(), roomids, task.content, task.mediaPath);

        if (r.ok) {
            run.state = TaskRun.TEMPLATE_CREATED;
            run.msgid = r.msgid;
            run.lastError = null;
            run.nextAttemptAt = null;
            store.updateRun(run);
            store.updateTaskApiMsgid(task.id, r.msgid);
            log("✅ [P4] run " + run.id + " 群发任务已创建 msgid=" + r.msgid
                + " rooms=" + roomids);
            return;
        }

        run.lastError = r.error;
        if (r.fatal || run.apiAttempts >= Config.P4_MAX_ATTEMPTS) {
            run.state = TaskRun.FAILED_FINAL;
            run.nextAttemptAt = null;
            run.firedAt = now;
            store.updateRun(run);
            warnFinal(task, run, r.fatal ? "致命错误: " + r.error
                : "API 重试 " + run.apiAttempts + " 次仍失败: " + r.error);
            return;
        }
        run.state = TaskRun.FAILED_API;
        run.nextAttemptAt = now + backoff(run.apiAttempts);
        store.updateRun(run);
        log("🔄 [P4] run " + run.id + " API 第 " + run.apiAttempts + " 次失败: "
            + r.error + "，" + (backoff(run.apiAttempts) / 1000) + "s 后重试");
    }

    private void callAdbPhase(ScheduledTask task, TaskRun run, long now) {
        run.adbAttempts++;

        // ADR-0009 第二刀补丁：pdf 媒体任务 ADB 调用前多等 3 秒——
        // 大文件上传后企微端群发助手历史页同步较慢，立即找卡片容易 NO_MATCH 触发重试；
        // 多等 3 秒给企微端同步时间，从源头减少重试。
        if (isPdfMedia(task.mediaPath)) {
            try {
                log("⏳ [P4] run " + run.id + " pdf 媒体任务：ADB 调用前多等 3 秒（企微端同步大文件慢）");
                Thread.sleep(3000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        String prefix = "p4_" + run.id + "_" + run.adbAttempts;
        ApiMsgSender.ConfirmOutcome o = sender.confirmByAdbClick(task.content, prefix);

        switch (o) {
            case SENT:
                run.state = TaskRun.SEND_CLICKED;
                run.nextAttemptAt = null;
                run.lastError = null;
                run.firedAt = now;
                store.updateRun(run);
                log("✅ [P4] run " + run.id + " 已在群发助手点击发送（msgid=" + run.msgid + "）");
                return;
            case DRY_RUN:
                run.state = TaskRun.DRY_RUN_VERIFIED;
                run.nextAttemptAt = null;
                run.lastError = null;
                run.firedAt = now;
                store.updateRun(run);
                log("🧪 [P4] run " + run.id + " dry-run 验证通过（未点击发送，不占配额）");
                return;
            default:
                break;
        }

        String mapped;
        String detail;
        switch (o) {
            case NO_BTN:
                mapped = TaskRun.FAILED_NO_BTN;
                detail = "详情页找不到发送按钮";
                break;
            case NO_ENTRY:
                mapped = TaskRun.FAILED_NO_ENTRY;
                detail = "进不了群发助手/找不到任务卡片";
                break;
            case NO_MATCH:
                mapped = TaskRun.FAILED_NO_ENTRY;
                detail = "历史页无匹配的待发送卡片（企微同步延迟？）";
                break;
            default:
                mapped = TaskRun.FAILED_CLICK;
                detail = "ADB 导航/点击异常";
        }
        run.lastError = detail;
        if (run.adbAttempts >= Config.P4_MAX_ATTEMPTS) {
            run.state = TaskRun.FAILED_FINAL;
            run.nextAttemptAt = null;
            run.firedAt = now;
            store.updateRun(run);
            warnFinal(task, run, "ADB 重试 " + run.adbAttempts + " 次仍失败: " + detail
                + "（msgid=" + run.msgid + "，任务仍在企微端待发，可人工补救）");
            return;
        }
        run.state = mapped;
        run.nextAttemptAt = now + backoff(run.adbAttempts);
        store.updateRun(run);
        log("🔄 [P4] run " + run.id + " ADB 第 " + run.adbAttempts + " 次失败: "
            + detail + "，" + (backoff(run.adbAttempts) / 1000) + "s 后重试（复用 msgid）");
    }

    // ===== 目标群解析 =====

    /**
     * 任务 target_rooms ∩ room_map(enabled=1) ∩ test.target-roomid 白名单，去重保序。
     * 群发任务只能发给 sender 当群主的群——room_map 由运维保证全部属于该 sender（见 ADR-0008）。
     */
    private List<String> resolveTargetRooms(ScheduledTask task) {
        Set<String> enabled = RoomMap.enabledRooms().stream()
            .map(r -> r.roomid).collect(Collectors.toSet());
        Set<String> out = new LinkedHashSet<>();
        for (String id : Arrays.asList(task.targetRooms.split(","))) {
            String s = id.trim();
            if (!s.isEmpty() && enabled.contains(s)) out.add(s);
        }
        if (!Config.TEST_TARGET_ROOMID.isEmpty()) {
            out.retainAll(new ArrayList<>(List.of(Config.TEST_TARGET_ROOMID)));
        }
        return new ArrayList<>(out);
    }

    private long backoff(int failedAttempts) {
        // ADR-0010（2026-09-16）：P4 task_run 退避改用 Config.P4_RETRY_BACKOFF_MS（5min/30min/60min）。
        // P2 出站队列仍用 Config.RETRY_BACKOFF_MS（30s/2min/10min）不动。
        long[] table = Config.P4_RETRY_BACKOFF_MS;
        int idx = Math.min(failedAttempts - 1, table.length - 1);
        return table[Math.max(idx, 0)];
    }

    /** ADR-0009 第二刀：pdf 媒体任务特殊处理（大文件企微端同步慢） */
    private static boolean isPdfMedia(String mediaPath) {
        return mediaPath != null && mediaPath.toLowerCase().endsWith(".pdf");
    }

    private void warnFinal(ScheduledTask task, TaskRun run, String reason) {
        // ADR-0010：P5 阶段仅埋点 + WARN 日志，P7 阶段实现邮件发送
        com.mumu.bot.alert.AlertThrottle.record("task_failed_final");
        log("⚠️ [P4] 任务「" + task.name + "」run " + run.id + " 转 failed_final：" + reason
            + "（已埋点 alert_throttle.task_failed_final，P7 起此处接邮件告警）");
    }

    private static void log(String msg) {
        System.out.println(msg);
        System.out.flush();
    }
}
