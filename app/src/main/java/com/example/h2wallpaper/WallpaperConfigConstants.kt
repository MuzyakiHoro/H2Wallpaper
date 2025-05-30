package com.example.h2wallpaper

import android.graphics.Color
import kotlin.math.roundToInt

/**
 * H2Wallpaper 应用的配置常量对象。
 * 包含 SharedPreferences 的名称、各个配置项的键名，以及这些配置项的默认值。
 * 同时也定义了一些 UI 相关的常量，如动画时长、参数调整步长等。
 */
object WallpaperConfigConstants {

    // SharedPreferences Name
    /** SharedPreferences 存储的名称 */
    const val PREFS_NAME = "H2WallpaperPrefs"

    // SharedPreferences Keys
    /** 存储内部图片 URI 的键名 */
    const val KEY_IMAGE_URI = "internalImageUri"
    /** 存储背景颜色的键名 */
    const val KEY_BACKGROUND_COLOR = "backgroundColor"
    /** 存储 P1 图片高度比例的键名 */
    const val KEY_IMAGE_HEIGHT_RATIO = "imageHeightRatio" // P1的高度比例
    /** 存储 P1 图片归一化焦点 X 坐标的键名 */
    const val KEY_P1_FOCUS_X = "p1FocusX"
    /** 存储 P1 图片归一化焦点 Y 坐标的键名 */
    const val KEY_P1_FOCUS_Y = "p1FocusY"
    /** 存储 P1 图片内容缩放因子的键名 */
    const val KEY_P1_CONTENT_SCALE_FACTOR = "p1ContentScaleFactor"

    /** 存储滚动灵敏度的键名 */
    const val KEY_SCROLL_SENSITIVITY = "scrollSensitivity"
    /** 存储 P1 遮罩层淡出过渡比例的键名 */
    const val KEY_P1_OVERLAY_FADE_RATIO = "p1OverlayFadeRatio"
    /** 存储背景模糊半径的键名 */
    const val KEY_BACKGROUND_BLUR_RADIUS = "backgroundBlurRadius"
    /** 存储背景初始滚动偏移量的键名 */
    const val KEY_BACKGROUND_INITIAL_OFFSET = "backgroundInitialOffset"
    /** 存储 P2 背景淡入过渡比例的键名 */
    const val KEY_P2_BACKGROUND_FADE_IN_RATIO = "p2BackgroundFadeInRatio"
    /** 存储模糊处理时降采样因子的键名 */
    const val KEY_BLUR_DOWNSCALE_FACTOR = "blurDownscaleFactor"
    /** 存储模糊处理迭代次数的键名 */
    const val KEY_BLUR_ITERATIONS = "blurIterations"
    /** 存储 P1 图片投影效果半径的键名 */
    const val KEY_P1_SHADOW_RADIUS = "p1ShadowRadius"
    /** 存储 P1 图片投影效果 X 轴偏移量的键名 */
    const val KEY_P1_SHADOW_DX = "p1ShadowDx"
    /** 存储 P1 图片投影效果 Y 轴偏移量的键名 */
    const val KEY_P1_SHADOW_DY = "p1ShadowDy"
    /** 存储 P1 图片投影效果颜色的键名 */
    const val KEY_P1_SHADOW_COLOR = "p1ShadowColor"
    /** 存储 P1 图片底部融入效果高度的键名 */
    const val KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT = "p1ImageBottomFadeHeight"
    /** 存储图片内容版本号的键名，用于检测配置是否有实质性变化 */
    const val KEY_IMAGE_CONTENT_VERSION = "imageContentVersion"

    // Default Values
    /** 默认背景颜色 (浅灰色) */
    val DEFAULT_BACKGROUND_COLOR: Int = Color.LTGRAY
    /** 默认 P1 图片高度比例 (屏幕高度的 1/3) */
    const val DEFAULT_HEIGHT_RATIO: Float = 1f / 3f
    /** 默认 P1 图片归一化焦点 X 坐标 (中心) */
    const val DEFAULT_P1_FOCUS_X: Float = 0.5f
    /** 默认 P1 图片归一化焦点 Y 坐标 (中心) */
    const val DEFAULT_P1_FOCUS_Y: Float = 0.5f
    /** 默认 P1 图片内容缩放因子 (不缩放) */
    const val DEFAULT_P1_CONTENT_SCALE_FACTOR: Float = 1.0f

    /** 默认滚动灵敏度 */
    const val DEFAULT_SCROLL_SENSITIVITY: Float = 1.0f
    /** 默认 P1 遮罩层淡出过渡比例 */
    const val DEFAULT_P1_OVERLAY_FADE_RATIO: Float = 0.5f
    /** 默认背景模糊半径 */
    const val DEFAULT_BACKGROUND_BLUR_RADIUS: Float = 25f
    /** 默认背景初始滚动偏移量 */
    const val DEFAULT_BACKGROUND_INITIAL_OFFSET: Float = 0.2f
    /** 默认 P2 背景淡入过渡比例 */
    const val DEFAULT_P2_BACKGROUND_FADE_IN_RATIO: Float = 0.8f
    /** 默认模糊处理时降采样因子对应的整数值 (存储为百分比的100倍，实际值为 0.1) */
    const val DEFAULT_BLUR_DOWNSCALE_FACTOR_INT: Int = 10
    /** 默认模糊处理迭代次数 */
    const val DEFAULT_BLUR_ITERATIONS: Int = 1

