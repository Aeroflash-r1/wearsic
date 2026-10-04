package com.wearsic.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import java.net.ConnectException

/**
 * Unit tests for the WearsicError hierarchy.
 * Tests error creation, display messages, and conversion from Throwables.
 */
class ErrorTest {

    // ============================================================================
    // NetworkError Tests
    // ============================================================================

    @Test
    fun `NoInternetError has correct display message`() {
        val error = NoInternetError
        assertEquals("No internet connection", error.getDisplayMessage())
        assertFalse(error.isRetryable)
        assertFalse(error.isFatal)
    }

    @Test
    fun `TimeoutError has correct display message`() {
        val error = TimeoutError(5000)
        assertEquals("Connection timed out", error.getDisplayMessage())
        assertTrue(error.isRetryable)
        assertFalse(error.isFatal)
    }

    @Test
    fun `HostUnreachableError has correct display message`() {
        val error = HostUnreachableError("example.com")
        assertEquals("Cannot reach example.com", error.getDisplayMessage())
        assertTrue(error.isRetryable)
        assertFalse(error.isFatal)
    }

    @Test
    fun `ConnectionRefusedError has correct display message`() {
        val error = ConnectionRefusedError
        assertEquals("Connection refused", error.getDisplayMessage())
        assertTrue(error.isRetryable)
        assertFalse(error.isFatal)
    }

    @Test
    fun `DnsError has correct display message`() {
        val error = DnsError("example.com")
        assertEquals("Could not resolve example.com", error.getDisplayMessage())
        assertTrue(error.isRetryable)
        assertFalse(error.isFatal)
    }

    // ============================================================================
    // ServerError Tests
    // ============================================================================

    @Test
    fun `HttpError 400 has correct display message`() {
        val error = HttpError("http://example.com", 400, "Bad request")
        assertEquals("Bad request", error.getDisplayMessage())
        assertFalse(error.isRetryable)
        assertEquals(400, error.statusCode)
    }

    @Test
    fun `HttpError 401 has correct display message`() {
        val error = HttpError("http://example.com", 401, "Unauthorized")
        assertEquals("Unauthorized. Check API key.", error.getDisplayMessage())
        assertFalse(error.isRetryable)
    }

    @Test
    fun `HttpError 404 has correct display message`() {
        val error = HttpError("http://example.com", 404, "Not found")
        assertEquals("Not found", error.getDisplayMessage())
        assertFalse(error.isRetryable)
    }

    @Test
    fun `HttpError 429 has correct display message`() {
        val error = HttpError("http://example.com", 429, "Rate limited")
        assertEquals("Server busy. Try again later.", error.getDisplayMessage())
        assertFalse(error.isRetryable)
    }

    @Test
    fun `HttpError 500 has correct display message`() {
        val error = HttpError("http://example.com", 500, "Internal server error")
        assertEquals("Server error", error.getDisplayMessage())
        assertFalse(error.isRetryable)
    }

    @Test
    fun `HttpError 502 has correct display message`() {
        val error = HttpError("http://example.com", 502, "Bad gateway")
        assertEquals("Server unavailable", error.getDisplayMessage())
        assertFalse(error.isRetryable)
    }

    @Test
    fun `HttpError 503 has correct display message`() {
        val error = HttpError("http://example.com", 503, "Service unavailable")
        assertEquals("Service unavailable", error.getDisplayMessage())
        assertTrue(error.isRetryable)
    }

    @Test
    fun `RateLimitedError has correct properties`() {
        val error = RateLimitedError
        assertEquals("Too many requests", error.message)
        assertEquals(429, error.statusCode)
        assertTrue(error.isRetryable)
    }

    @Test
    fun `UnauthorizedError has correct properties`() {
        val error = UnauthorizedError
        assertEquals("Unauthorized", error.message)
        assertEquals(401, error.statusCode)
        assertFalse(error.isRetryable)
    }

    @Test
    fun `ServiceUnavailableError has correct properties`() {
        val error = ServiceUnavailableError
        assertEquals("Service unavailable", error.message)
        assertEquals(503, error.statusCode)
        assertTrue(error.isRetryable)
    }

