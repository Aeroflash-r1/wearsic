package com.wearsic.server

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class SingleFlightTest {

    @Test
    fun `concurrent callers for the same key share one computation`() = runTest {
        val sf = SingleFlight<String, Int>()
        var executions = 0

        val results = (1..20).map {
            async {
                sf.run("key") {
                    executions++ // delay-free: deterministic in runTest
                    42
                }
            }
        }.awaitAll()

        assertEquals(20, results.size)
        results.forEach { assertEquals(42, it) }
        assertEquals(1, executions, "expensive operation must run exactly once")
    }

    @Test
    fun `entry is removed after successful completion`() = runTest {
        val sf = SingleFlight<String, Int>()
        sf.run("k") { 1 }
        assertFalse(sf.contains("k"), "in-flight entry must be removed after success")
        assertEquals(0, sf.size)
    }

    @Test
    fun `entry is removed after failure`() = runTest {
        val sf = SingleFlight<String, Int>()
        val outcome = runCatching { sf.run("k") { throw IllegalStateException("boom") } }
        assertTrue(outcome.isFailure, "the failure must propagate to the caller")
        assertFalse(sf.contains("k"), "in-flight entry must be removed after failure")
        assertEquals(0, sf.size)
    }

    @Test
    fun `entry is removed after cancellation`() = runTest {
        val sf = SingleFlight<String, Int>()
        val job = launch { sf.run("k") { awaitCancellation() } }
        // Let the callee register and block.
        kotlinx.coroutines.yield()
        kotlinx.coroutines.yield()
        job.cancelAndJoin()
        assertFalse(sf.contains("k"), "in-flight entry must be removed after cancellation")
        assertEquals(0, sf.size)
    }

    @Test
    fun `failure propagates to joiners and map is cleaned`() = runTest {
        val sf = SingleFlight<String, Int>()
        val results = (1..5).map {
            async {
                runCatching { sf.run("k") { error("shared boom") } }
            }
        }.awaitAll()

        results.forEach { assertTrue(it.isFailure, "all joiners must see the shared failure") }
        assertEquals(0, sf.size)
    }

    @Test
    fun `different keys do not interfere`() = runTest {
        val sf = SingleFlight<String, Int>()
        val results = (1..10).map { i ->
            async { sf.run("key-$i") { i * 2 } }
        }.awaitAll()

        results.forEachIndexed { index, value -> assertEquals((index + 1) * 2, value) }
        assertEquals(0, sf.size)
    }

    @Test
    fun `sequential runs recompute after cleanup`() = runTest {
        val sf = SingleFlight<String, String>()
        val first = sf.run("k") { "a" }
        val second = sf.run("k") { "b" }
        assertEquals("a", first)
        assertEquals("b", second, "a completed operation must not be reused on the next call")
    }

    @Test
    fun `parallel candidates never execute losing computations`() = kotlinx.coroutines.runBlocking {
        val sf = SingleFlight<String, Int>()
        val executions = java.util.concurrent.atomic.AtomicInteger()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val started = java.util.concurrent.CountDownLatch(32)
        val jobs = (1..32).map {
            async(kotlinx.coroutines.Dispatchers.Default) {
                started.countDown()
                sf.run("shared") {
                    executions.incrementAndGet()
                    release.await()
                    42
                }
            }
        }
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        // Keep the winner alive while all racing callers join it.
        delay(100)
        release.complete(Unit)
        assertTrue(jobs.awaitAll().all { it == 42 })
        assertEquals(1, executions.get())
        assertEquals(0, sf.size)
    }

    private suspend fun awaitCancellation(): Nothing =
        kotlinx.coroutines.awaitCancellation()
}
