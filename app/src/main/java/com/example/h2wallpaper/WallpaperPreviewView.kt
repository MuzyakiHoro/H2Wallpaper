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
import android.os.SystemClock
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

import kotlinx.coroutines.cancel

/**
 * 自定义 View，用于在 MainActivity 中实时预览动态壁纸的效果。
 * 支持模拟壁纸的横向滚动、P1/P2 页面的过渡动画，
 * 并且实现了 P1 图片的编辑模式，允许用户通过手势调整 P1 图片的显示区域（焦点）、
 * 缩放大小以及 P1 区域本身的高度。
 *
 * @constructor 创建 WallpaperPreviewView 实例。
 * @param context View 的上下文。
 * @param attrs 从 XML 布局传递的属性集。
 * @param defStyleAttr 默认样式属性。
 */
class WallpaperPreviewView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs) {

    private val TAG = "WallpaperPreviewView" // 日志标签
    private enum class BlurTarget {
        P2_BACKGROUND, // 用于P2层全局背景模糊
        STYLE_B_P1_MASK // 用于Style B的P1遮罩背景模糊
    }
    // --- 可配置状态 (通常由 ViewModel 或外部设置) ---
    /** 当前显示的图片 URI */
    private var imageUri: Uri? = null

    /** P1 层底部（图片未覆盖区域）的背景颜色 */
    private var selectedBackgroundColor: Int = Color.LTGRAY

    /** 非编辑模式下，P1 图片区域高度与 View 高度的比例 */
    var nonEditModePage1ImageHeightRatio: Float = WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO

    /** 当前 P1 图片内容的归一化焦点 X 坐标 (0.0 - 1.0) */
    var currentNormalizedFocusX: Float = WallpaperConfigConstants.DEFAULT_P1_FOCUS_X

    /** 当前 P1 图片内容的归一化焦点 Y 坐标 (0.0 - 1.0) */
    var currentNormalizedFocusY: Float = WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y

    /** 当前 P1 图片内容的缩放因子 (相对于基础填充 P1 区域的缩放) */
    var currentP1ContentScaleFactor: Float =
        WallpaperConfigConstants.DEFAULT_P1_CONTENT_SCALE_FACTOR

    // --- 高级渲染参数 ---
    /** 背景滚动灵敏度因子 */
    private var currentScrollSensitivity: Float =
        WallpaperConfigConstants.DEFAULT_SCROLL_SENSITIVITY

    /** P1 层前景在滚动时的淡出过渡比例 */
    private var currentP1OverlayFadeRatio: Float =
        WallpaperConfigConstants.DEFAULT_P1_OVERLAY_FADE_RATIO

    /** P2 层背景在滚动时的淡入过渡比例 */
    private var currentP2BackgroundFadeInRatio: Float =
        WallpaperConfigConstants.DEFAULT_P2_BACKGROUND_FADE_IN_RATIO

    /** P2 层背景的模糊半径 */
    private var currentBackgroundBlurRadius: Float =
        WallpaperConfigConstants.DEFAULT_BACKGROUND_BLUR_RADIUS

    /** P2 层背景模糊处理时的降采样因子 (0.01 - 1.0) */
    private var currentBlurDownscaleFactor: Float =
        WallpaperConfigConstants.DEFAULT_BLUR_DOWNSCALE_FACTOR_INT / 100.0f

    /** P2 层背景模糊处理的迭代次数 */
    private var currentBlurIterations: Int = WallpaperConfigConstants.DEFAULT_BLUR_ITERATIONS

    /** 预览视图中页面吸附动画的时长 (毫秒) */
    private var currentSnapAnimationDurationMs: Long =
        WallpaperConfigConstants.DEFAULT_PREVIEW_SNAP_DURATION_MS

    /** P2 背景层在第一页的归一化初始横向偏移量 */
    private var currentNormalizedInitialBgScrollOffset: Float =
        WallpaperConfigConstants.DEFAULT_BACKGROUND_INITIAL_OFFSET

    /** P1 层图片的投影半径 */
    private var currentP1ShadowRadius: Float = WallpaperConfigConstants.DEFAULT_P1_SHADOW_RADIUS

    /** P1 层图片的投影在 X 轴上的偏移量 */
    private var currentP1ShadowDx: Float = WallpaperConfigConstants.DEFAULT_P1_SHADOW_DX

    /** P1 层图片的投影在 Y 轴上的偏移量 */
    private var currentP1ShadowDy: Float = WallpaperConfigConstants.DEFAULT_P1_SHADOW_DY

    /** P1 层图片的投影颜色 */
    private var currentP1ShadowColor: Int = WallpaperConfigConstants.DEFAULT_P1_SHADOW_COLOR

    /** P1 层图片底部融入背景的渐变高度 */
    private var currentP1ImageBottomFadeHeight: Float =
        WallpaperConfigConstants.DEFAULT_P1_IMAGE_BOTTOM_FADE_HEIGHT

    // --- 新增 P1 风格参数 ---
    private var currentP1StyleType: Int = WallpaperConfigConstants.DEFAULT_P1_STYLE_TYPE
    private var currentStyleBMaskAlpha: Float = WallpaperConfigConstants.DEFAULT_STYLE_B_MASK_ALPHA
    private var currentStyleBRotationParamA: Float = WallpaperConfigConstants.DEFAULT_STYLE_B_ROTATION_PARAM_A
    private var currentStyleBGapSizeRatio: Float = WallpaperConfigConstants.DEFAULT_STYLE_B_GAP_SIZE_RATIO
    private var currentStyleBGapPositionYRatio: Float = WallpaperConfigConstants.DEFAULT_STYLE_B_GAP_POSITION_Y_RATIO
    private var currentStyleBUpperMaskMaxRotation: Float = WallpaperConfigConstants.DEFAULT_STYLE_B_UPPER_MASK_MAX_ROTATION
    private var currentStyleBLowerMaskMaxRotation: Float = WallpaperConfigConstants.DEFAULT_STYLE_B_LOWER_MASK_MAX_ROTATION
    private var currentStyleBP1FocusX: Float = WallpaperConfigConstants.DEFAULT_STYLE_B_P1_FOCUS_X
    private var currentStyleBP1FocusY: Float = WallpaperConfigConstants.DEFAULT_STYLE_B_P1_FOCUS_Y
    private var currentStyleBP1ScaleFactor: Float = WallpaperConfigConstants.DEFAULT_STYLE_B_P1_SCALE_FACTOR
    // --- 结束新增 P1 风格参数 ---
    private var currentStyleBMasksHorizontallyFlipped: Boolean = WallpaperConfigConstants.DEFAULT_STYLE_B_MASKS_HORIZONTALLY_FLIPPED
    // --- 新增：样式 B P1 遮罩专属模糊参数的成员变量 ---
    private var currentStyleBP1MaskBlurRadius: Float = WallpaperConfigConstants.DEFAULT_STYLE_B_BLUR_RADIUS
    private var currentStyleBP1MaskBlurDownscale: Float = WallpaperConfigConstants.DEFAULT_STYLE_B_BLUR_DOWNSCALE_FACTOR
    private var currentStyleBP1MaskBlurIterations: Int = WallpaperConfigConstants.DEFAULT_STYLE_B_BLUR_ITERATIONS

    /** 持有渲染所需的各种位图资源 */
    private var wallpaperBitmaps: SharedWallpaperRenderer.WallpaperBitmaps? = null

    // --- 内部状态 ---
    /** View 的当前宽度 */
    private var viewWidth: Int = 0

    /** View 的当前高度 */
    private var viewHeight: Int = 0

    /** 当前预览的横向滚动偏移量 (0.0 代表第一页最左边, 1.0 代表最后一页最右边) */
    private var currentPreviewXOffset: Float = 0f

    /** 预览时模拟的虚拟页面数量 */
    private val numVirtualPages: Int = 3 // 预览固定为3页，用于展示滚动效果

    // --- 滑动和惯性滚动 ---
    /** 用于跟踪触摸速度以实现惯性滚动 */
    private var velocityTracker: VelocityTracker? = null

    /** 用于处理页面整体横向滚动的 OverScroller */
    private lateinit var pageScroller: OverScroller

    /** 用于处理 P1 内容在编辑模式下拖拽后的惯性滚动 */
    private lateinit var p1ContentScroller: OverScroller

    /** P1 内容 Scroller 上一次的 X 坐标 (用于计算增量) */
    private var lastP1ScrollerX: Int = 0

    /** P1 内容 Scroller 上一次的 Y 坐标 (用于计算增量) */
    private var lastP1ScrollerY: Int = 0

    /** 上一次触摸事件的 X 坐标 (用于页面滑动) */
    private var lastTouchX: Float = 0f

    /** ACTION_DOWN 事件时的 X 坐标 (用于判断是否为点击或滑动) */
    private var downTouchX: Float = 0f

    /** 标记当前是否正在进行页面横向滑动 */
    private var isPageSwiping: Boolean = false

    /** 系统定义的触摸移动阈值，超过此值才视为滑动 */
    private val touchSlop: Int

    /** 系统定义的最小滑动速度，超过此速度才视为 Fling */
    private val minFlingVelocity: Int

    /** 系统定义的最大滑动速度 */
    private val maxFlingVelocity: Int

    /** 当前活动触摸点的 ID */
    private var activePointerId: Int = MotionEvent.INVALID_POINTER_ID

    /** View 级别的协程作用域，用于管理异步任务 (如图片加载、模糊处理) */
    private val viewScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** 当前正在执行的完整位图加载任务 */
    private var fullBitmapLoadingJob: Job? = null

    /** 当前正在执行的 P1 顶部裁剪图更新任务 */
    private var topBitmapUpdateJob: Job? = null

    /** (已废弃，被 blurTaskProcessor 替代) 用于执行单个模糊任务的 Job */
    private var blurUpdateJob: Job? = null // 用于执行单个模糊任务

    // --- 智能模糊任务调度系统 ---
    /** 模糊任务队列，用于缓存待处理的模糊请求 */
    private val blurTaskQueue = Channel<BlurTask>(Channel.UNLIMITED)

    /** 处理模糊任务队列的协程 Job */
    private var blurTaskProcessor: Job? = null
    /**用于样式B P1遮罩模糊更新的Job跟踪器**/
    private var styleBBlurUpdateJob: Job? = null //

    /** 上一个模糊任务的开始时间戳，用于判断任务是否处理过慢 */
    private var lastBlurTaskStartTime = 0L

    /** 模糊任务处理时间的阈值 (毫秒)，超过此值视为慢任务，可能触发优化策略 */
    private val BLUR_TASK_TIME_THRESHOLD = 30L

    /**
     * 数据类，定义一个模糊处理任务。
     * @property radius 模糊半径。
     * @property downscaleFactor 降采样因子。
     * @property iterations 模糊迭代次数。
     * @property timestamp 任务创建时的时间戳，用于处理任务队列中的优先级或丢弃旧任务。
     */
    private data class BlurTask(
        val target: BlurTarget,
        val radius: Float,
        val downscaleFactor: Float,
        val iterations: Int,
        val timestamp: Long = System.currentTimeMillis()
    )

    // --- P1 编辑模式相关 ---
    /** 标记当前是否处于 P1 图片编辑模式 */
    private var isInP1EditMode: Boolean = false

    /** P1 编辑模式下，用于图片变换（平移、缩放）的矩阵 */
    private val p1EditMatrix = Matrix()

    /** P1 编辑模式下，P1 图片区域高度与 View 高度的比例 */
    private var currentEditP1HeightRatio: Float = WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO

    /** P1 编辑模式下，P1 图片内容的缩放因子 */
    private var currentEditP1ContentScaleFactor: Float =
        WallpaperConfigConstants.DEFAULT_P1_CONTENT_SCALE_FACTOR

    /** P1 图片在 View 中的实际显示区域 (矩形坐标) */
    private val p1DisplayRectView = RectF()

    /** 用于检测 P1 内容拖动手势的 GestureDetector */
    private lateinit var p1ContentDragGestureDetector: GestureDetector

    /** 用于检测 P1 内容缩放手势的 ScaleGestureDetector */
    private lateinit var p1ContentScaleGestureDetector: ScaleGestureDetector

    /** 标记当前是否正在通过手势调整 P1 区域的高度 */
    private var isP1HeightResizing: Boolean = false

    /** 开始调整 P1 高度时，触摸点的原始 Y 坐标 (屏幕坐标) */
    private var p1HeightResizeStartRawY: Float = 0f

    /** 开始调整 P1 高度时，P1 的高度比例 */
    private var p1HeightResizeStartRatio: Float = 0f

    /** P1 高度调整手柄的绘制 Paint */
    private val p1HeightResizeHandlePaint = Paint().apply {
        color = Color.argb(200, 255, 223, 0) // 黄色，半透明
        style = Paint.Style.FILL
    }

    /** P1 高度调整手柄的矩形区域 */
    private val p1HeightResizeHandleRect = RectF()

    /** P1 高度调整手柄的触摸区域的敏感度因子 (相对于 touchSlop) */
    private val p1HeightResizeTouchSlopFactor = 2.5f

    /** P1 编辑模式下，用户允许的最小内容缩放因子 (相对于刚好填满 P1 区域的缩放) */
    private var p1UserMinScaleFactorRelativeToCover = 1.0f

    /** P1 编辑模式下，用户允许的最大内容缩放因子 (相对于刚好填满 P1 区域的缩放) */
    private var p1UserMaxScaleFactorRelativeToCover = 4.0f

    // --- P1 配置更新节流相关 ---
    /** UI 主线程的 Handler，用于延迟执行任务 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** P1 配置更新的节流时间间隔 (毫秒) */
    private val P1_CONFIG_UPDATE_THROTTLE_MS = 150L

    /** 上一次 P1 配置更新的时间戳 */
    private var lastP1ConfigUpdateTime: Long = 0L

    /** 标记是否有一个被节流的 P1 配置更新任务等待执行 */
    private var isThrottledP1ConfigUpdatePending: Boolean = false

    /** 用于延迟执行 P1 配置更新的 Runnable */
    private var throttledP1ConfigUpdateRunnable: Runnable? = null

    /** 当 P1 配置 (焦点、高度、缩放) 在编辑模式下发生改变时的回调监听器 */
    private var onP1ConfigEditedListener: ((normalizedX: Float, normalizedY: Float, heightRatio: Float, contentScale: Float) -> Unit)? =
        null

    /** 当 PreviewView 需要请求外部执行某个动作时的回调监听器 (例如，请求取消 P1 编辑模式) */
    private var onRequestActionCallback: ((action: PreviewViewAction) -> Unit)? = null

    /**
     * 定义 PreviewView 可能请求外部执行的动作类型。
     */
    enum class PreviewViewAction {
        /** 请求取消当前的 P1 编辑模式 */
        REQUEST_CANCEL_P1_EDIT_MODE
    }

