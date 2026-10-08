import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * 纯 ADB 操控 MuMu 模拟器企业微信，发送一条自定义消息到指定群聊。
 * 不装任何第三方输入法 / agent，不 hook，不碰协议。
 *
 * 使用前请按你的环境修改下面的常量。
 */
public final class WeComSender {

    // ===================== 按你的环境修改 =====================
    /** MuMu 12 的 ADB 端口是 16384 */
    private static final String DEVICE = "127.0.0.1:16384";
    /** 你本机 adb.exe 的绝对路径，例如 "C:\\platform-tools\\adb.exe" */
    private static final String ADB_PATH = "D:\\Program Files\\Netease\\MuMu Player 12\\shell\\adb.exe";
    /** 目标群聊的名称（要和企微消息列表里显示的完全一致） */
    private static final String TARGET_GROUP = "Agent测试群";
    /** 要发送的消息：中文直接发，不要含空格 */
    private static final String MESSAGE = "你干嘛呢";
    // 1080x1920 分辨率下企微聊天页的固定坐标（dump 失败时兜底）
    private static final int[] INPUT_BOX = {89, 1851, 918, 1890};
    private static final int[] SEND_BTN  = {974, 1842, 1062, 1898};
    // 风控熔断关键词
    private static final String[] RISK_KEYWORDS = {
        "身份验证", "重新登录", "安全验证", "操作频繁",
        "风险提醒", "账号异常", "登录验证", "验证身份"
    };
    private static final File TEMP_DIR =
        new File(System.getProperty("java.io.tmpdir"), "wecom-bot");
    // ==========================================================

    public static void main(String[] args) throws Exception {
        connectDevice();
        WeComSender s = new WeComSender();
        s.ensureForeground();
        s.enterGroupIfNeeded();
        s.sendPlain(MESSAGE);
        System.out.println("✅ 发送流程结束");
    }

    // ===================== ADB 底层封装 =====================

    /** 连接 MuMu（adb 重启后不会自动连上，必须显式 connect），并确认设备在线 */
    public static void connectDevice() throws Exception {
        ProcessBuilder pb = new ProcessBuilder(ADB_PATH, "connect", DEVICE);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        if (!out.contains("connected")) {
            throw new IllegalStateException("adb connect 失败: " + out.trim());
        }
        // get-state 返回 device / offline / unauthorized，确认是 device 才继续
        ProcessBuilder st = new ProcessBuilder(ADB_PATH, "-s", DEVICE, "get-state");
        st.redirectErrorStream(true);
        Process sp = st.start();
        String state = new String(sp.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        sp.waitFor();
        if (!"device".equals(state)) {
            throw new IllegalStateException("设备未就绪，get-state=" + state
                + "。请确认 MuMu 已启动，且 ADB 端口为 " + DEVICE);
        }
        System.out.println("    -> 设备已连接: " + DEVICE);
    }

    /** 执行 adb shell 命令并返回输出文本 */
    public static String shell(String cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(ADB_PATH, "-s", DEVICE, "shell", cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        // 绝不静默吞错：设备没连上时 adb 会在输出里返回这些字样
        String low = out.toLowerCase();
        if (low.contains("device not found") || low.contains("no devices")
                || low.contains("error: closed") || low.contains("cannot connect")
                || low.contains("offline") || low.contains("unauthorized")) {
            throw new IllegalStateException("ADB 命令执行失败 [" + cmd + "]: " + out.trim());
        }
        return out;
    }

    public static void pull(String remote, File local) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(ADB_PATH, "-s", DEVICE, "pull", remote, local.getAbsolutePath());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        if (p.exitValue() != 0 || out.toLowerCase().contains("error")) {
            throw new IllegalStateException("adb pull 失败: " + out.trim());
        }
    }

    public static void tap(int x, int y) throws Exception {
        shell("input tap " + x + " " + y);
    }

    public static void tapCenter(int[] b) throws Exception {
        tap((b[0] + b[2]) / 2, (b[1] + b[3]) / 2);
    }

    /**
     * 关键：用 ProcessBuilder 参数数组传文本，不拼 shell 字符串。
     * 中文走 InputConnection.commitText 直接注入，不乱码；
     * 但文本里不能有空格（会被截断）。
     */
    public static void inputText(String text) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(ADB_PATH, "-s", DEVICE, "shell", "input", "text", text);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        String low = out.toLowerCase();
        if (low.contains("device not found") || low.contains("no devices")
                || low.contains("error: closed") || low.contains("offline")) {
            throw new IllegalStateException("input text 失败: " + out.trim());
        }
    }

