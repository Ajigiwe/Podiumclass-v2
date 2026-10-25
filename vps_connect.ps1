# VPS 1-Click Connect Script
# Host: 161.97.176.191
# Custom SSH Port: 2222 (or 22 fallback)
#
# NOTE ON StrictHostKeyChecking:
#   The previous version used `-o StrictHostKeyChecking=no`, which accepts ANY
#   host key on EVERY connection -- including a changed one. That silently
#   defeats the protection SSH's host keys exist to provide, so a
#   man-in-the-middle proxy would go unnoticed.
#
#   `accept-new` keeps the convenience of connecting to a fresh box (the key is
#   trusted on first use) while still refusing to connect if a previously
#   recorded key has CHANGED, which is the signal that actually matters.
#
# NOTE ON ROOT:
#   Logging in as root means every command runs with full privileges and a
#   single typo can destroy the host. Prefer a sudo-enabled deploy user; if you
#   must use root, at least disable password login on the server
#   (PasswordAuthentication no in /etc/ssh/sshd_config).

param (
    [string]$Port = "22",
    [string]$User = "root"
)

$IdentityFile = "$HOME\.ssh\vps_ed25519"

if (-not (Test-Path $IdentityFile)) {
    Write-Host "SSH key not found: $IdentityFile" -ForegroundColor Red
    Write-Host "Generate one with: ssh-keygen -t ed25519 -C 'podium-vps'" -ForegroundColor Yellow
    exit 1
}

if ($User -eq "root") {
    Write-Host "WARNING: connecting as root. Consider a sudo-enabled deploy user." -ForegroundColor Yellow
}

Write-Host "Connecting to VPS (161.97.176.191) on port $Port as $User..." -ForegroundColor Cyan
ssh -i $IdentityFile -p $Port -o StrictHostKeyChecking=accept-new "$User@161.97.176.191"
