package com.scheda.app.ui.components

import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Rect
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.roundToInt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.scheda.app.ui.components.GlassOverlayEffect
import com.scheda.app.file.RecoveryManager
import com.scheda.app.file.SchedaSerializer
import com.scheda.app.file.ShareUtil
import com.scheda.app.file.StorageManager
import com.scheda.app.model.DrawingPrimitive
import com.scheda.app.model.Layer
import com.scheda.app.model.LineStyle
import com.scheda.app.model.LineType
import com.scheda.app.file.RecycleBinManager
import com.scheda.app.model.ToolType
import com.scheda.app.viewmodel.DrawingViewModel
import com.scheda.app.viewmodel.HomeViewModel

// ═══════════════════════════════════════════════════════════
//  ViewModel Factories
// ═══════════════════════════════════════════════════════════

class HomeViewModelFactory(
    private val storageManager: StorageManager,
    private val shareUtil: ShareUtil,
    private val serializer: SchedaSerializer,
    private val recycleBin: RecycleBinManager
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        HomeViewModel(storageManager, shareUtil, serializer, recycleBin) as T
}

class DrawingViewModelFactory(
    private val storageManager: StorageManager,
    private val serializer: SchedaSerializer,
    private val recoveryManager: RecoveryManager
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        DrawingViewModel(storageManager, serializer, recoveryManager, storageManager.context.applicationContext) as T
}

