package com.scheda.app.model

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 区间数字单段文字（两端数字 / 中间数字）的绘制信息。
 *
 * 坐标与角度约定：
 * - 锚点 [x]/[y] 为世界/模型坐标（App 画布坐标，Y 向下）。
 * - [angle] 为文字绘制角，弧度、屏幕顺时针为正（与 Android Canvas.rotate 语义一致）。
 */
data class RangeTextPlacement(
    val angle: Float,
    val x: Float,
    val y: Float
)

/** 区间数字朝向角度计算的结果：区间线（箭头）朝向 + 各文字段的绘制角度与锚点。 */
data class RangeLabelLayoutInfo(
    /** 区间线（箭头）朝向角（弧度，屏幕顺时针为正），= 布局旋转角 + 横屏偏移。 */
    val arrowAngle: Float,
    /** 起始端数字的绘制角度与锚点。 */
    val startText: RangeTextPlacement,
    /** 结束端数字的绘制角度与锚点。 */
    val endText: RangeTextPlacement,
    /** 中间数字的绘制角度与锚点（当前数据模型无中间数字字段，规则默认"跟随区间线方向"）。 */
    val middleText: RangeTextPlacement
)

/**
 * 区间数字朝向角度纯函数：画布渲染（Compose）与 DXF 导出两条独立路径共用，
 * 保证两处方向永远一致。输入布局旋转角，输出文字绘制角度与锚点信息。
 *
 * 朝向规则：
 * 1. 两端数字绘制角 = [numberAngle]（0 = 朝下/正向，π/2 = 朝左——数字下方朝屏幕左边，
 *    从上往下读），只影响数字本身的朝向，不随区间朝向旋转，也不动锚点与箭头。
 * 2. 区间线（箭头）朝向 = 布局旋转角（0 = 横向区间，π/2 = 竖向区间）。
 *    显示只由图元存储数据决定，与全局横屏开关无关：横屏模式只影响新图元
 *    生成时写入的 rotation，已有图元（含旧图纸）渲染永远不变。
 * 3. 中间数字（未启用）默认"跟随区间线方向"（angle = [arrowAngle]）。
 */
object RangeLabelLayout {

    /** 90°（弧度），横屏旋转偏移量（仅供生成新图元时使用，渲染路径不再消费）。 */
    val PI_HALF: Float = (PI / 2).toFloat()

    /** 两端数字绘制角：false = 朝下（正向，0），true = 朝左（+90°，屏幕顺时针）。 */
    fun numberAngleFor(numbersFaceLeft: Boolean): Float = if (numbersFaceLeft) PI_HALF else 0f

    /** 区间线（箭头）朝向角 = 布局旋转角。 */
    fun arrowAngle(storedRotation: Float): Float = storedRotation

    /** 箭头半长（与画布绘制 drawRangeLabel 一致：arrowLen/2）。 */
    fun arrowHalfLength(arrowSpan: Float): Float = max(40f * arrowSpan, 10f)

    /**
     * 箭头两翼终点相对尖端的单位偏移（未乘翼长）：尖端朝向角 dirA = arrowAngle
     *（反向时 +π，尖端落在起始端、朝反方向），两翼 = 尖端反方向 ±45°。
     * 返回值各分量范围 √2 内，调用方乘翼长 hs 得实际偏移（与历史视觉一致，不做归一）。
     * 画布渲染（Compose）、待确认预览、DXF 导出三处共用同一公式，方向永远一致。
     */
    fun arrowheadWingOffsets(arrowAngle: Float, reversed: Boolean): Pair<Pair<Float, Float>, Pair<Float, Float>> {
        val dirA = if (reversed) arrowAngle + PI.toFloat() else arrowAngle
        val cosD = cos(dirA); val sinD = sin(dirA)
        return Pair(
            (sinD - cosD) to -(sinD + cosD),   // 翼1 = 反方向 +45°
            (-(sinD + cosD)) to (cosD - sinD)  // 翼2 = 反方向 -45°
        )
    }

    /**
     * 计算区间数字完整朝向布局。
     *
     * @param storedRotation 布局旋转角（弧度，屏幕顺时针为正）；0 = 横向区间，π/2 = 竖向区间。
     * @param x/y            区间中心锚点（世界坐标）。
     * @param fontSize       字号（决定数字与箭头末端的间距）。
     * @param arrowSpan      箭线跨度倍率。
     * @param reversed       是否反向（起始/结束端互换、箭头朝起始端）。
     * @param numberAngle    两端数字绘制角（弧度，屏幕顺时针为正）；0 = 朝下/正向，π/2 = 朝左。
     */
    fun compute(
        storedRotation: Float,
        x: Float,
        y: Float,
        fontSize: Float,
        arrowSpan: Float,
        reversed: Boolean = false,
        numberAngle: Float = 0f
    ): RangeLabelLayoutInfo {
        val half = arrowHalfLength(arrowSpan)
        val d = half + fontSize
        val arrowA = arrowAngle(storedRotation)
        val textA = numberAngle
        val cosA = cos(arrowA); val sinA = sin(arrowA)

        fun rotateLocal(lx: Float, ly: Float): Pair<Float, Float> {
            val wx = x + lx * cosA - ly * sinA
            val wy = y + lx * sinA + ly * cosA
            return wx to wy
        }

        // reversed 不影响锚点位置——调用方（drawPrimitiveAt/drawRangeLabel）
        // 已经用 reversed 互换文字内容，如果 compute 再互换锚点就互相抵消。
        // 箭头方向由 arrowheadWingOffsets(arrowAngle, reversed) 在调用方控制。
        val startLocal = -d
        val endLocal = d
        val (sx, sy) = rotateLocal(startLocal, 0f)
        val (ex, ey) = rotateLocal(endLocal, 0f)

        return RangeLabelLayoutInfo(
            arrowAngle = arrowA,
            startText = RangeTextPlacement(textA, sx, sy),
            endText = RangeTextPlacement(textA, ex, ey),
            middleText = RangeTextPlacement(arrowA, x, y)
        )
    }
}
