package com.example.h2wallpaper

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.renderscript.*
import android.util.Log
import kotlin.math.min
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

object SharedWallpaperRenderer {

    private const val TAG = "SharedRenderer"
    private const val DEBUG_TAG_RENDERER = "SharedRenderer_Debug"

    data class WallpaperBitmaps(
        var sourceSampledBitmap: Bitmap?,
        var page1TopCroppedBitmap: Bitmap?,
        var scrollingBackgroundBitmap: Bitmap?,
        var blurredScrollingBackgroundBitmap: Bitmap?
    ) {
        fun recycleInternals() {
            Log.d(TAG, "Recycling WallpaperBitmaps internals...")
            sourceSampledBitmap?.recycle()
            sourceSampledBitmap = null
            page1TopCroppedBitmap?.recycle()
            page1TopCroppedBitmap = null
            scrollingBackgroundBitmap?.recycle()
            scrollingBackgroundBitmap = null
            blurredScrollingBackgroundBitmap?.recycle()
            blurredScrollingBackgroundBitmap = null
        }

        val isEmpty: Boolean
            get() = sourceSampledBitmap == null && page1TopCroppedBitmap == null &&
                    scrollingBackgroundBitmap == null && blurredScrollingBackgroundBitmap == null
    }

    data class WallpaperConfig(
        val screenWidth: Int,
        val screenHeight: Int,
        val page1BackgroundColor: Int,
        val page1ImageHeightRatio: Float,
        val currentXOffset: Float,
        val numVirtualPages: Int = 3,
        val p1OverlayFadeTransitionRatio: Float = 0.5f,
        val scrollSensitivityFactor: Float = 1.0f,
        val normalizedInitialBgScrollOffset: Float = 0.0f,
        val p2BackgroundFadeInRatio: Float,
        val p1ShadowRadius: Float,
        val p1ShadowDx: Float,
        val p1ShadowDy: Float,
        val p1ShadowColor: Int,
        val p1ImageBottomFadeHeight: Float
    )

    // Existing Paint objects
    private val scrollingBgPaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
    private val p1OverlayBgPaint = Paint() // Used for P1 solid color bar
    private val p1OverlayImagePaint = Paint().apply {
        isAntiAlias = true; isFilterBitmap = true
    } // Base paint for P1 image (no shadow)
    private val placeholderTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    private val placeholderBgPaint = Paint().apply {
        color = Color.DKGRAY
    }

    // --- New member Paint objects ---
    private val rectShadowPaint = Paint().apply { // For P1 shadow
        isAntiAlias = true
        // color will be set to Color.TRANSPARENT in drawPage1Layer before setting shadow
    }
    private val overlayFadePaint = Paint().apply { // For P1 bottom fade
        isAntiAlias = true
        // shader will be set in drawPage1Layer
    }
    // --- End of new Paint objects ---


    private const val MIN_DOWNSCALED_DIMENSION = 16

    fun drawFrame(
        canvas: Canvas,
        config: WallpaperConfig,
        bitmaps: WallpaperBitmaps
    ) {
        if (config.screenWidth <= 0 || config.screenHeight <= 0) {
            Log.w(TAG, "drawFrame: Screen dimensions are zero, cannot draw.")
            return
        }

        canvas.drawColor(config.page1BackgroundColor)
        drawPage2Layer(canvas, config, bitmaps)
        drawPage1Layer(canvas, config, bitmaps)
        Log.d(DEBUG_TAG_RENDERER, "drawFrame: XOffset=${config.currentXOffset}")
    }

