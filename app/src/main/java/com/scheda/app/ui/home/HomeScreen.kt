package com.scheda.app.ui.home
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.scheda.app.ui.components.GlassOverlayEffect
import com.scheda.app.ui.components.glassSurface
import com.scheda.app.ui.components.glassBackdropBlur
import com.scheda.app.file.RecycleBinManager
import com.scheda.app.viewmodel.HomeViewModel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 首页 — 文件夹树浏览（面包屑 + 列表）+ 回收站。统一走 HomeViewModel。
 *
 * 当 vm.showRecycle 为 true 时显示回收站视图。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onOpen: (file: File, name: String) -> Unit,
    onNew: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sc = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val listBackdrop = rememberLayerBackdrop()

    // ─── 回收站视图 ─────────────────────────────
    if (vm.showRecycle) {
        RecycleBinView(vm)
        return
    }

    // ─── 普通文件浏览 ────────────────────────────
    BackHandler {
        if (vm.share) vm.exitShare()
        else if (vm.crumbs.size > 1) vm.up()
    }

    LaunchedEffect(vm.err) { vm.err?.let { sc.launch { snack.showSnackbar(it) }; vm.clearErr() } }

    var del by remember { mutableStateOf<HomeViewModel.Item?>(null) }
    var batchDelConfirm by remember { mutableStateOf(false) }
    var showMoveDlg by remember { mutableStateOf(false) }
    var moveTargets by remember { mutableStateOf<List<File>>(emptyList()) }

    Scaffold(modifier = modifier, snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .glassSurface(
                        backdrop = listBackdrop,
                        shape = CircleShape,
                        tint = Color(0x59FFFFFF),
                        fallback = Color(0xF2FFFFFF)
                    )
                    .border(1.dp, Color(0x80FFFFFF), CircleShape)
                    .clickable(onClick = onNew),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.NoteAdd, "新建", tint = Color(0xFF1976D2), modifier = Modifier.size(26.dp))
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(bottom = pad.calculateBottomPadding())) {
        Column(Modifier.fillMaxSize()
            .layerBackdrop(listBackdrop)
            .glassBackdropBlur()
            .background(Brush.verticalGradient(listOf(Color(0xFFF7F8FA), Color(0xFFEDEFF3))))) {
            // 列表
            AnimatedContent(
                targetState = vm.crumbs.size,
                transitionSpec = {
                    if (targetState > initialState) {
                        // 进入子文件夹：新内容从右滑入，旧内容向左滑出
                        (slideInHorizontally { it } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it } + fadeOut())
                    } else {
                        // 返回上级：新内容从左滑入，旧内容向右滑出
                        (slideInHorizontally { -it } + fadeIn()) togetherWith
                        (slideOutHorizontally { it } + fadeOut())
                    }
                },
                label = "folderList"
            ) {
                if (vm.items.isEmpty() && !vm.loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(64.dp), tint = Color(0xFFBBBBBB))
                            Spacer(Modifier.height(12.dp))
                            Text("空目录", fontSize = 14.sp, color = Color(0xFF999999))
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp,
                        bottom = 4.dp)) {
                        items(vm.items, key = { it.file.absolutePath }) { item ->
                            ItemRow(item, vm,
                                onClick = {
                                    if (vm.share) vm.toggle(item)
                                    else if (item.isDir) vm.enter(item.file)
                                    else onOpen(item.file, item.name)
                                },
                                onLongClick = { if (!vm.share) { vm.enterShare(); vm.toggle(item) } },
                                onDel = { del = item })
                        }
                    }
                }
            }
        }
            // 玻璃顶栏浮层：面包屑并入（Scheda 标题已删）；必须在捕获层之外
            if (vm.share) ShareBar(vm, backdrop = listBackdrop, onDeleteClick = { batchDelConfirm = true }, onMoveClick = { showMoveDlg = true })
            else HomeTopBar(vm, backdrop = listBackdrop, onRecycle = { vm.openRecycleBin() }, onShare = { vm.enterShare() })
        }
    }

    del?.let {
        Dialog(onDismissRequest = { del = null }) {
            GlassOverlayEffect()
            Surface(shape = RoundedCornerShape(16.dp), color = Color(0xE0FFFFFF), modifier = Modifier.width(280.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("删除「${it.name}」？", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2A2A2A))
                    Spacer(Modifier.height(8.dp))
                    Text("文件将移入回收站，可还原", fontSize = 14.sp, color = Color(0xFF666666))
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { del = null },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF666666))) { Text("取消") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { vm.delete(it); del = null },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFD32F2F))) { Text("删除") }
                    }
                }
            }
        }
    }

    if (batchDelConfirm) {
        Dialog(onDismissRequest = { batchDelConfirm = false }) {
            GlassOverlayEffect()
            Surface(shape = RoundedCornerShape(16.dp), color = Color(0xE0FFFFFF), modifier = Modifier.width(280.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("批量删除", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2A2A2A))
                    Spacer(Modifier.height(8.dp))
                    Text("确认删除 ${vm.sel.size} 项？\n文件将移入回收站，可还原", fontSize = 14.sp, color = Color(0xFF666666))
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { batchDelConfirm = false },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF666666))) { Text("取消") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            vm.deleteSel()
                            batchDelConfirm = false
                        }, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFD32F2F))) { Text("删除") }
                    }
                }
            }
        }
    }

    if (showMoveDlg) {
        val root = vm.getRoot()
        var moveDir by remember { mutableStateOf(vm.curDir ?: root ?: File("/")) }

        // Build breadcrumb from path
        val moveCrumbs = remember(moveDir, root) {
            val crumbs = mutableListOf<File>()
            var d: File? = moveDir
            while (d != null) {
                crumbs.add(d)
                d = if (root != null && d == root) null else d.parentFile
            }
            val result = crumbs.reversed()
            if (result.isEmpty()) listOf(moveDir) else result
        }

        val subDirs = moveDir.listFiles()
            ?.filter { it.isDirectory && it.name != "blocks" && it.name != ".scheda_recycle" }
            ?.sortedBy { it.name } ?: emptyList()
        val canGoUp = root != null && moveDir != root

        AlertDialog(
            onDismissRequest = { showMoveDlg = false },
            title = { Text("移动到… 已选 ${vm.sel.size} 项") },
            text = {
                Column {
                    // Breadcrumb
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        moveCrumbs.forEachIndexed { i, dir ->
                            if (i > 0) Text("/", fontSize = 13.sp, color = Color(0xFF666666))
                            TextButton(onClick = { moveDir = dir },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = if (dir == moveDir) Color(0xFF1976D2) else Color(0xFF666666))) {
                                Text(dir.name ?: "根目录", fontSize = 12.sp,
                                    fontWeight = if (dir == moveDir) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                    HorizontalDivider()

                    // Folder list
                    if (subDirs.isEmpty() && !canGoUp) {
                        Text("无可转移的文件夹", fontSize = 13.sp, color = Color(0xFF777777),
                            modifier = Modifier.padding(vertical = 24.dp).align(Alignment.CenterHorizontally))
                    } else {
                        Column(modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                            if (canGoUp) {
                                TextButton(onClick = { moveDir = moveDir.parentFile ?: return@TextButton },
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
                                    Icon(Icons.Outlined.ArrowUpward, null, Modifier.size(18.dp), tint = Color(0xFF666666))
                                    Spacer(Modifier.width(6.dp))
                                    Text(".. 上级目录", fontSize = 14.sp, color = Color(0xFF666666))
                                }
                            }
                            subDirs.forEach { dir ->
                                TextButton(onClick = { moveDir = dir },
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
                                    Icon(Icons.Outlined.Folder, null, Modifier.size(20.dp), tint = Color(0xFFFF9800))
                                    Spacer(Modifier.width(8.dp))
                                    Text(dir.name, fontSize = 14.sp)
                                    Spacer(Modifier.weight(1f))
                                    Text("›", fontSize = 14.sp, color = Color(0xFF666666))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (vm.moveItems(moveDir)) showMoveDlg = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                    ) { Text("移动到这里", color = Color.White) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showMoveDlg = false }) { Text("取消") } }
        )
    }
}

// ─── 回收站视图 ──────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun RecycleBinView(vm: HomeViewModel) {
    val snack = remember { SnackbarHostState() }
    val sc = rememberCoroutineScope()
    var showEmptyConfirm by remember { mutableStateOf(false) }
    var delItem by remember { mutableStateOf<RecycleBinManager.RecycleItem?>(null) }

    BackHandler { vm.closeRecycleBin() }

    LaunchedEffect(vm.err) { vm.err?.let { sc.launch { snack.showSnackbar(it) }; vm.clearErr() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("回收站", fontWeight = FontWeight(500), color = Color(0xFF2A2A2A), fontSize = 16.sp)
                        if (vm.recycleCount > 0) {
                            Text("${vm.recycleCount} 项（超过 100 项自动删除最旧的）",
                                fontSize = 11.sp, color = Color(0xFF777777))
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { vm.closeRecycleBin() }) {
                        Icon(Icons.Outlined.ArrowBack, "返回", tint = Color(0xFF2A2A2A))
                    }
                },
                actions = {
                    if (vm.recycleCount > 0) {
                        IconButton(onClick = { showEmptyConfirm = true }) {
                            Icon(Icons.Outlined.DeleteSweep, "清空回收站", tint = Color(0xFFD32F2F))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xE0FFFFFF),
                    titleContentColor = Color(0xFF2A2A2A),
                    navigationIconContentColor = Color(0xFF2A2A2A),
                    actionIconContentColor = Color(0xFF2A2A2A)
                )
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).background(Brush.verticalGradient(listOf(Color(0xFFF7F8FA), Color(0xFFEDEFF3))))) {
            if (vm.recycleItems.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.DeleteSweep, null, Modifier.size(64.dp), tint = Color(0xFFBBBBBB))
                        Spacer(Modifier.height(12.dp))
                        Text("回收站是空的", fontSize = 14.sp, color = Color(0xFF999999))
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(vm.recycleItems, key = { it.storedName }) { item ->
                        RecycleRow(item, vm,
                            onRestore = { vm.restoreFromRecycle(item) },
                            onDelete = { delItem = item })
                    }
                }
            }
        }
    }

    // 确认清空回收站
    if (showEmptyConfirm) {
        Dialog(onDismissRequest = { showEmptyConfirm = false }) {
            GlassOverlayEffect()
            Surface(shape = RoundedCornerShape(16.dp), color = Color(0xE0FFFFFF), modifier = Modifier.width(280.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("清空回收站？", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2A2A2A))
                    Spacer(Modifier.height(8.dp))
                    Text("所有 ${vm.recycleCount} 项将被永久删除，不可恢复", fontSize = 14.sp, color = Color(0xFF666666))
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showEmptyConfirm = false },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF666666))) { Text("取消") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            vm.emptyRecycleBin()
                            showEmptyConfirm = false
                        }, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFD32F2F))) { Text("清空") }
                    }
                }
            }
        }
    }

    // 确认单条永久删除
    delItem?.let { item ->
        Dialog(onDismissRequest = { delItem = null }) {
            GlassOverlayEffect()
            Surface(shape = RoundedCornerShape(16.dp), color = Color(0xE0FFFFFF), modifier = Modifier.width(280.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("永久删除「${item.name}」？", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2A2A2A))
                    Spacer(Modifier.height(8.dp))
                    Text("不可恢复", fontSize = 14.sp, color = Color(0xFF666666))
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { delItem = null },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF666666))) { Text("取消") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            vm.deleteFromRecycle(item)
                            delItem = null
                        }, colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFD32F2F))) { Text("永久删除") }
                    }
                }
            }
        }
    }
}

