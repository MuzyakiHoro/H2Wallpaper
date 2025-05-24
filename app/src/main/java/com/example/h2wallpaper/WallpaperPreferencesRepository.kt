package com.example.h2wallpaper

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_BACKGROUND_INITIAL_OFFSET
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_BACKGROUND_INITIAL_OFFSET_INT
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_BLUR_DOWNSCALE_FACTOR_INT
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_BLUR_ITERATIONS
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_IMAGE_CONTENT_VERSION
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_P1_FOCUS_X
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_P1_IMAGE_BOTTOM_FADE_HEIGHT
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_P1_IMAGE_BOTTOM_FADE_HEIGHT_INT
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_P1_OVERLAY_FADE_RATIO
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_P1_OVERLAY_FADE_RATIO_INT
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_P1_SHADOW_COLOR
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_P1_SHADOW_DX
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_P1_SHADOW_DX_INT
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_P1_SHADOW_DY
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_P1_SHADOW_DY_INT
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_P1_SHADOW_RADIUS
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_P1_SHADOW_RADIUS_INT
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_P2_BACKGROUND_FADE_IN_RATIO
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_P2_BACKGROUND_FADE_IN_RATIO_INT
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_SCROLL_SENSITIVITY
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_SCROLL_SENSITIVITY_INT
import com.example.h2wallpaper.WallpaperConfigConstants.KEY_BACKGROUND_COLOR
import com.example.h2wallpaper.WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET
import com.example.h2wallpaper.WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS
import com.example.h2wallpaper.WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR
import com.example.h2wallpaper.WallpaperConfigConstants.KEY_BLUR_ITERATIONS
import com.example.h2wallpaper.WallpaperConfigConstants.KEY_IMAGE_CONTENT_VERSION
import com.example.h2wallpaper.WallpaperConfigConstants.KEY_IMAGE_HEIGHT_RATIO
import com.example.h2wallpaper.WallpaperConfigConstants.KEY_IMAGE_URI
import com.example.h2wallpaper.WallpaperConfigConstants.KEY_P1_FOCUS_X
import com.example.h2wallpaper.WallpaperConfigConstants.KEY_P1_FOCUS_Y
import com.example.h2wallpaper.WallpaperConfigConstants.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT
import com.example.h2wallpaper.WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO
import com.example.h2wallpaper.WallpaperConfigConstants.KEY_P1_SHADOW_COLOR
import com.example.h2wallpaper.WallpaperConfigConstants.KEY_P1_SHADOW_DX
import com.example.h2wallpaper.WallpaperConfigConstants.KEY_P1_SHADOW_DY
import com.example.h2wallpaper.WallpaperConfigConstants.KEY_P1_SHADOW_RADIUS
import com.example.h2wallpaper.WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO
import com.example.h2wallpaper.WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY
import com.example.h2wallpaper.WallpaperConfigConstants.PREFS_NAME
import kotlin.math.roundToInt

class WallpaperPreferencesRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- Getters ---
    fun getSelectedImageUri(): Uri? = prefs.getString(KEY_IMAGE_URI, null)?.let { Uri.parse(it) }
    fun getSelectedBackgroundColor(): Int =
        prefs.getInt(KEY_BACKGROUND_COLOR, DEFAULT_BACKGROUND_COLOR)

    fun getPage1ImageHeightRatio(): Float =
        prefs.getFloat(KEY_IMAGE_HEIGHT_RATIO, DEFAULT_HEIGHT_RATIO)

    fun getP1FocusX(): Float = prefs.getFloat(KEY_P1_FOCUS_X, DEFAULT_P1_FOCUS_X)
    fun getP1FocusY(): Float = prefs.getFloat(KEY_P1_FOCUS_Y, DEFAULT_P1_FOCUS_Y)
    fun getScrollSensitivity(): Float =
        prefs.getInt(KEY_SCROLL_SENSITIVITY, DEFAULT_SCROLL_SENSITIVITY_INT) / 10.0f

    fun getP1OverlayFadeRatio(): Float =
        prefs.getInt(KEY_P1_OVERLAY_FADE_RATIO, DEFAULT_P1_OVERLAY_FADE_RATIO_INT) / 100.0f

    fun getBackgroundBlurRadius(): Float = prefs.getInt(
        KEY_BACKGROUND_BLUR_RADIUS,
        WallpaperConfigConstants.DEFAULT_BACKGROUND_BLUR_RADIUS_INT
    ).toFloat()

    fun getBackgroundInitialOffset(): Float =
        prefs.getInt(KEY_BACKGROUND_INITIAL_OFFSET, DEFAULT_BACKGROUND_INITIAL_OFFSET_INT) / 10.0f

    fun getP2BackgroundFadeInRatio(): Float = prefs.getInt(
        KEY_P2_BACKGROUND_FADE_IN_RATIO,
        DEFAULT_P2_BACKGROUND_FADE_IN_RATIO_INT
    ) / 100.0f

    fun getBlurDownscaleFactorInt(): Int =
        prefs.getInt(KEY_BLUR_DOWNSCALE_FACTOR, DEFAULT_BLUR_DOWNSCALE_FACTOR_INT)

    fun getBlurIterations(): Int = prefs.getInt(KEY_BLUR_ITERATIONS, DEFAULT_BLUR_ITERATIONS)
    fun getP1ShadowRadius(): Float =
        prefs.getInt(KEY_P1_SHADOW_RADIUS, DEFAULT_P1_SHADOW_RADIUS_INT).toFloat()

    fun getP1ShadowDx(): Float = prefs.getInt(KEY_P1_SHADOW_DX, DEFAULT_P1_SHADOW_DX_INT).toFloat()
    fun getP1ShadowDy(): Float = prefs.getInt(KEY_P1_SHADOW_DY, DEFAULT_P1_SHADOW_DY_INT).toFloat()
    fun getP1ShadowColor(): Int = prefs.getInt(KEY_P1_SHADOW_COLOR, DEFAULT_P1_SHADOW_COLOR)
    fun getP1ImageBottomFadeHeight(): Float =
        prefs.getInt(KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT, DEFAULT_P1_IMAGE_BOTTOM_FADE_HEIGHT_INT)
            .toFloat()

    fun getImageContentVersion(): Long =
        prefs.getLong(KEY_IMAGE_CONTENT_VERSION, DEFAULT_IMAGE_CONTENT_VERSION)

    // --- Setters ---
    fun setSelectedImageUri(uri: Uri?) {
        val editor = prefs.edit()
        if (uri != null) {
            editor.putString(KEY_IMAGE_URI, uri.toString())
        } else {
            editor.remove(KEY_IMAGE_URI)
        }
        editor.apply()
        // Note: Changing image URI usually implies other related settings might reset (like focus, version)
        // This logic is currently in MainViewModel. Consider if some part should move here.
    }

    fun setSelectedBackgroundColor(color: Int) {
        prefs.edit().putInt(KEY_BACKGROUND_COLOR, color).apply()
        updateImageContentVersion() // Color change affects visuals
    }

    fun setPage1ImageHeightRatio(ratio: Float) {
        prefs.edit().putFloat(KEY_IMAGE_HEIGHT_RATIO, ratio).apply()
        updateImageContentVersion() // Height ratio change affects visuals
    }

    fun setP1Focus(focusX: Float, focusY: Float) {
        prefs.edit()
            .putFloat(KEY_P1_FOCUS_X, focusX)
            .putFloat(KEY_P1_FOCUS_Y, focusY)
            .apply()
        updateImageContentVersion() // Focus change affects P1 crop
    }

    // For settings typically changed by SettingsActivity (SeekBarPreferences which store Ints)
    // These are not directly set by MainViewModel in the current structure,
    // but if they were, methods like these would be useful.
    // SettingsActivity directly modifies SharedPreferences, and H2WallpaperService listens.
    // MainViewModel refreshes these for WallpaperPreviewView in onResume.
    // So, setters for these might not be immediately needed in the Repository for MainViewModel's use.
    // However, if we wanted MainViewModel to also be able to change these:
    /*
    fun setScrollSensitivity(value: Float) {
        prefs.edit().putInt(KEY_SCROLL_SENSITIVITY, (value * 10).toInt()).apply()
        updateImageContentVersion()
    }
    // ... and so on for other SeekBarPreference-backed values
    */

    // Method to update the image content version timestamp
    fun updateImageContentVersion() {
        prefs.edit().putLong(KEY_IMAGE_CONTENT_VERSION, System.currentTimeMillis()).apply()
    }

    // Method to reset specific fields when a new image is selected
    fun resetSettingsForNewImage(newImageUri: Uri) {
        prefs.edit()
            .putString(KEY_IMAGE_URI, newImageUri.toString())
            .putFloat(KEY_P1_FOCUS_X, DEFAULT_P1_FOCUS_X)
            .putFloat(KEY_P1_FOCUS_Y, DEFAULT_P1_FOCUS_Y)
            // Optionally reset other image-dependent settings here if desired
            .putLong(KEY_IMAGE_CONTENT_VERSION, System.currentTimeMillis())
            .apply()
    }

    fun removeImageUri() {
        prefs.edit().remove(KEY_IMAGE_URI).apply()
        // Consider if other related fields should be reset or if version needs update
    }
}