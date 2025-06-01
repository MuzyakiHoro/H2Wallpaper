// app/src/main/java/com/example/h2wallpaper/BottomSheetScreen.kt
package com.example.h2wallpaper

import android.app.Application
import android.content.Context
import androidx.compose.ui.graphics.Color // Using Compose's Color
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.* // Importing all filled icons for convenience
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

// --- Data Models (Keep as is) ---
data class SubCategory(
    val id: String,
    val name: String,
    val type: String = "action",
    val icon: androidx.compose.ui.graphics.vector.ImageVector? = null
)

data class MainCategory(
    val id: String,
    val name: String,
    val subCategories: List<SubCategory>
)

val mainCategoriesData = listOf(
    MainCategory("cat_general", "通用", listOf(
        SubCategory("sub_select_image", "选择图片", type = "action", icon = Icons.Filled.Image),
        SubCategory("sub_bg_color", "背景颜色", type = "color_picker_trigger", icon = Icons.Filled.ColorLens),
        SubCategory("sub_toggle_style_selection", "切换 P1 样式", type = "action", icon = Icons.Filled.Style),
        SubCategory("sub_apply_wallpaper", "应用壁纸", type = "action", icon = Icons.Filled.Wallpaper)
    )),
    MainCategory("cat_p1_foreground", "P1 前景", emptyList()),
    MainCategory("cat_background_effects", "背景效果", listOf(
        SubCategory(WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS, "模糊半径", type = "parameter_slider", icon = Icons.Filled.Tune),
        SubCategory(WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR, "模糊降采样", type = "parameter_slider", icon = Icons.Filled.Tune),
        SubCategory(WallpaperConfigConstants.KEY_BLUR_ITERATIONS, "模糊迭代", type = "parameter_slider", icon = Icons.Filled.Tune)
    )),
    MainCategory("cat_scroll_transitions", "滚动与过渡", listOf(
        SubCategory(WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY, "滚动灵敏度", type = "parameter_slider", icon = Icons.Filled.Tune),
        SubCategory(WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO, "P1 淡出", type = "parameter_slider", icon = Icons.Filled.Tune),
        SubCategory(WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO, "P2 淡入", type = "parameter_slider", icon = Icons.Filled.Tune),
        SubCategory(WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET, "背景初始偏移", type = "parameter_slider", icon = Icons.Filled.Tune)
    ))
)

val p1StyleASubCategories = listOf(
    SubCategory("p1_style_a_customize_action", "调整P1图片", type = "action", icon = Icons.Filled.AspectRatio),
    SubCategory(WallpaperConfigConstants.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT, "底部融入", type = "parameter_slider", icon = Icons.Filled.Tune),
    SubCategory(WallpaperConfigConstants.KEY_P1_SHADOW_RADIUS, "投影半径", type = "parameter_slider", icon = Icons.Filled.Tune),
    SubCategory(WallpaperConfigConstants.KEY_P1_SHADOW_DY, "投影Y偏移", type = "parameter_slider", icon = Icons.Filled.Tune),
    SubCategory(WallpaperConfigConstants.KEY_P1_SHADOW_DX, "投影X偏移", type = "parameter_slider", icon = Icons.Filled.Tune),
)

val p1StyleBSubCategories = listOf(
   // SubCategory("p1_style_b_customize_action", "调整P1背景", type = "action", icon = Icons.Filled.AspectRatio),
    SubCategory(WallpaperConfigConstants.KEY_STYLE_B_GAP_POSITION_Y_RATIO, "顶部高度", type = "parameter_slider", icon = Icons.Filled.Tune),
    SubCategory(WallpaperConfigConstants.KEY_STYLE_B_GAP_SIZE_RATIO, "中间高度", type = "parameter_slider", icon = Icons.Filled.Tune),
    SubCategory(WallpaperConfigConstants.KEY_STYLE_B_ROTATION_PARAM_A, "倾斜角度", type = "parameter_slider", icon = Icons.Filled.Tune),
    SubCategory(WallpaperConfigConstants.KEY_STYLE_B_MASKS_HORIZONTALLY_FLIPPED, "翻转方向", type = "action", icon = Icons.Filled.Flip ),
    SubCategory(WallpaperConfigConstants.KEY_STYLE_B_MASK_ALPHA, "遮罩透明度", type = "parameter_slider", icon = Icons.Filled.Tune),
)

interface MainActivityActions {
    fun requestReadMediaImagesPermission()
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
    val p1StyleType by viewModel.p1StyleType.observeAsState(WallpaperConfigConstants.DEFAULT_P1_STYLE_TYPE)

