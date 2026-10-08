# pack.ps1 - Assemble customer distribution package (ADR-0016)
#
# Usage: powershell -ExecutionPolicy Bypass -File pack.ps1 [-CustomerName <name>] [-Suffix <s>] [-SkipJre] [-NoZip]
#   -CustomerName  output dir prefix, default "Derien-Robot"
#   -Suffix        output dir name suffix (e.g. v2 -> Derien-Robot-20261005-v2)
#   -SkipJre       skip copying runtime/jre (for testing exclude list only)
#   -NoZip         skip the final auto-zip step
#
# Output: ..\<CustomerName>-<date>[-suffix]\  (one level above repo to avoid polluting it)
#         Plus a ready-to-send sibling zip <outdir>.zip (unless -NoZip).

param(
    [string]$CustomerName = "Derien-Robot",
    [string]$Suffix = "",
    [switch]$SkipJre,
    [switch]$NoZip
)

$ErrorActionPreference = "Stop"
$repoRoot = $PSScriptRoot
$dateStr = Get-Date -Format "yyyyMMdd"
if ($Suffix -ne "") { $dateStr = "$dateStr-$Suffix" }
$outRoot = Join-Path (Split-Path $repoRoot -Parent) "$CustomerName-$dateStr"

Write-Host "=== Assembling customer distribution package ==="
Write-Host "Source:  $repoRoot"
Write-Host "Output:  $outRoot"
Write-Host ""

