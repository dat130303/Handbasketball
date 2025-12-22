package com.example.handbasketball

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
    var attachedToHand: Boolean = false, // ⭐ MỚI
    var enteringHoop: Boolean = false   // ⭐ MỚI
)


data class GameState(
    val ball: Ball = Ball(),
    val score: Int = 0,
    val attempts: Int = 0,
    val powerLevel: Float = 0f,
    val isCharging: Boolean = false,
    val basketPosition: Offset = Offset(0.5f, 0.34f)
)

/** =========================
 *  SETTINGS / TUNING
 *  ========================= */
private const val FPS_DT = 1f / 60f

// “Thế giới” normalized 0..1
private const val GRAVITY_Y = 0.95f     // trọng lực theo trục y (world)
private const val DRAG_XZ = 0.985f      // ma sát ngang
private const val DRAG_Y = 0.995f       // ma sát dọc nhẹ
private const val DRAG_Z = 0.985f

private const val GROUND_Y = 0.92f      // mặt sàn (để bóng rơi xuống thấy rõ)
private const val GROUND_BOUNCE = 0.55f // nảy sàn
private const val STOP_EPS = 0.02f      // ngưỡng dừng

// Rổ (world units)
private const val RIM_RADIUS = 0.085f
private const val RIM_Z = 0.95f
// ✅ MỚI (đẹp & cân đối)
private const val BACKBOARD_W = 0.20f
private const val BACKBOARD_H = 0.13f
private const val BACKBOARD_Z = 1.0f

// “Hỗ trợ” để dễ vào & đẹp quỹ đạo
private const val AIM_ASSIST = 0.14f
private const val ARC_BASE = 0.85f
private const val ARC_BY_DIST = 0.85f

/** =========================
 *  UTILS
 *  ========================= */
fun distance(a: NormalizedLandmark, b: NormalizedLandmark): Float {
    val dx = a.x() - b.x()
    val dy = a.y() - b.y()
    return sqrt(dx * dx + dy * dy)
}

fun isFingerExtended(tip: NormalizedLandmark, pip: NormalizedLandmark): Boolean {
    return tip.y() < pip.y()
}

/** =========================
 *  MAIN COMPOSABLE
 *  ========================= */
