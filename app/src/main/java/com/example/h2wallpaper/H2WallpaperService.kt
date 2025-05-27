package com.example.h2wallpaper

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
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
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

// 导入 WallpaperConfigConstants 对象
import com.example.h2wallpaper.WallpaperConfigConstants
import kotlinx.coroutines.cancel

class H2WallpaperService : WallpaperService() {

    companion object {
        private const val TAG = "H2WallpaperSvc"
        private const val DEBUG_TAG = "H2WallpaperSvc_Debug"
        private const val DEFAULT_VIRTUAL_PAGES_FOR_SCROLLING = 3
    }

    override fun onCreateEngine(): Engine {
        return H2WallpaperEngine()
    }

    private inner class H2WallpaperEngine : Engine(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        private var surfaceHolder: SurfaceHolder? = null
        private var isVisible: Boolean = false
        private var screenWidth: Int = 0
        private var screenHeight: Int = 0

        private var engineWallpaperBitmaps: SharedWallpaperRenderer.WallpaperBitmaps? = null
        private val engineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        private var currentBitmapLoadJob: Job? = null

        private lateinit var preferencesRepository: WallpaperPreferencesRepository
        private lateinit var prefs: SharedPreferences

        // --- Configuration members ---
        private var imageUriString: String? = null
        private var page1BackgroundColor: Int = WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR
        private var page1ImageHeightRatio: Float = WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO
        private var currentP1FocusX: Float = WallpaperConfigConstants.DEFAULT_P1_FOCUS_X
        private var currentP1FocusY: Float = WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y
        private var currentP1ContentScaleFactor: Float = WallpaperConfigConstants.DEFAULT_P1_CONTENT_SCALE_FACTOR // 新增

        private var currentScrollSensitivity: Float = WallpaperConfigConstants.DEFAULT_SCROLL_SENSITIVITY
        private var currentP1OverlayFadeRatio: Float = WallpaperConfigConstants.DEFAULT_P1_OVERLAY_FADE_RATIO
        private var currentBackgroundBlurRadius: Float = WallpaperConfigConstants.DEFAULT_BACKGROUND_BLUR_RADIUS
        private var currentNormalizedInitialBgScrollOffset: Float = WallpaperConfigConstants.DEFAULT_BACKGROUND_INITIAL_OFFSET
        private var currentP2BackgroundFadeInRatio: Float = WallpaperConfigConstants.DEFAULT_P2_BACKGROUND_FADE_IN_RATIO
        private var currentBlurDownscaleFactor: Float = WallpaperConfigConstants.DEFAULT_BLUR_DOWNSCALE_FACTOR_INT / 100.0f
        private var currentBlurIterations: Int = WallpaperConfigConstants.DEFAULT_BLUR_ITERATIONS
        private var currentP1ShadowRadius: Float = WallpaperConfigConstants.DEFAULT_P1_SHADOW_RADIUS
        private var currentP1ShadowDx: Float = WallpaperConfigConstants.DEFAULT_P1_SHADOW_DX
        private var currentP1ShadowDy: Float = WallpaperConfigConstants.DEFAULT_P1_SHADOW_DY
        private var currentP1ShadowColor: Int = WallpaperConfigConstants.DEFAULT_P1_SHADOW_COLOR
        private var currentP1ImageBottomFadeHeight: Float = WallpaperConfigConstants.DEFAULT_P1_IMAGE_BOTTOM_FADE_HEIGHT
        private var currentImageContentVersion: Long = WallpaperConfigConstants.DEFAULT_IMAGE_CONTENT_VERSION
        // ---

        private var numPagesReportedByLauncher = 1
        private var currentBlurUpdateJob: Job? = null // 新增，用于单独更新模糊
        private var currentPageOffset = 0f


        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            this.surfaceHolder = surfaceHolder
            Log.i(TAG, "H2WallpaperEngine Created. IsPreview: ${isPreview}")

            preferencesRepository = WallpaperPreferencesRepository(applicationContext)
            prefs = applicationContext.getSharedPreferences(
                WallpaperConfigConstants.PREFS_NAME,
                Context.MODE_PRIVATE
            )
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
            var needsOnlyBlurUpdate = false // 新增标志
            var needsRedrawOnly = false

            val oldImageUriString = imageUriString
            val oldImageContentVersion = currentImageContentVersion
            val oldP1FocusX = currentP1FocusX
            val oldP1FocusY = currentP1FocusY
            val oldPage1ImageHeightRatio = page1ImageHeightRatio
            val oldP1ContentScaleFactor = currentP1ContentScaleFactor
            val oldBackgroundBlurRadius = currentBackgroundBlurRadius
            val oldBlurDownscaleFactor = currentBlurDownscaleFactor
            val oldBlurIterations = currentBlurIterations

            loadPreferencesFromStorage() // 重新加载所有配置

            if (oldImageContentVersion != currentImageContentVersion) {
                Log.i(DEBUG_TAG, "Image Content Version changed ($oldImageContentVersion -> $currentImageContentVersion).")
                // 版本变化通常意味着可能任何事情都变了，或者至少 P1 的视觉效果变了。
                // 如果URI也变了，肯定是 full reload。
                // 如果URI没变，但其他例如P1焦点、高度、或任何通过版本号管理的参数变了，
                // 我们需要判断这个版本变化是否仅仅因为模糊参数。
                // 为了简化，如果版本号变了，我们先假设可能需要 full reload，
                // 但如果能确定只有模糊参数导致版本更新，则可以优化。
                // 目前 MainViewModel 中的 updateAdvancedSettingRealtime 对所有滑块都更新版本号。

                // 更精细的判断：
                if (oldImageUriString != imageUriString) {
                    Log.i(DEBUG_TAG, "Image URI changed with version. Triggering full reload.")
                    needsFullReload = true
                } else if (oldP1FocusX != currentP1FocusX || oldP1FocusY != currentP1FocusY ||
                    oldPage1ImageHeightRatio != page1ImageHeightRatio ||
                    oldP1ContentScaleFactor != currentP1ContentScaleFactor) {
                    Log.i(DEBUG_TAG, "P1 visual params (Focus/Height/Scale) changed with version. Triggering P1 top update (and potentially full if no source).")
                    needsP1TopUpdate = true // 如果源图也需要重新加载（比如没有了），这个会升级为full reload
                } else if (oldBackgroundBlurRadius != currentBackgroundBlurRadius ||
                    oldBlurDownscaleFactor != currentBlurDownscaleFactor ||
                    oldBlurIterations != currentBlurIterations) {
                    Log.i(DEBUG_TAG, "Blur params changed with version. Triggering blur-only update.")
                    needsOnlyBlurUpdate = true
                } else {
                    // 版本号变了，但上面检查的主要参数都没变，可能是其他参数（如阴影、颜色等）
                    Log.i(DEBUG_TAG, "Other rendering params changed with version. Triggering redraw.")
                    needsRedrawOnly = true
                }
            }
            // 如果版本号没变，再检查具体的 key (这种情况理论上不应该发生，因为 ViewModel 更新参数后会更新版本号)
            // 但为了健壮性，可以保留对特定 key 的检查，以防万一版本号逻辑有疏漏
            else if (key != null) {
                when (key) {
                    WallpaperConfigConstants.KEY_IMAGE_URI -> { // URI直接变化，强制full reload
                        if (oldImageUriString != imageUriString) needsFullReload = true
                    }
                    WallpaperConfigConstants.KEY_P1_FOCUS_X,
                    WallpaperConfigConstants.KEY_P1_FOCUS_Y,
                    WallpaperConfigConstants.KEY_IMAGE_HEIGHT_RATIO,
                    WallpaperConfigConstants.KEY_P1_CONTENT_SCALE_FACTOR -> {
                        if (oldP1FocusX != currentP1FocusX || oldP1FocusY != currentP1FocusY ||
                            oldPage1ImageHeightRatio != page1ImageHeightRatio ||
                            oldP1ContentScaleFactor != currentP1ContentScaleFactor) {
                            needsP1TopUpdate = true
                        }
                    }
                    WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS,
                    WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR,
                    WallpaperConfigConstants.KEY_BLUR_ITERATIONS -> {
                        if (oldBackgroundBlurRadius != currentBackgroundBlurRadius ||
                            oldBlurDownscaleFactor != currentBlurDownscaleFactor ||
                            oldBlurIterations != currentBlurIterations) {
                            needsOnlyBlurUpdate = true // 优先尝试仅更新模糊
                        }
                    }
                    // ... 其他参数触发 needsRedrawOnly ...
                    else -> needsRedrawOnly = true
                }
            } else { // key is null
                if (imageUriString != null) needsRedrawOnly = true
            }


            // --- 根据标志执行操作 (优先级：Full Reload > P1 Update / Blur Update > Redraw) ---
            if (needsFullReload) {
                Log.i(DEBUG_TAG, "Action: Executing full bitmap reload.")
                currentBlurUpdateJob?.cancel() // 取消进行中的模糊更新
                loadFullBitmapsAsync()
            } else if (needsOnlyBlurUpdate) {
                // 仅当有未模糊的背景图时才尝试此优化
                if (engineWallpaperBitmaps?.scrollingBackgroundBitmap != null && imageUriString != null) {
                    Log.i(DEBUG_TAG, "Action: Executing blur-only update for background.")
                    currentBitmapLoadJob?.cancel() // 取消完整加载（如果正在进行）
                    updateOnlyBlurredBackgroundAsync()
                } else if (imageUriString != null) {
                    Log.w(DEBUG_TAG, "Action: Blur-only update requested, but scrollingBackgroundBitmap is null. Forcing full reload.")
                    loadFullBitmapsAsync() // 回退到完整加载
                }
            } else if (needsP1TopUpdate) {
                if (engineWallpaperBitmaps?.sourceSampledBitmap != null && imageUriString != null) {
                    Log.i(DEBUG_TAG, "Action: Executing P1 top cropped bitmap update.")
                    // 如果P1更新也可能影响模糊（例如，P1高度变化导致背景可见区域变化，模糊可能需要重新评估），则需要更复杂的逻辑
                    // 当前假设P1更新不直接要求背景模糊重做，除非模糊参数本身也变了
                    updateTopCroppedBitmapAsync()
                } else if (imageUriString != null) {
                    Log.w(DEBUG_TAG, "Action: P1 top update requested, but sourceSampledBitmap is null. Forcing full reload.")
                    loadFullBitmapsAsync()
                }
            } else if (needsRedrawOnly) {
                if (isVisible) {
                    Log.i(DEBUG_TAG, "Action: Executing redraw only.")
                    drawCurrentFrame()
                }
            }
        }
        private fun updateOnlyBlurredBackgroundAsync() {
            currentBlurUpdateJob?.cancel() // 取消上一个模糊更新任务
            currentBitmapLoadJob?.cancel() // 也取消完整加载任务，因为我们只更新模糊

            val baseForBlur = engineWallpaperBitmaps?.scrollingBackgroundBitmap
            if (baseForBlur == null || screenWidth <= 0 || screenHeight <= 0 || imageUriString == null) {
                if (imageUriString != null) { // 有URI但没有基础滚动图，说明有问题
                    Log.w(DEBUG_TAG, "updateOnlyBlurredBackgroundAsync: Base scrolling bitmap is null. Attempting full reload.")
                    loadFullBitmapsAsyncIfNeeded() // 尝试完整重载作为后备
                } else if (isVisible) {
                    drawCurrentFrame() // 没有URI，绘制占位符
                }
                return
            }

            Log.i(DEBUG_TAG, "updateOnlyBlurredBackgroundAsync: Starting for current scrolling background. BlurR=$currentBackgroundBlurRadius, DF=$currentBlurDownscaleFactor, It=$currentBlurIterations")

            currentBlurUpdateJob = engineScope.launch {
                var newBlurredBitmap: Bitmap? = null
                try {
                    ensureActive()
                    newBlurredBitmap = withContext(Dispatchers.IO) {
                        ensureActive()
                        SharedWallpaperRenderer.regenerateBlurredBitmap(
                            context = applicationContext,
                            baseBitmap = baseForBlur,
                            targetWidth = baseForBlur.width, // 模糊图的目标尺寸应与原滚动图一致
                            targetHeight = baseForBlur.height,
                            blurRadius = currentBackgroundBlurRadius,
                            blurDownscaleFactor = currentBlurDownscaleFactor,
                            blurIterations = currentBlurIterations
                        )
                    }
                    ensureActive()

                    val oldBlurred = engineWallpaperBitmaps?.blurredScrollingBackgroundBitmap
                    if (this@H2WallpaperEngine.imageUriString != null && engineWallpaperBitmaps?.scrollingBackgroundBitmap == baseForBlur) {
                        engineWallpaperBitmaps?.blurredScrollingBackgroundBitmap = newBlurredBitmap
                        if (oldBlurred != newBlurredBitmap) oldBlurred?.recycle()
                        Log.i(DEBUG_TAG, "updateOnlyBlurredBackgroundAsync: Successfully updated blurred background.")
                    } else {
                        Log.w(DEBUG_TAG, "updateOnlyBlurredBackgroundAsync: Conditions changed during async operation. Discarding result.")
                        newBlurredBitmap?.recycle()
                    }
                } catch (e: CancellationException) {
                    Log.d(TAG, "updateOnlyBlurredBackgroundAsync cancelled.")
                    newBlurredBitmap?.recycle()
                } catch (e: Exception) {
                    Log.e(TAG, "updateOnlyBlurredBackgroundAsync failed", e)
                    newBlurredBitmap?.recycle()
                    // 发生错误，考虑是否回退到完整加载或清除模糊图
                    if (this@H2WallpaperEngine.imageUriString != null && engineWallpaperBitmaps?.scrollingBackgroundBitmap == baseForBlur) {
                        engineWallpaperBitmaps?.blurredScrollingBackgroundBitmap = null // 清除损坏的模糊图
                    }
                } finally {
                    if (isActive && coroutineContext[Job] == currentBlurUpdateJob) currentBlurUpdateJob = null
                    if (isVisible && this@H2WallpaperEngine.imageUriString != null) {
                        drawCurrentFrame()
                    }
                }
            }
        }