    val selectedMainCategory = remember(selectedMainCategoryId) {
        mainCategoriesData.find { it.id == selectedMainCategoryId }
    }

    val currentP1SubCategories = remember(p1StyleType) {
        if (p1StyleType == 1 /* STYLE_B */) p1StyleBSubCategories else p1StyleASubCategories
    }

    val subCategoriesForSelectedMain = if (selectedMainCategory?.id == "cat_p1_foreground") {
        currentP1SubCategories
    } else {
        selectedMainCategory?.subCategories ?: emptyList()
    }

    val subCategoryForAdjustment = remember(subCategoryForAdjustmentId, subCategoriesForSelectedMain) {
        subCategoriesForSelectedMain.find { it.id == subCategoryForAdjustmentId }
    }

    val isP1EditMode by viewModel.isP1EditMode.observeAsState(initial = false)
    val showCustomColorSliders by viewModel.showCustomColorSliders.collectAsState()
    val showStyleSelectionView by viewModel.showStyleSelection.collectAsState() // Use public StateFlow

    val selectedImageUri by viewModel.selectedImageUri.observeAsState() // Use public LiveData
    val isEditingLocked = isP1EditMode || showCustomColorSliders || showStyleSelectionView

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 72.dp)
                .animateContentSize()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            when {
                showStyleSelectionView -> { StyleSelectionButtons(viewModel = viewModel) }
                showCustomColorSliders && !isP1EditMode -> {
                    CustomColorSlidersArea(
                        viewModel = viewModel,
                        initialColor = Color(viewModel.selectedBackgroundColor.observeAsState(WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR).value!!)
                    )
                }
                subCategoryForAdjustment?.type == "parameter_slider" && !isP1EditMode -> {
                    ParameterAdjustmentSection(
                        viewModel = viewModel,
                        subCategory = subCategoryForAdjustment,
                        keyOfParam = subCategoryForAdjustment.id
                    )
                }
                else -> {
                    val placeholderText = when {
                        isP1EditMode && selectedImageUri != null -> "P1调整模式已激活"
                        showCustomColorSliders -> "调整背景颜色中..."
                        showStyleSelectionView -> "选择P1样式中..."
                        else -> "选择下方分类中的选项进行调整"
                    }
                    PlaceholderForAdjustmentArea(text = placeholderText)
                }
            }
        }

        MainCategoryTabs(
            categories = mainCategoriesData,
            selectedCategory = selectedMainCategory ?: mainCategoriesData.firstOrNull(),
            onCategorySelected = { category ->
                if (!isEditingLocked || category.id == selectedMainCategory?.id) {
                    viewModel.onMainCategorySelectedInSheet(category.id)
                    if (viewModel.showStyleSelection.value && category.id != "cat_general") {
                        viewModel.toggleStyleSelectionView()
                    }
                } else {
                    val lockedBy = when {isP1EditMode -> "P1图片"; showCustomColorSliders -> "背景颜色"; showStyleSelectionView -> "样式选择"; else -> "操作" }
                    Toast.makeText(context, "请先完成${lockedBy}调整/选择", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            enabled = !isEditingLocked || (isEditingLocked && selectedMainCategory?.id == "cat_general" && showStyleSelectionView)
        )

        SubCategoryDisplayArea(
            // Pass necessary state directly instead of the whole ViewModel if Ambient is problematic
            p1StyleType = p1StyleType, // Pass the observed state
            subCategories = subCategoriesForSelectedMain,
            currentlyAdjusting = if (isEditingLocked) null else subCategoryForAdjustment,
            onSubCategoryClick = { subCategory ->
                val currentP1Type = viewModel.p1StyleType.value ?: WallpaperConfigConstants.DEFAULT_P1_STYLE_TYPE
                val p1CustomizeActionId = if (currentP1Type == 1) "p1_style_b_customize_action" else "p1_style_a_customize_action"

                if (isP1EditMode && subCategory.id != p1CustomizeActionId) {
                    Toast.makeText(context, "请先完成P1调整", Toast.LENGTH_SHORT).show()
                    return@SubCategoryDisplayArea
                }
                if (showCustomColorSliders && subCategory.id != "sub_bg_color") {
                    Toast.makeText(context, "请先完成背景颜色调整", Toast.LENGTH_SHORT).show()
                    return@SubCategoryDisplayArea
                }
                if (showStyleSelectionView && subCategory.id != "sub_toggle_style_selection") {
                    Toast.makeText(context, "请先完成样式选择或点击切换按钮返回", Toast.LENGTH_SHORT).show()
                    return@SubCategoryDisplayArea
                }

                if (subCategory.id == "p1_style_a_customize_action" || subCategory.id == "p1_style_b_customize_action") {
                    if (selectedImageUri != null) {
                        viewModel.toggleP1EditMode()
                        if(viewModel.showCustomColorSliders.value) viewModel.toggleCustomColorSlidersVisibility()
                        if(viewModel.showStyleSelection.value) viewModel.toggleStyleSelectionView()
                        if(viewModel.subCategoryForAdjustmentIdInSheet.value != null) viewModel.onSubCategoryForAdjustmentSelectedInSheet(null)
                    } else {
                        Toast.makeText(context, context.getString(R.string.please_select_image_first_toast), Toast.LENGTH_SHORT).show()
                    }
                } else if (subCategory.type == "color_picker_trigger") {
                    viewModel.toggleCustomColorSlidersVisibility()
                    if(viewModel.showStyleSelection.value) viewModel.toggleStyleSelectionView()
                    if(viewModel.subCategoryForAdjustmentIdInSheet.value != null) viewModel.onSubCategoryForAdjustmentSelectedInSheet(null)
                } else if (subCategory.id == "sub_toggle_style_selection") {
                    viewModel.toggleStyleSelectionView()
                    if(viewModel.showCustomColorSliders.value) viewModel.toggleCustomColorSlidersVisibility()
                    if(viewModel.subCategoryForAdjustmentIdInSheet.value != null) viewModel.onSubCategoryForAdjustmentSelectedInSheet(null)
                }
                else if (subCategory.type == "parameter_slider") {
                    viewModel.onSubCategoryForAdjustmentSelectedInSheet(subCategory.id)
                    if(viewModel.showCustomColorSliders.value) viewModel.toggleCustomColorSlidersVisibility()
                    if(viewModel.showStyleSelection.value) viewModel.toggleStyleSelectionView()
                } else {
                    viewModel.onSubCategoryForAdjustmentSelectedInSheet(null)
                    if(viewModel.showCustomColorSliders.value) viewModel.toggleCustomColorSlidersVisibility()
                    if(viewModel.showStyleSelection.value) viewModel.toggleStyleSelectionView()

                    handleSubCategoryAction(
                        subCategory,
                        viewModel,
                        activityActions,
                        context,
                        onHideSheet,
                        selectedImageUri)
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            isP1EditModeActive = isP1EditMode,
            editingInProgress = showCustomColorSliders || showStyleSelectionView,
            highlightedSubCategoryId = when {
                showCustomColorSliders -> "sub_bg_color"
                showStyleSelectionView -> "sub_toggle_style_selection"
                else -> null
            }
        )
    }
}

@Composable
fun StyleSelectionButtons(viewModel: MainViewModel) {
    val currentP1Style by viewModel.p1StyleType.observeAsState(WallpaperConfigConstants.DEFAULT_P1_STYLE_TYPE)
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("选择 P1 层样式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, color = Color.White, modifier = Modifier.padding(bottom = 12.dp))
        val styleAButtonColors = if (currentP1Style == WallpaperConfigConstants.DEFAULT_P1_STYLE_TYPE) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary) else ButtonDefaults.outlinedButtonColors()
        val styleBButtonColors = if (currentP1Style == 1) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary) else ButtonDefaults.outlinedButtonColors()
        Button(onClick = { viewModel.selectP1Style(WallpaperConfigConstants.DEFAULT_P1_STYLE_TYPE) }, modifier = Modifier.fillMaxWidth(0.75f).height(48.dp), shape = RoundedCornerShape(12.dp), colors = styleAButtonColors, border = if (currentP1Style != WallpaperConfigConstants.DEFAULT_P1_STYLE_TYPE) BorderStroke(1.dp, Color.White.copy(alpha = 0.7f)) else null) {
            Icon(Icons.Filled.Layers, contentDescription = "氢样式", modifier = Modifier.size(ButtonDefaults.IconSize)); Spacer(Modifier.size(ButtonDefaults.IconSpacing)); Text("氢样式 (经典)")
        }
        Button(onClick = { viewModel.selectP1Style(1) }, modifier = Modifier.fillMaxWidth(0.75f).height(48.dp), shape = RoundedCornerShape(12.dp), colors = styleBButtonColors, border = if (currentP1Style != 1) BorderStroke(1.dp, Color.White.copy(alpha = 0.7f)) else null) {
            Icon(Icons.Filled.Brush, contentDescription = "斜氢样式", modifier = Modifier.size(ButtonDefaults.IconSize)); Spacer(Modifier.size(ButtonDefaults.IconSpacing)); Text("斜氢样式 (形变)")
        }
    }
}

@Composable
private fun PlaceholderForAdjustmentArea(text: String = "选择下方参数项进行调整") {
    Box(modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 72.dp), contentAlignment = Alignment.Center) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f))
    }
}

