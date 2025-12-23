package com.example.handbasketball

import android.os.Build
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.*

/** =========================
 *  DATA MODELS
 *  ========================= */

data class Ball(
    var x: Float = 0.5f,
    var y: Float = 0.75f,
    var z: Float = 0f,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var vz: Float = 0f,
    var isFlying: Boolean = false,
    var justThrown: Boolean = false,
    var shotFinished: Boolean = false,
    var attachedToHand: Boolean = false,
    var enteringHoop: Boolean = false
)

data class GameState(
    val ball: Ball = Ball(),
    val score: Int = 0,
    val attempts: Int = 0,
    val powerLevel: Float = 0f,
    val powerDir: Float = 1f,   // ⭐ +1 lên, -1 xuống
    val isCharging: Boolean = false,
    val basketPosition: Offset = Offset(0.5f, 0.34f),
    val openHandFrames: Int = 0,
    val hasOpenedHandFirst: Boolean = false,
    val noHandFrames: Int = 0    // ⭐ ĐẾM SỐ FRAME KHÔNG THẤY TAY
)

/** =========================
 *  SETTINGS / TUNING
 *  ========================= */
private const val FPS_DT = 1f / 60f

private const val GRAVITY_Y = 0.95f
private const val DRAG_XZ = 0.985f
private const val DRAG_Y = 0.995f
private const val DRAG_Z = 0.985f

private const val GROUND_Y = 0.92f
private const val GROUND_BOUNCE = 0.55f
private const val STOP_EPS = 0.02f

private const val RIM_RADIUS = 0.085f
private const val RIM_Z = 0.95f
private const val BACKBOARD_W = 0.20f
private const val BACKBOARD_H = 0.13f
private const val BACKBOARD_Z = 1.0f
private const val ARC_BASE = 0.85f
private const val ARC_BY_DIST = 0.85f
private const val HAND_BALL_GRAB_RADIUS = 0.12f
private const val SCORE_RADIUS_FACTOR = 0.80f
private const val POWER_SPEED = 1.8f

// ⭐ CHO PHÉP MẤT TAY TỐI ĐA BAO NHIÊU FRAME (30 frame = 0.5 giây)
private const val MAX_NO_HAND_FRAMES = 30

/** =========================
 *  UTILS
 *  ========================= */
fun isPalmFacingCamera(lms: List<NormalizedLandmark>): Boolean {
    val wrist = lms[0]
    val middleMcp = lms[9]

    // Kiểm tra tay thẳng song song màn hình
    val verticalDiff = middleMcp.y() - wrist.y()

    // Nới lỏng threshold để dễ nhận diện
    return verticalDiff < 0.05f
}

fun isHandOverBall(
    handLm: NormalizedLandmark,
    ball: Ball
): Boolean {
    val dx = handLm.x() - ball.x
    val dy = handLm.y() - ball.y
    val dist = sqrt(dx * dx + dy * dy)
    return dist < HAND_BALL_GRAB_RADIUS
}

private val isOppoDevice = Build.MANUFACTURER.equals("OPPO", ignoreCase = true) ||
        Build.BRAND.equals("OPPO", ignoreCase = true)

private fun isFistGesture(landmarks: List<NormalizedLandmark>): Boolean {
    fun dist(a: Int, b: Int): Float {
        val dx = landmarks[a].x() - landmarks[b].x()
        val dy = landmarks[a].y() - landmarks[b].y()
        return sqrt(dx * dx + dy * dy)
    }

    fun distToPointNorm(i: Int, px: Float, py: Float): Float {
        val dx = landmarks[i].x() - px
        val dy = landmarks[i].y() - py
        return sqrt(dx * dx + dy * dy)
    }

    val palmSize = dist(0, 9).coerceAtLeast(1e-4f)
    val palmCx = (landmarks[0].x() + landmarks[9].x()) * 0.5f
    val palmCy = (landmarks[0].y() + landmarks[9].y()) * 0.5f

    // Nới lỏng threshold cho OPPO
    val fistThreshold = if (isOppoDevice) 0.80f else 0.75f
    val palmThreshold = if (isOppoDevice) 1.15f else 1.10f

    val fingersOk = listOf(
        8 to 5, 12 to 9, 16 to 13, 20 to 17
    ).all { (tip, mcp) ->
        val tipToMcp = dist(tip, mcp)
        val tipToPalm = distToPointNorm(tip, palmCx, palmCy)
        tipToMcp < fistThreshold * palmSize && tipToPalm < palmThreshold * palmSize
    }

    val thumbTip = 4
    val thumbToPalm = distToPointNorm(thumbTip, palmCx, palmCy)
    val thumbToIndexMcp = dist(thumbTip, 5)
    val thumbOk = (thumbToPalm < 1.20f * palmSize) || (thumbToIndexMcp < 1.00f * palmSize)

    return fingersOk && thumbOk
}