# Clean old output (retry: fresh-killed processes release handles with delay)
if (Test-Path $outRoot) {
    Write-Host "Cleaning old output dir..."
    $removed = $false
    foreach ($attempt in 1..3) {
        try {
            Remove-Item -Recurse -Force $outRoot -ErrorAction Stop
            $removed = $true
            break
        } catch {
            Write-Host "  attempt ${attempt}/3 failed: $($_.Exception.Message.Split("`n")[0])"
            Start-Sleep -Seconds 3
        }
    }
    if (-not $removed) {
        throw "Cannot delete $outRoot - a process (console window / Explorer) is using it. Close it and retry, or run with -Suffix <name> to output elsewhere."
    }
}
New-Item -ItemType Directory -Path $outRoot | Out-Null

# ===== Whitelist: directories to copy =====
$includeDirs = @(
    "java_sdk\WeWorkFinanceSdk",
    "docs\pem",
    "bot\lib",
    "bot\out",
    "watchdog\bin"
)
$includeFiles = @(
    "bot\run-only.bat",
    "bot\build.bat",
    "bot\config.properties",
    "bot\data\ai_prompt.txt",
    "watchdog\watchdog.ps1",
    "launcher.ps1",
    "export-diagnostics.ps1",
    "export-diagnostics.bat",
    "start.vbs",
    "stop.bat",
    "resume.bat",
    "README.txt"
)
$includeDataDirs = @(
    "bot\data\kb",
    "bot\data\media"
)

# 1. Copy directories
foreach ($dir in $includeDirs) {
    $src = Join-Path $repoRoot $dir
    $dst = Join-Path $outRoot $dir
    if (Test-Path $src) {
        Write-Host "[DIR] $dir"
        New-Item -ItemType Directory -Path (Split-Path $dst -Parent) -Force | Out-Null
        Copy-Item -Recurse -Force $src $dst
    } else {
        Write-Warning "Source dir not found, skipping: $src"
    }
}

# 2. Copy files
foreach ($f in $includeFiles) {
    $src = Join-Path $repoRoot $f
    $dst = Join-Path $outRoot $f
    if (Test-Path $src) {
        Write-Host "[FILE] $f"
        New-Item -ItemType Directory -Path (Split-Path $dst -Parent) -Force | Out-Null
        Copy-Item -Force $src $dst
    } else {
        Write-Warning "Source file not found, skipping: $src"
    }
}

# 3. Copy data/kb (content) and data/media (empty dir only)
foreach ($d in $includeDataDirs) {
    $src = Join-Path $repoRoot $d
    $dst = Join-Path $outRoot $d
    if (Test-Path $src) {
        New-Item -ItemType Directory -Path $dst -Force | Out-Null
        if ($d -eq "bot\data\kb") {
            Get-ChildItem $src -File | ForEach-Object {
                Copy-Item -Force $_.FullName (Join-Path $dst $_.Name)
                Write-Host "[KB]   $($_.Name)"
            }
        }
    }
}

# 4. Copy bundled JRE (runtime/jre)
if (-not $SkipJre) {
    $jreSrc = Join-Path $repoRoot "runtime\jre"
    $jreDst = Join-Path $outRoot "runtime\jre"
    if (Test-Path $jreSrc) {
        Write-Host "[JRE]  runtime/jre (~180MB, please wait)..."
        New-Item -ItemType Directory -Path (Split-Path $jreDst -Parent) -Force | Out-Null
        Copy-Item -Recurse -Force $jreSrc $jreDst
    } else {
        Write-Warning "runtime/jre not found, skipping JRE bundle."
        Write-Warning "Before real packaging, extract Adoptium Temurin 17 JRE to: $jreSrc"
    }
} else {
    Write-Host "[JRE]  skipped (-SkipJre)"
}

# 4.5 Build info (shipped inside the package; diagnostics bundle collects it,
#     so the developer can see which package a customer machine runs)
Set-Content -Path (Join-Path $outRoot "build-info.txt") -Value @(
    "pack-time=$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
    "package=$CustomerName-$dateStr"
) -Encoding ASCII

# 5. Create empty dir placeholders
$emptyDirs = @(
    "bot\logs",
    "bot\data\screenshots"
)
foreach ($d in $emptyDirs) {
    New-Item -ItemType Directory -Path (Join-Path $outRoot $d) -Force | Out-Null
    New-Item -ItemType File -Path (Join-Path $outRoot "$d\.gitkeep") -Force | Out-Null
}

# 6. Blacklist check: ensure sensitive/local files are NOT in the output
$forbidden = @(
    "bot\src",
    "bot\data\bot.db",
    "bot\data\bot.db-shm",
    "bot\data\bot.db-wal",
    "bot\data\runtime.properties",
    "bot\data\heartbeat",
    "bot\data\mumu_events.log",
    "bot\data\tunnel_events.log",
    "bot\data\watchdog_throttle.json",
    "bot\data\mail.properties",
    "bot\data\config_audit.log",
    "bot\data\list_page.xml",
    "bot\WeComSender.java",
    "bot\WeComSender.class",
    "bot\run.bat",
    "bot\run_ai_test.bat",
    "bot\run_kb_import.bat",
    "bot\run_p4_test.bat",
    "bot\run_queue_test.bat",
    "bot\run.log",
    "bot\run.err.log",
    ".trae",
    ".git",
    "docs\adr",
    "docs\agents",
    "docs\plan",
    "docs\scope.md",
    "CONTEXT.md",
    "AGENTS.md",
    "body.json",
    "body2.json",
    "pack.ps1"
)
$violations = @()
foreach ($f in $forbidden) {
    $p = Join-Path $outRoot $f
    if (Test-Path $p) { $violations += $f }
}
if ($violations.Count -gt 0) {
    Write-Host ""
    Write-Host "=== BLACKLIST CHECK FAILED ===" -ForegroundColor Red
    $violations | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
} else {
    Write-Host ""
    Write-Host "=== Blacklist check passed ===" -ForegroundColor Green
}

# 7. Required files check
$required = @(
    "runtime\jre\bin\java.exe",
    "java_sdk\WeWorkFinanceSdk\WeWorkFinanceSdk.dll",
    "docs\pem\private_key.pem",
    "bot\config.properties",
    "bot\run-only.bat",
    "bot\out\com\mumu\bot\Main.class",
    "watchdog\watchdog.ps1",
    "watchdog\bin\plink.exe",
    "launcher.ps1",
    "export-diagnostics.ps1",
    "export-diagnostics.bat",
    "start.vbs",
    "stop.bat",
    "resume.bat",
    "README.txt"
)
$missing = @()
foreach ($f in $required) {
    $p = Join-Path $outRoot $f
    if (-not (Test-Path $p)) { $missing += $f }
}
if ($missing.Count -gt 0) {
    Write-Host ""
    Write-Host "=== MISSING REQUIRED FILES ===" -ForegroundColor Yellow
    $missing | ForEach-Object { Write-Host "  $_" -ForegroundColor Yellow }
} else {
    Write-Host "=== Required files check passed ===" -ForegroundColor Green
}

# 8. Auto-zip: produce a ready-to-send single file next to the output dir
if (-not $NoZip) {
    $zipPath = "$outRoot.zip"
    if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
    Write-Host ""
    Write-Host "=== Zipping (~180MB JRE, please wait)... ==="
    Compress-Archive -Path $outRoot -DestinationPath $zipPath
    $zipSize = "{0:N0} MB" -f ((Get-Item $zipPath).Length / 1MB)
    Write-Host "Zip ready: $zipPath ($zipSize)"
}

Write-Host ""
Write-Host "=== Done ==="
Write-Host "Output: $outRoot"
if (-not $NoZip) { Write-Host "Send:   $outRoot.zip" } else { Write-Host "Next: zip this folder and send to customer." }
