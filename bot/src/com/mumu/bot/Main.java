package com.mumu.bot;

import com.mumu.bot.ai.AiService;
import com.mumu.bot.alert.CircuitBreaker;
import com.mumu.bot.archive.ArchiveClient;
import com.mumu.bot.config.Config;
import com.mumu.bot.contact.ContactApiClient;
import com.mumu.bot.contact.RosterSyncService;
import com.mumu.bot.kb.KbSyncService;
import com.mumu.bot.outbound.QueueConsumer;
import com.mumu.bot.outbound.ReplyDispatcher;
import com.mumu.bot.outbound.adb.AdbSender;
import com.mumu.bot.outbound.adb.WeChatUi;
import com.mumu.bot.schedule.TaskScheduler;
import com.mumu.bot.store.Db;
import com.mumu.bot.store.RoomMap;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

/**
 * 企微客服机器人 —— 装配入口。
 *
 * 入站：官方会话存档 SDK（只读）-> 多群路由 -> 检测 @提及 -> AI worker 云端问答（P3）
 * 出站：纯 ADB UI 自动化，不装 agent、不 hook、不碰协议（见 ADR-0001）
 * 名册：客户联系 API 启动全量 + 5 分钟轮询同步（见 ADR-0005）
 * 知识库：data/kb 目录（docx/xlsx/txt/md）启动同步进 kb_document，全量拼入提示词（见 ADR-0003/0007）
 *
 * 看门狗为 jar 外独立脚本：watchdog/watchdog.ps1（不在 Java 工程内）
 */
public final class Main {

