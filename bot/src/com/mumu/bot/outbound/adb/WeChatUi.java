package com.mumu.bot.outbound.adb;

import com.mumu.bot.config.Config;
import java.io.ByteArrayInputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * 企微 UI 高层操作：前台保活、进群、发消息（@提及/纯文本）、熔断。
 * 控件定位用 text 属性而非混淆 resource-id，降低企微版本更新的脆弱性（见 ADR-0001）。
 *
 * TODO: 发送后截图验证消息上屏（"已发送"目前是盲发，ADB 成功 != 消息真的发出）。
 */
public final class WeChatUi {

    /**
     * ADB 会话全局锁（P4）：P3 @回复（AdbSender）与 P4 定时宣传确认（ApiMsgSender）共用同一
     * 模拟器 UI，必须串行，否则两条导航链交叉会导致错发。调用方在 synchronized 块内调 UI 方法。
     */
    public static final Object SESSION_LOCK = new Object();

    /** 固定坐标（1080x1920 分辨率下企微聊天页） */
    private static final int[] INPUT_BOX = {89, 1851, 918, 1890};
    private static final int[] SEND_BTN = {974, 1842, 1062, 1898};

    /** 内存记录当前所在群名（null=未知/在列表页），避免重复导航 */
    private String currentGroupName;

    public void ensureForeground() throws Exception {
        String r = Adb.shell("dumpsys window | grep mCurrentFocus");
        if (r.contains("com.tencent.wework")) {
            log("    -> 企微已在前台");
            return;
        }
        Adb.shell("monkey -p com.tencent.wework 1");
        log("    -> 已启动企微，等待加载...");
        Adb.sleep(5000);
    }

    /**
     * 发送前定位目标群（多群路由，P1+P2）。
     * 核心原则：宁可漏回，不可错群发——无法可靠确认在目标群时返回 false，调用方跳过。
     *
     * 流程：
     *   1. dump 当前页面
     *   2. 若 dump 含目标群名且含"发送"按钮（聊天页特征）→ 已在目标群，直接返回 true
     *      （列表页 dump 也会含群名，但那是列表项文本，不能用来判定"已在群里"）
     *   3. 否则 BACK 一次回消息列表 → 在列表里找群名 → 点击进群 → 再 dump 校验
     *   4. 非企微前台先 ensureForeground
     *
     * 不再做列表滚动查找：用户的群已全部置顶，列表一定能直接看到。
     */
    public boolean enterGroup(String roomName) {
        try {
            // 第 1 步：dump 当前页面，看是否已在目标群
            String xml = Adb.dumpUi();
            checkRisk(xml);

            // 非企微前台：拉起企微后再 dump
            if (!isWeComForeground(xml)) {
                log("    -> 企微不在前台，重新拉起");
                ensureForeground();
                xml = Adb.dumpUi();
                checkRisk(xml);
            }

            // 已在目标群（聊天页：dump 含群名 + 含"发送"按钮）
            if (xml.length() >= 50 && xml.contains(roomName) && isInChatPage(xml)) {
                currentGroupName = roomName;
                log("    -> 已在群 " + roomName);
                return true;
            }

            // 第 2 步：不在目标群 → BACK 回消息列表
            // 企微聊天页 BACK 可能不立刻退出（输入框有焦点时先收起输入法/清空内容），需要循环
            log("    -> 当前不在 " + roomName + "，BACK 回列表");
            for (int backN = 0; backN < 3; backN++) {
                Adb.shell("input keyevent KEYCODE_BACK");
                Adb.sleep(800);
                xml = Adb.dumpUi();
                checkRisk(xml);

                // 非企微前台：BACK 可能把企微退到桌面，重新拉起
                if (!isWeComForeground(xml)) {
                    log("    -> BACK 后企微不在前台，重新拉起");
                    ensureForeground();
                    xml = Adb.dumpUi();
                    checkRisk(xml);
                }

                // 已到列表页（dump 不再是聊天页）→ 退出 BACK 循环
                if (xml.length() >= 50 && !isInChatPage(xml)) {
                    break;
                }
                // 还在聊天页 → 继续 BACK
                log("    -> BACK 第 " + (backN + 1) + " 次后仍在聊天页，重试");
            }

            if (xml.length() < 50) {
                log("    -> 消息列表 dump 失败，无法安全定位 " + roomName + "，跳过本次回复");
                currentGroupName = null;
                return false;
            }

            // 第 3 步：在列表里找群名（不滚动，群已置顶）
            int[] b = findTextBounds(xml, roomName);
            if (b == null) {
                log("    -> 消息列表找不到群「" + roomName + "」（已置顶，不应发生），跳过本次回复");
                currentGroupName = null;
                return false;
            }

            Adb.tapCenter(b);
            log("    -> 已点击进入群聊 " + roomName);
            Adb.sleep(2000);

            // 第 4 步：进群校验（聊天页 dump 偶发失败：失败时信任刚才的列表点击）
            String after = Adb.dumpUi();
            if (after.length() >= 50) {
                // 校验：dump 含群名 + 有"发送"按钮，才确认真进群了
                if (!after.contains(roomName) || !isInChatPage(after)) {
                    log("    -> 进群校验失败（dump 不含 " + roomName + " 或不是聊天页），为防错发跳过本次回复");
                    currentGroupName = null;
                    return false;
                }
            }
            currentGroupName = roomName;
            return true;
        } catch (Exception e) {
            log("    -> 导航异常 " + roomName + ": " + e.getMessage() + "，跳过本次回复");
            currentGroupName = null;
            return false;
        }
    }

