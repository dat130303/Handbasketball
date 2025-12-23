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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.math.sqrt


class HandLandmarkerHelper(
    private val context: Context,
    private val runningMode: RunningMode = RunningMode.LIVE_STREAM,
    private val minHandDetectionConfidence: Float = 0.5f,
    private val minHandTrackingConfidence: Float = 0.5f,
    private val minHandPresenceConfidence: Float = 0.5f,
    private val maxNumHands: Int = 1,
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

            // ===== 1. VẼ LÒNG BÀN TAY (PALM) + GỐC NGÓN CÁI =====
            // Thêm điểm gốc ngón cái (landmark 1) để lòng bàn tay đầy đủ hơn
            val palmPoints = listOf(0, 1, 2, 5, 9, 13, 17, 0).map { idx ->
                Offset(
                    landmarks[idx].x() * size.width,
                    landmarks[idx].y() * size.height
                )
            }

            drawPath(
                path = Path().apply {
                    moveTo(palmPoints[0].x, palmPoints[0].y)
                    palmPoints.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                },
                color = Color(0xFFFFDBB5).copy(alpha = 0.85f), // màu da
                style = Fill
            )

            // Viền lòng bàn tay
            drawPath(
                path = Path().apply {
                    moveTo(palmPoints[0].x, palmPoints[0].y)
                    palmPoints.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                },
                color = Color(0xFFD4A574).copy(alpha = 0.6f),
                style = Stroke(width = 2f)
            )

            // ===== 2. VẼ CÁC NGÓN TAY VỚI DA THỊT =====
            val fingers = listOf(
                listOf(2, 3, 4),            // Ngón cái (bắt đầu từ khớp 2)
                listOf(5, 6, 7, 8),         // Ngón trỏ
                listOf(9, 10, 11, 12),      // Ngón giữa
                listOf(13, 14, 15, 16),     // Ngón áp út
                listOf(17, 18, 19, 20)      // Ngón út
            )

            fingers.forEach { fingerIndices ->
                // VẼ TỪNG ĐỐT NGÓN TAY NHƯ HÌNH TRỤ
                for (i in 0 until fingerIndices.size - 1) {
                    val startIdx = fingerIndices[i]
                    val endIdx = fingerIndices[i + 1]

                    val start = Offset(
                        landmarks[startIdx].x() * size.width,
                        landmarks[startIdx].y() * size.height
                    )
                    val end = Offset(
                        landmarks[endIdx].x() * size.width,
                        landmarks[endIdx].y() * size.height
                    )

                    // Độ dày giảm dần từ gốc đến đầu ngón - MẬP HƠN
                    val startThickness = when {
                        fingerIndices[0] == 2 -> when (i) {  // Ngón cái
                            0 -> 20f  // CŨ: 14f
                            1 -> 17f  // CŨ: 12f
                            else -> 14f  // CŨ: 10f
                        }
                        else -> when (i) {  // Các ngón khác
                            0 -> 18f  // CŨ: 12f
                            1 -> 15f  // CŨ: 10f
                            2 -> 12f  // CŨ: 8f
                            else -> 10f  // CŨ: 7f
                        }
                    }

                    val endThickness = startThickness * 0.90f  // CŨ: 0.85f - thu nhỏ ít hơn

                    // Tính vector vuông góc để tạo độ rộng
                    val dx = end.x - start.x
                    val dy = end.y - start.y
                    val length = sqrt(dx * dx + dy * dy)

                    if (length > 0.1f) {
                        val perpX = -dy / length
                        val perpY = dx / length

                        // 4 điểm tạo thành hình chữ nhật (đốt ngón)
                        val p1 = Offset(start.x + perpX * startThickness, start.y + perpY * startThickness)
                        val p2 = Offset(start.x - perpX * startThickness, start.y - perpY * startThickness)
                        val p3 = Offset(end.x - perpX * endThickness, end.y - perpY * endThickness)
                        val p4 = Offset(end.x + perpX * endThickness, end.y + perpY * endThickness)

                        // Vẽ đốt ngón (màu da)
                        drawPath(
                            path = Path().apply {
                                moveTo(p1.x, p1.y)
                                lineTo(p4.x, p4.y)
                                lineTo(p3.x, p3.y)
                                lineTo(p2.x, p2.y)
                                close()
                            },
                            color = Color(0xFFFFDBB5).copy(alpha = 0.9f)
                        )

                        // Viền đốt ngón (tạo chiều sâu)
                        drawPath(
                            path = Path().apply {
                                moveTo(p1.x, p1.y)
                                lineTo(p4.x, p4.y)
                                lineTo(p3.x, p3.y)
                                lineTo(p2.x, p2.y)
                                close()
                            },
                            color = Color(0xFFD4A574).copy(alpha = 0.3f),
                            style = Stroke(width = 1.5f)
                        )

                        // Vẽ đầu ngón tròn
                        if (i == fingerIndices.size - 2) {
                            drawCircle(
                                color = Color(0xFFFFDBB5).copy(alpha = 0.9f),
                                radius = endThickness,
                                center = end
                            )
                            drawCircle(
                                color = Color(0xFFD4A574).copy(alpha = 0.3f),
                                radius = endThickness,
                                center = end,
                                style = Stroke(width = 1.5f)
                            )
                        }
                    }
                }

                // ===== VẼ CÁC KHỚP NGÓN TAY =====
                fingerIndices.forEach { idx ->
                    val point = Offset(
                        landmarks[idx].x() * size.width,
                        landmarks[idx].y() * size.height
                    )

                    val isThumb = fingerIndices[0] == 2
                    val isTip = idx in listOf(4, 8, 12, 16, 20)

                    val radius = when {
                        isTip && isThumb -> 15f  // CŨ: 11f - Đầu ngón cái
                        isTip -> 11f  // CŨ: 8f - Đầu ngón khác
                        isThumb -> 18f  // CŨ: 13f - Khớp ngón cái
                        else -> 15f  // CŨ: 11f - Khớp ngón khác
                    }

                    // Khớp ngón tay
                    drawCircle(
                        color = Color(0xFFFFDBB5),
                        radius = radius,
                        center = point
                    )

                    // Viền khớp
                    drawCircle(
                        color = Color(0xFFD4A574).copy(alpha = 0.3f),
                        radius = radius + 1f,
                        center = point,
                        style = Stroke(width = 1.5f)
                    )

                    // Đường gấp khớp (wrinkle)
                    if (!isTip && idx != fingerIndices[0]) {
                        drawCircle(
                            color = Color(0xFFD4A574).copy(alpha = 0.15f),
                            radius = radius * 0.5f,
                            center = point
                        )
                    }
                }
            }

            // ===== 3. VẼ ĐIỂM CỔ TAY (WRIST) =====
            val wrist = Offset(
                landmarks[0].x() * size.width,
                landmarks[0].y() * size.height
            )

            drawCircle(
                color = Color(0xFFFFDBB5),
                radius = 16f,
                center = wrist
            )

            drawCircle(
                color = Color(0xFFD4A574).copy(alpha = 0.4f),
                radius = 17f,
                center = wrist,
                style = Stroke(width = 2f)
            )
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