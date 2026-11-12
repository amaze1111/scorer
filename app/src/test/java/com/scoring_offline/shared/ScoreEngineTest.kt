package com.scoring_offline.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreEngineTest {

    private val players = listOf("A", "B", "C")
    private fun newGame(target: Int = 100) = Game(players, target)

    /** Lay one meld per player (their melded total), then end the round with the given hand points. */
    private fun Game.round(m: List<Int>, h: List<Int>, out: Int? = null): Game {
        var g = this
        m.forEachIndexed { p, pts -> g = GameOps.addMeld(g, p, pts) }
        return GameOps.endRound(g, h, out)
    }

    // ---- Rules.md section 6: card values ----
    @Test fun cardValues() {
        assertEquals(2, CardScoring.value("2"))
        assertEquals(10, CardScoring.value("10"))
        assertEquals(10, CardScoring.value("J"))
        assertEquals(10, CardScoring.value("q"))
        assertEquals(10, CardScoring.value("K"))
        assertEquals(1, CardScoring.value("A", aceLow = true))
        assertEquals(10, CardScoring.value("A", aceLow = false))
        assertEquals(0, CardScoring.value("X"))
        assertEquals(0, CardScoring.value("1"))
        assertEquals(0, CardScoring.value("11"))
    }

    @Test fun handValueSumsCards() {
        assertEquals(2 + 10 + 10 + 10, CardScoring.handValue(listOf("2", "10", "Q", "K")))
        assertEquals(1 + 2 + 3, CardScoring.handValue(listOf("A", "2", "3"), aceLow = true))
        assertEquals(10 + 2 + 3, CardScoring.handValue(listOf("A", "2", "3"), aceLow = false))
    }

    // ---- Rules.md section 7: live scoring ----
    @Test fun meldScoresImmediately() {
        var g = newGame()
        g = GameOps.addMeld(g, 1, 12)
        g = GameOps.addMeld(g, 1, 5)
        g = GameOps.addMeld(g, 0, 9)
        assertEquals(listOf(9, 17, 0), ScoreEngine.liveMelded(g))
        assertEquals(listOf(9, 17, 0), ScoreEngine.liveTotals(g))
        assertEquals(listOf(0, 0, 0), ScoreEngine.totals(g)) // nothing finished yet
        assertEquals(listOf(1), ScoreEngine.liveLeaders(g))
    }

    @Test fun liveTotalsAddToFinishedRounds() {
        var g = newGame().round(listOf(30, 20, 0), listOf(0, 8, 14), out = 0) // totals 30, 12, -14
        g = GameOps.addMeld(g, 2, 25)
        assertEquals(listOf(30, 12, 11), ScoreEngine.liveTotals(g))
        assertEquals(listOf(30, 12, -14), ScoreEngine.totals(g))
    }

    @Test fun addMeldIgnoresBadInput() {
        val g = newGame()
        assertEquals(g, GameOps.addMeld(g, 5, 10))
        assertEquals(g, GameOps.addMeld(g, -1, 10))
        assertEquals(g, GameOps.addMeld(g, 0, 0))
        assertEquals(g, GameOps.addMeld(g, 0, -3))
    }

    @Test fun undoMeldRemovesOnlyLastMeld() {
        var g = GameOps.addMeld(GameOps.addMeld(newGame(), 0, 5), 1, 7)
        g = GameOps.undoMeld(g)
        assertEquals(listOf(Meld(0, 5)), g.current)
        assertTrue(GameOps.undoMeld(newGame()).current.isEmpty())
    }

    @Test fun noLeadersBeforeAnyScore() {
        assertTrue(ScoreEngine.liveLeaders(newGame()).isEmpty())
        assertTrue(ScoreEngine.leaders(newGame()).isEmpty())
        assertFalse(ScoreEngine.isOver(newGame()))
    }

    // ---- Rules.md section 7: round score = melded - hand ----
    @Test fun roundScoreIsMeldedMinusHand() {
        val g = newGame().round(m = listOf(30, 20, 0), h = listOf(0, 8, 14), out = 0)
        assertEquals(listOf(30, 12, -14), ScoreEngine.totals(g))
    }

    @Test fun endRoundCommitsLiveMeldsAndClearsThem() {
        var g = GameOps.addMeld(GameOps.addMeld(newGame(), 0, 10), 0, 6)
        g = GameOps.endRound(g, listOf(0, 4, 2), wentOut = 0)
        assertTrue(g.current.isEmpty())
        assertEquals(1, g.rounds.size)
        assertEquals(listOf(16, 0, 0), g.rounds[0].melded)
        assertEquals(listOf(0, 4, 2), g.rounds[0].hand)
        assertEquals(0, g.rounds[0].wentOut)
        assertEquals(ScoreEngine.totals(g), ScoreEngine.liveTotals(g))
    }

    @Test fun totalsAccumulateAndCanBeNegative() {
        val g = newGame()
            .round(listOf(10, 0, 0), listOf(0, 5, 5), out = 0)
            .round(listOf(0, 0, 0), listOf(0, 20, 0), out = 2)
        assertEquals(listOf(10, -25, -5), ScoreEngine.totals(g))
    }

    @Test fun noPenaltyForPlayerWithNoMeldBeyondHandPoints() {
        val g = newGame().round(listOf(20, 0, 0), listOf(0, 0, 0), out = 0)
        assertEquals(listOf(20, 0, 0), ScoreEngine.totals(g)) // B and C laid nothing, held nothing: no penalty
    }

    @Test fun playerWhoWentOutHasZeroHand() {
        val g = newGame().round(listOf(5, 5, 5), listOf(9, 9, 9), out = 1)
        assertEquals(listOf(9, 0, 9), g.rounds[0].hand)
    }

    @Test fun negativeHandInputIsTreatedAsZero() {
        val g = newGame().round(listOf(0, 3, 1), listOf(-2, 0, 0))
        assertEquals(listOf(0, 3, 1), g.rounds[0].melded)
        assertEquals(listOf(0, 0, 0), g.rounds[0].hand)
    }

    @Test fun outOfRangeWentOutIsIgnored() {
        val g = newGame().round(listOf(1, 1, 1), listOf(4, 4, 4), out = 7)
        assertNull(g.rounds[0].wentOut)
        assertEquals(listOf(4, 4, 4), g.rounds[0].hand)
    }

    // ---- Rules.md section 8: game end ----
    @Test fun notOverBelowTarget() {
        assertFalse(ScoreEngine.isOver(newGame(100).round(listOf(60, 10, 0), listOf(0, 0, 0))))
    }

    @Test fun passingTargetMidRoundDoesNotEndGame() {
        var g = newGame(100).round(listOf(80, 10, 0), listOf(0, 0, 0), out = 0)
        g = GameOps.addMeld(g, 0, 30) // live total 110
        assertTrue(ScoreEngine.targetReachedLive(g))
        assertFalse(ScoreEngine.isOver(g)) // round not finished
        g = GameOps.endRound(g, listOf(0, 0, 0), wentOut = 0)
        assertTrue(ScoreEngine.isOver(g))
        assertEquals(listOf(0), ScoreEngine.leaders(g))
    }

    @Test fun handPointsAtRoundEndCanDropPlayerBelowTarget() {
        var g = GameOps.addMeld(newGame(100), 1, 105)
        assertTrue(ScoreEngine.targetReachedLive(g))
        g = GameOps.endRound(g, listOf(0, 10, 0), wentOut = 0) // B holds 10 points: total 95
        assertFalse(ScoreEngine.isOver(g))
        assertEquals(listOf(0, 95, 0), ScoreEngine.totals(g))
    }

    @Test fun overWhenOnePlayerAtOrAboveTargetAndAlone() {
        val g = newGame(100).round(listOf(100, 10, 0), listOf(0, 0, 0), out = 0)
        assertTrue(ScoreEngine.isOver(g))
        assertEquals(listOf(0), ScoreEngine.leaders(g))
    }

    @Test fun highestWinsEvenIfSeveralPassTarget() {
        val g = newGame(100).round(listOf(120, 105, 0), listOf(0, 0, 0))
        assertTrue(ScoreEngine.isOver(g))
        assertEquals(listOf(0), ScoreEngine.leaders(g))
    }

    @Test fun tieAtTopAfterTargetForcesAnotherRound() {
        val g = newGame(100).round(listOf(100, 100, 0), listOf(0, 0, 0))
        assertFalse(ScoreEngine.isOver(g))
        assertTrue(ScoreEngine.isTieBreak(g))
        val resolved = g.round(listOf(5, 0, 0), listOf(0, 0, 0))
        assertTrue(ScoreEngine.isOver(resolved))
        assertEquals(listOf(0), ScoreEngine.leaders(resolved))
    }

    @Test fun liveRankingOrdersHighestFirst() {
        var g = GameOps.addMeld(newGame(), 0, 5)
        g = GameOps.addMeld(g, 1, 30)
        g = GameOps.addMeld(g, 2, 12)
        assertEquals(listOf(1, 2, 0), ScoreEngine.liveRanking(g))
    }

    // ---- reopen and edit ----
    @Test fun reopenLastRoundMovesMeldsBackToLive() {
        val g = newGame().round(listOf(10, 0, 7), listOf(0, 3, 0), out = 0)
        val r = GameOps.reopenLastRound(g)
        assertTrue(r.rounds.isEmpty())
        assertEquals(listOf(10, 0, 7), ScoreEngine.liveMelded(r))
        assertEquals(ScoreEngine.liveTotals(r), listOf(10, 0, 7))
        assertTrue(GameOps.reopenLastRound(newGame()).rounds.isEmpty())
    }

    @Test fun editCellChangesOnePlayerInOneRound() {
        val g = newGame().round(listOf(1, 1, 1), listOf(0, 0, 0)).round(listOf(2, 2, 2), listOf(3, 3, 3))
        val e = GameOps.editCell(g, round = 1, player = 2, melded = 9, hand = 4)
        assertEquals(listOf(2, 2, 9), e.rounds[1].melded)
        assertEquals(listOf(3, 3, 4), e.rounds[1].hand)
        assertEquals(g.rounds[0], e.rounds[0])
    }

    @Test fun editCellKeepsZeroHandForPlayerWhoWentOut() {
        val g = newGame().round(listOf(1, 1, 1), listOf(5, 5, 5), out = 0)
        val e = GameOps.editCell(g, 0, 0, melded = 7, hand = 99)
        assertEquals(0, e.rounds[0].hand[0])
        assertEquals(7, e.rounds[0].melded[0])
    }

    @Test fun editCellOutOfRangeIsIgnored() {
        val g = newGame().round(listOf(1, 1, 1), listOf(0, 0, 0))
        assertEquals(g, GameOps.editCell(g, 5, 0, 9, 0))
        assertEquals(g, GameOps.editCell(g, 0, 7, 9, 0))
    }

    // ---- history and config ----
    @Test fun finishRecordsWinnersTotalsAndTarget() {
        val g = newGame(50).round(listOf(60, 20, 20), listOf(0, 0, 0), out = 0)
        val f = ScoreEngine.finish(g, Rules(gameName = "X"), now = 42L)
        assertEquals(listOf("A"), f.winners)
        assertEquals(listOf(60, 20, 20), f.totals)
        assertEquals(50, f.targetScore)
        assertEquals(1, f.roundCount)
        assertEquals(42L, f.endedAt)
    }

    @Test fun rulesJsonParsesAndIgnoresComment() {
        val json = """{"_comment":"x","gameName":"G","maxPlayers":3,"targetOptions":[50,150],"cardsPerPlayer":{"2":13,"3":7}}"""
        val r = AppJson.decodeFromString<Rules>(json)
        assertEquals("G", r.gameName)
        assertEquals(listOf(50, 150), r.targetOptions)
        assertEquals(7, r.cardsPerPlayer["3"])
    }

    @Test fun gameWithLiveMeldsSurvivesJsonRoundTrip() {
        val g = GameOps.addMeld(newGame().round(listOf(5, 6, 7), listOf(0, 1, 1), out = 0), 1, 9)
        val back = AppJson.decodeFromString<Game>(AppJson.encodeToString(Game.serializer(), g))
        assertEquals(g, back)
    }
}
