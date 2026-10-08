package com.mumu.bot.alert;

import com.mumu.bot.config.Config;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 邮件告警（P7，见 ADR-0012；MuMu 静默自愈策略见 ADR-0013）。
 *
 * 6 类邮件告警：
 *   circuit_breaker        熔断主动退出（绕过节流，立即发）
 *   heartbeat_lost         心跳丢失/进程假死（watchdog.ps1 发，JVM 已死）
 *   sdk_pull_failed        存档 SDK 拉取连续失败 10 次
 *   adb_failed             ADB 服务异常（shell 连续失败 5 次）
 *   outbound_failed_final  出站消息重试耗尽转 failed
 *   task_cleanup_failed    群发助手历史卡片删除失败
 *
 * MuMu 崩溃不发独立邮件：约定次数内自愈全程静默（仅 watchdog 留痕
 * data/mumu_events.log），超限熔断走 circuit_breaker（见 ADR-0013）。
 *
 * 节流：同一事件类型 30 分钟内只发一封（alert_throttle 表，circuit_breaker 绕过）。
 * 附件：发信前调用包根目录 export-diagnostics.ps1 组诊断包（与 watchdog/手动导出共用
 *       同一组包逻辑）；生成失败则降级纯文本——告警永不被附件阻塞。
 * SMTP：jakarta.mail（com.sun.mail:jakarta.mail:2.0.1），配置来自 config.properties。
 * 收件人：优先 data/mail.properties（管理端可改，watchdog 也读），缺省取 mail.smtp.account。
 */
public final class MailAlerter {

    // ===== 6 类邮件告警事件类型常量 =====
    public static final String CIRCUIT_BREAKER       = "circuit_breaker";
    public static final String HEARTBEAT_LOST        = "heartbeat_lost";
    public static final String SDK_PULL_FAILED       = "sdk_pull_failed";
    public static final String ADB_FAILED            = "adb_failed";
    public static final String OUTBOUND_FAILED_FINAL = "outbound_failed_final";
    public static final String TASK_CLEANUP_FAILED   = "task_cleanup_failed";

    /** 绕过节流的事件类型（熔断必须立即通知，不等 30 分钟） */
    private static final Set<String> BYPASS_THROTTLE = Set.of(CIRCUIT_BREAKER);

    private static final Map<String, String> CN_NAMES = Map.ofEntries(
        Map.entry(CIRCUIT_BREAKER,       "熔断触发"),
        Map.entry(HEARTBEAT_LOST,        "心跳丢失/进程假死"),
        Map.entry(SDK_PULL_FAILED,       "存档SDK拉取失败"),
        Map.entry(ADB_FAILED,            "ADB服务异常"),
        Map.entry(OUTBOUND_FAILED_FINAL, "出站消息重试耗尽"),
        Map.entry(TASK_CLEANUP_FAILED,   "群发助手历史卡片删除失败")
    );

    /** 管理端可编辑的收件人文件（watchdog 也读此文件，无需重启 watchdog） */
    private static final File RECIPIENT_FILE = new File("data/mail.properties");
    private static final DateTimeFormatter LOG_DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 触发一次邮件告警。
     * @param eventType 事件类型常量（见本类字段）
     * @param detail    详情描述（可为 null）
     */
    public static void alert(String eventType, String detail) {
        try {
            if (!Config.MAIL_ENABLED) return;

            // 节流判断（circuit_breaker 绕过）
            if (BYPASS_THROTTLE.contains(eventType)) {
                AlertThrottle.record(eventType); // 仍埋点，但不阻塞
            } else {
                if (!AlertThrottle.shouldSend(eventType)) {
                    return; // 节流命中：跳过发信
                }
            }

            String cn = CN_NAMES.getOrDefault(eventType, eventType);
            String subject = "[机器人告警] " + cn;
            String body = buildBody(eventType, cn, detail);
            // 诊断包附件：生成失败返回 null，邮件降级纯文本（告警永不被附件阻塞）
            File bundle = null;
            try { bundle = buildDiagnosticsBundle(); } catch (Exception ignore) {}
            try {
                sendMail(subject, body, bundle);
                System.out.println("  [MailAlerter] ✉️ 告警已发送: " + eventType
                        + (bundle != null ? "（含诊断包附件）" : ""));
            } finally {
                if (bundle != null) {
                    try { Files.deleteIfExists(bundle.toPath()); } catch (Exception ignore) {}
                }
            }
        } catch (Exception e) {
            // 告警发送失败不能影响主流程，仅 stderr
            System.err.println("[MailAlerter] 告警发送失败 " + eventType + ": " + e.getMessage());
        }
    }

    // ===== 邮件正文 =====

    private static String buildBody(String eventType, String cn, String detail) {
        StringBuilder sb = new StringBuilder();
        sb.append("事件类型: ").append(eventType).append("（").append(cn).append("）\n");
        sb.append("触发时间: ").append(new Date()).append("\n");
        sb.append("机器:     ").append(hostname()).append("\n");
        sb.append("详情:     ").append(detail == null || detail.isEmpty() ? "(无)" : detail).append("\n");
        sb.append("\n------ 当天日志最后 50 行 ------\n");
        sb.append(tailLog(50));
        return sb.toString();
    }

