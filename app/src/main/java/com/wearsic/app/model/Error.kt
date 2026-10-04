package com.wearsic.app.model

/**
 * Sealed hierarchy of all application errors.
 * Enables consistent error handling, user-friendly messages, and proper error classification.
 */

sealed class WearsicError(
    override val message: String,
    open val isRetryable: Boolean = false,
    open val isFatal: Boolean = false
) : Throwable(message) {
    /**
     * User-friendly message suitable for display on the watch.
     * Short, actionable, and never exposes internal details.
     */
    open fun getDisplayMessage(): String = message
}

// ============================================================================
// Network Errors
// ============================================================================

sealed class NetworkError(
    override val message: String,
    override val isRetryable: Boolean = true
) : WearsicError(message, isRetryable) {
    override fun getDisplayMessage(): String = when (this) {
        is NoInternetError -> "No internet connection"
        is TimeoutError -> "Connection timed out"
        is HostUnreachableError -> "Cannot reach $host"
        is ConnectionRefusedError -> "Connection refused"
        is DnsError -> "Could not resolve $hostname"
        else -> "Network error"
    }
}

/**
 * No network connectivity detected. Unlike the transient network failures
 * above, this is not retried automatically — the caller should re-check
 * connectivity first.
 */
data object NoInternetError : NetworkError("No internet connection", isRetryable = false)

/** Connection attempt timed out */
data class TimeoutError(val timeoutMs: Long = 5000) : NetworkError("Connection timed out after ${timeoutMs}ms")

/** Host could not be resolved (DNS failure) */
data class HostUnreachableError(val host: String) : NetworkError("Cannot reach $host")

/** Server actively refused the connection */
data object ConnectionRefusedError : NetworkError("Connection refused")

/** DNS resolution failed */
data class DnsError(val hostname: String) : NetworkError("Could not resolve $hostname")

// ============================================================================
// Server Errors
// ============================================================================

sealed class ServerError(
    override val message: String,
    open val statusCode: Int,
    override val isRetryable: Boolean = false
) : WearsicError(message, isRetryable) {
    override fun getDisplayMessage(): String = when (statusCode) {
        400 -> "Bad request"
        401 -> "Unauthorized. Check API key."
        403 -> "Access denied"
        404 -> "Not found"
        429 -> "Server busy. Try again later."
        503 -> "Service unavailable"
        500 -> "Server error"
        502 -> "Server unavailable"
        else -> "Server error: $statusCode"
    }
}

/** Server returned a non-2xx HTTP status */
data class HttpError(
    val url: String,
    override val statusCode: Int,
    override val message: String = "HTTP $statusCode"
) : ServerError(message, statusCode) {
    /**
     * 503 (service unavailable) is the one HTTP status the server explicitly
     * asks us to retry; every other status is a terminal answer for this
     * request.
     */
    override val isRetryable: Boolean get() = statusCode == 503
}

/** Server is rate limiting requests */
data object RateLimitedError : ServerError("Too many requests", 429, isRetryable = true)

/** Server requires authentication */
data object UnauthorizedError : ServerError("Unauthorized", 401)

/** Requested resource not found on server */
data class NotFoundError(val resource: String) : ServerError("Not found: $resource", 404)

/** Server is temporarily unavailable */
data object ServiceUnavailableError : ServerError("Service unavailable", 503, isRetryable = true)

// ============================================================================
// Validation Errors
// ============================================================================

sealed class ValidationError(
    override val message: String,
    override val isRetryable: Boolean = false
) : WearsicError(message, isRetryable)

/** Invalid URL scheme (not http/https) */
data class InvalidUrlError(val url: String) : ValidationError("Invalid URL: $url")

/** Empty or blank input */
data class EmptyInputError(val fieldName: String) : ValidationError("$fieldName cannot be empty")

/** Invalid track ID format */
data class InvalidTrackIdError(val trackId: String) : ValidationError("Invalid track ID: $trackId")

/** Input exceeds maximum length */
data class InputTooLongError(val fieldName: String, val maxLength: Int) : 
    ValidationError("$fieldName exceeds maximum length of $maxLength")

/** Invalid configuration value supplied by the user (a validation failure). */
data class InvalidConfigError(val key: String, val value: String) : 
    ValidationError("Invalid value for $key: $value")

// ============================================================================
// Storage Errors
// ============================================================================

sealed class StorageError(
    override val message: String,
    override val isRetryable: Boolean = false
) : WearsicError(message, isRetryable)

