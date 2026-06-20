package com.scheda.app.file

import android.content.Context
import com.google.gson.Gson
import com.scheda.app.model.*
import com.scheda.app.model.DrawingPrimitive.*

/**
 * Scheda 文档序列化工具。
 *
 * SchedaDocument (data class) ↔ JSON
 * 每 commit 一笔即时存 .recovery。
 */
class SchedaSerializer(private val context: Context) {

    private val gson = Gson()

    fun toJson(doc: SchedaDocument): String = gson.toJson(doc)

    // ═══════════════════════════════════════════════════════
    //  内部数据 ↔ SchedaDocument (可序列化)
    // ═══════════════════════════════════════════════════════

    fun toDocument(
        primitives: List<DrawingPrimitive>,
        layers: List<Layer>,
        blockDefs: List<BlockDef>,
        activeLayerId: Int,
        name: String,
        canvasOffsetX: Float = 0f,
        canvasOffsetY: Float = 0f,
        canvasScale: Float = 1f
    ): SchedaDocument {
        return SchedaDocument(
            version = 3,
            name = name,
            primitives = primitives.map { it.toSerializable() },
            layers = layers.map { it.toSerializable() },
            blockDefs = blockDefs.map { it.toSerializable() },
            activeLayerId = activeLayerId,
            currentTool = "FREEHAND",
            updatedAt = System.currentTimeMillis(),
            canvasOffsetX = canvasOffsetX,
            canvasOffsetY = canvasOffsetY,
            canvasScale = canvasScale
        )
    }

    fun fromDocument(doc: SchedaDocument): DocumentData {
        return DocumentData(
            primitives = doc.primitives.mapNotNull { it.toPrimitive() },
            layers = doc.layers.map { it.toLayer() },
            blockDefs = doc.blockDefs.map { it.toBlockDef() },
            activeLayerId = doc.activeLayerId,
            canvasOffsetX = doc.canvasOffsetX,
            canvasOffsetY = doc.canvasOffsetY,
            canvasScale = doc.canvasScale
        )
    }

    // ═══════════════════════════════════════════════════════
    //  序列化转换扩展
    // ═══════════════════════════════════════════════════════

    private fun DrawingPrimitive.toSerializable(): SerializablePrimitive {
        val base = SerializablePrimitive(
            type = "",
            layerId = layerId,
            color = (color.hashCode() shl 32) ushr 32,
            strokeWidth = strokeWidth,
            lineType = lineStyle.type.name,
            dashLength = lineStyle.dashLength,
            gapLength = lineStyle.gapLength,
            lineScaleFactor = lineScaleFactor
        )
        return when (this) {
            is FreehandPath -> base.copy(
                type = "freehand",
                points = points.map { listOf(it.x, it.y) },
                isClosed = isClosed
            )
            is RectanglePrimitive -> base.copy(
                type = "rectangle",
                startX = startX, startY = startY,
                endX = endX, endY = endY,
                rotation = rotation
            )
            is CirclePrimitive -> base.copy(
                type = "circle",
                centerX = centerX, centerY = centerY,
                endX = endX, endY = endY
            )
            is LinePrimitive -> base.copy(
                type = "line",
                startX = startX, startY = startY,
                endX = endX, endY = endY
            )
            is NumberLabelPrimitive -> base.copy(
                type = "number",
                value = value,
                x = x, y = y,
                rotation = rotation,
                fontSize = fontSize,
                horizontalOnly = horizontalOnly
            )
            is DrawingPrimitive.TextPrimitive -> base.copy(
                type = "text",
                text = text,
                x = x, y = y,
                rotation = rotation,
                fontSize = fontSize,
                horizontalOnly = horizontalOnly
            )
            is DrawingPrimitive.RangeLabelPrimitive -> base.copy(
                type = "range",
                value = startValue,
                endValue = endValue,
                x = x, y = y,
                rotation = rotation,
                fontSize = fontSize,
                horizontalOnly = horizontalOnly,
                scale = arrowSpan,
                reversed = reversed
            )
            is DrawingPrimitive.BlockRefPrimitive -> base.copy(
                type = "blockRef",
                blockDefId = blockDefId,
                x = x, y = y,
                scale = scale,
                rotation = rotation,
                snapPointIndex = snapPointIndex
            )
        }
    }

