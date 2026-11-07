package com.scheda.app.ui.screens

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.scheda.app.diagnostics.CrashRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 退出诊断器（v3，2026-11-05）：
 *
 * v1/v2 只读系统 ApplicationExitInfo，对 REASON_CRASH（Java 未捕获异常）拿不到堆栈
 * ——系统 API 本身不提供 trace（官方文档：trace 仅 ANR / CRASH_NATIVE 有），
 * 弹窗里那句「读取失败（可能已被系统清理）」是错的，会误导排查方向。
 *
 * v3 改为：
 * ① 优先展示 CrashRecorder 自建的崩溃记录（完整 Java 堆栈，含版本/进程/线程/最近操作）
 * ② 系统记录只作补充：补 pid / 进程名 / status / importance / RSS；CRASH 不再尝试读 trace
 * ③ 按 (timestamp, reason, processName) 去重；同一次崩溃的「自建记录 + 系统条目」只留自建
 */
@Composable
fun ExitDiagnostics() {
    val ctx = LocalContext.current
    var entries by remember { mutableStateOf<List<ExitDiagEntry>?>(null) }
    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) { buildExitEntries(ctx) }
        if (result.isNotEmpty()) entries = result
    }
    entries?.let { list ->
        AlertDialog(
            onDismissRequest = { entries = null },
            title = { Text("上次异常记录（请截图反馈）", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    // 发送按钮固定在弹窗顶部、滚动区之外
                    val sendables = list.filter { it.crashFile != null || it.traceUri != null }
                    if (sendables.isNotEmpty()) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            sendables.forEachIndexed { i, e ->
                                val file = e.crashFile
                                val label = when {
                                    file != null && sendables.size == 1 -> "发送崩溃日志"
                                    file != null -> "发送崩溃日志 ${i + 1}"
                                    else -> "发送堆栈文件 ${i + 1}"
                                }
                                TextButton(onClick = {
                                    if (file != null) shareCrashFile(ctx, file)
                                    else e.traceUri?.let { shareUri(ctx, it) }
                                }) { Text(label, fontSize = 12.sp) }
                            }
                        }
                    }
                    Column(Modifier.height(300.dp).verticalScroll(rememberScrollState())) {
                        list.forEachIndexed { idx, e ->
                            if (idx > 0) Spacer(Modifier.height(14.dp))
                            Text(e.title, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { entries = null }) { Text("知道了") } }
        )
    }
}

private data class ExitDiagEntry(val title: String, val crashFile: File?, val traceUri: Uri?)

/**
 * 白名单：只有这些 reason 才算真异常、才弹诊断窗。
 * 其余（应用升级/用户关闭/低内存/系统冻结/未来新增的 reason）一律静默——
 * 黑名单写法会随系统新增 reason 持续误报（2026-10-26 升级 reason 16 就是实例）。
 */
private val ALERT_EXIT_REASONS = setOf(
    ApplicationExitInfo.REASON_CRASH,                  // 4  Java 层崩溃
    ApplicationExitInfo.REASON_CRASH_NATIVE,           // 5  原生崩溃（tombstone）
    ApplicationExitInfo.REASON_ANR,                    // 6  无响应
    7,                                                  // REASON_INITIALIZATION_FAILURE 初始化失败
    9,                                                  // REASON_EXCESSIVE_RESOURCE_USAGE 资源滥用被杀
)

