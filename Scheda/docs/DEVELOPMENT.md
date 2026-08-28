# DEVELOPMENT.md — 区间数字朝向 / DXF 导出（文字 + 参考图）修复

本文档记录任务 `fix-interval-number-dxf-image` 的三个问题的根因、改动设计与验证方法。

## 一、问题与根因

### 问题 1：区间数字两端数字在横屏下朝向不对

**期望**：区间数字"两端数字始终朝下"跟随画布旋转后的坐标系 —— 竖屏时文字"下方"= 屏幕下；
横屏（画布旋转 90°）时文字"下方"= 屏幕左。

**根因（含对 5fda2f9 的分析）**：

v1.8（commit `5fda2f9`）的做法是在**创建时**把横屏偏移烘焙进区间数字的 `rotation` 存储值
（`buildLabelPrimitive` 中 `baseRot + π/2`）。它只打了"新生成的、横向区间"这一条路径：

1. **只影响新生成**：横竖屏切换时已存在的区间不会跟随旋转态，朝向仍错；
2. **横向/竖向只做对了一半**：横向区间（`horizontalOnly=true`）横屏烘焙 +π/2 后两端数字
   "下方"= 屏幕左（这半是对的）；但竖向区间（`horizontalOnly=false`）横屏烘焙成
   `π/2 + π/2 = π`，画布 `Canvas.rotate(180°)` 后文字**上下颠倒**（读序反向、字形倒置）；
3. **烘焙进存储值**：横屏状态被固化进图纸数据，后续渲染/导出无法再区分"布局旋转"与"横屏偏移"，
   两条路径（画布 Compose 渲染 vs Python ezdxf 导出）各自算角度，迟早分叉；
4. **切换工具/预览路径不全**：PFO 预览（PostCreationOverlay）与普通渲染（DrawingCanvas）各写一份
   区间绘制逻辑，横屏偏移并不统一；`toggleHorizontalText` 切换方向时也未叠加横屏偏移。

**改法**：把"区间数字朝向角度计算"抽成**纯函数**（见下文"共享纯函数"）。存储值只保留"布局旋转"
（0 = 横向区间，π/2 = 竖向区间），不再烘焙横屏；**渲染时**按 `布局旋转 + 横屏偏移` 计算有效角度，
已存在的区间在切换横竖屏时也会自动跟随。两端数字的绘制角与区间线（箭头）朝向**解耦**：
两端数字恒"朝下"（竖屏 0、横屏 +90°），区间线随画布旋转，中间数字跟随区间线方向（当前数据模型
无中间数字字段，规则仅作约定与文档化）。

### 问题 2：导出 DXF 中区间数字与画布显示不一致

**根因**：

a) DXF 是 Y 向上、TEXT 旋转角为逆时针（CCW）度数，画布是 Y 向下、屏幕顺时针角 —— 直接搬屏幕角度
会方向镜像。旧 Python `_write_range` 对"横向区间"用通用旋转分支、对"竖向区间"用 `is_v` 专用竖排
分支，两套布局与画布"整框旋转"的渲染并不一致（竖向区间在画布上两端数字是旋转 90°，导出却是正立
上下摆放），且横屏信息根本没传进导出，横屏下导出的数字方向与画布不一致。

b) Python 端对每个 TEXT 用 `set_placement(pos, align=MIDDLE_CENTER)`（会写组 72/73 与第二对齐点
组 11），但旧 `_write_range` 里还叠加了一个 `adj = len(text)*fs*0.12` 的横向偏移补偿，画布端没有这份
偏移，导致文字漂移、样式与画布不一致。

**改法**：Kotlin `DxfExporter` 用同一个纯函数把每个区间数字的**文字段锚点 + 绘制角**预计算好，写入
JSON 的 `textLayout` 字段；Python `_write_range` 直接消费（`fy` 翻 Y、`-angle` 转 DXF 逆时针度数、
`MIDDLE_CENTER` 对齐），移除旧 `is_v`/通用两套布局与 `adj` 补偿。方向、位置天然与画布一致（含横屏）。

