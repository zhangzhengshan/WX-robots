# 企微客户联系 API 改走 ECS SOCKS 隧道出口

2026-09-24 设计拷问锁定。当前 `ContactApiClient`（gettoken / groupchat/get / add_msg_template / media/upload）用 JDK `HttpClient` 直连 `qyapi.weixin.qq.com`，本地公网动态出口 IP 未加入企微可信 IP 白名单，连续踩 60020（非法 IP）：名册同步失败导致 @错名字事故（`member_roster.display_name` 残留旧值），P4 群发 API 同样 60020。本 ADR 把整个客户端的出站链路切换到已有 ECS 出口。

ECS 静态 IP（`map.orbai.cn` 的出口）加一次白名单即可永久解决；现有 watchdog 已用 plink 从 ECS 拉了一条 SOCKS5 动态代理隧道到本地 `127.0.0.1:1080`（ADR-0014），本变更复用同一端口、同一隧道，零新基建。

## Decision

### 1. 范围：ContactApiClient 整体走 ECS

P4 群发（`add_msg_template` + `media/upload`）与名册同步（`gettoken` + `groupchat/get`）共用同一个 corpid 的 IP 白名单校验，拆一半只解决一半。整个 `ContactApiClient` 的所有对外请求都走 ECS 出口；不拆分、不特判。

### 2. 实现：重写 ContactApiClient 为 HttpURLConnection + SOCKS

JDK `HttpClient` 原生不支持 SOCKS 代理（只支持 HTTP CONNECT 代理），三条路：

- **原方案 A**（否决）：ECS 装 tinyproxy + plink 加 `-L` + HttpClient 配 ProxySelector。需要 ECS 侧运维、HTTP CONNECT 比 SOCKS 隧道多一层握手。
- **原方案 B**（否决）：ECS 部署转发微服务。新服务、鉴权、部署，代码量最大。
- **原方案 C**（选定）：`HttpURLConnection` 配合 `new Proxy(SOCKS, InetSocketAddress)` 构造 SOCKS 代理连接。`URL.openConnection(Proxy)` 是 JDK 原生 API，multipart/form-data 手动拼（现有代码已经手动拼好了，只是从 HttpClient.BodyPublishers 换成 HttpURLConnection 的 OutputStream）。改动只在 `ContactApiClient` 一个文件内，零新 jar。

SOCKS 5 握手由 JDK 底层负责（`sun.net.www.protocol.http.HttpURLConnection` + `java.net.SocksSocketImpl`），TLS 端到端（SOCKS 隧道内发送 ClientHello 到 qyapi），SNI 和证书校验与直连完全一致，中间人风险为零。

### 3. 配置：复用 `proxy` 键 + 新增 `wecom.via-ecs` 开关

SOCKS host:port 复用 `config.properties` 的 `proxy=socks5://127.0.0.1:1080`，在 `ContactApiClient` 内部解析 URI。单一真相源，避免两个键描述同一台隧道而漂移。

新增布尔开关 `wecom.via-ecs=true`（默认 true），Config static 块冻结：

- `true` → 构造 `Proxy.Type.SOCKS` 连 `proxy` 键解析出的 host:port
- `false` → `Proxy.NO_PROXY` 直连（应急回退，直连必然 60020，但可在隧道彻底挂掉时快速跳过该路径）

`Config.java` 增 `WECOM_VIA_ECS` 常量，Config 是 final 类、static 块只在启动时跑一次，**不支持运行期热切**。热切需要单独的可变字段或 Properties 重加载，且 fallback 到注定 60020 的直连是负收益——隧道故障应靠 watchdog 自愈（30s 内）+ P4 重试窗口（最长 60min）覆盖，不引入运行期自动 fallback 逻辑。

### 4. SOCKS 隧道故障时的降级

