package com.scheda.app.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2

/**
 * 在 Initial 通道同步通知手柄按下/抬起（与 ArrayOverlay 的互锁一致），
 * 避免手柄拖动被画布手势抢走。private 文件级副本，互不干扰。
 */
private fun Modifier.handleInterlock(onHandleActiveChanged: (Boolean) -> Unit): Modifier =
    this.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            onHandleActiveChanged(true)
            try {
                waitForUpOrCancellation(PointerEventPass.Initial)
            } finally {
                // 协程取消（如编辑中途确认/取消）也必须复位，否则画布单指操作会被永久屏蔽
                onHandleActiveChanged(false)
            }
        }
    }

/**
 * 数字阵列编辑模式的覆盖层：
 * 1. 间距三角手柄——轴向锚点为末端数字中心沿生长方向外移半个间距处，
 *    整体再沿排列轴法向偏移固定屏距（避免与轴线上的方向手柄重叠），
 *    拖动沿排列轴投影调整相邻数字中心距（任意角度都有效）。
 * 2. 方向圆形手柄——始终位于阵列最外侧（末端数字再外扩固定屏幕距离，不远离最外侧数字），
 *    拖动时以首数字为圆心 360° 旋转，手指（屏幕根坐标）相对首数字的角度实时决定排列方向。
 */
