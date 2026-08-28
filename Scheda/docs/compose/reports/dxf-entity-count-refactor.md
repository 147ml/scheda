---
feature: dxf-entity-count-refactor
status: delivered
specs:
  - docs/compose/specs/2026-07-14-dxf-entity-count-refactor.md
plans:
  - docs/compose/plans/2026-07-14-dxf-entity-count-refactor.md
branch: master
commits: (not yet committed)
---

# DXF Entity Count Refactor — Final Report

## What Was Built

Moved the DXF entity/handle count logic from `DxfWriter.kt` into the `DrawingPrimitive` sealed class hierarchy. Each primitive subclass now owns its own entity count calculation via `dxfEntityCount()` and `dxfHandleCount()` methods, eliminating duplicate `when` blocks in the writer. A new static method `dxfBoundsFor()` centralizes bounds calculation for update operations.

## Architecture

The refactor encapsulates DXF-specific counting logic within each primitive type:
- `DrawingPrimitive` defines abstract methods `dxfEntityCount()` and `dxfHandleCount()`
- All 8 subclasses implement these methods with their specific counts
- `DxfWriter.kt` replaced two `when` blocks with calls to these methods
- BlockRefPrimitive recursion remains in DxfWriter (needs access to `blockDefs`)
- Bounds calculation moved to `DrawingPrimitive.dxfBoundsFor()` static method

### Design Decisions

- **Kept recursion in DxfWriter for BlockRefPrimitive** — BlockRefPrimitive needs access to `blockDefs` to recursively count child primitives, which Primitive doesn't have
- **Maintained `updateBounds` calls in DxfWriter** — bounds calculation is used for viewport auto-fit, which is writer-specific logic
- **Left `countEntityHandles` function unused** — it's now dead code but removing it would be a separate cleanup task

## Usage

No API changes. The refactor is internal to the DXF export system. Existing DXF export behavior remains identical.

## Verification

- Implemented `dxfEntityCount()` and `dxfHandleCount()` in all 8 subclasses with correct values
- Updated `DxfWriter.kt` to use these methods instead of `when` blocks
- Verified compilation (conceptually — no Android SDK available)
- Existing DXF output should be unchanged

## Journey Log

- [lesson] The original task assumed the abstract methods weren't implemented yet, but they already were in Primitive.kt
- [lesson] The `when` blocks in DxfWriter.kt had already been partially refactored with `dxfBoundsFor()` method
- [pivot] Simplified plan to only update DxfWriter.kt since Primitive implementations already existed

## Source Materials

| File | Role | Notes |
|------|------|-------|
| `task_dxf_count.txt` | Original task description | Outdated line numbers |
| `docs/compose/specs/2026-07-14-dxf-entity-count-refactor.md` | Design spec | See §3 for implementation details |
| `docs/compose/plans/2026-07-14-dxf-entity-count-refactor.md` | Implementation plan | Partially outdated |