// ═══════════════════════════════════════════════════════════
//  BottomToolbar
// ═══════════════════════════════════════════════════════════

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun BottomToolbar(
    viewModel: DrawingViewModel,
    onShowNumberDialog: () -> Unit,
    onShowTextDialog: () -> Unit = {},
    onShowRangeDialog: () -> Unit = {},
    onShowNumArrayDialog: () -> Unit = {},
    backdrop: com.kyant.backdrop.Backdrop? = null,
    modifier: Modifier = Modifier,
) {
    val glass = backdrop != null
    val fg: (Color) -> Color = { c -> glassAdapt(c, glass) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .glassSurface(
                backdrop = backdrop,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                fallback = Color(0xFF2A2A2A),
            )
    ) {
        // 虚线间距弹窗（点“比例”文字弹出，位置贴着文字上方）
        var dashPopup by remember { mutableStateOf(false) }
        var dashAnchor by remember { mutableStateOf<Rect?>(null) }
        Column(modifier = Modifier.navigationBarsPadding().padding(horizontal = 6.dp, vertical = 2.dp)) {
            // ── Row 2 ──
            Row(
                modifier = Modifier.fillMaxWidth().height(46.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 线型 — 合并为一个按钮 + 弹出菜单
                var ltExpanded by remember { mutableStateOf(false) }
                val ctLineType = viewModel.currentLineStyle.type
                val ctLabel = when (ctLineType) { LineType.DASHED -> "虚线"; LineType.LIGHTNING -> "闪电"; else -> "实线" }
                Box {
                    DarkPill(onClick = { ltExpanded = true }, modifier = Modifier.width(56.dp), glass = glass, backdrop = backdrop) {
                        Text(ctLabel, fontSize = 11.sp, maxLines = 1, color = fg(Color.White), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(2.dp))
                        Text("▲", fontSize = 6.sp, color = fg(Color(0xFFAAAAAA)))
                    }
                    DarkDropdownMenu(
                        expanded = ltExpanded,
                        onDismissRequest = { ltExpanded = false }
                    ) {
                        listOf(
                            LineType.SOLID to "实线",
                            LineType.DASHED to "虚线",
                            LineType.LIGHTNING to "闪电"
                        ).forEachIndexed { index, (lt, label) ->
                            DarkMenuItem(
                                label = label,
                                selected = ctLineType == lt,
                                onClick = { viewModel.setLineStyle(LineStyle(type = lt)); ltExpanded = false }
                            )
                            if (index < 2) {
                                HorizontalDivider(thickness = 0.5.dp, color = if (glass) Color(0x22000000) else Color(0xFF3A3A3A))
                            }
                        }
                    }
                }
                Spacer(Modifier.width(4.dp))
                // 线型预览 — 与实际绘制效果一致
                val prevColor = fg(Color(0xFFCCCCCC))
                val ls = viewModel.currentLineStyle
                val sw = viewModel.strokeWidth
                val pxScale = LocalDensity.current.density
                Canvas(modifier = Modifier.weight(1f).height(18.dp).padding(vertical = 2.dp)) {
                    val h = size.height / 2f
                    val maxW = size.height
                    val lineW = (maxW * 0.1f).coerceIn(3f, maxW)
                    when (ls.type) {
                        LineType.DASHED -> {
                            val p = Path().apply { moveTo(0f, h); lineTo(size.width, h) }
                            var dashPx = ls.dashLength * pxScale
                            var gapPx = ls.gapLength * pxScale * 1.2f
                            if (dashPx < 6f) dashPx = 6f
                            gapPx = gapPx.coerceAtLeast(dashPx * 0.5f).coerceAtLeast(4f)
                            drawPath(p, prevColor, style = Stroke(
                                width = lineW, cap = StrokeCap.Round,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashPx, gapPx))
                            ))
                        }
                        LineType.LIGHTNING -> {
                            val baseW = (lineW * 0.5f).coerceAtLeast(3f)
                            val p = Path().apply { moveTo(0f, h); lineTo(size.width, h) }
                            drawPath(p, prevColor, style = Stroke(width = baseW, cap = StrokeCap.Round))
                            val s = (size.height / 3f).coerceAtMost(size.width / 5f)
                            val xStroke = lineW.coerceAtLeast(4f)
                            listOf(size.width / 3f, size.width * 2f / 3f).forEach { px ->
                                drawLine(prevColor, Offset(px - s, h - s), Offset(px + s, h + s), strokeWidth = xStroke, cap = StrokeCap.Round)
                                drawLine(prevColor, Offset(px - s, h + s), Offset(px + s, h - s), strokeWidth = xStroke, cap = StrokeCap.Round)
                            }
                        }
                        else -> drawLine(prevColor, Offset(0f, h), Offset(size.width, h), strokeWidth = lineW, cap = StrokeCap.Round)
                    }
                }
                Spacer(Modifier.width(4.dp))
                // 线宽
                Text("线宽", fontSize = 10.sp, color = fg(Color(0xFFCCCCCC)))
                Spacer(Modifier.width(6.dp))
                CompactSlider(
                    value = viewModel.toolbarStrokeWidth,
                    onValueChange = { viewModel.setStrokeWidth(it) },
                    valueRange = 1f..20f,
                    modifier = Modifier.width(76.dp).height(20.dp),
                    thumbSize = 8.dp
                )
                Spacer(Modifier.width(6.dp))
                Text("${viewModel.toolbarStrokeWidth.toInt()}", fontSize = 10.sp, color = fg(Color(0xFFCCCCCC)), modifier = Modifier.width(14.dp))
                Spacer(Modifier.width(4.dp))
                // 线型比例：无选中=全局；有选中=作用于选中元素（多值不一显示"多"、起点取最大值、调节统一设值）
                val selScales = viewModel.selection.selectedIndices.mapNotNull { viewModel.primitives.getOrNull(it)?.lineScaleFactor }
                val scaleValue = selScales.maxOrNull() ?: viewModel.globalLineScale
                Text("比例", fontSize = 10.sp, color = fg(Color(0xFFCCCCCC)),
                    modifier = Modifier
                        .onGloballyPositioned { dashAnchor = it.boundsInWindow() }
                        .clickable { dashPopup = true })
                Spacer(Modifier.width(6.dp))
                CompactSlider(
                    value = scaleValue,
                    onValueChange = { v ->
                        if (selScales.isEmpty()) viewModel.setGlobalLineScale(v)
                        else viewModel.updateSelectedLineScale(v)
                    },
                    valueRange = 0.25f..4f,
                    modifier = Modifier.width(76.dp).height(20.dp),
                    thumbSize = 8.dp
                )
                Spacer(Modifier.width(6.dp))
                Text("%.1f".format(scaleValue), fontSize = 10.sp, color = fg(Color(0xFFCCCCCC)), modifier = Modifier.width(18.dp))
                Spacer(Modifier.width(4.dp))
                // 颜色圆 — 点击弹出颜色选择
                var colorDlg by remember { mutableStateOf(false) }
                Box(Modifier.size(24.dp).clip(CircleShape).background(viewModel.toolbarColor)
                    .border(1.5f.dp, fg(Color(0xFFCCCCCC)), CircleShape)
                    .clickable { colorDlg = true })
                if (colorDlg) ColorPickerDialog(viewModel.toolbarColor, { viewModel.setColor(it) }, { colorDlg = false })
            }

// ── Row 3 (bottom): 工具 ─────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 文字+数字合并为弹出菜单（套 Box 里，避免 DropdownMenu 参与 Row 测量）
                Box(Modifier.weight(1f)) {
                    var textNumExpanded by remember { mutableStateOf(false) }
                    val isTextNum = viewModel.currentTool == ToolType.ANNOTATE || viewModel.currentTool == ToolType.TEXT || viewModel.currentTool == ToolType.RANGE || viewModel.currentTool == ToolType.NUM_ARRAY
                    val label = when (viewModel.currentTool) {
                        ToolType.ANNOTATE -> "数字"
                        ToolType.TEXT -> "文字"
                        ToolType.RANGE -> "区间"
                        ToolType.NUM_ARRAY -> "数阵"
                        else -> "文字"
                    }
                    DarkPill(onClick = { textNumExpanded = true },
                        modifier = Modifier.fillMaxWidth(), glass = glass, selected = isTextNum, backdrop = backdrop) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label, fontSize = 11.sp, maxLines = 1,
                                color = if (isTextNum) fg(Color.White) else fg(Color(0xFFAAAAAA)))
                            Spacer(Modifier.width(2.dp))
                            Text("▲", fontSize = 6.sp, color = fg(Color(0xFF999999)))
                        }
                    }
                    DarkDropdownMenu(
                        expanded = textNumExpanded,
                        onDismissRequest = { textNumExpanded = false }
                    ) {
                        listOf(
                            Triple("文字", ToolType.TEXT, onShowTextDialog),
                            Triple("数字", ToolType.ANNOTATE, onShowNumberDialog),
                            Triple("区间数字", ToolType.RANGE, onShowRangeDialog),
                            Triple("数字阵列", ToolType.NUM_ARRAY, onShowNumArrayDialog)
                        ).forEachIndexed { index, (itemLabel, tool, action) ->
                            DarkMenuItem(
                                label = itemLabel,
                                selected = viewModel.currentTool == tool,
                                onClick = { textNumExpanded = false; viewModel.setTool(tool); action() }
                            )
                            if (index < 3) {
                                HorizontalDivider(thickness = 0.5.dp, color = if (glass) Color(0x22000000) else Color(0xFF3A3A3A))
                            }
                        }
                    }
                }

                val otherTools = listOf(
                    ToolType.FREEHAND to "手绘",
                    ToolType.RECTANGLE to "矩形",
                    ToolType.CIRCLE to "圆形",
                    ToolType.LINE to "直线",
                    ToolType.SELECT to "选择",
                    ToolType.ERASER to "橡皮"
                )
                otherTools.forEach { (tool, label) ->
                    ToolBtn(viewModel, tool, label, onShowNumberDialog, onShowTextDialog, glass = glass, backdrop = backdrop)
                }
            }
        }

        // 虚线间距面板：贴着“比例”文字上方弹出
        if (dashPopup) {
            val b = dashAnchor
            val cfg = androidx.compose.ui.platform.LocalConfiguration.current
            val panelW = with(LocalDensity.current) { 228.dp.roundToPx() }
            val screenW = with(LocalDensity.current) { cfg.screenWidthDp.dp.roundToPx() }
            val panelOffsetY = with(LocalDensity.current) { 96.dp.roundToPx() }
            Popup(
                offset = if (b != null)
                    IntOffset(
                        b.left.roundToInt().coerceIn(8, (screenW - panelW - 8).coerceAtLeast(8)),
                        (b.top.roundToInt() - panelOffsetY).coerceAtLeast(8)
                    )
                else IntOffset.Zero,
                onDismissRequest = { dashPopup = false },
                properties = PopupProperties(focusable = true)
            ) {
                DashGapPanel(viewModel)
            }
        }
    }
}

