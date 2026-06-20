package com.scheda.app.export

import com.scheda.app.model.DrawingPrimitive
import com.scheda.app.model.BlockDef
import com.scheda.app.model.Layer
import com.scheda.app.model.LineStyle
import com.scheda.app.model.LineType
import com.scheda.app.model.Point2D
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * DXF writer producing AC1015 (AutoCAD 2000) format with full handles,
 * subclass markers, owner references, and OBJECTS section — required for
 * CAD programs to open the file.
 */
class DxfWriter(private val outputStream: OutputStream) {

    private lateinit var writer: OutputStreamWriter
    private val lineSep = "\n"
    private var nextHandle = 0x10  // start past all hardcoded handles (1-9, A, D)
    private var currentLayerName = "0"

    private fun allocHandle(): String {
        val h = Integer.toHexString(nextHandle++).uppercase()
        raw("5", h); return h
    }

    /** Convenience: alloc a handle, write 330/350 owner, return handle. */
    private fun allocHandleWithOwner(groupCode: String, owner: String): String {
        val h = Integer.toHexString(nextHandle++).uppercase()
        raw("5", h)
        raw(groupCode, owner)
        return h
    }

    /** Pre-allocate a handle without writing anything to the stream. */
    private fun allocHandleStr(): String {
        return Integer.toHexString(nextHandle++).uppercase()
    }

    /** Reserved handles used by the root dictionary. */
    private val ROOT_HANDLE = "A"
    private val LAYOUT_DICT_HANDLE = "D"
    // BLOCK_RECORD handles
    private val BLOCK_RECORD_TABLE_HANDLE = "9"
    private var modelSpaceBlockRecHandle = ""
    private var paperSpaceBlockRecHandle = ""
    private var modelLayoutHandle = ""
    // OBJECTS sub-dictionary handles (pre-allocated after entities)
    private var colorDictH = ""
    private var groupDictH = ""
    private var materialDictH = ""
    private var mleaderstyleDictH = ""
    private var mlinestyleDictH = ""
    private var plotSettingsDictH = ""
    private var plotStyleNameH = ""
    private var plotStylePlaceholderH = ""
    private var scaleListDictH = ""
    private var tableStyleDictH = ""
    private var visualStyleDictH = ""
    private var paperSpaceLayoutH = ""
    private var matByBlockH = ""
    private var matByLayerH = ""
    private var matGlobalH = ""
    private var mlinestyleStdH = ""
    private var mleaderstyleStdH = ""

