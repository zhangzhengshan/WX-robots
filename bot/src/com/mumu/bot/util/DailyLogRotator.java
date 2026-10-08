package com.mumu.bot.util;

import com.mumu.bot.config.Config;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 按天滚动日志（P5，零新 jar）。
 *
 * - System.out / System.err 重定向到 logs/bot-yyyy-MM-dd.log + 同时输出控制台
 * - 跨天自动切割（next write 前检测日期变化）
 * - 保留最近 N 天（Config.LOG_RETENTION_DAYS），过期文件删除
 * - 线程安全：单 ReentrantLock 串行写入（与 Db.LOCK 同约定）
 * - P7 看门狗崩溃重启后续写当天文件（append 模式，不覆盖）
 *
 * 不实现：行缓冲清空策略、按大小切割、异步队列。System.out 用途是日志 + 启动信息，
 * 写入频率低（< 100 行/秒），同步落盘足够。
 */
public final class DailyLogRotator {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ReentrantLock LOCK = new ReentrantLock();
    private static final AtomicReference<PrintStream> FILE_OUT = new AtomicReference<>();
    private static volatile String currentDay;
    private static volatile long lastCleanup = 0L;
    private static final long CLEANUP_INTERVAL_MS = 6L * 60 * 60 * 1000;  // 6h 扫一次

    /** 安装按天滚动日志：System.out/err 同时写文件 + 控制台 */
    public static void install() {
        Config.LOG_DIR.mkdirs();
        rotate(true);  // 启动时 append 到当天文件（不覆盖）
        PrintStream filePs = FILE_OUT.get();
        PrintStream consoleOut = System.out;
        PrintStream consoleErr = System.err;
        System.setOut(new TeeStream(filePs, consoleOut));
        System.setErr(new TeeStream(filePs, consoleErr));
    }

    /** 写前检查日期变化 + 周期清理过期 */
    public static void checkRoll() {
        long now = System.currentTimeMillis();
        if (now - lastCleanup > CLEANUP_INTERVAL_MS) {
            lastCleanup = now;
            cleanupOldFiles();
        }
        String today = LocalDate.now().format(DATE_FMT);
        if (!today.equals(currentDay)) {
            rotate(true);
        }
    }

    /** 切换到当天文件（append 模式） */
    private static void rotate(boolean append) {
        LOCK.lock();
        try {
            String today = LocalDate.now().format(DATE_FMT);
            currentDay = today;
            File f = new File(Config.LOG_DIR, "bot-" + today + ".log");
            try {
                FileOutputStream fos = append
                    ? new FileOutputStream(f, true)
                    : new FileOutputStream(f, false);
                PrintStream old = FILE_OUT.getAndSet(
                    new PrintStream(fos, true, StandardCharsets.UTF_8));
                if (old != null) {
                    old.flush();
                    old.close();
                }
            } catch (IOException e) {
                throw new IllegalStateException("日志文件打开失败: " + f.getAbsolutePath(), e);
            }
        } finally {
            LOCK.unlock();
        }
    }

    /** 删除过期日志（保留最近 N 天） */
    private static void cleanupOldFiles() {
        File[] files = Config.LOG_DIR.listFiles((d, name) -> name.startsWith("bot-") && name.endsWith(".log"));
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - (long) Config.LOG_RETENTION_DAYS * 24 * 60 * 60 * 1000;
        for (File f : files) {
            if (f.lastModified() < cutoff) {
                try {
                    Files.deleteIfExists(f.toPath());
                } catch (IOException ignore) {}
            }
        }
    }

    /** 双输出流：文件 + 控制台 */
    private static final class TeeStream extends PrintStream {
        private final PrintStream file;
        private final PrintStream console;
        TeeStream(PrintStream file, PrintStream console) {
            super(console, true, StandardCharsets.UTF_8);
            this.file = file;
            this.console = console;
        }
        @Override
        public void write(int b) {
            LOCK.lock();
            try {
                file.write(b);
                console.write(b);
            } finally {
                LOCK.unlock();
            }
        }
        @Override
        public void write(byte[] buf, int off, int len) {
            LOCK.lock();
            try {
                file.write(buf, off, len);
                console.write(buf, off, len);
            } finally {
                LOCK.unlock();
            }
        }
        @Override
        public void flush() {
            LOCK.lock();
            try {
                file.flush();
                console.flush();
            } finally {
                LOCK.unlock();
            }
        }
    }

    private DailyLogRotator() {}
}
