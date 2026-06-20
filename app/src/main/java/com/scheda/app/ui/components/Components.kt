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
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.scheda.app.file.RecoveryManager
import com.scheda.app.file.SchedaSerializer
import com.scheda.app.file.ShareUtil
import com.scheda.app.file.StorageManager
import com.scheda.app.model.DrawingPrimitive
import com.scheda.app.model.Layer
import com.scheda.app.model.LineStyle
import com.scheda.app.model.LineType
import com.scheda.app.model.ToolType
import com.scheda.app.viewmodel.DrawingViewModel
import com.scheda.app.viewmodel.HomeViewModel

// ═══════════════════════════════════════════════════════════
//  ViewModel Factories
// ═══════════════════════════════════════════════════════════

class HomeViewModelFactory(
    private val storageManager: StorageManager,
    private val shareUtil: ShareUtil,
    private val serializer: SchedaSerializer
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        HomeViewModel(storageManager, shareUtil, serializer) as T
}

class DrawingViewModelFactory(
    private val storageManager: StorageManager,
    private val serializer: SchedaSerializer,
    private val recoveryManager: RecoveryManager
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        DrawingViewModel(storageManager, serializer, recoveryManager, storageManager.context) as T
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
    onShowRangeDialog: () -> Unit = {}
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 4.dp,
        color = Color(0xFF2A2A2A)
    ) {
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
                    DarkPill(onClick = { ltExpanded = true }, modifier = Modifier.widthIn(min = 40.dp)) {
                        Text(ctLabel, fontSize = 11.sp, maxLines = 1, color = Color.White, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(2.dp))
                        Text("▲", fontSize = 6.sp, color = Color(0xFFAAAAAA))
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
                                HorizontalDivider(thickness = 0.5.dp, color = Color(0xFF3A3A3A))
                            }
                        }
                    }
                }
                Spacer(Modifier.width(4.dp))
                // 线型预览 — 与实际绘制效果一致
                val prevColor = Color(0xFFCCCCCC)
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
                Text("线宽", fontSize = 10.sp, color = Color(0xFFCCCCCC))
                Spacer(Modifier.width(2.dp))
                CompactSlider(
                    value = viewModel.strokeWidth,
                    onValueChange = { viewModel.setStrokeWidth(it) },
                    valueRange = 1f..20f,
                    modifier = Modifier.width(76.dp).height(20.dp),
                    thumbSize = 8.dp
                )
                Text("${viewModel.strokeWidth.toInt()}", fontSize = 10.sp, color = Color(0xFFCCCCCC), modifier = Modifier.width(14.dp))
                Spacer(Modifier.width(4.dp))
                // 全局线型比例
                Text("比例", fontSize = 10.sp, color = Color(0xFFCCCCCC))
                Spacer(Modifier.width(2.dp))
                CompactSlider(
                    value = viewModel.globalLineScale,
                    onValueChange = { viewModel.setGlobalLineScale(it) },
                    valueRange = 0.25f..4f,
                    modifier = Modifier.width(76.dp).height(20.dp),
                    thumbSize = 8.dp
                )
                Text("${"%.1f".format(viewModel.globalLineScale)}", fontSize = 10.sp, color = Color(0xFFCCCCCC), modifier = Modifier.width(18.dp))
                Spacer(Modifier.width(4.dp))
                // 颜色圆 — 点击弹出颜色选择
                var colorDlg by remember { mutableStateOf(false) }
                Box(Modifier.size(24.dp).clip(CircleShape).background(viewModel.currentColor)
                    .border(1.5f.dp, Color(0xFFCCCCCC), CircleShape)
                    .clickable { colorDlg = true })
                if (colorDlg) ColorPickerDialog(viewModel.currentColor, { viewModel.setColor(it) }, { colorDlg = false })
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
                    val isTextNum = viewModel.currentTool == ToolType.ANNOTATE || viewModel.currentTool == ToolType.TEXT || viewModel.currentTool == ToolType.RANGE
                    val label = when (viewModel.currentTool) {
                        ToolType.ANNOTATE -> "数字"
                        ToolType.TEXT -> "文字"
                        ToolType.RANGE -> "区间"
                        else -> "文字"
                    }
                    DarkPill(onClick = { textNumExpanded = true },
                        modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label, fontSize = 11.sp, maxLines = 1,
                                color = if (isTextNum) Color.White else Color(0xFFAAAAAA))
                            Spacer(Modifier.width(2.dp))
                            Text("▲", fontSize = 6.sp, color = Color(0xFF999999))
                        }
                    }
                    DarkDropdownMenu(
                        expanded = textNumExpanded,
                        onDismissRequest = { textNumExpanded = false }
                    ) {
                        listOf(
                            Triple("文字", ToolType.TEXT, onShowTextDialog),
                            Triple("数字", ToolType.ANNOTATE, onShowNumberDialog),
                            Triple("区间数字", ToolType.RANGE, onShowRangeDialog)
                        ).forEachIndexed { index, (itemLabel, tool, action) ->
                            DarkMenuItem(
                                label = itemLabel,
                                selected = viewModel.currentTool == tool,
                                onClick = { textNumExpanded = false; action() }
                            )
                            if (index < 2) {
                                HorizontalDivider(thickness = 0.5.dp, color = Color(0xFF3A3A3A))
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
                    ToolBtn(viewModel, tool, label, onShowNumberDialog, onShowTextDialog)
                }
            }
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
    content: @Composable () -> Unit
) {
    Surface(shape = RoundedCornerShape(16.dp), color = Color(0xDD333333), shadowElevation = 2.dp,
        modifier = modifier.height(30.dp)) {
        TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            modifier = Modifier.height(30.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
            content()
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

/** Dark dropdown menu: uses SchedaDropdownMenu (no forced 8dp vertical padding). */
@Composable
private fun DarkDropdownMenu(
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
        Text(label, fontSize = 14.sp, color = Color.White)
    }
}

@Composable
private fun RowScope.ToolBtn(
    viewModel: DrawingViewModel,
    tool: ToolType,
    label: String,
    onShowNumberDialog: () -> Unit,
    onShowTextDialog: () -> Unit = {}
) {
    val selected = viewModel.currentTool == tool
    DarkPill(onClick = {
        when (tool) {
            ToolType.ANNOTATE -> onShowNumberDialog()
            ToolType.TEXT -> onShowTextDialog()
            else -> viewModel.setTool(tool)
        }
    }, modifier = Modifier.weight(1f)) {
        Text(label, fontSize = 11.sp,
            color = if (selected) Color.White else Color(0xFFAAAAAA),
            maxLines = 1)
    }
}

// ═════════════════════════════════════════════════════════════════
//  LayerDialog
// ═════════════════════════════════════════════════════════════════

private data class LayerDragState(
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
    val itemHeightPx = with(density) { 52.dp.toPx() }

    if (dragState == null) visualLayers = layers.toMutableList()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xCC222222),
            modifier = Modifier.width(320.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "图层",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(Modifier.height(12.dp))

                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(visualLayers.size, key = { visualLayers[it].id }) { idx ->
                        val layer = visualLayers[idx]
                        val isActive = layer.id == activeId
                        val isZero = layer.id == 0
                        val isDragging = dragState?.startId == layer.id

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
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
                            color = if (isActive) Color(0xFF1565C0) else Color(0xDD333333),
                            border = if (isDragging) BorderStroke(2.dp, Color(0xFF64B5F6)) else null,
                            shadowElevation = if (isDragging) 8.dp else 0.dp
                        ) {
                            Row(
                                Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { viewModel.toggleLayerVisibility(layer.id) },
                                    enabled = !isZero,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        if (layer.isVisible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                                        contentDescription = "显隐",
                                        modifier = Modifier.size(18.dp),
                                        tint = if (isZero) Color(0xFF888888) else Color.White
                                    )
                                }
                                Text(
                                    layer.name + if (isZero) " (底)" else "",
                                    fontSize = 13.sp,
                                    color = Color.White,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 4.dp),
                                    maxLines = 1
                                )
                                if (isActive) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(Color.White, CircleShape)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                }
                                if (!isZero) {
                                    IconButton(
                                        onClick = {
                                            renameId = layer.id
                                            renameValue = layer.name
                                            renameDlg = true
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Outlined.Edit,
                                            contentDescription = "重命名",
                                            modifier = Modifier.size(18.dp),
                                            tint = Color.White
                                        )
                                    }
                                    IconButton(
                                        onClick = { viewModel.duplicateLayer(layer.id) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Outlined.ContentCopy,
                                            contentDescription = "复制",
                                            modifier = Modifier.size(18.dp),
                                            tint = Color.White
                                        )
                                    }
                                    IconButton(
                                        onClick = { viewModel.deleteLayer(layer.id) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = "删除",
                                            modifier = Modifier.size(18.dp),
                                            tint = Color(0xFFFF5252)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // New layer
                Row(verticalAlignment = Alignment.CenterVertically) {
                    var tv by remember { mutableStateOf(TextFieldValue(newLayerName)) }
                    OutlinedTextField(
                        value = tv,
                        onValueChange = { tv = it; newLayerName = it.text },
                        label = { Text("新图层名", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .onFocusChanged {
                                if (it.isFocused) tv = tv.copy(selection = TextRange(0, tv.text.length))
                            },
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color(0xFF64B5F6),
                            unfocusedIndicatorColor = Color(0xFF888888),
                            focusedLabelColor = Color(0xFFAAAAAA),
                            unfocusedLabelColor = Color(0xFFAAAAAA),
                            cursorColor = Color(0xFF64B5F6)
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val name = newLayerName.trim().ifBlank { "图层${layers.size}" }
                            viewModel.addLayer(name)
                            newLayerName = ""
                            tv = TextFieldValue("")
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1565C0),
                            contentColor = Color.White
                        ),
                        modifier = Modifier.height(40.dp)
                    ) { Text("新建", fontSize = 12.sp) }
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.mergeLayerDown(activeId) },
                        enabled = activeId != 0,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1565C0),
                            contentColor = Color.White,
                            disabledContainerColor = Color(0xFF555555),
                            disabledContentColor = Color(0xFF888888)
                        )
                    ) { Text("合并到下层", fontSize = 12.sp) }
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF555555),
                            contentColor = Color.White
                        )
                    ) { Text("关闭", fontSize = 12.sp) }
                }
            }
        }
    }

    if (renameDlg && renameId != null) {
        val focusReq = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(100); focusReq.requestFocus() }
        AlertDialog(
            onDismissRequest = { renameDlg = false; renameValue = ""; renameId = null },
            title = { Text("重命名图层", fontSize = 16.sp) },
            text = {
                var tv by remember { mutableStateOf(TextFieldValue(renameValue)) }
                OutlinedTextField(
                    tv,
                    { tv = it; renameValue = it.text },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier
                        .focusRequester(focusReq)
                        .onFocusChanged { if (it.isFocused) tv = tv.copy(selection = TextRange(0, tv.text.length)) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renameId?.let { viewModel.renameLayer(it, renameValue.trim().ifBlank { "图层" }) }
                    renameDlg = false; renameValue = ""; renameId = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renameDlg = false; renameValue = ""; renameId = null }) { Text("取消") }
            }
        )
    }
}

