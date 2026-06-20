package com.scheda.app.file

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.scheda.app.model.SchedaDocument
import com.scheda.app.model.SerializableBlockDef
import java.io.File

/**
 * 文件系统管理 — MANAGE_EXTERNAL_STORAGE + java.io.File 单一后端。
 *
 * SAF 仅用于首次选目录（OpenDocumentTree），之后一切读写走 File API。
 * HyperOS 砍了 takePersistUriPermission，SAF 持久化不可用，所以不依赖它。
 */
class StorageManager(val context: Context) {

    companion object {
        private const val PREFS_NAME = "scheda_storage"
        private const val KEY_ROOT_PATH = "root_path"
        private const val TAG = "StorageManager"
    }

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ═══════════════════════════════════════════════════════
    //  状态
    // ═══════════════════════════════════════════════════════

    /** 用户选的根目录真实路径（/storage/emulated/0/Scheda） */
    val rootPath: String?
        get() = prefs.getString(KEY_ROOT_PATH, null)

    /** 全部文件访问权限 */
    val hasFullAccess: Boolean
        get() = Environment.isExternalStorageManager()

    /** App 是否可以正常读写文件 */
    val isAccessible: Boolean
        get() = hasFullAccess && rootPath != null && File(rootPath!!).run { exists() || mkdirs() }

    /** 是否已完成首次设置 */
    val isInitialized: Boolean
        get() = rootPath != null

    // ═══════════════════════════════════════════════════════
    //  首次设置：SAF 选目录 → 存路径
    // ═══════════════════════════════════════════════════════

    /**
     * 将 SAF tree URI 转成真实路径并持久化。
     * content://.../tree/primary%3AScheda → /storage/emulated/0/Scheda
     */
    fun setRootFromTreeUri(uri: Uri) {
        val path = treeUriToPath(uri)
        if (path != null) {
            prefs.edit().putString(KEY_ROOT_PATH, path).commit()
            File(path).mkdirs()
            android.util.Log.i(TAG, "根目录已设置: $path")
        } else {
            android.util.Log.e(TAG, "无法从 URI 解析路径: $uri")
        }
    }

    private fun treeUriToPath(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val parts = docId.split(":", limit = 2)
        if (parts.size < 2) return null
        return if (parts[0] == "primary") {
            "${Environment.getExternalStorageDirectory().absolutePath}/${parts[1]}"
        } else {
            "/storage/${parts[0]}/${parts[1]}"  // SD 卡
        }
    }

