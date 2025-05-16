package com.example.h2wallpaper

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.renderscript.*
import android.util.Log
import kotlin.math.min
import kotlin.math.pow // 确保导入 pow
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
        val currentXOffset: Float, // 当前总的滚动偏移 (0.0 到 1.0，代表所有可滚动页面的进度)
        val numVirtualPages: Int = 3, // 虚拟页面总数
        val p1OverlayFadeTransitionRatio: Float = 0.5f, // P1叠加层在此比例的第一页滑动距离内完成淡出 (例如0.3f)
        val p2BackgroundFadeInRatio: Float = 0.5f,    // P2背景在此比例的第一页滑动距离内完成淡入 (例如0.7f)
        val scrollSensitivityFactor: Float = 1.0f
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
        Log.d(DEBUG_TAG_RENDERER, "drawFrame START. Config: offset=${config.currentXOffset}, pages=${config.numVirtualPages}, screenW=${config.screenWidth}, p1FadeRatio=${config.p1OverlayFadeTransitionRatio}, p2FadeRatio=${config.p2BackgroundFadeInRatio}")
        // ... (其他初始日志和屏幕尺寸检查)

        if (config.screenWidth == 0 || config.screenHeight == 0) {
            Log.w(TAG, "Screen dimensions are zero, cannot draw.")
            return
        }

        canvas.drawColor(Color.BLACK)

        // --- 计算 Alpha 值 ---
        var overlayAlpha = 0  // P1 叠加层的 alpha (0-255)
        var backgroundAlpha = 0 // P2 背景的 alpha (0-255)

        // 将 factor 的声明提到条件判断之前，并设定单页时的默认值
        var p1AlphaFactor = 1.0f // 单页时 P1 不透明
        var p2AlphaFactor = 0f   // 单页时 P2 背景透明 (被P1覆盖)

        val safeNumVirtualPages = config.numVirtualPages.coerceAtLeast(1)
        val topImageActualHeight = (config.screenHeight * config.page1ImageHeightRatio).toInt()

        if (safeNumVirtualPages > 1) {
            val singlePageXOffsetRange = 1.0f / safeNumVirtualPages.toFloat() // 第一页对应的xOffset范围宽度

            // --- P1 叠加层 Alpha 计算 (淡出) ---
            // p1FadeOutEndXOffset 是P1叠加层完全消失时，currentXOffset 所达到的值
            val p1FadeOutEndXOffset = singlePageXOffsetRange * config.p1OverlayFadeTransitionRatio.coerceIn(0.01f, 1f)
            var p1AlphaFactor = 0f // P1 不透明度因子 (1.0 不透明, 0.0 透明)

            if (config.currentXOffset < p1FadeOutEndXOffset && p1FadeOutEndXOffset > 0) {
                val tP1 = config.currentXOffset / p1FadeOutEndXOffset // P1 淡出进度: 0 to 1
                // P1的淡出效果 (alpha从1变到0): 例如 Ease-Out (1 - t^2)
                p1AlphaFactor = 1.0f - Math.pow(tP1.toDouble(), 2.0).toFloat()
            } else {
                p1AlphaFactor = 0f // P1 完全透明
            }
            if (config.currentXOffset == 0f && p1FadeOutEndXOffset > 0) p1AlphaFactor = 1.0f // 确保起始时不透明
            overlayAlpha = (p1AlphaFactor * 255).toInt().coerceIn(0, 255)


            // --- P2 背景 Alpha 计算 (淡入) ---
            // p2FadeInEndXOffset 是P2背景完全不透明时，currentXOffset 所达到的值
            val p2FadeInEndXOffset = singlePageXOffsetRange * config.p2BackgroundFadeInRatio.coerceIn(0.01f, 1f)
            var p2AlphaFactor = 0f // P2 背景不透明度因子 (0.0 透明, 1.0 不透明)

            if (config.currentXOffset < p2FadeInEndXOffset && p2FadeInEndXOffset > 0) {
                val tP2 = config.currentXOffset / p2FadeInEndXOffset // P2 淡入进度: 0 to 1
                // P2的淡入效果 (alpha从0变到1): 例如 Ease-In (t^2)
                p2AlphaFactor = Math.pow(tP2.toDouble(), 2.0).toFloat()
            } else if (config.currentXOffset >= p2FadeInEndXOffset) {
                p2AlphaFactor = 1.0f // P2 完全不透明
            }
            if (config.currentXOffset == 0f) p2AlphaFactor = 0f // 确保起始时P2透明
            backgroundAlpha = (p2AlphaFactor * 255).toInt().coerceIn(0, 255)

        } else { // 只有一页
            overlayAlpha = 255
            backgroundAlpha = 0 // 在单页模式下，P2背景不应显示
        }
        Log.d(DEBUG_TAG_RENDERER, "Alpha Values: P1 OverlayAlpha=$overlayAlpha (Factor:$p1AlphaFactor), P2 BackgroundAlpha=$backgroundAlpha (Factor:$p2AlphaFactor)")

        // --- 绘制 P2 滚动背景层 ---
        val backgroundToDraw = bitmaps.blurredScrollingBackgroundBitmap ?: bitmaps.scrollingBackgroundBitmap
        backgroundToDraw?.let { bgBmp ->
            if (!bgBmp.isRecycled && bgBmp.width > 0) {
                val baseTotalScrollableWidth = (bgBmp.width - config.screenWidth).coerceAtLeast(0)
                val effectiveTotalScrollableWidth = (baseTotalScrollableWidth * config.scrollSensitivityFactor)
                var currentScrollPx = (config.currentXOffset * effectiveTotalScrollableWidth).toInt()
                currentScrollPx = currentScrollPx.coerceIn(0, baseTotalScrollableWidth)

                val bgTopOffset = ((config.screenHeight - bgBmp.height) / 2f)
                canvas.save()
                canvas.translate(-currentScrollPx.toFloat(), bgTopOffset)
                scrollingBgPaint.alpha = backgroundAlpha // 应用P2的backgroundAlpha
                canvas.drawBitmap(bgBmp, 0f, 0f, scrollingBgPaint)
                canvas.restore()
            }
        }

        // --- 绘制 P1 叠加层 ---
        if (overlayAlpha > 0 && topImageActualHeight > 0) {
            p1OverlayBgPaint.color = config.page1BackgroundColor
            p1OverlayBgPaint.alpha = overlayAlpha
            canvas.drawRect( 0f, topImageActualHeight.toFloat(), config.screenWidth.toFloat(), config.screenHeight.toFloat(), p1OverlayBgPaint )
            bitmaps.page1TopCroppedBitmap?.let { topBmp ->
                if (!topBmp.isRecycled) {
                    p1OverlayImagePaint.alpha = overlayAlpha
                    canvas.drawBitmap(topBmp, 0f, 0f, p1OverlayImagePaint)
                } else { drawPlaceholderForP1Overlay(canvas, config.screenWidth, topImageActualHeight, "顶图回收", overlayAlpha) }
            } ?: run {
                if (bitmaps.sourceSampledBitmap != null) {
                    drawPlaceholderForP1Overlay(canvas, config.screenWidth, topImageActualHeight, "P1顶图加载中...", overlayAlpha)
                }
            }
        }

        scrollingBgPaint.alpha = 255
        p1OverlayBgPaint.alpha = 255
        p1OverlayImagePaint.alpha = 255
        Log.d(DEBUG_TAG_RENDERER, "drawFrame END.")
    }

    // --- 其他辅助方法 ---
    // preparePage1TopCroppedBitmap, prepareScrollingAndBlurredBitmaps,
    // loadAndProcessInitialBitmaps, calculateInSampleSize, blurBitmapUsingRenderScript,
    // drawPlaceholder, drawPlaceholderForP1Overlay
    // 这些方法与您之前确认的版本保持一致。为了简洁，这里不再重复粘贴它们。
    // 请确保您使用的是这些方法的最新、最稳定的版本。
    // 为了避免歧义，我将再次粘贴这些辅助方法，确保与上下文一致：

    fun preparePage1TopCroppedBitmap(
        sourceBitmap: Bitmap?, targetScreenWidth: Int, targetScreenHeight: Int, page1ImageHeightRatio: Float
    ): Bitmap? {
        if (sourceBitmap == null || sourceBitmap.isRecycled || targetScreenWidth <= 0 || targetScreenHeight <= 0 || page1ImageHeightRatio <= 0f) {
            Log.w(TAG, "preparePage1TopCroppedBitmap: Invalid input. SourceNull: ${sourceBitmap==null}, Recycled: ${sourceBitmap?.isRecycled}, SW:$targetScreenWidth, SH:$targetScreenHeight, Ratio:$page1ImageHeightRatio")
            return null
        }
        val targetTopHeight = (targetScreenHeight * page1ImageHeightRatio).toInt()
        if (targetTopHeight <= 0) { Log.w(TAG, "preparePage1TopCroppedBitmap: Calculated targetTopHeight is <=0."); return null }
        var page1TopCropped: Bitmap? = null
        try {
            val bmWidth = sourceBitmap.width; val bmHeight = sourceBitmap.height
            var srcX = 0; var srcY = 0; var cropWidth = bmWidth; var cropHeight = bmHeight
            val bitmapAspectRatio = bmWidth.toFloat() / bmHeight.toFloat()
            val targetAspectRatio = targetScreenWidth.toFloat() / targetTopHeight.toFloat()
            if (bitmapAspectRatio > targetAspectRatio) {
                cropWidth = (bmHeight * targetAspectRatio).toInt(); srcX = (bmWidth - cropWidth) / 2
            } else {
                cropHeight = (bmWidth / targetAspectRatio).toInt(); srcY = (bmHeight - cropHeight) / 2
            }
            cropWidth = min(cropWidth, bmWidth - srcX).coerceAtLeast(1); cropHeight = min(cropHeight, bmHeight - srcY).coerceAtLeast(1)
            srcX = srcX.coerceAtLeast(0); srcY = srcY.coerceAtLeast(0)
            if (srcX + cropWidth > bmWidth) cropWidth = bmWidth - srcX
            if (srcY + cropHeight > bmHeight) cropHeight = bmHeight - srcY
            if (cropWidth > 0 && cropHeight > 0) {
                val cropped = Bitmap.createBitmap(sourceBitmap, srcX, srcY, cropWidth, cropHeight)
                page1TopCropped = Bitmap.createScaledBitmap(cropped, targetScreenWidth, targetTopHeight, true)
                if (cropped != page1TopCropped && !cropped.isRecycled) cropped.recycle()
            } else { Log.w(TAG, "preparePage1TopCroppedBitmap: Calculated cropWidth or cropHeight is zero.") }
        } catch (e: Exception) { Log.e(TAG, "Error creating page1TopCroppedBitmap from source", e); page1TopCropped?.recycle(); page1TopCropped = null }
        return page1TopCropped
    }

    fun prepareScrollingAndBlurredBitmaps(
        context: Context, sourceBitmap: Bitmap?, targetScreenWidth: Int, targetScreenHeight: Int,
        numVirtualPages: Int, blurRadius: Float
    ): Pair<Bitmap?, Bitmap?> {
        if (sourceBitmap == null || sourceBitmap.isRecycled || targetScreenWidth <= 0 || targetScreenHeight <= 0 || numVirtualPages <= 0) {
            Log.w(TAG, "prepareScrollingAndBlurredBitmaps: Invalid input.")
            return Pair(null, null)
        }
        var scrollingBackground: Bitmap? = null; var blurredScrollingBackground: Bitmap? = null
        val bgTargetHeight = targetScreenHeight; val obW = sourceBitmap.width.toFloat(); val obH = sourceBitmap.height.toFloat()
        if (obW > 0 && obH > 0) {
            val scaleToFitScreenHeight = bgTargetHeight / obH; val scaledOriginalWidth = (obW * scaleToFitScreenHeight).toInt()
            if (scaledOriginalWidth > 0) {
                val tempScaledBitmap = Bitmap.createScaledBitmap(sourceBitmap, scaledOriginalWidth, bgTargetHeight, true)
                val actualNumVirtualPages = numVirtualPages.coerceAtLeast(1)
                val bgFinalTargetWidth = targetScreenWidth * actualNumVirtualPages
                try {
                    scrollingBackground = Bitmap.createBitmap(bgFinalTargetWidth, bgTargetHeight, Bitmap.Config.ARGB_8888)
                    val canvasForScrollingBg = Canvas(scrollingBackground!!); var currentX = 0
                    if (scaledOriginalWidth >= bgFinalTargetWidth) {
                        val offsetX = (scaledOriginalWidth - bgFinalTargetWidth) / 2
                        canvasForScrollingBg.drawBitmap(tempScaledBitmap, Rect(offsetX, 0, offsetX + bgFinalTargetWidth, bgTargetHeight), Rect(0, 0, bgFinalTargetWidth, bgTargetHeight), null)
                    } else {
                        while (currentX < bgFinalTargetWidth) {
                            canvasForScrollingBg.drawBitmap(tempScaledBitmap, currentX.toFloat(), 0f, null)
                            currentX += tempScaledBitmap.width; if (tempScaledBitmap.width <= 0) break
                        }
                    }
                    if (blurRadius > 0f && scrollingBackground?.isRecycled == false) {
                        blurredScrollingBackground = blurBitmapUsingRenderScript(context, scrollingBackground, blurRadius)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating scrolling or blurred background", e)
                    scrollingBackground?.recycle(); scrollingBackground = null
                    blurredScrollingBackground?.recycle(); blurredScrollingBackground = null
                } finally { if (tempScaledBitmap != sourceBitmap && !tempScaledBitmap.isRecycled) tempScaledBitmap.recycle() }
            } else { Log.w(TAG, "prepareScrollingAndBlurredBitmaps: Scaled original width is zero.") }
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
            options.inJustDecodeBounds = false; options.inPreferredConfig = Bitmap.Config.ARGB_8888
            context.contentResolver.openInputStream(imageUri)?.use { sourceSampled = BitmapFactory.decodeStream(it, null, options) }
            if (sourceSampled == null || sourceSampled!!.isRecycled) { Log.e(TAG, "Failed to decode sourceSampledBitmap."); return WallpaperBitmaps(null, null, null, null) }
            val topCropped = preparePage1TopCroppedBitmap(sourceSampled, targetScreenWidth, targetScreenHeight, page1ImageHeightRatio)
            val (scrolling, blurred) = prepareScrollingAndBlurredBitmaps(context, sourceSampled, targetScreenWidth, targetScreenHeight, numVirtualPagesForScrolling, blurRadiusForBackground)
            return WallpaperBitmaps(sourceSampled, topCropped, scrolling, blurred)
        } catch (e: Exception) { Log.e(TAG, "Error in loadAndProcessInitialBitmaps", e); sourceSampled?.recycle(); return WallpaperBitmaps(null, null, null, null) }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth; var inSampleSize = 1
        if (width == 0 || height == 0 || reqWidth <= 0 || reqHeight <= 0) return 1
        if (height > reqHeight || width > reqWidth) {
            val halfH = height / 2; val halfW = width / 2
            while ((halfH / inSampleSize) >= reqHeight && (halfW / inSampleSize) >= reqWidth) {
                inSampleSize *= 2; if (inSampleSize <= 0 || inSampleSize > 1024) { inSampleSize = 1024; break; }
            }
        }
        return inSampleSize
    }

    private fun blurBitmapUsingRenderScript(context: Context, bitmap: Bitmap, radius: Float): Bitmap? {
        if (radius < 1f || radius > 25f) return null
        var rs: RenderScript? = null; var outputBitmap: Bitmap? = null
        try {
            outputBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888)
            rs = RenderScript.create(context)
            val input = Allocation.createFromBitmap(rs, bitmap); val output = Allocation.createFromBitmap(rs, outputBitmap)
            val script = ScriptIntrinsicBlur.create(rs, input.element)
            script.setRadius(radius); script.setInput(input); script.forEach(output); output.copyTo(outputBitmap)
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
        val textX = viewWidth / 2f; val textY = topImageActualHeight / 2f - ((placeholderTextPaint.descent() + placeholderTextPaint.ascent()) / 2f)
        canvas.drawText(text, textX, textY, placeholderTextPaint)
    }
}