// ─── 回收站条目行 ──────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecycleRow(
    item: RecycleBinManager.RecycleItem,
    vm: HomeViewModel,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }
    val deletedAtStr = remember(item.deletedAt) { dateFmt.format(Date(item.deletedAt)) }
    // 提取原始路径所在的父目录名用于显示
    val parentDir = remember(item.originalPath) {
        val idx = item.originalPath.lastIndexOf('/')
        if (idx > 0) item.originalPath.substring(0, idx) else ""
    }

    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp)
        .clip(RoundedCornerShape(14.dp)).background(Color(0xF2FFFFFF))
        .border(1.dp, Color(0x0F000000), RoundedCornerShape(14.dp))) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (item.isDir) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile,
                null,
                Modifier.size(36.dp),
                tint = if (item.isDir) Color(0xFFFF9800) else Color(0xFF1976D2)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, fontSize = 15.sp, fontWeight = FontWeight(500),
                    color = Color(0xFF333333), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row {
                    Text("$deletedAtStr", fontSize = 11.sp, color = Color(0xFF999999))
                    if (parentDir.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Text("来自 $parentDir", fontSize = 11.sp, color = Color(0xFFBBBBBB),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            IconButton(onClick = onRestore, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.RestoreFromTrash, "还原", Modifier.size(18.dp), tint = Color(0xFF1976D2))
            }
            Spacer(Modifier.width(2.dp))
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Delete, "永久删除", Modifier.size(18.dp), tint = Color(0xFFCCCCCC))
            }
        }
    }
}

