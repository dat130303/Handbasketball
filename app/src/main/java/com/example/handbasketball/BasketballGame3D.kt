package com.example.handbasketball

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
    val hasOpenedHandFirst: Boolean = false
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
private const val HAND_BALL_GRAB_RADIUS = 0.10f
private const val SCORE_RADIUS_FACTOR = 0.80f   // ⬅️ RẤT QUAN TRỌNG
private const val AIM_ASSIST = 0.30f             // ⬅️ TAY LỆCH VẪN VÀO
private const val POWER_SPEED = 3.5f // 1.2 = ~0.8s full bar
/** =========================
 *  UTILS
 *  ========================= */
fun isFingerReallyExtended(
    tip: NormalizedLandmark,
    pip: NormalizedLandmark,
    mcp: NormalizedLandmark
): Boolean {
    return tip.y() < pip.y() && pip.y() < mcp.y()
}

fun isPalmFacingCamera(lms: List<NormalizedLandmark>): Boolean {
    val wrist = lms[0]
    val middleMcp = lms[9]

    // Kiểm tra tay thẳng song song màn hình
    val verticalDiff = middleMcp.y() - wrist.y()

    // Nới lỏng threshold để dễ nhận diện
    return verticalDiff < 0.05f
}

fun distance(a: NormalizedLandmark, b: NormalizedLandmark): Float {
    val dx = a.x() - b.x()
    val dy = a.y() - b.y()
    return sqrt(dx * dx + dy * dy)
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

        // Reset khi không có tay và không đang bay
        if (!s0.isCharging && !s0.ball.isFlying) {
            gameState.value = s0.copy(openHandFrames = 0)
        }

        return
    }

    val lms = result.landmarks()[0]
    val s = gameState.value

    // ===================== NHẬN DIỆN NGÓN =====================
    val thumbOpen  = distance(lms[4], lms[2]) > 0.14f
    val indexOpen  = isFingerReallyExtended(lms[8],  lms[6],  lms[5])
    val middleOpen = isFingerReallyExtended(lms[12], lms[10], lms[9])
    val ringOpen   = isFingerReallyExtended(lms[16], lms[14], lms[13])
    val pinkyOpen  = isFingerReallyExtended(lms[20], lms[18], lms[17])

    val palmFacingCamera = isPalmFacingCamera(lms)

    // ===================== ĐẾM NGÓN MỞ =====================
    val openFingerCount = listOf(
        thumbOpen, indexOpen, middleOpen, ringOpen, pinkyOpen
    ).count { it }

    // ===================== TRẠNG THÁI TAY =====================
    // ⭐ NẮM TAY: tất cả ngón đều không mở
    val isFistClosed =
        !thumbOpen && !indexOpen && !middleOpen && !ringOpen && !pinkyOpen

    // ⭐ MỞ TAY ĐẦY ĐỦ: CẢ 5 NGÓN ĐỀU MỞ + SONG SONG MÀN HÌNH
    val isFullHandOpen =
        thumbOpen && indexOpen && middleOpen && ringOpen && pinkyOpen && palmFacingCamera

    // ⭐ MỞ TAY ĐỂ NÉM: ít nhất 3 ngón mở
    val isHandOpenForThrow =
        openFingerCount >= 3 && (indexOpen || middleOpen)

    // ===================== LOG =====================
    Log.d(
        TAG_GESTURE,
        buildString {
            append("DETECT | ")
            append("fist=$isFistClosed, open=$isHandOpenForThrow ")
            append("(thumb=$thumbOpen, idx=$indexOpen, mid=$middleOpen, ring=$ringOpen, pinky=$pinkyOpen) ")
            append("| palmStraight=$palmFacingCamera ")
            append("| isCharging=${s.isCharging} power=${"%.2f".format(s.powerLevel)} ")
            append("| openFrames=${s.openHandFrames}")
        }
    )

    // =========================================================
    // ============ CHARGE: NẮM TAY + SONG SONG ===============
    // =========================================================
    val handCenter = lms[9] // middle MCP
    val isHandOnBall = isHandOverBall(handCenter, s.ball)

    val canCharge =
        isFistClosed &&             // nắm tay
                palmFacingCamera &&         // song song camera
                !s.ball.isFlying &&         // bóng chưa bay
                isHandOnBall                // ⭐ PHẢI CHẠM BÓNG


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

        if (!s.isCharging) {
            Log.i(TAG_GESTURE, "⭐ CHARGE_START (PING-PONG)")
        }

        gameState.value = s.copy(
            ball = nb,
            isCharging = true,
            powerLevel = newPower,
            powerDir = newDir,
            openHandFrames = 0
        )
        return
    } else {
        // Log lý do không charge
        if (!s.ball.isFlying && !s.isCharging) {
            Log.v(
                TAG_GESTURE,
                "CHARGE_BLOCK ❌ | fist=$isFistClosed, straight=$palmFacingCamera"
            )
        }
    }

    // =========================================================
    // =============== ĐẾM FRAME MỞ TAY ========================
    // =========================================================
    val newOpenFrames = if (isHandOpenForThrow) s.openHandFrames + 1 else 0

    gameState.value = s.copy(openHandFrames = newOpenFrames)

    // =========================================================
    // ============= THROW: MỞ TAY SAU KHI CHARGE ==============
    // =========================================================
    val canThrow =
        s.isCharging &&
                !s.ball.isFlying &&
                (newOpenFrames >= 4 || isHandOpenForThrow)

    if (canThrow) {
        val power = s.powerLevel.coerceIn(0.15f, 1f)

        val handX = lms[9].x()
        val handY = lms[9].y()

        val bx = s.basketPosition.x
        val by = s.basketPosition.y

        val dx = bx - handX
        val dy = by - handY
        val dist = sqrt(dx * dx + dy * dy).coerceIn(0.05f, 1.2f)

        val ax = dx * (1f + AIM_ASSIST)
        val ay = dy * (1f + AIM_ASSIST)

        val arc = (ARC_BASE + ARC_BY_DIST * dist) * power

        val vx = ax * (2.0f + 1.2f * power)
        val vy = -arc
        val vz = (1.4f + 1.6f * power) + dist * 0.8f

        Log.w(
            TAG_GESTURE,
            "🚀 THROW ✅ power=${"%.2f".format(power)} " +
                    "vel(vx=${"%.2f".format(vx)}, vy=${"%.2f".format(vy)}, vz=${"%.2f".format(vz)})"
        )

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
            powerDir = 1f,   // ⭐ reset lại hướng
            openHandFrames = 0,
            hasOpenedHandFirst = false,
            attempts = s.attempts + 1
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDashedLine(
    color: Color,
    start: Offset,
    end: Offset,
    dashLength: Float,
    gapLength: Float,
    strokeWidth: Float
) {
    val totalLength = (end - start).getDistance()
    val direction = (end - start) / totalLength

    var currentDistance = 0f
    while (currentDistance < totalLength) {
        val dashStart = start + direction * currentDistance
        val dashEnd = start + direction * min(currentDistance + dashLength, totalLength)

        drawLine(
            color = color,
            start = dashStart,
            end = dashEnd,
            strokeWidth = strokeWidth
        )
        currentDistance += dashLength + gapLength
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
        // ===== HÌNH ẢNH RỔ + BẢNG =====
        // Tính toán vị trí và kích thước dựa trên depth
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

            // Hiển thị hình rổ + bảng
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(
                    id = R.drawable.basket_loop  // ⭐ Thay bằng tên file hình của bạn
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

        // ===== CANVAS CHO BÓNG + TRAIL + AIM GUIDE =====
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            val bx = w * state.basketPosition.x
            val by = h * state.basketPosition.y

            // Aim guide - PARABOL THỰC TẾ
            if (state.isCharging && state.ball.attachedToHand) {
                val power = state.powerLevel.coerceIn(0.15f, 1f)

                val handX = state.ball.x
                val handY = state.ball.y

                val basketX = state.basketPosition.x
                val basketY = state.basketPosition.y

                // Tính toán vận tốc giống như khi ném thật
                val dx = basketX - handX
                val dy = basketY - handY
                val dist = sqrt(dx * dx + dy * dy).coerceIn(0.05f, 1.2f)

                val ax = dx * (1f + AIM_ASSIST)
                val ay = dy * (1f + AIM_ASSIST)
                val arc = (ARC_BASE + ARC_BY_DIST * dist) * power

                val vx = ax * (2.0f + 1.2f * power)
                val vy = -arc
                val vz = (1.4f + 1.6f * power) + dist * 0.8f

                // Vẽ đường parabol
                val numPoints = 30
                var prevPoint: Offset? = null

                for (i in 0..numPoints) {
                    val t = i / numPoints.toFloat()
                    val simTime = t * 1.2f // thời gian mô phỏng

                    // Tính vị trí theo physics thật
                    val simX = handX + vx * simTime
                    val simY = handY + vy * simTime + GRAVITY_Y * simTime * simTime * 0.5f
                    val simZ = vz * simTime

                    // Dừng vẽ nếu chạm sàn hoặc quá xa
                    if (simY > GROUND_Y || simZ > 1.2f) break

                    val point = projectToScreen(simX, simY, simZ, w, h)

                    if (prevPoint != null) {
                        // Độ mờ dần theo độ xa
                        val alpha = (0.6f * (1f - t * 0.5f)).coerceIn(0.2f, 0.6f)

                        drawLine(
                            color = Color.White.copy(alpha = alpha),
                            start = prevPoint,
                            end = point,
                            strokeWidth = 3f
                        )
                    }

                    prevPoint = point
                }

                // ===== VÙNG TÂM RỔ (SCORE ZONE) =====
                val rimScreenY = by - RIM_Z * h * 0.075f
                val rimPos = Offset(bx, rimScreenY)

// bán kính SCORE = RIM_RADIUS * 0.65f (chuẩn physics)
                val scoreRadiusPx = (RIM_RADIUS * SCORE_RADIUS_FACTOR) * w

// Vùng score (xanh dương)
                drawCircle(
                    color = Color(0xFF2196F3).copy(alpha = 0.35f), // xanh dương
                    radius = scoreRadiusPx,
                    center = rimPos
                )

// Viền vùng score
                drawCircle(
                    color = Color(0xFF2196F3).copy(alpha = 0.85f),
                    radius = scoreRadiusPx,
                    center = rimPos,
                    style = Stroke(width = 3f)
                )

            }

            // Trail
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

            // ===== BALL IMAGE – CANVAS SYNC 100% =====
            val b = state.ball
            val bp = projectToScreen(b.x, b.y, b.z, w, h)

// ===== LOGIC SCALE CŨ (GIỮ NGUYÊN) =====
            val zNorm = b.z.coerceIn(0f, 1f)
            var ballScale =
                (2.8f * (1f - zNorm).pow(0.4f) + 1.5f)

            if (b.enteringHoop) {
                ballScale *= 0.85f
            }

            val ballRadius = 50f * ballScale
            val depthAlpha = (1f - b.z * 0.25f).coerceIn(0.80f, 1f)


// ===== VẼ ẢNH BÓNG =====
            drawImage(
                image = ballBitmap,

                // lấy toàn bộ ảnh gốc
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(
                    ballBitmap.width,
                    ballBitmap.height
                ),

                // vẽ ra màn hình theo physics
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
private fun projectToScreen(x: Float, y: Float, z: Float, w: Float, h: Float): Offset {
    val depth = z.coerceIn(0f, 1f)
    val py = y - depth * 0.15f
    val px = x
    return Offset(w * px, h * py)
}