    fun write(
        primitives: List<DrawingPrimitive>,
        layers: List<Layer>,
        blockDefs: List<BlockDef> = emptyList(),
        canvasOffsetX: Float? = null,
        canvasOffsetY: Float? = null,
        canvasScale: Float? = null,
        globalLineScale: Float = 1f
    ) {
        writer = OutputStreamWriter(outputStream, "GBK")

        // Count visible primitives before writing, so $HANDSEED is correct
        // Also track bounding box for viewport auto-fit
        val visibleLayerIds = layers.filter { it.isVisible }.map { it.id }.toSet()
        var entityCount = 0
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        fun updateBounds(x: Float, y: Float) {
            val dx = x.toDouble(); val dy = -y.toDouble()  // Y flipped for DXF
            if (dx < minX) minX = dx; if (dx > maxX) maxX = dx
            if (dy < minY) minY = dy; if (dy > maxY) maxY = dy
        }
        for (p in primitives) {
            if (p.layerId !in visibleLayerIds) continue
            when (p) {
                is DrawingPrimitive.FreehandPath -> {
                    entityCount += maxOf(0, p.points.size - 1)
                    for (pt in p.points) updateBounds(pt.x, pt.y)
                }
                is DrawingPrimitive.RectanglePrimitive -> {
                    entityCount += 4
                    updateBounds(p.startX, p.startY); updateBounds(p.endX, p.endY)
                }
                is DrawingPrimitive.CirclePrimitive -> {
                    entityCount++
                    updateBounds(p.centerX - p.radiusX, p.centerY - p.radiusY)
                    updateBounds(p.centerX + p.radiusX, p.centerY + p.radiusY)
                }
                is DrawingPrimitive.LinePrimitive -> {
                    entityCount++
                    updateBounds(p.startX, p.startY); updateBounds(p.endX, p.endY)
                }
                is DrawingPrimitive.NumberLabelPrimitive -> {
                    entityCount++
                    updateBounds(p.x, p.y)
                }
                is DrawingPrimitive.TextPrimitive -> {
                    entityCount++
                    updateBounds(p.x, p.y)
                }
                is DrawingPrimitive.RangeLabelPrimitive -> {
                    entityCount += 5  // 2 text + 1 line + 2 arrowhead
                    updateBounds(p.x, p.y)
                }
                is DrawingPrimitive.BlockRefPrimitive -> {
                    val bd = blockDefs.find { it.id == p.blockDefId }
                    if (bd != null) {
                        for (cp in bd.primitives) {
                            entityCount += countEntityHandles(cp)
                        }
                    }
                }
                else -> {}
            }
        }
        // Compute viewport center and height from bounding box or canvas state
        val viewCx: Double
        val viewCy: Double
        val viewH: Double
        if (canvasOffsetX != null && canvasOffsetY != null && canvasScale != null && canvasScale > 0f) {
            // Use canvas state for viewport positioning (approximate screen center)
            viewCx = -(canvasOffsetX / canvasScale).toDouble()
            viewCy = -(-canvasOffsetY / canvasScale).toDouble() // negate Y for DXF flip
            viewH = maxOf((1600.0 / canvasScale).toDouble(), 100.0)
        } else if (entityCount > 0 && minX != Double.MAX_VALUE) {
            // Auto-fit to bounding box
            var padX = (maxX - minX) * 0.1
            var padY = (maxY - minY) * 0.1
            if (padX < 1.0) padX = 100.0
            if (padY < 1.0) padY = 100.0
            viewCx = (minX + maxX) / 2.0
            viewCy = (minY + maxY) / 2.0
            viewH = maxOf(maxY - minY + padY * 2, 100.0)
        } else {
            viewCx = 0.0; viewCy = 0.0; viewH = 1000.0
        }
        // Fixed handles: VPORT(1) + LTYPE(5) + LAYER(1) + STYLE(1) + APPID(1) + DIMSTYLE(1) +
        // BLOCK_RECORD(2) + BLOCK(2) + ENDBLK(2) + VIEW(1) + UCS(1) = 18 handles (0x10-0x21)
        // Entity handles: entityCount (starting at 0x22)
        // OBJECTS handles: 18 (17 sub-dict/material/style + Model LAYOUT)
        val totalHandles = 18 + entityCount + 18
        val handSeed = Integer.toHexString(0x10 + totalHandles).uppercase()

        section("HEADER") {
            pair(9, "\$ACADVER"); pair(1, "AC1015")
            pair(9, "\$HANDSEED"); raw("5", handSeed)
            pair(9, "\$ACADMAINTVER"); pair(70, 6)
            pair(9, "\$CLAYER"); pair(8, "0")
            pair(9, "\$CECOLOR"); pair(62, 256)
            pair(9, "\$CELTYPE"); pair(6, "ByLayer")
            pair(9, "\$DWGCODEPAGE"); pair(3, "ANSI_936")
            pair(9, "\$LTSCALE"); pair(40, globalLineScale.toDouble())
            pair(9, "\$EXTMIN"); pair(10, 1e20); pair(20, 1e20); pair(30, 1e20)
            pair(9, "\$EXTMAX"); pair(10, -1e20); pair(20, -1e20); pair(30, -1e20)
            pair(9, "\$LIMMIN"); pair(10, 0.0); pair(20, 0.0)
            pair(9, "\$LIMMAX"); pair(10, 420.0); pair(20, 297.0)
            pair(9, "\$INSUNITS"); pair(70, 4)
        }

        section("TABLES") {
            // ---- VPORT ----
            begin("TABLE");
            pair(2, "VPORT")
            raw("5", "8"); raw("330", "0"); raw("100", "AcDbSymbolTable"); pair(70, 1)
            begin("VPORT"); allocHandle()
            raw("330", "8"); raw("100", "AcDbSymbolTableRecord"); raw("100", "AcDbViewportTableRecord")
            pair(2, "*Active"); pair(70, 0)
            pair(10, viewCx); pair(20, viewCy); pair(11, 1.0); pair(21, 1.0)
            pair(12, 0.0); pair(22, 0.0); pair(13, 0.0); pair(23, 0.0)
            pair(14, 10.0); pair(24, 10.0); pair(15, 10.0); pair(25, 10.0)
            pair(16, 0.0); pair(26, 0.0); pair(36, 1.0); pair(17, 0.0); pair(27, 0.0); pair(37, 0.0)
            pair(40, viewH); pair(41, 1.34); pair(42, 50.0); pair(43, 0.0); pair(44, 0.0)
            pair(50, 0.0); pair(51, 0.0); pair(71, 0); pair(72, 1000)
            pair(73, 1); pair(74, 3); pair(75, 0); pair(76, 0); pair(77, 0); pair(78, 0)
            pair(281, 0); pair(146, 0.0)
            raw("0", "ENDTAB")

            // ---- LTYPE ----
            begin("TABLE");
            pair(2, "LTYPE")
            raw("5", "2"); raw("330", "0"); raw("100", "AcDbSymbolTable"); pair(70, 5)
            for (ltype in listOf("ByBlock", "ByLayer", "Continuous")) {
                begin("LTYPE"); allocHandle()
                raw("330", "2"); raw("100", "AcDbSymbolTableRecord"); raw("100", "AcDbLinetypeTableRecord")
                pair(2, ltype); pair(70, 0); pair(3, ""); pair(72, 65); pair(73, 0); pair(40, 0.0)
            }
            // DASHED linetype
            begin("LTYPE"); allocHandle()
            raw("330", "2"); raw("100", "AcDbSymbolTableRecord"); raw("100", "AcDbLinetypeTableRecord")
            pair(2, "DASHED"); pair(70, 0); pair(3, "Dashed _ _ _ _")
            pair(72, 65); pair(73, 2); pair(40, 20.0)
            pair(49, 12.0); pair(49, -8.0)
            // LIGHTNING linetype (dash-dot-dash pattern)
            begin("LTYPE"); allocHandle()
            raw("330", "2"); raw("100", "AcDbSymbolTableRecord"); raw("100", "AcDbLinetypeTableRecord")
            pair(2, "LIGHTNING"); pair(70, 0); pair(3, "Lightning __.__.__.__")
            pair(72, 65); pair(73, 4); pair(40, 36.0)
            pair(49, 10.0); pair(49, -3.0); pair(49, 2.0); pair(49, -3.0)
            raw("0", "ENDTAB")

            // ---- LAYER ----
            begin("TABLE")
            pair(2, "LAYER")
            raw("5", "1"); raw("330", "0"); raw("100", "AcDbSymbolTable")
            pair(70, maxOf(1, layers.size))
            // Always write layer "0" first
            begin("LAYER"); allocHandle()
            raw("330", "1"); raw("100", "AcDbSymbolTableRecord"); raw("100", "AcDbLayerTableRecord")
            pair(2, "0"); pair(70, 0); pair(62, 7); pair(6, "Continuous")
            // Write user layers
            val layerIdToName = mutableMapOf<Int, String>()
            for (l in layers) {
                layerIdToName[l.id] = l.name
                if (l.id == 0) continue  // "0" already written
                begin("LAYER"); allocHandle()
                raw("330", "1"); raw("100", "AcDbSymbolTableRecord"); raw("100", "AcDbLayerTableRecord")
                pair(2, l.name); pair(70, 0); pair(62, 7); pair(6, "Continuous")
            }
            raw("0", "ENDTAB")

            // ---- STYLE ----
            begin("TABLE");
            pair(2, "STYLE")
            raw("5", "5"); raw("330", "0"); raw("100", "AcDbSymbolTable"); pair(70, 1)
            begin("STYLE"); allocHandle()
            raw("330", "5"); raw("100", "AcDbSymbolTableRecord"); raw("100", "AcDbTextStyleTableRecord")
            pair(2, "Standard"); pair(70, 0)
            pair(40, 0.0); pair(41, 1.0); pair(50, 0.0); pair(71, 0); pair(42, 2.5)
            pair(3, "simhei.ttf"); pair(4, "")
            raw("0", "ENDTAB")

            // ---- APPID ----
            begin("TABLE");
            pair(2, "APPID")
            raw("5", "3"); raw("330", "0"); raw("100", "AcDbSymbolTable"); pair(70, 1)
            begin("APPID"); allocHandle()
            raw("330", "3"); raw("100", "AcDbSymbolTableRecord"); raw("100", "AcDbRegAppTableRecord")
            pair(2, "ACAD"); pair(70, 0)
            raw("0", "ENDTAB")

            // ---- DIMSTYLE ----
            begin("TABLE");
            pair(2, "DIMSTYLE")
            raw("5", "4"); raw("330", "0"); raw("100", "AcDbSymbolTable"); pair(70, 1)
            begin("DIMSTYLE"); allocHandle()
            raw("330", "4"); raw("100", "AcDbSymbolTableRecord"); raw("100", "AcDbDimStyleTableRecord")
            pair(2, "Standard"); pair(70, 0); pair(3, ""); pair(4, "")
            raw("0", "ENDTAB")

            // ---- BLOCK_RECORD ----
            begin("TABLE")
            pair(2, "BLOCK_RECORD")
            raw("5", BLOCK_RECORD_TABLE_HANDLE); raw("330", "0"); raw("100", "AcDbSymbolTable"); pair(70, 2)
            // *Model_Space
            begin("BLOCK_RECORD")
            modelSpaceBlockRecHandle = allocHandleWithOwner("330", BLOCK_RECORD_TABLE_HANDLE)
            raw("100", "AcDbSymbolTableRecord"); raw("100", "AcDbBlockTableRecord")
            pair(2, "*Model_Space")
            // *Paper_Space
            begin("BLOCK_RECORD")
            paperSpaceBlockRecHandle = allocHandleWithOwner("330", BLOCK_RECORD_TABLE_HANDLE)
            raw("100", "AcDbSymbolTableRecord"); raw("100", "AcDbBlockTableRecord")
            pair(2, "*Paper_Space")
            raw("0", "ENDTAB")

            // ---- VIEW (empty) ----
            begin("TABLE");
            pair(2, "VIEW")
            raw("5", allocHandleStr()); raw("330", "0"); raw("100", "AcDbSymbolTable"); pair(70, 0)
            raw("0", "ENDTAB")

            // ---- UCS (empty) ----
            begin("TABLE");
            pair(2, "UCS")
            raw("5", allocHandleStr()); raw("330", "0"); raw("100", "AcDbSymbolTable"); pair(70, 0)
            raw("0", "ENDTAB")

        }

        section("BLOCKS") {
            // *Model_Space BLOCK
            begin("BLOCK")
            allocHandleWithOwner("330", modelSpaceBlockRecHandle)
            raw("100", "AcDbEntity"); pair(8, "0"); raw("100", "AcDbBlockBegin")
            pair(2, "*Model_Space"); pair(70, 0)
            pair(10, 0.0); pair(20, 0.0); pair(30, 0.0)
            pair(3, "*Model_Space"); pair(1, "")
            begin("ENDBLK")
            allocHandleWithOwner("330", modelSpaceBlockRecHandle)
            raw("100", "AcDbEntity"); pair(8, "0")

            // *Paper_Space BLOCK
            begin("BLOCK")
            allocHandleWithOwner("330", paperSpaceBlockRecHandle)
            raw("100", "AcDbEntity"); pair(8, "0"); raw("100", "AcDbBlockBegin")
            pair(2, "*Paper_Space"); pair(70, 0)
            pair(10, 0.0); pair(20, 0.0); pair(30, 0.0)
            pair(3, "*Paper_Space"); pair(1, "")
            begin("ENDBLK")
            allocHandleWithOwner("330", paperSpaceBlockRecHandle)
            raw("100", "AcDbEntity"); pair(8, "0")
        }

        section("ENTITIES") {
            val visibleLayers = layers.filter { it.isVisible }.map { it.id }.toSet()
            var writtenCount = 0
            for (p in primitives) {
                if (p.layerId !in visibleLayers && visibleLayers.isNotEmpty()) continue
                currentLayerName = layers.find { it.id == p.layerId }?.name ?: "0"
                val aci = colorToAci(p.color)
                when (p) {
                    is DrawingPrimitive.FreehandPath -> writePolyline(p, aci, p.lineStyle)
                    is DrawingPrimitive.RectanglePrimitive -> writeRect(p, aci, p.lineStyle)
                    is DrawingPrimitive.CirclePrimitive -> writeCircle(p, aci, p.lineStyle)
                    is DrawingPrimitive.LinePrimitive -> writeLine(p, aci, p.lineStyle)
                    is DrawingPrimitive.NumberLabelPrimitive -> writeNumber(p, aci)
                    is DrawingPrimitive.TextPrimitive -> writeText(p, aci)
                    is DrawingPrimitive.RangeLabelPrimitive -> writeRange(p, aci)
                    is DrawingPrimitive.BlockRefPrimitive -> {
                        val bd = blockDefs.find { it.id == p.blockDefId }
                        if (bd != null) {
                            for (cp in bd.primitives) {
                                writeTransformedPrimitive(cp, aci, p)
                            }
                        }
                    }
                    else -> {}
                }
            }
        }

        // ---- OBJECTS (root dictionary + all sub-dictionaries) ----
        section("OBJECTS") {
            // Pre-allocate handles for sub-dictionaries and content objects
            // These are allocated after entities, in the order they'll be written
            colorDictH = allocHandleStr()
            groupDictH = allocHandleStr()
            materialDictH = allocHandleStr()
            mleaderstyleDictH = allocHandleStr()
            mlinestyleDictH = allocHandleStr()
            plotSettingsDictH = allocHandleStr()
            plotStyleNameH = allocHandleStr()
            plotStylePlaceholderH = allocHandleStr()
            scaleListDictH = allocHandleStr()
            tableStyleDictH = allocHandleStr()
            visualStyleDictH = allocHandleStr()
            paperSpaceLayoutH = allocHandleStr()
            matByBlockH = allocHandleStr()
            matByLayerH = allocHandleStr()
            matGlobalH = allocHandleStr()
            mlinestyleStdH = allocHandleStr()
            mleaderstyleStdH = allocHandleStr()

            // Root dictionary — master dictionary for all named-object lookup trees
            begin("DICTIONARY")
            raw("5", ROOT_HANDLE); raw("330", "0"); raw("100", "AcDbDictionary"); pair(281, 1)
            raw("3", "ACAD_COLOR"); raw("350", colorDictH)
            raw("3", "ACAD_GROUP"); raw("350", groupDictH)
            raw("3", "ACAD_LAYOUT"); raw("350", LAYOUT_DICT_HANDLE)
            raw("3", "ACAD_MATERIAL"); raw("350", materialDictH)
            raw("3", "ACAD_MLEADERSTYLE"); raw("350", mleaderstyleDictH)
            raw("3", "ACAD_MLINESTYLE"); raw("350", mlinestyleDictH)
            raw("3", "ACAD_PLOTSETTINGS"); raw("350", plotSettingsDictH)
            raw("3", "ACAD_PLOTSTYLENAME"); raw("350", plotStyleNameH)
            raw("3", "ACAD_SCALELIST"); raw("350", scaleListDictH)
            raw("3", "ACAD_TABLESTYLE"); raw("350", tableStyleDictH)
            raw("3", "ACAD_VISUALSTYLE"); raw("350", visualStyleDictH)

            // ACAD_LAYOUT dictionary — contains Model and *Paper_Space layouts
            begin("DICTIONARY")
            raw("5", LAYOUT_DICT_HANDLE)
            raw("330", ROOT_HANDLE); raw("100", "AcDbDictionary"); pair(281, 1)
            // Pre-allocate handle for Model LAYOUT
            modelLayoutHandle = Integer.toHexString(nextHandle++).uppercase()
            raw("3", "Model"); raw("350", modelLayoutHandle)
            raw("3", "*Paper_Space"); raw("350", paperSpaceLayoutH)

            // ACAD_COLOR dictionary (empty)
            begin("DICTIONARY"); raw("5", colorDictH)
            raw("330", ROOT_HANDLE); raw("100", "AcDbDictionary"); pair(281, 1)

            // ACAD_GROUP dictionary (empty)
            begin("DICTIONARY"); raw("5", groupDictH)
            raw("330", ROOT_HANDLE); raw("100", "AcDbDictionary"); pair(281, 1)

            // ACAD_MATERIAL dictionary
            begin("DICTIONARY"); raw("5", materialDictH)
            raw("330", ROOT_HANDLE); raw("100", "AcDbDictionary"); pair(281, 1)
            raw("3", "ByBlock"); raw("350", matByBlockH)
            raw("3", "ByLayer"); raw("350", matByLayerH)
            raw("3", "Global"); raw("350", matGlobalH)

            // ACAD_MLEADERSTYLE dictionary
            begin("DICTIONARY"); raw("5", mleaderstyleDictH)
            raw("330", ROOT_HANDLE); raw("100", "AcDbDictionary"); pair(281, 1)
            raw("3", "Standard"); raw("350", mleaderstyleStdH)

            // ACAD_MLINESTYLE dictionary
            begin("DICTIONARY"); raw("5", mlinestyleDictH)
            raw("330", ROOT_HANDLE); raw("100", "AcDbDictionary"); pair(281, 1)
            raw("3", "Standard"); raw("350", mlinestyleStdH)

            // ACAD_PLOTSETTINGS dictionary (empty)
            begin("DICTIONARY"); raw("5", plotSettingsDictH)
            raw("330", ROOT_HANDLE); raw("100", "AcDbDictionary"); pair(281, 1)

            // ACAD_PLOTSTYLENAME (ACDBDICTIONARYWDFLT with placeholder)
            begin("ACDBDICTIONARYWDFLT")
            raw("5", plotStyleNameH); raw("330", ROOT_HANDLE)
            raw("100", "AcDbDictionary"); pair(281, 1)
            raw("100", "AcDbDictionaryWithDefault"); pair(340, plotStylePlaceholderH)
            // ACDBPLACEHOLDER for default plot style
            begin("ACDBPLACEHOLDER")
            raw("5", plotStylePlaceholderH); raw("330", ROOT_HANDLE)
            raw("100", "AcDbPlaceholder")

            // ACAD_SCALELIST dictionary (empty)
            begin("DICTIONARY"); raw("5", scaleListDictH)
            raw("330", ROOT_HANDLE); raw("100", "AcDbDictionary"); pair(281, 1)

            // ACAD_TABLESTYLE dictionary (empty)
            begin("DICTIONARY"); raw("5", tableStyleDictH)
            raw("330", ROOT_HANDLE); raw("100", "AcDbDictionary"); pair(281, 1)

            // ACAD_VISUALSTYLE dictionary (empty)
            begin("DICTIONARY"); raw("5", visualStyleDictH)
            raw("330", ROOT_HANDLE); raw("100", "AcDbDictionary"); pair(281, 1)

            // Model LAYOUT
            begin("LAYOUT")
            raw("5", modelLayoutHandle); raw("330", LAYOUT_DICT_HANDLE)
            raw("100", "AcDbPlotSettings")
            pair(1, ""); pair(4, "A3"); pair(6, "")
            pair(40, 7.5); pair(41, 20.0); pair(42, 7.5); pair(43, 20.0)
            pair(44, 420.0); pair(45, 297.0); pair(46, 0.0); pair(47, 0.0); pair(48, 0.0); pair(49, 0.0)
            pair(140, 0.0); pair(141, 0.0); pair(142, 1.0); pair(143, 1.0)
            pair(70, 1024); pair(72, 1); pair(73, 0); pair(74, 5); raw("7", ""); pair(75, 16)
            pair(76, 0); pair(77, 2); pair(78, 300)
            pair(147, 1.0); pair(148, 0.0); pair(149, 0.0)
            raw("100", "AcDbLayout")
            raw("1", "Model"); pair(70, 1); pair(71, 0)
            pair(10, 0.0); pair(20, 0.0); pair(11, 420.0); pair(21, 297.0)
            pair(12, 0.0); pair(22, 0.0); pair(32, 0.0)
            pair(14, 1e20); pair(24, 1e20); pair(34, 1e20)
            pair(15, -1e20); pair(25, -1e20); pair(35, -1e20)
            pair(146, 0.0); pair(13, 0.0); pair(23, 0.0); pair(33, 0.0)
            pair(16, 1.0); pair(26, 0.0); pair(36, 0.0)
            pair(17, 0.0); pair(27, 1.0); pair(37, 0.0)
            pair(76, 1)
            raw("330", modelSpaceBlockRecHandle)

            // *Paper_Space LAYOUT
            begin("LAYOUT")
            raw("5", paperSpaceLayoutH); raw("330", LAYOUT_DICT_HANDLE)
            raw("100", "AcDbPlotSettings")
            pair(1, ""); pair(4, "A3"); pair(6, "")
            pair(40, 7.5); pair(41, 20.0); pair(42, 7.5); pair(43, 20.0)
            pair(44, 420.0); pair(45, 297.0); pair(46, 0.0); pair(47, 0.0); pair(48, 0.0); pair(49, 0.0)
            pair(140, 0.0); pair(141, 0.0); pair(142, 1.0); pair(143, 1.0)
            pair(70, 1024); pair(72, 1); pair(73, 0); pair(74, 5); raw("7", ""); pair(75, 16)
            pair(76, 0); pair(77, 2); pair(78, 300)
            pair(147, 1.0); pair(148, 0.0); pair(149, 0.0)
            raw("100", "AcDbLayout")
            raw("1", "*Paper_Space"); pair(70, 1); pair(71, 1)  // 71=1 for paper space
            pair(10, 0.0); pair(20, 0.0); pair(11, 420.0); pair(21, 297.0)
            pair(12, 0.0); pair(22, 0.0); pair(32, 0.0)
            pair(14, 1e20); pair(24, 1e20); pair(34, 1e20)
            pair(15, -1e20); pair(25, -1e20); pair(35, -1e20)
            pair(146, 0.0); pair(13, 0.0); pair(23, 0.0); pair(33, 0.0)
            pair(16, 1.0); pair(26, 0.0); pair(36, 0.0)
            pair(17, 0.0); pair(27, 1.0); pair(37, 0.0)
            pair(76, 1)
            raw("330", paperSpaceBlockRecHandle)

            // MATERIAL ByBlock
            begin("MATERIAL"); raw("5", matByBlockH); raw("330", materialDictH)
            raw("100", "AcDbMaterial")
            raw("1", "ByBlock"); pair(40, 0.0); pair(45, 0.0)

            // MATERIAL ByLayer
            begin("MATERIAL"); raw("5", matByLayerH); raw("330", materialDictH)
            raw("100", "AcDbMaterial")
            raw("1", "ByLayer"); pair(40, 0.0); pair(45, 0.0)

            // MATERIAL Global
            begin("MATERIAL"); raw("5", matGlobalH); raw("330", materialDictH)
            raw("100", "AcDbMaterial")
            raw("1", "Global"); pair(40, 0.0); pair(45, 0.0)

            // MLINESTYLE Standard
            begin("MLINESTYLE"); raw("5", mlinestyleStdH); raw("330", mlinestyleDictH)
            raw("100", "AcDbMLineStyle")
            pair(2, "Standard"); pair(70, 0); pair(3, "")
            pair(62, 256); pair(51, 90.0); pair(52, 90.0)
            pair(71, 2)  // 2 elements
            pair(49, 0.5); pair(62, 256); pair(6, "BYLAYER")  // element 0
            pair(49, -0.5); pair(62, 256); pair(6, "BYLAYER")  // element 1

            // MLEADERSTYLE Standard
            begin("MLEADERSTYLE"); raw("5", mleaderstyleStdH); raw("330", mleaderstyleDictH)
            raw("100", "AcDbMLeaderStyle")
            pair(179, 2); pair(170, 2); pair(171, 1); pair(172, 0)
            pair(90, 2); pair(40, 0.0); pair(41, 0.0); pair(173, 1)
            raw("91", "-1056964608"); raw("92", "-2")
            pair(290, 1); pair(42, 2.0)
            pair(291, 1); pair(43, 8.0)
            pair(3, "Standard"); pair(44, 4.0); pair(300, "")
            raw("342", "0")  // style pointer (empty for default)
            pair(174, 1); pair(175, 1); pair(176, 0); pair(178, 1)
            raw("93", "-1056964608"); pair(45, 4.0)
            pair(292, 0); pair(297, 0); pair(46, 4.0)
            raw("94", "-1056964608"); pair(47, 1.0); pair(49, 1.0)
            pair(140, 1.0); pair(294, 1); pair(141, 0.0)
        }

        raw("0", "EOF")
        writer.close()
    }

