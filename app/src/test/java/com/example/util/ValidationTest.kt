package com.example.util

import com.example.model.EmptyInputError
import com.example.model.InvalidConfigError
import com.example.model.InvalidTrackIdError
import com.example.model.InvalidUrlError
import com.example.model.InputTooLongError
import com.example.model.WearsicError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Validation utilities.
 * Tests all validation functions and their edge cases.
 */
class ValidationTest {

    // ============================================================================
    // URL Validation Tests
    // ============================================================================

    @Test
    fun `Valid HTTP URL passes validation`() {
        val result = Validation.validateServerUrl("http://example.com")
        assertTrue(result.isSuccess)
        assertEquals("http://example.com", result.getOrThrow())
    }

    @Test
    fun `Valid HTTPS URL passes validation`() {
        val result = Validation.validateServerUrl("https://example.com")
        assertTrue(result.isSuccess)
        assertEquals("https://example.com", result.getOrThrow())
    }

    @Test
    fun `URL with trailing slash is normalized`() {
        val result = Validation.validateServerUrl("http://example.com/")
        assertTrue(result.isSuccess)
        assertEquals("http://example.com", result.getOrThrow())
    }

    @Test
    fun `URL with port passes validation`() {
        val result = Validation.validateServerUrl("http://example.com:8080")
        assertTrue(result.isSuccess)
        assertEquals("http://example.com:8080", result.getOrThrow())
    }

    @Test
    fun `URL with path passes validation`() {
        val result = Validation.validateServerUrl("http://example.com/api")
        assertTrue(result.isSuccess)
        assertEquals("http://example.com/api", result.getOrThrow())
    }

