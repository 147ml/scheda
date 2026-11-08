package com.scheda.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.input.pointer.pointerInput
import com.scheda.app.ui.components.glassHighlight
import com.scheda.app.ui.components.GlassOverlayEffect
import com.scheda.app.ui.components.glassSurface
import com.scheda.app.ui.components.glassAdapt
import com.scheda.app.ui.components.glassToolbarInset
import com.scheda.app.ui.components.glassBackdropBlur
import com.scheda.app.file.PendingWrites
import com.scheda.app.model.SelectionAction
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scheda.app.file.RecycleBinManager
import com.scheda.app.file.RecoveryManager
import com.scheda.app.file.SchedaSerializer
import com.scheda.app.file.ShareUtil
import com.scheda.app.file.StorageManager
import com.scheda.app.model.*
import com.scheda.app.export.DxfReader
import com.scheda.app.ui.canvas.DrawingCanvas
import com.scheda.app.ui.canvas.smoothPathFromPoints
import com.scheda.app.ui.canvas.GestureHost
import com.scheda.app.ui.canvas.PostCreationOverlay
import com.scheda.app.ui.canvas.PaddedFrameOverlay
import com.scheda.app.ui.canvas.ArrayOverlay
import com.scheda.app.ui.canvas.NumArrayOverlay
import com.scheda.app.ui.canvas.NudgePad
import com.scheda.app.ui.components.OrthoIcon
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.scheda.app.ui.components.BottomToolbar
import com.scheda.app.ui.components.ColorPickerDialog
import com.scheda.app.ui.components.CompactSlider
import com.scheda.app.ui.components.DrawingViewModelFactory
import com.scheda.app.ui.components.HomeViewModelFactory
import com.scheda.app.ui.components.LayerDialog
import com.scheda.app.ui.components.ArrayDialog
import androidx.compose.ui.graphics.Brush
import com.scheda.app.diagnostics.CrashRecorder
import com.scheda.app.ui.components.GlassMenuLayer
import com.scheda.app.ui.components.SchedaDropdownMenu
import com.scheda.app.ui.home.HomeScreen
import com.scheda.app.viewmodel.DrawingViewModel
import com.scheda.app.viewmodel.HomeViewModel
import java.io.File
import kotlin.math.abs
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen() {
    // 退出诊断：启动时读取系统记录的上次退出原因并弹窗
    ExitDiagnostics()
    val ctx = LocalContext.current
    val sm = remember { StorageManager(ctx) }
    val ser = remember { SchedaSerializer(ctx) }
    val rec = remember { RecoveryManager(ser) }
    val sh = remember { ShareUtil(ctx, ser) }
    val rbm = remember { RecycleBinManager(sm) }
    val dvm: DrawingViewModel = viewModel(factory = remember { DrawingViewModelFactory(sm, ser, rec) })
    val hvm: HomeViewModel = viewModel(factory = remember { HomeViewModelFactory(sm, sh, ser, rbm) })

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
            GlassOverlayEffect()
            Surface(shape = RoundedCornerShape(16.dp), color = Color(0xE0FFFFFF), modifier = Modifier.width(320.dp)) {
                Column(Modifier.padding(24.dp)) {
                    Icon(Icons.Outlined.Folder, null, Modifier.size(40.dp).align(Alignment.CenterHorizontally), tint = Color(0xFF1565C0))
                    Spacer(Modifier.height(16.dp))
                    Text("需要文件访问权限", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2A2A2A),
                        modifier = Modifier.align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(12.dp))
                    Text("Scheda 需要「所有文件访问」权限\n来读写图纸。", fontSize = 14.sp, color = Color(0xFF666666))
                    Spacer(Modifier.height(8.dp))
                    Text("请前往系统设置开启。", fontSize = 14.sp, color = Color(0xFF777777))
                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { needPerm = false; setup = false },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF666666))) { Text("稍后") }
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
    // DXF 导入（导航级状态）
    var dxfImportPrims by remember { mutableStateOf<List<DrawingPrimitive>?>(null) }
    var dxfImportMode by remember { mutableStateOf(DxfImportMode.MAIN) }

    Box(Modifier.fillMaxSize()) {
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
                    CrashRecorder.note("打开图纸 $name")
                    curFile = f
                    curName = name
                    screen = "drawing"
                    scope.launch {
                        val doc = withContext(Dispatchers.IO) { PendingWrites.await(); sm.loadDocument(f) }
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
            GlassOverlayEffect()
                    Surface(shape = RoundedCornerShape(16.dp), color = Color(0xE0FFFFFF), modifier = Modifier.width(300.dp)) {
                        Column(Modifier.padding(20.dp)) {
                            Text("新建", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2A2A2A))
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(nfTv, { nfTv = it; nf = it.text }, label = { Text("名称") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth().focusRequester(focusReq).onFocusChanged { if (it.isFocused) nfTv = nfTv.copy(selection = TextRange(0, nfTv.text.length)) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF64B5F6), unfocusedBorderColor = Color(0xFF666666),
                                    cursorColor = Color(0xFF2A2A2A), focusedTextColor = Color(0xFF2A2A2A), unfocusedTextColor = Color(0xFF2A2A2A),
                                    focusedLabelColor = Color(0xFF64B5F6), unfocusedLabelColor = Color(0xFFAAAAAA)))
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(newType == "drawing", { newType = "drawing" },
                                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF64B5F6), unselectedColor = Color(0xFF666666)))
                                Text(" 画图", fontSize = 12.sp, color = Color(0xFF666666), modifier = Modifier.padding(end = 16.dp))
                                RadioButton(newType == "folder", { newType = "folder" },
                                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF64B5F6), unselectedColor = Color(0xFF666666)))
                                Text(" 文件夹", fontSize = 12.sp, color = Color(0xFF666666))
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { showNewDlg = false; nf = "" },
                                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF666666))) { Text("取消") }
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = {
                                    if (nf.isNotBlank()) {
                                        when (newType) {
                                            "drawing" -> {
                                                val parent = hvm.curDir ?: sm.getRoot()
                                                if (parent == null) { Toast.makeText(ctx, "无法确定保存位置", Toast.LENGTH_SHORT).show(); return@TextButton }
                                                val f = sm.createDocumentIn(nf.trim(), parent)
                                                if (f != null) {
                                                    curFile = f; curName = nf.trim()
                                                    screen = "drawing"
                                                    scope.launch {
                                                        PendingWrites.await()
                                                        val doc = SchedaDocument(
                                                            name = nf.trim(), primitives = emptyList(),
                                                            layers = listOf(SerializableLayer(id = 0, name = "图层0")),
                                                            activeLayerId = 0, blockDefs = emptyList(),
                                                            canvasScale = 1f, canvasOffsetX = 0f, canvasOffsetY = 0f
                                                        )
                                                        sm.saveToFile(f, doc)
                                                        dvm.loadExistingDocument(doc, f)
                                                    }
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
                // 同步落盘（内部为流式写入，不在主线程堆整串 JSON）：退出时必须拿到已提交的文件
                dvm.saveNow()
                hvm.refresh()
                screen = "home"
            },
            onBlockEditor = {
                screen = "blockEditor"
            },
            onDxfImport = { prims, mode ->
                dxfImportPrims = prims
                dxfImportMode = mode
                screen = "dxfImport"
            }
        )
        "dxfImport" -> {
            val prims = dxfImportPrims
            if (prims == null) {
                screen = "drawing"
            } else {
                DxfImportScreen(
                    primitives = prims,
                    mode = dxfImportMode,
                    onConfirm = { selected ->
                        if (dxfImportMode == DxfImportMode.MAIN) {
                            dvm.setPendingImport(selected)
                            screen = "drawing"
                        } else {
                            // 导入到块：直接放入块编辑画布
                            dvm.enterBlockEditorWithImport(selected)
                            screen = "blockEditor"
                        }
                    },
                    onCancel = { screen = "drawing" }
                )
            }
        }
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
    Surface(Modifier.fillMaxSize(), color = Color(0xFFF5F6F8)) {
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
            Text("Scheda", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2A2A2A), letterSpacing = 2.sp)
            Spacer(Modifier.height(8.dp))
            Text("随手画", fontSize = 16.sp, color = Color(0xFF999999))
            Spacer(Modifier.height(4.dp))
            Text("简单、流畅的工程画图工具", fontSize = 13.sp, color = Color(0xFF888888))
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
            Text("需要「所有文件访问」权限来读写图纸", fontSize = 12.sp, color = Color(0xFF888888))
        }
    }
}