    /** 默认 P1 图片投影效果半径 (无投影) */
    const val DEFAULT_P1_SHADOW_RADIUS: Float = 0f
    /** 默认 P1 图片投影效果 X 轴偏移量 (无投影) */
    const val DEFAULT_P1_SHADOW_DX: Float = 0f
    /** 默认 P1 图片投影效果 Y 轴偏移量 (无投影) */
    const val DEFAULT_P1_SHADOW_DY: Float = 0f
    /** 默认 P1 图片投影效果颜色 (半透明黑色) */
    val DEFAULT_P1_SHADOW_COLOR: Int = Color.argb(180, 0, 0, 0)
    /** 默认 P1 图片底部融入效果高度 (无融入) */
    const val DEFAULT_P1_IMAGE_BOTTOM_FADE_HEIGHT: Float = 0f
    /** 默认图片内容版本号 */
    const val DEFAULT_IMAGE_CONTENT_VERSION: Long = 0L

    // UI Related Defaults
    /** 预览视图中页面吸附动画的默认时长 (毫秒) */
    const val DEFAULT_PREVIEW_SNAP_DURATION_MS: Long = 700L
    /** P1 图片高度比例调整的步长 */
    const val HEIGHT_RATIO_STEP: Float = 0.02f
    /** P1 图片高度比例的最小值 */
    const val MIN_HEIGHT_RATIO: Float = 0.15f
    /** P1 图片高度比例的最大值 */
    const val MAX_HEIGHT_RATIO: Float = 0.75f

    // Scaled Integer Defaults for SeekBarPreferences (used in MainViewModel & BottomSheetScreen for sliders)
    /** 滚动灵敏度在 SeekBar 中对应的整数默认值 (实际值 * 10) */
    val DEFAULT_SCROLL_SENSITIVITY_INT: Int = (DEFAULT_SCROLL_SENSITIVITY * 10).toInt()
    /** P1 遮罩层淡出过渡比例在 SeekBar 中对应的整数默认值 (实际值 * 100) */
    val DEFAULT_P1_OVERLAY_FADE_RATIO_INT: Int = (DEFAULT_P1_OVERLAY_FADE_RATIO * 100).toInt()
    /** 背景模糊半径在 SeekBar 中对应的整数默认值 */
    val DEFAULT_BACKGROUND_BLUR_RADIUS_INT: Int = DEFAULT_BACKGROUND_BLUR_RADIUS.roundToInt()
    /** 背景初始滚动偏移量在 SeekBar 中对应的整数默认值 (实际值 * 10) */
    val DEFAULT_BACKGROUND_INITIAL_OFFSET_INT: Int = (DEFAULT_BACKGROUND_INITIAL_OFFSET * 10).toInt()
    /** P2 背景淡入过渡比例在 SeekBar 中对应的整数默认值 (实际值 * 100) */
    val DEFAULT_P2_BACKGROUND_FADE_IN_RATIO_INT: Int = (DEFAULT_P2_BACKGROUND_FADE_IN_RATIO * 100).toInt()
    /** P1 图片投影效果半径在 SeekBar 中对应的整数默认值 */
    val DEFAULT_P1_SHADOW_RADIUS_INT: Int = DEFAULT_P1_SHADOW_RADIUS.roundToInt()
    /** P1 图片投影效果 X 轴偏移量在 SeekBar 中对应的整数默认值 */
    val DEFAULT_P1_SHADOW_DX_INT: Int = DEFAULT_P1_SHADOW_DX.roundToInt()
    /** P1 图片投影效果 Y 轴偏移量在 SeekBar 中对应的整数默认值 */
    val DEFAULT_P1_SHADOW_DY_INT: Int = DEFAULT_P1_SHADOW_DY.roundToInt()
    /** P1 图片底部融入效果高度在 SeekBar 中对应的整数默认值 */
    val DEFAULT_P1_IMAGE_BOTTOM_FADE_HEIGHT_INT: Int = DEFAULT_P1_IMAGE_BOTTOM_FADE_HEIGHT.roundToInt()


    /**
     * 用于旧版焦点调整 Activity 的常量 (FocusActivity 已移除，但常量键名可能仍有参考价值或需要清理)
     */
    object FocusParams {
        const val EXTRA_IMAGE_URI = "com.example.h2wallpaper.EXTRA_IMAGE_URI"
        const val EXTRA_ASPECT_RATIO = "com.example.h2wallpaper.EXTRA_ASPECT_RATIO"
        const val EXTRA_INITIAL_FOCUS_X = "com.example.h2wallpaper.EXTRA_INITIAL_FOCUS_X"
        const val EXTRA_INITIAL_FOCUS_Y = "com.example.h2wallpaper.EXTRA_INITIAL_FOCUS_Y"
        const val RESULT_FOCUS_X = "com.example.h2wallpaper.RESULT_FOCUS_X"
        const val RESULT_FOCUS_Y = "com.example.h2wallpaper.RESULT_FOCUS_Y"
    }
}