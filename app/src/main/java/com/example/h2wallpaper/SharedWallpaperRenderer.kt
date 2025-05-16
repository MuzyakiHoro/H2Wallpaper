package com.example.h2wallpaper

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.renderscript.*
import android.util.Log
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

object SharedWallpaperRenderer {

    private const val TAG = "SharedRenderer"
    private const val DEBUG_TAG_RENDERER = "SharedRenderer_Debug"

    /**
     * 默认的背景图片模糊半径。
     * 值越大，模糊程度越高。25f 是一个较强的模糊效果。
     * 设置为 0f 表示不进行模糊处理。
     */
    const val DEFAULT_BACKGROUND_BLUR_RADIUS = 25f

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
        val p2BackgroundFadeInRatio: Float = 0.5f,    // P2背景在此比例的第一页滑动距离内完成淡入 (例如0.7f) - 注意：此参数在新逻辑下作用减弱
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

        if (config.screenWidth == 0 || config.screenHeight == 0) {
            Log.w(TAG, "Screen dimensions are zero, cannot draw.")
            return
        }

        canvas.drawColor(Color.BLACK) // 1. 清空画布为黑色

        // --- 计算 Alpha 值 ---
        var overlayAlpha = 0  // P1 叠加层的 alpha (0-255)
        // var backgroundAlpha = 0 // P2 背景的 alpha (0-255) <-- 不再直接用于P2绘制alpha，但p2AlphaFactor仍代表其理想权重

        val safeNumVirtualPages = config.numVirtualPages.coerceAtLeast(1)
        val topImageActualHeight = (config.screenHeight * config.page1ImageHeightRatio).toInt()
        var p1AlphaFactor = 1.0f // P1 不透明度因子 (1.0 不透明, 0.0 透明)
        // var p2AlphaFactor = 0f   // P2 背景不透明度因子 (0.0 透明, 1.0 不透明) <-- p2AlphaFactor计算仍保留，但用途改变

        if (safeNumVirtualPages > 1) {
            val singlePageXOffsetRange = 1.0f / safeNumVirtualPages.toFloat()

            // P1 叠加层 Alpha 计算 (淡出)
            val p1FadeOutEndXOffset = singlePageXOffsetRange * config.p1OverlayFadeTransitionRatio.coerceIn(0.01f, 1f)
            if (config.currentXOffset < p1FadeOutEndXOffset && p1FadeOutEndXOffset > 0) {
                val tP1 = config.currentXOffset / p1FadeOutEndXOffset
                p1AlphaFactor = 1.0f - Math.pow(tP1.toDouble(), 2.0).toFloat()
            } else {
                p1AlphaFactor = 0f
            }
            if (config.currentXOffset == 0f && p1FadeOutEndXOffset > 0) p1AlphaFactor = 1.0f
            overlayAlpha = (p1AlphaFactor * 255).toInt().coerceIn(0, 255)

            // P2 背景 Alpha 计算 (淡入) - 这个因子现在更多是概念上的，因为P2会画成不透明
            // 但如果未来有P2单独从黑淡入的场景，这个计算可能有用
            val p2FadeInEndXOffset = singlePageXOffsetRange * config.p2BackgroundFadeInRatio.coerceIn(0.01f, 1f)
            var p2AlphaFactorCurrent = 0f
            if (config.currentXOffset < p2FadeInEndXOffset && p2FadeInEndXOffset > 0) {
                val tP2 = config.currentXOffset / p2FadeInEndXOffset
                p2AlphaFactorCurrent = Math.pow(tP2.toDouble(), 2.0).toFloat()
            } else if (config.currentXOffset >= p2FadeInEndXOffset) {
                p2AlphaFactorCurrent = 1.0f
            }
            if (config.currentXOffset == 0f) p2AlphaFactorCurrent = 0f
            // backgroundAlpha = (p2AlphaFactorCurrent * 255).toInt().coerceIn(0, 255) // 不再这样使用

        } else { // 只有一页
            overlayAlpha = 255
            // backgroundAlpha = 0 // P2 在单页时也不应该有独立alpha，它会被P1完全覆盖
        }
        Log.d(DEBUG_TAG_RENDERER, "Alpha Factors: P1 OverlayAlpha=$overlayAlpha (Factor:$p1AlphaFactor)")

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