    private static String tailLog(int lines) {
        try {
            String today = LocalDate.now().format(LOG_DATE_FMT);
            File f = new File(Config.LOG_DIR, "bot-" + today + ".log");
            if (!f.exists()) return "(日志文件不存在: " + f.getPath() + ")";
            List<String> all = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            int from = Math.max(0, all.size() - lines);
            return String.join("\n", all.subList(from, all.size()));
        } catch (Exception e) {
            return "(读取日志失败: " + e.getMessage() + ")";
        }
    }

    private static String hostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ===== 收件人解析 =====

    /**
     * 收件人：优先 data/mail.properties 的 recipient（管理端可改），
     * 缺省取 config.properties 的 mail.smtp.account（自发自收）。
     */
    public static String recipient() {
        try {
            if (RECIPIENT_FILE.exists()) {
                Properties p = new Properties();
                try (FileInputStream in = new FileInputStream(RECIPIENT_FILE)) {
                    p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                }
                String r = p.getProperty("recipient", "").trim();
                if (!r.isEmpty()) return r;
            }
        } catch (Exception ignore) {
            // 文件解析失败回退到 config 默认值
        }
        if (Config.MAIL_RECIPIENT != null && !Config.MAIL_RECIPIENT.isEmpty()) {
            return Config.MAIL_RECIPIENT;
        }
        return Config.MAIL_SMTP_ACCOUNT;
    }

    // ===== 诊断包附件 =====

    /**
     * 调用包根目录的 export-diagnostics.ps1 组装诊断包（组包逻辑单一来源，与
     * watchdog / 手动导出共用同一脚本）。返回生成的 zip；任何失败返回 null。
     * 脚本缺失（如目录结构变化）或 PowerShell 超时都不影响告警主流程。
     */
    private static File buildDiagnosticsBundle() {
        try {
            File script = new File(System.getProperty("user.dir"), "../export-diagnostics.ps1")
                    .getCanonicalFile();
            if (!script.exists()) return null;
            File outDir = new File("data", "diag-tmp");
            if (!outDir.exists() && !outDir.mkdirs()) return null;
            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile",
                    "-ExecutionPolicy", "Bypass", "-File", script.getAbsolutePath(),
                    "-OutDir", outDir.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // 脚本契约：最后一行非空 stdout = zip 完整路径；控制台输出按 GBK 解码（zh-CN 默认代码页）
            String last = "";
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), Charset.forName("GBK")))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.trim().isEmpty()) last = line.trim();
                }
            }
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) return null;
            File zip = new File(last);
            return zip.exists() && zip.length() > 0 ? zip : null;
        } catch (Exception e) {
            System.err.println("[MailAlerter] 诊断包生成失败（邮件将按纯文本发送）: " + e.getMessage());
            return null;
        }
    }

    // ===== SMTP 发送 =====

    private static void sendMail(String subject, String body, File attachment) throws Exception {
        if (Config.MAIL_SMTP_ACCOUNT.isEmpty() || Config.MAIL_SMTP_AUTH_CODE.isEmpty()) {
            throw new IllegalStateException("mail.smtp.account / mail.smtp.auth-code 未配置");
        }

        Properties props = new Properties();
        props.put("mail.smtp.host", Config.MAIL_SMTP_HOST);
        props.put("mail.smtp.port", String.valueOf(Config.MAIL_SMTP_PORT));
        props.put("mail.smtp.auth", "true");
        if (Config.MAIL_SMTP_SSL) {
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
            props.put("mail.smtp.socketFactory.port", String.valueOf(Config.MAIL_SMTP_PORT));
        }
        // 163/QQ 等国内 SMTP 要求发件人与登录账号一致，否则 553 错误
        props.put("mail.smtp.from", Config.MAIL_SMTP_ACCOUNT);

        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(Config.MAIL_SMTP_ACCOUNT, Config.MAIL_SMTP_AUTH_CODE);
            }
        });

        MimeMessage msg = new MimeMessage(session);
        msg.setFrom(new InternetAddress(Config.MAIL_SMTP_ACCOUNT));
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient()));
        msg.setSubject(subject, "UTF-8");
        if (attachment != null && attachment.exists()) {
            // 多部分邮件：正文 + 诊断包附件
            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(body, "UTF-8");
            MimeBodyPart filePart = new MimeBodyPart();
            filePart.attachFile(attachment);
            filePart.setFileName(attachment.getName());
            Multipart mp = new MimeMultipart();
            mp.addBodyPart(textPart);
            mp.addBodyPart(filePart);
            msg.setContent(mp);
        } else {
            msg.setText(body, "UTF-8");
        }
        msg.setSentDate(new Date());
        Transport.send(msg);
    }

    private MailAlerter() {}
}
