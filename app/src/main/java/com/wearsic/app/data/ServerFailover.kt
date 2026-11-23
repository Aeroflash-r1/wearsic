package com.wearsic.app.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The visible outcome of the failover coordinator.
 *
 * Sealed and pure-JVM (no android.* imports) so it can be driven and
 * asserted from a plain unit test.
 */
sealed interface FailoverState {

    /** No failover in flight: either it never ran, or things are normal again. */
    data object Idle : FailoverState

    /** A probe round is running (shown only to keep the UI honest during it). */
    data object Probing : FailoverState

    /**
     * The active URL failed and the coordinator switched to [toUrl] (named
     * [profileName]). The watch now serves from this profile's address; the
     * URL the user saved is left untouched in Settings — one tap restores it
     * when its server returns.
     */
    data class Switched(
        val fromUrl: String,
        val toUrl: String,
        val profileName: String,
    ) : FailoverState

    /**
     * A probe round ran and every alternative was also unreachable. The app
     * keeps its current URL (and its offline surface); a later round may
     * find a recovery.
     */
    data object NoAlternative : FailoverState
}

/**
 * Automatic server-profile failover for the watch.
 *
 * A profile is a saved (name, URL, apiKey) triple — Home-LAN server by day,
 * the permanent Cloudflare front door for anywhere else. Before this class,
 * moving between them (or surviving one server dying) meant opening Settings
 * on a 1.4" watch face and retyping a 24-character address. Now: when the
 * active server is CONFIRMED unreachable (the LibraryViewModel's hysteresis
 * already guards against Wi-Fi blips — this class only reacts after that
 * confirms), every OTHER saved profile is probed in parallel and the first
 * healthy one is activated — the same switch the Settings screen performs
 * by hand.
 *
 * Design constraints, driven by where this runs (a Wear OS watch):
 *
 *  - [onServerUnreachable] is the only expensive path and is single-flight:
 *    a burst of failing requests (an offline search wave) kicks off AT MOST
 *    one probe round, and every caller receives the same outcome.
 *  - Rounds are debounced by the injectable clock, and after a round that
 *    finds NOTHING healthy there is a long ([NO_ALTERNATIVE_COOLDOWN_MS])
 *    cooldown — a dead home network must not be re-probed on every tap
 *    while the offline banner already explains the situation.
 *  - Probes run in parallel with a short per-probe budget and prefer the
 *    SAVED ORDER: if home and cloud both answer, home wins (the user's
 *    first choice is never preempted by an accident of probe timing).
 *  - Recovery does NOT switch back. The fallback may be doing fine, the
 *    queue may hold its URLs, and a flip-flop between two half-alive
 *    servers is worse than settling on either. The user restores their
 *    preferred profile with one tap; the banner clears itself.
 *  - The [probe] hits `/health`, which is public and secret-free: probing
 *    needs no API key, so even a profile whose key was never typed can be
 *    recognized as "reachable, and therefore switchable".
 *
 * The caller provides EVERYTHING as lambdas so the whole state machine can
 * be exercised without Android, DataStore or OkHttp.
 */
