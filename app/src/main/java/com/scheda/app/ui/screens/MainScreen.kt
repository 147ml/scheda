package com.scheda.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scheda.app.file.RecoveryManager
import com.scheda.app.file.SchedaSerializer
import com.scheda.app.file.ShareUtil
import com.scheda.app.file.StorageManager
import com.scheda.app.model.*
import com.scheda.app.ui.canvas.DrawingCanvas
import com.scheda.app.ui.canvas.PostCreationOverlay
import com.scheda.app.ui.canvas.SelectionOverlay
import com.scheda.app.ui.components.BottomToolbar
import com.scheda.app.ui.components.CompactSlider
import com.scheda.app.ui.components.DrawingViewModelFactory
import com.scheda.app.ui.components.HomeViewModelFactory
import com.scheda.app.ui.components.LayerDialog
import com.scheda.app.ui.home.HomeScreen
import com.scheda.app.viewmodel.DrawingViewModel
import com.scheda.app.viewmodel.HomeViewModel
import java.io.File
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    val sm = remember { StorageManager(ctx) }
    val ser = remember { SchedaSerializer(ctx) }
    val rec = remember { RecoveryManager(ser) }
    val sh = remember { ShareUtil(ctx, ser) }
    val dvm: DrawingViewModel = viewModel(factory = remember { DrawingViewModelFactory(sm, ser, rec) })
    val hvm: HomeViewModel = viewModel(factory = remember { HomeViewModelFactory(sm, sh, ser) })

    val setupInit = remember { sm.isInitialized }
    var setup by remember { mutableStateOf(setupInit) }
    var curFile by remember { mutableStateOf<File?>(null) }
    var curName by remember { mutableStateOf("") }
    var needPerm by remember { mutableStateOf(false) }

    // SAF 目录选择器
    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            sm.setRootFromTreeUri(uri)
            setup = true
            needPerm = !sm.isAccessible
            if (!needPerm) hvm.init()
        }
    }

    // 启动时检查权限，以及每次 ON_RESUME 重新检查
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (setup && sm.isInitialized && !sm.isAccessible) {
                    needPerm = true
                } else if (setup && needPerm && sm.isAccessible) {
                    needPerm = false
                    hvm.init()
                } else if (setup && !needPerm) {
                    if (!sm.isInitialized) {
                        setup = false
                    } else {
                        hvm.refresh()
                    }
                }
            }
        }
        lifecycle.addObserver(observer)
        // 立即执行一次初始检查
        if (sm.isInitialized && !sm.isAccessible) {
            needPerm = true
        } else if (sm.isInitialized && sm.isAccessible) {
            hvm.init()
            needPerm = false
        }
        onDispose { lifecycle.removeObserver(observer) }
    }

    if (!setup) {
        SetupScreen(onSel = { treePicker.launch(null) })
        return
    }

    if (needPerm) {
        Dialog(onDismissRequest = { }) {
            Surface(shape = RoundedCornerShape(16.dp), color = Color(0xCC222222), modifier = Modifier.width(320.dp)) {
                Column(Modifier.padding(24.dp)) {
                    Icon(Icons.Outlined.Folder, null, Modifier.size(40.dp).align(Alignment.CenterHorizontally), tint = Color(0xFF1565C0))
                    Spacer(Modifier.height(16.dp))
                    Text("需要文件访问权限", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        modifier = Modifier.align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(12.dp))
                    Text("Scheda 需要「所有文件访问」权限\n来读写图纸。", fontSize = 14.sp, color = Color(0xFFCCCCCC))
                    Spacer(Modifier.height(8.dp))
                    Text("请前往系统设置开启。", fontSize = 14.sp, color = Color(0xFF999999))
                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { needPerm = false; setup = false },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFCCCCCC))) { Text("稍后") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:com.scheda.app"))
                            ctx.startActivity(intent)
                        }, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF64B5F6))) { Text("前往设置") }
                    }
                }
            }
        }
        return
    }

    var screen by remember { mutableStateOf("home") }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        // 预组合重型组件（全尺寸透明，z-order 底层），预热类加载 + SlotTable + Canvas 上下文
        Box(Modifier.fillMaxSize().alpha(0f)) {
            Scaffold(
                topBar = {
                    TopAppBar(title = { Text("") },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color(0xFF2A2A2A), titleContentColor = Color.White)
                    )
                },
                bottomBar = { BottomToolbar(viewModel = dvm, onShowNumberDialog = {}) }
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    DrawingCanvas(
                        primitives = dvm.primitives, currentPrimitive = dvm.currentPrimitive,
                        layers = dvm.layers, canvasScale = dvm.canvasScale,
                        canvasOffsetX = dvm.canvasOffsetX, canvasOffsetY = dvm.canvasOffsetY,
                        pendingEdit = dvm.pendingEdit, currentTool = dvm.currentTool,
                        currentLineStyle = dvm.currentLineStyle,
                        selectedIndices = dvm.selection.selectedIndices,
                        globalLineScale = dvm.globalLineScale,
                        blockDefs = dvm.blockDefs,
                        eraserRadius = dvm.eraserRadius,
                        eraserTouchPoint = dvm.eraserTouchPoint,
                        quickEraseEnabled = dvm.quickEraseEnabled,
                        onLongPressEraser = {}, onTouchStart = {}, onTouchMove = {},
                        onTouchEnd = {}, onTouchCancel = {},
                        onCanvasTransform = { _, _, _ -> },
                        modifier = Modifier.fillMaxSize()
                    )
                    PostCreationOverlay(dvm.pendingEdit,
                        onConfirm = {}, onCancel = {},
                        onUpdateOffset = { _, _ -> }, onUpdateRotation = {},
                        onUpdateScale = { _, _ -> }, onUpdateFontScale = {},
                        onUpdateArrowSpan = {}, onUpdateRangeValue = { _, _ -> },
                        onToggleTextOrientation = {}, onToggleRangeReversed = {},
                        currentFontSize = dvm.getPendingEffectiveFontSize(),
                        onCanvasTransform = { _, _, _ -> },
                        modifier = Modifier.fillMaxSize(),
                        canvasScale = dvm.canvasScale,
                        canvasOffsetX = dvm.canvasOffsetX, canvasOffsetY = dvm.canvasOffsetY,
                        globalLineScale = dvm.globalLineScale,
                        blockDefs = dvm.blockDefs
                    )
                    SelectionOverlay(
                        selection = dvm.selection,
                        canvasScale = dvm.canvasScale,
                        canvasOffsetX = dvm.canvasOffsetX, canvasOffsetY = dvm.canvasOffsetY,
                        onExecuteAction = {}, onClearSelection = {},
                        modifier = Modifier.fillMaxSize(),
                        onMoveSelected = { _, _ -> }, onRotateSelected = {},
                        onScaleSelected = { _, _ -> }, onTransformEnd = {}
                    )
                    FloatingStatusBar(
                        viewModel = dvm,
                        onShowNumberDialog = {}, onShowRangeDialog = {}
                    )
                }
            }
        }

        AnimatedContent(
            targetState = screen,
        transitionSpec = {
            if (targetState == "drawing") {
                // 进入画图：新页面从右滑入，旧页面向左滑出
                (fadeIn() + slideInHorizontally { it }) togetherWith
                (fadeOut() + slideOutHorizontally { -it })
            } else {
                // 返回首页：新页面从左滑入，旧页面向右滑出
                (fadeIn() + slideInHorizontally { -it }) togetherWith
                (fadeOut() + slideOutHorizontally { it })
            }
        }
    ) { currentScreen ->
        when (currentScreen) {
            "home" -> {
            var showNewDlg by remember { mutableStateOf(false) }
            var newType by remember { mutableStateOf("drawing") }
            var nf by remember { mutableStateOf("") }

            HomeScreen(
                vm = hvm,
                onOpen = { f, name ->
                    curFile = f
                    curName = name
                    screen = "drawing"
                    scope.launch {
                        val doc = withContext(Dispatchers.IO) { sm.loadDocument(f) }
                        if (doc != null) {
                            val data = withContext(Dispatchers.Default) { ser.fromDocument(doc) }
                            withContext(Dispatchers.Main) {
                                if (data.primitives.isEmpty()) {
                                    Toast.makeText(ctx, "空白图纸，无任何元素", Toast.LENGTH_SHORT).show()
                                }
                                dvm.loadExistingData(data, f)
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(ctx, "无法打开文件", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                onNew = { showNewDlg = true }
            )

            if (showNewDlg) {
                val focusReq = remember { FocusRequester() }
                LaunchedEffect(Unit) { delay(100); focusReq.requestFocus() }
                var nfTv by remember { mutableStateOf(TextFieldValue(nf)) }
                Dialog(onDismissRequest = { showNewDlg = false; nf = "" }) {
                    Surface(shape = RoundedCornerShape(16.dp), color = Color(0xCC222222), modifier = Modifier.width(300.dp)) {
                        Column(Modifier.padding(20.dp)) {
                            Text("新建", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(nfTv, { nfTv = it; nf = it.text }, label = { Text("名称") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth().focusRequester(focusReq).onFocusChanged { if (it.isFocused) nfTv = nfTv.copy(selection = TextRange(0, nfTv.text.length)) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF64B5F6), unfocusedBorderColor = Color(0xFF666666),
                                    cursorColor = Color.White, focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                    focusedLabelColor = Color(0xFF64B5F6), unfocusedLabelColor = Color(0xFFAAAAAA)))
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(newType == "drawing", { newType = "drawing" },
                                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF64B5F6), unselectedColor = Color(0xFF666666)))
                                Text(" 画图", fontSize = 12.sp, color = Color(0xFFCCCCCC), modifier = Modifier.padding(end = 16.dp))
                                RadioButton(newType == "folder", { newType = "folder" },
                                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF64B5F6), unselectedColor = Color(0xFF666666)))
                                Text(" 文件夹", fontSize = 12.sp, color = Color(0xFFCCCCCC))
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { showNewDlg = false; nf = "" },
                                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFCCCCCC))) { Text("取消") }
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = {
                                    if (nf.isNotBlank()) {
                                        when (newType) {
                                            "drawing" -> {
                                                val f = sm.createDocument(nf.trim())
                                                if (f != null) {
                                                    curFile = f; curName = nf.trim()
                                                    screen = "drawing"
                                                    val doc = SchedaDocument(
                                                        name = nf.trim(), primitives = emptyList(),
                                                        layers = listOf(SerializableLayer(id = 0, name = "图层0")),
                                                        activeLayerId = 0, blockDefs = emptyList(),
                                                        canvasScale = 1f, canvasOffsetX = 0f, canvasOffsetY = 0f
                                                    )
                                                    sm.saveToFile(f, doc)
                                                    dvm.loadExistingDocument(doc, f)
                                                } else {
                                                    Toast.makeText(ctx, "同名图纸已存在", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                            "folder" -> {
                                                if (!hvm.newFolder(nf.trim())) {
                                                    Toast.makeText(ctx, "同名文件夹已存在或创建失败", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    showNewDlg = false; nf = ""
                                                }
                                            }
                                        }
                                        showNewDlg = false; nf = ""
                                    }
                                }, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF64B5F6))) { Text("确定") }
                            }
                        }
                    }
                }
            }
        }
        "drawing" -> DrawingScreen(
            nm = curName,
            vm = dvm,
            sh = sh,
            onBack = {
                dvm.saveSettings()
                curFile?.let { sm.saveToFile(it, dvm.buildDocument()) }
                hvm.refresh()
                screen = "home"
            },
            onBlockEditor = {
                screen = "blockEditor"
            }
        )
        "blockEditor" -> BlockEditorScreen(
            vm = dvm,
            sm = sm,
            ctx = ctx,
            onSave = { name ->
                dvm.saveBlockEditorBlock(name)
                screen = "drawing"
            },
            onBack = {
                dvm.blockEditorCancelPrimitive()
                dvm.cancelBlockDraft()
                screen = "drawing"
            }
        )
    }
    }
    }
}

