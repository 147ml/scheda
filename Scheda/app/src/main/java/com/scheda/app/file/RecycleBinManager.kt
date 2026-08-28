package com.scheda.app.file

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * 回收站管理器 — 文件/文件夹删除后移到 .scheda_recycle/ 暂存，
 * 超过 MAX_ITEMS 条后自动滚动删除最旧的。
 *
 * 设计原则：
 * - 回收站目录隐藏在根目录下（.scheda_recycle/），对用户不可见（系统文件浏览器看不到）
 * - 每条记录存 originalPath（相对于根目录），复原时移回原位
 * - 重名保护：移入时加时间戳前缀，复原时原路径被占用则加 "(还原)" 后缀
 * - 容量上限按"条目"计数，文件夹算 1 条（跟文件一样），不计内部文件数
 */
class RecycleBinManager(private val storageManager: StorageManager) {

    companion object {
        private const val RECYCLE_DIR_NAME = ".scheda_recycle"
        private const val MANIFEST_FILE = "manifest.json"
        private const val MAX_ITEMS = 100
        private const val TAG = "RecycleBin"
    }

    private val gson = Gson()

    // ─── 数据模型 ─────────────────────────────────

    /** 回收站中一条已删除的条目 */
    data class RecycleItem(
        /** 还原目标路径（相对于根目录），如 "图纸/施工图" */
        val originalPath: String,
        /** 回收站内的存储名（含时间戳前缀），如 "1723456789_施工图" */
        val storedName: String,
        /** 显示用的原名 */
        val name: String,
        val isDir: Boolean,
        /** 删除时间戳（毫秒） */
        val deletedAt: Long,
        /** 文件大小（文件夹为 0） */
        val size: Long
    )

    // ─── 内部方法 ─────────────────────────────────

