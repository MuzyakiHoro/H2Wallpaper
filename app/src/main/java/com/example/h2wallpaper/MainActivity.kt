package com.example.h2wallpaper

import android.Manifest
import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Observer


// 导入 WallpaperConfigConstants 对象
import com.example.h2wallpaper.WallpaperConfigConstants

class MainActivity : AppCompatActivity() {

    // UI 控件声明
    private lateinit var btnSelectImage: Button
    private lateinit var colorPaletteContainer: LinearLayout
    private lateinit var btnSetWallpaper: Button
    private lateinit var wallpaperPreviewView: WallpaperPreviewView
    private lateinit var controlsContainer: LinearLayout // 包含选择图片、高级设置、自定义前景的容器
    private lateinit var heightControlsContainer: LinearLayout // 包含旧的高度控制按钮的容器
    private lateinit var btnHeightReset: Button
    private lateinit var btnHeightIncrease: Button
    private lateinit var btnHeightDecrease: Button
    private lateinit var btnCustomizeForeground: Button
    private lateinit var imageLoadingProgressBar: ProgressBar
    private lateinit var btnAdvancedSettings: Button

    // P1 编辑模式相关UI不再需要单独的“应用/取消”按钮容器和按钮
    // private lateinit var p1EditControlsContainer: LinearLayout // 已移除
    // private lateinit var btnApplyP1Changes: Button // 已移除
    // private lateinit var btnCancelP1Changes: Button // 已移除