/**
 * 虚线间距面板：一个滑块 = 间隔倍率（1× 对应 dash 12 / gap 8）。
 * 有选中元素时改选中元素的虚线间隔，无选中时改当前线型（之后画的虚线生效）。
 */
@Composable
private fun DashGapPanel(viewModel: DrawingViewModel) {
    val selDashes = viewModel.selection.selectedIndices.mapNotNull {
        viewModel.primitives.getOrNull(it)?.lineStyle?.dashLength
    }
    val baseMult = ((selDashes.maxOrNull() ?: viewModel.currentLineStyle.dashLength) / 12f)
        .coerceIn(0.5f, 5f)
    var mult by remember { mutableFloatStateOf(baseMult) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xF52A2A2A),
        shadowElevation = 10.dp,
        modifier = Modifier.width(228.dp)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("虚线间距", fontSize = 12.sp, color = Color(0xFFDDDDDD), fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text("%.1f×".format(mult), fontSize = 11.sp, color = Color(0xFF64B5F6))
            }
            Spacer(Modifier.height(8.dp))
            CompactSlider(
                value = mult,
                onValueChange = { v ->
                    mult = v
                    if (selDashes.isEmpty()) viewModel.setCurrentDashScale(v)
                    else viewModel.updateSelectedDashScale(v)
                },
                valueRange = 0.5f..5f,
                modifier = Modifier.fillMaxWidth().height(24.dp),
                thumbSize = 10.dp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "线段 %.0f   间隔 %.0f".format(12f * mult, 8f * mult),
                fontSize = 10.sp, color = Color(0xFF999999)
            )
        }
    }
}

/**
 * Dark pill style for toolbar buttons — matches the floating status bar's
 * rounded dark chip aesthetic without changing any existing functionality.
 */
