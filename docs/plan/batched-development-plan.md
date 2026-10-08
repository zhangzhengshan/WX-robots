# 分批次开发计划

> 依据 `docs/scope.md`（功能范围定稿）与两轮设计拷问结论整理，2026-09-12 锁定。
> 规则：每完成一个阶段即停止，按本阶段「验收方法 + 成功定义」验收通过后，才进入下一阶段。

## 已锁定的全局决策

1. 垂直切片优先：每个批次交付可肉眼验收的端到端行为，不做无行为的水平铺层。
2. 单进程、单 jar、SQLite 单文件 WAL；零构建系统，第三方 jar 放 `bot/lib/`（ADR-0004）。
3. 名册以 `(roomid, userid)` 复合键存储，@用名 = 群昵称优先、否则企微名；手工改名打 `manual_override`，同步不覆盖。
4. 名册维护：启动全量 + 每 5 分钟客户联系 API 轮询 diff + 存档未知 userid 即时刷新；入群新增、退群硬删除（事务内原子 diff）。见 ADR-0005。
5. 多群导航原则：宁可漏回，不可错群发——列表找不到目标群名则跳过该条回复并记日志。
6. 群范围固定由 room_map 表决定（初始 3 个群），新增群手工插表，不做代码自动发现。
7. qyapi.weixin.qq.com（客户联系 API）直连，不走 socks5 代理；socks5 代理仅用于会话存档 SDK。

## 批次总览

| 批次 | 内容 | 验收形态 |
|---|---|---|
| P1 | 数据地基 + 多群闭环：SQLite 全量表、room_map、名册 API 同步/新人检测/退群删除、多群路由、lastSeq 持久化 | 三群分别 @，机器人分别在对应群正确 @回复；重启不重复回复 |
| P2 | 出站队列：outbound_message 持久化状态机、按 source_id 去重、30s/2min/10min 退避重试、AI 回复优先 | 断网/杀进程重启不丢不重，失败自动重试 |
| P3 | AI 问答：kb_document、云端 LLM、整段单条（ADR-0006 取消分段）、诚实边界+购买引导（全提示词层） | 知识库内问题按原文答，无关问题不乱编 |
| P4 | 定时宣传（文本）：企业群发 API 建任务 + ADB 进群发助手点确认；一次性/每天/周几三节奏、暂停、unique(task_id,planned_date) 幂等、4h 错过补发、dry-run 闸门 | dry-run 端到端验证不占配额；重启不重发、暂停期间触发作废 |
| P5 | 管理端（JDK 内嵌 HttpServer，零新 jar）：知识库 + 定时任务（每群每天 1 条表单约束 + 运行时硬判定兜底）+ 追加群聊；日志/消息/队列/名册/群映射展示砍掉（落文件或 DB 工具直查） | 浏览器打开即可新建/编辑/触发任务、上传/删除知识库文件、追加群 |
| ~~P6~~ | **已并入 P4 第二刀**：图片/视频/文件不再走 ADB 相册流程，改走企业群发 API 素材管理（media_id/attachments） | 定时任务能带图片/文件（API 通道） |
| P7 | 告警 + 看门狗：6 类邮件告警、30 分钟节流、watchdog.ps1 崩溃重启/熔断不重启 | 制造熔断/杀进程，收到邮件且行为正确 |

---

## P1 数据地基 + 多群闭环

### 交付物

- `bot/lib/`：sqlite-jdbc、slf4j-api、slf4j-simple 三个 jar；run.bat classpath 全量编译
- config：新增 `contact.secret`、`db.path`；退役单群配置 `target.group`/`target.roomid`（群范围由 room_map 决定）
- `store/Db.java`：单文件 WAL 连接，9 张表一次性建齐（kv/room_map/member_roster/inbound_message/outbound_message/scheduled_task/task_run/kb_document/alert_throttle），room_map 种子 3 行
- `store/RoomMap.java`、重写 `store/MemberRoster.java`（DB 复合键、display_name、manual_override、pending）
- `contact/ContactApiClient.java`：accessToken 内存缓存（到期前 5 分钟刷新，token 不入库）；groupchat/get?need_name=1
- `contact/RosterSyncService.java`：启动全量、5 分钟轮询、未知 userid 即时刷新；事务内 insert/update/delete diff
- `archive/ArchiveClient.java`：lastSeq 读写 kv（断点续拉）；room_map 全群路由；回调带 roomid/群名/显示名；未知成员先即时刷新
- `outbound/adb/WeChatUi.java`：动态 enterGroup(群名)，内存记当前群，BACK 回列表再点群；找不到群名返回失败
- `outbound/ReplyDispatcher.java` + `AtMessage`：按消息所在群导航后回复
- `RosterSyncTest.java`：手动验证入口，同步并打印 room_map 与 member_roster 全量内容

### 验收方法与成功定义

| # | 验收动作 | 成功定义 |
|---|---|---|
| 1 | 运行 RosterSyncTest | room_map 3 行群名与企微一致；三群全部成员入库，@用名与群内显示一致（API 权威值） |
| 2 | 企微里把一个测试号拉进群、再移出群，各等 ≤5 分钟 | 名册自动出现该行、自动硬删除该行，日志打印 diff |
| 3 | 真人账号分别在 1/2/3 群各 @机器人 一次 | 机器人分别进入对应群回复并 @出正确显示名；其他群不出现任何消息 |
| 4 | @之后重启机器人进程 | kv.lastSeq 续拉，不对重启前旧 @ 重复回复 |

失败行为约定：导航找不到群 → 跳过回复并记日志（P7 起接邮件告警）；名册查无此人且即时刷新仍无 → 落 pending 行，回复降级为不带 @ 的纯文本。

### 事实勘误（设计拷问期间修正）

- `groupchat/list` 不返回 `member_version`；5 分钟轮询直接对 3 个群各调一次 `groupchat/get`（日约 864 次，远低于限额），member_version 仅作日志参考。
- 原硬编码名册与 API 权威数据不一致（如 YuanFoZi 实名"张飞扬"），P1 一律以 API 为准重写。

---

## P2 出站队列

### 已锁定设计决策

