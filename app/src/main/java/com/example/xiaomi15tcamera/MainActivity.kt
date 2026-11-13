
package com.example.xiaomi15tcamera

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.view.*
import android.widget.*
import android.graphics.Color
import java.io.OutputStream

class MainActivity : Activity() {

    private lateinit var texture: TextureView
    private lateinit var cameraManager: CameraManager
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var characteristics: CameraCharacteristics? = null
    private var cameraId: String = ""
    private var previewBuilder: CaptureRequest.Builder? = null
    private var handler: Handler? = null
    private var thread: HandlerThread? = null

    private var iso = 400
    private var exposure = 10000000L
    private var pendingImage: Image? = null
    private var pendingResult: TotalCaptureResult? = null

    private lateinit var isoText: TextView
    private lateinit var shutterText: TextView
    private lateinit var isoBar: SeekBar
    private lateinit var shutterBar: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        texture = TextureView(this)
        root.addView(texture, LinearLayout.LayoutParams(
            -1, 0, 1f
        ))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 8)
        }

        isoText = TextView(this).apply {
            setTextColor(Color.WHITE)
            text = "ISO: 400"
        }
        controls.addView(isoText)

        isoBar = SeekBar(this).apply { max = 100 }
        controls.addView(isoBar)

        shutterText = TextView(this).apply {
            setTextColor(Color.WHITE)
            text = "Shutter: 1/100 sec"
        }
        controls.addView(shutterText)

        shutterBar = SeekBar(this).apply { max = 100 }
        controls.addView(shutterBar)

        val capture = Button(this).apply {
            text = "CAPTURE DNG RAW"
            setOnClickListener { takePhoto() }
        }
        controls.addView(capture)

        root.addView(controls)
        setContentView(root)

        isoBar.setOnSeekBarChangeListener(listener { value ->
            iso = 50 + value * 1950 / 100
            isoText.text = "ISO: $iso"
            updatePreview()
        })

        shutterBar.setOnSeekBarChangeListener(listener { value ->
            exposure = 1000000L + value * 99000000L
            shutterText.text = "Shutter: 1/${(1000000000L / exposure).coerceAtLeast(1)} sec"
            updatePreview()
        })

        texture.surfaceTextureListener = object :
            TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture, width: Int, height: Int
            ) {
                openCamera()
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture, width: Int, height: Int
            ) {}

            override fun onSurfaceTextureDestroyed(
                surface: SurfaceTexture
            ): Boolean = true

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    private fun listener(change: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                bar: SeekBar?, value: Int, user: Boolean
            ) {
                if (user) change(value)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        }

    override fun onResume() {
        super.onResume()
        thread = HandlerThread("CameraThread").also {
            it.start()
            handler = Handler(it.looper)
        }
        if (::texture.isInitialized && texture.isAvailable) openCamera()
    }

    private fun openCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 10)
            return
        }

        try {
            cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val c = cameraManager.getCameraCharacteristics(id)
                c.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK &&
                    c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                        ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
            } ?: run {
                Toast.makeText(this, "No RAW camera available", Toast.LENGTH_LONG).show()
                return
            }

            characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val c = characteristics!!
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val rawSize = map.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR)
                ?.maxByOrNull { it.width.toLong() * it.height } ?: return

            reader = ImageReader.newInstance(
                rawSize.width, rawSize.height,
                android.graphics.ImageFormat.RAW_SENSOR, 2
            )

            reader!!.setOnImageAvailableListener({ source ->
                val image = source.acquireNextImage()
                synchronized(this) {
                    pendingImage?.close()
                    pendingImage = image
                    saveIfReady()
                }
            }, handler)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    startPreview()
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    camera = null
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    camera = null
                    runOnUiThread {
                        Toast.makeText(this@MainActivity,
                            "Camera error: $error", Toast.LENGTH_LONG).show()
                    }
                }
            }, handler)
        } catch (e: Exception) {
            Toast.makeText(this, e.message ?: "Camera error", Toast.LENGTH_LONG).show()
        }
    }

    private fun startPreview() {
        val device = camera ?: return
        val surfaceTexture = texture.surfaceTexture ?: return
        surfaceTexture.setDefaultBufferSize(1920, 1080)
        val previewSurface = Surface(surfaceTexture)

        try {
            previewBuilder = device.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            ).apply {
                addTarget(previewSurface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            }

            device.createCaptureSession(
                listOf(previewSurface, reader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        updatePreview()
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity,
                                "Camera session failed", Toast.LENGTH_LONG).show()
                        }
                    }
                }, handler
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Preview error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updatePreview() {
        try {
            val builder = previewBuilder ?: return
            val c = characteristics ?: return
            val range = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val minIso = range?.lower ?: 50
            val maxIso = range?.upper ?: 3200

            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso.coerceIn(minIso, maxIso))
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
            session?.setRepeatingRequest(builder.build(), null, handler)
        } catch (_: Exception) {}
    }

    private fun takePhoto() {
        try {
            val device = camera ?: return
            val s = session ?: return
            val c = characteristics ?: return
            val request = device.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE
            ).apply {
                addTarget(reader!!.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
            }

            synchronized(this) {
                pendingImage?.close()
                pendingImage = null
                pendingResult = null
            }

            s.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    synchronized(this@MainActivity) {
                        pendingResult = result
                        saveIfReady()
                    }
                }
            }, handler)

            Toast.makeText(this, "Capturing RAW...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Capture failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveIfReady() {
        val image = pendingImage ?: return
        val result = pendingResult ?: return
        pendingImage = null
        pendingResult = null

        Thread {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME,
                        "Xiaomi15T_RAW_${System.currentTimeMillis()}.dng")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                    if (Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.Images.Media.RELATIVE_PATH,
                            "Pictures/15T RAW")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }

                val uri = contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                ) ?: throw Exception("Could not create image file")

                contentResolver.openOutputStream(uri).use { output ->
                    DngCreator(characteristics!!, result)
                        .writeImage(output!!, image)
                }

                if (Build.VERSION.SDK_INT >= 29) {
                    val done = ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }
                    contentResolver.update(uri, done, null, null)
                }

                runOnUiThread {
                    Toast.makeText(this, "DNG RAW saved to Pictures/15T RAW",
                        Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Save failed: ${e.message}",
                        Toast.LENGTH_LONG).show()
                }
            } finally {
                image.close()
            }
        }.start()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            openCamera()
        }
    }

    override fun onPause() {
        super.onPause()
        session?.close()
        camera?.close()
        reader?.close()
        session = null
        camera = null
        reader = null
        thread?.quitSafely()
        thread = null
        handler = null
    }
}
