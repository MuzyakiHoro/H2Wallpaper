package com.example.h2wallpaper

import android.Manifest
import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color // Android Graphics Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
// import android.widget.Button // 旧的Button引用可能不再需要，取决于你是否全部移除
// import android.widget.LinearLayout // 旧的LinearLayout引用可能不再需要
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView // 必须导入
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
// LiveData Observer 通常在 androidx.lifecycle.Observer，但我们这里主要通过 ViewModel 的 LiveData.observe()
import com.example.h2wallpaper.ui.theme.H2WallpaperTheme // 你的 Compose 主题
import com.google.android.material.floatingactionbutton.FloatingActionButton


class MainActivity : AppCompatActivity(), MainActivityActions { // 实现接口

    // --- UI 控件声明 ---
    // 旧的控件，按需保留或移除其成员变量声明
    // private lateinit var btnSelectImage: Button
    // private lateinit var colorPaletteContainer: LinearLayout // 改用Compose实现
    // private lateinit var btnSetWallpaper: Button // XML中已隐藏，功能移至BottomSheet
    private lateinit var wallpaperPreviewView: WallpaperPreviewView
    // private lateinit var controlsContainer: LinearLayout // XML中已隐藏
    // private lateinit var heightControlsContainer: LinearLayout // XML中已隐藏
    // private lateinit var btnHeightReset: Button
    // private lateinit var btnHeightIncrease: Button
    // private lateinit var btnHeightDecrease: Button
    // private lateinit var btnCustomizeForeground: Button
    private lateinit var imageLoadingProgressBar: ProgressBar
    // private lateinit var btnAdvancedSettings: Button

    // 新增 ComposeView 和触发按钮的成员变量
    private lateinit var fabOpenConfigPanel: FloatingActionButton
    private lateinit var configBottomSheetComposeView: ComposeView

