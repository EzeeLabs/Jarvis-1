@file:OptIn(ExperimentalMaterial3Api::class)

package com.ezeelabs.jarvis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.Instant

@Composable
fun App(vm: JarvisVM) {
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.error) { vm.error?.let { snack.showSnackbar(it); vm.clearError() } }
    var tab by rememberSaveable { mutableStateOf(0) }
    var showCallScreen by rememberSaveable { mutableStateOf(false) }
    if (showCallScreen) {
        InAppCallScreen(vm = vm, onEnd = { showCallScreen = false })
        return
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            if (vm.loggedIn) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf("Home" to Icons.Filled.Home, "Business" to Icons.Filled.BarChart, "Memory" to Icons.Filled.Memory,
                    "Calls" to Icons.Filled.Call, "Settings" to Icons.Filled.Settings).forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icon, label) }, label = { Text(label, fontSize = 11.sp) })
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (!vm.loggedIn) LoginScreen(vm) else when (tab) {
                0 -> Dashboard(vm, onStartCall = { showCallScreen = true })
                1 -> BusinessTab(vm)
                2 -> MemoryTab(vm)
                3 -> CallsTab(vm)
                else -> SettingsTab(vm)
            }
        }
    }
}

@Composable
fun LoginScreen(vm: JarvisVM) {
    var url by remember { mutableStateOf(vm.baseUrl) }
    var pw by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("JARVIS", fontSize = 40.sp, fontWeight = FontWeight.Light, color = Accent)
        Text("EzeeLabs personal assistant", color = Muted)
        Column(Modifier.padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("Server URL (https://…)", url, { url = it })
            androidx.compose.material3.OutlinedTextField(
                value = pw, onValueChange = { pw = it }, label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = { vm.login(url, pw) }, enabled = !vm.busy && url.startsWith("https://") && pw.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                if (vm.busy) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp) else Text("Sign in")
            }
            Text("Only https:// servers are accepted.", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
fun Dashboard(vm: JarvisVM, onStartCall: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadAnalytics("today") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("JARVIS", fontSize = 30.sp, fontWeight = FontWeight.Light, color = Accent)
            Text(if (vm.online) "● Connected" else "● Offline", color = if (vm.online) Good else Bad)
        }
        CallCard(vm, onStartCall)

        vm.summary?.let { s ->
            Panel {
                Text("Today", color = Muted)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("Revenue", inr(s.optDouble("revenue")))
                    Stat("Expenses", inr(s.optDouble("expenses")))
                    Stat("Net", inr(s.optDouble("net_recorded_earnings")), if (s.optDouble("net_recorded_earnings") >= 0) Good else Bad)
                }
                Text("${s.optInt("completed_orders")} completed · ${s.optInt("pending_orders")} pending orders", color = Muted)
            }
        }
    }
}

@Composable
fun CallCard(vm: JarvisVM, onStartCall: () -> Unit) {
    Panel {
        Text("JARVIS voice assistant", color = Muted)
        Text("Talk to JARVIS through the app — no normal phone call required.", color = MaterialTheme.colorScheme.onSurface)
        Button(
            onClick = onStartCall,
            enabled = vm.online,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("Start voice session · 3 min", fontSize = 16.sp)
        }
        Text(
            if (vm.online) "The three-minute countdown and OnePlus-inspired call screen are ready for UI testing."
            else "Connect to your JARVIS server to start. The in-app voice engine is still being built.",
            color = Muted, fontSize = 12.sp
        )
    }
}

private fun isRecent(iso: String): Boolean = try { Instant.now().epochSecond - Instant.parse(iso).epochSecond < 1800 } catch (e: Exception) { false }
