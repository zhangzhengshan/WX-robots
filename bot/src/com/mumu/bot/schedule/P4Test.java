package com.mumu.bot.schedule;

import com.mumu.bot.config.Config;
import com.mumu.bot.contact.ContactApiClient;
import com.mumu.bot.outbound.adb.Adb;
import com.mumu.bot.outbound.adb.WeChatUi;
import com.mumu.bot.store.Db;
import com.mumu.bot.store.RoomMap;
import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

/**
 * P4 端到端验收入口（第一刀文本 + 第二刀媒体附件，见 ADR-0008 / ADR-0009）。
 *
 * 第一刀：真实调 add_msg_template 创建群发任务 → ADB 进群发助手 → 打开本次任务详情页校验
 *        内容 → 定位「发送」按钮。p4.dry-run=true 时最后一步不点击。
 *
 * 第二刀：7 个场景覆盖 image/file 类型、纯图任务、3 类 fatal、真发 1 次。
 *
 * 用法：
 *   run_p4_test.bat                  text 模式（第一刀，单条文本任务）
 *   run_p4_test.bat text 自定义文案   text 模式 + 自定义文案
 *   run_p4_test.bat media            第二刀全部 dry-run 场景（场景 1-6）
 *
 * 注：场景 7（真发带图）由 p4.dry-run=false 触发——把 dry-run 关掉再跑 media 模式即可。
 */
public final class P4Test {

    public static void main(String[] args) {
        String mode = (args.length > 0 && !args[0].isEmpty()) ? args[0].toLowerCase() : "text";

        Db.conn();
        System.out.println("=== P4Test（mode=" + mode + "）===");
        System.out.println("dry-run         : " + Config.P4_DRY_RUN
            + "(true=只校验不点击发送)");
        System.out.println("test 目标群白名单: "
            + (Config.TEST_TARGET_ROOMID.isEmpty() ? "(空=room_map 全部启用群)" : Config.TEST_TARGET_ROOMID));

        if ("media".equals(mode)) {
            runMediaScenarios();
        } else {
            runTextScenario(args);
        }
    }

    // ===== 第一刀：文本任务 =====

    private static void runTextScenario(String[] args) {
        String content;
        if (args.length > 1) {
            // args[0]=text，从 args[1] 起拼为自定义文案
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < args.length; i++) {
                if (i > 1) sb.append(' ');
                sb.append(args[i]);
            }
            content = sb.toString().trim();
        } else {
            content = "P4 定时宣传 dry-run 测试 "
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        System.out.println("消息内容         : " + content);

        String targetRooms = resolveTargetRooms();
        if (targetRooms == null) {
            System.out.println("❌ 无目标群：room_map 无启用群且未配 test.target-roomid");
            System.exit(2);
        }
        System.out.println("target_rooms    : " + targetRooms);

        ScheduledTask task = buildTask("P4Test-text-" + ts(), content, null, targetRooms);
        TaskStore store = new TaskStore();
        long taskId = store.insertTask(task);
        System.out.println("已创建一次性任务 id=" + taskId + " name=" + task.name);

        try {
            Adb.connectDevice();
            Adb.disableAnimations();
            WeChatUi ui = new WeChatUi();
            ui.ensureForeground();

            TaskScheduler scheduler = new TaskScheduler(new ContactApiClient(), ui);
            TaskRun run = scheduler.fireNow(taskId);

            printRun(run);
            passIf(run, Config.P4_DRY_RUN
                ? TaskRun.DRY_RUN_VERIFIED : TaskRun.SEND_CLICKED, null);
        } catch (Exception e) {
            System.out.println("❌ P4Test 异常: " + e.getMessage());
            e.printStackTrace();
            System.exit(2);
        }
    }

    // ===== 第二刀：媒体附件 7 个场景 =====

    private static void runMediaScenarios() {
        try {
            runMediaScenariosInner();
        } catch (Exception e) {
            System.out.println("❌ P4Test media 异常: " + e.getMessage());
            e.printStackTrace();
            System.exit(2);
        }
    }

