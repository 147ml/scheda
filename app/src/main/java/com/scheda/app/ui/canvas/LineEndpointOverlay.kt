package com.scheda.app.ui.canvas

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.scheda.app.model.DrawingPrimitive
import com.scheda.app.model.LineSnap
import com.scheda.app.model.SnapKind
import com.scheda.app.model.SnapResult
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.sqrt

// 端点球：屏幕常量尺寸（和 PFO 手柄一致，不随画布缩放变）
private val EndpointBallSize = 20.dp
private val EndpointHitSize = 40.dp
private val EndpointRingRadius = 14.dp
private val EndpointRingRadiusVertex = 15.dp
private val EndpointRippleMax = 26.dp

private val BallIdle = Color(0x3D8CBEFA)
private val BallEdgeSnap = Color(0x4D34C759)
private val BallVertexSnap = Color(0x6B2E9E4F)
private val DotIdle = Color(0xFF3A3A3A)
private val DotSnap = Color(0xFF1B5E20)
private val SnapRingColor = Color(0xFF34C759)

/**
 * 直线待确认的端点编辑层：两个圆形玻璃点直接压在线的两端上，替代整套框 + 旋转/缩放手柄。
 *
 * - 拖动端点改线长/角度，另一端固定，1:1 跟手（手指世界位移直接累加到端点）；
 * - 靠近其它线段/角点/交点/圆周时吸附，顶点级（端点、角点、交点、圆心）优先于边级；
 * - 首次吸附播一次波纹 + 球体轻弹 + 变色；沿同一目标继续滑动不重播（靠 key 判定）。
 */
