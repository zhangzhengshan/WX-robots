package com.mumu.bot.archive;

import com.mumu.bot.config.Config;
import com.mumu.bot.store.Db;
import com.mumu.bot.store.MemberRoster;
import com.mumu.bot.store.RoomMap;
import com.tencent.wework.Finance;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 入站：官方会话存档 SDK 拉取 -> 解密 -> room_map 多群路由 -> 检测 @提及。
 * 机器人只读，不碰企微进程。
 *
 * P1：lastSeq 持久化到 kv（archive.lastSeq），重启断点续拉、不重复回复；
 *     群范围由 room_map enabled=1 决定；成员名走 DB 成员名册，未知成员即时刷新。
 */
public final class ArchiveClient {

    /** @提及回调（携带群路由信息 + 去掉 @名 后的问题原文，P3） */
    public interface Listener {
        void onAtMessage(String roomid, String roomName, String fromUserid, String fromName,
                         int seq, String question);
    }

    /** 未知成员即时解析（由 Main 装配为 RosterSyncService.resolveUnknown） */
    public interface MemberResolver {
        String resolve(String roomid, String userid);
    }

    private static final String KV_LAST_SEQ = "archive.lastSeq";

    private long sdkHandle = 0;
    private PrivateKey rsaKey = null;
    private int lastSeq = 0;
    private volatile boolean running = true;
    private MemberResolver resolver;

    // P7：存档 SDK 拉取连续失败计数（fetchMaxSeq 或 GetChatData 失败均计），达阈值发告警
    private int consecutivePullFailures = 0;
    private static final int PULL_FAIL_THRESHOLD = 10;

    public void setResolver(MemberResolver resolver) {
        this.resolver = resolver;
    }

    public void init() throws Exception {
        rsaKey = loadPrivateKey();
        log("[SDK] RSA 私钥加载成功");

        sdkHandle = Finance.NewSdk();
        int ret = Finance.Init(sdkHandle, Config.CORPID, Config.ARCHIVE_SECRET);
        if (ret != 0) throw new RuntimeException("SDK Init 失败: " + ret);
        log("[SDK] Init OK");

        // lastSeq：优先读 kv 断点；首次运行拉当前最大 seq 作基线（不回放历史）
        String saved = Db.getKv(KV_LAST_SEQ);
        if (saved != null) {
            lastSeq = Integer.parseInt(saved);
            log("[SDK] 从断点续拉 lastSeq=" + lastSeq);
        } else {
            lastSeq = fetchMaxSeq();
            if (lastSeq < 0) throw new RuntimeException("首次启动 fetchMaxSeq 失败，无法建立基线");
            Db.setKv(KV_LAST_SEQ, String.valueOf(lastSeq));
            log("[SDK] 首次启动，基线 lastSeq=" + lastSeq);
        }
    }

    /** 启动监听线程（守护线程，拉取存档检测 @提及） */
    public void start(Listener listener) {
        Thread t = new Thread(() -> listenLoop(listener), "SDK-Listener");
        t.setDaemon(true);
        t.start();
    }

    public void destroy() {
        running = false;
        if (sdkHandle != 0) Finance.DestroySdk(sdkHandle);
    }

    // ===== 监听循环 =====

    private void listenLoop(Listener listener) {
        log("[Listener] 监听线程启动，作用群 " + RoomMap.enabledRooms().size() + " 个");
        while (running) {
            boolean pullOk = false;
            try {
                int newMax = fetchMaxSeq();
                if (newMax >= 0) {
                    pullOk = true; // fetchMaxSeq 成功
                    if (newMax > lastSeq) {
                        int fetchFrom = lastSeq;
                        int fetchCount = Math.min(newMax - lastSeq, 1000);
                        log("[Listener] 新消息: seq " + (lastSeq + 1) + "-" + newMax);

                        long slice = Finance.NewSlice();
                        int ret = Finance.GetChatData(
                            sdkHandle, fetchFrom, fetchCount, Config.PROXY, "", 30, slice);
                        if (ret == 0) {
                            String raw = Finance.GetContentFromSlice(slice);
                            processMessages(raw, listener);
                        } else {
                            log("[Listener] GetChatData 失败: " + ret);
                            pullOk = false;
                        }
                        Finance.FreeSlice(slice);
                        // 处理完才推进断点：重启不会对旧消息重复回复
                        lastSeq = newMax;
                        Db.setKv(KV_LAST_SEQ, String.valueOf(lastSeq));
                    }
                }
            } catch (Exception e) {
                log("[Listener] 异常: " + e.getMessage());
                pullOk = false;
            }
            // P7：连续失败计数 → 达阈值发 sdk_pull_failed 告警
            if (pullOk) {
                consecutivePullFailures = 0;
            } else {
                consecutivePullFailures++;
                if (consecutivePullFailures >= PULL_FAIL_THRESHOLD) {
                    com.mumu.bot.alert.MailAlerter.alert(
                        com.mumu.bot.alert.MailAlerter.SDK_PULL_FAILED,
                        "存档 SDK 拉取连续失败 " + consecutivePullFailures + " 次（POLL_INTERVAL="
                            + Config.POLL_INTERVAL + "ms，约 " + (consecutivePullFailures * Config.POLL_INTERVAL / 1000) + " 秒无新数据）");
                    consecutivePullFailures = 0; // 重置计数，靠 30 分钟节流防刷屏
                }
            }
            sleep(Config.POLL_INTERVAL);
        }
    }