@Composable
fun CustomColorSlidersArea(viewModel: MainViewModel, initialColor: Color) {
    val selectedColorInt by viewModel.selectedBackgroundColor.observeAsState(initialColor.toArgb())
    var localCustomColor by remember(selectedColorInt) { mutableStateOf(Color(selectedColorInt)) }
    var red by remember(localCustomColor) { mutableStateOf(localCustomColor.red) }
    var green by remember(localCustomColor) { mutableStateOf(localCustomColor.green) }
    var blue by remember(localCustomColor) { mutableStateOf(localCustomColor.blue) }
    LaunchedEffect(red, green, blue) { localCustomColor = Color(red, green, blue); viewModel.updateSelectedBackgroundColor(localCustomColor.toArgb()) }
    Column(modifier = Modifier.padding(vertical = 8.dp).defaultMinSize(minHeight = 64.dp)) {
        Text("自定义背景颜色", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, color = Color.White, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
        ColorSlider(label = "红", value = red, onValueChange = { red = it }, onValueChangeFinished = {})
        ColorSlider(label = "绿", value = green, onValueChange = { green = it }, onValueChangeFinished = {})
        ColorSlider(label = "蓝", value = blue, onValueChange = { blue = it }, onValueChangeFinished = {})
        Spacer(modifier = Modifier.height(8.dp))
        Text("预设颜色:", style = MaterialTheme.typography.labelMedium, color = Color.White, modifier = Modifier.padding(bottom = 4.dp))
        PresetColorPalette(viewModel = viewModel)
    }
}

@Composable
fun PresetColorPalette(viewModel: MainViewModel) {
    val colorPalette by viewModel.colorPalette.observeAsState(initial = emptyList())
    val selectedColorInt by viewModel.selectedBackgroundColor.observeAsState(initial = WallpaperConfigConstants.DEFAULT_BACKGROUND_COLOR)
    val selectedImageUri by viewModel.selectedImageUri.observeAsState()
    if (colorPalette.isEmpty() && selectedImageUri == null) {
        Text("无预设颜色 (请选择图片)", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.Start), contentPadding = PaddingValues(horizontal = 0.dp), modifier = Modifier.fillMaxWidth()) {
        if (colorPalette.isNotEmpty()) {
            items(colorPalette) { colorIntValue ->
                val itemColor = Color(colorIntValue)
                val isSelected = colorIntValue == selectedColorInt
                Box(modifier = Modifier.size(36.dp).background(itemColor, CircleShape).border(width = if (isSelected) 2.dp else 0.dp, color = if (isSelected) Color.White else Color.Transparent, shape = CircleShape).clickable { viewModel.updateSelectedBackgroundColor(colorIntValue) })
            }
        }
    }
}

@Composable
fun ColorSlider(label: String, value: Float, onValueChange: (Float) -> Unit, onValueChangeFinished: () -> Unit) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(text = "$label: ${(value * 255).roundToInt()}", fontSize = 13.sp, color = Color.White.copy(alpha = 0.9f))
        Slider(value = value, onValueChange = onValueChange, onValueChangeFinished = onValueChangeFinished, valueRange = 0f..1f, colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White.copy(alpha = 0.7f), inactiveTrackColor = Color.White.copy(alpha = 0.3f)), modifier = Modifier.heightIn(min = 24.dp))
    }
}

