package com.mumu.bot;

import com.mumu.bot.config.Config;
import com.mumu.bot.store.Db;
import com.mumu.bot.store.RoomMap;
import com.tencent.wework.Finance;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.HashSet;
import java.util.Set;

/**
 * 修复编码：GetContentFromSlice 输出可能是 Latin-1 编码的 UTF-8 字节
 */
public class SdkTest {

    private static PrivateKey loadPrivateKey() throws Exception {
        String pem = Files.readString(Path.of(Config.PRIVATE_KEY_PATH));
        String b64 = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(b64);
        return KeyFactory.getInstance("RSA")
            .generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static String decryptKey(String encryptedKeyB64, PrivateKey priv) throws Exception {
        byte[] encryptedKey = Base64.getDecoder().decode(encryptedKeyB64);
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, priv);
        byte[] decrypted = cipher.doFinal(encryptedKey);
        return new String(decrypted, "UTF-8");
    }

    /** 修复编码：SDK 返回的 String 可能是 ISO-8859-1 编码的 UTF-8 */
    private static String fixEncoding(String s) {
        // 如果字符串看起来像乱码，尝试 ISO-8859-1 -> UTF-8
        try {
            byte[] bytes = s.getBytes("ISO-8859-1");
            String fixed = new String(bytes, "UTF-8");
            // 如果修复后包含合法中文字符，返回修复版
            if (fixed.matches(".*[\\u4e00-\\u9fff].*") && !s.matches(".*[\\u4e00-\\u9fff].*")) {
                return fixed;
            }
        } catch (Exception e) {}
        return s;
    }

    public static void main(String[] args) throws Exception {
        Db.conn();
        Set<String> scopeRooms = new HashSet<>();
        for (RoomMap.Room r : RoomMap.enabledRooms()) scopeRooms.add(r.roomid);

        PrivateKey privKey = loadPrivateKey();

        long sdk = Finance.NewSdk();
        int ret = Finance.Init(sdk, Config.CORPID, Config.ARCHIVE_SECRET);
        if (ret != 0) { System.out.println("Init FAIL " + ret); return; }

        long slice = Finance.NewSlice();
        ret = Finance.GetChatData(sdk, 0, 100, Config.PROXY, "", 30, slice);
        if (ret != 0) { System.out.println("GetChatData FAIL " + ret); return; }

        String raw = Finance.GetContentFromSlice(slice);
        Finance.FreeSlice(slice);

        JSONObject json = new JSONObject(raw);
        JSONArray chatdata = json.getJSONArray("chatdata");
        System.out.println("Total: " + chatdata.length());

        int maxSeq = 0;

        for (int i = 0; i < chatdata.length(); i++) {
            try {
                JSONObject msg = chatdata.getJSONObject(i);
                int seq = msg.getInt("seq");
                if (seq > maxSeq) maxSeq = seq;

                String encryptKey = msg.getString("encrypt_random_key");
                    String encryptMsg = msg.getString("encrypt_chat_msg");

                    String chatKey;
                    try {
                        chatKey = decryptKey(encryptKey, privKey);
                    } catch (Exception e) {
                        System.out.println("seq=" + seq + " decryptKey FAIL: " + e.getMessage());
                        continue;
                    }

                    long dmsg = Finance.NewSlice();
                    int dret = Finance.DecryptData(sdk, chatKey, encryptMsg, dmsg);
                    if (dret != 0) {
                        System.out.println("seq=" + seq + " DecryptData ret=" + dret);
                        Finance.FreeSlice(dmsg);
                        continue;
                    }
                    if (dret == 0) {
                    String decrypted = Finance.GetContentFromSlice(dmsg);
                    // 修复编码
                    decrypted = fixEncoding(decrypted);

                    try {
                        JSONObject dm = new JSONObject(decrypted);
                        String from = dm.optString("from", "");
                        String roomid = dm.optString("roomid", "");
                        String msgtype = dm.optString("msgtype", "");

                        String content = "";
                        if ("text".equals(msgtype)) {
                            content = dm.getJSONObject("text").getString("content");
                        }
                        System.out.println("seq=" + seq + " inscope=" + scopeRooms.contains(roomid)
                            + " roomid=" + roomid + " room=" + RoomMap.nameOf(roomid)
                            + " from=" + from +
                            " tolist=" + dm.optJSONArray("tolist") +
                            " type=" + msgtype + " content=" + content);
                    } catch (Exception pe) {
                        System.out.println("seq=" + seq + " JSON parse error, raw: " +
                            decrypted.substring(0, Math.min(200, decrypted.length())));
                    }
                }
                Finance.FreeSlice(dmsg);
            } catch (Exception e) {
                // 跳过单条解析错误
            }
        }

        System.out.println("\nMax seq: " + maxSeq);
        Finance.DestroySdk(sdk);
    }
}
