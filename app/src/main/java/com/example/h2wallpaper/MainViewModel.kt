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

open class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesRepository: WallpaperPreferencesRepository =
        WallpaperPreferencesRepository(application)

    // --- LiveData for UI State (主配置值) ---
    private val _selectedImageUri = MutableLiveData<Uri?>()
    open val selectedImageUri: LiveData<Uri?> get() = _selectedImageUri

    private val _selectedBackgroundColor = MutableLiveData<Int>()
    open val selectedBackgroundColor: LiveData<Int> get() = _selectedBackgroundColor

    private val _page1ImageHeightRatio = MutableLiveData<Float>()
    open val page1ImageHeightRatio: LiveData<Float> get() = _page1ImageHeightRatio

    private val _p1FocusX = MutableLiveData<Float>()
    val p1FocusX: LiveData<Float> get() = _p1FocusX
    private val _p1FocusY = MutableLiveData<Float>()
    val p1FocusY: LiveData<Float> get() = _p1FocusY

    private val _p1ContentScaleFactor = MutableLiveData<Float>()
    val p1ContentScaleFactor: LiveData<Float> get() = _p1ContentScaleFactor

    private val _toastMessage = MutableLiveData<Event<String>>()
    val toastMessage: LiveData<Event<String>> get() = _toastMessage

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> get() = _isLoading

    private val _colorPalette = MutableLiveData<List<Int>>()
    open val colorPalette: LiveData<List<Int>> get() = _colorPalette

    private var originalBitmapForColorExtraction: Bitmap? = null

    // --- P1编辑模式状态 ---
    private val _isP1EditMode = MutableLiveData<Boolean>(false)
    open val isP1EditMode: LiveData<Boolean> get() = _isP1EditMode

    // --- BottomSheet 配置状态 ---
    protected val _showConfigSheet = MutableStateFlow(false)
    open val showConfigSheet: StateFlow<Boolean> get() = _showConfigSheet

    private val _selectedMainCategoryIdInSheet = MutableStateFlow(mainCategoriesData.firstOrNull()?.id)
    val selectedMainCategoryIdInSheet: StateFlow<String?> = _selectedMainCategoryIdInSheet

    private val _subCategoryForAdjustmentIdInSheet = MutableStateFlow<String?>(null)
    val subCategoryForAdjustmentIdInSheet: StateFlow<String?> = _subCategoryForAdjustmentIdInSheet

    // --- 高级设置参数的 LiveData (类型应为业务逻辑层期望的类型, 通常是 Float) ---
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

    private val _blurDownscaleFactor = MutableLiveData<Float>() // ViewModel 中保持 Float
    val blurDownscaleFactor: LiveData<Float> get() = _blurDownscaleFactor

    private val _blurIterations = MutableLiveData<Int>()
    val blurIterations: LiveData<Int> get() = _blurIterations

    private val _p1ShadowRadius = MutableLiveData<Float>()
    val p1ShadowRadius: LiveData<Float> get() = _p1ShadowRadius

    private val _p1ShadowDx = MutableLiveData<Float>()
    val p1ShadowDx: LiveData<Float> get() = _p1ShadowDx

    private val _p1ShadowDy = MutableLiveData<Float>()
    val p1ShadowDy: LiveData<Float> get() = _p1ShadowDy

    private val _p1ImageBottomFadeHeight = MutableLiveData<Float>()
    val p1ImageBottomFadeHeight: LiveData<Float> get() = _p1ImageBottomFadeHeight

    // 新增：控制自定义颜色RGB滑块区域的显示状态
    private val _showCustomColorSliders = MutableStateFlow(false)
    open val showCustomColorSliders: StateFlow<Boolean> get() = _showCustomColorSliders
    // 新方法：切换自定义颜色滑块的显示，并确保与参数滑块互斥
    open fun toggleCustomColorSlidersVisibility() {
        val newVisibility = !_showCustomColorSliders.value
        _showCustomColorSliders.value = newVisibility
        if (newVisibility) {
            // 如果显示颜色滑块，则隐藏参数调整滑块
            _subCategoryForAdjustmentIdInSheet.value = null
        }
    }

    init {
        loadInitialPreferences()
    }

    private fun loadInitialPreferences() {
        _selectedImageUri.value = preferencesRepository.getSelectedImageUri()
        _selectedBackgroundColor.value = preferencesRepository.getSelectedBackgroundColor()
        _page1ImageHeightRatio.value = preferencesRepository.getPage1ImageHeightRatio()
        _p1FocusX.value = preferencesRepository.getP1FocusX()
        _p1FocusY.value = preferencesRepository.getP1FocusY()
        _p1ContentScaleFactor.value = preferencesRepository.getP1ContentScaleFactor()

        // 直接从 Repository 获取转换好的 Float 值
        _scrollSensitivity.value = preferencesRepository.getScrollSensitivity()
        _p1OverlayFadeRatio.value = preferencesRepository.getP1OverlayFadeRatio()
        _p2BackgroundFadeInRatio.value = preferencesRepository.getP2BackgroundFadeInRatio()
        _backgroundInitialOffset.value = preferencesRepository.getBackgroundInitialOffset()
        _backgroundBlurRadius.value = preferencesRepository.getBackgroundBlurRadius()
        _blurDownscaleFactor.value = preferencesRepository.getBlurDownscaleFactor() // Repository 已处理转换
        _blurIterations.value = preferencesRepository.getBlurIterations()
        _p1ShadowRadius.value = preferencesRepository.getP1ShadowRadius()
        _p1ShadowDx.value = preferencesRepository.getP1ShadowDx()
        _p1ShadowDy.value = preferencesRepository.getP1ShadowDy()
        _p1ImageBottomFadeHeight.value = preferencesRepository.getP1ImageBottomFadeHeight()

        viewModelScope.launch {
            val currentUri = _selectedImageUri.value
            if (currentUri != null) {
                try {
                    extractColorsFromUri(currentUri, isNewImage = false)
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Error extracting colors on init: ${e.message}")
                    launch(Dispatchers.Main) { setDefaultColorPalette() }
                }
            } else {
                launch(Dispatchers.Main) { setDefaultColorPalette() }
            }
        }
    }

    open fun toggleConfigSheetVisibility() {
        _showConfigSheet.update { currentState -> !currentState }
    }

    open fun openConfigSheet() {
        _showConfigSheet.value = true
    }

    open fun closeConfigSheet() {
        _showConfigSheet.value = false
    }

    open fun toggleP1EditMode() {
        val currentlyEditing = _isP1EditMode.value ?: false
        val newEditMode = !currentlyEditing
        _isP1EditMode.value = newEditMode

        if (newEditMode) {
            _subCategoryForAdjustmentIdInSheet.value = null
            Log.d("MainViewModel", "Entering P1 Edit Mode.")
        } else {
            Log.d("MainViewModel", "Exiting P1 Edit Mode. Changes were saved real-time.")
        }
    }

    fun updateP1ConfigRealtime(normX: Float, normY: Float, heightRatio: Float, contentScale: Float) {
        var configActuallyChanged = false

        val newNormX = normX.coerceIn(0f, 1f)
        if (abs((_p1FocusX.value ?: WallpaperConfigConstants.DEFAULT_P1_FOCUS_X) - newNormX) > 0.0001f) {
            _p1FocusX.value = newNormX
            preferencesRepository.setP1FocusX(newNormX) // Repository 直接存 Float
            configActuallyChanged = true
        }

        val newNormY = normY.coerceIn(0f, 1f)
        if (abs((_p1FocusY.value ?: WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y) - newNormY) > 0.0001f) {
            _p1FocusY.value = newNormY
            preferencesRepository.setP1FocusY(newNormY) // Repository 直接存 Float
            configActuallyChanged = true
        }

        val newHeightRatio = heightRatio.coerceIn(
            WallpaperConfigConstants.MIN_HEIGHT_RATIO,
            WallpaperConfigConstants.MAX_HEIGHT_RATIO
        )
        if (abs((_page1ImageHeightRatio.value ?: WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO) - newHeightRatio) > 0.0001f) {
            _page1ImageHeightRatio.value = newHeightRatio
            preferencesRepository.setPage1ImageHeightRatio(newHeightRatio) // Repository 直接存 Float
            configActuallyChanged = true
        }

        val newContentScale = contentScale.coerceIn(
            WallpaperConfigConstants.DEFAULT_P1_CONTENT_SCALE_FACTOR,
            4.0f // Assuming max scale factor for P1 content
        )
        if (abs((_p1ContentScaleFactor.value ?: WallpaperConfigConstants.DEFAULT_P1_CONTENT_SCALE_FACTOR) - newContentScale) > 0.0001f) {
            _p1ContentScaleFactor.value = newContentScale
            preferencesRepository.setP1ContentScaleFactor(newContentScale) // Repository 直接存 Float
            configActuallyChanged = true
        }

        if (configActuallyChanged) {
            Log.d("MainViewModel", "P1 Config REALTIME SAVED: F=(${_p1FocusX.value},${_p1FocusY.value}), H=${_page1ImageHeightRatio.value}, S=${_p1ContentScaleFactor.value}")
            preferencesRepository.updateImageContentVersion()
        }
    }

    fun updatePage1ImageHeightRatio(newRatio: Float) {
        if (_isP1EditMode.value == true) {
            Log.i("MainViewModel", "In P1 Edit Mode, height changes via gesture. Ignoring legacy call.")
            return
        }
        val clampedRatio = newRatio.coerceIn(WallpaperConfigConstants.MIN_HEIGHT_RATIO, WallpaperConfigConstants.MAX_HEIGHT_RATIO)
        if (_page1ImageHeightRatio.value != clampedRatio) {
            _page1ImageHeightRatio.value = clampedRatio
            preferencesRepository.setPage1ImageHeightRatio(clampedRatio)
            preferencesRepository.updateImageContentVersion()
        }
    }

    fun updateP1Focus(focusX: Float, focusY: Float) {
        if (_isP1EditMode.value == true) {
            Log.i("MainViewModel", "In P1 Edit Mode, focus changes via gesture. Ignoring legacy call.")
            return
        }
        val clampedX = focusX.coerceIn(0f, 1f); val clampedY = focusY.coerceIn(0f, 1f)
        if (_p1FocusX.value != clampedX || _p1FocusY.value != clampedY) {
            _p1FocusX.value = clampedX; _p1FocusY.value = clampedY
            preferencesRepository.setP1Focus(clampedX, clampedY)
            preferencesRepository.updateImageContentVersion() // Ensure version updates on programmatic P1 changes too
        }
    }

    open fun updateSelectedBackgroundColor(color: Int) {
        if (_selectedBackgroundColor.value != color) {
            _selectedBackgroundColor.value = color
            preferencesRepository.setSelectedBackgroundColor(color)
            preferencesRepository.updateImageContentVersion()
        }
    }

    fun handleImageSelectionResult(uri: Uri?) {
        if (_isP1EditMode.value == true) {
            _isP1EditMode.value = false
        }
        if (uri != null) {
            _isLoading.value = true
            viewModelScope.launch {
                val internalFileUri = saveImageToInternalAppStorage(uri)
                withContext(Dispatchers.Main) { _isLoading.value = false }

                if (internalFileUri != null) {
                    val oldUri = _selectedImageUri.value
                    if (oldUri != null && oldUri != internalFileUri) deleteInternalImage(oldUri)
                    _selectedImageUri.postValue(internalFileUri)

                    val defFocusX = WallpaperConfigConstants.DEFAULT_P1_FOCUS_X
                    val defFocusY = WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y
                    val defHeightRatio = WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO
                    val defContentScale = WallpaperConfigConstants.DEFAULT_P1_CONTENT_SCALE_FACTOR

                    _p1FocusX.postValue(defFocusX); _p1FocusY.postValue(defFocusY)
                    _page1ImageHeightRatio.postValue(defHeightRatio)
                    _p1ContentScaleFactor.postValue(defContentScale)

                    preferencesRepository.resetSettingsForNewImage(internalFileUri)
                    // Ensure these defaults are also set via repository methods if they aren't covered by resetSettingsForNewImage
                    preferencesRepository.setPage1ImageHeightRatio(defHeightRatio)
                    preferencesRepository.setP1ContentScaleFactor(defContentScale)


                    try {
                        extractColorsFromUri(internalFileUri, isNewImage = true)
                    } catch (e: Exception) {
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
                // if (internalFile.exists()) internalFile.delete() // Optional: Overwrite if exists
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
            val defFx = WallpaperConfigConstants.DEFAULT_P1_FOCUS_X; val defFy = WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y
            val defHr = WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO; val defCs = WallpaperConfigConstants.DEFAULT_P1_CONTENT_SCALE_FACTOR
            _p1FocusX.postValue(defFx); _p1FocusY.postValue(defFy); _page1ImageHeightRatio.postValue(defHr); _p1ContentScaleFactor.postValue(defCs)
            preferencesRepository.setP1Focus(defFx, defFy); preferencesRepository.setPage1ImageHeightRatio(defHr); preferencesRepository.setP1ContentScaleFactor(defCs)
            preferencesRepository.updateImageContentVersion()
        }
    }

    open fun updateAdvancedSettingRealtime(paramKey: String, actualValue: Float) {
        var valueChanged = false
        // Determine the correct scale factor for integer storage if needed
        // Or call appropriate repository method for direct float/int storage
        when (paramKey) {
            WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY -> {
                if (_scrollSensitivity.value != actualValue) {
                    _scrollSensitivity.value = actualValue
                    preferencesRepository.saveScaledFloatSettingAsInt(paramKey, actualValue, 10)
                    valueChanged = true
                }
            }
            WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO -> {
                if (_p1OverlayFadeRatio.value != actualValue) {
                    _p1OverlayFadeRatio.value = actualValue
                    preferencesRepository.saveScaledFloatSettingAsInt(paramKey, actualValue, 100)
                    valueChanged = true
                }
            }
            WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO -> {
                if (_p2BackgroundFadeInRatio.value != actualValue) {
                    _p2BackgroundFadeInRatio.value = actualValue
                    preferencesRepository.saveScaledFloatSettingAsInt(paramKey, actualValue, 100)
                    valueChanged = true
                }
            }
            WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET -> {
                if (_backgroundInitialOffset.value != actualValue) {
                    _backgroundInitialOffset.value = actualValue
                    preferencesRepository.saveScaledFloatSettingAsInt(paramKey, actualValue, 10)
                    valueChanged = true
                }
            }
            WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS -> {
                if (_backgroundBlurRadius.value != actualValue) {
                    _backgroundBlurRadius.value = actualValue
                    preferencesRepository.saveIntSetting(paramKey, actualValue.roundToInt())
                    valueChanged = true
                }
            }
            WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR -> { // actualValue is Float
                if (_blurDownscaleFactor.value != actualValue) {
                    _blurDownscaleFactor.value = actualValue
                    preferencesRepository.saveScaledFloatSettingAsInt(paramKey, actualValue, 100)
                    valueChanged = true
                }
            }
            WallpaperConfigConstants.KEY_BLUR_ITERATIONS -> { // actualValue is Float, convert to Int
                val intValue = actualValue.roundToInt()
                if (_blurIterations.value != intValue) {
                    _blurIterations.value = intValue
                    preferencesRepository.saveIntSetting(paramKey, intValue)
                    valueChanged = true
                }
            }
            WallpaperConfigConstants.KEY_P1_SHADOW_RADIUS -> {
                if (_p1ShadowRadius.value != actualValue) {
                    _p1ShadowRadius.value = actualValue
                    preferencesRepository.saveIntSetting(paramKey, actualValue.roundToInt())
                    valueChanged = true
                }
            }
            WallpaperConfigConstants.KEY_P1_SHADOW_DX -> {
                if (_p1ShadowDx.value != actualValue) {
                    _p1ShadowDx.value = actualValue
                    preferencesRepository.saveIntSetting(paramKey, actualValue.roundToInt())
                    valueChanged = true
                }
            }
            WallpaperConfigConstants.KEY_P1_SHADOW_DY -> {
                if (_p1ShadowDy.value != actualValue) {
                    _p1ShadowDy.value = actualValue
                    preferencesRepository.saveIntSetting(paramKey, actualValue.roundToInt())
                    valueChanged = true
                }
            }
            WallpaperConfigConstants.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT -> {
                if (_p1ImageBottomFadeHeight.value != actualValue) {
                    _p1ImageBottomFadeHeight.value = actualValue
                    preferencesRepository.saveIntSetting(paramKey, actualValue.roundToInt())
                    valueChanged = true
                }
            }
            else -> {
                Log.w("MainViewModel", "updateAdvancedSettingRealtime: Unknown paramKey: $paramKey")
                return
            }
        }

        if (valueChanged) {
            preferencesRepository.updateImageContentVersion()
            Log.d("MainViewModel", "Advanced setting '$paramKey' updated to $actualValue and saved via Repository. Version updated.")
        }
    }

    open fun saveNonBitmapConfigAndUpdateVersion() {
        preferencesRepository.updateImageContentVersion()
        Log.d("MainViewModel", "saveNonBitmapConfigAndUpdateVersion: Image content version explicitly updated.")
    }

    override fun onCleared() {
        super.onCleared()
        originalBitmapForColorExtraction?.recycle(); originalBitmapForColorExtraction = null
        Log.d("MainViewModel", "ViewModel cleared.")
    }

    fun onMainCategorySelectedInSheet(categoryId: String?) {
        _selectedMainCategoryIdInSheet.value = categoryId
        _subCategoryForAdjustmentIdInSheet.value = null
    }

    // 修改：当选择子分类进行参数调整时，应隐藏自定义颜色滑块
    fun onSubCategoryForAdjustmentSelectedInSheet(subCategoryId: String?) {
        if (_subCategoryForAdjustmentIdInSheet.value == subCategoryId && subCategoryId != null) {
            // 如果再次点击已选中的参数滑块项，则收起它
            _subCategoryForAdjustmentIdInSheet.value = null
            // _showCustomColorSliders.value = false // 不需要，因为参数调整和颜色调整是互斥的
        } else {
            _subCategoryForAdjustmentIdInSheet.value = subCategoryId
            if (subCategoryId != null) {
                // 如果选择了参数滑块项，则隐藏自定义颜色滑块
                _showCustomColorSliders.value = false
            }
        }
    }
}