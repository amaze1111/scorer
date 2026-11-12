package com.scoring_offline.shared

/** Card values from Rules.md section 6. */
object CardScoring {
    const val ACE_LOW = 1
    const val ACE_HIGH = 10

    /** Value of a card by rank: "2".."10", "J", "Q", "K", "A". Ace returns the high value unless [aceLow]. */
    fun value(rank: String, aceLow: Boolean = false): Int = when (rank.uppercase()) {
        "A" -> if (aceLow) ACE_LOW else ACE_HIGH
        "J", "Q", "K" -> 10
        else -> rank.toIntOrNull()?.takeIf { it in 2..10 } ?: 0
    }

    fun handValue(ranks: List<String>, aceLow: Boolean = false): Int = ranks.sumOf { value(it, aceLow) }
}

/**
 * Pure scoring logic. No Android classes, so it is unit-tested on the JVM.
 * Implements Rules.md sections 7 and 8.
 *
 * "Committed" values use finished rounds only. "Live" values also include melds of the round in progress.
 */
object ScoreEngine {

    /** Totals of finished rounds. The game result is decided on these only (Rules.md section 8). */
    fun totals(game: Game): List<Int> =
        game.players.indices.map { p -> game.rounds.sumOf { it.score(p) } }

    /** Melded points per player in the round in progress. */
    fun liveMelded(game: Game): List<Int> =
        game.players.indices.map { p -> game.current.filter { it.player == p }.sumOf { it.points } }

    /** Finished rounds + melds so far this round. Hand points are not deducted until the round ends. */
    fun liveTotals(game: Game): List<Int> =
        totals(game).zip(liveMelded(game)) { a, b -> a + b }

    private fun leadersOf(totals: List<Int>): List<Int> {
        if (totals.isEmpty()) return emptyList()
        val best = totals.max()
        return totals.indices.filter { totals[it] == best }
    }

    /** Leaders of finished rounds. Empty before the first round. Ties return every tied player. */
    fun leaders(game: Game): List<Int> =
        if (game.rounds.isEmpty()) emptyList() else leadersOf(totals(game))

    /** Leaders by live total. Empty until something is scored. */
    fun liveLeaders(game: Game): List<Int> =
        if (game.rounds.isEmpty() && game.current.isEmpty()) emptyList() else leadersOf(liveTotals(game))

    /**
     * Game is over when a finished round leaves someone at or above the target
     * and exactly one player is highest. A tie at the top means play another round.
     * A live total passing the target mid-round does not end the game.
     */
    fun isOver(game: Game): Boolean {
        if (game.rounds.isEmpty()) return false
        return totals(game).any { it >= game.targetScore } && leaders(game).size == 1
    }

    /** True when the target is reached in finished rounds but a tie at the top forces another round. */
    fun isTieBreak(game: Game): Boolean {
        if (game.rounds.isEmpty()) return false
        return totals(game).any { it >= game.targetScore } && leaders(game).size > 1
    }

    /** True when a live total is at or above the target. The round must still be finished. */
    fun targetReachedLive(game: Game): Boolean = liveTotals(game).any { it >= game.targetScore }

    /** Player indices by live total, best first. Equal totals keep seating order. */
    fun liveRanking(game: Game): List<Int> {
        val t = liveTotals(game)
        return t.indices.sortedByDescending { t[it] }
    }

    fun finish(game: Game, rules: Rules, now: Long): FinishedGame =
        FinishedGame(
            endedAt = now,
            gameName = rules.gameName,
            targetScore = game.targetScore,
            players = game.players,
            totals = totals(game),
            winners = leaders(game).map { game.players[it] },
            roundCount = game.rounds.size,
        )
}

/** Edits on a [Game]. Each returns a new game, so state updates stay simple. */
object GameOps {

    /** Credit [points] to [player] now. Ignored for an unknown player or points of 0 or less. */
    fun addMeld(game: Game, player: Int, points: Int): Game =
        if (player !in game.players.indices || points <= 0) game
        else game.copy(current = game.current + Meld(player, points))

    fun undoMeld(game: Game): Game =
        if (game.current.isEmpty()) game else game.copy(current = game.current.dropLast(1))

    /**
     * Finish the round in progress. Melded points come from the live melds; [hand] is deducted now.
     * The player who went out has 0 hand points (Rules.md section 5).
     */
    fun endRound(game: Game, hand: List<Int>, wentOut: Int?): Game {
        val n = game.players.size
        val out = wentOut?.takeIf { it in 0 until n }
        val melded = ScoreEngine.liveMelded(game)
        val h = List(n) { if (it == out) 0 else maxOf(0, hand.getOrElse(it) { 0 }) }
        return game.copy(rounds = game.rounds + Round(melded, h, out), current = emptyList())
    }

    /** Take the last finished round back into the live round, so a mistake can be fixed. Hand points are dropped. */
    fun reopenLastRound(game: Game): Game {
        val last = game.rounds.lastOrNull() ?: return game
        val melds = last.melded.mapIndexedNotNull { p, pts -> if (pts > 0) Meld(p, pts) else null }
        return game.copy(rounds = game.rounds.dropLast(1), current = melds + game.current)
    }

    fun editCell(game: Game, round: Int, player: Int, melded: Int, hand: Int): Game {
        if (round !in game.rounds.indices || player !in game.players.indices) return game
        val r = game.rounds[round]
        val m = r.melded.toMutableList()
        val h = r.hand.toMutableList()
        while (m.size < game.players.size) m.add(0)
        while (h.size < game.players.size) h.add(0)
        m[player] = maxOf(0, melded)
        h[player] = if (r.wentOut == player) 0 else maxOf(0, hand)
        val rounds = game.rounds.toMutableList()
        rounds[round] = r.copy(melded = m, hand = h)
        return game.copy(rounds = rounds)
    }
}
