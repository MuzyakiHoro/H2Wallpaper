package com.example.h2wallpaper

import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Handler // 即使未使用，保留导入无大碍
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
// RenderScript imports
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur

// import java.io.IOException // 如果没有直接的IO异常处理，可以移除
import kotlin.math.min
import kotlin.math.roundToInt

class H2WallpaperService : WallpaperService() {

    companion object {
        private const val TAG = "H2WallpaperSvc"
        private const val NUM_VIRTUAL_SCROLL_PAGES = 3
        private const val P1_OVERLAY_FADE_TRANSITION_RATIO = 0.2f
        private const val BACKGROUND_BLUR_RADIUS = 25f // 模糊半径 (1-25)，值越大越模糊
    }

    override fun onCreateEngine(): Engine {
        return H2WallpaperEngine()
    }

    private inner class H2WallpaperEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        // private val handler = Handler(Looper.getMainLooper())
        private var surfaceHolder: SurfaceHolder? = null
        private var isVisible: Boolean = false
        private var screenWidth: Int = 0
        private var screenHeight: Int = 0

        private var originalBitmap: Bitmap? = null
        private var scrollingBackgroundBitmap: Bitmap? = null
        private var blurredScrollingBackgroundBitmap: Bitmap? = null // 新增：模糊背景图
        private var page1TopCroppedBitmap: Bitmap? = null

        private var page1BackgroundColor: Int = Color.LTGRAY
        private var page1ImageHeightRatio: Float = 1f / 3f
        private val DEFAULT_HEIGHT_RATIO_ENGINE = 1f / 3f

        private val scrollingBgPaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
        private val p1OverlayBgPaint = Paint()
        private val p1OverlayImagePaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
        private val placeholderTextPaint = Paint().apply {
            color = Color.WHITE; textSize = 40f; textAlign = Paint.Align.CENTER; isAntiAlias = true
        }
        private val placeholderBgPaint = Paint()

        private var numPagesReportedByLauncher = 1
        private var currentXOffsetStep = 1.0f
        private var currentPageOffset = 0f

        private val prefs: SharedPreferences = applicationContext.getSharedPreferences(
            MainActivity.PREFS_NAME, Context.MODE_PRIVATE
        )

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            this.surfaceHolder = surfaceHolder
            prefs.registerOnSharedPreferenceChangeListener(this)
            Log.d(TAG, "H2WallpaperEngine Created.")
            loadPreferencesOnly()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            val oldScreenWidth = screenWidth
            val oldScreenHeight = screenHeight
            this.screenWidth = width
            this.screenHeight = height
            Log.d(TAG, "Surface changed: $width x $height. Old: ${oldScreenWidth}x${oldScreenHeight}")

            if (oldScreenWidth != width || oldScreenHeight != height || originalBitmap == null || scrollingBackgroundBitmap == null) {
                Log.d(TAG, "Dimensions changed or bitmaps need (re)preparation.")
                loadAndPrepareWallpaperBitmaps()
            } else if (isVisible && surfaceHolder?.surface?.isValid == true) {
                Log.d(TAG, "Dimensions unchanged, redrawing.")
                drawCurrentFrame()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            isVisible = false
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            recycleBitmaps()
            Log.d(TAG, "H2WallpaperEngine Surface destroyed.")
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.isVisible = visible
            Log.d(TAG, "Visibility changed: $visible")
            if (visible) {
                if (originalBitmap == null || scrollingBackgroundBitmap == null || screenWidth == 0 || screenHeight == 0) {
                    Log.d(TAG, "Visible but bitmaps or dimensions not ready, preparing.")
                    loadAndPrepareWallpaperBitmaps()
                } else if (surfaceHolder?.surface?.isValid == true) {
                    Log.d(TAG, "Visible and ready, drawing frame.")
                    drawCurrentFrame()
                }
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float,
            xOffsetStep: Float, yOffsetStep: Float,
            xPixelOffset: Int, yPixelOffset: Int
        ) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset)

            val oldOffset = this.currentPageOffset
            this.currentPageOffset = xOffset
            this.currentXOffsetStep = if (xOffsetStep <= 0f || xOffsetStep >=1f) 1.0f else xOffsetStep

            val reportedPages = if (this.currentXOffsetStep < 1.0f) {
                (1f / this.currentXOffsetStep).roundToInt().coerceAtLeast(1)
            } else {
                1
            }
            this.numPagesReportedByLauncher = if (reportedPages < 1) 1 else reportedPages

