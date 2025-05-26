// app/src/main/java/com/example/h2wallpaper/BottomSheetScreen.kt
package com.example.h2wallpaper

// ... (所有 import 和数据模型，MainActivityActions 接口保持不变) ...
import android.app.Application
import android.content.Context

import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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




// --- 数据模型 (保持不变) ---
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
// --- 结束数据模型 ---

// MainActivityActions 接口保持不变
interface MainActivityActions {
    fun requestReadMediaImagesPermission()
    fun startSettingsActivity()
    fun promptToSetWallpaper()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigSheetContent(
    viewModel: MainViewModel,
    activityActions: MainActivityActions,
    onHideSheet: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(WallpaperConfigConstants.PREFS_NAME, Context.MODE_PRIVATE) }

    var selectedMainCategory by remember { mutableStateOf(mainCategoriesData.firstOrNull()) }
    var subCategoryForAdjustment by remember(selectedMainCategory) { mutableStateOf<SubCategory?>(null) }

    val isP1EditMode by viewModel.isP1EditMode.observeAsState(initial = false)


    val onSliderValueChangeFinished: (String, Float) -> Unit = { paramKey, newSliderPosition ->
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
        modifier = Modifier
            .fillMaxHeight(0.3f) // 最大高度为屏幕的30%
            .navigationBarsPadding()
            .fillMaxWidth()
    ) {
        // 1. 参数调整区域 (或占位符)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp) // 给Box一些内边距
                // 使用 height 而不是 defaultMinSize 来明确控制这个区域的高度
                // 这个高度应该与 ParameterAdjustmentSection 的内容高度大致匹配，或者略大一些
                .height(80.dp) // 尝试让Box包裹其内容，但如果内容是空的占位符，它可能很小
            // 或者给一个明确的最小高度，如果内容为空时也想它占位
            // .height(70.dp) // 例如，固定参数区高度为70dp
            // 如果 ParameterAdjustmentSection 实际需要更高，那么这个固定值需要调整
            // 更好的方式是让它在有内容时自适应，没内容时高度为0或一个小的固定值。
        ) {
            if (subCategoryForAdjustment != null && subCategoryForAdjustment!!.type == "parameter_slider") {
                ParameterAdjustmentSection(
                    subCategory = subCategoryForAdjustment!!,
                    keyOfParam = subCategoryForAdjustment!!.id,
                    prefs = prefs,
                    onFinalValueChange = { paramKey, finalSliderPos ->
                        onSliderValueChangeFinished(paramKey, finalSliderPos)
                    }
                )
            } else {
                // 当没有参数调整时，显示占位符文本，确保这个Box仍然有一定高度
                // 以免主分类跳动。
                // 如果上面的 .height(IntrinsicSize.Min) 或 .height(70.dp) 已经确保了高度，
                // 这里的 fillMaxSize 作用于内部的 Box，使其填充父 Box。
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("选择下方参数项进行调整", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }

        Divider(modifier = Modifier.padding(bottom = 8.dp)) // 调整Divider位置

        // 2. 主分类选择行
        //    它的高度由内容决定 (TextButton 的高度)
        MainCategoryTabs(
            categories = mainCategoriesData,
            selectedCategory = selectedMainCategory,
            onCategorySelected = { category ->
                selectedMainCategory = category
                subCategoryForAdjustment = null
            },
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Divider(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))

        // 3. 子分类/操作显示区域
        //    确保这个区域有空间显示
        SubCategoryDisplayArea(
            subCategories = selectedMainCategory?.subCategories ?: emptyList(),
            currentlyAdjusting = subCategoryForAdjustment,
            onSubCategoryClick = { subCategory ->
                if (subCategory.type == "parameter_slider") {
                    subCategoryForAdjustment = if (subCategoryForAdjustment == subCategory) null else subCategory
                } else {
                    subCategoryForAdjustment = null
                    handleSubCategoryAction(subCategory, viewModel, activityActions, context, onHideSheet)
                }
            },
            // 使用 weight 来填充剩余空间，但要确保前面组件的高度是固定的或可预测的
            modifier = Modifier.weight(1f).fillMaxWidth()
        )

        // 底部留白
        Spacer(modifier = Modifier.height(16.dp))
    }
}


// --- MainCategoryTabs Composable ---
@Composable
fun MainCategoryTabs(
    categories: List<MainCategory>,
    selectedCategory: MainCategory?,
    onCategorySelected: (MainCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    // ... (代码与上一版本一致) ...
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(categories) { category ->
            val isSelected = category == selectedCategory
            TextButton(
                onClick = { onCategorySelected(category) },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.textButtonColors(

                    containerColor =Color.Transparent,
                    contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                ),
                border =  null,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    category.name,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 13.sp
                )
            }
        }
    }
}

