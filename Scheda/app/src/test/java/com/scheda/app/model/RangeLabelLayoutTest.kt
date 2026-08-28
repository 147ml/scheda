package com.scheda.app.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 区间数字朝向纯函数 RangeLabelLayout 的单元测试。
 *
 * 核心断言：显示只由图元存储的布局旋转角决定，与全局横屏开关无关——
 * 两端数字绘制角默认 0（朝下/正向），numberAngle=π/2 时为朝左（锚点与箭头不变），
 * 区间线沿布局方向（横向区间 0 / 竖向区间 π/2）。
 */
class RangeLabelLayoutTest {

    private val tol = 1e-4f
    private val px = 100f
    private val py = 200f
    private val fs = 30f
    private val span = 1f

    /** 期望锚点（由局部偏移绕箭头朝向旋转） */
    private fun rotated(x: Float, y: Float, angle: Float, lx: Float, ly: Float): Pair<Float, Float> {
        val c = cos(angle); val s = sin(angle)
        return (x + lx * c - ly * s) to (y + lx * s + ly * c)
    }

    private fun assertAngle(expected: Float, actual: Float) {
        assertTrue(
            "角度不符 expected=$expected actual=$actual",
            abs(expected - actual) < tol
        )
    }

    private fun assertPoint(expected: Pair<Float, Float>, actualX: Float, actualY: Float) {
        assertTrue(
            "锚点不符 expected=(${expected.first},${expected.second}) actual=($actualX,$actualY)",
            abs(expected.first - actualX) < tol && abs(expected.second - actualY) < tol
        )
    }

    @Test
    fun horizontalRange_endTextUpright_arrowHorizontal() {
        val layout = RangeLabelLayout.compute(0f, px, py, fs, span, reversed = false)
        val d = RangeLabelLayout.arrowHalfLength(span) + fs

        // 两端数字绘制角 = 0：文字正向
        assertAngle(0f, layout.startText.angle)
        assertAngle(0f, layout.endText.angle)
        // 区间线朝向 0（横向），起始端在左、结束端在右
        assertAngle(0f, layout.arrowAngle)
        assertPoint(rotated(px, py, 0f, -d, 0f), layout.startText.x, layout.startText.y)
        assertPoint(rotated(px, py, 0f, d, 0f), layout.endText.x, layout.endText.y)
    }

    @Test
    fun verticalRange_arrowVertical_endTextStillUpright() {
        val rot = (PI / 2).toFloat()
        val layout = RangeLabelLayout.compute(rot, px, py, fs, span, reversed = false)
        val d = RangeLabelLayout.arrowHalfLength(span) + fs

        // 两端数字绘制角仍为 0：不随区间朝向旋转
        assertAngle(0f, layout.startText.angle)
        assertAngle(0f, layout.endText.angle)
        // 区间线朝向 π/2（竖向）
        assertAngle(rot, layout.arrowAngle)
        assertPoint(rotated(px, py, rot, -d, 0f), layout.startText.x, layout.startText.y)
        assertPoint(rotated(px, py, rot, d, 0f), layout.endText.x, layout.endText.y)
    }

    // ── 反向 ──

    @Test
    fun reversedDoesNotSwapAnchors() {
        val layout = RangeLabelLayout.compute(0f, px, py, fs, span, reversed = true)
        val d = RangeLabelLayout.arrowHalfLength(span) + fs

        assertAngle(0f, layout.startText.angle)
        assertPoint(rotated(px, py, 0f, -d, 0f), layout.startText.x, layout.startText.y)
        assertPoint(rotated(px, py, 0f, d, 0f), layout.endText.x, layout.endText.y)
    }

    // ── 中间数字 ──

    @Test
    fun middleTextFollowsIntervalLineDirection() {
        val rot = (PI / 2).toFloat()
        val layout = RangeLabelLayout.compute(rot, px, py, fs, span)
        // 中间数字"跟随区间线方向"：绘制角 = 区间线朝向角，锚点 = 区间中心
        assertAngle(layout.arrowAngle, layout.middleText.angle)
        assertPoint(px to py, layout.middleText.x, layout.middleText.y)
    }

    @Test
    fun middleTextFollowsIntervalLineWhenHorizontal() {
        val layout = RangeLabelLayout.compute(0f, px, py, fs, span)
        assertAngle(0f, layout.middleText.angle)
        assertPoint(px to py, layout.middleText.x, layout.middleText.y)
    }

    // ── 数字朝向（朝下/朝左） ──

    @Test
    fun faceLeftNumbers_rotateEndTexts90deg_anchorsAndArrowUnchanged() {
        // numberAngle = +π/2（朝左：数字下方朝屏幕左边）：两端数字绘制角跟随，锚点与箭头朝向不变
        for (rot in listOf(0f, (PI / 2).toFloat())) {
            val layout = RangeLabelLayout.compute(rot, px, py, fs, span, reversed = false,
                numberAngle = RangeLabelLayout.PI_HALF)
            val d = RangeLabelLayout.arrowHalfLength(span) + fs

            assertAngle(RangeLabelLayout.PI_HALF, layout.startText.angle)
            assertAngle(RangeLabelLayout.PI_HALF, layout.endText.angle)
            assertAngle(rot, layout.arrowAngle)
            assertPoint(rotated(px, py, rot, -d, 0f), layout.startText.x, layout.startText.y)
            assertPoint(rotated(px, py, rot, d, 0f), layout.endText.x, layout.endText.y)
        }
    }

    @Test
    fun numberAngleForMapping() {
        assertAngle(0f, RangeLabelLayout.numberAngleFor(false))
        assertAngle(RangeLabelLayout.PI_HALF, RangeLabelLayout.numberAngleFor(true))
    }

    // ── 几何一致性 ──

    @Test
    fun anchorDistanceFromCenterIsHalfPlusGap() {
        for (rot in listOf(0f, (PI / 2).toFloat(), (PI / 4).toFloat())) {
            val layout = RangeLabelLayout.compute(rot, px, py, fs, span)
            val d = RangeLabelLayout.arrowHalfLength(span) + fs
            val ds = kotlin.math.hypot(layout.startText.x - px, layout.startText.y - py)
            val de = kotlin.math.hypot(layout.endText.x - px, layout.endText.y - py)
            assertTrue("起始端距离不符 ds=$ds d=$d", abs(ds - d) < tol)
            assertTrue("结束端距离不符 de=$de d=$d", abs(de - d) < tol)
        }
    }
}
