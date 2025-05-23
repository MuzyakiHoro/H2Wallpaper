// WallpaperPreviewView.kt
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

class WallpaperPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs) {

    private val TAG = "WallpaperPreviewView"

    // --- 可配置状态 (部分由 MainActivity 通过方法设置) ---
    private var imageUri: Uri? = null
    private var selectedBackgroundColor: Int = Color.LTGRAY
    private var page1ImageHeightRatio: Float = MainActivity.DEFAULT_HEIGHT_RATIO // 引用MainActivity的默认值
    private var currentNormalizedFocusX: Float = 0.5f
    private var currentNormalizedFocusY: Float = 0.5f

    // 由 MainActivity 通过 setConfigValues 设置的参数
    private var currentScrollSensitivity: Float = MainActivity.DEFAULT_SCROLL_SENSITIVITY
    private var currentP1OverlayFadeRatio: Float = MainActivity.DEFAULT_P1_OVERLAY_FADE_RATIO
    private var currentP2BackgroundFadeInRatio: Float = MainActivity.DEFAULT_P2_BACKGROUND_FADE_IN_RATIO
    private var currentBackgroundBlurRadius: Float = MainActivity.DEFAULT_BACKGROUND_BLUR_RADIUS
    private var currentBlurDownscaleFactor: Float = MainActivity.DEFAULT_BLUR_DOWNSCALE_FACTOR_INT / 100.0f // 假设MainActivity有定义
    private var currentBlurIterations: Int = MainActivity.DEFAULT_BLUR_ITERATIONS // 假设MainActivity有定义
    private var currentSnapAnimationDurationMs: Long = MainActivity.DEFAULT_PREVIEW_SNAP_DURATION_MS
    private var currentNormalizedInitialBgScrollOffset: Float = MainActivity.DEFAULT_BACKGROUND_INITIAL_OFFSET

    // 新增的P1特效参数
    private var currentP1ShadowRadius: Float = MainActivity.DEFAULT_P1_SHADOW_RADIUS
    private var currentP1ShadowDx: Float = MainActivity.DEFAULT_P1_SHADOW_DX
    private var currentP1ShadowDy: Float = MainActivity.DEFAULT_P1_SHADOW_DY // 确保这个变量存在
    private var currentP1ShadowColor: Int = MainActivity.DEFAULT_P1_SHADOW_COLOR
    private var currentP1ImageBottomFadeHeight: Float = MainActivity.DEFAULT_P1_IMAGE_BOTTOM_FADE_HEIGHT

    private var wallpaperBitmaps: SharedWallpaperRenderer.WallpaperBitmaps? = null

    // --- 内部状态 ---
    private var viewWidth: Int = 0
    private var viewHeight: Int = 0
    private var currentPreviewXOffset: Float = 0f
    private val numVirtualPages: Int = 3

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
        Log.d(TAG, "onSizeChanged: New $viewWidth x $viewHeight, Old $oldViewWidth x $oldViewHeight")

