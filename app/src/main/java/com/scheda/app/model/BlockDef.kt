package com.scheda.app.model

import androidx.compose.ui.graphics.Color

/**
 * 图块定义 — 可复用的图形组件。
 *
 * 画块工具画完→保存为 BlockDef，
 * 插入时生成一个 BlockInstance，磁吸吸附到线条。
 */
data class BlockDef(
    val id: String,
    val name: String,
    /** 图块包含的原始基元（使用绝对坐标） */
    val primitives: List<DrawingPrimitive>,
    /** 手动设定的磁吸点（世界坐标） */
    val snapPoints: List<SnapPoint>,
    /** 图块的预览缩略图信息 */
    val bounds: Bounds? = null
)

data class Bounds(
    val minX: Float, val minY: Float,
    val maxX: Float, val maxY: Float
)

/**
 * 画块时的草稿状态
 */
data class BlockDraft(
    val primitives: MutableList<DrawingPrimitive> = mutableListOf(),
    val snapPoints: MutableList<SnapPoint> = mutableListOf()
)
