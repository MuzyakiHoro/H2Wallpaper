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
import androidx.core.view.WindowInsetsCompat
import com.example.h2wallpaper.ui.theme.H2WallpaperTheme
import com.google.android.material.floatingactionbutton.FloatingActionButton
// 确保 WallpaperPreferencesRepository 已导入，如果需要直接访问
// import com.example.h2wallpaper.WallpaperPreferencesRepository

class MainActivity : AppCompatActivity(), MainActivityActions {

    private lateinit var imageLoadingProgressBar: ProgressBar
    private lateinit var wallpaperPreviewView: WallpaperPreviewView
    private lateinit var fabOpenConfigPanel: FloatingActionButton
    private lateinit var configBottomSheetComposeView: ComposeView

    private val mainViewModel: MainViewModel by viewModels()

    // 在 MainActivity 中也保留一个 repository 实例，如果需要读取一些非 LiveData 管理的配置
    // 例如 p1ShadowColor，或者直接在 ViewModel 中为所有这些项提供 LiveData。
    // 为了与 syncPreviewViewWithViewModelConfig 保持一致，这里也初始化它。
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
        // 保持状态栏和导航栏图标为浅色主题（亮色背景对应深色图标）
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
        fabOpenConfigPanel = findViewById(R.id.fabOpenConfigPanel)
        configBottomSheetComposeView = findViewById(R.id.configBottomSheetComposeView)

        configBottomSheetComposeView.setContent {
            H2WallpaperTheme { // 可以根据系统主题动态切换 H2WallpaperTheme 的 darkTheme
                ConfigBottomSheetContainer(
                    viewModel = mainViewModel,
                    activityActions = this@MainActivity
                )
            }
        }

        fabOpenConfigPanel.setOnClickListener {
            if (mainViewModel.showConfigSheet.value) {
                mainViewModel.closeConfigSheet()
            } else {
                mainViewModel.openConfigSheet()
            }
        }

        wallpaperPreviewView.setOnClickListener {
            Log.d(TAG, "WallpaperPreviewView clicked (MainActivity OnClickListener), toggling config sheet.")
            mainViewModel.toggleConfigSheetVisibility()
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

        observeViewModel()
        setupWindowInsets()
    }

    override fun requestReadMediaImagesPermission() {
        checkAndRequestReadMediaImagesPermission()
    }

