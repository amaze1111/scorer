package com.scoring_offline.phone

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scoring_offline.Amber
import com.scoring_offline.Bad
import com.scoring_offline.Good
import com.scoring_offline.shared.FinishedGame
import com.scoring_offline.shared.Rules
import java.text.DateFormat
import java.util.Date

internal fun digits(v: String) = v.filter { it.isDigit() }.take(4)

@Composable
fun PhoneApp(vm: PhoneViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val status by vm.link.status.collectAsState()
    val tvs by vm.link.tvs.collectAsState()
    val current by vm.link.currentTv.collectAsState()
    val preferred by vm.link.preferredName.collectAsState()
    val manual by vm.link.manualHost.collectAsState()
    val scanning by vm.link.scanning.collectAsState()
    val castTvs by vm.cast.tvs.collectAsState()
    val casting by vm.cast.casting.collectAsState()
    val castError by vm.cast.error.collectAsState()

    var showLink by remember { mutableStateOf(false) }
    var showPast by remember { mutableStateOf(false) }
    var showInstall by remember { mutableStateOf(false) }
    var ending by remember { mutableStateOf(false) }
    val game = state.game

    // A game that ended elsewhere must not leave the end-of-round screen behind.
    LaunchedEffect(game == null) { if (game == null) ending = false }
    // Several TVs on the network and none remembered: ask which one.
    LaunchedEffect(status) { if (status == LinkStatus.CHOOSE_TV) showLink = true }

    val background by animateColorAsState(screenColor(game, ending), label = "state background")
    // Edge-to-edge: the Scaffold background also fills the area behind the system bars.

    Scaffold(containerColor = background) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .consumeWindowInsets(pad)
                .imePadding()
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Header(screenTitle(game, ending, "New game"), status, current?.name, casting) { showLink = true }
            Spacer(Modifier.height(6.dp))
            if (showInstall) {
                BackHandler { showInstall = false }
                InstallTvScreen(onClose = { showInstall = false })
            } else if (game == null) {
                SetupContent(state.rules, onStart = vm::startGame, onPast = { showPast = true })
            } else {
                GameContent(
                    game = game,
                    ending = ending, onEnding = { ending = it },
                    onMeld = vm::addMeld, onUndoMeld = vm::undoMeld,
                    onEndRound = vm::endRound, onReopen = vm::reopenLastRound, onEdit = vm::editCell,
                    onFinish = vm::finishGame, onDiscard = vm::discardGame, onPast = { showPast = true },
                )
            }
        }
    }

    if (showLink) {
        LinkDialog(
            tvs = tvs, preferred = preferred, connectedName = if (status == LinkStatus.CONNECTED) current?.name else null,
            manual = manual?.toString().orEmpty(), scanning = scanning,
            castTvs = castTvs, casting = casting, castError = castError,
            onCast = { vm.cast.select(it); showLink = false },
            onStopCast = { vm.cast.disconnect() },
            onSelect = { vm.link.selectTv(it); showLink = false },
            onManual = { vm.link.setManualHost(it); showLink = false },
            onRescan = vm.link::rescan,
            onInstallNew = { showLink = false; showInstall = true },
            onDismiss = { showLink = false },
        )
    }
    if (showPast) PastGamesDialog(state.history) { showPast = false }
}

@Composable
private fun Header(title: String, status: LinkStatus, tvName: String?, casting: String?, onLinkClick: () -> Unit) {
    val (text, color) = when {
        casting != null -> "On TV" to Good
        status == LinkStatus.CONNECTED -> (tvName?.removePrefix("Scoreboard-")?.replace('-', ' ')?.let { "TV: $it" } ?: "TV connected") to Good
        status == LinkStatus.DISCONNECTED -> "Reconnecting…" to Bad
        status == LinkStatus.CONNECTING -> "Connecting…" to Amber
        else -> "Connect TV" to Amber
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.32f),
            modifier = Modifier.clickable(onClick = onLinkClick),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(color, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White, modifier = Modifier.widthIn(max = 130.dp))
            }
        }
    }
}