    /** P1 编辑模式下绘制图片内容的 Paint */
    private val p1EditContentPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** P1 编辑模式下绘制 P1 区域边框的 Paint */
    private val p1EditBorderPaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density // 3dp 边框宽度
        alpha = 220 // 轻微透明
        // 虚线效果
        pathEffect = DashPathEffect(
            floatArrayOf(
                8f * resources.displayMetrics.density,
                4f * resources.displayMetrics.density
            ), 0f
        )
    }

    /** 用于在 P1 编辑模式下，绘制 P1 图片区域下方纯色背景的 Paint */
    private val p1OverlayBgPaint = Paint()

    // --- 修复从编辑模式退出时的闪烁问题 ---
    /** 标记当前是否正处于从 P1 编辑模式退出的过渡状态 */
    private var isTransitioningFromEditMode: Boolean = false

    /** 在从 P1 编辑模式退出过渡期间，用于存储 P1 编辑矩阵的副本，以实现平滑过渡效果 */
    private val transitionMatrix = Matrix()

    /**
     * 初始化块。
     * 获取系统配置的触摸参数，并初始化 Scroller 和手势检测器。
     */
    init {
        val viewConfig = ViewConfiguration.get(context)
        touchSlop = viewConfig.scaledTouchSlop
        minFlingVelocity = viewConfig.scaledMinimumFlingVelocity
        maxFlingVelocity = viewConfig.scaledMaximumFlingVelocity
        pageScroller = OverScroller(context)
        p1ContentScroller = OverScroller(context)
        initializeP1GestureDetectors() // 初始化 P1 编辑模式的手势检测器
    }

    /**
     * 初始化 P1 内容拖动和缩放的手势检测器。
     * 这些检测器仅在 P1 编辑模式下生效。
     */
    private fun initializeP1GestureDetectors() {
        p1ContentDragGestureDetector = GestureDetector(context, P1ContentGestureListener())
        p1ContentScaleGestureDetector = ScaleGestureDetector(context, P1ContentScaleListener())
    }

    /**
     * 设置 P1 配置编辑监听器。
     * 当 P1 图片在编辑模式下的焦点、高度比例或内容缩放比例通过手势发生变化，
     * 并且经过节流处理后，会通过此监听器回调通知外部 (通常是 ViewModel)。
     *
     * @param listener 回调函数，参数为：归一化X焦点, 归一化Y焦点, 高度比例, 内容缩放比例。
     * 如果传入 null，则移除现有监听器。
     */
    fun setOnP1ConfigEditedListener(listener: ((normalizedX: Float, normalizedY: Float, heightRatio: Float, contentScale: Float) -> Unit)?) {
        this.onP1ConfigEditedListener = listener
    }

    /**
     * 设置请求动作回调监听器。
     * 用于当 PreviewView 内部发生某些事件，需要通知外部组件 (如 Activity 或 Fragment)
     * 执行特定操作时 (例如，由于某种原因无法进入 P1 编辑模式，请求外部取消该模式)。
     *
     * @param callback 回调函数，参数为请求的动作类型 [PreviewViewAction]。
     * 如果传入 null，则移除现有监听器。
     */
    fun setOnRequestActionCallback(callback: ((action: PreviewViewAction) -> Unit)?) {
        this.onRequestActionCallback = callback
    }

    /**
     * 获取当前有效的 P1 图片区域高度比例。
     * 如果处于 P1 编辑模式或从编辑模式过渡中，则返回编辑模式下的高度比例；否则返回非编辑模式下的高度比例。
     */
    private val currentEffectiveP1HeightRatio: Float
        get() = if (isInP1EditMode || isTransitioningFromEditMode) currentEditP1HeightRatio else nonEditModePage1ImageHeightRatio

    /**
     * 获取当前有效的 P1 图片内容缩放因子。
     * 如果处于 P1 编辑模式或从编辑模式过渡中，则返回编辑模式下的内容缩放因子；否则返回非编辑模式下的缩放因子。
     */
    private val currentEffectiveP1ContentScaleFactor: Float
        get() = if (isInP1EditMode || isTransitioningFromEditMode) currentEditP1ContentScaleFactor else this.currentP1ContentScaleFactor


    /**
     * 当 View 的尺寸发生变化时被调用。
     * 在这里重新计算 P1 显示区域，并根据情况加载或更新位图资源。
     * @param w 新的宽度。
     * @param h 新的高度。
     * @param oldw 旧的宽度。
     * @param oldh 旧的高度。
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val oldViewWidth = viewWidth;
        val oldViewHeight = viewHeight
        viewWidth = w; viewHeight = h
        Log.d(
            TAG,
            "onSizeChanged: New $viewWidth x $viewHeight. EditMode: $isInP1EditMode, Transitioning: $isTransitioningFromEditMode"
        )

        if (w > 0 && h > 0) {
            // 重新计算P1的显示区域和高度调整手柄的位置
            calculateP1DisplayRectView()

            // 如果在P1编辑模式下且已有采样图，则根据当前焦点重置编辑矩阵
            if (isInP1EditMode && wallpaperBitmaps?.sourceSampledBitmap != null) {
                resetP1EditMatrixToFocus(this.currentNormalizedFocusX, this.currentNormalizedFocusY)
            }

            // 如果有图片URI，并且View尺寸变化或采样图为空，则加载所有位图
            if (imageUri != null && (w != oldViewWidth || h != oldViewHeight || wallpaperBitmaps?.sourceSampledBitmap == null)) {
                loadFullBitmapsFromUri(this.imageUri, true) // forceInternalReload = true
            }
            // 如果有图片URI和采样图，但P1顶图为空，且不在编辑/过渡模式，则只更新P1顶图
            else if (imageUri != null && wallpaperBitmaps?.sourceSampledBitmap != null && wallpaperBitmaps?.page1TopCroppedBitmap == null && !isInP1EditMode && !isTransitioningFromEditMode) {
                updateOnlyPage1TopCroppedBitmap(
                    nonEditModePage1ImageHeightRatio,
                    wallpaperBitmaps!!.sourceSampledBitmap!!,
                    this.currentP1ContentScaleFactor
                )
            }
            // 其他情况，仅重绘
            else {
                invalidate()
            }
        }
    }

    /**
     * 计算 P1 图片在 View 中的实际显示区域 (p1DisplayRectView)
     * 以及 P1 高度调整手柄 (p1HeightResizeHandleRect) 的位置和大小。
     * 这个方法依赖于 `currentEffectiveP1HeightRatio`。
     */
    private fun calculateP1DisplayRectView() {
        if (viewWidth <= 0 || viewHeight <= 0) {
            p1DisplayRectView.setEmpty()
            return
        }
        // P1区域的实际高度 = View高度 * 当前生效的P1高度比例
        val p1ActualHeight = viewHeight * currentEffectiveP1HeightRatio
        // P1区域总是从View顶部开始，宽度与View一致
        p1DisplayRectView.set(0f, 0f, viewWidth.toFloat(), p1ActualHeight)

        // 计算高度调整手柄的尺寸和位置
        // 手柄的视觉高度固定 (8dp)
        val handleVisualHeight = 8f * resources.displayMetrics.density
        // 手柄宽度为P1区域宽度的30%
        val handleWidth = p1DisplayRectView.width() * 0.3f
        p1HeightResizeHandleRect.set(
            p1DisplayRectView.centerX() - handleWidth / 2f, // 水平居中
            p1DisplayRectView.bottom - handleVisualHeight,  // 位于P1区域底部
            p1DisplayRectView.centerX() + handleWidth / 2f,
            p1DisplayRectView.bottom
        )
    }


