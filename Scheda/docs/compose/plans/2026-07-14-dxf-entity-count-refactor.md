# DXF Entity Count Refactor Implementation Plan

> [!NOTE]
> This document may not reflect the current implementation.
> See the final report for up-to-date state:
> [Final Report](../reports/dxf-entity-count-refactor.md)

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move DXF entity/handle count dispatch from DxfWriter.kt into Primitive sealed class.

**Architecture:** Add abstract methods to DrawingPrimitive, implement in each subclass, replace `when` blocks with method calls.

**Tech Stack:** Kotlin, Android

## Global Constraints
- Do NOT modify any other code besides Primitive.kt and DxfWriter.kt.
- Keep recursion for BlockRefPrimitive in DxfWriter.
- Keep `updateBounds` calls in DxfWriter.
- Maintain existing DXF output behavior.

---

### Task 1: Add implementations to Primitive.kt

**Covers:** [S3]

**Files:**
- Modify: `app/src/main/java/com/scheda/app/model/Primitive.kt`

**Interfaces:**
- Consumes: None
- Produces: `dxfEntityCount()` and `dxfHandleCount()` implementations for all 8 subclasses

- [ ] **Step 1: Add import for LineType**

```kotlin
import com.scheda.app.model.LineType
```

Add after existing imports.

- [ ] **Step 2: Implement FreehandPath.dxfEntityCount()**

Add inside FreehandPath data class:

```kotlin
override fun dxfEntityCount(): Int {
    if (lineStyle.type == LineType.LIGHTNING) {
        val segs = maxOf(0, points.size - 1)
        var extra = 0
        for (i in 0 until points.size - 1) {
            val dx = (points[i + 1].x - points[i].x).toDouble()
            val dy = (points[i + 1].y - points[i].y).toDouble()
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1.0) continue
            val n = maxOf(2, (len / 120.0).toInt())
            extra += 2 * n
        }
        return (segs + extra) * 8
    } else {
        return 1
    }
}
```

- [ ] **Step 3: Implement FreehandPath.dxfHandleCount()**

Add inside FreehandPath data class:

```kotlin
override fun dxfHandleCount(): Int = dxfEntityCount()
```

- [ ] **Step 4: Implement RectanglePrimitive.dxfEntityCount() and dxfHandleCount()**

Add inside RectanglePrimitive data class:

```kotlin
override fun dxfEntityCount(): Int = 4
override fun dxfHandleCount(): Int = 4
```

- [ ] **Step 5: Implement CirclePrimitive.dxfEntityCount() and dxfHandleCount()**

Add inside CirclePrimitive data class:

```kotlin
override fun dxfEntityCount(): Int = 1
override fun dxfHandleCount(): Int = 1
```

- [ ] **Step 6: Implement LinePrimitive.dxfEntityCount() and dxfHandleCount()**

Add inside LinePrimitive data class:

```kotlin
override fun dxfEntityCount(): Int {
    if (lineStyle.type == LineType.LIGHTNING) {
        val dx = (endX - startX).toDouble()
        val dy = (endY - startY).toDouble()
        val len = sqrt(dx * dx + dy * dy)
        val n = maxOf(2, (len / 120.0).toInt())
        return 1 + 2 * n
    } else {
        return 1
    }
}
override fun dxfHandleCount(): Int = dxfEntityCount()
```

- [ ] **Step 7: Implement NumberLabelPrimitive.dxfEntityCount() and dxfHandleCount()**

Add inside NumberLabelPrimitive data class:

```kotlin
override fun dxfEntityCount(): Int = 1
override fun dxfHandleCount(): Int = 1
```

- [ ] **Step 8: Implement TextPrimitive.dxfEntityCount() and dxfHandleCount()**

Add inside TextPrimitive data class:

```kotlin
override fun dxfEntityCount(): Int = 1
override fun dxfHandleCount(): Int = 1
```

- [ ] **Step 9: Implement RangeLabelPrimitive.dxfEntityCount() and dxfHandleCount()**

Add inside RangeLabelPrimitive data class:

```kotlin
override fun dxfEntityCount(): Int = 5
override fun dxfHandleCount(): Int = 5
```

- [ ] **Step 10: Implement BlockRefPrimitive.dxfEntityCount() and dxfHandleCount()**