1. **发送成功判定**：ADB 命令链无异常 + 截图落盘 `data/screenshots/{msg_id}_{ts}.png` + 日志记录路径。不做内容比对（OCR/像素 diff 在企微聊天页不可靠）。
2. **消费模型**：单线程 500ms 轮询 DB，`SELECT ... WHERE status='pending' AND (next_retry_at IS NULL OR next_retry_at <= ?) ORDER BY priority ASC, created_at ASC LIMIT 1`。认领用 `UPDATE status='sending' WHERE id=? AND status='pending'`，看 affected rows 是否=1（单线程下永远=1，但为 P5 管理端并发触发的乐观锁预留语义）。
3. **进程重启恢复**：启动扫一次 `UPDATE outbound_message SET status='pending', next_retry_at=NULL WHERE status='sending'`。attempts 不变（中断不算失败次数），靠 attempts<3 兜底。
4. **OutboundSender 接口（Result 模式）**：
   ```java
   public interface OutboundSender {
       SendResult send(OutboundMsg msg);
   }
   public final class SendResult {
       public final boolean ok;
       public final String error;
   }
   ```
   调用方根据 `ok` 决定 `UPDATE status='sent'` 或 `attempts+1 + next_retry_at`。生产实现 `AdbSender` 走 `WeChatUi`，测试桩 `StubSender` 按 attempts 计数返回 ok/fail。
5. **去重指纹 = source_id**：每个出站消息全局唯一来源标识，5 分钟窗口内同一 source_id 只入队一次。不按 content 维度去重。
   - @ 来源：`at:{seq}:{segment_idx}`（一次 @ 的多段回复每段独立 source_id、独立去重、独立重试）
   - 定时来源：`scheduled:{task_id}:{planned_date}`（与 task_run 唯一约束双保险）
   - 人工来源：`manual:{ts}`
6. **ReplyDispatcher 重构方式**：保留类名作为 ArchiveClient → OutboundQueue 的薄适配层（删掉内存队列和 WeChatUi 直调），ArchiveClient 调用方不变；消费循环移到独立的 QueueConsumer。
7. **AdbSender 与 WeChatUi 关系**：构造注入持有 WeChatUi 实例（`new AdbSender(weChatUi)`）。WeChatUi 保持纯 UI 操作不感知队列；AdbSender 负责调发送方法 + 截图 + 路径记录。不重写 P1 已验收的进群/@选择器逻辑。
8. **QueueTest 形态**：单 main 方法 6 场景，print + 断言（throw RuntimeException 表失败），不引入 JUnit jar：
   1. 3 条不同 source_id 全部 sent、attempts=0
   2. 同 source_id 5 分钟内重复入队仅 1 条入库
   3. StubSender 第 1 次 fail 第 2 次 ok → attempts=1、sent
   4. 连续 3 次 fail → failed、error 非空
   5. sending 崩溃恢复 → 重置 pending、attempts 不变
   6. priority=1 先于 priority=5 被消费
9. **多段回复的 segment_idx 由调用方分配**：ReplyDispatcher 只暴露单段入队 API（enqueueSegment 带 segmentIdx/isMention），不假设 LLM 切分策略；P3 的 AiService 负责切分与逐段入队。P2 验收用"同 seq 两段独立入队"模拟。
10. **截图策略**：sent 与 failed 都截图，文件名 `{msg_id}_{attempt}_{ts}.png`，目录 `data/screenshots/`。失败时路径写入 `outbound_message.error`，成功时记日志。
11. **截图保留期 1 个月（30 天）**：定时扫描截图目录，按文件 mtime 删除 >30 天的文件。
12. **schema 加两列**：`outbound_message` 表加 `is_mention INTEGER DEFAULT 0`、`mention_name TEXT`。CREATE TABLE 直接建；老库 ALTER TABLE ADD COLUMN 平滑升级（SQLite 原生支持）。AdbSender 发送逻辑：`if (msg.isMention) sendWithMention(mention_name, content) else sendPlain(content)`。
13. **截图清理时机**：启动时扫一次 + QueueConsumer 消费循环每 6 小时顺手扫一次（距上次清理 >6h 则执行）。复用消费线程，不引入新并发面。
14. **P2 真机验收文案**（多段，按 `--` 切分，4 段）：
    ```
    您好，已收到您的提问。 -- 我正在调取相关信息，请稍候片刻。 -- 本机器人当前处于 P2 阶段，尚未接入知识库，暂时无法回答具体业务问题。 -- 完整 AI 问答能力将在 P3 批次上线，届时可在群内直接 @ 我提问。
    ```
    ReplyDispatcher 把 `Config.REPLY_MSG` 按 `\\s*--\\s*` 拆分，第 1 段 `is_mention=true`，其余 `is_mention=false`，逐段独立 source_id 入队。P3 替换为 LLM 切分，链路不变。
15. **failed 善后**：P2 只转 failed + 填 error，不做自动重放。OutboundQueue 提供 `requeue(failedId)` 方法（UPDATE 回 pending、attempts=0、清 error/next_retry_at）作为 QueueTest 场景 4 后恢复环境的必需品，也作为 P5 管理端"失败重发"按钮的 API 预留。P2 期间靠日志+截图人工发现，P7 才接邮件告警。

### 一次 @ 多段回复语义

- LLM 一次回答切分成 N 段，逐段独立入队，每段独立 `source_id`
- **第 1 段**带 @ 提及发起者；**第 2..N 段**纯文本，不再 @
- 同群同用户在不同时间再次 @ 提另一个问题，又重新开启一轮"第 1 段 @、后续不 @"
- 同群不同用户问相同问题不去重（source_id 不同）
- 同一 @ 来源的多段回复即使内容相同也不去重（segment_idx 不同）

### 交付物

- `outbound/OutboundMsg.java`：队列消息载体（id/roomid/roomName/msg_type/content/mentionName/isMention/priority/source_id/...）
- `outbound/OutboundQueue.java`：入队 API（带去重）、状态机 UPDATE
- `outbound/OutboundSender.java` + `outbound/SendResult.java`：发送抽象
- `outbound/adb/AdbSender.java`：生产实现，内部调 `WeChatUi.sendWithMention` / `sendPlain` + `Adb.screenshot()`
- `outbound/QueueConsumer.java`：单线程消费循环 + 重试调度
- `outbound/QueueTest.java`：自动化验收入口（用 StubSender）
- `outbound/ReplyDispatcher.java`：重构，从内存 ConcurrentLinkedQueue 改为写 OutboundQueue + 触发消费
- schema 不变（`outbound_message` 已含 priority/attempts/next_retry_at/dedup_hash/error/sent_at 字段）

