# MBack · Xposed (OnePlus 15 / ColorOS)

在 ColorOS 手势导航的小白条上模拟魅族 mBack：**轻点返回，上滑仍保留系统原有导航功能**。v0.4 加入缩放与可调震动，v0.5 加入 Flyme 风格双底角小窗菜单。

已在一加 15（PLK110）、Android 17 / API 37、Vector Framework 2.2 / Xposed API 102 上测试。

## 实现

- 作用域为 SystemUI（com.android.systemui），Hook ColorOS 的 NavigationBarView。
- 原系统手势模式中 NavigationBar 的 touchableRegion 为空，因此旧 getevent 方案允许点穿。新版本使用 OnComputeInternalInsetsListener 在系统导航栏增加有限的可触区域，通过 ViewGroup.dispatchTouchEvent 消费点击。v0.3 扩大为导航栏窗口完整厚度，只在白条附近的长轴中间区域捕获，修复下沿漏判。
- 触摸区域以 SystemUI 当前导航栏布局及可见 NavigationHandle 为准，跟随屏幕旋转，不硬编码触控芯片的竖屏坐标。
- v0.3 的 0–380ms 短按、移动不超过 22dp 时通过 InputManagerGlobal 注入 BACK；取消了对极短点击的过滤。SystemUI 已具备 INJECT_EVENTS 权限。
- 滑动、多指、长按不触发返回；导航栏的上滑仍由系统的 gesture monitor 处理。
- 可设置小白条轻点时的缩放比例（50%～150%，每档 5%）、缩放总时长（80～500ms，每档 20ms），点击确认后先缩放再恢复。100% 相当于没有缩放幅度。
- 支持振动强度 0（关闭）及 1～10 级（10 档不同振幅），并提供“试听当前震动”按钮。硬件支持振幅控制时使用单次 VibrationEffect 自定义振幅，成功轻触时才触发。参数在 Xposed 的 SystemUI 进程通过 Settings.Global 观察并动态更新，不需要重启。
- 有“启用轻触返回”和“显示常驻状态通知”两个独立开关。关闭通知不会停止手势功能，无后台 getevent 进程，也不需要前台 Service。
- 开关通过 root 写入 Settings.Global.mback_xposed_enabled，SystemUI 监听变更动态更新触摸区域；设置值在触摸回调之外缓存，降低每次触摸的开销。

## 本地构建和安装

需要 JDK 17+、Android SDK 35、Build Tools 36。本机使用零第三方依赖构建脚本：

```bash
bash build-local.sh
adb install -r app/build/local/mback-debug.apk
```

stubs/ 下只有编译期 legacy Xposed API 的声明，脚本不会把这些 stub 打包进 APK。运行时 API 由 Vector / LSPosed 提供。

## 启用方式

1. 安装 APK。
2. 在 Vector（或兼容 Legacy Xposed 的 LSPosed）中启用 dev.mback.oneplus，作用域设置为 com.android.systemui 与 com.android.launcher（后者用于拦截多任务前段）。
3. 重启 SystemUI 或重启手机，让 Xposed 注入生效。
4. 打开 MBack，开启“启用轻触返回”，首次写入需要 root 授权。
5. 按需开启“显示常驻状态通知”。若通知不显示，请核实通知权限及“MBack 状态”渠道。

本机 Vector 命令：

```bash
adb shell su -c '/data/adb/lspd/cli modules enable dev.mback.oneplus'
adb shell su -c '/data/adb/lspd/cli scope add dev.mback.oneplus com.android.systemui/0'
adb shell su -c '/data/adb/lspd/cli scope add dev.mback.oneplus com.android.launcher/0'
```

## v0.6.4：Flyme 扇形 UI 与原生导航手势前段屏蔽