// --- SubCategoryDisplayArea Composable ---
@Composable
fun SubCategoryDisplayArea(
    subCategories: List<SubCategory>,
    currentlyAdjusting: SubCategory?,
    onSubCategoryClick: (SubCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    // ... (代码与上一版本一致，但要确保 modifier 被正确应用) ...
    if (subCategories.isEmpty()) {
        Box(modifier = modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) { // 应用 modifier
            Text(
                "此分类下无具体选项",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    LazyRow(
        modifier = modifier // 应用传入的 modifier (包含了 weight(1f))
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
    // ... (代码与上一版本一致) ...
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .width(100.dp)
            .height(80.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent, // <<< 使卡片背景透明
            contentColor = if (isHighlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant // 内容颜色可能需要调整
        ),
        border =null// if (isHighlighted) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = if (subCategory.type == "parameter_slider") Icons.Filled.Tune else Icons.Filled.ChevronRight,
                contentDescription = subCategory.name,
                modifier = Modifier.size(24.dp),
                tint = if (isHighlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                subCategory.name,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                color = if (isHighlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// --- ParameterAdjustmentSection Composable ---
@Composable
fun ParameterAdjustmentSection(
    subCategory: SubCategory,
    keyOfParam: String,
    prefs: SharedPreferences,
    onFinalValueChange: (paramKey: String, finalSliderPosition: Float) -> Unit
    // ... (代码与上一版本一致) ...
) {
    var internalSliderPosition by remember(keyOfParam) {
        mutableStateOf(getInitialSliderPosition(keyOfParam, prefs))
    }

    val minRaw = getMinRawValueForParam(keyOfParam, prefs)
    val maxRaw = getMaxRawValueForParam(keyOfParam, prefs)
    val currentValueDisplay = mapSliderPositionToActualValue(keyOfParam, internalSliderPosition, prefs)

    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                subCategory.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
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
                color = MaterialTheme.colorScheme.primary
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
            }
        )
    }
}

// --- 辅助函数 (getInitialSliderPosition, mapSliderPositionToActualValue, getMinRawValueForParam, getMaxRawValueForParam, getStepsForParam, handleSubCategoryAction) ---
// 这些函数保持不变，确保它们正确无误
// ... (复制粘贴上一版本中的这些辅助函数) ...
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
        WallpaperConfigConstants.KEY_P1_SHADOW_DY -> (maxRaw - minRaw).toInt() -1
        WallpaperConfigConstants.KEY_SCROLL_SENSITIVITY -> ((maxRaw - minRaw) * 10).toInt() -1
        WallpaperConfigConstants.KEY_P1_IMAGE_BOTTOM_FADE_HEIGHT -> if (maxRaw > 0) 255 else 0
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

    when (subCategory.id) {
        "sub_select_image" -> if (!isP1EditMode) activityActions.requestReadMediaImagesPermission()
        "sub_bg_color" -> Toast.makeText(context, "颜色选择功能待集成或放在主分类下", Toast.LENGTH_SHORT).show()
        "sub_apply_wallpaper" -> {
            if (!isP1EditMode && viewModel.selectedImageUri.value != null) {
                activityActions.promptToSetWallpaper()
                onHideSheet()
            } else if (viewModel.selectedImageUri.value == null) {
                Toast.makeText(context, context.getString(R.string.please_select_image_first_toast), Toast.LENGTH_SHORT).show()
            }
        }
        "sub_advanced_settings" -> {
            if (!isP1EditMode) {
                activityActions.startSettingsActivity()
                onHideSheet()
            }
        }
        "p1_customize_action" -> {
            if (viewModel.selectedImageUri.value != null) {
                viewModel.toggleP1EditMode()
                onHideSheet()
            } else {
                Toast.makeText(context, context.getString(R.string.please_select_image_first_toast), Toast.LENGTH_SHORT).show()
            }
        }
        else -> {
            Toast.makeText(context, "选择了动作: ${subCategory.name}", Toast.LENGTH_SHORT).show()
        }
    }
}


// --- ConfigBottomSheetContainer Composable (保持不变) ---
// --- ConfigBottomSheetContainer Composable ---
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

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.closeConfigSheet() },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            // 让 BottomSheet 背景更透明，以便更好地看到白色字体
            containerColor = MaterialTheme.colorScheme.surfaceVariant // 改回一个稍微不那么透明的背景，或者用深色背景
                .copy(alpha = 0.6f), // 例如 60% 不透明度，确保与白色字体对比度
            contentColor = Color.White, // 将默认内容颜色设为白色
            scrimColor = Color.Black.copy(alpha = 0.5f), // Scrim 可以深一些
            dragHandle = { // 自定义拖拽把手为白色系
                Box(
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .width(32.dp)
                        .height(4.dp)
                        .background(
                            color = Color.White.copy(alpha = 0.6f), // 把手颜色
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
                }
            )
        }
    }
}




// --- 预览代码 ---
@Preview(showBackground = true, name = "配置选项内容预览 (Tabbed Horizontal Sub)")
@Composable
fun ConfigSheetContentTabbedPreview() {
    // ... (预览代码与上一版本一致) ...
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
    H2WallpaperTheme {
        Surface(modifier = Modifier.fillMaxHeight(0.3f)) {
            ConfigSheetContent(
                viewModel = previewSafeViewModel,
                activityActions = fakeActions,
                onHideSheet = {}
            )
        }
    }
}
@Composable
fun TransparentBorderlessButton(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        // TextButton 默认背景就是透明的，边框也没有明显的外部轮廓
        // 你可以通过 colors 参数进一步控制不同状态下的颜色
        colors = ButtonDefaults.textButtonColors(
            containerColor = Color.Transparent, // 明确设置容器颜色为透明
            contentColor = MaterialTheme.colorScheme.primary // 设置文字/图标颜色，可以根据你的主题调整
        )
    ) {
        Text(text)
    }
}