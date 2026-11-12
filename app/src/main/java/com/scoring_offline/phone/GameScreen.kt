package com.scoring_offline.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scoring_offline.Amber
import com.scoring_offline.Bad
import com.scoring_offline.BgGameOver
import com.scoring_offline.BgPlaying
import com.scoring_offline.BgRoundEnd
import com.scoring_offline.BgTarget
import com.scoring_offline.BgTie
import com.scoring_offline.Good
import com.scoring_offline.colorOfPlayer
import com.scoring_offline.shared.CardScoring
import com.scoring_offline.shared.Game
import com.scoring_offline.shared.ScoreEngine

// Translucent, so panels and keys pick up the state colour behind them.
private val Panel = Color.Black.copy(alpha = 0.32f)
private val KeyFill = Color.White.copy(alpha = 0.13f)
private val Dim = Color.White.copy(alpha = 0.72f)
private val TileShape = RoundedCornerShape(16.dp)
private const val ROUNDS_PER_PAGE = 8

internal fun screenColor(game: Game?, ending: Boolean): Color = when {
    game == null -> BgPlaying
    ScoreEngine.isOver(game) -> BgGameOver
    ending -> BgRoundEnd
    ScoreEngine.isTieBreak(game) -> BgTie
    ScoreEngine.targetReachedLive(game) -> BgTarget
    else -> BgPlaying
}

/** Says in words what the background says in colour. */
internal fun screenTitle(game: Game?, ending: Boolean, newGameTitle: String): String {
    if (game == null) return newGameTitle
    val round = game.rounds.size + 1
    return when {
        ScoreEngine.isOver(game) -> "Game over"
        ending -> "End of round $round"
        ScoreEngine.isTieBreak(game) -> "Round $round · Tied, play on"
        ScoreEngine.targetReachedLive(game) -> "Round $round · Target hit"
        else -> "Round $round · To ${game.targetScore}"
    }
}

internal fun roundsLabel(n: Int) = if (n == 1) "1 round" else "$n rounds"

/** Card keys, laid out like a phone dial pad. Values come from Rules.md section 6. */
private data class CardKey(val label: String, val points: Int, val hint: String? = null)

private val CardRows = listOf(
    listOf(CardKey("A", CardScoring.ACE_LOW, "1"), CardKey("2", 2), CardKey("3", 3)),
    listOf(CardKey("4", 4), CardKey("5", 5), CardKey("6", 6)),
    listOf(CardKey("7", 7), CardKey("8", 8), CardKey("9", 9)),
    listOf(CardKey("10", 10), CardKey("J Q K", CardScoring.value("K"), "10"), CardKey("A", CardScoring.ACE_HIGH, "10")),
)

@Composable
internal fun GameContent(
    game: Game,
    ending: Boolean,
    onEnding: (Boolean) -> Unit,
    onMeld: (player: Int, points: Int) -> Unit,
    onUndoMeld: () -> Unit,
    onEndRound: (hand: List<Int>, wentOut: Int?) -> Unit,
    onReopen: () -> Unit,
    onEdit: (round: Int, player: Int, melded: Int, hand: Int) -> Unit,
    onFinish: () -> Unit,
    onDiscard: () -> Unit,
    onPast: () -> Unit,
) {
    var showRounds by remember { mutableStateOf(false) }

    when {
        showRounds -> {
            BackHandler { showRounds = false }
            RoundsContent(game, onReopen = { showRounds = false; onReopen() }, onEdit = onEdit, onClose = { showRounds = false })
        }
        ScoreEngine.isOver(game) -> GameOverContent(game, onRounds = { showRounds = true }, onPast = onPast, onFinish = onFinish)
        ending -> {
            BackHandler { onEnding(false) }
            EndRoundContent(
                game,
                onConfirm = { hand, out -> onEnding(false); onEndRound(hand, out) },
                onCancel = { onEnding(false) },
            )
        }
        else -> PlayContent(
            game, onMeld, onUndoMeld,
            onEnd = { onEnding(true) }, onRounds = { showRounds = true },
            onPast = onPast, onFinish = onFinish, onDiscard = onDiscard,
        )
    }
}

