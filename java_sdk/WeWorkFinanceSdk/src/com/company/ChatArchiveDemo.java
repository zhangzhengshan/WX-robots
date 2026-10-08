package com.company;

import com.tencent.wework.Finance;

public class ChatArchiveDemo {

    /**
     * 从 WECOM_PROXY 环境变量读取代理，允许为空（直连模式）。
     * 若未填协议头则自动补 socks5://。
     */
    private static String getProxy() {
        String proxy = System.getenv("WECOM_PROXY");
        if (proxy == null) {
            return "";
        }
        proxy = proxy.trim();
        if (proxy.isEmpty()) {
            return "";
        }
        if (!proxy.contains("://")) {
            proxy = "socks5://" + proxy;
        }
        return proxy;
    }

    public static String fetchChatData(long sdk, long seq, long limit, int timeout, String proxy) {
        long slice = Finance.NewSlice();
        try {
            int ret = Finance.GetChatData(sdk, seq, limit, proxy, "", timeout, slice);
            if (ret != 0) {
                return "{\"errcode\":" + ret + ",\"errmsg\":\"GetChatData failed\"}";
            }
            return Finance.GetContentFromSlice(slice);
        } finally {
            Finance.FreeSlice(slice);
        }
    }

    public static void main(String[] args) {
        String corpid  = "wwe3ff74260674563c";                                       // ← 换成你的
        String secret  = "h20iQ5sViOxhgLqdGjsHNkNOqN-7JyEX2XuHNI1VQbo";               // ← 换成你的

        String proxy = getProxy();
        System.out.println("[proxy] WECOM_PROXY = \"" + proxy + "\"");

        long sdk = Finance.NewSdk();
        int ret = Finance.Init(sdk, corpid, secret);
        if (ret != 0) {
            System.out.println("{\"errcode\":" + ret + ",\"errmsg\":\"Init failed\"}");
            Finance.DestroySdk(sdk);
            return;
        }

        System.out.println("Init ok, fetching...");
        String result = fetchChatData(sdk, 0L, 1000L, 30, proxy);
        System.out.println("WeWork response: " + result);

        Finance.DestroySdk(sdk);
    }
}