/** 读自建崩溃记录 + 系统退出记录；每条只展示一次 */
private fun buildExitEntries(ctx: Context): List<ExitDiagEntry> {
    val out = ArrayList<ExitDiagEntry>()
    try {
        val prefs = ctx.getSharedPreferences("exit_diag", Context.MODE_PRIVATE)

        // ① App 自建崩溃记录（完整堆栈，最可靠）
        val lastCrashShown = prefs.getLong("last_shown_crash_mtime", 0L)
        var newestCrashMtime = lastCrashShown
        val newCrashFiles = CrashRecorder.list(ctx)
            .filter { it.lastModified() > lastCrashShown }
            .take(3)
        for (f in newCrashFiles) {
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            val shown = if (text.length > 2500) {
                text.take(2500) + "\n…（已截断，点下方按钮发送完整文件）"
            } else text
            out.add(ExitDiagEntry(shown.trim(), f, null))
            newestCrashMtime = maxOf(newestCrashMtime, f.lastModified())
        }

        // ② 系统退出记录（API 30+）
        var newestSysTs = 0L
        if (Build.VERSION.SDK_INT >= 30) {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val exits = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 8)
            val lastShown = prefs.getLong("last_shown_ts_v2", 0L)
            var traceBudget = 3
            val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
            val seen = HashSet<String>()
            for (e in exits) {
                if (e.timestamp <= lastShown) break   // 反向时间序，旧记录全部跳过
                newestSysTs = maxOf(newestSysTs, e.timestamp)
                if (e.reason !in ALERT_EXIT_REASONS) continue
                // 已有自建崩溃记录覆盖同一次 Java 崩溃时，跳过系统条目，避免重复展示
                if (e.reason == ApplicationExitInfo.REASON_CRASH &&
                    newCrashFiles.any { abs(it.lastModified() - e.timestamp) < 3000 }
                ) continue
                // 同一次退出被系统记了多条（同秒重复）只留第一条
                val key = "${e.timestamp}|${e.reason}|${e.processName}"
                if (!seen.add(key)) continue

                val reason = when (e.reason) {
                    ApplicationExitInfo.REASON_CRASH -> "崩溃 CRASH"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生崩溃 NATIVE"
                    ApplicationExitInfo.REASON_ANR -> "无响应 ANR"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "系统低内存被杀 LMK"
                    ApplicationExitInfo.REASON_SIGNALED -> "被信号终止 SIGNALED"
                    ApplicationExitInfo.REASON_EXIT_SELF -> "自行退出 EXIT_SELF"
                    ApplicationExitInfo.REASON_USER_REQUESTED -> "用户主动关闭"
                    ApplicationExitInfo.REASON_OTHER -> "其他 OTHER ← 重点看描述"
                    else -> "未知(${e.reason})"
                }
                val t = fmt.format(Date(e.timestamp))
                val pssMb = try { e.pss / 1024 / 1024 } catch (_: Throwable) { 0L }
                val rssMb = try { e.rss / 1024 / 1024 } catch (_: Throwable) { 0L }
                val desc = e.description ?: ""

                // 只有 ANR / 原生崩溃系统才提供 trace；Java 崩溃（CRASH）永远没有
                val hasSysTrace = e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                    e.reason == ApplicationExitInfo.REASON_ANR
                var traceUri: Uri? = null
                if (hasSysTrace) {
                    val uriKey = "trace_uri_${e.timestamp}_${e.reason}"
                    val saved = prefs.getString(uriKey, null)
                    if (saved != null) {
                        traceUri = Uri.parse(saved)
                    } else if (traceBudget > 0) {
                        traceUri = exportTrace(ctx, e)
                        if (traceUri != null) {
                            traceBudget--
                            prefs.edit().putString(uriKey, traceUri.toString()).apply()
                        }
                    }
                }
                val note = when {
                    traceUri != null -> "\n堆栈文件已导出到「下载」目录 → 点下方按钮发送"
                    e.reason == ApplicationExitInfo.REASON_CRASH ->
                        "\n系统不保存 Java 崩溃堆栈（需 App 自建记录，见上方条目）"
                    hasSysTrace -> "\n堆栈: 读取失败（系统未保留或已被覆盖）"
                    else -> ""
                }
                out.add(
                    ExitDiagEntry(
                        "[$t] $reason\n" +
                            "进程: ${e.processName} (pid ${e.pid})  状态: ${e.status}  重要性: ${e.importance}\n" +
                            "描述: ${desc.ifBlank { "（无）" }}\n" +
                            "内存: PSS ${pssMb} MB / RSS ${rssMb} MB$note",
                        null,
                        traceUri
                    )
                )
            }
        }

        if (out.isNotEmpty()) {
            val ed = prefs.edit()
            if (newestCrashMtime > lastCrashShown) ed.putLong("last_shown_crash_mtime", newestCrashMtime)
            if (newestSysTs > 0) ed.putLong("last_shown_ts_v2", newestSysTs)
            ed.apply()
        }
    } catch (_: Throwable) {
        // 诊断失败静默
    }
    return out
}

/** 把系统保存的崩溃 trace 导出为文件到「下载」目录（MediaStore，免权限），返回可分享的 Uri */
@RequiresApi(Build.VERSION_CODES.Q)
private fun exportTrace(ctx: Context, e: ApplicationExitInfo): Uri? {
    return try {
        val bytes = e.traceInputStream?.use { it.readBytes() } ?: return null
        if (bytes.isEmpty() || bytes.size > 8 * 1024 * 1024) return null
        val name = "scheda_crash_" + SimpleDateFormat("MMdd_HHmmss", Locale.US).format(Date(e.timestamp)) + ".bin"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
        uri
    } catch (_: Throwable) {
        null
    }
}

/** 发送自建崩溃日志（复制到 cache/shares 后走 FileProvider） */
private fun shareCrashFile(ctx: Context, f: File) {
    try {
        val shareDir = File(ctx.cacheDir, "shares").apply { mkdirs() }
        val dst = File(shareDir, f.name)
        f.copyTo(dst, overwrite = true)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", dst)
        shareUri(ctx, uri)
    } catch (_: Throwable) {
        // 分享失败静默
    }
}

/** 通过系统分享面板发送文件 */
private fun shareUri(ctx: Context, uri: Uri) {
    try {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "发送崩溃日志"))
    } catch (_: Throwable) {
        // 分享失败静默（可改从「下载」目录手动发送）
    }
}
