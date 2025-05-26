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
    private var blurUpdateJob: Job? = null // 新增

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
        get() = if (isInP1EditMode || isTransitioningFromEditMode) currentEditP1HeightRatio else nonEditModePage1ImageHeightRatio


    private val currentEffectiveP1ContentScaleFactor: Float
        get() = if (isInP1EditMode || isTransitioningFromEditMode) currentEditP1ContentScaleFactor else this.currentP1ContentScaleFactor


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

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val touchX = event.x
        val touchY = event.y

        if (isInP1EditMode && wallpaperBitmaps?.sourceSampledBitmap != null) {
            // 1. 首先处理最优先的 P1 高度调整手势
            var handledByP1HeightResize = false
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val effectiveTouchSlop = touchSlop * p1HeightResizeTouchSlopFactor
                val heightHandleHotZone = RectF( p1DisplayRectView.left, p1DisplayRectView.bottom - effectiveTouchSlop, p1DisplayRectView.right, p1DisplayRectView.bottom + effectiveTouchSlop)
                if (p1DisplayRectView.height() > effectiveTouchSlop * 1.5f && heightHandleHotZone.contains(touchX, touchY)) {
                    if (!p1ContentScroller.isFinished) { p1ContentScroller.forceFinished(true); }
                    isP1HeightResizing = true
                    p1HeightResizeStartRawY = event.rawY
                    p1HeightResizeStartRatio = currentEditP1HeightRatio
                    parent?.requestDisallowInterceptTouchEvent(true)
                    handledByP1HeightResize = true
                }
            }
            if (isP1HeightResizing) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> { /* ... 高度调整的 move 逻辑 ... */
                        val dy = event.rawY - p1HeightResizeStartRawY
                        val deltaRatio = dy / viewHeight.toFloat()
                        var newRatio = p1HeightResizeStartRatio + deltaRatio
                        newRatio = newRatio.coerceIn(WallpaperConfigConstants.MIN_HEIGHT_RATIO, WallpaperConfigConstants.MAX_HEIGHT_RATIO)
                        if (abs(newRatio - currentEditP1HeightRatio) > 0.002f) {
                            currentEditP1HeightRatio = newRatio
                            calculateP1DisplayRectView()
                            resetP1EditMatrixToFocus(this.currentNormalizedFocusX, this.currentNormalizedFocusY)
                            attemptThrottledP1ConfigUpdate()
                        }
                        handledByP1HeightResize = true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { /* ... 高度调整的 up/cancel 逻辑 ... */
                        executeP1ConfigUpdate()
                        isP1HeightResizing = false
                        parent?.requestDisallowInterceptTouchEvent(false)
                        handledByP1HeightResize = true // 即使是UP/CANCEL，也认为高度调整逻辑处理了它
                    }
                }
                if (handledByP1HeightResize) return true // 如果高度调整正在进行，它消耗所有事件
            }

            // 2. 如果不是高度调整，再尝试 P1 内容的缩放和拖动/单击手势
            // handledByScale 会先于 handledByDragOrTap 判断，这通常没问题
            val handledByScale = p1ContentScaleGestureDetector.onTouchEvent(event)
            val handledByDragOrTap = p1ContentDragGestureDetector.onTouchEvent(event) // 这个现在也处理单击

            if (handledByScale || handledByDragOrTap) {
                // 如果是缩放、拖动或者P1区域内的单击，则我们认为事件已处理
                if (event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                } else {
                    if (isThrottledP1ConfigUpdatePending) {
                        executeP1ConfigUpdate()
                    }
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                return true // 明确返回 true，表示事件被 P1 编辑手势消耗
            }

            // 3. 如果事件在 P1 区域内，但没有被以上 P1 编辑手势（高度、缩放、拖动、P1内单击）处理，
            // 这通常不太可能发生，因为 onSingleTapUp 应该能捕获到。
            // 但为了保险，如果触摸点在 P1 区域内，我们也消费掉它，不让它触发页面滑动。
            // 不过，如果 onSingleTapUp 确实调用了 performClick()，我们其实是希望外部的 OnClickListener 响应的。
            // 所以这里的逻辑需要调整：如果触摸在 p1DisplayRectView 内，我们不应该阻止 performClick()。
            // p1ContentDragGestureDetector 已经处理了 p1DisplayRectView 内的单击。
            // 所以，如果事件到达这里，并且在 p1DisplayRectView 内，它可能是一个没有被任何 detector 消费的事件的尾声。
            // 我们主要的目标是阻止它意外触发页面滑动。
            if (p1DisplayRectView.contains(touchX, touchY)) {
                // 如果事件在P1区域但未被任何特定P1手势消耗，我们仍标记为已处理，
                // 以阻止它传递给下面的页面滑动逻辑。
                // MainActivity中的OnClickListener仍然会被之前的performClick()调用。
                return true
            }
            // 如果事件不在 P1 区域内，但在 P1 编辑模式下，我们希望它能触发全局单击（如果它是一个单击）
            // 这部分逻辑由下面的非 P1 编辑模式的单击处理覆盖。
        }

        // --- 非 P1 编辑模式 或 P1 编辑模式下但触摸点在 P1 区域之外 的触摸处理 ---
        // 这部分逻辑现在也适用于 P1 编辑模式下，当触摸发生在 P1 区域之外时，
        // 或者 P1 区域内的事件没有被 P1 的 detectors 完全消费并“漏”到这里。
        // 我们希望的是，无论是否在P1编辑模式，只要是一个“纯粹”的单击，performClick()就会被调用。

        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
        velocityTracker!!.addMovement(event)
        val action = event.actionMasked
        val x = event.x // 当前事件的x坐标

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (!pageScroller.isFinished) pageScroller.abortAnimation()
                lastTouchX = x
                downTouchX = x // 记录按下的 x 坐标
                activePointerId = event.getPointerId(0)
                isPageSwiping = false
                // 只有在非P1编辑模式下，或者P1编辑模式但触摸不在P1区域时，才阻止父类拦截
                // 但实际上，如果进入ACTION_DOWN，我们总是先假设可能要滑动
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) return false
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex < 0) return false
                val currentMoveX = event.getX(pointerIndex)
                val deltaX = lastTouchX - currentMoveX

                if (!isPageSwiping && abs(currentMoveX - downTouchX) > touchSlop) {
                    isPageSwiping = true
                }

                if (isPageSwiping) {
                    // 只有在非P1编辑模式下才允许页面滑动
                    if (!isInP1EditMode) {
                        currentPreviewXOffset = if (viewWidth > 0 && numVirtualPages > 1) (currentPreviewXOffset + deltaX / (viewWidth.toFloat() * (numVirtualPages - 1))).coerceIn(0f, 1f) else 0f
                        lastTouchX = currentMoveX
                        invalidate()
                    } else {
                        // P1编辑模式下，如果判定为页面滑动（通常是触摸在P1区域外），我们什么都不做
                        // 或者允许滑动，但需要确保 P1 编辑手势优先
                        // 当前的逻辑是，如果 isInP1EditMode 为 true，上面的 P1 手势处理会优先返回 true。
                        // 如果事件能到这里，说明它不在 P1 区域或未被 P1 手势处理。
                        // 我们在这里可以选择是否在 P1 编辑模式下也允许页面滑动（如果触摸在 P1 外）。
                        // 为了简化，我们先假设 P1 编辑模式下，主要交互在 P1 区域内，页面滑动被抑制。
                        // 但如果用户确实想在P1编辑时滑动页面，这里的逻辑需要调整。
                        // 目前，如果进入 isPageSwiping 且 isInP1EditMode，这里不会更新 offset。
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) return false
                var eventHandled = false
                if (isPageSwiping) {
                    if (!isInP1EditMode) { // 页面滑动只在非P1编辑模式下进行
                        val velocityTracker = this.velocityTracker!!
                        velocityTracker.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                        val velocityX = velocityTracker.getXVelocity(activePointerId)
                        if (abs(velocityX) > minFlingVelocity && numVirtualPages > 1) {
                            flingPage(velocityX)
                        } else {
                            snapToNearestPage(currentPreviewXOffset)
                        }
                    }
                    eventHandled = true
                } else {
                    // 如果不是页面滑动，则判断为单击
                    // 这个单击判断现在是全局的（无论是否在P1编辑模式）
                    // 因为P1编辑模式下的特定区域单击已由 p1ContentDragGestureDetector.onSingleTapUp -> performClick() 处理
                    // 如果触摸在P1区域外，或者P1区域内的单击没有被内部detector的onSingleTapUp处理（不太可能），则这里会捕获
                    if (abs(x - downTouchX) < touchSlop) {
                        Log.d(TAG, "onTouchEvent: Global ACTION_UP, potential click. Calling performClick(). isInP1EditMode: $isInP1EditMode")
                        performClick() // 调用 performClick，让外部 OnClickListener 处理
                    } else {
                        // 如果有微小移动但不足以算滑动，也不是 P1 编辑手G势，可能也需要吸附到最近页面（如果不在P1编辑模式）
                        if(!isInP1EditMode) snapToNearestPage(currentPreviewXOffset)
                    }
                    eventHandled = true
                }

                recycleVelocityTracker()
                activePointerId = MotionEvent.INVALID_POINTER_ID
                isPageSwiping = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return eventHandled // 或者总是 true
            }
            MotionEvent.ACTION_CANCEL -> {
                // 与 ACTION_UP 类似的处理
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) return false
                if (isPageSwiping && !isInP1EditMode) {
                    snapToNearestPage(currentPreviewXOffset)
                }
                recycleVelocityTracker()
                activePointerId = MotionEvent.INVALID_POINTER_ID
                isPageSwiping = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

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

    private fun updateOnlyBlurredBackgroundForPreviewAsync() {
        blurUpdateJob?.cancel()
        fullBitmapLoadingJob?.cancel() // 取消可能正在进行的完整加载

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

        blurUpdateJob = viewScope.launch {
            var newBlurredBitmap: Bitmap? = null
            try {
                ensureActive()
                newBlurredBitmap = withContext(Dispatchers.IO) { // 使用 Dispatchers.IO 或 Default
                    ensureActive()
                    SharedWallpaperRenderer.regenerateBlurredBitmap(
                        context, // WallpaperPreviewView 的 context
                        baseForBlur,
                        baseForBlur.width, // 目标尺寸与原滚动图一致
                        baseForBlur.height,
                        currentBackgroundBlurRadius,
                        currentBlurDownscaleFactor,
                        currentBlurIterations
                    )
                }
                ensureActive()
                val oldBlurred = wallpaperBitmaps?.blurredScrollingBackgroundBitmap
                if (this@WallpaperPreviewView.imageUri != null && wallpaperBitmaps?.scrollingBackgroundBitmap == baseForBlur) {
                    wallpaperBitmaps?.blurredScrollingBackgroundBitmap = newBlurredBitmap
                    if (oldBlurred != newBlurredBitmap) oldBlurred?.recycle()
                } else {
                    newBlurredBitmap?.recycle()
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Preview blur update cancelled.")
                newBlurredBitmap?.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Preview blur update failed", e)
                newBlurredBitmap?.recycle()
                if (this@WallpaperPreviewView.imageUri != null && wallpaperBitmaps?.scrollingBackgroundBitmap == baseForBlur) {
                    wallpaperBitmaps?.blurredScrollingBackgroundBitmap = null
                }
            } finally {
                if (isActive && coroutineContext[Job] == blurUpdateJob) blurUpdateJob = null
                if (isActive && this@WallpaperPreviewView.imageUri != null) {
                    invalidate()
                }
            }
        }
    }

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

    private fun mainScopeLaunch(block: suspend CoroutineScope.() -> Unit) {
        if (viewScope.isActive) {
            viewScope.launch {
                block()
            }
        } else {
            Log.w(TAG, "ViewScope not active for mainScopeLaunch.")
        }
    }


    fun setSelectedBackgroundColor(color: Int){
        if(this.selectedBackgroundColor != color){
            this.selectedBackgroundColor = color
            invalidate()
        }
    }

    // performClick() 方法是 View 类自带的，它会调用 setOnClickListener 设置的监听器
    override fun performClick(): Boolean {
        Log.d(TAG, "performClick() called. isInP1EditMode: $isInP1EditMode")
        // super.performClick() 会调用外部设置的 OnClickListener
        // 我们在外部 OnClickListener (MainActivity中) 判断 isP1EditMode
        return super.performClick()
    }

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


    private fun getScrollRange(): Int = (numVirtualPages - 1) * 10000

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Log.d(TAG,"onDetachedFromWindow: Cancelling jobs & recycling bitmaps.")
        fullBitmapLoadingJob?.cancel()
        fullBitmapLoadingJob = null
        topBitmapUpdateJob?.cancel()
        topBitmapUpdateJob = null
        viewScope.cancel()
        wallpaperBitmaps?.recycleInternals()
        wallpaperBitmaps = null
        mainHandler.removeCallbacksAndMessages(null)
    }
}