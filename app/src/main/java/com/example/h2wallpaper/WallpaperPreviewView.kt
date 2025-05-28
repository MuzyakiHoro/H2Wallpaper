package com.example.h2wallpaper

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

import com.example.h2wallpaper.WallpaperConfigConstants
import kotlinx.coroutines.cancel

class WallpaperPreviewView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs) {

    private val TAG = "WallpaperPreviewView"

    // --- 可配置状态 ---
    private var imageUri: Uri? = null
    private var selectedBackgroundColor: Int = Color.LTGRAY
    var nonEditModePage1ImageHeightRatio: Float = WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO
    var currentNormalizedFocusX: Float = WallpaperConfigConstants.DEFAULT_P1_FOCUS_X
    var currentNormalizedFocusY: Float = WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y
    var currentP1ContentScaleFactor: Float = WallpaperConfigConstants.DEFAULT_P1_CONTENT_SCALE_FACTOR

    private var currentScrollSensitivity: Float = WallpaperConfigConstants.DEFAULT_SCROLL_SENSITIVITY
    private var currentP1OverlayFadeRatio: Float = WallpaperConfigConstants.DEFAULT_P1_OVERLAY_FADE_RATIO
    private var currentP2BackgroundFadeInRatio: Float = WallpaperConfigConstants.DEFAULT_P2_BACKGROUND_FADE_IN_RATIO
    private var currentBackgroundBlurRadius: Float = WallpaperConfigConstants.DEFAULT_BACKGROUND_BLUR_RADIUS
    private var currentBlurDownscaleFactor: Float = WallpaperConfigConstants.DEFAULT_BLUR_DOWNSCALE_FACTOR_INT / 100.0f
    private var currentBlurIterations: Int = WallpaperConfigConstants.DEFAULT_BLUR_ITERATIONS
    private var currentSnapAnimationDurationMs: Long = WallpaperConfigConstants.DEFAULT_PREVIEW_SNAP_DURATION_MS
    private var currentNormalizedInitialBgScrollOffset: Float = WallpaperConfigConstants.DEFAULT_BACKGROUND_INITIAL_OFFSET
    private var currentP1ShadowRadius: Float = WallpaperConfigConstants.DEFAULT_P1_SHADOW_RADIUS
    private var currentP1ShadowDx: Float = WallpaperConfigConstants.DEFAULT_P1_SHADOW_DX
    private var currentP1ShadowDy: Float = WallpaperConfigConstants.DEFAULT_P1_SHADOW_DY
    private var currentP1ShadowColor: Int = WallpaperConfigConstants.DEFAULT_P1_SHADOW_COLOR
    private var currentP1ImageBottomFadeHeight: Float = WallpaperConfigConstants.DEFAULT_P1_IMAGE_BOTTOM_FADE_HEIGHT

    private var wallpaperBitmaps: SharedWallpaperRenderer.WallpaperBitmaps? = null

    // --- 内部状态 ---
    private var viewWidth: Int = 0
    private var viewHeight: Int = 0
    private var currentPreviewXOffset: Float = 0f
    private val numVirtualPages: Int = 3

    // --- 滑动和惯性滚动 ---
    private var velocityTracker: VelocityTracker? = null
    private lateinit var pageScroller: OverScroller // 重命名，用于页面整体横向滚动
    private lateinit var p1ContentScroller: OverScroller // 新增，用于P1内容拖拽后的惯性滚动
    private var lastP1ScrollerX: Int = 0
    private var lastP1ScrollerY: Int = 0

    private var lastTouchX: Float = 0f
    private var downTouchX: Float = 0f
    private var isPageSwiping: Boolean = false
    private val touchSlop: Int
    private val minFlingVelocity: Int
    private val maxFlingVelocity: Int
    private var activePointerId: Int = MotionEvent.INVALID_POINTER_ID

    private val viewScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var fullBitmapLoadingJob: Job? = null
    private var topBitmapUpdateJob: Job? = null
    private var blurUpdateJob: Job? = null // 用于执行单个模糊任务
    
    // 启用智能模糊任务调度系统
    private val blurTaskQueue = Channel<BlurTask>(Channel.UNLIMITED) // 模糊任务队列
    private var blurTaskProcessor: Job? = null // 处理模糊任务的协程
    private var lastBlurTaskStartTime = 0L // 上次任务开始时间
    private val BLUR_TASK_TIME_THRESHOLD = 30L // 任务处理时间阈值，单位毫秒
    
    // 定义模糊任务数据类
    private data class BlurTask(
        val radius: Float,
        val downscaleFactor: Float, 
        val iterations: Int,
        val timestamp: Long = System.currentTimeMillis()
    )

    // --- P1 编辑模式相关 ---
    private var isInP1EditMode: Boolean = false
    private val p1EditMatrix = Matrix()
    private var currentEditP1HeightRatio: Float = WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO
    private var currentEditP1ContentScaleFactor: Float = WallpaperConfigConstants.DEFAULT_P1_CONTENT_SCALE_FACTOR
    private val p1DisplayRectView = RectF()

    private lateinit var p1ContentDragGestureDetector: GestureDetector
    private lateinit var p1ContentScaleGestureDetector: ScaleGestureDetector

    private var isP1HeightResizing: Boolean = false
    private var p1HeightResizeStartRawY: Float = 0f
    private var p1HeightResizeStartRatio: Float = 0f

    private val p1HeightResizeHandlePaint = Paint().apply {
        color = Color.argb(200, 255, 223, 0)
        style = Paint.Style.FILL
    }
    private val p1HeightResizeHandleRect = RectF()
    private val p1HeightResizeTouchSlopFactor = 2.5f

    private var p1UserMinScaleFactorRelativeToCover = 1.0f
    private var p1UserMaxScaleFactorRelativeToCover = 4.0f

    // 节流相关
    private val mainHandler = Handler(Looper.getMainLooper())
    private val P1_CONFIG_UPDATE_THROTTLE_MS = 150L
    private var lastP1ConfigUpdateTime: Long = 0L
    private var isThrottledP1ConfigUpdatePending: Boolean = false
    private var throttledP1ConfigUpdateRunnable: Runnable? = null

    private var onP1ConfigEditedListener: ((normalizedX: Float, normalizedY: Float, heightRatio: Float, contentScale: Float) -> Unit)? = null
    private var onRequestActionCallback: ((action: PreviewViewAction) -> Unit)? = null
    enum class PreviewViewAction { REQUEST_CANCEL_P1_EDIT_MODE }


