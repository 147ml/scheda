# Phase 2+3: ViewModel Unification + File Consolidation

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move eraser hit-detection and distance computation into the Primitive sealed interface, simplify the ViewModel, and consolidate small model files.

**Architecture:** Add `hitTest` and `distanceTo` methods to the `Primitive` sealed interface with implementations in each data class. Replace the ViewModel's type-specific eraser functions with a simple loop calling `hitTest`. Move `NumberLabelState`/`NumberLabelInstance`/`RangeLabelState` from `NumberLabel.kt` into `Primitive.kt`.

**Tech Stack:** Kotlin, Android Compose

## Global Constraints

1. All changes in ONE session
2. Read current code before modifying
3. Verify compilation after changes
4. Preserve existing eraser behavior (fine-erase splitting stays in ViewModel)

---

## Task 1: Add hitTest to Primitive sealed interface

**Covers:** Phase 2 Steps 1-2

**Files:**
- Modify: `app/src/main/java/com/scheda/app/model/Primitive.kt:26-35` (sealed interface)
- Modify: `app/src/main/java/com/scheda/app/model/Primitive.kt` (add to each data class)

**Interfaces:**
- Consumes: existing `shapeHitByEraser`, `textHitByEraser`, `circleToPoints` patterns from ViewModel
- Produces: `hitTest(eraser: Point2D, radiusSq: Float): Boolean` on Primitive

- [ ] **Step 1: Add hitTest to sealed interface**

```kotlin
sealed interface Primitive {
    val color: Color
    val strokeWidth: Float
    val layerId: Int
    val lineStyle: LineStyle
    val lineScaleFactor: Float

    fun computeBounds(): Bounds
    fun applyTransform(pe: PendingEdit): Primitive
    fun hitTest(eraser: Point2D, radiusSq: Float): Boolean
}
```

- [ ] **Step 2: Implement hitTest for FreehandPath**

Add after `applyTransform` in `FreehandPath`:
```kotlin
override fun hitTest(eraser: Point2D, radiusSq: Float): Boolean {
    if (points.size < 2) return false
    if (points.any { distSq(it, eraser) <= radiusSq }) return true
    val edgeCount = if (isClosed) points.size else points.size - 1
    for (i in 0 until edgeCount) {
        val a = points[i]
        val b = if (isClosed && i == edgeCount - 1) points[0] else points[i + 1]
        if (segmentCircleIntersectAll(a, b, eraser, radiusSq).isNotEmpty()) return true
    }
    return false
}
```

Also add helper functions at top of file (after imports):
```kotlin
private fun distSq(a: Point2D, b: Point2D): Float {
    val dx = a.x - b.x; val dy = a.y - b.y
    return dx * dx + dy * dy
}

private fun segmentCircleIntersectAll(p1: Point2D, p2: Point2D, center: Point2D, rSq: Float): List<Float> {
    val dx = p2.x - p1.x; val dy = p2.y - p1.y
    val fx = p1.x - center.x; val fy = p1.y - center.y
    val a = dx * dx + dy * dy
    if (a < 0.0001f) return if (distSq(p1, center) < rSq) listOf(0f) else emptyList()
    val b = 2f * (fx * dx + fy * dy)
    val c = fx * fx + fy * fy - rSq
    val disc = b * b - 4f * a * c
    if (disc < -1e-4f) return emptyList()
    if (disc < 1e-4f) return emptyList()
    val sqrtD = sqrt(disc)
    val t1 = (-b - sqrtD) / (2f * a)
    val t2 = (-b + sqrtD) / (2f * a)
    return buildList {
        if (t1 >= -1e-4f && t1 <= 1f + 1e-4f) add(t1.coerceIn(0f, 1f))
        if (t2 >= -1e-4f && t2 <= 1f + 1e-4f) add(t2.coerceIn(0f, 1f))
    }.sorted()
}
```

- [ ] **Step 3: Implement hitTest for Rectangle**

