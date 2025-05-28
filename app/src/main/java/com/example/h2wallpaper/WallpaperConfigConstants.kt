package com.example.h2wallpaper

import android.graphics.Color
import kotlin.math.roundToInt

object WallpaperConfigConstants {

    // SharedPreferences Name
    const val PREFS_NAME = "H2WallpaperPrefs"

    // SharedPreferences Keys
    const val KEY_IMAGE_URI = "internalImageUri"
    const val KEY_BACKGROUND_COLOR = "backgroundColor"
    const val KEY_IMAGE_HEIGHT_RATIO = "imageHeightRatio" // P1的高度比例
    const val KEY_P1_FOCUS_X = "p1FocusX"
    const val KEY_P1_FOCUS_Y = "p1FocusY"
    const val KEY_P1_CONTENT_SCALE_FACTOR = "p1ContentScaleFactor" // 新增：P1内容的缩放因子

    const val KEY_SCROLL_SENSITIVITY = "scrollSensitivity"
    const val KEY_P1_OVERLAY_FADE_RATIO = "p1OverlayFadeRatio"
    const val KEY_BACKGROUND_BLUR_RADIUS = "backgroundBlurRadius"
    const val KEY_BACKGROUND_INITIAL_OFFSET = "backgroundInitialOffset"
    const val KEY_P2_BACKGROUND_FADE_IN_RATIO = "p2BackgroundFadeInRatio"
    const val KEY_BLUR_DOWNSCALE_FACTOR = "blurDownscaleFactor"
    const val KEY_BLUR_ITERATIONS = "blurIterations"
    const val KEY_P1_SHADOW_RADIUS = "p1ShadowRadius"
    const val KEY_P1_SHADOW_DX = "p1ShadowDx"
    const val KEY_P1_SHADOW_DY = "p1ShadowDy"
    const val KEY_P1_SHADOW_COLOR = "p1ShadowColor"
    const val KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT = "p1ImageBottomFadeHeight"
    const val KEY_IMAGE_CONTENT_VERSION = "imageContentVersion"

    // Default Values
    val DEFAULT_BACKGROUND_COLOR: Int = Color.LTGRAY
    const val DEFAULT_HEIGHT_RATIO: Float = 1f / 3f
    const val DEFAULT_P1_FOCUS_X: Float = 0.5f
    const val DEFAULT_P1_FOCUS_Y: Float = 0.5f
    const val DEFAULT_P1_CONTENT_SCALE_FACTOR: Float = 1.0f // 新增：默认内容不缩放

    const val DEFAULT_SCROLL_SENSITIVITY: Float = 1.0f
    const val DEFAULT_P1_OVERLAY_FADE_RATIO: Float = 0.5f
    const val DEFAULT_BACKGROUND_BLUR_RADIUS: Float = 25f // 保持与XML中SeekBar一致或找到源头
    const val DEFAULT_BACKGROUND_INITIAL_OFFSET: Float = 0.2f
    const val DEFAULT_P2_BACKGROUND_FADE_IN_RATIO: Float = 0.8f
    const val DEFAULT_BLUR_DOWNSCALE_FACTOR_INT: Int = 10 // 代表百分比的100倍，即0.1
    const val DEFAULT_BLUR_ITERATIONS: Int = 1

    const val DEFAULT_P1_SHADOW_RADIUS: Float = 0f
    const val DEFAULT_P1_SHADOW_DX: Float = 0f
    const val DEFAULT_P1_SHADOW_DY: Float = 0f
    val DEFAULT_P1_SHADOW_COLOR: Int = Color.argb(180, 0, 0, 0) // 默认半透明黑色阴影
    const val DEFAULT_P1_IMAGE_BOTTOM_FADE_HEIGHT: Float = 0f
    const val DEFAULT_IMAGE_CONTENT_VERSION: Long = 0L

    // UI Related Defaults
    const val DEFAULT_PREVIEW_SNAP_DURATION_MS: Long = 700L
    const val HEIGHT_RATIO_STEP: Float = 0.02f
    const val MIN_HEIGHT_RATIO: Float = 0.15f
    const val MAX_HEIGHT_RATIO: Float = 0.75f

    // Scaled Integer Defaults for SeekBarPreferences in SettingsActivity
    val DEFAULT_SCROLL_SENSITIVITY_INT: Int = (DEFAULT_SCROLL_SENSITIVITY * 10).toInt()
    val DEFAULT_P1_OVERLAY_FADE_RATIO_INT: Int = (DEFAULT_P1_OVERLAY_FADE_RATIO * 100).toInt()
    val DEFAULT_BACKGROUND_BLUR_RADIUS_INT: Int = DEFAULT_BACKGROUND_BLUR_RADIUS.roundToInt() // 来自XML默认值25
    val DEFAULT_BACKGROUND_INITIAL_OFFSET_INT: Int = (DEFAULT_BACKGROUND_INITIAL_OFFSET * 10).toInt() // 来自XML默认值2
    val DEFAULT_P2_BACKGROUND_FADE_IN_RATIO_INT: Int = (DEFAULT_P2_BACKGROUND_FADE_IN_RATIO * 100).toInt() // 来自XML默认值80
    val DEFAULT_P1_SHADOW_RADIUS_INT: Int = DEFAULT_P1_SHADOW_RADIUS.roundToInt()
    val DEFAULT_P1_SHADOW_DX_INT: Int = DEFAULT_P1_SHADOW_DX.roundToInt()
    val DEFAULT_P1_SHADOW_DY_INT: Int = DEFAULT_P1_SHADOW_DY.roundToInt()
    val DEFAULT_P1_IMAGE_BOTTOM_FADE_HEIGHT_INT: Int = DEFAULT_P1_IMAGE_BOTTOM_FADE_HEIGHT.roundToInt()


    object FocusParams { // FocusActivity已移除，但常量键名可能仍有价值或需要清理
        const val EXTRA_IMAGE_URI = "com.example.h2wallpaper.EXTRA_IMAGE_URI"
        const val EXTRA_ASPECT_RATIO = "com.example.h2wallpaper.EXTRA_ASPECT_RATIO"
        const val EXTRA_INITIAL_FOCUS_X = "com.example.h2wallpaper.EXTRA_INITIAL_FOCUS_X"
        const val EXTRA_INITIAL_FOCUS_Y = "com.example.h2wallpaper.EXTRA_INITIAL_FOCUS_Y"
        const val RESULT_FOCUS_X = "com.example.h2wallpaper.RESULT_FOCUS_X"
        const val RESULT_FOCUS_Y = "com.example.h2wallpaper.RESULT_FOCUS_Y"
    }
}