    /** 判断当前是否企微前台（dump 里有 com.tencent.wework 包名特征） */
    private boolean isWeComForeground(String xml) {
        // dump 失败时无法判断，乐观假设企微在前台（让后续流程尝试）
        if (xml.length() < 50) return true;
        return xml.contains("com.tencent.wework");
    }

    /**
     * 判断当前是否在某个聊天页（与列表页区分）。
     * 实测特征：聊天页含"发送"按钮 + EditText 输入框；列表页两者都没有。
     * 任一特征命中即判定为聊天页（dump 偶发缺字段，宽松匹配避免漏判）。
     */
    private boolean isInChatPage(String xml) {
        if (xml.length() < 50) return false;
        return xml.contains("发送") || xml.contains("EditText");
    }

    /**
     * 带 @提及发送，严格四步：
     * 1. 聊天输入框输入 @ → 弹出"选择提醒的人"面板
     * 2. 面板搜索框输入人名 → 点击成员 → 面板关闭，输入框自动插入 @名字
     * 3. 回到聊天输入框，输入消息文本
     * 4. 点击发送
     *
     * name 为 null 或选择器流程失败时，降级为纯文本发送 text。
     */
    public void sendWithMention(String name, String text) throws Exception {
        if (name == null) {
            log("  ⚠️ 未知用户名，退化为纯文本回复");
            sendPlain(text);
            return;
        }

        // === 第 1 步：在聊天输入框输入 @ ===
        log("  [1/4] 点击聊天输入框");
        Adb.tapCenter(INPUT_BOX);
        Adb.sleep(800);
        // 先清空残留
        Adb.clearInputBox();
        Adb.sleep(300);

        log("  输入 @ 触发选择器");
        Adb.inputText("@");
        Adb.sleep(2000); // 等待选择器弹出

        // === 第 2 步：在 @选择器面板中搜索并选择人 ===
        // @选择器是弹窗，dump 能成功（聊天页本身 dump 会失败）
        log("  [2/4] 在选择器中搜索: " + name);
        String pickerXml = Adb.dumpUi();

        // 确认选择器确实打开了
        if (!pickerXml.contains("选择提醒的人")) {
            log("  ⚠️ @选择器未弹出，退化为纯文本");
            Adb.clearInputBox();
            Adb.sleep(300);
            sendPlain(text);
            return;
        }

        // 找搜索框并输入人名
        int[] searchBox = findEditText(pickerXml);
        if (searchBox == null) {
            log("  ⚠️ 找不到选择器搜索框，退化为纯文本");
            sendPlain(text);
            return;
        }
        Adb.tapCenter(searchBox);
        Adb.sleep(500);
        Adb.inputText(name);
        Adb.sleep(2000); // 等待筛选

        // dump 筛选结果
        pickerXml = Adb.dumpUi();
        // 在人员列表中查找（必须排除 EditText 搜索框！）
        int[] personBounds = findPersonInPicker(pickerXml, name);
        if (personBounds == null) {
            log("  ⚠️ 选择器中找不到: " + name + "，退化为纯文本");
            // 按返回关闭选择器
            Adb.shell("input keyevent KEYCODE_BACK");
            Adb.sleep(500);
            Adb.clearInputBox();
            Adb.sleep(300);
            sendPlain(text);
            return;
        }

        // 点击成员（不需要点"确定"，点击即选中并关闭面板）
        Adb.tapCenter(personBounds);
        log("  已选择: @" + name);
        Adb.sleep(2000); // 等待面板关闭，回到聊天页

        // === 第 3 步：回到聊天输入框，输入消息文本 ===
        log("  [3/4] 输入消息: " + text);
        // 面板关闭后输入框已有 @名字（企微自动加空格），直接追加文本
        Adb.tapCenter(INPUT_BOX);
        Adb.sleep(500);
        Adb.inputText(text);
        Adb.sleep(1000);

        // === 第 4 步：点击发送 ===
        log("  [4/4] 点击发送");
        // 聊天页 dump 会失败，直接用固定坐标
        Adb.tapCenter(SEND_BTN);
        log("  -> 已发送: @" + name + " " + text);
        Adb.sleep(2000);
        log("  ✅ 发送完成");
    }