// ─── Drawing Screen ────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun BoxScope.GlassTopBar(
    title: String,
    vm: DrawingViewModel,
    onBack: () -> Unit,
    onImportDxf: () -> Unit,
    onOpenLayers: () -> Unit,
    onOpenBlocks: () -> Unit,
    onShare: () -> Unit,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop?,
) {
    val glass = backdrop != null
    val fg: (Color) -> Color = { c -> glassAdapt(c, glass) }
    Row(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .glassSurface(
                backdrop = backdrop,
                shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
                tint = Color(0x3D8CBEFA),
                fallback = Color(0xFF2A2A2A)
            )
            .statusBarsPadding()
            .height(50.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Outlined.ArrowBack, "返回", Modifier.size(20.dp), tint = fg(Color.White))
        }
        Text(title, fontSize = 16.sp, fontWeight = FontWeight(500), color = fg(Color.White), maxLines = 1)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { vm.undo() }, enabled = vm.canUndo, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Outlined.Undo, "撤销", Modifier.size(20.dp),
                tint = fg(if (vm.canUndo) Color.White else Color(0xFF9E9E9E)))
        }
        IconButton(onClick = { vm.redo() }, enabled = vm.canRedo, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Outlined.Redo, "重做", Modifier.size(20.dp),
                tint = fg(if (vm.canRedo) Color.White else Color(0xFF9E9E9E)))
        }
        IconButton(onClick = { vm.toggleOrthoMode() }, modifier = Modifier.size(32.dp)) {
            Icon(OrthoIcon, "正交", Modifier.size(20.dp),
                tint = fg(if (vm.orthoMode) Color(0xFF4B9CD3) else Color.White))
        }
        IconButton(onClick = { vm.toggleOrientation() }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Outlined.ScreenRotation, "横竖屏切换", Modifier.size(16.dp),
                tint = fg(if (vm.isLandscape) Color(0xFF4B9CD3) else Color.White))
        }
        Box {
            var moreExpanded by remember { mutableStateOf(false) }
            IconButton(onClick = { moreExpanded = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.MoreVert, "更多", Modifier.size(20.dp), tint = fg(Color.White))
            }
            SchedaDropdownMenu(
                expanded = moreExpanded,
                onDismissRequest = { moreExpanded = false },
                minWidth = 140.dp,
                containerColor = Color(0xF7FFFFFF),
            ) {
                listOf(
                    "导入 DXF" to onImportDxf,
                    "图层" to onOpenLayers,
                    "图块" to onOpenBlocks,
                    "图片" to { vm.enterImageManage() },
                    "分享" to onShare,
                ).forEach { (label, action) ->
                    Text(
                        label, fontSize = 14.sp, color = Color(0xFF2A2A2A),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { moreExpanded = false; action() }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawingScreen(
    nm: String,
    vm: DrawingViewModel,
    sh: ShareUtil,
    onBack: () -> Unit,
    onBlockEditor: () -> Unit,
    onDxfImport: (List<DrawingPrimitive>, DxfImportMode) -> Unit = { _, _ -> }
) {
    // 画布层捕获（液态玻璃 NudgePad 的采样源）
    // 液态玻璃总开关（应急降级用）：false 时全部玻璃回退纯色
    val glassEnabled = true
    val canvasBackdrop = if (glassEnabled) rememberLayerBackdrop() else null
    val ctx = LocalContext.current
    var canvasSz by remember { mutableStateOf(IntSize.Zero) }
    var numDlg by remember { mutableStateOf(false) }
    var textDlg by remember { mutableStateOf(false) }
    // 选中文字的编辑弹窗（与新建文字的 textDlg 分开：一个预填现有内容，一个用于放置新文字）
    var editTextDlg by remember { mutableStateOf(false) }
    // 图片管理页（按图层分组的网格）
    var showImageManager by remember { mutableStateOf(false) }
    var rangeDlg by remember { mutableStateOf(false) }
    var numArrayDlg by remember { mutableStateOf(false) }
    var layerDlg by remember { mutableStateOf(false) }
    var insertBlockDlg by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var dxfPickerTarget by remember { mutableStateOf<DxfImportMode?>(null) }
    // 阵列手柄按下时置位，DrawingCanvas 同步跳过单指"移动原始元素"逻辑
    var arrayHandleActive by remember { mutableStateOf(false) }
    // 数字阵列间距手柄按下时置位（互锁同阵列手柄）
    var numArrayHandleActive by remember { mutableStateOf(false) }
    // 图片 PFO 手柄按下时置位（互锁同阵列手柄）
    var imgHandleActive by remember { mutableStateOf(false) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/dxf")
    ) { uri -> uri?.let {
        val ok = vm.exportDxf(ctx, it)
        val msg = if (!ok) "DXF 导出失败"
            else if (vm.images.isNotEmpty()) "含参考图片，已打包 ZIP（解压后 DXF 与图片放同一目录）"
            else "已导出 DXF"
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    } }

    // 系统图库选择器（Photo Picker 相册网格界面，非文件管理器；无需权限）
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { vm.importReferenceImage(it) } }

    BackHandler {
        // 图片管理模式中：返回键先退出管理模式（保存调整），不退出图纸
        if (vm.imageManageActive) { vm.exitImageManage(); return@BackHandler }
        if (vm.pendingEdit.active) vm.confirmPendingEdit()
        if (vm.numArrayActive) vm.confirmNumArray()
        onBack()
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().glassBackdropBlur().onSizeChanged { canvasSz = it }) {
            GestureHost(
                primitives = vm.primitives, currentPrimitive = vm.currentPrimitive,
                layers = vm.layers, canvasScale = vm.canvasScale,
                canvasOffsetX = vm.canvasOffsetX, canvasOffsetY = vm.canvasOffsetY,
                pendingEdit = vm.pendingEdit, currentTool = vm.currentTool,
                currentLineStyle = vm.currentLineStyle,
                selectedIndices = vm.selection.selectedIndices,
                isTransforming = vm.selection.isTransforming,
                globalLineScale = vm.globalLineScale,
                blockDefs = vm.blockDefs,
                images = vm.images,
                imageBitmaps = vm.imageBitmaps,
                imageManageActive = vm.imageManageActive,
                selectedImageId = vm.imgSelectedId,
                eraserRadius = vm.eraserRadius,
                eraserTouchPoint = vm.eraserTouchPoint,
                quickEraseEnabled = vm.quickEraseEnabled,
                onLongPressEraser = { vm.enterTemporaryEraser() },
                onTouchStart = { vm.startPrimitive(it) },
                onTouchMove = { vm.updatePrimitive(it) },
                onTouchEnd = { vm.commitPrimitive() },
                onTouchCancel = { vm.cancelPrimitive() },
                onCanvasTransform = { z, c, p -> vm.transformCanvas(z, c, p) },
                onConfirm = { vm.confirmPendingEdit() },
                onCancel = { vm.cancelPendingEdit() },
                onUpdateOffset = { dx, dy -> vm.updatePendingOffset(dx, dy) },
                onUpdateRotation = { r -> vm.updatePendingRotation(r) },
                onUpdateScale = { sx, sy -> vm.updatePendingScale(sx, sy) },
                onUpdatePrimitive = { p -> vm.updatePendingPrimitive(p) },
                onUpdateFontScale = { delta -> vm.updatePendingFontScale(delta) },
                onUpdateArrowSpan = { factor -> vm.updatePendingArrowSpan(factor) },
                onToggleTextOrientation = { vm.toggleHorizontalText() },
                onToggleRangeReversed = { vm.toggleRangeReversed() },
                currentFontSize = vm.getPendingEffectiveFontSize(),
                selection = if (vm.arrayActive || vm.imageManageActive) null else vm.selection,
                onMoveSelected = { dx, dy -> vm.moveSelectedPrimitives(dx, dy) },
                onRotateSelected = { r -> vm.rotateSelectedPrimitives(r) },
                onScaleSelected = { sx, sy -> vm.scaleSelectedPrimitives(sx, sy) },
                onRectMidpointDrag = { idx, a -> vm.midpointDragSelected(idx, a) },
                rectDragHandleWorld = vm.rectDragHandleWorld?.let { Offset(it.x, it.y) },
                onMoveSelectedLineEndpoint = { isStart, x, y -> vm.moveSelectedLineEndpoint(isStart, x, y) },
                onSelectionDragEnd = { vm.finishSelectionDrag() },
                onTransformEnd = { vm.finalizeSelectionTransform() },
                orthoStep = vm.orthoMode,
                onStepRotatePending = { d -> vm.stepRotatePending(d) },
                onStepRotateSelected = { d -> vm.stepRotateSelected(d); vm.finalizeSelectionTransform() },
                arrayHandleActive = { arrayHandleActive || numArrayHandleActive || imgHandleActive },
                canvasBackdrop = canvasBackdrop,
                modifier = Modifier.fillMaxSize()
            )
            // 选中元素的精准微调（游戏手柄十字布局）：单击一步、长按加速（上限 16×）
            if (vm.selection.selectedIndices.isNotEmpty() && !vm.pendingEdit.active &&
                !vm.arrayActive && !vm.imageManageActive) {
                NudgePad(
                    onNudge = { dx, dy -> vm.nudgeSelection(dx, dy) },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = glassToolbarInset() + 52.dp),
                    backdrop = canvasBackdrop
                )
            }
            // Array preview overlay
            if (vm.arrayActive && vm.selection.bounds != null) {
                ArrayOverlay(
                    rows = vm.arrayRows.intValue, cols = vm.arrayCols.intValue,
                    gapX = vm.arrayGapX.floatValue, gapY = vm.arrayGapY.floatValue,
                    bounds = vm.selection.bounds!!,
                    canvasScale = vm.canvasScale,
                    canvasOffsetX = vm.canvasOffsetX, canvasOffsetY = vm.canvasOffsetY,
                    selOffsetX = vm.selection.selOffsetX, selOffsetY = vm.selection.selOffsetY,
                    dirX = vm.arrayDirX.intValue, dirY = vm.arrayDirY.intValue,
                    onGapXChange = { vm.arrayGapX.floatValue = it },
                    onGapYChange = { vm.arrayGapY.floatValue = it },
                    onColsChange = { vm.arrayCols.intValue = it },
                    onRowsChange = { vm.arrayRows.intValue = it },
                    onDirXChange = { vm.arrayDirX.intValue = it },
                    onDirYChange = { vm.arrayDirY.intValue = it },
                    onHandleActiveChanged = { arrayHandleActive = it },
                    globalLineScale = vm.globalLineScale,
                    primitives = vm.selection.selectedIndices.mapNotNull { vm.primitives.getOrNull(it) },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 数字阵列间距/方向手柄覆盖层
            if (vm.numArrayActive) {
                val na = vm.numArrayLabel
                NumArrayOverlay(
                    anchorX = vm.numArrayBaseX, anchorY = vm.numArrayBaseY,
                    count = kotlin.math.abs(na.endValue - na.startValue) + 1,
                    gap = na.gap,
                    rotationDeg = na.rotationDeg,
                    canvasScale = vm.canvasScale,
                    canvasOffsetX = vm.canvasOffsetX, canvasOffsetY = vm.canvasOffsetY,
                    onGapChange = { vm.setNumArrayGap(it) },
                    onRotationChange = { vm.setNumArrayRotation(it) },
                    onAnchorDragBegin = { vm.beginNumArrayDrag() },
                    onAnchorDrag = { dx, dy -> vm.moveNumArray(dx, dy) },
                    onAnchorDragEnd = { vm.endNumArrayDrag() },
                    onHandleActiveChanged = { numArrayHandleActive = it },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 数字阵列编辑确认/取消（与区间数字待确认样式一致：底部居中圆形按钮）
            if (vm.numArrayActive) {
                Row(
                    Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = glassToolbarInset() + 64.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val btnSize = 36.dp; val capsuleShape = RoundedCornerShape(btnSize / 2)
                    FloatingActionButton(onClick = { vm.cancelNumArray() }, modifier = Modifier.size(btnSize),
                        containerColor = Color(0xFF757575), contentColor = Color.White, shape = capsuleShape) {
                        Icon(Icons.Default.Close, "取消", modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    FloatingActionButton(onClick = { vm.confirmNumArray() }, modifier = Modifier.size(btnSize),
                        containerColor = Color(0xFF4CAF50), contentColor = Color.White, shape = capsuleShape) {
                        Icon(Icons.Default.Check, "确认", modifier = Modifier.size(18.dp))
                    }
                }
            }

            // 图片管理模式：选中图 PFO 变换框 + 悬浮管理面板（画布与已有图形始终可见，图片不可点选）
            if (vm.imageManageActive) {
                val selImg = vm.selectedImage
                if (selImg != null) {
                    // PFO 框：框内拖动移动、绿角等比缩放、橙角旋转（hideMidpoints 防拉伸）
                    PaddedFrameOverlay(
                        backdrop = canvasBackdrop,
                        bounds = Bounds(
                            selImg.centerX - selImg.width / 2f, selImg.centerY - selImg.height / 2f,
                            selImg.centerX + selImg.width / 2f, selImg.centerY + selImg.height / 2f
                        ),
                        frameRotation = Math.toRadians(selImg.rotationDeg.toDouble()).toFloat(),
                        scaleX = 1f, scaleY = 1f,
                        offsetX = 0f, offsetY = 0f,
                        canvasScale = vm.canvasScale,
                        canvasOffsetX = vm.canvasOffsetX, canvasOffsetY = vm.canvasOffsetY,
                        hideMidpoints = true,
                        onBodyDrag = { dx, dy -> vm.moveSelectedImage(dx, dy) },
                        onCornerScale = { f -> vm.scaleSelectedImage(f) },
                        onCornerRotate = { r -> vm.rotateSelectedImage(r) },
                        orthoStep = vm.orthoMode,
                        onStepRotate = { d -> vm.stepRotateSelectedImage(d) },
                        onTransformEnd = {},
                        onHandleActiveChanged = { imgHandleActive = it },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                ImageManagePanel(
                    vm = vm,
                    backdrop = canvasBackdrop,
                    onImport = {
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onManage = { showImageManager = true },
                    viewportW = canvasSz.width.toFloat(),
                    viewportH = canvasSz.height.toFloat(),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = glassToolbarInset() + 8.dp)
                )
            }

            // Floating status bar — centered on canvas, above overlays（图片管理模式下让位给图片状态栏）
            if (!vm.imageManageActive) {
                FloatingStatusBar(
                    viewModel = vm,
                    onShowNumberDialog = { numDlg = true },
                    onShowRangeDialog = { rangeDlg = true },
                    onShowNumArrayDialog = { numArrayDlg = true },
                    onEditSelectedText = { editTextDlg = true },
                    canvasBackdrop = canvasBackdrop
                )
            }

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
        GlassTopBar(
            title = nm.ifBlank { "未命名" },
            vm = vm,
            onBack = {
                if (vm.pendingEdit.active) vm.confirmPendingEdit()
                onBack()
            },
            onImportDxf = { dxfPickerTarget = DxfImportMode.MAIN },
            onOpenLayers = { layerDlg = true },
            onOpenBlocks = { insertBlockDlg = true },
            onShare = {
                vm.manualSave(ctx)
                val f = vm.getDocumentFile()
                if (f != null) sh.shareFile(f)
                else Toast.makeText(ctx, "分享失败：文件未保存", Toast.LENGTH_SHORT).show()
            },
            backdrop = canvasBackdrop
        )
        BottomToolbar(
            viewModel = vm,
            onShowNumberDialog = { numDlg = true },
            onShowTextDialog = { textDlg = true },
            onShowRangeDialog = { rangeDlg = true },
            onShowNumArrayDialog = { numArrayDlg = true },
            backdrop = canvasBackdrop,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
        // 下拉菜单浮层（同窗真玻璃）：必须在根 Box 末尾、捕获层之外
        GlassMenuLayer(backdrop = canvasBackdrop)
    }

    // ── 对话框 ──────────────────────────────────────

    if (numDlg) {
        val focusReq = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(100); focusReq.requestFocus() }
        val initVal = vm.numberLabel.currentValue.toString()
        var sv by remember { mutableStateOf(TextFieldValue(text = initVal, selection = TextRange(0, initVal.length))) }
        Dialog(onDismissRequest = { numDlg = false }) {
            GlassOverlayEffect()
            Surface(shape = RoundedCornerShape(16.dp), color = Color(0xE0FFFFFF), modifier = Modifier.width(300.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("标注数字", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2A2A2A))
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(sv, { sv = it.text.filter { c -> c.isDigit() }.let { s -> TextFieldValue(s, TextRange(s.length)) } },
                        label = { Text("起始值") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(focusReq).onFocusChanged { if (it.isFocused) sv = sv.copy(selection = TextRange(0, sv.text.length)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF64B5F6), unfocusedBorderColor = Color(0xFF666666),
                            cursorColor = Color(0xFF2A2A2A), focusedTextColor = Color(0xFF2A2A2A), unfocusedTextColor = Color(0xFF2A2A2A),
                            focusedLabelColor = Color(0xFF64B5F6), unfocusedLabelColor = Color(0xFFAAAAAA)))
                    Spacer(Modifier.height(4.dp))
                    Text("当前: ${vm.numberLabel.currentValue}", fontSize = 14.sp, color = Color(0xFF666666))
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(!vm.numberLabel.horizontalOnly, { vm.toggleHorizontalText() },
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF1565C0), uncheckedColor = Color(0xFF666666), checkmarkColor = Color(0xFF2A2A2A)))
                        Text("竖向", fontSize = 12.sp, color = Color(0xFF666666))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { numDlg = false },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF666666))) { Text("取消") }
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
    if (rangeDlg) {
        val focusStart = remember { FocusRequester() }
        val focusEnd = remember { FocusRequester() }
        val isAutoIncremented = vm.rangeLabel.lastEndValue > 1
        val initialStart = vm.rangeLabel.startValue
        val initialEnd = if (isAutoIncremented) vm.rangeLabel.endValue.toString() else ""
        LaunchedEffect(Unit) { delay(100); if (isAutoIncremented) focusEnd.requestFocus() else focusStart.requestFocus() }
        // 只有即将获得焦点的字段初始全选，另一个字段光标置末尾，避免两个字段同时显示全选
        var svTv by remember { mutableStateOf(TextFieldValue(text = initialStart.toString(), selection = if (isAutoIncremented) TextRange(initialStart.toString().length) else TextRange(0, initialStart.toString().length))) }
        var evTv by remember { mutableStateOf(TextFieldValue(text = initialEnd, selection = if (isAutoIncremented) TextRange(0, initialEnd.length) else TextRange(initialEnd.length))) }
        Dialog(onDismissRequest = { rangeDlg = false }) {
            GlassOverlayEffect()
            Surface(shape = RoundedCornerShape(16.dp), color = Color(0xE0FFFFFF), modifier = Modifier.width(300.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("区间数字", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2A2A2A))
                    Spacer(Modifier.height(12.dp))
                    val fieldColors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF64B5F6), unfocusedBorderColor = Color(0xFF666666),
                        cursorColor = Color(0xFF2A2A2A), focusedTextColor = Color(0xFF2A2A2A), unfocusedTextColor = Color(0xFF2A2A2A),
                        focusedLabelColor = Color(0xFF64B5F6), unfocusedLabelColor = Color(0xFFAAAAAA))
                    OutlinedTextField(svTv, { svTv = it.text.filter { c -> c.isDigit() }.let { s -> TextFieldValue(s, TextRange(s.length)) } },
                        label = { Text("首数字") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(focusStart).onFocusChanged { if (it.isFocused) svTv = svTv.copy(selection = TextRange(0, svTv.text.length)) }.pointerInput(svTv.text) { detectTapGestures { svTv = svTv.copy(selection = TextRange(0, svTv.text.length)) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focusEnd.requestFocus() }), colors = fieldColors)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(evTv, { evTv = it.text.filter { c -> c.isDigit() }.let { s -> TextFieldValue(s, TextRange(s.length)) } },
                        label = { Text("末数字") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(focusEnd).onFocusChanged { if (it.isFocused) evTv = evTv.copy(selection = TextRange(0, evTv.text.length)) }.pointerInput(evTv.text) { detectTapGestures { evTv = evTv.copy(selection = TextRange(0, evTv.text.length)) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = fieldColors)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(!vm.rangeLabel.horizontalOnly, { vm.toggleRangeOrientation() },
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF1565C0), uncheckedColor = Color(0xFF666666), checkmarkColor = Color(0xFF2A2A2A)))
                        Text("竖向", fontSize = 12.sp, color = Color(0xFF666666))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { rangeDlg = false },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF666666))) { Text("取消") }
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

    // ── 数字阵列对话框 ──────────────────────────────
    if (numArrayDlg) {
        val focusStart = remember { FocusRequester() }
        val focusEnd = remember { FocusRequester() }
        val initialStart = vm.numArrayLabel.startValue
        val initialEnd = vm.numArrayLabel.endValue.toString()
        LaunchedEffect(Unit) { delay(100); focusStart.requestFocus() }
        // 首数字初始全选，末数字光标置末尾
        var savTv by remember { mutableStateOf(TextFieldValue(text = initialStart.toString(), selection = TextRange(0, initialStart.toString().length))) }
        var eavTv by remember { mutableStateOf(TextFieldValue(text = initialEnd, selection = TextRange(initialEnd.length))) }
        Dialog(onDismissRequest = { numArrayDlg = false }) {
            GlassOverlayEffect()
            Surface(shape = RoundedCornerShape(16.dp), color = Color(0xE0FFFFFF), modifier = Modifier.width(300.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("数字阵列", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2A2A2A))
                    Spacer(Modifier.height(12.dp))
                    val fieldColors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF64B5F6), unfocusedBorderColor = Color(0xFF666666),
                        cursorColor = Color(0xFF2A2A2A), focusedTextColor = Color(0xFF2A2A2A), unfocusedTextColor = Color(0xFF2A2A2A),
                        focusedLabelColor = Color(0xFF64B5F6), unfocusedLabelColor = Color(0xFFAAAAAA))
                    OutlinedTextField(savTv, { savTv = it.text.filter { c -> c.isDigit() }.let { s -> TextFieldValue(s, TextRange(s.length)) } },
                        label = { Text("首数字") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(focusStart).onFocusChanged { if (it.isFocused) savTv = savTv.copy(selection = TextRange(0, savTv.text.length)) }.pointerInput(savTv.text) { detectTapGestures { savTv = savTv.copy(selection = TextRange(0, savTv.text.length)) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focusEnd.requestFocus() }), colors = fieldColors)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(eavTv, { eavTv = it.text.filter { c -> c.isDigit() }.let { s -> TextFieldValue(s, TextRange(s.length)) } },
                        label = { Text("末数字") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(focusEnd).onFocusChanged { if (it.isFocused) eavTv = eavTv.copy(selection = TextRange(0, eavTv.text.length)) }.pointerInput(eavTv.text) { detectTapGestures { eavTv = eavTv.copy(selection = TextRange(0, eavTv.text.length)) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = fieldColors)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = abs(vm.numArrayLabel.rotationDeg % 180f) > 45f,
                            { vm.toggleNumArrayLayout() },
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF1565C0), uncheckedColor = Color(0xFF666666), checkmarkColor = Color(0xFF2A2A2A)))
                        Text("竖向排列", fontSize = 12.sp, color = Color(0xFF666666))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { numArrayDlg = false },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF666666))) { Text("取消") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            if (eavTv.text.isBlank()) { focusEnd.requestFocus(); return@TextButton }
                            val s = savTv.text.filter { c -> c.isDigit() }.toIntOrNull() ?: initialStart
                            val e = eavTv.text.filter { c -> c.isDigit() }.toIntOrNull() ?: (s + 1)
                            vm.setNumArrayValues(s, e)
                            vm.setTool(ToolType.NUM_ARRAY)
                            numArrayDlg = false
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
        var isVertical by remember { mutableStateOf(!vm.textHorizontalOnly) }
        Dialog(onDismissRequest = { textDlg = false; tcTv = TextFieldValue("") }) {
            GlassOverlayEffect()
            Surface(shape = RoundedCornerShape(16.dp), color = Color(0xE0FFFFFF), modifier = Modifier.width(300.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("输入文字", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2A2A2A))
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(tcTv, { tcTv = it }, label = { Text("文本") }, singleLine = false, maxLines = 3,
                        modifier = Modifier.fillMaxWidth().focusRequester(focusReq).onFocusChanged { if (it.isFocused) tcTv = tcTv.copy(selection = TextRange(0, tcTv.text.length)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF64B5F6), unfocusedBorderColor = Color(0xFF666666),
                            cursorColor = Color(0xFF2A2A2A), focusedTextColor = Color(0xFF2A2A2A), unfocusedTextColor = Color(0xFF2A2A2A),
                            focusedLabelColor = Color(0xFF64B5F6), unfocusedLabelColor = Color(0xFFAAAAAA)))
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(isVertical, { isVertical = !isVertical },
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF1565C0), uncheckedColor = Color(0xFF666666), checkmarkColor = Color(0xFF2A2A2A)))
                        Text("竖向", fontSize = 12.sp, color = Color(0xFF666666))
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { textDlg = false; tcTv = TextFieldValue("") },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF666666))) { Text("取消") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            if (tcTv.text.isNotBlank()) {
                                // 只记录文字内容和方向，等用户点击屏幕时在点击处放置
                                vm.setPendingTextContent(tcTv.text)
                                vm.setTextOrientation(!isVertical)
                                textDlg = false
                            }
                        }, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF64B5F6))) { Text("确定") }
                    }
                }
            }
        }
    }

    if (editTextDlg) {
        // 预填当前选中文字；选择若在弹窗期间变了，取不到就按空串处理
        val initText = vm.selection.selectedIndices.singleOrNull()
            ?.let { vm.primitives.getOrNull(it) as? DrawingPrimitive.TextPrimitive }?.text ?: ""
        EditSelectedTextDialog(
            initial = initText,
            onConfirm = { t -> vm.updateSelectedTextContent(t); editTextDlg = false },
            onDismiss = { editTextDlg = false }
        )
    }

    if (showImageManager) {
        ImageManagerDialog(
            vm = vm,
            viewportW = canvasSz.width.toFloat(),
            viewportH = canvasSz.height.toFloat(),
            onDismiss = { showImageManager = false }
        )
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
            onImportDxf = { insertBlockDlg = false; dxfPickerTarget = DxfImportMode.BLOCK },
            onDismiss = { insertBlockDlg = false }
        )
    }

    // DXF 文件选择（主画布 / 块管理共用一个入口）
    dxfPickerTarget?.let { target ->
        DxfFilePickerDialog(
            onPick = { file ->
                dxfPickerTarget = null
                val prims = runCatching { DxfReader.read(file) }.getOrDefault(emptyList())
                if (prims.isEmpty()) {
                    Toast.makeText(ctx, "未能从该文件解析出图形", Toast.LENGTH_SHORT).show()
                } else {
                    onDxfImport(prims, target)
                }
            },
            onDismiss = { dxfPickerTarget = null }
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
    if (vm.showArrayDlg) {
        ArrayDialog(
            rows = vm.arrayRows.intValue,
            cols = vm.arrayCols.intValue,
            gapX = vm.arrayGapX.floatValue,
            gapY = vm.arrayGapY.floatValue,
            onRowsChange = { vm.arrayRows.intValue = it },
            onColsChange = { vm.arrayCols.intValue = it },
            onGapXChange = { vm.arrayGapX.floatValue = it },
            onGapYChange = { vm.arrayGapY.floatValue = it },
            onConfirm = { vm.executeArray() },
            onDismiss = { vm.dismissArrayDialog() }
        )
    }
}

// ─── 图片管理悬浮面板 ──────────────────────────────

/**
 * 图片管理模式的实时状态栏：纤细单行胶囊，悬浮于画布底部（非模态，画布与已有图形全程可见）。
 * 缩略图点选图片 → 画布上出现 PFO 框，拖位置/等比缩放/旋转；
 * 选中时同排显示透明度滑条（拖动实时生效）与删除按钮；＋从系统图库导入。
 */
@Composable
private fun ImageManagePanel(
    vm: DrawingViewModel,
    onImport: () -> Unit,
    onManage: () -> Unit = {},
    viewportW: Float = 0f,
    viewportH: Float = 0f,
    modifier: Modifier = Modifier,
    backdrop: com.kyant.backdrop.Backdrop? = null,
) {
    val glass = backdrop != null
    val fg: (Color) -> Color = { c -> glassAdapt(c, glass) }
    // 长按缩略图 → 单图操作菜单；重命名单独一个输入框
    var menuImageId by remember { mutableStateOf<String?>(null) }
    var renameImageId by remember { mutableStateOf<String?>(null) }
    Box(
        modifier = modifier.wrapContentWidth().glassSurface(
            backdrop = backdrop,
            shape = RoundedCornerShape(18.dp),
            fallback = Color(0xE6222222)
        )
    ) {
        Row(
            Modifier.height(44.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LazyRow(
                Modifier.weight(1f, fill = false).widthIn(max = 200.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(vm.images, key = { it.id }) { img ->
                    val selected = vm.imgSelectedId == img.id
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .border(
                                2.dp,
                                if (selected) Color(0xFF4B9CD3) else Color.Transparent,
                                RoundedCornerShape(7.dp)
                            )
                            .combinedClickable(
                                onClick = {
                                    if (selected) {
                                        vm.deselectImage()
                                    } else {
                                        vm.selectImage(img.id)
                                        vm.focusImage(img.id, viewportW, viewportH)
                                    }
                                },
                                onLongClick = { menuImageId = img.id }
                            )
                    ) {
                        val bmp = vm.imageBitmaps[img.id]
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "参考图片",
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(5.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(5.dp)).background(if (glass) Color(0x22000000) else Color(0xFF3A3A3A)))
                        }
                        // 隐藏态：压暗 + 眼睛关闭角标
                        if (!img.visible) {
                            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(5.dp)).background(Color(0x99000000)))
                            Icon(Icons.Outlined.VisibilityOff, null,
                                Modifier.size(14.dp).align(Alignment.Center), tint = Color.White)
                        }
                    }
                }
                item {
                    // ＋从系统图库导入
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (glass) Color(0x22000000) else Color(0xFF3A3A3A))
                            .clickable { onImport() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("＋", fontSize = 16.sp, color = fg(Color.White))
                    }
                }
            }
            // 数量：图多时一眼知道总量
            if (vm.images.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text("${vm.images.size}张", fontSize = 10.sp, color = fg(Color(0xFFCCCCCC)))
            }
            // 选中图：透明度滑条（实时）+ 删除
            val sel = vm.selectedImage
            if (sel != null) {
                Spacer(Modifier.width(8.dp))
                Text("透明度", fontSize = 10.sp, color = fg(Color(0xFFCCCCCC)))
                CompactSlider(
                    value = sel.alpha,
                    onValueChange = { vm.setSelectedImageAlpha(it) },
                    valueRange = 0.05f..1f,
                    modifier = Modifier.width(110.dp).padding(horizontal = 6.dp)
                )
                IconButton(onClick = { vm.deleteImage(sel.id) }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Filled.Delete, "删除图片", tint = Color(0xFFE57373), modifier = Modifier.size(16.dp))
                }
                // 换图层（图片仍恒垫底，仅随图层显隐/导出）
                var layerMenu by remember { mutableStateOf(false) }
                Box {
                    Text(
                        vm.layers.firstOrNull { it.id == sel.layerId }?.name ?: "图层",
                        fontSize = 10.sp, color = fg(Color(0xFFCCCCCC)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { layerMenu = true }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                    SchedaDropdownMenu(
                        expanded = layerMenu,
                        onDismissRequest = { layerMenu = false },
                        minWidth = 100.dp
                    ) {
                        vm.layers.forEach { layer ->
                            Text(
                                layer.name, fontSize = 13.sp,
                                color = if (layer.id == sel.layerId) Color(0xFF1565C0) else Color(0xFF2A2A2A),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { layerMenu = false; vm.moveSelectedImageToLayer(layer.id) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            Spacer(Modifier.width(4.dp))
            // 打开图片管理页（按图层分组的网格）
            IconButton(onClick = onManage, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Outlined.GridView, "图片管理", Modifier.size(16.dp), tint = fg(Color(0xFFCCCCCC)))
            }
            Text(
                "完成", fontSize = 12.sp, color = Color(0xFF4B9CD3), fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { vm.exitImageManage() }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }

    // ── 长按缩略图：单图操作 ──
    val menuImg = menuImageId?.let { id -> vm.images.firstOrNull { it.id == id } }
    if (menuImg != null) {
        AlertDialog(
            onDismissRequest = { menuImageId = null },
            containerColor = Color(0xFFF7F7F7),
            shape = RoundedCornerShape(16.dp),
            title = {
                Text(menuImg.name.ifBlank { "图片" }, fontSize = 16.sp,
                    fontWeight = FontWeight.Bold, color = Color(0xFF222222))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ImageMenuRow("重命名") { renameImageId = menuImg.id; menuImageId = null }
                    ImageMenuRow(if (menuImg.visible) "隐藏（不显示、不导出）" else "显示") {
                        vm.setImageVisible(menuImg.id, !menuImg.visible); menuImageId = null
                    }
                    ImageMenuRow("定位到画布") {
                        vm.selectImage(menuImg.id)
                        vm.focusImage(menuImg.id, viewportW, viewportH)
                        menuImageId = null
                    }
                    ImageMenuRow("删除", danger = true) {
                        vm.deleteImage(menuImg.id); menuImageId = null
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { menuImageId = null }) { Text("取消", color = Color(0xFF777777)) }
            }
        )
    }

    // ── 重命名输入框 ──
    val renamingId = renameImageId
    if (renamingId != null) {
        var nameTv by remember(renamingId) {
            mutableStateOf(TextFieldValue(vm.images.firstOrNull { it.id == renamingId }?.name ?: ""))
        }
        AlertDialog(
            onDismissRequest = { renameImageId = null },
            containerColor = Color(0xFFF7F7F7),
            shape = RoundedCornerShape(16.dp),
            title = { Text("重命名图片", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF222222)) },
            text = {
                OutlinedTextField(nameTv, { nameTv = it }, singleLine = true, label = { Text("名称") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF64B5F6), unfocusedBorderColor = Color(0xFF666666),
                        cursorColor = Color(0xFF2A2A2A), focusedTextColor = Color(0xFF2A2A2A), unfocusedTextColor = Color(0xFF2A2A2A),
                        focusedLabelColor = Color(0xFF64B5F6), unfocusedLabelColor = Color(0xFFAAAAAA)))
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameImage(renamingId, nameTv.text)
                    renameImageId = null
                }) { Text("确定", color = Color(0xFF1565C0), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { renameImageId = null }) { Text("取消", color = Color(0xFF777777)) }
            }
        )
    }
}

/** 图片操作菜单的一行（整行可点） */
@Composable
private fun ImageMenuRow(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, fontSize = 14.sp, color = if (danger) Color(0xFFD32F2F) else Color(0xFF2A2A2A))
    }
}
// ─── 图片管理页（按图层分组） ───────────────────────