### 问题 3：导出的参考图在 CAD 里看不到

**根因**：App 侧随 Chaquopy 打包的是 **ezdxf 1.4.4**。ezdxf 1.4.x 的 `Drawing.add_image_def`
参数名是 `size_in_pixel`（旧版曾用 `size_in_px`，新版本又改回 `size_in_px`），而 `_write_images`
用了 `size_in_px`，导致**每一张参考图都抛异常被跳过**，导出文件里根本没有 IMAGE 实体 / IMAGEDEF，
CAD 自然看不到参考图。R2000（AC1015）版本本身是正确的。

**改法**：修正参数名为 `size_in_pixel`；补充**裁剪边界**（`IMAGE.set_boundary_path`，WCS 矩形 =
图片占位绕中心旋转后的四角，`clipping=1, boundary_type=2`）；并校验 IMAGEDEF 挂到 named object
dictionary 的 `ACAD_IMAGE_DICT`、IMAGE 实体 340 软指针指向 IMAGEDEF、IMAGEDEF 路径为**相对文件名**
（图片与 DXF 同目录，不写手机绝对路径，否则 PC 端 CAD 必然看不到）。

## 二、共享纯函数（问题 1 & 问题 2 共源）

位置：`app/src/main/java/com/scheda/app/model/RangeLabelLayout.kt`（与 `RangeLabelPrimitive`
同包 `com.scheda.app.model`；纯 Kotlin，无 Android 依赖，可独立单测）。

```kotlin
object RangeLabelLayout {
    val PI_HALF: Float                       // 90° 弧度
    fun endTextAngle(isLandscape: Boolean): Float   // 两端数字绘制角：竖屏 0 / 横屏 +π/2
    fun arrowAngle(storedRotation: Float, isLandscape: Boolean): Float  // 区间线朝向
    fun arrowHalfLength(arrowSpan: Float): Float    // 箭头半长 = max(40*span, 10)
    fun compute(
        storedRotation: Float, isLandscape: Boolean,
        x: Float, y: Float, fontSize: Float, arrowSpan: Float,
        reversed: Boolean = false
    ): RangeLabelLayoutInfo          // arrowAngle + startText/endText/middleText 的锚点与角度
}
```

输出 `RangeLabelLayoutInfo`：
- 两个端数字 `RangeTextPlacement(angle, x, y)`：**angle 恒为两端数字绘制角**（竖屏 0 / 横屏 +π/2），
  锚点 = 局部 `±(half+fontSize)` 沿箭头方向旋转（reversed 交换两端）。
- `middleText`：angle = 区间线朝向角（**跟随区间线方向**），锚点 = 区间中心（当前模型无中间数字字段，
  此字段作规则约定）。
- `arrowAngle` = `storedRotation + (isLandscape ? π/2 : 0)`。

**调用方**（两处渲染 + 一处导出均走同一纯函数）：
1. `DrawingCanvas.drawRangeLabel`（正式渲染）；
2. `PostCreationOverlay` 的区间预览（PFO/选择变换，`storedRotation = rotation + edit.rotation`）；
3. `DxfExporter`（Kotlin 侧预计算 `textLayout` 写入 JSON，Python 端消费）。

横屏状态 `isLandscape` 从 `DrawingViewModel.isLandscape` 顺 `MainScreen → GestureHost` 传入
`DrawingCanvas` / `PostCreationOverlay`，导出时作为 `DxfExporter.export(isLandscape=...)` 参数传入。

## 三、涉及文件