    private fun drawPage2Layer(
        canvas: Canvas,
        config: WallpaperConfig,
        bitmaps: WallpaperBitmaps
    ) {
        val safeNumVirtualPages = config.numVirtualPages.coerceAtLeast(1)
        var p2BackgroundAlpha = 255

        if (safeNumVirtualPages > 1) {
            if (config.p2BackgroundFadeInRatio > 0.01f) {
                val p2SinglePageXOffsetRange = 1.0f / safeNumVirtualPages.toFloat()
                val p2FadeEffectMaxXOffset =
                    p2SinglePageXOffsetRange * config.p2BackgroundFadeInRatio.coerceIn(0.01f, 1f)
                if (config.currentXOffset < p2FadeEffectMaxXOffset) {
                    val p2TransitionProgress =
                        (config.currentXOffset / p2FadeEffectMaxXOffset).coerceIn(0f, 1f)
                    p2BackgroundAlpha =
                        (255 * p2TransitionProgress.toDouble().pow(2.0)).toInt().coerceIn(0, 255)
                } else {
                    p2BackgroundAlpha = 255
                }
            } else {
                p2BackgroundAlpha = 255
            }
        } else {
            p2BackgroundAlpha = 255
        }

        val backgroundToDraw =
            bitmaps.blurredScrollingBackgroundBitmap ?: bitmaps.scrollingBackgroundBitmap
        backgroundToDraw?.let { bgBmp ->
            if (!bgBmp.isRecycled && bgBmp.width > 0 && bgBmp.height > 0) {
                val imageActualWidth = bgBmp.width.toFloat()
                val screenActualWidth = config.screenWidth.toFloat()
                val p2ScrollableWidthPxAsFloat =
                    (bgBmp.width - config.screenWidth).coerceAtLeast(0).toFloat()

                val maxInitialPixelOffset = (imageActualWidth - screenActualWidth).coerceAtLeast(0f)
                val safeInitialPixelOffset =
                    (imageActualWidth * config.normalizedInitialBgScrollOffset).coerceIn(
                        0f,
                        maxInitialPixelOffset
                    )
                val totalScrollToAlignRightEdge =
                    (imageActualWidth - screenActualWidth).coerceAtLeast(0f)
                val dynamicScrollableRange =
                    (totalScrollToAlignRightEdge - safeInitialPixelOffset).coerceAtLeast(0f)
                val currentDynamicScroll =
                    config.currentXOffset * (dynamicScrollableRange * config.scrollSensitivityFactor)
                var currentScrollPxFloat = safeInitialPixelOffset + currentDynamicScroll
                currentScrollPxFloat = currentScrollPxFloat.coerceIn(
                    safeInitialPixelOffset,
                    totalScrollToAlignRightEdge
                )
                currentScrollPxFloat = currentScrollPxFloat.coerceAtLeast(0f)

                val bgTopOffset = ((config.screenHeight - bgBmp.height) / 2f)

                canvas.save()
                canvas.translate(-currentScrollPxFloat, bgTopOffset)
                scrollingBgPaint.alpha = p2BackgroundAlpha // Set alpha before drawing
                canvas.drawBitmap(bgBmp, 0f, 0f, scrollingBgPaint)
                canvas.restore()
            } else {
                Log.w(TAG, "drawFrame (P2): Background bitmap is invalid or recycled.")
            }
        } ?: Log.d(TAG, "drawFrame (P2): No background bitmap available.")
        Log.d(DEBUG_TAG_RENDERER, "drawPage2Layer: P2 BG Alpha=$p2BackgroundAlpha")
    }

