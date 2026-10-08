package com.mumu.bot.schedule;

import com.mumu.bot.store.Db;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * scheduled_task / task_run 的唯一读写入口（P4）。写操作在 synchronized(Db.LOCK) 内。
 */
public final class TaskStore {

    /** 调度候选：任务定义 + 其当天执行行 */
    public static final class Candidate {
        public final ScheduledTask task;
        public final TaskRun run;
        Candidate(ScheduledTask task, TaskRun run) {
            this.task = task;
            this.run = run;
        }
    }

    // ===== scheduled_task =====

    /** 新建任务，返回 task id（P4Test / P5 管理端用） */
    public long insertTask(ScheduledTask t) {
        synchronized (Db.LOCK) {
            try (PreparedStatement ps = Db.conn().prepareStatement(
                    "INSERT INTO scheduled_task (name,msg_type,content,media_path,rhythm,weekdays,"
                    + "planned_time,target_rooms,enabled,fire_once_at,sender_userid,created_at) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, t.name);
                ps.setString(2, t.msgType == null ? "text" : t.msgType);
                ps.setString(3, t.content);
                ps.setString(4, t.mediaPath);
                ps.setString(5, t.rhythm);
                ps.setString(6, t.weekdays);
                ps.setString(7, t.plannedTime);
                ps.setString(8, t.targetRooms);
                ps.setInt(9, t.enabled ? 1 : 0);
                if (t.fireOnceAt == null) ps.setNull(10, java.sql.Types.INTEGER);
                else ps.setLong(10, t.fireOnceAt);
                ps.setString(11, t.senderUserid);
                ps.setLong(12, System.currentTimeMillis());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        t.id = keys.getLong(1);
                        return t.id;
                    }
                    throw new IllegalStateException("insertTask 未拿到自增 id");
                }
            } catch (Exception e) {
                throw new RuntimeException("scheduled_task 插入失败: " + e.getMessage(), e);
            }
        }
    }

    public ScheduledTask getTask(long id) {
        try (PreparedStatement ps = Db.conn().prepareStatement(
                "SELECT id,name,msg_type,content,media_path,rhythm,weekdays,planned_time,"
                + "target_rooms,enabled,fire_once_at,sender_userid,api_msgid,created_at "
                + "FROM scheduled_task WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapTask(rs) : null;
            }
        } catch (Exception e) {
            throw new RuntimeException("scheduled_task 查询失败: " + id, e);
        }
    }

    public List<ScheduledTask> listEnabledTasks() {
        List<ScheduledTask> out = new ArrayList<>();
        try (PreparedStatement ps = Db.conn().prepareStatement(
                "SELECT id,name,msg_type,content,media_path,rhythm,weekdays,planned_time,"
                + "target_rooms,enabled,fire_once_at,sender_userid,api_msgid,created_at "
                + "FROM scheduled_task WHERE enabled=1 ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(mapTask(rs));
        } catch (Exception e) {
            throw new RuntimeException("scheduled_task 列表查询失败", e);
        }
        return out;
    }

    /** P5 管理端：全量任务（含停用的），按 id 倒序（新建在前） */
    public List<ScheduledTask> listAllTasks() {
        List<ScheduledTask> out = new ArrayList<>();
        try (PreparedStatement ps = Db.conn().prepareStatement(
                "SELECT id,name,msg_type,content,media_path,rhythm,weekdays,planned_time,"
                + "target_rooms,enabled,fire_once_at,sender_userid,api_msgid,created_at "
                + "FROM scheduled_task ORDER BY id DESC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(mapTask(rs));
        } catch (Exception e) {
            throw new RuntimeException("scheduled_task 全量查询失败", e);
        }
        return out;
    }

    /** P5 管理端：编辑任务（全字段更新；enabled/fire_once_at 可空） */
    public void updateTask(ScheduledTask t) {
        synchronized (Db.LOCK) {
            try (PreparedStatement ps = Db.conn().prepareStatement(
                    "UPDATE scheduled_task SET name=?,msg_type=?,content=?,media_path=?,rhythm=?,weekdays=?,"
                    + "planned_time=?,target_rooms=?,enabled=?,fire_once_at=?,sender_userid=? WHERE id=?")) {
                ps.setString(1, t.name);
                ps.setString(2, t.msgType == null ? "text" : t.msgType);
                ps.setString(3, t.content);
                ps.setString(4, t.mediaPath);
                ps.setString(5, t.rhythm);
                ps.setString(6, t.weekdays);
                ps.setString(7, t.plannedTime);
                ps.setString(8, t.targetRooms);
                ps.setInt(9, t.enabled ? 1 : 0);
                if (t.fireOnceAt == null) ps.setNull(10, java.sql.Types.INTEGER);
                else ps.setLong(10, t.fireOnceAt);
                ps.setString(11, t.senderUserid);
                ps.setLong(12, t.id);
                ps.executeUpdate();
            } catch (Exception e) {
                throw new RuntimeException("scheduled_task 更新失败: " + t.id, e);
            }
        }
    }

    /** P5 管理端：硬删任务 + 级联删 task_run（synchronized 保证原子） */
    public void deleteTask(long taskId) {
        synchronized (Db.LOCK) {
            try (PreparedStatement delRuns = Db.conn().prepareStatement(
                    "DELETE FROM task_run WHERE task_id=?");
                 PreparedStatement delTask = Db.conn().prepareStatement(
                    "DELETE FROM scheduled_task WHERE id=?")) {
                delRuns.setLong(1, taskId);
                delRuns.executeUpdate();
                delTask.setLong(1, taskId);
                delTask.executeUpdate();
            } catch (Exception e) {
                throw new RuntimeException("scheduled_task 删除失败: " + taskId, e);
            }
        }
    }

    /** P5 管理端：列出某任务最近 20 条 run（按 id 倒序） */
    public List<TaskRun> listRunsByTask(long taskId) {
        List<TaskRun> out = new ArrayList<>();
        try (PreparedStatement ps = Db.conn().prepareStatement(
                "SELECT id,task_id,planned_date,state,api_attempts,adb_attempts,next_attempt_at,"
                + "msgid,last_error,fired_at,created_at FROM task_run "
                + "WHERE task_id=? ORDER BY id DESC LIMIT 20")) {
            ps.setLong(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapRun(rs));
            }
        } catch (Exception e) {
            throw new RuntimeException("task_run 列表查询失败: " + taskId, e);
        }
        return out;
    }

    /**
     * P5 / ADR-0010：检测某群今天是否已有 send_clicked 状态的 run（占用了企微当天配额）。
     * dry_run_verified 不算（dry-run 未真发）；cancelled_paused 不算（已作废）。
     * SQL JOIN scheduled_task + task_run，用 target_rooms LIKE 匹配（前后加逗号防子串误匹配）。
     * @param roomid 群 ID
     * @param today  yyyy-MM-dd
     * @return true=已发过（不再创建新 run）
     */
    public boolean hasRoomSentToday(String roomid, String today) {
        String matchPattern = "%," + roomid + ",%";
        try (PreparedStatement ps = Db.conn().prepareStatement(
                "SELECT 1 FROM task_run r JOIN scheduled_task t ON t.id=r.task_id "
                + "WHERE r.state='send_clicked' AND r.planned_date=? "
                + "AND (',' || t.target_rooms || ',') LIKE ? LIMIT 1")) {
            ps.setString(1, today);
            ps.setString(2, matchPattern);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            throw new RuntimeException("hasRoomSentToday 查询失败: " + roomid + " " + today, e);
        }
    }

    /** 记录最近一次 API msgid 到任务行（管理端展示用） */
    public void updateTaskApiMsgid(long taskId, String msgid) {
        synchronized (Db.LOCK) {
            try (PreparedStatement ps = Db.conn().prepareStatement(
                    "UPDATE scheduled_task SET api_msgid=? WHERE id=?")) {
                ps.setString(1, msgid);
                ps.setLong(2, taskId);
                ps.executeUpdate();
            } catch (Exception e) {
                throw new RuntimeException("scheduled_task 更新 msgid 失败", e);
            }
        }
    }

    // ===== task_run =====

    /**
     * 幂等占位：UNIQUE(task_id, planned_date)。当天首次返回新建 runId；已存在返回 null。
     * 用 INSERT OR IGNORE 的 affected rows 判定（1=新建，0=已存在）。
     */
    public Long createRunIfAbsent(long taskId, String plannedDate) {
        synchronized (Db.LOCK) {
            try (PreparedStatement ps = Db.conn().prepareStatement(
                    "INSERT OR IGNORE INTO task_run (task_id,planned_date,state,created_at) "
                    + "VALUES (?,?,?,?)")) {
                ps.setLong(1, taskId);
                ps.setString(2, plannedDate);
                ps.setString(3, TaskRun.PENDING);
                ps.setLong(4, System.currentTimeMillis());
                int affected = ps.executeUpdate();
                if (affected == 0) return null; // 当天 run 已存在
            } catch (Exception e) {
                throw new RuntimeException("task_run 占位失败: " + taskId + "/" + plannedDate, e);
            }
            TaskRun r = getRunByDate(taskId, plannedDate);
            return r == null ? null : r.id;
        }
    }

    public TaskRun getRunByDate(long taskId, String plannedDate) {
        try (PreparedStatement ps = Db.conn().prepareStatement(
                "SELECT id,task_id,planned_date,state,api_attempts,adb_attempts,next_attempt_at,"
                + "msgid,last_error,fired_at,created_at FROM task_run "
                + "WHERE task_id=? AND planned_date=?")) {
            ps.setLong(1, taskId);
            ps.setString(2, plannedDate);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRun(rs) : null;
            }
        } catch (Exception e) {
            throw new RuntimeException("task_run 查询失败", e);
        }
    }

    /**
     * 扫描需要继续推进的 run（非终态、退避到期），JOIN 任务定义（任务被停用则不推进）。
     * 含：pending（新建占位后本 tick 继续）、failed_api、template_created（崩溃恢复）、ADB 失败态。
     */
    public List<Candidate> findRunnableRuns(long nowMs) {
        List<Candidate> out = new ArrayList<>();
        String sql =
            "SELECT t.id,t.name,t.msg_type,t.content,t.media_path,t.rhythm,t.weekdays,t.planned_time,"
            + "t.target_rooms,t.enabled,t.fire_once_at,t.sender_userid,t.api_msgid,t.created_at,"
            + "r.id,r.task_id,r.planned_date,r.state,r.api_attempts,r.adb_attempts,r.next_attempt_at,"
            + "r.msgid,r.last_error,r.fired_at,r.created_at "
            + "FROM task_run r JOIN scheduled_task t ON t.id=r.task_id "
            + "WHERE t.enabled=1 AND r.state NOT IN "
            + "('send_clicked','dry_run_verified','failed_final','failed_missed','cancelled_paused') "
            + "AND (r.next_attempt_at IS NULL OR r.next_attempt_at <= ?) "
            + "ORDER BY r.id";
        try (PreparedStatement ps = Db.conn().prepareStatement(sql)) {
            ps.setLong(1, nowMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ScheduledTask t = mapTask(rs, 1);
                    TaskRun r = mapRun(rs, 15);
                    out.add(new Candidate(t, r));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("task_run 可运行扫描失败", e);
        }
        return out;
    }

    /** 全字段回写（状态机每次转移调一次） */
    public void updateRun(TaskRun r) {
        synchronized (Db.LOCK) {
            try (PreparedStatement ps = Db.conn().prepareStatement(
                    "UPDATE task_run SET state=?,api_attempts=?,adb_attempts=?,next_attempt_at=?,"
                    + "msgid=?,last_error=?,fired_at=? WHERE id=?")) {
                ps.setString(1, r.state);
                ps.setInt(2, r.apiAttempts);
                ps.setInt(3, r.adbAttempts);
                if (r.nextAttemptAt == null) ps.setNull(4, java.sql.Types.INTEGER);
                else ps.setLong(4, r.nextAttemptAt);
                ps.setString(5, r.msgid);
                ps.setString(6, r.lastError);
                if (r.firedAt == null) ps.setNull(7, java.sql.Types.INTEGER);
                else ps.setLong(7, r.firedAt);
                ps.setLong(8, r.id);
                ps.executeUpdate();
            } catch (Exception e) {
                throw new RuntimeException("task_run 更新失败: " + r.id, e);
            }
        }
    }

    /** P5 停用任务时：尚未创建 API 任务的 pending 行直接作废（暂停期间触发作废） */
    public int cancelPendingRuns(long taskId) {
        synchronized (Db.LOCK) {
            try (PreparedStatement ps = Db.conn().prepareStatement(
                    "UPDATE task_run SET state='cancelled_paused',fired_at=? "
                    + "WHERE task_id=? AND state='pending'")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setLong(2, taskId);
                return ps.executeUpdate();
            } catch (Exception e) {
                throw new RuntimeException("task_run 作废失败", e);
            }
        }
    }

    // ===== 映射 =====

    private ScheduledTask mapTask(ResultSet rs) throws Exception {
        return mapTask(rs, 1);
    }

    private ScheduledTask mapTask(ResultSet rs, int c) throws Exception {
        ScheduledTask t = new ScheduledTask();
        t.id = rs.getLong(c);
        t.name = rs.getString(c + 1);
        t.msgType = rs.getString(c + 2);
        t.content = rs.getString(c + 3);
        t.mediaPath = rs.getString(c + 4);
        t.rhythm = rs.getString(c + 5);
        t.weekdays = rs.getString(c + 6);
        t.plannedTime = rs.getString(c + 7);
        t.targetRooms = rs.getString(c + 8);
        t.enabled = rs.getInt(c + 9) == 1;
        long foa = rs.getLong(c + 10);
        t.fireOnceAt = rs.wasNull() ? null : foa;
        t.senderUserid = rs.getString(c + 11);
        t.apiMsgid = rs.getString(c + 12);
        t.createdAt = rs.getLong(c + 13);
        return t;
    }

    private TaskRun mapRun(ResultSet rs) throws Exception {
        return mapRun(rs, 1);
    }

    private TaskRun mapRun(ResultSet rs, int c) throws Exception {
        TaskRun r = new TaskRun();
        r.id = rs.getLong(c);
        r.taskId = rs.getLong(c + 1);
        r.plannedDate = rs.getString(c + 2);
        r.state = rs.getString(c + 3);
        r.apiAttempts = rs.getInt(c + 4);
        r.adbAttempts = rs.getInt(c + 5);
        long n = rs.getLong(c + 6);
        r.nextAttemptAt = rs.wasNull() ? null : n;
        r.msgid = rs.getString(c + 7);
        r.lastError = rs.getString(c + 8);
        long f = rs.getLong(c + 9);
        r.firedAt = rs.wasNull() ? null : f;
        r.createdAt = rs.getLong(c + 10);
        return r;
    }
}
