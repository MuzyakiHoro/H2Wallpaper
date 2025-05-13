package com.example.h2wallpaper // 确保这仍然是你的包名

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
import android.os.Bundle // 注意这里是 android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
// TextView 可能没有直接用到，但如果你的布局中有其他 TextView 需要操作则保留
// import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.palette.graphics.Palette
import java.io.IOException

class MainActivity : AppCompatActivity() {

    // 声明UI控件变量
    private lateinit var btnSelectImage: Button
    private lateinit var ivImagePreviewTop: ImageView
    private lateinit var viewColorPreviewBottom: View
    private lateinit var colorPaletteContainer: LinearLayout
    private lateinit var btnSetWallpaper: Button
    private lateinit var previewContainer: ViewGroup // FrameLayout

    // 存储用户选择的状态
    private var selectedImageUri: Uri? = null
    private var selectedBackgroundColor: Int = Color.LTGRAY // 预览的默认背景色
    private var originalBitmap: Bitmap? = null // 存储加载的原始图片

    // 用于SharedPreferences的常量
    companion object {
        const val PREFS_NAME = "H2WallpaperPrefs"
        const val KEY_IMAGE_URI = "imageUri"
        const val KEY_BACKGROUND_COLOR = "backgroundColor"
        private const val PERMISSION_REQUEST_READ_MEDIA_IMAGES = 1001
        private const val TAG = "H2WallpaperMain"
    }

