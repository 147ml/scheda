package com.scheda.app.ui.canvas

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scheda.app.model.SelectionAction
import com.scheda.app.model.SelectionState
import com.scheda.app.model.Bounds
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Selection overlay that renders the selection rectangle being dragged, a bounding box
 * with resize/rotate handles when items are selected, and a floating action bar.
 *
 * The selection rectangle appears as a semi-transparent blue fill with a dashed blue
 * border while the user is dragging (isActive = true).
 *
 * When one or more primitives are selected (selectedIndices is non-empty), a light blue
 * bounding box with 4 corner handles (⤢↻↺⤡) is shown. The user can drag the body to move
 * selected items, or drag handles to scale/rotate them.
 *
 * @param selection        The current selection state (from the ViewModel).
 * @param canvasScale      Current canvas zoom factor for world-to-screen conversion.
 * @param canvasOffsetX    Current canvas horizontal offset in screen pixels.
 * @param canvasOffsetY    Current canvas vertical offset in screen pixels.
 * @param onExecuteAction  Called when the user taps an action button (COPY, DELETE, etc.).
 * @param onClearSelection Called when the user taps outside or otherwise clears selection.
 * @param onMoveSelected   Called with (dx, dy) in world coords when user drags the body.
 * @param onRotateSelected Called with rotation delta when user drags rotate handles.
 * @param onScaleSelected  Called with (sx, sy) scale factors when user drags scale handles.
 * @param modifier         Modifier applied to the root Box.
 */
