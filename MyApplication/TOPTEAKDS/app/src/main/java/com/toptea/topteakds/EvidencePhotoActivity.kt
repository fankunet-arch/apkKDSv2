package com.toptea.topteakds

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class EvidencePhotoActivity : AppCompatActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var currentLocation: Location? = null

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var viewFinder: PreviewView
    private lateinit var captureButton: ImageButton
    private lateinit var cancelButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusTextView: TextView
    private lateinit var gpsStatusTextView: TextView

    companion object {
        const val TAG = "EvidencePhotoActivity"
        const val EXTRA_PHOTO_DATA = "photoData"
        const val EXTRA_ERROR_MESSAGE = "errorMessage"
        const val EXTRA_OUTPUT_PATH = "outputFilePath"
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
            val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

            if (cameraGranted && (fineLocationGranted || coarseLocationGranted)) {
                startCamera()
                startGps()
            } else {
                returnError("Required permissions (Camera & Location) denied")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_evidence_photo)

        viewFinder = findViewById(R.id.viewFinder)
        captureButton = findViewById(R.id.camera_capture_button)
        cancelButton = findViewById(R.id.camera_cancel_button)
        progressBar = findViewById(R.id.progressBar)
        statusTextView = findViewById(R.id.statusTextView)
        gpsStatusTextView = findViewById(R.id.gpsStatusTextView)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        captureButton.setOnClickListener { takePhoto() }
        cancelButton.setOnClickListener {
             setResult(Activity.RESULT_CANCELED)
             finish()
        }

        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        val camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        val fineLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarseLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)

        if (camera == PackageManager.PERMISSION_GRANTED &&
            (fineLoc == PackageManager.PERMISSION_GRANTED || coarseLoc == PackageManager.PERMISSION_GRANTED)
        ) {
            startCamera()
            startGps()
        } else {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        updateGpsStatus("GPS: Acquiring...", Color.RED)
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    currentLocation = location
                    updateGpsStatus("GPS: Locked", Color.GREEN)
                    Log.d(TAG, "GPS Location acquired: $location")
                } else {
                     // Try last known location
                     fusedLocationClient.lastLocation.addOnSuccessListener { lastLocation ->
                         if (lastLocation != null) {
                             currentLocation = lastLocation
                             updateGpsStatus("GPS: Last Known", Color.YELLOW)
                             Log.d(TAG, "Last GPS Location acquired: $lastLocation")
                         } else {
                             updateGpsStatus("GPS: Unavailable", Color.RED)
                         }
                     }.addOnFailureListener {
                         updateGpsStatus("GPS: Failed", Color.RED)
                     }
                }
            }
            .addOnFailureListener {
                Log.e(TAG, "Failed to get location: ${it.message}")
                updateGpsStatus("GPS: Error", Color.RED)
            }
    }

    private fun updateGpsStatus(text: String, color: Int) {
        runOnUiThread {
            gpsStatusTextView.text = text
            gpsStatusTextView.setTextColor(color)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY) // Maximizing quality for better metadata handling
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
                returnError("Camera initialization failed: ${exc.message}")
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        // Strict Mode: Do not allow capture without location
        if (currentLocation == null) {
            Toast.makeText(this, "Waiting for GPS location...", Toast.LENGTH_SHORT).show()
            // Try to fetch again just in case
            startGps()
            return
        }

        // UI feedback
        setLoading(true)

        // Create output file
        val outputFilePath = intent.getStringExtra(EXTRA_OUTPUT_PATH)
        val photoFile = if (outputFilePath != null) {
            File(outputFilePath)
        } else {
             File.createTempFile("evidence_${System.currentTimeMillis()}", ".jpg", externalCacheDir ?: filesDir)
        }

        val metadata = ImageCapture.Metadata()
        metadata.location = currentLocation

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile)
            .setMetadata(metadata)
            .build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    setLoading(false)
                    returnError("Photo capture failed: ${exc.message}")
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d(TAG, "Photo capture succeeded: ${output.savedUri}")

                    // Force write EXIF just to be safe (Double Insurance)
                    // Run on background thread to avoid UI freeze
                    cameraExecutor.execute {
                        var verificationSuccess = false
                        if (currentLocation != null) {
                            try {
                                forceWriteExif(photoFile, currentLocation!!)
                                verificationSuccess = verifyExif(photoFile)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to force write EXIF", e)
                            }
                        }

                        // Handle success on UI thread
                        runOnUiThread {
                             if (verificationSuccess) {
                                 Toast.makeText(this@EvidencePhotoActivity, "GPS Info Saved: ${currentLocation?.latitude}, ${currentLocation?.longitude}", Toast.LENGTH_SHORT).show()
                             }
                            handleCaptureSuccess(photoFile)
                        }
                    }
                }
            }
        )
    }

    private fun forceWriteExif(file: File, location: Location) {
        val exif = ExifInterface(file.absolutePath)

        // 1. GPS
        exif.setLatLong(location.latitude, location.longitude)
        if (location.hasAltitude()) {
             exif.setAltitude(location.altitude)
        }

        // 2. Date/Time
        val now = Date()
        val sdf = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        val dateString = sdf.format(now)

        exif.setAttribute(ExifInterface.TAG_DATETIME, dateString)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateString)
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateString)

        // 3. Device Info (Make user happy: "Google product")
        exif.setAttribute(ExifInterface.TAG_MAKE, "Google")
        exif.setAttribute(ExifInterface.TAG_MODEL, "TopTeaKDS")
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "TopTeaKDS App")

        // 4. Offset Time
        val tz = TimeZone.getDefault()
        val sdfTz = SimpleDateFormat("Z", Locale.US)
        sdfTz.timeZone = tz
        val offset = sdfTz.format(now) // e.g., +0800
        // EXIF expects format like "+08:00"
        if (offset.length == 5) {
             val exifOffset = offset.substring(0, 3) + ":" + offset.substring(3)
             exif.setAttribute(ExifInterface.TAG_OFFSET_TIME, exifOffset)
             exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, exifOffset)
             exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, exifOffset)
        }

        exif.saveAttributes()
        Log.d(TAG, "Forced EXIF write completed for ${file.absolutePath}")
    }

    private fun verifyExif(file: File): Boolean {
        return try {
            val exif = ExifInterface(file.absolutePath)
            val latLong = exif.latLong
            if (latLong != null) {
                Log.d(TAG, "EXIF Verification PASSED: Lat=${latLong[0]}, Lon=${latLong[1]}")
                true
            } else {
                Log.e(TAG, "EXIF Verification FAILED: No Lat/Lon found")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "EXIF Verification Error", e)
            false
        }
    }

    private fun handleCaptureSuccess(photoFile: File) {
         val outputFilePath = intent.getStringExtra(EXTRA_OUTPUT_PATH)

         if (outputFilePath != null) {
             // Mode: File Path Return (for MainActivity)
             // File is already saved at the correct path with EXIF
             setResult(Activity.RESULT_OK)
             finish()
         } else {
             // Mode: Base64 Return (for JS Interface)
             try {
                val fileBytes = FileInputStream(photoFile).use { it.readBytes() }
                val base64 = Base64.encodeToString(fileBytes, Base64.NO_WRAP)

                // Cleanup temp file
                if (photoFile.exists()) {
                    photoFile.delete()
                }

                returnSuccess(base64)
             } catch (e: Exception) {
                 Log.e(TAG, "Error processing photo", e)
                 returnError("Error processing photo: ${e.message}")
             }
         }
    }

    private fun setLoading(loading: Boolean) {
        if (loading) {
            progressBar.visibility = View.VISIBLE
            statusTextView.visibility = View.VISIBLE
            captureButton.isEnabled = false
            captureButton.visibility = View.INVISIBLE
            cancelButton.isEnabled = false
        } else {
            progressBar.visibility = View.GONE
            statusTextView.visibility = View.GONE
            captureButton.isEnabled = true
            captureButton.visibility = View.VISIBLE
            cancelButton.isEnabled = true
        }
    }

    private fun returnSuccess(data: String) {
        val resultIntent = Intent().apply {
            putExtra(EXTRA_PHOTO_DATA, data)
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
        cameraExecutor.shutdown()
    }
}