// ─── Setup ─────────────────────────────────────────────

@Composable
private fun SetupScreen(onSel: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = Color(0xFF2A2A2A)) {
        Column(Modifier.fillMaxSize().padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            // Logo 图标
            Surface(
                modifier = Modifier.size(80.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF1565C0)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Draw, null, Modifier.size(40.dp), tint = Color.White)
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("Scheda", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, letterSpacing = 2.sp)
            Spacer(Modifier.height(8.dp))
            Text("随手画", fontSize = 16.sp, color = Color(0xFF999999))
            Spacer(Modifier.height(4.dp))
            Text("简单、流畅的工程画图工具", fontSize = 13.sp, color = Color(0xFF666666))
            Spacer(Modifier.height(48.dp))
            Surface(
                onClick = onSel,
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF1565C0),
                modifier = Modifier.width(200.dp)
            ) {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.FolderOpen, null, Modifier.size(20.dp), tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("选择存储目录", fontSize = 15.sp, color = Color.White)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("需要「所有文件访问」权限来读写图纸", fontSize = 12.sp, color = Color(0xFF666666))
        }
    }
}

// ─── Drawing Screen ────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun DrawingScreen(
    nm: String,
    vm: DrawingViewModel,
    sh: ShareUtil,
    onBack: () -> Unit,
    onBlockEditor: () -> Unit
) {
    val ctx = LocalContext.current
    var canvasSz by remember { mutableStateOf(IntSize.Zero) }
    var numDlg by remember { mutableStateOf(false) }
    var textDlg by remember { mutableStateOf(false) }
    var rangeDlg by remember { mutableStateOf(false) }
    var layerDlg by remember { mutableStateOf(false) }
    var insertBlockDlg by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/dxf")
    ) { uri -> uri?.let { vm.exportDxf(ctx, it); Toast.makeText(ctx, "已导出 DXF", Toast.LENGTH_SHORT).show() } }

    BackHandler {
        if (vm.pendingEdit.active) vm.confirmPendingEdit()
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(nm.ifBlank { "未命名" }, fontWeight = FontWeight(500)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (vm.pendingEdit.active) vm.confirmPendingEdit()
                        onBack()
                    }) { Icon(Icons.Outlined.ArrowBack, "返回") }
                },
                actions = {
                    IconButton(onClick = { vm.undo() }, enabled = vm.canUndo, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Undo, "撤销")
                    }
                    IconButton(onClick = { vm.redo() }, enabled = vm.canRedo, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Redo, "重做")
                    }
                    IconButton(onClick = { layerDlg = true }) {
                        Icon(Icons.Outlined.Layers, "图层")
                    }
                    IconButton(onClick = { insertBlockDlg = true }) {
                        Icon(Icons.Outlined.Widgets, "图块")
                    }
                    IconButton(onClick = {
                        vm.manualSave(ctx)
                        val f = vm.getDocumentFile()
                        if (f != null) sh.shareFile(f)
                    }) { Icon(Icons.Outlined.Share, "分享") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF2A2A2A),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        },
        bottomBar = {
            BottomToolbar(
                viewModel = vm,
                onShowNumberDialog = { numDlg = true },
                onShowTextDialog = { textDlg = true },
                onShowRangeDialog = { rangeDlg = true }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).onSizeChanged { canvasSz = it }) {
            DrawingCanvas(
                primitives = vm.primitives, currentPrimitive = vm.currentPrimitive,
                layers = vm.layers, canvasScale = vm.canvasScale,
                canvasOffsetX = vm.canvasOffsetX, canvasOffsetY = vm.canvasOffsetY,
                pendingEdit = vm.pendingEdit, currentTool = vm.currentTool,
                currentLineStyle = vm.currentLineStyle,
                selectedIndices = vm.selection.selectedIndices,
                globalLineScale = vm.globalLineScale,
                blockDefs = vm.blockDefs,
                eraserRadius = vm.eraserRadius,
                eraserTouchPoint = vm.eraserTouchPoint,
                quickEraseEnabled = vm.quickEraseEnabled,
                onLongPressEraser = { vm.enterTemporaryEraser() },
                onTouchStart = { vm.startPrimitive(it) },
                onTouchMove = { vm.updatePrimitive(it) },
                onTouchEnd = { vm.commitPrimitive() },
                onTouchCancel = { vm.cancelPrimitive() },
                onCanvasTransform = { z, c, p -> vm.transformCanvas(z, c, p) },
                modifier = Modifier.fillMaxSize()
            )
            PostCreationOverlay(vm.pendingEdit,
                onConfirm = { vm.confirmPendingEdit() },
                onCancel = { vm.cancelPendingEdit() },
                onUpdateOffset = { dx, dy -> vm.updatePendingOffset(dx, dy) },
                onUpdateRotation = { r -> vm.updatePendingRotation(r) },
                onUpdateScale = { sx, sy -> vm.updatePendingScale(sx, sy) },
                onUpdateFontScale = { delta -> vm.updatePendingFontScale(delta) },
                onUpdateArrowSpan = { factor -> vm.updatePendingArrowSpan(factor) },
                onUpdateRangeValue = { isStart, value -> vm.updatePendingRangeValue(isStart, value) },
                onToggleTextOrientation = { vm.toggleHorizontalText() },
                onToggleRangeReversed = { vm.toggleRangeReversed() },
                currentFontSize = vm.getPendingEffectiveFontSize(),
                onCanvasTransform = { z, c, p -> vm.transformCanvas(z, c, p) },
                modifier = Modifier.fillMaxSize(),
                canvasScale = vm.canvasScale,
                canvasOffsetX = vm.canvasOffsetX, canvasOffsetY = vm.canvasOffsetY,
                globalLineScale = vm.globalLineScale,
                blockDefs = vm.blockDefs
            )
            SelectionOverlay(
                selection = vm.selection,
                canvasScale = vm.canvasScale,
                canvasOffsetX = vm.canvasOffsetX, canvasOffsetY = vm.canvasOffsetY,
                onExecuteAction = { vm.executeSelectionAction(it) },
                onClearSelection = { vm.clearSelection() },
                modifier = Modifier.fillMaxSize(),
                onMoveSelected = { dx, dy -> vm.moveSelectedPrimitives(dx, dy) },
                onRotateSelected = { r -> vm.rotateSelectedPrimitives(r) },
                onScaleSelected = { sx, sy -> vm.scaleSelectedPrimitives(sx, sy) },
                onTransformEnd = { vm.finalizeSelectionTransform() }
            )

            // Floating status bar — centered on canvas, above overlays
            FloatingStatusBar(
                viewModel = vm,
                onShowNumberDialog = { numDlg = true },
                onShowRangeDialog = { rangeDlg = true }
            )

            // Eraser notification — top bar (always on top of all overlays)
            if (vm.currentTool == ToolType.ERASER) {
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xCCE53935),
                    shadowElevation = 4.dp
                ) {
                    Text(
                        if (vm.isTemporaryEraser) "长按启用橡皮擦中，松手恢复原工具"
                        else "橡皮擦功能正在使用",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }

    // ── 对话框 ──────────────────────────────────────

    if (numDlg) {
        val focusReq = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(100); focusReq.requestFocus() }
        val initVal = vm.numberLabel.currentValue.toString()
        var sv by remember { mutableStateOf(TextFieldValue(text = initVal, selection = TextRange(0, initVal.length))) }
        Dialog(onDismissRequest = { numDlg = false }) {
            Surface(shape = RoundedCornerShape(16.dp), color = Color(0xCC222222), modifier = Modifier.width(300.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("标注数字", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(sv, { sv = it.text.filter { c -> c.isDigit() }.let { s -> TextFieldValue(s, TextRange(s.length)) } },
                        label = { Text("起始值") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(focusReq).onFocusChanged { if (it.isFocused) sv = sv.copy(selection = TextRange(0, sv.text.length)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF64B5F6), unfocusedBorderColor = Color(0xFF666666),
                            cursorColor = Color.White, focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedLabelColor = Color(0xFF64B5F6), unfocusedLabelColor = Color(0xFFAAAAAA)))
                    Spacer(Modifier.height(4.dp))
                    Text("当前: ${vm.numberLabel.currentValue}", fontSize = 14.sp, color = Color(0xFFCCCCCC))
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(!vm.numberLabel.horizontalOnly, { vm.toggleHorizontalText() },
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF1565C0), uncheckedColor = Color(0xFF666666), checkmarkColor = Color.White))
                        Text("竖向", fontSize = 12.sp, color = Color(0xFFCCCCCC))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { numDlg = false },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFCCCCCC))) { Text("取消") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            vm.setNumberLabelStart(sv.text.toIntOrNull() ?: 1)
                            vm.setTool(ToolType.ANNOTATE)
                            numDlg = false
                        }, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF64B5F6))) { Text("开始") }
                    }
                }
            }
        }
    }

    // ── 区间数字对话框 ──────────────────────────────
    // ── 区间数字对话框 ──────────────────────────────
    if (rangeDlg) {
        val focusStart = remember { FocusRequester() }
        val focusEnd = remember { FocusRequester() }
        val isAutoIncremented = vm.rangeLabel.lastEndValue > 1
        val initialStart = vm.rangeLabel.startValue
        val initialEnd = if (isAutoIncremented) vm.rangeLabel.endValue.toString() else ""
        LaunchedEffect(Unit) { delay(100); if (isAutoIncremented) focusEnd.requestFocus() else focusStart.requestFocus() }
        var svTv by remember { mutableStateOf(TextFieldValue(initialStart.toString())) }
        var evTv by remember { mutableStateOf(TextFieldValue(initialEnd)) }
        Dialog(onDismissRequest = { rangeDlg = false }) {
            Surface(shape = RoundedCornerShape(16.dp), color = Color(0xCC222222), modifier = Modifier.width(300.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("区间数字", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    val fieldColors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF64B5F6), unfocusedBorderColor = Color(0xFF666666),
                        cursorColor = Color.White, focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedLabelColor = Color(0xFF64B5F6), unfocusedLabelColor = Color(0xFFAAAAAA))
                    OutlinedTextField(svTv, { svTv = it.text.filter { c -> c.isDigit() }.let { s -> TextFieldValue(s, TextRange(s.length)) } },
                        label = { Text("首数字") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(focusStart).onFocusChanged { if (it.isFocused) svTv = svTv.copy(selection = TextRange(0, svTv.text.length)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = fieldColors)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(evTv, { evTv = it.text.filter { c -> c.isDigit() }.let { s -> TextFieldValue(s, TextRange(s.length)) } },
                        label = { Text("末数字") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(focusEnd).onFocusChanged { if (it.isFocused) evTv = evTv.copy(selection = TextRange(0, evTv.text.length)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = fieldColors)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(!vm.rangeLabel.horizontalOnly, { vm.toggleRangeOrientation() },
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF1565C0), uncheckedColor = Color(0xFF666666), checkmarkColor = Color.White))
                        Text("竖向", fontSize = 12.sp, color = Color(0xFFCCCCCC))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { rangeDlg = false },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFCCCCCC))) { Text("取消") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            if (evTv.text.isBlank()) { focusEnd.requestFocus(); return@TextButton }
                            val s = svTv.text.filter { c -> c.isDigit() }.toIntOrNull() ?: initialStart
                            val e = evTv.text.filter { c -> c.isDigit() }.toIntOrNull() ?: (s + 1)
                            vm.setRangeValues(s, e)
                            vm.setTool(ToolType.RANGE)
                            rangeDlg = false
                        }, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF64B5F6))) { Text("开始") }
                    }
                }
            }
        }
    }

    if (textDlg) {
        val focusReq = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(100); focusReq.requestFocus() }
        var tcTv by remember { mutableStateOf(TextFieldValue("")) }
        var isVertical by remember { mutableStateOf(false) }
        Dialog(onDismissRequest = { textDlg = false; tcTv = TextFieldValue("") }) {
            Surface(shape = RoundedCornerShape(16.dp), color = Color(0xCC222222), modifier = Modifier.width(300.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("输入文字", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(tcTv, { tcTv = it }, label = { Text("文本") }, singleLine = false, maxLines = 3,
                        modifier = Modifier.fillMaxWidth().focusRequester(focusReq).onFocusChanged { if (it.isFocused) tcTv = tcTv.copy(selection = TextRange(0, tcTv.text.length)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF64B5F6), unfocusedBorderColor = Color(0xFF666666),
                            cursorColor = Color.White, focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedLabelColor = Color(0xFF64B5F6), unfocusedLabelColor = Color(0xFFAAAAAA)))
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(isVertical, { isVertical = !isVertical },
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF1565C0), uncheckedColor = Color(0xFF666666), checkmarkColor = Color.White))
                        Text("竖向", fontSize = 12.sp, color = Color(0xFFCCCCCC))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { textDlg = false; tcTv = TextFieldValue("") },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFCCCCCC))) { Text("取消") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            if (tcTv.text.isNotBlank()) {
                                vm.setPendingTextContent(tcTv.text)
                                vm.addTextAtCenter(canvasSz.width.toFloat(), canvasSz.height.toFloat(), !isVertical)
                                textDlg = false
                            }
                        }, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF64B5F6))) { Text("确定") }
                    }
                }
            }
        }
    }

    if (layerDlg) {
        LayerDialog(viewModel = vm, onDismiss = { layerDlg = false })
    }

    if (insertBlockDlg) {
        BlockManagerDialog(
            blockDefs = vm.blockDefs,
            onInsert = { b -> vm.startBlockInsert(b, canvasSz.width.toFloat(), canvasSz.height.toFloat()); insertBlockDlg = false },
            onDelete = { ids -> ids.forEach { vm.deleteBlockDef(it) } },
            onEdit = { bd ->
                vm.editBlockDef(bd.id)
                insertBlockDlg = false
                onBlockEditor()
            },
            onNewBlock = { insertBlockDlg = false; vm.enterBlockEditor(); onBlockEditor() },
            onDismiss = { insertBlockDlg = false }
        )
    }

    if (showClearConfirm) {
        AlertDialog(onDismissRequest = { showClearConfirm = false }, title = { Text("清空画布") },
            text = { Text("确定要删除所有绘制内容吗？") },
            confirmButton = { TextButton(onClick = { vm.clearAll(); showClearConfirm = false }) { Text("确认", color = Color(0xFFC62828)) } },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("取消") } })
    }

    if (vm.showPropertiesDlg) {
        PropertiesDialog(vm)
    }
}