    /** 纯文本发送（聊天页 dump 不可用，直接用固定坐标） */
    public void sendPlain(String text) throws Exception {
        // 点击输入框
        Adb.tapCenter(INPUT_BOX);
        Adb.sleep(800);
        // 清空残留
        Adb.clearInputBox();
        Adb.sleep(300);
        // 输入文本
        log("  输入: " + text);
        Adb.inputText(text);
        Adb.sleep(1000);
        // 点击发送（固定坐标）
        Adb.tapCenter(SEND_BTN);
        log("  -> 已发送: " + text);
        Adb.sleep(2000);
    }

    // ===== P4：群发助手导航（企业群发任务人工确认链路，见 ADR-0008）=====
    //
    // 链路（三层）：
    //   消息列表 ──tap「群发助手」(rid=i4o)──▶ 群发助手历史页
    //   历史页 ──tap 最新 ipg 卡片──▶ 任务详情页
    //   详情页 ──校验 rid=epi「待发送」+ rid=epd 内容前缀──▶ rid=cre「发送」按钮
    //   dry-run：校验按钮可见但不点击；生产：tapCenter 后任务立即发出。
    //
    // 注意：历史页卡片正文（含卡片内「发送」按钮）渲染在 uiautomator 不可见的表面上
    // （paj/ipg 节点 dump 为空），所以不能在历史页直接定位卡片内按钮，必须进详情页。

    /**
     * 从任意企微页面归位并进入「群发助手」历史页。
     * @return true=已在历史页（dump 含 ListView rid=iy0）
     */
    public boolean enterQunFaZhuShou() {
        try {
            for (int i = 0; i < 6; i++) {
                String xml = Adb.dumpUi();
                checkRisk(xml);

                if (!isWeComForeground(xml)) {
                    log("    -> 企微不在前台，重新拉起");
                    ensureForeground();
                    continue;
                }
                // 已在历史页
                if (isQunFaHistoryPage(xml)) {
                    log("    -> 已在群发助手历史页");
                    return true;
                }
                // 在任务详情页 → BACK 回历史页
                if (isQunFaDetailPage(xml)) {
                    log("    -> 当前在任务详情页，BACK 回历史页");
                    Adb.shell("input keyevent KEYCODE_BACK");
                    Adb.sleep(1200);
                    continue;
                }
                // 在消息列表（能找到群发助手入口 rid=i4o）→ 点击进入
                int[] entry = findByRid(xml, "i4o", "群发助手");
                if (entry != null) {
                    log("    -> 点击消息列表「群发助手」条目");
                    Adb.tapCenter(entry);
                    Adb.sleep(3000);
                    String after = Adb.dumpUi();
                    checkRisk(after);
                    if (isQunFaHistoryPage(after)) {
                        return true;
                    }
                    // 偶发直接落到详情页 → 下一轮 BACK 归位；否则继续重试
                    continue;
                }
                // 其余页面（聊天页/未知页）→ BACK 归位
                log("    -> 不在消息列表/群发助手，BACK 归位（第 " + (i + 1) + " 次）");
                Adb.shell("input keyevent KEYCODE_BACK");
                Adb.sleep(1000);
            }
            log("    -> ❌ 6 轮内未能进入群发助手历史页，跳过本次 ADB 确认");
            return false;
        } catch (Exception e) {
            log("    -> enterQunFaZhuShou 异常: " + e.getMessage());
            return false;
        }
    }