        if (w > 0 && h > 0) {
            if (imageUri != null && (w != oldViewWidth || h != oldViewHeight || wallpaperBitmaps?.sourceSampledBitmap == null)) {
                Log.d(TAG, "onSizeChanged: Triggering full bitmap reload due to size change or missing bitmaps.")
                loadFullBitmapsFromUri(this.imageUri)
            } else if (imageUri != null && wallpaperBitmaps?.sourceSampledBitmap != null && wallpaperBitmaps?.page1TopCroppedBitmap == null) {
                Log.d(TAG, "onSizeChanged: Source bitmap exists, but P1 top cropped is missing. Updating P1 top cropped.")
                updateOnlyPage1TopCroppedBitmap(this.page1ImageHeightRatio, wallpaperBitmaps!!.sourceSampledBitmap!!)
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
                // 新增P1特效参数传递
                p1ShadowRadius = this.currentP1ShadowRadius,
                p1ShadowDx = this.currentP1ShadowDx,
                p1ShadowDy = this.currentP1ShadowDy, // <--- 这是你错误信息中指出的参数
                p1ShadowColor = this.currentP1ShadowColor,
                p1ImageBottomFadeHeight = this.currentP1ImageBottomFadeHeight
            )
            SharedWallpaperRenderer.drawFrame(canvas, config, currentWpBitmaps)
        } else {
            SharedWallpaperRenderer.drawPlaceholder(
                canvas, viewWidth, viewHeight,
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
        blurDownscaleFactor: Float, // 确保MainActivity会传递这个
        blurIterations: Int,        // 确保MainActivity会传递这个
        // 新增的P1特效参数
        p1ShadowRadius: Float,
        p1ShadowDx: Float,
        p1ShadowDy: Float,
        p1ShadowColor: Int,
        p1ImageBottomFadeHeight: Float
    ) {
        // 保存旧值
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

        // 更新成员变量
        this.currentScrollSensitivity = scrollSensitivity.coerceIn(0.1f, 5.0f)
        this.currentP1OverlayFadeRatio = p1OverlayFadeRatio.coerceIn(0.01f, 1.0f)
        this.currentP2BackgroundFadeInRatio = p2BackgroundFadeInRatio.coerceIn(0.0f, 1.0f)
        this.currentBackgroundBlurRadius = backgroundBlurRadius.coerceIn(0f, 50f)
        this.currentSnapAnimationDurationMs = snapAnimationDurationMs
        this.currentNormalizedInitialBgScrollOffset = normalizedInitialBgScrollOffset.coerceIn(0f, 1f)
        this.currentBlurDownscaleFactor = blurDownscaleFactor
        this.currentBlurIterations = blurIterations

        this.currentP1ShadowRadius = p1ShadowRadius
        this.currentP1ShadowDx = p1ShadowDx
        this.currentP1ShadowDy = p1ShadowDy
        this.currentP1ShadowColor = p1ShadowColor
        this.currentP1ImageBottomFadeHeight = p1ImageBottomFadeHeight

        Log.d(TAG, "Preview Configs Updated: ScrollSens=$currentScrollSensitivity, P1FadeRatio=$currentP1OverlayFadeRatio, BgBlur=$currentBackgroundBlurRadius, BlurDownscale=$currentBlurDownscaleFactor, BlurIter=$currentBlurIterations, P1ShadowR=$currentP1ShadowRadius, P1FadeH=$currentP1ImageBottomFadeHeight")

        // 判断是否需要重绘或重载
        val visualParamsChanged = oldScrollSensitivity != this.currentScrollSensitivity ||
                oldP1OverlayFadeRatio != this.currentP1OverlayFadeRatio ||
                oldP2BackgroundFadeInRatio != this.currentP2BackgroundFadeInRatio ||
                oldNormalizedInitialBgScrollOffset != this.currentNormalizedInitialBgScrollOffset ||
                oldP1ShadowRadius != this.currentP1ShadowRadius ||
                oldP1ShadowDx != this.currentP1ShadowDx ||
                oldP1ShadowDy != this.currentP1ShadowDy ||
                oldP1ShadowColor != this.currentP1ShadowColor ||
                oldP1ImageBottomFadeHeight != this.currentP1ImageBottomFadeHeight

        if (visualParamsChanged) {
            invalidate()
        }

        val blurConfigChanged = oldBackgroundBlurRadius != this.currentBackgroundBlurRadius ||
                oldBlurDownscaleFactor != this.currentBlurDownscaleFactor ||
                oldBlurIterations != this.currentBlurIterations

        if (blurConfigChanged && this.imageUri != null) {
            Log.d(TAG, "Blur params changed for preview, forcing full bitmap reload.")
            loadFullBitmapsFromUri(this.imageUri, forceInternalReload = true)
        }
    }

    fun setImageUri(uri: Uri?, forceReload: Boolean = false) {
        Log.d(TAG, "setImageUri called with new URI: $uri. Previous URI: ${this.imageUri}. ForceReload: $forceReload")

        if (!forceReload && this.imageUri == uri && uri != null && wallpaperBitmaps?.sourceSampledBitmap != null) {
            Log.d(TAG, "setImageUri: URI unchanged and source bitmap exists. Applying current settings for P1 if needed.")
            if (wallpaperBitmaps?.sourceSampledBitmap != null) { // Redundant check, but safe
                // If focus or height ratio changed externally then this was called, P1 might need update
                updateOnlyPage1TopCroppedBitmap(page1ImageHeightRatio, wallpaperBitmaps!!.sourceSampledBitmap!!)
            } else {
                invalidate() // Should not happen if sourceSampledBitmap is not null
            }
            return
        }

        Log.d(TAG, "setImageUri: Proceeding with full bitmap update. ForceReload: $forceReload, URI changed: ${this.imageUri != uri}")
        fullBitmapLoadingJob?.cancel(); fullBitmapLoadingJob = null
        topBitmapUpdateJob?.cancel(); topBitmapUpdateJob = null

        if (forceReload || this.imageUri != uri) {
            wallpaperBitmaps?.recycleInternals()
            wallpaperBitmaps = null
        }

        this.imageUri = uri
        currentPreviewXOffset = 0f // Reset scroll on new image
        if (!scroller.isFinished) scroller.abortAnimation()

        if (uri != null) {
            invalidate() // Show loading placeholder
            loadFullBitmapsFromUri(uri)
        } else {
            wallpaperBitmaps?.recycleInternals() // Ensure cleanup if URI becomes null
            wallpaperBitmaps = null
            Log.d(TAG, "setImageUri: URI is null. Clearing bitmaps and invalidating.")
            invalidate()
        }
    }

    private fun loadFullBitmapsFromUri(uriToLoad: Uri?, forceInternalReload: Boolean = false) {
        if (uriToLoad == null || viewWidth <= 0 || viewHeight <= 0) {
            Log.w(TAG, "loadFullBitmapsFromUri: Invalid URI or view dimensions. URI: $uriToLoad, View: ${viewWidth}x$viewHeight")
            if (!forceInternalReload) {
                wallpaperBitmaps?.recycleInternals()
                wallpaperBitmaps = null
            }
            invalidate()
            return
        }

        fullBitmapLoadingJob?.cancel()
        topBitmapUpdateJob?.cancel()

        Log.d(TAG, "loadFullBitmapsFromUri: Starting for URI: $uriToLoad. Focus:($currentNormalizedFocusX, $currentNormalizedFocusY), BlurRadius:$currentBackgroundBlurRadius, BlurDownscale:$currentBlurDownscaleFactor, BlurIter:$currentBlurIterations")

        if (!forceInternalReload || wallpaperBitmaps == null) {
            wallpaperBitmaps?.recycleInternals()
            wallpaperBitmaps = null
            invalidate() // Show loading placeholder
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
                        currentBlurDownscaleFactor, // Pass new blur param
                        currentBlurIterations       // Pass new blur param
                    )
                }
                ensureActive()

                if (this@WallpaperPreviewView.imageUri == uriToLoad) {
                    wallpaperBitmaps?.recycleInternals()
                    wallpaperBitmaps = newFullBitmaps
                    Log.d(TAG, "Full bitmaps successfully loaded and applied for $uriToLoad.")
                } else {
                    Log.d(TAG, "URI changed during full bitmap load for $uriToLoad. Current: ${this@WallpaperPreviewView.imageUri}. Discarding.")
                    newFullBitmaps?.recycleInternals()
                    if (this@WallpaperPreviewView.imageUri == null) wallpaperBitmaps = null
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Full bitmap loading for $uriToLoad CANCELLED.")
                newFullBitmaps?.recycleInternals()
            } catch (e: Exception) {
                Log.e(TAG, "Error in loadFullBitmapsFromUri for $uriToLoad", e)
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
            Log.d(TAG, "Preview background color set to: $color")
            invalidate()
        }
    }

