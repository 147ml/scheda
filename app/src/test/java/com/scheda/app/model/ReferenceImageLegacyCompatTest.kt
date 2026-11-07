package com.scheda.app.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旧图纸兼容回归测试。
 *
 * 背景：name / visible 是 2026-11-04 新增字段。此前的 .scheda 文件里没有这两个字段，
 * 而 Gson 反序列化不走构造函数、不会应用 Kotlin 默认值——读出来 name = null、visible = false。
 * 打开老图纸恢复撤销历史时，`img.copy(data = ...)` 重建对象撞上构造函数对 name 的非空检查，
 * release 包里表现为 `Object.getClass()` 的 NullPointerException（R8 把检查压成了 getClass 调用）。
 */
class ReferenceImageLegacyCompatTest {

    private val gson = Gson()

    /** 11-04 之前的写法：没有 name / visible 字段 */
    private val legacyJson = """
        {"id":"img-1","data":"","centerX":12.0,"centerY":34.0,
         "width":100.0,"height":80.0,"rotationDeg":0.0,"alpha":1.0,
         "layerId":0,"pixelWidth":1600,"pixelHeight":1200}
    """.trimIndent()

    @Test
    fun `旧格式图片读出来缺省字段为 null 和 false`() {
        val img = gson.fromJson(legacyJson, ReferenceImage::class.java)
        assertNull(img.name)
        assertFalse(img.visible)
    }

    @Test
    fun `normalized 补正缺省字段并恢复可见`() {
        val img = gson.fromJson(legacyJson, ReferenceImage::class.java)
        val fixed = img.normalized()
        assertEquals("", fixed.name)
        assertTrue(fixed.visible)
        assertEquals("img-1", fixed.id)
    }

    @Test
    fun `补正后可以安全 copy`() {
        val img = gson.fromJson(legacyJson, ReferenceImage::class.java)
        // 补正前 copy 会抛 NPE（name 为 null）
        val copied = img.normalized().copy(data = "payload")
        assertEquals("payload", copied.data)
        assertEquals("", copied.name)
        assertTrue(copied.visible)
    }

    @Test
    fun `新格式隐藏状态不被改写`() {
        val json = """
            {"id":"img-2","data":"","centerX":0.0,"centerY":0.0,
             "width":10.0,"height":10.0,"name":"平面图","visible":false}
        """.trimIndent()
        val img = gson.fromJson(json, ReferenceImage::class.java)
        val fixed = img.normalized()
        assertEquals("平面图", fixed.name)
        assertFalse(fixed.visible)
    }
}
