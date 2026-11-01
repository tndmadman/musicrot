param(
    [switch]$SkipWait,
    [switch]$NoBrowser,
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
function RunningWorkers {
    # Inspect the actual running Java process, not the worker's idle/leased job state.
    return @(Get-CimInstance Win32_Process -ErrorAction Stop |
        Where-Object { $_.Name -match '^javaw?\.exe$' -and $_.CommandLine -match '(?i)chordflow\.Main\s+worker(\s|$)' })
}
$mutex = $null
$lockHeld = $false
try {
    if (-not $SkipWait) {
        $ready = $false
        for ($i = 0; $i -lt 90; $i++) {
            & (Join-Path $PSScriptRoot 'controller-health.ps1')
            if ($LASTEXITCODE -eq 0) { $ready = $true; break }
            Start-Sleep -Milliseconds 500
        }
        if (-not $ready) { throw 'CONTROLLER_STARTUP_TIMEOUT: ChordFlow was not ready on port 8791' }
    } else {
        & (Join-Path $PSScriptRoot 'controller-health.ps1')
        if ($LASTEXITCODE -ne 0) { throw 'CONTROLLER_NOT_READY' }
    }

    # Concurrent double-clicks cannot both decide to start a new worker.
    $mutex = [System.Threading.Mutex]::new($false, 'Local\ChordFlowStartWorker')
    $lockHeld = $mutex.WaitOne(30000)
    if (-not $lockHeld) { throw 'WORKER_START_LOCK_TIMEOUT' }
    $workers = @(RunningWorkers)
    if ($workers.Count -gt 0) {
        Log "Existing ChordFlow worker detected (PID $($workers[0].ProcessId)); reusing it."
    } else {
        $workerBatch = Join-Path $root 'Start-Worker.bat'
        if (-not (Test-Path $workerBatch)) { throw 'WORKER_LAUNCHER_MISSING' }
        $arguments = '/k call "' + $workerBatch + '"'
        Start-Process -FilePath 'cmd.exe' -ArgumentList $arguments -WorkingDirectory $root
        $found = $false
        for ($i = 0; $i -lt 40; $i++) {
            if (@(RunningWorkers).Count -gt 0) { $found = $true; break }
            Start-Sleep -Milliseconds 500
        }
        if (-not $found) { throw 'WORKER_DID_NOT_START: check the worker console for Java errors' }
        Log 'ChordFlow production worker is running.'
    }
    $mutex.ReleaseMutex()
    $lockHeld = $false
    if (-not $NoBrowser -and $env:CHORDFLOW_NO_BROWSER -ne '1') {
        Start-Process -FilePath $Dashboard
        Log "Opened command center: $Dashboard"
    } else {
        Log "Command center ready at $Dashboard (browser launch suppressed)"
    }
    exit 0
} catch {
    Log ("STARTUP_ERROR: " + $_.Exception.Message)
    exit 1
} finally {
    if ($lockHeld -and $null -ne $mutex) { $mutex.ReleaseMutex() }
    if ($null -ne $mutex) { $mutex.Dispose() }
}