@Composable
fun BasketballGame3D(modifier: Modifier = Modifier) {
    val gameState = remember { mutableStateOf(GameState()) }
    var handResult by remember { mutableStateOf<HandLandmarkerResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Vệt bóng để nhìn “bóng đi như nào”
    val trail = remember { mutableStateListOf<BallSample>() }

    // Physics loop (luôn chạy, nhưng chỉ update mạnh khi đang flying)
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(16)

            val s = gameState.value
            val b = s.ball

            // ⭐ RESET SAU KHI SHOT HOÀN TẤT
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

        // CAMERA BACKGROUND (bạn đã có sẵn)
        CameraPreview(
            onHandDetected = { result ->
                handResult = result
                processHandGestureThrowOnlyWhenFullyOpen(
                    result = result,
                    gameState = gameState
                )
            },
            onError = { errorMessage = it }
        )

        // LANDMARK DEBUG (nếu muốn)
        handResult?.let { HandLandmarksOverlay(result = it) }

        // GAME OVERLAY (rổ + bóng + trail + impact)
        Basketball3DView(
            state = gameState.value,
            trail = trail,
            modifier = Modifier.fillMaxSize()
        )

        // POWER BAR
        if (gameState.value.isCharging) {
            PowerBar(
                power = gameState.value.powerLevel,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(16.dp)
            )
        }

        // UI
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
    var life: Float // 1 -> 0
)

private fun pushTrail(trail: MutableList<BallSample>, ball: Ball) {
    // giới hạn độ dài để nhẹ máy
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
 *  PHYSICS STEP (thấy rõ bay lên -> chạm -> rơi xuống)
 *  ========================= */
private fun stepPhysics(
    state: MutableState<GameState>,
    trail: MutableList<BallSample>
) {
    val s = state.value
    val b = s.ball

    // 🚫 nếu đang cầm bóng → KHÔNG physics
    if (b.attachedToHand) {
        pushTrail(trail, b)
        fadeTrail(trail)
        state.value = s.copy()
        return
    }

    // lưu trail để “thấy bóng đi như nào”
    pushTrail(trail, b)
    fadeTrail(trail)

    val bx = s.basketPosition.x
    val by = s.basketPosition.y

    // ---------- integrate ----------
    // gravity
    b.vy += GRAVITY_Y * FPS_DT

    // integrate position
    b.x += b.vx * FPS_DT
    b.y += b.vy * FPS_DT
    b.z += b.vz * FPS_DT

    // drag
    b.vx *= DRAG_XZ
    b.vy *= DRAG_Y
    b.vz *= DRAG_Z

    // ---------- collisions ----------
    // 1) Backboard plane (simple)
    // backboard rectangle centered at basket pos, a bit above rim
    val boardCx = bx
    val boardCy = by - 0.06f
    val halfW = BACKBOARD_W / 2f
    val halfH = BACKBOARD_H / 2f

    val insideBoardXY =
        (b.x in (boardCx - halfW)..(boardCx + halfW)) &&
                (b.y in (boardCy - halfH)..(boardCy + halfH))

    if (insideBoardXY && b.z > BACKBOARD_Z) {
        // bounce from board: push forward a bit and invert vz
        b.z = BACKBOARD_Z
        b.vz = -abs(b.vz) * 0.7f
        // also damp x/y a bit
        b.vx *= 0.85f
        b.vy *= 0.85f
    }

    // 2) Rim collision (circle in x-y at rim height z = RIM_Z)
    // When ball is near rim z and near rim center -> bounce
    val dx = b.x - bx
    val dy = b.y - by
    val dist = sqrt(dx * dx + dy * dy)

    val nearRimZ = (b.z in (RIM_Z - 0.10f)..(RIM_Z + 0.12f))
    if (nearRimZ && dist < RIM_RADIUS) {
        // push out along normal
        val nx = if (dist > 0.0001f) dx / dist else 0f
        val ny = if (dist > 0.0001f) dy / dist else -1f

        // push position outward
        b.x = bx + nx * RIM_RADIUS
        b.y = by + ny * RIM_RADIUS

        // reflect velocity (simple)
        val vn = b.vx * nx + b.vy * ny
        if (vn < 0f) {
            b.vx = (b.vx - 1.8f * vn * nx) * 0.75f
            b.vy = (b.vy - 1.8f * vn * ny) * 0.75f
            b.vz *= 0.85f
        }
    }

    // 3) Ground bounce (để thấy “rơi xuống như nào”)
    if (b.y > GROUND_Y) {
        b.y = GROUND_Y
        b.vy = -abs(b.vy) * GROUND_BOUNCE
        b.vx *= 0.80f
        b.vz *= 0.80f

        // nếu đã quá chậm -> dừng hẳn và reset trạng thái bay
        val speed = sqrt(b.vx * b.vx + b.vy * b.vy + b.vz * b.vz)
        if (speed < STOP_EPS) {
            b.vx = 0f
            b.vy = 0f
            b.vz = 0f
            b.isFlying = false
            b.justThrown = false
            b.shotFinished = true   // ⭐
        }
    }

    // ---------- scoring (khoảnh khắc bóng rơi xuyên qua “miệng rổ”) ----------
    // Điều kiện: bóng ở gần tâm rổ, đang rơi xuống, và z nằm vùng rim
    val scoreWindow = (b.z in (RIM_Z - 0.06f)..(RIM_Z + 0.10f))
    if (dist < (RIM_RADIUS * 0.65f) && b.vy > 0f && scoreWindow) {
        // (vy > 0 vì gravity kéo xuống; y tăng là rơi xuống trong hệ toạ độ compose)
        state.value = s.copy(
            score = s.score + 1,
            ball = Ball(
                x = b.x,
                y = b.y,
                z = b.z,
                enteringHoop = true
            ) // giữ vị trí “vào rổ” một khoảnh khắc
        )
        // cho bóng tiếp tục rơi để thấy “rơi xuống”
        val nb = state.value.ball
        nb.isFlying = true
        nb.enteringHoop = true
        nb.vx = b.vx * 0.25f
        nb.vy = b.vy * 0.6f
        nb.vz = b.vz * 0.3f
        return
    }

    // commit
    state.value = s.copy()
}

/** =========================
 *  GESTURE: nắm tay charge, mở cả bàn tay mới ném
 *  (ném tạo cảm giác “rời tay” + có cung + có depth)
 *  ========================= */
fun processHandGestureThrowOnlyWhenFullyOpen(
    result: HandLandmarkerResult,
    gameState: MutableState<GameState>
) {
    if (result.landmarks().isEmpty()) {
        if (gameState.value.isCharging) {
            gameState.value = gameState.value.copy(isCharging = false, powerLevel = 0f)
        }
        return
    }

    val lms = result.landmarks()[0]
    val s = gameState.value

    val thumbOpen = distance(lms[4], lms[2]) > 0.08f
    val indexOpen = isFingerExtended(lms[8], lms[6])
    val middleOpen = isFingerExtended(lms[12], lms[10])
    val ringOpen = isFingerExtended(lms[16], lms[14])
    val pinkyOpen = isFingerExtended(lms[20], lms[18])

    val isFistClosed = !indexOpen && !middleOpen && !ringOpen && !pinkyOpen
    val isHandFullyOpen = thumbOpen && indexOpen && middleOpen && ringOpen && pinkyOpen

    // CHARGE
    if (isFistClosed && !s.ball.isFlying) {

        val handX = lms[9].x()   // tâm bàn tay
        val fixedY = 0.75f       // giữ nguyên Y ban đầu

        val newPower = (s.powerLevel + 0.02f).coerceAtMost(1f)

        val nb = s.ball.apply {
            x = handX.coerceIn(0.15f, 0.85f) // tránh ra ngoài màn hình
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
            powerLevel = newPower
        )
        return
    }


    // THROW
    if (isHandFullyOpen && s.isCharging && !s.ball.isFlying) {
        val power = s.powerLevel.coerceIn(0.15f, 1f)

        val handX = lms[9].x()
        val handY = lms[9].y()

        val bx = s.basketPosition.x
        val by = s.basketPosition.y

        val dx = (bx - handX)
        val dy = (by - handY)
        val dist = sqrt(dx * dx + dy * dy).coerceIn(0.05f, 1.2f)

        // Aim assist mềm
        val ax = dx * (1f + AIM_ASSIST)
        val ay = dy * (1f + AIM_ASSIST)

        // Tạo “cung ném” rõ ràng: lực lên (vy âm) lớn khi xa
        // (y tăng là đi xuống, nên để bay lên phải vy âm)
        val arc = (ARC_BASE + ARC_BY_DIST * dist) * power

        // Vận tốc world units / second
        val vx = ax * (2.0f + 1.2f * power)
        val vy = -arc
        val vz = (1.4f + 1.6f * power) + dist * 0.8f

        val nb = Ball(
            x = s.ball.x,       // 🔥 dùng vị trí đang cầm
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
            attempts = s.attempts + 1
        )
    }
}

/** =========================
 *  RENDER (pseudo-3D): perspective + depth alpha + trail
 *  ========================= */
@Composable
fun Basketball3DView(
    state: GameState,
    trail: List<BallSample>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val bx = w * state.basketPosition.x
        val by = h * state.basketPosition.y

        // ----- draw backboard (có depth) -----
        val boardZ = BACKBOARD_Z
        val boardScale = 1f + boardZ * 0.30f
        val boardY = by - boardZ * h * 0.05f

        val boardWpx = (BACKBOARD_W * w) * boardScale
        val boardHpx = (BACKBOARD_H * h) * boardScale

        drawRect(
            color = Color(0xFF8B4513).copy(alpha = 0.95f),
            topLeft = Offset(bx - boardWpx / 2f, boardY - boardHpx / 2f),
            size = androidx.compose.ui.geometry.Size(boardWpx, boardHpx)
        )
        drawRect(
            color = Color.White.copy(alpha = 0.95f),
            topLeft = Offset(bx - boardWpx / 2f + 10, boardY - boardHpx / 2f + 10),
            size = androidx.compose.ui.geometry.Size(boardWpx - 20, boardHpx - 20),
            style = Stroke(width = 4f)
        )

        // ----- draw rim (có depth) -----
        val rimScale = 1f + RIM_Z * 0.65f
        val rimY = by - RIM_Z * h * 0.08f
        val rimW = 0.16f * w * rimScale
        val rimH = 0.04f * h

        drawOval(
            color = Color(0xFFFF6347),
            topLeft = Offset(bx - rimW / 2f, rimY - rimH / 2f),
            size = androidx.compose.ui.geometry.Size(rimW, rimH),
            style = Stroke(width = 10f)
        )

        // net (đơn giản nhưng tạo chiều sâu)
        for (i in 0..8) {
            val ang = i * (Math.PI * 2.0 / 9.0)
            val nx = cos(ang).toFloat()
            val ny = sin(ang).toFloat()

            val sx = bx + nx * (rimW * 0.40f)
            val sy = rimY + ny * (rimH * 0.40f)
            val ex = sx
            val netSwing =
                if (state.ball.enteringHoop) sin(System.currentTimeMillis() * 0.02).toFloat() * 8f
                else 0f

            val ey = sy + 0.10f * h + netSwing


            drawLine(
                color = Color.White.copy(alpha = 0.55f),
                start = Offset(sx, sy),
                end = Offset(ex, ey),
                strokeWidth = 2f
            )
        }

        // ----- trail: để thấy “bóng đi như nào” -----
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

        // ----- ball -----
        val b = state.ball
        val bp = projectToScreen(b.x, b.y, b.z, w, h)

        // shadow on ground (rất quan trọng cho cảm giác rơi)
        val shadowP = Offset(w * b.x, h * GROUND_Y)
        val shadowAlpha = (0.30f * (1f - b.z * 0.9f)).coerceIn(0.05f, 0.30f)
        val shadowRadius = (30f * (1f - b.z * 0.7f)).coerceIn(10f, 30f)

        drawCircle(
            color = Color.Black.copy(alpha = shadowAlpha),
            radius = shadowRadius,
            center = shadowP
        )

        val zNorm = b.z.coerceIn(0f, 1f)

        // 🔥 SCALE MỚI
        var ballScale =
            (2.8f * (1f - zNorm).pow(1.15f) + 0.9f)

        if (b.enteringHoop) {
            ballScale *= 0.85f   // bóng co lại khi chui qua rổ
        }


        // 🔥 BÁN KÍNH GỐC LỚN HƠN
        val ballRadius = 30f * ballScale
        val depthAlpha = (1f - b.z * 0.25f).coerceIn(0.70f, 1f)

        // khoảnh khắc “rời tay” (glow 1-2 frame)
        if (b.justThrown) {
            drawCircle(
                color = Color(0xFFFFFFFF).copy(alpha = 0.18f),
                radius = ballRadius * 1.7f,
                center = bp
            )
        }

        drawCircle(
            color = Color(0xFFFF8C00).copy(alpha = depthAlpha),
            radius = ballRadius,
            center = bp
        )

        // lines trên bóng
        drawCircle(
            color = Color(0xFFD2691E).copy(alpha = depthAlpha),
            radius = ballRadius,
            center = bp,
            style = Stroke(width = 2f)
        )
        drawLine(
            color = Color(0xFFD2691E).copy(alpha = depthAlpha),
            start = Offset(bp.x - ballRadius, bp.y),
            end = Offset(bp.x + ballRadius, bp.y),
            strokeWidth = 2f
        )
        drawLine(
            color = Color(0xFFD2691E).copy(alpha = depthAlpha),
            start = Offset(bp.x, bp.y - ballRadius),
            end = Offset(bp.x, bp.y + ballRadius),
            strokeWidth = 2f
        )
    }
}

/**
 * Projection: world (0..1) + z -> screen pixel
 * - y perspective: z càng lớn thì “bay lên” nhiều hơn
 */
private fun projectToScreen(x: Float, y: Float, z: Float, w: Float, h: Float): Offset {
    val depth = z.coerceIn(0f, 1f)
    val py = y - depth * 0.15f   // chỉ “nâng” lên, không kéo về rổ   // y là cao thấp THUẦN
    val px = x
    return Offset(w * px, h * py)
}


/** =========================
 *  UI (giữ như bạn)
 *  ========================= */
@Composable
fun PowerBar(power: Float, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.width(60.dp).height(300.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            Text("POWER", color = Color.White, style = MaterialTheme.typography.labelSmall)
            Spacer(modifier = Modifier.height(8.dp))
            Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
                val hh = size.height
                val barH = hh * power
                drawRect(
                    color = Color.Gray.copy(alpha = 0.3f),
                    topLeft = Offset(0f, 0f),
                    size = androidx.compose.ui.geometry.Size(size.width, hh)
                )
                val barColor = when {
                    power < 0.3f -> Color.Yellow
                    power < 0.7f -> Color(0xFFFFA500)
                    else -> Color.Red
                }
                drawRect(
                    color = barColor,
                    topLeft = Offset(0f, hh - barH),
                    size = androidx.compose.ui.geometry.Size(size.width, barH)
                )
            }
            Text("${(power * 100).toInt()}%", color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun GameUI(gameState: GameState, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SCORE", color = Color.White, style = MaterialTheme.typography.labelMedium)
                Text("${gameState.score}", color = Color(0xFF4CAF50), style = MaterialTheme.typography.headlineLarge)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("ATTEMPTS", color = Color.White, style = MaterialTheme.typography.labelMedium)
                Text("${gameState.attempts}", color = Color.White, style = MaterialTheme.typography.headlineLarge)
            }
            if (gameState.attempts > 0) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ACCURACY", color = Color.White, style = MaterialTheme.typography.labelMedium)
                    Text(
                        "${(gameState.score.toFloat() / gameState.attempts * 100).toInt()}%",
                        color = Color(0xFFFFD700),
                        style = MaterialTheme.typography.headlineMedium
                    )
                }
            }
        }
    }
}

@Composable
fun InstructionsCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.8f))
    ) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🎮 HOW TO PLAY", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("✊ Nắm tay lại để tích lực", color = Color(0xFF4CAF50), style = MaterialTheme.typography.bodyMedium)
            Text("✋ Mở cả bàn tay để ném", color = Color(0xFFFF9800), style = MaterialTheme.typography.bodyMedium)
            Text("🎯 Di chuyển tay để nhắm", color = Color(0xFF2196F3), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