- 去掉 v0.5 的横向浅色卡片、标题和文字说明，改为参考 Flyme 8/9 小窗菜单的「蓝色三白点入口 + 逐级弧形展开的圆形应用图标」，带轻微遮罩、弹出动画、白色选中圈和触摸高亮，左右角镜像。可在设置页预览。
- 核心滑选仍支持一笔斜滑到图标松手，或呼出扇形后再点图标。小窗启动仍由 ColorOS 原生 `android.activity.windowingMode=100` 负责。
- 为避免刚开始斜滑就露出系统返回箭头或多任务缩略图，新增对 **SystemUI 的 EdgeBackGestureHandler** 和 **一加桌面 com.android.launcher 的 OplusBaseTouchInteractionService** 的 Xposed 入口过滤。只有触点从竖屏的两个下角区域开始时才过滤；中间上滑和其他屏幕位置维持系统原行为。
- 被动 SPY 输入监听贯穿完整斜滑过程，不调用 `pilferPointers`，避免 ColorOS 把同一触摸拆断成 `ACTION_CANCEL`。两个透明角落区域拦住对下层 App 的点击穿透，且仅占两侧底角，不覆盖中间的小白条。`FLAG_LAYOUT_IN_SCREEN` + `setFitInsetsTypes(0)` 保证角落触摸区延伸至物理屏幕底边；息屏/锁屏不接管，解锁后重试创建，横屏时清理遮罩。
- **作用域新增 com.android.launcher**：必须在 Vector / LSPosed 中将本模块作用域设为 `com.android.systemui` **与** `com.android.launcher`，然后重启这两个进程或重启手机。未勾选桌面作用域不能保证消除多任务开头动画。
- 这是参考 Flyme 官方公开演示的高保真重绘，不是 Flyme 原版源码移植，也不声称像素级一致。Flyme 各代版本图标角度、模糊与动效不同，且 ColorOS 的实际小窗控制界面仍由 ColorOS 提供。当前只适配一加 15 的 ColorOS 17，横屏不启用。由于输入回调时序和 ROM 升级存在不确定性，不能保证所有第三方应用、锁屏及特定全屏场景下与魅族原机完全一致。
- 真机验证（PLK110/Android 17）：在左/右底角，SystemUI edge-back 与 Launcher Quickstep 均拦截该段输入的 `ACTION_DOWN`，未再进入系统手势处理；斜滑选择成功打开 ColorOS 日历/计算器等原生悬浮任务，背景原 App 保持。`dumpsys input` 确认两块 `242×180px` 输入区分别贴合物理底边 `[0,2592]-[242,2772]`、`[1030,2592]-[1272,2772]`；屏幕中部普通上滑回桌面成功。
- 一键紧急关闭：`adb shell su -c 'settings put global mback_corner_menu_enabled 0'`；关闭时输入监视与透明底角区域都会移除。若 ROM 升级导致异常，还可在 Vector 禁用模块或取消 Launcher 作用域后重启对应进程。

## v0.5.0：左右下角斜滑小窗菜单

- 在 MBack 中开启“启用左右下角斜滑菜单（竖屏）”；**默认关闭**，不影响原有 mBack 单击返回。应用选择界面支持自定义最多 6 个已安装应用。
- 从竖屏左下角或右下角向中间斜向上滑。SystemUI 独立的 `monitorGestureInput` 监听器只在确认斜滑后 `pilferPointers`，避免 Launcher 的 swipe-up 抢走该手势；普通上滑交给系统。
- 菜单采用半透明、浅弧形排列，可滑到应用图标后松手打开，也可松手保留菜单再点击图标；点右上角 × 关闭。
- 小窗由 ColorOS 专用参数 `android.activity.windowingMode=100` 原生启动。标准 Android 自由窗口 mode 5 在本机被忽略。
- 真机测试：左下角滑选计算器、右下角滑选文件管理器，均显示为 ColorOS 原生浮动小窗，原 App 留在下层。
- 开关和应用列表分别写入 `Settings.Global.mback_corner_menu_enabled`、`mback_corner_menu_apps`；关闭开关停止独立输入监控。紧急关闭：`adb shell su -c 'settings put global mback_corner_menu_enabled 0'`。
- **不宣称与 Flyme 像素级或系统行为完全一致**：小窗之后由 ColorOS 管理，菜单最多 6 个应用，目前仅竖屏可用；部分游戏/银行应用可能禁止小窗。其他 ROM 版本未测试。

## v0.4.2 默认参数（2026-10-24）

- 根据手机实际 `Settings.Global` 读取结果，将新安装的缺省反馈设置改为：缩放比例 **110%**、动画时长 **240ms**、震动强度 **3 级**。
- 已有配置优先级高于代码默认值，更新 APK 不会覆盖用户已保存的数值。
- 轻触返回开关仍需用户显式启用；通知默认关闭，与设备当前状态一致。

## v0.4.1 缩放中心修复（2026-10-24）

