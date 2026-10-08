package com.mumu.bot.outbound;

import com.mumu.bot.config.Config;
import com.mumu.bot.store.Db;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 出站队列入口：入队（带 source_id 5 分钟去重）+ 状态机 UPDATE + 重启恢复 + requeue。
 *
 * 状态机：pending -> sending -> sent / failed
 *   入队：pending
 *   认领：UPDATE status='sending' WHERE id=? AND status='pending'（看 affected rows，单线程下永远=1，乐观锁为 P5 管理端并发预留）
 *   成功：UPDATE status='sent', sent_at=?, error=NULL
 *   失败：attempts+1，attempts<MAX 则 next_retry_at=now+RETRY_BACKOFF[attempts-1]；耗尽则 status='failed', error=?
 *   重启恢复：UPDATE SET status='pending', next_retry_at=NULL WHERE status='sending'（attempts 不变）
 *   requeue：UPDATE SET status='pending', attempts=0, error=NULL, next_retry_at=NULL WHERE id=?
 *
 * 去重（见 CONTEXT.md「去重」）：5 分钟窗口内同一 source_id 只入队一次。
 *   - 用 SELECT 1 FROM outbound_message WHERE dedup_hash=? AND created_at > now-DEDUP_WINDOW_MS
 *   - 不按 content 去重；同群不同用户问相同问题不去重；同一来源多段回复独立去重
 *
 * 跨线程事务：写操作全部在 synchronized(Db.LOCK) 内。
 */
public final class OutboundQueue {