    private fun drawPage1Layer(
        canvas: Canvas,
        config: WallpaperConfig,
        bitmaps: WallpaperBitmaps
    ) {
        val safeNumVirtualPages = config.numVirtualPages.coerceAtLeast(1)
        var topImageActualHeight = (config.screenHeight * config.page1ImageHeightRatio).toInt()
        var p1OverallAlpha = 255

        if (safeNumVirtualPages > 1) {
            if (config.p1OverlayFadeTransitionRatio > 0.01f) {
                val p1SinglePageXOffsetRange = 1.0f / safeNumVirtualPages.toFloat()
                val p1FadeEffectMaxXOffset =
                    p1SinglePageXOffsetRange * config.p1OverlayFadeTransitionRatio.coerceIn(
                        0.01f,
                        1f
                    )
                if (config.currentXOffset < p1FadeEffectMaxXOffset) {
                    val p1TransitionProgress =
                        (config.currentXOffset / p1FadeEffectMaxXOffset).coerceIn(0f, 1f)
                    p1OverallAlpha =
                        (255 * (1.0f - p1TransitionProgress.toDouble().pow(2.0))).toInt()
                            .coerceIn(0, 255)
                } else {
                    p1OverallAlpha = 0
                }
            } else {
                p1OverallAlpha = if (config.currentXOffset == 0f) 255 else 0
            }
        } else {
            p1OverallAlpha = 255
        }

        // Log statements from previous version (can be kept for debugging)
        // Log.d(TAG, "P1 Check: p1OverallAlpha=$p1OverallAlpha, topImageActualHeight=$topImageActualHeight, ratio=${config.page1ImageHeightRatio}")
        // ...

        if (p1OverallAlpha > 0 && topImageActualHeight > 0) {
            // Log.d(TAG, "Drawing P1 content block.") // Already present
            canvas.saveLayerAlpha(
                0f,
                0f,
                config.screenWidth.toFloat(),
                config.screenHeight.toFloat(),
                p1OverallAlpha
            )

            p1OverlayBgPaint.shader = null // Ensure no leftover shader
            p1OverlayBgPaint.color = config.page1BackgroundColor
            p1OverlayBgPaint.alpha = 255 // Solid color bar itself is opaque
            canvas.drawRect(
                0f,
                topImageActualHeight.toFloat(),
                config.screenWidth.toFloat(),
                config.screenHeight.toFloat(),
                p1OverlayBgPaint
            )

            bitmaps.page1TopCroppedBitmap?.let { topBmp ->
                if (!topBmp.isRecycled && topBmp.width > 0 && topBmp.height > 0) {
                    val imageWidth = topBmp.width.toFloat()
                    val imageHeight = topBmp.height.toFloat()

                    // 2a. 如果需要，先绘制阴影 (使用成员变量 rectShadowPaint)
                    if (config.p1ShadowRadius > 0.01f && Color.alpha(config.p1ShadowColor) > 0) {
                        rectShadowPaint.apply {
                            color = Color.TRANSPARENT // Rect itself is transparent
                            setShadowLayer(
                                config.p1ShadowRadius,
                                config.p1ShadowDx,
                                config.p1ShadowDy,
                                config.p1ShadowColor
                            )
                        }
                        canvas.drawRect(0f, 0f, imageWidth, imageHeight, rectShadowPaint)
                    }

                    // 2b. 绘制 P1 图片本身 (使用成员变量 p1OverlayImagePaint)
                    // Reset any shadow layer from p1OverlayImagePaint if it was ever set, though it shouldn't be by design.
                    p1OverlayImagePaint.clearShadowLayer()
                    canvas.drawBitmap(topBmp, 0f, 0f, p1OverlayImagePaint)

                    // 2c. 底部融入效果 (使用成员变量 overlayFadePaint)
                    val fadeActualHeight = config.p1ImageBottomFadeHeight.coerceIn(0f, imageHeight)
                    if (fadeActualHeight > 0.1f) {
                        val fadeStartY = imageHeight
                        val fadeEndY = imageHeight - fadeActualHeight
                        overlayFadePaint.shader = LinearGradient( // Set shader dynamically
                            0f, fadeStartY, 0f, fadeEndY,
                            config.page1BackgroundColor, Color.TRANSPARENT,
                            Shader.TileMode.CLAMP
                        )
                        canvas.drawRect(0f, fadeEndY, imageWidth, imageHeight, overlayFadePaint)
                    } else {
                        overlayFadePaint.shader = null // Clear shader if not used
                    }
                } else {
                    Log.w(TAG, "drawFrame (P1): Top cropped bitmap is invalid or recycled.")
                }
            } ?: Log.d(TAG, "drawFrame (P1): No top cropped bitmap available.")
            canvas.restore()
        }
        Log.d(DEBUG_TAG_RENDERER, "drawPage1Layer: P1 Overall Alpha=$p1OverallAlpha")
    }

