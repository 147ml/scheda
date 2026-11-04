package com.scheda.app.export

import com.scheda.app.model.DrawingPrimitive
import com.scheda.app.model.LineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * DXF 导入的线宽/线型口径：
 * - 线宽：实体 370 → BYLAYER 落图层 370 → 都没有按 0.25mm
 * - 线型：非 Continuous 映射为虚线，dash/gap = LTYPE 段长 × $LTSCALE
 */
class DxfReaderStyleTest {

    private fun dxf(body: String) = """
        0
        SECTION
        2
        HEADER
        9
        ${'$'}LTSCALE
        40
        2.0
        0
        ENDSEC
        0
        SECTION
        2
        TABLES
        0
        LTYPE
        2
        DASHED
        49
        0.6
        49
        -0.3
        0
        LAYER
        2
        0
        6
        DASHED
        370
        50
        0
        ENDSEC
        0
        SECTION
        2
        ENTITIES
        $body
        0
        ENDSEC
        0
        EOF
    """.trimIndent()

    @Test
    fun `BYLAYER 线宽取图层 370 且线型映射为虚线`() {
        val text = dxf(
            """
            0
            LINE
            8
            0
            10
            0
            20
            0
            11
            10
            21
            0
            6
            BYLAYER
            370
            -1
            """.trimIndent()
        )
        val prims = DxfReader.parse(text)
        assertEquals(1, prims.size)
        val p = prims[0] as DrawingPrimitive.LinePrimitive
        assertTrue("线宽 0.5mm 应换算成 0.5", abs(p.strokeWidth - 0.5f) < 1e-4f)
        assertEquals(LineType.DASHED, p.lineStyle.type)
        // 0.6 × LTSCALE(2.0) = 1.2，gap 0.3 × 2.0 = 0.6
        assertTrue(abs(p.lineStyle.dashLength - 1.2f) < 1e-4f)
        assertTrue(abs(p.lineStyle.gapLength - 0.6f) < 1e-4f)
    }

    @Test
    fun `实体显式 370 优先于图层线宽`() {
        val text = dxf(
            """
            0
            LINE
            8
            0
            10
            0
            20
            0
            11
            10
            21
            0
            370
            100
            """.trimIndent()
        )
        val prims = DxfReader.parse(text)
        val p = prims[0] as DrawingPrimitive.LinePrimitive
        // 实体 370=100（1.00mm）应盖掉图层的 50（0.5mm）
        assertEquals(1.0f, p.strokeWidth, 1e-4f)
    }

    @Test
    fun `Continuous 线型保持实线`() {
        val text = """
            0
            SECTION
            2
            HEADER
            9
            ${'$'}LTSCALE
            40
            1.0
            0
            ENDSEC
            0
            SECTION
            2
            TABLES
            0
            LAYER
            2
            0
            6
            Continuous
            370
            -3
            0
            ENDSEC
            0
            SECTION
            2
            ENTITIES
            0
            LINE
            8
            0
            10
            0
            20
            0
            11
            10
            21
            0
            0
            ENDSEC
            0
            EOF
        """.trimIndent()
        val p = DxfReader.parse(text)[0] as DrawingPrimitive.LinePrimitive
        assertEquals(LineType.SOLID, p.lineStyle.type)
        // 图层 -3（Default）→ 0.25mm
        assertEquals(0.25f, p.strokeWidth, 1e-4f)
    }

    @Test
    fun `INSERT 的块缩放只改几何 不改线宽`() {
        val text = """
            0
            SECTION
            2
            TABLES
            0
            LAYER
            2
            0
            6
            Continuous
            370
            -3
            0
            ENDSEC
            0
            SECTION
            2
            BLOCKS
            0
            BLOCK
            2
            MYBLK
            10
            0
            20
            0
            0
            LINE
            8
            0
            10
            0
            20
            0
            11
            10
            21
            0
            0
            ENDBLK
            0
            ENDSEC
            0
            SECTION
            2
            ENTITIES
            0
            INSERT
            2
            MYBLK
            10
            100
            20
            0
            41
            2
            42
            2
            0
            ENDSEC
            0
            EOF
        """.trimIndent()
        val p = DxfReader.parse(text)[0] as DrawingPrimitive.LinePrimitive
        // 几何放大 2 倍：0..10 → 100..120
        assertEquals(100f, p.startX, 1e-3f)
        assertEquals(120f, p.endX, 1e-3f)
        // 线宽保持 0.25，不乘块缩放
        assertEquals(0.25f, p.strokeWidth, 1e-4f)
    }
}
