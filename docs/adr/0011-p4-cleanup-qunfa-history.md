# P4 发送后自动删除群发助手历史卡片

2026-09-16 P4 定时宣传 ADB 确认链路末尾增加「删除历史卡片」步骤。原 ADR-0008 四层导航到点「发送」即止，历史页会残留任务卡片（dry-run 残留「待发送」、真发残留「已发送」），日积月累干扰下次卡片定位且暴露发送内容。本 ADR 记录删除链路的设计决策。

## Decision

在 `ApiMsgSender.confirmByAdbClick` 点发送（或 dry-run 跳过）后、`leaveQunFaToMessageList` 之前，插入 `WeChatUi.deleteLatestQunFaCard()` 清理链路：

1. **BACK 回历史页**：从详情页 BACK 1-3 次直到 dump 确认在历史页（`isQunFaHistoryPage`）。
2. **状态校验（退化模式）**：dump 历史页，dry-run 期望含「发送」、真发期望含「已发送」。卡片正文对 uiautomator 不可见（ADR-0008 已知），dump 不到时信任顺序——SESSION_LOCK 串行保证最新 ipg 卡片必然是本次任务的。两种情况都截图存证。
3. **长按最新卡片**：`Adb.longPress(bounds, 800ms)`（同点 swipe 800ms 触发长按菜单）。
4. **点长按菜单「删除」**：dump 找 `text="删除"` → tapCenter。
5. **点确认浮层「删除消息」**：dump 找 `text="删除消息"` → tapCenter。实测：浮层按钮文字是「删除消息」而非「删除」。
6. **验证 + 截图**：dump 确认卡片数减少，截图 `p4_cleanup_done`。

失败处理（grilling 7 轮收敛）：

- **会话内重试 3 次**（含首次）：任一步骤失败先 BACK 关闭残留菜单/浮层，再从长按开始重试。
- **3 次仍失败**：WARN + 截图 `p4_cleanup_failed` + 写 `alert_throttle(event_type='task_cleanup_failed')`（P7 接邮件通知人工删除）。
- **不翻转 run 状态**：删除是卫生操作，不影响业务结果。run 仍记 `send_clicked`（生产）或 `dry_run_verified`（dry-run）。
- **不补删崩溃残留**：进程在点发送后崩溃、重启后不补删。无法区分「我们的」和「客户手工建的」卡片，误删风险 > 卫生价值。崩溃场景写 `task_cleanup_failed` 由 P7 邮件通知人工。
- **无配置开关**：删除链路默认执行，无开关。残留卡片不阻断下次任务（下次按内容前缀匹配，不会误删历史任务）。

UI 证据（MuMu 1080×1920，2026-09-16 实测）：

| 界面 | 定位方式 | 实测值 |
|------|---------|--------|
| 长按菜单 | `text="删除"` | bounds [515,614][564,714] |
| 确认浮层 | `text="删除消息"` | bounds [480,1726][600,1767] |
| 确认浮层取消 | `text="取消"` | bounds [0,1815][1080,1920] |
| 删除后历史页 | 0 张 ipg 卡片 | — |

## Alternatives Considered

- **只删 dry-run 卡片、不删已发送卡片**：dry-run 残留「待发送」会误导下次卡片匹配，但真发残留「已发送」同样干扰定位。两种模式都删更干净，且长按菜单和确认浮层与卡片状态无关（实测一致），实现无额外分支。选 A：都删。
- **删除失败翻转 run 状态为失败态**：发送已成功（或 dry-run 已验证），删除只是卫生操作。翻转状态会触发重试 → 重新创建 API 任务 → 浪费配额。否决，失败只告警不翻转。
- **崩溃后重启补删残留卡片**：无法区分自动创建和客户手工创建的卡片，误删客户卡片风险高于残留卫生价值。否决，残留不阻断下次任务，崩溃场景只告警。
- **加配置开关控制是否删除**：用户不需要关删除的场景（Q6），加开关增加配置复杂度。否决，默认执行无开关。

## Consequences

- 优势：历史页保持干净，下次任务卡片定位不受干扰；发送内容不长期暴露在群发助手历史中。
- 代价：ADB 链路增加 4 个 dump + 2 个 tap + 1 个 longPress，总时长增加约 10-15 秒；依赖长按菜单和确认浮层的 text 不变（企微改版需回归）。
- 影响：ADR-0008 第 2 步从「三层导航」改为「四层导航」；`WeChatUi` 新增 `deleteLatestQunFaCard()` 方法；`Adb` 新增 `longPress(bounds, ms)` 方法；`ApiMsgSender.confirmByAdbClick` 在 `tapSendButtonDryRunAware` 后插入删除调用；`AlertThrottle` 新增事件类型 `task_cleanup_failed`。