    // --- Bitmap Preparation Methods (preparePage1TopCroppedBitmap, etc.) remain unchanged ---
    // ... (Copy the rest of the SharedWallpaperRenderer.kt file from your last correct version here)
    fun preparePage1TopCroppedBitmap(
        sourceBitmap: Bitmap?, targetScreenWidth: Int, targetScreenHeight: Int,
        page1ImageHeightRatio: Float, normalizedFocusX: Float = 0.5f, normalizedFocusY: Float = 0.5f
    ): Bitmap? {
        if (sourceBitmap == null || sourceBitmap.isRecycled) return null
        if (targetScreenWidth <= 0 || targetScreenHeight <= 0 || page1ImageHeightRatio <= 0f || page1ImageHeightRatio >= 1f) return null
        val targetP1ActualHeight = (targetScreenHeight * page1ImageHeightRatio).roundToInt()
        if (targetP1ActualHeight <= 0) return null
        val bmWidth = sourceBitmap.width
        val bmHeight = sourceBitmap.height
        if (bmWidth <= 0 || bmHeight <= 0) return null

        var page1TopCropped: Bitmap? = null
        try {
            val targetP1AspectRatio = targetScreenWidth.toFloat() / targetP1ActualHeight.toFloat()
            val sourceBitmapAspectRatio = bmWidth.toFloat() / bmHeight.toFloat()
            val cropRectWidthFromSource: Int
            val cropRectHeightFromSource: Int
            if (sourceBitmapAspectRatio > targetP1AspectRatio) {
                cropRectHeightFromSource = bmHeight
                cropRectWidthFromSource = (bmHeight * targetP1AspectRatio).roundToInt()
            } else {
                cropRectWidthFromSource = bmWidth
                cropRectHeightFromSource = (bmWidth / targetP1AspectRatio).roundToInt()
            }
            val finalCropWidth = cropRectWidthFromSource.coerceIn(1, bmWidth)
            val finalCropHeight = cropRectHeightFromSource.coerceIn(1, bmHeight)
            val desiredCenterXInSourcePx = normalizedFocusX.coerceIn(0f, 1f) * bmWidth
            val desiredCenterYInSourcePx = normalizedFocusY.coerceIn(0f, 1f) * bmHeight
            val srcXIdeal = desiredCenterXInSourcePx - (finalCropWidth / 2.0f)
            val srcYIdeal = desiredCenterYInSourcePx - (finalCropHeight / 2.0f)
            val srcX = srcXIdeal.roundToInt().coerceIn(0, bmWidth - finalCropWidth)
            val srcY = srcYIdeal.roundToInt().coerceIn(0, bmHeight - finalCropHeight)

            if (finalCropWidth > 0 && finalCropHeight > 0) {
                val cropped =
                    Bitmap.createBitmap(sourceBitmap, srcX, srcY, finalCropWidth, finalCropHeight)
                page1TopCropped = Bitmap.createScaledBitmap(
                    cropped,
                    targetScreenWidth,
                    targetP1ActualHeight,
                    true
                )
                if (cropped != page1TopCropped && !cropped.isRecycled) cropped.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating page1TopCroppedBitmap", e)
            page1TopCropped?.recycle()
            page1TopCropped = null
        }
        return page1TopCropped
    }

    fun prepareScrollingAndBlurredBitmaps(
        context: Context, sourceBitmap: Bitmap?, targetScreenWidth: Int, targetScreenHeight: Int,
        blurRadius: Float, blurDownscaleFactor: Float, blurIterations: Int
    ): Pair<Bitmap?, Bitmap?> {
        if (sourceBitmap == null || sourceBitmap.isRecycled) return Pair(null, null)
        if (targetScreenWidth <= 0 || targetScreenHeight <= 0) return Pair(null, null)
        var finalScrollingBackground: Bitmap? = null
        var finalBlurredScrollingBackground: Bitmap? = null
        val originalBitmapWidth = sourceBitmap.width.toFloat()
        val originalBitmapHeight = sourceBitmap.height.toFloat()
        if (originalBitmapWidth <= 0 || originalBitmapHeight <= 0) return Pair(null, null)
        val scaleToFitScreenHeight = targetScreenHeight / originalBitmapHeight
        val scaledWidthForScrollingBg = (originalBitmapWidth * scaleToFitScreenHeight).roundToInt()
        if (scaledWidthForScrollingBg <= 0) return Pair(null, null)

        var baseScrollingBitmap: Bitmap? = null
        var downscaledForBlur: Bitmap? = null
        var blurredDownscaled: Bitmap? = null
        var upscaledBlurredBitmap: Bitmap? = null
        try {
            baseScrollingBitmap = Bitmap.createScaledBitmap(
                sourceBitmap,
                scaledWidthForScrollingBg,
                targetScreenHeight,
                true
            )
            finalScrollingBackground = baseScrollingBitmap
            if (blurRadius > 0.01f && baseScrollingBitmap != null && !baseScrollingBitmap.isRecycled) {
                val sourceForActualBlur = baseScrollingBitmap
                val actualDownscaleFactor = blurDownscaleFactor.coerceIn(0.05f, 1.0f)
                val downscaledWidth =
                    (sourceForActualBlur.width * actualDownscaleFactor).roundToInt()
                        .coerceAtLeast(MIN_DOWNSCALED_DIMENSION)
                val downscaledHeight =
                    (sourceForActualBlur.height * actualDownscaleFactor).roundToInt()
                        .coerceAtLeast(MIN_DOWNSCALED_DIMENSION)
                if (actualDownscaleFactor < 0.99f && (downscaledWidth < sourceForActualBlur.width || downscaledHeight < sourceForActualBlur.height)) {
                    downscaledForBlur = Bitmap.createScaledBitmap(
                        sourceForActualBlur,
                        downscaledWidth,
                        downscaledHeight,
                        true
                    )
                    blurredDownscaled = blurBitmapUsingRenderScript(
                        context,
                        downscaledForBlur,
                        blurRadius.coerceIn(0.1f, 25.0f),
                        blurIterations
                    )
                    if (blurredDownscaled != null) {
                        upscaledBlurredBitmap = Bitmap.createScaledBitmap(
                            blurredDownscaled,
                            sourceForActualBlur.width,
                            sourceForActualBlur.height,
                            true
                        )
                        finalBlurredScrollingBackground = upscaledBlurredBitmap
                    } else {
                        Log.w(
                            TAG,
                            "Enhanced blur: Blurring on downscaled image failed. Falling back."
                        )
                        finalBlurredScrollingBackground = blurBitmapUsingRenderScript(
                            context,
                            sourceForActualBlur,
                            blurRadius,
                            blurIterations
                        )
                    }
                } else {
                    Log.d(
                        TAG,
                        "Applying standard blur (no effective downscale). Iterations: $blurIterations"
                    )
                    finalBlurredScrollingBackground = blurBitmapUsingRenderScript(
                        context,
                        sourceForActualBlur,
                        blurRadius,
                        blurIterations
                    )
                }
            } else if (baseScrollingBitmap != null && !baseScrollingBitmap.isRecycled) {
                Log.d(TAG, "No blur applied as blurRadius ($blurRadius) is too small or zero.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during prepareScrollingAndBlurredBitmaps", e)
            upscaledBlurredBitmap?.recycle()
            finalBlurredScrollingBackground = null
        } finally {
            if (downscaledForBlur != blurredDownscaled) downscaledForBlur?.recycle()
            blurredDownscaled?.recycle()
        }
        return Pair(finalScrollingBackground, finalBlurredScrollingBackground)
    }

    fun loadAndProcessInitialBitmaps(
        context: Context, imageUri: Uri?, targetScreenWidth: Int, targetScreenHeight: Int,
        page1ImageHeightRatio: Float, normalizedFocusX: Float, normalizedFocusY: Float,
        blurRadiusForBackground: Float, blurDownscaleFactor: Float, blurIterations: Int
    ): WallpaperBitmaps {
        if (imageUri == null || targetScreenWidth <= 0 || targetScreenHeight <= 0) return WallpaperBitmaps(
            null,
            null,
            null,
            null
        )
        var sourceSampled: Bitmap? = null
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(imageUri)
                ?.use { BitmapFactory.decodeStream(it, null, options) }
            if (options.outWidth <= 0 || options.outHeight <= 0) return WallpaperBitmaps(
                null,
                null,
                null,
                null
            )

            options.inSampleSize =
                calculateInSampleSize(options, targetScreenWidth * 2, targetScreenHeight * 2)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            context.contentResolver.openInputStream(imageUri)
                ?.use { sourceSampled = BitmapFactory.decodeStream(it, null, options) }
            if (sourceSampled == null || sourceSampled!!.isRecycled) return WallpaperBitmaps(
                null,
                null,
                null,
                null
            )

            val topCropped = preparePage1TopCroppedBitmap(
                sourceSampled,
                targetScreenWidth,
                targetScreenHeight,
                page1ImageHeightRatio,
                normalizedFocusX,
                normalizedFocusY
            )
            val (scrolling, blurred) = prepareScrollingAndBlurredBitmaps(
                context,
                sourceSampled,
                targetScreenWidth,
                targetScreenHeight,
                blurRadiusForBackground,
                blurDownscaleFactor,
                blurIterations
            )
            return WallpaperBitmaps(sourceSampled, topCropped, scrolling, blurred)
        } catch (e: Exception) {
            Log.e(TAG, "Error in loadAndProcessInitialBitmaps for URI: $imageUri", e)
            sourceSampled?.recycle()
            return WallpaperBitmaps(null, null, null, null)
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
                if (inSampleSize > 8) break
            }
        }
        return inSampleSize
    }

