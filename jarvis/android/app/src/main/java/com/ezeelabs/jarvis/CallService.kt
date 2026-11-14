package com.ezeelabs.jarvis

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps tracking a pending call after the app is closed:
 * shows a system-driven countdown notification (no polling needed for the clock) and posts
 * "about to call", "initiated" and "failed (reason)" notifications from the server's status.
 * The server owns the call: if this service is killed the call still happens.
 */
class CallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callId = intent?.getStringExtra(EXTRA_ID)
        if (callId == null) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_CANCEL) {
            scope.launch { cancelCall(callId) }
            return START_NOT_STICKY
        }
        val target = intent!!.getLongExtra(EXTRA_TARGET, System.currentTimeMillis() + 180_000)
        Notifs.channels(this)
        try {
            ServiceCompat.startForeground(this, Notifs.ID_COUNT, countdown(callId, target), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        job?.cancel()
        job = scope.launch { track(callId, target) }
        return START_REDELIVER_INTENT
    }

    private fun countdown(callId: String, target: Long): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, CallService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, callId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, Notifs.CH_COUNT)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("JARVIS will call you")
            .setContentText("Time until the call")
            .setUsesChronometer(true).setChronometerCountDown(true).setWhen(target)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(Notifs.openApp(this))
            .addAction(0, "Cancel call", cancel).build()
    }

    private suspend fun cancelCall(callId: String) {
        val api = Api(Store(this))
        try {
            api.post("/api/calls/$callId/cancel")
            Notifs.alert(this, Notifs.ID_FAILED, "JARVIS call cancelled", "No call will be placed.")
        } catch (e: ApiException) {
            Notifs.alert(this, Notifs.ID_FAILED, "Could not cancel", e.message)
        }
        finish()
    }

    private suspend fun track(callId: String, target: Long) {
        val api = Api(Store(this))
        var aboutShown = false
        var initShown = false
        var errors = 0
        val deadline = target + 10 * 60_000
        while (System.currentTimeMillis() < deadline) {
            val now = System.currentTimeMillis()
            try {
                val c = api.get("/api/calls/$callId").getJSONObject("call")
                errors = 0
                when (val st = c.getString("status")) {
                    "scheduled", "dialing" -> if (!aboutShown && target - now <= 30_000) {
                        aboutShown = true
                        Notifs.alert(this, Notifs.ID_ABOUT, "JARVIS will call in about 30 seconds", "Keep your phone nearby.")
                    }
                    "initiated" -> if (!initShown) {
                        initShown = true
                        Notifs.alert(this, Notifs.ID_INITIATED, "JARVIS is calling you", "Call initiated. Answer your phone.")
                    }
                    "in_progress", "completed", "cancelled" -> {
                        if (!initShown && st != "cancelled") Notifs.alert(this, Notifs.ID_INITIATED, "JARVIS called you", "Call connected.")
                        finish(); return
                    }
                    else -> { // failed, busy, no_answer
                        val reason = c.optString("failure_reason").ifEmpty { "The call did not go through." }
                        Notifs.alert(this, Notifs.ID_FAILED, "JARVIS call failed", reason)
                        finish(); return
                    }
                }
            } catch (e: ApiException) {
                if (e.code == 401) { finish(); return }
                errors++
                if (errors > 25 && now > target + 60_000) {
                    Notifs.alert(this, Notifs.ID_FAILED, "Call status unknown", "Could not reach the JARVIS server: " + e.message)
                    finish(); return
                }
            }
            delay(if (target - now in -60_000L..40_000L) 1_000 else 3_000)
        }
        finish()
    }

    private fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { scope.coroutineContext[Job]?.cancel(); super.onDestroy() }

    companion object {
        const val EXTRA_ID = "call_id"
        const val EXTRA_TARGET = "target_local_ms"
        const val ACTION_CANCEL = "com.ezeelabs.jarvis.CANCEL"

        fun start(ctx: Context, callId: String, targetLocalMs: Long) {
            val i = Intent(ctx, CallService::class.java).putExtra(EXTRA_ID, callId).putExtra(EXTRA_TARGET, targetLocalMs)
            ctx.startForegroundService(i)
        }
    }
}
