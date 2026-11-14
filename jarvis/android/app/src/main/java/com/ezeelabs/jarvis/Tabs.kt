@file:OptIn(ExperimentalMaterial3Api::class)

package com.ezeelabs.jarvis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

private val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

@Composable
fun Screen(title: String, content: @Composable () -> Unit) = Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(title, fontSize = 24.sp, fontWeight = FontWeight.Light, color = Accent)
    content()
}

// ------------------------------------------------------------------ Business
@Composable
fun BusinessTab(vm: JarvisVM) {
    var sub by rememberSaveable { mutableStateOf("Overview") }
    Screen("Business") {
        Choices(listOf("Overview", "Products", "Orders", "Expenses"), sub) { sub = it }
        when (sub) {
            "Overview" -> Overview(vm)
            "Products" -> ProductsList(vm)
            "Orders" -> OrdersList(vm)
            else -> ExpensesList(vm)
        }
    }
}

@Composable
fun Overview(vm: JarvisVM) {
    var period by rememberSaveable { mutableStateOf("month") }
    LaunchedEffect(period) { vm.loadAnalytics(period) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Choices(listOf("today", "week", "month", "all"), period) { period = it }
        val s = vm.summary
        if (s == null) Text("Loading…", color = Muted) else {
            Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("Revenue", inr(s.optDouble("revenue")))
                    Stat("Expenses", inr(s.optDouble("expenses")))
                    Stat("Net recorded", inr(s.optDouble("net_recorded_earnings")), if (s.optDouble("net_recorded_earnings") >= 0) Good else Bad)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("Completed", s.optInt("completed_orders").toString())
                    Stat("Pending", s.optInt("pending_orders").toString())
                    Stat("Units sold", s.optInt("units_sold").toString())
                }
                Text("Pending order value: " + inr(s.optDouble("pending_order_value")), color = Muted)
                if (!s.optBoolean("has_data")) Text("Nothing recorded for this period.", color = Muted)
            }
            vm.growth?.let { g ->
                Panel {
                    Text("Growth vs ${g.optString("compared_with").replace('_', ' ')}", color = Muted)
                    Text("Revenue: " + pct(g, "revenue_change_pct") + "  (" + inr(g.optDouble("previous_revenue")) + " before)")
                    Text("Orders: " + pct(g, "orders_change_pct") + "  (" + g.optInt("previous_orders") + " before)")
                }
            }
            val top = s.optJSONArray("top_products")?.objects().orEmpty()
            if (top.isNotEmpty()) Panel {
                Text("Top products", color = Muted)
                top.forEach { Text("${it.optString("product")} — ${it.optInt("units")} units · ${inr(it.optDouble("revenue"))}") }
            }
        }
    }
}

private fun pct(g: JSONObject, key: String) = if (g.isNull(key)) "no baseline" else (if (g.getDouble(key) >= 0) "+" else "") + g.getDouble(key) + "%"

@Composable
fun ProductsList(vm: JarvisVM) {
    LaunchedEffect(Unit) { vm.loadProducts() }
    var dlg by remember { mutableStateOf(false) }
    Button(onClick = { dlg = true }, modifier = Modifier.fillMaxWidth()) { Text("Add product") }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(vm.products) { p ->
            Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.optString("name"), fontWeight = FontWeight.Medium)
                        Text(inr(p.optDouble("price")) + " · stock " + p.optInt("stock"), color = Muted)
                        if (p.str("description").isNotEmpty()) Text(p.str("description"), color = Muted, fontSize = 12.sp)
                    }
                    IconButton(onClick = { vm.deleteProduct(p.getInt("id")) }) { Icon(Icons.Filled.Delete, "Delete") }
                }
            }
        }
    }
    if (dlg) {
        var n by remember { mutableStateOf("") }; var pr by remember { mutableStateOf("") }
        var st by remember { mutableStateOf("0") }; var d by remember { mutableStateOf("") }
        FormDialog("New product", { dlg = false }, { vm.addProduct(n, pr.toDouble(), st.toIntOrNull() ?: 0, d); dlg = false }, n.isNotBlank() && pr.toDoubleOrNull() != null) {
            Field("Name", n, { n = it }); Field("Price (₹)", pr, { pr = it }, true); Field("Stock", st, { st = it }, true); Field("Description", d, { d = it })
        }
    }
}

