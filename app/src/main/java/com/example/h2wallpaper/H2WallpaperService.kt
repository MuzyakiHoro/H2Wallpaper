// H2WallpaperService.kt
package com.example.h2wallpaper

import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

class H2WallpaperService : WallpaperService() {

    companion object {
        private const val TAG = "H2WallpaperSvc"
        private const val DEBUG_TAG = "H2WallpaperSvc_Debug"
        private const val DEFAULT_VIRTUAL_PAGES_FOR_SCROLLING = 3
    }

    override fun onCreateEngine(): Engine {
        return H2WallpaperEngine()
    }

    private inner class H2WallpaperEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private var surfaceHolder: SurfaceHolder? = null
        private var isVisible: Boolean = false
        private var screenWidth: Int = 0
        private var screenHeight: Int = 0

        private var engineWallpaperBitmaps: SharedWallpaperRenderer.WallpaperBitmaps? = null
        private val engineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        private var currentBitmapLoadJob: Job? = null

        // --- Configuration members, loaded from SharedPreferences or MainActivity defaults ---
        private var imageUriString: String? = null
        private var page1BackgroundColor: Int = Color.LTGRAY
        private var page1ImageHeightRatio: Float = MainActivity.DEFAULT_HEIGHT_RATIO
        private var currentScrollSensitivity: Float = MainActivity.DEFAULT_SCROLL_SENSITIVITY
        private var currentP1FocusX: Float = 0.5f
        private var currentP1FocusY: Float = 0.5f
        private var currentP1OverlayFadeRatio: Float = MainActivity.DEFAULT_P1_OVERLAY_FADE_RATIO
        private var currentBackgroundBlurRadius: Float = MainActivity.DEFAULT_BACKGROUND_BLUR_RADIUS
        private var currentNormalizedInitialBgScrollOffset: Float = MainActivity.DEFAULT_BACKGROUND_INITIAL_OFFSET // 新变量
        private var currentP2BackgroundFadeInRatio: Float = MainActivity.DEFAULT_P2_BACKGROUND_FADE_IN_RATIO
        private var currentBlurDownscaleFactor: Float = MainActivity.DEFAULT_BLUR_DOWNSCALE_FACTOR_INT / 100.0f // 存储为浮点因子
        private var currentBlurIterations: Int = MainActivity.DEFAULT_BLUR_ITERATIONS
        private var currentP1ShadowRadius: Float = MainActivity.DEFAULT_P1_SHADOW_RADIUS
        private var currentP1ShadowDx: Float = MainActivity.DEFAULT_P1_SHADOW_DX
        private var currentP1ShadowDy: Float = MainActivity.DEFAULT_P1_SHADOW_DY
        private var currentP1ShadowColor: Int = MainActivity.DEFAULT_P1_SHADOW_COLOR
        private var currentP1ImageBottomFadeHeight: Float = MainActivity.DEFAULT_P1_IMAGE_BOTTOM_FADE_HEIGHT
        private var currentImageContentVersion: Long = MainActivity.DEFAULT_IMAGE_CONTENT_VERSION

        // --- End Configuration members ---

        private var numPagesReportedByLauncher = 1
        private var currentPageOffset = 0f

