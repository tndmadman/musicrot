param(
    [switch]$SkipWait,
    [string]$Dashboard = 'http://127.0.0.1:8791'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
$logDir = Join-Path $root 'chordflow-data'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$logFile = Join-Path $logDir 'windows-launcher.log'
function Log([string]$line) {
    $message = ('[{0}] {1}' -f (Get-Date -Format o), $line)
    Add-Content -Path $logFile -Value $message
    Write-Host $line
}
try {
    if (-not $SkipWait) {
        # Only open the browser after a valid ChordFlow controller responds.
        $ready = $false
        for ($i = 0; $i -lt 90; $i++) {
            & (Join-Path $PSScriptRoot 'controller-health.ps1')
            if ($LASTEXITCODE -eq 0) { $ready = $true; break }
            Start-Sleep -Milliseconds 500
        }
        if (-not $ready) { throw 'CONTROLLER_STARTUP_TIMEOUT: no ChordFlow command center on port 8791' }
    } else {
        & (Join-Path $PSScriptRoot 'controller-health.ps1')
        if ($LASTEXITCODE -ne 0) { throw 'CONTROLLER_NOT_READY' }
    }

    # Repeated double-clicks should not create additional local Java worker claim loops.
    # We inspect the actual Java command line rather than a guessed open TCP port.
    $workers = @(Get-CimInstance Win32_Process -ErrorAction Stop |
        Where-Object { $_.Name -match '^javaw?\.exe$' -and $_.CommandLine -match '(?i)chordflow\.Main\s+worker(\s|$)' })
    if ($workers.Count -gt 0) {
        Log "Existing ChordFlow worker detected (PID $($workers[0].ProcessId)); reusing it."
    } else {
        $workerBatch = Join-Path $root 'Start-Worker.bat'
        if (-not (Test-Path $workerBatch)) { throw 'WORKER_LAUNCHER_MISSING' }
        $arguments = '/k call "' + $workerBatch + '"'
        Start-Process -FilePath 'cmd.exe' -ArgumentList $arguments -WorkingDirectory $root
        Log 'Launched ChordFlow production worker console.'
    }
    Start-Process -FilePath $Dashboard
    Log "Opened command center: $Dashboard"
    exit 0
} catch {
    Log ("STARTUP_ERROR: " + $_.Exception.Message)
    exit 1
}
