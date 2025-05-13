package com.example.h2wallpaper // 确保这仍然是你的包名

import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import java.io.IOException
import kotlin.math.min
import android.graphics.Paint // 确保是这个
import android.graphics.RenderEffect // 确保是这个
import android.graphics.Shader // 因为 createBlurEffect 需要 Shader.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect

// ...

// ... 其他必要的导入

class H2WallpaperService : WallpaperService() {

    companion object {
        private const val TAG = "H2WallpaperSvc" // 日志标签
    }

    // 当系统绑定到此服务时，创建 Engine 实例
    override fun onCreateEngine(): Engine {
        return H2WallpaperEngine()
    }

    private inner class H2WallpaperEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private val handler = Handler(Looper.getMainLooper()) // 用于在主线程执行操作
        private var surfaceHolder: SurfaceHolder? = null
        private var isVisible: Boolean = false
        private var screenWidth: Int = 0
        private var screenHeight: Int = 0

        // 用于存储处理后的图片资源
        private var originalBitmap: Bitmap? = null // 从URI加载的原始Bitmap（可能已采样）
        private var page1TopCroppedBitmap: Bitmap? = null // 第一页上1/3的裁剪后图片
        private var page2PlusBlurredBitmap: Bitmap? = null // 第二页及以后的模糊图片

        private var page1BackgroundColor: Int = Color.LTGRAY // 第一页下三分之二的背景色

        // Paint对象用于绘图时的样式控制
        private val imagePaint = Paint().apply {
            isAntiAlias = true // 抗锯齿
            isFilterBitmap = true // 对Bitmap进行滤波处理，使缩放时更平滑
        }
        private val backgroundPaint = Paint() // 用于绘制背景色

        // 桌面相关状态
        private var numPages = 1 // 桌面总页数，默认为1
        private var currentPageOffset = 0f // 当前页面偏移 (0.0 到 1.0)