@Composable
private fun SetupContent(rules: Rules, onStart: (List<String>, Int) -> Unit, onPast: () -> Unit) {
    val names = remember { mutableStateListOf("Player 1", "Player 2") }
    var target by remember { mutableStateOf((rules.targetOptions.firstOrNull() ?: 100).toString()) }
    val cards = rules.cardsPerPlayer[names.size.toString()]

    fun setCount(c: Int) {
        while (names.size < c) names.add("Player ${names.size + 1}")
        while (names.size > c) names.removeAt(names.lastIndex)
    }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("New game", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        Text("Players", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in rules.minPlayers..rules.maxPlayers) {
                val pick = { setCount(c) }
                val mod = Modifier.weight(1f).height(48.dp)
                if (names.size == c) Button(onClick = pick, modifier = mod) { Text("$c players") }
                else OutlinedButton(onClick = pick, modifier = mod) { Text("$c players") }
            }
        }
        if (cards != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Deal $cards cards to each player", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onPrimaryContainer) }
        }
        names.forEachIndexed { i, n ->
            OutlinedTextField(
                value = n, onValueChange = { names[i] = it.take(20) }, singleLine = true,
                label = { Text("Player ${i + 1} name") }, modifier = Modifier.fillMaxWidth(),
            )
        }

        Text("Target score", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            rules.targetOptions.forEach { t ->
                val mod = Modifier.height(48.dp)
                if (target == t.toString()) Button(onClick = { target = t.toString() }, modifier = mod) { Text("$t") }
                else OutlinedButton(onClick = { target = t.toString() }, modifier = mod) { Text("$t") }
            }
            OutlinedTextField(
                value = target, onValueChange = { target = digits(it) }, singleLine = true,
                label = { Text("Custom") }, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }

        val targetValue = target.toIntOrNull() ?: 0
        Button(
            onClick = { onStart(names.mapIndexed { i, n -> n.trim().ifEmpty { "Player ${i + 1}" } }, targetValue) },
            enabled = names.size >= rules.minPlayers && targetValue > 0,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Start game", fontSize = 18.sp) }
        TextButton(onClick = onPast, modifier = Modifier.fillMaxWidth()) { Text("Past games") }
    }
}

@Composable
private fun LinkDialog(
    tvs: List<TvInfo>,
    preferred: String?,
    connectedName: String?,
    manual: String,
    scanning: Boolean,
    castTvs: List<CastTv>,
    casting: String?,
    castError: String?,
    onCast: (CastTv) -> Unit,
    onStopCast: () -> Unit,
    onSelect: (TvInfo) -> Unit,
    onManual: (String) -> Unit,
    onRescan: () -> Unit,
    onInstallNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(manual) }
    var more by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Show on a TV") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                castError?.let { Text(it, color = Bad, fontSize = 13.sp) }
                if (casting != null) {
                    Text("Showing on $casting", fontWeight = FontWeight.Bold, color = Good)
                    OutlinedButton(onClick = onStopCast) { Text("Stop showing on TV") }
                } else {
                    Text(
                        "Tap your TV. Nothing to install. Phone and TV must be on the same Wi-Fi.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (castTvs.isEmpty()) Text("Looking for TVs…", fontWeight = FontWeight.Bold)
                    castTvs.forEach { tv ->
                        Button(onClick = { onCast(tv) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            Text(tv.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (tvs.isNotEmpty()) {
                    Text("TVs with the Scorer app", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                tvs.forEach { tv ->
                    val selected = manual.isEmpty() && (tv.name == connectedName || (connectedName == null && tv.name == preferred))
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(tv) }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = { onSelect(tv) })
                        Column {
                            Text(tv.name.removePrefix("Scoreboard-").replace('-', ' '), fontWeight = FontWeight.Bold)
                            Text(tv.host.toString(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                TextButton(onClick = { more = !more }) { Text(if (more) "Fewer options" else "More options") }
                if (more) {
                    OutlinedButton(onClick = onRescan, enabled = !scanning) { Text(if (scanning) "Scanning…" else "Search for Scorer app") }
                    OutlinedButton(onClick = onInstallNew) { Text("Install Scorer app on a TV") }
                    OutlinedTextField(
                        value = text, onValueChange = { text = it }, singleLine = true,
                        label = { Text("Scorer app address") }, modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { onManual(text) }) { Text(if (text.isBlank()) "Use automatic" else "Use address") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun PastGamesDialog(history: List<FinishedGame>, onDismiss: () -> Unit) {
    var page by remember { mutableStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Past games") },
        text = {
            if (history.isEmpty()) {
                Text("No finished games yet.")
            } else {
                val pages = history.chunked(4)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    pages[page.coerceAtMost(pages.lastIndex)].forEach { g ->
                        Column {
                            Text(
                                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(g.endedAt)) +
                                    " · ${g.roundCount} rounds · target ${g.targetScore}",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(g.players.indices.joinToString("   ") { "${g.players[it]} ${g.totals[it]}" })
                            Text("Winner: " + g.winners.joinToString(", "), fontWeight = FontWeight.Bold, color = Amber)
                        }
                    }
                    if (pages.size > 1) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { page-- }, enabled = page > 0) { Text("Newer") }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { page++ }, enabled = page < pages.lastIndex) { Text("Older") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
