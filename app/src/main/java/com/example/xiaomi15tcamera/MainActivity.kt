
package com.example.xiaomi15tcamera

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.media.Image
import android.media.ImageReader
import android.os.*
import android.provider.MediaStore
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.*
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln

class MainActivity : Activity() {

    private lateinit var manager: CameraManager
    private lateinit var preview: TextureView
    private lateinit var status: TextView
    private lateinit var isoText: TextView
    private lateinit var shutterText: TextView
    private lateinit var cameraSpinner: Spinner
    private lateinit var formatSpinner: Spinner
    private lateinit var wbSpinner: Spinner
    private lateinit var isoBar: SeekBar
    private lateinit var shutterBar: SeekBar
    private lateinit var timerSpinner: Spinner

    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewRequest: CaptureRequest.Builder? = null
    private var rawReader: ImageReader? = null
    private var jpegReader: ImageReader? = null
    private var characteristics: CameraCharacteristics? = null
    private var selectedCameraId: String? = null

    private var thread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private val cameraIds = mutableListOf<String>()
    private val cameraNames = mutableListOf<String>()

    private var isoMin = 100
    private var isoMax = 3200
    private var shutterMin = 100_000L
    private var shutterMax = 1_000_000_000L
    private var selectedIso = 100
    private var selectedShutter = 10_000_000L

    private var selectedFormat = 0
    private var selectedWb = 0
    private var selectedTimer = 0

    private val lock = Any()
    private var pendingRaw: Image? = null
    private var pendingJpeg: Image? = null
    private var pendingResult: TotalCaptureResult? = null
    private var saving = false

