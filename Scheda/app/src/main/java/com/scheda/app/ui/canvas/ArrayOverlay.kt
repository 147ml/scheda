package com.scheda.app.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.abs
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.scheda.app.model.Bounds
import com.scheda.app.model.DrawingPrimitive
import com.scheda.app.model.LineStyle
import com.scheda.app.model.LineType
import com.scheda.app.model.Point2D
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 在 Initial 通道同步通知手柄按下/抬起。
 * Initial 通道早于同一事件的 Main 通道，因此 DrawingCanvas 处理该事件时已能看到标志，
 * 避免手柄拖动被画布当作"移动原始元素"抢走。
 */
private fun Modifier.handleInterlock(onHandleActiveChanged: (Boolean) -> Unit): Modifier =
    this.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            onHandleActiveChanged(true)
            try {
                waitForUpOrCancellation(PointerEventPass.Initial)
            } finally {
                // 协程取消（如阵列中途确认/取消）也必须复位，否则画布单指操作会被永久屏蔽
                onHandleActiveChanged(false)
            }
        }
    }

/**
 * Array preview overlay with ghost elements and draggable handles for spacing and count.
 *
 * Handles are positioned:
 * - Column spacing: midpoint between original and first column ghost, along X
 * - Row spacing: midpoint between original and first row ghost, along Y
 * - Column count: right end of first row (last column position)
 * - Row count: bottom end of first column (last row position)
 */
