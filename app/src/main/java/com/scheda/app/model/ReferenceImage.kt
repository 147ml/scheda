package com.scheda.app.model

/**
 * 参考图片 — 贴在画布最底层的底图（手绘参照）。
 *
 * 不是 DrawingPrimitive：独立 _images 列表管理，天然不参与画布点选/围栏/橡皮。
 * 只能经图片管理面板选中后做移动/等比缩放/旋转/调透明度/删除。
 *
 * 位图以 JPEG Base64 内嵌（导入时已降采样长边 ≤2048px），.scheda 文件自包含。
 * Gson 直接序列化，内存态 / 文档态 / 撤销快照共用同一不可变实例。
 *
 * 注意：撤销快照按**引用**去重，但 `copy()` 改变换会产生新实例——
 * 位图数据的实际收敛靠 [stripDataForUndoPool]（池条目只留元数据，位图按 id 存一份）。
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
    val name: String = "",             // 显示名（空则由 UI 回落为「图片 N」）
    val visible: Boolean = true,       // 显隐：隐藏不绘制、不导出 DXF
    val pixelWidth: Int = 0,           // 原图像素尺寸（DXF IMAGEDEF 需要）
    val pixelHeight: Int = 0
)

/**
 * 收进撤销池前的降重：把位图数据从实例上摘掉。
 *
 * 同一张图被反复选中调整，每次都会 `copy()` 出新实例进池；若每个实例都带一份 base64，
 * 文件体积与序列化内存峰值会按"版本数"翻倍（实测 3 个版本 = 4 倍）。
 * 所以池条目只保留变换元数据，位图数据按 id 收敛：
 * - 已在文档 images 里的图 → 不重复登记，加载时从文档回填；
 * - 已被删除、撤销历史还引用着的图 → 登记到 [dataSink]，否则撤销删除后恢复不出位图。
 *
 * @param liveImageIds 当前文档 images 里的图片 id
 * @param dataSink 位图数据收集器（id → Base64）
 */
fun ReferenceImage.stripDataForUndoPool(
    liveImageIds: Set<String>,
    dataSink: MutableMap<String, String>
): ReferenceImage {
    if (data.isEmpty()) return this
    if (id !in liveImageIds) dataSink[id] = data
    return copy(data = "")
}

/**
 * 修复 Gson 反序列化留下的缺省字段。
 *
 * Gson 不走构造函数，旧文件里没有的字段会留在 null / false，而不是 Kotlin 的默认值：
 * name、visible 是 2026-11-04 新增，此前的图纸两者都不存在 → name = null、visible = false。
 * 直接用 copy() 重建会撞上构造函数对 name 的非空检查（release 里该检查被 R8 压成
 * Object.getClass() 的空调用），所以这里用主构造函数显式重建。
 *
 * `name == null` 即判为旧格式：新格式写盘时 name 至少是空串，不会是 null。
 */
@Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
fun ReferenceImage.normalized(): ReferenceImage {
    val legacy = name == null
    return ReferenceImage(
        id = id ?: "",
        data = data ?: "",
        centerX = centerX,
        centerY = centerY,
        width = width,
        height = height,
        rotationDeg = rotationDeg,
        alpha = alpha,
        layerId = layerId,
        name = name ?: "",
        visible = if (legacy) true else visible,
        pixelWidth = pixelWidth,
        pixelHeight = pixelHeight
    )
}
