package com.scheda.app.file

import com.scheda.app.model.BlockDef
import com.scheda.app.model.DrawingPrimitive
import com.scheda.app.model.Layer
import com.google.gson.GsonBuilder
import java.io.File

/**
 * 自动保存 — 直接写入 .scheda 文件，不生成 .recovery。
 */
class RecoveryManager(
    private val serializer: SchedaSerializer
) {
    private var currentFile: File? = null

    fun registerFile(file: File) {
        currentFile = file
    }

    fun autoSave(primitives: List<DrawingPrimitive>, layers: List<Layer>,
                 blockDefs: List<BlockDef>, activeLayerId: Int, name: String) {
        val file = currentFile ?: return
        val doc = serializer.toDocument(primitives, layers, blockDefs, activeLayerId, name)
        try {
            file.writeText(GsonBuilder().setPrettyPrinting().create().toJson(doc), Charsets.UTF_8)
        } catch (_: Exception) {}
    }
}
