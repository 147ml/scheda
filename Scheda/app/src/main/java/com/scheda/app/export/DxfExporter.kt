package com.scheda.app.export
import androidx.compose.ui.graphics.toArgb

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.gson.Gson
import com.scheda.app.model.BlockDef
import com.scheda.app.model.DrawingPrimitive
import com.scheda.app.model.Layer
import com.scheda.app.model.Point2D
import com.scheda.app.model.RangeLabelLayout
import com.scheda.app.model.ReferenceImage
import com.scheda.app.model.blockContentCentroid
import java.io.File
import java.io.FileOutputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

object DxfExporter {

    private const val TAG = "DxfExporter"
    /** 块嵌套炸开的最大深度，防止块自引用/循环引用导致栈溢出 */
    private const val MAX_BLOCK_DEPTH = 8

    @Volatile
    private var initialized = false
    private var lastError = ""

    fun getLastError() = lastError

    /**
     * 导出结果。
     * imageFiles：随 DXF 生成的参考图片文件（与 DXF 同目录）。
     * DXF 的 IMAGE 实体是外部文件引用（IMAGEDEF 存相对文件名），
     * 交付时图片必须与 DXF 放同一目录（打包 ZIP 或一并分享）。
     */
    data class Result(val success: Boolean, val imageFiles: List<File> = emptyList())

