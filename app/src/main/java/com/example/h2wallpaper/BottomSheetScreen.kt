// app/src/main/java/com/example/h2wallpaper/BottomSheetScreen.kt
package com.example.h2wallpaper

// ... (所有其他 imports 保持不变) ...
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke // 确保导入
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import androidx.compose.animation.animateContentSize // 新增


// --- 数据模型 (保持不变) ---
// ... (代码与上一条回复一致) ...
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
        SubCategory("sub_bg_color", "背景颜色", type = "action"),
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

// MainActivityActions 接口 (保持不变)
// ... (代码与上一条回复一致) ...
interface MainActivityActions {
    fun requestReadMediaImagesPermission()
    fun startSettingsActivity()
    fun promptToSetWallpaper()
}

// ConfigSheetContent Composable (保持不变)
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

    // 修改点：从 ViewModel 观察状态
    val selectedMainCategoryId by viewModel.selectedMainCategoryIdInSheet.collectAsState()
    val subCategoryForAdjustmentId by viewModel.subCategoryForAdjustmentIdInSheet.collectAsState()

    // 根据 ID 找到对应的对象
    val selectedMainCategory = remember(selectedMainCategoryId) {
        mainCategoriesData.find { it.id == selectedMainCategoryId } ?: mainCategoriesData.firstOrNull()
    }
    val subCategoryForAdjustment = remember(subCategoryForAdjustmentId, selectedMainCategory) {
        selectedMainCategory?.subCategories?.find { it.id == subCategoryForAdjustmentId && it.type == "parameter_slider"}
    }

    val isP1EditMode by viewModel.isP1EditMode.observeAsState(initial = false)

    val onSliderValueChangeFinished: (String, Float) -> Unit = { paramKey, newSliderPosition ->
        // ... (内容保持不变) ...
        val editor = prefs.edit()
        val actualValue = mapSliderPositionToActualValue(paramKey, newSliderPosition, prefs)
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
        }
        editor.apply()
        Log.d("ConfigSheet", "Slider for $paramKey saved with actual value: $actualValue (slider: $newSliderPosition)")
        viewModel.saveNonBitmapConfigAndUpdateVersion()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
    ) {
        // 1. 参数调整区域 (或占位符)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            val currentAdjustmentCategoryLocal = subCategoryForAdjustment // 使用从ID衍生的对象
            val showParameterControls = currentAdjustmentCategoryLocal != null // 因为我们已确保它是parameter_slider类型

            Crossfade(
                targetState = showParameterControls,
                label = "ParameterAdjustmentCrossfade"
            ) { shouldShowControls ->
                if (shouldShowControls && currentAdjustmentCategoryLocal != null) {
                    ParameterAdjustmentSection(
                        subCategory = currentAdjustmentCategoryLocal,
                        keyOfParam = currentAdjustmentCategoryLocal.id,
                        prefs = prefs,
                        onFinalValueChange = { paramKey, finalSliderPos ->
                            onSliderValueChangeFinished(paramKey, finalSliderPos)
                        }
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "选择下方参数项进行调整",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        // 2. 主分类选择行
        MainCategoryTabs(
            categories = mainCategoriesData,
            selectedCategory = selectedMainCategory, // 使用从ID衍生的对象
            onCategorySelected = { category ->
                // 修改点：调用 ViewModel 更新状态
                viewModel.onMainCategorySelectedInSheet(category.id)
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)
        )

        // 3. 子分类/操作显示区域
        SubCategoryDisplayArea(
            subCategories = selectedMainCategory?.subCategories ?: emptyList(),
            currentlyAdjusting = subCategoryForAdjustment, // 使用从ID衍生的对象
            onSubCategoryClick = { subCategory ->
                if (subCategory.type == "parameter_slider") {
                    // 修改点：调用 ViewModel 更新状态
                    viewModel.onSubCategoryForAdjustmentSelectedInSheet(subCategory.id)
                } else {
                    // 动作类，清除调整项并执行动作
                    viewModel.onSubCategoryForAdjustmentSelectedInSheet(null) // 清除调整项
                    handleSubCategoryAction(subCategory, viewModel, activityActions, context, onHideSheet)
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
    }
}


// MainCategoryTabs Composable (保持不变)
// ... (代码与上一条回复一致) ...
@Composable
fun MainCategoryTabs(
    categories: List<MainCategory>,
    selectedCategory: MainCategory?,
    onCategorySelected: (MainCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(categories) { category ->
            val isSelected = category == selectedCategory
            TextButton(
                onClick = { onCategorySelected(category) },
                shape = RoundedCornerShape(12.dp), // 增加圆角
                colors = ButtonDefaults.textButtonColors(
                    containerColor = if (isSelected) Color.White.copy(alpha = 0.15f) else Color.Transparent, // 选中时给一点半透明白色背景
                    contentColor = Color.White // 标签文本颜色改为白色
                ),
                border = if (isSelected) BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)) else null, // 选中时给一个半透明白色边框
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp) // 调整内边距
            ) {
                Text(
                    category.name,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 14.sp // 稍微调整字体大小
                )
            }
        }
    }
}

// SubCategoryDisplayArea Composable (保持不变)
// ... (代码与上一条回复一致) ...
@Composable
fun SubCategoryDisplayArea(
    subCategories: List<SubCategory>,
    currentlyAdjusting: SubCategory?,
    onSubCategoryClick: (SubCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    if (subCategories.isEmpty()) {
        Box(modifier = modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Text(
                "此分类下无具体选项",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = Color.White.copy(alpha = 0.7f) // 文本颜色
            )
        }
        return
    }

    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(subCategories) { subCategory ->
            SubCategoryCard(
                subCategory = subCategory,
                onClick = { onSubCategoryClick(subCategory) },
                isHighlighted = currentlyAdjusting == subCategory && subCategory.type == "parameter_slider"
            )
        }
    }
}


// --- SubCategoryCard Composable ---
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubCategoryCard(
    subCategory: SubCategory,
    onClick: () -> Unit,
    isHighlighted: Boolean
) {
    // 卡片的大小和形状可以与 MainCategoryTabs 中的 TextButton 类似或协调
    // 为了达到 TextButton 的效果，我们也可以直接使用 TextButton 或 Surface 来构建
    // 这里我们继续使用 Card，但调整其参数
    Card(
        onClick = onClick,
        modifier = Modifier
            .widthIn(min = 80.dp) // 最小宽度，允许内容扩展
            .defaultMinSize(minHeight = 70.dp) // 与 MainCategoryTabs 的 TextButton 高度感类似
            .padding(vertical = 4.dp), // 给卡片本身一点垂直外边距，使其不至于太拥挤
        shape = RoundedCornerShape(12.dp), // 与主分类一致的圆角
        colors = CardDefaults.cardColors(
            // 修改点：选中时背景，未选中时透明
            containerColor = if (isHighlighted) Color.White.copy(alpha = 0.15f) else Color.Transparent,
            contentColor = Color.White // 内容（文字和图标）颜色统一为白色
        ),
        // 修改点：选中时边框，未选中时无边框
        border = if (isHighlighted) BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)) else null
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp) // 调整内边距以适应内容
                .fillMaxHeight(), // 让 Column 填充 Card 的高度
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = if (subCategory.type == "parameter_slider") Icons.Filled.Tune else Icons.Filled.ChevronRight,
                contentDescription = subCategory.name,
                modifier = Modifier.size(24.dp), // 图标大小调整
                tint = Color.White // 图标颜色
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                subCategory.name,
                style = MaterialTheme.typography.labelSmall, // 使用 labelSmall 或 bodySmall
                textAlign = TextAlign.Center,
                maxLines = 2,
                fontSize = 12.sp, // 字体大小
                color = Color.White, // 文字颜色
                fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Medium // 高亮时加粗
            )
        }
    }
}

