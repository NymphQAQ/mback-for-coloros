# 不依赖负一屏的乘车码：恢复指南

归档日期：2026-10-25。本文记录已经通过真机验证、随后按用户要求撤回的实现，供以后恢复。归档时恢复到了 **0.7.0，由负一屏启动两个乘车码**；当前版本以项目 README 为准，保存本文不会改变手机或应用行为。

2026-10-26 补充：本地 0.7.2 / 30 已修复依赖负一屏模式下的系统小窗，仍使用负一屏。本文补丁继续对应历史 0.7.0 基线；以后切换到独立方案时，按文件差异合并当前 `FreeformLauncher` 等改动，沿用届时的递增版本号，不能覆盖较新的其他功能。历史补丁和文件摘要保持不变。

2026-10-26 切换版补充：本地 **0.7.3 / 33** 已将本文的独立方案整合为 **快捷小窗 → 乘车码启动方式 → 直接小窗启动**，默认选择仍为负一屏。当前版本切换设置即可，无需应用历史补丁；切回「负一屏启动」也无需还原代码。本文以下补丁与摘要继续保留为 0.7.0 基线的历史记录，不覆盖现有实现。最新使用说明及验证结果见项目 README。

## 恢复材料

本目录是完整的源码恢复材料，不依赖聊天记录或本机 `artifacts/` 备份：

| 文件 | 用途 |
| --- | --- |
| [restore.patch](restore.patch) | 11 个文件的完整差异，包含新增的启动代码和测试 |
| [manifest.json](manifest.json) | 基线提交、每个文件恢复前后的 SHA-256、补丁摘要及已验证 APK / DEX 摘要 |
| 本文 | 原理、关键参数、恢复、安装、验证、撤回及已踩过的坑 |

归档材料已在临时目录完成验证：补丁可正常应用，11 个恢复后文件的摘要全部匹配；独立构建及签名校验通过，生成的 DEX 与此前真机通过的 0.7.1 完全一致；原生测试代码可编译，Python 测试语法通过；反向补丁可恢复全部基线文件并移除 4 个新增文件。该次归档未安装或操作手机，归档时的生效源码为 0.7.0。

