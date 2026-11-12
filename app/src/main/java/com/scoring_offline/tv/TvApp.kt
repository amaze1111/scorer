package com.scoring_offline.tv

import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scoring_offline.shared.AppState
import com.scoring_offline.shared.FinishedGame
import com.scoring_offline.shared.Game
import com.scoring_offline.shared.ScoreEngine
import com.scoring_offline.shared.TV_PORT
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val Gold = Color(0xFFFFC107)
private val Green = Color(0xFF4CAF50)
private val Dim = Color(0xFFB0B0B0)
private val Panel = Color(0xFF1C1C1E)
private val TABS = listOf("Live", "Rounds", "Past games")

@Composable
fun TvApp(vm: TvViewModel = viewModel()) {
    val snapshot by vm.snapshot.collectAsState()
    val clients by vm.clients.collectAsState()
    val error by vm.serverError.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tab by remember { mutableIntStateOf(0) }
    var lastBack by remember { mutableLongStateOf(0L) }
    var shift by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }

    // Back must be pressed twice, so a stray press does not close the scoreboard.
    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBack < 2_000) {
            (context as? android.app.Activity)?.finish()
        } else {
            lastBack = now
            Toast.makeText(context, "Press Back again to exit", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) { focus.requestFocus() }
    // Slow layout drift (OLED burn-in protection).
    LaunchedEffect(Unit) {
        val steps = listOf(0, 8, 16, 8)
        var i = 0
        while (true) {
            delay(60_000)
            i = (i + 1) % steps.size
            shift = steps[i]
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key.nativeKeyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { tab = (tab + TABS.size - 1) % TABS.size; true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { tab = (tab + 1) % TABS.size; true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { scope.launch { listState.animateScrollBy(300f) }; true }
                    KeyEvent.KEYCODE_DPAD_UP -> { scope.launch { listState.animateScrollBy(-300f) }; true }
                    else -> false
                }
            },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = (48 + shift).dp, end = 48.dp, top = (32 + shift).dp, bottom = 32.dp),
        ) {
            val state = snapshot?.state
            if (state == null) {
                WaitingScreen(vm.tvName, vm.ipAddress, error)
            } else {
                TopBar(tab, clients > 0, state.rules.gameName)
                Spacer(Modifier.height(16.dp))
                when (tab) {
                    0 -> LiveTab(state)
                    1 -> RoundsTab(state.game, listState)
                    else -> PastTab(state.history, listState)
                }
            }
        }
    }
}