/** 图片管理页：按图层分组的缩略图网格。点格子=选中并定位到画布；右上眼睛切显隐；长按=重命名/删除。 */
@Composable
private fun ImageManagerDialog(
    vm: DrawingViewModel,
    viewportW: Float,
    viewportH: Float,
    onDismiss: () -> Unit
) {
    // 按图层分组（只列有图的层，层内保持导入顺序）
    val groups = vm.layers.mapNotNull { layer ->
        val list = vm.images.filter { it.layerId == layer.id }
        if (list.isEmpty()) null else layer to list
    }
    // 图层已被删除的孤儿图兜底
    val orphans = vm.images.filter { img -> vm.layers.none { it.id == img.layerId } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        GlassOverlayEffect()
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color(0xF5FFFFFF),
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.78f)
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("图片管理", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color(0xFF222222))
                    Spacer(Modifier.width(8.dp))
                    Text("${vm.images.size} 张", fontSize = 12.sp, color = Color(0xFF999999))
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, "关闭", Modifier.size(18.dp), tint = Color(0xFF666666))
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    groups.forEach { (layer, list) ->
                        item(span = { GridItemSpan(3) }) {
                            Text("${layer.name} · ${list.size} 张", fontSize = 12.sp,
                                color = Color(0xFF999999), modifier = Modifier.padding(top = 6.dp))
                        }
                        items(list, key = { it.id }) { img ->
                            ImageManagerCell(vm, img, viewportW, viewportH, onDismiss)
                        }
                    }
                    if (orphans.isNotEmpty()) {
                        item(span = { GridItemSpan(3) }) {
                            Text("其他 · ${orphans.size} 张", fontSize = 12.sp,
                                color = Color(0xFF999999), modifier = Modifier.padding(top = 6.dp))
                        }
                        items(orphans, key = { it.id }) { img ->
                            ImageManagerCell(vm, img, viewportW, viewportH, onDismiss)
                        }
                    }
                }
            }
        }
    }
}

