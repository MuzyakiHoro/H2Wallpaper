package com.example.h2wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
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
import android.os.SystemClock // 添加SystemClock用于精确计时
import java.lang.Math.abs
import kotlin.math.cos
import kotlin.math.log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

// import com.example.h2wallpaper.WallpaperConfigConstants // 通常此文件独立

/**
 * 单例对象，封装了动态壁纸的核心渲染逻辑。
 * 这个对象被 `H2WallpaperService` 和 `WallpaperPreviewView` 共用，
 * 以确保动态壁纸的实际渲染和应用内预览效果的一致性。
 *
 * 它负责处理位图的加载、裁剪、缩放、模糊以及将处理后的图像绘制到 Canvas 上。
 */
object SharedWallpaperRenderer {

    private const val TAG = "SharedRenderer"
    private const val DEBUG_TAG_RENDERER = "SharedRenderer_Debug"
    private const val MIN_DOWNSCALED_DIMENSION = 16 // 降采样后的最小尺寸

    // 性能数据统计相关
    private const val PERF_TAG = "BlurPerf" // 模糊性能测试的日志标签
    private var blurTestCounter = 0 // 模糊测试计数器
    private var totalDownscaleTimeStats = PerfStats() // 降采样总耗时统计
    private var totalBlurTimeStats = PerfStats() // 模糊处理总耗时统计
    private var totalUpscaleTimeStats = PerfStats() // 放大处理总耗时统计
    private var totalProcessTimeStats = PerfStats() // 总处理时间统计

    /**
     * 用于存储和管理性能统计数据的数据类。
     * @property total 总耗时。
     * @property count 执行次数。
     * @property min 最小耗时。
     * @property max 最大耗时。
     * @property average 平均耗时 (只读)。
     */
    private data class PerfStats(
        var total: Long = 0,
        var count: Int = 0,
        var min: Long = Long.MAX_VALUE,
        var max: Long = 0
    ) {
        /** 计算平均耗时 */
        val average: Long get() = if (count > 0) total / count else 0

        /**
         * 更新性能统计数据。
         * @param value 本次操作的耗时。
         */
        fun update(value: Long) {
            total += value
            count++
            min = min.coerceAtMost(value)
            max = max.coerceAtLeast(value)
        }

        /**
         * 重置所有性能统计数据。
         */
        fun reset() {
            total = 0
            count = 0
            min = Long.MAX_VALUE
            max = 0
        }

        override fun toString(): String = "平均=${average}ms, 最小=${min}ms, 最大=${max}ms, 样本数=$count"
    }

    /**
     * 数据类，用于封装动态壁纸渲染所需的各种位图资源。
     * @property sourceSampledBitmap 经过采样处理的原始图片位图，用于P1前景和P2背景的基础。
     * @property page1TopCroppedBitmap 经过裁剪和缩放处理，用于P1前景显示的位图。
     * @property scrollingBackgroundBitmap 用于P2背景滚动的位图（通常未模糊或轻微处理）。
     * @property blurredScrollingBackgroundBitmap 经过模糊处理的P2背景滚动位图。
     * @property shadowTextureBitmap 氢斜阴影位图。
     */
    data class WallpaperBitmaps(
        var sourceSampledBitmap: Bitmap?,
        var page1TopCroppedBitmap: Bitmap?, // 这个现在会考虑 contentScaleFactor
        var scrollingBackgroundBitmap: Bitmap?,
        var blurredScrollingBackgroundBitmap: Bitmap?,
        var shadowTextureBitmap: Bitmap?
    ) {
        /**
         * 回收内部持有的所有位图资源，防止内存泄漏。
         */
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
            shadowTextureBitmap?.recycle()
            shadowTextureBitmap = null
        }

