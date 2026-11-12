package com.scoring_offline.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.scoring_offline.BgGameOver
import com.scoring_offline.BgPlaying
import com.scoring_offline.BgTarget
import com.scoring_offline.BgTie
import com.scoring_offline.colorOfPlayer
import com.scoring_offline.shared.AppState
import com.scoring_offline.shared.Game
import com.scoring_offline.shared.ScoreEngine
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/** Referenced from the manifest. Uses the media receiver built into every Cast device. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setCastMediaOptions(
                CastMediaOptions.Builder().setNotificationOptions(null).setMediaSessionEnabled(false).build(),
            )
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

data class CastTv(val id: String, val name: String)

/**
 * Shows the board on any Google TV, Android TV or Chromecast with nothing installed on it.
 * The phone draws the board, encodes it as a video, serves that on the local network and tells the
 * TV's built-in receiver to play it, once per change.
 *
 * It is a video and not an image because only playback keeps the TV awake: with an image the
 * test TV went to sleep at its 5 minute screen-off timeout. See [BoardVideo].
 */
class CastLink(
    context: Context,
    private val scope: CoroutineScope,
    private val state: StateFlow<AppState>,
) {
    private val app = context.applicationContext
    private val castContext = runCatching { CastContext.getSharedInstance(app) }.getOrNull()
    private val router = MediaRouter.getInstance(app)
    private val selector = MediaRouteSelector.Builder()
        .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
        .build()

    private val _tvs = MutableStateFlow<List<CastTv>>(emptyList())
    val tvs: StateFlow<List<CastTv>> = _tvs

    private val _casting = MutableStateFlow<String?>(null)
    /** Name of the TV showing the board, or null. */
    val casting: StateFlow<String?> = _casting

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var publisher: Job? = null
    private var server: EmbeddedServer<*, *>? = null
    private var serverPort = 0
    private var version = 0L

    @Volatile private var latestVideo = ByteArray(0)
    private val videoFile = File(app.cacheDir, "board.mp4")

    private val routeCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshTvs()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshTvs()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshTvs()
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = attach(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = attach(session)
        override fun onSessionEnded(session: CastSession, error: Int) = detach()
        override fun onSessionSuspended(session: CastSession, reason: Int) = detach()
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            _error.value = "Could not reach that TV. Check it is on and on this Wi-Fi."
            detach()
        }
        override fun onSessionResumeFailed(session: CastSession, error: Int) = detach()
        override fun onSessionStarting(session: CastSession) { _error.value = null }
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
    }

    fun start() {
        val cast = castContext ?: return
        router.addCallback(selector, routeCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        refreshTvs()
        cast.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        cast.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let(::attach)
    }

    fun stop() {
        router.removeCallback(routeCallback)
        castContext?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        publisher?.cancel()
        server?.let { s -> Thread { runCatching { s.stop(100, 500) } }.start() }
        server = null
    }

    fun select(tv: CastTv) {
        _error.value = null
        router.routes.firstOrNull { it.id == tv.id }?.let { router.selectRoute(it) }
    }

    /** Also closes the board on the TV. */
    fun disconnect() {
        castContext?.sessionManager?.endCurrentSession(true)
    }

    private fun refreshTvs() {
        _tvs.value = router.routes
            .filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }
            .map { CastTv(it.id, it.name) }
            .sortedBy { it.name }
    }

    private fun attach(session: CastSession) {
        _casting.value = session.castDevice?.friendlyName ?: "TV"
        publisher?.cancel()
        publisher = scope.launch {
            if (!ensureServer()) {
                _error.value = "Could not open a network port on this phone."
                return@launch
            }
            state.map { it.game }.distinctUntilChanged().collectLatest { game ->
                delay(UPDATE_DELAY_MS) // Several quick taps become one update.
                val video = withContext(Dispatchers.Default) {
                    runCatching { BoardVideo.encode(BoardPainter.bitmap(game), videoFile) }.getOrNull()
                }
                if (video == null) {
                    _error.value = "This phone could not make the TV picture."
                    return@collectLatest
                }
                latestVideo = video
                while (true) {
                    load(session)
                    delay(REFRESH_MS)
                }
            }
        }
    }

    private fun detach() {
        publisher?.cancel()
        publisher = null
        _casting.value = null
    }

    private fun load(session: CastSession) {
        val host = localAddressFor(session) ?: return
        version++
        val info = MediaInfo.Builder("http://$host:$serverPort/board/$version.mp4")
            .setContentType("video/mp4")
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setMetadata(MediaMetadata(MediaMetadata.MEDIA_TYPE_GENERIC))
            .build()
        session.remoteMediaClient?.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build())
    }

    /** This phone's address on the same network as the TV, which matters when Wi-Fi and hotspot are both on. */
    private fun localAddressFor(session: CastSession): String? {
        val all = localIpv4Addresses()
        val subnet = session.castDevice?.inetAddress?.hostAddress?.substringBeforeLast('.')
        return all.firstOrNull { it.substringBeforeLast('.') == subnet } ?: all.firstOrNull()
    }

    private suspend fun ensureServer(): Boolean = withContext(Dispatchers.IO) {
        if (server != null) return@withContext true
        for (port in PORTS) {
            val started = runCatching { server = createServer(port).start(wait = false) }.isSuccess
            if (started) {
                serverPort = port
                return@withContext true
            }
        }
        false
    }

    // Outside any CoroutineScope receiver on purpose: inside one, embeddedServer becomes a child
    // of that scope, and the scope then never completes.
    private fun createServer(port: Int) =
        embeddedServer(CIO, port = port, host = "0.0.0.0") {
            routing {
                get("/board/{version}") {
                    call.response.header(HttpHeaders.AccessControlAllowOrigin, "*")
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    call.respondBytes(latestVideo, ContentType.Video.MP4)
                }
            }
        }

    private companion object {
        const val UPDATE_DELAY_MS = 150L
        /** Restarts the video before it runs out, for a board left untouched for hours. */
        const val REFRESH_MS = 5 * 60 * 60 * 1000L
        val PORTS = listOf(8766, 8767, 8768)
    }
}

