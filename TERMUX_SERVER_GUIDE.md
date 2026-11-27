# Wearsic Server — Termux Guide

This guide covers running the Wearsic server on an Android phone with
Termux. The server is a small Ktor process that bridges your watch to
YouTube.

## Requirements

- Termux (F-Droid version recommended)
- JDK 17+ (`pkg install openjdk-21`)
- ffmpeg (optional but recommended for Opus/WebM-only tracks:
  `pkg install ffmpeg`)
- ~200 MB free space

## Install

One command (or download the release `.sh` and run it):

```bash
curl -fsSL https://github.com/Aeroflash-r1/wearsic/releases/latest/download/wearsic-server-v1.0.0.sh -o wearsic-server.sh
chmod +x wearsic-server.sh
./wearsic-server.sh install
```

The installer sets up the `wearsic` command, starts the server, and enables
autostart via Termux:Boot.

## Start / stop / status

```bash
wearsic start
wearsic stop
wearsic status
wearsic restart
wearsic url    # prints the LAN address and the auto-generated API key
```

The server listens on `0.0.0.0:8080` by default. Override with
`PORT=9000 wearsic start`.

## Pair the watch

1. On the watch, open **Wearsic → Settings → Server URL / API key**.
2. Use the address from `wearsic url`.

Or push the pairing file over adb (no typing on the watch):

```bash
wearsic pair
```

Sideload a release APK with the CLI:

```bash
wearsic sideload v1.0.0
```

Health panel shows the running engine version (CLI box):

```
│   version     v1.0.0   │
```

This writes `wearsic-pairing.env` into the app's external files directory;
the app applies and deletes it on next launch.

## Optional: expose beyond your LAN

- **Cloudflare tunnel** (public HTTPS URL):
  ```bash
  wearsic cloudflare
  ```
- **Tailscale** (private mesh): install Tailscale on the phone, then use
  the Tailscale IP/`--ts` flag. The server URL stays private.

## YouTube cookie (bot-wall workaround)

If searches/streams fail with "bot challenge" style errors, add a YouTube
session cookie:

```bash
wearsic cookies
```

The cookie is stored locally and sent only to YouTube.

## AI re-ranker (optional)

Any OpenAI-compatible provider can personalize "Made for you":

```bash
export WEARSIC_AI_API_KEY=gsk_...
export WEARSIC_AI_BASE_URL=https://api.groq.com/openai/v1
export WEARSIC_AI_MODEL=llama-3.3-70b-versatile
wearsic restart
```

If any AI call fails the server silently falls back to classic ranking.

## Self-hosted security

- The installer already generates `WEARSIC_API_KEY` into `.env`; set a
  longer one (or rotate it with `wearsic api-key`) if the server is reachable
  beyond your phone.
- Never expose an unauthenticated server to the public internet.
- Keep the YouTube cookie and AI key secret.

## Troubleshooting

| Symptom | Likely fix |
|---|---|
| Watch can't reach server | Same Wi-Fi; check `wearsic url`; firewall |
| Opus/WebM songs 503 | `pkg install ffmpeg` then restart |
| Search/stream bot challenges | Add a YouTube cookie (`wearsic cookies`) |
| Server not starting | Check `wearsic-server.log`; JDK 17+ |
| Streaming is slow | Prewarm on the watch improves first-byte |

Full server details: [wearsic-server/README.md](wearsic-server/README.md).
