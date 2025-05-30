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
// import com.google.android.material.floatingactionbutton.FloatingActionButton // 未使用，可以移除
// import com.example.h2wallpaper.WallpaperPreferencesRepository // 通常通过ViewModel访问

/**
 * 应用的主 Activity，作为用户配置动态壁纸的界面。
 * 它包含一个 [WallpaperPreviewView] 用于实时预览壁纸效果，
 * 以及一个通过 [ComposeView] 实现的底部配置面板 ([ConfigBottomSheetContainer])。
 * Activity 实现了 [MainActivityActions] 接口，以供 Compose UI 回调执行特定操作。
 */
class MainActivity : AppCompatActivity(), MainActivityActions {

    /** 用于显示图片加载进度的 ProgressBar。*/
    private lateinit var imageLoadingProgressBar: ProgressBar //
    /** 自定义的壁纸预览 View。*/
    private lateinit var wallpaperPreviewView: WallpaperPreviewView //
    /** 用于承载底部配置面板 (Jetpack Compose UI) 的 ComposeView。*/
    private lateinit var configBottomSheetComposeView: ComposeView //

    /** 通过 KTX 扩展委托方式获取 MainViewModel 实例。*/
    private val mainViewModel: MainViewModel by viewModels() //

    /** 用于访问 SharedPreferences 的仓库实例 (早期可能直接使用，现在主要通过 ViewModel)。*/
    private lateinit var preferencesRepository: WallpaperPreferencesRepository //

