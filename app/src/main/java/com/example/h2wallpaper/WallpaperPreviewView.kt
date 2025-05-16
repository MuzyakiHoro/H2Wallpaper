package com.example.h2wallpaper

import android.content.Context
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
import kotlin.coroutines.coroutineContext // 需要导入这个来访问 coroutineContext[Job]
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
    private var currentBitmapLoadingJob: Job? = null // 用于跟踪当前加载任务

    companion object {
        private const val SNAP_ANIMATION_DURATION_MS = 400 // 吸附动画时长
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val oldViewWidth = viewWidth
        val oldViewHeight = viewHeight
        viewWidth = w
        viewHeight = h
        Log.d(TAG, "onSizeChanged: $viewWidth x $viewHeight")

        // 只有当尺寸实际发生变化，或者首次获取到有效尺寸时才重新加载
        if (w > 0 && h > 0 && (w != oldViewWidth || h != oldViewHeight || wallpaperBitmaps == null)) {
            if (imageUri != null) {
                loadAndPrepareBitmaps(keepOldBitmapWhileLoading = wallpaperBitmaps != null && (w == oldViewWidth && h == oldViewHeight) )
            } else {
                invalidate() // 如果没有图片，仅重绘占位符
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (viewWidth <= 0 || viewHeight <= 0) return

        val currentBitmaps = wallpaperBitmaps
        // 确保至少有一个有效位图才尝试绘制，否则显示占位符
        if (currentBitmaps != null && (currentBitmaps.scrollingBackgroundBitmap != null || currentBitmaps.page1TopCroppedBitmap != null)) {
            SharedWallpaperRenderer.drawFrame(
                canvas,
                SharedWallpaperRenderer.WallpaperConfig(
                    screenWidth = viewWidth,
                    screenHeight = viewHeight,
                    page1BackgroundColor = selectedBackgroundColor,
                    page1ImageHeightRatio = page1ImageHeightRatio,
                    currentXOffset = currentPreviewXOffset,
                    numVirtualPages = numVirtualPages,
                    p1OverlayFadeTransitionRatio = p1OverlayFadeTransitionRatio
                ),
                currentBitmaps
            )
        } else {
            SharedWallpaperRenderer.drawPlaceholder(canvas, viewWidth, viewHeight,
                if (imageUri != null && currentBitmapLoadingJob?.isActive == true) "图片加载中..."
                else if (imageUri != null && wallpaperBitmaps == null) "加载失败或无图片" // 可以更具体
                else "请选择图片"
            )
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        currentBitmapLoadingJob?.cancel() // 取消正在进行的加载
        currentBitmapLoadingJob = null
        viewScope.cancel() // 取消整个作用域
        SharedWallpaperRenderer.recycleBitmaps(wallpaperBitmaps)
        wallpaperBitmaps = null
        Log.d(TAG, "onDetachedFromWindow: Cleaned up resources.")
    }

    fun setImageUri(uri: Uri?) {
        if (this.imageUri == uri && uri != null && wallpaperBitmaps != null) {
            invalidate()
            return
        }
        // URI 变化或从 null 变为非 null，或从非 null 变为 null
        currentBitmapLoadingJob?.cancel() // 取消任何正在进行的加载
        currentBitmapLoadingJob = null

        val oldBitmaps = wallpaperBitmaps // 保存旧位图引用
        wallpaperBitmaps = null        // 先置空以显示加载状态
        this.imageUri = uri
        currentPreviewXOffset = 0f     // 新图片重置到第一页
        if (!scroller.isFinished) scroller.abortAnimation()

        if (uri != null) {
            invalidate() // 立即重绘，显示占位符
            loadAndPrepareBitmaps(keepOldBitmapWhileLoading = false) // 强制重新加载
        } else {
            SharedWallpaperRenderer.recycleBitmaps(oldBitmaps) // 新URI为null，回收旧的
            invalidate() // 显示“请选择图片”
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
            this.page1ImageHeightRatio = clampedRatio
            if (imageUri != null) {
                loadAndPrepareBitmaps(keepOldBitmapWhileLoading = true)
            } else {
                invalidate()
            }
        }
    }

    private fun loadAndPrepareBitmaps(keepOldBitmapWhileLoading: Boolean = false) {
        val currentUriToLoad = imageUri
        if (currentUriToLoad == null || viewWidth <= 0 || viewHeight <= 0) {
            currentBitmapLoadingJob?.cancel()
            currentBitmapLoadingJob = null
            SharedWallpaperRenderer.recycleBitmaps(wallpaperBitmaps)
            wallpaperBitmaps = null
            invalidate()
            return
        }

        val oldBitmapsBeingReplaced = wallpaperBitmaps // 当前正在显示的位图

        if (!keepOldBitmapWhileLoading || oldBitmapsBeingReplaced == null) {
            currentBitmapLoadingJob?.cancel() // 取消之前的任务
            currentBitmapLoadingJob = null
            if (oldBitmapsBeingReplaced != null) SharedWallpaperRenderer.recycleBitmaps(oldBitmapsBeingReplaced)
            wallpaperBitmaps = null // 清除，准备显示加载占位符
            invalidate()
        }
        // 如果是 keepOldBitmapWhileLoading 且 oldBitmapsBeingReplaced 存在，则 onDraw 会继续用它

        currentBitmapLoadingJob?.cancel() // 确保取消任何可能仍在运行的旧任务
        currentBitmapLoadingJob = viewScope.launch {
            Log.d(TAG, "Starting bitmap preparation job. URI: $currentUriToLoad, KeepOld: $keepOldBitmapWhileLoading, Ratio: $page1ImageHeightRatio")
            var newBitmaps: SharedWallpaperRenderer.WallpaperBitmaps? = null
            var exceptionOccurred = false
            try {
                ensureActive() // 在开始IO操作前检查协程是否已被取消
                newBitmaps = withContext(Dispatchers.IO) {
                    ensureActive() // 在实际的耗时操作前再次检查
                    SharedWallpaperRenderer.prepareAllBitmaps(
                        context, currentUriToLoad, viewWidth, viewHeight,
                        page1ImageHeightRatio, numVirtualPages, 0f
                    )
                }
                ensureActive() // IO操作完成后再次检查

                // 只有当外部 imageUri 仍然是这次加载的 URI 时，才应用结果
                if (imageUri == currentUriToLoad) {
                    // 如果是保留旧位图模式，并且旧位图与新位图不同，则回收旧位图
                    if (keepOldBitmapWhileLoading && oldBitmapsBeingReplaced != null && oldBitmapsBeingReplaced != newBitmaps) {
                        SharedWallpaperRenderer.recycleBitmaps(oldBitmapsBeingReplaced)
                    } else if (!keepOldBitmapWhileLoading && oldBitmapsBeingReplaced != null && oldBitmapsBeingReplaced != newBitmaps){
                        // 如果不是保留模式，之前的旧位图（即使主引用已为null）也应被回收
                        SharedWallpaperRenderer.recycleBitmaps(oldBitmapsBeingReplaced)
                    }
                    wallpaperBitmaps = newBitmaps // 应用新位图
                    Log.d(TAG, "Bitmaps prepared successfully for $currentUriToLoad.")
                } else {
                    Log.d(TAG, "Image URI changed during prep. Discarding bitmaps for $currentUriToLoad.")
                    SharedWallpaperRenderer.recycleBitmaps(newBitmaps) // 回收为旧URI加载的位图
                    // 如果当前最新的 imageUri 是 null，确保 wallpaperBitmaps 也反映这一点
                    if (imageUri == null && wallpaperBitmaps != null) {
                        SharedWallpaperRenderer.recycleBitmaps(wallpaperBitmaps)
                        wallpaperBitmaps = null
                    }
                }
            } catch (e: CancellationException) {
                exceptionOccurred = true
                Log.d(TAG, "Bitmap loading job for $currentUriToLoad was cancelled.", e)
                SharedWallpaperRenderer.recycleBitmaps(newBitmaps) // 如果中途取消，回收可能已创建的
                // 如果是保留模式且旧位图存在，并且当前 wallpaperBitmaps 指向的不是旧位图了（比如被置null），则恢复
                if (keepOldBitmapWhileLoading && oldBitmapsBeingReplaced != null && wallpaperBitmaps != oldBitmapsBeingReplaced) {
                    wallpaperBitmaps = oldBitmapsBeingReplaced
                }
            } catch (e: Exception) {
                exceptionOccurred = true
                Log.e(TAG, "Error preparing bitmaps for $currentUriToLoad", e)
                SharedWallpaperRenderer.recycleBitmaps(newBitmaps) // 回收可能创建的
                // 出错时，如果之前是保留旧位图，则尝试恢复；否则清除
                wallpaperBitmaps = if (keepOldBitmapWhileLoading && oldBitmapsBeingReplaced != null) oldBitmapsBeingReplaced else null
            } finally {
                // 只有当此协程是当前最新的（未被后续调用取消）并且完成了（无论成功、失败或取消）才进行处理
                val amITheCurrentJob = coroutineContext[Job] == currentBitmapLoadingJob
                val isStillActiveOrJustCompleted = isActive || exceptionOccurred || coroutineContext[Job]?.isCompleted == true


                if (amITheCurrentJob && isStillActiveOrJustCompleted) {
                    currentBitmapLoadingJob = null // 清理 job 引用
                }


                // 最终的重绘，确保UI反映最新状态
                // 如果URI在加载过程中改变了，就不应该用旧URI的结果来重绘
                if (imageUri == currentUriToLoad || (imageUri == null && currentUriToLoad != null)) {
                    invalidate()
                }
                Log.d(TAG, "Bitmap loading job finished. Current job ref: $currentBitmapLoadingJob. URI for this job: $currentUriToLoad. Active URI: $imageUri")

            }
        }
    }

    // onTouchEvent 和其他滑动相关方法 (performClick, flingPage, snapToNearestPage, animateToOffset, computeScroll, getScrollRange, recycleVelocityTracker)
    // 与上一个版本（您满意的那个版本）保持一致，这里不再重复列出，请确保使用那些已经调整好的版本。
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

    private fun getScrollRange(): Int {
        return 10000
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }
}