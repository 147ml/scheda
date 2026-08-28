# DXF Entity Count Refactor

> [!NOTE]
> This document may not reflect the current implementation.
> See the final report for up-to-date state:
> [Final Report](../reports/dxf-entity-count-refactor.md)

## [S1] Problem
DxfWriter.kt contains two `when` blocks that map each primitive type to its DXF entity count and handle count. This logic belongs to the primitive itself, not the writer.

## [S2] Solution
Add `dxfEntityCount()` and `dxfHandleCount()` abstract methods to `DrawingPrimitive`, implement them in all 8 subclasses, then replace the `when` blocks with method calls.

## [S3] Implementation Details
1. Primitive.kt: Add abstract methods (already declared) and implement in each subclass.
2. DxfWriter.kt: Replace `when` blocks with `p.dxfEntityCount()` and `p.dxfHandleCount()`.
3. Keep recursion for `BlockRefPrimitive` in DxfWriter (needs access to `blockDefs`).
4. Keep `updateBounds` calls in DxfWriter.

## [S4] Acceptance Criteria
- All subclasses implement `dxfEntityCount()` and `dxfHandleCount()`.
- DxfWriter.kt uses these methods instead of `when` blocks.
- Existing DXF output remains unchanged.
- No other code modifications.

## [S5] Out of Scope
- Changing `countWriteEntities` or `countEntityHandles` functions.
- Moving recursion logic into Primitive.
- Modifying `updateBounds` logic.