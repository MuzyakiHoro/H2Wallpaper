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
    private var selectedBackgroundColor: Int = Color.LTGRAY // MainActivity会通过方法设置
    private var page1ImageHeightRatio: Float = 1f / 3f    // MainActivity会通过方法设置
    private var currentNormalizedFocusX: Float = 0.5f     // MainActivity会通过方法设置
    private var currentNormalizedFocusY: Float = 0.5f     // MainActivity会通过方法设置

    // 新增: 由 MainActivity 通过 setConfigValues 设置的参数
    private var currentScrollSensitivity: Float = 1.0f
    private var currentP1OverlayFadeRatio: Float = 0.2f // 初始值可以与之前硬编码一致
    private var currentBackgroundBlurRadius: Float = 25f // 初始值可以与之前硬编码一致
    private var currentSnapAnimationDurationMs: Long = 700L
    private var currentNormalizedInitialBgScrollOffset: Float = 0.0f // 新增


    private var wallpaperBitmaps: SharedWallpaperRenderer.WallpaperBitmaps? = null

    // --- 内部状态 ---
    private var viewWidth: Int = 0
    private var viewHeight: Int = 0
    private var currentPreviewXOffset: Float = 0f
    private val numVirtualPages: Int = 3 // 预览时模拟的虚拟页面数
    // p1OverlayFadeTransitionRatio 现在由 currentP1OverlayFadeRatio 控制

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

    // Companion object 不再需要 SNAP_ANIMATION_DURATION_MS
    // companion object {
    // }

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
                p1OverlayFadeTransitionRatio = currentP1OverlayFadeRatio,   // 使用成员变量
                scrollSensitivityFactor = currentScrollSensitivity,        // 使用成员变量
                normalizedInitialBgScrollOffset = currentNormalizedInitialBgScrollOffset // 传递新参数
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

    // --- 公共方法供 MainActivity 设置配置 ---
    fun setConfigValues(
        scrollSensitivity: Float,
        p1OverlayFadeRatio: Float,
        backgroundBlurRadius: Float,
        snapAnimationDurationMs: Long,
        normalizedInitialBgScrollOffset: Float // 新增参数
    ) {
        val sensitivityChanged = this.currentScrollSensitivity != scrollSensitivity
        val fadeRatioChanged = this.currentP1OverlayFadeRatio != p1OverlayFadeRatio
        val blurRadiusChanged = this.currentBackgroundBlurRadius != backgroundBlurRadius
        // val snapDurationChanged = this.currentSnapAnimationDurationMs != snapAnimationDurationMs // snap duration change only affects new animations

        this.currentScrollSensitivity = scrollSensitivity.coerceIn(0.1f, 5.0f)
        this.currentP1OverlayFadeRatio = p1OverlayFadeRatio.coerceIn(0.01f, 1.0f)
        this.currentBackgroundBlurRadius = backgroundBlurRadius.coerceIn(0f, 25f) // Max 25f for RenderScript
        this.currentSnapAnimationDurationMs = snapAnimationDurationMs
        val initialBgOffsetChanged = this.currentNormalizedInitialBgScrollOffset != normalizedInitialBgScrollOffset
        this.currentNormalizedInitialBgScrollOffset = normalizedInitialBgScrollOffset.coerceIn(0f, 1f)


        Log.d(TAG, "Preview Configs Updated: Sensitivity=$currentScrollSensitivity, FadeRatio=$currentP1OverlayFadeRatio, Blur=$currentBackgroundBlurRadius, SnapMs=$currentSnapAnimationDurationMs")

        if (sensitivityChanged || fadeRatioChanged) {
            invalidate() // These parameter changes only require a redraw
        }

        // If blur radius changed and an image is already loaded, force a full bitmap reload
        if (blurRadiusChanged && this.imageUri != null) {
            Log.d(TAG, "Blur radius changed for preview, forcing full bitmap reload.")
            // Pass current imageUri to reload it with new blur settings
            loadFullBitmapsFromUri(this.imageUri, forceInternalReload = true)
        }
    }

    fun setImageUri(uri: Uri?, forceReload: Boolean = false) {
        Log.d(TAG, "setImageUri called with new URI: $uri. Previous URI: ${this.imageUri}. ForceReload: $forceReload")

        if (!forceReload && this.imageUri == uri && uri != null && wallpaperBitmaps?.sourceSampledBitmap != null) {
            Log.d(TAG, "setImageUri: URI unchanged and source bitmap exists (not forcing reload). Applying current settings.")
            // It might still be necessary to update P1 if focus/height changed externally and then this was called.
            if (wallpaperBitmaps?.sourceSampledBitmap != null) {
                updateOnlyPage1TopCroppedBitmap(page1ImageHeightRatio, wallpaperBitmaps!!.sourceSampledBitmap!!)
            } else {
                invalidate()
            }
            return
        }

        Log.d(TAG, "setImageUri: Proceeding with full bitmap update logic. ForceReload: $forceReload, URI changed: ${this.imageUri != uri}")
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
            invalidate() // Show loading placeholder
            loadFullBitmapsFromUri(uri) // This will use current member variables for blur, focus etc.
        } else {
            wallpaperBitmaps?.recycleInternals()
            wallpaperBitmaps = null
            Log.d(TAG, "setImageUri: URI is null. Clearing bitmaps and invalidating.")
            invalidate()
        }
    }

    // Modified to accept a flag for internal reloads (e.g., due to blur change)
    private fun loadFullBitmapsFromUri(uriToLoad: Uri?, forceInternalReload: Boolean = false) {
        if (uriToLoad == null || viewWidth <= 0 || viewHeight <= 0) {
            Log.w(TAG, "loadFullBitmapsFromUri: Invalid URI or view dimensions. URI: $uriToLoad, View: ${viewWidth}x$viewHeight")
            if (!forceInternalReload) { // Only clear if not an internal reload of existing URI
                wallpaperBitmaps?.recycleInternals()
                wallpaperBitmaps = null
            }
            invalidate()
            return
        }

        fullBitmapLoadingJob?.cancel()
        topBitmapUpdateJob?.cancel() // Also cancel this as full load will regenerate P1 top

        Log.d(TAG, "loadFullBitmapsFromUri: Starting full bitmap load for URI: $uriToLoad. Focus: ($currentNormalizedFocusX, $currentNormalizedFocusY), Blur: $currentBackgroundBlurRadius")

        // If it's not an internal reload for an existing URI, or if bitmaps are truly null, then clear/invalidate.
        // An internal reload (e.g. for blur) means imageUri is still valid.
        if (!forceInternalReload || wallpaperBitmaps == null) {
            wallpaperBitmaps?.recycleInternals() // Recycle if any old ones exist
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
                        //numVirtualPages,
                        currentBackgroundBlurRadius // Use the member variable
                    )
                }
                ensureActive()

                // Check if the URI context for this view has changed during the load
                if (this@WallpaperPreviewView.imageUri == uriToLoad) {
                    wallpaperBitmaps?.recycleInternals() // Recycle any previous (should be null if logic above is correct)
                    wallpaperBitmaps = newFullBitmaps
                    Log.d(TAG, "Full bitmaps successfully loaded and applied for $uriToLoad.")
                } else {
                    Log.d(TAG, "URI changed during full bitmap load for $uriToLoad. Current view URI: ${this@WallpaperPreviewView.imageUri}. Discarding loaded bitmaps.")
                    newFullBitmaps?.recycleInternals()
                    if (this@WallpaperPreviewView.imageUri == null) {
                        wallpaperBitmaps = null // Ensure it's cleared if current URI became null
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Full bitmap loading for $uriToLoad CANCELLED.")
                newFullBitmaps?.recycleInternals()
            } catch (e: Exception) {
                Log.e(TAG, "Error in loadFullBitmapsFromUri for $uriToLoad", e)
                newFullBitmaps?.recycleInternals()
                if (this@WallpaperPreviewView.imageUri == uriToLoad) {
                    wallpaperBitmaps = null
                }
            } finally {
                if (isActive && coroutineContext[Job] == fullBitmapLoadingJob) {
                    fullBitmapLoadingJob = null
                }
                // Redraw if the job was for the current URI or if current URI is now null (cleared state)
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
            invalidate() // Background color change only needs redraw
        }
    }

    fun setPage1ImageHeightRatio(ratio: Float) {
        val clampedRatio = ratio.coerceIn(0.1f, 0.9f)
        if (this.page1ImageHeightRatio != clampedRatio) {
            val oldRatio = this.page1ImageHeightRatio
            this.page1ImageHeightRatio = clampedRatio
            Log.d(TAG, "setPage1ImageHeightRatio: Ratio changed from $oldRatio to $clampedRatio.")

            if (imageUri != null && wallpaperBitmaps?.sourceSampledBitmap != null) {
                Log.d(TAG, "Source bitmap exists, updating only P1 top cropped bitmap for height ratio change.")
                updateOnlyPage1TopCroppedBitmap(clampedRatio, wallpaperBitmaps!!.sourceSampledBitmap!!)
            } else if (imageUri != null) {
                Log.d(TAG, "Source bitmap missing, triggering full reload for height ratio change.")
                loadFullBitmapsFromUri(this.imageUri)
            } else {
                invalidate()
            }
        }
    }

    fun setNormalizedFocus(focusX: Float, focusY: Float) {
        val clampedFocusX = focusX.coerceIn(0f, 1f)
        val clampedFocusY = focusY.coerceIn(0f, 1f)

        Log.d(TAG, "setNormalizedFocus called with X: $clampedFocusX, Y: $clampedFocusY. Current focus: ($currentNormalizedFocusX, $currentNormalizedFocusY)")

        if (this.currentNormalizedFocusX != clampedFocusX || this.currentNormalizedFocusY != clampedFocusY) {
            this.currentNormalizedFocusX = clampedFocusX
            this.currentNormalizedFocusY = clampedFocusY
            Log.d(TAG, "Normalized focus CHANGED to X: $currentNormalizedFocusX, Y: $currentNormalizedFocusY")

            if (imageUri != null && wallpaperBitmaps?.sourceSampledBitmap != null) {
                Log.d(TAG, "Image and source bitmap exist, updating P1 top cropped bitmap due to focus change.")
                updateOnlyPage1TopCroppedBitmap(this.page1ImageHeightRatio, wallpaperBitmaps!!.sourceSampledBitmap!!)
            } else if (imageUri != null && viewWidth > 0 && viewHeight > 0) {
                Log.d(TAG, "Image URI exists but source bitmap is null. Triggering full reload which will use new focus.")
                loadFullBitmapsFromUri(this.imageUri)
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
        Log.d(TAG, "updateOnlyPage1TopCroppedBitmap: Updating P1 top cropped for ratio: $newRatio. Focus: ($currentNormalizedFocusX, $currentNormalizedFocusY)")

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
            } finally {
                if (isActive && coroutineContext[Job] == topBitmapUpdateJob) {
                    topBitmapUpdateJob = null
                }
                if (isActive && this@WallpaperPreviewView.imageUri != null && wallpaperBitmaps?.sourceSampledBitmap == sourceBitmap) {
                    invalidate()
                }
            }
        }
    }

    // --- 滑动逻辑 ---
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
                        val offsetPerViewWidthScroll = 1.0f / (numVirtualPages - 1).toFloat()
                        val scrollDeltaRatio = (deltaX / viewWidth.toFloat()) * offsetPerViewWidthScroll
                        currentPreviewXOffset = (currentPreviewXOffset + scrollDeltaRatio).coerceIn(0f, 1f)
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
        val targetXOffset = if (numVirtualPages > 1) targetPageIndex.toFloat() / (numVirtualPages - 1).toFloat() else 0f
        animateToOffset(targetXOffset)
    }

    private fun snapToNearestPage(currentOffset: Float) {
        if (numVirtualPages <= 1) {
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
            // Use currentSnapAnimationDurationMs for the animation duration
            scroller.startScroll(currentPixelOffset, 0, dx, 0, currentSnapAnimationDurationMs.toInt())
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
                currentPreviewXOffset = (currentPixelOffset.toFloat() / scrollRange.toFloat()).coerceIn(0f, 1f)
            } else {
                currentPreviewXOffset = 0f
            }
            invalidate()
        }
    }

    private fun getScrollRange(): Int {
        return 10000
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }
}