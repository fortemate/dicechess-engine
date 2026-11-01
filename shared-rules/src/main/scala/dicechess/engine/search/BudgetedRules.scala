// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.movegen.MoveGenerator
import scala.collection.mutable.ListBuffer

/** Opt-in bounded traversal; the existing complete rules APIs are unchanged.
  *
  * One work unit admits a move-generator invocation, an examined micro-move, or a capture-roll iteration. A single
  * move-generator invocation is not preempted internally. These counters bound traversal and retained path entries, not
  * elapsed time, instructions or heap bytes; callers still need external wall/memory guards.
  */
object BudgetedRules:
  /** Share only within one synchronous invocation. Counts are cumulative across queries using this budget. Exhaustion
    * is sticky: a denied admission cannot later be resumed as a complete computation.
    */
  final class Budget(val limit: Long):
    require(limit >= 0, "work limit must be nonnegative")
    private var count                    = 0L
    private var stopped                  = false
    def used: Long                       = count
    def exhausted: Boolean               = stopped
    private[search] def admit(): Boolean =
      if !stopped && count < limit then
        count += 1
        true
      else
        stopped = true
        false

  /** Incomplete computations expose only cumulative work, never partial paths or a partial exact count. */
  enum Outcome[+A] derives CanEqual:
    case Complete(value: A, used: Long)
    case Incomplete(used: Long)

  /** Summary of a budgeted turn traversal over legal turn paths and resulting states.
    *
    * @param emittedPaths
    *   the number of legal turn paths visited by the callback before completion or early stop
    * @param totalLegalPaths
    *   the total number of legal turn paths for the state (0 if forced pass)
    * @param stoppedEarly
    *   `true` if traversal was terminated early by the callback returning `false`
    */
  final case class TurnVisitSummary(emittedPaths: Int, totalLegalPaths: Int, stoppedEarly: Boolean) derives CanEqual

  /** Result of a budgeted fold over legal turn paths and resulting states.
    *
    * @param value
    *   the accumulated value after all visited paths (or after early stop)
    * @param stoppedEarly
    *   `true` if the fold was terminated early by the callback returning `false`
    */
  final case class TurnFoldResult[+A](value: A, stoppedEarly: Boolean) derives CanEqual

  /** Traverses legal turn paths and their resulting states under budget constraints.
    *
    * Admissions during Phase 1 DFS exploration count towards `budget`: 1 unit before each move-generator invocation and
    * 1 unit before examining each generated micro-move, matching [[turnPaths]] accounting.
    *
    * If `budget.exhausted` occurs during Phase 1, zero visitor callbacks are invoked and traversal returns
    * [[Outcome.Incomplete]].
    *
    * Phase 2 emits King captures first in MoveGenerator order (without calling `endTurn()`), followed by maximal-dice
    * normal paths in MoveGenerator order (calling `endTurn()` once on the final step), reusing a 2-slot intra-turn
    * prefix cache. If the visitor callback returns `false`, traversal stops early and returns [[Outcome.Complete]] with
    * `stoppedEarly = true`.
    *
    * @param state
    *   the incoming [[dicechess.engine.domain.GameState]]
    * @param budget
    *   the shared traversal budget
    * @param f
    *   callback receiving borrowed moves array, path length, resulting state, and isKingCapture flag. Returns `true` to
    *   continue, `false` to stop early.
    * @return
    *   [[Outcome.Complete]] with [[TurnVisitSummary]] and work used, or [[Outcome.Incomplete]] if budget exhausted
    */
  def visitTurnStates(state: GameState, budget: Budget)(
      f: (Array[Move], Int, GameState, Boolean) => Boolean
  ): Outcome[TurnVisitSummary] =
    val collector = new BudgetedPathCollector(budget)
    if !budget.exhausted then collector.visit(state, 0, 0)
    if budget.exhausted then Outcome.Incomplete(budget.used)
    else
      val ctx        = collector.ctx
      val totalLegal = ctx.totalLegalPaths
      if totalLegal == 0 then Outcome.Complete(TurnVisitSummary(0, 0, false), budget.used)
      else
        val (emitted, stopped) = TurnGenerator.replayTurnStates(state, ctx)(f)
        Outcome.Complete(TurnVisitSummary(emitted, totalLegal, stopped), budget.used)

  /** Folds over legal turn paths and their resulting states under budget constraints.
    *
    * Work accounting and emission semantics match [[visitTurnStates]].
    *
    * If `budget.exhausted` occurs during Phase 1, zero callbacks are invoked and traversal returns
    * [[Outcome.Incomplete]].
    *
    * The fold callback receives the accumulated value, path as a `List[Move]`, resulting state, and isKingCapture flag,
    * returning a pair of updated accumulator and continuation flag (`false` to stop early).
    *
    * @param state
    *   the incoming [[dicechess.engine.domain.GameState]]
    * @param initial
    *   initial accumulator value
    * @param budget
    *   the shared traversal budget
    * @param f
    *   folding function `(acc, path, resState, isKingCapture) => (nextAcc, continue)`
    * @return
    *   [[Outcome.Complete]] with [[TurnFoldResult]] and work used, or [[Outcome.Incomplete]] if budget exhausted
    */
  def foldTurnStates[A](state: GameState, initial: A, budget: Budget)(
      f: (A, List[Move], GameState, Boolean) => (A, Boolean)
  ): Outcome[TurnFoldResult[A]] =
    val collector = new BudgetedPathCollector(budget)
    if !budget.exhausted then collector.visit(state, 0, 0)
    if budget.exhausted then Outcome.Incomplete(budget.used)
    else
      val ctx = collector.ctx
      if ctx.totalLegalPaths == 0 then Outcome.Complete(TurnFoldResult(initial, false), budget.used)
      else
        var acc          = initial
        val (_, stopped) = TurnGenerator.replayTurnStates(state, ctx) { (moves, len, resState, isKing) =>
          val pathList =
            if len == 1 then moves(0) :: Nil
            else if len == 2 then moves(0) :: moves(1) :: Nil
            else moves(0) :: moves(1) :: moves(2) :: Nil
          val (nextAcc, cont) = f(acc, pathList, resState, isKing)
          acc = nextAcc
          cont
        }
        Outcome.Complete(TurnFoldResult(acc, stopped), budget.used)

  private class BudgetedPathCollector(budget: Budget):
    val ctx                 = new TurnGenerator.TurnGenContext()
    private val currentPath = new Array[Move](3)

    def visit(s: GameState, depth: Int, consumed: Int): Unit =
      if budget.admit() then
        var remaining = MoveGenerator.generateMoves(s)
        val before    = ctx.normalCount + ctx.kingCaptureCount
        while remaining.nonEmpty && !budget.exhausted do
          val move = remaining.head
          currentPath(depth) = move
          examine(s, depth, consumed, move)
          remaining = remaining.tail
        if !budget.exhausted && depth > 0 && ctx.normalCount + ctx.kingCaptureCount == before then
          val packed = TurnGenerator.packPath(currentPath, depth, consumed)
          ctx.addNormal(packed)

    private def examine(s: GameState, depth: Int, consumed: Int, move: Move): Unit =
      if budget.admit() then
        val spent = consumed + (if move.isCastling then 2 else 1)
        if s.isKingCapture(move) then
          val packed = TurnGenerator.packPath(currentPath, depth + 1, spent)
          ctx.addKingCapture(packed)
        else
          val surviving = s.diceAfter(move)
          if surviving.isValid then visit(s.makeMove(move).withDiceSlotsOf(surviving), depth + 1, spent)

  /** Ordered full legal paths, with king captures before maximal-dice normal paths, as in [[TurnGenerator]]. An empty
    * complete list means a pass. Exhaustion during generation discards the buffered prefix.
    */
  def turnPaths(state: GameState, budget: Budget): Outcome[List[List[Move]]] =
    val paths = new PathCollector(budget)
    if !budget.exhausted then paths.visit(state, Nil, 0)
    if budget.exhausted then Outcome.Incomplete(budget.used)
    else Outcome.Complete(paths.result(), budget.used)

  /** First canonical king-capture path for the incoming active color and remaining dice, without replacing either. A
    * found capture is a complete existence proof and may stop before other legal paths are generated. Complete None
    * proves absence; exhaustion returns no path or absence claim. King captures are legal regardless of turn
    * maximality. Counts use the shared cumulative budget; atomic generator calls still require an external wall/memory
    * guard.
    */
  def kingCapturePath(state: GameState, budget: Budget): Outcome[Option[List[Move]]] =
    val pieces  = if state.activeColor.isWhite then state.blackPieces else state.whitePieces
    val targets = state.kings & pieces
    val search  = new CaptureWitness(budget)
    if !budget.exhausted && !targets.isEmpty then search.visit(state, Nil, GameFlags.DiceSlots)
    if budget.exhausted then Outcome.Incomplete(budget.used)
    else Outcome.Complete(search.result, budget.used)

  private class CaptureWitness(budget: Budget):
    private var found: Option[List[Move]]                                       = None
    def result: Option[List[Move]]                                              = found
    def visit(state: GameState, reversed: List[Move], remainingDice: Int): Unit =
      if remainingDice > 0 && budget.admit() then
        var moves = MoveGenerator.generateMoves(state)
        while moves.nonEmpty && found.isEmpty && !budget.exhausted do
          examine(state, reversed, remainingDice, moves.head)
          moves = moves.tail

    private def examine(state: GameState, reversed: List[Move], remainingDice: Int, move: Move): Unit =
      if budget.admit() then
        if state.isKingCapture(move) then found = Some((move :: reversed).reverse)
        else
          val surviving = state.diceAfter(move)
          val spent     = if move.isCastling then 2 else 1
          if surviving.isValid then
            visit(state.makeMove(move).withDiceSlotsOf(surviving), move :: reversed, remainingDice - spent)

  private class PathCollector(budget: Budget):
    private val normal                                           = ListBuffer.empty[(List[Move], Int)]
    private val kings                                            = ListBuffer.empty[List[Move]]
    private var maxDice                                          = 0
    private def addNormal(path: List[Move], consumed: Int): Unit =
      normal += ((path.reverse, consumed))
      maxDice = math.max(maxDice, consumed)
    def result(): List[List[Move]] =
      kings.toList ++ normal.iterator.filter(_._2 == maxDice).map(_._1).toList
    def visit(s: GameState, path: List[Move], consumed: Int): Unit =
      if budget.admit() then
        var remaining = MoveGenerator.generateMoves(s)
        val before    = normal.size + kings.size
        while remaining.nonEmpty && !budget.exhausted do
          examine(s, path, consumed, remaining.head)
          remaining = remaining.tail
        // No valid continuation: retain the preceding path, just as the complete generator does.
        if !budget.exhausted && path.nonEmpty && normal.size + kings.size == before then addNormal(path, consumed)
    private def examine(s: GameState, path: List[Move], consumed: Int, move: Move): Unit =
      if budget.admit() then
        val spent    = consumed + (if move.isCastling then 2 else 1)
        val nextPath = move :: path
        if s.isKingCapture(move) then
          kings += nextPath.reverse
          maxDice = math.max(maxDice, spent)
        else
          val surviving = s.diceAfter(move)
          if surviving.isValid then visit(s.makeMove(move).withDiceSlotsOf(surviving), nextPath, spent)

  /** Exact successful ordered-roll count out of [[DiceRolls.totalOrderedRolls]]. The attacker is the defender's
    * opponent, independent of incoming active color/dice, as in [[KingCaptureProbability.kingCaptureProbability]]. A
    * missing defender king has count zero. King capture is always legal, so each roll can stop at its first capture
    * without buffering all paths.
    */
  def kingCaptureRolls(state: GameState, defender: Color, budget: Budget): Outcome[Int] =
    val pieces  = if defender.isWhite then state.whitePieces else state.blackPieces
    val targets = pieces & state.kings
    var count   = 0
    var index   = 0
    while !targets.isEmpty && index < DiceRolls.weighted.length && !budget.exhausted do
      if budget.admit() then
        val (dice, weight) = DiceRolls.weighted(index)
        val rolled         = state.withActiveColor(defender.opponent).withDicePool(dice)
        if canCapture(rolled, budget, GameFlags.DiceSlots) then count += weight
      index += 1
    if budget.exhausted then Outcome.Incomplete(budget.used)
    else Outcome.Complete(count, budget.used)

  private def canCapture(state: GameState, budget: Budget, remainingDice: Int): Boolean =
    var found = false
    if remainingDice > 0 && budget.admit() then
      var moves = MoveGenerator.generateMoves(state)
      while moves.nonEmpty && !found && !budget.exhausted do
        val move = moves.head
        if budget.admit() then found = captureMove(state, move, budget, remainingDice)
        moves = moves.tail
    found

  private def captureMove(state: GameState, move: Move, budget: Budget, remainingDice: Int): Boolean =
    if state.isKingCapture(move) then true
    else
      val surviving = state.diceAfter(move)
      val spent     = if move.isCastling then 2 else 1
      surviving.isValid && canCapture(state.makeMove(move).withDiceSlotsOf(surviving), budget, remainingDice - spent)
