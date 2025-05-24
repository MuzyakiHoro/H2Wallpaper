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

// 显式导入 WallpaperConfigConstants 对象
import com.example.h2wallpaper.WallpaperConfigConstants

// H2WallpaperService 主要使用 WallpaperConfigConstants 的顶层成员，
// FocusParams 主要由 Activity 使用，这里可以不导入 FocusParams，除非将来服务也需要它。

class H2WallpaperService : WallpaperService() {

    companion object {
        private const val TAG = "H2WallpaperSvc"
        private const val DEBUG_TAG = "H2WallpaperSvc_Debug"

        // 这个默认值可以保留在此，或者如果希望完全统一，也可以移至 WallpaperConfigConstants
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

        private val prefs: SharedPreferences by lazy {
            applicationContext.getSharedPreferences(
                WallpaperConfigConstants.PREFS_NAME,
                Context.MODE_PRIVATE
            )
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
            val oldImageContentVersion = currentImageContentVersion
            val oldP1FocusX = currentP1FocusX
            val oldP1FocusY = currentP1FocusY
            val oldPage1ImageHeightRatio = page1ImageHeightRatio
            val oldBackgroundBlurRadius = currentBackgroundBlurRadius
            val oldBlurDownscaleFactor = currentBlurDownscaleFactor
            val oldBlurIterations = currentBlurIterations

            loadPreferencesFromStorage() // 这会更新所有成员变量

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
                                needsP1TopUpdate = true
                            }
                        }

