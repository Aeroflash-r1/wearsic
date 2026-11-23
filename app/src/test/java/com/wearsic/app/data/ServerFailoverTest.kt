package com.wearsic.app.data

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Pins the automatic profile-failover state machine (ServerFailover.kt) as
 * plain JUnit — no Robolectric — so it runs on every machine, including ARM
 * Linux where Robolectric is excluded.
 *
 * The scenarios encode the guarantees users actually depend on:
 * the first healthy alternative in SAVED order wins, the dead URL is never
 * given a second chance within the round, bursts of failing requests cost
 * ONE probe round, a dead network is not re-probed every tap, and recovery
 * news clears the "no alternative" state immediately.
 *
 * NOTE on org.junit.Assert argument order: the message comes FIRST
 * (`assertEquals(message, expected, actual)`) — the opposite of kotlin.test,
 * which the server-side suite uses.
 */
class ServerFailoverTest {

    private val home = ServerProfile(id = "p1", name = "Home LAN", url = "http://192.168.1.20:8080", apiKey = "K1")
    private val cloud = ServerProfile(id = "p2", name = "Cloudflare", url = "https://wearsic.example.com", apiKey = "K2")
    private val backup = ServerProfile(id = "p3", name = "Backup relay", url = "http://10.0.0.7:8080", apiKey = "K3")

    /** Shared fixture: fake clock + counting probe + recording activation. */
    private inner class F {
        val now = AtomicLong(1_000_000L)
        val probeCalls = AtomicInteger()
        val probedUrls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val activations = Collections.synchronizedList(mutableListOf<ServerProfile>())
        val healthy: MutableMap<String, Boolean> = HashMap()
        var profiles = mutableListOf(home, cloud)
        var currentUrl: String = home.url

        /** Toggle for a probe that always crashes (must read as unreachable). */
        var explodeProbes = false

        fun coordinator(): ServerFailoverCoordinator = ServerFailoverCoordinator(
            profilesProvider = { profiles.toList() },
            currentUrlProvider = { currentUrl },
            probe = { url ->
                probeCalls.incrementAndGet()
                probedUrls.add(url)
                if (explodeProbes) error("probe transport exploded")
                healthy[url] == true
            },
            // The real activation saves the profile; in production the saved
            // serverUrlFlow then reports the new url — mimic that here.
            activate = {
                activations.add(it)
                currentUrl = it.url
            },
            clock = { now.get() },
        )

        fun advance(byMs: Long) {
            now.addAndGet(byMs)
        }
    }

    // ---------------- happy path ----------------

    @Test
    fun `switches to the first healthy alternative and never probes the dead url`() {
        val f = F()
        f.healthy[cloud.url] = true
        val coordinator = f.coordinator()

        val result = runBlocking { coordinator.onServerUnreachable() }

        assertEquals(FailoverState.Switched(fromUrl = home.url, toUrl = cloud.url, profileName = cloud.name), result)
        assertEquals("exactly one activation: the healthy profile", listOf(cloud).size, f.activations.size)
        assertEquals("the healthy profile was activated", cloud.url, f.activations.single().url)
        assertTrue("the candidate was probed", f.probedUrls.contains(cloud.url))
        assertTrue(
            "the url that JUST failed must not be re-probed in the same round",
            !f.probedUrls.contains(home.url),
        )
    }

    @Test
    fun `saved order beats probe timing - earlier profile wins when both are healthy`() {
        val f = F()
        f.profiles = mutableListOf(home, cloud, backup)
        f.healthy[cloud.url] = true
        f.healthy[backup.url] = true

        val result = runBlocking { f.coordinator().onServerUnreachable() }

        assertTrue(result is FailoverState.Switched)
        assertEquals("the user's first choice server wins", cloud.url, f.activations.single().url)
    }

    // ---------------- nothing available ----------------

    @Test
    fun `all alternatives dead reports NoAlternative without activating anything`() {
        val f = F()

        val result = runBlocking { f.coordinator().onServerUnreachable() }

        assertEquals(FailoverState.NoAlternative, result)
        assertTrue("must not switch to a broken server", f.activations.isEmpty())
        assertTrue("the alternative was actually probed", f.probedUrls.contains(cloud.url))
    }

    @Test
    fun `a crashing probe counts as unreachable`() {
        val f = F()
        f.healthy[cloud.url] = true
        f.explodeProbes = true

        val result = runBlocking { f.coordinator().onServerUnreachable() }

        assertEquals(FailoverState.NoAlternative, result)
        assertTrue(f.activations.isEmpty())
    }

    @Test
    fun `profiles pointing at the same url are deduped to one probe`() {
        val f = F()
        f.profiles = mutableListOf(home, cloud, cloud.copy(id = "p4", name = "Cloudflare alias"))
        f.healthy[cloud.url] = true

        val result = runBlocking { f.coordinator().onServerUnreachable() }

        assertTrue(result is FailoverState.Switched)
        assertEquals("one server = one probe, regardless of aliases", 1, f.probeCalls.get())
    }