    private val formats = arrayOf("JPEG", "DNG RAW", "RAW + JPEG")
    private val whiteBalances = arrayOf(
        "Auto", "Daylight", "Cloudy", "Shade",
        "Tungsten", "Fluorescent"
    )
    private val timers = arrayOf("Off", "3 seconds", "5 seconds", "10 seconds")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = getSystemService(CAMERA_SERVICE) as CameraManager
        buildInterface()
    }

    override fun onResume() {
        super.onResume()
        startCameraThread()
        if (checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            if (preview.isAvailable) openCamera()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 100)
        }
    }

    override fun onPause() {
        closeCamera()
        stopCameraThread()
        super.onPause()
    }

    private fun startCameraThread() {
        if (thread != null) return
        thread = HandlerThread("CameraThread").also {
            it.start()
            cameraHandler = Handler(it.looper)
        }
    }

    private fun stopCameraThread() {
        thread?.quitSafely()
        try {
            thread?.join()
        } catch (_: InterruptedException) {
        }
        thread = null
        cameraHandler = null
    }

    private fun buildInterface() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.decorView.systemUiVisibility = 0

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 14, 17))
        }

        val header = TextView(this).apply {
            text = "15T  /  PRO CAMERA"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(18, 16, 18, 12)
        }
        root.addView(header)

        preview = TextureView(this).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surface: SurfaceTexture, width: Int, height: Int
                ) {
                    if (checkSelfPermission(Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                    ) openCamera()
                }

                override fun onSurfaceTextureSizeChanged(
                    surface: SurfaceTexture, width: Int, height: Int
                ) {
                    startPreview()
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }
        root.addView(
            preview,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        status = makeText("CAMERA READY")
        root.addView(status)

        val scroll = ScrollView(this)
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 8, 14, 14)
        }
        scroll.addView(controls)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        controls.addView(makeLabel("CAMERA / LENS"))
        cameraSpinner = Spinner(this)
        controls.addView(cameraSpinner)

        controls.addView(makeLabel("PHOTO FORMAT"))
        formatSpinner = Spinner(this)
        formatSpinner.adapter = adapter(formats)
        formatSpinner.onItemSelectedListener = simpleListener {
            selectedFormat = it
            updateStatus("Format: ${formats[it]}")
        }
        controls.addView(formatSpinner)

        controls.addView(makeLabel("WHITE BALANCE"))
        wbSpinner = Spinner(this)
        wbSpinner.adapter = adapter(whiteBalances)
        wbSpinner.onItemSelectedListener = simpleListener {
            selectedWb = it
            updatePreview()
        }
        controls.addView(wbSpinner)

        controls.addView(makeLabel("ISO"))
        isoText = makeText("ISO 100")
        controls.addView(isoText)
        isoBar = SeekBar(this)
        isoBar.max = 1000
        isoBar.setOnSeekBarChangeListener(seekListener {
            val range = isoMax - isoMin
            selectedIso = isoMin + (range * it / 1000)
            isoText.text = "ISO $selectedIso"
            updatePreview()
        })
        controls.addView(isoBar)

        controls.addView(makeLabel("SHUTTER SPEED"))
        shutterText = makeText("1/100 s")
        controls.addView(shutterText)
        shutterBar = SeekBar(this)
        shutterBar.max = 1000
        shutterBar.setOnSeekBarChangeListener(seekListener {
            val fraction = it / 1000.0
            selectedShutter = (
                shutterMin * exp(ln(shutterMax.toDouble() / shutterMin) * fraction)
            ).toLong()
            shutterText.text = formatShutter(selectedShutter)
            updatePreview()
        })
        controls.addView(shutterBar)

        controls.addView(makeLabel("SELF TIMER"))
        timerSpinner = Spinner(this)
        timerSpinner.adapter = adapter(timers)
        timerSpinner.onItemSelectedListener = simpleListener {
            selectedTimer = it
        }
        controls.addView(timerSpinner)

        val capture = Button(this).apply {
            text = "TAKE PHOTO"
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(190, 145, 55))
            setPadding(12, 16, 12, 16)
            setOnClickListener { takePhoto() }
        }
        controls.addView(
            capture,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 18 }
        )

        setContentView(root)
        findCameras()
    }

    private fun makeLabel(value: String) = TextView(this).apply {
        text = value
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(190, 170, 125))
        setPadding(2, 14, 2, 4)
    }

    private fun makeText(value: String) = TextView(this).apply {
        text = value
        textSize = 14f
        setTextColor(Color.WHITE)
        setPadding(2, 5, 2, 5)
    }

    private fun adapter(items: Array<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)

    private fun simpleListener(action: (Int) -> Unit) =
        object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                action(position)
            }
        }

    private fun seekListener(action: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar?, progress: Int, fromUser: Boolean
            ) {
                if (fromUser) action(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

    private fun findCameras() {
        cameraIds.clear()
        cameraNames.clear()

        try {
            for (id in manager.cameraIdList) {
                val c = manager.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
                ) {
                    cameraIds.add(id)
                    val raw = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                        ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)
                        ?: false
                    cameraNames.add(
                        "Rear camera $id" + if (raw) "  •  RAW" else ""
                    )
                }
            }
        } catch (e: Exception) {
            updateStatus("Camera list error: ${e.message}")
        }

        cameraSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, cameraNames
        )
        cameraSpinner.onItemSelectedListener = simpleListener { position ->
            if (position in cameraIds.indices) {
                val newId = cameraIds[position]
                if (newId != selectedCameraId) {
                    selectedCameraId = newId
                    closeCamera()
                    if (preview.isAvailable) openCamera()
                }
            }
        }

        if (cameraIds.isNotEmpty()) {
            selectedCameraId = cameraIds[0]
            cameraSpinner.setSelection(0)
        } else {
            updateStatus("No rear camera found")
        }
    }

    private fun openCamera() {
        if (cameraDevice != null || selectedCameraId == null) return
        if (checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        try {
            val id = selectedCameraId!!
            characteristics = manager.getCameraCharacteristics(id)

            val caps = characteristics!!.get(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
            ) ?: intArrayOf()

            val hasRaw = caps.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
            )

            val map = characteristics!!.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )

            val rawSize = if (hasRaw) {
                map?.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR)
                    ?.maxByOrNull { it.width.toLong() * it.height }
            } else null

            val jpegSize = map?.getOutputSizes(
                android.graphics.ImageFormat.JPEG
            )?.maxByOrNull { it.width.toLong() * it.height }

            rawReader?.close()
            jpegReader?.close()
            rawReader = null
            jpegReader = null

            if (rawSize != null) {
                rawReader = ImageReader.newInstance(
                    rawSize.width, rawSize.height,
                    android.graphics.ImageFormat.RAW_SENSOR, 2
                )
                rawReader!!.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                    synchronized(lock) {
                        pendingRaw?.close()
                        pendingRaw = image
                    }
                    processPending()
                }, cameraHandler)
            }

            if (jpegSize != null) {
                jpegReader = ImageReader.newInstance(
                    jpegSize.width, jpegSize.height,
                    android.graphics.ImageFormat.JPEG, 2
                )
                jpegReader!!.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                    synchronized(lock) {
                        pendingJpeg?.close()
                        pendingJpeg = image
                    }
                    processPending()
                }, cameraHandler)
            }

            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startPreview()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    updateStatus("Camera disconnected")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    updateStatus("Camera error: $error")
                }
            }, cameraHandler)

        } catch (e: Exception) {
            updateStatus("Open camera failed: ${e.message}")
        }
    }

    private fun startPreview() {
        val camera = cameraDevice ?: return
        if (!preview.isAvailable) return

        try {
            val texture = preview.surfaceTexture ?: return
            val size = characteristics?.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )?.getOutputSizes(SurfaceTexture::class.java)
                ?.firstOrNull()

            if (size != null) texture.setDefaultBufferSize(size.width, size.height)

            val previewSurface = Surface(texture)
            val outputs = mutableListOf(previewSurface)
            rawReader?.surface?.let { outputs.add(it) }
            jpegReader?.surface?.let { outputs.add(it) }

            val builder = camera.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            )
            builder.addTarget(previewSurface)
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            previewRequest = builder

            camera.createCaptureSession(
                outputs,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        updatePreview()
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        updateStatus("Preview setup failed on this camera")
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            updateStatus("Preview error: ${e.message}")
        }
    }

    private fun updatePreview() {
        val s = session ?: return
        val builder = previewRequest ?: return

        try {
            applyCameraSettings(builder)
            s.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (e: Exception) {
            updateStatus("Camera settings unavailable: ${e.message}")
        }
    }

    private fun applyCameraSettings(builder: CaptureRequest.Builder) {
        val wbModes = intArrayOf(
            CaptureRequest.CONTROL_AWB_MODE_AUTO,
            CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
            CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
            CaptureRequest.CONTROL_AWB_MODE_SHADE,
            CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
            CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
        )

        try {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, wbModes[selectedWb])
        } catch (_: Exception) {
        }

        try {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, selectedIso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, selectedShutter)
        } catch (_: Exception) {
            try {
                builder.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun takePhoto() {
        if (session == null || cameraDevice == null) {
            updateStatus("Camera is not ready")
            return
        }

        val delay = when (selectedTimer) {
            1 -> 3000L
            2 -> 5000L
            3 -> 10000L
            else -> 0L
        }

        if (delay > 0) {
            updateStatus("Taking photo in ${delay / 1000} seconds...")
            cameraHandler?.postDelayed({ captureNow() }, delay)
        } else {
            captureNow()
        }
    }

    private fun captureNow() {
        val camera = cameraDevice ?: return
        val s = session ?: return

        val wantsRaw = selectedFormat == 1 || selectedFormat == 2
        val wantsJpeg = selectedFormat == 0 || selectedFormat == 2

        if (wantsRaw && rawReader == null) {
            updateStatus("This camera does not provide DNG RAW")
            return
        }
        if (wantsJpeg && jpegReader == null) {
            updateStatus("JPEG capture is unavailable")
            return
        }

        synchronized(lock) {
            pendingRaw?.close()
            pendingJpeg?.close()
            pendingRaw = null
            pendingJpeg = null
            pendingResult = null
            saving = false
        }

        try {
            val builder = camera.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE
            )

            if (wantsRaw) builder.addTarget(rawReader!!.surface)
            if (wantsJpeg) builder.addTarget(jpegReader!!.surface)

            applyCameraSettings(builder)

            val rotation = windowManager.defaultDisplay.rotation
            val orientation = when (rotation) {
                Surface.ROTATION_90 -> 0
                Surface.ROTATION_180 -> 270
                Surface.ROTATION_270 -> 180
                else -> 90
            }
            builder.set(CaptureRequest.JPEG_ORIENTATION, orientation)

            updateStatus("Capturing ${formats[selectedFormat]}...")

            s.capture(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        synchronized(lock) {
                            pendingResult = result
                        }
                        processPending()
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        updateStatus("Capture failed")
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            updateStatus("Capture error: ${e.message}")
        }
    }

    private fun processPending() {
        var raw: Image? = null
        var jpeg: Image? = null
        var result: TotalCaptureResult? = null

        synchronized(lock) {
            if (saving) return

            val needsRaw = selectedFormat == 1 || selectedFormat == 2
            val needsJpeg = selectedFormat == 0 || selectedFormat == 2

            if (needsRaw && (pendingRaw == null || pendingResult == null)) return
            if (needsJpeg && pendingJpeg == null) return

            saving = true
            raw = pendingRaw
            jpeg = pendingJpeg
            result = pendingResult
            pendingRaw = null
            pendingJpeg = null
            pendingResult = null
        }

        cameraHandler?.post {
            try {
                if (raw != null && result != null) saveDng(raw!!, result!!)
                if (jpeg != null) saveJpeg(jpeg!!)
                updateStatus("Photo saved")
            } catch (e: Exception) {
                updateStatus("Save failed: ${e.message}")
            } finally {
                raw?.close()
                jpeg?.close()
                synchronized(lock) {
                    saving = false
                }
                updatePreview()
            }
        }
    }

    private fun saveJpeg(image: Image) {
        val values = ContentValues().apply {
            put(
                MediaStore.Images.Media.DISPLAY_NAME,
                "15T_${timestamp()}.jpg"
            )
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "Pictures/15T Camera"
            )
        }

        val uri = contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ) ?: throw Exception("Could not create JPEG file")

        contentResolver.openOutputStream(uri)?.use { output ->
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            output.write(bytes)
        } ?: throw Exception("Could not write JPEG")
    }

    private fun saveDng(image: Image, result: TotalCaptureResult) {
        val chars = characteristics ?: throw Exception("Camera information missing")
        val values = ContentValues().apply {
            put(
                MediaStore.Images.Media.DISPLAY_NAME,
                "15T_${timestamp()}.dng"
            )
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "Pictures/15T RAW"
            )
        }

        val uri = contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ) ?: throw Exception("Could not create DNG file")

        contentResolver.openOutputStream(uri)?.use { output ->
            DngCreator(chars, result).use { creator ->
                creator.writeImage(output, image)
            }
        } ?: throw Exception("Could not write DNG")
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    private fun formatShutter(nanos: Long): String {
        val seconds = nanos / 1_000_000_000.0
        return if (seconds >= 1.0) {
            String.format(Locale.US, "%.1f s", seconds)
        } else {
            "1/${(1.0 / seconds).toInt()} s"
        }
    }

    private fun updateStatus(message: String) {
        runOnUiThread { status.text = message }
    }

    private fun closeCamera() {
        try {
            session?.close()
            session = null
            cameraDevice?.close()
            cameraDevice = null
            rawReader?.close()
            rawReader = null
            jpegReader?.close()
            jpegReader = null
            synchronized(lock) {
                pendingRaw?.close()
                pendingJpeg?.close()
                pendingRaw = null
                pendingJpeg = null
                pendingResult = null
                saving = false
            }
        } catch (_: Exception) {
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            if (preview.isAvailable) openCamera()
        } else {
            updateStatus("Camera permission is required")
        }
    }
}
