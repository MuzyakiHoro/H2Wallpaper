package com.example.h2wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

// 导入 WallpaperConfigConstants 对象
import com.example.h2wallpaper.WallpaperConfigConstants

class WallpaperPreviewView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs) {

    private val TAG = "WallpaperPreviewView"

    // --- 可配置状态 (部分由 MainActivity 通过方法设置) ---
    private var imageUri: Uri? = null
    private var selectedBackgroundColor: Int = Color.LTGRAY // 这个通常由 MainActivity 直接设置，但初始可以有个值
    private var page1ImageHeightRatio: Float = WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO // 使用常量
    private var currentNormalizedFocusX: Float = 0.5f
    private var currentNormalizedFocusY: Float = 0.5f

    // 由 MainActivity 通过 setConfigValues 设置的参数，这些是运行时从 SharedPreferences 加载的值，
    // 但它们的初始/默认值（如果 MainActivity 没有立即设置它们）应该来自 WallpaperConfigConstants
    private var currentScrollSensitivity: Float =
        WallpaperConfigConstants.DEFAULT_SCROLL_SENSITIVITY
    private var currentP1OverlayFadeRatio: Float =
        WallpaperConfigConstants.DEFAULT_P1_OVERLAY_FADE_RATIO
    private var currentP2BackgroundFadeInRatio: Float =
        WallpaperConfigConstants.DEFAULT_P2_BACKGROUND_FADE_IN_RATIO
    private var currentBackgroundBlurRadius: Float =
        WallpaperConfigConstants.DEFAULT_BACKGROUND_BLUR_RADIUS

    // DEFAULT_BLUR_DOWNSCALE_FACTOR_INT 是整数 (代表百分比的100倍)，转换为浮点数因子
    private var currentBlurDownscaleFactor: Float =
        WallpaperConfigConstants.DEFAULT_BLUR_DOWNSCALE_FACTOR_INT / 100.0f
    private var currentBlurIterations: Int = WallpaperConfigConstants.DEFAULT_BLUR_ITERATIONS
    private var currentSnapAnimationDurationMs: Long =
        WallpaperConfigConstants.DEFAULT_PREVIEW_SNAP_DURATION_MS
    private var currentNormalizedInitialBgScrollOffset: Float =
        WallpaperConfigConstants.DEFAULT_BACKGROUND_INITIAL_OFFSET

    // 新增的P1特效参数
    private var currentP1ShadowRadius: Float = WallpaperConfigConstants.DEFAULT_P1_SHADOW_RADIUS
    private var currentP1ShadowDx: Float = WallpaperConfigConstants.DEFAULT_P1_SHADOW_DX
    private var currentP1ShadowDy: Float = WallpaperConfigConstants.DEFAULT_P1_SHADOW_DY
    private var currentP1ShadowColor: Int = WallpaperConfigConstants.DEFAULT_P1_SHADOW_COLOR
    private var currentP1ImageBottomFadeHeight: Float =
        WallpaperConfigConstants.DEFAULT_P1_IMAGE_BOTTOM_FADE_HEIGHT

    private var wallpaperBitmaps: SharedWallpaperRenderer.WallpaperBitmaps? = null

    // --- 内部状态 ---
    private var viewWidth: Int = 0
    private var viewHeight: Int = 0
    private var currentPreviewXOffset: Float = 0f
    private val numVirtualPages: Int = 3 // 这个预览视图固定为3页，与服务端的 launcher 报告页数分开

    // --- 滑动和惯性滚动 ---
    private var velocityTracker: VelocityTracker? = null
    private var scroller: OverScroller = OverScroller(context)
    private var lastTouchX: Float = 0f
    private var downTouchX: Float = 0f
    private var isBeingDragged: Boolean = false
    private val touchSlop: Int by lazy { ViewConfiguration.get(context).scaledTouchSlop }
    private val minFlingVelocity: Int by lazy { ViewConfiguration.get(context).scaledMinimumFlingVelocity }
    private val maxFlingVelocity: Int by lazy { ViewConfiguration.get(context).scaledMaximumFlingVelocity }
    private var activePointerId: Int = MotionEvent.INVALID_POINTER_ID

    private val viewScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var fullBitmapLoadingJob: Job? = null
    private var topBitmapUpdateJob: Job? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val oldViewWidth = viewWidth
        val oldViewHeight = viewHeight
        viewWidth = w
        viewHeight = h
        Log.d(
            TAG, "onSizeChanged: New $viewWidth x $viewHeight, Old $oldViewWidth x $oldViewHeight"
        )

