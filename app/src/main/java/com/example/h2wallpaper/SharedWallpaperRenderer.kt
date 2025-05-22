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
        val scrollSensitivityFactor: Float = 1.0f,
        val normalizedInitialBgScrollOffset: Float = 0.0f, // <-- 新增的参数
        val p2BackgroundFadeInRatio: Float


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

    // 值越小，"糊"的程度越高，但也可能越失真
    private const val MIN_DOWNSCALED_DIMENSION = 16 // 缩小后图像的最小宽度/高度，防止过小

    fun drawFrame(
        canvas: Canvas,
        config: WallpaperConfig,
        bitmaps: WallpaperBitmaps
    ) {
        if (config.screenWidth <= 0 || config.screenHeight <= 0) {
            Log.w(TAG, "drawFrame: Screen dimensions are zero, cannot draw.")
            return
        }

        canvas.drawColor(config.page1BackgroundColor) // 清除画布，作为最底层背景

        val safeNumVirtualPages = config.numVirtualPages.coerceAtLeast(1)
        var p1OverlayAlpha = 255
        var p2BackgroundAlpha = 255

        // --- P1 图层透明度计算 ---
        // P1的淡出效果由 config.p1OverlayFadeTransitionRatio 控制
        if (safeNumVirtualPages > 1) {
            if (config.p1OverlayFadeTransitionRatio > 0.01f) { // 仅当ratio大于一个小阈值时才应用过渡
                // 基准区间计算方式保持与之前一致，这决定了过渡效果发生的XOffset范围
                // 例如，如果 numVirtualPages = 3, ratio = 0.5, 则淡出效果发生在 XOffset 从 0 到 (1/3 * 0.5) = 1/6 的区间内。
                val p1SinglePageXOffsetRange = 1.0f / safeNumVirtualPages.toFloat()
                val p1FadeEffectMaxXOffset = p1SinglePageXOffsetRange * config.p1OverlayFadeTransitionRatio.coerceIn(0.01f, 1f)

                if (config.currentXOffset < p1FadeEffectMaxXOffset) {
                    val p1TransitionProgress = config.currentXOffset / p1FadeEffectMaxXOffset
                    // P1 淡出曲线: (1 - progress^2)
                    p1OverlayAlpha = (255 * (1.0f - p1TransitionProgress.toDouble().pow(2.0))).toInt().coerceIn(0, 255)
                } else {
                    p1OverlayAlpha = 0 // P1 完全淡出
                }
            } else { // p1OverlayFadeTransitionRatio 配置为0或非常小，P1采用瞬间切换
                p1OverlayAlpha = if (config.currentXOffset == 0f) 255 else 0
            }
        } else { // safeNumVirtualPages == 1 (单页模式)
            p1OverlayAlpha = 255 // P1 始终完全可见
        }

        // --- P2 背景图层透明度计算 ---
        // P2的淡入效果由 config.p2BackgroundFadeInRatio 控制
        if (safeNumVirtualPages > 1) {
            if (config.p2BackgroundFadeInRatio > 0.01f) { // P2有独立的淡入过渡配置
                // 使用与P1类似的逻辑计算P2的过渡区间
                val p2SinglePageXOffsetRange = 1.0f / safeNumVirtualPages.toFloat()
                val p2FadeEffectMaxXOffset = p2SinglePageXOffsetRange * config.p2BackgroundFadeInRatio.coerceIn(0.01f, 1f)

                if (config.currentXOffset < p2FadeEffectMaxXOffset) {
                    val p2TransitionProgress = config.currentXOffset / p2FadeEffectMaxXOffset
                    // P2 淡入曲线: progress^2 (使其在形式上与P1的淡出曲线对称，可按需调整)
                    p2BackgroundAlpha = (255 * p2TransitionProgress.toDouble().pow(2.0)).toInt().coerceIn(0, 255)
                } else {
                    p2BackgroundAlpha = 255 // P2 完全可见
                }
            } else { // P2 配置为不进行淡入过渡 (p2BackgroundFadeInRatio 约等于 0)
                // 在这种情况下，P2 的行为需要考虑 P1 的配置：
                // 1. 如果 P1 也配置为瞬间切换 (p1OverlayFadeTransitionRatio 约等于 0)
                //    则 P2 也瞬间切换，与 P1 可见性相反 (P1可见时P2不可见，反之亦然)。
                if (config.p1OverlayFadeTransitionRatio <= 0.01f) {
                    p2BackgroundAlpha = if (config.currentXOffset == 0f) 0 else 255
                } else {
                    // 2. 如果 P1 有自己的淡出过渡，而 P2 配置为不淡入（或瞬间完成），
                    //    那么 P2 应该始终保持完全可见，P1 在其上层进行淡出。
                    p2BackgroundAlpha = 255
                }
            }
        } else { // safeNumVirtualPages == 1 (单页模式)
            p2BackgroundAlpha = 255 // P2 背景在单页模式下始终完全可见 (P1会绘制在它之上)
        }

        // 背景图绘制逻辑
        val backgroundToDraw = bitmaps.blurredScrollingBackgroundBitmap ?: bitmaps.scrollingBackgroundBitmap
        backgroundToDraw?.let { bgBmp ->
            if (!bgBmp.isRecycled && bgBmp.width > 0 && bgBmp.height > 0) {
                val imageActualWidth = bgBmp.width.toFloat() //
                val screenActualWidth = config.screenWidth.toFloat() //

                // 背景滚动计算逻辑 (保持不变)
                val maxInitialPixelOffset = (imageActualWidth - screenActualWidth).coerceAtLeast(0f) //
                val safeInitialPixelOffset = (imageActualWidth * config.normalizedInitialBgScrollOffset).coerceIn(0f, maxInitialPixelOffset) //
                val totalScrollToAlignRightEdge = (imageActualWidth - screenActualWidth).coerceAtLeast(0f) //
                val dynamicScrollableRange = (totalScrollToAlignRightEdge - safeInitialPixelOffset).coerceAtLeast(0f) //
                val currentDynamicScroll = config.currentXOffset * (dynamicScrollableRange * config.scrollSensitivityFactor) //
                var currentScrollPxFloat = safeInitialPixelOffset + currentDynamicScroll //
                currentScrollPxFloat = currentScrollPxFloat.coerceIn(safeInitialPixelOffset, totalScrollToAlignRightEdge) //
                currentScrollPxFloat = currentScrollPxFloat.coerceAtLeast(0f) //

                val bgTopOffset = ((config.screenHeight - bgBmp.height) / 2f)
                canvas.save()
                canvas.translate(-currentScrollPxFloat, bgTopOffset)

                // 应用计算得到的 P2 背景透明度
                scrollingBgPaint.alpha = p2BackgroundAlpha
                canvas.drawBitmap(bgBmp, 0f, 0f, scrollingBgPaint)
                canvas.restore()

                Log.d(DEBUG_TAG_RENDERER, "drawFrame: P1 Alpha=$p1OverlayAlpha, P2 BG Alpha=$p2BackgroundAlpha, XOffset=${config.currentXOffset}, P1FadeRatio=${config.p1OverlayFadeTransitionRatio}, P2FadeInRatio=${config.p2BackgroundFadeInRatio}")

            } else {
                Log.w(TAG, "drawFrame: Background bitmap is invalid or recycled.")
            }
        } ?: Log.d(TAG, "drawFrame: No background bitmap available to draw.")

        // P1 前景图和背景色绘制逻辑 (这部分逻辑保持不变, P1 使用 p1OverlayAlpha)
        val topImageActualHeight = (config.screenHeight * config.page1ImageHeightRatio).toInt() //
        if (p1OverlayAlpha > 0 && topImageActualHeight > 0) { //
            // P1 自身的背景色 (绘制在P2图层之上，P1图片之下)
            p1OverlayBgPaint.color = config.page1BackgroundColor //
            p1OverlayBgPaint.alpha = p1OverlayAlpha // P1背景色使用P1的透明度
            canvas.drawRect( //
                0f, //
                topImageActualHeight.toFloat(), //
                config.screenWidth.toFloat(), //
                config.screenHeight.toFloat(), //
                p1OverlayBgPaint //
            )

            // P1 前景图片
            bitmaps.page1TopCroppedBitmap?.let { topBmp -> //
                if (!topBmp.isRecycled && topBmp.width > 0 && topBmp.height > 0) { //
                    p1OverlayImagePaint.alpha = p1OverlayAlpha // P1图片使用P1的透明度
                    canvas.drawBitmap(topBmp, 0f, 0f, p1OverlayImagePaint) //
                } else {
                    Log.w(TAG, "drawFrame: P1 top cropped bitmap is invalid or recycled.")
                }
            } ?: run { //
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
        blurRadius: Float,
        // --- 新增的参数，由高级设置控制 ---
        blurDownscaleFactor: Float, // 例如 0.05f 到 1.0f (1.0f 表示不额外降采样)
        blurIterations: Int         // 例如 1 到 3 次
    ): Pair<Bitmap?, Bitmap?> {
        if (sourceBitmap == null || sourceBitmap.isRecycled) {
            Log.w(TAG, "prepareScrollingAndBlurredBitmaps: Source bitmap is null or recycled.")
            return Pair(null, null)
        }
        if (targetScreenWidth <= 0 || targetScreenHeight <= 0) {
            Log.w(TAG, "prepareScrollingAndBlurredBitmaps: Invalid target dimensions.")
            return Pair(null, null)
        }

        var finalScrollingBackground: Bitmap? = null // 这是未模糊的、按屏幕高度缩放的滚动背景
        var finalBlurredScrollingBackground: Bitmap? = null // 这是最终模糊处理后的滚动背景

        val originalBitmapWidth = sourceBitmap.width.toFloat()
        val originalBitmapHeight = sourceBitmap.height.toFloat()

        if (originalBitmapWidth <= 0 || originalBitmapHeight <= 0) {
            Log.w(TAG, "prepareScrollingAndBlurredBitmaps: Source bitmap has invalid dimensions.")
            return Pair(null, null)
        }

        // 1. 计算按屏幕高度缩放后的滚动背景图尺寸
        val scaleToFitScreenHeight = targetScreenHeight / originalBitmapHeight
        val scaledWidthForScrollingBg = (originalBitmapWidth * scaleToFitScreenHeight).roundToInt()

        if (scaledWidthForScrollingBg <= 0) {
            Log.w(TAG, "prepareScrollingAndBlurredBitmaps: Calculated scaledOriginalWidth is zero or less.")
            return Pair(null, null)
        }

        // 用于存储中间步骤的位图，确保它们在不再需要时被回收
        var baseScrollingBitmap: Bitmap? = null // 即 tempScaledBitmap，按屏幕高度缩放后的图
        var downscaledForBlur: Bitmap? = null
        var blurredDownscaled: Bitmap? = null
        var upscaledBlurredBitmap: Bitmap? = null // 最终由小图放大回来的模糊图

        try {
            // 2. 创建基础的滚动背景图 (按屏幕高度缩放)
            baseScrollingBitmap = Bitmap.createScaledBitmap(sourceBitmap, scaledWidthForScrollingBg, targetScreenHeight, true)
            finalScrollingBackground = baseScrollingBitmap // 未模糊的滚动背景图赋值

            // 3. 如果需要模糊 (blurRadius > 0)，则进行模糊处理
            if (blurRadius > 0.01f && baseScrollingBitmap != null && !baseScrollingBitmap.isRecycled) {
                val sourceForActualBlur = baseScrollingBitmap // 将以此为基础进行模糊处理

                // 应用降采样模糊策略
                // actualDownscaleFactor 控制降采样的程度，值越小，图片越小，模糊感越强
                val actualDownscaleFactor = blurDownscaleFactor.coerceIn(0.05f, 1.0f) // 限制在合理范围

                val downscaledWidth = (sourceForActualBlur.width * actualDownscaleFactor)
                    .roundToInt().coerceAtLeast(MIN_DOWNSCALED_DIMENSION)
                val downscaledHeight = (sourceForActualBlur.height * actualDownscaleFactor)
                    .roundToInt().coerceAtLeast(MIN_DOWNSCALED_DIMENSION)

                // 条件：当降采样因子显著小于1 (表示希望降采样) 并且
                // 计算出的降采样后尺寸确实小于原待模糊图像尺寸时，才执行降采样路径。
                if (actualDownscaleFactor < 0.99f && (downscaledWidth < sourceForActualBlur.width || downscaledHeight < sourceForActualBlur.height)) {
                    Log.d(TAG, "Applying enhanced blur: Downscale by x$actualDownscaleFactor, Iterations: $blurIterations")
                    downscaledForBlur = Bitmap.createScaledBitmap(sourceForActualBlur, downscaledWidth, downscaledHeight, true)

                    val radiusForDownscaledBitmap = blurRadius.coerceIn(0.1f, 25.0f)
                    blurredDownscaled = blurBitmapUsingRenderScript(context, downscaledForBlur, radiusForDownscaledBitmap, blurIterations)

                    if (blurredDownscaled != null) {
                        upscaledBlurredBitmap = Bitmap.createScaledBitmap(
                            blurredDownscaled,
                            sourceForActualBlur.width, // 放大回 baseScrollingBitmap 的尺寸
                            sourceForActualBlur.height,
                            true // 使用双线性过滤
                        )
                        finalBlurredScrollingBackground = upscaledBlurredBitmap
                    } else {
                        Log.w(TAG, "Enhanced blur: Blurring on downscaled image failed. Falling back to standard blur on original-scale.")
                        // 如果在缩小图上模糊失败，则在原始缩放尺寸的图上进行模糊作为后备
                        finalBlurredScrollingBackground = blurBitmapUsingRenderScript(context, sourceForActualBlur, blurRadius, blurIterations)
                    }
                } else {
                    // 如果不满足降采样条件 (例如因子接近1，或图片本身已经很小)
                    // 直接在 baseScrollingBitmap 上进行标准模糊（但仍使用迭代次数）
                    Log.d(TAG, "Applying standard blur (no effective downscale or factor too high). Iterations: $blurIterations")
                    finalBlurredScrollingBackground = blurBitmapUsingRenderScript(context, sourceForActualBlur, blurRadius, blurIterations)
                }
            } else if (baseScrollingBitmap != null && !baseScrollingBitmap.isRecycled) {
                // 不需要模糊 (blurRadius 为0或非常小)
                // finalBlurredScrollingBackground 保持为 null，在 drawFrame 中会用 finalScrollingBackground
                Log.d(TAG, "No blur applied as blurRadius ($blurRadius) is too small or zero.")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error during prepareScrollingAndBlurredBitmaps", e)
            // 如果发生异常，确保最终返回的 blurredBackground 是 null
            // 已经创建的 finalScrollingBackground (baseScrollingBitmap) 仍可能有效，其回收由WallpaperBitmaps管理
            upscaledBlurredBitmap?.recycle() // 如果这个中间Bitmap已创建
            finalBlurredScrollingBackground = null // 清空，表示模糊失败
        } finally {
            // 回收在此函数作用域内明确创建且未赋给最终返回对象的中间Bitmap
            // baseScrollingBitmap 赋值给了 finalScrollingBackground，由外部管理
            // upscaledBlurredBitmap 赋值给了 finalBlurredScrollingBackground，由外部管理
            if (downscaledForBlur != blurredDownscaled) { // 避免重复回收（如果blurBitmapUsingRenderScript返回输入对象的情况，虽然我们改了）
                downscaledForBlur?.recycle()
            }
            blurredDownscaled?.recycle()
            Log.d(TAG, "Intermediate bitmaps for blur (downscaled, blurredDownscaled) recycled if they were created.")
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
        blurRadiusForBackground: Float,
        blurDownscaleFactor: Float,
        blurIterations: Int
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
                blurRadiusForBackground,
                blurDownscaleFactor,
                blurIterations,
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

    // blurBitmapUsingRenderScript 函数保持不变 (确保其内部对 radius 的 clamp 是 0.1f 到 25.0f)
    private fun blurBitmapUsingRenderScript(
        context: Context,
        bitmap: Bitmap, // 原始输入 bitmap，此函数不应回收它
        radius: Float,
        iterations: Int = 1 // 迭代次数，默认为1
    ): Bitmap? {
        // 检查输入 bitmap 是否有效
        if (bitmap.isRecycled || bitmap.width == 0 || bitmap.height == 0) {
            Log.w(TAG, "blurBitmapUsingRenderScript: Input bitmap is invalid or recycled.")
            return null
        }

        val clampedRadius = radius.coerceIn(0.1f, 25.0f) // RenderScript Blur 最大半径 25f
        val actualIterations = iterations.coerceAtLeast(1) // 确保至少迭代1次

        // 如果有效模糊半径过小且只迭代一次，直接返回原始bitmap的一个副本，以节省开销
        if (clampedRadius < 0.1f && actualIterations == 1) {
            Log.d(TAG, "blurBitmapUsingRenderScript: Radius ($clampedRadius) too small for single iteration, returning a copy.")
            return try {
                bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy bitmap when radius was too small", e)
                null // 复制失败则返回null
            }
        }

        var rs: RenderScript? = null
        var script: ScriptIntrinsicBlur? = null
        var currentProcessingBitmap: Bitmap = bitmap // 用于迭代的当前位图，初始为输入bitmap
        var iterationOutputBitmap: Bitmap? = null  // 每次迭代的输出位图

        try {
            rs = RenderScript.create(context)
            script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
            script.setRadius(clampedRadius)

            for (i in 0 until actualIterations) {
                Log.d(TAG, "Blur iteration ${i + 1} of $actualIterations with radius $clampedRadius")

                // 为当前迭代的输出创建一个新的 Bitmap
                // 注意：currentProcessingBitmap 在第一次迭代时是原始 bitmap，
                // 后续迭代时是上一次迭代产生的 iterationOutputBitmap。
                iterationOutputBitmap = Bitmap.createBitmap(
                    currentProcessingBitmap.width,
                    currentProcessingBitmap.height,
                    currentProcessingBitmap.config ?: Bitmap.Config.ARGB_8888
                )

                var inAlloc: Allocation? = null
                var outAlloc: Allocation? = null

                try {
                    inAlloc = Allocation.createFromBitmap(rs, currentProcessingBitmap) // 本次迭代的输入
                    outAlloc = Allocation.createFromBitmap(rs, iterationOutputBitmap) // 本次迭代的输出目标

                    script.setInput(inAlloc)
                    script.forEach(outAlloc)    // 执行模糊
                    outAlloc.copyTo(iterationOutputBitmap) // 将结果复制到 iterationOutputBitmap
                } finally {
                    inAlloc?.destroy()
                    outAlloc?.destroy()
                }

                // 如果 currentProcessingBitmap 不是最初传入的 bitmap (即它是一个中间产物)，
                // 那么它在本次迭代后就不再需要了，应该被回收。
                if (currentProcessingBitmap != bitmap) {
                    Log.d(TAG, "Recycling intermediate bitmap from iteration ${i + 1}")
                    currentProcessingBitmap.recycle()
                }
                currentProcessingBitmap = iterationOutputBitmap // 本次迭代的输出成为下一次迭代的输入
            }
            // 循环结束后，currentProcessingBitmap 持有的是最终的模糊结果
            return currentProcessingBitmap

        } catch (e: Exception) { // 通用异常捕获
            Log.e(TAG, "RenderScript blur failed during iterations (radius: $clampedRadius, iterations: $actualIterations).", e)

            // 发生异常时，我们需要清理掉在try块中可能创建的Bitmap
            // iterationOutputBitmap 是最后一次尝试创建或赋值的输出Bitmap
            iterationOutputBitmap?.recycle()

            // 如果 currentProcessingBitmap 不是原始传入的 bitmap，并且它也不是 iterationOutputBitmap
            // (或者 iterationOutputBitmap 为null)，那么 currentProcessingBitmap 是一个需要清理的中间状态。
            // 但更简单的是：如果 currentProcessingBitmap 不是原始的 bitmap，就意味着它是函数内部创建的，
            // 既然出错了，就应该回收它。
            if (currentProcessingBitmap != bitmap) {
                currentProcessingBitmap.recycle()
            }
            return null // 出错则返回null
        } finally {
            script?.destroy()
            rs?.destroy()
            Log.d(TAG, "RenderScript resources (script, rs) destroyed.")
        }
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