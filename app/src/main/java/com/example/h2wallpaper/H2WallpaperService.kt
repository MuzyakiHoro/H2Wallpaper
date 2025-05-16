package com.example.h2wallpaper

import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.net.Uri
import android.os.Build
// import android.os.Handler // 未使用，可以移除
// import android.os.Looper // 未使用，可以移除
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
// RenderScript imports 仍然需要，因为 SharedWallpaperRenderer 内部使用了它
// import android.renderscript.Allocation // 如果 SharedWallpaperRenderer 完全封装了RenderScript则不需要在这里导入
// import android.renderscript.Element
// import android.renderscript.RenderScript
// import android.renderscript.ScriptIntrinsicBlur
import kotlinx.coroutines.* // 引入协程
import kotlin.math.roundToInt

class H2WallpaperService : WallpaperService() {

    companion object {
        private const val TAG = "H2WallpaperSvc"
        // 这些常量现在由 SharedWallpaperRenderer.WallpaperConfig 的默认值或参数控制
        // private const val NUM_VIRTUAL_SCROLL_PAGES = 3
        // private const val P1_OVERLAY_FADE_TRANSITION_RATIO = 0.2f
        private const val BACKGROUND_BLUR_RADIUS = 25f // 这个可以作为参数传给 prepareAllBitmaps
    }

    override fun onCreateEngine(): Engine {
        return H2WallpaperEngine()
    }

    private inner class H2WallpaperEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        // private val handler = Handler(Looper.getMainLooper()) // 不再需要
        private var surfaceHolder: SurfaceHolder? = null
        private var isVisible: Boolean = false
        private var screenWidth: Int = 0
        private var screenHeight: Int = 0