Add after `applyTransform` in `Rectangle`:
```kotlin
override fun hitTest(eraser: Point2D, radiusSq: Float): Boolean {
    val pts = rectToPoints()
    if (pts.any { distSq(it, eraser) <= radiusSq }) return true
    for (i in pts.indices) {
        val a = pts[i]; val b = pts[(i + 1) % pts.size]
        if (segmentCircleIntersectAll(a, b, eraser, radiusSq).isNotEmpty()) return true
    }
    return false
}

private fun rectToPoints(): List<Point2D> {
    val left = minOf(startX, endX); val top = minOf(startY, endY)
    val right = maxOf(startX, endX); val bottom = maxOf(startY, endY)
    val corners = if (abs(rotation) < 0.01f) {
        listOf(Point2D(left, top), Point2D(right, top), Point2D(right, bottom), Point2D(left, bottom))
    } else {
        val cx = (left + right) / 2f; val cy = (top + bottom) / 2f
        val hw = (right - left) / 2f; val hh = (bottom - top) / 2f
        val cosR = cos(rotation); val sinR = sin(rotation)
        fun rot(wx: Float, wy: Float): Point2D {
            val dx = wx - cx; val dy = wy - cy
            return Point2D(cx + dx * cosR - dy * sinR, cy + dx * sinR + dy * cosR)
        }
        listOf(rot(left, top), rot(right, top), rot(right, bottom), rot(left, bottom))
    }
    return corners
}
```

- [ ] **Step 4: Implement hitTest for Circle**

Add after `applyTransform` in `Circle`:
```kotlin
override fun hitTest(eraser: Point2D, radiusSq: Float): Boolean {
    val rx = abs(endX - centerX); val ry = abs(endY - centerY)
    val cosR = cos(rotation); val sinR = sin(rotation)
    val pts = (0 until 32).map { i ->
        val angle = 2f * PI.toFloat() * i / 32f
        val lx = rx * cos(angle); val ly = ry * sin(angle)
        Point2D(centerX + lx * cosR - ly * sinR, centerY + lx * sinR + ly * cosR)
    }
    if (pts.any { distSq(it, eraser) <= radiusSq }) return true
    for (i in pts.indices) {
        val a = pts[i]; val b = pts[(i + 1) % pts.size]
        if (segmentCircleIntersectAll(a, b, eraser, radiusSq).isNotEmpty()) return true
    }
    return false
}
```

- [ ] **Step 5: Implement hitTest for Line**

Add after `applyTransform` in `Line`:
```kotlin
override fun hitTest(eraser: Point2D, radiusSq: Float): Boolean {
    val a = Point2D(startX, startY); val b = Point2D(endX, endY)
    if (distSq(a, eraser) <= radiusSq || distSq(b, eraser) <= radiusSq) return true
    return segmentCircleIntersectAll(a, b, eraser, radiusSq).isNotEmpty()
}
```

- [ ] **Step 6: Implement hitTest for Text**

Add after `applyTransform` in `Text`:
```kotlin
override fun hitTest(eraser: Point2D, radiusSq: Float): Boolean {
    if (text.isEmpty()) return distSq(Point2D(x, y), eraser) <= radiusSq
    val paint = android.graphics.Paint().apply {
        textSize = fontSize * 1.3f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
    }
    val halfW = paint.measureText(text) / 2f
    val halfH = fontSize * 0.5f
    val cosR = cos(rotation); val sinR = sin(rotation)
    val corners = listOf(
        Point2D(-halfW, -halfH), Point2D(halfW, -halfH),
        Point2D(halfW, halfH), Point2D(-halfW, halfH)
    ).map { local ->
        val rx = local.x * cosR - local.y * sinR
        val ry = local.x * sinR + local.y * cosR
        Point2D(x + rx, y + ry)
    }
    return hitTestPolygon(corners, eraser, radiusSq)
}

private fun hitTestPolygon(pts: List<Point2D>, center: Point2D, rSq: Float): Boolean {
    if (pts.any { distSq(it, center) <= rSq }) return true
    for (i in pts.indices) {
        val a = pts[i]; val b = pts[(i + 1) % pts.size]
        if (segmentCircleIntersectAll(a, b, center, rSq).isNotEmpty()) return true
    }
    return false
}
```

- [ ] **Step 7: Implement hitTest for NumberLabel**

Add after `applyTransform` in `NumberLabel`:
```kotlin
override fun hitTest(eraser: Point2D, radiusSq: Float): Boolean {
    val paint = android.graphics.Paint().apply {
        textSize = fontSize * 1.3f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
    }
    val text = value.toString()
    val halfW = paint.measureText(text) / 2f
    val halfH = fontSize * 0.5f
    val cosR = cos(rotation); val sinR = sin(rotation)
    val corners = listOf(
        Point2D(-halfW, -halfH), Point2D(halfW, -halfH),
        Point2D(halfW, halfH), Point2D(-halfW, halfH)
    ).map { local ->
        val rx = local.x * cosR - local.y * sinR
        val ry = local.x * sinR + local.y * cosR
        Point2D(x + rx, y + ry)
    }
    return hitTestPolygon(corners, eraser, radiusSq)
}
```

