package com.example.h2wallpaper

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.renderscript.* // 暂时保留，之后可以考虑替换模糊实现
import android.util.Log
import kotlin.math.min
import kotlin.math.roundToInt

object SharedWallpaperRenderer {

    private const val TAG = "SharedRenderer"
    // 这些常量可以从 H2WallpaperService 移到这里，或者通过参数传递
    // private const val NUM_VIRTUAL_SCROLL_PAGES = 3 // 如果固定，可以保留在这里
    // private const val P1_OVERLAY_FADE_TRANSITION_RATIO = 0.2f
    // private const val BACKGROUND_BLUR_RADIUS = 25f

    // --- Paint 对象 ---
    // 最好在实际使用时按需创建或作为参数传入，以避免在 object 中持有过多状态或 Context 依赖
    // 但为了简化初步实现，暂时这样放置，之后可以优化
    private val scrollingBgPaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
    private val p1OverlayBgPaint = Paint()
    private val p1OverlayImagePaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
    private val placeholderTextPaint = Paint().apply {
        color = Color.WHITE; textSize = 40f; textAlign = Paint.Align.CENTER; isAntiAlias = true
    }
    private val placeholderBgPaint = Paint()


    /**
     * 代表壁纸渲染所需的所有静态资源（位图）
     * 这些位图应该在外部准备好并传入 drawFrame 方法
     */
    data class WallpaperBitmaps(
        val page1TopCroppedBitmap: Bitmap?,
        val scrollingBackgroundBitmap: Bitmap?,
        val blurredScrollingBackgroundBitmap: Bitmap? // 可选的模糊背景
    )

    /**
     * 代表壁纸当前动态状态
     */
    data class WallpaperConfig(
        val screenWidth: Int,
        val screenHeight: Int,
        val page1BackgroundColor: Int,
        val page1ImageHeightRatio: Float,
        val currentXOffset: Float,      // 范围 0.0 到 1.0 (代表整体滚动进度)
        val numVirtualPages: Int = 3,   // 虚拟页面数量
        val p1OverlayFadeTransitionRatio: Float = 0.2f // 第一页叠加层淡出过渡比例
    )

