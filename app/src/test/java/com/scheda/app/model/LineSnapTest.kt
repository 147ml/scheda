package com.scheda.app.model

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 直线端点吸附（LineSnap）的规则单测。
 *
 * 覆盖：边级投影 / 顶点级绝对优先 / 交点 / 共享端点 key 稳定 / 滞回 /
 * 圆（圆心 + 圆周）/ 旋转矩形角点 / 手绘抽稀 / 延长线不吸附。
 */
class LineSnapTest {

    private val vIn = 18f
    private val vOut = 26f
    private val eIn = 14f
    private val eOut = 20f

    private fun seg(key: String, ax: Float, ay: Float, bx: Float, by: Float) =
        SnapSegment(key, ax, ay, bx, by)

    private fun snap(
        px: Float, py: Float,
        segs: List<SnapSegment> = emptyList(),
        circles: List<SnapCircle> = emptyList(),
        prevKey: String? = null
    ) = LineSnap.snap(px, py, segs, circles, vIn, vOut, eIn, eOut, prevKey)

    @Test
    fun edgeSnapProjectsOntoSegment() {
        val segs = listOf(seg("L0", 0f, 0f, 100f, 0f))
        val hit = snap(50f, 10f, segs)
        assertNotNull(hit)
        hit!!
        assertEquals(SnapKind.EDGE, hit.kind)
        assertEquals("L0", hit.key)
        assertEquals(50f, hit.x, 0.001f)
        assertEquals(0f, hit.y, 0.001f)
    }

    @Test
    fun vertexBeatsCloserEdge() {
        // L1 的边在 x=50 上，手指 (50,6) 到它的距离是 0；到交点 (50,0) 是 6。
        // 顶点级必须压过"更近的边"。
        val segs = listOf(
            seg("L0", 0f, 0f, 100f, 0f),
            seg("L1", 50f, -50f, 50f, 50f)
        )
        val hit = snap(50f, 6f, segs)
        assertNotNull(hit)
        hit!!
        assertEquals(SnapKind.VERTEX, hit.kind)
        assertEquals(50f, hit.x, 0.001f)
        assertEquals(0f, hit.y, 0.001f)
    }

    @Test
    fun sharedEndpointHasStableKey() {
        // 两条线共用端点 (100,0)：无论命中哪条线的端点、无论线段顺序，
        // key 必须是同一个（否则沿线段滑动时会被当作新目标、重播吸附动效）
        val a = seg("L0", 0f, 0f, 100f, 0f)
        val b = seg("L1", 100f, 0f, 100f, 100f)
        val hit1 = snap(101f, 1f, listOf(a, b))!!
        val hit2 = snap(101f, 1f, listOf(b, a))!!
        assertEquals(SnapKind.VERTEX, hit1.kind)
        assertEquals(hit1.key, hit2.key)
        assertTrue(hit1.key.startsWith("V"))
    }

    @Test
    fun hysteresisKeepsCurrentTargetButNotNewOne() {
        val segs = listOf(seg("L0", 0f, 0f, 100f, 0f))
        // 距边 17：超过进入阈值 14，但在退出阈值 20 内
        val fresh = snap(50f, 17f, segs, prevKey = null)
        assertNull(fresh)
        val kept = snap(50f, 17f, segs, prevKey = "L0")
        assertNotNull(kept)
        assertEquals("L0", kept!!.key)
    }

    @Test
    fun releaseBeyondExitThreshold() {
        val segs = listOf(seg("L0", 0f, 0f, 100f, 0f))
        // 距边 25：超过退出阈值 20，即使 prevKey 相同也脱离
        assertNull(snap(50f, 25f, segs, prevKey = "L0"))
    }

    @Test
    fun circleCenterAndRim() {
        val pool = LineSnap.buildPool(
            listOf(
                DrawingPrimitive.CirclePrimitive(
                    centerX = 0f, centerY = 0f, endX = 50f, endY = 50f,
                    rotation = 0f, rx = 0f, ry = 0f,
                    color = Color.Black, strokeWidth = 2f
                )
            )
        )
        // 圆心：顶点级
        assertEquals(50f, pool.second.first().rx, 0.001f)
        val center = snap(5f, 3f, pool.first, pool.second)!!
        assertEquals(SnapKind.VERTEX, center.kind)
        assertEquals(0f, center.x, 0.001f)
        assertEquals(0f, center.y, 0.001f)

        // 圆周：边级，落点在 (49.61, 6.20) 附近
        val q = LineSnap.closestOnCircle(48f, 6f, SnapCircle("C0", 0f, 0f, 50f, 50f, 0f))
        assertEquals(49.614f, q.x, 0.01f)
        assertEquals(6.202f, q.y, 0.01f)
        val rim = snap(48f, 6f, pool.first, pool.second)!!
        assertEquals(SnapKind.EDGE, rim.kind)
        assertEquals("C0", rim.key)
        assertEquals(49.614f, rim.x, 0.05f)
        assertEquals(6.202f, rim.y, 0.05f)
    }

    @Test
    fun rotatedRectangleCornersEnterPool() {
        val rect = DrawingPrimitive.RectanglePrimitive(
            corners = listOf(
                Point2D(0f, 0f), Point2D(100f, 0f), Point2D(100f, 50f), Point2D(0f, 50f)
            ),
            rotation = (Math.PI / 2.0).toFloat(),
            color = Color.Black, strokeWidth = 2f
        )
        val pool = LineSnap.buildPool(listOf(rect))
        assertEquals(4, pool.first.size)
        // 绕中心 (50,25) 顺时针 90°：原角 (0,0) → (75,-25)
        val hit = snap(74f, -24f, pool.first, pool.second)
        assertNotNull(hit)
        assertEquals(SnapKind.VERTEX, hit!!.kind)
        assertEquals(75f, hit.x, 0.01f)
        assertEquals(-25f, hit.y, 0.01f)
    }

    @Test
    fun degenerateCircleSnapsAsSegment() {
        val pool = LineSnap.buildPool(
            listOf(
                DrawingPrimitive.CirclePrimitive(
                    centerX = 0f, centerY = 0f, endX = 0f, endY = 50f,
                    rotation = 0f, rx = 0f, ry = 0f,
                    color = Color.Black, strokeWidth = 2f
                )
            )
        )
        // radiusX=0 → 退化成竖直线段 (0,-50)~(0,50)
        assertEquals(1, pool.first.size)
        assertEquals(0, pool.second.size)
        val hit = snap(6f, 20f, pool.first, pool.second)!!
        assertEquals(SnapKind.EDGE, hit.kind)
        assertEquals(0f, hit.x, 0.001f)
        assertEquals(20f, hit.y, 0.001f)
    }

    @Test
    fun freehandIsSimplified() {
        val points = (0..100).map { Point2D(it.toFloat(), 0f) }
        val path = DrawingPrimitive.FreehandPath(
            points = points, isClosed = false,
            color = Color.Black, strokeWidth = 2f
        )
        val pool = LineSnap.buildPool(listOf(path), freehandMinGap = 5f)
        // 100 段被抽稀到 ~20 段
        assertTrue("segments=${pool.first.size}", pool.first.size in 15..25)
    }

    @Test
    fun extensionLineDoesNotSnap() {
        val segs = listOf(seg("L0", 0f, 0f, 100f, 0f))
        // (140,0) 在延长线上，但既不在线段本体、也不在端点阈值内
        assertNull(snap(140f, 0f, segs))
    }
}
