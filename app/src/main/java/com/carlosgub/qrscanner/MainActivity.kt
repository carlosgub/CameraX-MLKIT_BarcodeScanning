package com.carlosgub.qrscanner

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.Image
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.android.synthetic.main.activity_main.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity(), CameraXConfig.Provider, QRUtil.Listener {

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0
    }

    private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    private var qrUtil = QRUtil()
    private var preview: Preview? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var onPause = false //Verificar que el activity no esta en OnPause


    /** Blocking camera operations are performed using this executor */
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        //Verificar Permisos
        if (allPermissionsGranted()) {
            pvMain.post { startCamera() }
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        // Initialize our background executor
        qrUtil.setListener(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        btClearImage.setOnClickListener {
            nextImage()
            btClearImage.visibility = View.GONE
        }
    }

    private fun startCamera() {
        //Obtener las metricas
        val metrics = DisplayMetrics().also { pvMain.display.getRealMetrics(it) }

        //Calcular el ratio de la pantalla
        val screenAspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)

        //Obtener la rotacion
        val rotation = pvMain.display.rotation

        //Request a CameraProvider
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        // Bind the CameraProvider to the LifeCycleOwner
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        cameraProviderFuture.addListener(Runnable {


            // CameraProvider
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview
            preview = Preview.Builder()
                // We request aspect ratio but no resolution
                .setTargetAspectRatio(screenAspectRatio)
                // Set initial target rotation
                .setTargetRotation(rotation)
                .build()

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(pvMain.previewSurfaceProvider)

            // ImageCapture
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                // We request aspect ratio but no resolution to match preview config, but letting
                // CameraX optimize for whatever specific resolution best fits our use cases
                .setTargetAspectRatio(screenAspectRatio)
                // Set initial target rotation, we will have to call this again if rotation changes
                // during the lifecycle of this use case
                .setTargetRotation(rotation)
                .build()

            // Must unbind the use-cases before rebinding them
            cameraProvider.unbindAll()

            try {
                // A variable number of use-cases can be passed here -
                // camera provides access to CameraControl & CameraInfo
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )

                nextImage()
            } catch (exc: Exception) {
                Log.e(":)", "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onError(error: String?) {
        Toast.makeText(this, error, Toast.LENGTH_LONG).show()
    }

    override fun onSuccess(barcodeValue: String) {
        Toast.makeText(this, barcodeValue, Toast.LENGTH_LONG).show()
        btClearImage.visibility = View.VISIBLE
    }

    override fun nextImage() {

        // Setup image capture listener which is triggered after photo has been taken
        imageCapture?.takePicture( cameraExecutor, object: ImageCapture.OnImageCapturedCallback() {
            @SuppressLint("UnsafeExperimentalUsageError")
            override fun onCaptureSuccess(image: ImageProxy) {
                image.image?.toBitmap()?.let {
                    if (!onPause) qrUtil.getQRCodeDetails(it)
                }
            }

            override fun onError(exception: ImageCaptureException) {

            }
        })
    }

    fun Image.toBitmap(): Bitmap {
        val buffer = planes[0].buffer
        buffer.rewind()
        val bytes = ByteArray(buffer.capacity())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    override fun onPause() {
        super.onPause()
        onPause = true
    }

    override fun onResume() {
        super.onResume()
        onPause = false
        btClearImage.performClick()
    }

    override fun getCameraXConfig(): CameraXConfig {
        return Camera2Config.defaultConfig()
    }

    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                pvMain.post { startCamera() }
            } else {
                Toast.makeText(
                    this,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Shut down our background executor
        cameraExecutor.shutdown()
    }
}
