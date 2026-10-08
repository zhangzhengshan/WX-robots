package com.company;

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

import javax.crypto.Cipher;

/**
 * 会话存档解密一条龙：拉取 → RSA解密随机密钥 → DecryptData 解密消息明文
 * 依赖：org.json（json-20240303.jar）
 */
public class DecryptArchiveDemo {

    // ====== 改成你自己的企业配置（与 Main.java 中一致）======
    private static final String CORPID = "wwe3ff74260674563c";
    private static final String SECRET = "h20iQ5sViOxhgLqdGjsHNkNOqN-7JyEX2XuHNI1VQbo";
    // 企业管理后台「聊天内容存档」生成密钥对时下载的 RSA 私钥（PKCS#8 PEM）
    private static final String PRIVATE_KEY_PATH =
            "F:\\Orb\\private_pem\\private_key.pem";

    /** 读取 PEM 私钥：去掉头尾标记与所有空白，Base64 解码为 DER（PKCS#8） */
    private static byte[] loadPrivateKeyDer(String path) throws Exception {
        String pem = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)
                .replaceAll("-----BEGIN [A-Z ]*PRIVATE KEY-----", "")
                .replaceAll("-----END [A-Z ]*PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        return Base64.getDecoder().decode(pem);
    }

    /** 第①步：用 RSA 私钥解密 encrypt_random_key，得到对称加密用的 encrypt_key */
    private static String rsaDecryptRandomKey(String encryptRandomKey, PrivateKey privateKey) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        byte[] keyBytes = cipher.doFinal(Base64.getDecoder().decode(encryptRandomKey));
        return new String(keyBytes, StandardCharsets.UTF_8);
    }

    private static String getProxy() {
        String proxy = System.getenv("WECOM_PROXY");
        if (proxy == null || proxy.trim().isEmpty()) {
            return "";
        }
        proxy = proxy.trim();
        if (!proxy.contains("://")) {
            proxy = "socks5://" + proxy;
        }
        return proxy;
    }

    public static void main(String[] args) throws Exception {
        String proxy = getProxy();

        // 0. 载入 RSA 私钥
        PrivateKey privateKey;
        try {
            byte[] der = loadPrivateKeyDer(PRIVATE_KEY_PATH);
            privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
            System.out.println("[key] RSA 私钥加载成功");
        } catch (Exception e) {
            System.err.println("[key] 读取 RSA 私钥失败：" + e.getMessage());
            System.err.println("      请确认 private.pem 路径正确，且为 PKCS#8 格式（-----BEGIN PRIVATE KEY-----）");
            return;
        }

        // 1. 初始化 SDK
        long sdk = Finance.NewSdk();
        long ret = Finance.Init(sdk, CORPID, SECRET);
        if (ret != 0L) {
            System.err.println("Init 失败 ret=" + ret);
            Finance.DestroySdk(sdk);
            return;
        }
        System.out.println("[init] SDK 初始化成功");

        // 2. 拉取会话存档（首次 seq=0，最多 1000 条）
        long slice = Finance.NewSlice();
        ret = Finance.GetChatData(sdk, 0L, 1000L, proxy, "", 30, slice);
        if (ret != 0L) {
            System.err.println("GetChatData 失败 ret=" + ret);
            Finance.FreeSlice(slice);
            Finance.DestroySdk(sdk);
            return;
        }
        String resp = Finance.GetContentFromSlice(slice);
        Finance.FreeSlice(slice);

        // 3. 解析 JSON
        JSONObject root = new JSONObject(resp);
        if (root.optInt("errcode", -1) != 0) {
            System.err.println("GetChatData errcode=" + root.optInt("errcode")
                    + " errmsg=" + root.optString("errmsg"));
            Finance.DestroySdk(sdk);
            return;
        }
        JSONArray chatdata = root.optJSONArray("chatdata");
        if (chatdata == null || chatdata.length() == 0) {
            System.out.println("没有可解密的会话记录（chatdata 为空）");
            Finance.DestroySdk(sdk);
            return;
        }
        System.out.println("[fetch] 拉取到 " + chatdata.length() + " 条记录，开始解密...\n");

        // 4. 逐条解密
        int ok = 0, fail = 0;
        for (int i = 0; i < chatdata.length(); i++) {
            JSONObject item = chatdata.getJSONObject(i);
            long seq = item.optLong("seq", 0);
            String msgid = item.optString("msgid", "");
            // 官方字段名：encrypt_random_key / encrypt_chat_msg
            String encryptRandomKey = item.optString("encrypt_random_key", "");
            String encryptChatMsg = item.optString("encrypt_chat_msg", item.optString("content", ""));

            try {
                // 第①步：RSA 解密随机密钥
                String encryptKey = rsaDecryptRandomKey(encryptRandomKey, privateKey);

                // 第②步：DecryptData 解密消息明文
                long msg = Finance.NewSlice();
                int r = Finance.DecryptData(sdk, encryptKey, encryptChatMsg, msg);
                if (r != 0) {
                    System.err.println("  [seq=" + seq + "] DecryptData 失败 ret=" + r);
                    fail++;
                } else {
                    String plain = Finance.GetContentFromSlice(msg);
                    System.out.println("===== seq=" + seq + "  msgid=" + msgid + " =====");
                    System.out.println(plain);
                    System.out.println();
                    ok++;
                }
                Finance.FreeSlice(msg);
            } catch (Exception e) {
                System.err.println("  [seq=" + seq + "] 解密异常：" + e.getMessage());
                fail++;
            }
        }

        System.out.println("完成：成功 " + ok + " 条，失败 " + fail + " 条");
        Finance.DestroySdk(sdk);
    }
}