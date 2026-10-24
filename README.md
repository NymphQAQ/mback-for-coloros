# MBack · Xposed (OnePlus 15 / ColorOS)

在 ColorOS 手势导航的小白条上模拟魅族 mBack：**轻点返回，上滑仍保留系统原有导航功能**。

已在一加 15（PLK110）、Android 17 / API 37、Vector Framework 2.2 / Xposed API 102 上测试。

## 实现

- 作用域为 SystemUI（com.android.systemui），Hook ColorOS 的 NavigationBarView。
- 原系统手势模式中 NavigationBar 的 touchableRegion 为空，因此旧 getevent 方案允许点穿。新版本使用 OnComputeInternalInsetsListener 在系统导航栏增加有限的可触区域，通过 ViewGroup.dispatchTouchEvent 消费点击。v0.3 扩大为导航栏窗口完整厚度，只在白条附近的长轴中间区域捕获，修复下沿漏判。
- 触摸区域以 SystemUI 当前导航栏布局及可见 NavigationHandle 为准，跟随屏幕旋转，不硬编码触控芯片的竖屏坐标。
- v0.3 的 0–380ms 短按、移动不超过 22dp 时通过 InputManagerGlobal 注入 BACK；取消了对极短点击的过滤。SystemUI 已具备 INJECT_EVENTS 权限。
- 滑动、多指、长按不触发返回；导航栏的上滑仍由系统的 gesture monitor 处理。
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
2. 在 Vector（或兼容 Legacy Xposed 的 LSPosed）中启用 dev.mback.oneplus，作用域设置为 com.android.systemui。
3. 重启 SystemUI 或重启手机，让 Xposed 注入生效。
4. 打开 MBack，开启“启用轻触返回”，首次写入需要 root 授权。
5. 按需开启“显示常驻状态通知”。若通知不显示，请核实通知权限及“MBack 状态”渠道。

本机 Vector 命令：

```bash
adb shell su -c '/data/adb/lspd/cli modules enable dev.mback.oneplus'
adb shell su -c '/data/adb/lspd/cli scope add dev.mback.oneplus com.android.systemui/0'
```

## 回归测试（2026-10-24）

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
