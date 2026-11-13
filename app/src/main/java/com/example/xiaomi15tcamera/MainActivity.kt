package com.example.xiaomi15tcamera

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.*
import android.graphics.Color
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln

class MainActivity : Activity() {

    private lateinit var manager: CameraManager
    private lateinit var texture: TextureView
    private lateinit var status: TextView
    private lateinit var isoLabel: TextView
    private lateinit var shutterLabel: TextView
    private lateinit var cameraSpinner: Spinner
    private lateinit var wbSpinner: Spinner

    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null
    private var characteristics: CameraCharacteristics? = null
    private var selectedCameraId: String? = null

    private var handlerThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var cameraIds = mutableListOf<String>()
    private var cameraOpening = false

    private var isoMin = 100
    private var isoMax = 3200
    private var shutterMin = 1_000_000L
    private var shutterMax = 100_000_000L

    private var selectedISO = 400
    private var selectedShutter = 10_000_000L
    private var selectedWB = CameraMetadata.CONTROL_AWB_MODE_AUTO

    private val saveLock = Any()
    private var pendingImage: Image? = null
    private var pendingResult: TotalCaptureResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        manager = getSystemService(CAMERA_SERVICE) as CameraManager

        buildInterface()

        handlerThread = HandlerThread("CameraThread").also { it.start() }
        cameraHandler = Handler(handlerThread!!.looper)