                // MODIFIED: P2层作为基底，始终不透明绘制，以便P1在其上混合
                scrollingBgPaint.alpha = 255
                canvas.drawBitmap(bgBmp, 0f, 0f, scrollingBgPaint)
                canvas.restore()
            }
        }

        // --- 绘制 P1 叠加层 ---
        // P1 使用 overlayAlpha (来自 p1AlphaFactor) 在不透明的P2上进行混合
        if (overlayAlpha > 0 && topImageActualHeight > 0) {
            // 绘制P1的底部颜色部分
            p1OverlayBgPaint.color = config.page1BackgroundColor
            p1OverlayBgPaint.alpha = overlayAlpha // P1底部颜色的透明度
            canvas.drawRect( 0f, topImageActualHeight.toFloat(), config.screenWidth.toFloat(), config.screenHeight.toFloat(), p1OverlayBgPaint )

            // 绘制P1的顶部图片部分
            bitmaps.page1TopCroppedBitmap?.let { topBmp ->
                if (!topBmp.isRecycled) {
                    p1OverlayImagePaint.alpha = overlayAlpha // P1顶部图片的透明度
                    canvas.drawBitmap(topBmp, 0f, 0f, p1OverlayImagePaint)
                } else {
                    drawPlaceholderForP1Overlay(canvas, config.screenWidth, topImageActualHeight, "顶图回收", overlayAlpha)
                }
            } ?: run {
                // 如果顶部图片为空但有源图，可能在加载中
                if (bitmaps.sourceSampledBitmap != null) {
                    drawPlaceholderForP1Overlay(canvas, config.screenWidth, topImageActualHeight, "P1顶图加载中...", overlayAlpha)
                }
            }
        }

        // 确保Paint对象的Alpha被重置，以防它们在其他地方被复用时带有意外的Alpha值
        // 虽然在此函数末尾重置可能不是绝对必要（如果每次使用前都设置），但这是个好习惯
        scrollingBgPaint.alpha = 255
        p1OverlayBgPaint.alpha = 255
        p1OverlayImagePaint.alpha = 255
        Log.d(DEBUG_TAG_RENDERER, "drawFrame END.")
    }

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

            if (bitmapAspectRatio > targetAspectRatio) { // 图片比目标区域更宽 (或一样宽但更高)
                cropWidth = (bmHeight * targetAspectRatio).toInt()
                srcX = (bmWidth - cropWidth) / 2
            } else { // 图片比目标区域更高 (或一样高但更窄)
                cropHeight = (bmWidth / targetAspectRatio).toInt()
                srcY = (bmHeight - cropHeight) / 2
            }

            cropWidth = min(cropWidth, bmWidth - srcX).coerceAtLeast(1)
            cropHeight = min(cropHeight, bmHeight - srcY).coerceAtLeast(1)
            srcX = srcX.coerceAtLeast(0)
            srcY = srcY.coerceAtLeast(0)

            // 防越界
            if (srcX + cropWidth > bmWidth) cropWidth = bmWidth - srcX
            if (srcY + cropHeight > bmHeight) cropHeight = bmHeight - srcY

            if (cropWidth > 0 && cropHeight > 0) {
                val cropped = Bitmap.createBitmap(sourceBitmap, srcX, srcY, cropWidth, cropHeight)
                page1TopCropped = Bitmap.createScaledBitmap(cropped, targetScreenWidth, targetTopHeight, true)
                if (cropped != page1TopCropped && !cropped.isRecycled) { // 如果创建了新的scaledBitmap且cropped不是同一个对象
                    cropped.recycle()
                }
            } else {
                Log.w(TAG, "preparePage1TopCroppedBitmap: Calculated cropWidth or cropHeight is zero.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating page1TopCroppedBitmap from source", e)
            page1TopCropped?.recycle()
            page1TopCropped = null
        }
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
                // 确保背景宽度至少是目标屏幕宽度的 actualNumVirtualPages 倍，但如果原始缩放图已经够宽，则不需要平铺那么多
                val bgFinalTargetWidth = (targetScreenWidth * actualNumVirtualPages)
                    .coerceAtLeast(scaledOriginalWidth) // 确保至少和缩放后的原图一样宽
                    .coerceAtLeast(targetScreenWidth) // 确保至少和屏幕一样宽

                try {
                    scrollingBackground = Bitmap.createBitmap(bgFinalTargetWidth, bgTargetHeight, Bitmap.Config.ARGB_8888)
                    val canvasForScrollingBg = Canvas(scrollingBackground!!)
                    var currentX = 0

                    if (scaledOriginalWidth >= bgFinalTargetWidth) {
                        // 如果缩放后的图片已经比最终目标宽度还要宽或相等，从中裁剪或直接使用
                        val offsetX = (scaledOriginalWidth - bgFinalTargetWidth) / 2 // 居中裁剪
                        canvasForScrollingBg.drawBitmap(
                            tempScaledBitmap,
                            Rect(offsetX, 0, offsetX + bgFinalTargetWidth, bgTargetHeight),
                            Rect(0, 0, bgFinalTargetWidth, bgTargetHeight),
                            null
                        )
                    } else {
                        // 如果缩放后的图片比最终目标宽度窄，则需要平铺
                        while (currentX < bgFinalTargetWidth) {
                            canvasForScrollingBg.drawBitmap(tempScaledBitmap, currentX.toFloat(), 0f, null)
                            currentX += tempScaledBitmap.width
                            if (tempScaledBitmap.width <= 0) break // 防止死循环
                        }
                    }

                    if (blurRadius > 0f && scrollingBackground?.isRecycled == false) {
                        blurredScrollingBackground = blurBitmapUsingRenderScript(context, scrollingBackground!!, blurRadius)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating scrolling or blurred background", e)
                    scrollingBackground?.recycle(); scrollingBackground = null
                    blurredScrollingBackground?.recycle(); blurredScrollingBackground = null
                } finally {
                    if (tempScaledBitmap != sourceBitmap && !tempScaledBitmap.isRecycled) {
                        tempScaledBitmap.recycle()
                    }
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
            Log.w(TAG, "loadAndProcessInitialBitmaps: Invalid input. URI null: ${imageUri==null}, SW:$targetScreenWidth, SH:$targetScreenHeight")
            return WallpaperBitmaps(null, null, null, null)
        }

        var sourceSampled: Bitmap? = null
        try {
            // 首次解码获取尺寸
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(imageUri)?.use { BitmapFactory.decodeStream(it, null, options) }

            if (options.outWidth <= 0 || options.outHeight <= 0) {
                Log.e(TAG, "loadAndProcessInitialBitmaps: Image bounds invalid after first decode. URI: $imageUri")
                return WallpaperBitmaps(null, null, null, null)
            }

            // 计算采样率，目标是加载一个足够大的位图用于后续所有处理
            // P1顶图需要适配屏幕宽度，P2背景图需要 numVirtualPagesForScrolling * 屏幕宽度
            val requiredWidthForP1 = targetScreenWidth
            val requiredHeightForP1 = (targetScreenHeight * page1ImageHeightRatio).toInt()
            val requiredWidthForP2 = targetScreenWidth * numVirtualPagesForScrolling.coerceAtLeast(1)
            val requiredHeightForP2 = targetScreenHeight

            // 以两者中较大的尺寸需求来计算采样率
            options.inSampleSize = calculateInSampleSize(options,
                maxOf(requiredWidthForP1, requiredWidthForP2),
                maxOf(requiredHeightForP1, requiredHeightForP2)
            )
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888 // 尝试获取带 Alpha 通道的位图

            // 实际解码
            context.contentResolver.openInputStream(imageUri)?.use { sourceSampled = BitmapFactory.decodeStream(it, null, options) }

            if (sourceSampled == null || sourceSampled!!.isRecycled) {
                Log.e(TAG, "Failed to decode sourceSampledBitmap. URI: $imageUri")
                return WallpaperBitmaps(null, null, null, null)
            }
            Log.d(TAG, "loadAndProcessInitialBitmaps: Source sampled to ${sourceSampled!!.width}x${sourceSampled!!.height} with inSampleSize=${options.inSampleSize}")


            val topCropped = preparePage1TopCroppedBitmap(sourceSampled, targetScreenWidth, targetScreenHeight, page1ImageHeightRatio)
            val (scrolling, blurred) = prepareScrollingAndBlurredBitmaps(context, sourceSampled, targetScreenWidth, targetScreenHeight, numVirtualPagesForScrolling, blurRadiusForBackground)

            return WallpaperBitmaps(sourceSampled, topCropped, scrolling, blurred)

        } catch (e: Exception) {
            Log.e(TAG, "Error in loadAndProcessInitialBitmaps for URI: $imageUri", e)
            sourceSampled?.recycle() // 确保出错时回收部分加载的位图
            return WallpaperBitmaps(null, null, null, null)
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (width == 0 || height == 0 || reqWidth <= 0 || reqHeight <= 0) return 1 // 防止除零

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than or equal to the requested height and width.
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
                // 安全退出，避免 inSampleSize 过大导致溢出或无效值
                if (inSampleSize <= 0 || inSampleSize > 1024) { // 1024 是一个相当大的采样值了
                    inSampleSize = if (inSampleSize > 1024) 1024 else maxOf(1, inSampleSize / 2) // 回退一步或取一个上限
                    break
                }
            }
        }
        Log.d(TAG, "calculateInSampleSize: original ${width}x${height}, required ${reqWidth}x${reqHeight}, sampleSize $inSampleSize")
        return inSampleSize
    }

    private fun blurBitmapUsingRenderScript(context: Context, bitmap: Bitmap, radius: Float): Bitmap? {
        // RenderScript 模糊半径限制在 (0, 25]
        val clampedRadius = radius.coerceIn(0.1f, 25.0f) // 小于等于0不模糊，大于25效果可能不佳或出错
        if (clampedRadius <= 0f) return null // 如果半径无效，直接返回null或原图（取决于设计）

        var rs: RenderScript? = null
        var outputBitmap: Bitmap? = null
        try {
            // 创建一个新的位图用于输出，避免直接修改输入位图（如果输入位图还需要用）
            outputBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888)
            rs = RenderScript.create(context)
            val input = Allocation.createFromBitmap(rs, bitmap)
            val output = Allocation.createFromBitmap(rs, outputBitmap)
            val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs)) // Element 根据位图类型选择

            script.setRadius(clampedRadius)
            script.setInput(input)
            script.forEach(output)
            output.copyTo(outputBitmap)

            input.destroy()
            output.destroy()
            script.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "RenderScript blur failed", e)
            outputBitmap?.recycle() // 出错时回收创建的输出位图
            outputBitmap = null
        } finally {
            rs?.destroy()
        }
        return outputBitmap
    }

    fun drawPlaceholder(canvas: Canvas, width: Int, height: Int, text: String) {
        placeholderBgPaint.color = Color.DKGRAY
        placeholderBgPaint.alpha = 255 // 占位符背景不透明
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), placeholderBgPaint)

        placeholderTextPaint.alpha = 200 // 文本可以稍微透明一点
        val textY = height / 2f - ((placeholderTextPaint.descent() + placeholderTextPaint.ascent()) / 2f)
        canvas.drawText(text, width / 2f, textY, placeholderTextPaint)
    }

    private fun drawPlaceholderForP1Overlay(canvas: Canvas, viewWidth: Int, topImageActualHeight: Int, text: String, overallAlpha: Int) {
        if (topImageActualHeight <= 0) return

        placeholderBgPaint.color = Color.GRAY // P1占位符用稍浅的灰色
        placeholderBgPaint.alpha = overallAlpha // P1占位符的透明度跟随P1整体
        canvas.drawRect(0f, 0f, viewWidth.toFloat(), topImageActualHeight.toFloat(), placeholderBgPaint)

        placeholderTextPaint.alpha = overallAlpha // 文本透明度也跟随P1整体
        val textX = viewWidth / 2f
        val textY = topImageActualHeight / 2f - ((placeholderTextPaint.descent() + placeholderTextPaint.ascent()) / 2f)
        canvas.drawText(text, textX, textY, placeholderTextPaint)
    }
}