@Composable
private fun DarkPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glass: Boolean = false,
    selected: Boolean = false,
    backdrop: com.kyant.backdrop.Backdrop? = null,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val brush: androidx.compose.ui.graphics.Brush = when {
        !glass -> androidx.compose.ui.graphics.SolidColor(Color(0xDD333333))
        selected -> androidx.compose.ui.graphics.Brush.verticalGradient(
            listOf(Color(0xF21565C0), Color(0xD91565C0))
        )
        else -> androidx.compose.ui.graphics.Brush.verticalGradient(
            listOf(Color(0xC7FFFFFF), Color(0x66E3F2FD))
        )
    }
    // 外层不裁切：选中的彩边光晕要能画到按钮外面，裁切只作用在底色层上
    Box(modifier.height(30.dp)) {
        Box(
            Modifier
                .matchParentSize()
                .then(
                    if (glass && backdrop != null) {
                        // 真玻璃：膜色渐变照旧，边缘光由库 Highlight 提供（与全 App 统一口径）
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { shape },
                            effects = {
                                vibrancy()
                                blur(2.dp.toPx())
                                lens(12.dp.toPx(), 24.dp.toPx())
                            },
                            highlight = { glassHighlight() },
                            onDrawSurface = { drawRect(brush) }
                        )
                    } else {
                        // 无捕获源（非玻璃模式 / 降级）：静态膜 + 渐变描边模拟受光
                        Modifier
                            .clip(shape)
                            .background(brush)
                            .border(
                                width = 0.8.dp,
                                brush = if (glass) androidx.compose.ui.graphics.Brush.verticalGradient(
                                    listOf(Color.White, Color.White.copy(alpha = 0.35f))
                                ) else androidx.compose.ui.graphics.SolidColor(Color.Transparent),
                                shape = shape
                            )
                    }
                )
        )
        TextButton(
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            modifier = Modifier.fillMaxSize(),
            colors = ButtonDefaults.textButtonColors(
                contentColor = if (!glass || selected) Color.White else Color(0xFF2A2A2A)
            )
        ) {
            content()
        }
        if (selected) {
            // 选中标记：一圈彩虹描边（8 秒转一圈；转速常驻不能太快，画图时抢注意力）
            Box(
                Modifier
                    .matchParentSize()
                    .auroraRing(
                        cornerRadius = 16.dp,
                        ringWidth = 1.6.dp,
                        glowWidth = 3.5.dp,
                        glowStrength = 0.5f,
                        spinSeconds = 8
                    )
            )
        }
    }
}

/** Deep-blue pill for clickable value displays (number/range). */
@Composable
private fun BluePill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF1565C0), shadowElevation = 2.dp,
        modifier = modifier.height(30.dp)) {
        TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.height(30.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
            content()
        }
    }
}

/** Dark dropdown menu: uses SchedaDropdownMenu (no forced 8dp vertical padding). BoxScope 扩展：菜单要捕获父 Box（锚点）边界。 */
@Composable
private fun BoxScope.DarkDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    SchedaDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        minWidth = 120.dp,
        content = content
    )
}

@Composable
private fun DarkMenuItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) Color(0xFF1565C0) else Color.Transparent
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(label, fontSize = 14.sp, color = if (selected) Color.White else Color(0xFF2A2A2A))
    }
}

@Composable
private fun RowScope.ToolBtn(
    viewModel: DrawingViewModel,
    tool: ToolType,
    label: String,
    onShowNumberDialog: () -> Unit,
    onShowTextDialog: () -> Unit = {},
    glass: Boolean = false,
    backdrop: com.kyant.backdrop.Backdrop? = null,
) {
    val selected = viewModel.currentTool == tool
    DarkPill(onClick = {
        viewModel.setTool(tool)
        when (tool) {
            ToolType.ANNOTATE -> onShowNumberDialog()
            ToolType.TEXT -> onShowTextDialog()
            else -> {}
        }
    }, modifier = Modifier.weight(1f), glass = glass, selected = selected, backdrop = backdrop) {
        Text(label, fontSize = 11.sp,
            color = if (!glass) (if (selected) Color.White else Color(0xFFAAAAAA))
                    else (if (selected) Color.White else Color(0xFF2A2A2A)),
            maxLines = 1)
    }
}

// ═════════════════════════════════════════════════════════════════
//  LayerDialog
// ═════════════════════════════════════════════════════════════════

