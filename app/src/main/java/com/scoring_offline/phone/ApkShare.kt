package com.scoring_offline.phone

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ShareState(
    /** Download addresses, one per network this phone is on. Empty until the server is up. */
    val urls: List<String> = emptyList(),
    val downloads: Int = 0,
    val error: String? = null,
)

/**
 * Serves this phone's own APK over the local network, so a new TV can install Scorer without a
 * computer or file hosting. Phone and TV run the same APK. Runs only while the install screen is open.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApkShare(context: Context) {
    private val apk = File(context.applicationContext.applicationInfo.sourceDir)
    private val apkType = ContentType.parse("application/vnd.android.package-archive")

    // One thread, so a quick start then stop always run in order.
    private val scope = CoroutineScope(Dispatchers.IO.limitedParallelism(1) + SupervisorJob())
    private var server: EmbeddedServer<*, *>? = null

    private val _state = MutableStateFlow(ShareState())
    val state: StateFlow<ShareState> = _state

    fun start() {
        scope.launch {
            val bytes = runCatching { apk.readBytes() }.getOrElse {
                _state.value = ShareState(error = "Could not read the app file.")
                return@launch
            }
            for (port in PORTS) {
                val started = runCatching { server = createServer(port, bytes).start(wait = false) }.isSuccess
                if (started) {
                    val urls = localIpv4Addresses().map { "http://$it:$port$APK_PATH" }
                    _state.value = if (urls.isEmpty()) {
                        ShareState(error = "Connect this phone to Wi-Fi, or turn on its hotspot.")
                    } else {
                        ShareState(urls = urls)
                    }
                    return@launch
                }
            }
            _state.value = ShareState(error = "Could not open a network port on this phone.")
        }
    }

    fun stop() {
        scope.launch {
            runCatching { server?.stop(100, 500) }
            server = null
        }
    }

    private fun createServer(port: Int, bytes: ByteArray) =
        embeddedServer(CIO, port = port, host = "0.0.0.0") {
            routing {
                for (path in listOf("/", APK_PATH)) {
                    // Some download apps check the file with HEAD first.
                    head(path) {
                        call.response.header(HttpHeaders.ContentDisposition, DISPOSITION)
                        call.respond(object : OutgoingContent.NoContent() {
                            override val contentType = apkType
                            override val contentLength = bytes.size.toLong()
                        })
                    }
                    get(path) {
                        _state.update { it.copy(downloads = it.downloads + 1) }
                        call.response.header(HttpHeaders.ContentDisposition, DISPOSITION)
                        call.respondBytes(bytes, apkType)
                    }
                }
            }
        }

    private companion object {
        const val APK_PATH = "/scorer.apk"
        const val DISPOSITION = "attachment; filename=\"scorer.apk\""
        val PORTS = listOf(8080, 8081, 8082)
    }
}
