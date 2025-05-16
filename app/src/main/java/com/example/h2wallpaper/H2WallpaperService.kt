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
        private const val BACKGROUND_BLUR_RADIUS = 25f
    }

    override fun onCreateEngine(): Engine {
        return H2WallpaperEngine()
    }

    private inner class H2WallpaperEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private var surfaceHolder: SurfaceHolder? = null
        private var isVisible: Boolean = false
        private var screenWidth: Int = 0
        private var screenHeight: Int = 0

        // 持有 SharedWallpaperRenderer.WallpaperBitmaps 实例
        private var engineWallpaperBitmaps: SharedWallpaperRenderer.WallpaperBitmaps? = null
        private val engineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        private var currentBitmapLoadJob: Job? = null // 用于跟踪加载任务

        // 从 SharedPreferences 加载的配置
        private var imageUriString: String? = null
        private var page1BackgroundColor: Int = Color.LTGRAY
        private var page1ImageHeightRatio: Float = 1f / 3f
        private val defaultHeightRatioEngine = 1f / 3f

        // Launcher 报告的页面信息
        private var numPagesReportedByLauncher = 1
        private var currentPageOffset = 0f // Launcher 报告的 xOffset

        private val prefs: SharedPreferences = applicationContext.getSharedPreferences(
            MainActivity.PREFS_NAME, Context.MODE_PRIVATE
        )

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            this.surfaceHolder = surfaceHolder
            prefs.registerOnSharedPreferenceChangeListener(this)
            Log.d(TAG, "H2WallpaperEngine Created.")
            loadAndApplyPreferences() // 加载初始偏好
        }

        private fun loadAndApplyPreferences(triggerBitmapLoad: Boolean = false) {
            val oldUri = imageUriString
            val oldRatio = page1ImageHeightRatio
            val oldColor = page1BackgroundColor

            imageUriString = prefs.getString(MainActivity.KEY_IMAGE_URI, null)
            page1BackgroundColor = prefs.getInt(MainActivity.KEY_BACKGROUND_COLOR, Color.LTGRAY)
            page1ImageHeightRatio = prefs.getFloat(MainActivity.KEY_IMAGE_HEIGHT_RATIO, defaultHeightRatioEngine)

            Log.d(TAG, "Preferences loaded/reloaded: URI=$imageUriString, Color=$page1BackgroundColor, Ratio=$page1ImageHeightRatio")

            if (triggerBitmapLoad) {
                if (imageUriString != oldUri || (imageUriString != null && engineWallpaperBitmaps?.sourceSampledBitmap == null)) {
                    Log.d(TAG, "URI changed or source bitmap missing, triggering full reload.")
                    loadFullBitmapsAsync()
                } else if (page1ImageHeightRatio != oldRatio && imageUriString != null) {
                    Log.d(TAG, "Height ratio changed, triggering top bitmap update.")
                    updateTopCroppedBitmapAsync()
                } else if (page1BackgroundColor != oldColor && isVisible) {
                    Log.d(TAG, "Background color changed, redrawing.")
                    drawCurrentFrame()
                } else if (isVisible && engineWallpaperBitmaps != null) {
                    drawCurrentFrame() // 如果只是 visibility 变化且已有位图，也重绘
                }
            }
        }


        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            val oldScreenWidth = screenWidth
            val oldScreenHeight = screenHeight
            this.screenWidth = width
            this.screenHeight = height
            Log.d(TAG, "Surface changed: $width x $height. Old: ${oldScreenWidth}x${oldScreenHeight}")

            if (screenWidth > 0 && screenHeight > 0) {
                if (oldScreenWidth != width || oldScreenHeight != height || engineWallpaperBitmaps?.sourceSampledBitmap == null) {
                    Log.d(TAG, "Surface dimensions changed or source bitmap missing, reloading bitmaps.")
                    loadFullBitmapsAsync() // 尺寸变化，需要重新加载所有位图
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
            engineScope.cancel() // 取消所有 engineScope 的协程
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            engineWallpaperBitmaps?.recycleInternals()
            engineWallpaperBitmaps = null
            Log.d(TAG, "H2WallpaperEngine Surface destroyed.")
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.isVisible = visible
            Log.d(TAG, "Visibility changed: $visible")
            if (visible) {
                loadAndApplyPreferences(triggerBitmapLoad = true) // 可见时，确保所有状态最新并触发加载
            } else {
                // 可选：在不可见时取消正在进行的加载以节省资源
                // currentBitmapLoadJob?.cancel()
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float, xOffsetStepParam: Float,
            yOffsetStep: Float, xPixelOffset: Int, yPixelOffset: Int
        ) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStepParam, yOffsetStep, xPixelOffset, yPixelOffset)
            val oldFrameworkOffset = this.currentPageOffset
            this.currentPageOffset = xOffset // Launcher 报告的 xOffset

            // 根据 Launcher 报告的 xOffsetStep 计算实际页面数
            val currentXOffsetStep = if (xOffsetStepParam <= 0f || xOffsetStepParam >= 1f) 1.0f else xOffsetStepParam
            val reportedPages = if (currentXOffsetStep > 0.0001f && currentXOffsetStep < 1.0f) {
                (1f / currentXOffsetStep).roundToInt().coerceAtLeast(1)
            } else 1
            this.numPagesReportedByLauncher = reportedPages

            if (oldFrameworkOffset != xOffset) {
                if (isVisible && surfaceHolder?.surface?.isValid == true) {
                    drawCurrentFrame()
                }
            }
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            Log.d(TAG, "Preference changed: '$key', reloading relevant parts.")
            // 在这里，我们让 loadAndApplyPreferences 根据变化的 key 决定如何操作
            val oldUri = imageUriString
            val oldRatio = page1ImageHeightRatio

            // 先更新引擎内的配置变量
            imageUriString = prefs.getString(MainActivity.KEY_IMAGE_URI, null)
            page1BackgroundColor = prefs.getInt(MainActivity.KEY_BACKGROUND_COLOR, Color.LTGRAY)
            page1ImageHeightRatio = prefs.getFloat(MainActivity.KEY_IMAGE_HEIGHT_RATIO, defaultHeightRatioEngine)

            if (key == MainActivity.KEY_IMAGE_URI) {
                if (imageUriString != oldUri) { // 只有当URI实际改变时才重新加载
                    Log.d(TAG, "Image URI changed in prefs, triggering full reload.")
                    loadFullBitmapsAsync()
                }
            } else if (key == MainActivity.KEY_IMAGE_HEIGHT_RATIO) {
                if (page1ImageHeightRatio != oldRatio && imageUriString != null) {
                    Log.d(TAG, "Height ratio changed in prefs, triggering top bitmap update.")
                    updateTopCroppedBitmapAsync()
                } else if (isVisible) drawCurrentFrame() // 如果URI是null，ratio变化不影响位图，直接重绘
            } else if (key == MainActivity.KEY_BACKGROUND_COLOR) {
                if (isVisible) drawCurrentFrame() // 颜色变化，直接重绘
            }
        }


        private fun loadFullBitmapsAsync() {
            currentBitmapLoadJob?.cancel(); currentBitmapLoadJob = null // 取消任何正在进行的加载

            val uriStringToLoad = imageUriString
            if (uriStringToLoad == null || screenWidth <= 0 || screenHeight <= 0) {
                Log.w(TAG, "loadFullBitmapsAsync: Cannot load, URI is null or screen dimensions invalid.")
                engineWallpaperBitmaps?.recycleInternals()
                engineWallpaperBitmaps = null
                if (isVisible) drawCurrentFrame() // 尝试绘制占位符
                return
            }
            val uri = Uri.parse(uriStringToLoad)
            Log.d(TAG, "loadFullBitmapsAsync: Starting for URI: $uri")

            // 在启动新任务前，将当前位图置空，以触发占位符（如果加载需要时间）
            // 但这可能会导致不必要的闪烁，如果加载很快。
            // 考虑是否应该保留旧的，直到新的准备好（类似于 keepOldBitmapWhileLoading）
            // 为了与预览视图的行为更一致（切换图片时先显示加载），我们先置空
            engineWallpaperBitmaps?.recycleInternals()
            engineWallpaperBitmaps = null
            if (isVisible) drawCurrentFrame() // 显示加载中

            currentBitmapLoadJob = engineScope.launch {
                var newBitmaps: SharedWallpaperRenderer.WallpaperBitmaps? = null
                try {
                    ensureActive()
                    newBitmaps = withContext(Dispatchers.IO) {
                        ensureActive()
                        SharedWallpaperRenderer.loadAndProcessInitialBitmaps(
                            applicationContext, uri, screenWidth, screenHeight,
                            page1ImageHeightRatio, numPagesReportedByLauncher.coerceAtLeast(1),
                            BACKGROUND_BLUR_RADIUS
                        )
                    }
                    ensureActive()
                    if (imageUriString == uriStringToLoad) { // 检查URI是否在加载期间改变
                        engineWallpaperBitmaps = newBitmaps
                        Log.d(TAG, "Full bitmaps loaded successfully for $uri.")
                    } else {
                        Log.w(TAG, "URI changed during full load for $uri. Discarding.")
                        newBitmaps?.recycleInternals()
                        if (imageUriString == null && engineWallpaperBitmaps != null) { // 如果新的是null
                            engineWallpaperBitmaps?.recycleInternals()
                            engineWallpaperBitmaps = null
                        }
                    }
                } catch (e: CancellationException) {
                    Log.d(TAG, "Full bitmap loading CANCELLED for $uri.")
                    newBitmaps?.recycleInternals()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in loadFullBitmapsAsync for $uri", e)
                    newBitmaps?.recycleInternals()
                    engineWallpaperBitmaps = null // 出错时确保为null
                } finally {
                    if (coroutineContext[Job] == currentBitmapLoadJob) currentBitmapLoadJob = null
                    if (isVisible && (imageUriString == uriStringToLoad || imageUriString == null) ) {
                        drawCurrentFrame() // 最终重绘
                    }
                }
            }
        }

        private fun updateTopCroppedBitmapAsync() {
            currentBitmapLoadJob?.cancel(); currentBitmapLoadJob = null // 取消其他加载

            val currentSourceBitmap = engineWallpaperBitmaps?.sourceSampledBitmap
            if (currentSourceBitmap == null || screenWidth <= 0 || screenHeight <= 0) {
                Log.w(TAG, "updateTopCroppedBitmapAsync: Source bitmap is null or screen dimensions invalid. Triggering full reload.")
                // 如果源图不存在，说明状态有问题，尝试完整重新加载
                if (imageUriString != null) loadFullBitmapsAsync()
                else if(isVisible) drawCurrentFrame() // 没图就画占位符
                return
            }
            Log.d(TAG, "updateTopCroppedBitmapAsync: Starting for ratio $page1ImageHeightRatio")

            currentBitmapLoadJob = engineScope.launch {
                var newTopCropped: Bitmap? = null
                val oldTopCropped = engineWallpaperBitmaps?.page1TopCroppedBitmap // 保存旧的以便回收
                try {
                    ensureActive()
                    newTopCropped = withContext(Dispatchers.Default) { // CPU密集型
                        ensureActive()
                        SharedWallpaperRenderer.preparePage1TopCroppedBitmap(
                            currentSourceBitmap, screenWidth, screenHeight, page1ImageHeightRatio
                        )
                    }
                    ensureActive()
                    // 仅当 imageUriString 未变（表示源图仍然有效）且 engineWallpaperBitmaps 仍然是同一个对象时才更新
                    if (imageUriString != null && engineWallpaperBitmaps?.sourceSampledBitmap == currentSourceBitmap) {
                        oldTopCropped?.recycle() // 回收旧的顶图
                        engineWallpaperBitmaps?.page1TopCroppedBitmap = newTopCropped
                        Log.d(TAG, "Top cropped bitmap updated successfully.")
                    } else {
                        Log.w(TAG, "State changed during top bitmap update. Discarding new top bitmap.")
                        newTopCropped?.recycle()
                    }
                } catch (e: CancellationException) {
                    Log.d(TAG, "Top bitmap update CANCELLED.")
                    newTopCropped?.recycle()
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating top cropped bitmap", e)
                    newTopCropped?.recycle()
                    // 出错时，旧的顶图仍在 engineWallpaperBitmaps 中，或者如果它被回收了，这里可以置null
                    // engineWallpaperBitmaps?.page1TopCroppedBitmap = null
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
                    if (currentWpBitmaps?.sourceSampledBitmap != null) { // 只要源图存在，就尝试绘制
                        SharedWallpaperRenderer.drawFrame(
                            canvas,
                            SharedWallpaperRenderer.WallpaperConfig(
                                screenWidth, screenHeight, page1BackgroundColor, page1ImageHeightRatio,
                                currentPageOffset, numPagesReportedByLauncher.coerceAtLeast(1), 0.2f
                            ),
                            currentWpBitmaps
                        )
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
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            engineWallpaperBitmaps?.recycleInternals()
            engineWallpaperBitmaps = null
            Log.d(TAG, "H2WallpaperEngine destroyed.")
        }
    }
}