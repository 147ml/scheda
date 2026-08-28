# 更新日志

## v1.2（2026-06-27）

### 🔧 修复

- **DXF 导出 FreeCAD 兼容性**：
  - 行尾格式 `\n` → `\r\n`（FreeCAD 需要 CRLF）
  - 编码 GBK → UTF-8（通用编码更兼容）
  - DWGCODEPAGE ANSI_936 → UTF-8
  - 字体 simhei.ttf → txt（标准 AutoCAD 默认字体）
  - 自动补充实体引用的缺失图层定义到 LAYER 表
