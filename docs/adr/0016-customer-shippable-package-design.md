# 客户可分发打包设计

2026-09-24 设计拷问锁定。目标：把项目打包成单个 zip，客户解压后安装 MuMu Player 12，双击 `start.vbs` 即可——前端（AdminServer）自动打开浏览器，看门狗（watchdog.ps1）与 bot 后台启动，全程无需客户配置。

## Assumptions（前提假设，偏离即需重做本 ADR）

1. **单客户预填密钥**：包内 `bot/config.properties` 与 `docs/pem/private_key.pem` 均为我方企业的密钥，客户以"操作员"身份登录我方企微 corp（`wwe3ff74260674563c`），非客户自有 corp。若转向多客户 / 客户自有 corp，密钥收集与分发链路需重新设计。
2. **客户机已装或愿装 MuMu Player 12**：MuMu 为第三方 ~1GB 模拟器，不随包分发（许可证与体积风险）。客户自行安装，README 指引默认安装路径。
3. **首次扫码一次**：客户机上的 MuMu 首次无企微登录态，需人工扫码登录一次（企微安全边界，不可消除）。
4. **P4 出厂真实发送**：`p4.dry-run=false`，客户群可收到定时宣传；前端可随时切回 dry-run。

## Decision

### 1. 捆绑便携 JRE 17，零系统 Java 依赖

- 将 Adoptium Temurin 17 JRE 解压到包根 `runtime/jre/`（约 180 MB）。
- `bot/run-only.bat`、`bot/build.bat` 的 `JAVA_HOME` 改为 `%~dp0..\runtime\jre`（相对路径），不再硬编码 `F:\plug-in\jdk17\...`。
- 不修改系统环境变量，不要求 `JAVA_HOME` 已设。
- 否决 jlink 裁剪（维护成本高，每加一个依赖要重跑 jlink）；否决假设客户已有 JDK（99% 客户没有）。

### 2. MuMu 自动检测：launcher 探测 + Admin UI 兜底

`launcher.ps1` 启动时按以下顺序探测，结果写入 `bot/data/runtime.properties`（与 bot 侧 `Config.reload()` 覆盖层一致）：

**adb.path 探测**：
1. 读注册表 `HKLM\SOFTWARE\WOW6432Node\Netease\MuMu12` 的 `InstallPath`；
2. 未命中则扫描常见安装目录：`C:\Program Files\Netease\MuMu Player 12\`、`C:\Program Files (x86)\Netease\MuMu Player 12\`、`D:\Program Files\Netease\MuMu Player 12\`；
3. 未命中则 `PATH` 中查找 `adb.exe`；
4. 全部未命中 → 留空 `adb.path`，流程继续，由 Admin UI `/config` 页面让客户手填（已有文件存在校验与保存后 `Config.reload()`）。

**adb.device 端口探测**：
1. 若找到 `MuMuManager.exe`（从 `adb.path` 同目录推导），执行 `MuMuManager.exe info -v all`，解析 JSON 取第一个运行中实例的 `adb_path` / 端口；
2. 否则暴力 TCP 探测端口序列 `16384, 16416, 16448, ...16704`（实例 0–10，步进 32），首个 `Socket.connect(500ms)` 命中即停；
3. 全部未命中 → 回退 `config.properties` 出厂默认 `127.0.0.1:16384`（实例 0，客户新装 MuMu 的默认实例）。

bot 侧 `Config.detectDevice()` 仍保留并在 `Main` 启动时执行（TCP 探测，无需 adb.path），与 launcher 写入值取并集：launcher 负责把 adb.path + 端口写进 runtime.properties，detectDevice 仅在 bot 启动时再确认一次端口（运行中 MuMu 实例可能已变）。

### 3. 启动序列（launcher.ps1）

1. **JRE 校验**：`runtime/jre/bin/java.exe` 不存在 → 弹可见错误框并退出（无法运行 Java）。
2. **MuMu/ADB 检测**：如上节，写 `bot/data/runtime.properties`。
3. **打开浏览器**：`Start-Process http://127.0.0.1:9173/`（每次启动都打开，让客户看到管理面板）。
4. **启动 watchdog**：`Start-Process powershell -ArgumentList "-ExecutionPolicy Bypass -File watchdog\watchdog.ps1" -WindowStyle Hidden`。

AdminServer 在 `Main.java` 中早于 SDK/ADB 启动，故即使 adb.path 为空，管理端仍可访问——客户在 `/config` 页面填 adb.path 并勾选"重启"，watchdog 拉起重试。

### 4. 进程可见性：隐藏启动，stop.bat 停止

- 客户日常双击 `start.vbs`（`wscript` 调用 `launcher.ps1`，`WindowStyle Hidden`），无控制台窗口。
- 提供 `stop.bat`：`taskkill /F /IM java.exe` + 终止含 `watchdog.ps1` 的 powershell 进程。无代码改动，零依赖。
- 不做 AdminServer `/shutdown` 端点（后续可加，非本次范围）。

