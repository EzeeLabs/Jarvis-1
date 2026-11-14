package com.ezeelabs.jarvis

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * OnePlus-inspired JARVIS in-app call screen.
 * This first UI milestone intentionally does not claim an audio connection:
 * the realtime in-app audio transport is implemented in the next milestone.
 */
@Composable
fun InAppCallScreen(onEnd: () -> Unit) {
    var secondsLeft by remember { mutableIntStateOf(180) }
    var countdownFinished by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft -= 1
        }
        countdownFinished = 1
    }

    val transition = rememberInfiniteTransition(label = "jarvis_call_pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(1700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val halo by transition.animateFloat(
        initialValue = 0.18f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(1700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "halo"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF080B10))
            .padding(horizontal = 28.dp, vertical = 34.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("JARVIS", color = Color.White, fontSize = 17.sp, letterSpacing = 5.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (countdownFinished == 0) "VOICE SESSION SCHEDULED" else "YOUR AI ASSISTANT",
                    color = Color(0xFF8D9AAA), fontSize = 10.sp, letterSpacing = 2.sp
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(250.dp)) {
                    Canvas(Modifier.fillMaxSize()) {
                        val radius = size.minDimension * 0.37f
                        drawCircle(Color(0xFF3CB9F2).copy(alpha = halo), radius = radius * pulse, style = Stroke(width = 2.dp.toPx()))
                        drawCircle(Color(0xFF3CB9F2).copy(alpha = 0.10f), radius = radius * 0.82f)
                        drawCircle(Color(0xFF3CB9F2).copy(alpha = 0.22f), radius = radius * 0.62f, style = Stroke(width = 1.dp.toPx()))
                    }
                    Box(
                        modifier = Modifier
                            .size(142.dp * pulse)
                            .clip(CircleShape)
                            .background(Color(0xFF142A3A)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("J", color = Color(0xFF8DDCFF), fontSize = 76.sp, fontWeight = FontWeight.ExtraLight)
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text("JARVIS", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Light, letterSpacing = 2.sp)
                Spacer(Modifier.height(8.dp))
                if (countdownFinished == 0) {
                    Text("Starting in", color = Color(0xFF9AA8B8), fontSize = 14.sp)
                    Text(
                        String.format("%02d:%02d", secondsLeft / 60, secondsLeft % 60),
                        color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Light
                    )
                    Text("Keep the app open for this first test", color = Color(0xFF748397), fontSize = 12.sp)
                } else {
                    Text("Voice connection is the next build step", color = Color(0xFF8DDCFF), fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("The call screen is ready; live microphone and AI audio are not connected yet.", color = Color(0xFF748397), fontSize = 12.sp)
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedButton(onClick = onEnd, modifier = Modifier.fillMaxWidth()) {
                    Text("End session", color = Color(0xFFFF7373))
                }
                Text("EzeeLabs • JARVIS V1.0", color = Color(0xFF536273), fontSize = 10.sp, letterSpacing = 1.sp)
            }
        }
    }
}