        if (checkSelfPermission(Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 100)
        } else {
            findCameras()
        }
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(12, 12, 12, 12)
        }

        texture = TextureView(this)
        root.addView(
            texture,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        status = TextView(this).apply {
            text = "Xiaomi 15T RAW Camera"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(4, 10, 4, 10)
        }
        root.addView(status)

        cameraSpinner = Spinner(this)
        root.addView(cameraSpinner)

        isoLabel = makeLabel("ISO: 400")
        root.addView(isoLabel)

        val isoBar = SeekBar(this).apply {
            max = 1000
            progress = 100
        }
        root.addView(isoBar)

        shutterLabel = makeLabel("Shutter: 1/100")
        root.addView(shutterLabel)

        val shutterBar = SeekBar(this).apply {
            max = 1000
            progress = 500
        }
        root.addView(shutterBar)

        val wbLabel = makeLabel("White balance")
        root.addView(wbLabel)

        wbSpinner = Spinner(this)
        val wbOptions = listOf(
            "Auto",
            "Daylight",
            "Cloudy",
            "Incandescent",
            "Fluorescent"
        )
        wbSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            wbOptions
        )
        root.addView(wbSpinner)

        val captureButton = Button(this).apply {
            text = "CAPTURE DNG RAW"
            textSize = 17f
            setOnClickListener { takePhoto() }
        }
        root.addView(captureButton)

        setContentView(root)

        isoBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    bar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    selectedISO = isoMin +
                        ((isoMax - isoMin) * progress / 1000)
                    isoLabel.text = "ISO: $selectedISO"
                    updatePreview()
                }

                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            }
        )

        shutterBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    bar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    val fraction = progress / 1000.0
                    selectedShutter = (
                        shutterMin * exp(
                            ln(shutterMax.toDouble() / shutterMin) * fraction
                        )
                    ).toLong()

                    shutterLabel.text =
                        "Shutter: ${formatShutter(selectedShutter)}"
                    updatePreview()
                }

                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            }
        )

        wbSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    selectedWB = when (position) {
                        1 -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
                        2 -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
                        3 -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
                        4 -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
                        else -> CameraMetadata.CONTROL_AWB_MODE_AUTO
                    }
                    updatePreview()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

        texture.surfaceTextureListener =
            object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int
                ) {
                    openCamera()
                }

                override fun onSurfaceTextureSizeChanged(
                    surface: SurfaceTexture,
                    width: Int,
                    height: Int
                ) {}

                override fun onSurfaceTextureDestroyed(
                    surface: SurfaceTexture
                ): Boolean = true

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
    }

    private fun makeLabel(value: String): TextView {
        return TextView(this).apply {
            text = value
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(4, 5, 4, 5)
        }
    }

    private fun findCameras() {
        try {
            cameraIds.clear()

            for (id in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val capabilities = chars.get(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
                )

                val supportsRaw = capabilities?.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
                ) == true

                if (facing == CameraCharacteristics.LENS_FACING_BACK &&
                    supportsRaw
                ) {
                    cameraIds.add(id)
                }
            }

            if (cameraIds.isEmpty()) {
                status.text = "No rear RAW camera was detected."
                return
            }

            cameraSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                cameraIds.mapIndexed { index, id ->
                    "RAW camera ${index + 1} ($id)"
                }
            )

            cameraSpinner.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long
                    ) {
                        val newId = cameraIds[position]
                        if (selectedCameraId != newId) {
                            selectedCameraId = newId
                            closeCamera()
                            openCamera()
                        }
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }

            selectedCameraId = cameraIds[0]

            if (texture.isAvailable) openCamera()

        } catch (e: Exception) {
            status.text = "Camera detection error: ${e.message}"
        }
    }

    private fun openCamera() {
        if (!texture.isAvailable ||
            cameraDevice != null ||
            cameraOpening ||
            selectedCameraId == null
        ) return

        if (checkSelfPermission(Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) return

        try {
            val id = selectedCameraId!!
            characteristics = manager.getCameraCharacteristics(id)

            val isoRange = characteristics?.get(
                CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
            )
            if (isoRange != null) {
                isoMin = isoRange.lower
                isoMax = isoRange.upper
                selectedISO = selectedISO.coerceIn(isoMin, isoMax)
            }

            val exposureRange = characteristics?.get(
                CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
            )
            if (exposureRange != null) {
                shutterMin = exposureRange.lower
                shutterMax = exposureRange.upper
                selectedShutter =
                    selectedShutter.coerceIn(shutterMin, shutterMax)
            }

            val map = characteristics?.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )

            val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR)
            if (rawSizes.isNullOrEmpty()) {
                status.text = "RAW capture is unavailable on this camera."
                return
            }

            val rawSize = rawSizes.maxByOrNull {
                it.width.toLong() * it.height.toLong()
            }!!

            imageReader?.close()
            imageReader = ImageReader.newInstance(
                rawSize.width,
                rawSize.height,
                ImageFormat.RAW_SENSOR,
                2
            )

            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireNextImage()
                synchronized(saveLock) {
                    pendingImage?.close()
                    pendingImage = image
                    saveIfReady()
                }
            }, cameraHandler)

            cameraOpening = true
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpening = false
                    cameraDevice = camera
                    startPreview()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpening = false
                    camera.close()
                    cameraDevice = null
                    status.text = "Camera disconnected"
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpening = false
                    camera.close()
                    cameraDevice = null
                    status.text = "Camera error: $error"
                }
            }, cameraHandler)

        } catch (e: Exception) {
            cameraOpening = false
            status.text = "Could not open camera: ${e.message}"
        }
    }

    private fun startPreview() {
        val camera = cameraDevice ?: return
        val surfaceTexture = texture.surfaceTexture ?: return
        val reader = imageReader ?: return

        try {
            val map = characteristics?.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )
            val sizes = map?.getOutputSizes(SurfaceTexture::class.java)
            val previewSize = sizes?.firstOrNull() ?: return

            surfaceTexture.setDefaultBufferSize(
                previewSize.width,
                previewSize.height
            )

            val previewSurface = Surface(surfaceTexture)

            previewBuilder = camera.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            ).apply {
                addTarget(previewSurface)
                set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
            }

            camera.createCaptureSession(
                listOf(previewSurface, reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(
                        configuredSession: CameraCaptureSession
                    ) {
                        session = configuredSession
                        updatePreview()
                        status.text = "RAW camera ready"
                    }

                    override fun onConfigureFailed(
                        configuredSession: CameraCaptureSession
                    ) {
                        status.text = "Preview setup failed"
                    }
                },
                cameraHandler
            )

        } catch (e: Exception) {
            status.text = "Preview error: ${e.message}"
        }
    }

    private fun updatePreview() {
        val currentSession = session ?: return
        val builder = previewBuilder ?: return

        try {
            builder.set(CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_OFF)

            builder.set(CaptureRequest.SENSOR_SENSITIVITY, selectedISO)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, selectedShutter)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, selectedWB)

            currentSession.setRepeatingRequest(
                builder.build(),
                null,
                cameraHandler
            )
        } catch (e: Exception) {
            status.text = "Settings error: ${e.message}"
        }
    }

    private fun takePhoto() {
        val camera = cameraDevice ?: return
        val currentSession = session ?: return
        val reader = imageReader ?: return

        try {
            val captureRequest = camera.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE
            ).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_SENSITIVITY, selectedISO)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, selectedShutter)
                set(CaptureRequest.CONTROL_AWB_MODE, selectedWB)
            }

            synchronized(saveLock) {
                pendingResult = null
                pendingImage?.close()
                pendingImage = null
            }

            currentSession.capture(
                captureRequest.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        synchronized(saveLock) {
                            pendingResult = result
                            saveIfReady()
                        }
                    }
                },
                cameraHandler
            )

            status.text = "Capturing DNG RAW..."

        } catch (e: Exception) {
            status.text = "Capture error: ${e.message}"
        }
    }

    private fun saveIfReady() {
        val image = pendingImage ?: return
        val result = pendingResult ?: return
        val chars = characteristics ?: return

        pendingImage = null
        pendingResult = null

        try {
            val filename = "Xiaomi15T_${SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.US
            ).format(Date())}.dng"

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "Pictures/15T RAW"
                    )
                }
            }

            val uri: Uri? = contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            )

            if (uri == null) {
                image.close()
                status.text = "Could not create file"
                return
            }

            val output: OutputStream? = contentResolver.openOutputStream(uri)

            if (output != null) {
                output.use {
                    DngCreator(chars, result).use { creator ->
                        creator.writeImage(it, image)
                    }
                }
                status.text = "RAW saved: Pictures/15T RAW"
            } else {
                status.text = "Could not save RAW file"
            }

        } catch (e: Exception) {
            status.text = "DNG save error: ${e.message}"
        } finally {
            image.close()
        }
    }

    private fun formatShutter(nanos: Long): String {
        val seconds = nanos / 1_000_000_000.0
        return if (seconds >= 1.0) {
            String.format(Locale.US, "%.1fs", seconds)
        } else {
            "1/${(1.0 / seconds).toInt()}"
        }
    }

    private fun closeCamera() {
        session?.close()
        session = null

        cameraDevice?.close()
        cameraDevice = null

        imageReader?.close()
        imageReader = null

        cameraOpening = false
    }

    override fun onResume() {
        super.onResume()
        if (::texture.isInitialized && texture.isAvailable) {
            openCamera()
        }
    }

    override fun onPause() {
        closeCamera()
        super.onPause()
    }

    override fun onDestroy() {
        handlerThread?.quitSafely()
        handlerThread = null
        cameraHandler = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == 100 &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            findCameras()
        } else {
            status.text = "Camera permission is required."
        }
    }
}
