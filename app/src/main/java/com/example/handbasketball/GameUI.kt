package com.example.handbasketball

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Button
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
fun GameUI(gameState: GameState, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f))
    ) {
        Column(
            modifier = Modifier.wrapContentSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Text("Player 1 Score: ${gameState.scorePlayer1}", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.weight(1f))
                Text("Player 2 Score: ${gameState.scorePlayer2}", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Log.d("GameUI", "Player 1 Score: ${gameState.scorePlayer1}, Player 2 Score: ${gameState.scorePlayer2}, ${gameState.isPlayer1Turn}")
            }

            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Text("P1 Round: ${gameState.attemptsPlayer1}/5", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.weight(1f))
                Text("P2 Round: ${gameState.attemptsPlayer2}/5", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }

            // Hiển thị lượt chơi
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (gameState.isPlayer1Turn) {
                    Text("Player 1's Turn", color = Color.White, style = MaterialTheme.typography.titleMedium)
                } else {
                    Text("Player 2's Turn", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }

            // Hiển thị kết quả nếu trò chơi kết thúc
            if (gameState.gameOver) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Game Over! ${gameState.winner} Wins", color = Color.White, style = MaterialTheme.typography.titleMedium)
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
            Text("✊ Nắm tay ở chỗ quả bóng để tích lực", color = Color(0xFF4CAF50), style = MaterialTheme.typography.bodyMedium)
            Text("✋ Mở cả bàn tay để ném", color = Color(0xFFFF9800), style = MaterialTheme.typography.bodyMedium)
            Text("🎯 Di chuyển tay để nhắm", color = Color(0xFF2196F3), style = MaterialTheme.typography.bodyMedium)
        }
    }
}