### 验收方法与成功定义

| # | 验收动作 | 成功定义 |
|---|---|---|
| 1 | 运行 QueueTest（StubSender 全程返回 ok） | 入队的消息全部走 pending→sending→sent；DB 中 sent_at 不为空；attempts=0；source_id 重复入队被跳过 |
| 2 | QueueTest 模拟发送失败（StubSender 第 N 次返回 fail） | attempts 累加；next_retry_at 按 30s/2min/10min 推进；3 次后 status=failed；error 字段填异常信息 |
| 3 | QueueTest 模拟进程崩溃（在 sending 状态时停止消费线程） | 重启（再次启动消费线程）后 sending 行被重置为 pending、attempts 不变、继续重试 |
| 4 | 真机验收：在群里 @ 机器人 | 机器人回复带 @；**用户连续多次 @（包括连续多次问相同问题）每次都回答**；只有进程重启后存档重新拉到同一 seq 时才被 source_id 去重 |
| 5 | 真机验收：杀进程后重启 | 重新启动不重发已 sent 的消息；pending 行继续发送 |

---

## P3 AI 问答（已完成 2026-09-15）

### 已锁定设计决策

1. **云端 LLM**：第三方中转 `https://www.orbapi.cn/v1`，OpenAI 兼容 `/chat/completions`，模型 `deepseek-v4-flash`；国内直连不走 socks5。新增配置 `ai.base-url/ai.api-key/ai.model/ai.prompt.path`，密钥只在 config.properties。JDK 自带 `java.net.http.HttpClient`，零新 HTTP jar。
2. **成品提示词**：业务方提供的德润芯客服提示词原样落盘 `bot/data/ai_prompt.txt`（含 `{{Content}}`、`{{query}}` 占位符），**每次调用读盘**，改提示词不用重启。
3. **取消分段（反转 scope #13，见 ADR-0006）**：一次 @ 的回复整段单条入队，source_id 收敛为 `at:{seq}:0`；长度只靠提示词约束（2-4 句）。LLM 输出的换行/连续空白入队前折叠为单空格（ADB input text 不支持换行）。
4. **单线程 AI worker**：监听线程只投递问题立即返回，LLM 调用（≤60s）不阻塞存档拉取与 lastSeq 推进；worker 内调 LLM 前先做 source_id 去重预查，命中连调用都不发。
5. **空 @ / 失败兜底**：剥掉 @名 后为空 → 不调 LLM，回固定闲聊引导；HTTP 失败/超时/空响应 → 回固定兜底文案；60s 超时、进程内不重试；AiService 内连败计数，≥5 打 WARN（P7 接邮件）。
6. **知识库目录为事实源（见 ADR-0007）**：`data/kb/` 放 `.docx/.xlsx/.txt/.md`，标题=文件名去扩展名；启动自动同步一次 + `KbImport` 手动入口；upsert/删除 diff（文件删→行删），解析失败保留旧行；同标题多扩展名只收排序第一个。
7. **格式与上限**：Apache POI 5.5.1 解析 docx/xlsx（jar 放 bot/lib，log4j-to-slf4j 桥接到 slf4j 2.0）；.doc/.xls 明确报错提示另存；全库转换后纯文本 ≤10 万字，超限单篇跳过、其余继续。xlsx 布局：sheet 名标题行、单元格 ` | ` 连接、空行/空 sheet 跳过。
8. **P5 边界预留**：管理端只做「上传文件 + 列表删除」，不做在线编辑；改一个字也须重新上传并删旧。

### 交付物

- `bot/lib/`：POI 5.5.1 最小依赖集（poi/poi-ooxml/poi-ooxml-lite/xmlbeans/commons 系列/SparseBitSet/curvesapi/log4j-api + log4j-to-slf4j，slf4j 升级 2.0.16）
- `data/ai_prompt.txt`：成品客服提示词模板；`data/kb/`：知识库素材目录
- `ai/AiService.java`：LLM 调用 + AI worker + 去重预查 + 连败计数 + 兜底/空白折叠
- `kb/KbTextExtractor.java`（POI 抽取）、`kb/KbSyncService.java`（目录 diff 同步）、`store/KbStore.java`（kb_document 访问，title 唯一索引）
- `kb/KbImport.java`、`ai/AiTest.java`：两个单 main 验收入口；配套 run_kb_import.bat / run_ai_test.bat
- `ArchiveClient.Listener` 回调增加 question（剥掉 @名 的原文）；`ReplyDispatcher` 重构为「空 @ 直发 / 非空投 worker」；Config 退役 reply.msg/reply.count

### 验收方法与成功定义

| # | 验收动作 | 成功定义 |
|---|---|---|
| 1 | 把 3 个真实素材（企微机器人使用手册.docx、产品介绍及价格.xlsx、茶品操作流程.xlsx）放 data/kb，运行 KbImport | 3 篇全部入库，中文/表格文本可读；打印每篇字数与总字数（≤10 万）；重复运行为「更新」；删文件再运行为「删除」 |
| 2 | AiTest 问知识库内问题（菜单/价格/团建） | 按知识库原文答，不编造价格库存，购买意图附小程序白名单链接（每条最多 1 个） |
| 3 | AiTest 问「美式和拿铁区别」 | 咖啡通用常识作答，不强推、不编数据 |
| 4 | AiTest 问「今天天气怎么样」 | 店外兜底，明确只服务本店咖啡/茶饮，不回答无关内容 |
| 5 | 真机：三群分别 @ 机器人提问 + 只 @ 不说话 | 各在本群收到 1 条带 @ 的整段回复；空 @ 回引导话术；其他群无消息；重启后旧 seq 不重复回复 |
| 6 | 真机：断网/错 key 时 @ | 60s 内收到兜底文案；队列层失败仍走 P2 重试；日志可见连败计数 |

---

## P4 定时宣传（当前批次，2026-09-15 锁定）

### 已锁定设计决策（设计拷问结论）

