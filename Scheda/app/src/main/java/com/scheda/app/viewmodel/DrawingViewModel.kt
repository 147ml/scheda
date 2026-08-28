package com.scheda.app.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.scheda.app.export.DxfExporter
import com.scheda.app.file.DocumentData
import com.scheda.app.file.RecoveryManager
import com.scheda.app.file.SchedaSerializer
import com.scheda.app.file.StorageManager
import com.scheda.app.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class DrawingViewModel(
    private val storageManager: StorageManager,
    private val serializer: SchedaSerializer,
    private val recoveryManager: RecoveryManager,
    private val appContext: Context
) : ViewModel() {

    companion object {
        private const val PREFS_SESSION = "scheda_session"
        private const val KEY_TOOL = "tool"
        private const val KEY_COLOR = "color"
        private const val KEY_STROKE = "stroke_width"
        private const val KEY_LINE_TYPE = "line_type"
        private const val KEY_LINE_DASH = "line_dash"
        private const val KEY_LINE_GAP = "line_gap"
        private const val KEY_GLOBAL_SCALE = "global_scale"
        private const val KEY_ERASER_R = "eraser_radius"
        private const val KEY_LAST_FILE = "last_doc_path"
        private const val KEY_CANVAS_SCALE = "canvas_scale"
        private const val KEY_CANVAS_OX = "canvas_ox"
        private const val KEY_CANVAS_OY = "canvas_oy"
        private const val KEY_TEXT_FS = "text_font_size"
        private const val KEY_NUM_FS = "num_font_size"
        private const val KEY_NUM_START = "num_start"
        private const val KEY_PENDING_TEXT = "pending_text"
        private const val KEY_CONSTRAINT = "constraint"
        private const val KEY_SNAP = "snap"
        private const val KEY_TEXT_HORIZONTAL = "text_horizontal"
        private const val KEY_RANGE_ARROW_SPAN = "range_arrow_span"
        private const val KEY_RANGE_FS = "range_font_size"
        private const val KEY_RANGE_HORIZONTAL = "range_horizontal_only"
        private const val KEY_RANGE_REVERSED = "range_reversed"
        private const val KEY_RANGE_NUMBERS_FACE_LEFT = "range_numbers_face_left"
        private const val KEY_QUICK_ERASE = "quick_erase"
        private const val KEY_FINE_ERASE = "fine_erase"
        private const val KEY_RECT_SQUARE = "rect_square"
        private const val KEY_CIRCLE_MODE = "circle_mode"
        private const val KEY_LINE_SNAP = "line_snap"
        private const val KEY_NUM_HORIZONTAL = "num_horizontal_only"
        private const val KEY_NUM_CIRCLED = "num_circled"
        private const val KEY_LANDSCAPE = "landscape_mode"
        private const val KEY_NARR_START = "num_array_start"
        private const val KEY_NARR_END = "num_array_end"
        private const val KEY_NARR_FS = "num_array_font_size"
        private const val KEY_NARR_GAP = "num_array_gap"
        private const val KEY_NARR_VERTICAL = "num_array_vertical" // 旧版竖向标记（仅兼容读取）
        private const val KEY_NARR_ROTATION = "num_array_rotation"
        private const val KEY_NARR_CIRCLED = "num_array_circled"
        const val MAX_UNDO_HISTORY = 50
    }

    private val sessionPrefs = appContext.getSharedPreferences(PREFS_SESSION, Context.MODE_PRIVATE)

    // ─── Reusable Paint for computeBounds (avoids per-call allocation) ──
    private val boundsMeasurePaint = Paint().apply {
        typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true; textAlign = Paint.Align.CENTER
    }

    // ─── 绘图基元 ──────────────────────────────────────────
    private val _primitives = mutableStateListOf<DrawingPrimitive>()
    val primitives: List<DrawingPrimitive> get() = _primitives

    // ─── 当前正在绘制的基元 ────────────────────────────────
    private val _currentPrimitive = mutableStateOf<DrawingPrimitive?>(null)
    val currentPrimitive: DrawingPrimitive? get() = _currentPrimitive.value

    // ─── 松手编辑 ─────────────────────────────────────────
    private val _pendingEdit = mutableStateOf(PendingEdit())
    val pendingEdit: PendingEdit get() = _pendingEdit.value

    // ─── 选择状态 ──────────────────────────────────────────
    private val _selection = mutableStateOf(SelectionState())
    val selection: SelectionState get() = _selection.value

    // ─── 剪贴板 ────────────────────────────────────────────
    private val _clipboard = mutableStateListOf<DrawingPrimitive>()
    val clipboard: List<DrawingPrimitive> get() = _clipboard.toList()

    // ─── 画布视口变换（缩放 + 平移）─────────────────────
    private val _canvasScale = mutableStateOf(1f)
    val canvasScale: Float get() = _canvasScale.value

    private val _canvasOffsetX = mutableStateOf(0f)
    val canvasOffsetX: Float get() = _canvasOffsetX.value

    private val _canvasOffsetY = mutableStateOf(0f)
    val canvasOffsetY: Float get() = _canvasOffsetY.value

    // ─── 横屏模式：UI 布局不变，画布内容与交互逻辑整体旋转 90° ──
    private val _isLandscape = mutableStateOf(false)
    val isLandscape: Boolean get() = _isLandscape.value

    fun toggleOrientation() {
        _isLandscape.value = !_isLandscape.value
        // 只更新工具状态（新建区间数字时继承此值），不修改画布上已确认的区间数字。
        _rangeLabel.value = _rangeLabel.value.copy(numbersFaceLeft = _isLandscape.value)
        // 待确认中的区间数字同步更新朝向
        val pe = _pendingEdit.value
        if (pe.isActive() && pe.primitive is DrawingPrimitive.RangeLabelPrimitive) {
            val p = pe.primitive as DrawingPrimitive.RangeLabelPrimitive
            _pendingEdit.value = pe.copy(primitive = p.copy(numbersFaceLeft = _isLandscape.value))
        }
        saveSettings()
    }

    // ─── 工具状态 ─────────────────────────────────────────
    private val _currentTool = mutableStateOf(ToolType.FREEHAND)
    val currentTool: ToolType get() = _currentTool.value

    private val _currentColor = mutableStateOf(Color.Black)
    val currentColor: Color get() = _currentColor.value

    private val _currentStrokeWidth = mutableFloatStateOf(5f)
    val strokeWidth: Float get() = _currentStrokeWidth.value

    private val _currentLineStyle = mutableStateOf(LineStyle())
    val currentLineStyle: LineStyle get() = _currentLineStyle.value

    private val _globalLineScale = mutableFloatStateOf(1f)
    val globalLineScale: Float get() = _globalLineScale.value

    // ─── 约束/吸附 ────────────────────────────────────────
    private val _constraintEnabled = mutableStateOf(false)
    val constraintEnabled: Boolean get() = _constraintEnabled.value

    private val _snapEnabled = mutableStateOf(false)
    val snapEnabled: Boolean get() = _snapEnabled.value

    // ─── 图层 ─────────────────────────────────────────────
    private val _layers = mutableStateListOf(Layer(id = 0, name = "图层0"))
    val layers: List<Layer> get() = _layers

    private val _activeLayerId = mutableIntStateOf(0)
    val activeLayerId: Int get() = _activeLayerId.value

    // ─── 参考图片（画布最底层底图；不参与选择/橡皮，仅经管理面板调整）───
    private val _images = mutableStateListOf<ReferenceImage>()
    val images: List<ReferenceImage> get() = _images

    /** id → 解码位图。Compose 可观察：后台解码完成后触发画布重绘 */
    val imageBitmaps = mutableStateMapOf<String, Bitmap>()

    // 图片管理模式：管理面板悬浮于画布之上，画布与已有图形始终可见
    private val _imageManageActive = mutableStateOf(false)
    val imageManageActive: Boolean get() = _imageManageActive.value

    private val _imgSelectedId = mutableStateOf<String?>(null)
    val imgSelectedId: String? get() = _imgSelectedId.value

    val selectedImage: ReferenceImage? get() = _images.firstOrNull { it.id == _imgSelectedId.value }

    fun enterImageManage() { _imageManageActive.value = true }

    fun exitImageManage() {
        if (_imgSelectedId.value != null) { _imgSelectedId.value = null; autoSave() }
        _imageManageActive.value = false
    }

    /** 选中图片进入调整（画布上出现 PFO 框）；undo 记录到选中前，一次撤销回滚整段调整 */
    fun selectImage(id: String) {
        if (_imgSelectedId.value == id) { deselectImage(); return }
        _imgSelectedId.value = id
        pushUndo()
    }

    fun deselectImage() {
        if (_imgSelectedId.value != null) { _imgSelectedId.value = null; autoSave() }
    }

    private fun updateSelectedImage(update: (ReferenceImage) -> ReferenceImage) {
        val id = _imgSelectedId.value ?: return
        val idx = _images.indexOfFirst { it.id == id }
        if (idx >= 0) _images[idx] = update(_images[idx])
    }

    fun moveSelectedImage(dx: Float, dy: Float) =
        updateSelectedImage { it.copy(centerX = it.centerX + dx, centerY = it.centerY + dy) }

    /** 等比缩放（保持宽高比；夹紧下限防止拖没） */
    fun scaleSelectedImage(factor: Float) = updateSelectedImage {
        val minDim = min(it.width, it.height)
        val f = factor.coerceIn(20f / minDim, 100f)
        it.copy(width = it.width * f, height = it.height * f)
    }

    /** 旋转（90° 吸附：距最近直角 ≤5° 时吸住，形成档位感，方便扫描图对齐坐标轴） */
    fun rotateSelectedImage(rad: Float) = updateSelectedImage {
        val deg = ((it.rotationDeg + Math.toDegrees(rad.toDouble()).toFloat()) % 360f + 360f) % 360f
        val nearestRaw = (deg / 90f).roundToInt() * 90  // 允许得 360（等价 0），处理跨界
        it.copy(rotationDeg = if (abs(deg - nearestRaw) <= 5f) (nearestRaw % 360).toFloat() else deg)
    }

    fun setSelectedImageAlpha(a: Float) =
        updateSelectedImage { it.copy(alpha = a.coerceIn(0.05f, 1f)) }

    /** 把选中图片移到指定图层（图片仍恒垫底，仅随图层显隐/导出；计入选中会话的一次 undo） */
    fun moveSelectedImageToLayer(layerId: Int) =
        updateSelectedImage { it.copy(layerId = layerId) }

    fun deleteImage(id: String) {
        val idx = _images.indexOfFirst { it.id == id }
        if (idx < 0) return
        pushUndo()
        if (_imgSelectedId.value == id) _imgSelectedId.value = null
        imageBitmaps.remove(id)
        _images.removeAt(idx)
        autoSave()
    }

    /** 系统图库选择器回调：后台解码（降采样 + EXIF 转正 + JPEG Base64），插入即进入调整 */
    fun importReferenceImage(uri: Uri) {
        viewModelScope.launch {
            val decoded = withContext(Dispatchers.IO) { decodeImageForImport(uri) }
            if (decoded == null) {
                android.widget.Toast.makeText(appContext, "图片读取失败", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            val (bmp, base64) = decoded
            // 初始摆放：当前视口中心、宽取视口世界宽 60%（保宽高比）
            val dm = appContext.resources.displayMetrics
            val worldW = (dm.widthPixels / _canvasScale.value) * 0.6f
            val worldH = worldW * bmp.height / bmp.width
            val img = ReferenceImage(
                id = UUID.randomUUID().toString(),
                data = base64,
                centerX = (dm.widthPixels / 2f - _canvasOffsetX.value) / _canvasScale.value,
                centerY = (dm.heightPixels / 2f - _canvasOffsetY.value) / _canvasScale.value,
                width = worldW, height = worldH,
                layerId = _activeLayerId.value,
                pixelWidth = bmp.width, pixelHeight = bmp.height
            )
            pushUndo()
            _images.add(img)
            imageBitmaps[img.id] = bmp
            // 插入即调：自动进入管理模式并选中新图（PFO 框就位，画布上立即可见）
            _imageManageActive.value = true
            _imgSelectedId.value = img.id
            autoSave()
        }
    }

    /** 后台解码：降采样至长边 ≤2048，按 EXIF 方向转正，重编码 JPEG(q85) Base64 */
    private fun decodeImageForImport(uri: Uri): Pair<Bitmap, String>? {
        return try {
            val resolver = appContext.contentResolver
            // 1) 只读尺寸
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            // 2) 降采样解码
            var sample = 1
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxDim / sample > 2048) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
            // 3) EXIF 方向转正
            val orientation = resolver.openInputStream(uri)?.use {
                android.media.ExifInterface(it).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL
                )
            } ?: android.media.ExifInterface.ORIENTATION_NORMAL
            val deg = when (orientation) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (deg != 0f) {
                val m = Matrix().apply { postRotate(deg) }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (rotated != bmp) bmp.recycle()
                bmp = rotated
            }
            // 4) JPEG Base64
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            bmp to Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT)
        } catch (e: Exception) {
            android.util.Log.e("RefImage", "decode failed", e)
            null
        }
    }

    /** 文档加载后为图片后台解码位图；写缓存完成自动触发画布重绘 */
    private fun decodeImageBitmapsAsync(images: List<ReferenceImage>) {
        viewModelScope.launch(Dispatchers.Default) {
            for (img in images) {
                if (imageBitmaps.containsKey(img.id)) continue
                try {
                    val bytes = Base64.decode(img.data, Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                    withContext(Dispatchers.Main) { imageBitmaps[img.id] = bmp }
                } catch (e: Exception) {
                    android.util.Log.e("RefImage", "decode base64 failed: ${img.id}", e)
                }
            }
        }
    }

    // ─── 撤销/重做 ───────────────────────────────────────
    private data class UndoSnapshot(
        val primitives: List<DrawingPrimitive>,
        val layers: List<Layer>,
        val blockDefs: List<BlockDef>,
        val canvasOffsetX: Float,
        val canvasOffsetY: Float,
        val canvasScale: Float,
        val activeLayerId: Int,
        val lastTextFontSize: Float,
        val lastNumberFontSize: Float,
        val numberLabel: NumberLabel,
        val selectedIndices: Set<Int> = emptySet(),
        val selectionRotation: Float = 0f,
        val images: List<ReferenceImage> = emptyList()
    )

    private fun takeSnapshot() = UndoSnapshot(
        primitives = _primitives.toList(),
        layers = _layers.toList(),
        blockDefs = _blockDefs.toList(),
        canvasOffsetX = _canvasOffsetX.value,
        canvasOffsetY = _canvasOffsetY.value,
        canvasScale = _canvasScale.value,
        activeLayerId = _activeLayerId.value,
        lastTextFontSize = _lastTextFontSize,
        lastNumberFontSize = _lastNumberFontSize,
        numberLabel = _numberLabel.value,
        selectedIndices = _selection.value.selectedIndices,
        selectionRotation = _selection.value.rotation,
        images = _images.toList()
    )

    private fun restoreSnapshot(s: UndoSnapshot) {
        _primitives.clear(); _primitives.addAll(s.primitives)
        _layers.clear(); _layers.addAll(s.layers)
        _blockDefs.clear(); _blockDefs.addAll(s.blockDefs)
        _images.clear(); _images.addAll(s.images)
        // 撤销删除等操作可能恢复出缓存中已清除的位图 → 重新解码（已缓存的自动跳过）
        if (s.images.isNotEmpty()) decodeImageBitmapsAsync(s.images)
        // 撤销后图片可能已不存在 → 清掉悬空的选中态
        if (_imgSelectedId.value != null && _images.none { it.id == _imgSelectedId.value }) {
            _imgSelectedId.value = null
        }
        _canvasOffsetX.value = s.canvasOffsetX
        _canvasOffsetY.value = s.canvasOffsetY
        _canvasScale.value = s.canvasScale
        _activeLayerId.value = s.activeLayerId
        _lastTextFontSize = s.lastTextFontSize
        _lastNumberFontSize = s.lastNumberFontSize
        _numberLabel.value = s.numberLabel
        // Restore selection if indices are still valid
        val validIndices = s.selectedIndices.filter { it < _primitives.size }.toSet()
        if (validIndices.isNotEmpty()) {
            _selection.value = SelectionState(
                selectedIndices = validIndices,
                bounds = computeSelectionBounds(validIndices),
                rotation = s.selectionRotation
            )
        } else {
            _selection.value = SelectionState()
        }
    }

    private fun pushUndo() {
        undoHistory.add(takeSnapshot())
        if (undoHistory.size > MAX_UNDO_HISTORY) {
            undoHistory.removeAt(0)
        }
        _canUndo.value = undoHistory.isNotEmpty()
        redoHistory.clear()
        _canRedo.value = false
    }

    // ─── 撤销历史持久化（随 .scheda 图纸文件保存，重开后仍可撤销）───
    // 增量池格式：相邻快照共享绝大多数对象，全历史只序列化一份图纸数据 + 索引数组。
    // 池按引用去重（IdentityHashMap），与内存中快照共享同一批实例的语义一致。
    private fun packUndoHistory(): com.scheda.app.model.SerializableUndoHistory {
        val primIds = java.util.IdentityHashMap<DrawingPrimitive, Int>()
        val layerIds = java.util.IdentityHashMap<Layer, Int>()
        val blockDefIds = java.util.IdentityHashMap<BlockDef, Int>()
        val imageIds = java.util.IdentityHashMap<ReferenceImage, Int>()
        val primPool = ArrayList<com.scheda.app.model.SerializablePrimitive>()
        val layerPool = ArrayList<com.scheda.app.model.SerializableLayer>()
        val blockDefPool = ArrayList<com.scheda.app.model.SerializableBlockDef>()
        val imagePool = ArrayList<ReferenceImage>()

        fun primRef(p: DrawingPrimitive): Int = primIds[p] ?: run {
            primPool.add(serializer.primitiveToSerializable(p))
            (primPool.size - 1).also { primIds[p] = it }
        }
        fun layerRef(l: Layer): Int = layerIds[l] ?: run {
            layerPool.add(serializer.layerToSerializable(l))
            (layerPool.size - 1).also { layerIds[l] = it }
        }
        fun blockDefRef(b: BlockDef): Int = blockDefIds[b] ?: run {
            blockDefPool.add(serializer.blockDefToSerializable(b))
            (blockDefPool.size - 1).also { blockDefIds[b] = it }
        }
        fun imageRef(i: ReferenceImage): Int = imageIds[i] ?: run {
            imagePool.add(i)
            (imagePool.size - 1).also { imageIds[i] = it }
        }

        val snapshots = undoHistory.map { s ->
            com.scheda.app.model.SerializableUndoSnapshotRef(
                primitiveRefs = s.primitives.map { primRef(it) },
                layerRefs = s.layers.map { layerRef(it) },
                blockDefRefs = s.blockDefs.map { blockDefRef(it) },
                imageRefs = s.images.map { imageRef(it) },
                activeLayerId = s.activeLayerId,
                canvasOffsetX = s.canvasOffsetX,
                canvasOffsetY = s.canvasOffsetY,
                canvasScale = s.canvasScale,
                lastTextFontSize = s.lastTextFontSize,
                lastNumberFontSize = s.lastNumberFontSize,
                numberLabel = s.numberLabel,
                selectedIndices = s.selectedIndices,
                selectionRotation = s.selectionRotation
            )
        }
        return com.scheda.app.model.SerializableUndoHistory(
            primitivePool = primPool,
            layerPool = layerPool,
            blockDefPool = blockDefPool,
            imagePool = imagePool,
            snapshots = snapshots
        )
    }

    /** 解包增量格式：池条目只转换一次，恢复出的快照与内存语义一致（共享实例） */
    private fun unpackUndoHistory(h: com.scheda.app.model.SerializableUndoHistory): List<UndoSnapshot> {
        // 基元转换可能失败（未知类型），保留 null 占位使索引不偏移，引用处再跳过
        val primPool: List<DrawingPrimitive?> = h.primitivePool.map { serializer.serializableToPrimitive(it) }
        val layerPool = h.layerPool.map { serializer.serializableToLayer(it) }
        val blockDefPool = h.blockDefPool.map { serializer.serializableToBlockDef(it) }
        return h.snapshots.map { s ->
            UndoSnapshot(
                primitives = s.primitiveRefs.mapNotNull { primPool.getOrNull(it) },
                layers = s.layerRefs.mapNotNull { layerPool.getOrNull(it) },
                blockDefs = s.blockDefRefs.mapNotNull { blockDefPool.getOrNull(it) },
                canvasOffsetX = s.canvasOffsetX,
                canvasOffsetY = s.canvasOffsetY,
                canvasScale = s.canvasScale,
                activeLayerId = s.activeLayerId,
                lastTextFontSize = s.lastTextFontSize,
                lastNumberFontSize = s.lastNumberFontSize,
                numberLabel = s.numberLabel ?: NumberLabel(),
                selectedIndices = s.selectedIndices,
                selectionRotation = s.selectionRotation,
                images = s.imageRefs.mapNotNull { h.imagePool.getOrNull(it) }
            )
        }
    }

    private fun serializableToUndoSnapshot(s: com.scheda.app.model.SerializableUndoSnapshot) = UndoSnapshot(
        primitives = s.primitives.mapNotNull { serializer.serializableToPrimitive(it) },
        layers = s.layers.map { serializer.serializableToLayer(it) },
        blockDefs = s.blockDefs.map { serializer.serializableToBlockDef(it) },
        canvasOffsetX = s.canvasOffsetX,
        canvasOffsetY = s.canvasOffsetY,
        canvasScale = s.canvasScale,
        activeLayerId = s.activeLayerId,
        lastTextFontSize = s.lastTextFontSize,
        lastNumberFontSize = s.lastNumberFontSize,
        numberLabel = s.numberLabel ?: NumberLabel(),
        selectedIndices = s.selectedIndices,
        selectionRotation = s.selectionRotation
    )

    /** 从文件恢复撤销历史（旧版文件无此字段则跳过；优先新增量格式，兼容旧全量格式） */
    private fun restoreUndoHistory(
        hist: List<com.scheda.app.model.SerializableUndoSnapshot>?,
        histV2: com.scheda.app.model.SerializableUndoHistory? = null
    ) {
        val snapshots = when {
            histV2 != null -> unpackUndoHistory(histV2)
            !hist.isNullOrEmpty() -> hist.map { serializableToUndoSnapshot(it) }
            else -> return
        }
        if (snapshots.isEmpty()) return
        undoHistory.clear()
        for (s in snapshots.takeLast(MAX_UNDO_HISTORY)) undoHistory.add(s)
        _canUndo.value = undoHistory.isNotEmpty()
    }

    private val undoHistory = mutableListOf<UndoSnapshot>()
    private val redoHistory = mutableListOf<UndoSnapshot>()
    private val _canUndo = mutableStateOf(false)
    val canUndo: Boolean get() = _canUndo.value
    private val _canRedo = mutableStateOf(false)
    val canRedo: Boolean get() = _canRedo.value

    // ─── 文字工具 ─────────────────────────────────────────
    private val _pendingTextContent = mutableStateOf("")
    val pendingTextContent: String get() = _pendingTextContent.value

    private val _previousTool = mutableStateOf(ToolType.FREEHAND)
    val previousTool: ToolType get() = _previousTool.value

    // ─── 标注数字 ─────────────────────────────────────────
    private val _numberLabel = mutableStateOf(NumberLabel())
    val numberLabel: NumberLabel get() = _numberLabel.value
    private val _rangeLabel = mutableStateOf(RangeLabel())
    val rangeLabel: RangeLabel get() = _rangeLabel.value

    // ─── 数字阵列 ─────────────────────────────────────────
    private val _numArrayLabel = mutableStateOf(NumArrayLabel())
    val numArrayLabel: NumArrayLabel get() = _numArrayLabel.value
    /** 数字阵列编辑模式（已生成未确认，手柄/工具栏可调） */
    private val _numArrayActive = mutableStateOf(false)
    val numArrayActive: Boolean get() = _numArrayActive.value
    /** 本组生成数字在 _primitives 中的索引（生成后不再变动，编辑=整组重建） */
    private var numArrayIndices: List<Int> = emptyList()
    var numArrayBaseX = 0f
        private set
    var numArrayBaseY = 0f
        private set

    // ─── 文档级工具预设（从 .scheda 读写）────────────
    var docDefaultArrowSpan: Float = 1f

    // ─── 橡皮擦 ───────────────────────────────────────────
    private val _eraserRadius = mutableFloatStateOf(200f)   // 内部值=显示值×10，默认显示20
    val eraserRadius: Float get() = _eraserRadius.value
    val displayEraserRadius: Float get() = _eraserRadius.value / 10f
    private val _eraserTouchPoint = mutableStateOf<Point2D?>(null)
    val eraserTouchPoint: Point2D? get() = _eraserTouchPoint.value

    // ─── 长按临时橡皮擦 ───────────────────────────────────
    private val _quickEraseEnabled = mutableStateOf(true)
    val quickEraseEnabled: Boolean get() = _quickEraseEnabled.value
    private val _isTemporaryEraser = mutableStateOf(false)
    val isTemporaryEraser: Boolean get() = _isTemporaryEraser.value
    private var _previousToolBeforeEraser: ToolType = ToolType.FREEHAND
    private var _eraserUndoPushed = false
    // 橡皮擦落下点：落下时不立即擦除，等移动（拖动擦）或抬起（点按擦）再执行，
    // 避免双指缩放的第一触点触发"擦除→快照→取消→撤销→存盘"的卡顿链
    private var eraserDownPoint: Point2D? = null
    private var _rectMidpointActive = false
    private val _fineEraseEnabled = mutableStateOf(true)
    val fineEraseEnabled: Boolean get() = _fineEraseEnabled.value

    fun toggleQuickEraseEnabled() { _quickEraseEnabled.value = !_quickEraseEnabled.value }
    fun toggleFineEraseEnabled() { _fineEraseEnabled.value = !_fineEraseEnabled.value }

    // ─── 矩形/圆形/直线标准模式 ──────────────────
    private val _rectangleSquareMode = mutableStateOf(false)
    val rectangleSquareMode: Boolean get() = _rectangleSquareMode.value
    private val _circleCircleMode = mutableStateOf(false)
    val circleCircleMode: Boolean get() = _circleCircleMode.value
    private val _lineSnapMode = mutableStateOf(false)
    val lineSnapMode: Boolean get() = _lineSnapMode.value

    fun toggleRectangleSquareMode() { _rectangleSquareMode.value = !_rectangleSquareMode.value }
    fun toggleCircleCircleMode() { _circleCircleMode.value = !_circleCircleMode.value }
    fun toggleLineSnapMode() { _lineSnapMode.value = !_lineSnapMode.value }

    fun enterTemporaryEraser() {
        if (!_quickEraseEnabled.value) return
        if (_currentTool.value == ToolType.ERASER) return
        // 有待确认图形时不激活临时橡皮擦
        if (_pendingEdit.value.isActive()) return
        // 数字阵列编辑中不激活（避免擦掉组成员破坏整组重建的索引假设）
        if (_numArrayActive.value) return
        _isTemporaryEraser.value = true
        _previousToolBeforeEraser = _currentTool.value
        // 取消当前正在画的基元
        if (_currentPrimitive.value != null) {
            _currentPrimitive.value = null
        }
        _currentTool.value = ToolType.ERASER
    }

    fun exitTemporaryEraser() {
        if (!_isTemporaryEraser.value) return
        _isTemporaryEraser.value = false
        _currentTool.value = _previousToolBeforeEraser
    }

    // ─── 图块 ─────────────────────────────────────────────
    private val _blockDefs = mutableStateListOf<BlockDef>()
    val blockDefs: List<BlockDef> get() = _blockDefs

    private val _blockDraft = mutableStateOf<BlockDraft?>(null)
    val blockDraft: BlockDraft? get() = _blockDraft.value

    // ─── 块编辑器 ─────────────────────────────────────────
    private val _blockEditorPrimitives = mutableStateListOf<DrawingPrimitive>()
    val blockEditorPrimitives: List<DrawingPrimitive> get() = _blockEditorPrimitives
    private val _blockEditorCurrent = mutableStateOf<DrawingPrimitive?>(null)
    val blockEditorCurrent: DrawingPrimitive? get() = _blockEditorCurrent.value
    private val _blockEditorPendingEdit = mutableStateOf(PendingEdit())
    val blockEditorPendingEdit: PendingEdit get() = _blockEditorPendingEdit.value
    private val _blockEditorSelectedIndex = mutableStateOf(-1)
    val blockEditorSelectedIndex: Int get() = _blockEditorSelectedIndex.value
    private val _editingBlockId = mutableStateOf<String?>(null)
    val editingBlockId: String? get() = _editingBlockId.value
    private val _blockEditorViewScale = mutableFloatStateOf(1f)
    val blockEditorViewScale: Float get() = _blockEditorViewScale.value
    private val _blockEditorViewX = mutableFloatStateOf(0f)
    val blockEditorViewX: Float get() = _blockEditorViewX.value
    private val _blockEditorViewY = mutableFloatStateOf(0f)
    val blockEditorViewY: Float get() = _blockEditorViewY.value
    private val _blockEditorUndoHistory = mutableListOf<List<DrawingPrimitive>>()
    private val _blockEditorRedoHistory = mutableListOf<List<DrawingPrimitive>>()
    private val _canBlockEditorUndo = mutableStateOf(false)
    val canBlockEditorUndo: Boolean get() = _canBlockEditorUndo.value
    private val _canBlockEditorRedo = mutableStateOf(false)
    val canBlockEditorRedo: Boolean get() = _canBlockEditorRedo.value

    // ─── 字号记忆 ─────────────────────────────────────────
    private var _lastTextFontSize: Float = 40f
    private var _lastNumberFontSize: Float = 30f

    // ─── 选择变换快照 ─────────────────────────────────────
    private data class SelectionTransformState(
        val snapshot: Map<Int, DrawingPrimitive>,
        val startRotation: Float,
        val startScaleX: Float,
        val startScaleY: Float,
        val startOffsetX: Float,
        val startOffsetY: Float
    )
    private var _transformState: SelectionTransformState? = null

    // ─── 属性对话框 ─────────────────────────────────────
    private val _showPropertiesDlg = mutableStateOf(false)
    val showPropertiesDlg: Boolean get() = _showPropertiesDlg.value
    fun dismissPropertiesDialog() { _showPropertiesDlg.value = false }
    // Array dialog state (selection array pattern)
    private val _showArrayDlg = mutableStateOf(false)
    val showArrayDlg: Boolean get() = _showArrayDlg.value
    val arrayRows = mutableIntStateOf(2)
    val arrayCols = mutableIntStateOf(2)
    val arrayGapX = mutableFloatStateOf(80f)   // horizontal gap between columns
    val arrayGapY = mutableFloatStateOf(80f)   // vertical gap between rows
    val arrayDirX = mutableIntStateOf(1)       // 1=right, -1=left
    val arrayDirY = mutableIntStateOf(1)       // 1=down, -1=up
    fun dismissArrayDialog() { _showArrayDlg.value = false }
    // Active array mode (ghost preview + handles visible)
    private val _arrayActive = mutableStateOf(false)
    val arrayActive: Boolean get() = _arrayActive.value

    private var hasUnsavedChanges = false
    val isDirty: Boolean get() = hasUnsavedChanges
    private var _documentName = mutableStateOf("未命名")
    val documentName: String get() = _documentName.value
    private var _documentFile: java.io.File? = null
    private var _documentParent: java.io.File? = null

    // ═══════════════════════════════════════════════════════
    //  工具选择
    // ═══════════════════════════════════════════════════════

    fun setTool(tool: ToolType) {
        // 阵列模式中不允许切换工具
        if (_arrayActive.value) return
        // 数字阵列编辑中不允许切换工具
        if (_numArrayActive.value) return
        // 有待确认图形(PFO)时：立即取消 PFO/输入，再切换到新工具
        if (_pendingEdit.value.isActive() && tool != _currentTool.value) {
            cancelPendingEdit()
        }
        // 选择变换中切工具 → 先应用变换再切
        if (tool != _currentTool.value && _currentTool.value == ToolType.SELECT && _transformState != null) {
            finalizeSelectionTransform()
        }
        _previousTool.value = _currentTool.value
        _currentTool.value = tool
        _isTemporaryEraser.value = false  // 手动切工具退出临时模式
        // 切换工具时清除选择和残留的变换状态
        if (tool != ToolType.SELECT) {
            clearSelection()
        }
        saveSettings()
    }
    /**
     * 工具栏颜色按钮：PFO(待确认/选择)期间实时作用到图形本身，不写图纸级颜色；
     * 无 PFO 时才修改图纸级颜色（退出 PFO 后按钮自动恢复显示图纸级颜色）。
     */
    fun setColor(color: Color) {
        // 待确认(PFO)图形：实时跟随
        val pe = _pendingEdit.value
        val prim = pe.primitive
        if (pe.isActive() && prim != null) {
            _pendingEdit.value = pe.copy(primitive = prim.withColor(color))
            return
        }
        // 选择(PFO)图形：实时调整所有选中图形
        val sel = _selection.value.selectedIndices
        if (sel.isNotEmpty()) {
            pushSelectionAdjustUndo()
            for (i in sel.sortedDescending()) {
                val p = _primitives.getOrNull(i) ?: continue
                _primitives[i] = p.withColor(color)
            }
            autoSave()
            return
        }
        _currentColor.value = color
        saveSettings()
    }
    /**
     * 工具栏线宽滑块：PFO(待确认/选择)期间实时作用到图形本身，不写图纸级线宽；
     * 无 PFO 时才修改图纸级线宽（退出 PFO 后滑块自动恢复显示图纸级线宽）。
     */
    fun setStrokeWidth(w: Float) {
        val v = w.coerceIn(1f, 40f)
        // 待确认(PFO)图形：实时跟随
        val pe = _pendingEdit.value
        val prim = pe.primitive
        if (pe.isActive() && prim != null) {
            val updated = shapeWithStrokeWidth(prim, v)
            if (updated != null) { _pendingEdit.value = pe.copy(primitive = updated); return }
        }
        // 选择(PFO)图形：实时调整所有选中图形
        val sel = _selection.value.selectedIndices
        if (sel.isNotEmpty()) {
            pushSelectionAdjustUndo()
            var changed = false
            for (i in sel.sortedDescending()) {
                val p = _primitives.getOrNull(i) ?: continue
                val updated = shapeWithStrokeWidth(p, v) ?: continue
                _primitives[i] = updated; changed = true
            }
            if (changed) autoSave()
            return
        }
        _currentStrokeWidth.value = v
        saveSettings()
    }

    /** 仅形状类图形支持线宽（数字/文字/区间/块引用不跟随） */
    private fun shapeWithStrokeWidth(p: DrawingPrimitive, w: Float): DrawingPrimitive? = when (p) {
        is DrawingPrimitive.FreehandPath -> p.copy(strokeWidth = w)
        is DrawingPrimitive.RectanglePrimitive -> p.copy(strokeWidth = w)
        is DrawingPrimitive.CirclePrimitive -> p.copy(strokeWidth = w)
        is DrawingPrimitive.LinePrimitive -> p.copy(strokeWidth = w)
        else -> null
    }

    /** 滑块连续拖动时每次 PFO 会话只压入一次撤销快照（参照橡皮擦的去重模式） */
    private var selectionAdjustUndoPushed = false
    private fun pushSelectionAdjustUndo() {
        if (!selectionAdjustUndoPushed) { pushUndo(); selectionAdjustUndoPushed = true }
    }

    /** 工具栏线宽滑块的显示值：待确认=该图形线宽；单选=该图形线宽；多选=最宽线宽；否则=图纸级线宽 */
    val toolbarStrokeWidth: Float
        get() {
            val pe = _pendingEdit.value
            if (pe.isActive()) {
                when (val p = pe.primitive) {
                    is DrawingPrimitive.FreehandPath -> return p.strokeWidth
                    is DrawingPrimitive.RectanglePrimitive -> return p.strokeWidth
                    is DrawingPrimitive.CirclePrimitive -> return p.strokeWidth
                    is DrawingPrimitive.LinePrimitive -> return p.strokeWidth
                    else -> {}
                }
            }
            val sel = _selection.value.selectedIndices
            if (sel.isNotEmpty()) {
                var maxW = -1f
                for (i in sel) {
                    when (val p = _primitives.getOrNull(i)) {
                        is DrawingPrimitive.FreehandPath -> maxW = maxOf(maxW, p.strokeWidth)
                        is DrawingPrimitive.RectanglePrimitive -> maxW = maxOf(maxW, p.strokeWidth)
                        is DrawingPrimitive.CirclePrimitive -> maxW = maxOf(maxW, p.strokeWidth)
                        is DrawingPrimitive.LinePrimitive -> maxW = maxOf(maxW, p.strokeWidth)
                        else -> {}
                    }
                }
                if (maxW > 0f) return maxW
            }
            return _currentStrokeWidth.value
        }

    /** 工具栏颜色按钮的显示值：待确认=该图形颜色；选择=首个选中图形颜色；否则=图纸级颜色 */
    val toolbarColor: Color
        get() {
            val pe = _pendingEdit.value
            if (pe.isActive()) pe.primitive?.let { return it.color }
            val sel = _selection.value.selectedIndices
            if (sel.isNotEmpty()) {
                sel.minOrNull()?.let { idx -> _primitives.getOrNull(idx)?.let { return it.color } }
            }
            return _currentColor.value
        }
    fun setLineStyle(style: LineStyle) { _currentLineStyle.value = style; saveSettings() }
    fun setActiveLayer(id: Int) {
        val target = _layers.find { it.id == id } ?: return
        if (!target.isVisible) return  // 隐藏层不能设为活动层，避免盲画/盲擦
        _activeLayerId.value = id
    }
    fun setGlobalLineScale(s: Float) { _globalLineScale.value = s.coerceIn(0.25f, 4f); saveSettings() }
    fun setEraserRadius(displayR: Float) { _eraserRadius.value = (displayR * 10f).coerceIn(50f, 1000f); saveSettings() }
    fun toggleConstraint() { _constraintEnabled.value = !_constraintEnabled.value; saveSettings() }
    fun toggleSnap() { _snapEnabled.value = !_snapEnabled.value; saveSettings() }
    fun setPendingTextContent(text: String) { _pendingTextContent.value = text }

    fun setNumberLabelStart(value: Int) {
        _numberLabel.value = _numberLabel.value.copy(startFrom = value, currentValue = value)
    }
    fun setNumberFontSize(size: Float) {
        _numberLabel.value = _numberLabel.value.copy(fontSize = size.coerceIn(30f, 600f))
    }
    fun updateNumberLabelValue(newValue: Int) {
        _numberLabel.value = _numberLabel.value.copy(currentValue = newValue)
    }
    fun getLastTextFontSize(): Float = _lastTextFontSize
    fun getLastNumberFontSize(): Float = _lastNumberFontSize
    /** 文字工具栏字号滑块（无 pending 时）：与缩放钳制一致 30..600 */
    fun setLastTextFontSize(size: Float) { _lastTextFontSize = size.coerceIn(30f, 600f); saveSettings() }

    // Persisted text orientation
    private val _textHorizontalOnly = mutableStateOf(false)
    val textHorizontalOnly: Boolean get() = _textHorizontalOnly.value
    fun toggleTextOrientation() {
        _textHorizontalOnly.value = !_textHorizontalOnly.value
        saveSettings()
    }
    /** 文字对话框确定时显式设置方向（true=横向） */
    fun setTextOrientation(horizontalOnly: Boolean) {
        _textHorizontalOnly.value = horizontalOnly
        saveSettings()
    }

    // ─── 区间数字 ─────────────────────────────────────────
    fun setRangeValues(start: Int, end: Int) {
        _rangeLabel.value = _rangeLabel.value.copy(startValue = start, endValue = end)
    }
    fun setRangeStart(value: Int) {
        _rangeLabel.value = _rangeLabel.value.copy(startValue = value)
    }
    fun setRangeEnd(value: Int) {
        _rangeLabel.value = _rangeLabel.value.copy(endValue = value)
    }
    fun setRangeFontSize(size: Float) {
        _rangeLabel.value = _rangeLabel.value.copy(fontSize = size.coerceIn(20f, 600f))
    }
    fun toggleRangeOrientation() {
        _rangeLabel.value = _rangeLabel.value.copy(horizontalOnly = !_rangeLabel.value.horizontalOnly)
    }
    fun toggleRangeReversed() {
        val pe = _pendingEdit.value
        if (pe.isActive() && pe.primitive is DrawingPrimitive.RangeLabelPrimitive) {
            val p = pe.primitive as DrawingPrimitive.RangeLabelPrimitive
            _pendingEdit.value = pe.copy(primitive = p.copy(reversed = !p.reversed))
        }
        _rangeLabel.value = _rangeLabel.value.copy(reversed = !_rangeLabel.value.reversed)
    }
    fun setRangeArrowSpan(span: Float) {
        _rangeLabel.value = _rangeLabel.value.copy(arrowSpan = span)
        docDefaultArrowSpan = span
        saveSettings()
    }

    // ─── 数字阵列 ─────────────────────────────────────────
    /** 松手后在 [start] 生成整组普通数字并进入编辑模式（创建只推一次撤销快照） */
    private fun startNumArray(start: Point2D) {
        val na = _numArrayLabel.value
        numArrayBaseX = start.x; numArrayBaseY = start.y
        pushUndo()
        val first = _primitives.size
        buildNumArrayPrimitives(na).forEach { _primitives.add(it) }
        numArrayIndices = (first until _primitives.size).toList()
        _numArrayActive.value = true
        hasUnsavedChanges = true
    }

    /** 按状态构建一组数字基元（竖屏正立、横屏转 90° 保持"朝下"可读，步长 1，start>end 时倒序；沿 rotationDeg 方向排列） */
    private fun buildNumArrayPrimitives(na: NumArrayLabel): List<DrawingPrimitive.NumberLabelPrimitive> {
        val step = if (na.endValue >= na.startValue) 1 else -1
        val count = abs(na.endValue - na.startValue) + 1
        val rad = Math.toRadians(na.rotationDeg.toDouble())
        val cosA = kotlin.math.cos(rad).toFloat()
        val sinA = kotlin.math.sin(rad).toFloat()
        return (0 until count).map { i ->
            DrawingPrimitive.NumberLabelPrimitive(
                value = na.startValue + step * i,
                x = numArrayBaseX + cosA * na.gap * i, y = numArrayBaseY + sinA * na.gap * i,
                fontSize = na.fontSize, color = _currentColor.value,
                strokeWidth = _currentStrokeWidth.value, layerId = _activeLayerId.value,
                horizontalOnly = true, circled = na.circled,
                rotation = if (_isLandscape.value) (Math.PI / 2).toFloat() else 0f
            )
        }
    }

    /** 编辑中调整参数：整组删旧建新（索引连续成块，不再推撤销快照） */
    private fun regenerateNumArray() {
        if (!_numArrayActive.value || numArrayIndices.isEmpty()) return
        val first = numArrayIndices.first()
        repeat(numArrayIndices.size) { _primitives.removeAt(first) }
        buildNumArrayPrimitives(_numArrayLabel.value).forEach { _primitives.add(it) }
        numArrayIndices = (first until _primitives.size).toList()
        hasUnsavedChanges = true
    }

    fun setNumArrayValues(start: Int, end: Int) {
        _numArrayLabel.value = _numArrayLabel.value.copy(startValue = start, endValue = end)
        if (_numArrayActive.value) regenerateNumArray()
    }
    fun setNumArrayFontSize(size: Float) {
        _numArrayLabel.value = _numArrayLabel.value.copy(fontSize = size.coerceIn(20f, 600f))
        if (_numArrayActive.value) regenerateNumArray()
    }
    fun setNumArrayGap(gap: Float) {
        _numArrayLabel.value = _numArrayLabel.value.copy(gap = gap.coerceIn(20f, 10000f))
        if (_numArrayActive.value) regenerateNumArray()
    }
    /** 竖向排列开关（对话框用）：在 0°（横向）与 90°（竖向）之间切换 */
    fun toggleNumArrayLayout() {
        val cur = _numArrayLabel.value.rotationDeg % 180f
        val newDeg = if (cur < 45f || cur > 135f) 90f else 0f
        _numArrayLabel.value = _numArrayLabel.value.copy(rotationDeg = newDeg)
        if (_numArrayActive.value) regenerateNumArray()
    }

    /** 方向手柄拖动：以首数字为圆心 360° 旋转，角度实时写入排列方向 */
    fun setNumArrayRotation(deg: Float) {
        val norm = ((deg % 360f) + 360f) % 360f
        if (abs(_numArrayLabel.value.rotationDeg - norm) < 0.5f) return
        _numArrayLabel.value = _numArrayLabel.value.copy(rotationDeg = norm)
        if (_numArrayActive.value) regenerateNumArray()
    }
    fun toggleNumArrayCircled() {
        _numArrayLabel.value = _numArrayLabel.value.copy(circled = !_numArrayLabel.value.circled)
        if (_numArrayActive.value) regenerateNumArray()
    }

    /** 确认：整组数字定稿为普通数字；首数字自动递增方便连续放下一组 */
    fun confirmNumArray() {
        if (!_numArrayActive.value) return
        val na = _numArrayLabel.value
        val span = na.endValue - na.startValue
        val newStart = na.endValue + (if (span >= 0) 1 else -1)
        _numArrayLabel.value = na.copy(startValue = newStart, endValue = newStart + span)
        _numArrayActive.value = false
        numArrayIndices = emptyList()
        saveSettings()
        autoSave()
    }

    /** 取消：回滚创建时的撤销快照，一次撤掉整组 */
    fun cancelNumArray() {
        if (!_numArrayActive.value) return
        _numArrayActive.value = false
        numArrayIndices = emptyList()
        undo()
    }

    fun toggleHorizontalText() {
        val pe = _pendingEdit.value
        if (pe.isActive()) {
            val prim = pe.primitive
            when (prim) {
                is DrawingPrimitive.TextPrimitive -> {
                    val newHoriz = !prim.horizontalOnly
                    val newRot = if (newHoriz) 0f else (Math.PI / 2).toFloat()
                    _pendingEdit.value = pe.copy(
                        primitive = prim.copy(horizontalOnly = newHoriz, rotation = newRot),
                        bounds = swapBounds(pe.bounds))
                    _textHorizontalOnly.value = newHoriz
                }
                is DrawingPrimitive.NumberLabelPrimitive -> {
                    val newHoriz = !prim.horizontalOnly
                    val newRot = if (newHoriz) 0f else (Math.PI / 2).toFloat()
                    _pendingEdit.value = pe.copy(
                        primitive = prim.copy(horizontalOnly = newHoriz, rotation = newRot),
                        bounds = swapBounds(pe.bounds))
                    _numberLabel.value = _numberLabel.value.copy(horizontalOnly = newHoriz)
                }
                is DrawingPrimitive.RangeLabelPrimitive -> {
                    val newHoriz = !prim.horizontalOnly
                    val newRot = if (newHoriz) 0f else (Math.PI / 2).toFloat()
                    _pendingEdit.value = pe.copy(
                        primitive = prim.copy(horizontalOnly = newHoriz, rotation = newRot),
                        bounds = swapBounds(pe.bounds))
                    _rangeLabel.value = _rangeLabel.value.copy(horizontalOnly = newHoriz)
                }
                else -> {}
            }
        } else {
            when (_currentTool.value) {
                ToolType.RANGE ->
                    _rangeLabel.value = _rangeLabel.value.copy(horizontalOnly = !_rangeLabel.value.horizontalOnly)
                ToolType.TEXT -> {
                    _textHorizontalOnly.value = !_textHorizontalOnly.value
                    saveSettings()
                }
                else ->
                    _numberLabel.value = _numberLabel.value.copy(horizontalOnly = !_numberLabel.value.horizontalOnly)
            }
        }
    }

    /** 数字外圈开关：pending 激活时同步刷新预览和工具状态 */
    fun toggleNumberCircled() {
        val pe = _pendingEdit.value
        val prim = pe.primitive
        if (pe.isActive() && prim is DrawingPrimitive.NumberLabelPrimitive) {
            val newCircled = !prim.circled
            _pendingEdit.value = pe.copy(primitive = prim.copy(circled = newCircled))
            _numberLabel.value = _numberLabel.value.copy(circled = newCircled)
        } else {
            _numberLabel.value = _numberLabel.value.copy(circled = !_numberLabel.value.circled)
        }
    }

    /** Swap bounding box width/height around center (for orientation toggle) */
    private fun swapBounds(bounds: Bounds?): Bounds? {
        if (bounds == null) return null
        val bw = bounds.maxX - bounds.minX
        val bh = bounds.maxY - bounds.minY
        val cx = (bounds.minX + bounds.maxX) / 2f
        val cy = (bounds.minY + bounds.maxY) / 2f
        return Bounds(cx - bh / 2f, cy - bw / 2f, cx + bh / 2f, cy + bw / 2f)
    }

    // ═══════════════════════════════════════════════════════
    //  画布视口变换
    // ═══════════════════════════════════════════════════════

    fun transformCanvas(zoom: Float, centroid: Offset, pan: Offset) {
        val oldScale = _canvasScale.value
        if (oldScale <= 0f) return
        val newScale = (oldScale * zoom).coerceIn(0.015f, 50f)
        if (zoom != 1f) {
            val ratio = newScale / oldScale
            _canvasOffsetX.value = centroid.x - (centroid.x - _canvasOffsetX.value) * ratio
            _canvasOffsetY.value = centroid.y - (centroid.y - _canvasOffsetY.value) * ratio
        }
        _canvasOffsetX.value += pan.x
        _canvasOffsetY.value += pan.y
        _canvasScale.value = newScale
    }

    fun transformBlockEditorCanvas(zoom: Float, centroid: Offset, pan: Offset) {
        val oldScale = _blockEditorViewScale.value
        if (oldScale <= 0f) return
        val newScale = (oldScale * zoom).coerceIn(0.015f, 50f)
        if (zoom != 1f) {
            val ratio = newScale / oldScale
            _blockEditorViewX.value = centroid.x - (centroid.x - _blockEditorViewX.value) * ratio
            _blockEditorViewY.value = centroid.y - (centroid.y - _blockEditorViewY.value) * ratio
        }
        _blockEditorViewX.value += pan.x
        _blockEditorViewY.value += pan.y
        _blockEditorViewScale.value = newScale
    }

    fun fitToScreen(screenWidth: Float, screenHeight: Float) {
        if (_primitives.isEmpty() && _pendingEdit.value.primitive == null) {
            _canvasScale.value = 1f; _canvasOffsetX.value = 0f; _canvasOffsetY.value = 0f; return
        }
        val allPrimitives = _primitives.toList()
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in allPrimitives) {
            val b = p.computeBounds(boundsMeasurePaint) ?: continue
            minX = minOf(minX, b[0], b[2]); minY = minOf(minY, b[1], b[3])
            maxX = maxOf(maxX, b[0], b[2]); maxY = maxOf(maxY, b[1], b[3])
        }
        _pendingEdit.value.primitive?.let { p ->
            val b = p.computeBounds(boundsMeasurePaint) ?: return@let
            minX = minOf(minX, b[0], b[2]); minY = minOf(minY, b[1], b[3])
            maxX = maxOf(maxX, b[0], b[2]); maxY = maxOf(maxY, b[1], b[3])
        }
        if (minX == Float.MAX_VALUE) return
        val pad = 50f; minX -= pad; minY -= pad; maxX += pad; maxY += pad
        val cw = maxX - minX; val ch = maxY - minY
        if (cw <= 0f || ch <= 0f) return
        val sp = 40f; val aw = screenWidth - 2f * sp; val ah = screenHeight - 2f * sp
        if (aw <= 0f || ah <= 0f) return
        val ns = minOf(aw / cw, ah / ch).coerceIn(0.05f, 50f)
        val cx = (minX + maxX) / 2f; val cy = (minY + maxY) / 2f
        val scx = screenWidth / 2f; val scy = screenHeight / 2f
        _canvasScale.value = ns; _canvasOffsetX.value = scx - cx * ns; _canvasOffsetY.value = scy - cy * ns
    }

    // ═══════════════════════════════════════════════════════

    fun startPrimitive(start: Point2D) {
        val activeLayer = _layers.find { it.id == _activeLayerId.value }
        if (activeLayer?.isLocked == true) return
        // 阵列预览中：单指拖动 = 平移原始元素（幽灵预览跟随）
        if (_arrayActive.value) {
            if (_selection.value.selectedIndices.isNotEmpty()) {
                arrayDragLast = start
                arrayDragUndoPushed = false  // 撤销延迟到首次实际移动时再记，避免点按/手柄按下产生空撤销
            }
            return
        }
        if (placePendingImport(start)) return
        // 数字阵列编辑中：单指不做事（间距由手柄调，其余由实时工具栏调）
        if (_numArrayActive.value) return
        // 图片管理模式中：单指不做事（画布仅双指平移缩放 + PFO 手柄，图片不可点选）
        if (_imageManageActive.value) return
        if (_pendingEdit.value.isActive()) return

        when (_currentTool.value) {
            ToolType.ERASER -> {
                // 只记落下点并显示橡皮光标，不立即擦除（见 eraserDownPoint 注释）
                eraserDownPoint = start
                _eraserTouchPoint.value = start
                return
            }
            ToolType.SELECT -> { startSelection(start); return }
            ToolType.ANNOTATE, ToolType.TEXT, ToolType.RANGE, ToolType.NUM_ARRAY -> {
                // 只记位置，松手（commitPrimitive）才创建；双指缩放时 cancelPrimitive 清掉，
                // 全程不产生预览，避免缩放手势第一触点闪现数字/文字
                labelPendingStart = start
                return
            }
            ToolType.BLOCK -> { clearSelection(); return }
            else -> {}
        }

        _currentPrimitive.value = when (_currentTool.value) {
            ToolType.FREEHAND -> DrawingPrimitive.FreehandPath(
                points = listOf(start), color = _currentColor.value,
                strokeWidth = _currentStrokeWidth.value, layerId = _activeLayerId.value,
                lineStyle = _currentLineStyle.value
            )
            ToolType.RECTANGLE -> DrawingPrimitive.RectanglePrimitive(
                corners = listOf(Point2D(start.x, start.y), Point2D(start.x, start.y),
                    Point2D(start.x, start.y), Point2D(start.x, start.y)),
                color = _currentColor.value, strokeWidth = _currentStrokeWidth.value,
                layerId = _activeLayerId.value, lineStyle = _currentLineStyle.value
            )
            ToolType.CIRCLE -> DrawingPrimitive.CirclePrimitive(
                centerX = start.x, centerY = start.y, endX = start.x, endY = start.y,
                color = _currentColor.value, strokeWidth = _currentStrokeWidth.value,
                layerId = _activeLayerId.value, lineStyle = _currentLineStyle.value
            )
            ToolType.LINE -> DrawingPrimitive.LinePrimitive(
                startX = start.x, startY = start.y, endX = start.x, endY = start.y,
                color = _currentColor.value, strokeWidth = _currentStrokeWidth.value,
                layerId = _activeLayerId.value, lineStyle = _currentLineStyle.value
            )
            else -> null
        }
    }

    /** 按当前工具在指定位置创建数字/文字/区间基元（松手时才调用，见 labelPendingStart） */
    private fun buildLabelPrimitive(start: Point2D): DrawingPrimitive? = when (_currentTool.value) {
        ToolType.ANNOTATE -> {
            val nl = _numberLabel.value
            val initRotation = if (nl.horizontalOnly) 0f else (Math.PI / 2).toFloat()
            DrawingPrimitive.NumberLabelPrimitive(
                value = nl.currentValue, x = start.x, y = start.y,
                fontSize = nl.fontSize, color = _currentColor.value,
                strokeWidth = _currentStrokeWidth.value, layerId = _activeLayerId.value,
                horizontalOnly = nl.horizontalOnly, circled = nl.circled, rotation = initRotation
            )
        }
        ToolType.TEXT -> {
            val txt = _pendingTextContent.value.ifBlank { "文本" }
            val textHoriz = _textHorizontalOnly.value
            DrawingPrimitive.TextPrimitive(
                text = txt, x = start.x, y = start.y,
                fontSize = _lastTextFontSize, color = _currentColor.value,
                strokeWidth = _currentStrokeWidth.value, layerId = _activeLayerId.value,
                horizontalOnly = textHoriz, rotation = if (textHoriz) 0f else (Math.PI / 2).toFloat()
            )
        }
        ToolType.RANGE -> {
            val rl = _rangeLabel.value
            // 布局旋转角只表达"横向/竖向"（0 / π/2），横屏偏移由渲染/导出期的纯函数
            // RangeLabelLayout 统一叠加（两端数字"朝下"跟随画布旋转态），不再烘焙进存储值，
            // 这样横竖屏切换时已存在的区间也能跟随。
            val initRotation = if (rl.horizontalOnly) 0f else (Math.PI / 2).toFloat()
            DrawingPrimitive.RangeLabelPrimitive(
                startValue = rl.startValue, endValue = rl.endValue,
                x = start.x, y = start.y,
                fontSize = rl.fontSize, color = _currentColor.value,
                strokeWidth = _currentStrokeWidth.value, layerId = _activeLayerId.value,
                horizontalOnly = rl.horizontalOnly, rotation = initRotation,
                arrowSpan = docDefaultArrowSpan, reversed = rl.reversed,
                numbersFaceLeft = rl.numbersFaceLeft
            )
        }
        else -> null
    }

    fun updatePrimitive(point: Point2D) {
        // 阵列预览中拖动：平移原始元素
        val adl = arrayDragLast
        if (_arrayActive.value && adl != null) {
            val dx = point.x - adl.x; val dy = point.y - adl.y
            arrayDragLast = point
            if (dx != 0f || dy != 0f) {
                if (!arrayDragUndoPushed) { pushUndo(); arrayDragUndoPushed = true }
                arrayDragSelected(dx, dy)
            }
            return
        }
        if (_currentTool.value == ToolType.ERASER) {
            // 首次移动先补擦落下点，避免起点处漏擦
            eraserDownPoint?.let { performErasure(it); eraserDownPoint = null }
            performErasure(point); return
        }
        if (_currentTool.value == ToolType.SELECT) { updateSelection(point); return }
        _currentPrimitive.value = when (val cp = _currentPrimitive.value) {
            is DrawingPrimitive.FreehandPath -> cp.copy(points = cp.points + point)
            is DrawingPrimitive.RectanglePrimitive -> {
                val sx = cp.corners[0].x; val sy = cp.corners[0].y
                if (_rectangleSquareMode.value) {
                    val dx = point.x - sx; val dy = point.y - sy
                    val side = maxOf(abs(dx), abs(dy))
                    val sgnX = if (dx > 0) 1f else if (dx < 0) -1f else 0f
                    val sgnY = if (dy > 0) 1f else if (dy < 0) -1f else 0f
                    val ex = sx + sgnX * side; val ey = sy + sgnY * side
                    cp.copy(corners = listOf(
                        Point2D(sx, sy), Point2D(ex, sy),
                        Point2D(ex, ey), Point2D(sx, ey)
                    ))
                } else cp.copy(corners = listOf(
                    Point2D(sx, sy), Point2D(point.x, sy),
                    Point2D(point.x, point.y), Point2D(sx, point.y))
                )
            }
            is DrawingPrimitive.CirclePrimitive -> {
                if (_circleCircleMode.value) {
                    val dx = point.x - cp.centerX
                    val dy = point.y - cp.centerY
                    val r = maxOf(abs(dx), abs(dy))
                    val sx = if (dx > 0) 1f else if (dx < 0) -1f else 0f
                    val sy = if (dy > 0) 1f else if (dy < 0) -1f else 0f
                    cp.copy(endX = cp.centerX + sx * r, endY = cp.centerY + sy * r)
                } else cp.copy(endX = point.x, endY = point.y)
            }
            is DrawingPrimitive.LinePrimitive -> {
                if (_lineSnapMode.value) {
                    val dx = point.x - cp.startX
                    val dy = point.y - cp.startY
                    if (abs(dx) >= abs(dy)) {
                        cp.copy(endX = point.x, endY = cp.startY)
                    } else {
                        cp.copy(endX = cp.startX, endY = point.y)
                    }
                } else cp.copy(endX = point.x, endY = point.y)
            }
            else -> _currentPrimitive.value
        }
    }

    fun commitPrimitive() {
        arrayDragLast = null
        if (_currentTool.value == ToolType.SELECT) { endSelection(); return }
        if (_currentTool.value == ToolType.ERASER) {
            // 点按（全程未移动）：在落下点补一次擦除
            eraserDownPoint?.let { performErasure(it); eraserDownPoint = null }
            _eraserTouchPoint.value = null
            // 什么都没擦掉时不存盘，避免空点一次就整档序列化写盘
            if (_eraserUndoPushed) autoSave()
            _eraserUndoPushed = false
            exitTemporaryEraser(); return
        }
        // 数字/文字/区间：松手时才真正创建基元（按下只记了位置）
        if (_currentPrimitive.value == null && labelPendingStart != null) {
            val st = labelPendingStart!!
            labelPendingStart = null
            // 数字阵列：直接生成整组普通数字，进入专属编辑模式（不进 PendingEdit）
            if (_currentTool.value == ToolType.NUM_ARRAY) { startNumArray(st); return }
            _currentPrimitive.value = buildLabelPrimitive(st)
        }
        val cp = _currentPrimitive.value ?: return
        val valid = when (cp) {
            is DrawingPrimitive.FreehandPath -> cp.points.size >= 3
            is DrawingPrimitive.RectanglePrimitive -> cp.corners.map { it.x }.let { xs -> xs.max() - xs.min() > 5f } ||
                cp.corners.map { it.y }.let { ys -> ys.max() - ys.min() > 5f }
            is DrawingPrimitive.CirclePrimitive -> cp.radiusX > 5f || cp.radiusY > 5f
            is DrawingPrimitive.LinePrimitive -> abs(cp.endX - cp.startX) > 3f || abs(cp.endY - cp.startY) > 3f
            else -> true
        }
        if (valid) {
            // 手绘线直接提交，不进预览编辑模式
            if (cp is DrawingPrimitive.FreehandPath) {
                if (_blockDraft.value != null) {
                    _blockDraft.value!!.primitives.add(cp)
                }
                pushUndo()
                _primitives.add(cp); hasUnsavedChanges = true
            } else {
                // 其他图形进入预览编辑模式（blockDraft 在 confirmPendingEdit 中添加）
                val arr = cp.computeBounds(boundsMeasurePaint)
                val bounds = if (arr != null) Bounds(arr[0], arr[1], arr[2], arr[3]) else Bounds(0f, 0f, 100f, 100f)
                _pendingEdit.value = PendingEdit(
                    active = true, primitive = cp, bounds = bounds,
                    rotation = 0f, scaleX = 1f, scaleY = 1f,
                    offsetX = 0f, offsetY = 0f,
                    pivotX = (bounds.minX + bounds.maxX) / 2f,
                    pivotY = (bounds.minY + bounds.maxY) / 2f
                )
                hasUnsavedChanges = true
            }
        }
        _currentPrimitive.value = null
    }

    fun cancelPrimitive() {
        // 数字阵列编辑中：双指缩放等打断不影响已生成的整组数字
        if (_numArrayActive.value) return
        // Selection persists until explicit deselect — no auto-clear on cancel
        labelPendingStart = null  // 双指缩放等打断：丢弃未创建的数字/文字/区间
        if (_currentTool.value == ToolType.ERASER) {
            eraserDownPoint = null
            _eraserTouchPoint.value = null
            // 双指缩放等打断场景：回滚本次手势已擦除的内容，避免误擦；
            // 什么都没擦掉时跳过撤销与存盘（否则每次双指缩放都要整档序列化写盘，导致卡顿）
            if (_eraserUndoPushed) {
                undo()
                autoSave()
            }
            _eraserUndoPushed = false
            exitTemporaryEraser()
            return
        }
        // Only clear pending edit if it was NOT just created by commitPrimitive
        // (commitPrimitive creates PendingEdit, cancelPrimitive should not undo it)
        if (_pendingEdit.value.isActive() &&
            (_currentTool.value == ToolType.ANNOTATE || _currentTool.value == ToolType.TEXT || _currentTool.value == ToolType.RANGE)) {
            // Don't clear here — let user interact with the preview
            return
        }
        _currentPrimitive.value = null
    }

    // ═══════════════════════════════════════════════════════
    //  松手后编辑
    // ═══════════════════════════════════════════════════════

    fun confirmPendingEdit() {
        val pe = _pendingEdit.value
        if (!pe.isActive()) return
        val primitive = pe.primitive ?: return
        var finalPrimitive = primitive.applyTransform(pe)
        if (_blockDraft.value != null) {
            _blockDraft.value!!.primitives.add(finalPrimitive)
        }
        pushUndo()
        _primitives.add(finalPrimitive)
        _pendingEdit.value = PendingEdit()
        when (finalPrimitive) {
            is DrawingPrimitive.TextPrimitive -> _lastTextFontSize = finalPrimitive.fontSize
            is DrawingPrimitive.NumberLabelPrimitive -> {
                _lastNumberFontSize = finalPrimitive.fontSize
                _numberLabel.value = _numberLabel.value.copy(fontSize = finalPrimitive.fontSize,
                    currentValue = _numberLabel.value.currentValue + 1,
                    horizontalOnly = finalPrimitive.horizontalOnly,
                    circled = finalPrimitive.circled)
            }
            is DrawingPrimitive.RangeLabelPrimitive -> {
                val newStart = finalPrimitive.endValue + 1
                _rangeLabel.value = _rangeLabel.value.copy(
                    startValue = newStart,
                    endValue = newStart + 1,
                    lastEndValue = finalPrimitive.endValue,
                    fontSize = finalPrimitive.fontSize,
                    horizontalOnly = finalPrimitive.horizontalOnly,
                    reversed = finalPrimitive.reversed,
                    arrowSpan = finalPrimitive.arrowSpan)
                // 箭线长度是图纸级变量：确认放置时同步默认值，
                // 保证新建区间数字复用、且随图纸文件保存（buildDocument → defaultArrowSpan）
                docDefaultArrowSpan = finalPrimitive.arrowSpan
            }
            else -> {}
        }
        // 图块插入确认后直接选中并切到选择工具（与 DXF 导入主画布行为一致），便于立即移动/缩放
        if (finalPrimitive is DrawingPrimitive.BlockRefPrimitive) {
            val idx = setOf(_primitives.size - 1)
            _selection.value = SelectionState(
                selectedIndices = idx,
                bounds = computeSelectionBounds(idx),
                rotation = computeSelectionRotation(idx)
            )
            setTool(ToolType.SELECT)
        }
        autoSave()
    }

    fun cancelPendingEdit() { _pendingEdit.value = PendingEdit() }

    fun updatePendingOffset(dx: Float, dy: Float) {
        val pe = _pendingEdit.value
        _pendingEdit.value = pe.copy(offsetX = pe.offsetX + dx, offsetY = pe.offsetY + dy)
    }

    fun updatePendingPrimitive(primitive: DrawingPrimitive) {
        val pe = _pendingEdit.value
        val b = primitive.computeBounds(boundsMeasurePaint)
        val bounds = if (b != null) Bounds(b[0], b[1], b[2], b[3]) else pe.bounds ?: Bounds(0f, 0f, 100f, 100f)
        _pendingEdit.value = pe.copy(primitive = primitive, bounds = bounds)
    }

    /** 矩形中点拖拽更新 — 保持 bounds 中心不变，防止旋转后旋转中心漂移导致对边移位 */
    fun updatePendingRectMidpoint(primitive: DrawingPrimitive) {
        val pe = _pendingEdit.value
        val oldBounds = pe.bounds ?: return
        val oldCx = (oldBounds.minX + oldBounds.maxX) / 2f
        val oldCy = (oldBounds.minY + oldBounds.maxY) / 2f
        val b = primitive.computeBounds(boundsMeasurePaint) ?: return
        val newHw = (b[2] - b[0]) / 2f
        val newHh = (b[3] - b[1]) / 2f
        val bounds = Bounds(oldCx - newHw, oldCy - newHh, oldCx + newHw, oldCy + newHh)
        _pendingEdit.value = pe.copy(primitive = primitive, bounds = bounds)
    }

    fun updatePendingRotation(delta: Float) {
        _pendingEdit.value = _pendingEdit.value.copy(rotation = _pendingEdit.value.rotation + delta)
    }

    fun updatePendingScale(sx: Float, sy: Float) {
        val pe = _pendingEdit.value
        var newScaleX = pe.scaleX * sx
        var newScaleY = pe.scaleY * sy
        val p = pe.primitive
        // 文字/数字/区间：缩放范围直接对齐字号范围（与字号滑块一致）。
        // 不能用通用 0.1..10 钳制 —— 基础字号 40 时到不了 600(=滑块100级)，
        // 基础字号大时到不了 30/20(=滑块1级)，且与滑块设置的大 scale 互相打架（卡死/跳变）
        val fontLimits: Pair<Float, Float>? = when (p) {
            is DrawingPrimitive.TextPrimitive -> 30f to 600f
            is DrawingPrimitive.NumberLabelPrimitive -> 30f to 600f
            is DrawingPrimitive.RangeLabelPrimitive -> 20f to 600f
            else -> null
        }
        if (fontLimits != null) {
            val baseFontSize = when (p) {
                is DrawingPrimitive.TextPrimitive -> p.fontSize
                is DrawingPrimitive.NumberLabelPrimitive -> p.fontSize
                is DrawingPrimitive.RangeLabelPrimitive -> p.fontSize
                else -> 1f
            }
            if (baseFontSize > 0f) {
                val minS = fontLimits.first / baseFontSize
                val maxS = fontLimits.second / baseFontSize
                newScaleX = newScaleX.coerceIn(minS, maxS)
                newScaleY = newScaleY.coerceIn(minS, maxS)
            }
        } else {
            newScaleX = newScaleX.coerceIn(0.1f, 10f)
            newScaleY = newScaleY.coerceIn(0.1f, 10f)
        }
        _pendingEdit.value = pe.copy(
            scaleX = newScaleX,
            scaleY = newScaleY
        )
    }

    fun updatePendingFontScale(delta: Float) {
        val pe = _pendingEdit.value
        if (pe.primitive is DrawingPrimitive.RangeLabelPrimitive) {
            val p = pe.primitive as DrawingPrimitive.RangeLabelPrimitive
            val newFs = (p.fontSize + delta).coerceIn(20f, 600f)
            _pendingEdit.value = pe.copy(primitive = p.copy(fontSize = newFs))
        } else {
            val factor = 1f + delta / 50f
            _pendingEdit.value = pe.copy(
                scaleX = (pe.scaleX * factor).coerceIn(0.1f, 10f),
                scaleY = (pe.scaleY * factor).coerceIn(0.1f, 10f)
            )
        }
    }

    fun updatePendingArrowSpan(factor: Float) {
        val pe = _pendingEdit.value
        val p = pe.primitive
        if (p is DrawingPrimitive.RangeLabelPrimitive) {
            val newSpan = (p.arrowSpan * factor).coerceAtLeast(0.2f)
            val updatedPrimitive = p.copy(arrowSpan = newSpan)
            val arr = updatedPrimitive.computeBounds(boundsMeasurePaint)
            val newBounds = if (arr != null) Bounds(arr[0], arr[1], arr[2], arr[3]) else pe.bounds
            _pendingEdit.value = pe.copy(primitive = updatedPrimitive, bounds = newBounds)
        }
    }

    fun updatePendingRangeFontSize(targetSize: Float) {
        val pe = _pendingEdit.value
        val p = pe.primitive
        if (p is DrawingPrimitive.RangeLabelPrimitive) {
            val clamped = targetSize.coerceIn(20f, 600f)
            _pendingEdit.value = pe.copy(primitive = p.copy(fontSize = clamped))
        }
    }

    fun getPendingEffectiveFontSize(): Float {
        val pe = _pendingEdit.value
        val p = pe.primitive ?: return _lastTextFontSize
        if (!pe.isActive()) return when (p) {
            is DrawingPrimitive.TextPrimitive -> _lastTextFontSize
            is DrawingPrimitive.NumberLabelPrimitive -> _lastNumberFontSize
            is DrawingPrimitive.RangeLabelPrimitive -> _rangeLabel.value.fontSize
            else -> _lastTextFontSize
        }
        val avgScale = sqrt(abs(pe.scaleX * pe.scaleY))
        return when (p) {
            is DrawingPrimitive.TextPrimitive -> (p.fontSize * avgScale).coerceIn(30f, 600f)
            is DrawingPrimitive.NumberLabelPrimitive -> (p.fontSize * avgScale).coerceIn(30f, 600f)
            is DrawingPrimitive.RangeLabelPrimitive -> {
                val isUniform = abs(pe.scaleX - pe.scaleY) < 0.01f
                (p.fontSize * (if (isUniform) avgScale else 1f)).coerceIn(20f, 600f)
            }
            else -> _lastTextFontSize
        }
    }

    fun updatePendingFontSize(targetSize: Float) {
        val pe = _pendingEdit.value
        val p = pe.primitive ?: return
        val baseFontSize = when (p) {
            is DrawingPrimitive.TextPrimitive -> p.fontSize
            is DrawingPrimitive.NumberLabelPrimitive -> p.fontSize
            else -> return
        }
        if (baseFontSize <= 0f) return
        // 不钳制比例：滑块范围由 getPendingEffectiveFontSize/applyTransform 的
        // coerceIn(30f,600f) 兜底，保证滑块能拉满整个范围
        val scale = targetSize / baseFontSize
        _pendingEdit.value = pe.copy(scaleX = scale, scaleY = scale)
    }

    fun updatePendingNumberValue(newValue: Int) {
        val pe = _pendingEdit.value
        val p = pe.primitive
        if (p is DrawingPrimitive.NumberLabelPrimitive) {
            _pendingEdit.value = pe.copy(primitive = p.copy(value = newValue))
        }
        _numberLabel.value = _numberLabel.value.copy(currentValue = newValue)
    }

    // ═══════════════════════════════════════════════════════
    //  选择操作
    // ═══════════════════════════════════════════════════════

    fun startSelection(point: Point2D) {
        _transformState = null  // 清除残留的变换快照，防止新拖框与旧变换状态冲突
        _selection.value = _selection.value.copy(
            isActive = true,
            selStartX = point.x, selStartY = point.y,
            selEndX = point.x, selEndY = point.y
        )
    }

    fun updateSelection(point: Point2D) {
        val s = _selection.value
        if (s.isActive) {
            _selection.value = s.copy(selEndX = point.x, selEndY = point.y)
        }
    }

    /** 图层是否可见（不存在视为不可见）：选择/编辑只作用于可见层，与画布渲染一致 */
    private fun isEditLayerVisible(layerId: Int): Boolean =
        _layers.find { it.id == layerId }?.isVisible == true

    fun endSelection() {
        val s = _selection.value
        if (!s.isActive) return  // no drag in progress, keep existing selection
        // 根据拖框方向确定选中模式：左→右=完全包含，右→左=交叉选中
        // 横屏模式：方向轴旋转 90°，改为上→下=完全包含，下→上=交叉选中
        val lr = if (_isLandscape.value) s.selStartY <= s.selEndY else s.selStartX <= s.selEndX
        val selBounds = Bounds(minOf(s.selStartX, s.selEndX), minOf(s.selStartY, s.selEndY),
            maxOf(s.selStartX, s.selEndX), maxOf(s.selStartY, s.selEndY))
        val dragSelected = _primitives.indices.filter { i ->
            val p = _primitives[i] ?: return@filter false
            if (!isEditLayerVisible(p.layerId)) return@filter false  // 隐藏层不参与选择，避免误删/误移不可见元素
            val pb = p.computeBounds(boundsMeasurePaint) ?: return@filter false
            if (lr) {
                // FULLY_COVER：完全包含
                pb[0] >= selBounds.minX && pb[1] >= selBounds.minY &&
                    pb[2] <= selBounds.maxX && pb[3] <= selBounds.maxY
            } else {
                // 交叉选中：有交集，且实际碰触图形（不选隔空戳到包围盒的）
                pb[0] <= selBounds.maxX && pb[2] >= selBounds.minX &&
                    pb[1] <= selBounds.maxY && pb[3] >= selBounds.minY &&
                    p.fenceHitsGeometry(selBounds)
            }
        }.toSet()
        // 判断拖框是否有实际面积（区分点击 vs 拖拽）
        val dragW = kotlin.math.abs(s.selEndX - s.selStartX)
        val dragH = kotlin.math.abs(s.selEndY - s.selStartY)
        val isMeaningfulDrag = dragW > 5f || dragH > 5f
        val prevSelected = s.selectedIndices
        if (!isMeaningfulDrag) {
            // 点击：尝试选中最近的基元
            val tapPoint = Point2D(s.selStartX, s.selStartY)
            val tol = 40f / _canvasScale.value
            var bestIdx = -1; var bestDist = Float.MAX_VALUE
            for ((i, p) in _primitives.withIndex()) {
                if (!isEditLayerVisible(p.layerId)) continue  // 隐藏层不参与点选
                val dist = p.distanceTo(tapPoint)
                if (dist < bestDist) { bestDist = dist; bestIdx = i }
            }
            val tappedIdx = if (bestIdx >= 0 && bestDist < tol) bestIdx else -1
            val newSelected = if (tappedIdx >= 0) setOf(tappedIdx) else prevSelected
            val hideMpTap = newSelected.isNotEmpty() && newSelected.all { i ->
                val p = _primitives.getOrNull(i)
                p is DrawingPrimitive.TextPrimitive || p is DrawingPrimitive.NumberLabelPrimitive
            }
            _selection.value = SelectionState(
                selectedIndices = newSelected,
                bounds = computeSelectionBounds(newSelected),
                rotation = if (tappedIdx >= 0) computeSelectionRotation(newSelected) else s.rotation,
                direction = if (lr) SelectDirection.FULLY_COVER else SelectDirection.INSIDE_TOUCH,
                hideMidpoints = hideMpTap
            )
            return
        }
        // 点击空白 → 保留旧选择；空拖 → 保留旧选择；选到东西 → 合并到旧选择
        val allSelected = if (dragSelected.isNotEmpty()) prevSelected + dragSelected else prevSelected
        val hideMp = allSelected.isNotEmpty() && allSelected.all { i ->
            val p = _primitives.getOrNull(i)
            p is DrawingPrimitive.TextPrimitive || p is DrawingPrimitive.NumberLabelPrimitive
        }
        _selection.value = SelectionState(
            selectedIndices = allSelected,
            bounds = computeSelectionBounds(allSelected),
            rotation = computeSelectionRotation(allSelected),
            direction = if (lr) SelectDirection.FULLY_COVER else SelectDirection.INSIDE_TOUCH,
            hideMidpoints = hideMp
        )
    }

    /** Compute rotation for selection frame.
     *  Single selection → match the element's intrinsic rotation.
     *  Multiple selection → unrotated frame. */
    private fun computeSelectionRotation(indices: Set<Int>): Float {
        if (indices.size != 1) return 0f
        return _primitives.getOrNull(indices.first())?.intrinsicRotation ?: 0f
    }

    fun computeSelectionBounds(indices: Set<Int>): Bounds? {
        if (indices.isEmpty()) return null
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in indices) {
            val p = _primitives.getOrNull(i) ?: continue
            // BlockRef 的 computeBounds 是固定占位框，这里按块实际内容计算
            val b = when (p) {
                is DrawingPrimitive.BlockRefPrimitive ->
                    computeBlockRefWorldBounds(p)?.let { floatArrayOf(it.minX, it.minY, it.maxX, it.maxY) }
                // 矩形的 computeBounds 不含旋转，多选框是不旋转的，必须用含旋转的世界 AABB
                is DrawingPrimitive.RectanglePrimitive -> p.worldBounds()
                else -> p.computeBounds(boundsMeasurePaint)
            } ?: continue
            minX = minOf(minX, b[0], b[2]); minY = minOf(minY, b[1], b[3])
            maxX = maxOf(maxX, b[0], b[2]); maxY = maxOf(maxY, b[1], b[3])
        }
        return if (minX != Float.MAX_VALUE) Bounds(minX, minY, maxX, maxY) else null
    }

    /** BlockRef 的世界包围盒：与 drawBlockRef 的渲染变换一致（内容缩放 → 绕原点旋转 → 平移使内容形心落锚点） */
    private fun computeBlockRefWorldBounds(p: DrawingPrimitive.BlockRefPrimitive): Bounds? {
        val bd = _blockDefs.find { it.id == p.blockDefId } ?: return null
        val cb = computeBlockBounds(bd.primitives) ?: return null
        // 与渲染同款形心（不是 AABB 中心），否则非对称块的包围盒与实际绘制位置有偏差
        val centroid = blockContentCentroid(bd.primitives) ?: return null
        val centroidX = centroid.x
        val centroidY = centroid.y
        val tx = p.x - centroidX * p.scale
        val ty = p.y - centroidY * p.scale
        val cosR = kotlin.math.cos(p.rotation); val sinR = kotlin.math.sin(p.rotation)
        fun map(wx: Float, wy: Float): Point2D {
            val sx = wx * p.scale; val sy = wy * p.scale
            return Point2D(sx * cosR - sy * sinR + tx, sx * sinR + sy * cosR + ty)
        }
        val pts = listOf(
            map(cb.minX, cb.minY), map(cb.maxX, cb.minY),
            map(cb.maxX, cb.maxY), map(cb.minX, cb.maxY)
        )
        val xs = pts.map { it.x }; val ys = pts.map { it.y }
        return Bounds(xs.min(), ys.min(), xs.max(), ys.max())
    }

    fun clearSelection() {
        _selection.value = SelectionState(
            selScaleX = 1f, selScaleY = 1f, selOffsetX = 0f, selOffsetY = 0f
        )
        _transformState = null
        selectionAdjustUndoPushed = false
    }

    /** Returns counts of each primitive type in the current selection, keyed by display name. */
    fun getSelectionTypeCounts(): Map<String, Int> {
        val counts = linkedMapOf<String, Int>()
        for (i in _selection.value.selectedIndices.sorted()) {
            if (i >= _primitives.size) continue
            val name = _primitives[i].typeName
            counts[name] = (counts[name] ?: 0) + 1
        }
        return counts
    }

    /** Filter selection to specific indices (used by type filter dialog). */
    fun filterSelectionToIndices(indices: Set<Int>) {
        if (indices.isEmpty()) { clearSelection(); return }
        _selection.value = _selection.value.copy(
            selectedIndices = indices,
            bounds = computeSelectionBounds(indices),
            rotation = computeSelectionRotation(indices)
        )
    }

    fun moveSelectedPrimitives(dx: Float, dy: Float) {
        val indices = _selection.value.selectedIndices
        if (indices.isEmpty()) return
        if (_transformState == null) {
            val s = _selection.value
            _transformState = SelectionTransformState(
                snapshot = indices.associateWith { _primitives[it] },
                startRotation = s.rotation,
                startScaleX = s.selScaleX,
                startScaleY = s.selScaleY,
                startOffsetX = s.selOffsetX,
                startOffsetY = s.selOffsetY
            )
            _selection.value = _selection.value.copy(
                initialBounds = _selection.value.bounds,
                startRotation = s.rotation,
                startScaleX = s.selScaleX,
                startScaleY = s.selScaleY,
                startX = s.selOffsetX,
                startY = s.selOffsetY
            )
            pushUndo()
        }
        _selection.value = _selection.value.copy(
            isTransforming = true,
            selOffsetX = _selection.value.selOffsetX + dx,
            selOffsetY = _selection.value.selOffsetY + dy
        )
    }

    fun scaleSelectedPrimitives(sx: Float, sy: Float) {
        val indices = _selection.value.selectedIndices
        if (indices.isEmpty()) return
        if (_transformState == null) {
            val s = _selection.value
            _transformState = SelectionTransformState(
                snapshot = indices.associateWith { _primitives[it] },
                startRotation = s.rotation,
                startScaleX = s.selScaleX,
                startScaleY = s.selScaleY,
                startOffsetX = s.selOffsetX,
                startOffsetY = s.selOffsetY
            )
            _selection.value = _selection.value.copy(
                initialBounds = _selection.value.bounds,
                startRotation = s.rotation,
                startScaleX = s.selScaleX,
                startScaleY = s.selScaleY,
                startX = s.selOffsetX,
                startY = s.selOffsetY
            )
            pushUndo()
        }
        val curSX = _selection.value.selScaleX
        val curSY = _selection.value.selScaleY
        _selection.value = _selection.value.copy(
            isTransforming = true,
            selScaleX = (curSX * sx).coerceIn(0.1f, 10f),
            selScaleY = (curSY * sy).coerceIn(0.1f, 10f)
        )
    }

    fun rotateSelectedPrimitives(rotation: Float) {
        val indices = _selection.value.selectedIndices
        if (indices.isEmpty()) return
        if (_transformState == null) {
            val s = _selection.value
            _transformState = SelectionTransformState(
                snapshot = indices.associateWith { _primitives[it] },
                startRotation = s.rotation,
                startScaleX = s.selScaleX,
                startScaleY = s.selScaleY,
                startOffsetX = s.selOffsetX,
                startOffsetY = s.selOffsetY
            )
            _selection.value = _selection.value.copy(
                initialBounds = _selection.value.bounds,
                startRotation = s.rotation,
                startScaleX = s.selScaleX,
                startScaleY = s.selScaleY,
                startX = s.selOffsetX,
                startY = s.selOffsetY
            )
            pushUndo()
        }
        val curR = _selection.value.rotation
        _selection.value = _selection.value.copy(
            isTransforming = true,
            rotation = curR + rotation
        )
    }

    /** 选择模式中间手柄拖拽：矩形/圆拉边（对边/对点固定），直线拉端点（另一端固定）。
     *  [amount] 为世界坐标位移（PFO 已做 padding 修正，与手指 1:1）。
     *  [edgeIndex] 为 PFO 中点索引 [top,bottom,left,right]（直线为 [起点侧, 终点侧]）。 */
    fun midpointDragSelected(edgeIndex: Int, amount: Float) {
        val indices = _selection.value.selectedIndices
        if (indices.isEmpty()) return

        // 首次拖拽：推 undo + 记录快照（整个拖拽手势只记一次，保留快照防止 finalize 回退逻辑误判）
        if (_transformState == null) {
            pushUndo()
            val s = _selection.value
            _transformState = SelectionTransformState(
                snapshot = indices.associateWith { _primitives[it] },
                startRotation = s.rotation,
                startScaleX = s.selScaleX, startScaleY = s.selScaleY,
                startOffsetX = s.selOffsetX, startOffsetY = s.selOffsetY
            )
            // 同步 SelectionState 的变换起始值：预览 transformEdit 用 sel.start* 算增量，
            // 不更新会残留上一个手势的旧旋转/偏移，导致预览偏转、整体位移
            _selection.value = _selection.value.copy(
                startRotation = s.rotation,
                startScaleX = s.selScaleX, startScaleY = s.selScaleY,
                startX = s.selOffsetX, startY = s.selOffsetY
            )
            _rectMidpointActive = true
        }

        // 按类型分发：矩形→dragEdge（对边固定），圆/椭圆→dragEdge（对向顶点固定），直线→拉端点
        for (idx in indices) {
            when (val p = _primitives.getOrNull(idx)) {
                is DrawingPrimitive.RectanglePrimitive -> {
                    // PFO midpoint index → rectangle edge index:
                    // PFO: [top, bottom, left, right] = [edge0, edge2, edge3, edge1]
                    val edgeIdx = intArrayOf(0, 2, 3, 1)[edgeIndex.coerceIn(0, 3)]
                    _primitives[idx] = p.dragEdge(edgeIdx, amount)
                }
                is DrawingPrimitive.CirclePrimitive -> {
                    _primitives[idx] = p.dragEdge(edgeIndex.coerceIn(0, 3), amount)
                }
                is DrawingPrimitive.LinePrimitive -> {
                    val dx = p.endX - p.startX; val dy = p.endY - p.startY
                    val len = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (len > 0.001f) {
                        val ux = dx / len; val uy = dy / len
                        _primitives[idx] = if (edgeIndex == 0)
                            p.copy(startX = p.startX - ux * amount, startY = p.startY - uy * amount)
                        else
                            p.copy(endX = p.endX + ux * amount, endY = p.endY + uy * amount)
                    }
                }
                else -> {}
            }
        }

        // 重算选中框包围盒（跟随矩形当前位置，包围盒始终包裹元素）
        val allXs = mutableListOf<Float>(); val allYs = mutableListOf<Float>()
        for (idx in indices) {
            val p = _primitives.getOrNull(idx) ?: continue
            val b = p.computeBounds(null) ?: continue
            allXs.add(b[0]); allXs.add(b[2]); allYs.add(b[1]); allYs.add(b[3])
        }
        val newBounds = if (allXs.isNotEmpty()) Bounds(allXs.min(), allYs.min(), allXs.max(), allYs.max())
            else _selection.value.bounds ?: Bounds(0f, 0f, 100f, 100f)

        // 设置 PFO 选框匹配新矩形（重置 scale/offset，已在角点上直接烘焙）
        _selection.value = _selection.value.copy(
            bounds = newBounds,
            selScaleX = 1f, selScaleY = 1f,
            selOffsetX = 0f, selOffsetY = 0f,
            isTransforming = true
        )
        hasUnsavedChanges = true
    }

    /** 选择变换实时生效：清除快照但保留旋转（包围盒已更新）。 */
    fun finalizeSelectionTransform() {
        val ts = _transformState
        val s = _selection.value
        // 矩形中点拖拽已直接烘焙角点，跳过快照回烘焙（否则会把角点改动退回）
        if (ts != null && s.isTransforming && !_rectMidpointActive) {
            val bakeEdit = PendingEdit(
                active = true,
                rotation = s.rotation - ts.startRotation,
                scaleX = if (ts.startScaleX != 0f) s.selScaleX / ts.startScaleX else 1f,
                scaleY = if (ts.startScaleY != 0f) s.selScaleY / ts.startScaleY else 1f,
                offsetX = s.selOffsetX - ts.startOffsetX,
                offsetY = s.selOffsetY - ts.startOffsetY,
                bounds = s.bounds,
                pivotX = s.bounds?.let { (it.minX + it.maxX) / 2f } ?: 0f,
                pivotY = s.bounds?.let { (it.minY + it.maxY) / 2f } ?: 0f
            )
            for (i in s.selectedIndices) {
                val p = ts.snapshot[i] ?: continue
                if (i < _primitives.size) _primitives[i] = p.applyTransform(bakeEdit)
            }
        }
        _transformState = null
        _rectMidpointActive = false
        val s2 = _selection.value
        // 平移/缩放已烘焙进基元，bounds 必须同步缩放+平移（旋转仍由选框 rotation 承担，不动 bounds），
        // 否则 selScale/selOffset 清零后选框停留在旧尺寸/旧位置，多次缩放后图形超出 PFO
        val bakedDx = if (ts != null) s.selOffsetX - ts.startOffsetX else 0f
        val bakedDy = if (ts != null) s.selOffsetY - ts.startOffsetY else 0f
        val bakedSx = if (ts != null && ts.startScaleX != 0f) s.selScaleX / ts.startScaleX else 1f
        val bakedSy = if (ts != null && ts.startScaleY != 0f) s.selScaleY / ts.startScaleY else 1f
        val shiftedBounds = s2.bounds?.let {
            if (bakedDx != 0f || bakedDy != 0f || bakedSx != 1f || bakedSy != 1f) {
                val pcx = (it.minX + it.maxX) / 2f; val pcy = (it.minY + it.maxY) / 2f
                val hx = (it.maxX - it.minX) / 2f * bakedSx
                val hy = (it.maxY - it.minY) / 2f * bakedSy
                Bounds(pcx - hx + bakedDx, pcy - hy + bakedDy, pcx + hx + bakedDx, pcy + hy + bakedDy)
            } else it
        }
        // 松手后按烘焙结果同步 bounds（平移+缩放已折算进 shiftedBounds），不从基元重算，
        // 避免旋转后的 AABB 与选框 rotation 叠加造成二次旋转
        _selection.value = s2.copy(
            isTransforming = false,
            initialBounds = null,
            bounds = shiftedBounds,
            // Keep the accumulated rotation so the selection frame stays oriented.
            // It is NOT reset — the baked primitives carry their own intrinsic rotation
            // which computeSelectionRotation will reflect for the next fresh selection.
            rotation = if (s.isTransforming) s.rotation else computeSelectionRotation(s2.selectedIndices),
            selScaleX = 1f, selScaleY = 1f,
            selOffsetX = 0f, selOffsetY = 0f
        )
        autoSave()
    }

    // ═══════════════════════════════════════════════════════
    //  选择动作（剪切/复制/粘贴/删除）
    // ═══════════════════════════════════════════════════════

    fun executeSelectionAction(action: SelectionAction) {
        when (action) {
            SelectionAction.PROPERTIES -> {
                if (_selection.value.selectedIndices.isEmpty()) return
                _showPropertiesDlg.value = true
                return  // 不清除选择、不记撤销
            }
            SelectionAction.ARRAY -> {
                if (_selection.value.selectedIndices.isEmpty()) return
                // 每次打开重置：1行1列（无额外拷贝）+ 默认间距，避免上次阵列太远找不到手柄
                arrayRows.intValue = 1
                arrayCols.intValue = 1
                arrayGapX.floatValue = 80f
                arrayGapY.floatValue = 80f
                // 初始手柄方向：竖屏默认右下，横屏默认左下（其余阵列逻辑不变）
                arrayDirX.intValue = if (_isLandscape.value) -1 else 1
                arrayDirY.intValue = 1
                _arrayActive.value = true
                return  // enter array preview mode
            }
            else -> {}
        }
        val indices = _selection.value.selectedIndices
        if (indices.isEmpty()) return
        pushUndo()
        when (action) {
            SelectionAction.DELETE -> {
                for (i in indices.sortedDescending()) _primitives.removeAt(i)
            }
            SelectionAction.COPY -> {
                _clipboard.clear()
                _clipboard.addAll(indices.map { i -> _primitives[i].deepCopy() })
                val beforeSize = _primitives.size
                for (cp in _clipboard) _primitives.add(cp.shiftPrimitive(30f, 30f))
                val newIndices = (beforeSize until _primitives.size).toSet()
                _selection.value = SelectionState(selectedIndices = newIndices, bounds = computeSelectionBounds(newIndices),
                    rotation = computeSelectionRotation(newIndices))
                autoSave(); return
            }
            SelectionAction.CUT -> {
                _clipboard.clear()
                _clipboard.addAll(indices.map { i -> _primitives[i].deepCopy() })
                for (i in indices.sortedDescending()) _primitives.removeAt(i)
                _selection.value = SelectionState()
                autoSave(); return
            }
            SelectionAction.PASTE -> {
                val newIndices = pasteClipboard()
                if (newIndices.isNotEmpty()) {
                    _selection.value = SelectionState(selectedIndices = newIndices, bounds = computeSelectionBounds(newIndices),
                        rotation = computeSelectionRotation(newIndices))
                }
                autoSave(); return
            }
            SelectionAction.MIRROR -> {
                val bounds = computeSelectionBounds(indices) ?: return
                val cx = (bounds.minX + bounds.maxX) / 2f
                val cy = (bounds.minY + bounds.maxY) / 2f
                // 横屏模式：镜像基准线从竖直轴改为水平轴（翻转 y）
                for (i in indices) {
                    val p = _primitives[i]
                    _primitives[i] = p.mirrorPrimitive(cx, cy, flipY = _isLandscape.value)
                }
                _selection.value = _selection.value.copy(bounds = computeSelectionBounds(indices))
                autoSave(); return
            }
            else -> {}
        }
        _selection.value = SelectionState()
        autoSave()
    }

    /** 阵列预览中拖动原始元素：直接平移选中基元并同步 bounds（幽灵预览跟随） */
    private var arrayDragLast: Point2D? = null
    /** 数字/文字/区间：按下时只记位置，松手才创建基元（避免双指缩放时第一个触点闪现预览） */
    private var labelPendingStart: Point2D? = null
    private var arrayDragUndoPushed = false

    private fun arrayDragSelected(dx: Float, dy: Float) {
        val indices = _selection.value.selectedIndices
        if (indices.isEmpty()) return
        for (i in indices) {
            val p = _primitives.getOrNull(i) ?: continue
            _primitives[i] = p.shiftPrimitive(dx, dy)
        }
        val b = _selection.value.bounds
        _selection.value = _selection.value.copy(
            bounds = b?.let { Bounds(it.minX + dx, it.minY + dy, it.maxX + dx, it.maxY + dy) }
        )
        hasUnsavedChanges = true
    }

    /** Execute array: duplicate the selected primitives in rows×cols grid. */
    fun executeArray() {
        val indices = _selection.value.selectedIndices
        if (indices.isEmpty()) return
        val rows = arrayRows.intValue
        val cols = arrayCols.intValue
        val gapX = arrayGapX.floatValue
        val gapY = arrayGapY.floatValue
        val dirX = arrayDirX.intValue
        val dirY = arrayDirY.intValue
        // 1行1列 = 无额外拷贝，直接退出，不产生空撤销
        if (rows * cols <= 1) { _arrayActive.value = false; return }
        pushUndo()
        val firstBounds = computeSelectionBounds(indices) ?: return
        val baseW = firstBounds.maxX - firstBounds.minX
        val baseH = firstBounds.maxY - firstBounds.minY
        val beforeSize = _primitives.size
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (r == 0 && c == 0) continue
                val dx = c * (baseW + gapX) * dirX
                val dy = r * (baseH + gapY) * dirY
                for (i in indices.sorted()) {
                    val p = _primitives[i]
                    _primitives.add(p.deepCopy().shiftPrimitive(dx, dy))
                }
            }
        }
        // 确认后同时选中新生成的元素和原始元素
        val newIndices = (beforeSize until _primitives.size).toSet()
        val all = indices + newIndices
        _selection.value = SelectionState(selectedIndices = all, bounds = computeSelectionBounds(all))
        _arrayActive.value = false
        autoSave()
    }

    fun cancelArray() {
        _arrayActive.value = false
    }

    private fun pasteClipboard(): Set<Int> {
        if (_clipboard.isEmpty()) return emptySet()
        val beforeSize = _primitives.size
        for (cp in _clipboard) _primitives.add(cp.shiftPrimitive(30f, 30f))
        return (beforeSize until _primitives.size).toSet()
    }

    // ═══════════════════════════════════════════════════════
    //  属性更新（选中元素）
    // ═══════════════════════════════════════════════════════

    fun getFirstSelectedColor(): Color {
        val idx = _selection.value.selectedIndices.firstOrNull() ?: return Color.Black
        return _primitives.getOrNull(idx)?.color ?: Color.Black
    }
    fun getFirstSelectedLineStyle(): LineStyle {
        val idx = _selection.value.selectedIndices.firstOrNull() ?: return LineStyle()
        return (_primitives.getOrNull(idx)?.lineStyle) ?: LineStyle()
    }
    fun getFirstSelectedLineScaleFactor(): Float {
        val idx = _selection.value.selectedIndices.firstOrNull() ?: return 1f
        return (_primitives.getOrNull(idx)?.lineScaleFactor) ?: 1f
    }

    fun updateSelectedColor(color: Color) {
        val indices = _selection.value.selectedIndices; if (indices.isEmpty()) return
        pushUndo()
        for (i in indices.sortedDescending()) {
            _primitives[i] = _primitives[i].withColor(color)
        }; autoSave()
    }

    fun updateSelectedLineStyle(style: LineStyle) {
        val indices = _selection.value.selectedIndices; if (indices.isEmpty()) return
        pushUndo()
        for (i in indices.sortedDescending()) {
            _primitives[i] = when (val p = _primitives[i]) {
                is DrawingPrimitive.FreehandPath -> p.copy(lineStyle = style)
                is DrawingPrimitive.RectanglePrimitive -> p.copy(lineStyle = style)
                is DrawingPrimitive.CirclePrimitive -> p.copy(lineStyle = style)
                is DrawingPrimitive.LinePrimitive -> p.copy(lineStyle = style)
                is DrawingPrimitive.BlockRefPrimitive -> p.copy(lineStyle = style)
                else -> p
            }
        }; autoSave()
    }

    fun updateSelectedFontSize(fontSize: Float) {
        val indices = _selection.value.selectedIndices; if (indices.isEmpty()) return
        pushUndo()
        val clamped = fontSize.coerceIn(20f, 600f)
        for (i in indices.sortedDescending()) {
            when (val p = _primitives[i]) {
                is DrawingPrimitive.NumberLabelPrimitive -> _primitives[i] = p.copy(fontSize = clamped)
                is DrawingPrimitive.TextPrimitive -> _primitives[i] = p.copy(fontSize = clamped)
                is DrawingPrimitive.RangeLabelPrimitive -> _primitives[i] = p.copy(fontSize = clamped)
                else -> {}
            }
        }; autoSave()
    }

    fun updateSelectedTextContent(text: String) {
        val indices = _selection.value.selectedIndices; if (indices.isEmpty()) return
        pushUndo()
        for (i in indices.sortedDescending()) {
            val p = _primitives[i]
            if (p is DrawingPrimitive.TextPrimitive) _primitives[i] = p.copy(text = text)
        }; autoSave()
    }

    fun updateSelectedNumberValue(value: Int) {
        val indices = _selection.value.selectedIndices; if (indices.isEmpty()) return
        pushUndo()
        for (i in indices.sortedDescending()) {
            val p = _primitives[i]
            if (p is DrawingPrimitive.NumberLabelPrimitive) _primitives[i] = p.copy(value = value)
        }; autoSave()
    }

    fun updateSelectedCircled(circled: Boolean) {
        val indices = _selection.value.selectedIndices; if (indices.isEmpty()) return
        pushUndo()
        for (i in indices.sortedDescending()) {
            val p = _primitives[i]
            if (p is DrawingPrimitive.NumberLabelPrimitive) _primitives[i] = p.copy(circled = circled)
        }; autoSave()
    }

    /** 区间数字朝向：false = 朝下（正向），true = 朝左（数字下方朝屏幕左边） */
    fun updateSelectedRangeNumbersFaceLeft(faceLeft: Boolean) {
        val indices = _selection.value.selectedIndices; if (indices.isEmpty()) return
        pushUndo()
        for (i in indices.sortedDescending()) {
            val p = _primitives[i]
            if (p is DrawingPrimitive.RangeLabelPrimitive) _primitives[i] = p.copy(numbersFaceLeft = faceLeft)
        }; autoSave()
    }

    fun updateSelectedBlockScale(scale: Float) {
        val indices = _selection.value.selectedIndices; if (indices.isEmpty()) return
        pushUndo()
        val clamped = scale.coerceIn(0.1f, 10f)
        for (i in indices.sortedDescending()) {
            val p = _primitives[i]
            if (p is DrawingPrimitive.BlockRefPrimitive) _primitives[i] = p.copy(scale = clamped)
        }; autoSave()
    }

    // ═══════════════════════════════════════════════════════
    //  文字工具
    // ═══════════════════════════════════════════════════════
    // 文字在对话框确定后只记录内容/方向（setPendingTextContent / setTextOrientation），
    // 等用户点击屏幕时由 startPrimitive(ToolType.TEXT) 在点击处放置，不主动出现在屏幕上。

    // ═══════════════════════════════════════════════════════
    //  图块
    // ═══════════════════════════════════════════════════════

    fun enterBlockDraft() {
        _blockDraft.value = BlockDraft(); _currentTool.value = ToolType.FREEHAND
    }

    fun addToBlockDraft(points: List<Point2D>) {
        val draft = _blockDraft.value ?: return
        if (points.size < 2) return
        draft.primitives.add(DrawingPrimitive.FreehandPath(points = points, color = _currentColor.value,
            strokeWidth = _currentStrokeWidth.value, layerId = _activeLayerId.value))
    }

    fun saveBlockDraft(name: String): Boolean {
        val draft = _blockDraft.value ?: return false
        if (draft.primitives.isEmpty()) return false
        val id = "block_${System.currentTimeMillis()}"
        val bounds = computeBlockBounds(draft.primitives)
        _blockDefs.add(BlockDef(id = id, name = name, primitives = draft.primitives.toList(), snapPoints = draft.snapPoints.toList(), bounds = bounds))
        _blockDraft.value = null; _currentTool.value = ToolType.SELECT
        return true
    }

    fun cancelBlockDraft() { _blockDraft.value = null; _editingBlockId.value = null; _currentTool.value = ToolType.SELECT }

    // ─── DXF 导入 ───
    /** 已确认待放置的导入基元：主画布下一次点击时以点击点为包围盒中心放置 */
    private val _pendingImportPrims = mutableStateOf<List<DrawingPrimitive>?>(null)
    val pendingImportPrims: List<DrawingPrimitive>? get() = _pendingImportPrims.value
    fun setPendingImport(prims: List<DrawingPrimitive>) { _pendingImportPrims.value = prims }
    fun clearPendingImport() { _pendingImportPrims.value = null }

    /** DXF 导入到块：导入基元直接放入块编辑画布（归一到块内默认图层 1） */
    fun enterBlockEditorWithImport(prims: List<DrawingPrimitive>) {
        enterBlockEditor()
        for (p in prims) _blockEditorPrimitives.add(p.withLayerId(1))
        _currentTool.value = ToolType.SELECT
        blockEditorFitPending = true
    }

    /** 块编辑器进入时按内容适配视图（由 BlockEditorScreen 首帧布局触发并复位） */
    var blockEditorFitPending = false
    fun fitBlockEditorViewToContent(w: Float, h: Float) {
        val b = computeBlockBounds(_blockEditorPrimitives) ?: return
        val cw = maxOf(b.maxX - b.minX, 1f); val ch = maxOf(b.maxY - b.minY, 1f)
        val sp = 60f
        val ns = minOf((w - 2 * sp) / cw, (h - 2 * sp) / ch).coerceIn(0.01f, 50f)
        _blockEditorViewScale.value = ns
        _blockEditorViewX.value = w / 2f - (b.minX + b.maxX) / 2f * ns
        _blockEditorViewY.value = h / 2f - (b.minY + b.maxY) / 2f * ns
    }

    /** 在 startPrimitive 最前面调用：有待放置导入内容时，以点击点为中心放置并全选 */
    private fun placePendingImport(start: Point2D): Boolean {
        val prims = _pendingImportPrims.value ?: return false
        if (prims.isNotEmpty()) {
            // 整体包围盒中心 → 点击点
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            var has = false
            for (p in prims) {
                val b = p.computeBounds(boundsMeasurePaint) ?: continue
                has = true
                minX = minOf(minX, b[0]); minY = minOf(minY, b[1])
                maxX = maxOf(maxX, b[2]); maxY = maxOf(maxY, b[3])
            }
            val dx = if (has) start.x - (minX + maxX) / 2f else 0f
            val dy = if (has) start.y - (minY + maxY) / 2f else 0f
            pushUndo()
            val beforeSize = _primitives.size
            // 导入内容落到当前活动层（活动层必为可见层），避免 layerId 悬空导致不可见/不可擦除
            for (p in prims) _primitives.add(p.shiftPrimitive(dx, dy).withLayerId(_activeLayerId.value))
            val newIndices = (beforeSize until _primitives.size).toSet()
            _selection.value = SelectionState(
                selectedIndices = newIndices,
                bounds = computeSelectionBounds(newIndices),
                rotation = computeSelectionRotation(newIndices)
            )
            setTool(ToolType.SELECT)
            hasUnsavedChanges = true
            autoSave()
        }
        _pendingImportPrims.value = null
        return true
    }

    private fun computeBlockBounds(primitives: List<DrawingPrimitive>): Bounds? {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var hasAny = false
        for (p in primitives) {
            val b = p.computeBounds(boundsMeasurePaint) ?: continue
            hasAny = true
            minX = minOf(minX, b[0]); minY = minOf(minY, b[1])
            maxX = maxOf(maxX, b[2]); maxY = maxOf(maxY, b[3])
        }
        return if (hasAny) Bounds(minX, minY, maxX, maxY) else null
    }

    fun deleteBlockDef(blockDefId: String) {
        _blockDefs.removeAll { it.id == blockDefId }
        storageManager.deleteBlockFile(blockDefId)
    }

    fun editBlockDef(blockDefId: String): Boolean {
        val bd = _blockDefs.find { it.id == blockDefId } ?: return false
        _editingBlockId.value = blockDefId
        // 初始化块编辑器状态（不调 enterBlockEditor，因为它会清空 _blockEditorPrimitives）
        _blockEditorPrimitives.clear()
        for (p in bd.primitives) _blockEditorPrimitives.add(p)
        _blockEditorCurrent.value = null
        _blockEditorSelectedIndex.value = -1
        _blockEditorPendingEdit.value = PendingEdit()
        _blockEditorViewScale.value = 1f; _blockEditorViewX.value = 0f; _blockEditorViewY.value = 0f
        _blockEditorUndoHistory.clear(); _blockEditorRedoHistory.clear()
        _canBlockEditorUndo.value = false; _canBlockEditorRedo.value = false
        _currentTool.value = ToolType.FREEHAND
        return true
    }

    fun startBlockInsert(blockDef: BlockDef, screenW: Float = 1080f, screenH: Float = 1920f): Boolean {
        if (_pendingEdit.value.isActive()) return false
        if (blockDef.primitives.isEmpty()) return false
        // 屏幕中心转世界坐标
        val vx = (screenW / 2f - _canvasOffsetX.value) / _canvasScale.value
        val vy = (screenH / 2f - _canvasOffsetY.value) / _canvasScale.value
        // 根据块实际内容算包围盒
        val realBounds = computeBlockBounds(blockDef.primitives)
        val halfW = realBounds?.let { (it.maxX - it.minX) / 2f } ?: 50f
        val halfH = realBounds?.let { (it.maxY - it.minY) / 2f } ?: 50f
        // 渲染时块内容形心落在锚点（drawBlockRef）：反推锚点，使内容包围盒中心正对屏幕中心
        val centroid = blockContentCentroid(blockDef.primitives)
        val ax = if (realBounds != null && centroid != null)
            vx - ((realBounds.minX + realBounds.maxX) / 2f - centroid.x) else vx
        val ay = if (realBounds != null && centroid != null)
            vy - ((realBounds.minY + realBounds.maxY) / 2f - centroid.y) else vy
        _pendingEdit.value = PendingEdit(
            active = true,
            primitive = DrawingPrimitive.BlockRefPrimitive(
                blockDefId = blockDef.id, x = ax, y = ay,
                scale = 1f, rotation = 0f, snapPointIndex = -1,
                color = _currentColor.value, strokeWidth = _currentStrokeWidth.value,
                layerId = _activeLayerId.value, lineStyle = _currentLineStyle.value
            ),
            bounds = Bounds(vx - halfW, vy - halfH, vx + halfW, vy + halfH),
            scaleX = 1f, scaleY = 1f,
            // PFO 旋转轴必须给：BlockRef 在浮层里算不出元素中心，缺省会退到世界原点导致框飞出屏幕
            pivotX = vx, pivotY = vy
        )
        return true
    }

    fun saveBlockEditorBlock(name: String): Boolean {
        if (_blockEditorPrimitives.isEmpty()) return false
        val editId = _editingBlockId.value
        val bd = if (editId != null) {
            // 编辑已有块：更新原定义，保持 ID 不变（画布上 BlockRef 引用不断裂）
            val existing = _blockDefs.find { it.id == editId }
            existing?.copy(name = name, primitives = _blockEditorPrimitives.toList())
                ?: BlockDef(id = editId, name = name, primitives = _blockEditorPrimitives.toList(), snapPoints = emptyList())
        } else {
            // 新建块
            BlockDef(id = "block_${System.currentTimeMillis()}", name = name, primitives = _blockEditorPrimitives.toList(), snapPoints = emptyList())
        }
        if (editId != null) {
            // 替换旧定义
            val idx = _blockDefs.indexOfFirst { it.id == editId }
            if (idx >= 0) _blockDefs[idx] = bd
        } else {
            _blockDefs.add(bd)
        }
        _blockEditorPrimitives.clear(); _blockEditorCurrent.value = null
        _editingBlockId.value = null
        // 同步到块文件
        storageManager.saveBlockFile(serializer.blockDefToSerializable(bd))
        autoSave()
        return true
    }

    fun enterBlockEditor() {
        _blockEditorPrimitives.clear(); _blockEditorCurrent.value = null
        _blockEditorSelectedIndex.value = -1
        _blockEditorPendingEdit.value = PendingEdit()
        _editingBlockId.value = null
        _blockEditorViewScale.value = 1f; _blockEditorViewX.value = 0f; _blockEditorViewY.value = 0f
        _blockEditorUndoHistory.clear(); _blockEditorRedoHistory.clear()
        _canBlockEditorUndo.value = false; _canBlockEditorRedo.value = false
        _currentTool.value = ToolType.FREEHAND
    }

    fun blockEditorStartPrimitive(start: Point2D) {
        if (_blockEditorPendingEdit.value.isActive()) return
        if (_currentTool.value == ToolType.ERASER) {
            blockEditorPerformErasure(start)
            return
        }
        if (_currentTool.value == ToolType.SELECT) {
            // Tap to select nearest primitive
            val tol = 40f / _blockEditorViewScale.value
            var bestIdx = -1; var bestDist = Float.MAX_VALUE
            for ((i, p) in _blockEditorPrimitives.withIndex()) {
                val dist = p.distanceTo(start)
                if (dist < bestDist) { bestDist = dist; bestIdx = i }
            }
            _blockEditorSelectedIndex.value = if (bestIdx >= 0 && bestDist < tol) bestIdx else -1
            return
        }
        _blockEditorCurrent.value = when (_currentTool.value) {
            ToolType.FREEHAND -> DrawingPrimitive.FreehandPath(points = listOf(start), color = _currentColor.value,
                strokeWidth = _currentStrokeWidth.value, layerId = 1, lineStyle = _currentLineStyle.value)
            ToolType.RECTANGLE -> DrawingPrimitive.RectanglePrimitive(corners = listOf(
                Point2D(start.x, start.y), Point2D(start.x, start.y),
                Point2D(start.x, start.y), Point2D(start.x, start.y)),
                color = _currentColor.value,
                strokeWidth = _currentStrokeWidth.value, layerId = 1, lineStyle = _currentLineStyle.value)
            ToolType.CIRCLE -> DrawingPrimitive.CirclePrimitive(centerX = start.x, centerY = start.y,
                endX = start.x, endY = start.y, color = _currentColor.value,
                strokeWidth = _currentStrokeWidth.value, layerId = 1, lineStyle = _currentLineStyle.value)
            ToolType.LINE -> DrawingPrimitive.LinePrimitive(startX = start.x, startY = start.y,
                endX = start.x, endY = start.y, color = _currentColor.value,
                strokeWidth = _currentStrokeWidth.value, layerId = 1, lineStyle = _currentLineStyle.value)
            else -> null
        }
    }

    private fun blockEditorPerformErasure(point: Point2D) {
        _eraserTouchPoint.value = point
        val rSq = _eraserRadius.value * _eraserRadius.value
        val toRemove = mutableListOf<Int>()
        val toAdd = mutableListOf<DrawingPrimitive>()
        for ((i, p) in _blockEditorPrimitives.withIndex()) {
            when (p) {
                is DrawingPrimitive.FreehandPath -> {
                    if (!eraserHitBounds(p, point, rSq)) continue
                    if (shapeHitByEraser(p.points, point, rSq, isClosed = p.isClosed)) {
                        toRemove.add(i)
                        if (_fineEraseEnabled.value) {
                            val segments = splitFreehand(p.points, point, rSq, isClosed = p.isClosed)
                            for (seg in segments) {
                                if (seg.size >= 2) toAdd.add(p.copy(points = seg, isClosed = false,
                                    sharpCorners = remapSharpCorners(p.points, p.sharpCorners, seg)))
                            }
                        }
                    }
                }
                else -> {
                    val dist = p.distanceTo(point)
                    if (dist < _eraserRadius.value) {
                        toRemove.add(i)
                    }
                }
            }
        }
        if (toRemove.isNotEmpty() || toAdd.isNotEmpty()) {
            _blockEditorUndoHistory.add(_blockEditorPrimitives.toList())
            if (_blockEditorUndoHistory.size > MAX_UNDO_HISTORY) _blockEditorUndoHistory.removeAt(0)
            _blockEditorRedoHistory.clear()
            for (idx in toRemove.sortedDescending()) _blockEditorPrimitives.removeAt(idx)
            _blockEditorPrimitives.addAll(toAdd)
            _blockEditorSelectedIndex.value = -1
            _canBlockEditorUndo.value = true; _canBlockEditorRedo.value = false
        }
    }

    fun blockEditorUpdatePrimitive(point: Point2D) {
        if (_currentTool.value == ToolType.ERASER) {
            blockEditorPerformErasure(point)
            return
        }
        _blockEditorCurrent.value = when (val cp = _blockEditorCurrent.value) {
            is DrawingPrimitive.FreehandPath -> cp.copy(points = cp.points + point)
            is DrawingPrimitive.RectanglePrimitive -> {
                val sx = cp.corners[0].x; val sy = cp.corners[0].y
                if (_rectangleSquareMode.value) {
                    val dx = point.x - sx; val dy = point.y - sy
                    val side = maxOf(abs(dx), abs(dy))
                    val sgnX = if (dx > 0) 1f else if (dx < 0) -1f else 0f
                    val sgnY = if (dy > 0) 1f else if (dy < 0) -1f else 0f
                    val ex = sx + sgnX * side; val ey = sy + sgnY * side
                    cp.copy(corners = listOf(
                        Point2D(sx, sy), Point2D(ex, sy),
                        Point2D(ex, ey), Point2D(sx, ey)
                    ))
                } else cp.copy(corners = listOf(
                    Point2D(sx, sy), Point2D(point.x, sy),
                    Point2D(point.x, point.y), Point2D(sx, point.y))
                )
            }
            is DrawingPrimitive.CirclePrimitive -> {
                if (_circleCircleMode.value) {
                    val dx = point.x - cp.centerX
                    val dy = point.y - cp.centerY
                    val r = maxOf(abs(dx), abs(dy))
                    val sx = if (dx > 0) 1f else if (dx < 0) -1f else 0f
                    val sy = if (dy > 0) 1f else if (dy < 0) -1f else 0f
                    cp.copy(endX = cp.centerX + sx * r, endY = cp.centerY + sy * r)
                } else cp.copy(endX = point.x, endY = point.y)
            }
            is DrawingPrimitive.LinePrimitive -> {
                if (_lineSnapMode.value) {
                    val dx = point.x - cp.startX
                    val dy = point.y - cp.startY
                    if (abs(dx) >= abs(dy)) {
                        cp.copy(endX = point.x, endY = cp.startY)
                    } else {
                        cp.copy(endX = cp.startX, endY = point.y)
                    }
                } else cp.copy(endX = point.x, endY = point.y)
            }
            else -> _blockEditorCurrent.value
        }
    }

    fun blockEditorCommitPrimitive() {
        if (_currentTool.value == ToolType.ERASER) { _eraserTouchPoint.value = null; return }
        val cp = _blockEditorCurrent.value ?: return
        _blockEditorCurrent.value = null
        // 手绘线直接提交
        if (cp is DrawingPrimitive.FreehandPath) {
            if (cp.points.size < 3) return
            _blockEditorUndoHistory.add(_blockEditorPrimitives.toList())
            if (_blockEditorUndoHistory.size > MAX_UNDO_HISTORY) _blockEditorUndoHistory.removeAt(0)
            _blockEditorRedoHistory.clear()
            _blockEditorPrimitives.add(cp)
            _canBlockEditorUndo.value = true; _canBlockEditorRedo.value = false
        } else {
            // 其他图形进入 PendingEdit 预览
            val arr = cp.computeBounds(boundsMeasurePaint)
            val bounds = if (arr != null) Bounds(arr[0], arr[1], arr[2], arr[3]) else Bounds(0f, 0f, 100f, 100f)
            _blockEditorPendingEdit.value = PendingEdit(
                active = true, primitive = cp, bounds = bounds,
                rotation = 0f, scaleX = 1f, scaleY = 1f,
                offsetX = 0f, offsetY = 0f
            )
        }
    }

    fun blockEditorUpdatePendingOffset(dx: Float, dy: Float) {
        val pe = _blockEditorPendingEdit.value
        _blockEditorPendingEdit.value = pe.copy(offsetX = pe.offsetX + dx, offsetY = pe.offsetY + dy)
    }

    fun blockEditorUpdatePendingRotation(delta: Float) {
        val pe = _blockEditorPendingEdit.value
        _blockEditorPendingEdit.value = pe.copy(rotation = pe.rotation + delta)
    }

    fun blockEditorUpdatePendingScale(sx: Float, sy: Float) {
        val pe = _blockEditorPendingEdit.value
        _blockEditorPendingEdit.value = pe.copy(
            scaleX = (pe.scaleX * sx).coerceIn(0.1f, 10f),
            scaleY = (pe.scaleY * sy).coerceIn(0.1f, 10f)
        )
    }

    fun blockEditorConfirmPendingEdit() {
        val pe = _blockEditorPendingEdit.value
        if (!pe.isActive() || pe.primitive == null) return
        _blockEditorUndoHistory.add(_blockEditorPrimitives.toList())
        if (_blockEditorUndoHistory.size > MAX_UNDO_HISTORY) _blockEditorUndoHistory.removeAt(0)
        _blockEditorRedoHistory.clear()
        _blockEditorPrimitives.add(pe.primitive!!.applyTransform(pe))
        _blockEditorPendingEdit.value = PendingEdit()
        _canBlockEditorUndo.value = true; _canBlockEditorRedo.value = false
    }

    fun blockEditorCancelPrimitive() {
        if (_currentTool.value == ToolType.ERASER) { _eraserTouchPoint.value = null; exitTemporaryEraser(); return }
        _blockEditorCurrent.value = null
        _blockEditorPendingEdit.value = PendingEdit()
    }

    fun blockEditorUndo() {
        if (_blockEditorUndoHistory.isEmpty()) return
        _blockEditorCurrent.value = null
        _blockEditorPendingEdit.value = PendingEdit()
        _blockEditorRedoHistory.add(_blockEditorPrimitives.toList())
        _blockEditorPrimitives.clear()
        _blockEditorPrimitives.addAll(_blockEditorUndoHistory.removeLast())
        _canBlockEditorUndo.value = _blockEditorUndoHistory.isNotEmpty()
        _canBlockEditorRedo.value = true
    }

    fun blockEditorRedo() {
        if (_blockEditorRedoHistory.isEmpty()) return
        _blockEditorCurrent.value = null
        _blockEditorPendingEdit.value = PendingEdit()
        _blockEditorUndoHistory.add(_blockEditorPrimitives.toList())
        if (_blockEditorUndoHistory.size > MAX_UNDO_HISTORY) _blockEditorUndoHistory.removeAt(0)
        _blockEditorPrimitives.clear()
        _blockEditorPrimitives.addAll(_blockEditorRedoHistory.removeLast())
        _canBlockEditorUndo.value = true
        _canBlockEditorRedo.value = _blockEditorRedoHistory.isNotEmpty()
    }

    // ═══════════════════════════════════════════════════════
    //  图层管理
    // ═══════════════════════════════════════════════════════

    fun addLayer(name: String) {
        pushUndo()
        val newId = (_layers.maxOfOrNull { it.id } ?: 0) + 1
        // 新图层插入到当前选中图层之上
        val activeIdx = _layers.indexOfFirst { it.id == _activeLayerId.value }
        val insertAt = if (activeIdx >= 0) activeIdx else _layers.size
        _layers.add(insertAt, Layer(id = newId, name = name))
        _activeLayerId.value = newId
    }

    fun addLayerWithName(name: String) { addLayer(name) }

    fun duplicateLayer(id: Int) {
        val src = _layers.find { it.id == id } ?: return
        pushUndo()
        val newId = (_layers.maxOfOrNull { it.id } ?: 0) + 1
        val srcIdx = _layers.indexOfFirst { it.id == id }
        val insertAt = if (srcIdx >= 0) srcIdx else _layers.size
        // 复制源层属性（颜色/显隐/锁定）
        _layers.add(insertAt, Layer(id = newId, name = "${src.name}(1)",
            color = src.color, isVisible = src.isVisible, isLocked = src.isLocked))
        // 副本可见才切换活动层（隐藏副本保持当前活动层，避免盲画）
        if (src.isVisible) _activeLayerId.value = newId
        // 复制源图层的所有基元
        for (p in _primitives.filter { it.layerId == id }) {
            _primitives.add(p.withLayerId(newId))
        }
    }

    fun mergeLayers(srcId: Int, dstId: Int) {
        pushUndo()
        for (i in _primitives.indices) {
            val p = _primitives[i]
            if (p.layerId == srcId) _primitives[i] = p.withLayerId(dstId)
        }
        removeLayerInternal(srcId)  // 不重复记撤销
    }

    fun toggleLayerVisibility(id: Int) {
        if (id == 0) return  // 图层0不可隐藏
        val idx = _layers.indexOfFirst { it.id == id }
        if (idx < 0) return
        val layer = _layers[idx]
        if (layer.isVisible && id == _activeLayerId.value) {
            // 隐藏当前活动层：活动层切换到最近的可见层；没有其他可见层则拒绝隐藏
            val fallback = _layers.withIndex()
                .filter { it.value.id != id && it.value.isVisible }
                .minByOrNull { abs(it.index - idx) } ?: return
            pushUndo()
            _layers[idx] = layer.copy(isVisible = false)
            _activeLayerId.value = fallback.value.id
        } else {
            pushUndo()
            _layers[idx] = layer.copy(isVisible = !layer.isVisible)
        }
        // 隐藏后把该层基元从选择集剔除，避免误删/误移动不可见元素
        if (!_layers[idx].isVisible) {
            val sel = _selection.value.selectedIndices
            val pruned = sel.filter { _primitives.getOrNull(it)?.layerId != id }.toSet()
            if (pruned.size != sel.size) {
                _selection.value = _selection.value.copy(
                    selectedIndices = pruned,
                    bounds = computeSelectionBounds(pruned),
                    rotation = computeSelectionRotation(pruned)
                )
            }
        }
    }

    fun deleteLayer(id: Int) {
        if (id == 0) return  // 图层0不可删除
        if (_layers.size <= 1) return
        pushUndo()
        removeLayerInternal(id)
    }

    /** 删除图层及其基元，不记撤销（由调用方统一记） */
    private fun removeLayerInternal(id: Int) {
        _layers.removeAll { it.id == id }
        _primitives.removeAll { it.layerId == id }
        if (_activeLayerId.value == id) _activeLayerId.value = _layers.first().id
    }

    fun moveLayer(fromIndex: Int, toIndex: Int) {
        val zeroIdx = _layers.indexOfLast { it.id == 0 }
        // 不允许移动图层0，也不允许移动到图层0之后
        if (fromIndex == zeroIdx || toIndex > zeroIdx || toIndex < 0) return
        if (fromIndex == toIndex) return
        pushUndo()
        val layer = _layers.removeAt(fromIndex)
        _layers.add(toIndex, layer)
    }

    fun mergeLayerDown(id: Int) {
        if (id == 0) return  // 图层0不能向下合并
        val idx = _layers.indexOfFirst { it.id == id }
        if (idx < 0) return
        // 找到下一层（更靠近图层0的方向）
        val belowIdx = idx + 1
        if (belowIdx >= _layers.size) return
        val belowId = _layers[belowIdx].id
        // 记撤销
        pushUndo()
        // 把当前层的所有基元移到下层
        for (i in _primitives.indices) {
            val p = _primitives[i]
            if (p.layerId == id) _primitives[i] = p.withLayerId(belowId)
        }
        removeLayerInternal(id)  // 不重复记撤销
        _activeLayerId.value = belowId
    }

    fun renameLayer(id: Int, name: String) {
        pushUndo()
        val idx = _layers.indexOfFirst { it.id == id }
        if (idx >= 0) _layers[idx] = _layers[idx].copy(name = name)
    }

    // ═══════════════════════════════════════════════════════
    //  撤销 / 重做
    // ═══════════════════════════════════════════════════════

    fun undo() {
        if (undoHistory.isEmpty()) return
        _currentPrimitive.value = null; _pendingEdit.value = PendingEdit()
        redoHistory.add(takeSnapshot())
        restoreSnapshot(undoHistory.removeLast())
        _canUndo.value = undoHistory.isNotEmpty(); _canRedo.value = true
        _transformState = null; _eraserUndoPushed = false
    }

    fun redo() {
        if (redoHistory.isEmpty()) return
        _currentPrimitive.value = null; _pendingEdit.value = PendingEdit()
        undoHistory.add(takeSnapshot())
        restoreSnapshot(redoHistory.removeLast())
        _canUndo.value = true; _canRedo.value = redoHistory.isNotEmpty()
        _transformState = null; _eraserUndoPushed = false
    }

    fun clearAll() {
        if (_primitives.isEmpty()) return
        pushUndo()
        _primitives.clear()
    }

    // ═══════════════════════════════════════════════════════
    //  文件输入输出
    // ═══════════════════════════════════════════════════════

    fun autoSave() {
        val f = _documentFile ?: return
        val doc = buildDocument()
        storageManager.saveToFile(f, doc)
    }
    fun getDocumentFile(): java.io.File? = _documentFile

    /** 加载已有文档 */
    fun loadExistingDocument(doc: com.scheda.app.model.SchedaDocument, file: java.io.File) {
        val data = serializer.fromDocument(doc)
        _primitives.clear()
        for (p in data.primitives) _primitives.add(p)
        _layers.clear(); for (l in data.layers) _layers.add(l)
        _blockDefs.clear(); for (b in data.blockDefs) _blockDefs.add(b)
        _images.clear(); for (i in data.images) _images.add(i)
        imageBitmaps.clear()
        if (data.images.isNotEmpty()) decodeImageBitmapsAsync(data.images)
        // 同时从 blocks/ 文件夹加载所有块文件（去重：文件里的覆盖文档里的同名 ID）
        mergeBlockFiles()
        _canvasScale.value = doc.canvasScale; _canvasOffsetX.value = doc.canvasOffsetX; _canvasOffsetY.value = doc.canvasOffsetY
        _activeLayerId.value = doc.activeLayerId
        _documentName.value = doc.name; _documentFile = file
        applyDocumentVariables(data)
        resetPerDocumentState()
        restoreUndoHistory(doc.undoHistory, doc.undoHistoryV2)  // 恢复随文件保存的撤销存档
    }

    /** 从已转换的 DocumentData 加载（用于后台线程预转换后主线程应用） */
    fun loadExistingData(data: DocumentData, file: java.io.File) {
        _primitives.clear()
        for (p in data.primitives) _primitives.add(p)
        _layers.clear(); for (l in data.layers) _layers.add(l)
        _blockDefs.clear(); for (b in data.blockDefs) _blockDefs.add(b)
        _images.clear(); for (i in data.images) _images.add(i)
        imageBitmaps.clear()
        if (data.images.isNotEmpty()) decodeImageBitmapsAsync(data.images)
        mergeBlockFiles()
        _canvasScale.value = data.canvasScale
        _canvasOffsetX.value = data.canvasOffsetX
        _canvasOffsetY.value = data.canvasOffsetY
        _activeLayerId.value = data.activeLayerId
        _documentName.value = file.nameWithoutExtension; _documentFile = file
        applyDocumentVariables(data)
        resetPerDocumentState()
        restoreUndoHistory(data.undoHistory, data.undoHistoryV2)  // 恢复随文件保存的撤销存档
    }

    /**
     * 应用图纸级变量（每张图纸独立，文件间不共享）。
     * 旧版文件缺少这些字段时为 null，回退到默认值：
     * 线性比例=1，线宽=5，数字=1，区间数字首数字=1、尾数字为空。
     */
    private fun applyDocumentVariables(data: DocumentData) {
        // 方向预设与箭头跨距（原有文档级字段）
        _numberLabel.value = _numberLabel.value.copy(horizontalOnly = data.numberHorizontal)
        _numberLabel.value = _numberLabel.value.copy(circled = data.numberCircled)
        _textHorizontalOnly.value = data.textHorizontal
        _rangeLabel.value = _rangeLabel.value.copy(
            horizontalOnly = data.rangeHorizontal,
            reversed = data.rangeReversed,
            numbersFaceLeft = data.rangeNumbersFaceLeft
        )
        docDefaultArrowSpan = data.defaultArrowSpan
        _rangeLabel.value = _rangeLabel.value.copy(arrowSpan = data.defaultArrowSpan)
        // 线宽、线性比例、数字、区间数字首/尾数字
        _currentStrokeWidth.value = (data.strokeWidth ?: 5f).coerceIn(1f, 40f)
        _globalLineScale.value = (data.globalLineScale ?: 1f).coerceIn(0.25f, 4f)
        val numStart = data.numberStart ?: 1
        _numberLabel.value = _numberLabel.value.copy(startFrom = numStart, currentValue = numStart)
        _rangeLabel.value = _rangeLabel.value.copy(
            startValue = data.rangeStart ?: 1,
            endValue = data.rangeEnd ?: 2,
            lastEndValue = data.rangeLastEnd ?: 1
        )
    }

    /** 每个图纸文件独立的状态：撤销/重做历史、选择、变换残留（文件间不共享） */
    private fun resetPerDocumentState() {
        undoHistory.clear(); redoHistory.clear()
        _canUndo.value = false; _canRedo.value = false
        clearSelection()
        _pendingEdit.value = PendingEdit()
        _currentPrimitive.value = null
        _imageManageActive.value = false
        _imgSelectedId.value = null
    }

    private fun mergeBlockFiles() {
        val fileBlocks = storageManager.loadAllBlockFiles().mapNotNull { serializer.serializableToBlockDef(it) }
        val existingIds = _blockDefs.map { it.id }.toSet()
        for (fb in fileBlocks) {
            if (fb.id !in existingIds) {
                _blockDefs.add(fb)
            }
        }
    }

    /** 构建文档对象用于保存 */
    fun buildDocument(): com.scheda.app.model.SchedaDocument {
        return serializer.toDocument(
            primitives = _primitives.toList(),
            layers = _layers.toList(),
            blockDefs = _blockDefs.toList(),
            activeLayerId = _activeLayerId.value,
            name = _documentName.value,
            canvasOffsetX = _canvasOffsetX.value,
            canvasOffsetY = _canvasOffsetY.value,
            canvasScale = _canvasScale.value,
            numberHorizontal = _numberLabel.value.horizontalOnly,
            numberCircled = _numberLabel.value.circled,
            textHorizontal = _textHorizontalOnly.value,
            rangeHorizontal = _rangeLabel.value.horizontalOnly,
            rangeReversed = _rangeLabel.value.reversed,
            rangeNumbersFaceLeft = _rangeLabel.value.numbersFaceLeft,
            defaultArrowSpan = docDefaultArrowSpan,
            strokeWidth = _currentStrokeWidth.value,
            globalLineScale = _globalLineScale.value,
            numberStart = _numberLabel.value.startFrom,
            rangeStart = _rangeLabel.value.startValue,
            rangeEnd = _rangeLabel.value.endValue,
            rangeLastEnd = _rangeLabel.value.lastEndValue,
            undoHistoryV2 = if (undoHistory.isEmpty()) null else packUndoHistory(),
            images = _images.toList()
        )
    }

    fun exportDxf(context: Context, uri: Uri): Boolean {
        return try {
            // 临时目录：DXF 与参考图片同目录生成（IMAGE 实体是相对文件名引用）
            val tmpDir = File(context.cacheDir, "dxf_out_${System.currentTimeMillis()}").apply { mkdirs() }
            val tmpFile = File(tmpDir, "${_documentName.value.ifBlank { "export" }}.dxf")
            val res = DxfExporter.export(
                context, tmpFile.absolutePath,
                _primitives.toList(), _layers.toList(), _blockDefs.toList(), _images.toList()
            )
            if (!res.success) {
                android.util.Log.e("DxfExport", "Export failed: ${DxfExporter.getLastError()}")
                tmpDir.deleteRecursively()
                return false
            }
            val out = context.contentResolver.openOutputStream(uri)
            if (out == null) {
                android.util.Log.e("DxfExport", "openOutputStream returned null for uri=$uri")
                tmpDir.deleteRecursively()
                return false
            }
            out.use { o ->
                if (res.imageFiles.isEmpty()) {
                    tmpFile.inputStream().use { ins -> ins.copyTo(o) }
                } else {
                    // 含参考图片：打包 ZIP（解压后 DXF 与图片须放同一目录）
                    java.util.zip.ZipOutputStream(o.buffered()).use { zos ->
                        zos.putNextEntry(java.util.zip.ZipEntry(tmpFile.name))
                        tmpFile.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                        for (imgFile in res.imageFiles) {
                            zos.putNextEntry(java.util.zip.ZipEntry(imgFile.name))
                            imgFile.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }
            }
            tmpDir.deleteRecursively()
            true
        } catch (e: Exception) {
            android.util.Log.e("DxfExport", "Export failed", e)
            false
        }
    }

    fun manualSave(context: Context) { autoSave() }

    // ═══════════════════════════════════════════════════════
    //  橡皮擦
    // ═══════════════════════════════════════════════════════

    private fun performErasure(worldPoint: Point2D) {
        val rSq = _eraserRadius.value * _eraserRadius.value
        _eraserTouchPoint.value = worldPoint
        val activeLayer = _layers.find { it.id == _activeLayerId.value }
        if (activeLayer?.isLocked == true) return

        val toRemove = mutableListOf<DrawingPrimitive>()
        val toAdd = mutableListOf<DrawingPrimitive>()

        for (p in _primitives) {
            if (p.layerId != _activeLayerId.value) continue
            if (!eraserHitBounds(p, worldPoint, rSq)) continue
            when (p) {
                is DrawingPrimitive.FreehandPath -> eraseFreehand(p, worldPoint, rSq, toRemove, toAdd)
                is DrawingPrimitive.RectanglePrimitive -> eraseRect(p, worldPoint, rSq, toRemove, toAdd)
                is DrawingPrimitive.CirclePrimitive -> eraseCircle(p, worldPoint, rSq, toRemove, toAdd)
                is DrawingPrimitive.LinePrimitive -> eraseLine(p, worldPoint, rSq, toRemove, toAdd)
                is DrawingPrimitive.NumberLabelPrimitive -> {
                    // 文字/数字大小不受线型比例影响，命中检测与渲染一致（strokeScale=1）
                    if (textHitByEraser(p.x, p.y, p.value.toString(), p.fontSize, p.rotation, worldPoint, rSq, 1f)) toRemove.add(p)
                }
                is DrawingPrimitive.TextPrimitive -> {
                    if (textHitByEraser(p.x, p.y, p.text, p.fontSize, p.rotation, worldPoint, rSq, 1f)) toRemove.add(p)
                }
                is DrawingPrimitive.RangeLabelPrimitive -> eraseRangeLabel(p, worldPoint, rSq, toRemove)
                is DrawingPrimitive.BlockRefPrimitive -> eraseBlockRef(p, worldPoint, rSq, toRemove)
            }
        }

        if (toRemove.isNotEmpty() || toAdd.isNotEmpty()) {
            if (!_eraserUndoPushed) {
                pushUndo()
                _eraserUndoPushed = true
            }
            redoHistory.clear(); _canRedo.value = false
            _primitives.removeAll(toRemove)
            _primitives.addAll(toAdd)
            hasUnsavedChanges = true
        }
    }

    // ─── 橡皮擦辅助 ──────────────────────────────────────

    private fun distSq(a: Point2D, b: Point2D): Float {
        val dx = a.x - b.x; val dy = a.y - b.y
        return dx * dx + dy * dy
    }

    /** 线段与圆的交点，返回所有在 [0,1] 内的参数 t（排序后），空列表=无交点 */
    private fun segmentCircleIntersectAll(p1: Point2D, p2: Point2D, center: Point2D, rSq: Float): List<Float> {
        val dx = p2.x - p1.x; val dy = p2.y - p1.y
        val fx = p1.x - center.x; val fy = p1.y - center.y
        val a = dx * dx + dy * dy
        if (a < 0.0001f) return if (distSq(p1, center) < rSq) listOf(0f) else emptyList()
        val b = 2f * (fx * dx + fy * dy)
        val c = fx * fx + fy * fy - rSq
        val disc = b * b - 4f * a * c
        val eps = 1e-4f
        if (disc < -eps) return emptyList()
        val sqrtD = sqrt(maxOf(disc, 0f))
        val t1 = (-b - sqrtD) / (2f * a)
        val t2 = (-b + sqrtD) / (2f * a)
        // 切点（t1==t2 或近切）两个都保留：splitFreehand 会翻转两次=净零变化。
        // 若合并成一个，inside 只翻转一次，切点之后的部分会被误判为圆内而整段删除。
        // 同理，[0,1] 之外的 t 绝不能宽容钳入区间：t<0 的交点在线段起点之前，
        // 钳到 0 会多翻转一次 inside，导致整条线被误判为圆内而整根删除
        return buildList {
            if (t1 >= 0f && t1 <= 1f) add(t1)
            if (t2 >= 0f && t2 <= 1f) add(t2)
        }.sorted()
    }

    /** 在圆交点处切断路径，去掉圈内段，保留圈外段；支持开放/闭合路径 */
    private fun splitFreehand(points: List<Point2D>, center: Point2D, rSq: Float, isClosed: Boolean = false): List<List<Point2D>> {
        if (points.size < 2) return emptyList()
        val n = points.size
        val edgeCount = if (isClosed) n else n - 1
        val result = mutableListOf<List<Point2D>>()
        val current = mutableListOf<Point2D>()

        val epsSq = 4f
        fun addDistinct(pt: Point2D) {
            if (current.isEmpty() || distSq(current.last(), pt) > epsSq) current.add(pt)
        }
        fun emit() {
            if (current.size >= 2) result.add(current.toList())
            current.clear()
        }

        if (distSq(points[0], center) > rSq) current.add(points[0])

        // 每条边按交点切成子段，用子段中点是否在圆内决定保留/丢弃。
        // 不能用 inside 翻转计数：圆边界恰好穿过折线顶点时，相邻两条边会各报一个
        // 交点（前一条 t=1、后一条 t=0），inside 翻转两次净零变化，顶点之后的
        // 所有点被误判为圆内，导致整段残留被吞掉
        for (i in 0 until edgeCount) {
            val a = points[i]
            val b = if (isClosed && i == edgeCount - 1) points[0] else points[i + 1]
            val ts = segmentCircleIntersectAll(a, b, center, rSq)
            var tPrev = 0f
            for (t in ts) {
                val tm = (tPrev + t) / 2f
                val mx = a.x + tm * (b.x - a.x); val my = a.y + tm * (b.y - a.y)
                if (distSq(Point2D(mx, my), center) > rSq) {
                    // 圆外子段：当前没有进行中的保留段时，以子段起点（交点）作为段首
                    if (current.isEmpty()) current.add(Point2D(a.x + tPrev * (b.x - a.x), a.y + tPrev * (b.y - a.y)))
                    addDistinct(Point2D(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y)))
                } else {
                    emit()
                }
                tPrev = t
            }
            // 末尾子段 [tPrev, 1]
            val tm = (tPrev + 1f) / 2f
            val mx = a.x + tm * (b.x - a.x); val my = a.y + tm * (b.y - a.y)
            if (distSq(Point2D(mx, my), center) > rSq) {
                if (current.isEmpty()) current.add(Point2D(a.x + tPrev * (b.x - a.x), a.y + tPrev * (b.y - a.y)))
                addDistinct(b)
            } else {
                emit()
            }
        }
        if (current.size >= 2) {
            // 闭合路径起点已经作为第一点，避免末尾重复
            if (isClosed && current.size > 2 && distSq(current.first(), current.last()) <= epsSq) {
                current.removeAt(current.size - 1)
            }
            if (current.size >= 2) result.add(current.toList())
        }
        // Post-merge: remove near-duplicate consecutive points (within 1px)
        val deduped = result.map { seg ->
            if (seg.size <= 2) seg
            else {
                val out = mutableListOf(seg[0])
                for (k in 1 until seg.size) {
                    if (distSq(out.last(), seg[k]) > 1f) out.add(seg[k])
                }
                out
            }
        }
        // Filter out degenerate segments (碎段渲染成圆头小点"圆啾啾")。
        // 阈值随橡皮半径缩放：小于橡皮尺度的残余段基本都是切割副产品而非有意保留
        val minSegLen = maxOf(8f, sqrt(rSq) * 0.3f)
        return deduped.filter { seg ->
            if (seg.size < 2) return@filter false
            var totalLen = 0f
            for (j in 1 until seg.size) {
                val dx = seg[j].x - seg[j - 1].x; val dy = seg[j].y - seg[j - 1].y
                totalLen += sqrt(dx * dx + dy * dy)
            }
            totalLen >= minSegLen
        }
    }

    /** 通用几何形状擦除命中检测：点在圆内或任意边与圆相交 */
    private fun shapeHitByEraser(pts: List<Point2D>, center: Point2D, rSq: Float, isClosed: Boolean): Boolean {
        if (pts.isEmpty()) return false
        if (pts.any { distSq(it, center) <= rSq }) return true
        val edgeCount = if (isClosed) pts.size else pts.size - 1
        for (i in 0 until edgeCount) {
            val a = pts[i]
            val b = if (isClosed && i == edgeCount - 1) pts[0] else pts[i + 1]
            if (segmentCircleIntersectAll(a, b, center, rSq).isNotEmpty()) return true
        }
        return false
    }

    /** 旋转文字包围盒与圆相交检测 */
    private fun textHitByEraser(x: Float, y: Float, text: String, fontSize: Float, rotation: Float, center: Point2D, rSq: Float, strokeScale: Float = 1f): Boolean {
        if (text.isEmpty()) return distSq(Point2D(x, y), center) <= rSq
        boundsMeasurePaint.textSize = fontSize * 1.3f * strokeScale
        val halfW = boundsMeasurePaint.measureText(text) / 2f
        val fm = boundsMeasurePaint.fontMetrics
        val halfH = (fm.descent - fm.ascent) / 2f
        val cosR = cos(rotation); val sinR = sin(rotation)
        val corners = listOf(
            Point2D(-halfW, -halfH), Point2D(halfW, -halfH),
            Point2D(halfW, halfH), Point2D(-halfW, halfH)
        ).map { local ->
            val rx = local.x * cosR - local.y * sinR
            val ry = local.x * sinR + local.y * cosR
            Point2D(x + rx, y + ry)
        }
        return shapeHitByEraser(corners, center, rSq, isClosed = true)
    }

    // ─── 各元素类型的独立擦除方法 ─────────────────────

    private fun eraseFreehand(p: DrawingPrimitive.FreehandPath, center: Point2D, rSq: Float,
                              toRemove: MutableList<DrawingPrimitive>, toAdd: MutableList<DrawingPrimitive>) {
        if (shapeHitByEraser(p.points, center, rSq, isClosed = p.isClosed)) {
            toRemove.add(p)
            if (_fineEraseEnabled.value) {
                val segments = splitFreehand(p.points, center, rSq, isClosed = p.isClosed)
                for (seg in segments) {
                    if (seg.size >= 2) toAdd.add(p.copy(points = seg, isClosed = false,
                        sharpCorners = remapSharpCorners(p.points, p.sharpCorners, seg)))
                }
            }
        }
    }

    /** 切割后按点坐标重映射尖角索引：splitFreehand 会插入交点/裁掉点，旧索引指向的点已错位 */
    private fun remapSharpCorners(oldPoints: List<Point2D>, oldSharp: Set<Int>, seg: List<Point2D>): Set<Int> {
        if (oldSharp.isEmpty()) return emptySet()
        val sharpPts = oldSharp.mapNotNullTo(HashSet<Point2D>()) { oldPoints.getOrNull(it) }
        if (sharpPts.isEmpty()) return emptySet()
        return seg.indices.filterTo(HashSet<Int>()) { seg[it] in sharpPts }
    }

    private fun eraseRect(p: DrawingPrimitive.RectanglePrimitive, center: Point2D, rSq: Float,
                          toRemove: MutableList<DrawingPrimitive>, toAdd: MutableList<DrawingPrimitive>) {
        val pts = rectToPoints(p)
        if (shapeHitByEraser(pts, center, rSq, isClosed = true)) {
            toRemove.add(p)
            if (_fineEraseEnabled.value) {
                val segments = splitFreehand(pts, center, rSq, isClosed = true)
                val xs = p.corners.map { it.x }; val ys = p.corners.map { it.y }
                val w = xs.max() - xs.min(); val h = ys.max() - ys.min()
                val segX = maxOf(4, minOf(200, (w / 20f + 0.5f).toInt()))
                val segY = maxOf(4, minOf(200, (h / 20f + 0.5f).toInt()))
                for (seg in segments) {
                    if (seg.size >= 2) {
                        val cornerSet = mutableSetOf<Int>()
                        for (k in seg.indices) {
                            val pt = seg[k]
                            if ((abs(pt.x - pts[0].x) < 0.01f && abs(pt.y - pts[0].y) < 0.01f) ||
                                (abs(pt.x - pts[segX].x) < 0.01f && abs(pt.y - pts[segX].y) < 0.01f) ||
                                (abs(pt.x - pts[segX + segY].x) < 0.01f && abs(pt.y - pts[segX + segY].y) < 0.01f) ||
                                (abs(pt.x - pts[2 * segX + segY].x) < 0.01f && abs(pt.y - pts[2 * segX + segY].y) < 0.01f)) {
                                cornerSet.add(k)
                            }
                        }
                        toAdd.add(DrawingPrimitive.FreehandPath(points = seg, color = p.color, strokeWidth = p.strokeWidth, layerId = p.layerId, lineStyle = p.lineStyle, lineScaleFactor = p.lineScaleFactor, sharpCorners = cornerSet))
                    }
                }
            }
        }
    }

    private fun eraseCircle(p: DrawingPrimitive.CirclePrimitive, center: Point2D, rSq: Float,
                            toRemove: MutableList<DrawingPrimitive>, toAdd: MutableList<DrawingPrimitive>) {
        val pts = circleToPoints(p)
        if (shapeHitByEraser(pts, center, rSq, isClosed = true)) {
            toRemove.add(p)
            if (_fineEraseEnabled.value) {
                val segments = splitFreehand(pts, center, rSq, isClosed = true)
                for (seg in segments) {
                    if (seg.size >= 2) toAdd.add(DrawingPrimitive.FreehandPath(points = seg, color = p.color, strokeWidth = p.strokeWidth, layerId = p.layerId, lineStyle = p.lineStyle, lineScaleFactor = p.lineScaleFactor))
                }
            }
        }
    }

    private fun eraseLine(p: DrawingPrimitive.LinePrimitive, center: Point2D, rSq: Float,
                          toRemove: MutableList<DrawingPrimitive>, toAdd: MutableList<DrawingPrimitive>) {
        val pts = lineToPoints(p)
        if (shapeHitByEraser(pts, center, rSq, isClosed = false)) {
            toRemove.add(p)
            if (_fineEraseEnabled.value) {
                val segments = splitFreehand(pts, center, rSq, isClosed = false)
                for (seg in segments) {
                    if (seg.size >= 2) toAdd.add(DrawingPrimitive.FreehandPath(points = seg, color = p.color, strokeWidth = p.strokeWidth, layerId = p.layerId, lineStyle = p.lineStyle, lineScaleFactor = p.lineScaleFactor))
                }
            }
        }
    }

    private fun eraseRangeLabel(p: DrawingPrimitive.RangeLabelPrimitive, center: Point2D, rSq: Float,
                                toRemove: MutableList<DrawingPrimitive>) {
        val arrowLen = maxOf(80f * p.arrowSpan, 20f)
        // 区间数字大小不受线型比例影响，命中检测与渲染一致
        boundsMeasurePaint.textSize = p.fontSize * 1.3f
        val textHalfW = maxOf(boundsMeasurePaint.measureText(p.startValue.toString()),
            boundsMeasurePaint.measureText(p.endValue.toString())) / 2f
        val reach = arrowLen / 2f + textHalfW + p.fontSize * 0.5f
        val cosR = cos(p.rotation); val sinR = sin(p.rotation)
        val hx = reach * cosR; val hy = reach * sinR
        val pts = listOf(Point2D(p.x - hx, p.y - hy), Point2D(p.x + hx, p.y + hy))
        if (shapeHitByEraser(pts, center, rSq, isClosed = false)) toRemove.add(p)
    }

    private fun eraseBlockRef(p: DrawingPrimitive.BlockRefPrimitive, center: Point2D, rSq: Float,
                              toRemove: MutableList<DrawingPrimitive>) {
        val r = sqrt(rSq)
        val blockDef = _blockDefs.find { it.id == p.blockDefId }
        val bounds = blockDef?.bounds
        if (bounds != null) {
            val bw = (bounds.maxX - bounds.minX) * p.scale
            val bh = (bounds.maxY - bounds.minY) * p.scale
            val hx = bw / 2f; val hy = bh / 2f
            // Transform eraser center into block's local coordinate system (account for rotation)
            val cosR = cos(-p.rotation); val sinR = sin(-p.rotation)
            val dx0 = center.x - p.x; val dy0 = center.y - p.y
            val localX = dx0 * cosR - dy0 * sinR
            val localY = dx0 * sinR + dy0 * cosR
            // Point-to-AABB surface distance in local space
            val dx = abs(localX) - hx
            val dy = abs(localY) - hy
            val dist = if (dx <= 0f && dy <= 0f) 0f
            else if (dx > 0f && dy > 0f) sqrt(dx * dx + dy * dy)
            else if (dx > 0f) dx else dy
            if (dist <= r) toRemove.add(p)
        } else if (blockDef != null) {
            // Fallback: compute bounds from block primitives
            val realBounds = computeBlockBounds(blockDef.primitives)
            if (realBounds != null) {
                val bw = (realBounds.maxX - realBounds.minX) * p.scale
                val bh = (realBounds.maxY - realBounds.minY) * p.scale
                val hx = bw / 2f; val hy = bh / 2f
                val cosR = cos(-p.rotation); val sinR = sin(-p.rotation)
                val dx0 = center.x - p.x; val dy0 = center.y - p.y
                val localX = dx0 * cosR - dy0 * sinR
                val localY = dx0 * sinR + dy0 * cosR
                val dx = abs(localX) - hx
                val dy = abs(localY) - hy
                val dist = if (dx <= 0f && dy <= 0f) 0f
                else if (dx > 0f && dy > 0f) sqrt(dx * dx + dy * dy)
                else if (dx > 0f) dx else dy
                if (dist <= r) toRemove.add(p)
            } else {
                if (distSq(Point2D(p.x, p.y), center) <= rSq) toRemove.add(p)
            }
        } else {
            if (distSq(Point2D(p.x, p.y), center) <= rSq) toRemove.add(p)
        }
    }

    /** 包围盒快速排除：圆心到基元包围盒的最短距离如果大于半径则跳过 */
    private fun eraserHitBounds(p: DrawingPrimitive, center: Point2D, rSq: Float): Boolean {
        val r = sqrt(rSq)
        val b = p.computeBounds(boundsMeasurePaint) ?: return true
        // Add padding for eraser detection (strokeWidth / 2f for most types)
        val pad = when (p) {
            is DrawingPrimitive.LinePrimitive -> p.strokeWidth / 2f + 10f
            is DrawingPrimitive.FreehandPath -> p.strokeWidth / 2f
            else -> p.strokeWidth / 2f
        }
        val bounds = Bounds(b[0] - pad, b[1] - pad, b[2] + pad, b[3] + pad)
        val closestX = center.x.coerceIn(bounds.minX - r, bounds.maxX + r)
        val closestY = center.y.coerceIn(bounds.minY - r, bounds.maxY + r)
        return distSq(Point2D(closestX, closestY), center) <= rSq
    }

    /** 矩形轮廓采样 — 先旋转4角再在每条边上等距插点，保证旋转精度 */
    private fun rectToPoints(r: DrawingPrimitive.RectanglePrimitive): List<Point2D> {
        val c = r.corners
        val xs = c.map { it.x }; val ys = c.map { it.y }
        val w = xs.max() - xs.min(); val h = ys.max() - ys.min()
        if (w < 0.01f && h < 0.01f) return listOf(c.first())
        if (!w.isFinite() || !h.isFinite() || w > 1e8f || h > 1e8f) return emptyList()
        val segX = maxOf(4, minOf(200, (w / 20f + 0.5f).toInt()))
        val segY = maxOf(4, minOf(200, (h / 20f + 0.5f).toInt()))
        val pts = mutableListOf<Point2D>()
        // 上边 c[0]→c[1]
        for (i in 0 until segX) {
            val t = i.toFloat() / segX
            pts.add(Point2D(c[0].x + t * (c[1].x - c[0].x), c[0].y + t * (c[1].y - c[0].y)))
        }
        // 右边 c[1]→c[2]
        for (i in 0 until segY) {
            val t = i.toFloat() / segY
            pts.add(Point2D(c[1].x + t * (c[2].x - c[1].x), c[1].y + t * (c[2].y - c[1].y)))
        }
        // 下边 c[2]→c[3]
        for (i in 0 until segX) {
            val t = i.toFloat() / segX
            pts.add(Point2D(c[2].x + t * (c[3].x - c[2].x), c[2].y + t * (c[3].y - c[2].y)))
        }
        // 左边 c[3]→c[0]
        for (i in 0 until segY) {
            val t = i.toFloat() / segY
            pts.add(Point2D(c[3].x + t * (c[0].x - c[3].x), c[3].y + t * (c[0].y - c[3].y)))
        }
        // 应用矩形自身旋转（存储角点为轴对齐 AABB，旋转在 rotation 字段）：
        // 不旋转的话，精细擦除旋转矩形后剩余线段的角度会丢失（变回水平/竖直）
        if (abs(r.rotation) > 0.001f) {
            val cx = (xs.min() + xs.max()) / 2f; val cy = (ys.min() + ys.max()) / 2f
            val cosR = cos(r.rotation); val sinR = sin(r.rotation)
            return pts.map { pt ->
                val dx = pt.x - cx; val dy = pt.y - cy
                Point2D(cx + dx * cosR - dy * sinR, cy + dx * sinR + dy * cosR)
            }
        }
        return pts
    }

    /** 圆形轮廓采样（32 段，不重复首尾） */
    private fun circleToPoints(c: DrawingPrimitive.CirclePrimitive): List<Point2D> {
        val rx = c.radiusX; val ry = c.radiusY
        val cosR = kotlin.math.cos(c.rotation); val sinR = kotlin.math.sin(c.rotation)
        return (0 until 32).map { i ->
            val angle = 2f * Math.PI.toFloat() * i / 32f
            val lx = rx * cos(angle); val ly = ry * sin(angle)
            Point2D(c.centerX + lx * cosR - ly * sinR, c.centerY + lx * sinR + ly * cosR)
        }
    }

    /** 直线的两个端点 */
    private fun lineToPoints(l: DrawingPrimitive.LinePrimitive): List<Point2D> =
        listOf(Point2D(l.startX, l.startY), Point2D(l.endX, l.endY))

    // ═══════════════════════════════════════════════════════
    //  会话持久化：工具设置 + 最后文件
    // ═══════════════════════════════════════════════════════

    init { restoreSettings() }

    fun saveSettings() {
        sessionPrefs.edit().apply {
            putString(KEY_TOOL, _currentTool.value.name)
            putInt(KEY_COLOR, _currentColor.value.toArgb())
            putFloat(KEY_STROKE, _currentStrokeWidth.value)
            putString(KEY_LINE_TYPE, _currentLineStyle.value.type.name)
            putFloat(KEY_LINE_DASH, _currentLineStyle.value.dashLength)
            putFloat(KEY_LINE_GAP, _currentLineStyle.value.gapLength)
            putFloat(KEY_GLOBAL_SCALE, _globalLineScale.value)
            putFloat(KEY_ERASER_R, _eraserRadius.value)
            putFloat(KEY_CANVAS_SCALE, _canvasScale.value)
            putFloat(KEY_CANVAS_OX, _canvasOffsetX.value)
            putFloat(KEY_CANVAS_OY, _canvasOffsetY.value)
            putFloat(KEY_TEXT_FS, _lastTextFontSize)
            putFloat(KEY_NUM_FS, _lastNumberFontSize)
            putInt(KEY_NUM_START, _numberLabel.value.startFrom)
            putString(KEY_PENDING_TEXT, _pendingTextContent.value)
            putBoolean(KEY_CONSTRAINT, _constraintEnabled.value)
            putBoolean(KEY_SNAP, _snapEnabled.value)
            putBoolean(KEY_TEXT_HORIZONTAL, _textHorizontalOnly.value)
            putBoolean(KEY_NUM_HORIZONTAL, _numberLabel.value.horizontalOnly)
            putBoolean(KEY_NUM_CIRCLED, _numberLabel.value.circled)
            putFloat(KEY_RANGE_ARROW_SPAN, _rangeLabel.value.arrowSpan)
            putFloat(KEY_RANGE_FS, _rangeLabel.value.fontSize)
            putBoolean(KEY_RANGE_HORIZONTAL, _rangeLabel.value.horizontalOnly)
            putBoolean(KEY_RANGE_REVERSED, _rangeLabel.value.reversed)
            putBoolean(KEY_RANGE_NUMBERS_FACE_LEFT, _rangeLabel.value.numbersFaceLeft)
            putBoolean(KEY_QUICK_ERASE, _quickEraseEnabled.value)
            putBoolean(KEY_FINE_ERASE, _fineEraseEnabled.value)
            putBoolean(KEY_RECT_SQUARE, _rectangleSquareMode.value)
            putBoolean(KEY_CIRCLE_MODE, _circleCircleMode.value)
            putBoolean(KEY_LINE_SNAP, _lineSnapMode.value)
            putBoolean(KEY_LANDSCAPE, _isLandscape.value)
            putInt(KEY_NARR_START, _numArrayLabel.value.startValue)
            putInt(KEY_NARR_END, _numArrayLabel.value.endValue)
            putFloat(KEY_NARR_FS, _numArrayLabel.value.fontSize)
            putFloat(KEY_NARR_GAP, _numArrayLabel.value.gap)
            putFloat(KEY_NARR_ROTATION, _numArrayLabel.value.rotationDeg)
            putBoolean(KEY_NARR_CIRCLED, _numArrayLabel.value.circled)
            apply()
        }
    }

    private fun restoreSettings() {
        val p = sessionPrefs ?: return
        _currentTool.value = try { ToolType.valueOf(p.getString(KEY_TOOL, ToolType.FREEHAND.name) ?: ToolType.FREEHAND.name) } catch (_: Exception) { ToolType.FREEHAND }
        _currentColor.value = Color(p.getInt(KEY_COLOR, Color.Black.toArgb()))
        _currentStrokeWidth.value = p.getFloat(KEY_STROKE, 5f)
        _currentLineStyle.value = LineStyle(
            type = try { LineType.valueOf(p.getString(KEY_LINE_TYPE, LineType.SOLID.name) ?: LineType.SOLID.name) } catch (_: Exception) { LineType.SOLID },
            dashLength = p.getFloat(KEY_LINE_DASH, 12f),
            gapLength = p.getFloat(KEY_LINE_GAP, 6f)
        )
        _globalLineScale.value = p.getFloat(KEY_GLOBAL_SCALE, 1f)
        _eraserRadius.value = p.getFloat(KEY_ERASER_R, 200f)
        _canvasScale.value = p.getFloat(KEY_CANVAS_SCALE, 1f)
        _canvasOffsetX.value = p.getFloat(KEY_CANVAS_OX, 0f)
        _canvasOffsetY.value = p.getFloat(KEY_CANVAS_OY, 0f)
        _lastTextFontSize = p.getFloat(KEY_TEXT_FS, 40f)
        _lastNumberFontSize = p.getFloat(KEY_NUM_FS, 30f)
        val startVal = p.getInt(KEY_NUM_START, 1)
        _numberLabel.value = _numberLabel.value.copy(startFrom = startVal, currentValue = startVal)
        _pendingTextContent.value = p.getString(KEY_PENDING_TEXT, "") ?: ""
        _constraintEnabled.value = p.getBoolean(KEY_CONSTRAINT, false)
        _snapEnabled.value = p.getBoolean(KEY_SNAP, false)
        _textHorizontalOnly.value = p.getBoolean(KEY_TEXT_HORIZONTAL, false)
        _numberLabel.value = _numberLabel.value.copy(horizontalOnly = p.getBoolean(KEY_NUM_HORIZONTAL, true))
        _numberLabel.value = _numberLabel.value.copy(circled = p.getBoolean(KEY_NUM_CIRCLED, false))
        _rangeLabel.value = _rangeLabel.value.copy(arrowSpan = p.getFloat(KEY_RANGE_ARROW_SPAN, 1f),
            fontSize = p.getFloat(KEY_RANGE_FS, 30f),
            horizontalOnly = p.getBoolean(KEY_RANGE_HORIZONTAL, true),
            reversed = p.getBoolean(KEY_RANGE_REVERSED, false),
            numbersFaceLeft = p.getBoolean(KEY_RANGE_NUMBERS_FACE_LEFT, false))
        _quickEraseEnabled.value = p.getBoolean(KEY_QUICK_ERASE, true)
        _fineEraseEnabled.value = p.getBoolean(KEY_FINE_ERASE, true)
        _rectangleSquareMode.value = p.getBoolean(KEY_RECT_SQUARE, false)
        _circleCircleMode.value = p.getBoolean(KEY_CIRCLE_MODE, false)
        _lineSnapMode.value = p.getBoolean(KEY_LINE_SNAP, false)
        _isLandscape.value = p.getBoolean(KEY_LANDSCAPE, false)
        _numArrayLabel.value = NumArrayLabel(
            startValue = p.getInt(KEY_NARR_START, 1),
            endValue = p.getInt(KEY_NARR_END, 5),
            fontSize = p.getFloat(KEY_NARR_FS, 30f),
            gap = p.getFloat(KEY_NARR_GAP, 100f),
            rotationDeg = p.getFloat(KEY_NARR_ROTATION, if (p.getBoolean(KEY_NARR_VERTICAL, false)) 90f else 0f),
            circled = p.getBoolean(KEY_NARR_CIRCLED, false))
    }
}
