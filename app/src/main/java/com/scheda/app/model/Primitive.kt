package com.scheda.app.model

import androidx.compose.ui.graphics.Color

/** 2D 坐标点 */
data class Point2D(val x: Float, val y: Float)

/** 绘图基元 — 所有图形的基类 */
sealed class DrawingPrimitive(
    open val color: Color,
    open val strokeWidth: Float,
    open val layerId: Int,
    open val lineStyle: LineStyle = LineStyle(),  // 新增线型
    open val lineScaleFactor: Float = 1f           // 每对象线宽倍率
) {
    /** 自由手绘路径 */
    data class FreehandPath(
        val points: List<Point2D>,
        val isClosed: Boolean = false,
        override val color: Color,
        override val strokeWidth: Float,
        override val layerId: Int = 1,
        override val lineStyle: LineStyle = LineStyle(),
        override val lineScaleFactor: Float = 1f
    ) : DrawingPrimitive(color, strokeWidth, layerId, lineStyle, lineScaleFactor)

    /** 矩形 */
    data class RectanglePrimitive(
        val startX: Float, val startY: Float,
        val endX: Float, val endY: Float,
        val rotation: Float = 0f,
        override val color: Color,
        override val strokeWidth: Float,
        override val layerId: Int = 1,
        override val lineStyle: LineStyle = LineStyle(),
        override val lineScaleFactor: Float = 1f
    ) : DrawingPrimitive(color, strokeWidth, layerId, lineStyle, lineScaleFactor)

    /** 圆形（两点直径模式，支持椭圆） */
    data class CirclePrimitive(
        val centerX: Float, val centerY: Float,
        /** 直径终点X（两点画圆的第二个点） */
        val endX: Float, val endY: Float,
        override val color: Color,
        override val strokeWidth: Float,
        override val layerId: Int = 1,
        override val lineStyle: LineStyle = LineStyle(),
        override val lineScaleFactor: Float = 1f
    ) : DrawingPrimitive(color, strokeWidth, layerId, lineStyle, lineScaleFactor) {
        /** 横轴半径 */
        val radiusX: Float get() = kotlin.math.abs(endX - centerX)
        /** 纵轴半径 */
        val radiusY: Float get() = kotlin.math.abs(endY - centerY)
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
    ) : DrawingPrimitive(color, strokeWidth, layerId, lineStyle, lineScaleFactor)

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
        override val lineScaleFactor: Float = 1f
    ) : DrawingPrimitive(color, strokeWidth, layerId, lineScaleFactor = lineScaleFactor)

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
    ) : DrawingPrimitive(color, strokeWidth, layerId, lineScaleFactor = lineScaleFactor)

    /** 区间数字标注 首→尾 */
    data class RangeLabelPrimitive(
        val startValue: Int,
        val endValue: Int,
        val x: Float, val y: Float,
        val rotation: Float = 0f,
        val fontSize: Float = 30f,
        val arrowSpan: Float = 1f,
        val reversed: Boolean = false,
        override val color: Color,
        override val strokeWidth: Float = 2f,
        override val layerId: Int = 1,
        val horizontalOnly: Boolean = true,
        override val lineScaleFactor: Float = 1f
    ) : DrawingPrimitive(color, strokeWidth, layerId, lineScaleFactor = lineScaleFactor)

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
    ) : DrawingPrimitive(color, strokeWidth, layerId, lineStyle, lineScaleFactor)
}
