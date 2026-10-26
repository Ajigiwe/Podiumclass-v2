# Redis for LiveKit (recording only).
#
# The LiveKit server and the egress worker talk to each other over this bus:
# without Redis the classroom still works, but every recording call fails
# closed with 503 RECORDING_UNAVAILABLE rather than pretending it recorded.
# Dev-only settings -- protected mode is off so a container can reach Redis
# through host.docker.internal, and the port is only ever opened by Docker
# Desktop on this machine.
$Root = Split-Path -Parent $PSScriptRoot
$Name = 'podium-redis'

# Docker must be up. `docker info` fails loudly while the engine is broken
# (WSL not started, virtualization off), so its output is captured, not thrown.
$daemon = (& docker info --format '{{.ServerVersion}}' 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or -not $daemon) {
    Write-Error "Docker Desktop is not running. Start it first (its engine needs WSL2 + virtualization enabled in BIOS)."
    exit 1
}

$running = (& docker ps --filter "name=$Name" --format '{{.Names}}' 2>&1 | Out-String).Trim()
if ($running -eq $Name) {
    Write-Host "Redis is already running as '$Name'." -ForegroundColor Green
} else {
    $stopped = (& docker ps -a --filter "name=$Name" --format '{{.Names}}' 2>&1 | Out-String).Trim()
    if ($stopped -eq $Name) {
        docker rm $Name | Out-Null
    }

    Write-Host "Starting Redis for LiveKit on localhost:6379..." -ForegroundColor Cyan
    docker run -d --name $Name -p 6379:6379 redis:7-alpine `
        redis-server --protected-mode no --appendonly yes | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Could not start the Redis container."
        exit 1
    }
    Start-Sleep -Seconds 2
}

$pong = (& docker exec $Name redis-cli ping 2>&1 | Out-String).Trim()
if ($pong -eq 'PONG') {
    Write-Host "Redis is up (PING answered PONG)." -ForegroundColor Green
} else {
    Write-Warning "Redis container is there but did not answer PING: $pong"
    docker logs --tail 20 $Name
}
