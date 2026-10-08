package com.mumu.bot.admin;

import com.mumu.bot.config.Config;
import com.mumu.bot.kb.KbSyncService;
import com.mumu.bot.kb.KbTextExtractor;
import com.mumu.bot.schedule.ScheduledTask;
import com.mumu.bot.schedule.TaskRun;
import com.mumu.bot.schedule.TaskStore;
import com.mumu.bot.store.KbStore;
import com.mumu.bot.store.RoomMap;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 管理端路由 handler（P5，零新 jar）。
 *
 * 所有路由集中在一个 HttpHandler 内，按 path + method 分发。
 * 每个分支内部 try/catch，返回 JSON 或 HTML；异常返回 500。
 *
 * 路由表（见 plan P5）：
 *   GET  /                       → 302 /tasks
 *   GET  /tasks                  → 列表页 HTML
 *   GET  /api/tasks              → 任务 JSON（含今日 run 状态）
 *   POST /api/tasks              → 新建
 *   PUT  /api/tasks/{id}         → 编辑
 *   DELETE /api/tasks/{id}       → 硬删除 + 级联
 *   POST /api/tasks/{id}/fire    → 立即触发
 *   POST /api/tasks/{id}/toggle  → 启用/停用
 *   GET  /api/tasks/{id}/runs    → 最近 20 条 run
 *   GET  /api/rooms              → enabled 群列表
 *   POST /api/rooms              → 追加群聊
 *   GET  /kb                     → 知识库列表页
 *   GET  /api/kb/docs             → kb_document 全量
 *   POST /api/kb/upload           → multipart 上传 + sync
 *   DELETE /api/kb/docs/{title}   → 删文件 + sync
 *   POST /api/media               → 媒体上传
 */
public final class AdminHandler implements HttpHandler {