// ─── Floating Status Bar ──────────────────────────────

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun BoxScope.FloatingStatusBar(
    viewModel: DrawingViewModel,
    onShowNumberDialog: () -> Unit,
    onShowRangeDialog: () -> Unit
) {
    val tool = viewModel.currentTool
    val toolName = tool.displayName
    Surface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 8.dp)
            .wrapContentWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF2A2A2A),
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.height(36.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(toolName, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFFCCCCCC))
            if (tool != ToolType.SELECT) {
                Spacer(Modifier.width(8.dp))
            }
            when (tool) {
                ToolType.ERASER -> {
                    Text("半径:", fontSize = 11.sp, color = Color(0xFFCCCCCC))
                    Spacer(Modifier.width(2.dp))
                    Text("${viewModel.displayEraserRadius.toInt()}", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.width(30.dp))
                    Spacer(Modifier.width(4.dp))
                    CompactSlider(
                        value = viewModel.displayEraserRadius,
                        onValueChange = { viewModel.setEraserRadius(it) },
                        valueRange = 5f..100f,
                        modifier = Modifier.width(80.dp).height(32.dp),
                        thumbSize = 10.dp,
                        trackHeight = 8.dp
                    )
                    Spacer(Modifier.width(6.dp))
                    SmallToggleChip(
                        label = "长按",
                        selected = viewModel.quickEraseEnabled,
                        onClick = { viewModel.toggleQuickEraseEnabled() }
                    )
                    Spacer(Modifier.width(4.dp))
                    SmallToggleChip(
                        label = "精细",
                        selected = viewModel.fineEraseEnabled,
                        onClick = { viewModel.toggleFineEraseEnabled() }
                    )
                }
                ToolType.ANNOTATE -> {
                    val pe = viewModel.pendingEdit
                    val peActive = pe.isActive()
                    val peNum = pe.primitive as? DrawingPrimitive.NumberLabelPrimitive
                    val displayNum = if (peNum != null) peNum.value.toString() else viewModel.numberLabel.currentValue.toString()
                    Text(
                        text = displayNum,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF64B5F6),
                        modifier = Modifier.clickable { onShowNumberDialog() }
                    )
                    Spacer(Modifier.width(6.dp))
                    // Font size slider
                    val currentFs = if (peActive) viewModel.getPendingEffectiveFontSize() else viewModel.numberLabel.fontSize
                    Text("字号", fontSize = 10.sp, color = Color(0xFFCCCCCC))
                    Spacer(Modifier.width(2.dp))
                    CompactSlider(
                        value = currentFs,
                        onValueChange = { newSize ->
                            if (peActive && pe.primitive is DrawingPrimitive.NumberLabelPrimitive)
                                viewModel.updatePendingFontSize(newSize)
                            else
                                viewModel.setNumberFontSize(newSize)
                        },
                        valueRange = 30f..200f,
                        modifier = Modifier.width(80.dp).height(32.dp),
                        thumbSize = 10.dp,
                        trackHeight = 8.dp
                    )
                    Text("${currentFs.toInt()}", fontSize = 12.sp, color = Color(0xFFCCCCCC), modifier = Modifier.width(20.dp))
                    Spacer(Modifier.width(4.dp))
                    // Orientation toggle
                    val isHoriz = if (peActive) {
                        (pe.primitive as? DrawingPrimitive.NumberLabelPrimitive)?.horizontalOnly ?: viewModel.numberLabel.horizontalOnly
                    } else viewModel.numberLabel.horizontalOnly
                    IconButton(onClick = { viewModel.toggleHorizontalText() }, modifier = Modifier.size(26.dp)) {
                        Text(if (isHoriz) "横" else "竖", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                ToolType.RANGE -> {
                    val pe = viewModel.pendingEdit
                    val peActive = pe.isActive()
                    val peRange = pe.primitive as? DrawingPrimitive.RangeLabelPrimitive
                    val startVal = peRange?.startValue ?: viewModel.rangeLabel.startValue
                    val endVal = peRange?.endValue ?: viewModel.rangeLabel.endValue
                    Text(
                        text = "$startVal→$endVal",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF64B5F6),
                        modifier = Modifier.clickable { onShowRangeDialog() }
                    )
                    Spacer(Modifier.width(4.dp))
                    // Font size slider
                    val currentFs = if (peActive && peRange != null) viewModel.getPendingEffectiveFontSize() else viewModel.rangeLabel.fontSize
                    Text("字号", fontSize = 12.sp, color = Color(0xFFCCCCCC))
                    Spacer(Modifier.width(4.dp))
                    CompactSlider(
                        value = currentFs,
                        onValueChange = { if (peActive && peRange != null) viewModel.updatePendingRangeFontSize(it) else viewModel.setRangeFontSize(it) },
                        valueRange = 20f..200f,
                        modifier = Modifier.width(80.dp).height(32.dp),
                        thumbSize = 10.dp,
                        trackHeight = 8.dp
                    )
                    Text("${currentFs.toInt()}", fontSize = 12.sp, color = Color(0xFFCCCCCC), modifier = Modifier.width(28.dp))
                    Spacer(Modifier.width(6.dp))
                    SmallToggleChip(
                        label = "⇄",
                        selected = viewModel.rangeLabel.reversed,
                        onClick = { viewModel.toggleRangeReversed() },
                        modifier = Modifier.width(32.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    SmallToggleChip(
                        label = if (viewModel.rangeLabel.horizontalOnly) "横" else "竖",
                        selected = !viewModel.rangeLabel.horizontalOnly,
                        onClick = { viewModel.toggleRangeOrientation() },
                        modifier = Modifier.width(32.dp)
                    )
                }
                ToolType.FREEHAND -> {
                    val ct = viewModel.currentLineStyle
                    val ltLabel = when (ct.type) { LineType.DASHED -> "虚线"; LineType.LIGHTNING -> "闪电"; else -> "实线" }
                    Text("线宽:${viewModel.strokeWidth.toInt()} $ltLabel", fontSize = 11.sp, color = Color(0xFFAAAAAA))
                }
                ToolType.LINE -> {
                    val ct = viewModel.currentLineStyle
                    val ltLabel = when (ct.type) { LineType.DASHED -> "虚线"; LineType.LIGHTNING -> "闪电"; else -> "实线" }
                    Text("线宽:${viewModel.strokeWidth.toInt()} $ltLabel", fontSize = 11.sp, color = Color(0xFFAAAAAA))
                    Spacer(Modifier.width(6.dp))
                    SmallToggleChip(
                        label = "标准",
                        selected = viewModel.lineSnapMode,
                        onClick = { viewModel.toggleLineSnapMode() }
                    )
                }
                ToolType.RECTANGLE -> {
                    val ct = viewModel.currentLineStyle
                    val ltLabel = when (ct.type) { LineType.DASHED -> "虚线"; LineType.LIGHTNING -> "闪电"; else -> "实线" }
                    Text("线宽:${viewModel.strokeWidth.toInt()} $ltLabel", fontSize = 11.sp, color = Color(0xFFAAAAAA))
                    Spacer(Modifier.width(6.dp))
                    SmallToggleChip(
                        label = "标准",
                        selected = viewModel.rectangleSquareMode,
                        onClick = { viewModel.toggleRectangleSquareMode() }
                    )
                }
                ToolType.CIRCLE -> {
                    val ct = viewModel.currentLineStyle
                    val ltLabel = when (ct.type) { LineType.DASHED -> "虚线"; LineType.LIGHTNING -> "闪电"; else -> "实线" }
                    Text("线宽:${viewModel.strokeWidth.toInt()} $ltLabel", fontSize = 11.sp, color = Color(0xFFAAAAAA))
                    Spacer(Modifier.width(6.dp))
                    SmallToggleChip(
                        label = "标准",
                        selected = viewModel.circleCircleMode,
                        onClick = { viewModel.toggleCircleCircleMode() }
                    )
                }
                ToolType.TEXT -> {
                    val pe = viewModel.pendingEdit
                    val peActive = pe.isActive()
                    val peText = pe.primitive as? DrawingPrimitive.TextPrimitive
                    val displayText = if (peText != null) peText.text.take(8) else "文本"
                    Text(displayText, fontSize = 12.sp, color = Color(0xFF64B5F6), fontWeight = FontWeight.Bold, maxLines = 1)
                    Spacer(Modifier.width(6.dp))
                    // Font size slider
                    val currentFs = if (peActive) viewModel.getPendingEffectiveFontSize() else viewModel.getLastTextFontSize()
                    Text("字号", fontSize = 10.sp, color = Color(0xFFCCCCCC))
                    Spacer(Modifier.width(2.dp))
                    CompactSlider(
                        value = currentFs,
                        onValueChange = { viewModel.updatePendingFontSize(it) },
                        valueRange = 30f..200f,
                        modifier = Modifier.width(80.dp).height(32.dp),
                        thumbSize = 10.dp,
                        trackHeight = 8.dp
                    )
                    Text("${currentFs.toInt()}", fontSize = 12.sp, color = Color(0xFFCCCCCC), modifier = Modifier.width(20.dp))
                    Spacer(Modifier.width(4.dp))
                    // Orientation toggle
                    val isHoriz = peText?.horizontalOnly ?: true
                    IconButton(onClick = { viewModel.toggleHorizontalText() }, modifier = Modifier.size(26.dp)) {
                        Text(if (isHoriz) "横" else "竖", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                else -> {}
            }
        }
    }
}

@Composable
private fun SmallToggleChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Color(0xFF1565C0) else Color.Transparent,
        border = if (!selected) BorderStroke(1.dp, Color(0xFF666666)) else null,
        modifier = modifier.height(22.dp).clickable { onClick() }
    ) {
        Box(modifier = Modifier.fillMaxHeight().padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 10.sp, color = if (selected) Color.White else Color(0xFFAAAAAA))
        }
    }
}