### 5. 包结构与排除清单

发布目录（zip 解压后）：

```
<CustomerName>-Robot/
├── runtime/jre/                     ← 捆绑 JRE 17
├── java_sdk/WeWorkFinanceSdk/       ← SDK（DLL + Finance.class + json jar）
├── docs/pem/private_key.pem         ← 我方 RSA 私钥（操作员模式）
├── bot/
│   ├── run-only.bat                 ← JAVA_HOME 改相对路径
│   ├── build.bat                    ← 同上（客户不运行，保留供排障）
│   ├── config.properties            ← 预填密钥，adb.device=127.0.0.1:16384，p4.dry-run=false
│   ├── data/                        ← 全新（无本地 DB/截图/心跳）
│   │   ├── kb/                      ← 知识库文档
│   │   ├── ai_prompt.txt            ← 提示词模板
│   │   └── media/                   ← 空目录
│   ├── lib/*                        ← 所有依赖 jar
│   └── out/*                        ← 编译产物（.class）
├── watchdog/
│   ├── watchdog.ps1                 ← 路径解析不变（已用 $PSScriptRoot）
│   └── bin/plink.exe                ← vendored plink
├── launcher.ps1                     ← 新增：检测 + 启动编排
├── start.vbs                        ← 新增：隐藏启动入口
├── stop.bat                         ← 新增：停止入口
├── pack.ps1                         ← 新增：本机打包脚本（不在分发包内）
└── README.txt                       ← 客户用：三步 + 故障排查
```

**排除**（不进包）：`bot/src/`（源码）、`bot/data/runtime.properties`（本机残留）、`bot/data/bot.db*`（本地数据库）、`bot/data/screenshots/`（截图）、`bot/data/heartbeat`、`bot/data/mumu_events.log`、`bot/data/tunnel_events.log`、`bot/data/watchdog_throttle.json`、`bot/data/mail.properties`、`bot/data/config_audit.log`、`bot/data/list_page.xml`、`bot/WeComSender.java/.class`（死代码）、`bot/run.log`/`run.err.log`、`bot/run.bat`/`run_*_test.bat`（编译与测试入口）、`.trae/`、`.git/`、`docs/adr/`、`docs/agents/`、`docs/plan/`、`docs/scope.md`、`CONTEXT.md`、`AGENTS.md`、`body.json`、`body2.json`、`pack.ps1` 本身（打包脚本不分发）。

**安全注意**：`docs/pem/private_key.pem` 明文随包分发（操作员模式下客户需用我方私钥解密存档）。zip 无密码加密——若需加密，改用 7z AES-256，但增加双方打包/解压摩擦。当前决策：不加密码，依赖客户机物理/账号安全。

### 6. P4 出厂配置

`p4.dry-run=false`（真实发送），前端 `/config` 页面可一键切回 `true`。对应 `config.properties` 中 `p4.dry-run=false`。

## Alternatives Considered

- **捆绑 MuMu 安装器/便携版**：许可证风险 + 体积 + MuMu 更新会破坏 UI rid。否决，客户自装。
- **假设客户已有 JDK 17**：99% 客户没有。否决，捆绑 JRE。
- **jlink 裁剪 JRE**：体积小但维护成本高，每加依赖要重跑。否决。
- **可见控制台窗口启动**：排障友好但客户体验差（日志刷屏）。否决，隐藏启动 + `logs/bot-*.log` 留痕。
- **AdminServer `/shutdown` 端点做停止**：需新增代码。否决，`stop.bat` taskkill 零改动，后续可加端点。
- **安装器（Inno Setup）**：开始菜单/卸载项/管理员权限。否决，单客户内部工具，zip 解压即可。
- **源码随包分发**：客户无编译环境且不应接触源码。否决。
- **zip 加密**：增加双方摩擦，操作员模式下客户本就需持有私钥。否决。

## Consequences

- 优势：客户零配置（除安装 MuMu + 首次扫码）；JRE 捆绑消除 Java 环境依赖；MuMu 自动检测覆盖 95% 默认安装场景；隐藏启动不干扰客户桌面；`logs/` 与 `*_events.log` 提供完整排障链路。
- 代价：包体积约 250–300 MB（JRE 180 MB + SDK/DLL/jar ~50 MB + 其余）；私钥明文随包分发（操作员模式可接受）；MuMu 未自动安装是唯一一个客户需要主动做的环境准备。
- 影响文件：`bot/run-only.bat`、`bot/build.bat`（JAVA_HOME 改相对路径）；新增 `launcher.ps1`、`start.vbs`、`stop.bat`、`pack.ps1`、`README.txt`；`config.properties` 的 `adb.device` 改 `127.0.0.1:16384`、`p4.dry-run` 改 `false`。无 Java 源码改动（bot 侧 `detectDevice` 与 `Config.reload` 已满足需求）。