1. **发送通道（反转原计划，见 ADR-0008）**：放弃 ADB 在群内直接发言，改混合无人值守链路：
   `add_msg_template`（企业群发 API，文档 path/92135）建任务 → ADB 进企微「群发助手」→ 打开本次任务详情页 → 点「发送」。
   API 只建任务不直接发送，必须 sender 确认；ADB 自动完成确认，不依赖人工。
2. **与 P3 完全隔离**：P3 @回复继续走 outbound_message 队列 + AdbSender（ADR-0001 仍适用，但范围收敛为 P3）；P4 走独立 task_run 状态机 + ApiMsgSender。两条 ADB 链路共用 `WeChatUi.SESSION_LOCK` 串行，杜绝导航交叉错发。
3. **凭证与发送者**：复用 `contact.secret`（自建应用 secret）换 access_token（ContactApiClient 缓存）；sender=群主 userid（scheduled_task.sender_userid，缺省 my.userid=YuanFoZi）。room_map 内群运维保证全部归属该群主；只允许发给 room_map 启用群。
4. **测试期白名单**：`test.target-roomid` 非空时目标群交集收敛到该 roomid（当前=wrImBKWQAAqUROMJzOacbd76_FBF5wRA / Agent测试群1群）；生产置空放行 room_map 全部启用群。
5. **dry-run 安全闸门**：`p4.dry-run=true`（默认）时 API 真创建任务、ADB 真导航并校验「发送」按钮可见，最后一步**不点击**——不点发送不占用客户群接收配额，可安全重复演练。上线置 false 才真发。
6. **每群每天一条**：管理端表单层禁止同群一天配第二次（P5 落地）；企微「群发助手」规则侧也设每天一条。代码不做 DB 硬约束。
7. **三层 ADB 导航（实测证据，2026-09-15 MuMu/企微 dump）**：
   - 消息列表「群发助手」入口：`text=群发助手 rid=i4o`（点 TextView 坐标命中父容器；条目需置顶）
   - 历史页：ListView `rid=iy0`，任务卡片 `rid=ipg`（clickable），文档顺序越靠下越新，倒序尝试，最多 3 张
   - 详情页校验：`rid=nwf`=「详情」、`rid=epi`=「待发送」、`rid=epd` 内容空白折叠后前 20 字符前缀匹配；不匹配 BACK 试上一张（防误点历史任务）
   - 发送按钮：`text=发送 rid=cre`（bounds 实测 [28,466][1052,547]）
   - 历史页卡片正文（含卡片内发送按钮/「发送今日全部待群发的消息」批量入口）渲染在 uiautomator 不可见表面，**禁止在历史页盲点**，必须进详情页。
8. **task_run 状态机**：`pending → template_created → send_clicked`（dry-run 终态 `dry_run_verified`）；失败态 `failed_api / failed_no_entry / failed_no_btn / failed_click`；终态 `failed_final / failed_missed / cancelled_paused`。
9. **重试分离**：api_attempts 与 adb_attempts 独立计数，各最多 3 次，退避复用 30s/2min/10min；API 失败重建任务，ADB 失败复用 msgid 只重试导航；template_created 后进程崩溃，重启只补 ADB。
10. **幂等与补发**：`UNIQUE(task_id, planned_date)` 占位，重启/重放不二次建任务；planned_time 后 4h（`p4.makeup-window-hours`）内开机补发，超窗一次性任务标 failed_missed、周期任务不补；暂停期到期由 P5 停用开关调 `TaskStore.cancelPendingRuns` 写 cancelled_paused（已建 API 任务的 template_created 行不作废，仍需确认）。
11. **调度线程**：TaskScheduler.tick() 挂在 QueueConsumer 消费循环（内部 30s 节流），不另起线程；P3 @回复天然优先（队列先消费）。
12. **文本约束**：text.content ≤ 4000 字节（UTF-8），超长/空内容/空目标群判 fatal 直接 failed_final，不浪费重试。
13. **审计**：点击前截图 `data/screenshots/p4_{runId}_{attempt}_{ts}.png`（dry-run 同样截，证据=按钮就绪但未点），随 P2 的 30 天清理策略自动过期。
14. **P6 并入第二刀**：图片/视频/文件不再做 ADB 相册流程，第二刀改走素材管理上传得 media_id（3 天有效）+ attachments（最多 9 个）；第一刀仅文本。
15. **发送后清理历史卡片**（ADR-0011）：点发送（或 dry-run 跳过）后、离开群发助手前，长按最新卡片 → 点「删除」→ 点确认浮层「删除消息」。dry-run 和真发都删；失败只 WARN + alert_throttle(`task_cleanup_failed`)，不翻转 run 状态。无配置开关，默认执行。

### 交付物（第一刀：仅文本）

- [Db.java](../bot/src/com/mumu/bot/store/Db.java)：scheduled_task 加 `sender_userid/api_msgid`；task_run 加 `state/api_attempts/adb_attempts/next_attempt_at/msgid/last_error/fired_at`（ALTER 平滑升级老库）
- [Config.java](../bot/src/com/mumu/bot/config/Config.java) + config.properties：`p4.dry-run`（默认 true）、`test.target-roomid`、`p4.makeup-window-hours=4`、P4_TICK_INTERVAL_MS=30s、P4_MAX_ATTEMPTS=3
- [WeChatUi.java](../bot/src/com/mumu/bot/outbound/adb/WeChatUi.java)：`SESSION_LOCK` + `enterQunFaZhuShou / listQunFaCardsLatestFirst / openPendingTaskAndVerify / findSendButton / tapSendButtonDryRunAware / deleteLatestQunFaCard / leaveQunFaToMessageList`
- [Adb.java](../bot/src/com/mumu/bot/outbound/adb/Adb.java)：新增 `longPress(bounds, ms)`（同点 swipe 触发长按菜单）
- [ContactApiClient.java](../bot/src/com/mumu/bot/contact/ContactApiClient.java)：暴露 `addMsgTemplate`
- `outbound/api/ApiMsgSender.java`：createTemplate（建任务+校验）+ confirmByAdbClick（持锁四层导航+截图+点击/跳过+清理历史卡片）
- `schedule/ScheduledTask.java`、`schedule/TaskRun.java`（状态常量）、`schedule/TaskStore.java`、`schedule/TaskScheduler.java`（tick 状态机）
- [QueueConsumer.java](../bot/src/com/mumu/bot/outbound/QueueConsumer.java)：tickHook 构造参数；[AdbSender.java](../bot/src/com/mumu/bot/outbound/adb/AdbSender.java) send 包 SESSION_LOCK；[Main.java](../bot/src/com/mumu/bot/Main.java) 装配
- `schedule/P4Test.java` + `run_p4_test.bat`：端到端验收（真 API + 真机 ADB dry-run）
- [ADR-0008](adr/0008-scheduled-promo-via-group-msg-api.md) 新建；ADR-0001 加适用范围备注；[ADR-0011](adr/0011-p4-cleanup-qunfa-history.md) 发送后清理历史卡片