private class LayerDragState(
    val startIndex: Int,
    val startId: Int,
    var offsetY: Float
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LayerDialog(
    viewModel: DrawingViewModel,
    onDismiss: () -> Unit
) {
            GlassOverlayEffect()
    val layers = viewModel.layers
    val activeId = viewModel.activeLayerId
    var newLayerName by remember { mutableStateOf("") }

    var renameDlg by remember { mutableStateOf(false) }
    var renameId by remember { mutableStateOf<Int?>(null) }
    var renameValue by remember { mutableStateOf("") }

    // Long-press drag reorder state: visualLayers mirrors layers during drag
    var visualLayers by remember { mutableStateOf<List<Layer>>(layers) }
    var dragState by remember { mutableStateOf<LayerDragState?>(null) }
    val density = LocalDensity.current
    val itemHeightPx = with(density) { 58.dp.toPx() }

    if (dragState == null) visualLayers = layers.toMutableList()

    Dialog(onDismissRequest = onDismiss) {
            GlassOverlayEffect()
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xE0FFFFFF),
            border = BorderStroke(1.dp, Color(0xFF383838)),
            modifier = Modifier.width(340.dp)
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 18.dp)) {
                // ── 标题栏 ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Layers, null, Modifier.size(18.dp),
                        tint = Color(0xFF64B5F6)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("图层", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2A2A2A))
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(8.dp), color = Color(0x14000000)) {
                        Text(
                            "${layers.size}", fontSize = 11.sp, color = Color(0xFF777777),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("点击设为当前图层 · 长按拖动排序", fontSize = 11.sp, color = Color(0xFF777777))
                Spacer(Modifier.height(12.dp))

                // ── 图层列表 ──
                LazyColumn(Modifier.heightIn(max = 330.dp)) {
                    items(visualLayers.size, key = { visualLayers[it].id }) { idx ->
                        val layer = visualLayers[idx]
                        val isActive = layer.id == activeId
                        val isZero = layer.id == 0
                        val isDragging = dragState?.startId == layer.id

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .animateItem(placementSpec = tween(120))
                                .graphicsLayer {
                                    if (isDragging) {
                                        scaleX = 1.03f
                                        scaleY = 1.03f
                                    }
                                }
                                .clickable { viewModel.setActiveLayer(layer.id) }
                                .then(
                                    if (!isZero) {
                                        Modifier.pointerInput(Unit) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = {
                                                    visualLayers = layers.toMutableList()
                                                    dragState = LayerDragState(idx, layer.id, 0f)
                                                },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    val ds = dragState ?: return@detectDragGesturesAfterLongPress
                                                    ds.offsetY += amount.y
                                                    val zeroIdx = visualLayers.indexOfLast { it.id == 0 }
                                                    val maxTarget = if (zeroIdx >= 0) zeroIdx - 1 else visualLayers.size - 1
                                                    val target = (ds.startIndex + ds.offsetY / itemHeightPx)
                                                        .roundToInt()
                                                        .coerceIn(0, maxTarget)
                                                    val currentIdx = visualLayers.indexOfFirst { it.id == ds.startId }
                                                    if (currentIdx >= 0 && currentIdx != target) {
                                                        val list = visualLayers.toMutableList()
                                                        val item = list.removeAt(currentIdx)
                                                        list.add(target, item)
                                                        visualLayers = list
                                                    }
                                                },
                                                onDragEnd = {
                                                    val ds = dragState ?: return@detectDragGesturesAfterLongPress
                                                    val actualFrom = layers.indexOfFirst { it.id == ds.startId }
                                                    val actualTo = visualLayers.indexOfFirst { it.id == ds.startId }
                                                    if (actualFrom >= 0 && actualTo >= 0 && actualFrom != actualTo) {
                                                        viewModel.moveLayer(actualFrom, actualTo)
                                                    }
                                                    dragState = null
                                                }
                                            )
                                        }
                                    } else Modifier
                                ),
                            shape = RoundedCornerShape(12.dp),
                            color = when {
                                isDragging -> Color(0x1F64B5F6)
                                isActive -> Color(0x1A1565C0)
                                else -> Color(0x0F000000)
                            },
                            border = when {
                                isDragging -> BorderStroke(1.5.dp, Color(0xFF64B5F6))
                                isActive -> BorderStroke(1.dp, Color(0xFF4B9CD3))
                                else -> null
                            },
                            shadowElevation = if (isDragging) 8.dp else 0.dp
                        ) {
                            Row(
                                Modifier.height(52.dp).padding(start = 4.dp, end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 显隐
                                IconButton(
                                    onClick = { viewModel.toggleLayerVisibility(layer.id) },
                                    enabled = !isZero,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        if (layer.isVisible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                                        contentDescription = "显隐",
                                        modifier = Modifier.size(18.dp),
                                        tint = when {
                                            isZero -> Color(0xFF666666)
                                            layer.isVisible -> Color(0xFF64B5F6)
                                            else -> Color(0xFF666666)
                                        }
                                    )
                                }
                                // 名称（隐藏图层降透明度）
                                Row(
                                    Modifier.weight(1f).padding(horizontal = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        layer.name,
                                        fontSize = 14.sp,
                                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (layer.isVisible) Color(0xFF2A2A2A) else Color(0xFF888888),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (isZero) {
                                        Spacer(Modifier.width(6.dp))
                                        Surface(shape = RoundedCornerShape(4.dp), color = Color(0x1A000000)) {
                                            Text(
                                                "底", fontSize = 9.sp, color = Color(0xFF777777),
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                                // 当前图层标记
                                if (isActive) {
                                    Icon(
                                        Icons.Outlined.CheckCircle, "当前图层",
                                        Modifier.size(16.dp), tint = Color(0xFF64B5F6)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                }
                                // 重命名
                                if (!isZero) {
                                    IconButton(
                                        onClick = {
                                            renameId = layer.id
                                            renameValue = layer.name
                                            renameDlg = true
                                        },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            Icons.Outlined.Edit, "重命名",
                                            Modifier.size(16.dp), tint = Color(0xFFB0B0B0)
                                        )
                                    }
                                }
                                // 复制图层 — 所有图层都可以复制（含图层0）
                                IconButton(
                                    onClick = { viewModel.duplicateLayer(layer.id) },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.ContentCopy, "复制",
                                        Modifier.size(16.dp), tint = Color(0xFFB0B0B0)
                                    )
                                }
                                // 删除
                                if (!isZero) {
                                    IconButton(
                                        onClick = { viewModel.deleteLayer(layer.id) },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            Icons.Outlined.Delete, "删除",
                                            Modifier.size(16.dp), tint = Color(0xFFFF6B6B)
                                        )
                                    }
                                    // 拖拽排序手柄（仅视觉提示，长按行任意位置均可拖动）
                                    Icon(
                                        Icons.Outlined.DragHandle, null,
                                        Modifier.size(16.dp), tint = Color(0xFF666666)
                                    )
                                } else {
                                    Spacer(Modifier.width(16.dp))
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── 新建图层 ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    var tv by remember { mutableStateOf(TextFieldValue("")) }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0x0F000000),
                        border = BorderStroke(1.dp, Color(0xFF404042)),
                        modifier = Modifier.weight(1f).height(44.dp)
                    ) {
                        Box(
                            Modifier.fillMaxSize().padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            BasicTextField(
                                value = tv,
                                onValueChange = { tv = it; newLayerName = it.text },
                                singleLine = true,
                                textStyle = TextStyle(color = Color(0xFF2A2A2A), fontSize = 13.sp),
                                cursorBrush = SolidColor(Color(0xFF64B5F6)),
                                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused && tv.text.isNotEmpty()) tv = tv.copy(selection = TextRange(0, tv.text.length)) },
                                decorationBox = { inner ->
                                    if (tv.text.isEmpty()) {
                                        Text("新图层名称", fontSize = 13.sp, color = Color(0xFF777777))
                                    }
                                    inner()
                                }
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val name = newLayerName.trim().ifBlank { "图层${layers.size}" }
                            viewModel.addLayer(name)
                            newLayerName = ""
                            tv = TextFieldValue("")
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1565C0),
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        modifier = Modifier.height(44.dp)
                    ) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("新建", fontSize = 13.sp)
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ── 底部操作 ──
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.mergeLayerDown(activeId) },
                        enabled = activeId != 0,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            1.dp,
                            if (activeId != 0) Color(0xFF4B9CD3) else Color(0xFF444444)
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (activeId != 0) Color(0xFF64B5F6) else Color(0xFF666666)
                        ),
                        modifier = Modifier.weight(1f).height(42.dp)
                    ) { Text("合并到下层", fontSize = 13.sp) }
                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1565C0),
                            contentColor = Color.White
                        ),
                        modifier = Modifier.weight(1f).height(42.dp)
                    ) { Text("关闭", fontSize = 13.sp) }
                }
            }
        }
    }

    if (renameDlg && renameId != null) {
        val focusReq = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(100); focusReq.requestFocus() }
        AlertDialog(
            onDismissRequest = { renameDlg = false; renameValue = ""; renameId = null },
            containerColor = Color(0xE0FFFFFF),
            shape = RoundedCornerShape(20.dp),
            title = {
                Text("重命名图层", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2A2A2A))
            },
            text = {
                var tv by remember { mutableStateOf(TextFieldValue(renameValue, selection = TextRange(0, renameValue.length))) }
                OutlinedTextField(
                    tv,
                    { tv = it; renameValue = it.text },
                    label = { Text("名称") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF64B5F6), unfocusedBorderColor = Color(0xFF555555),
                        cursorColor = Color(0xFF64B5F6), focusedTextColor = Color(0xFF2A2A2A),
                        unfocusedTextColor = Color(0xFF2A2A2A),
                        focusedLabelColor = Color(0xFF64B5F6), unfocusedLabelColor = Color(0xFF888888)
                    ),
                    modifier = Modifier
                        .focusRequester(focusReq)
                        .onFocusChanged { if (it.isFocused) tv = tv.copy(selection = TextRange(0, tv.text.length)) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renameId?.let { viewModel.renameLayer(it, renameValue.trim().ifBlank { "图层" }) }
                    renameDlg = false; renameValue = ""; renameId = null
                }) { Text("确定", color = Color(0xFF64B5F6), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { renameDlg = false; renameValue = ""; renameId = null }) {
                    Text("取消", color = Color(0xFF777777))
                }
            }
        )
    }
}

