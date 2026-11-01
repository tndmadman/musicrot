$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)

# Java 20 is the minimum supported runtime. Always compile Java 20 bytecode,
# including when a newer JDK is installed, so Windows JDK 20 can run it.
foreach ($app in @('java','javac','ffmpeg','ffprobe')) {
    if (-not (Get-Command $app -ErrorAction SilentlyContinue)) {
        throw "DEPENDENCY_UNAVAILABLE: $app is missing from PATH (JDK 20+ and FFmpeg/ffprobe required)"
    }
}
$compilerVersion = ((& javac -version) | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $compilerVersion -notmatch '^javac\s+(\d+)(?:\.|$)') {
    throw "Could not determine javac version: $compilerVersion"
}
$compilerMajor = [int]$Matches[1]
if ($compilerMajor -lt 20) {
    throw "JDK 20 or newer required, found: $compilerVersion"
}
$runtimeLines = @(& java --version)
if ($LASTEXITCODE -ne 0 -or $runtimeLines.Count -eq 0) {
    throw 'Could not determine Java runtime version'
}
$runtimeVersion = [string]$runtimeLines[0]
if ($runtimeVersion -notmatch '^(?:openjdk|java)\s+(\d+)(?:\.|\s|$)') {
    throw "Could not parse Java runtime version: $runtimeVersion"
}
$runtimeMajor = [int]$Matches[1]
if ($runtimeMajor -lt 20) {
    throw "Java runtime 20 or newer required, found: $runtimeVersion"
}

# Remove stale JDK-21-compiled classes when switching the machine to JDK 20.
# Do not allow a previously compiled class file to hide a compilation error.
if (Test-Path build) { Remove-Item -Recurse -Force build }
New-Item -ItemType Directory -Force build | Out-Null
$files = @(Get-ChildItem src/chordflow/*.java | Select-Object -ExpandProperty FullName)
if ($files.Count -eq 0) { throw 'No ChordFlow Java source files found' }
Write-Host "Compiling ChordFlow for Java 20 (compiler: $compilerVersion; runtime: $runtimeVersion)"
& javac --release 20 -encoding UTF-8 -d build $files
if ($LASTEXITCODE -ne 0) { throw 'Java 20-compatible compilation failed' }
& java '-Djava.awt.headless=true' -cp build chordflow.Main selftest
if ($LASTEXITCODE -ne 0) { throw 'Java selftest failed' }
Write-Host 'ChordFlow Java 20+ build and selftest PASS.'