    // ============================================================================
    // ValidationError Tests
    // ============================================================================

    @Test
    fun `InvalidUrlError has correct display message`() {
        val error = InvalidUrlError("ftp://example.com")
        assertEquals("Invalid URL: ftp://example.com", error.message)
        assertFalse(error.isRetryable)
    }

    @Test
    fun `EmptyInputError has correct display message`() {
        val error = EmptyInputError("query")
        assertEquals("query cannot be empty", error.message)
        assertFalse(error.isRetryable)
    }

    @Test
    fun `InvalidTrackIdError has correct display message`() {
        val error = InvalidTrackIdError("invalid-id")
        assertEquals("Invalid track ID: invalid-id", error.message)
        assertFalse(error.isRetryable)
    }

    @Test
    fun `InputTooLongError has correct display message`() {
        val error = InputTooLongError("query", 100)
        assertEquals("query exceeds maximum length of 100", error.message)
        assertFalse(error.isRetryable)
    }

    @Test
    fun `InvalidConfigError has correct display message`() {
        val error = InvalidConfigError("server_url", "invalid")
        assertEquals("Invalid value for server_url: invalid", error.message)
        assertFalse(error.isRetryable)
    }

    // ============================================================================
    // StorageError Tests
    // ============================================================================

    @Test
    fun `StorageFullError has correct display message`() {
        val error = StorageFullError(100 * 1024 * 1024, 50 * 1024 * 1024) // 100MB needed, 50MB available
        assertTrue(error.getDisplayMessage().contains("Storage full"))
        assertFalse(error.isRetryable)
    }

    @Test
    fun `PermissionDeniedError has correct display message`() {
        val error = PermissionDeniedError
        assertEquals("Permission denied", error.message)
        assertFalse(error.isRetryable)
    }

    @Test
    fun `FileNotFoundError has correct display message`() {
        val error = FileNotFoundError("/path/to/file")
        assertEquals("File not found: /path/to/file", error.message)
        assertFalse(error.isRetryable)
    }

    @Test
    fun `FileCorruptedError has correct display message`() {
        val error = FileCorruptedError("/path/to/file")
        assertEquals("File corrupted: /path/to/file", error.message)
        assertFalse(error.isRetryable)
    }

    @Test
    fun `DatabaseError has correct display message`() {
        val error = DatabaseError("insert", "constraint violation")
        assertEquals("Database error during insert", error.message)
        assertFalse(error.isRetryable)
    }

    // ============================================================================
    // PlaybackError Tests
    // ============================================================================

    @Test
    fun `DecodingError has correct display message`() {
        val error = DecodingError("audio/mpeg")
        assertEquals("Cannot play audio/mpeg", error.message)
        assertFalse(error.isRetryable)
    }

    @Test
    fun `DecodingError without mime type has correct display message`() {
        val error = DecodingError()
        assertEquals("Cannot play this file", error.message)
        assertFalse(error.isRetryable)
    }

    @Test
    fun `PlaybackInterruptedError has correct display message`() {
        val error = PlaybackInterruptedError
        assertEquals("Playback interrupted", error.message)
        assertFalse(error.isRetryable)
    }

    @Test
    fun `NoMediaSourceError has correct display message`() {
        val error = NoMediaSourceError
        assertEquals("No media source", error.message)
        assertFalse(error.isRetryable)
    }

    @Test
    fun `AudioFocusLostError has correct display message`() {
        val error = AudioFocusLostError
        assertEquals("Audio focus lost", error.message)
        assertFalse(error.isRetryable)
    }

    // ============================================================================
    // DownloadError Tests
    // ============================================================================

    @Test
    fun `DownloadCancelledError has correct properties`() {
        val error = DownloadCancelledError
        assertEquals("Download cancelled", error.message)
        assertFalse(error.isRetryable)
    }

    @Test
    fun `DownloadNetworkError has correct display message`() {
        val error = DownloadNetworkError("http://example.com/track.mp3")
        assertEquals("Failed to download: http://example.com/track.mp3", error.message)
        assertTrue(error.isRetryable)
    }

    @Test
    fun `DownloadConflictError has correct display message`() {
        val error = DownloadConflictError("track-123")
        assertEquals("Download conflict: track-123", error.message)
        assertTrue(error.isRetryable)
    }

