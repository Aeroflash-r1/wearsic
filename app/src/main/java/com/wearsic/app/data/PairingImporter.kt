package com.wearsic.app.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.wearsic.app.network.WearsicHttp
import com.wearsic.app.util.Validation
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Imports the pairing file that `wearsic pair` (phone-side CLI) pushes over
 * adb, so the server address and API key never have to be typed on a 1.4"
 * watch keyboard.
 *
 * File contract (kept in sync with `cmd_pair` in the CLI):
 *
 *     /sdcard/Android/data/com.wearsic.app/files/wearsic-pairing.env
 *     WEARSIC_SERVER_URL=http://192.168.1.38:8080
 *     WEARSIC_API_KEY=<key>
 *
 * The file is consumed exactly once: after a successful import it is
 * deleted, so a stale file can never overwrite a later manual change.
 * Validation mirrors the Settings screen (all-or-nothing): an invalid
 * address or key is rejected and reported with a toast, nothing is written.
 */
object PairingImporter {

    const val FILE_NAME = "wearsic-pairing.env"

    private const val TAG = "WearsicPairing"
    private const val KEY_URL = "WEARSIC_SERVER_URL"
    private const val KEY_API_KEY = "WEARSIC_API_KEY"

    sealed interface Result {
        data class Applied(val serverUrl: String?, val keyChanged: Boolean) : Result
        data class Invalid(val reason: String) : Result
    }

    /**
     * Applies the pairing file when one is present; returns null when there
     * is nothing to do. Safe to call on every app start and on every
     * Settings-screen entry (covers both cold and warm processes).
     */
    suspend fun importIfPresent(context: Context): Result? = withContext(Dispatchers.IO) {
        val dir = context.getExternalFilesDir(null) ?: return@withContext null
        val file = File(dir, FILE_NAME)
        if (!file.isFile) return@withContext null

        val text = runCatching { file.readText() }.getOrNull()
        if (text == null) {
            // Unreadable for now: keep the file and retry on the next launch.
            Log.w(TAG, "pairing file present but unreadable")
            return@withContext null
        }

        val values = parse(text)
        if (values.isEmpty()) {
            file.delete()
            return@withContext invalid(context, "no pairing values found")
        }

        val prefs = WearsicPreferencesRepository(context)

        // Validate everything BEFORE writing anything: a half-applied
        // pairing (new URL, rejected key) is worse than none.
        val rawUrl = values[KEY_URL]
        val cleanUrl = rawUrl?.let { Validation.validateServerUrl(it).getOrNull() }
        if (rawUrl != null && cleanUrl == null) {
            file.delete()
            return@withContext invalid(context, "invalid server address")
        }

        // saveApiKey() returns null for a rejected key and "" for a blank
        // one (which clears) — mirror its decision exactly, or the shared
        // HTTP client would carry a key the server will 401.
        val rawKey = values[KEY_API_KEY]
        val appliedKey = rawKey?.let { prefs.saveApiKey(it) }
        if (rawKey != null && appliedKey == null) {
            file.delete()
            return@withContext invalid(context, "invalid API key (8-256 characters)")
        }

        cleanUrl?.let { prefs.saveServerUrl(it) }
        if (appliedKey != null) WearsicHttp.apiKey = appliedKey.trim()

        file.delete()
        Log.i(TAG, "pairing applied (url=${cleanUrl != null}, key=${appliedKey != null})")
        toast(
            context,
            when {
                cleanUrl != null && appliedKey != null -> "Paired: server address + key applied"
                cleanUrl != null -> "Paired: server address applied"
                else -> "Paired: API key applied"
            }
        )
        Result.Applied(serverUrl = cleanUrl, keyChanged = appliedKey != null)
    }

    private fun parse(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val eq = trimmed.indexOf('=')
            if (eq <= 0) return@forEach
            val key = trimmed.substring(0, eq).trim()
            if (key == KEY_URL || key == KEY_API_KEY) {
                out[key] = trimmed.substring(eq + 1).trim()
            }
        }
        return out
    }

    private fun invalid(context: Context, reason: String): Result.Invalid {
        Log.w(TAG, "pairing file rejected: $reason")
        toast(context, "Pairing failed: $reason")
        return Result.Invalid(reason)
    }

    private fun toast(context: Context, message: String) {
        runCatching {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
            }
        }
    }
}
