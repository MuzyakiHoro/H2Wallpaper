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
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
// import androidx.core.view.updatePadding // Can be useful for insets
import androidx.palette.graphics.Palette
import androidx.viewpager2.widget.ViewPager2
import java.io.IOException

class MainActivity : AppCompatActivity() {

    // UI控件声明
    private lateinit var btnSelectImage: Button
    private lateinit var colorPaletteContainer: LinearLayout
    private lateinit var btnSetWallpaper: Button
    private lateinit var wallpaperPreviewPager: ViewPager2
    private lateinit var previewPagerAdapter: WallpaperPreviewPagerAdapter
    private lateinit var controlsContainer: LinearLayout
    private lateinit var heightControlsContainer: LinearLayout
    private lateinit var btnHeightReset: Button
    private lateinit var btnHeightIncrease: Button
    private lateinit var btnHeightDecrease: Button

    // 状态变量
    private var selectedImageUri: Uri? = null
    private var selectedBackgroundColor: Int = Color.LTGRAY
    private var originalBitmap: Bitmap? = null

    // 图片高度比例相关变量
    private var page1ImageHeightRatio: Float = 1f / 3f
    private val HEIGHT_RATIO_STEP = 0.05f
    private val MIN_HEIGHT_RATIO = 0.15f
    private val MAX_HEIGHT_RATIO = 0.60f
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
                    try {
                        val inputStream = contentResolver.openInputStream(uri)
                        // TODO: Consider adding sampling here for very large images to prevent OOM in MainActivity preview
                        originalBitmap = BitmapFactory.decodeStream(inputStream)
                        inputStream?.close()

                        originalBitmap?.let { bitmap ->
                            // When a new image is selected, you might want to reset the height ratio
                            // page1ImageHeightRatio = DEFAULT_HEIGHT_RATIO // If new image should reset height
                            extractColorsFromBitmap(bitmap) // This will update color and then adapter & prefs
                        } ?: run {
                            Toast.makeText(this, getString(R.string.image_load_failed_toast), Toast.LENGTH_SHORT).show()
                            if (::previewPagerAdapter.isInitialized) { // Check if adapter is initialized
                                previewPagerAdapter.updateData(null, selectedBackgroundColor, page1ImageHeightRatio)
                            }
                        }
                    } catch (e: IOException) {
                        Log.e(TAG, "Error loading image from URI: $uri", e)
                        Toast.makeText(this, getString(R.string.image_load_failed_toast), Toast.LENGTH_SHORT).show()
                        if (::previewPagerAdapter.isInitialized) {
                            previewPagerAdapter.updateData(null, selectedBackgroundColor, page1ImageHeightRatio)
                        }
                    }
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

        btnSelectImage = findViewById(R.id.btnSelectImage)
        colorPaletteContainer = findViewById(R.id.colorPaletteContainer)
        btnSetWallpaper = findViewById(R.id.btnSetWallpaper)
        wallpaperPreviewPager = findViewById(R.id.wallpaperPreviewPager)
        controlsContainer = findViewById(R.id.controlsContainer)
        heightControlsContainer = findViewById(R.id.heightControlsContainer)
        btnHeightReset = findViewById(R.id.btnHeightReset)
        btnHeightIncrease = findViewById(R.id.btnHeightIncrease)
        btnHeightDecrease = findViewById(R.id.btnHeightDecrease)

        loadPreferencesAndInitBitmapState() // 1. Load data into member variables

        // 2. Initialize Adapter with loaded or default data
        previewPagerAdapter = WallpaperPreviewPagerAdapter(this, originalBitmap, selectedBackgroundColor, page1ImageHeightRatio)
        wallpaperPreviewPager.adapter = previewPagerAdapter
        wallpaperPreviewPager.setPageTransformer(FadeAndScalePageTransformer()) // Apply animation

        // 3. If a bitmap was loaded from prefs, trigger color extraction which will update the adapter.
        //    If no bitmap, ensure adapter shows empty state.
        if (originalBitmap != null) {
            extractColorsFromBitmap(originalBitmap!!) // This calls adapter.updateData internally
        } else {
            previewPagerAdapter.updateData(null, selectedBackgroundColor, page1ImageHeightRatio)
            populateColorPaletteView(listOf(Color.GRAY, Color.DKGRAY, Color.LTGRAY)) // Show default color palette
        }


        val rootLayoutForInsets: View = findViewById(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayoutForInsets) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            (btnSetWallpaper.layoutParams as? ConstraintLayout.LayoutParams)?.apply {
                bottomMargin = systemBars.bottom + (16 * resources.displayMetrics.density).toInt()
                // btnSetWallpaper.layoutParams = this // Not needed if only margin changes
            }
            (heightControlsContainer.layoutParams as? ConstraintLayout.LayoutParams)?.apply {
                bottomMargin = systemBars.bottom + (8 * resources.displayMetrics.density).toInt()
                // heightControlsContainer.layoutParams = this
            }
            // Request layout if margins changed. Simpler to do it once if multiple views are adjusted.
            btnSetWallpaper.requestLayout()
            heightControlsContainer.requestLayout()
            // controlsContainer.requestLayout() // If its margins were also changed