    /** 光标移到末尾，连按 20 次退格清空输入框 */
    public static void clearInputBox() throws Exception {
        shell("input keyevent KEYCODE_MOVE_END");
        sleep(200);
        for (int i = 0; i < 20; i++) {
            shell("input keyevent KEYCODE_DEL");
            sleep(30);
        }
    }

    /** uiautomator dump，5 次重试；全部失败返回空 XML（调用方用固定坐标兜底） */
    public static String dumpUi() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            String r = shell("uiautomator dump /sdcard/wecom_ui.xml");
            if (r.contains("dumped")) {
                TEMP_DIR.mkdirs();
                File f = new File(TEMP_DIR, "ui.xml");
                pull("/sdcard/wecom_ui.xml", f);
                if (f.exists() && f.length() > 100) {
                    String xml = Files.readString(f.toPath());
                    if (xml.contains("class=") || xml.contains("text=")) return xml;
                }
            }
            sleep(1500);
        }
        System.out.println("    [dump 全部失败，使用固定坐标模式]");
        return "<hierarchy></hierarchy>";
    }

    public static void screenshot() {
        try {
            shell("screencap -p /sdcard/wecom_risk.png");
            TEMP_DIR.mkdirs();
            File f = new File(TEMP_DIR, "risk_" + System.currentTimeMillis() + ".png");
            pull("/sdcard/wecom_risk.png", f);
            System.out.println("   截图: " + f.getAbsolutePath());
        } catch (Exception e) {
            System.out.println("   截图失败: " + e.getMessage());
        }
    }

    public static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // ===================== 高层业务流程 =====================

    public void ensureForeground() throws Exception {
        String r = shell("dumpsys window | grep mCurrentFocus");
        if (r.contains("com.tencent.wework")) {
            System.out.println("    -> 企微已在前台");
            return;
        }
        shell("monkey -p com.tencent.wework 1");
        System.out.println("    -> 已启动企微，等待加载...");
        sleep(5000);
    }

    public void enterGroupIfNeeded() throws Exception {
        String xml = dumpUi();
        checkRisk(xml);

        int[] edit = findEditText(xml);
        if (edit != null && (xml.contains(TARGET_GROUP) || xml.length() < 50)) {
            System.out.println("    -> 已在 " + TARGET_GROUP + " 聊天页");
            return;
        }

        int[] b = findTextBounds(xml, TARGET_GROUP);
        if (b != null) {
            tapCenter(b);
            System.out.println("    -> 已点击群聊");
            sleep(2000);
        } else {
            System.out.println("    -> (无法 dump，假设已在聊天页)");
        }
    }

    /** 纯文本发送（聊天页 dump 不可靠，直接用固定坐标） */
    public void sendPlain(String text) throws Exception {
        tapCenter(INPUT_BOX);
        sleep(800);
        clearInputBox();
        sleep(300);
        System.out.println("  输入: " + text);
        inputText(text);
        sleep(1000);
        tapCenter(SEND_BTN);
        System.out.println("  -> 已发送: " + text);
        sleep(2000);
    }

    // ===================== 安全与 UI 解析 =====================

    public void checkRisk(String xml) throws Exception {
        if (xml.length() < 50) return;
        for (String kw : RISK_KEYWORDS) {
            if (xml.contains(kw)) {
                System.out.println("\n⛔ 熔断触发！检测到: " + kw + "\n自动化已停止。");
                screenshot();
                System.exit(1);
            }
        }
    }

    private Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private int[] findTextBounds(String xml, String text) throws Exception {
        Document doc = parseXml(xml);
        NodeList nodes = doc.getElementsByTagName("node");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            if (text.equals(el.getAttribute("text"))) return parseBounds(el.getAttribute("bounds"));
        }
        if (xml.length() < 50 && "发送".equals(text)) return SEND_BTN;
        return null;
    }

    private int[] findEditText(String xml) throws Exception {
        Document doc = parseXml(xml);
        NodeList nodes = doc.getElementsByTagName("node");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            if ("android.widget.EditText".equals(el.getAttribute("class")))
                return parseBounds(el.getAttribute("bounds"));
        }
        if (xml.length() < 50) return INPUT_BOX;
        return null;
    }

    private int[] parseBounds(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").matcher(s);
        if (m.find()) {
            return new int[]{
                Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))
            };
        }
        return null;
    }
}