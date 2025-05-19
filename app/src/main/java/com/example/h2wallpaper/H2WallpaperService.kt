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

    private inner class H2WallpaperEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener { // 实现接口

        private var surfaceHolder: SurfaceHolder? = null
        private var isVisible: Boolean = false
        private var screenWidth: Int = 0
        private var screenHeight: Int = 0

        private var engineWallpaperBitmaps: SharedWallpaperRenderer.WallpaperBitmaps? = null
        private val engineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        private var currentBitmapLoadJob: Job? = null // 可以考虑分离成 p1TopBitmapLoadJob 和 fullBitmapLoadJob

        private var imageUriString: String? = null
        private var page1BackgroundColor: Int = Color.LTGRAY
        private var page1ImageHeightRatio: Float = 1f / 3f
        private val defaultHeightRatioEngine = 1f / 3f
        private var currentScrollSensitivity: Float = MainActivity.DEFAULT_SCROLL_SENSITIVITY
        private var currentP1FocusX: Float = 0.5f
        private var currentP1FocusY: Float = 0.5f

        private var numPagesReportedByLauncher = 1
        private var currentPageOffset = 0f

        private val prefs: SharedPreferences by lazy {
            applicationContext.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            this.surfaceHolder = surfaceHolder
            Log.i(TAG, "H2WallpaperEngine Created. IsPreview: ${isPreview}")

            prefs.registerOnSharedPreferenceChangeListener(this) // 注册监听器
            loadPreferencesFromStorage() // 初始加载

            if (screenWidth > 0 && screenHeight > 0 && imageUriString != null) {
                Log.i(DEBUG_TAG, "onCreate: Triggering initial bitmap load if needed.")
                loadFullBitmapsAsyncIfNeeded()
            } else {
                Log.i(DEBUG_TAG, "onCreate: Conditions for initial load not met.")
            }
        }

        // 当SharedPreferences改变时回调
        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
            Log.i(DEBUG_TAG, "onSharedPreferenceChanged: key=$key")
            var needsFullReload = false
            var needsP1TopUpdate = false
            var needsScrollingBgUpdate = false // 虽然目前没有单独更新它的逻辑，但可以预留

            // 先加载所有可能变化的参数到成员变量
            val oldImageUri = imageUriString
            val oldP1FocusX = currentP1FocusX
            val oldP1FocusY = currentP1FocusY
            val oldPage1ImageHeightRatio = page1ImageHeightRatio
            // ... 其他可能变化的参数

            loadPreferencesFromStorage() // 加载最新的所有参数

            when (key) {
                MainActivity.KEY_IMAGE_URI -> {
                    if (oldImageUri != imageUriString) { // 比较新旧值，确认是否真的变了
                        Log.i(DEBUG_TAG, "Preference changed: Image URI. Triggering full reload.")
                        needsFullReload = true
                    }
                }
                MainActivity.KEY_P1_FOCUS_X, MainActivity.KEY_P1_FOCUS_Y -> {
                    if (oldP1FocusX != currentP1FocusX || oldP1FocusY != currentP1FocusY) {
                        Log.i(DEBUG_TAG, "Preference changed: P1 Focus. Triggering P1 top update.")
                        needsP1TopUpdate = true
                    }
                }
                MainActivity.KEY_IMAGE_HEIGHT_RATIO -> {
                    if (oldPage1ImageHeightRatio != page1ImageHeightRatio) {
                        Log.i(DEBUG_TAG, "Preference changed: P1 Height Ratio. Triggering P1 top update.")
                        needsP1TopUpdate = true
                    }
                }
                MainActivity.KEY_BACKGROUND_COLOR, MainActivity.KEY_SCROLL_SENSITIVITY -> {
                    // 这些参数的改变只需要重绘，不需要重新加载位图
                    Log.i(DEBUG_TAG, "Preference changed: $key. Triggering redraw.")
                    if (isVisible) drawCurrentFrame()
                }
                // 可以为其他参数添加case
            }

            if (needsFullReload) {
                loadFullBitmapsAsyncIfNeeded() // 会处理URI为null的情况
            } else if (needsP1TopUpdate) {
                // 确保 updateTopCroppedBitmapAsync 使用的是最新的焦点和高度比例
                if (engineWallpaperBitmaps?.sourceSampledBitmap != null && imageUriString != null) {
                    updateTopCroppedBitmapAsync()
                } else if (imageUriString != null) { // 如果有URI但没有源图，则需要完整加载
                    Log.w(DEBUG_TAG, "P1 top update requested, but sourceSampledBitmap is null. Forcing full reload.")
                    loadFullBitmapsAsyncIfNeeded()
                }
            }
        }


        private fun loadPreferencesFromStorage() {
            imageUriString = prefs.getString(MainActivity.KEY_IMAGE_URI, null)
            page1BackgroundColor = prefs.getInt(MainActivity.KEY_BACKGROUND_COLOR, Color.LTGRAY)
            page1ImageHeightRatio = prefs.getFloat(MainActivity.KEY_IMAGE_HEIGHT_RATIO, defaultHeightRatioEngine)
            currentScrollSensitivity = prefs.getFloat(MainActivity.KEY_SCROLL_SENSITIVITY, MainActivity.DEFAULT_SCROLL_SENSITIVITY)
            currentP1FocusX = prefs.getFloat(MainActivity.KEY_P1_FOCUS_X, 0.5f)
            currentP1FocusY = prefs.getFloat(MainActivity.KEY_P1_FOCUS_Y, 0.5f)
            Log.i(DEBUG_TAG, "Preferences loaded/reloaded from storage: URI=$imageUriString, Color=$page1BackgroundColor, Ratio=$page1ImageHeightRatio, Sensitivity=$currentScrollSensitivity, Focus=($currentP1FocusX, $currentP1FocusY)")
        }

        private fun loadFullBitmapsAsyncIfNeeded() {
            if (imageUriString != null && screenWidth > 0 && screenHeight > 0) {
                if (currentBitmapLoadJob == null || !currentBitmapLoadJob!!.isActive || engineWallpaperBitmaps?.sourceSampledBitmap == null) {
                    Log.i(DEBUG_TAG, "loadFullBitmapsAsyncIfNeeded: Conditions met, calling loadFullBitmapsAsync.")
                    loadFullBitmapsAsync()
                } else {
                    Log.i(DEBUG_TAG, "loadFullBitmapsAsyncIfNeeded: Bitmaps seem to be loaded or loading for current URI, might need redraw or specific update.")
                    // 如果只是参数变化，比如颜色，可能只需要重绘
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
                // loadPreferencesFromStorage() // onSharedPreferenceChanged 会处理参数更新，但这里可以保留以防万一
                loadFullBitmapsAsyncIfNeeded()
            } else {
                currentBitmapLoadJob?.cancel(); currentBitmapLoadJob = null
                engineWallpaperBitmaps?.recycleInternals()
                engineWallpaperBitmaps = null
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            isVisible = false
            // currentBitmapLoadJob?.cancel(); currentBitmapLoadJob = null // engineScope.cancel() 会处理
            // engineScope.cancel() // 移到 onDestroy
            // engineWallpaperBitmaps?.recycleInternals(); engineWallpaperBitmaps = null // 移到 onDestroy
            Log.i(TAG, "H2WallpaperEngine Surface destroyed.")
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.isVisible = visible
            Log.i(TAG, "Visibility changed: $visible. IsPreview: ${isPreview}")
            if (visible) {
                // 当变为可见时，确保配置是最新的，并且位图已加载
                // loadPreferencesFromStorage() // 确保参数最新（尽管listener应该处理了）
                // 检查当前位图是否有效，如果无效（例如服务在后台被回收后重启），则重新加载
                if (engineWallpaperBitmaps?.sourceSampledBitmap == null && imageUriString != null && screenWidth > 0 && screenHeight > 0) {
                    Log.i(DEBUG_TAG, "VisibilityChanged (visible=true): Source bitmap missing. Trying to load based on current engine URI: $imageUriString")
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

            val currentXOffsetStep = if (xOffsetStepParam <= 0f || xOffsetStepParam >= 1f) 1.0f else xOffsetStepParam
            val reportedPages = if (currentXOffsetStep > 0.0001f && currentXOffsetStep < 1.0f) {
                (1f / currentXOffsetStep).roundToInt().coerceAtLeast(1)
            } else 1
            val oldNumPages = numPagesReportedByLauncher
            this.numPagesReportedByLauncher = reportedPages


            if (!isPreview && oldNumPages != this.numPagesReportedByLauncher && this.numPagesReportedByLauncher > 0 && imageUriString != null) {
                if (engineWallpaperBitmaps?.sourceSampledBitmap != null) {
                    Log.i(DEBUG_TAG, "Number of reported pages changed from $oldNumPages to ${this.numPagesReportedByLauncher}. Updating scrolling background.")
                    updateScrollingBackgroundAsync() // 这个方法也需要确保使用最新的参数
                } else {
                    Log.i(DEBUG_TAG, "Number of reported pages changed, but no source bitmap. Triggering full reload.")
                    loadFullBitmapsAsyncIfNeeded()
                }
            } else if (oldFrameworkOffset != xOffset) {
                if (isVisible && surfaceHolder?.surface?.isValid == true) {
                    drawCurrentFrame()
                }
            }
        }


        private fun loadFullBitmapsAsync() {
            currentBitmapLoadJob?.cancel(); // 取消任何正在进行的加载
            // 重置为null，确保即使URI相同但内容可能由于其他参数变化（如焦点）而需要重新加载时，也会执行
            engineWallpaperBitmaps?.recycleInternals()
            engineWallpaperBitmaps = null

            val uriStringToLoad = imageUriString
            if (uriStringToLoad == null || screenWidth <= 0 || screenHeight <= 0) {
                Log.w(DEBUG_TAG, "loadFullBitmapsAsync: Preconditions not met. URI: $uriStringToLoad, Screen: ${screenWidth}x$screenHeight")
                if (isVisible) drawCurrentFrame() // 绘制占位符
                return
            }
            val uri = Uri.parse(uriStringToLoad)
            Log.i(DEBUG_TAG, "loadFullBitmapsAsync: Starting for URI: $uri. Focus:($currentP1FocusX, $currentP1FocusY)")

            if (isVisible) { // 如果可见，立即画一次占位符，因为旧位图已清除
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
                    Log.i(DEBUG_TAG, "loadFullBitmapsAsync (coroutine): Using $pagesForBackground pages for background. Sensitivity: $currentScrollSensitivity")

                    newBitmapsHolder = withContext(Dispatchers.IO) {
                        ensureActive()
                        SharedWallpaperRenderer.loadAndProcessInitialBitmaps(
                            context = applicationContext,
                            imageUri = uri,
                            targetScreenWidth = screenWidth,
                            targetScreenHeight = screenHeight,
                            page1ImageHeightRatio = page1ImageHeightRatio, // 使用当前最新的值
                            normalizedFocusX = currentP1FocusX,       // 使用当前最新的值
                            normalizedFocusY = currentP1FocusY,       // 使用当前最新的值
                            numVirtualPagesForScrolling = pagesForBackground,
                            blurRadiusForBackground = SharedWallpaperRenderer.DEFAULT_BACKGROUND_BLUR_RADIUS
                        )
                    }
                    ensureActive()

                    if (this@H2WallpaperEngine.imageUriString == uriStringToLoad) { // 检查URI在加载期间是否改变
                        engineWallpaperBitmaps = newBitmapsHolder // 直接赋值，旧的已在开始时回收
                        Log.i(DEBUG_TAG, "Full bitmaps loaded successfully for $uri.")
                    } else {
                        Log.w(DEBUG_TAG, "URI changed during full load for $uri. Current engine URI: ${this@H2WallpaperEngine.imageUriString}. Discarding newly loaded bitmaps.")
                        newBitmapsHolder?.recycleInternals()
                        // 如果当前URI已变为null，也确保 engineWallpaperBitmaps 为 null
                        if (this@H2WallpaperEngine.imageUriString == null) {
                            engineWallpaperBitmaps = null
                        }
                    }
                } catch (e: CancellationException) {
                    Log.d(DEBUG_TAG, "Full bitmap loading CANCELLED for $uri.")
                    newBitmapsHolder?.recycleInternals()
                    // 如果取消，并且 engineWallpaperBitmaps 为 null (因为开始时被清除了)，保持为 null
                } catch (e: Exception) {
                    Log.e(TAG, "Error in loadFullBitmapsAsync for $uri", e)
                    newBitmapsHolder?.recycleInternals()
                    engineWallpaperBitmaps = null // 出错则清除
                } finally {
                    if (coroutineContext[Job] == currentBitmapLoadJob) currentBitmapLoadJob = null
                    if (isVisible && (this@H2WallpaperEngine.imageUriString == uriStringToLoad || this@H2WallpaperEngine.imageUriString == null)) {
                        drawCurrentFrame()
                    }
                }
            }
        }

        private fun updateTopCroppedBitmapAsync() {
            // 如果已有全图加载任务，可能需要取消或等待它完成，或者用单独的job
            // currentBitmapLoadJob?.cancel(); // 谨慎取消，可能正在加载源图

            val currentSourceBitmap = engineWallpaperBitmaps?.sourceSampledBitmap
            if (currentSourceBitmap == null || screenWidth <= 0 || screenHeight <= 0 || imageUriString == null) {
                Log.w(DEBUG_TAG, "updateTopCroppedBitmapAsync: Source bitmap is null or screen dimensions invalid or no URI. Triggering full reload if URI exists.")
                if (imageUriString != null) loadFullBitmapsAsyncIfNeeded()
                else if(isVisible) drawCurrentFrame() // 画占位符
                return
            }
            Log.i(DEBUG_TAG, "updateTopCroppedBitmapAsync: Starting for ratio $page1ImageHeightRatio, Focus($currentP1FocusX, $currentP1FocusY)")

            // 可以为此操作使用一个单独的 Job，以避免与 fullBitmapLoadJob 冲突
            val p1TopUpdateJob = engineScope.launch { // 使用新的 Job 或确保 currentBitmapLoadJob 已完成/被取消
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
                            page1ImageHeightRatio, // 使用最新的高度比例
                            currentP1FocusX,       // 使用最新的焦点X
                            currentP1FocusY        // 使用最新的焦点Y
                        )
                    }
                    ensureActive()
                    // 再次检查状态，确保在异步操作期间源图和URI没有改变
                    if (this@H2WallpaperEngine.imageUriString != null && engineWallpaperBitmaps?.sourceSampledBitmap == currentSourceBitmap) {
                        if (oldTopCropped != newTopCropped) oldTopCropped?.recycle()
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
                        engineWallpaperBitmaps?.page1TopCroppedBitmap = null // 出错时清除，以便重绘时显示占位
                    }
                } finally {
                    // 不需要管理 currentBitmapLoadJob，因为这是个独立的 p1TopUpdateJob
                    if (isVisible && this@H2WallpaperEngine.imageUriString != null) drawCurrentFrame()
                }
            }
        }

        // updateScrollingBackgroundAsync 也应该使用最新的参数，但它目前只在 numPagesReportedByLauncher 变化时调用
        private fun updateScrollingBackgroundAsync() {
            // ... (类似 updateTopCroppedBitmapAsync, 确保它使用最新的 numPagesReportedByLauncher 等) ...
            // currentBitmapLoadJob?.cancel() // 同样，谨慎处理

            val currentSource = engineWallpaperBitmaps?.sourceSampledBitmap
            if (currentSource == null || imageUriString == null || screenWidth <= 0 || screenHeight <= 0) {
                Log.w(DEBUG_TAG, "updateScrollingBackgroundAsync: Cannot update, source bitmap is null or preconditions not met.")
                if (imageUriString != null) loadFullBitmapsAsyncIfNeeded()
                else if(isVisible) drawCurrentFrame()
                return
            }
            val pagesForBg = if (!isPreview && numPagesReportedByLauncher <= 1) {
                DEFAULT_VIRTUAL_PAGES_FOR_SCROLLING
            } else {
                numPagesReportedByLauncher.coerceAtLeast(1)
            }
            Log.i(DEBUG_TAG, "updateScrollingBackgroundAsync: Starting for numPages $pagesForBg")

            val scrollingUpdateJob = engineScope.launch {
                var newScrollingPair: Pair<Bitmap?, Bitmap?>? = null
                val oldScrollingBitmap = engineWallpaperBitmaps?.scrollingBackgroundBitmap
                val oldBlurredBitmap = engineWallpaperBitmaps?.blurredScrollingBackgroundBitmap
                try {
                    ensureActive()
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
                    if (isVisible && this@H2WallpaperEngine.imageUriString != null) drawCurrentFrame()
                }
            }
        }


        private fun drawCurrentFrame() {
            if (!isVisible || surfaceHolder?.surface?.isValid != true || screenWidth == 0 || screenHeight == 0) {
                // Log.d(DEBUG_TAG, "drawCurrentFrame: Skipped. Visible:$isVisible, SurfaceValid:${surfaceHolder?.surface?.isValid}, SW:$screenWidth, SH:$screenHeight")
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
                    // 确保 currentP1FocusX, currentP1FocusY, page1ImageHeightRatio是最新的
                    // WallpaperConfig 将使用这些最新的成员变量
                    if (currentWpBitmaps?.sourceSampledBitmap != null && currentWpBitmaps.page1TopCroppedBitmap != null) { // 增加对page1TopCroppedBitmap的检查
                        val pagesForConfig = if (!isPreview && numPagesReportedByLauncher <= 1) {
                            DEFAULT_VIRTUAL_PAGES_FOR_SCROLLING
                        } else {
                            numPagesReportedByLauncher.coerceAtLeast(1)
                        }
                        val config = SharedWallpaperRenderer.WallpaperConfig(
                            screenWidth, screenHeight, page1BackgroundColor, page1ImageHeightRatio,
                            currentPageOffset, pagesForConfig,
                            scrollSensitivityFactor = this.currentScrollSensitivity
                        )
                        SharedWallpaperRenderer.drawFrame(canvas, config, currentWpBitmaps)
                    } else {
                        SharedWallpaperRenderer.drawPlaceholder(canvas, screenWidth, screenHeight,
                            if (imageUriString != null && (currentBitmapLoadJob?.isActive == true /* || p1TopUpdateJob?.isActive == true */)) "壁纸加载中..." // 需要考虑多个job
                            else if (imageUriString == null) "请选择图片"
                            else "图片资源准备中..." // 如果有URI但位图不完整
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
            prefs.unregisterOnSharedPreferenceChangeListener(this) // 注销监听器
            engineScope.cancel() // 取消所有协程
            engineWallpaperBitmaps?.recycleInternals()
            engineWallpaperBitmaps = null
            Log.i(TAG, "H2WallpaperEngine destroyed.")
        }
    }
}