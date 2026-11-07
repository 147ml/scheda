/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified from androidx.compose.material3.DropdownMenuContent /
 * DropdownMenuPositionProvider to remove forced 8dp vertical padding.
 *
 * Scheda 下拉菜单 — 同窗浮层真玻璃版：
 * Popup / Dialog 是独立 Window，采不到页面内 LayerBackdrop（玻璃永远是空的），
 * 因此菜单不再走 Popup：锚点在自己的 Box 里捕获窗口坐标，菜单内容注册进全局
 * GlassMenuHost 单槽，由页面根 Box 末尾的 GlassMenuLayer 统一绘制为真玻璃卡片。
 * 定位候选与 transformOrigin 算法沿用 Material3 的对应实现。
 */
package com.scheda.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** 菜单注册数据：锚点窗口坐标（live State）+ 内容 + 关闭回调。 */
class GlassMenuSpec(
    val owner: Any,
    val anchor: State<IntRect>,
    val minWidth: Dp,
    val fallbackColor: Color,
    val onDismissRequest: () -> Unit,
    val contentModifier: Modifier,
    val content: @Composable ColumnScope.() -> Unit,
)

/** 全局菜单槽（单槽）：同一时刻至多一个下拉菜单。 */
object GlassMenuHost {
    var spec by mutableStateOf<GlassMenuSpec?>(null)
        private set

    fun show(s: GlassMenuSpec) { spec = s }
    fun hide(owner: Any) { if (spec?.owner === owner) spec = null }
    fun clear() { spec = null }
}

/**
 * 菜单浮层：放在页面根 Box 的最末尾（必须在所有 layerBackdrop 捕获层之外，自指循环铁律）。
 * 菜单关闭时本层零组合、零命中，不拦截画布手势。
 */
@Composable
fun GlassMenuLayer(backdrop: Backdrop?, modifier: Modifier = Modifier) {
    val current = GlassMenuHost.spec
    var shown by remember { mutableStateOf<GlassMenuSpec?>(null) }
    val ts = remember { MutableTransitionState(false) }

    LaunchedEffect(current) {
        if (current != null) {
            shown = current
            ts.targetState = true
        } else {
            ts.targetState = false
            while (!ts.isIdle) delay(16)   // 退场动画播完再摘内容
            shown = null
        }
    }
    DisposableEffect(Unit) { onDispose { GlassMenuHost.clear() } }

    val spec = shown ?: return
    if (!ts.currentState && !ts.targetState) return

    // ⚠️ 不要在此登记 GlassOverlayEffect：菜单弹出触发全屏虚化是已修掉的观感 bug（d8b8083）
    val active = current != null
    BackHandler(enabled = active) { current?.onDismissRequest?.invoke() }

    var menuSize by remember { mutableStateOf(IntSize.Zero) }
    var layerSize by remember { mutableStateOf(IntSize.Zero) }
    var measuredOwner by remember { mutableStateOf<Any?>(null) }
    val anchor = spec.anchor.value
    val density = LocalDensity.current

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { layerSize = it.size }
            .pointerInput(Unit) { detectTapGestures { current?.onDismissRequest?.invoke() } }
    ) {
        val offset = with(density) { computeMenuOffset(anchor, menuSize, layerSize, this) }
        val menuRect = IntRect(offset.x, offset.y, offset.x + menuSize.width, offset.y + menuSize.height)
        val origin = calcTransformOrigin(anchor, menuRect)
        val transition = rememberTransition(ts, "GlassMenu")
        val scale by transition.animateFloat(
            transitionSpec = { spring(dampingRatio = 0.7f, stiffness = 400f) }
        ) { open -> if (open) 1f else 0.8f }
        val alpha by transition.animateFloat(
            transitionSpec = { tween(durationMillis = 120) }
        ) { open -> if (open) 1f else 0f }
        // 本菜单尺寸量到前整体不画（消首帧错位闪跳）
        val ready = measuredOwner === spec.owner && menuSize.width > 0
        // ⚠️ 动画变换必须走 layerBlock 通道：库对它做采样逆变换补偿，玻璃与背景 1:1 对齐；
        // 变换放外层 graphicsLayer 会让采样按布局矩形取整块 → 弹出期间"提前透出"未盖住的内容
        // （库 TODO 已知缺陷：outer transformations lead to wrong position calculation）。
        // 平移补偿把缩放原点残差精确归零：net = p − offset + (1−s)·O + T，取 T = −(1−s)·O。
        val layerBlock: GraphicsLayerScope.() -> Unit = {
            scaleX = scale
            scaleY = scale
            transformOrigin = origin
            val ox = origin.pivotFractionX * menuSize.width
            val oy = origin.pivotFractionY * menuSize.height
            translationX = -(1f - scale) * ox
            translationY = -(1f - scale) * oy
            this.alpha = if (ready) alpha else 0f
        }

        Box(
            Modifier
                .offset { offset }
                .width(spec.minWidth)
                .onGloballyPositioned { menuSize = it.size; measuredOwner = spec.owner }
                // 卡片自身吞掉空白区点击，防止点菜单内部误触发外点关闭
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .glassSurface(
                    backdrop = backdrop,
                    shape = RoundedCornerShape(12.dp),
                    tint = Color(0x59FFFFFF),
                    fallback = spec.fallbackColor,
                    layerBlock = layerBlock,
                )
                .border(1.dp, Color(0x80FFFFFF), RoundedCornerShape(12.dp))
        ) {
            Column(spec.contentModifier.fillMaxWidth(), content = spec.content)
        }
    }
}

