package com.mumu.bot;

import com.mumu.bot.contact.ContactApiClient;
import com.mumu.bot.contact.RosterSyncService;
import com.mumu.bot.store.Db;
import com.mumu.bot.store.MemberRoster;
import com.mumu.bot.store.RoomMap;
import java.util.List;
import java.util.Map;

/**
 * P1 验收入口（验收项 1）：全量同步三群名册并打印 room_map / member_roster。
 * 不启动 SDK 监听、不操作 ADB。
 *
 * 用法：java com.mumu.bot.RosterSyncTest
 */
public class RosterSyncTest {

    public static void main(String[] args) {
        Db.conn();
        ContactApiClient api = new ContactApiClient();
        RosterSyncService sync = new RosterSyncService(api);

        System.out.println("===== 开始全量同步 =====");
        sync.syncAllRooms();

        System.out.println();
        System.out.println("===== room_map =====");
        for (Map<String, Object> row : RoomMap.dumpAll()) {
            System.out.printf("  enabled=%s  %-12s  %s%n",
                row.get("enabled"), row.get("name"), row.get("roomid"));
        }

        System.out.println();
        for (RoomMap.Room room : RoomMap.enabledRooms()) {
            List<Map<String, Object>> members = MemberRoster.dumpRoom(room.roomid);
            System.out.println("===== " + room.name + "（" + members.size() + " 人）=====");
            for (Map<String, Object> m : members) {
                System.out.printf("  %-6s @用名=%-10s 企微名=%-10s 群昵称=%-8s 类型=%s%s%n",
                    m.get("userid"),
                    m.get("display_name"),
                    m.get("name"),
                    m.get("group_nickname"),
                    (Integer) m.get("user_type") == 1 ? "内部" : "外部",
                    (Integer) m.get("pending") == 1 ? "  [待补录]" : "");
            }
            System.out.println();
        }

        String lastSeq = Db.getKv("archive.lastSeq");
        System.out.println("archive.lastSeq = " + (lastSeq == null ? "(尚未建立基线)" : lastSeq));
        System.out.println("===== 完成 =====");
    }

    private RosterSyncTest() {}
}
