package com.scoring_offline.phone

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.scoring_offline.shared.AppJson
import com.scoring_offline.shared.AppState
import com.scoring_offline.shared.NSD_TYPE
import com.scoring_offline.shared.NAME_PREFIX
import com.scoring_offline.shared.Snapshot
import com.scoring_offline.shared.TV_PORT
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.Frame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString
import java.net.Inet4Address
import java.net.NetworkInterface

enum class LinkStatus { SEARCHING, CHOOSE_TV, CONNECTING, CONNECTED, DISCONNECTED }

data class HostPort(val host: String, val port: Int) {
    override fun toString() = "$host:$port"
}

data class TvInfo(val name: String, val host: HostPort)

/** "192.168.1.20" or "192.168.1.20:8765" -> HostPort, else null. */
fun parseHostPort(text: String): HostPort? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val parts = t.split(":")
    val port = if (parts.size > 1) parts[1].toIntOrNull() ?: return null else TV_PORT
    return parts[0].takeIf { it.isNotBlank() }?.let { HostPort(it, port) }
}

/**
 * Finds a TV and pushes the full state after every change.
 *
 * Finding a TV, in order:
 *  1. Manual address, if the user typed one (saved).
 *  2. The TV used last time, if it is visible (name saved).
 *  3. The only TV visible. In a new place a new TV is picked up with no setup.
 *  4. Several TVs and none remembered: status CHOOSE_TV, the user picks one.
 * TVs are found by mDNS (NSD). If that finds nothing, the phone also scans its own Wi-Fi or hotspot
 * subnet for the TV's port, which works on networks that block mDNS.
 * Reconnects forever: 1 s, 2 s, then every 5 s.
 */
