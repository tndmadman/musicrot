param(
    [string]$Url = 'http://127.0.0.1:8791/',
    [int]$Attempts = 40
)
$ErrorActionPreference = 'Stop'
for ($i = 0; $i -lt $Attempts; $i++) {
    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 2
        if ($response.StatusCode -eq 200) {
            Write-Host 'ChordFlow controller is accepting connections.'
            exit 0
        }
    } catch {
        # Controller may still be starting; do not treat connection refused as a successful startup.
    }
    Start-Sleep -Milliseconds 500
}
Write-Host "ERROR: ChordFlow controller did not become available at $Url" -ForegroundColor Red
exit 1
