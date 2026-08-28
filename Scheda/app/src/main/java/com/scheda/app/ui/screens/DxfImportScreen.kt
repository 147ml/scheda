package com.scheda.app.ui.screens

import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scheda.app.model.Bounds
import com.scheda.app.model.DrawingPrimitive
import com.scheda.app.model.Layer
import com.scheda.app.model.LineStyle
import com.scheda.app.model.PendingEdit
import com.scheda.app.model.Point2D
import com.scheda.app.model.ToolType
import com.scheda.app.ui.canvas.DrawingCanvas

/** DXF 导入目标：主画布 / 图块 */
enum class DxfImportMode { MAIN, BLOCK }

/**
 * DXF 导入画布：只做浏览（双指缩放平移）和框选。
 * 选择逻辑与主画布一致：左→右全包含，右→左碰到即选，多次拖拽累加。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DxfImportScreen(
    primitives: List<DrawingPrimitive>,
    mode: DxfImportMode,
    onConfirm: (List<DrawingPrimitive>) -> Unit,
    onCancel: () -> Unit
) {
    val measurePaint = remember {
        Paint().apply { typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true; textAlign = Paint.Align.CENTER }
    }

    var canvasScale by remember { mutableFloatStateOf(1f) }
    var canvasOffsetX by remember { mutableFloatStateOf(0f) }
    var canvasOffsetY by remember { mutableFloatStateOf(0f) }
    var fitted by remember { mutableStateOf(false) }
    var canvasSz by remember { mutableStateOf(IntSize.Zero) }

    // 选择状态
    var selActive by remember { mutableStateOf(false) }
    // 单指按下先待命，拖动才进入框选：双指缩放不会被误判为框选
    var selArmed by remember { mutableStateOf(false) }
    var selStart by remember { mutableStateOf(Point2D(0f, 0f)) }
    var selEnd by remember { mutableStateOf(Point2D(0f, 0f)) }
    var selectedIndices by remember { mutableStateOf(setOf<Int>()) }
    var excludedTypes by remember { mutableStateOf(setOf<String>()) }
    var showFilter by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }

    // 左右滑返回手势：不直接退出软件，先弹确认框询问是否取消导入
    BackHandler { showExitConfirm = true }

    fun fitToContent(w: Int, h: Int) {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var has = false
        for (p in primitives) {
            val b = p.computeBounds(measurePaint) ?: continue
            has = true
            minX = minOf(minX, b[0]); minY = minOf(minY, b[1])
            maxX = maxOf(maxX, b[2]); maxY = maxOf(maxY, b[3])
        }
        if (!has) return
        val cw = maxOf(maxX - minX, 1f); val ch = maxOf(maxY - minY, 1f)
        val sp = 60f
        val ns = minOf((w - 2 * sp) / cw, (h - 2 * sp) / ch).coerceIn(0.01f, 50f)
        canvasScale = ns
        canvasOffsetX = w / 2f - (minX + maxX) / 2f * ns
        canvasOffsetY = h / 2f - (minY + maxY) / 2f * ns
    }

    fun endSelection() {
        if (!selActive) return
        selActive = false
        val lr = selStart.x <= selEnd.x
        val selB = Bounds(
            minOf(selStart.x, selEnd.x), minOf(selStart.y, selEnd.y),
            maxOf(selStart.x, selEnd.x), maxOf(selStart.y, selEnd.y)
        )
        val hit = primitives.indices.filter { i ->
            val p = primitives[i]
            if (p.typeName in excludedTypes) return@filter false
            val pb = p.computeBounds(measurePaint) ?: return@filter false
            if (lr) {
                // 左→右：完全包含
                pb[0] >= selB.minX && pb[1] >= selB.minY &&
                    pb[2] <= selB.maxX && pb[3] <= selB.maxY
            } else {
                // 右→左：相交且实际碰到几何
                pb[0] <= selB.maxX && pb[2] >= selB.minX &&
                    pb[1] <= selB.maxY && pb[3] >= selB.minY &&
                    p.fenceHitsGeometry(selB)
            }
        }.toSet()
        selectedIndices = selectedIndices + hit
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (mode == DxfImportMode.MAIN) "导入到主画布" else "导入到图块",
                            fontSize = 16.sp, fontWeight = FontWeight(500)
                        )
                        Spacer(Modifier.width(8.dp))
                        Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF1565C0)) {
                            Text("DXF", fontSize = 10.sp, color = Color.White,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Outlined.ArrowBack, "返回", Modifier.size(20.dp))
                    }
                },
                actions = {
                    Text("共${primitives.size}个 已选${selectedIndices.size}个",
                        fontSize = 12.sp, color = Color(0xFFCCCCCC),
                        modifier = Modifier.padding(end = 12.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF2A2A2A),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        bottomBar = {
            Surface(color = Color(0xFF2A2A2A)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 筛选元素
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Transparent,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF666666)),
                        modifier = Modifier.height(30.dp).clickable { showFilter = true }
                    ) {
                        Box(Modifier.fillMaxHeight().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                            Text(
                                if (excludedTypes.isEmpty()) "筛选" else "筛选(${excludedTypes.size}项排除)",
                                fontSize = 12.sp, color = Color(0xFFAAAAAA)
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onCancel) { Text("取消", color = Color(0xFFAAAAAA), fontSize = 14.sp) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(selectedIndices.sorted().map { primitives[it] }) },
                        enabled = selectedIndices.isNotEmpty(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1565C0), contentColor = Color.White,
                            disabledContainerColor = Color(0xFF3A3A3A), disabledContentColor = Color(0xFF777777)
                        )
                    ) { Text("确认导入(${selectedIndices.size})", fontSize = 14.sp) }
                }
            }
        }
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).onSizeChanged {
                canvasSz = it
                if (!fitted && it.width > 0 && it.height > 0) {
                    fitted = true
                    fitToContent(it.width, it.height)
                }
            }
        ) {
            DrawingCanvas(
                primitives = primitives,
                currentPrimitive = null,
                layers = listOf(Layer(1, "导入")),
                canvasScale = canvasScale,
                canvasOffsetX = canvasOffsetX,
                canvasOffsetY = canvasOffsetY,
                pendingEdit = PendingEdit(),
                currentTool = ToolType.SELECT,
                currentLineStyle = LineStyle(),
                onCanvasTransform = { z, _, p ->
                    val old = canvasScale
                    val ns = (old * z).coerceIn(0.01f, 50f)
                    if (z != 1f) {
                        val ratio = ns / old
                        canvasOffsetX = canvasSz.width / 2f - (canvasSz.width / 2f - canvasOffsetX) * ratio
                        canvasOffsetY = canvasSz.height / 2f - (canvasSz.height / 2f - canvasOffsetY) * ratio
                    }
                    canvasOffsetX += p.x
                    canvasOffsetY += p.y
                    canvasScale = ns
                },
                onTouchStart = { pt ->
                    selArmed = true
                    selStart = pt
                    selEnd = pt
                },
                onTouchMove = { pt ->
                    if (selArmed && !selActive) selActive = true
                    if (selActive) selEnd = pt
                },
                onTouchEnd = { endSelection(); selArmed = false },
                onTouchCancel = { selActive = false; selArmed = false },
                modifier = Modifier.fillMaxSize()
            )

            // 选择高亮 + 框选矩形
            Canvas(Modifier.fillMaxSize()) {
                fun ws(p: Point2D) = Offset(p.x * canvasScale + canvasOffsetX, p.y * canvasScale + canvasOffsetY)
                // 选中元素：蓝色包络高亮
                val hiColor = Color(0xFF4B9CD3)
                for (i in selectedIndices) {
                    val b = primitives.getOrNull(i)?.computeBounds(measurePaint) ?: continue
                    val tl = ws(Point2D(b[0], b[1])); val br = ws(Point2D(b[2], b[3]))
                    val pad = 3f
                    drawRect(
                        hiColor.copy(alpha = 0.15f),
                        Offset(tl.x - pad, tl.y - pad),
                        Size(br.x - tl.x + 2 * pad, br.y - tl.y + 2 * pad)
                    )
                    drawRect(
                        hiColor, Offset(tl.x - pad, tl.y - pad),
                        Size(br.x - tl.x + 2 * pad, br.y - tl.y + 2 * pad),
                        style = Stroke(width = 1.5f)
                    )
                }
                // 拖拽中的框选矩形（与主画布选择框一致）
                if (selActive) {
                    val s = ws(selStart); val e = ws(selEnd)
                    val left = minOf(s.x, e.x); val top = minOf(s.y, e.y)
                    val w = kotlin.math.abs(s.x - e.x); val h = kotlin.math.abs(s.y - e.y)
                    drawRect(Color(0x334B9CD3), Offset(left, top), Size(w, h))
                    drawRect(
                        Color(0xFF4B9CD3), Offset(left, top), Size(w, h),
                        style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)))
                    )
                }
            }
        }
    }

    // 筛选弹窗（样式与主画布筛选一致）
    if (showFilter) {
        val typeCounts = remember { primitives.groupBy { it.typeName }.mapValues { it.value.size } }
        var unchecked by remember { mutableStateOf(excludedTypes) }
        AlertDialog(
            onDismissRequest = { showFilter = false },
            title = {
                Text("筛选元素类型", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            },
            containerColor = Color(0xFF242424),
            shape = RoundedCornerShape(16.dp),
            text = {
                Column(Modifier.widthIn(min = 220.dp)) {
                    typeCounts.entries.forEachIndexed { i, (typeName, count) ->
                        val checked = typeName !in unchecked
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (checked) Color(0x334B9CD3) else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable {
                                    unchecked = if (checked) unchecked + typeName else unchecked - typeName
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Checkbox(
                                checked = checked, onCheckedChange = null, modifier = Modifier.size(20.dp),
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Color(0xFF4B9CD3),
                                    uncheckedColor = Color(0xFF777777),
                                    checkmarkColor = Color.White
                                )
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(typeName, fontSize = 14.sp, color = Color.White, modifier = Modifier.weight(1f))
                            Text("${count}个", fontSize = 12.sp, color = Color(0xFF999999))
                        }
                        if (i < typeCounts.size - 1) Spacer(Modifier.height(2.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    excludedTypes = unchecked
                    // 把被排除类型从已选中移除
                    if (unchecked.isNotEmpty()) {
                        selectedIndices = selectedIndices.filter { primitives[it].typeName !in unchecked }.toSet()
                    }
                    showFilter = false
                }) { Text("应用", color = Color(0xFF64B5F6), fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showFilter = false }) { Text("取消", color = Color(0xFFAAAAAA)) } }
        )
    }

    // 返回手势确认框：取消在左，确认在右；确认后返回上一级
    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("取消导入？", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White) },
            text = { Text("确定取消当前导入操作？", fontSize = 14.sp, color = Color(0xFFCCCCCC)) },
            containerColor = Color(0xFF242424),
            shape = RoundedCornerShape(16.dp),
            confirmButton = {
                TextButton(onClick = { showExitConfirm = false; onCancel() }) {
                    Text("确认", color = Color(0xFFC62828), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { showExitConfirm = false }) { Text("取消", color = Color(0xFFAAAAAA)) } }
        )
    }
}
