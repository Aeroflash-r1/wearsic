package com.wearsic.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.wearsic.app.BuildConfig
import com.wearsic.app.model.WearsicError
import com.wearsic.app.network.model.ConnectionTestState
import com.wearsic.app.util.Validation
import kotlinx.coroutines.delay
import com.wearsic.app.ui.components.WearsicScreenHeader
import androidx.compose.foundation.shape.RoundedCornerShape
import com.wearsic.app.ui.theme.WearsicSurfaceRaised
import com.wearsic.app.ui.theme.WearsicDimens
import com.wearsic.app.ui.theme.WearsicAppBackground
import com.wearsic.app.ui.theme.wearsicListContentPadding
import com.wearsic.app.ui.theme.WearsicBlack
import com.wearsic.app.ui.theme.WearsicError
import com.wearsic.app.ui.theme.WearsicLavenderContainer
import com.wearsic.app.ui.theme.WearsicSuccess
import com.wearsic.app.ui.theme.WearsicSurface
import com.wearsic.app.ui.theme.WearsicSurfaceActive
import com.wearsic.app.ui.theme.WearsicSurfaceBorderSubtle
import com.wearsic.app.ui.theme.WearsicTextMuted
import com.wearsic.app.ui.theme.WearsicTextPrimary
import com.wearsic.app.ui.theme.WearsicTextPrimaryDark
import com.wearsic.app.ui.theme.WearsicTextSecondary
import com.wearsic.app.ui.theme.WearsicTheme
import com.wearsic.app.ui.theme.WearsicVibrantLavender

import com.wearsic.app.ui.util.wearsicClickable
import com.wearsic.app.ui.util.wearsicEntrance
import com.wearsic.app.ui.util.wearsicRotaryScroll

