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
import kotlin.math.abs // 确保导入

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val preferencesRepository: WallpaperPreferencesRepository =
        WallpaperPreferencesRepository(application)

    // --- LiveData for UI State (主配置值) ---
    private val _selectedImageUri = MutableLiveData<Uri?>()
    val selectedImageUri: LiveData<Uri?> get() = _selectedImageUri

    private val _selectedBackgroundColor = MutableLiveData<Int>()
    val selectedBackgroundColor: LiveData<Int> get() = _selectedBackgroundColor

    private val _page1ImageHeightRatio = MutableLiveData<Float>()
    val page1ImageHeightRatio: LiveData<Float> get() = _page1ImageHeightRatio

    private val _p1FocusX = MutableLiveData<Float>()
    val p1FocusX: LiveData<Float> get() = _p1FocusX
    private val _p1FocusY = MutableLiveData<Float>()
    val p1FocusY: LiveData<Float> get() = _p1FocusY

    private val _p1ContentScaleFactor = MutableLiveData<Float>() // 新增：P1 内容的缩放因子
    val p1ContentScaleFactor: LiveData<Float> get() = _p1ContentScaleFactor

    private val _toastMessage = MutableLiveData<Event<String>>()
    val toastMessage: LiveData<Event<String>> get() = _toastMessage

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> get() = _isLoading

    private val _colorPalette = MutableLiveData<List<Int>>()
    val colorPalette: LiveData<List<Int>> get() = _colorPalette

    private var originalBitmapForColorExtraction: Bitmap? = null

    // --- P1编辑模式状态 ---
    private val _isP1EditMode = MutableLiveData<Boolean>(false)
    val isP1EditMode: LiveData<Boolean> get() = _isP1EditMode
    // --- 移除了 originalEdit... 和 tempP1... 变量 ---

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

    fun toggleP1EditMode() {
        val currentlyEditing = _isP1EditMode.value ?: false
        _isP1EditMode.value = !currentlyEditing
        if (currentlyEditing) {
            Log.d("MainViewModel", "Exiting P1 Edit Mode. Changes were saved real-time.")
        } else {
            Log.d("MainViewModel", "Entering P1 Edit Mode.")
        }
    }

    fun updateP1ConfigRealtime(normX: Float, normY: Float, heightRatio: Float, contentScale: Float) {
        if (_isP1EditMode.value != true) {
            // 通常此回调只应在编辑模式下由PreviewView触发
            // Log.w("MainViewModel", "updateP1ConfigRealtime called when not in P1 Edit Mode. Current values will be updated if different.")
            // 如果PreviewView在非编辑模式下也可能回调（例如初始化同步），则需要确保值确实不同才更新
        }

        var configActuallyChanged = false

        val newNormX = normX.coerceIn(0f, 1f)
        if (abs((_p1FocusX.value ?: WallpaperConfigConstants.DEFAULT_P1_FOCUS_X) - newNormX) > 0.0001f) {
            _p1FocusX.value = newNormX
            preferencesRepository.setP1FocusX(newNormX)
            configActuallyChanged = true
        }

        val newNormY = normY.coerceIn(0f, 1f)
        if (abs((_p1FocusY.value ?: WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y) - newNormY) > 0.0001f) {
            _p1FocusY.value = newNormY
            preferencesRepository.setP1FocusY(newNormY)
            configActuallyChanged = true
        }

        // 如果焦点X或Y任一改变, 确保调用 setP1Focus 以便统一处理（如果setP1Focus有特殊逻辑，如更新版本）
        // 但由于我们下面会统一更新版本，这里仅确保 SharedPreferences 中的 X 和 Y 被设置。
        // 如果 setP1FocusX/Y 不分别存在，则需要在这里组合调用 setP1Focus。

        val newHeightRatio = heightRatio.coerceIn(
            WallpaperConfigConstants.MIN_HEIGHT_RATIO,
            WallpaperConfigConstants.MAX_HEIGHT_RATIO
        )
        if (abs((_page1ImageHeightRatio.value ?: WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO) - newHeightRatio) > 0.0001f) {
            _page1ImageHeightRatio.value = newHeightRatio
            preferencesRepository.setPage1ImageHeightRatio(newHeightRatio)
            configActuallyChanged = true
        }

        val newContentScale = contentScale.coerceIn(
            WallpaperConfigConstants.DEFAULT_P1_CONTENT_SCALE_FACTOR, // Min scale
            4.0f // Max scale (should match WallpaperPreviewView's p1UserMaxScaleFactorRelativeToCover)
        )
        if (abs((_p1ContentScaleFactor.value ?: WallpaperConfigConstants.DEFAULT_P1_CONTENT_SCALE_FACTOR) - newContentScale) > 0.0001f) {
            _p1ContentScaleFactor.value = newContentScale
            preferencesRepository.setP1ContentScaleFactor(newContentScale)
            configActuallyChanged = true
        }

        if (configActuallyChanged) {
            Log.d("MainViewModel", "P1 Config REALTIME SAVED: F=(${_p1FocusX.value},${_p1FocusY.value}), H=${_page1ImageHeightRatio.value}, S=${_p1ContentScaleFactor.value}")
            preferencesRepository.updateImageContentVersion()
        }
    }

    fun updatePage1ImageHeightRatio(newRatio: Float) { // For legacy buttons if still used
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

    fun updateP1Focus(focusX: Float, focusY: Float) { // For potential external programmatic changes
        if (_isP1EditMode.value == true) {
            Log.i("MainViewModel", "In P1 Edit Mode, focus changes via gesture. Ignoring legacy call.")
            return
        }
        val clampedX = focusX.coerceIn(0f, 1f); val clampedY = focusY.coerceIn(0f, 1f)
        if (_p1FocusX.value != clampedX || _p1FocusY.value != clampedY) {
            _p1FocusX.value = clampedX; _p1FocusY.value = clampedY
            preferencesRepository.setP1Focus(clampedX, clampedY) // Assumes setP1Focus updates version
        }
    }

    fun updateSelectedBackgroundColor(color: Int) {
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

                    preferencesRepository.resetSettingsForNewImage(internalFileUri) // Resets focus, scale, and updates version
                    preferencesRepository.setPage1ImageHeightRatio(defHeightRatio) // Save height separately
                    // resetSettingsForNewImage already updates version, ensure no double update if setPage1ImageHeightRatio also updates.
                    // For safety, if setPage1ImageHeightRatio also updates version, this might be one place where MainViewModel
                    // directly calls updateImageContentVersion() after all defaults are set.
                    // However, resetSettingsForNewImage is the primary "new content" event.

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
                if (internalFile.exists()) internalFile.delete()
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
            } ?: run { // bitmapForPalette is null
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

    fun saveNonBitmapConfigAndUpdateVersion() {
        preferencesRepository.updateImageContentVersion()
    }

    override fun onCleared() {
        super.onCleared()
        originalBitmapForColorExtraction?.recycle(); originalBitmapForColorExtraction = null
        Log.d("MainViewModel", "ViewModel cleared.")
    }
}