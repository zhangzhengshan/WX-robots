package com.mumu.bot.kb;

import com.mumu.bot.config.Config;
import com.mumu.bot.store.Db;
import com.mumu.bot.store.KbStore;

/**
 * 知识库手动同步入口（P3 验收，不连真机/SDK）。
 *
 * 用法：java com.mumu.bot.kb.KbImport
 * 把 config.properties 的 kb.dir（默认 data/kb）下的 .docx/.xlsx/.txt/.md
 * 同步进 kb_document 表，并打印每篇标题/字数与全库总字数。
 */
public final class KbImport {

    public static void main(String[] args) {
        Db.conn();
        System.out.println("=== 知识库目录同步 ===");
        System.out.println("目录: " + Config.KB_DIR.getAbsolutePath());

        KbSyncService.Result r = new KbSyncService().sync(true);

        System.out.println();
        System.out.println("=== 库内现状（共 " + KbStore.all().size() + " 篇，"
            + r.totalChars + "/" + Config.KB_MAX_CHARS + " 字）===");
        for (KbStore.Doc d : KbStore.all()) {
            String preview = d.content.replaceAll("\\s+", " ");
            if (preview.length() > 50) preview = preview.substring(0, 50) + "...";
            System.out.println("- " + d.title + "（" + d.charCount + " 字）: " + preview);
        }
    }

    private KbImport() {}
}