@Composable
fun SettingsScreen(
    serverUrl: String = "",
    connectionTestState: ConnectionTestState = ConnectionTestState.Idle,
    onServerUrlChanged: (String) -> Unit = {},
    onTestConnection: (String) -> Unit = {},
    apiKey: String = "",
    onApiKeyChanged: (String) -> Unit = {},
    onOpenStorage: () -> Unit = {},
    onOpenServerDashboard: () -> Unit = {},
    autoCacheEnabled: Boolean = true,
    onAutoCacheToggled: (Boolean) -> Unit = {},
    offlineLimitSongs: Int = 15,
    onOfflineLimitChanged: (Int) -> Unit = {},
    onClearDownloads: () -> Unit = {},
    /** One-line startup health summary (recovery mode / last phase / crash). */
    startupHealth: String = "",
    modifier: Modifier = Modifier
) {
    var showClearDownloadsConfirm by remember { mutableStateOf(false) }
    var downloadsClearedMessage by remember { mutableStateOf<String?>(null) }

    val listState = rememberScalingLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current

    // ── Editor state ─────────────────────────────────────────────────────
    // Both text fields below are persisted asynchronously (DataStore): the
    // saved value flows back milliseconds after every write. The old code
    // bound the editable text straight to that flow (`remember(serverUrl)`),
    // so an emission carrying an older value reset the field mid-typing —
    // characters visibly vanished ("it backspaces itself"). These holders
    // make the local text authoritative while the field is focused, and
    // inbound values are adopted only when they are not racing our own save.
    val urlField = rememberGuardedTextFieldState(serverUrl)
    val keyField = rememberGuardedTextFieldState(apiKey)

    /**
     * Validates and persists the URL field. Invalid input is reported inline
     * and never written, so a half-typed address can no longer replace the
     * working one. Blank clears the setting on purpose.
     */
    fun commitUrl() {
        val raw = urlField.text.trim()
        if (raw.isEmpty()) {
            urlField.error = null
            urlField.text = ""
            urlField.pendingCommit = ""
            onServerUrlChanged("")
            return
        }
        Validation.validateServerUrl(raw).fold(
            onSuccess = { normalized ->
                urlField.error = null
                urlField.text = normalized
                urlField.pendingCommit = normalized
                if (normalized != serverUrl) onServerUrlChanged(normalized)
            },
            onFailure = { throwable ->
                urlField.error = (throwable as? WearsicError)?.getDisplayMessage()
                    ?: "Not a valid server address"
            }
        )
    }

    fun commitApiKey() {
        val raw = keyField.text.trim()
        if (raw.isEmpty()) {
            keyField.error = null
            keyField.text = ""
            keyField.pendingCommit = ""
            onApiKeyChanged("")
            return
        }
        Validation.validateApiKey(raw).fold(
            onSuccess = { normalized ->
                keyField.error = null
                keyField.text = normalized
                keyField.pendingCommit = normalized
                if (normalized != apiKey) onApiKeyChanged(normalized)
            },
            onFailure = { throwable ->
                keyField.error = (throwable as? WearsicError)?.getDisplayMessage()
                    ?: "Not a valid API key"
            }
        )
    }

    /**
     * Tests the address the user SEES (validated first), not the last
     * persisted one — previously a freshly typed URL was silently ignored
     * until it happened to have been saved.
     */
    fun testConnectionNow() {
        val raw = urlField.text.trim()
        if (raw.isEmpty()) {
            urlField.error = "Enter the server address first"
            return
        }
        Validation.validateServerUrl(raw).fold(
            onSuccess = { normalized ->
                urlField.error = null
                urlField.text = normalized
                urlField.pendingCommit = normalized
                onTestConnection(normalized)
            },
            onFailure = { throwable ->
                urlField.error = (throwable as? WearsicError)?.getDisplayMessage()
                    ?: "Not a valid server address"
            }
        )
    }

    // Never lose a finished edit: leaving the screen commits whatever is in
    // the fields, exactly like pressing Done.
    DisposableEffect(Unit) {
        onDispose {
            commitUrl()
            commitApiKey()
        }
    }

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
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            item {
                WearsicScreenHeader(
                    title = "Settings",
                    subtitle = "Server & Storage",
                )
            }

            // ── Connection ───────────────────────────────────────────
            item {
                Text(
                    text = "Connection",
                    color = WearsicVibrantLavender,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // Server URL Input Field (Fully Keyboard-enabled)
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CircleShape)
                            .background(if (urlField.focused) WearsicSurfaceActive else WearsicSurface)
                            .border(
                                1.dp,
                                when {
                                    urlField.error != null -> WearsicError
                                    urlField.focused -> WearsicVibrantLavender
                                    else -> WearsicSurfaceBorderSubtle
                                },
                                CircleShape
                            )
                            .padding(horizontal = WearsicDimens.ScreenPaddingH, vertical = 6.dp)
                            .testTag("settings_server_url_container"),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Dns,
                                contentDescription = "Server URL Icon",
                                tint = when {
                                    urlField.error != null -> WearsicError
                                    urlField.focused -> WearsicVibrantLavender
                                    else -> WearsicTextSecondary
                                },
                                modifier = Modifier.size(16.dp)
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "Server URL",
                                    color = WearsicTextMuted,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (urlField.text.isEmpty()) {
                                        Text(
                                            text = "Enter URL...",
                                            color = WearsicTextMuted,
                                            fontSize = 11.sp,
                                            maxLines = 1
                                        )
                                    }

                                    BasicTextField(
                                        value = urlField.text,
                                        onValueChange = {
                                            urlField.text = it
                                            // Editing again clears a stale
                                            // validation message.
                                            if (urlField.error != null) urlField.error = null
                                        },
                                        singleLine = true,
                                        textStyle = TextStyle(
                                            color = WearsicTextPrimary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Normal
                                        ),
                                        cursorBrush = SolidColor(WearsicVibrantLavender),
                                        keyboardOptions = KeyboardOptions(
                                            imeAction = ImeAction.Done
                                        ),
                                        keyboardActions = KeyboardActions(
                                            onDone = {
                                                keyboardController?.hide()
                                                commitUrl()
                                            }
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .onFocusChanged { focusState ->
                                                val wasFocused = urlField.focused
                                                urlField.focused = focusState.isFocused
                                                if (wasFocused && !focusState.isFocused) commitUrl()
                                            }
                                            .testTag("settings_server_url")
                                    )
                                }
                            }

                            if (urlField.text.isNotEmpty()) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Clear URL",
                                    tint = WearsicTextSecondary,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .clickable {
                                            urlField.text = ""
                                            urlField.error = null
                                            urlField.pendingCommit = ""
                                            onServerUrlChanged("")
                                        }
                                        .testTag("settings_server_url_clear")
                                )
                            }
                        }
                    }

                    if (urlField.error != null) {
                        Row(
                            modifier = Modifier
                                .padding(top = 3.dp, start = 10.dp)
                                .testTag("settings_server_url_error"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Warning,
                                contentDescription = null,
                                tint = WearsicError,
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = urlField.error ?: "",
                                color = WearsicError,
                                fontSize = 9.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Test Connection Button
            item {
                when (connectionTestState) {
                    is ConnectionTestState.Idle -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(CircleShape)
                                .background(WearsicVibrantLavender)
                                .wearsicClickable { testConnectionNow() }
                                .padding(horizontal = WearsicDimens.ScreenPaddingH, vertical = 10.dp)
                                .testTag("settings_test_connection"),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.NetworkCheck,
                                    contentDescription = "Test Connection",
                                    tint = WearsicTextPrimaryDark,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Test Connection",
                                    color = WearsicTextPrimaryDark,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    is ConnectionTestState.Testing -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(CircleShape)
                                .background(WearsicSurface)
                                .border(1.dp, WearsicVibrantLavender, CircleShape)
                                .padding(horizontal = WearsicDimens.ScreenPaddingH, vertical = 10.dp)
                                .testTag("settings_test_connection_testing"),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Connecting...",
                                    color = WearsicTextPrimary,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                    is ConnectionTestState.Success -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(CircleShape)
                                .background(WearsicSurface)
                                .border(1.dp, WearsicSuccess, CircleShape)
                                .wearsicClickable { testConnectionNow() }
                                .padding(horizontal = WearsicDimens.ScreenPaddingH, vertical = 10.dp)
                                .testTag("settings_test_connection_success"),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = "Success",
                                    tint = WearsicSuccess,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Connected (${connectionTestState.version})",
                                    color = WearsicSuccess,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    is ConnectionTestState.Error -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(CircleShape)
                                .background(WearsicSurface)
                                .border(1.dp, WearsicError, CircleShape)
                                .wearsicClickable { testConnectionNow() }
                                .padding(horizontal = WearsicDimens.ScreenPaddingH, vertical = 10.dp)
                                .testTag("settings_test_connection_error"),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Warning,
                                    contentDescription = "Failed",
                                    tint = WearsicError,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Failed: ${connectionTestState.message}",
                                    color = WearsicError,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // API Key Field (optional security)
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CircleShape)
                            .background(if (keyField.focused) WearsicSurfaceActive else WearsicSurface)
                            .border(
                                1.dp,
                                when {
                                    keyField.error != null -> WearsicError
                                    keyField.focused -> WearsicVibrantLavender
                                    else -> WearsicSurfaceBorderSubtle
                                },
                                CircleShape
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("settings_api_key_container"),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "API Key (optional)",
                                color = WearsicTextMuted,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                            BasicTextField(
                                value = keyField.text,
                                onValueChange = {
                                    keyField.text = it
                                    if (keyField.error != null) keyField.error = null
                                },
                                singleLine = true,
                                textStyle = TextStyle(color = WearsicTextPrimary, fontSize = 11.sp),
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    imeAction = ImeAction.Done,
                                    keyboardType = KeyboardType.Password
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        keyboardController?.hide()
                                        commitApiKey()
                                    }
                                ),
                                cursorBrush = SolidColor(WearsicVibrantLavender),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { focusState ->
                                        val wasFocused = keyField.focused
                                        keyField.focused = focusState.isFocused
                                        if (wasFocused && !focusState.isFocused) commitApiKey()
                                    }
                                    .testTag("settings_api_key")
                            )
                        }
                    }

                    if (keyField.error != null) {
                        Row(
                            modifier = Modifier
                                .padding(top = 3.dp, start = 10.dp)
                                .testTag("settings_api_key_error"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Warning,
                                contentDescription = null,
                                tint = WearsicError,
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = keyField.error ?: "",
                                color = WearsicError,
                                fontSize = 9.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Storage Stats Pill
            item {
                SettingsPillItem(
                    title = "Storage",
                    subtitle = "Downloads & offline cache",
                    icon = Icons.Rounded.SdStorage,
                    onClick = onOpenStorage,
                    testTag = "settings_storage",
                    showChevron = true
                )
            }

            // Engine dashboard: live /health + /ready straight from the server
            item {
                SettingsPillItem(
                    title = "Engine Dashboard",
                    subtitle = (connectionTestState as? ConnectionTestState.Success)
                        ?.let { "Engine v${it.version} connected" }
                        ?: "Health, extractor, updates",
                    icon = Icons.Rounded.MonitorHeart,
                    onClick = onOpenServerDashboard,
                    testTag = "settings_dashboard",
                    showChevron = true
                )
            }

            // ── Offline Audio ────────────────────────────────────────────
            item {
                Text(
                    text = "Offline Audio",
                    color = WearsicVibrantLavender,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // Auto-Cache Toggle Pill
            item {
                SettingsPillItem(
                    title = "Auto-Save Songs: ${if (autoCacheEnabled) "On" else "Off"}",
                    subtitle = if (autoCacheEnabled) {
                        "Every song you play saves for offline"
                    } else {
                        "Songs will not be saved offline"
                    },
                    icon = Icons.Rounded.AutoAwesome,
                    iconTint = if (autoCacheEnabled) WearsicVibrantLavender else WearsicTextMuted,
                    onClick = { onAutoCacheToggled(!autoCacheEnabled) },
                    testTag = "settings_auto_cache"
                )
            }

            // Offline song limit Pill
            item {
                val offlineLimits = listOf(15, 30, 50, 100)
                SettingsPillItem(
                    title = "Keep ${offlineLimitSongs} Songs Offline",
                    subtitle = "Tap to cycle · oldest removed first",
                    icon = Icons.Rounded.DownloadDone,
                    iconTint = WearsicVibrantLavender,
                    onClick = {
                        val idx = offlineLimits.indexOf(offlineLimitSongs)
                        val next = offlineLimits[(idx + 1).coerceAtLeast(0) % offlineLimits.size]
                        onOfflineLimitChanged(next)
                    },
                    testTag = "settings_offline_limit"
                )
            }

            // Clear Downloads Pill
            item {
                if (showClearDownloadsConfirm) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CircleShape)
                            .background(WearsicError.copy(alpha = 0.2f))
                            .border(1.dp, WearsicError, CircleShape)
                            .wearsicClickable {
                                onClearDownloads()
                                showClearDownloadsConfirm = false
                                downloadsClearedMessage = "Downloads Cleared"
                            }
                            .padding(horizontal = WearsicDimens.ScreenPaddingH, vertical = 10.dp)
                            .testTag("confirm_clear_downloads"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Confirm Delete All Downloads",
                            color = WearsicError,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    SettingsPillItem(
                        title = if (downloadsClearedMessage != null) downloadsClearedMessage!! else "Clear Downloads",
                        subtitle = if (downloadsClearedMessage != null) "All offline files deleted" else "Remove offline files",
                        icon = Icons.Rounded.Delete,
                        iconTint = if (downloadsClearedMessage != null) WearsicError else WearsicVibrantLavender,
                        onClick = {
                            showClearDownloadsConfirm = true
                        },
                        testTag = "settings_clear_downloads"
                    )
                }
            }

            // Build Info Footer
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Wearsic v${BuildConfig.VERSION_NAME}",
                        color = WearsicTextMuted,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center
                    )
                    // Diagnostics: only shown when a previous startup crashed
                    // or stalled, so it is invisible on healthy devices.
                    if (startupHealth.isNotBlank()) {
                        Text(
                            text = startupHealth,
                            color = WearsicTextMuted.copy(alpha = 0.8f),
                            fontSize = 8.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            // Bottom Spacing
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SettingsPillItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconTint: androidx.compose.ui.graphics.Color = WearsicVibrantLavender,
    showChevron: Boolean = false,
    testTag: String = "settings_pill"
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WearsicDimens.CardRadius))
            .background(WearsicSurfaceRaised)
            .border(1.dp, WearsicSurfaceBorderSubtle, RoundedCornerShape(WearsicDimens.CardRadius))
            .wearsicClickable(onClick = onClick)
            .padding(horizontal = WearsicDimens.ScreenPaddingH, vertical = 10.dp)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(WearsicLavenderContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = WearsicTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = WearsicTextSecondary,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (showChevron) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = WearsicTextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Text-editing state for a field whose canonical value lives in an async
 * store (DataStore). While the field is focused its local text is the source
 * of truth; inbound values are adopted only when they cannot be a stale echo
 * racing a save we just issued. This is what makes typing an address on the
 * watch keyboard stop "backspacing itself".
 */
private class GuardedTextFieldState(initial: String) {
    var text by mutableStateOf(initial)
    var focused by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    /** The value we expect the store to emit after a save; consumed once. */
    var pendingCommit by mutableStateOf<String?>(null)
}

@Composable
private fun rememberGuardedTextFieldState(external: String): GuardedTextFieldState {
    val state = remember { GuardedTextFieldState(external) }

    LaunchedEffect(external, state.focused) {
        if (!state.focused) {
            val pending = state.pendingCommit
            when {
                // No save in flight: plain sync (covers the initial load and
                // changes made by any other writer).
                pending == null -> if (state.text != external) state.text = external
                // Our own save round-tripped: consume it exactly once.
                external == pending -> {
                    state.pendingCommit = null
                    if (state.text != external) state.text = external
                }
                // A save is still in flight: leave the editor text alone.
            }
        }
    }

    // Safety valve: a save that never round-trips (screen left before the
    // write landed, storage hiccup) must not block sync forever.
    LaunchedEffect(state.pendingCommit) {
        if (state.pendingCommit != null) {
            delay(2500)
            state.pendingCommit = null
        }
    }

    return state
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun SettingsScreenPreview() {
    WearsicTheme {
        SettingsScreen()
    }
}