- `app/src/main/java/com/scheda/app/model/RangeLabelLayout.kt`（新增，共享纯函数）
- `app/src/main/java/com/scheda/app/model/Primitive.kt`（RangeLabelPrimitive，未改动，仅背景）
- `app/src/main/java/com/scheda/app/ui/canvas/DrawingCanvas.kt`（区间绘制改用纯函数 + isLandscape）
- `app/src/main/java/com/scheda/app/ui/canvas/PostCreationOverlay.kt`（区间预览改用纯函数 + isLandscape）
- `app/src/main/java/com/scheda/app/ui/canvas/GestureHost.kt`（透传 isLandscape）
- `app/src/main/java/com/scheda/app/ui/screens/MainScreen.kt`（向 GestureHost 传 isLandscape）
- `app/src/main/java/com/scheda/app/viewmodel/DrawingViewModel.kt`（区间创建不再烘焙横屏；导出传 isLandscape）
- `app/src/main/java/com/scheda/app/export/DxfExporter.kt`（区间 JSON 增加 textLayout 预计算字段）
- `app/src/main/python/scheda_dxf_export.py`（`_write_range` 消费 textLayout；`_write_images`
  修复 `size_in_pixel` + 裁剪边界；**范围外，需先上报后改**）
- `app/src/test/java/com/scheda/app/model/RangeLabelLayoutTest.kt`（新增，纯函数单测）
- `docs/verify_dxf_export.py` + `docs/requirements.txt`（本地 ezdxf 模拟验证脚本与其宿主依赖说明）
- `app/build.gradle.kts`（追加一行 `testImplementation("junit:junit:4.13.2")`，让区间朝向单测可在仓内运行）

## 四、实现要点

1. 区间创建（`buildLabelPrimitive` RANGE）`rotation = if (horizontalOnly) 0 else π/2`，去掉横屏烘焙。
2. 渲染统一公式：局部 `(-half,0)→(+half,0)` 绕区间中心按 `arrowAngle` 旋转出箭线两端；
   箭头在结束端（+half）或反向时在起始端（-half）；两端数字按纯函数锚点 + 端数字绘制角绘制，
   字体度量垂直居中（baseline = anchorY − (ascent+descent)/2），与 DXF `MIDDLE_CENTER` 对齐语义一致。
3. 导出：`textLayout` = `{arrowAngle, arrowHalf, reversed, texts:[{text,x,y,angle}]}`；
   Python 端角/坐标换算：`fy(y)` 翻 Y、`rotation = -angle*180/π`（AC1015 TEXT 逆时针度数）。
4. 参考图：`size_in_pixel` 修正 + `set_boundary_path` 矩形裁剪边界（WCS，绕中心旋转后四角），
   插入点按绕中心旋转计算保证图片中心落在 `(cx, fy(cy))`。

## 五、验证方法

- **单测**：`app/src/test/.../RangeLabelLayoutTest.kt` 8 个用例（竖屏横/竖、横屏横/竖、反向、
  中间数字、几何一致性）。运行：`./gradlew testDebugUnitTest`（`app/build.gradle.kts` 已加
  `testImplementation("junit:junit:4.13.2")`）。结果：8 tests, 0 failures。
- **本地模拟**：`python docs/verify_dxf_export.py`（需 `pip install ezdxf==1.4.4`，见
  `docs/requirements.txt`）—— 构造竖屏/横屏两种区间输入 + 一张带旋转参考图 JSON，走 App 内真实导出
  引擎，断言：ezdxf audit 通过、TEXT 数量/旋转角/组 72/73/11 对齐符合预期、IMAGE 实体存在且
  340→IMAGEDEF、IMAGEDEF 路径为相对文件名、ACAD_IMAGE_DICT 存在、裁剪边界启用、图片中心正确。
  结果：ALL DXF VERIFY CHECKS PASSED。
- **编译**：`./gradlew compileDebugKotlin` 通过（不跑 assembleDebug）。

## 六、波及与说明

- **数字阵列未波及**：数字阵列用 `NumberLabelPrimitive`，有独立的 `drawNumberLabel` 渲染路径，
  且它不经过 `RangeLabelLayout`；本次没有改动 `buildNumArrayPrimitives` 与数字阵列导出，行为不变。
- **中间数字**：当前数据模型只有 `startValue/endValue`（两端），没有中间数字字段；中间数字朝向
  按任务约定默认"跟随区间线方向"（纯函数 `middleText.angle = arrowAngle`），未自行发挥其他规则。