            if (oldOffset != xOffset) {
                if (isVisible && surfaceHolder?.surface?.isValid == true) {
                    drawCurrentFrame()
                }
            }
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            if (key == MainActivity.KEY_IMAGE_URI ||
                key == MainActivity.KEY_BACKGROUND_COLOR ||
                key == MainActivity.KEY_IMAGE_HEIGHT_RATIO) {
                Log.d(TAG, "Relevant preference changed ('$key'), reloading bitmaps.")
                loadAndPrepareWallpaperBitmaps()
            }
        }

        private fun loadPreferencesOnly() {
            page1BackgroundColor = prefs.getInt(MainActivity.KEY_BACKGROUND_COLOR, Color.LTGRAY)
            page1ImageHeightRatio = prefs.getFloat(MainActivity.KEY_IMAGE_HEIGHT_RATIO, DEFAULT_HEIGHT_RATIO_ENGINE)
        }

        private fun loadAndPrepareWallpaperBitmaps() {
            Log.d(TAG, "loadAndPrepareWallpaperBitmaps called.")
            loadPreferencesOnly()
            val imageUriString = prefs.getString(MainActivity.KEY_IMAGE_URI, null)
            recycleBitmaps()

            if (imageUriString == null) {
                Log.w(TAG, "No image URI set.")
                if (isVisible && surfaceHolder?.surface?.isValid == true) drawCurrentFrame()
                return
            }
            if (screenWidth == 0 || screenHeight == 0) {
                Log.w(TAG, "Screen dimensions zero, cannot prepare bitmaps yet.")
                return
            }

            try {
                val imageUri = Uri.parse(imageUriString)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                var inputStream = contentResolver.openInputStream(imageUri)
                BitmapFactory.decodeStream(inputStream, null, options)
                inputStream?.close()

                val sampleSizeTargetWidth = screenWidth * NUM_VIRTUAL_SCROLL_PAGES
                options.inSampleSize = calculateInSampleSize(options, sampleSizeTargetWidth, screenHeight)
                options.inJustDecodeBounds = false
                options.inPreferredConfig = Bitmap.Config.ARGB_8888

                inputStream = contentResolver.openInputStream(imageUri)
                originalBitmap = BitmapFactory.decodeStream(inputStream, null, options)
                inputStream?.close()

                if (originalBitmap != null) {
                    Log.d(TAG, "Original bitmap decoded: ${originalBitmap!!.width}x${originalBitmap!!.height}")
                    prepareDerivedBitmapsInternal(originalBitmap!!)
                } else {
                    Log.e(TAG, "Failed to decode originalBitmap after sampling.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading originalBitmap from URI: $imageUriString", e)
                originalBitmap = null
            }

            if (isVisible && surfaceHolder?.surface?.isValid == true) {
                drawCurrentFrame()
            }
        }

        private fun prepareDerivedBitmapsInternal(sourceBitmap: Bitmap) {
            Log.d(TAG, "prepareDerivedBitmapsInternal started.")
            // 1. Prepare P1 overlay's top image
            val targetTopHeight = (screenHeight * page1ImageHeightRatio).toInt()
            page1TopCroppedBitmap?.recycle(); page1TopCroppedBitmap = null
            if (targetTopHeight > 0 && screenWidth > 0) {
                try {
                    // ... (P1顶图的裁剪和缩放逻辑不变) ...
                    val bmWidth = sourceBitmap.width; val bmHeight = sourceBitmap.height
                    val targetWidth = screenWidth
                    val bitmapAspectRatio = bmWidth.toFloat() / bmHeight.toFloat()
                    val targetAspectRatio = targetWidth.toFloat() / targetTopHeight.toFloat()
                    var srcX = 0; var srcY = 0; var cropWidth = bmWidth; var cropHeight = bmHeight

                    if (bitmapAspectRatio > targetAspectRatio) {
                        cropWidth = (bmHeight * targetAspectRatio).toInt(); srcX = (bmWidth - cropWidth) / 2
                    } else {
                        cropHeight = (bmWidth / targetAspectRatio).toInt(); srcY = (bmHeight - cropHeight) / 2
                    }
                    cropWidth = min(cropWidth, bmWidth - srcX).coerceAtLeast(1)
                    cropHeight = min(cropHeight, bmHeight - srcY).coerceAtLeast(1)
                    srcX = srcX.coerceAtLeast(0); srcY = srcY.coerceAtLeast(0)
                    if (srcX + cropWidth > bmWidth) cropWidth = bmWidth - srcX
                    if (srcY + cropHeight > bmHeight) cropHeight = bmHeight - srcY

                    if (cropWidth > 0 && cropHeight > 0) {
                        val cropped = Bitmap.createBitmap(sourceBitmap, srcX, srcY, cropWidth, cropHeight)
                        page1TopCroppedBitmap = Bitmap.createScaledBitmap(cropped, targetWidth, targetTopHeight, true)
                        if (cropped != page1TopCroppedBitmap && !cropped.isRecycled) cropped.recycle()
                        Log.d(TAG, "page1TopCroppedBitmap created: ${page1TopCroppedBitmap?.width}x${page1TopCroppedBitmap?.height}")
                    }
                } catch (e: Exception) { Log.e(TAG, "Error creating page1TopCroppedBitmap", e) }
            }

            // 2. Prepare scrolling background
            scrollingBackgroundBitmap?.recycle(); scrollingBackgroundBitmap = null
            blurredScrollingBackgroundBitmap?.recycle(); blurredScrollingBackgroundBitmap = null // 清理旧的模糊图

            val bgTargetHeight = screenHeight
            val obW = sourceBitmap.width.toFloat(); val obH = sourceBitmap.height.toFloat()
            if (obW > 0 && obH > 0 && screenWidth > 0 && bgTargetHeight > 0) {
                val scaleToFitScreenHeight = bgTargetHeight / obH
                val scaledOriginalWidth = (obW * scaleToFitScreenHeight).toInt()
                val actualNumVirtualPages = NUM_VIRTUAL_SCROLL_PAGES.coerceAtLeast(1)
                val bgFinalTargetWidth = screenWidth * actualNumVirtualPages

                if (scaledOriginalWidth > 0 && bgFinalTargetWidth > 0) {
                    try {
                        val tempScaledBitmap = Bitmap.createScaledBitmap(sourceBitmap, scaledOriginalWidth, bgTargetHeight, true)
                        scrollingBackgroundBitmap = Bitmap.createBitmap(bgFinalTargetWidth, bgTargetHeight, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(scrollingBackgroundBitmap!!)
                        var currentX = 0
                        if (scaledOriginalWidth >= bgFinalTargetWidth) {
                            val offsetX = (scaledOriginalWidth - bgFinalTargetWidth) / 2
                            val srcRect = Rect(offsetX, 0, offsetX + bgFinalTargetWidth, bgTargetHeight)
                            val dstRect = Rect(0,0,bgFinalTargetWidth,bgTargetHeight)
                            canvas.drawBitmap(tempScaledBitmap, srcRect, dstRect, null)
                        } else {
                            while(currentX < bgFinalTargetWidth) {
                                canvas.drawBitmap(tempScaledBitmap, currentX.toFloat(), 0f, null)
                                currentX += tempScaledBitmap.width
                                if (tempScaledBitmap.width <= 0) break
                            }
                        }
                        if (tempScaledBitmap != sourceBitmap && !tempScaledBitmap.isRecycled) tempScaledBitmap.recycle()
                        Log.d(TAG, "scrollingBackgroundBitmap created: ${scrollingBackgroundBitmap!!.width}x${scrollingBackgroundBitmap!!.height}")

                        // 尝试生成模糊背景图
                        if (scrollingBackgroundBitmap != null && !scrollingBackgroundBitmap!!.isRecycled) {
                            blurredScrollingBackgroundBitmap = blurBitmap(applicationContext, scrollingBackgroundBitmap!!, BACKGROUND_BLUR_RADIUS)
                            if (blurredScrollingBackgroundBitmap != null) {
                                Log.d(TAG, "blurredScrollingBackgroundBitmap created.")
                            } else {
                                Log.e(TAG, "Failed to blur scrollingBackgroundBitmap.")
                            }
                        }

                    } catch (e: Exception) { Log.e(TAG, "Error creating scrollingBackgroundBitmap or blurring it", e) }
                }
            }
            if (scrollingBackgroundBitmap == null) Log.e(TAG, "Failed to create scrollingBackgroundBitmap.")
            if (page1TopCroppedBitmap == null) Log.w(TAG, "page1TopCroppedBitmap is null after preparation.")
        }

        // 模糊图片的辅助方法
        private fun blurBitmap(context: Context, bitmap: Bitmap, radius: Float): Bitmap? {
            if (radius < 1f || radius > 25f) { // RenderScript blur radius is 1-25
                Log.w(TAG, "Blur radius out of range (1-25): $radius. Returning original bitmap or null if failed.")
                // 不能直接返回bitmap，因为调用者可能期望一个新的实例或者可以安全回收的实例
                // 或者，我们可以复制一份原始bitmap返回，如果不想模糊的话
                // return bitmap.copy(bitmap.config, true)
                // 这里简单处理：如果半径无效，我们就不进行模糊，调用者会使用原始背景图
                return null // 表示模糊失败或未进行
            }
            var rs: RenderScript? = null
            var outputBitmap: Bitmap? = null
            try {
                // 创建一个与输入位图相同配置的输出位图
                outputBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888)
                rs = RenderScript.create(context)
                val input = Allocation.createFromBitmap(rs, bitmap)
                val output = Allocation.createFromBitmap(rs, outputBitmap)
                val script = ScriptIntrinsicBlur.create(rs, input.element) // 使用input的element确保类型匹配
                script.setRadius(radius)
                script.setInput(input)
                script.forEach(output)
                output.copyTo(outputBitmap)

                // 释放RenderScript资源
                input.destroy()
                output.destroy()
                script.destroy()

                return outputBitmap
            } catch (e: Exception) {
                Log.e(TAG, "RenderScript blur failed", e)
                outputBitmap?.recycle()
                return null // 模糊失败
            } finally {
                rs?.destroy()
            }
        }


        private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
            // ... (此方法不变) ...
            val (height: Int, width: Int) = options.outHeight to options.outWidth
            var inSampleSize = 1; if (width == 0 || height == 0 || reqWidth <= 0 || reqHeight <= 0) return 1
            if (height > reqHeight || width > reqWidth) {
                val halfH = height / 2; val halfW = width / 2
                while ((halfH / inSampleSize) >= reqHeight && (halfW / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2; if (inSampleSize <= 0 || inSampleSize > 1024) { inSampleSize = 1024; break; }
                }
            }
            return inSampleSize
        }

        private fun recycleBitmaps() {
            Log.d(TAG, "Recycling bitmaps...")
            originalBitmap?.recycle(); originalBitmap = null
            scrollingBackgroundBitmap?.recycle(); scrollingBackgroundBitmap = null
            blurredScrollingBackgroundBitmap?.recycle(); blurredScrollingBackgroundBitmap = null // 回收模糊图
            page1TopCroppedBitmap?.recycle(); page1TopCroppedBitmap = null
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
                    canvas.drawColor(Color.BLACK)

                    // 1. 绘制可滚动的背景层 (优先使用模糊版本)
                    val backgroundToDraw = blurredScrollingBackgroundBitmap ?: scrollingBackgroundBitmap

                    backgroundToDraw?.let { bgBmp ->
                        if (!bgBmp.isRecycled && bgBmp.width > 0) {
                            val maxScrollPxPossible = (bgBmp.width - screenWidth).coerceAtLeast(0)
                            val currentScrollPx = (currentPageOffset * maxScrollPxPossible).toInt()
                                .coerceIn(0, maxScrollPxPossible)

                            val bgTopOffset = ((screenHeight - bgBmp.height) / 2f)

                            // Log.d(TAG, "DrawBG: offset=$currentPageOffset, scrollPx=$currentScrollPx, bgW=${bgBmp.width}, blurred=${bgBmp == blurredScrollingBackgroundBitmap}")

                            canvas.save()
                            canvas.translate(-currentScrollPx.toFloat(), bgTopOffset)
                            scrollingBgPaint.alpha = 255
                            canvas.drawBitmap(bgBmp, 0f, 0f, scrollingBgPaint)
                            canvas.restore()
                        } else {
                            drawBackgroundPlaceholder(canvas, "背景处理中...")
                        }
                    } ?: run {
                        drawBackgroundPlaceholder(canvas, "请选择图片以生成背景")
                    }

                    // 2. 计算并绘制第一屏的叠加层
                    var overlayAlpha = 0
                    val topImageActualHeight = (screenHeight * page1ImageHeightRatio).toInt()

                    val safeNumPages = if (numPagesReportedByLauncher <= 0) 1 else numPagesReportedByLauncher
                    val safeXOffsetStep = if (currentXOffsetStep <= 0f || currentXOffsetStep > 1f) 1.0f / safeNumPages else currentXOffsetStep

                    if (safeNumPages == 1) {
                        overlayAlpha = 255
                    } else {
                        val transitionBoundary = safeXOffsetStep * P1_OVERLAY_FADE_TRANSITION_RATIO
                        if (transitionBoundary > 0.001f) { // 避免除以非常小的值或零
                            if (currentPageOffset <= transitionBoundary) {
                                val progressOutOfP1FadeZone = (currentPageOffset / transitionBoundary).coerceIn(0f, 1f)
                                overlayAlpha = ((1.0f - progressOutOfP1FadeZone) * 255).toInt().coerceIn(0, 255)
                            } else {
                                overlayAlpha = 0
                            }
                        } else { // 如果过渡区域无效（例如 xOffsetStep 太小），则根据是否在第一页粗略判断
                            overlayAlpha = if (currentPageOffset < 0.01f) 255 else 0 // 几乎完全在第一页才显示
                        }
                    }
                    // Log.d(TAG, "Overlay: alpha=$overlayAlpha, offset=$currentPageOffset, xStep=$safeXOffsetStep")

                    if (overlayAlpha > 0) {
                        p1OverlayBgPaint.color = page1BackgroundColor
                        p1OverlayBgPaint.alpha = overlayAlpha
                        canvas.drawRect(0f, topImageActualHeight.toFloat(), screenWidth.toFloat(), screenHeight.toFloat(), p1OverlayBgPaint)

                        if (page1TopCroppedBitmap != null && !page1TopCroppedBitmap!!.isRecycled) {
                            p1OverlayImagePaint.alpha = overlayAlpha
                            canvas.drawBitmap(page1TopCroppedBitmap!!, 0f, 0f, p1OverlayImagePaint)
                        } else if (originalBitmap != null) {
                            drawPlaceholderForP1Overlay(canvas, topImageActualHeight, "P1顶图处理中...", overlayAlpha)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during drawFrame", e)
            } finally {
                if (canvas != null) {
                    try { surfaceHolder!!.unlockCanvasAndPost(canvas) } catch (e: Exception) { Log.e(TAG, "Error unlocking canvas", e) }
                }
                scrollingBgPaint.alpha = 255
                p1OverlayBgPaint.alpha = 255
                p1OverlayImagePaint.alpha = 255
                placeholderTextPaint.alpha = 255
                placeholderBgPaint.alpha = 255
            }
        }

        private fun drawBackgroundPlaceholder(canvas: Canvas, text: String){
            placeholderBgPaint.color = Color.DKGRAY
            placeholderBgPaint.alpha = 255
            canvas.drawRect(0f,0f, screenWidth.toFloat(), screenHeight.toFloat(), placeholderBgPaint)
            placeholderTextPaint.alpha = 200
            val textY = screenHeight / 2f - ((placeholderTextPaint.descent() + placeholderTextPaint.ascent()) / 2f)
            canvas.drawText(text, screenWidth/2f, textY, placeholderTextPaint)
        }

        private fun drawPlaceholderForP1Overlay(canvas: Canvas, topImageActualHeight: Int, text: String, overallAlpha: Int) {
            // 确保 topImageActualHeight > 0 才绘制，避免无效矩形
            if (topImageActualHeight <= 0) return

            placeholderBgPaint.color = Color.GRAY
            placeholderBgPaint.alpha = overallAlpha
            canvas.drawRect(0f, 0f, screenWidth.toFloat(), topImageActualHeight.toFloat(), placeholderBgPaint)

            placeholderTextPaint.alpha = overallAlpha
            val textX = screenWidth / 2f
            val textY = topImageActualHeight / 2f - ((placeholderTextPaint.descent() + placeholderTextPaint.ascent()) / 2f)
            canvas.drawText(text, textX, textY, placeholderTextPaint)
        }

        override fun onDestroy() {
            super.onDestroy()
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            recycleBitmaps()
            Log.d(TAG, "H2WallpaperEngine destroyed.")
        }
    }
}