    @Synchronized
    private fun ensurePython(ctx: Context): Boolean {
        return try {
            if (!initialized) {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(ctx))
                }
                initialized = true
            }
            true
        } catch (e: Exception) {
            lastError = "Python init failed: ${e.javaClass.simpleName}: ${e.message}\n${e.stackTraceToString()}"
            false
        }
    }

    fun export(
        ctx: Context,
        outputPath: String,
        primitives: List<DrawingPrimitive>,
        layers: List<Layer>,
        blockDefs: List<BlockDef>,
        images: List<ReferenceImage> = emptyList(),
    ): Result {
        lastError = ""

        try {
            lastError = "Step 1: ensurePython..."
            if (!ensurePython(ctx)) return Result(false)

            lastError = "Step 2: explode blocks..."
            // 图块引用在导出前炸开为基本图元（锚定规则与 drawBlockRef 一致：内容形心落在引用点）
            val defMap = blockDefs.associateBy { it.id }
            val exploded = explodePrimitives(primitives, defMap, Affine.IDENTITY, 0)

            // 过滤 NaN/Infinity 坐标：Gson 默认拒绝非有限值，ezdxf 收到也会抛异常
            val valid = exploded.filter { hasFiniteGeometry(it) }
            val dropped = exploded.size - valid.size
            if (dropped > 0) {
                Log.w(TAG, "Dropped $dropped primitives with non-finite coordinates")
            }

            lastError = "Step 3a: write images..."
            // 参考图片：base64 → 与 DXF 同目录的图片文件（IMAGEDEF 相对文件名引用），
            // 摆放参数随 JSON 传给 Python。隐藏图层的图片照常导出（图层在 DXF 中置 OFF，与基元一致）
            val imageFiles = ArrayList<File>()
            val imageMaps = ArrayList<Map<String, Any>>()
            val outDir = File(outputPath).parentFile
            for (img in images) {
                try {
                    val bytes = android.util.Base64.decode(img.data, android.util.Base64.DEFAULT)
                    val f = File(outDir, "image_${img.id}.jpg")
                    FileOutputStream(f).use { it.write(bytes) }
                    imageFiles.add(f)
                    imageMaps.add(mapOf(
                        "filename" to f.name,
                        "cx" to img.centerX, "cy" to img.centerY,
                        "w" to img.width, "h" to img.height,
                        "rotationDeg" to img.rotationDeg,
                        "layerId" to img.layerId,
                        "pixelWidth" to img.pixelWidth, "pixelHeight" to img.pixelHeight,
                        "alpha" to img.alpha
                    ))
                } catch (e: Exception) {
                    Log.w(TAG, "Image skipped: ${img.id}", e)
                }
            }

            lastError = "Step 3: build JSON..."
            val jsonMap = mapOf(
                "primitives" to valid.map { primitiveToMap(it) },
                "layers" to layers.map { layerToMap(it) },
                "images" to imageMaps
            )
            val jsonString = Gson().toJson(jsonMap)

            lastError = "Step 4: call Python..."
            val py = Python.getInstance()
            val module = py.getModule("scheda_dxf_export")

            lastError = "Step 5: call scheda_json_to_dxf..."
            val result = module.callAttr("scheda_json_to_dxf", jsonString)
            val dxfBytes = result.toJava(ByteArray::class.java)
            val warnings = module.callAttr("get_last_warnings").toJava(String::class.java)
            if (warnings.isNotBlank()) {
                Log.w(TAG, "DXF export warnings:\n$warnings")
            }

            lastError = "Step 6: write file..."
            FileOutputStream(outputPath).use { it.write(dxfBytes) }
            lastError = buildString {
                append("Step 7: done (${dxfBytes.size} bytes")
                if (dropped > 0) append(", dropped $dropped invalid primitives")
                append(")")
                if (warnings.isNotBlank()) append("\nWarnings:\n").append(warnings)
            }
            return Result(true, imageFiles)
        } catch (e: Exception) {
            lastError = "FAILED at ${lastError}\n${e.javaClass.simpleName}: ${e.message}\n${e.stackTraceToString()}"
            Log.e(TAG, "Export failed", e)
            return Result(false)
        }
    }

    // ── 图块炸开 ──

    /** 2D 相似变换（旋转 + 等比缩放 + 平移），矩阵 [a b tx; c d ty] */
    private data class Affine(
        val a: Float, val b: Float, val c: Float, val d: Float,
        val tx: Float, val ty: Float
    ) {
        fun apply(x: Float, y: Float) = Point2D(a * x + b * y + tx, c * x + d * y + ty)

        /** this ∘ inner：先应用 inner，再应用 this */
        fun then(inner: Affine) = Affine(
            a * inner.a + b * inner.c, a * inner.b + b * inner.d,
            c * inner.a + d * inner.c, c * inner.b + d * inner.d,
            a * inner.tx + b * inner.ty + tx, c * inner.tx + d * inner.ty + ty
        )

        val scale: Float get() = hypot(a.toDouble(), c.toDouble()).toFloat()
        val rotation: Float get() = atan2(c.toDouble(), a.toDouble()).toFloat()

        companion object {
            val IDENTITY = Affine(1f, 0f, 0f, 1f, 0f, 0f)
        }
    }

    private fun explodePrimitives(
        prims: List<DrawingPrimitive>,
        defMap: Map<String, BlockDef>,
        m: Affine,
        depth: Int
    ): List<DrawingPrimitive> {
        val out = ArrayList<DrawingPrimitive>(prims.size)
        for (p in prims) {
            if (p is DrawingPrimitive.BlockRefPrimitive) {
                val bd = defMap[p.blockDefId]
                val centroid = bd?.let { blockContentCentroid(it.primitives) }
                when {
                    bd == null || centroid == null ->
                        Log.w(TAG, "BlockRef skipped: def ${p.blockDefId} missing or empty")
                    depth >= MAX_BLOCK_DEPTH ->
                        Log.w(TAG, "Block nesting too deep, skipped: ${bd.name}")
                    else -> {
                        // 与 drawBlockRef 一致：先缩放，再绕引用点旋转，最后平移使形心落在 (x, y)
                        val cosR = cos(p.rotation)
                        val sinR = sin(p.rotation)
                        val s = p.scale
                        val local = Affine(
                            a = s * cosR, b = -s * sinR, c = s * sinR, d = s * cosR,
                            tx = p.x - s * (cosR * centroid.x - sinR * centroid.y),
                            ty = p.y - s * (sinR * centroid.x + cosR * centroid.y)
                        )
                        out.addAll(explodePrimitives(bd.primitives, defMap, m.then(local), depth + 1))
                    }
                }
            } else {
                out.add(applyAffine(p, m))
            }
        }
        return out
    }

    private fun applyAffine(p: DrawingPrimitive, m: Affine): DrawingPrimitive {
        if (m === Affine.IDENTITY) return p
        val s = m.scale
        val rot = m.rotation
        return when (p) {
            is DrawingPrimitive.LinePrimitive -> {
                val p1 = m.apply(p.startX, p.startY)
                val p2 = m.apply(p.endX, p.endY)
                p.copy(startX = p1.x, startY = p1.y, endX = p2.x, endY = p2.y)
            }
            is DrawingPrimitive.FreehandPath ->
                p.copy(points = p.points.map { m.apply(it.x, it.y) })
            is DrawingPrimitive.RectanglePrimitive -> {
                // corners 恒为轴对齐包围盒四角，旋转量记录在 rotation（与 Python 端语义一致）
                val cx = (p.corners.minOf { it.x } + p.corners.maxOf { it.x }) / 2f
                val cy = (p.corners.minOf { it.y } + p.corners.maxOf { it.y }) / 2f
                val hx = (p.corners.maxOf { it.x } - p.corners.minOf { it.x }) / 2f * s
                val hy = (p.corners.maxOf { it.y } - p.corners.minOf { it.y }) / 2f * s
                val nc = m.apply(cx, cy)
                p.copy(
                    corners = listOf(
                        Point2D(nc.x - hx, nc.y - hy), Point2D(nc.x + hx, nc.y - hy),
                        Point2D(nc.x + hx, nc.y + hy), Point2D(nc.x - hx, nc.y + hy)
                    ),
                    rotation = p.rotation + rot
                )
            }
            is DrawingPrimitive.CirclePrimitive -> {
                val nc = m.apply(p.centerX, p.centerY)
                val ne = m.apply(p.endX, p.endY)
                p.copy(
                    centerX = nc.x, centerY = nc.y, endX = ne.x, endY = ne.y,
                    rotation = p.rotation + rot,
                    // 物化半径：变换含旋转时不能再依赖 endX/endY 反推
                    rx = p.radiusX * s,
                    ry = p.radiusY * s
                )
            }
            is DrawingPrimitive.TextPrimitive -> {
                val t = m.apply(p.x, p.y)
                p.copy(x = t.x, y = t.y, fontSize = p.fontSize * s, rotation = p.rotation + rot)
            }
            is DrawingPrimitive.NumberLabelPrimitive -> {
                val t = m.apply(p.x, p.y)
                p.copy(x = t.x, y = t.y, fontSize = p.fontSize * s, rotation = p.rotation + rot)
            }
            is DrawingPrimitive.RangeLabelPrimitive -> {
                val t = m.apply(p.x, p.y)
                p.copy(
                    x = t.x, y = t.y,
                    fontSize = p.fontSize * s,
                    arrowSpan = p.arrowSpan * s,
                    rotation = p.rotation + rot
                )
            }
            else -> p
        }
    }

    // ── NaN/Infinity 过滤 ──

    private fun hasFiniteGeometry(p: DrawingPrimitive): Boolean = when (p) {
        is DrawingPrimitive.LinePrimitive -> finite(p.startX, p.startY, p.endX, p.endY)
        is DrawingPrimitive.FreehandPath -> p.points.all { it.x.isFinite() && it.y.isFinite() }
        is DrawingPrimitive.RectanglePrimitive ->
            p.corners.all { it.x.isFinite() && it.y.isFinite() } && p.rotation.isFinite()
        is DrawingPrimitive.CirclePrimitive ->
            finite(p.centerX, p.centerY, p.radiusX, p.radiusY, p.rotation)
        is DrawingPrimitive.TextPrimitive -> finite(p.x, p.y, p.fontSize, p.rotation)
        is DrawingPrimitive.NumberLabelPrimitive -> finite(p.x, p.y, p.fontSize, p.rotation)
        is DrawingPrimitive.RangeLabelPrimitive -> finite(p.x, p.y, p.fontSize, p.rotation, p.arrowSpan)
        is DrawingPrimitive.BlockRefPrimitive -> finite(p.x, p.y, p.scale, p.rotation)
    }

    private fun finite(vararg v: Float) = v.all { it.isFinite() }

    // ── 序列化 ──

    private fun primitiveToMap(p: DrawingPrimitive): Map<String, Any> {
        val base = mutableMapOf<String, Any>(
            "type" to when (p) {
                is DrawingPrimitive.FreehandPath -> "freehand"
                is DrawingPrimitive.RectanglePrimitive -> "rectangle"
                is DrawingPrimitive.LinePrimitive -> "line"
                is DrawingPrimitive.NumberLabelPrimitive -> "number"
                is DrawingPrimitive.TextPrimitive -> "text"
                is DrawingPrimitive.RangeLabelPrimitive -> "range"
                is DrawingPrimitive.CirclePrimitive -> "circle"
                else -> "unknown"
            },
            "layerId" to p.layerId,
            "color" to p.color.toArgb(),
            "strokeWidth" to p.strokeWidth,
            "lineType" to p.lineStyle.type.name
        )

        when (p) {
            is DrawingPrimitive.FreehandPath -> {
                base["points"] = p.points.map { listOf(it.x, it.y) }
                base["isClosed"] = p.isClosed
            }
            is DrawingPrimitive.RectanglePrimitive -> {
                base["corners"] = p.corners.map { listOf(it.x, it.y) }
                base["rotation"] = p.rotation
            }
            is DrawingPrimitive.LinePrimitive -> {
                base["startX"] = p.startX; base["startY"] = p.startY
                base["endX"] = p.endX; base["endY"] = p.endY
            }
            is DrawingPrimitive.NumberLabelPrimitive -> {
                base["value"] = p.value; base["fontSize"] = p.fontSize
                base["x"] = p.x; base["y"] = p.y; base["rotation"] = p.rotation
                base["horizontalOnly"] = p.horizontalOnly
                base["circled"] = p.circled
            }
            is DrawingPrimitive.TextPrimitive -> {
                base["text"] = p.text; base["fontSize"] = p.fontSize
                base["x"] = p.x; base["y"] = p.y; base["rotation"] = p.rotation
                base["horizontalOnly"] = p.horizontalOnly
            }
            is DrawingPrimitive.RangeLabelPrimitive -> {
                base["startValue"] = p.startValue; base["endValue"] = p.endValue
                base["fontSize"] = p.fontSize; base["x"] = p.x; base["y"] = p.y
                base["rotation"] = p.rotation; base["reversed"] = p.reversed
                base["horizontalOnly"] = p.horizontalOnly; base["arrowSpan"] = p.arrowSpan
                // 区间数字朝向：与画布渲染共用 RangeLabelLayout 纯函数，预计算每个文字段的
                // 绘制角（屏幕顺时针弧度）与锚点（世界坐标）写入 JSON，Python 端直接消费，
                // 保证 DXF 与画布方向永远一致。
                val layout = RangeLabelLayout.compute(
                    p.rotation, p.x, p.y, p.fontSize, p.arrowSpan, p.reversed,
                    numberAngle = RangeLabelLayout.numberAngleFor(p.numbersFaceLeft)
                )
                base["textLayout"] = mapOf(
                    "arrowAngle" to layout.arrowAngle,
                    "arrowHalf" to RangeLabelLayout.arrowHalfLength(p.arrowSpan),
                    "reversed" to p.reversed,
                    "texts" to listOf(
                        mapOf(
                            "text" to (if (p.reversed) p.endValue else p.startValue).toString(),
                            "x" to layout.startText.x, "y" to layout.startText.y,
                            "angle" to layout.startText.angle
                        ),
                        mapOf(
                            "text" to (if (p.reversed) p.startValue else p.endValue).toString(),
                            "x" to layout.endText.x, "y" to layout.endText.y,
                            "angle" to layout.endText.angle
                        )
                    )
                )
            }
            is DrawingPrimitive.CirclePrimitive -> {
                base["centerX"] = p.centerX; base["centerY"] = p.centerY
                base["radiusX"] = p.radiusX; base["radiusY"] = p.radiusY
                base["rotation"] = p.rotation
            }
            else -> { /* BlockRef 已在导出前炸开；其余未知类型由 Python 端记录告警 */ }
        }
        return base
    }

    private fun layerToMap(l: Layer): Map<String, Any> = mapOf(
        "id" to l.id, "name" to l.name,
        "isVisible" to l.isVisible, "isLocked" to l.isLocked,
        "color" to l.color.toArgb()
    )
}
