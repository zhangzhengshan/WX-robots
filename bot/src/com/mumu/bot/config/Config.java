package com.mumu.bot.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 全局配置：唯一从 config.properties 读取（含密钥，禁止提交版本库）。
 * 数值型节奏参数与熔断关键词保留为代码常量。
 */
public final class Config {

    // ===== ADB / 模拟器 =====
    /** 运行时可改（Admin /config 页面，改后下一次 shell 调用即生效） */
    public static volatile String ADB_PATH;
    /** 启动时由 detectDevice() 自动探测（扫描 MuMu 端口 16384/16416/16448/16480），无命中则回退 config.properties 默认值 */
    public static volatile String DEVICE;
    /** config.properties 中的出厂默认 adb.device（detectDevice 全部未命中时使用） */
    private static String DEVICE_DEFAULT;

    // ===== 企微 =====
    /** 运行时可改（Admin /config 页面，需哨兵重启生效） */
    public static volatile String MY_USERID;
    /** 运行时可改（Admin /config 页面，改后下一条入站消息即生效） */
    public static volatile String MY_NAME;
    public static final String CONTACT_SECRET;
    public static final String DB_PATH;
    public static final String CORPID;
    public static final String ARCHIVE_SECRET;
    public static final String PROXY;
    public static final String PRIVATE_KEY_PATH;

    // ===== 云端 LLM（P3）=====
    public static final String AI_BASE_URL;
    /** 运行时可改（Admin /config 页面，改后下一次 LLM 调用即生效） */
    public static volatile String AI_API_KEY;
    /** 运行时可改（Admin /config 页面，改后下一次 LLM 调用即生效） */
    public static volatile String AI_MODEL;
    public static final File AI_PROMPT_FILE;
    public static final int AI_TIMEOUT_SEC = 60;        // 单次调用超时；进程内不重试
    public static final int AI_FAIL_ALERT_AFTER = 5;    // 连败 N 次打 WARN（P7 起接邮件告警）

    // ===== 知识库（P3）=====
    public static final File KB_DIR;
    public static final int KB_MAX_CHARS = 100_000;     // 全库总字数硬上限（见 ADR-0003/0007）

    // ===== 定时宣传（P4，企业群发 API + ADB 确认，见 ADR-0008）=====
    /** dry-run：API 真创建任务、ADB 真实导航到「发送」按钮，但最后一步不点击（不占用客户群接收配额）。运行时可改，前端开关切换即生效 */
    public static volatile boolean P4_DRY_RUN;
    /** 测试期目标群白名单（空=不限制，取 room_map 全部启用群）；非空时只向该 roomid 发送 */
    public static final String TEST_TARGET_ROOMID;
    /** 错过补发窗口（小时）：planned_time 过后超过该时长不再补发 */
    public static final int P4_MAKEUP_WINDOW_HOURS;
    public static final long P4_TICK_INTERVAL_MS = 30_000;   // TaskScheduler 扫描间隔
    public static final int  P4_MAX_ATTEMPTS = 3;            // API / ADB 各自最多尝试 3 次
    /** task_run 退避间隔（见 ADR-0010，2026-09-16 反转原 30s/2min/10min）。
     *  默认 5min/30min/60min；P2 出站队列仍用 RETRY_BACKOFF_MS 不动。 */
    public static final long[] P4_RETRY_BACKOFF_MS;
    /** 媒体附件大小上限（MB），见 ADR-0009 第二刀 */
    public static final int P4_MEDIA_MAX_IMAGE_MB;
    public static final int P4_MEDIA_MAX_VIDEO_MB;
    public static final int P4_MEDIA_MAX_FILE_MB;

    // ===== 管理端（P5，JDK 内嵌 HttpServer，见 ADR-0004 修订）=====
    public static final String ADMIN_HOST;
    public static final int    ADMIN_PORT;

    // ===== 日志（P5，按天滚动，自写 DailyLogRotator）=====
    public static final File   LOG_DIR;
    public static final int    LOG_RETENTION_DAYS;

    // ===== 邮件告警（P7，jakarta.mail SMTP，自发自收，见 ADR-0012）=====
    public static final boolean MAIL_ENABLED;
    public static final String  MAIL_SMTP_HOST;
    public static final int     MAIL_SMTP_PORT;
    public static final boolean MAIL_SMTP_SSL;
    public static final String  MAIL_SMTP_ACCOUNT;
    public static final String  MAIL_SMTP_AUTH_CODE;
    /** 收件人默认值（留空则取 MAIL_SMTP_ACCOUNT 自发自收）；管理端可改，存 data/mail.properties */
    public static final String  MAIL_RECIPIENT;

    public static final int REPLY_INTERVAL_MIN = 500;   // 0.5s
    public static final int REPLY_INTERVAL_MAX = 3000;  // 3s
    public static final long POLL_INTERVAL = 5000;      // 5s 轮询间隔

