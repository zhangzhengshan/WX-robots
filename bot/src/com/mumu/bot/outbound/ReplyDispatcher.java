package com.mumu.bot.outbound;

import com.mumu.bot.ai.AiService;
import com.mumu.bot.config.Config;

/**
 * P3 重构：ArchiveClient → AI 链路的薄适配层（ArchiveClient 调用方仍是 enqueue(...)）。
 *
 * 入参是已剥掉 "@张飞扬" 的问题原文 question：
 *   - 空 @（question 为空）：不调 LLM，直接入队一条固定闲聊引导（带 @）
 *   - 非空：投递给 AiService 的单线程 worker，由它调云端 LLM 后整段单条入队
 *
 * 一次 @ = 一条出站消息（P3 取消多段回复，见 ADR-0006），source_id 固定 "at:{seq}:0"。
 * 去重由 OutboundQueue 的 5 分钟 source_id 窗口兜底；AiService 调 LLM 前还会预查一次。
 *
 * 本类不阻塞：LLM 调用（10-60s）在 ai-worker 线程跑，不卡住 SDK 监听线程与 lastSeq 推进。
 */
public final class ReplyDispatcher {

    private final OutboundQueue queue = OutboundQueue.INSTANCE;
    private final AiService ai;

    public ReplyDispatcher(AiService ai) {
        this.ai = ai;
    }

    /** ArchiveClient 回调：@提及（question 为去掉 @名 后的问题原文，可能为空串） */
    public void enqueue(String roomid, String roomName, String fromUserid, String fromName,
                        int seq, String question) {
        String sourceId = "at:" + seq + ":0";

        if (question == null || question.isEmpty()) {
            log("");
            log("===== 收到空 @: " + (fromName != null ? fromName : fromUserid)
                + " 群=" + roomName + " seq=" + seq + "，回固定引导（不调 LLM）=====");
            long id = queue.enqueue(roomid, roomName, "text", AiService.EMPTY_QUESTION_REPLY,
                                    true, fromName, Config.PRIORITY_AI_REPLY, sourceId);
            if (id == -1) {
                log("  source_id=" + sourceId + " 5 分钟内已存在，跳过");
            } else {
                log("  已入队 id=" + id + " source_id=" + sourceId);
            }
            log("");
            return;
        }

        ai.submit(new AiService.Question(roomid, roomName, fromUserid, fromName, seq, question));
    }

    private static void log(String msg) {
        System.out.println(msg);
        System.out.flush();
    }
}
