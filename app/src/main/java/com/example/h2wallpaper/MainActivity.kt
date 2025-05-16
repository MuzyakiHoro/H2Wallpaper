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
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.palette.graphics.Palette
import java.io.IOException

class MainActivity : AppCompatActivity() {

    // UI控件声明
    private lateinit var btnSelectImage: Button
    private lateinit var colorPaletteContainer: LinearLayout
    private lateinit var btnSetWallpaper: Button
    private lateinit var wallpaperPreviewView: WallpaperPreviewView // 使用新的预览视图
    private lateinit var controlsContainer: LinearLayout
    private lateinit var heightControlsContainer: LinearLayout
    private lateinit var btnHeightReset: Button
    private lateinit var btnHeightIncrease: Button
    private lateinit var btnHeightDecrease: Button

    // 状态变量
    private var selectedImageUri: Uri? = null
    private var selectedBackgroundColor: Int = Color.LTGRAY
    private var originalBitmapForColorExtraction: Bitmap? = null // 用于颜色提取
    private var page1ImageHeightRatio: Float = 1f / 3f
    private var currentScrollSensitivityFactor: Float = 1.0f // 当前的滚动灵敏度因子

    // 常量
    private val HEIGHT_RATIO_STEP = 0.05f
    private val MIN_HEIGHT_RATIO = 0.15f
    private val MAX_HEIGHT_RATIO = 0.60f
    private val DEFAULT_HEIGHT_RATIO = 1f / 3f

    companion object {
        const val PREFS_NAME = "H2WallpaperPrefs"
        const val KEY_IMAGE_URI = "imageUri"
        const val KEY_BACKGROUND_COLOR = "backgroundColor"
        const val KEY_IMAGE_HEIGHT_RATIO = "imageHeightRatio"
        const val KEY_SCROLL_SENSITIVITY = "scrollSensitivity" // SharedPreferences Key for sensitivity
         const val DEFAULT_SCROLL_SENSITIVITY = 1.0f     // 默认滚动灵敏度
        private const val PERMISSION_REQUEST_READ_MEDIA_IMAGES = 1001
        private const val TAG = "H2WallpaperMain"
    }

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    selectedImageUri = uri
                    wallpaperPreviewView.setImageUri(selectedImageUri) // 更新预览视图

