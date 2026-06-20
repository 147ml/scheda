package com.scheda.app.viewmodel

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.scheda.app.file.ShareUtil
import com.scheda.app.file.SchedaSerializer
import com.scheda.app.file.StorageManager
import java.io.File

/**
 * 首页 ViewModel — 文件夹导航 + 文件浏览与分享。统一走 java.io.File。
 */
class HomeViewModel(
    private val storageManager: StorageManager,
    private val shareUtil: ShareUtil,
    private val serializer: SchedaSerializer
) : ViewModel() {

    // ═══════════════════════════════════════════════════════
    //  数据模型
    // ═══════════════════════════════════════════════════════

    /** 面包屑节点 */
    data class Crumb(val name: String, val dir: File)
    /** 首页文件条目 */
    data class Item(val file: File, val name: String, val isDir: Boolean, val size: Long = 0L, val mod: Long = 0L)

    // ═══════════════════════════════════════════════════════
    //  状态
    // ═══════════════════════════════════════════════════════

    private val _items = mutableStateListOf<Item>()
    val items: List<Item> get() = _items

    private val _crumbs = mutableStateListOf<Crumb>()
    val crumbs: List<Crumb> get() = _crumbs

    private val _curDir = mutableStateOf<File?>(null)
    val curDir: File? get() = _curDir.value

    private val _loading = mutableStateOf(false)
    val loading: Boolean get() = _loading.value

    private val _err = mutableStateOf<String?>(null)
    val err: String? get() = _err.value

    private val _title = mutableStateOf("")
    val title: String get() = _title.value

    /** 完整路径显示（crumbs拼接） */
    val displayPath: String get() = _crumbs.joinToString(" › ") { it.name }

    private val _share = mutableStateOf(false)
    val share: Boolean get() = _share.value

    val hasSel: Boolean get() = _sel.isNotEmpty()
    private val _sel = mutableStateListOf<File>()
    val sel: List<File> get() = _sel

    // ═══════════════════════════════════════════════════════
    //  导航
    // ═══════════════════════════════════════════════════════

    fun init() {
        val root = storageManager.getRoot() ?: return
        _crumbs.clear()
        _crumbs.add(Crumb(root.name ?: "根目录", root))
        _title.value = root.name ?: "根目录"
        load(root)
    }

    fun refresh() { load(_curDir.value ?: return) }

    private fun load(dir: File) {
        _curDir.value = dir
        _loading.value = true
        _items.clear()
        try {
            for (f in storageManager.listContents(dir)) {
                _items.add(Item(f,
                    name = f.name.removeSuffix(".scheda").ifBlank { f.name },
                    isDir = f.isDirectory,
                    size = if (!f.isDirectory) f.length() else 0L,
                    mod = f.lastModified()))
            }
            _loading.value = false
        } catch (e: Exception) { _err.value = "加载失败"; _loading.value = false }
    }

    fun enter(dir: File) {
        val name = dir.name ?: "?"
        _crumbs.add(Crumb(name, dir))
        _title.value = name
        clearSel()
        _share.value = false
        load(dir)
    }

    fun up() {
        if (_crumbs.size <= 1) return
        _crumbs.removeLast()
        val p = _crumbs.last().dir
        _title.value = _crumbs.last().name
        clearSel()
        _share.value = false
        load(p)
    }

    fun goto(idx: Int) {
        if (idx < 0 || idx >= _crumbs.size) return
        while (_crumbs.size > idx + 1) _crumbs.removeLast()
        load(_crumbs.last().dir)
    }

    // ═══════════════════════════════════════════════════════
    //  操作
    // ═══════════════════════════════════════════════════════

    fun newFolder(name: String): Boolean {
        if (name.isBlank()) return false
        val ok = File(_curDir.value, name.trim()).mkdirs()
        if (ok) refresh()
        return ok
    }

    fun delete(item: Item): Boolean {
        try {
            if (item.file.deleteRecursively()) {
                _sel.remove(item.file)
                refresh()
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    // ═══════════════════════════════════════════════════════
    //  分享
    // ═══════════════════════════════════════════════════════

    fun enterShare() { _share.value = true; _sel.clear() }
    fun exitShare() { _share.value = false; _sel.clear() }
    fun toggle(item: Item) { if (_sel.contains(item.file)) _sel.remove(item.file) else _sel.add(item.file) }
    fun isSel(item: Item) = _sel.contains(item.file)
    fun clearSel() { _sel.clear() }

    fun deleteSel() {
        for (f in _sel.toList()) { f.deleteRecursively() }
        _sel.clear()
        refresh()
    }

    fun doShare() {
        val fl = _sel.toList()
        if (fl.isEmpty()) return
        try {
            if (fl.size == 1) shareUtil.shareFile(fl.first())
            else shareUtil.shareMultipleFiles(fl)
            exitShare()
        } catch (_: Exception) {}
    }

    /** 将选中的文件/文件夹移动到目标目录 */
    fun moveItems(targetDir: File): Boolean {
        val fl = _sel.toList()
        if (fl.isEmpty()) return false
        try {
            for (f in fl) {
                val dest = File(targetDir, f.name)
                if (!f.renameTo(dest)) {
                    _err.value = "移动 ${f.name} 失败"
                    refresh()
                    return false
                }
            }
            _sel.clear()
            _share.value = false
            refresh()
            return true
        } catch (e: Exception) {
            _err.value = "移动失败: ${e.message}"
            refresh()
            return false
        }
    }

    fun clearErr() { _err.value = null }

    /** 根目录 */
    fun getRoot(): File? = storageManager.getRoot()
}
