param(
    [string]$AceStepRoot = $env:ACESTEP_ROOT,
    [ValidateRange(1024,65535)][int]$Port = 8001
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($AceStepRoot)) {
    throw 'Specify -AceStepRoot C:\Path\To\ACE-Step-1.5 or set ACESTEP_ROOT. Install ACE-Step separately; MusicRot does not download models.'
}
$root = (Resolve-Path -LiteralPath $AceStepRoot -ErrorAction Stop).ProviderPath
if (-not (Test-Path -LiteralPath (Join-Path $root 'acestep\api_server.py'))) {
    throw 'ACESTEP_ROOT_INVALID: expected acestep\api_server.py'
}
$uv = Get-Command uv -ErrorAction SilentlyContinue
if (-not $uv) { throw 'UV_NOT_AVAILABLE: install uv and run uv sync inside ACE-Step-1.5 first' }
$env:PORT = [string]$Port
$env:HOST = '127.0.0.1'
Write-Host 'ACE-Step API will run on http://127.0.0.1:'$Port
Write-Host 'Keep this PowerShell session open while MusicRot generates neural audio.'
Push-Location $root
try {
    & uv run acestep-api
    if ($LASTEXITCODE -ne 0) { throw "ACE-Step API exited with code $LASTEXITCODE" }
} finally {
    Pop-Location
}