- [ ] **Step 8: Implement hitTest for RangeLabel**

Add after `applyTransform` in `RangeLabel`:
```kotlin
override fun hitTest(eraser: Point2D, radiusSq: Float): Boolean {
    val arrowLen = maxOf(80f * arrowSpan, 20f)
    val reach = arrowLen / 2f + fontSize * 2f
    val cosR = cos(rotation); val sinR = sin(rotation)
    val hx = reach * cosR; val hy = reach * sinR
    val a = Point2D(x - hx, y - hy); val b = Point2D(x + hx, y + hy)
    if (distSq(a, eraser) <= radiusSq || distSq(b, eraser) <= radiusSq) return true
    return segmentCircleIntersectAll(a, b, eraser, radiusSq).isNotEmpty()
}
```

- [ ] **Step 9: Implement hitTest for BlockRef**

Add after `applyTransform` in `BlockRef`:
```kotlin
override fun hitTest(eraser: Point2D, radiusSq: Float): Boolean {
    val r = sqrt(radiusSq)
    val radius = 50f * scale
    val threshold = r + radius
    return distSq(Point2D(x, y), eraser) <= threshold * threshold
}
```

- [ ] **Step 10: Verify compilation**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 2: Simplify ViewModel eraser

**Covers:** Phase 2 Step 3

**Files:**
- Modify: `app/src/main/java/com/scheda/app/viewmodel/DrawingViewModel.kt:1977-2205`

**Interfaces:**
- Consumes: `Primitive.hitTest(eraser: Point2D, radiusSq: Float): Boolean`
- Produces: simplified `performErasure()` function

- [ ] **Step 1: Replace performErasure with hitTest-based loop**

Replace lines 1977-2015 with:
```kotlin
private fun performErasure(worldPoint: Point2D) {
    val rSq = _eraserRadius.value * _eraserRadius.value
    _eraserTouchPoint.value = worldPoint
    val activeLayer = _layers.find { it.id == _activeLayerId.value }
    if (activeLayer?.isLocked == true) return

    val toRemove = mutableListOf<Primitive>()
    val toAdd = mutableListOf<Primitive>()

    for (p in _primitives) {
        if (p.layerId != _activeLayerId.value) continue
        if (!p.hitTest(worldPoint, rSq)) continue
        toRemove.add(p)
        if (_fineEraseEnabled.value) {
            // Split logic stays in ViewModel for now
            when (p) {
                is FreehandPath -> {
                    val segments = splitFreehand(p.points, worldPoint, rSq, isClosed = p.isClosed)
                    for (seg in segments) { if (seg.size >= 2) toAdd.add(p.copy(points = seg)) }
                }
                is Rectangle -> {
                    val pts = rectToPoints(p)
                    val segments = splitFreehand(pts, worldPoint, rSq, isClosed = true)
                    for (seg in segments) {
                        if (seg.size >= 2) toAdd.add(FreehandPath(points = seg, color = p.color, strokeWidth = p.strokeWidth, layerId = p.layerId, lineStyle = p.lineStyle))
                    }
                }
                is Circle -> {
                    val pts = circleToPoints(p)
                    val segments = splitFreehand(pts, worldPoint, rSq, isClosed = true)
                    for (seg in segments) {
                        if (seg.size >= 2) toAdd.add(FreehandPath(points = seg, color = p.color, strokeWidth = p.strokeWidth, layerId = p.layerId, lineStyle = p.lineStyle))
                    }
                }
                is Line -> {
                    val pts = lineToPoints(p)
                    val segments = splitFreehand(pts, worldPoint, rSq, isClosed = false)
                    for (seg in segments) {
                        if (seg.size >= 2) toAdd.add(FreehandPath(points = seg, color = p.color, strokeWidth = p.strokeWidth, layerId = p.layerId, lineStyle = p.lineStyle))
                    }
                }
                else -> {}
            }
        }
    }

    if (toRemove.isNotEmpty() || toAdd.isNotEmpty()) {
        if (!_eraserUndoPushed) {
            pushUndo()
            _eraserUndoPushed = true
        }
        redoHistory.clear(); _canRedo.value = false
        _primitives.removeAll(toRemove)
        _primitives.addAll(toAdd)
        hasUnsavedChanges = true
    }
}
```

