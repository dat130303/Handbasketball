package com.example.handbasketball

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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
fun GameUI(
    gameState: GameState,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(text = "Player 1: ${gameState.scorePlayer1} points (Attempts: ${gameState.attemptsPlayer1}/5)", style = MaterialTheme.typography.bodyLarge)
        Text(text = "Player 2: ${gameState.scorePlayer2} points (Attempts: ${gameState.attemptsPlayer2}/5)", style = MaterialTheme.typography.bodyLarge)

        if (gameState.turn == 1) {
            Text(text = "Player 1's Turn", style = MaterialTheme.typography.bodyLarge)
        } else {
            Text(text = "Player 2's Turn", style = MaterialTheme.typography.bodyLarge)
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
            Text("✊ Nắm tay ở chỗ quả bóng để tích lực", color = Color(0xFF4CAF50), style = MaterialTheme.typography.bodyMedium)
            Text("✋ Mở cả bàn tay để ném", color = Color(0xFFFF9800), style = MaterialTheme.typography.bodyMedium)
            Text("🎯 Di chuyển tay để nhắm", color = Color(0xFF2196F3), style = MaterialTheme.typography.bodyMedium)
        }
    }
}