    @Test
    fun `TooManyDownloadsError has correct properties`() {
        val error = TooManyDownloadsError
        assertEquals("Too many downloads in progress", error.message)
        assertTrue(error.isRetryable)
    }

    // ============================================================================
    // AuthError Tests
    // ============================================================================

    @Test
    fun `InvalidApiKeyError has correct properties`() {
        val error = InvalidApiKeyError
        assertEquals("Invalid API key", error.message)
        assertFalse(error.isRetryable)
    }

    @Test
    fun `MissingApiKeyError has correct properties`() {
        val error = MissingApiKeyError
        assertEquals("API key required", error.message)
        assertFalse(error.isRetryable)
    }

    @Test
    fun `SessionExpiredError has correct properties`() {
        val error = SessionExpiredError
        assertEquals("Session expired", error.message)
        assertFalse(error.isRetryable)
    }

    // ============================================================================
    // ConfigError Tests
    // ============================================================================

    @Test
    fun `ServerUrlNotConfiguredError has correct properties`() {
        val error = ServerUrlNotConfiguredError
        assertEquals("Server URL not configured", error.message)
        assertFalse(error.isRetryable)
    }

    @Test
    fun `InvalidConfigError reports offline limit value`() {
        val error = InvalidConfigError("offline_limit", "1000")
        assertEquals("Invalid value for offline_limit: 1000", error.message)
        assertFalse(error.isRetryable)
    }

    // ============================================================================
    // UnknownError Tests
    // ============================================================================

    @Test
    fun `UnknownError has correct properties`() {
        val cause = Exception("Something went wrong")
        val error = UnknownError("An error occurred", cause)
        assertEquals("An error occurred", error.message)
        assertEquals(cause, error.cause)
        assertFalse(error.isRetryable)
    }

    @Test
    fun `UnknownError without cause has correct properties`() {
        val error = UnknownError("An error occurred")
        assertEquals("An error occurred", error.message)
        assertTrue(error.cause == null)
        assertFalse(error.isRetryable)
    }

    @Test
    fun `ExternalError has correct properties`() {
        val cause = Exception("Library error")
        val error = ExternalError("OkHttp", "Connection failed", cause)
        assertEquals("OkHttp: Connection failed", error.message)
        assertEquals("OkHttp", error.source)
        assertEquals(cause, error.original)
        assertFalse(error.isRetryable)
    }

    // ============================================================================
    // Throwable to WearsicError Conversion Tests
    // ============================================================================

    @Test
    fun `UnknownHostException converts to HostUnreachableError`() {
        val exception = UnknownHostException("example.com")
        val error = exception.toWearsicError()
        assertTrue(error is HostUnreachableError)
        assertEquals("example.com", (error as HostUnreachableError).host)
    }

    @Test
    fun `SocketTimeoutException converts to TimeoutError`() {
        val exception = SocketTimeoutException("Connection timed out")
        val error = exception.toWearsicError()
        assertTrue(error is TimeoutError)
    }

    @Test
    fun `ConnectException converts to ConnectionRefusedError`() {
        val exception = ConnectException("Connection refused")
        val error = exception.toWearsicError()
        assertTrue(error is ConnectionRefusedError)
    }

    @Test
    fun `IOException with hostname message converts to DnsError`() {
        val exception = java.io.IOException("No address associated with hostname")
        val error = exception.toWearsicError()
        assertTrue(error is DnsError)
    }

    @Test
    fun `Generic Exception converts to UnknownError`() {
        val exception = Exception("Something went wrong")
        val error = exception.toWearsicError()
        assertTrue(error is UnknownError)
        assertEquals("Something went wrong", error.message)
        assertEquals(exception, error.cause)
    }

    // ============================================================================
    // Int to ServerError Conversion Tests
    // ============================================================================

    @Test
    fun `Int 400 converts to HttpError`() {
        val error = 400.toServerError("http://example.com")
        assertTrue(error is HttpError)
        assertEquals(400, error.statusCode)
    }

    @Test
    fun `Int 401 converts to UnauthorizedError`() {
        val error = 401.toServerError()
        assertTrue(error is UnauthorizedError)
        assertEquals(401, error.statusCode)
    }

