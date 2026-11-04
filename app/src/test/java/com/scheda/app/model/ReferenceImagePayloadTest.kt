package com.scheda.app.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 参考图片在 .scheda 文件里的存储体积回归测试。
 *
 * 背景：图纸含多张参考图时，"删除某张图片"与"从图纸返回文件管理器"两条路径
 * 都会走 buildDocument() → Gson 全量序列化。修复前同一张图的位图数据
 * 会按"撤销池里的版本数"重复写入（实测 3 个版本 ⇒ 文件放大 4.01 倍），
 * 序列化峰值随之放大数倍，直接 OOM（表现为"图片一多必崩"）。
 *
 * 契约：单张图片的位图数据在整份文件中只出现 1 次，
 * 与它在撤销历史里被调整过多少个版本无关；已被删除但撤销历史还引用着的图同样只存 1 份。
 */
class ReferenceImagePayloadTest {

    private val gson = Gson()

    /** 单张位图的 base64 长度，量级对齐"长边 ≤2048px、JPEG q85"的导入结果 */
    private val payloadChars = 60_000

    private fun tagOf(i: Int) = "IMG%03d".format(i)

    /** 构造可辨识的伪 base64：唯一标记串只出现一次，便于统计"这份位图被写了几遍" */
    private fun payload(i: Int): String {
        val tag = tagOf(i)
        return tag + "A".repeat(payloadChars - tag.length)
    }

    private fun img(id: String, data: String, centerX: Float = 0f) = ReferenceImage(
        id = id, data = data, centerX = centerX, centerY = 0f,
        width = 100f, height = 100f
    )

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var i = haystack.indexOf(needle)
        while (i >= 0) {
            count++
            i = haystack.indexOf(needle, i + 1)
        }
        return count
    }

    @Test
    fun `每张参考图的位图数据在文件中只写一份`() {
        val liveCount = 10            // 图纸里现存的图
        val deletedCount = 5          // 已删除、但撤销历史还引用着的图
        val versionsPerImage = 3      // 同一张图被反复选中调整后留在撤销池里的版本数

        val live = (0 until liveCount).map { img("live$it", payload(it)) }
        val liveIds = live.map { it.id }.toSet()

        // 模拟 packUndoHistory：池按"实例"去重，每个调整会话留下一个新实例
        val imagePool = ArrayList<ReferenceImage>()
        val imageData = HashMap<String, String>()
        for (i in 0 until liveCount) {
            for (v in 0 until versionsPerImage) {
                imagePool.add(img("live$i", payload(i)).stripDataForUndoPool(liveIds, imageData))
            }
        }
        for (i in 0 until deletedCount) {
            val idx = liveCount + i
            for (v in 0 until versionsPerImage) {
                imagePool.add(img("gone$i", payload(idx)).stripDataForUndoPool(liveIds, imageData))
            }
        }

        // 已删除的图：只剩 imageData 一份（否则撤销删除后恢复不出位图）
        assertEquals("被删除的图必须留一份位图数据用于撤销", deletedCount, imageData.size)

        val doc = SchedaDocument(
            name = "多图图纸",
            images = live,
            undoHistoryV2 = SerializableUndoHistory(
                imagePool = imagePool,
                snapshots = listOf(SerializableUndoSnapshotRef(imageRefs = imagePool.indices.toList())),
                imageData = imageData.ifEmpty { null }
            )
        )

        val json = gson.toJson(doc)
        val imageCount = liveCount + deletedCount
        val imagePayloadTotal = imageCount.toLong() * payloadChars
        val ratio = json.length.toDouble() / imagePayloadTotal
        val evidence = "JSON=${json.length} 字符，位图原始=${imagePayloadTotal} 字符，放大 ${"%.2f".format(ratio)} 倍"

        for (i in 0 until imageCount) {
            assertEquals(
                "图片 #$i 的位图数据被重复写入（$evidence）",
                1, countOccurrences(json, tagOf(i))
            )
        }
        assertTrue("文件体积被位图重复放大：$evidence", ratio < 1.2)
    }

    @Test
    fun `往返后每张图的位图都能按 id 找回`() {
        val live = (0 until 3).map { img("live$it", payload(it)) }
        val liveIds = live.map { it.id }.toSet()
        val imageData = HashMap<String, String>()
        val imagePool = arrayListOf(
            img("live0", payload(0)).stripDataForUndoPool(liveIds, imageData),
            img("gone0", payload(9)).stripDataForUndoPool(liveIds, imageData)
        )

        val doc = SchedaDocument(
            name = "往返",
            images = live,
            undoHistoryV2 = SerializableUndoHistory(
                imagePool = imagePool,
                imageData = imageData.ifEmpty { null }
            )
        )
        val back = gson.fromJson(gson.toJson(doc), SchedaDocument::class.java)
        val pool = back.undoHistoryV2!!.imagePool
        val liveData = back.images!!.associate { it.id to it.data }

        // 与 DrawingViewModel.unpackUndoHistory 的回填规则一致
        fun resolve(i: ReferenceImage): String =
            i.data.ifEmpty { back.undoHistoryV2!!.imageData?.get(i.id) ?: liveData[i.id] ?: "" }

        assertEquals("现存图应从文档 images 回填", payload(0), resolve(pool[0]))
        assertEquals("已删除图应从 imageData 回填", payload(9), resolve(pool[1]))
    }
}