- [ ] **Step 2: Remove obsolete eraser helper functions**

Remove these private functions from DrawingViewModel.kt:
- `shapeHitByEraser` (lines 2092-2102)
- `textHitByEraser` (lines 2104-2125)
- `eraseFreehand` (lines 2129-2138)
- `eraseRect` (lines 2140-2152)
- `eraseCircle` (lines 2154-2166)
- `eraseLine` (lines 2168-2180)
- `eraseRangeLabel` (lines 2182-2190)
- `eraseBlockRef` (lines 2192-2205)
- `eraserHitBounds` (lines 2207-2251)

Keep these helpers (still used by fine-erase split logic):
- `distSq`
- `segmentCircleIntersectAll`
- `splitFreehand`
- `rectToPoints`
- `circleToPoints`
- `lineToPoints`

- [ ] **Step 3: Verify compilation**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 3: Add distanceTo to Primitive

**Covers:** Phase 2 Step 4

**Files:**
- Modify: `app/src/main/java/com/scheda/app/model/Primitive.kt` (sealed interface + data classes)
- Modify: `app/src/main/java/com/scheda/app/viewmodel/DrawingViewModel.kt:1550-1603` (replace pointToPrimitiveDist)

**Interfaces:**
- Consumes: existing `pointToPrimitiveDist` patterns
- Produces: `distanceTo(point: Point2D): Float` on Primitive

- [ ] **Step 1: Add distanceTo to sealed interface**

```kotlin
sealed interface Primitive {
    // ... existing fields ...
    fun hitTest(eraser: Point2D, radiusSq: Float): Boolean
    fun distanceTo(point: Point2D): Float
}
```

- [ ] **Step 2: Add helper for segment distance**

Add at top of Primitive.kt:
```kotlin
private fun distToSegment(pt: Point2D, a: Point2D, b: Point2D): Float {
    val abx = b.x - a.x; val aby = b.y - a.y
    val lenSq = abx * abx + aby * aby
    if (lenSq < 0.0001f) return sqrt((pt.x - a.x).squared() + (pt.y - a.y).squared())
    var t = ((pt.x - a.x) * abx + (pt.y - a.y) * aby) / lenSq
    t = t.coerceIn(0f, 1f)
    val px = a.x + t * abx; val py = a.y + t * aby
    return sqrt((pt.x - px).squared() + (pt.y - py).squared())
}

private fun Float.squared() = this * this
```

- [ ] **Step 3: Implement distanceTo for each type**

```kotlin
// FreehandPath
override fun distanceTo(point: Point2D): Float {
    if (points.size < 2) return Float.MAX_VALUE
    return points.zipWithNext().minOf { (a, b) -> distToSegment(point, a, b) }
}

// Rectangle
override fun distanceTo(point: Point2D): Float {
    val pts = rectToPoints()
    return (pts + pts.first()).zipWithNext().minOf { (a, b) -> distToSegment(point, a, b) }
}

// Circle
override fun distanceTo(point: Point2D): Float {
    val rx = abs(endX - centerX); val ry = abs(endY - centerY)
    val r = maxOf(rx, ry)
    val dc = sqrt((point.x - centerX).squared() + (point.y - centerY).squared())
    if (rx == ry || abs(rx - ry) < 0.01f) {
        abs(dc - r)
    } else {
        val cosR = cos(rotation); val sinR = sin(rotation)
        val dx = point.x - centerX; val dy = point.y - centerY
        val localX = dx * cosR + dy * sinR
        val localY = -dx * sinR + dy * cosR
        val angle = atan2(localY / ry, localX / rx)
        val nearestX = rx * cos(angle)
        val nearestY = ry * sin(angle)
        sqrt((localX - nearestX).squared() + (localY - nearestY).squared())
    }
}

// Line
override fun distanceTo(point: Point2D): Float {
    return distToSegment(point, Point2D(startX, startY), Point2D(endX, endY))
}

// Text
override fun distanceTo(point: Point2D): Float {
    val paint = android.graphics.Paint().apply {
        textSize = fontSize * 1.3f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
    }
    val halfW = paint.measureText(text) / 2f
    val halfH = fontSize * 0.5f
    val cosR = cos(rotation); val sinR = sin(rotation)
    val corners = listOf(
        Point2D(-halfW, -halfH), Point2D(halfW, -halfH),
        Point2D(halfW, halfH), Point2D(-halfW, halfH)
    ).map { local ->
        val rx = local.x * cosR - local.y * sinR
        val ry = local.x * sinR + local.y * cosR
        Point2D(x + rx, y + ry)
    }
    return (corners + corners.first()).zipWithNext().minOf { (a, b) -> distToSegment(point, a, b) }
}

// NumberLabel
override fun distanceTo(point: Point2D): Float {
    val paint = android.graphics.Paint().apply {
        textSize = fontSize * 1.3f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.CENTER
    }
    val text = value.toString()
    val halfW = paint.measureText(text) / 2f
    val halfH = fontSize * 0.5f
    val cosR = cos(rotation); val sinR = sin(rotation)
    val corners = listOf(
        Point2D(-halfW, -halfH), Point2D(halfW, -halfH),
        Point2D(halfW, halfH), Point2D(-halfW, halfH)
    ).map { local ->
        val rx = local.x * cosR - local.y * sinR
        val ry = local.x * sinR + local.y * cosR
        Point2D(x + rx, y + ry)
    }
    return (corners + corners.first()).zipWithNext().minOf { (a, b) -> distToSegment(point, a, b) }
}

// RangeLabel
override fun distanceTo(point: Point2D): Float {
    val arrowLen = maxOf(80f * arrowSpan, 20f)
    val reach = arrowLen / 2f + fontSize * 2f
    val cosR = cos(rotation); val sinR = sin(rotation)
    val hx = reach * cosR; val hy = reach * sinR
    return distToSegment(point, Point2D(x - hx, y - hy), Point2D(x + hx, y + hy))
}

// BlockRef
override fun distanceTo(point: Point2D): Float {
    return sqrt((point.x - x).squared() + (point.y - y).squared())
}
```

