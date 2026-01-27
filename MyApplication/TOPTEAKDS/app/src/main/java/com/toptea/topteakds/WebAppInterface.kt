/*
 * 文件名: app/src/main/java/com/toptea/topteakds/WebAppInterface.kt
 * 描述: 规范 3.0 和 4.0, 注入的 window.AndroidBridge 对象
 */
package com.toptea.topteakds // <-- 已修正

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
// import com.toptea.topteakds.MainActivity (在同一个包下, 无需 import)
// import com.toptea.topteakds.PrinterService (在同一个包下, 无需 import)
// import com.toptea.topteakds.ConfigManager (在同一个包下, 无需 import)
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 注入到 WebView 的 JS 桥
 * @param mainActivityRef 对 MainActivity 的引用, 用于启动 Activity 和执行 UI 线程回调
 */
class WebAppInterface(private val mainActivityRef: MainActivity) {

    private val context: Context = mainActivityRef.applicationContext
    private val scope = CoroutineScope(Dispatchers.IO) // 专用 IO 协程

    companion object {
        const val TAG = "WebAppInterface"
    }

    /**
     * 规范 4.1: 核心打印接口
     */
    @JavascriptInterface
    fun printJob(
        jsonPayloadString: String,
        successCallbackName: String,
        errorCallbackName: String
    ) {
        Log.d(TAG, "printJob called. Payload size: ${jsonPayloadString.length}")

        // 规范 5.1: 必须在后台线程执行
        scope.launch {
            try {
                // 委托给 PrinterService 执行 (规范 7.1)
                PrinterService.executePrint(context, jsonPayloadString)

                // 成功回调 (规范 5.0)
                Log.d(TAG, "printJob success. Calling: $successCallbackName")
                mainActivityRef.runJsCallback(successCallbackName)

            } catch (e: Exception) {
                Log.e(TAG, "printJob failed", e)

                // 失败回调 (规范 5.0)
                val errorMsg = e.message ?: "Unknown printing error"
                mainActivityRef.runJsCallback(errorCallbackName, errorMsg)
            }
        }
    }

    /**
     * 规范 4.2: 扫码接口
     */
    @JavascriptInterface
    fun startScan(successCallbackName: String, errorCallbackName: String) {
        Log.d(TAG, "startScan called. Callbacks: $successCallbackName, $errorCallbackName")

        // 扫码必须在 UI 线程启动 Activity
        // 委托给 MainActivity 处理 (规范 7.2)
        mainActivityRef.runOnUiThread {
            mainActivityRef.startScanActivity(successCallbackName, errorCallbackName)
        }
    }

    /**
     * 规范 4.3: 配置管理接口
     */
    @JavascriptInterface
    fun savePrinterConfig(
        type: String,
        ip: String,
        port: Int,
        macAddress: String,
        successCallbackName: String,
        errorCallbackName: String
    ) {
        Log.d(TAG, "savePrinterConfig called: Type=$type, IP=$ip, Port=$port, MAC=$macAddress")

        // 规范 5.1: 异步执行 (虽然 SharedPreferences 很快, 但仍是好习惯)
        scope.launch {
            try {
                // 规范 7.3: 执行逻辑
                ConfigManager.saveConfig(context, type, ip, port, macAddress)

                // 成功回调
                Log.d(TAG, "savePrinterConfig success. Calling: $successCallbackName")
                mainActivityRef.runJsCallback(successCallbackName, "Config saved successfully")

            } catch (e: Exception) {
                Log.e(TAG, "savePrinterConfig failed", e)

                // 失败回调
                val errorMsg = e.message ?: "Failed to save config"
                mainActivityRef.runJsCallback(errorCallbackName, errorMsg)
            }
        }
    }
}