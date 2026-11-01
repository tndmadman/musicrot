param(
    [string]$SoundFontPath = "",
    [string]$FluidSynthPath = ""
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
Set-Location $repo

Write-Host ''
Write-Host 'ChordFlow Real Instrument Setup (Windows 10 / Java 20+)' -ForegroundColor Cyan
Write-Host 'Choose a properly licensed SF2 sampled-instrument bank.'
Write-Host 'ChordFlow does not ship or automatically download copyrighted sounds.'
Write-Host ''
Write-Host 'Suggested resources (check license for your intended use):'
Write-Host '  https://musescore.org/en/node/101'
Write-Host '  https://github.com/mrbumpy409/GeneralUser-GS'
Write-Host 'FluidSynth Windows releases:'
Write-Host '  https://github.com/FluidSynth/fluidsynth/releases'
Write-Host ''

if ([string]::IsNullOrWhiteSpace($SoundFontPath)) {
    $SoundFontPath = Read-Host 'Enter full path to your .sf2 file (you may drag it into this window)'
}
$SoundFontPath = $SoundFontPath.Trim().Trim('"').Trim("'")
if ([string]::IsNullOrWhiteSpace($SoundFontPath) -or -not (Test-Path -LiteralPath $SoundFontPath -PathType Leaf)) {
    throw 'SOUNDFONT_NOT_FOUND: Select an existing local .sf2 file.'
}
$bank = (Resolve-Path -LiteralPath $SoundFontPath).ProviderPath
if (-not $bank.EndsWith('.sf2',[StringComparison]::OrdinalIgnoreCase)) {
    throw 'SOUNDFONT_FORMAT_INVALID: Select an SF2 file, not an archive or SF3.'
}

$bin = $null
if (-not [string]::IsNullOrWhiteSpace($FluidSynthPath)) {
    $FluidSynthPath = $FluidSynthPath.Trim().Trim('"').Trim("'")
    if (-not (Test-Path -LiteralPath $FluidSynthPath -PathType Leaf)) {
        throw 'FLUIDSYNTH_NOT_FOUND: Select an existing fluidsynth.exe file.'
    }
    $bin = (Resolve-Path -LiteralPath $FluidSynthPath).ProviderPath
} elseif (Get-Command fluidsynth -ErrorAction SilentlyContinue) {
    $bin = (Get-Command fluidsynth).Source
} else {
    foreach ($candidate in @(
        (Join-Path $repo 'tools\fluidsynth\bin\fluidsynth.exe'),
        (Join-Path $repo 'tools\fluidsynth\fluidsynth.exe')
    )) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            $bin = (Resolve-Path -LiteralPath $candidate).ProviderPath
            break
        }
    }
    if (-not $bin) {
        $FluidSynthPath = Read-Host 'Full path to fluidsynth.exe (install from official release, or leave blank)'
        $FluidSynthPath = $FluidSynthPath.Trim().Trim('"').Trim("'")
        if ($FluidSynthPath -and (Test-Path -LiteralPath $FluidSynthPath -PathType Leaf)) {
            $bin = (Resolve-Path -LiteralPath $FluidSynthPath).ProviderPath
        }
    }
}
if (-not $bin) {
    throw 'FLUIDSYNTH_MISSING: Install FluidSynth from its official release, then rerun this script with -FluidSynthPath.'
}
if (-not [System.IO.Path]::GetFileName($bin).Equals('fluidsynth.exe',[StringComparison]::OrdinalIgnoreCase)) {
    throw 'FLUIDSYNTH_EXECUTABLE_INVALID: Select fluidsynth.exe.'
}

try {
    & $bin --version | Select-Object -First 1
    if ($LASTEXITCODE -ne 0) { throw 'Nonzero exit code' }
} catch {
    throw "FLUIDSYNTH_START_FAILED: $($_.Exception.Message). Ensure the extracted binary has its required DLL files."
}

$data = Join-Path $repo 'chordflow-data'
New-Item -ItemType Directory -Force -Path $data | Out-Null
[System.IO.File]::WriteAllText((Join-Path $data 'soundfont.path'),$bank,[System.Text.UTF8Encoding]::new($false))
[System.IO.File]::WriteAllText((Join-Path $data 'fluidsynth.path'),$bin,[System.Text.UTF8Encoding]::new($false))
Write-Host ''
Write-Host 'SAMPLED INSTRUMENTS CONFIGURED' -ForegroundColor Green
Write-Host "SoundFont: $bank"
Write-Host "FluidSynth: $bin"
Write-Host 'Restart the Command Center and local worker to use the real instrument samples.'
Write-Host 'Production rendering requires sampled instruments unless you explicitly set CHORDFLOW_AUDIO_QUALITY=preview.'
