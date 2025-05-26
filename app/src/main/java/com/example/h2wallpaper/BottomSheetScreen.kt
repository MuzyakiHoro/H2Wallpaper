// app/src/main/java/com/example/h2wallpaper/BottomSheetScreen.kt
package com.example.h2wallpaper

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.h2wallpaper.ui.theme.H2WallpaperTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt


// --- 数据模型 (与之前一致) ---
data class SubCategory(
    val id: String,
    val name: String,
    val type: String = "action"
)

data class MainCategory(
    val id: String,
    val name: String,
    val subCategories: List<SubCategory>
)

val mainCategoriesData = listOf(
    MainCategory("cat_general", "通用", listOf(
        SubCategory("sub_select_image", "选择图片", type = "action"),
        SubCategory("sub_bg_color", "背景颜色", type = "color_picker"),
        SubCategory("sub_apply_wallpaper", "应用壁纸", type = "action"),
        SubCategory("sub_advanced_settings", "更多高级设置", type = "action")
    )),
    MainCategory("cat_p1_foreground", "P1 前景", listOf(
        SubCategory("p1_customize_action", "调整P1图片", type = "action"),
        SubCategory(WallpaperConfigConstants.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT, "底部融入", type = "parameter_slider"),
        SubCategory(WallpaperConfigConstants.KEY_P1_SHADOW_RADIUS, "投影半径", type = "parameter_slider"),
        SubCategory(WallpaperConfigConstants.KEY_P1_SHADOW_DY, "投影Y偏移", type = "parameter_slider"),
        SubCategory(WallpaperConfigConstants.KEY_P1_SHADOW_DX, "投影X偏移", type = "parameter_slider"),
    )),
    MainCategory("cat_background_effects", "背景效果", listOf(
        SubCategory(WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS, "模糊半径", type = "parameter_slider"),
        SubCategory(WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR, "模糊降采样", type = "parameter_slider"),
        SubCategory(WallpaperConfigConstants.KEY_BLUR_ITERATIONS, "模糊迭代", type = "parameter_slider")
    )),
    MainCategory("cat_scroll_transitions", "滚动与过渡", listOf(
        SubCategory(WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY, "滚动灵敏度", type = "parameter_slider"),
        SubCategory(WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO, "P1 淡出", type = "parameter_slider"),
        SubCategory(WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO, "P2 淡入", type = "parameter_slider"),
        SubCategory(WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET, "背景初始偏移", type = "parameter_slider")
    ))
)

// --- MainActivityActions 接口 (与之前一致) ---
interface MainActivityActions {
    fun requestReadMediaImagesPermission()
    fun startSettingsActivity()
    fun promptToSetWallpaper()
}

enum class AdjustmentAreaState { PLACEHOLDER, SLIDER, COLOR_PICKER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigSheetContent(
    viewModel: MainViewModel, // ViewModel 现在是必须的
    activityActions: MainActivityActions,
    onHideSheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // prefs 仍然可以用于读取范围等元数据，但不再用于直接写入受ViewModel管理的参数
    val prefs = remember { context.getSharedPreferences(WallpaperConfigConstants.PREFS_NAME, Context.MODE_PRIVATE) }

    val selectedMainCategoryId by viewModel.selectedMainCategoryIdInSheet.collectAsState()
    val subCategoryForAdjustmentId by viewModel.subCategoryForAdjustmentIdInSheet.collectAsState()

    val selectedMainCategory = remember(selectedMainCategoryId) {
        mainCategoriesData.find { it.id == selectedMainCategoryId } ?: mainCategoriesData.firstOrNull()
    }
    val subCategoryForAdjustment = remember(subCategoryForAdjustmentId, selectedMainCategory) {
        selectedMainCategory?.subCategories?.find { it.id == subCategoryForAdjustmentId }
    }

    val isP1EditMode by viewModel.isP1EditMode.observeAsState(initial = false)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            val currentAdjustmentCategory = subCategoryForAdjustment
            val areaState = if (isP1EditMode) {
                AdjustmentAreaState.PLACEHOLDER
            } else {
                when (currentAdjustmentCategory?.type) {
                    "parameter_slider" -> AdjustmentAreaState.SLIDER
                    "color_picker" -> AdjustmentAreaState.COLOR_PICKER
                    else -> AdjustmentAreaState.PLACEHOLDER
                }
            }

            Crossfade(
                targetState = areaState,
                label = "AdjustmentAreaCrossfade"
            ) { state ->
                when (state) {
                    AdjustmentAreaState.SLIDER -> {
                        if (currentAdjustmentCategory != null) {
                            ParameterAdjustmentSection(
                                viewModel = viewModel, // 传递 viewModel
                                subCategory = currentAdjustmentCategory,
                                keyOfParam = currentAdjustmentCategory.id
                                // onFinalValueChange 移除了，因为更新通过 onValueChange 和 viewModel 处理
                            )
                        } else {
                            PlaceholderForAdjustmentArea(text = if (isP1EditMode && viewModel.selectedImageUri.value != null) "P1图片调整模式已激活" else "选择下方参数项进行调整")
                        }
                    }
                    AdjustmentAreaState.COLOR_PICKER -> {
                        if (currentAdjustmentCategory != null) {
                            ColorSelectionSection(
                                viewModel = viewModel,
                                subCategory = currentAdjustmentCategory
                            )
                        } else {
                            PlaceholderForAdjustmentArea(text = if (isP1EditMode && viewModel.selectedImageUri.value != null) "P1图片调整模式已激活" else "选择下方参数项进行调整")
                        }
                    }
                    AdjustmentAreaState.PLACEHOLDER -> {
                        PlaceholderForAdjustmentArea(text = if (isP1EditMode && viewModel.selectedImageUri.value != null) "P1图片调整模式已激活" else "选择下方参数项进行调整")
                    }
                }
            }
        }

