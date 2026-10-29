$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'Java 21 required: java missing' }
if (-not (Get-Command javac -ErrorAction SilentlyContinue)) { throw 'JDK 21 required: javac missing' }
if (-not (Get-Command ffmpeg -ErrorAction SilentlyContinue)) { throw 'FFmpeg is required: ffmpeg missing' }
if (-not (Get-Command ffprobe -ErrorAction SilentlyContinue)) { throw 'FFprobe is required: ffprobe missing' }
$version = & javac -version 2>&1
if ($LASTEXITCODE -ne 0 -or ($version -notmatch '^javac 21')) { throw "Expected JDK 21, found $version" }
New-Item -ItemType Directory -Force build | Out-Null
$files = Get-ChildItem src/chordflow/*.java | Select-Object -ExpandProperty FullName
& javac -encoding UTF-8 -d build $files
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
& java '-Djava.awt.headless=true' -cp build chordflow.Main selftest
if ($LASTEXITCODE -ne 0) { throw 'Java selftest failed' }
Write-Host 'ChordFlow build and selftest PASS.'
