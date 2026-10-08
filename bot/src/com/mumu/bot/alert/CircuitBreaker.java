package com.mumu.bot.alert;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Date;

/**
 * 熔断哨兵（P7，见 ADR-0012）。
 *
 * 触发条件：
 *   1. ADB 风控关键词命中（WeChatUi.checkRisk）—— 企微风控弹窗，继续会封号
 *   2. 未捕获 Error（OOM / StackOverflow 等，Main 默认异常处理器）
 *   3. 数据库初始化失败（Db.open 抛异常）
 *
 * 行为：
 *   - 写 data/circuit_breaker 哨兵文件（含原因 + 时间戳）
 *   - 发 circuit_breaker 告警邮件（绕过节流，立即通知）
 *   - 调用方随后 System.exit(1) 退出
 *
 * watchdog.ps1 检测到哨兵文件 → 不重启、发告警、自身退出（等人工删哨兵 + 手动重启）。
 * 区别于崩溃重启：崩溃无哨兵，watchdog 会自动拉起；熔断有哨兵，watchdog 拒绝自动重启。
 */
public final class CircuitBreaker {

    public static final File SENTINEL = new File("data/circuit_breaker");

    /** 触发熔断：写哨兵 + 发告警邮件。调用方需自行 System.exit(1)。 */
    public static void trip(String reason) {
        // 1. 写哨兵文件（watchdog 据此判定「熔断不重启」）
        try {
            SENTINEL.getParentFile().mkdirs();
            String content = "原因: " + reason + "\n时间: " + new Date() + "\n";
            Files.writeString(SENTINEL.toPath(), content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            System.err.println("[CircuitBreaker] 哨兵文件已写入: " + SENTINEL.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("[CircuitBreaker] 哨兵文件写入失败: " + e.getMessage());
        }
        // 2. 发告警邮件（circuit_breaker 绕过节流，立即发）
        MailAlerter.alert(MailAlerter.CIRCUIT_BREAKER, reason);
    }

    private CircuitBreaker() {}
}
