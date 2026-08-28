package com.scheda.app.model

/**
 * 标注数字的状态
 *
 * 选起始值→点画布生成→自动+1
 * 生成的数字还没固定，可以调大小/位置/方向
 */
data class NumberLabel(
    val startFrom: Int = 1,
    val currentValue: Int = 1,
    val fontSize: Float = 30f,
    val horizontalOnly: Boolean = true,
    val circled: Boolean = false,
    /** 已放置但尚未固定的半成品 */
    val pending: NumberLabelInstance? = null
)

data class NumberLabelInstance(
    val value: Int,
    val x: Float,
    val y: Float,
    val rotation: Float = 0f,
    val fontSize: Float = 30f
)

/** 区间数字状态 */
data class RangeLabel(
    val startValue: Int = 1,
    val endValue: Int = 2,
    val fontSize: Float = 30f,
    /** 上次确认的尾数字，下一个首数字自动=lastEndValue+1 */
    val lastEndValue: Int = 1,
    val horizontalOnly: Boolean = true,
    val reversed: Boolean = false,
    val numbersFaceLeft: Boolean = false,
    val arrowSpan: Float = 1f
)

/** 数字阵列状态：点屏生成 startValue..endValue 一串普通数字 */
data class NumArrayLabel(
    val startValue: Int = 1,
    val endValue: Int = 5,
    val fontSize: Float = 30f,
    /** 相邻数字中心距（世界坐标） */
    val gap: Float = 100f,
    /** 排列角度（度，0=横向向右，正角=顺时针），任意角度 */
    val rotationDeg: Float = 0f,
    val circled: Boolean = false
)
