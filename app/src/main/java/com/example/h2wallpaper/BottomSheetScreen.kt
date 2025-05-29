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
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.graphics.toArgb
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
import kotlinx.coroutines.channels.Channel

// --- 数据模型 ---
data class SubCategory(
    val id: String,
    val name: String,
    val type: String = "action" // "action", "parameter_slider", "color_picker_trigger"
)

data class MainCategory(
    val id: String,
    val name: String,
    val subCategories: List<SubCategory>
)

// 更新后的 mainCategoriesData，移除了 "更多高级设置"
val mainCategoriesData = listOf(
    MainCategory("cat_general", "通用", listOf(
        SubCategory("sub_select_image", "选择图片", type = "action"),
        SubCategory("sub_bg_color", "背景颜色", type = "color_picker_trigger"), // 特殊类型，用于触发颜色滑块
        SubCategory("sub_apply_wallpaper", "应用壁纸", type = "action")
        // "sub_advanced_settings" 已移除
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

// --- MainActivityActions 接口 (移除了 startSettingsActivity) ---
interface MainActivityActions {
    fun requestReadMediaImagesPermission()
    // fun startSettingsActivity() // 已移除
    fun promptToSetWallpaper()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigSheetContent(
    viewModel: MainViewModel,
    activityActions: MainActivityActions,
    onHideSheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val selectedMainCategoryId by viewModel.selectedMainCategoryIdInSheet.collectAsState()
    val subCategoryForAdjustmentId by viewModel.subCategoryForAdjustmentIdInSheet.collectAsState()

    val selectedMainCategory = remember(selectedMainCategoryId) {
        mainCategoriesData.find { it.id == selectedMainCategoryId } ?: mainCategoriesData.firstOrNull()
    }
    val subCategoryForAdjustment = remember(subCategoryForAdjustmentId, selectedMainCategory) {
        selectedMainCategory?.subCategories?.find { it.id == subCategoryForAdjustmentId }
    }

    val isP1EditMode by viewModel.isP1EditMode.observeAsState(initial = false)
    val showCustomColorSliders by viewModel.showCustomColorSliders.collectAsState()

    // 是否处于某种"编辑锁定"模式 (P1编辑 或 颜色滑块编辑)
    val isEditingLocked = isP1EditMode || showCustomColorSliders

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
            if (showCustomColorSliders && !isP1EditMode) {
                CustomColorSlidersArea(
                    viewModel = viewModel,
                    initialColor = Color(viewModel.selectedBackgroundColor.observeAsState(WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR).value!!)
                )
            } else if (subCategoryForAdjustment?.type == "parameter_slider" && !isP1EditMode) {
                ParameterAdjustmentSection(
                    viewModel = viewModel,
                    subCategory = subCategoryForAdjustment,
                    keyOfParam = subCategoryForAdjustment.id
                )
            } else {
                PlaceholderForAdjustmentArea(text = if (isP1EditMode && viewModel.selectedImageUri.value != null) "P1图片调整模式已激活" else if (showCustomColorSliders) "调整背景颜色中..." else "选择下方分类中的选项进行调整")
            }
        }

        MainCategoryTabs(
            categories = mainCategoriesData,
            selectedCategory = selectedMainCategory,
            onCategorySelected = { category ->
                // 当处于任何编辑锁定模式时，不允许切换主分类
                if (!isEditingLocked) {
                    viewModel.onMainCategorySelectedInSheet(category.id)
                    // 如果之前颜色滑块是打开的，切换主分类时应关闭它 (ViewModel中已处理部分)
                    // if (viewModel.showCustomColorSliders.value) {
                    //     viewModel.toggleCustomColorSlidersVisibility()
                    // }
                } else {
                    val lockedBy = if (isP1EditMode) "P1图片" else "背景颜色"
                    Toast.makeText(context, "请先完成${lockedBy}调整", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            enabled = !isEditingLocked // P1编辑或颜色滑块显示时，禁用Tabs
        )

        SubCategoryDisplayArea(
            subCategories = selectedMainCategory?.subCategories ?: emptyList(),
            currentlyAdjusting = if (isEditingLocked) null else subCategoryForAdjustment,
            onSubCategoryClick = { subCategory ->
                if (isP1EditMode && subCategory.id != "p1_customize_action") {
                    Toast.makeText(context, "请先完成P1图片调整", Toast.LENGTH_SHORT).show()
                    return@SubCategoryDisplayArea
                }
                // 如果颜色滑块显示，只允许通过点击"背景颜色"卡片来关闭它
                if (showCustomColorSliders && subCategory.id != "sub_bg_color") {
                    Toast.makeText(context, "请先完成背景颜色调整", Toast.LENGTH_SHORT).show()
                    return@SubCategoryDisplayArea
                }


                if (subCategory.id == "p1_customize_action") {
                    if (viewModel.selectedImageUri.value != null) {
                        viewModel.toggleP1EditMode() // 这会改变 isP1EditMode，从而影响 isEditingLocked
                    } else {
                        Toast.makeText(context, context.getString(R.string.please_select_image_first_toast), Toast.LENGTH_SHORT).show()
                    }
                } else if (subCategory.type == "color_picker_trigger") { // 如 "sub_bg_color"
                    // 不论当前颜色滑块是否显示，点击此按钮都应切换其状态
                    viewModel.toggleCustomColorSlidersVisibility()
                    if (viewModel.subCategoryForAdjustmentIdInSheet.value != null) {
                        viewModel.onSubCategoryForAdjustmentSelectedInSheet(null) // 关闭参数滑块
                    }
                } else if (subCategory.type == "parameter_slider") {
                    viewModel.onSubCategoryForAdjustmentSelectedInSheet(subCategory.id)
                    // 如果颜色滑块之前是打开的，确保关闭它 (ViewModel中已处理)
                    // if (viewModel.showCustomColorSliders.value) {
                    //    viewModel.toggleCustomColorSlidersVisibility()
                    // }
                } else { // action 类型
                    viewModel.onSubCategoryForAdjustmentSelectedInSheet(null)
                    // if (viewModel.showCustomColorSliders.value) { // 如果有其他action需要关闭颜色滑块
                    //    viewModel.toggleCustomColorSlidersVisibility()
                    // }
                    handleSubCategoryAction(subCategory, viewModel, activityActions, context, onHideSheet)
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            isP1EditModeActive = isP1EditMode,
            // 新增一个参数来决定是否整体禁用子分类区域（除了特定按钮）
            editingColorInProgress = showCustomColorSliders && !isP1EditMode,
            highlightedSubCategoryIdForColor = if (showCustomColorSliders && !isP1EditMode) "sub_bg_color" else null
        )

    }
}

@Composable
private fun PlaceholderForAdjustmentArea(text: String = "选择下方参数项进行调整") {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 72.dp),
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
fun CustomColorSlidersArea(
    viewModel: MainViewModel,
    initialColor: Color
) {
    val selectedColorInt by viewModel.selectedBackgroundColor.observeAsState(initialColor.toArgb())
    var localCustomColor by remember(selectedColorInt) { mutableStateOf(Color(selectedColorInt)) }

    var red by remember(localCustomColor) { mutableStateOf(localCustomColor.red) }
    var green by remember(localCustomColor) { mutableStateOf(localCustomColor.green) }
    var blue by remember(localCustomColor) { mutableStateOf(localCustomColor.blue) }

    LaunchedEffect(red, green, blue) {
        localCustomColor = Color(red, green, blue)
        // 实时更新颜色到ViewModel，节流逻辑已经移至ViewModel中处理
        viewModel.updateSelectedBackgroundColor(localCustomColor.toArgb())
    }

    Column(modifier = Modifier.padding(vertical = 8.dp).defaultMinSize(minHeight = 64.dp)) {
        Text(
            "自定义背景颜色",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp)
        )
        ColorSlider(label = "红", value = red,
            onValueChange = { red = it },
            // 移除onValueChangeFinished，通过LaunchedEffect即时更新
            onValueChangeFinished = { }
        )
        ColorSlider(label = "绿", value = green,
            onValueChange = { green = it },
            // 移除onValueChangeFinished，通过LaunchedEffect即时更新
            onValueChangeFinished = { }
        )
        ColorSlider(label = "蓝", value = blue,
            onValueChange = { blue = it },
            // 移除onValueChangeFinished，通过LaunchedEffect即时更新
            onValueChangeFinished = { }
        )

        Spacer(modifier = Modifier.height(8.dp))
        Text("预设颜色:", style = MaterialTheme.typography.labelMedium, color = Color.White, modifier = Modifier.padding(bottom = 4.dp))
        PresetColorPalette(viewModel = viewModel)
    }
}

@Composable
fun PresetColorPalette(viewModel: MainViewModel) {
    val colorPalette by viewModel.colorPalette.observeAsState(initial = emptyList())
    val selectedColorInt by viewModel.selectedBackgroundColor.observeAsState(
        initial = WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR
    )

    if (colorPalette.isEmpty() && viewModel.selectedImageUri.value == null) {
        Text(
            "无预设颜色 (请选择图片)",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        return
    }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.Start),
        contentPadding = PaddingValues(horizontal = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        // "自定义"按钮，现在通过SubCategoryDisplayArea"sub_bg_color"卡片触发自定义滑块区
        // 所以这里不再需要单独的"自定义"按钮。

        if (colorPalette.isNotEmpty()) {
            items(colorPalette) { colorIntValue ->
                val itemColor = Color(colorIntValue)
                val isSelected = colorIntValue == selectedColorInt
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(itemColor, CircleShape)
                        .border(
                            width = if (isSelected) 2.dp else 0.dp,
                            color = if (isSelected) Color.White else Color.Transparent,
                            shape = CircleShape
                        )
                        .clickable { 
                            // 直接调用ViewModel方法，由ViewModel负责节流
                            viewModel.updateSelectedBackgroundColor(colorIntValue) 
                        }
                )
            }
        }
    }
}

@Composable
fun ColorSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = "$label: ${(value * 255).roundToInt()}",
            fontSize = 13.sp,
            color = Color.White.copy(alpha = 0.9f)
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White.copy(alpha = 0.7f),
                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
            ),
            modifier = Modifier.heightIn(min = 24.dp)
        )
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
    isP1EditModeActive: Boolean,
    editingColorInProgress: Boolean, // 新增
    highlightedSubCategoryIdForColor: String?
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
        // 当P1编辑或颜色编辑时，禁止横向滚动子分类列表
        userScrollEnabled = !isP1EditModeActive && !editingColorInProgress,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(subCategories) { subCategory ->
            val isP1CustomizeButton = subCategory.id == "p1_customize_action"
            val isBgColorButton = subCategory.id == "sub_bg_color" && subCategory.type == "color_picker_trigger"

            // 计算卡片是否启用
            val cardEnabled = when {
                isP1EditModeActive -> isP1CustomizeButton // P1编辑模式，只允许P1完成按钮
                editingColorInProgress -> isBgColorButton   // 颜色编辑模式，只允许背景颜色按钮（用于关闭）
                else -> true                              // 其他情况，所有按钮都启用
            }

            val isHighlighted = (!isP1EditModeActive && !editingColorInProgress &&
                    (currentlyAdjusting == subCategory && (subCategory.type == "parameter_slider"))
                    ) || (editingColorInProgress && isBgColorButton) // 当颜色编辑时，高亮背景色按钮


            SubCategoryCard(
                subCategory = subCategory,
                onClick = { if (cardEnabled) onSubCategoryClick(subCategory) },
                isHighlighted = isHighlighted,
                enabled = cardEnabled,
                displayText = if (isP1EditModeActive && isP1CustomizeButton) "完成P1调整" else if (editingColorInProgress && isBgColorButton) "完成颜色" else subCategory.name,
                isP1EditModeActive = isP1EditModeActive // 这个参数可能可以和editingColorInProgress合并或简化
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
                "sub_bg_color" -> Icons.Filled.ColorLens // "背景颜色" 卡片图标
                "sub_apply_wallpaper" -> Icons.Filled.Wallpaper
                // "sub_advanced_settings" -> Icons.Filled.Settings // 已移除
                else -> {
                    when (subCategory.type) {
                        "parameter_slider" -> Icons.Filled.Tune
                        "color_picker_trigger" -> Icons.Filled.ColorLens // Should not happen if ID is sub_bg_color
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
    viewModel: MainViewModel,
    subCategory: SubCategory,
    keyOfParam: String
) {
    val currentActualValueFromVM: State<Float?> = when (keyOfParam) {
        WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY -> viewModel.scrollSensitivity.observeAsState()
        WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO -> viewModel.p1OverlayFadeRatio.observeAsState()
        WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO -> viewModel.p2BackgroundFadeInRatio.observeAsState()
        WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET -> viewModel.backgroundInitialOffset.observeAsState()
        WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS -> viewModel.backgroundBlurRadius.observeAsState()
        WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR -> viewModel.blurDownscaleFactor.observeAsState()
        WallpaperConfigConstants.KEY_P1_SHADOW_RADIUS -> viewModel.p1ShadowRadius.observeAsState()
        WallpaperConfigConstants.KEY_P1_SHADOW_DX -> viewModel.p1ShadowDx.observeAsState()
        WallpaperConfigConstants.KEY_P1_SHADOW_DY -> viewModel.p1ShadowDy.observeAsState()
        WallpaperConfigConstants.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT -> viewModel.p1ImageBottomFadeHeight.observeAsState()
        else -> remember { mutableStateOf(null) }
    }
    val currentBlurIterationsFromVM: State<Int?> = if (keyOfParam == WallpaperConfigConstants.KEY_BLUR_ITERATIONS) {
        viewModel.blurIterations.observeAsState()
    } else {
        remember { mutableStateOf(null) }
    }

    var currentSliderPosition by remember(keyOfParam, currentActualValueFromVM.value, currentBlurIterationsFromVM.value) {
        val actualValueToUse = if (keyOfParam == WallpaperConfigConstants.KEY_BLUR_ITERATIONS) {
            currentBlurIterationsFromVM.value?.toFloat()
        } else {
            currentActualValueFromVM.value
        }
        mutableStateOf(
            actualValueToUse?.let { mapActualValueToSliderPosition(keyOfParam, it) } ?: 0.5f
        )
    }

    val displayValueString = remember(keyOfParam, currentSliderPosition) {
        val actualVal = mapSliderPositionToActualValue(keyOfParam, currentSliderPosition)
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
        
        // 添加对模糊迭代参数的特殊处理，显示刻度
        if (keyOfParam == WallpaperConfigConstants.KEY_BLUR_ITERATIONS) {
            Slider(
                value = currentSliderPosition,
                onValueChange = { newPosition ->
                    // 对于模糊迭代参数，steps已经限制了可用值
                    currentSliderPosition = newPosition
                    val actualValue = mapSliderPositionToActualValue(keyOfParam, newPosition)
                    viewModel.updateAdvancedSettingRealtime(keyOfParam, actualValue)
                },
                valueRange = 0f..1f,
                steps = 1, // 这里设置步数为1，表示有两个步骤（总共3个值：1、2、3）
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White.copy(alpha = 0.7f),
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 0.dp)
            )
        } else {
            // 其他参数使用常规滑动条
            Slider(
                value = currentSliderPosition,
                onValueChange = { newPosition ->
                    currentSliderPosition = newPosition
                    val actualValue = mapSliderPositionToActualValue(keyOfParam, newPosition)
                    viewModel.updateAdvancedSettingRealtime(keyOfParam, actualValue)
                },
                onValueChangeFinished = {
                    // 滑动结束时，确保最终值被处理
                    val finalValue = mapSliderPositionToActualValue(keyOfParam, currentSliderPosition)
                    viewModel.updateAdvancedSettingRealtime(keyOfParam, finalValue)
                },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White.copy(alpha = 0.7f),
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 0.dp)
            )
        }
    }
}

// --- 辅助函数 (mapActualValueToSliderPosition, mapSliderPositionToActualValue, getMinRawValueForParam, getMaxRawValueForParam, getStepsForParam) ---
// (这些函数的定义与之前回复中修正后的版本一致，确保它们不接受 prefs 参数)
fun mapActualValueToSliderPosition(paramKey: String, actualValue: Float): Float {
    val minRaw = getMinRawValueForParam(paramKey)
    val maxRaw = getMaxRawValueForParam(paramKey)
    return if ((maxRaw - minRaw) == 0f) 0f else ((actualValue - minRaw) / (maxRaw - minRaw)).coerceIn(0f, 1f)
}

fun mapSliderPositionToActualValue(paramKey: String, sliderPosition: Float): Float {
    val minRaw = getMinRawValueForParam(paramKey)
    val maxRaw = getMaxRawValueForParam(paramKey)
    return minRaw + (maxRaw - minRaw) * sliderPosition
}

fun getMinRawValueForParam(paramKey: String): Float {
    return when (paramKey) {
        WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY -> 0.1f
        WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO -> 0.01f
        WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO -> 0.01f
        WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET -> 0.0f
        WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS -> 0f
        WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR -> 0.01f
        WallpaperConfigConstants.KEY_BLUR_ITERATIONS -> 1f
        WallpaperConfigConstants.KEY_P1_SHADOW_RADIUS -> 0f
        WallpaperConfigConstants.KEY_P1_SHADOW_DX -> -20f
        WallpaperConfigConstants.KEY_P1_SHADOW_DY -> 0f
        WallpaperConfigConstants.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT -> 0f
        else -> 0f
    }
}

fun getMaxRawValueForParam(paramKey: String): Float {
    return when (paramKey) {
        WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY -> 2.0f
        WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO -> 1.0f
        WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO -> 1.0f
        WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET -> 1.0f
        WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS -> 25f
        WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR -> 0.5f
        WallpaperConfigConstants.KEY_BLUR_ITERATIONS -> 3f
        WallpaperConfigConstants.KEY_P1_SHADOW_RADIUS -> 20f
        WallpaperConfigConstants.KEY_P1_SHADOW_DX -> 20f
        WallpaperConfigConstants.KEY_P1_SHADOW_DY -> 20f
        WallpaperConfigConstants.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT -> 600f
        else -> 1f
    }
}

fun getStepsForParam(paramKey: String): Int {
    val minRawInt: Int
    val maxRawInt: Int
    when (paramKey) {
        WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY -> { minRawInt = 1; maxRawInt = 20 } // 0.1 to 2.0, step 0.1
        WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO -> { minRawInt = 1; maxRawInt = 100 } // 0.01 to 1.0, step 0.01
        WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO -> { minRawInt = 1; maxRawInt = 100 } // 0.01 to 1.0, step 0.01
        WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET -> { minRawInt = 0; maxRawInt = 10 } // 0.0 to 1.0, step 0.1
        WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS -> { minRawInt = 0; maxRawInt = 25 } // step 1
        WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR -> { minRawInt = 1; maxRawInt = 50 } // 0.01 to 0.5, step 0.01 (map to 1-50)
        WallpaperConfigConstants.KEY_BLUR_ITERATIONS -> { minRawInt = 1; maxRawInt = 3 } // step 1
        WallpaperConfigConstants.KEY_P1_SHADOW_RADIUS -> { minRawInt = 0; maxRawInt = 20 } // step 1
        WallpaperConfigConstants.KEY_P1_SHADOW_DX -> { minRawInt = -20; maxRawInt = 20 } // step 1
        WallpaperConfigConstants.KEY_P1_SHADOW_DY -> { minRawInt = 0; maxRawInt = 20 } // step 1
        WallpaperConfigConstants.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT -> {
            // Range 0 to 600. For ~30 steps, interval is 20.
            return (600 / 20) - 1 // 29 steps
        }
        else -> return 0
    }
    return (maxRawInt - minRawInt - 1).coerceAtLeast(0)
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
        // "sub_bg_color" is now handled by its "color_picker_trigger" type in onSubCategoryClick
        "sub_apply_wallpaper" -> {
            if (viewModel.selectedImageUri.value != null) {
                activityActions.promptToSetWallpaper()
                onHideSheet()
            } else {
                Toast.makeText(context, context.getString(R.string.please_select_image_first_toast), Toast.LENGTH_SHORT).show()
            }
        }
        // "sub_advanced_settings" was removed
        "p1_customize_action" -> { // This case is also handled by onSubCategoryClick's main logic
            if (viewModel.selectedImageUri.value != null) {
                viewModel.toggleP1EditMode()
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
    val scrollState = rememberScrollState() // for the main content column

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.closeConfigSheet() },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
            contentColor = Color.White,
            scrimColor = Color.Black.copy(alpha = 0.1f),
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
                    .heightIn(max = configuration.screenHeightDp.dp * 0.85f) // Slightly more max height
                    .verticalScroll(scrollState) // Make the entire content scrollable
                    .navigationBarsPadding()
            )
        }
    }
}


@Preview(showBackground = true, name = "配置选项内容预览 (自定义颜色滑块展开)")
@Composable
fun ConfigSheetContentCustomColorPreview() {
    val context = LocalContext.current
    val previewSafeViewModel = remember {
        object : MainViewModel(context.applicationContext as Application) {
            override val selectedImageUri: LiveData<Uri?> = MutableLiveData(null)
            override val selectedBackgroundColor: LiveData<Int> = MutableLiveData(0xFF4CAF50.toInt()) // Green
            override val page1ImageHeightRatio: LiveData<Float> = MutableLiveData(WallpaperConfigConstants.DEFAULT_HEIGHT_RATIO)
            override val colorPalette: LiveData<List<Int>> = MutableLiveData(listOf(0xFFDB4437.toInt(), 0xFF4285F4.toInt(), 0xFF0F9D58.toInt(), 0xFFF4B400.toInt()))
            override val isP1EditMode: LiveData<Boolean> = MutableLiveData(false)
            override val showConfigSheet: StateFlow<Boolean> = MutableStateFlow(true)
            override val showCustomColorSliders: StateFlow<Boolean> = MutableStateFlow(true) // For preview

            override fun toggleP1EditMode() { (this.isP1EditMode as MutableLiveData).value = !this.isP1EditMode.value!! }
            override fun updateSelectedBackgroundColor(color: Int) { (this.selectedBackgroundColor as MutableLiveData).value = color }
            override fun closeConfigSheet() { (this.showConfigSheet as MutableStateFlow).value = false }
            override fun toggleCustomColorSlidersVisibility() { (this.showCustomColorSliders as MutableStateFlow).value = !this.showCustomColorSliders.value }
            override fun saveNonBitmapConfigAndUpdateVersion() { Log.d("PreviewVM", "saveNonBitmapConfigAndUpdateVersion called") }
            override fun updateAdvancedSettingRealtime(paramKey: String, actualValue: Float) { Log.d("PreviewVM", "updateAdvancedSettingRealtime called for $paramKey with $actualValue") }
        }
    }
    val fakeActions = object : MainActivityActions {
        override fun requestReadMediaImagesPermission() {}
        override fun promptToSetWallpaper() {}
    }

    H2WallpaperTheme(darkTheme = true) {
        Surface(
            modifier = Modifier.fillMaxHeight(0.85f),
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