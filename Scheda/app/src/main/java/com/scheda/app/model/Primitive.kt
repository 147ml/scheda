package com.scheda.app.model

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import com.scheda.app.model.PendingEdit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 2D 坐标点 */
data class Point2D(val x: Float, val y: Float) {
    companion object {
        /** Squared distance from this point to another */
        fun distSquared(a: Point2D, b: Point2D): Float {
            val dx = a.x - b.x; val dy = a.y - b.y
            return dx * dx + dy * dy
        }
    }
}

/** 绘图基元 — 所有图形的基类 */
sealed class DrawingPrimitive(
    open val color: Color,
    open val strokeWidth: Float,
    open val layerId: Int,
    open val lineStyle: LineStyle = LineStyle(),  // 新增线型
    open val lineScaleFactor: Float = 1f           // 每对象线宽倍率
) {
    companion object {
        /** Compute AABB of 4 corners rotated around center by angle. */
        fun rotAABB(cx: Float, cy: Float, hw: Float, hh: Float, angle: Float): FloatArray {
            val cosR = cos(angle); val sinR = sin(angle)
            val corners = listOf(
                cx + (-hw) * cosR - (-hh) * sinR to cy + (-hw) * sinR + (-hh) * cosR,
                cx + (+hw) * cosR - (-hh) * sinR to cy + (+hw) * sinR + (-hh) * cosR,
                cx + (+hw) * cosR - (+hh) * sinR to cy + (+hw) * sinR + (+hh) * cosR,
                cx + (-hw) * cosR - (+hh) * sinR to cy + (-hw) * sinR + (+hh) * cosR,
            )
            val xs = corners.map { it.first }; val ys = corners.map { it.second }
            return floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
        }

        fun dxfBoundsFor(primitive: DrawingPrimitive): FloatArray? {
            return primitive.computeBounds(null)
        }

        /** Distance from point to line segment AB */
        fun distToSegment(pt: Point2D, a: Point2D, b: Point2D): Float {
            val abx = b.x - a.x; val aby = b.y - a.y
            val lenSq = abx * abx + aby * aby
            if (lenSq < 0.0001f) return sqrt(Point2D.distSquared(pt, a))
            var t = ((pt.x - a.x) * abx + (pt.y - a.y) * aby) / lenSq
            t = t.coerceIn(0f, 1f)
            val px = a.x + t * abx; val py = a.y + t * aby
            return sqrt(Point2D.distSquared(pt, Point2D(px, py)))
        }
    }

    /** Compute axis-aligned bounding box. measurePaint needed for text types. */
    abstract fun computeBounds(measurePaint: Paint? = null): FloatArray?

    /** Check if any part of the primitive intersects the selection fence. */
    abstract fun fenceHitsGeometry(fence: Bounds): Boolean

    /** Minimum distance from point to this primitive's geometry. */
    abstract fun distanceTo(point: Point2D): Float

    /** Bake PendingEdit transforms (offset/rotation/scale) into a new primitive. */
    abstract fun applyTransform(pe: PendingEdit): DrawingPrimitive

    /** Intrinsic rotation of this primitive (radians). 0f for primitives without rotation. */
    open val intrinsicRotation: Float get() = 0f

    /** 中文类型名称 */
    abstract val typeName: String
    /** Shift all coordinates by (dx, dy). Returns new instance. */
    abstract fun shiftPrimitive(dx: Float, dy: Float): DrawingPrimitive
    /** Deep copy. */
    abstract fun deepCopy(): DrawingPrimitive
    /** Mirror around centroid (cx, cy). flipY=false 绕竖直轴翻转 x；flipY=true 绕水平轴翻转 y（横屏模式） */
    abstract fun mirrorPrimitive(cx: Float, cy: Float, flipY: Boolean = false): DrawingPrimitive
    /** DXF entity count. */
    abstract fun dxfEntityCount(): Int
    /** DXF handle count. */
    abstract fun dxfHandleCount(): Int

    /** Copy with new color. */
    abstract fun withColor(color: Color): DrawingPrimitive
    /** Copy with new layer ID. */
    abstract fun withLayerId(id: Int): DrawingPrimitive
    /** Copy with new line scale factor. */
    abstract fun withLineScaleFactor(factor: Float): DrawingPrimitive

    /** 自由手绘路径 */
    data class FreehandPath(
        val points: List<Point2D>,
        val isClosed: Boolean = false,
        override val color: Color,
        override val strokeWidth: Float,
        override val layerId: Int = 1,
        override val lineStyle: LineStyle = LineStyle(),
        override val lineScaleFactor: Float = 1f,
        val sharpCorners: Set<Int> = emptySet()
    ) : DrawingPrimitive(color, strokeWidth, layerId, lineStyle, lineScaleFactor) {

        override fun computeBounds(measurePaint: Paint?): FloatArray? {
            if (points.size < 2) return null
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            // 包络垫一整条线宽：绘制描边宽度会乘 globalLineScale/lineScaleFactor，
            // 只垫半宽时线型比例>1会导致 PFO 包围盒小于实际元素
            val pad = strokeWidth
            val n = points.size
            if (n >= 3) {
                for (i in 1 until n - 1) {
                    val p0 = points[i - 1]; val p1 = points[i]
                    val p2 = points[i + 1]
                    val cp1x = p1.x + (p2.x - p0.x) / 6f
                    val cp1y = p1.y + (p2.y - p0.y) / 6f
                    val cp2x = if (i + 2 < n) p2.x - (points[i + 2].x - p1.x) / 6f else p2.x - (p2.x - p1.x) / 3f
                    val cp2y = if (i + 2 < n) p2.y - (points[i + 2].y - p1.y) / 6f else p2.y - (p2.y - p1.y) / 3f
                    for (pt in listOf(p0, p1, p2, Point2D(cp1x, cp1y), Point2D(cp2x, cp2y))) {
                        if (pt.x < minX) minX = pt.x; if (pt.y < minY) minY = pt.y
                        if (pt.x > maxX) maxX = pt.x; if (pt.y > maxY) maxY = pt.y
                    }
                }
                val last = points[n - 1]
                if (last.x < minX) minX = last.x; if (last.y < minY) minY = last.y
                if (last.x > maxX) maxX = last.x; if (last.y > maxY) maxY = last.y
            } else {
                for (pt in points) {
                    if (pt.x < minX) minX = pt.x; if (pt.y < minY) minY = pt.y
                    if (pt.x > maxX) maxX = pt.x; if (pt.y > maxY) maxY = pt.y
                }
            }
            return floatArrayOf(minX - pad, minY - pad, maxX + pad, maxY + pad)
        }

        override fun fenceHitsGeometry(fence: Bounds): Boolean {
            fun segmentHitsRect(ax: Float, ay: Float, bx: Float, by: Float): Boolean {
                if (ax >= fence.minX && ax <= fence.maxX && ay >= fence.minY && ay <= fence.maxY) return true
                if (bx >= fence.minX && bx <= fence.maxX && by >= fence.minY && by <= fence.maxY) return true
                val minX = minOf(ax, bx); val maxX = maxOf(ax, bx)
                val minY = minOf(ay, by); val maxY = maxOf(ay, by)
                if (maxX < fence.minX || minX > fence.maxX || maxY < fence.minY || minY > fence.maxY) return false
                fun ccw(px: Float, py: Float, qx: Float, qy: Float, rx: Float, ry: Float): Int {
                    val cross = (qx - px) * (ry - py) - (qy - py) * (rx - px)
                    return if (cross > 0) 1 else if (cross < 0) -1 else 0
                }
                fun segSeg(p1x: Float, p1y: Float, p2x: Float, p2y: Float,
                           q1x: Float, q1y: Float, q2x: Float, q2y: Float): Boolean {
                    val o1 = ccw(p1x, p1y, p2x, p2y, q1x, q1y)
                    val o2 = ccw(p1x, p1y, p2x, p2y, q2x, q2y)
                    val o3 = ccw(q1x, q1y, q2x, q2y, p1x, p1y)
                    val o4 = ccw(q1x, q1y, q2x, q2y, p2x, p2y)
                    return o1 != o2 && o3 != o4
                }
                val l = fence.minX; val r = fence.maxX; val t = fence.minY; val b = fence.maxY
                return segSeg(ax, ay, bx, by, l, t, l, b) ||
                       segSeg(ax, ay, bx, by, r, t, r, b) ||
                       segSeg(ax, ay, bx, by, l, t, r, t) ||
                       segSeg(ax, ay, bx, by, l, b, r, b)
            }
            val pts = points
            for (i in 1 until pts.size) {
                if (segmentHitsRect(pts[i-1].x, pts[i-1].y, pts[i].x, pts[i].y)) return true
            }
            return if (isClosed && pts.size > 2)
                segmentHitsRect(pts.last().x, pts.last().y, pts[0].x, pts[0].y)
            else false
        }

        override fun distanceTo(point: Point2D): Float {
            if (points.size < 2) return Float.MAX_VALUE
            return points.zipWithNext().minOf { (a, b) -> distToSegment(point, a, b) }
        }

        override fun applyTransform(pe: PendingEdit): DrawingPrimitive {
            val dx = pe.offsetX; val dy = pe.offsetY
            val sx = pe.scaleX; val sy = pe.scaleY
            val cosR = cos(pe.rotation.toDouble()).toFloat(); val sinR = sin(pe.rotation.toDouble()).toFloat()
            val bounds = pe.bounds
            val cx0 = bounds?.let { (it.minX + it.maxX) / 2f } ?: 0f
            val cy0 = bounds?.let { (it.minY + it.maxY) / 2f } ?: 0f
            fun transform(wx: Float, wy: Float): Point2D {
                val x = (wx - cx0) * sx; val y = (wy - cy0) * sy
                val rx = x * cosR - y * sinR + cx0 + dx; val ry = x * sinR + y * cosR + cy0 + dy
                return Point2D(rx, ry)
            }
            if (dx == 0f && dy == 0f && pe.rotation == 0f && sx == 1f && sy == 1f) return this
            return copy(points = points.map { transform(it.x, it.y) })
        }

        override fun withColor(color: Color): DrawingPrimitive = copy(color = color)
        override fun withLayerId(id: Int): DrawingPrimitive = copy(layerId = id)
        override fun withLineScaleFactor(factor: Float): DrawingPrimitive = copy(lineScaleFactor = factor)

        override val typeName: String get() = "手绘"
        override fun shiftPrimitive(dx: Float, dy: Float): DrawingPrimitive = copy(points = points.map { Point2D(it.x + dx, it.y + dy) })
        override fun deepCopy(): DrawingPrimitive = copy()
        override fun mirrorPrimitive(cx: Float, cy: Float, flipY: Boolean): DrawingPrimitive =
            copy(points = points.map { if (flipY) Point2D(it.x, cy * 2 - it.y) else Point2D(cx * 2 - it.x, it.y) })
        override fun dxfEntityCount(): Int { val segs = maxOf(0, points.size - 1); return if (lineStyle.type == LineType.LIGHTNING) { var extra = 0; for (i in 0 until points.size - 1) { val ddx = (points[i + 1].x - points[i].x).toDouble(); val ddy = (points[i + 1].y - points[i].y).toDouble(); val len = sqrt(ddx * ddx + ddy * ddy); if (len < 1.0) continue; val nn = maxOf(2, (len / 120.0).toInt()); extra += 2 * nn }; segs * 8 + extra } else 1 }
        override fun dxfHandleCount(): Int = dxfEntityCount()
    }

    /** 矩形 */
    data class RectanglePrimitive(
        val corners: List<Point2D>,
        val rotation: Float = 0f,
        override val color: Color,
        override val strokeWidth: Float,
        override val layerId: Int = 1,
        override val lineStyle: LineStyle = LineStyle(),
        override val lineScaleFactor: Float = 1f
    ) : DrawingPrimitive(color, strokeWidth, layerId, lineStyle, lineScaleFactor) {

        /** 从旧格式两点+旋转构造（旧文件兼容） */
        constructor(
            startX: Float, startY: Float, endX: Float, endY: Float,
            rotation: Float, color: Color, strokeWidth: Float,
            layerId: Int, lineStyle: LineStyle, lineScaleFactor: Float
        ) : this(
            corners = run {
                val cx = (startX + endX) / 2f; val cy = (startY + endY) / 2f
                val hw = abs(endX - startX) / 2f; val hh = abs(endY - startY) / 2f
                val cr = kotlin.math.cos(rotation.toDouble()).toFloat()
                val sr = kotlin.math.sin(rotation.toDouble()).toFloat()
                listOf(
                    Point2D(cx - hw * cr + hh * sr, cy - hw * sr - hh * cr),
                    Point2D(cx + hw * cr + hh * sr, cy + hw * sr - hh * cr),
                    Point2D(cx + hw * cr - hh * sr, cy + hw * sr + hh * cr),
                    Point2D(cx - hw * cr - hh * sr, cy - hw * sr + hh * cr),
                )
            },
            rotation = rotation,
            color = color, strokeWidth = strokeWidth,
            layerId = layerId, lineStyle = lineStyle, lineScaleFactor = lineScaleFactor
        )

        override val intrinsicRotation: Float get() = rotation
        override fun computeBounds(measurePaint: Paint?): FloatArray? {
            // 返回轴对齐 AABB（不含旋转），旋转由 PFO 的 frameRotation 处理
            val xs = corners.map { it.x }; val ys = corners.map { it.y }
            return floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
        }

        /** 世界坐标 AABB（含旋转）：多选合并包围盒等需要真实世界范围的场景用，
         *  旋转方式与 drawRectanglePrimitive 一致（绕 AABB 中心旋转 4 角） */
        fun worldBounds(): FloatArray {
            val xs = corners.map { it.x }; val ys = corners.map { it.y }
            val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
            if (abs(rotation) < 0.001f) return floatArrayOf(minX, minY, maxX, maxY)
            return rotAABB((minX + maxX) / 2f, (minY + maxY) / 2f,
                (maxX - minX) / 2f, (maxY - minY) / 2f, rotation)
        }

        override fun fenceHitsGeometry(fence: Bounds): Boolean {
            fun segHit(ax: Float, ay: Float, bx: Float, by: Float): Boolean {
                if (ax in fence.minX..fence.maxX && ay in fence.minY..fence.maxY) return true
                if (bx in fence.minX..fence.maxX && by in fence.minY..fence.maxY) return true
                val mx = minOf(ax, bx); val Mx = maxOf(ax, bx)
                val my = minOf(ay, by); val My = maxOf(ay, by)
                if (Mx < fence.minX || mx > fence.maxX || My < fence.minY || my > fence.maxY) return false
                fun ccw(px: Float, py: Float, qx: Float, qy: Float, rx: Float, ry: Float): Int {
                    val cross = (qx - px) * (ry - py) - (qy - py) * (rx - px)
                    return if (cross > 0f) 1 else if (cross < 0f) -1 else 0
                }
                fun ss(p1x: Float, p1y: Float, p2x: Float, p2y: Float,
                       q1x: Float, q1y: Float, q2x: Float, q2y: Float) =
                    ccw(p1x, p1y, p2x, p2y, q1x, q1y) != ccw(p1x, p1y, p2x, p2y, q2x, q2y) &&
                    ccw(q1x, q1y, q2x, q2y, p1x, p1y) != ccw(q1x, q1y, q2x, q2y, p2x, p2y)
                val l = fence.minX; val r = fence.maxX; val t = fence.minY; val b = fence.maxY
                return ss(ax, ay, bx, by, l, t, l, b) || ss(ax, ay, bx, by, r, t, r, b) ||
                       ss(ax, ay, bx, by, l, t, r, t) || ss(ax, ay, bx, by, l, b, r, b)
            }
            for (i in 0 until 4) {
                val j = (i + 1) % 4
                if (segHit(corners[i].x, corners[i].y, corners[j].x, corners[j].y)) return true
            }
            return false
        }

        override fun distanceTo(point: Point2D): Float {
            val pts = rectToPoints()
            return (pts + pts.first()).zipWithNext().minOf { (a, b) -> distToSegment(point, a, b) }
        }

        private fun rectToPoints(): List<Point2D> {
            val xs = corners.map { it.x }; val ys = corners.map { it.y }
            val w = xs.max() - xs.min(); val h = ys.max() - ys.min()
            if (w < 0.01f && h < 0.01f) return listOf(corners.first())
            if (!w.isFinite() || !h.isFinite() || w > 1e8f || h > 1e8f) return emptyList()
            val segX = maxOf(4, minOf(200, (w / 20f + 0.5f).toInt()))
            val segY = maxOf(4, minOf(200, (h / 20f + 0.5f).toInt()))
            val pts = mutableListOf<Point2D>()
            for (i in 0 until 4) {
                val j = (i + 1) % 4
                val segs = if (i % 2 == 0) segX else segY
                for (k in 0 until segs) {
                    val t = k.toFloat() / segs
                    pts.add(Point2D(
                        corners[i].x + t * (corners[j].x - corners[i].x),
                        corners[i].y + t * (corners[j].y - corners[i].y)
                    ))
                }
            }
            return pts
        }

        override fun applyTransform(pe: PendingEdit): DrawingPrimitive {
            val dx = pe.offsetX; val dy = pe.offsetY
            val sx = pe.scaleX; val sy = pe.scaleY
            val cosR = cos(pe.rotation.toDouble()).toFloat(); val sinR = sin(pe.rotation.toDouble()).toFloat()
            val cx0 = pe.pivotX  // 固定旋转轴，不随 bounds 漂移
            val cy0 = pe.pivotY
            fun transform(wx: Float, wy: Float): Point2D {
                var x = (wx - cx0) * sx; var y = (wy - cy0) * sy
                val rx = x * cosR - y * sinR + cx0 + dx; val ry = x * sinR + y * cosR + cy0 + dy
                return Point2D(rx, ry)
            }
            if (dx == 0f && dy == 0f && pe.rotation == 0f && sx == 1f && sy == 1f) return this
            val newRotation = (rotation + pe.rotation) % (2f * kotlin.math.PI.toFloat())
            val t = corners.map { transform(it.x, it.y) }
            val w = sqrt(((t[1].x - t[0].x) * (t[1].x - t[0].x) +
                (t[1].y - t[0].y) * (t[1].y - t[0].y)).toDouble()).toFloat()
            val h = sqrt(((t[2].x - t[1].x) * (t[2].x - t[1].x) +
                (t[2].y - t[1].y) * (t[2].y - t[1].y)).toDouble()).toFloat()
            val newCx = (t.sumOf { it.x.toDouble() } / 4f).toFloat()
            val newCy = (t.sumOf { it.y.toDouble() } / 4f).toFloat()
            return copy(
                corners = listOf(
                    Point2D(newCx - w / 2f, newCy - h / 2f),
                    Point2D(newCx + w / 2f, newCy - h / 2f),
                    Point2D(newCx + w / 2f, newCy + h / 2f),
                    Point2D(newCx - w / 2f, newCy + h / 2f),
                ),
                rotation = newRotation
            )
        }

        override fun withColor(color: Color): DrawingPrimitive = copy(color = color)
        override fun withLayerId(id: Int): DrawingPrimitive = copy(layerId = id)
        override fun withLineScaleFactor(factor: Float): DrawingPrimitive = copy(lineScaleFactor = factor)

        /** 中间手柄拖拽：沿边的法向移动该边，对边固定不动。
         *  [amount] 为世界坐标位移（沿旋转后的边法向，正=向外）。
         *  [frameRotation] 为矩形自身 rotation 渲染时的旋转角（即内部旋转）。
         *  注意：待确认(pending)模式的旋转在 edit.rotation 里，由 worldToScreen 绕 pivot
         *  外部施加在存储角点上，存储层只需局部系移动，调用方应传默认的 rotation（通常 0）。
         *  已旋转矩形的存储角点必须保持轴对齐（旋转由 rotation 字段表达），
         *  因此已旋转时改为"半尺寸+中心平移"：边移动 amount ⇒ 该轴向半尺寸 ±amount/2，
         *  中心沿旋转后法向移 amount/2，保证对边在屏幕上固定不动。 */
        fun dragEdge(edgeIdx: Int, amount: Float, frameRotation: Float = rotation): RectanglePrimitive {
            // 角点顺序取决于当初绘制方向（可能不是 TL,TR,BR,BL），先按 AABB 规范化，
            // 保证 edgeIdx 语义固定（0=top,1=right,2=bottom,3=left）且外法向恒朝外
            val xs0 = corners.map { it.x }; val ys0 = corners.map { it.y }
            val minX0 = xs0.min(); val maxX0 = xs0.max(); val minY0 = ys0.min(); val maxY0 = ys0.max()
            val norm = listOf(
                Point2D(minX0, minY0), Point2D(maxX0, minY0),
                Point2D(maxX0, maxY0), Point2D(minX0, maxY0)
            )
            val a = norm[edgeIdx]; val b = norm[(edgeIdx + 1) % 4]
            val dx = b.x - a.x; val dy = b.y - a.y
            val len = sqrt(dx * dx + dy * dy)
            if (len < 0.001f) return this
            val lnx = dy / len; val lny = -dx / len  // 局部外法向
            if (abs(frameRotation) < 0.001f) {
                // 未旋转：直接沿法向移动该边两个角点
                val nc = norm.toMutableList().also {
                    it[edgeIdx] = Point2D(a.x + lnx * amount, a.y + lny * amount)
                    it[(edgeIdx + 1) % 4] = Point2D(b.x + lnx * amount, b.y + lny * amount)
                }
                return copy(corners = nc)
            }
            val cosP = cos(frameRotation); val sinP = sin(frameRotation)
            // 世界外法向 = R(frameRotation) · 局部法向
            val wnx = lnx * cosP - lny * sinP
            val wny = lnx * sinP + lny * cosP
            val cx = (minX0 + maxX0) / 2f; val cy = (minY0 + maxY0) / 2f
            val hw = (maxX0 - minX0) / 2f; val hh = (maxY0 - minY0) / 2f
            val half = amount / 2f
            val isHorizEdge = abs(lny) > abs(lnx)  // 顶/底边：法向沿局部 y，改 hh
            // 不钳制半尺寸：允许拖过对向边（负半尺寸=沿该轴翻转），
            // 与未旋转时直接移角点的"直接越过"行为一致
            val newHw = if (isHorizEdge) hw else hw + half
            val newHh = if (isHorizEdge) hh + half else hh
            val newCx = cx + wnx * half
            val newCy = cy + wny * half
            return copy(corners = listOf(
                Point2D(newCx - newHw, newCy - newHh),
                Point2D(newCx + newHw, newCy - newHh),
                Point2D(newCx + newHw, newCy + newHh),
                Point2D(newCx - newHw, newCy + newHh)
            ))
        }
        override val typeName: String get() = "矩形"
        override fun shiftPrimitive(dx: Float, dy: Float): DrawingPrimitive = copy(corners = corners.map { Point2D(it.x + dx, it.y + dy) })
        override fun deepCopy(): DrawingPrimitive = copy()
        // 镜像 = 镜面反射：位置翻转的同时角度取反（θ→-θ），否则已旋转矩形只移位置、朝向不变
        override fun mirrorPrimitive(cx: Float, cy: Float, flipY: Boolean): DrawingPrimitive =
            copy(
                corners = corners.map { if (flipY) Point2D(it.x, cy * 2 - it.y) else Point2D(cx * 2 - it.x, it.y) },
                rotation = -rotation
            )
        override fun dxfEntityCount(): Int = 4
        override fun dxfHandleCount(): Int = 4
    }

    /** 圆形（两点直径模式，支持椭圆） */
    data class CirclePrimitive(
        val centerX: Float, val centerY: Float,
        /** 直径终点X（两点画圆的第二个点） */
        val endX: Float, val endY: Float,
        val rotation: Float = 0f,
        /** 独立半径字段（避免旋转后从 endX/endY 推算的半径出错） */
        val rx: Float = 0f,
        val ry: Float = 0f,
        override val color: Color,
        override val strokeWidth: Float,
        override val layerId: Int = 1,
        override val lineStyle: LineStyle = LineStyle(),
        override val lineScaleFactor: Float = 1f
    ) : DrawingPrimitive(color, strokeWidth, layerId, lineStyle, lineScaleFactor) {

        /** 横轴半径 — 优先用 rx，兼容旧文件回退到 endX/endY（取绝对值：dragEdge 允许拉过对侧变负） */
        val radiusX: Float get() = if (rx != 0f) abs(rx) else abs(endX - centerX)
        /** 纵轴半径 — 优先用 ry，兼容旧文件回退到 endX/endY（取绝对值，同上） */
        val radiusY: Float get() = if (ry != 0f) abs(ry) else abs(endY - centerY)

        /** 中间手柄拖拽：沿手柄法向拉伸，对向顶点固定不动。
         *  [amount] 世界坐标位移（沿旋转后法向，正=向外）。index: PFO [top,bottom,left,right]。
         *  [frameRotation] 自身 rotation（pending 模式的外部旋转 edit.rotation 勿传入，
         *  它与矩形同理：外部旋转由渲染管线施加，存储层只需局部系移动）。
         *  半径允许变负（椭圆关于中心对称，负半径=同一椭圆，等价于拉过对侧）。 */
        fun dragEdge(index: Int, amount: Float, frameRotation: Float = rotation): CirclePrimitive {
            val i = index.coerceIn(0, 3)
            // 局部外法向
            val lnx = when (i) { 2 -> -1f; 3 -> 1f; else -> 0f }
            val lny = when (i) { 0 -> -1f; 1 -> 1f; else -> 0f }
            // 边外移 amount ⇒ 半径 +amount/2，中心沿法向移 amount/2（对向顶点固定）
            val half = amount / 2f
            val cosP = cos(frameRotation); val sinP = sin(frameRotation)
            val wnx = lnx * cosP - lny * sinP
            val wny = lnx * sinP + lny * cosP
            val newCx = centerX + wnx * half
            val newCy = centerY + wny * half
            val newRx = if (i >= 2) radiusX + half else radiusX
            val newRy = if (i <= 1) radiusY + half else radiusY
            // endX/endY 兼容字段与 applyTransform 同约定
            val cosR = cos(rotation); val sinR = sin(rotation)
            return copy(
                centerX = newCx, centerY = newCy, rx = newRx, ry = newRy,
                endX = newCx + newRx * cosR - newRy * sinR,
                endY = newCy + newRx * sinR + newRy * cosR
            )
        }

        override val intrinsicRotation: Float get() = rotation
        override fun computeBounds(measurePaint: Paint?): FloatArray? {
            val rxR = radiusX; val ryR = radiusY
            val cosR = cos(rotation); val sinR = sin(rotation)
            val hw = sqrt(rxR * rxR * cosR * cosR + ryR * ryR * sinR * sinR).toFloat()
            val hh = sqrt(rxR * rxR * sinR * sinR + ryR * ryR * cosR * cosR).toFloat()
            return floatArrayOf(centerX - hw, centerY - hh, centerX + hw, centerY + hh)
        }

        override fun fenceHitsGeometry(fence: Bounds): Boolean {
            fun segmentHitsRect(ax: Float, ay: Float, bx: Float, by: Float): Boolean {
                if (ax >= fence.minX && ax <= fence.maxX && ay >= fence.minY && ay <= fence.maxY) return true
                if (bx >= fence.minX && bx <= fence.maxX && by >= fence.minY && by <= fence.maxY) return true
                val minX = minOf(ax, bx); val maxX = maxOf(ax, bx)
                val minY = minOf(ay, by); val maxY = maxOf(ay, by)
                if (maxX < fence.minX || minX > fence.maxX || maxY < fence.minY || minY > fence.maxY) return false
                fun ccw(px: Float, py: Float, qx: Float, qy: Float, rx: Float, ry: Float): Int {
                    val cross = (qx - px) * (ry - py) - (qy - py) * (rx - px)
                    return if (cross > 0) 1 else if (cross < 0) -1 else 0
                }
                fun segSeg(p1x: Float, p1y: Float, p2x: Float, p2y: Float,
                           q1x: Float, q1y: Float, q2x: Float, q2y: Float): Boolean {
                    val o1 = ccw(p1x, p1y, p2x, p2y, q1x, q1y)
                    val o2 = ccw(p1x, p1y, p2x, p2y, q2x, q2y)
                    val o3 = ccw(q1x, q1y, q2x, q2y, p1x, p1y)
                    val o4 = ccw(q1x, q1y, q2x, q2y, p2x, p2y)
                    return o1 != o2 && o3 != o4
                }
                val l = fence.minX; val r = fence.maxX; val t = fence.minY; val b = fence.maxY
                return segSeg(ax, ay, bx, by, l, t, l, b) ||
                       segSeg(ax, ay, bx, by, r, t, r, b) ||
                       segSeg(ax, ay, bx, by, l, t, r, t) ||
                       segSeg(ax, ay, bx, by, l, b, r, b)
            }
            val rx = radiusX; val ry = radiusY
            val cosR = cos(rotation); val sinR = sin(rotation)
            val segs = 16
            var prevX = centerX + rx * cosR
            var prevY = centerY + rx * sinR
            for (i in 1..segs) {
                val a = (i.toFloat() / segs) * 2f * kotlin.math.PI.toFloat()
                val lx = rx * cos(a.toDouble()).toFloat()
                val ly = ry * sin(a.toDouble()).toFloat()
                val cx = centerX + lx * cosR - ly * sinR
                val cy = centerY + lx * sinR + ly * cosR
                if (segmentHitsRect(prevX, prevY, cx, cy)) return true
                prevX = cx; prevY = cy
            }
            return false
        }

        override fun distanceTo(point: Point2D): Float {
            val rx = radiusX; val ry = radiusY
            if (abs(rotation) < 0.001f && abs(rx - ry) < 0.001f) {
                val dc = sqrt(Point2D.distSquared(point, Point2D(centerX, centerY)))
                return abs(dc - rx)
            } else {
                val cosR = cos(-rotation); val sinR = sin(-rotation)
                val dx = point.x - centerX; val dy = point.y - centerY
                val lx = dx * cosR - dy * sinR; val ly = dx * sinR + dy * cosR
                val angle = atan2(ly * rx, lx * ry)
                val ex = rx * cos(angle); val ey = ry * sin(angle)
                return sqrt(Point2D.distSquared(Point2D(lx, ly), Point2D(ex, ey)))
            }
        }

        override fun applyTransform(pe: PendingEdit): DrawingPrimitive {
            val dx = pe.offsetX; val dy = pe.offsetY
            val sx = pe.scaleX; val sy = pe.scaleY
            val cosR = cos(pe.rotation.toDouble()).toFloat(); val sinR = sin(pe.rotation.toDouble()).toFloat()
            val bounds = pe.bounds
            val cx0 = bounds?.let { (it.minX + it.maxX) / 2f } ?: 0f
            val cy0 = bounds?.let { (it.minY + it.maxY) / 2f } ?: 0f
            fun transform(wx: Float, wy: Float): Point2D {
                var x = (wx - cx0) * sx; var y = (wy - cy0) * sy
                val rx = x * cosR - y * sinR + cx0 + dx; val ry = x * sinR + y * cosR + cy0 + dy
                return Point2D(rx, ry)
            }
            if (dx == 0f && dy == 0f && pe.rotation == 0f && sx == 1f && sy == 1f) return this
            // Rotate the center around the bounds center (position change for multi-select)
            val c = transform(centerX, centerY)
            // Preserve the radii independently of endX/endY — rotation is stored in `rotation`.
            val oldRx = if (rx != 0f) rx else abs(endX - centerX)
            val oldRy = if (ry != 0f) ry else abs(endY - centerY)
            val newRx = oldRx * sx
            val newRy = oldRy * sy
            val newRotation = (rotation + pe.rotation) % (2f * kotlin.math.PI.toFloat())
            // Recompute endX/endY from center + radii at new rotation angle (for file compat)
            val cosNR = cos(newRotation.toDouble()).toFloat(); val sinNR = sin(newRotation.toDouble()).toFloat()
            return copy(
                centerX = c.x, centerY = c.y,
                rx = newRx, ry = newRy,
                endX = c.x + newRx * cosNR - newRy * sinNR,
                endY = c.y + newRx * sinNR + newRy * cosNR,
                rotation = newRotation
            )
        }
        override fun withColor(color: Color): DrawingPrimitive = copy(color = color)
        override fun withLayerId(id: Int): DrawingPrimitive = copy(layerId = id)
        override fun withLineScaleFactor(factor: Float): DrawingPrimitive = copy(lineScaleFactor = factor)



        override val typeName: String get() = "圆形"
        override fun shiftPrimitive(dx: Float, dy: Float): DrawingPrimitive = copy(centerX = centerX + dx, centerY = centerY + dy, endX = endX + dx, endY = endY + dy)
        override fun deepCopy(): DrawingPrimitive = copy()
        // 与矩形同理：椭圆镜像时角度取反（正圆无影响）
        override fun mirrorPrimitive(cx: Float, cy: Float, flipY: Boolean): DrawingPrimitive =
            if (flipY) copy(centerY = cy * 2 - centerY, endY = cy * 2 - endY, rotation = -rotation)
            else copy(centerX = cx * 2 - centerX, centerY = centerY, endX = cx * 2 - endX, endY = endY, rotation = -rotation)
        override fun dxfEntityCount(): Int = if (lineStyle.type == LineType.LIGHTNING) {
            val r = maxOf(radiusX, radiusY).toDouble()
            val n = maxOf(4, ((2.0 * kotlin.math.PI * r) / 120.0).toInt())
            1 + 2 * n
        } else 1
        override fun dxfHandleCount(): Int = dxfEntityCount()
    }

    /** 直线 */
    data class LinePrimitive(
        val startX: Float, val startY: Float,
        val endX: Float, val endY: Float,
        override val color: Color,
        override val strokeWidth: Float,
        override val layerId: Int = 1,
        override val lineStyle: LineStyle = LineStyle(),
        override val lineScaleFactor: Float = 1f
    ) : DrawingPrimitive(color, strokeWidth, layerId, lineStyle, lineScaleFactor) {

        override val intrinsicRotation: Float get() = atan2(endY - startY, endX - startX)

        override fun computeBounds(measurePaint: Paint?): FloatArray? {
            val xs = listOf(startX, endX); val ys = listOf(startY, endY)
            val pad = strokeWidth / 2f + 10f
            return floatArrayOf(xs.min() - pad, ys.min() - pad, xs.max() + pad, ys.max() + pad)
        }

        override fun fenceHitsGeometry(fence: Bounds): Boolean {
            fun segmentHitsRect(ax: Float, ay: Float, bx: Float, by: Float): Boolean {
                if (ax >= fence.minX && ax <= fence.maxX && ay >= fence.minY && ay <= fence.maxY) return true
                if (bx >= fence.minX && bx <= fence.maxX && by >= fence.minY && by <= fence.maxY) return true
                val minX = minOf(ax, bx); val maxX = maxOf(ax, bx)
                val minY = minOf(ay, by); val maxY = maxOf(ay, by)
                if (maxX < fence.minX || minX > fence.maxX || maxY < fence.minY || minY > fence.maxY) return false
                fun ccw(px: Float, py: Float, qx: Float, qy: Float, rx: Float, ry: Float): Int {
                    val cross = (qx - px) * (ry - py) - (qy - py) * (rx - px)
                    return if (cross > 0) 1 else if (cross < 0) -1 else 0
                }
                fun segSeg(p1x: Float, p1y: Float, p2x: Float, p2y: Float,
                           q1x: Float, q1y: Float, q2x: Float, q2y: Float): Boolean {
                    val o1 = ccw(p1x, p1y, p2x, p2y, q1x, q1y)
                    val o2 = ccw(p1x, p1y, p2x, p2y, q2x, q2y)
                    val o3 = ccw(q1x, q1y, q2x, q2y, p1x, p1y)
                    val o4 = ccw(q1x, q1y, q2x, q2y, p2x, p2y)
                    return o1 != o2 && o3 != o4
                }
                val l = fence.minX; val r = fence.maxX; val t = fence.minY; val b = fence.maxY
                return segSeg(ax, ay, bx, by, l, t, l, b) ||
                       segSeg(ax, ay, bx, by, r, t, r, b) ||
                       segSeg(ax, ay, bx, by, l, t, r, t) ||
                       segSeg(ax, ay, bx, by, l, b, r, b)
            }
            return segmentHitsRect(startX, startY, endX, endY)
        }

        override fun distanceTo(point: Point2D): Float {
            return distToSegment(point, Point2D(startX, startY), Point2D(endX, endY))
        }

        override fun applyTransform(pe: PendingEdit): DrawingPrimitive {
            val dx = pe.offsetX; val dy = pe.offsetY
            val sx = pe.scaleX; val sy = pe.scaleY
            val cosR = cos(pe.rotation.toDouble()).toFloat(); val sinR = sin(pe.rotation.toDouble()).toFloat()
            val bounds = pe.bounds
            val cx0 = bounds?.let { (it.minX + it.maxX) / 2f } ?: 0f
            val cy0 = bounds?.let { (it.minY + it.maxY) / 2f } ?: 0f
            fun transform(wx: Float, wy: Float): Point2D {
                val x = (wx - cx0) * sx; val y = (wy - cy0) * sy
                val rx = x * cosR - y * sinR + cx0 + dx; val ry = x * sinR + y * cosR + cy0 + dy
                return Point2D(rx, ry)
            }
            if (dx == 0f && dy == 0f && pe.rotation == 0f && sx == 1f && sy == 1f) return this
            val s = transform(startX, startY); val e = transform(endX, endY)
            val avgScale = sqrt(abs(sx * sy))
            return copy(startX = s.x, startY = s.y, endX = e.x, endY = e.y,
                strokeWidth = strokeWidth * avgScale)
        }

        override val typeName: String get() = "直线"
        override fun shiftPrimitive(dx: Float, dy: Float): DrawingPrimitive = copy(startX = startX + dx, startY = startY + dy, endX = endX + dx, endY = endY + dy)
        override fun deepCopy(): DrawingPrimitive = copy()
        override fun mirrorPrimitive(cx: Float, cy: Float, flipY: Boolean): DrawingPrimitive =
            if (flipY) copy(startY = cy * 2 - startY, endY = cy * 2 - endY)
            else copy(startX = cx * 2 - startX, endX = cx * 2 - endX, startY = startY, endY = endY)
        override fun dxfEntityCount(): Int { if (lineStyle.type == LineType.LIGHTNING) { val ddx = (endX - startX).toDouble(); val ddy = (endY - startY).toDouble(); val len = sqrt(ddx * ddx + ddy * ddy); val nn = maxOf(2, (len / 120.0).toInt()); return 1 + 2 * nn }; return 1 }
        override fun withColor(color: Color): DrawingPrimitive = copy(color = color)
        override fun withLayerId(id: Int): DrawingPrimitive = copy(layerId = id)
        override fun withLineScaleFactor(factor: Float): DrawingPrimitive = copy(lineScaleFactor = factor)


        override fun dxfHandleCount(): Int = dxfEntityCount()
    }

    /** 标注数字 */
    data class NumberLabelPrimitive(
        val value: Int,
        val x: Float, val y: Float,
        val rotation: Float = 0f,
        val fontSize: Float = 30f,
        override val color: Color,
        override val strokeWidth: Float,
        override val layerId: Int = 1,
        val horizontalOnly: Boolean = false,
        val circled: Boolean = false,
        override val lineScaleFactor: Float = 1f
    ) : DrawingPrimitive(color, strokeWidth, layerId, lineScaleFactor = lineScaleFactor) {

        override val intrinsicRotation: Float get() = rotation

        /** 外圈半径（与渲染一致）：包住文本再留约 15% 余量 */
        fun circleRadius(measurePaint: Paint): Float {
            measurePaint.textSize = fontSize * 1.3f
            val tw = measurePaint.measureText(value.toString())
            return maxOf(tw / 2f, fontSize * 0.65f) * 1.15f
        }

        override fun computeBounds(measurePaint: Paint?): FloatArray? {
            val mp = measurePaint ?: return null
            mp.textSize = fontSize * 1.3f
            val textWidth = mp.measureText(value.toString())
            val fm = mp.fontMetrics
            var hw = textWidth / 2f
            var hh = (fm.descent - fm.ascent) / 2f
            if (circled) {
                val r = circleRadius(mp)
                hw = maxOf(hw, r); hh = maxOf(hh, r)
            }
            if (abs(rotation) < 0.01f) {
                return floatArrayOf(x - hw, y - hh, x + hw, y + hh)
            } else {
                return rotAABB(x, y, hw, hh, rotation)
            }
        }

        override fun fenceHitsGeometry(fence: Bounds): Boolean {
            if (circled) {
                // 包围盒到外圈圆心的距离不超过半径即命中
                val numChars0 = value.toString().length.coerceAtLeast(1)
                val r = maxOf(fontSize * 0.3f * numChars0, fontSize * 0.65f) * 1.15f
                val nx = x.coerceIn(fence.minX, fence.maxX)
                val ny = y.coerceIn(fence.minY, fence.maxY)
                if ((nx - x) * (nx - x) + (ny - y) * (ny - y) <= r * r) return true
            }
            if (x >= fence.minX && x <= fence.maxX && y >= fence.minY && y <= fence.maxY) return true
            if (abs(rotation) < 0.001f) return false
            val numChars = value.toString().length.coerceAtLeast(1)
            val hw = fontSize * 0.3f * numChars; val hh = fontSize * 0.4f
            val cosR = cos(rotation); val sinR = sin(rotation)
            val corners = listOf(
                x + (-hw) * cosR - (-hh) * sinR to y + (-hw) * sinR + (-hh) * cosR,
                x + (+hw) * cosR - (-hh) * sinR to y + (+hw) * sinR + (-hh) * cosR,
                x + (+hw) * cosR - (+hh) * sinR to y + (+hw) * sinR + (+hh) * cosR,
                x + (-hw) * cosR - (+hh) * sinR to y + (-hw) * sinR + (+hh) * cosR,
            )
            return corners.any { (cx, cy) ->
                cx >= fence.minX && cx <= fence.maxX && cy >= fence.minY && cy <= fence.maxY
            }
        }

        override fun distanceTo(point: Point2D): Float {
            val mp = Paint().apply { typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true; textAlign = Paint.Align.CENTER }
            mp.textSize = fontSize * 1.3f
            val hw = mp.measureText(value.toString()) / 2f
            val cosR = cos(rotation); val sinR = sin(rotation)
            val dText = distToSegment(point,
                Point2D(x - hw * cosR, y - hw * sinR),
                Point2D(x + hw * cosR, y + hw * sinR))
            if (!circled) return dText
            val dx = point.x - x; val dy = point.y - y
            val dCircle = abs(sqrt(dx * dx + dy * dy) - circleRadius(mp))
            return minOf(dText, dCircle)
        }

        override fun applyTransform(pe: PendingEdit): DrawingPrimitive {
            val dx = pe.offsetX; val dy = pe.offsetY
            val sx = pe.scaleX; val sy = pe.scaleY
            val cosR = cos(pe.rotation.toDouble()).toFloat(); val sinR = sin(pe.rotation.toDouble()).toFloat()
            val bounds = pe.bounds
            val cx0 = bounds?.let { (it.minX + it.maxX) / 2f } ?: 0f
            val cy0 = bounds?.let { (it.minY + it.maxY) / 2f } ?: 0f
            fun transform(wx: Float, wy: Float): Point2D {
                var x = (wx - cx0) * sx; var y = (wy - cy0) * sy
                val rx = x * cosR - y * sinR + cx0 + dx; val ry = x * sinR + y * cosR + cy0 + dy
                return Point2D(rx, ry)
            }
            val t = transform(x, y)
            val newRotation = (rotation + pe.rotation) % (2f * kotlin.math.PI.toFloat())
            val avgScale = sqrt(abs(sx * sy))
            val newFontSize = (fontSize * avgScale).coerceIn(30f, 600f)
            return copy(x = t.x, y = t.y, rotation = newRotation, fontSize = newFontSize)
        }
        override fun withColor(color: Color): DrawingPrimitive = copy(color = color)
        override fun withLayerId(id: Int): DrawingPrimitive = copy(layerId = id)
        override fun withLineScaleFactor(factor: Float): DrawingPrimitive = copy(lineScaleFactor = factor)



        override val typeName: String get() = "数字"
        override fun shiftPrimitive(dx: Float, dy: Float): DrawingPrimitive = copy(x = x + dx, y = y + dy)
        override fun deepCopy(): DrawingPrimitive = copy()
        override fun mirrorPrimitive(cx: Float, cy: Float, flipY: Boolean): DrawingPrimitive =
            if (flipY) copy(y = cy * 2 - y) else copy(x = cx * 2 - x, y = y)
        override fun dxfEntityCount(): Int = 1
        override fun dxfHandleCount(): Int = 1
    }

    /** 文本标注 */
    data class TextPrimitive(
        val text: String,
        val x: Float, val y: Float,
        val rotation: Float = 0f,
        val fontSize: Float = 40f,
        override val color: Color,
        override val strokeWidth: Float = 2f,
        override val layerId: Int = 1,
        val horizontalOnly: Boolean = false,
        override val lineScaleFactor: Float = 1f
    ) : DrawingPrimitive(color, strokeWidth, layerId, lineScaleFactor = lineScaleFactor) {

        override val intrinsicRotation: Float get() = rotation
        override fun computeBounds(measurePaint: Paint?): FloatArray? {
            val mp = measurePaint ?: return null
            mp.textSize = fontSize * 1.3f
            val textWidth = mp.measureText(text)
            val fm = mp.fontMetrics
            val hw = textWidth / 2f
            val hh = (fm.descent - fm.ascent) / 2f
            if (abs(rotation) < 0.01f) {
                return floatArrayOf(x - hw, y - hh, x + hw, y + hh)
            } else {
                return rotAABB(x, y, hw, hh, rotation)
            }
        }

        override fun fenceHitsGeometry(fence: Bounds): Boolean {
            if (x >= fence.minX && x <= fence.maxX && y >= fence.minY && y <= fence.maxY) return true
            // 未旋转：文字的 computeBounds 即紧贴文字的矩形，调用方（交叉模式）已判定
            // AABB 相交 —— 沾到文字即选中；不能只看锚点，否则擦着文字却选不中
            if (abs(rotation) < 0.001f) return true
            val numChars = text.length.coerceAtLeast(1)
            val hw = fontSize * 0.35f * numChars; val hh = fontSize * 0.5f
            val cosR = cos(rotation); val sinR = sin(rotation)
            val corners = listOf(
                x + (-hw) * cosR - (-hh) * sinR to y + (-hw) * sinR + (-hh) * cosR,
                x + (+hw) * cosR - (-hh) * sinR to y + (+hw) * sinR + (-hh) * cosR,
                x + (+hw) * cosR - (+hh) * sinR to y + (+hw) * sinR + (+hh) * cosR,
                x + (-hw) * cosR - (+hh) * sinR to y + (-hw) * sinR + (+hh) * cosR,
            )
            return corners.any { (cx, cy) ->
                cx >= fence.minX && cx <= fence.maxX && cy >= fence.minY && cy <= fence.maxY
            }
        }

        override fun distanceTo(point: Point2D): Float {
            val mp = Paint().apply { typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true; textAlign = Paint.Align.CENTER }
            mp.textSize = fontSize * 1.3f
            val hw = mp.measureText(text) / 2f
            val cosR = cos(rotation); val sinR = sin(rotation)
            return distToSegment(point,
                Point2D(x - hw * cosR, y - hw * sinR),
                Point2D(x + hw * cosR, y + hw * sinR))
        }

        override fun applyTransform(pe: PendingEdit): DrawingPrimitive {
            val dx = pe.offsetX; val dy = pe.offsetY
            val sx = pe.scaleX; val sy = pe.scaleY
            val cosR = cos(pe.rotation.toDouble()).toFloat(); val sinR = sin(pe.rotation.toDouble()).toFloat()
            val bounds = pe.bounds
            val cx0 = bounds?.let { (it.minX + it.maxX) / 2f } ?: 0f
            val cy0 = bounds?.let { (it.minY + it.maxY) / 2f } ?: 0f
            fun transform(wx: Float, wy: Float): Point2D {
                var x = (wx - cx0) * sx; var y = (wy - cy0) * sy
                val rx = x * cosR - y * sinR + cx0 + dx; val ry = x * sinR + y * cosR + cy0 + dy
                return Point2D(rx, ry)
            }
            val t = transform(x, y)
            val newRotation = (rotation + pe.rotation) % (2f * kotlin.math.PI.toFloat())
            val avgScale = sqrt(abs(sx * sy))
            val newFontSize = (fontSize * avgScale).coerceIn(30f, 600f)
            return copy(x = t.x, y = t.y, rotation = newRotation, fontSize = newFontSize)
        }
        override fun withColor(color: Color): DrawingPrimitive = copy(color = color)
        override fun withLayerId(id: Int): DrawingPrimitive = copy(layerId = id)
        override fun withLineScaleFactor(factor: Float): DrawingPrimitive = copy(lineScaleFactor = factor)



        override val typeName: String get() = "文字"
        override fun shiftPrimitive(dx: Float, dy: Float): DrawingPrimitive = copy(x = x + dx, y = y + dy)
        override fun deepCopy(): DrawingPrimitive = copy()
        override fun mirrorPrimitive(cx: Float, cy: Float, flipY: Boolean): DrawingPrimitive =
            if (flipY) copy(y = cy * 2 - y) else copy(x = cx * 2 - x, y = y)
        override fun dxfEntityCount(): Int = 1
        override fun dxfHandleCount(): Int = 1
    }

    /** 区间数字标注 首→尾 */
    data class RangeLabelPrimitive(
        val startValue: Int,
        val endValue: Int,
        val x: Float, val y: Float,
        val rotation: Float = 0f,
        val fontSize: Float = 30f,
        val arrowSpan: Float = 1f,
        val reversed: Boolean = false,
        /** 两端数字朝向：false = 朝下（正向），true = 朝左（数字下方朝屏幕左边，绘制角 +90°） */
        val numbersFaceLeft: Boolean = false,
        override val color: Color,
        override val strokeWidth: Float = 2f,
        override val layerId: Int = 1,
        val horizontalOnly: Boolean = true,
        override val lineScaleFactor: Float = 1f
    ) : DrawingPrimitive(color, strokeWidth, layerId, lineScaleFactor = lineScaleFactor) {

        override val intrinsicRotation: Float get() = rotation
        override fun computeBounds(measurePaint: Paint?): FloatArray? {
            val mp = measurePaint ?: return null
            val arrowLen = maxOf(80f * arrowSpan, 20f)
            mp.textSize = fontSize
            val w1 = mp.measureText(startValue.toString())
            val w2 = mp.measureText(endValue.toString())
            val fm = mp.fontMetrics
            val textHh = (fm.descent - fm.ascent) / 2f
            // 两端数字锚点距中心 d = 箭头半长 + 字号；朝左（数字转 90°）时沿轴/垂直方向占用互换
            val d = arrowLen / 2f + fontSize * 1.0f
            val halfSpan: Float
            val halfH: Float
            if (numbersFaceLeft) {
                halfSpan = d + textHh
                halfH = maxOf(w1, w2) / 2f
            } else {
                halfSpan = d + maxOf(w1, w2) / 2f
                halfH = textHh
            }
            if (abs(rotation) < 0.01f) {
                return floatArrayOf(x - halfSpan, y - halfH, x + halfSpan, y + halfH)
            } else {
                return rotAABB(x, y, halfSpan, halfH, rotation)
            }
        }

        override fun fenceHitsGeometry(fence: Bounds): Boolean {
            return x >= fence.minX && x <= fence.maxX && y >= fence.minY && y <= fence.maxY
        }

        override fun distanceTo(point: Point2D): Float {
            val arrowLen = maxOf(80f * arrowSpan, 20f)
            val reach = arrowLen / 2f + fontSize
            val cosR = cos(rotation); val sinR = sin(rotation)
            val hx = reach * cosR; val hy = reach * sinR
            return distToSegment(point, Point2D(x - hx, y - hy), Point2D(x + hx, y + hy))
        }

        override fun applyTransform(pe: PendingEdit): DrawingPrimitive {
            val dx = pe.offsetX; val dy = pe.offsetY
            val sx = pe.scaleX; val sy = pe.scaleY
            val cosR = cos(pe.rotation.toDouble()).toFloat(); val sinR = sin(pe.rotation.toDouble()).toFloat()
            val bounds = pe.bounds
            val cx0 = bounds?.let { (it.minX + it.maxX) / 2f } ?: 0f
            val cy0 = bounds?.let { (it.minY + it.maxY) / 2f } ?: 0f
            fun transform(wx: Float, wy: Float): Point2D {
                var x = (wx - cx0) * sx; var y = (wy - cy0) * sy
                val rx = x * cosR - y * sinR + cx0 + dx; val ry = x * sinR + y * cosR + cy0 + dy
                return Point2D(rx, ry)
            }
            val t = transform(x, y)
            val newRotation = (rotation + pe.rotation) % (2f * kotlin.math.PI.toFloat())
            val avgScale = sqrt(abs(sx * sy))
            val isUniform = abs(sx - sy) < 0.01f
            val newFontSize = if (isUniform) (fontSize * avgScale).coerceIn(20f, 600f) else fontSize
            val newArrowSpan = ((arrowSpan / fontSize) * newFontSize).coerceAtLeast(0.2f)
            return copy(x = t.x, y = t.y, rotation = newRotation, fontSize = newFontSize, arrowSpan = newArrowSpan)
        }
        override fun withColor(color: Color): DrawingPrimitive = copy(color = color)
        override fun withLayerId(id: Int): DrawingPrimitive = copy(layerId = id)
        override fun withLineScaleFactor(factor: Float): DrawingPrimitive = copy(lineScaleFactor = factor)



        override val typeName: String get() = "区间"
        override fun shiftPrimitive(dx: Float, dy: Float): DrawingPrimitive = copy(x = x + dx, y = y + dy)
        override fun deepCopy(): DrawingPrimitive = copy()
        // 区间数字镜像：位置翻转 + 箭头朝向翻转（reversed 取反）；文字/数字只镜像位置
        // 横屏模式（绕水平轴翻转 y）不改变左右朝向，reversed 保持不变
        override fun mirrorPrimitive(cx: Float, cy: Float, flipY: Boolean): DrawingPrimitive =
            if (flipY) copy(y = cy * 2 - y) else copy(x = cx * 2 - x, y = y, reversed = !reversed)
        override fun dxfEntityCount(): Int = 5
        override fun dxfHandleCount(): Int = 5
    }

    /** 图块引用 */
    data class BlockRefPrimitive(
        val blockDefId: String,
        val x: Float, val y: Float,
        val scale: Float = 1f,
        val rotation: Float = 0f,
        override val color: Color,
        override val strokeWidth: Float,
        override val layerId: Int = 1,
        override val lineStyle: LineStyle = LineStyle(),
        val snapPointIndex: Int = -1,
        override val lineScaleFactor: Float = 1f
    ) : DrawingPrimitive(color, strokeWidth, layerId, lineStyle, lineScaleFactor) {

        override val intrinsicRotation: Float get() = rotation
        override fun computeBounds(measurePaint: Paint?): FloatArray? {
            val h = 50f * scale
            return floatArrayOf(x - h, y - h, x + h, y + h)
        }

        override fun fenceHitsGeometry(fence: Bounds): Boolean {
            return x >= fence.minX && x <= fence.maxX && y >= fence.minY && y <= fence.maxY
        }

        override fun distanceTo(point: Point2D): Float {
            return sqrt(Point2D.distSquared(point, Point2D(x, y)))
        }

        override fun applyTransform(pe: PendingEdit): DrawingPrimitive {
            val dx = pe.offsetX; val dy = pe.offsetY
            val sx = pe.scaleX; val sy = pe.scaleY
            val cosR = cos(pe.rotation.toDouble()).toFloat(); val sinR = sin(pe.rotation.toDouble()).toFloat()
            val bounds = pe.bounds
            val cx0 = bounds?.let { (it.minX + it.maxX) / 2f } ?: 0f
            val cy0 = bounds?.let { (it.minY + it.maxY) / 2f } ?: 0f
            fun transform(wx: Float, wy: Float): Point2D {
                var x = (wx - cx0) * sx; var y = (wy - cy0) * sy
                val rx = x * cosR - y * sinR + cx0 + dx; val ry = x * sinR + y * cosR + cy0 + dy
                return Point2D(rx, ry)
            }
            val t = transform(x, y)
            val avgScale = sqrt(abs(sx * sy))
            val newRotation = (rotation + pe.rotation) % (2f * kotlin.math.PI.toFloat())
            return copy(x = t.x, y = t.y, rotation = newRotation, scale = (scale * avgScale).coerceIn(0.1f, 10f))
        }
        override fun withColor(color: Color): DrawingPrimitive = copy(color = color)
        override fun withLayerId(id: Int): DrawingPrimitive = copy(layerId = id)
        override fun withLineScaleFactor(factor: Float): DrawingPrimitive = copy(lineScaleFactor = factor)



        override val typeName: String get() = "图块"
        override fun shiftPrimitive(dx: Float, dy: Float): DrawingPrimitive = copy(x = x + dx, y = y + dy)
        override fun deepCopy(): DrawingPrimitive = copy()
        override fun mirrorPrimitive(cx: Float, cy: Float, flipY: Boolean): DrawingPrimitive =
            if (flipY) copy(y = cy * 2 - y) else copy(x = cx * 2 - x, y = y)
        override fun dxfEntityCount(): Int = 0
        override fun dxfHandleCount(): Int = 1
    }
}
