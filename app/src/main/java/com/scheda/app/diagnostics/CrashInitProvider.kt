package com.scheda.app.diagnostics

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * 只为安装崩溃记录器而存在的空 ContentProvider。
 * ContentProvider.onCreate 早于 Application.onCreate 执行，
 * 放在这里才能在 Application 初始化阶段的崩溃中拿到堆栈。
 */
class CrashInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.applicationContext?.let { CrashRecorder.install(it) }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