// ─── Block Manager Dialog ──────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BlockManagerDialog(
    blockDefs: List<BlockDef>,
    onInsert: (BlockDef) -> Unit,
    onDelete: (List<String>) -> Unit,
    onEdit: (BlockDef) -> Unit,
    onNewBlock: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showConfirmDelete by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xCC222222),
            modifier = Modifier.width(320.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "图块",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(Modifier.weight(1f))
                    if (selectedIds.isNotEmpty()) {
                        val canEdit = selectedIds.size == 1
                        TextButton(
                            onClick = { showConfirmDelete = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF5252)),
                            modifier = Modifier.height(32.dp)
                        ) { Text("删除(${selectedIds.size})", fontSize = 12.sp) }
                        if (canEdit) {
                            val selBlock = blockDefs.find { it.id == selectedIds.first() }
                            TextButton(
                                onClick = {
                                    if (selBlock != null) {
                                        onEdit(selBlock)
                                        onDismiss()
                                    }
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF64B5F6)),
                                modifier = Modifier.height(32.dp)
                            ) { Text("修改", fontSize = 12.sp) }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                if (blockDefs.isEmpty()) {
                    Text("还没有图块", fontSize = 14.sp, color = Color(0xFF888888))
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.heightIn(max = 400.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(blockDefs) { bd ->
                            val isSelected = bd.id in selectedIds
                            BlockPreview(
                                blockDef = bd,
                                isSelected = isSelected,
                                onClick = {
                                    if (selectedIds.isEmpty()) onInsert(bd)
                                    else {
                                        selectedIds = if (isSelected) selectedIds - bd.id
                                        else selectedIds + bd.id
                                    }
                                },
                                onLongClick = {
                                    selectedIds = if (isSelected) selectedIds - bd.id
                                    else selectedIds + bd.id
                                },
                                modifier = Modifier.fillMaxWidth().height(140.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onNewBlock,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1565C0),
                        contentColor = Color.White
                    )
                ) { Text("＋ 新建块", fontSize = 14.sp) }
            }
        }
    }

    if (showConfirmDelete) {
        AlertDialog(
            onDismissRequest = { showConfirmDelete = false },
            title = { Text("删除图块") },
            text = { Text("确认删除 ${selectedIds.size} 个图块？", fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(selectedIds.toList())
                    selectedIds = emptySet()
                    showConfirmDelete = false
                }) { Text("删除", color = Color(0xFFC62828)) }
            },
            dismissButton = { TextButton(onClick = { showConfirmDelete = false }) { Text("取消") } }
        )
    }
}

