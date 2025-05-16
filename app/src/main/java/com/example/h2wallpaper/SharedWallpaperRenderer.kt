package com.example.h2wallpaper

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.renderscript.*
import android.util.Log
import kotlin.math.min
import kotlin.math.roundToInt

object SharedWallpaperRenderer {

    private const val TAG = "SharedRenderer"
    private const val DEBUG_TAG_RENDERER = "SharedRenderer_Debug" // 专门用于此文件的Debug TAG

    data class WallpaperBitmaps(
        var sourceSampledBitmap: Bitmap?,
        var page1TopCroppedBitmap: Bitmap?,
        var scrollingBackgroundBitmap: Bitmap?,
        var blurredScrollingBackgroundBitmap: Bitmap?
    ) {
        fun recycleInternals() {
            sourceSampledBitmap?.recycle(); sourceSampledBitmap = null
            page1TopCroppedBitmap?.recycle(); page1TopCroppedBitmap = null
            scrollingBackgroundBitmap?.recycle(); scrollingBackgroundBitmap = null
            blurredScrollingBackgroundBitmap?.recycle(); blurredScrollingBackgroundBitmap = null
        }
    }

    data class WallpaperConfig(
        val screenWidth: Int,
        val screenHeight: Int,
        val page1BackgroundColor: Int,
        val page1ImageHeightRatio: Float,
        val currentXOffset: Float,
        val numVirtualPages: Int = 3,
        val p1OverlayFadeTransitionRatio: Float = 0.2f,
        val scrollSensitivityFactor: Float = 1.0f // 新增：滚动灵敏度因子
    )

    // Paint 对象
    private val scrollingBgPaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
    private val p1OverlayBgPaint = Paint()
    private val p1OverlayImagePaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
    private val placeholderTextPaint = Paint().apply {
        color = Color.WHITE; textSize = 40f; textAlign = Paint.Align.CENTER; isAntiAlias = true
    }
    private val placeholderBgPaint = Paint()


