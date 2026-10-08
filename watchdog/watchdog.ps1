﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿﻿# 看门狗（P7，见 ADR-0012 / ADR-0013 / ADR-0014）
#
# 职责：
#   1. 进程存活检测：java.exe com.mumu.bot.Main 消失 → 崩溃 → 重启 bot/run-only.bat
#   2. 心跳检测：data/heartbeat mtime 超过 80s → 进程假死 → kill + 告警 + 重启
#   3. 熔断哨兵：data/circuit_breaker 存在 → 只告警不重启，watchdog 自身退出（等人工）
#   4. 崩溃环防护：10 分钟内重启 ≥5 次 → 停止重启 + 告警 + watchdog 退出
#   5. 邮件告警：用 Send-MailMessage 发 heartbeat_lost / circuit_breaker 告警
#      （JVM 已死时无法发信，由 watchdog 代发；节流 30 分钟，circuit_breaker 绕过）
#   6. MuMu 健康检测（ADR-0013）：MuMuManager info 的 player_state != start_finished
#      → MuMu 模拟器崩溃（长期运行企微内存溢出）→ 写 data/mumu_down 哨兵 → launch 重启
#      → 轮询启动状态（120s 超时）→ 删哨兵 → kill 残留 bot + 重启 bot。
#      约定次数内自愈全程静默（不发邮件），仅追加一行到 data/mumu_events.log 留痕；
#      哨兵的作用：MuMu 崩溃期间 bot 的 ADB 必然失败，bot 侧 Adb.shell 检测到哨兵就跳过
#      adb_failed 计数，避免同一故障双告警刷屏（见 bot/src/com/mumu/bot/outbound/adb/Adb.java）
#      MuMu 独立崩溃环：1 小时内重启 MuMu ≥3 次 → 写熔断哨兵 + circuit_breaker 告警
#      + watchdog 退出（等人工）——这是 MuMu 故障唯一发邮件的时机
#   7. SSH 隧道托管（ADR-0014）：SDK 拉取存档依赖 socks5://127.0.0.1:1080，watchdog 每
#      30s 检查端口监听，断了自动重拉 plink（-batch 防交互卡死，窗口隐藏）。幂等：端口
#      已有监听（手动 ssh.exe / 存活 plink）则跳过不拉。静默自愈不发邮件（最终兜底是
#      bot 侧 sdk_pull_failed），仅留痕 data/tunnel_events.log；连续拉起失败 ≥5 次
#      退避 5 分钟（防密码错/断网时无限刷）。首次使用需手动跑一次 host key 引导命令。
#
# 启动：powershell -ExecutionPolicy Bypass -File watchdog.ps1
# 停止：Ctrl+C 或关闭窗口（watchdog 退出后需手动重启）

param([int]$IntervalSec = 30)

$ErrorActionPreference = "Continue"

# ===== 路径（相对脚本位置，bot 在 ../bot/）=====
$botDir    = Join-Path $PSScriptRoot "..\bot"
$runBat    = Join-Path $botDir "run-only.bat"
$config    = Join-Path $botDir "config.properties"
$mailProp  = Join-Path $botDir "data\mail.properties"
$heartbeat = Join-Path $botDir "data\heartbeat"
$sentinel  = Join-Path $botDir "data\circuit_breaker"
$throttleFile = Join-Path $botDir "data\watchdog_throttle.json"
$restartRequested = Join-Path $botDir "data\restart_requested"
# ADR-0013：MuMu 崩溃哨兵。存在期间 bot 侧 Adb.shell 跳过 adb_failed 计数
$mumuDown  = Join-Path $botDir "data\mumu_down"
# ADR-0013：MuMu 自愈留痕。约定次数内恢复不发邮件，仅追加此日志供人工排查
$mumuEvents = Join-Path $botDir "data\mumu_events.log"
# ADR-0014：SSH 隧道。plink 单文件放 watchdog\bin\，自愈留痕 data/tunnel_events.log
$plink        = Join-Path $PSScriptRoot "bin\plink.exe"
$tunnelEvents = Join-Path $botDir "data\tunnel_events.log"

$HEARTBEAT_TIMEOUT_MS = 80 * 1000        # 心跳超时 80s
$CRASH_WINDOW_MS      = 10 * 60 * 1000   # 崩溃环窗口 10 分钟
$CRASH_MAX_RESTARTS   = 5                # 窗口内最多重启 5 次
$ALERT_THROTTLE_MS    = 30 * 60 * 1000   # 邮件节流 30 分钟

