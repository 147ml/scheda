package com.scheda.app.model

/** 线型 */
enum class LineType(val displayName: String) {
    SOLID("实线"),
    DASHED("虚线"),
    LIGHTNING("接闪带");
}

/** 线型样式 */
data class LineStyle(
    val type: LineType = LineType.SOLID,
    /** 虚线：间隔长度 */
    val dashLength: Float = 12f,
    val gapLength: Float = 8f
)