class TvLink(
    context: Context,
    private val scope: CoroutineScope,
    private val state: StateFlow<AppState>,
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("tvlink", Context.MODE_PRIVATE)
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val client = HttpClient(CIO) {
        install(WebSockets) { pingIntervalMillis = 15_000 }
    }
    // Separate client: HttpTimeout would also cut a long-lived WebSocket.
    private val probeClient = HttpClient(CIO) {
        install(HttpTimeout) { connectTimeoutMillis = 700; requestTimeoutMillis = 1_500 }
    }

    private val _status = MutableStateFlow(LinkStatus.SEARCHING)
    val status: StateFlow<LinkStatus> = _status

    private val _tvs = MutableStateFlow<List<TvInfo>>(emptyList())
    /** Every TV seen on the network right now. */
    val tvs: StateFlow<List<TvInfo>> = _tvs

    private val _manual = MutableStateFlow(prefs.getString("manual", null)?.let(::parseHostPort))
    val manualHost: StateFlow<HostPort?> = _manual

    private val _preferred = MutableStateFlow(prefs.getString("tv_name", null))
    /** Name of the TV used last. */
    val preferredName: StateFlow<String?> = _preferred

    private val _current = MutableStateFlow<TvInfo?>(null)
    /** The TV this phone is connected or connecting to. */
    val currentTv: StateFlow<TvInfo?> = _current

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private var lastSeq = 0L
    private var discovery: NsdManager.DiscoveryListener? = null
    private var loop: Job? = null

    // NSD (before Android 14) resolves one service at a time, so resolves are queued.
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun start() {
        startDiscovery()
        scope.launch { scanner() }
        restart()
    }

    fun stop() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        client.close()
        probeClient.close()
    }

    /** User typed an address. Empty text clears it and goes back to automatic. */
    fun setManualHost(text: String) {
        val hp = parseHostPort(text)
        _manual.value = hp
        prefs.edit().apply { if (hp == null) remove("manual") else putString("manual", text.trim()) }.apply()
        restart()
    }

    /** User picked a TV from the list. */
    fun selectTv(tv: TvInfo) {
        setManualHost("")
        _preferred.value = tv.name
        prefs.edit().putString("tv_name", tv.name).apply()
        restart()
    }

    fun rescan() {
        scope.launch { scanOnce() }
    }

    private fun restart() {
        loop?.cancel()
        loop = scope.launch { connectLoop() }
    }

    private fun pickTarget(): TvInfo? {
        _manual.value?.let { return TvInfo("Manual", it) }
        val list = _tvs.value
        _preferred.value?.let { name -> list.firstOrNull { it.name == name }?.let { return it } }
        return if (list.size == 1) list[0] else null
    }

    private suspend fun connectLoop() {
        var backoff = 1_000L
        while (scope.isActive) {
            val target = pickTarget()
            if (target == null) {
                _current.value = null
                _status.value = if (_tvs.value.size > 1) LinkStatus.CHOOSE_TV else LinkStatus.SEARCHING
                delay(700)
                continue
            }
            _current.value = target
            _status.value = LinkStatus.CONNECTING
            try {
                client.webSocket(host = target.host.host, port = target.host.port, path = "/ws") {
                    _status.value = LinkStatus.CONNECTED
                    backoff = 1_000L
                    if (target.name != "Manual") {
                        _preferred.value = target.name
                        prefs.edit().putString("tv_name", target.name).apply()
                    }
                    // StateFlow replays its current value, so the TV is up to date right after connecting.
                    val sender = launch {
                        state.collect { s ->
                            lastSeq = maxOf(lastSeq + 1, System.currentTimeMillis())
                            send(Frame.Text(AppJson.encodeToString(Snapshot(lastSeq, s))))
                        }
                    }
                    try {
                        for (ignored in incoming) { /* TV sends nothing; loop ends when socket closes */ }
                    } finally {
                        sender.cancel()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // fall through to retry
            }
            _status.value = LinkStatus.DISCONNECTED
            delay(backoff)
            backoff = if (backoff < 2_000L) 2_000L else 5_000L
        }
    }

    // ---- mDNS ----

    private fun startDiscovery() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.contains("_gamescore")) enqueueResolve(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                _tvs.update { list -> list.filterNot { it.name == info.serviceName } }
            }
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discovery = listener
        runCatching { nsd.discoverServices(NSD_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    private fun enqueueResolve(info: NsdServiceInfo) {
        val startNow = synchronized(resolveQueue) {
            resolveQueue.addLast(info)
            !resolving.also { if (!resolving) resolving = true }
        }
        if (startNow) resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val info = synchronized(resolveQueue) {
            resolveQueue.removeFirstOrNull().also { if (it == null) resolving = false }
        } ?: return
        val rl = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = resolveNext()
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val ip = serviceInfo.host?.hostAddress
                if (ip != null) addTv(TvInfo(serviceInfo.serviceName, HostPort(ip, serviceInfo.port)))
                resolveNext()
            }
        }
        if (runCatching { nsd.resolveService(info, rl) }.isFailure) resolveNext()
    }

    private fun addTv(tv: TvInfo) {
        _tvs.update { list -> (list.filterNot { it.name == tv.name } + tv).sortedBy { it.name } }
    }

    // ---- subnet scan (fallback when mDNS is blocked) ----

    private suspend fun scanner() {
        delay(4_000)
        while (scope.isActive) {
            if (_tvs.value.isEmpty() && _manual.value == null) scanOnce()
            delay(10_000)
        }
    }

    private suspend fun scanOnce() {
        if (_scanning.value) return
        _scanning.value = true
        try {
            val sem = Semaphore(48)
            coroutineScope {
                for (base in localSubnets()) {
                    for (i in 1..254) {
                        launch { sem.withPermit { probe("$base.$i") } }
                    }
                }
            }
        } finally {
            _scanning.value = false
        }
    }

    /** The TV answers GET /name with "scoreboard-tv:<name>". */
    private suspend fun probe(ip: String) {
        val body = runCatching { probeClient.get("http://$ip:$TV_PORT/name").bodyAsText() }.getOrNull() ?: return
        if (body.startsWith(NAME_PREFIX)) addTv(TvInfo(body.removePrefix(NAME_PREFIX), HostPort(ip, TV_PORT)))
    }

    /** First three octets of each local network. */
    private fun localSubnets(): List<String> =
        localIpv4Addresses().map { it.substringBeforeLast('.') }.distinct()
}

/** This phone's private IPv4 addresses on Wi-Fi, hotspot or Ethernet. Mobile data is skipped. */
internal fun localIpv4Addresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { nif ->
            nif.isUp && !nif.isLoopback &&
                listOf("wlan", "ap", "swlan", "softap", "eth").any { nif.name.startsWith(it) }
        }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .filter { it.isSiteLocalAddress }
        .mapNotNull { it.hostAddress }
        .distinct()
}.getOrDefault(emptyList())
