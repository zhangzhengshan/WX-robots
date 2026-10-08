package com.mumu.bot.outbound.adb;

import com.mumu.bot.config.Config;
import java.io.File;
import java.nio.file.Files;

/**
 * ADB 底层封装：shell / tap / input / dump / 截图 / 拉文件。
 * 出站走纯 ADB UI 自动化，不装 agent、不 hook、不碰协议（见 ADR-0001）。
 */
public final class Adb {

    // P7：ADB shell 连续失败计数（跨出站+定时共用），达 5 次发 adb_failed 告警
    private static int consecutiveShellFailures = 0;
    private static final int ADB_FAIL_THRESHOLD = 5;

    // ADR-0013：MuMu 崩溃哨兵。watchdog 在 MuMu 崩溃时写 data/mumu_down，
    // bot 侧检测到哨兵存在则跳过 adb_failed 计数，避免双告警刷屏。
    // watchdog 在 MuMu 恢复 + ADB 就绪后删除该哨兵；Main 启动时也会自愈清理残留哨兵。
    private static final File MUMU_DOWN_SENTINEL = new File("data/mumu_down");

    /** MuMu 崩溃哨兵是否存在（watchdog 在 MuMu 崩溃期间写入） */
    private static boolean mumuDownSentinelExists() {
        return MUMU_DOWN_SENTINEL.exists();
    }

