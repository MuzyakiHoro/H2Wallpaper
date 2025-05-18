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
       // private const val BACKGROUND_BLUR_RADIUS = 25f
        private const val DEFAULT_VIRTUAL_PAGES_FOR_SCROLLING = 3
    }

    override fun onCreateEngine(): Engine {
        return H2WallpaperEngine()
    }

    // 移除了 SharedPreferences.OnSharedPreferenceChangeListener
    private inner class H2WallpaperEngine : Engine() {

        private var surfaceHolder: SurfaceHolder? = null
        private var isVisible: Boolean = false
        private var screenWidth: Int = 0
        private var screenHeight: Int = 0

        private var engineWallpaperBitmaps: SharedWallpaperRenderer.WallpaperBitmaps? = null
        private val engineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        private var currentBitmapLoadJob: Job? = null

        // 从 SharedPreferences 加载的配置
        private var imageUriString: String? = null
        private var page1BackgroundColor: Int = Color.LTGRAY
        private var page1ImageHeightRatio: Float = 1f / 3f
        private val defaultHeightRatioEngine = 1f / 3f
        private var currentScrollSensitivity: Float = MainActivity.DEFAULT_SCROLL_SENSITIVITY
        private var currentP1FocusX: Float = 0.5f
        private var currentP1FocusY: Float = 0.5f

        // Launcher 报告的页面信息
        private var numPagesReportedByLauncher = 1
        private var currentPageOffset = 0f

        private var hasLoadedInitialPreferencesOnCreate = false // 新增标志

        private val prefs: SharedPreferences by lazy {
            applicationContext.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            this.surfaceHolder = surfaceHolder
            Log.i(TAG, "H2WallpaperEngine Created. IsPreview: ${isPreview}")

            loadPreferencesFromStorage() // 加载一次配置
            hasLoadedInitialPreferencesOnCreate = true // 标记已在 onCreate 加载过

            // 只有当屏幕尺寸已知且有URI时，才考虑加载位图
            if (screenWidth > 0 && screenHeight > 0 && imageUriString != null) {
                Log.i(DEBUG_TAG, "onCreate: Triggering initial bitmap load if needed.")
                loadFullBitmapsAsyncIfNeeded()
            } else {
                Log.i(DEBUG_TAG, "onCreate: Conditions for initial load not met (screen dimensions or URI).")
            }
        }

        private fun loadPreferencesFromStorage() {
            imageUriString = prefs.getString(MainActivity.KEY_IMAGE_URI, null)
            page1BackgroundColor = prefs.getInt(MainActivity.KEY_BACKGROUND_COLOR, Color.LTGRAY)
            page1ImageHeightRatio = prefs.getFloat(MainActivity.KEY_IMAGE_HEIGHT_RATIO, defaultHeightRatioEngine)
            currentScrollSensitivity = prefs.getFloat(MainActivity.KEY_SCROLL_SENSITIVITY, MainActivity.DEFAULT_SCROLL_SENSITIVITY)
            Log.i(DEBUG_TAG, "Preferences loaded from storage: URI=$imageUriString, Color=$page1BackgroundColor, Ratio=$page1ImageHeightRatio, Sensitivity=$currentScrollSensitivity")
        }

        private fun loadFullBitmapsAsyncIfNeeded() {
            // 只有当URI存在，屏幕尺寸有效，并且当前没有正在进行的加载任务，或者当前位图为空时，才加载
            if (imageUriString != null && screenWidth > 0 && screenHeight > 0) {
                if (currentBitmapLoadJob == null || !currentBitmapLoadJob!!.isActive || engineWallpaperBitmaps?.sourceSampledBitmap == null) {
                    Log.i(DEBUG_TAG, "loadFullBitmapsAsyncIfNeeded: Conditions met, calling loadFullBitmapsAsync.")
                    loadFullBitmapsAsync()
                } else {
                    Log.i(DEBUG_TAG, "loadFullBitmapsAsyncIfNeeded: Bitmaps seem to be loaded or loading for current URI, skipping direct call.")
                    // 如果已有位图，可能需要一次重绘来确保应用了最新的非位图参数（颜色等）
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
                Log.i(DEBUG_TAG, "SurfaceChanged: Dimensions valid. Reloading preferences and ensuring bitmaps are loaded.")
                loadPreferencesFromStorage() // 确保配置是最新的
                loadFullBitmapsAsyncIfNeeded() // 根据最新的配置加载位图 (如果需要)
            } else {
                currentBitmapLoadJob?.cancel(); currentBitmapLoadJob = null
                engineWallpaperBitmaps?.recycleInternals()
                engineWallpaperBitmaps = null
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            isVisible = false
            currentBitmapLoadJob?.cancel(); currentBitmapLoadJob = null
            engineScope.cancel()
            engineWallpaperBitmaps?.recycleInternals()
            engineWallpaperBitmaps = null
            Log.i(TAG, "H2WallpaperEngine Surface destroyed.")
            hasLoadedInitialPreferencesOnCreate = false // 重置标志
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.isVisible = visible
            Log.i(TAG, "Visibility changed: $visible. IsPreview: ${isPreview}")
            if (visible) {
                // 当变为可见时，如果 onCreate 还没有机会加载初始偏好 (例如服务已在后台但引擎被重用)
                // 或者我们想在这里强制刷新一次（但我们现在的目标是减少不必要的 SharedPreferences 读取）
                // if (!hasLoadedInitialPreferencesOnCreate) { // 这个标志可能不够用，因为引擎可能不是新创建的
                //    Log.i(DEBUG_TAG, "VisibilityChanged: Initial prefs not loaded in onCreate, loading now.")
                //    loadPreferencesFromStorage()
                //    hasLoadedInitialPreferencesOnCreate = true
                // }

                // 核心逻辑：变为可见时，如果当前没有有效的源位图，则尝试加载。
                // 它会使用当前引擎内存中的 imageUriString。
                // 这个 imageUriString 只会在引擎 onCreate 或 onSurfaceChanged 时从 SharedPreferences 更新。
                if (engineWallpaperBitmaps?.sourceSampledBitmap == null && imageUriString != null && screenWidth > 0 && screenHeight > 0) {
                    Log.i(DEBUG_TAG, "VisibilityChanged (visible=true): Source bitmap missing. Trying to load based on current engine URI: $imageUriString")
                    loadFullBitmapsAsyncIfNeeded() // 会使用引擎当前的 imageUriString
                } else if (engineWallpaperBitmaps != null) {
                    // 如果已有位图，仅重绘以确保应用了最新的非位图参数（如颜色，虽然颜色也不会在这里主动从prefs更新）
                    // 主要是为了响应可见性变化本身
                    Log.i(DEBUG_TAG, "VisibilityChanged (visible=true): Bitmaps exist. Redrawing current state.")
                    drawCurrentFrame()
                } else if (imageUriString == null) { // 确保无图时绘制占位符
                    Log.i(DEBUG_TAG, "VisibilityChanged (visible=true): No image URI. Ensuring placeholder.")
                    drawCurrentFrame()
                }
            }
        }

        override fun onOffsetsChanged( /* ...参数名与您之前版本一致... */
                                       xOffset: Float, yOffset: Float, xOffsetStepParam: Float,
                                       yOffsetStep: Float, xPixelOffset: Int, yPixelOffset: Int
        ) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStepParam, yOffsetStep, xPixelOffset, yPixelOffset)
            val oldFrameworkOffset = this.currentPageOffset
            this.currentPageOffset = xOffset

            val currentXOffsetStep = if (xOffsetStepParam <= 0f || xOffsetStepParam >= 1f) 1.0f else xOffsetStepParam
            val reportedPages = if (currentXOffsetStep > 0.0001f && currentXOffsetStep < 1.0f) {
                (1f / currentXOffsetStep).roundToInt().coerceAtLeast(1)
            } else 1
            val oldNumPages = numPagesReportedByLauncher
            this.numPagesReportedByLauncher = reportedPages

            // Log.i(DEBUG_TAG, "onOffsetsChanged: xOffset=$xOffset, xOffsetStepParam=$xOffsetStepParam -> calculatedStep=$currentXOffsetStep, reportedPages=$numPagesReportedByLauncher. IsPreview: ${isPreview}")

            if (!isPreview && oldNumPages != this.numPagesReportedByLauncher && this.numPagesReportedByLauncher > 0 && imageUriString != null) {
                if (engineWallpaperBitmaps?.sourceSampledBitmap != null) {
                    Log.i(DEBUG_TAG, "Number of reported pages changed from $oldNumPages to ${this.numPagesReportedByLauncher}. Updating scrolling background.")
                    updateScrollingBackgroundAsync()
                } else {
                    Log.i(DEBUG_TAG, "Number of reported pages changed, but no source bitmap. Triggering full reload.")
                    loadFullBitmapsAsyncIfNeeded() // 如果源图也没有，需要完整加载
                }
            } else if (oldFrameworkOffset != xOffset) {
                if (isVisible && surfaceHolder?.surface?.isValid == true) {
                    drawCurrentFrame()
                }
            }
        }

        // 移除 onSharedPreferenceChanged 方法

        private fun loadFullBitmapsAsync() {
            currentBitmapLoadJob?.cancel(); currentBitmapLoadJob = null

            val uriStringToLoad = imageUriString // 使用当前引擎持有的URI
            if (uriStringToLoad == null || screenWidth <= 0 || screenHeight <= 0) {
                Log.w(DEBUG_TAG, "loadFullBitmapsAsync: Preconditions not met. URI: $uriStringToLoad, Screen: ${screenWidth}x$screenHeight")
                engineWallpaperBitmaps?.recycleInternals(); engineWallpaperBitmaps = null
                if (isVisible) drawCurrentFrame()
                return
            }
            val uri = Uri.parse(uriStringToLoad)
            Log.i(DEBUG_TAG, "loadFullBitmapsAsync: Starting for URI: $uri. Current engine imageUriString: $imageUriString")

            val oldBitmaps = engineWallpaperBitmaps
            if (isVisible && oldBitmaps == null) { // 如果即将显示且当前没图，画一次占位符
                drawCurrentFrame()
            }

            currentBitmapLoadJob = engineScope.launch {
                var newBitmaps: SharedWallpaperRenderer.WallpaperBitmaps? = null
                try {
                    ensureActive()
                    val pagesForBackground = if (!isPreview && numPagesReportedByLauncher <= 1) {
                        DEFAULT_VIRTUAL_PAGES_FOR_SCROLLING
                    } else {
                        numPagesReportedByLauncher.coerceAtLeast(1)
                    }
                    Log.i(DEBUG_TAG, "loadFullBitmapsAsync (coroutine): Using $pagesForBackground pages for background. Sensitivity: $currentScrollSensitivity")

                    // 使用命名参数的调用方式 (更安全)
                    newBitmaps = withContext(Dispatchers.IO) {
                        ensureActive()
                        SharedWallpaperRenderer.loadAndProcessInitialBitmaps(
                            context = applicationContext,
                            imageUri = uri,
                            targetScreenWidth = screenWidth,
                            targetScreenHeight = screenHeight,
                            page1ImageHeightRatio = page1ImageHeightRatio,
                            normalizedFocusX = currentP1FocusX,
                            normalizedFocusY = currentP1FocusY,
                            numVirtualPagesForScrolling = pagesForBackground,
                            blurRadiusForBackground = SharedWallpaperRenderer.DEFAULT_BACKGROUND_BLUR_RADIUS
                        )
                    }
                    ensureActive()
                    // 比较的是加载开始时的 this.imageUriString (即 uriStringToLoad)
                    if (this@H2WallpaperEngine.imageUriString == uriStringToLoad) {
                        if (oldBitmaps != newBitmaps) oldBitmaps?.recycleInternals() // 只有当新旧不同时才回收旧的
                        engineWallpaperBitmaps = newBitmaps
                        Log.i(DEBUG_TAG, "Full bitmaps loaded successfully for $uri.")
                    } else {
                        Log.w(DEBUG_TAG, "URI changed during full load for $uri. Current engine URI: ${this@H2WallpaperEngine.imageUriString}. Discarding newly loaded bitmaps.")
                        newBitmaps?.recycleInternals()
                        if (this@H2WallpaperEngine.imageUriString == null && engineWallpaperBitmaps != null) { // 如果最新的URI是null
                            if (engineWallpaperBitmaps != oldBitmaps) engineWallpaperBitmaps?.recycleInternals() // 如果当前不是旧的，回收当前
                            engineWallpaperBitmaps = null
                        }
                    }
                } catch (e: CancellationException) {
                    Log.d(DEBUG_TAG, "Full bitmap loading CANCELLED for $uri.")
                    newBitmaps?.recycleInternals()
                    if (oldBitmaps != null && engineWallpaperBitmaps != oldBitmaps && this@H2WallpaperEngine.imageUriString == uriStringToLoad) {
                        // 如果被取消，且旧位图存在，且当前引擎的URI还是当初加载时的URI，则尝试恢复旧位图
                        engineWallpaperBitmaps = oldBitmaps
                        Log.i(DEBUG_TAG,"Full load cancelled, restoring old bitmaps for $uriStringToLoad")
                    } else if (oldBitmaps != null && this@H2WallpaperEngine.imageUriString != uriStringToLoad){
                        // 如果URI已经变了，旧位图也不再适用
                        oldBitmaps.recycleInternals()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in loadFullBitmapsAsync for $uri", e)
                    newBitmaps?.recycleInternals()
                    if (engineWallpaperBitmaps != oldBitmaps) engineWallpaperBitmaps?.recycleInternals()
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
            currentBitmapLoadJob?.cancel(); currentBitmapLoadJob = null

            val currentSourceBitmap = engineWallpaperBitmaps?.sourceSampledBitmap
            if (currentSourceBitmap == null || screenWidth <= 0 || screenHeight <= 0) {
                Log.w(DEBUG_TAG, "updateTopCroppedBitmapAsync: Source bitmap is null or screen dimensions invalid. Triggering full reload if URI exists.")
                if (imageUriString != null) loadFullBitmapsAsyncIfNeeded() // 改为调用 ifNeeded
                else if(isVisible) drawCurrentFrame()
                return
            }
            Log.i(DEBUG_TAG, "updateTopCroppedBitmapAsync: Starting for ratio $page1ImageHeightRatio")

            currentBitmapLoadJob = engineScope.launch {
                var newTopCropped: Bitmap? = null
                val oldTopCropped = engineWallpaperBitmaps?.page1TopCroppedBitmap
                try {
                    ensureActive()
                    newTopCropped = withContext(Dispatchers.Default) {
                        ensureActive()
                        SharedWallpaperRenderer.preparePage1TopCroppedBitmap(
                            currentSourceBitmap, screenWidth, screenHeight, page1ImageHeightRatio
                        )
                    }
                    ensureActive()
                    if (this@H2WallpaperEngine.imageUriString != null && engineWallpaperBitmaps?.sourceSampledBitmap == currentSourceBitmap) {
                        if (oldTopCropped != newTopCropped) oldTopCropped?.recycle()
                        engineWallpaperBitmaps?.page1TopCroppedBitmap = newTopCropped
                        Log.i(DEBUG_TAG, "Top cropped bitmap updated successfully.")
                    } else {
                        Log.w(DEBUG_TAG, "State changed during top bitmap update. Discarding new top bitmap.")
                        newTopCropped?.recycle()
                    }
                } catch (e: CancellationException) { Log.d(DEBUG_TAG, "Top bitmap update CANCELLED."); newTopCropped?.recycle()
                } catch (e: Exception) { Log.e(TAG, "Error updating top cropped bitmap", e); newTopCropped?.recycle()
                } finally {
                    if (coroutineContext[Job] == currentBitmapLoadJob) currentBitmapLoadJob = null
                    if (isVisible && this@H2WallpaperEngine.imageUriString != null) drawCurrentFrame()
                }
            }
        }

        private fun updateScrollingBackgroundAsync() {
            currentBitmapLoadJob?.cancel(); currentBitmapLoadJob = null

            val currentSource = engineWallpaperBitmaps?.sourceSampledBitmap
            if (currentSource == null || imageUriString == null || screenWidth <= 0 || screenHeight <= 0) {
                Log.w(DEBUG_TAG, "updateScrollingBackgroundAsync: Cannot update, source bitmap is null or preconditions not met.")
                if (imageUriString != null) loadFullBitmapsAsyncIfNeeded() // 改为调用 ifNeeded
                else if(isVisible) drawCurrentFrame()
                return
            }
            Log.i(DEBUG_TAG, "updateScrollingBackgroundAsync: Starting for numPages $numPagesReportedByLauncher")

            currentBitmapLoadJob = engineScope.launch {
                var newScrollingPair: Pair<Bitmap?, Bitmap?>? = null
                val oldScrollingBitmap = engineWallpaperBitmaps?.scrollingBackgroundBitmap
                val oldBlurredBitmap = engineWallpaperBitmaps?.blurredScrollingBackgroundBitmap
                try {
                    ensureActive()
                    val pagesForBg = if (!isPreview && numPagesReportedByLauncher <= 1) {
                        DEFAULT_VIRTUAL_PAGES_FOR_SCROLLING
                    } else {
                        numPagesReportedByLauncher.coerceAtLeast(1)
                    }
                    Log.i(DEBUG_TAG, "updateScrollingBackgroundAsync (coroutine): Using $pagesForBg pages for background generation.")

                    newScrollingPair = withContext(Dispatchers.IO) {
                        ensureActive()
                        SharedWallpaperRenderer.prepareScrollingAndBlurredBitmaps(
                            applicationContext, currentSource, screenWidth, screenHeight,
                            pagesForBg, SharedWallpaperRenderer.DEFAULT_BACKGROUND_BLUR_RADIUS
                        )
                    }
                    ensureActive()
                    if (this@H2WallpaperEngine.imageUriString != null && engineWallpaperBitmaps?.sourceSampledBitmap == currentSource) {
                        if (oldScrollingBitmap != newScrollingPair?.first) oldScrollingBitmap?.recycle()
                        if (oldBlurredBitmap != newScrollingPair?.second) oldBlurredBitmap?.recycle()
                        engineWallpaperBitmaps?.scrollingBackgroundBitmap = newScrollingPair?.first
                        engineWallpaperBitmaps?.blurredScrollingBackgroundBitmap = newScrollingPair?.second
                        Log.i(DEBUG_TAG, "Scrolling background updated successfully. New width: ${newScrollingPair?.first?.width}")
                    } else {
                        Log.w(DEBUG_TAG, "State changed during scrolling background update. Discarding.")
                        newScrollingPair?.first?.recycle(); newScrollingPair?.second?.recycle()
                    }
                } catch (e: CancellationException) { Log.d(DEBUG_TAG, "Scrolling background update CANCELLED."); newScrollingPair?.first?.recycle(); newScrollingPair?.second?.recycle()
                } catch (e: Exception) { Log.e(TAG, "Error updating scrolling background", e); newScrollingPair?.first?.recycle(); newScrollingPair?.second?.recycle()
                } finally {
                    if (coroutineContext[Job] == currentBitmapLoadJob) currentBitmapLoadJob = null
                    if (isVisible && this@H2WallpaperEngine.imageUriString != null) drawCurrentFrame()
                }
            }
        }

        private fun drawCurrentFrame() {
            if (!isVisible || surfaceHolder?.surface?.isValid != true || screenWidth == 0 || screenHeight == 0) {
                Log.d(DEBUG_TAG, "drawCurrentFrame: Skipped. Visible:$isVisible, SurfaceValid:${surfaceHolder?.surface?.isValid}, SW:$screenWidth, SH:$screenHeight")
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
                    if (currentWpBitmaps?.sourceSampledBitmap != null) {
                        val pagesForConfig = if (!isPreview && numPagesReportedByLauncher <= 1) {
                            DEFAULT_VIRTUAL_PAGES_FOR_SCROLLING
                        } else {
                            numPagesReportedByLauncher.coerceAtLeast(1)
                        }
                        val config = SharedWallpaperRenderer.WallpaperConfig(
                            screenWidth, screenHeight, page1BackgroundColor, page1ImageHeightRatio,
                            currentPageOffset, pagesForConfig,
                            //p1OverlayFadeTransitionRatio = 0.5f, // P1 在前 50% 滑动距离内淡出
                            //p2BackgroundFadeInRatio = 0.5f,    // P2 在前 50% 滑动距离内淡入
                            scrollSensitivityFactor = this.currentScrollSensitivity
                        )
                        SharedWallpaperRenderer.drawFrame(canvas, config, currentWpBitmaps)
                    } else {
                        SharedWallpaperRenderer.drawPlaceholder(canvas, screenWidth, screenHeight,
                            if (imageUriString != null && currentBitmapLoadJob?.isActive == true) "壁纸加载中..."
                            else "请选择图片"
                        )
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "Error during drawFrame", e)
            } finally {
                if (canvas != null) {
                    try { surfaceHolder!!.unlockCanvasAndPost(canvas) }
                    catch (e: Exception) { Log.e(TAG, "Error unlocking canvas", e) }
                }
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            currentBitmapLoadJob?.cancel(); currentBitmapLoadJob = null
            engineScope.cancel()
            engineWallpaperBitmaps?.recycleInternals()
            engineWallpaperBitmaps = null
            Log.i(TAG, "H2WallpaperEngine destroyed.")
            hasLoadedInitialPreferencesOnCreate = false
        }
    }
}