/** 管理页的一格：缩略图 + 名称 + 显隐眼睛；长按弹重命名/删除。 */
@Composable
private fun ImageManagerCell(
    vm: DrawingViewModel,
    img: ReferenceImage,
    viewportW: Float,
    viewportH: Float,
    onDismiss: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val selected = vm.imgSelectedId == img.id
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFFF0F0F0))
                .border(
                    if (selected) 2.dp else 1.dp,
                    if (selected) Color(0xFF4B9CD3) else Color(0xFFE0E0E0),
                    RoundedCornerShape(10.dp)
                )
                .combinedClickable(
                    onClick = {
                        vm.selectImage(img.id)
                        vm.focusImage(img.id, viewportW, viewportH)
                        onDismiss()
                    },
                    onLongClick = { menuOpen = true }
                )
        ) {
            val bmp = vm.imageBitmaps[img.id]
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = img.name,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop
                )
            }
            if (!img.visible) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Color(0x99000000)))
                Icon(Icons.Outlined.VisibilityOff, null, Modifier.size(20.dp).align(Alignment.Center), tint = Color.White)
            }
            // 右上角显隐开关（不触发格子点击）
            Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(Color(0x66000000))
                    .clickable { vm.setImageVisible(img.id, !img.visible) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (img.visible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                    "显隐", Modifier.size(13.dp), tint = Color.White
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            img.name.ifBlank { "未命名" },
            fontSize = 11.sp, color = if (img.visible) Color(0xFF444444) else Color(0xFF999999),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().clickable { menuOpen = true }.padding(vertical = 2.dp)
        )
    }

    if (menuOpen) {
        AlertDialog(
            onDismissRequest = { menuOpen = false },
            containerColor = Color(0xFFF7F7F7),
            shape = RoundedCornerShape(16.dp),
            title = { Text(img.name.ifBlank { "图片" }, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF222222)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ImageMenuRow("重命名") { menuOpen = false; renaming = true }
                    ImageMenuRow(if (img.visible) "隐藏（不显示、不导出）" else "显示") {
                        vm.setImageVisible(img.id, !img.visible); menuOpen = false
                    }
                    ImageMenuRow("定位到画布") {
                        vm.selectImage(img.id)
                        vm.focusImage(img.id, viewportW, viewportH)
                        menuOpen = false
                        onDismiss()
                    }
                    ImageMenuRow("删除", danger = true) { vm.deleteImage(img.id); menuOpen = false }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { menuOpen = false }) { Text("取消", color = Color(0xFF777777)) } }
        )
    }

    if (renaming) {
        var nameTv by remember(img.id) { mutableStateOf(TextFieldValue(img.name)) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            containerColor = Color(0xFFF7F7F7),
            shape = RoundedCornerShape(16.dp),
            title = { Text("重命名图片", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF222222)) },
            text = {
                OutlinedTextField(nameTv, { nameTv = it }, singleLine = true, label = { Text("名称") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF64B5F6), unfocusedBorderColor = Color(0xFF666666),
                        cursorColor = Color(0xFF2A2A2A), focusedTextColor = Color(0xFF2A2A2A), unfocusedTextColor = Color(0xFF2A2A2A),
                        focusedLabelColor = Color(0xFF64B5F6), unfocusedLabelColor = Color(0xFFAAAAAA)))
            },
            confirmButton = {
                TextButton(onClick = { vm.renameImage(img.id, nameTv.text); renaming = false }) {
                    Text("确定", color = Color(0xFF1565C0), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("取消", color = Color(0xFF777777)) } }
        )
    }
}