    private fun SerializablePrimitive.toPrimitive(): DrawingPrimitive? {
        val lineStyle = LineStyle(
            type = try { LineType.valueOf(lineType) } catch (_: Exception) { LineType.SOLID },
            dashLength = dashLength ?: 12f,
            gapLength = gapLength ?: 8f
        )
        val color = androidx.compose.ui.graphics.Color(color)
        return when (type) {
            "freehand" -> FreehandPath(
                points = points?.map { Point2D(it[0], it[1]) } ?: emptyList(),
                isClosed = isClosed ?: false,
                color = color, strokeWidth = strokeWidth,
                layerId = layerId, lineStyle = lineStyle
            )
            "rectangle" -> RectanglePrimitive(
                startX = startX ?: 0f, startY = startY ?: 0f,
                endX = endX ?: 0f, endY = endY ?: 0f,
                rotation = rotation ?: 0f,
                color = color, strokeWidth = strokeWidth,
                layerId = layerId, lineStyle = lineStyle
            )
            "circle" -> CirclePrimitive(
                centerX = centerX ?: 0f, centerY = centerY ?: 0f,
                endX = endX ?: 0f, endY = endY ?: 0f,
                color = color, strokeWidth = strokeWidth,
                layerId = layerId, lineStyle = lineStyle
            )
            "line" -> LinePrimitive(
                startX = startX ?: 0f, startY = startY ?: 0f,
                endX = endX ?: 0f, endY = endY ?: 0f,
                color = color, strokeWidth = strokeWidth,
                layerId = layerId, lineStyle = lineStyle
            )
            "number" -> NumberLabelPrimitive(
                value = value ?: 0,
                x = x ?: 0f, y = y ?: 0f,
                rotation = rotation ?: 0f,
                fontSize = fontSize ?: 30f,
                color = color, strokeWidth = strokeWidth,
                layerId = layerId, horizontalOnly = horizontalOnly ?: false
            )
            "text" -> TextPrimitive(
                text = text ?: "",
                x = x ?: 0f, y = y ?: 0f,
                rotation = rotation ?: 0f,
                fontSize = fontSize ?: 40f,
                color = color, strokeWidth = strokeWidth,
                layerId = layerId, horizontalOnly = horizontalOnly ?: false
            )
            "range" -> RangeLabelPrimitive(
                startValue = value ?: 1,
                endValue = endValue ?: 2,
                x = x ?: 0f, y = y ?: 0f,
                rotation = rotation ?: 0f,
                fontSize = fontSize ?: 30f,
                arrowSpan = scale ?: 1f,
                reversed = reversed ?: false,
                color = color, strokeWidth = strokeWidth,
                layerId = layerId, horizontalOnly = horizontalOnly ?: true
            )
            "blockRef" -> BlockRefPrimitive(
                blockDefId = blockDefId ?: "",
                x = x ?: 0f, y = y ?: 0f,
                scale = scale ?: 1f,
                rotation = rotation ?: 0f,
                snapPointIndex = snapPointIndex ?: -1,
                color = color, strokeWidth = strokeWidth,
                layerId = layerId, lineStyle = lineStyle
            )
            else -> null
        }
    }

    private fun Layer.toSerializable() = SerializableLayer(
        id = id, name = name,
        color = (color.hashCode() shl 32) ushr 32,
        isVisible = isVisible, isLocked = isLocked
    )

    private fun SerializableLayer.toLayer() = Layer(
        id = id, name = name,
        color = androidx.compose.ui.graphics.Color(color),
        isVisible = isVisible, isLocked = isLocked
    )

    fun blockDefToSerializable(bd: BlockDef): SerializableBlockDef = bd.toSerializable()
    fun serializableToBlockDef(sb: SerializableBlockDef): BlockDef = sb.toBlockDef()

    private fun BlockDef.toSerializable() = SerializableBlockDef(
        id = id, name = name,
        primitives = primitives.map { it.toSerializable() },
        snapPoints = snapPoints.map { listOf(it.x, it.y) }
    )

    private fun SerializableBlockDef.toBlockDef() = BlockDef(
        id = id, name = name,
        primitives = primitives.mapNotNull { it.toPrimitive() },
        snapPoints = snapPoints.map { SnapPoint(it[0], it[1]) }
    )
}

/** 从 SchedaDocument 反序列化后的完整数据 */
data class DocumentData(
    val primitives: List<DrawingPrimitive>,
    val layers: List<Layer>,
    val blockDefs: List<BlockDef>,
    val activeLayerId: Int,
    val canvasOffsetX: Float = 0f,
    val canvasOffsetY: Float = 0f,
    val canvasScale: Float = 1f
)
