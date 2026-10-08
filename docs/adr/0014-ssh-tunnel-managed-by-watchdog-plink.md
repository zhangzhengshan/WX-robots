# SSH 动态代理隧道由 watchdog 用 plink 托管

2026-09-17 设计拷问锁定。存档 SDK 的网络依赖 `proxy=socks5://127.0.0.1:1080`，此前靠人工在 cmd 窗口跑 `ssh -N -D 127.0.0.1:1080 root@map.orbai.cn`——机器重启、ssh 断线后需人工重开，SDK 拉取随之中断。本 ADR 把隧道的生命周期交给 watchdog 托管。

## Decision

### 1. 认证：plink.exe + `-pw` 明文密码

Windows 自带 OpenSSH 的 ssh.exe 无法从命令行传密码（只认交互输入或公钥），无人值守场景不可用。选 PuTTY 家族的 plink：单文件 exe、`-pw <密码>` 直接传参、`-batch` 拒绝一切交互提示（host key 未缓存时直接失败退出而不是卡死——这是特性，不在无人值守流程里盲信任意指纹）。密码明文放 `config.properties` 的 `ssh.proxy.password`（该文件已 gitignore）。

公钥认证（`-i keyfile`）更安全，被否决：需要人工生成密钥对、上传公钥、调试服务器端权限，配置链路长；单机单用途场景下用户明确选择「填个密码就能跑」。将来要切换时公钥方案在 plink 下只是换参数，无需改架构。

### 2. 托管位置：watchdog.ps1，每 30s 幂等检测

- 检测顺序：熔断哨兵 → **SSH 隧道** → MuMu → bot（隧道最先——SDK 拉取存档是主循环的输入，隧道断了后续一切检测照常但 bot 白跑）。
- 健康信号：`Get-NetTCPConnection -LocalPort <port> -State Listen`——端口有监听即视为健康。**幂等不抢占**：监听者是人工临时开的 ssh.exe 还是存活的 plink 不区分，一律跳过不拉。
- 本地端口从 `proxy` 键解析（`socks5://127.0.0.1:1080` → 1080），不另设配置键。
- 端口无监听 → `Start-Process plink -batch -N -D 127.0.0.1:<port> -P <ssh端口> -pw <密码> <user>@<host> -WindowStyle Hidden`（`-N` 纯转发不开 shell，窗口完全隐藏）。
- 拉起前先 `Stop-Process` 清残留 plink（端口已没监听但进程还挂住的僵尸场景，防堆积；全机只有本 watchdog 使用 plink，误杀面为零）。

### 3. 自愈策略：静默 + 留痕 + 退避（对齐 ADR-0013 原则）

- **断线重拉不发邮件**：隧道断了 watchdog 能自动恢复，可自愈故障不该打扰用户；最终兜底是既有 `sdk_pull_failed`（连续 10 次拉取失败，30 min 节流）——隧道长期断了它必然触发，邮件告警表保持 6 类不变。
- **留痕**：断线 / 恢复 / 退避各记一行到 `data/tunnel_events.log`（与 `mumu_events.log` 同模式，控制台关窗即失，留痕供「昨晚断过几次」排查）。
- **防抖退避**：连续拉起失败 ≥5 次（密码错 / 服务器不通 / host key 未引导时 plink 秒退）→ 退避 5 分钟再试，避免 30s 一次的无限刷屏与日志噪音。

### 4. host key 引导：一次性人工命令

`-batch` 遇到未缓存的服务器指纹直接失败。首次部署需人工跑一次（会答 y 缓存指纹、输一次密码）：

    echo y | <项目>\watchdog\bin\plink.exe -ssh root@map.orbai.cn "exit"

之后 plink 全程无人值守。换服务器或服务器重装（指纹变化）时重复一次此步骤。

### 5. 配置键

`config.properties` 新增（host 留空 = 整个隧道功能关闭，回到人工开隧道模式）：

    ssh.proxy.host=map.orbai.cn
    ssh.proxy.port=22
    ssh.proxy.user=root
    ssh.proxy.password=          # 用户手填（占位提交），文件已 gitignore

## Alternatives Considered

- **Windows OpenSSH ssh.exe**：无法命令行传密码，无人值守不可行——正是本次要解决的问题。否决。
- **公钥认证（ssh-keygen + authorized_keys）**：安全性更好，但配置链路长，用户明确拒绝。否决，见决策 1 的将来切换路径。
- **watchdog 强杀 1080 上的旧监听者再拉 plink**：会杀掉人工临时开的 ssh.exe，违背不抢占约定。否决，幂等跳过。
- **隧道断了发邮件**：可自动恢复的故障不该打扰用户（ADR-0013 同一原则），且 `sdk_pull_failed` 已兜底。否决。
- **Java 侧（bot 进程内）拉隧道**：JVM 崩溃/重启会连带丢隧道，Runtime.exec 管理子进程生命周期繁琐；watchdog 本就是进程托管方，职责对口。否决。
- **TCP 连接探测替代 LISTEN 状态检测**：连一次 SOCKS 端口不比 LISTEN 多证明什么，反而给隧道制造连接噪音。否决。

## Consequences

- 优势：机器重启 / 断线后隧道全自动恢复，SDK 拉取不再依赖人工开 cmd；静默自愈 + 留痕对齐告警信噪比原则；幂等不抢占人工隧道；邮件告警表维持 6 类。
- 代价：SSH 密码明文存于 config.properties 且出现在 plink 进程命令行参数中（gitignore + 单用户个人机可接受）；新增 vendored 二进制 `watchdog/bin/plink.exe`（约 1 MB）；host key 首次 / 换服务器需一次人工引导。
- 影响：`watchdog.ps1`（检测顺序插入隧道分支 + `Get-TunnelAlive` / `Start-Tunnel` / `Write-TunnelEvent` + 退避状态变量）；`config.properties` 4 个 `ssh.proxy.*` 键；CONTEXT.md 看门狗词条补第 7 项职责、新增 SSH 隧道词条。无 Java 改动。
