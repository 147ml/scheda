package com.scheda.app.ui.canvas

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.scheda.app.ui.components.glassToolbarInset
import com.scheda.app.model.Bounds
import com.scheda.app.model.DrawingPrimitive
import com.scheda.app.model.LineType
import com.scheda.app.model.PendingEdit
import com.scheda.app.model.Point2D
import com.scheda.app.model.RangeLabelLayout
import com.scheda.app.model.SelectionState
import com.scheda.app.model.blockContentCentroid
import com.scheda.app.model.blockMapPoint
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
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
    rectDragHandleWorld: Offset? = null,
    onTransformEnd: () -> Unit = {},
    onHandleActiveChanged: (Boolean) -> Unit = {},
    /** 选择态：单选直线拖端点（直接写回几何，不走变换参数） */
    onMoveSelectedLineEndpoint: (isStart: Boolean, x: Float, y: Float) -> Unit = { _, _, _ -> },
    /** 选择态：一次拖动结束（端点球与线身共用收尾：结束变换会话 + 落盘） */
    onSelectionDragEnd: () -> Unit = {},
    // 正交锁定：旋转角手柄点击 90°（待确认态 / 选择态两套回调）
    orthoStep: Boolean = false,
    onStepRotatePending: (Float) -> Unit = {},
    onStepRotateSelected: (Float) -> Unit = {},
    primitives: List<DrawingPrimitive> = emptyList(),
    // ── Shared params ──
    canvasBackdrop: LayerBackdrop? = null,
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

    // ── 待确认矩形中点拖拽 锁定状态（2026-10-13，越界翻转修复）──
    // 会话=400ms 无事件视为结束（待确认模式无显式拖动会话边界）。
    // 首帧规范化 corners + 锁定被拖边身份；后续帧只动锁定两点/带符号量，越过对边自然翻转。
    var peRectDragTime by remember { mutableStateOf(0L) }
    var peRectDragIdx by remember { mutableStateOf(-1) }
    var peRectDragPair by remember { mutableStateOf<IntArray?>(null) }
    var peRectDragNx by remember { mutableStateOf(0f) }
    var peRectDragNy by remember { mutableStateOf(0f) }
    var peRectDragRotStat by remember { mutableStateOf<FloatArray?>(null) }
    var peRectDragRotNx by remember { mutableStateOf(0f) }
    var peRectDragRotNy by remember { mutableStateOf(0f) }
    var peRectDragHandle by remember { mutableStateOf<Offset?>(null) }

    fun peRectDragApply(pfoIdx: Int, amount: Float) {
        var p = pendingEdit.primitive as? DrawingPrimitive.RectanglePrimitive ?: return
        val now = System.currentTimeMillis()
        val newSession = now - peRectDragTime > 400L || peRectDragIdx != pfoIdx
        peRectDragTime = now
        if (newSession) {
            peRectDragIdx = pfoIdx
            val edgeIdx = intArrayOf(0, 2, 3, 1)[pfoIdx.coerceIn(0, 3)]
            val norm0 = p.normalizedCorners()
            if (norm0.size == 4) {
                p = p.copy(corners = norm0)
                onUpdatePrimitive(p)
            }
            peRectDragPair = intArrayOf(edgeIdx, (edgeIdx + 1) % 4)
            val lnx: Float; val lny: Float
            when (edgeIdx) {
                0 -> { lnx = 0f; lny = -1f }
                1 -> { lnx = 1f; lny = 0f }
                2 -> { lnx = 0f; lny = 1f }
                else -> { lnx = -1f; lny = 0f }
            }
            val rot = p.rotation
            if (abs(rot) < 0.001f) {
                peRectDragRotStat = null
                peRectDragNx = lnx; peRectDragNy = lny
            } else {
                val cosP = cos(rot); val sinP = sin(rot)
                peRectDragNx = lnx * cosP - lny * sinP
                peRectDragNy = lnx * sinP + lny * cosP
                val xs = norm0.map { it.x }; val ys = norm0.map { it.y }
                val cAx = (xs.min() + xs.max()) / 2f; val cAy = (ys.min() + ys.max()) / 2f
                val hx0 = (xs.max() - xs.min()) / 2f; val hy0 = (ys.max() - ys.min()) / 2f
                peRectDragRotStat = floatArrayOf(
                    cAx, cAy,
                    if (edgeIdx == 3) -hx0 else hx0,
                    if (edgeIdx == 0) -hy0 else hy0
                )
                peRectDragRotNx = lnx; peRectDragRotNy = lny
            }
        }
        val rotStat = peRectDragRotStat
        val pair = peRectDragPair
        val np: DrawingPrimitive.RectanglePrimitive
        if (rotStat != null) {
            // 旋转：边沿世界法向移 amount ⇔ 局部系：中心 += nLocal·(amount/2)；带符号半宽 += amount/2
            val half = amount / 2f
            val cAx = rotStat[0] + peRectDragRotNx * half
            val cAy = rotStat[1] + peRectDragRotNy * half
            val hx = rotStat[2] + (if (peRectDragRotNx != 0f) half else 0f)
            val hy = rotStat[3] + (if (peRectDragRotNy != 0f) half else 0f)
            peRectDragRotStat = floatArrayOf(cAx, cAy, hx, hy)
            np = p.copy(corners = listOf(
                Point2D(cAx - hx, cAy - hy), Point2D(cAx + hx, cAy - hy),
                Point2D(cAx + hx, cAy + hy), Point2D(cAx - hx, cAy + hy)
            ))
        } else if (pair != null) {
            // 未旋转：锁定两点沿首帧法向移动（允许交叉=负宽翻转，边身份持续）
            val nc = p.corners.toMutableList()
            if (nc.size == 4) {
                for (k in pair) {
                    val pt = nc[k]
                    nc[k] = Point2D(pt.x + peRectDragNx * amount, pt.y + peRectDragNy * amount)
                }
            }
            np = p.copy(corners = nc)
        } else return
        onUpdatePrimitive(np)
        // 被拖边中点（世界/存储坐标），供 PFO 让被拖手柄跟随显示
        val hRot = np.rotation
        peRectDragHandle = if (abs(hRot) < 0.001f) {
            val pr = peRectDragPair
            if (pr == null) null else {
                val a = np.corners[pr[0]]; val b = np.corners[pr[1]]
                Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
            }
        } else {
            val s = peRectDragRotStat
            if (s == null) null else {
                val mx = if (peRectDragRotNx != 0f) s[0] + s[2] else s[0]
                val my = if (peRectDragRotNy != 0f) s[1] + s[3] else s[1]
                val cosP = cos(hRot); val sinP = sin(hRot)
                val dx = mx - s[0]; val dy = my - s[1]
                Offset(s[0] + dx * cosP - dy * sinP, s[1] + dx * sinP + dy * cosP)
            }
        }
    }

    // ── Pending-edit specific values ──
    val minHalf = with(density) { 30.dp.toPx() }
    val textMeasurePaint = Paint().apply {
        typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true; textAlign = Paint.Align.CENTER
    }
    val (customHw, customHh) = if (showPending) {
        val pe = pendingEdit
        val dims = pe.primitive?.let { computePrimitiveHalfDims(it, pe.scaleX, pe.scaleY, globalLineScale, canvasScale, minHalf, textMeasurePaint, blockDefs) }
        Pair(dims?.first, dims?.second)
    } else null to null

    val primFrameRot = if (showPending) pendingEdit.primitive?.intrinsicRotation ?: 0f else 0f
    val totalFrameRotation = if (showPending) pendingEdit.rotation + primFrameRot else 0f

    val isTextNum = showPending && (pendingEdit.primitive is DrawingPrimitive.TextPrimitive ||
        pendingEdit.primitive is DrawingPrimitive.NumberLabelPrimitive)
    val isRange = showPending && pendingEdit.primitive is DrawingPrimitive.RangeLabelPrimitive

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
            val peIsLine = pePrim is DrawingPrimitive.LinePrimitive
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
            if (!peIsLine) {
                PaddedFrameOverlay(
                    backdrop = canvasBackdrop,
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
                    orthoStep = orthoStep,
                    onStepRotate = onStepRotatePending,
                    onMidpointScale = onUpdateScale,
                    onMidpointOffset = onUpdateOffset,
                    onArrowSpan = onUpdateArrowSpan,
                    onRectMidpointDrag = run {
                        val p = pendingEdit.primitive
                        when (p) {
                            is DrawingPrimitive.RectanglePrimitive -> { idx, amount ->
                                // 锁定版（2026-10-13）：规范化两点索引+带符号半宽，越过对边自然翻转。
                                // 坐标系与原来一致：待确认旋转在 edit.rotation（worldToScreen 外部施加），
                                // 存储层只动 p.rotation 坐标系内的量。
                                peRectDragApply(idx, amount)
                            }
                            is DrawingPrimitive.CirclePrimitive -> { idx, amount ->
                                // 圆/椭圆：idx 即 PFO [top,bottom,left,right]，对向顶点固定
                                val np = p.dragEdge(idx, amount)
                                onUpdatePrimitive(np)
                                // 手柄跟随被拖顶点（带符号半径）：拉过圆心时手柄连续穿过、不跳对面
                                val ci = idx.coerceIn(0, 3)
                                peRectDragHandle = when (ci) {
                                    0 -> Offset(np.centerX, np.centerY - np.rawRadiusY)
                                    1 -> Offset(np.centerX, np.centerY + np.rawRadiusY)
                                    2 -> Offset(np.centerX - np.rawRadiusX, np.centerY)
                                    else -> Offset(np.centerX + np.rawRadiusX, np.centerY)
                                }
                            }
                            else -> null
                        }
                    },
                    onHandleActiveChanged = onHandleActiveChanged,
                    dragHandleWorld = peRectDragHandle,
                )
            }

            // Primitive preview — 直接读 pendingEdit 参数，重组时 lambda 必然新建触发重绘。
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (!pendingEdit.isActive() || pendingEdit.bounds == null) return@Canvas
                val p = pendingEdit.primitive ?: return@Canvas
                drawPrimitiveAt(p, pendingEdit, csState2.value, coxState.value, coyState.value,
                    glsState.value * p.lineScaleFactor, bdState.value, textMeasurePaint)
            }

            // 直线：两个圆形玻璃端点压在线的两端，各自独立拖动 / 吸附。
            // 画在预览之上（球压住线端），替代整套框与旋转、缩放、中点手柄。
            if (peIsLine) {
                val line = pePrim as? DrawingPrimitive.LinePrimitive
                if (line != null) {
                    LineEndpointOverlay(
                        line = line,
                        offsetX = pendingEdit.offsetX,
                        offsetY = pendingEdit.offsetY,
                        canvasScale = canvasScale,
                        canvasOffsetX = canvasOffsetX,
                        canvasOffsetY = canvasOffsetY,
                        primitives = primitives,
                        backdrop = canvasBackdrop,
                        onMoveEndpoint = { isStart, x, y ->
                            val cur = pendingEdit.primitive as? DrawingPrimitive.LinePrimitive
                            if (cur != null) {
                                onUpdatePrimitive(
                                    if (isStart) cur.copy(startX = x, startY = y)
                                    else cur.copy(endX = x, endY = y)
                                )
                            }
                        },
                        onBodyDrag = onUpdateOffset,
                        onHandleActiveChanged = onHandleActiveChanged
                    )
                }
            }

            // 方向键微调（游戏手柄十字布局）：单击=精准一步、长按=加速连续移动（上限 16×）
            NudgePad(
                onNudge = { dx, dy -> onUpdateOffset(dx, dy) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = glassToolbarInset() + 52.dp),   // 与选择模式微调键同高（2026-10-14）
                backdrop = canvasBackdrop
            )

            // Confirm / Cancel buttons
            Row(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = glassToolbarInset() + 64.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val btnSize = 36.dp
                // 2026-10-14：取消/确认改为液态玻璃圆键（参照微调键样式）；无玻璃时回退纯色
                val cancelMod = if (canvasBackdrop != null) {
                    Modifier.size(btnSize).drawBackdrop(
                        backdrop = canvasBackdrop,
                        shape = { CircleShape },
                        effects = {
                            vibrancy()
                            blur(2f.dp.toPx())
                            lens(12f.dp.toPx(), 24f.dp.toPx())
                        },
                        onDrawSurface = { drawRect(Color(0x0AFFFFFF)) }
                    )
                } else Modifier.size(btnSize).clip(CircleShape).background(Color(0xFF757575))
                Box(cancelMod.clickable { onCancel() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Close, "取消", modifier = Modifier.size(18.dp),
                        tint = if (canvasBackdrop != null) Color(0xFFD32F2F) else Color.White)
                }
                Spacer(Modifier.width(12.dp))
                val confirmMod = if (canvasBackdrop != null) {
                    Modifier.size(btnSize).drawBackdrop(
                        backdrop = canvasBackdrop,
                        shape = { CircleShape },
                        effects = {
                            vibrancy()
                            blur(2f.dp.toPx())
                            lens(12f.dp.toPx(), 24f.dp.toPx())
                        },
                        onDrawSurface = { drawRect(Color(0x0AFFFFFF)) }
                    )
                } else Modifier.size(btnSize).clip(CircleShape).background(Color(0xFF4CAF50))
                Box(confirmMod.clickable { onConfirm() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Check, "确认", modifier = Modifier.size(18.dp),
                        tint = if (canvasBackdrop != null) Color(0xFF2E7D32) else Color.White)
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
            // 单选直线：与画线待确认同一套界面（两个端点球），不出框、不出旋转/缩放手柄
            val singleLineIdx = selection.selectedIndices.singleOrNull()
                ?.takeIf { primitives.getOrNull(it) is DrawingPrimitive.LinePrimitive }
            if (singleLineIdx == null) {
            // For single selection, compute tight half-dimensions matching the primitive's intrinsic size.
            // This avoids the double-rotation conflict between computeBounds (returns AABB) and PFO rotation.
            val selCustomPair = if (selection.selectedIndices.size == 1) {
                val idx = selection.selectedIndices.first()
                val p = primitives.getOrNull(idx)
                p?.let { computePrimitiveHalfDims(it, selection.selScaleX, selection.selScaleY, globalLineScale, canvasScale, minHalf, textMeasurePaint, blockDefs) }
            } else null
            // 选择模式 pivot：用初始 bounds 中心（不随拖拽漂移）
            val selPivotX = selPivotSrc?.let { (it.minX + it.maxX) / 2f } ?: 0f
            val selPivotY = selPivotSrc?.let { (it.minY + it.maxY) / 2f } ?: 0f
            // 多选（或无紧致半尺寸的类型如手绘）时，框半尺寸用并集 bounds，保证包裹所有图形
            val selHalfW = selCustomPair?.first ?: ((bbox.maxX - bbox.minX) / 2f)
            val selHalfH = selCustomPair?.second ?: ((bbox.maxY - bbox.minY) / 2f)
            PaddedFrameOverlay(
                backdrop = canvasBackdrop,
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
                onBodyDrag = onMoveSelected,
                onCornerScale = { r -> onScaleSelected(r, r) },
                onCornerRotate = { r -> onRotateSelected(r) },
                orthoStep = orthoStep,
                onStepRotate = onStepRotateSelected,
                onMidpointScale = onScaleSelected,
                onMidpointOffset = onMoveSelected,
                // 只有矩形/圆走专用拖拽通道（单选直线已走上方的端点球分支）；
                // 其他类型（手绘等）必须回退到通用缩放，否则通道对所有类型非空会把通用缩放吃掉
                onRectMidpointDrag = run {
                    val single = selection.selectedIndices.singleOrNull()?.let { primitives.getOrNull(it) }
                    if (single is DrawingPrimitive.RectanglePrimitive ||
                        single is DrawingPrimitive.CirclePrimitive) onRectMidpointDrag else null
                },
                onTransformEnd = onTransformEnd,
                onHandleActiveChanged = onHandleActiveChanged,
                dragHandleWorld = rectDragHandleWorld,
            )
            } else {
                // 单选直线：两个端点球（与画线待确认同一套界面）。
                // 拖端点改长度/角度，拖线身整体平移；松手统一走 onSelectionDragEnd 收尾落盘。
                val line = primitives[singleLineIdx] as DrawingPrimitive.LinePrimitive
                LineEndpointOverlay(
                    line = line,
                    offsetX = selection.selOffsetX,
                    offsetY = selection.selOffsetY,
                    canvasScale = canvasScale,
                    canvasOffsetX = canvasOffsetX,
                    canvasOffsetY = canvasOffsetY,
                    primitives = primitives,
                    excludeIndex = singleLineIdx,
                    backdrop = canvasBackdrop,
                    onMoveEndpoint = { isStart, x, y -> onMoveSelectedLineEndpoint(isStart, x, y) },
                    onBodyDrag = onMoveSelected,
                    onHandleActiveChanged = { active ->
                        onHandleActiveChanged(active)
                        if (!active) onSelectionDragEnd()
                    }
                )
            }

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
            // 屏幕坐标四角（虚线/闪电都基于这份角点，保证与路径完全贴合）
            val cornersScreen: List<Offset> = if (kotlin.math.abs(totalRot) < 0.001f) {
                listOf(
                    worldToScreen(primitive.corners[0].x, primitive.corners[0].y),
                    worldToScreen(primitive.corners[1].x, primitive.corners[1].y),
                    worldToScreen(primitive.corners[2].x, primitive.corners[2].y),
                    worldToScreen(primitive.corners[3].x, primitive.corners[3].y)
                )
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
                listOf(corner(cxRect - hw, cyRect - hh), corner(cxRect + hw, cyRect - hh),
                    corner(cxRect + hw, cyRect + hh), corner(cxRect - hw, cyRect + hh))
            }
            val path = Path().apply {
                moveTo(cornersScreen[0].x, cornersScreen[0].y)
                lineTo(cornersScreen[1].x, cornersScreen[1].y)
                lineTo(cornersScreen[2].x, cornersScreen[2].y)
                lineTo(cornersScreen[3].x, cornersScreen[3].y)
                close()
            }
            val sw = maxOf(primitive.strokeWidth * strokeScale * canvasScale, 1.5f * canvasScale)
            if (glow) {
                // 光晕趟：连续描边即可（不做虚线/闪电效果）
                drawPath(path, strokeColor, style = Stroke(sw + 2f * gInf, cap = StrokeCap.Round, join = StrokeJoin.Round))
            } else {
                // 与画布渲染同源：strokeWidth 传世界单位，缩放交给 strokeScale（内部只乘一次 canvasScale）
                drawPathWithStyle(
                    path = path,
                    color = primitive.color.copy(alpha = alpha),
                    strokeWidth = primitive.strokeWidth,
                    lineStyle = primitive.lineStyle,
                    strokeScale = strokeScale * canvasScale,
                    minWidth = 1.5f * canvasScale
                )
                if (primitive.lineStyle.type == LineType.LIGHTNING) {
                    val xs = strokeScale * canvasScale
                    for (i in 0 until 4) {
                        drawLightningXMarks(cornersScreen[i], cornersScreen[(i + 1) % 4],
                            primitive.color.copy(alpha = alpha), xs)
                    }
                }
            }
        }
        is DrawingPrimitive.CirclePrimitive -> {
            // worldToScreen rotates the center around the bounds center (needed for multi-select).
            // 椭圆自身旋转（primitive.rotation + edit.rotation）由采样路径承担。
            val c = worldToScreen(primitive.centerX, primitive.centerY)
            val drawRx = primitive.radiusX * abs(sx) * canvasScale
            val drawRy = primitive.radiusY * abs(sy) * canvasScale
            val totalRot = primitive.rotation + edit.rotation  // full rotation around own center
            val cosT = cos(totalRot); val sinT = sin(totalRot)
            val sw = maxOf(primitive.strokeWidth * strokeScale * canvasScale, 1.5f * canvasScale)
            // 统一采样成折线路径：addOval 无法带旋转，而虚线(PathEffect)与闪电(X 标记)
            // 都需要“沿路径度量”的语义，采样后才能与画布最终渲染一致
            val steps = 96
            val path = Path().apply {
                for (i in 0..steps) {
                    val a = (i.toFloat() / steps) * 2f * kotlin.math.PI.toFloat()
                    val lx = drawRx * cos(a); val ly = drawRy * sin(a)
                    val px = c.x + lx * cosT - ly * sinT
                    val py = c.y + lx * sinT + ly * cosT
                    if (i == 0) moveTo(px, py) else lineTo(px, py)
                }
                close()
            }
            if (glow) {
                // 光晕趟：连续描边即可（不做虚线/闪电效果）
                drawPath(path, strokeColor, style = Stroke(sw + 2f * gInf, cap = StrokeCap.Round, join = StrokeJoin.Round))
            } else {
                drawPathWithStyle(
                    path = path,
                    color = primitive.color.copy(alpha = alpha),
                    strokeWidth = primitive.strokeWidth,
                    lineStyle = primitive.lineStyle,
                    strokeScale = strokeScale * canvasScale,
                    minWidth = 1.5f * canvasScale
                )
                if (primitive.lineStyle.type == LineType.LIGHTNING) {
                    // 与 DrawingCanvas.drawCirclePrimitive 同源：按周长等分，最少 8 个 X
                    val circumference = kotlin.math.PI.toFloat() *
                        (3f * (drawRx + drawRy) - sqrt((3f * drawRx + drawRy) * (drawRx + 3f * drawRy)))
                    val count = maxOf(8, (circumference / 120f).toInt())
                    val xs = strokeScale * canvasScale
                    for (i in 0 until count) {
                        val angle = (i.toFloat() / count) * 2f * kotlin.math.PI.toFloat()
                        val lx = drawRx * cos(angle); val ly = drawRy * sin(angle)
                        val px = c.x + lx * cosT - ly * sinT
                        val py = c.y + lx * sinT + ly * cosT
                        val ltx = -drawRx * sin(angle); val lty = drawRy * cos(angle)
                        val tx2 = ltx * cosT - lty * sinT; val ty2 = ltx * sinT + lty * cosT
                        drawXMarkOnSegment(Offset(px, py), Offset(tx2, ty2), primitive.color.copy(alpha = alpha), xs)
                    }
                }
            }
        }
        is DrawingPrimitive.LinePrimitive -> {
            val sw = maxOf(primitive.strokeWidth * strokeScale * canvasScale, 1.5f * canvasScale)
            if (glow) {
                // 光晕趟：连续描边即可（不做虚线/闪电效果）
                lineWSP(primitive.startX, primitive.startY, primitive.endX, primitive.endY,
                    strokeColor, sw + 2f * gInf)
            } else if (primitive.lineStyle.type == LineType.SOLID) {
                lineWSP(primitive.startX, primitive.startY, primitive.endX, primitive.endY,
                    primitive.color.copy(alpha = alpha), sw)
            } else {
                val sp = worldToScreen(primitive.startX, primitive.startY)
                val ep = worldToScreen(primitive.endX, primitive.endY)
                val path = Path().apply { moveTo(sp.x, sp.y); lineTo(ep.x, ep.y) }
                // strokeWidth 必须传世界单位：drawPathWithStyle 内部会再乘一次 strokeScale（已含 canvasScale）。
                // 传屏幕 px 会被二次放大，粗笔 + 圆头端点会把短 dash 之间的空隙填满 → 预览看着像实线。
                drawPathWithStyle(
                    path = path,
                    color = primitive.color.copy(alpha = alpha),
                    strokeWidth = primitive.strokeWidth,
                    lineStyle = primitive.lineStyle,
                    strokeScale = strokeScale * canvasScale,
                    minWidth = 1.5f * canvasScale
                )
                if (primitive.lineStyle.type == LineType.LIGHTNING) {
                    // X 标记必须用屏幕坐标（此前传的是世界坐标，标记被画到别处，只剩一条实线）
                    drawLightningXMarks(sp, ep, primitive.color.copy(alpha = alpha), strokeScale * canvasScale)
                }
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
            // 预览与画布走同一条渲染路径（BlockRender 唯一入口）：
            //   锚定 = 内容形心落锚点；块内图元 = drawPrimitive；光晕 = drawSelectionGlowPass。
            // 此前这里另有一套 drawBlockPrimitive，旋转/区间标注/嵌套块/尖角/线宽比例在预览里全部缺失。
            val bd = blockDefs.find { it.id == primitive.blockDefId }
            val sc = worldToScreen(primitive.x, primitive.y)
            // 屏幕系标量缩放：块自身 scale × PFO 等比缩放 × 画布缩放
            val s = primitive.scale * avgScale * canvasScale
            val totalRot = primitive.rotation + edit.rotation
            val contentStrokeScale = blockContentStrokeScale(strokeScale, primitive.scale * avgScale)
            // 线宽下限系数：1/块缩放（不含 canvasScale——线宽公式里 canvasScale 是显式乘的）
            val blockFloor = 1f / abs(primitive.scale * avgScale).coerceAtLeast(1e-4f)
            if (bd != null && bd.primitives.isNotEmpty()) {
                if (glow) {
                    // 光晕外扩量 gInf 是屏幕 px，换算成块内局部单位（矩阵已含 s）
                    drawBlockGlow(
                        bd = bd, blockDefs = blockDefs,
                        anchor = sc, rotationRad = totalRot, scale = s,
                        strokeScale = contentStrokeScale,
                        inflate = gInf / s.coerceAtLeast(0.001f),
                        gc = glowColor,
                        refStrokeWidth = primitive.strokeWidth,
                        strokeFloorScale = blockFloor
                    )
                } else {
                    // dashScale 用未除块缩放的 strokeScale：虚线图案随块放大，跟线宽解耦
                    drawBlockContents(
                        bd = bd, blockDefs = blockDefs,
                        anchor = sc, rotationRad = totalRot, scale = s,
                        contentStrokeScale = contentStrokeScale,
                        dashScale = strokeScale,
                        refStrokeWidth = primitive.strokeWidth,
                        strokeFloorScale = blockFloor
                    )
                }
            } else {
                // 块定义缺失：与画布同一占位（预览在屏幕坐标系，笔宽系数并入 canvasScale）
                drawBlockPlaceholder(
                    center = sc,
                    halfSize = 30f * s * canvasScale,
                    strokeScale = contentStrokeScale * canvasScale,
                    strokeWidth = primitive.strokeWidth,
                    color = primitive.color,
                    rotationRad = totalRot,
                    lineStyle = primitive.lineStyle
                )
            }
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
        // 与 BlockRender.withBlockTransform 同一映射（model.BlockTransform.blockMapPoint）
        val centroid = blockContentCentroid(bd.primitives) ?: return null
        fun mp(wx: Float, wy: Float): Point2D = blockMapPoint(wx, wy, p, centroid)
        val pts = listOf(mp(cb[0], cb[1]), mp(cb[2], cb[1]), mp(cb[2], cb[3]), mp(cb[0], cb[3]))
        return floatArrayOf(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
    }
    // 矩形的 computeBounds 不含旋转，多选合并框需要含旋转的世界 AABB
    if (p is DrawingPrimitive.RectanglePrimitive) return p.worldBounds()
    return p.computeBounds(paint)
}
