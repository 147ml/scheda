package com.scheda.app.model

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 块引用锚定映射（model.BlockTransform.blockMapPoint）的回归单测。
 *
 * 历史 bug：块渲染把形心平移写在旋转外层（`translate(anchor - 形心*scale) → rotate → scale`），
 * 平移量不参与旋转，块一旋转内容就整体飘离锚点（实测偏移可达数百单位、块飞出屏幕）。
 * 这里把「形心必须落在锚点」锁死，任何一处（渲染/包围盒/DXF）回退都会被这个测试抓到。
 */
class BlockTransformTest {

    private val pi = kotlin.math.PI.toFloat()

    private fun ref(x: Float, y: Float, scale: Float, rotation: Float) =
        DrawingPrimitive.BlockRefPrimitive(
            blockDefId = "b1", x = x, y = y, scale = scale, rotation = rotation,
            color = Color.Black, strokeWidth = 1f
        )

    /** 形心经映射后必须等于锚点，与旋转角/缩放无关 */
    @Test
    fun centroidAlwaysLandsOnAnchor() {
        val centroid = Point2D(500f, 300f)
        for (deg in listOf(0f, 15f, 45f, 90f, 180f, 270f, -30f)) {
            for (scale in listOf(0.5f, 1f, 1.5f, 3f)) {
                val p = ref(120f, -80f, scale, deg * pi / 180f)
                val got = blockMapPoint(centroid.x, centroid.y, p, centroid)
                assertEquals("deg=$deg scale=$scale x", p.x, got.x, 0.01f)
                assertEquals("deg=$deg scale=$scale y", p.y, got.y, 0.01f)
            }
        }
    }

    /** 旋转 90°：内容右方向 (+x) 应转到世界 +y（顺时针为正，与 Canvas.rotate 同向） */
    @Test
    fun rotationFollowsCanvasConvention() {
        val centroid = Point2D(0f, 0f)
        val p = ref(100f, 100f, 1f, pi / 2f)
        val got = blockMapPoint(10f, 0f, p, centroid)   // (10,0) 绕形心转 90°
        assertEquals(100f, got.x, 0.01f)
        assertEquals(110f, got.y, 0.01f)
    }

    /** 缩放以形心为基准：形心两侧对称点放大后仍对称，且形心不动 */
    @Test
    fun scaleIsAboutCentroid() {
        val centroid = Point2D(200f, 200f)
        val p = ref(0f, 0f, 3f, 0f)
        val left = blockMapPoint(180f, 200f, p, centroid)
        val right = blockMapPoint(220f, 200f, p, centroid)
        // 相对形心 ±20 × 3 = ±60
        assertEquals(-60f, left.x, 0.01f)
        assertEquals(60f, right.x, 0.01f)
        assertEquals(0f, left.y, 0.01f)
    }

    /** 防回退：旧的错误公式（平移不参与旋转）在旋转时必须得到不同结果 */
    @Test
    fun legacyFormulaWouldDifferWhenRotated() {
        val centroid = Point2D(500f, 300f)
        val p = ref(0f, 0f, 1.5f, 30f * pi / 180f)
        val good = blockMapPoint(centroid.x, centroid.y, p, centroid)   // 形心 → 锚点

        // 旧公式：v' = R(S·v) + (anchor - centroid*scale)
        val sx = centroid.x * p.scale
        val sy = centroid.y * p.scale
        val c = kotlin.math.cos(p.rotation)
        val s = kotlin.math.sin(p.rotation)
        val legacyX = sx * c - sy * s + (p.x - centroid.x * p.scale)
        val legacyY = sx * s + sy * c + (p.y - centroid.y * p.scale)

        assertEquals(0f, good.x, 0.01f)
        assertEquals(0f, good.y, 0.01f)
        assertNotEquals("旧公式应当与正确结果不同（否则说明测试没覆盖旋转）",
            legacyX, good.x, 1f)
        assertNotEquals(legacyY, good.y, 1f)
    }

    /** 锚点偏移只看原点平移：改变 anchor 只整体平移，形状不变 */
    @Test
    fun anchorOnlyTranslates() {
        val centroid = Point2D(50f, 50f)
        val a = blockMapPoint(80f, 20f, ref(0f, 0f, 2f, 0.7f), centroid)
        val b = blockMapPoint(80f, 20f, ref(100f, -40f, 2f, 0.7f), centroid)
        assertEquals(100f, b.x - a.x, 0.01f)
        assertEquals(-40f, b.y - a.y, 0.01f)
    }
}
