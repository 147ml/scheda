package com.scheda.app.model

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 块内容坐标 → 世界坐标的**唯一**映射：
 * `anchor + R(rotation) · S(scale) · (p - 形心)`
 *
 * 语义：块内容形心（[blockContentCentroid]，不是 AABB 中心）落在 BlockRef 锚点 (x, y) 上。
 * 渲染（BlockRender.withBlockTransform）、包围盒（computeBlockRefWorldBounds / pfoWorldBounds）、
 * DXF 导出（DxfExporter.explodePrimitives）都必须等价于这一个映射，否则同一个块在不同路径下
 * 会出现在不同位置（渲染/选中框/导出三处对不上）。
 *
 * ⚠️ 不要写成「绕原点旋转后平移 (anchor - 形心 * scale)」：
 * 那样平移量不参与旋转，块一转内容就整体飘离锚点。历史 bug 实测：形心 (500,300)、
 * scale 1.5、旋转 30° 时偏移 453 个世界单位（块飞出屏幕）。
 */
fun blockMapPoint(
    wx: Float, wy: Float,
    anchorX: Float, anchorY: Float,
    rotationRad: Float,
    scale: Float,
    centroidX: Float, centroidY: Float
): Point2D {
    val dx = (wx - centroidX) * scale
    val dy = (wy - centroidY) * scale
    val c = cos(rotationRad)
    val s = sin(rotationRad)
    return Point2D(dx * c - dy * s + anchorX, dx * s + dy * c + anchorY)
}

/** 便捷重载：块内容点按 BlockRef 参数映射（形心由 [blockContentCentroid] 提供） */
fun blockMapPoint(
    wx: Float, wy: Float,
    ref: DrawingPrimitive.BlockRefPrimitive,
    centroid: Point2D
): Point2D = blockMapPoint(wx, wy, ref.x, ref.y, ref.rotation, ref.scale, centroid.x, centroid.y)

/** 角度/弧度互转（渲染侧 Canvas.rotate 用度） */
fun radToDegrees(rad: Float): Float = rad * 180f / PI.toFloat()