    /**
     * 历史页收集群发任务卡片（rid=ipg，clickable），按「最新在前」返回。
     * 实测 ListView 内卡片按时间正序排列（越靠下越新）。
     */
    public java.util.List<int[]> listQunFaCardsLatestFirst() throws Exception {
        String xml = Adb.dumpUi();
        checkRisk(xml);
        java.util.List<int[]> cards = new java.util.ArrayList<>();
        Document doc = parseXml(xml);
        NodeList nodes = doc.getElementsByTagName("node");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            String rid = el.getAttribute("resource-id");
            if (rid.endsWith(":id/ipg") && "true".equals(el.getAttribute("clickable"))) {
                int[] b = parseBounds(el.getAttribute("bounds"));
                if (b != null) cards.add(b);
            }
        }
        java.util.Collections.reverse(cards); // 最新在前
        return cards;
    }

    /**
     * 点开一张任务卡片进入详情页，并校验「确实是我们刚创建的那条待发送任务」：
     *   rid=nwf 标题「详情」 + rid=epi「待发送」 + rid=epd 内容前缀匹配。
     * 任一不匹配 → BACK 回历史页并返回 false，调用方尝试上一张卡片（防误点历史任务）。
     *
     * @param expectedText 本次任务的预期文本（取空白折叠后前 20 字符做前缀比对）；
     *                     null/空 = 纯图任务（ADR-0009），跳过 epd 内容比对，
     *                     只校验「待发送」状态 + 后续发送按钮可见。
     *                     风险兜底：靠「每群每天 1 条」业务约束保证前 3 张卡片范围内
     *                     取最新一张「待发送」即可，不混淆历史任务。
     */
    public boolean openPendingTaskAndVerify(int[] card, String expectedText) {
        try {
            Adb.tapCenter(card);
            Adb.sleep(2500);
            String xml = Adb.dumpUi();
            checkRisk(xml);

            if (!isQunFaDetailPage(xml)) {
                log("    -> 点击卡片后未进入详情页，返回历史页");
                backToQunFaHistory();
                return false;
            }
            String status = textByRid(xml, "epi");
            if (!"待发送".equals(status)) {
                log("    -> 卡片状态非「待发送」(实际: " + status + ")，返回历史页试下一张");
                backToQunFaHistory();
                return false;
            }
            // ADR-0009：纯图任务（expectedText=null/空）跳过 epd 内容前缀比对
            if (expectedText == null || expectedText.isBlank()) {
                log("    -> 纯图任务：跳过 epd 内容比对，只校验「待发送」状态");
                return true;
            }
            String shown = textByRid(xml, "epd");
            if (!contentPrefixMatch(expectedText, shown)) {
                log("    -> 卡片内容与本次任务不匹配（期望前缀: "
                    + prefix20(expectedText) + "，实际: " + prefix20(shown) + "），试下一张");
                backToQunFaHistory();
                return false;
            }
            log("    -> 已打开待发送任务详情页，内容校验通过");
            return true;
        } catch (Exception e) {
            log("    -> openPendingTaskAndVerify 异常: " + e.getMessage());
            return false;
        }
    }

    /** 详情页定位「发送」按钮（rid=cre，兜底用 text=发送）；找不到返回 null */
    public int[] findSendButton(String xml) throws Exception {
        int[] b = findByRid(xml, "cre", "发送");
        if (b != null) return b;
        return findTextBounds(xml, "发送");
    }

    /**
     * 点击「发送」按钮。dry-run（Config.P4_DRY_RUN=true）时只记录不点击——
     * 不点发送就不占用客户群接收配额，可安全重复演练整条链路。
     */
    public boolean tapSendButtonDryRunAware(int[] btn) throws Exception {
        if (Config.P4_DRY_RUN) {
            log("    -> 🧪 dry-run：发送按钮已校验可见，跳过最后点击（不占配额）");
            return true;
        }
        log("    -> 点击「发送」按钮");
        Adb.tapCenter(btn);
        Adb.sleep(2500);
        return true;
    }

    /** 从群发助手（历史页/详情页）逐级 BACK 回到消息列表 */
    public void leaveQunFaToMessageList() {
        try {
            for (int i = 0; i < 5; i++) {
                String xml = Adb.dumpUi();
                if (xml.length() >= 50 && findByRid(xml, "i4o", "群发助手") != null) {
                    log("    -> 已回到消息列表");
                    return;
                }
                if (!isWeComForeground(xml)) {
                    ensureForeground();
                    continue;
                }
                Adb.shell("input keyevent KEYCODE_BACK");
                Adb.sleep(1000);
            }
            log("    -> ⚠️ leaveQunFa 5 轮内未确认回到消息列表");
        } catch (Exception e) {
            log("    -> leaveQunFaToMessageList 异常: " + e.getMessage());
        }
    }

    /**
     * P4 清理（ADR-0011）：删除群发助手历史页最新一条卡片。
     * 在详情页点击发送（或 dry-run 跳过）后调用：从详情页 BACK 回历史页 →
     * 长按最新卡片 → 点「删除」→ 点确认浮层「删除」。
     *
     * 设计决策（grilling 7 轮收敛）：
     *   - dry-run 和真发都删（dry-run 删「待发送」、真发删「已发送」）
     *   - 失败不翻转 run 状态（业务已成功），只 WARN + 截图 + 调用方写 alert_throttle
     *   - 会话内重试 3 次（含首次），仍失败放弃告警
     *   - 不补删崩溃残留（无法区分「我们的」和「客户手工建的」）
     *
     * @return true=删除成功或无卡片可删；false=删除失败（调用方写 alert_throttle）
     */
    public boolean deleteLatestQunFaCard() {
        final int MAX_RETRIES = 3;
        try {
            // 1. 从详情页回到历史页
            for (int i = 0; i < 3; i++) {
                String xml = Adb.dumpUi();
                if (isQunFaHistoryPage(xml)) break;
                log("    -> [清理] 从详情页 BACK 回历史页（第 " + (i + 1) + " 次）");
                Adb.shell("input keyevent KEYCODE_BACK");
                Adb.sleep(1200);
            }

            for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
                if (attempt > 0) {
                    log("    -> [清理] 重试第 " + attempt + "/" + (MAX_RETRIES - 1) + " 次");
                    // 先 BACK 关闭可能残留的长按菜单/确认浮层
                    Adb.shell("input keyevent KEYCODE_BACK");
                    Adb.sleep(1000);
                }

                // 2. 获取最新卡片
                java.util.List<int[]> cards = listQunFaCardsLatestFirst();
                if (cards.isEmpty()) {
                    log("    -> [清理] 历史页无卡片（可能已自动清理或 dump 失败）");
                    return true; // 无卡片 = 无需删除 = 成功
                }
                int[] latestCard = cards.get(0);

                // 3. 状态校验（退化模式：卡片内容对 uiautomator 不可见时信任顺序）
                String preXml = Adb.dumpUi();
                checkRisk(preXml);
                boolean dryRun = Config.P4_DRY_RUN;
                String expectedText = dryRun ? "发送" : "已发送";
                if (preXml.contains(expectedText)) {
                    log("    -> [清理] 状态校验通过：卡片含「" + expectedText + "」");
                } else {
                    log("    -> [清理] 状态校验退化：dump 未见「" + expectedText
                        + "」，信任顺序（SESSION_LOCK 串行，最新 ipg 必然是本次）");
                }
                Adb.screenshotTo(Config.SCREENSHOT_DIR,
                    "p4_cleanup_pre_" + (dryRun ? "dry" : "real"));

                // 4. 长按最新卡片
                log("    -> [清理] 长按最新卡片");
                Adb.longPress(latestCard, 800);
                Adb.sleep(1500);

                // 5. 找到弹出的「删除」按钮并点击
                String menuXml = Adb.dumpUi();
                checkRisk(menuXml);
                int[] deleteBtn = findTextBounds(menuXml, "删除");
                if (deleteBtn == null) {
                    log("    -> [清理] ⚠️ 长按后未找到「删除」按钮");
                    continue;
                }
                log("    -> [清理] 点击长按菜单「删除」");
                Adb.tapCenter(deleteBtn);
                Adb.sleep(1500);

                // 6. 找到确认浮层的「删除消息」按钮并点击（实测：浮层按钮文字是「删除消息」而非「删除」）
                String confirmXml = Adb.dumpUi();
                checkRisk(confirmXml);
                int[] confirmBtn = findTextBounds(confirmXml, "删除消息");
                if (confirmBtn == null) {
                    log("    -> [清理] ⚠️ 确认浮层未找到「删除消息」按钮");
                    continue;
                }
                log("    -> [清理] 点击确认浮层「删除消息」");
                Adb.tapCenter(confirmBtn);
                Adb.sleep(2000);

                // 7. 验证 + 截图存证
                String afterXml = Adb.dumpUi();
                checkRisk(afterXml);
                java.util.List<int[]> remaining = listQunFaCardsLatestFirst();
                if (remaining.isEmpty() || remaining.size() < cards.size()) {
                    Adb.screenshotTo(Config.SCREENSHOT_DIR, "p4_cleanup_done");
                    log("    -> [清理] ✅ 已删除最新历史卡片");
                    return true;
                }
                log("    -> [清理] ⚠️ 删除后卡片数未减少，可能未成功");
            }

            // 全部重试失败
            log("    -> [清理] ⚠️ " + MAX_RETRIES + " 次重试均失败");
            Adb.screenshotTo(Config.SCREENSHOT_DIR, "p4_cleanup_failed");
            return false;
        } catch (Exception e) {
            log("    -> [清理] 异常: " + e.getMessage());
            try { Adb.screenshotTo(Config.SCREENSHOT_DIR, "p4_cleanup_err"); } catch (Exception ignore) {}
            return false;
        }
    }

    private void backToQunFaHistory() throws Exception {
        Adb.shell("input keyevent KEYCODE_BACK");
        Adb.sleep(1200);
    }

    private boolean isQunFaHistoryPage(String xml) {
        return xml.length() >= 50
            && xml.contains("群发助手")
            && xml.contains(":id/iy0");   // 历史页 ListView
    }

    private boolean isQunFaDetailPage(String xml) {
        return xml.length() >= 50 && findByRid(xml, "nwf", "详情") != null;
    }

    /** 空白折叠后前 20 字符前缀比对（详情页正文可能截断显示） */
    private boolean contentPrefixMatch(String expected, String shown) {
        if (expected == null || shown == null) return false;
        return prefix20(expected).equals(prefix20(shown));
    }

    private String prefix20(String s) {
        String n = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return n.length() <= 20 ? n : n.substring(0, 20);
    }

    /** 按 resource-id 后缀（如 "cre"）+ 可选精确 text 定位节点 bounds */
    private int[] findByRid(String xml, String ridSuffix, String textEquals) {
        try {
            Document doc = parseXml(xml);
            NodeList nodes = doc.getElementsByTagName("node");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element el = (Element) nodes.item(i);
                String rid = el.getAttribute("resource-id");
                if (!rid.endsWith(":id/" + ridSuffix)) continue;
                if (textEquals != null && !textEquals.equals(el.getAttribute("text"))) continue;
                int[] b = parseBounds(el.getAttribute("bounds"));
                if (b != null) return b;
            }
        } catch (Exception e) {
            log("    -> findByRid 解析异常: " + e.getMessage());
        }
        return null;
    }

    private String textByRid(String xml, String ridSuffix) {
        try {
            Document doc = parseXml(xml);
            NodeList nodes = doc.getElementsByTagName("node");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element el = (Element) nodes.item(i);
                if (el.getAttribute("resource-id").endsWith(":id/" + ridSuffix)) {
                    String t = el.getAttribute("text");
                    return t == null || t.isEmpty() ? null : t;
                }
            }
        } catch (Exception e) {
            log("    -> textByRid 解析异常: " + e.getMessage());
        }
        return null;
    }

    // ===== 安全机制（熔断，见 ADR-0012）=====

    /**
     * 风控关键词检测 → 熔断。
     * 写 data/circuit_breaker 哨兵 + 发 circuit_breaker 告警邮件（绕过节流）+ System.exit(1)。
     * watchdog.ps1 看到哨兵文件 → 不自动重启，等人工确认。
     */
    public void checkRisk(String xml) throws Exception {
        // dump 失败时空 XML 无法检测
        if (xml.length() < 50) return;
        for (String kw : Config.RISK_KEYWORDS) {
            if (xml.contains(kw)) {
                log("");
                log("⛔ 熔断触发！检测到: " + kw);
                log("   自动化已停止。");
                Adb.screenshot();
                com.mumu.bot.alert.CircuitBreaker.trip("企微风控弹窗，关键词命中: " + kw);
                System.exit(1);
            }
        }
    }

    // ===== UI 解析 =====

    private Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));
    }

    private int[] findTextBounds(String xml, String text) throws Exception {
        Document doc = parseXml(xml);
        NodeList nodes = doc.getElementsByTagName("node");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            if (text.equals(el.getAttribute("text"))) {
                return parseBounds(el.getAttribute("bounds"));
            }
        }
        // dump 失败时，"发送"按钮用固定坐标
        if (xml.length() < 50 && "发送".equals(text)) return SEND_BTN;
        return null;
    }

    /**
     * 在 @选择器人员列表中查找指定名字的人。
     * 必须排除 EditText（搜索框本身的 text 也可能等于搜索词）。
     * 外部联系人显示为"名字@微信"，内部员工直接显示名字。
     */
    private int[] findPersonInPicker(String xml, String name) throws Exception {
        Document doc = parseXml(xml);
        NodeList nodes = doc.getElementsByTagName("node");
        // 第一轮：精确匹配（内部员工，如"钟静怡"）
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            String cls = el.getAttribute("class");
            if ("android.widget.EditText".equals(cls)) continue; // 排除搜索框
            String t = el.getAttribute("text");
            if (name.equals(t)) {
                return parseBounds(el.getAttribute("bounds"));
            }
        }
        // 第二轮：前缀匹配（外部联系人，如"猪猪侠@微信"）
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            String cls = el.getAttribute("class");
            if ("android.widget.EditText".equals(cls)) continue;
            String t = el.getAttribute("text");
            if (t.startsWith(name) && t.length() < name.length() + 20) {
                return parseBounds(el.getAttribute("bounds"));
            }
        }
        return null;
    }

    private int[] findEditText(String xml) throws Exception {
        Document doc = parseXml(xml);
        NodeList nodes = doc.getElementsByTagName("node");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            if ("android.widget.EditText".equals(el.getAttribute("class"))) {
                return parseBounds(el.getAttribute("bounds"));
            }
        }
        // dump 失败时用固定坐标
        if (xml.length() < 50) return INPUT_BOX;
        return null;
    }

    private int[] parseBounds(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").matcher(s);
        if (m.find()) {
            return new int[]{
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)),
                Integer.parseInt(m.group(4))
            };
        }
        return null;
    }

    // ===== 工具 =====

    private static void log(String msg) {
        System.out.println(msg);
        System.out.flush();
    }
}