// ─── Block Preview ───────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BlockPreview(
    blockDef: BlockDef,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        ),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) Color(0xFF1565C0) else Color(0xDD333333),
        border = if (isSelected) BorderStroke(2.dp, Color(0xFF64B5F6)) else null,
        shadowElevation = if (isSelected) 4.dp else 0.dp
    ) {
        Column(Modifier.padding(6.dp)) {
            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFF0F0F0)
            ) {
                Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                    if (blockDef.primitives.isNotEmpty()) {
                        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
                        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                        for (p in blockDef.primitives) {
                            val b = when (p) {
                                is DrawingPrimitive.FreehandPath -> {
                                    if (p.points.size < 2) null
                                    else {
                                        val xs = p.points.map { it.x }; val ys = p.points.map { it.y }
                                        floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
                                    }
                                }
                                is DrawingPrimitive.RectanglePrimitive -> floatArrayOf(
                                    minOf(p.startX, p.endX), minOf(p.startY, p.endY),
                                    maxOf(p.startX, p.endX), maxOf(p.startY, p.endY))
                                is DrawingPrimitive.CirclePrimitive -> {
                                    val r = maxOf(abs(p.endX - p.centerX), abs(p.endY - p.centerY))
                                    floatArrayOf(p.centerX - r, p.centerY - r, p.centerX + r, p.centerY + r)
                                }
                                is DrawingPrimitive.LinePrimitive -> {
                                    val xs = listOf(p.startX, p.endX); val ys = listOf(p.startY, p.endY)
                                    floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
                                }
                                is DrawingPrimitive.NumberLabelPrimitive -> {
                                    floatArrayOf(p.x - 30f, p.y - 15f, p.x + 30f, p.y + 15f)
                                }
                                is DrawingPrimitive.TextPrimitive -> {
                                    floatArrayOf(p.x - 40f, p.y - 20f, p.x + 40f, p.y + 20f)
                                }
                                else -> null
                            }
                            if (b != null) {
                                minX = minOf(minX, b[0]); minY = minOf(minY, b[1])
                                maxX = maxOf(maxX, b[2]); maxY = maxOf(maxY, b[3])
                            }
                        }
                        if (minX <= maxX) {
                            val bw = maxX - minX; val bh = maxY - minY
                            val pad = 4f
                            val sx = (size.width - pad * 2) / bw.coerceAtLeast(1f)
                            val sy = (size.height - pad * 2) / bh.coerceAtLeast(1f)
                            val s = minOf(sx, sy)
                            val ox = -minX * s + pad + (size.width - bw * s) / 2f
                            val oy = -minY * s + pad + (size.height - bh * s) / 2f
                            for (p in blockDef.primitives) {
                                when (p) {
                                    is DrawingPrimitive.FreehandPath -> {
                                        if (p.points.size >= 2) {
                                            val path = Path().apply {
                                                var first = true
                                                for (pt in p.points) {
                                                    if (first) { moveTo(pt.x * s + ox, pt.y * s + oy); first = false }
                                                    else lineTo(pt.x * s + ox, pt.y * s + oy)
                                                }
                                                if (p.isClosed) close()
                                            }
                                            drawPath(path, p.color, style = Stroke(1.5f))
                                        }
                                    }
                                    is DrawingPrimitive.RectanglePrimitive -> {
                                        drawRect(p.color, Offset(p.startX * s + ox, p.startY * s + oy),
                                            Size((p.endX - p.startX) * s, (p.endY - p.startY) * s),
                                            style = Stroke(1.5f))
                                    }
                                    is DrawingPrimitive.CirclePrimitive -> {
                                        val r = maxOf(abs(p.endX - p.centerX), abs(p.endY - p.centerY))
                                        drawCircle(p.color, r * s, Offset(p.centerX * s + ox, p.centerY * s + oy),
                                            style = Stroke(1.5f))
                                    }
                                    is DrawingPrimitive.LinePrimitive -> {
                                        drawLine(p.color,
                                            Offset(p.startX * s + ox, p.startY * s + oy),
                                            Offset(p.endX * s + ox, p.endY * s + oy),
                                            strokeWidth = 1.5f)
                                    }
                                    else -> {}
                                }
                            }
                        }
                    }
                }
            }
            // Name label
            Text(
                blockDef.name,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

// ─── Properties Dialog ──────────────────────────────────

private enum class PropCat(val label: String, val icon: String) {
    GRAPHICS("图形", "▦"),
    NUMBER("数字", "①"),
    TEXT("文字", "T"),
    BLOCK("图块", "▣")
}

private fun categorizePrimitive(p: DrawingPrimitive): PropCat = when (p) {
    is DrawingPrimitive.FreehandPath, is DrawingPrimitive.RectanglePrimitive,
    is DrawingPrimitive.CirclePrimitive, is DrawingPrimitive.LinePrimitive -> PropCat.GRAPHICS
    is DrawingPrimitive.NumberLabelPrimitive, is DrawingPrimitive.RangeLabelPrimitive -> PropCat.NUMBER
    is DrawingPrimitive.TextPrimitive -> PropCat.TEXT
    is DrawingPrimitive.BlockRefPrimitive -> PropCat.BLOCK
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PropertiesDialog(vm: DrawingViewModel) {
    val indices = vm.selection.selectedIndices.toList()
    val primitives = indices.mapNotNull { i -> vm.primitives.getOrNull(i) }
    val typeCounts = primitives.groupBy { categorizePrimitive(it) }.mapValues { it.value.size }
    val hasMultipleTypes = typeCounts.size > 1
    var selectedCategory by remember { mutableStateOf<PropCat?>(if (hasMultipleTypes) null else typeCounts.keys.firstOrNull()) }
    val catPrimitives = if (selectedCategory != null) primitives.filter { categorizePrimitive(it) == selectedCategory } else emptyList()
    val firstP = catPrimitives.firstOrNull()

    AlertDialog(
        onDismissRequest = { vm.dismissPropertiesDialog() },
        title = {
            val cat = selectedCategory
            Text(
                if (cat != null) "${cat.label}属性 (${catPrimitives.size}个)"
                else "对象属性 (${indices.size}个)"
            )
        },
        text = {
            if (selectedCategory == null) {
                // 分类菜单
                Column(Modifier.widthIn(max = 280.dp)) {
                    typeCounts.forEach { (cat, count) ->
                        TextButton(
                            onClick = { selectedCategory = cat },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("${cat.icon}  ${cat.label} (${count}个)", fontSize = 15.sp)
                        }
                    }
                }
            } else {
                Column(Modifier.widthIn(max = 300.dp)) {
                    // 多类型时显示返回按钮
                    if (hasMultipleTypes) {
                        TextButton(onClick = { selectedCategory = null }, modifier = Modifier.align(Alignment.Start)) {
                            Icon(Icons.Outlined.ArrowBack, "返回", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("返回分类", fontSize = 13.sp)
                        }
                    }
                    if (firstP != null) {
                        val cat = selectedCategory
                        when (cat) {
                            PropCat.GRAPHICS -> GraphicsPropsPanel(vm, firstP, catPrimitives.size > 1)
                            PropCat.NUMBER -> TextNumPropsPanel(vm, firstP, PropCat.NUMBER, catPrimitives.size > 1)
                            PropCat.TEXT -> TextNumPropsPanel(vm, firstP, PropCat.TEXT, catPrimitives.size > 1)
                            PropCat.BLOCK -> BlockPropsPanel(vm, firstP, catPrimitives.size > 1)
                            null -> {}
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { vm.dismissPropertiesDialog() }) { Text("关闭") }
        }
    )
}

// ── Graphics Properties Panel ──
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColumnScope.GraphicsPropsPanel(vm: DrawingViewModel, p: DrawingPrimitive, multi: Boolean) {
    var lineType by remember(p) { mutableStateOf(p.lineStyle.type) }
    var color by remember(p) { mutableStateOf(p.color) }
    var lineScale by remember(p) { mutableFloatStateOf(p.lineScaleFactor) }
    var showColorPicker by remember { mutableStateOf(false) }

    if (multi) Text("修改将影响所有选中图形", fontSize = 12.sp, color = Color(0xFF888888))
    Spacer(Modifier.height(8.dp))

    // 线型
    Text("线型", fontSize = 13.sp, fontWeight = FontWeight(500)); Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        LineType.entries.forEach { lt ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp)
                .combinedClickable(
                    onClick = {
                        lineType = lt
                        vm.updateSelectedLineStyle(LineStyle(type = lt))
                    }
                )) {
                RadioButton(selected = lineType == lt, onClick = null)
                Spacer(Modifier.width(4.dp)); Text(lt.displayName, fontSize = 13.sp)
            }
        }
    }
    Spacer(Modifier.height(12.dp))

    // 颜色
    Text("颜色", fontSize = 13.sp, fontWeight = FontWeight(500)); Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(28.dp).combinedClickable(onClick = { showColorPicker = !showColorPicker }),
            shape = RoundedCornerShape(6.dp), color = color, border = BorderStroke(1.dp, Color.Gray)) {}
        Spacer(Modifier.width(8.dp))
        Text("#${color.toArgbColorHex().takeLast(6)}", fontSize = 12.sp, color = Color(0xFF555555))
    }
    if (showColorPicker) {
        Spacer(Modifier.height(4.dp))
        ColorPickerBar { c ->
            color = c; vm.updateSelectedColor(c); showColorPicker = false
        }
    }
    Spacer(Modifier.height(12.dp))

    // 线宽倍率
    Text("线宽倍率：${String.format(java.util.Locale.US, "%.2f", lineScale)}×", fontSize = 13.sp, fontWeight = FontWeight(500))
    Slider(
        value = lineScale, onValueChange = { lineScale = it; vm.updateSelectedLineScaleFactor(it) },
        valueRange = 0.25f..4f, modifier = Modifier.fillMaxWidth()
    )
}

// ── Text / Number Properties Panel ──
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColumnScope.TextNumPropsPanel(vm: DrawingViewModel, p: DrawingPrimitive, cat: PropCat, multi: Boolean) {
    var color by remember(p) { mutableStateOf(p.color) }
    var fontSize by remember(p) { mutableFloatStateOf((p as? DrawingPrimitive.TextPrimitive)?.fontSize ?: (p as? DrawingPrimitive.NumberLabelPrimitive)?.fontSize ?: 30f) }
    var textContent by remember(p) { mutableStateOf((p as? DrawingPrimitive.TextPrimitive)?.text ?: "") }
    var numValueStr by remember(p) { mutableStateOf(((p as? DrawingPrimitive.NumberLabelPrimitive)?.value ?: 0).toString()) }
    var showColorPicker by remember { mutableStateOf(false) }
    val isText = cat == PropCat.TEXT

    if (multi) Text("修改将影响所有选中${cat.label}", fontSize = 12.sp, color = Color(0xFF888888))
    Spacer(Modifier.height(8.dp))

    // 颜色
    Text("颜色", fontSize = 13.sp, fontWeight = FontWeight(500)); Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(28.dp).combinedClickable(onClick = { showColorPicker = !showColorPicker }),
            shape = RoundedCornerShape(6.dp), color = color, border = BorderStroke(1.dp, Color.Gray)) {}
        Spacer(Modifier.width(8.dp))
        Text("#${color.toArgbColorHex().takeLast(6)}", fontSize = 12.sp, color = Color(0xFF555555))
    }
    if (showColorPicker) {
        Spacer(Modifier.height(4.dp))
        ColorPickerBar { c ->
            color = c; vm.updateSelectedColor(c); showColorPicker = false
        }
    }
    Spacer(Modifier.height(12.dp))

    // 字号
    Text("字号：${fontSize.toInt()}", fontSize = 13.sp, fontWeight = FontWeight(500))
    Slider(
        value = fontSize, onValueChange = { fontSize = it; vm.updateSelectedFontSize(it) },
        valueRange = 30f..400f, modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(12.dp))

    // 内容
    if (isText) {
        Text("文本内容", fontSize = 13.sp, fontWeight = FontWeight(500)); Spacer(Modifier.height(4.dp))
        var textContentTv by remember(p) { mutableStateOf(TextFieldValue(textContent)) }
        OutlinedTextField(
            value = textContentTv, onValueChange = { textContentTv = it; textContent = it.text; vm.updateSelectedTextContent(it.text) },
            singleLine = false, maxLines = 3, modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) textContentTv = textContentTv.copy(selection = TextRange(0, textContentTv.text.length)) }
        )
    } else {
        Text("数字值", fontSize = 13.sp, fontWeight = FontWeight(500)); Spacer(Modifier.height(4.dp))
        var numValueTv by remember(p) { mutableStateOf(TextFieldValue(numValueStr)) }
        OutlinedTextField(
            value = numValueTv,
            onValueChange = { v ->
                numValueTv = v
                numValueStr = v.text.filter { it.isDigit() || it == '-' }
                numValueStr.toIntOrNull()?.let { vm.updateSelectedNumberValue(it) }
            },
            singleLine = true, modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) numValueTv = numValueTv.copy(selection = TextRange(0, numValueTv.text.length)) }
        )
    }
}