### 验收方法与成功定义

| # | 验收动作 | 成功定义 |
|---|---|---|
| 1 | 保持 `p4.dry-run=true`，运行 run_p4_test.bat | API 返回 msgid；ADB 自动进入群发助手→最新卡片→详情页；日志显示内容校验通过、发送按钮 rid=cre 定位成功；task_run.state=dry_run_verified；截图落盘；企微端任务保持「待发送」未发出（不占配额） |
| 2 | 对同一任务再次触发（fireNow/重启 tick） | 幂等跳过：不二次建任务、不二次导航，state/attempts/msgid 不变 |
| 3 | 真机制造 ADB 失败（如手动停在非企微页面前台）后恢复 | failed_no_entry 后按 30s/2min/10min 重试，复用同一 msgid；3 次失败转 failed_final 并打 WARN（P7 接邮件）；恢复导航后成功 |
| 4 | 断网/错 contact.secret 时到期 | failed_api 退避重试 3 次；恢复网络后下一轮重建任务成功；task_run 记录每次 last_error |
| 5 | 配置 once 任务 planned_time 早于当前 5h（超补发窗口）启动 | 一次性 run=failed_missed；同条件 daily 任务不补、等明天 |
| 6 | `test.target-roomid` 指向 room_map 外或任务 target_rooms 不含的群 | 交集为空 → failed_final「无有效目标群」，不调 API |
| 7 | 业务确认后 `p4.dry-run=false` 跑一次真实发送（当天唯一一次） | state=send_clicked；Agent测试群1群实际收到消息；群发助手该卡片变「已发送」 |
| 8 | P3 @回复与 P4 触发同时发生 | SESSION_LOCK 串行，两条导航不交叉，不错群、不误入群发助手 |

---

## P4 第二刀：媒体附件（2026-09-16 锁定）

> 原 P6 批次（图片/视频/文件 ADB 相册流程）并入本刀，但**改走企业群发 API 素材管理**：`media/upload` 换 media_id + `add_msg_template` 的 `attachments` 数组。详见 [ADR-0009](../adr/0009-scheduled-promo-media-attachments.md)。第一刀状态机、隔离、dry-run、幂等全部沿用，本刀只在 `createTemplate` 入口前置一步上传 + 一个 attachments 字段。

### 已锁定设计决策（设计拷问结论）

1. **单附件而非多附件**：复用 `scheduled_task.media_path` 单数列（第一刀已预留）。API 支持最多 9 个附件，但本店业务与 P5 管理端 UI 都只允许 1 个；P5 前端只给 1 个上传控件，杜绝多附件。
2. **源文件位置 `data/media/`**：相对路径（与 `data/kb/`、`data/ai_prompt.txt` 同级），run.bat 在 `bot/` 下运行时相对路径稳定命中；打包给客户时整个 `bot/` 目录交付，路径不变。
3. **`msg_type` 保持 `text` 不动**：用 `media_path` 非空作为「带附件」信号；文件类型由扩展名推断。向后兼容老任务。
4. **文本可选，纯图任务允许**：`content` 与 `media_path` 不能同时为空；二者有其一即可。纯图宣传（新品图无文案）是合理业务场景。
5. **media_id 不缓存，每次触发重传**：每日 1-3 任务 × 1-3 群，单次上传成本可忽略；缓存表会引入过期判断、跨进程一致性等复杂度，违反最小实现原则。
6. **multipart 加在 `ContactApiClient.uploadMedia`**：复用既有 access_token 缓存（与 `add_msg_template` 同源 token）；JDK `HttpClient` 原生拼 `multipart/form-data` body，**零新 jar**。
7. **上传前硬校验**（不通过直接 fatal，不浪费重试）：
   - image = `.jpg/.jpeg/.png`（≤10MB）
   - video = `.mp4`（≤10MB）
   - 其余扩展名一律当 file（≤20MB，API 不限制 file 扩展名）
   - **音频扩展名**（`.amr/.mp3/.m4a/.aac/.wav/.flac`）显式 fatal：「add_msg_template 不支持语音附件」
8. **`media/upload` 失败复用 `failed_api` + `api_attempts`**：与 `add_msg_template` 失败同列重试 30s/2min/10min，3 次后转 `failed_final`。不新增 failed_upload 状态、不新增 upload_attempts 列。
9. **dry-run 时真上传**：与第一刀「真建任务 + 真导航 + 不点发送」语义一致；不占客户群接收配额，仅占企业临时素材存储（3 天自动过期）。
10. **纯图任务 ADB 详情页校验跳过 `epd` 内容比对**：`expectedText=null` 时只校验「待发送」状态（`rid=epi`=待发送）+ 发送按钮 `rid=cre` 可见；靠「每群每天 1 条」业务约束兜底；若真机演练发现误点历史卡片再升级到找图片节点 rid。
11. **附件类型范围**：仅 image / video / file；link / miniprogram 不在本刀（需手填 url/appid/page 字段，属另一类「卡片任务」）。

### 交付物（第二刀）

