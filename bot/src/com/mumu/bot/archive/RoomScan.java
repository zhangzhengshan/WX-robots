package com.mumu.bot.archive;

import com.mumu.bot.config.Config;
import com.tencent.wework.Finance;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;

/**
 * 一次性运维工具：拉取会话存档前 N 条，按 roomid 聚合输出（roomid + 条数 + 最新时间 + 样例），
 * 用于新 corp 下发现群聊 chatid（内部群不在 groupchat/list 里，只能从存档消息反查）。
 *
 * 用法：java ... RoomScan [拉取条数，默认 1000]
 * 依赖 WECOM_PROXY / config 的 proxy 键做 socks5 出口。
 */
public final class RoomScan {

    private static byte[] loadPrivateKeyDer(String path) throws Exception {
        String pem = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)
                .replaceAll("-----BEGIN [A-Z ]*PRIVATE KEY-----", "")
                .replaceAll("-----END [A-Z ]*PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        return Base64.getDecoder().decode(pem);
    }

    /** 群聚合行 */
    static final class Room {
        long count;
        long lastMsgTime;
        String lastSender;
        String lastSnippet;
    }

    public static void main(String[] args) throws Exception {
        int limit = args.length > 0 ? Integer.parseInt(args[0]) : 1000;

        PrivateKey privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(loadPrivateKeyDer(Config.PRIVATE_KEY_PATH)));
        System.out.println("[key] RSA 私钥加载成功 (" + Config.PRIVATE_KEY_PATH + ")");

        long sdk = Finance.NewSdk();
        long ret = Finance.Init(sdk, Config.CORPID, Config.ARCHIVE_SECRET);
        if (ret != 0L) {
            System.err.println("Init 失败 ret=" + ret);
            return;
        }
        System.out.println("[init] SDK 初始化成功 corpid=" + Config.CORPID);

        String proxy = (Config.PROXY == null || Config.PROXY.isBlank())
                ? (System.getenv("WECOM_PROXY") == null ? "" : System.getenv("WECOM_PROXY").trim())
                : Config.PROXY.trim();

        long slice = Finance.NewSlice();
        ret = Finance.GetChatData(sdk, 0L, limit, proxy, "", 30, slice);
        if (ret != 0L) {
            System.err.println("GetChatData 失败 ret=" + ret);
            return;
        }
        JSONObject root = new JSONObject(Finance.GetContentFromSlice(slice));
        Finance.FreeSlice(slice);
        if (root.optInt("errcode", -1) != 0) {
            System.err.println("GetChatData errcode=" + root.optInt("errcode")
                    + " errmsg=" + root.optString("errmsg"));
            return;
        }
        JSONArray chatdata = root.optJSONArray("chatdata");
        System.out.println("[fetch] 拉取到 " + (chatdata == null ? 0 : chatdata.length()) + " 条记录");

        Map<String, Room> rooms = new LinkedHashMap<>();
        int ok = 0, fail = 0, noRoom = 0;
        for (int i = 0; chatdata != null && i < chatdata.length(); i++) {
            JSONObject item = chatdata.getJSONObject(i);
            try {
                String encryptKey;
                Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                rsa.init(Cipher.DECRYPT_MODE, privateKey);
                byte[] keyBytes = rsa.doFinal(Base64.getDecoder()
                        .decode(item.optString("encrypt_random_key", "")));
                encryptKey = new String(keyBytes, StandardCharsets.UTF_8);

                long msg = Finance.NewSlice();
                int r = Finance.DecryptData(sdk, encryptKey, item.optString("encrypt_chat_msg", ""), msg);
                if (r != 0) {
                    fail++;
                    Finance.FreeSlice(msg);
                    continue;
                }
                String plain = Finance.GetContentFromSlice(msg);
                Finance.FreeSlice(msg);
                JSONObject m = new JSONObject(plain);
                String roomid = m.optString("roomid", "");
                if (roomid == null || roomid.isBlank()) {
                    noRoom++;
                    continue;
                }
                Room row = rooms.computeIfAbsent(roomid, k -> new Room());
                row.count++;
                long t = m.optLong("msgtime", 0);
                if (t >= row.lastMsgTime) {
                    row.lastMsgTime = t;
                    row.lastSender = m.optString("from", "");
                    String snippet = "";
                    if ("text".equals(m.optString("msgtype"))) {
                        snippet = m.optJSONObject("text").optString("content", "");
                    }
                    row.lastSnippet = snippet.length() > 40 ? snippet.substring(0, 40) : snippet;
                }
                ok++;
            } catch (Exception e) {
                fail++;
            }
        }

        System.out.println("\n解密成功 " + ok + " 条（单聊/无 roomid " + noRoom + " 条，失败 " + fail + " 条）");
        System.out.println("---- 按群聚合 ----");
        for (Map.Entry<String, Room> e : rooms.entrySet()) {
            Room r = e.getValue();
            String time = r.lastMsgTime > 0
                    ? java.time.Instant.ofEpochMilli(r.lastMsgTime).atZone(java.time.ZoneId.of("Asia/Shanghai"))
                        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                    : "?";
            System.out.println(e.getKey() + "  条数=" + r.count + "  最新=" + time
                    + "  发送者=" + r.lastSender + "  样例=" + r.lastSnippet);
        }
    }
}
