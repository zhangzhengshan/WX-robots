package com.mumu.bot.store;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 群聊映射表 room_map 的读写入口（见 CONTEXT.md「群聊映射表」）。
 * 机器人作用域 = enabled=1 的群；新增群手工插表。
 */
public final class RoomMap {

    /** 一个启用群 */
    public static final class Room {
        public final String roomid;
        public String name;
        public Room(String roomid, String name) {
            this.roomid = roomid;
            this.name = name;
        }
    }

    /** 全部启用群（roomid -> Room，保持表内顺序） */
    public static List<Room> enabledRooms() {
        List<Room> rooms = new ArrayList<>();
        try (PreparedStatement ps = Db.conn().prepareStatement(
                "SELECT roomid, name FROM room_map WHERE enabled=1 ORDER BY roomid");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rooms.add(new Room(rs.getString(1), rs.getString(2)));
            }
        } catch (Exception e) {
            throw new RuntimeException("room_map 查询失败", e);
        }
        return rooms;
    }

    /** roomid -> 群名，仅启用群；查不到返回 null */
    public static String nameOf(String roomid) {
        try (PreparedStatement ps = Db.conn().prepareStatement(
                "SELECT name FROM room_map WHERE roomid=? AND enabled=1")) {
            ps.setString(1, roomid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            throw new RuntimeException("room_map 查询失败: " + roomid, e);
        }
    }

    /** 同步时以 API 权威群名回写（不改变 enabled 状态） */
    public static void updateName(String roomid, String name) {
        synchronized (Db.LOCK) {
            try (PreparedStatement ps = Db.conn().prepareStatement(
                    "UPDATE room_map SET name=? WHERE roomid=?")) {
                ps.setString(1, name);
                ps.setString(2, roomid);
                ps.executeUpdate();
            } catch (Exception e) {
                throw new RuntimeException("room_map 更新失败: " + roomid, e);
            }
        }
    }

    /** P5 管理端：追加群聊（roomid+name），默认 enabled=1；已存在 roomid 则覆盖名称 */
    public static void insert(String roomid, String name) {
        synchronized (Db.LOCK) {
            try (PreparedStatement ps = Db.conn().prepareStatement(
                    "INSERT INTO room_map (roomid, name, enabled, created_at) "
                    + "VALUES (?, ?, 1, ?) "
                    + "ON CONFLICT(roomid) DO UPDATE SET name=excluded.name, enabled=1")) {
                ps.setString(1, roomid);
                ps.setString(2, name);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (Exception e) {
                throw new RuntimeException("room_map 追加失败: " + roomid, e);
            }
        }
    }

    /** 管理端/调试用：全量导出 room_map */
    public static List<Map<String, Object>> dumpAll() {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = Db.conn().prepareStatement(
                "SELECT roomid, name, enabled, created_at FROM room_map ORDER BY roomid");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("roomid", rs.getString(1));
                m.put("name", rs.getString(2));
                m.put("enabled", rs.getInt(3));
                m.put("created_at", rs.getLong(4));
                out.add(m);
            }
        } catch (Exception e) {
            throw new RuntimeException("room_map 导出失败", e);
        }
        return out;
    }

    private RoomMap() {}
}
