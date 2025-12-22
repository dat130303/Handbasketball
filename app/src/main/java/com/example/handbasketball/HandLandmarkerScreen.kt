package com.example.handbasketball

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import android.Manifest
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors


class HandLandmarkerHelper(
    private val context: Context,
    private val runningMode: RunningMode = RunningMode.LIVE_STREAM,
    private val minHandDetectionConfidence: Float = 0.5f,
    private val minHandTrackingConfidence: Float = 0.5f,
    private val minHandPresenceConfidence: Float = 0.5f,
    private val maxNumHands: Int = 2,
    private val onResults: (HandLandmarkerResult, MPImage) -> Unit,
    private val onError: (String) -> Unit
) {
    private var handLandmarker: HandLandmarker? = null

    init {
        setupHandLandmarker()
    }

    private fun setupHandLandmarker() {
        try {
            val baseOptions = BaseOptions.builder()
                .setDelegate(Delegate.GPU)
                .setModelAssetPath("hand_landmarker.task")
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(runningMode)
                .setMinHandDetectionConfidence(minHandDetectionConfidence)
                .setMinTrackingConfidence(minHandTrackingConfidence)
                .setMinHandPresenceConfidence(minHandPresenceConfidence)
                .setNumHands(maxNumHands)

            if (runningMode == RunningMode.LIVE_STREAM) {
                options.setResultListener { result, input ->
                    onResults(result, input)
                }.setErrorListener { error ->
                    onError(error.message ?: "Unknown error")
                }
            }

            handLandmarker = HandLandmarker.createFromOptions(context, options.build())
        } catch (e: Exception) {
            onError("Error setting up Hand Landmarker: ${e.message}")
        }
    }

    fun detectLiveStream(imageProxy: ImageProxy, isFrontCamera: Boolean) {
        val frameTime = System.currentTimeMillis()

        val bitmap = imageProxy.toBitmap()
        val matrix = Matrix().apply {
            postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
            if (isFrontCamera) {
                postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
            }
        }

        val rotatedBitmap = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        )

        val mpImage = BitmapImageBuilder(rotatedBitmap).build()
        handLandmarker?.detectAsync(mpImage, frameTime)

        imageProxy.close()
    }

    fun detectImage(bitmap: Bitmap): HandLandmarkerResult? {
        return try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            handLandmarker?.detect(mpImage)
        } catch (e: Exception) {
            onError("Error detecting hands: ${e.message}")
            null
        }
    }

    fun clear() {
        handLandmarker?.close()
        handLandmarker = null
    }
}

// Extension function
private fun ImageProxy.toBitmap(): Bitmap {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}




@Composable
fun HandLandmarkerScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var handLandmarkerResult by remember { mutableStateOf<HandLandmarkerResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasPermission) {
            CameraPreview(
                onHandDetected = { result ->
                    handLandmarkerResult = result
                },
                onError = { error ->
                    errorMessage = error
                }
            )

            // Vẽ landmarks lên camera preview
            handLandmarkerResult?.let { result ->
                HandLandmarksOverlay(result)
            }

            // Hiển thị thông tin
            HandInfoCard(
                handLandmarkerResult = handLandmarkerResult,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            )
        } else {
            Text(
                "Camera permission is required",
                modifier = Modifier.align(Alignment.Center)
            )
        }

        errorMessage?.let { error ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
            ) {
                Text(error)
            }
        }
    }
}

@Composable
fun CameraPreview(
    onHandDetected: (HandLandmarkerResult) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    val handLandmarkerHelper = remember {
        HandLandmarkerHelper(
            context = context,
            onResults = { result, _ ->
                onHandDetected(result)
            },
            onError = onError
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            handLandmarkerHelper.clear()
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    handLandmarkerHelper.detectLiveStream(imageProxy, true)
                }

                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    onError("Camera binding failed: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun HandLandmarksOverlay(result: HandLandmarkerResult) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        result.landmarks().forEachIndexed { handIndex, landmarks ->
            // Vẽ các điểm landmark
            landmarks.forEach { landmark ->
                val x = landmark.x() * size.width
                val y = landmark.y() * size.height

                drawCircle(
                    color = if (handIndex == 0) Color.Green else Color.Blue,
                    radius = 8f,
                    center = Offset(x, y)
                )
            }

            // Vẽ các đường nối giữa các điểm
            val connections = listOf(
                // Ngón cái
                0 to 1, 1 to 2, 2 to 3, 3 to 4,
                // Ngón trỏ
                0 to 5, 5 to 6, 6 to 7, 7 to 8,
                // Ngón giữa
                0 to 9, 9 to 10, 10 to 11, 11 to 12,
                // Ngón áp út
                0 to 13, 13 to 14, 14 to 15, 15 to 16,
                // Ngón út
                0 to 17, 17 to 18, 18 to 19, 19 to 20,
                // Lòng bàn tay
                5 to 9, 9 to 13, 13 to 17
            )

            connections.forEach { (start, end) ->
                val startPoint = landmarks[start]
                val endPoint = landmarks[end]

                drawLine(
                    color = if (handIndex == 0) Color.Green else Color.Blue,
                    start = Offset(
                        startPoint.x() * size.width,
                        startPoint.y() * size.height
                    ),
                    end = Offset(
                        endPoint.x() * size.width,
                        endPoint.y() * size.height
                    ),
                    strokeWidth = 4f
                )
            }
        }
    }
}

@Composable
fun HandInfoCard(
    handLandmarkerResult: HandLandmarkerResult?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Hand Detection Info",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (handLandmarkerResult != null && handLandmarkerResult.landmarks().isNotEmpty()) {
                Text(
                    text = "Hands detected: ${handLandmarkerResult.landmarks().size}",
                    style = MaterialTheme.typography.bodyMedium
                )

                handLandmarkerResult.handednesses().forEachIndexed { index, handedness ->
                    val categoryName = handedness[0].categoryName()
                    val score = handedness[0].score()

                    Text(
                        text = "Hand ${index + 1}: $categoryName (${(score * 100).toInt()}%)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                Text(
                    text = "No hands detected",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}