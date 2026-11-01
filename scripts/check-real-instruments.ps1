$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$quality = $env:CHORDFLOW_AUDIO_QUALITY

if ($quality -and $quality -notin @('preview','sampled','auto')) {
    Write-Host "ERROR: Invalid CHORDFLOW_AUDIO_QUALITY: $quality"
    exit 1
}
if ($quality -eq 'preview') {
    Write-Host 'Audio quality: SYNTHETIC PREVIEW ONLY (explicitly requested)'
    exit 0
}

$bank = $null
if ($env:CHORDFLOW_SOUNDFONT) {
    $bank = $env:CHORDFLOW_SOUNDFONT
} elseif (Test-Path -LiteralPath 'chordflow-data\soundfont.path' -PathType Leaf) {
    $bank = (Get-Content -LiteralPath 'chordflow-data\soundfont.path' -Raw).Trim()
} else {
    foreach ($candidate in @('soundfonts\default.sf2','soundfonts\GeneralUser-GS.sf2','soundfonts\FluidR3_GM.sf2')) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { $bank=$candidate; break }
    }
}
if (-not $bank -or -not $bank.EndsWith('.sf2',[StringComparison]::OrdinalIgnoreCase) -or -not (Test-Path -LiteralPath $bank -PathType Leaf)) {
    Write-Host ''
    Write-Host 'REAL INSTRUMENTS NOT CONFIGURED' -ForegroundColor Yellow
    Write-Host 'ChordFlow no longer uses synthetic sine-wave tones for published videos.'
    Write-Host 'Select an SF2 sample bank and FluidSynth before starting production:'
    Write-Host '  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\configure-real-instruments.ps1'
    Write-Host 'Or copy an appropriately licensed bank to soundfonts\default.sf2'
    Write-Host 'For the OLD synthesized demo sound only, explicitly set CHORDFLOW_AUDIO_QUALITY=preview.'
    exit 1
}

$exe = $null
if ($env:CHORDFLOW_FLUIDSYNTH) {
    $exe=$env:CHORDFLOW_FLUIDSYNTH
} elseif (Test-Path -LiteralPath 'chordflow-data\fluidsynth.path' -PathType Leaf) {
    $exe=(Get-Content -LiteralPath 'chordflow-data\fluidsynth.path' -Raw).Trim()
} else {
    foreach ($candidate in @('tools\fluidsynth\bin\fluidsynth.exe','tools\fluidsynth\fluidsynth.exe')) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { $exe=$candidate; break }
    }
    if (-not $exe) {
        $command=Get-Command fluidsynth -ErrorAction SilentlyContinue
        if ($command) { $exe=$command.Source }
    }
}
if (-not $exe) {
    Write-Host 'FLUIDSYNTH_NOT_FOUND: Install FluidSynth and run scripts\configure-real-instruments.ps1' -ForegroundColor Yellow
    Write-Host 'Official release: https://github.com/FluidSynth/fluidsynth/releases'
    exit 1
}
try {
    & $exe --version *> $null
    if ($LASTEXITCODE -ne 0) { throw "exit code $LASTEXITCODE" }
} catch {
    Write-Host "FLUIDSYNTH_START_FAILED: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
Write-Host "Production audio: REAL INSTRUMENT SAMPLES ($([IO.Path]::GetFileName($bank)))" -ForegroundColor Green
exit 0
