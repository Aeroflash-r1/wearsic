# Wearsic Server on Termux — Easy Setup Guide

Turn an Android phone into a Wearsic music server for your Wear OS watch.

> **Recommended:** use the one-line installer. You do **not** need root, proot, or Linux in a container.

## What you are building

~~~text
Wear OS watch
     │ HTTPS / Wi-Fi
     ▼
Android phone + Termux
     │
     ▼
Wearsic server
     │
     ▼
YouTube / audio extraction
~~~

The phone does the heavy work. The watch is mainly the player.

---

# 1. 🚀 Recommended setup — one command

## Step 1: Install Termux

Install Termux from F-Droid:

https://f-droid.org/en/packages/com.termux/

Open Termux and run:

~~~bash
pkg update -y && pkg upgrade -y
~~~

## Step 2: Install Wearsic

Copy this **one line** into Termux:

~~~bash
curl -fsSL https://raw.githubusercontent.com/Aeroflash-r1/wearsic/main/install.sh | bash
~~~

The installer will:

- detect Termux and the phone architecture
- install the required Java/curl/unzip packages
- download the latest Wearsic server
- verify the release checksum
- install the server
- generate an API key if you do not already have one
- install the wearsic command
- configure automatic startup
- start the server
- check that the server is healthy

When it finishes:

~~~bash
wearsic status
~~~

For a complete setup diagnostic:

~~~bash
wearsic doctor
~~~

A successful setup should report **READY**.

---

# 2. 🔑 Get the API key

The installer creates an API key automatically.

Show it with:

~~~bash
wearsic api-key
~~~

Or show the complete connection information:

~~~bash
wearsic url
~~~

**Do not post your API key publicly.**

If you think the key was exposed, change it:

~~~bash
wearsic api-key YOUR_NEW_KEY
wearsic restart
~~~

Then put the new key into the Wearsic watch app.

---

# 3. 📱 Connect your watch

Choose **one** method.

## Option A — Same Wi-Fi

Get the phone's Wi-Fi IP:

~~~bash
wearsic ip
~~~

You may get something similar to:

~~~text
192.168.1.42
~~~

On the watch:

**Wearsic → Settings → Server URL**

Enter:

~~~text
http://192.168.1.42:8080
~~~

Then enter the same API key.

## Option B — Public HTTPS with Tailscale Funnel

For internet access, run:

~~~bash
wearsic funnel
~~~

The first time, it may install the Termux Tailscale components and ask you to authenticate Tailscale.

It should eventually print something similar to:

~~~text
Available on the internet:
https://your-phone.your-tailnet.ts.net/
|-- proxy http://127.0.0.1:8080
~~~

Put the printed HTTPS URL into:

**Wearsic → Settings → Server URL**

Then enter your Wearsic API key.

### First-time Tailscale requirement

If Funnel says HTTPS certificates are disabled, enable them in the Tailscale admin console:

https://login.tailscale.com/admin/dns

### ⚠️ Security

Funnel makes the server reachable from the public internet.

**Keep the Wearsic API key enabled.**

Never run a public Wearsic server without authentication.

---

# 4. 🍪 YouTube cookie — only if YouTube blocks extraction

Most users should **not** need this immediately.

If the server reports:

~~~text
Sign in to confirm you're not a bot
~~~

you may need to configure a YouTube browser cookie.

Use:

~~~bash
wearsic cookies "YOUR_COOKIE_STRING"
~~~

### Important

Keep the **entire cookie inside the double quotes** because cookie strings contain semicolons.

Correct structure:

~~~bash
wearsic cookies "COOKIE_PART_1=...; COOKIE_PART_2=...; COOKIE_PART_3=..."
~~~

**Never paste your real cookie into a chat, GitHub issue, screenshot, or public post.**

A YouTube cookie is an authentication credential. Treat it like a password.

---

# 5. ▶️ Everyday commands

| What you want | Command |
|---|---|
| Start server | wearsic start |
| Stop server | wearsic stop |
| Restart server | wearsic restart |
| Check status | wearsic status |
| Check health | wearsic health |
| Check readiness | wearsic ready |
| Live logs | wearsic logs |
| Server URL + API key | wearsic url |
| Show/change API key | wearsic api-key [new] |
| Phone Wi-Fi IP | wearsic ip |
| Update Wearsic | wearsic update |
| Diagnose problems | wearsic doctor |
| Set YouTube cookie | wearsic cookies [str] |
| Start public Funnel | wearsic funnel |
| Public URL options | wearsic public |
| Installed version | wearsic version |