// ─── Floating Status Bar ──────────────────────────────

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun BoxScope.FloatingStatusBar(
    viewModel: DrawingViewModel,
    onShowNumberDialog: () -> Unit,
    onShowRangeDialog: () -> Unit,
    onShowNumArrayDialog: () -> Unit = {},
    onEditSelectedText: () -> Unit = {},
    canvasBackdrop: com.kyant.backdrop.backdrops.LayerBackdrop? = null
) {
    val tool = viewModel.currentTool
    // 工具栏只在有内容时出现：SELECT 无选中/无阵列时整条隐藏（原「选择」标题已删，避免空胶囊）
    if (tool == ToolType.SELECT && !viewModel.arrayActive &&
        viewModel.selection.selectedIndices.isEmpty()) return
    // 2026-10-14：通透玻璃面（微调键同款）下的前景色适配；无玻璃时保持原深底配色
    val glass = canvasBackdrop != null
    val adapt: (Color) -> Color = { c ->
        if (!glass) c else when (c) {
            Color.White, Color(0xFFFFFFFF) -> Color(0xFF3A3A3A)
            Color(0xFFD6D6D6) -> Color(0xFF666666)
            Color(0xFFCCCCCC) -> Color(0xFF555555)
            Color(0xFF64B5F6) -> Color(0xFF1565C0)
            Color(0xFFFF6B6B) -> Color(0xFFD32F2F)
            Color(0xFF4CAF50) -> Color(0xFF2E7D32)
            Color(0xFF4B9CD3) -> Color(0xFF0D6EA8)
            Color(0xFFD34B4B) -> Color(0xFFC62828)
            else -> c
        }
    }
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = glassToolbarInset() + 8.dp)
            .wrapContentWidth()
            .then(
                if (canvasBackdrop != null) Modifier.drawBackdrop(
                    backdrop = canvasBackdrop,
                    shape = { RoundedCornerShape(16.dp) },
                    effects = {
                        vibrancy()
                        blur(2f.dp.toPx())
                        lens(12f.dp.toPx(), 24f.dp.toPx())
                    },
                    highlight = { glassHighlight() },
                    onDrawSurface = { drawRect(Color(0x0AFFFFFF)) }
                ) else Modifier
                    .background(Color(0xFF2A2A2A), RoundedCornerShape(16.dp))
            )
    ) {
        Row(
            modifier = Modifier.height(36.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (tool) {
                ToolType.SELECT -> {
                    // Array mode status bar (overrides normal SELECT controls)
                    if (viewModel.arrayActive) {
                        Spacer(Modifier.width(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val dirX = viewModel.arrayDirX.intValue
                            val dirY = viewModel.arrayDirY.intValue
                            val dirColorX = if (dirX > 0) adapt(Color(0xFF4B9CD3)) else adapt(Color(0xFFD34B4B))
                            val dirColorY = if (dirY > 0) adapt(Color(0xFF4B9CD3)) else adapt(Color(0xFFD34B4B))
                            // Row count ±（点击文字弹输入框直接改）
                            var arrayInputTarget by remember { mutableStateOf("") }
                            IconButton(onClick = { viewModel.arrayRows.intValue = maxOf(1, viewModel.arrayRows.intValue - 1) }, modifier = Modifier.size(20.dp)) {
                                Text("−", fontSize = 14.sp, color = adapt(Color.White))
                            }
                            Text("${viewModel.arrayRows.intValue}行", fontSize = 10.sp, color = dirColorY,
                                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { arrayInputTarget = "rows" }.padding(horizontal = 4.dp, vertical = 2.dp))
                            IconButton(onClick = { viewModel.arrayRows.intValue = viewModel.arrayRows.intValue + 1 }, modifier = Modifier.size(20.dp)) {
                                Text("+", fontSize = 14.sp, color = adapt(Color.White))
                            }
                            Spacer(Modifier.width(6.dp))
                            // Column count ±
                            IconButton(onClick = { viewModel.arrayCols.intValue = maxOf(1, viewModel.arrayCols.intValue - 1) }, modifier = Modifier.size(20.dp)) {
                                Text("−", fontSize = 14.sp, color = adapt(Color.White))
                            }
                            Text("${viewModel.arrayCols.intValue}列", fontSize = 10.sp, color = dirColorX,
                                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { arrayInputTarget = "cols" }.padding(horizontal = 4.dp, vertical = 2.dp))
                            IconButton(onClick = { viewModel.arrayCols.intValue = viewModel.arrayCols.intValue + 1 }, modifier = Modifier.size(20.dp)) {
                                Text("+", fontSize = 14.sp, color = adapt(Color.White))
                            }
                            if (arrayInputTarget.isNotEmpty()) {
                                val isRows = arrayInputTarget == "rows"
                                ArrayCountInputDialog(
                                    title = if (isRows) "行数" else "列数",
                                    initial = if (isRows) viewModel.arrayRows.intValue else viewModel.arrayCols.intValue,
                                    onConfirm = { v ->
                                        if (isRows) viewModel.arrayRows.intValue = v else viewModel.arrayCols.intValue = v
                                        arrayInputTarget = ""
                                    },
                                    onDismiss = { arrayInputTarget = "" }
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            // Cancel / Confirm（确认在取消右边）
                            IconButton(onClick = { viewModel.cancelArray() }, modifier = Modifier.size(22.dp)) {
                                Icon(Icons.Default.Close, "取消", Modifier.size(14.dp), tint = adapt(Color(0xFFFF6B6B)))
                            }
                            IconButton(onClick = { viewModel.executeArray() }, modifier = Modifier.size(22.dp)) {
                                Icon(Icons.Default.Check, "确认", Modifier.size(14.dp), tint = adapt(Color(0xFF4CAF50)))
                            }
                        }
                    } else {
                    val selCount = viewModel.selection.selectedIndices.size
                    if (selCount > 0) {
                        Spacer(Modifier.width(6.dp))
                        // Selection action buttons (icon-only, compact)
                        val btnMod = Modifier.size(24.dp)
                        IconButton(onClick = { viewModel.executeSelectionAction(SelectionAction.MIRROR) }, modifier = btnMod) {
                            Icon(Icons.Default.SwapHoriz, "Mirror", Modifier.size(14.dp), tint = adapt(Color.White))
                        }
                        IconButton(onClick = { viewModel.executeSelectionAction(SelectionAction.COPY) }, modifier = btnMod) {
                            Icon(Icons.Default.ContentCopy, "Copy", Modifier.size(14.dp), tint = adapt(Color.White))
                        }
                        IconButton(onClick = { viewModel.executeSelectionAction(SelectionAction.DELETE) }, modifier = btnMod) {
                            Icon(Icons.Default.Delete, "Del", Modifier.size(14.dp), tint = adapt(Color(0xFFFF6B6B)))
                        }
                        // 单选文字：直接编辑内容（弹编辑框，预填当前文字）
                        val singleTextSel = viewModel.selection.selectedIndices.singleOrNull()
                            ?.let { viewModel.primitives.getOrNull(it) as? DrawingPrimitive.TextPrimitive }
                        if (singleTextSel != null) {
                            IconButton(onClick = onEditSelectedText, modifier = btnMod) {
                                Icon(Icons.Outlined.Edit, "编辑文字", Modifier.size(14.dp), tint = adapt(Color.White))
                            }
                        }
                        IconButton(onClick = { viewModel.executeSelectionAction(SelectionAction.PROPERTIES) }, modifier = btnMod) {
                            Icon(Icons.Outlined.Settings, "Props", Modifier.size(14.dp), tint = adapt(Color.White))
                        }
                        IconButton(onClick = { viewModel.executeSelectionAction(SelectionAction.ARRAY) }, modifier = btnMod) {
                            Icon(Icons.Default.GridOn, "阵列", Modifier.size(14.dp), tint = adapt(Color.White))
                        }
                        // Separator
                        Spacer(Modifier.width(4.dp))
                        Text("${selCount}个", fontSize = 10.sp, color = adapt(Color(0xFFD6D6D6)))
                        // 选中元素字号：仅当全部选中元素都是文字类时显示（矩形等无此项→整块不出现）；值取最大
                        val allFontEditable = viewModel.selection.selectedIndices.isNotEmpty() &&
                            viewModel.selection.selectedIndices.all { i ->
                                val pr = viewModel.primitives.getOrNull(i)
                                pr is DrawingPrimitive.NumberLabelPrimitive ||
                                    pr is DrawingPrimitive.TextPrimitive ||
                                    pr is DrawingPrimitive.RangeLabelPrimitive
                            }
                        if (allFontEditable) {
                            val selFonts = viewModel.selection.selectedIndices.mapNotNull { i ->
                                when (val pr = viewModel.primitives.getOrNull(i)) {
                                    is DrawingPrimitive.NumberLabelPrimitive -> pr.fontSize
                                    is DrawingPrimitive.TextPrimitive -> pr.fontSize
                                    is DrawingPrimitive.RangeLabelPrimitive -> pr.fontSize
                                    else -> null
                                }
                            }
                            val fontValue = selFonts.maxOrNull() ?: 40f
                            Spacer(Modifier.width(6.dp))
                            Text("字号", fontSize = 10.sp, color = adapt(Color(0xFFFFFFFF)))
                            Spacer(Modifier.width(4.dp))
                            CompactSlider(
                                value = fontValue,
                                onValueChange = { v -> viewModel.updateSelectedFontSize(v) },
                                valueRange = 30f..600f,
                                modifier = Modifier.width(76.dp).height(32.dp),
                                thumbSize = 12.dp, trackHeight = 6.dp
                            )
                            Spacer(Modifier.width(4.dp))
                            // 固定宽度 + 居中：数字变化不抖，左右留白均衡（原左对齐导致右侧空一大截）
                            Text("${fontValue.toInt()}", fontSize = 12.sp, color = adapt(Color(0xFFCCCCCC)),
                                modifier = Modifier.width(26.dp), textAlign = TextAlign.Center)
                        }
                        // Type filter popup → checkbox dialog
                        var showFilter by remember { mutableStateOf(false) }
                        if (showFilter) {
                            val typeCounts = remember { viewModel.getSelectionTypeCounts() }
                            var checkedTypes by remember(typeCounts) { mutableStateOf(typeCounts.keys.toSet()) }
                            AlertDialog(onDismissRequest = { showFilter = false },
                                title = {
                                    Text("筛选类型", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF222222),
                                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                                },
                                containerColor = Color(0xFFF7F7F7),
                                shape = RoundedCornerShape(16.dp),
                                text = {
                                    Column(Modifier.widthIn(min = 230.dp)) {
                                        typeCounts.entries.forEachIndexed { i, (typeName, count) ->
                                            val checked = typeName in checkedTypes
                                            Row(verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(if (checked) Color(0xFF1565C0).copy(alpha = 0.10f) else Color.White)
                                                    .border(1.dp, if (checked) Color(0xFF1565C0).copy(alpha = 0.4f) else Color(0xFFE0E0E0), RoundedCornerShape(10.dp))
                                                    .clickable {
                                                        checkedTypes = if (checked)
                                                            checkedTypes - typeName else checkedTypes + typeName
                                                    }
                                                    .padding(horizontal = 10.dp, vertical = 8.dp)) {
                                                Checkbox(checked = checked, onCheckedChange = null,
                                                    modifier = Modifier.size(20.dp),
                                                    colors = CheckboxDefaults.colors(
                                                        checkedColor = Color(0xFF1565C0),
                                                        uncheckedColor = Color(0xFF777777),
                                                        checkmarkColor = Color(0xFF2A2A2A)))
                                                Spacer(Modifier.width(10.dp))
                                                Text(typeName, fontSize = 14.sp, color = Color(0xFF222222), modifier = Modifier.weight(1f))
                                                Text("${count}个", fontSize = 12.sp, color = Color(0xFF777777))
                                            }
                                            if (i < typeCounts.size - 1) Spacer(Modifier.height(6.dp))
                                        }
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        val indices = viewModel.selection.selectedIndices.toMutableSet()
                                        indices.removeAll { i ->
                                            i >= viewModel.primitives.size || viewModel.primitives[i].typeName !in checkedTypes
                                        }
                                        if (indices.isEmpty()) viewModel.clearSelection()
                                        else {
                                            viewModel.filterSelectionToIndices(indices)
                                        }
                                        showFilter = false
                                    }) { Text("应用", color = Color(0xFF1565C0), fontWeight = FontWeight.Bold) }
                                },
                                dismissButton = { TextButton(onClick = { showFilter = false }) { Text("取消", color = Color(0xFF777777)) } }
                            )
                        }
                        Box {
                            IconButton(onClick = { showFilter = true }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Outlined.FilterAlt, "筛选类型", Modifier.size(14.dp), tint = adapt(Color.White))
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                        // Deselect button
                        IconButton(onClick = { viewModel.clearSelection() }, modifier = Modifier.size(22.dp)) {
                            Icon(Icons.Default.Close, "取消选择", Modifier.size(14.dp), tint = adapt(Color(0xFFFF6B6B)))
                        }
                        }
                    }
                }
                ToolType.ERASER -> {
                    Text("半径:", fontSize = 11.sp, color = adapt(Color(0xFFFFFFFF)))
                    Spacer(Modifier.width(2.dp))
                    Text("${viewModel.displayEraserRadius.toInt()}", fontSize = 12.sp, color = adapt(Color.White), fontWeight = FontWeight.Bold, modifier = Modifier.width(30.dp))
                    Spacer(Modifier.width(4.dp))
                    CompactSlider(
                        value = viewModel.displayEraserRadius,
                        onValueChange = { viewModel.setEraserRadius(it) },
                        valueRange = 5f..100f,
                        modifier = Modifier.width(80.dp).height(32.dp),
                        thumbSize = 12.dp,
                        trackHeight = 6.dp
                    )
                    Spacer(Modifier.width(6.dp))
                    SmallToggleChip(
                        light = glass,
                        label = "长按",
                        selected = viewModel.quickEraseEnabled,
                        onClick = { viewModel.toggleQuickEraseEnabled() }
                    )
                    Spacer(Modifier.width(4.dp))
                    SmallToggleChip(
                        light = glass,
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
                        color = adapt(Color(0xFF64B5F6)),
                        modifier = Modifier.clickable { onShowNumberDialog() }
                    )
                    Spacer(Modifier.width(6.dp))
                    // Font size slider
                    val currentFs = if (peActive) viewModel.getPendingEffectiveFontSize() else viewModel.numberLabel.fontSize
                    Text("字号", fontSize = 10.sp, color = adapt(Color(0xFFFFFFFF)))
                    Spacer(Modifier.width(6.dp))
                    CompactSlider(
                        value = currentFs,
                        onValueChange = { newSize ->
                            if (peActive && pe.primitive is DrawingPrimitive.NumberLabelPrimitive)
                                viewModel.updatePendingFontSize(newSize)
                            else
                                viewModel.setNumberFontSize(newSize)
                                                        },
                                                        valueRange = 30f..600f,
                        modifier = Modifier.width(80.dp).height(32.dp),
                        thumbSize = 12.dp,
                        trackHeight = 6.dp
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("${(((currentFs - 30f) / 570f).coerceIn(0f, 1f) * 99 + 0.5f).toInt() + 1}",
                        fontSize = 12.sp, color = adapt(Color(0xFFFFFFFF)), modifier = Modifier.width(20.dp))
                    Spacer(Modifier.width(4.dp))
                    // Orientation toggle
                    val isHoriz = if (peActive) {
                        (pe.primitive as? DrawingPrimitive.NumberLabelPrimitive)?.horizontalOnly ?: viewModel.numberLabel.horizontalOnly
                    } else viewModel.numberLabel.horizontalOnly
                    IconButton(onClick = { viewModel.toggleHorizontalText() }, modifier = Modifier.size(26.dp)) {
                        Text(if (isHoriz) "横" else "竖", fontSize = 12.sp, color = adapt(Color.White), fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(4.dp))
                    // 外圈开关
                    val isCircled = if (peActive && peNum != null) peNum.circled else viewModel.numberLabel.circled
                    SmallToggleChip(
                        light = glass,
                        label = "圈",
                        selected = isCircled,
                        onClick = { viewModel.toggleNumberCircled() },
                        modifier = Modifier.width(32.dp)
                    )
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
                        color = adapt(Color(0xFF64B5F6)),
                        modifier = Modifier.clickable { onShowRangeDialog() }
                    )
                    Spacer(Modifier.width(4.dp))
                    // Font size slider
                    val currentFs = if (peActive && peRange != null) viewModel.getPendingEffectiveFontSize() else viewModel.rangeLabel.fontSize
                    Text("字号", fontSize = 12.sp, color = adapt(Color(0xFFFFFFFF)))
                    Spacer(Modifier.width(6.dp))
                    CompactSlider(
                        value = currentFs,
                        onValueChange = { if (peActive && peRange != null) viewModel.updatePendingRangeFontSize(it) else viewModel.setRangeFontSize(it) },
                        valueRange = 20f..600f,
                        modifier = Modifier.width(80.dp).height(32.dp),
                        thumbSize = 12.dp,
                        trackHeight = 6.dp
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("${(((currentFs - 20f) / 580f).coerceIn(0f, 1f) * 99 + 0.5f).toInt() + 1}",
                        fontSize = 12.sp, color = adapt(Color(0xFFFFFFFF)), modifier = Modifier.width(28.dp))
                    Spacer(Modifier.width(6.dp))
                    SmallToggleChip(
                        light = glass,
                        label = "⇄",
                        selected = viewModel.rangeLabel.reversed,
                        onClick = { viewModel.toggleRangeReversed() },
                        modifier = Modifier.width(32.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    SmallToggleChip(
                        light = glass,
                        label = if (peActive && peRange != null) peRange.horizontalOnly.let { if (it) "横" else "竖" } else if (viewModel.rangeLabel.horizontalOnly) "横" else "竖",
                        selected = !(if (peActive && peRange != null) peRange.horizontalOnly else viewModel.rangeLabel.horizontalOnly),
                        onClick = { viewModel.toggleHorizontalText() },
                        modifier = Modifier.width(32.dp)
                    )
                }
                ToolType.NUM_ARRAY -> {
                    val na = viewModel.numArrayLabel
                    Text(
                        text = "${na.startValue}→${na.endValue}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = adapt(Color(0xFF64B5F6)),
                        modifier = Modifier.clickable { onShowNumArrayDialog() }
                    )
                    Spacer(Modifier.width(4.dp))
                    // Font size slider
                    Text("字号", fontSize = 12.sp, color = adapt(Color(0xFFFFFFFF)))
                    Spacer(Modifier.width(6.dp))
                    CompactSlider(
                        value = na.fontSize,
                        onValueChange = { viewModel.setNumArrayFontSize(it) },
                        valueRange = 20f..600f,
                        modifier = Modifier.width(80.dp).height(32.dp),
                        thumbSize = 12.dp,
                        trackHeight = 6.dp
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("${(((na.fontSize - 20f) / 580f).coerceIn(0f, 1f) * 99 + 0.5f).toInt() + 1}",
                        fontSize = 12.sp, color = adapt(Color(0xFFFFFFFF)), modifier = Modifier.width(28.dp))
                    Spacer(Modifier.width(6.dp))
                    // 外圈开关（方向改由画布上的方向手柄拖动调整）
                    SmallToggleChip(
                        light = glass,
                        label = "圈",
                        selected = na.circled,
                        onClick = { viewModel.toggleNumArrayCircled() },
                        modifier = Modifier.width(32.dp)
                    )
                }
                ToolType.FREEHAND -> {
                    val ct = viewModel.currentLineStyle
                    val ltLabel = when (ct.type) { LineType.DASHED -> "虚线"; LineType.LIGHTNING -> "闪电"; else -> "实线" }
                    Text("线宽:${viewModel.strokeWidth.toInt()} $ltLabel", fontSize = 11.sp, color = adapt(Color(0xFFD6D6D6)))
                }
                ToolType.LINE -> {
                    val ct = viewModel.currentLineStyle
                    val ltLabel = when (ct.type) { LineType.DASHED -> "虚线"; LineType.LIGHTNING -> "闪电"; else -> "实线" }
                    Text("线宽:${viewModel.strokeWidth.toInt()} $ltLabel", fontSize = 11.sp, color = adapt(Color(0xFFD6D6D6)))
                    Spacer(Modifier.width(6.dp))
                    SmallToggleChip(
                        light = glass,
                        label = "标准",
                        selected = viewModel.lineSnapMode,
                        onClick = { viewModel.toggleLineSnapMode() }
                    )
                }
                ToolType.RECTANGLE -> {
                    val ct = viewModel.currentLineStyle
                    val ltLabel = when (ct.type) { LineType.DASHED -> "虚线"; LineType.LIGHTNING -> "闪电"; else -> "实线" }
                    Text("线宽:${viewModel.strokeWidth.toInt()} $ltLabel", fontSize = 11.sp, color = adapt(Color(0xFFD6D6D6)))
                    Spacer(Modifier.width(6.dp))
                    SmallToggleChip(
                        light = glass,
                        label = "正方形",
                        selected = viewModel.rectangleSquareMode,
                        onClick = { viewModel.toggleRectangleSquareMode() },
                        enabled = !viewModel.pendingEdit.isActive()
                    )
                }
                ToolType.CIRCLE -> {
                    val ct = viewModel.currentLineStyle
                    val ltLabel = when (ct.type) { LineType.DASHED -> "虚线"; LineType.LIGHTNING -> "闪电"; else -> "实线" }
                    Text("线宽:${viewModel.strokeWidth.toInt()} $ltLabel", fontSize = 11.sp, color = adapt(Color(0xFFD6D6D6)))
                    Spacer(Modifier.width(6.dp))
                    SmallToggleChip(
                        light = glass,
                        label = "正圆",
                        selected = viewModel.circleCircleMode,
                        onClick = { viewModel.toggleCircleCircleMode() },
                        enabled = !viewModel.pendingEdit.isActive()
                    )
                }
                ToolType.TEXT -> {
                    val pe = viewModel.pendingEdit
                    val peActive = pe.isActive()
                    val peText = pe.primitive as? DrawingPrimitive.TextPrimitive
                    // 不显示输入的文字内容，只保留字号 + 方向切换
                    // Font size slider（范围与缩放钳制一致 30..600，右侧显示 1-100 等级）
                    val currentFs = if (peActive) viewModel.getPendingEffectiveFontSize() else viewModel.getLastTextFontSize()
                    Text("字号", fontSize = 10.sp, color = adapt(Color(0xFFFFFFFF)))
                    Spacer(Modifier.width(6.dp))
                    CompactSlider(
                        value = currentFs,
                        onValueChange = {
                            if (peActive) viewModel.updatePendingFontSize(it)
                            else viewModel.setLastTextFontSize(it)
                        },
                        valueRange = 30f..600f,
                        modifier = Modifier.width(80.dp).height(32.dp),
                        thumbSize = 12.dp,
                        trackHeight = 6.dp
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("${(((currentFs - 30f) / 570f).coerceIn(0f, 1f) * 99 + 0.5f).toInt() + 1}",
                        fontSize = 12.sp, color = adapt(Color(0xFFFFFFFF)), modifier = Modifier.width(20.dp))
                    Spacer(Modifier.width(4.dp))
                    // Orientation toggle — read from pendingEdit when active, else from persisted state
                    val isHoriz = if (peActive && peText != null) peText.horizontalOnly
                        else viewModel.textHorizontalOnly
                    IconButton(onClick = { viewModel.toggleHorizontalText() }, modifier = Modifier.size(26.dp)) {
                        Text(if (isHoriz) "横" else "竖", fontSize = 12.sp, color = adapt(Color.White), fontWeight = FontWeight.Bold)
                    }
                }
                else -> {}
            }
        }
    }
}

/** 阵列行/列数输入对话框 */
@Composable
private fun ArrayCountInputDialog(
    title: String,
    initial: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
            GlassOverlayEffect()
    var tv by remember { mutableStateOf(TextFieldValue(initial.toString(), selection = TextRange(0, initial.toString().length))) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF222222),
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
        containerColor = Color(0xFFF7F7F7),
        shape = RoundedCornerShape(16.dp),
        text = {
            OutlinedTextField(
                tv, { tv = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) tv = tv.copy(selection = TextRange(0, tv.text.length)) }
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val v = tv.text.filter { it.isDigit() }.toIntOrNull()
                if (v != null && v >= 1) onConfirm(v)
            }) { Text("确定", color = Color(0xFF1565C0), fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = Color(0xFF777777)) } }
    )
}

@Composable
private fun SmallToggleChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, light: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected && enabled) Color(0xFF1565C0) else Color.Transparent,
        border = if (!selected || !enabled) BorderStroke(1.dp, if (enabled) Color(0xFF666666) else Color(0xFF3A3A3A)) else null,
        modifier = modifier.height(22.dp).clickable(enabled = enabled) { onClick() }
    ) {
        Box(modifier = Modifier.fillMaxHeight().padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 11.sp,
                color = if (!enabled) Color(0xFF555555) else if (selected) Color.White else (if (light) Color(0xFF555555) else Color(0xFFAAAAAA)))
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
    onImportDxf: () -> Unit,
    onDismiss: () -> Unit
) {
            GlassOverlayEffect()
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showConfirmDelete by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
            GlassOverlayEffect()
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xE0FFFFFF),
            modifier = Modifier.width(340.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                // 标题行
                Row(
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("图块管理", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2A2A2A))
                    Spacer(Modifier.width(8.dp))
                    Text("${blockDefs.size}个", fontSize = 12.sp, color = Color(0xFF888888))
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
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Outlined.Widgets, null, Modifier.size(40.dp), tint = Color(0xFF555555))
                        Spacer(Modifier.height(8.dp))
                        Text("还没有图块", fontSize = 14.sp, color = Color(0xFF888888))
                        Spacer(Modifier.height(4.dp))
                        Text("新建或从 DXF 导入", fontSize = 12.sp, color = Color(0xFF666666))
                    }
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

                // 底部操作：导入 DXF + 新建块
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        onClick = onImportDxf,
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, Color(0xFF64B5F6)),
                        modifier = Modifier.weight(1f).height(44.dp)
                    ) {
                        Row(
                            Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.FileDownload, null, Modifier.size(18.dp), tint = Color(0xFF64B5F6))
                            Spacer(Modifier.width(6.dp))
                            Text("导入 DXF", fontSize = 14.sp, color = Color(0xFF64B5F6))
                        }
                    }
                    Button(
                        onClick = onNewBlock,
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1565C0),
                            contentColor = Color.White
                        )
                    ) { Text("＋ 新建块", fontSize = 14.sp) }
                }
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

