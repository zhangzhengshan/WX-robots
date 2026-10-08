package com.mumu.bot.schedule;

/**
 * 定时宣传任务定义（scheduled_task 一行的内存映像，P4）。
 *
 * rhythm：
 *   once    一次性，触发时刻取 fireOnceAt
 *   daily   每天 plannedTime（HH:mm）
 *   weekly  每周 weekdays 指定的日子（1=周一 … 7=周日，逗号分隔）的 plannedTime
 *
 * targetRooms：roomid 逗号分隔；实际发送时还会与 room_map(enabled=1) 及
 * Config.TEST_TARGET_ROOMID 白名单取交集（见 ADR-0008）。
 */
public final class ScheduledTask {

    public long id;
    public String name;
    public String msgType;       // 目前固定 text（第二刀加 image/file）
    public String content;
    public String mediaPath;
    public String rhythm;        // once/daily/weekly
    public String weekdays;      // weekly 时 "1,3,5"
    public String plannedTime;   // HH:mm
    public String targetRooms;   // roomid 逗号分隔
    public boolean enabled;
    public Long fireOnceAt;      // once 的触发 epoch ms
    public String senderUserid;  // 群发 sender（空则取 Config.MY_USERID）
    public String apiMsgid;      // 最近一次 API 返回的 msgid
    public long createdAt;

    public String effectiveSender() {
        return (senderUserid == null || senderUserid.isBlank())
            ? com.mumu.bot.config.Config.MY_USERID : senderUserid.trim();
    }
}
