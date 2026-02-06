# TOPTEA KDS 系统设计说明书

> **文档版本**: 1.0
> **项目名称**: TOPTEA KDS (Kitchen Display System)
> **包名**: `com.toptea.topteakds`
> **生成日期**: 2026-02-06

---

## 目录

1. [系统概述](#1-系统概述)
2. [技术栈与依赖](#2-技术栈与依赖)
3. [项目结构](#3-项目结构)
4. [系统架构设计](#4-系统架构设计)
5. [核心模块详细设计](#5-核心模块详细设计)
   - 5.1 [MainActivity — WebView 容器与主入口](#51-mainactivity--webview-容器与主入口)
   - 5.2 [WebAppInterface — JavaScript 桥接层](#52-webappinterface--javascript-桥接层)
   - 5.3 [PrinterService — 打印服务](#53-printerservice--打印服务)
   - 5.4 [ConfigManager — 配置管理器](#54-configmanager--配置管理器)
   - 5.5 [ScannerActivity — 条码扫描](#55-scanneractivity--条码扫描)
   - 5.6 [EvidencePhotoActivity — 取证拍照](#56-evidencephotoactivity--取证拍照)
6. [数据流与交互时序](#6-数据流与交互时序)
7. [权限模型](#7-权限模型)
8. [UI 布局设计](#8-ui-布局设计)
9. [构建配置](#9-构建配置)
10. [安全设计](#10-安全设计)
11. [待实现功能 (TODO)](#11-待实现功能-todo)
12. [附录：文件清单](#12-附录文件清单)

---

## 1. 系统概述

### 1.1 产品定位

TOPTEA KDS 是一款面向餐饮行业的 **厨房显示系统 (Kitchen Display System)** Android 客户端应用。它作为一个 **WebView 容器壳 (Hybrid App)**，加载远程 Web 应用 (`https://store.toptea.es/kds/`)，同时通过 **JavaScript Bridge** 向 Web 端暴露原生 Android 硬件能力，包括：

- 热敏打印机控制（Wi-Fi / 蓝牙 / USB）
- 条码/二维码扫描（基于 CameraX + Google ML Kit）
- 取证拍照（带 GPS 定位 EXIF 写入）
- 打印机配置持久化

### 1.2 核心设计理念

| 设计原则 | 说明 |
|---------|------|
| **Hybrid 架构** | 业务逻辑全部运行在 Web 端，Android 端仅提供硬件桥接能力 |
| **异步回调模型** | 所有 Native 操作通过 JavaScript 回调函数名传参，操作完成后通过 `evaluateJavascript` 回调 Web 端 |
| **横屏锁定** | 所有 Activity 强制 `landscape` 方向，适配厨房显示器/平板场景 |
| **强制缓存清除** | 每次启动时清除 WebView 缓存，确保加载最新版本的 Web 应用 |

### 1.3 目标运行环境

| 参数 | 值 |
|------|-----|
| 最低 SDK | API 26 (Android 8.0) |
| 目标 SDK | API 35 (Android 15) |
| 编译 SDK | API 35 |
| JVM Target | Java 11 |
| 屏幕方向 | 强制横屏 (landscape) |
| 目标设备 | 厨房平板电脑、Android POS 一体机 |

---

## 2. 技术栈与依赖

### 2.1 构建工具

| 工具 | 版本 |
|------|------|
| Android Gradle Plugin (AGP) | 8.8.0 |
| Gradle Wrapper | 8.10.2 |
| Kotlin | 2.0.0 |
| Android Studio | Otter 2025.2.1 Patch 1 |

### 2.2 核心依赖库

| 分类 | 库名 | 版本 | 用途 |
|------|------|------|------|
| **Android 基础** | `androidx.core:core-ktx` | 1.15.0 | Kotlin 扩展函数 |
| | `androidx.appcompat:appcompat` | 1.7.0 | 向后兼容的 Activity 基类 |
| | `com.google.android.material:material` | 1.12.0 | Material Design 组件 |
| | `androidx.activity:activity` | 1.9.3 | Activity Result API + Edge-to-Edge |
| | `androidx.constraintlayout:constraintlayout` | 2.2.0 | 约束布局 |
| **WebView** | `androidx.webkit:webkit` | 1.11.0 | WebView 增强组件 |
| **协程** | `kotlinx-coroutines-core` | 1.8.0 | 协程核心库 |
| | `kotlinx-coroutines-android` | 1.8.0 | Android 主线程调度器 |
| **CameraX** | `camera-core` | 1.3.4 | 相机核心抽象 |
| | `camera-camera2` | 1.3.4 | Camera2 实现 |
| | `camera-lifecycle` | 1.3.4 | 生命周期绑定 |
| | `camera-view` | 1.3.4 | PreviewView 控件 |
| **ML Kit** | `barcode-scanning` | 17.2.0 | 条码/二维码识别 |
| **定位** | `play-services-location` | 21.3.0 | GPS 高精度定位 |
| **图像** | `androidx.exifinterface:exifinterface` | 1.3.7 | EXIF 元数据读写 |
| **二维码** | `com.google.zxing:core` | 3.5.3 | ZXing 条码库核心 |

### 2.3 版本目录 (Version Catalog)

项目使用 Gradle Version Catalog (`gradle/libs.versions.toml`) 统一管理依赖版本，部分 CameraX、ML Kit 等依赖直接在 `build.gradle.kts` 中以硬编码版本声明。

---

## 3. 项目结构

```
MyApplication/TOPTEAKDS/
├── build.gradle.kts                     # 项目级构建脚本
├── settings.gradle.kts                  # 项目设置（根项目名 + 模块声明）
├── gradle.properties                    # Gradle 全局属性
├── gradle/
│   ├── libs.versions.toml               # 版本目录
│   └── wrapper/
│       └── gradle-wrapper.properties    # Gradle Wrapper 配置
│
└── app/
    ├── build.gradle.kts                 # 模块级构建脚本
    ├── proguard-rules.pro               # ProGuard 混淆规则
    │
    └── src/main/
        ├── AndroidManifest.xml          # 应用清单
        │
        ├── java/com/toptea/topteakds/
        │   ├── MainActivity.kt          # 主入口 + WebView 容器
        │   ├── WebAppInterface.kt       # JS Bridge 接口类
        │   ├── PrinterService.kt        # 打印服务（ESC/POS + TSPL）
        │   ├── ConfigManager.kt         # 打印机配置持久化
        │   ├── ScannerActivity.kt       # 条码扫描 Activity
        │   └── EvidencePhotoActivity.kt # 取证拍照 Activity
        │
        └── res/
            ├── layout/
            │   ├── activity_main.xml           # 主界面（全屏 WebView）
            │   ├── activity_scanner.xml        # 扫码界面（全屏预览）
            │   └── activity_evidence_photo.xml # 拍照界面（预览+控件）
            ├── drawable/
            │   ├── ic_launcher_background.xml  # 启动图标背景
            │   ├── ic_launcher_foreground.xml  # 启动图标前景
            │   └── ic_shutter_button.xml       # 快门按钮图标
            ├── values/
            │   ├── colors.xml                  # 颜色资源
            │   ├── strings.xml                 # 字符串资源
            │   ├── themes.xml                  # 日间主题
            │   └── ic_launcher_background.xml  # 图标背景色
            ├── values-night/
            │   └── themes.xml                  # 夜间主题
            └── xml/
                ├── file_paths.xml              # FileProvider 路径配置
                ├── backup_rules.xml            # 备份规则 (API 31+)
                └── data_extraction_rules.xml   # 数据提取规则
```

---

## 4. 系统架构设计

### 4.1 整体架构图

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Web Application                              │
│                  (https://store.toptea.es/kds/)                     │
│                                                                     │
│   JavaScript 代码通过 window.AndroidBridge 调用原生功能              │
│   ┌──────────────────────────────────────────────────────────────┐  │
│   │  window.AndroidBridge.printJob(payload, onSuccess, onError) │  │
│   │  window.AndroidBridge.startScan(onSuccess, onError)         │  │
│   │  window.AndroidBridge.savePrinterConfig(...)                │  │
│   │  window.AndroidBridge.takeEvidencePhoto(onSuccess, onError) │  │
│   └──────────────────────────────────────────────────────────────┘  │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ @JavascriptInterface
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     Android Native Layer                            │
│                                                                     │
│  ┌─────────────────┐    ┌──────────────────┐    ┌───────────────┐  │
│  │  MainActivity   │◄──►│ WebAppInterface  │◄──►│ PrinterService│  │
│  │  (WebView 容器) │    │  (JS Bridge)     │    │ (ESC/POS/TSPL)│  │
│  └────────┬────────┘    └──────────────────┘    └───────┬───────┘  │
│           │                                              │          │
│           │ startActivityForResult                       │          │
│           ▼                                              ▼          │
│  ┌─────────────────┐    ┌──────────────────┐    ┌───────────────┐  │
│  │ ScannerActivity │    │EvidencePhotoAct. │    │ ConfigManager │  │
│  │ (CameraX+MLKit) │    │(CameraX+GPS+EXIF)│    │(SharedPrefs)  │  │
│  └─────────────────┘    └──────────────────┘    └───────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      Hardware Layer                                 │
│                                                                     │
│   ┌──────────┐  ┌───────────┐  ┌──────┐  ┌──────┐  ┌───────────┐  │
│   │ 热敏打印机│  │ 前置摄像头 │  │后置  │  │ GPS  │  │ 蓝牙模块  │  │
│   │(WiFi:9100)│  │ (扫码用)  │  │摄像头│  │模块  │  │ (待实现)  │  │
│   └──────────┘  └───────────┘  └──────┘  └──────┘  └───────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

### 4.2 通信模型

系统采用 **异步回调 (Asynchronous Callback)** 通信模型：

```
Web → Native:  window.AndroidBridge.method(data, "onSuccessFnName", "onErrorFnName")
Native → Web:  webView.evaluateJavascript("window.onSuccessFnName(result)")
```

**关键设计**：
- Web 端传递的不是函数引用，而是**全局函数名字符串**
- Native 端通过 `JSONObject.quote()` 安全转义参数
- 所有回调在 UI 线程通过 `runOnUiThread` 执行

---

## 5. 核心模块详细设计

### 5.1 MainActivity — WebView 容器与主入口

**文件**: `app/src/main/java/com/toptea/topteakds/MainActivity.kt`
**职责**: 应用主入口，作为 WebView 容器加载远程 KDS Web 应用

#### 5.1.1 类图

```
MainActivity : AppCompatActivity
├── 属性
│   ├── binding: ActivityMainBinding          # ViewBinding 实例
│   ├── webView: WebView                     # WebView 控件引用
│   ├── WEB_APP_URL: String                  # KDS Web 应用 URL
│   ├── scannerLauncher: ActivityResultLauncher    # 扫码结果接收器
│   ├── scanSuccessCallback: String?         # 扫码成功回调函数名
│   ├── scanErrorCallback: String?           # 扫码失败回调函数名
│   ├── evidencePhotoLauncher: ActivityResultLauncher  # 拍照结果接收器
│   ├── evidenceSuccessCallback: String?     # 拍照成功回调函数名
│   ├── evidenceErrorCallback: String?       # 拍照失败回调函数名
│   ├── filePathCallback: ValueCallback<Array<Uri>>?  # WebView 文件选择回调
│   ├── cameraPhotoUri: Uri?                 # 相机拍照 URI
│   ├── cameraPhotoFile: File?               # 相机拍照文件
│   ├── fusedLocationClient: FusedLocationProviderClient  # GPS 客户端
│   └── cameraPermissionLauncher: ActivityResultLauncher  # 权限请求器
│
├── 生命周期方法
│   └── onCreate()                           # 初始化所有组件
│
├── WebView 相关
│   ├── clearWebViewCache()                  # 强制清除缓存（规范 2.1）
│   ├── setupWebView()                       # 配置 WebView 环境（规范 2.2）
│   └── loadErrorPage(view)                  # 加载自定义错误页面
│
├── 扫码相关
│   ├── setupScannerLauncher()               # 注册扫码结果回调
│   └── startScanActivity(success, error)    # 启动扫码 Activity
│
├── 拍照相关
│   ├── setupEvidencePhotoLauncher()         # 注册拍照结果回调
│   ├── startEvidencePhotoActivity(s, e)     # 启动拍照 Activity
│   ├── setupFileChooserLaunchers()          # WebView <input type="file"> 支持
│   ├── checkPermissionsAndCapture()         # 检查相机+GPS权限
│   ├── launchSystemCamera()                 # 打开 CameraX 拍照
│   └── createImageFile()                    # 创建临时照片文件
│
├── JS 回调
│   └── runJsCallback(callbackName, args)    # 执行 JS 回调函数（规范 5.1）
│
└── 工具方法
    ├── deleteDir(dir)                       # 递归删除目录
    └── setupBackPressHandler()              # 返回键处理（WebView 历史）
```

#### 5.1.2 WebView 配置详情

| 配置项 | 值 | 原因 |
|--------|-----|------|
| `javaScriptEnabled` | `true` | Web 应用依赖 JavaScript |
| `domStorageEnabled` | `true` | 允许 LocalStorage/SessionStorage |
| `databaseEnabled` | `true` | 允许 IndexedDB/WebSQL |
| `mediaPlaybackRequiresUserGesture` | `false` | KDS 自动播放订单提示音 |
| `mixedContentMode` | `ALWAYS_ALLOW` | 允许 HTTPS 页面加载 HTTP 资源 |

#### 5.1.3 缓存清除策略

每次 `onCreate` 时执行以下清除操作（在 `loadUrl` 之前）：
1. `webView.clearCache(true)` — 清除 WebView 内部缓存
2. `CookieManager.removeAllCookies(null)` — 清除所有 Cookie
3. `CookieManager.flush()` — 刷新 Cookie 管理器
4. `webView.clearFormData()` — 清除表单数据
5. `deleteDir(app_webview)` — 递归删除 WebView 数据目录

#### 5.1.4 错误处理

当 WebView 加载失败时（网络错误或 HTTP 错误），显示一个自定义的内联 HTML 错误页面，包含：
- 渐变背景的卡片式布局
- 中文提示信息 "系统暂时无法访问"
- "重新加载" 按钮 (`location.reload()`)
- **不暴露具体 URL 信息**（安全考虑）

#### 5.1.5 `<input type="file">` 支持

WebView 的 `WebChromeClient.onShowFileChooser` 被重写以支持两种模式：

| 模式 | 触发条件 | 行为 |
|------|----------|------|
| **拍照模式** | `fileChooserParams.isCaptureEnabled == true` | 检查权限 → 启动 EvidencePhotoActivity |
| **相册模式** | `isCaptureEnabled == false` | 打开系统文件选择器（支持多选） |

#### 5.1.6 返回键处理

通过 `OnBackPressedCallback` 实现：
- 如果 WebView 有浏览历史 → `webView.goBack()`
- 如果没有历史 → 正常退出行为

---

### 5.2 WebAppInterface — JavaScript 桥接层

**文件**: `app/src/main/java/com/toptea/topteakds/WebAppInterface.kt`
**注入名称**: `window.AndroidBridge`
**职责**: 作为 Web 端与 Native 端的通信桥梁，暴露 4 个核心接口

#### 5.2.1 接口列表

| 方法签名 | 线程模型 | 功能描述 |
|----------|----------|----------|
| `printJob(jsonPayload, successCb, errorCb)` | `Dispatchers.IO` 协程 | 解析打印负载，委托 PrinterService 执行 |
| `startScan(successCb, errorCb)` | `runOnUiThread` | 启动 ScannerActivity |
| `savePrinterConfig(type, ip, port, mac, successCb, errorCb)` | `Dispatchers.IO` 协程 | 保存打印机配置到 SharedPreferences |
| `takeEvidencePhoto(successCb, errorCb)` | `runOnUiThread` | 启动 EvidencePhotoActivity |

#### 5.2.2 线程安全设计

```
@JavascriptInterface 方法运行在 WebView 的 Binder 线程上，而非 UI 线程。
因此：
  - 需要 UI 操作的（启动 Activity）→ 使用 runOnUiThread {}
  - 需要 I/O 操作的（打印、保存配置）→ 使用 CoroutineScope(Dispatchers.IO) 协程
  - 回调 JS 的 → 通过 MainActivity.runJsCallback() 在 UI 线程执行
```

#### 5.2.3 回调机制详解

```kotlin
// 回调执行流程:
// 1. Web 调用:  AndroidBridge.printJob(data, "onPrintOK", "onPrintFail")
// 2. Native 执行打印任务
// 3. 成功时:    evaluateJavascript("window.onPrintOK()")
// 4. 失败时:    evaluateJavascript("window.onPrintFail('错误信息')")
```

参数序列化规则（`runJsCallback` 方法）：

| 参数类型 | 序列化方式 |
|----------|-----------|
| `String` | `JSONObject.quote(arg)` — 安全转义引号等特殊字符 |
| `Number` | `arg.toString()` — 直接转字符串 |
| `Boolean` | `arg.toString()` — `true` / `false` |
| `null` | 字面量 `"null"` |
| 其他 | `JSONObject.quote(arg.toString())` |

---

### 5.3 PrinterService — 打印服务

**文件**: `app/src/main/java/com/toptea/topteakds/PrinterService.kt`
**类型**: `object` 单例
**职责**: 接收 JSON 打印指令，连接打印机，发送 ESC/POS 或 TSPL 指令流

#### 5.3.1 打印流程

```
executePrint(context, jsonPayloadString)
    │
    ├── 1. 读取打印机配置 (ConfigManager.loadConfig)
    │
    ├── 2. 解析 JSON 负载
    │   ├── size: "58mm" | "80mm" | "50x30 mm"
    │   └── commands: JSONArray
    │
    ├── 3. 判断打印模式
    │   ├── 包含 "x" → TSPL 标签模式
    │   └── 不包含 "x" → ESC/POS 收据模式
    │
    ├── 4. 建立连接
    │   ├── WIFI → Socket(ip, port)
    │   ├── BLUETOOTH → [未实现]
    │   └── USB → [未实现]
    │
    ├── 5. 发送初始化指令
    │   ├── TSPL: "SIZE W mm, H mm\n" + "GAP 2 mm, 0 mm\n" + "CLS\n"
    │   └── ESC/POS: 0x1B 0x40 (ESC @)
    │
    ├── 6. 循环执行指令 (commands[])
    │   ├── "text"    → 文本输出
    │   ├── "kv"      → 键值对输出
    │   ├── "divider" → 分隔线
    │   ├── "feed"    → 走纸
    │   └── "cut"     → 切刀 (仅 ESC/POS)
    │
    ├── 7. 发送结束指令
    │   ├── TSPL: "PRINT 1,1\n"
    │   └── ESC/POS: 走纸 + 切刀
    │
    └── 8. 关闭连接 (finally)
```

#### 5.3.2 JSON 打印负载格式

```json
{
  "size": "80mm",
  "commands": [
    { "type": "text", "value": "订单 #1234" },
    { "type": "divider", "char": "-" },
    { "type": "kv", "key": "芒果冰沙", "value": "x2" },
    { "type": "kv", "key": "珍珠奶茶", "value": "x1" },
    { "type": "divider", "char": "=" },
    { "type": "feed", "lines": 3 },
    { "type": "cut" }
  ]
}
```

#### 5.3.3 支持的指令类型

| 指令类型 | 字段 | ESC/POS 行为 | TSPL 行为 |
|----------|------|-------------|-----------|
| `text` | `value` | GBK 编码文本输出 + 换行 | `TEXT x,y,"TSS24.BF2",0,1,1,"value"` |
| `kv` | `key`, `value` | `key: value\n` GBK 编码 | `TEXT x,y,"TSS24.BF2",0,1,1,"key: value"` |
| `divider` | `char` (默认 `-`) | 重复字符填充（58mm=32列, 80mm=48列） | 未实现 (TODO) |
| `feed` | `lines` (默认 1) | 输出 N 个换行符 | 不单独使用 |
| `cut` | 无 | `0x1D 0x56 0x01` (GS V 1 全切) | 不适用 |

#### 5.3.4 连接类型

| 类型 | 状态 | 连接方式 |
|------|------|----------|
| **WIFI** | 已实现 | `Socket(ip, port)` — 默认端口 9100 |
| **BLUETOOTH** | 未实现 | 需使用厂商 BT SDK + MAC 地址 |
| **USB** | 未实现 | 需使用厂商 USB SDK |

#### 5.3.5 字符编码

- ESC/POS 模式下文本使用 **GBK 编码** (`charset("GBK")`)，适配中文热敏打印机
- TSPL 模式下使用字体 `TSS24.BF2`（繁体/简体中文 24 点阵）

---

### 5.4 ConfigManager — 配置管理器

**文件**: `app/src/main/java/com/toptea/topteakds/ConfigManager.kt`
**类型**: `object` 单例
**存储方式**: `SharedPreferences` (文件名: `TopTeaPrinterConfig`)

#### 5.4.1 数据模型

```kotlin
data class PrinterConfig(
    val type: String,       // "WIFI" | "BLUETOOTH" | "USB"
    val ip: String,         // 打印机 IP 地址（WIFI 模式用）
    val port: Int,          // 端口号（默认 9100）
    val macAddress: String  // 蓝牙 MAC 地址（BLUETOOTH 模式用）
)
```

#### 5.4.2 SharedPreferences 键值表

| 键 (Key) | 类型 | 默认值 | 说明 |
|----------|------|--------|------|
| `PRINTER_TYPE` | String | `"WIFI"` | 打印机连接类型 |
| `PRINTER_IP` | String | `""` | 打印机 IP |
| `PRINTER_PORT` | Int | `9100` | 打印端口 |
| `PRINTER_MAC` | String | `""` | 蓝牙 MAC 地址 |

#### 5.4.3 接口

| 方法 | 描述 |
|------|------|
| `saveConfig(context, type, ip, port, macAddress)` | 保存配置（异步 `apply()`） |
| `loadConfig(context): PrinterConfig` | 读取配置（同步） |

---

### 5.5 ScannerActivity — 条码扫描

**文件**: `app/src/main/java/com/toptea/topteakds/ScannerActivity.kt`
**职责**: 使用前置摄像头实时扫描条码/二维码

#### 5.5.1 技术方案

| 组件 | 库/API | 作用 |
|------|--------|------|
| 相机预览 | CameraX `Preview` + `PreviewView` | 实时摄像头画面 |
| 图像分析 | CameraX `ImageAnalysis` | 逐帧图像分析 |
| 条码识别 | Google ML Kit `BarcodeScanning` | 条码/二维码解码 |

#### 5.5.2 相机选择

- **强制使用前置摄像头** (`CameraSelector.DEFAULT_FRONT_CAMERA`)
- 设计意图：KDS 平板通常面朝操作员，前置摄像头朝向顾客/扫码区域

#### 5.5.3 扫描流程

```
onCreate()
    │
    ├── checkCameraPermission()
    │   ├── 已授权 → startCamera()
    │   └── 未授权 → requestCameraPermission()
    │
    └── startCamera()
        │
        ├── 初始化 CameraExecutor (单线程池)
        ├── 获取 ProcessCameraProvider
        │
        └── bindCameraUseCases()
            │
            ├── Preview → surfaceProvider
            │
            └── ImageAnalysis → BarcodeAnalyzer
                │
                └── analyze(imageProxy)
                    │
                    ├── InputImage.fromMediaImage()
                    ├── scanner.process(image)
                    │
                    ├── 成功 → barcodes.firstOrNull()
                    │   └── onBarcodeFound(rawValue)
                    │       └── returnSuccess(data)
                    │           └── setResult(RESULT_OK) + finish()
                    │
                    └── 完成 → imageProxy.close()
```

#### 5.5.4 BarcodeAnalyzer 内部类

- 实现 `ImageAnalysis.Analyzer` 接口
- 使用 `@ExperimentalGetImage` 注解访问底层 Image
- 将 `ImageProxy` 转换为 `InputImage` 并传递给 ML Kit
- 通过 `isScanProcessed` 标志确保只返回一次结果
- `BarcodeScanner` 实例由外部传入，在 `ScannerActivity.onDestroy()` 中关闭

#### 5.5.5 返回值

| 结果 | Extra Key | 数据 |
|------|-----------|------|
| 成功 | `scannedData` | 条码原始文本 (`rawValue`) |
| 失败/取消 | `errorMessage` | 错误描述字符串 |

---

### 5.6 EvidencePhotoActivity — 取证拍照

**文件**: `app/src/main/java/com/toptea/topteakds/EvidencePhotoActivity.kt`
**职责**: 使用后置摄像头拍照，附带 GPS 定位信息写入 EXIF 元数据

#### 5.6.1 设计要点

| 特性 | 说明 |
|------|------|
| **GPS 强制模式** | 必须获取到 GPS 定位后才允许拍照（`currentLocation == null` 时禁止拍照） |
| **双重 EXIF 写入** | CameraX `Metadata.location` + 手动 `forceWriteExif()` 双保险 |
| **EXIF 验证** | 拍照后立即验证 EXIF 中的 GPS 坐标是否成功写入 |
| **双返回模式** | 文件路径模式（WebView 文件选择器用）/ Base64 模式（JS Bridge 用） |

#### 5.6.2 GPS 获取策略

```
startGps()
    │
    ├── 1. getCurrentLocation(PRIORITY_HIGH_ACCURACY)
    │   ├── 成功 → GPS: Locked (绿色)
    │   └── 返回 null
    │       │
    │       └── 2. fusedLocationClient.lastLocation
    │           ├── 成功 → GPS: Last Known (黄色)
    │           └── 失败 → GPS: Unavailable (红色)
    │
    └── 失败 → GPS: Error (红色)
```

#### 5.6.3 EXIF 写入内容

`forceWriteExif()` 方法向照片写入以下 EXIF 标签：

| EXIF 标签 | 内容 |
|-----------|------|
| GPS 经纬度 | `exif.setLatLong(lat, lon)` |
| GPS 海拔 | `exif.setAltitude(alt)` (如可用) |
| `TAG_DATETIME` | 当前时间 (`yyyy:MM:dd HH:mm:ss`) |
| `TAG_DATETIME_ORIGINAL` | 同上 |
| `TAG_DATETIME_DIGITIZED` | 同上 |
| `TAG_MAKE` | `"Google"` |
| `TAG_MODEL` | `"TopTeaKDS"` |
| `TAG_SOFTWARE` | `"TopTeaKDS App"` |
| `TAG_OFFSET_TIME` | 时区偏移 (如 `+08:00`) |
| `TAG_OFFSET_TIME_ORIGINAL` | 同上 |
| `TAG_OFFSET_TIME_DIGITIZED` | 同上 |

#### 5.6.4 拍照流程

```
takePhoto()
    │
    ├── 检查 currentLocation != null
    │   └── null → Toast提示 + 重新获取GPS → 中断
    │
    ├── setLoading(true)
    │
    ├── 创建输出文件
    │   ├── 有 EXTRA_OUTPUT_PATH → 使用指定路径
    │   └── 无 → 创建临时文件
    │
    ├── 设置 ImageCapture.Metadata.location
    │
    └── imageCapture.takePicture()
        │
        ├── onError → setLoading(false) + returnError()
        │
        └── onImageSaved
            │
            └── cameraExecutor.execute (后台线程)
                │
                ├── forceWriteExif(photoFile, location)
                ├── verifyExif(photoFile)
                │
                └── runOnUiThread
                    └── handleCaptureSuccess(photoFile)
                        │
                        ├── 文件路径模式 → setResult(RESULT_OK) + finish()
                        │
                        └── Base64 模式
                            ├── readBytes() + Base64.encodeToString()
                            ├── 删除临时文件
                            └── returnSuccess(base64String)
```

#### 5.6.5 双返回模式

| 模式 | 触发条件 | 返回数据 |
|------|----------|----------|
| **文件路径模式** | `intent.getStringExtra(EXTRA_OUTPUT_PATH) != null` | 无数据，文件已保存到指定路径 |
| **Base64 模式** | `EXTRA_OUTPUT_PATH` 为 null | `EXTRA_PHOTO_DATA` = Base64 编码的 JPEG |

#### 5.6.6 UI 状态管理

| 状态 | ProgressBar | StatusText | CaptureButton | CancelButton |
|------|-------------|------------|---------------|-------------|
| 正常 | GONE | GONE | Visible + Enabled | Enabled |
| 拍照中 | VISIBLE | VISIBLE ("Processing...") | INVISIBLE + Disabled | Disabled |

---

## 6. 数据流与交互时序

### 6.1 打印流程时序

```
 Web App                  WebAppInterface          PrinterService       ConfigManager      打印机
   │                            │                       │                    │               │
   │  printJob(json,ok,err)     │                       │                    │               │
   │ ─────────────────────────► │                       │                    │               │
   │                            │  scope.launch(IO)     │                    │               │
   │                            │ ────────────────────► │                    │               │
   │                            │                       │  loadConfig()      │               │
   │                            │                       │ ─────────────────► │               │
   │                            │                       │  PrinterConfig     │               │
   │                            │                       │ ◄───────────────── │               │
   │                            │                       │                    │               │
   │                            │                       │  Socket(ip, port)                  │
   │                            │                       │ ────────────────────────────────► │
   │                            │                       │  ESC/POS 指令流                    │
   │                            │                       │ ────────────────────────────────► │
   │                            │                       │  close()                           │
   │                            │                       │ ────────────────────────────────► │
   │                            │  成功                  │                    │               │
   │                            │ ◄──────────────────── │                    │               │
   │  window.ok()               │                       │                    │               │
   │ ◄───────────────────────── │                       │                    │               │
```

### 6.2 扫码流程时序

```
 Web App           WebAppInterface        MainActivity         ScannerActivity      ML Kit
   │                     │                      │                     │                │
   │  startScan(ok,err)  │                      │                     │                │
   │ ──────────────────► │                      │                     │                │
   │                     │  runOnUiThread        │                     │                │
   │                     │ ───────────────────► │                     │                │
   │                     │                      │  startActivity       │                │
   │                     │                      │ ──────────────────► │                │
   │                     │                      │                     │  startCamera   │
   │                     │                      │                     │ ─────────────► │
   │                     │                      │                     │  process(image)│
   │                     │                      │                     │ ─────────────► │
   │                     │                      │                     │  barcodes[]    │
   │                     │                      │                     │ ◄───────────── │
   │                     │                      │  RESULT_OK(data)    │                │
   │                     │                      │ ◄────────────────── │                │
   │                     │                      │                     │                │
   │  window.ok(data)    │                      │                     │                │
   │ ◄───────────────────────────────────────── │                     │                │
```

### 6.3 取证拍照流程时序

```
 Web App       WebAppInterface      MainActivity     EvidencePhotoActivity     GPS
   │                 │                    │                    │                 │
   │ takeEvidence    │                    │                    │                 │
   │ Photo(ok,err)   │                    │                    │                 │
   │ ──────────────► │                    │                    │                 │
   │                 │  runOnUiThread     │                    │                 │
   │                 │ ─────────────────► │                    │                 │
   │                 │                    │  startActivity     │                 │
   │                 │                    │ ─────────────────► │                 │
   │                 │                    │                    │  startGps()     │
   │                 │                    │                    │ ──────────────► │
   │                 │                    │                    │  Location       │
   │                 │                    │                    │ ◄────────────── │
   │                 │                    │                    │                 │
   │                 │                    │                    │ [用户点击拍照]   │
   │                 │                    │                    │ takePicture()   │
   │                 │                    │                    │ forceWriteExif()│
   │                 │                    │                    │ verifyExif()    │
   │                 │                    │                    │                 │
   │                 │                    │  RESULT_OK(base64) │                 │
   │                 │                    │ ◄───────────────── │                 │
   │                 │                    │                    │                 │
   │ window.ok(b64)  │                    │                    │                 │
   │ ◄───────────────────────────────────  │                    │                 │
```

---

## 7. 权限模型

### 7.1 AndroidManifest 权限声明

| 权限 | 用途 | 限制条件 |
|------|------|----------|
| `INTERNET` | WebView 加载远程页面 + WiFi 打印 Socket 连接 | 无 |
| `CAMERA` | 条码扫描 + 取证拍照 | 运行时权限 |
| `ACCESS_FINE_LOCATION` | 取证拍照 GPS 定位 | 运行时权限 |
| `ACCESS_COARSE_LOCATION` | 粗略定位（降级方案） | 运行时权限 |
| `BLUETOOTH` | 蓝牙打印 | `maxSdkVersion="30"` |
| `BLUETOOTH_ADMIN` | 蓝牙管理 | `maxSdkVersion="30"` |
| `BLUETOOTH_SCAN` | 蓝牙扫描 (Android 12+) | 运行时权限 |
| `BLUETOOTH_CONNECT` | 蓝牙连接 (Android 12+) | 运行时权限 |

### 7.2 硬件特性声明

```xml
<uses-feature android:name="android.hardware.camera" android:required="true" />
```

设备**必须**具备摄像头才能安装此应用。

### 7.3 运行时权限请求时机

| 场景 | 请求的权限 | 请求方式 |
|------|-----------|----------|
| 扫码 (`ScannerActivity`) | `CAMERA` | `ActivityCompat.requestPermissions` |
| 拍照 (`EvidencePhotoActivity`) | `CAMERA` + `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` | `ActivityResultContracts.RequestMultiplePermissions` |
| WebView 文件选择器拍照 | `CAMERA` + `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION` | `ActivityResultContracts.RequestMultiplePermissions` |

---

## 8. UI 布局设计

### 8.1 MainActivity 布局 (`activity_main.xml`)

```
┌──────────────────────────────────────────┐
│                                          │
│              WebView (全屏)               │
│         id: webView                      │
│         约束: 四边贴合父容器               │
│                                          │
│      加载: https://store.toptea.es/kds/  │
│                                          │
└──────────────────────────────────────────┘
```

- 使用 `ConstraintLayout` 作为根布局
- WebView 占满整个屏幕 (`0dp` + 四边约束)
- 支持 Edge-to-Edge 显示

### 8.2 ScannerActivity 布局 (`activity_scanner.xml`)

```
┌──────────────────────────────────────────┐
│                                          │
│           PreviewView (全屏)              │
│         id: previewView                  │
│         前置摄像头实时预览                 │
│                                          │
└──────────────────────────────────────────┘
```

- 使用 `FrameLayout` 作为根布局
- `PreviewView` 占满整个屏幕
- 无 UI 控件（自动检测条码后立即返回结果）

### 8.3 EvidencePhotoActivity 布局 (`activity_evidence_photo.xml`)

```
┌──────────────────────────────────────────┐
│ ┌──────────────┐                         │
│ │GPS: Waiting..│  (gpsStatusTextView)    │
│ └──────────────┘                         │
│                                          │
│           PreviewView (全屏背景)          │
│         id: viewFinder                   │
│         后置摄像头实时预览                 │
│                                          │
│                              ┌──────┐    │
│                              │  ◉   │    │ (camera_capture_button)
│                              │快门键│    │  80x80dp, 居右垂直居中
│                              └──────┘    │
│                                          │
│  ┌────────┐     ┌─────────────────────┐  │
│  │ Cancel │     │ ProgressBar + Text  │  │ (默认隐藏)
│  └────────┘     └─────────────────────┘  │
└──────────────────────────────────────────┘
```

- 使用 `ConstraintLayout` 作为根布局，黑色背景
- GPS 状态指示器位于左上角（半透明黑色背景）
- 快门按钮 (`ImageButton`) 使用自定义 `ic_shutter_button` drawable（白色圆环+实心圆）
- 取消按钮位于左下角
- 加载状态指示器（ProgressBar + TextView）默认隐藏，拍照时显示

### 8.4 快门按钮设计 (`ic_shutter_button.xml`)

- `layer-list` drawable，两层叠加
- 外层：70dp 白色圆环（`stroke 4dp`，透明填充）
- 内层：白色实心圆（内缩 10dp padding）
- 模仿 iOS/Android 相机快门按钮样式

---

## 9. 构建配置

### 9.1 模块级 `app/build.gradle.kts`

| 配置项 | 值 |
|--------|-----|
| `namespace` | `com.toptea.topteakds` |
| `compileSdk` | 35 |
| `minSdk` | 26 |
| `targetSdk` | 35 |
| `versionCode` | 1 |
| `versionName` | `"1.0"` |
| `multiDexEnabled` | `true` |
| `jvmTarget` | `"11"` |
| `sourceCompatibility` | Java 11 |
| `targetCompatibility` | Java 11 |
| `isMinifyEnabled` | `false` (release) |
| `viewBinding` | `true` |

### 9.2 签名配置

- **Release 构建**: 使用系统内置的 `debug` 签名 (`signingConfigs.getByName("debug")`)
- **无自定义 signingConfigs 块** — 之前的 release keystore 引用因密码错误导致 `BadPaddingException`，已被移除
- 正式发布前需配置正确的 release keystore

### 9.3 Gradle 属性 (`gradle.properties`)

| 属性 | 值 | 说明 |
|------|-----|------|
| `org.gradle.jvmargs` | `-Xmx2048m -Dfile.encoding=UTF-8` | JVM 内存配置 |
| `android.useAndroidX` | `true` | 使用 AndroidX |
| `kotlin.code.style` | `official` | Kotlin 代码风格 |
| `android.nonTransitiveRClass` | `true` | R 类资源隔离 |
| `org.gradle.caching` | `false` | 禁用缓存（避免加密异常） |
| `org.gradle.vfs.watch` | `false` | 禁用虚拟文件系统监控 |
| `org.gradle.daemon` | `false` | 禁用 Gradle 守护进程 |

### 9.4 Gradle Wrapper

- **Gradle 版本**: 8.10.2（匹配 AGP 8.8.0 要求）

### 9.5 项目设置 (`settings.gradle.kts`)

```kotlin
rootProject.name = "TOPTEA_KDS"
include(":app")
```

---

## 10. 安全设计

### 10.1 网络安全

| 措施 | 实现方式 |
|------|----------|
| 允许明文 HTTP | `android:usesCleartextTraffic="true"` — 用于内网打印机通信 |
| 混合内容 | `MIXED_CONTENT_ALWAYS_ALLOW` — Web 应用可能混合 HTTP/HTTPS 资源 |

> **注意**: 当前配置允许明文流量，适用于内网环境。若部署到公网应考虑加强限制。

### 10.2 错误页面安全

- WebView 加载失败时显示自定义错误页面
- **不暴露具体 URL**，仅在 Log 中记录 `errorCode` 和 `description`
- 防止敏感内网地址泄露给用户

### 10.3 JavaScript Bridge 安全

- `WebAppInterface` 使用 `@JavascriptInterface` 注解标记公开方法
- 参数通过 `JSONObject.quote()` 进行安全转义，防止 JS 注入
- Bridge 仅暴露必要的 4 个接口方法

### 10.4 文件安全

- `FileProvider` 配置在 `file_paths.xml` 中限定访问范围
  - `external-cache-path` — 外部缓存目录
  - `files-path` — 内部文件目录
- Provider 设置为 `android:exported="false"` + `android:grantUriPermissions="true"`

### 10.5 ProGuard

- 当前 `isMinifyEnabled = false`（关闭混淆）
- `proguard-rules.pro` 包含注释提示需要保留 JavaScript Interface 类
- 启用混淆前需添加规则保留 `WebAppInterface` 的公开方法

---

## 11. 待实现功能 (TODO)

以下是代码中标记为 TODO 的待实现项：

| 编号 | 位置 | 描述 | 优先级 |
|------|------|------|--------|
| T-001 | `MainActivity.kt:44` | 替换 `WEB_APP_URL` 为生产环境 URL | 高 |
| T-002 | `PrinterService.kt:55-59` | 实现蓝牙打印连接逻辑 | 中 |
| T-003 | `PrinterService.kt:62-65` | 实现 USB 打印连接逻辑 | 中 |
| T-004 | `PrinterService.kt:97` | 确认 TSPL 打印机的标签间隙设置 | 低 |
| T-005 | `PrinterService.kt:103` | 添加 TSPL DENSITY/SPEED 等初始化指令 | 低 |
| T-006 | `PrinterService.kt:121-128` | 完善 ESC/POS text 指令的 size/align 处理 | 中 |
| T-007 | `PrinterService.kt:144` | 实现 TSPL 模式的分隔线 (BAR) 指令 | 低 |
| T-008 | `PrinterService.kt:189` | 添加蓝牙/USB 连接的 finally 关闭逻辑 | 中 |
| T-009 | 签名配置 | 配置正式发布用的 release keystore | 高 |
| T-010 | `proguard-rules.pro` | 启用 ProGuard 并配置保留规则 | 中 |

---

## 12. 附录：文件清单

### 12.1 Kotlin 源文件

| 文件 | 行数 | 描述 |
|------|------|------|
| `MainActivity.kt` | 542 | 主入口，WebView 容器，Activity 调度中心 |
| `WebAppInterface.kt` | 121 | JavaScript Bridge 接口 |
| `PrinterService.kt` | 192 | 打印任务执行服务 (ESC/POS + TSPL) |
| `ConfigManager.kt` | 54 | 打印机配置持久化 |
| `ScannerActivity.kt` | 205 | CameraX + ML Kit 条码扫描 |
| `EvidencePhotoActivity.kt` | 379 | CameraX + GPS + EXIF 取证拍照 |

### 12.2 布局文件

| 文件 | 描述 |
|------|------|
| `activity_main.xml` | 全屏 WebView |
| `activity_scanner.xml` | 全屏相机预览 |
| `activity_evidence_photo.xml` | 相机预览 + 快门键 + GPS 状态 + 进度条 |

### 12.3 XML 配置

| 文件 | 描述 |
|------|------|
| `AndroidManifest.xml` | 应用清单（3 个 Activity + FileProvider） |
| `file_paths.xml` | FileProvider 路径映射 |
| `backup_rules.xml` | 数据备份规则 |
| `data_extraction_rules.xml` | 数据提取规则 |

### 12.4 Gradle 配置

| 文件 | 描述 |
|------|------|
| `build.gradle.kts` (项目级) | Plugin 声明 |
| `app/build.gradle.kts` (模块级) | 编译/依赖/签名配置 |
| `settings.gradle.kts` | 项目名 + 模块声明 |
| `gradle.properties` | JVM 参数 + AndroidX 开关 |
| `gradle/libs.versions.toml` | 版本目录 |
| `gradle-wrapper.properties` | Gradle 8.10.2 |
| `proguard-rules.pro` | ProGuard 规则（默认关闭） |

---

> **文档结束**
> 本文档基于截至 2026-02-06 的代码仓库自动分析生成。