    // 获取 ViewModel 实例
    private val mainViewModel: MainViewModel by viewModels()

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                mainViewModel.handleImageSelectionResult(result.data?.data)
            } else {
                Log.d(TAG, "Image selection cancelled or failed, resultCode: ${result.resultCode}") //
                if (result.resultCode != Activity.RESULT_CANCELED) { // 如果不是用户主动取消
                    Toast.makeText(this, getString(R.string.image_selection_failed_toast), Toast.LENGTH_SHORT).show() //
                }
            }
        }

    companion object {
        private const val PERMISSION_REQUEST_READ_MEDIA_IMAGES = 1001 //
        private const val TAG = "H2WallpaperMain" //
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ... 窗口设置代码 ...
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
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

        imageLoadingProgressBar = findViewById(R.id.imageLoadingProgressBar)
        wallpaperPreviewView = findViewById(R.id.wallpaperPreviewView)
        fabOpenConfigPanel = findViewById(R.id.fabOpenConfigPanel)
        configBottomSheetComposeView = findViewById(R.id.configBottomSheetComposeView)

        configBottomSheetComposeView.setContent {
            H2WallpaperTheme {
                ConfigBottomSheetContainer(
                    viewModel = mainViewModel,
                    activityActions = this@MainActivity
                )
            }
        }

        // FAB（或其他你指定的XML按钮）的点击事件：打开 BottomSheet
        fabOpenConfigPanel.setOnClickListener {
            // 如果 BottomSheet 已经是打开的，再次点击 FAB 可以选择关闭它，或者什么都不做
            // mainViewModel.openConfigSheet() // 或者 mainViewModel.toggleConfigSheetVisibility()
            if (mainViewModel.showConfigSheet.value) { // 如果想让FAB也能关闭
                mainViewModel.closeConfigSheet()
            } else {
                mainViewModel.openConfigSheet()
            }
        }

        // WallpaperPreviewView 的单击事件：现在总是尝试切换 BottomSheet 的显示/隐藏
        // 由 WallpaperPreviewView 内部的 onSingleTapUp 来决定是否在 P1 编辑模式下触发 performClick()
        wallpaperPreviewView.setOnClickListener {
            // 不再需要在这里判断 isP1EditMode，因为如果是在P1编辑模式下的有效单击，
            // 我们希望 WallpaperPreviewView 内部的 onSingleTapUp 已经调用了 performClick()。
            // 如果是P1编辑手势（非单击），事件应该被消费，不会走到这里。
            Log.d(TAG, "WallpaperPreviewView clicked (MainActivity OnClickListener), toggling config sheet.")
            mainViewModel.toggleConfigSheetVisibility()
        }

        // 确保 WallpaperPreviewView 的其他回调仍然设置
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

        observeViewModel()
        setupWindowInsets()
    }

    // --- 实现 MainActivityActions 接口方法 ---
    override fun requestReadMediaImagesPermission() {
        checkAndRequestReadMediaImagesPermission() // 调用你已有的权限请求方法
    }

    override fun startSettingsActivity() {
        if (mainViewModel.isP1EditMode.value == true) { //
            Toast.makeText(this, "请先完成P1编辑", Toast.LENGTH_SHORT).show() //
            return
        }
        val intent = Intent(this, SettingsActivity::class.java) //
        startActivity(intent)
    }

    override fun promptToSetWallpaper() {
        if (mainViewModel.isP1EditMode.value == true) { //
            Toast.makeText(this, "请先完成P1编辑", Toast.LENGTH_SHORT).show() //
            return
        }
        if (mainViewModel.selectedImageUri.value != null) { //
            mainViewModel.saveNonBitmapConfigAndUpdateVersion() //
            try {
                val cn = ComponentName(packageName, H2WallpaperService::class.java.name) //
                val i = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER) //
                i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, cn) //
                startActivity(i) //
                Toast.makeText(this, getString(R.string.wallpaper_set_prompt_toast), Toast.LENGTH_LONG).show() //
            } catch (e: Exception) {
                Log.e(TAG, "Error trying to set wallpaper", e) //
                Toast.makeText(this, getString(R.string.wallpaper_set_failed_toast, e.message ?: "Unknown error"), Toast.LENGTH_LONG).show() //
            }
        } else {
            Toast.makeText(this, getString(R.string.please_select_image_first_toast), Toast.LENGTH_SHORT).show() //
        }
    }

    private fun observeViewModel() {
        mainViewModel.selectedImageUri.observe(this) { uri ->
            wallpaperPreviewView.setImageUri(uri, true) //
            val isLoading = mainViewModel.isLoading.value ?: false //
            val isEditing = mainViewModel.isP1EditMode.value ?: false //
            // fabOpenConfigPanel.isEnabled = !isLoading // FAB的可用性可能需要更复杂的逻辑

            if (uri == null && isEditing) {
                mainViewModel.toggleP1EditMode() //
            }
        }

        mainViewModel.selectedBackgroundColor.observe(this) { color ->
            wallpaperPreviewView.setSelectedBackgroundColor(color) //
        }

        mainViewModel.page1ImageHeightRatio.observe(this) { ratio ->
            if (mainViewModel.isP1EditMode.value != true) {
                wallpaperPreviewView.setPage1ImageHeightRatio(ratio) //
            }
        }
        mainViewModel.p1FocusX.observe(this) { focusX ->
            if (mainViewModel.isP1EditMode.value != true) { //
                mainViewModel.p1FocusY.value?.let { focusY ->
                    wallpaperPreviewView.setNormalizedFocus(focusX, focusY) //
                }
            }
        }
        mainViewModel.p1FocusY.observe(this) { focusY ->
            if (mainViewModel.isP1EditMode.value != true) { //
                mainViewModel.p1FocusX.value?.let { focusX ->
                    wallpaperPreviewView.setNormalizedFocus(focusX, focusY) //
                }
            }
        }
        mainViewModel.p1ContentScaleFactor.observe(this) { scale ->
            if (mainViewModel.isP1EditMode.value != true) { //
                wallpaperPreviewView.setP1ContentScaleFactor(scale)
                wallpaperPreviewView.invalidate() //
            }
        }

        mainViewModel.isLoading.observe(this) { isLoading ->
            Log.d(TAG, "isLoading Observer: isLoading = $isLoading") //
            imageLoadingProgressBar.visibility = if (isLoading) View.VISIBLE else View.GONE //
            fabOpenConfigPanel.isEnabled = !isLoading // 加载时禁用打开配置按钮
        }

        mainViewModel.toastMessage.observe(this) { event ->
            event.getContentIfNotHandled()?.let { msgContent -> //
                Log.d(TAG, "Toast message content: $msgContent") //
                Toast.makeText(this, msgContent, Toast.LENGTH_LONG).show() //
            }
        }

        // mainViewModel.colorPalette.observe(this) { colors -> populateColorPaletteView(colors) } // 移除，颜色选择在Compose中

        mainViewModel.isP1EditMode.observe(this) { isEditing ->
            Log.d(TAG, "isP1EditMode Observer: isEditing = $isEditing") //

            // P1编辑模式改变时，fabOpenConfigPanel 的可用性通常不受影响，因为BottomSheet中的"完成编辑"需要它
            // fabOpenConfigPanel.isEnabled = !(mainViewModel.isLoading.value ?: false)

            val imageSelected = mainViewModel.selectedImageUri.value != null //
            if (isEditing) {
                if (imageSelected) {
                    wallpaperPreviewView.setP1FocusEditMode(true,
                        mainViewModel.p1FocusX.value, mainViewModel.p1FocusY.value,
                        mainViewModel.page1ImageHeightRatio.value, mainViewModel.p1ContentScaleFactor.value
                    ) //
                } else {
                    mainViewModel.toggleP1EditMode() // 立即退出，因为没有图片无法编辑
                    Toast.makeText(this, getString(R.string.please_select_image_first_toast), Toast.LENGTH_SHORT).show() //
                }
            } else { // 退出编辑模式
                mainViewModel.page1ImageHeightRatio.value?.let { wallpaperPreviewView.setPage1ImageHeightRatio(it) } //
                mainViewModel.p1FocusX.value?.let { fx ->
                    mainViewModel.p1FocusY.value?.let { fy -> wallpaperPreviewView.setNormalizedFocus(fx, fy) } //
                }
                mainViewModel.p1ContentScaleFactor.value?.let { wallpaperPreviewView.setP1ContentScaleFactor(it) } //
                wallpaperPreviewView.setP1FocusEditMode(false) //
            }
            if (isEditing && imageSelected) {
                wallpaperPreviewView.invalidate() //
            }
        }

        wallpaperPreviewView.setOnP1ConfigEditedListener { normX, normY, heightRatio, contentScale ->
            mainViewModel.updateP1ConfigRealtime(normX, normY, heightRatio, contentScale) //
        }
        wallpaperPreviewView.setOnRequestActionCallback { action ->
            when (action) {
                WallpaperPreviewView.PreviewViewAction.REQUEST_CANCEL_P1_EDIT_MODE -> {
                    if (mainViewModel.isP1EditMode.value == true) mainViewModel.toggleP1EditMode() //
                    Toast.makeText(this, "P1编辑已退出", Toast.LENGTH_SHORT).show() //
                }
            }
        }
    }

    private fun setupWindowInsets() {
        val rootLayoutForInsets: View = findViewById(android.R.id.content) //
        ViewCompat.setOnApplyWindowInsetsListener(rootLayoutForInsets) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars()) //
            // 如果 fabOpenConfigPanel 或其他XML控件需要根据导航栏调整边距，可以在这里设置
            // 例如: (fabOpenConfigPanel.layoutParams as? ConstraintLayout.LayoutParams)?.let {
            // it.bottomMargin = systemBars.bottom + (16 * resources.displayMetrics.density).toInt()
            // fabOpenConfigPanel.requestLayout()
            // }
            // 对于 ComposeView 内部的 ModalBottomSheet，可以使用 .navigationBarsPadding() 修饰符来处理底部 insets
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MainActivity onResume.") //
        val prefs = getSharedPreferences(WallpaperConfigConstants.PREFS_NAME, Context.MODE_PRIVATE) //
        val scrollSensitivity = prefs.getInt(WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY, WallpaperConfigConstants.DEFAULT_SCROLL_SENSITIVITY_INT) / 10.0f //
        val p1OverlayFadeRatio = prefs.getInt(WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO, WallpaperConfigConstants.DEFAULT_P1_OVERLAY_FADE_RATIO_INT) / 100.0f //
        val backgroundBlurRadius = prefs.getInt(WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS, WallpaperConfigConstants.DEFAULT_BACKGROUND_BLUR_RADIUS_INT).toFloat() //
        val backgroundInitialOffset = prefs.getInt(WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET, WallpaperConfigConstants.DEFAULT_BACKGROUND_INITIAL_OFFSET_INT) / 10.0f //
        val p2BackgroundFadeInRatio = prefs.getInt(WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO, WallpaperConfigConstants.DEFAULT_P2_BACKGROUND_FADE_IN_RATIO_INT) / 100.0f //
        val blurDownscaleFactorInt = prefs.getInt(WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR, WallpaperConfigConstants.DEFAULT_BLUR_DOWNSCALE_FACTOR_INT) //
        val blurIterations = prefs.getInt(WallpaperConfigConstants.KEY_BLUR_ITERATIONS, WallpaperConfigConstants.DEFAULT_BLUR_ITERATIONS) //
        val p1ShadowRadius = prefs.getInt(WallpaperConfigConstants.KEY_P1_SHADOW_RADIUS, WallpaperConfigConstants.DEFAULT_P1_SHADOW_RADIUS_INT).toFloat() //
        val p1ShadowDx = prefs.getInt(WallpaperConfigConstants.KEY_P1_SHADOW_DX, WallpaperConfigConstants.DEFAULT_P1_SHADOW_DX_INT).toFloat() //
        val p1ShadowDy = prefs.getInt(WallpaperConfigConstants.KEY_P1_SHADOW_DY, WallpaperConfigConstants.DEFAULT_P1_SHADOW_DY_INT).toFloat() //
        val p1ShadowColor = prefs.getInt(WallpaperConfigConstants.KEY_P1_SHADOW_COLOR, WallpaperConfigConstants.DEFAULT_P1_SHADOW_COLOR) //
        val p1ImageBottomFadeHeight = prefs.getInt(WallpaperConfigConstants.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT, WallpaperConfigConstants.DEFAULT_P1_IMAGE_BOTTOM_FADE_HEIGHT_INT).toFloat() //

        wallpaperPreviewView.setConfigValues(
            scrollSensitivity = scrollSensitivity,
            p1OverlayFadeRatio = p1OverlayFadeRatio,
            backgroundBlurRadius = backgroundBlurRadius,
            snapAnimationDurationMs = WallpaperConfigConstants.DEFAULT_PREVIEW_SNAP_DURATION_MS, //
            normalizedInitialBgScrollOffset = backgroundInitialOffset,
            p2BackgroundFadeInRatio = p2BackgroundFadeInRatio,
            blurDownscaleFactor = blurDownscaleFactorInt / 100.0f,
            blurIterations = blurIterations,
            p1ShadowRadius = p1ShadowRadius,
            p1ShadowDx = p1ShadowDx,
            p1ShadowDy = p1ShadowDy,
            p1ShadowColor = p1ShadowColor,
            p1ImageBottomFadeHeight = p1ImageBottomFadeHeight
        ) //

        // 确保 WallpaperPreviewView 的状态在 onResume 时与 ViewModel 同步
        if (mainViewModel.isP1EditMode.value == true) {
            wallpaperPreviewView.setP1FocusEditMode( true,
                mainViewModel.p1FocusX.value, mainViewModel.p1FocusY.value,
                mainViewModel.page1ImageHeightRatio.value, mainViewModel.p1ContentScaleFactor.value
            ) //
        } else {
            wallpaperPreviewView.setP1FocusEditMode(false) //
            mainViewModel.selectedImageUri.value?.let { wallpaperPreviewView.setImageUri(it, false) } ?: wallpaperPreviewView.setImageUri(null, false) //
            mainViewModel.page1ImageHeightRatio.value?.let { wallpaperPreviewView.setPage1ImageHeightRatio(it) } //
            mainViewModel.p1FocusX.value?.let { fx ->
                mainViewModel.p1FocusY.value?.let { fy -> wallpaperPreviewView.setNormalizedFocus(fx, fy) } //
            }
            mainViewModel.p1ContentScaleFactor.value?.let { wallpaperPreviewView.setP1ContentScaleFactor(it) } //
            mainViewModel.selectedBackgroundColor.value?.let { wallpaperPreviewView.setSelectedBackgroundColor(it) } //
        }
        wallpaperPreviewView.invalidate() //
    }

    private fun checkAndRequestReadMediaImagesPermission() {
        val permissionToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE //
        if (ContextCompat.checkSelfPermission(this, permissionToRequest) != PackageManager.PERMISSION_GRANTED) { //
            ActivityCompat.requestPermissions(this, arrayOf(permissionToRequest), PERMISSION_REQUEST_READ_MEDIA_IMAGES) //
        } else {
            openGallery() //
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults) //
        if (requestCode == PERMISSION_REQUEST_READ_MEDIA_IMAGES) { //
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) { //
                openGallery() //
            } else {
                Toast.makeText(this, getString(R.string.permission_needed_toast), Toast.LENGTH_LONG).show() //
            }
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI) //
        try {
            pickImageLauncher.launch(intent) //
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch gallery picker", e) //
            Toast.makeText(this, getString(R.string.image_selection_failed_toast), Toast.LENGTH_SHORT).show() //
        }
    }

    override fun onBackPressed() {
        // 如果 BottomSheet 是打开的，优先关闭 BottomSheet
        if (mainViewModel.showConfigSheet.value) {
            mainViewModel.closeConfigSheet()
        } else if (mainViewModel.isP1EditMode.value == true) {
            mainViewModel.toggleP1EditMode()
            Toast.makeText(this, "已退出P1编辑", Toast.LENGTH_SHORT).show()
        } else {
            super.onBackPressed()
        }
    }
}