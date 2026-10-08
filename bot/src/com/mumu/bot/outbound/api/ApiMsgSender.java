package com.mumu.bot.outbound.api;

import com.mumu.bot.contact.ContactApiClient;
import com.mumu.bot.outbound.adb.Adb;
import com.mumu.bot.outbound.adb.WeChatUi;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * P4 定时宣传发送器（企业群发 API + ADB 确认，见 ADR-0008 / ADR-0009）。
 *
 * 与 P2/P3 的 OutboundSender/AdbSender 完全隔离：
 *   - P3 @回复：outbound_message 队列 → AdbSender 直接在群里发言
 *   - P4 定时：task_run 状态机 → add_msg_template 建任务 → ADB 进群发助手点「发送」
 * 两者共用 WeChatUi.SESSION_LOCK 串行，不共用队列表。
 *
 * 第一刀：文本最多 4000 字节（企微约束，超长字节按 UTF-8 计）。
 * 第二刀：可选带 1 个媒体附件（jpg/png 图片、mp4 视频、任意非音频文件）——
 *   入口硬校验扩展名 + 大小（不通过 fatal，不浪费重试）→ 调 media/upload 换 media_id
 *   → 入 add_msg_template 的 attachments 数组。文本可选，纯图任务允许。
 *   media/upload 失败复用 failed_api + api_attempts，与 add_msg_template 失败同列重试。
 */
public final class ApiMsgSender {

    /** ADB 确认结果（驱动 task_run 状态机） */
    public enum ConfirmOutcome {
        SENT,        // 生产：已点击发送
        DRY_RUN,     // dry-run：按钮校验可见但未点击
        NO_ENTRY,    // 进不了群发助手 / 找不到任何任务卡片
        NO_MATCH,    // 历史页没有内容匹配的待发送卡片（企微同步可能有延迟）
        NO_BTN,      // 详情页找不到发送按钮
        ERROR        // 导航/点击过程异常
    }

    /** API 创建结果 */
    public static final class CreateResult {
        public final boolean ok;
        public final boolean fatal;   // true=重试无意义（内容超长等），直接 failed_final
        public final String msgid;
        public final String error;

        private CreateResult(boolean ok, boolean fatal, String msgid, String error) {
            this.ok = ok; this.fatal = fatal; this.msgid = msgid; this.error = error;
        }
        public static CreateResult success(String msgid) {
            return new CreateResult(true, false, msgid, null);
        }
        public static CreateResult fail(String error) {
            return new CreateResult(false, false, null, error);
        }
        public static CreateResult fatal(String error) {
            return new CreateResult(false, true, null, error);
        }
    }

    /** 历史卡片最多往前试几张（防在长历史里逐个点开） */
    private static final int MAX_CARD_TRIES = 3;

    // ===== 媒体类型映射（ADR-0009）=====
    // image=jpg/jpeg/png(≤10MB)、video=mp4(≤10MB)、其余当 file(≤20MB)
    // 音频扩展名 fatal 拒绝（add_msg_template 不接受 voice 附件）
    private static final java.util.Set<String> IMAGE_EXTS = java.util.Set.of("jpg", "jpeg", "png");
    private static final java.util.Set<String> VIDEO_EXTS = java.util.Set.of("mp4");
    private static final java.util.Set<String> AUDIO_EXTS = java.util.Set.of(
        "amr", "mp3", "m4a", "aac", "wav", "flac");
    private static final long IMAGE_MAX = 10L * 1024 * 1024;
    private static final long VIDEO_MAX = 10L * 1024 * 1024;
    private static final long FILE_MAX  = 20L * 1024 * 1024;

    private final ContactApiClient api;
    private final WeChatUi ui;

    public ApiMsgSender(ContactApiClient api, WeChatUi ui) {
        this.api = api;
        this.ui = ui;
    }