// ── Block Properties Panel ──
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColumnScope.BlockPropsPanel(vm: DrawingViewModel, p: DrawingPrimitive, multi: Boolean) {
    val bp = p as DrawingPrimitive.BlockRefPrimitive
    var color by remember(p) { mutableStateOf(p.color) }
    var scale by remember(p) { mutableFloatStateOf(bp.scale) }
    var showColorPicker by remember { mutableStateOf(false) }

    if (multi) Text("修改将影响所有选中图块", fontSize = 12.sp, color = Color(0xFF888888))
    Spacer(Modifier.height(8.dp))

    // 颜色
    Text("颜色", fontSize = 13.sp, fontWeight = FontWeight(500)); Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(28.dp).combinedClickable(onClick = { showColorPicker = !showColorPicker }),
            shape = RoundedCornerShape(6.dp), color = color, border = BorderStroke(1.dp, Color.Gray)) {}
        Spacer(Modifier.width(8.dp))
        Text("#${color.toArgbColorHex().takeLast(6)}", fontSize = 12.sp, color = Color(0xFF555555))
    }
    if (showColorPicker) {
        Spacer(Modifier.height(4.dp))
        ColorPickerBar { c ->
            color = c; vm.updateSelectedColor(c); showColorPicker = false
        }
    }
    Spacer(Modifier.height(12.dp))

    // 缩放
    Text("缩放：${String.format(java.util.Locale.US, "%.2f", scale)}×", fontSize = 13.sp, fontWeight = FontWeight(500))
    Slider(
        value = scale, onValueChange = { scale = it; vm.updateSelectedBlockScale(it) },
        valueRange = 0.1f..10f, modifier = Modifier.fillMaxWidth()
    )
}

