$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if (-not (Test-Path build/chordflow/Main.class)) { & "$PSScriptRoot/build.ps1" }
& java '-Djava.awt.headless=true' -cp build chordflow.Main serve --port 8791 --root chordflow-data
if ($LASTEXITCODE -ne 0) { throw 'Controller exited with error' }