    /**
     * 主要的绘制函数，由壁纸服务和预览视图调用
     *
     * @param canvas 要绘制的画布
     * @param config 当前壁纸的配置和状态
     * @param bitmaps 预处理好的壁纸位图资源
     */
    fun drawFrame(
        canvas: Canvas,
        config: WallpaperConfig,
        bitmaps: WallpaperBitmaps
    ) {
        if (config.screenWidth == 0 || config.screenHeight == 0) {
            Log.w(TAG, "Screen dimensions are zero, cannot draw.")
            return
        }

        canvas.drawColor(Color.BLACK) // 清除背景

        // 1. 绘制可滚动的背景层 (优先使用模糊版本)
        val backgroundToDraw = bitmaps.blurredScrollingBackgroundBitmap ?: bitmaps.scrollingBackgroundBitmap

        backgroundToDraw?.let { bgBmp ->
            if (!bgBmp.isRecycled && bgBmp.width > 0) {
                // 计算背景图总的可滚动宽度
                // 假设 scrollingBackgroundBitmap 的宽度已经是 screenWidth * numVirtualPages
                // 或者如果不是，需要根据 numVirtualPages 动态调整这里的滚动计算
                val totalScrollableWidth = (bgBmp.width - config.screenWidth).coerceAtLeast(0)
                val currentScrollPx = (config.currentXOffset * totalScrollableWidth).toInt()
                    .coerceIn(0, totalScrollableWidth)

                // 背景图垂直居中 (如果背景图高度不等于屏幕高度)
                val bgTopOffset = ((config.screenHeight - bgBmp.height) / 2f)

                canvas.save()
                canvas.translate(-currentScrollPx.toFloat(), bgTopOffset)
                scrollingBgPaint.alpha = 255 // 重置alpha
                canvas.drawBitmap(bgBmp, 0f, 0f, scrollingBgPaint)
                canvas.restore()
            } else {
                drawPlaceholder(canvas, config.screenWidth, config.screenHeight, "背景处理中...")
            }
        } ?: run {
            drawPlaceholder(canvas, config.screenWidth, config.screenHeight, "请选择图片以生成背景")
        }

        // 2. 计算并绘制第一屏的叠加层
        var overlayAlpha = 0
        val topImageActualHeight = (config.screenHeight * config.page1ImageHeightRatio).toInt()

        // 计算 xOffsetStep，即单个页面的偏移比例
        val xOffsetStep = if (config.numVirtualPages > 1) 1.0f / (config.numVirtualPages -1) else 1.0f
        // 上面的计算可能不完全正确，H2WallpaperService 中 xOffsetStep 是由Launcher报告的
        // 对于预览，我们可能需要假设一个固定的页面数量来计算逻辑页面切换点
        // 或者，让 currentXOffset 直接代表逻辑页面索引（例如0.0到0.99代表第一页，1.0到1.99代表第二页等）
        // 这里我们暂时简化，认为 currentXOffset 是 0.0 (第一页开始) 到 1.0 (最后一页末尾)
        // 我们需要根据 currentXOffset 判断是否应该显示第一页的叠加元素以及其透明度

        if (config.numVirtualPages <= 0) { // 安全检查
            overlayAlpha = if (config.currentXOffset < 0.01f) 255 else 0 // 简单处理
        } else if (config.numVirtualPages == 1) {
            overlayAlpha = 255
        } else {
            // 假设 currentXOffset 从 0 (第一页最左边) 到 1 (最后一页最右边)
            // 第一页的范围大致是 currentXOffset 从 0 到 (1/numVirtualPages)
            // 过渡区域是第一页宽度的一部分
            val firstPageEndXOffset = 1.0f / config.numVirtualPages
            val transitionBoundary = firstPageEndXOffset * config.p1OverlayFadeTransitionRatio

            if (config.currentXOffset < firstPageEndXOffset) { // 当前在第一页的范围内
                if (config.currentXOffset <= transitionBoundary && transitionBoundary > 0.001f) {
                    val progressOutOfP1FadeZone = (config.currentXOffset / transitionBoundary).coerceIn(0f, 1f)
                    overlayAlpha = ((1.0f - progressOutOfP1FadeZone) * 255).toInt().coerceIn(0, 255)
                } else if (config.currentXOffset > transitionBoundary) { // 已经过了淡出区域，但在第一页内
                    overlayAlpha = 0 // 或者根据具体设计，在第一页内始终不完全透明？
                    // H2Service的逻辑是，只要currentXOffset > transitionBoundary 就 alpha = 0
                } else { // transitionBoundary 非常小或为0
                    overlayAlpha = 255 // 在第一页的起始位置，完全不透明
                }
            } else { // 不在第一页
                overlayAlpha = 0
            }
            // 修正：H2WallpaperService中的逻辑是基于 xOffsetStep 和 P1_OVERLAY_FADE_TRANSITION_RATIO
            // xOffsetStep 是指从一页完全显示到下一页完全显示所需的 xOffset 增量。
            // 如果 xOffsetStep = 0.5 (表示有2页，第0页和第1页)，那么 xOffset 从 0 到 0.5 是第一页，0.5 到 1.0 是第二页
            // 这部分逻辑需要仔细对齐 H2WallpaperService
            // 暂时简化：如果 currentXOffset < ( (1.0/numVirtualPages) * P1_OVERLAY_FADE_TRANSITION_RATIO ) 则完全显示，否则渐变到0
            val effectiveXOffsetStep = if (config.numVirtualPages > 1) 1f / (config.numVirtualPages - 1) else 1f
            // H2Service 的逻辑:
            // val transitionBoundary = effectiveXOffsetStep * config.p1OverlayFadeTransitionRatio; (这是错误的理解)
            // 正确的应该是：transitionBoundary 是 xOffset 绝对值的一个点。
            // 如果 xOffset 在 [0, transitionBoundary] 内，则 alpha 从 255 线性减到 0。
            // transitionBoundary 应该是 p1OverlayFadeTransitionRatio （例如0.2）乘以 单个屏幕的宽度对应的 xOffset 值。
            // 如果总共有3页，xOffset从0到1。每页占据1/3。
            // 第一页是 [0, 1/3)，第二页是 [1/3, 2/3)，第三页是 [2/3, 1]。
            // 仅当 currentXOffset 在第一页的特定比例内才开始渐变。
            // 例如，当 currentXOffset 从 0 增加到 (1/numVirtualPages) * p1OverlayFadeTransitionRatio 时，alpha 从 255 变为 0。

            // 重新校准透明度逻辑，使其更接近 H2WallpaperService
            // H2Service: numPagesReportedByLauncher, currentXOffsetStep
            // 预览时，我们可以自己定义虚拟页数 (config.numVirtualPages)
            // 那么，单页的 xOffset 范围是 1.0f / config.numVirtualPages
            val singlePageXOffsetRange = 1.0f / config.numVirtualPages.coerceAtLeast(1)
            val fadeOutEndXOffset = singlePageXOffsetRange * config.p1OverlayFadeTransitionRatio.coerceIn(0.01f, 1f) // 渐变结束的偏移点

            if (config.currentXOffset < fadeOutEndXOffset) {
                overlayAlpha = ((1.0f - (config.currentXOffset / fadeOutEndXOffset)) * 255).toInt().coerceIn(0, 255)
            } else {
                overlayAlpha = 0
            }
            if (config.currentXOffset == 0f) overlayAlpha = 255 // 确保在最开始时是完全不透明的
        }


        if (overlayAlpha > 0) {
            // 绘制第一屏下半部分的纯色背景
            p1OverlayBgPaint.color = config.page1BackgroundColor
            p1OverlayBgPaint.alpha = overlayAlpha
            canvas.drawRect(
                0f,
                topImageActualHeight.toFloat(),
                config.screenWidth.toFloat(),
                config.screenHeight.toFloat(),
                p1OverlayBgPaint
            )

            // 绘制第一屏上半部分的图片
            bitmaps.page1TopCroppedBitmap?.let { topBmp ->
                if (!topBmp.isRecycled) {
                    p1OverlayImagePaint.alpha = overlayAlpha
                    canvas.drawBitmap(topBmp, 0f, 0f, p1OverlayImagePaint)
                }
            } ?: run {
                // 如果顶图不存在但仍需显示叠加层（例如，颜色层需要显示），可以绘制一个占位符
                if (bitmaps.scrollingBackgroundBitmap != null) { // 至少有原始图片时才提示
                    drawPlaceholderForP1Overlay(canvas, config.screenWidth, topImageActualHeight, "P1顶图处理中...", overlayAlpha)
                }
            }
        }

        // 重置Paint对象的alpha，以防影响下一次绘制（虽然在这个单次调用中不是问题，但好习惯）
        scrollingBgPaint.alpha = 255
        p1OverlayBgPaint.alpha = 255
        p1OverlayImagePaint.alpha = 255
    }

