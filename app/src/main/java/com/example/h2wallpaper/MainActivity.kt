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
import kotlin.math.roundToInt // 确保导入 roundToInt

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
    private var selectedBackgroundColor: Int = Color.LTGRAY
    private var originalBitmapForColorExtraction: Bitmap? = null
    var page1ImageHeightRatio: Float = DEFAULT_HEIGHT_RATIO
    private var currentP1FocusX: Float = 0.5f
    private var currentP1FocusY: Float = 0.5f

    // 配置参数变量 (程序内部逻辑使用 Float，但与 SharedPreferences 交互时，部分参数会转为 Int)
    private var currentScrollSensitivity: Float = DEFAULT_SCROLL_SENSITIVITY
    private var currentP1OverlayFadeRatio: Float = DEFAULT_P1_OVERLAY_FADE_RATIO
    private var currentBackgroundBlurRadius: Float = DEFAULT_BACKGROUND_BLUR_RADIUS
    private var currentBackgroundInitialOffset: Float = DEFAULT_BACKGROUND_INITIAL_OFFSET
    private var currentP2BackgroundFadeInRatio: Float = DEFAULT_P2_BACKGROUND_FADE_IN_RATIO
    private var currentBlurDownscaleFactorInt: Int = DEFAULT_BLUR_DOWNSCALE_FACTOR_INT // 这个本身就是Int
    private var currentBlurIterations: Int = DEFAULT_BLUR_ITERATIONS // 这个本身就是Int
    private var currentP1ShadowRadius: Float = DEFAULT_P1_SHADOW_RADIUS
    private var currentP1ShadowDx: Float = DEFAULT_P1_SHADOW_DX
    private var currentP1ShadowDy: Float = DEFAULT_P1_SHADOW_DY
    private var currentP1ShadowColor: Int = DEFAULT_P1_SHADOW_COLOR // 颜色是 Int
    private var currentP1ImageBottomFadeHeight: Float = DEFAULT_P1_IMAGE_BOTTOM_FADE_HEIGHT


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
                    Toast.makeText(this, getString(R.string.image_selection_failed_toast) + " (No data URI)", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.d(TAG, "Image selection cancelled or failed, resultCode: ${result.resultCode}")
            }
        }

    private val focusActivityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                currentP1FocusX = data?.getFloatExtra(FocusParams.RESULT_FOCUS_X, 0.5f) ?: 0.5f
                currentP1FocusY = data?.getFloatExtra(FocusParams.RESULT_FOCUS_Y, 0.5f) ?: 0.5f

                savePreferences() // 保存更新后的焦点
                wallpaperPreviewView.setNormalizedFocus(currentP1FocusX, currentP1FocusY)

                Toast.makeText(
                    this,
                    "焦点已更新: X=${"%.2f".format(currentP1FocusX)}, Y=${"%.2f".format(currentP1FocusY)}",
                    Toast.LENGTH_LONG
                ).show()

            } else if (result.resultCode == Activity.RESULT_CANCELED) {
                Toast.makeText(this, "自定义焦点已取消", Toast.LENGTH_SHORT).show()
            }
        }

    companion object {
        const val PREFS_NAME = "H2WallpaperPrefs"
        const val KEY_IMAGE_URI = "internalImageUri"
        const val KEY_BACKGROUND_COLOR = "backgroundColor" // Int
        const val KEY_IMAGE_HEIGHT_RATIO = "imageHeightRatio" // Float
        const val KEY_P1_FOCUS_X = "p1FocusX" // Float
        const val KEY_P1_FOCUS_Y = "p1FocusY" // Float

        // 参数定义 (KEYs)
        const val KEY_SCROLL_SENSITIVITY = "scrollSensitivity"         // Stored as Int (scaled)
        const val KEY_P1_OVERLAY_FADE_RATIO = "p1OverlayFadeRatio"     // Stored as Int (scaled)
        const val KEY_BACKGROUND_BLUR_RADIUS = "backgroundBlurRadius"  // Stored as Int
        const val KEY_BACKGROUND_INITIAL_OFFSET = "backgroundInitialOffset" // Stored as Int (scaled)
        const val KEY_P2_BACKGROUND_FADE_IN_RATIO = "p2BackgroundFadeInRatio" // Stored as Int (scaled)
        const val KEY_BLUR_DOWNSCALE_FACTOR = "blurDownscaleFactor"    // Stored as Int
        const val KEY_BLUR_ITERATIONS = "blurIterations"               // Stored as Int
        const val KEY_P1_SHADOW_RADIUS = "p1ShadowRadius"              // Stored as Int
        const val KEY_P1_SHADOW_DX = "p1ShadowDx"                      // Stored as Int
        const val KEY_P1_SHADOW_DY = "p1ShadowDy"                      // Stored as Int
        const val KEY_P1_SHADOW_COLOR = "p1ShadowColor"                // Stored as Int
        const val KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT = "p1ImageBottomFadeHeight" // Stored as Int

        // 默认值 (Float 或 Int，根据内部逻辑使用类型)
        const val DEFAULT_HEIGHT_RATIO = 1f / 3f
        const val DEFAULT_SCROLL_SENSITIVITY = 1.0f
        const val DEFAULT_P1_OVERLAY_FADE_RATIO = 0.5f
        const val DEFAULT_P2_BACKGROUND_FADE_IN_RATIO = 0.8f
        const val DEFAULT_BACKGROUND_BLUR_RADIUS = 25f // 将其视为 Float，保存时转 Int
        const val DEFAULT_PREVIEW_SNAP_DURATION_MS: Long = 700L
        const val DEFAULT_BACKGROUND_INITIAL_OFFSET = 0.2f // 1f/5f
        const val DEFAULT_BLUR_DOWNSCALE_FACTOR_INT = 25 // 直接是 Int (代表0.25f)
        const val DEFAULT_BLUR_ITERATIONS = 1          // Int

        const val DEFAULT_P1_SHADOW_RADIUS = 0f
        const val DEFAULT_P1_SHADOW_DX = 0f
        const val DEFAULT_P1_SHADOW_DY = 0f
        val DEFAULT_P1_SHADOW_COLOR = Color.argb(100, 0, 0, 0) // Int
        const val DEFAULT_P1_IMAGE_BOTTOM_FADE_HEIGHT = 80f // Float, 保存时转 Int

        private const val PERMISSION_REQUEST_READ_MEDIA_IMAGES = 1001
        private const val TAG = "H2WallpaperMain"
        private const val HEIGHT_RATIO_STEP = 0.05f
        private const val MIN_HEIGHT_RATIO = 0.15f
        private const val MAX_HEIGHT_RATIO = 0.60f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ... (Window setup code remains the same) ...
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }

        setContentView(R.layout.activity_main)
        // ... (findViewById calls remain the same) ...
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

        // ... (WindowInsetsListener and button click listeners remain largely the same) ...
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
                Toast.makeText(this, getString(R.string.please_select_image_first_toast), Toast.LENGTH_SHORT).show()
            }
        }
        btnAdvancedSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
        btnHeightReset.setOnClickListener { updatePage1ImageHeightRatio(DEFAULT_HEIGHT_RATIO) }
        btnHeightIncrease.setOnClickListener { updatePage1ImageHeightRatio((page1ImageHeightRatio + HEIGHT_RATIO_STEP)) }
        btnHeightDecrease.setOnClickListener { updatePage1ImageHeightRatio((page1ImageHeightRatio - HEIGHT_RATIO_STEP)) }


        btnCustomizeForeground.setOnClickListener {
            Log.d("MainActivityFocus", "btnCustomizeForeground clicked")
            if (selectedImageUri != null) {
                Log.d("MainActivityFocus", "selectedImageUri is: ${selectedImageUri.toString()}")
                val intent = Intent(this, FocusActivity::class.java).apply {
                    putExtra(FocusParams.EXTRA_IMAGE_URI, selectedImageUri)

                    val previewWidth = wallpaperPreviewView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
                    val previewHeight = wallpaperPreviewView.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
                    val currentP1Height = (previewHeight * page1ImageHeightRatio)
                    val currentP1AspectRatio = if (previewWidth > 0 && currentP1Height > 0) {
                        previewWidth.toFloat() / currentP1Height.toFloat()
                    } else {
                        Log.w("MainActivityFocus", "PreviewView not measured or p1Height is 0, using default aspect ratio 16/9")
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
        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        selectedBackgroundColor = prefs.getInt(KEY_BACKGROUND_COLOR, Color.LTGRAY)
        page1ImageHeightRatio = prefs.getFloat(KEY_IMAGE_HEIGHT_RATIO, DEFAULT_HEIGHT_RATIO)
        currentP1FocusX = prefs.getFloat(KEY_P1_FOCUS_X, 0.5f)
        currentP1FocusY = prefs.getFloat(KEY_P1_FOCUS_Y, 0.5f)

        // 加载由 SeekBarPreference (Int) 控制的参数，并转换为 Float 成员变量
        currentScrollSensitivity = prefs.getInt(KEY_SCROLL_SENSITIVITY, (DEFAULT_SCROLL_SENSITIVITY * 10).toInt()) / 10.0f
        currentP1OverlayFadeRatio = prefs.getInt(KEY_P1_OVERLAY_FADE_RATIO, (DEFAULT_P1_OVERLAY_FADE_RATIO * 100).toInt()) / 100.0f
        currentBackgroundBlurRadius = prefs.getInt(KEY_BACKGROUND_BLUR_RADIUS, DEFAULT_BACKGROUND_BLUR_RADIUS.roundToInt()).toFloat()
        currentBackgroundInitialOffset = prefs.getInt(KEY_BACKGROUND_INITIAL_OFFSET, (DEFAULT_BACKGROUND_INITIAL_OFFSET * 10).toInt()) / 10.0f
        currentP2BackgroundFadeInRatio = prefs.getInt(KEY_P2_BACKGROUND_FADE_IN_RATIO, (DEFAULT_P2_BACKGROUND_FADE_IN_RATIO * 100).toInt()) / 100.0f
        currentBlurDownscaleFactorInt = prefs.getInt(KEY_BLUR_DOWNSCALE_FACTOR, DEFAULT_BLUR_DOWNSCALE_FACTOR_INT)
        currentBlurIterations = prefs.getInt(KEY_BLUR_ITERATIONS, DEFAULT_BLUR_ITERATIONS)

        currentP1ShadowRadius = prefs.getInt(KEY_P1_SHADOW_RADIUS, DEFAULT_P1_SHADOW_RADIUS.roundToInt()).toFloat()
        currentP1ShadowDx = prefs.getInt(KEY_P1_SHADOW_DX, DEFAULT_P1_SHADOW_DX.roundToInt()).toFloat()
        currentP1ShadowDy = prefs.getInt(KEY_P1_SHADOW_DY, DEFAULT_P1_SHADOW_DY.roundToInt()).toFloat()
        currentP1ShadowColor = prefs.getInt(KEY_P1_SHADOW_COLOR, DEFAULT_P1_SHADOW_COLOR) // Color is Int
        currentP1ImageBottomFadeHeight = prefs.getInt(KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT, DEFAULT_P1_IMAGE_BOTTOM_FADE_HEIGHT.roundToInt()).toFloat()


        val internalImageUriString = prefs.getString(KEY_IMAGE_URI, null)
        Log.i(TAG, "Loaded preferences: InternalURI=$internalImageUriString, ... P1ShadowRadius=$currentP1ShadowRadius, ...")


        wallpaperPreviewView.setConfigValues(
            scrollSensitivity = currentScrollSensitivity,
            p1OverlayFadeRatio = currentP1OverlayFadeRatio,
            backgroundBlurRadius = currentBackgroundBlurRadius,
            snapAnimationDurationMs = DEFAULT_PREVIEW_SNAP_DURATION_MS,
            normalizedInitialBgScrollOffset = currentBackgroundInitialOffset,
            p2BackgroundFadeInRatio = currentP2BackgroundFadeInRatio,
            blurDownscaleFactor = currentBlurDownscaleFactorInt / 100.0f, // 从Int转换为Float
            blurIterations = currentBlurIterations,
            p1ShadowRadius = currentP1ShadowRadius,
            p1ShadowDx = currentP1ShadowDx,
            p1ShadowDy = currentP1ShadowDy,
            p1ShadowColor = currentP1ShadowColor,
            p1ImageBottomFadeHeight = currentP1ImageBottomFadeHeight
        )

        wallpaperPreviewView.setPage1ImageHeightRatio(page1ImageHeightRatio)
        wallpaperPreviewView.setNormalizedFocus(currentP1FocusX, currentP1FocusY)
        wallpaperPreviewView.setSelectedBackgroundColor(selectedBackgroundColor) // 确保调用这个


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
        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putInt(KEY_BACKGROUND_COLOR, selectedBackgroundColor)
        editor.putFloat(KEY_IMAGE_HEIGHT_RATIO, page1ImageHeightRatio) // 保持 Float
        editor.putFloat(KEY_P1_FOCUS_X, currentP1FocusX)             // 保持 Float
        editor.putFloat(KEY_P1_FOCUS_Y, currentP1FocusY)             // 保持 Float

        // 对于由 SeekBarPreference 控制的参数，统一存为 Int
        editor.putInt(KEY_SCROLL_SENSITIVITY, (currentScrollSensitivity * 10).toInt())
        editor.putInt(KEY_P1_OVERLAY_FADE_RATIO, (currentP1OverlayFadeRatio * 100).toInt())
        editor.putInt(KEY_BACKGROUND_BLUR_RADIUS, currentBackgroundBlurRadius.roundToInt())
        editor.putInt(KEY_BACKGROUND_INITIAL_OFFSET, (currentBackgroundInitialOffset * 10).toInt())
        editor.putInt(KEY_P2_BACKGROUND_FADE_IN_RATIO, (currentP2BackgroundFadeInRatio * 100).toInt())
        editor.putInt(KEY_BLUR_DOWNSCALE_FACTOR, currentBlurDownscaleFactorInt) // 本身就是Int
        editor.putInt(KEY_BLUR_ITERATIONS, currentBlurIterations)         // 本身就是Int

        // 新增的 P1 特效参数也存为 Int
        editor.putInt(KEY_P1_SHADOW_RADIUS, currentP1ShadowRadius.roundToInt())
        editor.putInt(KEY_P1_SHADOW_DX, currentP1ShadowDx.roundToInt())
        editor.putInt(KEY_P1_SHADOW_DY, currentP1ShadowDy.roundToInt())
        editor.putInt(KEY_P1_SHADOW_COLOR, currentP1ShadowColor) // Color is Int
        editor.putInt(KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT, currentP1ImageBottomFadeHeight.roundToInt())


        selectedImageUri?.let { editor.putString(KEY_IMAGE_URI, it.toString()) } ?: editor.remove(KEY_IMAGE_URI)
        editor.apply()
        Log.d(TAG, "Preferences saved: ... P1ShadowRadius (as Int)=${currentP1ShadowRadius.roundToInt()}, P1BottomFadeHeight (as Int)=${currentP1ImageBottomFadeHeight.roundToInt()} ...")
    }

    // ... (copyImageToInternalStorage, saveImageToInternalAppStorage, deleteInternalImage, animateViewVisibility, updatePage1ImageHeightRatio, permission methods, openGallery, handleFailedImageAccess, color extraction, promptToSetWallpaper methods remain the same) ...
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
                currentP1FocusX = 0.5f
                currentP1FocusY = 0.5f
                savePreferences() // 保存新的URI和重置后的焦点及其他当前设置

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

    private suspend fun saveImageToInternalAppStorage(sourceUri: Uri): Uri? = withContext(Dispatchers.IO) {
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
            return@withContext FileProvider.getUriForFile(applicationContext, "${applicationContext.packageName}.provider", internalFile)
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
                if (internalFile.delete()) Log.i(TAG, "Deleted old internal image file: ${internalFile.path}")
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
                    Log.w(TAG, "Old internal image file path is not as expected or does not exist: $filePath")
                }
            }
        }
    }

    private fun animateViewVisibility(view: View, targetAlpha: Float, targetVisibilityIfGone: Int) {
        if (targetAlpha == 1f && view.visibility != View.VISIBLE) {
            view.alpha = 0f
            view.visibility = View.VISIBLE
        }
        view.animate()
            .alpha(targetAlpha)
            .setDuration(200)
            .withEndAction {
                if (targetAlpha == 0f) {
                    view.visibility = targetVisibilityIfGone
                }
            }
            .start()
    }

    private fun updatePage1ImageHeightRatio(newRatio: Float) {
        val clampedRatio = newRatio.coerceIn(MIN_HEIGHT_RATIO, MAX_HEIGHT_RATIO)
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
        if (ContextCompat.checkSelfPermission(this, permissionToRequest) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(permissionToRequest), PERMISSION_REQUEST_READ_MEDIA_IMAGES)
        } else {
            openGallery()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_READ_MEDIA_IMAGES) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openGallery()
            } else {
                Toast.makeText(this, getString(R.string.permission_needed_toast), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        try {
            pickImageLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch gallery picker", e)
            Toast.makeText(this, getString(R.string.image_selection_failed_toast), Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleFailedImageAccess(uriFailed: Uri?, message: String = "图片访问失败") {
        Log.e(TAG, "Failed to access internal URI: $uriFailed. Clearing it. Message: $message")
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        selectedImageUri = null
        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_IMAGE_URI).apply()
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
            originalBitmapForColorExtraction = BitmapFactory.decodeStream(inputStream, null, options)
            inputStream?.close()

            originalBitmapForColorExtraction?.let { bitmap ->
                extractColorsFromLoadedBitmap(bitmap, isNewImage)
            } ?: run {
                Log.e(TAG, "Failed to decode bitmap for color extraction from internal URI: $internalUri")
                handleFailedImageAccess(internalUri, getString(R.string.image_load_failed_toast) + " (内部图片解码失败)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception loading image for color extraction from internal URI: $internalUri", e)
            handleFailedImageAccess(internalUri, getString(R.string.image_load_failed_toast) + " (内部图片加载异常)")
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
                if (isNewImage || selectedBackgroundColor == Color.LTGRAY || !colors.contains(selectedBackgroundColor) || colors.size == 1) {
                    selectedBackgroundColor = colors[0]
                }
            } else {
                setDefaultColorPalette()
            }

            if (oldSelectedColor != selectedBackgroundColor || isNewImage) {
                wallpaperPreviewView.setSelectedBackgroundColor(selectedBackgroundColor)
                savePreferences()
            } else if (selectedBackgroundColor != Color.LTGRAY && colors.contains(selectedBackgroundColor)) {
                wallpaperPreviewView.setSelectedBackgroundColor(selectedBackgroundColor)
            }
        }
    }

    private fun setDefaultColorPaletteAndUpdatePreview() {
        setDefaultColorPalette()
        wallpaperPreviewView.setSelectedBackgroundColor(selectedBackgroundColor)
    }

    private fun setDefaultColorPalette() {
        val defaultColors = listOf(Color.GRAY, Color.DKGRAY, Color.LTGRAY, Color.WHITE, Color.BLACK)
        populateColorPaletteView(defaultColors)
        if (!defaultColors.contains(selectedBackgroundColor) || selectedImageUri == null) {
            selectedBackgroundColor = Color.LTGRAY
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
        // originalBitmapForColorExtraction?.recycle() // Consider lifecycle
        // originalBitmapForColorExtraction = null
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
            Toast.makeText(this, getString(R.string.wallpaper_set_prompt_toast), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error trying to set wallpaper", e)
            Toast.makeText(this, getString(R.string.wallpaper_set_failed_toast, e.message ?: "Unknown error"), Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MainActivity onResume: Reloading preferences and updating preview.")
        loadAndApplyPreferencesAndInitState()
    }
}