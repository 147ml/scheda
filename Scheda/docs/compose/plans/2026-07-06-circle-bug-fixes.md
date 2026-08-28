# Circle Bug Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use compose:subagent (recommended) or compose:execute to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 7 remaining Circle-related bugs across drawing, preview, export, selection, and bounds computation.

**Architecture:** Targeted fixes in existing functions — no new files or abstractions. Each bug is a localized code change in one function.

**Tech Stack:** Kotlin, Android Canvas, Compose, DXF export

## Global Constraints

- Do NOT touch unrelated code
- Show diff for each fix
- Bug 8 (applyTransform non-uniform scale) is NOT a bug — skip

---

### Task 1: DASHED line style for rotated circles (DrawingCanvas.kt)

**Files:**
- Modify: `app/src/main/java/com/scheda/app/ui/canvas/DrawingCanvas.kt:470-476`

**Fix:** Add `android.graphics.PathEffect` dash effect to the native paint when lineStyle is DASHED.

### Task 2: DASHED line style for rotated circles in preview (PostCreationOverlay.kt)

**Files:**
- Modify: `app/src/main/java/com/scheda/app/ui/canvas/PostCreationOverlay.kt:410-422`

**Fix:** Same as Task 1 — add dash pathEffect to native paint.

### Task 3: DXF Lightning X marks respect rotation (DxfWriter.kt)

**Files:**
- Modify: `app/src/main/java/com/scheda/app/export/DxfWriter.kt:646-678` (writeCircle)
- Modify: `app/src/main/java/com/scheda/app/export/DxfWriter.kt:858-881` (writeCircleX)

**Fix:** Pass rotation to writeCircleX and apply cosR/sinR to X mark positions.

### Task 4: Mirror negates Circle rotation (DrawingViewModel.kt)

**Files:**
- Modify: `app/src/main/java/com/scheda/app/viewmodel/DrawingViewModel.kt:1168`

**Fix:** Add `rotation = -p.rotation` to Circle copy in MIRROR action.

### Task 5: fenceHitsGeometry closes Circle perimeter (DrawingViewModel.kt)

**Files:**
- Modify: `app/src/main/java/com/scheda/app/viewmodel/DrawingViewModel.kt:2414-2417`

**Fix:** Add closing segment from last point back to first point.

### Task 6: pointToPrimitiveDist respects ellipse shape (DrawingViewModel.kt)

**Files:**
- Modify: `app/src/main/java/com/scheda/app/viewmodel/DrawingViewModel.kt:1576-1580`

**Fix:** Use parametric nearest-point approximation instead of maxOf(rx,ry).

### Task 7: computeBounds adds stroke width padding (Primitive.kt)

**Files:**
- Modify: `app/src/main/java/com/scheda/app/model/Primitive.kt:136-141`

**Fix:** Add strokeWidth/2 to hw and hh.