        MainCategoryTabs(
            categories = mainCategoriesData,
            selectedCategory = selectedMainCategory,
            onCategorySelected = { category ->
                if (!isP1EditMode) {
                    viewModel.onMainCategorySelectedInSheet(category.id)
                }
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            enabled = !isP1EditMode
        )

        SubCategoryDisplayArea(
            subCategories = selectedMainCategory?.subCategories ?: emptyList(),
            currentlyAdjusting = if (isP1EditMode) null else subCategoryForAdjustment,
            onSubCategoryClick = { subCategory ->
                if (subCategory.id == "p1_customize_action") {
                    if (viewModel.selectedImageUri.value != null) {
                        viewModel.toggleP1EditMode()
                        // 当切换P1编辑模式时，通常BottomSheet会隐藏，这里按需调用onHideSheet
                        // onHideSheet() // 如果希望切换P1编辑时关闭BottomSheet
                    } else {
                        Toast.makeText(context, context.getString(R.string.please_select_image_first_toast), Toast.LENGTH_SHORT).show()
                    }
                } else if (!isP1EditMode) {
                    if (subCategory.type == "parameter_slider" || subCategory.type == "color_picker") {
                        viewModel.onSubCategoryForAdjustmentSelectedInSheet(subCategory.id)
                    } else {
                        // 对于action类型的，清除调整区，然后执行操作
                        viewModel.onSubCategoryForAdjustmentSelectedInSheet(null)
                        handleSubCategoryAction(subCategory, viewModel, activityActions, context, onHideSheet)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            isP1EditModeActive = isP1EditMode
        )
    }
}


@Composable
private fun PlaceholderForAdjustmentArea(text: String = "选择下方参数项进行调整") {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 72.dp), // 稍微增高一点以匹配 Slider 区域的典型高度
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f)
        )
    }
}


@Composable
fun ColorSelectionSection(
    viewModel: MainViewModel,
    subCategory: SubCategory
) {
    val colorPalette by viewModel.colorPalette.observeAsState(initial = emptyList())
    val selectedColor by viewModel.selectedBackgroundColor.observeAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .defaultMinSize(minHeight = 64.dp), // 给予一些最小高度
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = subCategory.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        if (colorPalette.isEmpty()) {
            Text(
                "未提取到颜色或图片未选择",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f)
            )
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                items(colorPalette) { colorInt ->
                    val color = Color(colorInt)
                    val isSelected = colorInt == selectedColor
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(color, CircleShape)
                            .border(
                                width = if (isSelected) 2.5.dp else 0.dp, // 突出选中项
                                color = if (isSelected) Color.White else Color.Transparent,
                                shape = CircleShape
                            )
                            .clickable { viewModel.updateSelectedBackgroundColor(colorInt) }
                    )
                }
            }
        }
    }
}


