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
    val reversed: Boolean = false
)
