package com.example.h2wallpaper

import android.app.Application
import android.content.Context // Context 仍然可能被间接使用，例如 FileProvider
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

// 导入 WallpaperConfigConstants 对象
import com.example.h2wallpaper.WallpaperConfigConstants
import java.io.OutputStream

// FocusParams 在这个 ViewModel 中目前不直接使用其成员，所以不需要单独导入

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // 依赖 WallpaperPreferencesRepository
    private val preferencesRepository: WallpaperPreferencesRepository =
        WallpaperPreferencesRepository(application)

    // --- LiveData for UI State ---
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

    private val _scrollSensitivity = MutableLiveData<Float>() // 示例，如果需要ViewModel控制
    val scrollSensitivity: LiveData<Float> get() = _scrollSensitivity

    private val _toastMessage = MutableLiveData<Event<String>>()
    val toastMessage: LiveData<Event<String>> get() = _toastMessage

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> get() = _isLoading

    private val _colorPalette = MutableLiveData<List<Int>>()
    val colorPalette: LiveData<List<Int>> get() = _colorPalette

    private var originalBitmapForColorExtraction: Bitmap? = null


    init {
        loadInitialPreferences()
    }

    private fun loadInitialPreferences() {
        _selectedImageUri.value = preferencesRepository.getSelectedImageUri()
        _selectedBackgroundColor.value = preferencesRepository.getSelectedBackgroundColor()
        _page1ImageHeightRatio.value = preferencesRepository.getPage1ImageHeightRatio()
        _p1FocusX.value = preferencesRepository.getP1FocusX()
        _p1FocusY.value = preferencesRepository.getP1FocusY()
        _scrollSensitivity.value = preferencesRepository.getScrollSensitivity() // 示例

        viewModelScope.launch {
            val currentUri = _selectedImageUri.value
            if (currentUri != null) {
                try {
                    extractColorsFromUri(currentUri, isNewImage = false)
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Error extracting colors on init: ${e.message}")
                    setDefaultColorPalette()
                }
            } else {
                setDefaultColorPalette()
            }
        }
    }

    fun updatePage1ImageHeightRatio(newRatio: Float) {
        val clampedRatio = newRatio.coerceIn(
            WallpaperConfigConstants.MIN_HEIGHT_RATIO,
            WallpaperConfigConstants.MAX_HEIGHT_RATIO
        )
        if (_page1ImageHeightRatio.value != clampedRatio) {
            _page1ImageHeightRatio.value = clampedRatio
            preferencesRepository.setPage1ImageHeightRatio(clampedRatio)
        }
    }

    fun updateP1Focus(focusX: Float, focusY: Float) {
        _p1FocusX.value = focusX
        _p1FocusY.value = focusY
        preferencesRepository.setP1Focus(focusX, focusY)
    }

    fun updateSelectedBackgroundColor(color: Int) {
        _selectedBackgroundColor.value = color
        preferencesRepository.setSelectedBackgroundColor(color)
    }

    fun handleImageSelectionResult(uri: Uri?) {
        if (uri != null) {
            _isLoading.value = true
            viewModelScope.launch {
                val internalFileUri = saveImageToInternalAppStorage(uri) // 这个方法内部获取 context
                _isLoading.value = false
                if (internalFileUri != null) {
                    val oldUri = _selectedImageUri.value
                    if (oldUri != null && oldUri != internalFileUri) {
                        deleteInternalImage(oldUri)
                    }
                    // 更新 LiveData
                    _selectedImageUri.value = internalFileUri
                    _p1FocusX.value = WallpaperConfigConstants.DEFAULT_P1_FOCUS_X
                    _p1FocusY.value = WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y

                    // 通过 Repository 更新 SharedPreferences
                    preferencesRepository.resetSettingsForNewImage(internalFileUri)

                    try {
                        extractColorsFromUri(internalFileUri, isNewImage = true)
                    } catch (e: Exception) {
                        Log.e(
                            "MainViewModel",
                            "Error extracting colors after new image: ${e.message}"
                        )
                        _toastMessage.value = Event("颜色提取失败: ${e.message}")
                        setDefaultColorPalette() // 确保在出错时也有颜色板
                    }
                } else {
                    _toastMessage.value = Event("图片复制失败")
                }
            }
        } else {
            _toastMessage.value = Event("图片选择失败 (No data URI)")
        }
    }

    private suspend fun saveImageToInternalAppStorage(sourceUri: Uri): Uri? =
        withContext(Dispatchers.IO) {
            val context = getApplication<Application>().applicationContext
            var inputStream: InputStream? = null
            var outputStream: OutputStream? = null
            try {
                inputStream = context.contentResolver.openInputStream(sourceUri)
                if (inputStream == null) {
                    Log.e(
                        "MainViewModel",
                        "Failed to open input stream from source URI: $sourceUri"
                    )
                    return@withContext null
                }
                val imageDir = File(context.filesDir, "wallpaper_images") // 与 Repository 中路径一致
                if (!imageDir.exists()) {
                    imageDir.mkdirs()
                }
                val internalFile =
                    File(imageDir, "h2_wallpaper_internal_image.jpg") // 与 Repository 中文件名一致
                if (internalFile.exists()) {
                    internalFile.delete() // 删除旧文件，确保FileProvider URI不会指向旧内容
                }
                outputStream = FileOutputStream(internalFile)
                val buffer = ByteArray(4 * 1024)
                var read: Int
                while (inputStream.read(buffer).also { read = it } != -1) {
                    outputStream.write(buffer, 0, read)
                }
                outputStream.flush()
                return@withContext FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    internalFile
                )
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error saving image to internal storage", e)
                return@withContext null
            } finally {
                try {
                    inputStream?.close()
                    outputStream?.close()
                } catch (ioe: IOException) {
                    Log.e("MainViewModel", "Error closing streams", ioe)
                }
            }
        }

    private suspend fun deleteInternalImage(internalFileUri: Uri) = withContext(Dispatchers.IO) {
        val context = getApplication<Application>().applicationContext
        if (internalFileUri.scheme == "content" && internalFileUri.authority == "${context.packageName}.provider") {
            val imageDir = File(context.filesDir, "wallpaper_images")
            val internalFile = File(imageDir, "h2_wallpaper_internal_image.jpg")
            if (internalFile.exists()) {
                if (internalFile.delete()) Log.i(
                    "MainViewModel",
                    "Deleted old internal image file: ${internalFile.path}"
                )
                else Log.w(
                    "MainViewModel",
                    "Failed to delete old internal image file: ${internalFile.path}"
                )
            }
        } else if (internalFileUri.scheme == "file") {
            val filePath = internalFileUri.path
            if (filePath != null) {
                val file = File(filePath)
                if (file.exists() && file.isFile &&
                    file.parentFile?.name == "wallpaper_images" &&
                    file.parentFile?.parentFile == context.filesDir &&
                    file.name == "h2_wallpaper_internal_image.jpg"
                ) {
                    if (file.delete()) Log.i(
                        "MainViewModel",
                        "Deleted old internal image file: $filePath"
                    )
                    else Log.w(
                        "MainViewModel",
                        "Failed to delete old internal image file: $filePath"
                    )
                }
            }
        }
    }

    private suspend fun extractColorsFromUri(uri: Uri, isNewImage: Boolean) {
        val context = getApplication<Application>().applicationContext
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val options = BitmapFactory.Options()
            options.inSampleSize = 2
            originalBitmapForColorExtraction?.recycle()
            originalBitmapForColorExtraction =
                BitmapFactory.decodeStream(inputStream, null, options)
            inputStream?.close()

            originalBitmapForColorExtraction?.let { bitmap ->
                Palette.from(bitmap).generate { palette ->
                    val swatches = listOfNotNull(
                        palette?.dominantSwatch, palette?.vibrantSwatch, palette?.mutedSwatch,
                        palette?.lightVibrantSwatch, palette?.darkVibrantSwatch,
                        palette?.lightMutedSwatch, palette?.darkMutedSwatch
                    ).distinctBy { it.rgb }.take(8)

                    val extractedColors = swatches.map { it.rgb }
                    _colorPalette.postValue(extractedColors.ifEmpty { getDefaultColorList() })

                    val currentBgColor = _selectedBackgroundColor.value
                    if (isNewImage || currentBgColor == WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR ||
                        !extractedColors.contains(currentBgColor) || extractedColors.size == 1
                    ) {
                        extractedColors.firstOrNull()?.let {
                            _selectedBackgroundColor.postValue(it)
                            preferencesRepository.setSelectedBackgroundColor(it)
                        } ?: run {
                            if (isNewImage) { //只有在新图片且无法提取颜色时才设为默认
                                _selectedBackgroundColor.postValue(WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR)
                                preferencesRepository.setSelectedBackgroundColor(
                                    WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR
                                )
                            }
                        }
                    }
                }
            } ?: run {
                Log.e(
                    "MainViewModel",
                    "Failed to decode bitmap for color extraction from URI: $uri"
                )
                if (isNewImage) handleImageAccessFailed(uri, "图片加载失败 (颜色提取)")
                setDefaultColorPalette() // 确保UI有颜色板
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Exception loading image for color extraction from URI: $uri", e)
            if (isNewImage) handleImageAccessFailed(uri, "图片加载异常 (颜色提取)")
            setDefaultColorPalette() // 确保UI有颜色板
        }
    }

    private fun getDefaultColorList(): List<Int> {
        return listOf(
            Color.GRAY,
            Color.DKGRAY,
            WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR,
            Color.WHITE,
            Color.BLACK
        )
    }

    private fun setDefaultColorPalette() {
        val defaultColors = getDefaultColorList()
        _colorPalette.postValue(defaultColors)
        val currentSelectedColor = _selectedBackgroundColor.value
        if (_selectedImageUri.value == null || currentSelectedColor == null || !defaultColors.contains(
                currentSelectedColor
            )
        ) {
            _selectedBackgroundColor.postValue(WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR)
            // 如果ViewModel的init中已经加载了，这里可能不需要重复保存，除非逻辑上确实需要强制设回默认
            // preferencesRepository.setSelectedBackgroundColor(WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR)
        }
    }

    fun handleImageAccessFailed(uriFailed: Uri?, message: String = "图片访问失败") {
        _toastMessage.postValue(Event(message))
        _selectedImageUri.postValue(null)
        preferencesRepository.removeImageUri() // 通过仓库类移除
        originalBitmapForColorExtraction?.recycle()
        originalBitmapForColorExtraction = null
        setDefaultColorPalette()
        preferencesRepository.updateImageContentVersion() // 图片移除也算内容变化
    }

    // 当其他（非图片，非由ViewModel直接控制的LiveData）配置通过SettingsActivity改变后，
    // MainActivity在设置壁纸前调用此方法，以确保内容版本更新。
    fun saveNonBitmapConfigAndUpdateVersion() {
        preferencesRepository.updateImageContentVersion()
    }

    override fun onCleared() {
        super.onCleared()
        originalBitmapForColorExtraction?.recycle()
        originalBitmapForColorExtraction = null
        Log.d("MainViewModel", "ViewModel cleared and resources released.")
    }
}

// Event class (如果尚未在单独文件中定义)
/*
open class Event<out T>(private val content: T) {
    private var hasBeenHandled = false
    fun getContentIfNotHandled(): T? {
        return if (hasBeenHandled) null else {
            hasBeenHandled = true
            content
        }
    }
    fun peekContent(): T = content
}
*/