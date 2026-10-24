package ru.fortport.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Цикличная проверка сессии. Различает три состояния:
 *  - VALID   — сервер подтвердил вход;
 *  - INVALID — сервер ТРИ раза подряд однозначно ответил «не авторизован» (401 / authenticated=false);
 *  - UNKNOWN — нет сети, тайм-аут, 5xx, 403/429 (защита), не-JSON (заглушка провайдера/WAF).
 * Выход из аккаунта (onInvalid) происходит только при INVALID. UNKNOWN никогда не меняет состояние входа,
 * а проверка повторяется с нарастающей паузой, пока сервер не ответит внятно.
 * Проверяется: пока экран на переднем плане, и сразу после смены/возврата сети (в т.ч. включение/выключение VPN).
 */
class SessionGuard(
    private val context: Context,
    private val api: SiteApi,
    private val onValid: () -> Unit,
    private val onInvalid: () -> Unit,
    private val onNetworkAvailable: () -> Unit,
    /** Подтянуть userId/deviceId/User-Agent из WebView (UI-поток); done() вызвать по готовности. */
    private val prepare: (done: () -> Unit) -> Unit
) {
    private companion object {
        const val TAG = "SessionGuard"
        const val OK_INTERVAL_MS = 60_000L
        const val INVALID_INTERVAL_MS = 10_000L // пока «не вошёл» — проверяем чаще, чтобы панели вернулись сразу после входа
        const val RETRY_BASE_MS = 5_000L
        const val RETRY_MAX_MS = 60_000L
        const val NET_SETTLE_MS = 2_000L // после смены сети даём маршруту установиться
    }

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    @Volatile private var active = false
    private var unknownStreak = 0
    private var registered = false

    private val loop = Runnable { verify() }

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            ui.post {
                if (!active) return@post
                unknownStreak = 0
                onNetworkAvailable()
                schedule(NET_SETTLE_MS)
            }
        }
    }

    fun start() {
        if (active) return
        active = true
        try {
            context.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(netCallback)
            registered = true
        } catch (e: Exception) { Log.w(TAG, "Не удалось подписаться на сеть", e) }
        schedule(0)
    }

    fun stop() {
        active = false
        ui.removeCallbacks(loop)
        if (registered) {
            try { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(netCallback) } catch (_: Exception) {}
            registered = false
        }
    }

    fun destroy() { stop(); worker.shutdownNow() }

    /** Внеочередная проверка (например, после 401 в нативном экране или в WebView). */
    fun requestVerify() { ui.post { if (active) schedule(0) } }

    private fun schedule(delayMs: Long) {
        ui.removeCallbacks(loop)
        ui.postDelayed(loop, delayMs)
    }

    private fun verify() {
        if (!active) return
        if (!running.compareAndSet(false, true)) return
        prepare { runCheck() }
    }

    private fun runCheck() {
        if (!active) { running.set(false); return }
        try {
            worker.execute {
                val state = try { api.checkSessionConfirmed() } catch (e: Exception) { SiteApi.SessionState.UNKNOWN }
                ui.post {
                    running.set(false)
                    if (!active) return@post
                    Log.d(TAG, "сессия: $state")
                    when (state) {
                        SiteApi.SessionState.VALID -> { unknownStreak = 0; onValid(); schedule(OK_INTERVAL_MS) }
                        SiteApi.SessionState.INVALID -> { unknownStreak = 0; onInvalid(); schedule(INVALID_INTERVAL_MS) }
                        SiteApi.SessionState.UNKNOWN -> {
                            unknownStreak++
                            schedule(minOf(RETRY_MAX_MS, RETRY_BASE_MS shl minOf(unknownStreak - 1, 4)))
                        }
                    }
                }
            }
        } catch (e: Exception) { running.set(false) } // executor остановлен
    }
}