private fun isPalmGesture(landmarks: List<NormalizedLandmark>): Boolean {
    val fingerTips = listOf(8, 12, 16, 20)
    val fingerBases = listOf(5, 9, 13, 17)

    return fingerTips.zip(fingerBases).all { (tip, base) ->
        landmarks[tip].y() < landmarks[base].y()
    }
}

private fun projectToScreen(x: Float, y: Float, z: Float, w: Float, h: Float): Offset {
    val depth = z.coerceIn(0f, 1f)
    val py = y - depth * 0.15f
    val px = x
    return Offset(w * px, h * py)
}

/** =========================
 *  MAIN COMPOSABLE
 *  ========================= */
@Composable
fun BasketballGame3D(modifier: Modifier = Modifier) {
    val gameState = remember { mutableStateOf(GameState()) }
    var handResult by remember { mutableStateOf<HandLandmarkerResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val trail = remember { mutableStateListOf<BallSample>() }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(16)

            val s = gameState.value
            val b = s.ball

            if (b.shotFinished) {
                gameState.value = s.copy(
                    ball = Ball(
                        x = 0.5f,
                        y = 0.75f,
                        z = 0f
                    )
                )
                trail.clear()
                continue
            }

            if (!b.isFlying) {
                fadeTrail(trail)
                continue
            }

            stepPhysics(
                state = gameState,
                trail = trail
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        CameraPreview(
            onHandDetected = { result ->
                handResult = result
                processHandGesture(
                    result = result,
                    gameState = gameState
                )
            },
            onError = { errorMessage = it }
        )

        handResult?.let { HandLandmarksOverlay(result = it) }

        Basketball3DView(
            state = gameState.value,
            trail = trail,
            modifier = Modifier.fillMaxSize()
        )

        if (gameState.value.isCharging) {
            PowerBar(
                power = gameState.value.powerLevel,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(16.dp)
            )
        }

        GameUI(
            gameState = gameState.value,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp)
        )

        InstructionsCard(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )

        errorMessage?.let { msg ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
            ) { Text(msg) }
        }
    }
}

/** =========================
 *  TRAIL SAMPLE
 *  ========================= */
data class BallSample(
    val x: Float,
    val y: Float,
    val z: Float,
    var life: Float
)

private fun pushTrail(trail: MutableList<BallSample>, ball: Ball) {
    if (trail.size > 28) trail.removeAt(0)
    trail.add(BallSample(ball.x, ball.y, ball.z, 1f))
}

private fun fadeTrail(trail: MutableList<BallSample>) {
    for (i in trail.indices) {
        trail[i] = trail[i].copy(life = (trail[i].life - 0.04f).coerceAtLeast(0f))
    }
    trail.removeAll { it.life <= 0f }
}

/** =========================
 *  PHYSICS STEP
 *  ========================= */
