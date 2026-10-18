@file:OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)

package com.wearsic.app.data

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicLong

/**
 * Forensics only (2026-10-18 CI wedge) — TEMPORARY, removed once the root
 * cause is fixed.
 *
 * The wedge stalls the very first DataStore write in a fresh JVM for 30s,
 * with every thread idle; it only resumes when the paused Robolectric main
 * Looper queue is drained (a pump), which means some dispatch needed by the
 * write is queued somewhere that never runs. To find out which dispatch,
 * both the caller's dispatcher (the runBlocking event loop, wrapped by the
 * test) and the DataStore's internal scope dispatcher (wrapped here) are
 * pass-through taps that log every ENQUEUE/RUN/DONE with thread names.
 */
public object DataStoreForensics {
    /** Set by the test class; invoked on whatever thread dispatches. */
    @Volatile
    public var tap: ((String) -> Unit)? = null
}

/** Pass-through dispatcher that reports every dispatch through it. */
public class TapDispatcher(
    private val delegate: CoroutineDispatcher,
    private val tag: String
) : CoroutineDispatcher(), Delay {
    private val seq = AtomicLong()

    override fun isDispatchNeeded(context: CoroutineContext): Boolean =
        delegate.isDispatchNeeded(context)

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val id = seq.incrementAndGet()
        val job = context[Job]?.hashCode()
        DataStoreForensics.tap?.invoke(
            "$tag#$id ENQUEUE from=${Thread.currentThread().name} job=$job"
        )
        delegate.dispatch(
            context,
            object : Runnable {
                override fun run() {
                    DataStoreForensics.tap?.invoke(
                        "$tag#$id RUN on=${Thread.currentThread().name}"
                    )
                    try {
                        block.run()
                    } finally {
                        DataStoreForensics.tap?.invoke("$tag#$id DONE")
                    }
                }
            }
        )
    }

    override fun scheduleResumeAfterDelay(
        timeMillis: Long,
        continuation: CancellableContinuation<Unit>
    ) {
        val d = delegate
        check(d is Delay) { "TapDispatcher delegate is not a Delay: $d" }
        d.scheduleResumeAfterDelay(timeMillis, continuation)
    }

    override fun invokeOnTimeout(
        timeMillis: Long,
        block: Runnable,
        context: CoroutineContext
    ): DisposableHandle {
        val d = delegate
        check(d is Delay) { "TapDispatcher delegate is not a Delay: $d" }
        return d.invokeOnTimeout(timeMillis, block, context)
    }

    override fun toString(): String = "Tap[$tag]($delegate)"
}

/** The wrapped scope used by the preferences DataStore delegate. */
internal fun tappedDataStoreScope(): CoroutineScope =
    CoroutineScope(TapDispatcher(Dispatchers.IO, "DS") + SupervisorJob())
