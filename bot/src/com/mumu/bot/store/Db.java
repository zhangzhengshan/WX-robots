package com.mumu.bot.store;

import com.mumu.bot.config.Config;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite 唯一入口。单文件 WAL 模式，备份 = 复制文件（见 ADR-0002）。
 *
 * 单进程低并发：全 JVM 共享一个连接，跨线程的多语句写事务必须在 synchronized(LOCK) 内完成。
 *
 * 表清单：
 *   kv               断点等杂项键值（如 archive.lastSeq、各群 member_version）
 *   room_map         RoomID <-> 群名（机器人作用域，新增群手工插表）
 *   member_roster    (roomid, userid) -> 显示名（见 CONTEXT.md「成员名册」）
 *   inbound_message  机器人相关入站消息（@提及 + 机器人回复）
 *   outbound_message 出站队列状态机（待发送/发送中/已发送/失败，重试+去重，P2 启用）
 *   scheduled_task   定时宣传任务定义（P4 启用）
 *   task_run         幂等键 unique(task_id, planned_date)（P4 启用）
 *   kb_document      提示词知识库文档（P3 启用）
 *   alert_throttle   告警节流（P7 启用）
 */
public final class Db {

    /** 跨线程事务锁 */
    public static final Object LOCK = new Object();

    private static volatile Connection conn;

    /** 获取共享连接（首次调用时建库建表 + 种子数据） */
    public static Connection conn() {
        Connection c = conn;
        if (c != null) return c;
        synchronized (LOCK) {
            if (conn == null) {
                conn = open();
            }
            return conn;
        }
    }

