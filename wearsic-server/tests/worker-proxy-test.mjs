/**
 * Unit tests for the Cloudflare Worker front door.
 *
 * The Worker is the one piece of this feature that is NOT shell and NOT
 * Kotlin, and it carries the two properties that decide whether audio works
 * at all:
 *
 *   1. Range requests must survive. The engine serves byte-range audio; if
 *      the proxy drops `Range`, every seek re-downloads the whole file.
 *   2. The response body must stream. Buffering a whole song in the Worker
 *      would add seconds of latency and blow the memory limit on long tracks.
 *
 * These run in Node against the REAL worker source, so a syntax error or a
 * dropped header fails here rather than at deploy time.
 *
 * Usage:  node wearsic-server/tests/worker-proxy-test.mjs
 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const WORKER = join(HERE, "..", "wearsic-cloudflare-worker.js");

let pass = 0;
let fail = 0;
const ok = (name) => { console.log(`  ok   ${name}`); pass++; };
const bad = (name, detail) => { console.log(`  FAIL ${name}${detail ? ` — ${detail}` : ""}`); fail++; };

const mod = await import(pathToFileURL(WORKER).href);
const worker = mod.default;

assert.equal(typeof worker, "object", "the Worker must export an object");
assert.equal(typeof worker.fetch, "function", "the Worker must export a fetch handler");

/** Run the handler with a stubbed upstream and capture what it was called with. */
async function call(request, { origin = "https://abc.trycloudflare.com", upstream } = {}) {
  let seen = null;
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (url, init) => {
    seen = { url: String(url), init };
    if (upstream) return upstream;
    return new Response('{"ok":1}', { status: 200, headers: { "content-type": "application/json" } });
  };
  try {
    const res = await worker.fetch(request, { ORIGIN: origin });
    return { res, seen };
  } finally {
    globalThis.fetch = realFetch;
  }
}

// ── the origin is taken from the binding, not from the request ──────────────
{
  const { res, seen } = await call(new Request("https://wearsic.testsub.workers.dev/api/health"));
  assert.equal(seen.url, "https://abc.trycloudflare.com/api/health");
  ok("forwards to the ORIGIN binding");

  const { res: r2 } = await call(new Request("https://wearsic.testsub.workers.dev/api/health"), { origin: "" });
  assert.equal(r2.status, 500);
  ok("refuses to run without an ORIGIN binding");
}

// ── path AND query are preserved ───────────────────────────────────────────
{
  const { seen } = await call(
    new Request("https://wearsic.testsub.workers.dev/api/search?q=daft%20punk&limit=5")
  );
  assert.equal(seen.url, "https://abc.trycloudflare.com/api/search?q=daft%20punk&limit=5");
  ok("preserves the path and the query string");
}

// ── the audio-critical header ──────────────────────────────────────────────
{
  const { seen } = await call(
    new Request("https://wearsic.testsub.workers.dev/api/stream/xyz", {
      headers: { Range: "bytes=100-200" },
    })
  );
  assert.equal(seen.init.headers.get("range"), "bytes=100-200");
  ok("forwards the Range header (byte-range audio survives)");
}

// ── the API key is forwarded unchanged ─────────────────────────────────────
{
  const { seen } = await call(
    new Request("https://wearsic.testsub.workers.dev/api/favorites", {
      headers: { "X-Wearsic-Key": "sekret-key-1" },
    })
  );
  assert.equal(seen.init.headers.get("x-wearsic-key"), "sekret-key-1");
  ok("forwards the API key unchanged");
}

// ── hop-by-hop headers are NOT forwarded ───────────────────────────────────
{
  const { seen } = await call(
    new Request("https://wearsic.testsub.workers.dev/api/health", {
      headers: { Host: "evil.example.com", Connection: "keep-alive" },
    })
  );
  assert.equal(seen.init.headers.get("host"), null, "Host must not be forwarded");
  assert.equal(seen.init.headers.get("connection"), null, "Connection must not be forwarded");
  ok("strips hop-by-hop headers (Host, Connection)");
}

