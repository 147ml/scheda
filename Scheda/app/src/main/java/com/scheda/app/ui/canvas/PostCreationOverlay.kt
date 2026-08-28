package com.scheda.app.ui.canvas

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.scheda.app.model.BlockDef
import com.scheda.app.model.Bounds
import com.scheda.app.model.DrawingPrimitive
import com.scheda.app.model.LineType
import com.scheda.app.model.PendingEdit
import com.scheda.app.model.Point2D
import com.scheda.app.model.RangeLabelLayout
import com.scheda.app.model.SelectionState
import com.scheda.app.model.blockContentCentroid
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Unified overlay for both pending-edit (drawing/block) and selection modes.
 *
 * - When [pendingEdit] is active: show preview + confirm/cancel buttons.
 * - When [selection] is non-null and has selected indices: show transform handles.
 * - One Box, one pointer-input loop (pinch-to-zoom shared).
 */
@Composable
fun PostCreationOverlay(
    // ── Pending-edit mode params ──
    pendingEdit: PendingEdit,
    onConfirm: () -> Unit = {},
    onCancel: () -> Unit = {},
    onUpdateOffset: (Float, Float) -> Unit = { _, _ -> },
    onUpdateRotation: (Float) -> Unit = {},
    onUpdateScale: (Float, Float) -> Unit = { _, _ -> },
    onUpdatePrimitive: (DrawingPrimitive) -> Unit = {},
    onUpdateFontScale: (Float) -> Unit = {},
    onUpdateArrowSpan: (Float) -> Unit = {},
    onToggleTextOrientation: () -> Unit = {},
    onToggleRangeReversed: () -> Unit = {},
    currentFontSize: Float = 40f,
    // ── Selection-mode params ──
    selection: SelectionState? = null,
    onMoveSelected: (Float, Float) -> Unit = { _, _ -> },
    onRotateSelected: (Float) -> Unit = {},
    onScaleSelected: (Float, Float) -> Unit = { _, _ -> },
    onRectMidpointDrag: ((index: Int, r: Float) -> Unit)? = null,
    onTransformEnd: () -> Unit = {},
    onHandleActiveChanged: (Boolean) -> Unit = {},
    primitives: List<DrawingPrimitive> = emptyList(),
    // ── Shared params ──
    modifier: Modifier = Modifier,
    canvasScale: Float = 1f,
    canvasOffsetX: Float = 0f,
    canvasOffsetY: Float = 0f,
    globalLineScale: Float = 1f,
    blockDefs: List<BlockDef> = emptyList(),
) {
    val showPending = pendingEdit.isActive() && pendingEdit.bounds != null
    val showSelection = selection != null && selection.selectedIndices.isNotEmpty()
    val showSelectRect = selection != null && selection.isActive
    if (!showPending && !showSelection && !showSelectRect) return

    val density = LocalDensity.current

    // State wrappers so Canvas draw lambdas re-execute when values change
    // 注意：不用 rememberUpdatedState 包装 pendingEdit——直接在 drawBlock 里读参数，
    // PostCreationOverlay 重组时 lambda 必然新建，Canvas 必然重绘。
    val csState2 = rememberUpdatedState(canvasScale)
    val coxState = rememberUpdatedState(canvasOffsetX)
    val coyState = rememberUpdatedState(canvasOffsetY)
    val glsState = rememberUpdatedState(globalLineScale)
    val bdState = rememberUpdatedState(blockDefs)
    val selState = rememberUpdatedState(selection)

    // ── Pending-edit specific values ──
    val minHalf = with(density) { 30.dp.toPx() }
    val textMeasurePaint = Paint().apply {
        typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true; textAlign = Paint.Align.CENTER
    }
    val (customHw, customHh) = if (showPending) {
        val pe = pendingEdit
        val dims = pe.primitive?.let { computePrimitiveHalfDims(it, pe.scaleX, pe.scaleY, globalLineScale, canvasScale, minHalf, textMeasurePaint) }
        Pair(dims?.first, dims?.second)
    } else null to null

    val primFrameRot = if (showPending) pendingEdit.primitive?.intrinsicRotation ?: 0f else 0f
    val totalFrameRotation = if (showPending) pendingEdit.rotation + primFrameRot else 0f

    val isTextNum = showPending && (pendingEdit.primitive is DrawingPrimitive.TextPrimitive ||
        pendingEdit.primitive is DrawingPrimitive.NumberLabelPrimitive)
    val isRange = showPending && pendingEdit.primitive is DrawingPrimitive.RangeLabelPrimitive
    // Pending-edit line: 2 perpendicular midpoints
    val peLineMidpoints = if (showPending && pendingEdit.primitive is DrawingPrimitive.LinePrimitive && customHw != null && customHh != null) {
        val cosR = kotlin.math.cos(totalFrameRotation)
        val sinR = kotlin.math.sin(totalFrameRotation)
        val b = pendingEdit.bounds ?: Bounds(0f, 0f, 100f, 100f)
        val cx = (b.minX + b.maxX) / 2f + pendingEdit.offsetX
        val cy = (b.minY + b.maxY) / 2f + pendingEdit.offsetY
        val hw = customHw ?: 50f; val hh = customHh ?: 50f
        fun ws(wx: Float, wy: Float) = Offset(wx * canvasScale + canvasOffsetX, wy * canvasScale + canvasOffsetY)
        fun ls(lx: Float, ly: Float) = ws(lx * cosR - ly * sinR + cx, lx * sinR + ly * cosR + cy)
        val centerScreen = ws(cx, cy)
        val paddingPx = with(density) { 20.dp.toPx() }
        fun pad(c: Offset): Offset {
            val dx = c.x - centerScreen.x; val dy = c.y - centerScreen.y
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            return if (d > 0.01f) Offset(c.x + dx / d * paddingPx, c.y + dy / d * paddingPx) else c
        }
        listOf(pad(ls(-hw, 0f)), pad(ls(hw, 0f)))
    } else null

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // ══════════════════════════════════════════════════════
        //  SELECTION MODE — selection rectangle (while dragging)
        // ══════════════════════════════════════════════════════
        if (selection != null && selection.isActive) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val selColor = Color(0x334B9CD3)
                val borderColor = Color(0xFF4B9CD3)
                val sx = selection.selStartX * canvasScale + canvasOffsetX
                val sy = selection.selStartY * canvasScale + canvasOffsetY
                val ex = selection.selEndX * canvasScale + canvasOffsetX
                val ey = selection.selEndY * canvasScale + canvasOffsetY
                val left = minOf(sx, ex); val top = minOf(sy, ey)
                val right = maxOf(sx, ex); val bottom = maxOf(sy, ey)
                drawRect(selColor, Offset(left, top), Size(right - left, bottom - top))
                drawRect(borderColor, Offset(left, top), Size(right - left, bottom - top),
                    style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))))
            }
        }

        // ══════════════════════════════════════════════════════
        //  PADDED FRAME (shared between pending-edit + selection)
        // ══════════════════════════════════════════════════════
        if (showPending) {
            // ── Pending-edit mode ──
            // 矩形/圆/直线：框中心 = 元素当前中心经 scale/rotation 变换后的位置（pivot 为固定轴），
            // 中点手柄锚定拖拽（对边/对点固定）时元素中心会移动，保证包围盒始终包裹元素
            val pePrim = pendingEdit.primitive
            val peCenter: Point2D? = when (pePrim) {
                is DrawingPrimitive.RectanglePrimitive -> {
                    val xs = pePrim.corners.map { it.x }; val ys = pePrim.corners.map { it.y }
                    Point2D((xs.min() + xs.max()) / 2f, (ys.min() + ys.max()) / 2f)
                }
                is DrawingPrimitive.CirclePrimitive -> Point2D(pePrim.centerX, pePrim.centerY)
                is DrawingPrimitive.LinePrimitive -> Point2D((pePrim.startX + pePrim.endX) / 2f, (pePrim.startY + pePrim.endY) / 2f)
                else -> null
            }
            val (peFrameCx, peFrameCy) = if (peCenter != null) {
                val ddx = (peCenter.x - pendingEdit.pivotX) * pendingEdit.scaleX
                val ddy = (peCenter.y - pendingEdit.pivotY) * pendingEdit.scaleY
                val cosPe = cos(pendingEdit.rotation); val sinPe = sin(pendingEdit.rotation)
                Pair(pendingEdit.pivotX + ddx * cosPe - ddy * sinPe,
                     pendingEdit.pivotY + ddx * sinPe + ddy * cosPe)
            } else Pair(pendingEdit.pivotX, pendingEdit.pivotY)
            PaddedFrameOverlay(
                // 框中心 = pivot（和预览的旋转中心一致）
                bounds = Bounds(
                    peFrameCx - (customHw ?: 50f),
                    peFrameCy - (customHh ?: 50f),
                    peFrameCx + (customHw ?: 50f),
                    peFrameCy + (customHh ?: 50f)
                ),
                frameRotation = totalFrameRotation,
                scaleX = pendingEdit.scaleX,
                scaleY = pendingEdit.scaleY,
                offsetX = pendingEdit.offsetX,
                offsetY = pendingEdit.offsetY,
                canvasScale = canvasScale,
                canvasOffsetX = canvasOffsetX,
                canvasOffsetY = canvasOffsetY,
                isMidpointRange = isRange,
                hideMidpoints = isTextNum,
                customHalfW = customHw,
                customHalfH = customHh,
                onBodyDrag = onUpdateOffset,
                onCornerScale = { r -> onUpdateScale(r, r) },
                onCornerRotate = onUpdateRotation,
                onMidpointScale = onUpdateScale,
                onMidpointOffset = onUpdateOffset,
                onArrowSpan = onUpdateArrowSpan,
                onRectMidpointDrag = run {
                    val p = pendingEdit.primitive
                    when (p) {
                        is DrawingPrimitive.RectanglePrimitive -> { idx, amount ->
                            // PFO中点索引→矩形边索引: PFO[top,bottom,left,right]=[edge0,edge2,edge3,edge1]
                            // amount 已是世界坐标位移（PFO 传入 r*|h|/cs，含 padding 修正）。
                            // 注意：待确认模式的旋转在 edit.rotation 里，由 worldToScreen 绕 pivot 外部施加，
                            // 存储层只需局部系移动（dragEdge 默认传 p.rotation），
                            // 不能把 edit.rotation 算进 frameRotation，否则中心位移被二次旋转、对边跑偏。
                            val edgeIdx = intArrayOf(0, 2, 3, 1)[idx.coerceIn(0, 3)]
                            onUpdatePrimitive(p.dragEdge(edgeIdx, amount))
                        }
                        is DrawingPrimitive.CirclePrimitive -> { idx, amount ->
                            // 圆/椭圆：idx 即 PFO [top,bottom,left,right]，对向顶点固定
                            onUpdatePrimitive(p.dragEdge(idx, amount))
                        }
                        is DrawingPrimitive.LinePrimitive -> { idx, amount ->
                            // 直线：idx 0=起点侧手柄，1=终点侧手柄；另一端固定
                            val dx = p.endX - p.startX; val dy = p.endY - p.startY
                            val len = sqrt(dx * dx + dy * dy)
                            if (len > 0.001f) {
                                val ux = dx / len; val uy = dy / len
                                onUpdatePrimitive(
                                    if (idx == 0) p.copy(startX = p.startX - ux * amount, startY = p.startY - uy * amount)
                                    else p.copy(endX = p.endX + ux * amount, endY = p.endY + uy * amount)
                                )
                            }
                        }
                        else -> null
                    }
                },
                onHandleActiveChanged = onHandleActiveChanged,
                midpointOverrides = peLineMidpoints,
                swapMidpointAxes = peLineMidpoints != null,
            )

            // Primitive preview — 直接读 pendingEdit 参数，重组时 lambda 必然新建触发重绘。
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (!pendingEdit.isActive() || pendingEdit.bounds == null) return@Canvas
                val p = pendingEdit.primitive ?: return@Canvas
                drawPrimitiveAt(p, pendingEdit, csState2.value, coxState.value, coyState.value,
                    glsState.value * p.lineScaleFactor, bdState.value, textMeasurePaint)
            }

            // Confirm / Cancel buttons
            Row(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 64.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val btnSize = 36.dp; val capsuleShape = RoundedCornerShape(btnSize / 2)
                FloatingActionButton(onClick = onCancel, modifier = Modifier.size(btnSize),
                    containerColor = Color(0xFF757575), contentColor = Color.White, shape = capsuleShape) {
                    Icon(Icons.Default.Close, "取消", modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                FloatingActionButton(onClick = onConfirm, modifier = Modifier.size(btnSize),
                    containerColor = Color(0xFF4CAF50), contentColor = Color.White, shape = capsuleShape) {
                    Icon(Icons.Default.Check, "确认", modifier = Modifier.size(18.dp))
                }
            }
        } else if (showSelection) {
            // ── Selection mode (transform handles only) ──
            // bounds 缺失时按选中图形现算（含 BlockRef 按块内容），防止固定到原点的默认框
            val selBoundsFallback = if (selection.bounds == null && selection.initialBounds == null) {
                var acc: FloatArray? = null
                for (i in selection.selectedIndices) {
                    val p = primitives.getOrNull(i) ?: continue
                    val b = pfoWorldBounds(p, blockDefs, textMeasurePaint) ?: continue
                    acc = if (acc == null) b.copyOf() else floatArrayOf(
                        minOf(acc!![0], b[0]), minOf(acc!![1], b[1]),
                        maxOf(acc!![2], b[2]), maxOf(acc!![3], b[3])
                    )
                }
                acc?.let { Bounds(it[0], it[1], it[2], it[3]) }
            } else null
            val bbox = selection.bounds ?: selection.initialBounds ?: selBoundsFallback ?: Bounds(0f, 0f, 100f, 100f)
            val selPivotSrc = selection.initialBounds ?: selection.bounds ?: selBoundsFallback
            // For single selection, compute tight half-dimensions matching the primitive's intrinsic size.
            // This avoids the double-rotation conflict between computeBounds (returns AABB) and PFO rotation.
            val selCustomPair = if (selection.selectedIndices.size == 1) {
                val idx = selection.selectedIndices.first()
                val p = primitives.getOrNull(idx)
                p?.let { computePrimitiveHalfDims(it, selection.selScaleX, selection.selScaleY, globalLineScale, canvasScale, minHalf, textMeasurePaint, blockDefs) }
            } else null
            // For single-select line: only 2 perpendicular midpoints (along the line's long edges)
            val lineMidpoints = if (selCustomPair != null && selection.selectedIndices.size == 1) {
                val idx = selection.selectedIndices.first()
                val p = primitives.getOrNull(idx)
                if (p is DrawingPrimitive.LinePrimitive) {
                    val cosR = kotlin.math.cos(selection.rotation)
                    val sinR = kotlin.math.sin(selection.rotation)
                    val cx = (bbox.minX + bbox.maxX) / 2f + selection.selOffsetX
                    val cy = (bbox.minY + bbox.maxY) / 2f + selection.selOffsetY
                    val hw = selCustomPair.first
                    val hh = selCustomPair.second
                    val cs = canvasScale; val cox = canvasOffsetX; val coy = canvasOffsetY
                    fun ws(wx: Float, wy: Float) = Offset(wx * cs + cox, wy * cs + coy)
                    fun ls(lx: Float, ly: Float) =
                        ws(lx * cosR - ly * sinR + cx, lx * sinR + ly * cosR + cy)
                    val centerScreen = ws(cx, cy)
                    val paddingPx = with(density) { 20.dp.toPx() }
                    fun pad(c: Offset): Offset {
                        val dx = c.x - centerScreen.x; val dy = c.y - centerScreen.y
                        val d = kotlin.math.sqrt(dx * dx + dy * dy)
                        return if (d > 0.01f) Offset(c.x + dx / d * paddingPx, c.y + dy / d * paddingPx) else c
                    }
                    listOf(pad(ls(-hw, 0f)), pad(ls(hw, 0f)))
                } else null
            } else null
            // 选择模式 pivot：用初始 bounds 中心（不随拖拽漂移）
            val selPivotX = selPivotSrc?.let { (it.minX + it.maxX) / 2f } ?: 0f
            val selPivotY = selPivotSrc?.let { (it.minY + it.maxY) / 2f } ?: 0f
            // 多选（或无紧致半尺寸的类型如手绘）时，框半尺寸用并集 bounds，保证包裹所有图形
            val selHalfW = selCustomPair?.first ?: ((bbox.maxX - bbox.minX) / 2f)
            val selHalfH = selCustomPair?.second ?: ((bbox.maxY - bbox.minY) / 2f)
            PaddedFrameOverlay(
                // 框中心 = pivot（和待确认模式同一套）
                bounds = Bounds(
                    selPivotX - selHalfW,
                    selPivotY - selHalfH,
                    selPivotX + selHalfW,
                    selPivotY + selHalfH
                ),
                frameRotation = selection.rotation,
                scaleX = selection.selScaleX,
                scaleY = selection.selScaleY,
                offsetX = selection.selOffsetX,
                offsetY = selection.selOffsetY,
                canvasScale = canvasScale,
                canvasOffsetX = canvasOffsetX,
                canvasOffsetY = canvasOffsetY,
                hideMidpoints = selection.hideMidpoints || selection.selectedIndices.size > 1,
                customHalfW = selCustomPair?.first,
                customHalfH = selCustomPair?.second,
                midpointOverrides = lineMidpoints,
                swapMidpointAxes = lineMidpoints != null,
                onBodyDrag = onMoveSelected,
                onCornerScale = { r -> onScaleSelected(r, r) },
                onCornerRotate = { r -> onRotateSelected(r) },
                onMidpointScale = onScaleSelected,
                onMidpointOffset = onMoveSelected,
                // 只有矩形/圆/直线走专用拖拽通道；其他类型（手绘等）必须回退到通用缩放，
                // 否则通道对所有类型非空会把通用缩放吃掉（中间手柄空转无效果）
                onRectMidpointDrag = run {
                    val single = selection.selectedIndices.singleOrNull()?.let { primitives.getOrNull(it) }
                    if (single is DrawingPrimitive.RectanglePrimitive ||
                        single is DrawingPrimitive.CirclePrimitive ||
                        single is DrawingPrimitive.LinePrimitive) onRectMidpointDrag else null
                },
                onTransformEnd = onTransformEnd,
                onHandleActiveChanged = onHandleActiveChanged,
            )

            // Render selected primitives with transform params (when transforming)
            val sel = selState.value
            if (sel != null && sel.isTransforming && sel.selectedIndices.isNotEmpty()) {
                val transformEdit = PendingEdit(
                    active = true,
                    rotation = sel.rotation - sel.startRotation,
                    scaleX = if (sel.startScaleX != 0f) sel.selScaleX / sel.startScaleX else 1f,
                    scaleY = if (sel.startScaleY != 0f) sel.selScaleY / sel.startScaleY else 1f,
                    offsetX = sel.selOffsetX - sel.startX,
                    offsetY = sel.selOffsetY - sel.startY,
                    bounds = sel.bounds,
                    // 旋转中心用 initialBounds（变换开始时记录，不随 midpoint 漂移）
                    pivotX = (sel.initialBounds ?: sel.bounds)?.let { (it.minX + it.maxX) / 2f } ?: 0f,
                    pivotY = (sel.initialBounds ?: sel.bounds)?.let { (it.minY + it.maxY) / 2f } ?: 0f
                )
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // 选中光晕：紧贴元素的淡蓝色辉光（多趟加宽描边），画在变换预览之下
                    for (idx in sel.selectedIndices) {
                        if (idx >= primitives.size) continue
                        val p = primitives[idx]
                        for ((inflatePx, glowAlpha) in SELECTION_GLOW_PASSES) {
                            drawPrimitiveAt(p, transformEdit, csState2.value, coxState.value, coyState.value,
                                glsState.value * p.lineScaleFactor, bdState.value, textMeasurePaint,
                                glowInflatePx = inflatePx, glowColor = SELECTION_GLOW_COLOR.copy(alpha = glowAlpha))
                        }
                    }
                    for (idx in sel.selectedIndices) {
                        if (idx >= primitives.size) continue
                        val p = primitives[idx]
                        drawPrimitiveAt(p, transformEdit, csState2.value, coxState.value, coyState.value,
                            glsState.value * p.lineScaleFactor, bdState.value, textMeasurePaint)
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  Primitive preview drawing
// ═══════════════════════════════════════════════════════════

private fun DrawScope.drawPrimitiveAt(
    primitive: DrawingPrimitive, edit: PendingEdit, canvasScale: Float, canvasOffsetX: Float, canvasOffsetY: Float,
    strokeScale: Float = 1f,
    blockDefs: List<BlockDef> = emptyList(),
    reusablePaint: Paint? = null,
    // 光晕趟（选中高亮）：非 null 时本趟只画淡蓝色辉光，笔画/字形按 glowInflatePx（屏幕 px 单边）加宽
    glowInflatePx: Float? = null,
    glowColor: Color = SELECTION_GLOW_COLOR
) {
    val ox = edit.offsetX; val oy = edit.offsetY
    val sx = edit.scaleX; val sy = edit.scaleY
    val cosR = cos(edit.rotation); val sinR = sin(edit.rotation)
    val alpha = 1f
    // 旋转中心用 pivot（固定轴），确保没动的角点不漂移
    val cx0 = edit.pivotX
    val cy0 = edit.pivotY
    val avgScale = sqrt(abs(sx * sy))
    val glow = glowInflatePx != null
    val gInf = glowInflatePx ?: 0f
    val strokeColor = if (glow) glowColor else primitive.color.copy(alpha = alpha)

    fun worldToScreen(x: Float, y: Float): Offset {
        val lx = (x - cx0) * sx; val ly = (y - cy0) * sy
        val rx = lx * cosR - ly * sinR + cx0 + ox
        val ry = lx * sinR + ly * cosR + cy0 + oy
        return Offset(rx * canvasScale + canvasOffsetX, ry * canvasScale + canvasOffsetY)
    }

    fun lineWSP(x1: Float, y1: Float, x2: Float, y2: Float, color: Color, sw: Float, cap: StrokeCap = StrokeCap.Round) {
        val p1 = worldToScreen(x1, y1); val p2 = worldToScreen(x2, y2)
        drawLine(color, p1, p2, sw, cap)
    }

    when (primitive) {
        is DrawingPrimitive.FreehandPath -> {
            if (primitive.points.size >= 2) {
                val screenPts = primitive.points.map { worldToScreen(it.x, it.y) }
                val modelPts = screenPts.map { Point2D(it.x, it.y) }
                val path = smoothPathFromPoints(modelPts, primitive.isClosed, primitive.sharpCorners)
                if (glow) {
                    val w = maxOf(primitive.strokeWidth * strokeScale * canvasScale, 1.5f * canvasScale) + 2f * gInf
                    drawPath(path, glowColor, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
                } else {
                    drawPathWithStyle(
                        path = path,
                        color = primitive.color.copy(alpha = alpha),
                        strokeWidth = primitive.strokeWidth,
                        lineStyle = primitive.lineStyle,
                        strokeScale = strokeScale * canvasScale,
                        minWidth = 1.5f * canvasScale
                    )
                    if (primitive.lineStyle.type == LineType.LIGHTNING && primitive.points.size >= 2) {
                        drawLightningOnPolyline(modelPts, primitive.isClosed, primitive.color.copy(alpha = alpha), strokeScale * canvasScale)
                    }
                }
            }
        }
        is DrawingPrimitive.RectanglePrimitive -> {
            val cxRect = (primitive.corners.map { it.x }.let { (it.min() + it.max()) / 2f })
            val cyRect = (primitive.corners.map { it.y }.let { (it.min() + it.max()) / 2f })
            val hw = abs(primitive.corners[1].x - primitive.corners[0].x) / 2f
            val hh = abs(primitive.corners[3].y - primitive.corners[0].y) / 2f
            val totalRot = primitive.rotation  // edit.rotation already in worldToScreen
            if (kotlin.math.abs(totalRot) < 0.001f) {
                val p1 = worldToScreen(primitive.corners[0].x, primitive.corners[0].y)
                val p2 = worldToScreen(primitive.corners[1].x, primitive.corners[1].y)
                val p3 = worldToScreen(primitive.corners[2].x, primitive.corners[2].y)
                val p4 = worldToScreen(primitive.corners[3].x, primitive.corners[3].y)
                val path = Path().apply { moveTo(p1.x, p1.y); lineTo(p2.x, p2.y); lineTo(p3.x, p3.y); lineTo(p4.x, p4.y); close() }
                val sw = maxOf(primitive.strokeWidth * strokeScale * canvasScale, 1.5f * canvasScale)
                drawPath(path, strokeColor, style = Stroke(sw + 2f * gInf, cap = StrokeCap.Round, join = StrokeJoin.Round))
            } else {
                val cosR = cos(totalRot); val sinR = sin(totalRot)
                val eCosR = cos(edit.rotation); val eSinR = sin(edit.rotation)
                fun corner(wx: Float, wy: Float): Offset {
                    // Step 1: rotate axis-aligned corner by primitive.rotation around rect center
                    val dx = wx - cxRect; val dy = wy - cyRect
                    val rpx = cxRect + dx * cosR - dy * sinR
                    val rpy = cyRect + dx * sinR + dy * cosR
                    // Step 2: pass through worldToScreen (scale → edit.rotation → offset)
                    val lx = (rpx - cx0) * sx; val ly = (rpy - cy0) * sy
                    val rx = lx * eCosR - ly * eSinR + cx0 + ox
                    val ry = lx * eSinR + ly * eCosR + cy0 + oy
                    return Offset(rx * canvasScale + canvasOffsetX, ry * canvasScale + canvasOffsetY)
                }
                val p1 = corner(cxRect - hw, cyRect - hh)
                val p2 = corner(cxRect + hw, cyRect - hh)
                val p3 = corner(cxRect + hw, cyRect + hh)
                val p4 = corner(cxRect - hw, cyRect + hh)
                val path = Path().apply { moveTo(p1.x, p1.y); lineTo(p2.x, p2.y); lineTo(p3.x, p3.y); lineTo(p4.x, p4.y); close() }
                val sw = maxOf(primitive.strokeWidth * strokeScale * canvasScale, 1.5f * canvasScale)
                drawPath(path, strokeColor, style = Stroke(sw + 2f * gInf, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        is DrawingPrimitive.CirclePrimitive -> {
            // worldToScreen rotates the center around the bounds center (needed for multi-select).
            // nc.rotate handles the ellipse shape rotation around its own center (independent).
            val c = worldToScreen(primitive.centerX, primitive.centerY)
            val drawRx = primitive.radiusX * abs(sx) * canvasScale
            val drawRy = primitive.radiusY * abs(sy) * canvasScale
            val totalRot = primitive.rotation + edit.rotation  // full rotation around own center
            if (kotlin.math.abs(totalRot) < 0.001f) {
                val sw = maxOf(primitive.strokeWidth * strokeScale * canvasScale, 1.5f * canvasScale) + 2f * gInf
                if (abs(drawRx - drawRy) < 0.5f) {
                    drawCircle(strokeColor, drawRx, c, style = Stroke(sw))
                } else {
                    val path = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c.x - drawRx, c.y - drawRy, c.x + drawRx, c.y + drawRy)) }
                    drawPath(path, strokeColor, style = Stroke(sw))
                }
            } else {
                val nc = drawContext.canvas.nativeCanvas
                val paint = android.graphics.Paint().apply {
                    color = strokeColor.toArgb()
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = maxOf(primitive.strokeWidth * strokeScale * canvasScale, 1.5f * canvasScale) + 2f * gInf
                    isAntiAlias = true
                }
                nc.save()
                nc.rotate(totalRot * 180f / kotlin.math.PI.toFloat(), c.x, c.y)
                nc.drawOval(c.x - drawRx, c.y - drawRy, c.x + drawRx, c.y + drawRy, paint)
                nc.restore()
            }
        }
        is DrawingPrimitive.LinePrimitive -> {
            val sw = maxOf(primitive.strokeWidth * strokeScale * canvasScale, 1.5f * canvasScale)
            if (glow) {
                // 光晕趟：连续描边即可（不做虚线/闪电效果）
                lineWSP(primitive.startX, primitive.startY, primitive.endX, primitive.endY,
                    strokeColor, sw + 2f * gInf)
            } else if (primitive.lineStyle.type == LineType.DASHED || primitive.lineStyle.type == LineType.LIGHTNING) {
                val (sx, sy) = worldToScreen(primitive.startX, primitive.startY)
                val (ex, ey) = worldToScreen(primitive.endX, primitive.endY)
                val path = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
                drawPathWithStyle(path, primitive.color.copy(alpha = alpha), sw, primitive.lineStyle,
                    strokeScale = strokeScale * canvasScale, minWidth = 1.5f * canvasScale)
                if (primitive.lineStyle.type == LineType.LIGHTNING) {
                    drawLightningOnPolyline(listOf(Point2D(primitive.startX, primitive.startY), Point2D(primitive.endX, primitive.endY)), false, primitive.color.copy(alpha = alpha), strokeScale * canvasScale)
                }
            } else {
                lineWSP(primitive.startX, primitive.startY, primitive.endX, primitive.endY,
                    primitive.color.copy(alpha = alpha), sw)
            }
        }
        is DrawingPrimitive.TextPrimitive -> {
            val sc = worldToScreen(primitive.x, primitive.y)
            val drawX = sc.x; val drawY = sc.y
            val paint = reusablePaint ?: Paint().apply {
                color = strokeColor.toArgb()
                textSize = primitive.fontSize * 1.3f * canvasScale * avgScale
                typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true; textAlign = Paint.Align.CENTER
            }
            if (reusablePaint != null) {
                paint.color = strokeColor.toArgb()
                paint.textSize = primitive.fontSize * 1.3f * canvasScale * avgScale
            }
            // 光晕趟：字形轮廓加粗（本体随后画在上层，只露一圈淡蓝边）；显式复位避免污染复用 paint
            paint.style = if (glow) Paint.Style.FILL_AND_STROKE else Paint.Style.FILL
            paint.strokeWidth = if (glow) 2f * gInf else 0f
            val nc = drawContext.canvas.nativeCanvas
            // 与 DrawingCanvas 一致：按字体度量垂直居中，避免预览/确认位置偏移
            val yOff = -(paint.ascent() + paint.descent()) / 2f
            val totalRot = primitive.rotation + edit.rotation  // text must rotate around own center
            if (kotlin.math.abs(totalRot) < 0.01f) {
                nc.drawText(primitive.text, drawX, drawY + yOff, paint)
            } else {
                nc.save()
                nc.rotate(totalRot * 180f / kotlin.math.PI.toFloat(), drawX, drawY)
                nc.drawText(primitive.text, drawX, drawY + yOff, paint)
                nc.restore()
            }
        }
        is DrawingPrimitive.NumberLabelPrimitive -> {
            val sc = worldToScreen(primitive.x, primitive.y)
            val drawX = sc.x; val drawY = sc.y
            val paint = reusablePaint ?: Paint().apply {
                color = strokeColor.toArgb()
                textSize = primitive.fontSize * 1.3f * canvasScale * avgScale
                typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true; textAlign = Paint.Align.CENTER
            }
            if (reusablePaint != null) {
                paint.color = strokeColor.toArgb()
                paint.textSize = primitive.fontSize * 1.3f * canvasScale * avgScale
            }
            // 光晕趟：字形轮廓加粗（本体随后画在上层，只露一圈淡蓝边）；显式复位避免污染复用 paint
            paint.style = if (glow) Paint.Style.FILL_AND_STROKE else Paint.Style.FILL
            paint.strokeWidth = if (glow) 2f * gInf else 0f
            val nc = drawContext.canvas.nativeCanvas
            val yOff = primitive.fontSize * 0.4f * canvasScale * avgScale
            val totalRot = primitive.rotation + edit.rotation  // text must rotate around own center
            if (primitive.circled) {
                // 外圈：圆心对齐文字视觉中心（与 DrawingCanvas.drawNumberLabel 一致）
                val textWidth = paint.measureText(primitive.value.toString())
                val fsScaled = primitive.fontSize * canvasScale * avgScale
                val radius = maxOf(textWidth / 2f, fsScaled * 0.65f) * 1.15f
                val cy0 = drawY - fsScaled * 0.055f
                val circlePaint = Paint().apply {
                    color = strokeColor.toArgb()
                    style = Paint.Style.STROKE
                    strokeWidth = maxOf(primitive.strokeWidth * strokeScale * canvasScale, 1.5f * canvasScale) + 2f * gInf
                    isAntiAlias = true
                }
                if (kotlin.math.abs(totalRot) < 0.01f) {
                    nc.drawCircle(drawX, cy0, radius, circlePaint)
                } else {
                    nc.save()
                    nc.rotate(totalRot * 180f / kotlin.math.PI.toFloat(), drawX, drawY)
                    nc.drawCircle(drawX, cy0, radius, circlePaint)
                    nc.restore()
                }
            }
            if (kotlin.math.abs(totalRot) < 0.01f) {
                nc.drawText(primitive.value.toString(), drawX, drawY + yOff, paint)
            } else {
                nc.save()
                nc.rotate(totalRot * 180f / kotlin.math.PI.toFloat(), drawX, drawY)
                nc.drawText(primitive.value.toString(), drawX, drawY + yOff, paint)
                nc.restore()
            }
        }
        is DrawingPrimitive.BlockRefPrimitive -> {
            val bd = blockDefs.find { it.id == primitive.blockDefId } ?: return
            val sc = worldToScreen(primitive.x, primitive.y)
            val nc = drawContext.canvas.nativeCanvas
            // 与 drawBlockRef 同一锚定：平移使内容形心落锚点（平移量在旋转系外计算，不随旋转）
            val centroid = blockContentCentroid(bd.primitives) ?: Point2D(0f, 0f)
            val s = primitive.scale * avgScale * canvasScale
            val totalRot = primitive.rotation + edit.rotation
            val cosB = cos(totalRot); val sinB = sin(totalRot)
            val txC = sc.x - (centroid.x * s * cosB - centroid.y * s * sinB)
            val tyC = sc.y - (centroid.x * s * sinB + centroid.y * s * cosB)
            nc.save()
            nc.translate(txC, tyC)
            nc.rotate(totalRot * 180f / kotlin.math.PI.toFloat())
            nc.scale(s, s)
            // canvasScale 已并入矩阵，drawBlockPrimitive 的 canvasScale 形参传 1 避免笔宽重复缩放
            for (cp in bd.primitives) {
                // 光晕外扩量换算成块内局部单位（矩阵已含 s 缩放）
                drawBlockPrimitive(cp, 1f, strokeScale, blockDefs,
                    glowInflate = glowInflatePx?.let { it / s.coerceAtLeast(0.001f) }, glowColor = glowColor)
            }
            nc.restore()
        }
        is DrawingPrimitive.RangeLabelPrimitive -> {
            val sc = worldToScreen(primitive.x, primitive.y)
            val drawX = sc.x; val drawY = sc.y
            val nc = drawContext.canvas.nativeCanvas
            // 待确认/选择变换：布局旋转角 = 图元旋转 + 编辑旋转（纯函数统一计算）。
            // 屏幕空间计算：x/y、字号、arrowSpan 一并缩放，锚点直接落在屏幕坐标。
            val fs = primitive.fontSize * canvasScale * avgScale
            val spanScaled = primitive.arrowSpan * canvasScale * avgScale
            val storedRotation = primitive.rotation + edit.rotation
            val layout = RangeLabelLayout.compute(storedRotation, drawX, drawY, fs, spanScaled, primitive.reversed,
                numberAngle = RangeLabelLayout.numberAngleFor(primitive.numbersFaceLeft))
            val half = RangeLabelLayout.arrowHalfLength(spanScaled)
            val paint = reusablePaint ?: Paint().apply {
                color = strokeColor.toArgb()
                textSize = fs
                typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true; textAlign = Paint.Align.CENTER
            }
            if (reusablePaint != null) {
                paint.color = strokeColor.toArgb()
                paint.textSize = fs
            }
            paint.style = if (glow) Paint.Style.FILL_AND_STROKE else Paint.Style.FILL
            paint.strokeWidth = if (glow) 2f * gInf else 0f
            // Arrow line + arrowhead（局部 (-half,0)→(+half,0) 绕中心按 arrowAngle 旋转）
            val cosA = cos(layout.arrowAngle); val sinA = sin(layout.arrowAngle)
            val ax1x = drawX - half * cosA; val ax1y = drawY - half * sinA
            val ax2x = drawX + half * cosA; val ax2y = drawY + half * sinA
            val sw = maxOf(2f * strokeScale * canvasScale, 1.5f * canvasScale) + 2f * gInf
            drawLine(strokeColor, Offset(ax1x, ax1y), Offset(ax2x, ax2y), sw)
            val hs = maxOf(maxOf(4f, primitive.fontSize * 0.3f * avgScale) * canvasScale, 1.5f * canvasScale)
            val tipX = if (primitive.reversed) ax1x else ax2x
            val tipY = if (primitive.reversed) ax1y else ax2y
            // 翼公式统一走 RangeLabelLayout（与画布渲染、DXF 导出同源）
            val (wing1, wing2) = RangeLabelLayout.arrowheadWingOffsets(layout.arrowAngle, primitive.reversed)
            drawLine(strokeColor, Offset(tipX, tipY), Offset(tipX + hs * wing1.first, tipY + hs * wing1.second), sw)
            drawLine(strokeColor, Offset(tipX, tipY), Offset(tipX + hs * wing2.first, tipY + hs * wing2.second), sw)
            // 两端数字：锚点与绘制角由纯函数给出（朝下=正向 0 / 朝左=+90°）
            val label1 = if (primitive.reversed) primitive.endValue.toString() else primitive.startValue.toString()
            val label2 = if (primitive.reversed) primitive.startValue.toString() else primitive.endValue.toString()
            fun drawRangeTextAt(text: String, anchor: com.scheda.app.model.RangeTextPlacement) {
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
            drawRangeTextAt(label1, layout.startText)
            drawRangeTextAt(label2, layout.endText)
        }
    }
}

private fun DrawScope.drawBlockPrimitive(
    p: DrawingPrimitive, canvasScale: Float, strokeScale: Float, blockDefs: List<BlockDef>,
    glowInflate: Float? = null, glowColor: Color = SELECTION_GLOW_COLOR
) {
    val glow = glowInflate != null
    val gInf = glowInflate ?: 0f
    when (p) {
        is DrawingPrimitive.LinePrimitive -> {
            if (glow) drawLine(glowColor, Offset(p.startX, p.startY), Offset(p.endX, p.endY), 2f + 2f * gInf, StrokeCap.Round)
            else drawLine(p.color, Offset(p.startX.toFloat(), p.startY.toFloat()), Offset(p.endX.toFloat(), p.endY.toFloat()), 2f)
        }
        is DrawingPrimitive.RectanglePrimitive -> {
            val xs = p.corners.map { it.x }; val ys = p.corners.map { it.y }
            val l = xs.min(); val t = ys.min()
            val r = xs.max(); val b = ys.max()
            drawRect(if (glow) glowColor else p.color, Offset(l, t), androidx.compose.ui.geometry.Size(r-l, b-t), style = Stroke(2f + 2f * gInf))
        }
        is DrawingPrimitive.CirclePrimitive -> {
            val r = maxOf(p.radiusX, p.radiusY)
            drawCircle(if (glow) glowColor else p.color, r, Offset(p.centerX, p.centerY), style = Stroke(2f + 2f * gInf))
        }
        is DrawingPrimitive.TextPrimitive -> {
            val nc = drawContext.canvas.nativeCanvas
            val paint = android.graphics.Paint().apply {
                color = (if (glow) glowColor else p.color).toArgb(); textSize = p.fontSize * 1.3f
                typeface = android.graphics.Typeface.DEFAULT_BOLD; isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER
                if (glow) { style = android.graphics.Paint.Style.FILL_AND_STROKE; strokeWidth = 2f * gInf }
            }
            nc.drawText(p.text, p.x, p.y + p.fontSize * 0.4f, paint)
        }
        is DrawingPrimitive.NumberLabelPrimitive -> {
            val nc = drawContext.canvas.nativeCanvas
            val paint = android.graphics.Paint().apply {
                color = (if (glow) glowColor else p.color).toArgb(); textSize = p.fontSize * 1.3f
                typeface = android.graphics.Typeface.DEFAULT_BOLD; isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER
                if (glow) { style = android.graphics.Paint.Style.FILL_AND_STROKE; strokeWidth = 2f * gInf }
            }
            if (p.circled) {
                val radius = maxOf(paint.measureText(p.value.toString()) / 2f, p.fontSize * 0.65f) * 1.15f
                val circlePaint = android.graphics.Paint().apply {
                    color = (if (glow) glowColor else p.color).toArgb(); style = android.graphics.Paint.Style.STROKE
                    strokeWidth = maxOf(p.strokeWidth, 1.5f) + 2f * gInf; isAntiAlias = true
                }
                nc.drawCircle(p.x, p.y - p.fontSize * 0.055f, radius, circlePaint)
            }
            nc.drawText(p.value.toString(), p.x, p.y + p.fontSize * 0.4f, paint)
        }
        is DrawingPrimitive.FreehandPath -> {
            if (p.points.size >= 2) {
                val modelPts = p.points.map { Point2D(it.x, it.y) }
                val path = smoothPathFromPoints(modelPts, p.isClosed)
                if (glow) {
                    val w = maxOf(p.strokeWidth * strokeScale * canvasScale, 1.5f * canvasScale) + 2f * gInf
                    drawPath(path, glowColor, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
                } else {
                    drawPathWithStyle(
                        path = path,
                        color = p.color,
                        strokeWidth = p.strokeWidth,
                        lineStyle = p.lineStyle,
                        strokeScale = strokeScale * canvasScale,
                        minWidth = 1.5f * canvasScale
                    )
                    if (p.lineStyle.type == com.scheda.app.model.LineType.LIGHTNING) {
                        drawLightningOnPolyline(modelPts, p.isClosed, p.color, strokeScale * canvasScale)
                    }
                }
            }
        }
        else -> {}
    }
}

/** Compute tight half-dimensions for a primitive's selection/pending-edit frame.
 *  Returns (halfW, halfH) for actual dimensions, or null to let PFO fall back to bounds+minHalf.
 *  [scaleX]/[scaleY] 为 PFO 双轴向缩放：椭圆/矩形单轴拉伸时框架必须按各自轴向走，
 *  不能用 sqrt(sx·sy) 均匀值，否则框架四向膨胀、元素超出包围盒。
 *  minHalf is in units of canvasScale (i.e., world units), ~30dp worth. */
private fun computePrimitiveHalfDims(
    primitive: DrawingPrimitive, scaleX: Float, scaleY: Float,
    globalLineScale: Float, canvasScale: Float,
    minHalf: Float, textPaint: Paint,
    blockDefs: List<BlockDef> = emptyList()
): Pair<Float, Float>? {
    // 文字/区间/直线/图块按面积等比缩放
    val scaleFactor = sqrt(abs(scaleX * scaleY))
    return when (primitive) {
        is DrawingPrimitive.TextPrimitive -> {
            // 文字大小不受线型比例影响，PFO 框与渲染一致
            textPaint.textSize = primitive.fontSize * scaleFactor * 1.3f
            val tw = textPaint.measureText(primitive.text)
            val fm = textPaint.fontMetrics
            Pair(
                maxOf(tw / 2f, minHalf / canvasScale),
                maxOf((fm.descent - fm.ascent) / 2f, minHalf / canvasScale)
            )
        }
        is DrawingPrimitive.NumberLabelPrimitive -> {
            textPaint.textSize = primitive.fontSize * scaleFactor * 1.3f
            val tw = textPaint.measureText(primitive.value.toString())
            val fm = textPaint.fontMetrics
            var hw = maxOf(tw / 2f, minHalf / canvasScale)
            var hh = maxOf((fm.descent - fm.ascent) / 2f, minHalf / canvasScale)
            if (primitive.circled) {
                // 选择框要包住外圈圆
                val r = maxOf(tw / 2f, primitive.fontSize * scaleFactor * 0.65f) * 1.15f
                hw = maxOf(hw, r); hh = maxOf(hh, r)
            }
            Pair(hw, hh)
        }
        is DrawingPrimitive.RangeLabelPrimitive -> {
            textPaint.textSize = primitive.fontSize * scaleFactor
            val tw1 = textPaint.measureText(primitive.startValue.toString())
            val tw2 = textPaint.measureText(primitive.endValue.toString())
            val fm = textPaint.fontMetrics
            val arrowLen = maxOf(80f * primitive.arrowSpan, 20f) * scaleFactor
            val gap = primitive.fontSize * scaleFactor
            val textHh = (fm.descent - fm.ascent) / 2f
            // 朝左（数字转 90°）时沿轴/垂直方向占用互换，框保持紧贴
            val hw = if (primitive.numbersFaceLeft) arrowLen / 2f + gap + textHh
                else arrowLen / 2f + maxOf(tw1, tw2) / 2f + gap
            val hh = if (primitive.numbersFaceLeft) maxOf(tw1, tw2) / 2f
                else textHh
            Pair(
                maxOf(hw, minHalf / canvasScale),
                maxOf(hh, minHalf / canvasScale)
            )
        }
        is DrawingPrimitive.RectanglePrimitive -> {
            val xs = primitive.corners.map { it.x }; val ys = primitive.corners.map { it.y }
            val hw = (xs.max() - xs.min()) / 2f; val hh = (ys.max() - ys.min()) / 2f
            Pair(
                maxOf(hw * abs(scaleX), minHalf / canvasScale),
                maxOf(hh * abs(scaleY), minHalf / canvasScale)
            )
        }
        is DrawingPrimitive.CirclePrimitive -> {
            Pair(
                maxOf(primitive.radiusX * abs(scaleX), minHalf / canvasScale),
                maxOf(primitive.radiusY * abs(scaleY), minHalf / canvasScale)
            )
        }
        is DrawingPrimitive.BlockRefPrimitive -> {
            // 按块实际内容算半尺寸（未旋转内容 AABB × scale），框随 frameRotation 贴合
            val cb = blockDefs.find { it.id == primitive.blockDefId }
                ?.primitives?.let { unionAabb(it, textPaint) }
            if (cb != null) {
                Pair(
                    maxOf((cb[2] - cb[0]) / 2f * primitive.scale * scaleFactor, minHalf / canvasScale),
                    maxOf((cb[3] - cb[1]) / 2f * primitive.scale * scaleFactor, minHalf / canvasScale)
                )
            } else {
                val s = maxOf(50f * primitive.scale * scaleFactor / 2f, minHalf / canvasScale)
                Pair(s, s)
            }
        }
        is DrawingPrimitive.LinePrimitive -> {
            val dx = primitive.endX - primitive.startX
            val dy = primitive.endY - primitive.startY
            val halfLen = sqrt(dx * dx + dy * dy) / 2f * scaleFactor
            val halfH = maxOf(15f * scaleFactor, minHalf / canvasScale)
            Pair(maxOf(halfLen, minHalf / canvasScale), halfH)
        }
        // FreehandPath → use bounds fallback
        else -> null
    }
}


/** 一组基元的并集 AABB（BlockDef 内容等），无内容返回 null */
private fun unionAabb(prims: List<DrawingPrimitive>, paint: Paint): FloatArray? {
    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
    var has = false
    for (p in prims) {
        val b = p.computeBounds(paint) ?: continue
        has = true
        minX = minOf(minX, b[0]); minY = minOf(minY, b[1])
        maxX = maxOf(maxX, b[2]); maxY = maxOf(maxY, b[3])
    }
    return if (has) floatArrayOf(minX, minY, maxX, maxY) else null
}

/** 单个基元的世界 AABB；BlockRef 按块实际内容经渲染同款变换计算，其余走 computeBounds */
private fun pfoWorldBounds(p: DrawingPrimitive, blockDefs: List<BlockDef>, paint: Paint): FloatArray? {
    if (p is DrawingPrimitive.BlockRefPrimitive) {
        val bd = blockDefs.find { it.id == p.blockDefId } ?: return null
        val cb = unionAabb(bd.primitives, paint) ?: return null
        // 与 drawBlockRef 一致：内容缩放 → 绕原点旋转 → 平移使内容形心落锚点（用同款形心，非 AABB 中心）
        val centroid = blockContentCentroid(bd.primitives) ?: return null
        val cx = centroid.x; val cy = centroid.y
        val tx = p.x - cx * p.scale; val ty = p.y - cy * p.scale
        val cr = cos(p.rotation); val sr = sin(p.rotation)
        fun mp(wx: Float, wy: Float): Point2D {
            val sx = wx * p.scale; val sy = wy * p.scale
            return Point2D(sx * cr - sy * sr + tx, sx * sr + sy * cr + ty)
        }
        val pts = listOf(mp(cb[0], cb[1]), mp(cb[2], cb[1]), mp(cb[2], cb[3]), mp(cb[0], cb[3]))
        return floatArrayOf(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
    }
    // 矩形的 computeBounds 不含旋转，多选合并框需要含旋转的世界 AABB
    if (p is DrawingPrimitive.RectanglePrimitive) return p.worldBounds()
    return p.computeBounds(paint)
}
