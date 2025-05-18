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
import kotlin.coroutines.coroutineContext // 确保这个导入是正确的，通常不需要显式导入
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
    private var currentNormalizedFocusX: Float = 0.5f // 新增：P1前景图的归一化焦点X
    private var currentNormalizedFocusY: Float = 0.5f // 新增：P1前景图的归一化焦点Y

    // 持有 WallpaperBitmaps 对象
    private var wallpaperBitmaps: SharedWallpaperRenderer.WallpaperBitmaps? = null

    // --- 内部状态 ---
    private var viewWidth: Int = 0
    private var viewHeight: Int = 0
    private var currentPreviewXOffset: Float = 0f // 用于模拟页面滚动的偏移 (0.0 to 1.0)
    private val numVirtualPages: Int = 3 // 预览时模拟的虚拟页面数
    private val p1OverlayFadeTransitionRatio: Float = 0.2f // P1叠加层淡出过渡比例

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
    private var fullBitmapLoadingJob: Job? = null
    private var topBitmapUpdateJob: Job? = null

    companion object {
        private const val SNAP_ANIMATION_DURATION_MS = 700 // 页面吸附动画时长
        // 之前 MainActivity 中的常量，如果只在这里用，可以移过来
        // private const val DEFAULT_HEIGHT_RATIO = 1f / 3f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val oldViewWidth = viewWidth
        val oldViewHeight = viewHeight
        viewWidth = w
        viewHeight = h
        Log.d(TAG, "onSizeChanged: New $viewWidth x $viewHeight, Old $oldViewWidth x $oldViewHeight")

        if (w > 0 && h > 0) {
            // 当View尺寸变化时，如果已有图片，则重新加载所有位图以适应新尺寸
            if (imageUri != null && (w != oldViewWidth || h != oldViewHeight || wallpaperBitmaps?.sourceSampledBitmap == null)) {
                Log.d(TAG, "onSizeChanged: Triggering full bitmap reload due to size change or missing bitmaps.")
                loadFullBitmapsFromUri(this.imageUri)
            } else if (imageUri != null && wallpaperBitmaps?.sourceSampledBitmap != null && wallpaperBitmaps?.page1TopCroppedBitmap == null) {
                // 如果有源图但没有P1顶图（例如，焦点或高度刚改过，但完整加载被取消），则只更新P1顶图
                Log.d(TAG, "onSizeChanged: Source bitmap exists, but P1 top cropped is missing. Updating P1 top cropped.")
                updateOnlyPage1TopCroppedBitmap(this.page1ImageHeightRatio, wallpaperBitmaps!!.sourceSampledBitmap!!)
            } else {
                invalidate() // 其他情况，例如无图，或图已完好，仅重绘
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (viewWidth <= 0 || viewHeight <= 0) return

        val currentWpBitmaps = wallpaperBitmaps
        if (currentWpBitmaps != null && currentWpBitmaps.sourceSampledBitmap != null) {
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
                    // p2BackgroundFadeInRatio 和 scrollSensitivityFactor 可以在这里从Config或成员变量获取
                ),
                currentWpBitmaps // 传递包含所有位图的对象
            )
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
        viewScope.cancel() // 取消与此View关联的所有协程
        wallpaperBitmaps?.recycleInternals() // 回收所有内部Bitmap
        wallpaperBitmaps = null
    }

    fun setImageUri(uri: Uri?) {
        Log.d(TAG, "setImageUri called with new URI: $uri. Previous URI: ${this.imageUri}")

        // 如果 URI 相同且图片已加载，可能不需要做太多事，除非希望强制刷新
        if (this.imageUri == uri && uri != null && wallpaperBitmaps?.sourceSampledBitmap != null) {
            Log.d(TAG, "setImageUri: URI unchanged and source bitmap exists. Re-applying current focus and invalidating.")
            // 确保当前焦点被应用到P1图的生成上
            // MainActivity 在调用 setImageUri 前会先调用 setNormalizedFocus
            // 所以这里的 loadFullBitmapsFromUri (如果被调用) 或 updateOnlyPage1TopCroppedBitmap 会使用新焦点
            if (wallpaperBitmaps?.sourceSampledBitmap != null) {
                // 再次确认，如果仅URI相同，通常由外部通过setNormalizedFocus和setPage1ImageHeightRatio来触发更新
                // 这里主要是为了应对外部直接调用setImageUri(相同URI)的情况，确保刷新
                updateOnlyPage1TopCroppedBitmap(page1ImageHeightRatio, wallpaperBitmaps!!.sourceSampledBitmap!!)
            } else {
                invalidate()
            }
            return
        }

        // URI 变了，或之前无图
        fullBitmapLoadingJob?.cancel(); fullBitmapLoadingJob = null
        topBitmapUpdateJob?.cancel(); topBitmapUpdateJob = null

        wallpaperBitmaps?.recycleInternals() // 回收旧的位图
        wallpaperBitmaps = null // 先置空，以便显示加载状态

        this.imageUri = uri
        currentPreviewXOffset = 0f // 新图片，重置预览偏移
        if (!scroller.isFinished) scroller.abortAnimation()

        if (uri != null) {
            invalidate() // 立即重绘以显示“图片加载中...”或占位符
            loadFullBitmapsFromUri(uri) // loadFullBitmapsFromUri 内部会使用 this.currentNormalizedFocusX/Y
        } else {
            // URI 为 null，清除所有图片相关的状态
            Log.d(TAG, "setImageUri: URI is null. Clearing bitmaps and invalidating.")
            invalidate() // 重绘以显示“请选择图片”
        }
    }

    // 新增：设置P1前景图的归一化焦点
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
            } else if (imageUri != null) {
                Log.d(TAG, "Image URI exists but source bitmap is null. Full reload will use new focus if triggered by setImageUri/onSizeChanged.")
                // 可以考虑如果View已初始化，直接触发一次加载
                if (viewWidth > 0 && viewHeight > 0) {
                    loadFullBitmapsFromUri(this.imageUri)
                }
            }
            // invalidate() // updateOnlyPage1TopCroppedBitmap 或 loadFullBitmapsFromUri 内部会 invalidate
        } else {
            Log.d(TAG, "Normalized focus UNCHANGED.")
        }
    }

    private fun loadFullBitmapsFromUri(uriToLoad: Uri?) {
        if (uriToLoad == null || viewWidth <= 0 || viewHeight <= 0) {
            Log.w(TAG, "loadFullBitmapsFromUri: Invalid URI or view dimensions. URI: $uriToLoad, View: ${viewWidth}x$viewHeight")
            wallpaperBitmaps?.recycleInternals()
            wallpaperBitmaps = null
            invalidate()
            return
        }

        fullBitmapLoadingJob?.cancel()
        topBitmapUpdateJob?.cancel()

        Log.d(TAG, "loadFullBitmapsFromUri: Starting full bitmap load for URI: $uriToLoad. Focus: ($currentNormalizedFocusX, $currentNormalizedFocusY)")
        if (wallpaperBitmaps != null) { // 回收可能存在的旧位图，即使是同一个URI也重新加载
            wallpaperBitmaps?.recycleInternals()
            wallpaperBitmaps = null
        }
        invalidate() // 显示加载中

        fullBitmapLoadingJob = viewScope.launch {
            var newFullBitmaps: SharedWallpaperRenderer.WallpaperBitmaps? = null
            try {
                ensureActive() // 检查协程是否仍在活动
                newFullBitmaps = withContext(Dispatchers.IO) {
                    ensureActive()
                    SharedWallpaperRenderer.loadAndProcessInitialBitmaps(
                        context,
                        uriToLoad,
                        viewWidth,
                        viewHeight,
                        page1ImageHeightRatio,
                        currentNormalizedFocusX, // 传递当前焦点X
                        currentNormalizedFocusY, // 传递当前焦点Y
                        numVirtualPages, // 用于背景滚动图的页面数
                        SharedWallpaperRenderer.DEFAULT_BACKGROUND_BLUR_RADIUS // 预览时不模糊背景
                    )
                }
                ensureActive()

                // 检查URI在加载期间是否已更改
                if (this@WallpaperPreviewView.imageUri == uriToLoad) {
                    wallpaperBitmaps?.recycleInternals() // 回收旧的（理论上此时应为null或已在上面回收）
                    wallpaperBitmaps = newFullBitmaps
                    Log.d(TAG, "Full bitmaps successfully loaded and applied for $uriToLoad.")
                } else {
                    Log.d(TAG, "URI changed during full bitmap load for $uriToLoad. Current URI: ${this@WallpaperPreviewView.imageUri}. Discarding loaded bitmaps.")
                    newFullBitmaps?.recycleInternals() // URI变了，丢弃新加载的
                    if (this@WallpaperPreviewView.imageUri == null) { // 如果当前URI已变为null (例如用户清除了选择)
                        wallpaperBitmaps?.recycleInternals() // 确保清除当前持有的 (如果还有的话)
                        wallpaperBitmaps = null
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Full bitmap loading for $uriToLoad CANCELLED.")
                newFullBitmaps?.recycleInternals() // 回收部分加载的
            } catch (e: Exception) {
                Log.e(TAG, "Error in loadFullBitmapsFromUri for $uriToLoad", e)
                newFullBitmaps?.recycleInternals()
                // 出错时，确保清除 wallpaperBitmaps
                if (this@WallpaperPreviewView.imageUri == uriToLoad) { // 只有当URI没变时才清除，否则可能清除了新URI的图
                    wallpaperBitmaps?.recycleInternals()
                    wallpaperBitmaps = null
                }
            } finally {
                if (isActive && coroutineContext[Job] == fullBitmapLoadingJob) { // 清理自身job引用
                    fullBitmapLoadingJob = null
                }
                // 只有当URI与加载时一致，或者当前URI为null时（表示已清除），才重绘
                if (isActive && (this@WallpaperPreviewView.imageUri == uriToLoad || this@WallpaperPreviewView.imageUri == null)) {
                    invalidate()
                }
            }
        }
    }

    fun setSelectedBackgroundColor(color: Int) {
        if (this.selectedBackgroundColor != color) {
            this.selectedBackgroundColor = color
            invalidate() // 只需要重绘，不需要重新加载图片
        }
    }

    fun setPage1ImageHeightRatio(ratio: Float) {
        val clampedRatio = ratio.coerceIn(0.1f, 0.9f) // 限制比例范围
        if (this.page1ImageHeightRatio != clampedRatio) {
            val oldRatio = this.page1ImageHeightRatio
            this.page1ImageHeightRatio = clampedRatio
            Log.d(TAG, "setPage1ImageHeightRatio: Ratio changed from $oldRatio to $clampedRatio.")

            // 高度比例变化只影响 P1 顶图，如果源图已加载，则只更新P1顶图
            if (imageUri != null && wallpaperBitmaps?.sourceSampledBitmap != null) {
                Log.d(TAG, "Source bitmap exists, updating only P1 top cropped bitmap for height ratio change.")
                updateOnlyPage1TopCroppedBitmap(clampedRatio, wallpaperBitmaps!!.sourceSampledBitmap!!)
            } else if (imageUri != null) {
                // 源图不存在但有URI (例如之前加载失败或被回收)，则需要完整重新加载
                Log.d(TAG, "Source bitmap missing, triggering full reload for height ratio change.")
                loadFullBitmapsFromUri(this.imageUri)
            } else {
                // 没有图片，仅重绘 (例如，可能只显示背景色块)
                invalidate()
            }
        }
    }

    private fun updateOnlyPage1TopCroppedBitmap(newRatio: Float, sourceBitmap: Bitmap) {
        if (viewWidth <= 0 || viewHeight <= 0) {
            Log.w(TAG, "updateOnlyPage1TopCroppedBitmap: View not measured. Skipping update.")
            return
        }
        topBitmapUpdateJob?.cancel()
        // fullBitmapLoadingJob?.cancel() // 通常不需要，因为这是个更轻量的操作

        Log.d(TAG, "updateOnlyPage1TopCroppedBitmap: Updating P1 top cropped for ratio: $newRatio. Focus: ($currentNormalizedFocusX, $currentNormalizedFocusY)")

        topBitmapUpdateJob = viewScope.launch {
            var newTopCroppedBitmap: Bitmap? = null
            var exceptionOccurred = false
            try {
                ensureActive()
                newTopCroppedBitmap = withContext(Dispatchers.Default) {
                    ensureActive()
                    SharedWallpaperRenderer.preparePage1TopCroppedBitmap(
                        sourceBitmap,
                        viewWidth,
                        viewHeight,
                        newRatio,
                        currentNormalizedFocusX, // 使用当前焦点X
                        currentNormalizedFocusY  // 使用当前焦点Y
                    )
                }
                ensureActive()

                // 检查在异步操作期间状态是否已改变
                if (this@WallpaperPreviewView.imageUri != null && wallpaperBitmaps != null && wallpaperBitmaps?.sourceSampledBitmap == sourceBitmap) {
                    wallpaperBitmaps?.page1TopCroppedBitmap?.recycle() // 回收旧的P1顶图
                    wallpaperBitmaps?.page1TopCroppedBitmap = newTopCroppedBitmap
                    Log.d(TAG, "P1 top cropped bitmap updated successfully.")
                } else {
                    Log.d(TAG, "State changed during P1 top bitmap update. Discarding new top bitmap.")
                    newTopCroppedBitmap?.recycle()
                }
            } catch (e: CancellationException) {
                exceptionOccurred = true
                Log.d(TAG, "P1 top bitmap update CANCELLED.")
                newTopCroppedBitmap?.recycle()
            } catch (e: Exception) {
                exceptionOccurred = true
                Log.e(TAG, "Error updating P1 top cropped bitmap", e)
                newTopCroppedBitmap?.recycle()
                // 可以考虑将 wallpaperBitmaps.page1TopCroppedBitmap 置为 null 以触发占位符或重新加载
                // if (wallpaperBitmaps?.sourceSampledBitmap == sourceBitmap) wallpaperBitmaps?.page1TopCroppedBitmap = null
            } finally {
                if (isActive && coroutineContext[Job] == topBitmapUpdateJob) {
                    topBitmapUpdateJob = null
                }
                // 只有当URI和源图在操作开始时都存在的情况下才重绘，避免不必要的刷新
                if (isActive && this@WallpaperPreviewView.imageUri != null && wallpaperBitmaps?.sourceSampledBitmap == sourceBitmap) {
                    invalidate()
                }
                Log.d(TAG, "P1 top bitmap update job finished. Exception: $exceptionOccurred")
            }
        }
    }

    // --- 滑动逻辑 ---
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (imageUri == null && wallpaperBitmaps == null) { // 如果没有图片，不处理滑动
            return super.onTouchEvent(event)
        }

        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker!!.addMovement(event)

        val action = event.actionMasked
        val x = event.x // 当前事件的X坐标

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (!scroller.isFinished) {
                    scroller.abortAnimation() // 停止正在进行的滚动动画
                }
                lastTouchX = x
                downTouchX = x // 记录按下的初始X，用于判断是否是点击
                activePointerId = event.getPointerId(0)
                isBeingDragged = false
                parent?.requestDisallowInterceptTouchEvent(true) // 请求父View不要拦截事件
                return true // 我们要处理这个事件序列
            }
            MotionEvent.ACTION_MOVE -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) return false // 无效指针
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex < 0) return false // 找不到活动指针的索引

                val currentMoveX = event.getX(pointerIndex)
                val deltaX = lastTouchX - currentMoveX // 注意方向：向左滑deltaX为正

                if (!isBeingDragged && abs(currentMoveX - downTouchX) > touchSlop) {
                    isBeingDragged = true
                    parent?.requestDisallowInterceptTouchEvent(true) // 确认开始拖动，继续请求不拦截
                    // 如果有必要，可以在这里对 deltaX 做一个初始补偿，减去 touchSlop 的部分
                }

                if (isBeingDragged) {
                    // 计算预览偏移量的变化
                    // 假设总的可滚动宽度在概念上是 (numVirtualPages - 1) * viewWidth
                    // 那么每滚动一个viewWidth的距离，currentPreviewXOffset 应该变化 1.0 / (numVirtualPages - 1)
                    if (viewWidth > 0 && numVirtualPages > 1) {
                        val offsetPerViewWidthScroll = 1.0f / (numVirtualPages - 1).toFloat()
                        val scrollDeltaRatio = (deltaX / viewWidth.toFloat()) * offsetPerViewWidthScroll
                        currentPreviewXOffset = (currentPreviewXOffset + scrollDeltaRatio).coerceIn(0f, 1f)
                    } else {
                        currentPreviewXOffset = 0f // 如果只有一个页面或宽度为0，则无偏移
                    }
                    lastTouchX = currentMoveX
                    invalidate() // 重绘以反映新的偏移
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) return false
                if (isBeingDragged) {
                    val vt = velocityTracker!!
                    vt.computeCurrentVelocity(1000, maxFlingVelocity.toFloat()) // 计算1秒内的速度
                    val velocityX = vt.getXVelocity(activePointerId)

                    if (abs(velocityX) > minFlingVelocity && numVirtualPages > 1) {
                        flingPage(velocityX) // 根据速度fling
                    } else {
                        snapToNearestPage(currentPreviewXOffset) // 吸附到最近的页面
                    }
                } else {
                    // 如果不是拖动，且移动距离小于touchSlop，则认为是点击
                    if (abs(x - downTouchX) < touchSlop) {
                        performClick()
                    } else {
                        // 如果移动超过touchSlop但未形成fling，也吸附
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
                    snapToNearestPage(currentPreviewXOffset) // 取消时也吸附
                }
                recycleVelocityTracker()
                activePointerId = MotionEvent.INVALID_POINTER_ID
                isBeingDragged = false
                return true
            }
        }
        return super.onTouchEvent(event) // 对于未处理的事件，调用父类
    }

    override fun performClick(): Boolean {
        super.performClick()
        // 在这里处理点击事件，例如切换UI控件的可见性
        Log.d(TAG, "performClick called on WallpaperPreviewView")
        // 你可以将 MainActivity 中的点击逻辑移到这里，或者通过回调通知 MainActivity
        return true
    }

    private fun flingPage(velocityX: Float) {
        if (numVirtualPages <= 1) {
            animateToOffset(0f) // 单页或无页，回到0
            return
        }

        // 计算当前大致在哪个页面索引（浮点数）
        val currentEffectivePageIndex = currentPreviewXOffset * (numVirtualPages - 1)
        var targetPageIndex: Int

        // 根据速度方向确定目标页面
        // 向左滑 (内容向左移动, xOffset 增大), velocityX 为负
        if (velocityX < -minFlingVelocity) { // 负速度，向右翻页（预览内容向左移动）
            targetPageIndex = ceil(currentEffectivePageIndex).toInt()
            // 如果已经很接近目标页，并且不是最后一页，则翻到下一页
            if (targetPageIndex <= currentEffectivePageIndex + 0.05f && targetPageIndex < numVirtualPages - 1) {
                targetPageIndex++
            }
        } else if (velocityX > minFlingVelocity) { // 正速度，向左翻页（预览内容向右移动）
            targetPageIndex = floor(currentEffectivePageIndex).toInt()
            if (targetPageIndex >= currentEffectivePageIndex - 0.05f && targetPageIndex > 0) {
                targetPageIndex--
            }
        } else {
            // 速度不够，按当前位置吸附
            snapToNearestPage(currentPreviewXOffset)
            return
        }

        targetPageIndex = targetPageIndex.coerceIn(0, numVirtualPages - 1) // 确保目标页在范围内
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
        // 将0-1的偏移量转换为像素级别的滚动，以便OverScroller使用
        // getScrollRange() 可以返回一个较大的虚拟滚动范围，例如 viewWidth * (numVirtualPages -1)
        // 但为了简化，我们可以用一个固定的较大值，然后在computeScroll中转换回来
        val currentPixelOffset = (currentPreviewXOffset * getScrollRange()).toInt()
        val targetPixelOffset = (targetXOffset * getScrollRange()).toInt()
        val dx = targetPixelOffset - currentPixelOffset

        if (dx != 0) {
            scroller.startScroll(currentPixelOffset, 0, dx, 0, SNAP_ANIMATION_DURATION_MS)
            postInvalidateOnAnimation() // 触发computeScroll
        } else {
            // 如果已经在目标位置，确保偏移精确并重绘
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
            invalidate() // 持续重绘直到滚动结束
        }
    }

    // 定义一个虚拟的滚动范围，用于OverScroller计算。
    // 它可以是任意值，只要在animateToOffset和computeScroll中保持一致即可。
    private fun getScrollRange(): Int {
        // return viewWidth * (numVirtualPages -1) // 如果viewWidth有效
        return 10000 // 一个足够大的固定值
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }
}