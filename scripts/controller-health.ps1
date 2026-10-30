param([string]$Url = 'http://127.0.0.1:8791/api/jobs')
$ErrorActionPreference = 'Stop'
try {
    $reply = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 2
    if ($reply.StatusCode -ne 200) { exit 1 }
    $data = $reply.Content | ConvertFrom-Json
    if ($null -eq $data.jobs -or $null -eq $data.paused -or $null -eq $data.draining) { exit 1 }
    exit 0
} catch {
    exit 1
}