    fun drawFrame(
        canvas: Canvas,
        config: WallpaperConfig,
        bitmaps: WallpaperBitmaps
    ) {
        Log.d(DEBUG_TAG_RENDERER, "drawFrame START. Config: offset=${config.currentXOffset}, pages=${config.numVirtualPages}, screenW=${config.screenWidth}, sensitivity=${config.scrollSensitivityFactor}")
        Log.d(DEBUG_TAG_RENDERER, "Bitmaps: sourceNull=${bitmaps.sourceSampledBitmap==null}, scrollBgNull=${bitmaps.scrollingBackgroundBitmap==null}, scrollBgW=${bitmaps.scrollingBackgroundBitmap?.width}, topCropNull=${bitmaps.page1TopCroppedBitmap==null}")

        if (config.screenWidth == 0 || config.screenHeight == 0) {
            Log.w(TAG, "Screen dimensions are zero, cannot draw.")
            return
        }

        canvas.drawColor(Color.BLACK) // 清底

        val backgroundToDraw = bitmaps.blurredScrollingBackgroundBitmap ?: bitmaps.scrollingBackgroundBitmap

        backgroundToDraw?.let { bgBmp ->
            if (!bgBmp.isRecycled && bgBmp.width > 0) {
                val baseTotalScrollableWidth = (bgBmp.width - config.screenWidth).coerceAtLeast(0)
                // 应用灵敏度因子来调整有效的可滚动宽度
                val effectiveTotalScrollableWidth = (baseTotalScrollableWidth * config.scrollSensitivityFactor)

                var currentScrollPx = (config.currentXOffset * effectiveTotalScrollableWidth).toInt()
                // 关键：确保 currentScrollPx 不会超出原始位图的可滚动范围 (baseTotalScrollableWidth)
                currentScrollPx = currentScrollPx.coerceIn(0, baseTotalScrollableWidth)

                Log.d(DEBUG_TAG_RENDERER, "Scroll Calculation: baseTotalScrollW=$baseTotalScrollableWidth, effectiveTotalScrollW=${effectiveTotalScrollableWidth.toInt()}, currentScrollPx=$currentScrollPx (bgW=${bgBmp.width}, screenW=${config.screenWidth}, xOffset=${config.currentXOffset}, factor=${config.scrollSensitivityFactor})")

                val bgTopOffset = ((config.screenHeight - bgBmp.height) / 2f)
                canvas.save()
                canvas.translate(-currentScrollPx.toFloat(), bgTopOffset)
                scrollingBgPaint.alpha = 255 // 重置 alpha
                canvas.drawBitmap(bgBmp, 0f, 0f, scrollingBgPaint)
                canvas.restore()
            } else {
                Log.w(DEBUG_TAG_RENDERER, "Background bitmap is null, recycled, or has zero width. Not drawing background scroll.")
                // 背景缺失，画布保持黑色底
            }
        } ?: run {
            Log.w(DEBUG_TAG_RENDERER, "No background bitmap available (scrolling or blurred). Canvas remains black for background.")
            // 无背景图，画布保持黑色底
        }

        // --- 绘制第一屏叠加层 ---
        var overlayAlpha = 0
        val topImageActualHeight = (config.screenHeight * config.page1ImageHeightRatio).toInt()

        if (config.numVirtualPages <= 0) { // 安全检查
            overlayAlpha = if (config.currentXOffset < 0.01f) 255 else 0
        } else if (config.numVirtualPages == 1) {
            overlayAlpha = 255 // 单页时，叠加层始终显示 (如果设计如此)
        } else {
            val singlePageXOffsetRange = 1.0f / config.numVirtualPages.toFloat() // 确保是浮点数除法
            val fadeOutEndXOffset = singlePageXOffsetRange * config.p1OverlayFadeTransitionRatio.coerceIn(0.01f, 1f)

            if (config.currentXOffset < fadeOutEndXOffset && fadeOutEndXOffset > 0) { // 避免除以0
                overlayAlpha = ((1.0f - (config.currentXOffset / fadeOutEndXOffset)) * 255).toInt().coerceIn(0, 255)
            } else {
                overlayAlpha = 0
            }
            // 确保在第一页起始位置时完全不透明 (如果 fadeOutEndXOffset 大于0)
            if (config.currentXOffset == 0f && fadeOutEndXOffset > 0) overlayAlpha = 255
        }
        Log.d(DEBUG_TAG_RENDERER, "OverlayAlpha: $overlayAlpha (currentXOffset=${config.currentXOffset}, p1FadeRatio=${config.p1OverlayFadeTransitionRatio})")


        if (overlayAlpha > 0 && topImageActualHeight > 0) {
            // 绘制背景色部分
            p1OverlayBgPaint.color = config.page1BackgroundColor
            p1OverlayBgPaint.alpha = overlayAlpha
            canvas.drawRect(
                0f,
                topImageActualHeight.toFloat(),
                config.screenWidth.toFloat(),
                config.screenHeight.toFloat(),
                p1OverlayBgPaint
            )

            // 绘制顶部图片部分
            bitmaps.page1TopCroppedBitmap?.let { topBmp ->
                if (!topBmp.isRecycled) {
                    p1OverlayImagePaint.alpha = overlayAlpha
                    canvas.drawBitmap(topBmp, 0f, 0f, p1OverlayImagePaint)
                } else {
                    Log.w(DEBUG_TAG_RENDERER, "page1TopCroppedBitmap is recycled!")
                    drawPlaceholderForP1Overlay(canvas, config.screenWidth, topImageActualHeight, "顶图已回收", overlayAlpha)
                }
            } ?: run {
                // 如果顶图不存在，但源图存在（意味着可能正在生成顶图），则绘制占位符
                if (bitmaps.sourceSampledBitmap != null) {
                    drawPlaceholderForP1Overlay(canvas, config.screenWidth, topImageActualHeight, "顶图加载中...", overlayAlpha)
                }
            }
        }
        // 重置Paint对象的alpha，以防影响下一次绘制 (虽然在这个单次调用中不是问题，但好习惯)
        scrollingBgPaint.alpha = 255
        p1OverlayBgPaint.alpha = 255
        p1OverlayImagePaint.alpha = 255
        Log.d(DEBUG_TAG_RENDERER, "drawFrame END.")
    }


