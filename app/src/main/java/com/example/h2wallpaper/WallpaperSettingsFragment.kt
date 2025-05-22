package com.example.h2wallpaper

import android.content.SharedPreferences
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.SeekBarPreference
import java.util.Locale

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
        // 确保 sharedPreferences 已初始化
        if (!::sharedPreferences.isInitialized) {
            sharedPreferences = preferenceManager.sharedPreferences!!
        }

        when (preference.key) {
            MainActivity.KEY_SCROLL_SENSITIVITY -> {
                val scaledValue = sharedPreferences.getInt(
                    preference.key,
                    (MainActivity.DEFAULT_SCROLL_SENSITIVITY * 10).toInt()
                )
                // 使用 Locale.US 来确保小数点是 '.'
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
                val value = sharedPreferences.getInt(
                    preference.key,
                    MainActivity.DEFAULT_BACKGROUND_BLUR_RADIUS.toInt()
                )
                preference.summary = "当前: $value (范围 0 - 25)"
            }
            MainActivity.KEY_BLUR_DOWNSCALE_FACTOR -> { // 新增
                val intValue = sharedPreferences.getInt(
                    preference.key,
                    MainActivity.DEFAULT_BLUR_DOWNSCALE_FACTOR_INT
                )
                // 将整数值转换为实际的浮点因子进行显示
                val factor = intValue / 100.0f
                preference.summary = String.format(Locale.US, "当前因子: %.2f (值: %d, 范围 0.05 - 1.00)", factor, intValue)
            }
            MainActivity.KEY_BLUR_ITERATIONS -> { // 新增
                val value = sharedPreferences.getInt(
                    preference.key,
                    MainActivity.DEFAULT_BLUR_ITERATIONS
                )
                preference.summary = "当前: $value 次 (范围 1 - 3)"
            }

            // 你可以为其他类型的 Preference 添加 summary 更新逻辑
            // 例如，ListPreference:
            // is ListPreference -> {
            //     preference.summary = preference.entry
            // }
            // EditTextPreference:
            // is EditTextPreference -> {
            //     preference.summary = preference.text
            // }
            else -> {
                // 对于没有特殊处理的 Preference，可以保留其在 XML 中定义的静态 summary
                // 或者如果 XML summary 使用了占位符（如 %s），可以在这里设置
                // 例如：if (preference.summaryProvider == null && preference is EditTextPreference) {
                //    preference.summary = preference.text
                // }
            }
        }
    }
}