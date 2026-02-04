/*
 * 文件名: app/src/main/java/com/toptea/topteakds/MainActivity.kt
 * 描述: 规范 2.0, APK 的主入口和 WebView 容器
 */
package com.toptea.topteakds

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.FileProvider
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.toptea.topteakds.databinding.ActivityMainBinding
import org.json.JSONObject
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView

    // POS/KDS 的 Web URL
    // TODO: 替换为你的 KDS 生产环境 URL
    private val WEB_APP_URL = "https://store.toptea.es/kds/"

    // 规范 4.2/5.2: 扫码回调
    private lateinit var scannerLauncher: ActivityResultLauncher<Intent>
    private var scanSuccessCallback: String? = null
    private var scanErrorCallback: String? = null

    // 拍照取证回调
    private lateinit var evidencePhotoLauncher: ActivityResultLauncher<Intent>
    private var evidenceSuccessCallback: String? = null
    private var evidenceErrorCallback: String? = null

    // WebView 文件选择 (<input type="file">) 回调
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null
    private var cameraPhotoFile: File? = null
    private lateinit var fileChooserCameraLauncher: ActivityResultLauncher<Intent>
    private lateinit var fileChooserGalleryLauncher: ActivityResultLauncher<Intent>

    // GPS 定位客户端
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // 相机+GPS 运行时权限请求 launcher
    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<Array<String>>

    companion object {
        const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        webView = binding.webView
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // 规范 2.1: 必须在加载 URL 之前执行强制缓存清除
        clearWebViewCache()

        setupScannerLauncher()
        setupEvidencePhotoLauncher()
        setupFileChooserLaunchers()
        setupWebView()
        setupBackPressHandler()

        // 加载 URL
        webView.loadUrl(WEB_APP_URL)
    }

    /**
     * 规范 2.1: 启动时强制缓存清除
     */
    private fun clearWebViewCache() {
        Log.w(TAG, "--- Executing FORCE cache clear (Spec 2.1) ---")
        try {
            webView.clearCache(true)
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            webView.clearFormData()
            deleteDir(File(filesDir.parentFile, "app_webview"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear WebView cache", e)
        }
    }

    /**
     * 注册 WebView 文件选择器（<input type="file">）所需的 ActivityResultLauncher
     */
    private fun setupFileChooserLaunchers() {
        // 权限请求回调
        cameraPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
            val fineLocGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            val coarseLocGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

            if (cameraGranted && (fineLocGranted || coarseLocGranted)) {
                // 权限全部授权，启动相机
                launchSystemCamera()
            } else {
                Log.e(TAG, "Camera or Location permission denied")
                filePathCallback?.onReceiveValue(null)
                filePathCallback = null
            }
        }

        // 拍照回调
        fileChooserCameraLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && cameraPhotoUri != null && cameraPhotoFile != null) {
                // CameraX Activity saved the file with EXIF already.
                filePathCallback?.onReceiveValue(arrayOf(cameraPhotoUri!!))
            } else {
                // User cancelled or error
                filePathCallback?.onReceiveValue(null)
            }
            filePathCallback = null
            cameraPhotoUri = null
            cameraPhotoFile = null
        }

        // 相册选择回调
        fileChooserGalleryLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val data = result.data!!
                val uris = mutableListOf<Uri>()
                if (data.clipData != null) {
                    val count = data.clipData!!.itemCount
                    for (i in 0 until count) {
                        uris.add(data.clipData!!.getItemAt(i).uri)
                    }
                } else if (data.data != null) {
                    uris.add(data.data!!)
                }
                filePathCallback?.onReceiveValue(uris.toTypedArray())
            } else {
                filePathCallback?.onReceiveValue(null)
            }
            filePathCallback = null
        }
    }

    /**
     * 创建用于相机拍照的临时文件
     */
    private fun createImageFile(): File? {
        return try {
            val storageDir = externalCacheDir ?: filesDir
            File.createTempFile(
                "filechooser_${System.currentTimeMillis()}",
                ".jpg",
                storageDir
            )
        } catch (e: IOException) {
            Log.e(TAG, "Failed to create image file", e)
            null
        }
    }

    /**
     * 检查相机和GPS运行时权限
     */
    private fun checkPermissionsAndCapture() {
        val cameraOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val fineLocOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseLocOk = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (cameraOk && (fineLocOk || coarseLocOk)) {
            launchSystemCamera()
        } else {
            cameraPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    /**
     * 打开系统相机拍照 (Now uses EvidencePhotoActivity with CameraX)
     */
    private fun launchSystemCamera() {
        val photoFile = createImageFile()
        if (photoFile != null) {
            cameraPhotoFile = photoFile
            val authority = "${applicationContext.packageName}.fileprovider"
            cameraPhotoUri = FileProvider.getUriForFile(this, authority, photoFile)

            // Use EvidencePhotoActivity (CameraX) instead of system camera
            val cameraIntent = Intent(this, EvidencePhotoActivity::class.java).apply {
                putExtra(EvidencePhotoActivity.EXTRA_OUTPUT_PATH, photoFile.absolutePath)
            }
            fileChooserCameraLauncher.launch(cameraIntent)
        } else {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
        }
    }

    /**
     * 加载通用错误页面，不显示URL信息
     */
    private fun loadErrorPage(view: WebView?) {
        val errorHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>系统错误</title>
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
                        display: flex;
                        justify-content: center;
                        align-items: center;
                        min-height: 100vh;
                        margin: 0;
                        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                        padding: 20px;
                    }
                    .error-container {
                        background: white;
                        border-radius: 16px;
                        padding: 40px;
                        box-shadow: 0 10px 40px rgba(0, 0, 0, 0.1);
                        text-align: center;
                        max-width: 400px;
                        width: 100%;
                    }
                    .error-icon {
                        font-size: 64px;
                        margin-bottom: 20px;
                    }
                    h1 {
                        color: #333;
                        font-size: 24px;
                        margin: 0 0 16px 0;
                        font-weight: 600;
                    }
                    p {
                        color: #666;
                        font-size: 16px;
                        line-height: 1.6;
                        margin: 0 0 24px 0;
                    }
                    .retry-button {
                        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                        color: white;
                        border: none;
                        padding: 14px 32px;
                        border-radius: 8px;
                        font-size: 16px;
                        font-weight: 500;
                        cursor: pointer;
                        transition: transform 0.2s, box-shadow 0.2s;
                        box-shadow: 0 4px 12px rgba(102, 126, 234, 0.4);
                    }
                    .retry-button:active {
                        transform: scale(0.98);
                        box-shadow: 0 2px 8px rgba(102, 126, 234, 0.4);
                    }
                    .tips {
                        margin-top: 24px;
                        padding-top: 24px;
                        border-top: 1px solid #eee;
                        font-size: 14px;
                        color: #999;
                    }
                </style>
            </head>
            <body>
                <div class="error-container">
                    <div class="error-icon">⚠️</div>
                    <h1>系统暂时无法访问</h1>
                    <p>抱歉，系统当前无法连接。请检查您的网络连接后重试。</p>
                    <button class="retry-button" onclick="location.reload()">重新加载</button>
                    <div class="tips">
                        如问题持续存在，请联系技术支持
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()

        view?.loadDataWithBaseURL(null, errorHtml, "text/html", "UTF-8", null)
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

        // 允许混合内容
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // 规范 3.1: 注入 JS 对象
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.i(TAG, "WebView page loaded")
            }

            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                // 日志记录错误但不暴露URL
                Log.e(TAG, "WebView error: code=$errorCode, description=$description")
                // 加载自定义错误页面，不显示URL
                loadErrorPage(view)
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                // 只处理主页面的HTTP错误，忽略资源请求错误
                if (request?.isForMainFrame == true) {
                    val statusCode = errorResponse?.statusCode ?: 0
                    Log.e(TAG, "WebView HTTP error: statusCode=$statusCode")
                    // 加载自定义错误页面
                    loadErrorPage(view)
                }
            }
        }

        // 支持 <input type="file"> 文件选择
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // 如果上一次回调还未完成，先取消
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                val captureEnabled = fileChooserParams?.isCaptureEnabled ?: false

                if (captureEnabled) {
                    // 先检查相机+GPS权限，授权后再获取GPS定位并打开相机
                    checkPermissionsAndCapture()
                } else {
                    // 打开相册选择器
                    val galleryIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "image/*"
                        putExtra(
                            Intent.EXTRA_ALLOW_MULTIPLE,
                            fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                        )
                    }
                    fileChooserGalleryLauncher.launch(
                        Intent.createChooser(galleryIntent, "选择照片")
                    )
                }
                return true
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

        val argsString = args.joinToString(",") { arg ->
            when (arg) {
                is String -> JSONObject.quote(arg)
                is Number -> arg.toString()
                is Boolean -> arg.toString()
                null -> "null"
                else -> JSONObject.quote(arg.toString())
            }
        }

        val script = "javascript:try { window.$callbackName($argsString); } catch(e) { console.error('JS callback $callbackName failed:', e); }"
        Log.d(TAG, "Running JS Callback: $script")

        runOnUiThread {
            webView.evaluateJavascript(script, null)
        }
    }

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

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }
}