# Cloudflare Cutover Runbook — podiumclass.online

Goal: hide the origin IP (`161.97.176.191`) behind Cloudflare for everything except WebRTC media,
and lock the server so direct-IP access to the site is impossible.

Current state (captured Sep 2026):

- DNS hosted at **Namecheap** (nameservers `dns1/dns2.registrar-servers.com`).
- All A records point to `161.97.176.191`.
- Server: nginx + certbot, one Let's Encrypt cert (`/etc/letsencrypt/live/podiumclass.online/`)
  covering all hostnames; Next.js on :3000, LiveKit on :7880, MinIO on :9000/:9001.

---

## Step 0 — Before touching anything (5 min)

1. Log into **Namecheap** → *Domain List → Manage → Advanced DNS* and export / screenshot the
   **entire** record list. Compare with the inventory below. If anything is missing here
   (e.g. extra TXT records on subdomains), copy it over as-is.
2. Make sure **2FA is on** for the Namecheap account before changing nameservers.
3. Have a Cloudflare account ready (free plan is enough). If you don't have one: create it at
   dash.cloudflare.com and verify your email.

## Step 1 — Add the domain to Cloudflare

1. dash.cloudflare.com → **Add a site** → `podiumclass.online` → Free plan.
2. Cloudflare will scan existing DNS. **Manually reconcile it** against the inventory below —
   Cloudflare's scan can miss records, and every record must exist in Cloudflare before the
   nameserver swap or it stops resolving.
3. Set **SSL/TLS → Overview → Full (strict)** (origin has a valid LE cert; keeps end-to-end TLS).

## Step 2 — Recreate the DNS records in Cloudflare

### A records (proxied = orange cloud):

| Type | Name        | Content          | Proxy |
|------|-------------|------------------|-------|
| A    | `@`         | 161.97.176.191   | 🟠 Proxied |
| A    | `www`       | 161.97.176.191   | 🟠 Proxied |
| A    | `manage`    | 161.97.176.191   | 🟠 Proxied |
| A    | `minio-console` | 161.97.176.191 | 🟠 Proxied |
| A    | `storage`   | 161.97.176.191   | 🟠 Proxied* |
| A    | `livekit`   | 161.97.176.191   | ⚪ DNS only |
| A    | `turn`      | 161.97.176.191   | ⚪ DNS only |

\* `storage` (MinIO S3) can be proxied for downloads, but the Cloudflare **free plan caps request
bodies at 100 MB** — any upload (e.g. large egress recordings) above that fails. If you ever push
recordings >100 MB to MinIO, set `storage` to DNS-only instead. `minio-console` is a web UI — proxy
it.

### TXT records (must be exact — these keep email working):

| Type | Name                    | Content |
|------|-------------------------|---------|
| TXT  | `@`                     | `firebase=lite-class` |
| TXT  | `@`                     | `v=spf1 include:_spf.firebasemail.com ~all` |
| TXT  | `resend._domainkey`     | `p=MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQC2XDtRq3vG31IbgQgNrphKE482sXrQBtbOdBzQymHFbTrqZOG9XfTQGLwcFcPfIZ/2br7fhVtAx9VfCQLMXqT5Tc/P11ZEocxVylbxbPO7Z5QkjJPV8DvTIJoPZy9htgFVscyL6RfTPXnEw8yQUj9iZxTV6zyG5xb0WVyTyOiMRwIDAQAB` |

The `resend._domainkey` value is the **Resend DKIM key** — if this is wrong or missing, the
class-start / scheduled-class alert emails land in spam. Do not "optimize" or reformat it.

No MX, no DMARC currently — do not invent any.

## Step 3 — Swap nameservers at Namecheap

1. Cloudflare → your site → *Overview* shows two nameservers like `aaa.ns.cloudflare.com`.
2. Namecheap → *Domain List → Manage → Nameservers* → custom DNS → paste both.
3. Wait for propagation (Cloudflare dashboard shows "Active"; typically 10 min – few hours).

## Step 4 — Lock the origin (run from the VPS, only AFTER nameservers are live)

```bash
bash /opt/podium/scripts/vps/cloudflare-lock.sh
```

What it does:
- Verifies the site is actually resolving through Cloudflare (safety check — refuses to run if not).
- Adds all Cloudflare edge IP ranges to ufw for ports 80/443, then **removes** the open-to-world
  `80/tcp` and `443/tcp` rules → direct-IP access to the site is dropped.
- Adds an nginx `real_ip` snippet so logs and any IP-based logic see the real client IP, not
  Cloudflare's edge.
- Leaves SSH (22), LiveKit (7880/7881, 50000–60000/udp), TURN (3478/udp, 5349/tcp, 40000–40100/udp)
  untouched.

## Step 5 — Verify (in order)

1. `nslookup podiumclass.online` → resolves to a **Cloudflare** IP, not `161.97.176.191`.
2. `curl -sI https://podiumclass.online` → returns `cf-ray:` header.
3. **Origin locked:** `curl -sI https://161.97.176.191 -H "Host: podiumclass.online"` → times out /
   refused (direct access dropped).
4. Open the dashboard, start a test class, join as a student → LiveKit media still works
   (UDP direct, unaffected).
5. Create a community class → member inbox receives the email **in the main inbox** (not spam) —
   proves SPF + DKIM survived the move.
6. Certbot renewal still fine: `certbot renew --dry-run` on the VPS (HTTP-01 flows through CF).

## Rollback (if something breaks)

Namecheap → point nameservers back to `dns1/dns2.registrar-servers.com`, then on the VPS:
`ufw allow 80/tcp && ufw allow 443/tcp && rm /etc/nginx/conf.d/cloudflare-real-ip.conf && systemctl reload nginx`.

---

## Bonus synergy with the IP change

Once the site is proxied and the origin locked, **changing the VPS IP becomes invisible to the
public**: you only update the A records inside the Cloudflare dashboard (now hidden), and the
attacker's knowledge of `161.97.176.191` becomes useless for the site — direct connections are
dropped by ufw. Their only remaining surface is LiveKit/TURN media ports, which fail2ban/ufw already
guard and which carry no panel or shell access.![![alt text](image-1.png)](image.png)