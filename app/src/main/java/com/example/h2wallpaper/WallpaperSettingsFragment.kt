package com.example.h2wallpaper

import android.content.SharedPreferences
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
// SeekBarPreference 通常不需要单独导入，因为它属于 androidx.preference
import java.util.Locale
import kotlin.math.roundToInt

// 显式导入 WallpaperConfigConstants 对象
import com.example.h2wallpaper.WallpaperConfigConstants

class WallpaperSettingsFragment : PreferenceFragmentCompat(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // 使用 WallpaperConfigConstants 中的 PREFS_NAME
        preferenceManager.sharedPreferencesName = WallpaperConfigConstants.PREFS_NAME
        setPreferencesFromResource(R.xml.preferences_wallpaper, rootKey)

        // sharedPreferences 将会是 "H2WallpaperPrefs"
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

        // 使用 WallpaperConfigConstants 中的 KEY_* 和 DEFAULT_*_INT 常量
        when (preference.key) {
            WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY -> {
                val scaledValue = sharedPreferences.getInt(
                    preference.key,
                    WallpaperConfigConstants.DEFAULT_SCROLL_SENSITIVITY_INT
                )
                preference.summary =
                    String.format(Locale.US, "当前: %.1f (范围 0.1 - 2.0)", scaledValue / 10.0f)
            }

            WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO -> {
                val scaledValue = sharedPreferences.getInt(
                    preference.key,
                    WallpaperConfigConstants.DEFAULT_P1_OVERLAY_FADE_RATIO_INT
                )
                preference.summary =
                    String.format(Locale.US, "当前: %.2f (范围 0.01 - 1.0)", scaledValue / 100.0f)
            }

            WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO -> {
                val scaledValue = sharedPreferences.getInt(
                    preference.key,
                    WallpaperConfigConstants.DEFAULT_P2_BACKGROUND_FADE_IN_RATIO_INT
                )
                preference.summary =
                    String.format(Locale.US, "当前: %.2f (范围 0.01 - 1.0)", scaledValue / 100.0f)
            }

            WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET -> {
                val scaledValue = sharedPreferences.getInt(
                    preference.key,
                    WallpaperConfigConstants.DEFAULT_BACKGROUND_INITIAL_OFFSET_INT
                )
                preference.summary =
                    String.format(Locale.US, "当前: %.1f (范围 0.0 - 1.0)", scaledValue / 10.0f)
            }

            WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS -> {
                val valueInt = sharedPreferences.getInt(
                    preference.key,
                    WallpaperConfigConstants.DEFAULT_BACKGROUND_BLUR_RADIUS_INT
                )
                preference.summary = "当前: $valueInt px (范围 0 - 25)" // XML 中 max 是 25
            }

            WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR -> {
                val valueInt = sharedPreferences.getInt(
                    preference.key,
                    WallpaperConfigConstants.DEFAULT_BLUR_DOWNSCALE_FACTOR_INT
                )
                preference.summary = String.format(
                    Locale.US,
                    "当前: %.2f (值 %d / 100)",
                    valueInt / 100.0f,
                    valueInt
                )
            }

            WallpaperConfigConstants.KEY_BLUR_ITERATIONS -> {
                val valueInt = sharedPreferences.getInt(
                    preference.key,
                    WallpaperConfigConstants.DEFAULT_BLUR_ITERATIONS
                )
                preference.summary = "当前: $valueInt 次"
            }

            WallpaperConfigConstants.KEY_P1_SHADOW_RADIUS -> {
                val valueInt = sharedPreferences.getInt(
                    preference.key,
                    WallpaperConfigConstants.DEFAULT_P1_SHADOW_RADIUS_INT
                )
                preference.summary = String.format(Locale.US, "当前: %d px", valueInt)
            }

            WallpaperConfigConstants.KEY_P1_SHADOW_DX -> {
                val valueInt = sharedPreferences.getInt(
                    preference.key,
                    WallpaperConfigConstants.DEFAULT_P1_SHADOW_DX_INT
                )
                preference.summary = String.format(Locale.US, "当前: %d px", valueInt)
            }

            WallpaperConfigConstants.KEY_P1_SHADOW_DY -> {
                val valueInt = sharedPreferences.getInt(
                    preference.key,
                    WallpaperConfigConstants.DEFAULT_P1_SHADOW_DY_INT
                )
                preference.summary = String.format(Locale.US, "当前: %d px", valueInt)
            }

            WallpaperConfigConstants.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT -> {
                val valueInt = sharedPreferences.getInt(
                    preference.key,
                    WallpaperConfigConstants.DEFAULT_P1_IMAGE_BOTTOM_FADE_HEIGHT_INT
                )
                preference.summary = String.format(Locale.US, "当前: %d px", valueInt)
            }

            else -> {
                // 对于没有特殊处理的 Preference，可以尝试获取其持久化的值并显示
            }
        }
    }
}