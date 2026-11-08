# Scheda

手绘平面图绘制工具 —— Android 端轻量级 CAD 手绘应用。

## 功能

**绘制**

- 自由手绘、直线、矩形、圆形
- 标注数字、区间数字、文字标注
- 数字阵列：按设定的数量、间距、角度生成一排数字
- 正交锁定、方向键微调、撤销与重做
- 多图层管理（新建、复制、合并、显隐、删除）、线型、线宽、颜色

**编辑**

- 选中、移动、缩放、旋转、删除
- 镜像、复制、剪切、粘贴、属性查看
- 阵列：选中图元按行×列复制，可拖动调整间距与数量
- 框选与点选，点选支持开关式多选
- 图块（Block）定义与插入
- 橡皮擦（自由擦除 / 整段删除）

**图纸**

- 参考图片：导入、定位、显隐、归属图层
- DXF 导入：按 CAD 口径读取线宽与线型
- DXF 导出：AutoCAD 2000（AC1015），由 ezdxf 生成
- 文件管理：新建、保存、另存；删除进回收站

**诊断**

- 崩溃记录与退出原因查看，可导出日志

## 技术栈

- **语言：** Kotlin
- **UI：** Jetpack Compose + Material3
- **最低 SDK：** 26（Android 8.0）
- **编译 SDK：** 36（Android 16）
- **目标 SDK：** 35（Android 15）
- **序列化：** Gson
- **文件操作：** Storage Access Framework（SAF）
- **权限：** `MANAGE_EXTERNAL_STORAGE`（Android 11+ 全文文件访问；Google Play 对此权限有专项审核，通常只对文件管理类应用开放，本应用按侧载方式分发）
- **DXF 引擎：** Chaquopy + Python 3.11 + ezdxf + numpy
- **玻璃效果：** Kyant0 Backdrop

## 编译

环境要求：

- JDK 17
- Android SDK（compileSdk 36）
- Android Gradle Plugin 9.2.1、Gradle 9.6.1、Kotlin 2.4.10
- **Python 3.11**：DXF 引擎由 Chaquopy 在构建期打包，`python` 命令必须指向 3.11，3.12 及以上会报 `is not a valid Python 3.11 command`

```bash
# 在项目根目录执行
./gradlew assembleRelease
```

APK 输出位置：`app/build/outputs/apk/release/`，只包含 `arm64-v8a`。

需要在 x86_64 模拟器上调试时，加 `-PSCHEDA_X86=true` 一并打包 x86_64。

### 签名配置

仓库不包含签名证书。项目根目录放 `release.keystore` 时使用其中的正式签名，没有则回退到 debug 签名，clone 后可直接编译安装。

密码通过 gradle 属性或环境变量传入：

```bash
./gradlew assembleRelease \
  -PSCHEDA_STORE_PASSWORD="***" \
  -PSCHEDA_KEY_PASSWORD="***"
```

别名可用 `-PSCHEDA_KEY_ALIAS=***` 覆盖，默认为 `147ml`。

## 许可证

[Apache License 2.0](LICENSE)

Copyright © 2026 147ml

基于 AOSP Material3 DropdownMenu 修改的组件保留原始版权声明（见 `SchedaDropdownMenu.kt`）。
