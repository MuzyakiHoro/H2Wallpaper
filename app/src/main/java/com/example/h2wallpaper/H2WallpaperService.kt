package com.example.h2wallpaper

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
// RenderEffect and Shader are not used in this version due to setRenderEffect issues
// import android.graphics.RenderEffect
// import android.graphics.Shader

class H2WallpaperService : WallpaperService() {

    companion object {
        private const val TAG = "H2WallpaperSvc"
    }

    override fun onCreateEngine(): Engine {
        return H2WallpaperEngine()
    }

    private inner class H2WallpaperEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private val handler = Handler(Looper.getMainLooper())
        private var surfaceHolder: SurfaceHolder? = null
        private var isVisible: Boolean = false
        private var screenWidth: Int = 0
        private var screenHeight: Int = 0

        private var originalBitmap: Bitmap? = null
        private var page1TopCroppedBitmap: Bitmap? = null
        private var page2PlusImageBitmap: Bitmap? = null // Renamed from page2PlusBlurredBitmap

        private var page1BackgroundColor: Int = Color.LTGRAY
        private var page1ImageHeightRatio: Float = 1f / 3f // Default height ratio

        private val imagePaint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }
        private val backgroundPaint = Paint()

        private var numPages = 1
        private var currentPageOffset = 0f

        private val prefs: SharedPreferences = applicationContext.getSharedPreferences(
            MainActivity.PREFS_NAME, Context.MODE_PRIVATE
        )

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            this.surfaceHolder = surfaceHolder
            prefs.registerOnSharedPreferenceChangeListener(this)
            // Defer image loading until surface is ready or visibility changes,
            // as screenWidth/Height might not be available yet.
            // If you want to load immediately, ensure default values for calculateInSampleSize are robust.
            // For now, loadAndPrepareWallpaperBitmaps() will be called by onVisibilityChanged or onSurfaceChanged.
            Log.d(TAG, "Engine Created. Screen: ${screenWidth}x${screenHeight}")
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            this.screenWidth = width
            this.screenHeight = height
            Log.d(TAG, "Surface changed: $width x $height. Reloading and preparing bitmaps.")
            loadAndPrepareWallpaperBitmaps() // Now we have dimensions, load/reload everything
            // drawCurrentFrame() will be called at the end of loadAndPrepareWallpaperBitmaps
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            isVisible = false
            handler.removeCallbacks(drawRunner)
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            recycleBitmaps()
            Log.d(TAG, "Surface destroyed and bitmaps recycled.")
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.isVisible = visible
            if (visible) {
                Log.d(TAG, "Wallpaper visible. Screen: ${screenWidth}x${screenHeight}. Reloading and preparing bitmaps.")
                loadAndPrepareWallpaperBitmaps() // Ensure latest data is loaded when visible
            } else {
                handler.removeCallbacks(drawRunner)
            }
        }

        override fun onOffsetsChanged(
            xOffset: Float, yOffset: Float,
            xOffsetStep: Float, yOffsetStep: Float,
            xPixelOffset: Int, yPixelOffset: Int
        ) {
            super.onOffsetsChanged(xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset)
            numPages = if (xOffsetStep > 0f && xOffsetStep < 1f) ((1f / xOffsetStep).toInt()) + 1 else 1
            if (numPages < 1) numPages = 1
            currentPageOffset = xOffset
            if (isVisible && surfaceHolder != null && surfaceHolder!!.surface.isValid) {
                drawCurrentFrame()
            }
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
            Log.e(TAG, ">>>> onSharedPreferenceChanged - Key: $key <<<<")
            // If visible and surface is valid, reload and redraw
            if (isVisible && screenWidth > 0 && screenHeight > 0) { // Ensure dimensions are known
                loadAndPrepareWallpaperBitmaps()
            } else {
                Log.d(TAG, "Prefs changed but not redrawing (not visible or no dimensions yet)")
            }
        }

        private fun loadAndPrepareWallpaperBitmaps() {
            val imageUriString = prefs.getString(MainActivity.KEY_IMAGE_URI, null)
            page1BackgroundColor = prefs.getInt(MainActivity.KEY_BACKGROUND_COLOR, Color.LTGRAY)
            page1ImageHeightRatio = prefs.getFloat(MainActivity.KEY_IMAGE_HEIGHT_RATIO, 1f / 3f) // Load height ratio
            Log.e(TAG, ">>>> Loaded page1ImageHeightRatio from prefs: $page1ImageHeightRatio <<<<") // More visible log

            if (imageUriString != null) {
                try {
                    val imageUri = Uri.parse(imageUriString)
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    var inputStream = contentResolver.openInputStream(imageUri)
                    BitmapFactory.decodeStream(inputStream, null, options)
                    inputStream?.close()

                    // Use default large dimensions if screenWidth/Height are not yet set
                    val reqW = if (screenWidth > 0) screenWidth * 2 else 2160 // Default FHD width * 2
                    val reqH = if (screenHeight > 0) screenHeight * 2 else 4096 // Default large height

                    options.inSampleSize = calculateInSampleSize(options, reqW, reqH)
                    options.inJustDecodeBounds = false

                    recycleBitmaps()

                    inputStream = contentResolver.openInputStream(imageUri)
                    originalBitmap = BitmapFactory.decodeStream(inputStream, null, options)
                    inputStream?.close()

                    if (originalBitmap != null) {
                        if (screenWidth > 0 && screenHeight > 0) { // Prepare derived only if dimensions are known
                            prepareDerivedBitmaps()
                        } else {
                            Log.w(TAG, "Original bitmap loaded, but screen dimensions unknown. Derived bitmaps will be prepared onSurfaceChanged.")
                        }
                    } else {
                        Log.e(TAG, "Failed to decode original bitmap from URI after sampling.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading original image in service for URI: $imageUriString", e)
                    recycleBitmaps()
                    originalBitmap = null
                }
            } else {
                Log.w(TAG, "No image URI found in preferences. Clearing bitmaps.")
                recycleBitmaps()
                originalBitmap = null
            }

            if (isVisible && surfaceHolder != null && surfaceHolder!!.surface.isValid) {
                drawCurrentFrame()
            }
        }

        private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
            val (height: Int, width: Int) = options.outHeight to options.outWidth
            var inSampleSize = 1

            if (reqWidth <= 0 || reqHeight <= 0) { // Guard against zero requested dimensions
                Log.w(TAG, "Requested width or height is 0 or less in calculateInSampleSize. Defaulting inSampleSize to 1.")
                return 1
            }

            if (height > reqHeight || width > reqWidth) {
                val halfHeight: Int = height / 2
                val halfWidth: Int = width / 2
                while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            Log.d(TAG, "Calculated inSampleSize: $inSampleSize for ${options.outWidth}x${options.outHeight} -> req ${reqWidth}x${reqHeight}")
            return inSampleSize
        }

        private fun prepareDerivedBitmaps() {
            Log.e(TAG, ">>>> prepareDerivedBitmaps - Using heightRatio: $page1ImageHeightRatio, Screen: ${screenWidth}x${screenHeight} <<<<")
            originalBitmap?.let { bmp ->
                if (screenWidth == 0 || screenHeight == 0) {
                    Log.w(TAG, "Screen dimensions are zero, cannot prepare derived bitmaps yet.")
                    return
                }

                // 1. 准备第一页上部分的图片 (page1TopCroppedBitmap)
                val targetTopHeight = (screenHeight * page1ImageHeightRatio).toInt() // Use the loaded ratio
                if (targetTopHeight <= 0) {
                    Log.e(TAG, "Target top height for page 1 is zero or less. Cannot create bitmap.")
                    page1TopCroppedBitmap?.recycle()
                    page1TopCroppedBitmap = null
                } else {
                    val targetAspectRatio = screenWidth.toFloat() / targetTopHeight.toFloat()
                    val originalAspectRatio = bmp.width.toFloat() / bmp.height.toFloat()
                    var srcX = 0
                    var srcY = 0
                    var cropWidth = bmp.width
                    var cropHeight = bmp.height

                    if (originalAspectRatio > targetAspectRatio) {
                        cropHeight = bmp.height
                        cropWidth = (bmp.height * targetAspectRatio).toInt()
                        srcX = (bmp.width - cropWidth) / 2
                    } else {
                        cropWidth = bmp.width
                        cropHeight = (bmp.width / targetAspectRatio).toInt()
                        srcY = (bmp.height - cropHeight) / 2
                    }
                    cropWidth = min(cropWidth, bmp.width - srcX).coerceAtLeast(1) // Ensure > 0
                    cropHeight = min(cropHeight, bmp.height - srcY).coerceAtLeast(1) // Ensure > 0
                    srcX = srcX.coerceAtLeast(0)
                    srcY = srcY.coerceAtLeast(0)


                    if (cropWidth <=0 || cropHeight <=0 || bmp.width - srcX < cropWidth || bmp.height - srcY < cropHeight) {
                        Log.e(TAG, "Invalid crop dimensions calculated for page 1 top image. CropW:$cropWidth, CropH:$cropHeight, srcX:$srcX, srcY:$srcY, BmpW:${bmp.width}, BmpH:${bmp.height}")
                        page1TopCroppedBitmap?.recycle()
                        page1TopCroppedBitmap = null
                    } else {
                        try {
                            val croppedOriginal = Bitmap.createBitmap(bmp, srcX, srcY, cropWidth, cropHeight)
                            page1TopCroppedBitmap?.recycle()
                            page1TopCroppedBitmap = Bitmap.createScaledBitmap(croppedOriginal, screenWidth, targetTopHeight, true)
                            if (croppedOriginal != page1TopCroppedBitmap) croppedOriginal.recycle()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error creating page 1 top bitmap", e)
                            page1TopCroppedBitmap = null
                        }
                    }
                }


                // 2. 准备第二页及以后的图片 (page2PlusImageBitmap) - 无模糊
                val aspect = bmp.width.toFloat() / bmp.height.toFloat()
                var page2Width = screenWidth
                var page2Height = (page2Width / aspect).toInt()
                if (page2Height < screenHeight) {
                    page2Height = screenHeight
                    page2Width = (page2Height * aspect).toInt()
                } else if (page2Width < screenWidth) {
                    page2Width = screenWidth
                    page2Height = (page2Width / aspect).toInt()
                }

                page2PlusImageBitmap?.recycle()
                try {
                    page2PlusImageBitmap = Bitmap.createScaledBitmap(bmp, page2Width.coerceAtLeast(1), page2Height.coerceAtLeast(1), true)
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating page 2 image bitmap", e)
                    page2PlusImageBitmap = null
                }


                Log.d(TAG, "Derived bitmaps prepared. Page1: ${page1TopCroppedBitmap?.width}x${page1TopCroppedBitmap?.height}, Page2: ${page2PlusImageBitmap?.width}x${page2PlusImageBitmap?.height}")

            } ?: run {
                Log.w(TAG, "Original bitmap is null, cannot prepare derived bitmaps.")
                page1TopCroppedBitmap?.recycle()
                page1TopCroppedBitmap = null
                page2PlusImageBitmap?.recycle()
                page2PlusImageBitmap = null
            }
        }

        private fun recycleBitmaps() {
            originalBitmap?.recycle()
            page1TopCroppedBitmap?.recycle()
            page2PlusImageBitmap?.recycle()
            originalBitmap = null
            page1TopCroppedBitmap = null
            page2PlusImageBitmap = null
            Log.d(TAG, "All service bitmaps recycled.")
        }

        private val drawRunner = Runnable { drawCurrentFrame() }

        private fun drawCurrentFrame() {
            if (!isVisible || surfaceHolder == null || !surfaceHolder!!.surface.isValid || screenWidth == 0 || screenHeight == 0) {
                return
            }
            var canvas: Canvas? = null
            try {
                canvas = surfaceHolder!!.lockCanvas()
                if (canvas != null) {
                    canvas.drawColor(Color.BLACK)
                    val isFirstPage = if (numPages <= 1) true else currentPageOffset < ((1.0f / numPages) / 2.0f)
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
                        surfaceHolder!!.unlockCanvasAndPost(canvas)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error unlocking canvas", e)
                    }
                }
            }
        }

        private fun drawPage1Layout(canvas: Canvas) {
            val topImageActualHeight = (screenHeight * page1ImageHeightRatio).toInt()
            Log.e(TAG, ">>>> drawPage1Layout - Using heightRatio: $page1ImageHeightRatio, Calculated topImageActualHeight: $topImageActualHeight <<<<")

            backgroundPaint.color = page1BackgroundColor
            canvas.drawRect(0f, topImageActualHeight.toFloat(), screenWidth.toFloat(), screenHeight.toFloat(), backgroundPaint)

            page1TopCroppedBitmap?.let { bmp ->
                if (!bmp.isRecycled) { // 确保 bitmap 没有被回收
                    canvas.drawBitmap(bmp, 0f, 0f, imagePaint)
                } else {
                    Log.e(TAG, "page1TopCroppedBitmap was recycled before drawing!")
                }
            } ?: run {
                backgroundPaint.color = Color.DKGRAY
                canvas.drawRect(0f, 0f, screenWidth.toFloat(), topImageActualHeight.toFloat(), backgroundPaint)
                val textPaint = Paint().apply { color = Color.WHITE; textSize = 40f; textAlign = Paint.Align.CENTER }
                canvas.drawText("请在App中选择图片", screenWidth / 2f, topImageActualHeight / 2f, textPaint)
            }
        }

        private fun drawPage2PlusLayout(canvas: Canvas) {
            page2PlusImageBitmap?.let { bmp ->
                if (!bmp.isRecycled) { // 确保 bitmap 没有被回收
                    val left = (screenWidth - bmp.width) / 2f
                    val top = (screenHeight - bmp.height) / 2f
                    canvas.drawBitmap(bmp, left, top, imagePaint)
                } else {
                    Log.e(TAG, "page2PlusImageBitmap was recycled before drawing!")
                }
            } ?: run {
                backgroundPaint.color = Color.DKGRAY
                canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), backgroundPaint)
                val textPaint = Paint().apply { color = Color.WHITE; textSize = 40f; textAlign = Paint.Align.CENTER }
                canvas.drawText("请在App中选择图片", screenWidth / 2f, screenHeight / 2f, textPaint)
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            handler.removeCallbacks(drawRunner)
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            recycleBitmaps()
            Log.d(TAG, "H2WallpaperEngine destroyed.")
        }
    }
}