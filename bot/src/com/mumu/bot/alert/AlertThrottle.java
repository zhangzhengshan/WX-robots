package com.mumu.bot.alert;

import com.mumu.bot.store.Db;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * 告警埋点与节流（P7 启用邮件发送）。
 *
 * alert_throttle 表（见 Db.java）：
 *   event_type   TEXT PRIMARY KEY   -- 事件类型
 *   last_sent_at INTEGER NOT NULL   -- 最近一次「通过节流并准备发信」的时刻（epoch ms）
 *
 * - record(eventType)：纯埋点 UPSERT（非邮件类事件用，如 task_* / ai_failed）。
 * - shouldSend(eventType)：邮件类事件节流判断——30 分钟窗内返回 false 且不刷新时间戳；
 *   窗外返回 true 并 UPSERT last_sent_at=now。circuit_breaker 在 MailAlerter 内绕过节流。
 *
 * 事件类型清单（见 ADR-0012；MuMu 静默自愈策略见 ADR-0013）：
 *   邮件类（6 种，走 MailAlerter.alert）：
 *     circuit_breaker        熔断主动退出（绕过节流）
 *     heartbeat_lost         心跳丢失/进程假死（watchdog 发）
 *     sdk_pull_failed        存档 SDK 拉取连续失败 10 次
 *     adb_failed             ADB 服务异常（shell 连续失败 5 次）
 *     outbound_failed_final  出站消息重试耗尽转 failed
 *     task_cleanup_failed    群发助手历史卡片删除失败
 *   埋点类（仅 record，不发邮件）：
 *     task_failed_final / task_failed_missed / task_run_skipped_sent_today / ai_failed
 */
public final class AlertThrottle {

    /** 同一事件类型 30 分钟内只发一封邮件 */
    public static final long THROTTLE_MS = 30L * 60 * 1000;

    /**
     * 邮件类事件节流判断（P7）。
     * @return true=通过节流（调用方应发信并已 UPSERT last_sent_at）；false=节流命中（不刷新时间戳）
     */
    public static boolean shouldSend(String eventType) {
        synchronized (Db.LOCK) {
            long now = System.currentTimeMillis();
            try (PreparedStatement sel = Db.conn().prepareStatement(
                    "SELECT last_sent_at FROM alert_throttle WHERE event_type=?")) {
                sel.setString(1, eventType);
                try (ResultSet rs = sel.executeQuery()) {
                    if (rs.next()) {
                        long last = rs.getLong(1);
                        if (now - last < THROTTLE_MS) {
                            return false; // 节流命中：不发、不刷新时间戳（保持原锚点）
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[AlertThrottle] shouldSend 失败 " + eventType + ": " + e.getMessage());
                return false; // 保守：DB 异常不发
            }
            // 通过节流：刷新 last_sent_at=now
            record(eventType);
            return true;
        }
    }

    /** 记录某事件类型触发（UPSERT last_sent_at）。埋点类事件直接用；邮件类事件由 shouldSend 内部调。 */
    public static void record(String eventType) {
        synchronized (Db.LOCK) {
            try (PreparedStatement ps = Db.conn().prepareStatement(
                    "INSERT INTO alert_throttle (event_type, last_sent_at) VALUES (?, ?) "
                    + "ON CONFLICT(event_type) DO UPDATE SET last_sent_at=excluded.last_sent_at")) {
                ps.setString(1, eventType);
                ps.setLong(2, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (Exception e) {
                System.err.println("[AlertThrottle] 埋点失败 " + eventType + ": " + e.getMessage());
            }
        }
    }

    private AlertThrottle() {}
}