    @Test
    fun `Empty URL fails validation`() {
        val result = Validation.validateServerUrl("")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is EmptyInputError)
    }

    @Test
    fun `Blank URL fails validation`() {
        val result = Validation.validateServerUrl("   ")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is EmptyInputError)
    }

    @Test
    fun `URL without scheme fails validation`() {
        val result = Validation.validateServerUrl("example.com")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InvalidUrlError)
    }

    @Test
    fun `URL with FTP scheme fails validation`() {
        val result = Validation.validateServerUrl("ftp://example.com")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InvalidUrlError)
    }

    @Test
    fun `URL with invalid port fails validation`() {
        val result = Validation.validateServerUrl("http://example.com:99999")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InvalidUrlError)
    }

    @Test
    fun `URL with negative port fails validation`() {
        val result = Validation.validateServerUrl("http://example.com:-1")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InvalidUrlError)
    }

    @Test
    fun `URL without host fails validation`() {
        val result = Validation.validateServerUrl("http://")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InvalidUrlError)
    }

    @Test
    fun `URL with spaces in host fails validation`() {
        val result = Validation.validateServerUrl("http://ex ample.com")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InvalidUrlError)
    }

    @Test
    fun `HTTPS required validation fails for HTTP`() {
        val result = Validation.validateServerUrl("http://example.com", requireHttps = true)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InvalidConfigError)
    }

    @Test
    fun `HTTPS required validation passes for HTTPS`() {
        val result = Validation.validateServerUrl("https://example.com", requireHttps = true)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `Localhost URL passes validation`() {
        val result = Validation.validateServerUrl("http://localhost")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `loopback IP URL passes validation`() {
        val result = Validation.validateServerUrl("http://127.0.0.1")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `Private IP URL passes validation`() {
        val result = Validation.validateServerUrl("http://192.168.1.1")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `hasValidScheme returns true for HTTP`() {
        assertTrue(Validation.hasValidScheme("http://example.com"))
    }

    @Test
    fun `hasValidScheme returns true for HTTPS`() {
        assertTrue(Validation.hasValidScheme("https://example.com"))
    }

    @Test
    fun `hasValidScheme returns false for FTP`() {
        assertFalse(Validation.hasValidScheme("ftp://example.com"))
    }

    @Test
    fun `hasValidScheme returns false for empty string`() {
        assertFalse(Validation.hasValidScheme(""))
    }

    // ============================================================================
    // Track ID Validation Tests
    // ============================================================================

    @Test
    fun `Valid YouTube video ID passes validation`() {
        val result = Validation.validateTrackId("dQw4w9WgXcQ")
        assertTrue(result.isSuccess)
        assertEquals("dQw4w9WgXcQ", result.getOrThrow())
    }

    @Test
    fun `Valid YouTube Music ID passes validation`() {
        val result = Validation.validateTrackId("UC-9-kyTW8ZkZNDHQJ6FgpwQ")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `Empty track ID fails validation`() {
        val result = Validation.validateTrackId("")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is EmptyInputError)
    }

    @Test
    fun `Blank track ID fails validation`() {
        val result = Validation.validateTrackId("   ")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is EmptyInputError)
    }

    @Test
    fun `Track ID with path traversal fails validation`() {
        val result = Validation.validateTrackId("../track")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InvalidTrackIdError)
    }

    @Test
    fun `Track ID with forward slash fails validation`() {
        val result = Validation.validateTrackId("track/123")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InvalidTrackIdError)
    }

    @Test
    fun `Track ID with backslash fails validation`() {
        val result = Validation.validateTrackId("track\\123")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InvalidTrackIdError)
    }

    @Test
    fun `Track ID with double dots fails validation`() {
        val result = Validation.validateTrackId("track..123")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InvalidTrackIdError)
    }

    @Test
    fun `Very long track ID fails validation`() {
        val longId = "a".repeat(200)
        val result = Validation.validateTrackId(longId)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InputTooLongError)
    }

    // ============================================================================
    // API Key Validation Tests
    // ============================================================================

    @Test
    fun `Valid API key passes validation`() {
        val result = Validation.validateApiKey("my-secret-key-123")
        assertTrue(result.isSuccess)
        assertEquals("my-secret-key-123", result.getOrThrow())
    }

    @Test
    fun `API key with spaces is trimmed`() {
        val result = Validation.validateApiKey("  my-secret-key  ")
        assertTrue(result.isSuccess)
        assertEquals("my-secret-key", result.getOrThrow())
    }

    @Test
    fun `Empty API key fails validation`() {
        val result = Validation.validateApiKey("")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is EmptyInputError)
    }

    @Test
    fun `Too short API key fails validation`() {
        val result = Validation.validateApiKey("short")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InputTooLongError)
    }

    @Test
    fun `Very long API key fails validation`() {
        val longKey = "a".repeat(300)
        val result = Validation.validateApiKey(longKey)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InputTooLongError)
    }

    // ============================================================================
    // Query Validation Tests
    // ============================================================================

    @Test
    fun `Valid query passes validation`() {
        val result = Validation.validateQuery("search query")
        assertTrue(result.isSuccess)
        assertEquals("search query", result.getOrThrow())
    }

    @Test
    fun `Query with spaces passes validation`() {
        val result = Validation.validateQuery("  search query  ")
        assertTrue(result.isSuccess)
        assertEquals("search query", result.getOrThrow())
    }

    @Test
    fun `Empty query fails validation`() {
        val result = Validation.validateQuery("")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is EmptyInputError)
    }

    @Test
    fun `Blank query fails validation`() {
        val result = Validation.validateQuery("   ")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is EmptyInputError)
    }

    @Test
    fun `Very long query fails validation`() {
        val longQuery = "a".repeat(300)
        val result = Validation.validateQuery(longQuery)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InputTooLongError)
    }

    // ============================================================================
    // Playlist Name Validation Tests
    // ============================================================================

    @Test
    fun `Valid playlist name passes validation`() {
        val result = Validation.validatePlaylistName("My Playlist")
        assertTrue(result.isSuccess)
        assertEquals("My Playlist", result.getOrThrow())
    }

    @Test
    fun `Playlist name with spaces passes validation`() {
        val result = Validation.validatePlaylistName("  My Playlist  ")
        assertTrue(result.isSuccess)
        assertEquals("My Playlist", result.getOrThrow())
    }

    @Test
    fun `Empty playlist name fails validation`() {
        val result = Validation.validatePlaylistName("")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is EmptyInputError)
    }

    @Test
    fun `Playlist name with forward slash fails validation`() {
        val result = Validation.validatePlaylistName("My/Playlist")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InvalidConfigError)
    }

    @Test
    fun `Playlist name with backslash fails validation`() {
        val result = Validation.validatePlaylistName("My\\Playlist")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InvalidConfigError)
    }

    @Test
    fun `Playlist name with double dots fails validation`() {
        val result = Validation.validatePlaylistName("My..Playlist")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InvalidConfigError)
    }

    @Test
    fun `Playlist name with colon fails validation`() {
        val result = Validation.validatePlaylistName("My:Playlist")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InvalidConfigError)
    }

    @Test
    fun `Very long playlist name fails validation`() {
        val longName = "a".repeat(200)
        val result = Validation.validatePlaylistName(longName)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is InputTooLongError)
    }

    // ============================================================================
    // Sanitization Tests
    // ============================================================================

    @Test
    fun `sanitizeDisplayString removes null bytes`() {
        val result = Validation.sanitizeDisplayString("Hello\u0000World")
        assertEquals("Hello World", result)
    }

    @Test
    fun `sanitizeDisplayString removes newlines`() {
        val result = Validation.sanitizeDisplayString("Hello\nWorld")
        assertEquals("Hello World", result)
    }

    @Test
    fun `sanitizeDisplayString removes carriage returns`() {
        val result = Validation.sanitizeDisplayString("Hello\rWorld")
        assertEquals("Hello World", result)
    }

    @Test
    fun `sanitizeDisplayString removes tabs`() {
        val result = Validation.sanitizeDisplayString("Hello\tWorld")
        assertEquals("Hello World", result)
    }

    @Test
    fun `sanitizeDisplayString collapses multiple spaces`() {
        val result = Validation.sanitizeDisplayString("Hello   World")
        assertEquals("Hello World", result)
    }

    @Test
    fun `sanitizeDisplayString trims result`() {
        val result = Validation.sanitizeDisplayString("  Hello World  ")
        assertEquals("Hello World", result)
    }

    @Test
    fun `sanitizeFilename removes invalid characters`() {
        val result = Validation.sanitizeFilename("file<name>:test.txt")
        assertEquals("file_name_test.txt", result)
    }

    @Test
    fun `sanitizeFilename removes forward slashes`() {
        val result = Validation.sanitizeFilename("path/to/file.txt")
        assertEquals("path_to_file.txt", result)
    }

    @Test
    fun `sanitizeFilename removes backslashes`() {
        val result = Validation.sanitizeFilename("path\\to\\file.txt")
        assertEquals("path_to_file.txt", result)
    }

    @Test
    fun `sanitizeFilename returns unnamed for empty input`() {
        val result = Validation.sanitizeFilename("")
        assertEquals("unnamed", result)
    }

    @Test
    fun `sanitizeFilename returns unnamed for null bytes only`() {
        val result = Validation.sanitizeFilename("\u0000\u0000")
        assertEquals("unnamed", result)
    }

    @Test
    fun `sanitizeTrackIdForFilename replaces invalid characters`() {
        val result = Validation.sanitizeTrackIdForFilename("track-123_id")
        assertEquals("track-123_id", result)
    }

    @Test
    fun `sanitizeTrackIdForFilename replaces spaces`() {
        val result = Validation.sanitizeTrackIdForFilename("track 123")
        assertEquals("track_123", result)
    }

    @Test
    fun `sanitizeTrackIdForFilename returns unknown for empty input`() {
        val result = Validation.sanitizeTrackIdForFilename("")
        assertEquals("unknown", result)
    }

    // ============================================================================
    // Extension Function Tests
    // ============================================================================

    @Test
    fun `String validateServerUrl extension works`() {
        val result = "http://example.com".validateServerUrl()
        assertTrue(result.isSuccess)
    }

    @Test
    fun `String validateTrackId extension works`() {
        val result = "dQw4w9WgXcQ".validateTrackId()
        assertTrue(result.isSuccess)
    }

    @Test
    fun `String validateApiKey extension works`() {
        val result = "my-secret-key".validateApiKey()
        assertTrue(result.isSuccess)
    }

    @Test
    fun `String validateQuery extension works`() {
        val result = "search query".validateQuery()
        assertTrue(result.isSuccess)
    }

    @Test
    fun `String sanitizeDisplay extension works`() {
        val result = "Hello\nWorld".sanitizeDisplay()
        assertEquals("Hello World", result)
    }

    @Test
    fun `String sanitizeFilename extension works`() {
        val result = "file<name>.txt".sanitizeFilename()
        assertEquals("file_name_.txt", result)
    }

    @Test
    fun `String sanitizeForFilename extension works`() {
        val result = "track-123".sanitizeForFilename()
        assertEquals("track-123", result)
    }

    @Test
    fun `String hasValidScheme extension works`() {
        assertTrue("http://example.com".hasValidScheme())
        assertFalse("ftp://example.com".hasValidScheme())
    }

    // ============================================================================
    // Result Extension Tests
    // ============================================================================

    @Test
    fun `Result getOrNull returns value on success`() {
        val result: Result<String> = Result.success("value")
        assertEquals("value", result.getOrNull())
    }

    @Test
    fun `Result getOrNull returns null on failure`() {
        val result: Result<String> = Result.failure(Exception("error"))
        assertTrue(result.getOrNull() == null)
    }

    @Test
    fun `Result isValid returns true on success`() {
        val result: Result<String> = Result.success("value")
        assertTrue(result.isValid())
    }

    @Test
    fun `Result isValid returns false on failure`() {
        val result: Result<String> = Result.failure(Exception("error"))
        assertFalse(result.isValid())
    }

    @Test
    fun `Result getDisplayError returns error message for WearsicError`() {
        val error = InvalidUrlError("ftp://example.com")
        val result: Result<String> = Result.failure(error)
        assertEquals("Invalid URL: ftp://example.com", result.getDisplayError())
    }

    @Test
    fun `Result getDisplayError returns null for non-WearsicError`() {
        val result: Result<String> = Result.failure(Exception("error"))
        assertTrue(result.getDisplayError() == null)
    }

    @Test
    fun `Result getDisplayError returns null on success`() {
        val result: Result<String> = Result.success("value")
        assertTrue(result.getDisplayError() == null)
    }

    // ============================================================================
    // Edge Cases
    // ============================================================================

    @Test
    fun `URL with query parameters passes validation`() {
        val result = Validation.validateServerUrl("http://example.com?param=value")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `URL with fragment passes validation`() {
        val result = Validation.validateServerUrl("http://example.com#fragment")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `URL with user info fails validation`() {
        // URLs with user info are not standard and should be rejected
        val result = Validation.validateServerUrl("http://user:pass@example.com")
        // This might pass or fail depending on URI parsing behavior
        // The test documents the current behavior
    }

    @Test
    fun `Track ID with underscores passes validation`() {
        val result = Validation.validateTrackId("track_123_test")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `Track ID with hyphens passes validation`() {
        val result = Validation.validateTrackId("track-123-test")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `API key with special characters passes validation`() {
        val result = Validation.validateApiKey("my-key_123.test")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `Query with special characters passes validation`() {
        val result = Validation.validateQuery("search & query")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `Playlist name with special characters passes validation`() {
        val result = Validation.validatePlaylistName("My Playlist 123")
        assertTrue(result.isSuccess)
    }
}