// ─── 玻璃顶栏（面包屑并入，无 Scheda 标题） ────────────

/** 顶栏玻璃容器：浮层形态（必须在捕获层之外），采样列表层。 */
@Composable
private fun BoxScope.GlassBarContainer(backdrop: Backdrop?, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.align(Alignment.TopCenter).statusBarsPadding()
            .padding(start = 8.dp, end = 8.dp, top = 6.dp)
            .fillMaxWidth().height(48.dp)
            .glassSurface(backdrop = backdrop, shape = RoundedCornerShape(16.dp),
                tint = Color(0x59FFFFFF), fallback = Color(0xF2FFFFFF))
            .border(1.dp, Color(0x80FFFFFF), RoundedCornerShape(16.dp)),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
private fun BoxScope.HomeTopBar(
    vm: HomeViewModel,
    backdrop: Backdrop?,
    onRecycle: () -> Unit,
    onShare: () -> Unit
) {
    GlassBarContainer(backdrop) {
        val sc = rememberScrollState()
        LaunchedEffect(vm.crumbs.size) { sc.scrollTo(sc.maxValue) }
        Row(Modifier.weight(1f).horizontalScroll(sc).padding(start = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            vm.crumbs.forEachIndexed { i, c ->
                if (i > 0) Text("›", fontSize = 13.sp, color = Color(0xFFBBBBBB))
                Text(
                    c.name, fontSize = 13.sp,
                    fontWeight = if (i == vm.crumbs.lastIndex) FontWeight.Bold else FontWeight.Normal,
                    color = if (i == vm.crumbs.lastIndex) Color(0xFF1976D2) else Color(0xFF666666),
                    maxLines = 1,
                    modifier = Modifier.clickable { vm.goto(i) }.padding(horizontal = 4.dp, vertical = 8.dp)
                )
            }
        }
        IconButton(onClick = onRecycle, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Outlined.DeleteSweep, "回收站", tint = Color(0xFF2A2A2A))
        }
        IconButton(onClick = onShare, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Outlined.Share, "分享", tint = Color(0xFF2A2A2A))
        }
    }
}