    private val p1EditContentPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val p1EditBorderPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        alpha = 220
        pathEffect = DashPathEffect(floatArrayOf(8f * resources.displayMetrics.density, 4f * resources.displayMetrics.density), 0f)
    }
    private val p1OverlayBgPaint = Paint()

    // Flicker fix: Transition state
    private var isTransitioningFromEditMode: Boolean = false
    private val transitionMatrix = Matrix()

    init {
        val viewConfig = ViewConfiguration.get(context)
        touchSlop = viewConfig.scaledTouchSlop
        minFlingVelocity = viewConfig.scaledMinimumFlingVelocity
        maxFlingVelocity = viewConfig.scaledMaximumFlingVelocity
        pageScroller = OverScroller(context)
        p1ContentScroller = OverScroller(context)
        initializeP1GestureDetectors()
    }

    /**
     * 初始化P1内容拖动和缩放的手势检测器
     */
    private fun initializeP1GestureDetectors() {
        p1ContentDragGestureDetector = GestureDetector(context, P1ContentGestureListener())
        p1ContentScaleGestureDetector = ScaleGestureDetector(context, P1ContentScaleListener())
    }

    /**
     * 设置P1配置编辑监听器，当P1区域的焦点、高度比例或内容缩放比例变化时回调
     */
    fun setOnP1ConfigEditedListener(listener: ((normalizedX: Float, normalizedY: Float, heightRatio: Float, contentScale: Float) -> Unit)?) {
        this.onP1ConfigEditedListener = listener
    }

    /**
     * 设置请求动作回调，用于与外部Activity或Fragment通信
     */
    fun setOnRequestActionCallback(callback: ((action: PreviewViewAction) -> Unit)?) {
        this.onRequestActionCallback = callback
    }

    private val currentEffectiveP1HeightRatio: Float
        get() = if (isInP1EditMode || isTransitioningFromEditMode) currentEditP1HeightRatio else nonEditModePage1ImageHeightRatio


    private val currentEffectiveP1ContentScaleFactor: Float
        get() = if (isInP1EditMode || isTransitioningFromEditMode) currentEditP1ContentScaleFactor else this.currentP1ContentScaleFactor


    /**
     * 当视图大小改变时调用，重新计算布局并加载或更新位图
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val oldViewWidth = viewWidth; val oldViewHeight = viewHeight
        viewWidth = w; viewHeight = h
        Log.d(TAG, "onSizeChanged: New $viewWidth x $viewHeight. EditMode: $isInP1EditMode, Transitioning: $isTransitioningFromEditMode")

        if (w > 0 && h > 0) {
            calculateP1DisplayRectView()

            if (isInP1EditMode && wallpaperBitmaps?.sourceSampledBitmap != null) {
                resetP1EditMatrixToFocus(this.currentNormalizedFocusX, this.currentNormalizedFocusY)
            }

            if (imageUri != null && (w != oldViewWidth || h != oldViewHeight || wallpaperBitmaps?.sourceSampledBitmap == null)) {
                loadFullBitmapsFromUri(this.imageUri, true)
            } else if (imageUri != null && wallpaperBitmaps?.sourceSampledBitmap != null && wallpaperBitmaps?.page1TopCroppedBitmap == null && !isInP1EditMode && !isTransitioningFromEditMode) {
                updateOnlyPage1TopCroppedBitmap(nonEditModePage1ImageHeightRatio, wallpaperBitmaps!!.sourceSampledBitmap!!, this.currentP1ContentScaleFactor)
            } else {
                invalidate()
            }
        }
    }

    /**
     * 计算P1显示区域的矩形和调整手柄的位置
     */
    private fun calculateP1DisplayRectView() {
        if (viewWidth <= 0 || viewHeight <= 0) {
            p1DisplayRectView.setEmpty()
            return
        }
        val p1ActualHeight = viewHeight * currentEffectiveP1HeightRatio
        p1DisplayRectView.set(0f, 0f, viewWidth.toFloat(), p1ActualHeight)

        val handleVisualHeight = 8f * resources.displayMetrics.density
        val handleWidth = p1DisplayRectView.width() * 0.3f
        p1HeightResizeHandleRect.set(
            p1DisplayRectView.centerX() - handleWidth / 2f,
            p1DisplayRectView.bottom - handleVisualHeight,
            p1DisplayRectView.centerX() + handleWidth / 2f,
            p1DisplayRectView.bottom
        )
    }

    /**
     * 设置P1焦点编辑模式，用于进入或退出P1区域的编辑状态
     */
    fun setP1FocusEditMode(
        isEditing: Boolean,
        initialNormFocusX: Float? = null,
        initialNormFocusY: Float? = null,
        initialHeightRatio: Float? = null,
        initialContentScale: Float? = null
    ) {
        val wasEditing = this.isInP1EditMode

        if (wasEditing && isEditing) {
            Log.d(TAG, "setP1FocusEditMode: Staying in edit mode. Syncing params if provided.")
            currentEditP1HeightRatio = initialHeightRatio ?: currentEditP1HeightRatio
            this.currentNormalizedFocusX = initialNormFocusX ?: this.currentNormalizedFocusX
            this.currentNormalizedFocusY = initialNormFocusY ?: this.currentNormalizedFocusY
            currentEditP1ContentScaleFactor = initialContentScale ?: currentEditP1ContentScaleFactor

            calculateP1DisplayRectView()
            if (wallpaperBitmaps?.sourceSampledBitmap != null) {
                resetP1EditMatrixToFocus(this.currentNormalizedFocusX, this.currentNormalizedFocusY)
            }
            this.isTransitioningFromEditMode = false
            invalidate()
            return
        }

        this.isInP1EditMode = isEditing

        if (wasEditing && !isEditing) { // Exiting edit mode (true -> false)
            Log.d(TAG, "setP1FocusEditMode: Exiting edit mode. Capturing last matrix and starting transition.")
            this.isTransitioningFromEditMode = true
            this.transitionMatrix.set(p1EditMatrix)

            parent?.requestDisallowInterceptTouchEvent(false)
            currentEditP1HeightRatio = nonEditModePage1ImageHeightRatio
            calculateP1DisplayRectView()

            if (imageUri != null && wallpaperBitmaps?.sourceSampledBitmap != null) {
                updateOnlyPage1TopCroppedBitmap(
                    heightRatioToUse = nonEditModePage1ImageHeightRatio,
                    sourceBitmap = wallpaperBitmaps!!.sourceSampledBitmap!!,
                    contentScaleToUse = this.currentP1ContentScaleFactor,
                    onComplete = {
                        Log.d(TAG, "setP1FocusEditMode: P1 top cropped bitmap update complete (on exit). Ending transition.")
                        this.isTransitioningFromEditMode = false
                        invalidate()
                    }
                )
            } else {
                this.isTransitioningFromEditMode = false
                invalidate()
            }
            if (!pageScroller.isFinished) pageScroller.abortAnimation()
            if (!p1ContentScroller.isFinished) p1ContentScroller.forceFinished(true)


        } else if (!wasEditing && isEditing) { // Entering edit mode (false -> true)
            Log.d(TAG, "setP1FocusEditMode: Entering edit mode.")
            this.isTransitioningFromEditMode = false

            if (wallpaperBitmaps?.sourceSampledBitmap == null) {
                Log.w(TAG, "P1EditMode: No source bitmap. Requesting cancel.")
                this.isInP1EditMode = false;
                onRequestActionCallback?.invoke(PreviewViewAction.REQUEST_CANCEL_P1_EDIT_MODE)
                invalidate()
                return
            }

            currentEditP1HeightRatio = initialHeightRatio ?: nonEditModePage1ImageHeightRatio
            this.currentNormalizedFocusX = initialNormFocusX ?: this.currentNormalizedFocusX
            this.currentNormalizedFocusY = initialNormFocusY ?: this.currentNormalizedFocusY
            currentEditP1ContentScaleFactor = initialContentScale ?: this.currentP1ContentScaleFactor

            calculateP1DisplayRectView()
            resetP1EditMatrixToFocus(this.currentNormalizedFocusX, this.currentNormalizedFocusY)

            parent?.requestDisallowInterceptTouchEvent(true)
            isPageSwiping = false
            if (!pageScroller.isFinished) pageScroller.abortAnimation()
            if (!p1ContentScroller.isFinished) p1ContentScroller.forceFinished(true) // Stop P1 fling too
            invalidate()

        } else { // Mode not changing
            Log.d(TAG, "setP1FocusEditMode: Mode not changing or initial call. current isEditing: $isEditing")
            this.isTransitioningFromEditMode = false
            calculateP1DisplayRectView()
            if (!this.isInP1EditMode && imageUri != null && wallpaperBitmaps?.sourceSampledBitmap != null) {
                updateOnlyPage1TopCroppedBitmap(nonEditModePage1ImageHeightRatio, wallpaperBitmaps!!.sourceSampledBitmap!!, this.currentP1ContentScaleFactor)
            } else {
                invalidate()
            }
        }
    }


    /**
     * 重置P1编辑矩阵以将图像焦点居中显示
     */
    private fun resetP1EditMatrixToFocus(normFocusX: Float, normFocusY: Float) {
        val source = wallpaperBitmaps?.sourceSampledBitmap
        if (source == null || source.isRecycled || p1DisplayRectView.isEmpty) {
            p1EditMatrix.reset(); invalidate(); return
        }

        val baseFillScale = calculateP1BaseFillScale(source, p1DisplayRectView)
        val totalEffectiveScale = baseFillScale * currentEditP1ContentScaleFactor

        p1EditMatrix.reset()
        if (totalEffectiveScale > 0.00001f) {
            p1EditMatrix.setScale(totalEffectiveScale, totalEffectiveScale)
        } else {
            Log.w(TAG, "resetP1EditMatrixToFocus: totalEffectiveScale ($totalEffectiveScale) is invalid, using baseFillScale ($baseFillScale). currentEditP1ContentScaleFactor was $currentEditP1ContentScaleFactor")
            if (baseFillScale > 0.00001f) p1EditMatrix.setScale(baseFillScale, baseFillScale)
            else p1EditMatrix.setScale(1.0f, 1.0f)
        }

        val currentActualScaleApplied = getCurrentP1EditMatrixScale()
        val focusSourcePxX = normFocusX * source.width
        val focusSourcePxY = normFocusY * source.height
        val scaledFocusSourcePxX = focusSourcePxX * currentActualScaleApplied
        val scaledFocusSourcePxY = focusSourcePxY * currentActualScaleApplied

        val p1CenterX = p1DisplayRectView.centerX()
        val p1CenterY = p1DisplayRectView.centerY()
        val translateX = p1CenterX - scaledFocusSourcePxX
        val translateY = p1CenterY - scaledFocusSourcePxY
        p1EditMatrix.postTranslate(translateX, translateY)

        applyP1EditMatrixBounds()
        invalidate()
    }

    /**
     * 计算P1基本填充缩放比例，确保图像至少填满目标区域
     */
    private fun calculateP1BaseFillScale(source: Bitmap, targetRect: RectF): Float {
        if (source.width <= 0 || source.height <= 0 || targetRect.width() <= 0 || targetRect.height() <= 0) return 1.0f
        return max(targetRect.width() / source.width.toFloat(), targetRect.height() / source.height.toFloat())
    }

    /**
     * 获取当前P1编辑矩阵的缩放值
     */
    private fun getCurrentP1EditMatrixScale(): Float {
        val values = FloatArray(9); p1EditMatrix.getValues(values); return values[Matrix.MSCALE_X]
    }

    /**
     * 应用P1编辑矩阵边界限制，防止图像超出显示区域或缩放不合理
     */
    private fun applyP1EditMatrixBounds() {
        val source = wallpaperBitmaps?.sourceSampledBitmap ?: return
        if (p1DisplayRectView.isEmpty || source.isRecycled || source.width == 0 || source.height == 0) return

        var currentMatrixScaleVal = getCurrentP1EditMatrixScale()
        val baseFillScale = calculateP1BaseFillScale(source, p1DisplayRectView)
        if (baseFillScale <= 0.00001f) { Log.e(TAG, "applyP1EditMatrixBounds: baseFillScale is zero or too small!"); return }

        val minAllowedGlobalScale = baseFillScale * p1UserMinScaleFactorRelativeToCover
        val maxAllowedGlobalScale = baseFillScale * p1UserMaxScaleFactorRelativeToCover
        var scaleCorrectionFactor = 1.0f

        if (currentMatrixScaleVal < minAllowedGlobalScale) {
            scaleCorrectionFactor = if (currentMatrixScaleVal > 0.00001f) minAllowedGlobalScale / currentMatrixScaleVal else 0f
        } else if (currentMatrixScaleVal > maxAllowedGlobalScale) {
            scaleCorrectionFactor = if (currentMatrixScaleVal > 0.00001f) maxAllowedGlobalScale / currentMatrixScaleVal else 0f
        }

        if (scaleCorrectionFactor == 0f) {
            Log.w(TAG, "applyP1EditMatrixBounds: scaleCorrectionFactor is zero, resetting matrix to safe scale.")
            resetP1EditMatrixToFocus(this.currentNormalizedFocusX,this.currentNormalizedFocusY); return
        }

        if (abs(scaleCorrectionFactor - 1.0f) > 0.0001f) {
            p1EditMatrix.postScale(scaleCorrectionFactor, scaleCorrectionFactor, p1DisplayRectView.centerX(), p1DisplayRectView.centerY())
        }
        currentEditP1ContentScaleFactor = (getCurrentP1EditMatrixScale() / baseFillScale).coerceIn(p1UserMinScaleFactorRelativeToCover, p1UserMaxScaleFactorRelativeToCover)


        val values = FloatArray(9); p1EditMatrix.getValues(values)
        val currentTransX = values[Matrix.MTRANS_X]; val currentTransY = values[Matrix.MTRANS_Y]
        val finalScaleAfterCorrection = getCurrentP1EditMatrixScale()
        val scaledBitmapWidth = source.width * finalScaleAfterCorrection
        val scaledBitmapHeight = source.height * finalScaleAfterCorrection
        var dx = 0f; var dy = 0f

        if (scaledBitmapWidth <= p1DisplayRectView.width() + 0.1f) {
            dx = p1DisplayRectView.centerX() - (currentTransX + scaledBitmapWidth / 2f)
        } else {
            if (currentTransX > p1DisplayRectView.left) dx = p1DisplayRectView.left - currentTransX
            else if (currentTransX + scaledBitmapWidth < p1DisplayRectView.right) dx = p1DisplayRectView.right - (currentTransX + scaledBitmapWidth)
        }
        if (scaledBitmapHeight <= p1DisplayRectView.height() + 0.1f) {
            dy = p1DisplayRectView.centerY() - (currentTransY + scaledBitmapHeight / 2f)
        } else {
            if (currentTransY > p1DisplayRectView.top) dy = p1DisplayRectView.top - currentTransY
            else if (currentTransY + scaledBitmapHeight < p1DisplayRectView.bottom) dy = p1DisplayRectView.bottom - (currentTransY + scaledBitmapHeight)
        }
        if (abs(dx) > 0.001f || abs(dy) > 0.001f) p1EditMatrix.postTranslate(dx, dy)
    }

    /**
     * 尝试节流P1配置更新，避免频繁更新
     */
    private fun attemptThrottledP1ConfigUpdate() {
        if (!isInP1EditMode) return
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastP1ConfigUpdateTime >= P1_CONFIG_UPDATE_THROTTLE_MS) {
            executeP1ConfigUpdate()
        } else {
            isThrottledP1ConfigUpdatePending = true
            if (throttledP1ConfigUpdateRunnable == null) {
                throttledP1ConfigUpdateRunnable = Runnable {
                    if (isThrottledP1ConfigUpdatePending) executeP1ConfigUpdate()
                }
                mainHandler.postDelayed(throttledP1ConfigUpdateRunnable!!, P1_CONFIG_UPDATE_THROTTLE_MS - (currentTime - lastP1ConfigUpdateTime))
            }
        }
    }

    /**
     * 执行P1配置更新，计算新的焦点位置并通知监听器
     */
    private fun executeP1ConfigUpdate() {
        lastP1ConfigUpdateTime = System.currentTimeMillis()
        isThrottledP1ConfigUpdatePending = false
        throttledP1ConfigUpdateRunnable?.let { mainHandler.removeCallbacks(it) }
        throttledP1ConfigUpdateRunnable = null

        val source = wallpaperBitmaps?.sourceSampledBitmap ?: return
        if (p1DisplayRectView.isEmpty || source.width <= 0 || source.height <= 0) {
            onP1ConfigEditedListener?.invoke(this.currentNormalizedFocusX, this.currentNormalizedFocusY, currentEditP1HeightRatio, currentEditP1ContentScaleFactor)
            return
        }
        val viewCenter = floatArrayOf(p1DisplayRectView.centerX(), p1DisplayRectView.centerY())
        val invertedMatrix = Matrix()
        if (!p1EditMatrix.invert(invertedMatrix)) {
            Log.e(TAG, "executeP1ConfigUpdate: p1EditMatrix non-invertible!")
            onP1ConfigEditedListener?.invoke(this.currentNormalizedFocusX, this.currentNormalizedFocusY, currentEditP1HeightRatio, currentEditP1ContentScaleFactor)
            return
        }
        invertedMatrix.mapPoints(viewCenter)
        val newNormX = (viewCenter[0] / source.width.toFloat()).coerceIn(0f, 1f)
        val newNormY = (viewCenter[1] / source.height.toFloat()).coerceIn(0f, 1f)

        this.currentNormalizedFocusX = newNormX
        this.currentNormalizedFocusY = newNormY

        Log.d(TAG, "executeP1ConfigUpdate: Calling listener with NXY=($newNormX,$newNormY), HR=$currentEditP1HeightRatio, CS=$currentEditP1ContentScaleFactor")
        onP1ConfigEditedListener?.invoke(newNormX, newNormY, currentEditP1HeightRatio, currentEditP1ContentScaleFactor)
    }

    private inner class P1ContentGestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            if (isInP1EditMode && wallpaperBitmaps?.sourceSampledBitmap != null && p1DisplayRectView.contains(e.x, e.y) && !isP1HeightResizing) {
                if (!p1ContentScroller.isFinished) {
                    p1ContentScroller.forceFinished(true)
                }
                return true // 确保 GestureDetector 处理后续事件
            }
            return false
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // 在 P1 编辑模式下，如果 GestureDetector 确认这是一个单击
            if (isInP1EditMode && p1DisplayRectView.contains(e.x, e.y) && !isP1HeightResizing) {
                Log.d(TAG, "P1ContentGestureListener: onSingleTapUp in P1 Edit mode. Calling performClick().")
                // 我们在这里触发外部的 OnClickListener
                performClick()
                return true // 单击事件已处理
            }
            return super.onSingleTapUp(e)
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (e1 == null || !isInP1EditMode || isP1HeightResizing || wallpaperBitmaps?.sourceSampledBitmap == null || !p1DisplayRectView.contains(e1.x, e1.y)) {
                return false
            }
            p1EditMatrix.postTranslate(-distanceX, -distanceY)
            applyP1EditMatrixBounds()
            attemptThrottledP1ConfigUpdate()
            invalidate()
            return true
        }
        // ... onFling 等其他方法保持不变 ...
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (!isInP1EditMode || isP1HeightResizing || wallpaperBitmaps?.sourceSampledBitmap == null || e1 == null || !p1DisplayRectView.contains(e1.x, e1.y)) {
                return false
            }

            val source = wallpaperBitmaps!!.sourceSampledBitmap!!
            if (source.isRecycled || source.width == 0 || source.height == 0) return false

            p1ContentScroller.forceFinished(true)
            lastP1ScrollerX = 0
            lastP1ScrollerY = 0

            p1ContentScroller.fling(
                0, 0,
                velocityX.toInt(), velocityY.toInt(),
                Int.MIN_VALUE, Int.MAX_VALUE,
                Int.MIN_VALUE, Int.MAX_VALUE,
                (p1DisplayRectView.width() / 4).toInt().coerceAtLeast(1),
                (p1DisplayRectView.height() / 4).toInt().coerceAtLeast(1)
            )
            postInvalidateOnAnimation()
            return true
        }
    }

    private inner class P1ContentScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            if (isInP1EditMode && !isP1HeightResizing && wallpaperBitmaps?.sourceSampledBitmap != null && p1DisplayRectView.contains(detector.focusX, detector.focusY)) {
                if (!p1ContentScroller.isFinished) {
                    p1ContentScroller.forceFinished(true)
                }
                return true
            }
            return false
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (!isInP1EditMode || isP1HeightResizing || wallpaperBitmaps?.sourceSampledBitmap == null) return false
            val scaleFactor = detector.scaleFactor
            if (scaleFactor != 0f && abs(scaleFactor - 1.0f) > 0.0001f) {
                p1EditMatrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
                applyP1EditMatrixBounds()
                attemptThrottledP1ConfigUpdate()
                invalidate()
            }
            return true
        }
    }

    /**
     * 处理视图上的触摸事件，管理页面滑动、P1编辑和高度调整等交互
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val touchX = event.x
        val touchY = event.y
        // 添加日志以便调试
        // Log.d("WP_TOUCH", "onTouchEvent: action=${event.actionToString(event.actionMasked)}, x=$touchX, y=$touchY, isInP1EditMode=$isInP1EditMode, isP1Resizing=$isP1HeightResizing")

        var eventHandled = false // 用于跟踪事件是否已被处理

        // --- P1编辑模式下的专属手势处理 ---
        if (isInP1EditMode && wallpaperBitmaps?.sourceSampledBitmap != null) {
            // 1. P1 高度调整手势 (最高优先级)
            //    只在ACTION_DOWN时检查是否开始高度调整，或在isP1HeightResizing为true时处理后续事件
            if (event.actionMasked == MotionEvent.ACTION_DOWN && !isP1HeightResizing) { // 避免重复检查
                val effectiveTouchSlop = touchSlop * p1HeightResizeTouchSlopFactor
                val heightHandleHotZone = RectF(
                    p1DisplayRectView.left,
                    p1DisplayRectView.bottom - effectiveTouchSlop,
                    p1DisplayRectView.right,
                    p1DisplayRectView.bottom + effectiveTouchSlop
                )
                if (p1DisplayRectView.height() > effectiveTouchSlop * 1.5f && heightHandleHotZone.contains(touchX, touchY)) {
                    if (!p1ContentScroller.isFinished) { p1ContentScroller.forceFinished(true) }
                    isP1HeightResizing = true
                    p1HeightResizeStartRawY = event.rawY // 使用rawY以避免视图滚动影响
                    p1HeightResizeStartRatio = currentEditP1HeightRatio
                    parent?.requestDisallowInterceptTouchEvent(true)
                    // Log.d("WP_TOUCH", "P1 Height Resizing STARTED")
                    eventHandled = true // DOWN事件被高度调整逻辑初步处理
                }
            }

            if (isP1HeightResizing) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        val dy = event.rawY - p1HeightResizeStartRawY
                        val deltaRatio = dy / viewHeight.toFloat()
                        var newRatio = p1HeightResizeStartRatio + deltaRatio
                        newRatio = newRatio.coerceIn(
                            WallpaperConfigConstants.MIN_HEIGHT_RATIO,
                            WallpaperConfigConstants.MAX_HEIGHT_RATIO
                        )
                        if (abs(newRatio - currentEditP1HeightRatio) > 0.002f) {
                            currentEditP1HeightRatio = newRatio
                            calculateP1DisplayRectView()
                            resetP1EditMatrixToFocus(this.currentNormalizedFocusX, this.currentNormalizedFocusY)
                            attemptThrottledP1ConfigUpdate()
                        }
                        eventHandled = true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isThrottledP1ConfigUpdatePending) executeP1ConfigUpdate()
                        isP1HeightResizing = false
                        parent?.requestDisallowInterceptTouchEvent(false)
                        // Log.d("WP_TOUCH", "P1 Height Resizing ENDED")
                        eventHandled = true // UP/CANCEL事件结束了高度调整
                    }
                    // 对于ACTION_DOWN，如果isP1HeightResizing已经是true（理论上不应该），也标记为已处理
                    MotionEvent.ACTION_DOWN -> eventHandled = true
                }
                if (eventHandled) return true // 如果高度调整正在进行或刚结束，它消耗所有相关事件
            }

            // 2. P1 内容缩放 和 P1内容拖动/P1区域内单击 手势检测器
            //    仅当触摸点在 P1 显示区域内时，才让这些检测器处理。
            //    并且确保不是在高度调整中。
            var handledByP1SpecificDetectors = false
            if (p1DisplayRectView.contains(touchX, touchY) && !isP1HeightResizing) {
                // Log.d("WP_TOUCH", "P1 Edit: Event within P1 display rect, trying detectors.")
                // ScaleGestureDetector通常应该先于DragGestureDetector，以避免缩放时的焦点跳动被误认为拖动。
                // 然而，如果onTouchEvent中先调用一个，它返回true，另一个就不会被调用。
                // 标准做法是两个都调用，因为它们内部会正确处理事件。
                val scaleResult = p1ContentScaleGestureDetector.onTouchEvent(event)
                // 即使缩放处理了，单击/拖动检测器也应该有机会处理（例如，如果它是一个没有缩放的点击）
                // 但如果scaleResult为true，通常意味着是一个缩放手势，不太可能是单击。
                // GestureDetector处理单击和拖动。
                val dragOrTapResult = p1ContentDragGestureDetector.onTouchEvent(event)

                if (scaleResult || dragOrTapResult) {
                    // Log.d("WP_TOUCH", "P1 Edit: Handled by Scale ($scaleResult) or Drag/Tap ($dragOrTapResult)")
                    handledByP1SpecificDetectors = true
                }
            }

            if (handledByP1SpecificDetectors) {
                // 如果是P1区域内的特定手势（拖动、缩放、P1内单击），则它们优先处理
                if (event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                } else {
                    if (isThrottledP1ConfigUpdatePending) executeP1ConfigUpdate()
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                return true // P1专属手势消耗了事件
            }
            // 如果在P1编辑模式，但事件既不是高度调整，也不是P1区域内的特定手势（例如，点击P1区域外部）
            // 那么事件会继续流向下面的全局处理逻辑。
        }

        // --- 全局触摸处理逻辑 (适用于非P1编辑模式，或P1编辑模式下未被上述P1专属逻辑消耗的事件) ---
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker!!.addMovement(event)

        val action = event.actionMasked
        val currentGlobalX = event.x

        var globalEventHandled = false

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (!pageScroller.isFinished) pageScroller.abortAnimation()
                if (isInP1EditMode && !p1ContentScroller.isFinished) p1ContentScroller.forceFinished(true)

                lastTouchX = currentGlobalX
                downTouchX = currentGlobalX
                activePointerId = event.getPointerId(0)
                isPageSwiping = false
                parent?.requestDisallowInterceptTouchEvent(true) // 请求父容器不拦截
                // Log.d("WP_TOUCH", "Global ACTION_DOWN captured.")
                globalEventHandled = true // 总是处理DOWN，以接收后续事件
            }

            MotionEvent.ACTION_MOVE -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) return false // 或 globalEventHandled
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex < 0) return false // 或 globalEventHandled

                val moveX = event.getX(pointerIndex)
                val deltaX = lastTouchX - moveX

                // 页面滑动逻辑只在非P1编辑模式下生效
                if (!isInP1EditMode) {
                    if (!isPageSwiping && abs(moveX - downTouchX) > touchSlop) {
                        isPageSwiping = true
                        parent?.requestDisallowInterceptTouchEvent(true) // 确保滑动时父容器不拦截
                        // Log.d("WP_TOUCH", "Global Page Swiping STARTED.")
                    }
                    if (isPageSwiping) {
                        currentPreviewXOffset = if (viewWidth > 0 && numVirtualPages > 1) {
                            (currentPreviewXOffset + deltaX / (viewWidth.toFloat() * (numVirtualPages - 1)))
                                .coerceIn(0f, 1f)
                        } else 0f
                        invalidate()
                    }
                }
                lastTouchX = moveX
                globalEventHandled = true // MOVE事件被我们考虑（即使只是更新lastTouchX）
            }

            MotionEvent.ACTION_UP -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) {
                    recycleVelocityTracker()
                    isPageSwiping = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return false // 无效指针，未处理
                }

                if (isPageSwiping) { // isPageSwiping 只在 !isInP1EditMode 时为 true
                    val vt = this.velocityTracker!!
                    vt.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                    val velocityX = vt.getXVelocity(activePointerId)
                    if (abs(velocityX) > minFlingVelocity && numVirtualPages > 1) {
                        // Log.d("WP_TOUCH", "Global ACTION_UP: Flinging page.")
                        flingPage(velocityX)
                    } else {
                        // Log.d("WP_TOUCH", "Global ACTION_UP: Snapping page after swipe.")
                        snapToNearestPage(currentPreviewXOffset)
                    }
                    globalEventHandled = true
                } else {
                    // 不是页面滑动，则判断为单击
                    if (abs(currentGlobalX - downTouchX) < touchSlop) {
                        // Log.d("WP_TOUCH", "Global ACTION_UP: Valid click detected. Calling performClick(). isInP1EditMode: $isInP1EditMode")
                        performClick() // 无论是否在P1编辑模式，都调用 performClick
                        globalEventHandled = true
                    } else {
                        // Log.d("WP_TOUCH", "Global ACTION_UP: Not a swipe, but moved too far for a click.")
                        // 如果移动超过touchSlop但不足以判定为isPageSwiping
                        // 只有在非P1编辑模式下，才考虑吸附到最近页面
                        if (!isInP1EditMode) {
                            snapToNearestPage(currentPreviewXOffset)
                        }
                        globalEventHandled = true // 认为这个拖拽结束也被处理了
                    }
                }
                recycleVelocityTracker()
                activePointerId = MotionEvent.INVALID_POINTER_ID
                isPageSwiping = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }

            MotionEvent.ACTION_CANCEL -> {
                // Log.d("WP_TOUCH", "Global ACTION_CANCEL received.")
                if (isPageSwiping && !isInP1EditMode) { // isPageSwiping 只在非P1编辑模式为true
                    snapToNearestPage(currentPreviewXOffset)
                }
                recycleVelocityTracker()
                activePointerId = MotionEvent.INVALID_POINTER_ID
                isPageSwiping = false
                parent?.requestDisallowInterceptTouchEvent(false)
                globalEventHandled = true // CANCEL事件我们通常认为已处理
            }
        }

        // 如果我们的自定义逻辑处理了事件，则返回true
        if (globalEventHandled) {
            return true
        }

        // 否则，调用父类的实现
        // Log.d("WP_TOUCH", "Global: Event not handled by custom logic, calling super.onTouchEvent for action ${event.actionToString(event.actionMasked)}")
        return super.onTouchEvent(event)
    }

    /**
     * 绘制视图内容，根据不同模式展示壁纸预览、编辑状态或占位符
     */
    override fun onDraw(canvas: Canvas) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        val cWBM = wallpaperBitmaps

        if (isTransitioningFromEditMode && cWBM?.sourceSampledBitmap != null) {
            val sourceToDraw = cWBM.sourceSampledBitmap!!
            canvas.drawColor(Color.DKGRAY)
            canvas.save()
            canvas.clipRect(p1DisplayRectView)
            canvas.concat(transitionMatrix)
            canvas.drawBitmap(sourceToDraw, 0f, 0f, p1EditContentPaint)
            canvas.restore()
            p1OverlayBgPaint.color = selectedBackgroundColor
            if (p1DisplayRectView.bottom < viewHeight) {
                canvas.drawRect(0f, p1DisplayRectView.bottom, viewWidth.toFloat(), viewHeight.toFloat(), p1OverlayBgPaint)
            }
        } else if (isInP1EditMode && cWBM?.sourceSampledBitmap != null) {
            val sTD = cWBM.sourceSampledBitmap!!
            canvas.drawColor(Color.DKGRAY)
            canvas.save()
            canvas.clipRect(p1DisplayRectView)
            canvas.concat(p1EditMatrix)
            canvas.drawBitmap(sTD, 0f, 0f, p1EditContentPaint)
            canvas.restore()
            canvas.drawRect(p1DisplayRectView, p1EditBorderPaint)
            if (p1DisplayRectView.height() > touchSlop * p1HeightResizeTouchSlopFactor * 1.2f) {
                canvas.drawRoundRect(p1HeightResizeHandleRect, 5f * resources.displayMetrics.density, 5f * resources.displayMetrics.density, p1HeightResizeHandlePaint)
            }
            p1OverlayBgPaint.color = selectedBackgroundColor
            if (p1DisplayRectView.bottom < viewHeight) {
                canvas.drawRect(0f, p1DisplayRectView.bottom, viewWidth.toFloat(), viewHeight.toFloat(), p1OverlayBgPaint)
            }
        } else {
            if (cWBM?.sourceSampledBitmap != null) {
                val cfg = SharedWallpaperRenderer.WallpaperConfig(
                    viewWidth, viewHeight, selectedBackgroundColor,
                    nonEditModePage1ImageHeightRatio,
                    currentPreviewXOffset, numVirtualPages, currentP1OverlayFadeRatio,
                    currentScrollSensitivity, currentNormalizedInitialBgScrollOffset,
                    currentP2BackgroundFadeInRatio, currentP1ShadowRadius, currentP1ShadowDx,
                    currentP1ShadowDy, currentP1ShadowColor, currentP1ImageBottomFadeHeight
                )
                SharedWallpaperRenderer.drawFrame(canvas, cfg, cWBM)
            } else {
                val loadingText = context.getString(R.string.image_loading_in_preview_placeholder)
                val selectImageText = context.getString(R.string.please_select_image_for_preview_placeholder)
                SharedWallpaperRenderer.drawPlaceholder(
                    canvas, viewWidth, viewHeight,
                    if (imageUri != null && (fullBitmapLoadingJob?.isActive == true || topBitmapUpdateJob?.isActive == true)) loadingText
                    else selectImageText
                )
            }
        }
    }


    /**
     * 设置配置值，更新预览参数并根据需要刷新视图
     */
    fun setConfigValues(
        scrollSensitivity: Float, p1OverlayFadeRatio: Float, backgroundBlurRadius: Float,
        snapAnimationDurationMs: Long, normalizedInitialBgScrollOffset: Float,
        p2BackgroundFadeInRatio: Float, blurDownscaleFactor: Float, blurIterations: Int,
        p1ShadowRadius: Float, p1ShadowDx: Float, p1ShadowDy: Float,
        p1ShadowColor: Int, p1ImageBottomFadeHeight: Float
    ) {
        val oldBgBlurR = this.currentBackgroundBlurRadius
        val oldBgBlurDF = this.currentBlurDownscaleFactor
        val oldBgBlurIt = this.currentBlurIterations
        val oldP1ImageBottomFadeHeight = this.currentP1ImageBottomFadeHeight

        this.currentScrollSensitivity = scrollSensitivity.coerceIn(0.1f, 5.0f)
        this.currentP1OverlayFadeRatio = p1OverlayFadeRatio.coerceIn(0.01f, 1.0f)
        this.currentP2BackgroundFadeInRatio = p2BackgroundFadeInRatio.coerceIn(0.0f, 1.0f)
        this.currentBackgroundBlurRadius = backgroundBlurRadius.coerceIn(0f, 50f)
        this.currentSnapAnimationDurationMs = snapAnimationDurationMs
        this.currentNormalizedInitialBgScrollOffset = normalizedInitialBgScrollOffset.coerceIn(0f, 1f)
        this.currentBlurDownscaleFactor = blurDownscaleFactor.coerceIn(0.05f, 1.0f)
        this.currentBlurIterations = blurIterations.coerceIn(1, 3)
        this.currentP1ShadowRadius = p1ShadowRadius.coerceIn(0f, 50f)
        this.currentP1ShadowDx = p1ShadowDx.coerceIn(-50f, 50f)
        this.currentP1ShadowDy = p1ShadowDy.coerceIn(-50f, 50f)
        this.currentP1ShadowColor = p1ShadowColor
        this.currentP1ImageBottomFadeHeight = p1ImageBottomFadeHeight.coerceAtLeast(0f)

        // 检测底部融入参数变化并快速更新视图
        if (oldP1ImageBottomFadeHeight != this.currentP1ImageBottomFadeHeight) {
            invalidate() // 立即请求重绘，不走复杂的更新流程
            return
        }

        val blurParamsChanged = oldBgBlurR != this.currentBackgroundBlurRadius ||
                oldBgBlurDF != this.currentBlurDownscaleFactor ||
                oldBgBlurIt != this.currentBlurIterations

        if (this.imageUri != null) {
            if (blurParamsChanged && wallpaperBitmaps?.scrollingBackgroundBitmap != null) {
                // 如果只有模糊参数变了，并且我们有未模糊的滚动背景图，尝试只更新模糊
                Log.d(TAG, "setConfigValues: Only blur params changed, attempting to update blur for preview.")
                updateOnlyBlurredBackgroundForPreviewAsync()
            } else if (wallpaperBitmaps?.sourceSampledBitmap == null || blurParamsChanged /*如果其他参数也可能触发重载*/) {
                // 如果没有源图，或者其他参数也变了，或者无法单独更新模糊，则完整重载
                Log.d(TAG, "setConfigValues: Conditions require full bitmap reload for preview.")
                loadFullBitmapsFromUri(this.imageUri, true) // forceInternalReload = true
            } else {
                // 其他参数变了，但可能只需要重绘或更新P1顶图
                if (!isInP1EditMode && !isTransitioningFromEditMode && wallpaperBitmaps?.sourceSampledBitmap != null) {
                    updateOnlyPage1TopCroppedBitmap(nonEditModePage1ImageHeightRatio, wallpaperBitmaps!!.sourceSampledBitmap!!, this.currentP1ContentScaleFactor)
                } else {
                    invalidate()
                }
            }
        } else {
            invalidate() // 没有图片，只重绘占位符
        }
    }

    /**
     * 异步更新背景模糊效果，使用智能任务调度系统
     */
    private fun updateOnlyBlurredBackgroundForPreviewAsync() {
        // 检查必要条件，确保有源图像
        val baseForBlur = wallpaperBitmaps?.scrollingBackgroundBitmap
        if (baseForBlur == null || viewWidth <= 0 || viewHeight <= 0 || imageUri == null) {
            if (imageUri != null) {
                Log.w(TAG, "updateOnlyBlurredBackgroundForPreviewAsync: Base scrolling bitmap is null. Attempting full reload.")
                loadFullBitmapsFromUri(this.imageUri, true)
            } else {
                invalidate()
            }
            return
        }

        Log.d(TAG, "updateOnlyBlurredBackgroundForPreviewAsync: R=$currentBackgroundBlurRadius, DF=$currentBlurDownscaleFactor, It=$currentBlurIterations")
        
        // 创建新的模糊任务并加入队列
        val newTask = BlurTask(
            radius = currentBackgroundBlurRadius,
            downscaleFactor = currentBlurDownscaleFactor,
            iterations = currentBlurIterations
        )
        
        // 确保任务处理器在运行
        if (blurTaskProcessor == null || blurTaskProcessor?.isActive != true) {
            startBlurTaskProcessor()
        }
        
        // 加入任务到队列
        viewScope.launch {
            blurTaskQueue.send(newTask)
            Log.d(TAG, "Added blur task to queue: $newTask")
        }
    }
    
    /**
     * 启动模糊任务处理器，管理任务队列并实现智能调度策略
     */
    private fun startBlurTaskProcessor() {
        blurTaskProcessor?.cancel()
        blurTaskProcessor = viewScope.launch {
            Log.d(TAG, "Starting blur task processor")
            var lastProcessedTask: BlurTask? = null
            var slowTaskDetected = false // 跟踪是否检测到慢速任务
            
            for (task in blurTaskQueue) {
                try {
                    ensureActive()
                    
                    // 如果检测到上一个任务处理时间超过阈值，则执行特殊处理
                    if (slowTaskDetected) {
                        // 清空队列中的所有任务，只保留最新的任务
                        var newestTask: BlurTask? = task
                        while (!blurTaskQueue.isEmpty) {
                            val nextTask = blurTaskQueue.tryReceive().getOrNull()
                            if (nextTask != null) {
                                newestTask = nextTask
                                Log.d(TAG, "Slow task detected, skipping intermediate task")
                            }
                        }
                        
                        // 如果找到了更新的任务，则使用它而不是当前任务
                        if (newestTask != task) {
                            Log.d(TAG, "Slow task detected, jumping to newest task")
                            lastProcessedTask = newestTask
                            // 将最新任务重新放入队列
                            blurTaskQueue.send(newestTask!!)
                            // 重置慢速任务标志
                            slowTaskDetected = false
                            continue
                        }
                        
                        // 重置慢速任务标志
                        slowTaskDetected = false
                    }
                    
                    // 处理当前任务
                    lastProcessedTask = task
                    
                    // 执行模糊处理
                    val baseForBlur = wallpaperBitmaps?.scrollingBackgroundBitmap
                    if (baseForBlur == null || baseForBlur.isRecycled) {
                        Log.w(TAG, "Base bitmap is null or recycled, aborting task")
                        continue
                    }
                    
                    var newBlurredBitmap: Bitmap? = null
                    try {
                        val startTime = SystemClock.elapsedRealtime()
                        newBlurredBitmap = withContext(Dispatchers.IO) {
                            ensureActive()
                            SharedWallpaperRenderer.regenerateBlurredBitmap(
                                context,
                                baseForBlur,
                                baseForBlur.width,
                                baseForBlur.height,
                                task.radius,
                                task.downscaleFactor,
                                task.iterations
                            )
                        }
                        val processingTime = SystemClock.elapsedRealtime() - startTime
                        
                        // 检查任务处理时间是否超过阈值
                        if (processingTime > BLUR_TASK_TIME_THRESHOLD) {
                            Log.d(TAG, "Slow task detected! Processing time: ${processingTime}ms > ${BLUR_TASK_TIME_THRESHOLD}ms")
                            slowTaskDetected = true
                        }
                        
                        ensureActive()
                        val oldBlurred = wallpaperBitmaps?.blurredScrollingBackgroundBitmap
                        if (imageUri != null && wallpaperBitmaps?.scrollingBackgroundBitmap == baseForBlur) {
                            wallpaperBitmaps?.blurredScrollingBackgroundBitmap = newBlurredBitmap
                            if (oldBlurred != newBlurredBitmap) oldBlurred?.recycle()
                            invalidate()
                            Log.d(TAG, "Blur task completed in ${processingTime}ms")
                        } else {
                            newBlurredBitmap?.recycle()
                            Log.d(TAG, "Conditions changed during blur processing, discarded result")
                        }
                    } catch (e: CancellationException) {
                        Log.d(TAG, "Blur task was cancelled")
                        newBlurredBitmap?.recycle()
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing blur task", e)
                        newBlurredBitmap?.recycle()
                    }
                } catch (e: CancellationException) {
                    Log.d(TAG, "Blur task processor was cancelled")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in blur task processor", e)
                }
            }
            Log.d(TAG, "Blur task processor stopped")
            blurTaskProcessor = null
        }
    }

    /**
     * 设置图像URI，加载新图像或重新加载现有图像
     */
    fun setImageUri(uri: Uri?, forceReload: Boolean = false) {
        Log.d(TAG, "setImageUri called: $uri. EditMode: $isInP1EditMode, ForceReload: $forceReload")
        if (isInP1EditMode) {
            onRequestActionCallback?.invoke(PreviewViewAction.REQUEST_CANCEL_P1_EDIT_MODE)
        }

        if (!forceReload && this.imageUri == uri && uri != null && wallpaperBitmaps?.sourceSampledBitmap != null) {
            if (!isInP1EditMode && !isTransitioningFromEditMode) {
                updateOnlyPage1TopCroppedBitmap(nonEditModePage1ImageHeightRatio, wallpaperBitmaps!!.sourceSampledBitmap!!, this.currentP1ContentScaleFactor)
            } else {
                invalidate()
            }
            return
        }

        fullBitmapLoadingJob?.cancel()
        topBitmapUpdateJob?.cancel()

        if (forceReload || this.imageUri != uri) {
            wallpaperBitmaps?.recycleInternals()
            wallpaperBitmaps = null
        }

        this.imageUri = uri
        currentPreviewXOffset = 0f
        if (!pageScroller.isFinished) {
            pageScroller.abortAnimation()
        }
        if (!p1ContentScroller.isFinished) {
            p1ContentScroller.forceFinished(true)
        }


        if (uri != null) {
            invalidate()
            loadFullBitmapsFromUri(uri, true)
        } else {
            wallpaperBitmaps?.recycleInternals()
            wallpaperBitmaps = null
            invalidate()
        }
    }

    /**
     * 从URI加载完整位图集，包括源图、滚动背景和模糊背景
     */
    private fun loadFullBitmapsFromUri(uriToLoad: Uri?, forceInternalReload: Boolean = false) {
        if (uriToLoad == null || viewWidth <= 0 || viewHeight <= 0) {
            if (!forceInternalReload) {
                wallpaperBitmaps?.recycleInternals()
                wallpaperBitmaps = null
            }
            invalidate()
            return
        }

        fullBitmapLoadingJob?.cancel()
        topBitmapUpdateJob?.cancel()

        if (forceInternalReload || wallpaperBitmaps?.sourceSampledBitmap == null) {
            wallpaperBitmaps?.recycleInternals()
            wallpaperBitmaps = null
            invalidate()
        }

        fullBitmapLoadingJob = viewScope.launch {
            var newWpBitmaps: SharedWallpaperRenderer.WallpaperBitmaps? = null
            try {
                ensureActive()
                newWpBitmaps = withContext(Dispatchers.IO) {
                    ensureActive()
                    val heightToUse = nonEditModePage1ImageHeightRatio
                    val focusXToUse = this@WallpaperPreviewView.currentNormalizedFocusX
                    val focusYToUse = this@WallpaperPreviewView.currentNormalizedFocusY
                    val scaleToUse = this@WallpaperPreviewView.currentP1ContentScaleFactor

                    SharedWallpaperRenderer.loadAndProcessInitialBitmaps(
                        context, uriToLoad, viewWidth, viewHeight,
                        heightToUse, focusXToUse, focusYToUse, scaleToUse,
                        currentBackgroundBlurRadius, currentBlurDownscaleFactor, currentBlurIterations
                    )
                }
                ensureActive()

                val oldBitmaps = wallpaperBitmaps
                if (this@WallpaperPreviewView.imageUri == uriToLoad) {
                    wallpaperBitmaps = newWpBitmaps
                    if (oldBitmaps != newWpBitmaps) oldBitmaps?.recycleInternals()

                    if (isInP1EditMode && wallpaperBitmaps?.sourceSampledBitmap != null) {
                        resetP1EditMatrixToFocus(this@WallpaperPreviewView.currentNormalizedFocusX, this@WallpaperPreviewView.currentNormalizedFocusY)
                    } else if (!isInP1EditMode && !isTransitioningFromEditMode && wallpaperBitmaps?.sourceSampledBitmap != null) {
                        updateOnlyPage1TopCroppedBitmap(nonEditModePage1ImageHeightRatio, wallpaperBitmaps!!.sourceSampledBitmap!!, this@WallpaperPreviewView.currentP1ContentScaleFactor)
                    } else if (isTransitioningFromEditMode) {
                        // Transition will handle its own completion and redraw
                    } else {
                        invalidate()
                    }
                } else {
                    newWpBitmaps?.recycleInternals()
                    if (this@WallpaperPreviewView.imageUri == null) {
                        oldBitmaps?.recycleInternals()
                        wallpaperBitmaps = null
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Bitmap loading cancelled for $uriToLoad")
                newWpBitmaps?.recycleInternals()
            } catch (e: Exception) {
                Log.e(TAG, "Async bitmap loading failed for $uriToLoad", e)
                newWpBitmaps?.recycleInternals()
                if (this@WallpaperPreviewView.imageUri == uriToLoad) {
                    wallpaperBitmaps?.recycleInternals()
                    wallpaperBitmaps = null
                }
            } finally {
                if (isActive && coroutineContext[Job] == fullBitmapLoadingJob) {
                    fullBitmapLoadingJob = null
                }
                if (isActive && (this@WallpaperPreviewView.imageUri == uriToLoad || this@WallpaperPreviewView.imageUri == null)) {
                    invalidate()
                }
            }
        }
    }

    /**
     * 设置P1图像高度比例，更新显示区域大小
     */
    fun setPage1ImageHeightRatio(newRatio: Float) {
        val clampedRatio = newRatio.coerceIn(WallpaperConfigConstants.MIN_HEIGHT_RATIO, WallpaperConfigConstants.MAX_HEIGHT_RATIO)
        if (abs(nonEditModePage1ImageHeightRatio - clampedRatio) > 0.001f) {
            nonEditModePage1ImageHeightRatio = clampedRatio
            if (!isInP1EditMode && !isTransitioningFromEditMode) {
                calculateP1DisplayRectView()
                if (imageUri != null && wallpaperBitmaps?.sourceSampledBitmap != null) {
                    updateOnlyPage1TopCroppedBitmap(nonEditModePage1ImageHeightRatio, wallpaperBitmaps!!.sourceSampledBitmap!!, this.currentP1ContentScaleFactor)
                } else {
                    invalidate()
                }
            } else if (isInP1EditMode) {
                currentEditP1HeightRatio = clampedRatio
                calculateP1DisplayRectView()
                resetP1EditMatrixToFocus(this.currentNormalizedFocusX, this.currentNormalizedFocusY)
                attemptThrottledP1ConfigUpdate()
            }
        }
    }

    /**
     * 设置归一化焦点位置，用于确定P1区域显示的图像部分
     */
    fun setNormalizedFocus(focusX: Float, focusY: Float) {
        val clampedFocusX = focusX.coerceIn(0f, 1f)
        val clampedFocusY = focusY.coerceIn(0f, 1f)
        var changed = false
        if (abs(this.currentNormalizedFocusX - clampedFocusX) > 0.001f) { this.currentNormalizedFocusX = clampedFocusX; changed = true }
        if (abs(this.currentNormalizedFocusY - clampedFocusY) > 0.001f) { this.currentNormalizedFocusY = clampedFocusY; changed = true }

        if (changed) {
            if (!isInP1EditMode && !isTransitioningFromEditMode) {
                if (imageUri != null && wallpaperBitmaps?.sourceSampledBitmap != null) {
                    updateOnlyPage1TopCroppedBitmap(nonEditModePage1ImageHeightRatio, wallpaperBitmaps!!.sourceSampledBitmap!!, this.currentP1ContentScaleFactor)
                } else {
                    invalidate()
                }
            } else if (isInP1EditMode) {
                resetP1EditMatrixToFocus(this.currentNormalizedFocusX, this.currentNormalizedFocusY)
                attemptThrottledP1ConfigUpdate()
            }
        }
    }

    /**
     * 设置P1内容缩放因子，控制图像在P1区域的缩放级别
     */
    fun setP1ContentScaleFactor(scale: Float) {
        val clampedScale = scale.coerceIn(WallpaperConfigConstants.DEFAULT_P1_CONTENT_SCALE_FACTOR, p1UserMaxScaleFactorRelativeToCover)
        if (abs(this.currentP1ContentScaleFactor - clampedScale) > 0.001f) {
            this.currentP1ContentScaleFactor = clampedScale
            if (!isInP1EditMode && !isTransitioningFromEditMode) {
                if (imageUri != null && wallpaperBitmaps?.sourceSampledBitmap != null) {
                    updateOnlyPage1TopCroppedBitmap(nonEditModePage1ImageHeightRatio, wallpaperBitmaps!!.sourceSampledBitmap!!, this.currentP1ContentScaleFactor)
                } else {
                    invalidate()
                }
            } else if (isInP1EditMode) {
                currentEditP1ContentScaleFactor = clampedScale
                resetP1EditMatrixToFocus(this.currentNormalizedFocusX, this.currentNormalizedFocusY)
                attemptThrottledP1ConfigUpdate()
            }
        }
    }

    /**
     * 仅更新P1顶部裁剪位图，用于非编辑模式下的显示
     */
    private fun updateOnlyPage1TopCroppedBitmap(
        heightRatioToUse: Float,
        sourceBitmap: Bitmap,
        contentScaleToUse: Float,
        onComplete: (() -> Unit)? = null
    ) {
        if (viewWidth <= 0 || viewHeight <= 0) {
            mainScopeLaunch { onComplete?.invoke() }
            return
        }
        if (isInP1EditMode && !isTransitioningFromEditMode) {
            mainScopeLaunch { onComplete?.invoke() }
            return
        }

        topBitmapUpdateJob?.cancel()
        topBitmapUpdateJob = viewScope.launch {
            var newTopBmp: Bitmap? = null
            try {
                ensureActive()
                newTopBmp = withContext(Dispatchers.Default) {
                    ensureActive()
                    SharedWallpaperRenderer.preparePage1TopCroppedBitmap(
                        sourceBitmap, viewWidth, viewHeight,
                        heightRatioToUse, this@WallpaperPreviewView.currentNormalizedFocusX, this@WallpaperPreviewView.currentNormalizedFocusY, contentScaleToUse
                    )
                }
                ensureActive()
                val oldTopCropped = wallpaperBitmaps?.page1TopCroppedBitmap
                if (this@WallpaperPreviewView.imageUri != null && wallpaperBitmaps?.sourceSampledBitmap == sourceBitmap) {
                    wallpaperBitmaps?.page1TopCroppedBitmap = newTopBmp
                    if (oldTopCropped != newTopBmp) oldTopCropped?.recycle()
                } else {
                    newTopBmp?.recycle()
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "updateOnlyPage1TopCroppedBitmap cancelled")
                newTopBmp?.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "updateOnlyPage1TopCroppedBitmap failed", e)
                newTopBmp?.recycle()
                if (this@WallpaperPreviewView.imageUri != null && wallpaperBitmaps?.sourceSampledBitmap == sourceBitmap) {
                    wallpaperBitmaps?.page1TopCroppedBitmap = null
                }
            } finally {
                if (isActive && coroutineContext[Job] == topBitmapUpdateJob) {
                    topBitmapUpdateJob = null
                }
                mainScopeLaunch {
                    if (isActive) {
                        onComplete?.invoke()
                    }
                }
                if (onComplete == null && isActive && this@WallpaperPreviewView.imageUri != null &&
                    !this@WallpaperPreviewView.isInP1EditMode && !this@WallpaperPreviewView.isTransitioningFromEditMode) {
                    invalidate()
                }
            }
        }
    }

    /**
     * 在主线程协程作用域中启动任务的辅助方法
     */
    private fun mainScopeLaunch(block: suspend CoroutineScope.() -> Unit) {
        if (viewScope.isActive) {
            viewScope.launch {
                block()
            }
        } else {
            Log.w(TAG, "ViewScope not active for mainScopeLaunch.")
        }
    }


    /**
     * 设置选定的背景颜色并刷新视图
     */
    fun setSelectedBackgroundColor(color: Int){
        if(this.selectedBackgroundColor != color){
            this.selectedBackgroundColor = color
            invalidate()
        }
    }

    /**
     * 响应点击事件，调用设置的OnClickListener
     */
    override fun performClick(): Boolean {
        Log.d(TAG, "performClick() called. isInP1EditMode: $isInP1EditMode")
        // super.performClick() 会调用外部设置的 OnClickListener
        // 我们在外部 OnClickListener (MainActivity中) 判断 isP1EditMode
        return super.performClick()
    }

    /**
     * 处理页面快速滑动，计算目标页面并动画滚动到该位置
     */
    private fun flingPage(velocityX: Float) {
        if (isInP1EditMode || numVirtualPages <= 1) {
            if (!pageScroller.isFinished) pageScroller.abortAnimation()
            currentPreviewXOffset = 0f
            invalidate()
            return
        }
        val currentEffectivePageIndex = currentPreviewXOffset * (numVirtualPages - 1)
        var targetPageIndex: Int
        if (velocityX < -minFlingVelocity) {
            targetPageIndex = ceil(currentEffectivePageIndex).toInt()
            if (targetPageIndex <= currentEffectivePageIndex + 0.05f && targetPageIndex < numVirtualPages - 1) {
                targetPageIndex++
            }
        } else if (velocityX > minFlingVelocity) {
            targetPageIndex = floor(currentEffectivePageIndex).toInt()
            if (targetPageIndex >= currentEffectivePageIndex - 0.05f && targetPageIndex > 0) {
                targetPageIndex--
            }
        } else {
            snapToNearestPage(currentPreviewXOffset)
            return
        }
        targetPageIndex = targetPageIndex.coerceIn(0, numVirtualPages - 1)
        val targetXOffset = if (numVirtualPages > 1) targetPageIndex.toFloat() / (numVirtualPages - 1).toFloat() else 0f
        animateToOffset(targetXOffset)
    }

    /**
     * 将页面吸附到最近的整页位置
     */
    private fun snapToNearestPage(currentOffset: Float) {
        if (isInP1EditMode || numVirtualPages <= 1) {
            if (!pageScroller.isFinished) pageScroller.abortAnimation()
            currentPreviewXOffset = 0f
            invalidate()
            return
        }
        val pageIndexFloat = currentOffset * (numVirtualPages - 1)
        val targetPageIndex = pageIndexFloat.roundToInt().coerceIn(0, numVirtualPages - 1)
        val targetXOffset = if (numVirtualPages > 1) targetPageIndex.toFloat() / (numVirtualPages - 1).toFloat() else 0f
        animateToOffset(targetXOffset)
    }

    /**
     * 动画滚动到指定的偏移位置
     */
    private fun animateToOffset(targetXOffset: Float) {
        val currentPixelOffset = (currentPreviewXOffset * getScrollRange()).toInt()
        val targetPixelOffset = (targetXOffset * getScrollRange()).toInt()
        val dx = targetPixelOffset - currentPixelOffset

        if (dx != 0) {
            pageScroller.startScroll(currentPixelOffset, 0, dx, 0, currentSnapAnimationDurationMs.toInt())
            postInvalidateOnAnimation()
        } else {
            this.currentPreviewXOffset = targetXOffset.coerceIn(0f,1f)
            invalidate()
        }
    }

    /**
     * 计算滚动位置，处理页面滚动和P1内容滚动的动画
     */
    override fun computeScroll() {
        var triggerInvalidate = false

        if (pageScroller.computeScrollOffset()) {
            if (isInP1EditMode && !pageScroller.isFinished) {
                pageScroller.abortAnimation()
            } else if (!isInP1EditMode || isTransitioningFromEditMode) { // Allow page scroll if transitioning or not in edit mode
                val currentPixelOffset = pageScroller.currX
                val scrollRange = getScrollRange()
                currentPreviewXOffset = if (scrollRange > 0) {
                    (currentPixelOffset.toFloat() / scrollRange.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                triggerInvalidate = true
            }
        }

        if (p1ContentScroller.computeScrollOffset()) {
            if (isInP1EditMode) {
                val newX = p1ContentScroller.currX
                val newY = p1ContentScroller.currY

                val dx = newX - lastP1ScrollerX
                val dy = newY - lastP1ScrollerY

                if (dx != 0 || dy != 0) {
                    p1EditMatrix.postTranslate(dx.toFloat(), dy.toFloat())
                    applyP1EditMatrixBounds()
                    attemptThrottledP1ConfigUpdate()
                }

                lastP1ScrollerX = newX
                lastP1ScrollerY = newY
                triggerInvalidate = true

                if (p1ContentScroller.isFinished) {
                    executeP1ConfigUpdate()
                }
            } else {
                p1ContentScroller.forceFinished(true)
            }
        }

        if (triggerInvalidate) {
            postInvalidateOnAnimation()
        }
    }


    /**
     * 获取滚动范围的像素值
     */
    private fun getScrollRange(): Int = (numVirtualPages - 1) * 10000

    /**
     * 回收速度追踪器，释放资源
     */
    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    /**
     * 视图从窗口分离时调用，清理所有资源
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 取消所有协程
        viewScope.cancel()
        fullBitmapLoadingJob = null
        topBitmapUpdateJob = null
        blurUpdateJob = null
        
        // 清理模糊任务队列
        blurTaskProcessor?.cancel()
        blurTaskProcessor = null
        blurTaskQueue.close()
        
        // 回收位图资源
        wallpaperBitmaps?.recycleInternals()
        wallpaperBitmaps = null
        
        // 释放速度追踪器
        velocityTracker?.recycle()
        velocityTracker = null
        
        Log.d(TAG, "WallpaperPreviewView detached, resources cleaned up")
    }
}