// ... (先前已注释的部分)

    /**
     * 启用或禁用 P1 图片的编辑模式。
     *
     * 当进入编辑模式时：
     * - 如果没有源位图，会请求外部取消编辑模式。
     * - 会根据传入的或当前的 P1 配置参数（焦点、高度、缩放）初始化编辑状态。
     * - 会请求父 View 不要拦截触摸事件，以便 PreviewView 可以处理 P1 编辑手势。
     * - 会停止任何正在进行的页面滚动或 P1 内容滚动动画。
     *
     * 当退出编辑模式时：
     * - 会启动一个从当前编辑状态到标准预览状态的过渡效果。
     * - 会重新生成 P1 顶部裁剪图以反映非编辑模式下的配置。
     * - 会允许父 View 拦截触摸事件。
     *
     * @param isEditing true 表示进入编辑模式，false 表示退出。
     * @param initialNormFocusX (可选) 进入编辑模式时，P1 图片的初始归一化焦点 X 坐标。
     * @param initialNormFocusY (可选) 进入编辑模式时，P1 图片的初始归一化焦点 Y 坐标。
     * @param initialHeightRatio (可选) 进入编辑模式时，P1 区域的初始高度比例。
     * @param initialContentScale (可选) 进入编辑模式时，P1 图片内容的初始缩放因子。
     */
    fun setP1FocusEditMode(
        isEditing: Boolean,
        initialNormFocusX: Float? = null,
        initialNormFocusY: Float? = null,
        initialHeightRatio: Float? = null, // 对于样式B，此参数在进入编辑模式时通常不直接使用
        initialContentScale: Float? = null
    ) {
        val wasEditing = this.isInP1EditMode

        // 如果状态没有改变，并且是尝试在编辑模式下再次进入编辑模式 (可能用于同步外部参数)
        if (wasEditing && isEditing) {
            Log.d(TAG, "setP1FocusEditMode: Staying in edit mode. Syncing params if provided for current style: $currentP1StyleType")
            if (currentP1StyleType == 1 /* STYLE_B */) {
                val focusX = initialNormFocusX ?: this.currentStyleBP1FocusX
                val focusY = initialNormFocusY ?: this.currentStyleBP1FocusY
                val scale = initialContentScale ?: this.currentStyleBP1ScaleFactor // 使用样式B的缩放

                this.currentStyleBP1FocusX = focusX
                this.currentStyleBP1FocusY = focusY
                this.currentStyleBP1ScaleFactor = scale
                currentEditP1ContentScaleFactor = scale // 编辑模式内部统一用这个跟踪缩放

                p1DisplayRectView.set(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat()) // 确保是全屏
                if (wallpaperBitmaps?.sourceSampledBitmap != null) {
                    resetP1EditMatrixToFocus(focusX, focusY, scale, p1DisplayRectView)
                }

            } else { // STYLE_A
                currentEditP1HeightRatio = initialHeightRatio ?: currentEditP1HeightRatio // 编辑模式下用自己的高度
                this.currentNormalizedFocusX = initialNormFocusX ?: this.currentNormalizedFocusX
                this.currentNormalizedFocusY = initialNormFocusY ?: this.currentNormalizedFocusY
                currentEditP1ContentScaleFactor = initialContentScale ?: currentEditP1ContentScaleFactor

                calculateP1DisplayRectView() // 样式A依赖这个计算
                if (wallpaperBitmaps?.sourceSampledBitmap != null) {
                    resetP1EditMatrixToFocus(
                        this.currentNormalizedFocusX,
                        this.currentNormalizedFocusY,
                        currentEditP1ContentScaleFactor,
                        p1DisplayRectView
                    )
                }
            }
            this.isTransitioningFromEditMode = false
            invalidate()
            return
        }

        this.isInP1EditMode = isEditing

        if (wasEditing && !isEditing) { // 从编辑模式退出 (true -> false)
            Log.d(TAG, "setP1FocusEditMode: Exiting edit mode. Current Style: $currentP1StyleType. Capturing last matrix and starting transition.")
            this.isTransitioningFromEditMode = true
            this.transitionMatrix.set(p1EditMatrix)

            parent?.requestDisallowInterceptTouchEvent(false)

            // 退出编辑时，currentEditP1HeightRatio 和 p1DisplayRectView 需要恢复到非编辑状态
            // calculateP1DisplayRectView 会使用 nonEditModePage1ImageHeightRatio (对于样式A)
            // 或在样式B时，我们可能不需要它，因为渲染器会全屏处理
            // 为了统一，先调用 calculateP1DisplayRectView，它内部会根据 currentEffectiveP1HeightRatio
            // 而 currentEffectiveP1HeightRatio 在非编辑模式下对于样式A会是 nonEditModePage1ImageHeightRatio
            // 对于样式B，如果 nonEditModePage1ImageHeightRatio 也被设为1，那效果上也是全屏。
            // 或者，确保 setP1FocusEditMode(false)后，onDraw中的逻辑能正确处理。
            // 退出编辑时，将编辑时的高度比例恢复为非编辑模式下的P1图片高度（通常由ViewModel控制）
            // 但这主要针对样式A。样式B的 "高度" 始终是全屏。
            // 为了过渡动画的平滑，p1DisplayRectView在过渡期间的计算应基于退出编辑前的状态，
            // 但最终渲染会切换到非编辑模式的参数。

            // 确保p1DisplayRectView是基于非编辑模式的参数。
            // 对于样式A，是nonEditModePage1ImageHeightRatio；对于样式B，理论上是全屏。
            // 我们先简单地调用calculateP1DisplayRectView，它会使用 currentEffectiveP1HeightRatio,
            // 而 currentEffectiveP1HeightRatio 在非编辑模式下会取 nonEditModePage1ImageHeightRatio
            // 如果要确保样式B退出时也是全屏预览，可能需要调整 calculateP1DisplayRectView 或这里的逻辑。
            // 暂时保持，calculateP1DisplayRectView 会基于 nonEditModePage1ImageHeightRatio。
            // 如果nonEditModePage1ImageHeightRatio对于样式B不是1，则这里p1DisplayRectView可能不是全屏。
            // 但由于onDraw中样式B的普通预览会强制全屏渲染，所以视觉上应该还好。
            currentEditP1HeightRatio = nonEditModePage1ImageHeightRatio // 恢复到非编辑时的高度（主要对A有意义）
            calculateP1DisplayRectView()


            if (imageUri != null && wallpaperBitmaps?.sourceSampledBitmap != null) {
                if (currentP1StyleType == 1 /* STYLE_B */) {
                    // 样式B退出编辑，不需要更新P1顶图，但要确保后续预览使用正确的变换
                    // isTransitioningFromEditMode = false 会在下面处理
                    Log.d(TAG, "setP1FocusEditMode (Style B exit): Transitioning. No P1 top bitmap update needed.")
                    // 确保 isTransitioningFromEditMode 在一段时间后设为 false
                    postDelayed({
                        this.isTransitioningFromEditMode = false
                        invalidate()
                    }, 50) // 延迟一小段时间以允许过渡动画播放一帧
                } else { // 样式A
                    updateOnlyPage1TopCroppedBitmap(
                        heightRatioToUse = nonEditModePage1ImageHeightRatio,
                        sourceBitmap = wallpaperBitmaps!!.sourceSampledBitmap!!,
                        contentScaleToUse = this.currentP1ContentScaleFactor, // 使用非编辑模式的缩放因子
                        onComplete = {
                            Log.d(TAG, "setP1FocusEditMode (Style A exit): P1 top cropped bitmap update complete. Ending transition.")
                            this.isTransitioningFromEditMode = false
                            invalidate()
                        }
                    )
                }
            } else {
                this.isTransitioningFromEditMode = false
                invalidate()
            }
            if (!pageScroller.isFinished) pageScroller.abortAnimation()
            if (!p1ContentScroller.isFinished) p1ContentScroller.forceFinished(true)

        } else if (!wasEditing && isEditing) { // 进入编辑模式 (false -> true)
            Log.d(TAG, "setP1FocusEditMode: Entering edit mode. Current Style: $currentP1StyleType")
            this.isTransitioningFromEditMode = false

            if (wallpaperBitmaps?.sourceSampledBitmap == null) {
                Log.w(TAG, "P1EditMode: No source bitmap. Requesting cancel.")
                this.isInP1EditMode = false;
                onRequestActionCallback?.invoke(PreviewViewAction.REQUEST_CANCEL_P1_EDIT_MODE)
                invalidate()
                return
            }

            if (currentP1StyleType == 1 /* STYLE_B */) {
                // 样式B的P1编辑
                p1DisplayRectView.set(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat()) // 编辑目标是全屏
                // 使用样式B的参数初始化
                val focusX = initialNormFocusX ?: this.currentStyleBP1FocusX
                val focusY = initialNormFocusY ?: this.currentStyleBP1FocusY
                val scale = initialContentScale ?: this.currentStyleBP1ScaleFactor

                // 更新 ViewModel 通过回调保存的当前焦点/缩放值 (如果 initialXXX 不为 null)
                // 这些值会在 executeP1ConfigUpdate 中被手势的最终结果覆盖
                this.currentStyleBP1FocusX = focusX
                this.currentStyleBP1FocusY = focusY
                this.currentStyleBP1ScaleFactor = scale
                currentEditP1ContentScaleFactor = scale // 编辑模式内部统一用这个跟踪缩放

                resetP1EditMatrixToFocus(focusX, focusY, scale, p1DisplayRectView)

            } else { // 样式A的P1编辑
                currentEditP1HeightRatio = initialHeightRatio ?: nonEditModePage1ImageHeightRatio
                this.currentNormalizedFocusX = initialNormFocusX ?: this.currentNormalizedFocusX
                this.currentNormalizedFocusY = initialNormFocusY ?: this.currentNormalizedFocusY
                currentEditP1ContentScaleFactor = initialContentScale ?: this.currentP1ContentScaleFactor

                calculateP1DisplayRectView() // 确保 p1DisplayRectView 更新
                resetP1EditMatrixToFocus(
                    this.currentNormalizedFocusX,
                    this.currentNormalizedFocusY,
                    currentEditP1ContentScaleFactor,
                    p1DisplayRectView
                )
            }

            parent?.requestDisallowInterceptTouchEvent(true)
            isPageSwiping = false
            if (!pageScroller.isFinished) pageScroller.abortAnimation()
            if (!p1ContentScroller.isFinished) p1ContentScroller.forceFinished(true)
            invalidate()

        } else { // 模式未改变 (例如，初始调用时 isEditing 为 false)
            Log.d(TAG, "setP1FocusEditMode: Mode not changing or initial call. current isEditing: $isEditing, style: $currentP1StyleType")
            this.isTransitioningFromEditMode = false
            // 确保 p1DisplayRectView 是最新的
            if (this.isInP1EditMode && this.currentP1StyleType == 1) {
                p1DisplayRectView.set(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
            } else {
                calculateP1DisplayRectView()
            }

            if (!this.isInP1EditMode && imageUri != null && wallpaperBitmaps?.sourceSampledBitmap != null) {
                if (this.currentP1StyleType == 0 /* STYLE_A */) {
                    updateOnlyPage1TopCroppedBitmap(
                        nonEditModePage1ImageHeightRatio,
                        wallpaperBitmaps!!.sourceSampledBitmap!!,
                        this.currentP1ContentScaleFactor
                    )
                } else { // Style B 非编辑模式，不需要更新P1顶图，onDraw会处理
                    invalidate()
                }
            } else {
                invalidate()
            }
        }
    }


    /**
     * 在 P1 编辑模式下，重置 P1 内容的变换矩阵 (p1EditMatrix)，
     * 以使得源图像上由归一化焦点 (normFocusX, normFocusY) 指定的点
     * 显示在 P1 显示区域 (p1DisplayRectView) 的中心，并应用当前的 P1 内容缩放因子。
     *
     * @param normFocusX 归一化的目标焦点 X 坐标 (0.0 - 1.0)。
     * @param normFocusY 归一化的目标焦点 Y 坐标 (0.0 - 1.0)。
     */
    // 主方法，接受明确的目标矩形和内容缩放因子
    private fun resetP1EditMatrixToFocus(
        normFocusX: Float,
        normFocusY: Float,
        contentScaleFactor: Float, // 明确传入内容缩放因子
        targetRectForFocus: RectF   // 明确传入焦点对准的目标区域
    ) {
        val source = wallpaperBitmaps?.sourceSampledBitmap
        if (source == null || source.isRecycled || targetRectForFocus.isEmpty) {
            p1EditMatrix.reset()
            invalidate()
            return
        }

        val baseFillScale = calculateP1BaseFillScale(source, targetRectForFocus)
        val totalEffectiveScale = baseFillScale * contentScaleFactor.coerceAtLeast(1.0f) // 确保内容缩放至少为1

        p1EditMatrix.reset()
        if (totalEffectiveScale > 0.00001f) {
            p1EditMatrix.setScale(totalEffectiveScale, totalEffectiveScale)
        } else {
            Log.w(
                TAG,
                "resetP1EditMatrixToFocus: totalEffectiveScale ($totalEffectiveScale) is invalid, using baseFillScale ($baseFillScale). contentScaleFactor was $contentScaleFactor"
            )
            if (baseFillScale > 0.00001f) p1EditMatrix.setScale(baseFillScale, baseFillScale)
            else p1EditMatrix.setScale(1.0f, 1.0f) // 极端情况下的回退
        }

        val currentActualScaleApplied = getCurrentP1EditMatrixScale()
        val focusSourcePxX = normFocusX * source.width
        val focusSourcePxY = normFocusY * source.height
        val scaledFocusSourcePxX = focusSourcePxX * currentActualScaleApplied
        val scaledFocusSourcePxY = focusSourcePxY * currentActualScaleApplied

        val targetCenterX = targetRectForFocus.centerX()
        val targetCenterY = targetRectForFocus.centerY()

        val translateX = targetCenterX - scaledFocusSourcePxX
        val translateY = targetCenterY - scaledFocusSourcePxY
        p1EditMatrix.postTranslate(translateX, translateY)

        // 调用 applyP1EditMatrixBounds 的重载版本，传递正确的参数
        applyP1EditMatrixBounds(targetRectForFocus, contentScaleFactor)
        invalidate()
    }

    // 旧的签名，根据当前样式委派给新的重载版本
// 这个版本主要在非手势驱动的重置（例如，从外部设置焦点）时被间接调用
    private fun resetP1EditMatrixToFocus(normFocusX: Float, normFocusY: Float) {
        val targetRect: RectF
        val scaleToUse: Float

        if (currentP1StyleType == 1 /* STYLE_B */) {
            targetRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
            // 如果在编辑模式，currentEditP1ContentScaleFactor 反映手势或初始值
            // 如果非编辑模式，应使用 this.currentStyleBP1ScaleFactor
            scaleToUse = if (isInP1EditMode) currentEditP1ContentScaleFactor else this.currentStyleBP1ScaleFactor
            resetP1EditMatrixToFocus(normFocusX, normFocusY, scaleToUse, targetRect)
        } else { // Style A
            targetRect = p1DisplayRectView // 这个应该已经被 calculateP1DisplayRectView 正确设置
            // 同上，区分编辑模式和非编辑模式的缩放因子来源
            scaleToUse = if (isInP1EditMode) currentEditP1ContentScaleFactor else this.currentP1ContentScaleFactor
            resetP1EditMatrixToFocus(normFocusX, normFocusY, scaleToUse, targetRect)
        }
    }

    /**
     * 计算 P1 基础填充缩放比例。
     * 这个比例确保源图像在应用此缩放后，其较短的一边能够完全填充 P1 的目标矩形区域，
     * 即实现 "centerCrop" 或 "cover" 的效果。
     *
     * @param source 要缩放的源 Bitmap。
     * @param targetRect P1 的目标显示矩形区域。
     * @return 计算得到的最小填充缩放比例；如果输入无效，则返回 1.0。
     */
    private fun calculateP1BaseFillScale(source: Bitmap, targetRect: RectF): Float {
        if (source.width <= 0 || source.height <= 0 || targetRect.width() <= 0 || targetRect.height() <= 0) return 1.0f
        // 计算宽度和高度方向各自需要的缩放比例
        val scaleX = targetRect.width() / source.width.toFloat()
        val scaleY = targetRect.height() / source.height.toFloat()
        // 取两者中较大的一个，以确保完全覆盖目标区域
        return max(scaleX, scaleY)
    }

    /**
     * 获取当前 P1 编辑矩阵 (p1EditMatrix) 的缩放值。
     * 假设 X 和 Y 方向的缩放值是相同的。
     * @return 当前矩阵的缩放系数。
     */
    private fun getCurrentP1EditMatrixScale(): Float {
        val values = FloatArray(9)
        p1EditMatrix.getValues(values)
        return values[Matrix.MSCALE_X] // 返回 X 方向的缩放值
    }

    /**
     * 应用 P1 编辑模式下的矩阵边界限制。
     * 确保经过变换（平移、缩放）后的 P1 图片内容：
     * 1. 缩放比例在允许的用户范围内 (p1UserMinScaleFactorRelativeToCover 到 p1UserMaxScaleFactorRelativeToCover)。
     * 2. 图片的边界不会移出 P1 显示区域 (p1DisplayRectView) 的可见范围。
     * 如果图片比 P1 区域小，则使其在 P1 区域内居中。
     * 如果图片比 P1 区域大，则确保 P1 区域总是能看到图片的一部分，不允许出现空白。
     * 同时，此方法会根据最终的矩阵缩放值，反向更新 `currentEditP1ContentScaleFactor`。
     */
    // 主方法，接受明确的目标矩形和当前内容缩放因子
    private fun applyP1EditMatrixBounds(
        targetRect: RectF,
        currentContentScaleFactorForBounds: Float // 主要用于可能的反向重置逻辑
    ) {
        val source = wallpaperBitmaps?.sourceSampledBitmap ?: return
        if (targetRect.isEmpty || source.isRecycled || source.width == 0 || source.height == 0) return

        var currentMatrixScaleVal = getCurrentP1EditMatrixScale()
        val baseFillScale = calculateP1BaseFillScale(source, targetRect)
        if (baseFillScale <= 0.00001f) {
            Log.e(TAG, "applyP1EditMatrixBounds: baseFillScale is zero or too small for targetRect: $targetRect"); return
        }

        val minAllowedGlobalScale = baseFillScale * p1UserMinScaleFactorRelativeToCover
        val maxAllowedGlobalScale = baseFillScale * p1UserMaxScaleFactorRelativeToCover
        var scaleCorrectionFactor = 1.0f

        if (currentMatrixScaleVal < minAllowedGlobalScale) {
            scaleCorrectionFactor = if (currentMatrixScaleVal > 0.00001f) minAllowedGlobalScale / currentMatrixScaleVal else 0f
        } else if (currentMatrixScaleVal > maxAllowedGlobalScale) {
            scaleCorrectionFactor = if (currentMatrixScaleVal > 0.00001f) maxAllowedGlobalScale / currentMatrixScaleVal else 0f
        }

        if (scaleCorrectionFactor == 0f) {
            Log.w(TAG, "applyP1EditMatrixBounds: scaleCorrectionFactor is zero, resetting matrix to safe scale for targetRect: $targetRect")
            // 根据当前样式和其对应的焦点/缩放重置
            // 使用 currentContentScaleFactorForBounds 作为重置时的缩放依据
            if (currentP1StyleType == 1 /* STYLE_B */) {
                resetP1EditMatrixToFocus(this.currentStyleBP1FocusX, this.currentStyleBP1FocusY, currentContentScaleFactorForBounds, targetRect)
            } else { // 样式A
                resetP1EditMatrixToFocus(this.currentNormalizedFocusX, this.currentNormalizedFocusY, currentContentScaleFactorForBounds, targetRect)
            }
            return
        }

        if (abs(scaleCorrectionFactor - 1.0f) > 0.0001f) {
            p1EditMatrix.postScale(scaleCorrectionFactor, scaleCorrectionFactor, targetRect.centerX(), targetRect.centerY())
            // 当缩放被修正后，currentEditP1ContentScaleFactor 应该反映这个修正。
            val correctedGlobalScale = getCurrentP1EditMatrixScale()
            if (baseFillScale > 0.00001f) {
                currentEditP1ContentScaleFactor = (correctedGlobalScale / baseFillScale) // 直接计算新的内容缩放
                    .coerceIn(p1UserMinScaleFactorRelativeToCover, p1UserMaxScaleFactorRelativeToCover) // 再约束一次确保安全
            }
        }
        // 如果没有缩放校正，currentEditP1ContentScaleFactor 保持由 P1ContentScaleListener.onScale() 设置的值


        // --- 应用平移边界 ---
        val values = FloatArray(9)
        p1EditMatrix.getValues(values)
        val currentTransX = values[Matrix.MTRANS_X]
        val currentTransY = values[Matrix.MTRANS_Y]
        val finalScaleAfterCorrection = getCurrentP1EditMatrixScale()
        val scaledBitmapWidth = source.width * finalScaleAfterCorrection
        val scaledBitmapHeight = source.height * finalScaleAfterCorrection
        var dx = 0f
        var dy = 0f

        // 使用传入的 targetRect 进行边界判断
        if (scaledBitmapWidth <= targetRect.width() + 0.1f) {
            dx = targetRect.centerX() - (currentTransX + scaledBitmapWidth / 2f)
        } else {
            if (currentTransX > targetRect.left) dx = targetRect.left - currentTransX
            else if (currentTransX + scaledBitmapWidth < targetRect.right) dx =
                targetRect.right - (currentTransX + scaledBitmapWidth)
        }

        if (scaledBitmapHeight <= targetRect.height() + 0.1f) {
            dy = targetRect.centerY() - (currentTransY + scaledBitmapHeight / 2f)
        } else {
            if (currentTransY > targetRect.top) dy = targetRect.top - currentTransY
            else if (currentTransY + scaledBitmapHeight < targetRect.bottom) dy =
                targetRect.bottom - (currentTransY + scaledBitmapHeight)
        }

        if (abs(dx) > 0.001f || abs(dy) > 0.001f) {
            p1EditMatrix.postTranslate(dx, dy)
        }
    }

    // 旧的签名，根据当前样式委派给新的重载版本
    private fun applyP1EditMatrixBounds() {
        val targetRect: RectF
        // 在编辑模式下，currentEditP1ContentScaleFactor 是手势进行中或初始化时的内容缩放
        val scaleFactorForBounds = currentEditP1ContentScaleFactor

        if (currentP1StyleType == 1 /* STYLE_B */) {
            targetRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
            applyP1EditMatrixBounds(targetRect, scaleFactorForBounds)
        } else { // Style A
            targetRect = p1DisplayRectView // 这个应该已经被 calculateP1DisplayRectView 正确设置
            applyP1EditMatrixBounds(targetRect, scaleFactorForBounds)
        }
    }

    /**
     * 尝试以节流方式更新 P1 的配置。
     * 当 P1 图片在编辑模式下通过手势（拖动、缩放、高度调整）发生变化时，
     * 此方法会被调用，以避免过于频繁地计算新的焦点位置和回调外部监听器。
     * 如果距离上次更新时间足够长，则立即执行更新；否则，会延迟执行或合并更新。
     */
    private fun attemptThrottledP1ConfigUpdate() {
        if (!isInP1EditMode) return // 仅在P1编辑模式下有效
        val currentTime = System.currentTimeMillis()
        // 如果距离上次更新时间已超过节流间隔，则立即执行更新
        if (currentTime - lastP1ConfigUpdateTime >= P1_CONFIG_UPDATE_THROTTLE_MS) {
            executeP1ConfigUpdate()
        } else {
            // 否则，标记有待处理的更新，并设置一个延迟任务
            isThrottledP1ConfigUpdatePending = true
            if (throttledP1ConfigUpdateRunnable == null) { // 避免重复创建延迟任务
                throttledP1ConfigUpdateRunnable = Runnable {
                    // 延迟任务执行时，再次检查是否有待处理的更新
                    if (isThrottledP1ConfigUpdatePending) executeP1ConfigUpdate()
                }
                // 计算还需要延迟多久
                mainHandler.postDelayed(
                    throttledP1ConfigUpdateRunnable!!,
                    P1_CONFIG_UPDATE_THROTTLE_MS - (currentTime - lastP1ConfigUpdateTime)
                )
            }
        }
    }

    /**
     * 执行实际的 P1 配置更新操作。
     * 此方法会根据当前 P1 编辑矩阵 (p1EditMatrix) 和 P1 显示区域 (p1DisplayRectView)
     * 的中心点，反向计算出源图像上对应的归一化焦点坐标 (currentNormalizedFocusX, currentNormalizedFocusY)。
     * 然后，通过 `onP1ConfigEditedListener` 回调将新的焦点、当前编辑高度比例和内容缩放因子通知外部。
     */
    private fun executeP1ConfigUpdate() {
        lastP1ConfigUpdateTime = System.currentTimeMillis()
        isThrottledP1ConfigUpdatePending = false
        throttledP1ConfigUpdateRunnable?.let { mainHandler.removeCallbacks(it) }
        throttledP1ConfigUpdateRunnable = null

        val source = wallpaperBitmaps?.sourceSampledBitmap ?: return

        val targetRectForFocusCalculation: RectF
        // float contentScaleToReport; // 改为在下面计算

        if (currentP1StyleType == 1 /* STYLE_B */) {
            targetRectForFocusCalculation = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        } else { // 样式A
            targetRectForFocusCalculation = p1DisplayRectView
        }

        if (targetRectForFocusCalculation.isEmpty || source.width <= 0 || source.height <= 0) {
            // ... (错误处理和回调当前值的逻辑保持不变，但 contentScaleToReport 需要定义)
            // 对于错误情况，我们可以使用 currentEditP1ContentScaleFactor 作为回退
            val fallbackScale = currentEditP1ContentScaleFactor
            if (currentP1StyleType == 1) {
                onP1ConfigEditedListener?.invoke(this.currentStyleBP1FocusX, this.currentStyleBP1FocusY, 1.0f, fallbackScale)
            } else {
                onP1ConfigEditedListener?.invoke(this.currentNormalizedFocusX, this.currentNormalizedFocusY, currentEditP1HeightRatio, fallbackScale)
            }
            return
        }

        // 从 p1EditMatrix 计算归一化焦点
        val viewCenter = floatArrayOf(targetRectForFocusCalculation.centerX(), targetRectForFocusCalculation.centerY())
        val invertedMatrix = Matrix()
        if (!p1EditMatrix.invert(invertedMatrix)) {
            Log.e(TAG, "executeP1ConfigUpdate: p1EditMatrix non-invertible!")
            val fallbackScale = currentEditP1ContentScaleFactor
            if (currentP1StyleType == 1) {
                onP1ConfigEditedListener?.invoke(this.currentStyleBP1FocusX, this.currentStyleBP1FocusY, 1.0f, fallbackScale)
            } else {
                onP1ConfigEditedListener?.invoke(this.currentNormalizedFocusX, this.currentNormalizedFocusY, currentEditP1HeightRatio, fallbackScale)
            }
            return
        }
        invertedMatrix.mapPoints(viewCenter)
        val newNormX = (viewCenter[0] / source.width.toFloat()).coerceIn(0f, 1f)
        val newNormY = (viewCenter[1] / source.height.toFloat()).coerceIn(0f, 1f)

        // 从 p1EditMatrix 计算归一化的内容缩放因子
        val actualMatrixScale = getCurrentP1EditMatrixScale()
        val baseFillScale = calculateP1BaseFillScale(source, targetRectForFocusCalculation)
        val normalizedContentScale = if (baseFillScale > 0.00001f) {
            (actualMatrixScale / baseFillScale).coerceIn(p1UserMinScaleFactorRelativeToCover, p1UserMaxScaleFactorRelativeToCover)
        } else {
            currentEditP1ContentScaleFactor // 回退到当前编辑值
        }
        val contentScaleToReport = normalizedContentScale

        // 根据当前样式更新 ViewModel 持有的对应参数，并回调
        if (currentP1StyleType == 1 /* STYLE_B */) {
            this.currentStyleBP1FocusX = newNormX
            this.currentStyleBP1FocusY = newNormY
            this.currentStyleBP1ScaleFactor = contentScaleToReport // 确保内部也更新为这个精确计算的值

            Log.d(TAG, "executeP1ConfigUpdate (Style B): Calling listener with NXY=($newNormX,$newNormY), CS=$contentScaleToReport")
            onP1ConfigEditedListener?.invoke(
                newNormX,
                newNormY,
                1.0f,
                contentScaleToReport
            )
        } else { // 样式A
            this.currentNormalizedFocusX = newNormX
            this.currentNormalizedFocusY = newNormY
            this.currentP1ContentScaleFactor = contentScaleToReport // 更新样式A的内部保存值

            Log.d(TAG, "executeP1ConfigUpdate (Style A): Calling listener with NXY=($newNormX,$newNormY), HR=$currentEditP1HeightRatio, CS=$contentScaleToReport")
            onP1ConfigEditedListener?.invoke(
                newNormX,
                newNormY,
                currentEditP1HeightRatio, // heightRatio 对于样式A仍然来自 currentEditP1HeightRatio
                contentScaleToReport
            )
        }
    }


    /**
     * P1 内容拖动手势的监听器实现 (GestureDetector.SimpleOnGestureListener)。
     * 仅在 P1 编辑模式下生效，处理 P1 图片内容的拖动和快速滑动 (Fling)。
     */
    private inner class P1ContentGestureListener : GestureDetector.SimpleOnGestureListener() {
        /**
         * 当 ACTION_DOWN 事件发生在 P1 显示区域内且处于 P1 编辑模式时被调用。
         * @return 返回 true 表示消费此事件并希望处理后续手势；否则返回 false。
         */
        override fun onDown(e: MotionEvent): Boolean {
            if (isInP1EditMode && wallpaperBitmaps?.sourceSampledBitmap != null &&
                p1DisplayRectView.contains(e.x, e.y) && !isP1HeightResizing
            ) {
                // 如果P1内容正在惯性滚动，则停止它
                if (!p1ContentScroller.isFinished) {
                    p1ContentScroller.forceFinished(true)
                }
                return true // 消费事件，准备处理拖动或点击
            }
            return false
        }

        /**
         * 当一个单击手势 (Single Tap Up) 在 P1 显示区域内被确认时调用。
         * 在 P1 编辑模式下，这会触发 View 的 performClick() 方法，进而调用外部设置的 OnClickListener。
         * @return 如果事件被处理则返回 true。
         */
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (isInP1EditMode && p1DisplayRectView.contains(e.x, e.y) && !isP1HeightResizing) {
                Log.d(
                    TAG,
                    "P1ContentGestureListener: onSingleTapUp in P1 Edit mode. Calling performClick()."
                )
                // 触发View的点击事件，通常用于例如显示/隐藏配置面板等操作
                performClick()
                return true // 单击事件已处理
            }
            return super.onSingleTapUp(e)
        }

        /**
         * 当拖动手势发生时调用 (手指在屏幕上滑动)。
         * @param e1 初始的 ACTION_DOWN 事件。
         * @param e2 当前的 ACTION_MOVE 事件。
         * @param distanceX 本次回调 X 方向上滑动的距离 (旧位置 - 新位置)。
         * @param distanceY 本次回调 Y 方向上滑动的距离 (旧位置 - 新位置)。
         * @return 如果事件被处理则返回 true。
         */
        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            // 确保在P1编辑模式、有源图、触摸点在P1区域内且非高度调整状态
            if (e1 == null || !isInP1EditMode || isP1HeightResizing ||
                wallpaperBitmaps?.sourceSampledBitmap == null || !p1DisplayRectView.contains(
                    e1.x,
                    e1.y
                )
            ) {
                return false
            }
            // 根据手指滑动的反方向平移P1编辑矩阵
            p1EditMatrix.postTranslate(-distanceX, -distanceY)
            applyP1EditMatrixBounds() // 应用边界限制
            attemptThrottledP1ConfigUpdate() // 尝试节流更新P1配置
            invalidate() // 重绘
            return true
        }

        /**
         * 当快速滑动 (Fling) 手势发生时调用。
         * @param e1 初始的 ACTION_DOWN 事件。
         * @param e2 最后的 ACTION_UP 事件。
         * @param velocityX X 方向的滑动速度 (像素/秒)。
         * @param velocityY Y 方向的滑动速度 (像素/秒)。
         * @return 如果事件被处理则返回 true。
         */
        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            // 确保在P1编辑模式、有源图、触摸点在P1区域内且非高度调整状态
            if (!isInP1EditMode || isP1HeightResizing || wallpaperBitmaps?.sourceSampledBitmap == null ||
                e1 == null || !p1DisplayRectView.contains(e1.x, e1.y)
            ) {
                return false
            }

            val source = wallpaperBitmaps!!.sourceSampledBitmap!!
            if (source.isRecycled || source.width == 0 || source.height == 0) return false

            p1ContentScroller.forceFinished(true) // 停止上一次的Fling (如果有)
            lastP1ScrollerX = 0 // 重置Scroller的起始位置
            lastP1ScrollerY = 0

            // 使用OverScroller启动惯性滚动
            // 起始点(0,0)，速度为velocityX, velocityY
            // min/max X/Y 定义了滚动范围，这里设为Int.MIN_VALUE/MAX_VALUE表示理论上无限滚动
            // overX/overY 定义了超出边界后可以滚动的距离 (用于回弹效果，这里设为P1区域的1/4)
            p1ContentScroller.fling(
                0, 0,
                velocityX.toInt(), velocityY.toInt(),
                Int.MIN_VALUE, Int.MAX_VALUE,
                Int.MIN_VALUE, Int.MAX_VALUE,
                (p1DisplayRectView.width() / 4).toInt().coerceAtLeast(1), // overX
                (p1DisplayRectView.height() / 4).toInt().coerceAtLeast(1) // overY
            )
            postInvalidateOnAnimation() // 请求在下一帧动画时重绘 (computeScroll会被调用)
            return true
        }
    }

    /**
     * P1 内容缩放手势的监听器实现 (ScaleGestureDetector.SimpleOnScaleGestureListener)。
     * 仅在 P1 编辑模式下生效，处理 P1 图片内容的双指缩放。
     */
    private inner class P1ContentScaleListener :
        ScaleGestureDetector.SimpleOnScaleGestureListener() {
        /**
         * 当缩放手势开始时调用。
         * @param detector ScaleGestureDetector 实例。
         * @return 返回 true 表示希望处理此缩放手势；否则返回 false。
         */
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            // 确保在P1编辑模式、非高度调整、有源图、且缩放焦点在P1区域内
            if (isInP1EditMode && !isP1HeightResizing &&
                wallpaperBitmaps?.sourceSampledBitmap != null &&
                p1DisplayRectView.contains(detector.focusX, detector.focusY)
            ) {
                // 如果P1内容正在惯性滚动，则停止它
                if (!p1ContentScroller.isFinished) {
                    p1ContentScroller.forceFinished(true)
                }
                return true // 处理缩放手势
            }
            return false
        }

        /**
         * 当缩放手势进行中时调用。
         * @param detector ScaleGestureDetector 实例，包含缩放因子和焦点信息。
         * @return 如果事件被处理则返回 true。
         */
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (!isInP1EditMode || isP1HeightResizing || wallpaperBitmaps?.sourceSampledBitmap == null) return false
            val scaleFactor = detector.scaleFactor // 获取本次回调的缩放因子
            // 仅当缩放因子有效且有实际变化时才处理
            if (scaleFactor != 0f && abs(scaleFactor - 1.0f) > 0.0001f) {
                // 以缩放手势的焦点 (detector.focusX, detector.focusY) 为中心进行缩放
                p1EditMatrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
                applyP1EditMatrixBounds() // 应用边界和缩放范围限制
                attemptThrottledP1ConfigUpdate() // 尝试节流更新P1配置
                invalidate() // 重绘
            }
            return true
        }
    }

    /**
     * 处理 View 上的所有触摸事件。
     * 根据当前模式（是否为 P1 编辑模式、是否正在调整 P1 高度等），
     * 将事件分发给相应的处理器：
     * - P1 高度调整逻辑
     * - P1 内容缩放手势检测器 (p1ContentScaleGestureDetector)
     * - P1 内容拖动/单击手势检测器 (p1ContentDragGestureDetector)
     * - 全局的页面滑动和单击逻辑
     *
     * @param event 触摸事件对象。
     * @return 如果事件被消费则返回 true，否则返回 false (将事件传递给父 View 或默认处理)。
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val touchX = event.x
        val touchY = event.y
        var eventHandled = false

        // --- P1 编辑模式下的专属手势处理 ---
        if (isInP1EditMode && wallpaperBitmaps?.sourceSampledBitmap != null) {
            // 1. P1 高度调整手势
            //    对于样式B，这个逻辑应该被跳过或禁用
            if (currentP1StyleType != 1 /* NOT STYLE_B */) { // 仅在非样式B时启用高度调整
                if (event.actionMasked == MotionEvent.ACTION_DOWN && !isP1HeightResizing) {
                    val effectiveTouchSlopForResize = touchSlop * p1HeightResizeTouchSlopFactor
                    // p1DisplayRectView 在样式A的P1编辑模式下，应该已经被 setP1FocusEditMode 和 calculateP1DisplayRectView 设置为正确的P1区域
                    val heightHandleHotZone = RectF(
                        p1DisplayRectView.left,
                        p1DisplayRectView.bottom - effectiveTouchSlopForResize,
                        p1DisplayRectView.right,
                        p1DisplayRectView.bottom + effectiveTouchSlopForResize
                    )
                    if (p1DisplayRectView.height() > effectiveTouchSlopForResize * 1.5f && heightHandleHotZone.contains(
                            touchX,
                            touchY
                        )
                    ) {
                        if (!p1ContentScroller.isFinished) {
                            p1ContentScroller.forceFinished(true)
                        }
                        isP1HeightResizing = true
                        p1HeightResizeStartRawY = event.rawY
                        p1HeightResizeStartRatio = currentEditP1HeightRatio
                        parent?.requestDisallowInterceptTouchEvent(true)
                        eventHandled = true
                    }
                }

                if (isP1HeightResizing) { // 如果正在调整P1高度 (此分支仅对样式A可能进入)
                    when (event.actionMasked) {
                        MotionEvent.ACTION_MOVE -> {
                            val dyPixels = event.rawY - p1HeightResizeStartRawY
                            val deltaRatio = dyPixels / viewHeight.toFloat()
                            var newRatio = p1HeightResizeStartRatio + deltaRatio
                            newRatio = newRatio.coerceIn(
                                WallpaperConfigConstants.MIN_HEIGHT_RATIO,
                                WallpaperConfigConstants.MAX_HEIGHT_RATIO
                            )
                            if (abs(newRatio - currentEditP1HeightRatio) > 0.002f) {
                                currentEditP1HeightRatio = newRatio
                                calculateP1DisplayRectView() // 更新P1显示区域 (主要影响样式A)
                                // 高度变化后，重置P1内容的矩阵以保持焦点 (主要影响样式A)
                                // 对于样式A，targetRectForFocus 是 p1DisplayRectView
                                resetP1EditMatrixToFocus(
                                    this.currentNormalizedFocusX,
                                    this.currentNormalizedFocusY,
                                    currentEditP1ContentScaleFactor, // 使用当前编辑模式的缩放
                                    p1DisplayRectView
                                )
                                attemptThrottledP1ConfigUpdate()
                            }
                            eventHandled = true
                        }

                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            if (isThrottledP1ConfigUpdatePending) executeP1ConfigUpdate()
                            isP1HeightResizing = false
                            parent?.requestDisallowInterceptTouchEvent(false)
                            eventHandled = true
                        }

                        MotionEvent.ACTION_DOWN -> eventHandled = true
                    }
                    if (eventHandled) return true // 高度调整逻辑已处理事件
                }
            } else { // currentP1StyleType == 1 (STYLE_B)
                // 如果是样式B，确保 isP1HeightResizing 始终为 false，不处理高度调整
                if (isP1HeightResizing) { // 理论上不应该进入，但作为防御
                    isP1HeightResizing = false
                    parent?.requestDisallowInterceptTouchEvent(false) // 允许父拦截，因为我们不处理这个手势了
                    Log.w(TAG, "onTouchEvent: isP1HeightResizing was true in Style B edit mode. Resetting.")
                }
            }


            // 2. P1 内容缩放 和 P1内容拖动/P1区域内单击 手势检测器
            //    对于样式B，p1DisplayRectView 在进入编辑模式时已被设为全屏
            //    对于样式A，p1DisplayRectView 是其特定的P1区域
            var handledByP1SpecificDetectors = false
            // 确保触摸点在当前有效的 p1DisplayRectView 内 (样式A是P1区域，样式B是全屏)
            val currentActiveP1Rect = if (currentP1StyleType == 1 && isInP1EditMode) RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat()) else p1DisplayRectView

            if (currentActiveP1Rect.contains(touchX, touchY) && !isP1HeightResizing) {
                val scaleResult = p1ContentScaleGestureDetector.onTouchEvent(event)
                val dragOrTapResult = p1ContentDragGestureDetector.onTouchEvent(event)
                if (scaleResult || dragOrTapResult) {
                    handledByP1SpecificDetectors = true
                }
            }

            if (handledByP1SpecificDetectors) {
                if (event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                } else {
                    if (isThrottledP1ConfigUpdatePending) executeP1ConfigUpdate()
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                return true // P1专属手势消耗了事件
            }
        }

        // --- 全局触摸处理逻辑 (适用于非P1编辑模式，或P1编辑模式下未被上述P1专属逻辑消耗的事件) ---
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker!!.addMovement(event)

        val action = event.actionMasked
        val currentGlobalX = event.x
        var globalEventHandled = false

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (!pageScroller.isFinished) pageScroller.abortAnimation()
                if (isInP1EditMode && !p1ContentScroller.isFinished) p1ContentScroller.forceFinished(true)

                lastTouchX = currentGlobalX
                downTouchX = currentGlobalX
                activePointerId = event.getPointerId(0)
                isPageSwiping = false
                parent?.requestDisallowInterceptTouchEvent(true) // View想开始处理滑动
                globalEventHandled = true // Down事件总是被初步处理
            }

            MotionEvent.ACTION_MOVE -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) return false // Should not happen
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex < 0) return false // Should not happen

                val moveX = event.getX(pointerIndex)
                val deltaX = lastTouchX - moveX

                // 页面滑动逻辑仅在非P1编辑模式下生效
                if (!isInP1EditMode) {
                    if (!isPageSwiping && abs(moveX - downTouchX) > touchSlop) {
                        isPageSwiping = true
                        parent?.requestDisallowInterceptTouchEvent(true) // 确认滑动，父View不应拦截
                    }
                    if (isPageSwiping) {
                        currentPreviewXOffset = if (viewWidth > 0 && numVirtualPages > 1) {
                            (currentPreviewXOffset + deltaX / (viewWidth.toFloat() * (numVirtualPages - 1)))
                                .coerceIn(0f, 1f)
                        } else 0f
                        invalidate()
                    }
                }
                lastTouchX = moveX
                globalEventHandled = true
            }

            MotionEvent.ACTION_UP -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) {
                    recycleVelocityTracker()
                    isPageSwiping = false; parent?.requestDisallowInterceptTouchEvent(false)
                    return false
                }

                if (isPageSwiping) { // 仅在非P1编辑模式下可能为true
                    val vt = this.velocityTracker!!
                    vt.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                    val velocityX = vt.getXVelocity(activePointerId)
                    if (abs(velocityX) > minFlingVelocity && numVirtualPages > 1) {
                        flingPage(velocityX)
                    } else {
                        snapToNearestPage(currentPreviewXOffset)
                    }
                    globalEventHandled = true
                } else { // 不是页面滑动
                    if (abs(currentGlobalX - downTouchX) < touchSlop) { // 是单击
                        performClick()
                        globalEventHandled = true
                    } else { // 拖动了一小段距离后释放 (非P1编辑模式下)
                        if (!isInP1EditMode) {
                            snapToNearestPage(currentPreviewXOffset)
                        }
                        globalEventHandled = true
                    }
                }
                recycleVelocityTracker()
                activePointerId = MotionEvent.INVALID_POINTER_ID
                isPageSwiping = false
                parent?.requestDisallowInterceptTouchEvent(false) // 允许父View拦截
            }

            MotionEvent.ACTION_CANCEL -> {
                if (isPageSwiping && !isInP1EditMode) {
                    snapToNearestPage(currentPreviewXOffset)
                }
                recycleVelocityTracker()
                activePointerId = MotionEvent.INVALID_POINTER_ID
                isPageSwiping = false
                parent?.requestDisallowInterceptTouchEvent(false)
                globalEventHandled = true
            }
        }

        if (globalEventHandled) return true

        return super.onTouchEvent(event)
    }


    /**
     * 绘制 View 的内容。
     * 根据当前模式（是否P1编辑、是否从编辑模式过渡、是否有图片）进行不同的绘制：
     * - **过渡模式**: 绘制从 P1 编辑状态到标准预览状态的过渡帧。
     * - **P1编辑模式**: 绘制 P1 图片（应用编辑矩阵）、P1 区域边框、高度调整手柄和P1下方的背景色。
     * - **标准预览模式**: 使用 `SharedWallpaperRenderer.drawFrame()` 绘制完整的壁纸预览。
     * - **无图片或加载中**: 使用 `SharedWallpaperRenderer.drawPlaceholder()` 绘制占位符。
     *
     * @param canvas 要绘制的 Canvas 对象。
     */
    override fun onDraw(canvas: Canvas) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        val currentWallBitmaps = wallpaperBitmaps

        // --- 辅助方法，用于构建传递给 SharedRenderer 的 WallpaperConfig ---
        // 你可以将这个辅助方法移到 WallpaperPreviewView 类的顶层或者作为一个私有方法
        fun buildConfigForRenderer(currentTransformForStyleB: Matrix? = null): SharedWallpaperRenderer.WallpaperConfig {
            // 确保 currentP1StyleType, currentStyleBP1FocusX 等都是 WallpaperPreviewView 的成员变量，
            // 并且它们的值是从 ViewModel 同步过来的，或者在 P1 编辑模式下被更新。
            return SharedWallpaperRenderer.WallpaperConfig(
                screenWidth = viewWidth,
                screenHeight = viewHeight,
                page1BackgroundColor = selectedBackgroundColor,
                page1ImageHeightRatio = if (currentP1StyleType == 1) 1.0f else currentEffectiveP1HeightRatio, // 样式B P1高度是全屏
                currentXOffset = currentPreviewXOffset,
                numVirtualPages = numVirtualPages,
                p1OverlayFadeTransitionRatio = currentP1OverlayFadeRatio,
                scrollSensitivityFactor = currentScrollSensitivity,
                normalizedInitialBgScrollOffset = currentNormalizedInitialBgScrollOffset,
                p2BackgroundFadeInRatio = currentP2BackgroundFadeInRatio,
                // 样式B的P1前景通常没有独立的投影或底部融入，这些是样式A的特性
                p1ShadowRadius = if (currentP1StyleType == 1) 0f else this.currentP1ShadowRadius,
                p1ShadowDx = if (currentP1StyleType == 1) 0f else this.currentP1ShadowDx,
                p1ShadowDy = if (currentP1StyleType == 1) 0f else this.currentP1ShadowDy,
                p1ShadowColor = this.currentP1ShadowColor, // 颜色可以保持，但半径为0则无效
                p1ImageBottomFadeHeight = if (currentP1StyleType == 1) 0f else this.currentP1ImageBottomFadeHeight,

                p1StyleType = this.currentP1StyleType,
                styleBMaskAlpha = currentStyleBMaskAlpha,
                styleBRotationParamA = currentStyleBRotationParamA,
                styleBGapSizeRatio = currentStyleBGapSizeRatio,
                styleBGapPositionYRatio = currentStyleBGapPositionYRatio,
                styleBUpperMaskMaxRotation = currentStyleBUpperMaskMaxRotation,
                styleBLowerMaskMaxRotation = currentStyleBLowerMaskMaxRotation,
                // 样式B的P1背景焦点和缩放，这些由 PreviewView 内部的 currentStyleBP1... 变量提供
                styleBP1FocusX = this.currentStyleBP1FocusX,
                styleBP1FocusY = this.currentStyleBP1FocusY,
                styleBP1ScaleFactor = this.currentStyleBP1ScaleFactor,
                styleBMasksHorizontallyFlipped = if (this.currentP1StyleType == 1) this.currentStyleBMasksHorizontallyFlipped else false,
                // 样式B P1遮罩的模糊参数
                styleBModeP1MaskBlurRadius = currentStyleBP1MaskBlurRadius,
                styleBModeP1MaskBlurDownscale = currentStyleBP1MaskBlurDownscale,
                styleBModeP1MaskBlurIterations = currentStyleBP1MaskBlurIterations,
                // 关键：传递P1背景图的变换矩阵
                styleBP1BackgroundTransform = currentTransformForStyleB
            )
        }


        if (isTransitioningFromEditMode && currentWallBitmaps?.sourceSampledBitmap != null) {
            val sourceToDraw = currentWallBitmaps.sourceSampledBitmap!!
            canvas.drawColor(Color.DKGRAY) // 过渡时背景可以简单处理

            canvas.save()
            // 检查退出前是否是样式B，如果是，则裁剪区域是全屏
            // 这个状态可能需要额外记录，或者基于 transitionMatrix 来判断
            // 简单起见，如果 transitionMatrix 存在，我们假设它是P1编辑状态的延续
            // 更准确的做法是记录退出编辑前的样式类型
            val clipRectForTransition = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat()) // 假设P1编辑总是全屏目标
            // 如果你的样式A编辑区域不是全屏，这里需要更复杂的逻辑判断是从哪个样式退出的
            // 或者，setP1FocusEditMode(false)时，根据当前样式设置一个标记
            // 此处为了简化，先统一用全屏裁剪，具体效果可能需要调整
            canvas.clipRect(clipRectForTransition)
            canvas.concat(transitionMatrix) // 应用过渡开始时保存的P1编辑矩阵
            canvas.drawBitmap(sourceToDraw, 0f, 0f, p1EditContentPaint) // 绘制源图
            canvas.restore()

            // 对于样式B，下方的纯色背景意义不大，因为P1是全屏的
            if (currentP1StyleType != 1) { // 只为样式A绘制
                p1OverlayBgPaint.color = selectedBackgroundColor
                if (p1DisplayRectView.bottom < viewHeight) { // p1DisplayRectView 应该反映样式A的P1区域
                    canvas.drawRect(
                        0f,
                        p1DisplayRectView.bottom,
                        viewWidth.toFloat(),
                        viewHeight.toFloat(),
                        p1OverlayBgPaint
                    )
                }
            }
        } else if (isInP1EditMode && currentWallBitmaps?.sourceSampledBitmap != null) {
            // P1编辑模式
            if (currentP1StyleType == 1 /* STYLE_B */) {
                // 样式B的P1编辑模式: 直接让 SharedRenderer 绘制，并传递当前的 p1EditMatrix
                // p1EditMatrix 此时作用于全屏P1背景图
                val editConfig = buildConfigForRenderer(currentTransformForStyleB = Matrix(p1EditMatrix))
                SharedWallpaperRenderer.drawFrame(canvas, editConfig, currentWallBitmaps)
                // 样式B编辑模式下通常不绘制额外的边框或高度调整手柄
            } else { // 样式A的P1编辑模式
                val sourceBitmapToDraw = currentWallBitmaps.sourceSampledBitmap!!
                canvas.drawColor(Color.DKGRAY)
                canvas.save()
                canvas.clipRect(p1DisplayRectView) // p1DisplayRectView 应该是样式A的P1区域
                canvas.concat(p1EditMatrix)
                canvas.drawBitmap(sourceBitmapToDraw, 0f, 0f, p1EditContentPaint)
                canvas.restore()

                canvas.drawRect(p1DisplayRectView, p1EditBorderPaint)
                // 绘制P1高度调整手柄 (如果P1区域高度足够大)
                if (p1DisplayRectView.height() > touchSlop * p1HeightResizeTouchSlopFactor * 1.2f) {
                    canvas.drawRoundRect(
                        p1HeightResizeHandleRect,
                        5f * resources.displayMetrics.density,
                        5f * resources.displayMetrics.density,
                        p1HeightResizeHandlePaint
                    )
                }
                // 绘制P1区域下方的背景色
                p1OverlayBgPaint.color = selectedBackgroundColor
                if (p1DisplayRectView.bottom < viewHeight) {
                    canvas.drawRect(
                        0f,
                        p1DisplayRectView.bottom,
                        viewWidth.toFloat(),
                        viewHeight.toFloat(),
                        p1OverlayBgPaint
                    )
                }
            }
        } else { // 标准预览模式 (非编辑、非过渡)
            if (currentWallBitmaps?.sourceSampledBitmap != null) {
                var transformForStyleBNormalPreview: Matrix? = null
                if (currentP1StyleType == 1 /* STYLE_B */) {
                    transformForStyleBNormalPreview = Matrix()
                    // 使用 SharedWallpaperRenderer 中的静态/伴生对象方法
                    SharedWallpaperRenderer.calculateMatrixFromFocusAndScale(
                        currentWallBitmaps.sourceSampledBitmap,
                        viewWidth.toFloat(), viewHeight.toFloat(),
                        this.currentStyleBP1FocusX, // 使用 WallpaperPreviewView 内部存储的样式B焦点
                        this.currentStyleBP1FocusY,
                        this.currentStyleBP1ScaleFactor, // 使用 WallpaperPreviewView 内部存储的样式B缩放
                        transformForStyleBNormalPreview
                    )
                }
                val previewConfig = buildConfigForRenderer(currentTransformForStyleB = transformForStyleBNormalPreview)
                SharedWallpaperRenderer.drawFrame(canvas, previewConfig, currentWallBitmaps)
            } else {
                // 绘制占位符
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

    /**
     * 设置高级配置参数的值。
     * 当这些参数变化时，可能会触发位图的重新加载或特定部分的更新（如模糊效果）。
     *
     * @param scrollSensitivity 滚动灵敏度。
     * @param p1OverlayFadeRatio P1 前景淡出比例。
     * @param backgroundBlurRadius P2 全局背景模糊半径。
     * @param snapAnimationDurationMs 页面吸附动画时长。
     * @param normalizedInitialBgScrollOffset P2 背景初始偏移。
     * @param p2BackgroundFadeInRatio P2 背景淡入过渡比例。
     * @param blurDownscaleFactor P2 全局背景模糊降采样因子。
     * @param blurIterations P2 全局背景模糊迭代次数。
     * @param p1ShadowRadius 样式 A 的 P1 图片投影半径。
     * @param p1ShadowDx 样式 A 的 P1 图片投影X偏移。
     * @param p1ShadowDy 样式 A 的 P1 图片投影Y偏移。
     * @param p1ShadowColor 样式 A 的 P1 图片投影颜色。
     * @param p1ImageBottomFadeHeight 样式 A 的 P1 图片底部融入高度。
     * @param styleBP1FocusX 样式 B 的 P1 独立背景图焦点 X。
     * @param p1StyleType 当前 P1 层样式类型 (0=样式A, 1=样式B)。
     * @param styleBMaskAlpha 样式 B 遮罩透明度。
     * @param styleBRotationParamA 样式 B 旋转参数 A。
     * @param styleBGapSizeRatio 样式 B 间隔大小比例。
     * @param styleBGapPositionYRatio 样式 B 间隔位置Y轴比例。
     * @param styleBUpperMaskMaxRotation 样式 B 上遮罩最大旋转角度。
     * @param styleBLowerMaskMaxRotation 样式 B 下遮罩最大旋转角度。
     * @param styleBP1FocusY 样式 B 的 P1 独立背景图焦点 Y。
     * @param styleBP1ScaleFactor 样式 B 的 P1 独立背景图内容缩放因子。
     * @param p1FocusY 样式 A 的 P1 图片焦点 Y。
     * @param p1FocusX 样式 A 的 P1 图片焦点 X。
     * @param p1ContentScaleFactor 样式 A 的 P1 图片内容缩放因子。
     * @param page1ImageHeightRatio 样式 A 的 P1 图片高度比例。
     * @param styleBMasksHorizontallyFlipped 样式 B 遮罩是否水平翻转。
     * @param styleBP1MaskBlurRadiusVal 样式 B P1 遮罩专属模糊半径。
     * @param styleBP1MaskBlurDownscaleVal 样式 B P1 遮罩专属模糊降采样因子。
     * @param styleBP1MaskBlurIterationsVal 样式 B P1 遮罩专属模糊迭代次数。
     */
    fun setConfigValues(
        scrollSensitivity: Float,
        p1OverlayFadeRatio: Float,
        backgroundBlurRadius: Float, // P2 全局背景模糊
        snapAnimationDurationMs: Long,
        normalizedInitialBgScrollOffset: Float,
        p2BackgroundFadeInRatio: Float,
        blurDownscaleFactor: Float, // P2 全局背景降采样
        blurIterations: Int,        // P2 全局背景迭代
        p1ShadowRadius: Float,
        p1ShadowDx: Float,
        p1ShadowDy: Float,
        p1ShadowColor: Int,
        p1ImageBottomFadeHeight: Float,
        // 样式 B 非模糊参数
        styleBP1FocusX: Float,
        p1StyleType: Int,
        styleBMaskAlpha: Float,
        styleBRotationParamA: Float,
        styleBGapSizeRatio: Float,
        styleBGapPositionYRatio: Float,
        styleBUpperMaskMaxRotation: Float,
        styleBLowerMaskMaxRotation: Float,
        styleBP1FocusY: Float,
        styleBP1ScaleFactor: Float,
        // 样式 A 参数
        p1FocusY: Float, // 对应 currentNormalizedFocusY
        p1FocusX: Float, // 对应 currentNormalizedFocusX
        p1ContentScaleFactor: Float, // 对应 currentP1ContentScaleFactor (用于样式A的P1内容缩放)
        page1ImageHeightRatio: Float, // 对应 nonEditModePage1ImageHeightRatio
        // 样式 B 翻转
        styleBMasksHorizontallyFlipped: Boolean,
        // 新增：样式 B P1 遮罩专属模糊参数
        styleBP1MaskBlurRadiusVal: Float,
        styleBP1MaskBlurDownscaleVal: Float,
        styleBP1MaskBlurIterationsVal: Int
    ) {
        // --- 1. 保存旧的参数值，用于比较是否发生变化 ---
        val oldP1ImageBottomFadeHeight = this.currentP1ImageBottomFadeHeight
        val oldP1ShadowRadius = this.currentP1ShadowRadius
        val oldP1ShadowDx = this.currentP1ShadowDx
        val oldP1ShadowDy = this.currentP1ShadowDy
        // (如果还有其他仅影响重绘的参数，也在此保存其旧值)

        // 保存旧的 P2 全局背景模糊参数
        val oldGlobalBgBlurR = this.currentBackgroundBlurRadius
        val oldGlobalBgBlurDF = this.currentBlurDownscaleFactor
        val oldGlobalBgBlurIt = this.currentBlurIterations

        // 新增：保存旧的样式 B P1 遮罩专属模糊参数值
        val oldStyleBP1MaskBlurR = this.currentStyleBP1MaskBlurRadius
        val oldStyleBP1MaskBlurDF = this.currentStyleBP1MaskBlurDownscale
        val oldStyleBP1MaskBlurIt = this.currentStyleBP1MaskBlurIterations

        // --- 2. 更新当前View持有的所有参数到成员变量 ---
        this.currentScrollSensitivity = scrollSensitivity
        this.currentP1OverlayFadeRatio = p1OverlayFadeRatio
        this.currentBackgroundBlurRadius = backgroundBlurRadius // P2 全局背景模糊
        this.currentSnapAnimationDurationMs = snapAnimationDurationMs
        this.currentNormalizedInitialBgScrollOffset = normalizedInitialBgScrollOffset
        this.currentP2BackgroundFadeInRatio = p2BackgroundFadeInRatio
        this.currentBlurDownscaleFactor = blurDownscaleFactor     // P2 全局背景降采样
        this.currentBlurIterations = blurIterations               // P2 全局背景迭代
        this.currentP1ShadowRadius = p1ShadowRadius
        this.currentP1ShadowDx = p1ShadowDx
        this.currentP1ShadowDy = p1ShadowDy
        this.currentP1ShadowColor = p1ShadowColor
        this.currentP1ImageBottomFadeHeight = p1ImageBottomFadeHeight

        // 更新样式 B 非模糊参数
        this.currentP1StyleType = p1StyleType // 这个决定了渲染路径
        this.currentStyleBMaskAlpha = styleBMaskAlpha
        this.currentStyleBRotationParamA = styleBRotationParamA
        this.currentStyleBGapSizeRatio = styleBGapSizeRatio
        this.currentStyleBGapPositionYRatio = styleBGapPositionYRatio
        this.currentStyleBUpperMaskMaxRotation = styleBUpperMaskMaxRotation
        this.currentStyleBLowerMaskMaxRotation = styleBLowerMaskMaxRotation
        this.currentStyleBP1FocusX = styleBP1FocusX // 样式 B 的 P1 背景图焦点
        this.currentStyleBP1FocusY = styleBP1FocusY
        this.currentStyleBP1ScaleFactor = styleBP1ScaleFactor
        this.currentStyleBMasksHorizontallyFlipped = styleBMasksHorizontallyFlipped

        // 更新样式 A 的参数 (这些主要影响非编辑模式下的P1顶图生成，或编辑模式的初始值)
        this.currentNormalizedFocusX = p1FocusX // 注意：这是 WallpaperPreviewView 内的通用焦点变量，主要用于样式 A 或编辑模式焦点
        this.currentNormalizedFocusY = p1FocusY
        this.currentP1ContentScaleFactor = p1ContentScaleFactor // 这是样式 A 的内容缩放因子
        this.nonEditModePage1ImageHeightRatio = page1ImageHeightRatio // 样式 A 的 P1 图片高度比例

        // 新增：更新样式 B P1 遮罩专属模糊参数的成员变量
        this.currentStyleBP1MaskBlurRadius = styleBP1MaskBlurRadiusVal
        this.currentStyleBP1MaskBlurDownscale = styleBP1MaskBlurDownscaleVal
        this.currentStyleBP1MaskBlurIterations = styleBP1MaskBlurIterationsVal

        // --- 3. 检测参数变化并执行相应操作 ---

        // 标记那些只需要立即重绘的参数是否发生了变化
        val simpleRedrawParamsChanged = oldP1ImageBottomFadeHeight != this.currentP1ImageBottomFadeHeight ||
                oldP1ShadowRadius != this.currentP1ShadowRadius ||
                oldP1ShadowDx != this.currentP1ShadowDx ||
                oldP1ShadowDy != this.currentP1ShadowDy // 添加其他仅影响绘制的参数比较

        // 检测 P2 全局背景模糊参数是否发生变化
        val globalP2BlurParamsChanged = oldGlobalBgBlurR != this.currentBackgroundBlurRadius ||
                oldGlobalBgBlurDF != this.currentBlurDownscaleFactor ||
                oldGlobalBgBlurIt != this.currentBlurIterations

        // 新增：检测样式 B P1 遮罩专属模糊参数是否发生变化
        val styleBP1MaskBlurParamsChanged = oldStyleBP1MaskBlurR != this.currentStyleBP1MaskBlurRadius ||
                oldStyleBP1MaskBlurDF != this.currentStyleBP1MaskBlurDownscale ||
                oldStyleBP1MaskBlurIt != this.currentStyleBP1MaskBlurIterations

        var triggerFullReload = false // 标记是否最终需要完整重载

        if (this.imageUri != null) { // 只有在有图片URI时才进行位图相关的更新
            // 优先处理可能导致完整重载的情况 (例如，如果样式类型变化，或者某些关键位图缺失)
            // (此处的逻辑可以根据具体需求调整，例如，样式类型变化是否一定需要完整重载，还是可以只更新部分位图)
            if (wallpaperBitmaps?.sourceSampledBitmap == null) { // 如果最基础的源图都没有，必须完整重载
                triggerFullReload = true
                Log.d(TAG, "setConfigValues: Source bitmap is null, forcing full reload.")
            } else {
                // 源图存在，再检查局部更新
                if (globalP2BlurParamsChanged) {
                    if (wallpaperBitmaps?.scrollingBackgroundBitmap != null) {
                        Log.d(TAG, "setConfigValues: Global P2 Blur params changed, updating P2 blur for preview.")
                        updateOnlyBlurredBackgroundForPreviewAsync() // (使用 blurTaskProcessor)
                    } else {
                        // P2 滚动背景图缺失，可能也需要完整重载才能生成它
                        Log.w(TAG, "setConfigValues: Global P2 Blur params changed, but scrollingBackgroundBitmap is null. Flagging for full reload.")
                        triggerFullReload = true
                    }
                }

                if (styleBP1MaskBlurParamsChanged) {
                    // sourceSampledBitmap 是生成 styleBBlurredBitmap 的基础，所以检查它
                    if (wallpaperBitmaps?.sourceSampledBitmap != null) {
                        Log.d(TAG, "setConfigValues: Style B P1 Mask Blur params changed, updating Style B P1 Mask blur for preview.")
                        updateStyleBP1MaskBlurredBitmapForPreviewAsync()
                    } else {
                        // 源图缺失，无法生成样式B的模糊图，也应完整重载
                        Log.w(TAG, "setConfigValues: Style B P1 Mask Blur params changed, but sourceSampledBitmap is null. Flagging for full reload.")
                        triggerFullReload = true
                    }
                }

                // 如果没有任何模糊参数变化，并且也不是因为简单重绘参数变化
                if (!triggerFullReload && !globalP2BlurParamsChanged && !styleBP1MaskBlurParamsChanged) {
                    if (simpleRedrawParamsChanged) {
                        Log.d(TAG, "setConfigValues: Simple redraw parameters changed. Invalidating.")
                        invalidate()
                    } else {
                        // 如果还有其他非模糊、非简单重绘的参数也可能影响位图（例如P1高度比例影响P1顶图）
                        // 但当前setConfigValues主要处理高级参数，P1高度/焦点/缩放通常由单独的setter处理
                        // 此处可以假设，如果不是上述具体更新，则重绘。
                        // 或者，如果担心有遗漏，可以更保守地触发完整重载，但这可能影响性能。
                        // 当前的 WallpaperPreviewView 中，page1ImageHeightRatio, normalizedFocus, p1ContentScaleFactor 都有自己的 setter
                        // 它们会调用 updateOnlyPage1TopCroppedBitmap。
                        // 所以这里，如果上述条件都不满足，很可能只需要 invalidate()。
                        Log.d(TAG, "setConfigValues: No specific bitmap updates triggered by blur or simple params. Invalidating for other changes.")
                        invalidate()
                    }
                }
            }

            if (triggerFullReload) {
                Log.d(TAG, "setConfigValues: Final decision: Conditions require full bitmap reload for preview.")
                loadFullBitmapsFromUri(this.imageUri, true) // forceInternalReload = true
            } else if (!globalP2BlurParamsChanged && !styleBP1MaskBlurParamsChanged && !simpleRedrawParamsChanged) {
                // 如果以上都不是，但我们走到了这里（意味着imageUri存在，sourceBitmap也应该存在）
                // 这通常意味着是一些不直接影响位图生成的参数变化了（如滚动灵敏度等），只需要重绘
                invalidate()
            }

        } else { // 没有图片URI
            invalidate() // 重绘占位符
        }
    }

    /**
     * 异步更新预览视图中的背景模糊效果。
     * 此方法使用一个任务队列 (`blurTaskQueue`) 和一个专门的协程处理器 (`blurTaskProcessor`)
     * 来智能地调度模糊任务，以优化性能并响应用户的快速参数调整。
     *
     * 如果队列中有多个待处理的模糊任务，它可能会跳过中间任务，直接处理最新的任务，
     * 特别是当检测到单个模糊任务处理时间过长时。
     */
    private fun updateOnlyBlurredBackgroundForPreviewAsync() {

        val baseForBlur = wallpaperBitmaps?.scrollingBackgroundBitmap
        if (baseForBlur == null || viewWidth <= 0 || viewHeight <= 0 || imageUri == null) {
            if (imageUri != null) {
                Log.w(
                    TAG,
                    "updateOnlyBlurredBackgroundForPreviewAsync: Base scrolling bitmap is null. Attempting full reload."
                )
                loadFullBitmapsFromUri(this.imageUri, true)
            } else {
                invalidate()
            }
            return
        }

        Log.d(
            TAG,
            "updateOnlyBlurredBackgroundForPreviewAsync: Queuing P2 Background blur task with R=$currentBackgroundBlurRadius, DF=$currentBlurDownscaleFactor, It=$currentBlurIterations"
        )

        val newTask = BlurTask(
            target = BlurTarget.P2_BACKGROUND, // <--- 指定目标类型
            radius = currentBackgroundBlurRadius,
            downscaleFactor = currentBlurDownscaleFactor,
            iterations = currentBlurIterations
        )

        if (blurTaskProcessor == null || blurTaskProcessor?.isActive != true) {
            startBlurTaskProcessor()
        }

        viewScope.launch {
            try {
                blurTaskQueue.send(newTask)
                Log.d(TAG, "Added P2 Background blur task to queue: $newTask")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send P2 Background blur task to queue", e)
            }
        }
    }
    /**
     * 异步仅更新样式 B 的 P1 遮罩模糊背景图 (wallpaperBitmaps.styleBBlurredBitmap) 以供预览。
     * 当样式 B 的专属模糊参数发生变化时调用此方法。
     */
    private fun updateStyleBP1MaskBlurredBitmapForPreviewAsync() {
        val sourceBitmapToUse = wallpaperBitmaps?.sourceSampledBitmap
        if (sourceBitmapToUse == null || viewWidth <= 0 || viewHeight <= 0 || imageUri == null) {
            Log.w(TAG, "updateStyleBP1MaskBlurredBitmapForPreviewAsync: Conditions not met for queuing blur task.")
            if (imageUri != null && sourceBitmapToUse == null) {
                loadFullBitmapsFromUri(imageUri, true)
            }
            return
        }

        Log.d(
            TAG,
            "updateStyleBP1MaskBlurredBitmapForPreviewAsync: Queuing Style B P1 Mask blur task with R=$currentStyleBP1MaskBlurRadius, DF=$currentStyleBP1MaskBlurDownscale, It=$currentStyleBP1MaskBlurIterations"
        )

        val newTask = BlurTask(
            target = BlurTarget.STYLE_B_P1_MASK, // <--- 指定目标类型
            radius = currentStyleBP1MaskBlurRadius,
            downscaleFactor = currentStyleBP1MaskBlurDownscale,
            iterations = currentStyleBP1MaskBlurIterations
        )

        // 确保模糊任务处理器正在运行
        if (blurTaskProcessor == null || blurTaskProcessor?.isActive != true) {
            startBlurTaskProcessor()
        }

        // 将新任务发送到队列
        viewScope.launch {
            try {
                blurTaskQueue.send(newTask)
                Log.d(TAG, "Added Style B P1 Mask blur task to queue: $newTask")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send Style B P1 Mask blur task to queue", e)
            }
        }
    }


    /**
     * 启动模糊任务处理器协程 (`blurTaskProcessor`)。
     * 此协程会持续从 `blurTaskQueue` 中接收并处理模糊任务。
     * 它实现了智能调度策略：
     * - 顺序处理任务。
     * - 如果检测到上一个任务处理时间超过阈值 (`BLUR_TASK_TIME_THRESHOLD`)，
     * 则会清空队列中所有累积的旧任务，直接跳到并处理最新收到的任务，
     * 以确保预览能尽快响应用户的最新输入，避免因处理旧的、已过时的模糊请求而卡顿。
     */
    private fun startBlurTaskProcessor() {
        blurTaskProcessor?.cancel()
        blurTaskProcessor = viewScope.launch {
            Log.d(TAG, "Starting blur task processor coroutine.")
            var lastProcessedTaskTimestamp = 0L // 用于比较任务新旧，如果任务本身没有时间戳
            var slowTaskDetectedPreviously = false

            try {
                for (taskToProcess in blurTaskQueue) { // 循环从Channel接收任务
                    ensureActive()

                    var currentTask = taskToProcess

                    if (slowTaskDetectedPreviously) {
                        var newestTaskInQueue: BlurTask? = null
                        while (true) {
                            val received = blurTaskQueue.tryReceive().getOrNull() ?: break
                            newestTaskInQueue = received
                        }
                        if (newestTaskInQueue != null) {
                            Log.d(
                                TAG,
                                "Slow task detected previously, jumping to newest task in queue: $newestTaskInQueue"
                            )
                            currentTask = newestTaskInQueue
                        }
                        slowTaskDetectedPreviously = false
                    }

                    val taskStartTime = SystemClock.elapsedRealtime()
                    var newBlurredBitmap: Bitmap? = null
                    var bitmapChanged = false

                    try {
                        Log.d(TAG, "Blur task: Processing $currentTask")

                        when (currentTask.target) {
                            BlurTarget.P2_BACKGROUND -> {
                                val baseBitmapForP2 = wallpaperBitmaps?.scrollingBackgroundBitmap
                                if (baseBitmapForP2 == null || baseBitmapForP2.isRecycled) {
                                    Log.w(TAG, "Blur task (P2_BACKGROUND): Base scrollingBitmap became null or recycled. Skipping.")
                                    continue // 跳过此任务
                                }
                                newBlurredBitmap = withContext(Dispatchers.IO) {
                                    ensureActive()
                                    SharedWallpaperRenderer.regenerateBlurredBitmap(
                                        context,
                                        baseBitmapForP2,
                                        baseBitmapForP2.width, // 目标尺寸与基础图一致
                                        baseBitmapForP2.height,
                                        currentTask.radius,
                                        currentTask.downscaleFactor,
                                        currentTask.iterations
                                    )
                                }
                                ensureActive()
                                val oldBlurred = wallpaperBitmaps?.blurredScrollingBackgroundBitmap
                                if (this@WallpaperPreviewView.imageUri != null && wallpaperBitmaps?.scrollingBackgroundBitmap == baseBitmapForP2) {
                                    wallpaperBitmaps?.blurredScrollingBackgroundBitmap = newBlurredBitmap
                                    if (oldBlurred != newBlurredBitmap) {
                                        oldBlurred?.recycle()
                                        bitmapChanged = true
                                    }
                                    Log.d(TAG, "Blur task (P2_BACKGROUND): Successfully updated blurred scrolling background.")
                                } else {
                                    newBlurredBitmap?.recycle() // 条件不匹配，丢弃
                                    Log.d(TAG, "Blur task (P2_BACKGROUND): Conditions changed. Discarding result.")
                                }
                            }

                            BlurTarget.STYLE_B_P1_MASK -> {
                                val baseBitmapForStyleB = wallpaperBitmaps?.sourceSampledBitmap
                                if (baseBitmapForStyleB == null || baseBitmapForStyleB.isRecycled) {
                                    Log.w(TAG, "Blur task (STYLE_B_P1_MASK): Base sourceSampledBitmap became null or recycled. Skipping.")
                                    continue // 跳过此任务
                                }
                                newBlurredBitmap = withContext(Dispatchers.IO) {
                                    ensureActive()
                                    // regenerateStyleBBlurredBitmap 包含了为 Style B 准备全屏源图的逻辑
                                    SharedWallpaperRenderer.regenerateStyleBBlurredBitmap(
                                        context,
                                        baseBitmapForStyleB,
                                        viewWidth, // 需要 View 的当前尺寸
                                        viewHeight,
                                        currentTask.radius,
                                        currentTask.downscaleFactor,
                                        currentTask.iterations
                                    )
                                }
                                ensureActive()
                                val oldStyleBBlurred = wallpaperBitmaps?.styleBBlurredBitmap
                                if (this@WallpaperPreviewView.imageUri != null && wallpaperBitmaps?.sourceSampledBitmap == baseBitmapForStyleB) {
                                    wallpaperBitmaps?.styleBBlurredBitmap = newBlurredBitmap
                                    if (oldStyleBBlurred != newBlurredBitmap) {
                                        oldStyleBBlurred?.recycle()
                                        bitmapChanged = true
                                    }
                                    Log.d(TAG, "Blur task (STYLE_B_P1_MASK): Successfully updated Style B P1 mask blurred background.")
                                } else {
                                    newBlurredBitmap?.recycle() // 条件不匹配，丢弃
                                    Log.d(TAG, "Blur task (STYLE_B_P1_MASK): Conditions changed. Discarding result.")
                                }
                            }
                        }

                        val processingTime = SystemClock.elapsedRealtime() - taskStartTime
                        slowTaskDetectedPreviously = processingTime > BLUR_TASK_TIME_THRESHOLD
                        if (slowTaskDetectedPreviously) {
                            Log.d(TAG, "Blur task: SLOW task detected! Type: ${currentTask.target}, Time: ${processingTime}ms for $currentTask")
                        } else {
                            Log.d(TAG, "Blur task: Completed. Type: ${currentTask.target}, Time: ${processingTime}ms for $currentTask")
                        }

                        if (bitmapChanged && isActive) { // 只有当位图实际发生变化且协程仍活动时才重绘
                            invalidate()
                        }

                    } catch (e: CancellationException) {
                        Log.d(TAG, "Blur task for ${currentTask.target} ($currentTask) was cancelled during IO.")
                        newBlurredBitmap?.recycle()
                        if (isActive) throw e // 重新抛出，让外层捕获并停止处理器
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing blur task ${currentTask.target} ($currentTask)", e)
                        newBlurredBitmap?.recycle()
                        // 根据类型选择性清除失败的模糊图
                        if (isActive && this@WallpaperPreviewView.imageUri != null) {
                            when (currentTask.target) {
                                BlurTarget.P2_BACKGROUND -> if (wallpaperBitmaps?.scrollingBackgroundBitmap != null) wallpaperBitmaps?.blurredScrollingBackgroundBitmap = null
                                BlurTarget.STYLE_B_P1_MASK -> if (wallpaperBitmaps?.sourceSampledBitmap != null) wallpaperBitmaps?.styleBBlurredBitmap = null
                            }
                            if (bitmapChanged) invalidate() // 如果之前标记了变化，即使出错也尝试重绘一次
                        }
                    }
                    lastProcessedTaskTimestamp = currentTask.timestamp // 更新时间戳
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Blur task processor coroutine was cancelled.")
            } catch (e: Exception) {
                Log.e(TAG, "Exception in blur task processor coroutine", e)
            } finally {
                Log.d(TAG, "Blur task processor coroutine stopped.")
                if (coroutineContext[Job] == blurTaskProcessor) {
                    blurTaskProcessor = null
                }
            }
        }
    }

    /**
     * 设置当前预览的图片 URI。
     *
     * - 如果新的 URI 与当前 URI 相同且已有源位图 (且非强制重载)，则可能只更新 P1 顶图或重绘。
     * - 如果 URI 发生变化或需要强制重载，会取消正在进行的图片加载任务，并：
     * - 如果新 URI 不为 null，则调用 `loadFullBitmapsFromUri()` 加载新图片的所有位图。
     * - 如果新 URI 为 null，则清除所有位图资源并重绘（显示占位符）。
     * - 如果进入此方法时处于 P1 编辑模式，会请求外部取消编辑模式。
     *
     * @param uri 新的图片 URI，如果为 null 表示清除图片。
     * @param forceReload 是否强制重新加载所有位图，即使 URI 未改变。默认为 false。
     */
    fun setImageUri(uri: Uri?, forceReload: Boolean = false) {
        Log.d(
            TAG,
            "setImageUri called with URI: $uri. CurrentEditMode: $isInP1EditMode, ForceReload: $forceReload"
        )
        // 如果在P1编辑模式下设置新图片，则先退出编辑模式
        if (isInP1EditMode) {
            onRequestActionCallback?.invoke(PreviewViewAction.REQUEST_CANCEL_P1_EDIT_MODE)
            // setP1FocusEditMode(false) // 或者让外部回调后处理
        }

        // 情况1: URI 未变，已有源图，且非强制重载
        if (!forceReload && this.imageUri == uri && uri != null && wallpaperBitmaps?.sourceSampledBitmap != null) {
            Log.d(
                TAG,
                "setImageUri: URI unchanged and source bitmap exists. Updating P1 top or invalidating."
            )
            // 如果不在编辑/过渡模式，确保P1顶图是最新的
            if (!isInP1EditMode && !isTransitioningFromEditMode) {
                updateOnlyPage1TopCroppedBitmap(
                    nonEditModePage1ImageHeightRatio,
                    wallpaperBitmaps!!.sourceSampledBitmap!!,
                    this.currentP1ContentScaleFactor
                )
            } else { // 在编辑/过渡模式下，仅重绘 (P1编辑模式有自己的绘制逻辑)
                invalidate()
            }
            return
        }

        // 取消任何正在进行的位图加载/更新任务
        fullBitmapLoadingJob?.cancel()
        topBitmapUpdateJob?.cancel()
        // blurUpdateJob?.cancel() // blurUpdateJob 已被 blurTaskProcessor 替代，blurTaskQueue 会在 View Detached 时关闭

        // 如果是强制重载，或者URI实际发生了变化，则回收旧的位图资源
        if (forceReload || this.imageUri != uri) {
            wallpaperBitmaps?.recycleInternals()
            wallpaperBitmaps = null // 清除引用，以便后续判断是否需要加载
        }

        this.imageUri = uri // 更新当前URI
        currentPreviewXOffset = 0f // 新图片从第一页开始显示
        // 停止任何页面滚动或P1内容滚动动画
        if (!pageScroller.isFinished) {
            pageScroller.abortAnimation()
        }
        if (!p1ContentScroller.isFinished) {
            p1ContentScroller.forceFinished(true)
        }

        if (uri != null) { // 如果有新的有效URI
            invalidate() // 先重绘一次（可能会显示加载中占位符）
            loadFullBitmapsFromUri(uri, true) // 加载新图片的所有位图，forceInternalReload=true确保内部逻辑正确处理
        } else { // 如果新URI为null (清除图片)
            // wallpaperBitmaps 已在上文被设为 null (如果URI变化或强制重载)
            // 如果之前没有设为null (例如URI本来就是null，再次调用setImageUri(null))，这里再确认一下
            if (wallpaperBitmaps != null) {
                wallpaperBitmaps?.recycleInternals()
                wallpaperBitmaps = null
            }
            invalidate() // 重绘以显示占位符
        }
    }

    /**
     * 异步从给定的 URI 加载所有渲染所需的位图资源。
     * 包括：采样后的源位图、P1 顶部裁剪图、滚动背景图及其模糊版本。
     *
     * 此方法会取消任何正在进行的 `fullBitmapLoadingJob` 或 `topBitmapUpdateJob`。
     * 如果 `forceInternalReload` 为 true 或当前没有源位图，会先回收并清除现有的 `wallpaperBitmaps`。
     * 加载完成后，如果 URI 未发生变化，则更新 `wallpaperBitmaps` 并回收旧资源。
     * 如果在 P1 编辑模式下加载完成，会根据当前焦点重置编辑矩阵。
     * 如果不在编辑模式，则会尝试更新 P1 顶部裁剪图。
     *
     * @param uriToLoad 要加载的图片的 URI。如果为 null，或者 View 尺寸无效，则会清除位图并重绘。
     * @param forceInternalReload 是否强制内部重新加载，即使 `wallpaperBitmaps` 中已有源图。
     * 主要用于确保在 View 尺寸变化或 URI 变化时，所有位图都被正确重新生成。
     */
    private fun loadFullBitmapsFromUri(uriToLoad: Uri?, forceInternalReload: Boolean = false) {
        // 如果 URI 为空或 View 尺寸无效，则无法加载
        if (uriToLoad == null || viewWidth <= 0 || viewHeight <= 0) {
            if (!forceInternalReload) { // 仅在非强制内部重载时才清除（避免不必要的清除）
                wallpaperBitmaps?.recycleInternals()
                wallpaperBitmaps = null
            }
            invalidate() // 重绘（可能会显示占位符）
            return
        }

        // 取消正在进行的图片加载/更新任务
        fullBitmapLoadingJob?.cancel()
        topBitmapUpdateJob?.cancel()
        // blurUpdateJob?.cancel(); // 已被 blurTaskProcessor 替代

        // 如果需要强制内部重载，或者当前没有有效的源位图，则先回收并清除旧的位图资源
        if (forceInternalReload || wallpaperBitmaps?.sourceSampledBitmap == null) {
            wallpaperBitmaps?.recycleInternals()
            wallpaperBitmaps = null // 清除引用，以便后续绘制占位符（如果需要）
            invalidate() // 重绘一次，通常会显示加载中占位符
        }

        // 启动协程执行异步加载
        fullBitmapLoadingJob = viewScope.launch {
            var newWpBitmaps: SharedWallpaperRenderer.WallpaperBitmaps? = null
            try {
                ensureActive() // 确保协程活动
                // 在 IO 调度器上执行耗时的位图加载和处理
                newWpBitmaps = withContext(Dispatchers.IO) {
                    ensureActive()
                    // 获取当前非编辑模式下的 P1 配置用于初始加载
                    val heightToUse = nonEditModePage1ImageHeightRatio
                    val focusXToUse = this@WallpaperPreviewView.currentNormalizedFocusX
                    val focusYToUse = this@WallpaperPreviewView.currentNormalizedFocusY
                    val scaleToUse = this@WallpaperPreviewView.currentP1ContentScaleFactor

                    // 调用共享渲染器加载和处理所有初始位图
                    SharedWallpaperRenderer.loadAndProcessInitialBitmaps( //
                        context,
                        uriToLoad,
                        viewWidth,
                        viewHeight,
                        heightToUse,
                        focusXToUse,
                        focusYToUse,
                        scaleToUse,
                        currentBackgroundBlurRadius,
                        currentBlurDownscaleFactor,
                        currentBlurIterations,
                        styleBP1MaskBlurRadius = currentStyleBP1MaskBlurRadius,
                        styleBP1MaskBlurDownscale = currentStyleBP1MaskBlurDownscale,
                        styleBP1MaskBlurIterations = currentStyleBP1MaskBlurIterations
                    )
                }
                ensureActive() // 返回主线程后再次检查活动状态

                val oldBitmaps = wallpaperBitmaps // 保存对旧位图资源的引用
                // 检查在异步加载期间，用户是否更改了图片 URI
                if (this@WallpaperPreviewView.imageUri == uriToLoad) { // 如果 URI 未变
                    wallpaperBitmaps = newWpBitmaps // 更新为新加载的位图资源
                    // 如果新旧位图资源不是同一个对象，则回收旧的（避免内存泄漏）
                    if (oldBitmaps != newWpBitmaps) oldBitmaps?.recycleInternals()

                    // 根据当前模式处理加载完成后的操作
                    if (isInP1EditMode && wallpaperBitmaps?.sourceSampledBitmap != null) {
                        // 如果在P1编辑模式，则根据当前焦点重置编辑矩阵
                        resetP1EditMatrixToFocus(
                            this@WallpaperPreviewView.currentNormalizedFocusX,
                            this@WallpaperPreviewView.currentNormalizedFocusY
                        )
                    } else if (!isInP1EditMode && !isTransitioningFromEditMode && wallpaperBitmaps?.sourceSampledBitmap != null) {
                        // 如果不在编辑/过渡模式，且有源图，则更新P1顶部裁剪图
                        updateOnlyPage1TopCroppedBitmap(
                            nonEditModePage1ImageHeightRatio,
                            wallpaperBitmaps!!.sourceSampledBitmap!!,
                            this@WallpaperPreviewView.currentP1ContentScaleFactor
                        )
                    } else if (isTransitioningFromEditMode) {
                        // 如果正处于从编辑模式退出的过渡状态，过渡逻辑会处理自身的完成和重绘
                    } else {
                        invalidate() // 其他情况，直接重绘
                    }
                } else { // 如果在加载期间 URI 发生了变化
                    newWpBitmaps?.recycleInternals() // 新加载的位图作废
                    // 如果当前 URI 已被清除 (变为 null)，则也清除旧的位图资源
                    if (this@WallpaperPreviewView.imageUri == null) {
                        oldBitmaps?.recycleInternals()
                        wallpaperBitmaps = null
                    }
                    // 如果 URI 变成了另一个值，则当前 loadFullBitmapsFromUri 的结果作废，等待针对新 URI 的加载
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Bitmap loading cancelled for $uriToLoad")
                newWpBitmaps?.recycleInternals() // 确保即使取消也回收可能已创建的位图
            } catch (e: Exception) {
                Log.e(TAG, "Async bitmap loading failed for $uriToLoad", e)
                newWpBitmaps?.recycleInternals()
                // 如果加载失败的是当前显示的 URI，则清除位图资源
                if (this@WallpaperPreviewView.imageUri == uriToLoad) {
                    wallpaperBitmaps?.recycleInternals()
                    wallpaperBitmaps = null
                }
            } finally {
                // 如果协程仍然活动且当前 Job 是 fullBitmapLoadingJob，则清空 Job 引用
                if (isActive && coroutineContext[Job] == fullBitmapLoadingJob) {
                    fullBitmapLoadingJob = null
                }
                // 如果协程活动，并且当前 URI 与加载的 URI 匹配，或者当前 URI 已为 null (表示已清除图片)，则重绘
                if (isActive && (this@WallpaperPreviewView.imageUri == uriToLoad || this@WallpaperPreviewView.imageUri == null)) {
                    invalidate()
                }
            }
        }
    }

    /**
     * 设置 P1 图片区域的高度比例 (仅在非 P1 编辑模式下生效)。
     * 如果传入的比例与当前值不同，会更新 `nonEditModePage1ImageHeightRatio`，
     * 重新计算 P1 显示区域，并根据情况更新 P1 顶部裁剪图或重绘。
     * 如果当前处于 P1 编辑模式，此方法会更新 `currentEditP1HeightRatio` 并触发 P1 配置更新。
     *
     * @param newRatio 新的 P1 高度比例，会被限制在 MIN_HEIGHT_RATIO 和 MAX_HEIGHT_RATIO 之间。
     */
    fun setPage1ImageHeightRatio(newRatio: Float) {
        val clampedRatio = newRatio.coerceIn(
            WallpaperConfigConstants.MIN_HEIGHT_RATIO,
            WallpaperConfigConstants.MAX_HEIGHT_RATIO
        ) //
        if (abs(nonEditModePage1ImageHeightRatio - clampedRatio) > 0.001f) { // 仅当值有显著变化时更新
            nonEditModePage1ImageHeightRatio = clampedRatio
            if (!isInP1EditMode && !isTransitioningFromEditMode) { // 非编辑模式
                calculateP1DisplayRectView() // 更新P1显示区域
                // 如果有图片和源图，则更新P1顶部裁剪图
                if (imageUri != null && wallpaperBitmaps?.sourceSampledBitmap != null) {
                    updateOnlyPage1TopCroppedBitmap(
                        nonEditModePage1ImageHeightRatio,
                        wallpaperBitmaps!!.sourceSampledBitmap!!,
                        this.currentP1ContentScaleFactor
                    )
                } else { // 否则仅重绘
                    invalidate()
                }
            } else if (isInP1EditMode) { // 如果在P1编辑模式下通过外部调用此方法
                currentEditP1HeightRatio = clampedRatio // 更新编辑模式下的高度比例
                calculateP1DisplayRectView()
                resetP1EditMatrixToFocus(
                    this.currentNormalizedFocusX,
                    this.currentNormalizedFocusY
                ) // 重置矩阵以适应新高度
                attemptThrottledP1ConfigUpdate() // 尝试节流更新P1配置
            }
        }
    }

    /**
     * 设置 P1 图片内容的归一化焦点位置 (仅在非 P1 编辑模式下生效)。
     * 如果焦点发生变化，会更新 `currentNormalizedFocusX` 和 `currentNormalizedFocusY`，
     * 并根据情况更新 P1 顶部裁剪图或重绘。
     * 如果当前处于 P1 编辑模式，此方法会重置 P1 编辑矩阵以反映新焦点，并触发 P1 配置更新。
     *
     * @param focusX 新的归一化焦点 X 坐标 (0.0 - 1.0)。
     * @param focusY 新的归一化焦点 Y 坐标 (0.0 - 1.0)。
     */
    fun setNormalizedFocus(focusX: Float, focusY: Float) {
        val clampedFocusX = focusX.coerceIn(0f, 1f) // 限制在[0,1]
        val clampedFocusY = focusY.coerceIn(0f, 1f)
        var changed = false
        // 仅当值有显著变化时更新
        if (abs(this.currentNormalizedFocusX - clampedFocusX) > 0.001f) {
            this.currentNormalizedFocusX = clampedFocusX; changed = true
        }
        if (abs(this.currentNormalizedFocusY - clampedFocusY) > 0.001f) {
            this.currentNormalizedFocusY = clampedFocusY; changed = true
        }

        if (changed) {
            if (!isInP1EditMode && !isTransitioningFromEditMode) { // 非编辑模式
                // 如果有图片和源图，则更新P1顶部裁剪图以反映新焦点
                if (imageUri != null && wallpaperBitmaps?.sourceSampledBitmap != null) {
                    updateOnlyPage1TopCroppedBitmap(
                        nonEditModePage1ImageHeightRatio,
                        wallpaperBitmaps!!.sourceSampledBitmap!!,
                        this.currentP1ContentScaleFactor
                    )
                } else { // 否则仅重绘
                    invalidate()
                }
            } else if (isInP1EditMode) { // 如果在P1编辑模式下通过外部调用此方法
                resetP1EditMatrixToFocus(
                    this.currentNormalizedFocusX,
                    this.currentNormalizedFocusY
                ) // 重置编辑矩阵
                attemptThrottledP1ConfigUpdate() // 尝试节流更新P1配置
            }
        }
    }

    /**
     * 设置 P1 图片内容的缩放因子 (仅在非 P1 编辑模式下生效)。
     * 如果缩放因子发生变化，会更新 `currentP1ContentScaleFactor`，
     * 并根据情况更新 P1 顶部裁剪图或重绘。
     * 如果当前处于 P1 编辑模式，此方法会更新 `currentEditP1ContentScaleFactor` 并重置编辑矩阵，
     * 然后触发 P1 配置更新。
     *
     * @param scale 新的 P1 内容缩放因子，会被限制在合理范围内。
     */
    fun setP1ContentScaleFactor(scale: Float) {
        // 限制缩放因子在默认值和用户允许的最大值之间
        val clampedScale = scale.coerceIn(
            WallpaperConfigConstants.DEFAULT_P1_CONTENT_SCALE_FACTOR,
            p1UserMaxScaleFactorRelativeToCover
        ) //
        if (abs(this.currentP1ContentScaleFactor - clampedScale) > 0.001f) { // 仅当值有显著变化时更新
            this.currentP1ContentScaleFactor = clampedScale
            if (!isInP1EditMode && !isTransitioningFromEditMode) { // 非编辑模式
                // 如果有图片和源图，则更新P1顶部裁剪图以反映新缩放
                if (imageUri != null && wallpaperBitmaps?.sourceSampledBitmap != null) {
                    updateOnlyPage1TopCroppedBitmap(
                        nonEditModePage1ImageHeightRatio,
                        wallpaperBitmaps!!.sourceSampledBitmap!!,
                        this.currentP1ContentScaleFactor
                    )
                } else { // 否则仅重绘
                    invalidate()
                }
            } else if (isInP1EditMode) { // 如果在P1编辑模式下通过外部调用此方法
                currentEditP1ContentScaleFactor = clampedScale // 更新编辑模式下的缩放因子
                resetP1EditMatrixToFocus(
                    this.currentNormalizedFocusX,
                    this.currentNormalizedFocusY
                ) // 重置编辑矩阵
                attemptThrottledP1ConfigUpdate() // 尝试节流更新P1配置
            }
        }
    }

    /**
     * 异步仅更新 P1 顶部裁剪后的位图 (`wallpaperBitmaps.page1TopCroppedBitmap`)。
     * 此方法用于在 P1 的高度、焦点或内容缩放等参数发生变化时（通常在非编辑模式下），
     * 避免重新加载整个源图片，只重新生成 P1 前景图，以提高效率。
     *
     * @param heightRatioToUse 用于生成 P1 顶图的高度比例。
     * @param sourceBitmap 用作裁剪和缩放基础的源位图。
     * @param contentScaleToUse 用于生成 P1 顶图的内容缩放因子。
     * @param onComplete (可选) 当位图更新完成（成功或失败）后执行的回调。
     */
    private fun updateOnlyPage1TopCroppedBitmap(
        heightRatioToUse: Float,
        sourceBitmap: Bitmap,
        contentScaleToUse: Float,
        onComplete: (() -> Unit)? = null // 完成回调
    ) {
        // 如果View尺寸无效，或当前处于P1编辑模式且非过渡状态，则不执行更新
        if (viewWidth <= 0 || viewHeight <= 0) {
            mainScopeLaunch { onComplete?.invoke() } // 如果有回调，在主线程执行
            return
        }
        if (isInP1EditMode && !isTransitioningFromEditMode) {
            // 在P1编辑模式下，P1的显示由p1EditMatrix控制，不使用预裁剪的page1TopCroppedBitmap
            mainScopeLaunch { onComplete?.invoke() }
            return
        }

        topBitmapUpdateJob?.cancel() // 取消任何正在进行的P1顶图更新任务
        topBitmapUpdateJob = viewScope.launch {
            var newTopBmp: Bitmap? = null
            try {
                ensureActive()
                // 在默认调度器上执行耗时的位图处理
                newTopBmp = withContext(Dispatchers.Default) { // 使用Default适合CPU密集型任务
                    ensureActive()
                    // 调用共享渲染器准备P1顶部裁剪图
                    SharedWallpaperRenderer.preparePage1TopCroppedBitmap( //
                        sourceBitmap, viewWidth, viewHeight,
                        heightRatioToUse, this@WallpaperPreviewView.currentNormalizedFocusX,
                        this@WallpaperPreviewView.currentNormalizedFocusY, contentScaleToUse
                    )
                }
                ensureActive() // 返回主线程后再次检查活动状态

                val oldTopCropped = wallpaperBitmaps?.page1TopCroppedBitmap
                // 检查在异步处理期间，图片URI和源位图是否未发生变化
                if (this@WallpaperPreviewView.imageUri != null && wallpaperBitmaps?.sourceSampledBitmap == sourceBitmap) {
                    wallpaperBitmaps?.page1TopCroppedBitmap = newTopBmp // 更新P1顶图
                    if (oldTopCropped != newTopBmp) oldTopCropped?.recycle() // 回收旧的P1顶图 (如果不同)
                } else { // 如果条件变化，则丢弃新生成的位图
                    newTopBmp?.recycle()
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "updateOnlyPage1TopCroppedBitmap cancelled")
                newTopBmp?.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "updateOnlyPage1TopCroppedBitmap failed", e)
                newTopBmp?.recycle()
                // 如果失败的是当前图片和源图，则将P1顶图设为null
                if (this@WallpaperPreviewView.imageUri != null && wallpaperBitmaps?.sourceSampledBitmap == sourceBitmap) {
                    wallpaperBitmaps?.page1TopCroppedBitmap = null
                }
            } finally {
                // 如果协程仍然活动且当前 Job 是 topBitmapUpdateJob，则清空 Job 引用
                if (isActive && coroutineContext[Job] == topBitmapUpdateJob) {
                    topBitmapUpdateJob = null
                }
                // 在主线程上执行完成回调
                mainScopeLaunch {
                    if (isActive) { // 再次检查活动状态，因为回调可能在协程取消后被调用
                        onComplete?.invoke()
                    }
                }
                // 如果没有提供完成回调，并且当前仍处于有效状态（有URI，非编辑/过渡模式），则重绘
                if (onComplete == null && isActive && this@WallpaperPreviewView.imageUri != null &&
                    !this@WallpaperPreviewView.isInP1EditMode && !this@WallpaperPreviewView.isTransitioningFromEditMode
                ) {
                    invalidate()
                }
            }
        }
    }

    /**
     * 辅助方法，用于在主线程（View的协程作用域 `viewScope`）中安全地启动一个协程块。
     * 会检查 `viewScope` 是否仍然活动，避免在 View Detached 后尝试启动新协程。
     * @param block 要在主线程协程中执行的代码块。
     */
    private fun mainScopeLaunch(block: suspend CoroutineScope.() -> Unit) {
        if (viewScope.isActive) { // 检查作用域是否活动
            viewScope.launch { // 在此作用域（默认为Dispatchers.Main）启动协程
                block()
            }
        } else {
            Log.w(TAG, "ViewScope not active for mainScopeLaunch. Task will not be launched.")
        }
    }


    /**
     * 设置 P1 层底部（图片未覆盖区域）的背景颜色，并重绘 View。
     * @param color 新的背景颜色值。
     */
    fun setSelectedBackgroundColor(color: Int) {
        if (this.selectedBackgroundColor != color) { // 仅当颜色实际改变时才更新和重绘
            this.selectedBackgroundColor = color
            invalidate()
        }
    }

    /**
     * 执行 View 的点击操作。
     * 此方法会调用父类 `super.performClick()`，该方法通常会触发通过 `setOnClickListener` 设置的监听器。
     * 在此项目中，`MainActivity` 会为 `WallpaperPreviewView` 设置一个 `OnClickListener`，
     * 用于在单击预览视图时切换配置面板的显示状态。
     * @return 如果外部设置的 OnClickListener 处理了点击，则通常返回 true；否则返回 false。
     */
    override fun performClick(): Boolean {
        Log.d(TAG, "performClick() called. isInP1EditMode: $isInP1EditMode")
        // 调用父类的 performClick() 会触发外部设置的 OnClickListener
        return super.performClick()
    }

    /**
     * 处理页面横向快速滑动 (Fling) 手势。
     * 根据滑动速度和方向，计算目标页面，并使用 `animateToOffset()` 动画滚动到该页面。
     * 此方法仅在非 P1 编辑模式且虚拟页数大于1时有效。
     * @param velocityX X 方向的滑动速度 (像素/秒)。
     */
    private fun flingPage(velocityX: Float) {
        // 如果在P1编辑模式或只有一页，则不处理Fling，停止滚动并重置偏移
        if (isInP1EditMode || numVirtualPages <= 1) {
            if (!pageScroller.isFinished) pageScroller.abortAnimation()
            currentPreviewXOffset = 0f
            invalidate()
            return
        }

        // 根据当前偏移量计算有效的当前页面索引 (浮点数)
        val currentEffectivePageIndex = currentPreviewXOffset * (numVirtualPages - 1)
        var targetPageIndex: Int // 目标页面的整数索引

        if (velocityX < -minFlingVelocity) { // 向左快速滑动 (速度为负)
            targetPageIndex = ceil(currentEffectivePageIndex).toInt() // 目标是当前页或下一页 (向上取整)
            // 如果已经很接近目标页的右边界，并且不是最后一页，则实际目标是再下一页
            if (targetPageIndex <= currentEffectivePageIndex + 0.05f && targetPageIndex < numVirtualPages - 1) {
                targetPageIndex++
            }
        } else if (velocityX > minFlingVelocity) { // 向右快速滑动 (速度为正)
            targetPageIndex = floor(currentEffectivePageIndex).toInt() // 目标是当前页或上一页 (向下取整)
            // 如果已经很接近目标页的左边界，并且不是第一页，则实际目标是再上一页
            if (targetPageIndex >= currentEffectivePageIndex - 0.05f && targetPageIndex > 0) {
                targetPageIndex--
            }
        } else { // 速度不够快，不构成Fling，则执行吸附到最近页面
            snapToNearestPage(currentPreviewXOffset)
            return
        }
        // 确保目标页面索引在有效范围内 [0, numVirtualPages - 1]
        targetPageIndex = targetPageIndex.coerceIn(0, numVirtualPages - 1)
        // 将目标页面索引转换为归一化偏移量
        val targetXOffset =
            if (numVirtualPages > 1) targetPageIndex.toFloat() / (numVirtualPages - 1).toFloat() else 0f
        animateToOffset(targetXOffset) // 动画滚动到目标偏移量
    }

    /**
     * 将预览吸附到最近的整数页面位置。
     * 此方法仅在非 P1 编辑模式且虚拟页数大于1时有效。
     * @param currentOffset 当前的归一化滚动偏移量。
     */
    private fun snapToNearestPage(currentOffset: Float) {
        // 如果在P1编辑模式或只有一页，则不处理吸附，停止滚动并重置偏移
        if (isInP1EditMode || numVirtualPages <= 1) {
            if (!pageScroller.isFinished) pageScroller.abortAnimation()
            currentPreviewXOffset = 0f
            invalidate()
            return
        }
        // 将当前偏移量转换为浮点页面索引
        val pageIndexFloat = currentOffset * (numVirtualPages - 1)
        // 四舍五入到最近的整数页面索引，并确保在有效范围内
        val targetPageIndex = pageIndexFloat.roundToInt().coerceIn(0, numVirtualPages - 1)
        // 将目标页面索引转换为归一化偏移量
        val targetXOffset =
            if (numVirtualPages > 1) targetPageIndex.toFloat() / (numVirtualPages - 1).toFloat() else 0f
        animateToOffset(targetXOffset) // 动画滚动到目标偏移量
    }

    /**
     * 使用 `OverScroller` (pageScroller) 将预览动画滚动到指定的目标归一化偏移量。
     * @param targetXOffset 目标归一化 X 偏移量 (0.0 - 1.0)。
     */
    private fun animateToOffset(targetXOffset: Float) {
        // 将当前和目标归一化偏移量转换为 OverScroller 使用的像素级滚动值
        // getScrollRange() 返回一个较大的虚拟滚动范围
        val currentPixelOffset = (currentPreviewXOffset * getScrollRange()).toInt()
        val targetPixelOffset = (targetXOffset * getScrollRange()).toInt()
        val dx = targetPixelOffset - currentPixelOffset // 计算需要滚动的像素差值

        if (dx != 0) { // 如果需要滚动
            // 使用 OverScroller 启动滚动动画
            // 从 currentPixelOffset 开始，滚动 dx 距离，持续 currentSnapAnimationDurationMs 毫秒
            pageScroller.startScroll(
                currentPixelOffset,
                0,
                dx,
                0,
                currentSnapAnimationDurationMs.toInt()
            )
            postInvalidateOnAnimation() // 请求在下一帧动画时重绘 (computeScroll会被调用)
        } else { // 如果已经在目标位置，直接设置偏移并重绘
            this.currentPreviewXOffset = targetXOffset.coerceIn(0f, 1f)
            invalidate()
        }
    }

    /**
     * 由系统调用，用于计算和更新滚动动画的状态。
     * 此方法会检查 `pageScroller` (用于页面横向滚动) 和 `p1ContentScroller` (用于P1内容编辑时的惯性滚动)
     * 是否仍在计算滚动偏移。
     * 如果是，则更新相应的状态 (如 `currentPreviewXOffset` 或 `p1EditMatrix`) 并请求重绘。
     */
    override fun computeScroll() {
        var triggerInvalidate = false // 标记是否需要重绘

        // --- 处理页面横向滚动动画 ---
        if (pageScroller.computeScrollOffset()) { // 如果 pageScroller 仍在计算
            // 如果在P1编辑模式下，页面不应滚动，强制停止动画
            if (isInP1EditMode && !pageScroller.isFinished) {
                pageScroller.abortAnimation()
            }
            // 如果不在P1编辑模式，或者正在从P1编辑模式过渡，则允许页面滚动
            else if (!isInP1EditMode || isTransitioningFromEditMode) {
                val currentPixelOffset = pageScroller.currX // 获取当前滚动到的像素位置
                val scrollRange = getScrollRange()
                // 将像素位置转换为归一化偏移量
                currentPreviewXOffset = if (scrollRange > 0) {
                    (currentPixelOffset.toFloat() / scrollRange.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                triggerInvalidate = true // 需要重绘以反映新的页面偏移
            }
        }

        // --- 处理 P1 内容在编辑模式下的惯性滚动动画 ---
        if (p1ContentScroller.computeScrollOffset()) { // 如果 p1ContentScroller 仍在计算
            if (isInP1EditMode) { // 仅在P1编辑模式下有效
                val newX = p1ContentScroller.currX // 获取当前滚动到的X位置 (Scroller内部坐标)
                val newY = p1ContentScroller.currY // 获取当前滚动到的Y位置

                // 计算与上一次 Scroller 位置的差值
                val dx = newX - lastP1ScrollerX
                val dy = newY - lastP1ScrollerY

                if (dx != 0 || dy != 0) { // 如果有实际位移
                    p1EditMatrix.postTranslate(dx.toFloat(), dy.toFloat()) // 平移P1编辑矩阵
                    applyP1EditMatrixBounds() // 应用边界限制
                    attemptThrottledP1ConfigUpdate() // 尝试节流更新P1配置（因为焦点可能变化）
                }

                lastP1ScrollerX = newX // 更新上次Scroller位置
                lastP1ScrollerY = newY
                triggerInvalidate = true // 需要重绘以反映P1内容的移动

                // 如果P1内容滚动动画结束，确保执行一次最终的P1配置更新
                if (p1ContentScroller.isFinished) {
                    executeP1ConfigUpdate()
                }
            } else { // 如果不在P1编辑模式，则强制停止P1内容滚动
                p1ContentScroller.forceFinished(true)
            }
        }

        if (triggerInvalidate) { // 如果需要重绘
            postInvalidateOnAnimation() // 请求在下一帧动画时重绘
        }
    }


    /**
     * 获取用于 `OverScroller` 的虚拟滚动范围的像素值。
     * 这是一个较大的固定值，用于将归一化偏移量映射到 `OverScroller` 使用的像素单位。
     * @return 虚拟滚动范围的像素值。
     */
    private fun getScrollRange(): Int = (numVirtualPages - 1) * 10000 // (3-1)*10000 = 20000

    /**
     * 回收速度追踪器 (`velocityTracker`)，释放其占用的资源。
     * 在不再需要追踪速度时（例如 ACTION_UP 或 ACTION_CANCEL 后）调用。
     */
    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    /**
     * 当 View 从窗口分离时调用。
     * 在此方法中，应清理所有持有的资源，以防止内存泄漏。
     * 包括：取消所有正在运行的协程、关闭 Channel、回收位图资源、回收速度追踪器等。
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Log.d(TAG, "WallpaperPreviewView onDetachedFromWindow: Cleaning up resources.")
        // 取消 View 级别的所有协程
        viewScope.cancel("View detached from window")
        // 清空 Job 引用
        fullBitmapLoadingJob = null
        topBitmapUpdateJob = null
        blurUpdateJob = null // 虽然已废弃，但以防万一
        styleBBlurUpdateJob = null

        // 关闭并取消模糊任务处理器和队列
        blurTaskProcessor?.cancel("View detached, stopping blur processor")
        blurTaskProcessor = null
        if (!blurTaskQueue.isClosedForSend) {
            blurTaskQueue.close() // 关闭 Channel，这将导致 blurTaskProcessor 中的循环结束
        }

        // 回收所有持有的位图资源
        wallpaperBitmaps?.recycleInternals()
        wallpaperBitmaps = null

        // 回收速度追踪器
        recycleVelocityTracker()

        // 移除 Handler 中的回调 (如果有)
        throttledP1ConfigUpdateRunnable?.let { mainHandler.removeCallbacks(it) }
        throttledP1ConfigUpdateRunnable = null

        Log.d(TAG, "WallpaperPreviewView detached, resources cleaned up.")
    }
}