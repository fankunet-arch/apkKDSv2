package com.toptea.topteakds

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.io.File
import java.io.FileInputStream
import java.io.IOException

class EvidencePhotoActivity : AppCompatActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var photoUri: Uri? = null
    private var photoFile: File? = null
    private var currentLocation: Location? = null

    companion object {
        const val TAG = "EvidencePhotoActivity"
        const val EXTRA_PHOTO_DATA = "photoData"
        const val EXTRA_ERROR_MESSAGE = "errorMessage"
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
            val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

            if (cameraGranted && (fineLocationGranted || coarseLocationGranted)) {
                getLocationAndTakePhoto()
            } else {
                returnError("Required permissions (Camera & Location) denied")
            }
        }

    private val takePictureLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success) {
                processPhoto()
            } else {
                returnError("User cancelled photo capture")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_evidence_photo)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        val camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        val fineLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarseLoc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)

        if (camera == PackageManager.PERMISSION_GRANTED &&
            (fineLoc == PackageManager.PERMISSION_GRANTED || coarseLoc == PackageManager.PERMISSION_GRANTED)
        ) {
            getLocationAndTakePhoto()
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
    private fun getLocationAndTakePhoto() {
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    currentLocation = location
                    launchCamera()
                } else {
                    fusedLocationClient.lastLocation.addOnSuccessListener { lastLocation ->
                         if (lastLocation != null) {
                             currentLocation = lastLocation
                             launchCamera()
                         } else {
                             returnError("Unable to get GPS location. Please ensure GPS is enabled.")
                         }
                    }.addOnFailureListener {
                         returnError("Failed to retrieve location: ${it.message}")
                    }
                }
            }
            .addOnFailureListener {
                returnError("Failed to get location: ${it.message}")
            }
    }

    private fun launchCamera() {
        try {
            val photoFile = createPhotoFile()
            this.photoFile = photoFile
            val authority = "${applicationContext.packageName}.fileprovider"
            photoUri = FileProvider.getUriForFile(this, authority, photoFile)
            takePictureLauncher.launch(photoUri)
        } catch (e: Exception) {
            returnError("Failed to create temp file: ${e.message}")
        }
    }

    @Throws(IOException::class)
    private fun createPhotoFile(): File {
        val storageDir = externalCacheDir ?: filesDir
        return File.createTempFile(
            "evidence_${System.currentTimeMillis()}",
            ".jpg",
            storageDir
        )
    }

    private fun processPhoto() {
        val file = photoFile ?: return returnError("Photo file lost")
        val location = currentLocation ?: return returnError("Location lost")

        try {
            val exif = ExifInterface(file.absolutePath)
            exif.setLatLong(location.latitude, location.longitude)
            exif.saveAttributes()

            val fileBytes = FileInputStream(file).use { it.readBytes() }
            val base64 = Base64.encodeToString(fileBytes, Base64.NO_WRAP)

            returnSuccess(base64)

            // Clean up the file after reading
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing photo", e)
            returnError("Error processing photo: ${e.message}")
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
}