private fun stepPhysics(
    state: MutableState<GameState>,
    trail: MutableList<BallSample>
) {
    val s = state.value
    val b = s.ball

    if (b.attachedToHand) {
        pushTrail(trail, b)
        fadeTrail(trail)
        state.value = s.copy()
        return
    }

    pushTrail(trail, b)
    fadeTrail(trail)

    val bx = s.basketPosition.x
    val by = s.basketPosition.y

    b.vy += GRAVITY_Y * FPS_DT

    b.x += b.vx * FPS_DT
    b.y += b.vy * FPS_DT
    b.z += b.vz * FPS_DT

    b.vx *= DRAG_XZ
    b.vy *= DRAG_Y
    b.vz *= DRAG_Z

    // Backboard collision
    val boardCx = bx
    val boardCy = by - 0.06f
    val halfW = BACKBOARD_W / 2f
    val halfH = BACKBOARD_H / 2f

    val insideBoardXY =
        (b.x in (boardCx - halfW)..(boardCx + halfW)) &&
                (b.y in (boardCy - halfH)..(boardCy + halfH))

    if (insideBoardXY && b.z > BACKBOARD_Z) {
        b.z = BACKBOARD_Z
        b.vz = -abs(b.vz) * 0.7f
        b.vx *= 0.85f
        b.vy *= 0.85f
    }

    // Rim collision
    val dx = b.x - bx
    val dy = b.y - by
    val dist = sqrt(dx * dx + dy * dy)

    val nearRimZ = (b.z in (RIM_Z - 0.10f)..(RIM_Z + 0.12f))
    if (nearRimZ && dist < RIM_RADIUS) {
        val nx = if (dist > 0.0001f) dx / dist else 0f
        val ny = if (dist > 0.0001f) dy / dist else -1f

        b.x = bx + nx * RIM_RADIUS
        b.y = by + ny * RIM_RADIUS

        val vn = b.vx * nx + b.vy * ny
        if (vn < 0f) {
            b.vx = (b.vx - 1.8f * vn * nx) * 0.75f
            b.vy = (b.vy - 1.8f * vn * ny) * 0.75f
            b.vz *= 0.85f
        }
    }

    // Ground bounce
    if (b.y > GROUND_Y) {
        b.y = GROUND_Y
        b.vy = -abs(b.vy) * GROUND_BOUNCE
        b.vx *= 0.80f
        b.vz *= 0.80f

        val speed = sqrt(b.vx * b.vx + b.vy * b.vy + b.vz * b.vz)
        if (speed < STOP_EPS) {
            b.vx = 0f
            b.vy = 0f
            b.vz = 0f
            b.isFlying = false
            b.justThrown = false
            b.shotFinished = true
        }
    }

    // Scoring
    val scoreWindow = (b.z in (RIM_Z - 0.06f)..(RIM_Z + 0.10f))
    // Scoring – CHẠM VÙNG XANH LÀ VÀO
    if (
        dist < (RIM_RADIUS * SCORE_RADIUS_FACTOR) &&
        !b.enteringHoop
    ) {
        state.value = s.copy(
            score = s.score + 1,
            ball = Ball(
                x = b.x,
                y = b.y,
                z = b.z,
                enteringHoop = true
            )
        )

        val nb = state.value.ball
        nb.isFlying = true
        nb.enteringHoop = true
        nb.vx = b.vx * 0.25f
        nb.vy = abs(b.vy) * 0.4f   // luôn rơi xuống cho đẹp
        nb.vz = b.vz * 0.3f
        return
    }

    state.value = s.copy()
}

/** =========================
 *  GESTURE PROCESSING - ĐƠN GIẢN HÓA
 *  ========================= */
private const val TAG_GESTURE = "HB_GESTURE"

