package com.scheda.app.export

import androidx.compose.ui.graphics.Color
import com.scheda.app.model.DrawingPrimitive
import com.scheda.app.model.Bounds
import com.scheda.app.model.PendingEdit
import com.scheda.app.model.Point2D
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * DXF 读取器：把常见实体解析为 app 基元。
 *
 * 约定与 DxfWriter 完全反向：
 * - appY = -dxfY（app 的 Y 向下，DXF 的 Y 向上）
 * - appRotation = -dxfAngle（弧度）
 * - 图层信息全部丢弃（layerId = 1）
 * - INSERT 块引用按 BLOCK 定义炸开成线条（最多两层，防循环）
 *
 * 不支持的实体（SPLINE / HATCH / DIMENSION / 3D 等）直接跳过。
 */
object DxfReader {

    fun read(file: File): List<DrawingPrimitive> {
        val bytes = file.readBytes()
        var text = String(bytes, Charsets.UTF_8)
        // 中文 DXF 多为 GBK 编码：UTF-8 解码出现替换符时回退 GBK
        if (text.contains('�')) text = String(bytes, charset("GBK"))
        return parse(text)
    }

    private data class BlockRec(val name: String, val baseX: Float, val baseY: Float, val entities: List<List<Pair<Int, String>>>)

    fun parse(text: String): List<DrawingPrimitive> {
        // 1) 按 group code 0 切分为记录流
        val lines = text.split(Regex("\r\n|\r|\n"))
        val records = mutableListOf<MutableList<Pair<Int, String>>>()
        var cur: MutableList<Pair<Int, String>>? = null
        var i = 0
        while (i + 1 < lines.size) {
            val code = lines[i].trim().toIntOrNull() ?: -1
            val value = lines[i + 1]
            i += 2
            if (code == 0) {
                cur = mutableListOf()
                cur.add(0 to value.trim())
                records.add(cur)
            } else {
                cur?.add(code to value)
            }
        }

        // 2) 收集 BLOCK 定义
        val blocks = mutableMapOf<String, BlockRec>()
        run {
            var idx = 0
            while (idx < records.size) {
                val rec = records[idx]
                if (rec.firstOrNull()?.second == "BLOCK") {
                    val name = gv(rec, 2) ?: ""
                    val bx = (gd(rec, 10) ?: 0.0).toFloat()
                    val by = -(gd(rec, 20) ?: 0.0).toFloat()
                    val ents = mutableListOf<List<Pair<Int, String>>>()
                    idx++
                    while (idx < records.size && records[idx].firstOrNull()?.second != "ENDBLK") {
                        ents.add(records[idx])
                        idx++
                    }
                    if (name.isNotEmpty()) blocks[name] = BlockRec(name, bx, by, ents)
                }
                idx++
            }
        }

        // 3) 解析顶层实体
        val out = mutableListOf<DrawingPrimitive>()
        var idx = 0
        while (idx < records.size) {
            val rec = records[idx]
            val type = rec.firstOrNull()?.second
            if (type == "POLYLINE") {
                // 顺序消费 VERTEX 直到 SEQEND
                val pts = mutableListOf<Point2D>()
                val closed = ((gi(rec, 70) ?: 0) and 1) != 0
                idx++
                while (idx < records.size && records[idx].firstOrNull()?.second != "SEQEND") {
                    val v = records[idx]
                    if (v.firstOrNull()?.second == "VERTEX") {
                        val x = (gd(v, 10) ?: 0.0).toFloat()
                        val y = -(gd(v, 20) ?: 0.0).toFloat()
                        pts.add(Point2D(x, y))
                    }
                    idx++
                }
                if (pts.size >= 2) out.add(polyline(pts, closed, entityColor(rec)))
            } else {
                out.addAll(entityToPrimitives(rec, blocks, 0))
            }
            idx++
        }
        return out
    }

    // ── group code 读取辅助 ──
    private fun gv(rec: List<Pair<Int, String>>, code: Int): String? =
        rec.firstOrNull { it.first == code }?.second?.trim()
    private fun gd(rec: List<Pair<Int, String>>, code: Int): Double? =
        gv(rec, code)?.toDoubleOrNull()
    private fun gi(rec: List<Pair<Int, String>>, code: Int): Int? =
        gv(rec, code)?.toIntOrNull()

