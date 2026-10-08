package com.mumu.bot.outbound;

/**
 * 发送动作抽象（P2 设计决策 4：Result 模式）。
 *
 * 生产实现：outbound.adb.AdbSender，持有 WeChatUi 实例，调用 sendWithMention / sendPlain
 *           + Adb.screenshot() 落盘到 data/screenshots/{id}_{attempt}_{ts}.png。
 * 测试桩：QueueTest 内的 StubSender，按 attempts 计数返回 ok/fail。
 *
 * 调用方 QueueConsumer 根据 ok 决定 markSent 或 markFailed；不抛异常。
 */
public interface OutboundSender {

    /**
     * 发送一条出站消息。
     * @param msg 已被 claimNext 认领为 sending 的消息
     * @return ok=true 时已成功发送；ok=false 时 msg 仍为 sending 状态，由调用方决定重试
     */
    SendResult send(OutboundMsg msg);
}