    /**
     * 第 1 步：调 add_msg_template 创建企业群发任务（chat_type=group）。
     * API 成功只代表任务已创建，真正到群还需 sender 在群发助手确认（第 2 步）。
     *
     * 第一刀：content 必填、≤4000 字节 UTF-8。
     * 第二刀：content 可选；mediaPath 非空时校验→上传→入 attachments。content 与 mediaPath
     *        不能同时为空。mediaPath 为空时等价于第一刀（纯文本任务）。
     *
     * @param mediaPath 可选媒体文件相对路径（如 data/media/test.jpg）；null/空 表示纯文本任务
     */
    public CreateResult createTemplate(String senderUserid, List<String> chatIds,
                                       String content, String mediaPath) {
        boolean hasText = content != null && !content.isBlank();
        boolean hasMedia = mediaPath != null && !mediaPath.isBlank();
        if (!hasText && !hasMedia) {
            return CreateResult.fatal("消息内容为空（content 与 media_path 不能同时为空）");
        }
        if (hasText) {
            int bytes = content.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > 4000) {
                return CreateResult.fatal("消息内容 " + bytes + " 字节，超过企微 4000 字节上限");
            }
        }
        if (chatIds == null || chatIds.isEmpty()) {
            return CreateResult.fatal("目标群为空（检查 room_map 启用状态与 test.target-roomid）");
        }

        // 第二刀：媒体上传 + attachments 构造
        JSONArray attachments = null;
        if (hasMedia) {
            // resolveMedia 内部对音频扩展名直接抛 fatal 错误文案；这里 catch 转 CreateResult.fatal
            String[] mr;
            try {
                mr = resolveMedia(mediaPath);
            } catch (RuntimeException re) {
                return CreateResult.fatal(re.getMessage());
            }
            if (mr == null) {
                return CreateResult.fatal("媒体文件不存在: " + mediaPath);
            }
            String type = mr[1];
            long max = Long.parseLong(mr[2]);

            File f = new File(mediaPath);
            long size = f.length();
            if (size > max) {
                double mb = size / 1024.0 / 1024.0;
                return CreateResult.fatal("媒体文件 " + String.format(java.util.Locale.US, "%.2f", mb)
                    + "MB 超过 " + (max / 1024 / 1024) + "MB 上限（" + type + "）");
            }

            // 上传换 media_id（失败走 failed_api + api_attempts 重试，与 add_msg_template 失败同列）
            String mediaId;
            try {
                JSONObject up = api.uploadMedia(f, type);
                int ucode = up.optInt("errcode", -1);
                if (ucode != 0) {
                    return CreateResult.fail("media/upload errcode=" + ucode
                        + " " + up.optString("errmsg"));
                }
                mediaId = up.optString("media_id", null);
                if (mediaId == null || mediaId.isEmpty()) {
                    return CreateResult.fail("media/upload 未返回 media_id: " + up);
                }
            } catch (Exception e) {
                return CreateResult.fail("media/upload 异常: " + e.getMessage());
            }

            // 构造单元素 attachments 数组（msgtype 镜像 type，file 也用 file 而非扩展名）
            JSONObject item = new JSONObject().put("msgtype", type);
            JSONObject payload = new JSONObject().put("media_id", mediaId);
            item.put(type, payload);
            attachments = new JSONArray().put(item);
        }