@Composable
fun LineEndpointOverlay(
    line: DrawingPrimitive.LinePrimitive,
    offsetX: Float,
    offsetY: Float,
    canvasScale: Float,
    canvasOffsetX: Float,
    canvasOffsetY: Float,
    primitives: List<DrawingPrimitive>,
    /** 吸附池要排除的自身索引（选择态下这条线已在 primitives 里；待确认态传 -1） */
    excludeIndex: Int = -1,
    backdrop: Backdrop?,
    onMoveEndpoint: (isStart: Boolean, x: Float, y: Float) -> Unit,
    onBodyDrag: (dx: Float, dy: Float) -> Unit,
    onHandleActiveChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val lineState = rememberUpdatedState(line)
    val csState = rememberUpdatedState(canvasScale)
    val moveState = rememberUpdatedState(onMoveEndpoint)
    val bodyMove = rememberUpdatedState(onBodyDrag)
    val activeCb = rememberUpdatedState(onHandleActiveChanged)

    val safeScale = canvasScale.coerceAtLeast(0.01f)
    val freehandGap = with(density) { 6.dp.toPx() } / safeScale
    val minLenPx = with(density) { 6.dp.toPx() }
    // 线段池只在图元集合/缩放变化时重建；拖动端点期间 primitives 不变。
    // 选择态下这条线自身也在 primitives 里——必须排除，否则端点会被吸回原位。
    val snapPool = remember(primitives, excludeIndex) {
        if (excludeIndex >= 0) primitives.filterIndexed { i, _ -> i != excludeIndex } else primitives
    }
    val pool = remember(snapPool, freehandGap) { LineSnap.buildPool(snapPool, freehandGap) }

    val thresholds = rememberUpdatedState(
        floatArrayOf(
            with(density) { 18.dp.toPx() } / safeScale,   // vertexEnter
            with(density) { 26.dp.toPx() } / safeScale,   // vertexExit
            with(density) { 14.dp.toPx() } / safeScale,   // edgeEnter
            with(density) { 20.dp.toPx() } / safeScale    // edgeExit
        )
    )

    var startSnap by remember { mutableStateOf<SnapResult?>(null) }
    var endSnap by remember { mutableStateOf<SnapResult?>(null) }
    var startSeq by remember { mutableIntStateOf(0) }
    var endSeq by remember { mutableIntStateOf(0) }

    val startPulse = remember { Animatable(1f) }
    val endPulse = remember { Animatable(1f) }

    // 整体平移（NudgePad / 拖线身）会让端点离开原吸附点，吸附态随之失效
    LaunchedEffect(offsetX, offsetY) {
        startSnap = null
        endSnap = null
    }

    LaunchedEffect(startSeq) {
        if (startSeq > 0) {
            startPulse.snapTo(0f)
            startPulse.animateTo(1f, tween(320, easing = LinearOutSlowInEasing))
        }
    }
    LaunchedEffect(endSeq) {
        if (endSeq > 0) {
            endPulse.snapTo(0f)
            endPulse.animateTo(1f, tween(320, easing = LinearOutSlowInEasing))
        }
    }

    fun screenOf(wx: Float, wy: Float) = Offset(
        (wx + offsetX) * canvasScale + canvasOffsetX,
        (wy + offsetY) * canvasScale + canvasOffsetY
    )

    val startScreen = screenOf(line.startX, line.startY)
    val endScreen = screenOf(line.endX, line.endY)

    fun endpointDrag(isStart: Boolean): Modifier = Modifier.pointerInput(isStart) {
        var baseX = 0f
        var baseY = 0f
        var accX = 0f
        var accY = 0f
        var prevKey: String? = null
        var hadSnap = false

        detectDragGestures(
            onDragStart = {
                val l = lineState.value
                baseX = if (isStart) l.startX else l.endX
                baseY = if (isStart) l.startY else l.endY
                accX = 0f
                accY = 0f
                // 起手若已吸着：退出阈值按该目标算（滞回），且不算“首次吸附”
                val existing = if (isStart) startSnap else endSnap
                prevKey = existing?.key
                hadSnap = existing != null
                activeCb.value(true)
            },
            onDragEnd = { activeCb.value(false) },
            onDragCancel = { activeCb.value(false) },
            onDrag = { change, dragAmount ->
                change.consume()
                val cs = csState.value.coerceAtLeast(0.01f)
                // 用「起手端点 + 累计手指位移」推自由位置，而不是从当前（可能被吸附改写的）
                // 端点累加——否则吸附期间手指继续走，端点会丢失那段位移、脱离时错位。
                accX += dragAmount.x / cs
                accY += dragAmount.y / cs
                // primitive 坐标（写回用）不含 pe.offset；吸附判定必须用真实世界坐标（含 offset），
                // 否则按过 NudgePad / 拖过线身之后再拖端点，吸附位置会整体偏一个 offset。
                val rawX = baseX + accX
                val rawY = baseY + accY
                val probeX = rawX + offsetX
                val probeY = rawY + offsetY

                val th = thresholds.value
                val (segs, circles) = pool
                val hit = LineSnap.snap(
                    px = probeX, py = probeY,
                    segments = segs, circles = circles,
                    vertexEnter = th[0], vertexExit = th[1],
                    edgeEnter = th[2], edgeExit = th[3],
                    prevKey = prevKey
                )
                prevKey = hit?.key
                if (isStart) startSnap = hit else endSnap = hit
                if (hit != null && !hadSnap) {
                    if (isStart) startSeq++ else endSeq++
                }
                hadSnap = hit != null

                val wx = if (hit != null) hit.x - offsetX else rawX
                val wy = if (hit != null) hit.y - offsetY else rawY
                // 零长保护：两端距离小于 6dp（屏幕）时不写回，避免确认出一条看不见的线
                val fixedX = if (isStart) lineState.value.endX else lineState.value.startX
                val fixedY = if (isStart) lineState.value.endY else lineState.value.startY
                val ddx = wx - fixedX
                val ddy = wy - fixedY
                val minLen = minLenPx / cs
                if (ddx * ddx + ddy * ddy >= minLen * minLen) {
                    moveState.value(isStart, wx, wy)
                }
            }
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 波纹 + 常驻吸附环（画在球之下）
        Canvas(Modifier.fillMaxSize()) {
            fun drawSnapFx(center: Offset, snap: SnapResult?, pulse: Float) {
                if (snap == null) return
                val vertex = snap.kind == SnapKind.VERTEX
                drawCircle(
                    color = SnapRingColor.copy(alpha = if (vertex) 0.8f else 0.55f),
                    radius = (if (vertex) EndpointRingRadiusVertex else EndpointRingRadius).toPx(),
                    center = center,
                    style = Stroke(width = 1.25.dp.toPx())
                )
                if (pulse < 1f) {
                    val t = pulse.coerceIn(0f, 1f)
                    val r0 = EndpointBallSize / 2
                    drawCircle(
                        color = SnapRingColor.copy(alpha = 0.42f * (1f - t)),
                        radius = (r0 + (EndpointRippleMax - r0) * t).toPx(),
                        center = center,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
            }
            drawSnapFx(startScreen, startSnap, startPulse.value)
            drawSnapFx(endScreen, endSnap, endPulse.value)
        }

        // 线身拖动：沿线的透明命中带（宽 40dp，与端点热区同宽），保留原来的整体平移手感
        val vx = endScreen.x - startScreen.x
        val vy = endScreen.y - startScreen.y
        val lenPx = sqrt(vx * vx + vy * vy)
        if (lenPx > 1f) {
            val midX = (startScreen.x + endScreen.x) / 2f
            val midY = (startScreen.y + endScreen.y) / 2f
            val bodyW = lenPx.coerceAtMost(4000f)
            val bodyH = with(density) { 40.dp.toPx() }
            val angleDeg = Math.toDegrees(atan2(vy.toDouble(), vx.toDouble())).toFloat()
            Box(
                modifier = Modifier
                    .offset { IntOffset((midX - bodyW / 2f).toInt(), (midY - bodyH / 2f).toInt()) }
                    .size(with(density) { bodyW.toDp() }, 40.dp)
                    .graphicsLayer { rotationZ = angleDeg }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { activeCb.value(true) },
                            onDragEnd = { activeCb.value(false) },
                            onDragCancel = { activeCb.value(false) },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val cs = csState.value.coerceAtLeast(0.01f)
                                bodyMove.value(dragAmount.x / cs, dragAmount.y / cs)
                            }
                        )
                    }
            )
        }

        EndpointBall(
            center = startScreen,
            snap = startSnap,
            pulse = startPulse.value,
            backdrop = backdrop,
            dragModifier = endpointDrag(true)
        )
        EndpointBall(
            center = endScreen,
            snap = endSnap,
            pulse = endPulse.value,
            backdrop = backdrop,
            dragModifier = endpointDrag(false)
        )
    }
}

