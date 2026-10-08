package com.mumu.bot.ai;

import com.mumu.bot.store.Db;

/**
 * AI 问答离线验收入口（P3，不连真机/SDK、不入队）。
 *
 * 用法：java com.mumu.bot.ai.AiTest 美式咖啡和拿铁有什么区别
 *   拼提示词（data/ai_prompt.txt + kb_document 全量）→ 真实调一次云端 API → 打印回复。
 * 验收建议问题：
 *   1. 知识库内问题（菜单/价格/团建）→ 按知识库原文答，带购买引导
 *   2. 咖啡通用常识（美式和拿铁区别）→ 常识回答，不强推
 *   3. 店外问题（今天天气怎么样）→ 店外兜底，不编造
 */
public final class AiTest {

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("用法: java com.mumu.bot.ai.AiTest <问题文本>");
            System.exit(1);
        }
        String question = String.join(" ", args).trim();

        Db.conn(); // 让 kb_document 可读（无需 ADB/SDK）
        AiService ai = new AiService();

        System.out.println("=== AiTest ===");
        System.out.println("问题: " + question);
        System.out.println("--- 模型回复（原文）---");
        try {
            String raw = ai.answer(question);
            System.out.println(raw);
            System.out.println("--- 实际入队文本（空白折叠后）---");
            System.out.println(AiService.normalizeForAdb(raw));
        } catch (Exception e) {
            System.out.println("调用失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(2);
        }
    }

    private AiTest() {}
}