    private fun writePolyline(p: DrawingPrimitive.FreehandPath, aci: Int, ls: LineStyle) {
        if (p.points.size < 2) return
        if (ls.type == LineType.LIGHTNING) {
            // Lightning: use line segments + X marks (as before)
            for (i in 0 until p.points.size - 1) {
                val p1 = p.points[i]; val p2 = p.points[i + 1]
                line(p1.x, p1.y, p2.x, p2.y, aci, ls)
            }
        } else {
            // Regular path: use smooth SPLINE instead of line segments
            writeSpline(p.points, aci, p.isClosed)
        }
    }

    /** Write a smooth SPLINE through the given points (interpolating fit points). */
    private fun writeSpline(points: List<Point2D>, aci: Int, isClosed: Boolean) {
        val n = points.size
        if (n < 2) return
        val degree = when {
            n >= 4 -> 3    // cubic
            n == 3 -> 2    // quadratic
            else -> 1      // linear (n == 2)
        }
        val knotCount = n + degree + 1
        val flags = (if (isClosed) 1 else 0) or 8  // closed + planar

        begin("SPLINE")
        allocHandleWithOwner("330", modelSpaceBlockRecHandle)
        raw("100", "AcDbEntity"); pair(8, currentLayerName); if (aci != 7) pair(62, aci)
        raw("100", "AcDbSpline")
        pair(210, 0.0); pair(220, 0.0); pair(230, 1.0)  // normal vector
        pair(70, flags)
        pair(71, degree)
        pair(72, knotCount)
        pair(73, 0)   // no control points
        pair(74, n)   // fit points

        // Clamped knot vector
        val interior = knotCount - 2 * (degree + 1)  // = n - degree - 1
        for (i in 0 until degree + 1) pair(40, 0.0)
        for (i in 1..interior) pair(40, i.toDouble() / (interior + 1))
        for (i in 0 until degree + 1) pair(40, 1.0)

        // Fit points (the spline passes through these)
        for (pt in points) {
            pair(11, pt.x.toDouble()); pair(21, fy(pt.y)); pair(31, 0.0)
        }
    }