- [ContactApiClient.java](../bot/src/com/mumu/bot/contact/ContactApiClient.java)：新增 `uploadMedia(File file, String type)` 方法（multipart/form-data，零新 jar）
- [ApiMsgSender.java](../bot/src/com/mumu/bot/outbound/api/ApiMsgSender.java)：`createTemplate` 加 `mediaPath` 参数；内部串「校验 → 上传 → 构造 attachments → 调 addMsgTemplate」
- [WeChatUi.java](../bot/src/com/mumu/bot/outbound/adb/WeChatUi.java)：`openPendingTaskAndVerify` 支持 `expectedText=null`（纯图跳过 epd 比对，只校验待发送 + 按钮）
- [TaskScheduler.java](../bot/src/com/mumu/bot/schedule/TaskScheduler.java)：tick 透传 `task.media_path` 给 `ApiMsgSender.createTemplate`
- [P4Test.java](../bot/src/com/mumu/bot/schedule/P4Test.java)：加 7 个第二刀场景；main 方法第一参数 `text` / `media` 切换场景集
- [run_p4_test.bat](../bot/run_p4_test.bat)：改为合并入口 `run_p4_test.bat [text|media]`，默认 text
- 新建 `data/media/` 目录 + 测试素材（`test.jpg` / `test.pdf`，由业务方准备；P4Test 启动时校验存在并打印路径）
- [ADR-0009](../adr/0009-scheduled-promo-media-attachments.md) 新建；ADR-0008 末尾加指向
- [CONTEXT.md](../../CONTEXT.md) 加 4 条词条（定时宣传任务、媒体附件、媒体 ID、群发助手）

### 验收方法与成功定义（第二刀）

| # | 验收动作 | 成功定义 |
|---|---|---|
| 1 | 准备 `data/media/test.jpg` + 一段文案，`p4.dry-run=true`，运行 `run_p4_test.bat media` | `media/upload` 返回 media_id；`add_msg_template` 返回 msgid；ADB 自动进入群发助手→最新卡片→详情页；日志显示内容校验通过、发送按钮 `rid=cre` 定位成功；task_run.state=dry_run_verified；截图落盘；企微端任务保持「待发送」未发出 |
| 2 | 纯图任务（`content` 空、`media_path=data/media/test.jpg`），dry-run 跑 | 跳过 `epd` 内容比对，只校验「待发送」+ 发送按钮；state=dry_run_verified；详情页能看到图片预览 |
| 3 | `media_path=data/media/test.pdf` + 文案，dry-run 跑 | file 类型上传成功（≤20MB）、attachments 入队、state=dry_run_verified |
| 4 | `media_path=data/media/nonexistent.jpg` | fatal「媒体文件不存在: data/media/nonexistent.jpg」；state=failed_final；不调 `media/upload`、不调 `add_msg_template` |
| 5 | `media_path=data/media/voice.amr`（伪造音频文件） | fatal「add_msg_template 不支持语音附件，请改用文本或图片」；state=failed_final；不调 API |
| 6 | 伪造 >10MB 图（如复制现有 jpg 拼接到 11MB） | fatal「图片 X MB 超过 10MB 上限」；state=failed_final；不调 API |
| 7 | 业务确认后 `p4.dry-run=false` 跑一次真实带图任务（当天唯一一次） | state=send_clicked；群里实际收到「文案 + 图片」；群发助手该卡片变「已发送」 |

---

## P5 管理端（2026-09-16 锁定）

### 已锁定设计决策（设计拷问结论）

1. **HTTP 框架反转**（见 ADR-0004 修订）：放弃 Javalin/Jetty，改用 JDK 内嵌 `com.sun.net.httpserver.HttpServer`，零新 jar。理由：仅 2 页面 + JSON API，Javalin 5.x 引入 Jetty 11 全家桶（约 8 个 jar）与零构建手工管理原则冲突。
2. **认证**：无登录无权限，绑 `127.0.0.1:9173`（`admin.host` / `admin.port` 配置）。端口被占直接启动失败退出，不动态换端口。一旦客户机部署长期运行，端口绑定本身做访问边界。
3. **启动时机**：`Db.conn()` 后立即起 AdminServer，早于 SDK/ADB 初始化。ADB/SDK 失败不影响管理端已启动；handler 内部全 try/catch，请求线程异常绝不拖垮进程。
4. **本轮交付范围**：定时任务管理页 + 知识库管理页 + 追加群聊入口。砍掉原 7 页中的日志/机器人消息/队列状态/名册/群映射展示（落文件或 DB 工具直查）。
5. **任务删除**：硬删 `scheduled_task` + 级联 `DELETE FROM task_run WHERE task_id=?`，UI 二次确认。不软删、不保留审计。
6. **立即触发**：按钮调 `TaskStore.createRunIfAbsent(taskId, today)` + 把该 run 的 `next_attempt_at=null`，下个 30s tick 推它。已存在 run 返回"今日已触发"。
7. **rhythm 控件形态**：下拉 once/daily/weekly；planned_time 时分滚轮（上下滑动选择，非手输）；weekdays 周一到周日 7 个 checkbox（weekly 显示）；once 触发时刻 = 年/月/日/时/分 5 滚轮（年范围今年~明年）。所有输入控件均选择化，不接受手输。
8. **target_rooms 控件**：checkbox 组，每行**仅显示群名**（不显示 enabled/disabled/roomid）；disabled 群不显示；提供「追加群聊」入口（room_map 插入 roomid+name）。
9. **冲突判定严格度**：严格按"同一天同群只允许 1 条任务会触发"判定，与时间点无关。daily×daily 必冲突；daily×weekly 在 weekly 选中日冲突；daily×once 在 once 当天冲突；weekly×weekly 仅 weekdays 交集日冲突；once×once 仅同日期冲突。编辑时排除自身；冲突时表单红字"与任务《XXX》在 X 月 X 日冲突，请先删除该任务再提交"，HTTP 400 拒绝。
10. **同群当天配额运行时硬判定**（见 ADR-0010）：表单校验放行后仍可能在运行时撞配额（先建后删导致当天该群已有 send_clicked）。`TaskScheduler.tick` 触发前调 `TaskStore.hasRoomSentToday(roomid, today)`，有则不创建新 run + WARN + 写 `alert_throttle(event_type='task_run_skipped_sent_today')`（P7 邮件接）。`dry_run_verified` 不算占用配额，`cancelled_paused` 不算。
11. **失败重试反转**（见 ADR-0010）：task_run 退避从 30s/2min/10min 改为 5min/30min/60min（`p4.retry-backoff-ms` 配置）；api_attempts / adb_attempts 各 3 次独立保留。**不做手动重试按钮**：失败走自动退避；failed_final / failed_missed 写 alert_throttle 埋点，P7 接邮件。P2 出站队列保持 30s/2min/10min 不动。
12. **媒体上传交互**：任务表单内嵌文件选择按钮（系统文件对话框也是选择）；PUT 到 `data/media/`，409 探测同名 + `?overwrite=1` 二次确认；后端硬校验 jpg/jpeg/png ≤10MB、mp4 ≤10MB、其他文件 ≤20MB、音频拒绝（fatal）。任务硬删除时媒体文件不自动删（避免误删被其他任务引用）。
13. **知识库上传交互**：文件对话框选 `.docx/.xlsx/.txt/.md`；同名弹窗覆盖确认（409 + overwrite=1）；`.doc` 文件弹窗提示转存 `.docx`；上传后自动触发 `KbSyncService.sync` + 展示 `Result.lines` 明细（含跳过原因如"超过 10 万字上限""解析失败保留旧行"）。**不做内容预览/在线编辑**。
14. **按天滚动日志**（自写 `util/DailyLogRotator`）：`logs/bot-yyyy-MM-dd.log`，按天切割，保留 14 天（`log.retention-days` 配置）。System.out/err 重定向到文件 + 同时输出控制台。P7 看门狗崩溃重启后续写当天文件。零新 jar。
15. **时区**：`run.bat` 加 `-Duser.timezone=Asia/Shanghai` 硬编码锁定，避免客户机时区配置异常导致 planned_date 跨日误判。
16. **并发**：沿用 `Db.LOCK` 全局串行写；读不进锁（SQLite WAL 模式读可并发）。HttpServer 默认线程池（JDK 自管），写操作全部进 synchronized 块。
17. **编辑功能**：复用新建表单弹窗（原生 `<dialog>` 元素），预填值；保存后重跑冲突校验排除自身。编辑只影响未来触发（今天未发送的 pending run 因 JOIN 实时取任务定义也会用新值，接受此行为）。
18. **运行历史**：每任务卡片下内嵌"最近 20 条 task_run"（日期/状态/尝试次数/错误信息/时间），`ORDER BY id DESC LIMIT 20`，不做分页。