// ═══════════════════════════════════════════════════════════
//  ColorPickerDialog
// ═══════════════════════════════════════════════════════════

private val PRESET_COLORS = listOf(
    Color.Black, Color(0x14000000), Color(0xFF666666),
    Color.Red, Color(0xFFD32F2F), Color(0xFFC62828),
    Color(0xFFFF9800), Color(0xFFF57C00), Color(0xFFE65100),
    Color(0xFFFFEB3B), Color(0xFFFDD835), Color(0xFFF9A825),
    Color(0xFF4CAF50), Color(0xFF388E3C), Color(0xFF1B5E20),
    Color(0xFF2196F3), Color(0xFF1976D2), Color(0xFF0D47A1),
    Color(0xFF9C27B0), Color(0xFF7B1FA2), Color(0xFF4A0072)
)

@Composable
fun ColorPickerDialog(
    currentColor: Color,
    onColorSelected: (Color) -> Unit,
    onDismiss: () -> Unit
) {
            GlassOverlayEffect()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择颜色", fontSize = 16.sp) },
        text = {
            Column {
                // 预设颜色
                val chunks = PRESET_COLORS.chunked(7)
                chunks.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        row.forEach { color ->
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        if (color == currentColor) 3.dp else 1.dp,
                                        if (color == currentColor) Color(0xFF1976D2) else Color(0xFF666666),
                                        CircleShape
                                    )
                                    .clickable { onColorSelected(color); onDismiss() }
                            )
                        }
                        // 补齐空位
                        repeat(7 - row.size) { Spacer(Modifier.size(36.dp)) }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

// ═══════════════════════════════════════════════════════════
//  StrokeWidthDialog
// ═══════════════════════════════════════════════════════════

@Composable
fun StrokeWidthDialog(
    currentWidth: Float,
    onWidthChanged: (Float) -> Unit,
    onDismiss: () -> Unit
) {
            GlassOverlayEffect()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("线宽", fontSize = 16.sp) },
        text = {
            Column {
                Text("${currentWidth.toInt()} px", fontSize = 14.sp, color = Color(0xFF666666))
                Spacer(Modifier.height(8.dp))
                Slider(
                    value = currentWidth,
                    onValueChange = onWidthChanged,
                    valueRange = 1f..20f
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("确定") } }
    )
}