        private val prefs: SharedPreferences by lazy {
            applicationContext.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            this.surfaceHolder = surfaceHolder
            Log.i(TAG, "H2WallpaperEngine Created. IsPreview: ${isPreview}")

            prefs.registerOnSharedPreferenceChangeListener(this)
            loadPreferencesFromStorage() // Initial load

            if (screenWidth > 0 && screenHeight > 0 && imageUriString != null) {
                Log.i(DEBUG_TAG, "onCreate: Triggering initial bitmap load if needed.")
                loadFullBitmapsAsyncIfNeeded()
            } else {
                Log.i(DEBUG_TAG, "onCreate: Conditions for initial load not met (no URI or screen size yet).")
            }
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
            Log.i(DEBUG_TAG, "onSharedPreferenceChanged: key=$key received by service.")
            var needsFullReload = false
            var needsP1TopUpdate = false
            var needsRedrawOnly = false

            // --- 1. 保存关键参数的旧值，以便精确比较 ---
            val oldImageUriString = imageUriString
            val oldImageContentVersion = currentImageContentVersion // 新增：保存旧的图片内容版本

            // 保存其他可能影响位图生成或P1顶部裁剪的旧值
            val oldP1FocusX = currentP1FocusX
            val oldP1FocusY = currentP1FocusY
            val oldPage1ImageHeightRatio = page1ImageHeightRatio
            val oldBackgroundBlurRadius = currentBackgroundBlurRadius
            val oldBlurDownscaleFactor = currentBlurDownscaleFactor
            val oldBlurIterations = currentBlurIterations
            // 你可以根据需要添加更多旧值的保存，例如 P1 特效参数，如果它们的改变不仅仅是重绘

            // --- 2. 加载所有最新的配置值到成员变量 ---
            // 这会更新 this.imageUriString, this.currentImageContentVersion, 等所有成员变量
            loadPreferencesFromStorage()

            // --- 3. 核心判断逻辑：优先检查图片内容版本或URI本身 ---
            if (oldImageContentVersion != currentImageContentVersion) {
                Log.i(DEBUG_TAG, "Image Content Version changed from $oldImageContentVersion to $currentImageContentVersion. Triggering full reload.")
                needsFullReload = true
            } else if (oldImageUriString != imageUriString) { // 图片URI字符串本身发生变化（不太可能，但作为保险）
                Log.i(DEBUG_TAG, "Image URI string changed from '$oldImageUriString' to '$imageUriString'. Triggering full reload.")
                needsFullReload = true
            } else {
                // 如果图片内容版本和URI字符串都没变，再根据具体的 key (如果 key 不是 null) 判断
                key?.let { currentKey ->
                    when (currentKey) {
                        // KEY_IMAGE_URI 和 KEY_IMAGE_CONTENT_VERSION 的主要逻辑已在上面处理
                        // 这里主要处理其他参数的变化

                        // 需要更新P1顶部图片的情况
                        MainActivity.KEY_P1_FOCUS_X,
                        MainActivity.KEY_P1_FOCUS_Y -> {
                            if (oldP1FocusX != currentP1FocusX || oldP1FocusY != currentP1FocusY) {
                                Log.i(DEBUG_TAG, "Preference changed: P1 Focus. Triggering P1 top update.")
                                needsP1TopUpdate = true
                            } else {
                                Log.i(DEBUG_TAG, "Preference changed: P1 Focus. No P1 top update needed.")
                            }
                        }
                        MainActivity.KEY_IMAGE_HEIGHT_RATIO -> {
                            if (oldPage1ImageHeightRatio != page1ImageHeightRatio) {
                                Log.i(DEBUG_TAG, "Preference changed: P1 Height Ratio. Triggering P1 top update.")
                                needsP1TopUpdate = true
                            } else {
                                Log.i(DEBUG_TAG, "Preference changed: P1 Height Ratio. No P1 top update needed.")
                            }
                        }

                        // 需要完整重载背景位图的情况 (模糊相关)
                        MainActivity.KEY_BACKGROUND_BLUR_RADIUS,
                        MainActivity.KEY_BLUR_DOWNSCALE_FACTOR,
                        MainActivity.KEY_BLUR_ITERATIONS -> {
                            if (oldBackgroundBlurRadius != currentBackgroundBlurRadius ||
                                oldBlurDownscaleFactor != currentBlurDownscaleFactor ||
                                oldBlurIterations != currentBlurIterations) {
                                Log.i(DEBUG_TAG, "Preference changed: Blur related params ('$currentKey'). Triggering full reload.")
                                needsFullReload = true
                            } else {
                                Log.i(DEBUG_TAG, "Preference changed: Blur related params ('$currentKey'). No full reload needed.")
                            }
                        }

                        // 其他所有只影响最终绘制表现的参数，只需要重绘
                        MainActivity.KEY_BACKGROUND_COLOR,
                        MainActivity.KEY_SCROLL_SENSITIVITY,
                        MainActivity.KEY_P1_OVERLAY_FADE_RATIO,
                        MainActivity.KEY_P2_BACKGROUND_FADE_IN_RATIO,
                        MainActivity.KEY_BACKGROUND_INITIAL_OFFSET,
                        MainActivity.KEY_P1_SHADOW_RADIUS,
                        MainActivity.KEY_P1_SHADOW_DX,
                        MainActivity.KEY_P1_SHADOW_DY,
                        MainActivity.KEY_P1_SHADOW_COLOR,
                        MainActivity.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT -> {
                            // 这里可以添加对这些参数旧值和新值的比较，如果确实改变了才设置 needsRedrawOnly
                            // 为了确保更新，即使值可能相同（例如用户进入设置但没改动就退出，但SharedPreferences可能被触发），
                            // 简单起见，只要是这些key，就假设需要重绘。
                            // 或者更精确：
                            // if (isValueActuallyChanged(oldValue, newValue)) { needsRedrawOnly = true; }
                            Log.i(DEBUG_TAG, "Preference changed: Visual rendering param ('$currentKey'). Triggering redraw.")
                            needsRedrawOnly = true
                        }
                        else -> {
                            Log.w(DEBUG_TAG, "Unhandled preference key in onSharedPreferenceChanged: $currentKey")
                        }
                    }
                } ?: run {
                    // 如果 key 为 null (例如 editor.clear().commit() 或某些批量更新)，
                    // 我们已经通过比较 imageContentVersion 和 imageUriString 来处理了最重要的情况。
                    // 对于其他参数，如果 key 为 null，我们可能无法知道是哪个具体参数变了。
                    // 一个保守的做法是，如果 key 为 null 且不是因为图片变化，也触发一次重绘。
                    // 但通常，如果图片版本和URI都没变，而 key 是 null，可能表示没有用户可感知的变化。
                    // 或者，如果 key 为 null，并且我们没有因为版本号或URI变化而设置 needsFullReload，
                    // 我们可以假设至少需要一次重绘来确保状态同步。
                    if (!needsFullReload) { // 只有在没有因为图片变化而需要完整重载时
                        Log.i(DEBUG_TAG, "Preference key is null, and image not changed. Triggering redraw as a precaution.")
                        needsRedrawOnly = true
                    }
                }
            }

            // --- 4. 根据标志执行操作 ---
            if (needsFullReload) {
                Log.i(DEBUG_TAG, "Action: Executing full bitmap reload due to changed preferences.")
                loadFullBitmapsAsync() // 这个方法应该负责取消旧任务和回收旧位图
            } else if (needsP1TopUpdate) {
                if (engineWallpaperBitmaps?.sourceSampledBitmap != null && imageUriString != null) {
                    Log.i(DEBUG_TAG, "Action: Executing P1 top cropped bitmap update.")
                    updateTopCroppedBitmapAsync()
                } else if (imageUriString != null) { // 源位图丢失但URI仍在，可能需要完整重载
                    Log.w(DEBUG_TAG, "Action: P1 top update requested, but sourceSampledBitmap is null or image URI invalid. Forcing full reload.")
                    loadFullBitmapsAsync()
                } else {
                    Log.w(DEBUG_TAG, "Action: P1 top update requested, but no image URI available. Doing nothing.")
                }
            } else if (needsRedrawOnly) {
                if (isVisible) {
                    Log.i(DEBUG_TAG, "Action: Executing redraw due to changed preferences.")
                    drawCurrentFrame()
                } else {
                    Log.i(DEBUG_TAG, "Action: Redraw needed, but service not visible. Will redraw on next visibility change.")
                }
            } else {
                Log.i(DEBUG_TAG, "Action: No specific update action triggered by preference change.")
            }
        }