    /**
     * 入队一条出站消息。返回入库后的自增 id；source_id 在去重窗口内重复入队则返回 -1。
     * roomName 传入是为节省一次 room_map 查询（QueueConsumer 直接传给 AdbSender）。
     */
    public long enqueue(String roomid, String roomName, String msgType, String content,
                        boolean isMention, String mentionName, int priority, String sourceId) {
        long now = System.currentTimeMillis();
        long since = now - Config.DEDUP_WINDOW_MS;
        synchronized (Db.LOCK) {
            Connection c = Db.conn();
            try {
                // 去重检查：同 source_id 5 分钟内已存在则跳过
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT 1 FROM outbound_message WHERE dedup_hash=? AND created_at > ? LIMIT 1")) {
                    ps.setString(1, sourceId);
                    ps.setLong(2, since);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return -1; // 去重命中
                        }
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO outbound_message " +
                        "(roomid, msg_type, content, is_mention, mention_name, status, priority, attempts, dedup_hash, created_at) " +
                        "VALUES (?,?,?,?,?, 'pending', ?, 0, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, roomid);
                    ps.setString(2, msgType);
                    ps.setString(3, content);
                    ps.setInt(4, isMention ? 1 : 0);
                    ps.setString(5, mentionName);
                    ps.setInt(6, priority);
                    ps.setString(7, sourceId);
                    ps.setLong(8, now);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        return keys.next() ? keys.getLong(1) : -1;
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("入队失败: " + e.getMessage(), e);
            }
        }
    }

    /**
     * 去重窗预查（P3：AI worker 在调 LLM 之前先查一次，命中则直接跳过、不浪费一次云端调用）。
     * 与 enqueue 内的去重检查同口径：5 分钟窗口内同一 source_id 已存在即 true。
     */
    public boolean existsInWindow(String sourceId) {
        try (PreparedStatement ps = Db.conn().prepareStatement(
                "SELECT 1 FROM outbound_message WHERE dedup_hash=? AND created_at > ? LIMIT 1")) {
            ps.setString(1, sourceId);
            ps.setLong(2, System.currentTimeMillis() - Config.DEDUP_WINDOW_MS);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            throw new RuntimeException("去重预查失败: " + e.getMessage(), e);
        }
    }

    /**
     * 取下一条待发送消息（pending 且 next_retry_at 已到），按 priority 升序、created_at 升序。
     * 认领为 sending 状态；认领失败（并发抢占）返回 null。
     */
    public OutboundMsg claimNext() {
        synchronized (Db.LOCK) {
            Connection c = Db.conn();
            try {
                long now = System.currentTimeMillis();
                long id;
                String roomid, roomName, msgType, content, mentionName, sourceId;
                boolean isMention;
                int priority, attempts;

                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id, roomid, msg_type, content, is_mention, mention_name, " +
                        "       priority, attempts, dedup_hash " +
                        "FROM outbound_message " +
                        "WHERE status='pending' AND (next_retry_at IS NULL OR next_retry_at <= ?) " +
                        "ORDER BY priority ASC, created_at ASC LIMIT 1")) {
                    ps.setLong(1, now);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) return null;
                        id          = rs.getLong(1);
                        roomid      = rs.getString(2);
                        msgType     = rs.getString(3);
                        content     = rs.getString(4);
                        isMention   = rs.getInt(5) == 1;
                        mentionName = rs.getString(6);
                        priority    = rs.getInt(7);
                        attempts    = rs.getInt(8);
                        sourceId    = rs.getString(9);
                    }
                }

                // 查群名（room_map）；查不到用 roomid 兜底，让 AdbSender 进群失败再走跳过逻辑
                roomName = roomid;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT name FROM room_map WHERE roomid=?")) {
                    ps.setString(1, roomid);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) roomName = rs.getString(1);
                    }
                }

                // 认领（乐观锁：WHERE status='pending' 防并发抢）
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE outbound_message SET status='sending' WHERE id=? AND status='pending'")) {
                    ps.setLong(1, id);
                    int rows = ps.executeUpdate();
                    if (rows != 1) return null; // 被并发抢占
                }

                return new OutboundMsg(id, roomid, roomName, msgType, content,
                                        isMention, mentionName, priority, attempts, sourceId);
            } catch (Exception e) {
                throw new RuntimeException("claimNext 失败: " + e.getMessage(), e);
            }
        }
    }

    /** 发送成功：sent_at 落库，清空 error */
    public void markSent(long id) {
        synchronized (Db.LOCK) {
            try (PreparedStatement ps = Db.conn().prepareStatement(
                    "UPDATE outbound_message SET status='sent', sent_at=?, error=NULL WHERE id=?")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setLong(2, id);
                ps.executeUpdate();
            } catch (Exception e) {
                throw new RuntimeException("markSent 失败: " + e.getMessage(), e);
            }
        }
    }

    /**
     * 发送失败：attempts+1。
     *   attempts<MAX：next_retry_at = now + RETRY_BACKOFF[attempts]（attempts 已 +1，索引 -1 取对应退避）
     *   attempts>=MAX：status='failed'，error 字段填失败原因
     */
    public void markFailed(long id, String error) {
        synchronized (Db.LOCK) {
            try {
                // 读当前 attempts（避免并发覆盖）
                int attempts;
                try (PreparedStatement ps = Db.conn().prepareStatement(
                        "SELECT attempts FROM outbound_message WHERE id=?")) {
                    ps.setLong(1, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        attempts = rs.next() ? rs.getInt(1) : 0;
                    }
                }
                attempts += 1;
                if (attempts < Config.MAX_ATTEMPTS) {
                    long backoff = Config.RETRY_BACKOFF_MS[
                        Math.min(attempts - 1, Config.RETRY_BACKOFF_MS.length - 1)];
                    try (PreparedStatement ps = Db.conn().prepareStatement(
                            "UPDATE outbound_message SET status='pending', attempts=?, " +
                            "next_retry_at=?, error=? WHERE id=?")) {
                        ps.setInt(1, attempts);
                        ps.setLong(2, System.currentTimeMillis() + backoff);
                        ps.setString(3, error);
                        ps.setLong(4, id);
                        ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = Db.conn().prepareStatement(
                            "UPDATE outbound_message SET status='failed', attempts=?, " +
                            "error=?, next_retry_at=NULL WHERE id=?")) {
                        ps.setInt(1, attempts);
                        ps.setString(2, error);
                        ps.setLong(3, id);
                        ps.executeUpdate();
                    }
                    // P7：出站消息重试耗尽 → 邮件告警（30 分钟节流防刷屏）
                    com.mumu.bot.alert.MailAlerter.alert(
                        com.mumu.bot.alert.MailAlerter.OUTBOUND_FAILED_FINAL,
                        "出站消息 id=" + id + " 重试 " + attempts + " 次耗尽转 failed: " + error);
                }
            } catch (Exception e) {
                throw new RuntimeException("markFailed 失败: " + e.getMessage(), e);
            }
        }
    }

    /**
     * 进程重启恢复：所有 sending 状态重置为 pending，attempts 不变（中断不算失败次数）。
     * 在 QueueConsumer 启动时调用一次。
     */
    public int recoverStaleSending() {
        synchronized (Db.LOCK) {
            try (PreparedStatement ps = Db.conn().prepareStatement(
                    "UPDATE outbound_message SET status='pending', next_retry_at=NULL WHERE status='sending'")) {
                return ps.executeUpdate();
            } catch (Exception e) {
                throw new RuntimeException("recoverStaleSending 失败: " + e.getMessage(), e);
            }
        }
    }

    /**
     * 重放失败消息（P5 管理端按钮 / QueueTest 场景 4 后恢复环境用）：
     * 状态回 pending、attempts 清零、清 error / next_retry_at。
     */
    public void requeue(long id) {
        synchronized (Db.LOCK) {
            try (PreparedStatement ps = Db.conn().prepareStatement(
                    "UPDATE outbound_message SET status='pending', attempts=0, " +
                    "error=NULL, next_retry_at=NULL WHERE id=?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            } catch (Exception e) {
                throw new RuntimeException("requeue 失败: " + e.getMessage(), e);
            }
        }
    }

    /** 测试/管理端用：按 status 统计条数 */
    public int countByStatus(String status) {
        try (PreparedStatement ps = Db.conn().prepareStatement(
                "SELECT COUNT(*) FROM outbound_message WHERE status=?")) {
            ps.setString(1, status);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            throw new RuntimeException("countByStatus 失败: " + e.getMessage(), e);
        }
    }

    /** 测试/管理端用：取一条 by id（验证字段用） */
    public OutboundMsg getById(long id) {
        try (PreparedStatement ps = Db.conn().prepareStatement(
                "SELECT id, roomid, msg_type, content, is_mention, mention_name, " +
                "       priority, attempts, dedup_hash, status " +
                "FROM outbound_message WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new OutboundMsg(
                    rs.getLong(1), rs.getString(2), null, rs.getString(3),
                    rs.getString(4), rs.getInt(5) == 1, rs.getString(6),
                    rs.getInt(7), rs.getInt(8), rs.getString(9));
            }
        } catch (Exception e) {
            throw new RuntimeException("getById 失败: " + e.getMessage(), e);
        }
    }

    /** 测试/管理端用：清空 outbound_message（QueueTest 每个场景开头复位） */
    public void clearAll() {
        synchronized (Db.LOCK) {
            try (Statement st = Db.conn().createStatement()) {
                st.executeUpdate("DELETE FROM outbound_message");
                st.executeUpdate("DELETE FROM sqlite_sequence WHERE name='outbound_message'");
            } catch (Exception e) {
                throw new RuntimeException("clearAll 失败: " + e.getMessage(), e);
            }
        }
    }

    private OutboundQueue() {}
    public static final OutboundQueue INSTANCE = new OutboundQueue();
}