        try {
            JSONObject body = new JSONObject()
                .put("chat_type", "group")
                .put("chat_id_list", chatIds)
                .put("sender", senderUserid);
            if (hasText) {
                body.put("text", new JSONObject().put("content", content));
            }
            if (attachments != null) {
                body.put("attachments", attachments);
            }
            JSONObject resp = api.addMsgTemplate(body);
            int errcode = resp.optInt("errcode", -1);
            if (errcode != 0) {
                return CreateResult.fail("add_msg_template errcode=" + errcode
                    + " " + resp.optString("errmsg"));
            }
            // fail_list 非空 = 有 chatid 无效或不可达（P4 每任务至少一个目标群，任何失败都算失败）
            Object failList = resp.opt("fail_list");
            if (failList != null && failList.toString().length() > 2) {
                return CreateResult.fail("add_msg_template fail_list=" + failList);
            }
            String msgid = resp.optString("msgid", null);
            if (msgid == null || msgid.isEmpty()) {
                return CreateResult.fail("add_msg_template 未返回 msgid: " + resp);
            }
            return CreateResult.success(msgid);
        } catch (Exception e) {
            return CreateResult.fail("add_msg_template 异常: " + e.getMessage());
        }
    }

    /**
     * 解析扩展名 → (ext, type, maxBytes)：
     *   image=jpg/jpeg/png、video=mp4、其余当 file；音频 fatal 拒绝（调用方需先校验存在）
     * @return null=文件不存在；非 null=String[]{ext, type, maxBytes}
     */
    private static String[] resolveMedia(String mediaPath) {
        File f = new File(mediaPath);
        if (!f.exists() || !f.isFile()) return null;
        String name = mediaPath.toLowerCase();
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1) : "";
        if (AUDIO_EXTS.contains(ext)) {
            // 致命错误：add_msg_template 不接受 voice 类型附件
            throw new RuntimeException("add_msg_template 不支持语音附件，请改用文本或图片（."
                + ext + "）");
        }
        if (IMAGE_EXTS.contains(ext)) {
            return new String[]{ext, "image", String.valueOf(IMAGE_MAX)};
        }
        if (VIDEO_EXTS.contains(ext)) {
            return new String[]{ext, "video", String.valueOf(VIDEO_MAX)};
        }
        return new String[]{ext, "file", String.valueOf(FILE_MAX)};
    }

    /**
     * 第 2 步：ADB 进群发助手，找到本次任务卡片，校验后点「发送」。
     * 全程持 SESSION_LOCK（与 P3 AdbSender 串行）。
     *
     * @param expectedContent 本次任务文本（用于卡片内容校验，防误点历史任务）；
     *                         null/空 = 纯图任务，跳过 epd 内容比对，只校验「待发送」+ 按钮
     * @param shotPrefix      审计截图文件名前缀（p4_{runId}_{attempt}）
     */
    public ConfirmOutcome confirmByAdbClick(String expectedContent, String shotPrefix) {
        synchronized (WeChatUi.SESSION_LOCK) {
            try {
                if (!ui.enterQunFaZhuShou()) {
                    return ConfirmOutcome.NO_ENTRY;
                }

                List<int[]> cards = ui.listQunFaCardsLatestFirst();
                if (cards.isEmpty()) {
                    System.out.println("    -> ❌ 群发助手历史页无任何任务卡片");
                    return ConfirmOutcome.NO_ENTRY;
                }

                boolean matched = false;
                int tries = Math.min(MAX_CARD_TRIES, cards.size());
                for (int i = 0; i < tries; i++) {
                    System.out.println("    -> 尝试第 " + (i + 1) + "/" + tries
                        + " 张最新卡片（bounds 见调试日志）");
                    if (ui.openPendingTaskAndVerify(cards.get(i), expectedContent)) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    System.out.println("    -> ❌ 前 " + tries + " 张卡片均不匹配本次待发送任务"
                        + "（企微端同步可能延迟，下轮重试）");
                    ui.leaveQunFaToMessageList();
                    return ConfirmOutcome.NO_MATCH;
                }

                // 详情页：定位发送按钮
                String detailXml = Adb.dumpUi();
                int[] btn = ui.findSendButton(detailXml);
                if (btn == null) {
                    System.out.println("    -> ❌ 详情页未找到发送按钮");
                    ui.leaveQunFaToMessageList();
                    return ConfirmOutcome.NO_BTN;
                }
                System.out.println("    -> 发送按钮定位: [" + btn[0] + "," + btn[1] + "]["
                    + btn[2] + "," + btn[3] + "]");

                // 点击前截图审计（dry-run 同样截图：证据=按钮已就绪但未点）
                File shot = Adb.screenshotTo(com.mumu.bot.config.Config.SCREENSHOT_DIR, shotPrefix);
                if (shot != null) {
                    System.out.println("    -> 📸 P4 审计截图: " + shot.getAbsolutePath());
                }

                boolean clicked = ui.tapSendButtonDryRunAware(btn);
                if (!clicked) {
                    ui.leaveQunFaToMessageList();
                    return ConfirmOutcome.ERROR;
                }

                // ADR-0011：发送后清理群发助手历史卡片（dry-run 和真发都删）
                boolean deleted = ui.deleteLatestQunFaCard();
                if (!deleted) {
                    System.out.println("    -> ⚠️ 历史卡片删除失败，发邮件告警 task_cleanup_failed");
                    com.mumu.bot.alert.MailAlerter.alert(
                        com.mumu.bot.alert.MailAlerter.TASK_CLEANUP_FAILED,
                        "群发助手历史卡片删除失败（3 次重试后），shotPrefix=" + shotPrefix);
                    // 删除失败不翻转业务状态——发送已成功，继续 leave
                }

                ui.leaveQunFaToMessageList();
                return com.mumu.bot.config.Config.P4_DRY_RUN
                    ? ConfirmOutcome.DRY_RUN : ConfirmOutcome.SENT;
            } catch (Exception e) {
                System.out.println("    -> ❌ ADB 确认异常: " + e.getMessage());
                try { ui.leaveQunFaToMessageList(); } catch (Exception ignore) { }
                return ConfirmOutcome.ERROR;
            }
        }
    }
}