- 根因：ColorOS `OplusNavigationHandle.onDraw()` 把可见白条绘制在 View 靠下的位置：`bottom = viewHeight - mHandleBottom`，`top = bottom - (mHeight + mAdditionalHeightForAnimation * longPressAnimProgress)`。旧版以整个 131px 高 View 的中心为 pivot，使缩放时小白条上下偏移。
- 新版使用真实绘制矩形的纵向中心作为缩放 pivotY；水平中心继续使用宽度一半。这让 50%～150% 的缩放围绕可见白条中心进行，兼容 ColorOS 原有长按效果对绘制高度的改变。
- 对应数学验证：`PillGeometryTest`；保持 v0.3 的 Xposed 可触区域与 10 级振幅设置不变。

## v0.4.0 反馈参数与真机验证（2026-10-24）

- 默认缩放比例 75%，总时长 220ms，振动强度 5 级；调节后立即生效，可切换至 100% 或 0 级关闭相应效果。
- 一加 15 / Android 17：Xposed 成功加载 `v0.4-touch-feedback`，确认真实手柄类 `com.oplus.systemui.navigationbar.gesture.sidegesture.OplusNavigationHandle`（357×131）。
- 点击测试页小白条返回成功，SystemUI 触发动画和震动；横屏轻触返回成功，竖屏上滑仍回桌面。
- 使用 `dumpsys vibrator_manager` 核验：1 级和 10 级的触觉反馈确有差异，ROM 记录的强度约为 800 和 2334（ColorOS 内部标度，并非 Android 振幅值）。
- 通过界面拖动缩放滑块后，`Settings.Global.mback_scale_percent` 实际从 75 变成 110；设备已恢复默认 75% / 220ms / 5 级。
- 纯 Java 测试命令：`javac -d app/build/local/tests app/src/main/java/dev/mback/oneplus/FeedbackConfig.java tests/dev/mback/oneplus/FeedbackConfigTest.java && java -cp app/build/local/tests dev.mback.oneplus.FeedbackConfigTest`。

## 早期版本回归测试（2026-10-24）

v0.2 复现：竖屏屏幕 1272×2772，旧触摸区域 [421,2657]—[849,2753]。在测试页点击 (635,2760)，底部按钮计数从 0 增为 1，没有返回。

v0.3 修改后：竖屏触摸区域变为 [203,2641]—[1068,2772]。同一位置点击 (635,2760)，返回成功；极短 adb tap 同样成功。横屏自动旋转成侧向导航窗口区域 [0,443]—[131,2328]，屏幕底部白条点击返回成功。上滑回桌面成功。关闭功能会恢复空 touchableRegion，开启后立即恢复。

可执行纯 Java 区域边界测试：

```bash
javac -d app/build/local/tests app/src/main/java/dev/mback/oneplus/HitZone.java tests/dev/mback/oneplus/HitZoneTest.java
java -cp app/build/local/tests dev.mback.oneplus.HitZoneTest
```

## 原版真机测试（2026-10-24）

| 检查 | 结果 |
|---|---|
| Vector 加载模块、挂钩 SystemUI | 通过 |
| 导航栏可触区域从空变成白条范围 | 通过 |
| 竖屏短按小白条返回 | 通过 |
| 横屏短按小白条返回 | 通过 |
| 穿透点击到底下的测试按钮 | 未发生，计数 0 |
| 横屏底部上滑回桌面 | 通过 |
| 通知开关开启和关闭 | 通过 |

## 已知限制与恢复

- 当前仅在本机 ColorOS 版本验证。ROM 更新可能更改 SystemUI 方法或导航栏布局。
- v0.3 已移除最短 20ms 点击阈值。太长的触摸（超过 380ms）或移动超过 22dp 的触摸仍不会触发返回。
- 游戏沉浸模式、输入法覆盖、锁屏、三键导航和部分自定义导航模式尚未全量验证。
- 遇到系统导航异常可以先执行以下命令关闭功能，必要时再禁用 Xposed 模块并重启 SystemUI：

```bash
adb shell su -c 'settings put global mback_xposed_enabled 0'
adb shell su -c '/data/adb/lspd/cli modules disable dev.mback.oneplus'
```

旧 MBackService 源码已经清空并且从 Manifest 删除，不再有 root getevent 监听后台服务。
