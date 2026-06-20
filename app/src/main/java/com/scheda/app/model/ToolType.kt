package com.scheda.app.model

/** 绘图/选择工具 */
enum class ToolType(val displayName: String) {
    FREEHAND("手绘"),
    RECTANGLE("矩形"),
    CIRCLE("圆形"),
    LINE("直线"),
    ANNOTATE("标注数字"),
    BLOCK("图块"),
    SELECT("选择"),
    ERASER("橡皮擦"),
    TEXT("文字"),
    RANGE("区间数字");
}

/** 当前选择的子模式 */
enum class SubTool {
    NONE,
    /** 约束模式（正圆、正方形、水平/垂直） */
    CONSTRAINED,
    /** 标注数字的横排强制 */
    HORIZONTAL_TEXT
}

/** 选择框方向 */
enum class SelectDirection {
    /** 从左→右 / 上→下：框碰到就算选中 */
    INSIDE_TOUCH,
    /** 从右→左 / 下→上：框必须完全覆盖才选中 */
    FULLY_COVER
}

/** 选中后操作 */
enum class SelectionAction {
    MOVE, ROTATE, SCALE, MIRROR, COPY, DELETE, CUT, PASTE, PROPERTIES, NONE
}

/** 编辑手柄位置 */
enum class HandlePosition {
    TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
    TOP_CENTER, BOTTOM_CENTER, LEFT_CENTER, RIGHT_CENTER,
    ROTATE_TOP, ROTATE_BOTTOM
}

/** 磁吸点 */
data class SnapPoint(
    val x: Float,
    val y: Float,
    val label: String = ""
)

/** 贴块实例 */
data class BlockInstance(
    val blockDefId: String,
    val x: Float,
    val y: Float,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val snapPointIndex: Int = 0
)