    /** 引导用户去系统设置开启全部文件访问权限 */
    fun openFullAccessSettings() {
        if (!hasFullAccess) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                context.startActivity(intent)
            } catch (e: Exception) {
                // fallback: try generic manage settings
                val intent = Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
                context.startActivity(intent)
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    //  文件操作（java.io.File）
    // ═══════════════════════════════════════════════════════

    fun getRoot(): File? = rootPath?.let { File(it) }

    /** 列出指定目录的直接子内容（文件 + 文件夹，不递归）。目录优先排序。隐藏 blocks/ 目录。 */
    fun listContents(dir: File): List<File> {
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { !(it.isDirectory && it.name == "blocks") }
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name })
    }

    /** 列出根目录及子目录下所有 .scheda 文件 */
    fun listSchedaFiles(): List<File> {
        val r = getRoot() ?: return emptyList()
        return collectSchedaFiles(r, depth = 2)
    }

    private fun collectSchedaFiles(dir: File, depth: Int): List<File> {
        if (depth < 0) return emptyList()
        val result = mutableListOf<File>()
        val files = dir.listFiles() ?: return result
        for (f in files.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name })) {
            if (f.isDirectory) {
                result.addAll(collectSchedaFiles(f, depth - 1))
            } else if (f.name.endsWith(".scheda")) {
                result.add(f)
            }
        }
        return result
    }

    /** 加载文档 */
    fun loadDocument(name: String): SchedaDocument? {
        val r = getRoot() ?: return null
        val file = findFile(r, name) ?: return null
        return try {
            gson.fromJson(file.readText(Charsets.UTF_8), SchedaDocument::class.java)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "加载文件失败: ${e.message}")
            null
        }
    }

    /** 直接从 File 加载文档 */
    fun loadDocument(file: File): SchedaDocument? {
        return try {
            gson.fromJson(file.readText(Charsets.UTF_8), SchedaDocument::class.java)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "加载文件失败: ${e.message}")
            null
        }
    }

    /** 保存文档（根目录下） */
    fun saveDocument(name: String, doc: SchedaDocument): Boolean {
        val r = getRoot() ?: return false
        val fileName = if (name.endsWith(".scheda")) name else "$name.scheda"
        val file = File(r, fileName)
        return saveToFile(file, doc)
    }

    /** 保存到指定文件 */
    fun saveToFile(file: File, doc: SchedaDocument): Boolean {
        return try {
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(doc), Charsets.UTF_8)
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "保存文件失败: ${e.message}")
            false
        }
    }

    /** 在根目录下删除文件 */
    fun deleteFile(name: String): Boolean {
        val r = getRoot() ?: return false
        val file = findFile(r, name) ?: return false
        return file.delete()
    }

    /** 创建子文件夹 */
    fun createFolder(name: String): Boolean {
        val r = getRoot() ?: return false
        return File(r, name).mkdirs()
    }

    /** 在根目录下创建新的 .scheda 文件，如果已存在返回 null */
    fun createDocument(name: String): File? {
        val root = getRoot() ?: return null
        return createDocumentIn(name, root)
    }

    /** 在指定目录下创建 .scheda 文件（已存在时返回 null 不覆盖） */
    fun createDocumentIn(name: String, parent: File): File? {
        val fileName = if (name.endsWith(".scheda")) name else "$name.scheda"
        val file = File(parent, fileName)
        if (file.exists()) {
            android.util.Log.w(TAG, "文件已存在: $fileName — 跳过创建")
            return null
        }
        return try {
            file.parentFile?.mkdirs()
            file.createNewFile()
            file
        } catch (e: Exception) {
            android.util.Log.e(TAG, "创建文件失败: ${e.message}")
            null
        }
    }

    // ═══════════════════════════════════════════════════════
    //  块文件 (.block)
    // ═══════════════════════════════════════════════════════

    fun getBlocksDir(): File? {
        val root = getRoot() ?: return null
        val dir = File(root, "blocks")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun saveBlockFile(bd: SerializableBlockDef): Boolean {
        val dir = getBlocksDir() ?: return false
        val file = File(dir, "${bd.id}.block")
        return try {
            file.writeText(gson.toJson(bd), Charsets.UTF_8)
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "保存块文件失败: ${e.message}")
            false
        }
    }

    fun deleteBlockFile(blockId: String): Boolean {
        val dir = getBlocksDir() ?: return false
        val file = File(dir, "${blockId}.block")
        return file.exists() && file.delete()
    }

    fun loadAllBlockFiles(): List<SerializableBlockDef> {
        val dir = getBlocksDir() ?: return emptyList()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".block") } ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                gson.fromJson(f.readText(Charsets.UTF_8), SerializableBlockDef::class.java)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "加载块文件失败 ${f.name}: ${e.message}")
                null
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    //  helper
    // ═══════════════════════════════════════════════════════

    private fun findFile(dir: File, name: String): File? {
        val fileName = if (name.endsWith(".scheda")) name else "$name.scheda"
        val direct = File(dir, fileName)
        if (direct.exists()) return direct
        return searchDir(dir, fileName)
    }

    private fun searchDir(dir: File, fileName: String): File? {
        val files = dir.listFiles() ?: return null
        for (f in files) {
            if (f.isDirectory) {
                val found = searchDir(f, fileName)
                if (found != null) return found
            } else if (f.name == fileName) {
                return f
            }
        }
        return null
    }
}
