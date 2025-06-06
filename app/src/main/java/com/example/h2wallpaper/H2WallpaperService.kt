package com.example.h2wallpaper

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

// 导入 WallpaperConfigConstants 对象
import kotlinx.coroutines.cancel

/**
 * H2WallpaperService 是一个 Android [WallpaperService]，负责在用户设备的主屏幕上渲染动态壁纸。
 * 它会创建一个 [H2WallpaperEngine] 实例来处理实际的绘制逻辑、用户配置的加载与响应，
 * 以及与壁纸生命周期的交互。
 */
class H2WallpaperService : WallpaperService() {

    companion object {
        private const val TAG = "H2WallpaperSvc" // 日志标签
        private const val DEBUG_TAG = "H2WallpaperSvc_Debug" // 详细调试日志标签
        /** 动态壁纸渲染时，用于计算滚动和过渡效果的默认虚拟页面数量。*/
        private const val DEFAULT_VIRTUAL_PAGES_FOR_SCROLLING = 3
    }

    /**
     * 当壁纸服务被系统绑定时调用，用于创建壁纸引擎 [H2WallpaperEngine] 的实例。
     * 每个活动的壁纸连接都会有一个独立的引擎实例。
     * @return 返回新创建的 [H2WallpaperEngine] 实例。
     */
    override fun onCreateEngine(): Engine {
        return H2WallpaperEngine()
    }

    /**
     * H2WallpaperEngine 实现了动态壁纸的核心逻辑。
     * 它负责处理 Surface 的生命周期、加载用户配置、响应配置变化、
     * 处理壁纸滚动偏移，并调用 [SharedWallpaperRenderer] 来绘制壁纸的每一帧。
     */
    private inner class H2WallpaperEngine : Engine(),
        SharedPreferences.OnSharedPreferenceChangeListener { // 实现监听器以响应配置变化

        /** 与壁纸渲染目标 Surface 关联的 SurfaceHolder。*/
        private var surfaceHolder: SurfaceHolder? = null
        /** 标记壁纸当前是否可见。*/
        private var isVisible: Boolean = false
        /** 当前壁纸 Surface 的宽度 (像素)。*/
        private var screenWidth: Int = 0
        /** 当前壁纸 Surface 的高度 (像素)。*/
        private var screenHeight: Int = 0

        /** 持有壁纸渲染所需的各种位图资源。*/
        private var engineWallpaperBitmaps: SharedWallpaperRenderer.WallpaperBitmaps? = null
        /** 壁纸引擎级别的协程作用域，用于管理异步任务 (如图片加载)。*/
        private val engineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        /** 当前正在执行的完整位图加载任务。*/
        private var currentBitmapLoadJob: Job? = null

        /** 用于访问和管理壁纸配置的仓库实例。*/
        private lateinit var preferencesRepository: WallpaperPreferencesRepository
        /** SharedPreferences 实例，用于监听配置变化。*/
        private lateinit var prefs: SharedPreferences

        // --- 当前壁纸配置参数的成员变量 ---
        /** 当前选定图片的 URI 字符串。*/
        private var imageUriString: String? = null
        /** P1 层底部背景颜色。*/
        private var page1BackgroundColor: Int = WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR
        /** P1 图片高度与屏幕高度的比例。*/
        private var page1ImageHeightRatio: Float = WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO
        /** P1 图片内容的归一化焦点 X 坐标。*/
        private var currentP1FocusX: Float = WallpaperConfigConstants.DEFAULT_P1_FOCUS_X
        /** P1 图片内容的归一化焦点 Y 坐标。*/
        private var currentP1FocusY: Float = WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y
        /** P1 图片内容的缩放因子。*/
        private var currentP1ContentScaleFactor: Float = WallpaperConfigConstants.DEFAULT_P1_CONTENT_SCALE_FACTOR

        /** 背景滚动灵敏度因子。*/
        private var currentScrollSensitivity: Float = WallpaperConfigConstants.DEFAULT_SCROLL_SENSITIVITY
        /** P1 前景层淡出过渡比例。*/
        private var currentP1OverlayFadeRatio: Float = WallpaperConfigConstants.DEFAULT_P1_OVERLAY_FADE_RATIO
        /** P2 背景层模糊半径。*/
        private var currentBackgroundBlurRadius: Float = WallpaperConfigConstants.DEFAULT_BACKGROUND_BLUR_RADIUS
        /** P2 背景层在第一页的归一化初始横向偏移。*/
        private var currentNormalizedInitialBgScrollOffset: Float = WallpaperConfigConstants.DEFAULT_BACKGROUND_INITIAL_OFFSET
        /** P2 背景层淡入过渡比例。*/
        private var currentP2BackgroundFadeInRatio: Float = WallpaperConfigConstants.DEFAULT_P2_BACKGROUND_FADE_IN_RATIO
        /** P2 背景模糊处理时的降采样因子。*/
        private var currentBlurDownscaleFactor: Float = WallpaperConfigConstants.DEFAULT_BLUR_DOWNSCALE_FACTOR_INT / 100.0f
        /** P2 背景模糊处理的迭代次数。*/
        private var currentBlurIterations: Int = WallpaperConfigConstants.DEFAULT_BLUR_ITERATIONS
        /** P1 图片投影半径。*/
        private var currentP1ShadowRadius: Float = WallpaperConfigConstants.DEFAULT_P1_SHADOW_RADIUS
        /** P1 图片投影 X 轴偏移。*/
        private var currentP1ShadowDx: Float = WallpaperConfigConstants.DEFAULT_P1_SHADOW_DX
        /** P1 图片投影 Y 轴偏移。*/
        private var currentP1ShadowDy: Float = WallpaperConfigConstants.DEFAULT_P1_SHADOW_DY
        /** P1 图片投影颜色。*/
        private var currentP1ShadowColor: Int = WallpaperConfigConstants.DEFAULT_P1_SHADOW_COLOR
        /** P1 图片底部融入效果高度。*/
        private var currentP1ImageBottomFadeHeight: Float = WallpaperConfigConstants.DEFAULT_P1_IMAGE_BOTTOM_FADE_HEIGHT
        /** 当前图片内容的版本号，用于检测配置的实质性变化。*/
        private var currentImageContentVersion: Long = WallpaperConfigConstants.DEFAULT_IMAGE_CONTENT_VERSION

        // --- New Style Parameters ---
        /** P1 样式类型。*/
        private var currentP1StyleType: Int = WallpaperConfigConstants.DEFAULT_P1_STYLE_TYPE
        /** 样式 B 蒙版 Alpha 值。*/
        private var currentStyleBMaskAlpha: Float = WallpaperConfigConstants.DEFAULT_STYLE_B_MASK_ALPHA
        /** 样式 B 旋转参数 A。*/
        private var currentStyleBRotationParamA: Float = WallpaperConfigConstants.DEFAULT_STYLE_B_ROTATION_PARAM_A
        /** 样式 B 间隙大小比例。*/
        private var currentStyleBGapSizeRatio: Float = WallpaperConfigConstants.DEFAULT_STYLE_B_GAP_SIZE_RATIO
        /** 样式 B 间隙 Y 位置比例。*/
        private var currentStyleBGapPositionYRatio: Float = WallpaperConfigConstants.DEFAULT_STYLE_B_GAP_POSITION_Y_RATIO
        /** 样式 B 上蒙版最大旋转角度。*/
        private var currentStyleBUpperMaskMaxRotation: Float = WallpaperConfigConstants.DEFAULT_STYLE_B_UPPER_MASK_MAX_ROTATION
        /** 样式 B 下蒙版最大旋转角度。*/
        private var currentStyleBLowerMaskMaxRotation: Float = WallpaperConfigConstants.DEFAULT_STYLE_B_LOWER_MASK_MAX_ROTATION
        /** 样式 B P1 焦点 X。*/
        private var currentStyleBP1FocusX: Float = WallpaperConfigConstants.DEFAULT_STYLE_B_P1_FOCUS_X
        /** 样式 B P1 焦点 Y。*/
        private var currentStyleBP1FocusY: Float = WallpaperConfigConstants.DEFAULT_STYLE_B_P1_FOCUS_Y
        /** 样式 B P1 缩放因子。*/
        private var currentStyleBP1ScaleFactor: Float = WallpaperConfigConstants.DEFAULT_STYLE_B_P1_SCALE_FACTOR
        // ---
        private var currentStyleBMasksHorizontallyFlipped: Boolean = WallpaperConfigConstants.DEFAULT_STYLE_B_MASKS_HORIZONTALLY_FLIPPED

        // 新增：样式 B P1 遮罩专属模糊参数的成员变量
        private var currentStyleBP1MaskBlurRadius: Float = WallpaperConfigConstants.DEFAULT_STYLE_B_BLUR_RADIUS
        private var currentStyleBP1MaskBlurDownscale: Float = WallpaperConfigConstants.DEFAULT_STYLE_B_BLUR_DOWNSCALE_FACTOR
        private var currentStyleBP1MaskBlurIterations: Int = WallpaperConfigConstants.DEFAULT_STYLE_B_BLUR_ITERATIONS

        /** 启动器报告的实际页面数量，用于更精确的滚动计算。*/
        private var numPagesReportedByLauncher = 1
        /** 当前正在执行的仅更新模糊背景的任务。*/
        private var currentBlurUpdateJob: Job? = null
        /** 当前正在执行的仅用于样式B P1遮罩模糊更新。*/
        private var currentStyleBBlurUpdateJob: Job? = null // <--- 新增：
        /** 当前壁纸的横向滚动偏移量 (由系统通过 onOffsetsChanged 更新)。*/
        private var currentPageOffset = 0f


        /**
         * 当壁纸引擎被创建时调用。
         * 初始化 SurfaceHolder、配置仓库、加载初始配置，并注册 SharedPreferences 变化监听器。
         * 如果此时已有屏幕尺寸和图片 URI，会尝试加载位图。
         * @param surfaceHolder 用于绘制壁纸的 SurfaceHolder。
         */
        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            this.surfaceHolder = surfaceHolder
            Log.i(TAG, "H2WallpaperEngine Created. IsPreview: $isPreview")

            preferencesRepository = WallpaperPreferencesRepository(applicationContext) //
            prefs = applicationContext.getSharedPreferences(
                WallpaperConfigConstants.PREFS_NAME, //
                Context.MODE_PRIVATE
            )
            prefs.registerOnSharedPreferenceChangeListener(this) // 注册监听配置变化
            loadPreferencesFromStorage() // 加载初始配置

            // 如果屏幕尺寸有效且已选择图片，则尝试加载位图
            if (screenWidth > 0 && screenHeight > 0 && imageUriString != null) {
                Log.i(DEBUG_TAG, "onCreate: Triggering initial bitmap load if needed.")
                loadFullBitmapsAsyncIfNeeded() //
            } else {
                Log.i(DEBUG_TAG, "onCreate: Conditions for initial load not met (no URI or screen size yet).")
            }
        }

