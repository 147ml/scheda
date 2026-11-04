package com.scheda.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 全局遮罩层计数。
 *
 * Compose 的 Dialog / Popup 是独立 Window，采样不到页面内的 LayerBackdrop，
 * 单纯半透明白卡片看起来就是「白色弹窗」。这里用背面虚化来还原玻璃观感：
 * 遮罩层打开时把页面内容整体 blur，弹窗半透明背景透过去的就是模糊背景。
 */
object GlassOverlay {
    var count by mutableStateOf(0)
        private set

    fun enter() {
        count++
    }

    fun exit() {
        if (count > 0) count--
    }
}

/** Dialog / Popup 内容里调用：自动登记与注销遮罩状态。 */
@Composable
fun GlassOverlayEffect() {
    DisposableEffect(Unit) {
        GlassOverlay.enter()
        onDispose { GlassOverlay.exit() }
    }
}

/** 页面内容层用：有遮罩打开时虚化背景（Android 12+ 生效，低版本自动不模糊）。 */
fun Modifier.glassBackdropBlur(radius: Dp = 16.dp): Modifier = this.blur(
    if (GlassOverlay.count > 0) radius else 0.dp
)