    private fun writeRect(p: DrawingPrimitive.RectanglePrimitive, aci: Int, ls: LineStyle) {
        val x1 = minOf(p.startX, p.endX); val y1 = minOf(p.startY, p.endY)
        val x2 = maxOf(p.startX, p.endX); val y2 = maxOf(p.startY, p.endY)
        if (kotlin.math.abs(p.rotation) < 0.001f) {
            line(x1, y1, x2, y1, aci, ls)
            line(x2, y1, x2, y2, aci, ls)
            line(x2, y2, x1, y2, aci, ls)
            line(x1, y2, x1, y1, aci, ls)
        } else {
            val cx = (x1 + x2) / 2f; val cy = (y1 + y2) / 2f
            val cosR = kotlin.math.cos(p.rotation); val sinR = kotlin.math.sin(p.rotation)
            fun rot(wx: Float, wy: Float): Pair<Float, Float> {
                val dx = wx - cx; val dy = wy - cy
                val rx = cx + dx * cosR - dy * sinR
                val ry = cy + dx * sinR + dy * cosR
                return rx to ry
            }
            val c0 = rot(x1, y1); val c1 = rot(x2, y1)
            val c2 = rot(x2, y2); val c3 = rot(x1, y2)
            line(c0.first, c0.second, c1.first, c1.second, aci, ls)
            line(c1.first, c1.second, c2.first, c2.second, aci, ls)
            line(c2.first, c2.second, c3.first, c3.second, aci, ls)
            line(c3.first, c3.second, c0.first, c0.second, aci, ls)
        }
    }