补丁以仓库 [NymphQAQ/mback-for-coloros](https://github.com/NymphQAQ/mback-for-coloros) 的提交 [`6fc7fdae67d9e0656bb0677974ef701393d71638`](https://github.com/NymphQAQ/mback-for-coloros/commit/6fc7fdae67d9e0656bb0677974ef701393d71638) 为基线，即 v0.7.0 / versionCode 25。补丁中的开发版为 0.7.1 / 26，未发布。

## 下次收到恢复请求时

1. 先读取本文和 `manifest.json`，确认用户要恢复的是“两个乘车码直接启动、无需负一屏”，而非恢复负一屏模式。
2. 检查当前源码、微信版本、手机连接与安装版本，备份当前改动及设置。将当前文件摘要与清单的 `baseline_sha256` 对比；新增文件在基线中应不存在。
3. 基线一致时应用补丁；以后项目已有新功能时，按下文逐项合并，保留新功能。补丁检查失败时不要强制覆盖。
4. 构建、校验、覆盖安装并重载 SystemUI；保留已添加项、排序、自定义图标及所有调节值。
5. 若用户要求测试，运行下文的独立启动回归。未来版本必须重新检查兼容性，不能把 2026-10-25 的结果当作新版本已经通过。
6. 只有收到同步或发布请求后才修改远端和发布安装包。本文及补丁本身不触发安装、停用负一屏或发布。

## 两个入口的实现

### 支付宝

从 SystemUI 直接向以下活动发送 `ACTION_VIEW`，继续使用 ColorOS 原生小窗参数 `android.activity.windowingMode=100`：

```text
com.eg.android.AlipayGphone/com.alipay.mobile.quinox.SchemeLauncherActivity

alipays://platformapi/startapp?appId=200011235&chInfo=ch_oppo_left&sceneCode=KF_CHANGSHANG&partnerId=ch_oppo_left&pikshemo=YES
```

`200011235` 是出行入口，付款码仍使用 `20000056`。出行页面由支付宝自己的后续活动链打开，不直接硬启动最终的 `CSGAPushActivity`。负一屏内置的固定推荐参数 `shareUserId` 不需要复制，本方案未携带该参数。

### 微信

```text
SystemUI 中的 CornerAppMenu / FreeformLauncher
  → WechatTransit.request：创建指向固定微信活动的一次性 PendingIntent
  → 显式启动 MBack 的 WechatTransitActivity（不绘制窗口）
  → MBack 自身进程校验微信签名、加载微信 APK 中的启动参数类型
  → 填入固定小程序和页面参数，发送 SystemUI 创建的 PendingIntent
  → 微信 AppBrandLaunchProxyUI → AppBrandUIxx 乘车码页面
```

SystemUI 授权启动固定目标，微信的参数对象在 **MBack 自身进程** 构造，避免把微信类加载到 SystemUI 中。恢复方案没有额外的 `su`、`app_process` 或常驻进程；项目原有的 Xposed / 系统权限环境仍是前提。

已验证微信为 **8.0.79，versionCode 3300**。固定参数如下：

| 项目 | 值 |
| --- | --- |
| 目标活动 | `com.tencent.mm/com.tencent.mm.plugin.appbrand.launching.AppBrandLaunchProxyUI` |
| 参数类 | `com.tencent.mm.plugin.appbrand.launching.params.LaunchParcel` |
| 字段 `d` | `gh_3cf62f4f1d52@app` |
| 字段 `i` | `pages/qrcode/index?noback=1&channel=oppo_fuone&attach=02_oppo_fuone` |
| 字段 `o` | 默认构造的 `com.tencent.mm.plugin.appbrand.report.AppBrandStatObject` |
| Intent 参数 | `extra_launch_parcel` 为上述 Parcelable；`extra_from_mm=false` |
| 中转 action | `dev.mback.oneplus.OPEN_WECHAT_TRANSIT` |
| 中转唯一 extra | `wechat-launch`，值为 SystemUI 创建的 PendingIntent |
| 微信签名 SHA-256 | `0fe4ff85c215918396dadc7cd8ce6963339af33d37751a56e54c7206b63a3c7c` |

微信签名使用 `hasSigningCertificate(..., CERT_INPUT_SHA256)` 校验。对应代码中的 Base64 为 `D+T/hcIVkYOW2tx82M5pYzOa8z03dRpW5UxyBrY6PHw=`。这不是账号、密钥或乘车凭证。

`AppBrandStatObject` 使用默认构造即可。已测试的微信版本中不存在此前尝试的 `scene` 字段，不要添加该反射赋值。

### 必须保留的启动与校验细节

- PendingIntent 由 **SystemUI** 创建，显式固定微信组件；使用递增请求码和 `FLAG_ONE_SHOT`，避免连续打开共用旧凭据。Android 12+ 使用 `FLAG_MUTABLE`，用于补入私有参数，但不开放目标组件的覆盖。
- Android 15+ 设置创建方后台启动许可；发送时 Android 14+ 设置发送方后台启动许可。小窗模式 100 放在创建凭据的 ActivityOptions 中。
- `WechatTransitActivity` 通过 `android.permission.STATUS_BAR` 限制调用，使用 `Theme.NoDisplay`，不进入最近任务，并在 onCreate 中立即 finish。不能改成一个可见的 MBack 设置页。
- 中转只接受指定 action、无 data、唯一的 PendingIntent extra，且凭据创建包必须为 `com.android.systemui`、类型必须为 Activity。普通应用创建的凭据不能被接受。
- 加载微信代码前校验签名和应用启用状态；后台线程构造参数。重复请求取消多余凭据，所有退出路径释放凭据和 busy 标记，失败时显示提示。
- `FreeformLauncher` 应先识别微信乘车码再请求启动，不在 SystemUI 中按旧负一屏组件查询可用性；管理页的可用性检查改为查询实际微信目标活动。
- `ShortcutProvider` 保持原有只读图标用途，不加入乘车码执行入口。

## 配置与图标兼容

两个预置项继续保留以下旧字符串作为 **配置 ID**：

```text
com.coloros.assistantscreen/com.oplus.assistantscreen.flamingo.trafficcode.WxTrafficCodeActivity
com.coloros.assistantscreen/com.oplus.assistantscreen.flamingo.trafficcode.AliTrafficCodeActivity
```

它们只用于识别原有快捷项，实际启动目标另行保存。看到配置里还有负一屏包名，不代表运行时仍启动负一屏。不要改写 `ShortcutStore` 默认列表或迁移现有 ID，否则可能破坏已添加项的识别、自定义图标及排序。

六个预置图标及图标形状逻辑均不需要修改。微信扫一扫、付款码、支付宝扫一扫及付款码保留原有启动行为。

## 文件改动清单

| 文件（相对项目根目录） | 改动 |
| --- | --- |
| `app/src/main/java/dev/mback/oneplus/PresetShortcut.java` | 将稳定配置 ID 与实际目标分开；更换两个乘车码路由、可用性检查；增加微信乘车码识别 |
| `app/src/main/java/dev/mback/oneplus/FreeformLauncher.java` | 预置项优先分发；微信走专用请求，其他入口保留原生小窗 |
| `app/src/main/java/dev/mback/oneplus/WechatTransit.java` | 新增一次性凭据、微信签名验证、参数构造和发送逻辑 |
| `app/src/main/java/dev/mback/oneplus/WechatTransitActivity.java` | 新增无窗口的受保护中转活动及参数校验 |
| `app/src/main/AndroidManifest.xml` | 注册受 STATUS_BAR 权限保护的中转活动 |
| `app/build.gradle`、`build-local.sh` | 该快照版本改为 0.7.1 / 26 |
| `app/src/main/java/dev/mback/oneplus/MBackHook.java` | 注册日志标识改为 `v0.7.1-direct-transit`，方便确认 SystemUI 加载了新代码 |
| `tests/android/TransitShortcutsInstrumentation.java` | 新增路由、旧 ID、可用性、权限和请求校验检查 |
| `tests/transit_shortcuts_test.py` | 新增停用负一屏后的两个入口冷启动和重复打开回归、配置恢复与清理 |
| `tests/activity_shortcuts_test.py` | MBack 活动数量从 3 改为 4，包含新增的受保护活动 |

补丁不包含根 README 的历史叙述、发布工作流、APK 或私钥。

## 应用补丁与构建

所有命令均在项目根目录执行。本机工作目录没有 `.git`，`git apply` 仍可在普通目录使用；不要为此执行 `git reset --hard`。

先备份当前改动，核对 `manifest.json` 的基线及补丁摘要，再执行：

```bash
rtk proxy git apply --check docs/transit-direct/restore.patch
rtk proxy git apply docs/transit-direct/restore.patch
rtk proxy bash build-local.sh
```

应用完成后按清单的 `restored_sha256` 核对 11 个文件。`git apply --check` 应无报错，构建须退出 0，签名验证须通过。

所需环境与现有项目一致：JDK 17+、Android SDK 35、Build Tools 36.0.0。`build-local.sh` 沿用本机 `~/.android/debug.keystore`，不增加依赖。

**若未来基线发生变化：** 不整体复制旧版 `MBackHook`、`FreeformLauncher` 或测试文件；以差异为依据合并。版本号沿用届时的版本递增，不能把更高版本降回 26；活动数量断言也以届时完整 Manifest 为准。核对共享启动路径中的其他应用、预置项和内外圈调用是否仍正常。

## 安装与重载

先记录当前 `mback_*` 设置及负一屏启用状态，并保留当前 APK；不要卸载 MBack，以免丢失私有目录中的自定义图标。确认构建使用与已安装版本相同的签名。

本机 ColorOS 的普通安装方式曾弹出安装确认页，采用以下方式已成功安装：

```bash
rtk proxy adb push app/build/local/mback-debug.apk /data/local/tmp/mback-direct-transit.apk
rtk proxy adb shell su -c 'pm install -r /data/local/tmp/mback-direct-transit.apk'
rtk proxy adb shell dumpsys package dev.mback.oneplus
```

安装输出应包含 `Success`，包信息中的版本应与本次构建一致。随后读取 SystemUI 的 PID，确认是单个纯数字，再仅重启该 PID：

```bash
rtk proxy adb shell pidof com.android.systemui
```

将下行 `12345` 替换为刚读取且验证过的 PID，再执行；不要直接照抄示例数字：

```bash
rtk proxy adb shell su -c 'kill 12345'
```

确认 SystemUI 使用新 PID，并从当前 Vector / LSPosed 日志确认 `v0.7.1-direct-transit registered`；以后修改版本标识时按实际标识检查。Launcher 的功能代码没有变化，本方案不要求重载桌面。

重载可能回到指纹锁屏。需要真机操作时由本人解锁，不能绕过锁屏。没有被要求测试时，不必为了文档归档或编译而操作手机。

## 真机验收

安装并重载后，连接已解锁的手机，在根目录运行：

```bash
rtk proxy /Users/nymph/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3 tests/transit_shortcuts_test.py
```

这是本机已验证且带 Pillow 的 Python；换环境时选择具备 Pillow 的 Python，不要直接假定系统 Python 已安装。脚本还使用现有测试工具与当前 `app/build/local/classes`，必须先构建。

脚本会临时停用负一屏、安装同签名的 `MBack transit checks` 测试应用，校验后自动卸载；它不是需要用户长期保留的功能组件。脚本将两个乘车码临时排列在容易直线选中的位置，从真实圆弧分别做冷启动、重复打开，核对前台活动和页面关键词，检查无负一屏活动参与。

| 场景 | 通过条件 |
| --- | --- |
| 微信冷启动 / 重复打开 | `AppBrandUIxx` 为前台活动，页面包含“乘车码” |
| 支付宝冷启动 / 重复打开 | 支付宝 `CSGAPushActivity` 为前台活动，页面包含“出行” |
| 请求校验 | 缺失凭据、错误 action、额外参数、data URI、非 SystemUI 凭据均被拒绝 |
| 权限与配置 | 普通测试包无 STATUS_BAR 权限，SystemUI 有权限；旧 ID 仍识别、付款/出行路由分离 |
| 结束状态 | 原 MBack 配置全量相等，负一屏恢复原启用状态，临时测试包及 XML 删除 |

脚本应退出 0，打印四条 `transit page reached without Assistant` 及最终 `Assistant was never launched; original settings and enabled state restored`。2026-10-25 已在 OnePlus 15 / ColorOS 17 / Android 17、微信 8.0.79 上全部通过，包括 MBack 被 force-stop 后的微信启动。

验收不截图付款/乘车码，不执行支付、开通或实际乘车。脚本读取页面关键词时临时生成 UI XML，结束时删除。若脚本被外部终止，需按测试前记录手动恢复配置和负一屏状态、移除临时测试包，不能把中断视为测试成功。

## 已踩过的坑

| 尝试 | 实际问题 / 处理 |
| --- | --- |
| MBack 复用负一屏的微信 SDK AppID | 微信校验调用方包名与签名，提示校验不通过；不能只复制 AppID |
| 只复制最后的 `AppBrandUI00` 活动 | 这是通用小程序容器，缺少小程序和页面参数，不能定位乘车码 |
| 通用 `weixin://dl/jumpWxa/` 链接 | 本次未具备合适调用身份，显示页面无法访问，未作为最终方案 |
| `WXShortcutEntryActivity` | 需要账户相关加密参数，不适合通用预置 |
| 由 SystemUI 调用模块 Provider | 模块停止及可见性场景下无法找到 Provider，最终恢复只读图标用途 |
| 显式广播唤起模块 | ColorOS 的 `oplus startup` 控制跳过广播；shell 能发送成功不能证明 SystemUI 路径可靠 |
| root / `app_process` 中转 | 曾打开容器，但窗口前后台路由及生命周期复杂；最终实现已完全移除，不能恢复实验 helper |
| 分开的 DOWN / MOVE / UP 命令 | 各次注入不构成同一 `downTime`，生产手势会拒绝；使用单次连续 swipe |
| 直线滑向圆弧最靠竖边的图标 | 斜率不满足向内斜滑条件，菜单不会展开；测试通过临时排序规避，不修改生产识别规则 |
| 只检查活动存在或启动返回成功 | 可能只有容器或未获得前台，必须同时检查前台活动和页面特征 |

## 已验证范围与未来风险

已验证开发版 APK SHA-256 为 `e7d7e6b8b4d21c97dd30085a9518bf24d512289ff411bfb8944672e972300f31`，DEX SHA-256 为 `1e3ae805929c6802e71883f2d0200e9df6c5ee2a2034b0b71c7f13c47d17c070`。重新签名产生的 APK 摘要可能变化；源码摘要及 DEX 可用于核对是否复现同一实现。

微信依赖私有类型、字段和参数。以后微信改动这些内容时可能需要重新提取、适配；不能只因补丁能应用或编译通过就宣称新版本兼容。支付宝出行链接和 ColorOS 权限 / 小窗行为同样可能变化。未做其他微信版本、其他机型、实际乘车或支付验证，也未为此重跑全部界面和图标回归。

## 再次撤回到负一屏模式

如果后来只应用了本补丁，相关文件未再修改，可先检查反向补丁，再撤回：

```bash
rtk proxy git apply --reverse --check docs/transit-direct/restore.patch
rtk proxy git apply --reverse docs/transit-direct/restore.patch
rtk proxy bash build-local.sh
```

若后续代码已变化，逐项撤回启动逻辑而非整体覆盖新版文件。源代码恢复后仍须给手机安装对应 APK 并重载 SystemUI，源码操作不会改变已经加载的模块。

2026-10-25 实际撤回使用的是原发布版 `releases/mback-v0.7.0.apk`，SHA-256 为 `b73fb605ce437f272a2c53c3e96e2cb9a5d7854e5a0f38d68b8c52ee16e959b8`；同签名情况下 `pm install -r -d` 成功保留数据降级。只有确实恢复该历史版本时才使用其版本号和摘要。不要通过卸载来处理降级失败。

撤回后保持负一屏启用，并核对设置和图标保留。本文和恢复补丁可继续保存，便于下次切换。
