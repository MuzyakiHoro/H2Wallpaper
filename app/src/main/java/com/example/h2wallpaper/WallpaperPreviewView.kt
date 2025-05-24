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
    var nonEditModePage1ImageHeightRatio: Float = WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO // Made public for MainActivity to set
    var currentNormalizedFocusX: Float = WallpaperConfigConstants.DEFAULT_P1_FOCUS_X // Made public
    var currentNormalizedFocusY: Float = WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y // Made public
    var currentP1ContentScaleFactor: Float = WallpaperConfigConstants.DEFAULT_P1_CONTENT_SCALE_FACTOR // Made public

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

    private val p1HeightResizeHandlePaint = Paint().apply {
        color = Color.argb(200, 255, 223, 0) // Gold-ish color for handle
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
        strokeWidth = 3f * resources.displayMetrics.density // More visible border
        alpha = 220
        pathEffect = DashPathEffect(floatArrayOf(8f * resources.displayMetrics.density, 4f * resources.displayMetrics.density), 0f)
    }
    private val p1OverlayBgPaint = Paint() // Used for bg color below P1 image

    // Flicker fix: Transition state
    private var isTransitioningFromEditMode: Boolean = false
    private val transitionMatrix = Matrix()

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
        get() = if (isInP1EditMode || isTransitioningFromEditMode) currentEditP1HeightRatio else nonEditModePage1ImageHeightRatio


    private val currentEffectiveP1ContentScaleFactor: Float
        get() = if (isInP1EditMode || isTransitioningFromEditMode) currentEditP1ContentScaleFactor else this.currentP1ContentScaleFactor


    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val oldViewWidth = viewWidth; val oldViewHeight = viewHeight
        viewWidth = w; viewHeight = h
        Log.d(TAG, "onSizeChanged: New $viewWidth x $viewHeight. EditMode: $isInP1EditMode, Transitioning: $isTransitioningFromEditMode")

        if (w > 0 && h > 0) {
            calculateP1DisplayRectView() // This will use currentEffectiveP1HeightRatio

            if (isInP1EditMode && wallpaperBitmaps?.sourceSampledBitmap != null) {
                // If already in edit mode and size changes, re-center/re-scale
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
        // Use the P1 height ratio that is currently relevant (edit or non-edit)
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

        // Handle calls that don't change the mode but might sync parameters (e.g., onResume while already in edit mode)
        if (wasEditing && isEditing) {
            Log.d(TAG, "setP1FocusEditMode: Staying in edit mode. Syncing params if provided.")
            currentEditP1HeightRatio = initialHeightRatio ?: currentEditP1HeightRatio
            this.currentNormalizedFocusX = initialNormFocusX ?: this.currentNormalizedFocusX
            this.currentNormalizedFocusY = initialNormFocusY ?: this.currentNormalizedFocusY
            currentEditP1ContentScaleFactor = initialContentScale ?: currentEditP1ContentScaleFactor

            calculateP1DisplayRectView() // Based on currentEditP1HeightRatio
            if (wallpaperBitmaps?.sourceSampledBitmap != null) {
                resetP1EditMatrixToFocus(this.currentNormalizedFocusX, this.currentNormalizedFocusY)
            }
            this.isTransitioningFromEditMode = false // Not transitioning
            invalidate()
            return
        }

        // Actual mode switch
        this.isInP1EditMode = isEditing

        if (wasEditing && !isEditing) { // Exiting edit mode (true -> false)
            Log.d(TAG, "setP1FocusEditMode: Exiting edit mode. Capturing last matrix and starting transition.")
            this.isTransitioningFromEditMode = true
            this.transitionMatrix.set(p1EditMatrix) // Capture the last matrix state

            parent?.requestDisallowInterceptTouchEvent(false)

            // IMPORTANT: For the transition frame, p1DisplayRectView needs to use the final non-edit height.
            // nonEditModePage1ImageHeightRatio should have been updated by MainActivity *before* this call.
            // So, currentEditP1HeightRatio is set to nonEditModePage1ImageHeightRatio for consistent p1DisplayRectView calculation during transition.
            currentEditP1HeightRatio = nonEditModePage1ImageHeightRatio // Use the target height for display rect
            calculateP1DisplayRectView() // Recalculate p1DisplayRectView for the transition frame

            if (imageUri != null && wallpaperBitmaps?.sourceSampledBitmap != null) {
                // Use the View's nonEditModePage1ImageHeightRatio and currentP1ContentScaleFactor,
                // which should have been updated by MainActivity from ViewModel.
                updateOnlyPage1TopCroppedBitmap(
                    heightRatioToUse = nonEditModePage1ImageHeightRatio,
                    sourceBitmap = wallpaperBitmaps!!.sourceSampledBitmap!!,
                    contentScaleToUse = this.currentP1ContentScaleFactor,
                    onComplete = {
                        Log.d(TAG, "setP1FocusEditMode: P1 top cropped bitmap update complete (on exit). Ending transition.")
                        this.isTransitioningFromEditMode = false
                        invalidate() // Trigger a draw with the new non-edit bitmap
                    }
                )
            } else {
                this.isTransitioningFromEditMode = false // No bitmap to update, clear flag
                invalidate() // Still need to redraw in non-edit mode (e.g. placeholder)
            }
            if (!scroller.isFinished) scroller.abortAnimation()

        } else if (!wasEditing && isEditing) { // Entering edit mode (false -> true)
            Log.d(TAG, "setP1FocusEditMode: Entering edit mode.")
            this.isTransitioningFromEditMode = false // Not transitioning

            if (wallpaperBitmaps?.sourceSampledBitmap == null) {
                Log.w(TAG, "P1EditMode: No source bitmap. Requesting cancel.")
                this.isInP1EditMode = false; // Force back to non-edit
                onRequestActionCallback?.invoke(PreviewViewAction.REQUEST_CANCEL_P1_EDIT_MODE)
                invalidate()
                return
            }

            // Use initial parameters if provided, otherwise use current non-edit mode values as starting point
            currentEditP1HeightRatio = initialHeightRatio ?: nonEditModePage1ImageHeightRatio
            this.currentNormalizedFocusX = initialNormFocusX ?: this.currentNormalizedFocusX
            this.currentNormalizedFocusY = initialNormFocusY ?: this.currentNormalizedFocusY
            currentEditP1ContentScaleFactor = initialContentScale ?: this.currentP1ContentScaleFactor

            calculateP1DisplayRectView() // Based on currentEditP1HeightRatio
            resetP1EditMatrixToFocus(this.currentNormalizedFocusX, this.currentNormalizedFocusY)

            parent?.requestDisallowInterceptTouchEvent(true)
            isPageSwiping = false
            if (!scroller.isFinished) scroller.abortAnimation()
            invalidate() // Trigger edit mode draw

        } else { // Mode not changing (e.g., false -> false, or initial setup)
            Log.d(TAG, "setP1FocusEditMode: Mode not changing or initial call. current isEditing: $isEditing")
            this.isTransitioningFromEditMode = false
            calculateP1DisplayRectView()
            if (!this.isInP1EditMode && imageUri != null && wallpaperBitmaps?.sourceSampledBitmap != null) {
                // If not in edit mode and image exists, ensure P1 top is up-to-date
                updateOnlyPage1TopCroppedBitmap(nonEditModePage1ImageHeightRatio, wallpaperBitmaps!!.sourceSampledBitmap!!, this.currentP1ContentScaleFactor)
            } else {
                invalidate() // Draw placeholder or current state
            }
        }
    }


    private fun resetP1EditMatrixToFocus(normFocusX: Float, normFocusY: Float) {
        val source = wallpaperBitmaps?.sourceSampledBitmap
        if (source == null || source.isRecycled || p1DisplayRectView.isEmpty) {
            p1EditMatrix.reset(); invalidate(); return
        }

        // p1DisplayRectView is based on currentEditP1HeightRatio when in edit mode.
        val baseFillScale = calculateP1BaseFillScale(source, p1DisplayRectView)
        val totalEffectiveScale = baseFillScale * currentEditP1ContentScaleFactor // Use edit mode scale factor

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
                            resetP1EditMatrixToFocus(this.currentNormalizedFocusX, this.currentNormalizedFocusY) // Use current view focus for re-centering after height change
                            attemptThrottledP1ConfigUpdate() // Height change is a config update
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
                            executeP1ConfigUpdate() // Ensure final state is pushed
                        }
                    }
                    // Only re-enable parent interception if not handled by other gestures below
                    // if (handledByEdit) parent?.requestDisallowInterceptTouchEvent(false) // This might be too broad
                }
            }
            // If height resizing handled it, it's done.
            if (handledByEdit && event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                parent?.requestDisallowInterceptTouchEvent(false) // Re-enable parent interception on UP/CANCEL if handled.
            }
            if (handledByEdit) return true


            // If not height resizing, pass to other gesture detectors
            if (!isP1HeightResizing) {
                val handledByScale = p1ContentScaleGestureDetector.onTouchEvent(event)
                val handledByDrag = p1ContentDragGestureDetector.onTouchEvent(event)
                handledByEdit = handledByScale || handledByDrag // Update handledByEdit
            }

            if (handledByEdit) {
                if (event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                } else {
                    parent?.requestDisallowInterceptTouchEvent(false) // Re-enable on UP/CANCEL
                }
                return true
            }
        }


        // Non-edit mode touch handling (page swipe)
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain(); velocityTracker!!.addMovement(event)
        val action = event.actionMasked; val x = event.x
        when (action) {
            MotionEvent.ACTION_DOWN -> { if (!scroller.isFinished) scroller.abortAnimation(); lastTouchX = x; downTouchX = x; activePointerId = event.getPointerId(0); isPageSwiping = false; parent?.requestDisallowInterceptTouchEvent(true); return true }
            MotionEvent.ACTION_MOVE -> { if (activePointerId == MotionEvent.INVALID_POINTER_ID) return false; val pIdx = event.findPointerIndex(activePointerId); if (pIdx < 0) return false; val cMx = event.getX(pIdx); val dX = lastTouchX - cMx; if (!isPageSwiping && abs(cMx - downTouchX) > touchSlop) isPageSwiping = true; if (isPageSwiping) { currentPreviewXOffset = if (viewWidth > 0 && numVirtualPages > 1) (currentPreviewXOffset + dX / (viewWidth.toFloat() * (numVirtualPages - 1))).coerceIn(0f, 1f) else 0f; lastTouchX = cMx; invalidate() }; return true }
            MotionEvent.ACTION_UP -> { if (activePointerId == MotionEvent.INVALID_POINTER_ID) return false; if (isPageSwiping) { val vt = velocityTracker!!; vt.computeCurrentVelocity(1000, maxFlingVelocity.toFloat()); val velX = vt.getXVelocity(activePointerId); if (abs(velX) > minFlingVelocity && numVirtualPages > 1) flingPage(velX) else snapToNearestPage(currentPreviewXOffset) } else { if (abs(x - downTouchX) < touchSlop) performClick() else snapToNearestPage(currentPreviewXOffset) }; recycleVelocityTracker(); activePointerId = MotionEvent.INVALID_POINTER_ID; isPageSwiping = false; parent?.requestDisallowInterceptTouchEvent(false); return true }
            MotionEvent.ACTION_CANCEL -> { if (activePointerId == MotionEvent.INVALID_POINTER_ID) return false; if (isPageSwiping) snapToNearestPage(currentPreviewXOffset); recycleVelocityTracker(); activePointerId = MotionEvent.INVALID_POINTER_ID; isPageSwiping = false; parent?.requestDisallowInterceptTouchEvent(false); return true }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        val cWBM = wallpaperBitmaps

        // Priority to transitioning state
        if (isTransitioningFromEditMode && cWBM?.sourceSampledBitmap != null) {
            val sourceToDraw = cWBM.sourceSampledBitmap!!
            canvas.drawColor(Color.DKGRAY) // Base background for P1 area
            canvas.save()
            // p1DisplayRectView should be based on nonEditModePage1ImageHeightRatio
            // as set before calling setP1FocusEditMode(false)
            canvas.clipRect(p1DisplayRectView)
            canvas.concat(transitionMatrix) // Use the captured matrix from edit mode's end
            canvas.drawBitmap(sourceToDraw, 0f, 0f, p1EditContentPaint)
            canvas.restore()

            // Draw the area below P1 with selected background color
            p1OverlayBgPaint.color = selectedBackgroundColor
            if (p1DisplayRectView.bottom < viewHeight) {
                canvas.drawRect(0f, p1DisplayRectView.bottom, viewWidth.toFloat(), viewHeight.toFloat(), p1OverlayBgPaint)
            }
            // No edit mode adornments (border, handle) during transition
        } else if (isInP1EditMode && cWBM?.sourceSampledBitmap != null) {
            // Standard P1 Edit Mode drawing
            val sTD = cWBM.sourceSampledBitmap!!
            canvas.drawColor(Color.DKGRAY)
            canvas.save()
            canvas.clipRect(p1DisplayRectView) // Based on currentEditP1HeightRatio
            canvas.concat(p1EditMatrix) // Live edit matrix
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
        } else { // Standard Non-Edit Mode drawing
            if (cWBM?.sourceSampledBitmap != null) {
                val cfg = SharedWallpaperRenderer.WallpaperConfig(
                    viewWidth, viewHeight, selectedBackgroundColor,
                    nonEditModePage1ImageHeightRatio, // Use non-edit mode height
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
        this.currentBackgroundBlurRadius = backgroundBlurRadius.coerceIn(0f, 50f) // Max 50f for RenderScript
        this.currentSnapAnimationDurationMs = snapAnimationDurationMs
        this.currentNormalizedInitialBgScrollOffset = normalizedInitialBgScrollOffset.coerceIn(0f, 1f)
        this.currentBlurDownscaleFactor = blurDownscaleFactor.coerceIn(0.05f, 1.0f)
        this.currentBlurIterations = blurIterations.coerceIn(1, 3) // Sensible iteration limit
        this.currentP1ShadowRadius = p1ShadowRadius.coerceIn(0f, 50f)
        this.currentP1ShadowDx = p1ShadowDx.coerceIn(-50f, 50f)
        this.currentP1ShadowDy = p1ShadowDy.coerceIn(-50f, 50f)
        this.currentP1ShadowColor = p1ShadowColor
        this.currentP1ImageBottomFadeHeight = p1ImageBottomFadeHeight.coerceAtLeast(0f)

        val blurChanged = oldBgBlurR != this.currentBackgroundBlurRadius ||
                oldBgBlurDF != this.currentBlurDownscaleFactor ||
                oldBgBlurIt != this.currentBlurIterations

        if (blurChanged && this.imageUri != null) {
            // Blur params affect P2 (scrolling background), requires full reload.
            loadFullBitmapsFromUri(this.imageUri, true)
        } else {
            invalidate() // Other non-bitmap changes only need redraw
        }
    }

    fun setImageUri(uri: Uri?, forceReload: Boolean = false) {
        Log.d(TAG, "setImageUri called: $uri. EditMode: $isInP1EditMode, ForceReload: $forceReload")
        if (isInP1EditMode) {
            onRequestActionCallback?.invoke(PreviewViewAction.REQUEST_CANCEL_P1_EDIT_MODE)
        }

        if (!forceReload && this.imageUri == uri && uri != null && wallpaperBitmaps?.sourceSampledBitmap != null) {
            if (!isInP1EditMode && !isTransitioningFromEditMode) { // Only update P1 top if not in edit or transition
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
        currentPreviewXOffset = 0f // Reset preview page
        if (!scroller.isFinished) {
            scroller.abortAnimation()
        }

        if (uri != null) {
            invalidate() // Show placeholder or old state briefly
            loadFullBitmapsFromUri(uri, true) // New image always needs full load
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
            invalidate() // Show loading
        }

        fullBitmapLoadingJob = viewScope.launch {
            var newWpBitmaps: SharedWallpaperRenderer.WallpaperBitmaps? = null
            try {
                ensureActive()
                newWpBitmaps = withContext(Dispatchers.IO) {
                    ensureActive()
                    // Use properties that would be set by MainActivity from ViewModel for consistency
                    val heightToUse = nonEditModePage1ImageHeightRatio // Use non-edit mode default or saved value
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
                        // Ensure P1 top is also updated based on new source and current non-edit params
                        updateOnlyPage1TopCroppedBitmap(nonEditModePage1ImageHeightRatio, wallpaperBitmaps!!.sourceSampledBitmap!!, this@WallpaperPreviewView.currentP1ContentScaleFactor)
                    } else if (isTransitioningFromEditMode) {
                        // If transitioning when full load finishes, the onComplete of the transition's P1 update will handle next step
                    } else {
                        invalidate() // Fallback invalidate if no specific mode action
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

    // Setter for nonEditModePage1ImageHeightRatio (called by MainActivity from ViewModel)
    fun setPage1ImageHeightRatio(newRatio: Float) {
        val clampedRatio = newRatio.coerceIn(WallpaperConfigConstants.MIN_HEIGHT_RATIO, WallpaperConfigConstants.MAX_HEIGHT_RATIO)
        if (abs(nonEditModePage1ImageHeightRatio - clampedRatio) > 0.001f) {
            nonEditModePage1ImageHeightRatio = clampedRatio
            if (!isInP1EditMode && !isTransitioningFromEditMode) { // Only update if not in edit/transition
                calculateP1DisplayRectView() // Recalculate for non-edit mode
                if (imageUri != null && wallpaperBitmaps?.sourceSampledBitmap != null) {
                    updateOnlyPage1TopCroppedBitmap(nonEditModePage1ImageHeightRatio, wallpaperBitmaps!!.sourceSampledBitmap!!, this.currentP1ContentScaleFactor)
                } else {
                    invalidate()
                }
            } else if (isInP1EditMode) {
                // If in edit mode and MainActivity tries to set this, it means ViewModel state changed.
                // We should probably update currentEditP1HeightRatio as well if it's a "reset" or external change.
                currentEditP1HeightRatio = clampedRatio
                calculateP1DisplayRectView()
                resetP1EditMatrixToFocus(this.currentNormalizedFocusX, this.currentNormalizedFocusY)
                attemptThrottledP1ConfigUpdate()
            }
        }
    }

    // Setter for currentNormalizedFocusX and currentNormalizedFocusY (called by MainActivity)
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
                // If in edit mode, this means ViewModel state changed, possibly due to external action.
                // Re-apply to edit matrix and notify.
                resetP1EditMatrixToFocus(this.currentNormalizedFocusX, this.currentNormalizedFocusY)
                attemptThrottledP1ConfigUpdate()
            }
        }
    }

    // Setter for currentP1ContentScaleFactor (called by MainActivity)
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
                // If in edit mode, ViewModel state changed. Update edit state.
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
        // If in edit mode AND not transitioning, this P1 top bitmap is not for immediate display.
        // However, if transitioning, this IS the target bitmap.
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
                mainScopeLaunch { // Ensure onComplete is on main thread
                    if (isActive) { // Double check isActive within main thread launch
                        onComplete?.invoke()
                    }
                }

                // If onComplete is null (meaning this wasn't a transition-ending call),
                // and we are not in edit mode or transitioning, then invalidate.
                if (onComplete == null && isActive && this@WallpaperPreviewView.imageUri != null &&
                    !this@WallpaperPreviewView.isInP1EditMode && !this@WallpaperPreviewView.isTransitioningFromEditMode) {
                    invalidate()
                }
            }
        }
    }

    // Helper to launch on main scope safely
    private fun mainScopeLaunch(block: suspend CoroutineScope.() -> Unit) {
        if (viewScope.isActive) {
            viewScope.launch {
                block()
            }
        } else {
            Log.w(TAG, "ViewScope not active, cannot launch on main for onComplete.")
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
            return true
        }
        return super.performClick()
    }

    private fun flingPage(velocityX: Float) {
        if (isInP1EditMode || numVirtualPages <= 1) {
            animateToOffset(0f)
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
            this.currentPreviewXOffset = targetXOffset.coerceIn(0f,1f)
            invalidate()
        }
    }

    override fun computeScroll() {
        if (isInP1EditMode && !scroller.isFinished) {
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
            invalidate() // invalidate() is fine here, postInvalidateOnAnimation() if from non-UI thread
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