    private fun writeCircle(p: DrawingPrimitive.CirclePrimitive, aci: Int, ls: LineStyle) {
        val rx = p.radiusX.toDouble()
        val ry = p.radiusY.toDouble()
        if (rx == ry || abs(rx - ry) < 0.001) {
            // True circle
            begin("CIRCLE")
            allocHandleWithOwner("330", modelSpaceBlockRecHandle)
            raw("100", "AcDbEntity"); pair(8, currentLayerName); if (aci != 7) pair(62, aci)
            raw("100", "AcDbCircle")
            pair(10, p.centerX.toDouble()); pair(20, fy(p.centerY)); pair(30, 0.0)
            pair(40, rx)
            if (ls.type == LineType.LIGHTNING) writeCircleX(p.centerX, p.centerY, rx, ry, aci)
        } else {
            // Ellipse
            begin("ELLIPSE")
            allocHandleWithOwner("330", modelSpaceBlockRecHandle)
            raw("100", "AcDbEntity"); pair(8, currentLayerName); if (aci != 7) pair(62, aci)
            raw("100", "AcDbEllipse")
            pair(10, p.centerX.toDouble()); pair(20, fy(p.centerY)); pair(30, 0.0)
            if (rx >= ry) {
                pair(11, rx); pair(21, 0.0); pair(31, 0.0)
                pair(40, ry / rx)
            } else {
                pair(11, 0.0); pair(21, ry); pair(31, 0.0)
                pair(40, rx / ry)
            }
            pair(41, 0.0)
            pair(42, 6.283185307179586)
            if (ls.type == LineType.LIGHTNING) writeCircleX(p.centerX, p.centerY, rx, ry, aci)
        }
    }

