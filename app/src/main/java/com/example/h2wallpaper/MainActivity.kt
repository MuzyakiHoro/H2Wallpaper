package com.example.h2wallpaper

import android.Manifest
import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.roundToInt

// 显式导入 WallpaperConfigConstants 对象本身
import com.example.h2wallpaper.WallpaperConfigConstants
// 显式导入 FocusParams 嵌套对象
import com.example.h2wallpaper.WallpaperConfigConstants.FocusParams

class MainActivity : AppCompatActivity() {

    // UI 控件声明
    private lateinit var btnSelectImage: Button
    private lateinit var colorPaletteContainer: LinearLayout
    private lateinit var btnSetWallpaper: Button
    private lateinit var wallpaperPreviewView: WallpaperPreviewView
    private lateinit var controlsContainer: LinearLayout
    private lateinit var heightControlsContainer: LinearLayout
    private lateinit var btnHeightReset: Button
    private lateinit var btnHeightIncrease: Button
    private lateinit var btnHeightDecrease: Button
    private lateinit var btnCustomizeForeground: Button
    private lateinit var imageLoadingProgressBar: ProgressBar
    private lateinit var btnAdvancedSettings: Button

    // 状态变量
    private var selectedImageUri: Uri? = null
    private var selectedBackgroundColor: Int = WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR
    private var originalBitmapForColorExtraction: Bitmap? = null
    var page1ImageHeightRatio: Float = WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO
    private var currentP1FocusX: Float = WallpaperConfigConstants.DEFAULT_P1_FOCUS_X
    private var currentP1FocusY: Float = WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y

    // 配置参数变量
    private var currentScrollSensitivity: Float =
        WallpaperConfigConstants.DEFAULT_SCROLL_SENSITIVITY
    private var currentP1OverlayFadeRatio: Float =
        WallpaperConfigConstants.DEFAULT_P1_OVERLAY_FADE_RATIO
    private var currentBackgroundBlurRadius: Float =
        WallpaperConfigConstants.DEFAULT_BACKGROUND_BLUR_RADIUS
    private var currentBackgroundInitialOffset: Float =
        WallpaperConfigConstants.DEFAULT_BACKGROUND_INITIAL_OFFSET
    private var currentP2BackgroundFadeInRatio: Float =
        WallpaperConfigConstants.DEFAULT_P2_BACKGROUND_FADE_IN_RATIO
    private var currentBlurDownscaleFactorInt: Int =
        WallpaperConfigConstants.DEFAULT_BLUR_DOWNSCALE_FACTOR_INT
    private var currentBlurIterations: Int = WallpaperConfigConstants.DEFAULT_BLUR_ITERATIONS
    private var currentP1ShadowRadius: Float = WallpaperConfigConstants.DEFAULT_P1_SHADOW_RADIUS
    private var currentP1ShadowDx: Float = WallpaperConfigConstants.DEFAULT_P1_SHADOW_DX
    private var currentP1ShadowDy: Float = WallpaperConfigConstants.DEFAULT_P1_SHADOW_DY
    private var currentP1ShadowColor: Int = WallpaperConfigConstants.DEFAULT_P1_SHADOW_COLOR
    private var currentP1ImageBottomFadeHeight: Float =
        WallpaperConfigConstants.DEFAULT_P1_IMAGE_BOTTOM_FADE_HEIGHT
    private var currentImageContentVersion: Long =
        WallpaperConfigConstants.DEFAULT_IMAGE_CONTENT_VERSION


    private val INTERNAL_IMAGE_FILENAME = "h2_wallpaper_internal_image.jpg"
    private val INTERNAL_IMAGE_FOLDER = "wallpaper_images"


    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { externalUri ->
                    Log.i(TAG, "Image selected from picker: $externalUri")
                    copyImageToInternalStorage(externalUri)
                } ?: run {
                    btnCustomizeForeground.isEnabled = (this.selectedImageUri != null)
                    Toast.makeText(
                        this,
                        getString(R.string.image_selection_failed_toast) + " (No data URI)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                Log.d(TAG, "Image selection cancelled or failed, resultCode: ${result.resultCode}")
            }
        }

    private val focusActivityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                currentP1FocusX = data?.getFloatExtra(
                    FocusParams.RESULT_FOCUS_X, WallpaperConfigConstants.DEFAULT_P1_FOCUS_X
                ) ?: WallpaperConfigConstants.DEFAULT_P1_FOCUS_X
                currentP1FocusY = data?.getFloatExtra(
                    FocusParams.RESULT_FOCUS_Y, WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y
                ) ?: WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y