        if (w > 0 && h > 0) {
            if (imageUri != null && (w != oldViewWidth || h != oldViewHeight || wallpaperBitmaps?.sourceSampledBitmap == null)) {
                Log.d(
                    TAG,
                    "onSizeChanged: Triggering full bitmap reload due to size change or missing bitmaps."
                )
                loadFullBitmapsFromUri(this.imageUri)
            } else if (imageUri != null && wallpaperBitmaps?.sourceSampledBitmap != null && wallpaperBitmaps?.page1TopCroppedBitmap == null) {
                Log.d(
                    TAG,
                    "onSizeChanged: Source bitmap exists, but P1 top cropped is missing. Updating P1 top cropped."
                )
                updateOnlyPage1TopCroppedBitmap(
                    this.page1ImageHeightRatio, wallpaperBitmaps!!.sourceSampledBitmap!!
                )
            } else {
                invalidate()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (viewWidth <= 0 || viewHeight <= 0) return

        val currentWpBitmaps = wallpaperBitmaps
        if (currentWpBitmaps != null && currentWpBitmaps.sourceSampledBitmap != null) {
            val config = SharedWallpaperRenderer.WallpaperConfig(
                screenWidth = viewWidth,
                screenHeight = viewHeight,
                page1BackgroundColor = selectedBackgroundColor,
                page1ImageHeightRatio = page1ImageHeightRatio,
                currentXOffset = currentPreviewXOffset,
                numVirtualPages = numVirtualPages,
                p1OverlayFadeTransitionRatio = currentP1OverlayFadeRatio,
                scrollSensitivityFactor = currentScrollSensitivity,
                normalizedInitialBgScrollOffset = currentNormalizedInitialBgScrollOffset,
                p2BackgroundFadeInRatio = currentP2BackgroundFadeInRatio,
                p1ShadowRadius = this.currentP1ShadowRadius,
                p1ShadowDx = this.currentP1ShadowDx,
                p1ShadowDy = this.currentP1ShadowDy,
                p1ShadowColor = this.currentP1ShadowColor,
                p1ImageBottomFadeHeight = this.currentP1ImageBottomFadeHeight
            )
            SharedWallpaperRenderer.drawFrame(canvas, config, currentWpBitmaps)
        } else {
            SharedWallpaperRenderer.drawPlaceholder(
                canvas,
                viewWidth,
                viewHeight,
                if (imageUri != null && (fullBitmapLoadingJob?.isActive == true || topBitmapUpdateJob?.isActive == true)) "图片加载中..."
                else "请选择图片"
            )
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Log.d(TAG, "onDetachedFromWindow: Cancelling jobs and recycling bitmaps.")
        fullBitmapLoadingJob?.cancel()
        fullBitmapLoadingJob = null
        topBitmapUpdateJob?.cancel()
        topBitmapUpdateJob = null
        viewScope.cancel()
        wallpaperBitmaps?.recycleInternals()
        wallpaperBitmaps = null
    }

    fun setConfigValues(
        scrollSensitivity: Float,
        p1OverlayFadeRatio: Float,
        backgroundBlurRadius: Float,
        snapAnimationDurationMs: Long,
        normalizedInitialBgScrollOffset: Float,
        p2BackgroundFadeInRatio: Float,
        blurDownscaleFactor: Float,
        blurIterations: Int,
        p1ShadowRadius: Float,
        p1ShadowDx: Float,
        p1ShadowDy: Float,
        p1ShadowColor: Int,
        p1ImageBottomFadeHeight: Float
    ) {
        val oldScrollSensitivity = this.currentScrollSensitivity
        val oldP1OverlayFadeRatio = this.currentP1OverlayFadeRatio
        val oldBackgroundBlurRadius = this.currentBackgroundBlurRadius
        val oldNormalizedInitialBgScrollOffset = this.currentNormalizedInitialBgScrollOffset
        val oldP2BackgroundFadeInRatio = this.currentP2BackgroundFadeInRatio
        val oldBlurDownscaleFactor = this.currentBlurDownscaleFactor
        val oldBlurIterations = this.currentBlurIterations
        val oldP1ShadowRadius = this.currentP1ShadowRadius
        val oldP1ShadowDx = this.currentP1ShadowDx
        val oldP1ShadowDy = this.currentP1ShadowDy
        val oldP1ShadowColor = this.currentP1ShadowColor
        val oldP1ImageBottomFadeHeight = this.currentP1ImageBottomFadeHeight

        this.currentScrollSensitivity = scrollSensitivity.coerceIn(0.1f, 5.0f)
        this.currentP1OverlayFadeRatio = p1OverlayFadeRatio.coerceIn(0.01f, 1.0f)
        this.currentP2BackgroundFadeInRatio = p2BackgroundFadeInRatio.coerceIn(0.0f, 1.0f)
        this.currentBackgroundBlurRadius = backgroundBlurRadius.coerceIn(0f, 50f) // 实际模糊范围，和设置UI对应
        this.currentSnapAnimationDurationMs = snapAnimationDurationMs
        this.currentNormalizedInitialBgScrollOffset =
            normalizedInitialBgScrollOffset.coerceIn(0f, 1f)
        this.currentBlurDownscaleFactor = blurDownscaleFactor.coerceIn(0.05f, 1.0f) // 确保在合理范围
        this.currentBlurIterations = blurIterations.coerceIn(1, 3) // 确保在合理范围

        this.currentP1ShadowRadius = p1ShadowRadius.coerceIn(0f, 50f) // 假设合理范围
        this.currentP1ShadowDx = p1ShadowDx.coerceIn(-50f, 50f) // 假设合理范围
        this.currentP1ShadowDy = p1ShadowDy.coerceIn(-50f, 50f) // 假设合理范围
        this.currentP1ShadowColor = p1ShadowColor
        this.currentP1ImageBottomFadeHeight = p1ImageBottomFadeHeight.coerceAtLeast(0f)

        Log.d(
            TAG,
            "Preview Configs Updated: ScrollSens=$currentScrollSensitivity, P1FadeRatio=$currentP1OverlayFadeRatio, BgBlur=$currentBackgroundBlurRadius, BlurDownscale=$currentBlurDownscaleFactor, BlurIter=$currentBlurIterations, P1ShadowR=$currentP1ShadowRadius, P1FadeH=$currentP1ImageBottomFadeHeight"
        )

        val visualParamsChanged =
            oldScrollSensitivity != this.currentScrollSensitivity || oldP1OverlayFadeRatio != this.currentP1OverlayFadeRatio || oldP2BackgroundFadeInRatio != this.currentP2BackgroundFadeInRatio || oldNormalizedInitialBgScrollOffset != this.currentNormalizedInitialBgScrollOffset || oldP1ShadowRadius != this.currentP1ShadowRadius || oldP1ShadowDx != this.currentP1ShadowDx || oldP1ShadowDy != this.currentP1ShadowDy || oldP1ShadowColor != this.currentP1ShadowColor || oldP1ImageBottomFadeHeight != this.currentP1ImageBottomFadeHeight

        if (visualParamsChanged) {
            invalidate()
        }

        val blurConfigChanged =
            oldBackgroundBlurRadius != this.currentBackgroundBlurRadius || oldBlurDownscaleFactor != this.currentBlurDownscaleFactor || oldBlurIterations != this.currentBlurIterations

        if (blurConfigChanged && this.imageUri != null) {
            Log.d(TAG, "Blur params changed for preview, forcing full bitmap reload.")
            loadFullBitmapsFromUri(this.imageUri, forceInternalReload = true)
        }
    }

    fun setImageUri(uri: Uri?, forceReload: Boolean = false) {
        Log.d(
            TAG,
            "setImageUri called with new URI: $uri. Previous URI: ${this.imageUri}. ForceReload: $forceReload"
        )

        if (!forceReload && this.imageUri == uri && uri != null && wallpaperBitmaps?.sourceSampledBitmap != null) {
            if (wallpaperBitmaps?.sourceSampledBitmap != null) {
                updateOnlyPage1TopCroppedBitmap(
                    page1ImageHeightRatio, wallpaperBitmaps!!.sourceSampledBitmap!!
                )
            } else {
                invalidate()
            }
            return
        }

        fullBitmapLoadingJob?.cancel(); fullBitmapLoadingJob = null
        topBitmapUpdateJob?.cancel(); topBitmapUpdateJob = null

        if (forceReload || this.imageUri != uri) {
            wallpaperBitmaps?.recycleInternals()
            wallpaperBitmaps = null
        }

        this.imageUri = uri
        currentPreviewXOffset = 0f
        if (!scroller.isFinished) scroller.abortAnimation()

        if (uri != null) {
            invalidate()
            loadFullBitmapsFromUri(uri)
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

        if (!forceInternalReload || wallpaperBitmaps == null) {
            wallpaperBitmaps?.recycleInternals()
            wallpaperBitmaps = null
            invalidate()
        }

        fullBitmapLoadingJob = viewScope.launch {
            var newFullBitmaps: SharedWallpaperRenderer.WallpaperBitmaps? = null
            try {
                ensureActive()
                newFullBitmaps = withContext(Dispatchers.IO) {
                    ensureActive()
                    SharedWallpaperRenderer.loadAndProcessInitialBitmaps(
                        context,
                        uriToLoad,
                        viewWidth,
                        viewHeight,
                        page1ImageHeightRatio,
                        currentNormalizedFocusX,
                        currentNormalizedFocusY,
                        currentBackgroundBlurRadius,
                        currentBlurDownscaleFactor,
                        currentBlurIterations
                    )
                }
                ensureActive()

                if (this@WallpaperPreviewView.imageUri == uriToLoad) {
                    wallpaperBitmaps?.recycleInternals()
                    wallpaperBitmaps = newFullBitmaps
                } else {
                    newFullBitmaps?.recycleInternals()
                    if (this@WallpaperPreviewView.imageUri == null) wallpaperBitmaps = null
                }
            } catch (e: CancellationException) {
                newFullBitmaps?.recycleInternals()
            } catch (e: Exception) {
                newFullBitmaps?.recycleInternals()
                if (this@WallpaperPreviewView.imageUri == uriToLoad) wallpaperBitmaps = null
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

    fun setSelectedBackgroundColor(color: Int) {
        if (this.selectedBackgroundColor != color) {
            this.selectedBackgroundColor = color
            invalidate()
        }
    }

    fun setPage1ImageHeightRatio(ratio: Float) {
        // 使用 WallpaperConfigConstants 中的 MIN_HEIGHT_RATIO 和 MAX_HEIGHT_RATIO
        val clampedRatio = ratio.coerceIn(
            WallpaperConfigConstants.MIN_HEIGHT_RATIO, WallpaperConfigConstants.MAX_HEIGHT_RATIO
        )
        if (this.page1ImageHeightRatio != clampedRatio) {
            this.page1ImageHeightRatio = clampedRatio
            if (imageUri != null && wallpaperBitmaps?.sourceSampledBitmap != null) {
                updateOnlyPage1TopCroppedBitmap(
                    clampedRatio, wallpaperBitmaps!!.sourceSampledBitmap!!
                )
            } else if (imageUri != null) {
                loadFullBitmapsFromUri(this.imageUri)
            } else {
                invalidate()
            }
        }
    }

    fun setNormalizedFocus(focusX: Float, focusY: Float) {
        val clampedFocusX = focusX.coerceIn(0f, 1f)
        val clampedFocusY = focusY.coerceIn(0f, 1f)

        if (this.currentNormalizedFocusX != clampedFocusX || this.currentNormalizedFocusY != clampedFocusY) {
            this.currentNormalizedFocusX = clampedFocusX
            this.currentNormalizedFocusY = clampedFocusY
            if (imageUri != null && wallpaperBitmaps?.sourceSampledBitmap != null) {
                updateOnlyPage1TopCroppedBitmap(
                    this.page1ImageHeightRatio, wallpaperBitmaps!!.sourceSampledBitmap!!
                )
            } else if (imageUri != null && viewWidth > 0 && viewHeight > 0) {
                loadFullBitmapsFromUri(this.imageUri)
            }
        }
    }

    private fun updateOnlyPage1TopCroppedBitmap(newRatio: Float, sourceBitmap: Bitmap) {
        if (viewWidth <= 0 || viewHeight <= 0) {
            return
        }
        topBitmapUpdateJob?.cancel()
        topBitmapUpdateJob = viewScope.launch {
            var newTopCroppedBitmap: Bitmap? = null
            try {
                ensureActive()
                newTopCroppedBitmap = withContext(Dispatchers.Default) {
                    ensureActive()
                    SharedWallpaperRenderer.preparePage1TopCroppedBitmap(
                        sourceBitmap,
                        viewWidth,
                        viewHeight,
                        newRatio,
                        currentNormalizedFocusX,
                        currentNormalizedFocusY
                    )
                }
                ensureActive()
                if (this@WallpaperPreviewView.imageUri != null && wallpaperBitmaps?.sourceSampledBitmap == sourceBitmap) {
                    wallpaperBitmaps?.page1TopCroppedBitmap?.recycle()
                    wallpaperBitmaps?.page1TopCroppedBitmap = newTopCroppedBitmap
                } else {
                    newTopCroppedBitmap?.recycle()
                }
            } catch (e: CancellationException) {
                newTopCroppedBitmap?.recycle()
            } catch (e: Exception) {
                newTopCroppedBitmap?.recycle()
                if (this@WallpaperPreviewView.imageUri != null && wallpaperBitmaps?.sourceSampledBitmap == sourceBitmap) {
                    wallpaperBitmaps?.page1TopCroppedBitmap = null
                }
            } finally {
                if (isActive && coroutineContext[Job] == topBitmapUpdateJob) {
                    topBitmapUpdateJob = null
                }
                if (isActive && this@WallpaperPreviewView.imageUri != null) {
                    invalidate()
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (imageUri == null && wallpaperBitmaps == null) {
            return super.onTouchEvent(event)
        }
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker!!.addMovement(event)

        val action = event.actionMasked
        val x = event.x

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (!scroller.isFinished) {
                    scroller.abortAnimation()
                }
                lastTouchX = x
                downTouchX = x
                activePointerId = event.getPointerId(0)
                isBeingDragged = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) return false
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex < 0) return false

                val currentMoveX = event.getX(pointerIndex)
                val deltaX = lastTouchX - currentMoveX

                if (!isBeingDragged && abs(currentMoveX - downTouchX) > touchSlop) {
                    isBeingDragged = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }

                if (isBeingDragged) {
                    if (viewWidth > 0 && numVirtualPages > 1) {
                        val scrollDeltaRatio =
                            deltaX / (viewWidth.toFloat() * (numVirtualPages - 1))
                        currentPreviewXOffset =
                            (currentPreviewXOffset + scrollDeltaRatio).coerceIn(0f, 1f)
                    } else {
                        currentPreviewXOffset = 0f
                    }
                    lastTouchX = currentMoveX
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) return false
                if (isBeingDragged) {
                    val vt = velocityTracker!!
                    vt.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                    val velocityX = vt.getXVelocity(activePointerId)

                    if (abs(velocityX) > minFlingVelocity && numVirtualPages > 1) {
                        flingPage(velocityX)
                    } else {
                        snapToNearestPage(currentPreviewXOffset)
                    }
                } else {
                    if (abs(x - downTouchX) < touchSlop) {
                        performClick()
                    } else {
                        snapToNearestPage(currentPreviewXOffset)
                    }
                }
                recycleVelocityTracker()
                activePointerId = MotionEvent.INVALID_POINTER_ID
                isBeingDragged = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) return false
                if (isBeingDragged) {
                    snapToNearestPage(currentPreviewXOffset)
                }
                recycleVelocityTracker()
                activePointerId = MotionEvent.INVALID_POINTER_ID
                isBeingDragged = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        Log.d(TAG, "performClick called on WallpaperPreviewView")
        return true
    }

    private fun flingPage(velocityX: Float) {
        if (numVirtualPages <= 1) {
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
        val targetXOffset =
            if (numVirtualPages > 1) targetPageIndex.toFloat() / (numVirtualPages - 1).toFloat() else 0f
        animateToOffset(targetXOffset)
    }

    private fun snapToNearestPage(currentOffset: Float) {
        if (numVirtualPages <= 1) {
            animateToOffset(0f)
            return
        }
        val pageIndexFloat = currentOffset * (numVirtualPages - 1)
        val targetPageIndex = pageIndexFloat.roundToInt().coerceIn(0, numVirtualPages - 1)
        val targetXOffset =
            if (numVirtualPages > 1) targetPageIndex.toFloat() / (numVirtualPages - 1).toFloat() else 0f
        animateToOffset(targetXOffset)
    }

    private fun animateToOffset(targetXOffset: Float) {
        val currentPixelOffset = (currentPreviewXOffset * getScrollRange()).toInt()
        val targetPixelOffset = (targetXOffset * getScrollRange()).toInt()
        val dx = targetPixelOffset - currentPixelOffset

        if (dx != 0) {
            // currentSnapAnimationDurationMs 已经从 WallpaperConfigConstants 初始化
            scroller.startScroll(
                currentPixelOffset, 0, dx, 0, currentSnapAnimationDurationMs.toInt()
            )
            postInvalidateOnAnimation()
        } else {
            this.currentPreviewXOffset = targetXOffset.coerceIn(0f, 1f)
            invalidate()
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val currentPixelOffset = scroller.currX
            val scrollRange = getScrollRange()
            if (scrollRange > 0) {
                currentPreviewXOffset =
                    (currentPixelOffset.toFloat() / scrollRange.toFloat()).coerceIn(0f, 1f)
            } else {
                currentPreviewXOffset = 0f
            }
            invalidate()
        }
    }

    private fun getScrollRange(): Int {
        return (numVirtualPages - 1) * 10000
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }
}