package com.mumu.bot;

import com.mumu.bot.outbound.adb.WeChatUi;
import com.mumu.bot.store.Db;

/**
 * 手动测试 @提及发送流程（不依赖 SDK 监听）
 * 用法：java com.mumu.bot.AtTest [群名] [人名]
 * 默认：Agent测试群1群 / 猪猪侠
 */
public class AtTest {
    public static void main(String[] args) throws Exception {
        Db.conn(); // 初始化数据库
        String roomName = args.length > 0 ? args[0] : "Agent测试群1群";
        String name = args.length > 1 ? args[1] : "猪猪侠";
        System.out.println("=== 测试进入「" + roomName + "」并 @ " + name + " ===");

        WeChatUi ui = new WeChatUi();

        // 确保企微在前台
        ui.ensureForeground();
        Thread.sleep(2000);
        if (!ui.enterGroup(roomName)) {
            System.out.println("无法进入目标群，终止测试");
            return;
        }
        Thread.sleep(1000);

        // 测试带 @发送（固定测试文案）
        ui.sendWithMention(name, "这是一条 @提及测试消息");

        System.out.println("=== @提及测试完成 ===");
    }

    private AtTest() {}
}
