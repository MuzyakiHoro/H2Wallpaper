package com.example.h2wallpaper

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_BACKGROUND_INITIAL_OFFSET_INT
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_BLUR_DOWNSCALE_FACTOR_INT
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_BLUR_ITERATIONS
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_IMAGE_CONTENT_VERSION
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_P1_CONTENT_SCALE_FACTOR // 确保导入
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_P1_FOCUS_X
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_P1_IMAGE_BOTTOM_FADE_HEIGHT_INT
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_P1_OVERLAY_FADE_RATIO_INT
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_P1_SHADOW_COLOR
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_P1_SHADOW_DX_INT
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_P1_SHADOW_DY_INT
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_P1_SHADOW_RADIUS_INT
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_P2_BACKGROUND_FADE_IN_RATIO_INT
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_SCROLL_SENSITIVITY_INT
import com.example.h2wallpaper.WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS
import com.example.h2wallpaper.WallpaperConfigConstants.KEY_BACKGROUND_COLOR
import com.example.h2wallpaper.WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET
import com.example.h2wallpaper.WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR
import com.example.h2wallpaper.WallpaperConfigConstants.KEY_BLUR_ITERATIONS
import com.example.h2wallpaper.WallpaperConfigConstants.KEY_IMAGE_CONTENT_VERSION
import com.example.h2wallpaper.WallpaperConfigConstants.KEY_IMAGE_HEIGHT_RATIO
import com.example.h2wallpaper.WallpaperConfigConstants.KEY_IMAGE_URI
import com.example.h2wallpaper.WallpaperConfigConstants.KEY_P1_CONTENT_SCALE_FACTOR // 确保导入
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

class WallpaperPreferencesRepository(context: Context) {

    internal  val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- Getters ---
    fun getSelectedImageUri(): Uri? = prefs.getString(KEY_IMAGE_URI, null)?.let { Uri.parse(it) }
    fun getSelectedBackgroundColor(): Int =
        prefs.getInt(KEY_BACKGROUND_COLOR, DEFAULT_BACKGROUND_COLOR)

    fun getPage1ImageHeightRatio(): Float =
        prefs.getFloat(KEY_IMAGE_HEIGHT_RATIO, DEFAULT_HEIGHT_RATIO)

    fun getP1FocusX(): Float = prefs.getFloat(KEY_P1_FOCUS_X, DEFAULT_P1_FOCUS_X)
    fun getP1FocusY(): Float = prefs.getFloat(KEY_P1_FOCUS_Y, DEFAULT_P1_FOCUS_Y)

    fun getP1ContentScaleFactor(): Float = // 新增 Getter
        prefs.getFloat(KEY_P1_CONTENT_SCALE_FACTOR, DEFAULT_P1_CONTENT_SCALE_FACTOR)

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
    }

    fun setSelectedBackgroundColor(color: Int) {
        prefs.edit().putInt(KEY_BACKGROUND_COLOR, color).apply()
    }

    fun setPage1ImageHeightRatio(ratio: Float) {
        prefs.edit().putFloat(KEY_IMAGE_HEIGHT_RATIO, ratio).apply()
    }

    fun setP1FocusX(focusX: Float) {
        prefs.edit().putFloat(KEY_P1_FOCUS_X, focusX).apply()
    }

    fun setP1FocusY(focusY: Float) {
        prefs.edit().putFloat(KEY_P1_FOCUS_Y, focusY).apply()
    }

    fun setP1Focus(focusX: Float, focusY: Float) {
        prefs.edit()
            .putFloat(KEY_P1_FOCUS_X, focusX)
            .putFloat(KEY_P1_FOCUS_Y, focusY)
            .apply()
        // 版本更新由ViewModel在确认所有相关P1参数设置后统一处理
    }

    fun setP1ContentScaleFactor(scaleFactor: Float) { // 新增 Setter
        prefs.edit().putFloat(KEY_P1_CONTENT_SCALE_FACTOR, scaleFactor).apply()
    }

    fun updateImageContentVersion() {
        prefs.edit().putLong(KEY_IMAGE_CONTENT_VERSION, System.currentTimeMillis()).apply()
    }

    /**
     * 新增: 通用方法，用于保存整数类型的设置项
     */
    fun saveIntSetting(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    /**
     * 新增: 如果有直接保存Float的需求（虽然当前ViewModel都转成了Int）
     */
    fun saveFloatSetting(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
    }

    fun resetSettingsForNewImage(newImageUri: Uri) {
        prefs.edit()
            .putString(KEY_IMAGE_URI, newImageUri.toString())
            .putFloat(KEY_P1_FOCUS_X, DEFAULT_P1_FOCUS_X)
            .putFloat(KEY_P1_FOCUS_Y, DEFAULT_P1_FOCUS_Y)
            .putFloat(KEY_P1_CONTENT_SCALE_FACTOR, DEFAULT_P1_CONTENT_SCALE_FACTOR) // 重置内容缩放
            // P1 高度通常不随新图片重置，除非产品设计如此
            // .putFloat(KEY_IMAGE_HEIGHT_RATIO, DEFAULT_HEIGHT_RATIO)
            .putLong(KEY_IMAGE_CONTENT_VERSION, System.currentTimeMillis()) // 新图片意味着新版本
            .apply()
    }

    fun removeImageUri() {
        prefs.edit().remove(KEY_IMAGE_URI).apply()
        // ViewModel应在调用此方法后，重置相关配置并更新版本号
    }
}