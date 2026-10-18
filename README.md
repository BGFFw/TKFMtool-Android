# TKFMtool —— 天下布魔 · 全境征才小助手（原生 Android）

[![Android CI](https://github.com/BGFFw/TKFMtool-Android/actions/workflows/android.yml/badge.svg)](https://github.com/BGFFw/TKFMtool-Android/actions/workflows/android.yml)

原生 Android（Kotlin）重写版，独立仓库。**不再依赖 AutoX.js**：悬浮窗、截图、识图、点击全部由本 APK 自己完成。

> 旧的 AutoX.js 脚本在本仓库已不再维护，源码见 <https://github.com/BGFFw/TKFMtool>。
> 这里只保留一份 `legacy/TKFMtool.js` 快照，作为角色数据的抽取源（`tools/extract_dataset.js` 会读它）。

## 功能

* 三种运行模式
  * 模式一：识图后弹出结果页面
  * 模式二：识图后弹出结果页面，并自动选择优先级最高的词条组合
  * 模式三：识图后不弹页面，直接自动选择优先级最高的词条组合
* 自动设置招募时间（区分「非必出 SR」与「必出 SR 及以上」两档）
* 识图模式切换（精准 / 快速）
* 用户纠错：可编辑一张「识别错误 → 正确词条」的替换表，随改随生效
* 结果面板里点任意一行，即可按该词条组合自动选人

## 安装与使用

1. 安装 `app-debug.apk`（或自行构建，见下）
2. 打开 App，按首页三行状态提示把权限点开：
   * **悬浮窗权限**（必需）
   * **无障碍服务**（自动点击、自动改招募时间必需；不开也能识图）
   * 通知权限（可选，只影响常驻通知）
3. 点「启动悬浮窗」，在系统弹窗里同意「屏幕录制」
4. 切到游戏，进入**全境征才**界面
5. 点悬浮按钮「开始识图」；**长按**悬浮按钮打开设置菜单

> Android 14 及以上每次启动都要重新确认屏幕录制授权，这是系统限制，无法绕过。

## 设置项

长按悬浮按钮 → 设置，菜单与原版逐项对应：

| 菜单项 | 说明 |
| --- | --- |
| 选择运行模式 | 上面三种模式 |
| 自动设置招募时间开关 | 开启后选人前会先把时间拨到设定值 |
| 招募时间设置 | 先设「非必出 SR」，再设「必出 SR 及以上」 |
| 识图模式 | 精准模型（原图）/ 快速模型（缩放到 0.75 再识别） |
| 词表智能纠正 | **新增**：把不在词表里的识别结果吸附到最接近的已知词条 |
| 识图结果纠错 | 打开纠错列表页 |
| 查看上次识图结果 | 显示纠正后的上一次结果 |
| 关于本程序 | 版本与反馈方式 |
| 关闭程序 | 停服务并退出进程 |

## 构建

需要 JDK 17+ 与 Android SDK（`compileSdk 35`、`build-tools 34.0.0`）。

```powershell
# 环境准备（按你自己的路径调整）
$env:ANDROID_HOME='F:\Software\AndroidStudioSdk'
$env:GRADLE_USER_HOME='F:\Area\.gradle'

.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

仓库已带 Gradle Wrapper（`gradle-8.11.1`），Android Studio 直接 Open 工程根目录即可。

### 自动构建（GitHub Actions）

`.github/workflows/android.yml` 会在 push 到 `main`、提 PR、或手动触发时自动执行：

1. 装 JDK 17 与 Android SDK（platform 35 / build-tools 34.0.0）
2. 跑全部单元测试
3. 组装 debug APK，并用 `apksigner` 校验签名
4. 把 APK 与测试报告上传到该次运行的 Artifacts

打 `v*` 标签时会额外创建一个 GitHub Release，并把 APK 以 `TKFMtool-<tag>-debug.apk` 的名字附上去。所以安装包有两个来源：

* 稳定版：<https://github.com/BGFFw/TKFMtool-Android/releases>
* 每次提交的最新构建：对应那次 Actions 运行页面底部的 Artifacts

### 关于签名

`app/signing/debug.keystore` 是入库的固定签名（Android SDK 标准 debug 证书），本地构建和 CI 构建都用它。这样产出的 APK 签名一致，手机上已安装的旧版本可以直接覆盖升级，不会因签名变化要求卸载重装。

这个证书只用于个人工具的分发，不作为商店发布签名。

## 工程结构

```
app/src/main/
├── assets/characters.tsv            角色词条库（由 tools/extract_dataset.js 抽取，77 条）
├── java/com/bgffw/tkfmtool/
│   ├── MainActivity.kt              授权引导页
│   ├── core/
│   │   ├── TagMatcher.kt            词条组合匹配（对应原 tags_find / Tag_matcher）
│   │   ├── CorrectionPipeline.kt    OCR 文本清洗（对应原 correctresult / userCorrect）
│   │   ├── TagVocabulary.kt         词表与括号后缀归一化
│   │   ├── CharacterDatabase.kt     角色库加载
│   │   ├── SettingsRepository.kt    设置存储（沿用原 chosed 仓库的全部键名）
│   │   ├── Geometry.kt              1080×2400 设计坐标 → 实际分辨率
│   │   ├── OcrEngine.kt             OCR 抽象
│   │   ├── MlKitOcrEngine.kt        ML Kit 端侧中文 OCR
│   │   └── Clicker.kt               串行化点击
│   ├── capture/ScreenCapture.kt     MediaProjection 截图
│   ├── accessibility/TkfmAccessibilityService.kt   无障碍手势
│   ├── service/FloatingService.kt   悬浮按钮 + 主流程
│   └── ui/                          结果面板、设置、纠错页
└── test/...                         匹配与清洗逻辑的单元测试（18 项）
legacy/TKFMtool.js                   旧 AutoX.js 脚本快照，仅作数据抽取源
tools/extract_dataset.js             从 legacy/TKFMtool.js 重新生成 characters.tsv
```

## 与原 AutoX.js 版的差异

### 行为一致的部分

* 词条组合生成顺序（全 3 选 → 全 2 选 → 逐个单选，5 个词条共 25 组）
* 优先级规则：0☆→0、1☆→1、2☆→2、3☆ 且组合含「领袖」→3，其余丢弃
* 排序：优先级降序，同优先级按命中角色数升序
* 「必出 SR」判定：任一命中的是 0☆/1☆ 即不算
* 点击位置：词条框的右下角（`bounds.right`, `bounds.bottom`）
* 坐标缩放：`设备宽度/1080`、`设备高度/2400`
* 裁剪区域与时间按钮坐标、`60 → 09` 的读数特判
* 设置键名与取值语义（`mode`、`SetTime`、`None_SRtime`、`Have_SRtime`、`Ocr_Mode`、`UserCorrectSet`、`last_result`）

### 招募时间调节的实现差异

原版是裸的 `while (读值 != 目标) { 点一下; sleep(1000) }`，再挂个 10 秒定时器中断。那套节奏建立在 PaddleOCR 足够快的前提上。本工程每轮要「截图 + ML Kit 识别」，一圈一两秒，10 秒只够点两三次，而且选词条与调时间是并行的，前半段界面还没切过来，预算全空转。

现在改成：

* 读数交给 `RecruitTimeParser`：优先取能独立成数的 OCR 块（`^\d{1,2}$`），没有才把所有文本拼起来找数字。ML Kit 把 `09` 拆成 `0` 和 `9` 两块时不会再读成 `0`。
* 两段式预算：等界面出现最多 12 秒（没数字时每 300ms 重试一次），界面出现后允许继续调整 20 秒。
* 点击后 350ms 重读，不再固定睡 1 秒。
* 选词条 → 等 600ms → 调时间，串行执行，并加了重入保护。

### 修掉的问题

1. **纠错后点不到**：原版把纠正后的文本写回用于匹配的数组，却在定位时拿纠正后的文本去比对纠正前的 OCR 结果，导致被纠正过的词条永远找不到位置。现在每个词条带着自己的位置块走完整个管线。
2. **结果窗口叠加**：原版每次识图都新开一个 floaty 窗口。现在复用同一个面板，点击某行后自动收起。
3. **`##2196f3` 颜色笔误**：纠错项色条里的双井号会导致颜色解析失败，已更正。
4. **括号后缀导致漏匹配**：数据里同一词条写成 `巨乳` 与 `巨乳(?)` 两种，原版精确比较使带括号的那批角色永远匹配不到。现在统一剥掉 `(?)` / `(80%)` 这类作者手写后缀。
5. **招募时间读数不可靠**：OCR 出 `9` 而不是 `09` 时原版会一直点；被拆成两个块时又会读成 `0` 直接放弃。现在统一由 `RecruitTimeParser` 处理拼接、补零与范围校验。
6. **设置弹窗改动**：原版在 AutoX 运行时里直接弹悬浮对话框，MIUI 上容易闪退。现在改成一个透明 Activity 承载级联对话框。

### 引擎差异

| | AutoX.js 版 | 原生版 |
| --- | --- | --- |
| 截图 | `requestScreenCapture()` | `MediaProjection` + `VirtualDisplay` |
| OCR | PaddleOCR（`paddle.ocr`） | ML Kit 中文识别（模型随 APK 打包，离线，免 Play 服务） |
| 点击 | `press(x, y, 50)` | 无障碍 `dispatchGesture`，50ms 按压 |
| 存储 | `storages.create("chosed")` | 同名 SharedPreferences |

OCR 引擎被抽象成 `OcrEngine` 接口，想换回 PaddleOCR / ONNX Runtime 只需再实现一个接口。

### 权限说明

APK 只申请了：悬浮窗、前台服务（媒体投影）、通知、唤醒锁。

ML Kit 的库会声明 `ACCESS_NETWORK_STATE`（仅读取网络状态），而 `INTERNET` 已在 Manifest 里显式移除（`tools:node="remove"`）—— 这个应用在系统层面就不具备联网能力。

## 已知限制

* 每次启动都需要重新授权屏幕录制（Android 14+ 系统限制）
* 需要悬浮窗权限；自动点击需要无障碍服务
* 坐标系按 1080×2400 设计稿等比缩放，横屏与平板未适配
* 应用了 `FLAG_SECURE` 的画面无法被截图
* 识图依赖端侧模型，弱光 / 缩放异常的皮肤可能导致个别字识别错误，可用「识图结果纠错」补偿

## 原项目

原版基于 AutoX.js，历史 Release 见 <https://github.com/BGFFw/TKFMtool/releases/tag/v1.0.0>。
