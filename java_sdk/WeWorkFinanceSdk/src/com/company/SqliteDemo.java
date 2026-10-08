package com.company;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * SQLite 基础操作 Demo：建库、建表、插入、查询
 * 不需要额外依赖，JDK 自带 JDBC 驱动（org.sqlite 是纯 Java 实现，但 JDK 没内置）。
 * 需要把 sqlite-jdbc jar 放到 classpath。
 *
 * 编译运行（需要下载 sqlite-jdbc.jar）：
 *   javac -encoding UTF-8 -cp "sqlite-jdbc-3.46.1.0.jar" -d bin SqliteDemo.java
 *   java -cp "bin;sqlite-jdbc-3.46.1.0.jar" com.company.SqliteDemo
 */
public class SqliteDemo {

    /** 数据库文件路径（相对当前工作目录） */
    private static final String DB_PATH = "test.db";

    /** JDBC 连接串：文件不存在时自动创建 */
    private static final String JDBC_URL = "jdbc:sqlite:" + DB_PATH;

    public static void main(String[] args) throws Exception {
        // 0. 显式加载 SQLite JDBC 驱动
        Class.forName("org.sqlite.JDBC");

        // 1. 连接数据库（文件不存在会自动创建）
        Connection conn = DriverManager.getConnection(JDBC_URL);
        System.out.println("[1] 已连接数据库: " + DB_PATH);

        // 2. 建表
        Statement st = conn.createStatement();
        st.execute(
            "CREATE TABLE IF NOT EXISTS chat_messages (" +
            "  id       INTEGER PRIMARY KEY AUTOINCREMENT," +
            "  seq      INTEGER," +
            "  msgid    TEXT," +
            "  sender   TEXT," +
            "  roomid   TEXT," +
            "  msg_type TEXT," +
            "  content  TEXT," +
            "  msgtime  INTEGER" +
            ")"
        );
        System.out.println("[2] 表 chat_messages 已就绪");
        st.close();

        // 3. 插入数据（用 PreparedStatement 防注入，addBatch 批量提交）
        String sql = "INSERT INTO chat_messages (seq, msgid, sender, roomid, msg_type, content, msgtime) VALUES (?,?,?,?,?,?,?)";
        PreparedStatement ps = conn.prepareStatement(sql);

        Object[][] rows = {
            {1, "18069129108283026475_1788770315195", "ZhongJingYi", "wrImBKWQAAt_TLjwpyBtQYRyaHVDdX8w", "text",    "这是一次测试",  1788770310884L},
            {2, "17059267073416211799_1788853653168", "ZhongJingYi", "wrImBKWQAAt_TLjwpyBtQYRyaHVDdX8w", "text",    "测试一次的拉取", 1788853649838L},
            {3, "1340119017561599254_1788855748265", "ZhuZhuXia",   "wrImBKWQAAt_TLjwpyBtQYRyaHVDdX8w", "text",    "你好",          1788855744760L},
            {4, "10297308760557291774_1788858122244", "ZhongJingYi", "wrImBKWQAAt_TLjwpyBtQYRyaHVDdX8w", "emotion", "[表情]",        1788858118051L},
            {5, "6552779421039283927_1789191293172", "YuanFoZi",    "wrImBKWQAAW7vicC5ln-ugjX0akmjJhg", "text",    "你干嘛呢",       1789191289206L},
        };

        for (Object[] row : rows) {
            ps.setInt    (1, (int)    row[0]);
            ps.setString (2, (String) row[1]);
            ps.setString (3, (String) row[2]);
            ps.setString (4, (String) row[3]);
            ps.setString (5, (String) row[4]);
            ps.setString (6, (String) row[5]);
            ps.setLong   (7, (long)   row[6]);
            ps.addBatch();
        }
        int[] counts = ps.executeBatch();
        ps.close();
        System.out.println("[3] 已插入 " + counts.length + " 条数据");

        // 4. 查询验证
        Statement qst = conn.createStatement();
        ResultSet rs = qst.executeQuery("SELECT * FROM chat_messages");
        System.out.println("[4] 查询结果:");
        while (rs.next()) {
            System.out.printf("  seq=%d  sender=%s  type=%s  content=%s%n",
                rs.getInt("seq"),
                rs.getString("sender"),
                rs.getString("msg_type"),
                rs.getString("content"));
        }
        rs.close();
        qst.close();

        conn.close();
        System.out.println("[5] 完成，已关闭连接");
    }
}
