// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.kiosk

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.common.utils.android.showThemedToast
import timber.log.Timber
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Built-in offline CameraX + ZXing QR code scanner for registering a Kiosk key,
 * locking Kiosk mode, or unlocking Kiosk mode without launching external camera apps.
 */
class KioskQrScannerActivity : AnkiActivity() {
    enum class ScanMode {
        REGISTER_KEY,
        LOCK,
        UNLOCK,
    }

    private lateinit var previewView: PreviewView
    private lateinit var statusTextView: TextView
    private lateinit var scanMode: ScanMode
    private lateinit var cameraExecutor: ExecutorService

    private var cameraProvider: ProcessCameraProvider? = null
    private var activeCamera: Camera? = null
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var isTorchEnabled: Boolean = false
    private val handledResult = AtomicBoolean(false)

    @Volatile
    private var lastMismatchFeedbackMs: Long = 0L

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                showThemedToast(this, R.string.kiosk_camera_permission_required, false)
                setResult(RESULT_CANCELED)
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val modeOrdinal = intent.getIntExtra(EXTRA_SCAN_MODE, ScanMode.LOCK.ordinal)
        scanMode = ScanMode.entries.getOrElse(modeOrdinal) { ScanMode.LOCK }
        cameraExecutor = Executors.newSingleThreadExecutor()