    // ===== 企微客户联系 API 出口（ADR-0015）=====
    /** true=通过 ECS SOCKS 隧道（proxy 键）走静态 IP 出口；false=直连本地动态 IP（应急回退） */
    public static final boolean WECOM_VIA_ECS;

    // 出站队列优先级（数字越小越优先，见 ADR-0002）
    public static final int PRIORITY_AI_REPLY    = 1;   // @提及触发
    public static final int PRIORITY_SCHEDULED   = 5;   // 定时宣传
    public static final int PRIORITY_MANUAL      = 9;   // 人工触发

    // 出站队列调度
    public static final long QUEUE_POLL_INTERVAL_MS = 500;            // 消费线程轮询 DB
    public static final long DEDUP_WINDOW_MS         = 5 * 60 * 1000;  // 5 分钟去重窗
    public static final long[] RETRY_BACKOFF_MS      = {30_000, 120_000, 600_000}; // 30s/2min/10min
    public static final int  MAX_ATTEMPTS             = 3;

    // 截图（见 ADR-0002 + P2 设计决策 10/11/13）
    public static final File SCREENSHOT_DIR = new File("data/screenshots");
    public static final long SCREENSHOT_RETAIN_MS = 30L * 24 * 60 * 60 * 1000; // 30 天
    public static final long SCREENSHOT_CLEAN_INTERVAL_MS = 6L * 60 * 60 * 1000; // 6h 扫一次

    // 熔断关键词
    public static final String[] RISK_KEYWORDS = {
        "身份验证", "重新登录", "安全验证", "操作频繁",
        "风险提醒", "账号异常", "登录验证", "验证身份"
    };

    // ADB 临时文件目录（UI dump / 风控截图）
    public static final File TEMP_DIR = new File(
        System.getProperty("java.io.tmpdir"), "wecom-bot");

