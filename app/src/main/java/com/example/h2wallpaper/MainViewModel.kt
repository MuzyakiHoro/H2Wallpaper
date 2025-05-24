package com.example.h2wallpaper

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import androidx.core.content.FileProvider
import androidx.palette.graphics.Palette
import android.graphics.BitmapFactory
import android.util.Log
import kotlin.math.roundToInt

// 移除星号导入
// import com.example.h2wallpaper.WallpaperConfigConstants.*

// 显式导入 WallpaperConfigConstants 对象
import com.example.h2wallpaper.WallpaperConfigConstants
import java.io.OutputStream

// MainViewModel 目前不直接使用 FocusParams 的成员，所以不需要单独导入 FocusParams
// 如果将来需要，可以添加: import com.example.h2wallpaper.WallpaperConfigConstants.FocusParams

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // 在使用常量的地方，确保使用 WallpaperConfigConstants.CONSTANT_NAME
    private val prefs: SharedPreferences = application.getSharedPreferences(
        WallpaperConfigConstants.PREFS_NAME, // 修改这里
        Context.MODE_PRIVATE
    )

    // --- LiveData for UI State ---
    // 图片URI
    private val _selectedImageUri = MutableLiveData<Uri?>()
    val selectedImageUri: LiveData<Uri?> get() = _selectedImageUri

    // P1 背景色
    private val _selectedBackgroundColor = MutableLiveData<Int>()
    val selectedBackgroundColor: LiveData<Int> get() = _selectedBackgroundColor

    // P1 图片高度比例
    private val _page1ImageHeightRatio = MutableLiveData<Float>()
    val page1ImageHeightRatio: LiveData<Float> get() = _page1ImageHeightRatio

    // P1 图片焦点
    private val _p1FocusX = MutableLiveData<Float>()
    val p1FocusX: LiveData<Float> get() = _p1FocusX
    private val _p1FocusY = MutableLiveData<Float>()
    val p1FocusY: LiveData<Float> get() = _p1FocusY

    private val _scrollSensitivity = MutableLiveData<Float>()
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
        _selectedImageUri.value =
            prefs.getString(WallpaperConfigConstants.KEY_IMAGE_URI, null)?.let { Uri.parse(it) }
        _selectedBackgroundColor.value = prefs.getInt(
            WallpaperConfigConstants.KEY_BACKGROUND_COLOR,
            WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR
        )
        _page1ImageHeightRatio.value = prefs.getFloat(
            WallpaperConfigConstants.KEY_IMAGE_HEIGHT_RATIO,
            WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO
        )
        _p1FocusX.value = prefs.getFloat(
            WallpaperConfigConstants.KEY_P1_FOCUS_X,
            WallpaperConfigConstants.DEFAULT_P1_FOCUS_X
        )
        _p1FocusY.value = prefs.getFloat(
            WallpaperConfigConstants.KEY_P1_FOCUS_Y,
            WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y
        )

        _scrollSensitivity.value = prefs.getInt(
            WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY,
            WallpaperConfigConstants.DEFAULT_SCROLL_SENSITIVITY_INT
        ) / 10.0f
        // ... 加载其他需要的参数到对应的LiveData

        viewModelScope.launch {
            if (_selectedImageUri.value != null) {
                try {
                    extractColorsFromUri(_selectedImageUri.value!!, isNewImage = false)
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
            saveFloatPreference(WallpaperConfigConstants.KEY_IMAGE_HEIGHT_RATIO, clampedRatio)
        }
    }

    fun updateP1Focus(focusX: Float, focusY: Float) {
        _p1FocusX.value = focusX
        _p1FocusY.value = focusY
        val editor = prefs.edit()
        editor.putFloat(WallpaperConfigConstants.KEY_P1_FOCUS_X, focusX)
        editor.putFloat(WallpaperConfigConstants.KEY_P1_FOCUS_Y, focusY)
        editor.apply()
        updateContentVersion() // 焦点变化也应该更新内容版本，因为它影响P1图片裁剪
    }

    fun updateSelectedBackgroundColor(color: Int) {
        _selectedBackgroundColor.value = color
        saveIntPreference(WallpaperConfigConstants.KEY_BACKGROUND_COLOR, color)
    }

    fun handleImageSelectionResult(uri: Uri?) {
        if (uri != null) {
            _isLoading.value = true
            viewModelScope.launch {
                val internalFileUri = saveImageToInternalAppStorage(uri)
                _isLoading.value = false
                if (internalFileUri != null) {
                    val oldUri = _selectedImageUri.value
                    if (oldUri != null && oldUri != internalFileUri) {
                        deleteInternalImage(oldUri)
                    }
                    _selectedImageUri.value = internalFileUri
                    _p1FocusX.value = WallpaperConfigConstants.DEFAULT_P1_FOCUS_X
                    _p1FocusY.value = WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y
                    val editor = prefs.edit()
                    editor.putString(
                        WallpaperConfigConstants.KEY_IMAGE_URI,
                        internalFileUri.toString()
                    )
                    editor.putFloat(
                        WallpaperConfigConstants.KEY_P1_FOCUS_X,
                        WallpaperConfigConstants.DEFAULT_P1_FOCUS_X
                    )
                    editor.putFloat(
                        WallpaperConfigConstants.KEY_P1_FOCUS_Y,
                        WallpaperConfigConstants.DEFAULT_P1_FOCUS_Y
                    )
                    editor.putLong(
                        WallpaperConfigConstants.KEY_IMAGE_CONTENT_VERSION,
                        System.currentTimeMillis()
                    )
                    editor.apply()

                    try {
                        extractColorsFromUri(internalFileUri, isNewImage = true)
                    } catch (e: Exception) {
                        Log.e(
                            "MainViewModel",
                            "Error extracting colors after new image: ${e.message}"
                        )
                        _toastMessage.value = Event("颜色提取失败: ${e.message}")
                        setDefaultColorPalette()
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
                val imageDir = File(context.filesDir, "wallpaper_images")
                if (!imageDir.exists()) {
                    imageDir.mkdirs()
                }
                val internalFile = File(imageDir, "h2_wallpaper_internal_image.jpg")
                if (internalFile.exists()) {
                    internalFile.delete()
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
                        palette?.dominantSwatch,
                        palette?.vibrantSwatch,
                        palette?.mutedSwatch,
                        palette?.lightVibrantSwatch,
                        palette?.darkVibrantSwatch,
                        palette?.lightMutedSwatch,
                        palette?.darkMutedSwatch
                    ).distinctBy { it.rgb }.take(8)

                    val extractedColors = swatches.map { it.rgb }
                    _colorPalette.postValue(extractedColors.ifEmpty { getDefaultColorList() })

                    if (isNewImage || _selectedBackgroundColor.value == WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR ||
                        !extractedColors.contains(_selectedBackgroundColor.value) || extractedColors.size == 1
                    ) {
                        extractedColors.firstOrNull()?.let {
                            _selectedBackgroundColor.postValue(it)
                            saveIntPreference(WallpaperConfigConstants.KEY_BACKGROUND_COLOR, it)
                        } ?: run {
                            if (isNewImage) {
                                _selectedBackgroundColor.postValue(WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR)
                                saveIntPreference(
                                    WallpaperConfigConstants.KEY_BACKGROUND_COLOR,
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
                setDefaultColorPalette()
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Exception loading image for color extraction from URI: $uri", e)
            if (isNewImage) handleImageAccessFailed(uri, "图片加载异常 (颜色提取)")
            setDefaultColorPalette()
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
        if (_selectedImageUri.value == null || !defaultColors.contains(_selectedBackgroundColor.value)) {
            _selectedBackgroundColor.postValue(WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR)
            saveIntPreference(
                WallpaperConfigConstants.KEY_BACKGROUND_COLOR,
                WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR
            )
        }
    }

    fun handleImageAccessFailed(uriFailed: Uri?, message: String = "图片访问失败") {
        _toastMessage.postValue(Event(message))
        _selectedImageUri.postValue(null)
        prefs.edit().remove(WallpaperConfigConstants.KEY_IMAGE_URI).apply()
        originalBitmapForColorExtraction?.recycle()
        originalBitmapForColorExtraction = null
        setDefaultColorPalette()
    }

    private fun saveFloatPreference(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
        // 只有当关键的、影响壁纸服务视觉呈现的参数改变时才更新版本号
        if (key == WallpaperConfigConstants.KEY_IMAGE_HEIGHT_RATIO) {
            updateContentVersion()
        }
    }

    private fun saveIntPreference(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
        if (key == WallpaperConfigConstants.KEY_BACKGROUND_COLOR) {
            updateContentVersion()
        }
    }

    fun saveNonBitmapConfigAndUpdateVersion() {
        // 这个方法可以被MainActivity调用，当那些不直接在ViewModel中管理其LiveData的
        // SharedPreferences参数（例如通过SettingsActivity修改的参数）被确认需要
        // 刷新壁纸版本时。
        // 注意：如果所有配置都通过ViewModel的方法保存，并且这些方法内部都调用了updateContentVersion，
        // 那么这个公共方法可能不是必需的，除非MainActivity有其他方式保存配置。
        // 目前MainActivity的savePreferences方法是独立的，后续我们会把它也集成进来。
        updateContentVersion()
    }

    private fun updateContentVersion() {
        prefs.edit()
            .putLong(WallpaperConfigConstants.KEY_IMAGE_CONTENT_VERSION, System.currentTimeMillis())
            .apply()
        Log.d("MainViewModel", "Wallpaper content version updated.")
    }

    override fun onCleared() {
        super.onCleared()
        originalBitmapForColorExtraction?.recycle()
        originalBitmapForColorExtraction = null
    }
}

open class Event<out T>(private val content: T) {
    private var hasBeenHandled = false

    fun getContentIfNotHandled(): T? {
        return if (hasBeenHandled) {
            null
        } else {
            hasBeenHandled = true
            content
        }
    }

    fun peekContent(): T = content
}