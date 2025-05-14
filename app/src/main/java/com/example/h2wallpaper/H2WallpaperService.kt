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
        private var page2PlusImageBitmap: Bitmap? = null

        private var page1BackgroundColor: Int = Color.LTGRAY
        private var page1ImageHeightRatio: Float = 1f / 3f // Default height ratio, will be updated from prefs
        // 在这里定义 Service 内部使用的默认高度比例
        private val DEFAULT_HEIGHT_RATIO_ENGINE = 1f / 3f // 与 MainActivity 中的值保持一致


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
            Log.d(TAG, "Engine Created. Initial screen: ${screenWidth}x${screenHeight}. Loading initial preferences.")
            // Load preferences first to get ratio, but full bitmap prep might wait for surface dimensions
            loadPreferencesOnly() // Just load values from SharedPreferences
            // Actual bitmap loading and preparation will happen in onSurfaceChanged or onVisibilityChanged
            // if screen dimensions are needed and not yet available.
            // If screen dimensions are already somehow known (e.g. re-creation with existing surface),
            // then loadAndPrepareWallpaperBitmaps might be called sooner.
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            val oldScreenWidth = screenWidth
            val oldScreenHeight = screenHeight
            this.screenWidth = width
            this.screenHeight = height
            Log.d(TAG, "Surface changed: $width x $height. Old: ${oldScreenWidth}x${oldScreenHeight}")

            // If dimensions actually changed or if bitmaps haven't been prepared with dimensions yet
            if (oldScreenWidth != width || oldScreenHeight != height || page1TopCroppedBitmap == null || page2PlusImageBitmap == null) {
                Log.d(TAG, "Dimensions changed or bitmaps not ready, reloading and preparing bitmaps.")
                loadAndPrepareWallpaperBitmaps()
            } else {
                Log.d(TAG, "Dimensions unchanged and bitmaps seem ready, just redrawing.")
                if (isVisible && surfaceHolder != null && surfaceHolder!!.surface.isValid) {
                    drawCurrentFrame()
                }
            }
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
                Log.d(TAG, "Wallpaper visible. Screen: ${screenWidth}x${screenHeight}. Ensuring bitmaps are loaded and prepared.")
                loadAndPrepareWallpaperBitmaps() // Ensure latest data and correct bitmaps when becoming visible
            } else {
                Log.d(TAG, "Wallpaper not visible.")
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
            val oldRatio = page1ImageHeightRatio
            loadPreferencesOnly() // Reload all preferences
            if (oldRatio != page1ImageHeightRatio || key == MainActivity.KEY_IMAGE_URI || key == MainActivity.KEY_BACKGROUND_COLOR) {
                if (isVisible && screenWidth > 0 && screenHeight > 0) {
                    Log.d(TAG, "Relevant preference changed, reloading and preparing bitmaps.")
                    loadAndPrepareWallpaperBitmaps() // This will re-prepare and redraw
                } else {
                    Log.d(TAG, "Prefs changed but not fully reprocessing (not visible or no dimensions yet)")
                }
            }
        }

        // New method to only load preference values into member variables
        private fun loadPreferencesOnly() {
            page1BackgroundColor = prefs.getInt(MainActivity.KEY_BACKGROUND_COLOR, Color.LTGRAY)
            page1ImageHeightRatio = prefs.getFloat(MainActivity.KEY_IMAGE_HEIGHT_RATIO, DEFAULT_HEIGHT_RATIO_ENGINE)
            Log.e(TAG, ">>>> Preferences loaded into variables: BgColor=$page1BackgroundColor, HeightRatio=$page1ImageHeightRatio <<<<")
        }


        private fun loadAndPrepareWallpaperBitmaps() {
            // Ensure current preference values are loaded into member variables
            loadPreferencesOnly() // Make sure page1ImageHeightRatio is up-to-date before loading bitmap

            val imageUriString = prefs.getString(MainActivity.KEY_IMAGE_URI, null)
            Log.d(TAG, "loadAndPrepareWallpaperBitmaps: URI=$imageUriString")


            if (imageUriString != null) {
                try {
                    val imageUri = Uri.parse(imageUriString)
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    var inputStream = contentResolver.openInputStream(imageUri)
                    BitmapFactory.decodeStream(inputStream, null, options)
                    inputStream?.close()

                    val reqW = if (screenWidth > 0) screenWidth * 2 else 2160 // Default if screen dims not ready
                    val reqH = if (screenHeight > 0) screenHeight * 2 else 4096

                    options.inSampleSize = calculateInSampleSize(options, reqW, reqH)
                    options.inJustDecodeBounds = false

                    recycleBitmaps() // Recycle previous bitmaps before loading new ones

                    inputStream = contentResolver.openInputStream(imageUri)
                    originalBitmap = BitmapFactory.decodeStream(inputStream, null, options)
                    inputStream?.close()

                    if (originalBitmap != null) {
                        if (screenWidth > 0 && screenHeight > 0) {
                            prepareDerivedBitmaps()
                        } else {
                            Log.w(TAG, "Original bitmap loaded, but screen dimensions unknown. Derived bitmaps will be prepared onSurfaceChanged.")
                        }
                    } else {
                        Log.e(TAG, "Failed to decode original bitmap from URI after sampling.")
                        // Ensure derived bitmaps are also nullified
                        page1TopCroppedBitmap = null
                        page2PlusImageBitmap = null
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

            // Always attempt to draw, drawCurrentFrame will handle null bitmaps by drawing placeholders
            if (isVisible && surfaceHolder != null && surfaceHolder!!.surface.isValid) {
                drawCurrentFrame()
            }
        }

        private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
            val (height: Int, width: Int) = options.outHeight to options.outWidth
            var inSampleSize = 1

            if (options.outWidth == 0 || options.outHeight == 0) { // Image has no dimensions
                Log.e(TAG, "Image dimensions are zero (outWidth/outHeight is 0). Cannot calculate inSampleSize.")
                return 1 // Or throw an error / handle appropriately
            }
            if (reqWidth <= 0 || reqHeight <= 0) {
                Log.w(TAG, "Requested width ($reqWidth) or height ($reqHeight) is 0 or less. Defaulting inSampleSize to 1.")
                return 1
            }

            if (height > reqHeight || width > reqWidth) {
                val halfHeight: Int = height / 2
                val halfWidth: Int = width / 2
                while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                    if (inSampleSize == 0) { // Should not happen, but as an extreme guard
                        Log.e(TAG, "inSampleSize became 0 in loop, breaking.")
                        inSampleSize = 1024 // Arbitrary large to stop loop
                        break
                    }
                    inSampleSize *= 2
                }
            }
            Log.d(TAG, "Calculated inSampleSize: $inSampleSize for ${options.outWidth}x${options.outHeight} -> req ${reqWidth}x${reqHeight}")
            return inSampleSize
        }

        private fun prepareDerivedBitmaps() {
            Log.e(TAG, ">>>> prepareDerivedBitmaps - Using heightRatio: $page1ImageHeightRatio, Screen: ${screenWidth}x${screenHeight} <<<<")
            val currentOriginalBitmap = originalBitmap // Use a local copy for safety within this function
            if (currentOriginalBitmap == null) {
                Log.w(TAG, "Original bitmap is null, cannot prepare derived bitmaps.")
                page1TopCroppedBitmap?.recycle()
                page1TopCroppedBitmap = null
                page2PlusImageBitmap?.recycle()
                page2PlusImageBitmap = null
                return
            }

            if (screenWidth == 0 || screenHeight == 0) {
                Log.w(TAG, "Screen dimensions are zero, cannot prepare derived bitmaps yet.")
                return
            }

            // 1. 准备第一页上部分的图片 (page1TopCroppedBitmap)
            val targetTopHeight = (screenHeight * page1ImageHeightRatio).toInt()
            if (targetTopHeight <= 0) {
                Log.e(TAG, "Target top height for page 1 is zero or less ($targetTopHeight from ratio $page1ImageHeightRatio and screenH $screenHeight). Cannot create bitmap.")
                page1TopCroppedBitmap?.recycle()
                page1TopCroppedBitmap = null
            } else {
                val targetAspectRatio = screenWidth.toFloat() / targetTopHeight.toFloat()
                val originalAspectRatio = currentOriginalBitmap.width.toFloat() / currentOriginalBitmap.height.toFloat()
                var srcX = 0
                var srcY = 0
                var cropWidth = currentOriginalBitmap.width
                var cropHeight = currentOriginalBitmap.height

                if (originalAspectRatio > targetAspectRatio) {
                    cropHeight = currentOriginalBitmap.height
                    cropWidth = (currentOriginalBitmap.height * targetAspectRatio).toInt()
                    srcX = (currentOriginalBitmap.width - cropWidth) / 2
                } else {
                    cropWidth = currentOriginalBitmap.width
                    cropHeight = (currentOriginalBitmap.width / targetAspectRatio).toInt()
                    srcY = (currentOriginalBitmap.height - cropHeight) / 2
                }
                cropWidth = min(cropWidth, currentOriginalBitmap.width - srcX).coerceAtLeast(1)
                cropHeight = min(cropHeight, currentOriginalBitmap.height - srcY).coerceAtLeast(1)
                srcX = srcX.coerceAtLeast(0)
                srcY = srcY.coerceAtLeast(0)

                if (cropWidth <= 0 || cropHeight <= 0 || currentOriginalBitmap.width - srcX < cropWidth || currentOriginalBitmap.height - srcY < cropHeight) {
                    Log.e(TAG, "Invalid crop dimensions calculated for page 1 top image. CmW:$cropWidth, CmH:$cropHeight, sX:$srcX, sY:$srcY, BmW:${currentOriginalBitmap.width}, BmH:${currentOriginalBitmap.height}")
                    page1TopCroppedBitmap?.recycle()
                    page1TopCroppedBitmap = null
                } else {
                    try {
                        val croppedOriginal = Bitmap.createBitmap(currentOriginalBitmap, srcX, srcY, cropWidth, cropHeight)
                        page1TopCroppedBitmap?.recycle()
                        page1TopCroppedBitmap = Bitmap.createScaledBitmap(croppedOriginal, screenWidth, targetTopHeight, true)
                        if (croppedOriginal != page1TopCroppedBitmap) croppedOriginal.recycle()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error creating page 1 top bitmap", e)
                        page1TopCroppedBitmap = null
                    }
                }
            }

            // 2. 准备第二页及以后的图片 (page2PlusImageBitmap)
            val aspect = currentOriginalBitmap.width.toFloat() / currentOriginalBitmap.height.toFloat()
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
                page2PlusImageBitmap = Bitmap.createScaledBitmap(currentOriginalBitmap, page2Width.coerceAtLeast(1), page2Height.coerceAtLeast(1), true)
            } catch (e: Exception) {
                Log.e(TAG, "Error creating page 2 image bitmap", e)
                page2PlusImageBitmap = null
            }

            Log.d(TAG, "Derived bitmaps prepared. Page1: ${page1TopCroppedBitmap?.width}x${page1TopCroppedBitmap?.height}, Page2: ${page2PlusImageBitmap?.width}x${page2PlusImageBitmap?.height}")
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
                Log.d(TAG, "drawCurrentFrame: Not drawing (isVisible=$isVisible, surfaceValid=${surfaceHolder?.surface?.isValid}, screenW=$screenWidth, screenH=$screenHeight)")
                return
            }
            var canvas: Canvas? = null
            try {
                canvas = surfaceHolder!!.lockCanvas()
                if (canvas != null) {
                    canvas.drawColor(Color.BLACK)
                    val isFirstPage = if (numPages <= 1) true else currentPageOffset < ((1.0f / numPages) / 2.0f) // Simplified first page check
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
            // Ensure page1ImageHeightRatio is used for consistent calculations
            val currentRatio = this.page1ImageHeightRatio // Use the member variable
            val topImageActualHeight = (screenHeight * currentRatio).toInt()
            Log.e(TAG, ">>>> drawPage1Layout - Using heightRatio: $currentRatio, Calculated topImageActualHeight: $topImageActualHeight <<<<")

            backgroundPaint.color = page1BackgroundColor
            canvas.drawRect(0f, topImageActualHeight.toFloat(), screenWidth.toFloat(), screenHeight.toFloat(), backgroundPaint)

            page1TopCroppedBitmap?.let { bmp ->
                if (!bmp.isRecycled) {
                    canvas.drawBitmap(bmp, 0f, 0f, imagePaint)
                } else {
                    Log.e(TAG, "page1TopCroppedBitmap was recycled before drawing in drawPage1Layout!")
                }
            } ?: run {
                backgroundPaint.color = Color.DKGRAY
                canvas.drawRect(0f, 0f, screenWidth.toFloat(), topImageActualHeight.toFloat(), backgroundPaint)
                val textPaint = Paint().apply { color = Color.WHITE; textSize = 40f; textAlign = Paint.Align.CENTER }
                canvas.drawText("请选择图片", screenWidth / 2f, topImageActualHeight / 2f, textPaint)
            }
        }

        private fun drawPage2PlusLayout(canvas: Canvas) {
            page2PlusImageBitmap?.let { bmp ->
                if (!bmp.isRecycled) {
                    val left = (screenWidth - bmp.width) / 2f
                    val top = (screenHeight - bmp.height) / 2f
                    canvas.drawBitmap(bmp, left, top, imagePaint)
                } else {
                    Log.e(TAG, "page2PlusImageBitmap was recycled before drawing in drawPage2PlusLayout!")
                }
            } ?: run {
                backgroundPaint.color = Color.DKGRAY
                canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), backgroundPaint)
                val textPaint = Paint().apply { color = Color.WHITE; textSize = 40f; textAlign = Paint.Align.CENTER }
                canvas.drawText("请选择图片", screenWidth / 2f, screenHeight / 2f, textPaint)
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