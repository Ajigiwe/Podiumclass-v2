# Deploying Podium to a live VPS

One box, one command. This is the production path: TLS, Postgres, LiveKit, the
recording worker and the web app all start together and survive a reboot.

For local development nothing here applies — use `docker-compose.yml` and the
Vite dev server as before.

---

## 1. What the box needs

| Item | Minimum | Notes |
| --- | --- | --- |
| VPS | 2 vCPU / 4 GB RAM / 40 GB disk | The egress worker runs headless Chrome to composite recordings; 2 GB is not enough when a recording starts. 200 students in one class is fine — media is peer-to-peer through LiveKit, not through Django. |
| OS | Ubuntu 22.04 or 24.04 | Any distro with Docker works. |
| Domain | `podiumclass.online` + `livekit.podiumclass.online` | Two A records, both at the VPS IP. |
| Ports | 80, 443/tcp, 7881/tcp, 50000–50100/udp | 7881/UDP range is call media; without them signalling succeeds and every camera fails. |

DNS must resolve **before** the first deploy: Caddy asks Let's Encrypt for a
certificate during start-up, and a name that does not resolve yet fails that
challenge (Caddy retries, so it recovers on its own once DNS propagates).

## 2. Prepare the server

```bash
# as root
adduser --disabled-password --gecos "" podium
usermod -aG sudo podium
rsync --archive --chown=podium:podium ~/.ssh /home/podium

# Docker Engine + compose plugin
curl -fsSL https://get.docker.com | sh
usermod -aG docker podium

# Firewall
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw allow 7881/tcp
ufw allow 50000:50100/udp
ufw --force enable
```

Then log out and back in as `podium` (or use `vps_connect.ps1` from this repo
with the key you generated).

> 2 GB of swap is worth adding on a 4 GB box: `fallocate -l 2G /swapfile &&
> chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile`, plus the same
> line in `/etc/fstab`. Chrome is the process most likely to need it.

## 3. First deploy

```bash
git clone <your-repo-url> ~/podium
cd ~/podium

cp deploy/.env.example deploy/.env
chmod 600 deploy/.env
nano deploy/.env              # fill in every REQUIRED value
sudo bash deploy/deploy.sh
```

`deploy.sh` validates the secrets, creates `./data/{recordings,materials,avatars}`,
renders the LiveKit/Egress configs from `deploy/.env`, builds both images,
starts the stack and waits until `https://<APP_DOMAIN>/health` answers.

Generate the secrets it asks for:

```bash
python3 -c "import secrets; print(secrets.token_urlsafe(64))"   # DJANGO_SECRET_KEY
openssl rand -base64 32 | tr -d '/+=' | head -c 40              # POSTGRES_PASSWORD
openssl rand -hex 32                                            # LIVEKIT_API_SECRET
```

Values in `deploy/.env` must be shell-safe: no spaces, no quotes. The deploy
script sources the file to hand LiveKit its keys.

### Create the first admin

```bash
docker compose --env-file deploy/.env -f docker-compose.prod.yml \
  exec backend python manage.py createsuperuser
```

Admins created here can manage users and grant class-creation rights from
either the web admin dashboard or the Android app's admin section.

## 4. Point the clients at it

**Web** — built in: the SPA calls `https://<APP_DOMAIN>/api/v1/...` on its own
origin (nginx proxies `/api` to Django). No CORS, no second hostname.

**Google sign-in** — add `https://<APP_DOMAIN>` to the OAuth client's
*Authorized JavaScript origins* in the Google Cloud console and put the client
id in `GOOGLE_CLIENT_ID` in `deploy/.env`. Without both, the button opens a
popup that closes with `origin_mismatch`.

**Android** — a release build defaults to `https://podiumclass.online`, baked in
as `DEFAULT_API_URL` from `android/app/build.gradle.kts`. If you deploy under a
different domain, build with it:

```bash
./gradlew assembleRelease -PpodiumApiUrl=https://your.domain
```

Local development is unchanged: `-PpodiumApiUrl=http://<LAN-IP>:8000`. The
LiveKit URL is not compiled in — the server hands it out with the join token.
A release build also drops any saved LAN address left over from testing, so an
updated install does not keep dialling a network the phone has left.

## 5. Verify the deployment

In this order — each step exercises one more moving part:

1. `https://<APP_DOMAIN>/health` → `{"status": "healthy"...}`. Caddy's
   certificate is valid (padlock, no warning).
