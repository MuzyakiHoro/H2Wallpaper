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

    /**
     * 代表壁纸渲染所需的所有静态资源（位图）
     * sourceSampledBitmap 是从URI解码并进行初步采样后的位图，作为后续裁剪和缩放的基础。
     * 其他位图是从 sourceSampledBitmap 派生出来的。
     */
    data class WallpaperBitmaps(
        var sourceSampledBitmap: Bitmap?, // 新增：从URI解码和采样后的原始参照图
        var page1TopCroppedBitmap: Bitmap?,
        var scrollingBackgroundBitmap: Bitmap?,
        var blurredScrollingBackgroundBitmap: Bitmap?
    ) {
        // 辅助方法用于回收所有内部位图
        fun recycleInternals() {
            sourceSampledBitmap?.recycle(); sourceSampledBitmap = null
            page1TopCroppedBitmap?.recycle(); page1TopCroppedBitmap = null
            scrollingBackgroundBitmap?.recycle(); scrollingBackgroundBitmap = null
            blurredScrollingBackgroundBitmap?.recycle(); blurredScrollingBackgroundBitmap = null
        }
    }

    // WallpaperConfig data class 保持不变

    data class WallpaperConfig(
        val screenWidth: Int,
        val screenHeight: Int,
        val page1BackgroundColor: Int,
        val page1ImageHeightRatio: Float,
        val currentXOffset: Float,
        val numVirtualPages: Int = 3,
        val p1OverlayFadeTransitionRatio: Float = 0.2f
    )

    // drawFrame 方法基本保持不变，它依赖传入的 WallpaperBitmaps 的当前状态
    fun drawFrame(
        canvas: Canvas,
        config: WallpaperConfig,
        bitmaps: WallpaperBitmaps // 现在这个对象的成员是可变的
    ) {
        // ... (drawFrame 内部逻辑与之前版本一致，确保它正确处理 bitmaps 内部成员可能为 null 的情况)
        if (config.screenWidth == 0 || config.screenHeight == 0) {
            Log.w(TAG, "Screen dimensions are zero, cannot draw.")
            return
        }

        canvas.drawColor(Color.BLACK)

        val backgroundToDraw = bitmaps.blurredScrollingBackgroundBitmap ?: bitmaps.scrollingBackgroundBitmap
        backgroundToDraw?.let { bgBmp ->
            if (!bgBmp.isRecycled && bgBmp.width > 0) {
                val totalScrollableWidth = (bgBmp.width - config.screenWidth).coerceAtLeast(0)
                val currentScrollPx = (config.currentXOffset * totalScrollableWidth).toInt()
                    .coerceIn(0, totalScrollableWidth)
                val bgTopOffset = ((config.screenHeight - bgBmp.height) / 2f)
                canvas.save()
                canvas.translate(-currentScrollPx.toFloat(), bgTopOffset)
                scrollingBgPaint.alpha = 255
                canvas.drawBitmap(bgBmp, 0f, 0f, scrollingBgPaint)
                canvas.restore()
            } else {
                // 如果背景图无效但顶图可能有效，这里不应该绘制全屏占位符，而是让顶图部分绘制
                // 或者，如果 contract 是 scrollingBackgroundBitmap 必须存在，那这里可以绘制占位符
                // 为了安全，如果 scrollingBackgroundBitmap 无效，我们退化到黑色背景
                // drawPlaceholder(canvas, config.screenWidth, config.screenHeight, "背景处理中...")
            }
        } ?: run {
            // 如果两个背景图都为null，也只清空为黑色，顶图仍然可能绘制
            // drawPlaceholder(canvas, config.screenWidth, config.screenHeight, "请选择图片以生成背景")
        }


        var overlayAlpha = 0
        val topImageActualHeight = (config.screenHeight * config.page1ImageHeightRatio).toInt()
        val singlePageXOffsetRange = 1.0f / config.numVirtualPages.coerceAtLeast(1)
        val fadeOutEndXOffset = singlePageXOffsetRange * config.p1OverlayFadeTransitionRatio.coerceIn(0.01f, 1f)

        if (config.currentXOffset < fadeOutEndXOffset) {
            overlayAlpha = ((1.0f - (config.currentXOffset / fadeOutEndXOffset)) * 255).toInt().coerceIn(0, 255)
        } else {
            overlayAlpha = 0
        }
        if (config.currentXOffset == 0f && config.numVirtualPages == 1) overlayAlpha = 255 // 单页时常亮
        else if (config.currentXOffset == 0f && config.numVirtualPages > 1 && fadeOutEndXOffset > 0) overlayAlpha = 255


        if (overlayAlpha > 0) {
            p1OverlayBgPaint.color = config.page1BackgroundColor
            p1OverlayBgPaint.alpha = overlayAlpha
            canvas.drawRect(0f, topImageActualHeight.toFloat(), config.screenWidth.toFloat(), config.screenHeight.toFloat(), p1OverlayBgPaint)

            bitmaps.page1TopCroppedBitmap?.let { topBmp ->
                if (!topBmp.isRecycled) {
                    p1OverlayImagePaint.alpha = overlayAlpha
                    canvas.drawBitmap(topBmp, 0f, 0f, p1OverlayImagePaint)
                } else {
                    drawPlaceholderForP1Overlay(canvas, config.screenWidth, topImageActualHeight, "顶图回收", overlayAlpha)
                }
            } ?: run {
                if (bitmaps.sourceSampledBitmap != null) { // 至少有原始图片时才提示
                    drawPlaceholderForP1Overlay(canvas, config.screenWidth, topImageActualHeight, "P1顶图处理中...", overlayAlpha)
                }
            }
        }
        scrollingBgPaint.alpha = 255
        p1OverlayBgPaint.alpha = 255
        p1OverlayImagePaint.alpha = 255
    }


    /**
     * 从原始采样位图准备第一页的顶部裁剪图。
     */
    fun preparePage1TopCroppedBitmap(
        sourceBitmap: Bitmap?, // 使用采样后的源位图
        targetScreenWidth: Int,
        targetScreenHeight: Int,
        page1ImageHeightRatio: Float
    ): Bitmap? {
        if (sourceBitmap == null || sourceBitmap.isRecycled || targetScreenWidth <= 0 || targetScreenHeight <= 0 || page1ImageHeightRatio <= 0f) {
            return null
        }
        val targetTopHeight = (targetScreenHeight * page1ImageHeightRatio).toInt()
        if (targetTopHeight <= 0) return null

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
            // 安全检查，防止裁剪尺寸超过原图边界 (虽然理论上不应发生)
            if (srcX + cropWidth > bmWidth) cropWidth = bmWidth - srcX
            if (srcY + cropHeight > bmHeight) cropHeight = bmHeight - srcY


            if (cropWidth > 0 && cropHeight > 0) {
                val cropped = Bitmap.createBitmap(sourceBitmap, srcX, srcY, cropWidth, cropHeight)
                page1TopCropped = Bitmap.createScaledBitmap(cropped, targetScreenWidth, targetTopHeight, true)
                if (cropped != page1TopCropped && !cropped.isRecycled) cropped.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating page1TopCroppedBitmap from source", e)
            page1TopCropped?.recycle()
            page1TopCropped = null
        }
        return page1TopCropped
    }

    /**
     * 从原始采样位图准备滚动背景图和模糊背景图。
     */
    fun prepareScrollingAndBlurredBitmaps(
        context: Context,
        sourceBitmap: Bitmap?, // 使用采样后的源位图
        targetScreenWidth: Int,
        targetScreenHeight: Int,
        numVirtualPages: Int,
        blurRadius: Float
    ): Pair<Bitmap?, Bitmap?> {
        if (sourceBitmap == null || sourceBitmap.isRecycled || targetScreenWidth <= 0 || targetScreenHeight <= 0 || numVirtualPages <= 0) {
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
                val bgFinalTargetWidth = targetScreenWidth * actualNumVirtualPages

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
                if (tempScaledBitmap != sourceBitmap && !tempScaledBitmap.isRecycled) tempScaledBitmap.recycle()

                if (blurRadius > 0f && scrollingBackground != null && !scrollingBackground.isRecycled) {
                    blurredScrollingBackground = blurBitmapUsingRenderScript(context, scrollingBackground, blurRadius)
                }
            }
        }
        return Pair(scrollingBackground, blurredScrollingBackground)
    }


    /**
     * 从 URI 加载并准备所有位图，包括原始采样位图。
     * 返回一个 WallpaperBitmaps 对象。
     */
    fun loadAndProcessInitialBitmaps(
        context: Context,
        imageUri: Uri?,
        targetScreenWidth: Int, // 用于计算采样率和最终尺寸
        targetScreenHeight: Int,
        page1ImageHeightRatio: Float, // 用于首次生成 page1TopCroppedBitmap
        numVirtualPagesForScrolling: Int,
        blurRadiusForBackground: Float
    ): WallpaperBitmaps {
        if (imageUri == null || targetScreenWidth <= 0 || targetScreenHeight <= 0) {
            return WallpaperBitmaps(null, null, null, null)
        }

        var sourceSampled: Bitmap? = null
        try {
            // 1. 解码原始图片 (带采样)，得到 sourceSampledBitmap
            // 采样大小目标：略大于或等于 targetScreenHeight，宽度则按比例。
            // 或者，采样目标是 scrollingBackgroundBitmap 的需求，它通常最大。
            // targetScreenWidth * numVirtualPagesForScrolling 是滚动背景的总宽度。
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(imageUri)?.use { BitmapFactory.decodeStream(it, null, options) }

            options.inSampleSize = calculateInSampleSize(options, targetScreenWidth * numVirtualPagesForScrolling, targetScreenHeight)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            context.contentResolver.openInputStream(imageUri)?.use { sourceSampled = BitmapFactory.decodeStream(it, null, options) }

            if (sourceSampled == null || sourceSampled!!.isRecycled) {
                Log.e(TAG, "Failed to decode sourceSampledBitmap.")
                return WallpaperBitmaps(null, null, null, null)
            }
            Log.d(TAG, "Source sampled bitmap: ${sourceSampled!!.width}x${sourceSampled!!.height}")

            // 2. 从 sourceSampledBitmap 准备其他位图
            val topCropped = preparePage1TopCroppedBitmap(sourceSampled, targetScreenWidth, targetScreenHeight, page1ImageHeightRatio)
            val (scrolling, blurred) = prepareScrollingAndBlurredBitmaps(context, sourceSampled, targetScreenWidth, targetScreenHeight, numVirtualPagesForScrolling, blurRadiusForBackground)

            // 注意：sourceSampled 在这里不应该被回收，因为它被 WallpaperBitmaps 持有
            return WallpaperBitmaps(sourceSampled, topCropped, scrolling, blurred)

        } catch (e: Exception) {
            Log.e(TAG, "Error in loadAndProcessInitialBitmaps", e)
            sourceSampled?.recycle() // 如果发生错误，回收已加载的源图
            return WallpaperBitmaps(null, null, null, null)
        }
    }

    // calculateInSampleSize, blurBitmapUsingRenderScript, drawPlaceholder, drawPlaceholderForP1Overlay 保持不变
    // Paint 对象 (scrollingBgPaint, p1OverlayBgPaint, etc.) 也保持不变
    private val scrollingBgPaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
    private val p1OverlayBgPaint = Paint()
    private val p1OverlayImagePaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
    private val placeholderTextPaint = Paint().apply {
        color = Color.WHITE; textSize = 40f; textAlign = Paint.Align.CENTER; isAntiAlias = true
    }
    private val placeholderBgPaint = Paint()


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
            return outputBitmap
        } catch (e: Exception) { Log.e(TAG, "RenderScript blur failed", e); outputBitmap?.recycle(); return null
        } finally { rs?.destroy() }
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