        private fun loadPreferencesFromStorage() {
            imageUriString = preferencesRepository.getSelectedImageUri()?.toString()
            page1BackgroundColor = preferencesRepository.getSelectedBackgroundColor()
            page1ImageHeightRatio = preferencesRepository.getPage1ImageHeightRatio()
            currentP1FocusX = preferencesRepository.getP1FocusX()
            currentP1FocusY = preferencesRepository.getP1FocusY()
            currentP1ContentScaleFactor = preferencesRepository.getP1ContentScaleFactor() // 新增

            currentScrollSensitivity = preferencesRepository.getScrollSensitivity()
            currentP1OverlayFadeRatio = preferencesRepository.getP1OverlayFadeRatio()
            currentBackgroundBlurRadius = preferencesRepository.getBackgroundBlurRadius()
            currentNormalizedInitialBgScrollOffset = preferencesRepository.getBackgroundInitialOffset()
            currentP2BackgroundFadeInRatio = preferencesRepository.getP2BackgroundFadeInRatio()
            currentBlurDownscaleFactor = preferencesRepository.getBlurDownscaleFactor()
            currentBlurIterations = preferencesRepository.getBlurIterations()
            currentP1ShadowRadius = preferencesRepository.getP1ShadowRadius()
            currentP1ShadowDx = preferencesRepository.getP1ShadowDx()
            currentP1ShadowDy = preferencesRepository.getP1ShadowDy()
            currentP1ShadowColor = preferencesRepository.getP1ShadowColor()
            currentP1ImageBottomFadeHeight = preferencesRepository.getP1ImageBottomFadeHeight()
            currentImageContentVersion = preferencesRepository.getImageContentVersion()

            Log.i(DEBUG_TAG, "Prefs loaded (Service): URI=$imageUriString, P1H=$page1ImageHeightRatio, P1F=(${currentP1FocusX},${currentP1FocusY}), P1S=$currentP1ContentScaleFactor, Version=$currentImageContentVersion")
        }

