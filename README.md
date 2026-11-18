# 江山 · Compose Multiplatform 重写（里程碑 1）

这是用 Kotlin/Compose Multiplatform 重写的实验版本；原来的 Java/View 版本保留在 `../jiangshan-android`，随时可切回。

## 当前状态：可运行的地图 POC

已跑通（Android 真机/模拟器）：

- 数据：`atlas.json`、`rings.bin`、`settlements.json`、`terrain_relief.webp` 通过 Compose Resources 打包并加载（rings.bin 手写小端解析）。
- 渲染：地形晕渲底图 + z5/z6 瓦片 LOD、政权面（含 neighbor 透明处理）、区域边界、城池点与注记。
- 交互：地图单指拖动、双指缩放、双击放大；时间轴单指点击/拖动选年、双指捏合缩放时间窗口、双指平移。
- 史事：时间轴左缘标出当前朝代的事件节点（小白点，去重）；屏幕底部横向事件列表（LazyRow，尺寸/间距对齐 jiangshan-atlas：面板边距 6/6/8 dp、圆角 12、把手 26 dp、列表高 84 dp、内容 padding 14/6、卡片宽 212 dp、卡片 padding 10/6、标题 10 sp、正文 12.5 sp、选中底色 #FAEBD0 + 朱砂描边）；卡片点击后选中高亮（朱砂描边 + 暖底），并自动滚动到列表中间；列表左右各留 10 dp 边距；「史事 ▾/▴」把手切换显示/隐藏。
- 水系：`hydro.json` 的 198 条河流（按 rank 分 4 档线宽/颜色，黄河等主川带米白描边）、62 个湖泊（主/次两档填充）、67 条水名/山脉/大漠标注（斜体、字距、旋转按 web），河流/湖泊支持 `a`/`v` 年份显隐；水名优先级最低，城市/国名压在上面。
- 国名/州郡名：polities 的 `anchor` 画国名标注（main 19 / rival 15 / protectorate 12 / neighbor 14 sp，深褐/墨色，随缩放按 sqrt 增长），snapshot.regions 画州郡名（9 sp、`#522212`），都参与城市标签的避让。
- 城市/地名：按 web 的三档样式绘制（国都：朱砂圆环 + 菱形点、深绛色名；一级：米白圆点 + 墨色描边；二级：米色小点，缩小到一定倍率才出现）；字号随缩放按 `sqrt(scale/cover)` 增长，标签按国都→一级→二级优先级做简单避让。
- 横竖屏切换保留年份、年表缩放/光标/滚动位置、事件面板开合状态、选中事件与事件列表滚动位置。
- 时间轴任何缩放级别（含全览）拖到顶部/底部都有朱砂色边缘光反馈（由嵌套滚动的未消费增量驱动，松手淡出）。
- 时间轴手势：默认整条年表缩放到卡片内（全部朝代可见、淡色色条）；单击时间轴任意位置把光标移到该处并切换年份（地图/事件联动）；单指滚动时光标位置不动、年份随光标下内容变化；按住光标拖动时冻结列表，只有光标移动并改年份；双指捏合实时重建年表布局（文字/里程点不缩放）。
  - 展开/收起用 `AnimatedVisibility` 横向滑动 + 淡入淡出。
  - 时间轴/事件列表都是浮在地图上的圆角卡片（阴影 + 半透明纸色）；底部用 `navigationBars`、左右用 `safeDrawing` inset 避让导航栏和挖孔。

iOS：

- Xcode 工程在 `iosApp/iosApp.xcodeproj`（由 `iosApp/project.yml` 经 XcodeGen 生成）。
- 已用 `xcodebuild` 构建并在 iPhone 17 模拟器跑通（见 `docs/cmp-ios-simulator.png`）。
- `:composeApp:linkDebugFrameworkIosSimulatorArm64` 直接构建 framework 也可用。
- 用 Xcode 打开：`open iosApp/iosApp.xcodeproj`，选 `iosApp` scheme + 任意模拟器，Run。
- 首次构建会执行 `Compile Kotlin Framework` 脚本（`./gradlew :composeApp:embedAndSignAppleFrameworkForXcode`），需要几分钟。
- 注意：KMP 目标里补了 `iosX64()`，否则 `syncComposeResourcesForIos` 会报
  “Cannot query the value of this provider” 而失败。
