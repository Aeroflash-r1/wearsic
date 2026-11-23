package com.wearsic.app.ui.util

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * Colours derived from a track's album artwork so the player can theme itself
 * from what is on screen — the Pixel Watch "dynamic colour" idea.
 *
 * The four tones below were measured directly off a real Pixel Watch 4 media
 * screen (warm-orange artwork, `/Pixel-Watch-4-media-controls-1.png`):
 *
 *   control fill   #FFB68A   (light, saturated tint of the artwork hue)
 *   play blob      #FFDBC8   (a *lighter* tint of the same hue)
 *   glyph          near-black
 *   backdrop       #4E260A   (the artwork hue, darkened and desaturated)
 *   glass pills    translucent white over the backdrop, white glyphs
 *
 * So: the buttons are a light saturated tint of the artwork hue with dark
 * glyphs, the play button is an even lighter tint, and the whole background is
 * the same hue pushed dark. Extraction is dependency-free and cheap; callers
 * run it off the main thread.
 */

/** Glyph colour used on the light control fills. */
private val DarkGlyph = Color(0xFF17161A)

data class ArtworkColors(
    /** Light, saturated tint of the artwork hue — the skip-button fill. */
    val accent: Color,
    /** Even lighter tint of the same hue — the centre play/pause blob. */
    val blobTint: Color,
    /** Near-black glyph colour drawn on top of [accent] / [blobTint]. */
    val onAccent: Color,
    /** Dark tint of the same hue — the full-screen backdrop scrim. */
    val backdrop: Color,
    /** Deeper, richer hue for glows and progress highlights. */
    val accentStrong: Color,
    /** True when a real artwork colour was found (false = default palette). */
    val fromArtwork: Boolean
) {
    companion object {
        /** Fallback used when there is no artwork or it has no usable colour. */
        val Default = ArtworkColors(
            accent = Color(0xFFC9B6FF),
            blobTint = Color(0xFFE4DBFF),
            onAccent = DarkGlyph,
            backdrop = Color(0xFF241247),
            accentStrong = Color(0xFF8A5CF6),
            fromArtwork = false
        )
    }
}

private const val SAMPLE_EDGE = 32
private const val HUE_BUCKETS = 24
private const val MIN_SATURATION = 0.12f
private const val MIN_LUMA = 0.06f
private const val MAX_LUMA = 0.96f

/**
 * Extract the dominant hue from [source] and turn it into the Pixel Watch
 * control palette. Returns [ArtworkColors.Default] when the bitmap is
 * null/empty or contains no usable colour.
 */
fun extractArtworkColors(source: Bitmap?): ArtworkColors {
    if (source == null || source.width <= 0 || source.height <= 0) {
        return ArtworkColors.Default
    }

    val small = try {
        Bitmap.createScaledBitmap(source, SAMPLE_EDGE, SAMPLE_EDGE, true)
    } catch (_: Throwable) {
        source
    }

    val bucketWeight = FloatArray(HUE_BUCKETS)
    val bucketR = FloatArray(HUE_BUCKETS)
    val bucketG = FloatArray(HUE_BUCKETS)
    val bucketB = FloatArray(HUE_BUCKETS)

    var fallbackR = 0f
    var fallbackG = 0f
    var fallbackB = 0f
    var fallbackN = 0

    val width = small.width
    val height = small.height
    val pixels = IntArray(width * height)
    small.getPixels(pixels, 0, width, 0, 0, width, height)

    for (pixel in pixels) {
        val r = ((pixel shr 16) and 0xFF) / 255f
        val g = ((pixel shr 8) and 0xFF) / 255f
        val b = (pixel and 0xFF) / 255f

        fallbackR += r
        fallbackG += g
        fallbackB += b
        fallbackN++

        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val chroma = max - min

        if (luma < MIN_LUMA || luma > MAX_LUMA) continue
        if (chroma <= 0.0001f) continue
        val saturation = chroma / (1f - kotlin.math.abs(2f * luma - 1f)).coerceAtLeast(0.0001f)
        if (saturation < MIN_SATURATION) continue

        val hue = hueOf(r, g, b, max, chroma)
        val bucket = ((hue / 360f) * HUE_BUCKETS).toInt().coerceIn(0, HUE_BUCKETS - 1)

        // Weight vivid, mid-bright colours more heavily than dull ones.
        val weight = saturation * (1f - kotlin.math.abs(luma - 0.55f))
        bucketWeight[bucket] += weight.coerceAtLeast(0.01f)
        bucketR[bucket] += r * weight
        bucketG[bucket] += g * weight
        bucketB[bucket] += b * weight
    }

    if (small !== source) small.recycle()

    var best = -1
    var bestWeight = 0f
    for (i in 0 until HUE_BUCKETS) {
        if (bucketWeight[i] > bestWeight) {
            bestWeight = bucketWeight[i]
            best = i
        }
    }

    val base: Color = if (best < 0 || bestWeight <= 0f) {
        if (fallbackN == 0) return ArtworkColors.Default
        val r = fallbackR / fallbackN
        val g = fallbackG / fallbackN
        val b = fallbackB / fallbackN
        val avg = Color(red = r, green = g, blue = b)
        if (avg == Color.Black) return ArtworkColors.Default
        avg
    } else {
        Color(
            red = bucketR[best] / bestWeight,
            green = bucketG[best] / bestWeight,
            blue = bucketB[best] / bestWeight
        )
    }

    return fromBase(base, fromArtwork = true)
}

/** Build the Pixel Watch palette from a single dominant [base] colour. */
private fun fromBase(base: Color, fromArtwork: Boolean): ArtworkColors {
    return ArtworkColors(
        accent = tinted(base, saturation = 0.38f..0.62f, value = 1.0f),
        blobTint = tinted(base, saturation = 0.16f..0.30f, value = 1.0f),
        onAccent = DarkGlyph,
        backdrop = tinted(base, saturation = 0.70f..0.95f, value = 0.30f),
        accentStrong = tinted(base, saturation = 0.60f..0.85f, value = 0.58f),
        fromArtwork = fromArtwork
    )
}

/**
 * Keep the hue of [base] but push its saturation into [saturation] and set a
 * fixed brightness — this is what produces the light-saturated control tint
 * and the dark, hue-matched backdrop from one album colour.
 */
private fun tinted(
    base: Color,
    saturation: ClosedFloatingPointRange<Float>,
    value: Float
): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(base.toArgb(), hsv)
    hsv[1] = hsv[1].coerceIn(saturation.start, saturation.endInclusive)
    hsv[2] = value
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private fun hueOf(r: Float, g: Float, b: Float, max: Float, chroma: Float): Float {
    val hue = when (max) {
        r -> ((g - b) / chroma) % 6f
        g -> ((b - r) / chroma) + 2f
        else -> ((r - g) / chroma) + 4f
    }
    val degrees = hue * 60f
    return if (degrees < 0f) degrees + 360f else degrees
}