@Composable
fun OrdersList(vm: JarvisVM) {
    LaunchedEffect(Unit) { vm.loadOrders() }
    var dlg by remember { mutableStateOf(false) }
    Button(onClick = { dlg = true }, modifier = Modifier.fillMaxWidth()) { Text("Add order") }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(vm.orders) { o ->
            Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${o.optInt("quantity")} × ${o.optString("product_name")}", fontWeight = FontWeight.Medium)
                    Text(inr(o.optDouble("total")))
                }
                Text(stamp(o.optString("created_at")) + (if (o.str("customer").isNotEmpty()) " · " + o.str("customer") else ""), color = Muted, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Choices(listOf("pending", "completed", "cancelled"), o.optString("status")) { vm.setOrderStatus(o.getInt("id"), it) }
                }
                TextButton(onClick = { vm.deleteOrder(o.getInt("id")) }) { Text("Delete order", color = Bad) }
            }
        }
    }
    if (dlg) {
        var p by remember { mutableStateOf("") }; var q by remember { mutableStateOf("1") }; var u by remember { mutableStateOf("") }
        var c by remember { mutableStateOf("") }; var s by remember { mutableStateOf("pending") }
        FormDialog("New order", { dlg = false }, { vm.addOrder(p, q.toInt(), u.toDouble(), s, c); dlg = false },
            p.isNotBlank() && q.toIntOrNull() != null && u.toDoubleOrNull() != null) {
            Field("Product", p, { p = it }); Field("Quantity", q, { q = it }, true); Field("Unit price (₹)", u, { u = it }, true)
            Field("Customer", c, { c = it }); Choices(listOf("pending", "completed"), s) { s = it }
        }
    }
}

@Composable
fun ExpensesList(vm: JarvisVM) {
    LaunchedEffect(Unit) { vm.loadExpenses() }
    var dlg by remember { mutableStateOf(false) }
    Button(onClick = { dlg = true }, modifier = Modifier.fillMaxWidth()) { Text("Add expense") }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(vm.expenses) { e ->
            Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.optString("category") + " — " + inr(e.optDouble("amount")), fontWeight = FontWeight.Medium)
                        Text(stamp(e.optString("created_at")) + (if (e.str("note").isNotEmpty()) " · " + e.str("note") else ""), color = Muted, fontSize = 12.sp)
                    }
                    IconButton(onClick = { vm.deleteExpense(e.getInt("id")) }) { Icon(Icons.Filled.Delete, "Delete") }
                }
            }
        }
    }
    if (dlg) {
        var c by remember { mutableStateOf("") }; var a by remember { mutableStateOf("") }; var n by remember { mutableStateOf("") }
        FormDialog("New expense", { dlg = false }, { vm.addExpense(c, a.toDouble(), n); dlg = false }, c.isNotBlank() && (a.toDoubleOrNull() ?: 0.0) > 0) {
            Field("Category", c, { c = it }); Field("Amount (₹)", a, { a = it }, true); Field("Note", n, { n = it })
        }
    }
}

// ------------------------------------------------------------------ Memory
@Composable
fun MemoryTab(vm: JarvisVM) {
    LaunchedEffect(Unit) { vm.loadMemory() }
    var dlg by remember { mutableStateOf(false) }
    var wipe by remember { mutableStateOf(false) }
    Screen("JARVIS memory") {
        Text("JARVIS only uses items that are switched on. You can edit, disable or delete anything.", color = Muted, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { dlg = true }) { Text("Add memory") }
            OutlinedButton(onClick = { wipe = true }, enabled = vm.memory.isNotEmpty()) { Text("Delete all") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.memory) { m ->
                Panel {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.optString("title"), fontWeight = FontWeight.Medium)
                            Text(m.optString("kind") + (if (m.optString("source") == "jarvis") " · saved by JARVIS" else ""), color = Accent, fontSize = 12.sp)
                        }
                        Switch(checked = m.optInt("enabled") == 1, onCheckedChange = { vm.toggleMemory(m, it) })
                        IconButton(onClick = { vm.deleteMemory(m.getInt("id")) }) { Icon(Icons.Filled.Delete, "Delete") }
                    }
                    Text(m.optString("content"), color = Muted)
                }
            }
        }
    }
    if (dlg) {
        var k by remember { mutableStateOf("note") }; var t by remember { mutableStateOf("") }; var c by remember { mutableStateOf("") }
        FormDialog("New memory", { dlg = false }, { vm.addMemory(k, t, c); dlg = false }, t.isNotBlank() && c.isNotBlank()) {
            Choices(listOf("note", "product", "project", "business", "preference"), k) { k = it }
            Field("Title", t, { t = it }); Field("What should JARVIS remember?", c, { c = it })
        }
    }
    if (wipe) AlertDialog(onDismissRequest = { wipe = false }, title = { Text("Delete all memory?") }, text = { Text("This cannot be undone.") },
        confirmButton = { TextButton(onClick = { vm.wipeMemory(); wipe = false }) { Text("Delete", color = Bad) } },
        dismissButton = { TextButton(onClick = { wipe = false }) { Text("Cancel") } })
}

