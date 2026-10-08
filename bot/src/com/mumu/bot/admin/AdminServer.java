package com.mumu.bot.admin;

import com.mumu.bot.config.Config;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * 管理端（P5，JDK 内嵌 HttpServer，零新 jar）。
 *
 * - 启动：Main 在 Db 初始化后立即调用 start()，早于 SDK/ADB
 * - 绑定：127.0.0.1:9173（Config.ADMIN_HOST / Config.ADMIN_PORT），无认证无登录
 * - 路由：见 AdminHandler
 * - 异常隔离：handler 内部全 try/catch，请求线程异常绝不拖垮进程
 * - 线程池：默认 fixed（JDK 自管，写操作串行在 Db.LOCK 上）
 *
 * 见 CONTEXT.md「管理端」、ADR-0004 修订。
 */
public final class AdminServer {

    private static HttpServer server;

    /** 启动 HttpServer；端口被占直接退出 */
    public static void start() {
        try {
            InetSocketAddress addr = new InetSocketAddress(Config.ADMIN_HOST, Config.ADMIN_PORT);
            server = HttpServer.create(addr, 0);
            AdminHandler h = new AdminHandler();
            server.createContext("/", h);
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.start();
            log("✅ [Admin] 管理端已启动: http://" + Config.ADMIN_HOST + ":" + Config.ADMIN_PORT);
        } catch (IOException e) {
            // 端口被占等不可恢复错误：直接退出，不动态换端口
            throw new IllegalStateException("管理端启动失败 " + Config.ADMIN_HOST + ":" + Config.ADMIN_PORT
                + "（端口被占？）: " + e.getMessage(), e);
        }
    }

    /** 停止（Main 退出时调；JVM 退出会自动关，这里只是礼貌收尾） */
    public static void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    static void log(String msg) {
        System.out.println(msg);
        System.out.flush();
    }

    private AdminServer() {}
}