                        WallpaperConfigConstants.KEY_IMAGE_HEIGHT_RATIO -> {
                            if (oldPage1ImageHeightRatio != page1ImageHeightRatio) {
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
                                needsFullReload = true
                            }
                        }

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
                            needsRedrawOnly = true
                        }
                        // KEY_IMAGE_URI 和 KEY_IMAGE_CONTENT_VERSION 已通过比较版本号和URI字符串处理
                        WallpaperConfigConstants.KEY_IMAGE_URI -> { /* Already handled by string comparison */
                        }
                        // else -> Log.w(DEBUG_TAG, "Unhandled preference key: $currentKey") // 可选
                    }
                } ?: run {
                    if (!needsFullReload) { // 只有在没有因为图片变化而需要完整重载时
                        Log.i(
                            DEBUG_TAG,
                            "Preference key is null, and image not changed. Triggering redraw as a precaution."
                        )
                        needsRedrawOnly = true
                    }
                }
            }

            if (needsFullReload) {
                loadFullBitmapsAsync()
            } else if (needsP1TopUpdate) {
                if (engineWallpaperBitmaps?.sourceSampledBitmap != null && imageUriString != null) {
                    updateTopCroppedBitmapAsync()
                } else if (imageUriString != null) {
                    loadFullBitmapsAsync()
                }
            } else if (needsRedrawOnly) {
                if (isVisible) drawCurrentFrame()
            }
        }


        private fun loadPreferencesFromStorage() {
            imageUriString = prefs.getString(WallpaperConfigConstants.KEY_IMAGE_URI, null)
            page1BackgroundColor = prefs.getInt(
                WallpaperConfigConstants.KEY_BACKGROUND_COLOR,
                WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR
            )
            page1ImageHeightRatio = prefs.getFloat(
                WallpaperConfigConstants.KEY_IMAGE_HEIGHT_RATIO,
                WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO
            )
            currentP1FocusX = prefs.getFloat(
                WallpaperConfigConstants.KEY_P1_FOCUS_X,
                WallpaperConfigConstants.DEFAULT_P1_FOCUS_X
            )
            currentP1FocusY = prefs.getFloat(
                WallpaperConfigConstants.KEY_P1_FOCUS_Y,
                WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y
            )

            currentScrollSensitivity = prefs.getInt(
                WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY,
                WallpaperConfigConstants.DEFAULT_SCROLL_SENSITIVITY_INT
            ) / 10.0f

            currentP1OverlayFadeRatio = prefs.getInt(
                WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO,
                WallpaperConfigConstants.DEFAULT_P1_OVERLAY_FADE_RATIO_INT
            ) / 100.0f

            currentBackgroundBlurRadius = prefs.getInt(
                WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS,
                WallpaperConfigConstants.DEFAULT_BACKGROUND_BLUR_RADIUS_INT
            ).toFloat()

            currentNormalizedInitialBgScrollOffset = prefs.getInt(
                WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET,
                WallpaperConfigConstants.DEFAULT_BACKGROUND_INITIAL_OFFSET_INT
            ) / 10.0f

            currentP2BackgroundFadeInRatio = prefs.getInt(
                WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO,
                WallpaperConfigConstants.DEFAULT_P2_BACKGROUND_FADE_IN_RATIO_INT
            ) / 100.0f

            val blurDownscaleFactorInt = prefs.getInt(
                WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR,
                WallpaperConfigConstants.DEFAULT_BLUR_DOWNSCALE_FACTOR_INT
            )
            this.currentBlurDownscaleFactor = blurDownscaleFactorInt / 100.0f
            val blurIterationsVal = prefs.getInt(
                WallpaperConfigConstants.KEY_BLUR_ITERATIONS,
                WallpaperConfigConstants.DEFAULT_BLUR_ITERATIONS
            )
            this.currentBlurIterations = blurIterationsVal

            currentP1ShadowRadius = prefs.getInt(
                WallpaperConfigConstants.KEY_P1_SHADOW_RADIUS,
                WallpaperConfigConstants.DEFAULT_P1_SHADOW_RADIUS_INT
            ).toFloat()

            currentP1ShadowDx = prefs.getInt(
                WallpaperConfigConstants.KEY_P1_SHADOW_DX,
                WallpaperConfigConstants.DEFAULT_P1_SHADOW_DX_INT
            ).toFloat()

            currentP1ShadowDy = prefs.getInt(
                WallpaperConfigConstants.KEY_P1_SHADOW_DY,
                WallpaperConfigConstants.DEFAULT_P1_SHADOW_DY_INT
            ).toFloat()

            currentP1ShadowColor = prefs.getInt(
                WallpaperConfigConstants.KEY_P1_SHADOW_COLOR,
                WallpaperConfigConstants.DEFAULT_P1_SHADOW_COLOR
            )

            currentP1ImageBottomFadeHeight = prefs.getInt(
                WallpaperConfigConstants.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT,
                WallpaperConfigConstants.DEFAULT_P1_IMAGE_BOTTOM_FADE_HEIGHT_INT
            ).toFloat()

            currentImageContentVersion = prefs.getLong(
                WallpaperConfigConstants.KEY_IMAGE_CONTENT_VERSION,
                WallpaperConfigConstants.DEFAULT_IMAGE_CONTENT_VERSION
            )

            Log.i(
                DEBUG_TAG,
                "Preferences loaded/reloaded (Service): URI=$imageUriString, BlurDownscaleFactor=$currentBlurDownscaleFactor, BlurIterations=$currentBlurIterations, P1ShadowRadius=$currentP1ShadowRadius"
            )
        }

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

        private fun loadFullBitmapsAsync() {
            currentBitmapLoadJob?.cancel()
            engineWallpaperBitmaps?.recycleInternals()
            engineWallpaperBitmaps = null

            val uriStringToLoad = imageUriString
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
                drawCurrentFrame()
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
                            page1ImageHeightRatio = page1ImageHeightRatio,
                            normalizedFocusX = currentP1FocusX,
                            normalizedFocusY = currentP1FocusY,
                            blurRadiusForBackground = currentBackgroundBlurRadius,
                            blurDownscaleFactor = currentBlurDownscaleFactor,
                            blurIterations = currentBlurIterations
                        )
                    }
                    ensureActive()

                    if (this@H2WallpaperEngine.imageUriString == uriStringToLoad) {
                        engineWallpaperBitmaps = newBitmapsHolder
                        Log.i(DEBUG_TAG, "Full bitmaps loaded successfully for $uri.")
                    } else {
                        newBitmapsHolder?.recycleInternals()
                        if (this@H2WallpaperEngine.imageUriString == null) engineWallpaperBitmaps =
                            null
                    }
                } catch (e: CancellationException) {
                    newBitmapsHolder?.recycleInternals()
                } catch (e: Exception) {
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
                if (imageUriString != null) loadFullBitmapsAsyncIfNeeded()
                else if (isVisible) drawCurrentFrame()
                return
            }
            Log.i(
                DEBUG_TAG,
                "updateTopCroppedBitmapAsync: Starting for ratio $page1ImageHeightRatio, Focus($currentP1FocusX, $currentP1FocusY)"
            )

            val p1TopUpdateJob = engineScope.launch { // Renamed to avoid conflict with member
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

            val scrollingUpdateJob = engineScope.launch { // Renamed to avoid conflict with member
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
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            engineScope.cancel()
            engineWallpaperBitmaps?.recycleInternals()
            engineWallpaperBitmaps = null
            Log.i(TAG, "H2WallpaperEngine destroyed.")
        }
    }
}