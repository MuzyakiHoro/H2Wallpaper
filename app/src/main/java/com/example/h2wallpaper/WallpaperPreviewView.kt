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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt // 确保这个导入存在

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
    // currentPreviewXOffset: 0.0 (第一页最左端，内容完全显示第一页的开始)
    // 到 1.0 (最后一页最右端，内容完全显示最后一页的末尾，即背景图滚动到最大偏移)
    private var currentPreviewXOffset: Float = 0f
    private val numVirtualPages: Int = 3 // 固定为3页预览
    private val p1OverlayFadeTransitionRatio: Float = 0.2f

    // --- 用于处理滑动和惯性滚动 ---
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
    //动画平滑速度
    private  val SNAP_ANIMATION_DURATION_MS = 700 // 默认半秒，可调整

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h
        Log.d(TAG, "onSizeChanged: $viewWidth x $viewHeight")
        if (imageUri != null) {
            loadAndPrepareBitmaps()
        } else {
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (viewWidth <= 0 || viewHeight <= 0) return

        val currentBitmaps = wallpaperBitmaps
        if (currentBitmaps != null) {
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
            SharedWallpaperRenderer.drawPlaceholder(canvas, viewWidth, viewHeight, "请选择图片或加载中...")
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        viewScope.cancel()
        SharedWallpaperRenderer.recycleBitmaps(wallpaperBitmaps)
        wallpaperBitmaps = null
    }

    fun setImageUri(uri: Uri?) {
        if (this.imageUri == uri && wallpaperBitmaps != null && uri != null) {
            invalidate() // 即使URI相同，也可能需要因为其他状态（如颜色）变化而重绘
            return
        }
        SharedWallpaperRenderer.recycleBitmaps(wallpaperBitmaps)
        wallpaperBitmaps = null
        this.imageUri = uri
        currentPreviewXOffset = 0f // 新图片，重置到第一页
        scroller.abortAnimation() // 停止任何正在进行的滚动

        if (uri != null) {
            loadAndPrepareBitmaps()
        } else {
            invalidate()
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
                // 高度比例变化会影响 page1TopCroppedBitmap，需要重新准备
                loadAndPrepareBitmaps()
            } else {
                invalidate()
            }
        }
    }

    private fun loadAndPrepareBitmaps() {
        val currentUri = imageUri
        if (currentUri == null || viewWidth <= 0 || viewHeight <= 0) {
            SharedWallpaperRenderer.recycleBitmaps(wallpaperBitmaps)
            wallpaperBitmaps = null
            invalidate()
            return
        }

        SharedWallpaperRenderer.recycleBitmaps(wallpaperBitmaps)
        wallpaperBitmaps = null
        invalidate() // 显示加载占位符

        viewScope.launch {
            try {
                val preparedBitmaps = withContext(Dispatchers.IO) {
                    SharedWallpaperRenderer.prepareAllBitmaps(
                        context,
                        currentUri,
                        viewWidth,
                        viewHeight,
                        page1ImageHeightRatio,
                        numVirtualPages,
                        0f // 预览时不模糊以提高性能
                    )
                }
                if (imageUri == currentUri) { // 确保在加载过程中URI没变
                    wallpaperBitmaps = preparedBitmaps
                } else {
                    SharedWallpaperRenderer.recycleBitmaps(preparedBitmaps) // URI变了，丢弃结果
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error preparing bitmaps", e)
                wallpaperBitmaps = null // 确保异常时为null
            } finally {
                if (imageUri == currentUri) invalidate() // 无论成功失败都尝试重绘
            }
        }
    }

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
                val deltaX = lastTouchX - currentX // 手指向左，内容向右，offset增加

                if (!isBeingDragged && abs(currentX - downTouchX) > touchSlop) {
                    isBeingDragged = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }

                if (isBeingDragged) {
                    if (viewWidth > 0 && numVirtualPages > 1) {
                        // 手指滑动一个 viewWidth 的距离，currentPreviewXOffset 应该变化 1.0f / (numVirtualPages - 1)
                        // 这样滑动 (numVirtualPages - 1) 个 viewWidth 的距离，offset 从 0 到 1
                        val offsetPerViewWidthScroll = 1.0f / (numVirtualPages - 1).toFloat()
                        val scrollDeltaRatio = (deltaX / viewWidth.toFloat()) * offsetPerViewWidthScroll
                        currentPreviewXOffset = (currentPreviewXOffset + scrollDeltaRatio).coerceIn(0f, 1f)
                    } else {
                        currentPreviewXOffset = 0f // 只有一页或视图宽度为0，不滚动
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
                        snapToNearestPage(currentPreviewXOffset) // 轻微滑动也吸附
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
        // Log.d(TAG, "performClick called on WallpaperPreviewView")
        return true
    }

    private fun flingPage(velocityX: Float) {
        if (numVirtualPages <= 1) {
            animateToOffset(0f)
            return
        }

        // 当前逻辑页面索引 (0, 1, ..., numVirtualPages-1)
        // currentPreviewXOffset (0-1) 对应 (numVirtualPages-1) 个可滚动页面宽度
        // 因此，每个逻辑页面的起始 offset 是 pageIndex / (numVirtualPages-1)
        val currentEffectivePageIndex = currentPreviewXOffset * (numVirtualPages - 1)
        var targetPageIndex: Int

        if (velocityX < -minFlingVelocity) { // 向左快速滑动 (手指向左，内容右移，看下一页)
            targetPageIndex = ceil(currentEffectivePageIndex).toInt()
            // 如果当前已经非常接近或超过了 ceil 的结果 (意味着已经在向右移动或刚过那个点)，
            // 并且不是最后一页，那么目标应该是下一个页面
            if (targetPageIndex <= currentEffectivePageIndex + 0.05f && targetPageIndex < numVirtualPages - 1) { // 加一点容差
                targetPageIndex++
            }
        } else if (velocityX > minFlingVelocity) { // 向右快速滑动 (手指向右，内容左移，看上一页)
            targetPageIndex = floor(currentEffectivePageIndex).toInt()
            // 如果当前非常接近或小于 floor 的结果 (意味着已经在向左移动或刚过那个点)，
            // 并且不是第一页，那么目标应该是上一个页面
            if (targetPageIndex >= currentEffectivePageIndex - 0.05f && targetPageIndex > 0) { // 加一点容差
                targetPageIndex--
            }
        } else {
            snapToNearestPage(currentPreviewXOffset)
            return
        }

        targetPageIndex = targetPageIndex.coerceIn(0, numVirtualPages - 1)
        val targetXOffset = targetPageIndex.toFloat() / (numVirtualPages - 1).toFloat()
        animateToOffset(targetXOffset)
    }

    private fun snapToNearestPage(currentOffset: Float) {
        if (numVirtualPages <= 1) {
            animateToOffset(0f)
            return
        }

        // 将 currentOffset (0-1) 映射到页面索引 (0 to N-1)
        // 每个逻辑页面的理想起始 xOffset: 0, 1/(N-1), 2/(N-1), ..., (N-1)/(N-1)=1
        val pageIndexFloat = currentOffset * (numVirtualPages - 1)
        val targetPageIndex = pageIndexFloat.roundToInt().coerceIn(0, numVirtualPages - 1)

        val targetXOffset = targetPageIndex.toFloat() / (numVirtualPages - 1).toFloat()
        animateToOffset(targetXOffset)
    }

    private fun animateToOffset(targetXOffset: Float) {
        val currentPixelOffset = (currentPreviewXOffset * getScrollRange()).toInt()
        val targetPixelOffset = (targetXOffset * getScrollRange()).toInt()
        val dx = targetPixelOffset - currentPixelOffset

        if (dx != 0) {
            scroller.startScroll(currentPixelOffset, 0, dx, 0, SNAP_ANIMATION_DURATION_MS ) // 动画时长
            postInvalidateOnAnimation()
        } else {
            // 确保如果已经在目标位置，currentPreviewXOffset 是精确的
            this.currentPreviewXOffset = targetXOffset.coerceIn(0f,1f) // 再次确保范围
            invalidate() // 以防万一需要重绘（比如之前由于浮点数不精确导致没完全对齐）
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
            invalidate() // 这里用 invalidate() 也可以，因为 computeScroll 内部会处理动画的连续性
            // postInvalidateOnAnimation() // 也可以用这个
        }
    }

    private fun getScrollRange(): Int {
        return 10000 // 虚拟滚动范围，用于 OverScroller 的整数计算
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }
}