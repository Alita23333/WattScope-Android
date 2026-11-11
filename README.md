# WattScope

面向已 Root Android 手机的本地性能与功耗记录工具。本工程重新实现采集、存储、统计和界面，并沿用[原版 WattScope](https://github.com/Alita23333/WattScope) 的应用名和图标。新版包名为 `io.github.alita23333.wattscope`，可与原版并存。原版记录目前无法直接导入新版。

从 [GitHub Releases](https://github.com/Alita23333/WattScope-Android/releases) 下载 ARM64 APK。

当前验收设备是 Redmi K90 Pro Max（myron），Android 17。其他设备可以尝试安装 ARM64 APK，但不承诺其 QPT、GPU、温度节点或逐帧追踪兼容。

## 已实现的使用流程

1. 在 KernelSU 中为 **WattScope**（`io.github.alita23333.wattscope`）授予 Root。允许通知与悬浮窗权限，在系统或冻结工具中为本应用保留后台运行例外。
2. 首页“实时监控”与两种悬浮窗联动，任一窗口开启时总开关同步亮起。总开关可单独开启首页预览，关闭时隐藏两窗并取消预览；记录期间保持开启且不可用它中断记录。QPT 继续由共享采集会话管理。首页不需要选择目标游戏。
3. 进入游戏后，单击记录小窗开始，再次单击结束并保存；拖动只移动位置。从 App 内开始时先等待进入可记录应用。
4. 记录自动绑定当前前台应用。切到其他应用或桌面时结束并保存，不自动开始下一条。锁屏、通知栏和快捷面板临时暂停；回到原应用恢复。手动暂停须手动继续。
5. 点击常驻通知打开控制面板，分别切换两个窗口，记录中可暂停／继续或打标记。点击面板外或返回关闭。隐藏窗口不结束记录；无记录时 OSD 也能独立工作。打开完整主界面则会结束当前游戏记录。
6. 在“记录”按应用查看历史，搜索、筛选、收藏、备注、标签或重命名。删除后可立即撤销；当前实现保留原始文件，不自动永久清理。
7. 详情页以紧凑网格展示摘要，可展开功耗分项。图表依次为 FPS、Frame time、CPU freq、CPU usage、GPU freq / usage、CPU power（cpu-l / cpu-m）、gpu power 及其余指标。CPU 频率按真实 policy 识别簇画线，占用在同一张图中逐核画线；GPU 频率和占用采用双轴合图。数据折线为 0.8 dp，用上方双端区间滑块统一控制范围，松手自动重算汇总；点击、横向滑动图表可同步查看该时刻数值，不改动区间。支持命名区间与对比。诊断质量和事件列表不再占据详情页底部，原始数据仍可导出。
8. 导出 CSV、干净底色摘要图、完整记录包。系统分享界面由用户选择目的地；App 不主动联网发送数据。完整包可重新导入并产生新 ID，不覆盖原记录。

Miuix 风格提供深浅主题，默认舒适布局，可切换紧凑布局。支持本地背景图片、裁剪定位、预处理模糊、明暗遮罩与卡片透明度。OSD 的配色、背景、字号、大小、位置和锁定独立设置。


## 构建

使用 JDK 17、Android SDK 36、NDK `28.2.13676358`、CMake `3.22.1`。Gradle Wrapper 为 8.9，AGP 8.7.3，Kotlin 2.1.20。首次构建需要下载 Maven 依赖及 Gradle，后续复用缓存。

```powershell
# 本机已有工具中心，可直接复用；其他电脑配置自己的 JDK/Android SDK 即可。
& 'C:\DevTools\Activate-DevTools.ps1'
.\gradlew.bat assembleRelease testDebugUnitTest lintRelease
# 已有完整依赖缓存时，可严格离线执行：
.\gradlew.bat --offline assembleRelease testDebugUnitTest lintRelease
```

也可以执行 `./build.ps1`，脚本检查 JDK 与 SDK 后构建。输出：

- `app/build/outputs/apk/release/app-release.apk`：优化后的常规 APK。
- `app/build/outputs/apk/benchmark/app-benchmark.apk`：`assembleBenchmark` 生成的优化验证版本。
- `app/build/outputs/apk/debug/app-debug.apk`：调试版本，不作为功耗验收的安装包。

v0.1.0 APK 沿用开发版本的 Android 调试签名。自行构建时使用本机签名，签名不同的 APK 无法覆盖安装，切换前请先导出记录。

所有构建均只包含 ARM64。AGP 8.7 与部分新版 AndroidX 的 UAST 检查存在兼容问题：仅关闭未使用的 LiveData 专用检查，`produceState` 的已赋值误报在对应文件局部说明并抑制；其他发布检查继续运行。完整结果见构建日志和 Lint 报告。

## 结构与说明

- [指标和统计口径](docs/metrics.md)：来源、单位、暂停、缺失与统计公式。
- [架构和记录格式](docs/architecture.md)：采集服务、原始块、恢复和导入校验。
- [功耗开销方法](docs/power-overhead-method.md)：独立 QPT 观察器与匹配 ABBA 测量。

`tools` 下的测试只使用明确指定的设备序列号。运行前确认手机可用于测试，记录未结束时先从 App 正常保存退出。不要用其他应用的 CPU 占用率推算本应用瓦数，也不要把 USB 供电时的电池净功率当成整机功耗。