- [ ] **Step 4: Replace pointToPrimitiveDist in ViewModel**

In DrawingViewModel.kt, replace `pointToPrimitiveDist` function (lines 1550-1603) with:
```kotlin
private fun pointToPrimitiveDist(point: Point2D, p: Primitive): Float = p.distanceTo(point)
```

- [ ] **Step 5: Verify compilation**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 4: Consolidate NumberLabel.kt into Primitive.kt

**Covers:** Phase 3 Step 5

**Files:**
- Modify: `app/src/main/java/com/scheda/app/model/Primitive.kt` (add classes)
- Delete: `app/src/main/java/com/scheda/app/model/NumberLabel.kt`
- Modify: `app/src/main/java/com/scheda/app/viewmodel/DrawingViewModel.kt` (update imports)

**Interfaces:**
- Consumes: `NumberLabelState`, `NumberLabelInstance`, `RangeLabelState` from NumberLabel.kt
- Produces: these classes defined in Primitive.kt

- [ ] **Step 1: Add classes to Primitive.kt**

Add at the end of Primitive.kt (before the rendering functions):
```kotlin
// ═══════════════════════════════════════════════════════════
//  State classes (consolidated from NumberLabel.kt)
// ═══════════════════════════════════════════════════════════

data class NumberLabelState(
    val startFrom: Int = 1,
    val currentValue: Int = 1,
    val fontSize: Float = 30f,
    val horizontalOnly: Boolean = true,
    val pending: NumberLabelInstance? = null
)

data class NumberLabelInstance(
    val value: Int,
    val x: Float,
    val y: Float,
    val rotation: Float = 0f,
    val fontSize: Float = 30f
)

data class RangeLabelState(
    val startValue: Int = 1,
    val endValue: Int = 2,
    val fontSize: Float = 30f,
    val lastEndValue: Int = 1,
    val horizontalOnly: Boolean = true,
    val reversed: Boolean = false,
    val arrowSpan: Float = 1f
)
```

- [ ] **Step 2: Delete NumberLabel.kt**

Delete file: `app/src/main/java/com/scheda/app/model/NumberLabel.kt`

- [ ] **Step 3: Update imports in DrawingViewModel.kt**

The imports in DrawingViewModel.kt use `com.scheda.app.model.*` wildcard import, so no changes needed. Verify by checking that all usages compile.

- [ ] **Step 4: Verify compilation**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

## Task 5: Final verification

**Covers:** All phases

- [ ] **Step 1: Full build verification**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Check no stale references**

Search for any remaining imports of `NumberLabel.kt` classes:
```bash
grep -r "import com.scheda.app.model.NumberLabelState\|import com.scheda.app.model.RangeLabelState\|import com.scheda.app.model.NumberLabelInstance" app/
```
Expected: No results (wildcard import covers them)
