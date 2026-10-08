# launcher.ps1 - Customer distribution launcher (ADR-0016)
#
# Responsibilities:
#   0. Machine precheck: x64 architecture + VT virtualization; popup with specific
#      reason and exit when unsupported (dependency closure items)
#   1. Verify bundled JRE (runtime/jre/bin/java.exe) exists; else show error and exit
#   2. Auto-detect MuMu install path -> adb.path and MuMuManager.exe
#      (MuMuManager lives in shell\ on classic layout, <root>\nx_main\ on nx layout)
#      2d. MuMu totally not found (registry/common dirs/PATH all miss) -> guided
#          install popup and exit
#   3. Auto-detect ADB device port + MuMu instance index
#      3d. Enforce instance resolution 1080x1920@280 portrait (WeChatUi.java fixed
#          coordinates assume it): read MuMu boot config, auto-write custom profile
#          when mismatched; if the VM is running with wrong live resolution, restart
#          MuMu to apply; popup + exit when unfixable
#   4. Write results to bot/data/runtime.properties (adb.path / adb.device / mumu.instance)
#   5. Start watchdog/watchdog.ps1 in hidden window
#   6. Wait for AdminServer (port 9173) and open browser
#
# Invoked by start.vbs (hidden); for troubleshooting run: powershell -File launcher.ps1
# All milestones go to logs\launcher.log too (console is hidden by start.vbs).

$ErrorActionPreference = "Continue"
$root = $PSScriptRoot
$botDir = Join-Path $root "bot"
$runtimeProp = Join-Path $botDir "data\runtime.properties"
$logFile = Join-Path $root "logs\launcher.log"

# ===== Helper: log to console + logs\launcher.log =====
function Log($msg) {
    $line = "[{0}] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $msg
    Write-Host $line
    try {
        $dir = Split-Path -Path $logFile -Parent
        if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
        Add-Content -Path $logFile -Value $line -Encoding UTF8
    } catch {}
}

Log "[launcher] === launcher start (pid $PID) ==="

# ===== Helper: visible error popup (works even when started hidden by start.vbs) =====
function Show-ErrorPopup($title, $msg) {
    Log ("[popup] {0}: {1}" -f $title, ($msg -replace "`r?`n", " | "))
    try {
        Add-Type -AssemblyName PresentationFramework
        [System.Windows.MessageBox]::Show($msg, $title, "OK", "Error") | Out-Null
    } catch {}
}

# ===== 0a. Architecture precheck: MuMu only runs on x64 (Intel/AMD) =====
function Get-ArchProblem {
    $arch = $env:PROCESSOR_ARCHITECTURE
    if ($arch -eq "AMD64") { return $null }
    if ($arch -eq "ARM64") {
        return "This program requires a computer with an Intel or AMD processor (x64).`n`nThe current computer is ARM architecture (e.g. Snapdragon chip); MuMu emulator does not support ARM, so it cannot run.`n`nPlease switch to a regular x64 computer and run this program again."
    }
    return "This program requires a 64-bit x64 (Intel/AMD) system.`n`nThe current computer architecture is $arch, so it cannot run."
}

# ===== 0b. VT virtualization precheck: MuMu emulator depends on CPU virtualization =====
function Get-VtProblem {
    # A running hypervisor (Hyper-V/WSL2 etc.) means firmware VT is already on and taken
    # over by the hypervisor; when Hyper-V is enabled VirtualizationFirmwareEnabled reads
    # false (masked), so HypervisorPresent must be checked first
    $cs = Get-CimInstance Win32_ComputerSystem -ErrorAction SilentlyContinue
    if ($cs -and $cs.HypervisorPresent) { return $null }
    # No hypervisor: check the firmware VT switch
    $cpu = Get-CimInstance Win32_Processor -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cpu -and $cpu.VirtualizationFirmwareEnabled) { return $null }
    return "MuMu emulator requires CPU virtualization (VT), which is not enabled on this computer, so the emulator cannot start.`n`nPlease do the following, then rerun start.vbs:`n  1. Restart the computer and enter the BIOS (usually tap Del or F2 repeatedly during boot)`n  2. Find CPU-related options under Advanced / CPU Configuration`n  3. Enable Intel Virtualization Technology (Intel VT-x) or SVM Mode (AMD)`n  4. Save and exit (F10), then rerun start.vbs once Windows is up"
}

