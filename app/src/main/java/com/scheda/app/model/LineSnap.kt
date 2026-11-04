package com.scheda.app.model

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** 吸附级别：顶点（线段端点 / 图形角点 / 线段交点 / 圆心）优先于边（线段本体 / 圆周）。 */
enum class SnapKind { VERTEX, EDGE }

/**
 * 一次吸附命中。
 *
 * [key] 用来判定"是否还是同一个吸附目标"（决定是否重播首次吸附动效）。
 * 顶点级 key 按坐标量化，而不是按图元索引——两条线共享一个端点时，
 * 每帧可能命中不同的线段对象，但那是同一个角点，key 必须稳定，否则会反复播吸附动画。
 */
data class SnapResult(val key: String, val x: Float, val y: Float, val kind: SnapKind)

/** 吸附池里的线段（世界坐标）。 */
data class SnapSegment(val key: String, val ax: Float, val ay: Float, val bx: Float, val by: Float)

/** 吸附池里的椭圆（世界坐标 + 自身旋转）。 */
data class SnapCircle(
    val key: String,
    val cx: Float,
    val cy: Float,
    val rx: Float,
    val ry: Float,
    val rotation: Float
)

/**
 * 直线端点吸附的纯几何实现（无 Compose / Android 依赖，便于单测）。
 *
 * 规则：
 *  1. 顶点级（线段端点、矩形/折线角点、线段与线段交点、圆心）优先，阈值内绝对压过边级；
 *  2. 边级（线段本体最近点、圆周最近点）次之；
 *  3. 同一目标用更大的退出阈值（滞回），避免在阈值边界反复吸附/脱离导致动效闪烁。
 */
object LineSnap {

    /**
     * 从画布图元构建吸附池。
     *
     * @param freehandMinGap 手绘折线抽稀步长（世界单位，<=0 不抽稀）。手绘点很密，
     *        不抽稀会让段数爆炸、每帧线性扫描变慢。
     */
    fun buildPool(
        primitives: List<DrawingPrimitive>,
        freehandMinGap: Float = 0f
    ): Pair<List<SnapSegment>, List<SnapCircle>> {
        val segments = ArrayList<SnapSegment>()
        val circles = ArrayList<SnapCircle>()
        for ((index, primitive) in primitives.withIndex()) {
            when (primitive) {
                is DrawingPrimitive.LinePrimitive -> {
                    segments += SnapSegment(
                        "L$index", primitive.startX, primitive.startY, primitive.endX, primitive.endY
                    )
                }
                is DrawingPrimitive.RectanglePrimitive -> {
                    val corners = primitive.corners
                    if (corners.size >= 4) {
                        // corners 是未旋转的轴对齐四角，渲染时才按 rotation 旋转（见 drawRectanglePrimitive）
                        val rotated = rotateCorners(corners, primitive.rotation)
                        for (k in 0 until 4) {
                            val a = rotated[k]
                            val b = rotated[(k + 1) % 4]
                            segments += SnapSegment("R${index}_$k", a.x, a.y, b.x, b.y)
                        }
                    }
                }
                is DrawingPrimitive.FreehandPath -> {
                    val pts = simplify(primitive.points, freehandMinGap)
                    for (k in 0 until pts.size - 1) {
                        segments += SnapSegment("F${index}_$k", pts[k].x, pts[k].y, pts[k + 1].x, pts[k + 1].y)
                    }
                    if (primitive.isClosed && pts.size >= 3) {
                        val a = pts.last()
                        val b = pts.first()
                        segments += SnapSegment("F${index}_c", a.x, a.y, b.x, b.y)
                    }
                }
                is DrawingPrimitive.CirclePrimitive -> {
                    val rx = abs(primitive.radiusX)
                    val ry = abs(primitive.radiusY)
                    val cx = primitive.centerX
                    val cy = primitive.centerY
                    val cosR = cos(primitive.rotation)
                    val sinR = sin(primitive.rotation)
                    fun rotPoint(lx: Float, ly: Float) = Point2D(
                        cx + lx * cosR - ly * sinR,
                        cy + lx * sinR + ly * cosR
                    )
                    // 半径为 0 的椭圆在渲染上就是一条线（正圆模式纯垂直/水平拖动会出现），
                    // 吸附池按线段处理，避免用 0 半径去做归一化。
                    when {
                        rx < 0.5f && ry < 0.5f -> Unit
                        rx < 0.5f -> {
                            val a = rotPoint(0f, -ry)
                            val b = rotPoint(0f, ry)
                            segments += SnapSegment("C$index", a.x, a.y, b.x, b.y)
                        }
                        ry < 0.5f -> {
                            val a = rotPoint(-rx, 0f)
                            val b = rotPoint(rx, 0f)
                            segments += SnapSegment("C$index", a.x, a.y, b.x, b.y)
                        }
                        else -> circles += SnapCircle("C$index", cx, cy, rx, ry, primitive.rotation)
                    }
                }
                else -> Unit
            }
        }
        return segments to circles
    }

