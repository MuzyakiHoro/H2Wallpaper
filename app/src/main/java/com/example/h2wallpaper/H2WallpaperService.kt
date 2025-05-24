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

// 导入 WallpaperConfigConstants 对象
import com.example.h2wallpaper.WallpaperConfigConstants

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

        // --- WallpaperPreferencesRepository 实例 ---
        private lateinit var preferencesRepository: WallpaperPreferencesRepository

        // SharedPreferences 实例仍然需要，用于注册监听器
        private lateinit var prefs: SharedPreferences


        // --- Configuration members ---
        private var imageUriString: String? = null
        private var page1BackgroundColor: Int = WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR
        private var page1ImageHeightRatio: Float = WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO
        private var currentScrollSensitivity: Float =
            WallpaperConfigConstants.DEFAULT_SCROLL_SENSITIVITY
        private var currentP1FocusX: Float = WallpaperConfigConstants.DEFAULT_P1_FOCUS_X
        private var currentP1FocusY: Float = WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y
        private var currentP1OverlayFadeRatio: Float =
            WallpaperConfigConstants.DEFAULT_P1_OVERLAY_FADE_RATIO
        private var currentBackgroundBlurRadius: Float =
            WallpaperConfigConstants.DEFAULT_BACKGROUND_BLUR_RADIUS
        private var currentNormalizedInitialBgScrollOffset: Float =
            WallpaperConfigConstants.DEFAULT_BACKGROUND_INITIAL_OFFSET
        private var currentP2BackgroundFadeInRatio: Float =
            WallpaperConfigConstants.DEFAULT_P2_BACKGROUND_FADE_IN_RATIO
        private var currentBlurDownscaleFactor: Float =
            WallpaperConfigConstants.DEFAULT_BLUR_DOWNSCALE_FACTOR_INT / 100.0f
        private var currentBlurIterations: Int = WallpaperConfigConstants.DEFAULT_BLUR_ITERATIONS
        private var currentP1ShadowRadius: Float = WallpaperConfigConstants.DEFAULT_P1_SHADOW_RADIUS
        private var currentP1ShadowDx: Float = WallpaperConfigConstants.DEFAULT_P1_SHADOW_DX
        private var currentP1ShadowDy: Float = WallpaperConfigConstants.DEFAULT_P1_SHADOW_DY
        private var currentP1ShadowColor: Int = WallpaperConfigConstants.DEFAULT_P1_SHADOW_COLOR
        private var currentP1ImageBottomFadeHeight: Float =
            WallpaperConfigConstants.DEFAULT_P1_IMAGE_BOTTOM_FADE_HEIGHT
        private var currentImageContentVersion: Long =
            WallpaperConfigConstants.DEFAULT_IMAGE_CONTENT_VERSION
        // --- End Configuration members ---

        private var numPagesReportedByLauncher = 1
        private var currentPageOffset = 0f


        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            this.surfaceHolder = surfaceHolder
            Log.i(TAG, "H2WallpaperEngine Created. IsPreview: ${isPreview}")

            // 初始化 Repository 和 SharedPreferences 实例
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
                Log.i(
                    DEBUG_TAG,
                    "onCreate: Conditions for initial load not met (no URI or screen size yet)."
                )
            }
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
            Log.i(DEBUG_TAG, "onSharedPreferenceChanged: key=$key received by service.")
            var needsFullReload = false
            var needsP1TopUpdate = false
            var needsRedrawOnly = false

            val oldImageUriString = imageUriString
            val oldImageContentVersion = currentImageContentVersion // 使用 Repository 获取的值
            val oldP1FocusX = currentP1FocusX
            val oldP1FocusY = currentP1FocusY
            val oldPage1ImageHeightRatio = page1ImageHeightRatio
            val oldBackgroundBlurRadius = currentBackgroundBlurRadius
            val oldBlurDownscaleFactor = currentBlurDownscaleFactor
            val oldBlurIterations = currentBlurIterations

            // 从 Repository 重新加载所有配置到成员变量
            loadPreferencesFromStorage()

            // --- 核心判断逻辑：优先检查图片内容版本或URI本身 ---
            if (oldImageContentVersion != currentImageContentVersion) {
                Log.i(DEBUG_TAG, "Image Content Version changed. Triggering full reload.")
                needsFullReload = true
            } else if (oldImageUriString != imageUriString) {
                Log.i(DEBUG_TAG, "Image URI string changed. Triggering full reload.")
                needsFullReload = true
            } else {
                key?.let { currentKey ->
                    when (currentKey) {
                        WallpaperConfigConstants.KEY_P1_FOCUS_X,
                        WallpaperConfigConstants.KEY_P1_FOCUS_Y -> {
                            if (oldP1FocusX != currentP1FocusX || oldP1FocusY != currentP1FocusY) {
                                Log.i(
                                    DEBUG_TAG,
                                    "Preference changed: P1 Focus. Triggering P1 top update."
                                )
                                needsP1TopUpdate = true
                            }
                        }

                        WallpaperConfigConstants.KEY_IMAGE_HEIGHT_RATIO -> {
                            if (oldPage1ImageHeightRatio != page1ImageHeightRatio) {
                                Log.i(
                                    DEBUG_TAG,
                                    "Preference changed: P1 Height Ratio. Triggering P1 top update."
                                )
                                needsP1TopUpdate = true
                            }
                        }

                        WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS,
                        WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR,
                        WallpaperConfigConstants.KEY_BLUR_ITERATIONS -> {
                            if (oldBackgroundBlurRadius != currentBackgroundBlurRadius ||
                                oldBlurDownscaleFactor != currentBlurDownscaleFactor ||
                                oldBlurIterations != currentBlurIterations
                            ) {
                                Log.i(
                                    DEBUG_TAG,
                                    "Preference changed: Blur related params. Triggering full reload."
                                )
                                needsFullReload = true
                            }
                        }
                        // 其他只影响最终绘制表现的参数，只需要重绘
                        WallpaperConfigConstants.KEY_BACKGROUND_COLOR,
                        WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY,
                        WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO,
                        WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO,
                        WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET,
                        WallpaperConfigConstants.KEY_P1_SHADOW_RADIUS,
                        WallpaperConfigConstants.KEY_P1_SHADOW_DX,
                        WallpaperConfigConstants.KEY_P1_SHADOW_DY,
                        WallpaperConfigConstants.KEY_P1_SHADOW_COLOR,
                        WallpaperConfigConstants.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT -> {
                            Log.i(
                                DEBUG_TAG,
                                "Preference changed: Visual rendering param. Triggering redraw."
                            )
                            needsRedrawOnly = true
                        }
                        // KEY_IMAGE_URI 和 KEY_IMAGE_CONTENT_VERSION 已通过比较版本号和URI字符串处理
                        WallpaperConfigConstants.KEY_IMAGE_URI -> { /* Already handled */
                        }
                        // else -> Log.w(DEBUG_TAG, "Unhandled preference key in onSharedPreferenceChanged: $currentKey")
                    }
                } ?: run { // key is null
                    if (!needsFullReload) {
                        Log.i(
                            DEBUG_TAG,
                            "Preference key is null, and image not changed. Triggering redraw as a precaution."
                        )
                        needsRedrawOnly = true
                    }
                }
            }

            // --- 根据标志执行操作 ---
            if (needsFullReload) {
                Log.i(DEBUG_TAG, "Action: Executing full bitmap reload due to changed preferences.")
                loadFullBitmapsAsync()
            } else if (needsP1TopUpdate) {
                if (engineWallpaperBitmaps?.sourceSampledBitmap != null && imageUriString != null) {
                    Log.i(DEBUG_TAG, "Action: Executing P1 top cropped bitmap update.")
                    updateTopCroppedBitmapAsync()
                } else if (imageUriString != null) {
                    Log.w(
                        DEBUG_TAG,
                        "Action: P1 top update requested, but sourceSampledBitmap is null. Forcing full reload."
                    )
                    loadFullBitmapsAsync()
                }
            } else if (needsRedrawOnly) {
                if (isVisible) {
                    Log.i(DEBUG_TAG, "Action: Executing redraw due to changed preferences.")
                    drawCurrentFrame()
                }
            }
        }


        private fun loadPreferencesFromStorage() {
            // 从 Repository 读取配置
            imageUriString = preferencesRepository.getSelectedImageUri()?.toString()
            page1BackgroundColor = preferencesRepository.getSelectedBackgroundColor()
            page1ImageHeightRatio = preferencesRepository.getPage1ImageHeightRatio()
            currentP1FocusX = preferencesRepository.getP1FocusX()
            currentP1FocusY = preferencesRepository.getP1FocusY()
            currentScrollSensitivity = preferencesRepository.getScrollSensitivity()
            currentP1OverlayFadeRatio = preferencesRepository.getP1OverlayFadeRatio()
            currentBackgroundBlurRadius = preferencesRepository.getBackgroundBlurRadius()
            currentNormalizedInitialBgScrollOffset =
                preferencesRepository.getBackgroundInitialOffset()
            currentP2BackgroundFadeInRatio = preferencesRepository.getP2BackgroundFadeInRatio()
            currentBlurDownscaleFactor = preferencesRepository.getBlurDownscaleFactorInt() / 100.0f
            currentBlurIterations = preferencesRepository.getBlurIterations()
            currentP1ShadowRadius = preferencesRepository.getP1ShadowRadius()
            currentP1ShadowDx = preferencesRepository.getP1ShadowDx()
            currentP1ShadowDy = preferencesRepository.getP1ShadowDy()
            currentP1ShadowColor = preferencesRepository.getP1ShadowColor()
            currentP1ImageBottomFadeHeight = preferencesRepository.getP1ImageBottomFadeHeight()
            currentImageContentVersion = preferencesRepository.getImageContentVersion()

            Log.i(
                DEBUG_TAG,
                "Preferences loaded/reloaded via Repository (Service): URI=$imageUriString, BlurFactor=$currentBlurDownscaleFactor, BlurIter=$currentBlurIterations"
            )
        }

        // loadFullBitmapsAsyncIfNeeded, onSurfaceChanged, onSurfaceDestroyed,
        // onVisibilityChanged, onOffsetsChanged, loadFullBitmapsAsync,
        // updateTopCroppedBitmapAsync, updateScrollingBackgroundAsync,
        // drawCurrentFrame, onDestroy
        // 这些方法的内部逻辑大部分保持不变，因为它们依赖的是已经通过 loadPreferencesFromStorage()
        // 更新好的成员变量。

        // 只需要确保在调用 SharedWallpaperRenderer 的方法时，传递的是这些最新的成员变量即可。
        // 例如，在 loadFullBitmapsAsync 中：
        private fun loadFullBitmapsAsync() {
            currentBitmapLoadJob?.cancel()
            engineWallpaperBitmaps?.recycleInternals()
            engineWallpaperBitmaps = null

            val uriStringToLoad = imageUriString // 使用成员变量
            if (uriStringToLoad == null || screenWidth <= 0 || screenHeight <= 0) {
                if (isVisible) drawCurrentFrame()
                return
            }
            val uri = Uri.parse(uriStringToLoad)
            Log.i(
                DEBUG_TAG,
                "loadFullBitmapsAsync: Starting for URI: $uri. Focus:($currentP1FocusX, $currentP1FocusY), Blur:$currentBackgroundBlurRadius"
            )

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

                    newBitmapsHolder = withContext(Dispatchers.IO) {
                        ensureActive()
                        SharedWallpaperRenderer.loadAndProcessInitialBitmaps(
                            context = applicationContext,
                            imageUri = uri,
                            targetScreenWidth = screenWidth,
                            targetScreenHeight = screenHeight,
                            page1ImageHeightRatio = page1ImageHeightRatio, // 使用成员变量
                            normalizedFocusX = currentP1FocusX,           // 使用成员变量
                            normalizedFocusY = currentP1FocusY,           // 使用成员变量
                            blurRadiusForBackground = currentBackgroundBlurRadius, // 使用成员变量
                            blurDownscaleFactor = currentBlurDownscaleFactor,     // 使用成员变量
                            blurIterations = currentBlurIterations            // 使用成员变量
                        )
                    }
                    ensureActive()

                    if (this@H2WallpaperEngine.imageUriString == uriStringToLoad) {
                        engineWallpaperBitmaps = newBitmapsHolder
                    } else {
                        newBitmapsHolder?.recycleInternals()
                        if (this@H2WallpaperEngine.imageUriString == null) engineWallpaperBitmaps =
                            null
                    }
                } catch (e: CancellationException) {
                    newBitmapsHolder?.recycleInternals()
                } catch (e: Exception) {
                    newBitmapsHolder?.recycleInternals()
                    engineWallpaperBitmaps = null // 确保出错时清空
                } finally {
                    if (coroutineContext[Job] == currentBitmapLoadJob) currentBitmapLoadJob = null
                    if (isVisible && (this@H2WallpaperEngine.imageUriString == uriStringToLoad || this@H2WallpaperEngine.imageUriString == null)) {
                        drawCurrentFrame()
                    }
                }
            }
        }

        // 其余方法 (onSurfaceChanged, onSurfaceDestroyed, onVisibilityChanged, onOffsetsChanged,
        // updateTopCroppedBitmapAsync, updateScrollingBackgroundAsync, drawCurrentFrame, onDestroy)
        // 的内部逻辑与之前基本一致，因为它们大多操作的是引擎的成员变量或调用已更新的方法。
        // 主要变化是在 loadPreferencesFromStorage 中如何获取这些成员变量的值。

        private fun loadFullBitmapsAsyncIfNeeded() {
            if (imageUriString != null && screenWidth > 0 && screenHeight > 0) {
                if (currentBitmapLoadJob == null || !currentBitmapLoadJob!!.isActive || engineWallpaperBitmaps?.sourceSampledBitmap == null) {
                    loadFullBitmapsAsync()
                } else {
                    if (isVisible) drawCurrentFrame()
                }
            } else if (imageUriString == null) {
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
            Log.i(
                TAG,
                "Surface changed: New $width x $height. Old: ${oldScreenWidth}x${oldScreenHeight}. IsPreview: ${isPreview}"
            )

            if (screenWidth > 0 && screenHeight > 0) {
                if (imageUriString != null) {
                    loadFullBitmapsAsyncIfNeeded()
                } else {
                    if (isVisible) drawCurrentFrame()
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
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.isVisible = visible
            Log.i(TAG, "Visibility changed: $visible. IsPreview: ${isPreview}")
            if (visible) {
                if (engineWallpaperBitmaps?.sourceSampledBitmap == null && imageUriString != null && screenWidth > 0 && screenHeight > 0) {
                    loadFullBitmapsAsyncIfNeeded()
                } else {
                    drawCurrentFrame()
                }
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float, xOffsetStepParam: Float,
            yOffsetStep: Float, xPixelOffset: Int, yPixelOffset: Int
        ) {
            super.onOffsetsChanged(
                xOffset,
                yOffset,
                xOffsetStepParam,
                yOffsetStep,
                xPixelOffset,
                yPixelOffset
            )
            val oldFrameworkOffset = this.currentPageOffset
            this.currentPageOffset = xOffset

            val newlyCalculatedPages: Int
            if (xOffsetStepParam > 0.00001f && xOffsetStepParam < 1.0f) {
                newlyCalculatedPages = (1.0f / xOffsetStepParam).roundToInt() + 1
            } else {
                newlyCalculatedPages = 1
            }

            val oldNumPages = numPagesReportedByLauncher
            this.numPagesReportedByLauncher = newlyCalculatedPages.coerceIn(1, 20)


            Log.i(
                DEBUG_TAG,
                "onOffsetsChanged: xOffsetStepParam=$xOffsetStepParam, Calculated total pages=${this.numPagesReportedByLauncher}, oldNumPages=$oldNumPages, currentXOffset=$xOffset"
            )

            if (!isPreview && oldNumPages != this.numPagesReportedByLauncher && this.numPagesReportedByLauncher > 0 && imageUriString != null) {
                if (engineWallpaperBitmaps?.sourceSampledBitmap != null) {
                    updateScrollingBackgroundAsync()
                } else {
                    loadFullBitmapsAsyncIfNeeded()
                }
            } else if (oldFrameworkOffset != xOffset) {
                if (isVisible && surfaceHolder?.surface?.isValid == true) {
                    drawCurrentFrame()
                }
            }
        }

        private fun updateTopCroppedBitmapAsync() {
            val currentSourceBitmap = engineWallpaperBitmaps?.sourceSampledBitmap
            if (currentSourceBitmap == null || screenWidth <= 0 || screenHeight <= 0 || imageUriString == null) {
                if (imageUriString != null) loadFullBitmapsAsyncIfNeeded()
                else if (isVisible) drawCurrentFrame()
                return
            }
            Log.i(
                DEBUG_TAG,
                "updateTopCroppedBitmapAsync: Starting for ratio $page1ImageHeightRatio, Focus($currentP1FocusX, $currentP1FocusY)"
            )

            val p1TopUpdateJob = engineScope.launch {
                var newTopCropped: Bitmap? = null
                val oldTopCropped = engineWallpaperBitmaps?.page1TopCroppedBitmap
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
                        if (oldTopCropped != newTopCropped) oldTopCropped?.recycle()
                        engineWallpaperBitmaps?.page1TopCroppedBitmap = newTopCropped
                    } else {
                        newTopCropped?.recycle()
                    }
                } catch (e: CancellationException) {
                    newTopCropped?.recycle()
                } catch (e: Exception) {
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
                if (imageUriString != null) loadFullBitmapsAsyncIfNeeded()
                else if (isVisible) drawCurrentFrame()
                return
            }

            val downscaleFactorToUse = currentBlurDownscaleFactor
            val iterationsToUse = currentBlurIterations
            Log.i(
                DEBUG_TAG,
                "updateScrollingBackgroundAsync: BlurRadius: $currentBackgroundBlurRadius, Downscale: $downscaleFactorToUse, Iterations: $iterationsToUse"
            )

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
                            currentBackgroundBlurRadius,
                            downscaleFactorToUse,
                            iterationsToUse
                        )
                    }
                    ensureActive()
                    if (this@H2WallpaperEngine.imageUriString != null && engineWallpaperBitmaps?.sourceSampledBitmap == currentSource) {
                        if (oldScrollingBitmap != newScrollingPair?.first) oldScrollingBitmap?.recycle()
                        if (oldBlurredBitmap != newScrollingPair?.second) oldBlurredBitmap?.recycle()
                        engineWallpaperBitmaps?.scrollingBackgroundBitmap = newScrollingPair?.first
                        engineWallpaperBitmaps?.blurredScrollingBackgroundBitmap =
                            newScrollingPair?.second
                    } else {
                        newScrollingPair?.first?.recycle(); newScrollingPair?.second?.recycle()
                    }
                } catch (e: CancellationException) {
                    newScrollingPair?.first?.recycle(); newScrollingPair?.second?.recycle()
                } catch (e: Exception) {
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
                            p1OverlayFadeTransitionRatio = currentP1OverlayFadeRatio,
                            scrollSensitivityFactor = this.currentScrollSensitivity,
                            normalizedInitialBgScrollOffset = this.currentNormalizedInitialBgScrollOffset,
                            p2BackgroundFadeInRatio = this.currentP2BackgroundFadeInRatio,
                            p1ShadowRadius = this.currentP1ShadowRadius,
                            p1ShadowDx = this.currentP1ShadowDx,
                            p1ShadowDy = this.currentP1ShadowDy,
                            p1ShadowColor = this.currentP1ShadowColor,
                            p1ImageBottomFadeHeight = this.currentP1ImageBottomFadeHeight
                        )
                        SharedWallpaperRenderer.drawFrame(canvas, config, currentWpBitmaps)
                    } else {
                        SharedWallpaperRenderer.drawPlaceholder(
                            canvas, screenWidth, screenHeight,
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
                    try {
                        surfaceHolder!!.unlockCanvasAndPost(canvas)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error unlocking canvas", e)
                    }
                }
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            prefs.unregisterOnSharedPreferenceChangeListener(this) // 仍然需要取消监听
            engineScope.cancel()
            engineWallpaperBitmaps?.recycleInternals()
            engineWallpaperBitmaps = null
            Log.i(TAG, "H2WallpaperEngine destroyed.")
        }
    }
}