Add inside BlockRefPrimitive data class:

```kotlin
override fun dxfEntityCount(): Int = 0
override fun dxfHandleCount(): Int = 1
```

- [ ] **Step 11: Verify compilation**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 12: Commit**

```bash
git add app/src/main/java/com/scheda/app/model/Primitive.kt
git commit -m "feat: add dxfEntityCount/dxfHandleCount implementations to Primitive subclasses"
```

---

### Task 2: Update DxfWriter.kt to use new methods

**Covers:** [S3]

**Files:**
- Modify: `app/src/main/java/com/scheda/app/export/DxfWriter.kt`

**Interfaces:**
- Consumes: `dxfEntityCount()` and `dxfHandleCount()` from Task 1
- Produces: Updated entity count logic

- [ ] **Step 1: Replace entity count `when` block in write() method**

Replace lines 111-151 (the `when (p)` block inside the for loop) with:

```kotlin
for (p in primitives) {
    if (p.layerId !in visibleLayerIds) continue
    entityCount += p.dxfEntityCount()
    when (p) {
        is DrawingPrimitive.FreehandPath -> {
            for (pt in p.points) updateBounds(pt.x, pt.y)
        }
        is DrawingPrimitive.RectanglePrimitive -> {
            updateBounds(p.startX, p.startY); updateBounds(p.endX, p.endY)
        }
        is DrawingPrimitive.CirclePrimitive -> {
            updateBounds(p.centerX - p.radiusX, p.centerY - p.radiusY)
            updateBounds(p.centerX + p.radiusX, p.centerY + p.radiusY)
        }
        is DrawingPrimitive.LinePrimitive -> {
            updateBounds(p.startX, p.startY); updateBounds(p.endX, p.endY)
        }
        is DrawingPrimitive.NumberLabelPrimitive -> {
            updateBounds(p.x, p.y)
        }
        is DrawingPrimitive.TextPrimitive -> {
            updateBounds(p.x, p.y)
        }
        is DrawingPrimitive.RangeLabelPrimitive -> {
            updateBounds(p.x, p.y)
        }
        is DrawingPrimitive.BlockRefPrimitive -> {
            val bd = blockDefs.find { it.id == p.blockDefId }
            if (bd != null) {
                for (cp in bd.primitives) {
                    entityCount += cp.dxfEntityCount()
                }
            }
        }
        else -> {}
    }
}
```

- [ ] **Step 2: Replace entity count `when` block in writeWithTemplate() method**

Replace lines 1192-1232 (the `when (p)` block inside the for loop) with similar pattern:

```kotlin
for (p in primitives) {
    if (p.layerId !in visibleLayerIds) continue
    usedLayerNames.add(layers.find { it.id == p.layerId }?.name ?: "0")
    entityCount += p.dxfEntityCount()
    when (p) {
        is DrawingPrimitive.FreehandPath -> {
            for (pt in p.points) updateBounds(pt.x, pt.y)
        }
        is DrawingPrimitive.RectanglePrimitive -> {
            updateBounds(p.startX, p.startY); updateBounds(p.endX, p.endY)
        }
        is DrawingPrimitive.CirclePrimitive -> {
            updateBounds(p.centerX - p.radiusX, p.centerY - p.radiusY)
            updateBounds(p.centerX + p.radiusX, p.centerY + p.radiusY)
        }
        is DrawingPrimitive.LinePrimitive -> {
            updateBounds(p.startX, p.startY); updateBounds(p.endX, p.endY)
        }
        is DrawingPrimitive.NumberLabelPrimitive -> {
            updateBounds(p.x, p.y)
        }
        is DrawingPrimitive.TextPrimitive -> {
            updateBounds(p.x, p.y)
        }
        is DrawingPrimitive.RangeLabelPrimitive -> {
            updateBounds(p.x, p.y)
        }
        is DrawingPrimitive.BlockRefPrimitive -> {
            val bd = blockDefs.find { it.id == p.blockDefId }
            if (bd != null) {
                for (cp in bd.primitives) {
                    entityCount += cp.dxfEntityCount()
                }
            }
        }
        else -> {}
    }
}
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/scheda/app/export/DxfWriter.kt
git commit -m "refactor: replace entity count when blocks with Primitive.dxfEntityCount()"
```