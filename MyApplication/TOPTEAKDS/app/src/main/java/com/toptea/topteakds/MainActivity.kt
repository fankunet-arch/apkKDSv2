/*
 * 文件名: app/src/main/java/com/toptea/topteakds/MainActivity.kt
 * 描述: 规范 2.0, APK 的主入口和 WebView 容器
 */
package com.toptea.topteakds // <-- 已修正

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.toptea.topteakds.databinding.ActivityMainBinding // <-- 已修正
import org.json.JSONObject
import java.io.File
// import com.toptea.topteakds.WebAppInterface (在同一个包下, 无需 import)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView

    // POS/KDS 的 Web URL
    // TODO: 替换为你的 KDS 生产环境 URL
    private val WEB_APP_URL = "https://store.toptea.es/kds/" // 示例 URL (请修改为 KDS URL)

    // 规范 4.2/5.2: 扫码回调
    private lateinit var scannerLauncher: ActivityResultLauncher<Intent>
    private var scanSuccessCallback: String? = null
    private var scanErrorCallback: String? = null

    // 拍照取证回调
    private lateinit var evidencePhotoLauncher: ActivityResultLauncher<Intent>
    private var evidenceSuccessCallback: String? = null
    private var evidenceErrorCallback: String? = null

    companion object {
        const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        webView = binding.webView

        // 规范 2.1: 必须在加载 URL 之前执行强制缓存清除
        clearWebViewCache()

        setupScannerLauncher()
        setupEvidencePhotoLauncher()
        setupWebView()
        setupBackPressHandler()

        // 加载 URL
        webView.loadUrl(WEB_APP_URL)
    }

    /**
     * 规范 2.1: 启动时强制缓存清除
     * 这是规范中的必达项
     */
    private fun clearWebViewCache() {
        Log.w(TAG, "--- Executing FORCE cache clear (Spec 2.1) ---")
        try {
            // 1. 标准网页缓存
            webView.clearCache(true)
            Log.d(TAG, "clearCache(true) done.")

            // 2. Cookies
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            Log.d(TAG, "removeAllCookies() done.")

            // 3. 表单数据
            webView.clearFormData()
            Log.d(TAG, "clearFormData() done.")

            // 4. WebStorage (Local Storage, Session Storage) 和 IndexedDB
            // 这是最彻底的方式：删除 webview 存储数据的目录
            deleteDir(File(filesDir.parentFile, "app_webview"))
            Log.d(TAG, "WebStorage / IndexedDB (app_webview) directory deleted.")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear WebView cache", e)
        }
    }

    /**
     * 规范 2.2: WebView 环境要求
     * 规范 3.1: 注入 JavaScript 对象
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings

        // 规范 2.2
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true    // 允许 Local Storage / Session Storage
        settings.databaseEnabled = true      // 允许 IndexedDB / WebSQL
        settings.mediaPlaybackRequiresUserGesture = false // 允许 KDS 自动播放提示音

        // 允许混合内容 (如果你的 URL 是 HTTPS 但需要访问本地 IP 打印机)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // 规范 3.1: 注入 JS 对象
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.i(TAG, "WebView page loaded: $url")
                // 你可以在这里注入一个 JS 来通知 Web 端 "AndroidBridge is ready"
                // webView.evaluateJavascript("javascript:window.androidBridgeReady=true;", null)
            }
        }
    }

    /**
     * 规范 7.2: 准备扫码 Activity 启动器
     */
    private fun setupScannerLauncher() {
        scannerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                // 扫码成功
                val data = result.data?.getStringExtra(ScannerActivity.EXTRA_SCANNED_DATA)
                if (data != null && scanSuccessCallback != null) {
                    runJsCallback(scanSuccessCallback!!, data)
                }
            } else {
                // 扫码失败或取消
                val error = result.data?.getStringExtra(ScannerActivity.EXTRA_ERROR_MESSAGE)
                    ?: "Scan was cancelled by user"
                if (scanErrorCallback != null) {
                    runJsCallback(scanErrorCallback!!, error)
                }
            }
            // 清理回调
            scanSuccessCallback = null
            scanErrorCallback = null
        }
    }

    /**
     * 规范 4.2: 由 WebAppInterface 调用, 启动扫码
     */
    fun startScanActivity(successCallback: String, errorCallback: String) {
        // 保存回调名称, 以便在 onActivityResult 中使用
        this.scanSuccessCallback = successCallback
        this.scanErrorCallback = errorCallback

        val intent = Intent(this, ScannerActivity::class.java)
        scannerLauncher.launch(intent)
    }

    /**
     * 准备拍照取证 Activity 启动器
     */
    private fun setupEvidencePhotoLauncher() {
        evidencePhotoLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data?.getStringExtra(EvidencePhotoActivity.EXTRA_PHOTO_DATA)
                if (data != null && evidenceSuccessCallback != null) {
                    runJsCallback(evidenceSuccessCallback!!, data)
                }
            } else {
                val error = result.data?.getStringExtra(EvidencePhotoActivity.EXTRA_ERROR_MESSAGE)
                    ?: "Photo capture was cancelled"
                if (evidenceErrorCallback != null) {
                    runJsCallback(evidenceErrorCallback!!, error)
                }
            }
            evidenceSuccessCallback = null
            evidenceErrorCallback = null
        }
    }

    /**
     * 由 WebAppInterface 调用, 启动拍照取证
     */
    fun startEvidencePhotoActivity(successCallback: String, errorCallback: String) {
        this.evidenceSuccessCallback = successCallback
        this.evidenceErrorCallback = errorCallback

        val intent = Intent(this, EvidencePhotoActivity::class.java)
        evidencePhotoLauncher.launch(intent)
    }

    /**
     * 规范 5.1: 异步回调机制
     * 确保所有 Native-to-JS 的回调都在 UI 线程执行
     */
    fun runJsCallback(callbackName: String, vararg args: Any?) {
        if (callbackName.isEmpty()) return

        // 将参数转义为 JS 字符串
        // 注意: JSONObject.quote() 返回的字符串已经包含双引号，不需要再加引号
        val argsString = args.joinToString(",") { arg ->
            when (arg) {
                is String -> JSONObject.quote(arg) // quote() 已返回带双引号的字符串
                is Number -> arg.toString()
                is Boolean -> arg.toString()
                null -> "null"
                else -> JSONObject.quote(arg.toString())
            }
        }

        val script = "javascript:try { window.$callbackName($argsString); } catch(e) { console.error('JS callback $callbackName failed:', e); }"
        Log.d(TAG, "Running JS Callback: $script")

        // 必须在 UI 线程执行
        runOnUiThread {
            webView.evaluateJavascript(script, null)
        }
    }

    /**
     * 递归删除目录 (用于 clearWebViewCache)
     */
    private fun deleteDir(dir: File?): Boolean {
        if (dir == null || !dir.exists()) return true
        if (dir.isDirectory) {
            val children = dir.list()
            if (children != null) {
                for (child in children) {
                    val success = deleteDir(File(dir, child))
                    if (!success) {
                        return false
                    }
                }
            }
        }
        return dir.delete()
    }

    /**
     * 使用 OnBackPressedCallback 替代已废弃的 onBackPressed()
     * 兼容 Android 13+ (API 33+)
     */
    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 允许 WebView 后退
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    // 禁用此回调以允许系统处理返回
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }
}