// ── the response is streamed, not buffered ────────────────────────────────
{
  // A body that is not fully available must still be handed back as a
  // stream, not awaited. If the Worker buffered, the response would only
  // settle after the whole track arrived.
  let pulled = 0;
  const stream = new ReadableStream({
    pull(controller) {
      pulled++;
      if (pulled > 2) { controller.close(); return; }
      controller.enqueue(new TextEncoder().encode("chunk" + pulled));
    },
  });
  const { res } = await call(new Request("https://wearsic.testsub.workers.dev/api/stream/x"), {
    upstream: new Response(stream, { status: 206, headers: { "content-range": "bytes 0-99/1000" } }),
  });
  assert.equal(res.status, 206);
  assert.equal(res.headers.get("content-range"), "bytes 0-99/1000");
  const reader = res.body.getReader();
  const first = await reader.read();
  assert.equal(new TextDecoder().decode(first.value), "chunk1");
  ok("streams the response body (first chunk arrives before the rest)");
}

// ── the edge cache is off ─────────────────────────────────────────────────
{
  const { seen } = await call(new Request("https://wearsic.testsub.workers.dev/api/health"));
  assert.equal(seen.init.cf?.cacheTtl, 0);
  ok("disables the edge cache");
}

// ── redirects are not followed ─────────────────────────────────────────────
{
  const { seen } = await call(new Request("https://wearsic.testsub.workers.dev/api/health"));
  assert.equal(seen.init.redirect, "manual");
  ok("does not follow redirects");
}

// ── a dead origin is a clear 502, not a stack trace ───────────────────────
{
  const realFetch = globalThis.fetch;
  globalThis.fetch = async () => { throw new Error("ECONNREFUSED"); };
  let res;
  try {
    res = await worker.fetch(new Request("https://wearsic.testsub.workers.dev/health"), {
      ORIGIN: "https://abc.trycloudflare.com",
    });
  } finally {
    globalThis.fetch = realFetch;
  }
  assert.equal(res.status, 502);
  const body = JSON.parse(await res.text());
  assert.equal(body.error, "origin_unreachable");
  ok("reports a dead origin as a clean 502");
}

// ── end-to-end response headers are preserved ──────────────────────────────
{
  const { res } = await call(new Request("https://wearsic.testsub.workers.dev/api/stream/x"), {
    upstream: new Response("0123456789", {
      status: 206,
      headers: {
        "content-range": "bytes 0-9/1000",
        "content-length": "10",
        "accept-ranges": "bytes",
        etag: '"abc123"',
        "content-type": "audio/mpeg",
      },
    }),
  });
  assert.equal(res.status, 206);
  assert.equal(res.headers.get("content-range"), "bytes 0-9/1000");
  assert.equal(res.headers.get("content-length"), "10", "Content-Length is end-to-end, not hop-by-hop");
  assert.equal(res.headers.get("accept-ranges"), "bytes");
  assert.equal(res.headers.get("etag"), '"abc123"');
  assert.equal(res.headers.get("content-type"), "audio/mpeg");
  ok("preserves Content-Length, Content-Range, Accept-Ranges, ETag and Content-Type");
}

// ── a same-origin redirect stays on the permanent hostname ─────────────────
{
  const { res } = await call(new Request("https://wearsic.testsub.workers.dev/api/old"), {
    upstream: new Response(null, {
      status: 302,
      headers: { location: "https://abc.trycloudflare.com/api/new?x=1" },
    }),
  });
  assert.equal(res.status, 302);
  assert.equal(res.headers.get("location"), "https://wearsic.testsub.workers.dev/api/new?x=1");
  ok("rewrites a same-origin redirect to the permanent host (no tunnel leak)");
}

// ── a relative redirect is also rewritten ──────────────────────────────────
{
  const { res } = await call(new Request("https://wearsic.testsub.workers.dev/api/old"), {
    upstream: new Response(null, { status: 307, headers: { location: "/api/new" } }),
  });
  assert.equal(res.status, 307);
  assert.equal(res.headers.get("location"), "https://wearsic.testsub.workers.dev/api/new");
  ok("rewrites a relative redirect to the permanent host");
}