    private fun writeLine(p: DrawingPrimitive.LinePrimitive, aci: Int, ls: LineStyle) {
        line(p.startX, p.startY, p.endX, p.endY, aci, ls)
    }

    /** Write a number label as DXF TEXT entity. */
    private fun writeNumber(p: DrawingPrimitive.NumberLabelPrimitive, aci: Int) {
        begin("TEXT")
        allocHandleWithOwner("330", modelSpaceBlockRecHandle)
        raw("100", "AcDbEntity"); pair(8, currentLayerName); if (aci != 7) pair(62, aci)
        raw("100", "AcDbText")
        pair(10, p.x.toDouble()); pair(20, fy(p.y)); pair(30, 0.0)
        pair(40, p.fontSize.toDouble())
        raw("1", p.value.toString())
        if (p.rotation != 0f) pair(50, -p.rotation.toDouble() * 180.0 / kotlin.math.PI)  // rad→deg for DXF
        pair(7, "Standard")
        pair(72, 1); pair(73, 2)  // middle center
        pair(11, p.x.toDouble()); pair(21, fy(p.y)); pair(31, 0.0)  // alignment point
    }

    /** Write a text primitive as DXF TEXT entity. */
    private fun writeText(p: DrawingPrimitive.TextPrimitive, aci: Int) {
        begin("TEXT")
        allocHandleWithOwner("330", modelSpaceBlockRecHandle)
        raw("100", "AcDbEntity"); pair(8, currentLayerName); if (aci != 7) pair(62, aci)
        raw("100", "AcDbText")
        pair(10, p.x.toDouble()); pair(20, fy(p.y)); pair(30, 0.0)
        pair(40, p.fontSize.toDouble())
        raw("1", p.text.trimEnd('\n'))
        if (p.rotation != 0f) pair(50, -p.rotation.toDouble() * 180.0 / kotlin.math.PI)  // rad→deg for DXF
        pair(7, "Standard")
        pair(72, 1); pair(73, 2)  // middle center
        pair(11, p.x.toDouble()); pair(21, fy(p.y)); pair(31, 0.0)  // alignment point
    }