@Composable
fun CompactSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    thumbSize: Dp = 8.dp,
    trackHeight: Dp = 4.dp,
    trackColor: Color = Color(0xFF4A4A4A),
    activeColor: Color = Color(0xFF64B5F6),
    enabled: Boolean = true
) {
    val density = LocalDensity.current
    val thumbSizePx = with(density) { thumbSize.toPx() }
    val thumbRadiusPx = thumbSizePx / 2f
    val trackHeightPx = with(density) { trackHeight.toPx() }
    val ringPx = with(density) { 1.5.dp.toPx() }
    val shadowOffsetPx = with(density) { 1.dp.toPx() }

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        if (widthPx <= 0f) return@BoxWithConstraints

        val fraction = remember(value, valueRange) {
            if (valueRange.endInclusive == valueRange.start) 0f
            else ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
        }
        // 拇指中心：fraction=0 时左边缘贴轨道起点，fraction=1 时右边缘贴轨道终点
        val thumbCenterX = thumbRadiusPx + fraction * (widthPx - thumbSizePx)

        // 全部用 Canvas 一次绘制：轨道 + 激活段 + 拇指（阴影/白底/蓝环）
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerY = size.height / 2f
            drawLine(
                color = if (enabled) trackColor else Color(0xFF2A2A2A),
                start = Offset(thumbRadiusPx, centerY),
                end = Offset(size.width - thumbRadiusPx, centerY),
                strokeWidth = trackHeightPx,
                cap = StrokeCap.Round
            )
            drawLine(
                color = if (enabled) activeColor else Color(0xFF3A3A3A),
                start = Offset(thumbRadiusPx, centerY),
                end = Offset(thumbCenterX, centerY),
                strokeWidth = trackHeightPx,
                cap = StrokeCap.Round
            )
            drawCircle(Color(0x66000000), thumbRadiusPx, Offset(thumbCenterX, centerY + shadowOffsetPx))
            drawCircle(if (enabled) Color.White else Color(0xFF666666), thumbRadiusPx, Offset(thumbCenterX, centerY))
            drawCircle(
                if (enabled) activeColor else Color(0xFF4A4A4A), thumbRadiusPx - ringPx / 2f, Offset(thumbCenterX, centerY),
                style = Stroke(width = ringPx)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(valueRange, enabled) {
                    if (!enabled) return@pointerInput
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown()
                            updateSliderValue(
                                down.position.x, widthPx, thumbSizePx, valueRange, onValueChange
                            )

                            var currentX = down.position.x
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || change.changedToUp()) break
                                if (change.positionChanged()) {
                                    change.consume()
                                    currentX = change.position.x
                                    updateSliderValue(
                                        currentX, widthPx, thumbSizePx, valueRange, onValueChange
                                    )
                                }
                            }
                        }
                    }
                }
        )
    }
}

