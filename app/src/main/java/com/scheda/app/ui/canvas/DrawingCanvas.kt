package com.scheda.app.ui.canvas

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.scheda.app.model.DrawingPrimitive
import com.scheda.app.model.BlockDef
import com.scheda.app.model.Layer
import com.scheda.app.model.LineStyle
import com.scheda.app.model.LineType
import com.scheda.app.model.PendingEdit
import com.scheda.app.model.Point2D
import com.scheda.app.model.ToolType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Full drawing canvas for the hand-drawn CAD app.
 *
 * Supports all primitive types, three line styles (SOLID / DASHED / LIGHTNING),
 * two-point circle (ellipse) rendering, number labels via native canvas,
 * block-ref placeholders, and post-creation editing overlay.
 */
@Composable
fun DrawingCanvas(
    primitives: List<DrawingPrimitive>,
    currentPrimitive: DrawingPrimitive?,
    layers: List<Layer>,
    canvasScale: Float,
    canvasOffsetX: Float,
    canvasOffsetY: Float,
    pendingEdit: PendingEdit,
    currentTool: ToolType,
    currentLineStyle: LineStyle,
    selectedIndices: Set<Int> = emptySet(),
    globalLineScale: Float = 1f,
    blockDefs: List<BlockDef> = emptyList(),
    eraserRadius: Float = 30f,
    eraserTouchPoint: Point2D? = null,
    quickEraseEnabled: Boolean = false,
    onLongPressEraser: () -> Unit = {},
    onTouchStart: (Point2D) -> Unit,
    onTouchMove: (Point2D) -> Unit,
    onTouchEnd: () -> Unit,
    onTouchCancel: () -> Unit,
    onCanvasTransform: (zoom: Float, centroid: Offset, pan: Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    val scaleState = rememberUpdatedState(canvasScale)
    val offXState = rememberUpdatedState(canvasOffsetX)
    val offYState = rememberUpdatedState(canvasOffsetY)
    val touchStartState = rememberUpdatedState(onTouchStart)
    val touchMoveState = rememberUpdatedState(onTouchMove)
    val touchEndState = rememberUpdatedState(onTouchEnd)
    val touchCancelState = rememberUpdatedState(onTouchCancel)
    val transformState = rememberUpdatedState(onCanvasTransform)
    val quickEraseState = rememberUpdatedState(quickEraseEnabled)
    val currentToolState = rememberUpdatedState(currentTool)
    val longPressEraserState = rememberUpdatedState(onLongPressEraser)

    Box(modifier = modifier.fillMaxSize()) {
        // ── Layer 1: Drawing canvas ────────────────────────────
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .background(Color(0xFFF0F0F0))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        var isDrawing = false
                        var hadMultiTouch = false
                        var longPressDownTime = 0L
                        var longPressDownPos = Offset.Zero
                        var longPressTriggered = false

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val pressed = event.changes.filter { ch -> ch.pressed }
                            val pointerCount = pressed.size
                            val s = scaleState.value
                            val ox = offXState.value
                            val oy = offYState.value

                            // ── Long-press detection for temporary eraser ────
                            val qe = quickEraseState.value
                            val ct = currentToolState.value
                            if (pointerCount == 1 && !hadMultiTouch && qe && ct != ToolType.ERASER) {
                                val pos = pressed[0].position
                                val now = System.currentTimeMillis()
                                if (!longPressTriggered) {
                                    val dist = (pos - longPressDownPos).getDistance()
                                    if (dist > 5f) {
                                        // 手指移动了，重置长按计时
                                        longPressDownTime = now
                                        longPressDownPos = pos
                                    } else if (now - longPressDownTime > 700L) {
                                        // 连续 700ms 几乎静止，触发临时橡皮擦
                                        longPressEraserState.value()
                                        longPressTriggered = true
                                    }
                                }
                            }
                            if (pointerCount == 0) longPressTriggered = false

                            when {
                                // ── Multi-finger: zoom + pan
                                pointerCount >= 2 -> {
                                    if (isDrawing) {
                                        isDrawing = false
                                        touchCancelState.value()
                                    }
                                    hadMultiTouch = true

                                    val zoom = if (pressed.size >= 2) {
                                        val dx1 = pressed[0].position.x - pressed[1].position.x
                                        val dy1 = pressed[0].position.y - pressed[1].position.y
                                        val d1 = sqrt(dx1 * dx1 + dy1 * dy1)
                                        val dx2 = pressed[0].previousPosition.x - pressed[1].previousPosition.x
                                        val dy2 = pressed[0].previousPosition.y - pressed[1].previousPosition.y
                                        val d2 = sqrt(dx2 * dx2 + dy2 * dy2)
                                        if (d2 > 0f) d1 / d2 else 1f
                                    } else 1f

                                    val centroid = if (pressed.isNotEmpty()) {
                                        Offset(
                                            pressed.map { it.position.x }.average().toFloat(),
                                            pressed.map { it.position.y }.average().toFloat()
                                        )
                                    } else Offset.Zero

                                    val prevCentroid = if (pressed.isNotEmpty()) {
                                        Offset(
                                            pressed.map { it.previousPosition.x }.average().toFloat(),
                                            pressed.map { it.previousPosition.y }.average().toFloat()
                                        )
                                    } else Offset.Zero
                                    val pan = centroid - prevCentroid

                                    transformState.value(zoom, centroid, pan)
                                    pressed.forEach { ch -> ch.consume() }
                                }

                                // ── Single finger, never multi-touch: draw
                                pointerCount == 1 && !hadMultiTouch -> {
                                    val ch = pressed.first()
                                    val wx = (ch.position.x - ox) / s
                                    val wy = (ch.position.y - oy) / s

                                    if (!isDrawing) {
                                        isDrawing = true
                                        touchStartState.value(Point2D(wx, wy))
                                    } else {
                                        touchMoveState.value(Point2D(wx, wy))
                                    }
                                    ch.consume()
                                }

                                // ── All up: end
                                pointerCount == 0 -> {
                                    if (isDrawing) {
                                        isDrawing = false
                                        touchEndState.value()
                                    }
                                    hadMultiTouch = false
                                }

                                // ── Other (single after multi): ignore
                                else -> {
                                    pressed.forEach { ch -> ch.consume() }
                                }
                            }
                        }
                    }
                }
        ) {
            val visibleLayerIds = layers.filter { it.isVisible }.map { it.id }.toSet()

            withTransform({
                translate(canvasOffsetX, canvasOffsetY)
                scale(canvasScale, canvasScale, Offset.Zero)
            }) {
                // Adaptive grid
                val wl = -canvasOffsetX / canvasScale
                val wt = -canvasOffsetY / canvasScale
                val wr = wl + size.width / canvasScale
                val wb = wt + size.height / canvasScale
                drawGrid(canvasScale, wl, wt, wr, wb)

                // Committed primitives with selection highlight
                // Sort by layer order: bottom first (drawn first), top last (on top)
                val layerOrder = layers.withIndex().associate { it.value.id to it.index }
                val sortedByLayer = primitives.withIndex()
                    .sortedBy { layerOrder[it.value.layerId] ?: Int.MAX_VALUE }
                for ((origIdx, primitive) in sortedByLayer) {
                    if (primitive.layerId in visibleLayerIds) {
                        val compoundScale = globalLineScale * primitive.lineScaleFactor
                        if (origIdx in selectedIndices) {
                            val hl = primitive
                            val selColor = Color(0x604285F4)  // translucent blue
                            val minSelPx = 8f / canvasScale  // minimum 8 screen pixels
                            val selStroke = maxOf(hl.strokeWidth * 3f * compoundScale, minSelPx)
                            when (hl) {
                                is DrawingPrimitive.FreehandPath -> {
                                    val path = Path().apply {
                                        val pts = hl.points
                                        if (pts.isNotEmpty()) { moveTo(pts[0].x, pts[0].y)
                                            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y) }
                                        if (hl.isClosed) close()
                                    }
                                    drawPath(path, selColor, style = Stroke(selStroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
                                }
                                is DrawingPrimitive.RectanglePrimitive -> {
                                    val l = minOf(hl.startX, hl.endX); val t = minOf(hl.startY, hl.endY)
                                    val r = maxOf(hl.startX, hl.endX); val b = maxOf(hl.startY, hl.endY)
                                    if (kotlin.math.abs(hl.rotation) < 0.001f) {
                                        drawRect(selColor, Offset(l, t), Size(r - l, b - t), style = Stroke(selStroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
                                    } else {
                                        val cx = (l + r) / 2f; val cy = (t + b) / 2f
                                        val hw = (r - l) / 2f; val hh = (b - t) / 2f
                                        val cosR = kotlin.math.cos(hl.rotation); val sinR = kotlin.math.sin(hl.rotation)
                                        fun rot(wx: Float, wy: Float): Offset {
                                            val dx = wx - cx; val dy = wy - cy
                                            return Offset(cx + dx * cosR - dy * sinR, cy + dx * sinR + dy * cosR)
                                        }
                                        val path = Path().apply {
                                            val c0 = rot(cx - hw, cy - hh); val c1 = rot(cx + hw, cy - hh)
                                            val c2 = rot(cx + hw, cy + hh); val c3 = rot(cx - hw, cy + hh)
                                            moveTo(c0.x, c0.y); lineTo(c1.x, c1.y)
                                            lineTo(c2.x, c2.y); lineTo(c3.x, c3.y); close()
                                        }
                                        drawPath(path, selColor, style = Stroke(selStroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
                                    }
                                }
                                is DrawingPrimitive.LinePrimitive -> {
                                    val s = Offset(hl.startX, hl.startY)
                                    val e = Offset(hl.endX, hl.endY)
                                    drawLine(selColor, s, e, strokeWidth = selStroke, cap = StrokeCap.Round)
                                }
                                is DrawingPrimitive.CirclePrimitive -> {
                                    val rx = abs(hl.endX - hl.centerX)
                                    val ry = abs(hl.endY - hl.centerY)
                                    if (rx > 0.5f || ry > 0.5f) {
                                        val segs = 32; val pi = kotlin.math.PI.toFloat()
                                        val path = Path().apply {
                                            for (i in 0..segs) {
                                                val a = (i.toFloat() / segs) * 2f * pi
                                                val x = hl.centerX + rx * cos(a)
                                                val y = hl.centerY + ry * sin(a)
                                                if (i == 0) moveTo(x, y) else lineTo(x, y)
                                            }
                                            close()
                                        }
                                        drawPath(path, selColor, style = Stroke(selStroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
                                    }
                                }
                                is DrawingPrimitive.NumberLabelPrimitive -> {
                                    val half = hl.fontSize * 0.65f
                                    drawRect(selColor, Offset(hl.x - half, hl.y - half), Size(half * 2f, half * 2f),
                                        style = Stroke(minSelPx, cap = StrokeCap.Round, join = StrokeJoin.Round))
                                }
                                is DrawingPrimitive.TextPrimitive -> {
                                    val halfW = hl.text.length * hl.fontSize * 0.25f
                                    val halfH = hl.fontSize * 0.5f
                                    drawRect(selColor, Offset(hl.x - halfW, hl.y - halfH), Size(halfW * 2f, halfH * 2f),
                                        style = Stroke(minSelPx, cap = StrokeCap.Round, join = StrokeJoin.Round))
                                }
                                is DrawingPrimitive.RangeLabelPrimitive -> {
                                    val halfW = hl.fontSize * 2.5f
                                    val halfH = hl.fontSize * 0.5f
                                    drawRect(selColor, Offset(hl.x - halfW, hl.y - halfH), Size(halfW * 2f, halfH * 2f),
                                        style = Stroke(minSelPx, cap = StrokeCap.Round, join = StrokeJoin.Round))
                                }
                                is DrawingPrimitive.BlockRefPrimitive -> {
                                    val half = 30f * hl.scale
                                    val path = Path().apply {
                                        moveTo(hl.x - half, hl.y - half)
                                        lineTo(hl.x + half, hl.y - half)
                                        lineTo(hl.x + half, hl.y + half)
                                        lineTo(hl.x - half, hl.y + half)
                                        close()
                                    }
                                    drawPath(path, selColor, style = Stroke(selStroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
                                }
                            }
                        }
                        drawPrimitive(primitive, 1f, compoundScale, blockDefs)
                    }
                }

                // Current drawing preview
                currentPrimitive?.let { cp ->
                    if (cp.layerId in visibleLayerIds) {
                        drawPrimitive(cp, 0.6f, globalLineScale * cp.lineScaleFactor, blockDefs)
                    }
                }

                // Eraser radius indicator (very light red filled circle)
                if (currentTool == ToolType.ERASER && eraserTouchPoint != null) {
                    val ep = eraserTouchPoint!!
                    val r = eraserRadius.coerceAtLeast(5f)
                    drawCircle(Color(0x15FF4444), r, Offset(ep.x, ep.y))
                    drawCircle(Color(0x30FF4444), r, Offset(ep.x, ep.y), style = Stroke(width = 2f))
                }
            }
        }

        // ── Layer 2: Selection overlay ─────────────────────
        // (handled by SelectionOverlay in MainScreen)
    }
}

// ═══════════════════════════════════════════════════════════
//  Primitive drawing dispatch
// ═══════════════════════════════════════════════════════════

private fun DrawScope.drawPrimitive(primitive: DrawingPrimitive, alpha: Float, strokeScale: Float = 1f, blockDefs: List<BlockDef> = emptyList()) {
    when (primitive) {
        is DrawingPrimitive.FreehandPath -> drawFreehandPath(primitive, alpha, strokeScale)
        is DrawingPrimitive.RectanglePrimitive -> drawRectanglePrimitive(primitive, alpha, strokeScale)
        is DrawingPrimitive.CirclePrimitive -> drawCirclePrimitive(primitive, alpha, strokeScale)
        is DrawingPrimitive.LinePrimitive -> drawLinePrimitive(primitive, alpha, strokeScale)
        is DrawingPrimitive.NumberLabelPrimitive -> drawNumberLabel(primitive, alpha, strokeScale)
        is DrawingPrimitive.TextPrimitive -> drawTextPrimitive(primitive, alpha, strokeScale)
        is DrawingPrimitive.RangeLabelPrimitive -> drawRangeLabel(primitive, alpha, strokeScale)
        is DrawingPrimitive.BlockRefPrimitive -> drawBlockRef(primitive, alpha, strokeScale, blockDefs)
    }
}

// ═══════════════════════════════════════════════════════════
//  FreehandPath
// ═══════════════════════════════════════════════════════════

private fun DrawScope.drawFreehandPath(
    p: DrawingPrimitive.FreehandPath,
    alpha: Float,
    strokeScale: Float = 1f
) {
    if (p.points.size < 2) return
    val path = smoothPathFromPoints(p.points, p.isClosed)
    drawPathWithStyle(
        path = path,
        color = p.color.copy(alpha = alpha),
        strokeWidth = p.strokeWidth,
        lineStyle = p.lineStyle,
        strokeScale = strokeScale
    )

    // 闪电线型：按原始采样点的整段路径总长均匀分布X标记（不逐段调用，避免5px间距碎片化）
    if (p.lineStyle.type == LineType.LIGHTNING && p.points.size >= 2) {
        drawLightningOnPolyline(p.points, p.isClosed, p.color.copy(alpha = alpha), strokeScale)
    }
}

// ═══════════════════════════════════════════════════════════
//  Polyline lightning: distribute X evenly along total path length
// ═══════════════════════════════════════════════════════════

private fun DrawScope.drawLightningOnPolyline(
    points: List<Point2D>, isClosed: Boolean, color: Color, strokeScale: Float
) {
    // 计算总长和各段长
    val segLens = FloatArray(points.size - 1)
    var totalLen = 0f
    for (i in 0 until points.size - 1) {
        val dx = points[i + 1].x - points[i].x
        val dy = points[i + 1].y - points[i].y
        segLens[i] = sqrt(dx * dx + dy * dy)
        totalLen += segLens[i]
    }
    if (totalLen < 1f) return

    val n = maxOf(2, (totalLen / 120f).toInt())
    for (k in 1..n) {
        val target = (k.toFloat() / (n + 1)) * totalLen
        var accumulated = 0f
        for (segIdx in 0 until points.size - 1) {
            if (accumulated + segLens[segIdx] >= target) {
                val localT = if (segLens[segIdx] > 0f) (target - accumulated) / segLens[segIdx] else 0f
                val px = points[segIdx].x + localT * (points[segIdx + 1].x - points[segIdx].x)
                val py = points[segIdx].y + localT * (points[segIdx + 1].y - points[segIdx].y)
                val sdx = points[segIdx + 1].x - points[segIdx].x
                val sdy = points[segIdx + 1].y - points[segIdx].y
                drawXMarkOnSegment(Offset(px, py), Offset(sdx, sdy), color, strokeScale)
                break
            }
            accumulated += segLens[segIdx]
        }
    }
}

/** Catmull-Rom to cubic Bezier: build a smooth path through sampled points. */
private fun smoothPathFromPoints(points: List<Point2D>, isClosed: Boolean): Path {
    val n = points.size
    if (n < 2) return Path()
    return Path().apply {
        moveTo(points[0].x, points[0].y)
        if (n == 2) {
            lineTo(points[1].x, points[1].y)
            return@apply
        }
        if (isClosed) {
            for (i in 0 until n) {
                val p0 = points[(i - 1 + n) % n]
                val p1 = points[i]
                val p2 = points[(i + 1) % n]
                val p3 = points[(i + 2) % n]
                val cp1x = p1.x + (p2.x - p0.x) / 6f
                val cp1y = p1.y + (p2.y - p0.y) / 6f
                val cp2x = p2.x - (p3.x - p1.x) / 6f
                val cp2y = p2.y - (p3.y - p1.y) / 6f
                cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
            }
            close()
        } else {
            // First segment as straight line; internal segments as cubic curves.
            lineTo(points[1].x, points[1].y)
            for (i in 1 until n - 2) {
                val p0 = points[i - 1]
                val p1 = points[i]
                val p2 = points[i + 1]
                val p3 = points[i + 2]
                val cp1x = p1.x + (p2.x - p0.x) / 6f
                val cp1y = p1.y + (p2.y - p0.y) / 6f
                val cp2x = p2.x - (p3.x - p1.x) / 6f
                val cp2y = p2.y - (p3.y - p1.y) / 6f
                cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
            }
            lineTo(points[n - 1].x, points[n - 1].y)
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  RectanglePrimitive
// ═══════════════════════════════════════════════════════════

private fun DrawScope.drawRectanglePrimitive(
    p: DrawingPrimitive.RectanglePrimitive,
    alpha: Float,
    strokeScale: Float = 1f
) {
    val left = minOf(p.startX, p.endX)
    val top = minOf(p.startY, p.endY)
    val right = maxOf(p.startX, p.endX)
    val bottom = maxOf(p.startY, p.endY)
    val cx = (left + right) / 2f
    val cy = (top + bottom) / 2f
    // 矩形实际半宽/半高（前旋转尺寸）
    val hw = (right - left) / 2f
    val hh = (bottom - top) / 2f

    val rectPath = Path().apply {
        if (kotlin.math.abs(p.rotation) < 0.001f) {
            moveTo(left, top)
            lineTo(right, top)
            lineTo(right, bottom)
            lineTo(left, bottom)
        } else {
            val cosR = kotlin.math.cos(p.rotation)
            val sinR = kotlin.math.sin(p.rotation)
            // 用实际半宽/半高计算4个角，再旋转，而非旋转AABB角点
            fun corner(wx: Float, wy: Float): Offset {
                val dx = wx - cx; val dy = wy - cy
                return Offset(cx + dx * cosR - dy * sinR, cy + dx * sinR + dy * cosR)
            }
            val c0 = corner(cx - hw, cy - hh)
            val c1 = corner(cx + hw, cy - hh)
            val c2 = corner(cx + hw, cy + hh)
            val c3 = corner(cx - hw, cy + hh)
            moveTo(c0.x, c0.y)
            lineTo(c1.x, c1.y)
            lineTo(c2.x, c2.y)
            lineTo(c3.x, c3.y)
        }
        close()
    }
    drawPathWithStyle(
        path = rectPath,
        color = p.color.copy(alpha = alpha),
        strokeWidth = p.strokeWidth,
        lineStyle = p.lineStyle,
        strokeScale = strokeScale
    )

    // Lightning X marks along the perimeter
    if (p.lineStyle.type == LineType.LIGHTNING) {
        val cosR = kotlin.math.cos(p.rotation)
        val sinR = kotlin.math.sin(p.rotation)
        fun rot(wx: Float, wy: Float): Offset {
            val dx = wx - cx; val dy = wy - cy
            return Offset(cx + dx * cosR - dy * sinR, cy + dx * sinR + dy * cosR)
        }
        val edges = listOf(
            rot(left, top) to rot(right, top),
            rot(right, top) to rot(right, bottom),
            rot(right, bottom) to rot(left, bottom),
            rot(left, bottom) to rot(left, top)
        )
        for ((a, b) in edges) {
            drawLightningXMarks(a, b, p.color.copy(alpha = alpha), strokeScale)
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  CirclePrimitive (two-point diameter mode, supports ellipse)
// ═══════════════════════════════════════════════════════════

private fun DrawScope.drawCirclePrimitive(
    p: DrawingPrimitive.CirclePrimitive,
    alpha: Float,
    strokeScale: Float = 1f
) {
    val rx = abs(p.endX - p.centerX)
    val ry = abs(p.endY - p.centerY)
    if (rx < 0.5f && ry < 0.5f) return

    val path = Path().apply {
        addOval(Rect(p.centerX - rx, p.centerY - ry, p.centerX + rx, p.centerY + ry))
    }
    drawPathWithStyle(
        path = path,
        color = p.color.copy(alpha = alpha),
        strokeWidth = p.strokeWidth,
        lineStyle = p.lineStyle,
        strokeScale = strokeScale
    )

    // Lightning X marks along the perimeter
    if (p.lineStyle.type == LineType.LIGHTNING) {
        val circumference = PI.toFloat() * (3f * (rx + ry) - sqrt((3f * rx + ry) * (rx + 3f * ry)))
        val intervalScaled = 30f / strokeScale.coerceAtLeast(0.25f)
        val count = maxOf(8, (circumference / 120f).toInt())
        for (i in 0 until count) {
            val angle = (i.toFloat() / count) * 2f * PI.toFloat()
            val px = p.centerX + rx * cos(angle)
            val py = p.centerY + ry * sin(angle)
            // Tangent direction at this point on ellipse: (-rx*sin(θ), ry*cos(θ))
            val tx = -rx * sin(angle); val ty = ry * cos(angle)
            drawXMarkOnSegment(Offset(px, py), Offset(tx, ty), p.color.copy(alpha = alpha), strokeScale)
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  LinePrimitive
// ═══════════════════════════════════════════════════════════

private fun DrawScope.drawLinePrimitive(
    p: DrawingPrimitive.LinePrimitive,
    alpha: Float,
    strokeScale: Float = 1f
) {
    val start = Offset(p.startX, p.startY)
    val end = Offset(p.endX, p.endY)

    val linePath = Path().apply {
        moveTo(start.x, start.y)
        lineTo(end.x, end.y)
    }
    drawPathWithStyle(
        path = linePath,
        color = p.color.copy(alpha = alpha),
        strokeWidth = p.strokeWidth,
        lineStyle = p.lineStyle,
        strokeScale = strokeScale
    )

    // Lightning X marks along the line
    if (p.lineStyle.type == LineType.LIGHTNING) {
        drawLightningXMarks(start, end, p.color.copy(alpha = alpha), strokeScale)
    }
}

// ═══════════════════════════════════════════════════════════
//  NumberLabelPrimitive (via native canvas)
// ═══════════════════════════════════════════════════════════

private fun DrawScope.drawNumberLabel(
    p: DrawingPrimitive.NumberLabelPrimitive,
    alpha: Float,
    strokeScale: Float = 1f
) {
    val text = p.value.toString()
    val hc = p.color.copy(alpha = alpha).hashCode()
    val paint = Paint()
    paint.color = hc
    paint.textSize = p.fontSize * 1.3f
    paint.typeface = Typeface.DEFAULT_BOLD
    paint.isAntiAlias = true
    paint.textAlign = Paint.Align.CENTER
    val textWidth = paint.measureText(text)

    drawContext.canvas.nativeCanvas.apply {
        val cx = p.x
        val cy = p.y

        if (p.rotation == 0f) {
            drawText(text, cx, cy + p.fontSize * 0.4f, paint)
        } else {
            save()
            rotate(p.rotation * 180f / PI.toFloat(), cx, cy)
            drawText(text, cx, cy + p.fontSize * 0.4f, paint)
            restore()
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  TextPrimitive
// ═══════════════════════════════════════════════════════════

private fun DrawScope.drawTextPrimitive(
    p: DrawingPrimitive.TextPrimitive,
    alpha: Float,
    strokeScale: Float = 1f
) {
    if (p.text.isBlank()) return
    val hc = p.color.copy(alpha = alpha).hashCode()
    val paint = Paint().apply {
        color = hc
        textSize = p.fontSize * 1.3f * strokeScale
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    val textWidth = paint.measureText(p.text)
    drawContext.canvas.nativeCanvas.apply {
        val cx = p.x; val cy = p.y
        if (kotlin.math.abs(p.rotation) < 0.01f) {
            drawText(p.text, cx, cy + p.fontSize * 0.4f, paint)
        } else {
            save()
            rotate(p.rotation * 180f / PI.toFloat(), cx, cy)
            drawText(p.text, cx, cy + p.fontSize * 0.4f, paint)
            restore()
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  RangeLabelPrimitive
// ═══════════════════════════════════════════════════════════

private fun DrawScope.drawRangeLabel(
    p: DrawingPrimitive.RangeLabelPrimitive,
    alpha: Float,
    strokeScale: Float = 1f
) {
    val fs = p.fontSize * strokeScale
    val hc = p.color.copy(alpha = alpha).hashCode()
    val paint = Paint().apply {
        color = hc; textSize = fs; isAntiAlias = true; textAlign = Paint.Align.CENTER
    }
    val nc = drawContext.canvas.nativeCanvas
    val label1 = p.startValue.toString()
    val label2 = p.endValue.toString()
    val gap = fs * 1.0f
    val arrowLen = maxOf(80f * strokeScale * p.arrowSpan, 20f)
    val cx = p.x; val cy = p.y

    if (kotlin.math.abs(p.rotation) < 0.01f) {
        // Horizontal layout (no rotation)
        drawLabelContent(nc, paint, label1, label2, cx, cy, fs, arrowLen, gap, hc, strokeScale, alpha, p.reversed)
    } else {
        // Rotated layout
        nc.save()
        nc.rotate(p.rotation * 180f / kotlin.math.PI.toFloat(), cx, cy)
        drawLabelContent(nc, paint, label1, label2, cx, cy, fs, arrowLen, gap, hc, strokeScale, alpha, p.reversed)
        nc.restore()
    }
}

/** Draw the range label content (arrow + two numbers) assuming no rotation transform needed */
private fun drawLabelContent(
    nc: android.graphics.Canvas, paint: Paint,
    label1: String, label2: String,
    cx: Float, cy: Float, fs: Float,
    arrowLen: Float, gap: Float, hc: Int,
    strokeScale: Float, alpha: Float,
    reversed: Boolean = false
) {
    val leftX = cx - arrowLen / 2f - gap
    val rightX = cx + arrowLen / 2f + gap
    val textCenterY = cy + fs * 0.3f
    if (reversed) {
        nc.drawText(label2, leftX, textCenterY, paint)
        nc.drawText(label1, rightX, textCenterY, paint)
    } else {
        nc.drawText(label1, leftX, textCenterY, paint)
        nc.drawText(label2, rightX, textCenterY, paint)
    }
    // Arrow line centered vertically with number text
    val arrowY = cy
    val ap = Paint().apply { color = hc; strokeWidth = 2f * strokeScale; isAntiAlias = true }
    val ax1 = cx - arrowLen / 2f; val ax2 = cx + arrowLen / 2f
    nc.drawLine(ax1, arrowY, ax2, arrowY, ap)
    val hs = maxOf(4f, fs / strokeScale * 0.3f) * strokeScale
    if (reversed) {
        // Arrowhead at left end (pointing left)
        nc.drawLine(ax1, arrowY, ax1 + hs, arrowY - hs, ap)
        nc.drawLine(ax1, arrowY, ax1 + hs, arrowY + hs, ap)
    } else {
        // Arrowhead at right end (pointing right)
        nc.drawLine(ax2, arrowY, ax2 - hs, arrowY - hs, ap)
        nc.drawLine(ax2, arrowY, ax2 - hs, arrowY + hs, ap)
    }
}

// ═══════════════════════════════════════════════════════════
//  BlockRefPrimitive (simple box placeholder)
// ═══════════════════════════════════════════════════════════

private fun DrawScope.drawBlockRef(
    p: DrawingPrimitive.BlockRefPrimitive,
    alpha: Float,
    strokeScale: Float = 1f,
    blockDefs: List<BlockDef> = emptyList()
) {
    val bd = blockDefs.find { it.id == p.blockDefId }
    if (bd != null && bd.primitives.isNotEmpty()) {
        // Compute centroid of block content
        var cx = 0f; var cy = 0f; var count = 0
        for (prim in bd.primitives) {
            val b = when (prim) {
                is DrawingPrimitive.FreehandPath -> {
                    if (prim.points.size < 2) null
                    else { val xs = prim.points.map { it.x }; val ys = prim.points.map { it.y }
                        floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max()) }
                }
                is DrawingPrimitive.RectanglePrimitive -> {
                    val left = minOf(prim.startX, prim.endX); val top = minOf(prim.startY, prim.endY)
                    val right = maxOf(prim.startX, prim.endX); val bottom = maxOf(prim.startY, prim.endY)
                    if (kotlin.math.abs(prim.rotation) < 0.001f) {
                        floatArrayOf(left, top, right, bottom)
                    } else {
                        val cx = (left + right) / 2f; val cy = (top + bottom) / 2f
                        val cosR = kotlin.math.cos(prim.rotation); val sinR = kotlin.math.sin(prim.rotation)
                        fun rot(wx: Float, wy: Float): Point2D {
                            val dx = wx - cx; val dy = wy - cy
                            return Point2D(cx + dx * cosR - dy * sinR, cy + dx * sinR + dy * cosR)
                        }
                        val pts = listOf(rot(left, top), rot(right, top), rot(right, bottom), rot(left, bottom))
                        val xs = pts.map { it.x }; val ys = pts.map { it.y }
                        floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
                    }
                }
                is DrawingPrimitive.CirclePrimitive -> {
                    val r = maxOf(abs(prim.endX - prim.centerX), abs(prim.endY - prim.centerY))
                    floatArrayOf(prim.centerX - r, prim.centerY - r, prim.centerX + r, prim.centerY + r)
                }
                is DrawingPrimitive.LinePrimitive -> {
                    floatArrayOf(minOf(prim.startX, prim.endX), minOf(prim.startY, prim.endY),
                        maxOf(prim.startX, prim.endX), maxOf(prim.startY, prim.endY))
                }
                is DrawingPrimitive.NumberLabelPrimitive -> {
                    val numChars = prim.value.toString().length.coerceAtLeast(1)
                    val hw = prim.fontSize * 0.3f * numChars; val hh = prim.fontSize * 0.4f
                    floatArrayOf(prim.x - hw, prim.y - hh, prim.x + hw, prim.y + hh)
                }
                is DrawingPrimitive.TextPrimitive -> {
                    val numChars = prim.text.length.coerceAtLeast(1)
                    val hw = prim.fontSize * 0.35f * numChars; val hh = prim.fontSize * 0.5f
                    floatArrayOf(prim.x - hw, prim.y - hh, prim.x + hw, prim.y + hh)
                }
                else -> null
            }
            if (b != null) { cx += (b[0] + b[2]) / 2f; cy += (b[1] + b[3]) / 2f; count++ }
        }
        val centroidX = if (count > 0) cx / count else 0f
        val centroidY = if (count > 0) cy / count else 0f

        withTransform({
            translate(p.x - centroidX * p.scale, p.y - centroidY * p.scale)
            rotate(p.rotation, Offset.Zero)
            scale(p.scale, p.scale, Offset.Zero)
        }) {
            for (prim in bd.primitives) {
                drawPrimitive(prim, alpha, strokeScale * prim.lineScaleFactor)
            }
        }
    } else {
        // Fallback: placeholder square with X
        val halfSize = 30f * p.scale * strokeScale
        val left = p.x - halfSize
        val top = p.y - halfSize
        val right = p.x + halfSize
        val bottom = p.y + halfSize

        val color = p.color.copy(alpha = alpha)

        val boxPath = Path().apply {
            moveTo(left, top)
            lineTo(right, top)
            lineTo(right, bottom)
            lineTo(left, bottom)
            close()
        }
        drawPathWithStyle(
            path = boxPath,
            color = color,
            strokeWidth = p.strokeWidth,
            lineStyle = p.lineStyle,
            strokeScale = strokeScale
        )

        val xw = 1.5f * strokeScale
        drawLine(color, Offset(left, top), Offset(right, bottom), strokeWidth = xw)
        drawLine(color, Offset(right, top), Offset(left, bottom), strokeWidth = xw)

        if (p.rotation != 0f) {
            val rad = p.rotation
            val arrowLen = halfSize + 15f * strokeScale
            val ax = p.x + arrowLen * cos(rad)
            val ay = p.y + arrowLen * sin(rad)
            drawLine(color, Offset(p.x, p.y), Offset(ax, ay), strokeWidth = 2f * strokeScale)
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  Line-style-aware path drawing
// ═══════════════════════════════════════════════════════════

internal fun DrawScope.drawPathWithStyle(
    path: Path,
    color: Color,
    strokeWidth: Float,
    lineStyle: LineStyle,
    strokeScale: Float = 1f
) {
    val w = strokeWidth * strokeScale
    when (lineStyle.type) {
        LineType.SOLID -> {
            drawPath(
                path = path,
                color = color,
                style = Stroke(
                    width = w,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
        LineType.DASHED -> {
            drawPath(
                path = path,
                color = color,
                style = Stroke(
                    width = w,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(lineStyle.dashLength * strokeScale, lineStyle.gapLength * strokeScale)
                    )
                )
            )
        }
        LineType.LIGHTNING -> {
            // Draw solid path
            drawPath(
                path = path,
                color = color,
                style = Stroke(
                    width = strokeWidth,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
            // X marks are drawn by the caller per-primitive
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  Lightning X marks along a line segment
// ═══════════════════════════════════════════════════════════

private fun DrawScope.drawLightningXMarks(
    from: Offset,
    to: Offset,
    color: Color,
    strokeScale: Float = 1f
) {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val length = sqrt(dx * dx + dy * dy)
    if (length < 1f) return

    // 间距 120px（与 DXF 导出一致），最少 2 个内点
    val n = maxOf(2, (length / 120f).toInt())
    for (k in 1..n) {
        val t = k.toFloat() / (n + 1)
        val px = from.x + t * dx
        val py = from.y + t * dy
        // X 标记相对线段方向旋转 ±45°
        drawXMarkOnSegment(Offset(px, py), Offset(dx, dy), color, strokeScale)
    }
}

// ═══════════════════════════════════════════════════════════
//  Single X mark — two crossed lines at ±45° to a direction
// ═══════════════════════════════════════════════════════════

/** Draw X mark rotated to align with segment direction (for lightning style). */
private fun DrawScope.drawXMarkOnSegment(
    center: Offset,
    dir: Offset,  // segment direction vector
    color: Color,
    strokeScale: Float = 1f
) {
    val len = sqrt(dir.x * dir.x + dir.y * dir.y)
    if (len < 0.001f) return
    val ux = dir.x / len; val uy = dir.y / len  // unit direction
    val cos45 = 0.7071068f; val sin45 = 0.7071068f
    val s = 16f * strokeScale  // half-size
    val w = 2f * strokeScale
    // X mark: two lines at ±45° relative to segment direction → 90° between them
    val d1x = s * (ux * cos45 - uy * sin45)
    val d1y = s * (ux * sin45 + uy * cos45)
    val d2x = s * (ux * cos45 + uy * sin45)
    val d2y = s * (-ux * sin45 + uy * cos45)
    drawLine(color, Offset(center.x - d1x, center.y - d1y), Offset(center.x + d1x, center.y + d1y), strokeWidth = w)
    drawLine(color, Offset(center.x - d2x, center.y - d2y), Offset(center.x + d2x, center.y + d2y), strokeWidth = w)
}

// ═══════════════════════════════════════════════════════════
//  Adaptive grid
// ═══════════════════════════════════════════════════════════

private fun DrawScope.drawGrid(
    scale: Float,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float
) {
    val spacing = niceGridSpacing(scale)
    val minorColor = Color(0xFFE0E0E0)
    val majorColor = Color(0xFFD0D0D0)

    val startX = floor(left / spacing).toFloat() * spacing
    val startY = floor(top / spacing).toFloat() * spacing

    val majorEvery = when {
        spacing < 1f -> 100
        spacing < 10f -> 10
        spacing < 100f -> 5
        else -> 5
    }

    var x = startX
    while (x < right) {
        val isMajor = ((x / spacing).toInt() % majorEvery) == 0
        drawLine(
            if (isMajor) majorColor else minorColor,
            Offset(x, top), Offset(x, bottom),
            strokeWidth = if (isMajor) 0.8f else 0.4f
        )
        x += spacing
    }

    var y = startY
    while (y < bottom) {
        val isMajor = ((y / spacing).toInt() % majorEvery) == 0
        drawLine(
            if (isMajor) majorColor else minorColor,
            Offset(left, y), Offset(right, y),
            strokeWidth = if (isMajor) 0.8f else 0.4f
        )
        y += spacing
    }
}

private fun niceGridSpacing(scale: Float): Float {
    if (scale <= 0.001f) return 50f
    val targetPixels = 50f
    var raw = targetPixels / scale
    val magnitude = 10.0.pow(floor(log10(raw.toDouble()))).toFloat()
    val normalized = raw / magnitude
    raw = when {
        normalized < 1.5f -> magnitude
        normalized < 3.5f -> 2f * magnitude
        normalized < 7.5f -> 5f * magnitude
        else -> 10f * magnitude
    }
    return raw.coerceAtLeast(0.001f)
}