    /** Write a range label as 2 TEXT + 3 LINE (arrow shaft + head). */
    private fun writeRange(p: DrawingPrimitive.RangeLabelPrimitive, aci: Int) {
        val fs = p.fontSize.toDouble()
        val halfArrow = (40.0 * p.arrowSpan).coerceAtLeast(10.0)
        val gap = fs * 1.0
        val cx = p.x.toDouble(); val cy = fy(p.y)
        val rawY = p.y.toDouble()
        val isVertical = !p.horizontalOnly && kotlin.math.abs(p.rotation) > 0.01f
        val rot = -p.rotation.toDouble()

        if (isVertical) {
            // Vertical range: numbers stacked top→bottom, arrow vertical
            val startY = cy + halfArrow + gap
            val endY = cy - halfArrow - gap
            val label1 = p.startValue.toString()
            val label2 = p.endValue.toString()
            // Start TEXT
            begin("TEXT")
            allocHandleWithOwner("330", modelSpaceBlockRecHandle)
            raw("100", "AcDbEntity"); pair(8, currentLayerName); if (aci != 7) pair(62, aci)
            raw("100", "AcDbText")
            pair(10, cx); pair(20, if (p.reversed) endY else startY); pair(30, 0.0)
            pair(40, fs)
            raw("1", label1)
            pair(7, "Standard"); pair(72, 1); pair(73, 2)
            pair(11, cx); pair(21, if (p.reversed) endY else startY); pair(31, 0.0)
            // End TEXT
            begin("TEXT")
            allocHandleWithOwner("330", modelSpaceBlockRecHandle)
            raw("100", "AcDbEntity"); pair(8, currentLayerName); if (aci != 7) pair(62, aci)
            raw("100", "AcDbText")
            pair(10, cx); pair(20, if (p.reversed) startY else endY); pair(30, 0.0)
            pair(40, fs)
            raw("1", label2)
            pair(7, "Standard"); pair(72, 1); pair(73, 2)
            pair(11, cx); pair(21, if (p.reversed) startY else endY); pair(31, 0.0)
            // Vertical arrow shaft (top → bottom)
            val aStart = rawY - halfArrow; val aEnd = rawY + halfArrow
            rawLine(cx, aStart, cx, aEnd, aci)
            // Arrowhead at bottom (or top if reversed)
            val hs = maxOf(4.0, p.fontSize * 0.3)
            if (p.reversed) {
                rawLine(cx, aStart, cx - hs, aStart - hs, aci)
                rawLine(cx, aStart, cx + hs, aStart - hs, aci)
            } else {
                rawLine(cx, aEnd, cx - hs, aEnd - hs, aci)
                rawLine(cx, aEnd, cx + hs, aEnd - hs, aci)
            }
        } else {
            // Horizontal range
            val leftX = cx - halfArrow - gap
            val rightX = cx + halfArrow + gap
            val label1 = p.startValue.toString()
            val label2 = p.endValue.toString()
            // Start number TEXT
            begin("TEXT")
            allocHandleWithOwner("330", modelSpaceBlockRecHandle)
            raw("100", "AcDbEntity"); pair(8, currentLayerName); if (aci != 7) pair(62, aci)
            raw("100", "AcDbText")
            pair(10, if (p.reversed) rightX else leftX); pair(20, cy); pair(30, 0.0)
            pair(40, fs)
            raw("1", label1)
            if (rot != 0.0) pair(50, rot * 180.0 / kotlin.math.PI)
            pair(7, "Standard"); pair(72, 1); pair(73, 2)
            pair(11, if (p.reversed) rightX else leftX); pair(21, cy); pair(31, 0.0)
            // End number TEXT
            begin("TEXT")
            allocHandleWithOwner("330", modelSpaceBlockRecHandle)
            raw("100", "AcDbEntity"); pair(8, currentLayerName); if (aci != 7) pair(62, aci)
            raw("100", "AcDbText")
            pair(10, if (p.reversed) leftX else rightX); pair(20, cy); pair(30, 0.0)
            pair(40, fs)
            raw("1", label2)
            if (rot != 0.0) pair(50, rot * 180.0 / kotlin.math.PI)
            pair(7, "Standard"); pair(72, 1); pair(73, 2)
            pair(11, if (p.reversed) leftX else rightX); pair(21, cy); pair(31, 0.0)
            // Arrow shaft
            val ax1 = cx - halfArrow; val ax2 = cx + halfArrow
            rawLine(ax1, rawY, ax2, rawY, aci)
            // Arrowhead
            val hs = maxOf(4.0, p.fontSize * 0.3)
            if (p.reversed) {
                rawLine(ax1, rawY, ax1 + hs, rawY - hs, aci)
                rawLine(ax1, rawY, ax1 + hs, rawY + hs, aci)
            } else {
                rawLine(ax2, rawY, ax2 - hs, rawY - hs, aci)
                rawLine(ax2, rawY, ax2 - hs, rawY + hs, aci)
            }
        }
    }

    /** Write a single line segment with optional lightning X marks. */
    private fun line(x1: Float, y1: Float, x2: Float, y2: Float, aci: Int, ls: LineStyle = LineStyle()) {
        // Always write the base segment as solid (linetype only for DASHED)
        begin("LINE")
        allocHandleWithOwner("330", modelSpaceBlockRecHandle)
        raw("100", "AcDbEntity"); pair(8, currentLayerName); if (aci != 7) pair(62, aci)
        if (ls.type == LineType.DASHED) pair(6, "DASHED")
        raw("100", "AcDbLine")
        pair(10, x1.toDouble()); pair(20, fy(y1)); pair(30, 0.0)
        pair(11, x2.toDouble()); pair(21, fy(y2)); pair(31, 0.0)
        // Lightning: draw X marks as real geometry
        if (ls.type == LineType.LIGHTNING) writeSegX(x1, y1, x2, y2, aci)
    }

    // ── Lightning X marks as real LINE geometry ──────────────

