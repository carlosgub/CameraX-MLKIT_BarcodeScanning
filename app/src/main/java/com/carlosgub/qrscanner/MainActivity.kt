package com.carlosgub.qrscanner

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.media.Image
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.carlosgub.qrscanner.databinding.MainActivityBinding
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity(), QRUtil.Listener {

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0
    }

    private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

    private lateinit var binding: MainActivityBinding
    private var qrUtil = QRUtil()
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var displayId: Int = -1
    private var onPause = false //Verificar que el activity no esta en OnPause

    private val displayManager by lazy {
        this.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    /**
     * We need a display listener for orientation changes that do not trigger a configuration
     * change, for example if we choose to override config change in manifest or for 180-degree
     * orientation changes.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = binding.pvMain.let { view ->
            if (displayId == this@MainActivity.displayId) {
                imageCapture?.targetRotation = view.display.rotation
            }
        }
    }


    /** Blocking camera operations are performed using this executor */
    private lateinit var cameraExecutor: ExecutorService

    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = MainActivityBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        verifyCameraPermission()

        qrUtil.setListener(this)

        // Initialize our background executor
        cameraExecutor = Executors.newSingleThreadExecutor()
        // Every time the orientation of device changes, update rotation for use cases
        displayManager.registerDisplayListener(displayListener, null)

        binding.btContinueScanning.setOnClickListener {
            nextImage()
            binding.btContinueScanning.visibility = View.GONE
        }
    }

    private fun verifyCameraPermission() {
        if (allPermissionsGranted()) {
            // Keep track of the display in which this view is attached
            initCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun initCamera() {
        binding.pvMain.post {
            displayId = binding.pvMain.display.displayId
            startCamera()
            nextImage()
        }
    }

    private fun startCamera() {
        //Obtener las metricas
        val metrics = DisplayMetrics().also { binding.pvMain.display.getRealMetrics(it) }

        //Calcular el ratio de la pantalla
        val screenAspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)

        //Obtener la rotacion
        val rotation = binding.pvMain.display.rotation

        //Request a CameraProvider
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        // Bind the CameraProvider to the LifeCycleOwner
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        cameraProviderFuture.addListener({

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
            preview?.setSurfaceProvider(binding.pvMain.surfaceProvider)

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


            try {
                // A variable number of use-cases can be passed here -
                // camera provides access to CameraControl & CameraInfo
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
                nextImage()
            } catch (exc: Exception) {
                Log.e(":)", "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    override fun onError(error: String?) {
        Toast.makeText(this, error, Toast.LENGTH_LONG).show()
    }

    override fun onSuccess(barcodeValue: String) {
        Toast.makeText(this, barcodeValue, Toast.LENGTH_LONG).show()
        binding.btContinueScanning.visibility = View.VISIBLE
    }

    @SuppressLint("UnsafeExperimentalUsageError")
    override fun nextImage() {
        // Setup image capture listener which is triggered after photo has been taken
        imageCapture?.let { imageCapture ->
            imageCapture.takePicture(
                cameraExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        if (image.image != null) {
                            image.image!!.toBitmap().let {
                                if (!onPause) qrUtil.getQRCodeDetails(it)
                            }
                        } else {
                            nextImage()
                        }
                        image.close()
                    }

                    override fun onError(exception: ImageCaptureException) {
                        onError(exception.message)
                    }
                })
        }
    }

    fun Image.toBitmap(): Bitmap {
        val buffer = planes[0].buffer
        buffer.rewind()
        val bytes = ByteArray(buffer.capacity())
        buffer.get(bytes)
        return reduceBitmapSize(BitmapFactory.decodeByteArray(bytes, 0, bytes.size), 500)
    }

    fun reduceBitmapSize(image: Bitmap, maxSize: Int): Bitmap {
        var width = 50
        var height = 50
        val bitmapRatio = width.toFloat() / height.toFloat()
        if (bitmapRatio > 1) {
            width = maxSize
            height = (width / bitmapRatio).toInt()
        } else {
            height = maxSize
            width = (height * bitmapRatio).toInt()
        }
        return Bitmap.createScaledBitmap(image, width, height, true)
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
                initCamera()
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

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onResume() {
        super.onResume()
        onPause = false
        if (imageCapture != null) binding.btContinueScanning.performClick()
    }

    override fun onPause() {
        super.onPause()
        onPause = true
    }

    override fun onDestroy() {
        super.onDestroy()

        // Shut down our background executor
        cameraExecutor.shutdown()
        displayManager.unregisterDisplayListener(displayListener)
    }
}
