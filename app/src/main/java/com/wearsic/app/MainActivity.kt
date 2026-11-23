package com.wearsic.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.content.Intent
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.wearsic.app.ui.navigation.WearsicApp
import com.wearsic.app.ui.theme.WearsicBlack
import com.wearsic.app.ui.theme.WearsicTheme

class MainActivity : ComponentActivity() {

    companion object {
        /** Intent extra attached by the media notification's session activity. */
        const val EXTRA_OPEN_PLAYER = "com.wearsic.app.extra.OPEN_PLAYER"

        @Volatile
        var pendingTileAction: String? = null

        /**
         * Set when the app is opened from the media notification. The nav
         * host observes this snapshot state and routes straight to the player
         * instead of the library home screen; it resets itself once consumed.
         */
        val openPlayerRequest = mutableStateOf(false)
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Media notification permission result; playback works regardless. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StartupDiagnostics.log(this, "activity-oncreate")

        // Media notifications require POST_NOTIFICATIONS on API 33+ (Wear OS 6).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        handleOpenIntent(intent)

        setContent {
            WearsicTheme {
                // COLD-START CRITICAL SECTION
                //
                // The system splash screen (and its ANR window) lasts until
                // the FIRST frame is drawn. Building the ViewModel, the
                // repositories, the DataStore and the whole navigation graph
                // during that first composition is what produced the
                // "stuck on the opening screen, then the app closes" reports.
                //
                // So the first frame is deliberately trivial — a plain
                // backdrop — and the real app is composed on the NEXT frame,
                // after the splash has already been dismissed. Cold start is
                // no longer gated on it.
                var contentReady by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { contentReady = true }

                // Scale all app text up for comfortable reading on the watch
                // (1.25x lifts the smallest 8-12sp labels to a readable size).
                val density = LocalDensity.current

                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale = 1.25f)
                ) {
                    if (contentReady) {
                        WearsicApp()
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(WearsicBlack)
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenIntent(intent)
    }

    private fun handleOpenIntent(intent: Intent?) {
        val action = intent?.getStringExtra("tile_action")
        if (!action.isNullOrBlank()) {
            pendingTileAction = action
        }
        // Tapping the media notification must land on the player, not home.
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) {
            openPlayerRequest.value = true
        }
    }
}
