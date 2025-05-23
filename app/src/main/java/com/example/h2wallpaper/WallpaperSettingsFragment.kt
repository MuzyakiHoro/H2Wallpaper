package com.example.h2wallpaper

import android.content.SharedPreferences
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.SeekBarPreference
import java.util.Locale
import kotlin.math.roundToInt

class WallpaperSettingsFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {

    // 使用 lateinit 确保 sharedPreferences 在使用前被初始化
    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // *** 重要修改：指定 SharedPreferences 文件名 ***
        preferenceManager.sharedPreferencesName = MainActivity.PREFS_NAME
        // 可选：如果你的 SharedPreferences 不是 MODE_PRIVATE，可以在这里指定
        // preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE

        setPreferencesFromResource(R.xml.preferences_wallpaper, rootKey)

        // 现在 sharedPreferences 将会是 "H2WallpaperPrefs"
        sharedPreferences = preferenceManager.sharedPreferences!!
    }

    override fun onResume() {
        super.onResume()
        preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        updateAllPreferenceSummaries()
    }

    override fun onPause() {
        super.onPause()
        preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        key?.let {
            val changedPreference = findPreference<Preference>(it)
            changedPreference?.let { pref ->
                updatePreferenceSummary(pref)
            }
        }
        // 你可以在这里添加逻辑，例如通过接口或 LocalBroadcastManager 通知 MainActivity
        // MainActivity 中的 WallpaperPreviewView 可能需要根据新的设置值刷新
        // 不过，由于 MainActivity 在 onResume 时会重新加载配置，
        // 并且 Service 也会监听 SharedPreferences 的变化，
        // 所以这里可能不需要显式通知。
    }

    private fun updateAllPreferenceSummaries() {
        val screen = preferenceScreen
        for (i in 0 until screen.preferenceCount) {
            val preference = screen.getPreference(i)
            if (preference is PreferenceGroup) {
                for (j in 0 until preference.preferenceCount) {
                    updatePreferenceSummary(preference.getPreference(j))
                }
            } else {
                updatePreferenceSummary(preference)
            }
        }
    }

    private fun updatePreferenceSummary(preference: Preference) {
        if (!::sharedPreferences.isInitialized) {
            sharedPreferences = preferenceManager.sharedPreferences!!
        }

        when (preference.key) {
            MainActivity.KEY_SCROLL_SENSITIVITY -> {
                val scaledValue = sharedPreferences.getInt(
                    preference.key,
                    (MainActivity.DEFAULT_SCROLL_SENSITIVITY * 10).toInt()
                )
                preference.summary = String.format(Locale.US, "当前: %.1f (范围 0.1 - 2.0)", scaledValue / 10.0f)
            }
            MainActivity.KEY_P1_OVERLAY_FADE_RATIO -> {
                val scaledValue = sharedPreferences.getInt(
                    preference.key,
                    (MainActivity.DEFAULT_P1_OVERLAY_FADE_RATIO * 100).toInt()
                )
                preference.summary = String.format(Locale.US, "当前: %.2f (范围 0.01 - 1.0)", scaledValue / 100.0f)
            }
            MainActivity.KEY_P2_BACKGROUND_FADE_IN_RATIO -> {
                val scaledValue = sharedPreferences.getInt(
                    preference.key,
                    (MainActivity.DEFAULT_P2_BACKGROUND_FADE_IN_RATIO * 100).toInt()
                )
                preference.summary = String.format(Locale.US, "当前: %.2f (范围 0.01 - 1.0)", scaledValue / 100.0f)
            }
            MainActivity.KEY_BACKGROUND_INITIAL_OFFSET -> {
                val scaledValue = sharedPreferences.getInt(
                    preference.key,
                    (MainActivity.DEFAULT_BACKGROUND_INITIAL_OFFSET * 10).toInt()
                )
                preference.summary = String.format(Locale.US, "当前: %.1f (范围 0.0 - 1.0)", scaledValue / 10.0f)
            }
            MainActivity.KEY_BACKGROUND_BLUR_RADIUS -> {
                // 假设 blur radius 在 SharedPreferences 中存的是 Int (由 SeekBarPreference 直接写入)
                val valueInt = sharedPreferences.getInt(
                    preference.key,
                    MainActivity.DEFAULT_BACKGROUND_BLUR_RADIUS.roundToInt() // 默认值对应 Int
                )
                preference.summary = "当前: $valueInt px (范围 0 - 50)" // 直接显示整数像素
            }
            MainActivity.KEY_BLUR_DOWNSCALE_FACTOR -> {
                val valueInt = sharedPreferences.getInt( // 直接是百分比整数
                    preference.key,
                    MainActivity.DEFAULT_BLUR_DOWNSCALE_FACTOR_INT
                )
                preference.summary = String.format(Locale.US, "当前: %.2f (值 %d / 100)", valueInt / 100.0f, valueInt)
            }
            MainActivity.KEY_BLUR_ITERATIONS -> {
                val valueInt = sharedPreferences.getInt(
                    preference.key,
                    MainActivity.DEFAULT_BLUR_ITERATIONS
                )
                preference.summary = "当前: $valueInt 次"
            }

            // --- 新增 P1 特效参数的 summary 更新 ---
            // 假设这些参数在 SharedPreferences 中都由 SeekBarPreference 存为 Int (代表像素值)
            MainActivity.KEY_P1_SHADOW_RADIUS -> {
                val valueInt = sharedPreferences.getInt(
                    preference.key,
                    MainActivity.DEFAULT_P1_SHADOW_RADIUS.roundToInt()
                )
                preference.summary = String.format(Locale.US, "当前: %d px", valueInt)
            }
            MainActivity.KEY_P1_SHADOW_DX -> {
                val valueInt = sharedPreferences.getInt(
                    preference.key,
                    MainActivity.DEFAULT_P1_SHADOW_DX.roundToInt()
                )
                preference.summary = String.format(Locale.US, "当前: %d px", valueInt)
            }
            MainActivity.KEY_P1_SHADOW_DY -> {
                val valueInt = sharedPreferences.getInt(
                    preference.key,
                    MainActivity.DEFAULT_P1_SHADOW_DY.roundToInt()
                )
                preference.summary = String.format(Locale.US, "当前: %d px", valueInt)
            }
            MainActivity.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT -> {
                val valueInt = sharedPreferences.getInt( // 读取 Int
                    preference.key,
                    MainActivity.DEFAULT_P1_IMAGE_BOTTOM_FADE_HEIGHT.roundToInt() // 默认值也用Int形式
                )
                preference.summary = String.format(Locale.US, "当前: %d px", valueInt)
            }
            // 注意：KEY_P1_SHADOW_COLOR 是 Int 类型，通常不由 SeekBarPreference 直接控制，
            // 如果将来添加颜色选择，其 summary 更新方式会不同。目前我们没在UI上提供它。

            else -> {
                // 对于没有特殊处理的 Preference，可以尝试获取其持久化的值并显示
                // 但通常 SeekBarPreference 需要特定格式的 summary
                // 如果是 EditTextPreference 或 ListPreference，有标准方法获取其值
                // preference.summary = sharedPreferences.getString(preference.key, "") // 示例
            }
        }
    }
}