    fun preparePage1TopCroppedBitmap(
        sourceBitmap: Bitmap?,
        targetScreenWidth: Int,
        targetScreenHeight: Int,
        page1ImageHeightRatio: Float
    ): Bitmap? {
        if (sourceBitmap == null || sourceBitmap.isRecycled || targetScreenWidth <= 0 || targetScreenHeight <= 0 || page1ImageHeightRatio <= 0f) {
            Log.w(TAG, "preparePage1TopCroppedBitmap: Invalid input. SourceNull: ${sourceBitmap==null}, Recycled: ${sourceBitmap?.isRecycled}, SW:$targetScreenWidth, SH:$targetScreenHeight, Ratio:$page1ImageHeightRatio")
            return null
        }
        val targetTopHeight = (targetScreenHeight * page1ImageHeightRatio).toInt()
        if (targetTopHeight <= 0) {
            Log.w(TAG, "preparePage1TopCroppedBitmap: Calculated targetTopHeight is <=0.")
            return null
        }

        var page1TopCropped: Bitmap? = null
        try {
            val bmWidth = sourceBitmap.width
            val bmHeight = sourceBitmap.height
            var srcX = 0; var srcY = 0
            var cropWidth = bmWidth; var cropHeight = bmHeight
            val bitmapAspectRatio = bmWidth.toFloat() / bmHeight.toFloat()
            val targetAspectRatio = targetScreenWidth.toFloat() / targetTopHeight.toFloat()

            if (bitmapAspectRatio > targetAspectRatio) {
                cropWidth = (bmHeight * targetAspectRatio).toInt()
                srcX = (bmWidth - cropWidth) / 2
            } else {
                cropHeight = (bmWidth / targetAspectRatio).toInt()
                srcY = (bmHeight - cropHeight) / 2
            }
            cropWidth = min(cropWidth, bmWidth - srcX).coerceAtLeast(1)
            cropHeight = min(cropHeight, bmHeight - srcY).coerceAtLeast(1)
            srcX = srcX.coerceAtLeast(0); srcY = srcY.coerceAtLeast(0)
            if (srcX + cropWidth > bmWidth) cropWidth = bmWidth - srcX
            if (srcY + cropHeight > bmHeight) cropHeight = bmHeight - srcY

            if (cropWidth > 0 && cropHeight > 0) {
                val cropped = Bitmap.createBitmap(sourceBitmap, srcX, srcY, cropWidth, cropHeight)
                page1TopCropped = Bitmap.createScaledBitmap(cropped, targetScreenWidth, targetTopHeight, true)
                if (cropped != page1TopCropped && !cropped.isRecycled) cropped.recycle()
                Log.d(TAG, "preparePage1TopCroppedBitmap: Success. Output: ${page1TopCropped.width}x${page1TopCropped.height}")
            } else {
                Log.w(TAG, "preparePage1TopCroppedBitmap: Calculated cropWidth or cropHeight is zero.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating page1TopCroppedBitmap from source", e)
            page1TopCropped?.recycle(); page1TopCropped = null
        }
        return page1TopCropped
    }

    fun prepareScrollingAndBlurredBitmaps(
        context: Context,
        sourceBitmap: Bitmap?,
        targetScreenWidth: Int,
        targetScreenHeight: Int,
        numVirtualPages: Int,
        blurRadius: Float
    ): Pair<Bitmap?, Bitmap?> {
        if (sourceBitmap == null || sourceBitmap.isRecycled || targetScreenWidth <= 0 || targetScreenHeight <= 0 || numVirtualPages <= 0) {
            Log.w(TAG, "prepareScrollingAndBlurredBitmaps: Invalid input.")
            return Pair(null, null)
        }

        var scrollingBackground: Bitmap? = null
        var blurredScrollingBackground: Bitmap? = null
        val bgTargetHeight = targetScreenHeight
        val obW = sourceBitmap.width.toFloat()
        val obH = sourceBitmap.height.toFloat()

        if (obW > 0 && obH > 0) {
            val scaleToFitScreenHeight = bgTargetHeight / obH
            val scaledOriginalWidth = (obW * scaleToFitScreenHeight).toInt()
            if (scaledOriginalWidth > 0) {
                val tempScaledBitmap = Bitmap.createScaledBitmap(sourceBitmap, scaledOriginalWidth, bgTargetHeight, true)
                val actualNumVirtualPages = numVirtualPages.coerceAtLeast(1)
                // 这里的 bgFinalTargetWidth 是基于 numVirtualPages，不受 scrollSensitivityFactor 直接影响
                val bgFinalTargetWidth = targetScreenWidth * actualNumVirtualPages

                try {
                    scrollingBackground = Bitmap.createBitmap(bgFinalTargetWidth, bgTargetHeight, Bitmap.Config.ARGB_8888)
                    val canvasForScrollingBg = Canvas(scrollingBackground!!)
                    var currentX = 0
                    if (scaledOriginalWidth >= bgFinalTargetWidth) {
                        val offsetX = (scaledOriginalWidth - bgFinalTargetWidth) / 2
                        canvasForScrollingBg.drawBitmap(tempScaledBitmap, Rect(offsetX, 0, offsetX + bgFinalTargetWidth, bgTargetHeight), Rect(0, 0, bgFinalTargetWidth, bgTargetHeight), null)
                    } else {
                        while (currentX < bgFinalTargetWidth) {
                            canvasForScrollingBg.drawBitmap(tempScaledBitmap, currentX.toFloat(), 0f, null)
                            currentX += tempScaledBitmap.width
                            if (tempScaledBitmap.width <= 0) break
                        }
                    }
                    Log.d(TAG, "prepareScrollingAndBlurredBitmaps: Scrolling background created, W: ${scrollingBackground.width}")

                    if (blurRadius > 0f && scrollingBackground != null && !scrollingBackground.isRecycled) {
                        blurredScrollingBackground = blurBitmapUsingRenderScript(context, scrollingBackground, blurRadius)
                        Log.d(TAG, "prepareScrollingAndBlurredBitmaps: Blurred background created: ${blurredScrollingBackground != null}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating scrolling or blurred background", e)
                    scrollingBackground?.recycle(); scrollingBackground = null
                    blurredScrollingBackground?.recycle(); blurredScrollingBackground = null
                } finally {
                    if (tempScaledBitmap != sourceBitmap && !tempScaledBitmap.isRecycled) tempScaledBitmap.recycle()
                }
            } else {
                Log.w(TAG, "prepareScrollingAndBlurredBitmaps: Scaled original width is zero.")
            }
        }
        return Pair(scrollingBackground, blurredScrollingBackground)
    }

    fun loadAndProcessInitialBitmaps(
        context: Context, imageUri: Uri?, targetScreenWidth: Int, targetScreenHeight: Int,
        page1ImageHeightRatio: Float, numVirtualPagesForScrolling: Int, blurRadiusForBackground: Float
    ): WallpaperBitmaps {
        if (imageUri == null || targetScreenWidth <= 0 || targetScreenHeight <= 0) {
            return WallpaperBitmaps(null, null, null, null)
        }
        var sourceSampled: Bitmap? = null
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(imageUri)?.use { BitmapFactory.decodeStream(it, null, options) }

            options.inSampleSize = calculateInSampleSize(options, targetScreenWidth * numVirtualPagesForScrolling.coerceAtLeast(1), targetScreenHeight)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            context.contentResolver.openInputStream(imageUri)?.use { sourceSampled = BitmapFactory.decodeStream(it, null, options) }

            if (sourceSampled == null || sourceSampled!!.isRecycled) {
                Log.e(TAG, "Failed to decode sourceSampledBitmap.")
                return WallpaperBitmaps(null, null, null, null)
            }
            Log.d(TAG, "Source sampled bitmap: ${sourceSampled!!.width}x${sourceSampled!!.height}")

            val topCropped = preparePage1TopCroppedBitmap(sourceSampled, targetScreenWidth, targetScreenHeight, page1ImageHeightRatio)
            // 滚动背景的生成不受 scrollSensitivityFactor 影响，它只影响绘制时的平移量
            val (scrolling, blurred) = prepareScrollingAndBlurredBitmaps(context, sourceSampled, targetScreenWidth, targetScreenHeight, numVirtualPagesForScrolling, blurRadiusForBackground)

            return WallpaperBitmaps(sourceSampled, topCropped, scrolling, blurred)
        } catch (e: Exception) {
            Log.e(TAG, "Error in loadAndProcessInitialBitmaps", e)
            sourceSampled?.recycle(); return WallpaperBitmaps(null, null, null, null)
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (width == 0 || height == 0 || reqWidth <= 0 || reqHeight <= 0) return 1
        if (height > reqHeight || width > reqWidth) {
            val halfH = height / 2; val halfW = width / 2
            while ((halfH / inSampleSize) >= reqHeight && (halfW / inSampleSize) >= reqWidth) {
                inSampleSize *= 2; if (inSampleSize <= 0 || inSampleSize > 1024) { inSampleSize = 1024; break; }
            }
        }
        Log.d(TAG, "Calculated inSampleSize: $inSampleSize for reqW:$reqWidth, reqH:$reqHeight (origW:${options.outWidth}, origH:${options.outHeight})")
        return inSampleSize
    }

    private fun blurBitmapUsingRenderScript(context: Context, bitmap: Bitmap, radius: Float): Bitmap? {
        if (radius < 1f || radius > 25f) return null
        var rs: RenderScript? = null
        var outputBitmap: Bitmap? = null
        try {
            outputBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888)
            rs = RenderScript.create(context)
            val input = Allocation.createFromBitmap(rs, bitmap)
            val output = Allocation.createFromBitmap(rs, outputBitmap)
            val script = ScriptIntrinsicBlur.create(rs, input.element)
            script.setRadius(radius); script.setInput(input); script.forEach(output)
            output.copyTo(outputBitmap)
            input.destroy(); output.destroy(); script.destroy()
        } catch (e: Exception) { Log.e(TAG, "RenderScript blur failed", e); outputBitmap?.recycle(); outputBitmap = null
        } finally { rs?.destroy() }
        return outputBitmap
    }

    fun drawPlaceholder(canvas: Canvas, width: Int, height: Int, text: String) {
        placeholderBgPaint.color = Color.DKGRAY; placeholderBgPaint.alpha = 255
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), placeholderBgPaint)
        placeholderTextPaint.alpha = 200
        val textY = height / 2f - ((placeholderTextPaint.descent() + placeholderTextPaint.ascent()) / 2f)
        canvas.drawText(text, width / 2f, textY, placeholderTextPaint)
    }

    private fun drawPlaceholderForP1Overlay(canvas: Canvas, viewWidth: Int, topImageActualHeight: Int, text: String, overallAlpha: Int) {
        if (topImageActualHeight <= 0) return
        placeholderBgPaint.color = Color.GRAY; placeholderBgPaint.alpha = overallAlpha
        canvas.drawRect(0f, 0f, viewWidth.toFloat(), topImageActualHeight.toFloat(), placeholderBgPaint)
        placeholderTextPaint.alpha = overallAlpha
        val textX = viewWidth / 2f
        val textY = topImageActualHeight / 2f - ((placeholderTextPaint.descent() + placeholderTextPaint.ascent()) / 2f)
        canvas.drawText(text, textX, textY, placeholderTextPaint)
    }
}