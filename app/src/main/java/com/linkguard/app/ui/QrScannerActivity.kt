package com.linkguard.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.linkguard.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors


@androidx.camera.core.ExperimentalGetImage
class QrScannerActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraBarcodeScanner: com.google.mlkit.vision.barcode.BarcodeScanner? = null

    private var isProcessing = false
    private var scanCompleted = false

    // ─── Launchers ───────────────────────────────────────────────────────────

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else {
                Toast.makeText(this, getString(R.string.camera_permission_denied), Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    private val galleryLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { processImageFromGallery(it) }
        }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(previewView)

        // 1. Add Custom Overlay View (Scanner frame and laser)
        val overlayView = QrScannerOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(overlayView)

        // 2. Add Instruction Text
        val instructionText = TextView(this).apply {
            text = getString(R.string.qr_align_instruction)
            setTextColor(Color.WHITE)
            textSize = 12f
            letterSpacing = 0.1f
            gravity = Gravity.CENTER
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                topMargin = 220.dpToPx() // Position below the box
            }
            layoutParams = params
        }
        root.addView(instructionText)

        // 3. Add Upload Button Overlay
        val uploadBtn = MaterialButton(this).apply {
            text = getString(R.string.qr_upload_from_gallery)
            setBackgroundColor(getColor(R.color.e_card))
            setTextColor(getColor(R.color.e_blue))
            setStrokeColorResource(R.color.e_blue)
            strokeWidth = 2
            cornerRadius = 30
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = 60.dpToPx()
            }
            layoutParams = params
            setOnClickListener { galleryLauncher.launch("image/*") }
        }
        root.addView(uploadBtn)

        // 4. Add Back Button Overlay
        val backBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            setIconResource(R.drawable.ic_back)
            setIconTintResource(R.color.white)
            contentDescription = getString(R.string.cd_back)
            val params = FrameLayout.LayoutParams(60.dpToPx(), 60.dpToPx()).apply {
                gravity = Gravity.TOP or Gravity.START
                topMargin = 16.dpToPx()
                marginStart = 16.dpToPx()
            }
            layoutParams = params
            setOnClickListener { finish() }
        }
        root.addView(backBtn)

        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val barcodeScanner = BarcodeScanning.getClient()
            cameraBarcodeScanner = barcodeScanner

            val imageAnalysis = ImageAnalysis.Builder()
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (scanCompleted || isProcessing) {
                            imageProxy.close()
                            return@setAnalyzer
                        }

                        val mediaImage = imageProxy.image ?: run {
                            imageProxy.close()
                            return@setAnalyzer
                        }

                        isProcessing = true
                        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

                        barcodeScanner.process(inputImage)
                            .addOnSuccessListener { barcodes ->
                                for (barcode in barcodes) {
                                    handleResult(barcode.rawValue ?: continue)
                                    break
                                }
                            }
                            .addOnCompleteListener {
                                isProcessing = false
                                imageProxy.close()
                            }
                    }
                }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImageFromGallery(uri: Uri) {
        // Decode the gallery image off the main thread — fromFilePath reads/decodes the
        // bitmap synchronously and can ANR on large images. The scanner is created only
        // after a successful decode so it can't leak if this scope is cancelled first.
        lifecycleScope.launch {
            val image = withContext(Dispatchers.IO) {
                runCatching { InputImage.fromFilePath(this@QrScannerActivity, uri) }.getOrNull()
            } ?: run {
                Toast.makeText(this@QrScannerActivity, getString(R.string.qr_image_open_error), Toast.LENGTH_SHORT).show()
                return@launch
            }

            val scanner = BarcodeScanning.getClient()
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    if (barcodes.isEmpty()) {
                        Toast.makeText(this@QrScannerActivity, getString(R.string.qr_none_in_image), Toast.LENGTH_LONG).show()
                    } else {
                        handleResult(barcodes[0].rawValue ?: "")
                    }
                }
                .addOnFailureListener {
                    Toast.makeText(this@QrScannerActivity, getString(R.string.qr_read_failed), Toast.LENGTH_SHORT).show()
                }
                .addOnCompleteListener {
                    scanner.close()
                }
        }
    }

    private fun handleResult(rawValue: String) {
        if (scanCompleted) return
        scanCompleted = true
        val resultIntent = Intent().apply {
            putExtra("QR_RESULT", rawValue)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        cameraBarcodeScanner?.close()
    }
}