// ------------------------------------------------------------------ Calls
@Composable
fun CallsTab(vm: JarvisVM) {
    var sub by rememberSaveable { mutableStateOf("Schedules") }
    LaunchedEffect(sub) { if (sub == "Schedules") vm.loadSchedules() else vm.loadHistory() }
    var dlg by remember { mutableStateOf(false) }
    Screen("Calls") {
        Choices(listOf("Schedules", "History"), sub) { sub = it }
        if (sub == "Schedules") {
            Text("Times are India Standard Time. Scheduled calls are placed by the server.", color = Muted, fontSize = 13.sp)
            Button(onClick = { dlg = true }, modifier = Modifier.fillMaxWidth()) { Text("Add scheduled call") }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.schedules) { s ->
                    Panel {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.optString("name"), fontWeight = FontWeight.Medium)
                                val day = if (s.optString("frequency") == "weekly" && !s.isNull("weekday")) " " + DAYS[s.getInt("weekday")] else ""
                                Text(s.optString("frequency") + day + " · " + s.optString("time_hhmm") + " IST · " + s.optString("purpose"), color = Muted, fontSize = 12.sp)
                                if (s.str("note").isNotEmpty()) Text(s.str("note"), color = Muted, fontSize = 12.sp)
                            }
                            Switch(checked = s.optInt("enabled") == 1, onCheckedChange = { vm.toggleSchedule(s, it) })
                            IconButton(onClick = { vm.deleteSchedule(s.getInt("id")) }) { Icon(Icons.Filled.Delete, "Delete") }
                        }
                    }
                }
            }
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.history) { c ->
                val bad = c.optString("status") in listOf("failed", "busy", "no_answer")
                Panel {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(c.optString("purpose") + " · " + c.optString("kind"), fontWeight = FontWeight.Medium)
                        Text(c.optString("status"), color = if (bad) Bad else if (c.optString("status") == "completed") Good else Accent)
                    }
                    Text(stamp(c.optString("created_at")) + (if (!c.isNull("duration_seconds")) " · ${c.optInt("duration_seconds")}s" else ""), color = Muted, fontSize = 12.sp)
                    if (c.str("failure_reason").isNotEmpty()) Text(c.str("failure_reason"), color = if (bad) Bad else Muted, fontSize = 13.sp)
                }
            }
        }
    }
    if (dlg) {
        var n by remember { mutableStateOf("") }; var f by remember { mutableStateOf("daily") }; var t by remember { mutableStateOf("20:00") }
        var d by remember { mutableStateOf("Sun") }; var p by remember { mutableStateOf("briefing") }; var note by remember { mutableStateOf("") }
        FormDialog("New scheduled call", { dlg = false },
            { vm.addSchedule(n, f, t, if (f == "weekly") DAYS.indexOf(d) else null, p, note); dlg = false },
            n.isNotBlank() && t.isNotBlank() && (p != "reminder" || note.isNotBlank())) {
            Field("Name", n, { n = it })
            Choices(listOf("daily", "weekly", "once"), f) { f = it; if (it == "once" && !t.contains("-")) t = "" }
            Field(if (f == "once") "Date & time IST (YYYY-MM-DD HH:MM)" else "Time IST (HH:MM, 24h)", t, { t = it })
            if (f == "weekly") Choices(DAYS, d) { d = it }
            Choices(listOf("briefing", "weekly", "reminder", "chat"), p) { p = it }
            if (p == "reminder") Field("Reminder text", note, { note = it })
        }
    }
}

// ------------------------------------------------------------------ Settings
@Composable
fun SettingsTab(vm: JarvisVM) {
    LaunchedEffect(Unit) { vm.loadSettings() }
    val st = vm.status
    Screen("Settings") {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel {
                Text("Server", color = Muted)
                Text(vm.baseUrl)
                Text(if (vm.online) "Connected" else "Offline", color = if (vm.online) Good else Bad)
                if (st != null) {
                    Text("Server time: " + st.optString("ist"), color = Muted)
                    Text("Approved number: " + st.optString("approved_number"), color = Muted)
                    Text("Max call length: " + st.optInt("max_call_seconds") / 60 + " min", color = Muted)
                    Text("Calls enabled: " + st.optBoolean("calls_enabled"), color = if (st.optBoolean("calls_enabled")) Good else Bad)
                    Text("Exotel configured: " + st.optBoolean("exotel_configured"), color = Muted)
                    Text("OpenAI configured: " + st.optBoolean("openai_configured"), color = Muted)
                }
            }
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Save call transcripts")
                        Text("Off by default. Calls are never audio-recorded. Turning this off deletes saved transcripts.", color = Muted, fontSize = 12.sp)
                    }
                    Switch(checked = vm.storeTranscripts, onCheckedChange = { vm.setTranscripts(it) })
                }
            }
            OutlinedButton(onClick = { vm.logout() }, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
        }
    }
}
