package com.scheda.app.model

/** 选中状态 */
data class SelectionState(
    val isActive: Boolean = false,
    /** 选择框两点 */
    val selStartX: Float = 0f,
    val selStartY: Float = 0f,
    val selEndX: Float = 0f,
    val selEndY: Float = 0f,
    /** 选择方向 */
    val direction: SelectDirection = SelectDirection.FULLY_COVER,
    /** 被选中的基元索引 */
    val selectedIndices: Set<Int> = emptySet(),
    /** 选中包围盒（所有选中基元的并集） */
    val bounds: Bounds? = null,
    /** 正在变换中（变换后需确认/取消） */
    val isTransforming: Boolean = false,
    /** 累积旋转角度（弧度），用于画布上旋转框的显示 */
    val rotation: Float = 0f,
    /** 变换开始时的初始包围盒（变换过程中中心点固定） */
    val initialBounds: Bounds? = null,
    /** 正在进行的操作 */
    val action: SelectionAction = SelectionAction.NONE,
    /** 操作的参考点 */
    val refX: Float = 0f,
    val refY: Float = 0f,
    /** 是否隐藏中间手柄（纯文字/数字选择时） */
    val hideMidpoints: Boolean = false,
    /** 选择变换参数（渲染时应用，不直接修改基元） */
    val selScaleX: Float = 1f,
    val selScaleY: Float = 1f,
    val selOffsetX: Float = 0f,
    val selOffsetY: Float = 0f,
    /** 变换起始值（用于计算增量，避免世界坐标变换产生累积误差） */
    val startRotation: Float = 0f,
    val startScaleX: Float = 1f,
    val startScaleY: Float = 1f,
    val startX: Float = 0f,
    val startY: Float = 0f
) {
    /** 选择框的标准化范围 */
    val left: Float get() = minOf(selStartX, selEndX)
    val top: Float get() = minOf(selStartY, selEndY)
    val right: Float get() = maxOf(selStartX, selEndX)
    val bottom: Float get() = maxOf(selStartY, selEndY)
}

/** 松手后可编辑状态 */
data class PendingEdit(
    val active: Boolean = false,
    val primitive: DrawingPrimitive? = null,
    /** 包围盒 */
    val bounds: Bounds? = null,
    /** 旋转量 */
    val rotation: Float = 0f,
    /** 缩放量 */
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    /** 偏移量 */
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    /** 固定旋转轴（创建时记录，不随 bounds 漂移） */
    val pivotX: Float = 0f,
    val pivotY: Float = 0f
) {
    fun isActive() = active && primitive != null
}
