package com.scheda.app.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.scheda.app.ui.components.glassHighlight
import com.kyant.backdrop.effects.vibrancy
import kotlinx.coroutines.delay

/**
 * 方向键微调器（游戏手柄十字布局）。
 * 单击 = 移动一步（stepUnit 世界单位）；长按 = 自动重复，步长逐级加速（上限 16×）。
 * [onNudge] 增量单位为世界坐标（步长已乘方向）；长按会话结束（松手/滑出）自动停止。
 * [backdrop] 非空时按钮渲染为液态玻璃（采样画布层、正圆外框）；空时回退纯色圆键。
 */
@Composable
fun NudgePad(
    onNudge: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
    stepUnit: Float = 1f,
    backdrop: Backdrop? = null
) {
    // 十字严格等臂（2026-11-04 重写）：四键圆心到中心同一距离 arm。
    // 原 Column(上, Row(左, 中空22, 右), 下) 拼法把纵向臂长锁死在 40dp（中行高度=键径），
    // 横向臂长被中空压到 31dp——四向不等距，且纵向无法再收紧凑。
    // Box 尺寸 = 键外占 + 2×arm，四键各贴一边（TopCenter/CenterStart/CenterEnd/BottomCenter）
    // 时圆心正好落在四条臂的端点上，天然等距。
    val keyOuter = 40.dp   // 38dp 键 + 1dp 内边距 ×2
    val arm = 35.dp        // 圆心到布局中心
    Box(modifier = modifier.size(keyOuter + arm * 2)) {
        NudgeKey(backdrop, Icons.Default.KeyboardArrowUp, 0f, -1f, "上移", onNudge, stepUnit,
            Modifier.align(Alignment.TopCenter))
        NudgeKey(backdrop, Icons.Default.KeyboardArrowLeft, -1f, 0f, "左移", onNudge, stepUnit,
            Modifier.align(Alignment.CenterStart))
        NudgeKey(backdrop, Icons.Default.KeyboardArrowRight, 1f, 0f, "右移", onNudge, stepUnit,
            Modifier.align(Alignment.CenterEnd))
        NudgeKey(backdrop, Icons.Default.KeyboardArrowDown, 0f, 1f, "下移", onNudge, stepUnit,
            Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun NudgeKey(
    backdrop: Backdrop?,
    icon: ImageVector,
    dirX: Float,
    dirY: Float,
    desc: String,
    onNudge: (Float, Float) -> Unit,
    stepUnit: Float,
    modifier: Modifier = Modifier
) {
    var holding by remember { mutableStateOf(false) }
    val onNudgeState = rememberUpdatedState(onNudge)
    // 长按自动重复：步长 1,1,2,2,4,4,8,8,16（每 2 tick 翻倍、上限 16×，加速平滑不过陡）；
    // 松手/滑出由 holding=false 取消本协程。
    LaunchedEffect(holding) {
        if (holding) {
            var mult = 1
            var ticks = 0
            while (true) {
                delay(110L)
                onNudgeState.value(dirX * stepUnit * mult, dirY * stepUnit * mult)
                ticks++
                if (ticks % 2 == 0 && mult < 16) mult *= 2
            }
        }
    }
    val baseMod = Modifier
        .padding(1.dp)
        .size(38.dp)
    val keyMod = if (backdrop != null) {
        // 液态玻璃：背后实时模糊 + 折射透镜 + 活力层，正圆外框
        baseMod.drawBackdrop(
            backdrop = backdrop,
            shape = { CircleShape },
            effects = {
                vibrancy()
                blur(2f.dp.toPx())
                lens(12f.dp.toPx(), 24f.dp.toPx())
            },
            highlight = { glassHighlight() },
            onDrawSurface = {
                drawRect(Color(0x0AFFFFFF))
            }
        )
    } else {
        baseMod
            .clip(CircleShape)
            .background(Color(0xDD333333))
    }
    Box(
        modifier = modifier.then(keyMod).pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val t0 = System.currentTimeMillis()
                    var longStarted = false
                    var canceled = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break   // 抬起
                        // 滑出键区（含 25% 缓冲）：未长按=丢弃本次；长按中=停止重复
                        val out = change.position.x < -size.width * 0.25f ||
                            change.position.x > size.width * 1.25f ||
                            change.position.y < -size.height * 0.25f ||
                            change.position.y > size.height * 1.25f
                        if (out) { canceled = true; break }
                        if (!longStarted && System.currentTimeMillis() - t0 >= 500L) {
                            longStarted = true
                            holding = true
                            onNudgeState.value(dirX * stepUnit, dirY * stepUnit)   // 长按判定即走第一步
                        }
                    }
                    if (longStarted) {
                        holding = false                        // 长按结束（抬起/滑出）→ 停止循环
                    } else if (!canceled) {
                        onNudgeState.value(dirX * stepUnit, dirY * stepUnit)   // 单击一步
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // 玻璃底偏亮 → 深色箭头保证可读；回退深灰底 → 白色箭头
        Icon(
            icon, desc, Modifier.size(22.dp),
            tint = if (backdrop != null) Color(0xFF3A3A3A) else Color.White
        )
    }
}
