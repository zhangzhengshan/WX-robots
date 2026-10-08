package com.mumu.bot.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * kb_document 表访问（P3，见 ADR-0003/0007）。
 *
 * kb_document 是 data/kb 目录的索引：事实源是目录里的 docx/xlsx/txt/md 文件，
 * 标题 = 文件名去掉扩展名。同步逻辑见 ai.KbSyncService（upsert / 删除 diff）。
 * content 为转换后的纯文本，拼入 LLM 系统提示词；全库总字数硬上限 10 万。
 *
 * 跨线程写操作在 synchronized(Db.LOCK) 内（与 OutboundQueue 同约定）。
 */
public final class KbStore {

    /** 一篇知识库文档（kb_document 一行的内存映像） */
    public static final class Doc {
        public final String title;
        public final String content;
        public final int charCount;

        public Doc(String title, String content, int charCount) {
            this.title = title;
            this.content = content;
            this.charCount = charCount;
        }
    }

    /** 全量文档（按标题排序，拼装顺序稳定） */
    public static List<Doc> all() {
        List<Doc> docs = new ArrayList<>();
        try (PreparedStatement ps = Db.conn().prepareStatement(
                "SELECT title, content, char_count FROM kb_document ORDER BY title")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    docs.add(new Doc(rs.getString(1), rs.getString(2), rs.getInt(3)));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("kb_document 读取失败: " + e.getMessage(), e);
        }
        return docs;
    }

    /** 库内已有标题集合（同步 diff 用） */
    public static List<String> titles() {
        List<String> titles = new ArrayList<>();
        try (PreparedStatement ps = Db.conn().prepareStatement(
                "SELECT title FROM kb_document ORDER BY title");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) titles.add(rs.getString(1));
        } catch (Exception e) {
            throw new RuntimeException("kb_document 标题读取失败: " + e.getMessage(), e);
        }
        return titles;
    }

    /** 全库总字数 */
    public static int totalChars() {
        try (PreparedStatement ps = Db.conn().prepareStatement(
                "SELECT COALESCE(SUM(char_count),0) FROM kb_document");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) {
            throw new RuntimeException("kb_document 字数统计失败: " + e.getMessage(), e);
        }
    }

    /** 按标题 upsert（同名文件重新上传后内容被整体替换） */
    public static void upsert(String title, String content) {
        synchronized (Db.LOCK) {
            try (PreparedStatement ps = Db.conn().prepareStatement(
                    "INSERT INTO kb_document (title, content, char_count, updated_at) " +
                    "VALUES (?,?,?,?) " +
                    "ON CONFLICT(title) DO UPDATE SET content=excluded.content, " +
                    "  char_count=excluded.char_count, updated_at=excluded.updated_at")) {
                ps.setString(1, title);
                ps.setString(2, content);
                ps.setInt(3, content.length());
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (Exception e) {
                throw new RuntimeException("kb_document upsert 失败: " + title + " " + e.getMessage(), e);
            }
        }
    }

    /** 按标题硬删除（文件已从目录移除） */
    public static void deleteByTitle(String title) {
        synchronized (Db.LOCK) {
            try (PreparedStatement ps = Db.conn().prepareStatement(
                    "DELETE FROM kb_document WHERE title=?")) {
                ps.setString(1, title);
                ps.executeUpdate();
            } catch (Exception e) {
                throw new RuntimeException("kb_document 删除失败: " + title + " " + e.getMessage(), e);
            }
        }
    }

    private KbStore() {}
}