/**
 * Turns one picture into an MP4 of exactly two frames: the picture at the start and again six hours
 * later. It is tiny and quick to make, and the TV treats it as a video playing the whole time.
 */
internal object BoardVideo {
    private const val DURATION_US = 6L * 60 * 60 * 1_000_000
    private const val BIT_RATE = 16_000_000
    private const val FRAMES_TO_FEED = 8
    private const val FRAME_GAP_MS = 34L
    private const val DEQUEUE_TIMEOUT_US = 50_000L
    private const val MAX_DRAINS = 200

    /** Recycles [bitmap]. Returns the file's bytes. */
    fun encode(bitmap: Bitmap, file: File): ByteArray {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, bitmap.width, bitmap.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0) // Every frame stands alone.
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var surface: Surface? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
            repeat(FRAMES_TO_FEED) {
                surface.lockHardwareCanvas().let { canvas ->
                    canvas.drawBitmap(bitmap, 0f, 0f, null)
                    surface.unlockCanvasAndPost(canvas)
                }
                Thread.sleep(FRAME_GAP_MS)
            }
            codec.signalEndOfInputStream()

            // The encoder's quality settles over the first frames, so the last frame is the one kept.
            val info = MediaCodec.BufferInfo()
            var outputFormat: MediaFormat? = null
            var last: ByteArray? = null
            var drains = 0
            while (drains++ < MAX_DRAINS) {
                val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outputFormat = codec.outputFormat
                if (index < 0) continue
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (info.size > 0 && !isConfig) {
                    val buffer = codec.getOutputBuffer(index)!!
                    buffer.position(info.offset)
                    last = ByteArray(info.size).also { buffer.get(it) }
                }
                val done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(index, false)
                if (done) break
            }
            val frame = checkNotNull(last) { "Encoder produced no frame" }

            val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val track = muxer.addTrack(checkNotNull(outputFormat) { "Encoder gave no format" })
                muxer.start()
                val data = ByteBuffer.wrap(frame)
                listOf(0L, DURATION_US).forEach { time ->
                    data.rewind()
                    info.set(0, frame.size, time, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    muxer.writeSampleData(track, data, info)
                }
                muxer.stop()
            } finally {
                muxer.release()
            }
            return file.readBytes()
        } finally {
            runCatching { codec.stop() }
            codec.release()
            surface?.release()
            bitmap.recycle()
        }
    }
}

