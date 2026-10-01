# Wearsic Server — Complete Termux Setup Guide

Every command you need, in order, to run the Wearsic music server on a spare
Android phone using Termux — and connect your Wear OS watch to it.

---

## 0. What this is

```
[ Watch: Wearsic APK ]  ──WiFi/Internet──▶  [ Phone (Termux): wearsic-server ]  ──▶  YouTube Music
        search / stream / playlists                extracts & streams audio
```

The watch app is just a lightweight player. All the heavy work (searching
YouTube, extracting audio streams, artwork) is done by `wearsic-server` on an
old Android phone running [Termux](https://termux.dev).

The server **self-heals**: if it crashes it restarts automatically, if it hangs
it is detected via `/health` checks every 30 s and killed + restarted, and
`termux-wake-lock` stops Android from freezing it in the background.

---

## ⚡ Quick start — one line (recommended)

Install **Termux from F-Droid** (https://f-droid.org/en/packages/com.termux/),
open it, and paste this single line:

```bash
pkg install -y curl && curl -fsSL https://raw.githubusercontent.com/Aeroflash-r1/wearsic/main/wearsic-server/install.sh | bash
```

That one line does everything: installs Java + unzip, downloads the newest
server release, sets it up in `~/wearsic-server`, **generates a secure API
key for you** (no inventing or typing a secret by hand), keeps any existing
database and API key, wires up **auto-start on reboot**, and starts the
self-healing supervisor. At the end it prints the two things your watch
needs — the **Server URL** and the **API key**. The installer is also bundled
inside the release ZIP, so you can re-run it any time with `bash install.sh`.

When it says *"server started"*, skip ahead to:

- **Section 4** — your API key (already set; just copy it to the watch —
  re-show it any time with `wearsic server url`),
- **Section 5** — connect your watch.

> Want to see every step instead? Follow the manual walkthrough from Section 1.

---

## 1. Install Termux (manual walkthrough)

- Install **Termux from F-Droid** (the Play Store version is outdated/broken):
  https://f-droid.org/en/packages/com.termux/
- Open Termux, then update packages:

```bash
pkg update -y && pkg upgrade -y
```

## 2. Install Java, then get the server onto the phone

The server is a **JVM application**, so Java 17 is required — it is the one
package both options need (run-termux.sh installs ffmpeg itself on first
start):

```bash
pkg install -y openjdk-17 unzip curl
```

### Option A — download directly on the phone (latest release)

```bash
# Resolve the actual latest-release ZIP URL automatically (no version guessing):
ZIP_URL=$(curl -s https://api.github.com/repos/Aeroflash-r1/wearsic/releases/latest \
  | grep -o 'https://[^"]*wearsic-server-termux-[^"]*\.zip' | head -1)
curl -L -o ~/wearsic-server-termux.zip "$ZIP_URL"
```

Or, if you already know the latest release tag (e.g. `v1.1.0` — it must
match the newest release, which is why Option A is easier):

```bash
curl -L -o ~/wearsic-server-termux.zip \
  "https://github.com/Aeroflash-r1/wearsic/releases/latest/download/wearsic-server-termux-v1.1.0.zip"
```

### Option B — copy from somewhere else

Download the zip on a PC/another phone, then move it to the Termux phone
(Share → "Save to storage", or USB). Note where it lands (usually
`/storage/emulated/0/Download/`). Then enable access:

```bash
termux-setup-storage          # tap ALLOW on the permission popup
cp ~/storage/downloads/wearsic-server-termux-*.zip ~/wearsic-server-termux.zip
```

### Extract and enter the folder

```bash
cd ~ && unzip -o wearsic-server-termux.zip
cd ~/wearsic-server
```

Verify Java is present before the first start:

```bash
java -version     # should print: openjdk version "17..."
```

---

## 3. First start

```bash
chmod +x run-termux.sh        # zip tools sometimes drop execute permissions
./run-termux.sh
```

You should see:

```
[wearsic HH:MM:SS] wake lock acquired
[wearsic HH:MM:SS] auto-heal supervisor starting (health checks every 30s)
[wearsic HH:MM:SS] server started (pid XXXX, port 8080)
```

**Leave that Termux session running.** The screen can lock; just don't swipe
Termux away from recents.

### Verify it works

Open a **second** Termux session (swipe from left edge → New session) and run:

```bash
curl http://127.0.0.1:8080/health
```

Expected response:

```json
{"status":"ok","version":"1.0.0","serverName":"Wearsic Engine","transcoderAvailable":true}
```

(The exact `version` value depends on the release you installed — it always
matches the release tag.)

Try a real search too:

```bash
curl "http://127.0.0.1:8080/api/search?q=crowded+house"
```

You should get JSON with a `"results"` array.

---

## 4. Your API key 🔐

Without a key, anyone who can reach the server URL can use it. One shared
secret protects everything.

**The one-line installer already generated a key for you** — 8 short
characters (no confusing `0/O`, `1/l/I`), so it is typeable on the
watch keyboard. It was printed at the end of the install and saved in
`~/wearsic-server/.env`. To see it any time:

```bash
wearsic server api key
# or: wearsic server url
```

Want your own key instead (longer = better, especially for public
URLs)? Set one and restart:

```bash
wearsic server api key my-secret-wearsic-2026
wearsic server restart
```

### Step 3 — restart the server

Stop the supervisor with `Ctrl+C` in its session (or `pkill -f wearsic-server`),
then start again:

```bash
cd ~/wearsic-server && ./run-termux.sh
```

(Only needed if you changed the key yourself — the installer's key is
already live.)

### Step 4 — verify it is locked

```bash
# without key -> rejected (HTTP error):
curl "http://127.0.0.1:8080/api/search?q=test"

# with key -> works:
curl -H "X-Wearsic-Key: my-secret-wearsic-2026" "http://127.0.0.1:8080/api/search?q=test"
```

### Step 5 — tell the watch the key

On the watch: **Wearsic → Settings → API Key** → type/paste the *same* key.
The app now sends it (`X-Wearsic-Key` header) automatically with every request.

---

## 5. Connect the watch (choose ONE)

### A. Same WiFi network (simplest)

1. Find the server phone's IP: in Termux run
   ```bash
   ifconfig wlan0 | grep inet
   ```
   (e.g. `192.168.1.42`)
2. On the watch: **Settings → Server URL** → `http://192.168.1.42:8080`
3. Works only when both devices are on the same WiFi.

### B. Tailscale private network (works everywhere)

1. Install Tailscale on the **server phone** (Play Store) and log in
2. Install Tailscale on the **watch** (Play Store has a Wear OS version)
3. Find the phone's tailnet IP:
   ```bash
   ifconfig tun0 | grep inet     # usually 100.x.y.z
   ```
   or check https://login.tailscale.com/admin/machines
4. Watch → **Settings → Server URL** → `http://100.x.y.z:8080`
- Private, encrypted, works over any network. API key optional but recommended.

### C. Public HTTPS — Tailscale Funnel in Termux (stable URL)

The official Tailscale **app** doesn't expose Funnel on Android, but
the `tailscaled` **daemon** runs inside Termux (userspace networking —
no root, no `/dev/net/tun`) and **Funnel works through it**. The URL
is stable — `https://<phone-name>.<tailnet>.ts.net` — so you set it
on the watch once and it keeps working on any network:

```bash
wearsic server funnel
```

That command does all three steps:

1. Installs the Termux `tailscaled` build (community project
   [bropines/tailscale-termux-cli](https://github.com/bropines/tailscale-termux-cli))
   if it's missing,
2. runs `tailscale up` — open the printed link in a browser to log in,
3. runs `tailscale funnel 8080` and prints the public URL.

**First time only:** enable HTTPS certificates for your tailnet at
https://login.tailscale.com/admin/dns → **HTTPS Certificates** → Enable
(Funnel needs them; the command tells you if it's missing).

Put the printed `https://…` URL in the watch's **Settings → Server
URL**. The watch needs **no Tailscale app at all** — it just hits the
public URL.

⚠️ Funnel exposes the server to the whole internet — an API key
(Section 4) is **mandatory** (the command refuses to run without
one). Keep the Termux session open, or install Termux:Boot + set
battery unrestricted so `tailscaled` survives reboots.

The community build reports itself as a CLI client and will be
retired once upstream Tailscale 1.103 ships the Android fixes —
until then it is the working option.

**Fallback — Cloudflare Tunnel** (no account needed, but the URL
changes on every restart):

```bash
pkg install -y cloudflared
cloudflared tunnel --url http://localhost:8080
```

That prints a `https://<random>.trycloudflare.com` URL. (`wearsic
server public` prints both recipes any time.)

*Prefer private instead? Section 5-B (Tailscale VPN) needs no public
endpoint at all — but both devices need the Tailscale app.*

---

## 6. Daily-use commands — `wearsic`

The installer puts a `wearsic` command on your PATH. Everything
you need, no paths to remember:

| Action | Command |
|---|---|
| Start server | `wearsic server start` |
| Stop server | `wearsic server stop` |
| Restart | `wearsic server restart` |
| Is it running? | `wearsic server status` |
| Live logs | `wearsic server logs` |
| Health JSON | `wearsic server health` |
| **Server URL + API key** | `wearsic server url` |
| Your WiFi IP | `wearsic server ip` |
| Show / change API key | `wearsic server api key [new]` |
| Set YouTube cookie | `wearsic server cookies [str]` |
| **Public URL (Tailscale Funnel, stable)** | `wearsic server funnel` |
| Public URL recipes | `wearsic server public` |
| Free disk space | `df -h ~` |

(The raw supervisor also still works: `cd ~/wearsic-server &&
./run-termux.sh` runs it in the foreground; `Ctrl+C` stops it.)

Where your data lives:
- `~/wearsic-server/wearsic.db` — favorites & playlists (**back this up!**)
- `~/wearsic-server/.env` — API key & settings
- `~/wearsic-server/wearsic-server.log` — logs (auto-rotated at ~2 MB)
- `~/wearsic-server/wearsic-state/` — staged engine updates (safe to delete when no update is pending)

---

## 6-b. Self-healing engine updates

YouTube changes their site regularly, which can break the extraction engine
inside the server. The server now heals itself:

1. Every search/extract is counted. After repeated failures the server probes
   a **canary video** (a video that is effectively permanent on YouTube).
2. If the canary also fails, the engine is declared broken. The server
   downloads the newest `wearsic-server-termux-*.zip` from this project's
   GitHub Releases, **verifies it** (a truncated or corrupt download is
   discarded), and stages it.
3. The server exits; the supervisor applies the update and boots the new
   engine automatically. Your favorites and playlists are untouched (they live
   in `wearsic.db`), and the previous build is kept as `bin.bak`/`lib.bak`.

Rollback if a new build misbehaves:
```bash
cd ~/wearsic-server
mv bin bin.new && mv lib lib.new
mv bin.bak bin && mv lib.bak lib
bash run-termux.sh
```

Disable auto-updates (update manually instead) by adding this to `.env`:
```bash
WEARSIC_AUTO_UPDATE=0
```

Check engine health any time:
```bash
curl -s http://127.0.0.1:8080/health
```

---

## 7. Troubleshooting

| Symptom | Fix |
|---|---|
| `Missing wearsic-server binary` | You're not inside `~/wearsic-server`; re-extract the zip fully (`bin/` and `lib/` must sit next to `run-termux.sh`) |
| `Permission denied` on start | `chmod +x run-termux.sh bin/wearsic-server` |
| Search returns nothing / errors | Engine self-healing usually fixes this alone: after repeated failures the server probes a canary video, and if the engine is truly broken it downloads + stages the newest release and applies it on restart (on by default). Manual fix: `bash run-termux.sh` restart after downloading the latest ZIP |
| `Sign in to confirm you're not a bot` errors | Set a YouTube cookie: see `wearsic-server/README.md` → `WEARSIC_YOUTUBE_COOKIE` env var, or POST it to `/api/config/youtube-cookie` |
| Server dies when phone sleeps | Run `termux-wake-lock` manually; disable battery optimization for Termux (Android Settings → Apps → Termux → Battery → Unrestricted) |
| Watch shows "Host not found" | Wrong IP, different WiFi networks, or server not running — redo Section 5-A step 1 |
| Watch shows HTTP 401/403 | API key missing/different between `.env` and watch Settings — retype both |
| Port already in use | Another copy is running: `pkill -f wearsic-server` then start again |

---

## 8. Keeping it alive long-term (optional)

Install the **Termux:Boot** app from F-Droid
(https://f-droid.org/en/packages/com.termux.boot/) — it runs every script in
`~/.termux/boot/` when the phone boots. No extra `pkg` package is needed
(Termux:Boot is an app, not a Termux package):

```bash
mkdir -p ~/.termux/boot
cat > ~/.termux/boot/start-wearsic.sh <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
termux-wake-lock
exec ~/wearsic-server/run-termux.sh
EOF
chmod +x ~/.termux/boot/start-wearsic.sh
```

Also set Android Settings → Apps → Termux → Battery → **Unrestricted** so
Android never freezes the server in the background. Reboot the phone once to
confirm it comes up by itself.

---

*Server internals: [`wearsic-server/README.md`](wearsic-server/README.md).
Full endpoint reference: [`API_CONTRACT.md`](API_CONTRACT.md).
Historical jar-patch notes (obsolete): [`server-patches/PATCHES.md`](server-patches/PATCHES.md).*
