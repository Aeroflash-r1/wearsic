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
 * The engine serves AUDIO over HTTP byte ranges, so these are non-negotiable:
 *
 *   1. Range requests must survive. The engine answers `Range: bytes=N-`
 *      with a 206 and a Content-Range; dropping the header (or the 206)
 *      turns every seek into a full re-download and playback stalls.
 *   2. The response body must STREAM. Buffering a whole song in the Worker
 *      before replying would add seconds of latency and blow the memory
 *      limit on long tracks. `upstream.body` is piped straight through.
 *   3. The auth header must pass through untouched. The Worker never sees,
 *      stores, or invents the shared secret.
 *   4. Redirects must not send the caller to the random tunnel hostname.
 *      Same-origin redirects are rewritten to the permanent hostname.
 *
 * Hop-by-hop headers are stripped in both directions: they describe the
 * single TCP hop they arrived on and are invalid to forward. End-to-end
 * headers (Content-Length, Content-Range, Accept-Ranges, ETag, ...) are
 * preserved verbatim.
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

function requestId() {
  try {
    return crypto.randomUUID();
  } catch {
    return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
  }
}

function jsonError(status, error, id) {
  // Never echo the tunnel origin, the request URL, or a runtime stack trace:
  // the body is public. The request id and the worker log carry the detail.
  return new Response(JSON.stringify({ error, request_id: id }), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "x-wearsic-frontdoor": "1",
    },
  });
}

/**
 * Point a same-origin Location at the permanent hostname. A redirect to a
 * different origin (e.g. an external CDN) is passed through untouched.
 */
function rewriteLocation(location, upstreamUrl, publicUrl) {
  if (!location) return location;
  try {
    const loc = new URL(location, upstreamUrl);
    if (loc.host === upstreamUrl.host) {
      loc.protocol = publicUrl.protocol;
      loc.host = publicUrl.host;
      return loc.toString();
    }
  } catch {
    // A Location that is not a URL (or not parseable) is passed through.
  }
  return location;
}

export default {
  async fetch(request, env) {
    const id = requestId();
    const origin = env.ORIGIN;
    if (!origin) {
      return jsonError(500, "origin_not_configured", id);
    }

    let target;
    try {
      target = new URL(origin);
    } catch {
      console.error(`[${id}] ORIGIN binding is not a URL`);
      return jsonError(500, "origin_invalid", id);
    }

    // Preserve path AND query — the engine routes on both.
    const incoming = new URL(request.url);
    target.pathname = incoming.pathname;
    target.search = incoming.search;
    target.hash = "";

    const headers = stripHopByHop(request.headers);
    headers.set("x-forwarded-host", incoming.host);
    headers.set("x-forwarded-proto", "https");
    // Keep the real client address visible to the engine's rate limiter.
    // Cloudflare sets CF-Connecting-IP on the request to this Worker; the
    // tunnel hop would otherwise see only Cloudflare's edge address.
    const clientIp = request.headers.get("cf-connecting-ip");
    if (clientIp) {
      const prior = headers.get("x-forwarded-for");
      headers.set("x-forwarded-for", prior ? `${prior}, ${clientIp}` : clientIp);
    }

    const method = request.method;
    const bodyless = method === "GET" || method === "HEAD" || method === "OPTIONS";

    let upstream;
    try {
      upstream = await fetch(target.toString(), {
        method,
        headers,
        body: bodyless ? undefined : request.body,
        // Node's undici (used by the unit tests) requires this for a stream
        // body; the Workers runtime ignores it. Without it the request body
        // would have to be buffered, which this proxy deliberately avoids.
        ...(bodyless ? {} : { duplex: "half" }),
        // Do not follow redirects: the caller must see them, and following
        // one would silently re-target the tunnel.
        redirect: "manual",
        // Audio must never be cached — a cached track would be served
        // forever, and the tunnel hostname would stop mattering.
        cf: { cacheTtl: 0, cacheEverything: false },
      });
    } catch (err) {
      // The tunnel is down or the origin refused. Say so precisely: the
      // permanent URL is fine, the tunnel behind it is not. The real error
      // (which may contain the random tunnel hostname) stays in the log.
      console.error(`[${id}] origin fetch failed: ${err}`);
      return jsonError(502, "origin_unreachable", id);
    }

    const resHeaders = stripHopByHop(upstream.headers);

    // Set-Cookie may legitimately appear more than once; Headers iteration
    // folds them together, so copy the individual values when the runtime
    // exposes them.
    resHeaders.delete("set-cookie");
    const cookies =
      typeof upstream.headers.getSetCookie === "function"
        ? upstream.headers.getSetCookie()
        : [];
    if (cookies.length > 0) {
      for (const cookie of cookies) resHeaders.append("set-cookie", cookie);
    } else {
      const cookie = upstream.headers.get("set-cookie");
      if (cookie) resHeaders.set("set-cookie", cookie);
    }

    const location = resHeaders.get("location");
    if (location) {
      resHeaders.set("location", rewriteLocation(location, target, incoming));
    }

    // Never let a SHARED cache store an authenticated API response or a byte
    // range (the edge sees every request, so it must not cache anything the
    // watch's private cache is allowed to). But the ENGINE publishes its own
    // `Cache-Control: private, max-age=…` on the JSON discovery endpoints
    // (search/playlists/favorites) exactly so the WATCH's 16MB OkHttp disk
    // cache can skip a relayed round-trip. Overwriting that with no-store —
    // as earlier builds did — silently disabled the watch-side cache for
    // every front-door user. Preserve the engine's header when it exists and
    // the response is not an audio/stream range; default to no-store for
    // everything else (streams, errors, mutations, fallback).
    const isRangeResponse = upstream.headers.get("content-range") !== null;
    const upstreamCacheControl = upstream.headers.get("cache-control");
    if (upstreamCacheControl && !isRangeResponse) {
      resHeaders.set("cache-control", upstreamCacheControl);
    } else {
      resHeaders.set("cache-control", "no-store");
    }
    resHeaders.set("x-wearsic-frontdoor", "1");

    // 204/205/304 must not carry a body. Everything else streams through.
    const nullBody =
      upstream.status === 204 || upstream.status === 205 || upstream.status === 304;

    return new Response(nullBody ? null : upstream.body, {
      status: upstream.status,
      statusText: upstream.statusText,
      headers: resHeaders,
    });
  },
};
