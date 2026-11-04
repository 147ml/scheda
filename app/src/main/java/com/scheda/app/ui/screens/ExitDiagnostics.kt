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
import androidx.compose.foundation.layout.Column
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 退出诊断器：
 * 启动时读取系统记录的历史退出原因，只有崩溃 / ANR 等异常退出才弹窗；
 * 对异常记录读取完整堆栈（trace），导出为文件到「下载」目录，弹窗内提供发送按钮。
 * 系统只保留最近若干条记录，导出后不依赖复现。
 */
@Composable
fun ExitDiagnostics() {
    if (Build.VERSION.SDK_INT < 30) return
    val ctx = LocalContext.current
    var entries by remember { mutableStateOf<List<ExitDiagEntry>?>(null) }
    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) { buildExitEntries(ctx) }
        if (result.isNotEmpty()) entries = result
    }
    entries?.let { list ->
        AlertDialog(
            onDismissRequest = { entries = null },
            title = { Text("上次退出诊断", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.height(320.dp).verticalScroll(rememberScrollState())) {
                    list.forEach { e ->
                        Text(e.title, fontSize = 12.sp)
                        if (e.traceUri != null) {
                            TextButton(onClick = { shareTrace(ctx, e.traceUri) }) {
                                Text("发送堆栈文件", fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { entries = null }) { Text("知道了") } }
        )
    }
}

private data class ExitDiagEntry(val title: String, val traceUri: Uri?)

/**
 * 白名单：只有这些 reason 才算真异常、才弹诊断窗。
 * 其余（应用升级/用户关闭/低内存/系统冻结/未来新增的 reason）一律静默——
 * 黑名单写法会随系统新增 reason 持续误报。
 */
private val ALERT_EXIT_REASONS = setOf(
    ApplicationExitInfo.REASON_CRASH,                  // 4  Java 层崩溃
    ApplicationExitInfo.REASON_CRASH_NATIVE,           // 5  原生崩溃（tombstone）
    ApplicationExitInfo.REASON_ANR,                    // 6  无响应
    7,                                                  // REASON_INITIALIZATION_FAILURE 初始化失败
    9,                                                  // REASON_EXCESSIVE_RESOURCE_USAGE 资源滥用被杀
)

/** 读取历史退出记录；对崩溃/ANR 读取堆栈并导出为文件（每次启动最多新导出 3 条） */
private fun buildExitEntries(ctx: Context): List<ExitDiagEntry> {
    val out = ArrayList<ExitDiagEntry>()
    try {
        if (Build.VERSION.SDK_INT < 30) return out
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val exits = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 8)
        val prefs = ctx.getSharedPreferences("exit_diag", Context.MODE_PRIVATE)
        val lastShown = prefs.getLong("last_shown_ts_v2", 0L)
        var traceBudget = 3
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        for (e in exits) {
            if (e.timestamp <= lastShown) break   // 反向时间序，旧记录全部跳过
            // 白名单：只有确定是异常类才弹诊断窗，
            // 其余一律静默——应用升级(16)/用户关闭/低内存/系统冻结/未来新增 reason 都不打扰。
            val desc = e.description ?: ""
            if (e.reason !in ALERT_EXIT_REASONS) continue
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
            val wantTrace = e.reason == ApplicationExitInfo.REASON_CRASH ||
                e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                e.reason == ApplicationExitInfo.REASON_ANR
            var traceUri: Uri? = null
            var note = ""
            if (wantTrace) {
                val uriKey = "trace_uri_${e.timestamp}"
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
                note = if (traceUri != null)
                    "\n堆栈文件已导出到「下载」目录 → 点下方按钮发送"
                else
                    "\n堆栈: 读取失败（可能已被系统清理）"
            }
            out.add(
                ExitDiagEntry(
                    "[$t] $reason\n描述: ${desc.ifBlank { "（无）" }}\n退出时内存: ${pssMb} MB$note",
                    traceUri
                )
            )
        }
        if (out.isNotEmpty()) {
            prefs.edit().putLong("last_shown_ts_v2", exits.firstOrNull()?.timestamp ?: 0L).apply()
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

/** 通过系统分享面板发送堆栈文件 */
private fun shareTrace(ctx: Context, uri: Uri) {
    try {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "发送崩溃堆栈文件"))
    } catch (_: Throwable) {
        // 分享失败静默（可改从「下载」目录手动发送）
    }
}
