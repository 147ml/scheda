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
import com.scheda.app.viewmodel.HomeViewModel
import kotlinx.coroutines.launch
import java.io.File

/**
 * 首页 — 文件夹树浏览（面包屑 + 列表），统一走 HomeViewModel。
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
        topBar = {
            if (vm.share) ShareBar(vm, onDeleteClick = { batchDelConfirm = true }, onMoveClick = { showMoveDlg = true })
            else TopAppBar(
                title = { Text("Scheda", fontWeight = FontWeight(500), color = Color.White) },
                actions = {
                    IconButton(onClick = { vm.enterShare() }) { Icon(Icons.Outlined.Share, "分享", tint = Color.White) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF2A2A2A), titleContentColor = Color.White, actionIconContentColor = Color.White)
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNew, containerColor = Color(0xFF1976D2)) {
                Icon(Icons.Outlined.NoteAdd, "新建", tint = Color.White)
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).background(Color.White)) {
            // 面包屑
            BreadcrumbBar(vm)
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
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
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
    }

    del?.let { 
        Dialog(onDismissRequest = { del = null }) {
            Surface(shape = RoundedCornerShape(16.dp), color = Color(0xCC222222), modifier = Modifier.width(280.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("删除「${it.name}」？", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Text("不可撤销", fontSize = 14.sp, color = Color(0xFFCCCCCC))
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { del = null },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFCCCCCC))) { Text("取消") }
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
            Surface(shape = RoundedCornerShape(16.dp), color = Color(0xCC222222), modifier = Modifier.width(280.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("批量删除", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Text("确认删除 ${vm.sel.size} 个文件？\\n不可撤销", fontSize = 14.sp, color = Color(0xFFCCCCCC))
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { batchDelConfirm = false },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFCCCCCC))) { Text("取消") }
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
            ?.filter { it.isDirectory && it.name != "blocks" }
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
                            if (i > 0) Text("/", fontSize = 13.sp, color = Color(0xFFCCCCCC))
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
                        Text("无可转移的文件夹", fontSize = 13.sp, color = Color(0xFF999999),
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
                                    Text("›", fontSize = 14.sp, color = Color(0xFFCCCCCC))
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

// ─── 面包屑 ──────────────────────────────────────────

@Composable
private fun BreadcrumbBar(vm: HomeViewModel) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        vm.crumbs.forEachIndexed { i, c ->
            if (i > 0) Text("›", fontSize = 14.sp, color = Color(0xFFCCCCCC))
            TextButton(onClick = { vm.goto(i) },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                modifier = Modifier.height(36.dp).padding(horizontal = 1.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = if (i == vm.crumbs.lastIndex) Color(0xFF1976D2) else Color(0xFF666666))) {
                Text(c.name, fontSize = 13.sp, fontWeight = if (i == vm.crumbs.lastIndex) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
    HorizontalDivider()
}

// ─── 分享顶栏 ────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareBar(vm: HomeViewModel, onDeleteClick: () -> Unit, onMoveClick: () -> Unit) {
    TopAppBar(
        title = { Text(if (vm.hasSel) "已选 ${vm.sel.size} 项" else "选择文件", color = Color.White) },
        navigationIcon = { IconButton(onClick = { vm.exitShare() }) { Icon(Icons.Outlined.Close, null, tint = Color.White) } },
        actions = {
            IconButton(onClick = onDeleteClick, enabled = vm.hasSel) {
                Icon(Icons.Outlined.Delete, "删除", tint = if (vm.hasSel) Color.White else Color(0xFFBBBBBB))
            }
            IconButton(onClick = onMoveClick, enabled = vm.hasSel) {
                Icon(Icons.Outlined.DriveFileMove, "移动", tint = if (vm.hasSel) Color.White else Color(0xFFBBBBBB))
            }
            IconButton(onClick = { vm.doShare() }, enabled = vm.hasSel) {
                Icon(Icons.Outlined.Share, "分享", tint = if (vm.hasSel) Color.White else Color(0xFFBBBBBB))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF2A2A2A), titleContentColor = Color.White, navigationIconContentColor = Color.White, actionIconContentColor = Color.White))
}

// ─── 条目行 ──────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemRow(item: HomeViewModel.Item, vm: HomeViewModel, onClick: () -> Unit, onLongClick: () -> Unit, onDel: () -> Unit) {
    val bg = if (vm.isSel(item)) Color(0x1A1976D2) else Color.Transparent
    Surface(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick), color = bg) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (vm.share) { Checkbox(vm.isSel(item), { vm.toggle(item) }, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(6.dp)) }
            Icon(if (item.isDir) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile, null,
                Modifier.size(36.dp), tint = if (item.isDir) Color(0xFFFF9800) else Color(0xFF1976D2))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, fontSize = 15.sp, fontWeight = FontWeight(500), color = Color(0xFF333333), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (item.isDir) "文件夹" else "图纸 · ${when { item.size < 1024 -> "${item.size}B"; item.size < 1048576 -> "${item.size / 1024}KB"; else -> "%.1fMB".format(item.size.toDouble() / 1048576) }}", fontSize = 11.sp, color = Color(0xFF999999))
            }
            IconButton(onClick = onDel, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp), tint = Color(0xFFCCCCCC)) }
        }
    }
}