    /**
     * 准备壁纸所需的各种位图版本。
     * 这个方法应该在后台线程执行，因为它可能涉及文件IO和图像处理。
     * 注意：这个函数和 H2WallpaperService 里的 prepareDerivedBitmapsInternal 非常相似，
     * 我们需要决定是将这部分逻辑也完全移到这里，还是让 Service 和 PreviewView 各自调用部分共享的辅助函数。
     * 为了简化，这里暂时只定义接口，具体实现可以从 Service 迁移。
     *
     * @param context Context 对象，用于资源访问和 RenderScript
     * @param imageUri 用户选择的图片 URI
     * @param targetScreenWidth 目标渲染宽度 (通常是屏幕宽度)
     * @param targetScreenHeight 目标渲染高度 (通常是屏幕高度)
     * @param page1ImageHeightRatio 第一页顶部图片的高度比例
     * @param numVirtualPages 虚拟页面数量，用于计算滚动背景宽度
     * @param blurRadius 模糊半径 (如果为0或负数，则不进行模糊)
     * @return WallpaperBitmaps 对象，包含所有处理好的位图，或者在失败时某些位图为 null
     */
    fun prepareAllBitmaps(
        context: Context,
        imageUri: Uri?,
        targetScreenWidth: Int,
        targetScreenHeight: Int,
        page1ImageHeightRatio: Float,
        numVirtualPages: Int = 3,
        blurRadius: Float = 0f // H2WallpaperService.BACKGROUND_BLUR_RADIUS
    ): WallpaperBitmaps {
        if (imageUri == null || targetScreenWidth <= 0 || targetScreenHeight <= 0) {
            return WallpaperBitmaps(null, null, null)
        }

        var originalBitmap: Bitmap? = null
        var page1TopCropped: Bitmap? = null
        var scrollingBackground: Bitmap? = null
        var blurredScrollingBackground: Bitmap? = null

        try {
            // 1. 解码原始图片 (带采样)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            var inputStream = context.contentResolver.openInputStream(imageUri)
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream?.close()

            // 适当调整采样大小，目标是比最终需要的略大或相等，避免过度缩小
            // 对于滚动背景，宽度需求是 targetScreenWidth * numVirtualPages
            // 对于顶部图片，宽度需求是 targetScreenWidth
            options.inSampleSize = calculateInSampleSize(options, targetScreenWidth * numVirtualPages, targetScreenHeight)
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888 // ARGB_8888 更适合模糊

            inputStream = context.contentResolver.openInputStream(imageUri)
            originalBitmap = BitmapFactory.decodeStream(inputStream, null, options)
            inputStream?.close()

            if (originalBitmap == null) {
                Log.e(TAG, "Failed to decode originalBitmap after sampling.")
                return WallpaperBitmaps(null, null, null)
            }
            Log.d(TAG, "Original bitmap decoded: ${originalBitmap.width}x${originalBitmap.height}")

            // --- 开始准备派生位图 --- (逻辑从 H2WallpaperService.prepareDerivedBitmapsInternal 迁移和调整)

            // 2. 准备 P1 顶部图片 (page1TopCroppedBitmap)
            val targetTopHeight = (targetScreenHeight * page1ImageHeightRatio).toInt()
            if (targetTopHeight > 0) {
                try {
                    val bmWidth = originalBitmap.width
                    val bmHeight = originalBitmap.height
                    // 目标：从 originalBitmap 中心裁剪出 targetScreenWidth x targetTopHeight 的图片（保持比例进行缩放裁剪）
                    // 使用类似 ImageView.ScaleType.CENTER_CROP 的逻辑
                    var srcX = 0; var srcY = 0
                    var cropWidth = bmWidth; var cropHeight = bmHeight

                    val bitmapAspectRatio = bmWidth.toFloat() / bmHeight.toFloat()
                    val targetAspectRatio = targetScreenWidth.toFloat() / targetTopHeight.toFloat()

                    if (bitmapAspectRatio > targetAspectRatio) { // 图片比目标区域更宽 (或等高但更宽)
                        cropWidth = (bmHeight * targetAspectRatio).toInt()
                        srcX = (bmWidth - cropWidth) / 2
                    } else { // 图片比目标区域更高 (或等宽但更高)
                        cropHeight = (bmWidth / targetAspectRatio).toInt()
                        srcY = (bmHeight - cropHeight) / 2
                    }
                    // 确保裁剪区域不超出原图边界
                    cropWidth = min(cropWidth, bmWidth - srcX).coerceAtLeast(1)
                    cropHeight = min(cropHeight, bmHeight - srcY).coerceAtLeast(1)
                    srcX = srcX.coerceAtLeast(0); srcY = srcY.coerceAtLeast(0)
                    if (srcX + cropWidth > bmWidth) cropWidth = bmWidth - srcX
                    if (srcY + cropHeight > bmHeight) cropHeight = bmHeight - srcY


                    if (cropWidth > 0 && cropHeight > 0) {
                        val cropped = Bitmap.createBitmap(originalBitmap, srcX, srcY, cropWidth, cropHeight)
                        page1TopCropped = Bitmap.createScaledBitmap(cropped, targetScreenWidth, targetTopHeight, true)
                        if (cropped != page1TopCropped && !cropped.isRecycled) cropped.recycle()
                        Log.d(TAG, "page1TopCroppedBitmap created: ${page1TopCropped?.width}x${page1TopCropped?.height}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating page1TopCroppedBitmap", e)
                    page1TopCropped?.recycle()
                    page1TopCropped = null
                }
            }


            // 3. 准备可滚动的背景图 (scrollingBackgroundBitmap)
            val bgTargetHeight = targetScreenHeight
            val obW = originalBitmap.width.toFloat()
            val obH = originalBitmap.height.toFloat()

            if (obW > 0 && obH > 0) {
                // 首先，将原始图片缩放到适应屏幕高度，保持宽高比
                val scaleToFitScreenHeight = bgTargetHeight / obH
                val scaledOriginalWidth = (obW * scaleToFitScreenHeight).toInt()

                if (scaledOriginalWidth > 0) {
                    val tempScaledBitmap = Bitmap.createScaledBitmap(originalBitmap, scaledOriginalWidth, bgTargetHeight, true)
                    val actualNumVirtualPages = numVirtualPages.coerceAtLeast(1)
                    val bgFinalTargetWidth = targetScreenWidth * actualNumVirtualPages

                    scrollingBackground = Bitmap.createBitmap(bgFinalTargetWidth, bgTargetHeight, Bitmap.Config.ARGB_8888)
                    val canvasForScrollingBg = Canvas(scrollingBackground!!)
                    var currentX = 0

                    if (scaledOriginalWidth >= bgFinalTargetWidth) { // 缩放后的单张图已经够宽了，从中间截取
                        val offsetX = (scaledOriginalWidth - bgFinalTargetWidth) / 2
                        val srcRect = Rect(offsetX, 0, offsetX + bgFinalTargetWidth, bgTargetHeight)
                        val dstRect = Rect(0, 0, bgFinalTargetWidth, bgTargetHeight)
                        canvasForScrollingBg.drawBitmap(tempScaledBitmap, srcRect, dstRect, null)
                    } else { // 平铺
                        while (currentX < bgFinalTargetWidth) {
                            canvasForScrollingBg.drawBitmap(tempScaledBitmap, currentX.toFloat(), 0f, null)
                            currentX += tempScaledBitmap.width
                            if (tempScaledBitmap.width <= 0) break // 防止死循环
                        }
                    }
                    if (tempScaledBitmap != originalBitmap && !tempScaledBitmap.isRecycled) {
                        tempScaledBitmap.recycle()
                    }
                    Log.d(TAG, "scrollingBackgroundBitmap created: ${scrollingBackground!!.width}x${scrollingBackground!!.height}")

                    // 4. 准备模糊的滚动背景图 (blurredScrollingBackgroundBitmap)
                    if (blurRadius > 0f && scrollingBackground != null && !scrollingBackground!!.isRecycled) {
                        blurredScrollingBackground = blurBitmapUsingRenderScript(context, scrollingBackground!!, blurRadius)
                        if (blurredScrollingBackground != null) {
                            Log.d(TAG, "blurredScrollingBackgroundBitmap created.")
                        } else {
                            Log.e(TAG, "Failed to blur scrollingBackgroundBitmap.")
                        }
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in prepareAllBitmaps", e)
            // 确保回收中间位图
            originalBitmap?.recycle()
            page1TopCropped?.recycle(); page1TopCropped = null
            scrollingBackground?.recycle(); scrollingBackground = null
            blurredScrollingBackground?.recycle(); blurredScrollingBackground = null
            return WallpaperBitmaps(null, null, null) // 返回空的位图集合
        } finally {
            // 原始位图originalBitmap 在这里可以被安全回收，因为它已经被用于生成其他位图
            // 或者，如果外部还需要原始位图，则不应在此处回收
            // 目前的设计是 prepareAllBitmaps 生成所有需要的最终版本，所以可以回收原始采样后的。
            originalBitmap?.recycle()
        }

        return WallpaperBitmaps(page1TopCropped, scrollingBackground, blurredScrollingBackground)
    }

    // 从 H2WallpaperService 迁移过来的辅助函数
    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (width == 0 || height == 0 || reqWidth <= 0 || reqHeight <= 0) return 1
        if (height > reqHeight || width > reqWidth) {
            val halfH = height / 2
            val halfW = width / 2
            while ((halfH / inSampleSize) >= reqHeight && (halfW / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
                if (inSampleSize <= 0 || inSampleSize > 1024) { // 防止溢出或过大的采样率
                    inSampleSize = 1024
                    break
                }
            }
        }
        return inSampleSize
    }

    // RenderScript 模糊函数 (从 H2WallpaperService 迁移)
    // 注意：RenderScript 从 API 31 开始已弃用。对于新代码，建议寻找替代方案。
    // 但为了保持与原项目一致，暂时保留。
    private fun blurBitmapUsingRenderScript(context: Context, bitmap: Bitmap, radius: Float): Bitmap? {
        if (radius < 1f || radius > 25f) {
            Log.w(TAG, "Blur radius out of range (1-25): $radius. Not blurring.")
            return null // 或者返回原始位图的副本 bitmap.copy(bitmap.config, true)
        }
        var rs: RenderScript? = null
        var outputBitmap: Bitmap? = null
        try {
            outputBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888)
            rs = RenderScript.create(context)
            val input = Allocation.createFromBitmap(rs, bitmap)
            val output = Allocation.createFromBitmap(rs, outputBitmap)
            val script = ScriptIntrinsicBlur.create(rs, input.element)
            script.setRadius(radius)
            script.setInput(input)
            script.forEach(output)
            output.copyTo(outputBitmap)

            input.destroy()
            output.destroy()
            script.destroy()
            return outputBitmap
        } catch (e: Exception) {
            Log.e(TAG, "RenderScript blur failed", e)
            outputBitmap?.recycle()
            return null
        } finally {
            rs?.destroy()
        }
    }


     fun drawPlaceholder(canvas: Canvas, width: Int, height: Int, text: String) {
        placeholderBgPaint.color = Color.DKGRAY
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

    /**
     * 辅助方法，用于回收 WallpaperBitmaps 中的所有位图
     */
    fun recycleBitmaps(bitmaps: WallpaperBitmaps?) {
        bitmaps?.page1TopCroppedBitmap?.recycle()
        bitmaps?.scrollingBackgroundBitmap?.recycle()
        bitmaps?.blurredScrollingBackgroundBitmap?.recycle()
    }
}