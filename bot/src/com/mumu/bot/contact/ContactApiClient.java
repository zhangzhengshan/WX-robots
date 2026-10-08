package com.mumu.bot.contact;

import com.mumu.bot.config.Config;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/**
 * 企微客户联系 API 客户端（qyapi 直连，见 ADR-0005）。
 *
 * accessToken 仅存内存，到期前 5 分钟刷新，进程重启重新获取，不入库。
 * 封装名册同步与 P4 群发所需接口：gettoken、groupchat/get、add_msg_template、media/upload。
 *
 * ADR-0015：HTTP 栈由 JDK HttpClient 换为 HttpURLConnection + SOCKS 代理，
 * 复用 config.properties 的 proxy=socks5://host:port 出口走 ECS 静态 IP，
 * 解决本地动态出口 IP 触发企微可信 IP 白名单 60020 错误。
 * Proxy 注入到每个 URL.openConnection(proxy)，不在 JVM 全局设置 socksProxyHost，
 * 避免影响 SDK / AI 服务等其他出站路径。
 */
public final class ContactApiClient {

    private static final String BASE = "https://qyapi.weixin.qq.com/cgi-bin/";
    /** token 失效类错误码：强制刷新后重试一次 */
    private static final int ERR_INVALID_TOKEN = 40014;
    private static final int ERR_TOKEN_EXPIRED = 42001;
    private static final int ERR_MISSING_TOKEN = 41001;

    /** 连接超时 ms（qyapi 直连在国内很快，超时 10s 足够） */
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    /** 读超时 ms（token/gettoken/groupchat/get/add_msg_template 都是小响应体） */
    private static final int READ_TIMEOUT_MS = 20_000;
    /** media/upload 读超时 ms（上传后服务器处理素材可能较慢） */
    private static final int MEDIA_READ_TIMEOUT_MS = 60_000;

    private volatile String cachedToken;
    private volatile long tokenExpireAt;

    /**
     * 根据 Config.WECOM_VIA_ECS 构造本次请求的出站 Proxy。
     *
     * true → 从 Config.PROXY 字符串（socks5://host:port）解析出 InetSocketAddress
     *        并返回 Proxy.Type.SOCKS；解析失败（格式不合法 / 未配置）时 WARN 降级到 NO_PROXY。
     * false → 直连本地 IP，返回 Proxy.NO_PROXY（应急回退，直连必然 60020 失败）。
     *
     * 每次请求都调用，不缓存——Config 在 static 块已冻结，结果恒定；
     * 但 SOCKS 代理对象本身无状态，new 一份也无代价。
     */
    private static Proxy outboundProxy() {
        if (!Config.WECOM_VIA_ECS) {
            return Proxy.NO_PROXY;
        }
        String proxyUri = Config.PROXY;
        if (proxyUri == null || proxyUri.isBlank()) {
            System.out.println("[ContactApiClient] ⚠️ wecom.via-ecs=true 但 proxy 键为空，降级直连");
            return Proxy.NO_PROXY;
        }
        try {
            URI uri = URI.create(proxyUri.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            if (host == null || host.isBlank() || port <= 0) {
                throw new IllegalArgumentException("host/port 缺失");
            }
            // 兼容 socks5:// 和 socks://（plink 隧道不做认证，版本号无影响）
            if (scheme != null && !scheme.toLowerCase().startsWith("socks")) {
                System.out.println("[ContactApiClient] ⚠️ proxy 键 scheme 不是 socks，仍按 SOCKS 尝试: " + scheme);
            }
            return new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(host, port));
        } catch (Exception e) {
            System.out.println("[ContactApiClient] ⚠️ 解析 proxy 键失败，降级直连: " + proxyUri + " -> " + e.getMessage());
            return Proxy.NO_PROXY;
        }
    }

    /** 获取有效 accessToken（提前 5 分钟过期） */
    public String accessToken() throws Exception {
        String t = cachedToken;
        if (t != null && System.currentTimeMillis() < tokenExpireAt) {
            return t;
        }
        synchronized (this) {
            if (cachedToken == null || System.currentTimeMillis() >= tokenExpireAt) {
                String url = BASE + "gettoken?corpid=" + Config.CORPID
                    + "&corpsecret=" + Config.CONTACT_SECRET;
                JSONObject json = callGet(url);
                if (json.optInt("errcode", -1) != 0) {
                    throw new IllegalStateException("gettoken 失败: " + json);
                }
                cachedToken = json.getString("access_token");
                long expiresInSec = json.optLong("expires_in", 7200L);
                tokenExpireAt = System.currentTimeMillis() + (expiresInSec - 300) * 1000L;
            }
            return cachedToken;
        }
    }

    /** 强制丢弃缓存（token 失效重试时用） */
    private synchronized void invalidateToken() {
        cachedToken = null;
        tokenExpireAt = 0;
    }

    /**
     * 拉取客户群详情（含成员显示名）。
     * @return group_chat JSON 对象；API 返回错误时抛异常（群解散等交由调用方记日志）
     */
    public JSONObject getGroupChat(String chatId) throws Exception {
        JSONObject body = new JSONObject()
            .put("chat_id", chatId)
            .put("need_name", 1);
        JSONObject resp = postWithTokenRetry("externalcontact/groupchat/get", body);
        if (resp.optInt("errcode", -1) != 0) {
            throw new IllegalStateException("groupchat/get 失败 " + chatId + ": "
                + resp.optInt("errcode") + " " + resp.optString("errmsg"));
        }
        return resp.getJSONObject("group_chat");
    }

