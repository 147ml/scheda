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
    val canvasScale: Float = 1f
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
    // Shared position fields (for number labels, block refs)
    val x: Float? = null,
    val y: Float? = null,

    // Block ref
    val blockDefId: String? = null,
    val scale: Float? = null,
    val snapPointIndex: Int? = null,

    // Range label
    val reversed: Boolean? = null,

    // Common
    val layerId: Int = 1,
    val lineType: String = "SOLID",
    val dashLength: Float? = null,
    val gapLength: Float? = null,
    val lineScaleFactor: Float? = null
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