Run:

~~~bash
wearsic help
~~~

at any time for the command list.

---

# 6. 🔄 Updating Wearsic

To update to the newest release:

~~~bash
wearsic update
~~~

The updater verifies releases before installing them and keeps your existing database and API configuration.

Check the installed/running version:

~~~bash
wearsic version
~~~

---

# 7. 🩺 Troubleshooting — start here

If something is wrong, **do not change random files first**.

Run:

~~~bash
wearsic doctor
wearsic status
wearsic health
wearsic ready
~~~

Then check the logs:

~~~bash
wearsic logs
~~~

## Command not found

Close and reopen Termux, then try:

~~~bash
wearsic help
~~~

If it still does not exist, rerun the installer:

~~~bash
curl -fsSL https://raw.githubusercontent.com/Aeroflash-r1/wearsic/main/install.sh | bash
~~~

The installer preserves an existing database and API key.

## Server is stopped

~~~bash
wearsic start
wearsic status
~~~

## Server is unhealthy

~~~bash
wearsic doctor
wearsic logs
~~~

## Phone kills the server in the background

Android battery management can stop Termux.

Set:

**Android Settings → Apps → Termux → Battery → Unrestricted**

Also run:

~~~bash
termux-wake-lock
~~~

## Tailscale says "Logged out"

Run:

~~~bash
wearsic funnel
~~~

Open the authentication URL it prints and complete Tailscale login.

## Funnel does not start

Run:

~~~bash
wearsic doctor
~~~

If it mentions HTTPS certificates, enable them at:

https://login.tailscale.com/admin/dns

Then retry:

~~~bash
wearsic funnel
~~~

## Watch says "Host not found"

Check:

~~~bash
wearsic status
wearsic url
~~~

For Wi-Fi mode, make sure the phone and watch can reach each other.

For Funnel mode, use the HTTPS URL printed by wearsic funnel.

## Watch gets HTTP 401/403

The watch API key does not match the server API key.

On the phone:

~~~bash
wearsic api-key
~~~

Enter that exact key on the watch.

## YouTube says "Sign in to confirm you're not a bot"

Configure a YouTube cookie:

~~~bash
wearsic cookies "YOUR_COOKIE_STRING"
~~~

Then check:

~~~bash
wearsic health
~~~

---

# 8. 🔋 Start Wearsic automatically after reboot (optional)

Install **Termux:Boot**:

https://f-droid.org/en/packages/com.termux.boot/

Then run:

~~~bash
mkdir -p ~/.termux/boot

cat > ~/.termux/boot/start-wearsic.sh <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
termux-wake-lock
exec ~/wearsic-server/run-termux.sh
EOF

chmod +x ~/.termux/boot/start-wearsic.sh
~~~

Also set Termux battery usage to **Unrestricted**.

After reboot, check:

~~~bash
wearsic status
~~~

---

# 9. 💾 Important files

Main server directory:

~~~text
~/wearsic-server/
~~~

Important files:

| File/folder | Purpose |
|---|---|
| wearsic.db | Favorites and playlists |
| .env | API key and server settings |
| wearsic-server.log | Server logs |
| wearsic-state/ | Engine update state |

**Back up wearsic.db** if your favorites/playlists are important.

Do not upload .env publicly because it can contain your API key and private configuration.

---

# 10. 🧪 Quick health test

Check the server:

~~~bash
wearsic health
~~~

A healthy server should report:

~~~json
{"status":"ok"}
~~~

The exact JSON may contain additional fields depending on the server version.

Check whether it can serve music:

~~~bash
wearsic ready
~~~

---

# 11. 🆘 The three commands to remember

If you remember only three commands:

~~~bash
wearsic status
wearsic doctor
wearsic logs
~~~

**status** → Is it running?

**doctor** → What is wrong?

**logs** → What actually happened?

For most users, the complete setup is:

~~~bash
pkg update -y && pkg upgrade -y
curl -fsSL https://raw.githubusercontent.com/Aeroflash-r1/wearsic/main/install.sh | bash
wearsic status
~~~

Then choose:

~~~bash
wearsic ip
~~~

for local Wi-Fi, or:

~~~bash
wearsic funnel
~~~

for public HTTPS.

---

*For server internals, see wearsic-server/README.md.  
For the HTTP API, see API_CONTRACT.md.*
