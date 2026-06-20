package com.scheda.app.model

import androidx.compose.ui.graphics.Color

/** 图层 */
data class Layer(
    val id: Int,
    val name: String,
    val color: Color = Color.Black,
    val isVisible: Boolean = true,
    val isLocked: Boolean = false
)