/** Device storage is full */
data class StorageFullError(val requiredBytes: Long, val availableBytes: Long) : 
    StorageError("Storage full. Need ${requiredBytes / (1024 * 1024)}MB, have ${availableBytes / (1024 * 1024)}MB")

/** File system permission denied */
data object PermissionDeniedError : StorageError("Permission denied")

/** File not found on device */
data class FileNotFoundError(val path: String) : StorageError("File not found: $path")

/** File is corrupted or incomplete */
data class FileCorruptedError(val path: String) : StorageError("File corrupted: $path")

/** Database operation failed */
data class DatabaseError(val operation: String, val rootCause: String? = null) : 
    StorageError("Database error during $operation")

// ============================================================================
// Playback Errors
// ============================================================================

sealed class PlaybackError(
    override val message: String,
    override val isRetryable: Boolean = false
) : WearsicError(message, isRetryable)

/** Media file cannot be played */
data class DecodingError(val mimeType: String? = null) : 
    PlaybackError("Cannot play ${mimeType ?: "this file"}")

/** Playback was interrupted */
data object PlaybackInterruptedError : PlaybackError("Playback interrupted")

/** No valid media source */
data object NoMediaSourceError : PlaybackError("No media source")

/** Audio focus lost */
data object AudioFocusLostError : PlaybackError("Audio focus lost")

// ============================================================================
// Download Errors
// ============================================================================

sealed class DownloadError(
    override val message: String,
    override val isRetryable: Boolean = true
) : WearsicError(message, isRetryable)

/** Download was cancelled by user */
data object DownloadCancelledError : DownloadError("Download cancelled", isRetryable = false)

/** Download failed due to network */
data class DownloadNetworkError(val url: String) : DownloadError("Failed to download: $url")

/** Download file already exists with different checksum */
data class DownloadConflictError(val trackId: String) : DownloadError("Download conflict: $trackId")

/** Too many concurrent downloads */
data object TooManyDownloadsError : DownloadError("Too many downloads in progress")

// ============================================================================
// Authentication Errors
// ============================================================================

sealed class AuthError(
    override val message: String,
    override val isRetryable: Boolean = false
) : WearsicError(message, isRetryable)

/** Invalid API key */
data object InvalidApiKeyError : AuthError("Invalid API key")

/** API key is missing */
data object MissingApiKeyError : AuthError("API key required")

/** Session expired */
data object SessionExpiredError : AuthError("Session expired")

// ============================================================================
// Configuration Errors
// ============================================================================

sealed class ConfigError(
    override val message: String,
    override val isRetryable: Boolean = false
) : WearsicError(message, isRetryable)

/** Server URL is not configured */
data object ServerUrlNotConfiguredError : ConfigError("Server URL not configured")

// ============================================================================
// Unknown Errors
// ============================================================================

/** An unexpected error that doesn't fit other categories */
data class UnknownError(
    override val message: String,
    override val cause: Throwable? = null
) : WearsicError(message)

/**
 * Error from a third-party library. The detail is stored as [detailMessage]
 * and combined into the inherited `message` as "source: detail", so
 * `message` is never shadowed by a second property and [source]/[original]
 * stay separately available.
 */
data class ExternalError(
    val source: String,
    val detailMessage: String,
    val original: Throwable? = null
) : WearsicError("$source: $detailMessage")

// ============================================================================
// Extensions
// ============================================================================

/**
 * Convert a Throwable to a WearsicError
 */
fun Throwable.toWearsicError(): WearsicError = when (this) {
    is java.net.UnknownHostException -> HostUnreachableError(this.message ?: "unknown")
    is java.net.ConnectException -> ConnectionRefusedError
    is java.net.SocketTimeoutException -> TimeoutError()
    is java.io.IOException -> when (this.message) {
        "No address associated with hostname" -> DnsError(this.message ?: "unknown")
        else -> UnknownError(this.message ?: "Network error", this)
    }
    is java.net.SocketException -> ConnectionRefusedError
    else -> UnknownError(this.message ?: "Unknown error", this)
}

/**
 * Convert an HTTP status code to a ServerError
 */
fun Int.toServerError(url: String = ""): ServerError = when (this) {
    400 -> HttpError(url, this, "Bad request")
    401 -> UnauthorizedError
    403 -> HttpError(url, this, "Forbidden")
    404 -> NotFoundError(url)
    429 -> RateLimitedError
    500 -> HttpError(url, this, "Internal server error")
    502 -> HttpError(url, this, "Bad gateway")
    503 -> ServiceUnavailableError
    else -> HttpError(url, this, "HTTP $this")
}
