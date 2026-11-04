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
 * 块内容线宽参考基准：块内图元 strokeWidth 的最大值（无有效值取 1f）。
 *
 * 存在的理由：DXF 导入按 CAD 口径把线宽存成 mm 量级（默认 0.25mm → strokeWidth 0.25），
 * 而画布画笔线宽是 1~20（默认 5），两者差一个数量级。块内容若直接按 0.25 画，
 * 插到画布上就是一根几乎看不见的细线。
 */
fun blockStrokeReference(prims: List<DrawingPrimitive>): Float {
    var max = 0f
    for (p in prims) {
        val w = p.strokeWidth
        if (w.isFinite() && w > max) max = w
    }
    return if (max > 0.0001f) max else 1f
}

/**
 * 块内容线宽系数 = 块引用线宽 / 块内参考线宽。
 *
 * 渲染时块内每个图元的线宽乘这个系数：块整体线宽跟随块引用（插入时=当前画笔线宽，
 * 之后可用工具栏“线宽”调），块内各条线之间的相对粗细比例保持不变。
 * 自己用画笔画的块参考值就等于引用线宽，系数为 1，外观不变。
 */
fun blockLineWidthScale(prims: List<DrawingPrimitive>, refStrokeWidth: Float): Float {
    if (!refStrokeWidth.isFinite() || refStrokeWidth <= 0.0001f) return 1f
    return refStrokeWidth / blockStrokeReference(prims)
}

/**
 * 画块时的草稿状态
 */
class BlockDraft(
    val primitives: MutableList<DrawingPrimitive> = mutableListOf(),
    val snapPoints: MutableList<SnapPoint> = mutableListOf()
)
