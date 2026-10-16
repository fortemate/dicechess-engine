// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import scala.collection.mutable
import scala.util.Random

/** Explicit experimental settings; hosts supply their own measured profile. No built-in bot is replaced. */
final case class TacticianConfig(candidateLimit: Int, kingThreatCp: Int, kingDangerCp: Int, hangingWeight: Double):
  require(candidateLimit > 0, "candidateLimit must be positive")
  require(kingThreatCp >= 0 && kingDangerCp >= 0, "king weights must be nonnegative")
  require(hangingWeight.isFinite && hangingWeight >= 0.0, "hangingWeight must be finite and nonnegative")

/** Per-call work counts. Unique states include flags, clocks, mailbox and en-passant rights. */
final case class TacticianStats(legalPaths: Int, distinctStates: Int, rescoredStates: Int, threatQueries: Int)

/** Experimental full-turn search with cheap ordering and exact roll-coverage king threats on a bounded shortlist.
  *
  * Every candidate comes from the canonical maximal-dice generator. Immediate king captures bypass evaluation.
  * Different paths to the same complete post-turn state occupy one shortlist slot; this intentionally changes tie
  * multiplicities relative to path-based bots. The first representative is retained, making seeded ties reproducible.
  *
  * Aggressive evaluation orders the states. Rescoring replaces its boolean king-exposure penalty with roll-weighted
  * danger, adds hypothetical attacking potential, and prices hanging material using the public PieceSafety primitive.
  * Attacking potential assumes the board is unchanged before our next roll; it does not model an intervening reply.
  *
  * The work limit bounds expensive candidate queries, not total generation or wall time. This synchronous search makes
  * no UI responsiveness guarantee and is not registered as a default opponent.
  */
final class TacticianSearch(
    val config: TacticianConfig,
    statsSink: TacticianStats => Unit = _ => ()
) extends SearchAlgorithm:
  private case class Candidate(moves: List[Move], after: GameState, cheap: Int)

  override def findBestMove(state: GameState): Option[ScoredSequence] = findBestMove(state, new Random())

  override def findBestMove(state: GameState, random: Random): Option[ScoredSequence] =
    val paths = TurnGenerator.generateAllLegalTurnPaths(state)
    if paths.isEmpty then
      statsSink(TacticianStats(0, 0, 0, 0))
      None
    else
      val wins = paths.takeWhile(path => SearchScoring.isTerminalWin(state, path))
      if wins.nonEmpty then
        val shortestLength = wins.iterator.map(_.size).min
        val shortest       = wins.filter(_.size == shortestLength)
        statsSink(TacticianStats(paths.size, 0, 0, 0))
        Some(ScoredSequence(shortest(random.nextInt(shortest.size)), SearchScoring.TerminalWinScore))
      else select(state, paths, random)

  private def select(state: GameState, paths: List[List[Move]], random: Random): Option[ScoredSequence] =
    val distinct = mutable.LinkedHashMap.empty[GameState, Candidate]
    paths.foreach { path =>
      val after = path.foldLeft(state)((s, move) => s.makeMove(move)).endTurn()
      if !distinct.contains(after) then
        distinct(after) = Candidate(path, after, Evaluator.evaluateAggressive(after, state.activeColor))
    }
    val shortlisted = distinct.values.toList.sortBy(c => (-c.cheap, c.moves.size)).take(config.candidateLimit)
    var queries     = 0
    val rescored    = shortlisted.map { candidate =>
      val after                           = candidate.after
      val perspective                     = state.activeColor
      def threat(attacker: Color): Double =
        val defenders = if attacker.isWhite then after.blackPieces else after.whitePieces
        val king      = after.kings & defenders
        if king.isEmpty then 0.0
        else
          queries += 1
          val target = Square.fromIndex(java.lang.Long.numberOfTrailingZeros(king.value))
          KingThreat.threatWeight(after.withActiveColor(attacker), target, 3).toDouble / DiceRolls.totalOrderedRolls
      val danger = if config.kingDangerCp == 0 then 0.0 else threat(perspective.opponent)
      val attack = if config.kingThreatCp == 0 then 0.0 else threat(perspective)
      val hangs  =
        if config.hangingWeight == 0.0 then 0.0
        else
          config.hangingWeight *
            (PieceSafety.hangingMaterial(after, perspective.opponent) - PieceSafety.hangingMaterial(after, perspective))
      val value = candidate.cheap.toDouble - Evaluator.evaluateKingSafety(after, perspective) + hangs +
        config.kingThreatCp.toDouble * attack - config.kingDangerCp.toDouble * danger
      // A heuristic, even under extreme caller settings, must never impersonate a terminal win.
      val score = math.max(Int.MinValue.toDouble + 1, math.min(Int.MaxValue.toDouble - 1, math.rint(value))).toInt
      ScoredSequence(candidate.moves, score)
    }
    val best    = rescored.map(_.score).max
    val optimal = rescored.filter(_.score == best)
    statsSink(TacticianStats(paths.size, distinct.size, shortlisted.size, queries))
    Some(optimal(random.nextInt(optimal.size)))