private fun updateSliderValue(
    x: Float,
    widthPx: Float,
    thumbSizePx: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    if (widthPx <= thumbSizePx) return
    val fraction = ((x - thumbSizePx / 2f) / (widthPx - thumbSizePx)).coerceIn(0f, 1f)
    val newValue = valueRange.start + fraction * (valueRange.endInclusive - valueRange.start)
    onValueChange(newValue)
}

// ═══════════════════════════════════════════════════════
//  Array dialog — selection → grid pattern
// ═══════════════════════════════════════════════════════

@Composable
fun ArrayDialog(
    rows: Int, cols: Int, gapX: Float, gapY: Float,
    onRowsChange: (Int) -> Unit,
    onColsChange: (Int) -> Unit,
    onGapXChange: (Float) -> Unit,
    onGapYChange: (Float) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
            GlassOverlayEffect()
    Dialog(onDismissRequest = onDismiss) {
            GlassOverlayEffect()
        Surface(shape = RoundedCornerShape(16.dp), color = Color(0xE0FFFFFF), modifier = Modifier.width(300.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("阵列", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2A2A2A))
                Spacer(Modifier.height(12.dp))
                // Row count stepper
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("行数", fontSize = 13.sp, color = Color(0xFF666666), modifier = Modifier.width(60.dp))
                    IconButton(onClick = { onRowsChange(maxOf(1, rows - 1)) }, modifier = Modifier.size(28.dp)) {
                        Text("−", fontSize = 16.sp, color = Color(0xFF2A2A2A))
                    }
                    Text("$rows", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2A2A2A), modifier = Modifier.width(30.dp), textAlign = TextAlign.Center)
                    IconButton(onClick = { onRowsChange(rows + 1) }, modifier = Modifier.size(28.dp)) {
                        Text("+", fontSize = 16.sp, color = Color(0xFF2A2A2A))
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Column count stepper
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("列数", fontSize = 13.sp, color = Color(0xFF666666), modifier = Modifier.width(60.dp))
                    IconButton(onClick = { onColsChange(maxOf(1, cols - 1)) }, modifier = Modifier.size(28.dp)) {
                        Text("−", fontSize = 16.sp, color = Color(0xFF2A2A2A))
                    }
                    Text("$cols", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2A2A2A), modifier = Modifier.width(30.dp), textAlign = TextAlign.Center)
                    IconButton(onClick = { onColsChange(cols + 1) }, modifier = Modifier.size(28.dp)) {
                        Text("+", fontSize = 16.sp, color = Color(0xFF2A2A2A))
                    }
                }
                Spacer(Modifier.height(12.dp))
                // Column gap slider
                Text("列间距", fontSize = 12.sp, color = Color(0xFF666666))
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CompactSlider(value = gapX, onValueChange = onGapXChange, valueRange = 0f..400f,
                        modifier = Modifier.weight(1f).height(24.dp), thumbSize = 10.dp, trackHeight = 8.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("${gapX.toInt()}", fontSize = 12.sp, color = Color(0xFF2A2A2A), modifier = Modifier.width(32.dp), textAlign = TextAlign.End)
                }
                Spacer(Modifier.height(8.dp))
                // Row gap slider
                Text("行间距", fontSize = 12.sp, color = Color(0xFF666666))
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CompactSlider(value = gapY, onValueChange = onGapYChange, valueRange = 0f..400f,
                        modifier = Modifier.weight(1f).height(24.dp), thumbSize = 10.dp, trackHeight = 8.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("${gapY.toInt()}", fontSize = 12.sp, color = Color(0xFF2A2A2A), modifier = Modifier.width(32.dp), textAlign = TextAlign.End)
                }
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消", color = Color(0xFF888888)) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onConfirm) { Text("确定", color = Color(0xFF64B5F6), fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}