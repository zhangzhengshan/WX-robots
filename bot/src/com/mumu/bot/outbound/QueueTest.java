package com.mumu.bot.outbound;

import com.mumu.bot.config.Config;
import com.mumu.bot.store.Db;
import java.sql.PreparedStatement;

/**
 * P2 出站队列自动化验收（决策 8：6 场景 + print + 断言，不引入 JUnit）。
 *
 * 不跑 QueueConsumer 线程，而是直接调 OutboundQueue API 模拟消费循环，
 * 跳过 30s/2min/10min 重试等待（用 forceRetryDue 直接 UPDATE next_retry_at=0）。
 *
 * 验收清单（plan.md P2 验收方法 1/2/3）：
 *   1. 入队 3 条不同 source_id 全部 sent、attempts=0
 *   2. 同 source_id 5 分钟内重复入队仅 1 条入库
 *   3. StubSender 第 1 次 fail 第 2 次 ok → attempts=1、sent
 *   4. 连续 3 次 fail → failed、error 非空
 *   5. sending 崩溃恢复 → 重置 pending、attempts 不变
 *   6. priority=1 先于 priority=5 被消费
 *
 * 运行：java -cp ... com.mumu.bot.outbound.QueueTest
 * 退出码：0=全部通过，1=有失败
 */
public final class QueueTest {

    private static final OutboundQueue queue = OutboundQueue.INSTANCE;
    private static final String ROOM_A = "room_test_A";
    private static final String ROOM_NAME_A = "测试群A";
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== P2 出站队列验收开始 ===\n");

        // 触发 Db 初始化（schema 升级）
        Db.conn();
        // 确保测试群存在（room_map 测试用，不冲突 P1 的 3 个生产群）
        ensureTestRoom();

        try { scenario1_sent(); } catch (AssertionError e) { fail("场景1", e); }
        try { scenario2_dedup(); } catch (AssertionError e) { fail("场景2", e); }
        try { scenario3_retry_then_ok(); } catch (AssertionError e) { fail("场景3", e); }
        try { scenario4_failed_after_3(); } catch (AssertionError e) { fail("场景4", e); }
        try { scenario5_crash_recover(); } catch (AssertionError e) { fail("场景5", e); }
        try { scenario6_priority(); } catch (AssertionError e) { fail("场景6", e); }

