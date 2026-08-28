package com.scheda.app.model

/**
 * Scheda 文档 — 序列化为 JSON 的完整工程文件结构。
 *
 * 每张图纸 = 一个 .scheda 文件，内部就是此结构的 JSON 表示。
 */
data class SchedaDocument(
    val version: Int = 3,
    val name: String = "",
    val primitives: List<SerializablePrimitive> = emptyList(),
    val layers: List<SerializableLayer> = listOf(
        SerializableLayer(id = 0, name = "图层0")
    ),
    val blockDefs: List<SerializableBlockDef> = emptyList(),
    val activeLayerId: Int = 0,
    val currentTool: String = "FREEHAND",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    // Canvas view state (saved so DXF export preserves viewport position)
    val canvasOffsetX: Float = 0f,
    val canvasOffsetY: Float = 0f,
    val canvasScale: Float = 1f,
    // Document-level tool presets (per-document, not app-global)
    val numberHorizontal: Boolean = true,
    val numberCircled: Boolean = false,
    val textHorizontal: Boolean = true,
    val rangeHorizontal: Boolean = true,
    val rangeReversed: Boolean = false,
    val rangeNumbersFaceLeft: Boolean = false,
    val defaultArrowSpan: Float = 1f,
    // 图纸级变量（每张图纸独立；null = 旧版文件无此字段，加载时用默认值）
    // 默认值：线性比例=1，线宽=5，数字=1，区间数字首数字=1、尾数字为空
    val strokeWidth: Float? = null,
    val globalLineScale: Float? = null,
    val numberStart: Int? = null,
    val rangeStart: Int? = null,
    val rangeEnd: Int? = null,
    val rangeLastEnd: Int? = null,
    // 撤销历史（null = 旧版文件无此字段）。持久化撤销存档，重开图纸后仍可撤销
    // 旧格式：每步全量快照，体积约为图纸的 N 倍，仅用于读取旧文件，不再写入
    val undoHistory: List<SerializableUndoSnapshot>? = null,
    // 新格式（version 4）：对象池 + 索引引用，整个历史只存一份图纸数据
    val undoHistoryV2: SerializableUndoHistory? = null,
    // 参考图片（version 5；null = 旧版文件无此字段）
    val images: List<ReferenceImage>? = null
)

/** 撤销快照的可序列化形式（与 DrawingViewModel.UndoSnapshot 对应） */
data class SerializableUndoSnapshot(
    val primitives: List<SerializablePrimitive> = emptyList(),
    val layers: List<SerializableLayer> = emptyList(),
    val blockDefs: List<SerializableBlockDef> = emptyList(),
    val activeLayerId: Int = 0,
    val canvasOffsetX: Float = 0f,
    val canvasOffsetY: Float = 0f,
    val canvasScale: Float = 1f,
    val lastTextFontSize: Float = 40f,
    val lastNumberFontSize: Float = 30f,
    val numberLabel: NumberLabel? = null,
    val selectedIndices: Set<Int> = emptySet(),
    val selectionRotation: Float = 0f
)

/**
 * 撤销历史的增量存储：相邻快照共享绝大多数对象，
 * 全历史只维护一个对象池，每个快照只存池索引 + 标量字段。
 */
data class SerializableUndoHistory(
    val primitivePool: List<SerializablePrimitive> = emptyList(),
    val layerPool: List<SerializableLayer> = emptyList(),
    val blockDefPool: List<SerializableBlockDef> = emptyList(),
    val imagePool: List<ReferenceImage> = emptyList(),
    val snapshots: List<SerializableUndoSnapshotRef> = emptyList()
)

/** 增量格式下的单个快照：列表内容用对象池索引表示，标量字段与 SerializableUndoSnapshot 一致 */
data class SerializableUndoSnapshotRef(
    val primitiveRefs: List<Int> = emptyList(),
    val layerRefs: List<Int> = emptyList(),
    val blockDefRefs: List<Int> = emptyList(),
    val imageRefs: List<Int> = emptyList(),
    val activeLayerId: Int = 0,
    val canvasOffsetX: Float = 0f,
    val canvasOffsetY: Float = 0f,
    val canvasScale: Float = 1f,
    val lastTextFontSize: Float = 40f,
    val lastNumberFontSize: Float = 30f,
    val numberLabel: NumberLabel? = null,
    val selectedIndices: Set<Int> = emptySet(),
    val selectionRotation: Float = 0f
)

// ─── 可序列化的基元 ──────────────────────────────────────

data class SerializablePrimitive(
    val type: String,          // "freehand", "rectangle", "circle", "line", "number", "blockRef"
    val color: Int,            // ARGB
    val strokeWidth: Float,

    // FreehandPath
    val points: List<List<Float>>? = null,  // [[x,y], ...]
    val isClosed: Boolean? = null,

    // Rectangle
    val startX: Float? = null,
    val startY: Float? = null,
    val endX: Float? = null,
    val endY: Float? = null,

    // Circle (two-point diameter mode)
    val centerX: Float? = null,
    val centerY: Float? = null,

    // Number label + Block ref
    val value: Int? = null,
    val endValue: Int? = null,
    val text: String? = null,
    val rotation: Float? = null,
    val fontSize: Float? = null,
    val horizontalOnly: Boolean? = null,
    val circled: Boolean? = null,
    // Shared position fields (for number labels, block refs)
    val x: Float? = null,
    val y: Float? = null,

    // Block ref
    val blockDefId: String? = null,
    val scale: Float? = null,
    val snapPointIndex: Int? = null,

    // Range label
    val reversed: Boolean? = null,
    // Range label 两端数字朝向（null/false = 朝下/正向，true = 朝左）
    val numbersFaceLeft: Boolean? = null,

    // Circle radii (independent of endX/endY, avoids deformation after rotation)
    val circleRx: Float? = null,
    val circleRy: Float? = null,

    // Rectangle corners (4-corner storage, [[x,y],...])
    val corners: List<List<Float>>? = null,

    // Common
    val layerId: Int = 1,
    val lineType: String = "SOLID",
    val dashLength: Float? = null,
    val gapLength: Float? = null,
    val lineScaleFactor: Float? = null,

    // FreehandPath sharp corners
    val sharpCorners: List<Int>? = null
)

data class SerializableLayer(
    val id: Int,
    val name: String,
    val color: Int = 0xFF000000.toInt(),
    val isVisible: Boolean = true,
    val isLocked: Boolean = false
)

data class SerializableBlockDef(
    val id: String,
    val name: String,
    val primitives: List<SerializablePrimitive> = emptyList(),
    val snapPoints: List<List<Float>> = emptyList()  // [[x,y], ...]
)