/** Draws the board for a 1920 x 1080 TV. All text stays inside a margin that is safe for TV overscan. */
internal object BoardPainter {
    private const val W = 1920f
    private const val H = 1080f
    private const val MARGIN = 96f
    private const val MAX_ROUNDS = 5
    private val White = Color(0xFFF2F2F5).toArgb()
    private val Dim = Color(0xFFC9CAD3).toArgb()
    private val Gold = Color(0xFFFFC107).toArgb()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    @Synchronized
    fun bitmap(game: Game?): Bitmap {
        val bitmap = Bitmap.createBitmap(W.toInt(), H.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        when {
            game == null -> waiting(canvas)
            ScoreEngine.isOver(game) -> gameOver(canvas, game)
            else -> playing(canvas, game)
        }
        return bitmap
    }

    private fun waiting(c: Canvas) {
        c.drawColor(BgPlaying.toArgb())
        c.text("Scorer", W / 2, 500f, 180f, Gold)
        c.text("Waiting for a new game", W / 2, 640f, 64f, Dim, bold = false)
    }

    private fun playing(c: Canvas, game: Game) {
        val tie = ScoreEngine.isTieBreak(game)
        val target = ScoreEngine.targetReachedLive(game)
        c.drawColor((if (tie) BgTie else if (target) BgTarget else BgPlaying).toArgb())

        c.text("Round ${game.rounds.size + 1}", MARGIN, 150f, 72f, White, align = Paint.Align.LEFT, maxWidth = 700f)
        val status = when {
            tie -> "Tied at the top. One more round."
            target -> "Target reached. Finish the round."
            else -> "First to ${game.targetScore}"
        }
        c.text(status, W - MARGIN, 150f, 56f, if (tie || target) Gold else Dim, align = Paint.Align.RIGHT, maxWidth = 1000f)

        val totals = ScoreEngine.liveTotals(game)
        val melded = ScoreEngine.liveMelded(game)
        val columnWidth = (W - 2 * MARGIN - 110f) / game.players.size
        fun x(p: Int) = MARGIN + 110f + columnWidth * (p + 0.5f)
        game.players.forEachIndexed { p, name ->
            c.text(name, x(p), 290f, 76f, colorOfPlayer(p).toArgb(), maxWidth = columnWidth - 40f)
            c.text(totals[p].toString(), x(p), 510f, 210f, White, maxWidth = columnWidth - 40f)
            if (melded[p] > 0) c.text("+${melded[p]} this round", x(p), 590f, 50f, Dim, bold = false, maxWidth = columnWidth - 40f)
        }

        val recent = game.rounds.withIndex().toList().takeLast(MAX_ROUNDS).reversed()
        recent.forEachIndexed { row, (r, round) ->
            val y = 690f + row * 66f
            c.text("R${r + 1}", MARGIN, y, 44f, Dim, bold = false, align = Paint.Align.LEFT)
            game.players.indices.forEach { p ->
                c.text(round.score(p).toString() + if (round.wentOut == p) " *" else "", x(p), y, 50f, White, bold = false)
            }
        }
    }

    private fun gameOver(c: Canvas, game: Game) {
        c.drawColor(BgGameOver.toArgb())
        val totals = ScoreEngine.totals(game)
        val winners = ScoreEngine.leaders(game).joinToString(", ") { game.players[it] }
        c.text("WINNER", W / 2, 220f, 96f, Gold)
        c.text(winners, W / 2, 470f, 230f, White, maxWidth = W - 2 * MARGIN)
        val columnWidth = (W - 2 * MARGIN) / game.players.size
        game.players.forEachIndexed { p, name ->
            val x = MARGIN + columnWidth * (p + 0.5f)
            c.text(name, x, 680f, 70f, Dim, maxWidth = columnWidth - 40f)
            c.text(totals[p].toString(), x, 840f, 150f, White, maxWidth = columnWidth - 40f)
        }
        c.text("Final score after ${roundsLabel(game.rounds.size)}", W / 2, 970f, 50f, Dim, bold = false)
    }

    private fun Canvas.text(
        text: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        bold: Boolean = true,
        align: Paint.Align = Paint.Align.CENTER,
        maxWidth: Float = W - 2 * MARGIN,
    ) {
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.textAlign = align
        paint.color = color
        paint.textSize = size
        val width = paint.measureText(text)
        if (width > maxWidth) paint.textSize = size * maxWidth / width
        drawText(text, x, y, paint)
    }
}
