package com.mumu.bot.kb;

import com.mumu.bot.config.Config;
import com.mumu.bot.store.KbStore;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 知识库目录同步（P3，见 ADR-0007）。
 *
 * data/kb 目录是事实源，kb_document 是索引：
 *   - 支持 .docx/.xlsx/.txt/.md，标题 = 文件名去掉扩展名
 *   - 按文件名排序逐个解析；解析失败/内容为空的文件跳过并保留库内旧行（不删）
 *   - 全库总字数 ≤ Config.KB_MAX_CHARS（10 万）：单篇超限则跳过该篇，其余继续
 *   - 目录里消失的文件 → 对应 kb_document 行硬删除
 *   - 同标题不同扩展名（a.docx 与 a.txt）冲突：只收第一个，其余跳过
 *
 * 触发：Main 启动时一次；KbImport 手动入口（不重启重导）。
 * P5 管理端只提供"上传文件/删除文件"，不做在线内容编辑（文件改一个字也须重新上传）。
 */
public final class KbSyncService {

    /** 同步结果（日志/启动摘要用） */
    public static final class Result {
        public int inserted;
        public int updated;
        public int deleted;
        public int skipped;
        public int totalChars;
        public final List<String> lines = new ArrayList<>();
    }

    /**
     * 执行一次目录 → DB 同步。
     * @param verbose true=逐文件打印（KbImport）；false=只打印摘要（Main 启动）
     */
    public Result sync(boolean verbose) {
        Result r = new Result();
        File dir = Config.KB_DIR;
        if (!dir.exists()) {
            // 首次运行：创建空目录，不动 DB（避免因工作目录错误清空知识库）
            dir.mkdirs();
            r.lines.add("[KB] 知识库目录不存在，已创建 " + dir.getAbsolutePath() + "（空，跳过本次同步）");
            return r;
        }
        if (!dir.isDirectory()) {
            throw new IllegalStateException("知识库路径不是目录: " + dir.getAbsolutePath());
        }

        File[] files = dir.listFiles((d, name) -> KbTextExtractor.isSupported(name));
        if (files == null) files = new File[0];
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        Set<String> existingTitles = new HashSet<>(KbStore.titles());
        Set<String> scannedTitles = new HashSet<>();
        Set<String> seenTitles = new HashSet<>();
        List<String[]> accepted = new ArrayList<>(); // {title, text}
        int usedChars = 0;

        for (File f : files) {
            String title = stripExtension(f.getName());
            scannedTitles.add(title);
            if (!seenTitles.add(title)) {
                r.skipped++;
                r.lines.add("[KB] ⏭ 跳过 " + f.getName() + "：与另一文件标题冲突（" + title + "），只保留按文件名排序的第一个");
                continue;
            }
            String text;
            try {
                text = KbTextExtractor.extract(f);
            } catch (Exception e) {
                r.skipped++;
                r.lines.add("[KB] ⏭ 跳过 " + f.getName() + "（解析失败，保留库内旧行）：" + e.getMessage());
                continue;
            }
            if (text.isEmpty()) {
                r.skipped++;
                r.lines.add("[KB] ⏭ 跳过 " + f.getName() + "：转换后内容为空");
                continue;
            }
            if (usedChars + text.length() > Config.KB_MAX_CHARS) {
                r.skipped++;
                r.lines.add("[KB] ⏭ 跳过 " + f.getName() + "：导入后总字数 " + (usedChars + text.length())
                    + " 超过上限 " + Config.KB_MAX_CHARS + "（单篇跳过，其余继续）");
                continue;
            }
            usedChars += text.length();
            accepted.add(new String[]{title, text});
        }

        // 落库（按标题 upsert）
        for (String[] doc : accepted) {
            boolean isNew = !existingTitles.contains(doc[0]);
            KbStore.upsert(doc[0], doc[1]);
            if (isNew) {
                r.inserted++;
                r.lines.add("[KB] ✚ 新增 " + doc[0] + "（" + doc[1].length() + " 字）");
            } else {
                r.updated++;
                r.lines.add("[KB] ↻ 更新 " + doc[0] + "（" + doc[1].length() + " 字）");
            }
        }

        // 删除 diff：库内标题在目录中已彻底消失（解析失败的文件标题仍在 scannedTitles 中，不删）
        for (String t : existingTitles) {
            if (!scannedTitles.contains(t)) {
                KbStore.deleteByTitle(t);
                r.deleted++;
                r.lines.add("[KB] ✖ 删除 " + t + "（目录中已无对应文件）");
            }
        }

        r.totalChars = KbStore.totalChars();
        r.lines.add("[KB] 同步完成：新增 " + r.inserted + " 更新 " + r.updated
            + " 删除 " + r.deleted + " 跳过 " + r.skipped
            + "，库内总字数 " + r.totalChars + "/" + Config.KB_MAX_CHARS);

        if (verbose) {
            for (String line : r.lines) System.out.println(line);
        } else {
            System.out.println(r.lines.get(r.lines.size() - 1));
            for (int i = 0; i < r.lines.size() - 1; i++) System.out.println(r.lines.get(i));
        }
        System.out.flush();
        return r;
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