$archProblem = Get-ArchProblem
if ($archProblem) { Show-ErrorPopup "Startup environment unsupported" $archProblem; exit 1 }

$vtProblem = Get-VtProblem
if ($vtProblem) { Show-ErrorPopup "Startup environment unsupported" $vtProblem; exit 1 }

Log "[launcher] precheck ok (x64 + VT)"

# ===== 1. JRE check =====
$javaExe = Join-Path $root "runtime\jre\bin\java.exe"
if (-not (Test-Path $javaExe)) {
    Show-ErrorPopup "Startup failed" "Bundled JRE not found: $javaExe`n`nThe runtime\jre folder is missing in the package; please re-download the complete package."
    exit 1
}

# ===== Helper: write runtime.properties, preserving other keys =====
function Write-RuntimeProps($adbPath, $device, $mumuInstance) {
    $rp = @{}
    if (Test-Path $runtimeProp) {
        Get-Content $runtimeProp -Encoding UTF8 | ForEach-Object {
            $line = $_.Trim()
            if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
                $idx = $line.IndexOf("=")
                $key = $line.Substring(0, $idx).Trim()
                $val = $line.Substring($idx + 1).Trim()
                if ($val) { $rp[$key] = $val }
            }
        }
    }
    if ($adbPath) { $rp["adb.path"] = $adbPath } else { $rp.Remove("adb.path") }
    if ($device)  { $rp["adb.device"] = $device } else { $rp.Remove("adb.device") }
    if ($null -ne $mumuInstance) { $rp["mumu.instance"] = "$mumuInstance" }

    $parent = Split-Path $runtimeProp -Parent
    if (-not (Test-Path $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    $lines = @(
        "# Runtime config override (launcher writes adb.path/adb.device/mumu.instance; Admin /config page can edit)",
        "# Never writes to config.properties; delete this file to revert to factory defaults"
    )
    foreach ($key in $rp.Keys) { $lines += "$key=$($rp[$key])" }
    Set-Content -Path $runtimeProp -Value $lines -Encoding UTF8
}

# ===== 2. Detect MuMu install path / adb.exe =====
$adbPath = $null
$mumuManager = $null

# 2a. Registry (MuMu 12 installer writes here)
$regPaths = @(
    "HKLM:\SOFTWARE\WOW6432Node\Netease\MuMu12",
    "HKLM:\SOFTWARE\Netease\MuMu12",
    "HKCU:\SOFTWARE\Netease\MuMu12"
)
foreach ($rp in $regPaths) {
    try {
        $item = Get-ItemProperty -Path $rp -ErrorAction SilentlyContinue
        if ($item) {
            $installPath = $item.InstallPath
            if (-not $installPath) { $installPath = $item.InstallDir }
            if ($installPath -and (Test-Path $installPath)) {
                $candidate = Join-Path $installPath "shell\adb.exe"
                if (Test-Path $candidate) { $adbPath = $candidate; break }
            }
        }
    } catch {}
}

# 2b. Common install directories
if (-not $adbPath) {
    $commonDirs = @(
        "C:\Program Files\Netease\MuMu Player 12",
        "C:\Program Files (x86)\Netease\MuMu Player 12",
        "D:\Program Files\Netease\MuMu Player 12",
        "D:\Program Files (x86)\Netease\MuMu Player 12",
        "E:\Program Files\Netease\MuMu Player 12",
        "F:\Program Files\Netease\MuMu Player 12"
    )
    foreach ($d in $commonDirs) {
        $candidate = Join-Path $d "shell\adb.exe"
        if (Test-Path $candidate) { $adbPath = $candidate; break }
    }
}

# 2c. PATH lookup (fallback: customer may have generic platform-tools)
if (-not $adbPath) {
    $pathAdb = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($pathAdb) { $adbPath = $pathAdb.Source }
}

# Derive MuMuManager.exe path (same dir as adb.exe)
if ($adbPath) {
    $shellDir = Split-Path -Path $adbPath -Parent
    $mgrCandidate = Join-Path $shellDir "MuMuManager.exe"
    if (Test-Path $mgrCandidate) { $mumuManager = $mgrCandidate }
    else {
        # MuMu 12 nx layout: MuMuManager.exe lives in <install root>\nx_main\, not shell\
        $rootDir = Split-Path -Path $shellDir -Parent
        if ($rootDir) {
            $mgrNx = Join-Path $rootDir "nx_main\MuMuManager.exe"
            if (Test-Path $mgrNx) { $mumuManager = $mgrNx }
        }
    }
}

Log ("[launcher] adb.path={0}" -f $adbPath)
Log ("[launcher] MuMuManager={0}" -f $mumuManager)

# ===== 2d. MuMu not found at all -> guided install (popup + exit) =====
if (-not $adbPath) {
    Show-ErrorPopup "MuMu emulator not detected" "MuMu Player 12 is not installed on this computer (or it is installed in a very unusual location and cannot be auto-detected).`n`nPlease do the following, then rerun start.vbs:`n  1. Download and install MuMu Player 12: https://mumu.163.com/`n  2. After installing, open MuMu once, confirm you can reach the emulator desktop, then close it`n  3. Rerun start.vbs`n`nIf MuMu is already installed but this still appears, send the MuMu install path to technical support."
    exit 1
}

# ===== 3. Detect ADB device port =====
$device = $null
$mumuIndex = $null

# 3a. MuMuManager info -v all (authoritative)
if ($mumuManager) {
    try {
        $raw = (& $mumuManager info -v all 2>&1 | Out-String).Trim()
        $j = $raw | ConvertFrom-Json -ErrorAction Stop
        $instances = if ($j -is [System.Array]) { $j } else { @($j) }
        foreach ($inst in $instances) {
            if ($inst.player_state -eq "start_finished" -or $inst.player_state -eq "running") {
                $port = $null
                if ($inst.adb_port) { $port = [int]$inst.adb_port }
                elseif ($inst.vm_port) { $port = [int]$inst.vm_port }
                if ($port) {
                    $device = "127.0.0.1:$port"
                    try { if ($null -ne $inst.index) { $mumuIndex = [int]$inst.index } } catch {}
                    break
                }
            }
        }
    } catch {
        Log ("[launcher] MuMuManager info parse failed, falling back to brute-force: {0}" -f $_.Exception.Message)
    }
}

# 3b. Brute-force TCP scan (instances 0-10, step 32)
if (-not $device) {
    for ($port = 16384; $port -le 16704; $port += 32) {
        try {
            $sock = New-Object System.Net.Sockets.TcpClient
            $iar = $sock.BeginConnect("127.0.0.1", $port, $null, $null)
            $ok = $iar.AsyncWaitHandle.WaitOne(500, $false)
            $sock.EndConnect($iar)
            if ($ok) { $device = "127.0.0.1:$port"; $sock.Close(); break }
            $sock.Close()
        } catch {
            try { $sock.Close() } catch {}
        }
    }
}

# 3c. Fallback to factory default (instance 0)
if (-not $device) { $device = "127.0.0.1:16384" }

# Unify instance index: prefer info JSON's index of the running instance; else derive
# from port (16384=0, 16416=1, ... step 32); else 0. Written to runtime.properties as
# mumu.instance so watchdog checks/controls the SAME instance (no more hardcoded 0).
if ($null -eq $mumuIndex) {
    if ($device -match ":(\d+)\s*$") {
        $p = [int]$Matches[1]
        $d = [math]::Floor(($p - 16384) / 32)
        if (($p - 16384) -ge 0 -and (($p - 16384) % 32) -eq 0 -and $d -ge 0 -and $d -le 10) { $mumuIndex = $d }
    }
    if ($null -eq $mumuIndex) { $mumuIndex = 0 }
}
Log ("[launcher] adb.device={0}" -f $device)
Log ("[launcher] mumu.instance={0}" -f $mumuIndex)

# ===== 3d. Enforce resolution 1080x1920@280 portrait (WeChatUi fixed-coordinate prerequisite) =====
# WeChatUi.java hardcodes input box / send button coordinates for 1080x1920@280; any
# drift breaks sending. Two layers, neither replaces the other:
#   a) boot config (MuMuManager setting): written as custom 1080x1920@280, applies on next boot;
#   b) live VM (adb shell wm size / wm density): ground truth of the RUNNING emulator;
#      if wrong while config is already correct, restart MuMu to apply.
# Caution: MuMu's settings JSON may disagree with the running VM (observed: config said
# tablet landscape while VM was actually portrait), so both must be verified.

function Read-MuMuSettings($mgr, $idx) {
    try {
        $raw = (& $mgr setting -v $idx -a 2>&1 | Out-String)
        return $raw | ConvertFrom-Json -ErrorAction Stop
    } catch { return $null }
}

function Get-LiveResolution($dev) {
    try {
        $sizeOut = (& $adbPath -s $dev shell wm size 2>&1 | Out-String)
        if ($sizeOut -match "Physical size:\s*(\d+)x(\d+)") {
            $w = [int]$Matches[1]; $h = [int]$Matches[2]
            $d = $null
            $denOut = (& $adbPath -s $dev shell wm density 2>&1 | Out-String)
            if ($denOut -match "Physical density:\s*(\d+)") { $d = [int]$Matches[1] }
            return @{ W = $w; H = $h; Density = $d }
        }
    } catch {}
    return $null
}

if ($mumuManager -and (Test-Path $mumuManager)) {
    $targetW = 1080; $targetH = 1920; $targetDpi = 280

    # --- a) boot config calibration ---
    $mset = Read-MuMuSettings $mumuManager $mumuIndex
    if ($mset) {
        $cw = 0.0; $ch = 0.0; $cd = 0.0
        try {
            $cw = [double]$mset.resolution_width
            $ch = [double]$mset.resolution_height
            $cd = [double]$mset.resolution_dpi
        } catch {}
        if ($cw -ne $targetW -or $ch -ne $targetH -or $cd -ne $targetDpi) {
            Log ("[launcher] resolution config mismatch (mode={0}, {1}x{2}@{3}) -> writing custom {4}x{5}@{6}" -f $mset.resolution_mode, $cw, $ch, $cd, $targetW, $targetH, $targetDpi)
            & $mumuManager setting -v $mumuIndex -k resolution_mode -val custom `
                -k resolution_width.custom -val $targetW `
                -k resolution_height.custom -val $targetH `
                -k resolution_dpi.custom -val $targetDpi 2>&1 | Out-Null
            $mset2 = Read-MuMuSettings $mumuManager $mumuIndex
            $cfgOk = $false
            if ($mset2) {
                try {
                    $cfgOk = ([string]$mset2.resolution_mode -eq "custom") -and `
                             ([double]$mset2.resolution_width -eq $targetW) -and `
                             ([double]$mset2.resolution_height -eq $targetH) -and `
                             ([double]$mset2.resolution_dpi -eq $targetDpi)
                } catch {}
            }
            if (-not $cfgOk) {
                Show-ErrorPopup "MuMu resolution not supported" "The bot requires the MuMu screen to be 1080x1920 portrait (DPI 280), but it could not be set automatically.`n`nPlease set the resolution manually in MuMu 'Settings' - 'Display' to phone: 1080x1920 (DPI 280), restart the emulator, then rerun start.vbs."
                exit 1
            }
            Log ("[launcher] resolution config now custom {0}x{1}@{2}" -f $targetW, $targetH, $targetDpi)
        } else {
            Log ("[launcher] resolution config ok (mode={0}, {1}x{2}@{3})" -f $mset.resolution_mode, $cw, $ch, $cd)
        }
    } else {
        Log "[launcher] MuMuManager setting unreadable; skip boot-config check"
    }

    # --- b) live VM calibration ---
    $running = $false
    try {
        $j = (& $mumuManager info -v $mumuIndex 2>&1 | Out-String) | ConvertFrom-Json -ErrorAction Stop
        $running = ([string]$j.player_state -eq "start_finished")
    } catch {}
    if ($running) {
        $live = Get-LiveResolution $device
        if ($live -and ($live.W -ne $targetW -or $live.H -ne $targetH -or ($null -ne $live.Density -and $live.Density -ne $targetDpi))) {
            Log ("[launcher] live VM {0}x{1}@{2} != target {3}x{4}@{5} -> restart MuMu to apply" -f $live.W, $live.H, $live.Density, $targetW, $targetH, $targetDpi)
            & $mumuManager control -v $mumuIndex restart 2>&1 | Out-Null
            $deadline = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + 120000
            $booted = $false
            while ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() -lt $deadline) {
                Start-Sleep -Seconds 3
                $st = ""
                try {
                    $j2 = (& $mumuManager info -v $mumuIndex 2>&1 | Out-String) | ConvertFrom-Json -ErrorAction Stop
                    $st = [string]$j2.player_state
                } catch {}
                if ($st -eq "start_finished") {
                    # adb may lag a few seconds behind start_finished; retry a few rounds
                    for ($k = 0; $k -lt 5; $k++) {
                        $live2 = Get-LiveResolution $device
                        if ($live2 -and $live2.W -eq $targetW -and $live2.H -eq $targetH) { $booted = $true; break }
                        Start-Sleep -Seconds 2
                    }
                    break
                }
            }
            if (-not $booted) {
                Show-ErrorPopup "MuMu resolution not supported" "The bot requires the MuMu screen to be 1080x1920 portrait (DPI 280). The resolution was corrected and the emulator restarted, but verification failed.`n`nPlease set the resolution manually in MuMu 'Settings' - 'Display' to phone: 1080x1920 (DPI 280), restart the emulator, then rerun start.vbs."
                exit 1
            }
            Log ("[launcher] live VM is now {0}x{1} after restart" -f $targetW, $targetH)
        } elseif ($live) {
            Log ("[launcher] live VM ok ({0}x{1}@{2})" -f $live.W, $live.H, $live.Density)
        } else {
            Log "[launcher] wm size unavailable; skip live-resolution check"
        }
    }
}