fun processHandGesture(
    result: HandLandmarkerResult,
    gameState: MutableState<GameState>
) {
    // ===================== KHÔNG CÓ TAY =====================
    if (result.landmarks().isEmpty()) {
        val s0 = gameState.value
        val newNoHandFrames = s0.noHandFrames + 1

        // Nếu đang charge → tiếp tục tăng power trong thời gian ngắn
        if (s0.isCharging && newNoHandFrames <= MAX_NO_HAND_FRAMES) {
            val dt = FPS_DT

            var newPower = s0.powerLevel + POWER_SPEED * dt * s0.powerDir
            var newDir = s0.powerDir

            if (newPower >= 1f) {
                newPower = 1f
                newDir = -1f
            } else if (newPower <= 0f) {
                newPower = 0f
                newDir = 1f
            }

            gameState.value = s0.copy(
                powerLevel = newPower,
                powerDir = newDir,
                noHandFrames = newNoHandFrames
            )
            return
        }

        // Mất tay quá lâu → reset
        if (newNoHandFrames > MAX_NO_HAND_FRAMES && s0.isCharging) {
            gameState.value = s0.copy(
                isCharging = false,
                powerLevel = 0f,
                powerDir = 1f,
                openHandFrames = 0,
                noHandFrames = 0
            )
        } else if (!s0.isCharging && !s0.ball.isFlying) {
            gameState.value = s0.copy(
                openHandFrames = 0,
                noHandFrames = newNoHandFrames
            )
        }
        return
    }

    // ===================== CÓ TAY =====================
    val lms = result.landmarks()[0]
    val s = gameState.value.copy(noHandFrames = 0)

    // ⭐ GESTURE CHUẨN
    val isFist = isFistGesture(lms)
    val isPalm = isPalmGesture(lms)
    val palmFacingCamera = isPalmFacingCamera(lms)

    Log.d(
        TAG_GESTURE,
        "GESTURE | fist=$isFist palm=$isPalm | charging=${s.isCharging} openFrames=${s.openHandFrames}"
    )

    // =========================================================
    // ===================== CHARGE ============================
    // =========================================================
    val handCenter = lms[9] // middle MCP
    val isHandOnBall = isHandOverBall(handCenter, s.ball)

    val canCharge =
        isFist &&
                palmFacingCamera &&
                !s.ball.isFlying &&
                (s.isCharging || isHandOnBall)

    if (canCharge) {
        val dt = FPS_DT

        var newPower = s.powerLevel + POWER_SPEED * dt * s.powerDir
        var newDir = s.powerDir

        if (newPower >= 1f) {
            newPower = 1f
            newDir = -1f
        } else if (newPower <= 0f) {
            newPower = 0f
            newDir = 1f
        }

        val handX = lms[9].x()
        val fixedY = 0.75f

        val nb = s.ball.apply {
            x = handX.coerceIn(0.15f, 0.85f)
            y = fixedY
            z = 0f
            vx = 0f
            vy = 0f
            vz = 0f
            attachedToHand = true
            isFlying = false
        }

        gameState.value = s.copy(
            ball = nb,
            isCharging = true,
            powerLevel = newPower,
            powerDir = newDir,
            openHandFrames = 0,
            noHandFrames = 0
        )
        return
    }

    // =========================================================
    // ================= ĐẾM FRAME MỞ TAY ======================
    // =========================================================
    val newOpenFrames = if (isPalm) s.openHandFrames + 1 else 0

    gameState.value = s.copy(
        openHandFrames = newOpenFrames,
        noHandFrames = 0
    )

    // =========================================================
    // ====================== THROW ============================
    // =========================================================
    val canThrow =
        s.isCharging &&
                !s.ball.isFlying &&
                isPalm &&
                newOpenFrames >= 6   // ~0.1s giữ tay mở

    if (canThrow) {
        val power = s.powerLevel.coerceIn(0.15f, 1f)

        val handX = s.ball.x
        val handY = s.ball.y

        val bx = s.basketPosition.x
        val by = s.basketPosition.y

        val dx = bx - handX
        val dy = by - handY
        val dist = sqrt(dx * dx + dy * dy).coerceIn(0.05f, 1.2f)

        val arc = (ARC_BASE + ARC_BY_DIST * dist) * power

        val vx = dx * (2.0f + 1.2f * power)
        val vy = -arc
        val vz = (1.4f + 1.6f * power) + dist * 0.8f

        val nb = Ball(
            x = s.ball.x,
            y = s.ball.y,
            z = 0f,
            vx = vx,
            vy = vy,
            vz = vz,
            isFlying = true,
            justThrown = true,
            attachedToHand = false
        )

        gameState.value = s.copy(
            ball = nb,
            isCharging = false,
            powerLevel = 0f,
            powerDir = 1f,
            openHandFrames = 0,
            attempts = s.attempts + 1
        )
    }
}

/** =========================
 *  RENDER (pseudo-3D)
 *  ========================= */

