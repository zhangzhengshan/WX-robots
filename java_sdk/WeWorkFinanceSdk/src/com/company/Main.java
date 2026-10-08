package com.company;

import com.tencent.wework.Finance;
import java.io.File;
import java.io.FileOutputStream;

public class Main {

    // 从环境变量 WECOM_PROXY 读取代理
    private static String getProxy() {
        String proxy = System.getenv("WECOM_PROXY");
        if (proxy == null || proxy.trim().isEmpty()) {
            System.err.println("未检测到环境变量 WECOM_PROXY，请先设置代理");
            return null;
        }
        proxy = proxy.trim(); // 例如 socks5h://127.0.0.1:1080
        // SDK 底层是 libcurl，代理串必须带协议头；没有则按 socks5 补全
        if (!proxy.contains("://")) {
            proxy = "socks5://" + proxy;
        }
        return proxy;
    }

    public static void main(String[] args) {
        // 用法（代理不再从命令行传，统一读环境变量 WECOM_PROXY）：
        // ./sdktools 1 seq limit timeout            拉取会话存档
        // ./sdktools 2 fileid timeout savefile      拉取媒体文件
        // ./sdktools 3 encrypt_key encrypt_chat_msg 解密
        if (args.length < 2) {
            System.out.println("./sdktools 1(chatmsg) 2(mediadata) 3(decryptdata)\n");
            System.out.println("./sdktools 1 seq limit timeout\n");
            System.out.println("./sdktools 2 fileid timeout savefile\n");
            System.out.println("./sdktools 3 encrypt_key encrypt_chat_msg\n");
            return;
        }

        String proxy = getProxy(); // socks5h://127.0.0.1:1080
        String passwd = "";        // 该代理无需账号密码；如需要则填 "user:pass"
        if (proxy == null) {
            return;
        }

        long ret = 0;
        long sdk = Finance.NewSdk();
        // TODO: 换成你自己的企业 corpid 和 secret
        ret = Finance.Init(sdk, "wwe3ff74260674563c", "h20iQ5sViOxhgLqdGjsHNkNOqN-7JyEX2XuHNI1VQbo");
        if (ret != 0) {
            Finance.DestroySdk(sdk);
            System.out.println("init sdk err ret " + ret);
            return;
        }

        if (args[0].equals("1")) {
            // 拉取会话存档
            int seq = Integer.parseInt(args[1]);
            int limit = Integer.parseInt(args[2]);
            int timeout = Integer.parseInt(args[3]);

            long slice = Finance.NewSlice();
            ret = Finance.GetChatData(sdk, seq, limit, proxy, passwd, timeout, slice);
            if (ret != 0) {
                System.out.println("getchatdata ret " + ret);
                Finance.FreeSlice(slice);
                Finance.DestroySdk(sdk);
                return;
            }
            System.out.println("getchatdata :" + Finance.GetContentFromSlice(slice));
            Finance.FreeSlice(slice);
        } else if (args[0].equals("2")) {
            // 拉取媒体文件（分片循环）
            String sdkfileid = args[1];
            int timeout = Integer.parseInt(args[2]);
            String savefile = args[3];

            String indexbuf = "";
            while (true) {
                long media_data = Finance.NewMediaData();
                ret = Finance.GetMediaData(sdk, indexbuf, sdkfileid, proxy, passwd, timeout, media_data);
                if (ret != 0) {
                    System.out.println("getmediadata ret:" + ret);
                    Finance.FreeMediaData(media_data);
                    break;
                }
                System.out.printf("getmediadata outindex len:%d, data_len:%d, is_finis:%d\n",
                        Finance.GetIndexLen(media_data), Finance.GetDataLen(media_data),
                        Finance.IsMediaDataFinish(media_data));
                try {
                    FileOutputStream outputStream = new FileOutputStream(new File(savefile), true);
                    outputStream.write(Finance.GetData(media_data));
                    outputStream.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }

                if (Finance.IsMediaDataFinish(media_data) == 1) {
                    Finance.FreeMediaData(media_data);
                    break;
                } else {
                    indexbuf = Finance.GetOutIndexBuf(media_data);
                    Finance.FreeMediaData(media_data);
                }
            }
        } else if (args[0].equals("3")) {
            // 解密
            String encrypt_key = args[1];
            String encrypt_chat_msg = args[2];

            long msg = Finance.NewSlice();
            ret = Finance.DecryptData(sdk, encrypt_key, encrypt_chat_msg, msg);
            if (ret != 0) {
                System.out.println("decrypt ret " + ret);
                Finance.FreeSlice(msg);
                Finance.DestroySdk(sdk);
                return;
            }
            System.out.println("decrypt ret:" + ret + " msg:" + Finance.GetContentFromSlice(msg));
            Finance.FreeSlice(msg);
        } else {
            System.out.println("wrong args " + args[0]);
        }
        Finance.DestroySdk(sdk);
    }
}