        /**
         * 当 SharedPreferences 中的配置发生变化时被调用。
         * 此方法会重新加载所有配置，并根据变化的具体内容决定如何更新壁纸：
         * - 如果图片 URI 或内容版本号发生重大变化，可能需要完全重新加载所有位图。
         * - 如果仅 P1 相关参数（焦点、高度、缩放）变化，可能只需要更新 P1 顶部裁剪图。
         * - 如果仅模糊参数变化，可能只需要异步更新模糊背景图。
         * - 其他参数变化，可能只需要重绘当前帧。
         *
         * @param sharedPreferences 发生变化的 SharedPreferences 实例。
         * @param key 发生变化的配置项的键名；如果为 null，表示多个配置项可能已改变 (例如 clear() 被调用)。
         */
        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
            Log.i(DEBUG_TAG, "0002 onSharedPreferenceChanged: key=$key received by service.")
            var needsFullReload = false       // 标记是否需要完全重载所有位图
            var needsP1TopUpdate = false      // 标记是否只需要更新 P1 顶部裁剪图 (主要用于样式 A)
            var needsOnlyGlobalBlurUpdate = false   // 标记是否只需要更新 P2 全局背景模糊图
            var needsStyleBP1MaskBlurUpdate = false // 新增标记: 是否只需要更新样式 B 的 P1 遮罩模糊图
            var needsRedrawOnly = false       // 标记是否只需要重绘当前帧

            // --- 1. 保存变化前的关键参数 ---
            val oldImageUriString = imageUriString
            val oldImageContentVersion = currentImageContentVersion

            // 样式 A 相关 P1 参数
            val oldP1FocusX = currentP1FocusX
            val oldP1FocusY = currentP1FocusY
            val oldPage1ImageHeightRatio = page1ImageHeightRatio
            val oldP1ContentScaleFactor = currentP1ContentScaleFactor

            // 全局背景模糊参数 (主要用于 P2)
            val oldBackgroundBlurRadius = currentBackgroundBlurRadius
            val oldBlurDownscaleFactor = currentBlurDownscaleFactor
            val oldBlurIterations = currentBlurIterations

            // 样式 B 相关参数 (非模糊部分)
            val oldP1StyleType = currentP1StyleType
            val oldStyleBMaskAlpha = currentStyleBMaskAlpha
            val oldStyleBRotationParamA = currentStyleBRotationParamA
            val oldStyleBGapSizeRatio = currentStyleBGapSizeRatio
            val oldStyleBGapPositionYRatio = currentStyleBGapPositionYRatio
            val oldStyleBUpperMaskMaxRotation = currentStyleBUpperMaskMaxRotation
            val oldStyleBLowerMaskMaxRotation = currentStyleBLowerMaskMaxRotation
            val oldStyleBP1FocusX = currentStyleBP1FocusX // 样式 B 的 P1 焦点
            val oldStyleBP1FocusY = currentStyleBP1FocusY
            val oldStyleBP1ScaleFactor = currentStyleBP1ScaleFactor
            val oldStyleBMasksHorizontallyFlipped = currentStyleBMasksHorizontallyFlipped

            // 样式 B P1 遮罩专属模糊参数
            val oldStyleBP1MaskBlurRadius = currentStyleBP1MaskBlurRadius
            val oldStyleBP1MaskBlurDownscale = currentStyleBP1MaskBlurDownscale
            val oldStyleBP1MaskBlurIterations = currentStyleBP1MaskBlurIterations

            // --- 2. 重新从 SharedPreferences 加载所有当前配置到成员变量 ---
            loadPreferencesFromStorage()
            Log.i(DEBUG_TAG, "0002 fter loadPreferencesFromStorage - New imageUriString: ${this.imageUriString}, New Version: ${this.currentImageContentVersion}")

            // --- 3. 检测具体哪些参数发生了变化 ---

