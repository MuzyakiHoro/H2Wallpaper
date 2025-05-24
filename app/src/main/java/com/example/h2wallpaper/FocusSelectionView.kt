package com.example.h2wallpaper

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Toast
import androidx.core.graphics.withMatrix
import kotlinx.coroutines.*
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

class FocusSelectionView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs) {

    private val TAG = "FocusSelectionView"

    private var sourceBitmap: Bitmap? = null
    private val imageMatrix = Matrix() // 主矩阵，用于图片的缩放和平移

    // P1 预览框在 View 中的位置和大小 (根据外部传入的宽高比计算)
    private val p1PreviewRectView = RectF()
    private var p1PreviewAspectRatio: Float = 16f / 9f // 默认值，会被外部设置

    // 手势检测器
    private lateinit var gestureDetector: GestureDetector
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    // 缩放限制是相对于“基础填充缩放比例”的倍数
    private var minUserScaleFactor = 1.0f // 最小就是刚好填满预览框
    private var maxUserScaleFactor = 1.0f // 最大允许放大4倍

    private val viewScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var bitmapLoadingJob: Job? = null

    // 画笔
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val previewBoxBorderPaint = Paint().apply { // 用于预览框边框
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f // 边框宽度
        alpha = 200
    }
    private val previewBoxGridPaint = Paint().apply { // 用于预览框内部网格线
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f // 网格线宽度
        alpha = 100
    }
    private val overlayPaint = Paint().apply { // 预览框外部的半透明遮罩
        color = Color.BLACK
        alpha = 150 // 半透明度
    }

    init {
        if (!isInEditMode) { // 避免在布局编辑器预览时初始化手势检测器
            initializeGestureDetectors()
        }
    }

