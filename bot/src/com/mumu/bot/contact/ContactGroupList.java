package com.mumu.bot.contact;

import com.mumu.bot.store.Db;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 客户群列表查询 + room_map 切换工具（命令行入口，运维用，不进常驻进程）。
 *
 * 用法：
 *   java ... ContactGroupList                 列出全部客户群（chat_id + 群名 + 成员数）
 *   java ... ContactGroupList enable <chatid> 停用 room_map 全部群，仅启用指定群（群名取自 API）
 *
 * 依赖 SSH 隧道（proxy=socks5://127.0.0.1:1080，contact API 走 ECS 出口，见 ADR-0015）。
 */
public final class ContactGroupList {

    public static void main(String[] args) throws Exception {
        ContactApiClient client = new ContactApiClient();

        // enable 模式：先拿到目标群名，再切 room_map
        if (args.length >= 2 && "enable".equals(args[0])) {
            String chatId = args[1];
            String name = client.getGroupChat(chatId).optString("name", chatId);
            switchRoom(chatId, name);
            dumpRoomMap();
            return;
        }

        // 默认：分页列出全部客户群
        String cursor = null;
        int page = 0;
        while (true) {
            JSONObject resp = client.listGroupChats(cursor, 100);
            System.out.println("[raw] " + resp);
            JSONArray list = resp.optJSONArray("group_chat_list");
            if (list == null || list.isEmpty()) {
                if (page == 0) {
                    System.out.println("(无客户群，检查 contact.secret 权限/客户群范围)");
                }
                break;
            }
            for (int i = 0; i < list.length(); i++) {
                String chatId = list.getJSONObject(i).optString("chat_id");
                try {
                    JSONObject info = client.getGroupChat(chatId);
                    System.out.println(chatId + "  " + info.optString("name")
                        + "  (成员 " + info.optInt("member_count", -1) + ")");
                } catch (Exception e) {
                    System.out.println(chatId + "  <详情获取失败: " + e.getMessage() + ">");
                }
            }
            cursor = resp.optString("next_cursor", "");
            page++;
            if (cursor.isEmpty()) {
                break;
            }
        }
    }

    /** 停用 room_map 全部群，仅启用指定群（INSERT OR IGNORE，保留既有行名） */
    private static void switchRoom(String chatId, String name) throws Exception {
        try (PreparedStatement ps = Db.conn().prepareStatement("UPDATE room_map SET enabled=0")) {
            int n = ps.executeUpdate();
            System.out.println("已停用 " + n + " 个旧群");
        }
        try (PreparedStatement ps = Db.conn().prepareStatement(
                "INSERT OR IGNORE INTO room_map (roomid, name, enabled, created_at) VALUES (?,?,1,?)")) {
            ps.setString(1, chatId);
            ps.setString(2, name);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = Db.conn().prepareStatement(
                "UPDATE room_map SET enabled=1, name=? WHERE roomid=?")) {
            ps.setString(1, name);
            ps.setString(2, chatId);
            ps.executeUpdate();
        }
        System.out.println("已启用: " + chatId + "  " + name);
    }

    private static void dumpRoomMap() throws Exception {
        System.out.println("---- room_map 现状 ----");
        try (PreparedStatement ps = Db.conn().prepareStatement(
                "SELECT roomid, name, enabled FROM room_map ORDER BY enabled DESC, roomid");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                System.out.println((rs.getInt(3) == 1 ? "[启用] " : "[停用] ")
                    + rs.getString(1) + "  " + rs.getString(2));
            }
        }
    }
}