            insets
        }


        btnSelectImage.setOnClickListener { checkAndRequestReadMediaImagesPermission() }
        btnSetWallpaper.setOnClickListener {
            if (selectedImageUri != null && originalBitmap != null) {
                savePreferences() // Save all current states
                promptToSetWallpaper()
            } else {
                Toast.makeText(this, getString(R.string.please_select_image_first_toast), Toast.LENGTH_SHORT).show()
            }
        }
        btnHeightReset.setOnClickListener { updatePage1ImageHeightRatio(DEFAULT_HEIGHT_RATIO) }
        btnHeightIncrease.setOnClickListener { updatePage1ImageHeightRatio((page1ImageHeightRatio + HEIGHT_RATIO_STEP)) }
        btnHeightDecrease.setOnClickListener { updatePage1ImageHeightRatio((page1ImageHeightRatio - HEIGHT_RATIO_STEP)) }

        var controlsAreVisible = true
        wallpaperPreviewPager.setOnClickListener {
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
        if (::previewPagerAdapter.isInitialized) {
            previewPagerAdapter.updatePage1ImageHeightRatio(page1ImageHeightRatio)
        }
        savePreferences() // Save all current states
    }

    private fun checkAndRequestReadMediaImagesPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_MEDIA_IMAGES), PERMISSION_REQUEST_READ_MEDIA_IMAGES)
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
                palette?.vibrantSwatch, palette?.mutedSwatch, palette?.dominantSwatch,
                palette?.lightVibrantSwatch, palette?.darkVibrantSwatch,
                palette?.lightMutedSwatch, palette?.darkMutedSwatch
            ).distinctBy { it.rgb }
            val colors = swatches.map { it.rgb }.take(8)

            val oldSelectedColor = selectedBackgroundColor
            if (colors.isNotEmpty()) {
                populateColorPaletteView(colors)
                if (selectedBackgroundColor == Color.LTGRAY || !colors.contains(selectedBackgroundColor)) {
                    selectedBackgroundColor = colors[0]
                }
            } else {
                populateColorPaletteView(listOf(Color.GRAY, Color.DKGRAY, Color.LTGRAY, Color.WHITE, Color.BLACK))
            }

            if (::previewPagerAdapter.isInitialized) {
                previewPagerAdapter.updateData(originalBitmap, selectedBackgroundColor, page1ImageHeightRatio)
            }
            // Save preferences if color changed OR if a new image was just loaded (even if color choice didn't change from default)
            if (oldSelectedColor != selectedBackgroundColor || (selectedImageUri != null && originalBitmap != null) ) {
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
                if (::previewPagerAdapter.isInitialized) {
                    previewPagerAdapter.updateData(originalBitmap, selectedBackgroundColor, page1ImageHeightRatio)
                }
                savePreferences()
            }
            colorPaletteContainer.addView(colorView)
        }
    }

    // Unified save function, saves all current relevant states
    private fun savePreferences() {
        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putInt(KEY_BACKGROUND_COLOR, selectedBackgroundColor)
        editor.putFloat(KEY_IMAGE_HEIGHT_RATIO, page1ImageHeightRatio)

        if (selectedImageUri != null && originalBitmap != null) {
            editor.putString(KEY_IMAGE_URI, selectedImageUri.toString())
        } else {
            editor.remove(KEY_IMAGE_URI) // If no valid image, remove URI from prefs
        }
        editor.apply()
        Log.d(TAG, "Preferences saved: URI=${selectedImageUri?.toString()}, Color=$selectedBackgroundColor, HeightRatio=$page1ImageHeightRatio")
    }


    private fun loadPreferencesAndInitBitmapState() {
        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val imageUriString = prefs.getString(KEY_IMAGE_URI, null)
        selectedBackgroundColor = prefs.getInt(KEY_BACKGROUND_COLOR, Color.LTGRAY)
        page1ImageHeightRatio = prefs.getFloat(KEY_IMAGE_HEIGHT_RATIO, DEFAULT_HEIGHT_RATIO)

        if (imageUriString != null) {
            selectedImageUri = Uri.parse(imageUriString)
            try {
                val inputStream = contentResolver.openInputStream(selectedImageUri!!)
                originalBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error loading saved image URI: $imageUriString", e)
                Toast.makeText(this, getString(R.string.loading_saved_image_failed_toast), Toast.LENGTH_SHORT).show()
                // Nullify bitmap and URI if loading fails, so adapter shows empty state
                originalBitmap = null
                selectedImageUri = null
                // No need to call clearImagePreferenceOnError here, as savePreferences will handle removing URI if bitmap is null
            }
        } else {
            originalBitmap = null
            selectedImageUri = null
        }
        Log.d(TAG, "Preferences loaded: URI=${selectedImageUri?.toString()}, Color=$selectedBackgroundColor, HeightRatio=$page1ImageHeightRatio, Bitmap loaded: ${originalBitmap != null}")
    }

    // This is less critical now as savePreferences handles removing URI if originalBitmap is null
    // private fun clearImagePreferenceOnError(resetHeightRatioToDefault: Boolean = false) { ... }
    // You can choose to keep it or remove it if savePreferences covers its main purpose.
    // For now, I'll comment it out to simplify, as its main role (clearing URI)
    // is implicitly handled by savePreferences when originalBitmap is null.

    private fun promptToSetWallpaper() {
        val wallpaperManager = WallpaperManager.getInstance(this)
        try {
            val componentName = ComponentName(packageName, H2WallpaperService::class.java.name)
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, componentName)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Toast.makeText(this, getString(R.string.wallpaper_set_prompt_toast), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error trying to set wallpaper", e)
            Toast.makeText(this, getString(R.string.wallpaper_set_failed_toast, e.message), Toast.LENGTH_LONG).show()
        }
    }
}