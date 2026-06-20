package com.scheda.app.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import com.scheda.app.export.DxfWriter
import com.scheda.app.file.DocumentData
import com.scheda.app.file.RecoveryManager
import com.scheda.app.file.SchedaSerializer
import com.scheda.app.file.StorageManager
import com.scheda.app.model.*
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
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
    }

    private val sessionPrefs = appContext.getSharedPreferences(PREFS_SESSION, Context.MODE_PRIVATE)

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

    // ─── 工具状态 ─────────────────────────────────────────
    private val _currentTool = mutableStateOf(ToolType.FREEHAND)
    val currentTool: ToolType get() = _currentTool.value

    private val _currentColor = mutableStateOf(Color.Black)
    val currentColor: Color get() = _currentColor.value

    private val _currentStrokeWidth = mutableFloatStateOf(4f)
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
        val numberLabel: NumberLabel
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
        numberLabel = _numberLabel.value
    )

    private fun restoreSnapshot(s: UndoSnapshot) {
        _primitives.clear(); _primitives.addAll(s.primitives)
        _layers.clear(); _layers.addAll(s.layers)
        _blockDefs.clear(); _blockDefs.addAll(s.blockDefs)
        _canvasOffsetX.value = s.canvasOffsetX
        _canvasOffsetY.value = s.canvasOffsetY
        _canvasScale.value = s.canvasScale
        _activeLayerId.value = s.activeLayerId
        _lastTextFontSize = s.lastTextFontSize
        _lastNumberFontSize = s.lastNumberFontSize
        _numberLabel.value = s.numberLabel
    }

    private fun pushUndo() {
        undoHistory.add(takeSnapshot())
        _canUndo.value = true
        redoHistory.clear()
        _canRedo.value = false
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
    private var _selectionSnapshot: Map<Int, DrawingPrimitive>? = null
    private var _scaleAccumAvg: Float = 1f

    // ─── 属性对话框 ─────────────────────────────────────
    private val _showPropertiesDlg = mutableStateOf(false)
    val showPropertiesDlg: Boolean get() = _showPropertiesDlg.value
    fun dismissPropertiesDialog() { _showPropertiesDlg.value = false }

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
        // 有待确认图形时，不允许切换到其他工具
        if (_pendingEdit.value.isActive() && tool != _currentTool.value) return
        _previousTool.value = _currentTool.value
        _currentTool.value = tool
        _isTemporaryEraser.value = false  // 手动切工具退出临时模式
        // 切换工具时清除选择
        if (tool != ToolType.SELECT && _selection.value.selectedIndices.isNotEmpty()) {
            clearSelection()
        }
        saveSettings()
    }
    fun setColor(color: Color) { _currentColor.value = color; saveSettings() }
    fun setStrokeWidth(w: Float) { _currentStrokeWidth.value = w.coerceIn(1f, 40f); saveSettings() }
    fun setLineStyle(style: LineStyle) { _currentLineStyle.value = style; saveSettings() }
    fun setActiveLayer(id: Int) { _activeLayerId.value = id }
    fun setGlobalLineScale(s: Float) { _globalLineScale.value = s.coerceIn(0.25f, 4f); saveSettings() }
    fun setEraserRadius(displayR: Float) { _eraserRadius.value = (displayR * 10f).coerceIn(50f, 1000f); saveSettings() }
    fun toggleConstraint() { _constraintEnabled.value = !_constraintEnabled.value; saveSettings() }
    fun toggleSnap() { _snapEnabled.value = !_snapEnabled.value; saveSettings() }
    fun setPendingTextContent(text: String) { _pendingTextContent.value = text }

    fun setNumberLabelStart(value: Int) {
        _numberLabel.value = _numberLabel.value.copy(startFrom = value, currentValue = value)
    }
    fun setNumberFontSize(size: Float) {
        _numberLabel.value = _numberLabel.value.copy(fontSize = size.coerceIn(30f, 200f))
    }
    fun updateNumberLabelValue(newValue: Int) {
        _numberLabel.value = _numberLabel.value.copy(currentValue = newValue)
    }
    fun getLastTextFontSize(): Float = _lastTextFontSize
    fun getLastNumberFontSize(): Float = _lastNumberFontSize

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
        _rangeLabel.value = _rangeLabel.value.copy(fontSize = size.coerceIn(20f, 200f))
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
                }
                is DrawingPrimitive.NumberLabelPrimitive -> {
                    val newHoriz = !prim.horizontalOnly
                    val newRot = if (newHoriz) 0f else (Math.PI / 2).toFloat()
                    _pendingEdit.value = pe.copy(
                        primitive = prim.copy(horizontalOnly = newHoriz, rotation = newRot),
                        bounds = swapBounds(pe.bounds))
                }
                is DrawingPrimitive.RangeLabelPrimitive -> {
                    val newHoriz = !prim.horizontalOnly
                    val newRot = if (newHoriz) 0f else (Math.PI / 2).toFloat()
                    _pendingEdit.value = pe.copy(
                        primitive = prim.copy(horizontalOnly = newHoriz, rotation = newRot),
                        bounds = swapBounds(pe.bounds))
                }
                else -> {}
            }
        } else {
            if (_currentTool.value == ToolType.RANGE) {
                val newValue = !_rangeLabel.value.horizontalOnly
                _rangeLabel.value = _rangeLabel.value.copy(horizontalOnly = newValue)
            } else {
                val newValue = !_numberLabel.value.horizontalOnly
                _numberLabel.value = _numberLabel.value.copy(horizontalOnly = newValue)
            }
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
        val newScale = (oldScale * zoom).coerceIn(0.05f, 50f)
        if (zoom != 1f) {
            val ratio = newScale / oldScale
            _canvasOffsetX.value = centroid.x - (centroid.x - _canvasOffsetX.value) * ratio
            _canvasOffsetY.value = centroid.y - (centroid.y - _canvasOffsetY.value) * ratio
        }
        _canvasOffsetX.value += pan.x
        _canvasOffsetY.value += pan.y
        _canvasScale.value = newScale
    }

    fun fitToScreen(screenWidth: Float, screenHeight: Float) {
        if (_primitives.isEmpty() && _pendingEdit.value.primitive == null) {
            _canvasScale.value = 1f; _canvasOffsetX.value = 0f; _canvasOffsetY.value = 0f; return
        }
        val allPrimitives = _primitives.toList()
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in allPrimitives) {
            val b = computeBounds(p) ?: continue
            minX = minOf(minX, b[0], b[2]); minY = minOf(minY, b[1], b[3])
            maxX = maxOf(maxX, b[0], b[2]); maxY = maxOf(maxY, b[1], b[3])
        }
        _pendingEdit.value.primitive?.let { p ->
            val b = computeBounds(p) ?: return@let
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

    private fun computeBounds(p: DrawingPrimitive): FloatArray? {
        return when (p) {
            is DrawingPrimitive.FreehandPath -> {
                if (p.points.size < 2) return null
            val xs = p.points.map { it.x }; val ys = p.points.map { it.y }
            floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
        }
        is DrawingPrimitive.RectanglePrimitive -> {
            val left = minOf(p.startX, p.endX); val top = minOf(p.startY, p.endY)
            val right = maxOf(p.startX, p.endX); val bottom = maxOf(p.startY, p.endY)
            if (kotlin.math.abs(p.rotation) < 0.001f) {
                floatArrayOf(left, top, right, bottom)
            } else {
                val cx = (left + right) / 2f; val cy = (top + bottom) / 2f
                val hw = (right - left) / 2f; val hh = (bottom - top) / 2f
                val cosR = kotlin.math.cos(p.rotation); val sinR = kotlin.math.sin(p.rotation)
                fun rot(wx: Float, wy: Float): Point2D {
                    val dx = wx - cx; val dy = wy - cy
                    return Point2D(cx + dx * cosR - dy * sinR, cy + dx * sinR + dy * cosR)
                }
                val pts = listOf(rot(cx - hw, cy - hh), rot(cx + hw, cy - hh),
                    rot(cx + hw, cy + hh), rot(cx - hw, cy + hh))
                val xs = pts.map { it.x }; val ys = pts.map { it.y }
                floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
            }
        }
        is DrawingPrimitive.CirclePrimitive -> {
            val r = maxOf(abs(p.endX - p.centerX), abs(p.endY - p.centerY))
            floatArrayOf(p.centerX - r, p.centerY - r, p.centerX + r, p.centerY + r)
        }
        is DrawingPrimitive.LinePrimitive -> {
            val xs = listOf(p.startX, p.endX); val ys = listOf(p.startY, p.endY)
            val pad = p.strokeWidth / 2f + 10f
            floatArrayOf(xs.min() - pad, ys.min() - pad, xs.max() + pad, ys.max() + pad)
        }
        is DrawingPrimitive.NumberLabelPrimitive -> {
            val numChars = p.value.toString().length.coerceAtLeast(1)
            val hw = p.fontSize * 0.3f * numChars; val hh = p.fontSize * 0.4f
            floatArrayOf(p.x - hw, p.y - hh, p.x + hw, p.y + hh)
        }
        is DrawingPrimitive.TextPrimitive -> {
            val numChars = p.text.length.coerceAtLeast(1)
            val hw = p.fontSize * 0.35f * numChars; val hh = p.fontSize * 0.5f
            floatArrayOf(p.x - hw, p.y - hh, p.x + hw, p.y + hh)
        }
        is DrawingPrimitive.RangeLabelPrimitive -> {
            val arrowLen = maxOf(80f * p.arrowSpan, 20f)
            val maxDigits = maxOf(p.startValue.toString().length, p.endValue.toString().length).coerceAtLeast(1)
            val numWidth = p.fontSize * 0.35f * maxDigits
            val gap = p.fontSize * 1.0f
            val halfW = numWidth + arrowLen / 2f + gap
            val halfH = p.fontSize * 0.6f
            if (kotlin.math.abs(p.rotation) > 0.001f && !p.horizontalOnly) {
                // Vertical mode: swap width and height
                floatArrayOf(p.x - halfH, p.y - halfW, p.x + halfH, p.y + halfW)
            } else {
                floatArrayOf(p.x - halfW, p.y - halfH, p.x + halfW, p.y + halfH)
            }
        }
        is DrawingPrimitive.BlockRefPrimitive -> {
            val h = 50f * p.scale
            floatArrayOf(p.x - h, p.y - h, p.x + h, p.y + h)
        }
        }
    }

    // ═══════════════════════════════════════════════════════
    //  绘图操作
    // ═══════════════════════════════════════════════════════

    fun startPrimitive(start: Point2D) {
        val activeLayer = _layers.find { it.id == _activeLayerId.value }
        if (activeLayer?.isLocked == true) return
        if (_pendingEdit.value.isActive()) return

        when (_currentTool.value) {
            ToolType.ERASER -> { performErasure(start); return }
            ToolType.SELECT -> { startSelection(start); return }
            ToolType.ANNOTATE -> {
                val nl = _numberLabel.value
                val initRotation = if (nl.horizontalOnly) 0f else (Math.PI / 2).toFloat()
                _currentPrimitive.value = DrawingPrimitive.NumberLabelPrimitive(
                    value = nl.currentValue, x = start.x, y = start.y,
                    fontSize = nl.fontSize, color = _currentColor.value,
                    strokeWidth = _currentStrokeWidth.value, layerId = _activeLayerId.value,
                    horizontalOnly = nl.horizontalOnly, rotation = initRotation
                )
                return
            }
            ToolType.TEXT -> {
                val txt = _pendingTextContent.value.ifBlank { "文本" }
                _currentPrimitive.value = DrawingPrimitive.TextPrimitive(
                    text = txt, x = start.x, y = start.y,
                    fontSize = _lastTextFontSize, color = _currentColor.value,
                    strokeWidth = _currentStrokeWidth.value, layerId = _activeLayerId.value,
                    horizontalOnly = false, rotation = (Math.PI / 2).toFloat()
                )
                return
            }
            ToolType.RANGE -> {
                val rl = _rangeLabel.value
                val initRotation = if (rl.horizontalOnly) 0f else (Math.PI / 2).toFloat()
                _currentPrimitive.value = DrawingPrimitive.RangeLabelPrimitive(
                    startValue = rl.startValue, endValue = rl.endValue,
                    x = start.x, y = start.y,
                    fontSize = rl.fontSize, color = _currentColor.value,
                    strokeWidth = _currentStrokeWidth.value, layerId = _activeLayerId.value,
                    horizontalOnly = rl.horizontalOnly, rotation = initRotation,
                    arrowSpan = 1f, reversed = rl.reversed
                )
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
                startX = start.x, startY = start.y, endX = start.x, endY = start.y,
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

    fun updatePrimitive(point: Point2D) {
        if (_currentTool.value == ToolType.ERASER) { performErasure(point); return }
        if (_currentTool.value == ToolType.SELECT) { updateSelection(point); return }
        _currentPrimitive.value = when (val cp = _currentPrimitive.value) {
            is DrawingPrimitive.FreehandPath -> cp.copy(points = cp.points + point)
            is DrawingPrimitive.RectanglePrimitive -> {
                if (_rectangleSquareMode.value) {
                    val dx = point.x - cp.startX
                    val dy = point.y - cp.startY
                    val side = maxOf(abs(dx), abs(dy))
                    val sx = if (dx > 0) 1f else if (dx < 0) -1f else 0f
                    val sy = if (dy > 0) 1f else if (dy < 0) -1f else 0f
                    cp.copy(endX = cp.startX + sx * side, endY = cp.startY + sy * side)
                } else cp.copy(endX = point.x, endY = point.y)
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
        if (_currentTool.value == ToolType.SELECT) { endSelection(); return }
        if (_currentTool.value == ToolType.ERASER) { _eraserTouchPoint.value = null; _eraserUndoPushed = false; exitTemporaryEraser(); return }
        val cp = _currentPrimitive.value ?: return
        val valid = when (cp) {
            is DrawingPrimitive.FreehandPath -> cp.points.size >= 3
            is DrawingPrimitive.RectanglePrimitive -> abs(cp.endX - cp.startX) > 5f || abs(cp.endY - cp.startY) > 5f
            is DrawingPrimitive.CirclePrimitive -> abs(cp.endX - cp.centerX) > 5f && abs(cp.endY - cp.centerY) > 5f
            is DrawingPrimitive.LinePrimitive -> abs(cp.endX - cp.startX) > 3f || abs(cp.endY - cp.startY) > 3f
            else -> true
        }
        if (valid) {
            if (_blockDraft.value != null) {
                _blockDraft.value!!.primitives.add(cp)
            }
            // 手绘线直接提交，不进预览编辑模式
            if (cp is DrawingPrimitive.FreehandPath) {
                pushUndo()
                _primitives.add(cp); hasUnsavedChanges = true
            } else {
                // 其他图形进入预览编辑模式
                val arr = computeBounds(cp)
                val bounds = if (arr != null) Bounds(arr[0], arr[1], arr[2], arr[3]) else Bounds(0f, 0f, 100f, 100f)
                _pendingEdit.value = PendingEdit(
                    active = true, primitive = cp, bounds = bounds,
                    rotation = 0f, scaleX = 1f, scaleY = 1f,
                    offsetX = 0f, offsetY = 0f
                )
                pushUndo()
                hasUnsavedChanges = true
            }
        }
        _currentPrimitive.value = null
    }

    fun cancelPrimitive() {
        if (_currentTool.value == ToolType.SELECT) { clearSelection(); return }
        if (_currentTool.value == ToolType.ERASER) { _eraserTouchPoint.value = null; _eraserUndoPushed = false; exitTemporaryEraser(); return }
        // 双指缩放触发 onTouchCancel → 自动取消标注/文字的 PendingEdit
        if (_pendingEdit.value.isActive() &&
            (_currentTool.value == ToolType.ANNOTATE || _currentTool.value == ToolType.TEXT || _currentTool.value == ToolType.RANGE)) {
            _pendingEdit.value = PendingEdit()
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
        var finalPrimitive = applyTransform(primitive, pe)
        pushUndo()
        _primitives.add(finalPrimitive)
        _pendingEdit.value = PendingEdit()
        when (finalPrimitive) {
            is DrawingPrimitive.TextPrimitive -> _lastTextFontSize = finalPrimitive.fontSize
            is DrawingPrimitive.NumberLabelPrimitive -> {
                _lastNumberFontSize = finalPrimitive.fontSize
                _numberLabel.value = _numberLabel.value.copy(fontSize = finalPrimitive.fontSize,
                    currentValue = _numberLabel.value.currentValue + 1)
            }
            is DrawingPrimitive.RangeLabelPrimitive -> {
                val newStart = finalPrimitive.endValue + 1
                _rangeLabel.value = _rangeLabel.value.copy(
                    startValue = newStart,
                    endValue = newStart + 1,
                    lastEndValue = finalPrimitive.endValue,
                    fontSize = finalPrimitive.fontSize,
                    horizontalOnly = finalPrimitive.horizontalOnly,
                    reversed = finalPrimitive.reversed)
            }
            else -> {}
        }
        autoSave()
    }

    fun cancelPendingEdit() { _pendingEdit.value = PendingEdit() }

    fun updatePendingOffset(dx: Float, dy: Float) {
        val pe = _pendingEdit.value
        _pendingEdit.value = pe.copy(offsetX = pe.offsetX + dx, offsetY = pe.offsetY + dy)
    }

    fun updatePendingRotation(r: Float) {
        _pendingEdit.value = _pendingEdit.value.copy(rotation = r)
    }

    fun updatePendingScale(sx: Float, sy: Float) {
        val pe = _pendingEdit.value
        _pendingEdit.value = pe.copy(
            scaleX = (pe.scaleX * sx).coerceIn(0.1f, 10f),
            scaleY = (pe.scaleY * sy).coerceIn(0.1f, 10f)
        )
    }

    fun updatePendingFontScale(delta: Float) {
        val pe = _pendingEdit.value
        if (pe.primitive is DrawingPrimitive.RangeLabelPrimitive) {
            val p = pe.primitive as DrawingPrimitive.RangeLabelPrimitive
            val newFs = (p.fontSize + delta).coerceIn(20f, 200f)
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
            val arr = computeBounds(updatedPrimitive)
            val newBounds = if (arr != null) Bounds(arr[0], arr[1], arr[2], arr[3]) else pe.bounds
            _pendingEdit.value = pe.copy(primitive = updatedPrimitive, bounds = newBounds)
        }
    }

    fun updatePendingRangeFontSize(targetSize: Float) {
        val pe = _pendingEdit.value
        val p = pe.primitive
        if (p is DrawingPrimitive.RangeLabelPrimitive) {
            val clamped = targetSize.coerceIn(20f, 200f)
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
            is DrawingPrimitive.TextPrimitive -> (p.fontSize * avgScale).coerceIn(30f, 400f)
            is DrawingPrimitive.NumberLabelPrimitive -> (p.fontSize * avgScale).coerceIn(30f, 400f)
            is DrawingPrimitive.RangeLabelPrimitive -> {
                val isUniform = abs(pe.scaleX - pe.scaleY) < 0.01f
                (p.fontSize * (if (isUniform) avgScale else 1f)).coerceIn(20f, 400f)
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
        val scale = (targetSize / baseFontSize).coerceIn(0.1f, 10f)
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

    fun updatePendingRangeValue(isStart: Boolean, newValue: Int) {
        val pe = _pendingEdit.value
        val p = pe.primitive
        if (p is DrawingPrimitive.RangeLabelPrimitive) {
            val updated = if (isStart) p.copy(startValue = newValue) else p.copy(endValue = newValue)
            _pendingEdit.value = pe.copy(primitive = updated)
        }
    }

    private fun applyTransform(p: DrawingPrimitive, pe: PendingEdit): DrawingPrimitive {
        val dx = pe.offsetX; val dy = pe.offsetY
        val sx = pe.scaleX; val sy = pe.scaleY
        val cosR = cos(pe.rotation.toDouble()).toFloat(); val sinR = sin(pe.rotation.toDouble()).toFloat()
        val bounds = pe.bounds
        val cx0 = bounds?.let { (it.minX + it.maxX) / 2f } ?: 0f
        val cy0 = bounds?.let { (it.minY + it.maxY) / 2f } ?: 0f
        fun transform(wx: Float, wy: Float): Point2D {
            var x = (wx - cx0) * sx; var y = (wy - cy0) * sy
            val rx = x * cosR - y * sinR + cx0 + dx; val ry = x * sinR + y * cosR + cy0 + dy
            return Point2D(rx, ry)
        }
        return when (p) {
            is DrawingPrimitive.FreehandPath -> p.copy(points = p.points.map { transform(it.x, it.y) })
            is DrawingPrimitive.RectanglePrimitive -> {
                if (dx == 0f && dy == 0f && pe.rotation == 0f && sx == 1f && sy == 1f) return p
                val newRotation = (p.rotation + pe.rotation) % (2f * kotlin.math.PI.toFloat())
                // 原始矩形半宽半高和中心
                val hw0 = abs(p.endX - p.startX) / 2f
                val hh0 = abs(p.endY - p.startY) / 2f
                val rectCx = (p.startX + p.endX) / 2f
                val rectCy = (p.startY + p.endY) / 2f
                val cosR0 = cos(p.rotation.toDouble()).toFloat()
                val sinR0 = sin(p.rotation.toDouble()).toFloat()
                // 原始4个角（含 p.rotation）
                fun rc(dx: Float, dy: Float) = Point2D(rectCx + dx * cosR0 - dy * sinR0,
                    rectCy + dx * sinR0 + dy * cosR0)
                val corners = listOf(rc(-hw0, -hh0), rc(hw0, -hh0), rc(hw0, hh0), rc(-hw0, hh0))
                // 应用 PendingEdit 变换
                val t = corners.map { transform(it.x, it.y) }
                // 从边长计算实际宽高（变换后的矩形，非 AABB）
                val w = sqrt(((t[1].x - t[0].x) * (t[1].x - t[0].x) +
                    (t[1].y - t[0].y) * (t[1].y - t[0].y)).toDouble()).toFloat()
                val h = sqrt(((t[2].x - t[1].x) * (t[2].x - t[1].x) +
                    (t[2].y - t[1].y) * (t[2].y - t[1].y)).toDouble()).toFloat()
                val newCx = (t.sumOf { it.x.toDouble() } / 4f).toFloat()
                val newCy = (t.sumOf { it.y.toDouble() } / 4f).toFloat()
                return p.copy(
                    startX = newCx - w / 2f, startY = newCy - h / 2f,
                    endX = newCx + w / 2f, endY = newCy + h / 2f,
                    rotation = newRotation
                )
            }
            is DrawingPrimitive.CirclePrimitive -> {
                if (dx == 0f && dy == 0f && pe.rotation == 0f && sx == 1f && sy == 1f) return p
                val origRx = abs(p.endX - p.centerX); val origRy = abs(p.endY - p.centerY)
                val segs = 32
                val pts = (0..segs).map { i ->
                    val a = (i.toFloat() / segs) * 2f * kotlin.math.PI.toFloat()
                    transform(p.centerX + origRx * cos(a), p.centerY + origRy * sin(a))
                }
                DrawingPrimitive.FreehandPath(points = pts, isClosed = true, color = p.color,
                    strokeWidth = p.strokeWidth, layerId = p.layerId, lineStyle = p.lineStyle)
            }
            is DrawingPrimitive.LinePrimitive -> {
                val s = transform(p.startX, p.startY); val e = transform(p.endX, p.endY)
                p.copy(startX = s.x, startY = s.y, endX = e.x, endY = e.y)
            }
            is DrawingPrimitive.NumberLabelPrimitive -> {
                val t = transform(p.x, p.y)
                val newRotation = (p.rotation + pe.rotation) % (2f * kotlin.math.PI.toFloat())
                val avgScale = sqrt(abs(sx * sy))
                val newFontSize = (p.fontSize * avgScale).coerceIn(30f, 400f)
                p.copy(x = t.x, y = t.y, rotation = newRotation, fontSize = newFontSize)
            }
            is DrawingPrimitive.TextPrimitive -> {
                val t = transform(p.x, p.y)
                val newRotation = (p.rotation + pe.rotation) % (2f * kotlin.math.PI.toFloat())
                val avgScale = sqrt(abs(sx * sy))
                val newFontSize = (p.fontSize * avgScale).coerceIn(30f, 400f)
                p.copy(x = t.x, y = t.y, rotation = newRotation, fontSize = newFontSize)
            }
            is DrawingPrimitive.RangeLabelPrimitive -> {
                val t = transform(p.x, p.y)
                val newRotation = (p.rotation + pe.rotation) % (2f * kotlin.math.PI.toFloat())
                // 箭头杆长：角手柄均匀缩放（sx≈sy）时同步放大 arrowSpan，中点手柄已由 updatePendingArrowSpan 直接修改
                val avgScale = sqrt(abs(sx * sy))
                val isUniform = abs(sx - sy) < 0.01f
                val newFontSize = if (isUniform) (p.fontSize * avgScale).coerceIn(20f, 400f) else p.fontSize
                val newArrowSpan = (p.arrowSpan * sx).coerceAtLeast(0.2f)
                p.copy(x = t.x, y = t.y, rotation = newRotation, fontSize = newFontSize, arrowSpan = newArrowSpan)
            }
            is DrawingPrimitive.BlockRefPrimitive -> {
                val t = transform(p.x, p.y)
                val avgScale = sqrt(abs(sx * sy))
                p.copy(x = t.x, y = t.y, scale = (p.scale * avgScale).coerceIn(0.1f, 10f))
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    //  选择操作
    // ═══════════════════════════════════════════════════════

    fun startSelection(point: Point2D) {
        _selection.value = SelectionState(isActive = true, selStartX = point.x, selStartY = point.y,
            selEndX = point.x, selEndY = point.y)
    }

    fun updateSelection(point: Point2D) {
        val s = _selection.value
        if (s.isActive) {
            _selection.value = s.copy(selEndX = point.x, selEndY = point.y)
        }
    }

    fun endSelection() {
        val s = _selection.value
        if (!s.isActive) { _selection.value = SelectionState(); return }
        // 根据拖框方向确定选中模式：左→右=完全包含，右→左=交叉选中
        val lr = s.selStartX <= s.selEndX
        val selBounds = Bounds(minOf(s.selStartX, s.selEndX), minOf(s.selStartY, s.selEndY),
            maxOf(s.selStartX, s.selEndX), maxOf(s.selStartY, s.selEndY))
        val allSelected = _primitives.indices.filter { i ->
            val p = _primitives[i] ?: return@filter false
            val pb = computeBounds(p) ?: return@filter false
            if (lr) {
                // INSIDE_TOUCH：完全包含
                pb[0] >= selBounds.minX && pb[1] >= selBounds.minY &&
                    pb[2] <= selBounds.maxX && pb[3] <= selBounds.maxY
            } else {
                // 交叉选中：有交集，且实际碰触图形（不选隔空戳到包围盒的）
                pb[0] <= selBounds.maxX && pb[2] >= selBounds.minX &&
                    pb[1] <= selBounds.maxY && pb[3] >= selBounds.minY &&
                    fenceHitsGeometry(p, selBounds)
            }
        }.toSet()
        _selection.value = SelectionState(
            selectedIndices = allSelected,
            bounds = computeSelectionBounds(allSelected),
            direction = if (lr) SelectDirection.INSIDE_TOUCH else SelectDirection.FULLY_COVER
        )
    }

    fun computeSelectionBounds(indices: Set<Int>): Bounds? {
        if (indices.isEmpty()) return null
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in indices) {
            val p = _primitives.getOrNull(i) ?: continue
            val b = computeBounds(p) ?: continue
            minX = minOf(minX, b[0], b[2]); minY = minOf(minY, b[1], b[3])
            maxX = maxOf(maxX, b[0], b[2]); maxY = maxOf(maxY, b[1], b[3])
        }
        return if (minX != Float.MAX_VALUE) Bounds(minX, minY, maxX, maxY) else null
    }

    fun clearSelection() {
        _selection.value = SelectionState()
        _selectionSnapshot = null
        _scaleAccumAvg = 1f
    }

    fun moveSelectedPrimitives(dx: Float, dy: Float) {
        val indices = _selection.value.selectedIndices
        if (indices.isEmpty()) return
        if (_selectionSnapshot == null) {
            _selectionSnapshot = indices.associateWith { _primitives[it] }
            pushUndo()
        }
        for (i in indices) {
            val p = _primitives[i] ?: continue
            _primitives[i] = when (p) {
                is DrawingPrimitive.FreehandPath -> p.copy(points = p.points.map { Point2D(it.x + dx, it.y + dy) })
                is DrawingPrimitive.RectanglePrimitive -> p.copy(startX = p.startX + dx, startY = p.startY + dy, endX = p.endX + dx, endY = p.endY + dy)
                is DrawingPrimitive.CirclePrimitive -> p.copy(centerX = p.centerX + dx, centerY = p.centerY + dy, endX = p.endX + dx, endY = p.endY + dy)
                is DrawingPrimitive.LinePrimitive -> p.copy(startX = p.startX + dx, startY = p.startY + dy, endX = p.endX + dx, endY = p.endY + dy)
                is DrawingPrimitive.NumberLabelPrimitive -> p.copy(x = p.x + dx, y = p.y + dy)
                is DrawingPrimitive.TextPrimitive -> p.copy(x = p.x + dx, y = p.y + dy)
                is DrawingPrimitive.RangeLabelPrimitive -> p.copy(x = p.x + dx, y = p.y + dy)
                is DrawingPrimitive.BlockRefPrimitive -> p.copy(x = p.x + dx, y = p.y + dy)
            }
        }
        _selection.value = _selection.value.copy(bounds = computeSelectionBounds(_selection.value.selectedIndices))
    }

    fun scaleSelectedPrimitives(sx: Float, sy: Float) {
        val indices = _selection.value.selectedIndices
        if (indices.isEmpty()) return
        if (_selectionSnapshot == null) {
            _selectionSnapshot = indices.associateWith { _primitives[it] }
            _selection.value = _selection.value.copy(rotation = 0f, initialBounds = _selection.value.bounds)
            _scaleAccumAvg = 1f
            pushUndo()
        }
        val selBounds = _selection.value.initialBounds ?: _selection.value.bounds ?: return
        val cx = (selBounds.minX + selBounds.maxX) / 2f; val cy = (selBounds.minY + selBounds.maxY) / 2f
        _scaleAccumAvg *= sqrt(abs(sx * sy))
        for (i in indices) {
            val p = _primitives[i] ?: continue
            fun tx(wx: Float, wy: Float) = Point2D(cx + (wx - cx) * sx, cy + (wy - cy) * sy)
            val snapshotP = _selectionSnapshot?.get(i)
            _primitives[i] = when (p) {
                is DrawingPrimitive.FreehandPath -> p.copy(points = p.points.map { tx(it.x, it.y) })
                is DrawingPrimitive.RectanglePrimitive -> {
                    if (sx != 1f || sy != 1f) {
                        val hw = abs(p.endX - p.startX) / 2f
                        val hh = abs(p.endY - p.startY) / 2f
                        val rcx = (p.startX + p.endX) / 2f
                        val rcy = (p.startY + p.endY) / 2f
                        val cosP = kotlin.math.cos(p.rotation)
                        val sinP = kotlin.math.sin(p.rotation)
                        fun rc(dx: Float, dy: Float) = Point2D(rcx + dx * cosP - dy * sinP,
                            rcy + dx * sinP + dy * cosP)
                        val corners = listOf(rc(-hw, -hh), rc(hw, -hh), rc(hw, hh), rc(-hw, hh))
                        val sc = corners.map { tx(it.x, it.y) }
                        // 从边长计算实际宽高
                        val nw = sqrt(((sc[1].x - sc[0].x) * (sc[1].x - sc[0].x) +
                            (sc[1].y - sc[0].y) * (sc[1].y - sc[0].y)).toDouble()).toFloat()
                        val nh = sqrt(((sc[2].x - sc[1].x) * (sc[2].x - sc[1].x) +
                            (sc[2].y - sc[1].y) * (sc[2].y - sc[1].y)).toDouble()).toFloat()
                        val ncx = (sc.sumOf { it.x.toDouble() } / 4f).toFloat()
                        val ncy = (sc.sumOf { it.y.toDouble() } / 4f).toFloat()
                        p.copy(startX = ncx - nw / 2f, startY = ncy - nh / 2f,
                            endX = ncx + nw / 2f, endY = ncy + nh / 2f, rotation = p.rotation)
                    } else p
                }
                is DrawingPrimitive.CirclePrimitive -> {
                    val c = tx(p.centerX, p.centerY); val e = tx(p.endX, p.endY)
                    p.copy(centerX = c.x, centerY = c.y, endX = e.x, endY = e.y)
                }
                is DrawingPrimitive.LinePrimitive -> {
                    val s = tx(p.startX, p.startY); val e = tx(p.endX, p.endY)
                    p.copy(startX = s.x, startY = s.y, endX = e.x, endY = e.y)
                }
                is DrawingPrimitive.NumberLabelPrimitive -> {
                    val t = tx(p.x, p.y)
                    val origFontSize = (snapshotP as? DrawingPrimitive.NumberLabelPrimitive)?.fontSize ?: p.fontSize
                    p.copy(x = t.x, y = t.y, fontSize = (origFontSize * _scaleAccumAvg).coerceIn(30f, 400f))
                }
                is DrawingPrimitive.TextPrimitive -> {
                    val t = tx(p.x, p.y)
                    val origFontSize = (snapshotP as? DrawingPrimitive.TextPrimitive)?.fontSize ?: p.fontSize
                    p.copy(x = t.x, y = t.y, fontSize = (origFontSize * _scaleAccumAvg).coerceIn(30f, 400f))
                }
                is DrawingPrimitive.RangeLabelPrimitive -> {
                    val t = tx(p.x, p.y)
                    val origFontSize = (snapshotP as? DrawingPrimitive.RangeLabelPrimitive)?.fontSize ?: p.fontSize
                    p.copy(x = t.x, y = t.y, fontSize = (origFontSize * _scaleAccumAvg).coerceIn(20f, 400f))
                }
                is DrawingPrimitive.BlockRefPrimitive -> {
                    val t = tx(p.x, p.y)
                    p.copy(x = t.x, y = t.y, scale = (p.scale * sx).coerceIn(0.1f, 10f))
                }
            }
        }
        _selection.value = _selection.value.copy(bounds = computeSelectionBounds(_selection.value.selectedIndices))
    }

    fun rotateSelectedPrimitives(rotation: Float) {
        val indices = _selection.value.selectedIndices
        if (indices.isEmpty()) return
        if (_selectionSnapshot == null) {
            _selectionSnapshot = indices.associateWith { _primitives[it] }
            _selection.value = _selection.value.copy(rotation = 0f, initialBounds = _selection.value.bounds)
            pushUndo()
        }
        val selBounds = _selection.value.initialBounds ?: _selection.value.bounds ?: return
        val cx = (selBounds.minX + selBounds.maxX) / 2f; val cy = (selBounds.minY + selBounds.maxY) / 2f
        val cosR = cos(rotation); val sinR = sin(rotation)
        fun rot(wx: Float, wy: Float): Point2D {
            val dx = wx - cx; val dy = wy - cy
            return Point2D(cx + dx * cosR - dy * sinR, cy + dx * sinR + dy * cosR)
        }
        for (i in indices) {
            val p = _primitives[i] ?: continue
            _primitives[i] = when (p) {
                is DrawingPrimitive.FreehandPath -> p.copy(points = p.points.map { rot(it.x, it.y) })
                is DrawingPrimitive.RectanglePrimitive -> {
                    val hw = abs(p.endX - p.startX) / 2f
                    val hh = abs(p.endY - p.startY) / 2f
                    val cosP = kotlin.math.cos(p.rotation)
                    val sinP = kotlin.math.sin(p.rotation)
                    val visCorners = listOf(
                        cx + (-hw) * cosP - (-hh) * sinP to cy + (-hw) * sinP + (-hh) * cosP,
                        cx + ( hw) * cosP - (-hh) * sinP to cy + ( hw) * sinP + (-hh) * cosP,
                        cx + ( hw) * cosP - ( hh) * sinP to cy + ( hw) * sinP + ( hh) * cosP,
                        cx + (-hw) * cosP - ( hh) * sinP to cy + (-hw) * sinP + ( hh) * cosP,
                    )
                    val cosR = kotlin.math.cos(rotation); val sinR = kotlin.math.sin(rotation)
                    val transCorners = visCorners.map { (wx, wy) ->
                        val dx2 = wx - cx; val dy2 = wy - cy
                        Point2D(cx + dx2 * cosR - dy2 * sinR, cy + dx2 * sinR + dy2 * cosR)
                    }
                    val xs = transCorners.map { it.x }; val ys = transCorners.map { it.y }
                    p.copy(startX = xs.min(), startY = ys.min(),
                        endX = xs.max(), endY = ys.max(),
                        rotation = p.rotation + rotation)
                }
                is DrawingPrimitive.CirclePrimitive -> {
                    val c = rot(p.centerX, p.centerY); val e = rot(p.endX, p.endY)
                    p.copy(centerX = c.x, centerY = c.y, endX = e.x, endY = e.y)
                }
                is DrawingPrimitive.LinePrimitive -> { val s = rot(p.startX, p.startY); val e = rot(p.endX, p.endY); p.copy(startX = s.x, startY = s.y, endX = e.x, endY = e.y) }
                is DrawingPrimitive.NumberLabelPrimitive -> { val t = rot(p.x, p.y); p.copy(x = t.x, y = t.y, rotation = p.rotation + rotation) }
                is DrawingPrimitive.TextPrimitive -> { val t = rot(p.x, p.y); p.copy(x = t.x, y = t.y, rotation = p.rotation + rotation) }
                is DrawingPrimitive.RangeLabelPrimitive -> { val t = rot(p.x, p.y); p.copy(x = t.x, y = t.y, rotation = p.rotation + rotation) }
                is DrawingPrimitive.BlockRefPrimitive -> { val t = rot(p.x, p.y); p.copy(x = t.x, y = t.y) }
            }
        }
        val curR = _selection.value.rotation
        _selection.value = _selection.value.copy(rotation = curR + rotation, bounds = computeSelectionBounds(_selection.value.selectedIndices))
    }

    fun confirmSelectionTransform() {
        _selectionSnapshot = null; _scaleAccumAvg = 1f
        val s = _selection.value
        if (s.isTransforming) {
            _selection.value = s.copy(isTransforming = false, rotation = 0f, initialBounds = null,
                bounds = computeSelectionBounds(s.selectedIndices))
            autoSave()
        }
    }

    /** 选择变换实时生效：清除快照但保留修改。 */
    fun finalizeSelectionTransform() {
        _selectionSnapshot = null; _scaleAccumAvg = 1f
        val s = _selection.value
        _selection.value = s.copy(rotation = 0f, initialBounds = null,
            bounds = computeSelectionBounds(s.selectedIndices))
        autoSave()
    }

    fun cancelSelectionTransform() {
        val snap = _selectionSnapshot ?: return
        for ((idx, original) in snap) { if (idx < _primitives.size) _primitives[idx] = original }
        _selectionSnapshot = null; _scaleAccumAvg = 1f
        _selection.value = SelectionState()
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
            else -> {}
        }
        val indices = _selection.value.selectedIndices
        pushUndo()
        when (action) {
            SelectionAction.DELETE -> {
                if (indices.isEmpty()) return
                for (i in indices.sortedDescending()) _primitives.removeAt(i)
            }
            SelectionAction.COPY -> {
                if (indices.isEmpty()) return
                _clipboard.clear()
                _clipboard.addAll(indices.map { i -> deepCopyPrimitive(_primitives[i]) })
                val beforeSize = _primitives.size
                for (cp in _clipboard) _primitives.add(shiftPrimitive(cp, 30f, 30f))
                val newIndices = (beforeSize until _primitives.size).toSet()
                _selection.value = SelectionState(selectedIndices = newIndices, bounds = computeSelectionBounds(newIndices))
                autoSave(); return
            }
            SelectionAction.CUT -> {
                if (indices.isEmpty()) return
                _clipboard.clear()
                _clipboard.addAll(indices.map { i -> deepCopyPrimitive(_primitives[i]) })
                for (i in indices.sortedDescending()) _primitives.removeAt(i)
                val beforeSize = _primitives.size
                for (cp in _clipboard) _primitives.add(shiftPrimitive(cp, 30f, 30f))
                val newIndices = (beforeSize until _primitives.size).toSet()
                _selection.value = SelectionState(selectedIndices = newIndices, bounds = computeSelectionBounds(newIndices))
                autoSave(); return
            }
            SelectionAction.PASTE -> { pasteClipboard() }
            SelectionAction.MIRROR -> {
                if (indices.isEmpty()) return
                val bounds = computeSelectionBounds(indices) ?: return
                val cx = (bounds.minX + bounds.maxX) / 2f
                for (i in indices) {
                    val p = _primitives[i]
                    _primitives[i] = when (p) {
                        is DrawingPrimitive.FreehandPath -> p.copy(points = p.points.map { Point2D(cx * 2 - it.x, it.y) })
                        is DrawingPrimitive.RectanglePrimitive -> p.copy(startX = cx * 2 - p.startX, endX = cx * 2 - p.endX)
                        is DrawingPrimitive.CirclePrimitive -> p.copy(centerX = cx * 2 - p.centerX, endX = cx * 2 - p.endX)
                        is DrawingPrimitive.LinePrimitive -> p.copy(startX = cx * 2 - p.startX, endX = cx * 2 - p.endX)
                        is DrawingPrimitive.NumberLabelPrimitive -> p.copy(x = cx * 2 - p.x)
                        is DrawingPrimitive.TextPrimitive -> p.copy(x = cx * 2 - p.x)
                        is DrawingPrimitive.BlockRefPrimitive -> p.copy(x = cx * 2 - p.x)
                        else -> p
                    }
                }
                _selection.value = _selection.value.copy(bounds = computeSelectionBounds(indices))
                autoSave(); return
            }
            else -> {}
        }
        _selection.value = SelectionState()
        autoSave()
    }

    private fun pasteClipboard() {
        if (_clipboard.isEmpty()) return
        for (cp in _clipboard) _primitives.add(shiftPrimitive(cp, 30f, 30f))
    }

    private fun deepCopyPrimitive(p: DrawingPrimitive): DrawingPrimitive = when (p) {
        is DrawingPrimitive.FreehandPath -> p.copy()
        is DrawingPrimitive.RectanglePrimitive -> p.copy()
        is DrawingPrimitive.CirclePrimitive -> p.copy()
        is DrawingPrimitive.LinePrimitive -> p.copy()
        is DrawingPrimitive.NumberLabelPrimitive -> p.copy()
        is DrawingPrimitive.TextPrimitive -> p.copy()
        is DrawingPrimitive.RangeLabelPrimitive -> p.copy()
        is DrawingPrimitive.BlockRefPrimitive -> p.copy()
    }

    private fun shiftPrimitive(p: DrawingPrimitive, dx: Float, dy: Float): DrawingPrimitive = when (p) {
        is DrawingPrimitive.FreehandPath -> p.copy(points = p.points.map { Point2D(it.x + dx, it.y + dy) })
        is DrawingPrimitive.RectanglePrimitive -> p.copy(startX = p.startX + dx, startY = p.startY + dy, endX = p.endX + dx, endY = p.endY + dy)
        is DrawingPrimitive.CirclePrimitive -> p.copy(centerX = p.centerX + dx, centerY = p.centerY + dy, endX = p.endX + dx, endY = p.endY + dy)
        is DrawingPrimitive.LinePrimitive -> p.copy(startX = p.startX + dx, startY = p.startY + dy, endX = p.endX + dx, endY = p.endY + dy)
        is DrawingPrimitive.NumberLabelPrimitive -> p.copy(x = p.x + dx, y = p.y + dy)
        is DrawingPrimitive.TextPrimitive -> p.copy(x = p.x + dx, y = p.y + dy)
        is DrawingPrimitive.RangeLabelPrimitive -> p.copy(x = p.x + dx, y = p.y + dy)
        is DrawingPrimitive.BlockRefPrimitive -> p.copy(x = p.x + dx, y = p.y + dy)
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
            _primitives[i] = when (val p = _primitives[i]) {
                is DrawingPrimitive.FreehandPath -> p.copy(color = color)
                is DrawingPrimitive.RectanglePrimitive -> p.copy(color = color)
                is DrawingPrimitive.CirclePrimitive -> p.copy(color = color)
                is DrawingPrimitive.LinePrimitive -> p.copy(color = color)
                is DrawingPrimitive.NumberLabelPrimitive -> p.copy(color = color)
                is DrawingPrimitive.TextPrimitive -> p.copy(color = color)
                is DrawingPrimitive.RangeLabelPrimitive -> p.copy(color = color)
                is DrawingPrimitive.BlockRefPrimitive -> p.copy(color = color)
            }
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

    fun updateSelectedLineScaleFactor(factor: Float) {
        val indices = _selection.value.selectedIndices; if (indices.isEmpty()) return
        pushUndo()
        val clamped = factor.coerceIn(0.25f, 4f)
        for (i in indices.sortedDescending()) {
            _primitives[i] = when (val p = _primitives[i]) {
                is DrawingPrimitive.FreehandPath -> p.copy(lineScaleFactor = clamped)
                is DrawingPrimitive.RectanglePrimitive -> p.copy(lineScaleFactor = clamped)
                is DrawingPrimitive.CirclePrimitive -> p.copy(lineScaleFactor = clamped)
                is DrawingPrimitive.LinePrimitive -> p.copy(lineScaleFactor = clamped)
                is DrawingPrimitive.NumberLabelPrimitive -> p.copy(lineScaleFactor = clamped)
                is DrawingPrimitive.TextPrimitive -> p.copy(lineScaleFactor = clamped)
                is DrawingPrimitive.RangeLabelPrimitive -> p.copy(lineScaleFactor = clamped)
                is DrawingPrimitive.BlockRefPrimitive -> p.copy(lineScaleFactor = clamped)
            }
        }; autoSave()
    }

    fun updateSelectedFontSize(fontSize: Float) {
        val indices = _selection.value.selectedIndices; if (indices.isEmpty()) return
        pushUndo()
        val clamped = fontSize.coerceIn(30f, 400f)
        for (i in indices.sortedDescending()) {
            when (val p = _primitives[i]) {
                is DrawingPrimitive.NumberLabelPrimitive -> _primitives[i] = p.copy(fontSize = clamped)
                is DrawingPrimitive.TextPrimitive -> _primitives[i] = p.copy(fontSize = clamped)
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

    fun addTextAtCenter(screenWidth: Float, screenHeight: Float, horizontalOnly: Boolean = false) {
        val txt = _pendingTextContent.value.ifBlank { "文字" }
        val centerX = (screenWidth / 2f - _canvasOffsetX.value) / _canvasScale.value
        val centerY = (screenHeight / 2f - _canvasOffsetY.value) / _canvasScale.value
        val initRotation = if (horizontalOnly) 0f else (Math.PI / 2).toFloat()
        _pendingEdit.value = PendingEdit(
            active = true,
            primitive = DrawingPrimitive.TextPrimitive(
                text = txt, x = centerX, y = centerY,
                fontSize = _lastTextFontSize, color = _currentColor.value,
                strokeWidth = _currentStrokeWidth.value, layerId = _activeLayerId.value,
                horizontalOnly = horizontalOnly, rotation = initRotation
            ),
            bounds = Bounds(centerX - 80f, centerY - 40f, centerX + 80f, centerY + 40f)
        )
        _currentTool.value = _previousTool.value
    }

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
        _blockDefs.add(BlockDef(id = id, name = name, primitives = draft.primitives.toList(), snapPoints = draft.snapPoints.toList()))
        _blockDraft.value = null; _currentTool.value = ToolType.SELECT
        return true
    }

    fun cancelBlockDraft() { _blockDraft.value = null; _editingBlockId.value = null; _currentTool.value = ToolType.SELECT }

    private fun computeBlockBounds(primitives: List<DrawingPrimitive>): Bounds? {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var hasAny = false
        for (p in primitives) {
            val b = computeBounds(p) ?: continue
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
        _pendingEdit.value = PendingEdit(
            active = true,
            primitive = DrawingPrimitive.BlockRefPrimitive(
                blockDefId = blockDef.id, x = vx, y = vy,
                scale = 1f, rotation = 0f, snapPointIndex = -1,
                color = _currentColor.value, strokeWidth = _currentStrokeWidth.value,
                layerId = _activeLayerId.value, lineStyle = _currentLineStyle.value
            ),
            bounds = Bounds(vx - halfW, vy - halfH, vx + halfW, vy + halfH),
            scaleX = 1f, scaleY = 1f
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
                val dist = pointToPrimitiveDist(start, p)
                if (dist < bestDist) { bestDist = dist; bestIdx = i }
            }
            _blockEditorSelectedIndex.value = if (bestIdx >= 0 && bestDist < tol) bestIdx else -1
            return
        }
        _blockEditorCurrent.value = when (_currentTool.value) {
            ToolType.FREEHAND -> DrawingPrimitive.FreehandPath(points = listOf(start), color = _currentColor.value,
                strokeWidth = _currentStrokeWidth.value, layerId = 1, lineStyle = _currentLineStyle.value)
            ToolType.RECTANGLE -> DrawingPrimitive.RectanglePrimitive(startX = start.x, startY = start.y,
                endX = start.x, endY = start.y, color = _currentColor.value,
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
        val tol = 30f / _blockEditorViewScale.value  // larger tolerance in world units
        // Find the nearest primitive edge to the touch point
        var bestIdx = -1; var bestDist = Float.MAX_VALUE
        for ((i, p) in _blockEditorPrimitives.withIndex()) {
            val dist = pointToPrimitiveDist(point, p)
            if (dist < bestDist) { bestDist = dist; bestIdx = i }
        }
        if (bestIdx >= 0 && bestDist < tol) {
            _blockEditorUndoHistory.add(_blockEditorPrimitives.toList())
            _blockEditorRedoHistory.clear()
            _blockEditorPrimitives.removeAt(bestIdx)
            _blockEditorSelectedIndex.value = -1
            _canBlockEditorUndo.value = true; _canBlockEditorRedo.value = false
        }
    }

    /** Point-to-primitive distance: min distance to any segment/edge */
    private fun pointToPrimitiveDist(point: Point2D, p: DrawingPrimitive): Float {
        return when (p) {
            is DrawingPrimitive.FreehandPath -> {
                if (p.points.size < 2) Float.MAX_VALUE
                else p.points.zipWithNext().minOf { (a, b) -> distToSegment(point, a, b) }
            }
            is DrawingPrimitive.LinePrimitive -> distToSegment(point,
                Point2D(p.startX, p.startY), Point2D(p.endX, p.endY))
            is DrawingPrimitive.RectanglePrimitive -> {
                val x1 = minOf(p.startX, p.endX); val y1 = minOf(p.startY, p.endY)
                val x2 = maxOf(p.startX, p.endX); val y2 = maxOf(p.startY, p.endY)
                val corners = listOf(
                    Point2D(x1, y1), Point2D(x2, y1), Point2D(x2, y2), Point2D(x1, y2)
                )
                (corners + corners.first()).zipWithNext().minOf { (a, b) -> distToSegment(point, a, b) }
            }
            is DrawingPrimitive.CirclePrimitive -> {
                val r = maxOf(abs(p.endX - p.centerX), abs(p.endY - p.centerY))
                val dc = sqrt((point.x - p.centerX).squared() + (point.y - p.centerY).squared())
                abs(dc - r)
            }
            is DrawingPrimitive.NumberLabelPrimitive -> distToSegment(point,
                Point2D(p.x - 30f, p.y), Point2D(p.x + 30f, p.y))
            is DrawingPrimitive.TextPrimitive -> distToSegment(point,
                Point2D(p.x - 40f, p.y), Point2D(p.x + 40f, p.y))
            else -> Float.MAX_VALUE
        }
    }

    /** Distance from point to line segment AB */
    private fun distToSegment(pt: Point2D, a: Point2D, b: Point2D): Float {
        val abx = b.x - a.x; val aby = b.y - a.y
        val lenSq = abx * abx + aby * aby
        if (lenSq < 0.0001f) return sqrt((pt.x - a.x).squared() + (pt.y - a.y).squared())
        var t = ((pt.x - a.x) * abx + (pt.y - a.y) * aby) / lenSq
        t = t.coerceIn(0f, 1f)
        val px = a.x + t * abx; val py = a.y + t * aby
        return sqrt((pt.x - px).squared() + (pt.y - py).squared())
    }

    private fun Float.squared() = this * this

    fun blockEditorUpdatePrimitive(point: Point2D) {
        if (_currentTool.value == ToolType.ERASER) {
            blockEditorPerformErasure(point)
            return
        }
        _blockEditorCurrent.value = when (val cp = _blockEditorCurrent.value) {
            is DrawingPrimitive.FreehandPath -> cp.copy(points = cp.points + point)
            is DrawingPrimitive.RectanglePrimitive -> {
                if (_rectangleSquareMode.value) {
                    val dx = point.x - cp.startX
                    val dy = point.y - cp.startY
                    val side = maxOf(abs(dx), abs(dy))
                    val sx = if (dx > 0) 1f else if (dx < 0) -1f else 0f
                    val sy = if (dy > 0) 1f else if (dy < 0) -1f else 0f
                    cp.copy(endX = cp.startX + sx * side, endY = cp.startY + sy * side)
                } else cp.copy(endX = point.x, endY = point.y)
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
        if (_currentTool.value == ToolType.ERASER) { _eraserTouchPoint.value = null; exitTemporaryEraser(); return }
        val cp = _blockEditorCurrent.value ?: return
        _blockEditorCurrent.value = null
        // 手绘线直接提交
        if (cp is DrawingPrimitive.FreehandPath) {
            if (cp.points.size < 3) return
            _blockEditorUndoHistory.add(_blockEditorPrimitives.toList())
            _blockEditorRedoHistory.clear()
            _blockEditorPrimitives.add(cp)
            _canBlockEditorUndo.value = true; _canBlockEditorRedo.value = false
        } else {
            // 其他图形进入 PendingEdit 预览
            val arr = computeBounds(cp)
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

    fun blockEditorUpdatePendingRotation(r: Float) {
        _blockEditorPendingEdit.value = _blockEditorPendingEdit.value.copy(rotation = r)
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
        _blockEditorRedoHistory.clear()
        _blockEditorPrimitives.add(pe.primitive!!)
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
        _blockEditorPrimitives.clear()
        _blockEditorPrimitives.addAll(_blockEditorRedoHistory.removeLast())
        _canBlockEditorUndo.value = true
        _canBlockEditorRedo.value = _blockEditorRedoHistory.isNotEmpty()
    }

    // ═══════════════════════════════════════════════════════
    //  图层管理
    // ═══════════════════════════════════════════════════════

    fun addLayer(name: String) {
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
        if (id == 0) return  // 图层0不可复制
        pushUndo()
        val newId = (_layers.maxOfOrNull { it.id } ?: 0) + 1
        val srcIdx = _layers.indexOfFirst { it.id == id }
        val insertAt = if (srcIdx >= 0) srcIdx else _layers.size
        _layers.add(insertAt, Layer(id = newId, name = "${src.name}(1)"))
        _activeLayerId.value = newId
        // 复制源图层的所有基元
        for (p in _primitives.filter { it.layerId == id }) {
            _primitives.add(when (p) {
                is DrawingPrimitive.FreehandPath -> p.copy(layerId = newId)
                is DrawingPrimitive.RectanglePrimitive -> p.copy(layerId = newId)
                is DrawingPrimitive.CirclePrimitive -> p.copy(layerId = newId)
                is DrawingPrimitive.LinePrimitive -> p.copy(layerId = newId)
                is DrawingPrimitive.NumberLabelPrimitive -> p.copy(layerId = newId)
                is DrawingPrimitive.TextPrimitive -> p.copy(layerId = newId)
                is DrawingPrimitive.RangeLabelPrimitive -> p.copy(layerId = newId)
                is DrawingPrimitive.BlockRefPrimitive -> p.copy(layerId = newId)
            })
        }
    }

    fun mergeLayers(srcId: Int, dstId: Int) {
        for (i in _primitives.indices) {
            val p = _primitives[i]
            if (p.layerId == srcId) _primitives[i] = when (p) {
                is DrawingPrimitive.FreehandPath -> p.copy(layerId = dstId)
                is DrawingPrimitive.RectanglePrimitive -> p.copy(layerId = dstId)
                is DrawingPrimitive.CirclePrimitive -> p.copy(layerId = dstId)
                is DrawingPrimitive.LinePrimitive -> p.copy(layerId = dstId)
                is DrawingPrimitive.NumberLabelPrimitive -> p.copy(layerId = dstId)
                is DrawingPrimitive.TextPrimitive -> p.copy(layerId = dstId)
                is DrawingPrimitive.RangeLabelPrimitive -> p.copy(layerId = dstId)
                is DrawingPrimitive.BlockRefPrimitive -> p.copy(layerId = dstId)
            }
        }
        deleteLayer(srcId)
    }

    fun toggleLayerVisibility(id: Int) {
        if (id == 0) return  // 图层0不可隐藏
        val idx = _layers.indexOfFirst { it.id == id }
        if (idx >= 0) _layers[idx] = _layers[idx].copy(isVisible = !_layers[idx].isVisible)
    }

    fun deleteLayer(id: Int) {
        if (id == 0) return  // 图层0不可删除
        if (_layers.size <= 1) return
        pushUndo()
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
        redoHistory.clear(); _canRedo.value = false
        // 把当前层的所有基元移到下层
        for (i in _primitives.indices) {
            val p = _primitives[i]
            if (p.layerId == id) _primitives[i] = when (p) {
                is DrawingPrimitive.FreehandPath -> p.copy(layerId = belowId)
                is DrawingPrimitive.RectanglePrimitive -> p.copy(layerId = belowId)
                is DrawingPrimitive.CirclePrimitive -> p.copy(layerId = belowId)
                is DrawingPrimitive.LinePrimitive -> p.copy(layerId = belowId)
                is DrawingPrimitive.NumberLabelPrimitive -> p.copy(layerId = belowId)
                is DrawingPrimitive.TextPrimitive -> p.copy(layerId = belowId)
                is DrawingPrimitive.RangeLabelPrimitive -> p.copy(layerId = belowId)
                is DrawingPrimitive.BlockRefPrimitive -> p.copy(layerId = belowId)
            }
        }
        deleteLayer(id)
        _activeLayerId.value = belowId
    }

    fun renameLayer(id: Int, name: String) {
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
    }

    fun redo() {
        if (redoHistory.isEmpty()) return
        _currentPrimitive.value = null; _pendingEdit.value = PendingEdit()
        undoHistory.add(takeSnapshot())
        restoreSnapshot(redoHistory.removeLast())
        _canUndo.value = true; _canRedo.value = redoHistory.isNotEmpty()
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
        // 同时从 blocks/ 文件夹加载所有块文件（去重：文件里的覆盖文档里的同名 ID）
        mergeBlockFiles()
        _canvasScale.value = doc.canvasScale; _canvasOffsetX.value = doc.canvasOffsetX; _canvasOffsetY.value = doc.canvasOffsetY
        _activeLayerId.value = doc.activeLayerId
        _documentName.value = doc.name; _documentFile = file
    }

    /** 从已转换的 DocumentData 加载（用于后台线程预转换后主线程应用） */
    fun loadExistingData(data: DocumentData, file: java.io.File) {
        _primitives.clear()
        for (p in data.primitives) _primitives.add(p)
        _layers.clear(); for (l in data.layers) _layers.add(l)
        _blockDefs.clear(); for (b in data.blockDefs) _blockDefs.add(b)
        mergeBlockFiles()
        _canvasScale.value = data.canvasScale
        _canvasOffsetX.value = data.canvasOffsetX
        _canvasOffsetY.value = data.canvasOffsetY
        _activeLayerId.value = data.activeLayerId
        _documentName.value = file.nameWithoutExtension; _documentFile = file
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
            canvasScale = _canvasScale.value
        )
    }

    fun exportDxf(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                DxfWriter(stream).write(_primitives.toList(), _layers.toList(), blockDefs = _blockDefs.toList())
                hasUnsavedChanges = false; true
            } ?: false
        } catch (e: Exception) { e.printStackTrace(); false }
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
                    if (textHitByEraser(p.x, p.y, p.value.toString(), p.fontSize, p.rotation, worldPoint, rSq)) toRemove.add(p)
                }
                is DrawingPrimitive.TextPrimitive -> {
                    if (textHitByEraser(p.x, p.y, p.text, p.fontSize, p.rotation, worldPoint, rSq)) toRemove.add(p)
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
        if (disc < eps) return emptyList() // 相切不切，避免重根导致状态翻转两次
        val sqrtD = sqrt(disc)
        val t1 = (-b - sqrtD) / (2f * a)
        val t2 = (-b + sqrtD) / (2f * a)
        return buildList {
            if (t1 >= -eps && t1 <= 1f + eps) add(t1.coerceIn(0f, 1f))
            if (t2 >= -eps && t2 <= 1f + eps) add(t2.coerceIn(0f, 1f))
        }.sorted()
    }

    /** 在圆交点处切断路径，去掉圈内段，保留圈外段；支持开放/闭合路径 */
    private fun splitFreehand(points: List<Point2D>, center: Point2D, rSq: Float, isClosed: Boolean = false): List<List<Point2D>> {
        if (points.size < 2) return emptyList()
        val n = points.size
        val edgeCount = if (isClosed) n else n - 1
        val result = mutableListOf<List<Point2D>>()
        val current = mutableListOf<Point2D>()
        var inside = distSq(points[0], center) <= rSq
        if (!inside) current.add(points[0])

        val epsSq = 0.01f
        fun addDistinct(pt: Point2D) {
            if (current.isEmpty() || distSq(current.last(), pt) > epsSq) current.add(pt)
        }

        for (i in 0 until edgeCount) {
            val a = points[i]
            val b = if (isClosed && i == edgeCount - 1) points[0] else points[i + 1]
            val ts = segmentCircleIntersectAll(a, b, center, rSq)
            for (t in ts) {
                val ix = a.x + t * (b.x - a.x)
                val iy = a.y + t * (b.y - a.y)
                val ip = Point2D(ix, iy)
                if (!inside) {
                    addDistinct(ip)
                    if (current.size >= 2) result.add(current.toList())
                }
                inside = !inside
                current.clear()
                if (!inside) current.add(ip)
            }
            if (!inside) {
                val target = if (isClosed && i == edgeCount - 1) points[0] else points[i + 1]
                addDistinct(target)
            }
        }
        if (!inside && current.size >= 2) {
            // 闭合路径起点已经作为第一点，避免末尾重复
            if (isClosed && current.size > 2 && distSq(current.first(), current.last()) <= epsSq) {
                current.removeAt(current.size - 1)
            }
            if (current.size >= 2) result.add(current.toList())
        }
        return result
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
    private fun textHitByEraser(x: Float, y: Float, text: String, fontSize: Float, rotation: Float, center: Point2D, rSq: Float): Boolean {
        if (text.isEmpty()) return distSq(Point2D(x, y), center) <= rSq
        val halfW = text.length * fontSize * 0.35f
        val halfH = fontSize * 0.5f
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
                for (seg in segments) toAdd.add(p.copy(points = seg))
            }
        }
    }

    private fun eraseRect(p: DrawingPrimitive.RectanglePrimitive, center: Point2D, rSq: Float,
                          toRemove: MutableList<DrawingPrimitive>, toAdd: MutableList<DrawingPrimitive>) {
        val pts = rectToPoints(p)
        if (shapeHitByEraser(pts, center, rSq, isClosed = true)) {
            toRemove.add(p)
            if (_fineEraseEnabled.value) {
                val segments = splitFreehand(pts, center, rSq, isClosed = true)
                for (seg in segments) {
                    toAdd.add(DrawingPrimitive.FreehandPath(points = seg, color = p.color, strokeWidth = p.strokeWidth, layerId = p.layerId, lineStyle = p.lineStyle))
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
                    toAdd.add(DrawingPrimitive.FreehandPath(points = seg, color = p.color, strokeWidth = p.strokeWidth, layerId = p.layerId, lineStyle = p.lineStyle))
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
                    toAdd.add(DrawingPrimitive.FreehandPath(points = seg, color = p.color, strokeWidth = p.strokeWidth, layerId = p.layerId, lineStyle = p.lineStyle))
                }
            }
        }
    }

    private fun eraseRangeLabel(p: DrawingPrimitive.RangeLabelPrimitive, center: Point2D, rSq: Float,
                                toRemove: MutableList<DrawingPrimitive>) {
        val arrowLen = maxOf(80f * p.arrowSpan, 20f)
        val reach = arrowLen / 2f + p.fontSize * 2f
        val cosR = cos(p.rotation); val sinR = sin(p.rotation)
        val hx = reach * cosR; val hy = reach * sinR
        val pts = listOf(Point2D(p.x - hx, p.y - hy), Point2D(p.x + hx, p.y + hy))
        if (shapeHitByEraser(pts, center, rSq, isClosed = false)) toRemove.add(p)
    }

    private fun eraseBlockRef(p: DrawingPrimitive.BlockRefPrimitive, center: Point2D, rSq: Float,
                              toRemove: MutableList<DrawingPrimitive>) {
        val r = sqrt(rSq)
        val blockDef = _blockDefs.find { it.id == p.blockDefId }
        val radius = if (blockDef?.bounds != null) {
            val bw = blockDef.bounds.maxX - blockDef.bounds.minX
            val bh = blockDef.bounds.maxY - blockDef.bounds.minY
            maxOf(bw, bh) * 0.5f * p.scale
        } else {
            50f * p.scale
        }
        val threshold = r + radius
        if (distSq(Point2D(p.x, p.y), center) <= threshold * threshold) toRemove.add(p)
    }

    /** 包围盒快速排除：圆心到基元包围盒的最短距离如果大于半径则跳过 */
    private fun eraserHitBounds(p: DrawingPrimitive, center: Point2D, rSq: Float): Boolean {
        val bounds: Bounds = when (p) {
            is DrawingPrimitive.FreehandPath -> {
                var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
                var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                for (pt in p.points) {
                    if (pt.x < minX) minX = pt.x; if (pt.y < minY) minY = pt.y
                    if (pt.x > maxX) maxX = pt.x; if (pt.y > maxY) maxY = pt.y
                }
                Bounds(minX, minY, maxX, maxY)
            }
            is DrawingPrimitive.RectanglePrimitive -> {
                Bounds(minOf(p.startX, p.endX), minOf(p.startY, p.endY),
                       maxOf(p.startX, p.endX), maxOf(p.startY, p.endY))
            }
            is DrawingPrimitive.CirclePrimitive -> {
                val rx = abs(p.endX - p.centerX); val ry = abs(p.endY - p.centerY)
                Bounds(p.centerX - rx, p.centerY - ry, p.centerX + rx, p.centerY + ry)
            }
            is DrawingPrimitive.LinePrimitive -> {
                Bounds(minOf(p.startX, p.endX), minOf(p.startY, p.endY),
                       maxOf(p.startX, p.endX), maxOf(p.startY, p.endY))
            }
            else -> return true // 其他类型不做包围盒排除
        }
        val closestX = center.x.coerceIn(bounds.minX, bounds.maxX)
        val closestY = center.y.coerceIn(bounds.minY, bounds.maxY)
        return distSq(Point2D(closestX, closestY), center) <= rSq
    }

    /** 矩形轮廓采样 — 每条边等距加密，至少 4 段/边，每 20px 一个点 */
    private fun rectToPoints(r: DrawingPrimitive.RectanglePrimitive): List<Point2D> {
        val left = minOf(r.startX, r.endX); val top = minOf(r.startY, r.endY)
        val right = maxOf(r.startX, r.endX); val bottom = maxOf(r.startY, r.endY)
        val w = right - left; val h = bottom - top
        if (w < 0.01f && h < 0.01f) return listOf(Point2D(left, top))
        val segX = maxOf(4, (w / 20f + 0.5f).toInt())
        val segY = maxOf(4, (h / 20f + 0.5f).toInt())
        val pts = mutableListOf<Point2D>()
        // 上边 left→right（不含右上角）
        for (i in 0 until segX) pts.add(Point2D(left + w * i / segX, top))
        // 右边 top→bottom（不含右下角）
        for (i in 0 until segY) pts.add(Point2D(right, top + h * i / segY))
        // 下边 right→left（不含左下角）
        for (i in 0 until segX) pts.add(Point2D(right - w * i / segX, bottom))
        // 左边 bottom→top（不含左上角，闭合边的最后连接由 isClosed 处理）
        for (i in 0 until segY) pts.add(Point2D(left, bottom - h * i / segY))
        if (kotlin.math.abs(r.rotation) < 0.001f) return pts
        val cx = (left + right) / 2f; val cy = (top + bottom) / 2f
        val cosR = kotlin.math.cos(r.rotation); val sinR = kotlin.math.sin(r.rotation)
        return pts.map { pt ->
            val dx = pt.x - cx; val dy = pt.y - cy
            Point2D(cx + dx * cosR - dy * sinR, cy + dx * sinR + dy * cosR)
        }
    }

    /** 圆形轮廓采样（32 段，不重复首尾） */
    private fun circleToPoints(c: DrawingPrimitive.CirclePrimitive): List<Point2D> {
        val rx = abs(c.endX - c.centerX); val ry = abs(c.endY - c.centerY)
        return (0 until 32).map { i ->
            val angle = 2f * Math.PI.toFloat() * i / 32f
            Point2D(c.centerX + rx * cos(angle), c.centerY + ry * sin(angle))
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
            putLong(KEY_COLOR, _currentColor.value.value.toLong())
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
            apply()
        }
    }

    private fun restoreSettings() {
        val p = sessionPrefs ?: return
        _currentTool.value = try { ToolType.valueOf(p.getString(KEY_TOOL, ToolType.FREEHAND.name) ?: ToolType.FREEHAND.name) } catch (_: Exception) { ToolType.FREEHAND }
        _currentColor.value = Color(p.getLong(KEY_COLOR, Color.Black.value.toLong()))
        _currentStrokeWidth.value = p.getFloat(KEY_STROKE, 4f)
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
    }

    /** 围栏选中：检查选择框是否实际碰到图形的线条，不只靠包围盒 */
    private fun fenceHitsGeometry(p: DrawingPrimitive, fence: Bounds): Boolean {
        // 线段与轴对齐矩形相交测试（端点或穿越）
        fun segmentHitsRect(ax: Float, ay: Float, bx: Float, by: Float): Boolean {
            // 端点在内
            if (ax >= fence.minX && ax <= fence.maxX && ay >= fence.minY && ay <= fence.maxY) return true
            if (bx >= fence.minX && bx <= fence.maxX && by >= fence.minY && by <= fence.maxY) return true
            // 线段完全在一边之外
            val minX = minOf(ax, bx); val maxX = maxOf(ax, bx)
            val minY = minOf(ay, by); val maxY = maxOf(ay, by)
            if (maxX < fence.minX || minX > fence.maxX || maxY < fence.minY || minY > fence.maxY) return false
            // 与矩形 4 边相交检测
            fun ccw(px: Float, py: Float, qx: Float, qy: Float, rx: Float, ry: Float): Int {
                val cross = (qx - px) * (ry - py) - (qy - py) * (rx - px)
                return if (cross > 0) 1 else if (cross < 0) -1 else 0
            }
            fun segSeg(p1x: Float, p1y: Float, p2x: Float, p2y: Float,
                       q1x: Float, q1y: Float, q2x: Float, q2y: Float): Boolean {
                val o1 = ccw(p1x, p1y, p2x, p2y, q1x, q1y)
                val o2 = ccw(p1x, p1y, p2x, p2y, q2x, q2y)
                val o3 = ccw(q1x, q1y, q2x, q2y, p1x, p1y)
                val o4 = ccw(q1x, q1y, q2x, q2y, p2x, p2y)
                return o1 != o2 && o3 != o4
            }
            val l = fence.minX; val r = fence.maxX; val t = fence.minY; val b = fence.maxY
            return segSeg(ax, ay, bx, by, l, t, l, b) ||  // left
                   segSeg(ax, ay, bx, by, r, t, r, b) ||  // right
                   segSeg(ax, ay, bx, by, l, t, r, t) ||  // top
                   segSeg(ax, ay, bx, by, l, b, r, b)     // bottom
        }
        return when (p) {
            is DrawingPrimitive.FreehandPath -> {
                val pts = p.points
                for (i in 1 until pts.size) {
                    if (segmentHitsRect(pts[i-1].x, pts[i-1].y, pts[i].x, pts[i].y)) return true
                }
                if (p.isClosed && pts.size > 2)
                    segmentHitsRect(pts.last().x, pts.last().y, pts[0].x, pts[0].y)
                else false
            }
            is DrawingPrimitive.RectanglePrimitive -> {
                val left = minOf(p.startX, p.endX); val right = maxOf(p.startX, p.endX)
                val top = minOf(p.startY, p.endY); val bottom = maxOf(p.startY, p.endY)
                if (kotlin.math.abs(p.rotation) < 0.001f) {
                    segmentHitsRect(left, top, right, top) ||
                    segmentHitsRect(right, top, right, bottom) ||
                    segmentHitsRect(right, bottom, left, bottom) ||
                    segmentHitsRect(left, bottom, left, top)
                } else {
                    val cx = (left + right) / 2f; val cy = (top + bottom) / 2f
                    val cosR = kotlin.math.cos(p.rotation); val sinR = kotlin.math.sin(p.rotation)
                    fun rot(wx: Float, wy: Float): Pair<Float, Float> {
                        val dx = wx - cx; val dy = wy - cy
                        return cx + dx * cosR - dy * sinR to cy + dx * sinR + dy * cosR
                    }
                    val c0 = rot(left, top); val c1 = rot(right, top)
                    val c2 = rot(right, bottom); val c3 = rot(left, bottom)
                    segmentHitsRect(c0.first, c0.second, c1.first, c1.second) ||
                    segmentHitsRect(c1.first, c1.second, c2.first, c2.second) ||
                    segmentHitsRect(c2.first, c2.second, c3.first, c3.second) ||
                    segmentHitsRect(c3.first, c3.second, c0.first, c0.second)
                }
            }
            is DrawingPrimitive.CirclePrimitive -> {
                val rx = abs(p.endX - p.centerX); val ry = abs(p.endY - p.centerY)
                val segs = 16
                var prevX = p.centerX + rx * kotlin.math.cos(0.0).toFloat()
                var prevY = p.centerY + ry * kotlin.math.sin(0.0).toFloat()
                for (i in 1..segs) {
                    val a = (i.toFloat() / segs) * 2f * kotlin.math.PI.toFloat()
                    val cx = p.centerX + rx * kotlin.math.cos(a.toDouble()).toFloat()
                    val cy = p.centerY + ry * kotlin.math.sin(a.toDouble()).toFloat()
                    if (segmentHitsRect(prevX, prevY, cx, cy)) return true
                    prevX = cx; prevY = cy
                }
                false
            }
            is DrawingPrimitive.LinePrimitive -> {
                segmentHitsRect(p.startX, p.startY, p.endX, p.endY)
            }
            // 文字/数字/区间/图块：有填充面积，保持用包围盒（已经过 bounding box 过滤）
            is DrawingPrimitive.NumberLabelPrimitive -> true
            is DrawingPrimitive.TextPrimitive -> true
            is DrawingPrimitive.RangeLabelPrimitive -> true
            is DrawingPrimitive.BlockRefPrimitive -> true
        }
    }
}