    private static void runMediaScenariosInner() throws Exception {
        File mediaDir = new File("data/media");
        if (!mediaDir.exists()) mediaDir.mkdirs();
        File jpg = new File("data/media/test.jpg");
        File pdf = new File("data/media/test.pdf");
        File nonexistent = new File("data/media/nonexistent.jpg");
        File amr = new File("data/media/test_voice.amr");
        File oversized = new File("data/media/test_oversized.jpg");

        // 自动生成音频占位与超大图（不需要真实内容，校验在扩展名/大小层 fatal）
        if (!amr.exists()) {
            Files.write(amr.toPath(), new byte[]{0x00, 0x01, 0x02, 0x03});
            System.out.println("已生成音频占位 " + amr.getPath());
        }
        if (!oversized.exists() && jpg.exists()) {
            byte[] jpgBytes = Files.readAllBytes(jpg.toPath());
            try (OutputStream out = new java.io.FileOutputStream(oversized)) {
                long target = 11L * 1024 * 1024;
                long written = 0;
                while (written < target) {
                    out.write(jpgBytes);
                    written += jpgBytes.length;
                }
            }
            System.out.println("已生成超大图 " + oversized.getPath()
                + " (" + (oversized.length() / 1024 / 1024) + "MB)");
        }

        if (!jpg.exists()) {
            System.out.println("❌ 缺少 data/media/test.jpg，请业务方放置一张测试图片");
            System.exit(2);
        }
        if (!pdf.exists()) {
            System.out.println("⚠️ 缺少 data/media/test.pdf，跳过场景 3（file 类型）");
        }

        String targetRooms = resolveTargetRooms();
        if (targetRooms == null) {
            System.out.println("❌ 无目标群：room_map 无启用群且未配 test.target-roomid");
            System.exit(2);
        }
        System.out.println("target_rooms    : " + targetRooms);

        Adb.connectDevice();
        Adb.disableAnimations();
        WeChatUi ui = new WeChatUi();
        ui.ensureForeground();
        TaskScheduler scheduler = new TaskScheduler(new ContactApiClient(), ui);

        boolean allPass = true;

        // 场景 1：jpg + 文案 dry-run
        System.out.println("\n--- 场景 1：jpg + 文案 dry-run ---");
        TaskRun r1 = fireTask(scheduler, "P4Media-1 图+文", "图+文测试 " + ts(),
            "data/media/test.jpg", targetRooms);
        allPass &= passIf(r1, TaskRun.DRY_RUN_VERIFIED, null);

        // 场景 2：纯图任务 dry-run
        System.out.println("\n--- 场景 2：纯图任务（content=null）dry-run ---");
        TaskRun r2 = fireTask(scheduler, "P4Media-2 纯图", null,
            "data/media/test.jpg", targetRooms);
        allPass &= passIf(r2, TaskRun.DRY_RUN_VERIFIED, null);

        // 场景 3：pdf + 文案 dry-run
        if (pdf.exists()) {
            System.out.println("\n--- 场景 3：pdf + 文案 dry-run ---");
            TaskRun r3 = fireTask(scheduler, "P4Media-3 文件", "文件测试 " + ts(),
                "data/media/test.pdf", targetRooms);
            allPass &= passIf(r3, TaskRun.DRY_RUN_VERIFIED, null);
        }

        // 场景 4：不存在的文件 → fatal
        System.out.println("\n--- 场景 4：不存在的文件 → fatal ---");
        TaskRun r4 = fireTask(scheduler, "P4Media-4 不存在", "不存在测试 " + ts(),
            "data/media/nonexistent.jpg", targetRooms);
        allPass &= passIf(r4, TaskRun.FAILED_FINAL, "媒体文件不存在");

        // 场景 5：音频 → fatal
        System.out.println("\n--- 场景 5：音频 → fatal ---");
        TaskRun r5 = fireTask(scheduler, "P4Media-5 音频", "音频测试 " + ts(),
            "data/media/test_voice.amr", targetRooms);
        allPass &= passIf(r5, TaskRun.FAILED_FINAL, "不支持语音附件");

        // 场景 6：超大图 → fatal
        if (oversized.exists()) {
            System.out.println("\n--- 场景 6：超大图 (>10MB) → fatal ---");
            TaskRun r6 = fireTask(scheduler, "P4Media-6 超大", "超大测试 " + ts(),
                "data/media/test_oversized.jpg", targetRooms);
            allPass &= passIf(r6, TaskRun.FAILED_FINAL, "超过");
        } else {
            System.out.println("\n--- 场景 6：跳过（未生成 test_oversized.jpg）---");
        }

        // 场景 7：真发带图（dry-run=false 时）
        if (!Config.P4_DRY_RUN) {
            System.out.println("\n--- 场景 7：真发带图（dry-run=false）---");
            TaskRun r7 = fireTask(scheduler, "P4Media-7 真发", "真发测试 " + ts(),
                "data/media/test.jpg", targetRooms);
            allPass &= passIf(r7, TaskRun.SEND_CLICKED, null);
        } else {
            System.out.println("\n--- 场景 7：跳过（dry-run=true）置 p4.dry-run=false 后再跑可触发 ---");
        }

        System.out.println("\n=== P4Test media 结果 ===");
        System.out.println(allPass ? "✅ 全部场景通过" : "❌ 部分场景失败");
        System.exit(allPass ? 0 : 1);
    }

