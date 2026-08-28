package com.scheda.app.file

import com.scheda.app.export.DxfExporter
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider

import com.scheda.app.model.SerializableLayer
import com.scheda.app.model.SerializablePrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 分享工具 — 将 .scheda 转为 .dxf 后分享。
 * 调试模式：分享 .log.txt 以排查 DXF 导出问题。
 */
class ShareUtil(
    private val context: Context,
    private val serializer: SchedaSerializer
) {
    private val cacheDir = File(context.cacheDir, "shares").also { it.mkdirs() }
    private fun log(msg: String) { android.util.Log.d("ShareUtil", msg) }



    fun shareFile(file: File) {
        val json: String
        try {
            json = file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "读取文件失败", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val doc: com.scheda.app.model.SchedaDocument
        try {
            doc = com.google.gson.Gson().fromJson(json, com.scheda.app.model.SchedaDocument::class.java)
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "解析文件失败", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val primitives = doc.primitives?.mapNotNull { serializer.serializableToPrimitive(it) } ?: emptyList()
        val layers = doc.layers?.map { serializer.serializableToLayer(it) } ?: emptyList()
        val blockDefs = doc.blockDefs?.mapNotNull { serializer.serializableToBlockDef(it) } ?: emptyList()

        if (primitives.isEmpty()) {
            android.widget.Toast.makeText(context, "没有可导出的图元", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val dxfFile = File(cacheDir, file.name.replace(".scheda", "") + ".dxf")
        val res = DxfExporter.export(
            context, dxfFile.absolutePath,
            primitives, layers, blockDefs, doc.images ?: emptyList()
        )

        if (res.success && dxfFile.exists()) {
            // 含参考图片：DXF 的 IMAGE 是相对文件名引用，图片须与 DXF 同目录 → 打包 ZIP
            if (res.imageFiles.isEmpty()) sendDxf(dxfFile)
            else sendZip(listOf(dxfFile) + res.imageFiles)
        } else {
            val logFile = File(cacheDir, "dxf_error_log.txt")
            val sb = StringBuilder()
            sb.appendLine("DXF 导出失败")
            sb.appendLine("Debug Log:")
            sb.appendLine(DxfExporter.getLastError())
            sb.appendLine("Scheda file: ${file.absolutePath}")
            sb.appendLine("Primitives: ${primitives.size}")
            sb.appendLine("Layers: ${layers.size}")
            sb.appendLine("BlockDefs: ${blockDefs.size}")
            logFile.writeText(sb.toString())
            android.widget.Toast.makeText(context, "DXF 导出失败，已生成错误日志", android.widget.Toast.LENGTH_SHORT).show()
            // Share error log as debug file
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", logFile)
            context.startActivity(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }.let { Intent.createChooser(it, "分享错误日志") })
        }
    }

    fun shareMultipleFiles(files: List<File>) {
        val converted = files.mapNotNull { convertToDxf(it) }
        if (converted.isEmpty()) {
            android.util.Log.e("ShareUtil", "All DXF conversions failed: ${DxfExporter.getLastError()}")
            android.widget.Toast.makeText(context, "DXF 导出失败", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        // DXF + 各自的参考图片文件一起打包（图片须与 DXF 同目录）
        sendZip(converted.flatMap { (dxf, imgs) -> listOf(dxf) + imgs })
    }

    /** 返回 DXF 文件 + 随它生成的参考图片文件列表 */
    private fun convertToDxf(schedaFile: File): Pair<File, List<File>>? {
        val json: String
        try {
            json = schedaFile.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            log("readText 失败: ${e.message}")
            return null
        }
        log("JSON 读取成功: ${json.length} 字符")

        val doc: com.scheda.app.model.SchedaDocument
        try {
            doc = com.google.gson.Gson().fromJson(json, com.scheda.app.model.SchedaDocument::class.java)
        } catch (e: Exception) {
            log("Gson 解析失败: ${e.message}")
            return null
        }
        log("Gson 解析成功, version=${doc.version}")

        if (doc.primitives == null || doc.primitives.isEmpty()) {
            log("primitives 为空: ${doc.primitives?.size}")
            return null
        }
        log("primitives: ${doc.primitives.size} 个")

        val primitives = doc.primitives.mapNotNull {
            val p = serializer.serializableToPrimitive(it)
            if (p == null) log("  primitive 转换失败: type=${it.type}")
            p
        }
        log("有效 primitives: ${primitives.size} 个")

        if (primitives.isEmpty()) {
            log("所有 primitive 转换后都为空")
            return null
        }

        val layers = doc.layers.map { serializer.serializableToLayer(it) }
        val blockDefs = doc.blockDefs.mapNotNull { serializer.serializableToBlockDef(it) }
        log("layers: ${layers.size}, blockDefs: ${blockDefs.size}")

        val dxfFile = File(cacheDir, schedaFile.name.replace(".scheda", "") + ".dxf")
        return try {
            val res = DxfExporter.export(
                context, dxfFile.absolutePath,
                primitives, layers, blockDefs, doc.images ?: emptyList()
            )
            log("DXF 导出: ${if (res.success) "成功" else "失败"}: ${dxfFile.absolutePath}")
            if (res.success) dxfFile to res.imageFiles else null
        } catch (e: Exception) {
            log("DXF 导出异常: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    private fun sendDxf(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent(Intent.ACTION_SEND).apply {
            type = "application/dxf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
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
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }.let { Intent.createChooser(it, "分享图纸") })
        } catch (e: Exception) {
            android.util.Log.e("ShareUtil", "sendZip failed", e)
        }
    }
}
