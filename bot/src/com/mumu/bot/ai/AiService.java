package com.mumu.bot.ai;

import com.mumu.bot.config.Config;
import com.mumu.bot.outbound.OutboundQueue;
import com.mumu.bot.store.KbStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 云端 LLM 问答（P3，见 ADR-0003/0006/0007）。
 *
 * 链路：ArchiveClient 检测到 @提及 → ReplyDispatcher 把问题投递到这里 →
 *       单线程 AI worker：去重预查 → 拼提示词（data/ai_prompt.txt 每次读盘 + kb_document 全量）
 *       → 调 OpenAI 兼容 /chat/completions → 整段单条入队（不再分段，见 ADR-0006）。
 *
 * 约束：
 *   - 单轮问答，无上下文记忆、无 RAG（知识库全量进提示词，≤10 万字）
 *   - 单次 HTTP 超时 60s；进程内不重试（用户再 @ 一次即自然重试）
 *   - 调用失败/返回空：入队固定兜底文案；连败计数，达 AI_FAIL_ALERT_AFTER 打 WARN（P7 接邮件）
 *   - LLM 输出的换行/连续空白折叠为单空格：ADB input text 不支持换行
 *   - 国内中转直连，不走 socks5 代理
 */
public final class AiService {

    /** 空 @（只有 @张飞扬 没有问题）时的固定回复，沿用提示词闲聊骨架 */
    public static final String EMPTY_QUESTION_REPLY = "在的，想喝咖啡或茶回复商品名，或回复“推荐”。";
    /** LLM 调用失败时的客户可见兜底 */
    public static final String FAIL_REPLY = "抱歉，我这边暂时无法处理，请稍后再 @ 我一次。";

    /** worker 的入站问题载体 */
    public static final class Question {
        public final String roomid;
        public final String roomName;
        public final String fromUserid;
        public final String fromName;
        public final int seq;
        public final String text;

        public Question(String roomid, String roomName, String fromUserid,
                        String fromName, int seq, String text) {
            this.roomid = roomid;
            this.roomName = roomName;
            this.fromUserid = fromUserid;
            this.fromName = fromName;
            this.seq = seq;
            this.text = text;
        }
    }

    private final OutboundQueue queue = OutboundQueue.INSTANCE;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private final BlockingQueue<Question> inbox = new LinkedBlockingQueue<>();
    private int consecutiveFailures = 0;
    private Thread worker;

    public AiService() {
        if (Config.AI_BASE_URL.isEmpty() || Config.AI_API_KEY.isEmpty() || Config.AI_MODEL.isEmpty()) {
            throw new IllegalStateException(
                "AI 未配置：请在 config.properties 设置 ai.base-url / ai.api-key / ai.model");
        }
        if (!Config.AI_PROMPT_FILE.exists()) {
            throw new IllegalStateException(
                "找不到 AI 提示词模板：" + Config.AI_PROMPT_FILE.getAbsolutePath());
        }
    }

    /** 启动单线程 AI worker（daemon，不阻塞存档监听线程与 lastSeq 推进） */
    public void start() {
        worker = new Thread(this::workerLoop, "ai-worker");
        worker.setDaemon(true);
        worker.start();
        log("[AI] worker 已启动，模型=" + Config.AI_MODEL + "，提示词=" + Config.AI_PROMPT_FILE);
    }

    /** 投递一个问题（非阻塞；监听线程立即返回） */
    public void submit(Question q) {
        inbox.offer(q);
    }

    // ===== worker =====

    private void workerLoop() {
        while (true) {
            try {
                Question q = inbox.take();
                process(q);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                // worker 不能因为单条问题异常而死掉
                log("[AI] worker 未预期异常: " + t.getMessage());
            }
        }
    }