        // --- 新的成员变量，用于存储通过 SharedWallpaperRenderer 准备的位图 ---
        private var engineWallpaperBitmaps: SharedWallpaperRenderer.WallpaperBitmaps? = null
        private val engineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()) // 用于加载位图

        // --- 存储从 SharedPreferences 读取的配置 ---
        private var imageUriString: String? = null // 存储 URI 字符串
        private var page1BackgroundColor: Int = Color.LTGRAY
        private var page1ImageHeightRatio: Float = 1f / 3f
        private val defaultHeightRatioEngine = 1f / 3f // 引擎内的默认值

        // --- Paint 对象现在由 SharedWallpaperRenderer 管理，这里不再需要 ---
        // private val scrollingBgPaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
        // ... 其他 Paint 对象 ...

        private var numPagesReportedByLauncher = 1 // 由 Launcher 报告的页面数
        private var currentXOffsetStep = 1.0f      // 由 Launcher 报告的每页偏移步长
        private var currentPageOffset = 0f         // 由 Launcher 报告的当前总偏移 (0.0 - 1.0)


        private val prefs: SharedPreferences = applicationContext.getSharedPreferences(
            MainActivity.PREFS_NAME, Context.MODE_PRIVATE
        )

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            this.surfaceHolder = surfaceHolder
            prefs.registerOnSharedPreferenceChangeListener(this)
            Log.d(TAG, "H2WallpaperEngine Created.")
            // 初始加载偏好设置，但不立即准备位图，等待 surfaceC hanged 或 visibilityChanged
            loadPreferencesOnly()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            val oldScreenWidth = screenWidth
            val oldScreenHeight = screenHeight
            this.screenWidth = width
            this.screenHeight = height
            Log.d(TAG, "Surface changed: $width x $height. Old: ${oldScreenWidth}x${oldScreenHeight}")

            // 只有当屏幕尺寸有效且发生变化，或者位图尚未准备好时，才重新准备位图
            if (screenWidth > 0 && screenHeight > 0) {
                if (oldScreenWidth != width || oldScreenHeight != height || engineWallpaperBitmaps == null) {
                    Log.d(TAG, "Dimensions changed or bitmaps need (re)preparation.")
                    loadAndPrepareWallpaperBitmapsAsync()
                } else if (isVisible && this.surfaceHolder?.surface?.isValid == true) {
                    Log.d(TAG, "Dimensions unchanged, redrawing.")
                    drawCurrentFrame()
                }
            } else {
                // 屏幕尺寸无效，清理位图
                SharedWallpaperRenderer.recycleBitmaps(engineWallpaperBitmaps)
                engineWallpaperBitmaps = null
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            isVisible = false
            engineScope.cancel() // 取消所有协程
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            SharedWallpaperRenderer.recycleBitmaps(engineWallpaperBitmaps)
            engineWallpaperBitmaps = null
            Log.d(TAG, "H2WallpaperEngine Surface destroyed.")
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.isVisible = visible
            Log.d(TAG, "Visibility changed: $visible")
            if (visible) {
                // 确保偏好是最新的
                loadPreferencesOnly() // 重新加载偏好，以防在不可见时发生变化
                if (screenWidth > 0 && screenHeight > 0) {
                    if (engineWallpaperBitmaps == null) { // 如果位图还未加载
                        Log.d(TAG, "Visible but bitmaps not ready, preparing.")
                        loadAndPrepareWallpaperBitmapsAsync()
                    } else if (surfaceHolder?.surface?.isValid == true) {
                        Log.d(TAG, "Visible and ready, drawing frame.")
                        drawCurrentFrame()
                    }
                }
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float,
            xOffsetStepParam: Float, yOffsetStep: Float,
            xPixelOffset: Int, yPixelOffset: Int
        ) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStepParam, yOffsetStep, xPixelOffset, yPixelOffset)

            val oldOffset = this.currentPageOffset
            this.currentPageOffset = xOffset // Launcher 报告的 xOffset 通常是 0 到 1 之间的值，代表当前屏幕中心点在总宽度上的位置
            this.currentXOffsetStep = if (xOffsetStepParam <= 0f || xOffsetStepParam >= 1f) 1.0f else xOffsetStepParam

            // 根据 Launcher 报告的 xOffsetStep 计算实际页面数
            // 例如，如果 xOffsetStep 是 0.25，表示有 1/0.25 = 4 页 (索引 0, 1, 2, 3)
            // 但要注意，有些 Launcher 可能报告的 xOffsetStep 不准确，或页面数与 (1/xOffsetStep) 不完全对应
            val reportedPages = if (this.currentXOffsetStep > 0.0001f && this.currentXOffsetStep < 1.0f) {
                (1f / this.currentXOffsetStep).roundToInt().coerceAtLeast(1)
            } else {
                1 // 如果 xOffsetStep 无效或为1，则认为只有1页
            }
            this.numPagesReportedByLauncher = reportedPages
            // Log.d(TAG, "OffsetsChanged: xOffset=$xOffset, xOffsetStep=${this.currentXOffsetStep}, reportedPages=$numPagesReportedByLauncher")


            if (oldOffset != xOffset) {
                if (isVisible && surfaceHolder?.surface?.isValid == true) {
                    drawCurrentFrame()
                }
            }
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            Log.d(TAG, "Preference changed: '$key'")
            // 重新加载影响视觉的偏好
            loadPreferencesOnly()

            if (key == MainActivity.KEY_IMAGE_URI) {
                // 图片 URI 变化，需要重新加载和准备所有位图
                SharedWallpaperRenderer.recycleBitmaps(engineWallpaperBitmaps) // 先回收旧的
                engineWallpaperBitmaps = null
                if (screenWidth > 0 && screenHeight > 0) { // 仅当有有效尺寸时才加载
                    loadAndPrepareWallpaperBitmapsAsync()
                }
            } else if (key == MainActivity.KEY_BACKGROUND_COLOR || key == MainActivity.KEY_IMAGE_HEIGHT_RATIO) {
                // 颜色或高度比例变化
                if (key == MainActivity.KEY_IMAGE_HEIGHT_RATIO) {
                    // 高度比例变化影响 page1TopCroppedBitmap，需要重新准备位图
                    SharedWallpaperRenderer.recycleBitmaps(engineWallpaperBitmaps)
                    engineWallpaperBitmaps = null
                    if (screenWidth > 0 && screenHeight > 0) {
                        loadAndPrepareWallpaperBitmapsAsync()
                    }
                } else {
                    // 仅背景颜色变化，不需要重新准备位图，直接重绘即可
                    if (isVisible && surfaceHolder?.surface?.isValid == true) {
                        drawCurrentFrame()
                    }
                }
            }
        }

        private fun loadPreferencesOnly() {
            imageUriString = prefs.getString(MainActivity.KEY_IMAGE_URI, null)
            page1BackgroundColor = prefs.getInt(MainActivity.KEY_BACKGROUND_COLOR, Color.LTGRAY)
            page1ImageHeightRatio = prefs.getFloat(MainActivity.KEY_IMAGE_HEIGHT_RATIO, defaultHeightRatioEngine)
            Log.d(TAG, "Preferences loaded: URI=$imageUriString, Color=$page1BackgroundColor, Ratio=$page1ImageHeightRatio")
        }

        private fun loadAndPrepareWallpaperBitmapsAsync() {
            if (screenWidth <= 0 || screenHeight <= 0) {
                Log.w(TAG, "Cannot prepare bitmaps, screen dimensions are invalid.")
                SharedWallpaperRenderer.recycleBitmaps(engineWallpaperBitmaps)
                engineWallpaperBitmaps = null
                if(isVisible) drawCurrentFrame() // 尝试绘制占位符
                return
            }
            val currentImageUriString = imageUriString // 从成员变量获取最新的URI
            if (currentImageUriString == null) {
                Log.w(TAG, "No image URI set, clearing bitmaps.")
                SharedWallpaperRenderer.recycleBitmaps(engineWallpaperBitmaps)
                engineWallpaperBitmaps = null
                if(isVisible) drawCurrentFrame() // 绘制占位符
                return
            }

            val uriToLoad = Uri.parse(currentImageUriString)

            // 显示加载状态 (可选：立即绘制一个加载中的占位符)
            // SharedWallpaperRenderer.recycleBitmaps(engineWallpaperBitmaps) // 在协程开始前回收
            // engineWallpaperBitmaps = null
            // if(isVisible) drawCurrentFrame() // 绘制占位符

            engineScope.launch {
                Log.d(TAG, "Starting async bitmap preparation for URI: $uriToLoad")
                // 先回收旧位图
                SharedWallpaperRenderer.recycleBitmaps(engineWallpaperBitmaps)
                engineWallpaperBitmaps = null

                val preparedBitmaps = withContext(Dispatchers.IO) {
                    SharedWallpaperRenderer.prepareAllBitmaps(
                        applicationContext, // 使用 applicationContext
                        uriToLoad,
                        screenWidth,
                        screenHeight,
                        page1ImageHeightRatio,
                        numPagesReportedByLauncher.coerceAtLeast(1), // 使用Launcher报告的页数，至少为1
                        BACKGROUND_BLUR_RADIUS
                    )
                }

                // 确保在协程执行期间，用户没有更改设置导致 URI 不同
                if (imageUriString == currentImageUriString) {
                    engineWallpaperBitmaps = preparedBitmaps
                    Log.d(TAG, "Async bitmaps prepared successfully.")
                } else {
                    Log.d(TAG, "Image URI changed during async preparation, discarding results.")
                    SharedWallpaperRenderer.recycleBitmaps(preparedBitmaps) // 回收已加载但过时的位图
                }

                if (isVisible && surfaceHolder?.surface?.isValid == true) {
                    drawCurrentFrame() // 无论成功与否都尝试重绘
                }
            }
        }


        // 移除旧的 prepareDerivedBitmapsInternal 和 calculateInSampleSize，因为逻辑已移至 SharedWallpaperRenderer

        private fun drawCurrentFrame() {
            if (!isVisible || surfaceHolder?.surface?.isValid != true || screenWidth == 0 || screenHeight == 0) {
                Log.d(TAG, "drawCurrentFrame: Conditions not met (visible=$isVisible, validSurface=${surfaceHolder?.surface?.isValid}, w=$screenWidth, h=$screenHeight)")
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
                    val currentBitmaps = engineWallpaperBitmaps
                    if (currentBitmaps != null &&
                        (currentBitmaps.scrollingBackgroundBitmap != null || currentBitmaps.page1TopCroppedBitmap != null) ) { // 至少有一个有效位图
                        // 创建配置对象
                        val config = SharedWallpaperRenderer.WallpaperConfig(
                            screenWidth = screenWidth,
                            screenHeight = screenHeight,
                            page1BackgroundColor = page1BackgroundColor,
                            page1ImageHeightRatio = page1ImageHeightRatio,
                            currentXOffset = currentPageOffset, // 使用 Launcher 报告的 xOffset
                            numVirtualPages = numPagesReportedByLauncher.coerceAtLeast(1), // 使用 Launcher 报告的页数
                            p1OverlayFadeTransitionRatio = 0.2f // 或者从 SharedWallpaperRenderer 统一获取
                        )
                        // 调用共享的绘制方法
                        SharedWallpaperRenderer.drawFrame(canvas, config, currentBitmaps)
                    } else {
                        // 如果位图无效或正在加载，绘制占位符
                        SharedWallpaperRenderer.drawPlaceholder(canvas, screenWidth, screenHeight,
                            if (imageUriString == null) "请选择图片" else "壁纸加载中...")
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

        // 移除旧的 drawBackgroundPlaceholder 和 drawPlaceholderForP1Overlay

        override fun onDestroy() { // WallpaperService.Engine.onDestroy
            super.onDestroy()
            engineScope.cancel() // 确保协程在引擎销毁时取消
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            SharedWallpaperRenderer.recycleBitmaps(engineWallpaperBitmaps)
            engineWallpaperBitmaps = null
            Log.d(TAG, "H2WallpaperEngine destroyed.")
        }
    }
}