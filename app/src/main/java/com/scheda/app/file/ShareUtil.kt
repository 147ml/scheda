package com.scheda.app.file

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.scheda.app.export.DxfWriter
import com.scheda.app.model.Layer
import com.scheda.app.model.LineStyle
import com.scheda.app.model.LineType
import com.scheda.app.model.Point2D
import com.scheda.app.model.SerializableLayer
import com.scheda.app.model.SerializablePrimitive
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 分享工具 — 将 .scheda 转为 .dxf 后分享。
 * 统一走 java.io.File 后端。
 */
class ShareUtil(
    private val context: Context,
    private val serializer: SchedaSerializer
) {
    private val cacheDir = File(context.cacheDir, "shares").also { it.mkdirs() }

    fun shareFile(file: File) {
        val dxfFile = convertToDxf(file) ?: return
        sendDxf(dxfFile)
    }

    fun shareMultipleFiles(files: List<File>) {
        val dxfFiles = files.mapNotNull { convertToDxf(it) }
        if (dxfFiles.isEmpty()) return
        sendZip(dxfFiles)
    }

    // --- 内部 ---

    private fun convertToDxf(schedaFile: File): File? {
        val doc = try {
            val json = schedaFile.readText(Charsets.UTF_8)
            com.google.gson.Gson().fromJson(json, com.scheda.app.model.SchedaDocument::class.java)
        } catch (_: Exception) { null } ?: return null
        if (doc.primitives.isEmpty()) return null

        val primitives = doc.primitives.mapNotNull { it.toDrawingPrimitive() }
        val layers = doc.layers.map { it.toLayer() }
        val dxfFile = File(cacheDir, schedaFile.name.replace(".scheda", "") + ".dxf")
        return try {
            DxfWriter(FileOutputStream(dxfFile)).write(
                primitives, layers,
                canvasOffsetX = doc.canvasOffsetX,
                canvasOffsetY = doc.canvasOffsetY,
                canvasScale = doc.canvasScale
            )
            dxfFile
        } catch (_: Exception) { null }
    }

    private fun sendDxf(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent(Intent.ACTION_SEND).apply {
            type = "application/dxf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.let { Intent.createChooser(it, "分享图纸") })
    }

    private fun sendZip(files: List<File>) {
        val zipFile = File(cacheDir, "scheda_share_${System.currentTimeMillis()}.zip")
        try {
            FileOutputStream(zipFile).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    for (file in files) {
                        zos.putNextEntry(ZipEntry(file.name))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
            context.startActivity(Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.let { Intent.createChooser(it, "分享图纸") })
        } catch (_: Exception) {}
    }
}

// ─── 序列化 → DrawingPrimitive 转换 ────────────────────

private fun SerializablePrimitive.toDrawingPrimitive(): com.scheda.app.model.DrawingPrimitive? {
    val ls = LineStyle(type = try { LineType.valueOf(lineType) } catch (_: Exception) { LineType.SOLID })
    return when (type) {
        "freehand" -> com.scheda.app.model.DrawingPrimitive.FreehandPath(
            points = points?.map { Point2D(it[0], it[1]) } ?: emptyList(),
            color = androidx.compose.ui.graphics.Color(color),
            strokeWidth = strokeWidth, layerId = layerId, lineStyle = ls
        )
        "rectangle" -> com.scheda.app.model.DrawingPrimitive.RectanglePrimitive(
            startX = startX ?: 0f, startY = startY ?: 0f,
            endX = endX ?: 0f, endY = endY ?: 0f,
            rotation = rotation ?: 0f,
            color = androidx.compose.ui.graphics.Color(color),
            strokeWidth = strokeWidth, layerId = layerId, lineStyle = ls
        )
        "circle" -> com.scheda.app.model.DrawingPrimitive.CirclePrimitive(
            centerX = centerX ?: 0f, centerY = centerY ?: 0f,
            endX = endX ?: 0f, endY = endY ?: 0f,
            color = androidx.compose.ui.graphics.Color(color),
            strokeWidth = strokeWidth, layerId = layerId, lineStyle = ls
        )
        "line" -> com.scheda.app.model.DrawingPrimitive.LinePrimitive(
            startX = startX ?: 0f, startY = startY ?: 0f,
            endX = endX ?: 0f, endY = endY ?: 0f,
            color = androidx.compose.ui.graphics.Color(color),
            strokeWidth = strokeWidth, layerId = layerId, lineStyle = ls
        )
        "number" -> com.scheda.app.model.DrawingPrimitive.NumberLabelPrimitive(
            value = value ?: 0,
            x = x ?: 0f, y = y ?: 0f,
            rotation = rotation ?: 0f,
            fontSize = fontSize ?: 30f,
            color = androidx.compose.ui.graphics.Color(color),
            strokeWidth = strokeWidth, layerId = layerId,
            horizontalOnly = horizontalOnly ?: false
        )
        "text" -> com.scheda.app.model.DrawingPrimitive.TextPrimitive(
            text = text ?: "",
            x = x ?: 0f, y = y ?: 0f,
            rotation = rotation ?: 0f,
            fontSize = fontSize ?: 40f,
            color = androidx.compose.ui.graphics.Color(color),
            strokeWidth = strokeWidth, layerId = layerId,
            horizontalOnly = horizontalOnly ?: false
        )
        "range" -> com.scheda.app.model.DrawingPrimitive.RangeLabelPrimitive(
            startValue = value ?: 1,
            endValue = endValue ?: (value ?: 1) + 1,
            x = x ?: 0f, y = y ?: 0f,
            rotation = rotation ?: 0f,
            fontSize = fontSize ?: 30f,
            arrowSpan = scale ?: 1f,
            reversed = reversed ?: false,
            color = androidx.compose.ui.graphics.Color(color),
            strokeWidth = strokeWidth, layerId = layerId,
            horizontalOnly = horizontalOnly ?: true
        )
        else -> null
    }
}

private fun SerializableLayer.toLayer() = Layer(
    id = id, name = name,
    color = androidx.compose.ui.graphics.Color(color),
    isVisible = isVisible, isLocked = isLocked
)
