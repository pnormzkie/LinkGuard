package com.linkguard.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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

    private lateinit var overlayView: QrScannerOverlayView
    private lateinit var instructionText: TextView
    private lateinit var cameraOffPanel: LinearLayout
    private lateinit var allowCameraLink: TextView
    private lateinit var uploadBtn: MaterialButton

    private var isProcessing = false
    private var scanCompleted = false

    // ─── Launchers ───────────────────────────────────────────────────────────

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // Denied: stay open. Gallery upload needs no camera, and closing here made it
            // unreachable for good once Android stopped showing the permission dialog.
            if (granted) showCameraOn() else showCameraOff()
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
        overlayView = QrScannerOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(overlayView)

        // 2. Add Instruction Text
        instructionText = TextView(this).apply {
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

        // 2b. Camera-off message inside the frame and "Allow camera" below it (hidden until denied)
        cameraOffPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.62f).toInt(),
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
            addView(ImageView(this@QrScannerActivity).apply {
                setImageResource(R.drawable.ic_camera_off)
                imageTintList = ColorStateList.valueOf(getColor(R.color.e_muted))
                layoutParams = LinearLayout.LayoutParams(36.dpToPx(), 36.dpToPx()).apply {
                    bottomMargin = 12.dpToPx()
                }
            })
            addView(TextView(this@QrScannerActivity).apply {
                text = getString(R.string.qr_camera_off_title)
                setTextColor(Color.WHITE)
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })
            addView(TextView(this@QrScannerActivity).apply {
                text = getString(R.string.qr_camera_off_body)
                setTextColor(getColor(R.color.e_muted))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, 6.dpToPx(), 0, 0)
            })
        }
        root.addView(cameraOffPanel)

        allowCameraLink = TextView(this).apply {
            text = getString(R.string.qr_allow_camera)
            setTextColor(getColor(R.color.e_blue_soft))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(24.dpToPx(), 12.dpToPx(), 24.dpToPx(), 12.dpToPx())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                topMargin = 220.dpToPx() // where the instruction sits while the camera is on
            }
            setOnClickListener { onAllowCameraClicked() }
        }
        root.addView(allowCameraLink)

        // 3. Add Upload Button Overlay
        uploadBtn = MaterialButton(this).apply {
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

        if (hasCameraPermission()) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        // Back from app settings with the camera now allowed.
        if (overlayView.cameraOff && hasCameraPermission()) showCameraOn()
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun showCameraOn() {
        overlayView.cameraOff = false
        instructionText.visibility = View.VISIBLE
        cameraOffPanel.visibility = View.GONE
        allowCameraLink.visibility = View.GONE
        uploadBtn.setBackgroundColor(getColor(R.color.e_card))
        uploadBtn.setTextColor(getColor(R.color.e_blue))
        uploadBtn.strokeWidth = 2
        startCamera()
    }

    /** Option A (approved mockup): scanner stays, frame says "Camera is off", gallery is the main action. */
    private fun showCameraOff() {
        overlayView.cameraOff = true
        instructionText.visibility = View.GONE
        cameraOffPanel.visibility = View.VISIBLE
        allowCameraLink.visibility = View.VISIBLE
        uploadBtn.setBackgroundColor(getColor(R.color.e_blue))
        uploadBtn.setTextColor(Color.WHITE)
        uploadBtn.strokeWidth = 0
    }

    private fun onAllowCameraClicked() {
        // After a denial Android still asks while a rationale is due; once it stops asking
        // (denied twice, or "don't ask again"), only the app's settings page can grant it.
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            )
        }
    }

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