@Composable
private fun PlayContent(
    game: Game,
    onMeld: (Int, Int) -> Unit,
    onUndoMeld: () -> Unit,
    onEnd: () -> Unit,
    onRounds: () -> Unit,
    onPast: () -> Unit,
    onFinish: () -> Unit,
    onDiscard: () -> Unit,
) {
    val live = ScoreEngine.liveTotals(game)
    val melded = ScoreEngine.liveMelded(game)
    val leaders = ScoreEngine.liveLeaders(game)
    var selected by remember(game.players) { mutableStateOf(0) }
    val pending = remember(game.players) { mutableStateListOf<Int>() }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmFinish by remember { mutableStateOf(false) }
    val sum = pending.sum()
    val last = game.current.lastOrNull()

    Column(Modifier.fillMaxSize().navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(128.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            game.players.forEachIndexed { p, name ->
                PlayerTile(
                    name = name, color = colorOfPlayer(p), big = live[p].toString(),
                    small = if (melded[p] > 0) "+${melded[p]} this round" else "${(game.targetScore - live[p]).coerceAtLeast(0)} to go",
                    selected = p == selected, leading = p in leaders,
                    progress = (live[p].toFloat() / game.targetScore.coerceAtLeast(1)).coerceIn(0f, 1f),
                    modifier = Modifier.weight(1f),
                    onClick = { selected = p; pending.clear() },
                )
            }
        }
        EntryDisplay(
            label = "Meld for ${game.players[selected]}",
            color = colorOfPlayer(selected),
            cards = when {
                pending.isNotEmpty() -> pending.joinToString(" + ")
                last != null -> "Last: ${game.players[last.player]} +${last.points}"
                else -> "Tap the cards of the meld"
            },
            value = if (pending.isEmpty()) "" else "= $sum",
        )
        CardPad(
            modifier = Modifier.weight(1f),
            keysEnabled = true,
            onCard = { pending.add(it) },
            deleteEnabled = pending.isNotEmpty(),
            onDelete = { pending.removeAt(pending.lastIndex) },
            secondLabel = "Undo",
            secondEnabled = game.current.isNotEmpty(),
            onSecond = onUndoMeld,
            confirmLabel = "ADD",
            confirmValue = if (sum > 0) "$sum" else null,
            confirmEnabled = sum > 0,
            onConfirm = { onMeld(selected, sum); pending.clear() },
        )
        Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onRounds, enabled = game.rounds.isNotEmpty(), modifier = Modifier.weight(1f).fillMaxHeight()) {
                Text("Rounds", maxLines = 1)
            }
            Button(
                onClick = onEnd,
                modifier = Modifier.weight(1.5f).fillMaxHeight(),
                colors = ButtonDefaults.buttonColors(containerColor = BgRoundEnd, contentColor = Color.White),
            ) { Text("End round", fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxSize()) { Text("Menu", maxLines = 1) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Past games") }, onClick = { menuOpen = false; onPast() })
                    DropdownMenuItem(text = { Text("Finish game") }, onClick = { menuOpen = false; confirmFinish = true })
                }
            }
        }
    }

    if (confirmFinish) {
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text("Finish game?") },
            text = { Text("The result of the finished rounds is saved to past games and the board is cleared.") },
            confirmButton = { TextButton(onClick = { confirmFinish = false; onFinish() }) { Text("Save & finish") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmFinish = false; onDiscard() }) { Text("Discard game", color = Bad) }
                    TextButton(onClick = { confirmFinish = false }) { Text("Cancel") }
                }
            },
        )
    }
}

/** Hand points are deducted at the end of the round (Rules.md section 7). */
@Composable
private fun EndRoundContent(game: Game, onConfirm: (List<Int>, Int?) -> Unit, onCancel: () -> Unit) {
    val n = game.players.size
    val melded = ScoreEngine.liveMelded(game)
    val hands = remember { List(n) { mutableStateListOf<Int>() } }
    var out by remember { mutableStateOf<Int?>(null) }
    var sel by remember { mutableStateOf(0) }
    val selOut = out == sel

    Column(Modifier.fillMaxSize().navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(156.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            game.players.forEachIndexed { p, name ->
                val hand = if (out == p) 0 else hands[p].sum()
                PlayerTile(
                    name = name, color = colorOfPlayer(p), big = (melded[p] - hand).toString(),
                    small = "${melded[p]} − $hand", selected = p == sel, leading = false, progress = null,
                    modifier = Modifier.weight(1f), onClick = { sel = p },
                ) {
                    val isOut = out == p
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isOut) Good else KeyFill)
                            .clickable {
                                out = p
                                hands[p].clear()
                                if (sel == p) sel = (p + 1) % n
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (isOut) "Went out" else "Out?", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            color = if (isOut) Color.Black else Color.White, maxLines = 1,
                        )
                    }
                }
            }
        }
        EntryDisplay(
            label = if (selOut) "${game.players[sel]} went out" else "Cards left with ${game.players[sel]}",
            color = colorOfPlayer(sel),
            cards = when {
                selOut -> "No cards left in hand"
                hands[sel].isNotEmpty() -> hands[sel].joinToString(" + ")
                out == null -> "Tap Out? on who went out"
                else -> "Tap the cards still in hand"
            },
            value = if (hands[sel].isEmpty() || selOut) "" else "= ${hands[sel].sum()}",
        )
        CardPad(
            modifier = Modifier.weight(1f),
            keysEnabled = !selOut,
            onCard = { hands[sel].add(it) },
            deleteEnabled = hands[sel].isNotEmpty(),
            onDelete = { hands[sel].removeAt(hands[sel].lastIndex) },
            secondLabel = "Clear",
            secondEnabled = hands[sel].isNotEmpty(),
            onSecond = { hands[sel].clear() },
            confirmLabel = "END",
            confirmValue = "ROUND",
            confirmEnabled = out != null,
            onConfirm = { onConfirm(hands.map { it.sum() }, out) },
        )
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Back to the round") }
    }
}