    /** Write X marks along a line segment (for lightning style). */
    private fun writeSegX(x1: Float, y1: Float, x2: Float, y2: Float, aci: Int) {
        val dx = (x2 - x1).toDouble()
        val dy = (y2 - y1).toDouble()
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1.0) return
        val xSize = 16.0  // X mark half-size in DXF units
        val n = maxOf(2, (len / 120.0).toInt())  // interval doubled: 60→120
        val cos45 = 0.7071067812
        val sin45 = 0.7071067812
        for (k in 1..n) {
            val t = k.toDouble() / (n + 1)
            val mx = x1 + t * dx
            val my = y1 + t * dy
            // Two lines at ±45° to segment direction, forming a 90° ×
            val d1x = (dx * cos45 - dy * sin45) / len * xSize
            val d1y = (dx * sin45 + dy * cos45) / len * xSize
            val d2x = (dx * cos45 + dy * sin45) / len * xSize
            val d2y = (-dx * sin45 + dy * cos45) / len * xSize
            rawLine(mx - d1x, my - d1y, mx + d1x, my + d1y, aci)
            rawLine(mx - d2x, my - d2y, mx + d2x, my + d2y, aci)
        }
    }

    /** Write X marks around a circle/ellipse perimeter (for lightning style). */
    private fun writeCircleX(cx: Float, cy: Float, rx: Double, ry: Double, aci: Int) {
        val n = maxOf(4, ((2 * 3.14159 * maxOf(rx, ry)) / 120.0).toInt())  // halved density
        val xSize = 16.0
        val cos45 = 0.7071067812
        val sin45 = 0.7071067812
        for (k in 0 until n) {
            val angle = 2 * 3.14159 * k / n
            val mx = cx + rx * cos(angle)
            val my = cy + ry * sin(angle)
            // Tangent direction at this point on ellipse
            val tx = -ry * sin(angle)
            val ty = rx * cos(angle)
            val tlen = sqrt(tx * tx + ty * ty)
            if (tlen > 0.001) {
                // Two lines at ±45° to the tangent, forming a 90° ×
                val d1x = (tx * cos45 - ty * sin45) / tlen * xSize
                val d1y = (tx * sin45 + ty * cos45) / tlen * xSize
                val d2x = (tx * cos45 + ty * sin45) / tlen * xSize
                val d2y = (-tx * sin45 + ty * cos45) / tlen * xSize
                rawLine(mx - d1x, my - d1y, mx + d1x, my + d1y, aci)
                rawLine(mx - d2x, my - d2y, mx + d2x, my + d2y, aci)
            }
        }
    }

    /** Write a raw LINE entity (no styling, just geometry). */
    private fun rawLine(x1: Double, y1: Double, x2: Double, y2: Double, aci: Int) {
        begin("LINE")
        allocHandleWithOwner("330", modelSpaceBlockRecHandle)
        raw("100", "AcDbEntity"); pair(8, currentLayerName); if (aci != 7) pair(62, aci)
        raw("100", "AcDbLine")
        pair(10, x1); pair(20, fy(y1)); pair(30, 0.0)
        pair(11, x2); pair(21, fy(y2)); pair(31, 0.0)
    }

    /** Negate Y to convert from screen coords (Y down) to DXF coords (Y up). */
    private fun fy(y: Float) = -y.toDouble()
    private fun fy(y: Double) = -y

    /** Map Scheda LineType to DXF linetype name. */
    private fun ltName(ls: LineStyle): String = when (ls.type) {
        LineType.DASHED -> "DASHED"
        LineType.LIGHTNING -> "LIGHTNING"
        LineType.SOLID -> "Continuous"
    }

    private fun section(name: String, block: () -> Unit) {
        raw("0", "SECTION"); raw("2", name); block(); raw("0", "ENDSEC")
    }

    private fun begin(type: String) { raw("0", type) }
    private fun pair(code: Int, value: Int) { raw(code.toString(), value.toString()) }
    private fun pair(code: Int, value: Double) { raw(code.toString(), d2s(value)) }
    private fun pair(code: Int, value: String) { raw(code.toString(), value) }

    private fun raw(code: String, value: String) {
        val padded = code.padStart(3)  // DXF 组码右对齐 3 字符
        writer.write(padded); writer.write(lineSep)
        writer.write(value); writer.write(lineSep)
    }

    private fun d2s(d: Double): String {
        val s = String.format(Locale.US, "%.3f", d)
        return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
    }

    private fun Double.pow() = this * this

    private fun colorToAci(color: androidx.compose.ui.graphics.Color): Int {
        val r = (color.red * 255f).roundToInt().coerceIn(0, 255)
        val g = (color.green * 255f).roundToInt().coerceIn(0, 255)
        val b = (color.blue * 255f).roundToInt().coerceIn(0, 255)
        data class Rgb(val r: Int, val g: Int, val b: Int, val aci: Int)
        val palette = listOf(
            Rgb(255,0,0,1), Rgb(255,255,0,2), Rgb(0,255,0,3), Rgb(0,255,255,4),
            Rgb(0,0,255,5), Rgb(255,0,255,6), Rgb(255,255,255,7), Rgb(0,0,0,7),
            Rgb(128,128,128,8), Rgb(192,192,192,9), Rgb(255,165,0,40), Rgb(165,42,42,14)
        )
        return palette.minByOrNull { val dr=it.r-r; val dg=it.g-g; val db=it.b-b; dr*dr+dg*dg+db*db }?.aci ?: 7
    }

    // ── Block reference helpers ──
    private fun countEntityHandles(p: DrawingPrimitive): Int = when (p) {
        is DrawingPrimitive.FreehandPath -> maxOf(0, p.points.size - 1)
        is DrawingPrimitive.RectanglePrimitive -> 4
        is DrawingPrimitive.CirclePrimitive -> 1
        is DrawingPrimitive.LinePrimitive -> 1
        is DrawingPrimitive.NumberLabelPrimitive -> 1
        is DrawingPrimitive.TextPrimitive -> 1
        is DrawingPrimitive.RangeLabelPrimitive -> 5
        is DrawingPrimitive.BlockRefPrimitive -> 0  // not recursed here
    }

    private fun DxfWriter.writeTransformedPrimitive(
        p: DrawingPrimitive, aci: Int, ref: DrawingPrimitive.BlockRefPrimitive
    ) {
        val cosR = cos(ref.rotation.toDouble())
        val sinR = sin(ref.rotation.toDouble())
        fun tx(wx: Float, wy: Float): Pair<Float, Float> {
            val dx = wx * cosR - wy * sinR
            val dy = wx * sinR + wy * cosR
            return (ref.x + dx.toFloat() * ref.scale) to (ref.y + dy.toFloat() * ref.scale)
        }
        fun fy(y: Float) = -y.toDouble()

        when (p) {
            is DrawingPrimitive.FreehandPath -> {
                if (p.points.size < 2) return
                writeSpline(p.points.map { pt ->
                    val (nx, ny) = tx(pt.x, pt.y); Point2D(nx, ny)
                }, aci, p.isClosed)
            }
            is DrawingPrimitive.RectanglePrimitive -> {
                val (sx, sy) = tx(p.startX, p.startY)
                val (ex, ey) = tx(p.endX, p.endY)
                writeLineSeg(fy(sx), sy, fy(ex), sy, aci)
                writeLineSeg(fy(ex), sy, fy(ex), ey, aci)
                writeLineSeg(fy(ex), ey, fy(sx), ey, aci)
                writeLineSeg(fy(sx), ey, fy(sx), sy, aci)
            }
            is DrawingPrimitive.CirclePrimitive -> {
                val (cx, cy) = tx(p.centerX, p.centerY)
                val r = maxOf(abs(p.endX - p.centerX), abs(p.endY - p.centerY)) * ref.scale
                begin("CIRCLE"); pair(8, currentLayerName); pair(62, aci)
                pair(10, fy(cx)); pair(20, cy.toDouble()); pair(30, 0.0)
                pair(40, r.toDouble())
            }
            is DrawingPrimitive.LinePrimitive -> {
                val (sx, sy) = tx(p.startX, p.startY)
                val (ex, ey) = tx(p.endX, p.endY)
                writeLineSeg(fy(sx), sy, fy(ex), ey, aci)
            }
            else -> {}
        }
    }

    private fun writeLineSeg(x1: Double, y1: Float, x2: Double, y2: Float, aci: Int) {
        begin("LINE"); pair(8, currentLayerName); pair(62, aci)
        pair(10, x1); pair(20, y1.toDouble()); pair(30, 0.0)
        pair(11, x2); pair(21, y2.toDouble()); pair(31, 0.0)
    }
}
