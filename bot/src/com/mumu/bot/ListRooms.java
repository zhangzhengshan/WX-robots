package com.mumu.bot;

import com.tencent.wework.Finance;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;
import org.json.JSONArray;

/** 从 SDK 存档中列出所有出现过的 roomid */
public class ListRooms {

    private static PrivateKey loadPrivateKey() throws Exception {
        String pem = Files.readString(Path.of("../docs/pem/private_key.pem"));
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

    public static void main(String[] args) throws Exception {
        PrivateKey privKey = loadPrivateKey();

        long sdk = Finance.NewSdk();
        int ret = Finance.Init(sdk, "ww1c15ca368ed3f02e", "OLfLMJUqR8f5sH3LqeqveR-TukCO0hswzoRVVbHoAFk");
        if (ret != 0) { System.out.println("Init FAIL " + ret); return; }
        System.out.println("Init OK");

        String proxy = "socks5://127.0.0.1:1080";
        
        // Step 1: get max seq
        long slice = Finance.NewSlice();
        ret = Finance.GetChatData(sdk, 0, 1000, proxy, "", 30, slice);
        if (ret != 0) { System.out.println("GetChatData FAIL " + ret); Finance.FreeSlice(slice); return; }

        String raw = Finance.GetContentFromSlice(slice);
        Finance.FreeSlice(slice);

        System.out.println("Raw (first 500 chars): " + raw.substring(0, Math.min(500, raw.length())));
        JSONObject json = new JSONObject(raw);
        if (json.has("errcode")) {
            System.out.println("errcode=" + json.getInt("errcode") + " errmsg=" + json.optString("errmsg"));
        }
        JSONArray chatdata = json.optJSONArray("chatdata");
        if (chatdata == null) {
            System.out.println("No chatdata array!");
            return;
        }
        System.out.println("Page 1 total: " + chatdata.length());

        int maxSeq = 0;
        Set<String> allRoomids = new HashSet<>();

        // Find max seq and collect roomids from first page
        for (int i = 0; i < chatdata.length(); i++) {
            JSONObject msg = chatdata.getJSONObject(i);
            int seq = msg.getInt("seq");
            if (seq > maxSeq) maxSeq = seq;

            // Try decrypt to get roomid
            try {
                String encryptKey = msg.getString("encrypt_random_key");
                String encryptMsg = msg.getString("encrypt_chat_msg");
                String chatKey = decryptKey(encryptKey, privKey);

                long dmsg = Finance.NewSlice();
                int dret = Finance.DecryptData(sdk, chatKey, encryptMsg, dmsg);
                if (dret == 0) {
                    String decrypted = Finance.GetContentFromSlice(dmsg);
                    JSONObject dm = new JSONObject(decrypted);
                    String roomid = dm.optString("roomid", "");
                    String from = dm.optString("from", "");
                    String msgtype = dm.optString("msgtype", "");
                    String content = "";
                    if ("text".equals(msgtype)) {
                        content = dm.getJSONObject("text").optString("content", "");
                    }
                    if (!roomid.isEmpty()) {
                        allRoomids.add(roomid);
                        System.out.println("seq=" + seq + " roomid=" + roomid + " from=" + from + " type=" + msgtype + " content=" + content.substring(0, Math.min(60, content.length())));
                    }
                }
                Finance.FreeSlice(dmsg);
            } catch (Exception e) {
                System.out.println("seq=" + msg.getInt("seq") + " decrypt FAIL: " + e.getMessage());
            }
        }

        System.out.println("\nMax seq: " + maxSeq);
        System.out.println("Unique roomids found on page 1: " + allRoomids);

        // Step 2: fetch from lastSeq-2000 to maxSeq to get older messages from other rooms
        // But first, let's just fetch from seq 0 with larger page
        if (chatdata.length() >= 1000) {
            System.out.println("\nFetching older messages...");
            // Fetch older messages
            long oldSlice = Finance.NewSlice();
            ret = Finance.GetChatData(sdk, 0, 1000, proxy, "", 30, oldSlice);
            // Actually we need to use index properly...
            // Let's just continue from where we are
        }

        Finance.DestroySdk(sdk);
    }
}