@Composable
private fun GameOverContent(game: Game, onRounds: () -> Unit, onPast: () -> Unit, onFinish: () -> Unit) {
    val totals = ScoreEngine.totals(game)
    val winners = ScoreEngine.leaders(game)
    Column(
        Modifier.fillMaxSize().navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("WINNER", color = Amber, fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 6.sp)
            Text(
                winners.joinToString(", ") { game.players[it] }, color = Color.White, fontWeight = FontWeight.Bold,
                fontSize = 52.sp, lineHeight = 58.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text("Final score after ${roundsLabel(game.rounds.size)}", color = Dim, fontSize = 14.sp)
        }
        Row(Modifier.fillMaxWidth().height(128.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            game.players.forEachIndexed { p, name ->
                PlayerTile(
                    name = name, color = colorOfPlayer(p), big = totals[p].toString(), small = "Final",
                    selected = p in winners, leading = false, progress = null, modifier = Modifier.weight(1f), onClick = {},
                )
            }
        }
        Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRounds, modifier = Modifier.weight(1f).fillMaxHeight()) { Text("Rounds", maxLines = 1) }
            OutlinedButton(onClick = onPast, modifier = Modifier.weight(1f).fillMaxHeight()) { Text("Past games", maxLines = 1) }
        }
        Button(onClick = onFinish, modifier = Modifier.fillMaxWidth().height(60.dp)) {
            Text("Save & new game", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Finished rounds, newest first, one page at a time. */
@Composable
private fun RoundsContent(
    game: Game,
    onReopen: () -> Unit,
    onEdit: (Int, Int, Int, Int) -> Unit,
    onClose: () -> Unit,
) {
    var page by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val newestFirst = game.rounds.indices.reversed().toList()
    val pages = newestFirst.chunked(ROUNDS_PER_PAGE)
    val shown = pages.getOrElse(page.coerceAtMost((pages.size - 1).coerceAtLeast(0))) { emptyList() }

    Column(Modifier.fillMaxSize().navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Spacer(Modifier.width(40.dp))
            game.players.forEachIndexed { p, name ->
                Text(
                    name, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, color = colorOfPlayer(p),
                    fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            shown.forEach { r ->
                val round = game.rounds[r]
                Row(
                    Modifier.fillMaxWidth().weight(1f, fill = false).height(58.dp).clip(RoundedCornerShape(10.dp)).background(Panel),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("R${r + 1}", modifier = Modifier.width(40.dp), textAlign = TextAlign.Center, fontSize = 13.sp, color = Dim)
                    game.players.indices.forEach { p ->
                        Column(
                            Modifier.weight(1f).fillMaxHeight().clickable { editing = r to p },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                round.score(p).toString() + if (round.wentOut == p) " *" else "",
                                fontWeight = FontWeight.Bold, fontSize = 17.sp,
                                color = if (round.score(p) < 0) Bad else Color.Unspecified,
                            )
                            Text("${round.melded[p]} − ${round.hand[p]}", fontSize = 11.sp, color = Dim)
                        }
                    }
                }
            }
        }
        Text("Tap a score to edit it.  * = went out", fontSize = 12.sp, color = Dim)
        if (pages.size > 1) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { page-- }, enabled = page > 0) { Text("Newer") }
                Text("Page ${page + 1} of ${pages.size}", Modifier.weight(1f), textAlign = TextAlign.Center, color = Dim)
                TextButton(onClick = { page++ }, enabled = page < pages.size - 1) { Text("Older") }
            }
        }
        Row(Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onReopen, enabled = game.current.isEmpty() && game.rounds.isNotEmpty(),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) { Text("Reopen last", maxLines = 1) }
            Button(onClick = onClose, modifier = Modifier.weight(1f).fillMaxHeight()) { Text("Close", fontWeight = FontWeight.Bold) }
        }
    }

    editing?.let { (r, p) ->
        val round = game.rounds[r]
        val wasOut = round.wentOut == p
        var m by remember(r, p) { mutableStateOf(round.melded[p].toString()) }
        var h by remember(r, p) { mutableStateOf(round.hand[p].toString()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Round ${r + 1}: ${game.players[p]}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = m, onValueChange = { m = digits(it) }, singleLine = true, label = { Text("Melded points") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = if (wasOut) "0" else h, onValueChange = { h = digits(it) }, singleLine = true,
                        enabled = !wasOut, label = { Text("Points left in hand") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onEdit(r, p, m.toIntOrNull() ?: 0, h.toIntOrNull() ?: 0); editing = null }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PlayerTile(
    name: String,
    color: Color,
    big: String,
    small: String,
    selected: Boolean,
    leading: Boolean,
    progress: Float?,
    modifier: Modifier,
    onClick: () -> Unit,
    footer: @Composable () -> Unit = {},
) {
    Column(
        modifier
            .fillMaxHeight()
            .clip(TileShape)
            .background(if (selected) color.copy(alpha = 0.26f) else Panel)
            .then(if (selected) Modifier.border(3.dp, color, TileShape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            if (leading) "★ $name" else name, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(big, fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
        Text(small, fontSize = 12.sp, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(5.dp),
                color = color, trackColor = Color.White.copy(alpha = 0.15f), drawStopIndicator = {},
            )
        }
        footer()
    }
}

/** The line above the keys, like the number display on a phone dialer. */
@Composable
private fun EntryDisplay(label: String, color: Color, cards: String, value: String) {
    Row(
        Modifier.fillMaxWidth().height(62.dp).clip(TileShape).background(Panel).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Newest cards stay visible when a long meld does not fit.
            Text(cards.takeLast(MAX_ENTRY_CHARS), color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (value.isNotEmpty()) {
            Spacer(Modifier.width(10.dp))
            Text(value, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

private const val MAX_ENTRY_CHARS = 30

/**
 * Dial-pad card keys on the left, actions on the right. Every cell is an equal share of the height,
 * so the pad fills whatever space the phone has and never scrolls.
 */
@Composable
private fun CardPad(
    modifier: Modifier,
    keysEnabled: Boolean,
    onCard: (Int) -> Unit,
    deleteEnabled: Boolean,
    onDelete: () -> Unit,
    secondLabel: String,
    secondEnabled: Boolean,
    onSecond: () -> Unit,
    confirmLabel: String,
    confirmValue: String?,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
) {
    Row(modifier.fillMaxWidth()) {
        Column(Modifier.weight(3f).fillMaxHeight()) {
            CardRows.forEach { row ->
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    row.forEach { key ->
                        PadKey(Modifier.weight(1f), KeyFill, keysEnabled, { onCard(key.points) }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(key.label, fontSize = if (key.label.length > 2) 21.sp else 30.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                                key.hint?.let { Text("= $it", fontSize = 12.sp, color = Dim) }
                            }
                        }
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            PadKey(Modifier.weight(1f).fillMaxWidth(), Color.White.copy(alpha = 0.22f), deleteEnabled, onDelete) {
                Text("Del", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            PadKey(Modifier.weight(1f).fillMaxWidth(), Color.White.copy(alpha = 0.22f), secondEnabled, onSecond) {
                Text(secondLabel, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            PadKey(Modifier.weight(2f).fillMaxWidth(), Amber, confirmEnabled, onConfirm) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(confirmLabel, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    if (confirmValue != null) {
                        Text(
                            confirmValue, fontSize = if (confirmValue.length > 3) 18.sp else 30.sp,
                            fontWeight = FontWeight.Bold, color = Color.Black, maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PadKey(
    modifier: Modifier,
    fill: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier
            .fillMaxHeight()
            .padding(3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) fill else fill.copy(alpha = fill.alpha * 0.4f))
            .clickable(enabled = enabled) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) { content() }
}
