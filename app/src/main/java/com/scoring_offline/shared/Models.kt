package com.scoring_offline.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Values come from assets/rules.json. Unknown keys (like "_comment") are ignored. See Rules.md. */
@Serializable
data class Rules(
    val gameName: String = "Rummy",
    val minPlayers: Int = 2,
    val maxPlayers: Int = 3,
    /** Target score choices offered when a game starts. */
    val targetOptions: List<Int> = listOf(100, 200),
    /** Cards dealt per player, keyed by player count ("2" -> 13). Informational only. */
    val cardsPerPlayer: Map<String, Int> = mapOf("2" to 13, "3" to 7),
)

/**
 * One finished round. All lists are indexed by player.
 * Round score of player p = melded[p] - hand[p] (see Rules.md section 7).
 */
@Serializable
data class Round(
    val melded: List<Int>,
    val hand: List<Int>,
    /** Index of the player who discarded the last card, or null if not recorded. */
    val wentOut: Int? = null,
) {
    fun score(p: Int): Int = melded.getOrElse(p) { 0 } - hand.getOrElse(p) { 0 }
}

/** Points a player scored by laying cards on the table (live scoring). */
@Serializable
data class Meld(val player: Int, val points: Int)

@Serializable
data class Game(
    val players: List<String>,
    val targetScore: Int,
    /** Finished rounds. */
    val rounds: List<Round> = emptyList(),
    /** Melds laid in the round now in progress, oldest first. */
    val current: List<Meld> = emptyList(),
    val startedAt: Long = 0L,
)

@Serializable
data class FinishedGame(
    val endedAt: Long,
    val gameName: String,
    val targetScore: Int,
    val players: List<String>,
    val totals: List<Int>,
    val winners: List<String>,
    val roundCount: Int,
)

/** Everything the phone stores. The TV gets a copy inside [Snapshot]. */
@Serializable
data class AppState(
    val rules: Rules = Rules(),
    val game: Game? = null,
    val history: List<FinishedGame> = emptyList(),
)

/** One full-state message phone -> TV. TV ignores any message with an older [seq]. */
@Serializable
data class Snapshot(
    val seq: Long,
    val state: AppState,
)

val AppJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    encodeDefaults = true
}

const val TV_PORT = 8765
const val NSD_TYPE = "_gamescore._tcp"

/** The TV answers GET /name with this prefix + its name. Lets the phone find it without mDNS. */
const val NAME_PREFIX = "scoreboard-tv:"