    @Test
    fun `no other saved profile means no probing at all`() {
        val f = F()
        f.profiles = mutableListOf(home)

        val result = runBlocking { f.coordinator().onServerUnreachable() }

        assertEquals(FailoverState.NoAlternative, result)
        assertEquals("nothing to probe", 0, f.probeCalls.get())
        assertTrue("nothing to switch to, nothing activated", f.activations.isEmpty())
    }

    // ---------------- single flight + debounce ----------------

    @Test
    fun `a burst of failures kicks off exactly one probe round`() {
        val f = F()
        f.healthy[cloud.url] = true
        val coordinator = f.coordinator()

        val outcomes = runBlocking {
            (1..5).map {
                async { coordinator.onServerUnreachable() }
            }.map { it.await() }
        }

        assertEquals(5, outcomes.size)
        assertTrue("every caller learns the switch", outcomes.all { it is FailoverState.Switched })
        assertEquals("five failing requests cost ONE probe round", 1, f.probeCalls.get())
        assertEquals("activation must not be applied twice", 1, f.activations.size)
    }

    @Test
    fun `an immediate repeat is debounced - nothing probes again within the gap`() {
        val f = F()
        f.healthy[cloud.url] = true
        val coordinator = f.coordinator()
        runBlocking { coordinator.onServerUnreachable() }
        val probingBefore = f.probeCalls.get()
        f.advance(10_000)

        runBlocking { coordinator.onServerUnreachable() }

        assertEquals("still inside the round gap: no new round", probingBefore, f.probeCalls.get())
        assertEquals(1, f.activations.size)
    }

    @Test
    fun `after the debounce the coordinator probes the remaining alternatives`() {
        val f = F()
        f.healthy[cloud.url] = true
        val coordinator = f.coordinator()
        runBlocking { coordinator.onServerUnreachable() } // switched to cloud
        assertEquals("activation moved the fixture's current url", cloud.url, f.currentUrl)

        f.healthy.clear() // ... and now cloud died too
        f.advance(40_000)

        val result = runBlocking { coordinator.onServerUnreachable() }
        // The current url (cloud) is excluded from candidates; home is probed
        // (still dead); the state keeps describing the last working switch
        // rather than claiming a fresh one that never happened.
        assertTrue(
            "the previously dead home url should be retried on a later round",
            f.probedUrls.contains(home.url),
        )
        assertTrue(result is FailoverState.Switched)
    }

    @Test
    fun `a dead round cools down five minutes before the next round`() {
        val f = F()
        val coordinator = f.coordinator()
        runBlocking { coordinator.onServerUnreachable() } // all dead -> NoAlternative
        val before = f.probeCalls.get()

        f.advance(30_000)
        runBlocking { coordinator.onServerUnreachable() }
        assertEquals("within the cooldown nothing is re-probed", before, f.probeCalls.get())

        f.advance(5 * 60_000L + 1)
        runBlocking { coordinator.onServerUnreachable() }
        assertEquals(
            "after the cooldown a fresh round runs",
            before + 1, f.probeCalls.get(),
        )
    }

    // ---------------- recovery ----------------

    @Test
    fun `success clears NoAlternative immediately`() {
        val f = F()
        val coordinator = f.coordinator()
        runBlocking { coordinator.onServerUnreachable() }
        assertEquals(FailoverState.NoAlternative, coordinator.state.value)

        coordinator.onServerReachable()

        assertEquals(FailoverState.Idle, coordinator.state.value)
    }

    @Test
    fun `the switched banner survives brief successes and clears after the grace period`() {
        val f = F()
        f.healthy[cloud.url] = true
        val coordinator = f.coordinator()
        runBlocking { coordinator.onServerUnreachable() }
        assertTrue(coordinator.state.value is FailoverState.Switched)

        // A success right after switching must not erase the banner — that
        // signal is the whole reason the user knows WHY playback still works.
        coordinator.onServerReachable()
        assertTrue(
            "banner survives a success inside the grace period",
            coordinator.state.value is FailoverState.Switched,
        )

        f.advance(10 * 60_000L + 1)
        coordinator.onServerReachable()
        assertEquals(
            "the banner ages out after the grace period",
            FailoverState.Idle, coordinator.state.value,
        )
    }

    // ---------------- activation contract ----------------

    @Test
    fun `the activated profile is passed whole - url AND api key`() {
        val f = F()
        f.healthy[cloud.url] = true

        runBlocking { f.coordinator().onServerUnreachable() }

        assertEquals("activation carries the profile, key included", listOf(cloud), f.activations.toList())
    }
}