        private fun loadFullBitmapsAsync() {
            currentBitmapLoadJob?.cancel()
            // 不立即回收 engineWallpaperBitmaps，让旧的显示直到新的加载完成
            // engineWallpaperBitmaps?.recycleInternals()
            // engineWallpaperBitmaps = null // 会导致闪烁占位符

            val uriStringToLoad = imageUriString
            if (uriStringToLoad == null || screenWidth <= 0 || screenHeight <= 0) {
                if (engineWallpaperBitmaps != null) { // 如果之前有图，现在没了URI，则清空
                    engineWallpaperBitmaps?.recycleInternals()
                    engineWallpaperBitmaps = null
                }
                if (isVisible) drawCurrentFrame() // 可能会画占位符
                return
            }
            val uri = Uri.parse(uriStringToLoad)
            Log.i(DEBUG_TAG, "loadFullBitmapsAsync: Starting for URI: $uri. Focus:($currentP1FocusX, $currentP1FocusY), Scale:$currentP1ContentScaleFactor, Blur:$currentBackgroundBlurRadius")

            if (isVisible && engineWallpaperBitmaps == null) { // 如果可见但还没图，先画个占位符
                drawCurrentFrame()
            }

            currentBitmapLoadJob = engineScope.launch {
                var newBitmapsHolder: SharedWallpaperRenderer.WallpaperBitmaps? = null
                try {
                    ensureActive()
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
                            contentScaleFactorForP1 = currentP1ContentScaleFactor, // 传递缩放因子
                            blurRadiusForBackground = currentBackgroundBlurRadius,
                            blurDownscaleFactor = currentBlurDownscaleFactor,
                            blurIterations = currentBlurIterations
                        )
                    }
                    ensureActive()

                    // 在UI线程更新位图引用
                    val oldBitmaps = engineWallpaperBitmaps // 保存旧的以便稍后回收
                    if (this@H2WallpaperEngine.imageUriString == uriStringToLoad) { // 再次检查URI是否匹配
                        engineWallpaperBitmaps = newBitmapsHolder
                        oldBitmaps?.recycleInternals() // 回收旧的（如果与新加载的不同）
                    } else { // URI在这期间改变了
                        newBitmapsHolder?.recycleInternals() // 新加载的作废
                        if (this@H2WallpaperEngine.imageUriString == null && oldBitmaps != null) { // 如果当前URI已为空
                            oldBitmaps.recycleInternals()
                            engineWallpaperBitmaps = null
                        }
                        // 如果URI变成了另一个值，则当前loadFullBitmapsAsync的结果作废，等待新的加载
                    }
                } catch (e: CancellationException) {
                    Log.d(TAG, "loadFullBitmapsAsync cancelled for $uriStringToLoad")
                    newBitmapsHolder?.recycleInternals() // 确保回收，即使被取消
                } catch (e: Exception) {
                    Log.e(TAG, "loadFullBitmapsAsync failed for $uriStringToLoad", e)
                    newBitmapsHolder?.recycleInternals()
                    if (this@H2WallpaperEngine.imageUriString == uriStringToLoad) { // 如果加载失败的是当前URI
                        engineWallpaperBitmaps?.recycleInternals()
                        engineWallpaperBitmaps = null
                    }
                } finally {
                    if (isActive && coroutineContext[Job] == currentBitmapLoadJob) currentBitmapLoadJob = null
                    if (isVisible && (this@H2WallpaperEngine.imageUriString == uriStringToLoad || this@H2WallpaperEngine.imageUriString == null)) {
                        drawCurrentFrame() // 无论成功失败，都尝试重绘
                    }
                }
            }
        }

        private fun loadFullBitmapsAsyncIfNeeded() {
            if (imageUriString != null && screenWidth > 0 && screenHeight > 0) {
                if (currentBitmapLoadJob?.isActive != true && engineWallpaperBitmaps?.sourceSampledBitmap == null) {
                    loadFullBitmapsAsync()
                } else if (isVisible) {
                    drawCurrentFrame()
                }
            } else if (imageUriString == null) { // 没有图片URI
                currentBitmapLoadJob?.cancel(); currentBitmapLoadJob = null
                engineWallpaperBitmaps?.recycleInternals(); engineWallpaperBitmaps = null
                if (isVisible) drawCurrentFrame() // 绘制占位符
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            val oldScreenWidth = screenWidth; val oldScreenHeight = screenHeight
            this.screenWidth = width; this.screenHeight = height
            Log.i(TAG, "Surface changed: New $width x $height. Old: ${oldScreenWidth}x${oldScreenHeight}. IsPreview: $isPreview")
            if (width > 0 && height > 0) loadFullBitmapsAsyncIfNeeded()
            else { currentBitmapLoadJob?.cancel(); engineWallpaperBitmaps?.recycleInternals(); engineWallpaperBitmaps = null }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            isVisible = false // 标记为不可见，避免不必要的绘制
            Log.i(TAG, "H2WallpaperEngine Surface destroyed.")
            // 位图资源在 engine onDestroy 中统一清理
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.isVisible = visible
            Log.i(TAG, "Visibility changed: $visible. IsPreview: $isPreview")
            if (visible) {
                // 可见时，如果需要，确保加载位图并绘制第一帧
                loadFullBitmapsAsyncIfNeeded() // 会判断是否需要加载，如果已加载则直接绘制
            }
        }

        override fun onOffsetsChanged(xOffset: Float, yOffset: Float, xOffsetStepParam: Float, yOffsetStep: Float, xPixelOffset: Int, yPixelOffset: Int) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStepParam, yOffsetStep, xPixelOffset, yPixelOffset)
            val oldFrameworkOffset = this.currentPageOffset
            this.currentPageOffset = xOffset

            val newlyCalculatedPages = if (xOffsetStepParam > 0.00001f && xOffsetStepParam < 1.0f) {
                (1.0f / xOffsetStepParam).roundToInt() + 1 // 有些启动器xOffsetStep是1/(N-1)
            } else {
                1 // 如果xOffsetStep无效或为0，则认为是单页
            }
            val oldNumPages = numPagesReportedByLauncher
            this.numPagesReportedByLauncher = newlyCalculatedPages.coerceIn(1, 20) // 限制页数范围

            // Log.i(DEBUG_TAG, "onOffsetsChanged: xOffset=$xOffset, xOffsetStepParam=$xOffsetStepParam, Calculated total pages=${this.numPagesReportedByLauncher}")

            if (oldFrameworkOffset != xOffset) { // 只有在偏移量实际改变时才重绘
                if (isVisible && surfaceHolder?.surface?.isValid == true) {
                    drawCurrentFrame()
                }
            }
            // 页数变化(numPagesReportedByLauncher)不会直接触发位图重载，
            // 因为背景图的宽度是基于屏幕宽度和固定虚拟页数或一个较大值来生成的，
            // 或者是基于源图按屏幕高度缩放后的宽度。
            // SharedWallpaperRenderer中的 numVirtualPages 参数是用于计算过渡效果的，
            // 实际背景图滚动范围由背景图自身宽度和屏幕宽度决定。
        }

        private fun updateTopCroppedBitmapAsync() {
            val currentSourceBitmap = engineWallpaperBitmaps?.sourceSampledBitmap
            if (currentSourceBitmap == null || screenWidth <= 0 || screenHeight <= 0 || imageUriString == null) {
                if (imageUriString != null) loadFullBitmapsAsyncIfNeeded() // 如果有URI但没源图，尝试重载
                else if (isVisible) drawCurrentFrame() // 没URI，画占位符
                return
            }
            Log.i(DEBUG_TAG, "updateTopCroppedBitmapAsync: R=$page1ImageHeightRatio, F=($currentP1FocusX,$currentP1FocusY), CS=$currentP1ContentScaleFactor")

            val p1TopUpdateJob = engineScope.launch { // 可以重命名 job 变量
                var newTopCropped: Bitmap? = null
                val oldTopCropped = engineWallpaperBitmaps?.page1TopCroppedBitmap // 保存旧的引用
                try {
                    ensureActive()
                    newTopCropped = withContext(Dispatchers.Default) {
                        ensureActive()
                        SharedWallpaperRenderer.preparePage1TopCroppedBitmap(
                            currentSourceBitmap, screenWidth, screenHeight,
                            page1ImageHeightRatio, currentP1FocusX, currentP1FocusY,
                            currentP1ContentScaleFactor // 传递内容缩放因子
                        )
                    }
                    ensureActive()
                    // 在UI线程更新
                    if (this@H2WallpaperEngine.imageUriString != null && engineWallpaperBitmaps?.sourceSampledBitmap == currentSourceBitmap) {
                        if (oldTopCropped != newTopCropped) oldTopCropped?.recycle() // 回收旧的，如果它与新生成的不同
                        engineWallpaperBitmaps?.page1TopCroppedBitmap = newTopCropped
                    } else { // 如果在这期间源图或URI变了，则新生成的作废
                        newTopCropped?.recycle()
                    }
                } catch (e: CancellationException) { newTopCropped?.recycle() }
                catch (e: Exception) { Log.e(TAG, "updateTopCroppedBitmapAsync failed", e); newTopCropped?.recycle(); if (this@H2WallpaperEngine.imageUriString != null && engineWallpaperBitmaps?.sourceSampledBitmap == currentSourceBitmap) engineWallpaperBitmaps?.page1TopCroppedBitmap = null }
                finally {
                    if (isActive && isVisible && this@H2WallpaperEngine.imageUriString != null) drawCurrentFrame()
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
                        // numPagesReportedByLauncher 是从启动器获取的实际页数，可以用于更精确的滚动计算
                        // 但 SharedWallpaperRenderer 中的 numVirtualPages 是用于预览和过渡效果的页数，可以不同
                        // 这里我们让 SharedWallpaperRenderer 使用一个固定的虚拟页数或基于启动器报告的页数
                        val pagesForRenderer = if (!isPreview && numPagesReportedByLauncher > 1) numPagesReportedByLauncher else DEFAULT_VIRTUAL_PAGES_FOR_SCROLLING

                        val config = SharedWallpaperRenderer.WallpaperConfig(
                            screenWidth, screenHeight, page1BackgroundColor, page1ImageHeightRatio,
                            currentPageOffset, pagesForRenderer, // 使用 pagesForRenderer
                            p1OverlayFadeTransitionRatio = currentP1OverlayFadeRatio,
                            scrollSensitivityFactor = this.currentScrollSensitivity,
                            normalizedInitialBgScrollOffset = this.currentNormalizedInitialBgScrollOffset,
                            p2BackgroundFadeInRatio = this.currentP2BackgroundFadeInRatio,
                            p1ShadowRadius = this.currentP1ShadowRadius,
                            p1ShadowDx = this.currentP1ShadowDx,
                            p1ShadowDy = this.currentP1ShadowDy,
                            p1ShadowColor = this.currentP1ShadowColor,
                            p1ImageBottomFadeHeight = this.currentP1ImageBottomFadeHeight
                            // p1ContentScaleFactor 已在 page1TopCroppedBitmap 生成时应用
                        )
                        SharedWallpaperRenderer.drawFrame(canvas, config, currentWpBitmaps)
                    } else { // 没有图片或P1顶图未准备好
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
                    try { surfaceHolder!!.unlockCanvasAndPost(canvas) }
                    catch (e: Exception) { Log.e(TAG, "Error unlocking canvas", e) }
                }
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            engineScope.cancel() // 取消所有在此作用域启动的协程
            engineWallpaperBitmaps?.recycleInternals() // 清理所有位图资源
            engineWallpaperBitmaps = null
            Log.i(TAG, "H2WallpaperEngine destroyed.")
        }
    }
}