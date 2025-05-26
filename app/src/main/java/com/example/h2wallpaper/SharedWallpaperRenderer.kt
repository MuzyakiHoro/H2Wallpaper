package com.example.h2wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.util.Log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

// import com.example.h2wallpaper.WallpaperConfigConstants // 通常此文件独立

object SharedWallpaperRenderer {

    private const val TAG = "SharedRenderer"
    private const val DEBUG_TAG_RENDERER = "SharedRenderer_Debug"
    private const val MIN_DOWNSCALED_DIMENSION = 16

    data class WallpaperBitmaps(
        var sourceSampledBitmap: Bitmap?,
        var page1TopCroppedBitmap: Bitmap?, // 这个现在会考虑 contentScaleFactor
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
        // p1ContentScaleFactor 在 preparePage1TopCroppedBitmap 时已应用
    )

    private val scrollingBgPaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
    private val p1OverlayBgPaint = Paint()
    private val p1OverlayImagePaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
    private val placeholderTextPaint = Paint().apply {
        color = Color.WHITE; textSize = 40f; textAlign = Paint.Align.CENTER; isAntiAlias = true
    }
    private val placeholderBgPaint = Paint().apply { color = Color.DKGRAY }
    private val rectShadowPaint = Paint().apply { isAntiAlias = true }
    private val overlayFadePaint = Paint().apply { isAntiAlias = true }


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
    }

    private fun drawPage2Layer(
        canvas: Canvas,
        config: WallpaperConfig,
        bitmaps: WallpaperBitmaps
    ) {
        val safeNumVirtualPages = config.numVirtualPages.coerceAtLeast(1)
        var p2BackgroundAlpha = 255

        if (safeNumVirtualPages > 1) {
            val fadeInEndXOffset = (1.0f / safeNumVirtualPages.toFloat()) * config.p2BackgroundFadeInRatio.coerceIn(0.01f, 1.0f)
            if (config.currentXOffset < fadeInEndXOffset) {
                val p2TransitionProgress = (config.currentXOffset / fadeInEndXOffset).coerceIn(0f, 1f)
                p2BackgroundAlpha = (255 * p2TransitionProgress.pow(2.0f)).toInt().coerceIn(0, 255)
            } else {
                p2BackgroundAlpha = 255
            }
        } else {
            p2BackgroundAlpha = 255
        }

        val backgroundToDraw = bitmaps.blurredScrollingBackgroundBitmap ?: bitmaps.scrollingBackgroundBitmap
        backgroundToDraw?.let { bgBmp ->
            if (!bgBmp.isRecycled && bgBmp.width > 0 && bgBmp.height > 0) {
                val imageActualWidth = bgBmp.width.toFloat()
                val screenActualWidth = config.screenWidth.toFloat()
                val totalScrollableWidthPx = (imageActualWidth - screenActualWidth).coerceAtLeast(0f)
                val initialPixelOffset = totalScrollableWidthPx * config.normalizedInitialBgScrollOffset.coerceIn(0f,1f)
                val scrollRangeForSensitivity = totalScrollableWidthPx - initialPixelOffset
                var currentDynamicScrollPx = config.currentXOffset * config.scrollSensitivityFactor * scrollRangeForSensitivity
                currentDynamicScrollPx = currentDynamicScrollPx.coerceIn(0f, scrollRangeForSensitivity)
                val currentScrollPxFloat = (initialPixelOffset + currentDynamicScrollPx).coerceIn(0f, totalScrollableWidthPx)
                val bgTopOffset = ((config.screenHeight - bgBmp.height) / 2f)

                canvas.save()
                canvas.translate(-currentScrollPxFloat, bgTopOffset)
                scrollingBgPaint.alpha = p2BackgroundAlpha
                canvas.drawBitmap(bgBmp, 0f, 0f, scrollingBgPaint)
                canvas.restore()
            } else {
                Log.w(TAG, "drawFrame (P2): Background bitmap is invalid or recycled.")
            }
        }
    }

    /**
     * 仅根据已有的基础Bitmap和新的模糊参数重新生成模糊后的Bitmap。
     *
     * @param context Context
     * @param baseBitmap 要应用模糊效果的基础Bitmap (例如，未模糊的滚动背景图)
     * @param targetWidth 最终模糊图的目标宽度 (通常与baseBitmap宽度一致)
     * @param targetHeight 最终模糊图的目标高度 (通常与baseBitmap高度一致)
     * @param blurRadius 模糊半径
     * @param blurDownscaleFactor 模糊前的降采样因子
     * @param blurIterations 模糊迭代次数
     * @return 新的模糊后的Bitmap，如果失败则返回null
     */
    fun regenerateBlurredBitmap(
        context: Context,
        baseBitmap: Bitmap?,
        targetWidth: Int,
        targetHeight: Int,
        blurRadius: Float,
        blurDownscaleFactor: Float,
        blurIterations: Int
    ): Bitmap? {
        if (baseBitmap == null || baseBitmap.isRecycled || targetWidth <= 0 || targetHeight <= 0) {
            Log.w(TAG, "regenerateBlurredBitmap: Invalid baseBitmap or target dimensions.")
            return null
        }
        if (blurRadius <= 0.01f) { // 如果不需要模糊，可以返回null或baseBitmap的副本
            return null // 或者 baseBitmap.copy(baseBitmap.config, true) 如果希望返回一个未模糊的图
        }

        var downscaledForBlur: Bitmap? = null
        var blurredDownscaled: Bitmap? = null
        var upscaledBlurredBitmap: Bitmap? = null

        try {
            val sourceForActualBlur = baseBitmap
            val actualDownscaleFactor = blurDownscaleFactor.coerceIn(0.05f, 1.0f)
            val downscaledWidth = (sourceForActualBlur.width * actualDownscaleFactor).roundToInt().coerceAtLeast(MIN_DOWNSCALED_DIMENSION)
            val downscaledHeight = (sourceForActualBlur.height * actualDownscaleFactor).roundToInt().coerceAtLeast(MIN_DOWNSCALED_DIMENSION)

            if (actualDownscaleFactor < 0.99f && (downscaledWidth < sourceForActualBlur.width || downscaledHeight < sourceForActualBlur.height)) {
                downscaledForBlur = Bitmap.createScaledBitmap(sourceForActualBlur, downscaledWidth, downscaledHeight, true)
                blurredDownscaled = blurBitmapUsingRenderScript(context, downscaledForBlur, blurRadius.coerceIn(0.1f, 25.0f), blurIterations)
                if (blurredDownscaled != null) {
                    // 放大回目标尺寸 (通常是 baseBitmap 的原始尺寸)
                    upscaledBlurredBitmap = Bitmap.createScaledBitmap(blurredDownscaled, targetWidth, targetHeight, true)
                } else {
                    Log.w(TAG, "regenerateBlurredBitmap: Blurred downscaled bitmap is null, falling back to blur on base bitmap.")
                    upscaledBlurredBitmap = blurBitmapUsingRenderScript(context, sourceForActualBlur, blurRadius, blurIterations)
                    // 如果fallback也需要缩放到targetWidth/Height，但通常sourceForActualBlur已经是正确尺寸了
                }
            } else {
                // 不需要降采样，直接模糊
                upscaledBlurredBitmap = blurBitmapUsingRenderScript(context, sourceForActualBlur, blurRadius, blurIterations)
            }
            return upscaledBlurredBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error during regenerateBlurredBitmap", e)
            upscaledBlurredBitmap?.recycle() // newBitmapHolder?.recycleInternals() in H2WallpaperService
            blurredDownscaled?.recycle()
            downscaledForBlur?.recycle()
            return null
        } finally {
            // 清理中间位图，除非它们是返回的结果
            if (upscaledBlurredBitmap != blurredDownscaled) blurredDownscaled?.recycle()
            if (upscaledBlurredBitmap != downscaledForBlur && blurredDownscaled != downscaledForBlur) downscaledForBlur?.recycle()
        }
    }


    private fun drawPage1Layer(
        canvas: Canvas,
        config: WallpaperConfig,
        bitmaps: WallpaperBitmaps
    ) {
        val safeNumVirtualPages = config.numVirtualPages.coerceAtLeast(1)
        val topImageActualHeight = (config.screenHeight * config.page1ImageHeightRatio).roundToInt()
        var p1OverallAlpha = 255

        if (safeNumVirtualPages > 1) {
            val fadeOutEndXOffset = (1.0f / safeNumVirtualPages.toFloat()) * config.p1OverlayFadeTransitionRatio.coerceIn(0.01f, 1.0f)
            if (config.currentXOffset < fadeOutEndXOffset) {
                val p1TransitionProgress = (config.currentXOffset / fadeOutEndXOffset).coerceIn(0f, 1f)
                p1OverallAlpha = (255 * (1.0f - p1TransitionProgress.pow(2.0f))).toInt().coerceIn(0, 255)
            } else {
                p1OverallAlpha = 0
            }
        } else {
            p1OverallAlpha = 255
        }

        if (p1OverallAlpha > 0 && topImageActualHeight > 0) {
            canvas.saveLayerAlpha(0f, 0f, config.screenWidth.toFloat(), config.screenHeight.toFloat(), p1OverallAlpha)
            p1OverlayBgPaint.shader = null
            p1OverlayBgPaint.color = config.page1BackgroundColor
            canvas.drawRect(0f, topImageActualHeight.toFloat(), config.screenWidth.toFloat(), config.screenHeight.toFloat(), p1OverlayBgPaint)

            bitmaps.page1TopCroppedBitmap?.let { topBmp ->
                if (!topBmp.isRecycled && topBmp.width > 0 && topBmp.height > 0) {
                    if (config.p1ShadowRadius > 0.01f && Color.alpha(config.p1ShadowColor) > 0) {
                        rectShadowPaint.apply { color = Color.TRANSPARENT
                            setShadowLayer(config.p1ShadowRadius, config.p1ShadowDx, config.p1ShadowDy, config.p1ShadowColor)
                        }
                        canvas.drawRect(0f, 0f, topBmp.width.toFloat(), topBmp.height.toFloat(), rectShadowPaint)
                    }
                    p1OverlayImagePaint.clearShadowLayer()
                    canvas.drawBitmap(topBmp, 0f, 0f, p1OverlayImagePaint)
                    val fadeActualHeight = config.p1ImageBottomFadeHeight.coerceIn(0f, topBmp.height.toFloat())
                    if (fadeActualHeight > 0.1f) {
                        val fadeStartY = topBmp.height.toFloat(); val fadeEndY = topBmp.height.toFloat() - fadeActualHeight
                        overlayFadePaint.shader = LinearGradient(0f, fadeStartY, 0f, fadeEndY, config.page1BackgroundColor, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                        canvas.drawRect(0f, fadeEndY, topBmp.width.toFloat(), fadeStartY, overlayFadePaint)
                    } else {
                        overlayFadePaint.shader = null
                    }
                } else { Log.w(TAG, "drawFrame (P1): Top cropped bitmap is invalid or recycled.") }
            } ?: Log.d(TAG, "drawFrame (P1): No top cropped bitmap available.")
            canvas.restore()
        }
    }

    fun preparePage1TopCroppedBitmap(
        sourceBitmap: Bitmap?, targetScreenWidth: Int, targetScreenHeight: Int,
        page1ImageHeightRatio: Float,
        normalizedFocusX: Float = 0.5f, normalizedFocusY: Float = 0.5f,
        contentScaleFactor: Float = 1.0f // 新增参数
    ): Bitmap? {
        if (sourceBitmap == null || sourceBitmap.isRecycled) return null
        if (targetScreenWidth <= 0 || targetScreenHeight <= 0 || page1ImageHeightRatio <= 0f) return null
        val targetP1ActualHeight = (targetScreenHeight * page1ImageHeightRatio).roundToInt()
        if (targetP1ActualHeight <= 0) return null

        val bmWidth = sourceBitmap.width; val bmHeight = sourceBitmap.height
        if (bmWidth <= 0 || bmHeight <= 0) return null

        var finalP1Bitmap: Bitmap? = null
        try {
            // 1. P1区域的基础填充缩放 (使源图刚好填满P1区域的短边所需的缩放)
            val baseScaleXToFillP1 = targetScreenWidth.toFloat() / bmWidth.toFloat()
            val baseScaleYToFillP1 = targetP1ActualHeight.toFloat() / bmHeight.toFloat()
            val baseFillScale = max(baseScaleXToFillP1, baseScaleYToFillP1)

            // 2. 应用用户自定义的内容缩放因子
            val totalEffectiveScale = baseFillScale * contentScaleFactor.coerceAtLeast(1.0f) // 内容缩放至少为1倍（相对于基础填充）

            // 3. 计算在源图像上需要裁剪的区域的尺寸（这个区域在经过totalEffectiveScale缩放后，将等于P1的目标显示尺寸）
            val cropWidthInSourcePx = targetScreenWidth / totalEffectiveScale
            val cropHeightInSourcePx = targetP1ActualHeight / totalEffectiveScale

            // 4. 计算裁剪区域在源图像中的左上角坐标(srcX, srcY)
            // 目标是：源图像上由(normalizedFocusX, normalizedFocusY)指定的点，成为裁剪出的小区域的中心点。
            var srcX = (normalizedFocusX * bmWidth) - (cropWidthInSourcePx / 2f)
            var srcY = (normalizedFocusY * bmHeight) - (cropHeightInSourcePx / 2f)

            // 5. 边界检查，确保裁剪区域不超出源图像边界
            srcX = srcX.coerceIn(0f, bmWidth - cropWidthInSourcePx)
            srcY = srcY.coerceIn(0f, bmHeight - cropHeightInSourcePx)
            // 再次确保裁剪宽高不超过源图像的剩余有效尺寸
            val finalCropWidth = min(cropWidthInSourcePx, bmWidth - srcX).roundToInt()
            val finalCropHeight = min(cropHeightInSourcePx, bmHeight - srcY).roundToInt()

            if (finalCropWidth > 0 && finalCropHeight > 0) {
                val croppedBmp = Bitmap.createBitmap(sourceBitmap, srcX.roundToInt(), srcY.roundToInt(), finalCropWidth, finalCropHeight)
                finalP1Bitmap = Bitmap.createScaledBitmap(croppedBmp, targetScreenWidth, targetP1ActualHeight, true)
                if (croppedBmp != finalP1Bitmap && !croppedBmp.isRecycled) croppedBmp.recycle()
            } else { Log.w(TAG, "preparePage1TopCroppedBitmap: Calculated crop dimensions are zero or negative.") }
        } catch (e: Exception) {
            Log.e(TAG, "Error in preparePage1TopCroppedBitmap (contentScale: $contentScaleFactor)", e)
            finalP1Bitmap?.recycle(); finalP1Bitmap = null
        }
        return finalP1Bitmap
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
        val originalBitmapHeight = sourceBitmap.height.toFloat() //修正：使用 sourceBitmap

        if (originalBitmapWidth <= 0 || originalBitmapHeight <= 0) return Pair(null, null)

        val scaleToFitScreenHeight = targetScreenHeight / originalBitmapHeight
        val scaledWidthForScrollingBg = (originalBitmapWidth * scaleToFitScreenHeight).roundToInt()

        if (scaledWidthForScrollingBg <= 0) return Pair(null, null)

        var baseScrollingBitmap: Bitmap? = null // 用于滚动的底图
        var downscaledForBlur: Bitmap? = null   // 为模糊而降采样的图
        var blurredDownscaled: Bitmap? = null   // 降采样后模糊的图
        var upscaledBlurredBitmap: Bitmap? = null // 模糊后又放大回来的图

        try {
            baseScrollingBitmap = Bitmap.createScaledBitmap(
                sourceBitmap,
                scaledWidthForScrollingBg,
                targetScreenHeight,
                true
            )
            finalScrollingBackground = baseScrollingBitmap // 至少这个是准备好了的

            if (blurRadius > 0.01f && baseScrollingBitmap != null && !baseScrollingBitmap.isRecycled) {
                val sourceForActualBlur = baseScrollingBitmap
                val actualDownscaleFactor = blurDownscaleFactor.coerceIn(0.05f, 1.0f)
                val downscaledWidth = (sourceForActualBlur.width * actualDownscaleFactor).roundToInt().coerceAtLeast(MIN_DOWNSCALED_DIMENSION)
                val downscaledHeight = (sourceForActualBlur.height * actualDownscaleFactor).roundToInt().coerceAtLeast(MIN_DOWNSCALED_DIMENSION)

                if (actualDownscaleFactor < 0.99f && (downscaledWidth < sourceForActualBlur.width || downscaledHeight < sourceForActualBlur.height)) {
                    downscaledForBlur = Bitmap.createScaledBitmap(sourceForActualBlur, downscaledWidth, downscaledHeight, true)
                    blurredDownscaled = blurBitmapUsingRenderScript(context, downscaledForBlur, blurRadius.coerceIn(0.1f, 25.0f), blurIterations)
                    if (blurredDownscaled != null) {
                        upscaledBlurredBitmap = Bitmap.createScaledBitmap(blurredDownscaled, sourceForActualBlur.width, sourceForActualBlur.height, true)
                        finalBlurredScrollingBackground = upscaledBlurredBitmap
                    } else {
                        // 降采样或模糊降采样图失败，直接在原始缩放图上模糊作为后备
                        Log.w(TAG, "Blurred downscaled bitmap is null, falling back to blur on base scrolling bitmap.")
                        finalBlurredScrollingBackground = blurBitmapUsingRenderScript(context, sourceForActualBlur, blurRadius, blurIterations)
                    }
                } else {
                    // 不需要降采样，直接模糊
                    finalBlurredScrollingBackground = blurBitmapUsingRenderScript(context, sourceForActualBlur, blurRadius, blurIterations)
                }
            } else if (baseScrollingBitmap != null && !baseScrollingBitmap.isRecycled) {
                // 不需要模糊，但要确保 finalBlurredScrollingBackground 不是 null (例如，可以复制 baseScrollingBitmap)
                // 或者保持为 null，取决于上层逻辑如何处理。通常如果没有模糊，它就是 null。
                // finalBlurredScrollingBackground = baseScrollingBitmap.copy(baseScrollingBitmap.config, true) // 如果不模糊时希望它等于原图
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during prepareScrollingAndBlurredBitmaps", e)
            // 出错时，回收所有中间产物，并将最终结果设为null
            upscaledBlurredBitmap?.recycle()
            blurredDownscaled?.recycle()
            downscaledForBlur?.recycle()
            // baseScrollingBitmap 可能是 finalScrollingBackground，也可能不是，要小心回收
            if (finalScrollingBackground != baseScrollingBitmap) finalScrollingBackground?.recycle() // 如果 final 和 base 不是同一个对象
            baseScrollingBitmap?.recycle() // base 总是要尝试回收，因为它在这里创建

            finalScrollingBackground = null
            finalBlurredScrollingBackground = null
        } finally {
            // 确保在 finally 中回收那些在 try 块中成功创建但在 catch 中可能未被处理的中间位图
            // blurredDownscaled 和 downscaledForBlur 应该只在它们不是最终返回的 upscaledBlurredBitmap 的一部分时回收
            // 但由于 upscaledBlurredBitmap 是从 blurredDownscaled 创建的，所以 blurredDownscaled 和 downscaledForBlur 在成功路径下可以被安全回收
            if (upscaledBlurredBitmap != blurredDownscaled) blurredDownscaled?.recycle() // 如果 upscaled 不是 blurredDownscaled 本身
            if (upscaledBlurredBitmap != downscaledForBlur && blurredDownscaled != downscaledForBlur) downscaledForBlur?.recycle() // 如果也不是 downscaledForBlur
        }
        return Pair(finalScrollingBackground, finalBlurredScrollingBackground)
    }

    fun loadAndProcessInitialBitmaps(
        context: Context, imageUri: Uri?, targetScreenWidth: Int, targetScreenHeight: Int,
        page1ImageHeightRatio: Float, normalizedFocusX: Float, normalizedFocusY: Float,
        contentScaleFactorForP1: Float, // 新增参数
        blurRadiusForBackground: Float, blurDownscaleFactor: Float, blurIterations: Int
    ): WallpaperBitmaps {
        if (imageUri == null || targetScreenWidth <= 0 || targetScreenHeight <= 0) return WallpaperBitmaps(null, null, null, null)
        var sourceSampled: Bitmap? = null
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(imageUri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return WallpaperBitmaps(null, null, null, null)
            opts.inSampleSize = calculateInSampleSize(opts, targetScreenWidth * 2, targetScreenHeight * 2)
            opts.inJustDecodeBounds = false; opts.inPreferredConfig = Bitmap.Config.ARGB_8888
            context.contentResolver.openInputStream(imageUri)?.use { sourceSampled = BitmapFactory.decodeStream(it, null, opts) }
            if (sourceSampled == null || sourceSampled!!.isRecycled) { Log.e(TAG, "Fail decode sourceSampled $imageUri"); return WallpaperBitmaps(null, null, null, null) }
            val topCropped = preparePage1TopCroppedBitmap(sourceSampled, targetScreenWidth, targetScreenHeight, page1ImageHeightRatio, normalizedFocusX, normalizedFocusY, contentScaleFactorForP1)
            val (scrolling, blurred) = prepareScrollingAndBlurredBitmaps(context, sourceSampled, targetScreenWidth, targetScreenHeight, blurRadiusForBackground, blurDownscaleFactor, blurIterations)
            return WallpaperBitmaps(sourceSampled, topCropped, scrolling, blurred)
        } catch (e: Exception) { Log.e(TAG, "Err loadAndProcessInitialBitmaps $imageUri", e); sourceSampled?.recycle(); return WallpaperBitmaps(null, null, null, null) }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (h, w) = options.outHeight to options.outWidth; var sample = 1
        if (h > reqHeight || w > reqWidth) { val halfH = h / 2; val halfW = w / 2
            while (halfH / sample >= reqHeight && halfW / sample >= reqWidth) { sample *= 2; if (sample > 8) break }
        }
        return sample
    }

    private fun blurBitmapUsingRenderScript(context: Context, bitmap: Bitmap, radius: Float, iterations: Int = 1): Bitmap? {
        if (bitmap.isRecycled || bitmap.width == 0 || bitmap.height == 0) return null
        val cRadius = radius.coerceIn(0.1f, 25.0f); val actualIter = iterations.coerceAtLeast(1)
        if (cRadius < 0.1f && actualIter == 1) { try { return bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true) } catch (e: Exception) { Log.e(TAG, "Fail copy no-blur", e); return null } }
        var rs: RenderScript? = null; var script: ScriptIntrinsicBlur? = null
        var currentBmp: Bitmap = bitmap; var outBmp: Bitmap? = null
        try {
            rs = RenderScript.create(context); script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs)); script.setRadius(cRadius)
            for (i in 0 until actualIter) {
                outBmp = Bitmap.createBitmap(currentBmp.width, currentBmp.height, currentBmp.config ?: Bitmap.Config.ARGB_8888)
                val ain = Allocation.createFromBitmap(rs, currentBmp); val aout = Allocation.createFromBitmap(rs, outBmp)
                script.setInput(ain); script.forEach(aout); aout.copyTo(outBmp)
                ain.destroy(); aout.destroy()
                if (currentBmp != bitmap && currentBmp != outBmp) currentBmp.recycle()
                currentBmp = outBmp!!
            }
            return currentBmp
        } catch (e: Exception) { Log.e(TAG, "RS blur fail", e); outBmp?.recycle(); if (currentBmp != bitmap && currentBmp != outBmp) currentBmp.recycle(); return null
        } finally { script?.destroy(); rs?.destroy() }
    }

    fun drawPlaceholder(canvas: Canvas, width: Int, height: Int, text: String) {
        if (width <= 0 || height <= 0) return
        placeholderBgPaint.alpha = 255; canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), placeholderBgPaint)
        placeholderTextPaint.alpha = 200; val textY = height / 2f - ((placeholderTextPaint.descent() + placeholderTextPaint.ascent()) / 2f)
        canvas.drawText(text, width / 2f, textY, placeholderTextPaint)
    }
}