    override fun startSettingsActivity() {
        if (mainViewModel.isP1EditMode.value == true) {
            Toast.makeText(this, "请先完成P1编辑", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    override fun promptToSetWallpaper() {
        if (mainViewModel.isP1EditMode.value == true) {
            Toast.makeText(this, "请先完成P1编辑", Toast.LENGTH_SHORT).show()
            return
        }
        if (mainViewModel.selectedImageUri.value != null) {
            mainViewModel.saveNonBitmapConfigAndUpdateVersion() // 确保在设置壁纸前，所有非图片相关的配置（例如通过滑块调整的）都已触发版本更新
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

    private fun syncPreviewViewWithViewModelConfig() {
        Log.d(TAG, "syncPreviewViewWithViewModelConfig called")
        val scrollSensitivity = mainViewModel.scrollSensitivity.value ?: preferencesRepository.getScrollSensitivity()
        val p1OverlayFadeRatio = mainViewModel.p1OverlayFadeRatio.value ?: preferencesRepository.getP1OverlayFadeRatio()
        val backgroundBlurRadius = mainViewModel.backgroundBlurRadius.value ?: preferencesRepository.getBackgroundBlurRadius()
        val backgroundInitialOffset = mainViewModel.backgroundInitialOffset.value ?: preferencesRepository.getBackgroundInitialOffset()
        val p2BackgroundFadeInRatio = mainViewModel.p2BackgroundFadeInRatio.value ?: preferencesRepository.getP2BackgroundFadeInRatio()
        val blurDownscaleFactor = mainViewModel.blurDownscaleFactor.value ?: (preferencesRepository.getBlurDownscaleFactorInt() / 100.0f)
        val blurIterations = mainViewModel.blurIterations.value ?: preferencesRepository.getBlurIterations()
        val p1ShadowRadius = mainViewModel.p1ShadowRadius.value ?: preferencesRepository.getP1ShadowRadius()
        val p1ShadowDx = mainViewModel.p1ShadowDx.value ?: preferencesRepository.getP1ShadowDx()
        val p1ShadowDy = mainViewModel.p1ShadowDy.value ?: preferencesRepository.getP1ShadowDy()
        val p1ShadowColorFromRepo = preferencesRepository.getP1ShadowColor() // p1ShadowColor 通常不通过 ViewModel 的 Float LiveData 控制
        val p1ImageBottomFadeHeight = mainViewModel.p1ImageBottomFadeHeight.value ?: preferencesRepository.getP1ImageBottomFadeHeight()

        wallpaperPreviewView.setConfigValues(
            scrollSensitivity = scrollSensitivity,
            p1OverlayFadeRatio = p1OverlayFadeRatio,
            backgroundBlurRadius = backgroundBlurRadius,
            snapAnimationDurationMs = WallpaperConfigConstants.DEFAULT_PREVIEW_SNAP_DURATION_MS,
            normalizedInitialBgScrollOffset = backgroundInitialOffset,
            p2BackgroundFadeInRatio = p2BackgroundFadeInRatio,
            blurDownscaleFactor = blurDownscaleFactor,
            blurIterations = blurIterations,
            p1ShadowRadius = p1ShadowRadius,
            p1ShadowDx = p1ShadowDx,
            p1ShadowDy = p1ShadowDy,
            p1ShadowColor = p1ShadowColorFromRepo, // 直接从 repo 获取或做成 LiveData
            p1ImageBottomFadeHeight = p1ImageBottomFadeHeight
        )
        // 通常 setConfigValues 内部会调用 invalidate()，如果它会影响渲染的话。
        // 如果没有，你可能需要显式调用 wallpaperPreviewView.invalidate()
    }


    private fun observeViewModel() {
        mainViewModel.selectedImageUri.observe(this) { uri ->
            wallpaperPreviewView.setImageUri(uri, true) // forceReload = true for new URI
            // fabOpenConfigPanel.isEnabled = !(mainViewModel.isLoading.value ?: false) // FAB的可用性可能还取决于是否有图片
            if (uri == null && (mainViewModel.isP1EditMode.value == true)) {
                mainViewModel.toggleP1EditMode()
            }
            // 当图片变化时，所有依赖于图片的配置（如模糊、P1参数）都需要用新图重新生成，
            // PreviewView 的 setImageUri 内部的 loadFullBitmapsFromUri 会处理这个。
            // 同时，ViewModel 中与图片相关的 LiveData (如颜色板，默认背景色) 也会更新。
            // syncPreviewViewWithViewModelConfig() // 确保其他配置也与当前状态同步
        }

        mainViewModel.selectedBackgroundColor.observe(this) { color ->
            wallpaperPreviewView.setSelectedBackgroundColor(color)
            // syncPreviewViewWithViewModelConfig() // 背景色也可能影响整体渲染
        }

        // P1 相关参数（非编辑模式下由ViewModel驱动）
        mainViewModel.page1ImageHeightRatio.observe(this) { ratio ->
            if (mainViewModel.isP1EditMode.value != true) {
                wallpaperPreviewView.setPage1ImageHeightRatio(ratio)
            }
            // syncPreviewViewWithViewModelConfig() // 高度变化也应同步到setConfigValues如果需要
        }
        mainViewModel.p1FocusX.observe(this) { focusX ->
            if (mainViewModel.isP1EditMode.value != true) {
                mainViewModel.p1FocusY.value?.let { focusY ->
                    wallpaperPreviewView.setNormalizedFocus(focusX, focusY)
                }
            }
            // syncPreviewViewWithViewModelConfig()
        }
        mainViewModel.p1FocusY.observe(this) { focusY ->
            if (mainViewModel.isP1EditMode.value != true) {
                mainViewModel.p1FocusX.value?.let { focusX ->
                    wallpaperPreviewView.setNormalizedFocus(focusX, focusY)
                }
            }
            // syncPreviewViewWithViewModelConfig()
        }
        mainViewModel.p1ContentScaleFactor.observe(this) { scale ->
            if (mainViewModel.isP1EditMode.value != true) {
                wallpaperPreviewView.setP1ContentScaleFactor(scale)
            }
            // syncPreviewViewWithViewModelConfig()
        }

        // 高级设置参数的观察者
        mainViewModel.scrollSensitivity.observe(this) { syncPreviewViewWithViewModelConfig() }
        mainViewModel.p1OverlayFadeRatio.observe(this) { syncPreviewViewWithViewModelConfig() }
        mainViewModel.p2BackgroundFadeInRatio.observe(this) { syncPreviewViewWithViewModelConfig() }
        mainViewModel.backgroundInitialOffset.observe(this) { syncPreviewViewWithViewModelConfig() }
        mainViewModel.backgroundBlurRadius.observe(this) { syncPreviewViewWithViewModelConfig() }
        mainViewModel.blurDownscaleFactor.observe(this) { syncPreviewViewWithViewModelConfig() }
        mainViewModel.blurIterations.observe(this) { syncPreviewViewWithViewModelConfig() }
        mainViewModel.p1ShadowRadius.observe(this) { syncPreviewViewWithViewModelConfig() }
        mainViewModel.p1ShadowDx.observe(this) { syncPreviewViewWithViewModelConfig() }
        mainViewModel.p1ShadowDy.observe(this) { syncPreviewViewWithViewModelConfig() }
        mainViewModel.p1ImageBottomFadeHeight.observe(this) { syncPreviewViewWithViewModelConfig() }
        // 注意: p1ShadowColor 没有对应的 LiveData (在 ViewModel 中)，所以 syncPreviewViewWithViewModelConfig()
        // 会直接从 preferencesRepository 读取。如果希望它也通过 LiveData 驱动，需要在 ViewModel 添加。


        mainViewModel.isLoading.observe(this) { isLoading ->
            Log.d(TAG, "isLoading Observer: isLoading = $isLoading")
            imageLoadingProgressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            fabOpenConfigPanel.isEnabled = !isLoading
        }

        mainViewModel.toastMessage.observe(this) { event ->
            event.getContentIfNotHandled()?.let { msgContent ->
                Log.d(TAG, "Toast message content: $msgContent")
                Toast.makeText(this, msgContent, Toast.LENGTH_LONG).show()
            }
        }

        mainViewModel.isP1EditMode.observe(this) { isEditing ->
            Log.d(TAG, "isP1EditMode Observer: isEditing = $isEditing")
            val imageSelected = mainViewModel.selectedImageUri.value != null
            if (isEditing) {
                if (imageSelected) {
                    wallpaperPreviewView.setP1FocusEditMode(true,
                        mainViewModel.p1FocusX.value, mainViewModel.p1FocusY.value,
                        mainViewModel.page1ImageHeightRatio.value, mainViewModel.p1ContentScaleFactor.value
                    )
                } else {
                    // 如果在没有图片的情况下尝试进入编辑模式，ViewModel应该处理并可能立即退出
                    if (isEditing) mainViewModel.toggleP1EditMode() // 强制退出
                    Toast.makeText(this, getString(R.string.please_select_image_first_toast), Toast.LENGTH_SHORT).show()
                }
            } else { // 退出编辑模式
                // 确保 PreviewView 的 P1 参数与 ViewModel 同步
                mainViewModel.page1ImageHeightRatio.value?.let { wallpaperPreviewView.setPage1ImageHeightRatio(it) }
                mainViewModel.p1FocusX.value?.let { fx ->
                    mainViewModel.p1FocusY.value?.let { fy -> wallpaperPreviewView.setNormalizedFocus(fx, fy) }
                }
                mainViewModel.p1ContentScaleFactor.value?.let { wallpaperPreviewView.setP1ContentScaleFactor(it) }
                wallpaperPreviewView.setP1FocusEditMode(false)
            }
            // 确保在模式切换后，PreviewView 的整体配置是最新的
            syncPreviewViewWithViewModelConfig() // 在模式切换后也同步一次总配置
            if (isEditing && imageSelected) { // 如果是进入编辑模式且有图
                wallpaperPreviewView.invalidate() // 确保 P1 编辑模式正确渲染
            }
        }
    }

    private fun setupWindowInsets() {
        val rootLayoutForInsets: View = findViewById(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayoutForInsets) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 对于 ComposeView 内部的 ModalBottomSheet，它使用 .navigationBarsPadding() 处理
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MainActivity onResume.")
        // 在 onResume 时，ViewModel 已经从 SharedPreferences 加载了初始值到 LiveData
        // 所以我们调用 syncPreviewViewWithViewModelConfig 来确保 PreviewView 与 ViewModel 同步
        syncPreviewViewWithViewModelConfig()

        // P1编辑模式的同步也需要考虑
        if (mainViewModel.isP1EditMode.value == true) {
            if (mainViewModel.selectedImageUri.value != null) {
                wallpaperPreviewView.setP1FocusEditMode(true,
                    mainViewModel.p1FocusX.value, mainViewModel.p1FocusY.value,
                    mainViewModel.page1ImageHeightRatio.value, mainViewModel.p1ContentScaleFactor.value
                )
            } else {
                // 如果恢复时发现处于编辑模式但没有图片，则退出编辑模式
                mainViewModel.toggleP1EditMode()
            }
        } else {
            wallpaperPreviewView.setP1FocusEditMode(false)
            // 确保非编辑模式下的P1参数也从ViewModel同步
            mainViewModel.selectedImageUri.value?.let { wallpaperPreviewView.setImageUri(it, false) } ?: wallpaperPreviewView.setImageUri(null, false)
            mainViewModel.page1ImageHeightRatio.value?.let { wallpaperPreviewView.setPage1ImageHeightRatio(it) }
            mainViewModel.p1FocusX.value?.let { fx ->
                mainViewModel.p1FocusY.value?.let { fy -> wallpaperPreviewView.setNormalizedFocus(fx, fy) }
            }
            mainViewModel.p1ContentScaleFactor.value?.let { wallpaperPreviewView.setP1ContentScaleFactor(it) }
        }
        mainViewModel.selectedBackgroundColor.value?.let { wallpaperPreviewView.setSelectedBackgroundColor(it) }
        wallpaperPreviewView.invalidate() // 确保在 onResume 后重绘一次
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