# ADR-0013：MuMu 崩溃检测参数
$MUMU_CRASH_WINDOW_MS   = 60 * 60 * 1000 # MuMu 独立崩溃环窗口 1 小时
$MUMU_CRASH_MAX_RESTARTS = 3             # 窗口内最多重启 MuMu 3 次，超则熔断
$MUMU_BOOT_TIMEOUT_MS   = 120 * 1000     # MuMu 启动轮询超时 120s
$MUMU_POLL_INTERVAL_MS  = 3000           # 轮询间隔 3s

# ADR-0014：SSH 隧道参数
$TUNNEL_BACKOFF_MS       = 5 * 60 * 1000 # 连续拉起失败 ≥5 次后退避 5 分钟
$TUNNEL_MAX_CONSEC_FAILS = 5

# ===== 读取 config.properties =====
function Read-Config {
    $cfg = @{}
    if (Test-Path $config) {
        Get-Content $config -Encoding UTF8 | ForEach-Object {
            $line = $_.Trim()
            if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
                $idx = $line.IndexOf("=")
                $key = $line.Substring(0, $idx).Trim()
                $val = $line.Substring($idx + 1).Trim()
                $cfg[$key] = $val
            }
        }
    }
    # 运行时配置覆盖层：data/runtime.properties 覆盖同名键（与 bot 侧 Config.reload() 一致）
    $runtimeProp = Join-Path $botDir "data\runtime.properties"
    if (Test-Path $runtimeProp) {
        Get-Content $runtimeProp -Encoding UTF8 | ForEach-Object {
            $line = $_.Trim()
            if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
                $idx = $line.IndexOf("=")
                $key = $line.Substring(0, $idx).Trim()
                $val = $line.Substring($idx + 1).Trim()
                if ($val) { $cfg[$key] = $val }
            }
        }
    }
    return $cfg
}

# ===== 收件人：优先 data/mail.properties，缺省取 mail.smtp.account =====
function Get-Recipient($cfg) {
    if (Test-Path $mailProp) {
        $mp = @{}
        Get-Content $mailProp -Encoding UTF8 | ForEach-Object {
            $line = $_.Trim()
            if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
                $idx = $line.IndexOf("=")
                $mp[$line.Substring(0, $idx).Trim()] = $line.Substring($idx + 1).Trim()
            }
        }
        if ($mp["recipient"]) { return $mp["recipient"] }
    }
    if ($cfg["mail.recipient"]) { return $cfg["mail.recipient"] }
    return $cfg["mail.smtp.account"]
}

# ===== 节流：读/写 watchdog_throttle.json =====
function Get-LastSent($eventType) {
    if (Test-Path $throttleFile) {
        try {
            $j = Get-Content $throttleFile -Raw -Encoding UTF8 | ConvertFrom-Json
            if ($j.$eventType) { return [long]$j.$eventType }
        } catch {}
    }
    return 0
}

function Set-LastSent($eventType) {
    $data = @{}
    if (Test-Path $throttleFile) {
        try {
            $j = Get-Content $throttleFile -Raw -Encoding UTF8 | ConvertFrom-Json
            $j.PSObject.Properties | ForEach-Object { $data[$_.Name] = $_.Value }
        } catch {}
    }
    $data[$eventType] = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    $data | ConvertTo-Json | Set-Content $throttleFile -Encoding UTF8
}

# ===== 诊断包附件（与手动导出共用包根目录 export-diagnostics.ps1，组包逻辑单一来源）=====
# 生成失败返回 $null，告警邮件照发（纯文本）——告警永不被附件阻塞
function New-DiagnosticsAttachment {
    $script = Join-Path $PSScriptRoot "..\export-diagnostics.ps1"
    if (-not (Test-Path $script)) { return $null }
    try {
        $outDir = Join-Path $env:TEMP "watchdog-diag"
        $out = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $script -OutDir $outDir 2>&1
        $zip = "$($out | Select-Object -Last 1)".Trim()
        if ($zip -and (Test-Path $zip)) { return $zip }
    } catch {}
    return $null
}

