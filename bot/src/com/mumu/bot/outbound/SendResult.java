package com.mumu.bot.outbound;

/**
 * 发送结果（Result 模式，见 P2 设计决策 4）。
 *
 * ok=true：发送成功（ADB 命令链无异常 + 截图落盘 + 路径记日志），error=null。
 * ok=false：发送失败，error 填异常摘要（QueueConsumer 写入 outbound_message.error）。
 *
 * 不用异常模式是为让 QueueConsumer 用 if/else 流水线式区分发送成功与失败，
 * 避免 try/catch 同时区分"发送失败"和"DB 写入失败"两类异常。
 * 测试桩 StubSender 按 attempts 计数返回 ok/fail，极度可测。
 */
public final class SendResult {

    public final boolean ok;
    public final String error;

    private SendResult(boolean ok, String error) {
        this.ok = ok;
        this.error = error;
    }

    public static SendResult ok() {
        return new SendResult(true, null);
    }

    public static SendResult fail(String error) {
        return new SendResult(false, error);
    }

    @Override
    public String toString() {
        return ok ? "SendResult{ok}" : "SendResult{fail: " + error + "}";
    }
}
