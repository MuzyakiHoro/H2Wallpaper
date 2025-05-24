package com.example.h2wallpaper

import android.Manifest
import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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

// 导入 WallpaperConfigConstants 对象本身和 FocusParams 嵌套对象
import com.example.h2wallpaper.WallpaperConfigConstants
import com.example.h2wallpaper.WallpaperConfigConstants.FocusParams

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
    private lateinit var imageLoadingProgressBar: ProgressBar
    private lateinit var btnAdvancedSettings: Button

    // 获取 ViewModel 实例
    private val mainViewModel: MainViewModel by viewModels()

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                mainViewModel.handleImageSelectionResult(result.data?.data)
            } else {
                Log.d(TAG, "Image selection cancelled or failed, resultCode: ${result.resultCode}")
            }
        }

    private val focusActivityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                val focusX = data?.getFloatExtra(
                    FocusParams.RESULT_FOCUS_X,
                    WallpaperConfigConstants.DEFAULT_P1_FOCUS_X
                ) ?: WallpaperConfigConstants.DEFAULT_P1_FOCUS_X
                val focusY = data?.getFloatExtra(
                    FocusParams.RESULT_FOCUS_Y,
                    WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y
                ) ?: WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y
                mainViewModel.updateP1Focus(focusX, focusY)

                Toast.makeText(
                    this,
                    "焦点已更新: X=${"%.2f".format(focusX)}, Y=${"%.2f".format(focusY)}",
                    Toast.LENGTH_LONG
                ).show()

            } else if (result.resultCode == Activity.RESULT_CANCELED) {
                Toast.makeText(this, "自定义焦点已取消", Toast.LENGTH_SHORT).show()
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

        setupButtonClickListeners()
        observeViewModel()
        setupWindowInsets()

        Log.d(TAG, "onCreate: Activity setup complete, ViewModel observers set.")
    }

    private fun setupButtonClickListeners() {
        btnSelectImage.setOnClickListener { checkAndRequestReadMediaImagesPermission() }

        btnSetWallpaper.setOnClickListener {
            if (mainViewModel.selectedImageUri.value != null) {
                mainViewModel.saveNonBitmapConfigAndUpdateVersion()
                promptToSetWallpaper()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.please_select_image_first_toast),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        btnAdvancedSettings.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        btnHeightReset.setOnClickListener {
            mainViewModel.updatePage1ImageHeightRatio(
                WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO
            )
        }
        btnHeightIncrease.setOnClickListener {
            val currentRatio = mainViewModel.page1ImageHeightRatio.value
                ?: WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO
            mainViewModel.updatePage1ImageHeightRatio(currentRatio + WallpaperConfigConstants.HEIGHT_RATIO_STEP)
        }
        btnHeightDecrease.setOnClickListener {
            val currentRatio = mainViewModel.page1ImageHeightRatio.value
                ?: WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO
            mainViewModel.updatePage1ImageHeightRatio(currentRatio - WallpaperConfigConstants.HEIGHT_RATIO_STEP)
        }

        btnCustomizeForeground.setOnClickListener {
            Log.d(TAG, "btnCustomizeForeground clicked")
            mainViewModel.selectedImageUri.value?.let { uri ->
                Log.d(TAG, "selectedImageUri is: $uri")
                val intent = Intent(this, FocusActivity::class.java).apply {
                    putExtra(FocusParams.EXTRA_IMAGE_URI, uri)

                    val previewWidth = wallpaperPreviewView.width.takeIf { it > 0 }
                        ?: resources.displayMetrics.widthPixels
                    val previewHeight = wallpaperPreviewView.height.takeIf { it > 0 }
                        ?: resources.displayMetrics.heightPixels
                    val currentP1Height = previewHeight * (mainViewModel.page1ImageHeightRatio.value
                        ?: WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO)
                    val currentP1AspectRatio = if (previewWidth > 0 && currentP1Height > 0) {
                        previewWidth.toFloat() / currentP1Height.toFloat()
                    } else {
                        16f / 9f // Default aspect ratio if dimensions are not ready
                    }
                    putExtra(FocusParams.EXTRA_ASPECT_RATIO, currentP1AspectRatio)
                    putExtra(
                        FocusParams.EXTRA_INITIAL_FOCUS_X,
                        mainViewModel.p1FocusX.value ?: WallpaperConfigConstants.DEFAULT_P1_FOCUS_X
                    )
                    putExtra(
                        FocusParams.EXTRA_INITIAL_FOCUS_Y,
                        mainViewModel.p1FocusY.value ?: WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y
                    )
                }
                focusActivityLauncher.launch(intent)
            } ?: run {
                Log.w(TAG, "selectedImageUri is NULL, cannot start FocusActivity")
                Toast.makeText(this, "请先选择一张图片", Toast.LENGTH_SHORT).show()
            }
        }

        var controlsAreVisible = true
        wallpaperPreviewView.setOnClickListener {
            controlsAreVisible = !controlsAreVisible
            val targetAlpha = if (controlsAreVisible) 1f else 0f
            val targetVisibility = if (controlsAreVisible) View.VISIBLE else View.GONE

            animateViewVisibility(controlsContainer, targetAlpha, targetVisibility)
            animateViewVisibility(heightControlsContainer, targetAlpha, targetVisibility)
            animateViewVisibility(btnSetWallpaper, targetAlpha, targetVisibility)
            animateViewVisibility(btnSelectImage, targetAlpha, targetVisibility)
            btnAdvancedSettings?.let { animateViewVisibility(it, targetAlpha, targetVisibility) }

            if (mainViewModel.selectedImageUri.value != null) {
                animateViewVisibility(btnCustomizeForeground, targetAlpha, targetVisibility)
            } else {
                btnCustomizeForeground.visibility = View.GONE
                btnCustomizeForeground.alpha = 0f
            }
        }
    }

    private fun observeViewModel() {
        mainViewModel.selectedImageUri.observe(this, Observer { uri ->
            wallpaperPreviewView.setImageUri(uri, true)
            btnCustomizeForeground.isEnabled = (uri != null)
            if (uri == null) {
                // Ensure palette is updated even if URI becomes null (e.g. image access failed)
                populateColorPaletteView(mainViewModel.colorPalette.value ?: emptyList())
            }
        })

        mainViewModel.selectedBackgroundColor.observe(this, Observer { color ->
            wallpaperPreviewView.setSelectedBackgroundColor(color)
        })

        mainViewModel.page1ImageHeightRatio.observe(this, Observer { ratio ->
            wallpaperPreviewView.setPage1ImageHeightRatio(ratio)
        })

        mainViewModel.p1FocusX.observe(this, Observer { focusX ->
            mainViewModel.p1FocusY.value?.let { focusY ->
                wallpaperPreviewView.setNormalizedFocus(focusX, focusY)
            }
        })
        mainViewModel.p1FocusY.observe(this, Observer { focusY ->
            mainViewModel.p1FocusX.value?.let { focusX ->
                wallpaperPreviewView.setNormalizedFocus(focusX, focusY)
            }
        })

        mainViewModel.isLoading.observe(this, Observer { isLoading ->
            imageLoadingProgressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
            btnSelectImage.isEnabled = !isLoading
        })

        mainViewModel.toastMessage.observe(this, Observer { event ->
            event.getContentIfNotHandled()?.let { message ->
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        })

        mainViewModel.colorPalette.observe(this, Observer { colors ->
            populateColorPaletteView(colors)
        })
    }

    private fun setupWindowInsets() {
        val rootLayoutForInsets: View = findViewById(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayoutForInsets) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (btnSetWallpaper.layoutParams as? ConstraintLayout.LayoutParams)?.apply {
                bottomMargin = systemBars.bottom + (16 * resources.displayMetrics.density).toInt()
            }
            btnSetWallpaper.requestLayout()
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MainActivity onResume: Reloading preferences for PreviewView.")
        // ViewModel handles its own state restoration and SharedPreferences loading in init.
        // However, settings modified by SettingsActivity are directly in SharedPreferences
        // and WallpaperPreviewView needs to be updated with these.
        val prefs = getSharedPreferences(WallpaperConfigConstants.PREFS_NAME, Context.MODE_PRIVATE)

        val scrollSensitivity = prefs.getInt(
            WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY,
            WallpaperConfigConstants.DEFAULT_SCROLL_SENSITIVITY_INT
        ) / 10.0f
        val p1OverlayFadeRatio = prefs.getInt(
            WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO,
            WallpaperConfigConstants.DEFAULT_P1_OVERLAY_FADE_RATIO_INT
        ) / 100.0f
        val backgroundBlurRadius = prefs.getInt(
            WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS,
            WallpaperConfigConstants.DEFAULT_BACKGROUND_BLUR_RADIUS_INT
        ).toFloat()
        val backgroundInitialOffset = prefs.getInt(
            WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET,
            WallpaperConfigConstants.DEFAULT_BACKGROUND_INITIAL_OFFSET_INT
        ) / 10.0f
        val p2BackgroundFadeInRatio = prefs.getInt(
            WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO,
            WallpaperConfigConstants.DEFAULT_P2_BACKGROUND_FADE_IN_RATIO_INT
        ) / 100.0f
        val blurDownscaleFactorInt = prefs.getInt(
            WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR,
            WallpaperConfigConstants.DEFAULT_BLUR_DOWNSCALE_FACTOR_INT
        )
        val blurIterations = prefs.getInt(
            WallpaperConfigConstants.KEY_BLUR_ITERATIONS,
            WallpaperConfigConstants.DEFAULT_BLUR_ITERATIONS
        )
        val p1ShadowRadius = prefs.getInt(
            WallpaperConfigConstants.KEY_P1_SHADOW_RADIUS,
            WallpaperConfigConstants.DEFAULT_P1_SHADOW_RADIUS_INT
        ).toFloat()
        val p1ShadowDx = prefs.getInt(
            WallpaperConfigConstants.KEY_P1_SHADOW_DX,
            WallpaperConfigConstants.DEFAULT_P1_SHADOW_DX_INT
        ).toFloat()
        val p1ShadowDy = prefs.getInt(
            WallpaperConfigConstants.KEY_P1_SHADOW_DY,
            WallpaperConfigConstants.DEFAULT_P1_SHADOW_DY_INT
        ).toFloat()
        val p1ShadowColor = prefs.getInt(
            WallpaperConfigConstants.KEY_P1_SHADOW_COLOR,
            WallpaperConfigConstants.DEFAULT_P1_SHADOW_COLOR
        )
        val p1ImageBottomFadeHeight = prefs.getInt(
            WallpaperConfigConstants.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT,
            WallpaperConfigConstants.DEFAULT_P1_IMAGE_BOTTOM_FADE_HEIGHT_INT
        ).toFloat()

        wallpaperPreviewView.setConfigValues(
            scrollSensitivity = scrollSensitivity,
            p1OverlayFadeRatio = p1OverlayFadeRatio,
            backgroundBlurRadius = backgroundBlurRadius,
            snapAnimationDurationMs = WallpaperConfigConstants.DEFAULT_PREVIEW_SNAP_DURATION_MS,
            normalizedInitialBgScrollOffset = backgroundInitialOffset,
            p2BackgroundFadeInRatio = p2BackgroundFadeInRatio,
            blurDownscaleFactor = blurDownscaleFactorInt / 100.0f,
            blurIterations = blurIterations,
            p1ShadowRadius = p1ShadowRadius,
            p1ShadowDx = p1ShadowDx,
            p1ShadowDy = p1ShadowDy,
            p1ShadowColor = p1ShadowColor,
            p1ImageBottomFadeHeight = p1ImageBottomFadeHeight
        )

        // Ensure other ViewModel-driven states are also applied to the preview,
        // though LiveData observers should handle this, explicitly setting here
        // onResume can ensure immediate consistency if there was any complex lifecycle interaction.
        mainViewModel.selectedImageUri.value?.let { wallpaperPreviewView.setImageUri(it) }
            ?: wallpaperPreviewView.setImageUri(null)
        mainViewModel.page1ImageHeightRatio.value?.let {
            wallpaperPreviewView.setPage1ImageHeightRatio(
                it
            )
        }
        mainViewModel.selectedBackgroundColor.value?.let {
            wallpaperPreviewView.setSelectedBackgroundColor(
                it
            )
        }
        mainViewModel.p1FocusX.value?.let { fx ->
            mainViewModel.p1FocusY.value?.let { fy ->
                wallpaperPreviewView.setNormalizedFocus(fx, fy)
            }
        }
    }

    private fun checkAndRequestReadMediaImagesPermission() {
        val permissionToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(
                this,
                permissionToRequest
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(permissionToRequest),
                PERMISSION_REQUEST_READ_MEDIA_IMAGES
            )
        } else {
            openGallery()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_READ_MEDIA_IMAGES) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openGallery()
            } else {
                Toast.makeText(this, getString(R.string.permission_needed_toast), Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        try {
            pickImageLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch gallery picker", e)
            Toast.makeText(
                this,
                getString(R.string.image_selection_failed_toast),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun populateColorPaletteView(colors: List<Int>) {
        colorPaletteContainer.removeAllViews()
        if (colors.isEmpty()) {
            // Optionally, display a message or a default single color if colors list is empty
            // For now, just clearing is fine.
            return
        }

        val colorViewSize = resources.getDimensionPixelSize(R.dimen.palette_color_view_size)
        val margin = resources.getDimensionPixelSize(R.dimen.palette_color_view_margin)

        for (color in colors) {
            val colorView = View(this)
            val params = LinearLayout.LayoutParams(colorViewSize, colorViewSize)
            params.setMargins(margin, margin, margin, margin)
            colorView.layoutParams = params
            colorView.setBackgroundColor(color)
            colorView.setOnClickListener {
                mainViewModel.updateSelectedBackgroundColor(color)
            }
            colorPaletteContainer.addView(colorView)
        }
    }

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

    private fun promptToSetWallpaper() {
        try {
            val componentName = ComponentName(packageName, H2WallpaperService::class.java.name)
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, componentName)
            startActivity(intent)
            Toast.makeText(this, getString(R.string.wallpaper_set_prompt_toast), Toast.LENGTH_LONG)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Error trying to set wallpaper", e)
            Toast.makeText(
                this,
                getString(R.string.wallpaper_set_failed_toast, e.message ?: "Unknown error"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // onDestroy and onStop are standard lifecycle methods, ViewModel handles its own lifecycle.
}