// ── an external redirect is passed through untouched ───────────────────────
{
  const { res } = await call(new Request("https://wearsic.testsub.workers.dev/api/old"), {
    upstream: new Response(null, {
      status: 302,
      headers: { location: "https://cdn.example.com/file" },
    }),
  });
  assert.equal(res.headers.get("location"), "https://cdn.example.com/file");
  ok("leaves an external redirect untouched");
}

// ── POST bodies stream through ─────────────────────────────────────────────
{
  const req = new Request("https://wearsic.testsub.workers.dev/api/favorites", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: '{"videoId":"abc"}',
    duplex: "half",
  });
  const { seen } = await call(req);
  assert.equal(seen.init.method, "POST");
  const forwarded = await new Response(seen.init.body).text();
  assert.equal(forwarded, '{"videoId":"abc"}');
  ok("streams the request body to the origin");
}

// ── the real client IP is visible to the engine's rate limiter ──────────────
{
  const { seen } = await call(
    new Request("https://wearsic.testsub.workers.dev/api/search?q=x", {
      headers: { "CF-Connecting-IP": "203.0.113.9" },
    })
  );
  assert.equal(seen.init.headers.get("x-forwarded-for"), "203.0.113.9");
  assert.equal(seen.init.headers.get("x-forwarded-host"), "wearsic.testsub.workers.dev");
  ok("forwards the real client IP and the permanent host");
}

// ── multiple Set-Cookie headers survive ────────────────────────────────────
{
  const h = new Headers();
  h.append("set-cookie", "a=1; Path=/");
  h.append("set-cookie", "b=2; Path=/");
  const { res } = await call(new Request("https://wearsic.testsub.workers.dev/api/login"), {
    upstream: new Response("ok", { headers: h }),
  });
  const cookies = typeof res.headers.getSetCookie === "function" ? res.headers.getSetCookie() : [];
  assert.equal(cookies.length, 2);
  assert.ok(cookies.includes("a=1; Path=/"));
  assert.ok(cookies.includes("b=2; Path=/"));
  ok("preserves multiple Set-Cookie headers");
}

// ── bodyless statuses stay bodyless ────────────────────────────────────────
{
  const { res } = await call(new Request("https://wearsic.testsub.workers.dev/api/x"), {
    upstream: new Response(null, { status: 204 }),
  });
  assert.equal(res.status, 204);
  assert.equal(res.body, null);
  ok("does not attach a body to a 204 response");
}

// ── a failure body never leaks the tunnel hostname or the error ─────────────
{
  const realFetch = globalThis.fetch;
  globalThis.fetch = async () => { throw new Error("ECONNREFUSED https://abc.trycloudflare.com"); };
  let res;
  try {
    res = await worker.fetch(new Request("https://wearsic.testsub.workers.dev/health"), {
      ORIGIN: "https://abc.trycloudflare.com",
    });
  } finally {
    globalThis.fetch = realFetch;
  }
  const text = await res.text();
  assert.ok(!text.includes("trycloudflare.com"), "the public error must not reveal the tunnel hostname");
  assert.ok(!text.includes("ECONNREFUSED"), "the public error must not reveal the runtime error");
  const body = JSON.parse(text);
  assert.ok(body.request_id, "a request id is included for support");
  ok("failure bodies are opaque (no origin or stack trace)");
}

// ── the source really is the file we think it is ──────────────────────────
{
  const src = readFileSync(WORKER, "utf8");
  assert.ok(src.includes("export default"), "the Worker must use the module syntax");
  assert.ok(!/HOP_BY_HOP[\s\S]{0,400}"range"/.test(src), "Range must not be in the hop-by-hop list");
  ok("the shipped source keeps Range out of the hop-by-hop list");
}

console.log("");
if (fail === 0) {
  console.log(`worker-proxy-test: PASSED (${pass} checks)`);
  process.exit(0);
}
console.log(`worker-proxy-test: FAILED (${fail} of ${pass + fail} checks)`);
process.exit(1);
