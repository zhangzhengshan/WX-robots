# P4 定时宣传改用企业群发 API + ADB 确认

2026-09-15 P4 设计拷问锁定。原 P4/P6 设想定时宣传与媒体发送都走 ADB 操作企微 UI 直接在群里发言（ADR-0001 路径）；本 ADR 把 **P4 定时宣传** 改为「官方企业群发 API 建任务 + ADB 进群发助手点确认」的混合无人值守链路。ADR-0001 不退休，但其适用范围收敛为 **P3 @回复路径**。

## Decision

P4 定时宣传分两段执行，全部自动完成：

1. **创建任务**：调客户联系 API [`add_msg_template`](https://developer.work.weixin.qq.com/document/path/92135)（`chat_type=group`、`sender`=群主 userid、`chat_id_list` 取自 room_map 经白名单过滤）。access_token 复用 `contact.secret` 的 ContactApiClient 缓存。该接口不直接发消息，只在 sender 的企微「群发助手」里生成一条待发送任务。
2. **确认发送**：ADB 四层导航（全部用 text/resource-id 证据，不用猜坐标）：
   - 消息列表点「群发助手」条目（`rid=i4o`）
   - 历史页取 ListView(`rid=iy0`) 内最新的任务卡片（`rid=ipg`），从新到旧逐个点开
   - 详情页校验 `rid=epi`=「待发送」且 `rid=epi` 内容前缀匹配任务文本，然后点 `rid=cre`「发送」
   - 历史页卡片正文渲染在 uiautomator 不可见的表面（ipg 子树 dump 为空），无法在历史页直接定位卡片内按钮，必须进详情页。
   - **第四层——清理历史卡片**（ADR-0011）：点发送（或 dry-run 跳过）后 BACK 回历史页，长按最新 ipg 卡片 → 点长按菜单「删除」→ 点确认浮层「删除消息」→ 截图存证。dry-run 和真发都删；失败只 WARN + 写 alert_throttle(`task_cleanup_failed`)，不翻转 run 状态。

配套约束：

- **dry-run 安全闸门**：`p4.dry-run=true` 时 API 真创建、ADB 真导航并校验「发送」按钮，但最后一步不点击。不点发送不占用客户群接收配额，可安全重复演练；上线置 false。
- **每群每天一条**：管理端表单层不允许同一群一天配置第二次（P5 实现）；API 侧企微「群发助手」规则也可设为每天一条。代码不做 DB 硬约束。
- **隔离**：P3 @回复仍走 outbound_message 队列 + AdbSender；P4 走独立 task_run 状态机 + ApiMsgSender，不共用队列表。两条 ADB 链路用 `WeChatUi.SESSION_LOCK` 串行（P4 让路给消费线程天然时序，错发风险归零）。
- **状态机**：task_run `pending → template_created → send_clicked`（dry-run 为 `dry_run_verified`）；API 失败与 ADB 失败分列 `api_attempts/adb_attempts`，各最多 3 次，**退避 5min/30min/60min**（见 [ADR-0010](0010-p4-backoff-and-per-room-quota.md)，2026-09-16 反转原 30s/2min/10min；配置项 `p4.retry-backoff-ms`）；ADB 重试复用 msgid 不重建任务；进程在 template_created 后崩溃，重启只重试 ADB。
- **幂等**：`UNIQUE(task_id, planned_date)` 保证重启/重放不二次创建任务；错过 planned_time 超过 `p4.makeup-window-hours`（默认 4h）一次性任务标 failed_missed、周期任务不补等明天；暂停期到期由 P5 停用开关写 cancelled_paused（TaskStore.cancelPendingRuns）。
- **同群一天一条**（双层兜底，见 [ADR-0010](0010-p4-backoff-and-per-room-quota.md)）：① 表单层——管理端创建/编辑时按 rhythm 计算未来触发日期集合与现有任务目标群取交集，冲突直接拒绝；② 运行时层——`TaskScheduler.tick` 触发前查 `TaskStore.hasRoomSentToday(roomid, today)` 检测当天该群是否已有 send_clicked 状态的 run，有则不创建新 run + WARN + 写 alert_throttle。企微侧规则也设每天一条，三层兜底。
- **发送者/群范围**：sender 存 scheduled_task.sender_userid（缺省 my.userid=YuanFoZi）；room_map 内的群运维保证全部归属同一群主；测试期 `test.target-roomid` 白名单收敛到单群。
- 第一刀仅文本（≤4000 字节）；图片/视频/文件（素材管理上传得 media_id + attachments）为 P4 第二刀，原 P6 批次并入该刀，详见 [ADR-0009](0009-scheduled-promo-media-attachments.md)。

## Alternatives Considered

- **纯 ADB 在群里直接发言（原 P4 设计）**：与 P3 复用同一链路看似省事，但定时宣传属于「企业群发」语义，官方 API 提供任务化、配额、可追溯能力，且不用把机器人导航进每个群操作。否决用于 P4（P3 因需要 @提及且要求即时对话，官方群发 API 不支持 @，仍保留纯 ADB）。
- **API 建任务后人工点发送**：省掉 ADB 确认环节，但依赖人记得点，无人值守不成立，还要新增「任务未确认」告警。否决，改为 ADB 自动确认。
- **历史页直接点卡片内按钮（不进详情页）**：卡片正文在 uiautomator dump 中为空表面，按钮坐标随文本行数浮动且页面还有「发送今日全部待群发的消息」批量入口，误点会一次发掉全部 pending 任务。实测后否决，固定走详情页（按钮 rid=cre 稳定可定位）。
- **P4 也走 OutboundSender/outbound_message 队列**：队列语义是「一条消息一次发送」，P4 是「建任务 + 二次确认 + 人机协同状态」，塞进同一状态机需要大量特判。否决，独立 task_run。

## Consequences

- 优势：定时宣传全程无人值守但仍走官方任务通道，可在企微管理后台追溯；dry-run 可零配额反复演练；P3 即时对话链路零改动。
- 代价：P3/P4 两条出站路径并存，维护两套状态机；强依赖群主账号（YuanFoZi）在同一台模拟器登录，群发助手条目需置顶可见；企微 UI 改版影响面多了三个页面（依赖 rid i4o/iy0/ipg/epi/epd/cre，版本升级需回归）。
- 影响：ADR-0001 头部加适用范围备注；batched-development-plan.md 的 P4 段重写、P6 段标记并入 P4 第二刀；scope.md 同步；scheduled_task/task_run 表结构在 P4 启用时变更（ALTER 平滑升级）。