    // ActivityResultLauncher 用于处理从图库选择图片后的结果
    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) { // 这是 IF
                // 如果 result.data?.data 为 null，这里会直接跳过 let，不会有 else
                result.data?.data?.let { uri ->
                    selectedImageUri = uri
                    try {
                        val inputStream = contentResolver.openInputStream(uri)
                        originalBitmap = BitmapFactory.decodeStream(inputStream)
                        inputStream?.close()

                        originalBitmap?.let { bitmap ->
                            displayImageInPreview(bitmap)
                            extractColorsFromBitmap(bitmap)
                            savePreferences(uri.toString(), selectedBackgroundColor)
                        } ?: run {
                            Toast.makeText(this, getString(R.string.image_load_failed_toast), Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: IOException) {
                        Log.e(TAG, "Error loading image from URI: $uri", e)
                        Toast.makeText(this, getString(R.string.image_load_failed_toast), Toast.LENGTH_SHORT).show()
                    }
                } ?: run { // 这个 run 对应 result.data?.data?.let 的 null 情况
                    // 当 result.data?.data 为 null 时执行 (即 uri 为 null)
                    Toast.makeText(this, getString(R.string.image_selection_failed_toast) + " (No data URI)", Toast.LENGTH_SHORT).show()
                }
            } else { // 这个 ELSE 对应 if (result.resultCode == Activity.RESULT_OK)
                // 当 resultCode 不是 RESULT_OK 时 (例如用户取消了选择)
                Log.d(TAG, "Image selection cancelled or failed, resultCode: ${result.resultCode}")
                // 根据需要，你也可以在这里给用户一个提示，但通常取消操作不需要提示
                // Toast.makeText(this, "图片选择已取消", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // 设置布局文件

        // 初始化UI控件
        btnSelectImage = findViewById(R.id.btnSelectImage)
        ivImagePreviewTop = findViewById(R.id.ivImagePreviewTop)
        viewColorPreviewBottom = findViewById(R.id.viewColorPreviewBottom)
        colorPaletteContainer = findViewById(R.id.colorPaletteContainer)
        btnSetWallpaper = findViewById(R.id.btnSetWallpaper)
        previewContainer = findViewById(R.id.previewContainer)

        // 动态调整预览区域内视图的高度和位置
        // 使用 post 确保在布局完成后获取正确的 previewContainer 高度
        previewContainer.post {
            val totalHeight = previewContainer.height
            val topPartHeight = (totalHeight * 0.33).toInt() // 上三分之一

            val topImageParams = ivImagePreviewTop.layoutParams
            topImageParams.height = topPartHeight
            ivImagePreviewTop.layoutParams = topImageParams
            // ivImagePreviewTop.visibility = View.VISIBLE // 如果默认是gone或invisible

            // viewColorPreviewBottom 的高度已经是 match_parent in FrameLayout, 它会填充整个 previewContainer
            // 我们只需要确保它的背景色正确
            viewColorPreviewBottom.setBackgroundColor(selectedBackgroundColor)
        }

        loadPreferences() // Activity创建时，加载之前保存的设置

        // 设置按钮点击监听器
        btnSelectImage.setOnClickListener {
            checkAndRequestReadMediaImagesPermission()
        }

        btnSetWallpaper.setOnClickListener {
            if (selectedImageUri != null && originalBitmap != null) {
                // 确保最新的选择被保存
                savePreferences(selectedImageUri.toString(), selectedBackgroundColor)
                promptToSetWallpaper()
            } else {
                Toast.makeText(this, getString(R.string.please_select_image_first_toast), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 检查并请求读取媒体图片的权限
    private fun checkAndRequestReadMediaImagesPermission() {
        // 由于 minSdk 是 32 (Android 12L), 我们直接检查 READ_MEDIA_IMAGES
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_IMAGES
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // 请求权限
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES),
                PERMISSION_REQUEST_READ_MEDIA_IMAGES
            )
        } else {
            // 权限已授予，打开图库
            openGallery()
        }
    }

    // 处理权限请求的结果
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_READ_MEDIA_IMAGES) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 权限被授予
                openGallery()
            } else {
                // 权限被拒绝
                Toast.makeText(this, getString(R.string.permission_needed_toast), Toast.LENGTH_LONG).show()
            }
        }
    }

    // 打开系统图库
    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        try {
            pickImageLauncher.launch(intent)
        } catch (e: Exception) { // ActivityNotFoundException 等
            Log.e(TAG, "Failed to launch gallery picker", e)
            Toast.makeText(this, getString(R.string.image_selection_failed_toast), Toast.LENGTH_SHORT).show()
        }
    }

    // 在预览区域显示选择的图片
    private fun displayImageInPreview(bitmap: Bitmap) {
        ivImagePreviewTop.setImageBitmap(bitmap)
        // 确保预览区域的背景颜色也更新，因为图片可能不会完全覆盖ivImagePreviewTop下面的viewColorPreviewBottom
        viewColorPreviewBottom.setBackgroundColor(selectedBackgroundColor)
    }

    // 从Bitmap中提取颜色并更新UI
    private fun extractColorsFromBitmap(bitmap: Bitmap) {
        Palette.from(bitmap).generate { palette ->
            val swatches = listOfNotNull( // 收集所有可用的 Swatch
                palette?.vibrantSwatch,
                palette?.mutedSwatch,
                palette?.dominantSwatch,
                palette?.lightVibrantSwatch,
                palette?.darkVibrantSwatch,
                palette?.lightMutedSwatch,
                palette?.darkMutedSwatch
            ).distinctBy { it.rgb } // 按颜色去重

            val colors = swatches.map { it.rgb }.take(8) // 最多取8个不重复的颜色

            if (colors.isNotEmpty()) {
                populateColorPaletteView(colors)
                // 如果当前选择的背景色是初始默认色，或者不在新提取的颜色中，
                // 则默认选择提取到的第一个颜色作为背景色。
                if (selectedBackgroundColor == Color.LTGRAY || !colors.contains(selectedBackgroundColor)) {
                    if(originalBitmap != null) { // 只有在成功加载图片后才自动改变并保存颜色
                        selectedBackgroundColor = colors[0]
                        viewColorPreviewBottom.setBackgroundColor(selectedBackgroundColor)
                        savePreferences(selectedImageUri.toString(), selectedBackgroundColor)
                    }
                }
            } else {
                // 如果提取不到颜色，可以提供一些默认选项或保持当前颜色
                populateColorPaletteView(listOf(Color.GRAY, Color.DKGRAY, Color.LTGRAY, Color.WHITE, Color.BLACK))
            }
        }
    }

    // 动态创建颜色选择块并添加到UI
    private fun populateColorPaletteView(colors: List<Int>) {
        colorPaletteContainer.removeAllViews() // 清空之前的颜色块
        // 获取一个合适的尺寸作为颜色块的大小，例如使用Material Design的卡片间距
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
                viewColorPreviewBottom.setBackgroundColor(selectedBackgroundColor)
                // 只有在有图片被选中的情况下，更改颜色才保存（避免没有图片时也保存颜色）
                if (originalBitmap != null && selectedImageUri != null) {
                    savePreferences(selectedImageUri.toString(), selectedBackgroundColor)
                }
            }
            colorPaletteContainer.addView(colorView)
        }
    }

    // 保存用户选择到SharedPreferences
    private fun savePreferences(imageUriString: String?, color: Int) {
        if (imageUriString == null) {
            Log.w(TAG, "Attempted to save preferences with null image URI.")
            return // 如果图片URI为空，不保存
        }
        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putString(KEY_IMAGE_URI, imageUriString)
        editor.putInt(KEY_BACKGROUND_COLOR, color)
        editor.apply() // 异步保存
        Log.d(TAG, "Preferences saved: URI=$imageUriString, Color=$color")
    }

    // 从SharedPreferences加载用户之前的选择
    private fun loadPreferences() {
        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val imageUriString = prefs.getString(KEY_IMAGE_URI, null)
        val previouslySelectedColor = prefs.getInt(KEY_BACKGROUND_COLOR, Color.LTGRAY)

        selectedBackgroundColor = previouslySelectedColor // 总是先加载颜色
        viewColorPreviewBottom.setBackgroundColor(selectedBackgroundColor) // 应用加载的颜色到预览

        if (imageUriString != null) {
            selectedImageUri = Uri.parse(imageUriString)
            try {
                val inputStream = contentResolver.openInputStream(selectedImageUri!!)
                originalBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                originalBitmap?.let {
                    displayImageInPreview(it) // 显示图片
                    // 提取颜色，如果颜色已存在且在提取列表中，则不会强制改变
                    // 如果颜色是默认的LTGRAY，extractColorsFromBitmap会尝试用提取的第一个颜色替换
                    extractColorsFromBitmap(it)
                } ?: run {
                    // 如果Bitmap为空（例如，文件被删除或URI无效），清除无效的偏好设置
                    clearImagePreferenceOnError()
                }
            } catch (e: Exception) { // IOException, SecurityException等
                Log.e(TAG, "Error loading saved image URI: $imageUriString", e)
                Toast.makeText(this, getString(R.string.loading_saved_image_failed_toast), Toast.LENGTH_SHORT).show()
                clearImagePreferenceOnError()
            }
        } else {
            // 没有保存的图片URI，可以显示一些默认的颜色选项
            populateColorPaletteView(listOf(Color.GRAY, Color.DKGRAY, Color.LTGRAY))
        }
    }

    // 当加载已存图片URI失败时，清除相关的图片偏好设置
    private fun clearImagePreferenceOnError() {
        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_IMAGE_URI).apply() // 只清除图片URI，颜色选择可以保留
        selectedImageUri = null
        originalBitmap = null
        ivImagePreviewTop.setImageBitmap(null) // 清除预览中的图片
        Log.w(TAG, "Cleared image URI from preferences due to loading error.")
        // 可以考虑也清空颜色选择条，或者显示默认颜色
        populateColorPaletteView(listOf(Color.GRAY, Color.DKGRAY, Color.LTGRAY))
    }

    // 引导用户去系统壁纸选择器设置壁纸
    private fun promptToSetWallpaper() {
        val wallpaperManager = WallpaperManager.getInstance(this)
        try {
            // 使用你的壁纸服务的ComponentName
            val componentName = ComponentName(packageName, H2WallpaperService::class.java.name)

            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, componentName)
            // 在某些设备上，可能需要这个flag来正确启动壁纸选择器或避免崩溃
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Toast.makeText(this, getString(R.string.wallpaper_set_prompt_toast), Toast.LENGTH_LONG).show()

        } catch (e: Exception) { // ActivityNotFoundException, SecurityException 等
            Log.e(TAG, "Error trying to set wallpaper", e)
            Toast.makeText(this, getString(R.string.wallpaper_set_failed_toast, e.message), Toast.LENGTH_LONG).show()
        }
    }
}