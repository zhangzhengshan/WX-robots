package com.mumu.bot.contact;

import com.mumu.bot.store.Db;
import com.mumu.bot.store.MemberRoster;
import com.mumu.bot.store.RoomMap;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 成员名册同步（见 ADR-0005）：
 *   - 启动全量、每 5 分钟轮询 room_map 启用群
 *   - 以 groupchat/get 返回成员全集为唯一权威，单事务内 insert/update/delete diff
 *   - 入群新增、退群硬删除（manual_override 行也删）
 *   - 未知 userid 即时刷新该群；仍查不到落 pending
 */
public final class RosterSyncService {

    /** 轮询周期 5 分钟 */
    private static final long POLL_INTERVAL_MS = 5 * 60 * 1000L;

    /** 单群同步结果（日志/测试用） */
    public static final class SyncResult {
        public String roomid;
        public String roomName;
        public int added;
        public int updated;
        public int removed;
        public int total;
        public String memberVersion;
        @Override public String toString() {
            return roomName + " [" + roomid + "] 成员=" + total
                + " 新增=" + added + " 更新=" + updated + " 删除=" + removed;
        }
    }

    private final ContactApiClient api;
    private volatile boolean running = true;

    public RosterSyncService(ContactApiClient api) {
        this.api = api;
    }

    /** 启动全量同步所有启用群 */
    public void syncAllRooms() {
        for (RoomMap.Room room : RoomMap.enabledRooms()) {
            try {
                SyncResult r = syncRoom(room.roomid);
                log("[名册] 同步 " + r);
            } catch (Exception e) {
                log("[名册] 同步失败 " + room.roomid + ": " + e.getMessage()
                    + "（保留上次同步结果，等下个周期）");
            }
        }
    }

