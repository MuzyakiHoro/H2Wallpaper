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
        private const val BACKGROUND_BLUR_RADIUS = 25f
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

        // 从 SharedPreferences 加载的配置
        private var imageUriString: String? = null
        private var page1BackgroundColor: Int = Color.LTGRAY
        private var page1ImageHeightRatio: Float = 1f / 3f
        private val defaultHeightRatioEngine = 1f / 3f
        private var currentScrollSensitivity: Float = MainActivity.DEFAULT_SCROLL_SENSITIVITY // 新增

        // Launcher 报告的页面信息
        private var numPagesReportedByLauncher = 1
        private var currentPageOffset = 0f

        private val prefs: SharedPreferences = applicationContext.getSharedPreferences(
            MainActivity.PREFS_NAME, Context.MODE_PRIVATE
        )

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            this.surfaceHolder = surfaceHolder
            prefs.registerOnSharedPreferenceChangeListener(this)
            Log.i(TAG, "H2WallpaperEngine Created. IsPreview: ${isPreview}")
            loadAndApplyPreferences(triggerBitmapLoad = !isPreview)
        }

        private fun loadAndApplyPreferences(triggerBitmapLoad: Boolean = false, preferenceKeyChanged: String? = null) {
            val oldUri = imageUriString
            val oldRatio = page1ImageHeightRatio
            val oldColor = page1BackgroundColor
            val oldSensitivity = currentScrollSensitivity

            imageUriString = prefs.getString(MainActivity.KEY_IMAGE_URI, null)
            page1BackgroundColor = prefs.getInt(MainActivity.KEY_BACKGROUND_COLOR, Color.LTGRAY)
            page1ImageHeightRatio = prefs.getFloat(MainActivity.KEY_IMAGE_HEIGHT_RATIO, defaultHeightRatioEngine)
            currentScrollSensitivity = prefs.getFloat(MainActivity.KEY_SCROLL_SENSITIVITY, MainActivity.DEFAULT_SCROLL_SENSITIVITY) // 加载灵敏度

            Log.i(DEBUG_TAG, "Preferences loaded/reloaded: URI=$imageUriString, Color=$page1BackgroundColor, Ratio=$page1ImageHeightRatio, Sensitivity=$currentScrollSensitivity. TriggerLoad: $triggerBitmapLoad, KeyChanged: $preferenceKeyChanged")

            var needsRedraw = false
            var needsFullReload = false
            var needsTopBitmapUpdate = false

            if (triggerBitmapLoad) { // 通常在 onVisibilityChanged(true) 或 onCreate 时
                if (imageUriString != oldUri || (imageUriString != null && engineWallpaperBitmaps?.sourceSampledBitmap == null)) {
                    needsFullReload = true
                } else if (page1ImageHeightRatio != oldRatio && imageUriString != null) {
                    needsTopBitmapUpdate = true
                } else if (page1BackgroundColor != oldColor || currentScrollSensitivity != oldSensitivity) {
                    needsRedraw = true // 颜色或灵敏度变化只需要重绘
                } else if (engineWallpaperBitmaps != null) { // 其他情况如果位图存在也重绘
                    needsRedraw = true
                }
            } else if (preferenceKeyChanged != null) { // 由 onSharedPreferenceChanged 触发
                if (preferenceKeyChanged == MainActivity.KEY_IMAGE_URI && imageUriString != oldUri) {
                    needsFullReload = true
                } else if (preferenceKeyChanged == MainActivity.KEY_IMAGE_HEIGHT_RATIO && page1ImageHeightRatio != oldRatio && imageUriString != null) {
                    needsTopBitmapUpdate = true
                } else if (preferenceKeyChanged == MainActivity.KEY_BACKGROUND_COLOR && page1BackgroundColor != oldColor) {
                    needsRedraw = true
                } else if (preferenceKeyChanged == MainActivity.KEY_SCROLL_SENSITIVITY && currentScrollSensitivity != oldSensitivity) {
                    needsRedraw = true
                }
            }


            if (needsFullReload) {
                Log.i(DEBUG_TAG, "Prefs logic: Triggering full bitmap reload.")
                loadFullBitmapsAsync()
            } else if (needsTopBitmapUpdate) {
                Log.i(DEBUG_TAG, "Prefs logic: Triggering top bitmap update.")
                updateTopCroppedBitmapAsync()
            } else if (needsRedraw && isVisible && surfaceHolder?.surface?.isValid == true) {
                Log.i(DEBUG_TAG, "Prefs logic: Triggering redraw.")
                drawCurrentFrame()
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
                if (oldScreenWidth != width || oldScreenHeight != height || engineWallpaperBitmaps?.sourceSampledBitmap == null) {
                    Log.i(DEBUG_TAG, "SurfaceChanged: Dimensions changed or source bitmap missing, reloading bitmaps.")
                    loadFullBitmapsAsync()
                } else if (isVisible && this.surfaceHolder?.surface?.isValid == true) {
                    drawCurrentFrame()
                }
            } else {
                currentBitmapLoadJob?.cancel()
                engineWallpaperBitmaps?.recycleInternals()
                engineWallpaperBitmaps = null
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            isVisible = false
            currentBitmapLoadJob?.cancel(); currentBitmapLoadJob = null
            engineScope.cancel()
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            engineWallpaperBitmaps?.recycleInternals()
            engineWallpaperBitmaps = null
            Log.i(TAG, "H2WallpaperEngine Surface destroyed.")
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.isVisible = visible
            Log.i(TAG, "Visibility changed: $visible. IsPreview: ${isPreview}")
            if (visible) {
                loadAndApplyPreferences(triggerBitmapLoad = true)
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

            Log.i(DEBUG_TAG, "onOffsetsChanged: xOffset=$xOffset, xOffsetStepParam=$xOffsetStepParam -> calculatedStep=$currentXOffsetStep, reportedPages=$numPagesReportedByLauncher. IsPreview: ${isPreview}")

            if (!isPreview && oldNumPages != numPagesReportedByLauncher && numPagesReportedByLauncher > 0 && imageUriString != null) {
                Log.i(DEBUG_TAG, "Number of reported pages changed from $oldNumPages to $numPagesReportedByLauncher. Updating scrolling background (which might trigger full reload if source is also missing).")
                // 之前是 loadFullBitmapsAsync()，现在尝试只更新滚动背景
                updateScrollingBackgroundAsync()
            } else if (oldFrameworkOffset != xOffset) {
                if (isVisible && surfaceHolder?.surface?.isValid == true) {
                    drawCurrentFrame()
                }
            }
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            Log.i(TAG, "Preference changed via listener: '$key'")
            // 当SharedPreferences通过外部更改时（例如MainActivity保存后），此方法被调用
            // 我们在这里重新加载所有相关偏好，并根据变化的key决定下一步操作
            loadAndApplyPreferences(triggerBitmapLoad = false, preferenceKeyChanged = key)
        }


        private fun loadFullBitmapsAsync() {
            currentBitmapLoadJob?.cancel(); currentBitmapLoadJob = null

            val uriStringToLoad = imageUriString
            if (uriStringToLoad == null || screenWidth <= 0 || screenHeight <= 0) {
                Log.w(DEBUG_TAG, "loadFullBitmapsAsync: Preconditions not met. URI: $uriStringToLoad, Screen: ${screenWidth}x$screenHeight")
                engineWallpaperBitmaps?.recycleInternals()
                engineWallpaperBitmaps = null
                if (isVisible) drawCurrentFrame()
                return
            }
            val uri = Uri.parse(uriStringToLoad)
            Log.i(DEBUG_TAG, "loadFullBitmapsAsync: Starting for URI: $uri. IsPreview: ${isPreview}")

            val oldBitmaps = engineWallpaperBitmaps // 保存旧的引用，用于可能的平滑过渡或正确回收
            engineWallpaperBitmaps = null // 先置空，表示正在加载（或让旧的继续显示，取决于策略）
            // 为了体验，我们不在这里立即置空，而是在协程成功加载新位图后替换并回收旧的

            if (isVisible && oldBitmaps == null) { // 如果之前就没有位图，立即绘制占位符
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
                    Log.i(DEBUG_TAG, "loadFullBitmapsAsync (coroutine): Using $pagesForBackground pages for background generation. Sensitivity: $currentScrollSensitivity")

                    newBitmaps = withContext(Dispatchers.IO) {
                        ensureActive()
                        SharedWallpaperRenderer.loadAndProcessInitialBitmaps(
                            applicationContext, uri, screenWidth, screenHeight,
                            page1ImageHeightRatio, pagesForBackground,
                            BACKGROUND_BLUR_RADIUS
                        )
                    }
                    ensureActive()
                    if (imageUriString == uriStringToLoad) {
                        oldBitmaps?.recycleInternals() // 回收之前持有的旧位图
                        engineWallpaperBitmaps = newBitmaps
                        Log.i(DEBUG_TAG, "Full bitmaps loaded successfully for $uri. ScrollingBgWidth: ${newBitmaps?.scrollingBackgroundBitmap?.width}")
                    } else {
                        Log.w(DEBUG_TAG, "URI changed during full load for $uri. Discarding newly loaded bitmaps.")
                        newBitmaps?.recycleInternals()
                        if (imageUriString == null && engineWallpaperBitmaps != null && engineWallpaperBitmaps != oldBitmaps) { // 如果最新的是null，且当前持有的不是old，则回收
                            engineWallpaperBitmaps?.recycleInternals()
                            engineWallpaperBitmaps = null
                        } else if (imageUriString == null && oldBitmaps != null) { // 如果最新的是null，且之前有old，确保old也被清（如果它没被赋给engineWallpaperBitmaps）
                            // oldBitmaps 应该在 engineWallpaperBitmaps = newBitmaps 后被回收
                        }
                    }
                } catch (e: CancellationException) {
                    Log.d(DEBUG_TAG, "Full bitmap loading CANCELLED for $uri.")
                    newBitmaps?.recycleInternals()
                    if (oldBitmaps != null && engineWallpaperBitmaps == null) engineWallpaperBitmaps = oldBitmaps //如果被取消且当前是null，尝试恢复旧的
                } catch (e: Exception) {
                    Log.e(TAG, "Error in loadFullBitmapsAsync for $uri", e)
                    newBitmaps?.recycleInternals()
                    if (oldBitmaps != null && engineWallpaperBitmaps != oldBitmaps) oldBitmaps.recycleInternals() // 确保旧的也被回收
                    engineWallpaperBitmaps = null
                } finally {
                    if (coroutineContext[Job] == currentBitmapLoadJob) currentBitmapLoadJob = null
                    if (isVisible && (imageUriString == uriStringToLoad || imageUriString == null || engineWallpaperBitmaps != newBitmaps) ) {
                        drawCurrentFrame()
                    }
                }
            }
        }

        private fun updateTopCroppedBitmapAsync() {
            // ... (这个方法与上一个版本类似，确保它也正确处理 currentBitmapLoadJob) ...
            // 在调用这个方法前，确保 currentBitmapLoadJob 已被取消（如果正在进行其他加载）
            currentBitmapLoadJob?.cancel(); currentBitmapLoadJob = null

            val currentSourceBitmap = engineWallpaperBitmaps?.sourceSampledBitmap
            if (currentSourceBitmap == null || screenWidth <= 0 || screenHeight <= 0) {
                Log.w(DEBUG_TAG, "updateTopCroppedBitmapAsync: Source bitmap is null or screen dimensions invalid. Triggering full reload.")
                if (imageUriString != null) loadFullBitmapsAsync()
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
                    if (imageUriString != null && engineWallpaperBitmaps?.sourceSampledBitmap == currentSourceBitmap) {
                        oldTopCropped?.recycle()
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
                } finally {
                    if (coroutineContext[Job] == currentBitmapLoadJob) currentBitmapLoadJob = null
                    if (isVisible && imageUriString != null) {
                        drawCurrentFrame()
                    }
                }
            }
        }

        private fun updateScrollingBackgroundAsync() {
            currentBitmapLoadJob?.cancel(); currentBitmapLoadJob = null

            val currentSource = engineWallpaperBitmaps?.sourceSampledBitmap
            if (currentSource == null || imageUriString == null || screenWidth <= 0 || screenHeight <= 0) {
                Log.w(DEBUG_TAG, "updateScrollingBackgroundAsync: Cannot update, source bitmap is null or preconditions not met.")
                if (imageUriString != null) loadFullBitmapsAsync()
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
                    Log.i(DEBUG_TAG, "updateScrollingBackgroundAsync (coroutine): Using $pagesForBg pages for background generation. Sensitivity: $currentScrollSensitivity")

                    newScrollingPair = withContext(Dispatchers.IO) {
                        ensureActive()
                        // 注意：prepareScrollingAndBlurredBitmaps 需要 scrollFactor 参数，我们从 currentScrollSensitivity 获取
                        // 但 SharedWallpaperRenderer 的 prepareScrollingAndBlurredBitmaps 当前没有 scrollFactor 参数
                        // scrollFactor 是在 drawFrame 时应用的。所以这里不需要传。
                        SharedWallpaperRenderer.prepareScrollingAndBlurredBitmaps(
                            applicationContext, currentSource, screenWidth, screenHeight,
                            pagesForBg, BACKGROUND_BLUR_RADIUS
                        )
                    }
                    ensureActive()

                    if (imageUriString != null && engineWallpaperBitmaps?.sourceSampledBitmap == currentSource) {
                        oldScrollingBitmap?.recycle()
                        oldBlurredBitmap?.recycle()
                        engineWallpaperBitmaps?.scrollingBackgroundBitmap = newScrollingPair?.first
                        engineWallpaperBitmaps?.blurredScrollingBackgroundBitmap = newScrollingPair?.second
                        Log.i(DEBUG_TAG, "Scrolling background updated successfully. New width: ${newScrollingPair?.first?.width}")
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
                    if (coroutineContext[Job] == currentBitmapLoadJob) currentBitmapLoadJob = null
                    if (isVisible && imageUriString != null) {
                        drawCurrentFrame()
                    }
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
                            p1OverlayFadeTransitionRatio = 0.2f, // 您截图中的值
                            scrollSensitivityFactor = this.currentScrollSensitivity // 传入灵敏度
                        )
                        Log.d(DEBUG_TAG, "drawCurrentFrame - Config: offset=${config.currentXOffset}, pages=${config.numVirtualPages}, screenW=$screenWidth, sensitivity=${config.scrollSensitivityFactor}")
                        // ... (其他日志和 drawFrame 调用)
                        SharedWallpaperRenderer.drawFrame(canvas, config, currentWpBitmaps)

                    } else {
                        Log.d(DEBUG_TAG, "drawCurrentFrame: No sourceSampledBitmap, drawing placeholder.")
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
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            engineWallpaperBitmaps?.recycleInternals()
            engineWallpaperBitmaps = null
            Log.i(TAG, "H2WallpaperEngine destroyed.")
        }
    }
}