@Composable
fun ArrayOverlay(
    rows: Int,
    cols: Int,
    gapX: Float,
    gapY: Float,
    bounds: Bounds,
    canvasScale: Float,
    canvasOffsetX: Float,
    canvasOffsetY: Float,
    dirX: Int = 1,
    dirY: Int = 1,
    selOffsetX: Float = 0f,
    selOffsetY: Float = 0f,
    onGapXChange: (Float) -> Unit,
    onGapYChange: (Float) -> Unit,
    onColsChange: (Int) -> Unit,
    onRowsChange: (Int) -> Unit,
    onDirXChange: (Int) -> Unit = {},
    onDirYChange: (Int) -> Unit = {},
    onHandleActiveChanged: (Boolean) -> Unit = {},
    primitives: List<DrawingPrimitive> = emptyList(),
    globalLineScale: Float = 1f,
    modifier: Modifier = Modifier
) {
    if (rows <= 0 || cols <= 0) return
    val density = LocalDensity.current
    val handleSize = 36.dp
    val handleRadiusPx = with(density) { handleSize.toPx() / 2f }

    val baseW = bounds.maxX - bounds.minX
    val baseH = bounds.maxY - bounds.minY
    val cx = (bounds.minX + bounds.maxX) / 2f
    val cy = (bounds.minY + bounds.maxY) / 2f

    fun worldToScreen(wx: Float, wy: Float): Offset =
        Offset(wx * canvasScale + canvasOffsetX, wy * canvasScale + canvasOffsetY)

    // Handle positions in world coords (with direction + selection offset)
    // Column spacing handle: midpoint between original edge and first column ghost
    // （反向阵列时 ghost 在 minX 一侧，手柄跟随到对应边缘）
    val colSpacingX = (if (dirX > 0) bounds.maxX else bounds.minX) + gapX / 2f * dirX + selOffsetX
    val colSpacingY = cy + selOffsetY
    // Row spacing handle: midpoint between original edge and first row ghost
    val rowSpacingX = cx + selOffsetX
    val rowSpacingY = (if (dirY > 0) bounds.maxY else bounds.minY) + gapY / 2f * dirY + selOffsetY
    // Column count handle: right/left edge of last column (first row)
    val colCountX = if (dirX > 0) bounds.maxX + (cols - 1) * (baseW + gapX) * dirX + selOffsetX
                    else bounds.minX + (cols - 1) * (baseW + gapX) * dirX + selOffsetX
    val colCountY = cy + selOffsetY
    // Row count handle: bottom/top edge of last row (first column)
    val rowCountX = cx + selOffsetX
    val rowCountY = if (dirY > 0) bounds.maxY + (rows - 1) * (baseH + gapY) * dirY + selOffsetY
                    else bounds.minY + (rows - 1) * (baseH + gapY) * dirY + selOffsetY

    val colSpacingScreen = worldToScreen(colSpacingX, colSpacingY)
    val rowSpacingScreen = worldToScreen(rowSpacingX, rowSpacingY)
    val colCountScreen = worldToScreen(colCountX, colCountY)
    val rowCountScreen = worldToScreen(rowCountX, rowCountY)

    // 数量手柄拖动中跟随手指（松手后吸附回整格边缘）
    var colCountDragX by remember { mutableStateOf<Float?>(null) }
    var rowCountDragY by remember { mutableStateOf<Float?>(null) }
    val colCountScreenEff = colCountDragX?.let { Offset(it, colCountScreen.y) } ?: colCountScreen
    val rowCountScreenEff = rowCountDragY?.let { Offset(rowCountScreen.x, it) } ?: rowCountScreen

    Box(modifier = modifier.fillMaxSize()) {
        // Ghost preview canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            val ghostColor = Color(0x804B9CD3)
            val ghostAlpha = 0.35f
            // Draw ghost primitives for each array position
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    if (r == 0 && c == 0) continue
                    val dx = c * (baseW + gapX) * dirX + selOffsetX
                    val dy = r * (baseH + gapY) * dirY + selOffsetY
                    for (prim in primitives) {
                        when (prim) {
                            is DrawingPrimitive.FreehandPath -> {
                                if (prim.points.size < 2) continue
                                val modelPts = prim.points.map { Point2D(it.x + dx, it.y + dy) }
                                val path = smoothPathFromPoints(modelPts, prim.isClosed)
                                drawPathWithStyle(
                                    path = path,
                                    color = ghostColor,
                                    strokeWidth = prim.strokeWidth,
                                    lineStyle = prim.lineStyle,
                                    strokeScale = canvasScale * globalLineScale
                                )
                                if (prim.lineStyle.type == LineType.LIGHTNING) {
                                    drawLightningOnPolyline(modelPts, prim.isClosed, ghostColor, canvasScale * globalLineScale)
                                }
                            }
                            is DrawingPrimitive.RectanglePrimitive -> {
                                // 与 DrawingCanvas 一致：AABB 角点绕中心按 rotation 旋转
                                val xs = prim.corners.map { it.x }; val ys = prim.corners.map { it.y }
                                val rcx = (xs.min() + xs.max()) / 2f; val rcy = (ys.min() + ys.max()) / 2f
                                val hw = (xs.max() - xs.min()) / 2f; val hh = (ys.max() - ys.min()) / 2f
                                val cosP = kotlin.math.cos(prim.rotation); val sinP = kotlin.math.sin(prim.rotation)
                                fun gcorner(lx: Float, ly: Float): Offset {
                                    val wx = rcx + lx * cosP - ly * sinP + dx
                                    val wy = rcy + lx * sinP + ly * cosP + dy
                                    return worldToScreen(wx, wy)
                                }
                                val pts = listOf(gcorner(-hw, -hh), gcorner(hw, -hh), gcorner(hw, hh), gcorner(-hw, hh))
                                val path = Path().apply { moveTo(pts[0].x, pts[0].y); lineTo(pts[1].x, pts[1].y); lineTo(pts[2].x, pts[2].y); lineTo(pts[3].x, pts[3].y); close() }
                                val sw = maxOf(prim.strokeWidth * canvasScale * globalLineScale, 1f)
                                drawPath(path, ghostColor, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
                            }
                            is DrawingPrimitive.CirclePrimitive -> {
                                val center = worldToScreen(prim.centerX + dx, prim.centerY + dy)
                                val rx = prim.radiusX * canvasScale
                                val ry = prim.radiusY * canvasScale
                                val sw = maxOf(prim.strokeWidth * canvasScale * globalLineScale, 1f)
                                if (abs(prim.rotation) > 0.001f && abs(rx - ry) > 0.5f) {
                                    // 旋转椭圆：原生画布绕中心旋转
                                    val nc = drawContext.canvas.nativeCanvas
                                    val paint = Paint().apply {
                                        color = ghostColor.toArgb()
                                        style = Paint.Style.STROKE
                                        strokeWidth = sw; isAntiAlias = true
                                    }
                                    nc.save()
                                    nc.rotate(prim.rotation * 180f / kotlin.math.PI.toFloat(), center.x, center.y)
                                    nc.drawOval(center.x - rx, center.y - ry, center.x + rx, center.y + ry, paint)
                                    nc.restore()
                                } else {
                                    val path = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center.x - rx, center.y - ry, center.x + rx, center.y + ry)) }
                                    drawPath(path, ghostColor, style = Stroke(sw))
                                }
                            }
                            is DrawingPrimitive.LinePrimitive -> {
                                val s = worldToScreen(prim.startX + dx, prim.startY + dy)
                                val e = worldToScreen(prim.endX + dx, prim.endY + dy)
                                val sw = maxOf(prim.strokeWidth * canvasScale * globalLineScale, 1f)
                                drawLine(ghostColor, s, e, sw, StrokeCap.Round)
                            }
                            is DrawingPrimitive.TextPrimitive -> {
                                val sc = worldToScreen(prim.x + dx, prim.y + dy)
                                val p = Paint().apply {
                                    typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true
                                    textAlign = Paint.Align.CENTER
                                    textSize = prim.fontSize * canvasScale
                                    color = ghostColor.toArgb()
                                }
                                val nc = drawContext.canvas.nativeCanvas
                                nc.save(); nc.translate(sc.x, sc.y)
                                nc.rotate(prim.rotation * 180f / kotlin.math.PI.toFloat())
                                nc.drawText(prim.text, 0f, p.fontMetrics.descent * 0.3f, p)
                                nc.restore()
                            }
                            is DrawingPrimitive.NumberLabelPrimitive -> {
                                val sc = worldToScreen(prim.x + dx, prim.y + dy)
                                val p = Paint().apply {
                                    typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true
                                    textAlign = Paint.Align.CENTER
                                    textSize = prim.fontSize * canvasScale
                                    color = ghostColor.toArgb()
                                }
                                val nc = drawContext.canvas.nativeCanvas
                                nc.save(); nc.translate(sc.x, sc.y)
                                nc.rotate(prim.rotation * 180f / kotlin.math.PI.toFloat())
                                nc.drawText(prim.value.toString(), 0f, p.fontMetrics.descent * 0.3f, p)
                                nc.restore()
                            }
                            is DrawingPrimitive.RangeLabelPrimitive -> {
                                val sc = worldToScreen(prim.x + dx, prim.y + dy)
                                val p = Paint().apply {
                                    typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true
                                    textAlign = Paint.Align.CENTER
                                    textSize = prim.fontSize * canvasScale
                                    color = ghostColor.toArgb()
                                }
                                val nc = drawContext.canvas.nativeCanvas
                                nc.save(); nc.translate(sc.x, sc.y)
                                nc.rotate(prim.rotation * 180f / kotlin.math.PI.toFloat())
                                nc.drawText("${prim.startValue}→${prim.endValue}", 0f, p.fontMetrics.descent * 0.3f, p)
                                nc.restore()
                            }
                            is DrawingPrimitive.BlockRefPrimitive -> {
                                val sc = worldToScreen(prim.x + dx, prim.y + dy)
                                // Simple cross marker for block refs
                                val sz = 8f * canvasScale
                                drawLine(ghostColor, Offset(sc.x - sz, sc.y - sz), Offset(sc.x + sz, sc.y + sz), 1.5f)
                                drawLine(ghostColor, Offset(sc.x + sz, sc.y - sz), Offset(sc.x - sz, sc.y + sz), 1.5f)
                            }
                        }
                    }
                }
            }

            // Handle visuals — 三角指向手柄：蓝填充 + 白描边 + 投影，
            // 方向跟随阵列朝向（反向阵列时三角自动镜像）；数量手柄内部带加号
            val handleBlue = Color(0xFF4B9CD3)
            val triH = with(density) { 13.dp.toPx() }   // 三角形半长（尖端方向）
            val triW = with(density) { 11.dp.toPx() }   // 三角形半宽（底边方向）

            fun drawTriHandle(center: Offset, dir: Offset, plus: Boolean) {
                val px = -dir.y; val py = dir.x  // dir 的法向
                val tipX = center.x + dir.x * triH; val tipY = center.y + dir.y * triH
                val bx = center.x - dir.x * triH; val by = center.y - dir.y * triH
                fun build(dy: Float) = Path().apply {
                    moveTo(tipX, tipY + dy)
                    lineTo(bx + px * triW, by + py * triW + dy)
                    lineTo(bx - px * triW, by - py * triW + dy)
                    close()
                }
                drawPath(build(1.5f), Color(0x33000000))  // 投影
                drawPath(build(0f), handleBlue)
                drawPath(build(0f), Color.White, style = Stroke(width = 2f, join = StrokeJoin.Round))
                if (plus) {
                    // 质心处画白色小加号
                    val cxp = (tipX + 2 * bx) / 3f; val cyp = (tipY + 2 * by) / 3f
                    val a = triW * 0.4f
                    drawLine(Color.White, Offset(cxp - a, cyp), Offset(cxp + a, cyp), 2.5f, StrokeCap.Round)
                    drawLine(Color.White, Offset(cxp, cyp - a), Offset(cxp, cyp + a), 2.5f, StrokeCap.Round)
                }
            }

            if (cols > 1) drawTriHandle(colSpacingScreen, Offset(dirX.toFloat(), 0f), plus = false)
            if (rows > 1) drawTriHandle(rowSpacingScreen, Offset(0f, dirY.toFloat()), plus = false)
            drawTriHandle(colCountScreenEff, Offset(dirX.toFloat(), 0f), plus = true)
            drawTriHandle(rowCountScreenEff, Offset(0f, dirY.toFloat()), plus = true)
        }

        // Column spacing handle (drag horizontal → gapX) — hidden when cols <= 1
        if (cols > 1) {
        val csGapXState = rememberUpdatedState(gapX)
        val csDirXState = rememberUpdatedState(dirX)
        Box(Modifier.offset { IntOffset((colSpacingScreen.x - handleRadiusPx).toInt(), (colSpacingScreen.y - handleRadiusPx).toInt()) }
            .size(handleSize).clip(CircleShape)
            .handleInterlock(onHandleActiveChanged)
            .pointerInput(Unit) {
                detectDragGestures { ch, da ->
                    ch.consume()
                    // 最小间距 0：单元间距 = 图形宽度 + gap ≥ 图形宽度
                    // 反向阵列时向阵列生长方向（左）拖才增大间距
                    val newGap = (csGapXState.value + da.x * csDirXState.value / canvasScale)
                        .coerceIn(0f, 10000f)
                    onGapXChange(newGap)
                }
            })
        }

        // Row spacing handle (drag vertical → gapY) — hidden when rows <= 1
        if (rows > 1) {
        val rsGapYState = rememberUpdatedState(gapY)
        val rsDirYState = rememberUpdatedState(dirY)
        Box(Modifier.offset { IntOffset((rowSpacingScreen.x - handleRadiusPx).toInt(), (rowSpacingScreen.y - handleRadiusPx).toInt()) }
            .size(handleSize).clip(CircleShape)
            .handleInterlock(onHandleActiveChanged)
            .pointerInput(Unit) {
                detectDragGestures { ch, da ->
                    ch.consume()
                    // 最小间距 0：单元间距 = 图形高度 + gap ≥ 图形高度
                    // 反向阵列时向阵列生长方向（上）拖才增大间距
                    val newGap = (rsGapYState.value + da.y * rsDirYState.value / canvasScale)
                        .coerceIn(0f, 10000f)
                    onGapYChange(newGap)
                }
            })
        }

        // Column count handle (drag to adjust count, follows finger)
        val colsState = rememberUpdatedState(cols)
        val dirXState = rememberUpdatedState(dirX)
        val colCountScreenState = rememberUpdatedState(colCountScreen)
        val canvasOffsetXState = rememberUpdatedState(canvasOffsetX)
        val selOffsetXState = rememberUpdatedState(selOffsetX)
        val baseWState = rememberUpdatedState(baseW)
        val gapXState = rememberUpdatedState(gapX)
        Box(Modifier.offset { IntOffset((colCountScreenEff.x - handleRadiusPx).toInt(), (colCountScreenEff.y - handleRadiusPx).toInt()) }
            .size(handleSize).clip(CircleShape)
            .handleInterlock(onHandleActiveChanged)
            .pointerInput(Unit) {
                // 以拖动起点为锚累加位移，避免手柄吸附跳动造成计数失控
                var anchorScreenX = 0f
                var dragAccX = 0f
                detectDragGestures(
                    onDragStart = {
                        anchorScreenX = colCountScreenState.value.x
                        dragAccX = 0f
                        colCountDragX = anchorScreenX
                    },
                    onDragEnd = { colCountDragX = null },
                    onDragCancel = { colCountDragX = null },
                    onDrag = { ch, da ->
                        ch.consume()
                        dragAccX += da.x
                        colCountDragX = anchorScreenX + dragAccX
                        val fx = (anchorScreenX + dragAccX - canvasOffsetXState.value) / canvasScale - selOffsetXState.value
                        val cellW = baseWState.value + gapXState.value
                        if (cellW > 0.1f) {
                            // 手指越过边缘立即生成首个幽灵列（不再要求拖满整格），之后每过一整格加一列；
                            // 图形内部为死区（保持 1 列、方向不变），从对侧边缘穿出后立即按新方向连续计数
                            when {
                                fx > bounds.maxX -> {
                                    if (dirXState.value < 0) onDirXChange(1)
                                    val n = (2 + floor((fx - bounds.maxX) / cellW).toInt()).coerceAtLeast(2)
                                    if (n != colsState.value) onColsChange(n)
                                }
                                fx < bounds.minX -> {
                                    if (dirXState.value > 0) onDirXChange(-1)
                                    val n = (2 + floor((bounds.minX - fx) / cellW).toInt()).coerceAtLeast(2)
                                    if (n != colsState.value) onColsChange(n)
                                }
                                else -> {
                                    if (colsState.value != 1) onColsChange(1)
                                }
                            }
                        }
                    }
                )
            })

        // Row count handle (drag to adjust count, follows finger)
        val rowsState = rememberUpdatedState(rows)
        val dirYState = rememberUpdatedState(dirY)
        val rowCountScreenState = rememberUpdatedState(rowCountScreen)
        val canvasOffsetYState = rememberUpdatedState(canvasOffsetY)
        val selOffsetYState = rememberUpdatedState(selOffsetY)
        val baseHState = rememberUpdatedState(baseH)
        val gapYState = rememberUpdatedState(gapY)
        Box(Modifier.offset { IntOffset((rowCountScreenEff.x - handleRadiusPx).toInt(), (rowCountScreenEff.y - handleRadiusPx).toInt()) }
            .size(handleSize).clip(CircleShape)
            .handleInterlock(onHandleActiveChanged)
            .pointerInput(Unit) {
                var anchorScreenY = 0f
                var dragAccY = 0f
                detectDragGestures(
                    onDragStart = {
                        anchorScreenY = rowCountScreenState.value.y
                        dragAccY = 0f
                        rowCountDragY = anchorScreenY
                    },
                    onDragEnd = { rowCountDragY = null },
                    onDragCancel = { rowCountDragY = null },
                    onDrag = { ch, da ->
                        ch.consume()
                        dragAccY += da.y
                        rowCountDragY = anchorScreenY + dragAccY
                        val fy = (anchorScreenY + dragAccY - canvasOffsetYState.value) / canvasScale - selOffsetYState.value
                        val cellH = baseHState.value + gapYState.value
                        if (cellH > 0.1f) {
                            // 与列数手柄同理：越过边缘立即生成首个幽灵行，之后每过一整格加一行，穿越图形换边
                            when {
                                fy > bounds.maxY -> {
                                    if (dirYState.value < 0) onDirYChange(1)
                                    val n = (2 + floor((fy - bounds.maxY) / cellH).toInt()).coerceAtLeast(2)
                                    if (n != rowsState.value) onRowsChange(n)
                                }
                                fy < bounds.minY -> {
                                    if (dirYState.value > 0) onDirYChange(-1)
                                    val n = (2 + floor((bounds.minY - fy) / cellH).toInt()).coerceAtLeast(2)
                                    if (n != rowsState.value) onRowsChange(n)
                                }
                                else -> {
                                    if (rowsState.value != 1) onRowsChange(1)
                                }
                            }
                        }
                    }
                )
            })
    }
}