    /**
     * 同步单个群：API 拉取 -> 事务 diff。
     * API/网络异常向上抛出，不修改任何成员数据。
     */
    public SyncResult syncRoom(String roomid) throws Exception {
        JSONObject group = api.getGroupChat(roomid);

        String apiName = group.optString("name", "");
        if (!apiName.isEmpty()) {
            RoomMap.updateName(roomid, apiName);
        }
        String memberVersion = group.optString("member_version", "");

        // API 成员全集：userid -> (name, nickname, type)
        Map<String, Member> remote = new LinkedHashMap<>();
        JSONArray list = group.optJSONArray("member_list");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject m = list.getJSONObject(i);
                remote.put(m.getString("userid"), new Member(
                    m.optString("name", ""),
                    m.optString("group_nickname", ""),
                    m.optInt("type", 0)));
            }
        }

        SyncResult result;
        synchronized (Db.LOCK) {
            Connection c = Db.conn();
            boolean prevAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                // 库内现有成员
                Map<String, Existing> local = new HashMap<>();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT userid, manual_override, pending FROM member_roster WHERE roomid=?")) {
                    ps.setString(1, roomid);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            local.put(rs.getString(1), new Existing(rs.getInt(2), rs.getInt(3)));
                        }
                    }
                }

                long now = System.currentTimeMillis();
                Set<String> kept = new HashSet<>();
                int added = 0, updated = 0;

                try (PreparedStatement insert = c.prepareStatement(
                        "INSERT INTO member_roster (roomid,userid,name,group_nickname,display_name,"
                        + "user_type,manual_override,pending,updated_at) VALUES (?,?,?,?,?,?,0,0,?)");
                     PreparedStatement updateAuto = c.prepareStatement(
                        "UPDATE member_roster SET name=?, group_nickname=?, display_name=?, user_type=?, "
                        + "pending=0, updated_at=? WHERE roomid=? AND userid=?");
                     PreparedStatement updateKeepName = c.prepareStatement(
                        "UPDATE member_roster SET name=?, group_nickname=?, user_type=?, "
                        + "pending=0, updated_at=? WHERE roomid=? AND userid=?")) {
                    for (Map.Entry<String, Member> e : remote.entrySet()) {
                        String userid = e.getKey();
                        Member m = e.getValue();
                        kept.add(userid);
                        String display = !m.groupNickname.isEmpty() ? m.groupNickname : m.name;
                        Existing ex = local.get(userid);
                        if (ex == null) {
                            insert.setString(1, roomid);
                            insert.setString(2, userid);
                            insert.setString(3, m.name);
                            insert.setString(4, m.groupNickname);
                            insert.setString(5, display);
                            insert.setInt(6, m.type);
                            insert.setLong(7, now);
                            insert.addBatch();
                            added++;
                        } else {
                            if (ex.manualOverride == 1) {
                                // 人工改过名：同步企微名/昵称/类型，但 display_name 保留人工值
                                updateKeepName.setString(1, m.name);
                                updateKeepName.setString(2, m.groupNickname);
                                updateKeepName.setInt(3, m.type);
                                updateKeepName.setLong(4, now);
                                updateKeepName.setString(5, roomid);
                                updateKeepName.setString(6, userid);
                                updateKeepName.addBatch();
                            } else {
                                updateAuto.setString(1, m.name);
                                updateAuto.setString(2, m.groupNickname);
                                updateAuto.setString(3, display);
                                updateAuto.setInt(4, m.type);
                                updateAuto.setLong(5, now);
                                updateAuto.setString(6, roomid);
                                updateAuto.setString(7, userid);
                                updateAuto.addBatch();
                            }
                            updated++;
                        }
                    }
                    insert.executeBatch();
                    updateAuto.executeBatch();
                    updateKeepName.executeBatch();
                }

                // 退群硬删除：库内有、API 全集没有（含 manual_override/pending 行）
                int removed = 0;
                try (PreparedStatement del = c.prepareStatement(
                        "DELETE FROM member_roster WHERE roomid=? AND userid=?")) {
                    for (String userid : local.keySet()) {
                        if (!kept.contains(userid)) {
                            del.setString(1, roomid);
                            del.setString(2, userid);
                            del.addBatch();
                            removed++;
                        }
                    }
                    del.executeBatch();
                }

                c.commit();
                result = new SyncResult();
                result.roomid = roomid;
                result.roomName = !apiName.isEmpty() ? apiName : RoomMap.nameOf(roomid);
                result.added = added;
                result.updated = updated;
                result.removed = removed;
                result.total = remote.size();
                result.memberVersion = memberVersion;
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            } finally {
                c.setAutoCommit(prevAutoCommit);
            }
        }

        if (!memberVersion.isEmpty()) {
            Db.setKv("member_version." + roomid, memberVersion);
        }
        return result;
    }

    /**
     * 存档遇到未知 (roomid,userid) 时的即时兜底：立刻刷新该群再查一次。
     * 仍查不到则落 pending，返回 null（出站降级纯文本）。
     */
    public String resolveUnknown(String roomid, String userid) {
        try {
            syncRoom(roomid);
        } catch (Exception e) {
            log("[名册] 即时刷新失败 " + roomid + ": " + e.getMessage());
        }
        String name = MemberRoster.displayName(roomid, userid);
        if (name == null) {
            MemberRoster.markPending(roomid, userid);
            log("[名册] " + roomid + " 未知成员 " + userid + " 已标记待补录，回复降级纯文本");
        }
        return name;
    }

    /** 启动 5 分钟轮询守护线程 */
    public void startPolling() {
        Thread t = new Thread(() -> {
            log("[名册] 轮询线程启动，周期 " + (POLL_INTERVAL_MS / 1000) + "s");
            while (running) {
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                    syncAllRooms();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    log("[名册] 轮询异常: " + e.getMessage());
                }
            }
        }, "Roster-Sync");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
    }

    // ===== 内部模型 =====

    private static final class Member {
        final String name;
        final String groupNickname;
        final int type;
        Member(String name, String groupNickname, int type) {
            this.name = name;
            this.groupNickname = groupNickname;
            this.type = type;
        }
    }

    private static final class Existing {
        final int manualOverride;
        final int pending;
        Existing(int manualOverride, int pending) {
            this.manualOverride = manualOverride;
            this.pending = pending;
        }
    }

    private static void log(String msg) {
        System.out.println(msg);
        System.out.flush();
    }
}
