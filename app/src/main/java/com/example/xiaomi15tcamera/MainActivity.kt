
package com.example.xiaomi15tcamera

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.*
import android.provider.MediaStore
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.*
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln

class MainActivity : Activity() {

    private val bg = Color.rgb(12, 14, 13)
    private val panel = Color.rgb(24, 27, 25)
    private val green = Color.rgb(158, 190, 135)
    private val white = Color.rgb(239, 241, 235)
    private val muted = Color.rgb(139, 146, 136)

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
    private var previewSurface: Surface? = null

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
    private var shutterMax = 10_000_000_000L
    private var selectedIso = 100
    private var selectedShutter = 10_000_000L

    private var selectedFormat = 0
    private var selectedWb = 0
    private var selectedTimer = 0
    private var manualSensorSupported = false

    private val formats = arrayOf("JPEG", "DNG RAW", "RAW + JPEG")
    private val whiteBalances = arrayOf(
        "AUTO", "DAYLIGHT", "CLOUDY", "SHADE",
        "TUNGSTEN", "FLUORESCENT"
    )
    private val timers = arrayOf("OFF", "3 SEC", "5 SEC", "10 SEC")

    private val lock = Any()
    private var pendingRaw: Image? = null
    private var pendingJpeg: Image? = null
    private var pendingResult: TotalCaptureResult? = null
    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0

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
        thread = HandlerThread("LeicaCameraThread").also {
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

    // ---------------- INTERFACE ----------------

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        // Top title bar
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20, 13, 20, 10)
        }

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        brand.addView(TextView(this).apply {
            text = "LEICA"
            textSize = 23f
            letterSpacing = 0.22f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(white)
        })

        brand.addView(TextView(this).apply {
            text = "15T  /  PHOTOGRAPHY"
            textSize = 9f
            letterSpacing = 0.17f
            setTextColor(green)
        })

        top.addView(
            brand,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        top.addView(TextView(this).apply {
            text = "●  PHOTO"
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(green)
            typeface = Typeface.DEFAULT_BOLD
        })

        root.addView(top)

        // Live viewfinder
        val viewfinder = FrameLayout(this).apply {
            background = rounded(bg, 12)
        }

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

                override fun onSurfaceTextureDestroyed(
                    surface: SurfaceTexture
                ): Boolean = true

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }

        viewfinder.addView(
            preview,
            FrameLayout.LayoutParams(-1, -1)
        )

        val viewfinderLabel = TextView(this).apply {
            text = "15T  •  LIVE VIEW"
            textSize = 9f
            letterSpacing = 0.15f
            setTextColor(white)
            setPadding(12, 9, 12, 9)
            background = rounded(Color.argb(145, 0, 0, 0), 6)
        }

        viewfinder.addView(
            viewfinderLabel,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                setMargins(12, 12, 0, 0)
            }
        )

        root.addView(
            viewfinder,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.25f
            ).apply {
                setMargins(12, 2, 12, 8)
            }
        )

        // Camera status
        status = TextView(this).apply {
            text = "CAMERA INITIALIZING"
            textSize = 10f
            letterSpacing = 0.08f
            setTextColor(green)
            gravity = Gravity.CENTER
            setPadding(8, 5, 8, 5)
        }
        root.addView(status)

        // Control panel
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 6, 16, 12)
        }

        scroll.addView(controls)

        // Lens selector
        addSection(controls, "LENS")

        cameraSpinner = Spinner(this)
        styleSpinner(cameraSpinner)
        controls.addView(cameraSpinner)

        // Format selector
        addSection(controls, "IMAGE FORMAT")

        formatSpinner = Spinner(this)
        formatSpinner.adapter = spinnerAdapter(formats)
        formatSpinner.onItemSelectedListener = listener {
            selectedFormat = it
            updateStatus("FORMAT  /  ${formats[it]}")
        }
        styleSpinner(formatSpinner)
        controls.addView(formatSpinner)

        // White balance
        addSection(controls, "WHITE BALANCE")

        wbSpinner = Spinner(this)
        wbSpinner.adapter = spinnerAdapter(whiteBalances)
        wbSpinner.onItemSelectedListener = listener {
            selectedWb = it
            updatePreview()
        }
        styleSpinner(wbSpinner)
        controls.addView(wbSpinner)

        // ISO
        addSection(controls, "ISO")

        isoText = valueText("ISO 100")
        controls.addView(isoText)

        isoBar = SeekBar(this).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(green)
            thumbTintList = android.content.res.ColorStateList.valueOf(green)
            progressBackgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.DKGRAY)
        }

        isoBar.setOnSeekBarChangeListener(seekListener {
            selectedIso = isoMin + ((isoMax - isoMin) * it / 1000)
            isoText.text = "ISO  $selectedIso"
            updatePreview()
        })

        controls.addView(isoBar)

        // Shutter
        addSection(controls, "SHUTTER SPEED")

        shutterText = valueText("1/100 s")
        controls.addView(shutterText)

        shutterBar = SeekBar(this).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(green)
            thumbTintList = android.content.res.ColorStateList.valueOf(green)
            progressBackgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.DKGRAY)
        }

        shutterBar.setOnSeekBarChangeListener(seekListener {
            val fraction = it / 1000.0
            selectedShutter = (
                shutterMin * exp(
                    ln(shutterMax.toDouble() / shutterMin) * fraction
                )
            ).toLong()

            shutterText.text = formatShutter(selectedShutter)
            updatePreview()
        })

        controls.addView(shutterBar)

        // Timer
        addSection(controls, "SELF TIMER")

        timerSpinner = Spinner(this)
        timerSpinner.adapter = spinnerAdapter(timers)
        timerSpinner.onItemSelectedListener = listener {
            selectedTimer = it
        }
        styleSpinner(timerSpinner)
        controls.addView(timerSpinner)

        // Capture button
        val capture = TextView(this).apply {
            text = "●     CAPTURE"
            textSize = 15f
            letterSpacing = 0.15f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(13, 18, 14))
            setPadding(12, 17, 12, 17)
            background = rounded(green, 30)
            setOnClickListener { takePhoto() }
        }

        controls.addView(
            capture,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = 18
            }
        )

        val footer = TextView(this).apply {
            text = "15T CAMERA  •  STILL PHOTOGRAPHY"
            textSize = 9f
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setPadding(5, 14, 5, 8)
        }
        controls.addView(footer)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        setContentView(root)
        findCameras()
    }

    private fun addSection(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 10f
            letterSpacing = 0.16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(green)
            setPadding(2, 15, 2, 5)
        })
    }

    private fun valueText(value: String) = TextView(this).apply {
        text = value
        textSize = 16f
        letterSpacing = 0.04f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(white)
        setPadding(2, 3, 2, 3)
    }

    private fun rounded(color: Int, radius: Int) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }

    private fun spinnerAdapter(items: Array<String>) =
        ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            items
        ).also {
            it.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item
            )
        }

    private fun styleSpinner(spinner: Spinner) {
        spinner.background = rounded(panel, 8)
        spinner.setPadding(8, 3, 8, 3)
    }

    private fun listener(action: (Int) -> Unit) =
        object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}

            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                action(position)
            }
        }

    private fun seekListener(action: (Int) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: SeekBar?,
                progress: Int,
                fromUser: Boolean
            ) {
                if (fromUser) action(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

    // ---------------- CAMERA DISCOVERY ----------------

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

                    val raw = c.get(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
                    )?.contains(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
                    ) ?: false

                    cameraNames.add(
                        "REAR CAMERA $id" + if (raw) "  /  RAW" else ""
                    )
                }
            }
        } catch (e: Exception) {
            updateStatus("CAMERA LIST ERROR")
        }

        cameraSpinner.adapter = spinnerAdapter(
            cameraNames.toTypedArray()
        )

        cameraSpinner.onItemSelectedListener = listener { position ->
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
            updateStatus("NO REAR CAMERA FOUND")
        }
    }

    // ---------------- OPEN CAMERA ----------------

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

            manualSensorSupported = caps.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
            )

            characteristics!!.get(
                CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
            )?.let {
                isoMin = it.lower
                isoMax = it.upper
            }

            characteristics!!.get(
                CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
            )?.let {
                shutterMin = it.lower
                shutterMax = it.upper
            }

            selectedIso = selectedIso.coerceIn(isoMin, isoMax)
            selectedShutter = selectedShutter.coerceIn(
                shutterMin, shutterMax
            )

            isoText.text = "ISO  $selectedIso"
            shutterText.text = formatShutter(selectedShutter)

            isoBar.progress =
                ((selectedIso - isoMin).toFloat() /
                    (isoMax - isoMin).coerceAtLeast(1) * 1000).toInt()

            val shutterFraction =
                ln(selectedShutter.toDouble() / shutterMin) /
                    ln(shutterMax.toDouble() / shutterMin)

            shutterBar.progress =
                (shutterFraction * 1000).toInt().coerceIn(0, 1000)

            if (!manualSensorSupported) {
                updateStatus("AUTO EXPOSURE  /  MANUAL SENSOR UNAVAILABLE")
            }

            val map = characteristics!!.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )

            val rawSize = if (hasRaw) {
                map?.getOutputSizes(
                    android.graphics.ImageFormat.RAW_SENSOR
                )?.maxByOrNull {
                    it.width.toLong() * it.height
                }
            } else null

            val jpegSize = map?.getOutputSizes(
                android.graphics.ImageFormat.JPEG
            )?.maxByOrNull {
                it.width.toLong() * it.height
            }

            rawReader?.close()
            jpegReader?.close()
            rawReader = null
            jpegReader = null

            if (rawSize != null) {
                rawReader = ImageReader.newInstance(
                    rawSize.width,
                    rawSize.height,
                    android.graphics.ImageFormat.RAW_SENSOR,
                    2
                )

                rawReader!!.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireNextImage()
                        ?: return@setOnImageAvailableListener

                    synchronized(lock) {
                        pendingRaw?.close()
                        pendingRaw = image
                    }
                    processPending()
                }, cameraHandler)
            }

            if (jpegSize != null) {
                jpegReader = ImageReader.newInstance(
                    jpegSize.width,
                    jpegSize.height,
                    android.graphics.ImageFormat.JPEG,
                    2
                )

                jpegReader!!.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireNextImage()
                        ?: return@setOnImageAvailableListener

                    synchronized(lock) {
                        pendingJpeg?.close()
                        pendingJpeg = image
                    }
                    processPending()
                }, cameraHandler)
            }

            manager.openCamera(
                id,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        startPreview()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        cameraDevice = null
                        updateStatus("CAMERA DISCONNECTED")
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        cameraDevice = null
                        updateStatus("CAMERA ERROR  /  $error")
                    }
                },
                cameraHandler
            )

        } catch (e: Exception) {
            updateStatus("OPEN CAMERA FAILED: ${e.message}")
        }
    }

    // ---------------- PREVIEW ----------------

    private fun startPreview() {
        val camera = cameraDevice ?: return
        if (!preview.isAvailable) return

        try {
            session?.close()
            session = null

            previewSurface?.release()
            previewSurface = null

            val texture = preview.surfaceTexture ?: return

            val size = characteristics?.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )?.getOutputSizes(SurfaceTexture::class.java)?.firstOrNull()

            if (size != null) {
                texture.setDefaultBufferSize(size.width, size.height)
            }

            val surface = Surface(texture)
            previewSurface = surface

            val outputs = mutableListOf(surface)
            rawReader?.surface?.let { outputs.add(it) }
            jpegReader?.surface?.let { outputs.add(it) }

            val builder = camera.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            )

            builder.addTarget(surface)

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
                        updateStatus("PREVIEW SETUP FAILED")
                    }
                },
                cameraHandler
            )

        } catch (e: Exception) {
            updateStatus("PREVIEW ERROR: ${e.message}")
        }
    }

    private fun updatePreview() {
        val s = session ?: return
        val builder = previewRequest ?: return

        try {
            applyCameraSettings(builder)
            s.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (e: Exception) {
            updateStatus("CAMERA SETTINGS ERROR")
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
            val available = characteristics?.get(
                CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES
            ) ?: intArrayOf(CaptureRequest.CONTROL_AWB_MODE_AUTO)

            val wanted = wbModes[selectedWb]
            val mode = if (available.contains(wanted)) {
                wanted
            } else {
                CaptureRequest.CONTROL_AWB_MODE_AUTO
            }

            builder.set(CaptureRequest.CONTROL_AWB_MODE, mode)
        } catch (_: Exception) {
        }

        try {
            if (manualSensorSupported) {
                builder.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_OFF
                )
                builder.set(
                    CaptureRequest.SENSOR_SENSITIVITY,
                    selectedIso
                )
                builder.set(
                    CaptureRequest.SENSOR_EXPOSURE_TIME,
                    selectedShutter
                )
            } else {
                builder.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON
                )
            }
        } catch (_: Exception) {
        }
    }

    // ---------------- PHOTO CAPTURE ----------------

    private fun takePhoto() {
        if (session == null || cameraDevice == null) {
            updateStatus("CAMERA NOT READY")
            return
        }

        val delay = when (selectedTimer) {
            1 -> 3000L
            2 -> 5000L
            3 -> 10000L
            else -> 0L
        }

        if (delay > 0) {
            updateStatus("SELF TIMER  /  ${delay / 1000} SECONDS")
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
            updateStatus("RAW NOT AVAILABLE ON THIS CAMERA")
            return
        }

        if (wantsJpeg && jpegReader == null) {
            updateStatus("JPEG NOT AVAILABLE")
            return
        }

        synchronized(lock) {
            if (saving) {
                updateStatus("SAVING PREVIOUS PHOTO")
                return
            }

            pendingRaw?.close()
            pendingJpeg?.close()
            pendingRaw = null
            pendingJpeg = null
            pendingResult = null
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

            builder.set(
                CaptureRequest.JPEG_ORIENTATION,
                orientation
            )

            updateStatus("CAPTURING  /  ${formats[selectedFormat]}")

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
                        updateStatus("CAPTURE FAILED")
                        updatePreview()
                    }
                },
                cameraHandler
            )

        } catch (e: Exception) {
            updateStatus("CAPTURE ERROR: ${e.message}")
        }
    }

    // ---------------- SAVE PHOTOS ----------------

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
                if (raw != null && result != null) {
                    saveDng(raw!!, result!!)
                }

                if (jpeg != null) {
                    saveJpeg(jpeg!!)
                }

                updateStatus("PHOTO SAVED  /  GALLERY")
            } catch (e: Exception) {
                updateStatus("SAVE FAILED: ${e.message}")
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
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: throw Exception("Could not create JPEG")

        contentResolver.openOutputStream(uri)?.use { output ->
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            output.write(bytes)
        } ?: throw Exception("Could not write JPEG")
    }

    private fun saveDng(image: Image, result: TotalCaptureResult) {
        val chars = characteristics
            ?: throw Exception("Camera information missing")

        val values = ContentValues().apply {
            put(
                MediaStore.Images.Media.DISPLAY_NAME,
                "15T_${timestamp()}.dng"
            )
            put(
                MediaStore.Images.Media.MIME_TYPE,
                "image/x-adobe-dng"
            )
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "Pictures/15T RAW"
            )
        }

        val uri = contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: throw Exception("Could not create DNG")

        contentResolver.openOutputStream(uri)?.use { output ->
            DngCreator(chars, result).use { creator ->
                creator.writeImage(output, image)
            }
        } ?: throw Exception("Could not write DNG")
    }

    private fun timestamp(): String =
        SimpleDateFormat(
            "yyyyMMdd_HHmmss_SSS",
            Locale.US
        ).format(Date())

    private fun formatShutter(nanos: Long): String {
        val seconds = nanos / 1_000_000_000.0

        return if (seconds >= 1.0) {
            String.format(Locale.US, "%.1f s", seconds)
        } else {
            "1/${(1.0 / seconds).toInt().coerceAtLeast(1)} s"
        }
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            if (::status.isInitialized) status.text = message
        }
    }

    // ---------------- CLOSE CAMERA ----------------

    private fun closeCamera() {
        try {
            session?.close()
            session = null

            cameraDevice?.close()
            cameraDevice = null

            previewSurface?.release()
            previewSurface = null

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
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == 100 &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            if (preview.isAvailable) openCamera()
        } else {
            updateStatus("CAMERA PERMISSION REQUIRED")
        }
    }
}
