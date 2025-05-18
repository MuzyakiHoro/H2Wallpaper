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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout // 假设你的根布局是ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.palette.graphics.Palette
import java.io.IOException

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
    private lateinit var btnCustomizeForeground: Button // 新增的按钮

    // 状态变量
    private var selectedImageUri: Uri? = null
    private var selectedBackgroundColor: Int = Color.LTGRAY // 默认背景色
    private var originalBitmapForColorExtraction: Bitmap? = null // 用于颜色提取
    var page1ImageHeightRatio: Float = DEFAULT_HEIGHT_RATIO // 当前P1图片高度比例
    private var currentScrollSensitivityFactor: Float = DEFAULT_SCROLL_SENSITIVITY // 当前滚动灵敏度因子

    // P1 前景图的自定义焦点参数
    private var currentP1FocusX: Float = 0.5f
    private var currentP1FocusY: Float = 0.5f

    // ActivityResultLaunchers
    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    selectedImageUri = uri
                    Log.i(TAG, "pickImageLauncher: selectedImageUri SET to: $selectedImageUri")
                    // 重置焦点，因为换了新图
                    currentP1FocusX = 0.5f
                    currentP1FocusY = 0.5f
                    savePreferences() // 保存新的URI和重置后的焦点

                    wallpaperPreviewView.setNormalizedFocus(currentP1FocusX, currentP1FocusY) // 先设置焦点
                    wallpaperPreviewView.setImageUri(selectedImageUri) // 再设置图片，内部会使用已设置的焦点

                    btnCustomizeForeground.isEnabled = true
                    extractColorsFromBitmapUri(uri) // 从URI提取颜色
                } ?: run {
                    btnCustomizeForeground.isEnabled = false
                    Toast.makeText(this, getString(R.string.image_selection_failed_toast) + " (No data URI)", Toast.LENGTH_SHORT).show()
                }
            } else {
                // 如果用户取消选择，但之前已有图片，不应该禁用按钮
                // btnCustomizeForeground.isEnabled = (selectedImageUri != null) // 根据实际selectedImageUri状态决定
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
                wallpaperPreviewView.setNormalizedFocus(currentP1FocusX, currentP1FocusY) // 通知预览视图更新焦点

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
        const val KEY_IMAGE_URI = "imageUri"
        const val KEY_BACKGROUND_COLOR = "backgroundColor"
        const val KEY_IMAGE_HEIGHT_RATIO = "imageHeightRatio"
        const val KEY_SCROLL_SENSITIVITY = "scrollSensitivity"
        const val KEY_P1_FOCUS_X = "p1FocusX" // 新增：P1焦点X的Key
        const val KEY_P1_FOCUS_Y = "p1FocusY" // 新增：P1焦点Y的Key

        const val DEFAULT_SCROLL_SENSITIVITY = 1.0f
        const val DEFAULT_HEIGHT_RATIO = 1f / 3f
        private const val PERMISSION_REQUEST_READ_MEDIA_IMAGES = 1001
        private const val TAG = "H2WallpaperMain" // Logcat TAG for MainActivity

        // P1 高度比例限制
        private const val HEIGHT_RATIO_STEP = 0.05f
        private const val MIN_HEIGHT_RATIO = 0.15f
        private const val MAX_HEIGHT_RATIO = 0.60f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: Activity starting")

        WindowCompat.setDecorFitsSystemWindows(window, false)
        // ... (状态栏和导航栏透明化代码) ...
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
        btnCustomizeForeground = findViewById(R.id.btnCustomizeForeground) // 初始化新按钮
        Log.d(TAG, "onCreate: btnCustomizeForeground is null: ${btnCustomizeForeground == null}")


        loadAndApplyPreferencesAndInitState() // 加载偏好并初始化状态和UI

        // 处理窗口边衬区，调整按钮位置
        val rootLayoutForInsets: View = findViewById(android.R.id.content) // 或者你的根布局ID
        ViewCompat.setOnApplyWindowInsetsListener(rootLayoutForInsets) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 调整底部按钮的外边距以避开导航栏
            (btnSetWallpaper.layoutParams as? ConstraintLayout.LayoutParams)?.apply {
                bottomMargin = systemBars.bottom + (16 * resources.displayMetrics.density).toInt()
            }
            // 根据你的布局结构，可能需要调整其他控件的 padding 或 margin
            // (heightControlsContainer.layoutParams as? ConstraintLayout.LayoutParams)?.apply {
            // bottomMargin = systemBars.bottom + (8 * resources.displayMetrics.density).toInt()
            // }
            // (controlsContainer.layoutParams as? ConstraintLayout.LayoutParams)?.apply {
            // paddingBottom = systemBars.bottom + (8 * resources.displayMetrics.density).toInt()
            // }
            //  确保布局在应用insets后刷新
            btnSetWallpaper.requestLayout()
            // heightControlsContainer.requestLayout()
            // controlsContainer.requestLayout()
            insets
        }

        // 设置按钮点击监听
        btnSelectImage.setOnClickListener { checkAndRequestReadMediaImagesPermission() }

        btnSetWallpaper.setOnClickListener {
            if (selectedImageUri != null) {
                savePreferences() // 确保在应用前保存当前所有设置
                promptToSetWallpaper()
            } else {
                Toast.makeText(this, getString(R.string.please_select_image_first_toast), Toast.LENGTH_SHORT).show()
            }
        }

        btnHeightReset.setOnClickListener { updatePage1ImageHeightRatio(DEFAULT_HEIGHT_RATIO) }
        btnHeightIncrease.setOnClickListener { updatePage1ImageHeightRatio((page1ImageHeightRatio + HEIGHT_RATIO_STEP)) }
        btnHeightDecrease.setOnClickListener { updatePage1ImageHeightRatio((page1ImageHeightRatio - HEIGHT_RATIO_STEP)) }

        btnCustomizeForeground.setOnClickListener {
            Log.d("MainActivityFocus", "btnCustomizeForeground clicked")
            if (selectedImageUri != null) {
                Log.d("MainActivityFocus", "selectedImageUri is: ${selectedImageUri.toString()}")
                Log.d("MainActivityFocus", "page1ImageHeightRatio is: $page1ImageHeightRatio")
                Log.d("MainActivityFocus", "wallpaperPreviewView width: ${wallpaperPreviewView.width}, height: ${wallpaperPreviewView.height}")

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

        // 点击预览区域切换控件显隐
        var controlsAreVisible = true
        wallpaperPreviewView.setOnClickListener {
            controlsAreVisible = !controlsAreVisible
            val targetAlpha = if (controlsAreVisible) 1f else 0f
            val targetVisibility = if (controlsAreVisible) View.VISIBLE else View.GONE

            animateViewVisibility(controlsContainer, targetAlpha, targetVisibility)
            animateViewVisibility(heightControlsContainer, targetAlpha, targetVisibility)
            animateViewVisibility(btnSetWallpaper, targetAlpha, targetVisibility)
            // 你可能还想控制 btnSelectImage 和 btnCustomizeForeground 的显隐
            animateViewVisibility(btnSelectImage, targetAlpha, targetVisibility)
            if (selectedImageUri != null) { // 只有在有图片时才改变自定义按钮的可见性
                animateViewVisibility(btnCustomizeForeground, targetAlpha, targetVisibility)
            } else { // 如果没图片，自定义按钮始终不可见或禁用
                btnCustomizeForeground.visibility = View.GONE
                btnCustomizeForeground.alpha = 0f
            }
        }
        Log.d(TAG, "onCreate: Activity setup complete")
    }

    private fun animateViewVisibility(view: View, targetAlpha: Float, targetVisibilityIfGone: Int) {
        if (targetAlpha == 1f && view.visibility != View.VISIBLE) {
            view.alpha = 0f // 先设为透明再显示，以播放淡入动画
            view.visibility = View.VISIBLE
        }
        view.animate()
            .alpha(targetAlpha)
            .setDuration(200) // 动画时长
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
        wallpaperPreviewView.setPage1ImageHeightRatio(page1ImageHeightRatio) // WallpaperPreviewView 内部会使用当前焦点重绘
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
        try {
            pickImageLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch gallery picker", e)
            Toast.makeText(this, getString(R.string.image_selection_failed_toast), Toast.LENGTH_SHORT).show()
        }
    }

    private fun extractColorsFromBitmapUri(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val options = BitmapFactory.Options()
            options.inSampleSize = 2 // 为颜色提取简单采样
            originalBitmapForColorExtraction = BitmapFactory.decodeStream(inputStream, null, options)
            inputStream?.close()

            originalBitmapForColorExtraction?.let { bitmap ->
                extractColorsFromLoadedBitmap(bitmap)
            } ?: run {
                Log.e(TAG, "Failed to decode bitmap for color extraction from URI: $uri")
                Toast.makeText(this, getString(R.string.image_load_failed_toast) + " (for color extraction)", Toast.LENGTH_SHORT).show()
                setDefaultColorPaletteAndUpdatePreview()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error loading image for color extraction: $uri", e)
            Toast.makeText(this, getString(R.string.image_load_failed_toast), Toast.LENGTH_SHORT).show()
            setDefaultColorPaletteAndUpdatePreview()
        }
    }

    private fun extractColorsFromLoadedBitmap(bitmap: Bitmap) {
        Palette.from(bitmap).generate { palette ->
            val swatches = listOfNotNull(
                palette?.dominantSwatch,
                palette?.vibrantSwatch,
                palette?.mutedSwatch,
                palette?.lightVibrantSwatch,
                palette?.darkVibrantSwatch,
                palette?.lightMutedSwatch,
                palette?.darkMutedSwatch
            ).distinctBy { it.rgb }.take(8) // 最多取8个不重复的颜色
            val colors = swatches.map { it.rgb }

            val oldSelectedColor = selectedBackgroundColor
            if (colors.isNotEmpty()) {
                populateColorPaletteView(colors)
                // 如果当前选的背景色不在新颜色列表里，或者之前是默认色，或者只有一个颜色，则自动选第一个
                if (selectedBackgroundColor == Color.LTGRAY || !colors.contains(selectedBackgroundColor) || colors.size == 1) {
                    selectedBackgroundColor = colors[0]
                }
            } else { // 如果没提取到颜色
                setDefaultColorPalette()
                // selectedBackgroundColor = Color.LTGRAY; // 可以保持或重置为默认
            }
            wallpaperPreviewView.setSelectedBackgroundColor(selectedBackgroundColor)
            if (oldSelectedColor != selectedBackgroundColor || originalBitmapForColorExtraction != null) {
                // 如果颜色变了，或者是因为新图片导致颜色提取，都保存一下
                savePreferences()
            }
        }
    }

    private fun setDefaultColorPaletteAndUpdatePreview() {
        setDefaultColorPalette()
        wallpaperPreviewView.setSelectedBackgroundColor(selectedBackgroundColor) // 使用当前的selectedBackgroundColor
    }

    private fun setDefaultColorPalette() {
        populateColorPaletteView(listOf(Color.GRAY, Color.DKGRAY, Color.LTGRAY, Color.WHITE, Color.BLACK))
        // selectedBackgroundColor = Color.LTGRAY // 如果需要，在这里重置选择的颜色
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
                savePreferences() // 保存选择的颜色
            }
            colorPaletteContainer.addView(colorView)
        }
    }

    private fun savePreferences() {
        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putInt(KEY_BACKGROUND_COLOR, selectedBackgroundColor)
        editor.putFloat(KEY_IMAGE_HEIGHT_RATIO, page1ImageHeightRatio)
        editor.putFloat(KEY_SCROLL_SENSITIVITY, currentScrollSensitivityFactor)
        editor.putFloat(KEY_P1_FOCUS_X, currentP1FocusX) // 保存P1焦点X
        editor.putFloat(KEY_P1_FOCUS_Y, currentP1FocusY) // 保存P1焦点Y

        selectedImageUri?.let { editor.putString(KEY_IMAGE_URI, it.toString()) } ?: editor.remove(KEY_IMAGE_URI)
        editor.apply()
        Log.d(TAG, "Preferences saved: URI=${selectedImageUri?.toString()}, Color=$selectedBackgroundColor, HeightRatio=$page1ImageHeightRatio, Sensitivity=$currentScrollSensitivityFactor, Focus=($currentP1FocusX, $currentP1FocusY)")
    }

    private fun loadAndApplyPreferencesAndInitState() {
        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        selectedBackgroundColor = prefs.getInt(KEY_BACKGROUND_COLOR, Color.LTGRAY)
        page1ImageHeightRatio = prefs.getFloat(KEY_IMAGE_HEIGHT_RATIO, DEFAULT_HEIGHT_RATIO)
        currentScrollSensitivityFactor = prefs.getFloat(KEY_SCROLL_SENSITIVITY, DEFAULT_SCROLL_SENSITIVITY)
        currentP1FocusX = prefs.getFloat(KEY_P1_FOCUS_X, 0.5f) // 加载P1焦点X
        currentP1FocusY = prefs.getFloat(KEY_P1_FOCUS_Y, 0.5f) // 加载P1焦点Y
        val imageUriString = prefs.getString(KEY_IMAGE_URI, null)

        Log.i(TAG, "Loaded preferences: URI=$imageUriString, Color=$selectedBackgroundColor, Ratio=$page1ImageHeightRatio, Sensitivity=$currentScrollSensitivityFactor, Focus=($currentP1FocusX, $currentP1FocusY)")

        wallpaperPreviewView.setSelectedBackgroundColor(selectedBackgroundColor)
        wallpaperPreviewView.setPage1ImageHeightRatio(page1ImageHeightRatio)
        wallpaperPreviewView.setNormalizedFocus(currentP1FocusX, currentP1FocusY) // 应用加载的焦点

        if (imageUriString != null) {
            selectedImageUri = Uri.parse(imageUriString)
            wallpaperPreviewView.setImageUri(selectedImageUri) // 这会使用上面已设置的焦点
            btnCustomizeForeground.isEnabled = true
            extractColorsFromBitmapUri(selectedImageUri!!) // 从保存的URI提取颜色
        } else {
            selectedImageUri = null
            wallpaperPreviewView.setImageUri(null)
            btnCustomizeForeground.isEnabled = false
            setDefaultColorPalette() // 显示默认颜色
        }
        Log.d(TAG, "Preferences loaded and initial state set for PreviewView.")
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

    // 临时方法，用于测试改变滚动灵敏度 (可以移除或连接到UI)
    private fun changeScrollSensitivity(factor: Float) {
        currentScrollSensitivityFactor = factor.coerceIn(0.1f, 3.0f)
        savePreferences()
        Log.i(TAG, "Scroll sensitivity changed to: $currentScrollSensitivityFactor and saved.")
        Toast.makeText(this, "滚动灵敏度设为: $currentScrollSensitivityFactor (重新设置壁纸后生效)", Toast.LENGTH_LONG).show()
    }
}