// ─── Color Picker Bar ──────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColorPickerBar(onPick: (Color) -> Unit) {
    val colors = listOf(
        Color.Black, Color(0xFFC62828), Color(0xFFD84315), Color(0xFFEF6C00),
        Color(0xFFF9A825), Color(0xFF2E7D32), Color(0xFF0277BD), Color(0xFF1565C0),
        Color(0xFF6A1B9A), Color(0xFF00838F), Color(0xFF4E342E), Color(0xFF616161),
        Color(0xFFB71C1C), Color(0xFF880E4F), Color(0xFF1B5E20), Color(0xFF0D47A1)
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        colors.forEach { c ->
            Surface(Modifier.size(24.dp).combinedClickable(onClick = { onPick(c) }),
                shape = RoundedCornerShape(4.dp), color = c,
                border = BorderStroke(1.dp, Color.Gray)) {}
        }
    }
}

private fun Color.toArgbColorHex(): String {
    val r = (red * 255).toInt().coerceIn(0, 255)
    val g = (green * 255).toInt().coerceIn(0, 255)
    val b = (blue * 255).toInt().coerceIn(0, 255)
    val a = (alpha * 255).toInt().coerceIn(0, 255)
    return "#%02X%02X%02X%02X".format(a, r, g, b)
}