        /**
         * 判断当前持有的所有位图是否都为 null。
         */
        val isEmpty: Boolean
            get() = sourceSampledBitmap == null && page1TopCroppedBitmap == null &&
                    scrollingBackgroundBitmap == null && blurredScrollingBackgroundBitmap == null
    }

    /**
     * 数据类，用于封装渲染动态壁纸单帧所需的配置参数。
     * @property screenWidth 目标屏幕/画布的宽度。
     * @property screenHeight 目标屏幕/画布的高度。
     * @property page1BackgroundColor P1层下半部分（图片未覆盖区域）的背景颜色。
     * @property page1ImageHeightRatio P1层图片部分相对于屏幕高度的比例。
     * @property currentXOffset 当前壁纸的横向滚动偏移量 (0.0 到 1.0)。
     * @property numVirtualPages 虚拟页面数量，用于计算过渡效果的进度。
     * @property p1OverlayFadeTransitionRatio P1层前景开始淡出的滚动偏移比例。
     * @property scrollSensitivityFactor 背景滚动灵敏度因子。
     * @property normalizedInitialBgScrollOffset P2背景层在第一页的归一化初始横向偏移。
     * @property p2BackgroundFadeInRatio P2层背景开始淡入的滚动偏移比例。
     * @property p1ShadowRadius P1层图片的投影半径。
     * @property p1ShadowDx P1层图片的投影在X轴上的偏移。
     * @property p1ShadowDy P1层图片的投影在Y轴上的偏移。
     * @property p1ShadowColor P1层图片的投影颜色。
     * @property p1ImageBottomFadeHeight P1层图片底部融入背景的渐变高度。
     */
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
        val p1ImageBottomFadeHeight: Float,
        // p1ContentScaleFactor 在 preparePage1TopCroppedBitmap 时已应用
        // 新增：样式B 特有参数
        val styleBMaskAlpha: Float,
        val styleBRotationParamA: Float,
        val styleBGapSizeRatio: Float,
        val styleBGapPositionYRatio: Float,
        val styleBUpperMaskMaxRotation: Float,
        val styleBLowerMaskMaxRotation: Float,

        // 新增：样式B 的 P1独立背景图的编辑参数
        val styleBP1FocusX: Float,
        val styleBP1FocusY: Float,
        val styleBP1ScaleFactor: Float,
        val p1StyleType: Int,
        val styleBMasksHorizontallyFlipped: Boolean,
    )

    // --- Paint 对象定义 ---
    /** 用于绘制P2滚动背景的Paint对象 */
    private val scrollingBgPaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
    /** 用于绘制P1层下半部分纯色背景的Paint对象 */
    private val p1OverlayBgPaint = Paint()
    /** 用于绘制P1层前景图片的Paint对象 */
    private val p1OverlayImagePaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
    /** 用于在图片未加载时绘制占位文字的Paint对象 */
    private val placeholderTextPaint = Paint().apply {
        color = Color.WHITE; textSize = 40f; textAlign = Paint.Align.CENTER; isAntiAlias = true
    }
    /** 用于在图片未加载时绘制占位背景的Paint对象 */
    private val placeholderBgPaint = Paint().apply { color = Color.DKGRAY }
    /** 用于绘制P1图片投影效果的Paint对象 */
    private val rectShadowPaint = Paint().apply { isAntiAlias = true }
    /** 用于绘制P1图片底部融入渐变效果的Paint对象 */
    private val overlayFadePaint = Paint().apply { isAntiAlias = true }

    /**
     * 调整给定颜色的透明度。
     * @param color 原始颜色值。
     * @param factor 透明度调整因子 (0.0 表示完全透明, 1.0 表示完全不透明)。
     * @return 调整透明度后的颜色值。
     */
    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(
            alpha,
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    /**
     * 绘制动态壁纸的单帧。
     * 此方法会先绘制背景层(P2)，然后在其上叠加绘制前景层(P1)。
     * @param canvas 目标画布。
     * @param config 当前壁纸的渲染配置。
     * @param bitmaps 包含渲染所需位图的 [WallpaperBitmaps] 对象。
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
        // 先用P1的背景色填充整个画布，作为最底层
        canvas.drawColor(config.page1BackgroundColor)
        // 绘制P2背景层 (模糊滚动图)
        drawPage2Layer(canvas, config, bitmaps)
        // 绘制P1前景层 (顶部图片及其下方纯色区域)
        drawPage1Layer(canvas, config, bitmaps)
    }

    /**
     * 绘制P2背景层。
     * 包括模糊的滚动背景图，并根据滚动偏移计算其透明度和位置。
     * @param canvas 目标画布。
     * @param config 当前壁纸的渲染配置。
     * @param bitmaps 包含渲染所需位图的 [WallpaperBitmaps] 对象。
     */
    private fun drawPage2Layer(
        canvas: Canvas,
        config: WallpaperConfig,
        bitmaps: WallpaperBitmaps
    ) {
        val safeNumVirtualPages = config.numVirtualPages.coerceAtLeast(1)
        var p2BackgroundAlpha = 255 // P2背景的透明度

        // 根据当前滚动偏移和P2淡入比例计算P2背景的透明度
        if (safeNumVirtualPages > 1) {
            val fadeInEndXOffset = (1.0f / safeNumVirtualPages.toFloat()) * config.p2BackgroundFadeInRatio.coerceIn(0.01f, 1.0f)
            if (config.currentXOffset < fadeInEndXOffset) {
                val p2TransitionProgress = (config.currentXOffset / fadeInEndXOffset).coerceIn(0f, 1f)
                // 使用平方增加过渡的非线性感
                p2BackgroundAlpha = (255 * p2TransitionProgress.pow(2.0f)).toInt().coerceIn(0, 255)
            } else {
                p2BackgroundAlpha = 255 // 完全显示
            }
        } else {
            p2BackgroundAlpha = 255 // 单页时总是完全显示
        }

        // 选择要绘制的背景图：优先使用模糊图，如果不存在则使用未模糊的滚动背景图
        val backgroundToDraw = bitmaps.blurredScrollingBackgroundBitmap ?: bitmaps.scrollingBackgroundBitmap
        backgroundToDraw?.let { bgBmp ->
            if (!bgBmp.isRecycled && bgBmp.width > 0 && bgBmp.height > 0) {
                val imageActualWidth = bgBmp.width.toFloat()
                val imageActualHeight = bgBmp.height.toFloat()
                val screenActualWidth = config.screenWidth.toFloat()
                val screenActualHeight = config.screenHeight.toFloat()

                // 计算缩放比例以确保背景图像至少填满屏幕（通常是高度填满，宽度超出用于滚动）
                val scaleX = screenActualWidth / imageActualWidth
                val scaleY = screenActualHeight / imageActualHeight
                val scale = max(scaleX, scaleY) // 通常会是 scaleY，因为背景图是按屏幕高度缩放的

                val scaledWidth = imageActualWidth * scale
                val scaledHeight = imageActualHeight * scale

                // 计算垂直居中偏移（如果缩放后高度与屏幕高度不完全一致）
                val offsetY = (screenActualHeight - scaledHeight) / 2f

                // 计算背景图的横向滚动
                val totalScrollableWidthPx = (scaledWidth - screenActualWidth).coerceAtLeast(0f)
                val initialScrollPosition = totalScrollableWidthPx * config.normalizedInitialBgScrollOffset.coerceIn(0f, 1f)
                val scrollRangeForSensitivity = totalScrollableWidthPx * (1f - config.normalizedInitialBgScrollOffset.coerceIn(0f, 1f))
                var currentDynamicScrollPx = config.currentXOffset * config.scrollSensitivityFactor * scrollRangeForSensitivity
                currentDynamicScrollPx = currentDynamicScrollPx.coerceIn(0f, scrollRangeForSensitivity)
                val currentScrollPxFloat = initialScrollPosition + currentDynamicScrollPx

                // 保存画布状态，设置透明度，绘制背景图，然后恢复画布状态
                canvas.save()
                scrollingBgPaint.alpha = p2BackgroundAlpha

                val dstRect = RectF(
                    -currentScrollPxFloat,  // X轴根据滚动偏移
                    offsetY,
                    scaledWidth - currentScrollPxFloat,
                    offsetY + scaledHeight
                )
                // 使用 drawBitmap(bitmap, srcRect, dstRect, paint) 的形式绘制，这里 srcRect 为 null 表示使用整个 bitmap
                canvas.drawBitmap(bgBmp, null, dstRect, scrollingBgPaint)
                canvas.restore()
            } else {
                Log.w(TAG, "drawFrame (P2): Background bitmap is invalid or recycled.")
            }
        }
    }
    /**
     * 仅根据已有的基础Bitmap和新的模糊参数重新生成模糊后的Bitmap。
     * 此函数设计用于在模糊参数变化时，避免重新加载和处理原始图片，从而优化性能。
     *
     * @param context Context 对象，用于访问 RenderScript。
     * @param baseBitmap 要应用模糊效果的基础 Bitmap (例如，未模糊的滚动背景图)。
     * @param targetWidth 最终模糊图的目标宽度 (通常与 baseBitmap 宽度一致)。
     * @param targetHeight 最终模糊图的目标高度 (通常与 baseBitmap 高度一致)。
     * @param blurRadius 模糊半径 (有效范围 0.1f 到 25.0f)。
     * @param blurDownscaleFactor 模糊前的降采样因子 (0.01f 到 0.5f)，用于加速模糊处理。较小的值意味着更小的图被模糊。
     * @param blurIterations 模糊迭代次数，多次迭代可以增强模糊效果，但也会增加处理时间。
     * @return 新的模糊后的 Bitmap；如果基础 Bitmap 无效、无需模糊或处理失败，则返回 null 或原始 Bitmap 的副本。
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
        // 如果模糊半径过小，几乎等于不模糊，可以直接返回（或返回副本）
        if (blurRadius <= 0.01f) {
            // return baseBitmap.copy(baseBitmap.config, true) // 如果希望在不模糊时返回一个副本
            return null // 或者根据业务需求，返回null表示“无模糊版本”
        }

        var downscaledForBlur: Bitmap? = null // 降采样后用于模糊的位图
        var blurredDownscaled: Bitmap? = null // 降采样并模糊后的位图
        var upscaledBlurredBitmap: Bitmap? = null // 最终的模糊位图 (可能是 blurredDownscaled 或其放大版本)

        // 性能统计初始化
        val totalStartTime = SystemClock.elapsedRealtime()
        var downscaleTime = 0L
        var blurTime = 0L
        var upscaleTime = 0L // 注意：当前代码路径下，upscaleTime 通常为0，因为直接使用降采样模糊后的图
        var isFallbackPath = false // 标记是否走了备用路径（直接模糊原图）

        try {
            val sourceForActualBlur = baseBitmap // 将要进行模糊操作的源泉
            val actualDownscaleFactor = blurDownscaleFactor.coerceIn(0.01f, 0.5f) // 确保降采样因子在合理范围

            // 计算降采样后的目标尺寸
            val downscaledWidth = (sourceForActualBlur.width * actualDownscaleFactor).roundToInt().coerceAtLeast(MIN_DOWNSCALED_DIMENSION)
            val downscaledHeight = (sourceForActualBlur.height * actualDownscaleFactor).roundToInt().coerceAtLeast(MIN_DOWNSCALED_DIMENSION)

            // 判断是否真的需要执行降采样 (如果因子接近1或计算出的尺寸没有变小)
            if (actualDownscaleFactor < 0.99f && (downscaledWidth < sourceForActualBlur.width || downscaledHeight < sourceForActualBlur.height)) {
                val downscaleStartTime = SystemClock.elapsedRealtime()
                downscaledForBlur = Bitmap.createScaledBitmap(sourceForActualBlur, downscaledWidth, downscaledHeight, true)
                downscaleTime = SystemClock.elapsedRealtime() - downscaleStartTime

                val blurStartTime = SystemClock.elapsedRealtime()
                blurredDownscaled = blurBitmapUsingRenderScript(context, downscaledForBlur, blurRadius.coerceIn(0.1f, 25.0f), blurIterations)
                blurTime = SystemClock.elapsedRealtime() - blurStartTime

                if (blurredDownscaled != null) {
                    // 当前逻辑：不进行放大，直接使用降采样后的模糊图像作为最终结果。
                    // 这是因为在 loadAndProcessInitialBitmaps 中，如果进行了低分辨率加载，
                    // 那么这里的 baseBitmap (即 scrollingBackgroundBitmap) 已经是高质量的了。
                    // 而 regenerateBlurredBitmap 的目的是基于这个高质量的 scrollingBackgroundBitmap 生成一个 *新的* blurredScrollingBackgroundBitmap。
                    // 为了性能，我们对 baseBitmap 进行降采样 -> 模糊 -> (可选)放大。
                    // 当前的实现是 降采样 -> 模糊，然后直接使用这个模糊后的较小图像。
                    // 这意味着最终的 blurredScrollingBackgroundBitmap 尺寸可能小于 scrollingBackgroundBitmap。
                    // 如果要求模糊图和原图尺寸一致，则需要在此处添加放大步骤。
                    upscaledBlurredBitmap = blurredDownscaled // 直接赋值，不放大
                    upscaleTime = 0L // 没有放大操作
                    Log.d(TAG, "Using downscaled blurred image, skipping upscale. Final blur size: ${upscaledBlurredBitmap.width}x${upscaledBlurredBitmap.height}")
                } else {
                    // 如果降采样后的图模糊失败，则尝试直接模糊原始传入的 baseBitmap (作为后备)
                    Log.w(TAG, "regenerateBlurredBitmap: Blurred downscaled bitmap is null, falling back to blur on base bitmap.")
                    isFallbackPath = true
                    val blurFallbackStartTime = SystemClock.elapsedRealtime()
                    upscaledBlurredBitmap = blurBitmapUsingRenderScript(context, sourceForActualBlur, blurRadius, blurIterations)
                    blurTime = SystemClock.elapsedRealtime() - blurFallbackStartTime // 更新模糊时间为后备路径的
                    // 此时 upscaledBlurredBitmap 的尺寸与 sourceForActualBlur (即 baseBitmap) 一致
                }
            } else {
                // 不需要降采样 (例如 downscaleFactor 接近 1.0)，直接模糊原始传入的 baseBitmap
                isFallbackPath = true
                val blurDirectStartTime = SystemClock.elapsedRealtime()
                upscaledBlurredBitmap = blurBitmapUsingRenderScript(context, sourceForActualBlur, blurRadius, blurIterations)
                blurTime = SystemClock.elapsedRealtime() - blurDirectStartTime
            }

            // 性能日志记录
            val totalTime = SystemClock.elapsedRealtime() - totalStartTime
            totalProcessTimeStats.update(totalTime)
            if (!isFallbackPath) { // 只有在非后备路径（即执行了降采样）时才记录降采样和放大时间
                totalDownscaleTimeStats.update(downscaleTime)
                totalUpscaleTimeStats.update(upscaleTime) // upscaleTime 当前为0
            }
            totalBlurTimeStats.update(blurTime)
            blurTestCounter++

            val sb = StringBuilder()
            sb.append("【Blur Perf Test #$blurTestCounter】\n")
            sb.append("BaseImg: ${baseBitmap.width}x${baseBitmap.height}, Factor: $actualDownscaleFactor, Radius: $blurRadius, Iter: $blurIterations\n")
            if (isFallbackPath) {
                sb.append("[Direct Blur Path]\n")
                sb.append("Blur: ${blurTime}ms\n")
            } else {
                sb.append("Downscale(${sourceForActualBlur.width}x${sourceForActualBlur.height}→${downscaledWidth}x${downscaledHeight}): ${downscaleTime}ms\n")
                sb.append("Blur: ${blurTime}ms\n")
                if (upscaleTime > 0) sb.append("Upscale: ${upscaleTime}ms\n")
            }
            sb.append("Total: ${totalTime}ms. Result: ${upscaledBlurredBitmap?.width ?: 0}x${upscaledBlurredBitmap?.height ?: 0}")
            Log.d(PERF_TAG, sb.toString())

            if (blurTestCounter >= 10) { // 每10次测试输出一次汇总统计
                outputPerformanceStats()
                resetPerformanceStats()
            }

            return upscaledBlurredBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error during regenerateBlurredBitmap", e)
            // 确保回收过程中创建的临时位图
            upscaledBlurredBitmap?.recycle()
            blurredDownscaled?.recycle() // 即使 upscaledBlurredBitmap 是它，重复回收是安全的 (内部有检查)
            downscaledForBlur?.recycle()
            return null
        } finally {
            // 再次确保中间位图被回收，除非它们是返回的结果
            // 如果 upscaledBlurredBitmap 是 blurredDownscaled，则 blurredDownscaled 不应在此处回收
            // 如果 upscaledBlurredBitmap 是 downscaledForBlur (理论上不太可能)，则 downscaledForBlur 不应在此处回收
            if (upscaledBlurredBitmap != blurredDownscaled) blurredDownscaled?.recycle()
            if (upscaledBlurredBitmap != downscaledForBlur && blurredDownscaled != downscaledForBlur) downscaledForBlur?.recycle()
        }
    }


    /**
     * 绘制P1前景层。
     * 包括顶部的裁剪图片、图片下方的纯色背景、图片的投影效果以及底部融入效果。
     * P1层的整体透明度会根据滚动偏移和P1淡出比例进行计算。
     * @param canvas 目标画布。
     * @param config 当前壁纸的渲染配置。
     * @param bitmaps 包含渲染所需位图的 [WallpaperBitmaps] 对象。
     */
    private fun drawPage1Layer(
        canvas: Canvas,
        config: WallpaperConfig,
        bitmaps: WallpaperBitmaps
    ) {
        val safeNumVirtualPages = config.numVirtualPages.coerceAtLeast(1)
        val topImageActualHeight = (config.screenHeight * config.page1ImageHeightRatio).roundToInt()
        var p1OverallAlpha = 255 // P1层整体透明度

        // 根据当前滚动偏移和P1淡出比例计算P1层的整体透明度
        if (safeNumVirtualPages > 1) {
            val fadeOutEndXOffset = (1.0f / safeNumVirtualPages.toFloat()) * config.p1OverlayFadeTransitionRatio.coerceIn(0.01f, 1.0f)
            if (config.currentXOffset < fadeOutEndXOffset) {
                val p1TransitionProgress = (config.currentXOffset / fadeOutEndXOffset).coerceIn(0f, 1f)
                // 使用 (1 - progress)^2 使淡出效果更明显
                p1OverallAlpha = (255 * (1.0f - p1TransitionProgress.pow(2.0f))).toInt().coerceIn(0, 255)
            } else {
                p1OverallAlpha = 0 // 完全透明
            }
        } else {
            p1OverallAlpha = 255 // 单页时总是完全显示
        }

        //绘制P1层样式
        if (p1OverallAlpha > 0) { // 只有当P1层不是完全透明时才进行绘制
            // 从 config 中获取当前 P1 样式类型
            // val currentP1Style = config.p1StyleType // 假设 config 中有这个属性

            // 为了测试，我们先假设一个样式类型，你需要从配置中实际获取
            val currentP1Style =config.p1StyleType// WallpaperConfigConstants.DEFAULT_P1_STYLE_TYPE // 或者你从 ViewModel 获取后传入的
            if (currentP1Style == 0 /* STYLE_A */) { // 使用常量或枚举替代魔法数字 0
                drawStyleALayer(canvas, config, bitmaps, p1OverallAlpha)
            } else if (currentP1Style == 1 /* STYLE_B */) {
                drawStyleBLayer(canvas, config, bitmaps, p1OverallAlpha)
            }
            // 未来可以增加 else if (currentP1Style == STYLE_C) { drawStyleCLayer(...) }
        }
    }

    /**
     * 根据给定的源位图、目标屏幕尺寸、P1图片高度比例、焦点和内容缩放因子，
     * 准备用于P1前景显示的裁剪和缩放后的位图。
     *
     * @param sourceBitmap 原始的、采样处理过的位图。
     * @param targetScreenWidth P1前景图片的目标宽度（通常等于屏幕宽度）。
     * @param targetScreenHeight 目标屏幕的总高度（用于计算P1实际高度）。
     * @param page1ImageHeightRatio P1图片部分相对于屏幕高度的比例。
     * @param normalizedFocusX 归一化的焦点X坐标 (0.0 到 1.0)，表示图片中被关注的横向中心点。
     * @param normalizedFocusY 归一化的焦点Y坐标 (0.0 到 1.0)，表示图片中被关注的纵向中心点。
     * @param contentScaleFactor P1内容的缩放因子，大于等于1.0，用于在基础填充之上进一步放大内容。
     * @return 处理完成的P1前景位图；如果输入无效或处理失败，则返回 null。
     */
    fun preparePage1TopCroppedBitmap(
        sourceBitmap: Bitmap?, targetScreenWidth: Int, targetScreenHeight: Int,
        page1ImageHeightRatio: Float,
        normalizedFocusX: Float = 0.5f, normalizedFocusY: Float = 0.5f,
        contentScaleFactor: Float = 1.0f
    ): Bitmap? {
        if (sourceBitmap == null || sourceBitmap.isRecycled) return null
        if (targetScreenWidth <= 0 || targetScreenHeight <= 0 || page1ImageHeightRatio <= 0f) return null

        // 计算P1图片区域的实际目标高度
        val targetP1ActualHeight = (targetScreenHeight * page1ImageHeightRatio).roundToInt()
        if (targetP1ActualHeight <= 0) return null

        val bmWidth = sourceBitmap.width
        val bmHeight = sourceBitmap.height
        if (bmWidth <= 0 || bmHeight <= 0) return null

        var finalP1Bitmap: Bitmap? = null
        try {
            // 1. 计算基础填充缩放比例：使源图刚好填满P1区域的短边所需的最小缩放。
            //    例如，如果P1区域是宽的，就按高度填满；如果P1区域是高的，就按宽度填满。
            //    目标是 "cover" P1区域。
            val baseScaleXToFillP1 = targetScreenWidth.toFloat() / bmWidth.toFloat()
            val baseScaleYToFillP1 = targetP1ActualHeight.toFloat() / bmHeight.toFloat()
            val baseFillScale = max(baseScaleXToFillP1, baseScaleYToFillP1)

            // 2. 应用用户自定义的内容缩放因子。
            //    总的有效缩放 = 基础填充缩放 * 用户内容缩放。
            //    用户内容缩放至少为1.0（即不小于基础填充效果）。
            val totalEffectiveScale = baseFillScale * contentScaleFactor.coerceAtLeast(1.0f)

            // 3. 根据总有效缩放，反向计算在源图像上需要裁剪的区域的尺寸。
            //    这个源图像上的裁剪区域，在经过 totalEffectiveScale 缩放后，将正好等于P1的目标显示尺寸。
            val cropWidthInSourcePx = targetScreenWidth / totalEffectiveScale
            val cropHeightInSourcePx = targetP1ActualHeight / totalEffectiveScale

            // 4. 计算裁剪区域在源图像中的左上角坐标(srcX, srcY)。
            //    目标是：源图像上由(normalizedFocusX, normalizedFocusY)指定的点，成为这个裁剪出的小区域的中心点。
            var srcX = (normalizedFocusX * bmWidth) - (cropWidthInSourcePx / 2f)
            var srcY = (normalizedFocusY * bmHeight) - (cropHeightInSourcePx / 2f)

            // 5. 边界检查和校正：确保裁剪区域完全位于源图像内部。
            srcX = srcX.coerceIn(0f, bmWidth - cropWidthInSourcePx)
            srcY = srcY.coerceIn(0f, bmHeight - cropHeightInSourcePx)
            // 再次确保裁剪的宽度和高度不超过源图像在 (srcX, srcY) 之后剩余的有效尺寸。
            val finalCropWidth = min(cropWidthInSourcePx, bmWidth - srcX).roundToInt()
            val finalCropHeight = min(cropHeightInSourcePx, bmHeight - srcY).roundToInt()

            if (finalCropWidth > 0 && finalCropHeight > 0) {
                // 从源图像中裁剪出计算好的区域
                val croppedBmp = Bitmap.createBitmap(sourceBitmap, srcX.roundToInt(), srcY.roundToInt(), finalCropWidth, finalCropHeight)
                // 将裁剪出的位图缩放到P1区域的目标尺寸
                finalP1Bitmap = Bitmap.createScaledBitmap(croppedBmp, targetScreenWidth, targetP1ActualHeight, true)
                // 如果 croppedBmp 和 finalP1Bitmap 不是同一个对象（createScaledBitmap 可能会返回新对象），则回收中间的 croppedBmp
                if (croppedBmp != finalP1Bitmap && !croppedBmp.isRecycled) croppedBmp.recycle()
            } else {
                Log.w(TAG, "preparePage1TopCroppedBitmap: Calculated crop dimensions are zero or negative. W=$finalCropWidth, H=$finalCropHeight")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in preparePage1TopCroppedBitmap (contentScale: $contentScaleFactor)", e)
            finalP1Bitmap?.recycle() // 清理可能已创建的位图
            finalP1Bitmap = null
        }
        return finalP1Bitmap
    }

    /**
     * 准备用于P2背景滚动的位图和其模糊版本。
     *
     * @param context Context 对象。
     * @param sourceBitmap 原始的、采样处理过的位图。
     * @param targetScreenWidth 目标屏幕宽度。
     * @param targetScreenHeight 目标屏幕高度。
     * @param blurRadius 背景模糊半径。
     * @param blurDownscaleFactor 模糊处理前的降采样因子。
     * @param blurIterations 模糊处理迭代次数。
     * @return 返回一个 Pair，第一个元素是用于滚动的背景位图 (可能未模糊)，第二个元素是其模糊版本。
     * 如果输入无效或处理失败，则对应的位图可能为 null。
     */
    fun prepareScrollingAndBlurredBitmaps(
        context: Context, sourceBitmap: Bitmap?, targetScreenWidth: Int, targetScreenHeight: Int,
        blurRadius: Float, blurDownscaleFactor: Float, blurIterations: Int
    ): Pair<Bitmap?, Bitmap?> {
        if (sourceBitmap == null || sourceBitmap.isRecycled) return Pair(null, null)
        if (targetScreenWidth <= 0 || targetScreenHeight <= 0) return Pair(null, null)

        var finalScrollingBackground: Bitmap? = null // 未模糊（或基础）的滚动背景
        var finalBlurredScrollingBackground: Bitmap? = null // 模糊后的滚动背景

        val originalBitmapWidth = sourceBitmap.width.toFloat()
        val originalBitmapHeight = sourceBitmap.height.toFloat()

        if (originalBitmapWidth <= 0 || originalBitmapHeight <= 0) return Pair(null, null)

        // 计算滚动背景图的尺寸：通常按屏幕高度缩放，宽度自适应，以提供横向滚动空间
        val scaleToFitScreenHeight = targetScreenHeight / originalBitmapHeight
        val scaledWidthForScrollingBg = (originalBitmapWidth * scaleToFitScreenHeight).roundToInt()

        if (scaledWidthForScrollingBg <= 0) return Pair(null, null)

        var baseScrollingBitmap: Bitmap? = null    // 经过缩放，用于滚动的底图
        var downscaledForBlur: Bitmap? = null      // 为优化模糊而降采样的图
        var blurredDownscaled: Bitmap? = null      // 降采样后并模糊的图
        var upscaledBlurredBitmap: Bitmap? = null  // （未使用）如果需要，这是模糊后又放大回来的图

        try {
            // 1. 准备基础的滚动背景图 (未模糊)
            baseScrollingBitmap = Bitmap.createScaledBitmap(
                sourceBitmap,
                scaledWidthForScrollingBg,
                targetScreenHeight,
                true //启用filter以获得更好的缩放质量
            )
            finalScrollingBackground = baseScrollingBitmap // 至少这个是准备好了的

            // 2. 如果需要模糊，则处理模糊背景图
            if (blurRadius > 0.01f && baseScrollingBitmap != null && !baseScrollingBitmap.isRecycled) {
                val sourceForActualBlur = baseScrollingBitmap // 将要进行模糊操作的源泉
                val actualDownscaleFactor = blurDownscaleFactor.coerceIn(0.01f, 0.5f)
                val downscaledWidth = (sourceForActualBlur.width * actualDownscaleFactor).roundToInt().coerceAtLeast(MIN_DOWNSCALED_DIMENSION)
                val downscaledHeight = (sourceForActualBlur.height * actualDownscaleFactor).roundToInt().coerceAtLeast(MIN_DOWNSCALED_DIMENSION)

                if (actualDownscaleFactor < 0.99f && (downscaledWidth < sourceForActualBlur.width || downscaledHeight < sourceForActualBlur.height)) {
                    // 执行降采样
                    downscaledForBlur = Bitmap.createScaledBitmap(sourceForActualBlur, downscaledWidth, downscaledHeight, true)
                    // 模糊降采样后的图
                    blurredDownscaled = blurBitmapUsingRenderScript(context, downscaledForBlur, blurRadius.coerceIn(0.1f, 25.0f), blurIterations)

                    if (blurredDownscaled != null) {
                        // 当前策略：直接使用模糊后的降采样图像，不进行放大。
                        // 这意味着 finalBlurredScrollingBackground 的尺寸可能小于 finalScrollingBackground。
                        // 这个选择是为了性能，如果需要模糊图和原滚动图尺寸一致，则需要在此处添加放大步骤。
                        finalBlurredScrollingBackground = blurredDownscaled
                        Log.d(TAG, "prepareScrollingAndBlurredBitmaps: Using downscaled blurred image, size: ${blurredDownscaled.width}x${blurredDownscaled.height}")
                    } else {
                        // 如果降采样或模糊降采样图失败，作为后备，直接在原始缩放的滚动图 (baseScrollingBitmap) 上进行模糊
                        Log.w(TAG, "Blurred downscaled bitmap is null, falling back to blur on base scrolling bitmap.")
                        finalBlurredScrollingBackground = blurBitmapUsingRenderScript(context, sourceForActualBlur, blurRadius, blurIterations)
                    }
                } else {
                    // 不需要降采样 (例如，downscaleFactor 接近 1.0)，直接在 baseScrollingBitmap 上模糊
                    finalBlurredScrollingBackground = blurBitmapUsingRenderScript(context, sourceForActualBlur, blurRadius, blurIterations)
                }
            } else if (baseScrollingBitmap != null && !baseScrollingBitmap.isRecycled) {
                // 不需要模糊，finalBlurredScrollingBackground 保持为 null 或根据需要设置为 baseScrollingBitmap 的副本。
                // 当前行为是保持为 null。
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during prepareScrollingAndBlurredBitmaps", e)
            // 出错时，回收所有可能已创建的中间位图，并将最终结果设为null
            upscaledBlurredBitmap?.recycle()
            blurredDownscaled?.recycle()
            downscaledForBlur?.recycle()
            // baseScrollingBitmap 可能是 finalScrollingBackground，也可能已被赋值给 finalScrollingBackground
            // 如果 finalScrollingBackground 不是 baseScrollingBitmap（理论上在此代码块中它们在成功路径是同一个），则单独回收
            if (finalScrollingBackground != baseScrollingBitmap) finalScrollingBackground?.recycle()
            baseScrollingBitmap?.recycle() // baseScrollingBitmap 是在此函数内创建的，总应尝试回收

            finalScrollingBackground = null
            finalBlurredScrollingBackground = null
        } finally {
            // 清理那些在 try 块中成功创建但在 catch 中可能未被处理的中间位图
            // 确保不会回收最终返回的位图对象
            if (finalBlurredScrollingBackground != blurredDownscaled) blurredDownscaled?.recycle()
            if (finalBlurredScrollingBackground != downscaledForBlur && blurredDownscaled != downscaledForBlur) downscaledForBlur?.recycle()
            // upscaledBlurredBitmap 在当前逻辑下通常不会被赋值给最终结果，所以可以安全回收（如果它被创建过）
            // 但为了保险，如果它被意外赋值，这里的逻辑需要更严谨。当前代码路径下，它基本用不上。
        }
        return Pair(finalScrollingBackground, finalBlurredScrollingBackground)
    }

    /**
     * 从给定的 URI 加载图片，并处理生成所有初始状态所需的位图版本。
     * 包括：经过采样的高质量源图、P1前景裁剪图、P2滚动背景图及其模糊版本。
     *
     * @param context Context 对象。
     * @param imageUri 要加载的图片的 URI。
     * @param targetScreenWidth 目标屏幕/画布的宽度。
     * @param targetScreenHeight 目标屏幕/画布的高度。
     * @param page1ImageHeightRatio P1前景图片相对于屏幕高度的比例。
     * @param normalizedFocusX P1前景的归一化焦点X坐标。
     * @param normalizedFocusY P1前景的归一化焦点Y坐标。
     * @param contentScaleFactorForP1 P1前景的内容缩放因子。
     * @param blurRadiusForBackground P2背景的模糊半径。
     * @param blurDownscaleFactor P2背景模糊处理时的降采样因子。
     * @param blurIterations P2背景模糊处理的迭代次数。
     * @return 返回一个 [WallpaperBitmaps] 对象，包含所有处理好的位图；如果加载或处理失败，则对应位图可能为 null。
     */
    fun loadAndProcessInitialBitmaps(
        context: Context, imageUri: Uri?, targetScreenWidth: Int, targetScreenHeight: Int,
        page1ImageHeightRatio: Float, normalizedFocusX: Float, normalizedFocusY: Float,
        contentScaleFactorForP1: Float,
        blurRadiusForBackground: Float, blurDownscaleFactor: Float, blurIterations: Int
    ): WallpaperBitmaps {
        if (imageUri == null || targetScreenWidth <= 0 || targetScreenHeight <= 0) {
            return WallpaperBitmaps(null, null, null, null,null)
        }

        var sourceSampled: Bitmap? = null // 高质量的、经过内存优化的源图
        var blurSourceBitmap: Bitmap? = null // （可选）专门为模糊处理加载的低分辨率源图
        var shadowBitmap: Bitmap? = null  //氢斜阴影贴图
        try {
            shadowBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.mask_shadow_texture_a) //获取氢斜阴影贴图
            // --- 第一步：获取图片原始尺寸信息 ---
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(imageUri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                Log.e(TAG, "Failed to get image bounds for $imageUri")
                return WallpaperBitmaps(null, null, null, null,null)
            }

            // --- 第二步：加载高质量的源图 (sourceSampled) ---
            // 用于 P1 前景和未模糊的 P2 滚动背景。
            // 采样率基于屏幕尺寸的两倍，以保证在缩放和裁剪后仍有足够细节。
            val highQualityOpts = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(opts, targetScreenWidth * 2, targetScreenHeight * 2) // 计算合适的采样率
                inJustDecodeBounds = false // 实际解码图片
                inPreferredConfig = Bitmap.Config.ARGB_8888 // 偏好高质量的颜色配置
            }
            context.contentResolver.openInputStream(imageUri)?.use {
                sourceSampled = BitmapFactory.decodeStream(it, null, highQualityOpts)
            }
            if (sourceSampled == null || sourceSampled!!.isRecycled) {
                Log.e(TAG, "Failed to decode high-quality sourceSampled from $imageUri")
                return WallpaperBitmaps(null, null, null, null,null)
            }

            // --- 第三步：准备 P1 前景裁剪图 ---
            // 始终基于高质量的 sourceSampled 图进行处理。
            val topCropped = preparePage1TopCroppedBitmap(
                sourceSampled, targetScreenWidth, targetScreenHeight,
                page1ImageHeightRatio, normalizedFocusX, normalizedFocusY, contentScaleFactorForP1
            )

            // --- 第四步：准备 P2 滚动背景图 和 模糊背景图 ---
            var scrollingBackground: Bitmap?
            var blurredBackground: Bitmap? = null

            if (blurRadiusForBackground > 0.009f) { // 如果需要模糊效果
                // 优化策略：为模糊背景专门加载一个分辨率更低的源图 (blurSourceBitmap)
                val effectiveBlurDownscaleForLoad = blurDownscaleFactor.coerceIn(0.01f, 1.0f) // 降采样因子
                // 基于高质量图的采样率，进一步增加采样率来获得低分辨率图
                val blurSampleSize = (1.0f / effectiveBlurDownscaleForLoad).toInt().coerceAtLeast(1)
                val finalBlurLoadSampleSize = highQualityOpts.inSampleSize * blurSampleSize

                val blurSpecificOpts = BitmapFactory.Options().apply {
                    inSampleSize = finalBlurLoadSampleSize
                    inJustDecodeBounds = false
                    inPreferredConfig = Bitmap.Config.ARGB_8888 // 通常模糊对颜色配置要求不高，但保持一致
                }
                Log.d(PERF_TAG, "Loading low-res bitmap for blur with sampleSize: $finalBlurLoadSampleSize (HQ_SS=${highQualityOpts.inSampleSize} * Blur_SS=$blurSampleSize)")
                context.contentResolver.openInputStream(imageUri)?.use {
                    blurSourceBitmap = BitmapFactory.decodeStream(it, null, blurSpecificOpts)
                }

                if (blurSourceBitmap != null && !blurSourceBitmap!!.isRecycled) {
                    // 1. 准备高质量的、未模糊的滚动背景图 (scrollingBackground)
                    //    仍然基于 sourceSampled (高质量源图)
                    scrollingBackground = prepareScrollingBitmap(sourceSampled, targetScreenWidth, targetScreenHeight)

                    // 2. 在低分辨率的 blurSourceBitmap 上直接应用模糊
                    val blurStartTime = SystemClock.elapsedRealtime()
                    val blurredLowRes = blurBitmapUsingRenderScript(context, blurSourceBitmap, blurRadiusForBackground, blurIterations)

                    if (blurredLowRes != null) {
                        // 当前策略：直接使用这个低分辨率模糊后的图像作为最终的 blurredBackground，不进行放大。
                        // 这是因为壁纸服务在绘制时，会将此图拉伸到屏幕大小。
                        // 如果希望模糊图和高质量滚动图在处理前尺寸一致，这里需要对 blurredLowRes 进行放大。
                        blurredBackground = blurredLowRes
                        Log.d(TAG, "Using low-resolution pre-blurred bitmap. Size: ${blurredBackground.width}x${blurredBackground.height}")
                    }
                    val totalBlurTime = SystemClock.elapsedRealtime() - blurStartTime
                    Log.d(PERF_TAG, "Optimized blur processing (on low-res source) total time: ${totalBlurTime}ms")
                } else {
                    // 如果加载低分辨率图失败，则回退到标准方法：
                    // 即在高质量的 sourceSampled 图上进行缩放、然后降采样模糊。
                    Log.w(TAG, "Failed to load low-resolution bitmap for blur. Falling back to standard blur path on high-quality source.")
                    val (scrolling, blurred) = prepareScrollingAndBlurredBitmaps(
                        context, sourceSampled, targetScreenWidth, targetScreenHeight,
                        blurRadiusForBackground, blurDownscaleFactor, blurIterations
                    )
                    scrollingBackground = scrolling
                    blurredBackground = blurred
                }
            } else {
                // 不需要模糊效果，只准备未模糊的滚动背景图
                scrollingBackground = prepareScrollingBitmap(sourceSampled, targetScreenWidth, targetScreenHeight)
                // blurredBackground 保持为 null
            }

            // 清理临时的低分辨率源图 (如果加载过)
            blurSourceBitmap?.recycle()

            return WallpaperBitmaps(sourceSampled, topCropped, scrollingBackground, blurredBackground,shadowBitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Error in loadAndProcessInitialBitmaps for $imageUri", e)
            sourceSampled?.recycle()
            blurSourceBitmap?.recycle()
            return WallpaperBitmaps(null, null, null, null,null)
        }
    }

    /**
     * 辅助方法：仅准备用于滚动的背景位图 (不进行模糊处理)。
     * 将源位图缩放到适合屏幕高度，宽度按比例调整。
     *
     * @param sourceBitmap 原始位图。
     * @param targetScreenWidth 目标屏幕宽度。
     * @param targetScreenHeight 目标屏幕高度。
     * @return 处理好的滚动背景位图；如果失败则返回 null。
     */
    private fun prepareScrollingBitmap(
        sourceBitmap: Bitmap?, targetScreenWidth: Int, targetScreenHeight: Int
    ): Bitmap? {
        if (sourceBitmap == null || sourceBitmap.isRecycled) return null
        if (targetScreenWidth <= 0 || targetScreenHeight <= 0) return null

        val originalBitmapWidth = sourceBitmap.width.toFloat()
        val originalBitmapHeight = sourceBitmap.height.toFloat()

        if (originalBitmapWidth <= 0 || originalBitmapHeight <= 0) return null

        // 按屏幕高度缩放，宽度自适应
        val scaleToFitScreenHeight = targetScreenHeight / originalBitmapHeight
        val scaledWidthForScrollingBg = (originalBitmapWidth * scaleToFitScreenHeight).roundToInt()

        if (scaledWidthForScrollingBg <= 0) return null

        return try {
            Bitmap.createScaledBitmap(
                sourceBitmap,
                scaledWidthForScrollingBg,
                targetScreenHeight,
                true // filter
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error in prepareScrollingBitmap", e)
            null
        }
    }

    /**
     * 计算 BitmapFactory.Options 的 inSampleSize 值。
     * 用于在解码图片时进行下采样，以减少内存占用。
     * @param options 包含原始图片尺寸的 BitmapFactory.Options 对象。
     * @param reqWidth 期望的目标宽度。
     * @param reqHeight 期望的目标高度。
     * @return 计算得到的 inSampleSize 值 (总是2的幂，最小为1)。
     */
    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (h, w) = options.outHeight to options.outWidth
        var sample = 1
        if (h > reqHeight || w > reqWidth) {
            val halfH = h / 2
            val halfW = w / 2
            // 循环减半尺寸，直到采样后的尺寸小于等于请求尺寸
            while (halfH / sample >= reqHeight && halfW / sample >= reqWidth) {
                sample *= 2
                if (sample > 8) break // 限制最大采样率，避免过度缩小
            }
        }
        return sample
    }
    /**
     * 使用 RenderScript 对给定的 Bitmap 应用高斯模糊效果。
     *
     * @param context Context 对象，用于创建 RenderScript 实例。
     * @param bitmap 要模糊的原始 Bitmap。
     * @param radius 模糊半径，有效值范围建议为 0.1f 到 25.0f。值越大，模糊程度越高。
     * @param iterations 模糊迭代次数，默认为1。多次迭代可以增强模糊效果，但会增加处理时间。
     * @return 返回模糊处理后的新 Bitmap 对象。如果原始 Bitmap 无效、无需模糊（半径过小且迭代1次）或处理失败，则可能返回原始 Bitmap 的副本或 null。
     */
    private fun blurBitmapUsingRenderScript(context: Context, bitmap: Bitmap, radius: Float, iterations: Int = 1): Bitmap? {
        // 输入校验
        if (bitmap.isRecycled || bitmap.width == 0 || bitmap.height == 0) {
            Log.w(TAG, "blurBitmapUsingRenderScript: Input bitmap is invalid.")
            return null
        }

        // 规范化参数
        val clampedRadius = radius.coerceIn(0.1f, 25.0f) // RenderScript 模糊半径通常限制在 (0, 25]
        val actualIterations = iterations.coerceAtLeast(1)

        // 如果模糊半径非常小且只有一次迭代，可以认为不需要模糊，直接返回副本以避免不必要的处理
        if (clampedRadius < 0.1f && actualIterations == 1) {
            return try {
                bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy bitmap for no-blur case", e)
                null
            }
        }

        var rs: RenderScript? = null
        var script: ScriptIntrinsicBlur? = null
        var currentIterationBitmap: Bitmap = bitmap // 当前迭代的输入位图
        var outputBitmap: Bitmap? = null          // 当前迭代的输出位图

        // 性能计时相关 (可以根据需要启用或移除详细日志)
        // val totalBlurProcessStartTime = SystemClock.elapsedRealtime()
        // var scriptInitTime = 0L
        // val iterationTimes = mutableListOf<Long>()

        try {
            // val rsCreateStartTime = SystemClock.elapsedRealtime()
            rs = RenderScript.create(context)
            script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs)) // U8_4 对应 ARGB_8888
            script.setRadius(clampedRadius)
            // scriptInitTime = SystemClock.elapsedRealtime() - rsCreateStartTime

            for (i in 0 until actualIterations) {
                // val iterStartTime = SystemClock.elapsedRealtime()

                // 为本次迭代的输出创建一个新的 Bitmap
                // 如果是第一次迭代，且 currentIterationBitmap 就是传入的 bitmap，
                // 那么 outputBitmap 必须是一个新的实例。
                // 如果不是第一次迭代，currentIterationBitmap 是上一次的 outputBitmap，
                // 我们可以考虑复用，但为了逻辑清晰和避免潜在问题，每次都创建新的 outputBitmap 更安全，
                // 并在迭代结束时回收不再需要的 currentIterationBitmap (如果它不是原始输入 bitmap)。
                outputBitmap = Bitmap.createBitmap(
                    currentIterationBitmap.width,
                    currentIterationBitmap.height,
                    currentIterationBitmap.config ?: Bitmap.Config.ARGB_8888
                )

                val ain = Allocation.createFromBitmap(rs, currentIterationBitmap) // 输入 Allocation
                val aout = Allocation.createFromBitmap(rs, outputBitmap)          // 输出 Allocation

                script.setInput(ain)    // 设置模糊脚本的输入
                script.forEach(aout)    // 执行模糊操作，结果写入 aout
                aout.copyTo(outputBitmap) // 将结果从 Allocation 复制回 outputBitmap

                // 清理本次迭代的 Allocation 对象
                ain.destroy()
                aout.destroy()

                // 如果 currentIterationBitmap 不是原始传入的 bitmap (即它是上一次迭代的输出)，
                // 并且它也不同于本次新创建的 outputBitmap，那么它可以被回收了。
                if (currentIterationBitmap != bitmap && currentIterationBitmap != outputBitmap) {
                    currentIterationBitmap.recycle()
                }
                // 更新 currentIterationBitmap 为本次的输出，作为下一次迭代的输入 (如果还有迭代)
                currentIterationBitmap = outputBitmap

                // iterationTimes.add(SystemClock.elapsedRealtime() - iterStartTime)
            }
            // val totalBlurTime = SystemClock.elapsedRealtime() - totalBlurProcessStartTime
            // Log.d(PERF_TAG, "RS Blur Detail: Init=${scriptInitTime}ms, Iterations(${iterationTimes.size}): ${iterationTimes.joinToString()}ms, TotalBlurInternal=${totalBlurTime}ms")

            // 经过所有迭代后，currentIterationBitmap (即最后一次的 outputBitmap) 就是最终的模糊结果
            return currentIterationBitmap

        } catch (e: Exception) {
            Log.e(TAG, "RenderScript blur failed", e)
            // 发生异常时，尝试回收可能已创建的 outputBitmap
            outputBitmap?.recycle()
            // 如果 currentIterationBitmap 在异常发生时不是原始 bitmap，也不是刚创建失败的 outputBitmap，也尝试回收
            if (currentIterationBitmap != bitmap && currentIterationBitmap != outputBitmap) {
                currentIterationBitmap.recycle()
            }
            return null
        } finally {
            // 确保 RenderScript 和 ScriptIntrinsicBlur 对象被销毁
            script?.destroy()
            rs?.destroy()
            // 注意：最终返回的 currentIterationBitmap (即模糊结果) 不应在此处回收。
            // 如果在循环中 currentIterationBitmap 被赋值为 outputBitmap，而 outputBitmap 在异常时被回收，
            // 那么 currentIterationBitmap 也指向了那个被回收的位图，这需要注意。
            // 上面的逻辑确保了只有中间迭代产生的、不再被引用的位图会被回收。
        }
    }

    /**
     * 输出性能统计的汇总数据到日志。
     * 通常在进行一系列模糊测试后调用。
     */
    private fun outputPerformanceStats() {
        val sb = StringBuilder()
        sb.append("【Blur Performance Summary】Total ${blurTestCounter} tests:\n")
        sb.append("Downscale Phase: ${totalDownscaleTimeStats}\n")
        sb.append("Blur Process Phase: ${totalBlurTimeStats}\n")
        sb.append("Upscale Phase: ${totalUpscaleTimeStats}\n") // 当前通常为0
        sb.append("Total Processing Time: ${totalProcessTimeStats}")
        Log.d(PERF_TAG, sb.toString())
    }

    /**
     * 重置所有性能统计数据，以便开始新的测试周期。
     */
    private fun resetPerformanceStats() {
        blurTestCounter = 0
        totalDownscaleTimeStats.reset()
        totalBlurTimeStats.reset()
        totalUpscaleTimeStats.reset()
        totalProcessTimeStats.reset()
    }

    /**
     * 在给定的画布上绘制占位符。
     * 通常在图片尚未加载或加载失败时调用。
     * @param canvas 目标画布。
     * @param width 画布宽度。
     * @param height 画布高度。
     * @param text 要显示的占位文本。
     */
    fun drawPlaceholder(canvas: Canvas, width: Int, height: Int, text: String) {
        if (width <= 0 || height <= 0) return
        // 绘制深灰色背景
        placeholderBgPaint.alpha = 255
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), placeholderBgPaint)
        // 绘制居中的白色提示文字
        placeholderTextPaint.alpha = 200
        val textY = height / 2f - ((placeholderTextPaint.descent() + placeholderTextPaint.ascent()) / 2f) // 计算文字垂直居中的Y坐标
        canvas.drawText(text, width / 2f, textY, placeholderTextPaint)
    }
    /**
     * 绘制 P1 层 - 样式 A (顶部小窗图片 + 图片下方背景色)
     */
    private fun drawStyleALayer(
        canvas: Canvas,
        config: WallpaperConfig,
        bitmaps: WallpaperBitmaps,
        p1OverallAlpha: Int // 将整体透明度传入
    ) {
        canvas.saveLayerAlpha(0f, 0f, config.screenWidth.toFloat(), config.screenHeight.toFloat(), p1OverallAlpha)

        val topImageActualHeight = (config.screenHeight * config.page1ImageHeightRatio).roundToInt()
        if (topImageActualHeight > 0) {
            p1OverlayBgPaint.shader = null
            p1OverlayBgPaint.color = config.page1BackgroundColor
            canvas.drawRect(0f, topImageActualHeight.toFloat(), config.screenWidth.toFloat(), config.screenHeight.toFloat(), p1OverlayBgPaint)

            bitmaps.page1TopCroppedBitmap?.let { topBmp ->
                if (!topBmp.isRecycled && topBmp.width > 0 && topBmp.height > 0) {
                    // ... (样式A的投影、图片绘制、底部融入逻辑) ...
                    // (从你之前注释掉的代码块中恢复这部分)
                    if (config.p1ShadowRadius > 0.01f && Color.alpha(config.p1ShadowColor) > 0) {
                        rectShadowPaint.apply {
                            color = Color.TRANSPARENT
                            setShadowLayer(config.p1ShadowRadius, config.p1ShadowDx, config.p1ShadowDy, config.p1ShadowColor)
                        }
                        canvas.drawRect(0f, 0f, topBmp.width.toFloat(), topBmp.height.toFloat(), rectShadowPaint)
                    }
                    p1OverlayImagePaint.clearShadowLayer()
                    canvas.drawBitmap(topBmp, 0f, 0f, p1OverlayImagePaint)
                    val fadeActualHeight = config.p1ImageBottomFadeHeight.coerceIn(0f, topBmp.height.toFloat())
                    if (fadeActualHeight > 0.1f) {
                        val fadeStartY = topBmp.height.toFloat(); val fadeEndY = topBmp.height.toFloat() - fadeActualHeight
                        val colors = intArrayOf(
                            config.page1BackgroundColor,
                            adjustAlpha(config.page1BackgroundColor, 0.8f),
                            adjustAlpha(config.page1BackgroundColor, 0.3f),
                            Color.TRANSPARENT
                        )
                        val positions = floatArrayOf(0f, 0.3f, 0.7f, 1f)
                        overlayFadePaint.shader = LinearGradient(
                            0f, fadeStartY, 0f, fadeEndY,
                            colors, positions, Shader.TileMode.CLAMP
                        )
                        canvas.drawRect(0f, fadeEndY, topBmp.width.toFloat(), fadeStartY, overlayFadePaint)
                    } else {
                        overlayFadePaint.shader = null
                    }
                } else { Log.w(TAG, "drawFrame (P1 Style A): Top cropped bitmap is invalid or recycled.") }
            } ?: Log.d(TAG, "drawFrame (P1 Style A): No top cropped bitmap available for Style A.")
        } else {
            // 如果样式A的图片高度为0，P1层除了透明度外基本是空的。
            // 可以考虑如果 page1BackgroundColor 不是完全透明，是否要用它填充整个 P1 区域。
            // 但当前的逻辑是，如果 topImageActualHeight <=0，则不绘制任何样式A的内容。
            // 为了保持与之前一致，可以暂时保留这个行为。
            // 或者，如果希望即使图片高度为0，下方背景色也显示：
            // p1OverlayBgPaint.shader = null
            // p1OverlayBgPaint.color = config.page1BackgroundColor
            // canvas.drawRect(0f, 0f, config.screenWidth.toFloat(), config.screenHeight.toFloat(), p1OverlayBgPaint)
        }
        canvas.restore()
    }

    /**
     * 绘制 P1 层 - 样式 B (P1独立背景图 + 上下旋转遮罩 + 中间间隔)
     */
    // 在 SharedWallpaperRenderer.kt 文件中

    // 在 SharedWallpaperRenderer.kt 文件中

    private fun drawStyleBLayer(
        canvas: Canvas,
        config: WallpaperConfig,
        bitmaps: WallpaperBitmaps,
        p1OverallAlpha: Int
    ) {
        canvas.saveLayerAlpha(0f, 0f, config.screenWidth.toFloat(), config.screenHeight.toFloat(), p1OverallAlpha)

        Log.d(TAG, "Drawing P1 Layer with Style B (Stretching Upper Inner Shadow to Diagonal)")
        val p1FullScreenBackgroundBitmap = bitmaps.sourceSampledBitmap
        val horizontalInnerShadowBitmap = bitmaps.shadowTextureBitmap // 您的横向内阴影贴图

        if (p1FullScreenBackgroundBitmap != null && !p1FullScreenBackgroundBitmap.isRecycled) {
            // 1. 绘制 P1 独立背景图 (逻辑不变)
            val p1BgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val bgMatrix = Matrix()
            // ... (bgMatrix 计算和绘制 p1FullScreenBackgroundBitmap 的代码保持不变)
            val scale: Float
            val dx: Float
            val dy: Float
            if (p1FullScreenBackgroundBitmap.width * config.screenHeight > config.screenWidth * p1FullScreenBackgroundBitmap.height) {
                scale = config.screenHeight.toFloat() / p1FullScreenBackgroundBitmap.height.toFloat()
                dx = (config.screenWidth.toFloat() - p1FullScreenBackgroundBitmap.width * scale) * 0.5f
                dy = 0f
            } else {
                scale = config.screenWidth.toFloat() / p1FullScreenBackgroundBitmap.width.toFloat()
                dx = 0f
                dy = (config.screenHeight.toFloat() - p1FullScreenBackgroundBitmap.height * scale) * 0.5f
            }
            bgMatrix.setScale(scale, scale)
            bgMatrix.postTranslate(dx, dy)
            canvas.drawBitmap(p1FullScreenBackgroundBitmap, bgMatrix, p1BgPaint)


            val flipRestorePoint = canvas.saveCount
            if (config.styleBMasksHorizontallyFlipped) {
                canvas.scale(-1f, 1f, config.screenWidth / 2f, config.screenHeight / 2f)
            }

            // 2. 获取参数 (不变)
            val parameterA = config.styleBRotationParamA
            val gapSizeRatio = config.styleBGapSizeRatio
            val gapPositionYRatio = config.styleBGapPositionYRatio
            val maskAlpha = config.styleBMaskAlpha
            val maskColorForFallback = config.page1BackgroundColor
            val upperMaxRotation = config.styleBUpperMaskMaxRotation
            val lowerMaxRotation = config.styleBLowerMaskMaxRotation

            // 3. 计算几何 (不变)
            val currentGapHeight = config.screenHeight * gapSizeRatio
            val gapCenterY = config.screenHeight * gapPositionYRatio
            val gapTopY = (gapCenterY - currentGapHeight / 2f).coerceIn(0f, config.screenHeight.toFloat() - currentGapHeight.coerceAtLeast(0f))
            val gapBottomY = (gapTopY + currentGapHeight).coerceIn(currentGapHeight.coerceAtLeast(0f), config.screenHeight.toFloat())

            // 4. 计算画布旋转角度 (不变)
            val actualRotationUpper = -upperMaxRotation * parameterA
            val actualRotationLower = -lowerMaxRotation * parameterA

            val screenWidthF = config.screenWidth.toFloat()
            val screenHeightF = config.screenHeight.toFloat()

            // --- 计算屏幕对角线长度 ---
            val diagonalScreenLength = kotlin.math.sqrt(screenWidthF * screenWidthF + screenHeightF * screenHeightF)


            // overdrawExtension 现在也应该基于这个对角线长度，如果上遮罩路径宽度也想用它
            // 或者，您可以为上遮罩路径定义一个宽度，然后让阴影贴图拉伸到该宽度。
            // 我们假设上遮罩路径的宽度就是 diagonalScreenLength。
            val upperMaskPathWidth = diagonalScreenLength


            // 5. 准备上遮罩的 Paint
            val upperMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                alpha = (maskAlpha * 255).toInt().coerceIn(0, 255)

                if (horizontalInnerShadowBitmap != null && !horizontalInnerShadowBitmap.isRecycled) {
                    // 使用 CLAMP 模式，因为我们将精确缩放贴图以匹配目标尺寸
                    val shader = BitmapShader(horizontalInnerShadowBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    val shaderMatrix = Matrix()
                    // a. 贴图内容180度旋转 (围绕其中心)
                    shaderMatrix.postRotate(180f,  horizontalInnerShadowBitmap.width.toFloat() / 2f, horizontalInnerShadowBitmap.height.toFloat() / 2f)
                    // b. 计算缩放比例
                    //    X轴：将贴图宽度拉伸到屏幕对角线长度 (upperMaskPathWidth)
                    //    Y轴：保持贴图原始高度（即阴影厚度），所以scaleY = 1f
                    val adjacentLength = screenWidthF / cos(Math.toRadians(upperMaxRotation * parameterA.toDouble()).toFloat())
                    val scaleX = (adjacentLength /  horizontalInnerShadowBitmap.width.toFloat() )*1.2f
                    val scaleY = 1f // Y轴不缩放，保持贴图原始高度作为阴影厚度
                    // 在旋转之后应用缩放。缩放默认以(0,0)为中心。
                    // 如果旋转中心不是(0,0)，顺序和缩放中心需要仔细考虑。
                    // 当前：先旋转整个贴图，然后对这个已旋转的贴图进行缩放。
                    shaderMatrix.postScale(scaleX, scaleY)
                    // c. Y轴定位
                    //    innerShadowEffectiveHeight 是经过Y轴缩放后的高度 (此处为贴图原始高度因为scaleY=1)
                    val innerShadowEffectiveHeight = horizontalInnerShadowBitmap.height.toFloat() * scaleY
                    val translateYUpper = gapTopY - innerShadowEffectiveHeight
                    // X轴平移：您之前测试好的-50f。
                    // 由于贴图宽度已被精确拉伸到upperMaskPathWidth，X轴平移通常为0，除非您想调整拉伸后内容的相位。
                    // 如果-50f是在非拉伸情况下调好的，现在可能需要重新评估或设为0。
                    val translateXUpper = -50f // 建议先尝试0f
                    shaderMatrix.postTranslate(translateXUpper, translateYUpper)
                    shader.setLocalMatrix(shaderMatrix)
                    this.shader = shader
                } else {
                    Log.w(TAG, "Horizontal inner shadow texture (for upper mask) is not available, falling back to solid color.")
                    color = maskColorForFallback
                }
            }

            // 6. 准备下遮罩的 Paint (保持之前的逻辑，如果您已调好或暂时不修改)
            val lowerMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                alpha = (maskAlpha * 255).toInt().coerceIn(0, 255)
                if (horizontalInnerShadowBitmap != null && !horizontalInnerShadowBitmap.isRecycled) {
                    val shader = BitmapShader(horizontalInnerShadowBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    val shaderMatrix = Matrix()
                    // a.

                    // b. X轴缩放：下遮罩路径的宽度是 screenWidthF - (0f - overdrawExtension) = screenWidthF + overdrawExtension
                    val lowerMaskDesignWidth = screenWidthF / cos(Math.toRadians(upperMaxRotation * parameterA.toDouble()).toFloat())

                    val scaleXLower = (lowerMaskDesignWidth /  horizontalInnerShadowBitmap.width.toFloat())*1.2f
                    shaderMatrix.postScale(scaleXLower, 1f) // Y轴不缩放
                    // c. 定位贴图：
                    val translateY = gapBottomY
                    val translateX = screenWidthF - ( horizontalInnerShadowBitmap.width.toFloat() * scaleXLower)+50f
                    shaderMatrix.postTranslate(translateX, translateY)
                    shader.setLocalMatrix(shaderMatrix)
                    this.shader = shader
                } else {
                    Log.w(TAG, "Horizontal inner shadow texture (for lower mask) is not available, falling back to solid color.")
                    color = maskColorForFallback
                }
            }


            // 7. 绘制上部旋转遮罩
            canvas.save()
            canvas.rotate(actualRotationUpper, 0f, gapTopY)
            val upperMaskPath = Path()
            // 上遮罩路径的宽度现在使用 upperMaskPathWidth (即屏幕对角线长度)
            upperMaskPath.addRect(0f, 0f, upperMaskPathWidth, gapTopY, Path.Direction.CW)
            canvas.drawPath(upperMaskPath, upperMaskPaint)
            canvas.restore()

            // 8. 绘制下部旋转遮罩
            canvas.save()
            canvas.rotate(actualRotationLower, screenWidthF, gapBottomY)
            val lowerMaskPath = Path()
            // 下遮罩路径宽度也应考虑 overdrawExtension 或一个足够大的值
            // 当前定义是从 -overdrawExtension (即 -screenWidthF*2f) 到 screenWidthF
            val lowerMaskRectLeft = 0f - (screenWidthF * 2f) // 使用您之前的 overdrawExtension
            val lowerMaskRectRight = screenWidthF
            lowerMaskPath.addRect(lowerMaskRectLeft, gapBottomY, lowerMaskRectRight, screenHeightF, Path.Direction.CW)
            canvas.drawPath(lowerMaskPath, lowerMaskPaint)
            canvas.restore()

            canvas.restoreToCount(flipRestorePoint)

        } else {
            // ... (占位符绘制逻辑)
        }
        canvas.restore() // 对应最外层的 saveLayerAlpha
    }



}