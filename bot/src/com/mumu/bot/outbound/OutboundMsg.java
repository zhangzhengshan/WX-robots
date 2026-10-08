package com.mumu.bot.outbound;

/**
 * 出站队列消息载体（一行 outbound_message 的内存映像，见 ADR-0002 与 P2 设计决策）。
 *
 * 字段对应 DB 列：
 *   id           自增主键，未入队时为 0
 *   roomid       目标群（用于多群路由）
 *   roomName     目标群名（WeChatUi.enterGroup 用，避免每次查 room_map）
 *   msgType      目前固定 "text"（P6 起加 "image"/"file"）
 *   content      文本内容
 *   isMention    是否带 @ 提及（一次 @ 的多段回复：第 1 段 true、其余 false，见 CONTEXT.md「一次 @ 的多段回复」）
 *   mentionName  @ 用名（display_name）；isMention=false 时为 null
 *   priority     数字越小越优先（AI 回复=1、定时=5、人工=9）
 *   attempts     已尝试次数
 *   sourceId     全局唯一来源标识（去重指纹，见 CONTEXT.md「去重」）
 *
 * 不可变值对象；QueueConsumer 取出后构造本类传给 OutboundSender。
 */
public final class OutboundMsg {

    public final long id;
    public final String roomid;
    public final String roomName;
    public final String msgType;
    public final String content;
    public final boolean isMention;
    public final String mentionName;
    public final int priority;
    public final int attempts;
    public final String sourceId;

    public OutboundMsg(long id, String roomid, String roomName, String msgType,
                       String content, boolean isMention, String mentionName,
                       int priority, int attempts, String sourceId) {
        this.id = id;
        this.roomid = roomid;
        this.roomName = roomName;
        this.msgType = msgType;
        this.content = content;
        this.isMention = isMention;
        this.mentionName = mentionName;
        this.priority = priority;
        this.attempts = attempts;
        this.sourceId = sourceId;
    }

    @Override
    public String toString() {
        return "OutboundMsg{id=" + id + ", room=" + roomName
            + ", mention=" + (isMention ? "@" + mentionName : "plain")
            + ", attempts=" + attempts
            + ", source=" + sourceId
            + ", content=" + (content == null ? "" :
                (content.length() > 30 ? content.substring(0, 30) + "..." : content))
            + "}";
    }
}
