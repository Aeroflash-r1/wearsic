package com.wearsic.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.wearsic.app.data.PairingImporter
import com.wearsic.app.data.WearsicPreferencesRepository
import com.wearsic.app.network.WearsicHttp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the `wearsic pair` contract on the watch side: the two-line env file
 * pushed by the CLI is applied exactly once (address + key through the same
 * validated paths the Settings screen uses), then deleted — and a file that
 * fails validation changes nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = RobolectricDeviceQualifiers.WearOSLargeRound, sdk = [36])
class PairingImportTest {

    private lateinit var context: Context
    private lateinit var pairingFile: File

    private fun prefs() = WearsicPreferencesRepository(context)

    @Before
    fun setUp() {
        runBlocking(Dispatchers.IO) {
            context = ApplicationProvider.getApplicationContext()
            WearsicHttp.apiKey = ""
            val dir = context.getExternalFilesDir(null)!!.apply { mkdirs() }
            pairingFile = File(dir, PairingImporter.FILE_NAME)
            pairingFile.delete()
            // Start every test from blank settings: DataStore is a per-process
            // singleton, and sibling tests must not leak values into this one.
            prefs().saveServerUrl("")
            prefs().saveApiKey("")
        }
    }

    @Test
    fun pairingFile_appliesUrlAndKey_andIsConsumed() = runBlocking {
        pairingFile.writeText(
            "WEARSIC_SERVER_URL=http://192.168.1.50:8080\n" +
                "WEARSIC_API_KEY=secret-key-123\n"
        )

        val result = PairingImporter.importIfPresent(context)

        assertTrue(result is PairingImporter.Result.Applied)
        assertEquals("http://192.168.1.50:8080", prefs().serverUrlFlow.first())
        assertEquals("secret-key-123", prefs().apiKeyFlow.first())
        assertEquals("secret-key-123", WearsicHttp.apiKey)
        assertFalse("pairing file must be consumed exactly once", pairingFile.exists())
    }

    @Test
    fun pairingFile_toleratesCommentsAndCrlf() = runBlocking {
        pairingFile.writeText(
            "# pushed by wearsic pair\r\n" +
                "WEARSIC_SERVER_URL=https://phone.tailnet.ts.net\r\n" +
                "WEARSIC_API_KEY=secret-key-123\r\n"
        )

        val result = PairingImporter.importIfPresent(context)

        assertTrue(result is PairingImporter.Result.Applied)
        assertEquals("https://phone.tailnet.ts.net", prefs().serverUrlFlow.first())
        assertEquals("secret-key-123", prefs().apiKeyFlow.first())
    }

    @Test
    fun pairingFile_invalidUrl_changesNothing() = runBlocking {
        prefs().saveServerUrl("http://10.0.2.2:8080")

        pairingFile.writeText(
            "WEARSIC_SERVER_URL=not-a-url\n" +
                "WEARSIC_API_KEY=secret-key-123\n"
        )

        val result = PairingImporter.importIfPresent(context)

        assertTrue(result is PairingImporter.Result.Invalid)
        // All-or-nothing: the still-valid key line must NOT be applied.
        assertEquals("http://10.0.2.2:8080", prefs().serverUrlFlow.first())
        assertEquals("", prefs().apiKeyFlow.first())
        assertFalse(pairingFile.exists())
    }

    @Test
    fun pairingFile_blankKey_clearsTheStoredKey() = runBlocking {
        prefs().saveApiKey("old-secret-key")
        WearsicHttp.apiKey = "old-secret-key"

        pairingFile.writeText(
            "WEARSIC_SERVER_URL=http://192.168.1.50:8080\n" +
                "WEARSIC_API_KEY=\n"
        )

        val result = PairingImporter.importIfPresent(context)

        assertTrue(result is PairingImporter.Result.Applied)
        assertEquals("", prefs().apiKeyFlow.first())
        assertEquals("", WearsicHttp.apiKey)
    }

    @Test
    fun noPairingFile_isANoOp() = runBlocking {
        assertNull(PairingImporter.importIfPresent(context))
        assertEquals("", prefs().serverUrlFlow.first())
        assertEquals("", prefs().apiKeyFlow.first())
    }
}
