package com.mumu.bot.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 成员名册（DB 版）：按 (roomid, userid) 复合键查询 @用显示名。
 * 写入/同步由 contact.RosterSyncService 负责（见 ADR-0005）。
 *
 * 查不到或 pending=1（API 查不到、待人工补录）时返回 null，
 * 出站侧据此降级为不带 @ 的纯文本回复。
 */
public final class MemberRoster {

    /** 查询某群内某成员的 @用显示名；不存在/待补录返回 null */
    public static String displayName(String roomid, String userid) {
        try (PreparedStatement ps = Db.conn().prepareStatement(
                "SELECT display_name FROM member_roster WHERE roomid=? AND userid=? AND pending=0")) {
            ps.setString(1, roomid);
            ps.setString(2, userid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            throw new RuntimeException("member_roster 查询失败: " + roomid + "/" + userid, e);
        }
    }

    /** API 刷新后仍找不到的 userid 落 pending 行（幂等，不覆盖手工补录） */
    public static void markPending(String roomid, String userid) {
        synchronized (Db.LOCK) {
            try (PreparedStatement ps = Db.conn().prepareStatement(
                    "INSERT INTO member_roster (roomid,userid,name,group_nickname,display_name," +
                    "user_type,manual_override,pending,updated_at) " +
                    "VALUES (?,?,?,?,?,?,0,1,?) " +
                    "ON CONFLICT(roomid,userid) DO UPDATE SET pending=1, updated_at=excluded.updated_at")) {
                long now = System.currentTimeMillis();
                ps.setString(1, roomid);
                ps.setString(2, userid);
                ps.setString(3, null);
                ps.setString(4, "");
                ps.setString(5, null);
                ps.setObject(6, null);
                ps.setLong(7, now);
                ps.executeUpdate();
            } catch (Exception e) {
                throw new RuntimeException("member_roster markPending 失败", e);
            }
        }
    }

    /** 调试/管理端用：导出某群全部成员 */
    public static List<Map<String, Object>> dumpRoom(String roomid) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = Db.conn().prepareStatement(
                "SELECT userid,name,group_nickname,display_name,user_type,manual_override,pending,updated_at " +
                "FROM member_roster WHERE roomid=? ORDER BY user_type DESC, userid")) {
            ps.setString(1, roomid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("userid", rs.getString(1));
                    m.put("name", rs.getString(2));
                    m.put("group_nickname", rs.getString(3));
                    m.put("display_name", rs.getString(4));
                    m.put("user_type", rs.getInt(5));
                    m.put("manual_override", rs.getInt(6));
                    m.put("pending", rs.getInt(7));
                    m.put("updated_at", rs.getLong(8));
                    out.add(m);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("member_roster 导出失败", e);
        }
        return out;
    }

    private MemberRoster() {}
}
