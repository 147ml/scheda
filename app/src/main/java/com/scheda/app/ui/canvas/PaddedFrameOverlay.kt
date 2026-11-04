package com.scheda.app.ui.canvas

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
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
    // 正交锁定：两个旋转角手柄改为「点击步进 90°」（右上 ↻ 顺时针 / 左下 ↺ 逆时针），拖动旋转禁用
    orthoStep: Boolean = false,
    onStepRotate: (Float) -> Unit = {},
    onMidpointScale: (Float, Float) -> Unit = { _, _ -> },
    onMidpointOffset: (Float, Float) -> Unit = { _, _ -> },
    onArrowSpan: (Float) -> Unit = {},
    onTransformEnd: () -> Unit = {},
    onHandleActiveChanged: (Boolean) -> Unit = {},
    midpointOverrides: List<Offset>? = null,
    swapMidpointAxes: Boolean = false,
    midpointScaleOnly: Boolean = false,
    onRectMidpointDrag: ((index: Int, r: Float) -> Unit)? = null,
    // 拖拽中被拖手柄应跟随的"被拖边中点"（世界坐标；null=无覆盖）。
    // 越界翻转时手柄不再"脱手"跳回框的另一侧（2026-10-13）
    dragHandleWorld: Offset? = null,
    backdrop: com.kyant.backdrop.Backdrop? = null,
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
    // 当前正在被拖动的手柄索引（用于让"被拖手柄"的位置跟随被拖边中点）
    var activeMidIndex by remember { mutableStateOf<Int?>(null) }

    val cx = (bounds.minX + bounds.maxX) / 2f + offsetX
    val cy = (bounds.minY + bounds.maxY) / 2f + offsetY
    val halfW = customHalfW ?: maxOf(((bounds.maxX - bounds.minX) / 2f) * scaleX, minHalf / canvasScale)
    val halfH = customHalfH ?: maxOf(((bounds.maxY - bounds.minY) / 2f) * scaleY, minHalf / canvasScale)
    // 未受 minHalf 抬升前的“真实”半尺寸：中点拖拽的缩放/补偿必须按真实半尺寸计算，
    // 否则小图形（显示框被 minHalf 撑大）拖动时对边会漂移（2026-10-13 双模型复核结论）
    val rawHalfW = customHalfW ?: ((bounds.maxX - bounds.minX) / 2f * kotlin.math.abs(scaleX))
    val rawHalfH = customHalfH ?: ((bounds.maxY - bounds.minY) / 2f * kotlin.math.abs(scaleY))

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

    // 拖拽中的"被拖手柄"位置覆盖：跟随被拖边的实时中点（世界坐标由 VM 提供）——
    // 越过对边后手柄不再"脱手"（跟随该边穿到另一侧，2026-10-13）
    val activeMidIdx = activeMidIndex
    val allMidpointsFinal = if (dragHandleWorld != null && activeMidIdx != null &&
        activeMidIdx in allMidpoints.indices) {
        val hs = worldToScreen(dragHandleWorld.x, dragHandleWorld.y)
        val dxp = hs.x - centerScreen.x; val dyp = hs.y - centerScreen.y
        val dp = sqrt(dxp * dxp + dyp * dyp)
        val hp = if (dp > 0.01f) Offset(hs.x + dxp / dp * paddingPx, hs.y + dyp / dp * paddingPx) else hs
        allMidpoints.toMutableList().also { it[activeMidIdx] = hp }
    } else allMidpoints

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
        val onStepRotateState = rememberUpdatedState(onStepRotate)
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
        // 正交步进模式：四角手柄按「屏幕方位」锁位——右上↻、左下↺、左上/右下缩放。
        // 几何角会随框旋转互换，按几何角绑定时点一次旋转按钮整组手柄就跟着"转"过去；
        // 按屏幕方位排序后，每次步进 90° 按钮仍停在原位（语义与几何角解耦）。
        val cornerHandles: List<Pair<Offset, Int>> = if (orthoStep) {
            val sorted = paddedCorners.sortedBy { c ->
                kotlin.math.atan2((c.y - centerScreen.y).toDouble(), (c.x - centerScreen.x).toDouble())
            }
            // 屏幕角度升序：左上(-135°) → 右上(-45°) → 右下(45°) → 左下(135°)
            val slotSemantic = intArrayOf(0, 1, 3, 2)
            sorted.mapIndexed { slot, c -> c to slotSemantic[slot] }
        } else paddedCorners.mapIndexed { i, c -> c to i }
        for ((corner, i) in cornerHandles) {
            val cornerState = rememberUpdatedState(corner)
            val centerState = rememberUpdatedState(centerScreen)
            val rotationState = rememberUpdatedState(frameRotation)
            val scaleState2 = rememberUpdatedState(canvasScale)
            var cornerActive by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .offset { IntOffset((corner.x - handleRadiusPx).toInt(), (corner.y - handleRadiusPx).toInt()) }
                    .size(handleSize).clip(CircleShape)
                    .pointerInput(orthoStep, i) {
                        // 正交步进：i=1 右上 ↻ 顺时针，i=2 左下 ↺ 逆时针，只认点击
                        if (orthoStep && (i == 1 || i == 2)) {
                            detectTapGestures {
                                onStepRotateState.value(
                                    if (i == 1) (Math.PI / 2).toFloat() else (-Math.PI / 2).toFloat()
                                )
                            }
                            return@pointerInput
                        }
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
            ) {
                // 玻璃手柄视觉（功能色掺色毛玻璃·无高光）：绿=缩放角(0,3)、橙=旋转角(1,2)
                val tint = if (i == 0 || i == 3) Color(0x3D78D796) else Color(0x3DFFB96E)
                Box(Modifier.fillMaxSize().glassHandle(backdrop, CircleShape, tint))
                Canvas(Modifier.fillMaxSize()) {
                    val arrowRotDeg = frameRotation * 180f / kotlin.math.PI.toFloat()
                    if (i == 1 || i == 2) {
                        val p = Paint().apply {
                            this.color = android.graphics.Color.rgb(58, 58, 58)
                            textSize = handleRadiusPx * 1.2f; textAlign = Paint.Align.CENTER; isAntiAlias = true
                        }
                        drawContext.canvas.nativeCanvas.drawText(
                            if (i == 1) "\u21BB" else "\u21BA",
                            size.width / 2f, size.height / 2f + handleRadiusPx * 0.35f, p
                        )
                    } else {
                        val nc = drawContext.canvas.nativeCanvas
                        nc.save(); nc.translate(size.width / 2f, size.height / 2f); nc.rotate(arrowRotDeg)
                        val r = handleRadiusPx * 0.32f; val strokeW = 2.5f
                        drawLine(Color(0xFF3A3A3A), Offset(0f, r), Offset(0f, -r), strokeW, StrokeCap.Round)
                        drawLine(Color(0xFF3A3A3A), Offset(r, 0f), Offset(-r, 0f), strokeW, StrokeCap.Round)
                        nc.restore()
                    }
                }
            }
        }

        // Layer 4: Midpoint handles
        for ((i, m) in allMidpointsFinal.withIndex()) {
            val midPosState = rememberUpdatedState(m)
            val midCenterState = rememberUpdatedState(centerScreen)
            val midRangeState = rememberUpdatedState(isMidpointRange)
            var midActive by remember { mutableStateOf(false) }
            // 拖拽开始时的手柄方向快照（单位向量）：全程用它做投影方向，
            // 避免手柄位置随图形缩放/越界变化导致方向翻转、拖拽折返（2026-10-13）
            var midStartDir by remember { mutableStateOf<Offset?>(null) }
            Box(
                modifier = Modifier
                    .offset { IntOffset((midPosState.value.x - handleRadiusPx).toInt(), (midPosState.value.y - handleRadiusPx).toInt()) }
                    .size(handleSize).clip(CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                midActive = true; activeMidIndex = i; onHandleActiveChanged(true)
                                val mp0 = midPosState.value; val cs0 = midCenterState.value
                                val dx0 = mp0.x - cs0.x; val dy0 = mp0.y - cs0.y
                                val nd0 = sqrt(dx0 * dx0 + dy0 * dy0)
                                midStartDir = if (nd0 > 0.01f) Offset(dx0 / nd0, dy0 / nd0) else null
                            },
                            onDragEnd = { midActive = false; midStartDir = null; activeMidIndex = null; onHandleActiveChanged(false); onTransformEnd() },
                            onDragCancel = { midActive = false; midStartDir = null; activeMidIndex = null; onHandleActiveChanged(false); onTransformEnd() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val mp = midPosState.value; val cs = midCenterState.value
                                val hx = mp.x - cs.x; val hy = mp.y - cs.y
                                val dsq = hx * hx + hy * hy
                                if (dsq > 1f) {
                                    val nd = sqrt(dsq)
                                    val isRange = midRangeState.value
                                    // 投影方向锁定为拖拽开始时的方向：符号全程稳定（跨过对边/中心不折返）
                                    val ux = midStartDir?.x ?: (hx / nd)
                                    val uy = midStartDir?.y ?: (hy / nd)
                                    val d = dragAmount.x * ux + dragAmount.y * uy
                                    if (isRange) {
                                        onArrowSpan(1f + d / nd)
                                    } else {
                                        val sc = csState.value
                                        val rectDrag = rectDragState.value
                                        if (rectDrag != null) {
                                            // 传世界坐标位移量（方向锁定首帧，边与手指 1:1 跟随）
                                            rectDrag(i, d / sc)
                                        } else {
                                            // 精确单边缩放：σ 按“当前真实半尺寸”归一（不是手柄到中心距离），
                                            // 补偿量 = σ_eff × 真实半尺寸（沿锁定方向）——对边精确不动、被拖边 1:1 跟手。
                                            // σ_eff 与 VM 侧 coerceIn(0.1, 10) 自洽：触底/触顶时补偿同步收敛，不再“缩放停、平移跑”。
                                            // （原 swapMidpointAxes 分支已随公式重写移除：该分支当前不可达——rectDrag 恒优先拦截直线。）
                                            val dWorld = d / sc
                                            val rawHalfAxis = if (i < 2) rawHalfH else rawHalfW
                                            val scaleAxis = if (i < 2) scaleY else scaleX
                                            val sigmaReq = dWorld / (2f * maxOf(rawHalfAxis, 0.5f))
                                            val sTarget = (scaleAxis * (1f + sigmaReq)).coerceIn(0.1f, 10f)
                                            val sigmaEff = if (scaleAxis > 0.0001f) sTarget / scaleAxis - 1f else 0f
                                            val offAmt = sigmaEff * maxOf(rawHalfAxis, 0.5f)
                                            if (i < 2) onMidpointScale(1f, 1f + sigmaEff)
                                            else onMidpointScale(1f + sigmaEff, 1f)
                                            if (!midpointScaleOnly) onMidpointOffset(offAmt * ux, offAmt * uy)
                                        }
                                    }
                                }
                            }
                        )
                    }
            ) {
                // 玻璃药丸视觉（掺蓝毛玻璃·无高光），随框旋转
                val isHoriz = if (isMidpointRange) false else if (swapMidpointAxes) i >= 2 else i < 2
                val midRotDeg = frameRotation * 180f / kotlin.math.PI.toFloat()
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(if (isHoriz) 26.dp else 14.dp, if (isHoriz) 14.dp else 26.dp)
                            .graphicsLayer { rotationZ = midRotDeg }
                            .glassHandle(backdrop, RoundedCornerShape(7.dp), Color(0x3D8CBEFA))
                    )
                }
            }
        }
    }
}

/** 手柄玻璃视觉：功能色掺色毛玻璃，无高光/描边/投影（用户定稿 A2）。backdrop=null 回退实色。 */
@Composable
internal fun Modifier.glassHandle(backdrop: Backdrop?, shape: Shape, tint: Color): Modifier =
    if (backdrop != null) this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(2f.dp.toPx())
            lens(12f.dp.toPx(), 24f.dp.toPx())
        },
        onDrawSurface = { drawRect(tint) },
        highlight = null,
        shadow = null,
        innerShadow = null,
    ) else this.clip(shape).background(tint.copy(alpha = 0.92f), shape)
