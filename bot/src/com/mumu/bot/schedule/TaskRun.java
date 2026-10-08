package com.mumu.bot.schedule;

/**
 * 一次计划触发的执行状态（task_run 一行，P4，见 ADR-0008）。
 *
 * 状态流转：
 *   pending ──API ok──▶ template_created ──ADB 确认成功──▶ send_clicked（生产终态）
 *      │                    │                          └▶ dry_run_verified（dry-run 终态）
 *      │                    ├─ADB 找不到入口─▶ failed_no_entry ─┐
 *      │                    ├─找不到发送按钮─▶ failed_no_btn ───┤ 退避后重试 ADB（复用 msgid）
 *      │                    └─点击异常──────▶ failed_click ─────┘
 *      └─API 失败──▶ failed_api ──退避后重试 API（重建任务）
 * 任一阶段重试耗尽 → failed_final（P7 接邮件告警）
 * 超过补发窗口未跑 → failed_missed；暂停期间到期 → cancelled_paused
 */
public final class TaskRun {

    public static final String PENDING           = "pending";
    public static final String TEMPLATE_CREATED  = "template_created";
    public static final String SEND_CLICKED      = "send_clicked";
    public static final String DRY_RUN_VERIFIED  = "dry_run_verified";
    public static final String FAILED_API        = "failed_api";
    public static final String FAILED_NO_ENTRY   = "failed_no_entry";
    public static final String FAILED_NO_BTN     = "failed_no_btn";
    public static final String FAILED_CLICK      = "failed_click";
    public static final String FAILED_FINAL      = "failed_final";
    public static final String FAILED_MISSED     = "failed_missed";
    public static final String CANCELLED_PAUSED  = "cancelled_paused";

    /** 终态：不再被调度器扫描 */
    public static boolean isTerminal(String state) {
        return SEND_CLICKED.equals(state) || DRY_RUN_VERIFIED.equals(state)
            || FAILED_FINAL.equals(state) || FAILED_MISSED.equals(state)
            || CANCELLED_PAUSED.equals(state);
    }

    /** ADB 阶段失败态：复用已有 msgid 只重试 ADB */
    public static boolean isAdbFailure(String state) {
        return FAILED_NO_ENTRY.equals(state) || FAILED_NO_BTN.equals(state)
            || FAILED_CLICK.equals(state);
    }

    public long id;
    public long taskId;
    public String plannedDate;  // yyyy-MM-dd
    public String state;
    public int apiAttempts;
    public int adbAttempts;
    public Long nextAttemptAt;
    public String msgid;
    public String lastError;
    public Long firedAt;
    public long createdAt;
}