# ===== 发邮件（Send-MailMessage，PS5 内置）=====
function Send-AlertMail($eventType, $cnName, $detail) {
    $cfg = Read-Config
    if (-not $cfg["mail.enabled"] -or $cfg["mail.enabled"] -eq "false") { return }
    $account  = $cfg["mail.smtp.account"]
    $authCode = $cfg["mail.smtp.auth-code"]
    if (-not $account -or -not $authCode) {
        Write-Log ("[{0}] [告警] 跳过：mail.smtp.account/auth-code 未配置" -f (Get-Date -Format "HH:mm:ss"))
        return
    }
    $host_ = $cfg["mail.smtp.host"]
    $port  = [int]$cfg["mail.smtp.port"]
    $ssl   = $cfg["mail.smtp.ssl"] -ne "false"
    $recipient = Get-Recipient $cfg
    if (-not $recipient) { $recipient = $account }

    # 当天日志最后 50 行
    $today = Get-Date -Format "yyyy-MM-dd"
    $logFile = Join-Path $botDir "logs\bot-$today.log"
    $logTail = ""
    if (Test-Path $logFile) {
        try { $logTail = (Get-Content $logFile -Tail 50 -Encoding UTF8) -join "`n" } catch {}
    } else { $logTail = "(日志文件不存在: $logFile)" }

    $body = "事件类型: $eventType（$cnName）`n" +
            "触发时间: $(Get-Date)`n" +
            "机器:     $env:COMPUTERNAME`n" +
            "详情:     $detail`n`n" +
            "------ 当天日志最后 50 行 ------`n$logTail"

    # 诊断包附件（生成失败则为 $null，不影响纯文本告警）
    $attach = $null
    try { $attach = New-DiagnosticsAttachment } catch {}

    $sent = $false
    for ($attempt = 1; $attempt -le 2 -and -not $sent; $attempt++) {
        try {
            $sec = ConvertTo-SecureString $authCode -AsPlainText -Force
            $cred = New-Object System.Management.Automation.PSCredential($account, $sec)
            $mailArgs = @{
                From        = $account
                To          = $recipient
                Subject     = "[机器人告警] $cnName"
                Body        = $body
                SmtpServer  = $host_
                Port        = $port
                UseSsl      = $ssl
                Credential  = $cred
                Encoding    = "UTF8"
                ErrorAction = "Stop"
            }
            if ($attach) { $mailArgs["Attachments"] = $attach }
            Send-MailMessage @mailArgs
            Write-Log ("[{0}] [告警] sent: $eventType" -f (Get-Date -Format "HH:mm:ss"))
            $sent = $true
        } catch {
            if ($attempt -lt 2) {
                Write-Log ("[{0}] [告警] attempt $attempt failed, retrying: $($_.Exception.Message)" -f (Get-Date -Format "HH:mm:ss"))
                Start-Sleep -Seconds 3
            } else {
                Write-Log ("[{0}] [告警] send failed: $($_.Exception.Message)" -f (Get-Date -Format "HH:mm:ss"))
            }
        }
    }

    # 附件用后即删（%TEMP% 不堆积；发送失败也删——临时 zip 无保留价值，人工导出另有桌面副本）
    if ($attach) { try { Remove-Item $attach -Force -ErrorAction SilentlyContinue } catch {} }
}

# 带节流地发告警（circuit_breaker 绕过）
function Send-AlertThrottled($eventType, $cnName, $detail) {
    if ($eventType -ne "circuit_breaker") {
        $last = Get-LastSent $eventType
        if (([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() - $last) -lt $ALERT_THROTTLE_MS) {
            return  # 节流命中
        }
    }
    Set-LastSent $eventType
    Send-AlertMail $eventType $cnName $detail
}

# ===== 查找机器人进程 =====
function Get-BotProcess {
    return Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -match 'com\.mumu\.bot\.Main' }
}

# ===== 重启机器人 =====
function Restart-Bot {
    Write-Log ("[{0}] 重启机器人: {1}" -f (Get-Date -Format "HH:mm:ss"), $runBat)
    Start-Process -FilePath "cmd.exe" -ArgumentList "/c", "`"$runBat`"" -WindowStyle Hidden
}

# ===== ADR-0013：MuMu 自愈留痕 =====
# 约定次数内自愈不发邮件（告警信噪比），但控制台输出关窗即失——
# 追加一行到 data/mumu_events.log，人工排查"昨晚崩过几次又自愈了"时有据可查。
function Write-MuMuEvent($state, $detail, $result) {
    try {
        $line = "[{0}] player_state={1} {2} -> {3}" -f `
            (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $state, $detail, $result
        Add-Content -Path $mumuEvents -Value $line -Encoding UTF8
    } catch {
        Write-Log ("[{0}] MuMu 事件留痕写入失败: {1}" -f (Get-Date -Format "HH:mm:ss"), $_.Exception.Message)
    }
}