2. Sign in. A rejected origin here means step 4 was skipped.
3. Open a class as host: your camera/mic must work. If the tiles stay black but
   the room connects, ports 7881/tcp and 50000–50100/udp are blocked.
4. Start a recording as host. It should reach `recording` within a few seconds
   and appear under Dashboard → Records when stopped.
   `docker compose -f docker-compose.prod.yml logs egress` explains failures.
5. Join from a phone on mobile data (not the same Wi-Fi) — that is the only
   check that proves the public IP and ports are right for external clients.

## 6. Updating

```bash
cd ~/podium
git pull
sudo bash deploy/deploy.sh
```

Migrations and `collectstatic` run automatically in the backend entrypoint
before gunicorn accepts traffic. Volumes and `./data` are untouched, so
recordings, uploads and the database survive every rebuild.

If a release only changed frontend source, the backend image is still rebuilt —
harmless, and Docker reuses its cached layers.

## 7. Backups

```bash
sudo bash deploy/backup.sh              # database + uploads/recordings
sudo crontab -e
# nightly at 03:15
15 3 * * * /bin/bash /home/podium/podium/deploy/backup.sh >> /var/log/podium-backup.log 2>&1
```

The script keeps the last 7 runs in `/var/backups/podium` and verifies each
archive. That protects against a bad migration or a mistaken delete — **not**
against losing the VPS. Copy the directory off the box (rsync, S3, Backblaze)
on the same schedule.

Restore:

```bash
gunzip -c /var/backups/podium/db-<stamp>.sql.gz \
  | docker compose --env-file deploy/.env -f docker-compose.prod.yml \
      exec -T db psql -U podium -d podium
tar -xzf /var/backups/podium/files-<stamp>.tar.gz
```

## 8. How the pieces fit

```
              :80 / :443
                 |
              [caddy]  --TLS, auto-renewed
              /      \
   app domain /        \ livekit domain
             v          v
       [frontend]    [livekit]  <-- :7881/tcp, :50000-50100/udp (media, direct)
        nginx           ^  |
   /api /admin /static  |  | redis
             |          |  v
         [backend] -----+ [egress] --> ./data/recordings/*.mp4
         gunicorn           (composite recorder)
             |
           [db] postgres 16   (no published port)
```

* Only Caddy publishes ports to the internet. Postgres and Redis are reachable
  only inside the compose network.
* Recordings are written by `egress` into `./data/recordings` and read back by
  Django from the same directory — one file, two mount points, never copied.
* `deploy/generated/*.yaml` hold the LiveKit secret and are gitignored; they are
  re-rendered from `deploy/.env` on every deploy.

## 9. Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `deploy.sh` fails validation | A REQUIRED value in `deploy/.env` is empty | Fill it in; the message names the variable. |
| Caddy logs `no such host` / ACME errors | DNS not pointing at the box yet | Fix the A records, then `docker compose -f docker-compose.prod.yml restart caddy`. |
| Web loads, every API call fails | Frontend built without `VITE_API_URL` | Rebuild: `docker compose -f docker-compose.prod.yml build --no-cache frontend`. |
| Sign-in popup closes instantly | Origin not registered for the OAuth client | Add `https://<APP_DOMAIN>` to Authorized JavaScript origins. |
| Class connects, no video/audio | Media ports blocked, or LiveKit advertising its container IP | Open 7881/tcp + 50000–50100/udp, and keep `LIVEKIT_USE_EXTERNAL_IP=true` so the public address is advertised. |
| Recording stuck on "starting" | Egress worker down, or Redis unreachable | `docker compose -f docker-compose.prod.yml logs egress`; the worker must log a successful registration. |
| `CSRF verification failed` on `/admin` | `CSRF_TRUSTED_ORIGINS` missing that origin | It is set from `APP_ORIGIN`; check the value matches the scheme exactly. |
| Backend restarts in a loop | DB password/URL mismatch, or migrations failing | `docker compose -f docker-compose.prod.yml logs backend`. |
| Caddy fails with `address already in use` | Something else (Apache/nginx installed by the provider) owns port 80 or 443 | `sudo ss -ltnp | grep -E ':80|:443'`, then stop it and re-run the deploy. |
| Disk filling up | Recordings | `du -sh data/*`; prune old recordings under Dashboard → Records (deleting a class keeps its attendance but not its video). |

Logs for everything:

```bash
docker compose --env-file deploy/.env -f docker-compose.prod.yml ps
docker compose --env-file deploy/.env -f docker-compose.prod.yml logs -f backend
```
