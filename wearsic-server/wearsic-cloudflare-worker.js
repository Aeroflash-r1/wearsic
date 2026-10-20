/**
 * Wearsic public front door — a Cloudflare Worker that forwards every request
 * to the Wearsic engine running behind a Cloudflare Quick Tunnel.
 *
 * WHY THIS EXISTS
 * ---------------
 * A Cloudflare Quick Tunnel gives a free, fast, low-latency public URL
 * (https://<random>.trycloudflare.com) with NO account, NO domain and NO
 * money. Its one flaw: the hostname is random and changes every time the
 * tunnel restarts, so a watch app cannot remember it.
 *
 * This Worker is the fix. It lives at a PERMANENT address
 * (https://<worker>.<subdomain>.workers.dev) and proxies to whichever
 * Quick Tunnel hostname is current. When the tunnel hands out a new random
 * hostname, the CLI re-uploads this Worker with a new ORIGIN binding and
 * the permanent address keeps working — the watch never notices.
 *
 * That buys a stable URL for free, without owning a domain.
 *
 * WHAT THIS PROXY MUST GET RIGHT
 * ------------------------------
 * The engine serves AUDIO over HTTP byte ranges, so two things are
 * non-negotiable:
 *
 *   1. Range requests must survive. The engine answers `Range: bytes=N-`
 *      with a 206 and a Content-Range; dropping the header turns every
 *      seek into a full re-download and playback stalls.
 *   2. The response body must STREAM. Buffering a whole song in the Worker
 *      before replying would add seconds of latency and blow the memory
 *      limit on long tracks. `upstream.body` is piped straight through.
 *
 * Hop-by-hop headers are stripped in both directions: they describe the
 * single TCP hop they arrived on and are invalid to forward.
 */

const HOP_BY_HOP = new Set([
  "connection",
  "keep-alive",
  "proxy-authenticate",
  "proxy-authorization",
  "te",
  "trailer",
  "transfer-encoding",
  "upgrade",
  // The origin is chosen by us, not by the caller. Forwarding the caller's
  // Host header would make the tunnel answer for the wrong vhost.
  "host",
  // The body may be re-chunked by the runtime; a stale length breaks it.
  "content-length",
]);

function stripHopByHop(headers) {
  const out = new Headers();
  for (const [key, value] of headers) {
    if (!HOP_BY_HOP.has(key.toLowerCase())) {
      out.set(key, value);
    }
  }
  return out;
}

function jsonError(status, error, detail) {
  return new Response(JSON.stringify({ error, detail: detail || undefined }), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

export default {
  async fetch(request, env) {
    const origin = env.ORIGIN;
    if (!origin) {
      return jsonError(500, "origin_not_configured");
    }

    let target;
    try {
      target = new URL(origin);
    } catch {
      return jsonError(500, "origin_invalid", String(origin));
    }

    // Preserve path AND query — the engine routes on both.
    const incoming = new URL(request.url);
    target.pathname = incoming.pathname;
    target.search = incoming.search;

    const headers = stripHopByHop(request.headers);
    headers.set("x-forwarded-host", incoming.host);
    headers.set("x-forwarded-proto", "https");

    const method = request.method;
    const bodyless = method === "GET" || method === "HEAD";

    let upstream;
    try {
      upstream = await fetch(target.toString(), {
        method,
        headers,
        body: bodyless ? undefined : request.body,
        // Do not follow redirects: the caller must see them verbatim, and
        // following one would silently re-target the tunnel.
        redirect: "manual",
        // Audio must never be cached — a cached track would be served
        // forever and the tunnel hostname would stop mattering.
        cf: { cacheTtl: 0, cacheEverything: false },
      });
    } catch (err) {
      // The tunnel is down or the origin refused. Say so precisely: the
      // permanent URL is fine, the tunnel behind it is not.
      return jsonError(502, "origin_unreachable", String(err));
    }

    const resHeaders = stripHopByHop(upstream.headers);
    // Content-Length is not valid once the body is re-framed by the runtime.
    resHeaders.delete("content-length");
    resHeaders.set("cache-control", "no-store");

    // Pipe the body straight through — no buffering, no size limit.
    return new Response(upstream.body, {
      status: upstream.status,
      statusText: upstream.statusText,
      headers: resHeaders,
    });
  },
};
