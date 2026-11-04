package com.scheda.app.file

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * 后台写入登记表（2026-10-14）。
 *
 * 退出图纸时 saveNow 不再在主线程等待写盘完成（根治退出卡顿），
 * 但"构建+写盘"任务读的是 ViewModel 当前状态——必须在"切换文档/操作文件"前
 * 等它完成，否则可能把新文档的状态写进旧文件、或与删除/移动竞争。
 *
 * - 所有"针对该文件的写"都在 DrawingViewModel 的单线程保存队列上串行执行，
 *   等待"最近一次提交"的完成即等待了全部。
 * - await() 供 IO/协程上下文使用；awaitBlocking() 供无法异步化的同步文件操作使用。
 */
object PendingWrites {
    @Volatile private var future: Future<*>? = null

    /** 登记最近一次后台保存任务（saveNow 提交时调用） */
    fun track(f: Future<*>) { future = f }

    /** 等待最近一次后台保存完成（挂起；一般立即返回，正在写时最多等 15s） */
    suspend fun await() {
        val f = future ?: return
        if (f.isDone) return
        withContext(Dispatchers.IO) {
            try { f.get(15, TimeUnit.SECONDS) } catch (_: Throwable) {}
        }
    }

    /** 同步有限等待（仅供无法异步化的文件操作）：一般立即返回；极端窗口最多等 timeoutMs */
    fun awaitBlocking(timeoutMs: Long = 2000) {
        val f = future ?: return
        if (f.isDone) return
        try { f.get(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: Throwable) {}
    }
}