@Composable
private fun WaitingScreen(name: String, ip: String, error: String?) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Waiting for phone…", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(16.dp))
        Text("Open the Scorer app on your phone (same Wi-Fi).", fontSize = 24.sp, color = Dim)
        Spacer(Modifier.height(8.dp))
        Text("This TV is called:  ${name.removePrefix("Scoreboard-").replace('-', ' ')}", fontSize = 24.sp, color = Gold)
        Spacer(Modifier.height(8.dp))
        Text("If it does not connect, enter this address on the phone:", fontSize = 20.sp, color = Dim)
        Text("$ip:$TV_PORT", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Gold)
        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error, fontSize = 20.sp, color = Color(0xFFF44336))
        }
        Spacer(Modifier.height(32.dp))
        Text(
            "TV setup tip: set Settings > System > Power & Energy > Energy saver to Never,\n" +
                "otherwise the TV may switch off during long pauses.",
            fontSize = 18.sp, color = Dim, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TopBar(tab: Int, connected: Boolean, title: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
        TABS.forEachIndexed { i, name ->
            Text(
                name, fontSize = 22.sp, fontWeight = if (i == tab) FontWeight.Bold else FontWeight.Normal,
                color = if (i == tab) Gold else Dim,
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .background(if (i == tab) Panel else Color.Transparent, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.width(24.dp))
        Text(
            if (connected) "● Phone connected" else "○ Phone offline",
            fontSize = 18.sp, color = if (connected) Green else Color(0xFFF44336),
        )
    }
}

@Composable
private fun LiveTab(state: AppState) {
    val game = state.game
    if (game == null) {
        // Keep the last result on screen after the phone finishes the game.
        val lastGame = state.history.firstOrNull()
        if (lastGame != null) {
            ResultView(
                lastGame.players, lastGame.totals, lastGame.winners,
                "Final score  ·  ${lastGame.roundCount} rounds  ·  target ${lastGame.targetScore}",
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No game in progress", fontSize = 40.sp, color = Dim)
            }
        }
        return
    }
    val over = ScoreEngine.isOver(game)
    if (over) {
        ResultView(
            game.players, ScoreEngine.totals(game),
            ScoreEngine.leaders(game).map { game.players[it] },
            "Final score  ·  ${game.rounds.size} rounds  ·  target ${game.targetScore}",
        )
        return
    }
    val totals = ScoreEngine.liveTotals(game)
    val liveMelded = ScoreEngine.liveMelded(game)
    val leaders = if (over) ScoreEngine.leaders(game) else ScoreEngine.liveLeaders(game)
    val ranking = ScoreEngine.liveRanking(game)
    val inProgress = game.current.isNotEmpty()
    val last = game.rounds.lastOrNull()

    val subtitle = when {
        over -> "Game over — " + leaders.joinToString(", ") { game.players[it] } + " wins"
        ScoreEngine.isTieBreak(game) -> "Tied at the top — one more round"
        ScoreEngine.targetReachedLive(game) -> "Target ${game.targetScore} reached — round finishes first"
        else -> "Round ${game.rounds.size + 1}  ·  Target ${game.targetScore}"
    }
    Text(subtitle, fontSize = 26.sp, color = if (over) Gold else Dim)
    Spacer(Modifier.height(12.dp))

    // Rows share the space equally, so any player count fits without scrolling.
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ranking.forEachIndexed { pos, p ->
            val isLeader = p in leaders
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Panel, RoundedCornerShape(14.dp))
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${pos + 1}", fontSize = 36.sp, color = Dim, modifier = Modifier.width(56.dp))
                Text(
                    game.players[p], fontSize = 40.sp, fontWeight = FontWeight.Bold,
                    color = if (isLeader) Gold else Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (inProgress) {
                    Text(
                        "+${liveMelded[p]} this round", fontSize = 28.sp, color = Green,
                        modifier = Modifier.padding(end = 32.dp),
                    )
                } else if (last != null) {
                    val d = last.score(p)
                    Text(
                        (if (d >= 0) "+$d" else "$d") + if (last.wentOut == p) " OUT" else "",
                        fontSize = 30.sp, color = if (d >= 0) Green else Color(0xFFF44336),
                        modifier = Modifier.padding(end = 32.dp),
                    )
                }
                Text(
                    totals[p].toString(), fontSize = 64.sp, fontWeight = FontWeight.Bold,
                    color = if (isLeader) Gold else Color.White,
                )
            }
        }
        if (inProgress) {
            Text(
                "Latest: " + game.current.takeLast(4).reversed().joinToString("     ") { "${game.players[it.player]} +${it.points}" },
                fontSize = 22.sp, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RoundsTab(game: Game?, listState: androidx.compose.foundation.lazy.LazyListState) {
    if (game == null || (game.rounds.isEmpty() && game.current.isEmpty())) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No rounds yet", fontSize = 40.sp, color = Dim)
        }
        return
    }
    val totals = ScoreEngine.liveTotals(game)
    val liveMelded = ScoreEngine.liveMelded(game)
    Column(Modifier.fillMaxSize()) {
        TableRow("", game.players, null, bold = true, color = Gold)
        TableRow("Total", totals.map { it.toString() }, null, bold = true, color = Color.White)
        if (game.current.isNotEmpty()) {
            TableRow("Now", liveMelded.map { "+$it" }, null, bold = false, color = Gold)
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(game.rounds.indices.reversed().toList()) { r ->
                val round = game.rounds[r]
                TableRow(
                    "R${r + 1}",
                    game.players.indices.map { round.score(it).toString() + if (round.wentOut == it) " *" else "" },
                    game.players.indices.map { "${round.melded.getOrElse(it) { 0 }} − ${round.hand.getOrElse(it) { 0 }}" },
                    bold = false, color = if (r == game.rounds.lastIndex) Green else Dim,
                )
            }
        }
    }
}

@Composable
private fun TableRow(label: String, cells: List<String>, sub: List<String>?, bold: Boolean, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 24.sp, color = Dim, modifier = Modifier.width(100.dp))
        cells.forEachIndexed { i, text ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text, fontSize = 30.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                    color = color, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (sub != null) Text(sub[i], fontSize = 16.sp, color = Dim)
            }
        }
    }
}

/** Game over: WINNER banner and final standings, best first. */
@Composable
private fun ResultView(players: List<String>, totals: List<Int>, winners: List<String>, subtitle: String) {
    val order = totals.indices.sortedByDescending { totals[it] }
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Text(if (winners.size > 1) "WINNERS" else "WINNER", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Gold)
        Text(
            winners.joinToString("  &  "), fontSize = 88.sp, fontWeight = FontWeight.Bold, color = Color.White,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(subtitle, fontSize = 24.sp, color = Dim)
        Spacer(Modifier.height(4.dp))
        order.forEachIndexed { pos, p ->
            val isWinner = players[p] in winners
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Panel, RoundedCornerShape(14.dp))
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${pos + 1}", fontSize = 36.sp, color = Dim, modifier = Modifier.width(56.dp))
                Text(
                    players[p], fontSize = 40.sp, fontWeight = FontWeight.Bold,
                    color = if (isWinner) Gold else Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Text(
                    totals[p].toString(), fontSize = 64.sp, fontWeight = FontWeight.Bold,
                    color = if (isWinner) Gold else Color.White,
                )
            }
        }
    }
}

@Composable
private fun PastTab(history: List<FinishedGame>, listState: androidx.compose.foundation.lazy.LazyListState) {
    if (history.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No finished games yet", fontSize = 40.sp, color = Dim)
        }
        return
    }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        items(history) { g ->
            Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).padding(20.dp)) {
                Text("${fmt.format(Date(g.endedAt))}  ·  ${g.roundCount} rounds  ·  target ${g.targetScore}", fontSize = 20.sp, color = Dim)
                Text(
                    g.players.indices.joinToString("     ") { "${g.players[it]} ${g.totals[it]}" },
                    fontSize = 28.sp, color = Color.White,
                )
                Text("Winner: " + g.winners.joinToString(", "), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Gold)
            }
        }
    }
}