@Composable
private fun EndpointBall(
    center: Offset,
    snap: SnapResult?,
    pulse: Float,
    backdrop: Backdrop?,
    dragModifier: Modifier
) {
    val density = LocalDensity.current
    val hitPx = with(density) { EndpointHitSize.toPx() }

    val ballColor by animateColorAsState(
        targetValue = when (snap?.kind) {
            SnapKind.VERTEX -> BallVertexSnap
            SnapKind.EDGE -> BallEdgeSnap
            null -> BallIdle
        },
        animationSpec = tween(180),
        label = "endpointBall"
    )
    val dotColor by animateColorAsState(
        targetValue = if (snap != null) DotSnap else DotIdle,
        animationSpec = tween(180),
        label = "endpointDot"
    )
    val scale = 1f + 0.14f * sin(PI.toFloat() * pulse.coerceIn(0f, 1f))

    Box(
        modifier = Modifier
            .offset { IntOffset((center.x - hitPx / 2f).toInt(), (center.y - hitPx / 2f).toInt()) }
            .size(EndpointHitSize)
            .then(dragModifier),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(EndpointBallSize)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .glassHandle(backdrop, CircleShape, ballColor)
        ) {
            Canvas(Modifier.fillMaxSize()) {
                // 端点精确位置：玻璃球心一个小实点（玻璃半透明，线从球里穿过去能看见）
                drawCircle(dotColor, radius = 1.75.dp.toPx())
            }
        }
    }
}
