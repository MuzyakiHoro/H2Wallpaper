// SharedWallpaperRenderer.kt
package com.example.h2wallpaper

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.renderscript.* // 需要在 build.gradle 中启用 renderscriptSupportModeEnabled true 和 renderscriptTargetApi
import android.util.Log
import kotlin.math.min
import kotlin.math.max // 需要导入 max
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
    const val DEFAULT_BACKGROUND_BLUR_RADIUS = 25f // 保持公开，Service可能会用

    data class WallpaperBitmaps(
        var sourceSampledBitmap: Bitmap?,            // 采样后的原始完整图
        var page1TopCroppedBitmap: Bitmap?,         // P1 前景图 (已裁剪和缩放)
        var scrollingBackgroundBitmap: Bitmap?,     // P2 滚动背景图 (可能已平铺)
        var blurredScrollingBackgroundBitmap: Bitmap? // P2 滚动背景的模糊版本
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
        val currentXOffset: Float, // 当前总的滚动偏移 (0.0 到 1.0)
        val numVirtualPages: Int = 3,
        val p1OverlayFadeTransitionRatio: Float = 0.5f, // P1叠加层在此比例的第一页滑动距离内完成淡出
        // val p2BackgroundFadeInRatio: Float = 0.5f, // P2层现在总是作为底图，这个参数作用不大
        val scrollSensitivityFactor: Float = 1.0f
    )

    // Paint 对象，可以复用
    private val scrollingBgPaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
    private val p1OverlayBgPaint = Paint() // 用于P1底部颜色块
    private val p1OverlayImagePaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true } // 用于P1顶部图片
    private val placeholderTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    private val placeholderBgPaint = Paint().apply {
        color = Color.DKGRAY // 占位符背景色
    }

    /**
     * 绘制一帧壁纸。
     */
    fun drawFrame(
        canvas: Canvas,
        config: WallpaperConfig,
        bitmaps: WallpaperBitmaps
    ) {
        if (config.screenWidth <= 0 || config.screenHeight <= 0) {
            Log.w(TAG, "drawFrame: Screen dimensions are zero, cannot draw.")
            return
        }

        canvas.drawColor(Color.BLACK) // 1. 清空画布为黑色 (或config.page1BackgroundColor作为最底层颜色)

        // --- 计算P1叠加层的Alpha值 ---
        var p1OverlayAlpha = 255 // P1 叠加层的 alpha (0-255)
        val safeNumVirtualPages = config.numVirtualPages.coerceAtLeast(1)

        if (safeNumVirtualPages > 1 && config.p1OverlayFadeTransitionRatio > 0) {
            val singlePageXOffsetRange = 1.0f / safeNumVirtualPages.toFloat() // 单个虚拟页对应的偏移范围
            val p1FadeOutEndXOffset = singlePageXOffsetRange * config.p1OverlayFadeTransitionRatio.coerceIn(0.01f, 1f)

            if (config.currentXOffset < p1FadeOutEndXOffset) {
                val transitionProgress = config.currentXOffset / p1FadeOutEndXOffset
                // 使用 (1-t)^2 的曲线，使得淡出在开始时较慢，结束时较快
                p1OverlayAlpha = (255 * (1.0f - transitionProgress.toDouble().pow(2.0))).toInt().coerceIn(0, 255)
            } else {
                p1OverlayAlpha = 0 // 超出过渡范围则完全透明
            }
        } else if (safeNumVirtualPages == 1) {
            p1OverlayAlpha = 255 // 单页时P1总是完全不透明
        } else { // numVirtualPages < 1 或 p1OverlayFadeTransitionRatio <= 0
            p1OverlayAlpha = if (config.currentXOffset == 0f) 255 else 0 // 简单处理：只在第一页显示P1
        }
        // Log.d(DEBUG_TAG_RENDERER, "drawFrame: currentXOffset=${config.currentXOffset}, p1OverlayAlpha=$p1OverlayAlpha")


        // --- 绘制 P2 滚动背景层 ---
        // P2层作为基底，始终不透明绘制。使用模糊背景（如果存在），否则用普通滚动背景。
        val backgroundToDraw = bitmaps.blurredScrollingBackgroundBitmap ?: bitmaps.scrollingBackgroundBitmap
        backgroundToDraw?.let { bgBmp ->
            if (!bgBmp.isRecycled && bgBmp.width > 0 && bgBmp.height > 0) {
                // 计算总的可滚动宽度，考虑灵敏度因子
                val baseTotalScrollableWidth = (bgBmp.width - config.screenWidth).coerceAtLeast(0)
                val effectiveTotalScrollableWidth = (baseTotalScrollableWidth * config.scrollSensitivityFactor)
                var currentScrollPx = (config.currentXOffset * effectiveTotalScrollableWidth).toInt()
                currentScrollPx = currentScrollPx.coerceIn(0, baseTotalScrollableWidth) // 确保滚动不超过实际可滚动范围

                val bgTopOffset = ((config.screenHeight - bgBmp.height) / 2f) // 使背景垂直居中
                canvas.save()
                canvas.translate(-currentScrollPx.toFloat(), bgTopOffset)
                scrollingBgPaint.alpha = 255 // P2背景始终不透明
                canvas.drawBitmap(bgBmp, 0f, 0f, scrollingBgPaint)
                canvas.restore()
            } else {
                Log.w(TAG, "drawFrame: P2 background bitmap is invalid or recycled.")
            }
        } ?: Log.d(TAG, "drawFrame: No P2 background bitmap available to draw.")


        // --- 绘制 P1 叠加层 ---
        val topImageActualHeight = (config.screenHeight * config.page1ImageHeightRatio).toInt()
        if (p1OverlayAlpha > 0 && topImageActualHeight > 0) {
            // 绘制P1的底部颜色部分 (在图片下方)
            p1OverlayBgPaint.color = config.page1BackgroundColor
            p1OverlayBgPaint.alpha = p1OverlayAlpha // P1底部颜色的透明度
            canvas.drawRect(
                0f,
                topImageActualHeight.toFloat(),
                config.screenWidth.toFloat(),
                config.screenHeight.toFloat(),
                p1OverlayBgPaint
            )

            // 绘制P1的顶部图片部分
            bitmaps.page1TopCroppedBitmap?.let { topBmp ->
                if (!topBmp.isRecycled && topBmp.width > 0 && topBmp.height > 0) {
                    p1OverlayImagePaint.alpha = p1OverlayAlpha // P1顶部图片的透明度
                    canvas.drawBitmap(topBmp, 0f, 0f, p1OverlayImagePaint) // P1图从(0,0)开始绘制
                } else {
                    Log.w(TAG, "drawFrame: P1 top cropped bitmap is invalid or recycled.")
                    // 可以选择在这里绘制一个P1图片的占位符
                    // drawPlaceholderForP1Overlay(canvas, config.screenWidth, topImageActualHeight, "P1图错误", p1OverlayAlpha)
                }
            } ?: run {
                Log.d(TAG, "drawFrame: No P1 top cropped bitmap available to draw.")
                // 如果顶部图片为空但有源图，可能在加载中，或者P1高度为0
                // drawPlaceholderForP1Overlay(canvas, config.screenWidth, topImageActualHeight, "P1图加载中", p1OverlayAlpha)
            }
        }
        // Log.d(DEBUG_TAG_RENDERER, "drawFrame END.")
    }

    /**
     * 准备P1前景顶图。
     * 根据源图、目标屏幕尺寸、P1图片高度比例以及归一化焦点来裁剪和缩放。
     */
    fun preparePage1TopCroppedBitmap(
        sourceBitmap: Bitmap?,
        targetScreenWidth: Int,
        targetScreenHeight: Int,
        page1ImageHeightRatio: Float,
        normalizedFocusX: Float = 0.5f,
        normalizedFocusY: Float = 0.5f
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
            // P1小窗的目标宽高比
            val targetP1AspectRatio = targetScreenWidth.toFloat() / targetP1ActualHeight.toFloat()
            // 源图的宽高比
            val sourceBitmapAspectRatio = bmWidth.toFloat() / bmHeight.toFloat()

            var cropWidth: Int
            var cropHeight: Int

            // 计算从源图中需要裁剪出的部分的尺寸 (cropWidth, cropHeight)
            // 这个计算的目标是，裁剪出的这部分 (cropWidth x cropHeight) 在经过缩放后，
            // 能够以 CENTER_CROP 的方式填满 targetScreenWidth x targetP1ActualHeight 的区域。
            if (sourceBitmapAspectRatio > targetP1AspectRatio) {
                // 源图比目标P1区域更“宽”，所以P1的高度将决定裁剪高度，宽度按比例裁剪
                cropHeight = bmHeight
                cropWidth = (bmHeight * targetP1AspectRatio).roundToInt()
            } else {
                // 源图比目标P1区域更“高”或宽高比相同，所以P1的宽度将决定裁剪宽度，高度按比例裁剪
                cropWidth = bmWidth
                cropHeight = (bmWidth / targetP1AspectRatio).roundToInt()
            }
            // 确保裁剪尺寸不超过源图尺寸（虽然理论上上面的计算方式能保证）
            cropWidth = cropWidth.coerceAtMost(bmWidth)
            cropHeight = cropHeight.coerceAtMost(bmHeight)


            var srcX: Int
            var srcY: Int

            // 根据焦点参数调整 srcX 和 srcY
            // 可平移范围
            val pannableWidth = (bmWidth - cropWidth).coerceAtLeast(0)
            val pannableHeight = (bmHeight - cropHeight).coerceAtLeast(0)

            srcX = (pannableWidth * normalizedFocusX.coerceIn(0f,1f)).roundToInt()
            srcY = (pannableHeight * normalizedFocusY.coerceIn(0f,1f)).roundToInt()

            // 再次确保 srcX, srcY, cropWidth, cropHeight 在有效范围内
            srcX = srcX.coerceIn(0, bmWidth - cropWidth) // cropWidth 此时是基于原图比例计算的，可能比bmWidth小
            srcY = srcY.coerceIn(0, bmHeight - cropHeight)


            val finalCropWidth = cropWidth.coerceAtLeast(1) // 确保不为0
            val finalCropHeight = cropHeight.coerceAtLeast(1) // 确保不为0

            // 确保裁剪区域不超出源图边界 (这个很重要)
            if (srcX + finalCropWidth > bmWidth) {
                Log.w(TAG, "Adjusting cropWidth due to boundary: ${srcX + finalCropWidth} > $bmWidth")
                // 这通常不应该发生，如果 cropWidth 是基于aspect ratio正确计算的话
                // 但作为保险，可以 srcX = bmWidth - finalCropWidth 或者重新调整
            }
            if (srcY + finalCropHeight > bmHeight) {
                Log.w(TAG, "Adjusting cropHeight due to boundary: ${srcY + finalCropHeight} > $bmHeight")
            }


            Log.d(TAG, "preparePage1TopCroppedBitmap: src($srcX,$srcY), crop($finalCropWidth,$finalCropHeight) from ${bmWidth}x${bmHeight} for target ${targetScreenWidth}x${targetP1ActualHeight}")

            if (finalCropWidth > 0 && finalCropHeight > 0) {
                val cropped = Bitmap.createBitmap(sourceBitmap, srcX, srcY, finalCropWidth, finalCropHeight)
                // 现在将这个裁剪下来的 `cropped` Bitmap 缩放到 P1 小窗的实际目标尺寸
                page1TopCropped = Bitmap.createScaledBitmap(cropped, targetScreenWidth, targetP1ActualHeight, true)
                if (cropped != page1TopCropped && !cropped.isRecycled) {
                    cropped.recycle()
                }
                Log.d(TAG, "Page1 top cropped bitmap created: ${page1TopCropped.width}x${page1TopCropped.height}")
            } else {
                Log.w(TAG, "preparePage1TopCroppedBitmap: Calculated finalCropWidth or finalCropHeight is zero or less.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating page1TopCroppedBitmap", e)
            page1TopCropped?.recycle()
            page1TopCropped = null
        }
        return page1TopCropped
    }

    /**
     * 准备P2滚动背景图和其模糊版本。
     */
    fun prepareScrollingAndBlurredBitmaps(
        context: Context,
        sourceBitmap: Bitmap?,
        targetScreenWidth: Int,
        targetScreenHeight: Int,
        numVirtualPages: Int,
        blurRadius: Float
    ): Pair<Bitmap?, Bitmap?> {
        if (sourceBitmap == null || sourceBitmap.isRecycled) {
            Log.w(TAG, "prepareScrollingAndBlurredBitmaps: Source bitmap is null or recycled.")
            return Pair(null, null)
        }
        if (targetScreenWidth <= 0 || targetScreenHeight <= 0 || numVirtualPages <= 0) {
            Log.w(TAG, "prepareScrollingAndBlurredBitmaps: Invalid target dimensions or page count.")
            return Pair(null, null)
        }

        var scrollingBackground: Bitmap? = null
        var blurredScrollingBackground: Bitmap? = null
        val bgTargetHeight = targetScreenHeight // 背景图的高度通常等于屏幕高度
        val obW = sourceBitmap.width.toFloat()
        val obH = sourceBitmap.height.toFloat()

        if (obW > 0 && obH > 0) {
            // 缩放原始图片，使其高度等于屏幕高度，宽度等比缩放
            val scaleToFitScreenHeight = bgTargetHeight / obH
            val scaledOriginalWidth = (obW * scaleToFitScreenHeight).roundToInt()

            if (scaledOriginalWidth > 0) {
                var tempScaledBitmap: Bitmap? = null
                try {
                    tempScaledBitmap = Bitmap.createScaledBitmap(sourceBitmap, scaledOriginalWidth, bgTargetHeight, true)

                    val actualNumVirtualPages = numVirtualPages.coerceAtLeast(1)
                    // 背景最终目标宽度：至少是屏幕宽度的 actualNumVirtualPages 倍，
                    // 或者至少和缩放后的原图一样宽（如果原图已经很宽），
                    // 并且至少和屏幕一样宽。
                    val bgFinalTargetWidth = (targetScreenWidth * actualNumVirtualPages)
                        .coerceAtLeast(scaledOriginalWidth)
                        .coerceAtLeast(targetScreenWidth)

                    scrollingBackground = Bitmap.createBitmap(bgFinalTargetWidth, bgTargetHeight, Bitmap.Config.ARGB_8888)
                    val canvasForScrollingBg = Canvas(scrollingBackground)
                    var currentX = 0

                    if (scaledOriginalWidth >= bgFinalTargetWidth) {
                        // 如果缩放后的图片已经比最终目标宽度还要宽或相等，从中居中裁剪
                        val offsetX = (scaledOriginalWidth - bgFinalTargetWidth) / 2
                        val srcRect = Rect(offsetX, 0, offsetX + bgFinalTargetWidth, bgTargetHeight)
                        val dstRect = Rect(0, 0, bgFinalTargetWidth, bgTargetHeight)
                        canvasForScrollingBg.drawBitmap(tempScaledBitmap, srcRect, dstRect, null)
                    } else {
                        // 如果缩放后的图片比最终目标宽度窄，则需要平铺
                        while (currentX < bgFinalTargetWidth) {
                            canvasForScrollingBg.drawBitmap(tempScaledBitmap, currentX.toFloat(), 0f, null)
                            currentX += tempScaledBitmap.width
                            if (tempScaledBitmap.width <= 0) break // 防止死循环
                        }
                    }

                    if (blurRadius > 0f && scrollingBackground?.isRecycled == false) {
                        blurredScrollingBackground = blurBitmapUsingRenderScript(context, scrollingBackground, blurRadius)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating scrolling or blurred background", e)
                    scrollingBackground?.recycle(); scrollingBackground = null
                    blurredScrollingBackground?.recycle(); blurredScrollingBackground = null
                } finally {
                    // 回收临时缩放的位图，如果它不是源位图本身
                    if (tempScaledBitmap != null && tempScaledBitmap != sourceBitmap && !tempScaledBitmap.isRecycled) {
                        tempScaledBitmap.recycle()
                    }
                }
            } else {
                Log.w(TAG, "prepareScrollingAndBlurredBitmaps: Scaled original width is zero or less.")
            }
        }
        return Pair(scrollingBackground, blurredScrollingBackground)
    }

    /**
     * 从URI加载并处理所有初始位图 (P1前景, P2背景, P2模糊背景)。
     */
    fun loadAndProcessInitialBitmaps(
        context: Context,
        imageUri: Uri?,
        targetScreenWidth: Int,
        targetScreenHeight: Int,
        page1ImageHeightRatio: Float,
        normalizedFocusX: Float, // 新增
        normalizedFocusY: Float, // 新增
        numVirtualPagesForScrolling: Int,
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
            // 首次解码获取尺寸
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(imageUri)?.use { BitmapFactory.decodeStream(it, null, options) }

            if (options.outWidth <= 0 || options.outHeight <= 0) {
                Log.e(TAG, "loadAndProcessInitialBitmaps: Image bounds invalid after first decode. URI: $imageUri")
                return WallpaperBitmaps(null, null, null, null)
            }

            // 计算采样率，目标是加载一个足够大的位图用于后续所有处理
            // P1顶图需要适配屏幕宽度，P2背景图可能需要更宽
            val requiredWidthForP1 = targetScreenWidth
            val requiredHeightForP1 = (targetScreenHeight * page1ImageHeightRatio).roundToInt()
            // P2背景图的高度是屏幕高度，宽度可能需要 numVirtualPagesForScrolling * targetScreenWidth
            // 为了采样，我们以P1和P2中对原始图片细节要求更高的一方为准
            // 通常P1前景图对细节要求更高。
            // 一个简化的采样目标：让图片的较短边不小于屏幕的较短边（或P1高度），或者最大边不超过某个阈值。
            options.inSampleSize = calculateInSampleSize(options,
                maxOf(requiredWidthForP1, targetScreenWidth), // 至少需要屏幕宽度
                maxOf(requiredHeightForP1, targetScreenHeight) // 至少需要屏幕高度
            )
            // options.inSampleSize = calculateInSampleSize(options, 1080, 1920) // 或者一个固定的较高分别率


            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888

            // 实际解码
            context.contentResolver.openInputStream(imageUri)?.use { sourceSampled = BitmapFactory.decodeStream(it, null, options) }

            if (sourceSampled == null || sourceSampled!!.isRecycled) {
                Log.e(TAG, "Failed to decode sourceSampledBitmap. URI: $imageUri")
                return WallpaperBitmaps(null, null, null, null)
            }
            Log.d(TAG, "loadAndProcessInitialBitmaps: Source sampled to ${sourceSampled!!.width}x${sourceSampled!!.height} with inSampleSize=${options.inSampleSize}")

            // 准备P1前景图
            val topCropped = preparePage1TopCroppedBitmap(
                sourceSampled,
                targetScreenWidth,
                targetScreenHeight,
                page1ImageHeightRatio,
                normalizedFocusX,
                normalizedFocusY
            )

            // 准备P2滚动背景图及其模糊版本
            val (scrolling, blurred) = prepareScrollingAndBlurredBitmaps(
                context,
                sourceSampled, // 使用采样后的图作为背景源，以节省内存和处理时间
                targetScreenWidth,
                targetScreenHeight,
                numVirtualPagesForScrolling,
                blurRadiusForBackground
            )

            return WallpaperBitmaps(sourceSampled, topCropped, scrolling, blurred)

        } catch (e: Exception) {
            Log.e(TAG, "Error in loadAndProcessInitialBitmaps for URI: $imageUri", e)
            sourceSampled?.recycle()
            return WallpaperBitmaps(null, null, null, null) // 返回空的Bitmaps对象
        }
    }

    /**
     * 计算BitmapFactory的inSampleSize值。
     */
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
                if (inSampleSize <= 0 || inSampleSize > 1024) { // 安全退出
                    inSampleSize = if (inSampleSize > 1024) 1024 else maxOf(1, inSampleSize / 2)
                    break
                }
            }
        }
        Log.d(TAG, "calculateInSampleSize: original ${width}x${height}, required ${reqWidth}x${reqHeight}, sampleSize $inSampleSize")
        return inSampleSize
    }

    /**
     * 使用RenderScript模糊Bitmap。
     * 需要在 build.gradle 中配置RenderScript。
     */
    private fun blurBitmapUsingRenderScript(context: Context, bitmap: Bitmap, radius: Float): Bitmap? {
        if (radius <= 0f) return bitmap // 如果不模糊，返回原图的拷贝或原图本身（取决于是否希望修改原图）
        // 这里应该返回一个新的拷贝，或者调用者自己处理

        // RenderScript 模糊半径限制在 (0, 25f]
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
            // 作为降级方案，可以返回未模糊的Bitmap的拷贝，或者null
            // return bitmap.copy(bitmap.config, true) // 返回一个拷贝
        } catch (e: Exception) {
            Log.e(TAG, "RenderScript blur failed (General Exception)", e)
            outputBitmap?.recycle()
            outputBitmap = null
            // return bitmap.copy(bitmap.config, true)
        } finally {
            rs?.destroy()
        }
        return outputBitmap
    }

    /**
     * 绘制占位符。
     */
    fun drawPlaceholder(canvas: Canvas, width: Int, height: Int, text: String) {
        if (width <= 0 || height <= 0) return
        placeholderBgPaint.alpha = 255
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), placeholderBgPaint)

        placeholderTextPaint.alpha = 200
        val textY = height / 2f - ((placeholderTextPaint.descent() + placeholderTextPaint.ascent()) / 2f)
        canvas.drawText(text, width / 2f, textY, placeholderTextPaint)
    }

    // （可选）为P1叠加层单独绘制占位符的方法
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