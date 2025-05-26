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
import androidx.compose.material.icons.filled.AspectRatio // 示例图标
import androidx.compose.material.icons.filled.Brightness6 // 示例图标
import androidx.compose.material.icons.filled.CheckCircleOutline // 示例图标
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
import androidx.compose.ui.graphics.graphicsLayer // 确保导入
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
    MainCategory("cat_scroll_transitions", "滚动与过渡", listOf(
        SubCategory(WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY, "滚动灵敏度", type = "parameter_slider"),
        SubCategory(WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO, "P1 淡出", type = "parameter_slider"),
        SubCategory(WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO, "P2 淡入", type = "parameter_slider"),
        SubCategory(WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET, "背景初始偏移", type = "parameter_slider")
    )),
    MainCategory("cat_background_effects", "背景效果", listOf(
        SubCategory(WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS, "模糊半径", type = "parameter_slider"),
        SubCategory(WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR, "模糊降采样", type = "parameter_slider"),
        SubCategory(WallpaperConfigConstants.KEY_BLUR_ITERATIONS, "模糊迭代", type = "parameter_slider")
    )),
    MainCategory("cat_p1_foreground", "P1 前景", listOf(
        SubCategory("p1_customize_action", "调整P1图片", type = "action"),
        SubCategory(WallpaperConfigConstants.KEY_P1_SHADOW_RADIUS, "投影半径", type = "parameter_slider"),
        SubCategory(WallpaperConfigConstants.KEY_P1_SHADOW_DX, "投影X偏移", type = "parameter_slider"),
        SubCategory(WallpaperConfigConstants.KEY_P1_SHADOW_DY, "投影Y偏移", type = "parameter_slider"),
        SubCategory(WallpaperConfigConstants.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT, "底部融入", type = "parameter_slider")
    ))
)

// --- MainActivityActions 接口 (与之前一致) ---
interface MainActivityActions {
    fun requestReadMediaImagesPermission()
    fun startSettingsActivity()
    fun promptToSetWallpaper()
}

// enum class 定义移到文件顶部
enum class AdjustmentAreaState { PLACEHOLDER, SLIDER, COLOR_PICKER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigSheetContent(
    viewModel: MainViewModel,
    activityActions: MainActivityActions,
    onHideSheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
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

    // 这就是截图第130行报错的 Lambda 表达式
    val onSliderValueChangeFinished: (String, Float) -> Unit = { paramKey, newSliderPosition ->
        val editor = prefs.edit()
        val actualValue = mapSliderPositionToActualValue(paramKey, newSliderPosition, prefs) // 确保这个函数存在且签名正确
        when (paramKey) {
            WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY -> editor.putInt(paramKey, (actualValue * 10).roundToInt())
            WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO,
            WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO,
            WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR -> editor.putInt(paramKey, (actualValue * 100).roundToInt())
            WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET -> editor.putInt(paramKey, (actualValue * 10).roundToInt())
            WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS,
            WallpaperConfigConstants.KEY_BLUR_ITERATIONS,
            WallpaperConfigConstants.KEY_P1_SHADOW_RADIUS,
            WallpaperConfigConstants.KEY_P1_SHADOW_DX,
            WallpaperConfigConstants.KEY_P1_SHADOW_DY,
            WallpaperConfigConstants.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT -> editor.putInt(paramKey, actualValue.roundToInt())
            // 确保所有在 ParameterAdjustmentSection 中可能用到的 paramKey 都在这里有处理
        }
        editor.apply()
        Log.d("ConfigSheet", "Slider for $paramKey saved with actual value: $actualValue (slider: $newSliderPosition)")
        viewModel.saveNonBitmapConfigAndUpdateVersion()
    }

    Column(
        modifier = modifier // 这个 modifier 来自 ConfigBottomSheetContainer，包含高度限制和滚动
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
                            ParameterAdjustmentSection( // 调用 ParameterAdjustmentSection
                                subCategory = currentAdjustmentCategory,
                                keyOfParam = currentAdjustmentCategory.id,
                                prefs = prefs,
                                onFinalValueChange = { paramKey, finalSliderPos -> // 签名 (String, Float) -> Unit
                                    onSliderValueChangeFinished(paramKey, finalSliderPos) // 传递给 onSliderValueChangeFinished
                                }
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
                    } else {
                        Toast.makeText(context, context.getString(R.string.please_select_image_first_toast), Toast.LENGTH_SHORT).show()
                    }
                } else if (!isP1EditMode) {
                    if (subCategory.type == "parameter_slider" || subCategory.type == "color_picker") {
                        viewModel.onSubCategoryForAdjustmentSelectedInSheet(subCategory.id)
                    } else {
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

// --- PlaceholderForAdjustmentArea Composable (与之前一致) ---
@Composable
private fun PlaceholderForAdjustmentArea(text: String = "选择下方参数项进行调整") {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 40.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f)
        )
    }
}

// --- ColorSelectionSection Composable (与之前一致) ---
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
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = subCategory.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        if (colorPalette.isEmpty()) {
            Text(
                "未提取到颜色或图片未选择",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f)
            )
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                items(colorPalette) { colorInt ->
                    val color = Color(colorInt)
                    val isSelected = colorInt == selectedColor
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(color, CircleShape)
                            .border(
                                width = if (isSelected) 2.dp else 0.dp,
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


// --- MainCategoryTabs Composable (与之前一致) ---
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

// --- SubCategoryDisplayArea Composable (与之前一致) ---

@Composable
fun SubCategoryDisplayArea(
    subCategories: List<SubCategory>,
    currentlyAdjusting: SubCategory?,
    onSubCategoryClick: (SubCategory) -> Unit,
    modifier: Modifier = Modifier,
    isP1EditModeActive: Boolean // 这个参数已存在
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
                isP1EditModeActive = isP1EditModeActive // <--- 新增：传递参数
            )
        }
    }
}