plink 隧道断了时 watchdog 每 30s 检测端口监听并自愈，最长恢复窗口约 80s（检测间隔 + plink 启动）。P4 重试退避 5min/30min/60min，最长等待 60min 足够覆盖隧道短暂不可用。不额外加运行期 fallback 到直连——直连必然 60020，fallback 只是把一个注定失败的路径提前暴露。

隧道长期断了（ECS 宕机 / SSH 密码改了 / host key 变了）→ SDK 拉取连续 10 次失败触发 `sdk_pull_failed` 邮件告警（已有机制），P4 API 连续 3 次失败进 task_run `failed_api` + 邮件告警（已有机制）。两层兜底保持不变。

### 5. watchdog / plink 零改动

现有 plink `-D 127.0.0.1:1080` SOCKS 动态代理按目标域名路由，SDK（企微服务器）与 ContactApiClient（qyapi.weixin.qq.com）目标不同，共用 1080 端口无冲突。watchdog 无需加 `-L` 端口转发、无需增新端口、无需改检测逻辑。

### 6. 具体实现要点（ContactApiClient.java）

- HttpClient → HttpURLConnection，代理由 `URL.openConnection(new Proxy(SOCKS, addr))` 注入，**不在 JVM 全局设置 `socksProxyHost`**——避免 AI 服务（云端 LLM，不走 ECS）意外被路由。
- `multipart/form-data`（`media/upload`）：从 `http.send(BodyPublishers.ofByteArray(body))` 换成 `conn.setDoOutput(true); conn.getOutputStream().write(body)`；Content-Type header 和 boundary 字符串保持不变。
- `accessToken` 缓存逻辑、`postWithTokenRetry` 的 40014/42001/41001 重试逻辑、超时设置（connect 10s / read 20s，media/upload read 60s）全部原样保留，只换 HTTP 栈。
- 新增私有方法 `parseProxyHostPort(String proxyUri)` 从 `socks5://host:port` 拆出 `InetSocketAddress`；`via-ecs=false` 时返回 `Proxy.NO_PROXY`。

## Alternatives Considered

- **HttpClient + tinyproxy HTTP CONNECT**：需要 ECS 装代理服务、plink 加 `-L`、HttpClient 配 ProxySelector。多一层运维负担，SOCKS 方案更直接。否决。
- **ECS 部署转发微服务**：新服务、鉴权、部署运维，代码量最大。否决。
- **运行期自动 fallback 到直连**：直连必然 60020，fallback 到注定失败的路径意义为负；且 Config final 常量需要绕开重加载。否决，降级靠启动时配置开关 + watchdog 自愈 + P4 重试。
- **单独配置键 `wecom.socks.host/port`**：解耦但多一处同步维护，可能与 `proxy` 键漂移。否决，复用现有键。
- **JVM 全局 `-DsocksProxyHost=127.0.0.1 -DsocksProxyPort=1080`**：影响全 JVM（包括 AI 服务、SDK、可能其他出站），AI 服务不该走 ECS 出口（云端 LLM 直接连更快）。否决，代理注入到单个连接。
- **plink 加 `-L` 本地端口转发给 HttpClient HTTP 代理**：需要 ECS 装 HTTP CONNECT 代理（tinyproxy），多一层。否决，SOCKS 一步到位。

## Consequences

- 优势：一次加 ECS 静态 IP 白名单，名册同步 + P4 群发同时解决 60020；复用现有 plink 隧道零新基建；SOCKS 方案 TTL 端到端安全；改动集中在一个文件（ContactApiClient.java）。
- 代价：JDK HttpClient → HttpURLConnection 重写，multipart 上传换写法（代码量不大）；Config final 类不支持运行期热切，改开关需重启 bot。
- 影响：`ContactApiClient.java`（HTTP 栈重写，代理注入）、`Config.java`（新增 `WECOM_VIA_ECS` + 解析逻辑）、`config.properties`（新增注释/键）；watchdog.ps1 / plink / ECS 侧零改动。
