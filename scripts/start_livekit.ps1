# Self-Hosted LiveKit Server Launcher
# Uses .livekit/livekit-server.exe with configuration from .livekit/livekit.local.yaml

$Root = Split-Path -Parent $PSScriptRoot
$ExePath = Join-Path $Root ".livekit\livekit-server.exe"
$ConfigPath = Join-Path $Root ".livekit\livekit.local.yaml"

if (-not (Test-Path $ExePath)) {
    Write-Error "LiveKit binary not found at $ExePath"
    exit 1
}

# Check if already listening on port 7880
$Existing = Get-NetTCPConnection -LocalPort 7880 -ErrorAction SilentlyContinue
if ($Existing) {
    $livekitPid = $Existing[0].OwningProcess
    Write-Host "LiveKit is already running on port 7880 (PID: $livekitPid)." -ForegroundColor Green
    exit 0
}

Write-Host "Starting self-hosted LiveKit server on ws://localhost:7880..." -ForegroundColor Cyan
Start-Process -FilePath $ExePath -ArgumentList "--config", $ConfigPath -WindowStyle Hidden
Start-Sleep -Seconds 2

$Check = Get-NetTCPConnection -LocalPort 7880 -ErrorAction SilentlyContinue
if ($Check) {
    Write-Host "Self-hosted LiveKit server is active and listening on port 7880!" -ForegroundColor Green
} else {
    Write-Warning "LiveKit process started, verifying port 7880..."
}