@Composable
fun NumArrayOverlay(
    anchorX: Float,
    anchorY: Float,
    count: Int,
    gap: Float,
    rotationDeg: Float,
    canvasScale: Float,
    canvasOffsetX: Float,
    canvasOffsetY: Float,
    onGapChange: (Float) -> Unit,
    onRotationChange: (deg: Float) -> Unit,
    onHandleActiveChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val handleSize = 36.dp
    val handleRadiusPx = with(density) { handleSize.toPx() / 2f }
    // 方向手柄在阵列最外侧的外扩距离（屏幕固定值）
    val outerRadiusPx = with(density) { 44.dp.toPx() }
    val outerRadiusWorld = outerRadiusPx / canvasScale

    fun worldToScreen(wx: Float, wy: Float): Offset =
        Offset(wx * canvasScale + canvasOffsetX, wy * canvasScale + canvasOffsetY)

    // 排列方向向量（角度 0°=向右，正角顺时针）
    val rad = Math.toRadians(rotationDeg.toDouble())
    val cosA = kotlin.math.cos(rad).toFloat()
    val sinA = kotlin.math.sin(rad).toFloat()
    val dirVec = Offset(cosA, sinA)

    // 方向手柄：阵列最外侧 = 首数字 + 方向向量 × (阵列长度 + 外扩)
    val arrayLen = gap * (count - 1)
    val dirHandleWorld = Offset(
        anchorX + dirVec.x * (arrayLen + outerRadiusWorld),
        anchorY + dirVec.y * (arrayLen + outerRadiusWorld)
    )
    val dirHandleScreen = worldToScreen(dirHandleWorld.x, dirHandleWorld.y)

    Box(modifier = modifier.fillMaxSize()) {
        // ── 间距三角手柄（count<2 时无间距可调，不显示）──
        if (count >= 2) {
            // 轴向锚点：末端数字中心沿生长方向再外移半个间距
            val axisWX = anchorX + dirVec.x * (arrayLen + gap / 2f)
            val axisWY = anchorY + dirVec.y * (arrayLen + gap / 2f)
            // 整体再沿排列轴法向偏移固定屏距，避免与轴线上的方向手柄重叠
            val gapPerpWorld = with(density) { 44.dp.toPx() } / canvasScale
            val perpX = -dirVec.y; val perpY = dirVec.x
            val handleWorldX = axisWX + perpX * gapPerpWorld
            val handleWorldY = axisWY + perpY * gapPerpWorld
            val handleScreen = worldToScreen(handleWorldX, handleWorldY)
            val axisScreen = worldToScreen(axisWX, axisWY)

            // 三角手柄视觉：蓝填充 + 白描边 + 投影（尖端指向生长方向）
            Canvas(modifier = Modifier.fillMaxSize()) {
                // 手柄 → 轴向锚点的引导细线（法向偏移后提示作用点）
                drawLine(
                    Color(0x664B9CD3), axisScreen, handleScreen,
                    strokeWidth = with(density) { 1.dp.toPx() }
                )
                val handleBlue = Color(0xFF4B9CD3)
                val triH = with(density) { 13.dp.toPx() }   // 三角形半长（尖端方向）
                val triW = with(density) { 11.dp.toPx() }   // 三角形半宽（底边方向）
                val px = -dirVec.y; val py = dirVec.x  // 法向
                val tipX = handleScreen.x + dirVec.x * triH; val tipY = handleScreen.y + dirVec.y * triH
                val bx = handleScreen.x - dirVec.x * triH; val by = handleScreen.y - dirVec.y * triH
                fun build(dy: Float) = Path().apply {
                    moveTo(tipX, tipY + dy)
                    lineTo(bx + px * triW, by + py * triW + dy)
                    lineTo(bx - px * triW, by - py * triW + dy)
                    close()
                }
                drawPath(build(1.5f), Color(0x33000000))  // 投影
                drawPath(build(0f), handleBlue)
                drawPath(build(0f), Color.White, style = Stroke(width = 2f, join = StrokeJoin.Round))
            }

            // 间距手柄手势：沿排列轴投影拖动 → 间距
            val gapState = rememberUpdatedState(gap)
            val cosState = rememberUpdatedState(cosA)
            val sinState = rememberUpdatedState(sinA)
            Box(Modifier.offset { IntOffset((handleScreen.x - handleRadiusPx).toInt(), (handleScreen.y - handleRadiusPx).toInt()) }
                .size(handleSize).clip(CircleShape)
                .handleInterlock(onHandleActiveChanged)
                .pointerInput(Unit) {
                    detectDragGestures { ch, da ->
                        ch.consume()
                        // 拖动手势在排列轴方向的投影（任意角度均有效）
                        val delta = da.x * cosState.value + da.y * sinState.value
                        onGapChange(gapState.value + delta / canvasScale)
                    }
                })
        }

        // ── 方向圆形手柄（始终显示在阵列最外侧；count=1 时也在首数字外侧）──
        // 蓝底白 ⇄ 箭头，拖动绕首数字 360° 旋转
        // 注意：detectDragGestures 的位置是手柄 Box 局部坐标，必须换算到屏幕根坐标
        // 再与 anchorScreenCenter 求夹角，否则旋转中心会偏到手柄附近、角度不跟手
        val anchorScreenCenter = worldToScreen(anchorX, anchorY)
        val anchorState = rememberUpdatedState(anchorScreenCenter)
        val dirHandleState = rememberUpdatedState(dirHandleScreen)
        Box(
            Modifier
                .offset { IntOffset((dirHandleScreen.x - handleRadiusPx).toInt(), (dirHandleScreen.y - handleRadiusPx).toInt()) }
                .size(handleSize)
                .clip(CircleShape)
                .background(Color(0xFF4B9CD3))
                .handleInterlock(onHandleActiveChanged)
                .pointerInput(Unit) {
                    var fingerX = 0f
                    var fingerY = 0f
                    detectDragGestures(
                        onDragStart = { pos ->
                            // 手指屏幕根坐标 = 手柄 Box 左上角 + 局部落点
                            val h = dirHandleState.value
                            fingerX = h.x - handleRadiusPx + pos.x
                            fingerY = h.y - handleRadiusPx + pos.y
                        },
                        onDrag = { ch, da ->
                            ch.consume()
                            fingerX += da.x
                            fingerY += da.y
                            // 相对首数字中心的角度（屏幕 Y 向下，atan2 自然顺时针为正）
                            val a = anchorState.value
                            val dx = fingerX - a.x
                            val dy = fingerY - a.y
                            if (abs(dx) < 1f && abs(dy) < 1f) return@detectDragGestures
                            val deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                            onRotationChange(deg)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Text(
                "⇄",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun abs(v: Float) = if (v < 0) -v else v
