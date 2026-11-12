package com.scoring_offline.phone

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scoring_offline.shared.AppJson
import com.scoring_offline.shared.AppState
import com.scoring_offline.shared.Game
import com.scoring_offline.shared.GameOps
import com.scoring_offline.shared.Rules
import com.scoring_offline.shared.ScoreEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.io.File

/** Phone owns all data. Every change is saved to disk, then pushed to the TV by [TvLink]. */
class PhoneViewModel(app: Application) : AndroidViewModel(app) {

    private val file = File(app.filesDir, "state.json")

    private val _state = MutableStateFlow(load(app))
    val state: StateFlow<AppState> = _state

    val link = TvLink(app, viewModelScope, _state).also { it.start() }
    val cast = CastLink(app, viewModelScope, _state).also { it.start() }

    private fun load(app: Application): AppState {
        // Rules always come from the bundled file, so editing rules.json + reinstall takes effect.
        val rules = runCatching {
            app.assets.open("rules.json").bufferedReader().use { AppJson.decodeFromString<Rules>(it.readText()) }
        }.getOrDefault(Rules())
        val saved = runCatching { AppJson.decodeFromString<AppState>(file.readText()) }.getOrNull() ?: AppState()
        return saved.copy(rules = rules)
    }

    private fun change(block: (AppState) -> AppState) {
        _state.update(block)
        val snapshot = _state.value
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val tmp = File(file.parentFile, "state.json.tmp")
                tmp.writeText(AppJson.encodeToString(snapshot))
                if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
            }
        }
    }

    fun startGame(players: List<String>, targetScore: Int) = change {
        it.copy(game = Game(players = players, targetScore = targetScore, startedAt = System.currentTimeMillis()))
    }

    fun addMeld(player: Int, points: Int) = change { s ->
        s.game?.let { s.copy(game = GameOps.addMeld(it, player, points)) } ?: s
    }

    fun undoMeld() = change { s -> s.game?.let { s.copy(game = GameOps.undoMeld(it)) } ?: s }

    fun endRound(hand: List<Int>, wentOut: Int?) = change { s ->
        s.game?.let { s.copy(game = GameOps.endRound(it, hand, wentOut)) } ?: s
    }

    fun reopenLastRound() = change { s -> s.game?.let { s.copy(game = GameOps.reopenLastRound(it)) } ?: s }

    fun editCell(round: Int, player: Int, melded: Int, hand: Int) = change { s ->
        s.game?.let { s.copy(game = GameOps.editCell(it, round, player, melded, hand)) } ?: s
    }

    /** Archive the game (if it has rounds) and clear the board. */
    fun finishGame() = change { s ->
        val g = s.game ?: return@change s
        val history = if (g.rounds.isEmpty()) s.history
        else listOf(ScoreEngine.finish(g, s.rules, System.currentTimeMillis())) + s.history
        s.copy(game = null, history = history)
    }

    fun discardGame() = change { it.copy(game = null) }

    override fun onCleared() {
        link.stop()
        cast.stop()
    }
}