        private fun loadPreferencesFromStorage() {
            imageUriString = prefs.getString(MainActivity.KEY_IMAGE_URI, null)
            page1BackgroundColor = prefs.getInt(MainActivity.KEY_BACKGROUND_COLOR, Color.LTGRAY) // 这个是 Int, 通常没问题

            // 这些参数在 MainActivity 中是以 Float 形式存在，但在 SharedPreferences 中我们统一存为 Int (或 scaled Int)
            // 因此，我们先用 getInt 读取，然后转换为 Float

            page1ImageHeightRatio = prefs.getFloat(MainActivity.KEY_IMAGE_HEIGHT_RATIO, MainActivity.DEFAULT_HEIGHT_RATIO) // 这个之前就是Float，保持
            currentP1FocusX = prefs.getFloat(MainActivity.KEY_P1_FOCUS_X, 0.5f) // 这个之前就是Float，保持
            currentP1FocusY = prefs.getFloat(MainActivity.KEY_P1_FOCUS_Y, 0.5f) // 这个之前就是Float，保持

            currentScrollSensitivity = prefs.getInt(
                MainActivity.KEY_SCROLL_SENSITIVITY,
                (MainActivity.DEFAULT_SCROLL_SENSITIVITY * 10).toInt()
            ) / 10.0f

            currentP1OverlayFadeRatio = prefs.getInt(
                MainActivity.KEY_P1_OVERLAY_FADE_RATIO,
                (MainActivity.DEFAULT_P1_OVERLAY_FADE_RATIO * 100).toInt()
            ) / 100.0f

            currentBackgroundBlurRadius = prefs.getInt( // 读取 Int
                MainActivity.KEY_BACKGROUND_BLUR_RADIUS,
                MainActivity.DEFAULT_BACKGROUND_BLUR_RADIUS.roundToInt() // 默认值也用 Int 形式
            ).toFloat() // 转换为 Float

            currentNormalizedInitialBgScrollOffset = prefs.getInt(
                MainActivity.KEY_BACKGROUND_INITIAL_OFFSET,
                (MainActivity.DEFAULT_BACKGROUND_INITIAL_OFFSET * 10).toInt()
            ) / 10.0f

            currentP2BackgroundFadeInRatio = prefs.getInt(
                MainActivity.KEY_P2_BACKGROUND_FADE_IN_RATIO,
                (MainActivity.DEFAULT_P2_BACKGROUND_FADE_IN_RATIO * 100).toInt()
            ) / 100.0f

            // 模糊相关的额外参数 (假设它们在 SharedPreferences 中也是 Int)
            // 确保 MainActivity.DEFAULT_BLUR_DOWNSCALE_FACTOR_INT 和 DEFAULT_BLUR_ITERATIONS 存在
            // currentBlurDownscaleFactor = prefs.getInt(MainActivity.KEY_BLUR_DOWNSCALE_FACTOR, MainActivity.DEFAULT_BLUR_DOWNSCALE_FACTOR_INT) / 100.0f
            // currentBlurIterations = prefs.getInt(MainActivity.KEY_BLUR_ITERATIONS, MainActivity.DEFAULT_BLUR_ITERATIONS)
            // 你在 H2WallpaperService 中可能没有直接使用这两个模糊参数，它们主要用于 loadAndProcessInitialBitmaps
            // 但如果 H2WallpaperEngine 也需要它们，就需要像上面这样加载。
            // 如果它们只在 MainActivity 中用于决定传递给 loadAndProcessInitialBitmaps 的值，
            // 那么 Service 在调用 loadAndProcessInitialBitmaps 时需要直接从 SharedPreferences 读取或通过某种方式获取这些值。
            // 为了与 MainActivity 的 loadAndApplyPreferencesAndInitState 保持一致，我们也读取它们：
            val blurDownscaleFactorInt = prefs.getInt(MainActivity.KEY_BLUR_DOWNSCALE_FACTOR, MainActivity.DEFAULT_BLUR_DOWNSCALE_FACTOR_INT)
            // this.currentBlurDownscaleFactor = blurDownscaleFactorInt / 100.0f; // 如果 H2WallpaperEngine 有这个成员变量
            val blurIterations = prefs.getInt(MainActivity.KEY_BLUR_ITERATIONS, MainActivity.DEFAULT_BLUR_ITERATIONS)
            // this.currentBlurIterations = blurIterations; // 如果 H2WallpaperEngine 有这个成员变量


            // --- 为新增的 P1 特效参数，也使用 getInt 读取，然后转换为 Float ---
            currentP1ShadowRadius = prefs.getInt(
                MainActivity.KEY_P1_SHADOW_RADIUS,
                MainActivity.DEFAULT_P1_SHADOW_RADIUS.roundToInt()
            ).toFloat()

            currentP1ShadowDx = prefs.getInt(
                MainActivity.KEY_P1_SHADOW_DX,
                MainActivity.DEFAULT_P1_SHADOW_DX.roundToInt()
            ).toFloat()

            currentP1ShadowDy = prefs.getInt(
                MainActivity.KEY_P1_SHADOW_DY,
                MainActivity.DEFAULT_P1_SHADOW_DY.roundToInt()
            ).toFloat()

            currentP1ShadowColor = prefs.getInt(MainActivity.KEY_P1_SHADOW_COLOR, MainActivity.DEFAULT_P1_SHADOW_COLOR) // Color 是 Int

            currentP1ImageBottomFadeHeight = prefs.getInt(
                MainActivity.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT,
                MainActivity.DEFAULT_P1_IMAGE_BOTTOM_FADE_HEIGHT.roundToInt()
            ).toFloat()

            currentImageContentVersion = prefs.getLong(MainActivity.KEY_IMAGE_CONTENT_VERSION, MainActivity.DEFAULT_IMAGE_CONTENT_VERSION)

            // 更新 Logcat 打印以包含新参数
            Log.i(DEBUG_TAG, "Preferences loaded/reloaded (Service): URI=$imageUriString, Color=$page1BackgroundColor, Ratio=$page1ImageHeightRatio, Sensitivity=$currentScrollSensitivity, Focus=($currentP1FocusX, $currentP1FocusY), P1FadeRatio=$currentP1OverlayFadeRatio, BlurRadius=$currentBackgroundBlurRadius, P1ShadowRadius=$currentP1ShadowRadius, P1BottomFadeHeight=$currentP1ImageBottomFadeHeight, BlurDownscaleInt=$blurDownscaleFactorInt, BlurIter=$blurIterations")
            Log.i(DEBUG_TAG, "Service Loaded Prefs: imageUriString='$imageUriString', page1BackgroundColor=${page1BackgroundColor.toUInt().toString(16)}, page1ImageHeightRatio=$page1ImageHeightRatio, ... (打印所有重要参数)")
        }

