package com.example.h2wallpaper

import android.Manifest
import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
// import android.graphics.Color // Android Graphics Color - 如果未使用则移除
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import com.example.h2wallpaper.ui.theme.H2WallpaperTheme
// import com.example.h2wallpaper.WallpaperPreferencesRepository // 通常通过ViewModel访问

/**
 * 应用的主 Activity，作为用户配置动态壁纸的界面。
 * (原有注释保持不变)
 */
class MainActivity : AppCompatActivity(), MainActivityActions {

    private lateinit var imageLoadingProgressBar: ProgressBar
    private lateinit var wallpaperPreviewView: WallpaperPreviewView
    private lateinit var configBottomSheetComposeView: ComposeView

    private val mainViewModel: MainViewModel by viewModels()

    // preferencesRepository 现在主要通过 ViewModel 访问，但如果某些旧逻辑仍直接使用，则保留
    private lateinit var preferencesRepository: WallpaperPreferencesRepository


    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                mainViewModel.handleImageSelectionResult(result.data?.data)
            } else {
                Log.d(TAG, "Image selection cancelled or failed, resultCode: ${result.resultCode}")
                if (result.resultCode != Activity.RESULT_CANCELED) {
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
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            var flags = window.decorView.systemUiVisibility
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            window.decorView.systemUiVisibility = flags
        }

        setContentView(R.layout.activity_main)

        preferencesRepository = WallpaperPreferencesRepository(applicationContext) // 初始化

        imageLoadingProgressBar = findViewById(R.id.imageLoadingProgressBar)
        wallpaperPreviewView = findViewById(R.id.wallpaperPreviewView)
        configBottomSheetComposeView = findViewById(R.id.configBottomSheetComposeView)

        configBottomSheetComposeView.setContent {
            H2WallpaperTheme {
                ConfigBottomSheetContainer(
                    viewModel = mainViewModel,
                    activityActions = this@MainActivity
                )
            }
        }

        wallpaperPreviewView.setOnClickListener {
            Log.d(TAG, "WallpaperPreviewView clicked, toggling config sheet.")
            mainViewModel.toggleConfigSheetVisibility()
        }

        // P1 编辑回调
        wallpaperPreviewView.setOnP1ConfigEditedListener { normX, normY, heightRatio, contentScale ->
            // 根据当前样式类型调用不同的 ViewModel 更新方法
            if (mainViewModel.p1StyleType.value == 1 /* STYLE_B */) {
                // 对于样式B，P1编辑调整的是P1独立背景图的焦点和缩放
                // heightRatio 在样式B中不由手势直接调整
                mainViewModel.updateStyleBP1Config(normX, normY, contentScale)
            } else { // STYLE_A
                mainViewModel.updateP1ConfigRealtime(normX, normY, heightRatio, contentScale)
            }
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

    override fun requestReadMediaImagesPermission() {
        checkAndRequestReadMediaImagesPermission()
    }

    override fun promptToSetWallpaper() {
        if (mainViewModel.isP1EditMode.value == true) {
            Toast.makeText(this, "请先完成P1编辑", Toast.LENGTH_SHORT).show()
            return
        }
        if (mainViewModel.selectedImageUri.value != null) {
            mainViewModel.saveNonBitmapConfigAndUpdateVersion() // 确保所有最新配置已写入
            try {
                val cn = ComponentName(packageName, H2WallpaperService::class.java.name)
                val i = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, cn)
                startActivity(i)
                Toast.makeText(this, getString(R.string.wallpaper_set_prompt_toast), Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "Error trying to set wallpaper", e)
                Toast.makeText(this, getString(R.string.wallpaper_set_failed_toast, e.message ?: "Unknown error"), Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, getString(R.string.please_select_image_first_toast), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 将 ViewModel 中的所有相关配置参数同步到 [WallpaperPreviewView]。
     * 这个方法现在会获取包括样式类型和样式B的参数。
     * 注意：WallpaperPreviewView.setConfigValues 也需要相应扩展。
     */
    private fun syncPreviewViewWithViewModelConfig() {
        Log.d(TAG, "syncPreviewViewWithViewModelConfig called")

        // 从 ViewModel 获取各项配置值
        // 样式 A 的 P1 参数
        val imageHeightRatio = mainViewModel.page1ImageHeightRatio.value ?: WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO
        val p1FocusX = mainViewModel.p1FocusX.value ?: WallpaperConfigConstants.DEFAULT_P1_FOCUS_X
        val p1FocusY = mainViewModel.p1FocusY.value ?: WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y
        val p1ContentScaleFactor = mainViewModel.p1ContentScaleFactor.value ?: WallpaperConfigConstants.DEFAULT_P1_CONTENT_SCALE_FACTOR
        val p1ShadowRadius = mainViewModel.p1ShadowRadius.value ?: WallpaperConfigConstants.DEFAULT_P1_SHADOW_RADIUS
        val p1ShadowDx = mainViewModel.p1ShadowDx.value ?: WallpaperConfigConstants.DEFAULT_P1_SHADOW_DX
        val p1ShadowDy = mainViewModel.p1ShadowDy.value ?: WallpaperConfigConstants.DEFAULT_P1_SHADOW_DY
        val p1ImageBottomFadeHeight = mainViewModel.p1ImageBottomFadeHeight.value ?: WallpaperConfigConstants.DEFAULT_P1_IMAGE_BOTTOM_FADE_HEIGHT

        // 通用参数
        val scrollSensitivity = mainViewModel.scrollSensitivity.value ?: WallpaperConfigConstants.DEFAULT_SCROLL_SENSITIVITY
        val p1OverlayFadeRatio = mainViewModel.p1OverlayFadeRatio.value ?: WallpaperConfigConstants.DEFAULT_P1_OVERLAY_FADE_RATIO
        val backgroundBlurRadius = mainViewModel.backgroundBlurRadius.value ?: WallpaperConfigConstants.DEFAULT_BACKGROUND_BLUR_RADIUS
        val backgroundInitialOffset = mainViewModel.backgroundInitialOffset.value ?: WallpaperConfigConstants.DEFAULT_BACKGROUND_INITIAL_OFFSET
        val p2BackgroundFadeInRatio = mainViewModel.p2BackgroundFadeInRatio.value ?: WallpaperConfigConstants.DEFAULT_P2_BACKGROUND_FADE_IN_RATIO
        val blurDownscaleFactor = mainViewModel.blurDownscaleFactor.value ?: (WallpaperConfigConstants.DEFAULT_BLUR_DOWNSCALE_FACTOR_INT / 100.0f)
        val blurIterations = mainViewModel.blurIterations.value ?: WallpaperConfigConstants.DEFAULT_BLUR_ITERATIONS
        val p1ShadowColorFromRepo = preferencesRepository.getP1ShadowColor() // P1投影颜色通常不常变，可直接从Repo或ViewModel获取

        // 新增：获取样式类型和样式 B 的参数
        val p1StyleType = mainViewModel.p1StyleType.value ?: WallpaperConfigConstants.DEFAULT_P1_STYLE_TYPE
        val styleBMaskAlpha = mainViewModel.styleBMaskAlpha.value ?: WallpaperConfigConstants.DEFAULT_STYLE_B_MASK_ALPHA
        val styleBRotationParamA = mainViewModel.styleBRotationParamA.value ?: WallpaperConfigConstants.DEFAULT_STYLE_B_ROTATION_PARAM_A
        val styleBGapSizeRatio = mainViewModel.styleBGapSizeRatio.value ?: WallpaperConfigConstants.DEFAULT_STYLE_B_GAP_SIZE_RATIO
        val styleBGapPositionYRatio = mainViewModel.styleBGapPositionYRatio.value ?: WallpaperConfigConstants.DEFAULT_STYLE_B_GAP_POSITION_Y_RATIO
        val styleBUpperMaskMaxRotation = mainViewModel.styleBUpperMaskMaxRotation.value ?: WallpaperConfigConstants.DEFAULT_STYLE_B_UPPER_MASK_MAX_ROTATION
        val styleBLowerMaskMaxRotation = mainViewModel.styleBLowerMaskMaxRotation.value ?: WallpaperConfigConstants.DEFAULT_STYLE_B_LOWER_MASK_MAX_ROTATION
        val styleBP1FocusX = mainViewModel.styleBP1FocusX.value ?: WallpaperConfigConstants.DEFAULT_STYLE_B_P1_FOCUS_X
        val styleBP1FocusY = mainViewModel.styleBP1FocusY.value ?: WallpaperConfigConstants.DEFAULT_STYLE_B_P1_FOCUS_Y
        val styleBP1ScaleFactor = mainViewModel.styleBP1ScaleFactor.value ?: WallpaperConfigConstants.DEFAULT_STYLE_B_P1_SCALE_FACTOR
        val styleBMasksFlippedState = mainViewModel.styleBMasksHorizontallyFlipped.value ?: WallpaperConfigConstants.DEFAULT_STYLE_B_MASKS_HORIZONTALLY_FLIPPED
        // ...
        // 调用 WallpaperPreviewView 的 setConfigValues 方法更新其渲染参数
        // ** 你需要在 WallpaperPreviewView.kt 中扩展 setConfigValues 方法以接收这些新参数 **
        wallpaperPreviewView.setConfigValues(
            // 样式 A 的 P1 参数
            page1ImageHeightRatio = imageHeightRatio,
            p1FocusX = p1FocusX, // 样式 A 的焦点X
            p1FocusY = p1FocusY, // 样式 A 的焦点Y
            p1ContentScaleFactor = p1ContentScaleFactor, // 样式 A 的缩放
            p1ShadowRadius = p1ShadowRadius,
            p1ShadowDx = p1ShadowDx,
            p1ShadowDy = p1ShadowDy,
            p1ShadowColor = p1ShadowColorFromRepo,
            p1ImageBottomFadeHeight = p1ImageBottomFadeHeight,

            // 通用参数
            scrollSensitivity = scrollSensitivity,
            p1OverlayFadeRatio = p1OverlayFadeRatio,
            backgroundBlurRadius = backgroundBlurRadius,
            snapAnimationDurationMs = WallpaperConfigConstants.DEFAULT_PREVIEW_SNAP_DURATION_MS,
            normalizedInitialBgScrollOffset = backgroundInitialOffset,
            p2BackgroundFadeInRatio = p2BackgroundFadeInRatio,
            blurDownscaleFactor = blurDownscaleFactor,
            blurIterations = blurIterations,

            // 新增：传递样式类型和样式 B 的参数
            p1StyleType = p1StyleType,
            styleBMaskAlpha = styleBMaskAlpha,
            styleBRotationParamA = styleBRotationParamA,
            styleBGapSizeRatio = styleBGapSizeRatio,
            styleBGapPositionYRatio = styleBGapPositionYRatio,
            styleBUpperMaskMaxRotation = styleBUpperMaskMaxRotation,
            styleBLowerMaskMaxRotation = styleBLowerMaskMaxRotation,
            styleBP1FocusX = styleBP1FocusX,
            styleBP1FocusY = styleBP1FocusY,
            styleBP1ScaleFactor = styleBP1ScaleFactor,
            styleBMasksHorizontallyFlipped = if (p1StyleType == 1) styleBMasksFlippedState else false,
        )
    }


    private fun observeViewModel() {
        mainViewModel.selectedImageUri.observe(this) { uri ->
            wallpaperPreviewView.setImageUri(uri, true)
            if (uri == null && (mainViewModel.isP1EditMode.value == true)) {
                mainViewModel.toggleP1EditMode()
            }
        }

        mainViewModel.selectedBackgroundColor.observe(this) { color ->
            wallpaperPreviewView.setSelectedBackgroundColor(color)
        }

        // --- 观察样式 A 的 P1 参数 ---
        mainViewModel.page1ImageHeightRatio.observe(this) { ratio ->
            if (mainViewModel.p1StyleType.value == WallpaperConfigConstants.DEFAULT_P1_STYLE_TYPE && mainViewModel.isP1EditMode.value != true) {
                wallpaperPreviewView.setPage1ImageHeightRatio(ratio)
            } else if (mainViewModel.p1StyleType.value == 1 /* STYLE_B */ && mainViewModel.isP1EditMode.value == true) {
                // 在样式B的P1编辑模式下，高度不由滑块控制，而是手势作用于P1独立背景图的其他参数
            }
        }
        mainViewModel.p1FocusX.observe(this) { focusX ->
            if (mainViewModel.p1StyleType.value == WallpaperConfigConstants.DEFAULT_P1_STYLE_TYPE && mainViewModel.isP1EditMode.value != true) {
                mainViewModel.p1FocusY.value?.let { focusY ->
                    wallpaperPreviewView.setNormalizedFocus(focusX, focusY) // 这个方法在PreviewView中可能也需要区分样式
                }
            }
        }
        mainViewModel.p1FocusY.observe(this) { focusY ->
            if (mainViewModel.p1StyleType.value == WallpaperConfigConstants.DEFAULT_P1_STYLE_TYPE && mainViewModel.isP1EditMode.value != true) {
                mainViewModel.p1FocusX.value?.let { focusX ->
                    wallpaperPreviewView.setNormalizedFocus(focusX, focusY)
                }
            }
        }
        mainViewModel.p1ContentScaleFactor.observe(this) { scale ->
            if (mainViewModel.p1StyleType.value == WallpaperConfigConstants.DEFAULT_P1_STYLE_TYPE && mainViewModel.isP1EditMode.value != true) {
                wallpaperPreviewView.setP1ContentScaleFactor(scale)
            }
        }
        mainViewModel.p1ShadowRadius.observe(this) { syncPreviewViewWithViewModelConfig() }
        mainViewModel.p1ShadowDx.observe(this) { syncPreviewViewWithViewModelConfig() }
        mainViewModel.p1ShadowDy.observe(this) { syncPreviewViewWithViewModelConfig() }
        mainViewModel.p1ImageBottomFadeHeight.observe(this) { syncPreviewViewWithViewModelConfig() }


        // --- 观察通用高级设置参数 ---
        mainViewModel.scrollSensitivity.observe(this) { syncPreviewViewWithViewModelConfig() }
        mainViewModel.p1OverlayFadeRatio.observe(this) { syncPreviewViewWithViewModelConfig() }
        mainViewModel.p2BackgroundFadeInRatio.observe(this) { syncPreviewViewWithViewModelConfig() }
        mainViewModel.backgroundInitialOffset.observe(this) { syncPreviewViewWithViewModelConfig() }
        mainViewModel.backgroundBlurRadius.observe(this) { syncPreviewViewWithViewModelConfig() }
        mainViewModel.blurDownscaleFactor.observe(this) { syncPreviewViewWithViewModelConfig() }
        mainViewModel.blurIterations.observe(this) { syncPreviewViewWithViewModelConfig() }
        mainViewModel.styleBMasksHorizontallyFlipped.observe(this) { syncPreviewViewWithViewModelConfig() }
        // --- 新增：观察 P1 样式类型和样式 B 的参数 ---
        mainViewModel.p1StyleType.observe(this) { styleType ->
            Log.d(TAG, "P1 Style Type changed to: $styleType, syncing preview.")
            syncPreviewViewWithViewModelConfig()
            // 当样式切换时，如果 P1 编辑模式是激活的，可能需要根据新样式重新初始化 PreviewView 的编辑状态
            if (mainViewModel.isP1EditMode.value == true) {
                val imageSelected = mainViewModel.selectedImageUri.value != null
                if (imageSelected) {
                    if (styleType == 1 /* STYLE_B */) {
                        wallpaperPreviewView.setP1FocusEditMode(true,
                            mainViewModel.styleBP1FocusX.value, mainViewModel.styleBP1FocusY.value,
                            null, // 样式B的高度不由ratio直接控制
                            mainViewModel.styleBP1ScaleFactor.value
                        )
                    } else { // STYLE_A
                        wallpaperPreviewView.setP1FocusEditMode(true,
                            mainViewModel.p1FocusX.value, mainViewModel.p1FocusY.value,
                            mainViewModel.page1ImageHeightRatio.value, mainViewModel.p1ContentScaleFactor.value
                        )
                    }
                }
            }
        }
        mainViewModel.styleBMaskAlpha.observe(this) { syncPreviewViewWithViewModelConfig() }
        mainViewModel.styleBRotationParamA.observe(this) { syncPreviewViewWithViewModelConfig() }
        mainViewModel.styleBGapSizeRatio.observe(this) { syncPreviewViewWithViewModelConfig() }
        mainViewModel.styleBGapPositionYRatio.observe(this) { syncPreviewViewWithViewModelConfig() }
        mainViewModel.styleBP1FocusX.observe(this) {
            // 仅当样式B激活且不在P1编辑模式时，才通过此观察者更新PreviewView的特定参数
            // (在P1编辑模式下，焦点由手势回调直接处理，然后通过syncPreviewViewWithViewModelConfig整体同步)
            if (mainViewModel.p1StyleType.value == 1 && mainViewModel.isP1EditMode.value != true) {
                syncPreviewViewWithViewModelConfig() // 或者调用一个PreviewView的特定方法设置样式B的P1焦点
            }
        }
        mainViewModel.styleBP1FocusY.observe(this) {
            if (mainViewModel.p1StyleType.value == 1 && mainViewModel.isP1EditMode.value != true) {
                syncPreviewViewWithViewModelConfig()
            }
        }
        mainViewModel.styleBP1ScaleFactor.observe(this) {
            if (mainViewModel.p1StyleType.value == 1 && mainViewModel.isP1EditMode.value != true) {
                syncPreviewViewWithViewModelConfig()
            }
        }
        mainViewModel.styleBUpperMaskMaxRotation.observe(this) { syncPreviewViewWithViewModelConfig() }
        mainViewModel.styleBLowerMaskMaxRotation.observe(this) { syncPreviewViewWithViewModelConfig() }


        mainViewModel.toastMessage.observe(this) { event ->
            event.getContentIfNotHandled()?.let { msgContent ->
                Toast.makeText(this, msgContent, Toast.LENGTH_LONG).show()
            }
        }

        mainViewModel.isP1EditMode.observe(this) { isEditing ->
            Log.d(TAG, "isP1EditMode Observer: isEditing = $isEditing")
            val imageSelected = mainViewModel.selectedImageUri.value != null
            val currentStyle = mainViewModel.p1StyleType.value ?: WallpaperConfigConstants.DEFAULT_P1_STYLE_TYPE

            if (isEditing) {
                if (imageSelected) {
                    if (currentStyle == 1 /* STYLE_B */) {
                        wallpaperPreviewView.setP1FocusEditMode(true,
                            mainViewModel.styleBP1FocusX.value,
                            mainViewModel.styleBP1FocusY.value,
                            null, // 样式B的高度不由ratio直接控制
                            mainViewModel.styleBP1ScaleFactor.value
                        )
                    } else { // STYLE_A
                        wallpaperPreviewView.setP1FocusEditMode(true,
                            mainViewModel.p1FocusX.value,
                            mainViewModel.p1FocusY.value,
                            mainViewModel.page1ImageHeightRatio.value,
                            mainViewModel.p1ContentScaleFactor.value
                        )
                    }
                } else {
                    if (isEditing) mainViewModel.toggleP1EditMode() // 无法进入编辑则切回
                    Toast.makeText(this, getString(R.string.please_select_image_first_toast), Toast.LENGTH_SHORT).show()
                }
            } else { // 退出编辑模式
                if (currentStyle == 1 /* STYLE_B */) {
                    // 同步样式B的P1参数到PreviewView (如果PreviewView有特定方法)
                    // 或者依赖 syncPreviewViewWithViewModelConfig()
                } else { // STYLE_A
                    mainViewModel.page1ImageHeightRatio.value?.let { wallpaperPreviewView.setPage1ImageHeightRatio(it) }
                    mainViewModel.p1FocusX.value?.let { fx ->
                        mainViewModel.p1FocusY.value?.let { fy -> wallpaperPreviewView.setNormalizedFocus(fx, fy) }
                    }
                    mainViewModel.p1ContentScaleFactor.value?.let { wallpaperPreviewView.setP1ContentScaleFactor(it) }
                }
                wallpaperPreviewView.setP1FocusEditMode(false) // 通知PreviewView退出编辑模式
            }
            syncPreviewViewWithViewModelConfig() // 确保所有配置同步
            if (isEditing && imageSelected) {
                wallpaperPreviewView.invalidate()
            }
        }
    }

    private fun setupWindowInsets() {
        val rootLayoutForInsets: View = findViewById(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayoutForInsets) { _, insets ->
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MainActivity onResume.")
        syncPreviewViewWithViewModelConfig() // 确保配置是最新的

        val isEditing = mainViewModel.isP1EditMode.value == true
        val imageSelected = mainViewModel.selectedImageUri.value != null
        val currentStyle = mainViewModel.p1StyleType.value ?: WallpaperConfigConstants.DEFAULT_P1_STYLE_TYPE

        if (isEditing) {
            if (imageSelected) {
                if (currentStyle == 1 /* STYLE_B */) {
                    wallpaperPreviewView.setP1FocusEditMode(true,
                        mainViewModel.styleBP1FocusX.value, mainViewModel.styleBP1FocusY.value,
                        null, mainViewModel.styleBP1ScaleFactor.value
                    )
                } else { // STYLE_A
                    wallpaperPreviewView.setP1FocusEditMode(true,
                        mainViewModel.p1FocusX.value, mainViewModel.p1FocusY.value,
                        mainViewModel.page1ImageHeightRatio.value, mainViewModel.p1ContentScaleFactor.value
                    )
                }
            } else {
                mainViewModel.toggleP1EditMode() // 如果不能进入编辑，则退出
            }
        } else { // 非编辑模式
            wallpaperPreviewView.setP1FocusEditMode(false)
            mainViewModel.selectedImageUri.value?.let { wallpaperPreviewView.setImageUri(it, false) } ?: wallpaperPreviewView.setImageUri(null, false)

            if (currentStyle == 1 /* STYLE_B */) {
                // 对于样式B，高度、焦点、缩放由样式B的参数控制，主要通过syncPreviewViewWithViewModelConfig同步
                // wallpaperPreviewView.setPage1ImageHeightRatio() 等特定于样式A的方法不应在此处针对样式B调用
            } else { // STYLE_A
                mainViewModel.page1ImageHeightRatio.value?.let { wallpaperPreviewView.setPage1ImageHeightRatio(it) }
                mainViewModel.p1FocusX.value?.let { fx ->
                    mainViewModel.p1FocusY.value?.let { fy -> wallpaperPreviewView.setNormalizedFocus(fx, fy) }
                }
                mainViewModel.p1ContentScaleFactor.value?.let { wallpaperPreviewView.setP1ContentScaleFactor(it) }
            }
        }
        mainViewModel.selectedBackgroundColor.value?.let { wallpaperPreviewView.setSelectedBackgroundColor(it) }
        wallpaperPreviewView.invalidate()
    }

    private fun checkAndRequestReadMediaImagesPermission() {
        val permissionToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
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

    override fun onBackPressed() {
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