// ─── 分享顶栏（玻璃浮层） ─────────────────────────────

@Composable
private fun BoxScope.ShareBar(vm: HomeViewModel, backdrop: Backdrop?, onDeleteClick: () -> Unit, onMoveClick: () -> Unit) {
    GlassBarContainer(backdrop) {
        IconButton(onClick = { vm.exitShare() }, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Outlined.Close, null, tint = Color(0xFF2A2A2A))
        }
        Text(
            if (vm.hasSel) "已选 ${vm.sel.size} 项" else "选择文件",
            fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFF2A2A2A),
            modifier = Modifier.weight(1f).padding(start = 4.dp)
        )
        IconButton(onClick = onDeleteClick, enabled = vm.hasSel, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Outlined.Delete, "删除", tint = if (vm.hasSel) Color(0xFF2A2A2A) else Color(0xFF999999))
        }
        IconButton(onClick = onMoveClick, enabled = vm.hasSel, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Outlined.DriveFileMove, "移动", tint = if (vm.hasSel) Color(0xFF2A2A2A) else Color(0xFF999999))
        }
        IconButton(onClick = { vm.doShare() }, enabled = vm.hasSel, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Outlined.Share, "分享", tint = if (vm.hasSel) Color(0xFF2A2A2A) else Color(0xFF999999))
        }
    }
}

// ─── 条目行 ──────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemRow(item: HomeViewModel.Item, vm: HomeViewModel, onClick: () -> Unit, onLongClick: () -> Unit, onDel: () -> Unit) {
    val bg = if (vm.isSel(item)) Color(0x2A1976D2) else Color(0xF2FFFFFF)
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp)
        .clip(RoundedCornerShape(14.dp)).background(bg)
        .border(1.dp, Color(0x0F000000), RoundedCornerShape(14.dp))
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (item.isDir) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile, null,
                Modifier.size(36.dp), tint = if (item.isDir) Color(0xFFFF9800) else Color(0xFF1976D2))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, fontSize = 15.sp, fontWeight = FontWeight(500), color = Color(0xFF333333), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (item.isDir) "文件夹" else "图纸 · ${when { item.size < 1024 -> "${item.size}B"; item.size < 1048576 -> "${item.size / 1024}KB"; else -> "%.1fMB".format(item.size.toDouble() / 1048576) }}", fontSize = 11.sp, color = Color(0xFF999999))
            }
            IconButton(onClick = onDel, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp), tint = Color(0xFF9E9E9E)) }
        }
    }
}