        private fun loadFullBitmapsAsyncIfNeeded() {
            if (imageUriString != null && screenWidth > 0 && screenHeight > 0) {
                // Always reload if source is null, or job is not active.
                // This handles cases where URI is same, but other params (like blur) that affect full bitmap set might have changed.
                if (currentBitmapLoadJob == null || !currentBitmapLoadJob!!.isActive || engineWallpaperBitmaps?.sourceSampledBitmap == null) {
                    Log.i(DEBUG_TAG, "loadFullBitmapsAsyncIfNeeded: Conditions met, calling loadFullBitmapsAsync.")
                    loadFullBitmapsAsync()
                } else {
                    Log.i(DEBUG_TAG, "loadFullBitmapsAsyncIfNeeded: Bitmaps seem to be loaded or loading. Redrawing if visible.")
                    if (isVisible) drawCurrentFrame()
                }
            } else if (imageUriString == null) {
                Log.i(DEBUG_TAG, "loadFullBitmapsAsyncIfNeeded: No image URI. Clearing bitmaps and drawing placeholder.")
                currentBitmapLoadJob?.cancel(); currentBitmapLoadJob = null
                engineWallpaperBitmaps?.recycleInternals()
                engineWallpaperBitmaps = null
                if (isVisible) drawCurrentFrame()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            val oldScreenWidth = screenWidth
            val oldScreenHeight = screenHeight
            this.screenWidth = width
            this.screenHeight = height
            Log.i(TAG, "Surface changed: New $width x $height. Old: ${oldScreenWidth}x${oldScreenHeight}. IsPreview: ${isPreview}")

            if (screenWidth > 0 && screenHeight > 0) {
                Log.i(DEBUG_TAG, "SurfaceChanged: Dimensions valid. Ensuring bitmaps are loaded for new dimensions.")
                // Parameters like blur radius, focus, height ratio etc. might affect how bitmaps are scaled/cropped for new screen size.
                // So, a full reload is generally appropriate here if an image URI is set.
                if (imageUriString != null) {
                    loadFullBitmapsAsyncIfNeeded()
                } else {
                    if (isVisible) drawCurrentFrame() // Draw placeholder if no image
                }
            } else {
                currentBitmapLoadJob?.cancel(); currentBitmapLoadJob = null
                engineWallpaperBitmaps?.recycleInternals(); engineWallpaperBitmaps = null
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            isVisible = false
            Log.i(TAG, "H2WallpaperEngine Surface destroyed.")
            // Resources are cleaned up in onDestroy
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.isVisible = visible
            Log.i(TAG, "Visibility changed: $visible. IsPreview: ${isPreview}")
            if (visible) {
                // When becoming visible, ensure everything is up-to-date
                // Preferences should be fresh due to listener, but a check won't hurt
                // loadPreferencesFromStorage() // Usually not needed if listener works
                if (engineWallpaperBitmaps?.sourceSampledBitmap == null && imageUriString != null && screenWidth > 0 && screenHeight > 0) {
                    Log.i(DEBUG_TAG, "VisibilityChanged (visible=true): Source bitmap missing. Triggering full load.")
                    loadFullBitmapsAsyncIfNeeded()
                } else {
                    Log.i(DEBUG_TAG, "VisibilityChanged (visible=true): Bitmaps seem okay or no URI. Redrawing.")
                    drawCurrentFrame()
                }
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float, xOffsetStepParam: Float,
            yOffsetStep: Float, xPixelOffset: Int, yPixelOffset: Int
        ) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStepParam, yOffsetStep, xPixelOffset, yPixelOffset)
            val oldFrameworkOffset = this.currentPageOffset
            this.currentPageOffset = xOffset

            // Determine the number of virtual pages based on xOffsetStepParam
            // xOffsetStepParam is the যুদ্ধের step size between pages, e.g., 1/(N-1) for N pages.
            // If xOffsetStepParam is 0 or >= 1, it usually means a single page or fixed wallpaper.
            val newlyCalculatedPages: Int
            if (xOffsetStepParam > 0.00001f && xOffsetStepParam < 1.0f) {
                // Number of intervals = 1.0f / xOffsetStepParam
                // Number of pages = Number of intervals + 1
                newlyCalculatedPages = (1.0f / xOffsetStepParam).roundToInt() + 1
            } else {
                // If step is invalid or indicates a single page scenario
                newlyCalculatedPages = 1
            }

            val oldNumPages = numPagesReportedByLauncher
            // Coerce at least 1, and also consider a reasonable upper limit if necessary (e.g., 20 pages)
            this.numPagesReportedByLauncher = newlyCalculatedPages.coerceIn(1, 20)


            Log.i(DEBUG_TAG, "onOffsetsChanged: xOffsetStepParam=$xOffsetStepParam, Calculated total pages=${this.numPagesReportedByLauncher}, oldNumPages=$oldNumPages, currentXOffset=$xOffset")

            // If the number of pages has changed, we might need to regenerate the scrolling background
            if (!isPreview && oldNumPages != this.numPagesReportedByLauncher && this.numPagesReportedByLauncher > 0 && imageUriString != null) {
                if (engineWallpaperBitmaps?.sourceSampledBitmap != null) {
                    Log.i(DEBUG_TAG, "Number of reported pages changed from $oldNumPages to ${this.numPagesReportedByLauncher}. Updating scrolling background.")
                    updateScrollingBackgroundAsync() // This will use the new numPagesReportedByLauncher
                } else {
                    Log.i(DEBUG_TAG, "Number of reported pages changed, but no source bitmap. Triggering full reload.")
                    loadFullBitmapsAsyncIfNeeded() // This will also eventually use the new page count
                }
            } else if (oldFrameworkOffset != xOffset) {
                if (isVisible && surfaceHolder?.surface?.isValid == true) {
                    drawCurrentFrame()
                }
            }
        }

        private fun loadFullBitmapsAsync() {
            currentBitmapLoadJob?.cancel()
            engineWallpaperBitmaps?.recycleInternals() // Always recycle before new load attempt
            engineWallpaperBitmaps = null

            val uriStringToLoad = imageUriString
            if (uriStringToLoad == null || screenWidth <= 0 || screenHeight <= 0) {
                Log.w(DEBUG_TAG, "loadFullBitmapsAsync: Preconditions not met. URI: $uriStringToLoad, Screen: ${screenWidth}x$screenHeight")
                if (isVisible) drawCurrentFrame() // Draw placeholder
                return
            }
            val uri = Uri.parse(uriStringToLoad)
            Log.i(DEBUG_TAG, "loadFullBitmapsAsync: Starting for URI: $uri. Focus:($currentP1FocusX, $currentP1FocusY), Blur:$currentBackgroundBlurRadius")

            if (isVisible) {
                drawCurrentFrame() // Draw placeholder immediately
            }

            currentBitmapLoadJob = engineScope.launch {
                var newBitmapsHolder: SharedWallpaperRenderer.WallpaperBitmaps? = null
                try {
                    ensureActive()
                    val pagesForBackground = if (!isPreview && numPagesReportedByLauncher <= 1) {
                        DEFAULT_VIRTUAL_PAGES_FOR_SCROLLING
                    } else {
                        numPagesReportedByLauncher.coerceAtLeast(1)
                    }
                    Log.i(DEBUG_TAG, "loadFullBitmapsAsync (coroutine): Using $pagesForBackground pages for background. Sensitivity: $currentScrollSensitivity, Blur: $currentBackgroundBlurRadius")

                    newBitmapsHolder = withContext(Dispatchers.IO) {
                        ensureActive()
                        SharedWallpaperRenderer.loadAndProcessInitialBitmaps(
                            context = applicationContext,
                            imageUri = uri,
                            targetScreenWidth = screenWidth,
                            targetScreenHeight = screenHeight,
                            page1ImageHeightRatio = page1ImageHeightRatio,
                            normalizedFocusX = currentP1FocusX,
                            normalizedFocusY = currentP1FocusY,
                            //numVirtualPagesForScrolling = pagesForBackground,
                            blurRadiusForBackground = currentBackgroundBlurRadius, // Pass current blur radius
                            blurDownscaleFactor = currentBlurDownscaleFactor, // 新增
                            blurIterations = currentBlurIterations
                        )
                    }
                    ensureActive()

                    if (this@H2WallpaperEngine.imageUriString == uriStringToLoad) {
                        engineWallpaperBitmaps = newBitmapsHolder
                        Log.i(DEBUG_TAG, "Full bitmaps loaded successfully for $uri.")
                    } else {
                        Log.w(DEBUG_TAG, "URI changed during full load for $uri. Current engine URI: ${this@H2WallpaperEngine.imageUriString}. Discarding.")
                        newBitmapsHolder?.recycleInternals()
                        if (this@H2WallpaperEngine.imageUriString == null) engineWallpaperBitmaps = null
                    }
                } catch (e: CancellationException) {
                    Log.d(DEBUG_TAG, "Full bitmap loading CANCELLED for $uri.")
                    newBitmapsHolder?.recycleInternals()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in loadFullBitmapsAsync for $uri", e)
                    newBitmapsHolder?.recycleInternals()
                    engineWallpaperBitmaps = null
                } finally {
                    if (coroutineContext[Job] == currentBitmapLoadJob) currentBitmapLoadJob = null
                    if (isVisible && (this@H2WallpaperEngine.imageUriString == uriStringToLoad || this@H2WallpaperEngine.imageUriString == null)) {
                        drawCurrentFrame()
                    }
                }
            }
        }

        private fun updateTopCroppedBitmapAsync() {
            val currentSourceBitmap = engineWallpaperBitmaps?.sourceSampledBitmap
            if (currentSourceBitmap == null || screenWidth <= 0 || screenHeight <= 0 || imageUriString == null) {
                Log.w(DEBUG_TAG, "updateTopCroppedBitmapAsync: Preconditions not met. Triggering full reload if URI exists.")
                if (imageUriString != null) loadFullBitmapsAsyncIfNeeded()
                else if(isVisible) drawCurrentFrame()
                return
            }
            Log.i(DEBUG_TAG, "updateTopCroppedBitmapAsync: Starting for ratio $page1ImageHeightRatio, Focus($currentP1FocusX, $currentP1FocusY)")

            // Use a separate job for this to avoid interfering with fullBitmapLoadJob if it's for source
            val p1TopUpdateJob = engineScope.launch {
                var newTopCropped: Bitmap? = null
                val oldTopCropped = engineWallpaperBitmaps?.page1TopCroppedBitmap // Cache to recycle if different
                try {
                    ensureActive()
                    newTopCropped = withContext(Dispatchers.Default) {
                        ensureActive()
                        SharedWallpaperRenderer.preparePage1TopCroppedBitmap(
                            currentSourceBitmap,
                            screenWidth,
                            screenHeight,
                            page1ImageHeightRatio,
                            currentP1FocusX,
                            currentP1FocusY
                        )
                    }
                    ensureActive()
                    if (this@H2WallpaperEngine.imageUriString != null && engineWallpaperBitmaps?.sourceSampledBitmap == currentSourceBitmap) {
                        if (oldTopCropped != newTopCropped) oldTopCropped?.recycle() // Recycle old only if new one is different
                        engineWallpaperBitmaps?.page1TopCroppedBitmap = newTopCropped
                        Log.i(DEBUG_TAG, "Top cropped bitmap updated successfully.")
                    } else {
                        Log.w(DEBUG_TAG, "State changed during top bitmap update. Discarding new top bitmap.")
                        newTopCropped?.recycle()
                    }
                } catch (e: CancellationException) {
                    Log.d(DEBUG_TAG, "Top bitmap update CANCELLED.")
                    newTopCropped?.recycle()
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating top cropped bitmap", e)
                    newTopCropped?.recycle()
                    if (this@H2WallpaperEngine.imageUriString != null && engineWallpaperBitmaps?.sourceSampledBitmap == currentSourceBitmap) {
                        engineWallpaperBitmaps?.page1TopCroppedBitmap = null
                    }
                } finally {
                    if (isVisible && this@H2WallpaperEngine.imageUriString != null) drawCurrentFrame()
                }
            }
        }

        private fun updateScrollingBackgroundAsync() {
            val currentSource = engineWallpaperBitmaps?.sourceSampledBitmap
            if (currentSource == null || imageUriString == null || screenWidth <= 0 || screenHeight <= 0) {
                Log.w(DEBUG_TAG, "updateScrollingBackgroundAsync: Preconditions not met. Triggering full reload if URI exists.")
                if (imageUriString != null) loadFullBitmapsAsyncIfNeeded()
                else if(isVisible) drawCurrentFrame()
                return
            }
            // val pagesForBg = ... (这行你之前已经注释掉了，保持即可)

            // 从成员变量中获取当前的模糊参数值
            // 这些成员变量应该已经在 loadPreferencesFromStorage() 中被正确加载了
            val downscaleFactorToUse = currentBlurDownscaleFactor
            val iterationsToUse = currentBlurIterations

            Log.i(DEBUG_TAG, "updateScrollingBackgroundAsync: Starting for BlurRadius: $currentBackgroundBlurRadius, DownscaleFactor: $downscaleFactorToUse, Iterations: $iterationsToUse")

            val scrollingUpdateJob = engineScope.launch {
                var newScrollingPair: Pair<Bitmap?, Bitmap?>? = null
                val oldScrollingBitmap = engineWallpaperBitmaps?.scrollingBackgroundBitmap
                val oldBlurredBitmap = engineWallpaperBitmaps?.blurredScrollingBackgroundBitmap
                try {
                    ensureActive()
                    newScrollingPair = withContext(Dispatchers.IO) {
                        ensureActive()
                        SharedWallpaperRenderer.prepareScrollingAndBlurredBitmaps(
                            applicationContext,
                            currentSource,
                            screenWidth,
                            screenHeight,
                            currentBackgroundBlurRadius, // 使用当前的模糊半径
                            // --- 传递缺失的参数 ---
                            downscaleFactorToUse,     // 使用从成员变量获取的降采样因子
                            iterationsToUse           // 使用从成员变量获取的迭代次数
                        )
                    }
                    ensureActive()
                    // ... (后续逻辑不变)
                    if (this@H2WallpaperEngine.imageUriString != null && engineWallpaperBitmaps?.sourceSampledBitmap == currentSource) {
                        if (oldScrollingBitmap != newScrollingPair?.first) oldScrollingBitmap?.recycle()
                        if (oldBlurredBitmap != newScrollingPair?.second) oldBlurredBitmap?.recycle()
                        engineWallpaperBitmaps?.scrollingBackgroundBitmap = newScrollingPair?.first
                        engineWallpaperBitmaps?.blurredScrollingBackgroundBitmap = newScrollingPair?.second
                        Log.i(DEBUG_TAG, "Scrolling background updated. New width: ${newScrollingPair?.first?.width}")
                    } else {
                        Log.w(DEBUG_TAG, "State changed during scrolling background update. Discarding.")
                        newScrollingPair?.first?.recycle(); newScrollingPair?.second?.recycle()
                    }
                } catch (e: CancellationException) {
                    Log.d(DEBUG_TAG, "Scrolling background update CANCELLED.")
                    newScrollingPair?.first?.recycle(); newScrollingPair?.second?.recycle()
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating scrolling background", e)
                    newScrollingPair?.first?.recycle(); newScrollingPair?.second?.recycle()
                } finally {
                    if (isVisible && this@H2WallpaperEngine.imageUriString != null) drawCurrentFrame()
                }
            }
        }

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
                    if (currentWpBitmaps?.sourceSampledBitmap != null && currentWpBitmaps.page1TopCroppedBitmap != null) {
                        val pagesForConfig = if (!isPreview && numPagesReportedByLauncher <= 1) {
                            DEFAULT_VIRTUAL_PAGES_FOR_SCROLLING
                        } else {
                            numPagesReportedByLauncher.coerceAtLeast(1)
                        }
                        val config = SharedWallpaperRenderer.WallpaperConfig(
                            screenWidth, screenHeight, page1BackgroundColor, page1ImageHeightRatio,
                            currentPageOffset, pagesForConfig,
                            p1OverlayFadeTransitionRatio = currentP1OverlayFadeRatio, // Use member variable
                            scrollSensitivityFactor = this.currentScrollSensitivity,    // Use member variable
                            normalizedInitialBgScrollOffset = this.currentNormalizedInitialBgScrollOffset, // Use member variable
                            p2BackgroundFadeInRatio = this.currentP2BackgroundFadeInRatio, //传递 P2 淡入比例
                            p1ShadowRadius = this.currentP1ShadowRadius,
                            p1ShadowDx = this.currentP1ShadowDx,
                            p1ShadowDy = this.currentP1ShadowDy,
                            p1ShadowColor = this.currentP1ShadowColor,
                            p1ImageBottomFadeHeight = this.currentP1ImageBottomFadeHeight
                        )
                        SharedWallpaperRenderer.drawFrame(canvas, config, currentWpBitmaps)
                    } else {
                        SharedWallpaperRenderer.drawPlaceholder(canvas, screenWidth, screenHeight,
                            if (imageUriString != null && (currentBitmapLoadJob?.isActive == true)) "壁纸加载中..."
                            else if (imageUriString == null) "请选择图片"
                            else "图片资源准备中..."
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during drawFrame", e)
            } finally {
                if (canvas != null) {
                    try { surfaceHolder!!.unlockCanvasAndPost(canvas) }
                    catch (e: Exception) { Log.e(TAG, "Error unlocking canvas", e) }
                }
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            engineScope.cancel() // Cancel all coroutines
            engineWallpaperBitmaps?.recycleInternals()
            engineWallpaperBitmaps = null
            Log.i(TAG, "H2WallpaperEngine destroyed.")
        }
    }
}