    @Test
    fun `Int 404 converts to NotFoundError`() {
        val error = 404.toServerError("http://example.com/resource")
        assertTrue(error is NotFoundError)
        assertEquals("http://example.com/resource", (error as NotFoundError).resource)
    }

    @Test
    fun `Int 500 converts to HttpError`() {
        val error = 500.toServerError("http://example.com")
        assertTrue(error is HttpError)
        assertEquals(500, error.statusCode)
    }

    // ============================================================================
    // Error Hierarchy Tests
    // ============================================================================

    @Test
    fun `All errors extend WearsicError`() {
        val errors = listOf(
            NoInternetError,
            TimeoutError(),
            HostUnreachableError("example.com"),
            HttpError("http://example.com", 500, "Error"),
            InvalidUrlError("ftp://example.com"),
            StorageFullError(100, 50),
            DecodingError(),
            DownloadCancelledError,
            InvalidApiKeyError,
            ServerUrlNotConfiguredError,
            UnknownError("Error")
        )
        
        for (error in errors) {
            assertTrue("$error should be a WearsicError", error is WearsicError)
        }
    }

    @Test
    fun `NetworkError hierarchy is correct`() {
        val errors = listOf(
            NoInternetError,
            TimeoutError(),
            HostUnreachableError("example.com"),
            ConnectionRefusedError,
            DnsError("example.com")
        )
        
        for (error in errors) {
            assertTrue("$error should be a NetworkError", error is NetworkError)
            assertTrue("$error should be a WearsicError", error is WearsicError)
        }
    }

    @Test
    fun `ServerError hierarchy is correct`() {
        val errors = listOf(
            HttpError("http://example.com", 500, "Error"),
            RateLimitedError,
            UnauthorizedError,
            ServiceUnavailableError
        )
        
        for (error in errors) {
            assertTrue("$error should be a ServerError", error is ServerError)
            assertTrue("$error should be a WearsicError", error is WearsicError)
        }
    }

    @Test
    fun `ValidationError hierarchy is correct`() {
        val errors = listOf(
            InvalidUrlError("ftp://example.com"),
            EmptyInputError("query"),
            InvalidTrackIdError("invalid"),
            InputTooLongError("query", 100),
            InvalidConfigError("key", "value")
        )
        
        for (error in errors) {
            assertTrue("$error should be a ValidationError", error is ValidationError)
            assertTrue("$error should be a WearsicError", error is WearsicError)
        }
    }

    // ============================================================================
    // Display Message Tests
    // ============================================================================

    @Test
    fun `All errors have non-null display messages`() {
        val errors = listOf(
            NoInternetError,
            TimeoutError(),
            HostUnreachableError("example.com"),
            HttpError("http://example.com", 500, "Error"),
            InvalidUrlError("ftp://example.com"),
            StorageFullError(100, 50),
            DecodingError(),
            DownloadCancelledError,
            InvalidApiKeyError,
            ServerUrlNotConfiguredError,
            UnknownError("Error")
        )
        
        for (error in errors) {
            val displayMessage = error.getDisplayMessage()
            assertNotNull("$error should have a non-null display message", displayMessage)
            assertTrue("$error display message should not be empty", displayMessage.isNotEmpty())
        }
    }

    // ============================================================================
    // Equality Tests
    // ============================================================================

    @Test
    fun `Errors with same properties are equal`() {
        val error1 = InvalidUrlError("ftp://example.com")
        val error2 = InvalidUrlError("ftp://example.com")
        assertEquals(error1, error2)
    }

    @Test
    fun `Errors with different properties are not equal`() {
        val error1 = InvalidUrlError("ftp://example.com")
        val error2 = InvalidUrlError("ftp://other.com")
        assertTrue(error1 != error2)
    }

    @Test
    fun `Singleton errors are equal to themselves`() {
        val errors = listOf(
            NoInternetError,
            ConnectionRefusedError,
            DownloadCancelledError,
            InvalidApiKeyError,
            ServerUrlNotConfiguredError
        )
        
        for (error in errors) {
            assertEquals(error, error)
        }
    }
}
