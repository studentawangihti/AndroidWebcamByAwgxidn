package com.example.androidwebcam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CaptureRequest
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class MainActivity : AppCompatActivity() {

    private lateinit var server: MjpegServer

    // UI Elements
    private lateinit var tvIpInfo: TextView
    private lateinit var tvStatusIndicator: TextView
    private lateinit var badgeMute: TextView
    private lateinit var badgePause: TextView
    private lateinit var controlLayout: LinearLayout
    private lateinit var rootLayout: ConstraintLayout
    private lateinit var indicatorLayout: LinearLayout

    // Layouts Menu
    private lateinit var startLayout: LinearLayout
    private lateinit var disconnectLayout: LinearLayout // BARU

    private lateinit var btnStartApp: Button
    private lateinit var btnBackToMenu: Button // BARU
    private lateinit var tvUsbDebugStatus: TextView

    // Controls
    private lateinit var btnPause: Button
    private lateinit var btnMute: Button
    private lateinit var btnSwitch: Button
    private lateinit var btnFlash: Button
    private lateinit var btnSettings: Button
    private lateinit var btnStop: Button
    private lateinit var sbZoom: SeekBar
    private lateinit var btnFocusMode: Button
    private lateinit var sbFocus: SeekBar
    private lateinit var btnExposureMode: Button
    private lateinit var sbExposure: SeekBar

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideMenuRunnable = Runnable { controlLayout.visibility = View.GONE }

    // Status Vars
    private var isPaused = false
    private var isMuted = false
    private var currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var camera: Camera? = null
    private var isFlashOn = false
    private var isManualFocus = false
    private var isManualExposure = false
    private var currentResolution = Size(1280, 720)
    private var currentFpsDelay: Long = 33

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) launchCameraSequence()
        else Toast.makeText(this, "Izin Kamera Dibutuhkan!", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Init UI
        rootLayout = findViewById(R.id.rootLayout)
        controlLayout = findViewById(R.id.controlLayout)
        indicatorLayout = findViewById(R.id.indicatorLayout)
        tvIpInfo = findViewById(R.id.tvIpAddress)
        tvStatusIndicator = findViewById(R.id.tvStatusIndicator)
        badgeMute = findViewById(R.id.badgeMute)
        badgePause = findViewById(R.id.badgePause)

        startLayout = findViewById(R.id.startLayout)
        disconnectLayout = findViewById(R.id.disconnectLayout) // Init

        btnStartApp = findViewById(R.id.btnStartApp)
        btnBackToMenu = findViewById(R.id.btnBackToMenu) // Init
        tvUsbDebugStatus = findViewById(R.id.tvUsbDebugStatus)

        btnPause = findViewById(R.id.btnPause)
        btnMute = findViewById(R.id.btnMute)
        btnSwitch = findViewById(R.id.btnSwitch)
        btnFlash = findViewById(R.id.btnFlash)
        btnSettings = findViewById(R.id.btnSettings)
        btnStop = findViewById(R.id.btnStop)
        sbZoom = findViewById(R.id.seekBarZoom)
        btnFocusMode = findViewById(R.id.btnFocusMode)
        sbFocus = findViewById(R.id.seekBarFocus)
        btnExposureMode = findViewById(R.id.btnExposureMode)
        sbExposure = findViewById(R.id.seekBarExposure)

        checkUsbStatus()

        btnStartApp.setOnClickListener {
            checkUsbStatus()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
                launchCameraSequence()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        // --- TOMBOL STOP / DISCONNECT ---
        btnStop.setOnClickListener {
            performDisconnect() // Panggil fungsi disconnect
        }

        // --- TOMBOL KEMBALI KE MENU (BARU) ---
        btnBackToMenu.setOnClickListener {
            resetToMainMenu() // Panggil fungsi reset
        }

        rootLayout.setOnClickListener { showMenuAndResetTimer() }
        findViewById<PreviewView>(R.id.viewFinder).setOnClickListener { showMenuAndResetTimer() }
        setupTouchReset(controlLayout)

        // Listeners Standard
        btnPause.setOnClickListener {
            resetAutoHideTimer()
            isPaused = !isPaused
            if (isPaused) {
                btnPause.text = "Resume"
                badgePause.visibility = View.VISIBLE
                showStatus("Video Paused")
            } else {
                btnPause.text = "Pause"
                badgePause.visibility = View.GONE
                showStatus("Video Resumed")
            }
        }

        btnMute.setOnClickListener {
            resetAutoHideTimer()
            isMuted = !isMuted
            if (isMuted) {
                btnMute.text = "Unmute"
                badgeMute.visibility = View.VISIBLE
                showStatus("Mic Muted")
            } else {
                btnMute.text = "Mute"
                badgeMute.visibility = View.GONE
                showStatus("Mic Active")
            }
        }

        btnSwitch.setOnClickListener {
            resetAutoHideTimer()
            val newStatus = if (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                currentCameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                "Kamera Depan"
            } else {
                currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                "Kamera Belakang"
            }
            isFlashOn = false
            btnFlash.text = "Flash Off"
            sbZoom.progress = 0
            isManualFocus = false
            isManualExposure = false
            updateFocusUI()
            updateExposureUI()
            showStatus(newStatus)
            startCamera()
        }

        btnFlash.setOnClickListener {
            resetAutoHideTimer()
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                isFlashOn = !isFlashOn
                camera?.cameraControl?.enableTorch(isFlashOn)
                btnFlash.text = if (isFlashOn) "Flash ON" else "Flash Off"
                showStatus(if (isFlashOn) "Flash Menyala" else "Flash Mati")
            } else {
                Toast.makeText(this, "Kamera ini tidak punya Flash", Toast.LENGTH_SHORT).show()
            }
        }

        btnSettings.setOnClickListener {
            resetAutoHideTimer()
            showSettingsMenu()
        }

        // SeekBars
        sbZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                resetAutoHideTimer()
                val zoomLevel = progress / 100f
                camera?.cameraControl?.setLinearZoom(zoomLevel)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { resetAutoHideTimer() }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { resetAutoHideTimer() }
        })

        btnFocusMode.setOnClickListener {
            resetAutoHideTimer()
            isManualFocus = !isManualFocus
            updateFocusUI()
            if (!isManualFocus) {
                setAutoFocus()
                showStatus("Focus: Auto")
            } else {
                showStatus("Focus: Manual")
                val dist = sbFocus.progress.toFloat()
                setManualFocus(dist)
            }
        }
        sbFocus.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                resetAutoHideTimer()
                if (isManualFocus) setManualFocus(progress.toFloat())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { resetAutoHideTimer() }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { resetAutoHideTimer() }
        })

        btnExposureMode.setOnClickListener {
            resetAutoHideTimer()
            isManualExposure = !isManualExposure
            updateExposureUI()
            if (!isManualExposure) {
                camera?.cameraControl?.setExposureCompensationIndex(0)
                showStatus("Exposure: Auto")
            } else {
                showStatus("Exposure: Manual")
                sbExposure.progress = 50
            }
        }
        sbExposure.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                resetAutoHideTimer()
                if (isManualExposure) setManualExposure(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { resetAutoHideTimer() }
            override fun onStopTrackingTouch(seekBar: SeekBar?) { resetAutoHideTimer() }
        })
    }

    // --- ROTATION & ANALYSIS LOGIC ---

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val previewView = findViewById<PreviewView>(R.id.viewFinder)
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(currentResolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor()) { image ->
                if (isPaused) { image.close(); return@setAnalyzer }

                try {
                    val rotationDegrees = image.imageInfo.rotationDegrees
                    val nv21Bytes = yuvToNv21(image)
                    val yuvImage = YuvImage(nv21Bytes, ImageFormat.NV21, image.width, image.height, null)
                    val rawOut = ByteArrayOutputStream()

                    val quality = if (image.width >= 2000) 40 else 60
                    yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), quality, rawOut)
                    val rawJpeg = rawOut.toByteArray()

                    val finalBytes = if (rotationDegrees != 0) {
                        val bitmap = BitmapFactory.decodeByteArray(rawJpeg, 0, rawJpeg.size)
                        val matrix = Matrix()
                        matrix.postRotate(rotationDegrees.toFloat())

                        val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                        val rotatedOut = ByteArrayOutputStream()
                        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, rotatedOut)

                        bitmap.recycle()
                        rotatedBitmap.recycle()
                        rotatedOut.toByteArray()
                    } else {
                        rawJpeg
                    }
                    server.pushImage(finalBytes)
                } catch (e: Exception) { e.printStackTrace() } finally { image.close() }
            }
            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, currentCameraSelector, preview, imageAnalysis)
            } catch (e: Exception) { Log.e("Camera", "Gagal bind kamera", e) }
        }, ContextCompat.getMainExecutor(this))
    }

    // --- FUNGSI RESET & DISCONNECT ---

    private fun performDisconnect() {
        // 1. Matikan Server & Kamera
        if (::server.isInitialized) server.stop()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()
        }, ContextCompat.getMainExecutor(this))

        // 2. Tampilkan Layar Disconnect
        controlLayout.visibility = View.GONE
        indicatorLayout.visibility = View.GONE
        tvIpInfo.visibility = View.GONE
        disconnectLayout.visibility = View.VISIBLE // Tampil layar merah
    }

    private fun resetToMainMenu() {
        // 1. Reset Semua UI ke kondisi awal
        disconnectLayout.visibility = View.GONE
        startLayout.visibility = View.VISIBLE

        // 2. Reset Variabel
        isPaused = false
        isMuted = false
        isFlashOn = false
        isManualFocus = false
        isManualExposure = false
        btnPause.text = "Pause"
        btnMute.text = "Mute"
        btnFlash.text = "Flash Off"
        badgePause.visibility = View.GONE
        badgeMute.visibility = View.GONE

        // 3. Cek ulang USB
        checkUsbStatus()
    }

    // --- Helper Lainnya ---

    private fun checkUsbStatus() {
        val adb = Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0)
        if (adb == 1) {
            tvUsbDebugStatus.text = "USB Debugging: AKTIF (Siap untuk Kabel USB)"
            tvUsbDebugStatus.setTextColor(0xFF00FF00.toInt())
        } else {
            tvUsbDebugStatus.text = "USB Debugging: NONAKTIF (Hanya via WiFi)"
            tvUsbDebugStatus.setTextColor(0xFFFF0000.toInt())
        }
    }

    private fun launchCameraSequence() {
        startLayout.visibility = View.GONE
        controlLayout.visibility = View.VISIBLE
        indicatorLayout.visibility = View.VISIBLE
        tvIpInfo.visibility = View.VISIBLE
        startCameraAndServer()
        resetAutoHideTimer()
    }

    private fun showSettingsMenu() {
        val popup = PopupMenu(this, btnSettings)
        val m = popup.menu
        m.add(0, 1, 0, "720p  | 30 FPS")
        m.add(0, 2, 1, "720p  | 60 FPS")
        m.add(0, 3, 2, "1080p | 30 FPS")
        m.add(0, 4, 3, "1080p | 60 FPS")
        m.add(0, 5, 4, "2K    | 30 FPS")
        m.add(0, 6, 5, "4K    | 30 FPS (Berat)")

        popup.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                1 -> applySettings(1280, 720, 33)
                2 -> applySettings(1280, 720, 10)
                3 -> applySettings(1920, 1080, 33)
                4 -> applySettings(1920, 1080, 10)
                5 -> applySettings(2560, 1440, 33)
                6 -> applySettings(3840, 2160, 33)
            }
            true
        }
        popup.show()
    }

    private fun applySettings(width: Int, height: Int, fpsDelay: Long) {
        currentResolution = Size(width, height)
        currentFpsDelay = fpsDelay
        val fpsName = if (fpsDelay <= 16) "60fps" else "30fps"
        showStatus("${height}p $fpsName Applied")
        startCamera()
    }

    private fun showMenuAndResetTimer() {
        controlLayout.visibility = View.VISIBLE
        tvIpInfo.visibility = View.VISIBLE
        resetAutoHideTimer()
    }

    private fun resetAutoHideTimer() {
        mainHandler.removeCallbacks(hideMenuRunnable)
        mainHandler.postDelayed(hideMenuRunnable, 3000)
    }

    private fun setupTouchReset(view: View) {
        if (view !is SeekBar && view !is Button) {
            view.setOnTouchListener { _, _ -> resetAutoHideTimer(); false }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) { setupTouchReset(view.getChildAt(i)) }
        }
    }

    private fun setManualExposure(progress: Int) {
        val cam = camera ?: return
        val exposureState = cam.cameraInfo.exposureState
        if (!exposureState.isExposureCompensationSupported) return
        val range = exposureState.exposureCompensationRange
        val min = range.lower
        val max = range.upper
        val exposureIndex = min + (progress / 100f) * (max - min)
        cam.cameraControl.setExposureCompensationIndex(exposureIndex.toInt())
    }

    private fun setManualFocus(sliderValue: Float) {
        val cam = camera ?: return
        val camera2Control = Camera2CameraControl.from(cam.cameraControl)
        val distance = sliderValue / 10.0f
        val captureRequestOptions = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
            .build()
        camera2Control.setCaptureRequestOptions(captureRequestOptions)
    }

    private fun setAutoFocus() {
        val cam = camera ?: return
        val camera2Control = Camera2CameraControl.from(cam.cameraControl)
        val captureRequestOptions = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            .build()
        camera2Control.setCaptureRequestOptions(captureRequestOptions)
    }

    private fun updateFocusUI() {
        if (isManualFocus) {
            btnFocusMode.text = "Focus: Manual"
            sbFocus.visibility = View.VISIBLE
        } else {
            btnFocusMode.text = "Focus: Auto"
            sbFocus.visibility = View.GONE
        }
    }

    private fun updateExposureUI() {
        if (isManualExposure) {
            btnExposureMode.text = "Exp: Manual"
            sbExposure.visibility = View.VISIBLE
        } else {
            btnExposureMode.text = "Exp: Auto"
            sbExposure.visibility = View.GONE
        }
    }

    private fun showStatus(text: String) {
        tvStatusIndicator.text = text
        tvStatusIndicator.visibility = View.VISIBLE
        val tempHandler = Handler(Looper.getMainLooper())
        tempHandler.postDelayed({ tvStatusIndicator.visibility = View.GONE }, 2000)
    }

    private fun getIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress ?: "Unknown"
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return "No Network"
    }

    private fun startCameraAndServer() {
        startCamera()
        server = MjpegServer(8080)
        try {
            server.start()
            val ip = getIpAddress()
            tvIpInfo.text = "Server IP: http://$ip:8080"
        } catch (e: Exception) {
            tvIpInfo.text = "Error Server: ${e.message}"
        }
    }

    private fun yuvToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        yBuffer.get(nv21, 0, ySize)
        val pixelStride = vPlane.pixelStride
        val rowStride = vPlane.rowStride
        var pos = ySize
        if (pixelStride == 1) {
            for (row in 0 until height / 2) {
                for (col in 0 until width / 2) {
                    val vIndex = row * rowStride + col * pixelStride
                    val uIndex = row * uPlane.rowStride + col * uPlane.pixelStride
                    nv21[pos++] = vBuffer.get(vIndex)
                    nv21[pos++] = uBuffer.get(uIndex)
                }
            }
        } else {
            for (row in 0 until height / 2) {
                for (col in 0 until width / 2) {
                    val vIndex = row * rowStride + col * pixelStride
                    val uIndex = row * uPlane.rowStride + col * uPlane.pixelStride
                    nv21[pos++] = vBuffer.get(vIndex)
                    nv21[pos++] = uBuffer.get(uIndex)
                }
            }
        }
        return nv21
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::server.isInitialized) {
            server.stop()
        }
    }

    inner class MjpegServer(port: Int) : NanoHTTPD(port) {
        @Volatile private var currentFrame: ByteArray? = null
        fun pushImage(image: ByteArray) { currentFrame = image }

        override fun serve(session: IHTTPSession): Response {
            return newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=--myboundary", MjpegInputStream())
        }

        private inner class MjpegInputStream : InputStream() {
            private var buffer: ByteArray = ByteArray(0)
            private var index = 0

            override fun read(): Int {
                if (index >= buffer.size) loadNextFrame()
                return if (index < buffer.size) buffer[index++].toInt() and 0xFF else -1
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (index >= buffer.size) loadNextFrame()
                if (index >= buffer.size) return -1
                val available = buffer.size - index
                val toCopy = Math.min(len, available)
                System.arraycopy(buffer, index, b, off, toCopy)
                index += toCopy
                return toCopy
            }

            private fun loadNextFrame() {
                var localFrame = currentFrame
                while (localFrame == null) {
                    try { Thread.sleep(10) } catch (e: InterruptedException) {}
                    localFrame = currentFrame
                }
                val finalFrame = localFrame!!
                val header = "--myboundary\r\nContent-Type: image/jpeg\r\nContent-Length: ${finalFrame.size}\r\n\r\n"
                val out = ByteArrayOutputStream()
                try {
                    out.write(header.toByteArray())
                    out.write(finalFrame)
                    out.write("\r\n".toByteArray())
                } catch (e: Exception) { }
                buffer = out.toByteArray()
                index = 0
                try { Thread.sleep(currentFpsDelay) } catch (e: Exception) {}
            }
        }
    }
}