### 交付物

- `bot/src/com/mumu/bot/admin/AdminServer.java`：HttpServer 启动 + 路由分发 + 生命周期
- `bot/src/com/mumu/bot/admin/AdminHandler.java`：各路由 handler 集中类（tasks / rooms / kb / media）
- `bot/src/com/mumu/bot/admin/TemplateRenderer.java`：7 段原生 HTML 模板（任务列表/编辑弹窗/知识库列表/上传弹窗/历史/冲突红字/媒体选择）
- `bot/src/com/mumu/bot/admin/MediaUploadParser.java`：multipart/form-data 原生解析（零新 jar）
- `bot/src/com/mumu/bot/util/DailyLogRotator.java`：按天滚动日志 + 保留 14 天
- [Config.java](../bot/src/com/mumu/bot/config/Config.java) + config.properties：新增 `admin.host`/`admin.port`/`log.dir`/`log.retention-days`/`p4.retry-backoff-ms`/`p4.media.max-image-mb`/`p4.media.max-video-mb`/`p4.media.max-file-mb` 共 8 字段
- [Main.java](../bot/src/com/mumu/bot/Main.java)：Db 初始化后立即起 AdminServer（在 ADB/SDK 之前）
- [TaskStore.java](../bot/src/com/mumu/bot/schedule/TaskStore.java)：新增 `listAllTasks` / `updateTask` / `deleteTask` / `hasRoomSentToday` / `listRunsByTask` 方法
- [RoomMap.java](../bot/src/com/mumu/bot/store/RoomMap.java)：新增 `insert(roomid, name)` 方法
- [TaskScheduler.java](../bot/src/com/mumu/bot/schedule/TaskScheduler.java)：退避常量改读 `Config.P4_RETRY_BACKOFF_MS`；tick 内加 `hasRoomSentToday` 兜底
- [ApiMsgSender.java](../bot/src/com/mumu/bot/outbound/api/ApiMsgSender.java)：媒体上限从硬编码改读 `Config.P4_MEDIA_MAX_*_MB`
- [run.bat](../bot/run.bat)：加 `-Duser.timezone=Asia/Shanghai`
- [ADR-0010](../adr/0010-p4-backoff-and-per-room-quota.md) 新建；ADR-0004 加修订段；ADR-0008 第 9/10 条更新；[CONTEXT.md](../../CONTEXT.md) 加「管理端」「按天滚动日志」「同群当天配额」「失败终态告警」4 词条

### 验收方法与成功定义

| # | 验收动作 | 成功定义 |
|---|---|---|
| 1 | 启动机器人，浏览器访问 `http://127.0.0.1:9173/` | 重定向到 /tasks，看到任务列表（即使没有任务显示空态）；ADB 未连不影响管理端打开 |
| 2 | 在管理端新建一个 daily 任务，目标群选 1 个，planned_time 设为 5 分钟后 | 列表出现该任务；到点后 task_run 自动推进；dry-run 模式下 state=dry_run_verified |
| 3 | 在管理端再建一个同群同天的 daily 任务 | 表单红字提示"与任务《XXX》冲突"，HTTP 400 拒绝提交 |
| 4 | 把第 2 步的任务硬删除，再建一个同群同天 daily 任务 | 表单校验放行（无冲突）；但运行时 tick 触发前检测到当天该群已 send_clicked（来自旧任务的 run 未级联删除前的痕迹——若已级联删除则放行）→ 视情况跳过或继续，行为符合 ADR-0010 描述 |
| 5 | 在管理端编辑任务（改文案、改 planned_time、改目标群） | 保存后列表更新；当天 pending run 因 JOIN 取新值；冲突校验排除自身放行 |
| 6 | 在管理端点「立即触发一次」 | `createRunIfAbsent(today)` 创建 run；下个 tick 推进；已存在 run 返回"今日已触发" |
| 7 | 在管理端上传 `data/kb/test.docx` | 列表出现该文档 + 字数 + 更新时间；总字数进度条更新；sync 结果展示"新增 X 字" |
| 8 | 在管理端上传同名 `test.docx` | 弹窗"已存在同名文件，是否覆盖？"；点确认后覆盖 + sync 显示"更新" |
| 9 | 在管理端上传 `test.doc` | 弹窗"请将 .doc 文件转存为 .docx 后再上传" |
| 10 | 在管理端删除 `test.docx` | 二次确认后 data/kb/test.docx 文件删除 + sync 显示"删除 X"；列表更新 |
| 11 | 在管理端「追加群聊」输入 roomid+name | room_map 多一行；任务表单 target_rooms checkbox 多一项 |
| 12 | 在管理端新建带图任务（选 jpg 附件） | 上传到 data/media/；任务列表显示带附件标记；dry-run 触发后 task_run 推进 |
| 13 | 杀进程后重启 | logs/ 目录下当天日志文件续写；管理端重新可访问；当天未完成的 pending run 继续推进 |
| 14 | 制造连续失败（如断网） | api_attempts 按 5min/30min/60min 推进；3 次后 failed_final + 写 alert_throttle + WARN 日志 |

