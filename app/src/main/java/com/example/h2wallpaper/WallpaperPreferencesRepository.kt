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
import com.example.h2wallpaper.WallpaperConfigConstants.DEFAULT_P1_CONTENT_SCALE_FACTOR
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
import com.example.h2wallpaper.WallpaperConfigConstants.KEY_P1_CONTENT_SCALE_FACTOR
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

/**
 * 仓库类，负责壁纸配置的持久化存储和读取。
 * 使用 Android 的 SharedPreferences 作为存储后端。
 *
 * @property prefs SharedPreferences 实例，用于实际的存取操作。
 */
class WallpaperPreferencesRepository(context: Context) {

    internal val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) //

    // --- Getters ---

    /**
     * 获取存储的选定图片 URI。
     * @return 如果已存储则返回 Uri，否则返回 null。
     */
    fun getSelectedImageUri(): Uri? = prefs.getString(KEY_IMAGE_URI, null)?.let { Uri.parse(it) } //

    /**
     * 获取存储的选定背景颜色。
     * @return 返回存储的颜色值，如果未存储则返回默认背景颜色。
     */
    fun getSelectedBackgroundColor(): Int =
        prefs.getInt(KEY_BACKGROUND_COLOR, DEFAULT_BACKGROUND_COLOR) //

    /**
     * 获取存储的 P1 图片高度比例。
     * @return 返回存储的高度比例，如果未存储则返回默认高度比例。
     */
    fun getPage1ImageHeightRatio(): Float =
        prefs.getFloat(KEY_IMAGE_HEIGHT_RATIO, DEFAULT_HEIGHT_RATIO) //

    /**
     * 获取存储的 P1 图片归一化焦点 X 坐标。
     * @return 返回存储的焦点 X 坐标，如果未存储则返回默认值。
     */
    fun getP1FocusX(): Float = prefs.getFloat(KEY_P1_FOCUS_X, DEFAULT_P1_FOCUS_X) //

    /**
     * 获取存储的 P1 图片归一化焦点 Y 坐标。
     * @return 返回存储的焦点 Y 坐标，如果未存储则返回默认值。
     */
    fun getP1FocusY(): Float = prefs.getFloat(KEY_P1_FOCUS_Y, DEFAULT_P1_FOCUS_Y) //

    /**
     * 获取存储的 P1 图片内容缩放因子。
     * @return 返回存储的内容缩放因子，如果未存储则返回默认值。
     */
    fun getP1ContentScaleFactor(): Float =
        prefs.getFloat(KEY_P1_CONTENT_SCALE_FACTOR, DEFAULT_P1_CONTENT_SCALE_FACTOR) //

    /**
     * 获取存储的滚动灵敏度。
     * 存储时是整数 (实际值 * 10)，读取时转换为 Float。
     * @return 返回存储的滚动灵敏度，如果未存储则返回默认值。
     */
    fun getScrollSensitivity(): Float =
        prefs.getInt(KEY_SCROLL_SENSITIVITY, DEFAULT_SCROLL_SENSITIVITY_INT) / 10.0f //

    /**
     * 获取存储的 P1 遮罩层淡出过渡比例。
     * 存储时是整数 (实际值 * 100)，读取时转换为 Float (0.0f - 1.0f)。
     * @return 返回存储的淡出比例，如果未存储则返回默认值。
     */
    fun getP1OverlayFadeRatio(): Float =
        prefs.getInt(KEY_P1_OVERLAY_FADE_RATIO, DEFAULT_P1_OVERLAY_FADE_RATIO_INT) / 100.0f //

    /**
     * 获取存储的背景模糊半径。
     * @return 返回存储的模糊半径 (Float)，如果未存储则返回默认值。
     */
    fun getBackgroundBlurRadius(): Float = prefs.getInt(
        KEY_BACKGROUND_BLUR_RADIUS, //
        WallpaperConfigConstants.DEFAULT_BACKGROUND_BLUR_RADIUS_INT //
    ).toFloat()

    /**
     * 获取存储的背景初始滚动偏移量。
     * 存储时是整数 (实际值 * 10)，读取时转换为 Float。
     * @return 返回存储的初始偏移量，如果未存储则返回默认值。
     */
    fun getBackgroundInitialOffset(): Float =
        prefs.getInt(KEY_BACKGROUND_INITIAL_OFFSET, DEFAULT_BACKGROUND_INITIAL_OFFSET_INT) / 10.0f //

    /**
     * 获取存储的 P2 背景淡入过渡比例。
     * 存储时是整数 (实际值 * 100)，读取时转换为 Float (0.0f - 1.0f)。
     * @return 返回存储的淡入比例，如果未存储则返回默认值。
     */
    fun getP2BackgroundFadeInRatio(): Float = prefs.getInt(
        KEY_P2_BACKGROUND_FADE_IN_RATIO, //
        DEFAULT_P2_BACKGROUND_FADE_IN_RATIO_INT //
    ) / 100.0f

    /**
     * 获取存储的模糊处理降采样因子。
     * 存储时是整数 (实际值 * 100)，读取时转换为 Float。
     * @return 返回存储的降采样因子，如果未存储则返回默认值。
     */
    fun getBlurDownscaleFactor(): Float =
        prefs.getInt(KEY_BLUR_DOWNSCALE_FACTOR, DEFAULT_BLUR_DOWNSCALE_FACTOR_INT) / 100.0f //

    /**
     * 获取存储的模糊处理迭代次数。
     * @return 返回存储的迭代次数，如果未存储则返回默认值。
     */
    fun getBlurIterations(): Int = prefs.getInt(KEY_BLUR_ITERATIONS, DEFAULT_BLUR_ITERATIONS) //

    /**
     * 获取存储的 P1 图片投影效果半径。
     * @return 返回存储的投影半径 (Float)，如果未存储则返回默认值。
     */
    fun getP1ShadowRadius(): Float =
        prefs.getInt(KEY_P1_SHADOW_RADIUS, DEFAULT_P1_SHADOW_RADIUS_INT).toFloat() //

    /**
     * 获取存储的 P1 图片投影效果 X 轴偏移量。
     * @return 返回存储的投影 X 轴偏移量 (Float)，如果未存储则返回默认值。
     */
    fun getP1ShadowDx(): Float = prefs.getInt(KEY_P1_SHADOW_DX, DEFAULT_P1_SHADOW_DX_INT).toFloat() //

    /**
     * 获取存储的 P1 图片投影效果 Y 轴偏移量。
     * @return 返回存储的投影 Y 轴偏移量 (Float)，如果未存储则返回默认值。
     */
    fun getP1ShadowDy(): Float = prefs.getInt(KEY_P1_SHADOW_DY, DEFAULT_P1_SHADOW_DY_INT).toFloat() //

    /**
     * 获取存储的 P1 图片投影效果颜色。
     * @return 返回存储的投影颜色，如果未存储则返回默认颜色。
     */
    fun getP1ShadowColor(): Int = prefs.getInt(KEY_P1_SHADOW_COLOR, DEFAULT_P1_SHADOW_COLOR) //

    /**
     * 获取存储的 P1 图片底部融入效果的高度。
     * @return 返回存储的融入高度 (Float)，如果未存储则返回默认值。
     */
    fun getP1ImageBottomFadeHeight(): Float =
        prefs.getInt(KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT, DEFAULT_P1_IMAGE_BOTTOM_FADE_HEIGHT_INT) //
            .toFloat()

    /**
     * 获取存储的图片内容版本号。
     * 用于跟踪配置的实质性变化。
     * @return 返回存储的版本号，如果未存储则返回默认版本号。
     */
    fun getImageContentVersion(): Long =
        prefs.getLong(KEY_IMAGE_CONTENT_VERSION, DEFAULT_IMAGE_CONTENT_VERSION) //

    // --- Setters ---

    /**
     * 设置并存储选定的图片 URI。
     * @param uri 要存储的图片 Uri，如果为 null，则会移除已存储的 URI。
     */
    fun setSelectedImageUri(uri: Uri?) {
        val editor = prefs.edit()
        if (uri != null) {
            editor.putString(KEY_IMAGE_URI, uri.toString()) //
        } else {
            editor.remove(KEY_IMAGE_URI) //
        }
        editor.apply()
    }

    /**
     * 设置并存储选定的背景颜色。
     * @param color 要存储的颜色值。
     */
    fun setSelectedBackgroundColor(color: Int) {
        prefs.edit().putInt(KEY_BACKGROUND_COLOR, color).apply() //
    }

    /**
     * 设置并存储 P1 图片的高度比例。
     * @param ratio 要存储的高度比例。
     */
    fun setPage1ImageHeightRatio(ratio: Float) {
        prefs.edit().putFloat(KEY_IMAGE_HEIGHT_RATIO, ratio).apply() //
    }

    /**
     * 设置并存储 P1 图片的归一化焦点 X 坐标。
     * @param focusX 要存储的焦点 X 坐标。
     */
    fun setP1FocusX(focusX: Float) {
        prefs.edit().putFloat(KEY_P1_FOCUS_X, focusX).apply() //
    }

    /**
     * 设置并存储 P1 图片的归一化焦点 Y 坐标。
     * @param focusY 要存储的焦点 Y 坐标。
     */
    fun setP1FocusY(focusY: Float) {
        prefs.edit().putFloat(KEY_P1_FOCUS_Y, focusY).apply() //
    }

    /**
     * 同时设置并存储 P1 图片的归一化焦点 X 和 Y 坐标。
     * @param focusX 要存储的焦点 X 坐标。
     * @param focusY 要存储的焦点 Y 坐标。
     */
    fun setP1Focus(focusX: Float, focusY: Float) {
        prefs.edit()
            .putFloat(KEY_P1_FOCUS_X, focusX) //
            .putFloat(KEY_P1_FOCUS_Y, focusY) //
            .apply()
    }

    /**
     * 设置并存储 P1 图片的内容缩放因子。
     * @param scaleFactor 要存储的内容缩放因子。
     */
    fun setP1ContentScaleFactor(scaleFactor: Float) {
        prefs.edit().putFloat(KEY_P1_CONTENT_SCALE_FACTOR, scaleFactor).apply() //
    }

    /**
     * 更新并存储图片内容版本号为当前系统时间戳。
     * 当任何可能影响壁纸视觉效果的配置发生更改时，应调用此方法。
     */
    fun updateImageContentVersion() {
        prefs.edit().putLong(KEY_IMAGE_CONTENT_VERSION, System.currentTimeMillis()).apply() //
    }

    /**
     * 将一个浮点型配置值乘以一个缩放因子后，四舍五入为整数并存储。
     * 主要用于将 ViewModel 中的 Float 类型参数转换为整数存储，以便与 SeekBar 等组件兼容。
     * @param key 配置项的键名。
     * @param actualFloatValue 实际的浮点数值。
     * @param scaleFactor 乘以的缩放因子。
     */
    fun saveScaledFloatSettingAsInt(key: String, actualFloatValue: Float, scaleFactor: Int) {
        val intValue = (actualFloatValue * scaleFactor).roundToInt()
        prefs.edit().putInt(key, intValue).apply()
    }

    /**
     * 直接存储一个浮点型配置值。
     * @param key 配置项的键名。
     * @param value 要存储的浮点数值。
     */
    fun saveFloatSetting(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
    }

    /**
     * 直接存储一个整型配置值。
     * @param key 配置项的键名。
     * @param value 要存储的整数值。
     */
    fun saveIntSetting(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }


    /**
     * 当用户选择一张新图片时，重置与图片相关的部分配置项为默认值。
     * 例如，焦点位置和内容缩放通常需要针对新图片重置。
     * P1 图片高度通常不随新图片重置，除非有特定设计需求。
     * @param newImageUri 新选择的图片的 Uri。
     */
    fun resetSettingsForNewImage(newImageUri: Uri) {
        prefs.edit()
            .putString(KEY_IMAGE_URI, newImageUri.toString()) //
            .putFloat(KEY_P1_FOCUS_X, DEFAULT_P1_FOCUS_X) //
            .putFloat(KEY_P1_FOCUS_Y, DEFAULT_P1_FOCUS_Y) //
            .putFloat(KEY_P1_CONTENT_SCALE_FACTOR, DEFAULT_P1_CONTENT_SCALE_FACTOR) //
            // .putFloat(KEY_IMAGE_HEIGHT_RATIO, DEFAULT_HEIGHT_RATIO) // P1 高度通常不随新图片重置
            .putLong(KEY_IMAGE_CONTENT_VERSION, System.currentTimeMillis()) //
            .apply()
    }

    /**
     * 从 SharedPreferences 中移除已存储的图片 URI。
     * 通常在图片加载失败或用户主动清除图片时调用。
     */
    fun removeImageUri() {
        prefs.edit().remove(KEY_IMAGE_URI).apply() //
    }
}