        setupUi()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
    }

    private fun setupUi() {
        val density = resources.displayMetrics.density

        fun dp(value: Int): Int = (value * density).toInt()

        val root =
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            }

        previewView =
            PreviewView(this).apply {
                layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        root.addView(previewView)

        val reticleSize = dp(240)
        val reticle =
            FrameLayout(this).apply {
                layoutParams =
                    FrameLayout.LayoutParams(reticleSize, reticleSize).apply {
                        gravity = Gravity.CENTER
                    }
                background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(16).toFloat()
                        setStroke(dp(3), Color.WHITE)
                        setColor(Color.TRANSPARENT)
                    }
            }
        root.addView(reticle)

        val topOverlay =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xAA000000.toInt())
                setPadding(dp(20), dp(36), dp(20), dp(16))
                layoutParams =
                    FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                        gravity = Gravity.TOP
                    }
            }

        val promptRes =
            when (scanMode) {
                ScanMode.REGISTER_KEY -> R.string.kiosk_scan_prompt_register
                ScanMode.LOCK -> R.string.kiosk_scan_prompt_lock
                ScanMode.UNLOCK -> R.string.kiosk_scan_prompt_unlock
            }
        val promptTextView =
            TextView(this).apply {
                text = getString(promptRes)
                setTextColor(Color.WHITE)
                textSize = 16f
                gravity = Gravity.CENTER
            }
        topOverlay.addView(promptTextView)

        statusTextView =
            TextView(this).apply {
                setTextColor(0xFFFF8A80.toInt())
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            }
        topOverlay.addView(statusTextView)
        root.addView(topOverlay)

        val bottomBar =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setBackgroundColor(0xAA000000.toInt())
                setPadding(dp(16), dp(16), dp(16), dp(28))
                layoutParams =
                    FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                        gravity = Gravity.BOTTOM
                    }
            }

        val switchCameraBtn =
            MaterialButton(this).apply {
                text = getString(R.string.kiosk_switch_camera)
                setOnClickListener {
                    lensFacing =
                        if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                    isTorchEnabled = false
                    bindCameraUseCases()
                }
            }
        val torchBtn =
            MaterialButton(this).apply {
                text = getString(R.string.kiosk_toggle_torch)
                val params =
                    LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                        marginStart = dp(12)
                        marginEnd = dp(12)
                    }
                layoutParams = params
                setOnClickListener {
                    val cam = activeCamera ?: return@setOnClickListener
                    if (cam.cameraInfo.hasFlashUnit()) {
                        isTorchEnabled = !isTorchEnabled
                        cam.cameraControl.enableTorch(isTorchEnabled)
                    }
                }
            }
        val cancelBtn =
            MaterialButton(this).apply {
                text = getString(R.string.dialog_cancel)
                setOnClickListener {
                    setResult(RESULT_CANCELED)
                    finish()
                }
            }

        bottomBar.addView(switchCameraBtn)
        bottomBar.addView(torchBtn)
        bottomBar.addView(cancelBtn)
        root.addView(bottomBar)

        setContentView(root)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                runCatching {
                    cameraProvider = future.get()
                    selectInitialCameraFacing()
                    bindCameraUseCases()
                }.onFailure { e ->
                    Timber.e(e, "Failed to initialize CameraX provider")
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun selectInitialCameraFacing() {
        val provider = cameraProvider ?: return
        if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
            lensFacing = CameraSelector.LENS_FACING_BACK
        } else if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
            lensFacing = CameraSelector.LENS_FACING_FRONT
        }
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        if (!provider.hasCamera(selector)) {
            Timber.w("Requested camera facing %d not available", lensFacing)
            return
        }

        val preview =
            Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

        val analysis =
            ImageAnalysis
                .Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { imageAnalysis ->
                    imageAnalysis.setAnalyzer(cameraExecutor, ::analyzeFrame)
                }

        runCatching {
            provider.unbindAll()
            activeCamera = provider.bindToLifecycle(this, selector, preview, analysis)
        }.onFailure { e ->
            Timber.e(e, "Failed to bind CameraX QR scanner use cases")
        }
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        try {
            if (handledResult.get()) return
            val plane = imageProxy.planes.firstOrNull() ?: return
            val luminance =
                KioskQrDecoder.extractContiguousYPlane(
                    buffer = plane.buffer,
                    width = imageProxy.width,
                    height = imageProxy.height,
                    rowStride = plane.rowStride,
                    pixelStride = plane.pixelStride,
                )
            val qrText = KioskQrDecoder.decodeLuminance(luminance, imageProxy.width, imageProxy.height) ?: return
            runOnUiThread { onQrDecoded(qrText) }
        } finally {
            imageProxy.close()
        }
    }

    private fun onQrDecoded(qrText: String) {
        if (handledResult.get() || isFinishing) return
        when (scanMode) {
            ScanMode.REGISTER_KEY -> {
                if (KioskManager.registerQrKey(this, qrText)) {
                    handledResult.set(true)
                    showThemedToast(this, R.string.kiosk_qr_registered_toast, true)
                    setResult(RESULT_OK)
                    finish()
                }
            }
            ScanMode.LOCK -> {
                if (KioskManager.lockWithQr(this, qrText)) {
                    handledResult.set(true)
                    showThemedToast(this, R.string.kiosk_locked_toast, true)
                    setResult(RESULT_OK)
                    finish()
                } else {
                    showMismatchFeedback()
                }
            }
            ScanMode.UNLOCK -> {
                if (KioskManager.unlockWithQr(this, qrText)) {
                    handledResult.set(true)
                    showThemedToast(this, R.string.kiosk_unlocked_toast, true)
                    setResult(RESULT_OK)
                    finish()
                } else {
                    showMismatchFeedback()
                }
            }
        }
    }

    private fun showMismatchFeedback() {
        val now = TimeManager.time.intTimeMS()
        if (now - lastMismatchFeedbackMs < MISMATCH_FEEDBACK_COOLDOWN_MS) return
        lastMismatchFeedbackMs = now
        statusTextView.text = getString(R.string.kiosk_scan_mismatch)
    }

    companion object {
        private const val EXTRA_SCAN_MODE = "extra_scan_mode"
        private const val MISMATCH_FEEDBACK_COOLDOWN_MS = 1500L

        fun createIntent(
            context: Context,
            mode: ScanMode,
        ): Intent =
            Intent(context, KioskQrScannerActivity::class.java).apply {
                putExtra(EXTRA_SCAN_MODE, mode.ordinal)
            }
    }
}
