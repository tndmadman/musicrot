$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if (-not (Test-Path build/chordflow/Main.class)) { & "$PSScriptRoot/build.ps1" }
& java '-Djava.awt.headless=true' '-Xmx2g' -cp build chordflow.Main worker --url http://127.0.0.1:8791 --id "$env:COMPUTERNAME-$PID" --out chordflow-data/output
if ($LASTEXITCODE -ne 0) { throw 'Worker exited with error' }