                savePreferences()
                wallpaperPreviewView.setNormalizedFocus(currentP1FocusX, currentP1FocusY)

                Toast.makeText(
                    this, "焦点已更新: X=${"%.2f".format(currentP1FocusX)}, Y=${
                        "%.2f".format(
                            currentP1FocusY
                        )
                    }", Toast.LENGTH_LONG
                ).show()

            } else if (result.resultCode == Activity.RESULT_CANCELED) {
                Toast.makeText(this, "自定义焦点已取消", Toast.LENGTH_SHORT).show()
            }
        }

    companion object {
        private const val PERMISSION_REQUEST_READ_MEDIA_IMAGES = 1001
        private const val TAG = "H2WallpaperMain"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }

        setContentView(R.layout.activity_main)
        btnSelectImage = findViewById(R.id.btnSelectImage)
        colorPaletteContainer = findViewById(R.id.colorPaletteContainer)
        btnSetWallpaper = findViewById(R.id.btnSetWallpaper)
        wallpaperPreviewView = findViewById(R.id.wallpaperPreviewView)
        controlsContainer = findViewById(R.id.controlsContainer)
        heightControlsContainer = findViewById(R.id.heightControlsContainer)
        btnHeightReset = findViewById(R.id.btnHeightReset)
        btnHeightIncrease = findViewById(R.id.btnHeightIncrease)
        btnHeightDecrease = findViewById(R.id.btnHeightDecrease)
        btnCustomizeForeground = findViewById(R.id.btnCustomizeForeground)
        imageLoadingProgressBar = findViewById(R.id.imageLoadingProgressBar)
        btnAdvancedSettings = findViewById(R.id.btnAdvancedSettings)


        loadAndApplyPreferencesAndInitState()

        val rootLayoutForInsets: View = findViewById(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayoutForInsets) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (btnSetWallpaper.layoutParams as? ConstraintLayout.LayoutParams)?.apply {
                bottomMargin = systemBars.bottom + (16 * resources.displayMetrics.density).toInt()
            }
            btnSetWallpaper.requestLayout()
            insets
        }

        btnSelectImage.setOnClickListener { checkAndRequestReadMediaImagesPermission() }

        btnSetWallpaper.setOnClickListener {
            if (selectedImageUri != null) {
                savePreferences()
                promptToSetWallpaper()
            } else {
                Toast.makeText(
                    this, getString(R.string.please_select_image_first_toast), Toast.LENGTH_SHORT
                ).show()
            }
        }
        btnAdvancedSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
        btnHeightReset.setOnClickListener { updatePage1ImageHeightRatio(WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO) }
        btnHeightIncrease.setOnClickListener { updatePage1ImageHeightRatio((page1ImageHeightRatio + WallpaperConfigConstants.HEIGHT_RATIO_STEP)) }
        btnHeightDecrease.setOnClickListener { updatePage1ImageHeightRatio((page1ImageHeightRatio - WallpaperConfigConstants.HEIGHT_RATIO_STEP)) }


        btnCustomizeForeground.setOnClickListener {
            Log.d("MainActivityFocus", "btnCustomizeForeground clicked")
            if (selectedImageUri != null) {
                Log.d("MainActivityFocus", "selectedImageUri is: ${selectedImageUri.toString()}")
                val intent = Intent(this, FocusActivity::class.java).apply {
                    putExtra(FocusParams.EXTRA_IMAGE_URI, selectedImageUri)

                    val previewWidth = wallpaperPreviewView.width.takeIf { it > 0 }
                        ?: resources.displayMetrics.widthPixels
                    val previewHeight = wallpaperPreviewView.height.takeIf { it > 0 }
                        ?: resources.displayMetrics.heightPixels
                    val currentP1Height = (previewHeight * page1ImageHeightRatio)
                    val currentP1AspectRatio = if (previewWidth > 0 && currentP1Height > 0) {
                        previewWidth.toFloat() / currentP1Height.toFloat()
                    } else {
                        Log.w(
                            "MainActivityFocus",
                            "PreviewView not measured or p1Height is 0, using default aspect ratio 16/9"
                        )
                        16f / 9f
                    }
                    putExtra(FocusParams.EXTRA_ASPECT_RATIO, currentP1AspectRatio)
                    putExtra(FocusParams.EXTRA_INITIAL_FOCUS_X, currentP1FocusX)
                    putExtra(FocusParams.EXTRA_INITIAL_FOCUS_Y, currentP1FocusY)
                }
                focusActivityLauncher.launch(intent)
            } else {
                Log.w("MainActivityFocus", "selectedImageUri is NULL, cannot start FocusActivity")
                Toast.makeText(this, "请先选择一张图片", Toast.LENGTH_SHORT).show()
            }
        }
        var controlsAreVisible = true
        wallpaperPreviewView.setOnClickListener {
            controlsAreVisible = !controlsAreVisible
            val targetAlpha = if (controlsAreVisible) 1f else 0f
            val targetVisibility = if (controlsAreVisible) View.VISIBLE else View.GONE

            animateViewVisibility(controlsContainer, targetAlpha, targetVisibility)
            animateViewVisibility(heightControlsContainer, targetAlpha, targetVisibility)
            animateViewVisibility(btnSetWallpaper, targetAlpha, targetVisibility)
            animateViewVisibility(btnSelectImage, targetAlpha, targetVisibility)

            btnAdvancedSettings?.let { animateViewVisibility(it, targetAlpha, targetVisibility) }

            if (selectedImageUri != null) {
                animateViewVisibility(btnCustomizeForeground, targetAlpha, targetVisibility)
            } else {
                btnCustomizeForeground.visibility = View.GONE
                btnCustomizeForeground.alpha = 0f
            }
        }
        Log.d(TAG, "onCreate: Activity setup complete")
    }

    private fun loadAndApplyPreferencesAndInitState() {
        val prefs: SharedPreferences =
            getSharedPreferences(WallpaperConfigConstants.PREFS_NAME, Context.MODE_PRIVATE)
        selectedBackgroundColor = prefs.getInt(
            WallpaperConfigConstants.KEY_BACKGROUND_COLOR,
            WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR
        )
        page1ImageHeightRatio = prefs.getFloat(
            WallpaperConfigConstants.KEY_IMAGE_HEIGHT_RATIO,
            WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO
        )
        currentP1FocusX = prefs.getFloat(
            WallpaperConfigConstants.KEY_P1_FOCUS_X, WallpaperConfigConstants.DEFAULT_P1_FOCUS_X
        )
        currentP1FocusY = prefs.getFloat(
            WallpaperConfigConstants.KEY_P1_FOCUS_Y, WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y
        )

        currentScrollSensitivity = prefs.getInt(
            WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY,
            WallpaperConfigConstants.DEFAULT_SCROLL_SENSITIVITY_INT
        ) / 10.0f
        currentP1OverlayFadeRatio = prefs.getInt(
            WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO,
            WallpaperConfigConstants.DEFAULT_P1_OVERLAY_FADE_RATIO_INT
        ) / 100.0f
        currentBackgroundBlurRadius = prefs.getInt(
            WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS,
            WallpaperConfigConstants.DEFAULT_BACKGROUND_BLUR_RADIUS_INT
        ).toFloat()
        currentBackgroundInitialOffset = prefs.getInt(
            WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET,
            WallpaperConfigConstants.DEFAULT_BACKGROUND_INITIAL_OFFSET_INT
        ) / 10.0f
        currentP2BackgroundFadeInRatio = prefs.getInt(
            WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO,
            WallpaperConfigConstants.DEFAULT_P2_BACKGROUND_FADE_IN_RATIO_INT
        ) / 100.0f
        currentBlurDownscaleFactorInt = prefs.getInt(
            WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR,
            WallpaperConfigConstants.DEFAULT_BLUR_DOWNSCALE_FACTOR_INT
        )
        currentBlurIterations = prefs.getInt(
            WallpaperConfigConstants.KEY_BLUR_ITERATIONS,
            WallpaperConfigConstants.DEFAULT_BLUR_ITERATIONS
        )

        currentP1ShadowRadius = prefs.getInt(
            WallpaperConfigConstants.KEY_P1_SHADOW_RADIUS,
            WallpaperConfigConstants.DEFAULT_P1_SHADOW_RADIUS_INT
        ).toFloat()
        currentP1ShadowDx = prefs.getInt(
            WallpaperConfigConstants.KEY_P1_SHADOW_DX,
            WallpaperConfigConstants.DEFAULT_P1_SHADOW_DX_INT
        ).toFloat()
        currentP1ShadowDy = prefs.getInt(
            WallpaperConfigConstants.KEY_P1_SHADOW_DY,
            WallpaperConfigConstants.DEFAULT_P1_SHADOW_DY_INT
        ).toFloat()
        currentP1ShadowColor = prefs.getInt(
            WallpaperConfigConstants.KEY_P1_SHADOW_COLOR,
            WallpaperConfigConstants.DEFAULT_P1_SHADOW_COLOR
        )
        currentP1ImageBottomFadeHeight = prefs.getInt(
            WallpaperConfigConstants.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT,
            WallpaperConfigConstants.DEFAULT_P1_IMAGE_BOTTOM_FADE_HEIGHT_INT
        ).toFloat()
        currentImageContentVersion = prefs.getLong(
            WallpaperConfigConstants.KEY_IMAGE_CONTENT_VERSION,
            WallpaperConfigConstants.DEFAULT_IMAGE_CONTENT_VERSION
        )


        val internalImageUriString = prefs.getString(WallpaperConfigConstants.KEY_IMAGE_URI, null)
        Log.i(
            TAG,
            "Loaded preferences: InternalURI=$internalImageUriString, ... P1ShadowRadius=$currentP1ShadowRadius, ..."
        )


        wallpaperPreviewView.setConfigValues(
            scrollSensitivity = currentScrollSensitivity,
            p1OverlayFadeRatio = currentP1OverlayFadeRatio,
            backgroundBlurRadius = currentBackgroundBlurRadius,
            snapAnimationDurationMs = WallpaperConfigConstants.DEFAULT_PREVIEW_SNAP_DURATION_MS,
            normalizedInitialBgScrollOffset = currentBackgroundInitialOffset,
            p2BackgroundFadeInRatio = currentP2BackgroundFadeInRatio,
            blurDownscaleFactor = currentBlurDownscaleFactorInt / 100.0f,
            blurIterations = currentBlurIterations,
            p1ShadowRadius = currentP1ShadowRadius,
            p1ShadowDx = currentP1ShadowDx,
            p1ShadowDy = currentP1ShadowDy,
            p1ShadowColor = currentP1ShadowColor,
            p1ImageBottomFadeHeight = currentP1ImageBottomFadeHeight
        )

        wallpaperPreviewView.setPage1ImageHeightRatio(page1ImageHeightRatio)
        wallpaperPreviewView.setNormalizedFocus(currentP1FocusX, currentP1FocusY)
        wallpaperPreviewView.setSelectedBackgroundColor(selectedBackgroundColor)


        if (internalImageUriString != null) {
            val internalUri = Uri.parse(internalImageUriString)
            var fileExists = false
            try {
                if (internalUri.scheme == "content") {
                    contentResolver.openInputStream(internalUri)?.use { fileExists = true }
                } else if (internalUri.scheme == "file") {
                    val path = internalUri.path
                    if (path != null) fileExists = File(path).exists()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking existence of internal file $internalUri: ${e.message}")
                fileExists = false
            }

            if (fileExists) {
                selectedImageUri = internalUri
                wallpaperPreviewView.setImageUri(selectedImageUri)
                btnCustomizeForeground.isEnabled = true
                extractColorsFromBitmapUri(selectedImageUri!!, false)
            } else {
                handleFailedImageAccess(internalUri, "之前选择的图片文件已丢失，请重新选择")
            }
        } else {
            selectedImageUri = null
            wallpaperPreviewView.setImageUri(null)
            btnCustomizeForeground.isEnabled = false
            setDefaultColorPaletteAndUpdatePreview()
        }
        Log.d(TAG, "Preferences loaded and initial state set for PreviewView.")
    }

    private fun savePreferences() {
        val prefs: SharedPreferences =
            getSharedPreferences(WallpaperConfigConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putInt(WallpaperConfigConstants.KEY_BACKGROUND_COLOR, selectedBackgroundColor)
        editor.putFloat(WallpaperConfigConstants.KEY_IMAGE_HEIGHT_RATIO, page1ImageHeightRatio)
        editor.putFloat(WallpaperConfigConstants.KEY_P1_FOCUS_X, currentP1FocusX)
        editor.putFloat(WallpaperConfigConstants.KEY_P1_FOCUS_Y, currentP1FocusY)

        editor.putInt(
            WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY, (currentScrollSensitivity * 10).toInt()
        )
        editor.putInt(
            WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO,
            (currentP1OverlayFadeRatio * 100).toInt()
        )
        editor.putInt(
            WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS,
            currentBackgroundBlurRadius.roundToInt()
        )
        editor.putInt(
            WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET,
            (currentBackgroundInitialOffset * 10).toInt()
        )
        editor.putInt(
            WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO,
            (currentP2BackgroundFadeInRatio * 100).toInt()
        )
        editor.putInt(
            WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR, currentBlurDownscaleFactorInt
        )
        editor.putInt(WallpaperConfigConstants.KEY_BLUR_ITERATIONS, currentBlurIterations)

        editor.putInt(
            WallpaperConfigConstants.KEY_P1_SHADOW_RADIUS, currentP1ShadowRadius.roundToInt()
        )
        editor.putInt(WallpaperConfigConstants.KEY_P1_SHADOW_DX, currentP1ShadowDx.roundToInt())
        editor.putInt(WallpaperConfigConstants.KEY_P1_SHADOW_DY, currentP1ShadowDy.roundToInt())
        editor.putInt(WallpaperConfigConstants.KEY_P1_SHADOW_COLOR, currentP1ShadowColor)
        editor.putInt(
            WallpaperConfigConstants.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT,
            currentP1ImageBottomFadeHeight.roundToInt()
        )


        selectedImageUri?.let {
            editor.putString(
                WallpaperConfigConstants.KEY_IMAGE_URI, it.toString()
            )
        } ?: editor.remove(WallpaperConfigConstants.KEY_IMAGE_URI)
        editor.putLong(
            WallpaperConfigConstants.KEY_IMAGE_CONTENT_VERSION, System.currentTimeMillis()
        )

        editor.apply()
        Log.d(
            TAG,
            "Preferences saved: ... P1ShadowRadius (as Int)=${currentP1ShadowRadius.roundToInt()}, P1BottomFadeHeight (as Int)=${currentP1ImageBottomFadeHeight.roundToInt()} ..."
        )
    }

    private fun copyImageToInternalStorage(sourceUri: Uri) {
        imageLoadingProgressBar.visibility = View.VISIBLE
        btnSelectImage.isEnabled = false

        lifecycleScope.launch {
            val internalFileUri = saveImageToInternalAppStorage(sourceUri)
            imageLoadingProgressBar.visibility = View.GONE
            btnSelectImage.isEnabled = true

            if (internalFileUri != null) {
                Log.i(TAG, "Image copied successfully to internal URI: $internalFileUri")
                if (selectedImageUri != null && selectedImageUri != internalFileUri) {
                    deleteInternalImage(selectedImageUri!!)
                }
                selectedImageUri = internalFileUri
                currentP1FocusX = WallpaperConfigConstants.DEFAULT_P1_FOCUS_X
                currentP1FocusY = WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y
                savePreferences()

                wallpaperPreviewView.setNormalizedFocus(currentP1FocusX, currentP1FocusY)
                wallpaperPreviewView.setImageUri(selectedImageUri, true)

                btnCustomizeForeground.isEnabled = true
                extractColorsFromBitmapUri(selectedImageUri!!, true)
            } else {
                Log.e(TAG, "Failed to copy image to internal storage.")
                Toast.makeText(applicationContext, "图片复制失败", Toast.LENGTH_SHORT).show()
                btnCustomizeForeground.isEnabled = (this@MainActivity.selectedImageUri != null)
            }
        }
    }

    private suspend fun saveImageToInternalAppStorage(sourceUri: Uri): Uri? =
        withContext(Dispatchers.IO) {
            var inputStream: InputStream? = null
            var outputStream: OutputStream? = null
            try {
                inputStream = contentResolver.openInputStream(sourceUri)
                if (inputStream == null) {
                    Log.e(TAG, "Failed to open input stream from source URI: $sourceUri")
                    return@withContext null
                }
                val imageDir = File(filesDir, INTERNAL_IMAGE_FOLDER)
                if (!imageDir.exists()) {
                    imageDir.mkdirs()
                }
                val internalFile = File(imageDir, INTERNAL_IMAGE_FILENAME)
                if (internalFile.exists()) {
                    internalFile.delete()
                }
                outputStream = FileOutputStream(internalFile)
                val buffer = ByteArray(4 * 1024)
                var read: Int
                while (inputStream.read(buffer).also { read = it } != -1) {
                    outputStream.write(buffer, 0, read)
                }
                outputStream.flush()
                Log.i(TAG, "Image saved to internal file: ${internalFile.absolutePath}")
                return@withContext FileProvider.getUriForFile(
                    applicationContext, "${applicationContext.packageName}.provider", internalFile
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error saving image to internal storage", e)
                return@withContext null
            } finally {
                try {
                    inputStream?.close()
                    outputStream?.close()
                } catch (ioe: IOException) {
                    Log.e(TAG, "Error closing streams", ioe)
                }
            }
        }

    private fun deleteInternalImage(internalFileUri: Uri?) {
        internalFileUri ?: return
        if (internalFileUri.scheme == "content" && internalFileUri.authority == "${applicationContext.packageName}.provider") {
            val imageDir = File(filesDir, INTERNAL_IMAGE_FOLDER)
            val internalFile = File(imageDir, INTERNAL_IMAGE_FILENAME)
            if (internalFile.exists()) {
                if (internalFile.delete()) Log.i(
                    TAG, "Deleted old internal image file: ${internalFile.path}"
                )
                else Log.w(TAG, "Failed to delete old internal image file: ${internalFile.path}")
            }
        } else if (internalFileUri.scheme == "file") {
            val filePath = internalFileUri.path
            if (filePath != null) {
                val file = File(filePath)
                if (file.exists() && file.isFile && file.parentFile?.name == INTERNAL_IMAGE_FOLDER && file.parentFile?.parentFile == filesDir) {
                    if (file.delete()) Log.i(TAG, "Deleted old internal image file: $filePath")
                    else Log.w(TAG, "Failed to delete old internal image file: $filePath")
                } else {
                    Log.w(
                        TAG,
                        "Old internal image file path is not as expected or does not exist: $filePath"
                    )
                }
            }
        }
    }

    private fun animateViewVisibility(view: View, targetAlpha: Float, targetVisibilityIfGone: Int) {
        if (targetAlpha == 1f && view.visibility != View.VISIBLE) {
            view.alpha = 0f
            view.visibility = View.VISIBLE
        }
        view.animate().alpha(targetAlpha).setDuration(200).withEndAction {
            if (targetAlpha == 0f) {
                view.visibility = targetVisibilityIfGone
            }
        }.start()
    }

    private fun updatePage1ImageHeightRatio(newRatio: Float) {
        val clampedRatio = newRatio.coerceIn(
            WallpaperConfigConstants.MIN_HEIGHT_RATIO, WallpaperConfigConstants.MAX_HEIGHT_RATIO
        )
        if (page1ImageHeightRatio == clampedRatio) return

        page1ImageHeightRatio = clampedRatio
        Log.d(TAG, "updatePage1ImageHeightRatio: New ratio $page1ImageHeightRatio")
        wallpaperPreviewView.setPage1ImageHeightRatio(page1ImageHeightRatio)
        savePreferences()
    }

    private fun checkAndRequestReadMediaImagesPermission() {
        val permissionToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(
                this, permissionToRequest
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(permissionToRequest), PERMISSION_REQUEST_READ_MEDIA_IMAGES
            )
        } else {
            openGallery()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_READ_MEDIA_IMAGES) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openGallery()
            } else {
                Toast.makeText(this, getString(R.string.permission_needed_toast), Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        try {
            pickImageLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch gallery picker", e)
            Toast.makeText(
                this, getString(R.string.image_selection_failed_toast), Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun handleFailedImageAccess(uriFailed: Uri?, message: String = "图片访问失败") {
        Log.e(TAG, "Failed to access internal URI: $uriFailed. Clearing it. Message: $message")
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        selectedImageUri = null
        val prefs: SharedPreferences =
            getSharedPreferences(WallpaperConfigConstants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(WallpaperConfigConstants.KEY_IMAGE_URI).apply()
        wallpaperPreviewView.setImageUri(null)
        btnCustomizeForeground.isEnabled = false
        originalBitmapForColorExtraction?.recycle()
        originalBitmapForColorExtraction = null
        setDefaultColorPaletteAndUpdatePreview()
    }

    private fun extractColorsFromBitmapUri(internalUri: Uri, isNewImage: Boolean) {
        try {
            val inputStream = contentResolver.openInputStream(internalUri)
            val options = BitmapFactory.Options()
            options.inSampleSize = 2
            originalBitmapForColorExtraction?.recycle()
            originalBitmapForColorExtraction =
                BitmapFactory.decodeStream(inputStream, null, options)
            inputStream?.close()

            originalBitmapForColorExtraction?.let { bitmap ->
                extractColorsFromLoadedBitmap(bitmap, isNewImage)
            } ?: run {
                Log.e(
                    TAG,
                    "Failed to decode bitmap for color extraction from internal URI: $internalUri"
                )
                handleFailedImageAccess(
                    internalUri, getString(R.string.image_load_failed_toast) + " (内部图片解码失败)"
                )
            }
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Exception loading image for color extraction from internal URI: $internalUri",
                e
            )
            handleFailedImageAccess(
                internalUri, getString(R.string.image_load_failed_toast) + " (内部图片加载异常)"
            )
        }
    }

    private fun extractColorsFromLoadedBitmap(bitmap: Bitmap, isNewImage: Boolean) {
        Palette.from(bitmap).generate { palette ->
            val swatches = listOfNotNull(
                palette?.dominantSwatch,
                palette?.vibrantSwatch,
                palette?.mutedSwatch,
                palette?.lightVibrantSwatch,
                palette?.darkVibrantSwatch,
                palette?.lightMutedSwatch,
                palette?.darkMutedSwatch
            ).distinctBy { it.rgb }.take(8)
            val colors = swatches.map { it.rgb }

            val oldSelectedColor = selectedBackgroundColor
            if (colors.isNotEmpty()) {
                populateColorPaletteView(colors)
                if (isNewImage || selectedBackgroundColor == WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR || !colors.contains(
                        selectedBackgroundColor
                    ) || colors.size == 1
                ) {
                    selectedBackgroundColor = colors[0]
                }
            } else {
                setDefaultColorPalette()
            }

            if (oldSelectedColor != selectedBackgroundColor || isNewImage) {
                wallpaperPreviewView.setSelectedBackgroundColor(selectedBackgroundColor)
                savePreferences()
            } else if (selectedBackgroundColor != WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR && colors.contains(
                    selectedBackgroundColor
                )
            ) {
                wallpaperPreviewView.setSelectedBackgroundColor(selectedBackgroundColor)
            }
        }
    }

    private fun setDefaultColorPaletteAndUpdatePreview() {
        setDefaultColorPalette()
        wallpaperPreviewView.setSelectedBackgroundColor(selectedBackgroundColor)
    }

    private fun setDefaultColorPalette() {
        val defaultColors = listOf(
            Color.GRAY,
            Color.DKGRAY,
            WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR,
            Color.WHITE,
            Color.BLACK
        )
        populateColorPaletteView(defaultColors)
        if (!defaultColors.contains(selectedBackgroundColor) || selectedImageUri == null) {
            selectedBackgroundColor = WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR
        }
    }

    private fun populateColorPaletteView(colors: List<Int>) {
        colorPaletteContainer.removeAllViews()
        val colorViewSize = resources.getDimensionPixelSize(R.dimen.palette_color_view_size)
        val margin = resources.getDimensionPixelSize(R.dimen.palette_color_view_margin)

        for (color in colors) {
            val colorView = View(this)
            val params = LinearLayout.LayoutParams(colorViewSize, colorViewSize)
            params.setMargins(margin, margin, margin, margin)
            colorView.layoutParams = params
            colorView.setBackgroundColor(color)
            colorView.setOnClickListener {
                selectedBackgroundColor = color
                wallpaperPreviewView.setSelectedBackgroundColor(selectedBackgroundColor)
                savePreferences()
            }
            colorPaletteContainer.addView(colorView)
        }
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        originalBitmapForColorExtraction?.recycle()
        originalBitmapForColorExtraction = null
        Log.d(TAG, "MainActivity onDestroy")
    }

    private fun promptToSetWallpaper() {
        try {
            val componentName = ComponentName(packageName, H2WallpaperService::class.java.name)
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, componentName)
            startActivity(intent)
            Toast.makeText(this, getString(R.string.wallpaper_set_prompt_toast), Toast.LENGTH_LONG)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Error trying to set wallpaper", e)
            Toast.makeText(
                this,
                getString(R.string.wallpaper_set_failed_toast, e.message ?: "Unknown error"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MainActivity onResume: Reloading preferences and updating preview.")
        loadAndApplyPreferencesAndInitState()
    }
}