    private fun getRecycleDir(): File? {
        val root = storageManager.getRoot() ?: return null
        val dir = File(root, RECYCLE_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getManifestFile(): File? {
        val recycleDir = getRecycleDir() ?: return null
        return File(recycleDir, MANIFEST_FILE)
    }

    private fun loadManifest(): MutableList<RecycleItem> {
        val file = getManifestFile() ?: return mutableListOf()
        if (!file.exists()) return mutableListOf()
        return try {
            val text = file.readText(Charsets.UTF_8)
            val type = object : TypeToken<MutableList<RecycleItem>>() {}.type
            val list: MutableList<RecycleItem> = gson.fromJson(text, type) ?: mutableListOf()
            // 过滤掉文件已不存在的记录（异常情况：用户手动删了回收站文件）
            list.filterTo(mutableListOf()) { item ->
                val f = recycleFile(item.storedName)
                f?.exists() == true
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "读取回收站清单失败: ${e.message}")
            mutableListOf()
        }
    }

    private fun saveManifest(items: List<RecycleItem>) {
        val file = getManifestFile() ?: return
        try {
            file.writeText(gson.toJson(items), Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "保存回收站清单失败: ${e.message}")
        }
    }

    /** 回收站目录下对应 storedName 的文件 */
    private fun recycleFile(storedName: String): File? {
        val dir = getRecycleDir() ?: return null
        return File(dir, storedName)
    }

    /** 计算文件/文件夹在根目录下的相对路径 */
    private fun relativePath(file: File): String? {
        val root = storageManager.getRoot() ?: return null
        val rootPath = root.absolutePath.trimEnd('/') + "/"
        val filePath = file.absolutePath
        return if (filePath.startsWith(rootPath)) {
            filePath.removePrefix(rootPath)
        } else null
    }

    // ─── 公开操作 ─────────────────────────────────

    /**
     * 将文件或文件夹移入回收站。
     * 返回 true 表示成功移入，false 表示失败（文件已在回收站内等）。
     */
    fun moveToRecycle(file: File): Boolean {
        if (!file.exists()) return false
        // 防止把回收站本身移入回收站
        if (file.name == RECYCLE_DIR_NAME) return false
        val relPath = relativePath(file) ?: return false
        val recycleDir = getRecycleDir() ?: return false
        // 防冲突：毫秒戳+后缀，如果已有同名则加计数器
        val baseName = "${System.currentTimeMillis()}_${file.name}"
        var storedName = baseName
        var counter = 0
        while (File(recycleDir, storedName).exists()) {
            counter++
            storedName = "${baseName}_$counter"
        }

        // 同文件系统 renameTo 是 O(1) 移动而非复制
        val dest = File(recycleDir, storedName)
        if (!file.renameTo(dest)) {
            android.util.Log.e(TAG, "移入回收站失败: ${file.absolutePath}")
            return false
        }

        val items = loadManifest()
        items.add(RecycleItem(
            originalPath = relPath,
            storedName = storedName,
            name = file.name,
            isDir = file.isDirectory || dest.isDirectory,
            deletedAt = System.currentTimeMillis(),
            size = if (file.isDirectory) 0L else dest.length()
        ))

        // 先写 manifest，再裁剪；裁剪需要读 manifest
        saveManifest(items)
        pruneIfNeeded()
        return true
    }

    /**
     * 从回收站恢复到原位置。
     * 如果原位置已被占用，追加 "（还原）" 后缀。
     */
    fun restore(item: RecycleItem): Boolean {
        val src = recycleFile(item.storedName) ?: return false
        if (!src.exists()) return false
        val root = storageManager.getRoot() ?: return false
        var target = File(root, item.originalPath)

        // 原路径已被占用 → 加后缀
        if (target.exists()) {
            val baseName = item.name.removeSuffix(".scheda")
            val ext = if (item.name.endsWith(".scheda")) ".scheda" else ""
            var suffix = 1
            while (target.exists()) {
                target = File(root, "${baseName}（还原$suffix）$ext")
                suffix++
            }
        }

        target.parentFile?.mkdirs()
        if (!src.renameTo(target)) return false

        // 从 manifest 移除
        val items = loadManifest()
        items.removeAll { it.storedName == item.storedName }
        saveManifest(items)
        return true
    }

    /** 从回收站彻底删除（永久） */
    fun deletePermanently(item: RecycleItem): Boolean {
        val file = recycleFile(item.storedName) ?: return false
        val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
        if (ok) {
            val items = loadManifest()
            items.removeAll { it.storedName == item.storedName }
            saveManifest(items)
        }
        return ok
    }

    /** 清空回收站 */
    fun emptyBin(): Boolean {
        val dir = getRecycleDir() ?: return false
        val items = loadManifest()
        var allOk = true
        for (item in items) {
            val f = recycleFile(item.storedName)
            if (f?.exists() == true) {
                val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
                if (!ok) allOk = false
            }
        }
        // 清 manifest
        saveManifest(emptyList())
        return allOk
    }

    /** 获取回收站条目列表（按删除时间降序 = 最新的在前） */
    fun getItems(): List<RecycleItem> {
        return loadManifest().sortedByDescending { it.deletedAt }
    }

    /** 回收站条目数 */
    fun getItemCount(): Int = loadManifest().size

    // ─── 容量管理 ─────────────────────────────────

    /**
     * 检查条目数是否超过上限，是则删除最旧的。
     * 每次移入新文件后自动调用。
     */
    private fun pruneIfNeeded() {
        val items = loadManifest()
        if (items.size <= MAX_ITEMS) return

        // 按删除时间升序排列（最旧的在前）
        val sorted = items.sortedBy { it.deletedAt }
        val toRemove = sorted.size - MAX_ITEMS

        for (i in 0 until toRemove) {
            val item = sorted[i]
            val f = recycleFile(item.storedName)
            if (f?.exists() == true) {
                if (f.isDirectory) f.deleteRecursively() else f.delete()
            }
            items.removeAll { it.storedName == item.storedName }
        }
        saveManifest(items)
    }
}