/**
 * 下拉菜单（注册进 GlassMenuLayer 绘制）。
 * 必须放在只包含「锚点 + 本菜单」的 Box 内部：matchParentSize 捕获节点不参与父测量，
 * 父 Box 边界即锚点边界（全部 4 个调用点已核实满足此前提）。
 */
@Composable
fun BoxScope.SchedaDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    minWidth: Dp = 120.dp,
    containerColor: Color = Color(0xF7FFFFFF),
    shadowElevation: Dp = 4.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val anchorState = remember { mutableStateOf(IntRect.Zero) }
    val owner = remember { Any() }
    // 闭包过期防护：内容/回调每帧取最新值，spec 本体只建一次
    val contentState = rememberUpdatedState(content)
    val dismissState = rememberUpdatedState(onDismissRequest)

    Box(
        Modifier
            .matchParentSize()
            .onGloballyPositioned { c ->
                val r = c.boundsInWindow()
                anchorState.value = IntRect(
                    r.left.roundToInt(), r.top.roundToInt(),
                    r.right.roundToInt(), r.bottom.roundToInt()
                )
            }
    )

    val spec = remember(owner) {
        GlassMenuSpec(
            owner = owner,
            anchor = anchorState,
            minWidth = minWidth,
            fallbackColor = containerColor,
            onDismissRequest = { dismissState.value() },
            contentModifier = modifier,
            content = {
                val userContent = contentState.value
                userContent(this)
            },
        )
    }
    DisposableEffect(expanded) {
        if (expanded) GlassMenuHost.show(spec) else GlassMenuHost.hide(owner)
        onDispose { GlassMenuHost.hide(owner) }
    }
}

// ── 定位（Material3 DropdownMenuPositionProvider 候选算法） ──────────

private fun computeMenuOffset(
    anchor: IntRect,
    menu: IntSize,
    layer: IntSize,
    density: Density,
): IntOffset {
    if (menu.width == 0 || menu.height == 0 || layer.width == 0) {
        return IntOffset(anchor.left, anchor.bottom)
    }
    val hMargin = with(density) { 8.dp.roundToPx() }
    val vMargin = with(density) { 48.dp.roundToPx() }
    val xCandidates = listOf(
        anchor.left,                       // 锚点左对齐
        anchor.right - menu.width,          // 锚点右对齐
        if (anchor.center.x < layer.width / 2) 0 else layer.width - menu.width, // 贴边兜底
    )
    val yCandidates = listOf(
        anchor.bottom,                     // 锚点下方
        anchor.top - menu.height,          // 上翻
        anchor.top - menu.height / 2,      // 与锚点垂直居中
        if (anchor.center.y < layer.height / 2) 0 else layer.height - menu.height,
    )
    val x = pickCandidate(xCandidates, menu.width, layer.width, hMargin)
    val y = pickCandidate(yCandidates, menu.height, layer.height, vMargin)
    return IntOffset(x, y)
}

private fun pickCandidate(candidates: List<Int>, menuSize: Int, windowSize: Int, margin: Int): Int {
    for (index in candidates.indices) {
        val candidate = candidates[index]
        if (candidate >= margin && candidate + menuSize <= windowSize - margin) return candidate
        if (index == candidates.lastIndex) {
            return if (menuSize >= windowSize - 2 * margin) {
                (windowSize - menuSize) / 2
            } else {
                candidate.coerceIn(margin, windowSize - margin - menuSize)
            }
        }
    }
    return 0
}

private fun calcTransformOrigin(anchor: IntRect, menu: IntRect): TransformOrigin {
    val pivotX = when {
        menu.left >= anchor.right -> 0f
        menu.right <= anchor.left -> 1f
        menu.width == 0 -> 0f
        else -> {
            val intersectionCenter =
                (maxOf(anchor.left, menu.left) + minOf(anchor.right, menu.right)) / 2
            (intersectionCenter - menu.left).toFloat() / menu.width
        }
    }
    val pivotY = when {
        menu.top >= anchor.bottom -> 0f
        menu.bottom <= anchor.top -> 1f
        menu.height == 0 -> 0f
        else -> {
            val intersectionCenter =
                (maxOf(anchor.top, menu.top) + minOf(anchor.bottom, menu.bottom)) / 2
            (intersectionCenter - menu.top).toFloat() / menu.height
        }
    }
    return TransformOrigin(pivotX, pivotY)
}
