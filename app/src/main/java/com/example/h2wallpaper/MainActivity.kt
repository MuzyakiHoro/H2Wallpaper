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
import android.widget.ProgressBar // 新增：用于显示复制进度
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider // 新增：如果需要通过FileProvider共享内部URI
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope // 新增：用于启动协程
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
    private lateinit var imageLoadingProgressBar: ProgressBar // 新增

    // 状态变量
    // selectedImageUri 现在将存储指向应用内部副本的 File URI 或 Content URI (通过FileProvider)
    private var selectedImageUri: Uri? = null
    private var selectedBackgroundColor: Int = Color.LTGRAY
    private var originalBitmapForColorExtraction: Bitmap? = null
    var page1ImageHeightRatio: Float = DEFAULT_HEIGHT_RATIO
    private var currentScrollSensitivityFactor: Float = DEFAULT_SCROLL_SENSITIVITY

    private var currentP1FocusX: Float = 0.5f
    private var currentP1FocusY: Float = 0.5f

    // 新增：用于存储内部图片的文件名
    private val INTERNAL_IMAGE_FILENAME = "h2_wallpaper_internal_image.jpg"
    private val INTERNAL_IMAGE_FOLDER = "wallpaper_images"


    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { externalUri ->
                    Log.i(TAG, "Image selected from picker: $externalUri")
                    // 启动协程复制图片到内部存储
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

                savePreferences() // 保存新的焦点参数
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
        // KEY_IMAGE_URI 现在存储的是内部文件的URI字符串
        const val KEY_IMAGE_URI = "internalImageUri" // 修改Key的名称以反映其含义
        const val KEY_BACKGROUND_COLOR = "backgroundColor"
        const val KEY_IMAGE_HEIGHT_RATIO = "imageHeightRatio"
        const val KEY_SCROLL_SENSITIVITY = "scrollSensitivity"
        const val KEY_P1_FOCUS_X = "p1FocusX"
        const val KEY_P1_FOCUS_Y = "p1FocusY"

        const val DEFAULT_SCROLL_SENSITIVITY = 1.0f
        const val DEFAULT_HEIGHT_RATIO = 1f / 3f
        private const val PERMISSION_REQUEST_READ_MEDIA_IMAGES = 1001
        private const val TAG = "H2WallpaperMain"

        private const val HEIGHT_RATIO_STEP = 0.05f
        private const val MIN_HEIGHT_RATIO = 0.15f
        private const val MAX_HEIGHT_RATIO = 0.60f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: Activity starting")

        WindowCompat.setDecorFitsSystemWindows(window, false)
        // ... (状态栏和导航栏透明化代码保持不变) ...
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

        // 初始化UI控件
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
        imageLoadingProgressBar = findViewById(R.id.imageLoadingProgressBar) // 在你的activity_main.xml中添加一个ProgressBar

        loadAndApplyPreferencesAndInitState()

        // ... (处理窗口边衬区代码保持不变) ...
        val rootLayoutForInsets: View = findViewById(android.R.id.content) // 或者你的根布局ID
        ViewCompat.setOnApplyWindowInsetsListener(rootLayoutForInsets) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (btnSetWallpaper.layoutParams as? ConstraintLayout.LayoutParams)?.apply {
                bottomMargin = systemBars.bottom + (16 * resources.displayMetrics.density).toInt()
            }
            btnSetWallpaper.requestLayout()
            insets
        }


        // 设置按钮点击监听 (大部分保持不变)
        btnSelectImage.setOnClickListener { checkAndRequestReadMediaImagesPermission() }

        btnSetWallpaper.setOnClickListener {
            if (selectedImageUri != null) {
                savePreferences() // 确保在应用前保存当前所有设置
                promptToSetWallpaper()
            } else {
                Toast.makeText(this, getString(R.string.please_select_image_first_toast), Toast.LENGTH_SHORT).show()
            }
        }
        // ... (高度调节按钮监听保持不变) ...
        btnHeightReset.setOnClickListener { updatePage1ImageHeightRatio(DEFAULT_HEIGHT_RATIO) }
        btnHeightIncrease.setOnClickListener { updatePage1ImageHeightRatio((page1ImageHeightRatio + HEIGHT_RATIO_STEP)) }
        btnHeightDecrease.setOnClickListener { updatePage1ImageHeightRatio((page1ImageHeightRatio - HEIGHT_RATIO_STEP)) }


        btnCustomizeForeground.setOnClickListener {
            Log.d("MainActivityFocus", "btnCustomizeForeground clicked")
            // selectedImageUri 现在是内部文件的 URI
            if (selectedImageUri != null) {
                Log.d("MainActivityFocus", "selectedImageUri is: ${selectedImageUri.toString()}")
                // ... (启动 FocusActivity 的逻辑基本不变, 因为 selectedImageUri 仍然是一个有效的Uri) ...
                val intent = Intent(this, FocusActivity::class.java).apply {
                    putExtra(FocusParams.EXTRA_IMAGE_URI, selectedImageUri) // FocusActivity 现在会收到内部文件的URI

                    val previewWidth = wallpaperPreviewView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
                    val previewHeight = wallpaperPreviewView.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels

                    val currentP1Height = (previewHeight * page1ImageHeightRatio)
                    val currentP1AspectRatio = if (previewWidth > 0 && currentP1Height > 0) {
                        previewWidth.toFloat() / currentP1Height.toFloat()
                    } else {
                        Log.w("MainActivityFocus", "PreviewView not measured or p1Height is 0, using default aspect ratio 16/9")
                        16f / 9f
                    }
                    Log.d("MainActivityFocus", "Calculated P1 AspectRatio: $currentP1AspectRatio for FocusActivity")
                    putExtra(FocusParams.EXTRA_ASPECT_RATIO, currentP1AspectRatio)

                    Log.d("MainActivityFocus", "Passing Initial Focus X: $currentP1FocusX, Y: $currentP1FocusY to FocusActivity")
                    putExtra(FocusParams.EXTRA_INITIAL_FOCUS_X, currentP1FocusX)
                    putExtra(FocusParams.EXTRA_INITIAL_FOCUS_Y, currentP1FocusY)
                }
                Log.d("MainActivityFocus", "Intent extras before launch: Bundle[${intent.extras?.let { bundle -> bundle.keySet().joinToString { key -> "$key=${bundle.get(key)}" } } ?: "null"}]")
                focusActivityLauncher.launch(intent)
            } else {
                Log.w("MainActivityFocus", "selectedImageUri is NULL, cannot start FocusActivity")
                Toast.makeText(this, "请先选择一张图片", Toast.LENGTH_SHORT).show()
            }
        }
        // ... (预览区域点击监听保持不变) ...
        var controlsAreVisible = true
        wallpaperPreviewView.setOnClickListener {
            controlsAreVisible = !controlsAreVisible
            val targetAlpha = if (controlsAreVisible) 1f else 0f
            val targetVisibility = if (controlsAreVisible) View.VISIBLE else View.GONE

            animateViewVisibility(controlsContainer, targetAlpha, targetVisibility)
            animateViewVisibility(heightControlsContainer, targetAlpha, targetVisibility)
            animateViewVisibility(btnSetWallpaper, targetAlpha, targetVisibility)
            animateViewVisibility(btnSelectImage, targetAlpha, targetVisibility)
            if (selectedImageUri != null) {
                animateViewVisibility(btnCustomizeForeground, targetAlpha, targetVisibility)
            } else {
                btnCustomizeForeground.visibility = View.GONE
                btnCustomizeForeground.alpha = 0f
            }
        }

        Log.d(TAG, "onCreate: Activity setup complete")
    }

    // --- 文件复制相关逻辑 ---
    private fun copyImageToInternalStorage(sourceUri: Uri) {
        imageLoadingProgressBar.visibility = View.VISIBLE // 显示加载指示
        btnSelectImage.isEnabled = false // 复制期间禁用选择按钮

        lifecycleScope.launch {
            val internalFileUri = saveImageToInternalAppStorage(sourceUri)
            imageLoadingProgressBar.visibility = View.GONE
            btnSelectImage.isEnabled = true

            if (internalFileUri != null) {
                Log.i(TAG, "Image copied successfully to internal URI: $internalFileUri")
                // 清理旧的内部图片（如果存在且与新选择的不同）
                // selectedImageUri 此刻可能还是旧的内部图片URI
                if (selectedImageUri != null && selectedImageUri != internalFileUri) {
                    deleteInternalImage(selectedImageUri!!) // 删除旧的副本
                }

                selectedImageUri = internalFileUri // 更新为新的内部文件URI
                currentP1FocusX = 0.5f // 新图片，重置焦点
                currentP1FocusY = 0.5f
                savePreferences() // 保存新的内部URI和重置后的焦点

                wallpaperPreviewView.setNormalizedFocus(currentP1FocusX, currentP1FocusY)
                wallpaperPreviewView.setImageUri(selectedImageUri,true) // 使用内部URI设置预览

                btnCustomizeForeground.isEnabled = true
                extractColorsFromBitmapUri(selectedImageUri!!, true) // 从内部URI提取颜色
            } else {
                Log.e(TAG, "Failed to copy image to internal storage.")
                Toast.makeText(applicationContext, "图片复制失败", Toast.LENGTH_SHORT).show()
                // 保留之前的 selectedImageUri (如果有的话)
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

            // 如果文件已存在，先删除旧文件，确保是新的复制
            if (internalFile.exists()) {
                internalFile.delete()
            }

            outputStream = FileOutputStream(internalFile)
            val buffer = ByteArray(4 * 1024) // 4k buffer
            var read: Int
            while (inputStream.read(buffer).also { read = it } != -1) {
                outputStream.write(buffer, 0, read)
            }
            outputStream.flush()

            Log.i(TAG, "Image saved to internal file: ${internalFile.absolutePath}")
            // return Uri.fromFile(internalFile) // File URI
            // 或者使用 FileProvider (更推荐，如果需要与其他应用安全共享，但对于内部使用File URI也行)
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
            // 如果是 FileProvider URI, 需要转换为 File Path 来删除
            // (这需要 FileProvider 的 path-mapping 逆向逻辑，或者直接删除文件名)
            val imageDir = File(filesDir, INTERNAL_IMAGE_FOLDER)
            val internalFile = File(imageDir, INTERNAL_IMAGE_FILENAME)
            if (internalFile.exists()) {
                if (internalFile.delete()) {
                    Log.i(TAG, "Deleted old internal image file: ${internalFile.path}")
                } else {
                    Log.w(TAG, "Failed to delete old internal image file: ${internalFile.path}")
                }
            }
        } else if (internalFileUri.scheme == "file") {
            val filePath = internalFileUri.path
            if (filePath != null) {
                val file = File(filePath)
                if (file.exists() && file.isFile && file.parentFile?.name == INTERNAL_IMAGE_FOLDER && file.parentFile?.parentFile == filesDir) {
                    if (file.delete()) {
                        Log.i(TAG, "Deleted old internal image file: $filePath")
                    } else {
                        Log.w(TAG, "Failed to delete old internal image file: $filePath")
                    }
                } else {
                    Log.w(TAG, "Old internal image file path is not as expected or does not exist: $filePath")
                }
            }
        }
    }


    // animateViewVisibility, updatePage1ImageHeightRatio, permission checks, openGallery 保持不变
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
            Log.d(TAG, "Requesting permission: $permissionToRequest")
            ActivityCompat.requestPermissions(this, arrayOf(permissionToRequest), PERMISSION_REQUEST_READ_MEDIA_IMAGES)
        } else {
            Log.d(TAG, "Permission already granted: $permissionToRequest. Opening gallery.")
            openGallery()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_READ_MEDIA_IMAGES) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Permission granted by user. Opening gallery.")
                openGallery()
            } else {
                Log.w(TAG, "Permission denied by user.")
                Toast.makeText(this, getString(R.string.permission_needed_toast), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        // 不再需要 FLAG_GRANT_READ_URI_PERMISSION, 因为我们会立即复制
        // intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            pickImageLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch gallery picker", e)
            Toast.makeText(this, getString(R.string.image_selection_failed_toast), Toast.LENGTH_SHORT).show()
        }
    }


    // handleFailedImageAccess 现在处理的是内部文件访问失败的情况 (理论上不应发生权限问题)
    private fun handleFailedImageAccess(uriFailed: Uri?, message: String = "图片访问失败") {
        Log.e(TAG, "Failed to access internal URI: $uriFailed. Clearing it. Message: $message")
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

        selectedImageUri = null
        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_IMAGE_URI).apply() // 使用更新后的KEY_IMAGE_URI

        wallpaperPreviewView.setImageUri(null)
        btnCustomizeForeground.isEnabled = false
        originalBitmapForColorExtraction?.recycle()
        originalBitmapForColorExtraction = null
        setDefaultColorPaletteAndUpdatePreview()
    }

    // extractColorsFromBitmapUri 现在总是接收内部 URI
    private fun extractColorsFromBitmapUri(internalUri: Uri, isNewImage: Boolean) {
        try {
            // 内部URI可以直接用 contentResolver 打开 (如果是 FileProvider URI)
            // 或者如果是 File URI, 可以直接创建 FileInputStream
            val inputStream = contentResolver.openInputStream(internalUri)
            // val inputStream = FileInputStream(File(internalUri.path!!)) // 如果 internalUri 是 File URI

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
        }
        // 对于内部文件，SecurityException 理论上不应发生，主要是 IOException (FileNotFound)
        catch (e: Exception) { // 捕获更通用的异常，以防万一
            Log.e(TAG, "Exception loading image for color extraction from internal URI: $internalUri", e)
            handleFailedImageAccess(internalUri, getString(R.string.image_load_failed_toast) + " (内部图片加载异常)")
        }
    }

    // extractColorsFromLoadedBitmap, setDefaultColorPaletteAndUpdatePreview, setDefaultColorPalette, populateColorPaletteView 保持不变
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
        // 可以考虑在这里回收 originalBitmapForColorExtraction，以减少内存占用
        // 但要注意如果 Activity 只是 stop 而不是 destroy，下次 resume 可能还需要它
        // originalBitmapForColorExtraction?.recycle()
        // originalBitmapForColorExtraction = null
    }

    override fun onDestroy() {
        super.onDestroy()
        // 确保回收位图
        originalBitmapForColorExtraction?.recycle()
        originalBitmapForColorExtraction = null
        // wallpaperPreviewView 内部的 onDetachedFromWindow 会处理其自身的位图回收
    }


    private fun savePreferences() {
        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putInt(KEY_BACKGROUND_COLOR, selectedBackgroundColor)
        editor.putFloat(KEY_IMAGE_HEIGHT_RATIO, page1ImageHeightRatio)
        editor.putFloat(KEY_SCROLL_SENSITIVITY, currentScrollSensitivityFactor)
        editor.putFloat(KEY_P1_FOCUS_X, currentP1FocusX)
        editor.putFloat(KEY_P1_FOCUS_Y, currentP1FocusY)

        // 保存内部图片的URI字符串
        selectedImageUri?.let { editor.putString(KEY_IMAGE_URI, it.toString()) } ?: editor.remove(KEY_IMAGE_URI)
        editor.apply()
        Log.d(TAG, "Preferences saved: InternalURI=${selectedImageUri?.toString()}, Color=$selectedBackgroundColor, HeightRatio=$page1ImageHeightRatio, Sensitivity=$currentScrollSensitivityFactor, Focus=($currentP1FocusX, $currentP1FocusY)")
    }

    private fun loadAndApplyPreferencesAndInitState() {
        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        selectedBackgroundColor = prefs.getInt(KEY_BACKGROUND_COLOR, Color.LTGRAY)
        page1ImageHeightRatio = prefs.getFloat(KEY_IMAGE_HEIGHT_RATIO, DEFAULT_HEIGHT_RATIO)
        currentScrollSensitivityFactor = prefs.getFloat(KEY_SCROLL_SENSITIVITY, DEFAULT_SCROLL_SENSITIVITY)
        currentP1FocusX = prefs.getFloat(KEY_P1_FOCUS_X, 0.5f)
        currentP1FocusY = prefs.getFloat(KEY_P1_FOCUS_Y, 0.5f)
        val internalImageUriString = prefs.getString(KEY_IMAGE_URI, null) // 使用更新后的Key

        Log.i(TAG, "Loaded preferences: InternalURI=$internalImageUriString, Color=$selectedBackgroundColor, Ratio=$page1ImageHeightRatio, Sensitivity=$currentScrollSensitivityFactor, Focus=($currentP1FocusX, $currentP1FocusY)")

        wallpaperPreviewView.setSelectedBackgroundColor(selectedBackgroundColor)
        wallpaperPreviewView.setPage1ImageHeightRatio(page1ImageHeightRatio)
        wallpaperPreviewView.setNormalizedFocus(currentP1FocusX, currentP1FocusY)

        if (internalImageUriString != null) {
            val internalUri = Uri.parse(internalImageUriString)
            // 检查内部文件是否存在
            var fileExists = false
            try {
                // 对于FileProvider URI，需要尝试打开流来检查；对于File URI，可以直接检查File.exists()
                if (internalUri.scheme == "content") {
                    contentResolver.openInputStream(internalUri)?.use {
                        fileExists = true // 如果能打开，说明文件（在某种程度上）是可访问的
                    }
                } else if (internalUri.scheme == "file") {
                    val path = internalUri.path
                    if (path != null) fileExists = File(path).exists()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking existence of internal file $internalUri: ${e.message}")
                fileExists = false // 无法确认，视为不存在或不可访问
            }


            if (fileExists) {
                selectedImageUri = internalUri
                wallpaperPreviewView.setImageUri(selectedImageUri) // 这会触发加载
                btnCustomizeForeground.isEnabled = true
                extractColorsFromBitmapUri(selectedImageUri!!, false) // isNewImage = false
            } else {
                Log.w(TAG, "Internal image file URI loaded from prefs but file not found/accessible: $internalImageUriString")
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

    // promptToSetWallpaper 保持不变
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
}