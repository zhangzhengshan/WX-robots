package com.mumu.bot.outbound.adb;

import com.mumu.bot.config.Config;
import com.mumu.bot.outbound.OutboundMsg;
import com.mumu.bot.outbound.OutboundSender;
import com.mumu.bot.outbound.SendResult;
import java.io.File;

/**
 * 生产环境出站发送器（P2 设计决策 7）：
 *   构造注入持有 WeChatUi 实例（不重写 P1 已验收的进群/@选择器逻辑）
 *   WeChatUi 保持纯 UI 操作，不感知队列；AdbSender 负责调发送方法 + 截图 + 路径记录
 *
 * 发送逻辑：
 *   1. enterGroup(roomName)；失败返回 fail（QueueConsumer 会跳过本次回复，状态机不重试 @ 的群导航失败？见下方注释）
 *   2. isMention ? sendWithMention(mentionName, content) : sendPlain(content)
 *   3. Adb.screenshotTo(Config.SCREENSHOT_DIR, "{id}_{attempt}") 落盘审计
 *   4. 成功记日志；失败把 error 返回给 QueueConsumer 写入 outbound_message.error
 *
 * 注意：群导航失败由 WeChatUi.enterGroup 内部判定（false=找不到群名跳过本次回复），
 *   本类把"导航失败"和"发送失败"统一返回 fail——QueueConsumer 会按状态机尝试重试 3 次后转 failed。
 *   这与 P1 的"导航失败跳过不重试"行为不同，但符合 P2"失败自动重试"语义；
 *   若群名长期不存在，重试 3 次后转 failed + 邮件告警（P7）才是正确反馈。
 */
public final class AdbSender implements OutboundSender {

    private final WeChatUi ui;

    public AdbSender(WeChatUi ui) {
        this.ui = ui;
    }

    @Override
    public SendResult send(OutboundMsg msg) {
        // P4：与定时宣传的群发助手确认串行，避免两条 ADB 导航链交叉错发
        synchronized (WeChatUi.SESSION_LOCK) {
            return sendInternal(msg);
        }
    }

    private SendResult sendInternal(OutboundMsg msg) {
        try {
            // 第 1 步：进群
            if (!ui.enterGroup(msg.roomName)) {
                return SendResult.fail("导航失败：无法安全进入群「" + msg.roomName + "」");
            }

            // 第 2 步：发送
            if (msg.isMention) {
                ui.sendWithMention(msg.mentionName, msg.content == null ? "" : msg.content);
            } else {
                ui.sendPlain(msg.content == null ? "" : msg.content);
            }

            // 第 3 步：截图审计（成功 + 失败都截，失败路径由 catch 块处理）
            String prefix = msg.id + "_" + (msg.attempts + 1);
            File shot = Adb.screenshotTo(Config.SCREENSHOT_DIR, prefix);
            if (shot != null) {
                System.out.println("  📸 审计截图: " + shot.getAbsolutePath());
            }

            System.out.println("  ✅ 发送成功: " + msg);
            System.out.flush();
            return SendResult.ok();

        } catch (Exception e) {
            // 失败也要截图（决策 10：sent 与 failed 都截）
            String prefix = msg.id + "_" + (msg.attempts + 1);
            try {
                File shot = Adb.screenshotTo(Config.SCREENSHOT_DIR, prefix);
                if (shot != null) {
                    System.out.println("  📸 失败截图: " + shot.getAbsolutePath());
                }
            } catch (Exception ignore) { /* 截图失败不影响主链路 */ }
            System.out.println("  ❌ 发送失败: " + e.getMessage());
            System.out.flush();
            return SendResult.fail(e.getMessage());
        }
    }
}
