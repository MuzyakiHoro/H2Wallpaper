package com.example.h2wallpaper

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import com.example.h2wallpaper.WallpaperConfigConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

/**
 * MainActivity 和 BottomSheetScreen 的 ViewModel。
 * 负责管理壁纸配置的 UI 状态，处理用户交互，
 * 与 WallpaperPreferencesRepository 交互以持久化配置，
 * 并执行图片加载、颜色提取等异步任务。
 *
 * @param application Application 实例，用于访问应用上下文。
 */
open class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesRepository: WallpaperPreferencesRepository =
        WallpaperPreferencesRepository(application)

    // --- LiveData for UI State (主配置值) ---

    /** 用户选择的图片 URI。如果为 null，表示未选择图片。 */
    private val _selectedImageUri = MutableLiveData<Uri?>()
    open val selectedImageUri: LiveData<Uri?> get() = _selectedImageUri

    /** 用户选择的背景颜色。 */
    private val _selectedBackgroundColor = MutableLiveData<Int>()
    open val selectedBackgroundColor: LiveData<Int> get() = _selectedBackgroundColor

    /** P1 层图片的高度与屏幕高度的比例。 */
    private val _page1ImageHeightRatio = MutableLiveData<Float>()
    open val page1ImageHeightRatio: LiveData<Float> get() = _page1ImageHeightRatio

    /** P1 层图片的归一化焦点 X 坐标 (0.0 - 1.0)。 */
    private val _p1FocusX = MutableLiveData<Float>()
    val p1FocusX: LiveData<Float> get() = _p1FocusX

    /** P1 层图片的归一化焦点 Y 坐标 (0.0 - 1.0)。 */
    private val _p1FocusY = MutableLiveData<Float>()
    val p1FocusY: LiveData<Float> get() = _p1FocusY

    /** P1 层图片内容的缩放因子。 */
    private val _p1ContentScaleFactor = MutableLiveData<Float>()
    val p1ContentScaleFactor: LiveData<Float> get() = _p1ContentScaleFactor

    /** 用于向 UI 显示一次性消息 (如 Toast) 的事件。 */
    private val _toastMessage = MutableLiveData<Event<String>>()
    val toastMessage: LiveData<Event<String>> get() = _toastMessage

    /** 指示当前是否正在进行耗时操作 (如图片加载)。 */
    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> get() = _isLoading

    /** 从选定图片中提取的调色板颜色列表。 */
    private val _colorPalette = MutableLiveData<List<Int>>()
    open val colorPalette: LiveData<List<Int>> get() = _colorPalette

    /** 用于颜色提取的原始 Bitmap 对象，会在新图片加载时被替换和回收。 */
    private var originalBitmapForColorExtraction: Bitmap? = null

    // --- P1编辑模式状态 ---
    /** 指示当前是否处于 P1 图片编辑模式。 */
    private val _isP1EditMode = MutableLiveData<Boolean>(false)
    open val isP1EditMode: LiveData<Boolean> get() = _isP1EditMode

    // --- BottomSheet 配置状态 ---
    /** 控制底部配置表单 (BottomSheet) 是否显示。 */
    protected val _showConfigSheet = MutableStateFlow(false)
    open val showConfigSheet: StateFlow<Boolean> get() = _showConfigSheet

    /** 底部配置表单中当前选中的主分类 ID。 */
    private val _selectedMainCategoryIdInSheet = MutableStateFlow(mainCategoriesData.firstOrNull()?.id)
    val selectedMainCategoryIdInSheet: StateFlow<String?> = _selectedMainCategoryIdInSheet

    /** 底部配置表单中当前选中的、用于参数调整的子分类 ID。 */
    private val _subCategoryForAdjustmentIdInSheet = MutableStateFlow<String?>(null)
    val subCategoryForAdjustmentIdInSheet: StateFlow<String?> = _subCategoryForAdjustmentIdInSheet

    // --- 高级设置参数的 LiveData (存储实际的业务逻辑值，通常是 Float 或 Int) ---
    /** 壁纸滚动灵敏度。 */
    private val _scrollSensitivity = MutableLiveData<Float>()
    open val scrollSensitivity: LiveData<Float> get() = _scrollSensitivity

    /** P1 层前景在滚动时的淡出过渡比例。 */
    private val _p1OverlayFadeRatio = MutableLiveData<Float>()
    open val p1OverlayFadeRatio: LiveData<Float> get() = _p1OverlayFadeRatio

    /** P2 层背景在滚动时的淡入过渡比例。 */
    private val _p2BackgroundFadeInRatio = MutableLiveData<Float>()
    val p2BackgroundFadeInRatio: LiveData<Float> get() = _p2BackgroundFadeInRatio

    /** P2 层背景在第一页的初始归一化横向偏移量。 */
    private val _backgroundInitialOffset = MutableLiveData<Float>()
    val backgroundInitialOffset: LiveData<Float> get() = _backgroundInitialOffset

    /** P2 层背景的模糊半径。 */
    private val _backgroundBlurRadius = MutableLiveData<Float>()
    val backgroundBlurRadius: LiveData<Float> get() = _backgroundBlurRadius

    /** P2 层背景模糊处理时的降采样因子。 */
    private val _blurDownscaleFactor = MutableLiveData<Float>() // ViewModel 中保持 Float
    val blurDownscaleFactor: LiveData<Float> get() = _blurDownscaleFactor

    /** P2 层背景模糊处理的迭代次数。 */
    private val _blurIterations = MutableLiveData<Int>()
    val blurIterations: LiveData<Int> get() = _blurIterations

    /** P1 层图片的投影半径。 */
    private val _p1ShadowRadius = MutableLiveData<Float>()
    val p1ShadowRadius: LiveData<Float> get() = _p1ShadowRadius

    /** P1 层图片的投影在 X 轴上的偏移量。 */
    private val _p1ShadowDx = MutableLiveData<Float>()
    val p1ShadowDx: LiveData<Float> get() = _p1ShadowDx

    /** P1 层图片的投影在 Y 轴上的偏移量。 */
    private val _p1ShadowDy = MutableLiveData<Float>()
    val p1ShadowDy: LiveData<Float> get() = _p1ShadowDy

    /** P1 层图片底部融入效果的高度。 */
    private val _p1ImageBottomFadeHeight = MutableLiveData<Float>()
    val p1ImageBottomFadeHeight: LiveData<Float> get() = _p1ImageBottomFadeHeight

    /** 控制底部配置表单中自定义背景颜色 RGB 滑块区域是否显示。 */
    private val _showCustomColorSliders = MutableStateFlow(false)
    open val showCustomColorSliders: StateFlow<Boolean> get() = _showCustomColorSliders

    /**
     * 切换底部配置表单中自定义颜色滑块区域的显示状态。
     * 如果显示颜色滑块，则隐藏参数调整滑块区域，确保两者互斥。
     */
    open fun toggleCustomColorSlidersVisibility() {
        val newVisibility = !_showCustomColorSliders.value
        _showCustomColorSliders.value = newVisibility
        if (newVisibility) {
            // 如果显示颜色滑块，则隐藏参数调整滑块
            _subCategoryForAdjustmentIdInSheet.value = null
        }
    }

    /** 用于参数更新节流的时间记录，键为参数 Key，值为上次更新时间戳。 */
    private val paramUpdateTimes = mutableMapOf<String, Long>()
    /** 参数更新的节流时间间隔 (毫秒)，避免过于频繁地写入 SharedPreferences。 */
    private val throttleInterval = 20f // 改为20ms，对应50fps

    /**
     * ViewModel 初始化块。
     * 在 ViewModel 创建时，会调用 loadInitialPreferences() 加载初始配置。
     */
    init {
        loadInitialPreferences()
    }

    /**
     * 从 WallpaperPreferencesRepository 加载所有存储的配置项到 ViewModel 的 LiveData/StateFlow 中。
     * 同时，如果存在已选图片，会尝试从图片中提取调色板。
     */
    private fun loadInitialPreferences() {
        _selectedImageUri.value = preferencesRepository.getSelectedImageUri() //
        _selectedBackgroundColor.value = preferencesRepository.getSelectedBackgroundColor() //
        _page1ImageHeightRatio.value = preferencesRepository.getPage1ImageHeightRatio() //
        _p1FocusX.value = preferencesRepository.getP1FocusX() //
        _p1FocusY.value = preferencesRepository.getP1FocusY() //
        _p1ContentScaleFactor.value = preferencesRepository.getP1ContentScaleFactor() //

        // 直接从 Repository 获取转换好的 Float/Int 值
        _scrollSensitivity.value = preferencesRepository.getScrollSensitivity() //
        _p1OverlayFadeRatio.value = preferencesRepository.getP1OverlayFadeRatio() //
        _p2BackgroundFadeInRatio.value = preferencesRepository.getP2BackgroundFadeInRatio() //
        _backgroundInitialOffset.value = preferencesRepository.getBackgroundInitialOffset() //
        _backgroundBlurRadius.value = preferencesRepository.getBackgroundBlurRadius() //
        _blurDownscaleFactor.value = preferencesRepository.getBlurDownscaleFactor() //
        _blurIterations.value = preferencesRepository.getBlurIterations() //
        _p1ShadowRadius.value = preferencesRepository.getP1ShadowRadius() //
        _p1ShadowDx.value = preferencesRepository.getP1ShadowDx() //
        _p1ShadowDy.value = preferencesRepository.getP1ShadowDy() //
        _p1ImageBottomFadeHeight.value = preferencesRepository.getP1ImageBottomFadeHeight() //

        viewModelScope.launch {
            val currentUri = _selectedImageUri.value
            if (currentUri != null) {
                try {
                    extractColorsFromUri(currentUri, isNewImage = false) //
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Error extracting colors on init: ${e.message}")
                    launch(Dispatchers.Main) { setDefaultColorPalette() } //
                }
            } else {
                launch(Dispatchers.Main) { setDefaultColorPalette() } //
            }
        }
    }

    /**
     * 切换底部配置表单 (BottomSheet) 的显示/隐藏状态。
     */
    open fun toggleConfigSheetVisibility() {
        _showConfigSheet.update { currentState -> !currentState }
    }

    /**
     * 打开底部配置表单 (BottomSheet)。
     */
    open fun openConfigSheet() {
        _showConfigSheet.value = true
    }

    /**
     * 关闭底部配置表单 (BottomSheet)。
     */
    open fun closeConfigSheet() {
        _showConfigSheet.value = false
    }

    /**
     * 切换 P1 图片编辑模式的激活状态。
     * 进入编辑模式时，会清除子分类调整目标。
     */
    open fun toggleP1EditMode() {
        val currentlyEditing = _isP1EditMode.value ?: false
        val newEditMode = !currentlyEditing
        _isP1EditMode.value = newEditMode

        if (newEditMode) {
            _subCategoryForAdjustmentIdInSheet.value = null // 进入编辑模式时，清除参数调整目标
            Log.d("MainViewModel", "Entering P1 Edit Mode.")
        } else {
            Log.d("MainViewModel", "Exiting P1 Edit Mode. Changes were saved real-time.")
            // 退出编辑模式时，之前通过 updateP1ConfigRealtime 实时保存的已经是最终值
        }
    }

    /**
     * 当 P1 图片在编辑模式下发生变化时（通过 WallpaperPreviewView 的手势回调），
     * 实时更新 ViewModel 中的 P1 相关配置（焦点、高度、内容缩放），并持久化这些更改。
     *
     * @param normX 新的归一化焦点 X 坐标。
     * @param normY 新的归一化焦点 Y 坐标。
     * @param heightRatio 新的 P1 图片高度比例。
     * @param contentScale 新的 P1 图片内容缩放因子。
     */
    fun updateP1ConfigRealtime(normX: Float, normY: Float, heightRatio: Float, contentScale: Float) {
        var configActuallyChanged = false // 标记是否有配置实际发生改变

        // 更新焦点 X
        val newNormX = normX.coerceIn(0f, 1f)
        if (abs((_p1FocusX.value ?: WallpaperConfigConstants.DEFAULT_P1_FOCUS_X) - newNormX) > 0.0001f) {
            _p1FocusX.value = newNormX
            preferencesRepository.setP1FocusX(newNormX) //
            configActuallyChanged = true
        }

        // 更新焦点 Y
        val newNormY = normY.coerceIn(0f, 1f)
        if (abs((_p1FocusY.value ?: WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y) - newNormY) > 0.0001f) {
            _p1FocusY.value = newNormY
            preferencesRepository.setP1FocusY(newNormY) //
            configActuallyChanged = true
        }

        // 更新高度比例
        val newHeightRatio = heightRatio.coerceIn(
            WallpaperConfigConstants.MIN_HEIGHT_RATIO, //
            WallpaperConfigConstants.MAX_HEIGHT_RATIO //
        )
        if (abs((_page1ImageHeightRatio.value ?: WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO) - newHeightRatio) > 0.0001f) {
            _page1ImageHeightRatio.value = newHeightRatio
            preferencesRepository.setPage1ImageHeightRatio(newHeightRatio) //
            configActuallyChanged = true
        }

        // 更新内容缩放因子
        val newContentScale = contentScale.coerceIn(
            WallpaperConfigConstants.DEFAULT_P1_CONTENT_SCALE_FACTOR, //
            4.0f // 假设P1内容的最大缩放因子为4.0f (可以考虑定义为常量)
        )
        if (abs((_p1ContentScaleFactor.value ?: WallpaperConfigConstants.DEFAULT_P1_CONTENT_SCALE_FACTOR) - newContentScale) > 0.0001f) {
            _p1ContentScaleFactor.value = newContentScale
            preferencesRepository.setP1ContentScaleFactor(newContentScale) //
            configActuallyChanged = true
        }

        if (configActuallyChanged) {
            Log.d("MainViewModel", "P1 Config REALTIME SAVED: F=(${_p1FocusX.value},${_p1FocusY.value}), H=${_page1ImageHeightRatio.value}, S=${_p1ContentScaleFactor.value}")
            preferencesRepository.updateImageContentVersion() // // 更新版本号，通知壁纸服务刷新
        }
    }

    /**
     * （旧方法，主要用于非 P1 编辑模式）更新 P1 图片的高度比例。
     * 如果当前处于 P1 编辑模式，此调用将被忽略，因为高度由手势控制。
     * @param newRatio 新的高度比例。
     */
    fun updatePage1ImageHeightRatio(newRatio: Float) {
        if (_isP1EditMode.value == true) {
            Log.i("MainViewModel", "In P1 Edit Mode, height changes via gesture. Ignoring legacy call.")
            return
        }
        val clampedRatio = newRatio.coerceIn(WallpaperConfigConstants.MIN_HEIGHT_RATIO, WallpaperConfigConstants.MAX_HEIGHT_RATIO) //
        if (_page1ImageHeightRatio.value != clampedRatio) {
            _page1ImageHeightRatio.value = clampedRatio
            preferencesRepository.setPage1ImageHeightRatio(clampedRatio) //
            preferencesRepository.updateImageContentVersion() //
        }
    }

    /**
     * （旧方法，主要用于非 P1 编辑模式）更新 P1 图片的归一化焦点位置。
     * 如果当前处于 P1 编辑模式，此调用将被忽略，因为焦点由手势控制。
     * @param focusX 新的归一化焦点 X 坐标。
     * @param focusY 新的归一化焦点 Y 坐标。
     */
    fun updateP1Focus(focusX: Float, focusY: Float) {
        if (_isP1EditMode.value == true) {
            Log.i("MainViewModel", "In P1 Edit Mode, focus changes via gesture. Ignoring legacy call.")
            return
        }
        val clampedX = focusX.coerceIn(0f, 1f)
        val clampedY = focusY.coerceIn(0f, 1f)
        if (_p1FocusX.value != clampedX || _p1FocusY.value != clampedY) {
            _p1FocusX.value = clampedX
            _p1FocusY.value = clampedY
            preferencesRepository.setP1Focus(clampedX, clampedY) //
            preferencesRepository.updateImageContentVersion() //
        }
    }

    /**
     * 更新选定的背景颜色，并持久化。
     * 包含节流逻辑，以避免过于频繁地写入 SharedPreferences。
     * @param color 新的背景颜色值。
     */
    open fun updateSelectedBackgroundColor(color: Int) {
        val currentTime = System.currentTimeMillis()
        val lastUpdateTime = paramUpdateTimes["bg_color"] ?: 0L
        if (currentTime - lastUpdateTime < throttleInterval) {
            return  // 如果更新间隔太短，则跳过本次更新以节流
        }
        paramUpdateTimes["bg_color"] = currentTime // 记录本次更新时间

        if (_selectedBackgroundColor.value != color) {
            _selectedBackgroundColor.value = color
            preferencesRepository.setSelectedBackgroundColor(color) //
            preferencesRepository.updateImageContentVersion() //
        }
    }

    /**
     * 处理从图片选择器返回的结果。
     * 如果成功选择了图片，会将其复制到应用内部存储，然后更新 ViewModel 状态，
     * 重置与新图片相关的配置项，并尝试从新图片中提取调色板。
     * @param uri 从图片选择器返回的图片 Uri，可能为 null。
     */
    fun handleImageSelectionResult(uri: Uri?) {
        if (_isP1EditMode.value == true) { // 如果在P1编辑模式下选了新图，则退出编辑模式
            _isP1EditMode.value = false
        }
        if (uri != null) {
            _isLoading.value = true // 开始加载，显示进度条
            viewModelScope.launch {
                // 将图片复制到应用内部存储，返回内部存储的 Uri
                val internalFileUri = saveImageToInternalAppStorage(uri) //
                withContext(Dispatchers.Main) { _isLoading.value = false } // 加载完成，隐藏进度条

                if (internalFileUri != null) {
                    val oldUri = _selectedImageUri.value
                    // 如果之前有选中的图片且与新图片不同，则删除旧的内部存储图片
                    if (oldUri != null && oldUri != internalFileUri) {
                        deleteInternalImage(oldUri) //
                    }
                    _selectedImageUri.postValue(internalFileUri) // 更新当前选中的图片 Uri

                    // 为新图片重置 P1 焦点、高度和内容缩放为默认值
                    val defFocusX = WallpaperConfigConstants.DEFAULT_P1_FOCUS_X //
                    val defFocusY = WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y //
                    val defHeightRatio = WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO //
                    val defContentScale = WallpaperConfigConstants.DEFAULT_P1_CONTENT_SCALE_FACTOR //

                    _p1FocusX.postValue(defFocusX)
                    _p1FocusY.postValue(defFocusY)
                    _page1ImageHeightRatio.postValue(defHeightRatio)
                    _p1ContentScaleFactor.postValue(defContentScale)

                    // 通过 Repository 重置 SharedPreferences 中的相关配置
                    preferencesRepository.resetSettingsForNewImage(internalFileUri) //
                    // 确保这些默认值也通过 repository 单独设置，以防 resetSettingsForNewImage 未覆盖所有
                    preferencesRepository.setPage1ImageHeightRatio(defHeightRatio) //
                    preferencesRepository.setP1ContentScaleFactor(defContentScale) //


                    try {
                        // 从新图片中提取调色板
                        extractColorsFromUri(internalFileUri, isNewImage = true) //
                    } catch (e: Exception) {
                        _toastMessage.postValue(Event("颜色提取失败: ${e.message}")) //
                        launch(Dispatchers.Main) { setDefaultColorPalette() } // // 提取失败则设置默认调色板
                    }
                } else {
                    _toastMessage.postValue(Event(getApplication<Application>().getString(R.string.image_load_failed_toast) + " (复制失败)")) //
                }
            }
        } else {
            _toastMessage.postValue(Event(getApplication<Application>().getString(R.string.image_selection_failed_toast))) //
        }
    }

    /**
     * 将用户从外部选择的图片（通过其 Uri 访问）复制到应用的内部存储空间。
     * 使用 FileProvider 生成内部文件的 Uri。
     * @param sourceUri 用户选择的原始图片的 Uri。
     * @return 复制成功后，返回图片在应用内部存储的 Uri；如果失败则返回 null。
     */
    private suspend fun saveImageToInternalAppStorage(sourceUri: Uri): Uri? = withContext(Dispatchers.IO) {
        val context = getApplication<Application>().applicationContext
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                val imageDir = File(context.filesDir, "wallpaper_images") // 定义内部存储的子目录
                if (!imageDir.exists() && !imageDir.mkdirs()) { // 如果目录不存在则创建
                    Log.e("MainViewModel", "Failed to create directory: ${imageDir.absolutePath}")
                    return@withContext null
                }
                // 定义内部存储的文件名 (固定文件名，新图片会覆盖旧图片)
                val internalFile = File(imageDir, "h2_wallpaper_internal_image.jpg")
                // if (internalFile.exists()) internalFile.delete() // 可选：如果需要确保总是创建新文件而不是覆盖

                FileOutputStream(internalFile).use { outputStream -> // 将输入流复制到输出流 (即文件)
                    inputStream.copyTo(outputStream)
                }
                // 使用 FileProvider 为内部文件生成 content URI，以便其他组件 (如 WallpaperService) 安全访问
                return@withContext FileProvider.getUriForFile(context, "${context.packageName}.provider", internalFile) //
            } ?: Log.e("MainViewModel", "ContentResolver failed to open InputStream for $sourceUri")
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error saving image to internal storage for $sourceUri", e)
        }
        return@withContext null // 发生错误或输入流为空时返回 null
    }

    /**
     * 删除存储在应用内部的旧壁纸图片。
     * 通常在用户选择新图片并成功保存后调用，以清理不再使用的旧图片。
     * @param internalFileUri 要删除的内部图片的 Uri。
     */
    private suspend fun deleteInternalImage(internalFileUri: Uri) = withContext(Dispatchers.IO) {
        val context = getApplication<Application>().applicationContext
        // 注意：直接从 Uri 获取 File 对象可能不总是可靠，特别是对于 FileProvider 的 Uri。
        // 更稳健的做法是基于已知的文件路径来删除。
        // 假设文件名总是 "h2_wallpaper_internal_image.jpg" 在 "wallpaper_images" 目录下。
        val imageDir = File(context.filesDir, "wallpaper_images")
        val internalFile = File(imageDir, "h2_wallpaper_internal_image.jpg")
        if (internalFile.exists()) {
            if (internalFile.delete()) {
                Log.i("MainViewModel", "Deleted old internal image: ${internalFile.path}")
            } else {
                Log.w("MainViewModel", "Failed to delete old internal image: ${internalFile.path}")
            }
        }
    }


    /**
     * 从给定的图片 Uri 中异步提取调色板颜色。
     * 提取成功后，会更新 [_colorPalette] LiveData。
     * 如果是新选择的图片，或者当前背景色无效/未在提取颜色中，会尝试自动设置一个新的背景色。
     * @param uri 图片的 Uri。
     * @param isNewImage 指示这张图片是否是用户新选择的。
     */
    private suspend fun extractColorsFromUri(uri: Uri, isNewImage: Boolean) = withContext(Dispatchers.IO) {
        val context = getApplication<Application>().applicationContext
        var bitmapForPalette: Bitmap? = null
        try {
            // 第一次打开输入流，仅解码图片边界以获取尺寸，用于计算采样率
            context.contentResolver.openInputStream(uri)?.use { inputStreamBounds ->
                val optionsBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(inputStreamBounds, null, optionsBounds)
                if (optionsBounds.outWidth <= 0 || optionsBounds.outHeight <= 0) {
                    throw IOException("Bitmap bounds invalid for $uri")
                }

                // 计算合适的采样率以加载用于调色板提取的、尺寸较小的位图
                val options = BitmapFactory.Options().apply {
                    inSampleSize = calculateInSampleSizeForPalette(optionsBounds, 256) // //目标尺寸256x256
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                // 第二次打开输入流，实际解码位图
                context.contentResolver.openInputStream(uri)?.use { inputStreamBitmap ->
                    originalBitmapForColorExtraction?.recycle() // 回收旧的位图
                    originalBitmapForColorExtraction = BitmapFactory.decodeStream(inputStreamBitmap, null, options)
                    bitmapForPalette = originalBitmapForColorExtraction
                } ?: throw IOException("Failed to reopen input stream for bitmap for $uri")
            } ?: throw IOException("Failed to open input stream for bounds for $uri")

            bitmapForPalette?.let { bitmap ->
                // 使用 Palette 库从位图生成调色板
                Palette.from(bitmap).generate { palette ->
                    // 提取多种类型的色样 (dominant, vibrant, muted 等)
                    val swatches = listOfNotNull(
                        palette?.dominantSwatch, palette?.vibrantSwatch, palette?.mutedSwatch,
                        palette?.lightVibrantSwatch, palette?.darkVibrantSwatch,
                        palette?.lightMutedSwatch, palette?.darkMutedSwatch
                    ).distinctBy { it.rgb }.take(8) // 去重并取前8个
                    val extractedColors = swatches.map { it.rgb }
                    _colorPalette.postValue(extractedColors.ifEmpty { getDefaultColorList() }) // // 更新调色板 LiveData

                    // 如果是新图片，或当前背景色无效，或不在提取的颜色中，则自动选择一个新背景色
                    if (isNewImage ||
                        _selectedBackgroundColor.value == WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR || //
                        !extractedColors.contains(_selectedBackgroundColor.value) ||
                        extractedColors.isEmpty()) {
                        val newBgColor = extractedColors.firstOrNull() ?: WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR //
                        viewModelScope.launch(Dispatchers.Main) { updateSelectedBackgroundColor(newBgColor) } //
                    }
                }
            } ?: run { // 如果解码后的位图为 null
                Log.e("MainViewModel", "extractColorsFromUri: Decoded bitmap for palette is null. URI: $uri")
                if (isNewImage) _toastMessage.postValue(Event(getApplication<Application>().getString(R.string.image_load_failed_toast) + " (提取颜色时解码失败)")) //
                launch(Dispatchers.Main) { setDefaultColorPalette() } //
            }
        } catch (e: Exception) { // 捕获所有可能的异常
            Log.e("MainViewModel", "Exception in extractColorsFromUri for $uri", e)
            if (isNewImage) _toastMessage.postValue(Event(getApplication<Application>().getString(R.string.image_load_failed_toast) + " (提取颜色异常)")) //
            launch(Dispatchers.Main) { setDefaultColorPalette() } //
        }
    }

    /**
     * 为调色板提取计算 BitmapFactory 的 inSampleSize。
     * 目标是加载一个较小尺寸的位图（大约 reqSize x reqSize）以加速调色板提取。
     * @param options 包含原始图片尺寸的 BitmapFactory.Options。
     * @param reqSize 期望的目标尺寸 (宽度和高度都接近此值)。
     * @return 计算得到的 inSampleSize (2的幂，最小为1)。
     */
    private fun calculateInSampleSizeForPalette(options: BitmapFactory.Options, reqSize: Int): Int {
        val (h, w) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (h > reqSize || w > reqSize) {
            val halfH = h / 2
            val halfW = w / 2
            // 只要缩放一半后的尺寸仍大于等于请求尺寸，就继续增加采样率
            while ((halfH / inSampleSize) >= reqSize && (halfW / inSampleSize) >= reqSize) {
                inSampleSize *= 2
                if (inSampleSize > 16) break // 限制最大采样率，避免图片过小
            }
        }
        return inSampleSize
    }

    /**
     * 获取默认的调色板颜色列表。
     * 在无法从图片提取颜色或未选择图片时使用。
     * @return 一个包含预定义颜色整数的列表。
     */
    private fun getDefaultColorList(): List<Int> {
        return listOf(
            Color.parseColor("#616161"), Color.parseColor("#424242"),
            WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR, //
            Color.parseColor("#9E9E9E"), Color.parseColor("#212121")
        )
    }

    /**
     * 设置并应用默认的调色板和背景颜色。
     * 通常在图片加载失败或应用初始化且无已选图片时调用。
     */
    private fun setDefaultColorPalette() {
        _colorPalette.postValue(getDefaultColorList()) //
        // 如果没有选定图片，或者当前背景色无效/不是默认列表中的，则设置为默认背景色
        if (_selectedImageUri.value == null ||
            _selectedBackgroundColor.value == null ||
            !getDefaultColorList().contains(_selectedBackgroundColor.value)) { //
            updateSelectedBackgroundColor(WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR) //
        }
    }

    /**
     * 处理图片访问失败的情况。
     * 会发送 Toast 提示用户，清除已选图片状态，重置相关配置为默认值。
     * @param uriFailed 访问失败的图片的 Uri，可能为 null。
     * @param message 要显示的错误消息。
     */
    fun handleImageAccessFailed(uriFailed: Uri?, message: String = "图片访问失败") {
        _toastMessage.postValue(Event(message)) //
        // 如果失败的 URI 是当前选中的 URI，或者传入的 URI 为 null (通用失败)
        if (_selectedImageUri.value == uriFailed || uriFailed == null) {
            _selectedImageUri.postValue(null) // 清除选中的图片 URI
            preferencesRepository.removeImageUri() // // 从 SharedPreferences 中移除
            originalBitmapForColorExtraction?.recycle() // 回收用于颜色提取的位图
            originalBitmapForColorExtraction = null
            setDefaultColorPalette() // // 设置默认调色板

            // 重置 P1 相关配置为默认值
            val defFx = WallpaperConfigConstants.DEFAULT_P1_FOCUS_X //
            val defFy = WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y //
            val defHr = WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO //
            val defCs = WallpaperConfigConstants.DEFAULT_P1_CONTENT_SCALE_FACTOR //
            _p1FocusX.postValue(defFx)
            _p1FocusY.postValue(defFy)
            _page1ImageHeightRatio.postValue(defHr)
            _p1ContentScaleFactor.postValue(defCs)
            // 同样通过 Repository 更新 SharedPreferences
            preferencesRepository.setP1Focus(defFx, defFy) //
            preferencesRepository.setPage1ImageHeightRatio(defHr) //
            preferencesRepository.setP1ContentScaleFactor(defCs) //
            preferencesRepository.updateImageContentVersion() // // 更新版本号
        }
    }

    /**
     * 实时更新高级设置参数的值，并将其持久化。
     * 此方法用于处理底部配置面板中各个滑块的值变化。
     * 包含节流逻辑，以避免过于频繁地写入 SharedPreferences。
     *
     * @param paramKey 参数的键名，应为 [WallpaperConfigConstants] 中定义的 KEY_* 常量。
     * @param actualValue 参数的实际浮点数值 (对于迭代次数等整数型参数，会四舍五入)。
     */
    open fun updateAdvancedSettingRealtime(paramKey: String, actualValue: Float) {
        // 节流逻辑
        val currentTime = System.currentTimeMillis()
        val lastUpdateTime = paramUpdateTimes[paramKey] ?: 0L
        if (currentTime - lastUpdateTime < throttleInterval) {
            return  // 更新太频繁，跳过
        }
        paramUpdateTimes[paramKey] = currentTime // 记录本次更新时间

        var valueChanged = false // 标记值是否真的改变了
        // 根据参数键名，更新对应的 LiveData，并调用 Repository 保存
        when (paramKey) {
            WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY -> { //
                if (_scrollSensitivity.value != actualValue) {
                    _scrollSensitivity.value = actualValue
                    // 将浮点值乘以10后转为整数存储
                    preferencesRepository.saveScaledFloatSettingAsInt(paramKey, actualValue, 10) //
                    valueChanged = true
                }
            }
            WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO -> { //
                if (_p1OverlayFadeRatio.value != actualValue) {
                    _p1OverlayFadeRatio.value = actualValue
                    // 将浮点值乘以100后转为整数存储
                    preferencesRepository.saveScaledFloatSettingAsInt(paramKey, actualValue, 100) //
                    valueChanged = true
                }
            }
            WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO -> { //
                if (_p2BackgroundFadeInRatio.value != actualValue) {
                    _p2BackgroundFadeInRatio.value = actualValue
                    preferencesRepository.saveScaledFloatSettingAsInt(paramKey, actualValue, 100) //
                    valueChanged = true
                }
            }
            WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET -> { //
                if (_backgroundInitialOffset.value != actualValue) {
                    _backgroundInitialOffset.value = actualValue
                    preferencesRepository.saveScaledFloatSettingAsInt(paramKey, actualValue, 10) //
                    valueChanged = true
                }
            }
            WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS -> { //
                if (_backgroundBlurRadius.value != actualValue) {
                    _backgroundBlurRadius.value = actualValue
                    preferencesRepository.saveIntSetting(paramKey, actualValue.roundToInt()) // // 直接存整数
                    valueChanged = true
                }
            }
            WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR -> { //
                if (_blurDownscaleFactor.value != actualValue) { // actualValue 是 Float
                    _blurDownscaleFactor.value = actualValue
                    preferencesRepository.saveScaledFloatSettingAsInt(paramKey, actualValue, 100) //
                    valueChanged = true
                }
            }
            WallpaperConfigConstants.KEY_BLUR_ITERATIONS -> { //
                val intValue = actualValue.roundToInt() // actualValue 来自滑块，是Float，转为Int
                if (_blurIterations.value != intValue) {
                    _blurIterations.value = intValue
                    preferencesRepository.saveIntSetting(paramKey, intValue) //
                    valueChanged = true
                }
            }
            WallpaperConfigConstants.KEY_P1_SHADOW_RADIUS -> { //
                if (_p1ShadowRadius.value != actualValue) {
                    _p1ShadowRadius.value = actualValue
                    preferencesRepository.saveIntSetting(paramKey, actualValue.roundToInt()) //
                    valueChanged = true
                }
            }
            WallpaperConfigConstants.KEY_P1_SHADOW_DX -> { //
                if (_p1ShadowDx.value != actualValue) {
                    _p1ShadowDx.value = actualValue
                    preferencesRepository.saveIntSetting(paramKey, actualValue.roundToInt()) //
                    valueChanged = true
                }
            }
            WallpaperConfigConstants.KEY_P1_SHADOW_DY -> { //
                if (_p1ShadowDy.value != actualValue) {
                    _p1ShadowDy.value = actualValue
                    preferencesRepository.saveIntSetting(paramKey, actualValue.roundToInt()) //
                    valueChanged = true
                }
            }
            WallpaperConfigConstants.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT -> { //
                if (_p1ImageBottomFadeHeight.value != actualValue) {
                    _p1ImageBottomFadeHeight.value = actualValue
                    preferencesRepository.saveIntSetting(paramKey, actualValue.roundToInt()) //
                    valueChanged = true
                }
            }
            else -> {
                Log.w("MainViewModel", "updateAdvancedSettingRealtime: Unknown paramKey: $paramKey")
                return
            }
        }

        if (valueChanged) {
            preferencesRepository.updateImageContentVersion() // // 配置变化，更新版本号
            Log.d("MainViewModel", "Advanced setting '$paramKey' updated to $actualValue and saved. Version updated.")
        }
    }

    /**
     * 显式保存非位图相关的配置更改，并强制更新图片内容版本号。
     * 这个方法主要用于确保当某些不直接涉及位图数据，但影响壁纸渲染的配置发生变化时，
     * 壁纸服务能够收到通知并刷新。
     * （当前主要通过 `updateAdvancedSettingRealtime` 和 `updateP1ConfigRealtime` 间接触发版本更新）
     */
    open fun saveNonBitmapConfigAndUpdateVersion() {
        preferencesRepository.updateImageContentVersion() //
        Log.d("MainViewModel", "saveNonBitmapConfigAndUpdateVersion: Image content version explicitly updated.")
    }

    /**
     * ViewModel 被销毁前调用。
     * 在此清理持有的资源，例如回收用于颜色提取的位图。
     */
    override fun onCleared() {
        super.onCleared()
        originalBitmapForColorExtraction?.recycle()
        originalBitmapForColorExtraction = null
        Log.d("MainViewModel", "ViewModel cleared.")
    }

    /**
     * 当用户在底部配置表单中选择一个新的主分类时调用。
     * @param categoryId 新选中的主分类的 ID；如果为 null，则可能表示清除选择或默认状态。
     */
    fun onMainCategorySelectedInSheet(categoryId: String?) {
        _selectedMainCategoryIdInSheet.value = categoryId
        _subCategoryForAdjustmentIdInSheet.value = null // 切换主分类时，清除子分类调整目标
    }

    /**
     * 当用户在底部配置表单中选择一个子分类进行参数调整时调用。
     * 如果再次点击已选中的参数调整项，则会收起它。
     * 如果选择了参数调整项，会隐藏自定义颜色滑块区域。
     * @param subCategoryId 新选中的子分类的 ID；如果为 null，表示取消参数调整。
     */
    fun onSubCategoryForAdjustmentSelectedInSheet(subCategoryId: String?) {
        if (_subCategoryForAdjustmentIdInSheet.value == subCategoryId && subCategoryId != null) {
            // 如果再次点击已选中的参数滑块项，则收起它
            _subCategoryForAdjustmentIdInSheet.value = null
        } else {
            _subCategoryForAdjustmentIdInSheet.value = subCategoryId
            if (subCategoryId != null) {
                // 如果选择了参数滑块项，则隐藏自定义颜色滑块
                _showCustomColorSliders.value = false
            }
        }
    }
}