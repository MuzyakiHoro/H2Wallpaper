package com.example.h2wallpaper

import android.content.Context
import android.graphics.Bitmap // 需要导入 Bitmap
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

    // --- 可配置状态 ---
    private var imageUri: Uri? = null
    private var selectedBackgroundColor: Int = Color.LTGRAY
    private var page1ImageHeightRatio: Float = 1f / 3f

    // 持有 WallpaperBitmaps 对象，其内部成员是可变的 (var)
    private var wallpaperBitmaps: SharedWallpaperRenderer.WallpaperBitmaps? = null

    // --- 内部状态 ---
    private var viewWidth: Int = 0
    private var viewHeight: Int = 0
    private var currentPreviewXOffset: Float = 0f
    private val numVirtualPages: Int = 3
    private val p1OverlayFadeTransitionRatio: Float = 0.2f

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

    // --- 协程 ---
    private val viewScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var fullBitmapLoadingJob: Job? = null // 用于加载全套位图的任务 (setImageUri)
    private var topBitmapUpdateJob: Job? = null   // 用于仅更新顶部位图的任务 (setPage1ImageHeightRatio)


    companion object {
        private const val SNAP_ANIMATION_DURATION_MS = 700
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val oldViewWidth = viewWidth
        val oldViewHeight = viewHeight
        viewWidth = w
        viewHeight = h
        Log.d(TAG, "onSizeChanged: New $viewWidth x $viewHeight, Old $oldViewWidth x $oldViewHeight")

        if (w > 0 && h > 0) {
            if (imageUri != null && (w != oldViewWidth || h != oldViewHeight || wallpaperBitmaps == null || wallpaperBitmaps?.sourceSampledBitmap == null)) {
                Log.d(TAG, "onSizeChanged: Triggering full bitmap reload due to size change or missing bitmaps.")
                loadFullBitmapsFromUri(this.imageUri)
            } else if (imageUri != null && wallpaperBitmaps?.page1TopCroppedBitmap == null) {
                // wallpaperBitmaps 肯定不为 null (来自上一个if的else分支)
                // 并且 sourceSampledBitmap 也应该不为 null (同样来自上一个if的else分支)
                wallpaperBitmaps!!.sourceSampledBitmap?.let { srcBitmap -> // 使用安全调用和 let
                    Log.d(TAG, "onSizeChanged: Source bitmap exists, but top cropped is missing. Updating top cropped.")
                    updateOnlyPage1TopCroppedBitmap(this.page1ImageHeightRatio, srcBitmap) // srcBitmap 在这里是 Bitmap (非空)
                } ?: run {
                    // 如果 sourceSampledBitmap 意外为 null，记录警告并可能触发完整加载
                    Log.w(TAG, "onSizeChanged: Source bitmap was null when trying to update top cropped. Forcing full reload.")
                    if (this.imageUri != null) loadFullBitmapsFromUri(this.imageUri)
                }
            } else {
                invalidate()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (viewWidth <= 0 || viewHeight <= 0) return

        val currentWpBitmaps = wallpaperBitmaps
        // 只要 wallpaperBitmaps 对象存在，就尝试用它绘制，让 SharedWallpaperRenderer.drawFrame 内部处理其成员可能为null的情况
        if (currentWpBitmaps != null) {
            SharedWallpaperRenderer.drawFrame(
                canvas,
                SharedWallpaperRenderer.WallpaperConfig(
                    screenWidth = viewWidth, screenHeight = viewHeight,
                    page1BackgroundColor = selectedBackgroundColor, page1ImageHeightRatio = page1ImageHeightRatio,
                    currentXOffset = currentPreviewXOffset, numVirtualPages = numVirtualPages,
                    p1OverlayFadeTransitionRatio = p1OverlayFadeTransitionRatio
                ),
                currentWpBitmaps
            )
        } else {
            SharedWallpaperRenderer.drawPlaceholder(canvas, viewWidth, viewHeight,
                if (imageUri != null && (fullBitmapLoadingJob?.isActive == true || topBitmapUpdateJob?.isActive == true) ) "图片加载中..."
                else "请选择图片"
            )
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        fullBitmapLoadingJob?.cancel(); fullBitmapLoadingJob = null
        topBitmapUpdateJob?.cancel(); topBitmapUpdateJob = null
        viewScope.cancel()
        wallpaperBitmaps?.recycleInternals() // 使用新的回收方法
        wallpaperBitmaps = null
        Log.d(TAG, "onDetachedFromWindow: Cleaned up resources.")
    }

    fun setImageUri(uri: Uri?) {
        if (this.imageUri == uri && uri != null && wallpaperBitmaps?.sourceSampledBitmap != null) {
            Log.d(TAG, "setImageUri: URI unchanged and source bitmap exists. Invalidating.")
            invalidate()
            return
        }

        Log.d(TAG, "setImageUri called with new URI: $uri. Previous URI: ${this.imageUri}")
        // 取消所有正在进行的位图操作
        fullBitmapLoadingJob?.cancel(); fullBitmapLoadingJob = null
        topBitmapUpdateJob?.cancel(); topBitmapUpdateJob = null

        // 回收旧的 wallpaperBitmaps 对象及其内部所有位图
        wallpaperBitmaps?.recycleInternals()
        wallpaperBitmaps = null // 先置空，以便显示加载状态

        this.imageUri = uri
        currentPreviewXOffset = 0f
        if (!scroller.isFinished) scroller.abortAnimation()

        if (uri != null) {
            invalidate() // 立即重绘以显示占位符
            loadFullBitmapsFromUri(uri)
        } else {
            invalidate() // 新 URI 为 null，重绘以显示“请选择图片”
        }
    }

    private fun loadFullBitmapsFromUri(uriToLoad: Uri?) {
        if (uriToLoad == null || viewWidth <= 0 || viewHeight <= 0) {
            Log.w(TAG, "loadFullBitmapsFromUri: Invalid URI or view dimensions.")
            wallpaperBitmaps?.recycleInternals()
            wallpaperBitmaps = null
            invalidate()
            return
        }

        fullBitmapLoadingJob?.cancel() // 取消之前的完整加载任务
        topBitmapUpdateJob?.cancel()   // 也取消可能在进行的顶图更新任务

        Log.d(TAG, "loadFullBitmapsFromUri: Starting full bitmap load for URI: $uriToLoad")
        // 在启动新任务前，确保旧的 wallpaperBitmaps (如果从其他地方来) 被正确处理或已为null
        if (wallpaperBitmaps != null) { // 可能由 setPage1ImageHeightRatio 等调用后未完成的任务遗留
            wallpaperBitmaps?.recycleInternals()
            wallpaperBitmaps = null
        }
        invalidate() // 显示加载中

        fullBitmapLoadingJob = viewScope.launch {
            var newFullBitmaps: SharedWallpaperRenderer.WallpaperBitmaps? = null
            try {
                ensureActive()
                newFullBitmaps = withContext(Dispatchers.IO) {
                    ensureActive()
                    SharedWallpaperRenderer.loadAndProcessInitialBitmaps(
                        context, uriToLoad, viewWidth, viewHeight,
                        page1ImageHeightRatio, numVirtualPages, 0f // 预览时不模糊
                    )
                }
                ensureActive()

                if (imageUri == uriToLoad) { // 确保URI在加载期间未变
                    wallpaperBitmaps?.recycleInternals() // 回收旧的（理论上此时应为null）
                    wallpaperBitmaps = newFullBitmaps
                    Log.d(TAG, "Full bitmaps successfully loaded and applied for $uriToLoad.")
                } else {
                    Log.d(TAG, "URI changed during full bitmap load for $uriToLoad. Discarding.")
                    newFullBitmaps?.recycleInternals()
                    if(imageUri == null) { // 如果当前URI已变为null
                        wallpaperBitmaps?.recycleInternals()
                        wallpaperBitmaps = null
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Full bitmap loading for $uriToLoad CANCELLED.")
                newFullBitmaps?.recycleInternals() // 回收部分加载的
            } catch (e: Exception) {
                Log.e(TAG, "Error in loadFullBitmapsFromUri for $uriToLoad", e)
                newFullBitmaps?.recycleInternals()
                wallpaperBitmaps?.recycleInternals() // 确保清除
                wallpaperBitmaps = null
            } finally {
                if (coroutineContext[Job] == fullBitmapLoadingJob) { // 清理自身job引用
                    fullBitmapLoadingJob = null
                }
                if (imageUri == uriToLoad || imageUri == null) {
                    invalidate() // 最终重绘
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
        val clampedRatio = ratio.coerceIn(0.1f, 0.9f)
        if (this.page1ImageHeightRatio != clampedRatio) {
            val oldRatio = this.page1ImageHeightRatio
            this.page1ImageHeightRatio = clampedRatio
            Log.d(TAG, "setPage1ImageHeightRatio: Ratio changed from $oldRatio to $clampedRatio.")

            // 如果 wallpaperBitmaps 或其 sourceSampledBitmap 为空，说明需要完整加载
            if (imageUri != null && (wallpaperBitmaps == null || wallpaperBitmaps?.sourceSampledBitmap == null)) {
                Log.d(TAG, "Source bitmap missing, triggering full reload for height change.")
                loadFullBitmapsFromUri(this.imageUri) // 触发完整重新加载
            } else if (imageUri != null && wallpaperBitmaps?.sourceSampledBitmap != null) {
                // 有源图，只更新顶图
                updateOnlyPage1TopCroppedBitmap(clampedRatio, wallpaperBitmaps!!.sourceSampledBitmap!!)
            } else {
                invalidate() // 无图，仅重绘
            }
        }
    }

    private fun updateOnlyPage1TopCroppedBitmap(newRatio: Float, sourceBitmap: Bitmap) {
        topBitmapUpdateJob?.cancel() // 取消之前的顶图更新任务
        fullBitmapLoadingJob?.cancel() // 也取消可能的完整加载任务，因为我们只更新顶图

        Log.d(TAG, "updateOnlyPage1TopCroppedBitmap: Updating top cropped for ratio: $newRatio")
        // 这个操作理论上应该很快，但为了避免任何可能的ANR，仍然使用协程
        // 并且，如果用户快速连续点击，这也提供了一个取消点
        topBitmapUpdateJob = viewScope.launch {
            var newTopCroppedBitmap: Bitmap? = null
            var exceptionOccurred = false
            try {
                ensureActive()
                // preparePage1TopCroppedBitmap 相对较快，可以在 Dispatchers.Default 或 Main (如果极快)
                // 为了安全和一致性，如果涉及到Bitmap.createBitmap，放到IO或Default
                newTopCroppedBitmap = withContext(Dispatchers.Default) { // 使用Default进行CPU密集型操作
                    ensureActive()
                    SharedWallpaperRenderer.preparePage1TopCroppedBitmap(
                        sourceBitmap, viewWidth, viewHeight, newRatio
                    )
                }
                ensureActive()

                if (imageUri != null && wallpaperBitmaps != null) { // 确保在操作期间 imageUri 和主 bitmap 对象没变
                    wallpaperBitmaps?.page1TopCroppedBitmap?.recycle() // 回收旧的顶图
                    wallpaperBitmaps?.page1TopCroppedBitmap = newTopCroppedBitmap
                    Log.d(TAG, "Top cropped bitmap updated successfully.")
                } else {
                    Log.d(TAG, "State changed during top bitmap update. Discarding new top bitmap.")
                    newTopCroppedBitmap?.recycle() // 状态变了，丢弃
                }
            } catch (e: CancellationException) {
                exceptionOccurred = true
                Log.d(TAG, "Top bitmap update CANCELLED.")
                newTopCroppedBitmap?.recycle()
            } catch (e: Exception) {
                exceptionOccurred = true
                Log.e(TAG, "Error updating top cropped bitmap", e)
                newTopCroppedBitmap?.recycle()
                // 出错时，可以考虑将 wallpaperBitmaps.page1TopCroppedBitmap 置为 null
                // wallpaperBitmaps?.page1TopCroppedBitmap = null
            } finally {
                if (coroutineContext[Job] == topBitmapUpdateJob) {
                    topBitmapUpdateJob = null
                }
                if (imageUri != null) { // 只有在还有图片的情况下才重绘
                    invalidate()
                }
                Log.d(TAG, "Top bitmap update job finished. Exception: $exceptionOccurred")
            }
        }
    }

    // --- 滑动逻辑 (onTouchEvent, performClick, flingPage, snapToNearestPage, animateToOffset, computeScroll, getScrollRange, recycleVelocityTracker) ---
    // 这些方法与您满意的上一版本保持一致，这里不再重复，请确保从之前的版本复制过来。
    // 为了代码的完整性，我还是把它们粘贴过来：
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
        velocityTracker!!.addMovement(event)

        val action = event.actionMasked
        val x = event.x

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (!scroller.isFinished) scroller.abortAnimation()
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
                val currentX = event.getX(pointerIndex)
                val deltaX = lastTouchX - currentX

                if (!isBeingDragged && abs(currentX - downTouchX) > touchSlop) {
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
                    lastTouchX = currentX
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
                if (isBeingDragged) snapToNearestPage(currentPreviewXOffset)
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
            scroller.startScroll(currentPixelOffset, 0, dx, 0, SNAP_ANIMATION_DURATION_MS)
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

    private fun getScrollRange(): Int { return 10000 }
    private fun recycleVelocityTracker() { velocityTracker?.recycle(); velocityTracker = null }
}