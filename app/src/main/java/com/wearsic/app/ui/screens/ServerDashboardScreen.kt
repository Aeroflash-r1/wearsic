package com.wearsic.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.tooling.preview.devices.WearDevices
import androidx.compose.ui.tooling.preview.Preview
import com.wearsic.app.network.model.ExtractionHealthDto
import com.wearsic.app.network.model.ReadyResponseDto
import com.wearsic.app.network.model.ServerHealthDto
import com.wearsic.app.network.model.ServerUpdateStatusDto
import com.wearsic.app.ui.components.WearsicScreenHeader
import com.wearsic.app.ui.theme.LocalWearsicAccent
import com.wearsic.app.ui.theme.WearsicAccentSky
import com.wearsic.app.ui.theme.WearsicDimens
import com.wearsic.app.ui.theme.WearsicAppBackground
import com.wearsic.app.ui.theme.WearsicError
import com.wearsic.app.ui.theme.WearsicGlassBorder
import com.wearsic.app.ui.theme.WearsicGlassFill
import com.wearsic.app.ui.theme.WearsicSuccess
import com.wearsic.app.ui.theme.WearsicTextMuted
import com.wearsic.app.ui.theme.WearsicTextPrimary
import com.wearsic.app.ui.theme.WearsicTextSecondary
import com.wearsic.app.ui.theme.WearsicTheme
import com.wearsic.app.ui.theme.WearsicVibrantLavender
import com.wearsic.app.ui.theme.wearsicListContentPadding
import com.wearsic.app.ui.util.wearsicClickable
import com.wearsic.app.ui.util.wearsicEntrance
import com.wearsic.app.ui.util.wearsicRotaryScroll
import com.wearsic.app.ui.viewmodel.ServerDashboardState

/**
 * ENGINE DASHBOARD — the server's live self-report on the wrist.
 *
 * Probes /health (status, version, extraction success/failure counters,
 * canary probe, update staging, crash count) and /ready (can the server
 * serve music right now) and renders one compact card per group. Every field
 * is optional so a watch pointed at a server built before these endpoints
 * still gets a useful screen instead of an error.
 */
@Composable
fun ServerDashboardScreen(
    state: ServerDashboardState = ServerDashboardState(),
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberScalingLazyListState()

    ScreenScaffold(
        scrollState = listState,
        modifier = modifier
            .fillMaxSize()
            .background(WearsicAppBackground)
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .wearsicEntrance()
                .wearsicRotaryScroll(listState),
            contentPadding = wearsicListContentPadding(it),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                WearsicScreenHeader(
                    title = "Engine",
                    subtitle = state.health?.serverName ?: "Server diagnostics",
                    icon = Icons.Rounded.MonitorHeart
                )
            }

            // ── Status hero ──────────────────────────────────────────────
            item {
                StatusCard(state = state)
            }

            item {
                RefreshChip(loading = state.loading, onRefresh = onRefresh)
            }

            // ── Engine section ───────────────────────────────────────────
            val health = state.health
            if (health != null) {
                item { SectionLabel("Engine") }
                item {
                    DashboardCard {
                        ValueRow("Version", health.version.ifBlank { "unknown" })
                        ValueRow(
                            label = "Transcoder",
                            value = if (health.transcoderAvailable) "Ready" else "Not installed",
                            valueColor = if (health.transcoderAvailable) WearsicSuccess else WearsicTextMuted
                        )
                        ValueRow(
                            label = "Extractor",
                            value = extractionSummary(health.extraction),
                            valueColor = extractionColor(health.extraction)
                        )
                        health.canaryHealthy?.let { healthy ->
                            ValueRow(
                                label = "Canary",
                                value = if (healthy) "Healthy" else "Failing",
                                valueColor = if (healthy) WearsicSuccess else WearsicError
                            )
                        }
                        ValueRow(
                            label = "Update",
                            value = updateSummary(health.update),
                            valueColor = when (health.update?.status) {
                                "staged" -> WearsicVibrantLavender
                                "checking" -> WearsicAccentSky
                                else -> WearsicTextPrimary
                            }
                        )
                        ValueRow(
                            label = "Crashes",
                            value = crashSummary(health.crashCount, health.lastCrashAtMillis)
                        )
                        if (health.upstreamRefusals > 0) {
                            ValueRow(
                                label = "Refusals",
                                value = "${health.upstreamRefusals}",
                                valueColor = WearsicTextSecondary
                            )
                        }
                    }
                }

                val lastError = health.extraction?.lastError ?: health.update?.lastError
                if (!lastError.isNullOrBlank()) {
                    item {
                        Text(
                            text = lastError,
                            color = WearsicError,
                            fontSize = 9.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }

                // ── Readiness ────────────────────────────────────────────
                val ready = state.ready
                if (ready != null) {
                    item { SectionLabel("Readiness") }
                    item {
                        DashboardCard {
                            ReadyRow("Database", ready.database)
                            ReadyRow("Extractor", ready.extractor)
                            ReadyRow("Transcoder", ready.transcoder)
                        }
                    }
                    if (!ready.ready) {
                        item {
                            Text(
                                text = "The server reports it cannot play music right now",
                                color = WearsicError,
                                fontSize = 9.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

/** Status hero: dot + one word + latency, or the transport error. */
@Composable
private fun StatusCard(state: ServerDashboardState) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WearsicDimens.CardRadius))
            .background(WearsicGlassFill)
            .border(1.dp, WearsicGlassBorder, RoundedCornerShape(WearsicDimens.CardRadius))
            .padding(horizontal = WearsicDimens.ScreenPaddingH, vertical = 12.dp)
            .testTag("server_dashboard_status"),
        contentAlignment = Alignment.Center
    ) {
        when {
            state.loading -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Probing server…", color = WearsicTextPrimary, fontSize = 12.sp)
                }
            }

            state.error != null -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = WearsicError,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Offline",
                            color = WearsicError,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = state.error,
                        color = WearsicTextSecondary,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            state.health != null -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(WearsicSuccess)
                        )
                        Spacer(modifier = Modifier.width(7.dp))
                        Text(
                            text = if (state.health.status.equals("ok", ignoreCase = true)) {
                                "Online"
                            } else {
                                state.health.status
                            },
                            color = WearsicSuccess,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        state.latencyMs?.let { latency ->
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "· ${latency} ms",
                                color = WearsicTextMuted,
                                fontSize = 10.sp
                            )
                        }
                    }
                    val checked = remember(state.checkedAtMillis) {
                        relativeTime(state.checkedAtMillis)
                    }
                    if (checked.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(text = checked, color = WearsicTextMuted, fontSize = 9.sp)
                    }
                }
            }

            else -> {
                Text("Not checked yet", color = WearsicTextMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun RefreshChip(loading: Boolean, onRefresh: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(WearsicGlassFill)
            .border(1.dp, WearsicGlassBorder, CircleShape)
            .wearsicClickable(pressedScale = 0.94f, onClick = onRefresh)
            .padding(horizontal = WearsicDimens.ScreenPaddingH, vertical = 8.dp)
            .testTag("server_dashboard_refresh"),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = null,
                tint = if (loading) WearsicTextMuted else LocalWearsicAccent.current,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (loading) "Refreshing…" else "Refresh",
                color = if (loading) WearsicTextMuted else WearsicTextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** Glass card grouping label→value rows. */
@Composable
private fun DashboardCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WearsicDimens.CardRadius))
            .background(WearsicGlassFill)
            .border(1.dp, WearsicGlassBorder, RoundedCornerShape(WearsicDimens.CardRadius))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        content()
    }
}