                    try {
                        val inputStream = contentResolver.openInputStream(uri)
                        val options = BitmapFactory.Options()
                        options.inSampleSize = 2 // 为颜色提取简单采样
                        originalBitmapForColorExtraction = BitmapFactory.decodeStream(inputStream, null, options)
                        inputStream?.close()

                        originalBitmapForColorExtraction?.let { bitmap ->
                            extractColorsFromBitmap(bitmap)
                        } ?: run {
                            Toast.makeText(this, getString(R.string.image_load_failed_toast) + " (for color extraction)", Toast.LENGTH_SHORT).show()
                            populateColorPaletteView(listOf(Color.GRAY, Color.DKGRAY, Color.LTGRAY))
                            wallpaperPreviewView.setSelectedBackgroundColor(this.selectedBackgroundColor)
                        }
                    } catch (e: IOException) {
                        Log.e(TAG, "Error loading image for color extraction: $uri", e)
                        Toast.makeText(this, getString(R.string.image_load_failed_toast), Toast.LENGTH_SHORT).show()
                        populateColorPaletteView(listOf(Color.GRAY, Color.DKGRAY, Color.LTGRAY))
                        wallpaperPreviewView.setSelectedBackgroundColor(this.selectedBackgroundColor)
                    }
                    savePreferences() // 图片选择后保存URI和其他设置
                } ?: run {
                    Toast.makeText(this, getString(R.string.image_selection_failed_toast) + " (No data URI)", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.d(TAG, "Image selection cancelled or failed, resultCode: ${result.resultCode}")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        btnSelectImage = findViewById(R.id.btnSelectImage)
        colorPaletteContainer = findViewById(R.id.colorPaletteContainer)
        btnSetWallpaper = findViewById(R.id.btnSetWallpaper)
        wallpaperPreviewView = findViewById(R.id.wallpaperPreviewView)
        controlsContainer = findViewById(R.id.controlsContainer)
        heightControlsContainer = findViewById(R.id.heightControlsContainer)
        btnHeightReset = findViewById(R.id.btnHeightReset)
        btnHeightIncrease = findViewById(R.id.btnHeightIncrease)
        btnHeightDecrease = findViewById(R.id.btnHeightDecrease)

        loadAndApplyPreferencesAndInitState() // 加载偏好并初始化状态

        val rootLayoutForInsets: View = findViewById(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayoutForInsets) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (btnSetWallpaper.layoutParams as? ConstraintLayout.LayoutParams)?.apply {
                bottomMargin = systemBars.bottom + (16 * resources.displayMetrics.density).toInt()
            }
            (heightControlsContainer.layoutParams as? ConstraintLayout.LayoutParams)?.apply {
                bottomMargin = systemBars.bottom + (8 * resources.displayMetrics.density).toInt()
            }
            // (controlsContainer.layoutParams as? ConstraintLayout.LayoutParams)?.apply {
            //     bottomMargin = systemBars.bottom + (0 * resources.displayMetrics.density).toInt()
            // }

            btnSetWallpaper.requestLayout()
            heightControlsContainer.requestLayout()
            // controlsContainer.requestLayout()
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
        btnHeightReset.setOnClickListener { updatePage1ImageHeightRatio(DEFAULT_HEIGHT_RATIO) }
        btnHeightIncrease.setOnClickListener { updatePage1ImageHeightRatio((page1ImageHeightRatio + HEIGHT_RATIO_STEP)) }
        btnHeightDecrease.setOnClickListener { updatePage1ImageHeightRatio((page1ImageHeightRatio - HEIGHT_RATIO_STEP)) }

        var controlsAreVisible = true
        wallpaperPreviewView.setOnClickListener {
            controlsAreVisible = !controlsAreVisible
            val targetAlpha = if (controlsAreVisible) 1f else 0f
            val targetVisibility = if (controlsAreVisible) View.VISIBLE else View.GONE

            animateViewVisibility(controlsContainer, targetAlpha, targetVisibility)
            animateViewVisibility(heightControlsContainer, targetAlpha, targetVisibility)
            animateViewVisibility(btnSetWallpaper, targetAlpha, targetVisibility)
        }

        // 临时测试代码：可以在这里调用 changeScrollSensitivity 来测试不同的值
         changeScrollSensitivity(0.5f) // 例如，设置为0.5倍的滚动速度
    }

    private fun animateViewVisibility(view: View, targetAlpha: Float, targetVisibility: Int) {
        if (targetAlpha == 1f && view.visibility != View.VISIBLE) {
            view.alpha = 0f
            view.visibility = View.VISIBLE
        }
        view.animate()
            .alpha(targetAlpha)
            .setDuration(200)
            .withEndAction { if (targetAlpha == 0f) view.visibility = targetVisibility }
            .start()
    }

    private fun updatePage1ImageHeightRatio(newRatio: Float) {
        val clampedRatio = newRatio.coerceIn(MIN_HEIGHT_RATIO, MAX_HEIGHT_RATIO)
        if (page1ImageHeightRatio == clampedRatio) return

        page1ImageHeightRatio = clampedRatio
        wallpaperPreviewView.setPage1ImageHeightRatio(page1ImageHeightRatio)
        savePreferences()
    }

    private fun checkAndRequestReadMediaImagesPermission() {
        val permissionToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
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

    private fun extractColorsFromBitmap(bitmap: Bitmap) {
        Palette.from(bitmap).generate { palette ->
            val swatches = listOfNotNull(
                palette?.dominantSwatch, // 主导色通常最能代表图片整体色调
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
                if (selectedBackgroundColor == Color.LTGRAY || !colors.contains(selectedBackgroundColor) || colors.size == 1) {
                    selectedBackgroundColor = colors[0]
                }
            } else {
                populateColorPaletteView(listOf(Color.GRAY, Color.DKGRAY, Color.LTGRAY, Color.WHITE, Color.BLACK))
                // selectedBackgroundColor = Color.LTGRAY; // 可选：如果无颜色提取则重置
            }
            wallpaperPreviewView.setSelectedBackgroundColor(selectedBackgroundColor)
            if (oldSelectedColor != selectedBackgroundColor || originalBitmapForColorExtraction != null) {
                savePreferences()
            }
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

    private fun savePreferences() {
        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putInt(KEY_BACKGROUND_COLOR, selectedBackgroundColor)
        editor.putFloat(KEY_IMAGE_HEIGHT_RATIO, page1ImageHeightRatio)
        editor.putFloat(KEY_SCROLL_SENSITIVITY, currentScrollSensitivityFactor) // 保存灵敏度

        selectedImageUri?.let { editor.putString(KEY_IMAGE_URI, it.toString()) } ?: editor.remove(KEY_IMAGE_URI)
        editor.apply()
        Log.d(TAG, "Preferences saved: URI=${selectedImageUri?.toString()}, Color=$selectedBackgroundColor, HeightRatio=$page1ImageHeightRatio, Sensitivity=$currentScrollSensitivityFactor")
    }

    private fun loadAndApplyPreferencesAndInitState() {
        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        selectedBackgroundColor = prefs.getInt(KEY_BACKGROUND_COLOR, Color.LTGRAY)
        page1ImageHeightRatio = prefs.getFloat(KEY_IMAGE_HEIGHT_RATIO, DEFAULT_HEIGHT_RATIO)
        currentScrollSensitivityFactor = prefs.getFloat(KEY_SCROLL_SENSITIVITY, DEFAULT_SCROLL_SENSITIVITY) // 加载灵敏度
        val imageUriString = prefs.getString(KEY_IMAGE_URI, null)

        Log.i(TAG, "Loaded preferences: Sensitivity=$currentScrollSensitivityFactor, URI=$imageUriString")

        wallpaperPreviewView.setSelectedBackgroundColor(selectedBackgroundColor)
        wallpaperPreviewView.setPage1ImageHeightRatio(page1ImageHeightRatio)

        if (imageUriString != null) {
            selectedImageUri = Uri.parse(imageUriString)
            wallpaperPreviewView.setImageUri(selectedImageUri) // 让预览视图加载图片

            // 为了初始化颜色选择器，从保存的URI中提取颜色
            // 这个过程现在是 pickImageLauncher 回调和这里都会做，可以考虑优化封装
            try {
                contentResolver.openInputStream(selectedImageUri!!)?.use { inputStream ->
                    val options = BitmapFactory.Options()
                    options.inSampleSize = 2 // 为颜色提取简单采样
                    val bitmapForColorExtraction = BitmapFactory.decodeStream(inputStream, null, options)
                    if (bitmapForColorExtraction != null) {
                        this.originalBitmapForColorExtraction = bitmapForColorExtraction
                        extractColorsFromBitmap(bitmapForColorExtraction)
                    } else {
                        Log.e(TAG, "Failed to decode bitmap for color extraction from saved URI.")
                        populateColorPaletteView(listOf(Color.GRAY, Color.DKGRAY, Color.LTGRAY))
                    }
                } ?: run {
                    Log.e(TAG, "Failed to open input stream for saved URI.")
                    populateColorPaletteView(listOf(Color.GRAY, Color.DKGRAY, Color.LTGRAY))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading saved image URI for color extraction: $imageUriString", e)
                Toast.makeText(this, getString(R.string.loading_saved_image_failed_toast), Toast.LENGTH_SHORT).show()
                populateColorPaletteView(listOf(Color.GRAY, Color.DKGRAY, Color.LTGRAY))
            }
        } else {
            selectedImageUri = null
            originalBitmapForColorExtraction = null
            wallpaperPreviewView.setImageUri(null) // 确保预览视图也清空图片
            populateColorPaletteView(listOf(Color.GRAY, Color.DKGRAY, Color.LTGRAY)) // 显示默认颜色
        }
        Log.d(TAG, "Preferences loaded and initial state set for PreviewView.")
    }

    // 临时方法，用于测试改变滚动灵敏度
    // 将来可以连接到UI控件
    private fun changeScrollSensitivity(factor: Float) {
        currentScrollSensitivityFactor = factor.coerceIn(0.1f, 3.0f) // 限制一下范围
        savePreferences() // 保存新的灵敏度值
        Log.i(TAG, "Scroll sensitivity changed to: $currentScrollSensitivityFactor and saved.")
        Toast.makeText(this, "滚动灵敏度设为: $currentScrollSensitivityFactor (重新设置壁纸后生效)", Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, getString(R.string.wallpaper_set_failed_toast, e.message), Toast.LENGTH_LONG).show()
        }
    }
}