package com.ezeelabs.jarvis

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

@Composable
fun InAppCallScreen(vm: JarvisVM, onEnd: () -> Unit) {
    val context = LocalContext.current
    var voiceState by remember { mutableStateOf("permission") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var secondsLeft by remember { mutableIntStateOf(180) }
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    var muted by remember { mutableStateOf(false) }
    var speakerOn by remember { mutableStateOf(true) }

    val session = remember(vm) {
        vm.createVoiceSession(
            onState = { next -> voiceState = next },
            onError = { message -> errorText = message; voiceState = "error" }
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        if (!granted) {
            voiceState = "permission_denied"
            errorText = "Microphone permission is needed for a voice conversation."
        } else {
            voiceState = "countdown"
            errorText = null
        }
    }

    DisposableEffect(session) {
        onDispose { session.close() }
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(permissionGranted) {
        if (permissionGranted) {
            voiceState = "countdown"
            while (secondsLeft > 0) {
                delay(1000)
                secondsLeft -= 1
            }
            voiceState = "connecting"
            session.connect()
        }
    }

    LaunchedEffect(voiceState) {
        if (voiceState in listOf("listening", "speaking", "muted")) {
            while (voiceState in listOf("listening", "speaking", "muted")) {
                delay(1000)
                elapsedSeconds += 1
            }
        }
    }

    val transition = rememberInfiniteTransition(label = "jarvis_call_pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.88f,
        targetValue = if (voiceState == "speaking") 1.14f else 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (voiceState == "speaking") 700 else 1700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val halo by transition.animateFloat(
        initialValue = 0.16f,
        targetValue = if (voiceState == "speaking") 0.68f else 0.38f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "halo"
    )

    val statusLabel = when (voiceState) {
        "permission" -> "MICROPHONE PERMISSION"
        "permission_denied" -> "MICROPHONE ACCESS NEEDED"
        "countdown" -> "VOICE SESSION SCHEDULED"
        "connecting" -> "CONNECTING TO JARVIS"
        "connected" -> "CONNECTED"
        "listening" -> "LISTENING"
        "speaking" -> "JARVIS IS SPEAKING"
        "muted" -> "MICROPHONE MUTED"
        "ending" -> "ENDING SESSION"
        "ended" -> "SESSION ENDED"
        "error" -> "CONNECTION ISSUE"
        else -> "JARVIS"
    }

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
                Text(statusLabel, color = Color(0xFF8D9AAA), fontSize = 10.sp, letterSpacing = 2.sp)
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

                when (voiceState) {
                    "countdown" -> {
                        Text("Starting in", color = Color(0xFF9AA8B8), fontSize = 14.sp)
                        Text(String.format("%02d:%02d", secondsLeft / 60, secondsLeft % 60), color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Light)
                        Text("Keep the app open while the session starts", color = Color(0xFF748397), fontSize = 12.sp)
                    }
                    "listening", "speaking", "muted", "connected" -> {
                        Text(String.format("%02d:%02d", elapsedSeconds / 60, elapsedSeconds % 60), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Light)
                        Text(if (voiceState == "speaking") "Audio from JARVIS" else "In-app voice session", color = Color(0xFF8DDCFF), fontSize = 13.sp)
                    }
                    "permission_denied" -> {
                        Text("Allow microphone access to talk to JARVIS.", color = Color(0xFF9AA8B8), fontSize = 13.sp)
                        OutlinedButton(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Allow microphone") }
                    }
                    "connecting" -> Text("Opening secure voice connection…", color = Color(0xFF8DDCFF), fontSize = 13.sp)
                    "ended" -> Text("This session has ended.", color = Color(0xFF9AA8B8), fontSize = 13.sp)
                    "error" -> Text(errorText ?: "Please check your server and try again.", color = Color(0xFFFF8A8A), fontSize = 12.sp)
                    else -> Text("Preparing microphone…", color = Color(0xFF9AA8B8), fontSize = 13.sp)
                }
                if (errorText != null && voiceState != "error" && voiceState != "permission_denied") {
                    Spacer(Modifier.height(6.dp))
                    Text(errorText!!, color = Color(0xFFFF8A8A), fontSize = 11.sp)
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (voiceState in listOf("listening", "speaking", "muted", "connected")) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(onClick = {
                                muted = !muted
                                session.setMuted(muted)
                                voiceState = if (muted) "muted" else "listening"
                            }) {
                                Icon(if (muted) Icons.Filled.MicOff else Icons.Filled.Mic, contentDescription = "Toggle microphone", tint = Color.White)
                            }
                            Text(if (muted) "Muted" else "Mute", color = Color(0xFF9AA8B8), fontSize = 11.sp)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(onClick = {
                                speakerOn = !speakerOn
                                session.setSpeakerEnabled(speakerOn)
                            }) {
                                Icon(if (speakerOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff, contentDescription = "Toggle speaker", tint = Color.White)
                            }
                            Text(if (speakerOn) "Speaker" else "Earpiece", color = Color(0xFF9AA8B8), fontSize = 11.sp)
                        }
                    }
                }
                Button(
                    onClick = { session.end(); onEnd() },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC83E4D))
                ) {
                    Icon(Icons.Filled.CallEnd, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("End session", color = Color.White)
                }
                Text("EzeeLabs • JARVIS V1.0", color = Color(0xFF536273), fontSize = 10.sp, letterSpacing = 1.sp)
            }
        }
    }
}