---

## P7 告警 + 看门狗（2026-09-16 锁定）

### 已锁定设计决策（设计拷问结论）

1. **6 类邮件告警（其余仅埋点+WARN）**：`circuit_breaker`（绕过节流）、`heartbeat_lost`、`sdk_pull_failed`（10 次）、`adb_failed`（5 次）、`outbound_failed_final`、`task_cleanup_failed`。非邮件埋点类：`task_failed_final` / `task_failed_missed` / `task_run_skipped_sent_today` / `ai_failed`。
2. **节流 30 分钟**：`alert_throttle.last_sent_at`，`shouldSend` 命中时不刷新时间戳；`circuit_breaker` 绕过。watchdog 侧用 `data/watchdog_throttle.json`。
3. **SMTP 选型**：`com.sun.mail:jakarta.mail:2.0.1` 单 jar（~689KB），163 邮箱 `smtp.163.com:465` SSL + 授权码，自发自收。
4. **配置分层**：SMTP host/port/ssl/account/auth-code 在 `config.properties`（gitignore，不进 UI）；收件人在管理端 `/mail` 页面编辑，存 `data/mail.properties`，JVM 与 watchdog 发信时读取。
5. **熔断哨兵文件**：熔断时写 `data/circuit_breaker` + 发 `circuit_breaker` 告警 + `System.exit(1)`；watchdog 检测到哨兵 → 不重启、发告警、自身退出（等人工删哨兵+重启 watchdog）。退出码无法区分熔断/崩溃，故用哨兵。
6. **看门狗 watchdog.ps1**：每 30s 检测——哨兵优先（不重启）→ 心跳 mtime>80s 假死（kill+告警+重启）→ 进程消失崩溃（告警+重启）→ 崩溃环 10min/5次（停止+告警+退出）。重启 `bot/run-only.bat`（无编译无 pause）。
7. **心跳**：Main daemon 线程每 10s 写 `data/heartbeat`，80s 超时容忍 ADB 长导航/LLM 调用。
8. **启动脚本拆分**：`build.bat`（仅编译）+ `run-only.bat`（仅运行，透传 exit code）+ `run.bat`（人工用，编译+运行+pause）。

### 交付物

- `bot/lib/jakarta.mail-2.0.1.jar`（Maven Central 下载）
- `alert/MailAlerter.java`（jakarta.mail 发信 + 节流 + circuit_breaker 绕过 + 50 行日志尾部）
- `alert/AlertThrottle.java`（新增 `shouldSend` 节流判断）
- `alert/CircuitBreaker.java`（哨兵文件 + 发信）
- `archive/ArchiveClient.java`（sdk_pull_failed 连续 10 次计数，fetchMaxSeq 失败返回 -1）
- `outbound/adb/Adb.java`（adb_failed 连续 5 次计数）
- `outbound/OutboundQueue.java`（markFailed failed → outbound_failed_final 告警）
- `outbound/api/ApiMsgSender.java`（task_cleanup_failed → MailAlerter.alert）
- `ai/AiService.java`（ai_failed 埋点，不发邮件）
- `outbound/adb/WeChatUi.java`（checkRisk → CircuitBreaker.trip）
- `Main.java`（全局 Error 熔断 + DB 失败熔断 + 心跳线程）
- `watchdog/watchdog.ps1`（重写：哨兵/心跳/崩溃环/Send-MailMessage）
- `bot/build.bat` + `bot/run-only.bat`（拆分），`run.bat` 改为调用两者
- `bot/config.properties` + `Config.java`：新增 `mail.enabled/smtp.host/smtp.port/smtp.ssl/smtp.account/smtp.auth-code/recipient`
- 管理端 `/mail` 页 + `/api/mail` GET/POST（收件人配置）
- [ADR-0012](../adr/0012-email-alert-and-watchdog.md)；[CONTEXT.md](../../CONTEXT.md) 加「邮件告警」「看门狗」「心跳」词条，更新「熔断」「失败终态告警」

### 验收方法与成功定义

| # | 验收动作 | 成功定义 |
|---|---|---|
| 1 | 配置 `mail.smtp.account`/`auth-code` 为 163 邮箱授权码，管理端 `/mail` 设收件人 | 页面保存成功，`data/mail.properties` 写入 recipient |
| 2 | 手动制造 `outbound_failed_final`（断网让 @回复重试耗尽） | 30 分钟内收到 1 封告警邮件；同事件 30 分钟内不重复发 |
| 3 | 断网让 SDK 拉取连续失败 ≥10 次 | 收到 `sdk_pull_failed` 告警邮件 |
| 4 | 关闭 ADB 设备（模拟器断连）让 shell 连续失败 ≥5 次 | 收到 `adb_failed` 告警邮件 |
| 5 | 杀进程（TaskKill java） | watchdog 检测到进程消失 → 发 `heartbeat_lost` 告警 → 自动重启机器人 |
| 6 | 阻塞主线程 >80s（模拟假死）或删 `data/heartbeat` | watchdog 检测心跳超时 → kill + 告警 + 重启 |
| 7 | 制造熔断（往企微界面注入风控关键词，或删 DB 文件） | 写 `data/circuit_breaker` → 发 `circuit_breaker` 告警 → 进程 exit(1)；watchdog 看到哨兵不重启、自身退出 |
| 8 | 崩溃环：10 分钟内反复杀进程 ≥5 次 | watchdog 第 5 次后停止重启、发 `circuit_breaker` 告警、自身退出 |
| 9 | 管理端 `/mail` 改收件人后触发告警 | 告警发到新收件人，无需重启 JVM/watchdog |