// ─── DXF 文件选择对话框（只显示目录和 .dxf 文件，单选） ───

@Composable
private fun DxfFilePickerDialog(
    onPick: (File) -> Unit,
    onDismiss: () -> Unit
) {
            GlassOverlayEffect()
    val rootDir = remember {
        android.os.Environment.getExternalStorageDirectory()?.takeIf { it.exists() && it.canRead() }
    }
    var curDir by remember { mutableStateOf(rootDir) }
    var selected by remember { mutableStateOf<File?>(null) }
    // 最上级目录限制为外部存储根目录（/storage/emulated/0），不能再往上
    val canGoUp = curDir != null && rootDir != null &&
        curDir!!.absolutePath != rootDir!!.absolutePath && curDir!!.parentFile != null
    fun goUp() {
        if (canGoUp) { curDir = curDir!!.parentFile; selected = null }
    }
    val entries = remember(curDir) {
        curDir?.listFiles()
            ?.filter { (it.isDirectory && !it.isHidden) || (it.isFile && it.name.lowercase().endsWith(".dxf")) }
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: emptyList()
    }

    // 左右滑返回手势只返回上级目录，不退出选择器；只有点"取消"才退出
    Dialog(
        onDismissRequest = { goUp() },
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false)
    ) {
            GlassOverlayEffect()
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xE0FFFFFF),
            modifier = Modifier.width(340.dp).heightIn(max = 480.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("选择 DXF 文件", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2A2A2A),
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                // 当前路径 + 返回上级
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { goUp() },
                        enabled = canGoUp,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Outlined.ArrowBack, "上级", Modifier.size(18.dp),
                            tint = if (canGoUp) Color(0xFF64B5F6) else Color(0xFF555555))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        curDir?.absolutePath ?: "存储",
                        fontSize = 11.sp, color = Color(0xFF888888), maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(6.dp))
                // 文件夹切换过渡动画：进入子文件夹向左滑，返回上级向右滑
                AnimatedContent(
                    targetState = curDir,
                    transitionSpec = {
                        val initialDepth = initialState?.absolutePath?.count { c -> c == '/' } ?: 0
                        val targetDepth = targetState?.absolutePath?.count { c -> c == '/' } ?: 0
                        if (targetDepth >= initialDepth) {
                            (slideInHorizontally { it } + fadeIn()) togetherWith
                            (slideOutHorizontally { -it } + fadeOut())
                        } else {
                            (slideInHorizontally { -it } + fadeIn()) togetherWith
                            (slideOutHorizontally { it } + fadeOut())
                        }
                    },
                    label = "dxfFolderList",
                    modifier = Modifier.weight(1f)
                ) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(entries) { f ->
                        val isSel = selected == f
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSel) Color(0x334B9CD3) else Color.Transparent)
                                .clickable {
                                    if (f.isDirectory) { curDir = f; selected = null }
                                    else selected = if (isSel) null else f
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                if (f.isDirectory) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile,
                                null, Modifier.size(18.dp),
                                tint = if (f.isDirectory) Color(0xFFF9A825) else Color(0xFF64B5F6)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(f.name, fontSize = 13.sp,
                                color = if (isSel) Color(0xFF1565C0) else Color(0xFF2A2A2A), maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                    if (entries.isEmpty()) {
                        item {
                            Text("此目录下没有 .dxf 文件", fontSize = 13.sp, color = Color(0xFF777777),
                                modifier = Modifier.padding(16.dp))
                        }
                    }
                }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消", color = Color(0xFFAAAAAA)) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { selected?.let(onPick) },
                        enabled = selected != null
                    ) {
                        Text("确定", color = if (selected != null) Color(0xFF64B5F6) else Color(0xFF555555),
                            fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
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
                                is DrawingPrimitive.RectanglePrimitive -> {
                                    val xs = p.corners.map { it.x }; val ys = p.corners.map { it.y }
                                    floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
                                }
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
                                is DrawingPrimitive.RangeLabelPrimitive -> {
                                    val arrowLen = maxOf(80f * p.arrowSpan, 20f)
                                    val hw = arrowLen / 2f + p.fontSize; val hh = p.fontSize * 0.5f
                                    floatArrayOf(p.x - hw, p.y - hh, p.x + hw, p.y + hh)
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
                                            val scaledPoints = p.points.map { pt ->
                                                Point2D(pt.x * s + ox, pt.y * s + oy)
                                            }
                                            val path = smoothPathFromPoints(scaledPoints, p.isClosed)
                                            drawPath(path, p.color, style = Stroke(1.5f))
                                        }
                                    }
                                    is DrawingPrimitive.RectanglePrimitive -> {
                                        val xs = p.corners.map { it.x * s + ox }; val ys = p.corners.map { it.y * s + oy }
                                        val path = Path().apply { moveTo(xs[0], ys[0]); lineTo(xs[1], ys[1]); lineTo(xs[2], ys[2]); lineTo(xs[3], ys[3]); close() }
                                        drawPath(path, p.color, style = Stroke(1.5f))
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
                                    is DrawingPrimitive.NumberLabelPrimitive -> {
                                        val paint = android.graphics.Paint().apply {
                                            color = p.color.toArgb()
                                            textSize = p.fontSize * s * 1.3f
                                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                                            isAntiAlias = true
                                            textAlign = android.graphics.Paint.Align.CENTER
                                        }
                                        drawContext.canvas.nativeCanvas.drawText(
                                            p.value.toString(),
                                            p.x * s + ox,
                                            p.y * s + oy + p.fontSize * s * 0.4f,
                                            paint
                                        )
                                    }
                                    is DrawingPrimitive.TextPrimitive -> {
                                        val paint = android.graphics.Paint().apply {
                                            color = p.color.toArgb()
                                            textSize = p.fontSize * s * 1.3f
                                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                                            isAntiAlias = true
                                            textAlign = android.graphics.Paint.Align.CENTER
                                        }
                                        drawContext.canvas.nativeCanvas.drawText(
                                            p.text,
                                            p.x * s + ox,
                                            p.y * s + oy + p.fontSize * s * 0.4f,
                                            paint
                                        )
                                    }
                                    is DrawingPrimitive.RangeLabelPrimitive -> {
                                        val paint = android.graphics.Paint().apply {
                                            color = p.color.toArgb()
                                            textSize = p.fontSize * s * 1.3f
                                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                                            isAntiAlias = true
                                            textAlign = android.graphics.Paint.Align.CENTER
                                        }
                                        val arrowLen = maxOf(80f * s * p.arrowSpan, 20f)
                                        val gap = p.fontSize * s
                                        val leftX = p.x * s + ox - arrowLen / 2f - gap
                                        val rightX = p.x * s + ox + arrowLen / 2f + gap
                                        val textY = p.y * s + oy
                                        val label1 = if (p.reversed) p.endValue.toString() else p.startValue.toString()
                                        val label2 = if (p.reversed) p.startValue.toString() else p.endValue.toString()
                                        drawContext.canvas.nativeCanvas.drawText(label1, leftX, textY + paint.textSize * 0.35f, paint)
                                        drawContext.canvas.nativeCanvas.drawText(label2, rightX, textY + paint.textSize * 0.35f, paint)
                                        drawLine(p.color, Offset(p.x * s + ox - arrowLen / 2f, textY), Offset(p.x * s + ox + arrowLen / 2f, textY), strokeWidth = 1.5f)
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

/** 编辑选中文字的弹窗：预填当前内容，确认后整段写回（不改变字号/颜色/方向）。 */
@Composable
private fun EditSelectedTextDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val focusReq = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(120); focusReq.requestFocus() }
    var tv by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    Dialog(onDismissRequest = onDismiss) {
        GlassOverlayEffect()
        Surface(shape = RoundedCornerShape(16.dp), color = Color(0xE0FFFFFF), modifier = Modifier.width(300.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text("编辑文字", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2A2A2A))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(tv, { tv = it }, label = { Text("文本") }, singleLine = false, maxLines = 3,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusReq),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF64B5F6), unfocusedBorderColor = Color(0xFF666666),
                        cursorColor = Color(0xFF2A2A2A), focusedTextColor = Color(0xFF2A2A2A), unfocusedTextColor = Color(0xFF2A2A2A),
                        focusedLabelColor = Color(0xFF64B5F6), unfocusedLabelColor = Color(0xFFAAAAAA)))
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF666666))) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { if (tv.text.isNotBlank()) onConfirm(tv.text) },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF64B5F6))) { Text("确定") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PropertiesDialog(vm: DrawingViewModel) {
            GlassOverlayEffect()
    val indices = vm.selection.selectedIndices.toList()
    val primitives = indices.mapNotNull { i -> vm.primitives.getOrNull(i) }
    val typeCounts = primitives.groupBy { categorizePrimitive(it) }.mapValues { it.value.size }
    val hasMultipleTypes = typeCounts.size > 1
    var selectedCategory by remember { mutableStateOf<PropCat?>(if (hasMultipleTypes) null else typeCounts.keys.firstOrNull()) }
    val catPrimitives = if (selectedCategory != null) primitives.filter { categorizePrimitive(it) == selectedCategory } else emptyList()
    val firstP = catPrimitives.firstOrNull()

    AlertDialog(
        onDismissRequest = { vm.dismissPropertiesDialog() },
        containerColor = Color(0xFFF7F7F7),
        shape = RoundedCornerShape(16.dp),
        title = {
            val cat = selectedCategory
            Text(
                if (cat != null) "${cat.label}属性 (${catPrimitives.size}个)"
                else "对象属性 (${indices.size}个)",
                fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF222222),
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
            )
        },
        text = {
            if (selectedCategory == null) {
                // 分类菜单：白色磁贴
                Column(Modifier.widthIn(min = 240.dp, max = 280.dp)) {
                    typeCounts.forEach { (cat, count) ->
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White)
                                .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(10.dp))
                                .clickable { selectedCategory = cat }
                                .padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(cat.icon, fontSize = 16.sp, color = Color(0xFF1565C0))
                            Spacer(Modifier.width(10.dp))
                            Text(cat.label, fontSize = 14.sp, color = Color(0xFF222222), modifier = Modifier.weight(1f))
                            Text("${count}个", fontSize = 12.sp, color = Color(0xFF777777))
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            } else {
                Column(Modifier.widthIn(max = 300.dp)) {
                    // 多类型时显示返回按钮
                    if (hasMultipleTypes) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selectedCategory = null }
                                .padding(horizontal = 4.dp, vertical = 4.dp)) {
                            Icon(Icons.Outlined.ArrowBack, "返回", modifier = Modifier.size(16.dp), tint = Color(0xFF1565C0))
                            Spacer(Modifier.width(4.dp))
                            Text("返回分类", fontSize = 13.sp, color = Color(0xFF1565C0))
                        }
                        Spacer(Modifier.height(8.dp))
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
            TextButton(onClick = { vm.dismissPropertiesDialog() }) { Text("关闭", color = Color(0xFF777777)) }
        }
    )
}