    private static Connection open() {
        try {
            Class.forName("org.sqlite.JDBC");
            // 确保 data 目录存在（db.path 为相对工作目录 bot/ 的路径）
            java.io.File dbFile = new java.io.File(Config.DB_PATH);
            java.io.File parent = dbFile.getParentFile();
            if (parent != null) parent.mkdirs();

            Connection c = DriverManager.getConnection("jdbc:sqlite:" + Config.DB_PATH);
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA foreign_keys=ON");
                st.execute("PRAGMA busy_timeout=5000");
            }
            schema(c);
            seed(c);
            return c;
        } catch (Exception e) {
            throw new IllegalStateException("数据库初始化失败: " + e.getMessage(), e);
        }
    }

    private static void schema(Connection c) throws Exception {
        try (Statement st = c.createStatement()) {
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS kv (" +
                "  k TEXT PRIMARY KEY," +
                "  v TEXT NOT NULL" +
                ")");

            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS room_map (" +
                "  roomid     TEXT PRIMARY KEY," +
                "  name       TEXT NOT NULL," +
                "  enabled    INTEGER NOT NULL DEFAULT 1," +
                "  created_at INTEGER NOT NULL" +
                ")");

            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS member_roster (" +
                "  roomid          TEXT NOT NULL," +
                "  userid          TEXT NOT NULL," +
                "  name            TEXT," +                 // API 企微名
                "  group_nickname  TEXT NOT NULL DEFAULT ''," +
                "  display_name    TEXT," +                 // @用名：群昵称优先，否则企微名
                "  user_type       INTEGER," +              // 1 内部员工 2 外部联系人
                "  manual_override INTEGER NOT NULL DEFAULT 0," +
                "  pending         INTEGER NOT NULL DEFAULT 0," + // 1=API 查不到，待人工补录
                "  updated_at      INTEGER NOT NULL," +
                "  PRIMARY KEY (roomid, userid)" +
                ")");

            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS inbound_message (" +
                "  id          INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  seq         INTEGER NOT NULL," +
                "  roomid      TEXT NOT NULL," +
                "  from_userid TEXT NOT NULL," +
                "  msg_type    TEXT," +
                "  content     TEXT," +
                "  msgtime     INTEGER," +
                "  handled     INTEGER NOT NULL DEFAULT 0," +
                "  created_at  INTEGER NOT NULL" +
                ")");

            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS outbound_message (" +
                "  id           INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  roomid       TEXT NOT NULL," +
                "  msg_type     TEXT NOT NULL DEFAULT 'text'," +
                "  content      TEXT," +
                "  media_path   TEXT," +
                "  is_mention   INTEGER NOT NULL DEFAULT 0," +  // 1=带 @ 提及（见 P2 决策 12）
                "  mention_name TEXT," +                          // @ 用名（display_name）
                "  status       TEXT NOT NULL DEFAULT 'pending'," + // pending/sending/sent/failed
                "  priority     INTEGER NOT NULL DEFAULT 5," +      // 数字越小越优先（AI 回复优先）
                "  attempts     INTEGER NOT NULL DEFAULT 0," +
                "  next_retry_at INTEGER," +
                "  dedup_hash   TEXT," +
                "  error        TEXT," +
                "  created_at   INTEGER NOT NULL," +
                "  sent_at      INTEGER" +
                ")");

            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS scheduled_task (" +
                "  id            INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  name          TEXT NOT NULL," +
                "  msg_type      TEXT NOT NULL DEFAULT 'text'," +
                "  content       TEXT," +
                "  media_path    TEXT," +                        // P4 第二刀（API 附件）
                "  rhythm        TEXT NOT NULL," +               // once/daily/weekly
                "  weekdays      TEXT," +                        // weekly 时 1-7（1=周一）
                "  planned_time  TEXT NOT NULL," +               // HH:mm
                "  target_rooms  TEXT NOT NULL," +               // roomid 逗号分隔（必须在 room_map 内）
                "  enabled       INTEGER NOT NULL DEFAULT 1," +
                "  fire_once_at  INTEGER," +                     // once 节奏的触发时刻
                "  sender_userid TEXT," +                        // 群发 sender（必须是目标群群主，缺省取 my.userid）
                "  api_msgid     TEXT," +                        // 最近一次 API 返回的 msgid（管理端展示用）
                "  created_at    INTEGER NOT NULL" +
                ")");

            // task_run：一次计划触发行 = 一次 API 创建 + 一次 ADB 确认（见 ADR-0008 状态机）
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS task_run (" +
                "  id            INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  task_id       INTEGER NOT NULL," +
                "  planned_date  TEXT NOT NULL," +                // yyyy-MM-dd
                "  status        TEXT NOT NULL DEFAULT 'done'," +// 旧列保留（P4 前占位）
                "  state         TEXT NOT NULL DEFAULT 'pending'," + // 见 TaskRun.State
                "  api_attempts  INTEGER NOT NULL DEFAULT 0," +
                "  adb_attempts  INTEGER NOT NULL DEFAULT 0," +
                "  next_attempt_at INTEGER," +                   // 退避后最早重试时刻
                "  msgid         TEXT," +                        // add_msg_template 返回的 msgid
                "  last_error    TEXT," +
                "  fired_at      INTEGER," +                     // 终态时间
                "  created_at    INTEGER NOT NULL," +
                "  UNIQUE (task_id, planned_date)" +
                ")");

            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS kb_document (" +
                "  id         INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  title      TEXT NOT NULL," +
                "  content    TEXT NOT NULL," +
                "  char_count INTEGER NOT NULL," +
                "  updated_at INTEGER NOT NULL" +
                ")");

            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS alert_throttle (" +
                "  event_type   TEXT PRIMARY KEY," +
                "  last_sent_at INTEGER NOT NULL" +
                ")");

            // 老库平滑升级：P2 给 outbound_message 加 is_mention / mention_name 列
            // SQLite 不支持 IF NOT EXISTS，用 try-catch 包 ALTER（列已存在时重复 ALTER 会抛duplicate column，忽略即可）
            try {
                st.executeUpdate("ALTER TABLE outbound_message ADD COLUMN is_mention INTEGER NOT NULL DEFAULT 0");
            } catch (SQLException dup) { /* 列已存在 */ }
            try {
                st.executeUpdate("ALTER TABLE outbound_message ADD COLUMN mention_name TEXT");
            } catch (SQLException dup) { /* 列已存在 */ }

            // P4 老库平滑升级：scheduled_task 加 sender_userid / api_msgid
            try {
                st.executeUpdate("ALTER TABLE scheduled_task ADD COLUMN sender_userid TEXT");
            } catch (SQLException dup) { /* 列已存在 */ }
            try {
                st.executeUpdate("ALTER TABLE scheduled_task ADD COLUMN api_msgid TEXT");
            } catch (SQLException dup) { /* 列已存在 */ }

            // P4：task_run 加状态机列
            try {
                st.executeUpdate("ALTER TABLE task_run ADD COLUMN state TEXT NOT NULL DEFAULT 'pending'");
            } catch (SQLException dup) { /* 列已存在 */ }
            try {
                st.executeUpdate("ALTER TABLE task_run ADD COLUMN api_attempts INTEGER NOT NULL DEFAULT 0");
            } catch (SQLException dup) { /* 列已存在 */ }
            try {
                st.executeUpdate("ALTER TABLE task_run ADD COLUMN adb_attempts INTEGER NOT NULL DEFAULT 0");
            } catch (SQLException dup) { /* 列已存在 */ }
            try {
                st.executeUpdate("ALTER TABLE task_run ADD COLUMN next_attempt_at INTEGER");
            } catch (SQLException dup) { /* 列已存在 */ }
            try {
                st.executeUpdate("ALTER TABLE task_run ADD COLUMN msgid TEXT");
            } catch (SQLException dup) { /* 列已存在 */ }
            try {
                st.executeUpdate("ALTER TABLE task_run ADD COLUMN last_error TEXT");
            } catch (SQLException dup) { /* 列已存在 */ }
            try {
                st.executeUpdate("ALTER TABLE task_run ADD COLUMN fired_at INTEGER");
            } catch (SQLException dup) { /* 列已存在 */ }

            // P3：kb_document 标题唯一（文件名=标题，upsert 依赖；老库平滑加索引）
            st.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS idx_kb_document_title " +
                              "ON kb_document(title)");
        }
    }

    /** room_map 初始种子：机器人作用域群（德润 corp，chatid 由 ContactGroupList 从客户群 API 拉取）；新群手工 INSERT，不做自动发现 */
    private static void seed(Connection c) throws Exception {
        long now = System.currentTimeMillis();
        Object[][] rooms = {
            {"wr9AFdbAAAn-STmWoFsAwMq6GsNdvt0A", "德润芯·Derien茶咖宠粉群"},
        };
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO room_map (roomid, name, enabled, created_at) VALUES (?,?,1,?)")) {
            for (Object[] r : rooms) {
                ps.setString(1, (String) r[0]);
                ps.setString(2, (String) r[1]);
                ps.setLong(3, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ===== kv 便捷方法 =====

    public static String getKv(String key) {
        try (PreparedStatement ps = conn().prepareStatement("SELECT v FROM kv WHERE k=?")) {
            ps.setString(1, key);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            throw new RuntimeException("kv 读取失败: " + key, e);
        }
    }

    public static void setKv(String key, String value) {
        synchronized (LOCK) {
            try (PreparedStatement ps = conn().prepareStatement(
                    "INSERT INTO kv (k,v) VALUES (?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v")) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.executeUpdate();
            } catch (Exception e) {
                throw new RuntimeException("kv 写入失败: " + key, e);
            }
        }
    }

    private Db() {}
}
