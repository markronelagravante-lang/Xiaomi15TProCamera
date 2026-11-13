
package com.example.xiaomi15tcamera

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln

class MainActivity : Activity() {

    private val bg = Color.rgb(12, 14, 13)
    private val panel = Color.rgb(27, 31, 28)
    private val green = Color.rgb(158, 190, 135)
    private val white = Color.rgb(239, 241, 235)
    private val muted = Color.rgb(139, 146, 136)

    private lateinit var manager: CameraManager
    private lateinit var preview: TextureView
    private lateinit var status: TextView

    private lateinit var isoButton: TextView
    private lateinit var shutterButton: TextView
    private lateinit var wbButton: TextView
    private lateinit var lensButton: TextView
    private lateinit var formatButton: TextView
    private lateinit var settingsButton: TextView

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
    private var activeCaptureFormat = 0

    private var manualSensorSupported = false

    private val formats = arrayOf("JPEG", "DNG RAW", "RAW + JPEG")

    private val whiteBalances = arrayOf(
        "AUTO",
        "DAYLIGHT",
        "CLOUDY",
        "SHADE",
        "TUNGSTEN",
        "FLUORESCENT"
    )

    private val timers = arrayOf(
        "OFF",
        "3 SECONDS",
        "5 SECONDS",
        "10 SECONDS"
    )

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
            requestPermissions(
                arrayOf(Manifest.permission.CAMERA),
                100
            )
        }
    }

    override fun onPause() {
        closeCamera()
        stopCameraThread()
        super.onPause()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

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

    // =====================================================
    // INTERFACE
    // =====================================================

    private fun buildInterface() {

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(10), dp(3), dp(10), dp(4))
        }

        // TOP BAR

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(8), dp(7))
        }

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        brand.addView(TextView(this).apply {
            text = "LEICA"
            textSize = 22f
            letterSpacing = 0.22f
            typeface = Typeface.create(
                "sans-serif-light",
                Typeface.NORMAL
            )
            setTextColor(white)
        })

        brand.addView(TextView(this).apply {
            text = "15T  /  PHOTOGRAPHY"
            textSize = 9f
            letterSpacing = 0.16f
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
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(green)
        })

        root.addView(top)

        // LARGE LIVE PREVIEW

        val viewfinder = FrameLayout(this).apply {
            background = rounded(Color.BLACK, 12)
            clipToOutline = true
        }

        preview = TextureView(this).apply {
            surfaceTextureListener =
                object : TextureView.SurfaceTextureListener {

                    override fun onSurfaceTextureAvailable(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        if (checkSelfPermission(
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            openCamera()
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        startPreview()
                    }

                    override fun onSurfaceTextureDestroyed(
                        surface: SurfaceTexture
                    ): Boolean = true

                    override fun onSurfaceTextureUpdated(
                        surface: SurfaceTexture
                    ) {
                    }
                }
        }

        viewfinder.addView(
            preview,
            FrameLayout.LayoutParams(-1, -1)
        )

        // Preview label

        val liveLabel = TextView(this).apply {
            text = "15T  •  LIVE VIEW"
            textSize = 9f
            letterSpacing = 0.15f
            setTextColor(white)
            setPadding(dp(11), dp(8), dp(11), dp(8))
            background = rounded(
                Color.argb(170, 0, 0, 0),
                dp(6)
            )
        }

        viewfinder.addView(
            liveLabel,
            FrameLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.TOP or Gravity.START
                setMargins(dp(11), dp(11), 0, 0)
            }
        )

        // Status overlay at bottom of preview

        status = TextView(this).apply {
            text = "CAMERA INITIALIZING"
            textSize = 9f
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            setTextColor(green)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rounded(
                Color.argb(165, 0, 0, 0),
                dp(5)
            )
        }

        viewfinder.addView(
            status,
            FrameLayout.LayoutParams(-1, -2).apply {
                gravity = Gravity.BOTTOM
                setMargins(dp(10), 0, dp(10), dp(10))
            }
        )

        root.addView(
            viewfinder,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                setMargins(0, dp(2), 0, dp(7))
            }
        )

        // CONTROL ROW ONE

        val rowOne = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        isoButton = makeControlButton("ISO", "AUTO") {
            showIsoDialog()
        }

        shutterButton = makeControlButton("SHUTTER", "AUTO") {
            showShutterDialog()
        }

        wbButton = makeControlButton("WB", "AUTO") {
            showWhiteBalanceDialog()
        }

        rowOne.addView(isoButton, controlLayout())
        rowOne.addView(shutterButton, controlLayout())
        rowOne.addView(wbButton, controlLayout())

        root.addView(
            rowOne,
            LinearLayout.LayoutParams(-1, dp(53)).apply {
                bottomMargin = dp(5)
            }
        )

        // CONTROL ROW TWO

        val rowTwo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        lensButton = makeControlButton("LENS", "1×") {
            showLensDialog()
        }

        formatButton = makeControlButton("FORMAT", "JPEG") {
            showFormatDialog()
        }

        settingsButton = makeControlButton("SETTINGS", "⚙") {
            showSettingsDialog()
        }

        rowTwo.addView(lensButton, controlLayout())
        rowTwo.addView(formatButton, controlLayout())
        rowTwo.addView(settingsButton, controlLayout())

        root.addView(
            rowTwo,
            LinearLayout.LayoutParams(-1, dp(53)).apply {
                bottomMargin = dp(5)
            }
        )

        // SHUTTER AREA

        val shutterArea = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                -1,
                dp(75)
            )
        }

        val shutter = TextView(this).apply {
            text = ""
            gravity = Gravity.CENTER
            background = rounded(green, 100)
            setOnClickListener {
                takePhoto()
            }
        }

        val shutterRing = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dp(3), green)
        }

        shutter.background = shutterRing

        val shutterInner = TextView(this).apply {
            background = rounded(green, 100)
            isClickable = false
        }

        val shutterHolder = FrameLayout(this).apply {
            background = rounded(Color.TRANSPARENT, 100)
            addView(
                shutterInner,
                FrameLayout.LayoutParams(
                    dp(51),
                    dp(51),
                    Gravity.CENTER
                )
            )

            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(3), green)
            }

            setOnClickListener {
                takePhoto()
            }
        }

        shutterArea.addView(
            shutterHolder,
            FrameLayout.LayoutParams(
                dp(65),
                dp(65),
                Gravity.CENTER
            )
        )

        root.addView(shutterArea)

        root.addView(TextView(this).apply {
            text = "15T CAMERA  •  STILL PHOTOGRAPHY"
            textSize = 8f
            letterSpacing = 0.13f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setPadding(0, dp(1), 0, dp(3))
        })

        setContentView(root)

        findCameras()
        updateControlLabels()
    }

    private fun makeControlButton(
        title: String,
        value: String,
        action: () -> Unit
    ): TextView {

        return TextView(this).apply {
            text = "$title\n$value"
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 2
            letterSpacing = 0.04f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(white)
            background = rounded(panel, dp(8))
            setPadding(dp(2), dp(4), dp(2), dp(4))
            setOnClickListener {
                action()
            }
        }
    }

    private fun controlLayout(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, -1, 1f).apply {
            setMargins(dp(3), 0, dp(3), 0)
        }

    private fun rounded(color: Int, radius: Int) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }

    private fun updateControlLabels() {
        if (!::isoButton.isInitialized) return

        isoButton.text = "ISO\n${if (manualSensorSupported) selectedIso else "AUTO"}"
        shutterButton.text =
            "SHUTTER\n${if (manualSensorSupported) formatShutter(selectedShutter) else "AUTO"}"

        wbButton.text = "WB\n${whiteBalances[selectedWb]}"
        formatButton.text = "FORMAT\n${formats[selectedFormat]}"

        val lensIndex = cameraIds.indexOf(selectedCameraId)
        lensButton.text = "LENS\n${if (lensIndex >= 0) "CAM ${lensIndex + 1}" else "AUTO"}"

        settingsButton.text = "SETTINGS\n${timers[selectedTimer]}"
    }

    // =====================================================
    // POPUP CONTROLS
    // =====================================================

    private fun showIsoDialog() {

        if (!manualSensorSupported) {
            AlertDialog.Builder(this)
                .setTitle("ISO")
                .setMessage(
                    "Manual ISO is not supported by this camera. " +
                    "The camera will use automatic exposure."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(10))
        }

        val value = TextView(this).apply {
            text = "ISO  $selectedIso"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(white)
        }

        layout.addView(value)

        val bar = SeekBar(this).apply {
            max = 1000
            progress = (
                (selectedIso - isoMin).toFloat() /
                (isoMax - isoMin).coerceAtLeast(1) * 1000
            ).toInt().coerceIn(0, 1000)

            progressTintList = ColorStateList.valueOf(green)
            thumbTintList = ColorStateList.valueOf(green)
        }

        layout.addView(bar)

        bar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    selectedIso = isoMin +
                        ((isoMax - isoMin) * progress / 1000)

                    value.text = "ISO  $selectedIso"
                    updatePreview()
                    updateControlLabels()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            }
        )

        AlertDialog.Builder(this)
            .setTitle("ISO SENSITIVITY")
            .setView(layout)
            .setPositiveButton("DONE", null)
            .show()
    }

    private fun showShutterDialog() {

        if (!manualSensorSupported) {
            AlertDialog.Builder(this)
                .setTitle("SHUTTER SPEED")
                .setMessage(
                    "Manual shutter speed is not supported by this camera. " +
                    "The camera will use automatic exposure."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(10))
        }

        val value = TextView(this).apply {
            text = formatShutter(selectedShutter)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(white)
        }

        layout.addView(value)

        val bar = SeekBar(this).apply {
            max = 1000

            val range = ln(
                shutterMax.toDouble() /
                    shutterMin.coerceAtLeast(1)
            )

            progress = (
                ln(
                    selectedShutter.toDouble() /
                        shutterMin.coerceAtLeast(1)
                ) / range * 1000
            ).toInt().coerceIn(0, 1000)

            progressTintList = ColorStateList.valueOf(green)
            thumbTintList = ColorStateList.valueOf(green)
        }

        layout.addView(bar)

        bar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    val fraction = progress / 1000.0

                    selectedShutter = (
                        shutterMin * exp(
                            ln(
                                shutterMax.toDouble() /
                                    shutterMin
                            ) * fraction
                        )
                    ).toLong()

                    value.text = formatShutter(selectedShutter)
                    updatePreview()
                    updateControlLabels()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            }
        )

        AlertDialog.Builder(this)
            .setTitle("SHUTTER SPEED")
            .setView(layout)
            .setPositiveButton("DONE", null)
            .show()
    }

    private fun showWhiteBalanceDialog() {
        AlertDialog.Builder(this)
            .setTitle("WHITE BALANCE")
            .setSingleChoiceItems(
                whiteBalances,
                selectedWb
            ) { dialog, which ->
                selectedWb = which
                updatePreview()
                updateControlLabels()
                dialog.dismiss()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showFormatDialog() {
        AlertDialog.Builder(this)
            .setTitle("IMAGE FORMAT")
            .setSingleChoiceItems(
                formats,
                selectedFormat
            ) { dialog, which ->
                selectedFormat = which
                updateControlLabels()
                updateStatus("FORMAT  /  ${formats[which]}")
                dialog.dismiss()
            }
            .setNegativeButton("CANCEL", null)
            .sh
