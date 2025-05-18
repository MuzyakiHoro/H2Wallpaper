package com.example.h2wallpaper

import android.app.Activity
import android.content.Intent
import android.graphics.PointF // 确保导入 PointF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class FocusActivity : AppCompatActivity() {

    private val TAG = "FocusActivityLifecycle" // Logcat TAG for this Activity

    // UI 控件
    private lateinit var focusSelectionView: FocusSelectionView
    private lateinit var btnApplyFocus: Button
    private lateinit var btnCancelFocus: Button
    private lateinit var btnResetFocus: Button

    // 从 Intent 接收的参数
    private var imageUriFromMain: Uri? = null
    private var p1AspectRatioFromMain: Float = 16f / 9f // 默认值
    private var initialP1FocusX: Float = 0.5f
    private var initialP1FocusY: Float = 0.5f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate START")
        setContentView(R.layout.activity_focus) // 确保这个布局文件名正确

        // 初始化UI控件
        focusSelectionView = findViewById(R.id.focusSelectionView)
        btnApplyFocus = findViewById(R.id.btnApplyFocus)
        btnCancelFocus = findViewById(R.id.btnCancelFocus)
        btnResetFocus = findViewById(R.id.btnResetFocus)

        // 1. 接收从 MainActivity 传来的数据
        val receivedIntent = intent
        if (receivedIntent == null) {
            Log.e(TAG, "Intent is null! Finishing activity.")
            Toast.makeText(this, "启动参数错误", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // 打印 Intent extras (用于调试)
        if (receivedIntent.extras != null) {
            for (key in receivedIntent.extras!!.keySet()) {
                Log.d(TAG, "Received Extra: Key=$key, Value='${receivedIntent.extras!!.get(key)}'")
            }
        } else {
            Log.w(TAG, "Received Intent extras are null!")
        }
        Log.d(TAG, "Received Intent data URI: ${receivedIntent.data}")


        // 获取 imageUriFromMain (优先从 Intent.data 获取，如果 MainActivity 设置了的话，否则从 extra)
        var tempUri: Uri? = receivedIntent.data // 尝试从 data 字段获取
        if (tempUri == null) { // 如果 data 字段为空，再从 extra 获取
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                tempUri = receivedIntent.getParcelableExtra(FocusParams.EXTRA_IMAGE_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                tempUri = receivedIntent.getParcelableExtra(FocusParams.EXTRA_IMAGE_URI)
            }
        }
        imageUriFromMain = tempUri


        p1AspectRatioFromMain = receivedIntent.getFloatExtra(FocusParams.EXTRA_ASPECT_RATIO, 16f / 9f)
        initialP1FocusX = receivedIntent.getFloatExtra(FocusParams.EXTRA_INITIAL_FOCUS_X, 0.5f)
        initialP1FocusY = receivedIntent.getFloatExtra(FocusParams.EXTRA_INITIAL_FOCUS_Y, 0.5f)

        Log.i(TAG, "Processed imageUriFromMain: $imageUriFromMain")
        Log.i(TAG, "Processed p1AspectRatioFromMain: $p1AspectRatioFromMain")
        Log.i(TAG, "Processed initialP1FocusX: $initialP1FocusX, initialP1FocusY: $initialP1FocusY")


        // 2. 验证图片URI是否存在
        if (imageUriFromMain == null) {
            Log.e(TAG, "imageUriFromMain is NULL after processing Intent. Finishing activity.")
            Toast.makeText(this, "未接收到有效的图片URI", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // 3. 将参数设置给 FocusSelectionView
        // 注意：确保 FocusSelectionView.setImageUri 方法的参数顺序和类型与这里一致
        Log.d(TAG, "Calling focusSelectionView.setImageUri with URI: $imageUriFromMain")
        focusSelectionView.setImageUri(
            uri = imageUriFromMain!!,
            targetP1AspectRatio = p1AspectRatioFromMain,
            initialNormFocusX = initialP1FocusX,
            initialNormFocusY = initialP1FocusY
        )

        // 4. 设置按钮点击事件
        btnApplyFocus.setOnClickListener {
            val currentFocusPoint: PointF = focusSelectionView.getNormalizedFocusPoint()
            Log.d(TAG, "Apply button clicked. Returning focus: (${currentFocusPoint.x}, ${currentFocusPoint.y})")
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
            Log.d(TAG, "Reset button clicked. Resetting to initial focus: ($initialP1FocusX, $initialP1FocusY)")
            // 让 FocusSelectionView 重置到最初传入的焦点，或者一个标准的默认焦点
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