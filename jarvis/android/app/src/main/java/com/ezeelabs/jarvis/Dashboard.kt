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
                0 -> Dashboard(vm)
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
fun Dashboard(vm: JarvisVM) {
    val st = vm.status
    val pending = st?.optJSONObject("pending_call")
    val last = st?.optJSONObject("last_call")
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(250) } }
    LaunchedEffect(pending?.optString("status")) { vm.loadAnalytics("today") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("JARVIS", fontSize = 30.sp, fontWeight = FontWeight.Light, color = Accent)
            Text(if (vm.online) "● Connected" else "● Offline", color = if (vm.online) Good else Bad)
        }
        if (st != null && !st.optBoolean("calls_enabled")) Panel {
            Text("Calls are disabled on the server", color = Bad, fontWeight = FontWeight.Medium)
            Text("A scheduled call will fail with this reason until CALLS_ENABLED=true is set after setup and testing.", color = Muted, fontSize = 13.sp)
        }
        if (st != null && (!st.optBoolean("exotel_configured") || !st.optBoolean("openai_configured") || !st.optBoolean("number_valid")))
            Panel { Text("Server setup incomplete", color = Bad); Text("Exotel / OpenAI / approved number not fully configured.", color = Muted, fontSize = 13.sp) }

        CallCard(vm, pending, last, now)

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
fun CallCard(vm: JarvisVM, pending: JSONObject?, last: JSONObject?, now: Long) {
    Panel {
        val status = pending?.optString("status")
        when (status) {
            "scheduled", "dialing" -> {
                val target = pending!!.getLong("scheduled_for_ms") - vm.clockOffsetMs // in phone-clock terms
                val remain = (target - now).coerceAtLeast(0)
                Text("Call scheduled", color = Muted)
                if (remain > 0) {
                    Text(String.format("%02d:%02d", remain / 60000, (remain / 1000) % 60), fontSize = 56.sp, fontWeight = FontWeight.Light, color = Accent)
                    LinearProgressIndicator(progress = { (1f - remain / 180000f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                } else Text("Placing the call…", fontSize = 24.sp, color = Accent)
                Text("Expected at ${clock(target)}", color = Muted)
                Text("This runs on the server: it continues even if you close the app.", color = Muted, fontSize = 12.sp)
                if (status == "scheduled") OutlinedButton(onClick = { vm.cancelCall(pending!!.getString("id")) }, modifier = Modifier.fillMaxWidth()) { Text("Cancel call") }
            }
            "initiated" -> { Text("JARVIS is calling you", fontSize = 22.sp, color = Accent); Text("Answer your phone. The call was initiated.", color = Muted) }
            "in_progress" -> { Text("Call in progress", fontSize = 22.sp, color = Good); Text("Connected to JARVIS.", color = Muted) }
            else -> {
                Button(onClick = { vm.callMe() }, enabled = !vm.busy && vm.online, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    if (vm.busy) CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp) else Text("Call me in 3 minutes", fontSize = 16.sp)
                }
                Text("JARVIS will place a real phone call to your registered number.", color = Muted, fontSize = 12.sp)
                if (last != null && last.optString("status") in listOf("failed", "busy", "no_answer") && isRecent(last.str("ended_at"))) {
                    Text("Last call failed", color = Bad, fontWeight = FontWeight.Medium)
                    Text(last.str("failure_reason").ifEmpty { "No reason reported." }, color = Bad, fontSize = 13.sp)
                }
            }
        }
    }
}

private fun isRecent(iso: String): Boolean = try { Instant.now().epochSecond - Instant.parse(iso).epochSecond < 1800 } catch (e: Exception) { false }