@Composable
private fun PropSectionTitle(text: String) {
    Text(text, fontSize = 12.sp, color = Color(0xFF999999))
    Spacer(Modifier.height(6.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PropColorRow(color: Color, onTogglePicker: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(28.dp).combinedClickable(onClick = onTogglePicker),
            shape = RoundedCornerShape(6.dp), color = color,
            border = BorderStroke(1.dp, Color(0xFFCCCCCC))) {}
        Spacer(Modifier.width(10.dp))
        Text("#${color.toArgbColorHex().takeLast(6)}", fontSize = 13.sp, color = Color(0xFF333333))
    }
}

// ── Graphics Properties Panel ──
@Composable
private fun ColumnScope.GraphicsPropsPanel(vm: DrawingViewModel, p: DrawingPrimitive, multi: Boolean) {
    var lineType by remember(p) { mutableStateOf(p.lineStyle.type) }
    var color by remember(p) { mutableStateOf(p.color) }
    var showColorPicker by remember { mutableStateOf(false) }

    if (multi) {
        Text("修改将影响所有选中图形", fontSize = 12.sp, color = Color(0xFF999999))
        Spacer(Modifier.height(10.dp))
    }

    // 线型
    PropSectionTitle("线型")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LineType.entries.forEach { lt ->
            val sel = lineType == lt
            Surface(shape = RoundedCornerShape(14.dp),
                color = if (sel) Color(0xFF1565C0) else Color.White,
                border = if (sel) null else BorderStroke(1.dp, Color(0xFFDDDDDD)),
                modifier = Modifier.clickable {
                    lineType = lt
                    vm.updateSelectedLineStyle(LineStyle(type = lt))
                }) {
                Text(lt.displayName, fontSize = 12.sp,
                    color = if (sel) Color.White else Color(0xFF555555),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
    Spacer(Modifier.height(14.dp))

    // 颜色（与主画布工具栏一致：点开弹出颜色选择对话框）
    PropSectionTitle("颜色")
    PropColorRow(color) { showColorPicker = true }
    if (showColorPicker) {
        ColorPickerDialog(color, { c ->
            color = c; vm.updateSelectedColor(c); showColorPicker = false
        }, { showColorPicker = false })
    }
}

// ── Text / Number Properties Panel ──
@Composable
private fun ColumnScope.TextNumPropsPanel(vm: DrawingViewModel, p: DrawingPrimitive, cat: PropCat, multi: Boolean) {
    var color by remember(p) { mutableStateOf(p.color) }
    var fontSize by remember(p) { mutableFloatStateOf((p as? DrawingPrimitive.TextPrimitive)?.fontSize ?: (p as? DrawingPrimitive.NumberLabelPrimitive)?.fontSize ?: 30f) }
    var textContent by remember(p) { mutableStateOf((p as? DrawingPrimitive.TextPrimitive)?.text ?: "") }
    var showColorPicker by remember { mutableStateOf(false) }
    val isText = cat == PropCat.TEXT

    if (multi) {
        Text("修改将影响所有选中${cat.label}", fontSize = 12.sp, color = Color(0xFF999999))
        Spacer(Modifier.height(10.dp))
    }

    // 颜色
    PropSectionTitle("颜色")
    PropColorRow(color) { showColorPicker = !showColorPicker }
    if (showColorPicker) {
        Spacer(Modifier.height(6.dp))
        ColorPickerBar { c ->
            color = c; vm.updateSelectedColor(c); showColorPicker = false
        }
    }
    Spacer(Modifier.height(14.dp))

    // 字号
    PropSectionTitle("字号")
    Row(verticalAlignment = Alignment.CenterVertically) {
        CompactSlider(
            value = fontSize,
            onValueChange = { fontSize = it; vm.updateSelectedFontSize(it) },
            valueRange = 30f..600f,
            modifier = Modifier.weight(1f).height(28.dp),
            thumbSize = 12.dp, trackHeight = 6.dp,
            trackColor = Color(0xFFDCDCDC), activeColor = Color(0xFF1565C0)
        )
        Spacer(Modifier.width(10.dp))
        Text("${fontSize.toInt()}", fontSize = 13.sp,
            color = Color(0xFF333333), modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
    }

    // 外圈（仅数字）
    if (!isText) {
        Spacer(Modifier.height(14.dp))
        PropSectionTitle("外圈")
        var circled by remember(p) { mutableStateOf((p as? DrawingPrimitive.NumberLabelPrimitive)?.circled ?: false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(circled, { circled = it; vm.updateSelectedCircled(it) },
                colors = CheckboxDefaults.colors(checkedColor = Color(0xFF1565C0), uncheckedColor = Color(0xFF666666), checkmarkColor = Color.White))
            Text("数字加外圈圆圈", fontSize = 13.sp, color = Color(0xFF333333))
        }
    }

    // 数字朝向（仅区间数字：两端数字 朝下=正向 / 朝左=数字下方朝屏幕左边）
    val rangeP = p as? DrawingPrimitive.RangeLabelPrimitive
    if (!isText && rangeP != null) {
        Spacer(Modifier.height(14.dp))
        PropSectionTitle("数字朝向")
        var faceLeft by remember(p) { mutableStateOf(rangeP.numbersFaceLeft) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(false to "朝下", true to "朝左").forEach { (fl, label) ->
                val sel = faceLeft == fl
                Surface(shape = RoundedCornerShape(14.dp),
                    color = if (sel) Color(0xFF1565C0) else Color.White,
                    border = if (sel) null else BorderStroke(1.dp, Color(0xFFDDDDDD)),
                    modifier = Modifier.clickable {
                        faceLeft = fl
                        vm.updateSelectedRangeNumbersFaceLeft(fl)
                    }) {
                    Text(label, fontSize = 12.sp,
                        color = if (sel) Color.White else Color(0xFF555555),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }
    }

    // 内容
    if (isText) {
        Spacer(Modifier.height(14.dp))
        PropSectionTitle("文本内容")
        var textContentTv by remember(p) { mutableStateOf(TextFieldValue(textContent)) }
        OutlinedTextField(
            value = textContentTv,
            onValueChange = { textContentTv = it; textContent = it.text; vm.updateSelectedTextContent(it.text) },
            singleLine = false, maxLines = 3,
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) textContentTv = textContentTv.copy(selection = TextRange(0, textContentTv.text.length)) }
        )
    }
}

// ── Block Properties Panel ──
@Composable
private fun ColumnScope.BlockPropsPanel(vm: DrawingViewModel, p: DrawingPrimitive, multi: Boolean) {
    val bp = p as DrawingPrimitive.BlockRefPrimitive
    var color by remember(p) { mutableStateOf(p.color) }
    var scale by remember(p) { mutableFloatStateOf(bp.scale) }
    var showColorPicker by remember { mutableStateOf(false) }

    if (multi) {
        Text("修改将影响所有选中图块", fontSize = 12.sp, color = Color(0xFF999999))
        Spacer(Modifier.height(10.dp))
    }

    // 颜色
    PropSectionTitle("颜色")
    PropColorRow(color) { showColorPicker = !showColorPicker }
    if (showColorPicker) {
        Spacer(Modifier.height(6.dp))
        ColorPickerBar { c ->
            color = c; vm.updateSelectedColor(c); showColorPicker = false
        }
    }
    Spacer(Modifier.height(14.dp))

    // 缩放
    // 块 scale 可以很大（无业务上限），0.1×~500× 跨 5000 倍：线性滑块会把 1× 挤在最左边
    // 二十几个像素里，这里用对数映射，1× 落在滑块中部
    val scaleMin = 0.1f
    val scaleMax = 500f
    val lnMin = kotlin.math.ln(scaleMin)
    val lnRange = kotlin.math.ln(scaleMax) - lnMin
    PropSectionTitle("缩放")
    Row(verticalAlignment = Alignment.CenterVertically) {
        CompactSlider(
            value = ((kotlin.math.ln(scale.coerceIn(scaleMin, scaleMax)) - lnMin) / lnRange),
            onValueChange = { t ->
                val s = kotlin.math.exp(lnMin + t * lnRange)
                scale = s; vm.updateSelectedBlockScale(s)
            },
            valueRange = 0f..1f,
            modifier = Modifier.weight(1f).height(28.dp),
            thumbSize = 12.dp, trackHeight = 6.dp,
            trackColor = Color(0xFFDCDCDC), activeColor = Color(0xFF1565C0)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            (if (scale < 10f) String.format(java.util.Locale.US, "%.2f", scale)
             else String.format(java.util.Locale.US, "%.0f", scale)) + "×",
            fontSize = 13.sp,
            color = Color(0xFF333333), modifier = Modifier.width(52.dp), textAlign = TextAlign.End
        )
    }
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

    var hasChanges = vm.blockEditorPrimitives.isNotEmpty()
    val blockBackdrop = rememberLayerBackdrop()

    BackHandler {
        if (hasChanges) exitConfirmDlg = true
        else { vm.blockEditorCancelPrimitive(); vm.cancelBlockDraft(); onBack() }
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().glassBackdropBlur().onSizeChanged {
            canvasSz = it
            // DXF 导入到块等场景：进入时按内容适配一次视图
            if (vm.blockEditorFitPending && it.width > 0 && it.height > 0) {
                vm.blockEditorFitPending = false
                vm.fitBlockEditorViewToContent(it.width.toFloat(), it.height.toFloat())
            }
        }) {
            GestureHost(
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
                onCanvasTransform = { z, c, p -> vm.transformBlockEditorCanvas(z, c, p) },
                onConfirm = { vm.blockEditorConfirmPendingEdit() },
                onCancel = { vm.blockEditorCancelPrimitive() },
                onUpdateOffset = { dx, dy -> vm.blockEditorUpdatePendingOffset(dx, dy) },
                onUpdateRotation = { r -> vm.blockEditorUpdatePendingRotation(r) },
                onUpdateScale = { sx, sy -> vm.blockEditorUpdatePendingScale(sx, sy) },
                onUpdatePrimitive = { p -> vm.blockEditorUpdatePendingPrimitive(p) },
                onUpdateArrowSpan = { vm.updatePendingArrowSpan(it) },
                onToggleRangeReversed = { vm.toggleRangeReversed() },
                canvasBackdrop = blockBackdrop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .glassSurface(
                    backdrop = blockBackdrop,
                    shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
                    fallback = Color(0xFFF7FFFFFF)
                )
                .statusBarsPadding()
                .height(50.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (hasChanges) exitConfirmDlg = true
                else { vm.blockEditorCancelPrimitive(); vm.cancelBlockDraft(); onBack() }
            }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.ArrowBack, "返回", Modifier.size(20.dp), tint = Color(0xFF2A2A2A))
            }
            Text("编辑图块", fontSize = 16.sp, fontWeight = FontWeight(500), color = Color(0xFF2A2A2A))
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { vm.blockEditorUndo() }, enabled = vm.canBlockEditorUndo, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Undo, "撤销", Modifier.size(20.dp),
                    tint = if (vm.canBlockEditorUndo) Color(0xFF2A2A2A) else Color(0xFF9E9E9E))
            }
            IconButton(onClick = { vm.blockEditorRedo() }, enabled = vm.canBlockEditorRedo, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Redo, "重做", Modifier.size(20.dp),
                    tint = if (vm.canBlockEditorRedo) Color(0xFF2A2A2A) else Color(0xFF9E9E9E))
            }
            IconButton(onClick = {
                val editId = vm.editingBlockId
                if (editId != null) {
                    val bd = vm.blockDefs.find { it.id == editId }
                    val name = bd?.name ?: "图块"
                    vm.saveBlockEditorBlock(name)
                    onSave(name)
                } else {
                    saveDlg = true
                }
            }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Save, "保存", Modifier.size(20.dp), tint = Color(0xFF2A2A2A))
            }
            TextButton(
                onClick = {
                    if (hasChanges) exitConfirmDlg = true
                    else { vm.blockEditorCancelPrimitive(); vm.cancelBlockDraft(); onBack() }
                },
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFC62828))
            ) { Text("放弃", fontSize = 13.sp) }
        }
        BottomToolbar(
            viewModel = vm,
            onShowNumberDialog = {},
            onShowTextDialog = {},
            onShowRangeDialog = {},
            backdrop = blockBackdrop,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
        // 下拉菜单浮层（同窗真玻璃）：块编辑器独立部署，采样 blockBackdrop
        GlassMenuLayer(backdrop = blockBackdrop)
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