            // 优先判断图片 URI 或 P1 样式类型这种可能导致根本性变化的参数
            if (oldImageUriString != imageUriString) {
                Log.i(DEBUG_TAG, "0002 Image URI changed. Triggering full reload.")
                needsFullReload = true
            } else if (oldP1StyleType != currentP1StyleType) {
                Log.i(DEBUG_TAG, "0002 P1 Style Type changed. Triggering full reload (safer to ensure all bitmaps are correct for the style).")
                // 切换样式通常意味着P1层的渲染方式完全不同，可能需要不同的位图或处理，完整重载更安全
                needsFullReload = true
            } else {
                // 如果 URI 和样式类型未变，再检查其他参数

                // 检查图片内容版本号（通常由ViewModel在参数保存后更新）
                // 如果版本号变了，说明有实质性配置更改
                if (oldImageContentVersion != currentImageContentVersion) {
                    Log.i(DEBUG_TAG, "0002 Image Content Version changed ($oldImageContentVersion -> $currentImageContentVersion). Determining specific updates.")


                    // 检查样式 A 的 P1 编辑参数变化
                    if (currentP1StyleType == WallpaperConfigConstants.DEFAULT_P1_STYLE_TYPE) { // 仅当是样式 A 时这些参数才直接影响 P1 顶图
                        if (oldP1FocusX != currentP1FocusX ||
                            oldP1FocusY != currentP1FocusY ||
                            oldPage1ImageHeightRatio != page1ImageHeightRatio ||
                            oldP1ContentScaleFactor != currentP1ContentScaleFactor) {
                            Log.i(DEBUG_TAG, "0002 Style A P1 visual params (Focus/Height/Scale) changed. Triggering P1 top update.")
                            needsP1TopUpdate = true
                        }
                    }

                    // 检查样式 B 的 P1 独立背景图编辑参数变化
                    if (currentP1StyleType == 1) { // 仅当是样式 B 时这些参数才直接影响 P1 独立背景图
                        if (oldStyleBP1FocusX != currentStyleBP1FocusX ||
                            oldStyleBP1FocusY != currentStyleBP1FocusY ||
                            oldStyleBP1ScaleFactor != currentStyleBP1ScaleFactor) {
                            // 这种变化影响的是 sourceSampled 如何被绘制为 P1 背景，
                            // SharedRenderer.drawStyleBLayer 会处理，通常只需要重绘。
                            // 但如果 P1 编辑模式在 Service 中也对应位图裁剪（目前不是），则可能需要更新。
                            // 当前设计下，这些参数变化通常仅需重绘。
                            if (!needsP1TopUpdate && !needsFullReload) needsRedrawOnly = true
                            Log.i(DEBUG_TAG, "Style B P1 BG params (Focus/Scale) changed. Marking for redraw.")
                        }
                    }

                    // 检查全局背景模糊参数 (用于 P2) 是否变化
                    if (oldBackgroundBlurRadius != currentBackgroundBlurRadius ||
                        oldBlurDownscaleFactor != currentBlurDownscaleFactor ||
                        oldBlurIterations != currentBlurIterations) {
                        Log.i(DEBUG_TAG, "Global Blur params (for P2) changed. Triggering global blur update.")
                        needsOnlyGlobalBlurUpdate = true
                    }

                    // 检查样式 B P1 遮罩专属模糊参数是否变化
                    if (oldStyleBP1MaskBlurRadius != currentStyleBP1MaskBlurRadius ||
                        oldStyleBP1MaskBlurDownscale != currentStyleBP1MaskBlurDownscale ||
                        oldStyleBP1MaskBlurIterations != currentStyleBP1MaskBlurIterations) {
                        Log.i(DEBUG_TAG, "Style B P1 Mask Blur params changed. Triggering Style B P1 Mask blur update.")
                        needsStyleBP1MaskBlurUpdate = true
                    }

                    // 检查其他仅影响绘制的参数
                    // (例如：滚动灵敏度、P1/P2淡出比例、P1阴影、样式B非模糊参数等)
                    // 如果上述更具体的更新没有被触发，并且版本号变了，那么至少需要重绘
                    if (!needsFullReload && !needsP1TopUpdate && !needsOnlyGlobalBlurUpdate && !needsStyleBP1MaskBlurUpdate) {
                        Log.i(DEBUG_TAG, "0002 Version changed, and no specific bitmap update triggered. Setting needsFullReload=true as a fallback for parameter reset cases.")
                        needsFullReload = true
                        // needsRedrawOnly = true
                       // Log.i(DEBUG_TAG, "Other rendering params likely changed (due to version change). Triggering redraw.")
                    }

                } else if (key != null) { // 版本号未变，但某个具体的 key 变了 (作为备用逻辑)
                    when (key) {
                        WallpaperConfigConstants.KEY_IMAGE_URI -> { /* 已在前面处理 */ }
                        WallpaperConfigConstants.KEY_P1_STYLE_TYPE -> { /* 已在前面处理 */ }

                        // 样式 A 的 P1 编辑参数
                        WallpaperConfigConstants.KEY_P1_FOCUS_X,
                        WallpaperConfigConstants.KEY_P1_FOCUS_Y,
                        WallpaperConfigConstants.KEY_IMAGE_HEIGHT_RATIO,
                        WallpaperConfigConstants.KEY_P1_CONTENT_SCALE_FACTOR -> {
                            if (currentP1StyleType == WallpaperConfigConstants.DEFAULT_P1_STYLE_TYPE) {
                                needsP1TopUpdate = true
                            }
                        }

                        // 全局背景模糊参数 (P2)
                        WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS,
                        WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR,
                        WallpaperConfigConstants.KEY_BLUR_ITERATIONS -> {
                            needsOnlyGlobalBlurUpdate = true
                        }

                        // 样式 B P1 遮罩专属模糊参数
                        WallpaperConfigConstants.KEY_STYLE_B_BLUR_RADIUS,
                        WallpaperConfigConstants.KEY_STYLE_B_BLUR_DOWNSCALE_FACTOR,
                        WallpaperConfigConstants.KEY_STYLE_B_BLUR_ITERATIONS -> {
                            needsStyleBP1MaskBlurUpdate = true
                        }

                        // 其他所有参数变化都至少需要重绘
                        else -> needsRedrawOnly = true
                    }
                } else { // key 为 null (例如 prefs.edit().clear().apply())，且版本号和URI未变
                    if (imageUriString != null) needsRedrawOnly = true // 如果还有图片，则重绘
                }
            }