@Composable
fun MainCategoryTabs(
    categories: List<MainCategory>,
    selectedCategory: MainCategory?,
    onCategorySelected: (MainCategory) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        userScrollEnabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(categories) { category ->
            val isSelected = category == selectedCategory
            TextButton(
                onClick = { if (enabled) onCategorySelected(category) },
                enabled = enabled,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = if (isSelected && enabled) Color.White.copy(alpha = 0.15f) else Color.Transparent,
                    contentColor = Color.White,
                    disabledContainerColor = Color.Transparent,
                    disabledContentColor = Color.White.copy(alpha = 0.3f)
                ),
                border = if (isSelected && enabled) BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)) else null,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    category.name,
                    fontWeight = if (isSelected && enabled) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 14.sp
                )
            }
        }
    }
}


@Composable
fun SubCategoryDisplayArea(
    subCategories: List<SubCategory>,
    currentlyAdjusting: SubCategory?,
    onSubCategoryClick: (SubCategory) -> Unit,
    modifier: Modifier = Modifier,
    isP1EditModeActive: Boolean
) {
    if (subCategories.isEmpty()) {
        Box(modifier = modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Text(
                "此分类下无具体选项",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
        return
    }

    LazyRow(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        userScrollEnabled = !isP1EditModeActive,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(subCategories) { subCategory ->
            val isP1CustomizeButton = subCategory.id == "p1_customize_action"
            val cardEnabled = if (isP1EditModeActive) isP1CustomizeButton else true

            SubCategoryCard(
                subCategory = subCategory,
                onClick = { if (cardEnabled) onSubCategoryClick(subCategory) },
                isHighlighted = !isP1EditModeActive && currentlyAdjusting == subCategory &&
                        (subCategory.type == "parameter_slider" || subCategory.type == "color_picker"),
                enabled = cardEnabled,
                displayText = if (isP1EditModeActive && isP1CustomizeButton) "完成P1调整" else subCategory.name,
                isP1EditModeActive = isP1EditModeActive
            )
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubCategoryCard(
    subCategory: SubCategory,
    onClick: () -> Unit,
    isHighlighted: Boolean,
    enabled: Boolean = true,
    displayText: String = subCategory.name,
    isP1EditModeActive: Boolean
) {
    val cardAlpha = if (enabled) 1f else 0.4f

    Card(
        onClick = { if (enabled) onClick() },
        enabled = enabled,
        modifier = Modifier
            .widthIn(min = 80.dp)
            .defaultMinSize(minHeight = 70.dp)
            .padding(vertical = 4.dp)
            .graphicsLayer(alpha = cardAlpha),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isHighlighted && enabled) Color.White.copy(alpha = 0.15f) else Color.Transparent,
            contentColor = Color.White,
            disabledContainerColor = Color.Transparent
        ),
        border = if (isHighlighted && enabled) BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)) else null
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val iconToShow = when (subCategory.id) {
                "p1_customize_action" -> if (isP1EditModeActive && enabled) Icons.Filled.CheckCircleOutline else Icons.Filled.AspectRatio
                "sub_select_image" -> Icons.Filled.Image
                "sub_bg_color" -> Icons.Filled.ColorLens
                "sub_apply_wallpaper" -> Icons.Filled.Wallpaper
                "sub_advanced_settings" -> Icons.Filled.Settings
                else -> {
                    when (subCategory.type) {
                        "parameter_slider" -> Icons.Filled.Tune
                        "color_picker" -> Icons.Filled.ColorLens
                        "action" -> Icons.Filled.ChevronRight
                        else -> Icons.Filled.ChevronRight
                    }
                }
            }
            Icon(
                imageVector = iconToShow,
                contentDescription = displayText,
                modifier = Modifier.size(28.dp),
                tint = Color.White
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = displayText,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                fontSize = 12.sp,
                color = Color.White,
                fontWeight = if (isHighlighted && enabled) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

@Composable
fun ParameterAdjustmentSection(
    viewModel: MainViewModel, // 接收 ViewModel
    subCategory: SubCategory,
    keyOfParam: String
) {
    // 从 ViewModel 获取对应参数的 LiveData，并观察其状态
    // 这里需要一个映射，将 keyOfParam 映射到 ViewModel 中的具体 LiveData
    // 例如，使用 LaunchedEffect 来获取初始值，或者 ViewModel 提供一个统一的获取方法
    val currentActualValueFromVM: State<Float?> = when (keyOfParam) {
        WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY -> viewModel.scrollSensitivity.observeAsState()
        WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO -> viewModel.p1OverlayFadeRatio.observeAsState()
        WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO -> viewModel.p2BackgroundFadeInRatio.observeAsState()
        WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET -> viewModel.backgroundInitialOffset.observeAsState()
        WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS -> viewModel.backgroundBlurRadius.observeAsState()
        WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR -> viewModel.blurDownscaleFactor.observeAsState()
        // KEY_BLUR_ITERATIONS 是 Int，需要特殊处理或在 ViewModel 中提供 Float LiveData
        WallpaperConfigConstants.KEY_P1_SHADOW_RADIUS -> viewModel.p1ShadowRadius.observeAsState()
        WallpaperConfigConstants.KEY_P1_SHADOW_DX -> viewModel.p1ShadowDx.observeAsState()
        WallpaperConfigConstants.KEY_P1_SHADOW_DY -> viewModel.p1ShadowDy.observeAsState()
        WallpaperConfigConstants.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT -> viewModel.p1ImageBottomFadeHeight.observeAsState()
        else -> remember { mutableStateOf(null) } // 对于未知key或需要Int的key，提供默认值
    }
    val currentBlurIterationsFromVM: State<Int?> = if (keyOfParam == WallpaperConfigConstants.KEY_BLUR_ITERATIONS) {
        viewModel.blurIterations.observeAsState()
    } else {
        remember { mutableStateOf(null) }
    }


    // prefs 仍然用于获取参数的min/max范围，因为这些通常是固定的
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(WallpaperConfigConstants.PREFS_NAME, Context.MODE_PRIVATE) }

    // 滑块的0f-1f位置状态，其初始值基于ViewModel中的实际值计算得来
    var currentSliderPosition by remember(keyOfParam, currentActualValueFromVM.value, currentBlurIterationsFromVM.value) {
        val actualValueToUse = if (keyOfParam == WallpaperConfigConstants.KEY_BLUR_ITERATIONS) {
            currentBlurIterationsFromVM.value?.toFloat()
        } else {
            currentActualValueFromVM.value
        }
        mutableStateOf(
            actualValueToUse?.let { mapActualValueToSliderPosition(keyOfParam, it, prefs) } ?: 0.5f // 默认中间位置
        )
    }

    // 用于在UI上显示格式化后的当前实际值
    val displayValueString = remember(keyOfParam, currentSliderPosition) {
        val actualVal = mapSliderPositionToActualValue(keyOfParam, currentSliderPosition, prefs)
        if (keyOfParam == WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY ||
            keyOfParam == WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO ||
            keyOfParam == WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO ||
            keyOfParam == WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET ||
            keyOfParam == WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR
        ) { String.format("%.2f", actualVal) }
        else { actualVal.roundToInt().toString() }
    }


    Column(modifier = Modifier.padding(horizontal = 0.dp, vertical = 4.dp).defaultMinSize(minHeight = 64.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                subCategory.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
            Text(
                displayValueString,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f)
            )
        }
        Slider(
            value = currentSliderPosition,
            onValueChange = { newSliderPos ->
                currentSliderPosition = newSliderPos // 更新本地滑块位置状态以驱动UI
                // 将新的滑块位置转换为实际参数值
                val actualParamValue = mapSliderPositionToActualValue(keyOfParam, newSliderPos, prefs)
                // 调用 ViewModel 的方法来更新配置和 SharedPreferences
                viewModel.updateAdvancedSettingRealtime(keyOfParam, actualParamValue)
            },
            valueRange = 0f..1f,
            steps = getStepsForParam(keyOfParam, prefs),
            modifier = Modifier.fillMaxWidth().padding(top = 0.dp),
            // onValueChangeFinished 移除了，因为实时更新已在 onValueChange 中处理
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White.copy(alpha = 0.8f),
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                activeTickColor = Color.White.copy(alpha = 0.5f),
                inactiveTickColor = Color.White.copy(alpha = 0.2f)
            )
        )
    }
}

// --- 辅助函数 ---

// 将实际参数值映射回滑块的 0f-1f 位置
fun mapActualValueToSliderPosition(paramKey: String, actualValue: Float, prefs: SharedPreferences): Float {
    val minRaw = getMinRawValueForParam(paramKey, prefs)
    val maxRaw = getMaxRawValueForParam(paramKey, prefs)
    return if ((maxRaw - minRaw) == 0f) 0f else ((actualValue - minRaw) / (maxRaw - minRaw)).coerceIn(0f, 1f)
}

// (getInitialSliderPosition 已被 mapActualValueToSliderPosition 替代了其主要用途)
// (mapSliderPositionToActualValue, getMinRawValueForParam, getMaxRawValueForParam, getStepsForParam 保持不变)

fun mapSliderPositionToActualValue(paramKey: String, sliderPosition: Float, prefs: SharedPreferences): Float {
    val minRaw = getMinRawValueForParam(paramKey, prefs)
    val maxRaw = getMaxRawValueForParam(paramKey, prefs)
    val value = minRaw + (maxRaw - minRaw) * sliderPosition

    // 根据参数键进行必要的精度调整或类型转换
    return when (paramKey) {
        // 对于需要特定小数位或整数的参数，可以在这里处理，但通常 ViewModel 保存时会处理
        // 例如，如果某个值总是希望是整数，可以在这里 .roundToInt().toFloat()
        // 但由于 ViewModel 的 updateAdvancedSettingRealtime 接收 Float，所以这里保持 Float
        else -> value
    }
}

fun getMinRawValueForParam(paramKey: String, prefs: SharedPreferences): Float {
    // XML preferences_wallpaper.xml 中的 app:min 定义了 SeekBarPreference 的整数最小值
    // 我们需要将其转换为实际的浮点最小值
    return when (paramKey) {
        WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY -> prefs.getInt("scrollSensitivity_min", 1) / 10.0f // 对应XML min="1"
        WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO -> prefs.getInt("p1OverlayFadeRatio_min", 1) / 100.0f // 对应XML min="1"
        WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO -> prefs.getInt("p2BackgroundFadeInRatio_min", 1) / 100.0f // 对应XML min="1"
        WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET -> prefs.getInt("backgroundInitialOffset_min", 0) / 10.0f // 对应XML min="0"
        WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS -> prefs.getInt("backgroundBlurRadius_min", 0).toFloat() // 对应XML min="0"
        WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR -> prefs.getInt("blurDownscaleFactor_min", 5) / 100.0f // 对应XML min="5"
        WallpaperConfigConstants.KEY_BLUR_ITERATIONS -> prefs.getInt("blurIterations_min", 1).toFloat() // 对应XML min="1"
        WallpaperConfigConstants.KEY_P1_SHADOW_RADIUS -> prefs.getInt("p1ShadowRadius_min", 0).toFloat() // 对应XML min="0"
        WallpaperConfigConstants.KEY_P1_SHADOW_DX -> prefs.getInt("p1ShadowDx_min", -20).toFloat() // 对应XML min="-20"
        WallpaperConfigConstants.KEY_P1_SHADOW_DY -> prefs.getInt("p1ShadowDy_min", 0).toFloat() // 对应XML min="0"
        WallpaperConfigConstants.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT -> prefs.getInt("p1ImageBottomFadeHeight_min", 0).toFloat() // 对应XML min="0"
        else -> 0f
    }
}

fun getMaxRawValueForParam(paramKey: String, prefs: SharedPreferences): Float {
    // XML preferences_wallpaper.xml 中的 android:max 定义了 SeekBarPreference 的整数最大值
    return when (paramKey) {
        WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY -> prefs.getInt("scrollSensitivity_max", 20) / 10.0f // 对应XML max="20"
        WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO -> prefs.getInt("p1OverlayFadeRatio_max", 100) / 100.0f // 对应XML max="100"
        WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO -> prefs.getInt("p2BackgroundFadeInRatio_max", 100) / 100.0f // 对应XML max="100"
        WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET -> prefs.getInt("backgroundInitialOffset_max", 10) / 10.0f // 对应XML max="10" (假设之前是1.0，所以10/10.0)
        WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS -> prefs.getInt("backgroundBlurRadius_max", 25).toFloat() // 对应XML max="25"
        WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR -> prefs.getInt("blurDownscaleFactor_max", 100) / 100.0f // 对应XML max="100"
        WallpaperConfigConstants.KEY_BLUR_ITERATIONS -> prefs.getInt("blurIterations_max", 3).toFloat() // 对应XML max="3" (之前代码示例是10，统一为XML)
        WallpaperConfigConstants.KEY_P1_SHADOW_RADIUS -> prefs.getInt("p1ShadowRadius_max", 20).toFloat() // 对应XML max="20"
        WallpaperConfigConstants.KEY_P1_SHADOW_DX -> prefs.getInt("p1ShadowDx_max", 20).toFloat() // 对应XML max="20"
        WallpaperConfigConstants.KEY_P1_SHADOW_DY -> prefs.getInt("p1ShadowDy_max", 20).toFloat() // 对应XML max="20"
        WallpaperConfigConstants.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT -> prefs.getInt("p1ImageBottomFadeHeight_max", 2560).toFloat() // 对应XML max="2560"
        else -> 1f
    }
}


fun getStepsForParam(paramKey: String, prefs: SharedPreferences): Int {
    // Steps for a Slider is (number of discrete points - 2) if range is [min, max]
    // Or (number of intervals - 1)
    // If a SeekBarPreference has max M and min N, it has (M - N) intervals if each step is 1.
    // So, steps for Compose Slider would be (M - N - 1) if integer steps.
    // For float ranges, it's more about desired granularity.
    // Let's try to match the granularity of SeekBarPreference.
    val minRawInt: Int
    val maxRawInt: Int

    when (paramKey) {
        WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY -> { minRawInt = 1; maxRawInt = 20 }
        WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO,
        WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO,
        WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR -> { minRawInt = 1; maxRawInt = 100 } // Assuming min was 1 in XML for these
        WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET -> { minRawInt = 0; maxRawInt = 10 }
        WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS -> { minRawInt = 0; maxRawInt = 25 }
        WallpaperConfigConstants.KEY_BLUR_ITERATIONS -> { minRawInt = 1; maxRawInt = 3 } // From XML
        WallpaperConfigConstants.KEY_P1_SHADOW_RADIUS -> { minRawInt = 0; maxRawInt = 20 }
        WallpaperConfigConstants.KEY_P1_SHADOW_DX -> { minRawInt = -20; maxRawInt = 20 }
        WallpaperConfigConstants.KEY_P1_SHADOW_DY -> { minRawInt = 0; maxRawInt = 20 }
        WallpaperConfigConstants.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT -> {
            // This has a large range, fewer steps might be better for UX
            // Max 2560. If we want ~100 steps: 2560 / 100 = 25.6. So, (2560-0)/step_size - 1
            // Let's aim for a reasonable number of steps, e.g., 63 for a step of 40px.
            // (2560 - 0) = 2560.  If steps = 63, then 64 intervals. 2560/64 = 40. This matches XML.
            minRawInt = 0; maxRawInt = 2560
            return if (maxRawInt > minRawInt) 63 else 0 // (maxRawInt / 40) - 1
        }
        else -> return 0 // Default to continuous if not specified
    }
    return (maxRawInt - minRawInt -1).coerceAtLeast(0) // (intervals - 1)
}


fun handleSubCategoryAction(
    subCategory: SubCategory,
    viewModel: MainViewModel,
    activityActions: MainActivityActions,
    context: Context,
    onHideSheet: () -> Unit
) {
    Log.d("ConfigSheet", "SubCategory Action: ${subCategory.name} (ID: ${subCategory.id})")
    val isP1EditMode = viewModel.isP1EditMode.value ?: false

    if (isP1EditMode && subCategory.id != "p1_customize_action") {
        Toast.makeText(context, "请先完成P1图片调整", Toast.LENGTH_SHORT).show()
        return
    }

    when (subCategory.id) {
        "sub_select_image" -> activityActions.requestReadMediaImagesPermission()
        "sub_bg_color" -> { // Action for color picker subcategory is handled by selecting it.
            // This direct action might be redundant if selection itself shows the picker.
            // However, if it's meant as a quick toggle or cycle:
            val currentColors = viewModel.colorPalette.value
            val currentBgColor = viewModel.selectedBackgroundColor.value
            if (!currentColors.isNullOrEmpty() && currentBgColor != null) {
                val currentIndex = currentColors.indexOf(currentBgColor)
                val nextIndex = if (currentIndex == -1 || currentIndex == currentColors.lastIndex) 0 else currentIndex + 1
                viewModel.updateSelectedBackgroundColor(currentColors[nextIndex])
                Toast.makeText(context, "背景色已切换 (若要更多选择请点选此项)", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "请先选择图片以提取颜色", Toast.LENGTH_SHORT).show()
            }
        }
        "sub_apply_wallpaper" -> {
            if (viewModel.selectedImageUri.value != null) {
                activityActions.promptToSetWallpaper()
                onHideSheet()
            } else {
                Toast.makeText(context, context.getString(R.string.please_select_image_first_toast), Toast.LENGTH_SHORT).show()
            }
        }
        "sub_advanced_settings" -> {
            activityActions.startSettingsActivity()
            onHideSheet()
        }
        "p1_customize_action" -> { // This case is also handled by onSubCategoryClick's main logic
            if (viewModel.selectedImageUri.value != null) {
                viewModel.toggleP1EditMode()
                // onHideSheet() // Decide if sheet should hide when entering P1 edit mode
            } else {
                Toast.makeText(context, context.getString(R.string.please_select_image_first_toast), Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigBottomSheetContainer(
    viewModel: MainViewModel,
    activityActions: MainActivityActions
) {
    val showSheet by viewModel.showConfigSheet.collectAsState()
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { true }
    )
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val scrollState = rememberScrollState()

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.closeConfigSheet() },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f), // 更透明一些
            contentColor = Color.White,
            scrimColor = Color.Black.copy(alpha = 0.1f), // 使用一点点背景遮罩
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(vertical = 16.dp)
                        .width(40.dp)
                        .height(4.dp)
                        .background(
                            color = Color.White.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(2.dp)
                        )
                )
            }
        ) {
            ConfigSheetContent(
                viewModel = viewModel,
                activityActions = activityActions,
                onHideSheet = {
                    scope.launch {
                        sheetState.hide()
                    }.invokeOnCompletion {
                        if (!sheetState.isVisible) {
                            viewModel.closeConfigSheet()
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = configuration.screenHeightDp.dp * 0.75f) // 稍微增加最大高度
                    .verticalScroll(scrollState)
                    .navigationBarsPadding()
            )
        }
    }
}


@Preview(showBackground = true, name = "配置选项内容预览 (Tabbed Horizontal Sub)")
@Composable
fun ConfigSheetContentTabbedPreview() {
    val context = LocalContext.current
    val previewSafeViewModel = remember {
        object : MainViewModel(context.applicationContext as Application) {
            override val selectedImageUri: LiveData<Uri?> = MutableLiveData(null)
            override val selectedBackgroundColor: LiveData<Int> = MutableLiveData(WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR)
            override val page1ImageHeightRatio: LiveData<Float> = MutableLiveData(WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO)
            override val colorPalette: LiveData<List<Int>> = MutableLiveData(listOf(0xFFDB4437.toInt(), 0xFF4285F4.toInt(), 0xFF0F9D58.toInt(), 0xFFF4B400.toInt()))
            override val isP1EditMode: LiveData<Boolean> = MutableLiveData(false)
            override val showConfigSheet: StateFlow<Boolean> = MutableStateFlow(true)

            // Mock LiveData for advanced settings for preview
            override val scrollSensitivity: LiveData<Float> = MutableLiveData(1.0f)
            override val p1OverlayFadeRatio: LiveData<Float> = MutableLiveData(0.5f)
            // ... add other mocked LiveData for preview as needed

            override fun toggleP1EditMode() { (this.isP1EditMode as MutableLiveData).value = !this.isP1EditMode.value!! }
            override fun updateSelectedBackgroundColor(color: Int) { (this.selectedBackgroundColor as MutableLiveData).value = color }
            override fun closeConfigSheet() { (this.showConfigSheet as MutableStateFlow).value = false }
            override fun saveNonBitmapConfigAndUpdateVersion() { Log.d("PreviewVM", "saveNonBitmapConfigAndUpdateVersion called") }
            override fun updateAdvancedSettingRealtime(paramKey: String, actualValue: Float) {
                Log.d("PreviewVM", "updateAdvancedSettingRealtime called for $paramKey with $actualValue")
                // In a real scenario, this would update the specific LiveData
                when (paramKey) {
                    WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY -> (this.scrollSensitivity as MutableLiveData).value = actualValue
                    WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO -> (this.p1OverlayFadeRatio as MutableLiveData).value = actualValue
                    // ...
                }
            }
        }
    }
    val fakeActions = object : MainActivityActions {
        override fun requestReadMediaImagesPermission() {}
        override fun startSettingsActivity() {}
        override fun promptToSetWallpaper() {}
    }

    H2WallpaperTheme(darkTheme = true) {
        Surface(
            modifier = Modifier.fillMaxHeight(0.75f), // Match container height
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        ) {
            ConfigSheetContent(
                viewModel = previewSafeViewModel,
                activityActions = fakeActions,
                onHideSheet = {}
            )
        }
    }
}