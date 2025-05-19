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

    private inner class H2WallpaperEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {

        private var surfaceHolder: SurfaceHolder? = null
        private var isVisible: Boolean = false
        private var screenWidth: Int = 0
        private var screenHeight: Int = 0

        private var engineWallpaperBitmaps: SharedWallpaperRenderer.WallpaperBitmaps? = null
        private val engineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        private var currentBitmapLoadJob: Job? = null

        // --- Configuration members, loaded from SharedPreferences or MainActivity defaults ---
        private var imageUriString: String? = null
        private var page1BackgroundColor: Int = Color.LTGRAY
        private var page1ImageHeightRatio: Float = MainActivity.DEFAULT_HEIGHT_RATIO
        private var currentScrollSensitivity: Float = MainActivity.DEFAULT_SCROLL_SENSITIVITY
        private var currentP1FocusX: Float = 0.5f
        private var currentP1FocusY: Float = 0.5f
        private var currentP1OverlayFadeRatio: Float = MainActivity.DEFAULT_P1_OVERLAY_FADE_RATIO
        private var currentBackgroundBlurRadius: Float = MainActivity.DEFAULT_BACKGROUND_BLUR_RADIUS
        // --- End Configuration members ---

        private var numPagesReportedByLauncher = 1
        private var currentPageOffset = 0f

        private val prefs: SharedPreferences by lazy {
            applicationContext.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            this.surfaceHolder = surfaceHolder
            Log.i(TAG, "H2WallpaperEngine Created. IsPreview: ${isPreview}")

            prefs.registerOnSharedPreferenceChangeListener(this)
            loadPreferencesFromStorage() // Initial load

            if (screenWidth > 0 && screenHeight > 0 && imageUriString != null) {
                Log.i(DEBUG_TAG, "onCreate: Triggering initial bitmap load if needed.")
                loadFullBitmapsAsyncIfNeeded()
            } else {
                Log.i(DEBUG_TAG, "onCreate: Conditions for initial load not met (no URI or screen size yet).")
            }
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
            Log.i(DEBUG_TAG, "onSharedPreferenceChanged: key=$key received.")
            var needsFullReload = false
            var needsP1TopUpdate = false
            var needsRedrawOnly = false

            // Store old values to compare after reloading all preferences
            val oldImageUri = imageUriString
            val oldP1FocusX = currentP1FocusX
            val oldP1FocusY = currentP1FocusY
            val oldPage1ImageHeightRatio = page1ImageHeightRatio
            val oldScrollSensitivity = currentScrollSensitivity
            val oldP1OverlayFadeRatio = currentP1OverlayFadeRatio
            val oldBackgroundBlurRadius = currentBackgroundBlurRadius
            val oldPage1BackgroundColor = page1BackgroundColor

            loadPreferencesFromStorage() // Load all preferences to get the new values

            when (key) {
                MainActivity.KEY_IMAGE_URI -> {
                    if (oldImageUri != imageUriString) {
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
                MainActivity.KEY_SCROLL_SENSITIVITY -> {
                    if (oldScrollSensitivity != currentScrollSensitivity) {
                        Log.i(DEBUG_TAG, "Preference changed: Scroll Sensitivity. Triggering redraw.")
                        needsRedrawOnly = true
                    }
                }
                MainActivity.KEY_P1_OVERLAY_FADE_RATIO -> {
                    if (oldP1OverlayFadeRatio != currentP1OverlayFadeRatio) {
                        Log.i(DEBUG_TAG, "Preference changed: P1 Overlay Fade Ratio. Triggering redraw.")
                        needsRedrawOnly = true
                    }
                }
                MainActivity.KEY_BACKGROUND_BLUR_RADIUS -> {
                    if (oldBackgroundBlurRadius != currentBackgroundBlurRadius) {
                        Log.i(DEBUG_TAG, "Preference changed: Background Blur Radius. Triggering full reload.")
                        needsFullReload = true // Blur change requires regenerating scrolling background
                    }
                }
                MainActivity.KEY_BACKGROUND_COLOR -> {
                    if (oldPage1BackgroundColor != page1BackgroundColor) {
                        Log.i(DEBUG_TAG, "Preference changed: Background Color. Triggering redraw.")
                        needsRedrawOnly = true
                    }
                }
            }

            if (needsFullReload) {
                loadFullBitmapsAsyncIfNeeded()
            } else if (needsP1TopUpdate) {
                if (engineWallpaperBitmaps?.sourceSampledBitmap != null && imageUriString != null) {
                    updateTopCroppedBitmapAsync()
                } else if (imageUriString != null) { // If source bitmap is missing, but we have URI, do full reload
                    Log.w(DEBUG_TAG, "P1 top update requested, but sourceSampledBitmap is null. Forcing full reload.")
                    loadFullBitmapsAsyncIfNeeded()
                }
            } else if (needsRedrawOnly) {
                if (isVisible) drawCurrentFrame()
            }
        }


        private fun loadPreferencesFromStorage() {
            imageUriString = prefs.getString(MainActivity.KEY_IMAGE_URI, null)
            page1BackgroundColor = prefs.getInt(MainActivity.KEY_BACKGROUND_COLOR, Color.LTGRAY) // Default color if not set
            page1ImageHeightRatio = prefs.getFloat(MainActivity.KEY_IMAGE_HEIGHT_RATIO, MainActivity.DEFAULT_HEIGHT_RATIO)
            currentScrollSensitivity = prefs.getFloat(MainActivity.KEY_SCROLL_SENSITIVITY, MainActivity.DEFAULT_SCROLL_SENSITIVITY)
            currentP1FocusX = prefs.getFloat(MainActivity.KEY_P1_FOCUS_X, 0.5f)
            currentP1FocusY = prefs.getFloat(MainActivity.KEY_P1_FOCUS_Y, 0.5f)

            // Load new preferences with MainActivity defaults as fallbacks
            currentP1OverlayFadeRatio = prefs.getFloat(MainActivity.KEY_P1_OVERLAY_FADE_RATIO, MainActivity.DEFAULT_P1_OVERLAY_FADE_RATIO)
            currentBackgroundBlurRadius = prefs.getFloat(MainActivity.KEY_BACKGROUND_BLUR_RADIUS, MainActivity.DEFAULT_BACKGROUND_BLUR_RADIUS)

            Log.i(DEBUG_TAG, "Preferences loaded/reloaded (Service): URI=$imageUriString, Color=$page1BackgroundColor, Ratio=$page1ImageHeightRatio, Sensitivity=$currentScrollSensitivity, Focus=($currentP1FocusX, $currentP1FocusY), P1FadeRatio=$currentP1OverlayFadeRatio, BlurRadius=$currentBackgroundBlurRadius")
        }

        private fun loadFullBitmapsAsyncIfNeeded() {
            if (imageUriString != null && screenWidth > 0 && screenHeight > 0) {
                // Always reload if source is null, or job is not active.
                // This handles cases where URI is same, but other params (like blur) that affect full bitmap set might have changed.
                if (currentBitmapLoadJob == null || !currentBitmapLoadJob!!.isActive || engineWallpaperBitmaps?.sourceSampledBitmap == null) {
                    Log.i(DEBUG_TAG, "loadFullBitmapsAsyncIfNeeded: Conditions met, calling loadFullBitmapsAsync.")
                    loadFullBitmapsAsync()
                } else {
                    Log.i(DEBUG_TAG, "loadFullBitmapsAsyncIfNeeded: Bitmaps seem to be loaded or loading. Redrawing if visible.")
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
                Log.i(DEBUG_TAG, "SurfaceChanged: Dimensions valid. Ensuring bitmaps are loaded for new dimensions.")
                // Parameters like blur radius, focus, height ratio etc. might affect how bitmaps are scaled/cropped for new screen size.
                // So, a full reload is generally appropriate here if an image URI is set.
                if (imageUriString != null) {
                    loadFullBitmapsAsyncIfNeeded()
                } else {
                    if (isVisible) drawCurrentFrame() // Draw placeholder if no image
                }
            } else {
                currentBitmapLoadJob?.cancel(); currentBitmapLoadJob = null
                engineWallpaperBitmaps?.recycleInternals(); engineWallpaperBitmaps = null
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            isVisible = false
            Log.i(TAG, "H2WallpaperEngine Surface destroyed.")
            // Resources are cleaned up in onDestroy
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.isVisible = visible
            Log.i(TAG, "Visibility changed: $visible. IsPreview: ${isPreview}")
            if (visible) {
                // When becoming visible, ensure everything is up-to-date
                // Preferences should be fresh due to listener, but a check won't hurt
                // loadPreferencesFromStorage() // Usually not needed if listener works
                if (engineWallpaperBitmaps?.sourceSampledBitmap == null && imageUriString != null && screenWidth > 0 && screenHeight > 0) {
                    Log.i(DEBUG_TAG, "VisibilityChanged (visible=true): Source bitmap missing. Triggering full load.")
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
                    updateScrollingBackgroundAsync()
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
            currentBitmapLoadJob?.cancel()
            engineWallpaperBitmaps?.recycleInternals() // Always recycle before new load attempt
            engineWallpaperBitmaps = null

            val uriStringToLoad = imageUriString
            if (uriStringToLoad == null || screenWidth <= 0 || screenHeight <= 0) {
                Log.w(DEBUG_TAG, "loadFullBitmapsAsync: Preconditions not met. URI: $uriStringToLoad, Screen: ${screenWidth}x$screenHeight")
                if (isVisible) drawCurrentFrame() // Draw placeholder
                return
            }
            val uri = Uri.parse(uriStringToLoad)
            Log.i(DEBUG_TAG, "loadFullBitmapsAsync: Starting for URI: $uri. Focus:($currentP1FocusX, $currentP1FocusY), Blur:$currentBackgroundBlurRadius")

            if (isVisible) {
                drawCurrentFrame() // Draw placeholder immediately
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
                    Log.i(DEBUG_TAG, "loadFullBitmapsAsync (coroutine): Using $pagesForBackground pages for background. Sensitivity: $currentScrollSensitivity, Blur: $currentBackgroundBlurRadius")

                    newBitmapsHolder = withContext(Dispatchers.IO) {
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
                            blurRadiusForBackground = currentBackgroundBlurRadius // Pass current blur radius
                        )
                    }
                    ensureActive()

                    if (this@H2WallpaperEngine.imageUriString == uriStringToLoad) {
                        engineWallpaperBitmaps = newBitmapsHolder
                        Log.i(DEBUG_TAG, "Full bitmaps loaded successfully for $uri.")
                    } else {
                        Log.w(DEBUG_TAG, "URI changed during full load for $uri. Current engine URI: ${this@H2WallpaperEngine.imageUriString}. Discarding.")
                        newBitmapsHolder?.recycleInternals()
                        if (this@H2WallpaperEngine.imageUriString == null) engineWallpaperBitmaps = null
                    }
                } catch (e: CancellationException) {
                    Log.d(DEBUG_TAG, "Full bitmap loading CANCELLED for $uri.")
                    newBitmapsHolder?.recycleInternals()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in loadFullBitmapsAsync for $uri", e)
                    newBitmapsHolder?.recycleInternals()
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
            val currentSourceBitmap = engineWallpaperBitmaps?.sourceSampledBitmap
            if (currentSourceBitmap == null || screenWidth <= 0 || screenHeight <= 0 || imageUriString == null) {
                Log.w(DEBUG_TAG, "updateTopCroppedBitmapAsync: Preconditions not met. Triggering full reload if URI exists.")
                if (imageUriString != null) loadFullBitmapsAsyncIfNeeded()
                else if(isVisible) drawCurrentFrame()
                return
            }
            Log.i(DEBUG_TAG, "updateTopCroppedBitmapAsync: Starting for ratio $page1ImageHeightRatio, Focus($currentP1FocusX, $currentP1FocusY)")

            // Use a separate job for this to avoid interfering with fullBitmapLoadJob if it's for source
            val p1TopUpdateJob = engineScope.launch {
                var newTopCropped: Bitmap? = null
                val oldTopCropped = engineWallpaperBitmaps?.page1TopCroppedBitmap // Cache to recycle if different
                try {
                    ensureActive()
                    newTopCropped = withContext(Dispatchers.Default) {
                        ensureActive()
                        SharedWallpaperRenderer.preparePage1TopCroppedBitmap(
                            currentSourceBitmap,
                            screenWidth,
                            screenHeight,
                            page1ImageHeightRatio,
                            currentP1FocusX,
                            currentP1FocusY
                        )
                    }
                    ensureActive()
                    if (this@H2WallpaperEngine.imageUriString != null && engineWallpaperBitmaps?.sourceSampledBitmap == currentSourceBitmap) {
                        if (oldTopCropped != newTopCropped) oldTopCropped?.recycle() // Recycle old only if new one is different
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
                        engineWallpaperBitmaps?.page1TopCroppedBitmap = null
                    }
                } finally {
                    if (isVisible && this@H2WallpaperEngine.imageUriString != null) drawCurrentFrame()
                }
            }
        }

        private fun updateScrollingBackgroundAsync() {
            val currentSource = engineWallpaperBitmaps?.sourceSampledBitmap
            if (currentSource == null || imageUriString == null || screenWidth <= 0 || screenHeight <= 0) {
                Log.w(DEBUG_TAG, "updateScrollingBackgroundAsync: Preconditions not met. Triggering full reload if URI exists.")
                if (imageUriString != null) loadFullBitmapsAsyncIfNeeded()
                else if(isVisible) drawCurrentFrame()
                return
            }
            val pagesForBg = if (!isPreview && numPagesReportedByLauncher <= 1) {
                DEFAULT_VIRTUAL_PAGES_FOR_SCROLLING
            } else {
                numPagesReportedByLauncher.coerceAtLeast(1)
            }
            Log.i(DEBUG_TAG, "updateScrollingBackgroundAsync: Starting for numPages $pagesForBg, Blur: $currentBackgroundBlurRadius")

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
                            pagesForBg, currentBackgroundBlurRadius // Use current blur radius
                        )
                    }
                    ensureActive()
                    if (this@H2WallpaperEngine.imageUriString != null && engineWallpaperBitmaps?.sourceSampledBitmap == currentSource) {
                        if (oldScrollingBitmap != newScrollingPair?.first) oldScrollingBitmap?.recycle()
                        if (oldBlurredBitmap != newScrollingPair?.second) oldBlurredBitmap?.recycle()
                        engineWallpaperBitmaps?.scrollingBackgroundBitmap = newScrollingPair?.first
                        engineWallpaperBitmaps?.blurredScrollingBackgroundBitmap = newScrollingPair?.second
                        Log.i(DEBUG_TAG, "Scrolling background updated. New width: ${newScrollingPair?.first?.width}")
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
                    if (isVisible && this@H2WallpaperEngine.imageUriString != null) drawCurrentFrame()
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
                    if (currentWpBitmaps?.sourceSampledBitmap != null && currentWpBitmaps.page1TopCroppedBitmap != null) {
                        val pagesForConfig = if (!isPreview && numPagesReportedByLauncher <= 1) {
                            DEFAULT_VIRTUAL_PAGES_FOR_SCROLLING
                        } else {
                            numPagesReportedByLauncher.coerceAtLeast(1)
                        }
                        val config = SharedWallpaperRenderer.WallpaperConfig(
                            screenWidth, screenHeight, page1BackgroundColor, page1ImageHeightRatio,
                            currentPageOffset, pagesForConfig,
                            p1OverlayFadeTransitionRatio = currentP1OverlayFadeRatio, // Use member variable
                            scrollSensitivityFactor = this.currentScrollSensitivity    // Use member variable
                        )
                        SharedWallpaperRenderer.drawFrame(canvas, config, currentWpBitmaps)
                    } else {
                        SharedWallpaperRenderer.drawPlaceholder(canvas, screenWidth, screenHeight,
                            if (imageUriString != null && (currentBitmapLoadJob?.isActive == true)) "壁纸加载中..."
                            else if (imageUriString == null) "请选择图片"
                            else "图片资源准备中..."
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during drawFrame", e)
            } finally {
                if (canvas != null) {
                    try { surfaceHolder!!.unlockCanvasAndPost(canvas) }
                    catch (e: Exception) { Log.e(TAG, "Error unlocking canvas", e) }
                }
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            engineScope.cancel() // Cancel all coroutines
            engineWallpaperBitmaps?.recycleInternals()
            engineWallpaperBitmaps = null
            Log.i(TAG, "H2WallpaperEngine destroyed.")
        }
    }
}