    /**
     * 分页拉取客户群 chat_id 列表（文档 path/92117）。
     * @param cursor 上一页返回的 next_cursor，首页传 null/空串
     * @param limit  单页上限（最大 1000，建议 100）
     * @return 原始响应：group_chat_list[{chat_id, status}] + next_cursor
     */
    public JSONObject listGroupChats(String cursor, int limit) throws Exception {
        JSONObject body = new JSONObject().put("limit", limit);
        if (cursor != null && !cursor.isBlank()) {
            body.put("cursor", cursor);
        }
        return postWithTokenRetry("externalcontact/groupchat/list", body);
    }

    /**
     * 创建企业群发任务（P4，文档 path/92135）。
     * 注意：该接口只创建任务并通知 sender，不会直接发到客户群——须由 sender 在企微
     * 「群发助手」内确认发送（P4 用 ADB 自动完成确认，见 ADR-0008）。
     *
     * @param body chat_type=group、chat_id_list、sender、text/attachments
     * @return 原始响应（errcode/errmsg/msgid/fail_list），由调用方判定
     */
    public JSONObject addMsgTemplate(JSONObject body) throws Exception {
        return postWithTokenRetry("externalcontact/add_msg_template", body);
    }

    /**
     * 上传临时素材（P4 第二刀，文档 path/90253）。
     * 复用本类 access_token 缓存（与 add_msg_template 同源 token）。
     *
     * media_id 3 天有效、企业内跨应用可共享；本项目不持久化、每次触发重传（ADR-0009）。
     * 零新 jar：手动拼 multipart/form-data body（boundary + Content-Disposition + 字节流），
     * HttpURLConnection 直接写 OutputStream。
     *
     * @param file 待上传文件（调用方已校验扩展名/大小）
     * @param type image/voice/video/file（voice 仅上传成功但 add_msg_template 不接受，
     *             调用方已在入口 fatal 拦截音频扩展名）
     * @return 原始响应（errcode/errmsg/media_id/created_at/type），由调用方判定
     */
    public JSONObject uploadMedia(File file, String type) throws Exception {
        byte[] data = java.nio.file.Files.readAllBytes(file.toPath());
        String boundary = "----mumubot" + System.currentTimeMillis();
        String fileName = file.getName();
        String CRLF = "\r\n";

        StringBuilder head = new StringBuilder();
        head.append("--").append(boundary).append(CRLF);
        head.append("Content-Disposition: form-data; name=\"media\"; filename=\"")
            .append(fileName).append("\"").append(CRLF);
        head.append("Content-Type: application/octet-stream").append(CRLF);
        head.append(CRLF);

        StringBuilder tail = new StringBuilder();
        tail.append(CRLF).append("--").append(boundary).append("--").append(CRLF);

        byte[] headBytes = head.toString().getBytes(StandardCharsets.UTF_8);
        byte[] tailBytes = tail.toString().getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(headBytes);
        body.write(data);
        body.write(tailBytes);

        String url = BASE + "media/upload?access_token=" + accessToken()
            + "&type=" + type;
        Proxy proxy = outboundProxy();
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection(proxy);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(MEDIA_READ_TIMEOUT_MS);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toByteArray());
        }
        return readJsonResponse(conn);
    }

    // ===== HTTP =====

    private JSONObject postWithTokenRetry(String path, JSONObject body) throws Exception {
        JSONObject resp = callPost(BASE + path + "?access_token=" + accessToken(), body);
        int code = resp.optInt("errcode", 0);
        if (code == ERR_INVALID_TOKEN || code == ERR_TOKEN_EXPIRED || code == ERR_MISSING_TOKEN) {
            invalidateToken();
            resp = callPost(BASE + path + "?access_token=" + accessToken(), body);
        }
        return resp;
    }

    private JSONObject callGet(String url) throws Exception {
        Proxy proxy = outboundProxy();
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection(proxy);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        return readJsonResponse(conn);
    }

    private JSONObject callPost(String url, JSONObject body) throws Exception {
        Proxy proxy = outboundProxy();
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection(proxy);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        return readJsonResponse(conn);
    }

    /** 从 HttpURLConnection 读完整响应体，解析为 JSONObject（错误码/网络异常调用方处理） */
    private JSONObject readJsonResponse(HttpURLConnection conn) throws Exception {
        try (InputStream is = conn.getInputStream()) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return new JSONObject(baos.toString(StandardCharsets.UTF_8));
        } catch (java.net.SocketTimeoutException e) {
            // 读超时：尽量读 errorStream（服务器返回错误体），读不到再抛原异常
            try (InputStream err = conn.getErrorStream()) {
                if (err != null) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = err.read(buf)) != -1) baos.write(buf, 0, n);
                    if (baos.size() > 0) {
                        return new JSONObject(baos.toString(StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception ignore) { }
            throw e;
        } catch (java.io.IOException e) {
            // 非超时 IO 异常：同样尝试读 errorStream
            try (InputStream err = conn.getErrorStream()) {
                if (err != null) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = err.read(buf)) != -1) baos.write(buf, 0, n);
                    if (baos.size() > 0) {
                        return new JSONObject(baos.toString(StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception ignore) { }
            throw e;
        }
    }
}
