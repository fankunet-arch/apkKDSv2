/*
 * 文件名: app/src/main/java/com/toptea/topteakds/MainActivity.kt
 * 描述: 规范 2.0, APK 的主入口和 WebView 容器
 */
package com.toptea.topteakds // <-- 已修正

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.toptea.topteakds.databinding.ActivityMainBinding
import org.json.JSONObject
import java.io.File
import java.io.IOException

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

    // WebView 文件选择 (<input type="file">) 回调
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null
    private var cameraPhotoFile: File? = null
    private lateinit var fileChooserCameraLauncher: ActivityResultLauncher<Intent>
    private lateinit var fileChooserGalleryLauncher: ActivityResultLauncher<Intent>

    // GPS + 拍照并行状态：两者同时启动，都完成后才写EXIF并返回
    private var gpsLocation: Location? = null  // GPS获取到的位置
    private var gpsFinished = false            // GPS获取是否完成（成功或失败）
    private var photoFinished = false          // 拍照是否完成
    private var photoCancelled = false         // 拍照是否被取消

    // GPS 定位客户端
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // 相机+GPS 运行时权限请求 launcher
    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<Array<String>>

    companion object {
        const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
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
                // 权限全部授权，并行启动GPS获取 + 打开相机
                startGpsFetchAndCamera()
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
                // 拍照成功，标记完成
                photoFinished = true
                photoCancelled = false
                Log.d(TAG, "Photo taken, photoFinished=true, gpsFinished=$gpsFinished")
                tryWriteExifAndReturn()
            } else {
                // 用户取消
                photoFinished = true
                photoCancelled = true
                Log.d(TAG, "Photo cancelled")
                tryWriteExifAndReturn()
            }
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
            startGpsFetchAndCamera()
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
     * 核心：并行启动GPS获取和打开相机。
     * 重置所有并行状态，然后同时启动两个操作。
     * 两者都完成后 tryWriteExifAndReturn() 负责汇合。
     */
    private fun startGpsFetchAndCamera() {
        // 重置并行状态
        gpsLocation = null
        gpsFinished = false
        photoFinished = false
        photoCancelled = false

        // 并行操作1: 启动GPS获取（后台进行）
        startGpsFetch()

        // 并行操作2: 立即打开系统相机（用户零等待）
        launchSystemCamera()
    }

    /**
     * 启动GPS获取。先尝试 lastLocation（瞬时），再尝试 getCurrentLocation。
     * 完成后设置 gpsFinished=true 并调用 tryWriteExifAndReturn()。
     */
    @SuppressLint("MissingPermission")
    private fun startGpsFetch() {
        Log.d(TAG, "GPS fetch started")

        // 第一步：先尝试 lastLocation（瞬时返回，不需要等待硬件）
        fusedLocationClient.lastLocation
            .addOnSuccessListener { lastLoc ->
                if (lastLoc != null) {
                    // lastLocation 有值，直接使用
                    Log.d(TAG, "GPS from lastLocation: lat=${lastLoc.latitude}, lon=${lastLoc.longitude}")
                    gpsLocation = lastLoc
                    gpsFinished = true
                    tryWriteExifAndReturn()
                } else {
                    // lastLocation 为空，需要等 getCurrentLocation
                    Log.d(TAG, "lastLocation is null, requesting getCurrentLocation...")
                    fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                        .addOnSuccessListener { currentLoc ->
                            if (currentLoc != null) {
                                Log.d(TAG, "GPS from getCurrentLocation: lat=${currentLoc.latitude}, lon=${currentLoc.longitude}")
                                gpsLocation = currentLoc
                            } else {
                                Log.w(TAG, "getCurrentLocation returned null")
                            }
                            gpsFinished = true
                            tryWriteExifAndReturn()
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "getCurrentLocation failed: ${e.message}")
                            gpsFinished = true
                            tryWriteExifAndReturn()
                        }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "lastLocation failed: ${e.message}")
                // lastLocation 失败，也尝试 getCurrentLocation
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { currentLoc ->
                        if (currentLoc != null) {
                            Log.d(TAG, "GPS from getCurrentLocation (fallback): lat=${currentLoc.latitude}, lon=${currentLoc.longitude}")
                            gpsLocation = currentLoc
                        }
                        gpsFinished = true
                        tryWriteExifAndReturn()
                    }
                    .addOnFailureListener { e2 ->
                        Log.e(TAG, "getCurrentLocation also failed: ${e2.message}")
                        gpsFinished = true
                        tryWriteExifAndReturn()
                    }
            }
    }

    /**
     * 汇合点：GPS获取和拍照两个并行操作都完成后，执行EXIF写入并返回结果。
     * 任何一方未完成时直接返回，等另一方完成后再次调用。
     */
    private fun tryWriteExifAndReturn() {
        // 两个操作必须都完成
        if (!gpsFinished || !photoFinished) {
            Log.d(TAG, "tryWriteExifAndReturn: waiting (gpsFinished=$gpsFinished, photoFinished=$photoFinished)")
            return
        }

        Log.d(TAG, "tryWriteExifAndReturn: both finished. cancelled=$photoCancelled, gpsLocation=$gpsLocation")

        // 拍照被取消
        if (photoCancelled) {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
            cameraPhotoUri = null
            cameraPhotoFile = null
            return
        }

        val file = cameraPhotoFile
        val uri = cameraPhotoUri

        if (file == null || uri == null) {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
            return
        }

        // 写入GPS EXIF
        val loc = gpsLocation
        if (loc != null) {
            try {
                val exif = ExifInterface(file.absolutePath)
                exif.setLatLong(loc.latitude, loc.longitude)
                exif.saveAttributes()
                Log.d(TAG, "EXIF written OK: lat=${loc.latitude}, lon=${loc.longitude}, file=${file.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "EXIF write FAILED: ${e.message}", e)
            }
        } else {
            Log.w(TAG, "No GPS location available, photo returned without EXIF")
        }

        // 返回URI给WebView
        filePathCallback?.onReceiveValue(arrayOf(uri))
        filePathCallback = null
        cameraPhotoUri = null
        cameraPhotoFile = null
    }

    /**
     * 打开系统相机拍照
     */
    private fun launchSystemCamera() {
        val photoFile = createImageFile()
        if (photoFile != null) {
            cameraPhotoFile = photoFile
            val authority = "${applicationContext.packageName}.fileprovider"
            cameraPhotoUri = FileProvider.getUriForFile(this, authority, photoFile)
            val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri)
            }
            fileChooserCameraLauncher.launch(cameraIntent)
        } else {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
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