            // --- 4. 根据标记执行相应的更新操作 (优先级：Full Reload > 部分位图更新 > Redraw) ---
            Log.i(DEBUG_TAG, "Final flags before action: full=$needsFullReload, p1top=$needsP1TopUpdate, globalBlur=$needsOnlyGlobalBlurUpdate, styleBBlur=$needsStyleBP1MaskBlurUpdate, redraw=$needsRedrawOnly")
            if (needsFullReload) {
                Log.i(DEBUG_TAG, "0002 Final Action: Executing full bitmap reload.")
                currentBitmapLoadJob?.cancel() // 取消正在进行的完整加载
                currentBlurUpdateJob?.cancel() // 取消P2背景模糊更新
                currentStyleBBlurUpdateJob?.cancel() // 取消样式B P1遮罩模糊更新
                Log.e(DEBUG_TAG, "DECISION: Full reload selected.")
                loadFullBitmapsAsync()
            } else {
                // 如果不需要完整重载，才考虑部分更新
                var partialBitmapUpdateTriggered = false

                if (needsOnlyGlobalBlurUpdate) {
                    if (engineWallpaperBitmaps?.scrollingBackgroundBitmap != null && imageUriString != null) {
                        Log.i(DEBUG_TAG, "Final Action: Executing global blur-only update for P2 background.")
                        updateOnlyBlurredBackgroundAsync()
                        partialBitmapUpdateTriggered = true
                    } else if (imageUriString != null) {
                        Log.w(DEBUG_TAG, "Skipping P2 blur update (base bitmap missing), will force full reload if no other update occurs or try redraw.")
                        // 如果其他更新也没发生，可能需要强制重载或至少重绘
                    }
                }

                if (needsStyleBP1MaskBlurUpdate) {
                    if (engineWallpaperBitmaps?.sourceSampledBitmap != null && imageUriString != null) {
                        Log.i(DEBUG_TAG, "Final Action: Executing Style B P1 Mask blur update.")
                        updateStyleBP1MaskBlurredBitmapAsync()
                        partialBitmapUpdateTriggered = true
                    } else if (imageUriString != null) {
                        Log.w(DEBUG_TAG, "Skipping Style B P1 Mask blur update (source bitmap missing), will force full reload if no other update occurs or try redraw.")
                    }
                }

                if (needsP1TopUpdate && currentP1StyleType == WallpaperConfigConstants.DEFAULT_P1_STYLE_TYPE) { // 仅对样式A有效
                    if (engineWallpaperBitmaps?.sourceSampledBitmap != null && imageUriString != null) {
                        Log.i(DEBUG_TAG, "Final Action: Executing P1 top cropped bitmap update (Style A).")
                        Log.e(DEBUG_TAG, "DECISION: P1 top update selected.")
                        updateTopCroppedBitmapAsync()
                        partialBitmapUpdateTriggered = true
                    } else if (imageUriString != null) {
                        Log.w(DEBUG_TAG, "Skipping P1 top update (source bitmap missing), will force full reload if no other update occurs or try redraw.")
                    }
                }

                // 如果没有任何位图更新被成功触发（例如因为基础位图丢失），但有URI，则可能需要完整重载
                if (!partialBitmapUpdateTriggered && imageUriString != null &&
                    (needsOnlyGlobalBlurUpdate || needsStyleBP1MaskBlurUpdate || needsP1TopUpdate)) {
                    Log.w(DEBUG_TAG, "Final Action: A partial bitmap update was needed but base bitmaps were missing. Forcing full reload.")
                    loadFullBitmapsAsync()
                }
                // 如果只有重绘标记，或者没有任何更新标记但壁纸可见（例如，外部调用了 prefs.edit().commit() 但没有实际值变化）
                else if (needsRedrawOnly && isVisible) {
                    Log.i(DEBUG_TAG, "Final Action: Executing redraw only.")
                    drawCurrentFrame()
                } else if (isVisible && !partialBitmapUpdateTriggered && key == null && oldImageContentVersion == currentImageContentVersion) {
                    // 针对 key 为 null 且版本号未变的情况，如果可见也重绘一次
                    Log.i(DEBUG_TAG, "Final Action: key was null, version unchanged, visible. Executing redraw.")
                    drawCurrentFrame()
                }
            }
        }

        /**
         * 异步仅更新壁纸的模糊背景图 (`engineWallpaperBitmaps.blurredScrollingBackgroundBitmap`)。
         * 当只有模糊相关参数发生变化时调用此方法，以避免重新加载和处理所有位图。
         * 它会使用当前已有的未模糊滚动背景图 (`scrollingBackgroundBitmap`) 作为基础，
         * 应用新的模糊参数重新生成模糊版本。
         */
        private fun updateOnlyBlurredBackgroundAsync() {
            currentBlurUpdateJob?.cancel() // 取消上一个正在进行的模糊更新任务
            currentBitmapLoadJob?.cancel() // 同时取消可能正在进行的完整加载任务

            val baseForBlur = engineWallpaperBitmaps?.scrollingBackgroundBitmap // 获取用于模糊的基础图
            // 如果没有基础图，或屏幕尺寸无效，或没有图片URI，则无法执行
            if (baseForBlur == null || screenWidth <= 0 || screenHeight <= 0 || imageUriString == null) {
                if (imageUriString != null) { // 如果有URI但没有基础图，说明状态有问题，尝试完整重载
                    Log.w(DEBUG_TAG, "updateOnlyBlurredBackgroundAsync: Base scrolling bitmap is null. Attempting full reload.")
                    loadFullBitmapsAsyncIfNeeded() //
                } else if (isVisible) { // 如果没有URI，且壁纸可见，则绘制占位符
                    drawCurrentFrame() //
                }
                return
            }

            Log.i(DEBUG_TAG, "updateOnlyBlurredBackgroundAsync: Starting for current scrolling background. BlurR=$currentBackgroundBlurRadius, DF=$currentBlurDownscaleFactor, It=$currentBlurIterations")

            // 启动协程执行异步模糊处理
            currentBlurUpdateJob = engineScope.launch {
                var newBlurredBitmap: Bitmap? = null
                try {
                    ensureActive()
                    // 在IO调度器上执行耗时的模糊操作
                    newBlurredBitmap = withContext(Dispatchers.IO) {
                        ensureActive()
                        // 调用共享渲染器重新生成模糊位图
                        SharedWallpaperRenderer.regenerateBlurredBitmap( //
                            context = applicationContext,
                            baseBitmap = baseForBlur,
                            targetWidth = baseForBlur.width, // 目标尺寸与基础图一致
                            targetHeight = baseForBlur.height,
                            blurRadius = currentBackgroundBlurRadius,
                            blurDownscaleFactor = currentBlurDownscaleFactor,
                            blurIterations = currentBlurIterations
                        )
                    }
                    ensureActive() // 返回主线程后再次检查活动状态

                    val oldBlurred = engineWallpaperBitmaps?.blurredScrollingBackgroundBitmap
                    // 检查在异步处理期间，图片URI和基础图是否未发生变化
                    if (this@H2WallpaperEngine.imageUriString != null && engineWallpaperBitmaps?.scrollingBackgroundBitmap == baseForBlur) {
                        engineWallpaperBitmaps?.blurredScrollingBackgroundBitmap = newBlurredBitmap // 更新模糊图
                        if (oldBlurred != newBlurredBitmap) oldBlurred?.recycle() // 回收旧的模糊图
                        Log.i(DEBUG_TAG, "updateOnlyBlurredBackgroundAsync: Successfully updated blurred background.")
                    } else { // 如果条件变化，则丢弃新生成的位图
                        Log.w(DEBUG_TAG, "updateOnlyBlurredBackgroundAsync: Conditions changed during async operation. Discarding result.")
                        newBlurredBitmap?.recycle()
                    }
                } catch (e: CancellationException) {
                    Log.d(TAG, "updateOnlyBlurredBackgroundAsync cancelled.")
                    newBlurredBitmap?.recycle()
                } catch (e: Exception) {
                    Log.e(TAG, "updateOnlyBlurredBackgroundAsync failed", e)
                    newBlurredBitmap?.recycle()
                    // 如果失败的是当前图片和基础图，则可以将模糊图设为null
                    if (this@H2WallpaperEngine.imageUriString != null && engineWallpaperBitmaps?.scrollingBackgroundBitmap == baseForBlur) {
                        engineWallpaperBitmaps?.blurredScrollingBackgroundBitmap = null
                    }
                } finally {
                    // 如果协程仍然活动且当前 Job 是 currentBlurUpdateJob，则清空 Job 引用
                    if (isActive && coroutineContext[Job] == currentBlurUpdateJob) currentBlurUpdateJob = null
                    // 如果壁纸可见且有图片URI，则在操作完成后重绘
                    if (isVisible && this@H2WallpaperEngine.imageUriString != null) {
                        drawCurrentFrame() //
                    }
                }
            }
        }

        /**
         * 从 SharedPreferences 加载所有壁纸配置参数到引擎的成员变量中。
         * 此方法会被 `onCreate` 和 `onSharedPreferenceChanged` 调用。
         */
        private fun loadPreferencesFromStorage() {
            imageUriString = preferencesRepository.getSelectedImageUri()?.toString() //
            Log.d(DEBUG_TAG, "0002 loadPreferencesFromStorage: imageUriString loaded as: $imageUriString")
            page1BackgroundColor = preferencesRepository.getSelectedBackgroundColor() //
            page1ImageHeightRatio = preferencesRepository.getPage1ImageHeightRatio() //
            currentP1FocusX = preferencesRepository.getP1FocusX() //
            currentP1FocusY = preferencesRepository.getP1FocusY() //
            currentP1ContentScaleFactor = preferencesRepository.getP1ContentScaleFactor() //

            currentScrollSensitivity = preferencesRepository.getScrollSensitivity() //
            currentP1OverlayFadeRatio = preferencesRepository.getP1OverlayFadeRatio() //
            currentBackgroundBlurRadius = preferencesRepository.getBackgroundBlurRadius() //
            currentNormalizedInitialBgScrollOffset = preferencesRepository.getBackgroundInitialOffset() //
            currentP2BackgroundFadeInRatio = preferencesRepository.getP2BackgroundFadeInRatio() //
            currentBlurDownscaleFactor = preferencesRepository.getBlurDownscaleFactor() //
            currentBlurIterations = preferencesRepository.getBlurIterations() //
            currentP1ShadowRadius = preferencesRepository.getP1ShadowRadius() //
            currentP1ShadowDx = preferencesRepository.getP1ShadowDx() //
            currentP1ShadowDy = preferencesRepository.getP1ShadowDy() //
            currentP1ShadowColor = preferencesRepository.getP1ShadowColor() //
            currentP1ImageBottomFadeHeight = preferencesRepository.getP1ImageBottomFadeHeight() //
            currentImageContentVersion = preferencesRepository.getImageContentVersion() //
            Log.d(DEBUG_TAG, "0002 loadPreferencesFromStorage: currentImageContentVersion loaded as: $currentImageContentVersion")
            // Load new style parameters
            currentP1StyleType = preferencesRepository.getP1StyleType()
            currentStyleBMaskAlpha = preferencesRepository.getStyleBMaskAlpha()
            currentStyleBRotationParamA = preferencesRepository.getStyleBRotationParamA()
            currentStyleBGapSizeRatio = preferencesRepository.getStyleBGapSizeRatio()
            currentStyleBGapPositionYRatio = preferencesRepository.getStyleBGapPositionYRatio()
            currentStyleBUpperMaskMaxRotation = preferencesRepository.getStyleBUpperMaskMaxRotation()
            currentStyleBLowerMaskMaxRotation = preferencesRepository.getStyleBLowerMaskMaxRotation()
            currentStyleBP1FocusX = preferencesRepository.getStyleBP1FocusX()
            currentStyleBP1FocusY = preferencesRepository.getStyleBP1FocusY()
            currentStyleBP1ScaleFactor = preferencesRepository.getStyleBP1ScaleFactor()
            currentStyleBMasksHorizontallyFlipped = preferencesRepository.getStyleBMasksHorizontallyFlipped()
            currentStyleBP1MaskBlurRadius = preferencesRepository.getStyleBBlurRadius()
            currentStyleBP1MaskBlurDownscale = preferencesRepository.getStyleBBlurDownscaleFactor()
            currentStyleBP1MaskBlurIterations = preferencesRepository.getStyleBBlurIterations()

            Log.i(DEBUG_TAG, "Prefs loaded (Service): URI=$imageUriString, P1H=$page1ImageHeightRatio, P1F=(${currentP1FocusX},${currentP1FocusY}), P1S=$currentP1ContentScaleFactor, Version=$currentImageContentVersion, P1Style=$currentP1StyleType")
        }

        /**
         * 异步加载所有渲染所需的位图资源。
         * 此方法会取消任何正在进行的 `currentBitmapLoadJob`。
         * 如果 `imageUriString` 为空或屏幕尺寸无效，则会清除现有位图并尝试绘制占位符。
         * 否则，它会启动一个协程，在后台线程通过 `SharedWallpaperRenderer.loadAndProcessInitialBitmaps`
         * 加载和处理图片，然后将结果更新到 `engineWallpaperBitmaps`，并回收旧资源。
         * 加载完成后会触发重绘。
         */
        private fun loadFullBitmapsAsync() {
            currentBitmapLoadJob?.cancel() // 取消上一个完整加载任务
            // 注意：这里不立即回收或清空 engineWallpaperBitmaps，是为了避免在加载新图时屏幕闪烁占位符。
            // 旧的位图会一直显示，直到新的位图加载完成并替换掉它。

            val uriStringToLoad = imageUriString // 获取当前配置的图片URI
            Log.e(DEBUG_TAG, "0002 !!!!! LOAD_FULL_BITMAPS_ASYNC_CALLED !!!!! URI: $uriStringToLoad")
            // 如果URI为空或屏幕尺寸无效，则无法加载
            Log.i(DEBUG_TAG, "0002 loadFullBitmapsAsync: Starting for URI: $uriStringToLoad. Current engine imageUriString: ${this.imageUriString}")
            if (uriStringToLoad == null || screenWidth <= 0 || screenHeight <= 0) {
                // 如果之前有位图（例如，用户清除了图片选择），则回收它们
                if (engineWallpaperBitmaps != null) {
                    engineWallpaperBitmaps?.recycleInternals()
                    engineWallpaperBitmaps = null
                }
                if (isVisible) drawCurrentFrame() // 绘制占位符（如果可见）
                return
            }
            val uri = Uri.parse(uriStringToLoad)
            Log.i(DEBUG_TAG, "0002 loadFullBitmapsAsync: Starting for URI: $uri. Focus:($currentP1FocusX, $currentP1FocusY), Scale:$currentP1ContentScaleFactor, Blur:$currentBackgroundBlurRadius")

            // 如果壁纸可见但当前还没有任何位图，先绘制一次占位符（显示加载中）
            if (isVisible && engineWallpaperBitmaps == null) {
                drawCurrentFrame() //
            }

            // 启动协程执行异步加载
            currentBitmapLoadJob = engineScope.launch {
                var newBitmapsHolder: SharedWallpaperRenderer.WallpaperBitmaps? = null
                try {
                    ensureActive()
                    // 在IO调度器上执行耗时的位图加载和处理
                    newBitmapsHolder = withContext(Dispatchers.IO) {
                        ensureActive()
                        // 调用共享渲染器加载和处理所有初始位图
                        SharedWallpaperRenderer.loadAndProcessInitialBitmaps( //
                            context = applicationContext,
                            imageUri = uri,
                            targetScreenWidth = screenWidth,
                            targetScreenHeight = screenHeight,
                            page1ImageHeightRatio = page1ImageHeightRatio,
                            normalizedFocusX = currentP1FocusX,
                            normalizedFocusY = currentP1FocusY,
                            contentScaleFactorForP1 = currentP1ContentScaleFactor,
                            blurRadiusForBackground = currentBackgroundBlurRadius,
                            blurDownscaleFactor = currentBlurDownscaleFactor,
                            blurIterations = currentBlurIterations,
                            styleBP1MaskBlurRadius = currentStyleBP1MaskBlurRadius,
                            styleBP1MaskBlurDownscale = currentStyleBP1MaskBlurDownscale,
                            styleBP1MaskBlurIterations = currentStyleBP1MaskBlurIterations
                        )
                    }
                    if (newBitmapsHolder == null || newBitmapsHolder.sourceSampledBitmap == null) {
                        Log.e(DEBUG_TAG, "0002 loadFullBitmapsAsync: newBitmapsHolder or its sourceSampledBitmap is null after loading from $uriStringToLoad.")
                    } else {
                        Log.i(DEBUG_TAG, "0002 loadFullBitmapsAsync: Successfully loaded bitmaps for $uriStringToLoad. Source size: ${newBitmapsHolder.sourceSampledBitmap?.width}x${newBitmapsHolder.sourceSampledBitmap?.height}")
                    }
                    ensureActive() // 返回主线程后再次检查活动状态

                    val oldBitmaps = engineWallpaperBitmaps // 保存对旧位图资源的引用

                    Log.i(DEBUG_TAG, "0002 loadFullBitmapsAsync: About to assign new bitmaps. Current engine URI: ${this@H2WallpaperEngine.imageUriString}, URI used for load: $uriStringToLoad")
                    // 再次检查在异步加载期间，用户配置的图片 URI 是否与本次加载的 URI 一致
                    if (this@H2WallpaperEngine.imageUriString == uriStringToLoad) {
                        engineWallpaperBitmaps = newBitmapsHolder // 更新为新加载的位图
                        // 如果新旧位图不是同一个对象，则回收旧的（非常重要，防止内存泄漏）
                        if (oldBitmaps != newBitmapsHolder) oldBitmaps?.recycleInternals()
                    } else { // 如果在加载期间 URI 发生了变化 (例如用户快速切换图片)
                        Log.w(DEBUG_TAG, "0002 loadFullBitmapsAsync: URI changed during async load. Discarding loaded bitmaps for $uriStringToLoad. Current engine URI is ${this@H2WallpaperEngine.imageUriString}")
                        newBitmapsHolder?.recycleInternals() // 新加载的位图作废，不使用
                        // 如果当前配置的 URI 已被清除 (变为 null)，则也清除旧的位图资源
                        if (this@H2WallpaperEngine.imageUriString == null && oldBitmaps != null) {
                            oldBitmaps.recycleInternals()
                            engineWallpaperBitmaps = null
                        }
                        // 如果 URI 变成了另一个值，则当前 loadFullBitmapsAsync 的结果作废，
                        // 等待后续针对新 URI 的加载操作。
                    }
                } catch (e: CancellationException) {
                    Log.d(TAG, "loadFullBitmapsAsync cancelled for $uriStringToLoad")
                    newBitmapsHolder?.recycleInternals() // 即使取消也要确保回收可能已创建的位图
                } catch (e: Exception) {
                    Log.e(TAG, "loadFullBitmapsAsync failed for $uriStringToLoad", e)
                    newBitmapsHolder?.recycleInternals()
                    // 如果加载失败的是当前配置的 URI，则清除引擎持有的位图资源
                    if (this@H2WallpaperEngine.imageUriString == uriStringToLoad) {
                        engineWallpaperBitmaps?.recycleInternals()
                        engineWallpaperBitmaps = null
                    }
                } finally {
                    // 如果协程仍然活动且当前 Job 是 currentBitmapLoadJob，则清空 Job 引用
                    if (isActive && coroutineContext[Job] == currentBitmapLoadJob) currentBitmapLoadJob = null
                    // 如果壁纸可见，并且当前配置的 URI 与加载的 URI 匹配，或者当前 URI 已为 null (表示已清除图片)，则触发重绘
                    // 无论成功失败，都尝试重绘以反映最新状态 (可能是图片，可能是加载失败的占位符)
                    if (isVisible && (this@H2WallpaperEngine.imageUriString == uriStringToLoad || this@H2WallpaperEngine.imageUriString == null)) {
                        drawCurrentFrame() //
                    }
                }
            }
        }

        /**
         * 根据需要异步加载所有位图。
         * 仅在以下情况会实际触发 `loadFullBitmapsAsync()`：
         * - 当前配置了图片 URI (`imageUriString != null`)。
         * - 屏幕尺寸有效 (`screenWidth > 0 && screenHeight > 0`)。
         * - 当前没有正在进行的加载任务 (`currentBitmapLoadJob?.isActive != true`)。
         * - 当前引擎没有有效的源位图 (`engineWallpaperBitmaps?.sourceSampledBitmap == null`)。
         *
         * 如果没有配置图片 URI，则会取消加载任务并清除现有位图。
         * 如果条件不满足加载但壁纸可见，会触发一次重绘（可能绘制占位符或现有图像）。
         */
        private fun loadFullBitmapsAsyncIfNeeded() {
            if (imageUriString != null && screenWidth > 0 && screenHeight > 0) { // 有图有尺寸
                // 如果没有正在加载的任务，并且当前没有源图，则启动完整加载
                if (currentBitmapLoadJob?.isActive != true && engineWallpaperBitmaps?.sourceSampledBitmap == null) {
                    loadFullBitmapsAsync() //
                } else if (isVisible) { // 否则，如果可见，直接绘制当前帧 (可能已有图，或正在加载中)
                    drawCurrentFrame() //
                }
            } else if (imageUriString == null) { // 没有图片 URI
                currentBitmapLoadJob?.cancel(); currentBitmapLoadJob = null // 取消加载任务
                engineWallpaperBitmaps?.recycleInternals(); engineWallpaperBitmaps = null // 清除位图
                if (isVisible) drawCurrentFrame() // 绘制占位符
            }
            // 如果 imageUriString != null 但屏幕尺寸无效，则不执行任何操作，等待 onSurfaceChanged
        }

        /**
         * 当壁纸的 Surface 尺寸发生变化时调用 (例如屏幕旋转)。
         * 更新 `screenWidth` 和 `screenHeight`，并根据需要重新加载位图。
         * @param holder SurfaceHolder。
         * @param format Surface 的像素格式。
         * @param width 新的宽度。
         * @param height 新的高度。
         */
        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            val oldScreenWidth = screenWidth; val oldScreenHeight = screenHeight
            this.screenWidth = width; this.screenHeight = height
            Log.i(TAG, "Surface changed: New $width x $height. Old: ${oldScreenWidth}x${oldScreenHeight}. IsPreview: $isPreview")
            // 如果新的宽度和高度都有效，则按需加载位图 (通常尺寸变化会导致位图需要重新生成)
            if (width > 0 && height > 0) {
                loadFullBitmapsAsyncIfNeeded() //
            } else { // 尺寸无效，取消加载并清除位图
                currentBitmapLoadJob?.cancel()
                engineWallpaperBitmaps?.recycleInternals()
                engineWallpaperBitmaps = null
            }
        }

        /**
         * 当壁纸的 Surface 被销毁时调用。
         * 标记壁纸为不可见。位图资源将在 `Engine.onDestroy()` 中统一清理。
         * @param holder SurfaceHolder。
         */
        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            isVisible = false // 标记为不可见，避免不必要的绘制尝试
            Log.i(TAG, "H2WallpaperEngine Surface destroyed.")
            // 实际的资源清理（如协程取消、位图回收）在 onDestroy 中进行
        }

        /**
         * 当壁纸的可见性发生变化时调用。
         * @param visible true 表示壁纸变为可见，false 表示变为不可见。
         */
        override fun onVisibilityChanged(visible: Boolean) {
            this.isVisible = visible
            Log.i(TAG, "Visibility changed: $visible. IsPreview: $isPreview")
            if (visible) {
                // 当壁纸变为可见时，确保位图已加载（如果需要）并绘制第一帧
                loadFullBitmapsAsyncIfNeeded() // 此方法内部会判断是否需要加载，如果已加载则会触发绘制
            }
            // 不可见时，通常不需要特别操作，绘制循环会自然停止
        }

        /**
         * 当壁纸的横向滚动偏移量发生变化时调用（用户在主屏幕左右滑动页面）。
         * @param xOffset 新的X轴偏移量 (0.0 到 1.0 之间，或根据启动器页数可能超出)。
         * @param yOffset Y轴偏移量 (通常为0)。
         * @param xOffsetStep X轴偏移步长，表示相邻两个逻辑页面之间的偏移差。
         * @param yOffsetStep Y轴偏移步长。
         * @param xPixelOffset X轴像素偏移。
         * @param yPixelOffset Y轴像素偏移。
         */
        override fun onOffsetsChanged(xOffset: Float, yOffset: Float, xOffsetStepParam: Float, yOffsetStep: Float, xPixelOffset: Int, yPixelOffset: Int) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStepParam, yOffsetStep, xPixelOffset, yPixelOffset)
            val oldFrameworkOffset = this.currentPageOffset
            this.currentPageOffset = xOffset // 更新当前页面偏移量

            // 根据 xOffsetStepParam 尝试计算启动器报告的页面总数
            // 不同的启动器对 xOffsetStep 的实现可能不同
            val newlyCalculatedPages = if (xOffsetStepParam > 0.00001f && xOffsetStepParam < 1.0f) {
                // 常见情况：xOffsetStep = 1 / (N - 1)，其中 N 是总页数
                (1.0f / xOffsetStepParam).roundToInt() + 1
            } else {
                1 // 如果 xOffsetStep 无效或为0 (或1.0)，则认为是单页或无法计算
            }
            this.numPagesReportedByLauncher = newlyCalculatedPages.coerceIn(1, 20) // 限制页数在合理范围

            // Log.i(DEBUG_TAG, "onOffsetsChanged: xOffset=$xOffset, xOffsetStepParam=$xOffsetStepParam, Calculated total pages=${this.numPagesReportedByLauncher}")

            // 仅当偏移量实际发生改变时才重绘，以避免不必要的绘制
            if (oldFrameworkOffset != xOffset) {
                if (isVisible && surfaceHolder?.surface?.isValid == true) {
                    drawCurrentFrame() //
                }
            }
            // 注意：启动器报告的页面数量 (numPagesReportedByLauncher) 的变化，
            // 通常不会直接触发位图的重新加载。SharedWallpaperRenderer 使用的 numVirtualPages
            // 是用于计算过渡效果的，而背景图的实际滚动范围由其自身宽度和屏幕宽度决定。
        }

        /**
         * 异步仅更新 P1 顶部裁剪后的位图 (`engineWallpaperBitmaps.page1TopCroppedBitmap`)。
         * 当 P1 的高度、焦点或内容缩放等参数发生变化时（通常由 `onSharedPreferenceChanged` 触发），
         * 调用此方法以避免重新加载整个源图片，只重新生成 P1 前景图。
         */
        private fun updateTopCroppedBitmapAsync() {
            val currentSourceBitmap = engineWallpaperBitmaps?.sourceSampledBitmap
            // 如果没有源图，或屏幕尺寸无效，或没有图片URI，则无法执行
            if (currentSourceBitmap == null || screenWidth <= 0 || screenHeight <= 0 || imageUriString == null) {
                if (imageUriString != null) loadFullBitmapsAsyncIfNeeded() // 如果有URI但没源图，尝试重载
                else if (isVisible) drawCurrentFrame() // 没URI，画占位符
                return
            }
            Log.i(DEBUG_TAG, "updateTopCroppedBitmapAsync: R=$page1ImageHeightRatio, F=($currentP1FocusX,$currentP1FocusY), CS=$currentP1ContentScaleFactor")

            // 启动协程执行异步处理
            val p1TopUpdateJob = engineScope.launch { // 可以重命名 job 变量以示区分
                var newTopCropped: Bitmap? = null
                val oldTopCropped = engineWallpaperBitmaps?.page1TopCroppedBitmap // 保存对旧P1顶图的引用
                try {
                    ensureActive()
                    // 在默认调度器上执行耗时的位图处理
                    newTopCropped = withContext(Dispatchers.Default) {
                        ensureActive()
                        // 调用共享渲染器准备P1顶部裁剪图
                        SharedWallpaperRenderer.preparePage1TopCroppedBitmap( //
                            currentSourceBitmap, screenWidth, screenHeight,
                            page1ImageHeightRatio, currentP1FocusX, currentP1FocusY,
                            currentP1ContentScaleFactor
                        )
                    }
                    ensureActive() // 返回主线程后再次检查活动状态

                    // 检查在异步处理期间，图片URI和源位图是否未发生变化
                    if (this@H2WallpaperEngine.imageUriString != null && engineWallpaperBitmaps?.sourceSampledBitmap == currentSourceBitmap) {
                        // 如果新旧P1顶图不是同一个对象，则回收旧的
                        if (oldTopCropped != newTopCropped) oldTopCropped?.recycle()
                        engineWallpaperBitmaps?.page1TopCroppedBitmap = newTopCropped // 更新P1顶图
                    } else { // 如果条件变化，则丢弃新生成的位图
                        newTopCropped?.recycle()
                    }
                } catch (e: CancellationException) {
                    Log.d(TAG, "updateTopCroppedBitmapAsync cancelled.")
                    newTopCropped?.recycle()
                } catch (e: Exception) {
                    Log.e(TAG, "updateTopCroppedBitmapAsync failed", e)
                    newTopCropped?.recycle()
                    // 如果失败的是当前图片和源图，则将P1顶图设为null
                    if (this@H2WallpaperEngine.imageUriString != null && engineWallpaperBitmaps?.sourceSampledBitmap == currentSourceBitmap) {
                        engineWallpaperBitmaps?.page1TopCroppedBitmap = null
                    }
                } finally {
                    // 如果壁纸可见且有图片URI，则在操作完成后重绘
                    if (isActive && isVisible && this@H2WallpaperEngine.imageUriString != null) {
                        drawCurrentFrame() //
                    }
                }
            }
        }


        /**
         * 绘制当前壁纸帧到 Surface 上。
         * 此方法会获取当前的 Canvas，并根据是否有有效的位图资源来决定：
         * - 如果有位图：调用 `SharedWallpaperRenderer.drawFrame()` 进行绘制。
         * - 如果没有位图或位图未准备好：调用 `SharedWallpaperRenderer.drawPlaceholder()` 绘制占位符。
         *
         * 绘制操作会在 try-finally 块中进行，以确保 Canvas 被正确解锁。
         * 仅当壁纸可见 (`isVisible`) 且 Surface 有效 (`surfaceHolder?.surface?.isValid`)
         * 且屏幕尺寸有效时才执行绘制。
         */
        private fun drawCurrentFrame() {
            if (!isVisible || surfaceHolder?.surface?.isValid != true || screenWidth == 0 || screenHeight == 0) {
                return
            }

            var canvas: Canvas? = null
            try {
                canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    surfaceHolder!!.lockHardwareCanvas()
                } else {
                    surfaceHolder!!.lockCanvas()
                }

                if (canvas != null) {
                    val currentWpBitmaps = engineWallpaperBitmaps
                    if (currentWpBitmaps?.sourceSampledBitmap != null &&
                        // 对于样式A，需要page1TopCroppedBitmap；对于样式B，P1背景是sourceSampledBitmap本身，遮罩模糊是styleBBlurredBitmap
                        // 所以这里的条件可以调整为：有源图，并且如果是样式A，则P1顶图也要有。
                        // 或者更简单：只要有源图就尝试绘制，Renderer内部会处理。
                        // 为了安全，先保持原条件，如果样式B不需要page1TopCroppedBitmap，后续可优化。
                        // 但 SharedRenderer.drawStyleBLayer 内部如果 p1FullScreenBackgroundBitmap (即 sourceSampledBitmap) 为null会绘制占位符，
                        // 所以这里的检查主要针对 sourceSampledBitmap。
                        // 如果是样式A，page1TopCroppedBitmap 也需要存在。
                        (currentP1StyleType == 1 /* STYLE_B */ || currentWpBitmaps.page1TopCroppedBitmap != null) // 样式B不依赖page1TopCroppedBitmap作为前景
                    ) {
                        val pagesForRenderer = if (!isPreview && numPagesReportedByLauncher > 1) {
                            numPagesReportedByLauncher
                        } else {
                            DEFAULT_VIRTUAL_PAGES_FOR_SCROLLING
                        }

                        // --- 为样式B计算P1背景的变换矩阵 ---
                        var styleBTransformForService: Matrix? = null
                        if (currentP1StyleType == 1 /* STYLE_B */ && currentWpBitmaps.sourceSampledBitmap != null) {
                            styleBTransformForService = Matrix()
                            SharedWallpaperRenderer.calculateMatrixFromFocusAndScale(
                                currentWpBitmaps.sourceSampledBitmap, // 源图
                                screenWidth.toFloat(),               // 目标宽度 (全屏)
                                screenHeight.toFloat(),              // 目标高度 (全屏)
                                currentStyleBP1FocusX,               // 保存的样式B焦点X
                                currentStyleBP1FocusY,               // 保存的样式B焦点Y
                                currentStyleBP1ScaleFactor,          // 保存的样式B缩放因子
                                styleBTransformForService            // 输出矩阵
                            )
                        }
                        // --- 变换矩阵计算结束 ---

                        val config = SharedWallpaperRenderer.WallpaperConfig(
                            screenWidth = screenWidth,
                            screenHeight = screenHeight,
                            page1BackgroundColor = page1BackgroundColor,
                            page1ImageHeightRatio = if (currentP1StyleType == 1) 1.0f else page1ImageHeightRatio, // 样式B P1是全屏
                            currentXOffset = currentPageOffset,
                            numVirtualPages = pagesForRenderer,
                            p1OverlayFadeTransitionRatio = currentP1OverlayFadeRatio,
                            scrollSensitivityFactor = currentScrollSensitivity,
                            normalizedInitialBgScrollOffset = currentNormalizedInitialBgScrollOffset,
                            p2BackgroundFadeInRatio = currentP2BackgroundFadeInRatio,
                            // 样式B的P1前景通常没有独立的投影或底部融入
                            p1ShadowRadius = if (currentP1StyleType == 1) 0f else currentP1ShadowRadius,
                            p1ShadowDx = if (currentP1StyleType == 1) 0f else currentP1ShadowDx,
                            p1ShadowDy = if (currentP1StyleType == 1) 0f else currentP1ShadowDy,
                            p1ShadowColor = currentP1ShadowColor,
                            p1ImageBottomFadeHeight = if (currentP1StyleType == 1) 0f else currentP1ImageBottomFadeHeight,

                            p1StyleType = currentP1StyleType,
                            styleBMaskAlpha = currentStyleBMaskAlpha,
                            styleBRotationParamA = currentStyleBRotationParamA,
                            styleBGapSizeRatio = currentStyleBGapSizeRatio,
                            styleBGapPositionYRatio = currentStyleBGapPositionYRatio,
                            styleBUpperMaskMaxRotation = currentStyleBUpperMaskMaxRotation,
                            styleBLowerMaskMaxRotation = currentStyleBLowerMaskMaxRotation,
                            // 样式B的P1背景焦点和缩放参数 (这些主要是为了信息传递，实际变换已在styleBTransformForService中)
                            styleBP1FocusX = currentStyleBP1FocusX,
                            styleBP1FocusY = currentStyleBP1FocusY,
                            styleBP1ScaleFactor = currentStyleBP1ScaleFactor,
                            styleBMasksHorizontallyFlipped = if (currentP1StyleType == 1) currentStyleBMasksHorizontallyFlipped else false,
                            styleBModeP1MaskBlurRadius = currentStyleBP1MaskBlurRadius,
                            styleBModeP1MaskBlurDownscale = currentStyleBP1MaskBlurDownscale,
                            styleBModeP1MaskBlurIterations = currentStyleBP1MaskBlurIterations,
                            // 将计算得到的变换矩阵传递给渲染器
                            styleBP1BackgroundTransform = styleBTransformForService
                        )
                        SharedWallpaperRenderer.drawFrame(canvas, config, currentWpBitmaps)
                    } else {
                        // 绘制占位符
                        SharedWallpaperRenderer.drawPlaceholder(
                            canvas, screenWidth, screenHeight,
                            if (imageUriString != null && (currentBitmapLoadJob?.isActive == true)) "壁纸加载中..."
                            else if (imageUriString == null) "请选择图片 (服务)"
                            else "图片资源准备中..."
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during drawFrame", e)
            } finally {
                if (canvas != null) {
                    try {
                        surfaceHolder!!.unlockCanvasAndPost(canvas)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error unlocking canvas", e)
                    }
                }
            }
        }

        /**
         * 异步仅更新样式 B 的 P1 遮罩模糊背景图 (engineWallpaperBitmaps.styleBBlurredBitmap)。
         * 当样式 B 的专属模糊参数发生变化时调用此方法。
         */
        private fun updateStyleBP1MaskBlurredBitmapAsync() {
            currentStyleBBlurUpdateJob?.cancel() // 取消上一个正在进行的样式B P1遮罩模糊更新任务
            // currentBitmapLoadJob?.cancel() // 通常不需要取消完整加载，因为我们基于已有的 sourceSampled

            val sourceBitmapToUse = engineWallpaperBitmaps?.sourceSampledBitmap
            if (sourceBitmapToUse == null || screenWidth <= 0 || screenHeight <= 0 || imageUriString == null) {
                Log.w(DEBUG_TAG, "updateStyleBP1MaskBlurredBitmapAsync: Conditions not met (no source bitmap, invalid screen size, or no URI).")
                if (imageUriString != null && sourceBitmapToUse == null) {
                    // 如果有URI但没有源图，说明状态可能不一致，可能需要完整重载
                    loadFullBitmapsAsyncIfNeeded()
                }
                return
            }

            Log.i(DEBUG_TAG, "updateStyleBP1MaskBlurredBitmapAsync: Starting. Style B P1 Mask BlurR=$currentStyleBP1MaskBlurRadius, DF=$currentStyleBP1MaskBlurDownscale, It=$currentStyleBP1MaskBlurIterations")

            currentStyleBBlurUpdateJob = engineScope.launch {
                var newStyleBBlurred: Bitmap? = null
                try {
                    ensureActive()
                    newStyleBBlurred = withContext(Dispatchers.IO) {
                        ensureActive()
                        SharedWallpaperRenderer.regenerateStyleBBlurredBitmap(
                            context = applicationContext,
                            sourceSampledBitmap = sourceBitmapToUse,
                            // 使用当前引擎持有的样式 B 专属模糊参数
                            styleBP1MaskBlurRadius = currentStyleBP1MaskBlurRadius,
                            styleBP1MaskBlurDownscale = currentStyleBP1MaskBlurDownscale,
                            styleBP1MaskBlurIterations = currentStyleBP1MaskBlurIterations
                        )
                    }
                    ensureActive()

                    val oldStyleBBlurred = engineWallpaperBitmaps?.styleBBlurredBitmap
                    // 再次检查在异步处理期间，图片URI和源位图是否未发生变化
                    if (this@H2WallpaperEngine.imageUriString != null && engineWallpaperBitmaps?.sourceSampledBitmap == sourceBitmapToUse) {
                        engineWallpaperBitmaps?.styleBBlurredBitmap = newStyleBBlurred
                        if (oldStyleBBlurred != newStyleBBlurred) oldStyleBBlurred?.recycle() // 回收旧的
                        Log.i(DEBUG_TAG, "updateStyleBP1MaskBlurredBitmapAsync: Successfully updated Style B P1 Mask blurred background.")
                    } else {
                        Log.w(DEBUG_TAG, "updateStyleBP1MaskBlurredBitmapAsync: Conditions changed during async operation. Discarding result.")
                        newStyleBBlurred?.recycle()
                    }
                } catch (e: CancellationException) {
                    Log.d(TAG, "updateStyleBP1MaskBlurredBitmapAsync cancelled.")
                    newStyleBBlurred?.recycle()
                } catch (e: Exception) {
                    Log.e(TAG, "updateStyleBP1MaskBlurredBitmapAsync failed", e)
                    newStyleBBlurred?.recycle()
                    // 如果失败，确保旧的也被清理或标记为null
                    if (this@H2WallpaperEngine.imageUriString != null && engineWallpaperBitmaps?.sourceSampledBitmap == sourceBitmapToUse) {
                        engineWallpaperBitmaps?.styleBBlurredBitmap = null
                    }
                } finally {
                    if (isActive && coroutineContext[Job] == currentStyleBBlurUpdateJob) currentStyleBBlurUpdateJob = null
                    if (isVisible && this@H2WallpaperEngine.imageUriString != null) {
                        drawCurrentFrame() // 完成后重绘
                    }
                }
            }
        }

        /**
         * 当壁纸引擎被销毁时调用。
         * 在此清理所有资源：注销 SharedPreferences 监听器、取消所有协程、回收所有位图。
         */
        override fun onDestroy() {
            super.onDestroy()
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            engineScope.cancel("H2WallpaperEngine destroyed")
            currentBitmapLoadJob = null // 清理引用
            currentBlurUpdateJob = null // 清理引用
            currentStyleBBlurUpdateJob = null // <--- 新增：清理引用
            engineWallpaperBitmaps?.recycleInternals()
            engineWallpaperBitmaps = null
            Log.i(TAG, "H2WallpaperEngine destroyed.")
        }
    }
}