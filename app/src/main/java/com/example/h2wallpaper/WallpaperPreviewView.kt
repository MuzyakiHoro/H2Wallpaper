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
    private var nonEditModePage1ImageHeightRatio: Float = WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO
    private var currentNormalizedFocusX: Float = WallpaperConfigConstants.DEFAULT_P1_FOCUS_X
    private var currentNormalizedFocusY: Float = WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y
    private var currentP1ContentScaleFactor: Float = WallpaperConfigConstants.DEFAULT_P1_CONTENT_SCALE_FACTOR

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
    private var scroller: OverScroller
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
    // p1HeightResizeAnchorNormX/Y 在节流优化后不再直接用于resetP1EditMatrixToFocus，
    // resetP1EditMatrixToFocus会使用currentNormalizedFocusX/Y
    // 但如果需要在拖动高度时，保持一个更严格的视觉锚点，则可能需要重新引入类似概念

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

    init {
        val viewConfig = ViewConfiguration.get(context)
        touchSlop = viewConfig.scaledTouchSlop
        minFlingVelocity = viewConfig.scaledMinimumFlingVelocity
        maxFlingVelocity = viewConfig.scaledMaximumFlingVelocity
        scroller = OverScroller(context)
        initializeP1GestureDetectors()
    }

    private fun initializeP1GestureDetectors() {
        p1ContentDragGestureDetector = GestureDetector(context, P1ContentGestureListener())
        p1ContentScaleGestureDetector = ScaleGestureDetector(context, P1ContentScaleListener())
    }

    fun setOnP1ConfigEditedListener(listener: ((normalizedX: Float, normalizedY: Float, heightRatio: Float, contentScale: Float) -> Unit)?) {
        this.onP1ConfigEditedListener = listener
    }
    fun setOnRequestActionCallback(callback: ((action: PreviewViewAction) -> Unit)?) {
        this.onRequestActionCallback = callback
    }

    private val currentEffectiveP1HeightRatio: Float
        get() = if (isInP1EditMode) currentEditP1HeightRatio else nonEditModePage1ImageHeightRatio

    private val currentEffectiveP1ContentScaleFactor: Float
        get() = if (isInP1EditMode) currentEditP1ContentScaleFactor else currentP1ContentScaleFactor


    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val oldViewWidth = viewWidth; val oldViewHeight = viewHeight
        viewWidth = w; viewHeight = h
        Log.d(TAG, "onSizeChanged: New $viewWidth x $viewHeight. EditMode: $isInP1EditMode")

        if (w > 0 && h > 0) {
            calculateP1DisplayRectView()
            if (isInP1EditMode && wallpaperBitmaps?.sourceSampledBitmap != null) {
                resetP1EditMatrixToFocus(currentNormalizedFocusX, currentNormalizedFocusY)
            }

            if (imageUri != null && (w != oldViewWidth || h != oldViewHeight || wallpaperBitmaps?.sourceSampledBitmap == null)) {
                loadFullBitmapsFromUri(this.imageUri, true)
            } else if (imageUri != null && wallpaperBitmaps?.sourceSampledBitmap != null && wallpaperBitmaps?.page1TopCroppedBitmap == null && !isInP1EditMode) {
                updateOnlyPage1TopCroppedBitmap(nonEditModePage1ImageHeightRatio, wallpaperBitmaps!!.sourceSampledBitmap!!, currentP1ContentScaleFactor)
            } else {
                invalidate()
            }
        }
    }

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
            p1DisplayRectView.bottom - handleVisualHeight, // 在P1下边缘内侧
            p1DisplayRectView.centerX() + handleWidth / 2f,
            p1DisplayRectView.bottom
        )
    }

    fun setP1FocusEditMode(isEditing: Boolean,
                           initialNormFocusX: Float? = null, initialNormFocusY: Float? = null,
                           initialHeightRatio: Float? = null, initialContentScale: Float? = null) {

        if (this.isInP1EditMode == isEditing && !isEditing) { // 如果已经是目标状态（非编辑），确保刷新
            invalidate()
            return
        }
        // 如果模式未变（都在编辑模式），但可能初始值需要从外部同步（如Activity onResume）
        if (this.isInP1EditMode == isEditing && isEditing) {
            currentEditP1HeightRatio = initialHeightRatio ?: currentEditP1HeightRatio
            currentNormalizedFocusX = initialNormFocusX ?: currentNormalizedFocusX
            currentNormalizedFocusY = initialNormFocusY ?: currentNormalizedFocusY
            currentEditP1ContentScaleFactor = initialContentScale ?: currentEditP1ContentScaleFactor
            calculateP1DisplayRectView()
            if (wallpaperBitmaps?.sourceSampledBitmap != null) {
                resetP1EditMatrixToFocus(currentNormalizedFocusX, currentNormalizedFocusY)
            }
            invalidate()
            return
        }

        this.isInP1EditMode = isEditing
        Log.i(TAG, "setP1FocusEditMode: $isEditing. Initials: F=($initialNormFocusX,$initialNormFocusY), HR=$initialHeightRatio, CS=$initialContentScale")

        if (isEditing) {
            if (wallpaperBitmaps?.sourceSampledBitmap == null) {
                Log.w(TAG, "P1EditMode: No source bitmap. Requesting cancel.")
                this.isInP1EditMode = false; invalidate() // 强制改回非编辑状态
                onRequestActionCallback?.invoke(PreviewViewAction.REQUEST_CANCEL_P1_EDIT_MODE)
                return
            }
            // 从外部传入的值更新View内部的编辑状态变量
            currentEditP1HeightRatio = initialHeightRatio ?: nonEditModePage1ImageHeightRatio
            currentNormalizedFocusX = initialNormFocusX ?: currentNormalizedFocusX
            currentNormalizedFocusY = initialNormFocusY ?: currentNormalizedFocusY
            currentEditP1ContentScaleFactor = initialContentScale ?: currentP1ContentScaleFactor

            calculateP1DisplayRectView() // 基于 currentEditP1HeightRatio
            resetP1EditMatrixToFocus(currentNormalizedFocusX, currentNormalizedFocusY) // 使用更新后的焦点

            parent?.requestDisallowInterceptTouchEvent(true)
            isPageSwiping = false
            if (!scroller.isFinished) scroller.abortAnimation()
        } else { // Exiting edit mode
            parent?.requestDisallowInterceptTouchEvent(false)
            // 当退出编辑模式时，MainActivity会通过setter方法（setPage1ImageHeightRatio, setNormalizedFocus, setP1ContentScaleFactor）
            // 来设置nonEditModePage1ImageHeightRatio, currentNormalizedFocusX/Y, currentP1ContentScaleFactor
            // 这些setter内部会调用calculateP1DisplayRectView或updateOnlyPage1TopCroppedBitmap
            // 所以这里主要是确保calculateP1DisplayRectView使用非编辑模式的高度
            calculateP1DisplayRectView() // 用 nonEditModePage1ImageHeightRatio 重新计算
            if (imageUri != null && wallpaperBitmaps?.sourceSampledBitmap != null) {
                updateOnlyPage1TopCroppedBitmap(nonEditModePage1ImageHeightRatio, wallpaperBitmaps!!.sourceSampledBitmap!!, currentP1ContentScaleFactor)
            }
        }
        invalidate()
    }

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
            else p1EditMatrix.setScale(1.0f, 1.0f) // Absolute fallback
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

    private fun calculateP1BaseFillScale(source: Bitmap, targetRect: RectF): Float {
        if (source.width <= 0 || source.height <= 0 || targetRect.width() <= 0 || targetRect.height() <= 0) return 1.0f
        return max(targetRect.width() / source.width.toFloat(), targetRect.height() / source.height.toFloat())
    }

    private fun getCurrentP1EditMatrixScale(): Float {
        val values = FloatArray(9); p1EditMatrix.getValues(values); return values[Matrix.MSCALE_X]
    }

    private fun applyP1EditMatrixBounds() {
        val source = wallpaperBitmaps?.sourceSampledBitmap ?: return
        if (p1DisplayRectView.isEmpty || source.isRecycled || source.width == 0 || source.height == 0) return

        var currentMatrixScaleVal = getCurrentP1EditMatrixScale() // 使用局部变量，避免并发问题
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
            resetP1EditMatrixToFocus(currentNormalizedFocusX,currentNormalizedFocusY); return
        }

        if (abs(scaleCorrectionFactor - 1.0f) > 0.0001f) {
            p1EditMatrix.postScale(scaleCorrectionFactor, scaleCorrectionFactor, p1DisplayRectView.centerX(), p1DisplayRectView.centerY())
        }
        // 更新 currentEditP1ContentScaleFactor 为修正后的值
        currentEditP1ContentScaleFactor = (getCurrentP1EditMatrixScale() / baseFillScale).coerceIn(p1UserMinScaleFactorRelativeToCover, p1UserMaxScaleFactorRelativeToCover)


        val values = FloatArray(9); p1EditMatrix.getValues(values)
        val currentTransX = values[Matrix.MTRANS_X]; val currentTransY = values[Matrix.MTRANS_Y]
        val finalScaleAfterCorrection = getCurrentP1EditMatrixScale() // 获取修正后的总缩放
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
                    // throttledP1ConfigUpdateRunnable = null; // 可选：每次执行后清除，下次重新创建
                }
                mainHandler.postDelayed(throttledP1ConfigUpdateRunnable!!, P1_CONFIG_UPDATE_THROTTLE_MS - (currentTime - lastP1ConfigUpdateTime))
            }
        }
    }

    private fun executeP1ConfigUpdate() {
        lastP1ConfigUpdateTime = System.currentTimeMillis()
        isThrottledP1ConfigUpdatePending = false
        throttledP1ConfigUpdateRunnable?.let { mainHandler.removeCallbacks(it) }
        throttledP1ConfigUpdateRunnable = null

        val source = wallpaperBitmaps?.sourceSampledBitmap ?: return
        if (p1DisplayRectView.isEmpty || source.width <= 0 || source.height <= 0) {
            onP1ConfigEditedListener?.invoke(currentNormalizedFocusX, currentNormalizedFocusY, currentEditP1HeightRatio, currentEditP1ContentScaleFactor)
            return
        }
        val viewCenter = floatArrayOf(p1DisplayRectView.centerX(), p1DisplayRectView.centerY())
        val invertedMatrix = Matrix()
        if (!p1EditMatrix.invert(invertedMatrix)) {
            Log.e(TAG, "executeP1ConfigUpdate: p1EditMatrix non-invertible!")
            onP1ConfigEditedListener?.invoke(currentNormalizedFocusX, currentNormalizedFocusY, currentEditP1HeightRatio, currentEditP1ContentScaleFactor)
            return
        }
        invertedMatrix.mapPoints(viewCenter)
        val newNormX = (viewCenter[0] / source.width.toFloat()).coerceIn(0f, 1f)
        val newNormY = (viewCenter[1] / source.height.toFloat()).coerceIn(0f, 1f)

        currentNormalizedFocusX = newNormX // 更新View内部的焦点
        currentNormalizedFocusY = newNormY
        // currentEditP1HeightRatio 和 currentEditP1ContentScaleFactor 在手势中已直接更新

        Log.d(TAG, "executeP1ConfigUpdate: Calling listener with NXY=($newNormX,$newNormY), HR=$currentEditP1HeightRatio, CS=$currentEditP1ContentScaleFactor")
        onP1ConfigEditedListener?.invoke(newNormX, newNormY, currentEditP1HeightRatio, currentEditP1ContentScaleFactor)
    }

    private inner class P1ContentGestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = isInP1EditMode && wallpaperBitmaps?.sourceSampledBitmap != null && p1DisplayRectView.contains(e.x, e.y) && !isP1HeightResizing
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (e1==null || !isInP1EditMode || isP1HeightResizing || wallpaperBitmaps?.sourceSampledBitmap == null || !p1DisplayRectView.contains(e1.x,e1.y)) return false
            p1EditMatrix.postTranslate(-distanceX, -distanceY); applyP1EditMatrixBounds(); attemptThrottledP1ConfigUpdate(); invalidate()
            return true
        }
    }
    private inner class P1ContentScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = isInP1EditMode && !isP1HeightResizing && wallpaperBitmaps?.sourceSampledBitmap != null && p1DisplayRectView.contains(detector.focusX, detector.focusY)
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (!isInP1EditMode || isP1HeightResizing || wallpaperBitmaps?.sourceSampledBitmap == null) return false
            val scaleFactor = detector.scaleFactor
            if (scaleFactor != 0f && abs(scaleFactor - 1.0f) > 0.0001f) {
                p1EditMatrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
                applyP1EditMatrixBounds(); attemptThrottledP1ConfigUpdate(); invalidate()
            }
            return true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isInP1EditMode && wallpaperBitmaps?.sourceSampledBitmap != null) {
            var handledByEdit = false; val touchX = event.x; val touchY = event.y
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val effectiveTouchSlop = touchSlop * p1HeightResizeTouchSlopFactor
                    val heightHandleHotZone = RectF( p1DisplayRectView.left, p1DisplayRectView.bottom - effectiveTouchSlop, p1DisplayRectView.right, p1DisplayRectView.bottom + effectiveTouchSlop)
                    if (p1DisplayRectView.height() > effectiveTouchSlop * 1.5f && heightHandleHotZone.contains(touchX, touchY)) {
                        isP1HeightResizing = true; p1HeightResizeStartRawY = event.rawY; p1HeightResizeStartRatio = currentEditP1HeightRatio
                        handledByEdit = true; parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isP1HeightResizing) {
                        val dy = event.rawY - p1HeightResizeStartRawY; val deltaRatio = dy / viewHeight.toFloat()
                        var newRatio = p1HeightResizeStartRatio + deltaRatio
                        newRatio = newRatio.coerceIn(WallpaperConfigConstants.MIN_HEIGHT_RATIO, WallpaperConfigConstants.MAX_HEIGHT_RATIO)
                        if (abs(newRatio - currentEditP1HeightRatio) > 0.002f) {
                            currentEditP1HeightRatio = newRatio; calculateP1DisplayRectView()
                            resetP1EditMatrixToFocus(currentNormalizedFocusX, currentNormalizedFocusY)
                            attemptThrottledP1ConfigUpdate()
                        }
                        handledByEdit = true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    var needsImmediateUpdate = false
                    if (isP1HeightResizing) { isP1HeightResizing = false; handledByEdit = true; needsImmediateUpdate = true }
                    if (handledByEdit || isThrottledP1ConfigUpdatePending) {
                        throttledP1ConfigUpdateRunnable?.let { mainHandler.removeCallbacks(it) }
                        throttledP1ConfigUpdateRunnable = null
                        if (needsImmediateUpdate || isThrottledP1ConfigUpdatePending) {
                            executeP1ConfigUpdate()
                        }
                    }
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            if (!isP1HeightResizing) {
                val handledByScale = p1ContentScaleGestureDetector.onTouchEvent(event)
                val handledByDrag = p1ContentDragGestureDetector.onTouchEvent(event)
                handledByEdit = handledByEdit || handledByScale || handledByDrag
            }
            if (handledByEdit) {
                if (event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL) parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
        }

        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain(); velocityTracker!!.addMovement(event)
        val action = event.actionMasked; val x = event.x
        when (action) {
            MotionEvent.ACTION_DOWN -> { if (!scroller.isFinished) scroller.abortAnimation(); lastTouchX = x; downTouchX = x; activePointerId = event.getPointerId(0); isPageSwiping = false; parent?.requestDisallowInterceptTouchEvent(true); return true }
            MotionEvent.ACTION_MOVE -> { if (activePointerId == MotionEvent.INVALID_POINTER_ID) return false; val pIdx = event.findPointerIndex(activePointerId); if (pIdx < 0) return false; val cMx = event.getX(pIdx); val dX = lastTouchX - cMx; if (!isPageSwiping && abs(cMx - downTouchX) > touchSlop) isPageSwiping = true; if (isPageSwiping) { currentPreviewXOffset = if (viewWidth > 0 && numVirtualPages > 1) (currentPreviewXOffset + dX / (viewWidth.toFloat() * (numVirtualPages - 1))).coerceIn(0f, 1f) else 0f; lastTouchX = cMx; invalidate() }; return true }
            MotionEvent.ACTION_UP -> { if (activePointerId == MotionEvent.INVALID_POINTER_ID) return false; if (isPageSwiping) { val vt = velocityTracker!!; vt.computeCurrentVelocity(1000, maxFlingVelocity.toFloat()); val velX = vt.getXVelocity(activePointerId); if (abs(velX) > minFlingVelocity && numVirtualPages > 1) flingPage(velX) else snapToNearestPage(currentPreviewXOffset) } else { if (abs(x - downTouchX) < touchSlop) performClick() else snapToNearestPage(currentPreviewXOffset) }; recycleVelocityTracker(); activePointerId = MotionEvent.INVALID_POINTER_ID; isPageSwiping = false; return true }
            MotionEvent.ACTION_CANCEL -> { if (activePointerId == MotionEvent.INVALID_POINTER_ID) return false; if (isPageSwiping) snapToNearestPage(currentPreviewXOffset); recycleVelocityTracker(); activePointerId = MotionEvent.INVALID_POINTER_ID; isPageSwiping = false; return true }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        if(viewWidth<=0 || viewHeight<=0)return; val cWBM = wallpaperBitmaps; if(isInP1EditMode && cWBM?.sourceSampledBitmap!=null){ val sTD = cWBM.sourceSampledBitmap!!; canvas.drawColor(Color.DKGRAY); canvas.save(); canvas.clipRect(p1DisplayRectView); canvas.concat(p1EditMatrix); canvas.drawBitmap(sTD,0f,0f,p1EditContentPaint); canvas.restore(); canvas.drawRect(p1DisplayRectView,p1EditBorderPaint); if(p1DisplayRectView.height()>touchSlop*p1HeightResizeTouchSlopFactor*1.2f) canvas.drawRoundRect(p1HeightResizeHandleRect,5f*resources.displayMetrics.density,5f*resources.displayMetrics.density,p1HeightResizeHandlePaint); p1OverlayBgPaint.color = selectedBackgroundColor; if(p1DisplayRectView.bottom<viewHeight)canvas.drawRect(0f,p1DisplayRectView.bottom,viewWidth.toFloat(),viewHeight.toFloat(),p1OverlayBgPaint)}else{if(cWBM?.sourceSampledBitmap!=null){val cfg=SharedWallpaperRenderer.WallpaperConfig(viewWidth,viewHeight,selectedBackgroundColor,nonEditModePage1ImageHeightRatio,currentPreviewXOffset,numVirtualPages,currentP1OverlayFadeRatio,currentScrollSensitivity,currentNormalizedInitialBgScrollOffset,currentP2BackgroundFadeInRatio,currentP1ShadowRadius,currentP1ShadowDx,currentP1ShadowDy,currentP1ShadowColor,currentP1ImageBottomFadeHeight); SharedWallpaperRenderer.drawFrame(canvas,cfg,cWBM)}else SharedWallpaperRenderer.drawPlaceholder(canvas,viewWidth,viewHeight,if(imageUri!=null&&(fullBitmapLoadingJob?.isActive==true||topBitmapUpdateJob?.isActive==true))"图片加载中..." else "请选择图片")}
    }

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

        val blurChanged = oldBgBlurR != this.currentBackgroundBlurRadius ||
                oldBgBlurDF != this.currentBlurDownscaleFactor ||
                oldBgBlurIt != this.currentBlurIterations

        if (blurChanged && this.imageUri != null) {
            loadFullBitmapsFromUri(this.imageUri, true) // forceInternalReload = true
        } else {
            invalidate()
        }
    }

    fun setImageUri(uri: Uri?, forceReload: Boolean = false) {
        Log.d(TAG, "setImageUri called: $uri. EditMode: $isInP1EditMode, ForceReload: $forceReload")
        if (isInP1EditMode) {
            // 如果在编辑模式下选择了新图片，应通知外部取消当前编辑
            onRequestActionCallback?.invoke(PreviewViewAction.REQUEST_CANCEL_P1_EDIT_MODE)
            // ViewModel 会处理 isP1EditMode 的变化，并进而调用 setP1FocusEditMode(false)
        }

        if (!forceReload && this.imageUri == uri && uri != null && wallpaperBitmaps?.sourceSampledBitmap != null) {
            // URI未变，且非强制重载，且已有位图
            if (!isInP1EditMode) { // 仅在非编辑模式下才考虑更新P1顶图（如果需要的话）
                updateOnlyPage1TopCroppedBitmap(nonEditModePage1ImageHeightRatio, wallpaperBitmaps!!.sourceSampledBitmap!!, currentP1ContentScaleFactor)
            } else {
                invalidate() // 编辑模式下，如果URI未变，通常不需要做什么，除非是要重置编辑状态
            }
            return
        }

        fullBitmapLoadingJob?.cancel()
        topBitmapUpdateJob?.cancel()

        if (forceReload || this.imageUri != uri) { // 如果是强制重载，或者URI确实变了
            wallpaperBitmaps?.recycleInternals()
            wallpaperBitmaps = null
        }

        this.imageUri = uri
        currentPreviewXOffset = 0f // 新图片，重置预览页码
        if (!scroller.isFinished) {
            scroller.abortAnimation()
        }

        if (uri != null) {
            invalidate() // 先画个占位符或旧图的最后状态
            loadFullBitmapsFromUri(uri, true) // 新图片总是需要完整加载 (forceInternalReload=true 确保旧的同名URI也被刷新)
        } else { // uri is null
            wallpaperBitmaps?.recycleInternals()
            wallpaperBitmaps = null
            invalidate()
        }
    }

    private fun loadFullBitmapsFromUri(uriToLoad: Uri?, forceInternalReload: Boolean = false) {
        if (uriToLoad == null || viewWidth <= 0 || viewHeight <= 0) {
            if (!forceInternalReload) { // 仅当不是强制内部重载时才根据uriToLoad==null清空位图
                wallpaperBitmaps?.recycleInternals()
                wallpaperBitmaps = null
            }
            invalidate()
            return
        }

        fullBitmapLoadingJob?.cancel()
        topBitmapUpdateJob?.cancel()

        // 如果是强制内部重载（例如模糊参数变了），或者当前没有有效的源位图，则清空旧位图
        if (forceInternalReload || wallpaperBitmaps?.sourceSampledBitmap == null) {
            wallpaperBitmaps?.recycleInternals()
            wallpaperBitmaps = null
            invalidate() // 显示加载中...
        }

        fullBitmapLoadingJob = viewScope.launch {
            var newWpBitmaps: SharedWallpaperRenderer.WallpaperBitmaps? = null
            try {
                ensureActive()
                newWpBitmaps = withContext(Dispatchers.IO) {
                    ensureActive()
                    // 这些值应该在调用loadFullBitmapsFromUri之前，已经通过setter方法被MainActivity从ViewModel更新到View的成员变量了
                    val heightToUse = currentEffectiveP1HeightRatio
                    val focusXToUse = currentNormalizedFocusX
                    val focusYToUse = currentNormalizedFocusY
                    val scaleToUse = currentEffectiveP1ContentScaleFactor

                    SharedWallpaperRenderer.loadAndProcessInitialBitmaps(
                        context, uriToLoad, viewWidth, viewHeight,
                        heightToUse, focusXToUse, focusYToUse, scaleToUse,
                        currentBackgroundBlurRadius, currentBlurDownscaleFactor, currentBlurIterations
                    )
                }
                ensureActive()

                val oldBitmaps = wallpaperBitmaps
                if (this@WallpaperPreviewView.imageUri == uriToLoad) { // 确保URI未变
                    wallpaperBitmaps = newWpBitmaps
                    if (oldBitmaps != newWpBitmaps) oldBitmaps?.recycleInternals() // 回收旧的（如果不同）

                    if (isInP1EditMode && wallpaperBitmaps?.sourceSampledBitmap != null) {
                        // 图片加载完成后，如果仍在编辑模式，用当前焦点和缩放重置编辑矩阵
                        resetP1EditMatrixToFocus(currentNormalizedFocusX, currentNormalizedFocusY)
                    } else if (!isInP1EditMode && wallpaperBitmaps?.sourceSampledBitmap != null) {
                        // 非编辑模式，确保P1顶图也基于新加载的源图和当前配置更新
                        updateOnlyPage1TopCroppedBitmap(nonEditModePage1ImageHeightRatio, wallpaperBitmaps!!.sourceSampledBitmap!!, currentP1ContentScaleFactor)
                    }
                } else { // URI在这期间改变了
                    newWpBitmaps?.recycleInternals() // 新加载的作废
                    if (this@WallpaperPreviewView.imageUri == null) { // 如果当前URI已为空
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
                if (this@WallpaperPreviewView.imageUri == uriToLoad) { // 如果是当前URI加载失败，清空
                    wallpaperBitmaps?.recycleInternals()
                    wallpaperBitmaps = null
                }
            } finally {
                if (isActive && coroutineContext[Job] == fullBitmapLoadingJob) {
                    fullBitmapLoadingJob = null
                }
                // 无论成功与否，只要协程结束时URI匹配或当前URI已为空，就重绘
                if (isActive && (this@WallpaperPreviewView.imageUri == uriToLoad || this@WallpaperPreviewView.imageUri == null)) {
                    invalidate()
                }
            }
        }
    }

    fun setPage1ImageHeightRatio(newRatio: Float) { // 由MainActivity根据ViewModel的主配置值调用
        val clampedRatio = newRatio.coerceIn(WallpaperConfigConstants.MIN_HEIGHT_RATIO, WallpaperConfigConstants.MAX_HEIGHT_RATIO)
        if (isInP1EditMode) {
            // 如果在编辑模式下，外部（如ViewModel通过Activity）强制设定了高度
            // (例如，撤销操作恢复了ViewModel中的主配置值，然后同步到这里)
            // 那么我们也应该更新编辑模式下的当前高度，并重新初始化编辑矩阵
            if (abs(currentEditP1HeightRatio - clampedRatio) > 0.001f) {
                currentEditP1HeightRatio = clampedRatio
                calculateP1DisplayRectView()
                resetP1EditMatrixToFocus(currentNormalizedFocusX, currentNormalizedFocusY) // 使用当前焦点
                // 由于这是外部触发的更改，也应该通过回调通知，以便ViewModel的实时保存逻辑能感知到
                attemptThrottledP1ConfigUpdate() // 或者直接 executeP1ConfigUpdate() 如果希望立即保存
            }
        } else {
            // 非编辑模式，更新非编辑模式的高度并准备相应的P1顶图
            if (abs(nonEditModePage1ImageHeightRatio - clampedRatio) > 0.001f) {
                nonEditModePage1ImageHeightRatio = clampedRatio
                calculateP1DisplayRectView()
                if (imageUri != null && wallpaperBitmaps?.sourceSampledBitmap != null) {
                    updateOnlyPage1TopCroppedBitmap(nonEditModePage1ImageHeightRatio, wallpaperBitmaps!!.sourceSampledBitmap!!, currentP1ContentScaleFactor)
                } else { // 没有源图，只需要重绘占位符或空白
                    invalidate()
                }
            }
        }
    }

    fun setNormalizedFocus(focusX: Float, focusY: Float) { // 由MainActivity根据ViewModel的主配置值调用
        val clampedFocusX = focusX.coerceIn(0f,1f)
        val clampedFocusY = focusY.coerceIn(0f,1f)
        var changed = false
        if(abs(currentNormalizedFocusX - clampedFocusX) > 0.001f){ currentNormalizedFocusX = clampedFocusX; changed = true}
        if(abs(currentNormalizedFocusY - clampedFocusY) > 0.001f){ currentNormalizedFocusY = clampedFocusY; changed = true}

        if(changed){
            if (isInP1EditMode) { // 编辑模式下，外部强制设焦点
                resetP1EditMatrixToFocus(currentNormalizedFocusX, currentNormalizedFocusY)
                attemptThrottledP1ConfigUpdate() // 通知ViewModel
            } else if (imageUri != null && wallpaperBitmaps?.sourceSampledBitmap != null) { // 非编辑模式
                updateOnlyPage1TopCroppedBitmap(nonEditModePage1ImageHeightRatio, wallpaperBitmaps!!.sourceSampledBitmap!!, currentP1ContentScaleFactor)
            } else {
                invalidate()
            }
        }
    }

    fun setP1ContentScaleFactor(scale: Float) { // 由MainActivity根据ViewModel的主配置值调用
        // 这里允许的缩放范围应该与编辑模式下的用户可操作范围一致或更广（如果非编辑模式下有默认缩放）
        val clampedScale = scale.coerceIn(WallpaperConfigConstants.DEFAULT_P1_CONTENT_SCALE_FACTOR, p1UserMaxScaleFactorRelativeToCover)

        if (isInP1EditMode) { // 编辑模式下，外部强制设内容缩放
            if (abs(currentEditP1ContentScaleFactor - clampedScale) > 0.001f) {
                currentEditP1ContentScaleFactor = clampedScale
                resetP1EditMatrixToFocus(currentNormalizedFocusX, currentNormalizedFocusY)
                attemptThrottledP1ConfigUpdate() // 通知ViewModel
            }
        } else { // 非编辑模式
            if (abs(currentP1ContentScaleFactor - clampedScale) > 0.001f) {
                currentP1ContentScaleFactor = clampedScale
                if (imageUri != null && wallpaperBitmaps?.sourceSampledBitmap != null) {
                    updateOnlyPage1TopCroppedBitmap(nonEditModePage1ImageHeightRatio, wallpaperBitmaps!!.sourceSampledBitmap!!, currentP1ContentScaleFactor)
                } else {
                    invalidate()
                }
            }
        }
    }

    private fun updateOnlyPage1TopCroppedBitmap(heightRatioToUse: Float, sourceBitmap: Bitmap, contentScaleToUse: Float) {
        if (viewWidth <= 0 || viewHeight <= 0 || isInP1EditMode) { // 编辑模式下不更新这个缓存图
            if(isInP1EditMode) invalidate() // 编辑模式下可能只需要重绘当前编辑状态
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
                        heightRatioToUse, currentNormalizedFocusX, currentNormalizedFocusY, contentScaleToUse)
                }
                ensureActive()
                // 在UI线程更新
                val oldTopCropped = wallpaperBitmaps?.page1TopCroppedBitmap
                if (this@WallpaperPreviewView.imageUri != null && wallpaperBitmaps?.sourceSampledBitmap == sourceBitmap) {
                    wallpaperBitmaps?.page1TopCroppedBitmap = newTopBmp
                    if (oldTopCropped != newTopBmp) oldTopCropped?.recycle() // 仅当新旧对象不同时回收旧的
                } else { // 源图或URI已变，新生成的不适用
                    newTopBmp?.recycle()
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "updateOnlyPage1TopCroppedBitmap cancelled")
                newTopBmp?.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "updateOnlyPage1TopCroppedBitmap failed", e)
                newTopBmp?.recycle()
                if (this@WallpaperPreviewView.imageUri != null && wallpaperBitmaps?.sourceSampledBitmap == sourceBitmap) {
                    wallpaperBitmaps?.page1TopCroppedBitmap = null // 出错则清空
                }
            } finally {
                if (isActive && coroutineContext[Job] == topBitmapUpdateJob) {
                    topBitmapUpdateJob = null
                }
                // 只有在协程结束时仍然处于活动状态、有图片、且不在编辑模式，才重绘
                if (isActive && this@WallpaperPreviewView.imageUri != null && !isInP1EditMode) {
                    invalidate()
                }
            }
        }
    }

    fun setSelectedBackgroundColor(color: Int){
        if(this.selectedBackgroundColor != color){
            this.selectedBackgroundColor = color
            invalidate()
        }
    }

    override fun performClick(): Boolean {
        if (isInP1EditMode) {
            // 在编辑模式下，我们不希望单击触发MainActivity中的UI显隐切换
            return true // 表示事件已处理
        }
        // 非编辑模式下，调用父类的performClick，它会触发setOnClickListener
        return super.performClick()
    }

    private fun flingPage(velocityX: Float) {
        if (isInP1EditMode || numVirtualPages <= 1) { // 编辑模式下禁用页面滑动
            animateToOffset(0f) // 可以选择回到第一页或保持不动
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

    private fun snapToNearestPage(currentOffset: Float) {
        if (isInP1EditMode || numVirtualPages <= 1) { // 编辑模式下禁用页面吸附
            animateToOffset(0f)
            return
        }
        val pageIndexFloat = currentOffset * (numVirtualPages - 1)
        val targetPageIndex = pageIndexFloat.roundToInt().coerceIn(0, numVirtualPages - 1)
        val targetXOffset = if (numVirtualPages > 1) targetPageIndex.toFloat() / (numVirtualPages - 1).toFloat() else 0f
        animateToOffset(targetXOffset)
    }

    private fun animateToOffset(targetXOffset: Float) {
        val currentPixelOffset = (currentPreviewXOffset * getScrollRange()).toInt()
        val targetPixelOffset = (targetXOffset * getScrollRange()).toInt()
        val dx = targetPixelOffset - currentPixelOffset

        if (dx != 0) {
            scroller.startScroll(currentPixelOffset, 0, dx, 0, currentSnapAnimationDurationMs.toInt())
            postInvalidateOnAnimation()
        } else {
            this.currentPreviewXOffset = targetXOffset.coerceIn(0f,1f) // 确保值在范围内
            invalidate() // 即使dx为0，如果targetXOffset被限制了，也需要重绘
        }
    }

    override fun computeScroll() {
        if (isInP1EditMode && !scroller.isFinished) { // 编辑模式下如果还在滚动，立即停止
            scroller.abortAnimation()
            return
        }
        if (scroller.computeScrollOffset()) {
            val currentPixelOffset = scroller.currX
            val scrollRange = getScrollRange()
            currentPreviewXOffset = if (scrollRange > 0) {
                (currentPixelOffset.toFloat() / scrollRange.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            invalidate()
        }
    }

    private fun getScrollRange(): Int = (numVirtualPages - 1) * 10000 // 虚拟滚动范围，保持不变

    private fun recycleVelocityTracker() { // 保持不变
        velocityTracker?.recycle()
        velocityTracker = null
    }

    override fun onDetachedFromWindow() { // 保持不变
        super.onDetachedFromWindow()
        Log.d(TAG,"onDetachedFromWindow: Cancelling jobs & recycling bitmaps.")
        fullBitmapLoadingJob?.cancel()
        fullBitmapLoadingJob = null
        topBitmapUpdateJob?.cancel()
        topBitmapUpdateJob = null
        viewScope.cancel() // 取消所有与此View关联的协程
        wallpaperBitmaps?.recycleInternals()
        wallpaperBitmaps = null
        mainHandler.removeCallbacksAndMessages(null) // 移除所有Handler中的消息和回调
    }
}