// ParameterAdjustmentSection Composable (保持不变)
// ... (代码与上一条回复一致) ...
@Composable
fun ParameterAdjustmentSection(
    subCategory: SubCategory,
    keyOfParam: String,
    prefs: SharedPreferences,
    onFinalValueChange: (paramKey: String, finalSliderPosition: Float) -> Unit
) {
    var internalSliderPosition by remember(keyOfParam) {
        mutableStateOf(getInitialSliderPosition(keyOfParam, prefs))
    }

    val currentValueDisplay = mapSliderPositionToActualValue(keyOfParam, internalSliderPosition, prefs)

    Column(modifier = Modifier.padding(horizontal = 0.dp, vertical = 4.dp)) { // 移除父级Column的 horizontal padding
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                subCategory.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = Color.White // 文本颜色
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
                color = Color.White.copy(alpha = 0.85f) // 当前值文本颜色，可以略微透明
            )
        }
        Slider(
            value = internalSliderPosition,
            onValueChange = { newPosition -> internalSliderPosition = newPosition },
            valueRange = 0f..1f,
            steps = getStepsForParam(keyOfParam, prefs),
            modifier = Modifier.fillMaxWidth().padding(top = 0.dp),
            onValueChangeFinished = {
                onFinalValueChange(keyOfParam, internalSliderPosition)
            },
            colors = SliderDefaults.colors( // 自定义滑块颜色
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