    private fun blurBitmapUsingRenderScript(
        context: Context, bitmap: Bitmap, radius: Float, iterations: Int = 1
    ): Bitmap? {
        if (bitmap.isRecycled || bitmap.width == 0 || bitmap.height == 0) return null
        val clampedRadius = radius.coerceIn(0.1f, 25.0f)
        val actualIterations = iterations.coerceAtLeast(1)
        if (clampedRadius < 0.1f && actualIterations == 1) {
            return try {
                bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
            } catch (e: Exception) {
                null
            }
        }
        var rs: RenderScript? = null
        var script: ScriptIntrinsicBlur? = null
        var currentBitmap: Bitmap = bitmap
        var outBitmap: Bitmap? = null

        try {
            rs = RenderScript.create(context)
            script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
            script.setRadius(clampedRadius)

            for (i in 0 until actualIterations) {
                outBitmap = Bitmap.createBitmap(
                    currentBitmap.width,
                    currentBitmap.height,
                    currentBitmap.config ?: Bitmap.Config.ARGB_8888
                )

                val ain = Allocation.createFromBitmap(rs, currentBitmap)
                val aout = Allocation.createFromBitmap(rs, outBitmap)

                script.setInput(ain)
                script.forEach(aout)
                aout.copyTo(outBitmap)

                ain.destroy()
                aout.destroy()

                if (currentBitmap != bitmap && currentBitmap != outBitmap) {
                    currentBitmap.recycle()
                }
                currentBitmap = outBitmap!!
            }
            return currentBitmap
        } catch (e: Exception) {
            Log.e(TAG, "RenderScript blur failed", e)
            outBitmap?.recycle()
            if (currentBitmap != bitmap && currentBitmap != outBitmap) {
                currentBitmap.recycle()
            }
            return null
        } finally {
            script?.destroy()
            rs?.destroy()
        }
    }

    fun drawPlaceholder(canvas: Canvas, width: Int, height: Int, text: String) {
        if (width <= 0 || height <= 0) return
        placeholderBgPaint.alpha = 255
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), placeholderBgPaint)
        placeholderTextPaint.alpha = 200
        val textY =
            height / 2f - ((placeholderTextPaint.descent() + placeholderTextPaint.ascent()) / 2f)
        canvas.drawText(text, width / 2f, textY, placeholderTextPaint)
    }

}