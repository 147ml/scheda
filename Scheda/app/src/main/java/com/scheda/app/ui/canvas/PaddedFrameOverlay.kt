package com.scheda.app.ui.canvas

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.scheda.app.model.Bounds
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun PaddedFrameOverlay(
    bounds: Bounds,
    frameRotation: Float,
    scaleX: Float,
    scaleY: Float,
    offsetX: Float,
    offsetY: Float,
    canvasScale: Float,
    canvasOffsetX: Float,
    canvasOffsetY: Float,
    isMidpointRange: Boolean = false,
    hideMidpoints: Boolean = false,
    customHalfW: Float? = null,
    customHalfH: Float? = null,
    onBodyDrag: (Float, Float) -> Unit = { _, _ -> },
    onCornerScale: (Float) -> Unit = {},
    onCornerRotate: (Float) -> Unit = {},
    onMidpointScale: (Float, Float) -> Unit = { _, _ -> },
    onMidpointOffset: (Float, Float) -> Unit = { _, _ -> },
    onArrowSpan: (Float) -> Unit = {},
    onTransformEnd: () -> Unit = {},
    onHandleActiveChanged: (Boolean) -> Unit = {},
    midpointOverrides: List<Offset>? = null,
    swapMidpointAxes: Boolean = false,
    midpointScaleOnly: Boolean = false,
    onRectMidpointDrag: ((index: Int, r: Float) -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {}
) {
    val density = LocalDensity.current
    val handleSize = 40.dp
    val handleRadiusPx = with(density) { handleSize.toPx() / 2f }
    val minHalf = with(density) { 30.dp.toPx() }
    val paddingPx = with(density) { 20.dp.toPx() }
    val csState = rememberUpdatedState(canvasScale)
    // onRectMidpointDrag 是 PostCreationOverlay 传入的条件 lambda（if (is Rectangle) { ... } else null），
    // 它闭包中捕获了 pendingEdit / canvasScale / density 等 Compose 状态。
    // pointerInput(Unit) 的 key=Unit 稳定，拖拽期间手势处理器永不重建→总用第一次传入的旧 lambda。
    // 用 rememberUpdatedState 包装确保每次读的都是最新 lambda。
    // 其他回调（onCornerRotate / onMidpointScale 等）传的是函数引用（viewModel::fn），
    // 不捕获组合状态，不需要此包装。
    val rectDragState = rememberUpdatedState(onRectMidpointDrag)

    val cx = (bounds.minX + bounds.maxX) / 2f + offsetX
    val cy = (bounds.minY + bounds.maxY) / 2f + offsetY
    val halfW = customHalfW ?: maxOf(((bounds.maxX - bounds.minX) / 2f) * scaleX, minHalf / canvasScale)
    val halfH = customHalfH ?: maxOf(((bounds.maxY - bounds.minY) / 2f) * scaleY, minHalf / canvasScale)

    val cosR = cos(frameRotation); val sinR = sin(frameRotation)

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

    val paddedCorners = corners.map { c ->
        val dx = c.x - centerScreen.x; val dy = c.y - centerScreen.y
        val dist = sqrt(dx * dx + dy * dy)
        if (dist > 0.01f) Offset(c.x + (dx / dist) * paddingPx, c.y + (dy / dist) * paddingPx) else c
    }

    val allMidpoints = if (hideMidpoints) emptyList()
        else if (midpointOverrides != null) midpointOverrides
        else if (isMidpointRange) listOf(
            Offset((paddedCorners[0].x + paddedCorners[2].x) / 2f, (paddedCorners[0].y + paddedCorners[2].y) / 2f),
            Offset((paddedCorners[1].x + paddedCorners[3].x) / 2f, (paddedCorners[1].y + paddedCorners[3].y) / 2f),
        )
        else listOf(
            Offset((paddedCorners[0].x + paddedCorners[1].x) / 2f, (paddedCorners[0].y + paddedCorners[1].y) / 2f),
            Offset((paddedCorners[2].x + paddedCorners[3].x) / 2f, (paddedCorners[2].y + paddedCorners[3].y) / 2f),
            Offset((paddedCorners[0].x + paddedCorners[2].x) / 2f, (paddedCorners[0].y + paddedCorners[2].y) / 2f),
            Offset((paddedCorners[1].x + paddedCorners[3].x) / 2f, (paddedCorners[1].y + paddedCorners[3].y) / 2f),
        )

    Box(modifier = modifier.fillMaxSize()) {
        content()

        // Layer 1: Canvas — blue frame + corner circles + midpoint pills
        Canvas(modifier = Modifier.fillMaxSize()) {
            val framePath = Path().apply {
                moveTo(paddedCorners[0].x, paddedCorners[0].y)
                lineTo(paddedCorners[1].x, paddedCorners[1].y)
                lineTo(paddedCorners[3].x, paddedCorners[3].y)
                lineTo(paddedCorners[2].x, paddedCorners[2].y)
                close()
            }
            drawPath(framePath, Color(0x1A4B9CD3))
            drawPath(framePath, Color(0xFF4B9CD3), style = Stroke(width = 2f))

            val cornerColors = listOf(Color(0xFF34A853), Color(0xFFFF9800), Color(0xFFFF9800), Color(0xFF34A853))
            val cornerLabels = listOf("", "\u21BB", "\u21BA", "")
            val arrowRotDeg = frameRotation * 180f / kotlin.math.PI.toFloat()
            for ((i, c) in paddedCorners.withIndex()) {
                drawCircle(cornerColors[i].copy(alpha = 0.2f), handleRadiusPx, c)
                drawCircle(cornerColors[i], handleRadiusPx, c, style = Stroke(width = 2.5f))
                if (cornerLabels[i].isNotEmpty()) {
                    val p = Paint().apply {
                        this.color = cornerColors[i].hashCode()
                        textSize = handleRadiusPx * 1.2f; textAlign = Paint.Align.CENTER; isAntiAlias = true
                    }
                    drawContext.canvas.nativeCanvas.drawText(cornerLabels[i], c.x, c.y + handleRadiusPx * 0.35f, p)
                } else {
                    val nc = drawContext.canvas.nativeCanvas
                    nc.save(); nc.translate(c.x, c.y); nc.rotate(arrowRotDeg)
                    val r = handleRadiusPx * 0.32f; val strokeW = 2.5f
                    val headLen = handleRadiusPx * 0.16f; val headAngle = 30f * kotlin.math.PI.toFloat() / 180f
                    val isTopLeft = i == 0
                    for ((start, end, _) in if (isTopLeft) listOf(
                        Triple(Offset(0f, r), Offset(0f, -r), 0f),
                        Triple(Offset(r, 0f), Offset(-r, 0f), 0f)
                    ) else listOf(
                        Triple(Offset(0f, -r), Offset(0f, r), 0f),
                        Triple(Offset(-r, 0f), Offset(r, 0f), 0f)
                    )) {
                        drawLine(cornerColors[i], start, end, strokeW, StrokeCap.Round)
                    }
                    nc.restore()
                }
            }

            val midHw = with(density) { 13.dp.toPx() }; val midHh = with(density) { 7.dp.toPx() }
            val midR = with(density) { 4.dp.toPx() }; val midColor = Color(0xFF4B9CD3)
            val midRotDeg = frameRotation * 180f / kotlin.math.PI.toFloat()
            for ((i, m) in allMidpoints.withIndex()) {
                val isHoriz = if (isMidpointRange) false else if (swapMidpointAxes) i >= 2 else i < 2
                val hw = if (isHoriz) midHw else midHh
                val hh = if (isHoriz) midHh else midHw
                if (kotlin.math.abs(midRotDeg) > 1f) {
                    val nc = drawContext.canvas.nativeCanvas
                    nc.save(); nc.translate(m.x, m.y); nc.rotate(midRotDeg)
                    drawRoundRect(Color.White, Offset(-hw, -hh), Size(hw * 2, hh * 2), CornerRadius(midR))
                    drawRoundRect(midColor, Offset(-hw, -hh), Size(hw * 2, hh * 2), CornerRadius(midR), style = Stroke(width = 2f))
                    nc.restore()
                } else {
                    drawRoundRect(Color.White, Offset(m.x - hw, m.y - hh), Size(hw * 2, hh * 2), CornerRadius(midR))
                    drawRoundRect(midColor, Offset(m.x - hw, m.y - hh), Size(hw * 2, hh * 2), CornerRadius(midR), style = Stroke(width = 2f))
                }
            }
        }

        // Layer 2: Body drag
        val bx = paddedCorners.minOf { it.x }; val by = paddedCorners.minOf { it.y }
        val bw = paddedCorners.maxOf { it.x } - bx; val bh = paddedCorners.maxOf { it.y } - by
        // Clamp to sane range: skip body drag when zoomed so far out that coordinates would overflow IntOffset
        val saneBx = bx.coerceIn(-50000f, 50000f)
        val saneBy = by.coerceIn(-50000f, 50000f)
        val saneBw = bw.coerceIn(0f, 100000f)
        val saneBh = bh.coerceIn(0f, 100000f)

        // 回调包装：pointerInput key=Unit 拖拽期间不重建，必须用 rememberUpdatedState 读最新值
        val onCornerRotateState = rememberUpdatedState(onCornerRotate)
        val onCornerScaleState = rememberUpdatedState(onCornerScale)
        val onTransformEndState = rememberUpdatedState(onTransformEnd)
        val onBodyDragState = rememberUpdatedState(onBodyDrag)
        if (saneBw > 20f && saneBh > 20f) {
            val scaleState = rememberUpdatedState(canvasScale)

            var bodyActive by remember { mutableStateOf(false) }
            Box(Modifier.offset { IntOffset(saneBx.toInt(), saneBy.toInt()) }
                .size(with(density) { saneBw.toDp() }, with(density) { saneBh.toDp() })
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { bodyActive = true; onHandleActiveChanged(true) },
                        onDragEnd = { bodyActive = false; onHandleActiveChanged(false); onTransformEndState.value() },
                        onDragCancel = { bodyActive = false; onHandleActiveChanged(false); onTransformEndState.value() },
                        onDrag = { ch, da ->
                            ch.consume()
                            val sc = scaleState.value
                            onBodyDragState.value(da.x / sc, da.y / sc)
                        }
                    )
                })
        }


        // Layer 3: Corner handles
        for ((i, corner) in paddedCorners.withIndex()) {
            val cornerState = rememberUpdatedState(corner)
            val centerState = rememberUpdatedState(centerScreen)
            val rotationState = rememberUpdatedState(frameRotation)
            val scaleState2 = rememberUpdatedState(canvasScale)
            var cornerActive by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .offset { IntOffset((corner.x - handleRadiusPx).toInt(), (corner.y - handleRadiusPx).toInt()) }
                    .size(handleSize).clip(CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { cornerActive = true; onHandleActiveChanged(true) },
                            onDragEnd = { cornerActive = false; onHandleActiveChanged(false); onTransformEndState.value() },
                            onDragCancel = { cornerActive = false; onHandleActiveChanged(false); onTransformEndState.value() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val c = cornerState.value; val cs = centerState.value
                                val hx = c.x - cs.x; val hy = c.y - cs.y; val dsq = hx * hx + hy * hy
                                when (i) {
                                    0 -> { if (dsq > 1f) { val r = (hx * dragAmount.x + hy * dragAmount.y) / dsq; onCornerScaleState.value(1f + r) } }
                                    1 -> { if (dsq > 1f) { val r = (hx * dragAmount.y - hy * dragAmount.x) / dsq * 0.5f; onCornerRotateState.value(r) } }
                                    2 -> { if (dsq > 1f) { val r = (hx * dragAmount.y - hy * dragAmount.x) / dsq * 0.5f; onCornerRotateState.value(r) } }
                                    3 -> { if (dsq > 1f) { val r = (hx * dragAmount.x + hy * dragAmount.y) / dsq; onCornerScaleState.value(1f + r) } }
                                }
                            }
                        )
                    }
            )
        }

        // Layer 4: Midpoint handles
        for ((i, m) in allMidpoints.withIndex()) {
            val midPosState = rememberUpdatedState(m)
            val midCenterState = rememberUpdatedState(centerScreen)
            val midRangeState = rememberUpdatedState(isMidpointRange)
            var midActive by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .offset { IntOffset((midPosState.value.x - handleRadiusPx).toInt(), (midPosState.value.y - handleRadiusPx).toInt()) }
                    .size(handleSize).clip(CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { midActive = true; onHandleActiveChanged(true) },
                            onDragEnd = { midActive = false; onHandleActiveChanged(false); onTransformEnd() },
                            onDragCancel = { midActive = false; onHandleActiveChanged(false); onTransformEnd() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val mp = midPosState.value; val cs = midCenterState.value
                                val hx = mp.x - cs.x; val hy = mp.y - cs.y
                                val dsq = hx * hx + hy * hy
                                if (dsq > 1f) {
                                    val r = (hx * dragAmount.x + hy * dragAmount.y) / dsq
                                    val isRange = midRangeState.value
                                    if (isRange) {
                                        onArrowSpan(1f + r)
                                    } else {
                                        val sc = csState.value
                                        val halfActual = sqrt(dsq) - paddingPx
                                        val rectDrag = rectDragState.value
                                        if (rectDrag != null) {
                                            // 传世界坐标位移量：手指在边法向上的投影 r*|h|/cs，
                                            // 已含 padding 修正，边与手指 1:1 跟随
                                            rectDrag(i, r * sqrt(dsq) / sc)
                                        } else {
                                            // Offset along the rotated frame direction (hx, hy), not world axes
                                            val nd = kotlin.math.sqrt(dsq.toDouble()).toFloat()
                                            val nx = hx / nd; val ny = hy / nd
                                            val amt = halfActual * r / sc
                                            when (i) {
                                                0 -> { onMidpointScale(1f, 1f + r); if (!midpointScaleOnly) onMidpointOffset(amt * nx, amt * ny) }
                                                1 -> { onMidpointScale(1f, 1f + r); if (!midpointScaleOnly) onMidpointOffset(amt * nx, amt * ny) }
                                                2 -> { onMidpointScale(1f + r, 1f); if (!midpointScaleOnly) onMidpointOffset(amt * nx, amt * ny) }
                                                3 -> { onMidpointScale(1f + r, 1f); if (!midpointScaleOnly) onMidpointOffset(amt * nx, amt * ny) }
                                            }
                                            if (swapMidpointAxes) {
                                                when (i) {
                                                    0 -> { onMidpointScale(1f + r, 1f); if (!midpointScaleOnly) onMidpointOffset(amt * nx, amt * ny) }
                                                    1 -> { onMidpointScale(1f + r, 1f); if (!midpointScaleOnly) onMidpointOffset(-amt * nx, -amt * ny) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    }
            )
        }
    }
}