    // ===== helpers =====

    private static TaskRun fireTask(TaskScheduler scheduler, String namePrefix,
                                    String content, String mediaPath, String targetRooms) {
        ScheduledTask task = buildTask(namePrefix + "-" + ts(), content, mediaPath, targetRooms);
        TaskStore store = new TaskStore();
        long taskId = store.insertTask(task);
        System.out.println("已创建任务 id=" + taskId + " name=" + task.name
            + " media=" + (mediaPath == null ? "(无)" : mediaPath));
        try {
            return scheduler.fireNow(taskId);
        } catch (Exception e) {
            System.out.println("❌ fireNow 异常: " + e.getMessage());
            TaskRun fake = new TaskRun();
            fake.state = TaskRun.FAILED_FINAL;
            fake.lastError = "fireNow 异常: " + e.getMessage();
            return fake;
        }
    }

    private static ScheduledTask buildTask(String name, String content,
                                           String mediaPath, String targetRooms) {
        ScheduledTask t = new ScheduledTask();
        t.name = name;
        t.msgType = "text";
        t.content = content;
        t.mediaPath = mediaPath;
        t.rhythm = "once";
        t.plannedTime = "00:00";
        t.targetRooms = targetRooms;
        t.enabled = true;
        t.fireOnceAt = System.currentTimeMillis();
        t.senderUserid = Config.MY_USERID;
        return t;
    }

    private static String resolveTargetRooms() {
        String targetRooms = Config.TEST_TARGET_ROOMID.isEmpty()
            ? RoomMap.enabledRooms().stream().map(r -> r.roomid)
                .collect(Collectors.joining(","))
            : Config.TEST_TARGET_ROOMID;
        return targetRooms.isBlank() ? null : targetRooms;
    }

    private static void printRun(TaskRun run) {
        System.out.println("--- 最终 task_run ---");
        System.out.println("  state        = " + run.state);
        System.out.println("  msgid        = " + run.msgid);
        System.out.println("  api_attempts = " + run.apiAttempts);
        System.out.println("  adb_attempts = " + run.adbAttempts);
        System.out.println("  last_error   = " + run.lastError);
    }

    /** 返回 true=通过；false=失败。打印详细对比 + 不调用 System.exit 让其他场景继续 */
    private static boolean passIf(TaskRun run, String expectedState, String errorContains) {
        printRun(run);
        boolean ok = expectedState.equals(run.state);
        if (ok && errorContains != null) {
            ok = run.lastError != null && run.lastError.contains(errorContains);
        }
        if (ok) {
            System.out.println("✅ 通过（state=" + run.state
                + (errorContains != null ? ", last_error 含「" + errorContains + "」" : "") + "）");
        } else {
            System.out.println("❌ 失败：期望 state=" + expectedState
                + (errorContains != null ? " + last_error 含「" + errorContains + "」" : "")
                + "，实际 state=" + run.state + " last_error=" + run.lastError);
        }
        return ok;
    }

    private static String ts() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    }

    private P4Test() {}
}
