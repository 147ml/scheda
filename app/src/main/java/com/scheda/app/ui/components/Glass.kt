package com.scheda.app.ui.components

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SweepGradient
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/**
 * 统一液态玻璃口径（与 Nota / Scheda 浮动状态栏实参一致）：
 * vibrancy + blur(2dp) + lens(12, 24) + 膜 0x0AFFFFFF。
 * 改观感只动这几个参数，别在调用点各写一套。
 */
val GlassTint = Color(0x0AFFFFFF)
val GlassFallback = Color(0xFF2A2A2A)

/** 底部玻璃工具栏（含系统导航条）的占位高度：浮层元素要让它避让。 */
@Composable
fun glassToolbarInset(): androidx.compose.ui.unit.Dp {
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return nav + 90.dp
}

/** 有 backdrop 时画真玻璃；没有（降级/跨窗口）时回退纯色。
 *  layerBlock：动画变换走这个通道（库自带采样逆变换补偿）——外层 graphicsLayer 缩放
 *  会让采样错位（库 TODO："outer transformations lead to wrong position calculation"）。
 *  tintBrush：渐变膜（非空时替代平色 tint）。 */
@Composable
fun Modifier.glassSurface(
    backdrop: Backdrop?,
    shape: Shape = RoundedCornerShape(16.dp),
    tint: Color = GlassTint,
    fallback: Color = GlassFallback,
    layerBlock: (androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit)? = null,
    tintBrush: androidx.compose.ui.graphics.Brush? = null,
): Modifier = if (backdrop != null) {
    this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(2.dp.toPx())
            lens(12.dp.toPx(), 24.dp.toPx())
        },
        onDrawSurface = { if (tintBrush != null) drawRect(tintBrush) else drawRect(tint) },
        layerBlock = layerBlock,
    )
} else {
    val base = if (layerBlock != null) this.graphicsLayer(layerBlock) else this
    base.background(fallback, shape)
}

/**
 * 玻璃质感静态样式（无捕获源可用时）。
 * Compose 的 Dialog / Popup 是独立 Window，采样不到页面内的 LayerBackdrop，
 * 这些场景用半透明白膜 + 细边框模拟，保证全应用观感一致。
 */
fun Modifier.glassLook(
    shape: Shape = RoundedCornerShape(16.dp),
    tint: Color = Color(0xE6FFFFFF),
    borderColor: Color = Color(0x33FFFFFF),
): Modifier = this
    .clip(shape)
    .background(tint, shape)
    .border(1.dp, borderColor, shape)

/**
 * 玻璃面上的前景色映射：亮玻璃上原来用白色/浅灰的文字图标改深色，保证可读性。
 * glass=false 时原样返回（保持原深底配色）。
 */
fun glassAdapt(c: Color, glass: Boolean): Color {
    if (!glass) return c
    return when (c) {
        Color.White, Color(0xFFFFFFFF) -> Color(0xFF2A2A2A)
        Color(0xFFF0F0F0), Color(0xFFD6D6D6) -> Color(0xFF555555)
        Color(0xFFCCCCCC) -> Color(0xFF555555)
        Color(0xFFAAAAAA) -> Color(0xFF777777)
        Color(0xFF64B5F6) -> Color(0xFF1565C0)
        Color(0xFF4B9CD3) -> Color(0xFF0D6EA8)
        Color(0xFFFF6B6B) -> Color(0xFFD32F2F)
        Color(0xFFD34B4B) -> Color(0xFFC62828)
        Color(0xFF4CAF50) -> Color(0xFF2E7D32)
        else -> c
    }
}

// ───────────────────────── 彩虹描边（选中标记） ─────────────────────────

/** 环带取色：首尾同色闭合（避免接缝），与文件盒子 AuroraGlassFab 同一套色序。 */
private val AuroraColors = intArrayOf(
    0xFFFF5FA2.toInt(), // 品红
    0xFFFF8A3D.toInt(), // 橙
    0xFFF7C948.toInt(), // 琥珀黄
    0xFF8BE04E.toInt(), // 黄绿
    0xFF35D6C5.toInt(), // 青绿
    0xFF3B9BFF.toInt(), // 蓝
    0xFF8B5CF6.toInt(), // 紫
    0xFFFF5FA2.toInt(), // 回到品红（闭合）
)

private val AuroraStops = floatArrayOf(
    0f, 1f / 7f, 2f / 7f, 3f / 7f, 4f / 7f, 5f / 7f, 6f / 7f, 1f
)

/**
 * 沿圆角矩形轮廓描一圈彩虹边（带轻微光晕），当「选中」标记用。
 *
 * 与文件盒子 `AuroraGlassFab` 同源，区别是这里画**圆角矩形**而不是圆：
 * 用原生 `drawRoundRect` + 扫掠渐变描边，`cornerRadius >= 高度/2` 时自动成胶囊/正圆。
 *
 * 性能口径：`spinSeconds = 0` 时完全静态、零动画开销。工具栏是常驻玻璃条，
 * 环每帧转动会连带重画整条玻璃（vibrancy + blur + lens），画图时纯属白耗 GPU——
 * 要转动再显式传秒数。
 */
@Composable
fun Modifier.auroraRing(
    cornerRadius: Dp = 16.dp,
    ringWidth: Dp = 1.6.dp,
    glowWidth: Dp = 3.5.dp,
    glowStrength: Float = 0.5f,
    spinSeconds: Int = 0,
): Modifier {
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE } }
    val matrix = remember { Matrix() }

    // 角度只在 draw 阶段读（不触发重组）；不转时用常量 State，避免起动画协程
    val angle: State<Float> = if (spinSeconds > 0) {
        rememberInfiniteTransition(label = "auroraRing").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(spinSeconds * 1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "auroraRingAngle"
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    return this.drawWithCache {
        val ringPx = ringWidth.toPx()
        val glowPx = glowWidth.toPx()
        val rPx = cornerRadius.toPx().coerceAtMost(size.minDimension / 2f)
        val cx = size.width / 2f
        val cy = size.height / 2f
        val shader = SweepGradient(cx, cy, AuroraColors, AuroraStops)
        onDrawBehind {
            matrix.setRotate(angle.value, cx, cy)
            shader.setLocalMatrix(matrix)
            paint.shader = shader
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                // 光晕：沿轮廓叠几层渐宽的半透明描边（外扩一圈，内侧边缘微微透色）
                val steps = 5
                for (i in steps downTo 1) {
                    val t = i / steps.toFloat()   // 1 = 最外
                    val falloff = (1f - t) * (1f - t)
                    paint.strokeWidth = ringPx + 2f * glowPx * t
                    paint.alpha = (255f * 0.30f * glowStrength * falloff).toInt().coerceIn(0, 255)
                    native.drawRoundRect(0f, 0f, size.width, size.height, rPx, rPx, paint)
                }
                // 主环
                paint.strokeWidth = ringPx
                paint.alpha = 255
                native.drawRoundRect(0f, 0f, size.width, size.height, rPx, rPx, paint)
            }
        }
    }
}
