# Full deploy guide: eKMS on your VPS + Cloudflare

**Goal:** run **backend + web** on your VPS, use **Cloudflare only for DNS** (and optional CDN/proxy). Domain: **kms-cvt.com**.

| Public URL | What runs |
|------------|-----------|
| `https://kms-cvt.com/` | Web portal (Wasm) |
| `https://kms-cvt.com/v1/...` | Node API |
| `https://kms-cvt.com/health` | Health check |

`api.kms-cvt.com` is **not** used (already taken elsewhere).

Android terminal server address: `https://kms-cvt.com`

---

## Architecture

```text
Browser / Terminal
        │
        ▼
  Cloudflare DNS  ──(A record)──►  Your VPS (other provider)
        │                              │
   (optional orange                    ├── Caddy :80/:443 (TLS)
    proxy / CDN)                       ├── Web static files
                                       ├── API container :3000
                                       └── MySQL (internal only)
```

Cloudflare does **not** host the Node app or MySQL. The VPS does.

---

## Part A — Cloudflare DNS

1. Log in to [Cloudflare](https://dash.cloudflare.com) → select zone **kms-cvt.com**.
2. **DNS → Records** → Add:

| Type | Name | Content | Proxy status |
|------|------|---------|--------------|
| A | `@` | *your VPS public IPv4* | see below |
| A | `www` | *same VPS IPv4* (optional) | same as `@` |

3. Do **not** change `api` if another product owns `api.kms-cvt.com`.

### Proxy status (important for first SSL)

**Recommended for first deploy:**

- Set `@` (and `www`) to **DNS only** (grey cloud).
- That lets Caddy get a real Let’s Encrypt certificate with HTTP-01.
- After `https://kms-cvt.com/health` works, you may turn **Proxied** (orange cloud) on.

**If you enable orange cloud later:**

- Cloudflare SSL/TLS → Overview → set mode to **Full (strict)** (not Flexible).
- Flexible breaks JWT/cookies and is insecure (Cloudflare↔origin is HTTP).

### SSL / TLS settings (Cloudflare)

- SSL/TLS → Overview: **Full (strict)** once origin has a valid cert.
- SSL/TLS → Edge Certificates: keep **Always Use HTTPS** on if you like.

---

## Part B — VPS preparation (Ubuntu)

SSH into the VPS as a sudo user.

### 1. Update and install Docker

```bash
sudo apt update
sudo apt install -y docker.io docker-compose-v2 git curl
sudo usermod -aG docker "$USER"
```

Log out and SSH back in so the `docker` group applies.

```bash
docker --version
docker compose version
```

### 2. Firewall

Allow SSH, HTTP, HTTPS (UFW example):

```bash
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
sudo ufw status
```

### 3. Confirm ports 80 and 443 are free

```bash
sudo ss -tlnp | grep -E ':80|:443'
```

If Nginx/Apache already binds them, stop those sites for this host **or** reverse-proxy into Docker instead of using Caddy’s published ports (advanced — ask if you need that).

### 4. Note your VPS public IP

```bash
curl -4 ifconfig.me
```

Put that IP in the Cloudflare A records (Part A).

Wait until DNS resolves (can take a few minutes):

```bash
# from your PC
nslookup kms-cvt.com
# should show your VPS IP (grey cloud) or Cloudflare IPs (orange cloud)
```

---

## Part C — Put the code on the VPS

### Option 1 — Git clone (preferred)

```bash
cd ~
git clone <YOUR_GIT_REPO_URL> eKMS
cd eKMS/backend
```

### Option 2 — SCP / SFTP from your PC

Zip or copy the `backend` folder (and later the web build) to e.g. `~/eKMS/backend`.

---

## Part D — Configure production secrets

```bash
cd ~/eKMS/backend
cp .env.production.example .env.production
nano .env.production
```

Set **all** of these to strong unique values:

| Variable | Notes |
|----------|--------|
| `MYSQL_ROOT_PASSWORD` | long random |
| `DB_PASSWORD` | long random |
| `JWT_ACCESS_SECRET` | ≥32 random chars |
| `JWT_REFRESH_SECRET` | different ≥32 random chars |
| `SUPER_ADMIN_EMAIL` | e.g. `admin@kms-cvt.com` |
| `SUPER_ADMIN_PASSWORD` | strong; you will use this to log in |
| `CORS_ORIGINS` | `https://kms-cvt.com,https://www.kms-cvt.com` |

Never commit `.env.production`.

Generate secrets example:

```bash
openssl rand -base64 48
```

---

## Part E — Start MySQL + API + Caddy

```bash
cd ~/eKMS/backend

docker compose -f docker-compose.prod.yml --env-file .env.production up -d --build
docker compose -f docker-compose.prod.yml --env-file .env.production ps
```

Wait until MySQL is healthy and Caddy is up.

### Seed Super Admin (once)

```bash
docker compose -f docker-compose.prod.yml --env-file .env.production exec api node src/seed.js
```

### Run schema migrations

```bash
docker compose -f docker-compose.prod.yml --env-file .env.production exec api node src/migratePhase2.js
```

### Verify API through the domain

```bash
curl -s https://kms-cvt.com/health
# expect: {"ok":true,"service":"ekms-backend"}
```

If TLS fails while DNS is still orange-clouded, switch the A record to **DNS only**, wait 1–2 minutes, restart Caddy:

```bash
docker compose -f docker-compose.prod.yml --env-file .env.production restart caddy
```

Check Caddy logs:

```bash
docker compose -f docker-compose.prod.yml --env-file .env.production logs -f caddy
```

---

## Part F — Build and upload the web portal

### 1. Build on your Windows PC (repo root)

```bat
gradlew.bat :webApp:wasmJsBrowserDistribution
```

Output folder (typical):

`webApp\build\dist\wasmJs\productionExecutable\`

### 2. Copy to VPS

From PowerShell (adjust user/IP/path):

```powershell
scp -r webApp\build\dist\wasmJs\productionExecutable\* USER@VPS_IP:/tmp/ekms-web/
```

Or use FileZilla / WinSCP to upload that folder’s **contents** to `/tmp/ekms-web/` on the VPS.

### 3. Install into Caddy’s web volume

On the VPS:

```bash
# Find the exact volume name
docker volume ls | grep web_dist

# Example name: backend_web_dist  (may vary)
export WEB_VOL=backend_web_dist

docker run --rm \
  -v "${WEB_VOL}:/srv" \
  -v /tmp/ekms-web:/in \
  alpine sh -c 'rm -rf /srv/* && cp -a /in/. /srv/ && ls -la /srv'
```

### 4. Open the site

Browser: **https://kms-cvt.com**

Sign in with `SUPER_ADMIN_EMAIL` / `SUPER_ADMIN_PASSWORD` from `.env.production`.

The portal detects `kms-cvt.com` and calls `https://kms-cvt.com/v1/...` (same origin).

---

## Part G — After it works: Cloudflare proxy (optional)

1. Cloudflare DNS → set `@` (and `www`) to **Proxied** (orange).
2. SSL/TLS → **Full (strict)**.
3. Recheck `https://kms-cvt.com/health` and login.

If something breaks, set DNS back to **DNS only** while debugging.

---

## Part H — Terminal app (colleague)

Admin Menu:

- **Set server address:** `https://kms-cvt.com`
- **Key Cabinet ID:** UUID of the terminal row in the backend

No `api.` subdomain.

---

## Day-2 operations

### View logs

```bash
cd ~/eKMS/backend
docker compose -f docker-compose.prod.yml --env-file .env.production logs -f api
docker compose -f docker-compose.prod.yml --env-file .env.production logs -f caddy
```

### Update after `git pull`

```bash
cd ~/eKMS
git pull
cd backend
docker compose -f docker-compose.prod.yml --env-file .env.production up -d --build
docker compose -f docker-compose.prod.yml --env-file .env.production exec api node src/migratePhase2.js
# rebuild web on PC and re-copy into web_dist (Part F)
```

### Backup MySQL

```bash
docker compose -f docker-compose.prod.yml --env-file .env.production exec -T mysql \
  mysqldump -u root -p"$MYSQL_ROOT_PASSWORD" ekms > ekms-backup-$(date +%F).sql
```

(Use the root password from `.env.production`.)

---

## Troubleshooting

| Symptom | What to check |
|---------|----------------|
| DNS not resolving to VPS | Cloudflare A record; wait for propagation; grey cloud shows VPS IP in `nslookup` |
| Certificate / HTTPS errors | Grey cloud first; Caddy logs; ports 80/443 open |
| `502` on `/health` | `docker compose ps` — is `api` healthy? `logs api` |
| Site loads but login fails | Seed ran? Correct email/password? Browser console Network tab on `/v1/auth/login` |
| Web is blank / 404 | `web_dist` empty — re-run Part F; volume name correct? |
| Port already in use | Something else on 80/443 — free them or integrate with existing Nginx |
| Orange cloud + broken SSL | Set SSL to **Full (strict)** or temporarily DNS-only |

---

## Security checklist

- [ ] Strong admin + DB + JWT secrets in `.env.production`
- [ ] `.env.production` not in git
- [ ] MySQL not published to the public internet (prod compose keeps it internal)
- [ ] UFW allows only 22/80/443 (or your SSH port)
- [ ] Cloudflare SSL is **Full (strict)** if proxied
- [ ] Local seed password (`ChangeMeNow!`) **not** used in production
