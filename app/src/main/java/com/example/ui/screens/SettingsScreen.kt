package com.example.ui.screens

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
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
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
import com.example.BuildConfig
import com.example.network.model.ConnectionTestState
import com.example.ui.components.WearsicScreenHeader
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.ui.theme.WearsicSurfaceRaised
import com.example.ui.theme.WearsicAppBackground
import com.example.ui.theme.wearsicListContentPadding
import com.example.ui.theme.WearsicBlack
import com.example.ui.theme.WearsicError
import com.example.ui.theme.WearsicLavenderContainer
import com.example.ui.theme.WearsicSuccess
import com.example.ui.theme.WearsicSurface
import com.example.ui.theme.WearsicSurfaceActive
import com.example.ui.theme.WearsicSurfaceBorderSubtle
import com.example.ui.theme.WearsicTextMuted
import com.example.ui.theme.WearsicTextPrimary
import com.example.ui.theme.WearsicTextPrimaryDark
import com.example.ui.theme.WearsicTextSecondary
import com.example.ui.theme.WearsicTheme
import com.example.ui.theme.WearsicVibrantLavender

import com.example.ui.util.wearsicClickable
import com.example.ui.util.wearsicEntrance
import com.example.ui.util.wearsicRotaryScroll

@Composable
fun SettingsScreen(
    serverUrl: String = "",
    connectionTestState: ConnectionTestState = ConnectionTestState.Idle,
    onServerUrlChanged: (String) -> Unit = {},
    onTestConnection: (String) -> Unit = {},
    apiKey: String = "",
    onApiKeyChanged: (String) -> Unit = {},
    onOpenStorage: () -> Unit = {},
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
                var isFocused by remember { mutableStateOf(false) }
                var typedUrl by remember(serverUrl) { mutableStateOf(serverUrl) }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .background(if (isFocused) WearsicSurfaceActive else WearsicSurface)
                        .border(
                            1.dp,
                            if (isFocused) WearsicVibrantLavender else WearsicSurfaceBorderSubtle,
                            CircleShape
                        )
                        .padding(horizontal = 14.dp, vertical = 6.dp)
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
                            tint = if (isFocused) WearsicVibrantLavender else WearsicTextSecondary,
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
                                if (typedUrl.isEmpty()) {
                                    Text(
                                        text = "Enter URL...",
                                        color = WearsicTextMuted,
                                        fontSize = 11.sp,
                                        maxLines = 1
                                    )
                                }

                                BasicTextField(
                                    value = typedUrl,
                                    onValueChange = {
                                        typedUrl = it
                                        onServerUrlChanged(it)
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
                                            onServerUrlChanged(typedUrl)
                                        }
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onFocusChanged { focusState ->
                                            isFocused = focusState.isFocused
                                        }
                                        .testTag("settings_server_url")
                                )
                            }
                        }

                        if (typedUrl.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Clear URL",
                                tint = WearsicTextSecondary,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        typedUrl = ""
                                        onServerUrlChanged("")
                                    }
                                    .testTag("settings_server_url_clear")
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
                                .wearsicClickable { onTestConnection(serverUrl) }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
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
                                .padding(horizontal = 14.dp, vertical = 10.dp)
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
                                .wearsicClickable { onTestConnection(serverUrl) }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
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
                                .wearsicClickable { onTestConnection(serverUrl) }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
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
                var typedKey by remember(apiKey) { mutableStateOf(apiKey) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .background(WearsicSurface)
                        .border(1.dp, WearsicSurfaceBorderSubtle, CircleShape)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
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
                            value = typedKey,
                            onValueChange = {
                                typedKey = it
                                onApiKeyChanged(it)
                            },
                            singleLine = true,
                            textStyle = TextStyle(color = WearsicTextPrimary, fontSize = 11.sp),
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Done,
                                keyboardType = KeyboardType.Password
                            ),
                            cursorBrush = SolidColor(WearsicVibrantLavender),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Storage Stats Pill
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .background(WearsicSurface)
                        .border(1.dp, WearsicSurfaceBorderSubtle, CircleShape)
                        .wearsicClickable(onClick = onOpenStorage)
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.SdStorage,
                            contentDescription = null,
                            tint = WearsicVibrantLavender,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Storage", color = WearsicTextPrimary, fontSize = 12.sp)
                    }
                }
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
                            .padding(horizontal = 14.dp, vertical = 10.dp)
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
    testTag: String = "settings_pill"
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(WearsicSurfaceRaised)
            .border(1.dp, WearsicSurfaceBorderSubtle, RoundedCornerShape(20.dp))
            .wearsicClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
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
        }
    }
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true)
@Composable
fun SettingsScreenPreview() {
    WearsicTheme {
        SettingsScreen()
    }
}
