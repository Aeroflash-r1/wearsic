# Security Policy

## Supported versions

| Version | Supported |
|---|---|
| 2.x (main branch) | ✅ |
| 1.x | ❌ |

## Reporting a vulnerability

If you find a security issue, **do not open a public issue**. Send a
private report to the maintainers via a GitHub issue with the "security"
label, or contact them through the repository's GitHub profile.

Please include:

- the affected component (App or Server) and version;
- a minimal reproduction;
- the impact, if you know it.

You should receive an initial response within one week. We treat security
reports as confidential until a fix is available.

## Self-hosted deployment notes

The Server is designed to run on a private LAN or behind a private tunnel.

- **Keep `WEARSIC_API_KEY` set** — the installer auto-generates one into
  `.env`; replace it with a longer key when the Server is reachable by more
  than yourself.
- Never expose the Server's administrative endpoints to the public internet
  without authentication.
- The YouTube cookie and the AI API key are secrets: protect them like
  passwords.
- Keep the Server up to date to receive security fixes.
