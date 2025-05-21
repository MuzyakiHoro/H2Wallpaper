// SharedWallpaperRenderer.kt
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

    const val DEFAULT_BACKGROUND_BLUR_RADIUS = 25f

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
        val scrollSensitivityFactor: Float = 1.0f
    )

    private val scrollingBgPaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
    private val p1OverlayBgPaint = Paint()
    private val p1OverlayImagePaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
    private val placeholderTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    private val placeholderBgPaint = Paint().apply {
        color = Color.DKGRAY
    }

    fun drawFrame(
        canvas: Canvas,
        config: WallpaperConfig,
        bitmaps: WallpaperBitmaps
    ) {
        if (config.screenWidth <= 0 || config.screenHeight <= 0) {
            Log.w(TAG, "drawFrame: Screen dimensions are zero, cannot draw.")
            return
        }

        canvas.drawColor(Color.BLACK)

        var p1OverlayAlpha = 255
        val safeNumVirtualPages = config.numVirtualPages.coerceAtLeast(1)

        if (safeNumVirtualPages > 1 && config.p1OverlayFadeTransitionRatio > 0) {
            val singlePageXOffsetRange = 1.0f / safeNumVirtualPages.toFloat()
            val p1FadeOutEndXOffset = singlePageXOffsetRange * config.p1OverlayFadeTransitionRatio.coerceIn(0.01f, 1f)

            if (config.currentXOffset < p1FadeOutEndXOffset) {
                val transitionProgress = config.currentXOffset / p1FadeOutEndXOffset
                p1OverlayAlpha = (255 * (1.0f - transitionProgress.toDouble().pow(2.0))).toInt().coerceIn(0, 255)
            } else {
                p1OverlayAlpha = 0
            }
        } else if (safeNumVirtualPages == 1) {
            p1OverlayAlpha = 255
        } else {
            p1OverlayAlpha = if (config.currentXOffset == 0f) 255 else 0
        }

        val backgroundToDraw = bitmaps.blurredScrollingBackgroundBitmap ?: bitmaps.scrollingBackgroundBitmap
        backgroundToDraw?.let { bgBmp ->
            if (!bgBmp.isRecycled && bgBmp.width > 0 && bgBmp.height > 0) {
                val baseTotalScrollableWidth = (bgBmp.width - config.screenWidth).coerceAtLeast(0)
                val effectiveTotalScrollableWidth = (baseTotalScrollableWidth * config.scrollSensitivityFactor)
                var currentScrollPx = (config.currentXOffset * effectiveTotalScrollableWidth).toInt()
                currentScrollPx = currentScrollPx.coerceIn(0, baseTotalScrollableWidth)

                val bgTopOffset = ((config.screenHeight - bgBmp.height) / 2f)
                canvas.save()
                canvas.translate(-currentScrollPx.toFloat(), bgTopOffset)
                scrollingBgPaint.alpha = 255
                canvas.drawBitmap(bgBmp, 0f, 0f, scrollingBgPaint)
                canvas.restore()
            } else {
                Log.w(TAG, "drawFrame: P2 background bitmap is invalid or recycled.")
            }
        } ?: Log.d(TAG, "drawFrame: No P2 background bitmap available to draw.")

        val topImageActualHeight = (config.screenHeight * config.page1ImageHeightRatio).toInt()
        if (p1OverlayAlpha > 0 && topImageActualHeight > 0) {
            p1OverlayBgPaint.color = config.page1BackgroundColor
            p1OverlayBgPaint.alpha = p1OverlayAlpha
            canvas.drawRect(
                0f,
                topImageActualHeight.toFloat(),
                config.screenWidth.toFloat(),
                config.screenHeight.toFloat(),
                p1OverlayBgPaint
            )

            bitmaps.page1TopCroppedBitmap?.let { topBmp ->
                if (!topBmp.isRecycled && topBmp.width > 0 && topBmp.height > 0) {
                    p1OverlayImagePaint.alpha = p1OverlayAlpha
                    canvas.drawBitmap(topBmp, 0f, 0f, p1OverlayImagePaint)
                } else {
                    Log.w(TAG, "drawFrame: P1 top cropped bitmap is invalid or recycled.")
                }
            } ?: run {
                Log.d(TAG, "drawFrame: No P1 top cropped bitmap available to draw.")
            }
        }
    }

    fun preparePage1TopCroppedBitmap(
        sourceBitmap: Bitmap?,
        targetScreenWidth: Int,
        targetScreenHeight: Int,
        page1ImageHeightRatio: Float,
        normalizedFocusX: Float = 0.5f, // This now represents the desired CENTER X
        normalizedFocusY: Float = 0.5f  // This now represents the desired CENTER Y
    ): Bitmap? {
        if (sourceBitmap == null || sourceBitmap.isRecycled) {
            Log.w(TAG, "preparePage1TopCroppedBitmap: Source bitmap is null or recycled.")
            return null
        }
        if (targetScreenWidth <= 0 || targetScreenHeight <= 0 || page1ImageHeightRatio <= 0f || page1ImageHeightRatio >= 1f) {
            Log.w(TAG, "preparePage1TopCroppedBitmap: Invalid target dimensions or ratio. SW:$targetScreenWidth, SH:$targetScreenHeight, Ratio:$page1ImageHeightRatio")
            return null
        }

        val targetP1ActualHeight = (targetScreenHeight * page1ImageHeightRatio).roundToInt()
        if (targetP1ActualHeight <= 0) {
            Log.w(TAG, "preparePage1TopCroppedBitmap: Calculated targetP1ActualHeight is zero or less.")
            return null
        }

        val bmWidth = sourceBitmap.width
        val bmHeight = sourceBitmap.height
        if (bmWidth <= 0 || bmHeight <= 0) {
            Log.w(TAG, "preparePage1TopCroppedBitmap: Source bitmap has zero or negative dimensions.")
            return null
        }

        var page1TopCropped: Bitmap? = null
        try {
            val targetP1AspectRatio = targetScreenWidth.toFloat() / targetP1ActualHeight.toFloat()
            val sourceBitmapAspectRatio = bmWidth.toFloat() / bmHeight.toFloat()

            // This will be the dimensions of the piece cut from sourceBitmap
            val cropRectWidthFromSource: Int
            val cropRectHeightFromSource: Int

            if (sourceBitmapAspectRatio > targetP1AspectRatio) {
                cropRectHeightFromSource = bmHeight
                cropRectWidthFromSource = (bmHeight * targetP1AspectRatio).roundToInt()
            } else {
                cropRectWidthFromSource = bmWidth
                cropRectHeightFromSource = (bmWidth / targetP1AspectRatio).roundToInt()
            }
            // Ensure crop dimensions do not exceed source bitmap dimensions (though the logic above should prevent this unless aspect ratios are extreme)
            val finalCropWidth = cropRectWidthFromSource.coerceIn(1, bmWidth)
            val finalCropHeight = cropRectHeightFromSource.coerceIn(1, bmHeight)


            // --- START OF MODIFIED LOGIC ---
            // normalizedFocusX/Y now define the desired center of the crop within the sourceBitmap.
            val desiredCenterXInSourcePx = normalizedFocusX.coerceIn(0f, 1f) * bmWidth
            val desiredCenterYInSourcePx = normalizedFocusY.coerceIn(0f, 1f) * bmHeight

            // Calculate the ideal top-left corner (srcX, srcY) for the crop
            // such that the desiredFocusPoint is at the center of the (finalCropWidth x finalCropHeight) rect.
            val srcXIdeal = desiredCenterXInSourcePx - (finalCropWidth / 2.0f)
            val srcYIdeal = desiredCenterYInSourcePx - (finalCropHeight / 2.0f)

            // Coerce srcX and srcY to be within valid bounds of the sourceBitmap
            // The crop rect must not start outside [0, bmWidth - finalCropWidth]
            val srcX = srcXIdeal.roundToInt().coerceIn(0, bmWidth - finalCropWidth)
            val srcY = srcYIdeal.roundToInt().coerceIn(0, bmHeight - finalCropHeight)
            // --- END OF MODIFIED LOGIC ---

            Log.d(TAG, "preparePage1TopCroppedBitmap: Focus($normalizedFocusX, $normalizedFocusY) -> DesiredCenterPx(${desiredCenterXInSourcePx.roundToInt()},${desiredCenterYInSourcePx.roundToInt()})")
            Log.d(TAG, "preparePage1TopCroppedBitmap: CropRectSize(${finalCropWidth}x${finalCropHeight}), IdealSrc(${srcXIdeal.roundToInt()},${srcYIdeal.roundToInt()}) -> FinalSrc($srcX,$srcY) from ${bmWidth}x${bmHeight} for target ${targetScreenWidth}x${targetP1ActualHeight}")


            if (finalCropWidth > 0 && finalCropHeight > 0) { // Redundant check as coerced to 1 above, but good for safety
                // Check if srcX/Y + cropWidth/Height would exceed bmWidth/Height (should be handled by coerceIn for srcX/Y)
                // Example: if srcX = bmWidth - finalCropWidth, then srcX + finalCropWidth = bmWidth. Correct.

                val cropped = Bitmap.createBitmap(sourceBitmap, srcX, srcY, finalCropWidth, finalCropHeight)
                page1TopCropped = Bitmap.createScaledBitmap(cropped, targetScreenWidth, targetP1ActualHeight, true)
                if (cropped != page1TopCropped && !cropped.isRecycled) {
                    cropped.recycle()
                }
                Log.d(TAG, "Page1 top cropped bitmap created: ${page1TopCropped.width}x${page1TopCropped.height}")
            } else {
                Log.w(TAG, "preparePage1TopCroppedBitmap: Calculated finalCropWidth or finalCropHeight is zero or less (should not happen).")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating page1TopCroppedBitmap", e)
            page1TopCropped?.recycle()
            page1TopCropped = null
        }
        return page1TopCropped
    }

    // In SharedWallpaperRenderer.kt
    fun prepareScrollingAndBlurredBitmaps(
        context: Context,
        sourceBitmap: Bitmap?,
        targetScreenWidth: Int,
        targetScreenHeight: Int,
        // numVirtualPages: Int, // 这个参数对于确定背景位图本身的宽度不再需要
        blurRadius: Float
    ): Pair<Bitmap?, Bitmap?> {
        if (sourceBitmap == null || sourceBitmap.isRecycled) {
            Log.w(TAG, "prepareScrollingAndBlurredBitmaps: Source bitmap is null or recycled.")
            return Pair(null, null)
        }
        if (targetScreenWidth <= 0 || targetScreenHeight <= 0) {
            Log.w(TAG, "prepareScrollingAndBlurredBitmaps: Invalid target dimensions.")
            return Pair(null, null)
        }

        var finalScrollingBackground: Bitmap? = null
        var finalBlurredScrollingBackground: Bitmap? = null

        val obW = sourceBitmap.width.toFloat()
        val obH = sourceBitmap.height.toFloat()

        if (obW > 0 && obH > 0) {
            val scaleToFitScreenHeight = targetScreenHeight / obH
            val scaledOriginalWidth = (obW * scaleToFitScreenHeight).roundToInt()

            if (scaledOriginalWidth <= 0) {
                Log.w(TAG, "prepareScrollingAndBlurredBitmaps: Scaled original width is zero or less.")
                return Pair(null, null)
            }

            var tempScaledBitmap: Bitmap? = null
            try {
                // tempScaledBitmap 是源图片按屏幕高度缩放后的结果，宽度为 scaledOriginalWidth
                tempScaledBitmap = Bitmap.createScaledBitmap(sourceBitmap, scaledOriginalWidth, targetScreenHeight, true)

                // 直接使用这个缩放后的位图作为可滚动的背景
                finalScrollingBackground = tempScaledBitmap

                if (blurRadius > 0f && finalScrollingBackground != null && !finalScrollingBackground.isRecycled) {
                    // 确保 blurBitmapUsingRenderScript 返回的是一个新的位图，或者如果它修改了传入的位图，
                    // 我们需要先复制一份 finalScrollingBackground。
                    // 假设 blurBitmapUsingRenderScript 设计良好，会返回新位图或处理好引用。
                    finalBlurredScrollingBackground = blurBitmapUsingRenderScript(context, finalScrollingBackground, blurRadius)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error creating scaled or blurred background", e)
                // 如果 tempScaledBitmap 被赋值给了 finalScrollingBackground，则它的生命周期由外部管理
                // 这里主要回收在异常情况下可能产生的中间位图（如果还有的话）
                // 由于 finalScrollingBackground 可能就是 tempScaledBitmap，所以只回收 finalBlurredScrollingBackground
                finalBlurredScrollingBackground?.recycle()
                finalBlurredScrollingBackground = null
                // 如果 tempScaledBitmap 创建成功但后续出错，finalScrollingBackground 可能是它。
                // 若 finalScrollingBackground 未成功赋值或创建，它自己会是 null。
                // 若 tempScaledBitmap 赋值给了 finalScrollingBackground，则不能在这里回收 tempScaledBitmap。
                // 最好的做法是让 WallpaperBitmaps 类负责回收。
                if (finalScrollingBackground == tempScaledBitmap) {
                    // 如果异常发生在模糊之后，但 tempScaledBitmap 已赋给 finalScrollingBackground
                    // 我们只将 finalScrollingBackground 置空，其回收由调用者（WallpaperBitmaps）处理
                    finalScrollingBackground = null;
                } else {
                    // 如果 tempScaledBitmap 未赋值给 finalScrollingBackground (例如在createScaledBitmap处就失败)
                    tempScaledBitmap?.recycle()
                }
            }
        }
        return Pair(finalScrollingBackground, finalBlurredScrollingBackground)
    }

    fun loadAndProcessInitialBitmaps(
        context: Context,
        imageUri: Uri?,
        targetScreenWidth: Int,
        targetScreenHeight: Int,
        page1ImageHeightRatio: Float,
        normalizedFocusX: Float,
        normalizedFocusY: Float,
        //numVirtualPagesForScrolling: Int,
        blurRadiusForBackground: Float
    ): WallpaperBitmaps {
        if (imageUri == null) {
            Log.w(TAG, "loadAndProcessInitialBitmaps: Image URI is null.")
            return WallpaperBitmaps(null, null, null, null)
        }
        if (targetScreenWidth <= 0 || targetScreenHeight <= 0) {
            Log.w(TAG, "loadAndProcessInitialBitmaps: Invalid target dimensions. SW:$targetScreenWidth, SH:$targetScreenHeight")
            return WallpaperBitmaps(null, null, null, null)
        }

        var sourceSampled: Bitmap? = null
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(imageUri)?.use { BitmapFactory.decodeStream(it, null, options) }

            if (options.outWidth <= 0 || options.outHeight <= 0) {
                Log.e(TAG, "loadAndProcessInitialBitmaps: Image bounds invalid after first decode. URI: $imageUri")
                return WallpaperBitmaps(null, null, null, null)
            }

            options.inSampleSize = calculateInSampleSize(options,
                maxOf(targetScreenWidth, targetScreenWidth),
                maxOf((targetScreenHeight * page1ImageHeightRatio).roundToInt(), targetScreenHeight)
            )
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888

            context.contentResolver.openInputStream(imageUri)?.use { sourceSampled = BitmapFactory.decodeStream(it, null, options) }

            if (sourceSampled == null || sourceSampled!!.isRecycled) {
                Log.e(TAG, "Failed to decode sourceSampledBitmap. URI: $imageUri")
                return WallpaperBitmaps(null, null, null, null)
            }
            Log.d(TAG, "loadAndProcessInitialBitmaps: Source sampled to ${sourceSampled!!.width}x${sourceSampled!!.height} with inSampleSize=${options.inSampleSize}")

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
              //  numVirtualPagesForScrolling,
                blurRadiusForBackground
            )

            return WallpaperBitmaps(sourceSampled, topCropped, scrolling, blurred)

        } catch (e: Exception) {
            Log.e(TAG, "Error in loadAndProcessInitialBitmaps for URI: $imageUri", e)
            sourceSampled?.recycle()
            return WallpaperBitmaps(null, null, null, null)
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (width <= 0 || height <= 0 || reqWidth <= 0 || reqHeight <= 0) return 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
                if (inSampleSize <= 0 || inSampleSize > 1024) {
                    inSampleSize = if (inSampleSize > 1024) 1024 else maxOf(1, inSampleSize / 2)
                    break
                }
            }
        }
        Log.d(TAG, "calculateInSampleSize: original ${width}x${height}, required ${reqWidth}x${reqHeight}, sampleSize $inSampleSize")
        return inSampleSize
    }

    private fun blurBitmapUsingRenderScript(context: Context, bitmap: Bitmap, radius: Float): Bitmap? {
        if (radius <= 0f) return bitmap

        val clampedRadius = radius.coerceIn(0.1f, 25.0f)
        if (bitmap.isRecycled || bitmap.width == 0 || bitmap.height == 0) {
            Log.w(TAG, "blurBitmapUsingRenderScript: Input bitmap is invalid.")
            return null
        }

        var rs: RenderScript? = null
        var outputBitmap: Bitmap? = null
        try {
            outputBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888)
            rs = RenderScript.create(context)
            val input = Allocation.createFromBitmap(rs, bitmap)
            val output = Allocation.createFromBitmap(rs, outputBitmap)
            val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))

            script.setRadius(clampedRadius)
            script.setInput(input)
            script.forEach(output)
            output.copyTo(outputBitmap)

            input.destroy()
            output.destroy()
            script.destroy()
        } catch (e: RSRuntimeException) {
            Log.e(TAG, "RenderScript blur failed (RSRuntimeException). Ensure renderscriptTargetApi and support mode are set in build.gradle. Or device compatibility issue.", e)
            outputBitmap?.recycle()
            outputBitmap = null
        } catch (e: Exception) {
            Log.e(TAG, "RenderScript blur failed (General Exception)", e)
            outputBitmap?.recycle()
            outputBitmap = null
        } finally {
            rs?.destroy()
        }
        return outputBitmap
    }

    fun drawPlaceholder(canvas: Canvas, width: Int, height: Int, text: String) {
        if (width <= 0 || height <= 0) return
        placeholderBgPaint.alpha = 255
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), placeholderBgPaint)

        placeholderTextPaint.alpha = 200
        val textY = height / 2f - ((placeholderTextPaint.descent() + placeholderTextPaint.ascent()) / 2f)
        canvas.drawText(text, width / 2f, textY, placeholderTextPaint)
    }

    private fun drawPlaceholderForP1Overlay(canvas: Canvas, viewWidth: Int, topImageActualHeight: Int, text: String, overallAlpha: Int) {
        if (topImageActualHeight <= 0) return

        placeholderBgPaint.color = Color.GRAY
        placeholderBgPaint.alpha = overallAlpha
        canvas.drawRect(0f, 0f, viewWidth.toFloat(), topImageActualHeight.toFloat(), placeholderBgPaint)

        placeholderTextPaint.alpha = overallAlpha
        val textX = viewWidth / 2f
        val textY = topImageActualHeight / 2f - ((placeholderTextPaint.descent() + placeholderTextPaint.ascent()) / 2f)
        canvas.drawText(text, textX, textY, placeholderTextPaint)
    }
}