@Composable
fun MainCategoryTabs(categories: List<MainCategory>, selectedCategory: MainCategory?, onCategorySelected: (MainCategory) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    LazyRow(modifier = modifier.fillMaxWidth(), userScrollEnabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(categories) { category ->
            val isSelected = category == selectedCategory
            TextButton(
                onClick = { if (enabled) onCategorySelected(category) }, enabled = enabled, shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.textButtonColors(containerColor = if (isSelected && enabled) Color.White.copy(alpha = 0.15f) else Color.Transparent, contentColor = Color.White, disabledContainerColor = Color.Transparent, disabledContentColor = Color.White.copy(alpha = 0.3f)),
                border = if (isSelected && enabled) BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)) else null,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) { Text(category.name, fontWeight = if (isSelected && enabled) FontWeight.Bold else FontWeight.Medium, fontSize = 14.sp) }
        }
    }
}

// Corrected SubCategoryDisplayArea
@Composable
fun SubCategoryDisplayArea(
    p1StyleType: Int, // Pass p1StyleType directly
    subCategories: List<SubCategory>,
    currentlyAdjusting: SubCategory?,
    onSubCategoryClick: (SubCategory) -> Unit,
    modifier: Modifier = Modifier,
    isP1EditModeActive: Boolean,
    editingInProgress: Boolean,
    highlightedSubCategoryId: String?
) {
    if (subCategories.isEmpty()) {
        Box(modifier = modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Text("此分类下无具体选项", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = Color.White.copy(alpha = 0.7f))
        }
        return
    }
    LazyRow(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        userScrollEnabled = !isP1EditModeActive && !editingInProgress,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(subCategories) { subCategory ->
            val p1CustomizeActionIdForCurrentStyle = if (p1StyleType == 1) "p1_style_b_customize_action" else "p1_style_a_customize_action"
            val cardEnabled = when {
                isP1EditModeActive -> subCategory.id == p1CustomizeActionIdForCurrentStyle
                editingInProgress -> subCategory.id == highlightedSubCategoryId
                else -> true
            }
            val isHighlighted = (!isP1EditModeActive && !editingInProgress &&
                    (currentlyAdjusting == subCategory && (subCategory.type == "parameter_slider"))
                    ) || (editingInProgress && subCategory.id == highlightedSubCategoryId)

            SubCategoryCard(
                subCategory = subCategory,
                onClick = { if (cardEnabled) onSubCategoryClick(subCategory) },
                isHighlighted = isHighlighted,
                enabled = cardEnabled,
                displayText = if (isP1EditModeActive && subCategory.id == p1CustomizeActionIdForCurrentStyle) "完成P1调整"
                else if (editingInProgress && subCategory.id == "sub_bg_color") "完成颜色"
                else if (editingInProgress && subCategory.id == "sub_toggle_style_selection") "返回配置"
                else subCategory.name,
                iconOverride = if (isP1EditModeActive && subCategory.id == p1CustomizeActionIdForCurrentStyle) Icons.Filled.CheckCircleOutline
                else if (editingInProgress && (subCategory.id == "sub_bg_color" || subCategory.id == "sub_toggle_style_selection") ) Icons.Filled.CheckCircleOutline
                else subCategory.icon
            )
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubCategoryCard(subCategory: SubCategory, onClick: () -> Unit, isHighlighted: Boolean, enabled: Boolean = true, displayText: String = subCategory.name, iconOverride: androidx.compose.ui.graphics.vector.ImageVector? = subCategory.icon) {
    val cardAlpha = if (enabled) 1f else 0.4f
    Card(
        onClick = { if (enabled) onClick() }, enabled = enabled,
        modifier = Modifier.widthIn(min = 90.dp, max = 120.dp).defaultMinSize(minHeight = 80.dp).padding(vertical = 4.dp).graphicsLayer(alpha = cardAlpha),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (isHighlighted && enabled) Color.White.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.08f), contentColor = Color.White, disabledContainerColor = Color.White.copy(alpha = 0.05f)),
        border = if (isHighlighted && enabled) BorderStroke(1.dp, Color.White.copy(alpha = 0.7f)) else null
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            val finalIcon = iconOverride ?: Icons.Filled.ChevronRight
            Icon(imageVector = finalIcon, contentDescription = displayText, modifier = Modifier.size(28.dp), tint = Color.White)
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = displayText, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2, fontSize = 12.sp, color = Color.White, fontWeight = if (isHighlighted && enabled) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

@Composable
fun ParameterAdjustmentSection(viewModel: MainViewModel, subCategory: SubCategory, keyOfParam: String) {
    val p1StyleType by viewModel.p1StyleType.observeAsState(WallpaperConfigConstants.DEFAULT_P1_STYLE_TYPE)
    val currentActualValueState: State<Float?> = remember(keyOfParam, p1StyleType) {
        derivedStateOf {
            when (keyOfParam) {
                WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY -> viewModel.scrollSensitivity.value; WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO -> viewModel.p1OverlayFadeRatio.value; WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO -> viewModel.p2BackgroundFadeInRatio.value; WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET -> viewModel.backgroundInitialOffset.value; WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS -> viewModel.backgroundBlurRadius.value; WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR -> viewModel.blurDownscaleFactor.value;
                WallpaperConfigConstants.KEY_IMAGE_HEIGHT_RATIO -> if (p1StyleType == 0) viewModel.page1ImageHeightRatio.value else null; WallpaperConfigConstants.KEY_P1_CONTENT_SCALE_FACTOR -> if (p1StyleType == 0) viewModel.p1ContentScaleFactor.value else null; WallpaperConfigConstants.KEY_P1_SHADOW_RADIUS -> if (p1StyleType == 0) viewModel.p1ShadowRadius.value else null; WallpaperConfigConstants.KEY_P1_SHADOW_DX -> if (p1StyleType == 0) viewModel.p1ShadowDx.value else null; WallpaperConfigConstants.KEY_P1_SHADOW_DY -> if (p1StyleType == 0) viewModel.p1ShadowDy.value else null; WallpaperConfigConstants.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT -> if (p1StyleType == 0) viewModel.p1ImageBottomFadeHeight.value else null;
                WallpaperConfigConstants.KEY_STYLE_B_MASK_ALPHA -> if (p1StyleType == 1) viewModel.styleBMaskAlpha.value else null; WallpaperConfigConstants.KEY_STYLE_B_ROTATION_PARAM_A -> if (p1StyleType == 1) viewModel.styleBRotationParamA.value else null; WallpaperConfigConstants.KEY_STYLE_B_GAP_SIZE_RATIO -> if (p1StyleType == 1) viewModel.styleBGapSizeRatio.value else null; WallpaperConfigConstants.KEY_STYLE_B_GAP_POSITION_Y_RATIO -> if (p1StyleType == 1) viewModel.styleBGapPositionYRatio.value else null; WallpaperConfigConstants.KEY_STYLE_B_UPPER_MASK_MAX_ROTATION -> if (p1StyleType == 1) viewModel.styleBUpperMaskMaxRotation.value else null; WallpaperConfigConstants.KEY_STYLE_B_LOWER_MASK_MAX_ROTATION -> if (p1StyleType == 1) viewModel.styleBLowerMaskMaxRotation.value else null;
                else -> null
            }
        }
    }
    val currentBlurIterationsFromVM: State<Int?> = if (keyOfParam == WallpaperConfigConstants.KEY_BLUR_ITERATIONS) viewModel.blurIterations.observeAsState() else remember { mutableStateOf(null) }
    val actualValueForSlider = if (keyOfParam == WallpaperConfigConstants.KEY_BLUR_ITERATIONS) currentBlurIterationsFromVM.value?.toFloat() else currentActualValueState.value
    if (actualValueForSlider == null && keyOfParam != WallpaperConfigConstants.KEY_BLUR_ITERATIONS) { PlaceholderForAdjustmentArea("此参数不适用于当前P1样式"); return }
    var currentSliderPosition by remember(keyOfParam, actualValueForSlider) { mutableStateOf(actualValueForSlider?.let { mapActualValueToSliderPosition(keyOfParam, it) } ?: 0.5f) }
    val displayValueString = remember(keyOfParam, currentSliderPosition) {
        val actualVal = mapSliderPositionToActualValue(keyOfParam, currentSliderPosition)
        if (listOf(WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY, WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO, WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO, WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET, WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR, WallpaperConfigConstants.KEY_STYLE_B_MASK_ALPHA, WallpaperConfigConstants.KEY_STYLE_B_ROTATION_PARAM_A, WallpaperConfigConstants.KEY_STYLE_B_GAP_SIZE_RATIO, WallpaperConfigConstants.KEY_STYLE_B_GAP_POSITION_Y_RATIO).contains(keyOfParam)) String.format("%.2f", actualVal) else actualVal.roundToInt().toString()
    }
    Column(modifier = Modifier.padding(horizontal = 0.dp, vertical = 4.dp).defaultMinSize(minHeight = 64.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(subCategory.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, color = Color.White)
            Text(displayValueString, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
        }
        val steps = if (listOf(WallpaperConfigConstants.KEY_BLUR_ITERATIONS, WallpaperConfigConstants.KEY_STYLE_B_UPPER_MASK_MAX_ROTATION, WallpaperConfigConstants.KEY_STYLE_B_LOWER_MASK_MAX_ROTATION).contains(keyOfParam)) { val minVal = getMinRawValueForParam(keyOfParam).roundToInt(); val maxVal = getMaxRawValueForParam(keyOfParam).roundToInt(); (maxVal - minVal -1).coerceAtLeast(0) } else 0
        Slider(value = currentSliderPosition, onValueChange = { newPosition -> currentSliderPosition = newPosition; viewModel.updateAdvancedSettingRealtime(keyOfParam, mapSliderPositionToActualValue(keyOfParam, newPosition)) }, onValueChangeFinished = { viewModel.updateAdvancedSettingRealtime(keyOfParam, mapSliderPositionToActualValue(keyOfParam, currentSliderPosition)) }, valueRange = 0f..1f, steps = if (steps > 0) steps else 0, colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White.copy(alpha = 0.7f), inactiveTrackColor = Color.White.copy(alpha = 0.3f)), modifier = Modifier.fillMaxWidth().padding(top = 0.dp))
    }
}

// --- 辅助函数 (确保它们在文件末尾或可访问的作用域内) ---
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
        WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY -> 0.1f; WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO -> 0.01f; WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO -> 0.01f; WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET -> 0.0f; WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS -> 0f; WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR -> 0.01f; WallpaperConfigConstants.KEY_BLUR_ITERATIONS -> 1f;
        WallpaperConfigConstants.KEY_IMAGE_HEIGHT_RATIO -> WallpaperConfigConstants.MIN_HEIGHT_RATIO; WallpaperConfigConstants.KEY_P1_CONTENT_SCALE_FACTOR -> WallpaperConfigConstants.DEFAULT_P1_CONTENT_SCALE_FACTOR; WallpaperConfigConstants.KEY_P1_SHADOW_RADIUS -> 0f; WallpaperConfigConstants.KEY_P1_SHADOW_DX -> -20f; WallpaperConfigConstants.KEY_P1_SHADOW_DY -> 0f; WallpaperConfigConstants.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT -> 0f;
        WallpaperConfigConstants.KEY_STYLE_B_MASK_ALPHA -> 0.0f; WallpaperConfigConstants.KEY_STYLE_B_ROTATION_PARAM_A -> 0.0f; WallpaperConfigConstants.KEY_STYLE_B_GAP_SIZE_RATIO -> 0.0f; WallpaperConfigConstants.KEY_STYLE_B_GAP_POSITION_Y_RATIO -> 0.0f; WallpaperConfigConstants.KEY_STYLE_B_UPPER_MASK_MAX_ROTATION -> 0f; WallpaperConfigConstants.KEY_STYLE_B_LOWER_MASK_MAX_ROTATION -> 0f;
        else -> 0f
    }
}

fun getMaxRawValueForParam(paramKey: String): Float {
    return when (paramKey) {
        WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY -> 2.0f; WallpaperConfigConstants.KEY_P1_OVERLAY_FADE_RATIO -> 1.0f; WallpaperConfigConstants.KEY_P2_BACKGROUND_FADE_IN_RATIO -> 1.0f; WallpaperConfigConstants.KEY_BACKGROUND_INITIAL_OFFSET -> 1.0f; WallpaperConfigConstants.KEY_BACKGROUND_BLUR_RADIUS -> 25f; WallpaperConfigConstants.KEY_BLUR_DOWNSCALE_FACTOR -> 0.5f; WallpaperConfigConstants.KEY_BLUR_ITERATIONS -> 3f;
        WallpaperConfigConstants.KEY_IMAGE_HEIGHT_RATIO -> WallpaperConfigConstants.MAX_HEIGHT_RATIO; WallpaperConfigConstants.KEY_P1_CONTENT_SCALE_FACTOR -> 4.0f; WallpaperConfigConstants.KEY_P1_SHADOW_RADIUS -> 20f; WallpaperConfigConstants.KEY_P1_SHADOW_DX -> 20f; WallpaperConfigConstants.KEY_P1_SHADOW_DY -> 20f; WallpaperConfigConstants.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT -> 2560f;
        WallpaperConfigConstants.KEY_STYLE_B_MASK_ALPHA -> 1.0f; WallpaperConfigConstants.KEY_STYLE_B_ROTATION_PARAM_A -> 1.0f; WallpaperConfigConstants.KEY_STYLE_B_GAP_SIZE_RATIO -> 0.8f; WallpaperConfigConstants.KEY_STYLE_B_GAP_POSITION_Y_RATIO -> 1.0f; WallpaperConfigConstants.KEY_STYLE_B_UPPER_MASK_MAX_ROTATION -> 60f; WallpaperConfigConstants.KEY_STYLE_B_LOWER_MASK_MAX_ROTATION -> 60f;
        else -> 1f
    }
}
// In BottomSheetScreen.kt
fun handleSubCategoryAction(
    subCategory: SubCategory,
    viewModel: MainViewModel, // ViewModel 仍然需要，用于其他可能的非UI操作
    activityActions: MainActivityActions,
    context: Context,
    onHideSheet: () -> Unit,
    currentSelectedImageUri: Uri? // <--- 新增参数
) {
    Log.d("ConfigSheet", "SubCategory Action: ${subCategory.name} (ID: ${subCategory.id})")

    when (subCategory.id) {
        "sub_select_image" -> activityActions.requestReadMediaImagesPermission()
        "sub_apply_wallpaper" -> {
            if (currentSelectedImageUri != null) { // <--- 使用传入的参数
                activityActions.promptToSetWallpaper()
                onHideSheet()
            } else {
                Toast.makeText(context, context.getString(R.string.please_select_image_first_toast), Toast.LENGTH_SHORT).show()
            }
        }
        WallpaperConfigConstants.KEY_STYLE_B_MASKS_HORIZONTALLY_FLIPPED -> {
            viewModel.toggleStyleBMasksFlip() // 调用ViewModel中的方法
            // 确保没有其他编辑面板是打开的，或者这个点击不会尝试打开滑块区
            if (viewModel.subCategoryForAdjustmentIdInSheet.value != null) {
                viewModel.onSubCategoryForAdjustmentSelectedInSheet(null)
            }
        }
        else -> { Log.w("ConfigSheet", "Unhandled action for subCategory ID: ${subCategory.id}") }
    }
}

// CompositionLocal for providing MainViewModel to Previews, if needed.
// It's generally better to pass ViewModels as parameters to top-level screen Composables.
val AmbientMainViewModel = compositionLocalOf<MainViewModel> { error("No MainViewModel provided for Preview") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigBottomSheetContainer(viewModel: MainViewModel, activityActions: MainActivityActions) {
    val showSheet by viewModel.showConfigSheet.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { true })
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val scrollState = rememberScrollState()
    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.closeConfigSheet() }, sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            contentColor = Color.White, scrimColor = Color.Black.copy(alpha = 0.2f),
            dragHandle = { Box(modifier = Modifier.padding(vertical = 16.dp).width(40.dp).height(4.dp).background(color = Color.White.copy(alpha = 0.6f), shape = RoundedCornerShape(2.dp))) }
        ) {
            // Provide the ViewModel to the preview context if SubCategoryDisplayArea still uses AmbientMainViewModel
            CompositionLocalProvider(AmbientMainViewModel provides viewModel) {

                ConfigSheetContent(
                    viewModel = viewModel, activityActions = activityActions,
                    onHideSheet = { scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) viewModel.closeConfigSheet() } },
                    modifier = Modifier.fillMaxWidth().heightIn(max = configuration.screenHeightDp.dp * 0.9f).verticalScroll(scrollState).navigationBarsPadding()
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF202020)
@Composable
fun ConfigSheetContentWithStyleSelectionPreview() {
    val context = LocalContext.current
    val previewSafeViewModel = remember {
        object : MainViewModel(context.applicationContext as Application) {
            // For preview, directly initialize public-facing states if possible,
            // or ensure MainViewModel's init sets defaults that work for preview.
            // This anonymous object doesn't have the same private backing fields.
            // Instead, we override the public getters.
            override val p1StyleType: LiveData<Int> = MutableLiveData(WallpaperConfigConstants.DEFAULT_P1_STYLE_TYPE)
            override val showStyleSelection: StateFlow<Boolean> = MutableStateFlow(true) // Preview this state
            override val selectedImageUri: LiveData<Uri?> = MutableLiveData(null)
            override val selectedBackgroundColor: LiveData<Int> = MutableLiveData(Color(0xFF4CAF50).toArgb())
            override val colorPalette: LiveData<List<Int>> = MutableLiveData(listOf(Color(0xFFDB4437).toArgb(), Color(0xFF4285F4).toArgb(), Color(0xFF0F9D58).toArgb(), Color(0xFFF4B400).toArgb()))
            // Add other LiveData/StateFlow overrides as needed for the preview to function
        }
    }
    // Use CompositionLocalProvider correctly for the preview
    CompositionLocalProvider(AmbientMainViewModel provides previewSafeViewModel) {
        H2WallpaperTheme(darkTheme = true) {
            Surface(modifier = Modifier.fillMaxHeight(0.9f), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)) {
                ConfigSheetContent(viewModel = previewSafeViewModel, activityActions = object : MainActivityActions {
                    override fun requestReadMediaImagesPermission() {}
                    override fun promptToSetWallpaper() {}
                }, onHideSheet = {})
            }
        }
    }
}