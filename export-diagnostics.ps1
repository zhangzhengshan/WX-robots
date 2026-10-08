# export-diagnostics.ps1 - Diagnostics bundle builder (single source of truth)
#
# Collects logs, redacted configs, local db, recent screenshots and a system
# snapshot into one zip, for report-back to the developer (email or manual).
#
# Callers: export-diagnostics.bat (manual, -Mail), watchdog.ps1 (alert attach),
#          MailAlerter.java (alert attach, via powershell.exe).
#
# Contract:
#   - Progress lines are prefixed "[export] ".
#   - The LAST non-empty stdout line is ALWAYS the full path of the zip (bare).
#   - Never included: docs\pem (SDK private key), data\media, bot\src.
#   - Redaction: config values whose key matches password|auth-code|api-key|token|secret
#     are replaced with ***REDACTED***.
#
# Usage:
#   powershell -File export-diagnostics.ps1 [-OutDir <dir>] [-Mail] [-LogDays 7] [-ScreenshotCount 10]
#     -OutDir   where the zip lands (default %TEMP%; bat uses Desktop)
#     -Mail     also send the zip via the bot's SMTP config

param(
    [string]$OutDir = $env:TEMP,
    [switch]$Mail,
    [int]$LogDays = 7,
    [int]$ScreenshotCount = 10
)

$ErrorActionPreference = "Continue"
$root    = $PSScriptRoot
$botDir  = Join-Path $root "bot"
$dataDir = Join-Path $botDir "data"
$logDir  = Join-Path $botDir "logs"
$stamp   = Get-Date -Format "yyyyMMdd-HHmmss"

function Info($m) { Write-Host "[export] $m" }

# ===== helpers =====

function Read-PropsRaw($path) {
    $h = @{}
    if (Test-Path $path) {
        Get-Content $path -Encoding UTF8 | ForEach-Object {
            $line = $_.Trim()
            if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
                $idx = $line.IndexOf("=")
                $h[$line.Substring(0, $idx).Trim()] = $line.Substring($idx + 1).Trim()
            }
        }
    }
    return $h
}

function Redact-Props($src, $dst) {
    if (-not (Test-Path $src)) { return $false }
    $out = New-Object System.Collections.Generic.List[string]
    foreach ($line in (Get-Content $src -Encoding UTF8)) {
        $t = $line.Trim()
        if ($t -and -not $t.StartsWith("#") -and $t.Contains("=")) {
            $idx = $t.IndexOf("=")
            $key = $t.Substring(0, $idx).Trim()
            if ($key -match "(?i)(password|auth-code|api-key|token|secret)") {
                $out.Add("$key=***REDACTED***")
                continue
            }
        }
        $out.Add($line)
    }
    Set-Content -Path $dst -Value $out -Encoding UTF8
    return $true
}

# merged config view (config.properties + runtime.properties override)
$cfgAll = Read-PropsRaw (Join-Path $botDir "config.properties")
foreach ($kv in (Read-PropsRaw (Join-Path $dataDir "runtime.properties")).GetEnumerator()) {
    $cfgAll[$kv.Key] = $kv.Value
}

# ===== staging =====
$stage = Join-Path ([System.IO.Path]::GetTempPath()) "diag-$stamp"
New-Item -ItemType Directory -Path $stage -Force | Out-Null
Info "staging: $stage"

$collected = New-Object System.Collections.Generic.List[string]
function Add-File($src, $subDir) {
    if (-not (Test-Path $src)) { return }
    try {
        $dstDir = Join-Path $stage $subDir
        if (-not (Test-Path $dstDir)) { New-Item -ItemType Directory -Path $dstDir -Force | Out-Null }
        Copy-Item $src (Join-Path $dstDir (Split-Path $src -Leaf)) -Force
        $collected.Add("$subDir\$(Split-Path $src -Leaf)")
    } catch {
        Info "SKIP (locked?): $src - $($_.Exception.Message)"
    }
}

# 1. logs: dated bot/watchdog logs of last N days + launcher.log + event logs
$cutoff = (Get-Date).AddDays(-1 * $LogDays)
if (Test-Path $logDir) {
    Get-ChildItem $logDir -File -ErrorAction SilentlyContinue | Where-Object {
        ($_.Name -match "^(bot-\d{4}-\d{2}-\d{2}\.log|watchdog-\d{4}-\d{2}-\d{2}\.log)$") -and
        $_.LastWriteTime -ge $cutoff
    } | ForEach-Object { Add-File $_.FullName "logs" }
    Add-File (Join-Path $logDir "launcher.log") "logs"
}
# launcher writes to package-root logs\ (not bot\logs) - collect both locations
Add-File (Join-Path $root "logs\launcher.log") "logs"
Add-File (Join-Path $dataDir "mumu_events.log") "logs"
Add-File (Join-Path $dataDir "tunnel_events.log") "logs"

# 2. redacted configs (flat at stage root for easy reading)
foreach ($n in @("config.properties", "runtime.properties", "mail.properties")) {
    $src = if ($n -eq "config.properties") { Join-Path $botDir $n } else { Join-Path $dataDir $n }
    $dst = Join-Path $stage $n
    if (Redact-Props $src $dst) { $collected.Add($n) }
}

