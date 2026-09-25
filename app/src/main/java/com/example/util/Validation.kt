package com.example.util

import com.example.model.WearsicError
import com.example.model.EmptyInputError
import com.example.model.InvalidConfigError
import com.example.model.InvalidTrackIdError
import com.example.model.InvalidUrlError
import com.example.model.InputTooLongError
import java.net.URI
import java.net.URISyntaxException
import java.util.regex.Pattern

/**
 * Input validation utilities for the Wearsic application.
 * Centralizes all validation logic to prevent security issues and data corruption.
 */

object Validation {
    private val ALLOWED_SCHEMES = setOf("http", "https")
    private const val MAX_URL_LENGTH = 4096
    private val YOUTUBE_VIDEO_ID_PATTERN: Pattern = Pattern.compile("^[A-Za-z0-9_-]{11}$")
    private const val MAX_TRACK_ID_LENGTH = 128
    private const val MIN_API_KEY_LENGTH = 8
    private const val MAX_API_KEY_LENGTH = 256
    private const val MAX_QUERY_LENGTH = 200
    private const val MAX_PLAYLIST_NAME_LENGTH = 100

    fun validateServerUrl(url: String, requireHttps: Boolean = false): Result<String> {
        if (url.isBlank()) return Result.failure(EmptyInputError("URL"))
        if (url.length > MAX_URL_LENGTH) return Result.failure(InputTooLongError("URL", MAX_URL_LENGTH))
        
        val sanitized = url.trim().removeSuffix("/")
        val uri: URI = try { URI(sanitized) } catch (e: URISyntaxException) { return Result.failure(InvalidUrlError(sanitized)) }
        
        val scheme = uri.scheme?.lowercase()
        if (scheme == null || scheme !in ALLOWED_SCHEMES) return Result.failure(InvalidUrlError(sanitized))
        if (requireHttps && scheme == "http") return Result.failure(InvalidConfigError("requireHttps", "HTTP not allowed"))
        
        val host = uri.host
        if (host.isNullOrBlank()) return Result.failure(InvalidUrlError(sanitized))
        
        val port = uri.port
        if (port != -1 && (port < 1 || port > 65535)) return Result.failure(InvalidUrlError(sanitized))
        
        return Result.success(sanitized)
    }

    fun hasValidScheme(url: String): Boolean {
        if (url.isBlank()) return false
        val trimmed = url.trim().lowercase()
        return trimmed.startsWith("http://") || trimmed.startsWith("https://")
    }

    fun validateTrackId(trackId: String): Result<String> {
        if (trackId.isBlank()) return Result.failure(EmptyInputError("track ID"))
        if (trackId.length > MAX_TRACK_ID_LENGTH) return Result.failure(InputTooLongError("track ID", MAX_TRACK_ID_LENGTH))
        if (trackId.length == 11 && !YOUTUBE_VIDEO_ID_PATTERN.matcher(trackId).matches()) return Result.failure(InvalidTrackIdError(trackId))
        if (trackId.contains("..") || trackId.contains("/") || trackId.contains("\\")) return Result.failure(InvalidTrackIdError(trackId))
        return Result.success(trackId)
    }

    fun validateApiKey(apiKey: String): Result<String> {
        if (apiKey.isBlank()) return Result.failure(EmptyInputError("API key"))
        val trimmed = apiKey.trim()
        if (trimmed.length < MIN_API_KEY_LENGTH) return Result.failure(InputTooLongError("API key", MIN_API_KEY_LENGTH))
        if (trimmed.length > MAX_API_KEY_LENGTH) return Result.failure(InputTooLongError("API key", MAX_API_KEY_LENGTH))
        return Result.success(trimmed)
    }

    fun validateQuery(query: String): Result<String> {
        if (query.isBlank()) return Result.failure(EmptyInputError("query"))
        val trimmed = query.trim()
        if (trimmed.length > MAX_QUERY_LENGTH) return Result.failure(InputTooLongError("query", MAX_QUERY_LENGTH))
        return Result.success(trimmed)
    }

    fun validatePlaylistName(name: String): Result<String> {
        if (name.isBlank()) return Result.failure(EmptyInputError("playlist name"))
        val trimmed = name.trim()
        if (trimmed.length > MAX_PLAYLIST_NAME_LENGTH) return Result.failure(InputTooLongError("playlist name", MAX_PLAYLIST_NAME_LENGTH))
        if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("..") || trimmed.contains(":")) 
            return Result.failure(InvalidConfigError("playlist name", "Invalid characters"))
        return Result.success(trimmed)
    }

    fun sanitizeDisplayString(input: String): String {
        return input
            .replace("\u0000", "")
            .replace("\n", " ")
            .replace("\r", " ")
            .replace("\t", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun sanitizeFilename(input: String): String {
        return input
            .replace(Regex("[<>:\\\\\\|\\?\\*]"), "_")
            .replace("/", "_")
            .replace("\\", "_")
            .replace("\u0000", "")
            .trim()
            .takeIf { it.isNotBlank() } ?: "unnamed"
    }

    fun sanitizeTrackIdForFilename(trackId: String): String {
        return trackId
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .takeIf { it.isNotBlank() } ?: "unknown"
    }
}

fun String.validateServerUrl(requireHttps: Boolean = false): Result<String> = Validation.validateServerUrl(this, requireHttps)
fun String.validateTrackId(): Result<String> = Validation.validateTrackId(this)
fun String.validateApiKey(): Result<String> = Validation.validateApiKey(this)
fun String.validateQuery(): Result<String> = Validation.validateQuery(this)
fun String.sanitizeDisplay(): String = Validation.sanitizeDisplayString(this)
fun String.sanitizeFilename(): String = Validation.sanitizeFilename(this)
fun String.sanitizeForFilename(): String = Validation.sanitizeTrackIdForFilename(this)
fun String.hasValidScheme(): Boolean = Validation.hasValidScheme(this)

fun <T> Result<T>.getOrNull(): T? = this.getOrNull()
fun <T> Result<T>.isValid(): Boolean = this.isSuccess
fun <T> Result<T>.getDisplayError(): String? = (this.exceptionOrNull() as? WearsicError)?.getDisplayMessage()