    static {
        File f = new File("config.properties");
        if (!f.exists()) {
            throw new IllegalStateException(
                "找不到 config.properties（工作目录应为 bot/）");
        }
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(f)) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("config.properties 读取失败: " + e.getMessage(), e);
        }
        ADB_PATH         = p.getProperty("adb.path");
        DEVICE_DEFAULT   = p.getProperty("adb.device");
        DEVICE           = DEVICE_DEFAULT;
        MY_USERID        = p.getProperty("my.userid");
        MY_NAME          = p.getProperty("my.name", "");
        CONTACT_SECRET   = p.getProperty("contact.secret", "");
        DB_PATH          = p.getProperty("db.path", "data/bot.db");
        CORPID           = p.getProperty("corpid");
        ARCHIVE_SECRET   = p.getProperty("archive.secret");
        PROXY            = p.getProperty("proxy");
        PRIVATE_KEY_PATH = p.getProperty("private.key.path");
        WECOM_VIA_ECS    = Boolean.parseBoolean(p.getProperty("wecom.via-ecs", "true"));
        AI_BASE_URL      = p.getProperty("ai.base-url", "");
        AI_API_KEY       = p.getProperty("ai.api-key", "");
        AI_MODEL         = p.getProperty("ai.model", "");
        AI_PROMPT_FILE   = new File(p.getProperty("ai.prompt.path", "data/ai_prompt.txt"));
        KB_DIR           = new File(p.getProperty("kb.dir", "data/kb"));

        // P4：默认 dry-run=true（安全闸门：不点发送就不占用客户群配额，见 ADR-0008）
        P4_DRY_RUN              = Boolean.parseBoolean(p.getProperty("p4.dry-run", "true"));
        TEST_TARGET_ROOMID      = p.getProperty("test.target-roomid", "").trim();
        P4_MAKEUP_WINDOW_HOURS  = Integer.parseInt(p.getProperty("p4.makeup-window-hours", "4").trim());
        // ADR-0010：task_run 退避反转 5min/30min/60min（P2 出站队列 RETRY_BACKOFF_MS 不动）
        P4_RETRY_BACKOFF_MS     = parseLongArray(
            p.getProperty("p4.retry-backoff-ms", "300000,1800000,3600000"));
        P4_MEDIA_MAX_IMAGE_MB   = Integer.parseInt(p.getProperty("p4.media.max-image-mb", "10").trim());
        P4_MEDIA_MAX_VIDEO_MB   = Integer.parseInt(p.getProperty("p4.media.max-video-mb", "10").trim());
        P4_MEDIA_MAX_FILE_MB    = Integer.parseInt(p.getProperty("p4.media.max-file-mb", "20").trim());

        // P5 管理端（JDK 内嵌 HttpServer，绑 127.0.0.1:9173）
        ADMIN_HOST              = p.getProperty("admin.host", "127.0.0.1").trim();
        ADMIN_PORT              = Integer.parseInt(p.getProperty("admin.port", "9173").trim());

        // P5 按天滚动日志（保留 14 天）
        LOG_DIR                 = new File(p.getProperty("log.dir", "logs"));
        LOG_RETENTION_DAYS      = Integer.parseInt(p.getProperty("log.retention-days", "14").trim());

        // P7 邮件告警（SMTP，自发自收）
        MAIL_ENABLED            = Boolean.parseBoolean(p.getProperty("mail.enabled", "true"));
        MAIL_SMTP_HOST          = p.getProperty("mail.smtp.host", "smtp.163.com").trim();
        MAIL_SMTP_PORT          = Integer.parseInt(p.getProperty("mail.smtp.port", "465").trim());
        MAIL_SMTP_SSL           = Boolean.parseBoolean(p.getProperty("mail.smtp.ssl", "true"));
        MAIL_SMTP_ACCOUNT       = p.getProperty("mail.smtp.account", "").trim();
        MAIL_SMTP_AUTH_CODE     = p.getProperty("mail.smtp.auth-code", "").trim();
        MAIL_RECIPIENT          = p.getProperty("mail.recipient", "").trim();

        // 运行时配置覆盖层：data/runtime.properties 覆盖 config.properties 的 5 个管理端可改键
        // config.properties 退化为纯出厂默认，运行期永不被写入
        reload();
    }

    /**
     * 重新读取 data/runtime.properties，覆盖 5 个 volatile 字段。
     * AdminHandler 保存配置后调用，让改动立即生效。
     * 文件不存在或键缺失 → 保留出厂值。
     *
     * 注意：不使用 Properties.load() 解析——它会把 \P/\N/\s 等非标准转义序列的
     * 反斜杠静默丢弃，导致 Windows 路径 D:\Program Files\... 变成 D:Program Files...
     * 这里用简单行解析（与 watchdog.ps1 的 IndexOf("=") 逻辑一致），保留原始值。
     */
    public static synchronized void reload() {
        File rf = new File("data/runtime.properties");
        if (!rf.exists()) return;
        java.util.Map<String, String> rp = new java.util.HashMap<>();
        try {
            for (String line : java.nio.file.Files.readAllLines(rf.toPath(), StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int eq = trimmed.indexOf('=');
                if (eq < 0) continue;
                String key = trimmed.substring(0, eq).trim();
                String val = trimmed.substring(eq + 1);
                rp.put(key, val);
            }
        } catch (Exception e) {
            System.err.println("[Config] runtime.properties 读取失败: " + e.getMessage());
            return;
        }
        // 6 个运行时可改键：仅覆盖非空值
        String v;
        if ((v = rp.get("adb.path")) != null)     ADB_PATH   = v.trim();
        if ((v = rp.get("my.userid")) != null)    MY_USERID  = v.trim();
        if ((v = rp.get("my.name")) != null)      MY_NAME    = v.trim();
        if ((v = rp.get("ai.api-key")) != null)   AI_API_KEY = v;
        if ((v = rp.get("ai.model")) != null)     AI_MODEL   = v.trim();
        if ((v = rp.get("p4.dry-run")) != null)   P4_DRY_RUN = Boolean.parseBoolean(v.trim());
    }

    /** runtime.properties 的 6 个受管键名（AdminHandler /config 页面使用） */
    public static final java.util.List<String> RUNTIME_KEYS = java.util.Arrays.asList(
        "adb.path", "my.userid", "my.name", "ai.api-key", "ai.model", "p4.dry-run"
    );

    /**
     * 启动时探测 MuMu ADB 端口：扫描 16384/16416/16448/16480（实例 0/1/2/3，每个 +32），
     * 用 Socket.connect(500ms) 做快速 TCP 探测避免 adb connect 死端口卡住。
     * 第一个命中即停，Config.DEVICE 直接覆盖。全部未命中回退 config.properties 默认值 + WARN。
     *
     * 必须在 Main 中（而不是静态初始化块）调用——静态块里日志系统尚未就绪，WARN 看不到。
     */
    public static void detectDevice() {
        // MuMu Player 端口规律：实例0=16384, 1=16416, 2=16448, 3=16480
        int[] ports = {16384, 16416, 16448, 16480};
        for (int port : ports) {
            try (java.net.Socket sock = new java.net.Socket()) {
                java.net.InetSocketAddress addr = new java.net.InetSocketAddress("127.0.0.1", port);
                sock.connect(addr, 500);  // 500ms 快速探测
                DEVICE = "127.0.0.1:" + port;
                System.out.println("[Config] ADB 端口探测命中: " + DEVICE
                    + "（MuMu 实例 " + ((port - 16384) / 32) + "）");
                return;
            } catch (Exception e) {
                // 该端口无监听，继续扫下一个
            }
        }
        // 全部未命中：回退 config.properties 默认值
        System.out.println("[Config] ⚠️ 4 个 MuMu 端口均无监听（16384/16416/16448/16480），"
            + "回退 config.properties 默认值: " + DEVICE_DEFAULT);
        DEVICE = DEVICE_DEFAULT;
    }

    private static long[] parseLongArray(String s) {
        String[] parts = s.split("\\s*,\\s*");
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Long.parseLong(parts[i]);
        return out;
    }

    private Config() {}
}