// --- SubCategoryCard Composable (与之前一致) ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubCategoryCard(
    subCategory: SubCategory,
    onClick: () -> Unit,
    isHighlighted: Boolean,
    enabled: Boolean = true,
    displayText: String = subCategory.name,
    isP1EditModeActive: Boolean // <--- 新增参数
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
            // 现在可以安全使用 isP1EditModeActive
            val iconToShow = when (subCategory.id) {
                "p1_customize_action" -> if (isP1EditModeActive && enabled) Icons.Filled.CheckCircleOutline else Icons.Filled.AspectRatio
                "sub_select_image" -> Icons.Filled.Image
                "sub_bg_color" -> Icons.Filled.ColorLens
                "sub_apply_wallpaper" -> Icons.Filled.Wallpaper
                "sub_advanced_settings" -> Icons.Filled.Settings
                else -> {
                    when (subCategory.type) {
                        "parameter_slider" -> Icons.Filled.Tune
                        "color_picker" -> Icons.Filled.ColorLens // 确保这个 type 在 mainCategoriesData 中正确设置
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

// --- ParameterAdjustmentSection Composable (与之前一致) ---
@Composable
fun ParameterAdjustmentSection(
    subCategory: SubCategory,
    keyOfParam: String,
    prefs: SharedPreferences,
    onFinalValueChange: (paramKey: String, finalSliderPosition: Float) -> Unit
) {
    var internalSliderPosition by remember(keyOfParam) { // key 确保参数切换时 slider 重置
        mutableStateOf(getInitialSliderPosition(keyOfParam, prefs))
    }

    val currentValueDisplay = mapSliderPositionToActualValue(keyOfParam, internalSliderPosition, prefs)

    Column(modifier = Modifier.padding(horizontal = 0.dp, vertical = 4.dp)) {
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
                if (keyOfParam == WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY ||
                    keyOfParam == WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO ||
                    keyOfParam == WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO ||
                    keyOfParam == WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET ||
                    keyOfParam == WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR
                ) { String.format("%.2f", currentValueDisplay) }
                else { currentValueDisplay.roundToInt().toString() },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f)
            )
        }
        Slider(
            value = internalSliderPosition,
            onValueChange = { newPosition -> internalSliderPosition = newPosition },
            valueRange = 0f..1f,
            steps = getStepsForParam(keyOfParam, prefs), // 确保 getStepsForParam 存在且正确
            modifier = Modifier.fillMaxWidth().padding(top = 0.dp),
            onValueChangeFinished = {
                onFinalValueChange(keyOfParam, internalSliderPosition) // 正确调用
            },
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

// 辅助函数 (保持不变)
// ... (getInitialSliderPosition, mapSliderPositionToActualValue, etc. 代码与上一条回复一致) ...
fun getInitialSliderPosition(paramKey: String, prefs: SharedPreferences): Float {
    val minRaw = getMinRawValueForParam(paramKey, prefs)
    val maxRaw = getMaxRawValueForParam(paramKey, prefs)
    val currentRawValue: Float = when (paramKey) {
        WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY -> prefs.getInt(paramKey, WallpaperConfigConstants.DEFAULT_SCROLL_SENSITIVITY_INT) / 10.0f
        WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO -> prefs.getInt(paramKey, WallpaperConfigConstants.DEFAULT_P1_OVERLAY_FADE_RATIO_INT) / 100.0f
        WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO -> prefs.getInt(paramKey, WallpaperConfigConstants.DEFAULT_P2_BACKGROUND_FADE_IN_RATIO_INT) / 100.0f
        WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET -> prefs.getInt(paramKey, WallpaperConfigConstants.DEFAULT_BACKGROUND_INITIAL_OFFSET_INT) / 10.0f
        WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS -> prefs.getInt(paramKey, WallpaperConfigConstants.DEFAULT_BACKGROUND_BLUR_RADIUS_INT).toFloat()
        WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR -> prefs.getInt(paramKey, WallpaperConfigConstants.DEFAULT_BLUR_DOWNSCALE_FACTOR_INT) / 100.0f
        WallpaperConfigConstants.KEY_BLUR_ITERATIONS -> prefs.getInt(paramKey, WallpaperConfigConstants.DEFAULT_BLUR_ITERATIONS).toFloat()
        WallpaperConfigConstants.KEY_P1_SHADOW_RADIUS -> prefs.getInt(paramKey, WallpaperConfigConstants.DEFAULT_P1_SHADOW_RADIUS_INT).toFloat()
        WallpaperConfigConstants.KEY_P1_SHADOW_DX -> prefs.getInt(paramKey, WallpaperConfigConstants.DEFAULT_P1_SHADOW_DX_INT).toFloat()
        WallpaperConfigConstants.KEY_P1_SHADOW_DY -> prefs.getInt(paramKey, WallpaperConfigConstants.DEFAULT_P1_SHADOW_DY_INT).toFloat()
        WallpaperConfigConstants.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT -> prefs.getInt(paramKey, WallpaperConfigConstants.DEFAULT_P1_IMAGE_BOTTOM_FADE_HEIGHT_INT).toFloat()
        else -> minRaw
    }
    return if ((maxRaw - minRaw) == 0f) 0f else ((currentRawValue - minRaw) / (maxRaw - minRaw)).coerceIn(0f, 1f)
}

fun mapSliderPositionToActualValue(paramKey: String, sliderPosition: Float, prefs: SharedPreferences): Float {
    val minRaw = getMinRawValueForParam(paramKey, prefs)
    val maxRaw = getMaxRawValueForParam(paramKey, prefs)
    val value = minRaw + (maxRaw - minRaw) * sliderPosition
    return when (paramKey) {
        else -> value
    }
}

fun getMinRawValueForParam(paramKey: String, prefs: SharedPreferences): Float {
    return when (paramKey) {
        WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY -> 0.1f
        WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO -> 0.01f
        WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO -> 0.01f
        WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET -> 0f
        WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS -> 0f
        WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR -> 0.05f
        WallpaperConfigConstants.KEY_BLUR_ITERATIONS -> 1f
        WallpaperConfigConstants.KEY_P1_SHADOW_RADIUS -> 0f
        WallpaperConfigConstants.KEY_P1_SHADOW_DX -> -20f
        WallpaperConfigConstants.KEY_P1_SHADOW_DY -> 0f
        WallpaperConfigConstants.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT -> 0f
        else -> 0f
    }
}

fun getMaxRawValueForParam(paramKey: String, prefs: SharedPreferences): Float {
    return when (paramKey) {
        WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY -> 2.0f
        WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO -> 1.0f
        WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO -> 1.0f
        WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET -> 1.0f
        WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS -> 25f
        WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR -> 1.0f
        WallpaperConfigConstants.KEY_BLUR_ITERATIONS -> 10f
        WallpaperConfigConstants.KEY_P1_SHADOW_RADIUS -> 20f
        WallpaperConfigConstants.KEY_P1_SHADOW_DX -> 20f
        WallpaperConfigConstants.KEY_P1_SHADOW_DY -> 20f
        WallpaperConfigConstants.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT -> 2560f
        else -> 1f
    }
}

fun getStepsForParam(paramKey: String, prefs: SharedPreferences): Int {
    val minRaw = getMinRawValueForParam(paramKey, prefs)
    val maxRaw = getMaxRawValueForParam(paramKey, prefs)
    return when (paramKey) {
        WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS,
        WallpaperConfigConstants.KEY_BLUR_ITERATIONS,
        WallpaperConfigConstants.KEY_P1_SHADOW_RADIUS,
        WallpaperConfigConstants.KEY_P1_SHADOW_DX,
        WallpaperConfigConstants.KEY_P1_SHADOW_DY
            -> (maxRaw - minRaw).toInt() -1
        WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY
            -> (((maxRaw - minRaw) * 10).toInt() -1).coerceAtLeast(0)
        WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO,
        WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO,
        WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR,
        WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET
            -> {
            when(paramKey){
                WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET -> ((maxRaw - minRaw) * 10).toInt() -1
                else -> 99
            }
        }
        WallpaperConfigConstants.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT
            -> if (maxRaw > 0) 63 else 0
        else -> 0
    }.coerceAtLeast(0)
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
        "sub_bg_color" -> {
            val currentColors = viewModel.colorPalette.value
            val currentBgColor = viewModel.selectedBackgroundColor.value
            if (!currentColors.isNullOrEmpty() && currentBgColor != null) {
                val currentIndex = currentColors.indexOf(currentBgColor)
                val nextIndex = if (currentIndex == -1 || currentIndex == currentColors.lastIndex) 0 else currentIndex + 1
                viewModel.updateSelectedBackgroundColor(currentColors[nextIndex])
                Toast.makeText(context, "背景色已切换", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "颜色选择功能待完善", Toast.LENGTH_SHORT).show()
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
        "p1_customize_action" -> {
            if (viewModel.selectedImageUri.value != null) {
                viewModel.toggleP1EditMode()
                onHideSheet()
            } else {
                Toast.makeText(context, context.getString(R.string.please_select_image_first_toast), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
// ConfigBottomSheetContainer Composable (保持不变)
// ... (代码与上一条回复一致) ...
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
    val scrollState = rememberScrollState() // 创建滚动状态

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.closeConfigSheet() },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
            contentColor = Color.White,
            scrimColor = Color.Transparent,
            // 移除 windowInsets 参数，避免编译错误
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
            // 这里是 ModalBottomSheet 的内容 lambda
            // ConfigSheetContent 将会在这里被调用，并接收下面定义的 modifier
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
                // 修改点：将高度限制、滚动和系统栏内边距的 Modifier 传递给 ConfigSheetContent
                modifier = Modifier
                    // 这个 Modifier 会应用到 ConfigSheetContent 的根 Column 上
                    .fillMaxWidth() // 确保内容宽度填满 BottomSheet
                    .heightIn(max = configuration.screenHeightDp.dp * 0.65f) // 例如，最大高度为屏幕的65%
                    .verticalScroll(scrollState) // 使 ConfigSheetContent 的内容可以在其限定高度内滚动
                    .navigationBarsPadding() // 应用导航栏的内边距，避免遮挡
            )
        }
    }
}

// 预览代码 (保持不变)
// ... (代码与上一条回复一致) ...
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
            override fun toggleP1EditMode() { (this.isP1EditMode as MutableLiveData).value = !this.isP1EditMode.value!! }
            override fun updateSelectedBackgroundColor(color: Int) { (this.selectedBackgroundColor as MutableLiveData).value = color }
            override fun closeConfigSheet() { (this.showConfigSheet as MutableStateFlow).value = false }
            override fun saveNonBitmapConfigAndUpdateVersion() { Log.d("PreviewVM", "saveNonBitmapConfigAndUpdateVersion called") }
        }
    }
    val fakeActions = object : MainActivityActions {
        override fun requestReadMediaImagesPermission() {}
        override fun startSettingsActivity() {}
        override fun promptToSetWallpaper() {}
    }

    H2WallpaperTheme(darkTheme = true) {
        Surface(
            modifier = Modifier.fillMaxHeight(0.6f),
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

