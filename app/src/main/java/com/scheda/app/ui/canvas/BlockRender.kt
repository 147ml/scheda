package com.scheda.app.ui.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import com.scheda.app.model.BlockDef
import com.scheda.app.model.DrawingPrimitive
import com.scheda.app.model.LineStyle
import com.scheda.app.model.Point2D
import com.scheda.app.model.blockContentCentroid
import com.scheda.app.model.blockLineWidthScale
import com.scheda.app.model.radToDegrees
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 块引用（BlockRef）的唯一渲染入口。
 *
 * 画布本体、选中光晕、待确认/变换预览、以及嵌套块递归，全部经由本文件的三个函数，
 * 不允许再出现第二套「预览用」的块内图元绘制实现——Preview 与画布不一致的根因就是那份平行实现。
 */

private fun radToDeg(rad: Float): Float = radToDegrees(rad)

/**
 * 块引用的唯一锚定变换：`T(anchor) · R(rotation) · S(scale) · T(-shapeCentroid)`
 *
 * 数学等价于 model 包的 [com.scheda.app.model.blockMapPoint]（包围盒/DXF 导出用它算点），
 * 两侧必须保持一致——渲染侧走矩阵，非渲染侧走点映射。
 *
 * 语义：块内容形心（[blockContentCentroid]，不是 AABB 中心）落在 anchor 上。
 * 同一个公式也被预览（PostCreationOverlay）、包围盒（computeBlockRefWorldBounds / pfoWorldBounds）、
 * DXF 导出（DxfExporter.explodePrimitives）使用，四者必须保持一致。
 *
 * ⚠️ Canvas 变换是右乘：`v' = anchor + R(S(v - centroid))`。
 * 形心的平移必须写在**最内层**（最后调用）；写成
 * `translate(anchor - centroid*scale) → rotate → scale`（平移在外层）会让平移量不参与旋转，
 * 块一转内容就整体飘离锚点（实测旋转 45° 可偏出数百世界单位）。
 */
private inline fun DrawScope.withBlockTransform(
    anchor: Offset,
    rotationRad: Float,
    scale: Float,
    centroid: Point2D,
    block: DrawScope.() -> Unit
) {
    withTransform({
        translate(anchor.x, anchor.y)
        rotate(radToDeg(rotationRad), Offset.Zero)
        scale(scale, scale, Offset.Zero)
        translate(-centroid.x, -centroid.y)
    }, block)
}

/**
 * 绘制块内容（画布本体 / 预览 / 嵌套块共用）。
 *
 * @param anchor           块引用锚点，坐标系由调用方决定（画布=世界坐标，预览=屏幕坐标）
 * @param rotationRad      块自身旋转 + 调用方的额外旋转（预览要加 PFO 的 edit.rotation）
 * @param scale            块缩放；预览需把 avgScale × canvasScale 一并并进来
 * @param contentStrokeScale 传给块内图元的线宽系数（**已除块缩放**，实现"块缩放不改线宽"）
 * @param dashScale        传给块内图元的虚线系数（**未除块缩放**，实现"虚线随块放大"）
 * @param refStrokeWidth   块引用自身线宽：块整体线宽 = 它（块内各线保持相对比例）
 * @param strokeFloorScale 线宽下限系数（**1/块缩放**）：让 0.1 的 minWidth 在块的局部坐标系里同比缩小，
 *                         否则块放大几十倍后细线会被下限抬粗，屏幕线宽随放大倍数一起膨胀
 */