    fun setPage1ImageHeightRatio(ratio: Float) {
        val clampedRatio = ratio.coerceIn(0.1f, 0.9f)
        if (this.page1ImageHeightRatio != clampedRatio) {
            val oldRatio = this.page1ImageHeightRatio
            this.page1ImageHeightRatio = clampedRatio
            Log.d(TAG, "setPage1ImageHeightRatio: Ratio changed from $oldRatio to $clampedRatio.")

            if (imageUri != null && wallpaperBitmaps?.sourceSampledBitmap != null) {
                updateOnlyPage1TopCroppedBitmap(clampedRatio, wallpaperBitmaps!!.sourceSampledBitmap!!)
            } else if (imageUri != null) {
                loadFullBitmapsFromUri(this.imageUri) // Full reload if source is missing
            } else {
                invalidate() // No image, just redraw placeholder if needed
            }
        }
    }

    fun setNormalizedFocus(focusX: Float, focusY: Float) {
        val clampedFocusX = focusX.coerceIn(0f, 1f)
        val clampedFocusY = focusY.coerceIn(0f, 1f)

        if (this.currentNormalizedFocusX != clampedFocusX || this.currentNormalizedFocusY != clampedFocusY) {
            this.currentNormalizedFocusX = clampedFocusX
            this.currentNormalizedFocusY = clampedFocusY
            Log.d(TAG, "Normalized focus CHANGED to X: $currentNormalizedFocusX, Y: $currentNormalizedFocusY")

            if (imageUri != null && wallpaperBitmaps?.sourceSampledBitmap != null) {
                updateOnlyPage1TopCroppedBitmap(this.page1ImageHeightRatio, wallpaperBitmaps!!.sourceSampledBitmap!!)
            } else if (imageUri != null && viewWidth > 0 && viewHeight > 0) {
                loadFullBitmapsFromUri(this.imageUri) // Full reload if source is missing
            }
        } else {
            Log.d(TAG, "Normalized focus UNCHANGED.")
        }
    }

