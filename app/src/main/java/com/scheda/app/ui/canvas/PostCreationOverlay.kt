package com.scheda.app.ui.canvas

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scheda.app.model.DrawingPrimitive
import com.scheda.app.model.BlockDef
import com.scheda.app.model.LineType
import com.scheda.app.model.PendingEdit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.delay

@Composable
fun PostCreationOverlay(
    pendingEdit: PendingEdit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onUpdateOffset: (Float, Float) -> Unit,
    onUpdateRotation: (Float) -> Unit,
    onUpdateScale: (Float, Float) -> Unit,
    onUpdateFontScale: (Float) -> Unit = {},
    onUpdateArrowSpan: (Float) -> Unit = {},
    onUpdateRangeValue: (isStart: Boolean, value: Int) -> Unit = { _, _ -> },
    onToggleTextOrientation: () -> Unit = {},
    onToggleRangeReversed: () -> Unit = {},
    currentFontSize: Float = 40f,
    onCanvasTransform: (zoom: Float, centroid: Offset, pan: Offset) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
    canvasScale: Float = 1f,
    canvasOffsetX: Float = 0f,
    canvasOffsetY: Float = 0f,
    globalLineScale: Float = 1f,
    blockDefs: List<BlockDef> = emptyList()
) {
    if (!pendingEdit.isActive() || pendingEdit.bounds == null) return

    val bounds = pendingEdit.bounds!!
    val handleSize = 40.dp
    val density = LocalDensity.current
    val handleRadiusPx = with(density) { handleSize.toPx() / 2f }
    val ctState = rememberUpdatedState(onCanvasTransform)
    val csState = rememberUpdatedState(canvasScale)

    val cx = (bounds.minX + bounds.maxX) / 2f + pendingEdit.offsetX
    val cy = (bounds.minY + bounds.maxY) / 2f + pendingEdit.offsetY
    // Minimum half-extent in screen pixels (so corner handles don't collapse)
    val minHalf = with(density) { 30.dp.toPx() }
    val textMeasurePaint = Paint().apply {
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    val (halfW, halfH) = when (val p = pendingEdit.primitive) {
        is DrawingPrimitive.TextPrimitive -> {
            val avgScale = sqrt(abs(pendingEdit.scaleX * pendingEdit.scaleY))
            val lineScale = globalLineScale * p.lineScaleFactor
            textMeasurePaint.textSize = p.fontSize * avgScale * lineScale * 1.3f
            val textWidth = textMeasurePaint.measureText(p.text)
            val fm = textMeasurePaint.fontMetrics
            Pair(
                maxOf(textWidth / 2f, minHalf / canvasScale),
                maxOf((fm.descent - fm.ascent) / 2f, minHalf / canvasScale)
            )
        }
        is DrawingPrimitive.NumberLabelPrimitive -> {
            val avgScale = sqrt(abs(pendingEdit.scaleX * pendingEdit.scaleY))
            val lineScale = globalLineScale * p.lineScaleFactor
            textMeasurePaint.textSize = p.fontSize * avgScale * lineScale * 1.3f
            val textWidth = textMeasurePaint.measureText(p.value.toString())
            val fm = textMeasurePaint.fontMetrics
            Pair(
                maxOf(textWidth / 2f, minHalf / canvasScale),
                maxOf((fm.descent - fm.ascent) / 2f, minHalf / canvasScale)
            )
        }
        else -> {
            val w = maxOf(((bounds.maxX - bounds.minX) / 2f) * pendingEdit.scaleX, minHalf / canvasScale)
            val h = maxOf(((bounds.maxY - bounds.minY) / 2f) * pendingEdit.scaleY, minHalf / canvasScale)
            Pair(w, h)
        }
    }
    // Frame rotation = pending edit handle rotation + primitive's intrinsic rotation
    val primFrameRot = when (val p = pendingEdit.primitive) {
        is DrawingPrimitive.RangeLabelPrimitive -> p.rotation
        is DrawingPrimitive.NumberLabelPrimitive -> p.rotation
        is DrawingPrimitive.TextPrimitive -> p.rotation
        is DrawingPrimitive.BlockRefPrimitive -> p.rotation
        is DrawingPrimitive.RectanglePrimitive -> p.rotation
        else -> 0f
    }
    val totalFrameRotation = pendingEdit.rotation + primFrameRot
    val cosR = cos(totalFrameRotation); val sinR = sin(totalFrameRotation)

    fun worldToScreen(wx: Float, wy: Float): Offset =
        Offset(wx * canvasScale + canvasOffsetX, wy * canvasScale + canvasOffsetY)

    fun localToScreen(lx: Float, ly: Float): Offset {
        val rx = lx * cosR - ly * sinR + cx; val ry = lx * sinR + ly * cosR + cy
        return worldToScreen(rx, ry)
    }

    val corners = listOf(
        localToScreen(-halfW, -halfH), localToScreen(+halfW, -halfH),
        localToScreen(-halfW, +halfH), localToScreen(+halfW, +halfH),
    )
    val centerScreen = worldToScreen(cx, cy)

    // Calculate padded corners for the blue frame AND handles
    val paddingPx = with(density) { 20.dp.toPx() }
    val paddedCorners = corners.map { c ->
        val dx = c.x - centerScreen.x
        val dy = c.y - centerScreen.y
        val dist = sqrt(dx * dx + dy * dy)
        if (dist > 0.01f) {
            Offset(c.x + (dx / dist) * paddingPx, c.y + (dy / dist) * paddingPx)
        } else c
    }

    // Midpoints on the blue padded frame edges (only for non-text/number primitives)
    val isTextNum = pendingEdit.primitive is DrawingPrimitive.TextPrimitive ||
                    pendingEdit.primitive is DrawingPrimitive.NumberLabelPrimitive
    val isRange = pendingEdit.primitive is DrawingPrimitive.RangeLabelPrimitive
    val allMidpoints = if (isTextNum) emptyList()
        else if (isRange) listOf(
            Offset((paddedCorners[0].x + paddedCorners[2].x) / 2f, (paddedCorners[0].y + paddedCorners[2].y) / 2f),  // left only
            Offset((paddedCorners[1].x + paddedCorners[3].x) / 2f, (paddedCorners[1].y + paddedCorners[3].y) / 2f),  // right only
        )
        else listOf(
        Offset((paddedCorners[0].x + paddedCorners[1].x) / 2f, (paddedCorners[0].y + paddedCorners[1].y) / 2f),  // top
        Offset((paddedCorners[2].x + paddedCorners[3].x) / 2f, (paddedCorners[2].y + paddedCorners[3].y) / 2f),  // bottom
        Offset((paddedCorners[0].x + paddedCorners[2].x) / 2f, (paddedCorners[0].y + paddedCorners[2].y) / 2f),  // left
        Offset((paddedCorners[1].x + paddedCorners[3].x) / 2f, (paddedCorners[1].y + paddedCorners[3].y) / 2f),  // right
    )
    val topLen = (paddedCorners[1] - paddedCorners[0]).getDistance()
    val botLen = (paddedCorners[3] - paddedCorners[2]).getDistance()
    val leftLen = (paddedCorners[2] - paddedCorners[0]).getDistance()
    val rightLen = (paddedCorners[3] - paddedCorners[1]).getDistance()
    val finalMidpoints = allMidpoints

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var hadMulti = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.filter { it.pressed }
                        val sc = canvasScale
                        if (pressed.size >= 2) {
                            hadMulti = true
                            val ctf = ctState.value
                            val zoom = if (pressed.size >= 2) {
                                val dx1 = pressed[0].position.x - pressed[1].position.x
                                val dy1 = pressed[0].position.y - pressed[1].position.y
                                val d1 = sqrt(dx1 * dx1 + dy1 * dy1)
                                val dx2 = pressed[0].previousPosition.x - pressed[1].previousPosition.x
                                val dy2 = pressed[0].previousPosition.y - pressed[1].previousPosition.y
                                val d2 = sqrt(dx2 * dx2 + dy2 * dy2)
                                if (d2 > 0f) d1 / d2 else 1f
                            } else 1f
                            val centroid = if (pressed.isNotEmpty()) Offset(
                                pressed.map { it.position.x }.average().toFloat(),
                                pressed.map { it.position.y }.average().toFloat()
                            ) else Offset.Zero
                            val prevCentroid = if (pressed.isNotEmpty()) Offset(
                                pressed.map { it.previousPosition.x }.average().toFloat(),
                                pressed.map { it.previousPosition.y }.average().toFloat()
                            ) else Offset.Zero
                            val pan = centroid - prevCentroid
                            ctf(zoom, centroid, pan)
                            pressed.forEach { ch -> ch.consume() }
                        } else if (hadMulti && pressed.isEmpty()) {
                            hadMulti = false
                        }
                        // single-touch falls through to children
                    }
                }
            }
    ) {
        // Layer 1: non-interactive Canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            val primitive = pendingEdit.primitive
            if (primitive != null) drawPrimitiveAt(primitive, pendingEdit, canvasScale, canvasOffsetX, canvasOffsetY, globalLineScale * primitive.lineScaleFactor, blockDefs)

            val colors = listOf(Color(0xFF34A853), Color(0xFFFF9800), Color(0xFFFF9800), Color(0xFF34A853))
                val labels = listOf("", "↻", "↺", "")
                for ((i, c) in paddedCorners.withIndex()) {
                val bg = colors[i].copy(alpha = 0.2f)
                drawCircle(bg, handleRadiusPx, c)
                drawCircle(colors[i], handleRadiusPx, c, style = Stroke(width = 2.5f))
                if (labels[i].isNotEmpty()) {
                    val p = Paint().apply {
                        this.color = colors[i].hashCode()
                        textSize = handleRadiusPx * 1.2f; textAlign = Paint.Align.CENTER; isAntiAlias = true
                    }
                    val nc = drawContext.canvas.nativeCanvas
                    val textX = c.x; val textY = c.y + handleRadiusPx * 0.35f
                    nc.drawText(labels[i], textX, textY, p)
                } else {
                    // Scale corners: two smaller outward-pointing arrows rotating with the frame
                    val arrowRotDeg = totalFrameRotation * 180f / kotlin.math.PI.toFloat()
                    val nc = drawContext.canvas.nativeCanvas
                    nc.save()
                    nc.translate(c.x, c.y)
                    nc.rotate(arrowRotDeg)
                    val r = handleRadiusPx * 0.32f
                    val strokeW = 2.5f
                    val headLen = handleRadiusPx * 0.16f
                    val headAngle = 30f * kotlin.math.PI.toFloat() / 180f
                    val isTopLeft = i == 0
                    val arrows = if (isTopLeft) listOf(
                        Triple(Offset(0f, r), Offset(0f, -r), 270f * kotlin.math.PI.toFloat() / 180f), // up
                        Triple(Offset(r, 0f), Offset(-r, 0f), 180f * kotlin.math.PI.toFloat() / 180f)  // left
                    ) else listOf(
                        Triple(Offset(0f, -r), Offset(0f, r), 90f * kotlin.math.PI.toFloat() / 180f),  // down
                        Triple(Offset(-r, 0f), Offset(r, 0f), 0f)                                      // right
                    )
                    for ((start, end, dirAngle) in arrows) {
                        drawLine(colors[i], start, end, strokeW, StrokeCap.Round)
                        val backAngle = dirAngle + kotlin.math.PI.toFloat()
                        val a1 = backAngle + headAngle
                        val a2 = backAngle - headAngle
                        drawLine(colors[i], end, Offset(end.x + headLen * kotlin.math.cos(a1), end.y + headLen * kotlin.math.sin(a1)), strokeW, StrokeCap.Round)
                        drawLine(colors[i], end, Offset(end.x + headLen * kotlin.math.cos(a2), end.y + headLen * kotlin.math.sin(a2)), strokeW, StrokeCap.Round)
                    }
                    nc.restore()
                }
            }

            // Midpoint resize handles (rounded-rect pills on each edge)
            val midHw = with(density) { 13.dp.toPx() }
            val midHh = with(density) { 7.dp.toPx() }
            val midR = with(density) { 4.dp.toPx() }
            val midColor = Color(0xFF4B9CD3)
            for ((i, m) in finalMidpoints.withIndex()) {
                // Range side handles always vertical pill; rotation aligns with edge
                val isHorizontal = if (isRange) false else i < 2
                val hw = if (isHorizontal) midHw else midHh
                val hh = if (isHorizontal) midHh else midHw
                val tl = Offset(m.x - hw, m.y - hh)
                val sz = Size(hw * 2, hh * 2)
                val midRot = totalFrameRotation * 180f / kotlin.math.PI.toFloat()
                if (kotlin.math.abs(midRot) > 1f) {
                    // Use native canvas translate->rotate so the rect stays centered on
                    // the midpoint and ONLY its orientation changes.  DrawScope.rotate()
                    // applies the offset BEFORE rotating, which moves the pill to a
                    // completely different world position (the "flying-off" bug).
                    val nc = drawContext.canvas.nativeCanvas
                    nc.save()
                    nc.translate(m.x, m.y)
                    nc.rotate(midRot)
                    drawRoundRect(Color.White, Offset(-hw, -hh), sz, CornerRadius(midR))
                    drawRoundRect(midColor, Offset(-hw, -hh), sz, CornerRadius(midR), style = Stroke(width = 2f))
                    nc.restore()
                } else {
                    drawRoundRect(Color.White, tl, sz, CornerRadius(midR))
                    drawRoundRect(midColor, tl, sz, CornerRadius(midR), style = Stroke(width = 2f))
                }
            }

            // Semi-transparent light blue frame through corner centers (circles drawn on top cover the joins)
            val lightBlue = Color(0x404B9CD3)
                val framePath = Path().apply {
                    moveTo(paddedCorners[0].x, paddedCorners[0].y)
                    lineTo(paddedCorners[1].x, paddedCorners[1].y)
                    lineTo(paddedCorners[3].x, paddedCorners[3].y)
                    lineTo(paddedCorners[2].x, paddedCorners[2].y)
                    close()
                }
                drawPath(framePath, lightBlue, style = Stroke(width = 3f))
                // Also fill with very faint light blue
                drawPath(framePath, lightBlue.copy(alpha = 0.12f))
        }

        // Layer 1.5: range edit dialog (triggered from bottom toolbar, status bar removed)
        val pendingPrimitive = pendingEdit.primitive
        if (pendingPrimitive is DrawingPrimitive.RangeLabelPrimitive) {
            val prim = pendingPrimitive
            var showRangeEditDlg by remember { mutableStateOf(false) }
            // Range number edit dialog
            if (showRangeEditDlg) {
                val focusEnd = remember { FocusRequester() }
                val svInit = prim.startValue.toString()
                val evInit = prim.endValue.toString()
                var svTv by remember { mutableStateOf(TextFieldValue(svInit)) }
                var evTv by remember { mutableStateOf(TextFieldValue(evInit)) }
                LaunchedEffect(Unit) { delay(100); focusEnd.requestFocus() }
                AlertDialog(onDismissRequest = { showRangeEditDlg = false },
                    title = { Text("区间数字") }, text = {
                        Column {
                            OutlinedTextField(svTv, { svTv = it },
                                label = { Text("首数字") }, singleLine = true,
                                modifier = Modifier.onFocusChanged { if (it.isFocused) svTv = svTv.copy(selection = TextRange(0, svTv.text.length)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            Spacer(Modifier.height(4.dp))
                            OutlinedTextField(evTv, { evTv = it },
                                label = { Text("尾数字") }, singleLine = true,
                                modifier = Modifier.focusRequester(focusEnd).onFocusChanged { if (it.isFocused) evTv = evTv.copy(selection = TextRange(0, evTv.text.length)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        }
                    }, confirmButton = {
                        TextButton(onClick = {
                            val s = svTv.text.filter { c -> c.isDigit() }.toIntOrNull() ?: prim.startValue
                            val e = evTv.text.filter { c -> c.isDigit() }.toIntOrNull() ?: prim.endValue
                            onUpdateRangeValue(true, s)
                            onUpdateRangeValue(false, e)
                            showRangeEditDlg = false
                        }) { Text("确定") }
                    }, dismissButton = {
                        TextButton(onClick = { showRangeEditDlg = false }) { Text("取消") }
                    })
            }
            Unit
        }

        // Layer 2: body drag (below handles so handles receive touch first)
        val bx = paddedCorners.minOf { it.x }; val by = paddedCorners.minOf { it.y }
        val bw = paddedCorners.maxOf { it.x } - bx; val bh = paddedCorners.maxOf { it.y } - by
        if (bw > 20f && bh > 20f) {
            val scaleState2 = rememberUpdatedState(canvasScale)
            Box(Modifier.offset { IntOffset(bx.toInt(), by.toInt()) }
                .size(with(density) { bw.toDp() }, with(density) { bh.toDp() })
                .pointerInput(Unit) {
                    detectDragGestures { ch, da ->
                        ch.consume()
                        val sc = scaleState2.value
                        onUpdateOffset(da.x / sc, da.y / sc)    // 全向移动
                    }
                })
        }

        // Layer 3: corner handles (on top of body drag)
        for ((i, corner) in paddedCorners.withIndex()) {
            val cornerState = rememberUpdatedState(corner)
            val centerState = rememberUpdatedState(centerScreen)
            val peState = rememberUpdatedState(pendingEdit)
            val scaleState = rememberUpdatedState(canvasScale)
            Box(
                modifier = Modifier
                    .offset { IntOffset((corner.x - handleRadiusPx).toInt(), (corner.y - handleRadiusPx).toInt()) }
                    .size(handleSize).clip(CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val c = cornerState.value; val cs = centerState.value; val pe = peState.value; val sc = scaleState.value
                            val dxW = dragAmount.x / sc; val dyW = dragAmount.y / sc
                            val hx = c.x - cs.x; val hy = c.y - cs.y; val dsq = hx * hx + hy * hy
                            when (i) {
                                0 -> { if (dsq > 1f) { val r = (hx * dragAmount.x + hy * dragAmount.y) / dsq; onUpdateScale(1f + r, 1f + r) } }
                                1 -> { if (dsq > 1f) onUpdateRotation(pe.rotation + (hx * dragAmount.y - hy * dragAmount.x) / dsq) }
                                2 -> { if (dsq > 1f) onUpdateRotation(pe.rotation + (hx * dragAmount.y - hy * dragAmount.x) / dsq) }
                                3 -> { if (dsq > 1f) { val r = (hx * dragAmount.x + hy * dragAmount.y) / dsq; onUpdateScale(1f + r, 1f + r) } }
                            }
                        }
                    }
            )
        }

        // Layer 3b: midpoint resize handles (directional)
        for ((i, m) in finalMidpoints.withIndex()) {
            val midPosState = rememberUpdatedState(m)
            val midCenterState = rememberUpdatedState(centerScreen)
            val midPeState = rememberUpdatedState(pendingEdit)
            Box(
                modifier = Modifier
                    .offset { IntOffset((m.x - handleRadiusPx).toInt(), (m.y - handleRadiusPx).toInt()) }
                    .size(handleSize).clip(CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val mp = midPosState.value; val cs = midCenterState.value
                            val pe = midPeState.value
                            val hx = mp.x - cs.x; val hy = mp.y - cs.y
                            val dsq = hx * hx + hy * hy
                            if (dsq > 1f) {
                                val r = (hx * dragAmount.x + hy * dragAmount.y) / dsq
                                if (isRange) {
                                    // 区间数字：两侧中点只调箭头杆长（arrowSpan），不影响整体缩放
                                    onUpdateArrowSpan(1f + r)
                                } else {
                                    val sc = csState.value
                                    val halfActual = sqrt(dsq) - paddingPx
                                    when (i) {
                                        0 -> { // 上中点：拉伸高度，下边不动
                                            onUpdateScale(1f, 1f + r)
                                            onUpdateOffset(0f, -halfActual * r / sc)
                                        }
                                        1 -> { // 下中点：拉伸高度，上边不动
                                            onUpdateScale(1f, 1f + r)
                                            onUpdateOffset(0f, halfActual * r / sc)
                                        }
                                        2 -> { // 左中点：拉伸宽度，右边不动
                                            onUpdateScale(1f + r, 1f)
                                            onUpdateOffset(-halfActual * r / sc, 0f)
                                        }
                                        3 -> { // 右中点：拉伸宽度，左边不动
                                            onUpdateScale(1f + r, 1f)
                                            onUpdateOffset(halfActual * r / sc, 0f)
                                        }
                                    }
                                }
                            }
                        }
                    }
            )
        }

        // Layer 4: confirm/cancel buttons above floating status bar
        Row(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 64.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 确认取消按钮统一大小
            val btnSize = 36.dp
            val capsuleShape = RoundedCornerShape(btnSize / 2)
            FloatingActionButton(onClick = onCancel,
                modifier = Modifier.size(btnSize),
                containerColor = Color(0xFF757575), contentColor = Color.White, shape = capsuleShape) {
                Icon(Icons.Default.Close, "取消", modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            FloatingActionButton(onClick = onConfirm,
                modifier = Modifier.size(btnSize),
                containerColor = Color(0xFF4CAF50), contentColor = Color.White, shape = capsuleShape) {
                Icon(Icons.Default.Check, "确认", modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * Draw a primitive with pending edit transforms applied (preview overlay).
 */
private fun DrawScope.drawPrimitiveAt(
    primitive: DrawingPrimitive, edit: PendingEdit, canvasScale: Float, canvasOffsetX: Float, canvasOffsetY: Float,
    strokeScale: Float = 1f,
    blockDefs: List<BlockDef> = emptyList()
) {
    val ox = edit.offsetX; val oy = edit.offsetY
    val sx = edit.scaleX; val sy = edit.scaleY
    val cosR = cos(edit.rotation); val sinR = sin(edit.rotation)
    val alpha = 0.7f
    val bounds = edit.bounds
    val cx0 = bounds?.let { (it.minX + it.maxX) / 2f } ?: 0f
    val cy0 = bounds?.let { (it.minY + it.maxY) / 2f } ?: 0f
    val newCx = cx0 + ox; val newCy = cy0 + oy
    fun transform(wx: Float, wy: Float): Offset {
        var x = (wx - cx0) * sx; var y = (wy - cy0) * sy
        val rx = x * cosR - y * sinR + newCx; val ry = x * sinR + y * cosR + newCy
        return Offset(rx * canvasScale + canvasOffsetX, ry * canvasScale + canvasOffsetY)
    }
    when (primitive) {
        is DrawingPrimitive.FreehandPath -> {
            if (primitive.points.size < 2) return
            val path = Path().apply {
                var first = true
                for (pt in primitive.points) {
                    val tp = transform(pt.x, pt.y)
                    if (first) { moveTo(tp.x, tp.y); first = false }
                    else lineTo(tp.x, tp.y)
                }
                if (primitive.isClosed) close()
            }
            drawPathWithStyle(path, primitive.color.copy(alpha = alpha),
                primitive.strokeWidth, primitive.lineStyle, canvasScale * strokeScale)
        }
        is DrawingPrimitive.RectanglePrimitive -> {
            // First handle intrinsic rotation if any
            val hw = abs(primitive.endX - primitive.startX) / 2f
            val hh = abs(primitive.endY - primitive.startY) / 2f
            val rcX = minOf(primitive.startX, primitive.endX) + hw
            val rcY = minOf(primitive.startY, primitive.endY) + hh
            val cosP = kotlin.math.cos(primitive.rotation)
            val sinP = kotlin.math.sin(primitive.rotation)
            // 4 local corners (relative to rect center), rotated by intrinsic rotation
            val localCorners = listOf(
                rcX + (-hw) * cosP - (-hh) * sinP to rcY + (-hw) * sinP + (-hh) * cosP,
                rcX + ( hw) * cosP - (-hh) * sinP to rcY + ( hw) * sinP + (-hh) * cosP,
                rcX + ( hw) * cosP - ( hh) * sinP to rcY + ( hw) * sinP + ( hh) * cosP,
                rcX + (-hw) * cosP - ( hh) * sinP to rcY + (-hw) * sinP + ( hh) * cosP,
            )
            val corners = localCorners.map { (wx, wy) -> transform(wx, wy) }
            val rectPath = Path().apply {
                moveTo(corners[0].x, corners[0].y)
                for (i in 1..3) lineTo(corners[i].x, corners[i].y)
                close()
            }
            drawPathWithStyle(rectPath, primitive.color.copy(alpha = alpha),
                primitive.strokeWidth, primitive.lineStyle, canvasScale * strokeScale)
            if (primitive.lineStyle.type == LineType.LIGHTNING) {
                val edges = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0)
                for ((ei, ej) in edges) {
                    val a = corners[ei]; val b = corners[ej]
                    val lx = b.x - a.x; val ly = b.y - a.y
                    val len = sqrt(lx * lx + ly * ly)
                    if (len < 1f) continue
                    val n = maxOf(2, (len / 60f).toInt())
                    for (k in 1..n) {
                        val t = k.toFloat() / (n + 1)
                        val px = a.x + t * lx; val py = a.y + t * ly
                        val sz = 16f * canvasScale * strokeScale
                        drawLine(primitive.color.copy(alpha = alpha), Offset(px - sz, py - sz), Offset(px + sz, py + sz), strokeWidth = 1.5f * canvasScale)
                        drawLine(primitive.color.copy(alpha = alpha), Offset(px + sz, py - sz), Offset(px - sz, py + sz), strokeWidth = 1.5f * canvasScale)
                    }
                }
            }
        }
        is DrawingPrimitive.CirclePrimitive -> {
            val origRx = abs(primitive.endX - primitive.centerX)
            val origRy = abs(primitive.endY - primitive.centerY)
            val path = Path().apply {
                val segs = 32
                for (i in 0..segs) { val a = (i.toFloat() / segs) * 2f * kotlin.math.PI.toFloat()
                    val pt = transform(primitive.centerX + origRx * cos(a), primitive.centerY + origRy * sin(a))
                    if (i == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y) }
                close()
            }
            drawPathWithStyle(path, primitive.color.copy(alpha = alpha),
                primitive.strokeWidth, primitive.lineStyle, canvasScale * strokeScale)
            if (primitive.lineStyle.type == LineType.LIGHTNING) {
                val segs = 16
                val avgScale = sqrt(abs(edit.scaleX * edit.scaleY))
                val r2x = origRx * avgScale; val r2y = origRy * avgScale
                for (i in 0 until segs) {
                    val a1 = (i.toFloat() / segs) * 2f * kotlin.math.PI.toFloat()
                    val a2 = ((i + 1).toFloat() / segs) * 2f * kotlin.math.PI.toFloat()
                    val mid = (a1 + a2) / 2f
                    val mx = transform(primitive.centerX + origRx * cos(mid), primitive.centerY + origRy * sin(mid))
                    val sz = 16f * canvasScale * strokeScale
                    drawLine(primitive.color.copy(alpha = alpha), Offset(mx.x - sz, mx.y - sz), Offset(mx.x + sz, mx.y + sz), strokeWidth = 1.5f * canvasScale)
                    drawLine(primitive.color.copy(alpha = alpha), Offset(mx.x + sz, mx.y - sz), Offset(mx.x - sz, mx.y + sz), strokeWidth = 1.5f * canvasScale)
                }
            }
        }
        is DrawingPrimitive.LinePrimitive -> {
            val s = transform(primitive.startX, primitive.startY); val e = transform(primitive.endX, primitive.endY)
            drawLine(primitive.color.copy(alpha = alpha), s, e, strokeWidth = primitive.strokeWidth * canvasScale * strokeScale, cap = StrokeCap.Round)
            if (primitive.lineStyle.type == LineType.LIGHTNING) {
                val dx = e.x - s.x; val dy = e.y - s.y
                val len = sqrt(dx * dx + dy * dy)
                if (len > 1f) {
                    val n = maxOf(2, (len / 60f).toInt())
                    for (k in 1..n) {
                        val t = k.toFloat() / (n + 1)
                        val px = s.x + t * dx; val py = s.y + t * dy
                        val sz = 16f * canvasScale * strokeScale
                        drawLine(primitive.color.copy(alpha = alpha), Offset(px - sz, py - sz), Offset(px + sz, py + sz), strokeWidth = 1.5f * canvasScale)
                        drawLine(primitive.color.copy(alpha = alpha), Offset(px + sz, py - sz), Offset(px - sz, py + sz), strokeWidth = 1.5f * canvasScale)
                    }
                }
            }
        }
        is DrawingPrimitive.NumberLabelPrimitive -> {
            val pt = transform(primitive.x, primitive.y)
            val text = primitive.value.toString()
            val totalRotation = edit.rotation + primitive.rotation
            val avgScale = sqrt(abs(edit.scaleX * edit.scaleY))
            val lineScale = strokeScale
            val effectiveFontSize = (primitive.fontSize * avgScale).coerceIn(30f, 600f)
            val fs = effectiveFontSize * lineScale * canvasScale * 1.3f
            val p = Paint().apply {
                color = primitive.color.copy(alpha = alpha).hashCode()
                textSize = fs
                isAntiAlias = true; textAlign = Paint.Align.CENTER
            }
            val nc = drawContext.canvas.nativeCanvas
            if (kotlin.math.abs(totalRotation) > 0.001f) {
                nc.save()
                nc.rotate(totalRotation * 180f / kotlin.math.PI.toFloat(), pt.x, pt.y)
                nc.drawText(text, pt.x, pt.y + fs * 0.3f, p)
                nc.restore()
            } else {
                nc.drawText(text, pt.x, pt.y + fs * 0.3f, p)
            }
        }
        is DrawingPrimitive.TextPrimitive -> {
            val pt = transform(primitive.x, primitive.y)
            val totalRotation = edit.rotation + primitive.rotation
            val avgScale = sqrt(abs(edit.scaleX * edit.scaleY))
            val lineScale = strokeScale
            val effectiveFontSize = (primitive.fontSize * avgScale).coerceIn(30f, 600f)
            val fs = effectiveFontSize * lineScale * canvasScale
            val p = Paint().apply {
                color = primitive.color.copy(alpha = alpha).hashCode()
                textSize = fs * 1.3f
                isAntiAlias = true; textAlign = Paint.Align.CENTER
            }
            val nc = drawContext.canvas.nativeCanvas
            val text = primitive.text
            if (text.isBlank()) return@drawPrimitiveAt
            if (kotlin.math.abs(totalRotation) > 0.001f) {
                nc.save()
                nc.rotate(totalRotation * 180f / kotlin.math.PI.toFloat(), pt.x, pt.y)
                nc.drawText(text, pt.x, pt.y + fs * 0.3f, p)
                nc.restore()
            } else {
                nc.drawText(text, pt.x, pt.y + fs * 0.3f, p)
            }
        }
        is DrawingPrimitive.RangeLabelPrimitive -> {
            val pt = transform(primitive.x, primitive.y)
            val avgScale = sqrt(abs(edit.scaleX * edit.scaleY))
            val isUniform = abs(edit.scaleX - edit.scaleY) < 0.01f
            val lineScale = strokeScale
            // 字号上限与 applyTransform 一致，确保预览和确认后一致
            val effectiveFontSize = if (isUniform) (primitive.fontSize * avgScale).coerceIn(20f, 600f) else primitive.fontSize
            val fs = effectiveFontSize * lineScale * canvasScale
            val totalRotation = edit.rotation + primitive.rotation
            val p = Paint().apply {
                color = primitive.color.copy(alpha = alpha).hashCode()
                textSize = fs
                isAntiAlias = true; textAlign = Paint.Align.CENTER
            }
            val nc = drawContext.canvas.nativeCanvas
            val label1 = primitive.startValue.toString()
            val label2 = primitive.endValue.toString()
            val gap = fs * 1.0f
            // 箭头杆长与字号保持比例（与 applyTransform 一致）
            val effectiveArrowSpan = primitive.arrowSpan * (effectiveFontSize / primitive.fontSize)
            val arrowLen = maxOf((effectiveArrowSpan * 80f * lineScale * canvasScale), 20f)

            if (kotlin.math.abs(totalRotation) < 0.001f) {
                drawPreviewLabelContent(nc, p, label1, label2, pt.x, pt.y, fs, arrowLen, gap, primitive, alpha, canvasScale, lineScale)
            } else {
                nc.save()
                nc.rotate(totalRotation * 180f / kotlin.math.PI.toFloat(), pt.x, pt.y)
                drawPreviewLabelContent(nc, p, label1, label2, pt.x, pt.y, fs, arrowLen, gap, primitive, alpha, canvasScale, lineScale)
                nc.restore()
            }
        }
        is DrawingPrimitive.BlockRefPrimitive -> {
            val bd = blockDefs.find { it.id == primitive.blockDefId }
            if (bd != null && bd.primitives.isNotEmpty()) {
                // Compute centroid of block content
                var cx = 0f; var cy = 0f; var count = 0
                for (p in bd.primitives) {
                    val b = when (p) {
                        is DrawingPrimitive.FreehandPath -> {
                            if (p.points.size < 2) null
                            else { val xs = p.points.map { it.x }; val ys = p.points.map { it.y }
                                floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max()) }
                        }
                        is DrawingPrimitive.RectanglePrimitive -> floatArrayOf(
                            minOf(p.startX, p.endX), minOf(p.startY, p.endY),
                            maxOf(p.startX, p.endX), maxOf(p.startY, p.endY))
                        is DrawingPrimitive.CirclePrimitive -> {
                            val r = maxOf(abs(p.endX - p.centerX), abs(p.endY - p.centerY))
                            floatArrayOf(p.centerX - r, p.centerY - r, p.centerX + r, p.centerY + r)
                        }
                        is DrawingPrimitive.LinePrimitive -> {
                            floatArrayOf(minOf(p.startX, p.endX), minOf(p.startY, p.endY),
                                maxOf(p.startX, p.endX), maxOf(p.startY, p.endY))
                        }
                        is DrawingPrimitive.NumberLabelPrimitive -> floatArrayOf(p.x - 30f, p.y - 15f, p.x + 30f, p.y + 15f)
                        is DrawingPrimitive.TextPrimitive -> floatArrayOf(p.x - 40f, p.y - 20f, p.x + 40f, p.y + 20f)
                        else -> null
                    }
                    if (b != null) { cx += (b[0] + b[2]) / 2f; cy += (b[1] + b[3]) / 2f; count++ }
                }
                val centroidX = if (count > 0) cx / count else 0f
                val centroidY = if (count > 0) cy / count else 0f

                val pt = transform(primitive.x, primitive.y)
                val totalRotation = edit.rotation + primitive.rotation
                val avgScale = sqrt(abs(edit.scaleX * edit.scaleY))
                val s = primitive.scale * avgScale
                drawContext.canvas.save()
                drawContext.canvas.translate(pt.x - centroidX * s * canvasScale, pt.y - centroidY * s * canvasScale)
                drawContext.canvas.rotate(totalRotation * 180f / kotlin.math.PI.toFloat())
                drawContext.canvas.scale(s, s)
                for (prim in bd.primitives) {
                    drawPrimitiveAt(prim, PendingEdit(), canvasScale, 0f, 0f, strokeScale, emptyList())
                }
                drawContext.canvas.restore()
            } else {
                // Fallback placeholder
                val pt = transform(primitive.x, primitive.y)
                val totalRotation = edit.rotation + primitive.rotation
                val avgScale = sqrt(abs(edit.scaleX * edit.scaleY))
                val s = primitive.scale * avgScale
                val halfSize = 30f * s * canvasScale
                val color = primitive.color.copy(alpha = alpha)
                val cosR = cos(totalRotation); val sinR = sin(totalRotation)
                fun rotBox(lx: Float, ly: Float): Offset {
                    val rx = lx * cosR - ly * sinR; val ry = lx * sinR + ly * cosR
                    return Offset(pt.x + rx, pt.y + ry)
                }
                val boxCorners = listOf(
                    rotBox(-halfSize, -halfSize), rotBox(halfSize, -halfSize),
                    rotBox(halfSize, halfSize), rotBox(-halfSize, halfSize)
                )
                val boxPath = Path().apply {
                    moveTo(boxCorners[0].x, boxCorners[0].y)
                    for (i in 1..3) lineTo(boxCorners[i].x, boxCorners[i].y)
                    close()
                }
                drawPath(boxPath, color, style = Stroke(primitive.strokeWidth * canvasScale * strokeScale))
                drawLine(color, boxCorners[0], boxCorners[2], strokeWidth = 2f * canvasScale)
                drawLine(color, boxCorners[1], boxCorners[3], strokeWidth = 2f * canvasScale)
            }
        }
        else -> {}
    }
}

/** Preview-specific range label content drawing */
private fun DrawScope.drawPreviewLabelContent(
    nc: android.graphics.Canvas, paint: Paint,
    label1: String, label2: String,
    cx: Float, cy: Float, fs: Float,
    arrowLen: Float, gap: Float,
    primitive: DrawingPrimitive.RangeLabelPrimitive,
    alpha: Float, canvasScale: Float,
    lineScale: Float
) {
    val leftX = cx - arrowLen / 2f - gap
    val rightX = cx + arrowLen / 2f + gap
    val textCenterY = cy + fs * 0.3f
    if (primitive.reversed) {
        nc.drawText(label2, leftX, textCenterY, paint)
        nc.drawText(label1, rightX, textCenterY, paint)
    } else {
        nc.drawText(label1, leftX, textCenterY, paint)
        nc.drawText(label2, rightX, textCenterY, paint)
    }
    val arrowY = cy
    val ap = Paint().apply { color = primitive.color.copy(alpha = alpha).hashCode(); strokeWidth = 2f * lineScale * canvasScale; isAntiAlias = true }
    val arrowStartX = cx - arrowLen / 2f; val arrowEndX = cx + arrowLen / 2f
    nc.drawLine(arrowStartX, arrowY, arrowEndX, arrowY, ap)
    val headSize = maxOf(4f, primitive.fontSize * 0.3f) * lineScale * canvasScale
    if (primitive.reversed) {
        nc.drawLine(arrowStartX, arrowY, arrowStartX + headSize, arrowY - headSize, ap)
        nc.drawLine(arrowStartX, arrowY, arrowStartX + headSize, arrowY + headSize, ap)
    } else {
        nc.drawLine(arrowEndX, arrowY, arrowEndX - headSize, arrowY - headSize, ap)
        nc.drawLine(arrowEndX, arrowY, arrowEndX - headSize, arrowY + headSize, ap)
    }
}