    private fun initializeGestureDetectors() {
        gestureDetector =
            GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean {
                    return sourceBitmap != null // 只有在有图片时才开始处理手势
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float
                ): Boolean {
                    if (sourceBitmap == null || e1 == null) return false // 添加e1的空检查
                    imageMatrix.postTranslate(-distanceX, -distanceY)
                    applyMatrixBounds()
                    invalidate()
                    return true
                }
            })

        scaleGestureDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (sourceBitmap == null) return false
                    val scaleFactor = detector.scaleFactor
                    val currentGlobalScale = getCurrentGlobalScale()
                    val baseFillScale = calculateBaseFillScale()

                    var newGlobalScale = currentGlobalScale * scaleFactor
                    newGlobalScale = newGlobalScale.coerceIn(
                        baseFillScale * minUserScaleFactor,
                        baseFillScale * maxUserScaleFactor
                    )

                    val actualAppliedScaleFactor = newGlobalScale / currentGlobalScale

                    if (actualAppliedScaleFactor != 1.0f) {
                        imageMatrix.postScale(
                            actualAppliedScaleFactor,
                            actualAppliedScaleFactor,
                            detector.focusX,
                            detector.focusY
                        )
                        applyMatrixBounds()
                        invalidate()
                    }
                    return true
                }
            })
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        bitmapLoadingJob?.cancel()
        viewScope.cancel() // 取消所有与此View关联的协程
        sourceBitmap?.recycle()
        sourceBitmap = null
        Log.d(TAG, "onDetachedFromWindow: Resources cleaned up.")
    }

    fun setImageUri(
        uri: Uri,
        targetP1AspectRatio: Float,
        initialNormFocusX: Float = 0.5f,
        initialNormFocusY: Float = 0.5f
    ) {
        Log.d(
            TAG,
            "setImageUri called with URI: $uri, AspectRatio: $targetP1AspectRatio, InitialFocus: ($initialNormFocusX, $initialNormFocusY)"
        )
        this.p1PreviewAspectRatio = if (targetP1AspectRatio > 0) targetP1AspectRatio else 16f / 9f

        bitmapLoadingJob?.cancel()
        sourceBitmap?.recycle() // 回收旧的Bitmap
        sourceBitmap = null
        imageMatrix.reset() // 重置矩阵

        bitmapLoadingJob = viewScope.launch {
            Log.d(TAG, "setImageUri: Coroutine started for URI: $uri")
            try {
                val loadedBitmap = loadImageAsync(uri)
                if (!isActive) { // 协程可能在加载过程中被取消
                    loadedBitmap?.recycle()
                    Log.d(
                        TAG,
                        "setImageUri: Coroutine cancelled during/after load, recycling loaded bitmap if any."
                    )
                    return@launch
                }

                if (loadedBitmap != null) {
                    Log.i(
                        TAG,
                        "setImageUri: Bitmap loaded successfully, original size: ${loadedBitmap.width}x${loadedBitmap.height}"
                    )
                    sourceBitmap = loadedBitmap
                    if (width > 0 && height > 0) { // 确保View已测量
                        resetImageToFocusPoint(initialNormFocusX, initialNormFocusY)
                    } else {
                        // View尚未测量，等待onSizeChanged中调用resetImageState
                        Log.d(
                            TAG,
                            "setImageUri: View not yet measured, reset will happen in onSizeChanged."
                        )
                        // 也可以post一个任务来确保在测量后执行
                        post {
                            if (width > 0 && height > 0) resetImageToFocusPoint(
                                initialNormFocusX,
                                initialNormFocusY
                            )
                        }
                    }
                } else {
                    Log.e(TAG, "setImageUri: loadImageAsync returned null for URI: $uri")
                    Toast.makeText(context, "图片加载失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "setImageUri: Exception during image loading or processing for URI: $uri",
                    e
                )
                Toast.makeText(context, "图片加载异常: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                if (isActive) invalidate() // 确保在协程结束时（如果还在活动状态）重绘一次
                Log.d(TAG, "setImageUri: Coroutine finished for URI: $uri")
            }
        }
    }

    private suspend fun loadImageAsync(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        Log.i(TAG, "loadImageAsync: Attempting to load URI: $uri")
        var inputStream: java.io.InputStream? = null
        try {
            inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.e(
                    TAG,
                    "loadImageAsync: contentResolver.openInputStream(uri) returned NULL for $uri"
                )
                return@withContext null
            }
            Log.d(TAG, "loadImageAsync: Successfully opened InputStream for $uri")

            // 第一次解码获取尺寸
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // 需要重新打开流或使用 mark/reset，简单起见这里重新打开
            context.contentResolver.openInputStream(uri)?.use { sizeCheckStream ->
                BitmapFactory.decodeStream(sizeCheckStream, null, options)
            }

            if (options.outWidth <= 0 || options.outHeight <= 0) {
                Log.e(
                    TAG,
                    "loadImageAsync: Bitmap dimensions are invalid after size check. Width: ${options.outWidth}, Height: ${options.outHeight}"
                )
                return@withContext null
            }

            // 计算采样率
            var inSampleSize = 1
            // 目标：让解码后的图片尺寸不要过大，例如最长边不超过2048px，或短边不小于预览框估算尺寸
            val estimatedPreviewBoxShortSide =
                min(width.toFloat() * 0.6f, height.toFloat() * 0.6f) / 2f // 非常粗略的估算
            val reqWidth =
                if (estimatedPreviewBoxShortSide > 0) estimatedPreviewBoxShortSide.toInt() * 2 else 1080
            val reqHeight =
                if (estimatedPreviewBoxShortSide > 0) estimatedPreviewBoxShortSide.toInt() * 2 else 1920


            if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
                val halfHeight: Int = options.outHeight / 2
                val halfWidth: Int = options.outWidth / 2
                while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2
                    if (inSampleSize > 8) break // 避免采样过度
                }
            }
            options.inSampleSize = inSampleSize
            options.inJustDecodeBounds = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            Log.d(
                TAG,
                "loadImageAsync: Calculated inSampleSize: $inSampleSize for original ${options.outWidth}x${options.outHeight}"
            )


            // 再次打开流进行实际解码
            val bitmap = context.contentResolver.openInputStream(uri)?.use { finalInputStream ->
                BitmapFactory.decodeStream(finalInputStream, null, options)
            }

            if (bitmap != null) {
                Log.i(
                    TAG,
                    "loadImageAsync: Bitmap decoded successfully from $uri, new size: ${bitmap.width}x${bitmap.height}"
                )
            } else {
                Log.e(
                    TAG,
                    "loadImageAsync: BitmapFactory.decodeStream returned null for $uri with inSampleSize=$inSampleSize"
                )
            }
            return@withContext bitmap
        } catch (e: SecurityException) {
            Log.e(
                TAG,
                "loadImageAsync: SecurityException for URI: $uri. READ PERMISSION DENIED.",
                e
            )
            launch(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    "无法访问图片，请检查权限",
                    Toast.LENGTH_LONG
                ).show()
            }
            return@withContext null
        } catch (e: FileNotFoundException) {
            Log.e(TAG, "loadImageAsync: FileNotFoundException for URI: $uri. File not found.", e)
            launch(Dispatchers.Main) {
                Toast.makeText(context, "图片文件未找到", Toast.LENGTH_LONG).show()
            }
            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "loadImageAsync: Generic Exception for URI: $uri", e)
            launch(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    "加载图片时发生未知错误",
                    Toast.LENGTH_LONG
                ).show()
            }
            return@withContext null
        } finally {
            try {
                inputStream?.close() // 虽然 use 会自动关，但明确一下无妨
            } catch (e: IOException) {
                Log.e(TAG, "loadImageAsync: Error closing input stream", e)
            }
        }
    }

    private fun calculateBaseFillScale(): Float {
        sourceBitmap ?: return 1.0f
        val previewWidth = p1PreviewRectView.width()
        val previewHeight = p1PreviewRectView.height()

        if (previewWidth <= 0 || previewHeight <= 0 || sourceBitmap!!.width <= 0 || sourceBitmap!!.height <= 0) {
            return 1.0f
        }
        val scaleX = previewWidth / sourceBitmap!!.width.toFloat()
        val scaleY = previewHeight / sourceBitmap!!.height.toFloat()
        return max(scaleX, scaleY) // 取较大值以确保填满预览框 (CENTER_CROP 行为)
    }

    fun resetImageToFocusPoint(normFocusX: Float, normFocusY: Float) {
        sourceBitmap ?: return
        if (width <= 0 || height <= 0 || p1PreviewRectView.isEmpty) {
            Log.w(TAG, "resetImageToFocusPoint: View or preview box not ready. Deferring.")
            // 可以post一个任务，或者依赖onSizeChanged之后的调用
            post {
                if (width > 0 && height > 0 && !p1PreviewRectView.isEmpty) resetImageToFocusPoint(
                    normFocusX,
                    normFocusY
                )
            }
            return
        }
        Log.d(TAG, "resetImageToFocusPoint: Resetting to focus ($normFocusX, $normFocusY)")

        val baseScale = calculateBaseFillScale()
        val initialActualScale = baseScale * minUserScaleFactor // minUserScaleFactor 通常是 1.0f
        imageMatrix.setScale(initialActualScale, initialActualScale)

        val focusBitmapX = normFocusX * sourceBitmap!!.width
        val focusBitmapY = normFocusY * sourceBitmap!!.height

        val scaledFocusBitmapX = focusBitmapX * initialActualScale
        val scaledFocusBitmapY = focusBitmapY * initialActualScale

        val translateX = p1PreviewRectView.centerX() - scaledFocusBitmapX
        val translateY = p1PreviewRectView.centerY() - scaledFocusBitmapY

        imageMatrix.postTranslate(translateX, translateY)
        applyMatrixBounds()
        invalidate()
        Log.d(
            TAG,
            "resetImageToFocusPoint: Matrix reset. Scale: $initialActualScale, Translate: ($translateX, $translateY)"
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        Log.d(TAG, "onSizeChanged: New size $w x $h")
        if (w > 0 && h > 0) {
            // 计算 P1 预览框在 View 中的位置和大小
            // 目标：预览框在View中大致居中，宽度占80-90%，高度根据传入的宽高比计算
            val targetBoxWidth: Float
            val targetBoxHeight: Float

            if (p1PreviewAspectRatio <= 0f) p1PreviewAspectRatio = 16f / 9f // 安全检查

            // 假设我们希望预览框宽度占View宽度的85%
            targetBoxWidth = w * 0.85f
            targetBoxHeight = targetBoxWidth / p1PreviewAspectRatio

            // 如果计算出的高度超出View高度的85%，则反过来以高度为基准
            if (targetBoxHeight > h * 0.85f) {
                val newTargetBoxHeight = h * 0.85f
                val newTargetBoxWidth = newTargetBoxHeight * p1PreviewAspectRatio
                p1PreviewRectView.set(
                    (w - newTargetBoxWidth) / 2f,
                    (h - newTargetBoxHeight) / 2f,
                    (w + newTargetBoxWidth) / 2f,
                    (h + newTargetBoxHeight) / 2f
                )
            } else {
                p1PreviewRectView.set(
                    (w - targetBoxWidth) / 2f,
                    (h - targetBoxHeight) / 2f,
                    (w + targetBoxWidth) / 2f,
                    (h + targetBoxHeight) / 2f
                )
            }
            Log.d(TAG, "onSizeChanged: p1PreviewRectView set to: $p1PreviewRectView")

            if (sourceBitmap != null) {
                val currentFocus = getNormalizedFocusPointInternal() // 获取当前的焦点（可能是默认的或已设置的）
                resetImageToFocusPoint(currentFocus.x, currentFocus.y)
            }
        }
    }

    private fun getCurrentGlobalScale(): Float {
        val values = FloatArray(9)
        imageMatrix.getValues(values)
        return values[Matrix.MSCALE_X] // 假设均匀缩放
    }

    private fun applyMatrixBounds() {
        sourceBitmap ?: return
        if (p1PreviewRectView.isEmpty) return

        val currentGlobalScale = getCurrentGlobalScale()
        if (currentGlobalScale <= 0f) { // 无效缩放，可能需要重置
            Log.w(
                TAG,
                "applyMatrixBounds: Invalid currentGlobalScale: $currentGlobalScale. Resetting image state might be needed."
            )
            // resetImageToFocusPoint(0.5f,0.5f) // 考虑是否需要强制重置
            return
        }

        val values = FloatArray(9)
        imageMatrix.getValues(values)
        var currentTransX = values[Matrix.MTRANS_X]
        var currentTransY = values[Matrix.MTRANS_Y]

        val scaledBitmapWidth = sourceBitmap!!.width * currentGlobalScale
        val scaledBitmapHeight = sourceBitmap!!.height * currentGlobalScale

        var dx = 0f
        var dy = 0f

        // 确保预览框在图片内容之内，或者如果图片更小，则图片在预览框内居中
        val previewWidth = p1PreviewRectView.width()
        val previewHeight = p1PreviewRectView.height()

        // 水平方向
        if (scaledBitmapWidth < previewWidth) {
            // 图片比预览框窄，水平居中图片在预览框内
            dx = p1PreviewRectView.left + (previewWidth - scaledBitmapWidth) / 2f - currentTransX
        } else {
            // 图片比预览框宽，确保预览框的左右边缘都在图片内
            if (currentTransX > p1PreviewRectView.left) { // 图片的左边缘跑到了预览框左边缘的右边
                dx = p1PreviewRectView.left - currentTransX
            } else if (currentTransX + scaledBitmapWidth < p1PreviewRectView.right) { // 图片的右边缘跑到了预览框右边缘的左边
                dx = p1PreviewRectView.right - (currentTransX + scaledBitmapWidth)
            }
        }

        // 垂直方向
        if (scaledBitmapHeight < previewHeight) {
            // 图片比预览框窄，垂直居中图片在预览框内
            dy = p1PreviewRectView.top + (previewHeight - scaledBitmapHeight) / 2f - currentTransY
        } else {
            // 图片比预览框高，确保预览框的上下边缘都在图片内
            if (currentTransY > p1PreviewRectView.top) {
                dy = p1PreviewRectView.top - currentTransY
            } else if (currentTransY + scaledBitmapHeight < p1PreviewRectView.bottom) {
                dy = p1PreviewRectView.bottom - (currentTransY + scaledBitmapHeight)
            }
        }

        if (dx != 0f || dy != 0f) {
            imageMatrix.postTranslate(dx, dy)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        sourceBitmap ?: return
        if (p1PreviewRectView.isEmpty) return

        // 1. 绘制缩放和平移后的背景大图
        canvas.withMatrix(imageMatrix) {
            drawBitmap(sourceBitmap!!, 0f, 0f, bitmapPaint)
        }

        // 2. 绘制P1预览框外部的半透明遮罩
        // 使用 Path.op(Path.Op.DIFFERENCE) 或更兼容的方式
        val path = Path()
        path.fillType = Path.FillType.EVEN_ODD // 或者 .INVERSE_WINDING
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        path.addRect(p1PreviewRectView, Path.Direction.CW) // 之前是CCW，用EVEN_ODD时，两个同向矩形相减
        canvas.drawPath(path, overlayPaint)


        // 3. 绘制P1预览框的边框
        canvas.drawRect(p1PreviewRectView, previewBoxBorderPaint)

        // 4. (可选) 绘制网格线
        drawGridLines(canvas, p1PreviewRectView)
    }

    private fun drawGridLines(canvas: Canvas, rect: RectF) {
        val thirdWidth = rect.width() / 3f
        val thirdHeight = rect.height() / 3f

        // 垂直线
        canvas.drawLine(
            rect.left + thirdWidth,
            rect.top,
            rect.left + thirdWidth,
            rect.bottom,
            previewBoxGridPaint
        )
        canvas.drawLine(
            rect.left + thirdWidth * 2,
            rect.top,
            rect.left + thirdWidth * 2,
            rect.bottom,
            previewBoxGridPaint
        )
        // 水平线
        canvas.drawLine(
            rect.left,
            rect.top + thirdHeight,
            rect.right,
            rect.top + thirdHeight,
            previewBoxGridPaint
        )
        canvas.drawLine(
            rect.left,
            rect.top + thirdHeight * 2,
            rect.right,
            rect.top + thirdHeight * 2,
            previewBoxGridPaint
        )
    }


    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (sourceBitmap == null) return super.onTouchEvent(event) // 没有图片不处理触摸

        var handledByScale = scaleGestureDetector.onTouchEvent(event)
        var handledByGesture = gestureDetector.onTouchEvent(event)

        // 如果任何一个手势检测器处理了事件，就认为我们处理了
        // 并且通常会请求父View不要拦截事件
        if (handledByScale || handledByGesture) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }

        // 为了确保performClick和可访问性事件仍然可以触发，
        // 如果我们的手势检测器没有完全消耗事件（例如，只是onDown），
        // 调用super.onTouchEvent可能是必要的。
        // 一个更简单的模式是，如果我们确定处理了就返回true，否则返回super。
        // 但 GestureDetector 和 ScaleGestureDetector 的返回值语义有时比较复杂。
        // 通常，只要我们想自己处理，就返回true。
        return handledByScale || handledByGesture || super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        // 如果视图是可点击的，并且没有被手势消耗，会调用这个
        // 我们这个视图主要靠手势，performClick可能用处不大
        Log.d(TAG, "performClick called")
        return super.performClick()
    }

    private fun getNormalizedFocusPointInternal(): PointF {
        sourceBitmap ?: return PointF(0.5f, 0.5f)
        if (p1PreviewRectView.isEmpty || sourceBitmap!!.width <= 0 || sourceBitmap!!.height <= 0) {
            return PointF(0.5f, 0.5f)
        }

        val previewCenterXInView = p1PreviewRectView.centerX()
        val previewCenterYInView = p1PreviewRectView.centerY()
        val viewPoint = floatArrayOf(previewCenterXInView, previewCenterYInView)

        val invertedMatrix = Matrix()
        if (!imageMatrix.invert(invertedMatrix)) {
            Log.e(TAG, "Matrix non-invertible in getNormalizedFocusPointInternal")
            return PointF(0.5f, 0.5f)
        }
        invertedMatrix.mapPoints(viewPoint)

        val focusXInBitmap = viewPoint[0]
        val focusYInBitmap = viewPoint[1]

        val normalizedX = (focusXInBitmap / sourceBitmap!!.width.toFloat()).coerceIn(0f, 1f)
        val normalizedY = (focusYInBitmap / sourceBitmap!!.height.toFloat()).coerceIn(0f, 1f)

        return PointF(normalizedX, normalizedY)
    }

    fun getNormalizedFocusPoint(): PointF {
        return getNormalizedFocusPointInternal()
    }
}