    private fun updateOnlyPage1TopCroppedBitmap(newRatio: Float, sourceBitmap: Bitmap) {
        if (viewWidth <= 0 || viewHeight <= 0) {
            Log.w(TAG, "updateOnlyPage1TopCroppedBitmap: View not measured. Skipping update.")
            return
        }
        topBitmapUpdateJob?.cancel()
        Log.d(TAG, "updateOnlyPage1TopCroppedBitmap: Updating P1 top for ratio: $newRatio. Focus: ($currentNormalizedFocusX, $currentNormalizedFocusY)")

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
                    Log.d(TAG, "P1 top cropped bitmap updated successfully.")
                } else {
                    Log.d(TAG, "State changed during P1 top bitmap update. Discarding new top bitmap.")
                    newTopCroppedBitmap?.recycle()
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "P1 top bitmap update CANCELLED.")
                newTopCroppedBitmap?.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Error updating P1 top cropped bitmap", e)
                newTopCroppedBitmap?.recycle()
                // If it failed, and conditions still match, ensure old one is not used or set to null
                if (this@WallpaperPreviewView.imageUri != null && wallpaperBitmaps?.sourceSampledBitmap == sourceBitmap) {
                    wallpaperBitmaps?.page1TopCroppedBitmap = null // Or re-try, or keep old one if safe
                }
            } finally {
                if (isActive && coroutineContext[Job] == topBitmapUpdateJob) {
                    topBitmapUpdateJob = null
                }
                if (isActive && this@WallpaperPreviewView.imageUri != null) { // Redraw if image still set
                    invalidate()
                }
            }
        }
    }

    // --- 滑动逻辑 ---
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (imageUri == null && wallpaperBitmaps == null) { // Only process touch if there's an image
            return super.onTouchEvent(event)
        }
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker!!.addMovement(event)

        val action = event.actionMasked
        val x = event.x // current x

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (!scroller.isFinished) {
                    scroller.abortAnimation()
                }
                lastTouchX = x
                downTouchX = x
                activePointerId = event.getPointerId(0)
                isBeingDragged = false
                parent?.requestDisallowInterceptTouchEvent(true) // Request parent not to intercept
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) return false
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex < 0) return false // Should not happen

                val currentMoveX = event.getX(pointerIndex)
                val deltaX = lastTouchX - currentMoveX //手指从右向左滑, deltaX为正; 手指从左向右滑, deltaX为负

                if (!isBeingDragged && abs(currentMoveX - downTouchX) > touchSlop) {
                    isBeingDragged = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }

                if (isBeingDragged) {
                    if (viewWidth > 0 && numVirtualPages > 1) {
                        // Convert pixel scroll to normalized offset scroll (0 to 1)
                        // The total normalized scroll range is 1 (from page 0 to page numVirtualPages-1)
                        // The total pixel scroll range for this normalized 0-1 is effectively (numVirtualPages-1) * viewWidth
                        // So, deltaX / ((numVirtualPages-1) * viewWidth) would be the normalized change.
                        // However, currentPreviewXOffset is 0 for first page, 1 for last page.
                        // A scroll of viewWidth should change currentPreviewXOffset by 1/(numVirtualPages-1)
                        val scrollDeltaRatio = deltaX / (viewWidth.toFloat() * (numVirtualPages -1) ) // More direct mapping
                        // Or, using your previous logic to maintain consistency if it worked:
                        // val offsetPerViewWidthScroll = 1.0f / (numVirtualPages - 1).toFloat()
                        // val scrollDeltaRatio = (deltaX / viewWidth.toFloat()) * offsetPerViewWidthScroll

                        currentPreviewXOffset = (currentPreviewXOffset + scrollDeltaRatio).coerceIn(0f, 1f)
                    } else {
                        currentPreviewXOffset = 0f // No scroll if single page or no width
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
                    // Not a drag, check for click
                    if (abs(x - downTouchX) < touchSlop) { // It's a click
                        performClick()
                    } else { // It was a small, unintentional drag below slop, snap anyway
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
                if (isBeingDragged) { // If drag was cancelled, snap to nearest
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
        // Toggle UI visibility or other click actions here
        return true
    }

    private fun flingPage(velocityX: Float) {
        if (numVirtualPages <= 1) {
            animateToOffset(0f)
            return
        }
        // Calculate current page index based on offset
        val currentEffectivePageIndex = currentPreviewXOffset * (numVirtualPages - 1)
        var targetPageIndex: Int

        if (velocityX < -minFlingVelocity) { // Fling left (to next page)
            targetPageIndex = ceil(currentEffectivePageIndex).toInt()
            // If already very close to targetPageIndex and not the last page, go one further
            if (targetPageIndex <= currentEffectivePageIndex + 0.05f && targetPageIndex < numVirtualPages - 1) {
                targetPageIndex++
            }
        } else if (velocityX > minFlingVelocity) { // Fling right (to previous page)
            targetPageIndex = floor(currentEffectivePageIndex).toInt()
            // If already very close to targetPageIndex and not the first page, go one further
            if (targetPageIndex >= currentEffectivePageIndex - 0.05f && targetPageIndex > 0) {
                targetPageIndex--
            }
        } else { // Velocity not high enough for a fling, just snap
            snapToNearestPage(currentPreviewXOffset)
            return
        }
        targetPageIndex = targetPageIndex.coerceIn(0, numVirtualPages - 1)
        val targetXOffset = if (numVirtualPages > 1) targetPageIndex.toFloat() / (numVirtualPages - 1).toFloat() else 0f
        animateToOffset(targetXOffset)
    }

    private fun snapToNearestPage(currentOffset: Float) {
        if (numVirtualPages <= 1) {
            animateToOffset(0f) // Snap to 0 if single page
            return
        }
        val pageIndexFloat = currentOffset * (numVirtualPages - 1)
        val targetPageIndex = pageIndexFloat.roundToInt().coerceIn(0, numVirtualPages - 1)
        val targetXOffset = if (numVirtualPages > 1) targetPageIndex.toFloat() / (numVirtualPages - 1).toFloat() else 0f
        animateToOffset(targetXOffset)
    }

    private fun animateToOffset(targetXOffset: Float) {
        // Scroller works with pixels, so we need a virtual pixel range for our normalized offset
        val currentPixelOffset = (currentPreviewXOffset * getScrollRange()).toInt()
        val targetPixelOffset = (targetXOffset * getScrollRange()).toInt()
        val dx = targetPixelOffset - currentPixelOffset

        if (dx != 0) {
            scroller.startScroll(currentPixelOffset, 0, dx, 0, currentSnapAnimationDurationMs.toInt())
            postInvalidateOnAnimation()
        } else {
            // Already at target, ensure offset is exact and redraw if needed
            this.currentPreviewXOffset = targetXOffset.coerceIn(0f, 1f)
            invalidate()
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val currentPixelOffset = scroller.currX
            val scrollRange = getScrollRange()
            if (scrollRange > 0) {
                currentPreviewXOffset = (currentPixelOffset.toFloat() / scrollRange.toFloat()).coerceIn(0f, 1f)
            } else {
                currentPreviewXOffset = 0f // Avoid division by zero
            }
            invalidate()
        }
    }

    // A virtual large range for scroller to operate on, as our offset is normalized (0-1)
    private fun getScrollRange(): Int {
        // This can be any large enough number.
        // The actual scroll distance is determined by how currentPreviewXOffset is used.
        return (numVirtualPages -1) * 10000 // Or viewWidth * (numVirtualPages -1) if mapping directly to pixels
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }
}