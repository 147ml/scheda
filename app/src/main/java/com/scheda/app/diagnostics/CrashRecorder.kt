package com.scheda.app.diagnostics

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * 崩溃记录器（2026-11-05）：
 *
 * 背景：ApplicationExitInfo 对 REASON_CRASH（Java 未捕获异常）**不提供 trace**，
 * 系统只留一个 "crash" 描述、pss 常常是 0，靠它没法定位崩溃。这是 API 的硬限制，
 * 不是记录被系统清理了。
 *
 * 做法：自己接管 UncaughtExceptionHandler，把完整堆栈同步写到
 * filesDir/crashes/crash_<时间>.txt，下次启动由 ExitDiagnostics 读取展示。
 * 写完仍然转交系统默认处理器 —— 系统弹「应用已停止」和 dropbox 记录行为不变。
 *
 * 安装时机：由 CrashInitProvider（ContentProvider）在 Application.onCreate 之前安装，
 * 这样连 Application 初始化阶段的崩溃也能抓到。
 */
object CrashRecorder {
    private const val DIR_NAME = "crashes"
    private const val MAX_FILES = 10

    @Volatile
    private var appCtx: Context? = null

    /** 最近一次用户操作，随崩溃一起落盘（没有就留空） */
    @Volatile
    private var lastAction: String = ""

    fun note(action: String) {
        lastAction = action
    }

    fun dir(ctx: Context): File = File(ctx.filesDir, DIR_NAME)

    /** 按时间倒序（最新在前） */
    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.isFile && f.name.startsWith("crash_") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        if (appCtx != null) return
        appCtx = app
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(app, thread, throwable)
            } catch (_: Throwable) {
                // 记录失败不能掩盖原崩溃
            }
            if (prev != null) {
                prev.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    private fun writeCrash(ctx: Context, thread: Thread, t: Throwable) {
        val now = Date()
        val headFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val fileFmt = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val body = buildString {
            appendLine("时间: ${headFmt.format(now)}")
            appendLine("版本: ${appVersion(ctx)}")
            appendLine("进程: ${ctx.packageName} (pid ${Process.myPid()})")
            appendLine("线程: ${thread.name}")
            appendLine(
                "设备: ${Build.MANUFACTURER} ${Build.MODEL} / Android " +
                    "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            )
            if (lastAction.isNotBlank()) appendLine("最近操作: $lastAction")
            appendLine("---")
            append(sw.toString())
        }
        val d = dir(ctx)
        if (!d.exists()) d.mkdirs()
        val f = File(d, "crash_${fileFmt.format(now)}.txt")
        // 同步落盘：崩溃后进程随时会被系统收走，必须当场写完
        FileOutputStream(f).use { os ->
            os.write(body.toByteArray(Charsets.UTF_8))
            os.flush()
            os.fd.sync()
        }
        list(ctx).drop(MAX_FILES).forEach { runCatching { it.delete() } }
    }

    private fun appVersion(ctx: Context): String = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        "${pi.versionName} ($code)"
    } catch (_: Throwable) {
        "?"
    }
}
