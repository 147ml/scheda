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
 */

package com.scheda.app.ui.components

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * Custom dropdown menu: same position/animation as Material DropdownMenu, but without
 * the 8dp forced vertical padding inside the content column.
 */
@Composable
fun SchedaDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    minWidth: Dp = 120.dp,
    properties: PopupProperties = PopupProperties(focusable = true),
    shape: Shape = RoundedCornerShape(12.dp),
    containerColor: Color = Color(0xFF2A2A2A),
    shadowElevation: Dp = 4.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val expandedState = remember { MutableTransitionState(false) }
    expandedState.targetState = expanded

    if (expandedState.currentState || expandedState.targetState) {
        val density = LocalDensity.current
        val positionProvider = remember(density) {
            SchedaDropdownMenuPositionProvider(density)
        }

        Popup(
            onDismissRequest = onDismissRequest,
            popupPositionProvider = positionProvider,
            properties = properties,
        ) {
            SchedaDropdownMenuContent(
                modifier = modifier,
                expandedState = expandedState,
                transformOrigin = { positionProvider.transformOrigin },
                minWidth = minWidth,
                shape = shape,
                containerColor = containerColor,
                shadowElevation = shadowElevation,
                content = content,
            )
        }
    }
}

// ── Content wrapper: Surface + animated Column, no forced padding ─────

@Composable
private fun SchedaDropdownMenuContent(
    modifier: Modifier,
    expandedState: MutableTransitionState<Boolean>,
    transformOrigin: () -> TransformOrigin,
    minWidth: Dp,
    shape: Shape,
    containerColor: Color,
    shadowElevation: Dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val transition = rememberTransition(expandedState, "SchedaDropdownMenu")

    // Use Material3's actual motion token values (FastSpatial → spring 0.7f/400f)
    val scale by transition.animateFloat(
        transitionSpec = { spring(dampingRatio = 0.7f, stiffness = 400f) }
    ) { expanded -> if (expanded) 1f else 0.8f }

    val alpha by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 120) }
    ) { expanded -> if (expanded) 1f else 0f }

    Surface(
        modifier = Modifier
            .width(minWidth)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
                this.transformOrigin = transformOrigin()
            },
        shape = shape,
        color = containerColor,
        shadowElevation = shadowElevation,
    ) {
        Column(
            modifier = modifier.fillMaxWidth(),
            content = content,
        )
    }
}

// ── Position provider (exact Material3 DropdownMenuPositionProvider logic) ─

internal data class SchedaDropdownMenuPositionProvider(
    val density: Density,
    private val verticalMargin: Int = with(density) { 48.dp.roundToPx() },
    private val horizontalMargin: Int = with(density) { 8.dp.roundToPx() },
) : PopupPositionProvider {

    var transformOrigin by mutableStateOf(TransformOrigin.Center)
        private set

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        // ── x-candidates (same as Material3 MenuAnchorPosition.Below) ──
        val xCandidates = listOf(
            // startToAnchorStart
            anchorBounds.left,
            // endToAnchorEnd
            anchorBounds.right - popupContentSize.width,
            // leftToWindowLeft or rightToWindowRight
            if (anchorBounds.center.x < windowSize.width / 2) {
                0 // leftToWindowLeft
            } else {
                windowSize.width - popupContentSize.width // rightToWindowRight
            },
        )

        // ── y-candidates (same as Material3 MenuAnchorPosition.Below) ──
        val yCandidates = listOf(
            // topToAnchorBottom
            anchorBounds.bottom,
            // bottomToAnchorTop
            anchorBounds.top - popupContentSize.height,
            // centerToAnchorTop
            anchorBounds.top - popupContentSize.height / 2,
            // topToWindowTop or bottomToWindowBottom
            if (anchorBounds.center.y < windowSize.height / 2) {
                0 // topToWindowTop
            } else {
                windowSize.height - popupContentSize.height // bottomToWindowBottom
            },
        )

        // ── pick fitting candidate (same iteration as Material) ──
        val contentOffsetX = with(density) { (0.dp).roundToPx() } // DpOffset(0,0)
        val contentOffsetY = with(density) { (0.dp).roundToPx() } // DpOffset(0,0)

        val x = pickCandidate(
            candidates = xCandidates,
            offset = contentOffsetX,
            menuSize = popupContentSize.width,
            windowSize = windowSize.width,
            margin = horizontalMargin,
        )
        val y = pickCandidate(
            candidates = yCandidates,
            offset = contentOffsetY,
            menuSize = popupContentSize.height,
            windowSize = windowSize.height,
            margin = verticalMargin,
        )

        val menuBounds = IntRect(offset = IntOffset(x, y), size = popupContentSize)
        transformOrigin = calculateTransformOrigin(anchorBounds, menuBounds)
        return IntOffset(x, y)
    }
}

private fun pickCandidate(
    candidates: List<Int>,
    offset: Int,
    menuSize: Int,
    windowSize: Int,
    margin: Int,
): Int {
    for (index in candidates.indices) {
        val candidate = candidates[index] + offset
        if (candidate >= margin && candidate + menuSize <= windowSize - margin) {
            return candidate
        }
        if (index == candidates.lastIndex) {
            return if (menuSize >= windowSize - 2 * margin) {
                Alignment.CenterHorizontally.align(
                    size = menuSize,
                    space = windowSize,
                    layoutDirection = LayoutDirection.Ltr,
                )
            } else {
                candidate.coerceIn(margin, windowSize - margin - menuSize)
            }
        }
    }
    return ((windowSize - menuSize) / 2).coerceIn(margin, windowSize - menuSize - margin)
}

// ── TransformOrigin calculation (copied from Material) ────────────────

internal fun calculateTransformOrigin(
    anchorBounds: IntRect,
    menuBounds: IntRect
): TransformOrigin {
    val pivotX = when {
        menuBounds.left >= anchorBounds.right -> 0f
        menuBounds.right <= anchorBounds.left -> 1f
        menuBounds.width == 0 -> 0f
        else -> {
            val intersectionCenter =
                (maxOf(anchorBounds.left, menuBounds.left) +
                    minOf(anchorBounds.right, menuBounds.right)) / 2
            (intersectionCenter - menuBounds.left).toFloat() / menuBounds.width
        }
    }
    val pivotY = when {
        menuBounds.top >= anchorBounds.bottom -> 0f
        menuBounds.bottom <= anchorBounds.top -> 1f
        menuBounds.height == 0 -> 0f
        else -> {
            val intersectionCenter =
                (maxOf(anchorBounds.top, menuBounds.top) +
                    minOf(anchorBounds.bottom, menuBounds.bottom)) / 2
            (intersectionCenter - menuBounds.top).toFloat() / menuBounds.height
        }
    }
    return TransformOrigin(pivotX, pivotY)
}