# 3. local db trio (sqlite: db + wal + shm keep a consistent snapshot)
Add-File (Join-Path $dataDir "bot.db") "db"
Add-File (Join-Path $dataDir "bot.db-wal") "db"
Add-File (Join-Path $dataDir "bot.db-shm") "db"

# 4. recent screenshots
$ssDir = Join-Path $dataDir "screenshots"
if (Test-Path $ssDir) {
    Get-ChildItem $ssDir -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First $ScreenshotCount |
        ForEach-Object { Add-File $_.FullName "screenshots" }
}

# 5. build info (written by pack.ps1)
$bi = Join-Path $root "build-info.txt"
if (Test-Path $bi) {
    try { Copy-Item $bi (Join-Path $stage "build-info.txt") -Force; $collected.Add("build-info.txt") } catch {}
}

# 6. system snapshot
$sb = New-Object System.Text.StringBuilder

[void]$sb.AppendLine("==== Diagnostics system snapshot ====")
[void]$sb.AppendLine(("time      : " + (Get-Date -Format "yyyy-MM-dd HH:mm:ss")))
[void]$sb.AppendLine("host      : $env:COMPUTERNAME  user: $env:USERNAME")
try {
    $os = Get-CimInstance Win32_OperatingSystem
    [void]$sb.AppendLine("os        : $($os.Caption) | $($os.OSArchitecture) | ver $($os.Version)")
    [void]$sb.AppendLine(("mem       : total {0:N0} MB, free {1:N0} MB" -f ($os.TotalVisibleMemorySize / 1KB), ($os.FreePhysicalMemory / 1KB)))
} catch {
    [void]$sb.AppendLine("os        : (query failed: $($_.Exception.Message))")
}
try {
    $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
    [void]$sb.AppendLine("cpu       : $($cpu.Name)")
} catch {}
try {
    Get-PSDrive -PSProvider FileSystem | Where-Object { $_.Free -ne $null } | ForEach-Object {
        [void]$sb.AppendLine(("disk {0}     : free {1:N1} GB / total {2:N1} GB" -f $_.Name, ($_.Free / 1GB), (($_.Used + $_.Free) / 1GB)))
    }
} catch {}
try {
    $javaExe = Join-Path $root "runtime\jre\bin\java.exe"
    if (Test-Path $javaExe) {
        $javaOut = (& $javaExe -version 2>&1 | Out-String).Trim() -replace "`r?`n", " | "
        [void]$sb.AppendLine("java      : $javaOut")
    } else {
        [void]$sb.AppendLine("java      : (bundled jre missing: $javaExe)")
    }
} catch {
    [void]$sb.AppendLine("java      : (version call failed)")
}

# MuMu: manager path (shell dir, nx_main fallback) + version + info JSON
$adbPath = $cfgAll["adb.path"]
$mumMgr = $null
if ($adbPath -and (Test-Path $adbPath)) {
    $shellDir = Split-Path $adbPath -Parent
    $cand = Join-Path $shellDir "MuMuManager.exe"
    if (Test-Path $cand) { $mumMgr = $cand }
    else {
        $nx = Join-Path (Split-Path $shellDir -Parent) "nx_main\MuMuManager.exe"
        if (Test-Path $nx) { $mumMgr = $nx }
    }
}
if ($mumMgr) {
    try {
        $vi = (Get-Item $mumMgr).VersionInfo
        [void]$sb.AppendLine("mumu mgr  : $mumMgr | ver $($vi.ProductVersion)")
        $infoRaw = (& $mumMgr info -v all 2>&1 | Out-String).Trim()
        if ($infoRaw.Length -gt 3000) { $infoRaw = $infoRaw.Substring(0, 3000) + " ...(truncated)" }
        [void]$sb.AppendLine("mumu info : $infoRaw")
    } catch {
        [void]$sb.AppendLine("mumu info : (call failed: $($_.Exception.Message))")
    }
} else {
    [void]$sb.AppendLine("mumu mgr  : (not found)")
}

# ADB device state
if ($adbPath -and (Test-Path $adbPath)) {
    try {
        [void]$sb.AppendLine("adb devices: " + ((& $adbPath devices 2>&1 | Out-String).Trim()))
    } catch {
        [void]$sb.AppendLine("adb devices: (call failed)")
    }
    $dev = $cfgAll["adb.device"]
    if ($dev) {
        foreach ($cmd in @("wm size", "wm density")) {
            try {
                [void]$sb.AppendLine("adb [$cmd] ($dev): " + ((& $adbPath -s $dev shell $cmd 2>&1 | Out-String).Trim()))
            } catch {}
        }
        try {
            $dump = (& $adbPath -s $dev shell "dumpsys package com.tencent.wework" 2>&1 | Out-String)
            $m = [regex]::Match($dump, "versionName=([^\s\}]+)")
            $wv = if ($m.Success) { $m.Groups[1].Value } else { "(not installed?)" }
            [void]$sb.AppendLine("wecom ver ($dev): $wv")
        } catch {
            [void]$sb.AppendLine("wecom ver : (dumpsys failed)")
        }
    }
} else {
    [void]$sb.AppendLine("adb       : (not found)")
}