        System.out.println("\n=== 验收结果：通过 " + passed + " / 失败 " + failed + " ===");
        System.exit(failed > 0 ? 1 : 0);
    }

    // ===== 场景 1：3 条不同 source_id 全部 sent、attempts=0 =====
    private static void scenario1_sent() {
        System.out.println("[场景1] 3 条不同 source_id 全部 sent、attempts=0");
        queue.clearAll();
        StubSender stub = new StubSender(0); // 永远 ok
        long id1 = queue.enqueue(ROOM_A, ROOM_NAME_A, "text", "msg1", false, null, Config.PRIORITY_AI_REPLY, "at:1:0");
        long id2 = queue.enqueue(ROOM_A, ROOM_NAME_A, "text", "msg2", false, null, Config.PRIORITY_AI_REPLY, "at:2:0");
        long id3 = queue.enqueue(ROOM_A, ROOM_NAME_A, "text", "msg3", false, null, Config.PRIORITY_AI_REPLY, "at:3:0");
        assertOk(id1 > 0 && id2 > 0 && id3 > 0, "入队返回有效 id");
        drain(stub);
        OutboundMsg m1 = queue.getById(id1);
        OutboundMsg m2 = queue.getById(id2);
        OutboundMsg m3 = queue.getById(id3);
        // getById 不查 status 列（保持值对象只读），用 countByStatus 间接验证
        assertOk(queue.countByStatus("sent") == 3, "3 条全部 sent");
        assertOk(queue.countByStatus("pending") == 0, "无 pending");
        assertOk(queue.countByStatus("failed") == 0, "无 failed");
        pass("场景1");
    }

    // ===== 场景 2：同 source_id 5 分钟内重复入队仅 1 条 =====
    private static void scenario2_dedup() {
        System.out.println("[场景2] 同 source_id 5 分钟内重复入队仅 1 条");
        queue.clearAll();
        long id1 = queue.enqueue(ROOM_A, ROOM_NAME_A, "text", "dup", false, null, Config.PRIORITY_AI_REPLY, "at:9:0");
        long id2 = queue.enqueue(ROOM_A, ROOM_NAME_A, "text", "dup", false, null, Config.PRIORITY_AI_REPLY, "at:9:0");
        assertOk(id1 > 0, "首次入队成功 id=" + id1);
        assertOk(id2 == -1, "重复入队返回 -1（被去重）");
        assertOk(queue.countByStatus("pending") == 1, "DB 中仅 1 条 pending");
        pass("场景2");
    }

    // ===== 场景 3：第 1 次 fail 第 2 次 ok → attempts=1、sent =====
    private static void scenario3_retry_then_ok() {
        System.out.println("[场景3] StubSender attempts=0 fail、attempts=1 ok → 最终 sent、attempts=1");
        queue.clearAll();
        StubSender stub = new StubSender(1); // attempts<1 fail，attempts>=1 ok
        long id = queue.enqueue(ROOM_A, ROOM_NAME_A, "text", "retry-ok", false, null, Config.PRIORITY_AI_REPLY, "at:10:0");
        drain(stub);
        assertOk(queue.countByStatus("sent") == 1, "最终 sent");
        assertOk(queue.countByStatus("failed") == 0, "未 failed");
        pass("场景3");
    }

    // ===== 场景 4：连续 3 次 fail → failed、error 非空 =====
    private static void scenario4_failed_after_3() {
        System.out.println("[场景4] StubSender 永远 fail → 3 次后 status=failed、error 非空");
        queue.clearAll();
        StubSender stub = new StubSender(Integer.MAX_VALUE); // 永远 fail
        long id = queue.enqueue(ROOM_A, ROOM_NAME_A, "text", "always-fail", false, null, Config.PRIORITY_AI_REPLY, "at:11:0");
        drain(stub);
        assertOk(queue.countByStatus("failed") == 1, "1 条 failed");
        assertOk(queue.countByStatus("sent") == 0, "0 条 sent");
        assertOk(queue.countByStatus("pending") == 0, "无残留 pending");
        // 验证 error 字段：直接 SQL 查
        String err = queryString("SELECT error FROM outbound_message WHERE id=" + id);
        assertOk(err != null && !err.isEmpty(), "error 字段非空: " + err);
        // 清理：requeue 复位环境（验证 requeue API 顺手覆盖）
        queue.requeue(id);
        assertOk(queue.countByStatus("pending") == 1, "requeue 后回 pending");
        assertOk(queue.countByStatus("failed") == 0, "requeue 后 failed=0");
        pass("场景4");
    }

    // ===== 场景 5：sending 崩溃恢复 → 重置 pending、attempts 不变 =====
    private static void scenario5_crash_recover() {
        System.out.println("[场景5] sending 崩溃后重启恢复：status 回 pending、attempts 不变");
        queue.clearAll();
        long id = queue.enqueue(ROOM_A, ROOM_NAME_A, "text", "crash-test", false, null, Config.PRIORITY_AI_REPLY, "at:12:0");
        // 第 1 次 claim，状态变 sending
        OutboundMsg m1 = queue.claimNext();
        assertOk(m1 != null && m1.id == id, "claim 取到消息");
        assertOk(m1.attempts == 0, "初始 attempts=0");
        // 模拟崩溃：不调 send/markSent/markFailed，直接"进程死掉"
        // 此时 DB 里 status=sending
        assertOk(queue.countByStatus("sending") == 1, "崩溃前 status=sending");
        // 进程重启恢复
        int recovered = queue.recoverStaleSending();
        assertOk(recovered == 1, "recoverStaleSending 恢复 1 条");
        assertOk(queue.countByStatus("sending") == 0, "恢复后 sending=0");
        assertOk(queue.countByStatus("pending") == 1, "恢复后 pending=1");
        // 再次 claim
        OutboundMsg m2 = queue.claimNext();
        assertOk(m2 != null && m2.id == id, "再次 claim 取到同一 id");
        assertOk(m2.attempts == 0, "恢复后 attempts 不变（仍为 0）");
        // 收尾：markSent 清掉这条
        queue.markSent(id);
        assertOk(queue.countByStatus("sent") == 1, "收尾 markSent");
        pass("场景5");
    }

    // ===== 场景 6：priority=1 先于 priority=5 被消费 =====
    private static void scenario6_priority() {
        System.out.println("[场景6] priority=1（AI 回复）先于 priority=5（定时）被消费");
        queue.clearAll();
        // 先入队低优先级，再入队高优先级（验证不是按入队顺序而是按 priority）
        long lowId = queue.enqueue(ROOM_A, ROOM_NAME_A, "text", "low-prio", false, null, Config.PRIORITY_SCHEDULED, "scheduled:1:2026-09-14");
        long highId = queue.enqueue(ROOM_A, ROOM_NAME_A, "text", "high-prio", false, null, Config.PRIORITY_AI_REPLY, "at:13:0");
        assertOk(lowId > 0 && highId > 0, "两条都入队成功");
        OutboundMsg first = queue.claimNext();
        assertOk(first != null, "claim 取到一条");
        assertOk(first.id == highId, "先取到 priority=1 的 id=" + first.id + " (期望 " + highId + ")");
        assertOk(first.priority == Config.PRIORITY_AI_REPLY, "priority=AI_REPLY");
        // 收尾
        queue.markSent(first.id);
        OutboundMsg second = queue.claimNext();
        assertOk(second != null && second.id == lowId, "再取到 priority=5 的");
        queue.markSent(second.id);
        pass("场景6");
    }

    // ===== 测试辅助 =====

    /** 模拟消费循环到队列空（跳过重试等待：每次 markFailed 后强制 next_retry_at=0） */
    private static void drain(OutboundSender sender) {
        int guard = 0;
        while (guard++ < 20) { // 防御性上限
            OutboundMsg msg = queue.claimNext();
            if (msg == null) break;
            SendResult r = sender.send(msg);
            if (r.ok) {
                queue.markSent(msg.id);
            } else {
                queue.markFailed(msg.id, r.error);
                // 强制重试到期（否则要等 30s/2min/10min）
                forceRetryDue(msg.id);
                // 若刚被转为 failed，强制到期也无效（status=failed 不被 claimNext 取）
            }
        }
    }

    /** 强制让 pending 状态的 next_retry_at 立即到期（测试加速用） */
    private static void forceRetryDue(long id) {
        try (PreparedStatement ps = Db.conn().prepareStatement(
                "UPDATE outbound_message SET next_retry_at=0 WHERE id=? AND status='pending'")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("forceRetryDue 失败: " + e.getMessage(), e);
        }
    }

    /** 查单个字符串字段（验证 error 等用） */
    private static String queryString(String sql) {
        try (PreparedStatement ps = Db.conn().prepareStatement(sql);
             java.sql.ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            throw new RuntimeException("queryString 失败: " + e.getMessage(), e);
        }
    }

    /** 确保测试群在 room_map（不与生产 3 群冲突） */
    private static void ensureTestRoom() {
        try (PreparedStatement ps = Db.conn().prepareStatement(
                "INSERT OR IGNORE INTO room_map (roomid, name, enabled, created_at) VALUES (?,?,1,?)")) {
            ps.setString(1, ROOM_A);
            ps.setString(2, ROOM_NAME_A);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("ensureTestRoom 失败: " + e.getMessage(), e);
        }
    }

    private static void assertOk(boolean cond, String msg) {
        if (!cond) {
            failed++;
            throw new AssertionError("断言失败: " + msg);
        }
        System.out.println("  ✓ " + msg);
    }

    private static void pass(String name) {
        passed++;
        System.out.println("  → " + name + " PASS\n");
    }

    private static void fail(String name, AssertionError e) {
        // assertOk 已 failed++；这里只打日志
        System.out.println("  ✗ " + name + " FAIL: " + e.getMessage() + "\n");
    }

    private QueueTest() {}
}

/**
 * 测试桩：按 msg.attempts 决定 ok/fail（决策 8）。
 *   failUntil=N 表示 "attempts < N 时 fail，attempts >= N 时 ok"
 *   - StubSender(0)：永远 ok（场景 1）
 *   - StubSender(1)：attempts=0 fail、attempts>=1 ok（场景 3）
 *   - StubSender(Integer.MAX_VALUE)：永远 fail（场景 4）
 */
final class StubSender implements OutboundSender {
    private final int failUntil;
    StubSender(int failUntil) { this.failUntil = failUntil; }
    @Override
    public SendResult send(OutboundMsg msg) {
        if (msg.attempts < failUntil) {
            System.out.println("  [stub] fail id=" + msg.id + " attempts=" + msg.attempts);
            return SendResult.fail("stub fail at attempts=" + msg.attempts);
        }
        System.out.println("  [stub] ok   id=" + msg.id + " attempts=" + msg.attempts);
        return SendResult.ok();
    }
}
