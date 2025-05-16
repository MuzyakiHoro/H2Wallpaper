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
        private const val DEBUG_TAG = "H2WallpaperSvc_Debug" // 新增专门的Debug TAG
        private const val BACKGROUND_BLUR_RADIUS = 25f
        private const val DEFAULT_VIRTUAL_PAGES_FOR_SCROLLING = 3 // 新增：用于无有效Launcher报告时的默认页面数
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

        private var imageUriString: String? = null
        private var page1BackgroundColor: Int = Color.LTGRAY
        private var page1ImageHeightRatio: Float = 1f / 3f
        private val defaultHeightRatioEngine = 1f / 3f

        private var numPagesReportedByLauncher = 1 // 初始化为1
        private var currentPageOffset = 0f

        private val prefs: SharedPreferences = applicationContext.getSharedPreferences(
            MainActivity.PREFS_NAME, Context.MODE_PRIVATE
        )

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            this.surfaceHolder = surfaceHolder
            prefs.registerOnSharedPreferenceChangeListener(this)
            Log.i(TAG, "H2WallpaperEngine Created. IsPreview: ${isPreview}")
            loadAndApplyPreferences(triggerBitmapLoad = !isPreview) // 如果不是预览模式，首次尝试加载
        }

        private fun loadAndApplyPreferences(triggerBitmapLoad: Boolean = false) {
            val oldUri = imageUriString
            val oldRatio = page1ImageHeightRatio
            val oldColor = page1BackgroundColor

            imageUriString = prefs.getString(MainActivity.KEY_IMAGE_URI, null)
            page1BackgroundColor = prefs.getInt(MainActivity.KEY_BACKGROUND_COLOR, Color.LTGRAY)
            page1ImageHeightRatio = prefs.getFloat(MainActivity.KEY_IMAGE_HEIGHT_RATIO, defaultHeightRatioEngine)

            Log.i(DEBUG_TAG, "Preferences loaded/reloaded: URI=$imageUriString, Color=$page1BackgroundColor, Ratio=$page1ImageHeightRatio. TriggerLoad: $triggerBitmapLoad")

            if (triggerBitmapLoad) {
                // 如果URI变了，或者URI没变但之前就没有加载过源图，或者屏幕尺寸也变了（这个在onSurfaceChanged处理）
                if (imageUriString != oldUri || (imageUriString != null && engineWallpaperBitmaps?.sourceSampledBitmap == null)) {
                    Log.i(DEBUG_TAG, "Prefs: URI changed or source bitmap missing, triggering full reload.")
                    loadFullBitmapsAsync()
                } else if (page1ImageHeightRatio != oldRatio && imageUriString != null) {
                    Log.i(DEBUG_TAG, "Prefs: Height ratio changed, triggering top bitmap update.")
                    updateTopCroppedBitmapAsync()
                } else if (page1BackgroundColor != oldColor && isVisible) {
                    Log.i(DEBUG_TAG, "Prefs: Background color changed, redrawing.")
                    drawCurrentFrame()
                } else if (isVisible && engineWallpaperBitmaps != null) {
                    Log.i(DEBUG_TAG, "Prefs: No major change but visible and bitmaps exist, redrawing.")
                    drawCurrentFrame()
                }
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
                // 只要尺寸变化，或者之前没有加载过有效的源图，都重新加载
                if (oldScreenWidth != width || oldScreenHeight != height || engineWallpaperBitmaps?.sourceSampledBitmap == null) {
                    Log.i(DEBUG_TAG, "SurfaceChanged: Dimensions changed or source bitmap missing, reloading bitmaps.")
                    loadFullBitmapsAsync()
                } else if (isVisible && this.surfaceHolder?.surface?.isValid == true) {
                    Log.i(DEBUG_TAG, "SurfaceChanged: Dimensions unchanged, visible and valid, redrawing.")
                    drawCurrentFrame()
                }
            } else {
                Log.w(TAG, "SurfaceChanged: Invalid screen dimensions $width x $height. Clearing bitmaps.")
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
                // 确保在可见时，如果配置已加载且必要，则启动位图加载
                loadAndApplyPreferences(triggerBitmapLoad = true)
            } else {
                // currentBitmapLoadJob?.cancel() // 可选：在不可见时取消加载
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

            // 如果报告的页面数从1变为大于1（或者反之，或者显著变化），并且我们不在预览模式，可能需要重新生成背景图
            if (!isPreview && oldNumPages != numPagesReportedByLauncher && numPagesReportedByLauncher > 0 && imageUriString != null) {
                Log.i(DEBUG_TAG, "Number of reported pages changed from $oldNumPages to $numPagesReportedByLauncher. Reloading full bitmaps.")
                loadFullBitmapsAsync() // 重新生成背景以适应新的页面数
            } else if (oldFrameworkOffset != xOffset) {
                if (isVisible && surfaceHolder?.surface?.isValid == true) {
                    drawCurrentFrame()
                }
            }
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            Log.i(TAG, "Preference changed: '$key'")
            val oldUri = imageUriString
            val oldRatio = page1ImageHeightRatio

            imageUriString = prefs.getString(MainActivity.KEY_IMAGE_URI, null)
            page1BackgroundColor = prefs.getInt(MainActivity.KEY_BACKGROUND_COLOR, Color.LTGRAY)
            page1ImageHeightRatio = prefs.getFloat(MainActivity.KEY_IMAGE_HEIGHT_RATIO, defaultHeightRatioEngine)
            Log.i(DEBUG_TAG, "onSharedPrefChanged: New URI=$imageUriString, Color=$page1BackgroundColor, Ratio=$page1ImageHeightRatio")

            if (key == MainActivity.KEY_IMAGE_URI) {
                if (imageUriString != oldUri) {
                    Log.i(DEBUG_TAG, "Prefs: Image URI changed, triggering full reload.")
                    loadFullBitmapsAsync()
                }
            } else if (key == MainActivity.KEY_IMAGE_HEIGHT_RATIO) {
                if (page1ImageHeightRatio != oldRatio && imageUriString != null) {
                    Log.i(DEBUG_TAG, "Prefs: Height ratio changed, triggering top bitmap update.")
                    updateTopCroppedBitmapAsync()
                } else if (isVisible) drawCurrentFrame()
            } else if (key == MainActivity.KEY_BACKGROUND_COLOR) {
                if (isVisible) drawCurrentFrame()
            }
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

            engineWallpaperBitmaps?.recycleInternals()
            engineWallpaperBitmaps = null
            if (isVisible) drawCurrentFrame() // 显示加载中

            currentBitmapLoadJob = engineScope.launch {
                var newBitmaps: SharedWallpaperRenderer.WallpaperBitmaps? = null
                try {
                    ensureActive()
                    // 决定用于滚动背景的页面数
                    val pagesForBackground = if (!isPreview && numPagesReportedByLauncher <= 1) {
                        Log.w(DEBUG_TAG, "Using DEFAULT_VIRTUAL_PAGES_FOR_SCROLLING ($DEFAULT_VIRTUAL_PAGES_FOR_SCROLLING) as numPagesReportedByLauncher is $numPagesReportedByLauncher and not in preview.")
                        DEFAULT_VIRTUAL_PAGES_FOR_SCROLLING
                    } else {
                        numPagesReportedByLauncher.coerceAtLeast(1)
                    }
                    Log.i(DEBUG_TAG, "loadFullBitmapsAsync: Using $pagesForBackground pages for background generation.")

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
                        engineWallpaperBitmaps = newBitmaps
                        Log.i(DEBUG_TAG, "Full bitmaps loaded successfully for $uri. ScrollingBgWidth: ${newBitmaps?.scrollingBackgroundBitmap?.width}, ScreenWidth: $screenWidth, PagesUsedForBg: $pagesForBackground")
                    } else {
                        Log.w(DEBUG_TAG, "URI changed during full load for $uri. Discarding.")
                        newBitmaps?.recycleInternals()
                        if (imageUriString == null && engineWallpaperBitmaps != null) {
                            engineWallpaperBitmaps?.recycleInternals(); engineWallpaperBitmaps = null
                        }
                    }
                } catch (e: CancellationException) {
                    Log.d(DEBUG_TAG, "Full bitmap loading CANCELLED for $uri.")
                    newBitmaps?.recycleInternals()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in loadFullBitmapsAsync for $uri", e)
                    newBitmaps?.recycleInternals()
                    engineWallpaperBitmaps = null
                } finally {
                    if (coroutineContext[Job] == currentBitmapLoadJob) currentBitmapLoadJob = null
                    if (isVisible && (imageUriString == uriStringToLoad || imageUriString == null) ) {
                        drawCurrentFrame()
                    }
                }
            }
        }

        private fun updateTopCroppedBitmapAsync() {
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
                        // 在传递给 Config 之前，再次确认 numPagesReportedByLauncher
                        val pagesForConfig = if (!isPreview && numPagesReportedByLauncher <= 1) {
                            DEFAULT_VIRTUAL_PAGES_FOR_SCROLLING
                        } else {
                            numPagesReportedByLauncher.coerceAtLeast(1)
                        }
                        val config = SharedWallpaperRenderer.WallpaperConfig(
                            screenWidth, screenHeight, page1BackgroundColor, page1ImageHeightRatio,
                            currentPageOffset, pagesForConfig, 0.2f
                        )
                        Log.d(DEBUG_TAG, "drawCurrentFrame - Config: offset=${config.currentXOffset}, pages=${config.numVirtualPages}, screenW=$screenWidth")
                        if (currentWpBitmaps.scrollingBackgroundBitmap == null) {
                            Log.w(DEBUG_TAG, "drawCurrentFrame: engineWallpaperBitmaps.scrollingBackgroundBitmap is NULL. Source exists: ${currentWpBitmaps.sourceSampledBitmap != null}")
                        } else {
                            Log.d(DEBUG_TAG, "drawCurrentFrame: ScrollingBG Width: ${currentWpBitmaps.scrollingBackgroundBitmap?.width}")
                        }

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