    private val ACI_COLORS = mapOf(
        1 to Color(0xFFE53935), 2 to Color(0xFFFDD835), 3 to Color(0xFF43A047),
        4 to Color(0xFF00ACC1), 5 to Color(0xFF1E88E5), 6 to Color(0xFF8E24AA),
        7 to Color(0xFF222222), 8 to Color(0xFF757575), 9 to Color(0xFFBDBDBD)
    )

    private fun entityColor(rec: List<Pair<Int, String>>): Color {
        gi(rec, 420)?.let { return Color(0xFF000000.toInt() or (it and 0xFFFFFF)) }
        gi(rec, 62)?.let { ACI_COLORS[it]?.let { c -> return c } }
        return Color(0xFF222222)
    }

    private fun polyline(pts: List<Point2D>, closed: Boolean, color: Color): DrawingPrimitive.FreehandPath =
        DrawingPrimitive.FreehandPath(
            points = pts, isClosed = closed, color = color, strokeWidth = 2f,
            // 全部角点标记为尖角：CAD 折线按直线段渲染，不做平滑
            sharpCorners = pts.indices.toSet()
        )

    private fun entityToPrimitives(
        rec: List<Pair<Int, String>>,
        blocks: Map<String, BlockRec>,
        depth: Int
    ): List<DrawingPrimitive> {
        val color = entityColor(rec)
        return when (rec.firstOrNull()?.second) {
            "LINE" -> {
                val x1 = (gd(rec, 10) ?: 0.0).toFloat(); val y1 = -(gd(rec, 20) ?: 0.0).toFloat()
                val x2 = (gd(rec, 11) ?: 0.0).toFloat(); val y2 = -(gd(rec, 21) ?: 0.0).toFloat()
                listOf(DrawingPrimitive.LinePrimitive(x1, y1, x2, y2, color, 2f))
            }
            "LWPOLYLINE" -> {
                val pts = rec.filter { it.first == 10 || it.first == 20 }
                    .let { pairs ->
                        val xs = pairs.filter { it.first == 10 }.map { it.second.trim().toFloatOrNull() ?: 0f }
                        val ys = pairs.filter { it.first == 20 }.map { -(it.second.trim().toFloatOrNull() ?: 0f) }
                        xs.zip(ys) { x, y -> Point2D(x, y) }
                    }
                if (pts.size < 2) emptyList()
                else {
                    val closed = ((gi(rec, 70) ?: 0) and 1) != 0
                    listOf(polyline(pts, closed, color))
                }
            }
            "SPLINE" -> {
                // 样条：用控制点(11/21)折线近似（自家导出文件的回导路径）
                val pts = rec.filter { it.first == 11 || it.first == 21 }
                    .let { pairs ->
                        val xs = pairs.filter { it.first == 11 }.map { it.second.trim().toFloatOrNull() ?: 0f }
                        val ys = pairs.filter { it.first == 21 }.map { -(it.second.trim().toFloatOrNull() ?: 0f) }
                        xs.zip(ys) { x, y -> Point2D(x, y) }
                    }
                if (pts.size < 2) emptyList()
                else {
                    val closed = ((gi(rec, 70) ?: 0) and 1) != 0
                    listOf(DrawingPrimitive.FreehandPath(points = pts, isClosed = closed, color = color, strokeWidth = 2f))
                }
            }
            "CIRCLE" -> {
                val cx = (gd(rec, 10) ?: 0.0).toFloat(); val cy = -(gd(rec, 20) ?: 0.0).toFloat()
                val r = (gd(rec, 40) ?: 0.0).toFloat()
                if (r <= 0f) emptyList()
                else listOf(DrawingPrimitive.CirclePrimitive(
                    centerX = cx, centerY = cy, endX = cx + r, endY = cy,
                    rx = r, ry = r, color = color, strokeWidth = 2f))
            }
            "ARC" -> {
                val cx = (gd(rec, 10) ?: 0.0).toFloat(); val cy = -(gd(rec, 20) ?: 0.0).toFloat()
                val r = (gd(rec, 40) ?: 0.0).toFloat()
                val a1 = Math.toRadians(gd(rec, 50) ?: 0.0)
                val a2 = Math.toRadians(gd(rec, 51) ?: 0.0)
                if (r <= 0f) emptyList()
                else {
                    // DXF 空间逆时针扫掠；app 侧 y = -cy_dxf - r·sin(a)，方向随之取反
                    var sweep = a2 - a1
                    while (sweep <= 0) sweep += 2 * Math.PI
                    val n = maxOf(8, (sweep / (Math.PI / 16)).toInt() + 1)
                    val ptsFlip = (0..n).map { k ->
                        val a = a1 + sweep * k / n
                        Point2D((cx + r * cos(a)).toFloat(), cy - (r * sin(a)).toFloat())
                    }
                    listOf(DrawingPrimitive.FreehandPath(points = ptsFlip, color = color, strokeWidth = 2f))
                }
            }
            "ELLIPSE" -> {
                val cx = (gd(rec, 10) ?: 0.0).toFloat(); val cy = -(gd(rec, 20) ?: 0.0).toFloat()
                val mx = (gd(rec, 11) ?: 0.0).toFloat(); val my = (gd(rec, 21) ?: 0.0).toFloat()
                val ratio = (gd(rec, 40) ?: 1.0).toFloat()
                val major = hypot(mx.toDouble(), my.toDouble()).toFloat()
                if (major <= 0f) emptyList()
                else {
                    val rx = major; val ry = major * ratio
                    val rot = -atan2(my, mx)
                    listOf(DrawingPrimitive.CirclePrimitive(
                        centerX = cx, centerY = cy,
                        endX = cx + rx * cos(rot) - ry * sin(rot),
                        endY = cy + rx * sin(rot) + ry * cos(rot),
                        rotation = rot, rx = rx, ry = ry,
                        color = color, strokeWidth = 2f))
                }
            }
            "TEXT", "MTEXT" -> {
                val raw = if (rec.first().second == "MTEXT") {
                    rec.filter { it.first == 1 || it.first == 3 }.joinToString("") { it.second }
                } else gv(rec, 1) ?: ""
                val content = stripMtext(raw)
                if (content.isBlank()) emptyList()
                else {
                    val hasAlign = (gi(rec, 72) ?: 0) != 0 || (gi(rec, 73) ?: 0) != 0
                    val useSecond = hasAlign && gv(rec, 11) != null
                    val px = (gd(rec, if (useSecond) 11 else 10) ?: 0.0).toFloat()
                    val py = -(gd(rec, if (useSecond) 21 else 20) ?: 0.0).toFloat()
                    val height = (gd(rec, 40) ?: 30.0).toFloat()
                    val rot = -Math.toRadians(gd(rec, 50) ?: 0.0).toFloat()
                    listOf(DrawingPrimitive.TextPrimitive(
                        text = content, x = px, y = py,
                        rotation = rot, fontSize = (height / 1.3f).coerceIn(10f, 600f),
                        color = color, horizontalOnly = true))
                }
            }
            "INSERT" -> {
                if (depth >= 2) return emptyList()
                val name = gv(rec, 2) ?: return emptyList()
                val blk = blocks[name] ?: return emptyList()
                val ix = (gd(rec, 10) ?: 0.0).toFloat(); val iy = -(gd(rec, 20) ?: 0.0).toFloat()
                val sx = (gd(rec, 41) ?: 1.0).toFloat(); val sy = (gd(rec, 42) ?: 1.0).toFloat()
                val rot = -Math.toRadians(gd(rec, 50) ?: 0.0).toFloat()
                val edit = PendingEdit(
                    active = true,
                    rotation = rot, scaleX = sx, scaleY = sy,
                    offsetX = ix - blk.baseX, offsetY = iy - blk.baseY,
                    bounds = Bounds(blk.baseX, blk.baseY, blk.baseX, blk.baseY),
                    pivotX = blk.baseX, pivotY = blk.baseY
                )
                val out = mutableListOf<DrawingPrimitive>()
                for (sub in blk.entities) {
                    if (sub.firstOrNull()?.second == "INSERT" && depth >= 1) continue
                    for (p in entityToPrimitives(sub, blocks, depth + 1)) {
                        out.add(p.applyTransform(edit))
                    }
                }
                out
            }
            else -> emptyList()
        }
    }

    /** 去掉 MTEXT 内联格式码（\A1; \f...; \H2.5x; {} 等），\P 换行转空格 */
    private fun stripMtext(s: String): String {
        var t = s.replace("\\P", " ")
        t = t.replace(Regex("\\\\[A-Za-z][^;{}]*;"), "")
        t = t.replace(Regex("[{}]"), "")
        return t.trim()
    }
}