- **竖向区间在竖屏的外观变化**：旧画布把整个区间旋转 90°（两端数字变成竖排、相对"下方"朝左）；
  本次按"两端数字始终朝下"改成正立摆放在箭头两端 —— 这正是 DXF 旧 `is_v` 布局的意图，改后画布与
  DXF 首次真正一致（所见即所得）。
- **向后兼容说明**：v1.8 时期横屏创建的区间把横屏偏移烘焙进了 `rotation` 存储值；本方案把横屏偏移
  移到渲染/导出期，加载这类旧图纸后再渲染可能存在一次"多转 90°/少转 90°"的显示偏差（图纸数据未迁移）。
  本版本属于快速迭代期，接受该兼容代价，已在汇报中说明。
- **限制**：`RangeLabelPrimitive.computeBounds`（选择框/适配视图）仍只按存储 `rotation` 计算，
  横屏下包围盒与显示有 ±90° 的近似误差；属显示外的小偏差，不在本次问题范围内。
---

## 追加：区间数字朝向设置（朝下/朝左）+ 选中元素淡蓝色光晕

### 区间数字朝向（两端数字 朝下/朝左）

- 语义约定：**朝下** = 数字下方朝屏幕正下方（即既有正向，绘制角 0，默认）；**朝左** = 数字下方朝
  屏幕左边（绘制角 +π/2，屏幕顺时针，从上往下读）。只影响两端数字本身的绘制角，
  **不动**箭头朝向、数字排布位置（锚点不变）。
- 数据：`RangeLabelPrimitive.numbersFaceLeft: Boolean = false`（序列化
  `SerializablePrimitive.numbersFaceLeft`，旧文件缺省 = false，行为不变）。
- 纯函数：`RangeLabelLayout.compute(..., numberAngle)`，两端数字绘制角由入参给出；
  `RangeLabelLayout.numberAngleFor(numbersFaceLeft)` 做 false→0 / true→+π/2 映射。
  画布渲染、PFO/选择变换预览、DXF 导出（textLayout.angle）三条路径仍共用同一纯函数，方向一致。
- 包围盒/选择框：朝左时两端数字沿轴/垂直方向占用互换，`RangeLabelPrimitive.computeBounds` 与
  `PostCreationOverlay.computePrimitiveHalfDims` 同步处理，框保持紧贴。
- UI：选中区间数字后属性对话框（数字分类）新增"数字朝向"——朝下/朝左两档
  （`DrawingViewModel.updateSelectedRangeNumbersFaceLeft`，进撤销历史）。

### 选中元素淡蓝色光晕

- 选中集内每个图元画一圈紧贴轮廓的淡蓝（`#90CAF9`）辉光：3 趟加宽描边模拟软辉光
  （单边外扩 3/8/14 px，透明度 0.50/0.28/0.15），画在元素本体之下。
- 线条类（手绘/矩形/圆/直线/区间箭线/图块内容）用同几何加宽描边；文字/数字用
  `Paint.Style.FILL_AND_STROKE` 加粗字形轮廓，保证光晕紧贴字形而非包围盒。
- 两条渲染路径都画：常态选中在 `DrawingCanvas`（世界坐标，外扩量除 canvasScale 换算）；
  变换拖动中（isTransforming，图元改由 overlay 渲染）在 `PostCreationOverlay.drawPrimitiveAt`
  以 `glowInflatePx/glowColor` 参数画光晕趟，跟随实时变换。

### 涉及文件（本次）

- `model/RangeLabelLayout.kt`、`model/Primitive.kt`、`model/SchedaDocument.kt`、
  `file/SchedaSerializer.kt`、`ui/canvas/DrawingCanvas.kt`、`ui/canvas/PostCreationOverlay.kt`、
  `viewmodel/DrawingViewModel.kt`、`ui/screens/MainScreen.kt`、`export/DxfExporter.kt`
- 单测：`app/src/test/java/com/scheda/app/model/RangeLabelLayoutTest.kt`（新增 numberAngle 用例）
