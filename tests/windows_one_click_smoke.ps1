# Windows-only integration: exercise the actual one-click launcher with a real controller and worker.
# CHORDFLOW_NO_BROWSER suppresses a GUI browser in CI; normal double-click still opens it.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
$env:CHORDFLOW_NO_BROWSER = '1'
$dir = Join-Path $root 'chordflow-data'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$stdout = Join-Path $dir 'windows-ci-launcher.out.log'
$stderr = Join-Path $dir 'windows-ci-launcher.err.log'

function ChordFlowProcesses {
    return @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -match '^javaw?\.exe$' -and $_.CommandLine -match '(?i)chordflow\.Main\s+(serve|worker)(\s|$)'
    })
}

$before = @(ChordFlowProcesses | ForEach-Object { $_.ProcessId })
$launcher = $null
try {
    $launcher = Start-Process -FilePath 'cmd.exe' -ArgumentList '/d /c command_center_windows.bat' -WorkingDirectory $root -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $data = $null
    for ($i=0; $i -lt 100; $i++) {
        try {
            $data = Invoke-RestMethod -Uri 'http://127.0.0.1:8791/api/jobs' -TimeoutSec 2
            if ($null -ne $data.jobs) { break }
        } catch {}
        Start-Sleep -Milliseconds 500
    }
    if ($null -eq $data -or $null -eq $data.jobs) { throw 'ONE_CLICK_CONTROLLER_UNAVAILABLE' }

    $workerFound = $false
    for ($i=0; $i -lt 100; $i++) {
        $workerFound = @((ChordFlowProcesses) | Where-Object { $_.CommandLine -match '(?i)chordflow\.Main\s+worker(\s|$)' }).Count -gt 0
        if ($workerFound) { break }
        Start-Sleep -Milliseconds 500
    }
    if (-not $workerFound) { throw 'ONE_CLICK_WORKER_NOT_RUNNING' }

    $seed = 984211
    $response = Invoke-RestMethod -Method POST -Uri "http://127.0.0.1:8791/api/enqueue?count=1&seed=$seed&seconds=2&mode=flow&theme=gold&width=720&height=1280&encoder=x264&instrument=warm-pad"
    if (-not $response.ids) { throw 'ONE_CLICK_ENQUEUE_FAILED' }
    $jobId = [string]$response.ids
    $state = ''
    for ($i=0; $i -lt 150; $i++) {
        $jobs = (Invoke-RestMethod -Uri 'http://127.0.0.1:8791/api/jobs').jobs
        $job = @($jobs | Where-Object { $_.id -eq $jobId }) | Select-Object -First 1
        if ($job) {
            $state = $job.state
            if ($state -eq 'READY' -or $state -eq 'FAILED') { break }
        }
        Start-Sleep -Seconds 1
    }
    if ($state -ne 'READY') { throw "ONE_CLICK_RENDER_NOT_READY: $state" }
    Write-Host "PASS: command_center_windows.bat started controller and worker; job $jobId rendered and passed READY media QA."
} catch {
    if (Test-Path $stdout) { Get-Content $stdout -Tail 50 }
    if (Test-Path $stderr) { Get-Content $stderr -Tail 50 }
    $launcherLog = Join-Path $dir 'windows-launcher.log'
    if (Test-Path $launcherLog) { Get-Content $launcherLog -Tail 50 }
    throw
} finally {
    $new = @(ChordFlowProcesses | Where-Object { $before -notcontains $_.ProcessId })
    foreach ($p in $new) {
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
    }
    if ($launcher -and -not $launcher.HasExited) {
        Stop-Process -Id $launcher.Id -Force -ErrorAction SilentlyContinue
    }
    # Clean up only the new worker cmd console launched by this test checkout.
    Get-CimInstance Win32_Process | Where-Object {
        $_.Name -eq 'cmd.exe' -and $_.CommandLine -match 'Start-Worker\.bat'
    } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}