@Composable
fun Basketball3DView(
    state: GameState,
    trail: List<BallSample>,
    modifier: Modifier = Modifier
) {
    val ballBitmap = ImageBitmap.imageResource(id = R.drawable.ball)
    Box(modifier = modifier) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val w = maxWidth.value
            val h = maxHeight.value

            val bx = w * state.basketPosition.x
            val by = h * state.basketPosition.y

            val boardZ = BACKBOARD_Z
            val boardScale = 1f + boardZ * 0.30f
            val boardY = by - boardZ * h * 0.05f

            val hoopImageWidth = (BACKBOARD_W * w * boardScale * 1.5f).dp
            val hoopImageHeight = (BACKBOARD_H * h * boardScale * 2.0f).dp

            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(
                    id = R.drawable.basket_loop  // Thay bằng tên file hình của bạn
                ),
                contentDescription = "Basketball Hoop",
                modifier = Modifier
                    .width(hoopImageWidth)
                    .height(hoopImageHeight)
                    .offset(
                        x = (bx - hoopImageWidth.value / 2).dp,
                        y = (boardY - hoopImageHeight.value / 2).dp
                    ),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit
            )
        }

        // Vẽ đường cong hình trái chuối từ bóng đến rổ
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            val bx = w * state.basketPosition.x
            val by = h * state.basketPosition.y

            // Vẽ đường cong từ bóng đến rổ
            if (state.isCharging && state.ball.attachedToHand) {
                val handX = state.ball.x
                val handY = state.ball.y

                // Gọi hàm vẽ đường cong từ tâm bóng đến tâm rổ
                drawBananaCurve(
                    start = Offset(handX * w, handY * h), // Tọa độ bóng
                    end = Offset(bx, by),  // Tọa độ rổ
                    canvas = this
                )
            }

            // Vẽ trail bóng
            for (t in trail) {
                val p = projectToScreen(t.x, t.y, t.z, w, h)
                val alpha = (0.55f * t.life).coerceIn(0f, 0.55f)
                val scale = 1f + t.z * 1.0f
                val r = (10f * scale)

                drawCircle(
                    color = Color(0xFFFFD180).copy(alpha = alpha),
                    radius = r,
                    center = p
                )
            }

            // Vẽ bóng
            val b = state.ball
            val bp = projectToScreen(b.x, b.y, b.z, w, h)

            val zNorm = b.z.coerceIn(0f, 1f)
            var ballScale = (2.8f * (1f - zNorm).pow(0.4f) + 1.5f)

            if (b.enteringHoop) {
                ballScale *= 0.85f
            }

            val ballRadius = 50f * ballScale
            val depthAlpha = (1f - b.z * 0.25f).coerceIn(0.80f, 1f)

            drawImage(
                image = ballBitmap,

                srcOffset = IntOffset.Zero,
                srcSize = IntSize(
                    ballBitmap.width,
                    ballBitmap.height
                ),

                dstOffset = IntOffset(
                    (bp.x - ballRadius).toInt(),
                    (bp.y - ballRadius).toInt()
                ),
                dstSize = IntSize(
                    (ballRadius * 2).toInt(),
                    (ballRadius * 2).toInt()
                ),

                alpha = depthAlpha,
                filterQuality = FilterQuality.High
            )
        }
    }
}

// Hàm để vẽ đường cong hình trái chuối từ bóng đến rổ
fun drawBananaCurve(
    start: Offset,
    end: Offset,
    canvas: DrawScope
) {
    // Tính toán điểm kiểm soát (control points)
    // Điểm kiểm soát cao hơn để tạo độ cong
    val controlPoint1 = Offset(
        x = start.x + (end.x - start.x) * 0.3f,
        y = start.y - 1000f // Tạo độ cong lên trên
    )
    val controlPoint2 = Offset(
        x = start.x + (end.x - start.x) * 0.75f,
        y = end.y - 500f // Tạo độ cong xuống dưới
    )

    // Tạo đường cong Bézier Cubic (3 điểm kiểm soát)
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(start.x, start.y)  // Điểm bắt đầu (tâm bóng)
        cubicTo(
            controlPoint1.x, controlPoint1.y,  // Điểm kiểm soát 1
            controlPoint2.x, controlPoint2.y,  // Điểm kiểm soát 2
            end.x, end.y                     // Điểm kết thúc (tâm rổ)
        )
    }

    // Vẽ đường cong với nét đứt
    val pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)) // Mảng [10f, 10f] xác định độ dài các đoạn đứt và đoạn trống
    canvas.drawPath(path, color = Color.Red, style = Stroke(width = 15f, pathEffect = pathEffect))
}