    /**
     * ActivityResultLauncher 用于处理从图片选择器返回的结果。
     * 当用户选择一张图片后，会调用 [MainViewModel.handleImageSelectionResult] 处理。
     */
    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                mainViewModel.handleImageSelectionResult(result.data?.data) //
            } else {
                Log.d(TAG, "Image selection cancelled or failed, resultCode: ${result.resultCode}")
                if (result.resultCode != Activity.RESULT_CANCELED) { // 如果不是用户主动取消
                    Toast.makeText(this, getString(R.string.image_selection_failed_toast), Toast.LENGTH_SHORT).show() //
                }
            }
        }

    companion object {
        /** 请求读取媒体图片权限的请求码。*/
        private const val PERMISSION_REQUEST_READ_MEDIA_IMAGES = 1001
        /** MainActivity 的日志标签。*/
        private const val TAG = "H2WallpaperMain"
    }

    /**
     * Activity 创建时调用。
     * 初始化视图、设置窗口样式 (沉浸式状态栏/导航栏)、
     * 初始化 ViewModel 和 SharedPreferences Repository、
     * 设置 ComposeView 的内容、为 WallpaperPreviewView 设置监听器，
     * 并开始观察 ViewModel 中的数据变化。
     * @param savedInstanceState 如果 Activity 被重新创建，此参数包含之前保存的状态。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 配置窗口以实现边到边 (edge-to-edge) 的沉浸式效果
        WindowCompat.setDecorFitsSystemWindows(window, false) //
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = android.graphics.Color.TRANSPARENT // 状态栏透明
            window.navigationBarColor = android.graphics.Color.TRANSPARENT // 导航栏透明
        }
        // 设置状态栏和导航栏图标为浅色主题（亮色背景，深色图标）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            var flags = window.decorView.systemUiVisibility
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR //
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR //
            }
            window.decorView.systemUiVisibility = flags
        }

        setContentView(R.layout.activity_main) // 加载 XML 布局

        preferencesRepository = WallpaperPreferencesRepository(applicationContext) // 初始化仓库

        // 获取布局中的 View 实例
        imageLoadingProgressBar = findViewById(R.id.imageLoadingProgressBar) //
        wallpaperPreviewView = findViewById(R.id.wallpaperPreviewView) //
        configBottomSheetComposeView = findViewById(R.id.configBottomSheetComposeView) //

        // 为 ComposeView 设置其 Compose 内容
        configBottomSheetComposeView.setContent {
            H2WallpaperTheme { // 应用 Compose 主题
                ConfigBottomSheetContainer( // 底部配置面板的 Compose UI
                    viewModel = mainViewModel,
                    activityActions = this@MainActivity // 将 Activity 作为回调接口传递
                )
            }
        }

        // 设置 WallpaperPreviewView 的点击监听器
        wallpaperPreviewView.setOnClickListener { //
            Log.d(TAG, "WallpaperPreviewView clicked (MainActivity OnClickListener), toggling config sheet.")
            mainViewModel.toggleConfigSheetVisibility() // 点击预览视图时，切换底部配置面板的显示状态
        }

        // 设置 WallpaperPreviewView 的 P1 配置编辑回调监听器
        wallpaperPreviewView.setOnP1ConfigEditedListener { normX, normY, heightRatio, contentScale -> //
            mainViewModel.updateP1ConfigRealtime(normX, normY, heightRatio, contentScale) //
        }
        // 设置 WallpaperPreviewView 的请求动作回调监听器
        wallpaperPreviewView.setOnRequestActionCallback { action -> //
            when (action) {
                WallpaperPreviewView.PreviewViewAction.REQUEST_CANCEL_P1_EDIT_MODE -> { //
                    // 如果 PreviewView 请求取消 P1 编辑模式（例如因缺少图片无法进入）
                    if (mainViewModel.isP1EditMode.value == true) mainViewModel.toggleP1EditMode() //
                    Toast.makeText(this, "P1编辑已退出", Toast.LENGTH_SHORT).show()
                }
            }
        }

        observeViewModel() // 开始观察 ViewModel 中的数据变化
        setupWindowInsets() // 处理窗口边衬区，确保 UI 元素不与系统栏重叠
    }

    /**
     * 实现 [MainActivityActions] 接口的方法。
     * 当底部配置面板中的 "选择图片" 按钮被点击时，由 Compose UI 回调此方法，
     * 触发检查并请求读取媒体图片的权限。
     */
    override fun requestReadMediaImagesPermission() {
        checkAndRequestReadMediaImagesPermission() //
    }


    /**
     * 实现 [MainActivityActions] 接口的方法。
     * 当底部配置面板中的 "应用壁纸" 按钮被点击时，由 Compose UI 回调此方法。
     * 此方法会检查是否已选择图片，如果是，则尝试启动系统的动态壁纸设置流程，
     * 将本应用的 [H2WallpaperService] 设置为动态壁纸。
     */
    override fun promptToSetWallpaper() {
        // 如果当前处于P1编辑模式，则提示用户先完成编辑
        if (mainViewModel.isP1EditMode.value == true) {
            Toast.makeText(this, "请先完成P1编辑", Toast.LENGTH_SHORT).show()
            return
        }
        // 必须先选择图片才能设置壁纸
        if (mainViewModel.selectedImageUri.value != null) {
            // 保存任何非位图相关的配置（例如滑块调整的参数），并更新版本号
            // 以确保壁纸服务能加载到最新的配置。
            mainViewModel.saveNonBitmapConfigAndUpdateVersion() //
            try {
                val componentName = ComponentName(packageName, H2WallpaperService::class.java.name) //
                val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER) //
                intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, componentName) //
                startActivity(intent) // 启动系统壁纸选择器
                Toast.makeText(this, getString(R.string.wallpaper_set_prompt_toast), Toast.LENGTH_LONG).show() //
            } catch (e: Exception) {
                Log.e(TAG, "Error trying to set wallpaper", e)
                Toast.makeText(this, getString(R.string.wallpaper_set_failed_toast, e.message ?: "Unknown error"), Toast.LENGTH_LONG).show() //
            }
        } else {
            Toast.makeText(this, getString(R.string.please_select_image_first_toast), Toast.LENGTH_SHORT).show() //
        }
    }

    /**
     * 将 ViewModel 中的高级渲染配置参数同步到 [WallpaperPreviewView]。
     * 当这些参数通过底部配置面板发生变化时，此方法会被调用（通常通过观察 ViewModel 的 LiveData）。
     */
    private fun syncPreviewViewWithViewModelConfig() {
        Log.d(TAG, "syncPreviewViewWithViewModelConfig called")
        // 从 ViewModel 获取各项配置值，如果 ViewModel 中尚未加载，则从 Repository 获取默认值或已存值
        val scrollSensitivity = mainViewModel.scrollSensitivity.value ?: preferencesRepository.getScrollSensitivity() //
        val p1OverlayFadeRatio = mainViewModel.p1OverlayFadeRatio.value ?: preferencesRepository.getP1OverlayFadeRatio() //
        val backgroundBlurRadius = mainViewModel.backgroundBlurRadius.value ?: preferencesRepository.getBackgroundBlurRadius() //
        val backgroundInitialOffset = mainViewModel.backgroundInitialOffset.value ?: preferencesRepository.getBackgroundInitialOffset() //
        val p2BackgroundFadeInRatio = mainViewModel.p2BackgroundFadeInRatio.value ?: preferencesRepository.getP2BackgroundFadeInRatio() //
        val blurDownscaleFactor = mainViewModel.blurDownscaleFactor.value ?: (preferencesRepository.getBlurDownscaleFactor() ) //
        val blurIterations = mainViewModel.blurIterations.value ?: preferencesRepository.getBlurIterations() //
        val p1ShadowRadius = mainViewModel.p1ShadowRadius.value ?: preferencesRepository.getP1ShadowRadius() //
        val p1ShadowDx = mainViewModel.p1ShadowDx.value ?: preferencesRepository.getP1ShadowDx() //
        val p1ShadowDy = mainViewModel.p1ShadowDy.value ?: preferencesRepository.getP1ShadowDy() //
        val p1ShadowColorFromRepo = preferencesRepository.getP1ShadowColor() // P1投影颜色直接从Repo获取
        val p1ImageBottomFadeHeight = mainViewModel.p1ImageBottomFadeHeight.value ?: preferencesRepository.getP1ImageBottomFadeHeight() //

        // 调用 WallpaperPreviewView 的 setConfigValues 方法更新其渲染参数
        wallpaperPreviewView.setConfigValues( //
            scrollSensitivity = scrollSensitivity,
            p1OverlayFadeRatio = p1OverlayFadeRatio,
            backgroundBlurRadius = backgroundBlurRadius,
            snapAnimationDurationMs = WallpaperConfigConstants.DEFAULT_PREVIEW_SNAP_DURATION_MS, // 使用常量中的默认动画时长
            normalizedInitialBgScrollOffset = backgroundInitialOffset,
            p2BackgroundFadeInRatio = p2BackgroundFadeInRatio,
            blurDownscaleFactor = blurDownscaleFactor,
            blurIterations = blurIterations,
            p1ShadowRadius = p1ShadowRadius,
            p1ShadowDx = p1ShadowDx,
            p1ShadowDy = p1ShadowDy,
            p1ShadowColor = p1ShadowColorFromRepo,
            p1ImageBottomFadeHeight = p1ImageBottomFadeHeight
        )
    }


    /**
     * 设置对 [MainViewModel] 中各项 LiveData 的观察。
     * 当 ViewModel 中的数据发生变化时，此方法中的观察者会接收到通知，
     * 并据此更新 UI 或 [WallpaperPreviewView] 的状态。
     */
    private fun observeViewModel() {
        // 观察选定图片 URI 的变化
        mainViewModel.selectedImageUri.observe(this) { uri -> //
            wallpaperPreviewView.setImageUri(uri, true) // 更新预览视图的图片，并强制重载
            // 如果 URI 为 null (图片被清除) 且当前在P1编辑模式，则退出编辑模式
            if (uri == null && (mainViewModel.isP1EditMode.value == true)) {
                mainViewModel.toggleP1EditMode() //
            }
        }

        // 观察选定背景颜色的变化
        mainViewModel.selectedBackgroundColor.observe(this) { color -> //
            wallpaperPreviewView.setSelectedBackgroundColor(color) // 更新预览视图的背景色
        }

        // 观察 P1 图片高度比例的变化
        mainViewModel.page1ImageHeightRatio.observe(this) { ratio -> //
            // 仅当不在P1编辑模式时，才通过此观察者更新预览视图的高度比例
            // (在编辑模式下，高度由手势直接在 PreviewView 内部处理，并通过回调更新 ViewModel)
            if (mainViewModel.isP1EditMode.value != true) {
                wallpaperPreviewView.setPage1ImageHeightRatio(ratio) //
            }
        }
        // 观察 P1 焦点 X 坐标的变化
        mainViewModel.p1FocusX.observe(this) { focusX -> //
            if (mainViewModel.isP1EditMode.value != true) {
                mainViewModel.p1FocusY.value?.let { focusY ->
                    wallpaperPreviewView.setNormalizedFocus(focusX, focusY) //
                }
            }
        }
        // 观察 P1 焦点 Y 坐标的变化
        mainViewModel.p1FocusY.observe(this) { focusY -> //
            if (mainViewModel.isP1EditMode.value != true) {
                mainViewModel.p1FocusX.value?.let { focusX ->
                    wallpaperPreviewView.setNormalizedFocus(focusX, focusY) //
                }
            }
        }
        // 观察 P1 内容缩放因子的变化
        mainViewModel.p1ContentScaleFactor.observe(this) { scale -> //
            if (mainViewModel.isP1EditMode.value != true) {
                wallpaperPreviewView.setP1ContentScaleFactor(scale) //
            }
        }

        // 观察所有高级渲染配置参数的变化，并在任一参数变化时同步到 PreviewView
        mainViewModel.scrollSensitivity.observe(this) { syncPreviewViewWithViewModelConfig() } //
        mainViewModel.p1OverlayFadeRatio.observe(this) { syncPreviewViewWithViewModelConfig() } //
        mainViewModel.p2BackgroundFadeInRatio.observe(this) { syncPreviewViewWithViewModelConfig() } //
        mainViewModel.backgroundInitialOffset.observe(this) { syncPreviewViewWithViewModelConfig() } //
        mainViewModel.backgroundBlurRadius.observe(this) { syncPreviewViewWithViewModelConfig() } //
        mainViewModel.blurDownscaleFactor.observe(this) { syncPreviewViewWithViewModelConfig() } //
        mainViewModel.blurIterations.observe(this) { syncPreviewViewWithViewModelConfig() } //
        mainViewModel.p1ShadowRadius.observe(this) { syncPreviewViewWithViewModelConfig() } //
        mainViewModel.p1ShadowDx.observe(this) { syncPreviewViewWithViewModelConfig() } //
        mainViewModel.p1ShadowDy.observe(this) { syncPreviewViewWithViewModelConfig() } //
        mainViewModel.p1ImageBottomFadeHeight.observe(this) { syncPreviewViewWithViewModelConfig() } //


        // 观察 Toast 消息事件
        mainViewModel.toastMessage.observe(this) { event -> //
            event.getContentIfNotHandled()?.let { msgContent -> //确保消息只被显示一次
                Log.d(TAG, "Toast message content: $msgContent")
                Toast.makeText(this, msgContent, Toast.LENGTH_LONG).show()
            }
        }

        // 观察 P1 编辑模式状态的变化
        mainViewModel.isP1EditMode.observe(this) { isEditing -> //
            Log.d(TAG, "isP1EditMode Observer: isEditing = $isEditing")
            val imageSelected = mainViewModel.selectedImageUri.value != null // 检查是否已选择图片
            if (isEditing) { // 如果要进入编辑模式
                if (imageSelected) { // 必须有图片才能进入编辑
                    wallpaperPreviewView.setP1FocusEditMode(true, //
                        mainViewModel.p1FocusX.value, mainViewModel.p1FocusY.value,
                        mainViewModel.page1ImageHeightRatio.value, mainViewModel.p1ContentScaleFactor.value
                    )
                } else { // 没有图片，无法进入编辑，则切回非编辑状态并提示
                    if (isEditing) mainViewModel.toggleP1EditMode() // 立即切回
                    Toast.makeText(this, getString(R.string.please_select_image_first_toast), Toast.LENGTH_SHORT).show() //
                }
            } else { // 如果要退出编辑模式
                // 将 ViewModel 中的非编辑模式参数同步到 PreviewView
                mainViewModel.page1ImageHeightRatio.value?.let { wallpaperPreviewView.setPage1ImageHeightRatio(it) } //
                mainViewModel.p1FocusX.value?.let { fx ->
                    mainViewModel.p1FocusY.value?.let { fy -> wallpaperPreviewView.setNormalizedFocus(fx, fy) } //
                }
                mainViewModel.p1ContentScaleFactor.value?.let { wallpaperPreviewView.setP1ContentScaleFactor(it) } //
                wallpaperPreviewView.setP1FocusEditMode(false) // 通知 PreviewView 退出编辑模式
            }
            // 无论进入还是退出编辑模式，都同步一次所有高级配置到预览视图
            syncPreviewViewWithViewModelConfig() //
            // 如果是进入编辑模式且有图片，确保预览视图重绘
            if (isEditing && imageSelected) {
                wallpaperPreviewView.invalidate() //
            }
        }
    }

    /**
     * 设置窗口边衬区 (Window Insets) 的处理。
     * 用于确保应用内容能够正确布局在系统栏（状态栏、导航栏）的后面，
     * 实现边到边 (edge-to-edge) 的沉浸式效果，同时避免内容被系统栏遮挡。
     * 当前实现中，只是设置了一个监听器，但没有具体调整 padding 的逻辑，
     * 沉浸式效果主要通过主题和 `WindowCompat.setDecorFitsSystemWindows(window, false)` 实现。
     */
    private fun setupWindowInsets() {
        val rootLayoutForInsets: View = findViewById(android.R.id.content) // 获取根视图
        ViewCompat.setOnApplyWindowInsetsListener(rootLayoutForInsets) { _, insets -> //
            // val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars()) // 获取系统栏的insets
            // 此处可以根据 systemBars.top, systemBars.bottom 等调整 View 的 padding
            // 但当前代码仅返回原始 insets，表示由系统自动处理或由 Compose 的 navigationBarsPadding() 等修饰符处理
            insets
        }
    }

    /**
     * Activity 恢复可见时调用。
     * 在此方法中，会再次同步 ViewModel 中的配置到 [WallpaperPreviewView]，
     * 并根据当前的 P1 编辑模式状态和图片选择状态，确保预览视图显示正确。
     */
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MainActivity onResume.")
        syncPreviewViewWithViewModelConfig() // 同步所有高级配置

        // 根据当前的P1编辑模式状态，正确设置PreviewView的模式和参数
        if (mainViewModel.isP1EditMode.value == true) { // 如果应处于P1编辑模式
            if (mainViewModel.selectedImageUri.value != null) { // 且有图片
                wallpaperPreviewView.setP1FocusEditMode(true, // 进入编辑模式并传入当前参数
                    mainViewModel.p1FocusX.value, mainViewModel.p1FocusY.value,
                    mainViewModel.page1ImageHeightRatio.value, mainViewModel.p1ContentScaleFactor.value
                )
            } else { // 无图则退出编辑模式
                mainViewModel.toggleP1EditMode() //
            }
        } else { // 如果不应处于P1编辑模式
            wallpaperPreviewView.setP1FocusEditMode(false) // 确保退出编辑模式
            // 将 ViewModel 中的P1相关参数（图片URI、高度、焦点、缩放）同步到PreviewView
            mainViewModel.selectedImageUri.value?.let { wallpaperPreviewView.setImageUri(it, false) } ?: wallpaperPreviewView.setImageUri(null, false) //
            mainViewModel.page1ImageHeightRatio.value?.let { wallpaperPreviewView.setPage1ImageHeightRatio(it) } //
            mainViewModel.p1FocusX.value?.let { fx ->
                mainViewModel.p1FocusY.value?.let { fy -> wallpaperPreviewView.setNormalizedFocus(fx, fy) } //
            }
            mainViewModel.p1ContentScaleFactor.value?.let { wallpaperPreviewView.setP1ContentScaleFactor(it) } //
        }
        // 同步背景色并重绘
        mainViewModel.selectedBackgroundColor.value?.let { wallpaperPreviewView.setSelectedBackgroundColor(it) } //
        wallpaperPreviewView.invalidate() // 触发重绘
    }

    /**
     * 检查并请求读取媒体图片的权限。
     * 根据 Android 版本，请求 [Manifest.permission.READ_MEDIA_IMAGES] (API 33+)
     * 或 [Manifest.permission.READ_EXTERNAL_STORAGE] (API 32及以下)。
     * 如果已有权限，则直接打开图库；否则，发起权限请求。
     */
    private fun checkAndRequestReadMediaImagesPermission() {
        // 根据Android版本确定要请求的权限
        val permissionToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES // API 33+
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE // API 32 及以下
        }
        // 检查是否已有权限
        if (ContextCompat.checkSelfPermission(this, permissionToRequest) != PackageManager.PERMISSION_GRANTED) {
            // 如果没有权限，则请求权限
            ActivityCompat.requestPermissions(this, arrayOf(permissionToRequest), PERMISSION_REQUEST_READ_MEDIA_IMAGES) //
        } else {
            // 如果已有权限，则直接打开图库
            openGallery() //
        }
    }

    /**
     * 处理权限请求的结果。
     * @param requestCode 请求权限时使用的请求码。
     * @param permissions 请求的权限数组。
     * @param grantResults 对应权限的授予结果数组。
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_READ_MEDIA_IMAGES) { //
            // 如果请求已授予
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openGallery() // 打开图库
            } else { // 如果权限被拒绝
                Toast.makeText(this, getString(R.string.permission_needed_toast), Toast.LENGTH_LONG).show() //
            }
        }
    }

    /**
     * 打开系统图库以供用户选择图片。
     * 使用 [ActivityResultLauncher] (`pickImageLauncher`) 来启动 Intent 并处理返回结果。
     */
    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI) //
        try {
            pickImageLauncher.launch(intent) // 启动图片选择器
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch gallery picker", e)
            Toast.makeText(this, getString(R.string.image_selection_failed_toast), Toast.LENGTH_SHORT).show() //
        }
    }

    /**
     * 处理用户按下返回键的逻辑。
     * 优先顺序：
     * 1. 如果底部配置面板正在显示，则关闭它。
     * 2. 如果当前处于 P1 编辑模式，则退出编辑模式。
     * 3. 否则，执行默认的返回操作 (通常是关闭 Activity)。
     */
    override fun onBackPressed() {
        if (mainViewModel.showConfigSheet.value) { // 如果配置面板显示
            mainViewModel.closeConfigSheet() // 关闭配置面板
        } else if (mainViewModel.isP1EditMode.value == true) { // 如果在P1编辑模式
            mainViewModel.toggleP1EditMode() // 退出P1编辑模式
            Toast.makeText(this, "已退出P1编辑", Toast.LENGTH_SHORT).show()
        } else { // 否则，执行默认返回操作
            super.onBackPressed()
        }
    }
}