- 真机启动必须在 `Info.plist` 里带 `CADisableMinimumFrameDurationOnPhone = true`（CMP 1.7+ 的
  `PlistSanityCheck` 会强制校验 120Hz 高刷；模拟器不校验，所以只在真机崩）。已在
  `iosApp/project.yml` 的 `info.properties` 里加上，重新 `xcodegen generate` 即可。
- 改了 `project.yml` 或增删 Swift 文件后，在 `iosApp/` 下执行 `xcodegen generate` 重新生成工程。

## 尚未实现（下一阶段）

1. ~~地形瓦片 LOD（z5/z6）与瓦片缓存~~ 已接入：按 `needPpd` 选级、按需异步解码、36 张 LRU 缓存；缩放范围参考 atlas：`min = contain`（整张地图刚好放下，可缩到全图）、初始 `cover`（铺满屏幕、聚焦中原）、`max = contain × 20`（手机竖屏约 50 规范单位，比 web 的 100 再深一倍）。
2. 水系层（河流/湖泊/水名注记）。
3. 史事列表（横向卡片、点击跳年、收起/展开）。
4. 横屏三栏布局（左史事抽屉 / 右时间轴 / 单行标题）与挖孔安全区细化。
5. 时间轴双指缩放窗口、双击复位。
6. 标签避让、字号与字体的跨平台对齐（iOS 用系统衬线字体）。
7. iOS app shell + 真机签名。

## 构建

```bash
# Android
./gradlew :composeApp:assembleDebug
# 产物 composeApp/build/outputs/apk/debug/composeApp-debug.apk

# iOS 模拟器 framework
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
```

版本：Kotlin 2.3.21 / Compose Multiplatform 1.10.3 / AGP 8.7.3 / Gradle 8.9 / minSdk 26。

## 结构

```
composeApp/src/
  commonMain/kotlin/com/jiangshan/atlas/
    AtlasData.kt      JSON + rings.bin 加载、数据模型
    Projector.kt      Web-Mercator（与 Android 版一致的 1000×700 规范坐标）
    MapView.kt        Canvas 绘制 + 手势 + 相机
    TimelineView.kt   朝代色带 / 播放针 / 点选
    App.kt            标题卡 + 地图 + 时间轴
  commonMain/composeResources/files/atlas/   数据与底图
  androidMain/     MainActivity（ComponentActivity + setContent）
  iosMain/         MainViewController（ComposeUIViewController）
```

## 踩到的坑

- Compose 的 `Canvas` 默认不裁剪，地形底图的负偏移会画到上方标题卡上；必须给地图容器加 `clipToBounds()`。
- Compose Resources 在 1.10 里 `Res.readBytes` 是生成对象的成员函数，不需要额外 import。
- 底图 `Color(0xF2F3EEE4)`（Long 字面量）会得到透明色，需要用 `0xFF...` 或显式 Color(r,g,b,a)。

## Logo

- 来源：web 版 `assets/favicon.svg`（朱砂印章：城门、日、水纹）。用无头 Chrome 以 1024×1024 透明背景栅格化，ImageMagick 自带的 SVG 渲染器会把渐变画黑，不可用。
- 使用位置：
  - Compose 标题卡左侧 38 dp 小图标（`composeResources/drawable/logo.png`）；
  - Android launcher icon：adaptive icon（纸色背景 + 62% 印章前景）+ 各密度 legacy PNG；
  - iOS `Assets.xcassets/AppIcon`：1024 不透明图标（纸色底）。
- 原始 1024 图：`docs/logo.png`。

## 截图

- `docs/cmp-portrait.png`：竖屏全览
- `docs/cmp-zoom.png`：双击放大后的政权与城池注记
