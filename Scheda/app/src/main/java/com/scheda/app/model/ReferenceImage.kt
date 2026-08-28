package com.scheda.app.model

/**
 * 参考图片 — 贴在画布最底层的底图（手绘参照）。
 *
 * 不是 DrawingPrimitive：独立 _images 列表管理，天然不参与画布点选/围栏/橡皮。
 * 只能经图片管理面板选中后做移动/等比缩放/旋转/调透明度/删除。
 *
 * 位图以 JPEG Base64 内嵌（导入时已降采样长边 ≤2048px），.scheda 文件自包含。
 * Gson 直接序列化，内存态 / 文档态 / 撤销快照共用同一不可变实例（增量撤销池按引用去重，
 * base64 数据在整个撤销历史中只存一份）。
 */
data class ReferenceImage(
    val id: String,                    // UUID，位图缓存键
    val data: String,                  // JPEG Base64
    val centerX: Float,                // 世界坐标中心
    val centerY: Float,
    val width: Float,                  // 世界尺寸（未旋转）
    val height: Float,
    val rotationDeg: Float = 0f,       // 屏幕顺时针为正（与世界坐标系一致）
    val alpha: Float = 1f,
    val layerId: Int = 0,              // 所属图层（导入时取当前活动图层；图层隐藏则图片不画/不导出）
    val pixelWidth: Int = 0,           // 原图像素尺寸（DXF IMAGEDEF 需要）
    val pixelHeight: Int = 0
)
