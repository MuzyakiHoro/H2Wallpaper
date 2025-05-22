package com.example.h2wallpaper

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // WindowCompat.setDecorFitsSystemWindows(window, false) // 可选，如果需要沉浸式

        setContentView(R.layout.activity_settings) // 使用上面的 FrameLayout 布局

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, WallpaperSettingsFragment())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true) // 显示返回按钮
        title = "壁纸高级设置" // 设置标题
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed() // 处理返回按钮点击
        return true
    }
}