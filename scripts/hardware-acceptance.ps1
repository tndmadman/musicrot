param(
    [string]$OutputDir = "hardware-acceptance",
    [string]$Encoder = "auto",
    [switch]$SkipFullRender
)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
Write-Host 'ChordFlow Windows hardware acceptance - no publishing' -ForegroundColor Cyan
foreach ($app in @('java','javac','ffmpeg','ffprobe')) {
    if (-not (Get-Command $app -ErrorAction SilentlyContinue)) { throw "DEPENDENCY_UNAVAILABLE: $app" }
}
$version = (& javac -version 2>&1 | Out-String).Trim()
if ($version -notmatch '^javac 21\.') { throw "JDK 21 required: $version" }
if (Get-Command nvidia-smi -ErrorAction SilentlyContinue) {
    & nvidia-smi --query-gpu=name,driver_version,memory.total --format=csv
} else { Write-Warning 'NVENC hardware verification cannot be certified without nvidia-smi' }
& "$PSScriptRoot/build.ps1"
if ($LASTEXITCODE -ne 0) { throw 'BUILD_FAILED' }
& java '-Djava.awt.headless=true' -cp build chordflow.Main selftest
if ($LASTEXITCODE -ne 0) { throw 'SELFTEST_FAILED' }
New-Item -Force -ItemType Directory -Path $OutputDir | Out-Null
$scenarios = @(
    @{ Seed=8811; Mode='flow'; Theme='gold'; Instrument='warm-pad' },
    @{ Seed=8812; Mode='guitar'; Theme='blue'; Instrument='nylon-pluck' }
)
$results = [System.Collections.Generic.List[object]]::new()
foreach ($scenario in $scenarios) {
    $seconds=60;$width=1080;$height=1920
    if ($SkipFullRender) { $seconds=10;$width=720;$height=1280 }
    $before=@(Get-ChildItem $OutputDir -Filter '*.mp4' | Select-Object -ExpandProperty FullName)
    $clock=[System.Diagnostics.Stopwatch]::StartNew()
    & java '-Djava.awt.headless=true' -cp build chordflow.Main render --seed $scenario.Seed --seconds $seconds --mode $scenario.Mode --theme $scenario.Theme --instrument $scenario.Instrument --width $width --height $height --fps 30 --encoder $Encoder --out $OutputDir
    $clock.Stop()
    if ($LASTEXITCODE -ne 0) { throw "RENDER_FAILED: $($scenario.Mode)" }
    $videos=@(Get-ChildItem $OutputDir -Filter '*.mp4' | Where-Object { $_.FullName -notin $before })
    if ($videos.Count -ne 1) { throw 'OUTPUT_COUNT_UNEXPECTED' }
    $video=$videos[0]
    $probe=& ffprobe -v error -show_entries stream=codec_name,codec_type,width,height,avg_frame_rate:format=duration -of json $video.FullName | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { throw 'FFPROBE_FAILED' }
    $sha=(Get-FileHash -Algorithm SHA256 $video.FullName).Hash.ToLowerInvariant()
    $manifest=[IO.Path]::ChangeExtension($video.FullName,'.manifest.json')
    if (-not (Test-Path $manifest)) { throw 'MANIFEST_MISSING' }
    if ((Get-Content -Raw $manifest) -notmatch $sha) { throw 'MANIFEST_SHA_MISMATCH' }
    $results.Add([pscustomobject]@{mode=$scenario.Mode;instrument=$scenario.Instrument;video=$video.Name;sha256=$sha;render_seconds=[math]::Round($clock.Elapsed.TotalSeconds,2);ffprobe=$probe})
}
$results | ConvertTo-Json -Depth 12 | Set-Content -Encoding UTF8 "$OutputDir/acceptance-report.json"
Write-Host "Acceptance run report: $OutputDir/acceptance-report.json"
Write-Host 'Manual musical and phone-size visual review still required.'