@Composable
fun SelectionOverlay(
    selection: SelectionState,
    canvasScale: Float,
    canvasOffsetX: Float,
    canvasOffsetY: Float,
    onExecuteAction: (SelectionAction) -> Unit,
    onClearSelection: () -> Unit,
    onMoveSelected: (Float, Float) -> Unit = { _, _ -> },
    onRotateSelected: (Float) -> Unit = {},
    onScaleSelected: (Float, Float) -> Unit = { _, _ -> },
    onTransformEnd: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val handleSize = 40.dp
    val handleRadiusPx = with(density) { handleSize.toPx() / 2f }

    Box(modifier = modifier.fillMaxSize()) {

        // ── Layer 1: Selection rectangle (drawn while dragging) ────────────
        if (selection.isActive) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val selectionColor = Color(0x334B9CD3)   // semi-transparent blue fill
                val borderColor = Color(0xFF4B9CD3)       // solid blue border

                // Convert world coords to screen coords
                val screenStartX = selection.selStartX * canvasScale + canvasOffsetX
                val screenStartY = selection.selStartY * canvasScale + canvasOffsetY
                val screenEndX = selection.selEndX * canvasScale + canvasOffsetX
                val screenEndY = selection.selEndY * canvasScale + canvasOffsetY

                val left = minOf(screenStartX, screenEndX)
                val top = minOf(screenStartY, screenEndY)
                val right = maxOf(screenStartX, screenEndX)
                val bottom = maxOf(screenStartY, screenEndY)

                // Fill
                drawRect(
                    color = selectionColor,
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top)
                )

                // Dashed border
                drawRect(
                    color = borderColor,
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                    style = Stroke(
                        width = 2f,
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(10f, 6f),
                            phase = 0f
                        )
                    )
                )
            }
        }

        // ── Layer 3: Floating action bar (when items are selected) ─────────
        if (selection.selectedIndices.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp, start = 8.dp, end = 8.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val capsuleShape = RoundedCornerShape(50)
                val bgBtn = ButtonDefaults.buttonColors(
                    containerColor = Color(0xE6000000),
                    contentColor = Color.White
                )
                // Mirror
                Button(onClick = { onExecuteAction(SelectionAction.MIRROR) },
                    colors = bgBtn,
                    shape = capsuleShape, modifier = Modifier.weight(1f).height(30.dp)) {
                    Text("⇔", fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text("镜像", fontSize = 10.sp)
                }
                // Copy
                Button(onClick = { onExecuteAction(SelectionAction.COPY) },
                    colors = bgBtn,
                    shape = capsuleShape, modifier = Modifier.weight(1f).height(30.dp)) {
                    Icon(Icons.Default.ContentCopy, "Copy", modifier = Modifier.size(14.dp))
                    Text("复制", fontSize = 10.sp)
                }
                // Delete
                Button(onClick = { onExecuteAction(SelectionAction.DELETE) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0x33C62828),
                        contentColor = Color(0xFFFF6B6B)
                    ),
                    shape = capsuleShape, modifier = Modifier.weight(1f).height(30.dp)) {
                    Icon(Icons.Default.Delete, "Del", modifier = Modifier.size(14.dp))
                    Text("删除", fontSize = 10.sp)
                }
                // Properties
                Button(onClick = { onExecuteAction(SelectionAction.PROPERTIES) },
                    colors = bgBtn,
                    shape = capsuleShape, modifier = Modifier.weight(1f).height(30.dp)) {
                    Icon(Icons.Outlined.Settings, "Props", modifier = Modifier.size(14.dp))
                    Text("属性", fontSize = 10.sp)
                }
            }
        }

        // ── Layer 5: Bounding box with handles ──
        val showHandles = selection.selectedIndices.isNotEmpty() && (selection.bounds != null || selection.isTransforming)
        if (showHandles) {
            val bbox = selection.bounds ?: selection.initialBounds ?: Bounds(0f, 0f, 100f, 100f)
            val paddingPx = with(density) { 30.dp.toPx() }

            val wcx = (bbox.minX + bbox.maxX) / 2f
            val wcy = (bbox.minY + bbox.maxY) / 2f
            val halfW = (bbox.maxX - bbox.minX) / 2f + paddingPx / canvasScale
            val halfH = (bbox.maxY - bbox.minY) / 2f + paddingPx / canvasScale

            fun worldToScreen(wx: Float, wy: Float): Offset =
                Offset(wx * canvasScale + canvasOffsetX, wy * canvasScale + canvasOffsetY)

            val worldCorners = listOf(
                Offset(wcx - halfW, wcy - halfH), Offset(wcx + halfW, wcy - halfH),
                Offset(wcx - halfW, wcy + halfH), Offset(wcx + halfW, wcy + halfH),
            )
            val rot = selection.rotation
            val cornersW = if (rot != 0f) {
                val cosR = cos(rot); val sinR = sin(rot)
                worldCorners.map { wpt ->
                    val dx = wpt.x - wcx; val dy = wpt.y - wcy
                    Offset(wcx + dx * cosR - dy * sinR, wcy + dx * sinR + dy * cosR)
                }
            } else worldCorners
            val corners = cornersW.map { worldToScreen(it.x, it.y) }
            val centerScreen = worldToScreen(wcx, wcy)

            val transformEndState = rememberUpdatedState(onTransformEnd)
            // Padded corners for handle positioning
            val handlePadPx = with(density) { 20.dp.toPx() }
            val paddedCorners = corners.map { c ->
                val dx = c.x - centerScreen.x; val dy = c.y - centerScreen.y
                val dist = sqrt(dx * dx + dy * dy)
                if (dist > 0.01f) Offset(c.x + (dx / dist) * handlePadPx, c.y + (dy / dist) * handlePadPx) else c
            }
            // Midpoints on padded frame edges
            val allMidpoints = listOf(
                Offset((paddedCorners[0].x + paddedCorners[1].x) / 2f, (paddedCorners[0].y + paddedCorners[1].y) / 2f),
                Offset((paddedCorners[2].x + paddedCorners[3].x) / 2f, (paddedCorners[2].y + paddedCorners[3].y) / 2f),
                Offset((paddedCorners[0].x + paddedCorners[2].x) / 2f, (paddedCorners[0].y + paddedCorners[2].y) / 2f),
                Offset((paddedCorners[1].x + paddedCorners[3].x) / 2f, (paddedCorners[1].y + paddedCorners[3].y) / 2f),
            )
            val finalMidpoints = allMidpoints

            Canvas(modifier = Modifier.fillMaxSize()) {
                // Blue frame on paddedCorners (outer), matching PostCreationOverlay style
                val framePath = Path().apply {
                    moveTo(paddedCorners[0].x, paddedCorners[0].y); lineTo(paddedCorners[1].x, paddedCorners[1].y)
                    lineTo(paddedCorners[3].x, paddedCorners[3].y); lineTo(paddedCorners[2].x, paddedCorners[2].y); close()
                }
                drawPath(framePath, Color(0x1A4B9CD3))
                drawPath(framePath, Color(0xFF4B9CD3), style = Stroke(width = 2f))
                val colors = listOf(Color(0xFF34A853), Color(0xFFFF9800), Color(0xFFFF9800), Color(0xFF34A853))
                val labels = listOf("", "↻", "↺", "")
                val rotDeg = rot * 180f / kotlin.math.PI.toFloat()
                for ((i, c) in corners.withIndex()) {
                    drawCircle(colors[i].copy(alpha = 0.2f), handleRadiusPx, c)
                    drawCircle(colors[i], handleRadiusPx, c, style = Stroke(width = 2.5f))
                    if (labels[i].isNotEmpty()) {
                        val p = Paint().apply { this.color = colors[i].hashCode(); textSize = handleRadiusPx * 1.2f; textAlign = Paint.Align.CENTER; isAntiAlias = true }
                        drawContext.canvas.nativeCanvas.drawText(labels[i], c.x, c.y + handleRadiusPx * 0.35f, p)
                    } else {
                        // Scale corners: two smaller outward-pointing arrows rotating with the frame
                        val nc = drawContext.canvas.nativeCanvas
                        nc.save()
                        nc.translate(c.x, c.y)
                        nc.rotate(rotDeg)
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
                // Midpoint resize handles (white pills with blue border)
                val midHw = with(density) { 13.dp.toPx() }
                val midHh = with(density) { 7.dp.toPx() }
                val midR = with(density) { 4.dp.toPx() }
                val midColor = Color(0xFF4B9CD3)
                for ((mi, m) in finalMidpoints.withIndex()) {
                    val isHoriz = mi < 2
                    val hw = if (isHoriz) midHw else midHh
                    val hh = if (isHoriz) midHh else midHw
                    val tl = Offset(m.x - hw, m.y - hh)
                    val sz = Size(hw * 2, hh * 2)
                    drawRoundRect(Color.White, tl, sz, CornerRadius(midR))
                    drawRoundRect(midColor, tl, sz, CornerRadius(midR), style = Stroke(width = 2f))
                }
            }

            for ((i, corner) in corners.withIndex()) {
                val cornerState = rememberUpdatedState(corner)
                val centerState = rememberUpdatedState(centerScreen)
                val scaleState = rememberUpdatedState(canvasScale)
                Box(modifier = Modifier
                    .offset { IntOffset((corner.x - handleRadiusPx).toInt(), (corner.y - handleRadiusPx).toInt()) }
                    .size(handleSize).clip(CircleShape)
                    .pointerInput(Unit) {
                        val cb = transformEndState
                        detectDragGestures(
                            onDrag = { change, dragAmount -> change.consume()
                                val c = cornerState.value; val cs = centerState.value; val sc = scaleState.value
                                val hx = c.x - cs.x; val hy = c.y - cs.y; val dsq = hx * hx + hy * hy
                                when (i) {
                                    0 -> { if (dsq > 1f) { val r = (hx * dragAmount.x + hy * dragAmount.y) / dsq; onScaleSelected(1f + r, 1f + r) } }
                                    1 -> { if (dsq > 1f) onRotateSelected((hx * dragAmount.y - hy * dragAmount.x) / dsq) }
                                    2 -> { if (dsq > 1f) onRotateSelected((hx * dragAmount.y - hy * dragAmount.x) / dsq) }
                                    3 -> { if (dsq > 1f) { val r = (hx * dragAmount.x + hy * dragAmount.y) / dsq; onScaleSelected(1f + r, 1f + r) } }
                                }
                            },
                            onDragEnd = { cb.value() }
                        )
                    })
            }

            // Layer 2b: Midpoint touch handles (directional resize)
            for ((mi, m) in finalMidpoints.withIndex()) {
                val midPosState = rememberUpdatedState(m)
                val midCenterState = rememberUpdatedState(centerScreen)
                Box(
                    modifier = Modifier
                        .offset { IntOffset((m.x - handleRadiusPx).toInt(), (m.y - handleRadiusPx).toInt()) }
                        .size(handleSize).clip(CircleShape)
                        .pointerInput(Unit) {
                            val cb = transformEndState
                            val mpState = midPosState; val csState = midCenterState
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val mp = mpState.value; val cs = csState.value
                                    val hx = mp.x - cs.x; val hy = mp.y - cs.y
                                    val dsq = hx * hx + hy * hy
                                    if (dsq > 1f) {
                                        val r = (hx * dragAmount.x + hy * dragAmount.y) / dsq
                                        when (mi) {
                                            0, 1 -> onScaleSelected(1f, 1f + r)     // scale Y only
                                            2, 3 -> onScaleSelected(1f + r, 1f)     // scale X only
                                        }
                                    }
                                },
                                onDragEnd = { cb.value() }
                            )
                        }
                )
            }

            val bx = corners.minOf { it.x }; val by = corners.minOf { it.y }
            val bw = corners.maxOf { it.x } - bx; val bh = corners.maxOf { it.y } - by
            if (bw > 20f && bh > 20f) {
                val scaleState2 = rememberUpdatedState(canvasScale)
                Box(Modifier.offset { IntOffset(bx.toInt(), by.toInt()) }
                    .size(with(density) { bw.toDp() }, with(density) { bh.toDp() })
                    .pointerInput(Unit) {
                        val cb = transformEndState
                        detectDragGestures(
                            onDrag = { ch, da -> ch.consume(); onMoveSelected(da.x / scaleState2.value, da.y / scaleState2.value) },
                            onDragEnd = { cb.value() }
                        )
                    })
            }
        }

        // ── end of Box ──
    }
}
