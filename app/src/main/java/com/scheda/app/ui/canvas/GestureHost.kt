package com.scheda.app.ui.canvas

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import com.scheda.app.model.BlockDef
import com.scheda.app.model.DrawingPrimitive
import com.scheda.app.model.Layer
import com.scheda.app.model.LineStyle
import com.scheda.app.model.PendingEdit
import com.scheda.app.model.Point2D
import com.scheda.app.model.ReferenceImage
import com.scheda.app.model.SelectionState
import com.scheda.app.model.ToolType
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop

/**
 * Gesture host: wraps DrawingCanvas + PostCreationOverlay,
 * and coordinates handle-drag vs drawing-event routing.
 *
 * Zoom is handled inside DrawingCanvas's own pointerInput at
 * PointerEventPass.Main — 2‑finger events trigger zoom, 1‑finger
 * events trigger drawing. No separate zoom handler needed.
 *
 * Architecture rationale:
 *  - While PaddedFrameOverlay handles are being dragged,
 *    [isHandleActive] is true, causing DrawingCanvas to skip
 *    its own 1‑finger touch handler → no steal conflicts.
 */
@Composable
fun GestureHost(
    // ── DrawingCanvas params ──
    primitives: List<DrawingPrimitive>,
    currentPrimitive: DrawingPrimitive?,
    layers: List<Layer>,
    canvasScale: Float,
    canvasOffsetX: Float,
    canvasOffsetY: Float,
    pendingEdit: PendingEdit,
    currentTool: ToolType,
    currentLineStyle: LineStyle,
    selectedIndices: Set<Int> = emptySet(),
    isTransforming: Boolean = false,
    globalLineScale: Float = 1f,
    blockDefs: List<BlockDef> = emptyList(),
    images: List<ReferenceImage> = emptyList(),
    imageBitmaps: Map<String, Bitmap> = emptyMap(),
    imageManageActive: Boolean = false,
    selectedImageId: String? = null,
    eraserRadius: Float = 30f,
    eraserTouchPoint: Point2D? = null,
    quickEraseEnabled: Boolean = false,
    onLongPressEraser: () -> Unit = {},
    onTouchStart: (Point2D) -> Unit,
    onTouchMove: (Point2D) -> Unit,
    onTouchEnd: () -> Unit,
    onTouchCancel: () -> Unit,
    // ── Canvas transform (called by unified zoom) ──
    onCanvasTransform: (zoom: Float, centroid: Offset, pan: Offset) -> Unit,
    // ── PCO params ──
    onConfirm: () -> Unit = {},
    onCancel: () -> Unit = {},
    onUpdateOffset: (Float, Float) -> Unit = { _, _ -> },
    onUpdateRotation: (Float) -> Unit = {},
    onUpdateScale: (Float, Float) -> Unit = { _, _ -> },
    onUpdatePrimitive: (DrawingPrimitive) -> Unit = {},
    onUpdateFontScale: (Float) -> Unit = {},
    onUpdateArrowSpan: (Float) -> Unit = {},
    onToggleTextOrientation: () -> Unit = {},
    onToggleRangeReversed: () -> Unit = {},
    currentFontSize: Float = 40f,
    // ── Selection params ──
    selection: SelectionState? = null,
    onMoveSelected: (Float, Float) -> Unit = { _, _ -> },
    onRotateSelected: (Float) -> Unit = {},
    onScaleSelected: (Float, Float) -> Unit = { _, _ -> },
    onRectMidpointDrag: ((index: Int, r: Float) -> Unit)? = null,
    rectDragHandleWorld: Offset? = null,
    onTransformEnd: () -> Unit = {},
    /** 选择态：单选直线拖端点（直接写回几何） */
    onMoveSelectedLineEndpoint: (isStart: Boolean, x: Float, y: Float) -> Unit = { _, _, _ -> },
    /** 选择态：一次拖动结束（结束变换会话 + 落盘） */
    onSelectionDragEnd: () -> Unit = {},
    // ── 正交步进旋转（旋转角手柄点击 90°）──
    orthoStep: Boolean = false,
    onStepRotatePending: (Float) -> Unit = {},
    onStepRotateSelected: (Float) -> Unit = {},
    // ── ArrayOverlay 手柄联动（阵列手柄按下时同步暂停画布单指处理） ──
    arrayHandleActive: () -> Boolean = { false },
    // ── Layer backdrop（液态玻璃的采样源：画布层捕获） ──
    canvasBackdrop: LayerBackdrop? = null,
    // ── Modifier ──
    modifier: Modifier = Modifier
) {
    // Track whether PFO handles are being dragged; DrawingCanvas reads this
    // to skip its own 1‑finger touch handler.
    var isHandleActive by remember { mutableStateOf(false) }

    // ⚠️ 捕获层只含画布内容。玻璃元素（PCO 内 NudgePad / 状态栏 / 主界面 NudgePad）必须
    // 在捕获 Box 之外——玻璃采样"包含自身的层"会形成循环，库官方文档明确会崩溃
    // （Fatal signal 11 SIGSEGV in RenderThread）。2026-10-14 修复。
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxSize().let { if (canvasBackdrop != null) it.layerBackdrop(canvasBackdrop) else it }
        ) {
            // ── Layer 1: DrawingCanvas (renders content + handles zoom at Main pass) ──
            DrawingCanvas(
                primitives = primitives,
                currentPrimitive = currentPrimitive,
                layers = layers,
                canvasScale = canvasScale,
                canvasOffsetX = canvasOffsetX,
                canvasOffsetY = canvasOffsetY,
                pendingEdit = pendingEdit,
                currentTool = currentTool,
                currentLineStyle = currentLineStyle,
                selectedIndices = selectedIndices,
                isTransforming = isTransforming,
                globalLineScale = globalLineScale,
                blockDefs = blockDefs,
                images = images,
                imageBitmaps = imageBitmaps,
                imageManageActive = imageManageActive,
                selectedImageId = selectedImageId,
                eraserRadius = eraserRadius,
                eraserTouchPoint = eraserTouchPoint,
                quickEraseEnabled = quickEraseEnabled,
                isHandleActive = { isHandleActive || arrayHandleActive() },
                onLongPressEraser = onLongPressEraser,
                onCanvasTransform = onCanvasTransform,
                onTouchStart = onTouchStart,
                onTouchMove = onTouchMove,
                onTouchEnd = onTouchEnd,
                onTouchCancel = onTouchCancel,
                modifier = Modifier.fillMaxSize()
            )
        }

        // ── Layer 2: PostCreationOverlay (handles, buttons, preview) ──
        PostCreationOverlay(
            pendingEdit = pendingEdit,
            onConfirm = onConfirm,
            onCancel = onCancel,
            onUpdateOffset = onUpdateOffset,
            onUpdateRotation = onUpdateRotation,
            onUpdateScale = onUpdateScale,
            onUpdatePrimitive = onUpdatePrimitive,
            onUpdateFontScale = onUpdateFontScale,
            onUpdateArrowSpan = onUpdateArrowSpan,
            onToggleTextOrientation = onToggleTextOrientation,
            onToggleRangeReversed = onToggleRangeReversed,
            currentFontSize = currentFontSize,
            selection = selection,
            onMoveSelected = onMoveSelected,
            onRotateSelected = onRotateSelected,
            onScaleSelected = onScaleSelected,
            onRectMidpointDrag = onRectMidpointDrag,
            rectDragHandleWorld = rectDragHandleWorld,
            onMoveSelectedLineEndpoint = onMoveSelectedLineEndpoint,
            onSelectionDragEnd = onSelectionDragEnd,
            onTransformEnd = {
                isHandleActive = false
                onTransformEnd()
            },
            onHandleActiveChanged = { active -> isHandleActive = active },
            orthoStep = orthoStep,
            onStepRotatePending = onStepRotatePending,
            onStepRotateSelected = onStepRotateSelected,
            primitives = primitives,
            modifier = Modifier.fillMaxSize(),
            canvasScale = canvasScale,
            canvasOffsetX = canvasOffsetX,
            canvasOffsetY = canvasOffsetY,
            globalLineScale = globalLineScale,
            blockDefs = blockDefs,
            canvasBackdrop = canvasBackdrop,
        )
    }
}