    // 获取 ViewModel 实例
    private val mainViewModel: MainViewModel by viewModels()

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                mainViewModel.handleImageSelectionResult(result.data?.data)
            } else {
                Log.d(TAG, "Image selection cancelled or failed, resultCode: ${result.resultCode}")
                if (result.resultCode != Activity.RESULT_CANCELED) { // 如果不是用户主动取消
                    Toast.makeText(this, getString(R.string.image_selection_failed_toast), Toast.LENGTH_SHORT).show()
                }
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
        imageLoadingProgressBar = findViewById(R.id.imageLoadingProgressBar)
        btnAdvancedSettings = findViewById(R.id.btnAdvancedSettings)

        // 移除对 p1EditControlsContainer, btnApplyP1Changes, btnCancelP1Changes 的 findViewById

        setupButtonClickListeners()
        observeViewModel()
        setupWindowInsets()

        Log.d(TAG, "onCreate: Activity setup complete.")
    }

    private fun setupButtonClickListeners() {
        btnSelectImage.setOnClickListener {
            if (mainViewModel.isP1EditMode.value == true) {
                Toast.makeText(this, "请先完成P1编辑", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            checkAndRequestReadMediaImagesPermission()
        }

        btnSetWallpaper.setOnClickListener {
            if (mainViewModel.isP1EditMode.value == true) {
                Toast.makeText(this, "请先完成P1编辑", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (mainViewModel.selectedImageUri.value != null) {
                mainViewModel.saveNonBitmapConfigAndUpdateVersion()
                promptToSetWallpaper()
            } else {
                Toast.makeText(this, getString(R.string.please_select_image_first_toast), Toast.LENGTH_SHORT).show()
            }
        }

        btnAdvancedSettings.setOnClickListener {
            if (mainViewModel.isP1EditMode.value == true) {
                Toast.makeText(this, "请先完成P1编辑", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        btnCustomizeForeground.setOnClickListener {
            if (mainViewModel.selectedImageUri.value != null) {
                mainViewModel.toggleP1EditMode() // 仅切换模式
            } else {
                Toast.makeText(this, getString(R.string.please_select_image_first_toast), Toast.LENGTH_SHORT).show()
            }
        }

        // 移除 btnApplyP1Changes 和 btnCancelP1Changes 的监听器

        btnHeightReset.setOnClickListener {
            if (mainViewModel.isP1EditMode.value == true) {
                Toast.makeText(this, "P1编辑模式下请使用手势调整", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            mainViewModel.updatePage1ImageHeightRatio(WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO)
        }
        btnHeightIncrease.setOnClickListener {
            if (mainViewModel.isP1EditMode.value == true) {
                Toast.makeText(this, "P1编辑模式下请使用手势调整", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val currentRatio = mainViewModel.page1ImageHeightRatio.value ?: WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO
            mainViewModel.updatePage1ImageHeightRatio(currentRatio + WallpaperConfigConstants.HEIGHT_RATIO_STEP)
        }
        btnHeightDecrease.setOnClickListener {
            if (mainViewModel.isP1EditMode.value == true) {
                Toast.makeText(this, "P1编辑模式下请使用手势调整", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val currentRatio = mainViewModel.page1ImageHeightRatio.value ?: WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO
            mainViewModel.updatePage1ImageHeightRatio(currentRatio - WallpaperConfigConstants.HEIGHT_RATIO_STEP)
        }

        var controlsAreVisible = true
        wallpaperPreviewView.setOnClickListener {
            if (mainViewModel.isP1EditMode.value == true) {
                return@setOnClickListener
            }
            controlsAreVisible = !controlsAreVisible
            val targetAlpha = if (controlsAreVisible) 1f else 0f
            animateViewVisibility(controlsContainer, targetAlpha, if (controlsAreVisible) View.VISIBLE else View.GONE)
            animateViewVisibility(heightControlsContainer, targetAlpha, if (controlsAreVisible) View.VISIBLE else View.GONE)
            animateViewVisibility(btnSetWallpaper, targetAlpha, if (controlsAreVisible) View.VISIBLE else View.GONE)
        }
    }

    private fun observeViewModel() {
        mainViewModel.selectedImageUri.observe(this) { uri ->
            wallpaperPreviewView.setImageUri(uri, true)
            val isLoading = mainViewModel.isLoading.value ?: false
            val isEditing = mainViewModel.isP1EditMode.value ?: false
            btnCustomizeForeground.isEnabled = !isLoading && !isEditing && (uri != null)
            if (uri == null && isEditing) {
                mainViewModel.toggleP1EditMode()
            }
        }

        mainViewModel.selectedBackgroundColor.observe(this) { color ->
            wallpaperPreviewView.setSelectedBackgroundColor(color)
        }

        mainViewModel.page1ImageHeightRatio.observe(this) { ratio ->
            if (mainViewModel.isP1EditMode.value != true) {
                wallpaperPreviewView.setPage1ImageHeightRatio(ratio)
            }
        }
        mainViewModel.p1FocusX.observe(this) { focusX ->
            if (mainViewModel.isP1EditMode.value != true) {
                mainViewModel.p1FocusY.value?.let { focusY ->
                    wallpaperPreviewView.setNormalizedFocus(focusX, focusY)
                }
            }
        }
        mainViewModel.p1FocusY.observe(this) { focusY ->
            if (mainViewModel.isP1EditMode.value != true) {
                mainViewModel.p1FocusX.value?.let { focusX ->
                    wallpaperPreviewView.setNormalizedFocus(focusX, focusY)
                }
            }
        }
        mainViewModel.p1ContentScaleFactor.observe(this) { scale ->
            if (mainViewModel.isP1EditMode.value != true) {
                wallpaperPreviewView.setP1ContentScaleFactor(scale) // 确保View有此方法
                wallpaperPreviewView.invalidate()
            }
        }

        mainViewModel.isLoading.observe(this) { isLoading ->
            Log.d(TAG, "isLoading Observer: isLoading = $isLoading")
            imageLoadingProgressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            val currentEditMode = mainViewModel.isP1EditMode.value ?: false
            btnSelectImage.isEnabled = !isLoading && !currentEditMode
            // 更新其他按钮的可用性，因为isLoading变化了
            val imageSelected = mainViewModel.selectedImageUri.value != null
            btnSetWallpaper.isEnabled = !isLoading && !currentEditMode && imageSelected
            btnAdvancedSettings.isEnabled = !isLoading && !currentEditMode
            btnCustomizeForeground.isEnabled = !isLoading && !currentEditMode && imageSelected || currentEditMode
            btnHeightReset.isEnabled = !isLoading && !currentEditMode
            btnHeightIncrease.isEnabled = !isLoading && !currentEditMode
            btnHeightDecrease.isEnabled = !isLoading && !currentEditMode

            Log.d(TAG, "isLoading Observer: btnSelectImage.isEnabled set to ${btnSelectImage.isEnabled}, alpha: ${btnSelectImage.alpha}, visibility: ${btnSelectImage.visibility}")
        }

        mainViewModel.toastMessage.observe(this) { event ->
            val msgContent: String? = event.getContentIfNotHandled()
            if (msgContent != null) {
                Log.d(TAG, "Toast message content: $msgContent")
                Toast.makeText(this, msgContent, Toast.LENGTH_LONG).show()
            }
        }

        mainViewModel.colorPalette.observe(this) { colors -> populateColorPaletteView(colors) }

        mainViewModel.isP1EditMode.observe(this) { isEditing ->
            Log.d(TAG, "isP1EditMode Observer: isEditing = $isEditing")
            // p1EditControlsContainer.visibility 被移除

            val enableNonEditControls = !isEditing
            val currentLoadingState = mainViewModel.isLoading.value ?: false
            val imageSelected = mainViewModel.selectedImageUri.value != null

            btnSelectImage.isEnabled = enableNonEditControls && !currentLoadingState
            btnSetWallpaper.isEnabled = enableNonEditControls && !currentLoadingState && imageSelected
            btnAdvancedSettings.isEnabled = enableNonEditControls && !currentLoadingState
            btnHeightReset.isEnabled = enableNonEditControls && !currentLoadingState
            btnHeightIncrease.isEnabled = enableNonEditControls && !currentLoadingState
            btnHeightDecrease.isEnabled = enableNonEditControls && !currentLoadingState
            btnCustomizeForeground.isEnabled = (enableNonEditControls && !currentLoadingState && imageSelected) || isEditing
            btnCustomizeForeground.text = if(isEditing) "完成编辑" else "自定义前景"

            Log.d(TAG, "isP1EditMode Observer: btnSelectImage.isEnabled set to ${btnSelectImage.isEnabled}, alpha: ${btnSelectImage.alpha}, visibility: ${btnSelectImage.visibility}")

            colorPaletteContainer.alpha = if (isEditing) 0.3f else 1.0f
            for (i in 0 until colorPaletteContainer.childCount) {
                colorPaletteContainer.getChildAt(i).isClickable = enableNonEditControls
            }
            controlsContainer.alpha = if(isEditing) 0.3f else 1.0f
            // controlsContainer.isEnabled = enableNonEditControls; // 可选，统一控制父容器

            if (isEditing) {
                if (imageSelected) {
                    wallpaperPreviewView.setP1FocusEditMode(true,
                        mainViewModel.p1FocusX.value, mainViewModel.p1FocusY.value,
                        mainViewModel.page1ImageHeightRatio.value, mainViewModel.p1ContentScaleFactor.value
                    )
                    Toast.makeText(this, "P1编辑已开启", Toast.LENGTH_SHORT).show()
                } else { // 没有图片不应该能进入编辑模式
                    mainViewModel.toggleP1EditMode() // 立即退出
                    Toast.makeText(this, getString(R.string.please_select_image_first_toast), Toast.LENGTH_SHORT).show()
                }
            } else { // 退出编辑模式
                wallpaperPreviewView.setP1FocusEditMode(false)
                // 确保PreviewView基于最新的主LiveData的值刷新
                mainViewModel.p1FocusX.value?.let { fx ->
                    mainViewModel.p1FocusY.value?.let { fy -> wallpaperPreviewView.setNormalizedFocus(fx, fy) }
                }
                mainViewModel.page1ImageHeightRatio.value?.let { hr -> wallpaperPreviewView.setPage1ImageHeightRatio(hr) }
                mainViewModel.p1ContentScaleFactor.value?.let { wallpaperPreviewView.setP1ContentScaleFactor(it) }
                wallpaperPreviewView.invalidate()
            }
        }

        wallpaperPreviewView.setOnP1ConfigEditedListener { normX, normY, heightRatio, contentScale ->
            mainViewModel.updateP1ConfigRealtime(normX, normY, heightRatio, contentScale)
        }
        wallpaperPreviewView.setOnRequestActionCallback { action ->
            when (action) {
                WallpaperPreviewView.PreviewViewAction.REQUEST_CANCEL_P1_EDIT_MODE -> {
                    if (mainViewModel.isP1EditMode.value == true) mainViewModel.toggleP1EditMode()
                    Toast.makeText(this, "P1编辑已退出", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupWindowInsets() {
        val rootLayoutForInsets: View = findViewById(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayoutForInsets) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (controlsContainer.layoutParams as? ConstraintLayout.LayoutParams)?.let {
                it.bottomMargin = systemBars.bottom + (8 * resources.displayMetrics.density).toInt()
                controlsContainer.requestLayout()
            }
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MainActivity onResume.")
        val prefs = getSharedPreferences(WallpaperConfigConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val scrollSensitivity = prefs.getInt(WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY, WallpaperConfigConstants.DEFAULT_SCROLL_SENSITIVITY_INT) / 10.0f
        val p1OverlayFadeRatio = prefs.getInt(WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO, WallpaperConfigConstants.DEFAULT_P1_OVERLAY_FADE_RATIO_INT) / 100.0f
        val backgroundBlurRadius = prefs.getInt(WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS, WallpaperConfigConstants.DEFAULT_BACKGROUND_BLUR_RADIUS_INT).toFloat()
        val backgroundInitialOffset = prefs.getInt(WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET, WallpaperConfigConstants.DEFAULT_BACKGROUND_INITIAL_OFFSET_INT) / 10.0f
        val p2BackgroundFadeInRatio = prefs.getInt(WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO, WallpaperConfigConstants.DEFAULT_P2_BACKGROUND_FADE_IN_RATIO_INT) / 100.0f
        val blurDownscaleFactorInt = prefs.getInt(WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR, WallpaperConfigConstants.DEFAULT_BLUR_DOWNSCALE_FACTOR_INT)
        val blurIterations = prefs.getInt(WallpaperConfigConstants.KEY_BLUR_ITERATIONS, WallpaperConfigConstants.DEFAULT_BLUR_ITERATIONS)
        val p1ShadowRadius = prefs.getInt(WallpaperConfigConstants.KEY_P1_SHADOW_RADIUS, WallpaperConfigConstants.DEFAULT_P1_SHADOW_RADIUS_INT).toFloat()
        val p1ShadowDx = prefs.getInt(WallpaperConfigConstants.KEY_P1_SHADOW_DX, WallpaperConfigConstants.DEFAULT_P1_SHADOW_DX_INT).toFloat()
        val p1ShadowDy = prefs.getInt(WallpaperConfigConstants.KEY_P1_SHADOW_DY, WallpaperConfigConstants.DEFAULT_P1_SHADOW_DY_INT).toFloat()
        val p1ShadowColor = prefs.getInt(WallpaperConfigConstants.KEY_P1_SHADOW_COLOR, WallpaperConfigConstants.DEFAULT_P1_SHADOW_COLOR)
        val p1ImageBottomFadeHeight = prefs.getInt(WallpaperConfigConstants.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT, WallpaperConfigConstants.DEFAULT_P1_IMAGE_BOTTOM_FADE_HEIGHT_INT).toFloat()

        wallpaperPreviewView.setConfigValues( scrollSensitivity, p1OverlayFadeRatio, backgroundBlurRadius,
            WallpaperConfigConstants.DEFAULT_PREVIEW_SNAP_DURATION_MS, normalizedInitialBgScrollOffset = backgroundInitialOffset,
            p2BackgroundFadeInRatio = p2BackgroundFadeInRatio, blurDownscaleFactor = blurDownscaleFactorInt / 100.0f,
            blurIterations = blurIterations, p1ShadowRadius = p1ShadowRadius, p1ShadowDx = p1ShadowDx,
            p1ShadowDy = p1ShadowDy, p1ShadowColor = p1ShadowColor, p1ImageBottomFadeHeight = p1ImageBottomFadeHeight
        )

        if (mainViewModel.isP1EditMode.value == true) {
            wallpaperPreviewView.setP1FocusEditMode( true,
                mainViewModel.p1FocusX.value, mainViewModel.p1FocusY.value,
                mainViewModel.page1ImageHeightRatio.value, mainViewModel.p1ContentScaleFactor.value
            )
        } else {
            wallpaperPreviewView.setP1FocusEditMode(false)
            mainViewModel.selectedImageUri.value?.let { wallpaperPreviewView.setImageUri(it, false) } ?: wallpaperPreviewView.setImageUri(null, false)
            mainViewModel.page1ImageHeightRatio.value?.let { wallpaperPreviewView.setPage1ImageHeightRatio(it) }
            mainViewModel.p1FocusX.value?.let { fx ->
                mainViewModel.p1FocusY.value?.let { fy -> wallpaperPreviewView.setNormalizedFocus(fx, fy) }
            }
            mainViewModel.p1ContentScaleFactor.value?.let { wallpaperPreviewView.setP1ContentScaleFactor(it) }
            mainViewModel.selectedBackgroundColor.value?.let { wallpaperPreviewView.setSelectedBackgroundColor(it) }
        }
        wallpaperPreviewView.invalidate()
    }

    private fun checkAndRequestReadMediaImagesPermission() { /* ... (保持不变) ... */ val permissionToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE; if (ContextCompat.checkSelfPermission(this, permissionToRequest) != PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this, arrayOf(permissionToRequest), PERMISSION_REQUEST_READ_MEDIA_IMAGES) else openGallery() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) { /* ... (保持不变) ... */ super.onRequestPermissionsResult(requestCode, permissions, grantResults); if (requestCode == PERMISSION_REQUEST_READ_MEDIA_IMAGES) { if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) openGallery() else Toast.makeText(this, getString(R.string.permission_needed_toast), Toast.LENGTH_LONG).show() } }
    private fun openGallery() { /* ... (保持不变) ... */ val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI); try { pickImageLauncher.launch(intent) } catch (e: Exception) { Log.e(TAG, "Failed to launch gallery picker", e); Toast.makeText(this, getString(R.string.image_selection_failed_toast), Toast.LENGTH_SHORT).show() } }
    private fun populateColorPaletteView(colors: List<Int>) { /* ... (保持不变，但内部的isClickable受isP1EditMode影响) ... */ colorPaletteContainer.removeAllViews(); if (colors.isEmpty()) return; val cs = resources.getDimensionPixelSize(R.dimen.palette_color_view_size); val m = resources.getDimensionPixelSize(R.dimen.palette_color_view_margin); for (c in colors) { val v = View(this); val p = LinearLayout.LayoutParams(cs, cs); p.setMargins(m,m,m,m); v.layoutParams = p; v.setBackgroundColor(c); v.setOnClickListener { if (mainViewModel.isP1EditMode.value == true) { Toast.makeText(this, "P1编辑模式下不能更改背景色", Toast.LENGTH_SHORT).show(); return@setOnClickListener }; mainViewModel.updateSelectedBackgroundColor(c) }; v.isClickable = mainViewModel.isP1EditMode.value != true; colorPaletteContainer.addView(v) } }
    private fun animateViewVisibility(view: View, targetAlpha: Float, targetVisibility: Int) {
        /* ... (保持不变) ... */
        if (targetAlpha == 1f && view.visibility != View.VISIBLE) {
            view.alpha = 0f;
            view.visibility = View.VISIBLE
        } else if (targetAlpha == 0f && view.visibility == View.VISIBLE) {
            /* no op before anim */
        }
        view.animate().alpha(targetAlpha).setDuration(200).withEndAction {
            view.visibility = targetVisibility
        }.start()
    }

    private fun promptToSetWallpaper() { /* ... (保持不变) ... */ try { val cn = ComponentName(packageName, H2WallpaperService::class.java.name); val i = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER); i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, cn); startActivity(i); Toast.makeText(this, getString(R.string.wallpaper_set_prompt_toast), Toast.LENGTH_LONG).show() } catch (e: Exception) { Log.e(TAG, "Error trying to set wallpaper", e); Toast.makeText(this, getString(R.string.wallpaper_set_failed_toast, e.message ?: "Unknown error"), Toast.LENGTH_LONG).show() } }

    override fun onBackPressed() {
        if (mainViewModel.isP1EditMode.value == true) {
            mainViewModel.toggleP1EditMode() // 退出编辑模式，因为更改是实时保存的
            Toast.makeText(this, "已退出P1编辑", Toast.LENGTH_SHORT).show()
        } else {
            super.onBackPressed()
        }
    }
}