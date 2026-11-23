# Wearsic Privacy Policy

Last updated: 2026-11-22

## 1. Overview

Wearsic is a music player for Wear OS that streams content from YouTube via
a **self-hosted server that you run**. No cloud service operated by the
maintainers is involved. This policy describes what data is handled, where
it lives, and how it is shared.

## 2. Data stored on your watch

The App stores the following **locally on your device**:

- your server address and API key (DataStore);
- favorites, recently played tracks, downloads and lyrics cache (Room
  database on the watch);
- app preferences (theme, accent, crossfade, etc.).

This data never leaves your watch except in the requests described below.

## 3. Data stored on your server

The Server stores in a local SQLite database (`wearsic.db`):

- your favorites and playlists (so the watch can restore them);
- a surrogate-ID cache for favorites;
- health/update state.

This database lives on your own machine and is never transmitted to us.

## 4. Data sent to YouTube

To resolve and stream tracks, the Server sends **video IDs and search
queries** to YouTube/YouTube Music. The Server does **not** upload your
favorites list or personal data to YouTube beyond the specific track or
query you request.

## 5. Optional: YouTube cookie

If you configure a YouTube cookie (a bot-challenge workaround), it is used
only to authenticate requests from your Server to YouTube. It is stored
locally on your Server and never logged or transmitted anywhere else.

## 6. Optional: AI provider

If you configure an OpenAI-compatible API key (`WEARSIC_AI_API_KEY`), song
titles and artist names from your recommendation pool are sent to that
provider **only** to re-rank suggestions. This is disabled by default. You
are responsible for the provider you choose and its privacy terms.

## 7. Audio content

The Server **relays** audio streams from YouTube to your watch; it does not
permanently store audio you stream. Downloaded tracks are stored only on
your watch when you explicitly download them.

## 8. Analytics

Wearsic does **not** include analytics, advertising SDKs, or remote
telemetry.

## 9. Your choices

- You can delete your server database to erase server-side state.
- You can clear App data on the watch to erase local state.
- You can disable the AI provider or remove the YouTube cookie at any time.

## 10. Contact

For privacy questions, open an issue at
https://github.com/Aeroflash-r1/wearsic/issues.