    public static void main(String[] args) {
        // P7：全局未捕获异常处理器——Error（OOM/StackOverflow 等）→ 熔断退出
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            if (e instanceof Error) {
                System.err.println("⛔ 未捕获 Error，熔断退出（线程 " + t.getName() + "）: " + e);
                e.printStackTrace();
                CircuitBreaker.trip("未捕获 Error: " + e);
                System.exit(1);
            } else {
                System.err.println("未捕获异常（线程 " + t.getName() + "）: " + e);
                e.printStackTrace();
            }
        });

        // 0. 先安装按天滚动日志（见 ADR-0010 配套决策 / P5）
        com.mumu.bot.util.DailyLogRotator.install();

        Config.TEMP_DIR.mkdirs();
        // P7：数据库初始化失败 → 熔断（无法运行，watchdog 反复重启无意义）
        try {
            Db.conn(); // 初始化 SQLite（建表 + room_map 种子）
        } catch (Throwable t) {
            System.err.println("⛔ 数据库初始化失败，熔断退出: " + t.getMessage());
            t.printStackTrace();
            CircuitBreaker.trip("数据库初始化失败: " + t.getMessage());
            System.exit(1);
        }

        // P7：心跳线程——每 10s 写 data/heartbeat，watchdog 据此检测假死（超时 80s）
        startHeartbeat();

        log("=== 企微客服机器人 ===");
        log("作用群:");
        for (RoomMap.Room r : RoomMap.enabledRooms()) {
            log("  - " + r.name + " (" + r.roomid + ")");
        }
        log("我的 ID: " + Config.MY_USERID);
        log("");

        // P5：Db 初始化后立即起 AdminServer（早于 SDK/ADB），ADB 失败不影响管理端
        com.mumu.bot.admin.AdminServer.start();

        ArchiveClient archive = new ArchiveClient();
        WeChatUi ui = new WeChatUi();

        ContactApiClient contactApi = new ContactApiClient();
        RosterSyncService roster = new RosterSyncService(contactApi);

        // P3：AI worker（构造时校验 ai.* 配置与提示词文件，快速失败）
        AiService ai = new AiService();
        ReplyDispatcher dispatcher = new ReplyDispatcher(ai);

        try {
            // 0. 知识库：data/kb 目录同步进 kb_document（改文件后重启即生效，也可跑 KbImport 手动重导）
            log("[启动] 同步知识库目录 " + Config.KB_DIR + " ...");
            new KbSyncService().sync(false);

            // 1. 名册：先全量同步，再启动 5 分钟轮询
            log("[启动] 同步群成员名册...");
            roster.syncAllRooms();
            roster.startPolling();

            // 2. 初始化 SDK + RSA（lastSeq 从 kv 断点恢复）
            archive.setResolver(roster::resolveUnknown);
            archive.init();

            // 3. 启动时自动探测 MuMu ADB 端口（扫描 16384/16416/16448/16480，命中第一个即停）
            Config.detectDevice();

            // 4. 连接 ADB 设备 + 关闭动画（MuMu 12 上动画会让 uiautomator 等不到 idle，dump 必失败）
            com.mumu.bot.outbound.adb.Adb.connectDevice();
            com.mumu.bot.outbound.adb.Adb.disableAnimations();

            // ADR-0013：自愈清理残留的 mumu_down 哨兵。
            // 正常流程下 watchdog 在 MuMu 恢复 + ADB 就绪后会删除哨兵；但如果 watchdog
            // 自身在中途崩溃（如机器断电后只有 bot 自启），哨兵会永久残留，导致 Adb.shell
            // 失败时永远跳过 adb_failed 计数。此处 ADB 已成功连接说明 MuMu 已就绪，
            // 主动清理哨兵避免误抑制。
            File mumuDownSentinel = new File("data/mumu_down");
            if (mumuDownSentinel.exists()) {
                log("[启动] 检测到残留 data/mumu_down 哨兵，ADB 已就绪，主动清理");
                if (!mumuDownSentinel.delete()) {
                    log("[启动] 警告：data/mumu_down 删除失败，可能继续抑制 adb_failed 计数");
                }
            }

            // Admin /config 主动重启哨兵：如果 watchdog 在上一轮 bot exit 后、重启前崩溃，
            // 哨兵会残留。此处清理避免 watchdog 下次启动时立即重启 bot（哨兵已无意义）。
            File restartSentinel = new File("data/restart_requested");
            if (restartSentinel.exists()) {
                log("[启动] 清理残留 data/restart_requested 哨兵（watchdog 可能在上一轮重启间崩溃）");
                restartSentinel.delete();
            }

            ui.ensureForeground();

            // 4. 启动出站消费线程（P2：单线程串行，AI 回复 priority=1 优先）
            //    P4：定时宣传扫描复用该线程（TaskScheduler 30s 节流），与 ADB 出站共用 SESSION_LOCK
            TaskScheduler taskScheduler = new TaskScheduler(contactApi, ui);
            QueueConsumer consumer = new QueueConsumer(
                new AdbSender(ui), taskScheduler::tick);
            consumer.start();

            // 5. 启动 AI worker（@提及 -> 云端 LLM -> 整段回复入队，见 ADR-0006）
            ai.start();

            // 6. 启动存档监听（@提及入站 -> ReplyDispatcher -> AI worker -> QueueConsumer 消费）
            archive.start(dispatcher::enqueue);

            // 7. 主线程阻塞（worker 都在 daemon 线程内运行）
            Thread.currentThread().join();
        } catch (Exception e) {
            log("❌ 异常: " + e.getMessage());
            e.printStackTrace();
        } finally {
            archive.destroy();
            roster.stop();
        }
    }

    // ===== 工具 =====

    /**
     * P7：心跳守护线程。每 10s 写 data/heartbeat（epoch ms）。
     * watchdog.ps1 检测该文件 mtime 超过 80s → 判定进程假死 → kill + 告警 + 重启。
     */
    private static void startHeartbeat() {
        File hb = new File("data/heartbeat");
        hb.getParentFile().mkdirs();
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Files.writeString(hb.toPath(), String.valueOf(System.currentTimeMillis()),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } catch (Exception e) {
                    System.err.println("[heartbeat] 写入失败: " + e.getMessage());
                }
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "heartbeat");
        t.setDaemon(true);
        t.start();
    }

    private static void log(String msg) {
        System.out.println(msg);
        System.out.flush();
    }

    private Main() {}
}
