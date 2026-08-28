package com.scheda.app.file

import android.content.Context
import com.google.gson.Gson
import com.scheda.app.model.*
import com.scheda.app.model.DrawingPrimitive.*
import androidx.compose.ui.graphics.toArgb

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
        canvasScale: Float = 1f,
        numberHorizontal: Boolean = true,
        numberCircled: Boolean = false,
        textHorizontal: Boolean = true,
        rangeHorizontal: Boolean = true,
        rangeReversed: Boolean = false,
        rangeNumbersFaceLeft: Boolean = false,
        defaultArrowSpan: Float = 1f,
        strokeWidth: Float? = null,
        globalLineScale: Float? = null,
        numberStart: Int? = null,
        rangeStart: Int? = null,
        rangeEnd: Int? = null,
        rangeLastEnd: Int? = null,
        undoHistoryV2: SerializableUndoHistory? = null,
        images: List<ReferenceImage> = emptyList()
    ): SchedaDocument {
        return SchedaDocument(
            version = 5,
            name = name,
            primitives = primitives.map { it.toSerializable() },
            layers = layers.map { it.toSerializable() },
            blockDefs = blockDefs.map { it.toSerializable() },
            activeLayerId = activeLayerId,
            currentTool = "FREEHAND",
            updatedAt = System.currentTimeMillis(),
            canvasOffsetX = canvasOffsetX,
            canvasOffsetY = canvasOffsetY,
            canvasScale = canvasScale,
            numberHorizontal = numberHorizontal,
            numberCircled = numberCircled,
            textHorizontal = textHorizontal,
            rangeHorizontal = rangeHorizontal,
            rangeReversed = rangeReversed,
            rangeNumbersFaceLeft = rangeNumbersFaceLeft,
            defaultArrowSpan = defaultArrowSpan,
            strokeWidth = strokeWidth,
            globalLineScale = globalLineScale,
            numberStart = numberStart,
            rangeStart = rangeStart,
            rangeEnd = rangeEnd,
            rangeLastEnd = rangeLastEnd,
            undoHistoryV2 = undoHistoryV2,
            images = images.ifEmpty { null }
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
            canvasScale = doc.canvasScale,
            numberHorizontal = doc.numberHorizontal,
            numberCircled = doc.numberCircled,
            textHorizontal = doc.textHorizontal,
            rangeHorizontal = doc.rangeHorizontal,
            rangeReversed = doc.rangeReversed,
            rangeNumbersFaceLeft = doc.rangeNumbersFaceLeft,
            defaultArrowSpan = doc.defaultArrowSpan,
            strokeWidth = doc.strokeWidth,
            globalLineScale = doc.globalLineScale,
            numberStart = doc.numberStart,
            rangeStart = doc.rangeStart,
            rangeEnd = doc.rangeEnd,
            rangeLastEnd = doc.rangeLastEnd,
            undoHistory = doc.undoHistory,
            undoHistoryV2 = doc.undoHistoryV2,
            images = doc.images ?: emptyList()
        )
    }

    // ═══════════════════════════════════════════════════════
    //  序列化转换扩展
    // ═══════════════════════════════════════════════════════

    private fun DrawingPrimitive.toSerializable(): SerializablePrimitive {
        val base = SerializablePrimitive(
            type = "",
            layerId = layerId,
            color = color.toArgb(),
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
                isClosed = isClosed,
                sharpCorners = sharpCorners.toList()
            )
            is RectanglePrimitive -> base.copy(
                type = "rectangle",
                corners = corners.map { listOf(it.x, it.y) },
                rotation = rotation
            )
            is CirclePrimitive -> base.copy(
                type = "circle",
                centerX = centerX, centerY = centerY,
                endX = endX, endY = endY,
                rotation = rotation,
                circleRx = rx, circleRy = ry
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
                horizontalOnly = horizontalOnly,
                circled = circled
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
                reversed = reversed,
                numbersFaceLeft = numbersFaceLeft
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
        val lsf = lineScaleFactor ?: 1f
        return when (type) {
            "freehand" -> FreehandPath(
                points = points?.map { Point2D(it[0], it[1]) } ?: emptyList(),
                isClosed = isClosed ?: false,
                color = color, strokeWidth = strokeWidth,
                layerId = layerId, lineStyle = lineStyle, lineScaleFactor = lsf,
                sharpCorners = sharpCorners?.toSet() ?: emptySet()
            )
            "rectangle" -> {
                val c = corners
                if (c != null && c.size == 4) {
                    RectanglePrimitive(
                        corners = c.map { Point2D(it[0], it[1]) },
                        rotation = rotation ?: 0f,
                        color = color, strokeWidth = strokeWidth,
                        layerId = layerId, lineStyle = lineStyle, lineScaleFactor = lsf
                    )
                } else {
                    RectanglePrimitive(
                        startX = startX ?: 0f, startY = startY ?: 0f,
                        endX = endX ?: 0f, endY = endY ?: 0f,
                        rotation = rotation ?: 0f,
                        color = color, strokeWidth = strokeWidth,
                        layerId = layerId, lineStyle = lineStyle, lineScaleFactor = lsf
                    )
                }
            }
            "circle" -> CirclePrimitive(
                centerX = centerX ?: 0f, centerY = centerY ?: 0f,
                endX = endX ?: 0f, endY = endY ?: 0f,
                rotation = rotation ?: 0f,
                rx = circleRx ?: 0f, ry = circleRy ?: 0f,
                color = color, strokeWidth = strokeWidth,
                layerId = layerId, lineStyle = lineStyle, lineScaleFactor = lsf
            )
            "line" -> LinePrimitive(
                startX = startX ?: 0f, startY = startY ?: 0f,
                endX = endX ?: 0f, endY = endY ?: 0f,
                color = color, strokeWidth = strokeWidth,
                layerId = layerId, lineStyle = lineStyle, lineScaleFactor = lsf
            )
            "number" -> NumberLabelPrimitive(
                value = value ?: 0,
                x = x ?: 0f, y = y ?: 0f,
                rotation = rotation ?: 0f,
                fontSize = fontSize ?: 30f,
                color = color, strokeWidth = strokeWidth,
                layerId = layerId, horizontalOnly = horizontalOnly ?: false,
                circled = circled ?: false, lineScaleFactor = lsf
            )
            "text" -> TextPrimitive(
                text = text ?: "",
                x = x ?: 0f, y = y ?: 0f,
                rotation = rotation ?: 0f,
                fontSize = fontSize ?: 40f,
                color = color, strokeWidth = strokeWidth,
                layerId = layerId, horizontalOnly = horizontalOnly ?: false, lineScaleFactor = lsf
            )
            "range" -> RangeLabelPrimitive(
                startValue = value ?: 1,
                endValue = endValue ?: 2,
                x = x ?: 0f, y = y ?: 0f,
                rotation = rotation ?: 0f,
                fontSize = fontSize ?: 30f,
                arrowSpan = scale ?: 1f,
                reversed = reversed ?: false,
                numbersFaceLeft = numbersFaceLeft ?: false,
                color = color, strokeWidth = strokeWidth,
                layerId = layerId, horizontalOnly = horizontalOnly ?: true, lineScaleFactor = lsf
            )
            "blockRef" -> BlockRefPrimitive(
                blockDefId = blockDefId ?: "",
                x = x ?: 0f, y = y ?: 0f,
                scale = scale ?: 1f,
                rotation = rotation ?: 0f,
                snapPointIndex = snapPointIndex ?: -1,
                color = color, strokeWidth = strokeWidth,
                layerId = layerId, lineStyle = lineStyle, lineScaleFactor = lsf
            )
            else -> null
        }
    }

    private fun Layer.toSerializable() = SerializableLayer(
        id = id, name = name,
        color = color.toArgb(),
        isVisible = isVisible, isLocked = isLocked
    )

    private fun SerializableLayer.toLayer() = Layer(
        id = id, name = name,
        color = androidx.compose.ui.graphics.Color(color),
        isVisible = isVisible, isLocked = isLocked
    )

    fun blockDefToSerializable(bd: BlockDef): SerializableBlockDef = bd.toSerializable()
    fun serializableToBlockDef(sb: SerializableBlockDef): BlockDef = sb.toBlockDef()

    /** Convert a single SerializablePrimitive to DrawingPrimitive (shared with ShareUtil). */
    fun serializableToPrimitive(sp: SerializablePrimitive): DrawingPrimitive? = sp.toPrimitive()

    /** Convert a single DrawingPrimitive to SerializablePrimitive (used for undo-history persistence). */
    fun primitiveToSerializable(p: DrawingPrimitive): SerializablePrimitive = p.toSerializable()

    /** Convert a single Layer to SerializableLayer (used for undo-history persistence). */
    fun layerToSerializable(l: Layer): SerializableLayer = l.toSerializable()

    /** Convert a single SerializableLayer to Layer (shared with ShareUtil). */
    fun serializableToLayer(sl: SerializableLayer): Layer = sl.toLayer()

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
    val canvasScale: Float = 1f,
    val numberHorizontal: Boolean = true,
    val numberCircled: Boolean = false,
    val textHorizontal: Boolean = true,
    val rangeHorizontal: Boolean = true,
    val rangeReversed: Boolean = false,
    val rangeNumbersFaceLeft: Boolean = false,
    val defaultArrowSpan: Float = 1f,
    // 图纸级变量（null = 旧版文件无此字段，加载时用默认值）
    val strokeWidth: Float? = null,
    val globalLineScale: Float? = null,
    val numberStart: Int? = null,
    val rangeStart: Int? = null,
    val rangeEnd: Int? = null,
    val rangeLastEnd: Int? = null,
    // 撤销历史（null = 无此字段）。undoHistory 为旧版全量格式，undoHistoryV2 为增量池格式
    val undoHistory: List<SerializableUndoSnapshot>? = null,
    val undoHistoryV2: SerializableUndoHistory? = null,
    // 参考图片（旧版文件无此字段 → 空列表）
    val images: List<ReferenceImage> = emptyList()
)