# ===== ADR-0013：MuMu 相关 =====

# MuMuManager.exe 路径：从 config.properties 的 adb.path（...\shell\adb.exe）同目录推导
function Get-MuMuManagerPath($cfg) {
    $adbPath = $cfg["adb.path"]
    if (-not $adbPath) { return $null }
    $shellDir = Split-Path -Path $adbPath -Parent
    if (-not $shellDir) { return $null }
    $mgr = Join-Path $shellDir "MuMuManager.exe"
    if (Test-Path $mgr) { return $mgr }
    # MuMu 12 nx 布局：MuMuManager.exe 在 <安装根>\nx_main\，不在 shell\
    $rootDir = Split-Path -Path $shellDir -Parent
    if ($rootDir) {
        $mgrNx = Join-Path $rootDir "nx_main\MuMuManager.exe"
        if (Test-Path $mgrNx) { return $mgrNx }
    }
    return $mgr
}

# MuMu 实例索引：优先读 runtime.properties 的 mumu.instance（launcher 从 info JSON 的
# index 字段写入，最权威）；缺省时从 adb.device 端口推导（16384=0, 16416=1, ...）。
# ADR-0016：launcher 自动探测实际运行实例端口并写入 runtime.properties，
# watchdog 据此确定实例索引，不再硬编码 0。
function Get-MuMuIndex($cfg) {
    $mi = $cfg["mumu.instance"]
    if ($mi -match '^\d+$') {
        $i = [int]$mi
        if ($i -ge 0 -and $i -le 10) { return $i }
    }
    $dev = $cfg["adb.device"]
    if ($dev -match ":(\d+)\s*$") {
        $port = [int]$Matches[1]
        $idx = [math]::Floor(($port - 16384) / 32)
        if ($idx -ge 0 -and $idx -le 10) { return $idx }
    }
    return 0
}

# 查询 MuMu 健康状态。
# 返回 @{ Healthy = bool; PlayerState = string; Detail = string }
# 判定规则：player_state == "start_finished" 才算健康。MuMu 进程崩溃/消失时 info 返回
# {"errcode":-200,"errmsg":"unknown error"}（JSON 合法但无 player_state）→ 判为不健康。
# 保守策略：MuMuManager 调用本身抛异常（exe 不存在/被占用/输出非 JSON）时判为 Healthy=$true，
# 避免把「查询工具坏了」误报成「模拟器崩了」而反复重启 MuMu。
function Get-MuMuState($cfg) {
    $mgr = Get-MuMuManagerPath $cfg
    if (-not $mgr -or -not (Test-Path $mgr)) {
        return @{ Healthy = $true; PlayerState = "(unknown)"; Detail = "MuMuManager.exe 未找到: $mgr" }
    }
    try {
        $mumuIndex = Get-MuMuIndex $cfg
        $raw = (& $mgr info -v $mumuIndex 2>&1 | Out-String).Trim()
        $j = $raw | ConvertFrom-Json -ErrorAction Stop
        $state = [string]$j.player_state
        if ($state -eq "start_finished") {
            return @{ Healthy = $true; PlayerState = $state; Detail = "" }
        }
        # 不健康：优先取 errcode/errmsg（进程崩溃签名），其次 error_code / launch_err_msg
        $err = ""
        if ($j.PSObject.Properties.Name -contains "errcode") {
            $err = "errcode=$($j.errcode) errmsg=$($j.errmsg)"
        } else {
            if ($j.error_code) { $err += "error_code=$($j.error_code) " }
            if ($j.launch_err_msg) { $err += "launch_err_msg=$($j.launch_err_msg) " }
            if ($j.launch_err_code) { $err += "launch_err_code=$($j.launch_err_code)" }
            $err = $err.Trim()
        }
        if (-not $state) { $state = "(none)" }
        return @{ Healthy = $false; PlayerState = $state; Detail = $err }
    } catch {
        # 查询失败（工具异常，非模拟器崩溃）：不轻易判定崩溃
        return @{ Healthy = $true; PlayerState = "(query_failed)"; Detail = "MuMuManager info 调用失败: $($_.Exception.Message)" }
    }
}