// ─── Block Editor Screen ───────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlockEditorScreen(
    vm: DrawingViewModel,
    sm: StorageManager,
    ctx: android.content.Context,
    onSave: (String) -> Unit,
    onBack: () -> Unit
) {
    var blkName by remember { mutableStateOf("") }
    var saveDlg by remember { mutableStateOf(false) }
    var canvasSz by remember { mutableStateOf(IntSize.Zero) }
    var exitConfirmDlg by remember { mutableStateOf(false) }

    val hasChanges = vm.blockEditorPrimitives.isNotEmpty()

    BackHandler {
        if (hasChanges) exitConfirmDlg = true
        else { vm.blockEditorCancelPrimitive(); vm.cancelBlockDraft(); onBack() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("编辑图块", fontWeight = FontWeight(500)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (hasChanges) exitConfirmDlg = true
                        else { vm.blockEditorCancelPrimitive(); vm.cancelBlockDraft(); onBack() }
                    }) { Icon(Icons.Outlined.ArrowBack, "返回") }
                },
                actions = {
                    IconButton(
                        onClick = { vm.blockEditorUndo() },
                        enabled = vm.canBlockEditorUndo
                    ) { Icon(Icons.Outlined.Undo, "撤销") }
                    IconButton(
                        onClick = { vm.blockEditorRedo() },
                        enabled = vm.canBlockEditorRedo
                    ) { Icon(Icons.Outlined.Redo, "重做") }
                    IconButton(onClick = {
                        val editId = vm.editingBlockId
                        if (editId != null) {
                            // 编辑已有块：直接保存，不弹起名框
                            val bd = vm.blockDefs.find { it.id == editId }
                            val name = bd?.name ?: "图块"
                            vm.saveBlockEditorBlock(name)
                            onSave(name)
                        } else {
                            saveDlg = true
                        }
                    }) {
                        Icon(Icons.Outlined.Save, "保存")
                    }
                    TextButton(
                        onClick = {
                            if (hasChanges) exitConfirmDlg = true
                            else { vm.blockEditorCancelPrimitive(); vm.cancelBlockDraft(); onBack() }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFC62828))
                    ) { Text("放弃", fontSize = 13.sp) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.White,
                    titleContentColor = Color(0xFF333333)
                )
            )
        },
        bottomBar = {
            BottomToolbar(
                viewModel = vm,
                onShowNumberDialog = {},
                onShowTextDialog = {},
                onShowRangeDialog = {}
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).onSizeChanged { canvasSz = it }) {
            DrawingCanvas(
                primitives = vm.blockEditorPrimitives,
                currentPrimitive = vm.blockEditorCurrent,
                layers = listOf(Layer(id = 1, name = "默认")),
                canvasScale = vm.blockEditorViewScale,
                canvasOffsetX = vm.blockEditorViewX,
                canvasOffsetY = vm.blockEditorViewY,
                pendingEdit = vm.blockEditorPendingEdit,
                currentTool = vm.currentTool,
                currentLineStyle = vm.currentLineStyle,
                selectedIndices = if (vm.blockEditorSelectedIndex >= 0) setOf(vm.blockEditorSelectedIndex) else emptySet(),
                globalLineScale = vm.globalLineScale,
                blockDefs = vm.blockDefs,
                eraserRadius = vm.eraserRadius,
                eraserTouchPoint = vm.eraserTouchPoint,
                quickEraseEnabled = vm.quickEraseEnabled,
                onLongPressEraser = { vm.enterTemporaryEraser() },
                onTouchStart = { vm.blockEditorStartPrimitive(it) },
                onTouchMove = { vm.blockEditorUpdatePrimitive(it) },
                onTouchEnd = { vm.blockEditorCommitPrimitive() },
                onTouchCancel = { vm.blockEditorCancelPrimitive() },
                onCanvasTransform = { z, c, p -> },
                modifier = Modifier.fillMaxSize()
            )
            PostCreationOverlay(
                pendingEdit = vm.blockEditorPendingEdit,
                onConfirm = { vm.blockEditorConfirmPendingEdit() },
                onCancel = { vm.blockEditorCancelPrimitive() },
                onUpdateOffset = { dx, dy -> vm.blockEditorUpdatePendingOffset(dx, dy) },
                onUpdateRotation = { r -> vm.blockEditorUpdatePendingRotation(r) },
                onUpdateScale = { sx, sy -> vm.blockEditorUpdatePendingScale(sx, sy) },
                onUpdateArrowSpan = { vm.updatePendingArrowSpan(it) },
                onUpdateRangeValue = { isStart, value -> vm.updatePendingRangeValue(isStart, value) },
                onToggleRangeReversed = { vm.toggleRangeReversed() },
                canvasScale = vm.blockEditorViewScale,
                canvasOffsetX = vm.blockEditorViewX,
                canvasOffsetY = vm.blockEditorViewY,
                globalLineScale = vm.globalLineScale,
                blockDefs = vm.blockDefs,
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    if (saveDlg) {
        val focusReq = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(100); focusReq.requestFocus() }
        var blkNameTv by remember { mutableStateOf(TextFieldValue(blkName)) }
        AlertDialog(onDismissRequest = { saveDlg = false }, title = { Text("保存图块") },
            text = { OutlinedTextField(blkNameTv, { blkNameTv = it; blkName = it.text }, label = { Text("名称") }, singleLine = true,
                modifier = Modifier.focusRequester(focusReq).onFocusChanged { if (it.isFocused) blkNameTv = blkNameTv.copy(selection = TextRange(0, blkNameTv.text.length)) }) },
            confirmButton = {
                TextButton(onClick = {
                    val n = blkName.trim()
                    if (n.isNotBlank()) {
                        vm.saveBlockEditorBlock(n)
                        saveDlg = false
                        onSave(n)
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { saveDlg = false }) { Text("取消") } })
    }

    if (exitConfirmDlg) {
        AlertDialog(onDismissRequest = { exitConfirmDlg = false },
            title = { Text("放弃修改？") },
            text = { Text("当前内容未保存，确定放弃？") },
            confirmButton = {
                TextButton(onClick = {
                    vm.blockEditorCancelPrimitive()
                    vm.cancelBlockDraft()
                    exitConfirmDlg = false
                    onBack()
                }, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFC62828))
                ) { Text("放弃") }
            },
            dismissButton = { TextButton(onClick = { exitConfirmDlg = false }) { Text("取消") } })
    }
}