# ===== 4. Write runtime.properties =====
Write-RuntimeProps $adbPath $device $mumuIndex

# ===== 5. Start watchdog (hidden window) =====
$watchdog = Join-Path $root "watchdog\watchdog.ps1"
if (-not (Test-Path $watchdog)) {
    Log ("[launcher] watchdog.ps1 not found: {0}" -f $watchdog)
    exit 1
}
Start-Process -FilePath "powershell.exe" `
    -ArgumentList "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "`"$watchdog`"" `
    -WindowStyle Hidden

# ===== 6. Wait for AdminServer (port 9173) before opening browser =====
# The bot needs a few seconds to boot; opening the browser earlier shows
# ERR_CONNECTION_REFUSED. Poll the port for up to 90s, then open anyway.
$ready = $false
for ($i = 0; $i -lt 45; $i++) {
    Start-Sleep -Seconds 2
    $sock = $null
    try {
        $sock = New-Object System.Net.Sockets.TcpClient
        $iar = $sock.BeginConnect("127.0.0.1", 9173, $null, $null)
        if ($iar.AsyncWaitHandle.WaitOne(500, $false) -and $sock.Connected) { $ready = $true }
    } catch {}
    if ($sock) { try { $sock.Close() } catch {} }
    if ($ready) { break }
}

# ===== 7. Open browser =====
Start-Process "http://127.0.0.1:9173/"
if ($ready) {
    Log "[launcher] AdminServer is up, browser opened to http://127.0.0.1:9173/"
} else {
    Log "[launcher] AdminServer not listening after 90s; browser opened anyway - check bot/logs/"
}
