package com.example.h2wallpaper

import android.app.Activity
import android.content.Intent
import android.graphics.PointF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

// 显式导入 WallpaperConfigConstants 对象本身，以及 FocusParams 嵌套对象
import com.example.h2wallpaper.WallpaperConfigConstants
import com.example.h2wallpaper.WallpaperConfigConstants.FocusParams

class FocusActivity : AppCompatActivity() {

    private val TAG = "FocusActivityLifecycle"

    // UI 控件
    private lateinit var focusSelectionView: FocusSelectionView
    private lateinit var btnApplyFocus: Button
    private lateinit var btnCancelFocus: Button
    private lateinit var btnResetFocus: Button

    // 从 Intent 接收的参数
    private var imageUriFromMain: Uri? = null
    private var p1AspectRatioFromMain: Float = 16f / 9f
    private var initialP1FocusX: Float = 0.5f
    private var initialP1FocusY: Float = 0.5f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate START")
        setContentView(R.layout.activity_focus)

        focusSelectionView = findViewById(R.id.focusSelectionView)
        btnApplyFocus = findViewById(R.id.btnApplyFocus)
        btnCancelFocus = findViewById(R.id.btnCancelFocus)
        btnResetFocus = findViewById(R.id.btnResetFocus)

        val receivedIntent = intent
        if (receivedIntent == null) {
            Log.e(TAG, "Intent is null! Finishing activity.")
            Toast.makeText(this, "启动参数错误", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (receivedIntent.extras != null) {
            for (key in receivedIntent.extras!!.keySet()) {
                Log.d(TAG, "Received Extra: Key=$key, Value='${receivedIntent.extras!!.get(key)}'")
            }
        } else {
            Log.w(TAG, "Received Intent extras are null!")
        }
        Log.d(TAG, "Received Intent data URI: ${receivedIntent.data}")

        var tempUri: Uri? = receivedIntent.data
        if (tempUri == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                tempUri =
                    receivedIntent.getParcelableExtra(FocusParams.EXTRA_IMAGE_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                tempUri = receivedIntent.getParcelableExtra(FocusParams.EXTRA_IMAGE_URI)
            }
        }
        imageUriFromMain = tempUri

        // 使用 FocusParams 中的常量
        p1AspectRatioFromMain =
            receivedIntent.getFloatExtra(FocusParams.EXTRA_ASPECT_RATIO, 16f / 9f)
        // 注意：这里的默认值 0.5f 应该与 WallpaperConfigConstants.DEFAULT_P1_FOCUS_X/Y 一致，
        // 但由于 Intent 的 getFloatExtra 本身就需要一个默认值，所以这里直接写 0.5f 是可以的，
        // 或者你可以显式使用 WallpaperConfigConstants.DEFAULT_P1_FOCUS_X。
        // 为保持一致性，我们使用 WallpaperConfigConstants 中的默认值。
        initialP1FocusX = receivedIntent.getFloatExtra(
            FocusParams.EXTRA_INITIAL_FOCUS_X,
            WallpaperConfigConstants.DEFAULT_P1_FOCUS_X
        )
        initialP1FocusY = receivedIntent.getFloatExtra(
            FocusParams.EXTRA_INITIAL_FOCUS_Y,
            WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y
        )

        Log.i(TAG, "Processed imageUriFromMain: $imageUriFromMain")
        Log.i(TAG, "Processed p1AspectRatioFromMain: $p1AspectRatioFromMain")
        Log.i(TAG, "Processed initialP1FocusX: $initialP1FocusX, initialP1FocusY: $initialP1FocusY")

        if (imageUriFromMain == null) {
            Log.e(TAG, "imageUriFromMain is NULL after processing Intent. Finishing activity.")
            Toast.makeText(this, "未接收到有效的图片URI", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        Log.d(TAG, "Calling focusSelectionView.setImageUri with URI: $imageUriFromMain")
        focusSelectionView.setImageUri(
            uri = imageUriFromMain!!,
            targetP1AspectRatio = p1AspectRatioFromMain,
            initialNormFocusX = initialP1FocusX,
            initialNormFocusY = initialP1FocusY
        )

        btnApplyFocus.setOnClickListener {
            val currentFocusPoint: PointF = focusSelectionView.getNormalizedFocusPoint()
            Log.d(
                TAG,
                "Apply button clicked. Returning focus: (${currentFocusPoint.x}, ${currentFocusPoint.y})"
            )
            val resultIntent = Intent().apply {
                putExtra(FocusParams.RESULT_FOCUS_X, currentFocusPoint.x)
                putExtra(FocusParams.RESULT_FOCUS_Y, currentFocusPoint.y)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }

        btnCancelFocus.setOnClickListener {
            Log.d(TAG, "Cancel button clicked.")
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        btnResetFocus.setOnClickListener {
            Log.d(
                TAG,
                "Reset button clicked. Resetting to initial focus: ($initialP1FocusX, $initialP1FocusY)"
            )
            focusSelectionView.resetImageToFocusPoint(initialP1FocusX, initialP1FocusY)
            Toast.makeText(this, "焦点已重置", Toast.LENGTH_SHORT).show()
        }
        Log.d(TAG, "onCreate END. FocusSelectionView should be trying to load the image.")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
    }
}