# 重启 MuMu：写哨兵 → launch → 轮询启动状态 → 删哨兵。
# 返回 $true 表示 MuMu 已恢复；$false 表示启动超时（哨兵仍会删除，避免永久抑制 adb_failed）
function Restart-MuMu($cfg) {
    $mgr = Get-MuMuManagerPath $cfg
    Write-Log ("[{0}] 写 MuMu 崩溃哨兵: {1}" -f (Get-Date -Format "HH:mm:ss"), $mumuDown)
    try {
        $parent = Split-Path -Path $mumuDown -Parent
        if (-not (Test-Path $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
        Set-Content -Path $mumuDown -Value ("MuMu crashed at " + (Get-Date)) -Encoding UTF8
    } catch {
        Write-Log ("[{0}] 哨兵写入失败: {1}" -f (Get-Date -Format "HH:mm:ss"), $_.Exception.Message)
    }

    try {
        $mumuIndex = Get-MuMuIndex $cfg
        Write-Log ("[{0}] 启动 MuMu: {1} control -v {2} launch" -f (Get-Date -Format "HH:mm:ss"), $mgr, $mumuIndex)
        & $mgr control -v $mumuIndex launch 2>&1 | Out-Null
    } catch {
        Write-Log ("[{0}] MuMu launch 调用失败: {1}" -f (Get-Date -Format "HH:mm:ss"), $_.Exception.Message)
    }

    # 轮询直到 start_finished 或超时。
    # 注意：这里显式判 player_state=="start_finished"，不用 .Healthy——
    # Get-MuMuState 在查询工具异常时保守返回 Healthy=$true（避免误报崩溃），
    # 但那种"未确认"不能作为 MuMu 已恢复的证据；启动过程中一次瞬时查询失败
    # 应继续轮询，而非误判成功。
    $deadline = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + $MUMU_BOOT_TIMEOUT_MS
    $ok = $false
    $lastState = ""
    while ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() -lt $deadline) {
        Start-Sleep -Milliseconds $MUMU_POLL_INTERVAL_MS
        $s = Get-MuMuState $cfg
        $lastState = $s.PlayerState
        if ($s.PlayerState -eq "start_finished") { $ok = $true; break }
    }

    # 无论成功超时都删哨兵：超时场景下留着哨兵会永久抑制 bot 的 adb_failed 告警
    try {
        if (Test-Path $mumuDown) { Remove-Item $mumuDown -Force -ErrorAction SilentlyContinue }
        Write-Log ("[{0}] 已删除 MuMu 崩溃哨兵" -f (Get-Date -Format "HH:mm:ss"))
    } catch {}

    if ($ok) {
        Write-Log ("[{0}] MuMu 已启动完成（player_state={1}）" -f (Get-Date -Format "HH:mm:ss"), $lastState)
    } else {
        Write-Log ("[{0}] MuMu 启动超时（{1}s，最后状态 {2}）" -f (Get-Date -Format "HH:mm:ss"), ($MUMU_BOOT_TIMEOUT_MS/1000), $lastState)
    }
    return $ok
}

# ===== ADR-0014：SSH 动态代理隧道 =====
# SDK 拉取存档依赖 proxy=socks5://127.0.0.1:1080，该端口由 plink -D 动态转发提供。
# 自愈策略与 MuMu 一致（ADR-0013 先例）：静默重拉不发邮件，仅留痕 tunnel_events.log；
# 最终兜底是 bot 侧既有 sdk_pull_failed 告警（隧道长期断了 SDK 必然连续拉取失败）。

# 本地隧道端口是否已有监听。手动开的 ssh.exe 或存活的 plink 都算——幂等跳过，不抢占
function Get-TunnelAlive($port) {
    try {
        return [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
    } catch {
        return $false
    }
}

# 拉起 plink 动态转发：隐藏窗口、-N 纯转发不开 shell、-batch 防 host key 交互卡死
function Start-Tunnel($cfg, $listenPort) {
    if (-not (Test-Path $plink)) {
        Write-Log ("[{0}] [隧道] plink.exe 不存在: {1}" -f (Get-Date -Format "HH:mm:ss"), $plink)
        return
    }
    $sshHost = $cfg["ssh.proxy.host"]
    $sshUser = $cfg["ssh.proxy.user"]
    $sshPort = if ($cfg["ssh.proxy.port"]) { $cfg["ssh.proxy.port"] } else { "22" }
    $sshPass = $cfg["ssh.proxy.password"]
    # ADR-0016：服务器指纹（如 SHA256:xxx）。plink 0.85 的密钥确认提示走控制台不走 stdin，
    # 管道喂 y 无效；-batch 又拒绝未缓存密钥。显式 -hostkey 可在全新客户机上零交互通过验证
    $sshHostKey = $cfg["ssh.proxy.hostkey"]
    if (-not $sshPass) {
        Write-Log ("[{0}] [隧道] ssh.proxy.password 未配置，跳过拉起（配置见 bot/config.properties）" -f (Get-Date -Format "HH:mm:ss"))
        return
    }
    # 端口无监听但可能残留挂住的 plink，先清掉避免进程堆积（全机只有本 watchdog 用 plink）
    Get-Process plink -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    $argLine = "-batch -N -D 127.0.0.1:{0} -P {1} -pw `"{2}`" {3}@{4}" -f $listenPort, $sshPort, $sshPass, $sshUser, $sshHost
    if ($sshHostKey) { $argLine += " -hostkey $sshHostKey" }
    Start-Process -FilePath $plink -ArgumentList $argLine -WindowStyle Hidden
    Write-Log ("[{0}] [隧道] 已拉起 plink -> {1}:{2}（本地 127.0.0.1:{3}）" -f (Get-Date -Format "HH:mm:ss"), $sshHost, $sshPort, $listenPort)
}

# 隧道自愈留痕：控制台关窗即失，追加一行到 data/tunnel_events.log 供人工排查
function Write-TunnelEvent($detail) {
    try {
        Add-Content -Path $tunnelEvents -Value ("[{0}] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $detail) -Encoding UTF8
    } catch {
        Write-Log ("[{0}] 隧道事件留痕写入失败: {1}" -f (Get-Date -Format "HH:mm:ss"), $_.Exception.Message)
    }
}

# ===== 统一日志：控制台 + logs\watchdog-YYYY-MM-DD.log（按天滚动）=====
# 隐藏窗口的控制台输出关窗即失，文件留痕既是排查证据，也是诊断包的原料。
# 消息原样写入文件（多数调用已自带 [HH:mm:ss] 前缀），文件侧再补完整日期时间。
function Write-Log {
    param([string]$Message)
    try { Microsoft.PowerShell.Utility\Write-Host $Message } catch {}
    try {
        $wdLogDir = Join-Path $botDir "logs"
        if (-not (Test-Path $wdLogDir)) { New-Item -ItemType Directory -Path $wdLogDir -Force | Out-Null }
        $wdLog = Join-Path $wdLogDir ("watchdog-{0}.log" -f (Get-Date -Format "yyyy-MM-dd"))
        Add-Content -Path $wdLog -Value ("[{0}] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $Message) -Encoding UTF8
    } catch {}
}

# ===== 主循环 =====
Write-Log "=== 看门狗启动（每 ${IntervalSec}s 检测）==="
Write-Log "bot 目录: $botDir"
Write-Log "心跳超时: $($HEARTBEAT_TIMEOUT_MS/1000)s | 崩溃环: $CRASH_MAX_RESTARTS 次/$($CRASH_WINDOW_MS/60000)min"
Write-Log ""

$restartHistory = New-Object System.Collections.ArrayList  # 重启时间戳列表
$mumuRestartHistory = New-Object System.Collections.ArrayList  # ADR-0013：MuMu 重启时间戳列表（独立于 bot 崩溃环）

# ADR-0014：隧道自愈状态（$null = 尚未检测过，避免启动即记一笔"断线"）
$tunnelWasAlive     = $null
$tunnelConsecFails  = 0
$tunnelBackoffUntil = 0

while ($true) {
    try {
        # 1. 熔断哨兵检测（最高优先级：不重启）
        if (Test-Path $sentinel) {
            $reason = "(哨兵文件存在)"
            try { $reason = (Get-Content $sentinel -Raw -Encoding UTF8).Trim() } catch {}
            Write-Log ("[{0}] ⛔ 检测到熔断哨兵，不重启。原因: {1}" -f (Get-Date -Format "HH:mm:ss"), $reason)
            Send-AlertThrottled "circuit_breaker" "熔断触发" "watchdog 检测到熔断哨兵文件，已停止自动重启。原因: $reason"
            Write-Log "watchdog 退出。请人工处理后删除 $sentinel 并重新启动 watchdog。"
            exit 0
        }

        $cfg = Read-Config

        # 1.4 ADR-0014：SSH 隧道检测（先于 MuMu——SDK 拉取存档依赖本地 socks5 端口）
        if ($cfg["ssh.proxy.host"]) {
            $tunnelPort = 1080
            if ($cfg["proxy"] -match ":(\d+)\s*$") { $tunnelPort = [int]$Matches[1] }
            $tunnelAlive = Get-TunnelAlive $tunnelPort
            if ($tunnelAlive) {
                if ($tunnelWasAlive -eq $false) { Write-TunnelEvent "隧道恢复（本地端口重新监听）" }
                $tunnelConsecFails = 0
                $tunnelBackoffUntil = 0
            } else {
                if ($tunnelWasAlive -eq $true) { Write-TunnelEvent "隧道断线（本地端口无监听），尝试重新拉起" }
                $nowMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
                if ($nowMs -ge $tunnelBackoffUntil) {
                    if ($tunnelConsecFails -ge $TUNNEL_MAX_CONSEC_FAILS) {
                        Write-TunnelEvent ("连续 {0} 次拉起失败，退避 5 分钟（常见原因：密码错/服务器不通/host key 未引导，见 ADR-0014）" -f $tunnelConsecFails)
                        $tunnelBackoffUntil = $nowMs + $TUNNEL_BACKOFF_MS
                        $tunnelConsecFails = 0
                    } else {
                        Start-Tunnel $cfg $tunnelPort
                        $tunnelConsecFails++
                    }
                }
            }
            $tunnelWasAlive = $tunnelAlive
        }

        # 1.5 ADR-0013：MuMu 健康检测（先于 bot 检测——MuMu 崩了 bot 的 ADB 必然失败）
        $mumuState = Get-MuMuState $cfg
        if (-not $mumuState.Healthy) {
            Write-Log ("[{0}] 💀 MuMu 模拟器崩溃（player_state={1} {2}）" -f `
                (Get-Date -Format "HH:mm:ss"), $mumuState.PlayerState, $mumuState.Detail)

            # MuMu 独立崩溃环：1 小时内重启 ≥3 次 → 熔断退出（内存溢出反复崩，重启无意义）
            $mumuCutoff = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() - $MUMU_CRASH_WINDOW_MS
            $mumuRecent = @($mumuRestartHistory | Where-Object { $_ -gt $mumuCutoff })
            if ($mumuRecent.Count -ge $MUMU_CRASH_MAX_RESTARTS) {
                $cbReason = "MuMu 崩溃环防护：$($MUMU_CRASH_WINDOW_MS/60000) 分钟内 MuMu 崩溃并重启 $($mumuRecent.Count) 次，已停止自动重启，请人工排查（疑似企微长期运行导致模拟器内存溢出）"
                Write-Log ("[{0}] 🛑 {1}" -f (Get-Date -Format "HH:mm:ss"), $cbReason)
                Write-MuMuEvent $mumuState.PlayerState $mumuState.Detail "熔断：近 $($MUMU_CRASH_WINDOW_MS/60000) 分钟重启 $($mumuRecent.Count) 次，watchdog 退出等人工"
                try {
                    $cbParent = Split-Path -Path $sentinel -Parent
                    if (-not (Test-Path $cbParent)) { New-Item -ItemType Directory -Path $cbParent -Force | Out-Null }
                    Set-Content -Path $sentinel -Value $cbReason -Encoding UTF8
                } catch {}
                Send-AlertThrottled "circuit_breaker" "熔断触发" $cbReason
                Write-Log "watchdog 退出。请人工排查后删除 $sentinel 并重新启动 watchdog。"
                exit 0
            }

            $mumuOk = Restart-MuMu $cfg
            [void]$mumuRestartHistory.Add([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())

            # 无论 MuMu 是否起来都重启 bot：超时场景让 bot 自行重试 ADB 连接兜底
            # （残留 bot 的 ADB 连接已失效，必须 kill 后重启）
            $staleBot = Get-BotProcess
            if ($staleBot) {
                try { Stop-Process -Id $staleBot.ProcessId -Force -ErrorAction SilentlyContinue } catch {}
                Start-Sleep -Seconds 3
            }
            Restart-Bot
            [void]$restartHistory.Add([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())

            # ADR-0013：约定次数内自愈不发邮件，仅留痕（含启动超时——非终态，下周期继续重试）
            if ($mumuOk) {
                Write-MuMuEvent $mumuState.PlayerState $mumuState.Detail "自愈成功：已重启 MuMu+bot（近 $($MUMU_CRASH_WINDOW_MS/60000) 分钟内第 $($mumuRestartHistory.Count) 次）"
            } else {
                Write-MuMuEvent $mumuState.PlayerState $mumuState.Detail ("MuMu 启动超时 {0}s，已重启 bot 自行重试 ADB 连接（近 {1} 分钟内第 {2} 次）" -f ($MUMU_BOOT_TIMEOUT_MS/1000), ($MUMU_CRASH_WINDOW_MS/60000), $mumuRestartHistory.Count)
            }
            # 本轮已处理 MuMu，跳过后续 bot 检测（bot 刚重启，心跳还没写）
            Start-Sleep -Seconds $IntervalSec
            continue
        }

        $bot = Get-BotProcess

        if ($bot) {
            # 2. 心跳检测：进程活着但心跳超时 → 假死
            $hung = $false
            if (Test-Path $heartbeat) {
                $mtime = (Get-Item $heartbeat).LastWriteTime
                $age = ([DateTime]::Now - $mtime).TotalMilliseconds
                if ($age -gt $HEARTBEAT_TIMEOUT_MS) { $hung = $true }
            } else {
                $hung = $true  # 心跳文件不存在也视为假死
            }

            if ($hung) {
                Write-Log ("[{0}] 💀 心跳超时（{1:N0}ms），判定假死，kill 进程" -f (Get-Date -Format "HH:mm:ss"), $age)
                Send-AlertThrottled "heartbeat_lost" "心跳丢失/进程假死" "心跳超时超过 $($HEARTBEAT_TIMEOUT_MS/1000)s，已 kill 并重启"
                try { Stop-Process -Id $bot.ProcessId -Force -ErrorAction SilentlyContinue } catch {}
                Start-Sleep -Seconds 3
                Restart-Bot
                [void]$restartHistory.Add([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())
            }
            # 进程正常 → 清理过期崩溃环记录
            $cutoff = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() - $CRASH_WINDOW_MS
            $keep = @($restartHistory | Where-Object { $_ -gt $cutoff })
            $restartHistory.Clear()
            foreach ($x in $keep) { [void]$restartHistory.Add($x) }
        } else {
            # 3. 进程消失 → 检查是否 Admin /config 主动重启
            if (Test-Path $restartRequested) {
                # 哨兵存在 = 身份变更或管理员请求重启，主动退出
                Write-Log ("[{0}] 🔄 检测到 restart_requested 哨兵，Admin /config 触发，立即重启（不计入崩溃环）" -f (Get-Date -Format "HH:mm:ss"))
                try { Remove-Item $restartRequested -Force -ErrorAction SilentlyContinue } catch {}
                Restart-Bot
                continue
            }

            # 崩溃场景
            Write-Log ("[{0}] 🔌 机器人进程消失" -f (Get-Date -Format "HH:mm:ss"))

            # 崩溃环防护
            $cutoff = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() - $CRASH_WINDOW_MS
            $recent = @($restartHistory | Where-Object { $_ -gt $cutoff })
            if ($recent.Count -ge $CRASH_MAX_RESTARTS) {
                Write-Log ("[{0}] 🛑 崩溃环触发：{1} 分钟内已重启 {2} 次，停止自动重启" -f `
                    (Get-Date -Format "HH:mm:ss"), ($CRASH_WINDOW_MS/60000), $recent.Count)
                Send-AlertThrottled "circuit_breaker" "熔断触发" `
                    "崩溃环防护：$($CRASH_WINDOW_MS/60000) 分钟内重启 $($recent.Count) 次，已停止自动重启，请人工排查"
                Write-Log "watchdog 退出。请人工排查后重新启动。"
                exit 0
            }

            Send-AlertThrottled "heartbeat_lost" "心跳丢失/进程假死" "机器人进程消失，正在自动重启"
            Restart-Bot
            [void]$restartHistory.Add([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())
        }
    } catch {
        Write-Log ("[{0}] watchdog 循环异常: {1}" -f (Get-Date -Format "HH:mm:ss"), $_.Exception.Message)
    }

    Start-Sleep -Seconds $IntervalSec
}
