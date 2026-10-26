# LiveKit Egress worker: the process that actually writes the MP4.
#
#   Django  --POST /twirp/livekit.Egress/*-->  LiveKit server (localhost:7880)
#                                                  |
#                                                  | Redis bus
#                                                  v
#   .livekit/recordings/*.mp4  <--  egress container (/out/recordings)
#
# Mirrors start_livekit.ps1: safe to run repeatedly, and it names exactly
# what is missing (Docker, Redis, the image, the config) instead of failing
# silently -- a half-started worker would otherwise look like "record is
# broken" from the classroom, when the truth is a container that never ran.
$Root = Split-Path -Parent $PSScriptRoot
$Name = 'podium-egress'
# Matched to the local server line (livekit-server 1.13.10): the server and
# the worker share a Redis bus, so their release lines must agree.
$Image = 'livekit/egress:v1.13.0'
$Config = Join-Path $Root '.livekit\egress.local.yaml'
$Recordings = Join-Path $Root '.livekit\recordings'

if (-not (Test-Path $Config)) {
    Write-Error "Missing $Config (egress.local.yaml holds the API key, ws_url and Redis address)."
    exit 1
}
New-Item -ItemType Directory -Force -Path $Recordings | Out-Null

# --- preconditions -------------------------------------------------------
$daemon = (& docker info --format '{{.ServerVersion}}' 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or -not $daemon) {
    Write-Error "Docker Desktop is not running. Its engine needs WSL2 with virtualization enabled in BIOS (scripts/start_redis.ps1 explains the same thing)."
    exit 1
}

function Test-TcpPort([int]$Port) {
    try {
        $client = New-Object Net.Sockets.TcpClient
        $client.Connect('127.0.0.1', $Port)
        $client.Close()
        return $true
    } catch { return $false }
}

if (-not (Test-TcpPort 7880)) {
    Write-Warning "LiveKit server is not listening on 7880 yet - run scripts/start_livekit.ps1. Egress will retry until it appears."
}
if (-not (Test-TcpPort 6379)) {
    Write-Warning "Redis is not listening on 6379 - run scripts/start_redis.ps1. Without it the server cannot forward recording requests to this worker."
}

# --- image ----------------------------------------------------------------
$images = (& docker images --format '{{.Repository}}:{{.Tag}}' 2>&1 | Out-String)
if ($images -notmatch [regex]::Escape($Image)) {
    Write-Host "Pulling $Image (matched to livekit-server 1.13.10)..." -ForegroundColor Cyan
    docker pull $Image
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Could not pull $Image."
        exit 1
    }
}

# --- container ------------------------------------------------------------
$running = (& docker ps --filter "name=$Name" --format '{{.Names}}' 2>&1 | Out-String).Trim()
if ($running -eq $Name) {
    Write-Host "Egress worker is already running as '$Name'." -ForegroundColor Green
    exit 0
}
$stopped = (& docker ps -a --filter "name=$Name" --format '{{.Names}}' 2>&1 | Out-String).Trim()
if ($stopped -eq $Name) {
    docker rm $Name | Out-Null
}

# Forward slashes keep Docker's Windows path parsing unambiguous. shm-size is
# not cosmetic: Chrome composites the room grid, and the 64MB default shared
# memory makes it die partway through a real lecture.
$ConfigLinux = $Config -replace '\\', '/'
$RecordingsLinux = $Recordings -replace '\\', '/'

Write-Host "Starting LiveKit Egress worker..." -ForegroundColor Cyan
docker run -d --name $Name `
    --shm-size=1g `
    --cap-add=SYS_ADMIN `
    -e EGRESS_CONFIG_FILE=/etc/livekit/egress.yaml `
    -v "${ConfigLinux}:/etc/livekit/egress.yaml:ro" `
    -v "${RecordingsLinux}:/out/recordings" `
    $Image | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Error "Could not start the egress container."
    exit 1
}

Start-Sleep -Seconds 3
$state = (& docker inspect -f '{{.State.Status}}' $Name 2>&1 | Out-String).Trim()
if ($state -eq 'running') {
    Write-Host "Egress worker is running. MP4s appear in .livekit\recordings" -ForegroundColor Green
    Write-Host "Recording requests go to the LiveKit server on 7880, which reaches this worker over Redis." -ForegroundColor DarkGray
} else {
    Write-Warning "Egress container state: $state"
    docker logs --tail 40 $Name
    exit 1
}