    /**
     * 计算吸附命中；无命中返回 null（端点自由跟手）。
     *
     * @param prevKey 上一次的吸附目标 key，用于滞回（同目标的退出阈值更大）
     */
    fun snap(
        px: Float,
        py: Float,
        segments: List<SnapSegment>,
        circles: List<SnapCircle>,
        vertexEnter: Float,
        vertexExit: Float,
        edgeEnter: Float,
        edgeExit: Float,
        prevKey: String? = null
    ): SnapResult? {
        val nearLimit = maxOf(vertexEnter, vertexExit, edgeEnter, edgeExit)
        val nearLimitSq = nearLimit * nearLimit

        var bestVertex: SnapResult? = null
        var bestVertexSq = Float.MAX_VALUE
        val nearSegments = ArrayList<SnapSegment>()

        fun considerVertex(x: Float, y: Float) {
            val dx = x - px
            val dy = y - py
            val dSq = dx * dx + dy * dy
            val key = vertexKey(x, y)
            val limit = if (key == prevKey) vertexExit else vertexEnter
            if (dSq <= limit * limit && dSq < bestVertexSq) {
                bestVertexSq = dSq
                bestVertex = SnapResult(key, x, y, SnapKind.VERTEX)
            }
        }

        for (s in segments) {
            if (distSqToSegment(px, py, s) <= nearLimitSq) nearSegments += s
            considerVertex(s.ax, s.ay)
            considerVertex(s.bx, s.by)
        }
        for (c in circles) considerVertex(c.cx, c.cy)

        // 交点只需要在"手指附近的线段"之间求，局部集合通常 1~3 条，成本可以忽略。
        if (nearSegments.size in 2..16) {
            for (i in 0 until nearSegments.size - 1) {
                for (j in i + 1 until nearSegments.size) {
                    val hit = intersect(nearSegments[i], nearSegments[j]) ?: continue
                    considerVertex(hit.x, hit.y)
                }
            }
        }
        bestVertex?.let { return it }

        var bestEdge: SnapResult? = null
        var bestEdgeSq = Float.MAX_VALUE

        for (s in segments) {
            val q = closestOnSegment(px, py, s) ?: continue
            val dx = q.x - px
            val dy = q.y - py
            val dSq = dx * dx + dy * dy
            val limit = if (s.key == prevKey) edgeExit else edgeEnter
            if (dSq <= limit * limit && dSq < bestEdgeSq) {
                bestEdgeSq = dSq
                bestEdge = SnapResult(s.key, q.x, q.y, SnapKind.EDGE)
            }
        }
        for (c in circles) {
            val q = closestOnCircle(px, py, c)
            val dx = q.x - px
            val dy = q.y - py
            val dSq = dx * dx + dy * dy
            val limit = if (c.key == prevKey) edgeExit else edgeEnter
            if (dSq <= limit * limit && dSq < bestEdgeSq) {
                bestEdgeSq = dSq
                bestEdge = SnapResult(c.key, q.x, q.y, SnapKind.EDGE)
            }
        }
        return bestEdge
    }