class ServerFailoverCoordinator(
    /** All saved profiles, in the user's preferred order. */
    private val profilesProvider: suspend () -> List<ServerProfile>,
    /** The URL currently in effect (the one that just failed). */
    private val currentUrlProvider: suspend () -> String,
    /** True when [String] (a base URL) answers health right now. */
    private val probe: suspend (String) -> Boolean,
    /** Activate a profile as the new server (URL + key), like Settings does. */
    private val activate: suspend (ServerProfile) -> Unit,
    /** Injectable for tests; wall clock in production. */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private companion object {
        /** Hard budget for one profile probe. `/health` is trivial; 5s covers a
         *  phone-class radio waking up, and anything slower is not usable. */
        const val PROBE_TIMEOUT_MS = 5_000L

        /** Minimum spacing between probe ROUNDS: a stream of failing requests
         *  within this window reuses the previous outcome instead of re-probing. */
        const val MIN_ROUND_GAP_MS = 30_000L

        /** After a round found nothing, this long passes before the next round —
         *  the offline banner is already up; probing is just battery burn. */
        const val NO_ALTERNATIVE_COOLDOWN_MS = 5 * 60_000L

        /** Switched-banner auto-expiry after a success on the fallback. */
        const val SWITCHED_BANNER_MS = 10 * 60_000L
    }

    /** Serializes all probe rounds; also the single-flight mechanism. */
    private val roundMutex = Mutex()

    private val _state = MutableStateFlow<FailoverState>(FailoverState.Idle)
    val state: StateFlow<FailoverState> = _state.asStateFlow()

    /** Start time of the last round (for the debounce) and of the last switch. */
    private var lastRoundStartedAt = 0L
    private var lastSwitchedAt = 0L

    /**
     * The active server is confirmed unreachable. Probes every OTHER profile
     * in parallel and activates the first healthy one (saved order first).
     * Concurrent and rapid repeat callers receive the outcome of the round
     * already running or just finished — they never start a second one.
     */
    suspend fun onServerUnreachable(): FailoverState = roundMutex.withLock {
        val now = clock()
        val current = _state.value
        // Debounce: within the round gap (or the longer silence-cooldown after
        // a round that found nothing) there is nothing new to learn by probing.
        val requiredGap = if (current is FailoverState.NoAlternative) NO_ALTERNATIVE_COOLDOWN_MS else MIN_ROUND_GAP_MS
        if (lastRoundStartedAt != 0L && now - lastRoundStartedAt < requiredGap) {
            return current
        }
        lastRoundStartedAt = now

        val fromUrl = currentUrlProvider()
        // Same address under two names is ONE server: dedupe so a merged
        // profile list does not probe the same host twice per round.
        val candidates = profilesProvider()
            .filter { it.url.isNotBlank() && it.url != fromUrl }
            .distinctBy { it.url.trimEnd('/') }
        if (candidates.isEmpty()) {
            // Nothing to fail over TO. Keep an existing Switched banner (it
            // describes the last working state), else mark plainly.
            if (current is FailoverState.Idle) _state.value = FailoverState.NoAlternative
            return _state.value
        }

        if (current !is FailoverState.Switched) _state.value = FailoverState.Probing

        val healthy = probeInParallelPreferOrder(candidates)
        return if (healthy != null) {
            activate(healthy)
            lastSwitchedAt = clock()
            FailoverState.Switched(
                fromUrl = fromUrl,
                toUrl = healthy.url,
                profileName = healthy.name,
            ).also { _state.value = it }
        } else {
            if (current is FailoverState.Idle) _state.value = FailoverState.NoAlternative
            _state.value
        }
    }

    /**
     * A request just succeeded against the active server.
     *
     * Clears [FailoverState.NoAlternative] immediately (recovery is news) but
     * keeps the [FailoverState.Switched] banner visible for a grace period —
     * a success one second after switching would otherwise erase the only
     * signal telling the user WHY their watch suddenly shows cloud URLs.
     */
    fun onServerReachable() {
        when (val current = _state.value) {
            is FailoverState.Switched ->
                if (clock() - lastSwitchedAt > SWITCHED_BANNER_MS) {
                    _state.value = FailoverState.Idle
                }
            is FailoverState.NoAlternative -> _state.value = FailoverState.Idle
            else -> Unit
        }
    }

    /** Test helper: wipe to a fresh state (no production caller needs this). */
    fun resetForTest() {
        _state.value = FailoverState.Idle
        lastRoundStartedAt = 0L
        lastSwitchedAt = 0L
    }

    /**
     * Probes every candidate simultaneously, then picks the FIRST healthy one
     * in [candidates] order — user preference, not probe-completion order.
     * A probe that throws or outlives its budget counts as unreachable.
     */
    private suspend fun probeInParallelPreferOrder(
        candidates: List<ServerProfile>,
    ): ServerProfile? = coroutineScope {
        val probes = candidates.map { profile ->
            async {
                try {
                    withTimeoutOrNull(PROBE_TIMEOUT_MS) { probe(profile.url) } == true
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce // cancellation is never "unreachable" — let it fly
                } catch (_: Exception) {
                    false // a crashed probe is an unreachable probe
                }
            }
        }
        val results = probes.map { it.await() }
        val winner = candidates.indices.firstOrNull { results[it] } ?: return@coroutineScope null
        candidates[winner]
    }
}
