package com.ezeelabs.jarvis

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class JarvisVM(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val api = Api(store)

    var loggedIn by mutableStateOf(store.token.isNotEmpty())
    var baseUrl by mutableStateOf(store.baseUrl)
    var status by mutableStateOf<JSONObject?>(null)
    var online by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var clockOffsetMs by mutableStateOf(0L) // server clock minus phone clock

    var summary by mutableStateOf<JSONObject?>(null)
    var growth by mutableStateOf<JSONObject?>(null)
    var products by mutableStateOf(listOf<JSONObject>())
    var orders by mutableStateOf(listOf<JSONObject>())
    var expenses by mutableStateOf(listOf<JSONObject>())
    var memory by mutableStateOf(listOf<JSONObject>())
    var schedules by mutableStateOf(listOf<JSONObject>())
    var history by mutableStateOf(listOf<JSONObject>())
    var storeTranscripts by mutableStateOf(false)

    private var trackedCall: String? = null

    init {
        viewModelScope.launch {
            while (true) {
                if (loggedIn) refreshStatus()
                delay(3000)
            }
        }
    }

    /** Runs [block] off the main thread; surfaces ApiException messages in [error]. */
    private fun io(onDone: (() -> Unit)? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                onDone?.invoke()
            } catch (e: ApiException) {
                if (e.code == 401) logout() else error = e.message
            } catch (e: Exception) {
                error = e.message ?: "Unexpected error"
            }
        }
    }

    fun login(url: String, password: String) {
        busy = true
        viewModelScope.launch {
            try {
                store.baseUrl = url
                baseUrl = store.baseUrl
                val t = withContext(Dispatchers.IO) { api.login(password) }
                store.token = t
                loggedIn = true
                error = null
            } catch (e: ApiException) {
                error = e.message
            } finally { busy = false }
        }
    }

    fun logout() { store.token = ""; loggedIn = false; status = null; online = false }

    fun createVoiceSession(onState: (String) -> Unit, onError: (String) -> Unit): VoiceSession {
        return VoiceSession(getApplication(), store.baseUrl, store.token, onState, onError)
    }

    private suspend fun refreshStatus() {
        try {
            val s = withContext(Dispatchers.IO) { api.get("/api/status") }
            status = s
            online = true
            clockOffsetMs = s.getLong("server_now_ms") - System.currentTimeMillis()
            val p = s.optJSONObject("pending_call")
            if (p != null && p.optString("status") == "scheduled" && trackedCall != p.getString("id")) {
                trackedCall = p.getString("id")
                CallService.start(getApplication<Application>(), p.getString("id"), p.getLong("scheduled_for_ms") - clockOffsetMs)
            }
        } catch (e: ApiException) {
            if (e.code == 401) logout() else online = false
        } catch (e: Exception) { online = false }
    }

    fun callMe() {
        if (busy) return
        busy = true
        error = null
        io(onDone = { busy = false }) {
            try {
                val r = api.post("/api/calls/call-me")
                val c = r.getJSONObject("call")
                withContext(Dispatchers.Main) { // sync offset first so the countdown is exact
                    clockOffsetMs = c.getLong("server_now_ms") - System.currentTimeMillis()
                    trackedCall = c.getString("id")
                    CallService.start(getApplication<Application>(), c.getString("id"), c.getLong("scheduled_for_ms") - clockOffsetMs)
                }
            } finally { withContext(Dispatchers.Main) { busy = false } }
            refreshStatus()
        }
    }

    fun cancelCall(id: String) = io { api.post("/api/calls/$id/cancel"); refreshStatus() }

    fun clearError() { error = null }

    // ---- data loaders / mutations ----
    fun loadAnalytics(period: String) = io {
        val r = api.get("/api/analytics?period=$period")
        withContext(Dispatchers.Main) { summary = r.getJSONObject("summary"); growth = r.optJSONObject("growth") }
    }
    fun loadProducts() = io { val l = api.get("/api/products").getJSONArray("products").objects(); withContext(Dispatchers.Main) { products = l } }
    fun loadOrders() = io { val l = api.get("/api/orders").getJSONArray("orders").objects(); withContext(Dispatchers.Main) { orders = l } }
    fun loadExpenses() = io { val l = api.get("/api/expenses").getJSONArray("expenses").objects(); withContext(Dispatchers.Main) { expenses = l } }
    fun loadMemory() = io { val l = api.get("/api/memory").getJSONArray("memory").objects(); withContext(Dispatchers.Main) { memory = l } }
    fun loadSchedules() = io { val l = api.get("/api/schedules").getJSONArray("schedules").objects(); withContext(Dispatchers.Main) { schedules = l } }
    fun loadHistory() = io { val l = api.get("/api/calls").getJSONArray("calls").objects(); withContext(Dispatchers.Main) { history = l } }
    fun loadSettings() = io { val s = api.get("/api/settings").getBoolean("store_transcripts"); withContext(Dispatchers.Main) { storeTranscripts = s } }

    fun addProduct(name: String, price: Double, stock: Int, desc: String) = io {
        api.post("/api/products", JSONObject().put("name", name).put("price", price).put("stock", stock).put("description", desc)); loadProducts()
    }
    fun deleteProduct(id: Int) = io { api.delete("/api/products/$id"); loadProducts() }

    fun addOrder(product: String, qty: Int, price: Double, status: String, customer: String) = io {
        api.post("/api/orders", JSONObject().put("product_name", product).put("quantity", qty).put("unit_price", price)
            .put("status", status).put("customer", customer)); loadOrders()
    }
    fun setOrderStatus(id: Int, status: String) = io { api.patch("/api/orders/$id", JSONObject().put("status", status)); loadOrders() }
    fun deleteOrder(id: Int) = io { api.delete("/api/orders/$id"); loadOrders() }

    fun addExpense(category: String, amount: Double, note: String) = io {
        api.post("/api/expenses", JSONObject().put("category", category).put("amount", amount).put("note", note)); loadExpenses()
    }
    fun deleteExpense(id: Int) = io { api.delete("/api/expenses/$id"); loadExpenses() }

    fun addMemory(kind: String, title: String, content: String) = io {
        api.post("/api/memory", JSONObject().put("kind", kind).put("title", title).put("content", content)); loadMemory()
    }
    fun toggleMemory(m: JSONObject, on: Boolean) = io {
        api.put("/api/memory/${m.getInt("id")}", JSONObject().put("kind", m.getString("kind")).put("title", m.getString("title"))
            .put("content", m.getString("content")).put("enabled", on)); loadMemory()
    }
    fun deleteMemory(id: Int) = io { api.delete("/api/memory/$id"); loadMemory() }
    fun wipeMemory() = io { api.delete("/api/memory"); loadMemory() }

    fun addSchedule(name: String, freq: String, time: String, weekday: Int?, purpose: String, note: String) = io {
        val b = JSONObject().put("name", name).put("frequency", freq).put("time", time).put("purpose", purpose).put("enabled", true)
        if (weekday != null) b.put("weekday", weekday)
        if (note.isNotBlank()) b.put("note", note)
        api.post("/api/schedules", b); loadSchedules()
    }
    fun toggleSchedule(s: JSONObject, on: Boolean) = io {
        val b = JSONObject().put("name", s.getString("name")).put("frequency", s.getString("frequency")).put("time", s.getString("time_hhmm"))
            .put("purpose", s.getString("purpose")).put("enabled", on)
        if (!s.isNull("weekday")) b.put("weekday", s.getInt("weekday"))
        if (!s.isNull("note")) b.put("note", s.getString("note"))
        api.put("/api/schedules/${s.getInt("id")}", b); loadSchedules()
    }
    fun deleteSchedule(id: Int) = io { api.delete("/api/schedules/$id"); loadSchedules() }

    fun setTranscripts(on: Boolean) = io { api.put("/api/settings", JSONObject().put("store_transcripts", on)); loadSettings() }
}