    // ── 内部几何 ──

    /** 顶点 key 按 0.01 世界单位量化：共享端点 / 重复交点得到同一个 key。 */
    private fun vertexKey(x: Float, y: Float): String {
        val qx = (x * 100f).roundToInt()
        val qy = (y * 100f).roundToInt()
        return "V$qx:$qy"
    }

    private fun rotateCorners(corners: List<Point2D>, rotation: Float): List<Point2D> {
        if (abs(rotation) < 0.0001f) return corners
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in corners) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        val cosR = cos(rotation)
        val sinR = sin(rotation)
        return corners.map {
            val dx = it.x - cx
            val dy = it.y - cy
            Point2D(cx + dx * cosR - dy * sinR, cy + dx * sinR + dy * cosR)
        }
    }

    private fun simplify(points: List<Point2D>, minGap: Float): List<Point2D> {
        if (points.size <= 2 || minGap <= 0f) return points
        val gapSq = minGap * minGap
        val out = ArrayList<Point2D>(points.size / 2 + 2)
        out += points.first()
        var last = points.first()
        for (i in 1 until points.size) {
            val p = points[i]
            val dx = p.x - last.x
            val dy = p.y - last.y
            if (i == points.size - 1 || dx * dx + dy * dy >= gapSq) {
                out += p
                last = p
            }
        }
        return out
    }

    private fun intersect(a: SnapSegment, b: SnapSegment): Point2D? {
        val r1x = a.bx - a.ax
        val r1y = a.by - a.ay
        val r2x = b.bx - b.ax
        val r2y = b.by - b.ay
        val den = r1x * r2y - r1y * r2x
        if (abs(den) < 1e-6f) return null
        val qpx = b.ax - a.ax
        val qpy = b.ay - a.ay
        val t = (qpx * r2y - qpy * r2x) / den
        val u = (qpx * r1y - qpy * r1x) / den
        if (t < 0f || t > 1f || u < 0f || u > 1f) return null
        return Point2D(a.ax + t * r1x, a.ay + t * r1y)
    }

    private fun closestOnSegment(px: Float, py: Float, s: SnapSegment): Point2D? {
        val dx = s.bx - s.ax
        val dy = s.by - s.ay
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-6f) return Point2D(s.ax, s.ay)
        val t = (((px - s.ax) * dx + (py - s.ay) * dy) / lenSq).coerceIn(0f, 1f)
        return Point2D(s.ax + t * dx, s.ay + t * dy)
    }

    private fun distSqToSegment(px: Float, py: Float, s: SnapSegment): Float {
        val q = closestOnSegment(px, py, s) ?: return Float.MAX_VALUE
        val dx = q.x - px
        val dy = q.y - py
        return dx * dx + dy * dy
    }

    /**
     * 椭圆的最近点：把点变换到椭圆局部坐标系后按方向归一化再变换回来。
     * 不是严格最近点（严格解要牛顿迭代），但对手指吸附的视觉精度足够。
     * internal 便于单测直接验证几何。
     */
    internal fun closestOnCircle(px: Float, py: Float, c: SnapCircle): Point2D {
        val cosR = cos(c.rotation)
        val sinR = sin(c.rotation)
        val dx = px - c.cx
        val dy = py - c.cy
        // 逆旋转到局部
        val lx = dx * cosR + dy * sinR
        val ly = -dx * sinR + dy * cosR
        val u = lx / c.rx
        val v = ly / c.ry
        val n = sqrt(u * u + v * v)
        val ux: Float
        val uy: Float
        if (n < 1e-6f) {
            ux = 1f
            uy = 0f
        } else {
            ux = u / n
            uy = v / n
        }
        val ox = ux * c.rx
        val oy = uy * c.ry
        // 正旋转回世界
        return Point2D(c.cx + ox * cosR - oy * sinR, c.cy + ox * sinR + oy * cosR)
    }
}
