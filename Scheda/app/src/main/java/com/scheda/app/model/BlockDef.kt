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
 * 块内容形心：各基元包围盒中心的平均值。
 * 渲染（drawBlockRef）按"形心落在 BlockRef 锚点 (x, y)"锚定内容，
 * 插入/预览/包围盒计算都必须用同一形心才能与最终渲染对齐。
 */
fun blockContentCentroid(prims: List<DrawingPrimitive>): Point2D? {
    var cx = 0f; var cy = 0f; var count = 0
    for (prim in prims) {
        val b = when (prim) {
            is DrawingPrimitive.FreehandPath -> {
                if (prim.points.size < 2) null
                else { val xs = prim.points.map { it.x }; val ys = prim.points.map { it.y }
                    floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max()) }
            }
            is DrawingPrimitive.RectanglePrimitive -> {
                val xs = prim.corners.map { it.x }; val ys = prim.corners.map { it.y }
                floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
            }
            is DrawingPrimitive.CirclePrimitive -> {
                val r = maxOf(prim.radiusX, prim.radiusY)
                floatArrayOf(prim.centerX - r, prim.centerY - r, prim.centerX + r, prim.centerY + r)
            }
            is DrawingPrimitive.LinePrimitive -> {
                floatArrayOf(minOf(prim.startX, prim.endX), minOf(prim.startY, prim.endY),
                    maxOf(prim.startX, prim.endX), maxOf(prim.startY, prim.endY))
            }
            is DrawingPrimitive.NumberLabelPrimitive -> {
                val numChars = prim.value.toString().length.coerceAtLeast(1)
                val hw = prim.fontSize * 0.3f * numChars; val hh = prim.fontSize * 0.4f
                floatArrayOf(prim.x - hw, prim.y - hh, prim.x + hw, prim.y + hh)
            }
            is DrawingPrimitive.TextPrimitive -> {
                val numChars = prim.text.length.coerceAtLeast(1)
                val hw = prim.fontSize * 0.35f * numChars; val hh = prim.fontSize * 0.5f
                floatArrayOf(prim.x - hw, prim.y - hh, prim.x + hw, prim.y + hh)
            }
            else -> null
        }
        if (b != null) { cx += (b[0] + b[2]) / 2f; cy += (b[1] + b[3]) / 2f; count++ }
    }
    return if (count > 0) Point2D(cx / count, cy / count) else null
}

/**
 * 画块时的草稿状态
 */
class BlockDraft(
    val primitives: MutableList<DrawingPrimitive> = mutableListOf(),
    val snapPoints: MutableList<SnapPoint> = mutableListOf()
)
