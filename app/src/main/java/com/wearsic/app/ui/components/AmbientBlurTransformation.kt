package com.wearsic.app.ui.components

import android.graphics.Bitmap
import coil.size.Size
import coil.transform.Transformation

/**
 * Blurs a bitmap ONCE at load time by downscaling + upscaling with bilinear
 * filtering. Zero runtime GPU cost — the result is cached with the image.
 * Used for ambient player backdrops where a soft color blob is all we need.
 */
class AmbientBlurTransformation(
    private val scaleFactor: Int = 6
) : Transformation {

    override val cacheKey: String = "ambient-blur-$scaleFactor"

    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override suspend fun transform(source: Bitmap, size: Size): Bitmap {
        val w = maxOf(1, source.width / scaleFactor)
        val h = maxOf(1, source.height / scaleFactor)
        val small = Bitmap.createScaledBitmap(source, w, h, true)
        val out = Bitmap.createScaledBitmap(small, source.width, source.height, true)
        small.recycle()
        return out
    }
}