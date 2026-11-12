package com.scoring_offline.tv

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import com.scoring_offline.shared.AppJson
import com.scoring_offline.shared.NAME_PREFIX
import com.scoring_offline.shared.NSD_TYPE
import com.scoring_offline.shared.Snapshot
import com.scoring_offline.shared.TV_PORT
import io.ktor.server.application.install
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import java.io.File
import java.net.Inet4Address

/** Runs the WebSocket server the phone connects to, and keeps the last snapshot on disk. */
class TvViewModel(private val app: Application) : AndroidViewModel(app) {

    private val file = File(app.filesDir, "tv_snapshot.json")

    private val _snapshot = MutableStateFlow(
        runCatching { AppJson.decodeFromString<Snapshot>(file.readText()) }.getOrNull(),
    )
    val snapshot: StateFlow<Snapshot?> = _snapshot

    private val _clients = MutableStateFlow(0)
    val clients: StateFlow<Int> = _clients

    private val _serverError = MutableStateFlow<String?>(null)
    val serverError: StateFlow<String?> = _serverError

    val ipAddress: String = findIp()

    /** Shown on the TV and in the phone's TV list, so the right TV can be picked. */
    val tvName: String = "Scoreboard-" + friendlyName().replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(24)

    /** The name set in the TV's own settings (for example "OnePlus TV"), else maker + model. */
    private fun friendlyName(): String =
        runCatching { Settings.Global.getString(app.contentResolver, "device_name") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    private var server: EmbeddedServer<*, *>? = null
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registration: NsdManager.RegistrationListener? = null

    init {
        startServer()
        registerNsd()
    }

    private fun startServer() {
        try {
            server = embeddedServer(CIO, port = TV_PORT, host = "0.0.0.0") {
                install(WebSockets) {
                    pingPeriodMillis = 15_000L
                    timeoutMillis = 30_000L
                }
                routing {
                    get("/name") { call.respondText(NAME_PREFIX + tvName) }
                    webSocket("/ws") {
                        _clients.update { it + 1 }
                        try {
                            for (frame in incoming) {
                                if (frame !is Frame.Text) continue
                                val s = runCatching { AppJson.decodeFromString<Snapshot>(frame.readText()) }.getOrNull()
                                    ?: continue
                                accept(s)
                            }
                        } finally {
                            _clients.update { it - 1 }
                        }
                    }
                }
            }.start(wait = false)
        } catch (e: Exception) {
            _serverError.value = "Cannot listen on port $TV_PORT: ${e.message}"
        }
    }

    @Synchronized
    private fun accept(s: Snapshot) {
        val cur = _snapshot.value
        if (cur != null && s.seq <= cur.seq) return // stale
        _snapshot.value = s
        runCatching {
            val tmp = File(file.parentFile, "tv_snapshot.json.tmp")
            tmp.writeText(AppJson.encodeToString(s))
            if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        }
    }

    private fun registerNsd() {
        val info = NsdServiceInfo().apply {
            serviceName = tvName
            serviceType = NSD_TYPE
            port = TV_PORT
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registration = l
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    private fun findIp(): String = runCatching {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val props = cm.getLinkProperties(cm.activeNetwork)
        props?.linkAddresses?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull() ?: "unknown"

    override fun onCleared() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        runCatching { server?.stop(500, 1000) }
    }
}