        // 获取 SharedPreferences 实例以读取 MainActivity 保存的设置
        private val prefs: SharedPreferences = applicationContext.getSharedPreferences(
            MainActivity.PREFS_NAME, Context.MODE_PRIVATE
        )

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            this.surfaceHolder = surfaceHolder
            // 注册监听器，当MainActivity中的设置改变时，这里会收到通知
            prefs.registerOnSharedPreferenceChangeListener(this)
            // 初始加载一次壁纸资源
            loadAndPrepareWallpaperBitmaps()
        }

        // 当壁纸的Surface尺寸发生变化时调用（例如屏幕旋转）
        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            this.screenWidth = width
            this.screenHeight = height
            Log.d(TAG, "Surface changed: $width x $height")
            // 屏幕尺寸变化后，需要重新准备和缩放Bitmap以适应新尺寸
            if (originalBitmap != null) {
                prepareDerivedBitmaps() // 根据新屏幕尺寸重新处理图片
            }
            drawCurrentFrame() // 重绘壁纸
        }

        // 当壁纸的Surface被销毁时调用
        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            isVisible = false
            handler.removeCallbacks(drawRunner) // 移除所有待处理的绘制任务
            prefs.unregisterOnSharedPreferenceChangeListener(this) // 注销监听器
            recycleBitmaps() // 释放Bitmap内存
            Log.d(TAG, "Surface destroyed and bitmaps recycled.")
        }

        // 当壁纸的可见性发生变化时调用
        override fun onVisibilityChanged(visible: Boolean) {
            this.isVisible = visible
            if (visible) {
                // 壁纸变为可见时，确保加载最新的配置并重绘
                loadAndPrepareWallpaperBitmaps()
                drawCurrentFrame()
            } else {
                // 壁纸不可见时，停止绘制任务
                handler.removeCallbacks(drawRunner)
            }
        }

        // 当用户在桌面左右滑动页面时调用
        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float,
            xOffsetStep: Float, yOffsetStep: Float,
            xPixelOffset: Int, yPixelOffset: Int
        ) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset)
            // xOffsetStep > 0 时，numPages 大约是 1/xOffsetStep (不完全精确，但可用于简单判断)
            // 为了更准确，可以认为只要xOffsetStep有效且小于1，就至少有2页
            numPages = if (xOffsetStep > 0f && xOffsetStep < 1f) ((1f / xOffsetStep).toInt()) +1 else 1
            if (numPages < 1) numPages = 1 // 至少1页

            currentPageOffset = xOffset
            // Log.d(TAG, "OffsetsChanged: xOffset=$xOffset, xOffsetStep=$xOffsetStep, numPages=$numPages")
            drawCurrentFrame() // 根据新的页面偏移重绘壁纸
        }

        // 当SharedPreferences中的数据发生改变时（即用户在MainActivity中更新了设置）调用
        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            Log.d(TAG, "Preferences changed: key=$key, reloading wallpaper data.")
            // 配置已更新，需要重新加载图片、颜色并重绘
            loadAndPrepareWallpaperBitmaps()
        }

        // 加载用户设置（图片URI、背景色）并准备Bitmap对象
        private fun loadAndPrepareWallpaperBitmaps() {
            val imageUriString = prefs.getString(MainActivity.KEY_IMAGE_URI, null)
            page1BackgroundColor = prefs.getInt(MainActivity.KEY_BACKGROUND_COLOR, Color.LTGRAY)
            Log.d(TAG, "Preferences loaded: URI=$imageUriString, BackgroundColor=$page1BackgroundColor")

            if (imageUriString != null) {
                try {
                    val imageUri = Uri.parse(imageUriString)
                    // 为了避免OOM，需要对大图进行采样加载
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    var inputStream = contentResolver.openInputStream(imageUri) // 第一次打开获取尺寸
                    BitmapFactory.decodeStream(inputStream, null, options)
                    inputStream?.close()

                    // 计算合适的采样率
                    // 我们希望加载的图片尺寸不要远超屏幕尺寸，以节省内存
                    // 对于原始图片，可以允许其比屏幕稍大，以便后续裁剪和缩放
                    options.inSampleSize = calculateInSampleSize(options, screenWidth * 2, screenHeight * 2)
                    options.inJustDecodeBounds = false

                    recycleBitmaps() // 在加载新Bitmap前，先释放旧的Bitmap内存

                    inputStream = contentResolver.openInputStream(imageUri) // 第二次打开真正加载图片
                    originalBitmap = BitmapFactory.decodeStream(inputStream, null, options)
                    inputStream?.close()

                    if (originalBitmap != null) {
                        prepareDerivedBitmaps() // 如果成功加载原始图片，则准备其他派生图片
                    } else {
                        Log.e(TAG, "Failed to decode original bitmap from URI after sampling.")
                    }
                } catch (e: Exception) { // IOException or SecurityException or OutOfMemoryError
                    Log.e(TAG, "Error loading original image in service for URI: $imageUriString", e)
                    recycleBitmaps()
                    originalBitmap = null
                }
            } else {
                Log.w(TAG, "No image URI found in preferences. Clearing bitmaps.")
                recycleBitmaps()
                originalBitmap = null
            }
            drawCurrentFrame() // 无论加载成功与否，都尝试重绘（无图时会绘制默认背景）
        }

        // 计算BitmapFactory的inSampleSize值
        private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
            val (height: Int, width: Int) = options.outHeight to options.outWidth
            var inSampleSize = 1
            if (height > reqHeight || width > reqWidth) {
                val halfHeight: Int = height / 2
                val halfWidth: Int = width / 2
                // 计算最大的inSampleSize值，该值是2的幂，并使高度和宽度都大于请求的高度和宽度。
                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            Log.d(TAG, "Calculated inSampleSize: $inSampleSize for ${options.outWidth}x${options.outHeight} -> req ${reqWidth}x${reqHeight}")
            return inSampleSize
        }

        // 根据原始Bitmap准备用于不同页面的Bitmap（裁剪、模糊等）
        private fun prepareDerivedBitmaps() {
            originalBitmap?.let { bmp ->
                if (screenWidth == 0 || screenHeight == 0) {
                    Log.w(TAG, "Screen dimensions are zero, cannot prepare derived bitmaps yet.")
                    return // 如果屏幕尺寸未知，无法继续
                }

                // 1. 准备第一页上三分之一的图片 (page1TopCroppedBitmap)
                val targetTopHeight = screenHeight / 3
                // 目标是使图片宽度为屏幕宽度，高度为 targetTopHeight，内容居中裁剪
                val scaleToFitWidth = screenWidth.toFloat() / bmp.width.toFloat()
                val scaledHeightForWidthFit = (bmp.height * scaleToFitWidth).toInt()

                val tempScaledBitmap1: Bitmap
                var srcX = 0
                var srcY = 0
                var cropWidth = bmp.width
                var cropHeight = bmp.height

                // 计算如何从原图中裁剪出一块，使其缩放后能最佳填充目标区域
                // 目标宽高比
                val targetAspectRatio = screenWidth.toFloat() / targetTopHeight.toFloat()
                // 原图宽高比
                val originalAspectRatio = bmp.width.toFloat() / bmp.height.toFloat()

                if (originalAspectRatio > targetAspectRatio) { // 原图比目标区域更宽（或一样宽但更高）
                    // 以高度为基准进行裁剪宽度
                    cropHeight = bmp.height
                    cropWidth = (bmp.height * targetAspectRatio).toInt()
                    srcX = (bmp.width - cropWidth) / 2
                } else { // 原图比目标区域更高（或一样高但更窄）
                    // 以宽度为基准进行裁剪高度
                    cropWidth = bmp.width
                    cropHeight = (bmp.width / targetAspectRatio).toInt()
                    srcY = (bmp.height - cropHeight) / 2
                }

                // 防止裁剪尺寸超出原图边界
                cropWidth = min(cropWidth, bmp.width - srcX)
                cropHeight = min(cropHeight, bmp.height - srcY)
                if (cropWidth <=0 || cropHeight <=0) { // 避免无效裁剪
                    Log.e(TAG, "Invalid crop dimensions for page 1 top image.")
                    page1TopCroppedBitmap?.recycle()
                    page1TopCroppedBitmap = null
                } else {
                    val croppedOriginal = Bitmap.createBitmap(bmp, srcX, srcY, cropWidth, cropHeight)
                    page1TopCroppedBitmap?.recycle() // 回收旧的
                    page1TopCroppedBitmap = Bitmap.createScaledBitmap(croppedOriginal, screenWidth, targetTopHeight, true)
                    if (croppedOriginal != page1TopCroppedBitmap) croppedOriginal.recycle()
                }


                // 2. 准备第二页及以后的模糊图片 (page2PlusBlurredBitmap)
                // 目标：全屏居中显示，保持宽高比，然后模糊
                val aspect = bmp.width.toFloat() / bmp.height.toFloat()
                var page2Width = screenWidth
                var page2Height = (page2Width / aspect).toInt()

                // 调整尺寸以确保至少有一边填满屏幕，同时保持比例，且图片居中
                if (page2Height < screenHeight) {
                    page2Height = screenHeight
                    page2Width = (page2Height * aspect).toInt()
                } else if (page2Width < screenWidth) { // 应该不会走到这里如果上面逻辑正确
                    page2Width = screenWidth
                    page2Height = (page2Width/aspect).toInt()
                }


                val scaledBmpForPage2 = Bitmap.createScaledBitmap(bmp, page2Width, page2Height, true)

                // 由于 minSdk 是 32，可以直接使用 RenderEffect 进行模糊
                val blurRadius = 25f // 模糊半径，可以根据需要调整
                val renderEffect = RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP)


                page2PlusBlurredBitmap?.recycle()
                page2PlusBlurredBitmap = Bitmap.createBitmap(scaledBmpForPage2.width, scaledBmpForPage2.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(page2PlusBlurredBitmap!!)
                val paintWithBlur = Paint().apply { setRenderEffect(renderEffect) } // 然后在这里使用
                canvas.drawBitmap(scaledBmpForPage2, 0f, 0f, paintWithBlur)

                if (scaledBmpForPage2 != page2PlusBlurredBitmap) scaledBmpForPage2.recycle() // 如果创建了新Bitmap则回收中间品

                Log.d(TAG, "Derived bitmaps prepared. Page1: ${page1TopCroppedBitmap?.width}x${page1TopCroppedBitmap?.height}, Page2: ${page2PlusBlurredBitmap?.width}x${page2PlusBlurredBitmap?.height}")

            } ?: run {
                Log.w(TAG, "Original bitmap is null, cannot prepare derived bitmaps.")
                page1TopCroppedBitmap?.recycle()
                page1TopCroppedBitmap = null
                page2PlusBlurredBitmap?.recycle()
                page2PlusBlurredBitmap = null
            }
        }

        // 释放所有Bitmap对象占用的内存
        private fun recycleBitmaps() {
            originalBitmap?.recycle()
            page1TopCroppedBitmap?.recycle()
            page2PlusBlurredBitmap?.recycle()
            originalBitmap = null
            page1TopCroppedBitmap = null
            page2PlusBlurredBitmap = null
            Log.d(TAG, "All service bitmaps recycled.")
        }

        // Runnable 对象用于通过 Handler 在主线程执行绘制操作
        private val drawRunner = Runnable { drawCurrentFrame() }

        // 核心绘制函数
        private fun drawCurrentFrame() {
            if (!isVisible || surfaceHolder == null || screenWidth == 0 || screenHeight == 0) {
                // 如果壁纸不可见、Surface未创建或屏幕尺寸未知，则不进行绘制
                return
            }

            var canvas: Canvas? = null
            try {
                canvas = surfaceHolder!!.lockCanvas() // 锁定画布准备绘制
                if (canvas != null) {
                    canvas.drawColor(Color.BLACK) // 用黑色清除画布，作为默认背景

                    // 判断当前是第几页
                    // 简单处理：currentPageOffset 接近0认为是第一页 (给一个小阈值避免浮点数精度问题)
                    val isFirstPage = if (numPages <= 1) true else currentPageOffset < ( (1.0f / numPages) / 2.0f ) // 如果偏移量小于半个页面步长，则认为是第一页

                    if (isFirstPage) {
                        drawPage1Layout(canvas)
                    } else {
                        drawPage2PlusLayout(canvas)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during drawFrame", e)
            }
            finally {
                if (canvas != null) {
                    try {
                        surfaceHolder!!.unlockCanvasAndPost(canvas) // 解锁画布并提交绘制内容
                    } catch (e: Exception) {
                        Log.e(TAG, "Error unlocking canvas", e)
                    }
                }
            }
            // 如果需要连续动画，可以在这里重新post drawRunner
            // handler.removeCallbacks(drawRunner)
            // if (isVisible) {
            //     handler.postDelayed(drawRunner, 40) // 例如每40毫秒重绘一次
            // }
        }

        // 绘制第一页的布局
        private fun drawPage1Layout(canvas: Canvas) {
            // 1. 绘制下三分之二的背景色
            backgroundPaint.color = page1BackgroundColor
            val topThirdHeight = screenHeight / 3f
            canvas.drawRect(0f, topThirdHeight, screenWidth.toFloat(), screenHeight.toFloat(), backgroundPaint)

            // 2. 绘制上三分之一的图片
            page1TopCroppedBitmap?.let { bmp ->
                // 图片应该已经是屏幕宽度，高度为1/3屏幕高，直接从(0,0)绘制
                canvas.drawBitmap(bmp, 0f, 0f, imagePaint)
            } ?: run {
                // 如果没有图片，用一个默认颜色填充上1/3区域，并提示
                backgroundPaint.color = Color.DKGRAY
                canvas.drawRect(0f, 0f, screenWidth.toFloat(), topThirdHeight, backgroundPaint)
                val textPaint = Paint().apply { color = Color.WHITE; textSize = 40f; textAlign = Paint.Align.CENTER }
                canvas.drawText("请在App中选择图片", screenWidth / 2f, topThirdHeight / 2, textPaint)
            }
        }

        // 绘制第二页及以后页面的布局 (全屏居中模糊图片)
        private fun drawPage2PlusLayout(canvas: Canvas) {
            page2PlusBlurredBitmap?.let { bmp ->
                // 计算图片居中绘制的左上角坐标
                val left = (screenWidth - bmp.width) / 2f
                val top = (screenHeight - bmp.height) / 2f
                canvas.drawBitmap(bmp, left, top, imagePaint)
            } ?: run {
                // 如果没有图片，用一个默认颜色填充整个屏幕，并提示
                backgroundPaint.color = Color.DKGRAY
                canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), backgroundPaint)
                val textPaint = Paint().apply { color = Color.WHITE; textSize = 40f; textAlign = Paint.Align.CENTER }
                canvas.drawText("请在App中选择图片", screenWidth / 2f, screenHeight / 2f, textPaint)
            }
        }

        // 当Engine被销毁时调用
        override fun onDestroy() {
            super.onDestroy()
            handler.removeCallbacks(drawRunner) // 确保移除所有回调
            prefs.unregisterOnSharedPreferenceChangeListener(this) // 注销监听器
            recycleBitmaps() // 释放所有Bitmap资源
            Log.d(TAG, "H2WallpaperEngine destroyed.")
        }
    }
}