package com.wearsic.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Rotation-ticket invariants for the mid-song URL pool — the byte-safety
 * rules behind multi-URL streaming:
 *
 *  - a resumed stream may only switch to a URL with the IDENTICAL encoding
 *    (same itag); cross-encoding bytes encode a different timeline and would
 *    jump the audio,
 *  - incompatible requests leave the queue untouched (never drain it),
 *  - pool merges never duplicate URLs and respect the cap.
 */
class TicketPoolTest {

    private var n = 0

    private fun ticket(
        itag: Int,
        mime: String = "audio/mp4; codecs=\"mp4a.40.2\"",
        client: String = "IOS",
    ): YoutubeGateway.LeanAudio =
        YoutubeGateway.LeanAudio("https://cdn.example/$itag/${n++}", mime, 128, itag, client)

    @Test
    fun `same itag is popped first even if later in the queue`() {
        val q = ArrayDeque(listOf(ticket(251, "audio/webm"), ticket(140), ticket(139)))
        val picked = selectCompatibleTicket(q, preferItag = 140, preferMime = null, requireCompatible = true)
        assertEquals(140, picked?.itag)
        assertEquals(listOf(251, 139), q.map { it.itag })
    }

    @Test
    fun `requireCompatible refuses to cross encodings mid-stream`() {
        val q = ArrayDeque(listOf(ticket(251, "audio/webm"), ticket(249, "audio/webm")))
        assertNull(
            selectCompatibleTicket(q, preferItag = 140, preferMime = "audio/mp4", requireCompatible = true),
            "must not pick a different encoding for a live stream",
        )
        assertEquals(2, q.size, "queue must not be drained when nothing is compatible")
    }

    @Test
    fun `itag-less targets fall back to mime compatibility`() {
        val q = ArrayDeque(listOf(ticket(251, "audio/webm"), ticket(0, "audio/mp4")))
        val picked = selectCompatibleTicket(
            q, preferItag = 0, preferMime = "audio/mp4; codecs=\"mp4a.40.2\"", requireCompatible = true,
        )
        assertEquals(0, picked?.itag)
        assertEquals(1, q.size)
    }

    @Test
    fun `no compatibility requirement takes any ticket`() {
        val q = ArrayDeque(listOf(ticket(251, "audio/webm")))
        val picked = selectCompatibleTicket(q, preferItag = 140, preferMime = null, requireCompatible = false)
        assertEquals(251, picked?.itag)
        assertTrue(q.isEmpty())
    }

    @Test
    fun `merge skips duplicate urls and respects the cap`() {
        val q = ArrayDeque(listOf(ticket(140)))
        val dup = q.first()
        val added = mergeTickets(q, listOf(dup, ticket(140), ticket(251, "audio/webm")), maxSize = 2)
        assertEquals(1, added)
        assertEquals(2, q.size)
    }

    @Test
    fun `rotation drains every same-itag ticket across clients`() {
        val q = ArrayDeque(
            listOf(
                ticket(140, client = "IOS"),
                ticket(140, client = "ANDROID_VR"),
                ticket(251, "audio/webm", "TVHTML5_SIMPLY_EMBEDDED_PLAYER"),
            )
        )
        val seen = mutableListOf<YoutubeGateway.LeanAudio>()
        while (true) {
            val t = selectCompatibleTicket(q, preferItag = 140, preferMime = "audio/mp4", requireCompatible = true)
                ?: break
            seen.add(t)
        }
        assertEquals(listOf("IOS", "ANDROID_VR"), seen.map { it.client })
        // The cross-encoded ticket stays for the entry-point (non-compatible) path.
        assertEquals(1, q.size)
    }

    @Test
    fun `prepended tickets win the next rotation and keep their order`() {
        val q = ArrayDeque(listOf(ticket(140, client = "IOS"), ticket(251, "audio/webm", "IOS")))
        val extractor140 = ticket(140, client = "extractor")
        val extractor139 = ticket(139, client = "extractor")
        val added = mergeTickets(q, listOf(extractor140, extractor139), maxSize = 40, prepend = true)
        assertEquals(2, added)
        // Fresh extractor tickets sit at the head, then the lean leftovers.
        assertEquals(listOf(140, 139, 140, 251), q.map { it.itag })
        // A mid-stream rotation must take the extractor ticket FIRST — it is
        // the proven-unwalled source; the lean same-itag stays for later.
        val picked = selectCompatibleTicket(q, preferItag = 140, preferMime = "audio/mp4", requireCompatible = true)
        assertEquals("extractor", picked?.client)
    }

    @Test
    fun `prepend still skips duplicate urls`() {
        val existing = ticket(140, client = "extractor")
        val q = ArrayDeque(listOf(existing))
        val added = mergeTickets(q, listOf(existing, ticket(139, client = "extractor")), maxSize = 40, prepend = true)
        assertEquals(1, added)
        assertEquals(2, q.size)
    }
}
