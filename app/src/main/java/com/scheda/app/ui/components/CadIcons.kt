package com.scheda.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 正交模式图标（CAD 风格）：直角折线 + 拐角内侧小方块（贴角式）。
 * 描边颜色会被 Icon(tint=...) 的 ColorFilter 覆盖。
 */
val OrthoIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Ortho",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(6f, 4f)
            lineTo(6f, 18f)
            lineTo(20f, 18f)
        }
        // 拐角内侧小方块（贴角：左下角即折线拐点，边长 5）
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(6f, 13f)
            lineTo(11f, 13f)
            lineTo(11f, 18f)
            lineTo(6f, 18f)
            close()
        }
    }.build()
}
