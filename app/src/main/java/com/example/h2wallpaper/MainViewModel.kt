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
import com.example.h2wallpaper.WallpaperConfigConstants // 确保导入
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs
import kotlin.math.roundToInt

// --- 数据类定义 (从之前的回复中复制，如果它们不在此文件中，请确保MainViewModel可以访问) ---
// ... (P1ConfigState, BackgroundConfigState, ScrollConfigState, StyleBSpecificConfigState, WallpaperSettingsState)
// 为了简洁，这里不再重复数据类的定义，假设它们已存在或你暂时不使用顶层WallpaperSettingsState

/**
 * MainActivity 和 BottomSheetScreen 的 ViewModel。
 * (注释保持不变)
 */
open class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesRepository: WallpaperPreferencesRepository =
        WallpaperPreferencesRepository(application)

    // --- LiveData for UI State ---

    /** 用户选择的图片 URI。这是UI层应该观察的主要URI状态。*/
    private val _selectedImageUri = MutableLiveData<Uri?>()
    open val selectedImageUri: LiveData<Uri?> get() = _selectedImageUri // 主URI LiveData

    // selectedImageUriForUi 替换为直接使用 selectedImageUri，或者如果确实需要独立，则如下：
    // private val _selectedImageUriForUi = MutableLiveData<Uri?>()
    // open val selectedImageUriForUi: LiveData<Uri?> get() = _selectedImageUriForUi
    // 为了解决报错，我们假设 BottomSheetScreen 会观察 selectedImageUri

    /** 用户选择的背景颜色。 */
    private val _selectedBackgroundColor = MutableLiveData<Int>()
    open val selectedBackgroundColor: LiveData<Int> get() = _selectedBackgroundColor

    // --- 样式 A 的 P1 参数 ---
    private val _page1ImageHeightRatio = MutableLiveData<Float>()
    open val page1ImageHeightRatio: LiveData<Float> get() = _page1ImageHeightRatio
    private val _p1FocusX = MutableLiveData<Float>()
    val p1FocusX: LiveData<Float> get() = _p1FocusX
    private val _p1FocusY = MutableLiveData<Float>()
    val p1FocusY: LiveData<Float> get() = _p1FocusY
    private val _p1ContentScaleFactor = MutableLiveData<Float>()
    val p1ContentScaleFactor: LiveData<Float> get() = _p1ContentScaleFactor
    private val _p1ShadowRadius = MutableLiveData<Float>()
    val p1ShadowRadius: LiveData<Float> get() = _p1ShadowRadius
    private val _p1ShadowDx = MutableLiveData<Float>()
    val p1ShadowDx: LiveData<Float> get() = _p1ShadowDx
    private val _p1ShadowDy = MutableLiveData<Float>()
    val p1ShadowDy: LiveData<Float> get() = _p1ShadowDy
    private val _p1ImageBottomFadeHeight = MutableLiveData<Float>()
    val p1ImageBottomFadeHeight: LiveData<Float> get() = _p1ImageBottomFadeHeight

    // --- P1 样式类型 ---
    /** 当前 P1 层的样式类型 (例如，0 代表样式 A，1 代表样式 B)。 */
    private val _p1StyleType = MutableLiveData<Int>() // 私有 MutableLiveData
    open val p1StyleType: LiveData<Int> get() = _p1StyleType // 公开为 LiveData

    // --- 样式 B 特有参数 LiveData ---
    private val _styleBMaskAlpha = MutableLiveData<Float>()
    val styleBMaskAlpha: LiveData<Float> get() = _styleBMaskAlpha
    private val _styleBRotationParamA = MutableLiveData<Float>()
    val styleBRotationParamA: LiveData<Float> get() = _styleBRotationParamA
    private val _styleBGapSizeRatio = MutableLiveData<Float>()
    val styleBGapSizeRatio: LiveData<Float> get() = _styleBGapSizeRatio
    private val _styleBGapPositionYRatio = MutableLiveData<Float>()
    val styleBGapPositionYRatio: LiveData<Float> get() = _styleBGapPositionYRatio
    private val _styleBP1FocusX = MutableLiveData<Float>()
    val styleBP1FocusX: LiveData<Float> get() = _styleBP1FocusX
    private val _styleBP1FocusY = MutableLiveData<Float>()
    val styleBP1FocusY: LiveData<Float> get() = _styleBP1FocusY
    private val _styleBP1ScaleFactor = MutableLiveData<Float>()
    val styleBP1ScaleFactor: LiveData<Float> get() = _styleBP1ScaleFactor
    private val _styleBUpperMaskMaxRotation = MutableLiveData<Float>()
    val styleBUpperMaskMaxRotation: LiveData<Float> get() = _styleBUpperMaskMaxRotation
    private val _styleBLowerMaskMaxRotation = MutableLiveData<Float>()
    val styleBLowerMaskMaxRotation: LiveData<Float> get() = _styleBLowerMaskMaxRotation

    // --- 通用高级设置参数 LiveData ---
    private val _scrollSensitivity = MutableLiveData<Float>()
    open val scrollSensitivity: LiveData<Float> get() = _scrollSensitivity
    private val _p1OverlayFadeRatio = MutableLiveData<Float>()
    open val p1OverlayFadeRatio: LiveData<Float> get() = _p1OverlayFadeRatio
    private val _p2BackgroundFadeInRatio = MutableLiveData<Float>()
    val p2BackgroundFadeInRatio: LiveData<Float> get() = _p2BackgroundFadeInRatio
    private val _backgroundInitialOffset = MutableLiveData<Float>()
    val backgroundInitialOffset: LiveData<Float> get() = _backgroundInitialOffset
    private val _backgroundBlurRadius = MutableLiveData<Float>()
    val backgroundBlurRadius: LiveData<Float> get() = _backgroundBlurRadius
    private val _blurDownscaleFactor = MutableLiveData<Float>()
    val blurDownscaleFactor: LiveData<Float> get() = _blurDownscaleFactor
    private val _blurIterations = MutableLiveData<Int>()
    val blurIterations: LiveData<Int> get() = _blurIterations

    // --- 其他 ViewModel 状态 ---
    private val _toastMessage = MutableLiveData<Event<String>>()
    val toastMessage: LiveData<Event<String>> get() = _toastMessage
    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> get() = _isLoading
    private val _colorPalette = MutableLiveData<List<Int>>()
    open val colorPalette: LiveData<List<Int>> get() = _colorPalette
    private var originalBitmapForColorExtraction: Bitmap? = null
    private val _isP1EditMode = MutableLiveData<Boolean>(false)
    open val isP1EditMode: LiveData<Boolean> get() = _isP1EditMode

    // --- BottomSheet Navigation and State ---
    /** 控制底部配置表单 (BottomSheet) 是否显示。 */
    protected val _showConfigSheet = MutableStateFlow(false) // 通常 ViewModel 内部用 protected 或 private
    open val showConfigSheet: StateFlow<Boolean> get() = _showConfigSheet.asStateFlow() // 对外暴露为 StateFlow

    /** 底部配置表单中当前选中的主分类 ID。 */
    private val _selectedMainCategoryIdInSheet = MutableStateFlow(mainCategoriesData.firstOrNull()?.id)
    val selectedMainCategoryIdInSheet: StateFlow<String?> = _selectedMainCategoryIdInSheet.asStateFlow()

    /** 底部配置表单中当前选中的、用于参数调整的子分类 ID。 */
    private val _subCategoryForAdjustmentIdInSheet = MutableStateFlow<String?>(null)
    val subCategoryForAdjustmentIdInSheet: StateFlow<String?> = _subCategoryForAdjustmentIdInSheet.asStateFlow()

    /** 控制底部配置表单中自定义背景颜色 RGB 滑块区域是否显示。 */
    private val _showCustomColorSliders = MutableStateFlow(false) // 私有 MutableStateFlow
    open val showCustomColorSliders: StateFlow<Boolean> get() = _showCustomColorSliders.asStateFlow() // 公开为 StateFlow

    /** 控制P1样式选择视图的显示/隐藏。 */
    private val _showStyleSelection = MutableStateFlow(false) // 私有 MutableStateFlow
    open val showStyleSelection: StateFlow<Boolean> = _showStyleSelection.asStateFlow() // 公开为 StateFlow

    private val _styleBMasksHorizontallyFlipped = MutableLiveData<Boolean>()
    val styleBMasksHorizontallyFlipped: LiveData<Boolean> get() = _styleBMasksHorizontallyFlipped

    private val paramUpdateTimes = mutableMapOf<String, Long>()
    private val throttleInterval = 20f

    init {
        loadInitialPreferences()
    }

    private fun loadInitialPreferences() {
        _selectedImageUri.value = preferencesRepository.getSelectedImageUri()
        // _selectedImageUriForUi.value = _selectedImageUri.value // 如果保留 selectedImageUriForUi
        _selectedBackgroundColor.value = preferencesRepository.getSelectedBackgroundColor()
        _page1ImageHeightRatio.value = preferencesRepository.getPage1ImageHeightRatio()
        _p1FocusX.value = preferencesRepository.getP1FocusX()
        _p1FocusY.value = preferencesRepository.getP1FocusY()
        _p1ContentScaleFactor.value = preferencesRepository.getP1ContentScaleFactor()
        _p1ShadowRadius.value = preferencesRepository.getP1ShadowRadius()
        _p1ShadowDx.value = preferencesRepository.getP1ShadowDx()
        _p1ShadowDy.value = preferencesRepository.getP1ShadowDy()
        _p1ImageBottomFadeHeight.value = preferencesRepository.getP1ImageBottomFadeHeight()
        _scrollSensitivity.value = preferencesRepository.getScrollSensitivity()
        _p1OverlayFadeRatio.value = preferencesRepository.getP1OverlayFadeRatio()
        _p2BackgroundFadeInRatio.value = preferencesRepository.getP2BackgroundFadeInRatio()
        _backgroundInitialOffset.value = preferencesRepository.getBackgroundInitialOffset()
        _backgroundBlurRadius.value = preferencesRepository.getBackgroundBlurRadius()
        _blurDownscaleFactor.value = preferencesRepository.getBlurDownscaleFactor()
        _blurIterations.value = preferencesRepository.getBlurIterations()
        _p1StyleType.value = preferencesRepository.getP1StyleType()
        _styleBMaskAlpha.value = preferencesRepository.getStyleBMaskAlpha()
        _styleBRotationParamA.value = preferencesRepository.getStyleBRotationParamA()
        _styleBGapSizeRatio.value = preferencesRepository.getStyleBGapSizeRatio()
        _styleBGapPositionYRatio.value = preferencesRepository.getStyleBGapPositionYRatio()
        _styleBP1FocusX.value = preferencesRepository.getStyleBP1FocusX()
        _styleBP1FocusY.value = preferencesRepository.getStyleBP1FocusY()
        _styleBP1ScaleFactor.value = preferencesRepository.getStyleBP1ScaleFactor()
        _styleBUpperMaskMaxRotation.value = preferencesRepository.getStyleBUpperMaskMaxRotation()
        _styleBLowerMaskMaxRotation.value = preferencesRepository.getStyleBLowerMaskMaxRotation()
        _styleBMasksHorizontallyFlipped.value = preferencesRepository.getStyleBMasksHorizontallyFlipped()

        viewModelScope.launch {
            val currentUri = _selectedImageUri.value
            if (currentUri != null) {
                try { extractColorsFromUri(currentUri, isNewImage = false) }
                catch (e: Exception) {
                    Log.e("MainViewModel", "Error extracting colors on init: ${e.message}")
                    launch(Dispatchers.Main) { setDefaultColorPalette() }
                }
            } else {
                launch(Dispatchers.Main) { setDefaultColorPalette() }
            }
        }
    }

    // --- 方法区 ---

    open fun toggleConfigSheetVisibility() {
        _showConfigSheet.update { !it }
    }

    open fun openConfigSheet() { _showConfigSheet.value = true }

    open fun closeConfigSheet() {
        _showConfigSheet.value = false
        _showStyleSelection.value = false
    }

    open fun toggleP1EditMode() {
        val newEditMode = !(_isP1EditMode.value ?: false)
        _isP1EditMode.value = newEditMode
        if (newEditMode) {
            _subCategoryForAdjustmentIdInSheet.value = null
            _showCustomColorSliders.value = false
            _showStyleSelection.value = false
            Log.d("MainViewModel", "Entering P1 Edit Mode.")
        } else {
            Log.d("MainViewModel", "Exiting P1 Edit Mode.")
        }
    }

    fun updateP1StyleType(styleType: Int) {
        val validatedStyleType = if (styleType == 1) 1 else 0
        if (_p1StyleType.value != validatedStyleType) {
            _p1StyleType.value = validatedStyleType
            preferencesRepository.saveIntSetting(WallpaperConfigConstants.KEY_P1_STYLE_TYPE, validatedStyleType)
            preferencesRepository.updateImageContentVersion()
            Log.d("MainViewModel", "P1 Style Type updated to: $validatedStyleType")
        }
    }

    fun updateP1ConfigRealtime(normX: Float, normY: Float, heightRatio: Float, contentScale: Float) {
        if (_p1StyleType.value != WallpaperConfigConstants.DEFAULT_P1_STYLE_TYPE) {
            Log.w("MainViewModel", "updateP1ConfigRealtime (Style A editor) called but current style is not Style A. Ignoring.")
            return
        }
        var configActuallyChanged = false
        val newNormX = normX.coerceIn(0f, 1f)
        if (abs((_p1FocusX.value ?: WallpaperConfigConstants.DEFAULT_P1_FOCUS_X) - newNormX) > 0.0001f) {
            _p1FocusX.value = newNormX
            preferencesRepository.setP1FocusX(newNormX); configActuallyChanged = true
        }
        val newNormY = normY.coerceIn(0f, 1f)
        if (abs((_p1FocusY.value ?: WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y) - newNormY) > 0.0001f) {
            _p1FocusY.value = newNormY
            preferencesRepository.setP1FocusY(newNormY); configActuallyChanged = true
        }
        val newHeightRatio = heightRatio.coerceIn(WallpaperConfigConstants.MIN_HEIGHT_RATIO, WallpaperConfigConstants.MAX_HEIGHT_RATIO)
        if (abs((_page1ImageHeightRatio.value ?: WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO) - newHeightRatio) > 0.0001f) {
            _page1ImageHeightRatio.value = newHeightRatio
            preferencesRepository.setPage1ImageHeightRatio(newHeightRatio); configActuallyChanged = true
        }
        val newContentScale = contentScale.coerceIn(WallpaperConfigConstants.DEFAULT_P1_CONTENT_SCALE_FACTOR, 4.0f)
        if (abs((_p1ContentScaleFactor.value ?: WallpaperConfigConstants.DEFAULT_P1_CONTENT_SCALE_FACTOR) - newContentScale) > 0.0001f) {
            _p1ContentScaleFactor.value = newContentScale
            preferencesRepository.setP1ContentScaleFactor(newContentScale); configActuallyChanged = true
        }
        if (configActuallyChanged) {
            Log.d("MainViewModel", "P1 Style A Config REALTIME SAVED")
            preferencesRepository.updateImageContentVersion()
        }
    }

    fun updateStyleBP1Config(normX: Float, normY: Float, scaleFactor: Float) {
        if (_p1StyleType.value != 1 /* STYLE_B */) {
            Log.w("MainViewModel", "updateStyleBP1Config called but current style is not Style B. Ignoring.")
            return
        }
        var configActuallyChanged = false
        val newNormX = normX.coerceIn(0f, 1f)
        if (abs((_styleBP1FocusX.value ?: WallpaperConfigConstants.DEFAULT_STYLE_B_P1_FOCUS_X) - newNormX) > 0.0001f) {
            _styleBP1FocusX.value = newNormX
            preferencesRepository.saveFloatSetting(WallpaperConfigConstants.KEY_STYLE_B_P1_FOCUS_X, newNormX); configActuallyChanged = true
        }
        val newNormY = normY.coerceIn(0f, 1f)
        if (abs((_styleBP1FocusY.value ?: WallpaperConfigConstants.DEFAULT_STYLE_B_P1_FOCUS_Y) - newNormY) > 0.0001f) {
            _styleBP1FocusY.value = newNormY
            preferencesRepository.saveFloatSetting(WallpaperConfigConstants.KEY_STYLE_B_P1_FOCUS_Y, newNormY); configActuallyChanged = true
        }
        val newScaleFactor = scaleFactor.coerceIn(WallpaperConfigConstants.DEFAULT_STYLE_B_P1_SCALE_FACTOR, 4.0f)
        if (abs((_styleBP1ScaleFactor.value ?: WallpaperConfigConstants.DEFAULT_STYLE_B_P1_SCALE_FACTOR) - newScaleFactor) > 0.0001f) {
            _styleBP1ScaleFactor.value = newScaleFactor
            preferencesRepository.saveFloatSetting(WallpaperConfigConstants.KEY_STYLE_B_P1_SCALE_FACTOR, newScaleFactor); configActuallyChanged = true
        }
        if (configActuallyChanged) {
            Log.d("MainViewModel", "P1 Style B Config SAVED")
            preferencesRepository.updateImageContentVersion()
        }
    }

    open fun updateSelectedBackgroundColor(color: Int) {
        val currentTime = System.currentTimeMillis()
        val lastUpdateTime = paramUpdateTimes[WallpaperConfigConstants.KEY_BACKGROUND_COLOR] ?: 0L
        if (currentTime - lastUpdateTime < throttleInterval) return
        paramUpdateTimes[WallpaperConfigConstants.KEY_BACKGROUND_COLOR] = currentTime
        if (_selectedBackgroundColor.value != color) {
            _selectedBackgroundColor.value = color
            preferencesRepository.setSelectedBackgroundColor(color)
            preferencesRepository.updateImageContentVersion()
        }
    }

    fun handleImageSelectionResult(uri: Uri?) {
        if (_isP1EditMode.value == true) _isP1EditMode.value = false
        if (uri != null) {
            _isLoading.value = true
            viewModelScope.launch {
                val internalFileUri = saveImageToInternalAppStorage(uri)
                withContext(Dispatchers.Main) { _isLoading.value = false }
                if (internalFileUri != null) {
                    val oldUri = _selectedImageUri.value
                    if (oldUri != null && oldUri != internalFileUri) deleteInternalImage(oldUri)


                    _selectedImageUri.postValue(internalFileUri)
                    // _selectedImageUriForUi.postValue(internalFileUri) // 如果保留
                    /*
                                        // Reset Style A P1 params
                                        _p1FocusX.postValue(WallpaperConfigConstants.DEFAULT_P1_FOCUS_X)
                                        _p1FocusY.postValue(WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y)
                                        _page1ImageHeightRatio.postValue(WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO)
                                        _p1ContentScaleFactor.postValue(WallpaperConfigConstants.DEFAULT_P1_CONTENT_SCALE_FACTOR)
                                        // Reset Style B P1 params
                                        _styleBP1FocusX.postValue(WallpaperConfigConstants.DEFAULT_STYLE_B_P1_FOCUS_X)
                                        _styleBP1FocusY.postValue(WallpaperConfigConstants.DEFAULT_STYLE_B_P1_FOCUS_Y)
                                        _styleBP1ScaleFactor.postValue(WallpaperConfigConstants.DEFAULT_STYLE_B_P1_SCALE_FACTOR)
*/
                                        preferencesRepository.resetSettingsForNewImage(internalFileUri)
                                        preferencesRepository.updateImageContentVersion()
                    try { extractColorsFromUri(internalFileUri, isNewImage = true) }
                    catch (e: Exception) {
                        _toastMessage.postValue(Event("颜色提取失败: ${e.message}"))
                        launch(Dispatchers.Main) { setDefaultColorPalette() }
                    }
                } else {
                    _toastMessage.postValue(Event(getApplication<Application>().getString(R.string.image_load_failed_toast) + " (复制失败)"))
                }
            }
        } else {
            _toastMessage.postValue(Event(getApplication<Application>().getString(R.string.image_selection_failed_toast)))
        }
    }

    private suspend fun saveImageToInternalAppStorage(sourceUri: Uri): Uri? = withContext(Dispatchers.IO) {
        val context = getApplication<Application>().applicationContext
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                val imageDir = File(context.filesDir, "wallpaper_images")
                if (!imageDir.exists() && !imageDir.mkdirs()) {
                    Log.e("MainViewModel", "Failed to create directory: ${imageDir.absolutePath}")
                    return@withContext null
                }
                val internalFile = File(imageDir, "h2_wallpaper_internal_image.jpg")
                FileOutputStream(internalFile).use { outputStream -> inputStream.copyTo(outputStream) }
                return@withContext FileProvider.getUriForFile(context, "${context.packageName}.provider", internalFile)
            } ?: Log.e("MainViewModel", "ContentResolver failed to open InputStream for $sourceUri")
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error saving image to internal storage for $sourceUri", e)
        }
        return@withContext null
    }

    private suspend fun deleteInternalImage(internalFileUri: Uri) = withContext(Dispatchers.IO) {
        val context = getApplication<Application>().applicationContext
        val imageDir = File(context.filesDir, "wallpaper_images")
        val internalFile = File(imageDir, "h2_wallpaper_internal_image.jpg")
        if (internalFile.exists()) {
            if (internalFile.delete()) Log.i("MainViewModel", "Deleted old internal image: ${internalFile.path}")
            else Log.w("MainViewModel", "Failed to delete old internal image: ${internalFile.path}")
        }
    }

    private suspend fun extractColorsFromUri(uri: Uri, isNewImage: Boolean) = withContext(Dispatchers.IO) {
        val context = getApplication<Application>().applicationContext
        var bitmapForPalette: Bitmap? = null
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStreamBounds ->
                val optionsBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(inputStreamBounds, null, optionsBounds)
                if (optionsBounds.outWidth <= 0 || optionsBounds.outHeight <= 0) throw IOException("Bitmap bounds invalid for $uri")
                val options = BitmapFactory.Options().apply {
                    inSampleSize = calculateInSampleSizeForPalette(optionsBounds, 256)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                context.contentResolver.openInputStream(uri)?.use { inputStreamBitmap ->
                    originalBitmapForColorExtraction?.recycle()
                    originalBitmapForColorExtraction = BitmapFactory.decodeStream(inputStreamBitmap, null, options)
                    bitmapForPalette = originalBitmapForColorExtraction
                } ?: throw IOException("Failed to reopen input stream for bitmap for $uri")
            } ?: throw IOException("Failed to open input stream for bounds for $uri")

            bitmapForPalette?.let { bitmap ->
                Palette.from(bitmap).generate { palette ->
                    val swatches = listOfNotNull(
                        palette?.dominantSwatch, palette?.vibrantSwatch, palette?.mutedSwatch,
                        palette?.lightVibrantSwatch, palette?.darkVibrantSwatch,
                        palette?.lightMutedSwatch, palette?.darkMutedSwatch
                    ).distinctBy { it.rgb }.take(8)
                    val extractedColors = swatches.map { it.rgb }
                    _colorPalette.postValue(extractedColors.ifEmpty { getDefaultColorList() })
                    if (isNewImage || _selectedBackgroundColor.value == WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR ||
                        !extractedColors.contains(_selectedBackgroundColor.value) || extractedColors.isEmpty()) {
                        val newBgColor = extractedColors.firstOrNull() ?: WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR
                        viewModelScope.launch(Dispatchers.Main) { updateSelectedBackgroundColor(newBgColor) }
                    }
                }
            } ?: run {
                Log.e("MainViewModel", "extractColorsFromUri: Decoded bitmap for palette is null. URI: $uri")
                if (isNewImage) _toastMessage.postValue(Event(getApplication<Application>().getString(R.string.image_load_failed_toast) + " (提取颜色时解码失败)"))
                launch(Dispatchers.Main) { setDefaultColorPalette() }
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Exception in extractColorsFromUri for $uri", e)
            if (isNewImage) _toastMessage.postValue(Event(getApplication<Application>().getString(R.string.image_load_failed_toast) + " (提取颜色异常)"))
            launch(Dispatchers.Main) { setDefaultColorPalette() }
        }
    }

    private fun calculateInSampleSizeForPalette(options: BitmapFactory.Options, reqSize: Int): Int {
        val (h, w) = options.outHeight to options.outWidth; var inSampleSize = 1
        if (h > reqSize || w > reqSize) { val halfH = h / 2; val halfW = w / 2
            while ((halfH / inSampleSize) >= reqSize && (halfW / inSampleSize) >= reqSize) {
                inSampleSize *= 2; if (inSampleSize > 16) break
            }
        }
        return inSampleSize
    }

    private fun getDefaultColorList(): List<Int> {
        return listOf(Color.parseColor("#616161"), Color.parseColor("#424242"),
            WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR, Color.parseColor("#9E9E9E"), Color.parseColor("#212121"))
    }

    private fun setDefaultColorPalette() {
        _colorPalette.postValue(getDefaultColorList())
        if (_selectedImageUri.value == null || _selectedBackgroundColor.value == null || !getDefaultColorList().contains(_selectedBackgroundColor.value)) {
            updateSelectedBackgroundColor(WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR)
        }
    }
    fun handleImageAccessFailed(uriFailed: Uri?, message: String = "图片访问失败") {
        _toastMessage.postValue(Event(message))
        if (_selectedImageUri.value == uriFailed || uriFailed == null) {
            _selectedImageUri.postValue(null); preferencesRepository.removeImageUri()
            originalBitmapForColorExtraction?.recycle(); originalBitmapForColorExtraction = null
            setDefaultColorPalette()
            _p1FocusX.postValue(WallpaperConfigConstants.DEFAULT_P1_FOCUS_X)
            _p1FocusY.postValue(WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y)
            _page1ImageHeightRatio.postValue(WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO)
            _p1ContentScaleFactor.postValue(WallpaperConfigConstants.DEFAULT_P1_CONTENT_SCALE_FACTOR)
            preferencesRepository.setP1Focus(WallpaperConfigConstants.DEFAULT_P1_FOCUS_X, WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y)
            preferencesRepository.setPage1ImageHeightRatio(WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO)
            preferencesRepository.setP1ContentScaleFactor(WallpaperConfigConstants.DEFAULT_P1_CONTENT_SCALE_FACTOR)
            _styleBP1FocusX.postValue(WallpaperConfigConstants.DEFAULT_STYLE_B_P1_FOCUS_X)
            _styleBP1FocusY.postValue(WallpaperConfigConstants.DEFAULT_STYLE_B_P1_FOCUS_Y)
            _styleBP1ScaleFactor.postValue(WallpaperConfigConstants.DEFAULT_STYLE_B_P1_SCALE_FACTOR)
            preferencesRepository.saveFloatSetting(WallpaperConfigConstants.KEY_STYLE_B_P1_FOCUS_X, WallpaperConfigConstants.DEFAULT_STYLE_B_P1_FOCUS_X)
            preferencesRepository.saveFloatSetting(WallpaperConfigConstants.KEY_STYLE_B_P1_FOCUS_Y, WallpaperConfigConstants.DEFAULT_STYLE_B_P1_FOCUS_Y)
            preferencesRepository.saveFloatSetting(WallpaperConfigConstants.KEY_STYLE_B_P1_SCALE_FACTOR, WallpaperConfigConstants.DEFAULT_STYLE_B_P1_SCALE_FACTOR)
            preferencesRepository.updateImageContentVersion()
        }
    }

    open fun updateAdvancedSettingRealtime(paramKey: String, actualValue: Float) {
        val currentTime = System.currentTimeMillis()
        val lastUpdateTime = paramUpdateTimes[paramKey] ?: 0L
        if (currentTime - lastUpdateTime < throttleInterval) return
        paramUpdateTimes[paramKey] = currentTime
        var valueChanged = false
        when (paramKey) {
            WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY -> { if (_scrollSensitivity.value != actualValue) { _scrollSensitivity.value = actualValue; preferencesRepository.saveScaledFloatSettingAsInt(paramKey, actualValue, 10); valueChanged = true } }
            WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO -> { if (_p1OverlayFadeRatio.value != actualValue) { _p1OverlayFadeRatio.value = actualValue; preferencesRepository.saveScaledFloatSettingAsInt(paramKey, actualValue, 100); valueChanged = true } }
            WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO -> { if (_p2BackgroundFadeInRatio.value != actualValue) { _p2BackgroundFadeInRatio.value = actualValue; preferencesRepository.saveScaledFloatSettingAsInt(paramKey, actualValue, 100); valueChanged = true } }
            WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET -> { if (_backgroundInitialOffset.value != actualValue) { _backgroundInitialOffset.value = actualValue; preferencesRepository.saveScaledFloatSettingAsInt(paramKey, actualValue, 10); valueChanged = true } }
            WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS -> { if (_backgroundBlurRadius.value != actualValue) { _backgroundBlurRadius.value = actualValue; preferencesRepository.saveIntSetting(paramKey, actualValue.roundToInt()); valueChanged = true } }
            WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR -> { if (_blurDownscaleFactor.value != actualValue) { _blurDownscaleFactor.value = actualValue; preferencesRepository.saveScaledFloatSettingAsInt(paramKey, actualValue, 100); valueChanged = true } }
            WallpaperConfigConstants.KEY_BLUR_ITERATIONS -> { val intValue = actualValue.roundToInt(); if (_blurIterations.value != intValue) { _blurIterations.value = intValue; preferencesRepository.saveIntSetting(paramKey, intValue); valueChanged = true } }
            WallpaperConfigConstants.KEY_IMAGE_HEIGHT_RATIO -> { if (_page1ImageHeightRatio.value != actualValue) { _page1ImageHeightRatio.value = actualValue; preferencesRepository.setPage1ImageHeightRatio(actualValue); valueChanged = true } }
            WallpaperConfigConstants.KEY_P1_CONTENT_SCALE_FACTOR -> { if (_p1ContentScaleFactor.value != actualValue) { _p1ContentScaleFactor.value = actualValue; preferencesRepository.setP1ContentScaleFactor(actualValue); valueChanged = true } }
            WallpaperConfigConstants.KEY_P1_SHADOW_RADIUS -> { if (_p1ShadowRadius.value != actualValue) { _p1ShadowRadius.value = actualValue; preferencesRepository.saveIntSetting(paramKey, actualValue.roundToInt()); valueChanged = true } }
            WallpaperConfigConstants.KEY_P1_SHADOW_DX -> { if (_p1ShadowDx.value != actualValue) { _p1ShadowDx.value = actualValue; preferencesRepository.saveIntSetting(paramKey, actualValue.roundToInt()); valueChanged = true } }
            WallpaperConfigConstants.KEY_P1_SHADOW_DY -> { if (_p1ShadowDy.value != actualValue) { _p1ShadowDy.value = actualValue; preferencesRepository.saveIntSetting(paramKey, actualValue.roundToInt()); valueChanged = true } }
            WallpaperConfigConstants.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT -> { if (_p1ImageBottomFadeHeight.value != actualValue) { _p1ImageBottomFadeHeight.value = actualValue; preferencesRepository.saveIntSetting(paramKey, actualValue.roundToInt()); valueChanged = true } }
            WallpaperConfigConstants.KEY_STYLE_B_MASK_ALPHA -> { if (_styleBMaskAlpha.value != actualValue) { _styleBMaskAlpha.value = actualValue; preferencesRepository.saveScaledFloatSettingAsInt(paramKey, actualValue, 100); valueChanged = true } }
            WallpaperConfigConstants.KEY_STYLE_B_ROTATION_PARAM_A -> { if (_styleBRotationParamA.value != actualValue) { _styleBRotationParamA.value = actualValue; preferencesRepository.saveScaledFloatSettingAsInt(paramKey, actualValue, 100); valueChanged = true } }
            WallpaperConfigConstants.KEY_STYLE_B_GAP_SIZE_RATIO -> { if (_styleBGapSizeRatio.value != actualValue) { _styleBGapSizeRatio.value = actualValue; preferencesRepository.saveScaledFloatSettingAsInt(paramKey, actualValue, 100); valueChanged = true } }
            WallpaperConfigConstants.KEY_STYLE_B_GAP_POSITION_Y_RATIO -> { if (_styleBGapPositionYRatio.value != actualValue) { _styleBGapPositionYRatio.value = actualValue; preferencesRepository.saveScaledFloatSettingAsInt(paramKey, actualValue, 100); valueChanged = true } }
            WallpaperConfigConstants.KEY_STYLE_B_UPPER_MASK_MAX_ROTATION -> { if (_styleBUpperMaskMaxRotation.value != actualValue) { _styleBUpperMaskMaxRotation.value = actualValue; preferencesRepository.saveIntSetting(paramKey, actualValue.roundToInt()); valueChanged = true } }
            WallpaperConfigConstants.KEY_STYLE_B_LOWER_MASK_MAX_ROTATION -> { if (_styleBLowerMaskMaxRotation.value != actualValue) { _styleBLowerMaskMaxRotation.value = actualValue; preferencesRepository.saveIntSetting(paramKey, actualValue.roundToInt()); valueChanged = true } }
            else -> { Log.w("MainViewModel", "updateAdvancedSettingRealtime: Unknown paramKey: $paramKey"); return }
        }
        if (valueChanged) {
            preferencesRepository.updateImageContentVersion()
            Log.d("MainViewModel", "Advanced setting '$paramKey' updated to $actualValue and saved.")
        }
    }

    open fun saveNonBitmapConfigAndUpdateVersion() {
        preferencesRepository.updateImageContentVersion()
    }

    override fun onCleared() {
        super.onCleared()
        originalBitmapForColorExtraction?.recycle(); originalBitmapForColorExtraction = null
    }

    fun onMainCategorySelectedInSheet(categoryId: String?) {
        _selectedMainCategoryIdInSheet.value = categoryId
        _subCategoryForAdjustmentIdInSheet.value = null
        _showCustomColorSliders.value = false
        _showStyleSelection.value = false
    }

    fun onSubCategoryForAdjustmentSelectedInSheet(subCategoryId: String?) {
        if (_subCategoryForAdjustmentIdInSheet.value == subCategoryId && subCategoryId != null) {
            _subCategoryForAdjustmentIdInSheet.value = null
        } else {
            _subCategoryForAdjustmentIdInSheet.value = subCategoryId
            if (subCategoryId != null) {
                _showCustomColorSliders.value = false
                _showStyleSelection.value = false
            }
        }
    }

    open fun toggleCustomColorSlidersVisibility() {
        val newVisibility = !_showCustomColorSliders.value
        _showCustomColorSliders.value = newVisibility
        if (newVisibility) {
            _subCategoryForAdjustmentIdInSheet.value = null
            _showStyleSelection.value = false
        }
    }

    fun toggleStyleSelectionView() {
        val newVisibility = !_showStyleSelection.value
        _showStyleSelection.value = newVisibility
        if (newVisibility) {
            _subCategoryForAdjustmentIdInSheet.value = null
            _showCustomColorSliders.value = false
        }
    }

    fun selectP1Style(styleType: Int) {
        updateP1StyleType(styleType)
        _showStyleSelection.value = false
        _subCategoryForAdjustmentIdInSheet.value = null
    }
    open fun toggleStyleBMasksFlip() {
        val newState = !(_styleBMasksHorizontallyFlipped.value ?: WallpaperConfigConstants.DEFAULT_STYLE_B_MASKS_HORIZONTALLY_FLIPPED)
        _styleBMasksHorizontallyFlipped.value = newState
        preferencesRepository.saveBooleanSetting(WallpaperConfigConstants.KEY_STYLE_B_MASKS_HORIZONTALLY_FLIPPED, newState)
        preferencesRepository.updateImageContentVersion()
        Log.d("MainViewModel", "Style B Masks Horizontally Flipped toggled to: $newState")
    }
}