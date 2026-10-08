package com.mumu.bot.outbound;

import com.mumu.bot.config.Config;
import java.io.File;

/**
 * 单线程消费循环（P2 决策 2/3/13）：
 *   1. 启动时调用 recoverStaleSending() 把 sending 重置为 pending
 *   2. 启动时清理一次 >30 天的截图
 *   3. 每 QUEUE_POLL_INTERVAL_MS 轮询 DB 取一条 pending（认领为 sending）
 *   4. 调 OutboundSender.send(msg)：
 *        ok=true  → markSent
 *        ok=false → markFailed（attempts+1，未耗尽则等下次轮询到期重试，耗尽则转 failed）
 *   5. 距上次截图清理 >6h，顺手清一次
 *
 * 全局单线程（ADR-0002），AI 回复 priority=1 优先于定时任务 priority=5。
 * 消费循环可通过 stop() 优雅停止（QueueTest 模拟崩溃用）。
 */
public final class QueueConsumer implements Runnable {

    private final OutboundQueue queue = OutboundQueue.INSTANCE;
    private final OutboundSender sender;
    /** P4：每轮循环顺手调一次（TaskScheduler 内部 30s 节流），复用消费线程不另起并发面 */
    private final Runnable tickHook;
    private volatile boolean running = true;
    private volatile long lastScreenshotCleanAt = 0;
    private Thread thread;

    public QueueConsumer(OutboundSender sender) {
        this(sender, null);
    }

    public QueueConsumer(OutboundSender sender, Runnable tickHook) {
        this.sender = sender;
        this.tickHook = tickHook;
    }

    /** 启动消费线程（非阻塞，后台 daemon） */
    public void start() {
        // 1. 进程重启恢复
        int recovered = queue.recoverStaleSending();
        if (recovered > 0) {
            log("  🔄 恢复 " + recovered + " 条 sending 消息为 pending");
        }
        // 2. 启动清理一次
        cleanScreenshots();
        // 3. 后台线程
        thread = new Thread(this, "queue-consumer");
        thread.setDaemon(true);
        thread.start();
    }

    /** 优雅停止（QueueTest 模拟崩溃用：running=false 后线程会在下次循环检查时退出） */
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /** 等待消费线程处理完所有 pending（QueueTest 用：run 一轮后调用，确保跑完） */
    public void waitDrain(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (queue.countByStatus("pending") == 0
                && queue.countByStatus("sending") == 0) {
                return;
            }
            sleep(200);
        }
    }

    @Override
    public void run() {
        while (running) {
            try {
                OutboundMsg msg = queue.claimNext();
                if (msg != null) {
                    log("📤 取出 " + msg);
                    SendResult r = sender.send(msg);
                    if (r.ok) {
                        queue.markSent(msg.id);
                    } else {
                        queue.markFailed(msg.id, r.error);
                        log("  🔄 重试或转 failed（看 attempts 是否达 " + Config.MAX_ATTEMPTS + "）");
                    }
                } else {
                    sleep(Config.QUEUE_POLL_INTERVAL_MS);
                }

                // 每 6h 顺手清一次截图
                long now = System.currentTimeMillis();
                if (now - lastScreenshotCleanAt > Config.SCREENSHOT_CLEAN_INTERVAL_MS) {
                    cleanScreenshots();
                    lastScreenshotCleanAt = now;
                }

                // P4：定时宣传扫描（内部 30s 节流）
                if (tickHook != null) {
                    tickHook.run();
                }
            } catch (Exception e) {
                log("  ❌ 消费异常: " + e.getMessage());
                sleep(Config.QUEUE_POLL_INTERVAL_MS);
            }
        }
        log("  ⏹ QueueConsumer 已停止");
    }

    /** 清理 >30 天的截图（按文件 mtime） */
    private void cleanScreenshots() {
        try {
            File dir = Config.SCREENSHOT_DIR;
            if (!dir.exists()) return;
            File[] files = dir.listFiles();
            if (files == null) return;
            long cutoff = System.currentTimeMillis() - Config.SCREENSHOT_RETAIN_MS;
            int deleted = 0;
            for (File f : files) {
                if (f.isFile() && f.lastModified() < cutoff) {
                    if (f.delete()) deleted++;
                }
            }
            if (deleted > 0) {
                log("  🧹 清理 " + deleted + " 张 >30 天的截图");
            }
        } catch (Exception e) {
            log("  🧹 截图清理异常: " + e.getMessage());
        }
    }

    private static void log(String msg) {
        System.out.println(msg);
        System.out.flush();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
