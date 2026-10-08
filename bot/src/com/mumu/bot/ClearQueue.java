package com.mumu.bot;

import com.mumu.bot.outbound.OutboundQueue;
import com.mumu.bot.store.Db;

/**
 * 一次性清理脚本：清空 outbound_message 表里所有残留消息（包括 pending/sending/sent/failed）。
 * 用途：P2 修复 enterGroup 后，启动前清掉之前测试残留的旧消息。
 *
 * 运行：java -cp ... com.mumu.bot.ClearQueue
 */
public final class ClearQueue {

    public static void main(String[] args) {
        Db.conn();
        int before = OutboundQueue.INSTANCE.countByStatus("pending")
                   + OutboundQueue.INSTANCE.countByStatus("sending")
                   + OutboundQueue.INSTANCE.countByStatus("sent")
                   + OutboundQueue.INSTANCE.countByStatus("failed");
        System.out.println("清空前总数: " + before);
        OutboundQueue.INSTANCE.clearAll();
        System.out.println("已清空 outbound_message 表");
        System.out.println("现在可以启动 Main 了");
    }

    private ClearQueue() {}
}
