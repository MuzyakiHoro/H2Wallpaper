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
import androidx.constraintlayout.widget.ConstraintLayout // For adjusting LayoutParams
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
// import androidx.core.graphics.Insets // Not explicitly used in the final version here
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
// import androidx.core.view.updatePadding // Can be useful for insets
import androidx.palette.graphics.Palette
// ViewPager2 and related imports are removed
import java.io.IOException

class MainActivity : AppCompatActivity() {

    // UI控件声明
    private lateinit var btnSelectImage: Button
    private lateinit var colorPaletteContainer: LinearLayout
    private lateinit var btnSetWallpaper: Button
    private lateinit var wallpaperPreviewView: WallpaperPreviewView // 新的预览视图
    private lateinit var controlsContainer: LinearLayout
    private lateinit var heightControlsContainer: LinearLayout
    private lateinit var btnHeightReset: Button
    private lateinit var btnHeightIncrease: Button
    private lateinit var btnHeightDecrease: Button

    // 状态变量
    private var selectedImageUri: Uri? = null
    private var selectedBackgroundColor: Int = Color.LTGRAY
    // originalBitmap 仍然可以用于颜色提取，但预览视图会自己根据URI加载图片
    private var originalBitmapForColorExtraction: Bitmap? = null


    // 图片高度比例相关变量
    private var page1ImageHeightRatio: Float = 1f / 3f
    private val HEIGHT_RATIO_STEP = 0.05f
    private val MIN_HEIGHT_RATIO = 0.15f // 与 WallpaperPreviewView 中的限制可以保持一致
    private val MAX_HEIGHT_RATIO = 0.60f // 与 WallpaperPreviewView 中的限制可以保持一致
    private val DEFAULT_HEIGHT_RATIO = 1f / 3f

    companion object {
        const val PREFS_NAME = "H2WallpaperPrefs"
        const val KEY_IMAGE_URI = "imageUri"
        const val KEY_BACKGROUND_COLOR = "backgroundColor"
        const val KEY_IMAGE_HEIGHT_RATIO = "imageHeightRatio"
        private const val PERMISSION_REQUEST_READ_MEDIA_IMAGES = 1001
        private const val TAG = "H2WallpaperMain"
    }

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    selectedImageUri = uri
                    // 更新预览视图
                    wallpaperPreviewView.setImageUri(selectedImageUri)

                    // 为了提取颜色，我们仍然需要解码一次位图
                    // 考虑性能，这里可以解码一个较小版本的位图用于颜色提取
                    try {
                        val inputStream = contentResolver.openInputStream(uri)
                        // 简单解码，不进行过于复杂的采样，因为仅用于颜色提取
                        // 但如果图片过大，仍有OOM风险，可以加入适当采样
                        val options = BitmapFactory.Options()
                        options.inSampleSize = 2 // 简单采样，减少内存占用
                        originalBitmapForColorExtraction = BitmapFactory.decodeStream(inputStream, null, options)
                        inputStream?.close()

                        originalBitmapForColorExtraction?.let { bitmap ->
                            extractColorsFromBitmap(bitmap)
                        } ?: run {
                            Toast.makeText(this, getString(R.string.image_load_failed_toast) + " (for color extraction)", Toast.LENGTH_SHORT).show()
                            // 即便颜色提取失败，预览视图也已经收到了URI
                            // 可以提供一个默认的颜色面板
                            populateColorPaletteView(listOf(Color.GRAY, Color.DKGRAY, Color.LTGRAY))
                            wallpaperPreviewView.setSelectedBackgroundColor(this.selectedBackgroundColor) // 确保预览使用当前颜色
                        }
                    } catch (e: IOException) {
                        Log.e(TAG, "Error loading image for color extraction: $uri", e)
                        Toast.makeText(this, getString(R.string.image_load_failed_toast), Toast.LENGTH_SHORT).show()
                        populateColorPaletteView(listOf(Color.GRAY, Color.DKGRAY, Color.LTGRAY))
                        wallpaperPreviewView.setSelectedBackgroundColor(this.selectedBackgroundColor)
                    }
                    savePreferences() // 图片选择后就保存URI
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

        setContentView(R.layout.activity_main)