@Composable
private fun ValueRow(
    label: String,
    value: String,
    valueColor: Color = WearsicTextPrimary
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = WearsicTextMuted,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ReadyRow(label: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (ok) WearsicSuccess else WearsicError)
        )
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            text = label,
            color = WearsicTextPrimary,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (ok) "Ready" else "Unavailable",
            color = if (ok) WearsicSuccess else WearsicError,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** Small muted section heading above a card. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = WearsicTextMuted,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.2.sp,
        textAlign = TextAlign.Center
    )
}

// ── Value formatters (null-tolerant: old servers do not report everything) ──

private fun extractionSummary(extraction: ExtractionHealthDto?): String {
    if (extraction == null) return "Not reported"
    val base = "${extraction.successCount} ok · ${extraction.failureCount} failed"
    return if (extraction.failureRatePercent > 0) "$base (${extraction.failureRatePercent}%)" else base
}

private fun extractionColor(extraction: ExtractionHealthDto?): Color = when {
    extraction == null -> WearsicTextMuted
    extraction.consecutiveFailures >= 3 -> WearsicError
    extraction.failureRatePercent > 0 -> WearsicTextPrimary
    else -> WearsicSuccess
}

private fun updateSummary(update: ServerUpdateStatusDto?): String {
    if (update == null) return "Not reported"
    return when (update.status) {
        "staged" -> update.stagedVersion?.let { "Staged v$it" } ?: "Staged"
        "checking" -> "Checking…"
        "idle" -> "Idle"
        else -> update.status
    }
}

private fun crashSummary(count: Int, lastCrashAtMillis: Long): String {
    if (count <= 0) return "None recorded"
    val when_ = relativeTime(lastCrashAtMillis)
    return if (when_.isBlank()) "$count" else "$count (last $when_)"
}

/** "just now" / "42s ago" / "3m ago" / "2h ago", evaluated at composition. */
private fun relativeTime(atMillis: Long): String {
    if (atMillis <= 0L) return ""
    val diffSec = ((System.currentTimeMillis() - atMillis) / 1000L).coerceAtLeast(0L)
    return when {
        diffSec < 10 -> "just now"
        diffSec < 60 -> "${diffSec}s ago"
        diffSec < 3600 -> "${diffSec / 60}m ago"
        else -> "${diffSec / 3600}h ago"
    }
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun ServerDashboardScreenPreview() {
    WearsicTheme {
        ServerDashboardScreen(
            state = ServerDashboardState(
                health = ServerHealthDto(
                    status = "ok",
                    version = "1.9.0",
                    serverName = "Wearsic Engine",
                    transcoderAvailable = true,
                    extraction = ExtractionHealthDto(
                        successCount = 128,
                        failureCount = 4,
                        failureRatePercent = 3,
                        consecutiveFailures = 0
                    ),
                    canaryHealthy = true,
                    update = ServerUpdateStatusDto(status = "idle"),
                    crashCount = 0
                ),
                ready = ReadyResponseDto(
                    ready = true,
                    database = true,
                    extractor = true,
                    transcoder = true
                ),
                latencyMs = 38,
                checkedAtMillis = System.currentTimeMillis() - 12_000
            )
        )
    }
}