// ═══════════════════════════════════════════════════════════
//  ColorPickerDialog
// ═══════════════════════════════════════════════════════════

private val PRESET_COLORS = listOf(
    Color.Black, Color(0xFF333333), Color(0xFF666666),
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
                                        if (color == currentColor) Color(0xFF1976D2) else Color(0xFFCCCCCC),
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
    trackHeight: Dp = 4.dp
) {
    val density = LocalDensity.current
    val thumbSizePx = with(density) { thumbSize.toPx() }
    val thumbRadiusPx = thumbSizePx / 2f
    val trackHeightPx = with(density) { trackHeight.toPx() }

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
        val thumbCenterX = thumbRadiusPx + fraction * (widthPx - thumbSizePx)
        val thumbOffsetX = (thumbCenterX - widthPx / 2f).roundToInt()
        val thumbOffsetY = 0

        Canvas(modifier = Modifier.fillMaxWidth().height(trackHeight)) {
            val centerY = size.height / 2f
            drawLine(
                color = Color(0xFF3A3A3A),
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = trackHeightPx,
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color(0xFF64B5F6),
                start = Offset(0f, centerY),
                end = Offset(thumbCenterX, centerY),
                strokeWidth = trackHeightPx,
                cap = StrokeCap.Round
            )
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(thumbOffsetX, thumbOffsetY) }
                .size(thumbSize)
                .clip(CircleShape)
                .background(Color.White)
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(valueRange) {
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