# key processes
try {
    Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='plink.exe'" -ErrorAction SilentlyContinue | ForEach-Object {
        $cl = $_.CommandLine
        if ($cl -and $cl.Length -gt 220) { $cl = $cl.Substring(0, 220) + "..." }
        [void]$sb.AppendLine("proc      : $($_.Name) pid=$($_.ProcessId) $cl")
    }
} catch {}

# sentinels
foreach ($n in @("heartbeat", "circuit_breaker", "mumu_down", "restart_requested")) {
    $p = Join-Path $dataDir $n
    if (Test-Path $p) {
        $age = [int](((Get-Date) - (Get-Item $p).LastWriteTime).TotalSeconds)
        $val = ""
        try {
            $val = ((Get-Content $p -Raw -Encoding UTF8) -replace "`r?`n", " ").Trim()
            if ($val.Length -gt 160) { $val = $val.Substring(0, 160) + "..." }
        } catch {}
        [void]$sb.AppendLine("sentinel  : $n EXISTS age=${age}s $val")
    } else {
        [void]$sb.AppendLine("sentinel  : $n -")
    }
}
Set-Content -Path (Join-Path $stage "system_snapshot.txt") -Value $sb.ToString() -Encoding UTF8
$collected.Add("system_snapshot.txt")

# 7. manifest
$man = New-Object System.Text.StringBuilder
[void]$man.AppendLine("diagnostics bundle $stamp")
[void]$man.AppendLine("host: $env:COMPUTERNAME")
[void]$man.AppendLine("files:")
foreach ($c in $collected) {
    try { [void]$man.AppendLine(("  {0}  {1:N0} bytes" -f $c, (Get-Item (Join-Path $stage $c)).Length)) } catch {}
}
[void]$man.AppendLine("redaction: credential keys replaced with ***REDACTED***; docs\pem and data\media never included")
Set-Content -Path (Join-Path $stage "manifest.txt") -Value $man.ToString() -Encoding UTF8

# 8. zip
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }
# drop stale bundles (callers may fail to clean up)
Get-ChildItem $OutDir -Filter "diagnostics-*.zip" -ErrorAction SilentlyContinue |
    Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-1) } |
    Remove-Item -Force -ErrorAction SilentlyContinue

$zipPath = Join-Path $OutDir "diagnostics-$stamp.zip"
if (Test-Path $zipPath) { Remove-Item $zipPath -Force -ErrorAction SilentlyContinue }
try {
    Compress-Archive -Path (Join-Path $stage "*") -DestinationPath $zipPath -Force
} catch {
    Info "zip FAILED: $($_.Exception.Message)"
    Info "staging kept at: $stage"
    exit 1
}
Remove-Item -Recurse -Force $stage -ErrorAction SilentlyContinue
$sizeMb = "{0:N1} MB" -f ((Get-Item $zipPath).Length / 1MB)
Info "bundle ready: $sizeMb, $($collected.Count) items"

# 9. optional mail (manual export path)
if ($Mail) {
    $account = $cfgAll["mail.smtp.account"]
    $auth    = $cfgAll["mail.smtp.auth-code"]
    if (-not $account -or -not $auth) {
        Info "mail not configured (mail.smtp.account/auth-code missing); zip kept on disk only"
    } else {
        $host_ = if ($cfgAll["mail.smtp.host"]) { $cfgAll["mail.smtp.host"] } else { "smtp.163.com" }
        $port  = if ($cfgAll["mail.smtp.port"]) { [int]$cfgAll["mail.smtp.port"] } else { 25 }
        $ssl   = $cfgAll["mail.smtp.ssl"] -ne "false"
        $mp    = Read-PropsRaw (Join-Path $dataDir "mail.properties")
        $rcpt  = if ($mp["recipient"]) { $mp["recipient"] }
                 elseif ($cfgAll["mail.recipient"]) { $cfgAll["mail.recipient"] }
                 else { $account }
        try {
            $sec = ConvertTo-SecureString $auth -AsPlainText -Force
            $cred = New-Object System.Management.Automation.PSCredential($account, $sec)
            Send-MailMessage -From $account -To $rcpt `
                -Subject "[Diagnostics] bundle from $env:COMPUTERNAME $stamp" `
                -Body "Manual diagnostics export. See attachment (logs/configs/db, credentials redacted)." `
                -SmtpServer $host_ -Port $port -UseSsl:$ssl -Credential $cred `
                -Attachments $zipPath -Encoding UTF8 -ErrorAction Stop
            Info "mail sent to $rcpt"
        } catch {
            Info "mail send FAILED: $($_.Exception.Message)"
            Info "zip kept at: $zipPath (transfer it manually)"
        }
    }
}

# contract: last non-empty stdout line = bare zip path
Write-Host $zipPath