        // 初始化UI控件
        btnSelectImage = findViewById(R.id.btnSelectImage)
        colorPaletteContainer = findViewById(R.id.colorPaletteContainer)
        btnSetWallpaper = findViewById(R.id.btnSetWallpaper)
        wallpaperPreviewView = findViewById(R.id.wallpaperPreviewView) // 获取新预览视图的引用
        controlsContainer = findViewById(R.id.controlsContainer)
        heightControlsContainer = findViewById(R.id.heightControlsContainer)
        btnHeightReset = findViewById(R.id.btnHeightReset)
        btnHeightIncrease = findViewById(R.id.btnHeightIncrease)
        btnHeightDecrease = findViewById(R.id.btnHeightDecrease)

        loadPreferencesAndInitState() // 修改了方法名并调整其内部逻辑

        // 处理窗口边衬区
        val rootLayoutForInsets: View = findViewById(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayoutForInsets) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            (btnSetWallpaper.layoutParams as? ConstraintLayout.LayoutParams)?.apply {
                bottomMargin = systemBars.bottom + (16 * resources.displayMetrics.density).toInt()
            }
            (heightControlsContainer.layoutParams as? ConstraintLayout.LayoutParams)?.apply {
                bottomMargin = systemBars.bottom + (8 * resources.displayMetrics.density).toInt()
            }
            btnSetWallpaper.requestLayout()
            heightControlsContainer.requestLayout()
            insets
        }

        // 设置事件监听器
        btnSelectImage.setOnClickListener { checkAndRequestReadMediaImagesPermission() }
        btnSetWallpaper.setOnClickListener {
            // 注意：现在不直接检查 originalBitmapForColorExtraction 是否为null来判断是否可以设置壁纸
            // 而是检查 selectedImageUri 是否为null
            if (selectedImageUri != null) {
                savePreferences() // 保存所有当前状态
                promptToSetWallpaper()
            } else {
                Toast.makeText(this, getString(R.string.please_select_image_first_toast), Toast.LENGTH_SHORT).show()
            }
        }
        btnHeightReset.setOnClickListener { updatePage1ImageHeightRatio(DEFAULT_HEIGHT_RATIO) }
        btnHeightIncrease.setOnClickListener { updatePage1ImageHeightRatio((page1ImageHeightRatio + HEIGHT_RATIO_STEP)) }
        btnHeightDecrease.setOnClickListener { updatePage1ImageHeightRatio((page1ImageHeightRatio - HEIGHT_RATIO_STEP)) }

        var controlsAreVisible = true
        wallpaperPreviewView.setOnClickListener { // 给新的预览视图设置点击事件
            controlsAreVisible = !controlsAreVisible
            val targetAlpha = if (controlsAreVisible) 1f else 0f
            val targetVisibility = if (controlsAreVisible) View.VISIBLE else View.GONE

            animateViewVisibility(controlsContainer, targetAlpha, targetVisibility)
            animateViewVisibility(heightControlsContainer, targetAlpha, targetVisibility)
            animateViewVisibility(btnSetWallpaper, targetAlpha, targetVisibility)
        }
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
        wallpaperPreviewView.setPage1ImageHeightRatio(page1ImageHeightRatio) // 更新预览视图
        savePreferences() // 保存所有当前状态
    }

    private fun checkAndRequestReadMediaImagesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { // 运行时权限检查主要针对M及以上
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_MEDIA_IMAGES
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_MEDIA_IMAGES),
                    PERMISSION_REQUEST_READ_MEDIA_IMAGES
                )
            } else {
                openGallery()
            }
        } else {
            openGallery() // 对于低于M的版本，权限在安装时授予
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
                palette?.vibrantSwatch, palette?.mutedSwatch, palette?.dominantSwatch,
                palette?.lightVibrantSwatch, palette?.darkVibrantSwatch,
                palette?.lightMutedSwatch, palette?.darkMutedSwatch
            ).distinctBy { it.rgb } // distinctBy 确保颜色不重复
            val colors = swatches.map { it.rgb }.take(8) // 最多取8种颜色

            val oldSelectedColor = selectedBackgroundColor
            if (colors.isNotEmpty()) {
                populateColorPaletteView(colors) // 用提取到的颜色更新UI调色板
                // 如果当前选中的背景色是默认的 LTGRAY，或者不在新提取的颜色列表中，
                // 则自动选择提取到的第一种颜色作为新的背景色。
                if (selectedBackgroundColor == Color.LTGRAY || !colors.contains(selectedBackgroundColor)) {
                    selectedBackgroundColor = colors[0]
                }
            } else {
                // 如果没有提取到颜色，显示一个默认的调色板
                populateColorPaletteView(listOf(Color.GRAY, Color.DKGRAY, Color.LTGRAY, Color.WHITE, Color.BLACK))
                // 可以选择重置 selectedBackgroundColor 为一个安全值，或者保持不变
                // selectedBackgroundColor = Color.LTGRAY; // 如果需要重置
            }

            // 更新预览视图的背景色
            wallpaperPreviewView.setSelectedBackgroundColor(selectedBackgroundColor)

            // 如果颜色选择发生变化，或者刚加载了新图片（即使颜色选择没变），都保存偏好
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
                wallpaperPreviewView.setSelectedBackgroundColor(selectedBackgroundColor) // 更新预览
                savePreferences() // 保存偏好
            }
            colorPaletteContainer.addView(colorView)
        }
    }

    private fun savePreferences() {
        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putInt(KEY_BACKGROUND_COLOR, selectedBackgroundColor)
        editor.putFloat(KEY_IMAGE_HEIGHT_RATIO, page1ImageHeightRatio)

        if (selectedImageUri != null) {
            editor.putString(KEY_IMAGE_URI, selectedImageUri.toString())
        } else {
            editor.remove(KEY_IMAGE_URI)
        }
        editor.apply()
        Log.d(TAG, "Preferences saved: URI=${selectedImageUri?.toString()}, Color=$selectedBackgroundColor, HeightRatio=$page1ImageHeightRatio")
    }

    private fun loadPreferencesAndInitState() {
        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val imageUriString = prefs.getString(KEY_IMAGE_URI, null)
        selectedBackgroundColor = prefs.getInt(KEY_BACKGROUND_COLOR, Color.LTGRAY)
        page1ImageHeightRatio = prefs.getFloat(KEY_IMAGE_HEIGHT_RATIO, DEFAULT_HEIGHT_RATIO)

        // 首先更新预览视图的颜色和高度比例，这些不依赖于图片加载
        wallpaperPreviewView.setSelectedBackgroundColor(selectedBackgroundColor)
        wallpaperPreviewView.setPage1ImageHeightRatio(page1ImageHeightRatio)

        if (imageUriString != null) {
            selectedImageUri = Uri.parse(imageUriString)
            wallpaperPreviewView.setImageUri(selectedImageUri) // 让预览视图自己加载和处理图片

            // 为了初始化颜色选择器，我们仍然需要从保存的URI中提取颜色
            try {
                val inputStream = contentResolver.openInputStream(selectedImageUri!!)
                // 同样，这里解码一个用于颜色提取的位图
                val options = BitmapFactory.Options()
                options.inSampleSize = 2 // 简单采样
                val bitmapForColorExtraction = BitmapFactory.decodeStream(inputStream, null, options)
                inputStream?.close()

                if (bitmapForColorExtraction != null) {
                    this.originalBitmapForColorExtraction = bitmapForColorExtraction // 更新成员变量（如果其他地方仍需要）
                    extractColorsFromBitmap(bitmapForColorExtraction)
                } else {
                    Log.e(TAG, "Failed to load bitmap for color extraction from saved URI.")
                    populateColorPaletteView(listOf(Color.GRAY, Color.DKGRAY, Color.LTGRAY)) // 显示默认颜色
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading saved image URI for color extraction: $imageUriString", e)
                Toast.makeText(this, getString(R.string.loading_saved_image_failed_toast), Toast.LENGTH_SHORT).show()
                populateColorPaletteView(listOf(Color.GRAY, Color.DKGRAY, Color.LTGRAY))
            }
        } else {
            // 没有保存的图片URI
            selectedImageUri = null
            originalBitmapForColorExtraction = null
            wallpaperPreviewView.setImageUri(null) // 确保预览视图也清空图片
            populateColorPaletteView(listOf(Color.GRAY, Color.DKGRAY, Color.LTGRAY)) // 显示默认颜色
        }
        Log.d(TAG, "Preferences loaded and initial state set for PreviewView.")
    }


    private fun promptToSetWallpaper() {
        // WallpaperManager.getInstance(this)
        try {
            val componentName = ComponentName(packageName, H2WallpaperService::class.java.name)
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, componentName)
            // intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // 对于 startActivity 通常不需要 NEW_TASK
            startActivity(intent)
            Toast.makeText(this, getString(R.string.wallpaper_set_prompt_toast), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error trying to set wallpaper", e)
            Toast.makeText(this, getString(R.string.wallpaper_set_failed_toast, e.message), Toast.LENGTH_LONG).show()
        }
    }
}