package com.scheda.app.ui.canvas

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.scheda.app.model.DrawingPrimitive
import com.scheda.app.model.BlockDef
import com.scheda.app.model.blockContentCentroid
import com.scheda.app.model.Layer
import com.scheda.app.model.LineStyle
import com.scheda.app.model.LineType
import com.scheda.app.model.PendingEdit
import com.scheda.app.model.Point2D
import com.scheda.app.model.RangeLabelLayout
import com.scheda.app.model.ReferenceImage
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
 *
 * Touch handling: 2-finger events compute pinch-to-zoom via onCanvasTransform.
 * 1-finger events handle drawing/selection. The isHandleActive lambda pauses
 * 1-finger drawing while overlay handles (PaddedFrameOverlay / ArrayOverlay)
 * are being dragged; it is read synchronously at event time so a handle
 * pressed during the Initial pass blocks the canvas on the same event.
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
    isTransforming: Boolean = false,
    globalLineScale: Float = 1f,
    blockDefs: List<BlockDef> = emptyList(),
    images: List<ReferenceImage> = emptyList(),
    imageBitmaps: Map<String, Bitmap> = emptyMap(),
    imageManageActive: Boolean = false,
    selectedImageId: String? = null,
    eraserRadius: Float = 30f,
    eraserTouchPoint: Point2D? = null,
    quickEraseEnabled: Boolean = false,
    isHandleActive: () -> Boolean = { false },
    onLongPressEraser: () -> Unit = {},
    onCanvasTransform: (zoom: Float, centroid: Offset, pan: Offset) -> Unit = { _, _, _ -> },
    onTouchStart: (Point2D) -> Unit,
    onTouchMove: (Point2D) -> Unit,
    onTouchEnd: () -> Unit,
    onTouchCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scaleState = rememberUpdatedState(canvasScale)
    val offXState = rememberUpdatedState(canvasOffsetX)
    val offYState = rememberUpdatedState(canvasOffsetY)
    val touchStartState = rememberUpdatedState(onTouchStart)
    val touchMoveState = rememberUpdatedState(onTouchMove)
    val touchEndState = rememberUpdatedState(onTouchEnd)
    val touchCancelState = rememberUpdatedState(onTouchCancel)
    val quickEraseState = rememberUpdatedState(quickEraseEnabled)
    val currentToolState = rememberUpdatedState(currentTool)
    val longPressEraserState = rememberUpdatedState(onLongPressEraser)
    val handleActiveState = rememberUpdatedState(isHandleActive)
    val canvasTransformState = rememberUpdatedState(onCanvasTransform)

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
                        var longPressDownTime = 0L
                        var longPressDownPos = Offset.Zero
                        var longPressTriggered = false
                        var longPressBlocked = false
                        var wasZooming = false

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val pressed = event.changes.filter { ch -> ch.pressed }
                            val pointerCount = pressed.size
                            val s = scaleState.value
                            val ox = offXState.value
                            val oy = offYState.value

                            // ── Long-press detection for temporary eraser ────
                            // 仅单指静止长按可激活；手指移动过或出现多指即作废本次按压，
                            // 必须全部提起后重新长按
                            val qe = quickEraseState.value
                            val ct = currentToolState.value
                            if (qe && ct != ToolType.ERASER) {
                                when {
                                    pointerCount == 1 -> {
                                        val pos = pressed[0].position
                                        val now = System.currentTimeMillis()
                                        if (!longPressTriggered && !longPressBlocked) {
                                            if (longPressDownTime == 0L) {
                                                // 首个事件：记录按下位置和时间
                                                longPressDownTime = now
                                                longPressDownPos = pos
                                            } else {
                                                val dist = (pos - longPressDownPos).getDistance()
                                                if (dist > 5f) {
                                                    // 移动超阈值：本次按压作废，需提起重新长按
                                                    longPressBlocked = true
                                                    longPressDownTime = 0L
                                                } else if (now - longPressDownTime > 700L) {
                                                    longPressEraserState.value()
                                                    longPressTriggered = true
                                                }
                                            }
                                        }
                                    }
                                    pointerCount >= 2 -> {
                                        // 多指（缩放等）：本次手势作废，需全部提起后重新长按
                                        longPressBlocked = true
                                        longPressDownTime = 0L
                                    }
                                }
                            }
                            if (pointerCount == 0) {
                                longPressTriggered = false
                                longPressBlocked = false
                                longPressDownTime = 0L
                                longPressDownPos = Offset.Zero
                            }

                            when {
                                // ── Multi-finger: zoom ──
                                pointerCount >= 2 -> {
                                    wasZooming = true
                                    if (isDrawing) {
                                        isDrawing = false
                                        touchCancelState.value()
                                    }
                                    val dx1 = pressed[0].position.x - pressed[1].position.x
                                    val dy1 = pressed[0].position.y - pressed[1].position.y
                                    val d1 = sqrt(dx1 * dx1 + dy1 * dy1)
                                    val dx2 = pressed[0].previousPosition.x - pressed[1].previousPosition.x
                                    val dy2 = pressed[0].previousPosition.y - pressed[1].previousPosition.y
                                    val d2 = sqrt(dx2 * dx2 + dy2 * dy2)
                                    val zoom = if (d2 > 0f) d1 / d2 else 1f
                                    val centroid = Offset(
                                        pressed.sumOf { it.position.x.toDouble() }.toFloat() / pressed.size,
                                        pressed.sumOf { it.position.y.toDouble() }.toFloat() / pressed.size
                                    )
                                    val prevCentroid = Offset(
                                        pressed.sumOf { it.previousPosition.x.toDouble() }.toFloat() / pressed.size,
                                        pressed.sumOf { it.previousPosition.y.toDouble() }.toFloat() / pressed.size
                                    )
                                    canvasTransformState.value(zoom, centroid, centroid - prevCentroid)
                                    pressed.forEach { ch -> ch.consume() }
                                }

                                // ── Single finger: draw (skip if handle active) ──
                                pointerCount == 1 -> {
                                    if (wasZooming) {
                                        pressed.forEach { it.consume() }
                                        continue
                                    }
                                    if (handleActiveState.value()) {
                                        pressed.forEach { it.consume() }
                                        continue
                                    }
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
                                    wasZooming = false
                                    if (isDrawing) {
                                        isDrawing = false
                                        touchEndState.value()
                                    }
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

                // 参考图片：网格之上、所有基元之下（恒最底层，不参与图层排序；
                // 所属图层隐藏则跳过）。native drawBitmap + RectF 浮点目标避免取整抖动
                if (images.isNotEmpty()) {
                    val nc = drawContext.canvas.nativeCanvas
                    val imgPaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
                    // 管理模式：给未选中的图片画淡虚线外框（透明度低时也能定位、分辨多张图）
                    val outlinePaint = if (imageManageActive) Paint().apply {
                        isAntiAlias = true
                        style = Paint.Style.STROKE
                        color = 0xAA4B9CD3.toInt()
                        strokeWidth = 1.5f / canvasScale
                        pathEffect = android.graphics.DashPathEffect(
                            floatArrayOf(10f / canvasScale, 6f / canvasScale), 0f)
                    } else null
                    for (img in images) {
                        if (img.layerId !in visibleLayerIds) continue
                        val bmp = imageBitmaps[img.id]
                        nc.save()
                        nc.translate(img.centerX, img.centerY)
                        nc.rotate(img.rotationDeg)
                        if (bmp != null) {
                            imgPaint.alpha = (img.alpha.coerceIn(0f, 1f) * 255).toInt()
                            nc.drawBitmap(bmp, null, RectF(-img.width / 2f, -img.height / 2f, img.width / 2f, img.height / 2f), imgPaint)
                        }
                        if (outlinePaint != null && img.id != selectedImageId) {
                            nc.drawRect(-img.width / 2f, -img.height / 2f, img.width / 2f, img.height / 2f, outlinePaint)
                        }
                        nc.restore()
                    }
                }

                // Committed primitives with selection highlight
                // 按图层顺序绘制：列表末尾（图层0=底层）最先画，列表首部（顶层）最后画在最上
                val layerOrder = layers.withIndex().associate { it.value.id to it.index }
                val sortedByLayer = primitives.withIndex()
                    .sortedByDescending { layerOrder[it.value.layerId] ?: -1 }
                for ((origIdx, primitive) in sortedByLayer) {
                    if (primitive.layerId in visibleLayerIds) {
                        // Skip selected primitives when transform is active (PostCreationOverlay renders them)
                        if (isTransforming && origIdx in selectedIndices) continue
                        val compoundScale = globalLineScale * primitive.lineScaleFactor
                        // 选中光晕：紧贴元素的淡蓝色辉光，画在元素本体之下
                        // （变换拖动中图元由 PostCreationOverlay 渲染，光晕也在那边画）
                        if (origIdx in selectedIndices) {
                            drawSelectionGlow(primitive, canvasScale, compoundScale, blockDefs)
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
                    val ep = eraserTouchPoint
                    val r = eraserRadius.coerceAtLeast(5f)
                    drawCircle(Color(0x15FF4444), r, Offset(ep.x, ep.y))
                    drawCircle(Color(0x30FF4444), r, Offset(ep.x, ep.y), style = Stroke(width = 2f))
                }
            }
        }

        // ── Layer 2: Selection overlay ─────────────────────
        // (handled by PostCreationOverlay in DrawingScreen)
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
    val path = smoothPathFromPoints(p.points, p.isClosed, p.sharpCorners)
    drawPathWithStyle(
        path = path,
        color = p.color.copy(alpha = alpha),
        strokeWidth = p.strokeWidth,
        lineStyle = p.lineStyle,
        strokeScale = strokeScale
    )

    // 闪电线型：按原始采样点的整段路径总长均匀分布X标记（不逐段调用，避免5px间距碎片化）
    if (p.lineStyle.type == LineType.LIGHTNING) {
        drawLightningOnPolyline(p.points, p.isClosed, p.color.copy(alpha = alpha), strokeScale)
    }
}

// ═══════════════════════════════════════════════════════════
//  Polyline lightning: distribute X evenly along total path length
// ═══════════════════════════════════════════════════════════

internal fun DrawScope.drawLightningOnPolyline(
    points: List<Point2D>, isClosed: Boolean, color: Color, strokeScale: Float
) {
    // 计算总长和各段长
    val numSegs = if (isClosed) points.size else points.size - 1
    val segLens = FloatArray(numSegs)
    var totalLen = 0f
    for (i in 0 until numSegs) {
        val next = (i + 1) % points.size
        val dx = points[next].x - points[i].x
        val dy = points[next].y - points[i].y
        segLens[i] = sqrt(dx * dx + dy * dy)
        totalLen += segLens[i]
    }
    if (totalLen < 1f) return

    val n = maxOf(2, (totalLen / 120f).toInt())
    for (k in 1..n) {
        val target = (k.toFloat() / (n + 1)) * totalLen
        var accumulated = 0f
        for (segIdx in 0 until numSegs) {
            if (accumulated + segLens[segIdx] >= target) {
                val next = (segIdx + 1) % points.size
                val localT = if (segLens[segIdx] > 0f) (target - accumulated) / segLens[segIdx] else 0f
                val px = points[segIdx].x + localT * (points[next].x - points[segIdx].x)
                val py = points[segIdx].y + localT * (points[next].y - points[segIdx].y)
                val sdx = points[next].x - points[segIdx].x
                val sdy = points[next].y - points[segIdx].y
                drawXMarkOnSegment(Offset(px, py), Offset(sdx, sdy), color, strokeScale)
                break
            }
            accumulated += segLens[segIdx]
        }
    }
}

/** Catmull-Rom to cubic Bezier: build a smooth path through sampled points.
 *  @param sharpCorners indices of points that should remain sharp (straight lines instead of curves).
 */
internal fun smoothPathFromPoints(points: List<Point2D>, isClosed: Boolean, sharpCorners: Set<Int> = emptySet()): Path {
    val n = points.size
    if (n < 2) return Path()
    return Path().apply {
        moveTo(points[0].x, points[0].y)
        if (n == 2) {
            lineTo(points[1].x, points[1].y)
            return@apply
        }
        if (isClosed) {
            // 从 i = 0 开始：闭合样条共 n 段，段 i→(i+1)%n 的起点就是当前点。
            // 若从 i = 1 开始，第一条 cubicTo 会从 points[0] 直接跨到 points[2]，
            // 0→1 段丢失（全尖角的 DXF 闭合折线会因此缺掉第一个顶点）
            for (i in 0 until n) {
                val dest = (i + 1) % n
                // 尖角的入边和出边都必须是直线，否则出边按 Catmull-Rom 平滑会把角抹圆
                if (i in sharpCorners || dest in sharpCorners) {
                    lineTo(points[dest].x, points[dest].y)
                    continue
                }
                val p0 = points[(i - 1 + n) % n]
                val p1 = points[i]
                val p2 = points[dest]
                val p3 = points[(i + 2) % n]
                val cp1x = p1.x + (p2.x - p0.x) / 6f
                val cp1y = p1.y + (p2.y - p0.y) / 6f
                val cp2x = p2.x - (p3.x - p1.x) / 6f
                val cp2y = p2.y - (p3.y - p1.y) / 6f
                cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
            }
        } else {
            lineTo(points[1].x, points[1].y)
            for (i in 1 until n - 2) {
                // 尖角的入边和出边都必须是直线，否则出边按 Catmull-Rom 平滑会把角抹圆
                if (i in sharpCorners || (i + 1) in sharpCorners) {
                    lineTo(points[i + 1].x, points[i + 1].y)
                    continue
                }
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
    val xs = p.corners.map { it.x }; val ys = p.corners.map { it.y }
    val left = xs.min(); val top = ys.min()
    val right = xs.max(); val bottom = ys.max()
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
    val rx = p.radiusX
    val ry = p.radiusY
    if (rx < 0.5f && ry < 0.5f) return

    val path = Path().apply {
        addOval(Rect(p.centerX - rx, p.centerY - ry, p.centerX + rx, p.centerY + ry))
    }
    val paint = android.graphics.Paint().apply {
        color = p.color.copy(alpha = alpha).toArgb()
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = p.strokeWidth * strokeScale
        isAntiAlias = true
    }
    if (kotlin.math.abs(p.rotation) < 0.001f) {
        drawPathWithStyle(
            path = path,
            color = p.color.copy(alpha = alpha),
            strokeWidth = p.strokeWidth,
            lineStyle = p.lineStyle,
            strokeScale = strokeScale
        )
    } else {
        drawContext.canvas.nativeCanvas.save()
        drawContext.canvas.nativeCanvas.rotate(p.rotation * 180f / PI.toFloat(), p.centerX, p.centerY)
        drawContext.canvas.nativeCanvas.drawOval(
            p.centerX - rx, p.centerY - ry, p.centerX + rx, p.centerY + ry, paint
        )
        drawContext.canvas.nativeCanvas.restore()
    }

    // Lightning X marks along the perimeter
    if (p.lineStyle.type == LineType.LIGHTNING) {
        val circumference = PI.toFloat() * (3f * (rx + ry) - sqrt((3f * rx + ry) * (rx + 3f * ry)))
        val intervalScaled = 30f / strokeScale.coerceAtLeast(0.25f)
        val count = maxOf(8, (circumference / 120f).toInt())
        val cosR = cos(p.rotation); val sinR = sin(p.rotation)
        for (i in 0 until count) {
            val angle = (i.toFloat() / count) * 2f * PI.toFloat()
            val lx = rx * cos(angle); val ly = ry * sin(angle)
            val px = p.centerX + lx * cosR - ly * sinR
            val py = p.centerY + lx * sinR + ly * cosR
            val ltx = -rx * sin(angle); val lty = ry * cos(angle)
            val tx2 = ltx * cosR - lty * sinR; val ty2 = ltx * sinR + lty * cosR
            drawXMarkOnSegment(Offset(px, py), Offset(tx2, ty2), p.color.copy(alpha = alpha), strokeScale)
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
    val hc = p.color.copy(alpha = alpha).toArgb()
    val paint = Paint()
    paint.color = hc
    // 数字大小只由字号决定，不受线型比例影响
    paint.textSize = p.fontSize * 1.3f
    paint.typeface = Typeface.DEFAULT_BOLD
    paint.isAntiAlias = true
    paint.textAlign = Paint.Align.CENTER
    val textWidth = paint.measureText(text)

    drawContext.canvas.nativeCanvas.apply {
        val cx = p.x
        val cy = p.y

        if (p.circled) {
            // 外圈：圆心对齐文字视觉中心（baseline 上方约 0.35*textSize）
            val radius = (maxOf(textWidth / 2f, p.fontSize * 0.65f)) * 1.15f
            val cy0 = cy - p.fontSize * 0.055f
            val circlePaint = Paint().apply {
                color = hc
                style = Paint.Style.STROKE
                strokeWidth = maxOf(p.strokeWidth * strokeScale, 1.5f)
                isAntiAlias = true
            }
            if (p.rotation == 0f) {
                drawCircle(cx, cy0, radius, circlePaint)
            } else {
                save()
                rotate(p.rotation * 180f / PI.toFloat(), cx, cy)
                drawCircle(cx, cy0, radius, circlePaint)
                restore()
            }
        }

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
    val hc = p.color.copy(alpha = alpha).toArgb()
    val paint = Paint().apply {
        color = hc
        // 文字大小只由字号决定，不受线型比例影响
        textSize = p.fontSize * 1.3f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    val textWidth = paint.measureText(p.text)
    // 按字体度量真正垂直居中：ink 中心 == 锚点 (p.x, p.y) == 包围盒中心
    val fm = paint.fontMetrics
    val baseline = p.y - (fm.ascent + fm.descent) / 2f
    drawContext.canvas.nativeCanvas.apply {
        val cx = p.x
        if (kotlin.math.abs(p.rotation) < 0.01f) {
            drawText(p.text, cx, baseline, paint)
        } else {
            save()
            rotate(p.rotation * 180f / PI.toFloat(), cx, p.y)
            drawText(p.text, cx, baseline, paint)
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
    // 区间数字大小只由字号决定，不受线型比例影响（箭头线宽仍随线宽比例）
    val fs = p.fontSize
    val hc = p.color.copy(alpha = alpha).toArgb()
    val paint = Paint().apply {
        color = hc; textSize = fs; isAntiAlias = true; textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    val nc = drawContext.canvas.nativeCanvas
    val layout = RangeLabelLayout.compute(p.rotation, p.x, p.y, fs, p.arrowSpan, p.reversed,
        numberAngle = RangeLabelLayout.numberAngleFor(p.numbersFaceLeft))
    val half = RangeLabelLayout.arrowHalfLength(p.arrowSpan)

    // 区间线（箭头）：局部 (-half,0)→(+half,0) 绕中心按 arrowAngle 旋转（与纯函数同旋转公式）
    val cosA = kotlin.math.cos(layout.arrowAngle); val sinA = kotlin.math.sin(layout.arrowAngle)
    val ax1x = p.x - half * cosA; val ax1y = p.y - half * sinA
    val ax2x = p.x + half * cosA; val ax2y = p.y + half * sinA

    val ap = Paint().apply { color = hc; strokeWidth = 2f * strokeScale; isAntiAlias = true }
    nc.drawLine(ax1x, ax1y, ax2x, ax2y, ap)

    // 箭头：非反向在结束端（+half），反向在起始端（-half）；翼公式统一走 RangeLabelLayout
    val hs = maxOf(4f, fs / strokeScale * 0.3f) * strokeScale
    val tipX = if (p.reversed) ax1x else ax2x
    val tipY = if (p.reversed) ax1y else ax2y
    val (wing1, wing2) = RangeLabelLayout.arrowheadWingOffsets(layout.arrowAngle, p.reversed)
    nc.drawLine(tipX, tipY, tipX + hs * wing1.first, tipY + hs * wing1.second, ap)
    nc.drawLine(tipX, tipY, tipX + hs * wing2.first, tipY + hs * wing2.second, ap)

    // 两端数字：锚点与绘制角由纯函数给出（朝下=正向 0 / 朝左=+90°），垂直居中对齐锚点
    fun drawRangeText(text: String, anchor: com.scheda.app.model.RangeTextPlacement) {
        val fm = paint.fontMetrics
        val baseline = anchor.y - (fm.ascent + fm.descent) / 2f
        if (kotlin.math.abs(anchor.angle) < 0.01f) {
            nc.drawText(text, anchor.x, baseline, paint)
        } else {
            nc.save()
            nc.rotate(anchor.angle * 180f / kotlin.math.PI.toFloat(), anchor.x, anchor.y)
            nc.drawText(text, anchor.x, baseline, paint)
            nc.restore()
        }
    }
    val startValueText = if (p.reversed) p.endValue.toString() else p.startValue.toString()
    val endValueText = if (p.reversed) p.startValue.toString() else p.endValue.toString()
    drawRangeText(startValueText, layout.startText)
    drawRangeText(endValueText, layout.endText)
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
        // 内容形心（统一走 blockContentCentroid，与插入定位/预览/包围盒保持一致）
        val centroid = blockContentCentroid(bd.primitives) ?: Point2D(0f, 0f)
        val centroidX = centroid.x
        val centroidY = centroid.y

        withTransform({
            translate(p.x - centroidX * p.scale, p.y - centroidY * p.scale)
            // DrawTransform.rotate 单位是度；模型 rotation 是弧度（与 applyTransform/包围盒/PFO 预览一致）
            rotate(p.rotation * 180f / PI.toFloat(), Offset.Zero)
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
    strokeScale: Float = 1f,
    minWidth: Float = 1.5f
) {
    val w = maxOf(strokeWidth * strokeScale, minWidth)
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
                    width = w,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
            // X marks are drawn by the caller per-primitive
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  选中光晕：淡蓝色、紧贴元素轮廓（多趟加宽描边模拟软辉光，画在元素本体之下）
// ═══════════════════════════════════════════════════════════

/** 选中光晕颜色（淡蓝）；PostCreationOverlay 变换预览共用同一套 */
internal val SELECTION_GLOW_COLOR = Color(0xFF90CAF9)

/** 光晕趟数：（单边外扩屏幕 px, 透明度），由内向外逐趟变淡变宽 */
internal val SELECTION_GLOW_PASSES = listOf(3f to 0.50f, 8f to 0.28f, 14f to 0.15f)

/** 世界坐标空间光晕入口：canvasScale 用于把屏幕 px 外扩量换算成世界单位 */
private fun DrawScope.drawSelectionGlow(
    p: DrawingPrimitive,
    canvasScale: Float,
    strokeScale: Float,
    blockDefs: List<BlockDef>
) {
    val cs = if (canvasScale > 0.001f) canvasScale else 1f
    for ((inflatePx, alpha) in SELECTION_GLOW_PASSES) {
        drawSelectionGlowPass(p, inflatePx / cs, SELECTION_GLOW_COLOR.copy(alpha = alpha), strokeScale, blockDefs)
    }
}

/** 单趟光晕：[inflate] 为当前坐标系的单边外扩量，[gc] 为本趟颜色（含透明度） */
private fun DrawScope.drawSelectionGlowPass(
    p: DrawingPrimitive,
    inflate: Float,
    gc: Color,
    strokeScale: Float,
    blockDefs: List<BlockDef>
) {
    val nc = drawContext.canvas.nativeCanvas
    when (p) {
        is DrawingPrimitive.FreehandPath -> {
            if (p.points.size < 2) return
            val path = smoothPathFromPoints(p.points, p.isClosed, p.sharpCorners)
            val w = maxOf(p.strokeWidth * strokeScale, 1.5f) + 2f * inflate
            drawPath(path, gc, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        is DrawingPrimitive.RectanglePrimitive -> {
            // 与 drawRectanglePrimitive 同一几何（含旋转）
            val xs = p.corners.map { it.x }; val ys = p.corners.map { it.y }
            val left = xs.min(); val top = ys.min()
            val right = xs.max(); val bottom = ys.max()
            val cx = (left + right) / 2f; val cy = (top + bottom) / 2f
            val hw = (right - left) / 2f; val hh = (bottom - top) / 2f
            val path = Path().apply {
                if (abs(p.rotation) < 0.001f) {
                    moveTo(left, top); lineTo(right, top); lineTo(right, bottom); lineTo(left, bottom)
                } else {
                    val cosR = cos(p.rotation); val sinR = sin(p.rotation)
                    fun corner(wx: Float, wy: Float): Offset {
                        val dx = wx - cx; val dy = wy - cy
                        return Offset(cx + dx * cosR - dy * sinR, cy + dx * sinR + dy * cosR)
                    }
                    val c0 = corner(cx - hw, cy - hh); val c1 = corner(cx + hw, cy - hh)
                    val c2 = corner(cx + hw, cy + hh); val c3 = corner(cx - hw, cy + hh)
                    moveTo(c0.x, c0.y); lineTo(c1.x, c1.y); lineTo(c2.x, c2.y); lineTo(c3.x, c3.y)
                }
                close()
            }
            val w = maxOf(p.strokeWidth * strokeScale, 1.5f) + 2f * inflate
            drawPath(path, gc, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        is DrawingPrimitive.CirclePrimitive -> {
            val rx = p.radiusX; val ry = p.radiusY
            if (rx < 0.5f && ry < 0.5f) return
            val w = maxOf(p.strokeWidth * strokeScale, 1.5f) + 2f * inflate
            if (abs(p.rotation) < 0.001f) {
                val path = Path().apply {
                    addOval(Rect(p.centerX - rx, p.centerY - ry, p.centerX + rx, p.centerY + ry))
                }
                drawPath(path, gc, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
            } else {
                val paint = Paint().apply {
                    color = gc.toArgb(); style = Paint.Style.STROKE; strokeWidth = w; isAntiAlias = true
                }
                nc.save()
                nc.rotate(p.rotation * 180f / PI.toFloat(), p.centerX, p.centerY)
                nc.drawOval(p.centerX - rx, p.centerY - ry, p.centerX + rx, p.centerY + ry, paint)
                nc.restore()
            }
        }
        is DrawingPrimitive.LinePrimitive -> {
            val w = maxOf(p.strokeWidth * strokeScale, 1.5f) + 2f * inflate
            drawLine(gc, Offset(p.startX, p.startY), Offset(p.endX, p.endY),
                strokeWidth = w, cap = StrokeCap.Round)
        }
        is DrawingPrimitive.NumberLabelPrimitive -> {
            // 文字光晕：FILL_AND_STROKE 把字形轮廓均匀加粗，本体画在上层后只露一圈边
            val paint = Paint().apply {
                color = gc.toArgb(); textSize = p.fontSize * 1.3f
                typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true; textAlign = Paint.Align.CENTER
                style = Paint.Style.FILL_AND_STROKE; strokeWidth = 2f * inflate
            }
            if (p.circled) {
                val textWidth = paint.measureText(p.value.toString())
                val radius = (maxOf(textWidth / 2f, p.fontSize * 0.65f)) * 1.15f
                val cy0 = p.y - p.fontSize * 0.055f
                val cw = maxOf(p.strokeWidth * strokeScale, 1.5f) + 2f * inflate
                val cp = Paint().apply {
                    color = gc.toArgb(); style = Paint.Style.STROKE; strokeWidth = cw; isAntiAlias = true
                }
                if (p.rotation == 0f) {
                    nc.drawCircle(p.x, cy0, radius, cp)
                } else {
                    nc.save(); nc.rotate(p.rotation * 180f / PI.toFloat(), p.x, p.y)
                    nc.drawCircle(p.x, cy0, radius, cp); nc.restore()
                }
            }
            if (p.rotation == 0f) {
                nc.drawText(p.value.toString(), p.x, p.y + p.fontSize * 0.4f, paint)
            } else {
                nc.save(); nc.rotate(p.rotation * 180f / PI.toFloat(), p.x, p.y)
                nc.drawText(p.value.toString(), p.x, p.y + p.fontSize * 0.4f, paint); nc.restore()
            }
        }
        is DrawingPrimitive.TextPrimitive -> {
            if (p.text.isBlank()) return
            val paint = Paint().apply {
                color = gc.toArgb(); textSize = p.fontSize * 1.3f
                typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true; textAlign = Paint.Align.CENTER
                style = Paint.Style.FILL_AND_STROKE; strokeWidth = 2f * inflate
            }
            val fm = paint.fontMetrics
            val baseline = p.y - (fm.ascent + fm.descent) / 2f
            if (abs(p.rotation) < 0.01f) {
                nc.drawText(p.text, p.x, baseline, paint)
            } else {
                nc.save(); nc.rotate(p.rotation * 180f / PI.toFloat(), p.x, p.y)
                nc.drawText(p.text, p.x, baseline, paint); nc.restore()
            }
        }
        is DrawingPrimitive.RangeLabelPrimitive -> {
            // 与 drawRangeLabel 同一几何（含数字朝向 numbersFaceLeft）
            val fs = p.fontSize
            val layout = RangeLabelLayout.compute(p.rotation, p.x, p.y, fs, p.arrowSpan, p.reversed,
                numberAngle = RangeLabelLayout.numberAngleFor(p.numbersFaceLeft))
            val half = RangeLabelLayout.arrowHalfLength(p.arrowSpan)
            val cosA = cos(layout.arrowAngle); val sinA = sin(layout.arrowAngle)
            val ax1x = p.x - half * cosA; val ax1y = p.y - half * sinA
            val ax2x = p.x + half * cosA; val ax2y = p.y + half * sinA
            val w = maxOf(2f * strokeScale, 1.5f) + 2f * inflate
            drawLine(gc, Offset(ax1x, ax1y), Offset(ax2x, ax2y), strokeWidth = w, cap = StrokeCap.Round)
            val hs = maxOf(4f, fs / strokeScale * 0.3f) * strokeScale
            val tipX = if (p.reversed) ax1x else ax2x
            val tipY = if (p.reversed) ax1y else ax2y
            val (wing1, wing2) = RangeLabelLayout.arrowheadWingOffsets(layout.arrowAngle, p.reversed)
            drawLine(gc, Offset(tipX, tipY), Offset(tipX + hs * wing1.first, tipY + hs * wing1.second), strokeWidth = w, cap = StrokeCap.Round)
            drawLine(gc, Offset(tipX, tipY), Offset(tipX + hs * wing2.first, tipY + hs * wing2.second), strokeWidth = w, cap = StrokeCap.Round)
            val paint = Paint().apply {
                color = gc.toArgb(); textSize = fs; isAntiAlias = true; textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
                style = Paint.Style.FILL_AND_STROKE; strokeWidth = 2f * inflate
            }
            fun glowText(text: String, anchor: com.scheda.app.model.RangeTextPlacement) {
                val fm = paint.fontMetrics
                val baseline = anchor.y - (fm.ascent + fm.descent) / 2f
                if (abs(anchor.angle) < 0.01f) {
                    nc.drawText(text, anchor.x, baseline, paint)
                } else {
                    nc.save(); nc.rotate(anchor.angle * 180f / PI.toFloat(), anchor.x, anchor.y)
                    nc.drawText(text, anchor.x, baseline, paint); nc.restore()
                }
            }
            val startValueText = if (p.reversed) p.endValue.toString() else p.startValue.toString()
            val endValueText = if (p.reversed) p.startValue.toString() else p.endValue.toString()
            glowText(startValueText, layout.startText)
            glowText(endValueText, layout.endText)
        }
        is DrawingPrimitive.BlockRefPrimitive -> {
            // 与 drawBlockRef 同一锚定变换，光晕递归贴块内容
            val bd = blockDefs.find { it.id == p.blockDefId }
            if (bd != null && bd.primitives.isNotEmpty()) {
                val centroid = blockContentCentroid(bd.primitives) ?: Point2D(0f, 0f)
                withTransform({
                    translate(p.x - centroid.x * p.scale, p.y - centroid.y * p.scale)
                    rotate(p.rotation * 180f / PI.toFloat(), Offset.Zero)
                    scale(p.scale, p.scale, Offset.Zero)
                }) {
                    for (cp in bd.primitives) {
                        drawSelectionGlowPass(cp, inflate / p.scale, gc, strokeScale * cp.lineScaleFactor, blockDefs)
                    }
                }
            } else {
                val halfSize = 30f * p.scale * strokeScale
                val path = Path().apply {
                    moveTo(p.x - halfSize, p.y - halfSize); lineTo(p.x + halfSize, p.y - halfSize)
                    lineTo(p.x + halfSize, p.y + halfSize); lineTo(p.x - halfSize, p.y + halfSize); close()
                }
                val w = maxOf(p.strokeWidth * strokeScale, 1.5f) + 2f * inflate
                drawPath(path, gc, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
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
