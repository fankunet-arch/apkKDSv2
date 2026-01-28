/*
 * 文件名: app/src/main/java/com/toptea/topteakds/ScannerActivity.kt
 * 描述: 规范 4.2 和 7.2, 负责扫码 (使用后置摄像头，如不可用则自动切换前置)
 */
package com.toptea.topteakds

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.toptea.topteakds.databinding.ActivityScannerBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScannerBinding
    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var barcodeScanner: BarcodeScanner

    private var isScanProcessed = false // 确保只返回一次结果

    companion object {
        const val CAMERA_PERMISSION_REQUEST_CODE = 101
        const val EXTRA_SCANNED_DATA = "scannedData"
        const val EXTRA_ERROR_MESSAGE = "errorMessage"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (checkCameraPermission()) {
            startCamera()
        } else {
            requestCameraPermission()
        }
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(android.Manifest.permission.CAMERA),
            CAMERA_PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                // 规范 7.2: 用户拒绝权限
                Log.e("ScannerActivity", "Camera permission denied by user")
                returnError("Camera permission was denied")
            }
        }
    }

    private fun startCamera() {
        cameraExecutor = Executors.newSingleThreadExecutor()
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        barcodeScanner = BarcodeScanning.getClient() // 规范 7.2: 使用 ML Kit

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases(cameraProvider)
            } catch (e: Exception) {
                Log.e("ScannerActivity", "Failed to get CameraProvider", e)
                returnError("Failed to initialize camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases(cameraProvider: ProcessCameraProvider) {
        val preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                // 传入共享的 barcodeScanner 避免资源泄漏
                it.setAnalyzer(cameraExecutor, BarcodeAnalyzer(barcodeScanner) { scannedData ->
                    // 规范 7.2: 扫描到第一个有效条码
                    if (!isScanProcessed) {
                        isScanProcessed = true
                        Log.d("ScannerActivity", "Barcode found: $scannedData")
                        returnSuccess(scannedData)
                    }
                })
            }

        // 使用后置摄像头进行条码扫描（分辨率更高，扫码效果更好）
        // 如果后置摄像头不可用，尝试使用前置摄像头作为备选
        try {
            cameraProvider.unbindAll()

            // 首先尝试后置摄像头
            val backCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.bindToLifecycle(
                    this,
                    backCameraSelector,
                    preview,
                    imageAnalysis
                )
                Log.d("ScannerActivity", "Using back camera for scanning")
            } catch (backCameraException: Exception) {
                // 后置摄像头不可用，尝试前置摄像头
                Log.w("ScannerActivity", "Back camera unavailable, trying front camera", backCameraException)
                val frontCameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                cameraProvider.bindToLifecycle(
                    this,
                    frontCameraSelector,
                    preview,
                    imageAnalysis
                )
                Log.d("ScannerActivity", "Using front camera for scanning (fallback)")
            }
        } catch (e: Exception) {
            Log.e("ScannerActivity", "No camera available for scanning", e)
            returnError("Failed to initialize camera: ${e.message}")
        }
    }

    private fun returnSuccess(data: String) {
        val resultIntent = Intent().apply {
            putExtra(EXTRA_SCANNED_DATA, data)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }



    private fun returnError(errorMessage: String) {
        val resultIntent = Intent().apply {
            putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
        }
        setResult(Activity.RESULT_CANCELED, resultIntent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
        if (::barcodeScanner.isInitialized) {
            barcodeScanner.close()
        }
    }
}

/**
 * 规范 7.2: 使用 ML Kit Barcode Scanning 分析图像
 * 注意: scanner 由外部传入并管理生命周期，避免资源泄漏
 */
private class BarcodeAnalyzer(
    private val scanner: BarcodeScanner,
    private val onBarcodeFound: (String) -> Unit
) : ImageAnalysis.Analyzer {

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    // 规范 7.2: 返回结果
                    barcodes.firstOrNull()?.rawValue?.let {
                        onBarcodeFound(it)
                    }
                }
                .addOnFailureListener {
                    Log.e("BarcodeAnalyzer", "ML Kit scanning failed", it)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }
}