internal fun DrawScope.drawBlockContents(
    bd: BlockDef,
    blockDefs: List<BlockDef>,
    anchor: Offset,
    rotationRad: Float,
    scale: Float,
    contentStrokeScale: Float,
    dashScale: Float,
    alpha: Float = 1f,
    refStrokeWidth: Float = 1f,
    strokeFloorScale: Float = 1f
) {
    val centroid = blockContentCentroid(bd.primitives) ?: Point2D(0f, 0f)
    // 块整体线宽跟随块引用线宽（插入时=当前画笔线宽）。DXF 导入的块内线宽是 CAD 口径 0.25mm 级，
    // 直接用会细到几乎看不见；这里按 引用线宽/块内基准 放大，块内相对粗细不变。
    val lwScale = blockLineWidthScale(bd.primitives, refStrokeWidth)
    withBlockTransform(anchor, rotationRad, scale, centroid) {
        for (prim in bd.primitives) {
            // 块内每个图元走与画布完全相同的 drawPrimitive（唯一图元渲染入口），
            // 旋转/区间/嵌套块/尖角/线宽比例等行为才不会在预览里缺失。
            drawPrimitive(
                prim, alpha,
                strokeScale = contentStrokeScale * prim.lineScaleFactor * lwScale,
                blockDefs = blockDefs,
                dashScale = dashScale * prim.lineScaleFactor,
                strokeFloorScale = strokeFloorScale
            )
        }
    }
}

/**
 * 绘制块内容的光晕趟（选中高亮）。几何与 [drawBlockContents] 完全同源，只是画笔不同。
 *
 * @param strokeScale 块内线宽系数（已除块缩放）
 * @param inflate     当前坐标系下的单边外扩量（调用方需把屏幕 px 换算到块局部单位）
 * @param refStrokeWidth   块引用线宽（与 [drawBlockContents] 同口径）
 * @param strokeFloorScale 线宽下限系数（1/块缩放，与 [drawBlockContents] 同口径）
 */
internal fun DrawScope.drawBlockGlow(
    bd: BlockDef,
    blockDefs: List<BlockDef>,
    anchor: Offset,
    rotationRad: Float,
    scale: Float,
    strokeScale: Float,
    inflate: Float,
    gc: Color,
    refStrokeWidth: Float = 1f,
    strokeFloorScale: Float = 1f
) {
    val centroid = blockContentCentroid(bd.primitives) ?: Point2D(0f, 0f)
    val lwScale = blockLineWidthScale(bd.primitives, refStrokeWidth)
    withBlockTransform(anchor, rotationRad, scale, centroid) {
        for (prim in bd.primitives) {
            drawSelectionGlowPass(
                prim, inflate, gc,
                strokeScale * prim.lineScaleFactor * lwScale,
                blockDefs, strokeFloorScale
            )
        }
    }
}

/**
 * 块定义缺失时的占位：方块 + 对角交叉 + 旋转指示线。
 * 画布与预览共用，保证"块被删掉后预览/实际仍是同一个东西"。
 *
 * @param center    当前坐标系下的块锚点
 * @param halfSize  当前坐标系下的半边长（调用方按自己的单位换算）
 */
internal fun DrawScope.drawBlockPlaceholder(
    center: Offset,
    halfSize: Float,
    strokeScale: Float,
    strokeWidth: Float,
    color: Color,
    rotationRad: Float,
    lineStyle: LineStyle
) {
    val left = center.x - halfSize
    val top = center.y - halfSize
    val right = center.x + halfSize
    val bottom = center.y + halfSize

    val boxPath = Path().apply {
        moveTo(left, top)
        lineTo(right, top)
        lineTo(right, bottom)
        lineTo(left, bottom)
        close()
    }
    drawPathWithStyle(
        path = boxPath,
        color = color,
        strokeWidth = strokeWidth,
        lineStyle = lineStyle,
        strokeScale = strokeScale
    )

    val xw = 1.5f * strokeScale
    drawLine(color, Offset(left, top), Offset(right, bottom), strokeWidth = xw)
    drawLine(color, Offset(right, top), Offset(left, bottom), strokeWidth = xw)

    if (rotationRad != 0f) {
        val arrowLen = halfSize + 15f * strokeScale
        val ax = center.x + arrowLen * cos(rotationRad)
        val ay = center.y + arrowLen * sin(rotationRad)
        drawLine(color, center, Offset(ax, ay), strokeWidth = 2f * strokeScale)
    }
}

/** 块内线宽系数：把外层线宽系数按块缩放（含预览的 avgScale）除回去 */
internal fun blockContentStrokeScale(strokeScale: Float, blockScale: Float): Float =
    if (abs(blockScale) > 0.0001f) strokeScale / abs(blockScale) else strokeScale
