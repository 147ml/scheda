# Scheda

手绘平面图绘制工具 —— Android 端轻量级 CAD 手绘应用。

## 功能

- 自由手绘（Freehand）、直线、矩形、椭圆、圆弧、折线
- 数字标签、文字标注
- 多图层管理、线型与颜色
- 图块（Block）定义与插入
- 选中、移动、缩放、旋转、删除
- DXF 导出（AutoCAD 2000 格式，含完整 handles/OBJECTS 段）
- 橡皮擦（自由擦除 / 整段删除）
- 文件管理：新建、保存、另存

## 技术栈

- **语言：** Kotlin
- **UI：** Jetpack Compose + Material3
- **最低 SDK：** 26（Android 8.0）
- **目标 SDK：** 35（Android 15）
- **序列化：** Gson
- **文件操作：** Storage Access Framework（SAF）
- **权限：** `MANAGE_EXTERNAL_STORAGE`（Android 11+ 全文文件访问）

## 编译

```bash
# 需要 Android SDK 和 JDK 17+
# 在项目根目录执行
./gradlew assembleRelease
```

APK 输出位置：`app/build/outputs/apk/release/`

### Keystore 配置

签名密码通过环境变量传入（可选，不设置则用默认值）：

```bash
export SCHEDA_STORE_PASSWORD="你的密码"
export SCHEDA_KEY_PASSWORD="你的密码"
./gradlew assembleRelease
```

## 许可证

[Apache License 2.0](LICENSE)

基于 AOSP Material3 DropdownMenu 修改的组件保留原始版权声明（见 `SchedaDropdownMenu.kt`）。