    private final TaskStore taskStore = new TaskStore();

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            route(ex);
        } catch (Exception e) {
            json(ex, 500, obj("error", e.getMessage()));
        } finally {
            ex.close();
        }
    }

    private void route(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        if (path.equals("/") || path.isEmpty()) {
            redirect(ex, "/tasks");
            return;
        }
        if (path.equals("/tasks") && method.equals("GET")) {
            html(ex, TemplateRenderer.tasksPage());
            return;
        }
        if (path.equals("/api/tasks") && method.equals("GET")) {
            json(ex, 200, tasksJson());
            return;
        }
        if (path.equals("/api/tasks") && method.equals("POST")) {
            handleTaskCreate(ex);
            return;
        }
        if (path.matches("/api/tasks/\\d+") && method.equals("PUT")) {
            handleTaskUpdate(ex, parseId(path));
            return;
        }
        if (path.matches("/api/tasks/\\d+") && method.equals("DELETE")) {
            handleTaskDelete(ex, parseId(path));
            return;
        }
        if (path.matches("/api/tasks/\\d+/fire") && method.equals("POST")) {
            handleTaskFire(ex, parseIdFromSegment(path, 1));
            return;
        }
        if (path.matches("/api/tasks/\\d+/toggle") && method.equals("POST")) {
            handleTaskToggle(ex, parseIdFromSegment(path, 1));
            return;
        }
        if (path.matches("/api/tasks/\\d+/runs") && method.equals("GET")) {
            json(ex, 200, runsJson(parseIdFromSegment(path, 1)));
            return;
        }
        if (path.equals("/api/rooms") && method.equals("GET")) {
            json(ex, 200, roomsJson());
            return;
        }
        if (path.equals("/api/rooms") && method.equals("POST")) {
            handleRoomInsert(ex);
            return;
        }
        if (path.equals("/kb") && method.equals("GET")) {
            html(ex, TemplateRenderer.kbPage());
            return;
        }
        if (path.equals("/api/kb/docs") && method.equals("GET")) {
            json(ex, 200, kbDocsJson());
            return;
        }
        if (path.equals("/api/kb/upload") && method.equals("POST")) {
            handleKbUpload(ex);
            return;
        }
        if (path.matches("/api/kb/docs/.+") && method.equals("DELETE")) {
            handleKbDelete(ex, path.substring("/api/kb/docs/".length()));
            return;
        }
        if (path.equals("/api/media") && method.equals("POST")) {
            handleMediaUpload(ex);
            return;
        }
        // P7：告警邮箱配置
        if (path.equals("/mail") && method.equals("GET")) {
            html(ex, TemplateRenderer.mailPage());
            return;
        }
        if (path.equals("/api/mail") && method.equals("GET")) {
            json(ex, 200, obj("recipient", com.mumu.bot.alert.MailAlerter.recipient()));
            return;
        }
        if (path.equals("/api/mail") && method.equals("POST")) {
            handleMailSave(ex);
            return;
        }
        // 运行时配置（5 个管理端可改键，存 data/runtime.properties）
        if (path.equals("/config") && method.equals("GET")) {
            html(ex, TemplateRenderer.configPage());
            return;
        }
        if (path.equals("/api/config") && method.equals("GET")) {
            json(ex, 200, configGet());
            return;
        }
        if (path.equals("/api/config") && method.equals("POST")) {
            handleConfigSave(ex, false);
            return;
        }
        if (path.equals("/api/config/restart") && method.equals("POST")) {
            handleConfigSave(ex, true);
            return;
        }
        json(ex, 404, obj("error", "路由不存在: " + method + " " + path));
    }

    // ===== 任务 =====

    private void handleTaskCreate(HttpExchange ex) throws IOException {
        Map<String, Object> body = parseJsonBody(ex);
        try {
            ScheduledTask t = mapTaskFromJson(body, null);
            List<String> conflict = checkConflicts(t, null);
            if (!conflict.isEmpty()) {
                json(ex, 400, obj("error", "任务冲突：" + String.join("; ", conflict)));
                return;
            }
            taskStore.insertTask(t);
            json(ex, 200, obj("id", t.id));
        } catch (IllegalArgumentException e) {
            json(ex, 400, obj("error", e.getMessage()));
        }
    }

    private void handleTaskUpdate(HttpExchange ex, long id) throws IOException {
        Map<String, Object> body = parseJsonBody(ex);
        try {
            ScheduledTask t = mapTaskFromJson(body, id);
            List<String> conflict = checkConflicts(t, id);
            if (!conflict.isEmpty()) {
                json(ex, 400, obj("error", "任务冲突：" + String.join("; ", conflict)));
                return;
            }
            taskStore.updateTask(t);
            json(ex, 200, obj("id", id));
        } catch (IllegalArgumentException e) {
            json(ex, 400, obj("error", e.getMessage()));
        }
    }

    private void handleTaskDelete(HttpExchange ex, long id) throws IOException {
        taskStore.deleteTask(id);
        json(ex, 200, obj("ok", true));
    }

    private void handleTaskFire(HttpExchange ex, long id) throws IOException {
        String today = LocalDate.now().toString();
        Long runId = taskStore.createRunIfAbsent(id, today);
        if (runId == null) {
            json(ex, 200, obj("ok", false, "reason", "今日已触发"));
            return;
        }
        json(ex, 200, obj("ok", true, "runId", runId));
    }

    private void handleTaskToggle(HttpExchange ex, long id) throws IOException {
        ScheduledTask t = taskStore.getTask(id);
        if (t == null) {
            json(ex, 404, obj("error", "任务不存在: " + id));
            return;
        }
        t.enabled = !t.enabled;
        taskStore.updateTask(t);
        if (!t.enabled) taskStore.cancelPendingRuns(id);
        json(ex, 200, obj("id", id, "enabled", t.enabled));
    }

    private ScheduledTask mapTaskFromJson(Map<String, Object> body, Long existingId) {
        ScheduledTask t = new ScheduledTask();
        if (existingId != null) t.id = existingId;
        t.name = (String) body.get("name");
        if (t.name == null || t.name.isBlank()) throw new IllegalArgumentException("name 必填");
        t.msgType = "text";
        t.content = (String) body.get("content");
        t.mediaPath = (String) body.get("mediaPath");
        if ((t.content == null || t.content.isBlank())
            && (t.mediaPath == null || t.mediaPath.isBlank())) {
            throw new IllegalArgumentException("content 与 mediaPath 至少填一项");
        }
        t.rhythm = (String) body.get("rhythm");
        if (!"once".equals(t.rhythm) && !"daily".equals(t.rhythm) && !"weekly".equals(t.rhythm)) {
            throw new IllegalArgumentException("rhythm 必须是 once/daily/weekly");
        }
        t.weekdays = (String) body.get("weekdays");
        if ("weekly".equals(t.rhythm) && (t.weekdays == null || t.weekdays.isBlank())) {
            throw new IllegalArgumentException("weekly 节奏必须指定 weekdays");
        }
        t.plannedTime = (String) body.get("plannedTime");
        if (!"once".equals(t.rhythm) && (t.plannedTime == null || !t.plannedTime.matches("\\d{2}:\\d{2}"))) {
            throw new IllegalArgumentException("plannedTime 必须为 HH:mm 格式");
        }
        Object rooms = body.get("targetRooms");
        if (rooms == null) throw new IllegalArgumentException("target_rooms 必填");
        // target_rooms 可以是数组或逗号分隔字符串
        if (rooms instanceof List) {
            t.targetRooms = String.join(",", ((List<?>) rooms).stream()
                .map(Object::toString).collect(Collectors.toList()));
        } else {
            t.targetRooms = rooms.toString();
        }
        if (t.targetRooms.isBlank()) throw new IllegalArgumentException("target_rooms 不能为空");
        Object enabled = body.get("enabled");
        t.enabled = enabled == null ? true : Boolean.parseBoolean(enabled.toString());
        if ("once".equals(t.rhythm)) {
            Object foa = body.get("fireOnceAt");
            if (foa == null) throw new IllegalArgumentException("once 节奏必须指定 fireOnceAt（epoch ms）");
            t.fireOnceAt = "number".equals(foa.getClass().getSimpleName())
                ? (long) ((Number) foa).doubleValue()
                : Long.parseLong(foa.toString());
        }
        t.senderUserid = (String) body.get("senderUserid");
        return t;
    }

    /**
     * 冲突判定（plan P5 决策 #9）：
     * 严格按"同一天同群只允许 1 条任务会触发"判定，与时间点无关。
     * 计算新任务未来触发的日期集合，与现有 enabled 任务的目标群取交集。
     * 编辑时排除自身 id。
     */
    private List<String> checkConflicts(ScheduledTask t, Long excludeId) {
        List<String> conflicts = new ArrayList<>();
        Set<String> newRooms = new HashSet<>(Arrays.asList(t.targetRooms.split(",")));
        Set<LocalDate> newDates = computeTriggerDates(t, 90);  // 看未来 90 天

        for (ScheduledTask existing : taskStore.listAllTasks()) {
            if (!existing.enabled) continue;
            if (excludeId != null && existing.id == excludeId) continue;
            if (existing.id == t.id) continue;
            Set<String> existingRooms = new HashSet<>(Arrays.asList(existing.targetRooms.split(",")));
            Set<String> roomIntersect = new HashSet<>(newRooms);
            roomIntersect.retainAll(existingRooms);
            if (roomIntersect.isEmpty()) continue;
            Set<LocalDate> existingDates = computeTriggerDates(existing, 90);
            Set<LocalDate> dateIntersect = new HashSet<>(newDates);
            dateIntersect.retainAll(existingDates);
            if (dateIntersect.isEmpty()) continue;
            conflicts.add("与任务《" + existing.name + "》在 "
                + dateIntersect.stream().sorted().limit(3).map(LocalDate::toString)
                    .collect(Collectors.joining(", "))
                + (dateIntersect.size() > 3 ? " 等 " + dateIntersect.size() + " 天" : "")
                + " 冲突，群：" + String.join(",", roomIntersect)
                + "。请先删除该任务或调整目标群再提交");
        }
        return conflicts;
    }

    /** 计算任务在未来 maxDays 天内的触发日期集合 */
    private Set<LocalDate> computeTriggerDates(ScheduledTask t, int maxDays) {
        Set<LocalDate> dates = new java.util.TreeSet<>();
        LocalDate today = LocalDate.now();
        switch (t.rhythm == null ? "" : t.rhythm) {
            case "once":
                if (t.fireOnceAt != null) {
                    LocalDate d = LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(t.fireOnceAt), ZoneId.systemDefault())
                        .toLocalDate();
                    if (!d.isBefore(today)) dates.add(d);
                }
                break;
            case "daily":
                for (int i = 0; i < maxDays; i++) dates.add(today.plusDays(i));
                break;
            case "weekly":
                if (t.weekdays == null) break;
                Set<Integer> wds = new HashSet<>();
                for (String s : t.weekdays.split(",")) {
                    try { wds.add(Integer.parseInt(s.trim())); } catch (NumberFormatException ignore) {}
                }
                for (int i = 0; i < maxDays; i++) {
                    LocalDate d = today.plusDays(i);
                    if (wds.contains(d.getDayOfWeek().getValue())) dates.add(d);
                }
                break;
        }
        return dates;
    }

    // ===== 群聊 =====

    private void handleRoomInsert(HttpExchange ex) throws IOException {
        Map<String, Object> body = parseJsonBody(ex);
        String roomid = (String) body.get("roomid");
        String name = (String) body.get("name");
        if (roomid == null || roomid.isBlank() || name == null || name.isBlank()) {
            json(ex, 400, obj("error", "roomid 与 name 均必填"));
            return;
        }
        RoomMap.insert(roomid, name);
        json(ex, 200, obj("ok", true));
    }

    // ===== 知识库 =====

    private void handleKbUpload(HttpExchange ex) throws IOException {
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        if (ct == null || !ct.startsWith("multipart/form-data")) {
            json(ex, 400, obj("error", "需 multipart/form-data"));
            return;
        }
        String boundary = "--" + ct.substring(ct.indexOf("boundary=") + 9).trim();
        MediaUploadParser.ParseResult r = MediaUploadParser.parse(ex.getRequestBody(), boundary);
        MediaUploadParser.UploadedFile file = r.files.get("file");
        if (file == null) {
            json(ex, 400, obj("error", "未上传文件字段 file"));
            return;
        }
        // .doc 拒绝
        if (file.filename.toLowerCase().endsWith(".doc")) {
            json(ex, 400, obj("error", "请将 .doc 文件转存为 .docx 后再上传"));
            return;
        }
        // 扩展名校验
        if (!KbTextExtractor.isSupported(file.filename)) {
            json(ex, 400, obj("error", "仅支持 .docx/.xlsx/.txt/.md"));
            return;
        }
        File out = new File(Config.KB_DIR, file.filename);
        boolean overwrite = "1".equals(r.fields.get("overwrite"));
        if (out.exists() && !overwrite) {
            json(ex, 409, obj("error", "已存在同名文件", "existingSize", out.length(),
                "existingModified", out.lastModified()));
            return;
        }
        Config.KB_DIR.mkdirs();
        Files.write(out.toPath(), file.data);
        KbSyncService.Result result = new KbSyncService().sync(false);
        json(ex, 200, obj("ok", true, "lines", result.lines,
            "inserted", result.inserted, "updated", result.updated,
            "deleted", result.deleted, "skipped", result.skipped,
            "totalChars", result.totalChars));
    }

    private void handleKbDelete(HttpExchange ex, String title) throws IOException {
        // 扫 data/kb 目录找匹配文件（标题=文件名去扩展名）
        File[] files = Config.KB_DIR.listFiles((d, n) -> KbTextExtractor.isSupported(n));
        boolean deleted = false;
        if (files != null) {
            for (File f : files) {
                String t = f.getName();
                int dot = t.lastIndexOf('.');
                if (dot > 0) t = t.substring(0, dot);
                if (t.equals(title)) {
                    try { Files.delete(f.toPath()); deleted = true; } catch (IOException ignore) {}
                }
            }
        }
        if (!deleted) {
            // 目录里找不到也删 DB 行（文件已被外部删除）
            KbStore.deleteByTitle(title);
            json(ex, 200, obj("ok", true, "note", "目录已无对应文件，仅删 DB 行"));
            return;
        }
        KbSyncService.Result result = new KbSyncService().sync(false);
        json(ex, 200, obj("ok", true, "lines", result.lines, "deleted", result.deleted));
    }

    // ===== 媒体 =====

    private void handleMediaUpload(HttpExchange ex) throws IOException {
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        if (ct == null || !ct.startsWith("multipart/form-data")) {
            json(ex, 400, obj("error", "需 multipart/form-data"));
            return;
        }
        String boundary = "--" + ct.substring(ct.indexOf("boundary=") + 9).trim();
        MediaUploadParser.ParseResult r = MediaUploadParser.parse(ex.getRequestBody(), boundary);
        MediaUploadParser.UploadedFile file = r.files.get("file");
        if (file == null) {
            json(ex, 400, obj("error", "未上传文件字段 file"));
            return;
        }
        String name = file.filename;
        String lower = name.toLowerCase();
        String type;
        int maxMb;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")) {
            type = "image"; maxMb = Config.P4_MEDIA_MAX_IMAGE_MB;
        } else if (lower.endsWith(".mp4")) {
            type = "video"; maxMb = Config.P4_MEDIA_MAX_VIDEO_MB;
        } else if (lower.endsWith(".amr") || lower.endsWith(".mp3") || lower.endsWith(".m4a")
                   || lower.endsWith(".aac") || lower.endsWith(".wav") || lower.endsWith(".flac")) {
            json(ex, 400, obj("error", "add_msg_template 不支持语音附件，请改用文本或图片"));
            return;
        } else {
            type = "file"; maxMb = Config.P4_MEDIA_MAX_FILE_MB;
        }
        if (file.data.length > (long) maxMb * 1024 * 1024) {
            json(ex, 400, obj("error", type + " " + (file.data.length / 1024.0 / 1024.0)
                + " MB 超过 " + maxMb + "MB 上限"));
            return;
        }
        File out = new File("data/media", name);
        boolean overwrite = "1".equals(r.fields.get("overwrite"));
        if (out.exists() && !overwrite) {
            json(ex, 409, obj("error", "已存在同名文件", "existingSize", out.length()));
            return;
        }
        out.getParentFile().mkdirs();
        Files.write(out.toPath(), file.data);
        json(ex, 200, obj("ok", true, "path", out.getPath().replace('\\', '/'),
            "type", type, "size", file.data.length));
    }

    // ===== P7：告警邮箱配置 =====

    private void handleMailSave(HttpExchange ex) throws IOException {
        Map<String, Object> body = parseJsonBody(ex);
        Object r = body.get("recipient");
        if (r == null) { json(ex, 400, obj("error", "缺少 recipient 字段")); return; }
        String recipient = r.toString().trim();
        // 简单邮箱格式校验
        if (!recipient.isEmpty() && !recipient.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            json(ex, 400, obj("error", "邮箱格式不正确"));
            return;
        }
        try {
            java.io.File f = new java.io.File("data/mail.properties");
            f.getParentFile().mkdirs();
            String content = "# 告警收件人（管理端 /mail 页面可改，JVM 与 watchdog 发信时读取）\n"
                + "recipient=" + recipient + "\n";
            Files.writeString(f.toPath(), content, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            json(ex, 200, obj("ok", true));
        } catch (Exception e) {
            json(ex, 500, obj("error", "保存失败: " + e.getMessage()));
        }
    }

    // ===== 运行时配置（5 个管理端可改键，存 data/runtime.properties）=====

    /** GET /api/config — 返回当前生效值 + 覆盖层状态 + 掩码 api-key */
    private Map<String, Object> configGet() {
        // 读 runtime.properties 判断哪些键被覆盖了（用简单行解析，保留原始值）
        java.util.Map<String, String> runtimeOverrides = readRuntimeProps();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("adb.path", Config.ADB_PATH);
        m.put("adb_path_overridden", runtimeOverrides.containsKey("adb.path"));
        m.put("adb.device", Config.DEVICE);  // 只读展示（启动时自动探测）
        m.put("my.userid", Config.MY_USERID);
        m.put("my_userid_overridden", runtimeOverrides.containsKey("my.userid"));
        m.put("my.name", Config.MY_NAME);
        m.put("my_name_overridden", runtimeOverrides.containsKey("my.name"));
        m.put("ai.model", Config.AI_MODEL);
        m.put("ai_model_overridden", runtimeOverrides.containsKey("ai.model"));
        // api-key 返回掩码版本；实际值不回传
        m.put("ai.api.key_masked", maskApiKey(Config.AI_API_KEY));
        m.put("ai_api_key_overridden", runtimeOverrides.containsKey("ai.api-key"));
        // P4 dry-run 开关
        m.put("p4.dry-run", Config.P4_DRY_RUN);
        m.put("p4_dry_run_overridden", runtimeOverrides.containsKey("p4.dry-run"));
        return m;
    }

    private static String maskApiKey(String key) {
        if (key == null || key.isEmpty()) return "";
        if (key.length() <= 8) return "****";
        return key.substring(0, 3) + "****" + key.substring(key.length() - 4);
    }

    /**
     * POST /api/config — 合并保存（只更新 JSON body 中出现的字段，其余保留）。
     * restart=true 时（POST /api/config/restart）额外写 restart_requested 哨兵并 exit(0)。
     */
    private void handleConfigSave(HttpExchange ex, boolean restart) throws IOException {
        Map<String, Object> body = parseJsonBody(ex);

        // 1. 校验（按设计方案：adb.path 硬拦截文件不存在；my.name 禁含 @）
        String error = validateConfig(body);
        if (error != null) {
            json(ex, 400, obj("error", error));
            return;
        }

        // 2. 读现有 runtime.properties → 合并新值 → 原子写入
        try {
            java.util.Map<String, String> rp = readRuntimeProps();
            File rf = new File("data/runtime.properties");
            boolean changed = false;
            StringBuilder changedFields = new StringBuilder();

            // adb.path
            Object adbPathObj = body.get("adb.path");
            if (adbPathObj != null) {
                String v = adbPathObj.toString().trim();
                if (!v.equals(rp.get("adb.path"))) {
                    if (!v.isEmpty()) rp.put("adb.path", v);
                    else rp.remove("adb.path");
                    changed = true;
                    if (changedFields.length() > 0) changedFields.append(",");
                    changedFields.append("adb.path");
                }
            }
            // my.userid
            Object uidObj = body.get("my.userid");
            if (uidObj != null) {
                String v = uidObj.toString().trim();
                if (!v.equals(rp.get("my.userid"))) {
                    if (!v.isEmpty()) rp.put("my.userid", v);
                    else rp.remove("my.userid");
                    changed = true;
                    if (changedFields.length() > 0) changedFields.append(",");
                    changedFields.append("my.userid");
                }
            }
            // my.name
            Object nameObj = body.get("my.name");
            if (nameObj != null) {
                String v = nameObj.toString().trim();
                if (!v.equals(rp.get("my.name"))) {
                    if (!v.isEmpty()) rp.put("my.name", v);
                    else rp.remove("my.name");
                    changed = true;
                    if (changedFields.length() > 0) changedFields.append(",");
                    changedFields.append("my.name");
                }
            }
            // ai.model
            Object modelObj = body.get("ai.model");
            if (modelObj != null) {
                String v = modelObj.toString().trim();
                if (!v.equals(rp.get("ai.model"))) {
                    if (!v.isEmpty()) rp.put("ai.model", v);
                    else rp.remove("ai.model");
                    changed = true;
                    if (changedFields.length() > 0) changedFields.append(",");
                    changedFields.append("ai.model");
                }
            }
            // ai.api-key（留空 = 不修改）
            Object apiKeyObj = body.get("ai.api.key");
            if (apiKeyObj != null) {
                String v = apiKeyObj.toString();
                if (!v.trim().isEmpty() && !v.equals(maskApiKey(Config.AI_API_KEY))) {
                    if (!v.equals(rp.get("ai.api-key"))) {
                        rp.put("ai.api-key", v);
                        changed = true;
                        if (changedFields.length() > 0) changedFields.append(",");
                        changedFields.append("ai.api-key");
                    }
                }
            }
            // p4.dry-run（布尔开关）
            Object dryRunObj = body.get("p4.dry-run");
            if (dryRunObj != null) {
                String v = dryRunObj.toString();
                if (!v.equals(rp.get("p4.dry-run"))) {
                    rp.put("p4.dry-run", v);
                    changed = true;
                    if (changedFields.length() > 0) changedFields.append(",");
                    changedFields.append("p4.dry-run");
                }
            }

            if (!changed && !restart) {
                json(ex, 200, obj("ok", true, "note", "无字段变更"));
                return;
            }

            // 3. 原子写入：先写 .tmp 再 rename（手动写 key=value 行，避免 Properties.store 转义反斜杠）
            rf.getParentFile().mkdirs();
            File tmp = new File(rf.getParentFile(), "runtime.properties.tmp");
            try (java.io.FileWriter fw = new java.io.FileWriter(tmp, StandardCharsets.UTF_8)) {
                fw.write("# 运行时配置覆盖层（Admin /config 页面可改，config.properties 为出厂默认）\n");
                fw.write("# 运行期永不写入 config.properties；删除此文件即全部回出厂值\n");
                // 手动写避免 Properties.store 转义反斜杠（adb.path 含 Windows 路径分隔符）
                for (String key : Config.RUNTIME_KEYS) {
                    String val = rp.get(key);
                    if (val != null) fw.write(key + "=" + val + "\n");
                }
            }
            Files.move(tmp.toPath(), rf.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            // 4. reload 前捕获旧值（Config.reload() 会更新 volatile 字段）
            String oldUserid = Config.MY_USERID;

            // 5. 热加载 volatile 字段
            Config.reload();

            // 6. 审计日志（不含值）
            if (changed) {
                File auditLog = new File("data/config_audit.log");
                auditLog.getParentFile().mkdirs();
                String line = java.time.LocalDateTime.now().toString()
                    + " changed=[" + changedFields + "]\n";
                Files.writeString(auditLog.toPath(), line, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            }

            // 7. my.userid 变更 → 哨兵重启
            boolean useridChanged = body.get("my.userid") != null
                && !body.get("my.userid").toString().trim().isEmpty()
                && !body.get("my.userid").toString().trim().equals(oldUserid);

            if (restart || (useridChanged && changed)) {
                // 写哨兵 → 主动 exit(0) → watchdog 检测到哨兵后立即重启（不计入崩溃环）
                File sentinel = new File("data/restart_requested");
                Files.writeString(sentinel.toPath(),
                    "Admin /config restart: " + java.time.LocalDateTime.now() + "\n",
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                json(ex, 200, obj("ok", true, "restart", true,
                    "message", "配置已保存，身份变更请求重启。watchdog 将立即拉起，不计入崩溃环。"));
                // 异步退出（让 HTTP 响应先发出去）
                new Thread(() -> {
                    try { Thread.sleep(300); } catch (InterruptedException ignore) {}
                    System.exit(0);
                }, "admin-restart-sentinel").start();
            } else {
                json(ex, 200, obj("ok", true, "restart", false));
            }
        } catch (Exception e) {
            json(ex, 500, obj("error", "保存失败: " + e.getMessage()));
        }
    }

    /**
     * 简单行解析 runtime.properties（与 watchdog.ps1 的 IndexOf("=") 一致）。
     * 不使用 Properties.load()——它会把 \P/\N/\s 等非标准转义的反斜杠静默丢弃，
     * 导致 Windows 路径 D:\Program Files\... 变成 D:Program Files...
     */
    private static java.util.Map<String, String> readRuntimeProps() {
        java.util.Map<String, String> result = new java.util.HashMap<>();
        File rf = new File("data/runtime.properties");
        if (!rf.exists()) return result;
        try {
            for (String line : java.nio.file.Files.readAllLines(rf.toPath(), StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int eq = trimmed.indexOf('=');
                if (eq < 0) continue;
                String key = trimmed.substring(0, eq).trim();
                String val = trimmed.substring(eq + 1);
                result.put(key, val);
            }
        } catch (Exception ignore) {}
        return result;
    }

    /** POST /api/config 校验：adb.path 文件存在；my.name 非空且禁含 @ */
    private static String validateConfig(Map<String, Object> body) {
        Object adbPathObj = body.get("adb.path");
        if (adbPathObj != null) {
            String v = adbPathObj.toString().trim();
            if (!v.isEmpty() && !new File(v).exists()) {
                return "ADB 路径指向的文件不存在：" + v;
            }
        }
        Object nameObj = body.get("my.name");
        if (nameObj != null) {
            String v = nameObj.toString().trim();
            if (!v.isEmpty() && v.contains("@")) {
                return "@ 名字不能包含 @ 符号（检测词会变成 @@" + v + "）";
            }
        }
        Object uidObj = body.get("my.userid");
        if (uidObj != null && uidObj.toString().trim().isEmpty()) {
            return "my.userid 不能为空";
        }
        Object modelObj = body.get("ai.model");
        if (modelObj != null && modelObj.toString().trim().isEmpty()) {
            return "ai.model 不能为空";
        }
        return null;
    }

    private String tasksJson() {
        List<Map<String, Object>> arr = new ArrayList<>();
        String today = LocalDate.now().toString();
        for (ScheduledTask t : taskStore.listAllTasks()) {
            TaskRun todayRun = taskStore.getRunByDate(t.id, today);
            Map<String, Object> m = taskToMap(t);
            m.put("todayRun", todayRun == null ? null : runToMap(todayRun));
            arr.add(m);
        }
        return jsonArr(arr);
    }

    private String roomsJson() {
        List<Map<String, Object>> arr = new ArrayList<>();
        for (RoomMap.Room r : RoomMap.enabledRooms()) {
            arr.add(obj("roomid", r.roomid, "name", r.name));
        }
        return jsonArr(arr);
    }

    private String kbDocsJson() {
        List<Map<String, Object>> arr = new ArrayList<>();
        int total = 0;
        for (KbStore.Doc d : KbStore.all()) {
            arr.add(obj("title", d.title, "charCount", d.charCount));
            total += d.charCount;
        }
        return "{\"total\":" + total + ",\"max\":" + Config.KB_MAX_CHARS
            + ",\"docs\":" + jsonArr(arr) + "}";
    }

    private String runsJson(long taskId) {
        List<Map<String, Object>> arr = new ArrayList<>();
        for (TaskRun r : taskStore.listRunsByTask(taskId)) {
            arr.add(runToMap(r));
        }
        return jsonArr(arr);
    }

    private Map<String, Object> taskToMap(ScheduledTask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id);
        m.put("name", t.name);
        m.put("msgType", t.msgType);
        m.put("content", t.content);
        m.put("mediaPath", t.mediaPath);
        m.put("rhythm", t.rhythm);
        m.put("weekdays", t.weekdays);
        m.put("plannedTime", t.plannedTime);
        m.put("targetRooms", Arrays.asList(t.targetRooms.split(",")));
        m.put("enabled", t.enabled);
        m.put("fireOnceAt", t.fireOnceAt);
        m.put("senderUserid", t.senderUserid);
        m.put("apiMsgid", t.apiMsgid);
        m.put("createdAt", t.createdAt);
        return m;
    }

    private Map<String, Object> runToMap(TaskRun r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("taskId", r.taskId);
        m.put("plannedDate", r.plannedDate);
        m.put("state", r.state);
        m.put("apiAttempts", r.apiAttempts);
        m.put("adbAttempts", r.adbAttempts);
        m.put("nextAttemptAt", r.nextAttemptAt);
        m.put("msgid", r.msgid);
        m.put("lastError", r.lastError);
        m.put("firedAt", r.firedAt);
        m.put("createdAt", r.createdAt);
        return m;
    }

    // ===== 工具 =====

    private Map<String, Object> parseJsonBody(HttpExchange ex) throws IOException {
        byte[] body = ex.getRequestBody().readAllBytes();
        if (body.length == 0) return new LinkedHashMap<>();
        String s = new String(body, StandardCharsets.UTF_8);
        return parseJsonObject(s);
    }

    /** 手写极简 JSON 解析（不支持嵌套数组，但够用） */
    private static Map<String, Object> parseJsonObject(String s) {
        Map<String, Object> out = new LinkedHashMap<>();
        s = s.trim();
        if (!s.startsWith("{") || !s.endsWith("}")) return out;
        s = s.substring(1, s.length() - 1).trim();
        int i = 0;
        while (i < s.length()) {
            // 找 key
            int q1 = s.indexOf('"', i);
            if (q1 < 0) break;
            int q2 = s.indexOf('"', q1 + 1);
            if (q2 < 0) break;
            String key = s.substring(q1 + 1, q2);
            int colon = s.indexOf(':', q2 + 1);
            if (colon < 0) break;
            // 找 value 起始
            int vStart = colon + 1;
            while (vStart < s.length() && Character.isWhitespace(s.charAt(vStart))) vStart++;
            if (vStart >= s.length()) break;
            char ch = s.charAt(vStart);
            Object value;
            int vEnd;
            if (ch == '"') {
                int qEnd = s.indexOf('"', vStart + 1);
                value = s.substring(vStart + 1, qEnd);
                vEnd = qEnd + 1;
            } else if (ch == '[') {
                int arrEnd = s.indexOf(']', vStart);
                String arrStr = s.substring(vStart + 1, arrEnd);
                List<String> list = new ArrayList<>();
                for (String part : arrStr.split(",")) {
                    String p = part.trim();
                    if (p.startsWith("\"") && p.endsWith("\"")) p = p.substring(1, p.length() - 1);
                    if (!p.isEmpty()) list.add(p);
                }
                value = list;
                vEnd = arrEnd + 1;
            } else if (ch == '{') {
                // 嵌套对象不支持
                int objEnd = s.indexOf('}', vStart);
                value = s.substring(vStart, objEnd + 1);
                vEnd = objEnd + 1;
            } else {
                // 数字/布尔/null
                int end = vStart;
                while (end < s.length() && ",}]".indexOf(s.charAt(end)) < 0) end++;
                String raw = s.substring(vStart, end).trim();
                if ("true".equals(raw)) value = Boolean.TRUE;
                else if ("false".equals(raw)) value = Boolean.FALSE;
                else if ("null".equals(raw)) value = null;
                else {
                    try { value = Long.parseLong(raw); }
                    catch (NumberFormatException e) { value = raw; }
                }
                vEnd = end;
            }
            out.put(key, value);
            // 跳到下一对
            int comma = s.indexOf(',', vEnd);
            if (comma < 0) break;
            i = comma + 1;
        }
        return out;
    }

    private void json(HttpExchange ex, int code, Object body) throws IOException {
        String s;
        if (body instanceof String) s = (String) body;
        else if (body instanceof Map) s = jsonMap((Map<String, Object>) body);
        else s = jsonValue(body);
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private void html(HttpExchange ex, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().set("Location", location);
        ex.sendResponseHeaders(302, -1);
    }

    private long parseId(String path) {
        String[] segs = path.split("/");
        return Long.parseLong(segs[segs.length - 1]);
    }

    private long parseIdFromSegment(String path, int idxFromEnd) {
        String[] segs = path.split("/");
        return Long.parseLong(segs[segs.length - 1 - idxFromEnd]);
    }

    /** 构造 Map（顺序保留） */
    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i].toString(), kv[i + 1]);
        return m;
    }

    /** List<Map> → JSON 数组字符串 */
    private static String jsonArr(List<Map<String, Object>> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(jsonMap(list.get(i)));
        }
        return sb.append("]").toString();
    }

    /** Map → JSON 对象字符串（极简：字符串加引号，其他 toString） */
    private static String jsonMap(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        int i = 0;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (i++ > 0) sb.append(",");
            sb.append("\"").append(escape(e.getKey())).append("\":");
            sb.append(jsonValue(e.getValue()));
        }
        return sb.append("}").toString();
    }

    @SuppressWarnings("unchecked")
    private static String jsonValue(Object v) {
        if (v == null) return "null";
        if (v instanceof String) return "\"" + escape((String) v) + "\"";
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        if (v instanceof Map) return jsonMap((Map<String, Object>) v);
        if (v instanceof List) {
            List<?> list = (List<?>) v;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(jsonValue(list.get(i)));
            }
            return sb.append("]").toString();
        }
        return "\"" + escape(v.toString()) + "\"";
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}