    /** @return 最大 seq；失败返回 -1（区别于合法 seq=0） */
    private int fetchMaxSeq() {
        try {
            long slice = Finance.NewSlice();
            int ret = Finance.GetChatData(sdkHandle, 0, 1000, Config.PROXY, "", 30, slice);
            if (ret == 0) {
                String raw = Finance.GetContentFromSlice(slice);
                JSONObject json = new JSONObject(raw);
                JSONArray data = json.getJSONArray("chatdata");
                int max = 0;
                for (int i = 0; i < data.length(); i++) {
                    int seq = data.getJSONObject(i).getInt("seq");
                    if (seq > max) max = seq;
                }
                Finance.FreeSlice(slice);
                return max;
            }
            Finance.FreeSlice(slice);
        } catch (Exception e) {
            log("[Listener] fetchMaxSeq 异常: " + e.getMessage());
        }
        return -1;
    }

    private void processMessages(String raw, Listener listener) {
        Set<String> scopeRooms = new HashSet<>();
        for (RoomMap.Room r : RoomMap.enabledRooms()) {
            scopeRooms.add(r.roomid);
        }

        try {
            JSONObject json = new JSONObject(raw);
            JSONArray chatdata = json.getJSONArray("chatdata");

            for (int i = 0; i < chatdata.length(); i++) {
                try {
                    JSONObject msg = chatdata.getJSONObject(i);
                    int seq = msg.getInt("seq");
                    String encryptKey = msg.getString("encrypt_random_key");
                    String encryptMsg = msg.getString("encrypt_chat_msg");

                    String chatKey;
                    try {
                        chatKey = decryptKey(encryptKey);
                    } catch (Exception e) { continue; }

                    long dmsg = Finance.NewSlice();
                    int dret = Finance.DecryptData(sdkHandle, chatKey, encryptMsg, dmsg);
                    if (dret == 0) {
                        String decrypted = Finance.GetContentFromSlice(dmsg);
                        JSONObject dm = new JSONObject(decrypted);

                        String roomid = dm.optString("roomid", "");
                        // 只处理 room_map 启用群
                        if (!scopeRooms.contains(roomid)) {
                            Finance.FreeSlice(dmsg);
                            continue;
                        }

                        String from = dm.optString("from", "");
                        if (Config.MY_USERID.equals(from)) {
                            Finance.FreeSlice(dmsg);
                            continue; // 自己发的消息跳过
                        }

                        // @ 检测：必须是文本消息，且正文里包含 "@张飞扬"（MY_NAME）
                        // tolist 不可靠（@all/广播/系统消息都可能命中），改为严格文本匹配
                        String msgType = dm.optString("msgtype", "");
                        if (!"text".equals(msgType)) {
                            Finance.FreeSlice(dmsg);
                            continue;
                        }
                        if (Config.MY_NAME.isEmpty()) {
                            Finance.FreeSlice(dmsg);
                            continue; // 未配置 my.name，无法识别 @
                        }
                        String content = "";
                        if (dm.has("text")) {
                            content = dm.getJSONObject("text").optString("content", "");
                        }
                        String mentionToken = "@" + Config.MY_NAME;
                        if (!content.contains(mentionToken)) {
                            Finance.FreeSlice(dmsg);
                            continue; // 文本里没 @张飞扬，不触发
                        }

                        String name = MemberRoster.displayName(roomid, from);
                        if (name == null && resolver != null) {
                            // 未知成员：即时刷新名册兜底，仍无则 null（降级纯文本）
                            name = resolver.resolve(roomid, from);
                        }
                        String roomName = RoomMap.nameOf(roomid);
                        // 去掉 "@张飞扬" 后剩下的才是问题原文（可能为空 = 空 @）
                        String question = content.replace(mentionToken, "").trim();
                        log("[Listener] @了我 seq=" + seq + " 群=" + roomName
                            + " 来自=" + (name != null ? name : from)
                            + " 问题=" + (question.isEmpty() ? "(空 @)"
                                : question.length() > 40 ? question.substring(0, 40) + "..." : question));
                        listener.onAtMessage(roomid, roomName, from, name, seq, question);
                    }
                    Finance.FreeSlice(dmsg);
                } catch (Exception e) {
                    // 跳过单条解析错误，不影响同批其他消息
                }
            }
        } catch (Exception e) {
            log("[Listener] processMessages 异常: " + e.getMessage());
        }
    }

    // ===== SDK 私钥 =====

    private PrivateKey loadPrivateKey() throws Exception {
        String pem = Files.readString(Path.of(Config.PRIVATE_KEY_PATH));
        String b64 = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(b64);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private String decryptKey(String encryptedKeyB64) throws Exception {
        byte[] encryptedKey = Base64.getDecoder().decode(encryptedKeyB64);
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, rsaKey);
        byte[] decrypted = cipher.doFinal(encryptedKey);
        return new String(decrypted, "UTF-8");
    }

    // ===== 工具 =====

    private static void log(String msg) {
        System.out.println(msg);
        System.out.flush();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