    /** 连接 MuMu（adb server 重启后不会自动重连，必须显式 connect）并确认设备在线 */
    public static void connectDevice() throws Exception {
        ProcessBuilder pb = new ProcessBuilder(Config.ADB_PATH, "connect", Config.DEVICE);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), "UTF-8");
        p.waitFor();
        if (!out.contains("connected")) {
            throw new IllegalStateException("adb connect 失败: " + out.trim());
        }
        ProcessBuilder st = new ProcessBuilder(Config.ADB_PATH, "-s", Config.DEVICE, "get-state");
        st.redirectErrorStream(true);
        Process sp = st.start();
        String state = new String(sp.getInputStream().readAllBytes(), "UTF-8").trim();
        sp.waitFor();
        if (!"device".equals(state)) {
            throw new IllegalStateException("设备未就绪，get-state=" + state);
        }
        log("    -> ADB 设备已连接: " + Config.DEVICE);
    }

    public static String shell(String cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            Config.ADB_PATH, "-s", Config.DEVICE, "shell", cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), "UTF-8");
        p.waitFor();
        String low = out.toLowerCase();
        if (low.contains("device not found") || low.contains("no devices")
                || low.contains("error: closed") || low.contains("offline")
                || low.contains("unauthorized")) {
            // ADR-0013：MuMu 崩溃期间 watchdog 已写 data/mumu_down 哨兵，此时 ADB 必然失败
            // 属于预期场景，跳过 adb_failed 计数避免与熔断告警双报刷屏；
            // 仍抛异常让调用方感知失败，仅是不计入告警阈值
            if (!mumuDownSentinelExists()) {
                consecutiveShellFailures++;
                if (consecutiveShellFailures == ADB_FAIL_THRESHOLD) {
                    com.mumu.bot.alert.MailAlerter.alert(
                        com.mumu.bot.alert.MailAlerter.ADB_FAILED,
                        "ADB shell 连续失败 " + ADB_FAIL_THRESHOLD + " 次，最新命令 [" + cmd
                            + "] 输出: " + (out.trim().length() > 200 ? out.trim().substring(0, 200) : out.trim()));
                    consecutiveShellFailures = 0; // 重置计数，靠 30 分钟节流防刷屏
                }
            }
            throw new IllegalStateException("ADB 命令失败 [" + cmd + "]: " + out.trim());
        }
        consecutiveShellFailures = 0; // 成功一次清零
        return out;
    }

    public static void pull(String remote, File local) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            Config.ADB_PATH, "-s", Config.DEVICE, "pull", remote, local.getAbsolutePath());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().readAllBytes();
        p.waitFor();
    }

    public static void tap(int x, int y) throws Exception {
        shell("input tap " + x + " " + y);
    }

    public static void tapCenter(int[] b) throws Exception {
        tap((b[0] + b[2]) / 2, (b[1] + b[3]) / 2);
    }

    /**
     * 长按目标区域中心（同点 swipe，默认 800ms 触发长按/上下文菜单）。
     * ADB input swipe 同坐标 = 按住不松手，duration 到了才抬起。
     */
    public static void longPress(int[] bounds, int ms) throws Exception {
        int x = (bounds[0] + bounds[2]) / 2;
        int y = (bounds[1] + bounds[3]) / 2;
        shell("input swipe " + x + " " + y + " " + x + " " + y + " " + ms);
    }

    /**
     * 通过 `adb shell input text <text>` 注入文本到企微输入框。
     *
     * 关键修复：adb client 会把所有参数用空格拼接成一条命令字符串，发给设备上的
     * adbd 用 sh -c 执行，所以参数会再被 Android shell (mksh) 解析一遍。原实现直接
     * 把 text 作为一个参数传过去，导致：
     *   1. 文本里的空格让 shell 分词，input 命令只拿到第一个词
     *   2. 词首的 `#` 被 sh -c 当作注释起始符，整段后半被吞
     *   3. `?` 是 shell 通配符，匹配失败时保留原文但破坏语义
     * 实测表现：LLM 回复 "小程序链接在这里? #小程序://xxxx 点击..." 只上屏
     *          "小程序链接在这里?"，从 `#` 开始全部被截断。
     *
     * 修复：用单引号包裹整个 text 作为单个字面量参数传给 input 命令。单引号内
     * 所有字符都不被 shell 解析。文本里若含单引号本身，用标准 mksh 转义
     * `'...'` -> `'...'\''...'`（关闭单引号、反斜杠转义单引号、重开单引号）。
     *
     * 注意：input 命令自身仍会做 `%s`->空格、`%t`->tab 的转义，目前 LLM 输出
     * 不含 `%`，暂不处理；如后续出现 `%` 字符，再在此处先做 `%` -> `%%` 转义。
     */
    public static void inputText(String text) throws Exception {
        if (text == null || text.isEmpty()) return;
        String arg = "'" + text.replace("'", "'\\''") + "'";
        ProcessBuilder pb = new ProcessBuilder(
            Config.ADB_PATH, "-s", Config.DEVICE, "shell", "input", "text", arg);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), "UTF-8");
        p.waitFor();
        if (out.toLowerCase().contains("error")) {
            log("    [inputText 输出: " + out.trim() + "]");
        }
    }

    public static void clearInputBox() throws Exception {
        shell("input keyevent KEYCODE_MOVE_END");
        sleep(200);
        for (int i = 0; i < 20; i++) {
            shell("input keyevent KEYCODE_DEL");
            sleep(30);
        }
    }

    /**
     * uiautomator dump，5 次重试；全部失败返回空 XML（调用方用固定坐标 fallback）
     *
     * 已知失败根因：MuMu Player 12（Android 12）的 uiautomator 调 waitForIdle() 等空闲，
     * 企微群聊页有持续刷新（在线状态/未读红点/消息轮询），永远等不到 → 报
     * "ERROR: could not get idle state." 退出，不产生文件。
     *
     * 修复：
     *   1. 首次失败时调用 disableAnimations() 关掉三个动画 scale（一次性，重试前调）
     *   2. 重试交替用 --compressed（少节点，某些场景更容易成功）
     *   3. 失败时打印 uiautomator 原始输出，便于后续诊断
     */
    public static String dumpUi() throws Exception {
        String lastErr = "";
        for (int attempt = 0; attempt < 5; attempt++) {
            // 第 1 次失败后关动画（避免每次都关）
            if (attempt == 1) {
                disableAnimations();
                sleep(500);
            }
            // 交替用 --compressed（偶发更稳）
            String cmd = (attempt % 2 == 0)
                ? "uiautomator dump /sdcard/wecom_ui.xml"
                : "uiautomator dump --compressed /sdcard/wecom_ui.xml";
            String r = shell(cmd);
            if (r.contains("dumped")) {
                File f = new File(Config.TEMP_DIR, "ui.xml");
                pull("/sdcard/wecom_ui.xml", f);
                if (f.exists() && f.length() > 100) {
                    String xml = Files.readString(f.toPath());
                    if (xml.contains("class=") || xml.contains("text=")) {
                        return xml;
                    }
                }
                lastErr = "dumped 但 xml 内容为空/无节点";
            } else {
                lastErr = r.length() > 200 ? r.substring(0, 200) : r;
            }
            sleep(1000);
        }
        log("    [dump 全部失败，使用固定坐标模式]");
        log("    [最后错误: " + lastErr + "]");
        return "<hierarchy></hierarchy>";
    }

    /**
     * 关闭 Android 三大动画 scale（开发/自动化环境不需要动画，且动画会让 uiautomator 等不到 idle）。
     * MuMu Player 12 上验证有效：关后 dump 立刻成功。
     */
    public static void disableAnimations() {
        try {
            shell("settings put global window_animation_scale 0");
            shell("settings put global animator_duration_scale 0");
            shell("settings put global transition_animation_scale 0");
        } catch (Exception e) {
            log("    [disableAnimations 异常: " + e.getMessage() + "]");
        }
    }

    /** 风控截图存证 */
    public static void screenshot() {
        try {
            shell("screencap -p /sdcard/wecom_risk.png");
            File f = new File(Config.TEMP_DIR, "risk_" + System.currentTimeMillis() + ".png");
            pull("/sdcard/wecom_risk.png", f);
            log("   截图: " + f.getAbsolutePath());
        } catch (Exception e) {
            log("   截图失败: " + e.getMessage());
        }
    }

    /**
     * 出站发送审计截图（P2 决策 10）：落盘到 Config.SCREENSHOT_DIR，文件名 {prefix}_{ts}.png。
     * 失败时返回 null（不抛异常，避免发送链路因截图失败而中断）。
     */
    public static File screenshotTo(File dir, String prefix) {
        try {
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, prefix + "_" + System.currentTimeMillis() + ".png");
            shell("screencap -p /sdcard/wecom_send.png");
            pull("/sdcard/wecom_send.png", f);
            return f;
        } catch (Exception e) {
            log("   截图失败: " + e.getMessage());
            return null;
        }
    }

    // ===== 工具 =====

    public static void log(String msg) {
        System.out.println(msg);
        System.out.flush();
    }

    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Adb() {}
}