    private void process(Question q) {
        String sourceId = "at:" + q.seq + ":0";
        log("");
        log("===== AI 处理: seq=" + q.seq + " 群=" + q.roomName
            + " 来自=" + (q.fromName != null ? q.fromName : q.fromUserid)
            + " 问题=" + (q.text.length() > 40 ? q.text.substring(0, 40) + "..." : q.text) + " =====");

        // 去重预查：5 分钟窗内同源（重启重拉同 seq）已入过队 → 连 LLM 都不调
        if (queue.existsInWindow(sourceId)) {
            log("  [AI] source_id=" + sourceId + " 5 分钟内已回复过，跳过（不调 LLM）");
            return;
        }

        String reply;
        try {
            String raw = answer(q.text);
            reply = normalizeForAdb(raw);
            if (reply.isEmpty()) {
                throw new IllegalStateException("模型返回空内容");
            }
            consecutiveFailures = 0;
        } catch (Exception e) {
            consecutiveFailures++;
            log("  ❌ [AI] LLM 调用失败（连败 " + consecutiveFailures + " 次）: " + e.getMessage());
            if (consecutiveFailures >= Config.AI_FAIL_ALERT_AFTER) {
                log("  ⚠️ [AI] 已连败 " + consecutiveFailures + " 次（阈值 "
                    + Config.AI_FAIL_ALERT_AFTER + "），仅埋点 + WARN（不发邮件，避免 LLM 抖动刷屏）");
                com.mumu.bot.alert.AlertThrottle.record("ai_failed");
            }
            enqueueReply(q, sourceId, FAIL_REPLY);
            return;
        }

        log("  [AI] 回复(" + reply.length() + " 字): "
            + (reply.length() > 60 ? reply.substring(0, 60) + "..." : reply));
        enqueueReply(q, sourceId, reply);
    }

    private void enqueueReply(Question q, String sourceId, String text) {
        long id = queue.enqueue(q.roomid, q.roomName, "text", text,
                                true, q.fromName, Config.PRIORITY_AI_REPLY, sourceId);
        if (id == -1) {
            log("  [AI] 入队被去重跳过 source_id=" + sourceId);
        } else {
            log("  [AI] 已入队 id=" + id + " source_id=" + sourceId
                + (q.fromName != null ? " @" + q.fromName : "（纯文本降级）"));
        }
        log("");
    }

    // ===== LLM 调用（AiTest 离线验收也复用）=====

    /**
     * 同步问一次 LLM，返回模型文本回复。失败（网络/HTTP 非 2xx/响应无 content）抛异常。
     * 每次调用都重新读盘提示词模板 + 全量拼知识库：改提示词/同步知识库后无需重启即生效。
     */
    public String answer(String question) throws Exception {
        String template = Files.readString(Config.AI_PROMPT_FILE.toPath(), StandardCharsets.UTF_8);
        String prompt = template
            .replace("{{Content}}", assembleKnowledge())
            .replace("{{query}}", question);

        JSONObject body = new JSONObject()
            .put("model", Config.AI_MODEL)
            .put("messages", new JSONArray()
                .put(new JSONObject().put("role", "user").put("content", prompt)))
            .put("temperature", 0.7)
            .put("stream", false);

        String url = Config.AI_BASE_URL.replaceAll("/+$", "") + "/chat/completions";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(Config.AI_TIMEOUT_SEC))
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Authorization", "Bearer " + Config.AI_API_KEY)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> resp = http.send(req,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String respText = resp.body() == null ? "" : resp.body();
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + " "
                + (respText.length() > 300 ? respText.substring(0, 300) : respText));
        }
        JSONObject json = new JSONObject(respText);
        if (json.has("error") && !json.isNull("error")) {
            throw new IllegalStateException("API error: " + json.get("error"));
        }
        JSONArray choices = json.optJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new IllegalStateException("响应无 choices: "
                + (respText.length() > 300 ? respText.substring(0, 300) : respText));
        }
        String content = choices.getJSONObject(0)
            .getJSONObject("message").optString("content", "");
        return content;
    }

    /** kb_document 全量拼成提示词区块（标题排序；空库给明确占位） */
    private String assembleKnowledge() {
        List<KbStore.Doc> docs = KbStore.all();
        if (docs.isEmpty()) return "（知识库为空）";
        StringBuilder sb = new StringBuilder();
        for (KbStore.Doc d : docs) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("# ").append(d.title).append('\n').append(d.content);
        }
        return sb.toString();
    }

    /** ADB input text 不支持换行：所有连续空白（含换行）折叠为单个空格 */
    static String normalizeForAdb(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").trim();
    }

    private static void log(String msg) {
        System.out.println(msg);
        System.out.flush();
    }
}
