// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*

/** Independent reference implementations for [[ChanceNode]] tests: a one-ply decision value computed from
  * [[TurnGenerator.generateAllLegalTurnPaths]] and plain replay (no visitor, no partition), and the exhaustive
  * expectation over all 216 ordered rolls or the 56 canonical weighted rolls.
  *
  * Shared by the cross-platform [[ChanceNodeSuite]] and the JVM-only [[ChanceNodeConcurrencySpec]].
  */
object ChanceNodeOracle:

  private[search] def parse(fen: String): GameState = FenParser.parse(fen).toOption.get

  /** The public synthetic fixtures of the traversal and search contracts page (F1–F4, F7–F10) as chance boundaries: the
    * side to move has no dice yet, and the chance node asks what its unknown roll is worth.
    */
  val fixtures: List[(String, GameState)] = List(
    "F1 king-only"             -> "4k3/8/8/8/8/8/8/4K3 w - - 0 1",
    "F2 rank-7 promotion"      -> "k7/4P3/8/8/8/8/8/4K3 w - - 0 1",
    "F3 rank-6 promotion"      -> "k7/8/4P3/8/8/8/8/4K3 w - - 0 1",
    "F4 blocked pawn"          -> "4k3/8/8/8/8/p7/P7/R3K3 w - - 0 1",
    "F7 one-die king capture"  -> "8/8/8/2k5/8/1N6/2P5/K7 w - - 0 1",
    "F8 commuting pawn pushes" -> "4k3/8/8/8/8/8/P6P/4K3 w - - 0 1",
    "F9 mandatory rook move"   -> "4k3/8/8/8/3r4/2P5/8/4K3 b - - 0 1",
    "F10 developed opening"    -> "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 2 3"
  ).map((name, fen) => name -> parse(fen))

  private def pieces(state: GameState, color: Color): Bitboard =
    if color.isWhite then state.whitePieces else state.blackPieces

  /** The one-ply decision value of `rolled` for `bounds.perspective`: the mover's best reply by `leaf` (MAX when the
    * mover is the perspective, MIN otherwise), a terminal sentinel when some reply captures the king, and the forced
    * pass `rolled.endTurn()` when no reply exists.
    */
  def decisionValue(rolled: GameState, bounds: UtilityBounds, leaf: (GameState, Color) => Int): Double =
    val mover      = rolled.activeColor
    val maximizing = bounds.isMaximizing(mover)
    val paths      = TurnGenerator.generateAllLegalTurnPaths(rolled)
    if paths.isEmpty then leaf(rolled.endTurn(), bounds.perspective).toDouble
    else
      val values = paths.map: path =>
        val played = path.foldLeft(rolled)((s, m) => s.makeMove(m))
        if (played.kings & pieces(played, mover.opponent)).isEmpty then bounds.terminalFor(mover)
        else leaf(played.endTurn(), bounds.perspective).toDouble
      if maximizing then values.max else values.min

  /** The pass value and the true decision value of every class, for fixture-level assertions. */
  def passValue(rolled: GameState, bounds: UtilityBounds, leaf: (GameState, Color) => Int): Double =
    leaf(rolled.endTurn(), bounds.perspective).toDouble

  /** Material minus a flat penalty per hanging piece of each side — the cheapest evaluator that can see that a
    * mandatory move walked a piece into capture, which plain material cannot until the capture happens.
    */
  val threatAware: (GameState, Color) => Int = (state, color) =>
    Evaluator.evaluateMaterial(state, color) +
      100 * PieceSafety.hangingSquares(state, color.opponent).count -
      100 * PieceSafety.hangingSquares(state, color).count

  /** A position in which every knight move lands on an attacked square: with a knight die in hand Black must play one
    * of them, so the mandatory move is worse for Black than the pass it is not allowed to take.
    */
  val mandatoryKnight: GameState = parse("n6k/8/3P4/P7/8/8/8/7K b - - 0 1")

  /** The exact expectation over all 216 ordered rolls, accumulated as an integer sum and divided once. */
  def expectationOver216(afterTurn: GameState, bounds: UtilityBounds, leaf: (GameState, Color) => Int): Double =
    var sum = 0.0
    for d1 <- 1 to 6; d2 <- 1 to 6; d3 <- 1 to 6 do
      sum += decisionValue(afterTurn.withDicePool(List(d1, d2, d3)), bounds, leaf)
    sum / DiceRolls.totalOrderedRolls

  /** The same expectation over the 56 canonical weighted rolls. */
  def expectationOver56(afterTurn: GameState, bounds: UtilityBounds, leaf: (GameState, Color) => Int): Double =
    var sum = 0.0
    DiceRolls.weighted.foreach: (dice, weight) =>
      sum += weight.toDouble * decisionValue(afterTurn.withDicePool(dice), bounds, leaf)
    sum / DiceRolls.totalOrderedRolls

  /** Whether some roll of `afterTurn.activeColor` admits no legal turn at all — a forced pass with dice in hand. */
  def forcedPassMass(afterTurn: GameState): Int =
    var mass = 0
    DiceRolls.weighted.foreach: (dice, weight) =>
      if TurnGenerator.generateAllLegalTurnPaths(afterTurn.withDicePool(dice)).isEmpty then mass += weight
    mass

  /** A one-ply policy over `leaf`, mirroring what [[ExpectimaxSearch]] does one ply above the evaluator, with knobs for
    * the behaviours the mechanism must cope with: a fail-soft `decide` that answers with a bound when the exact value
    * lies outside the window, and an optional Star2 probe by the material-best reply.
    *
    * Counts every callback so tests can assert what the mechanism did and did not ask for.
    */
  class LeafPolicy(
      bounds: UtilityBounds,
      maximizing: Boolean,
      leaf: (GameState, Color) => Int,
      control: SearchControl = SearchControl.Unbounded,
      failSoft: Boolean = false,
      probing: Boolean = false,
      looseBounds: Boolean = false
  ) extends ChanceNode.Policy:
    var decideCalls           = 0
    var probeCalls            = 0
    var forcedPassesSeen      = 0
    var forcedPassWeightsSeen = List.empty[Int]
    val decidedClasses        = scala.collection.mutable.ArrayBuffer.empty[Int]

    def lowestValue: Double  = if maximizing then bounds.minNonTerminal else bounds.terminalLoss
    def highestValue: Double = if maximizing then bounds.terminalWin else bounds.maxNonTerminal

    private def exactValue(rolled: GameState): ChanceNode.Value =
      ChanceNode.Replies.generate(rolled, control) match
        case None                                  => ChanceNode.Value.incomplete(control)
        case Some(replies) if replies.capturesKing =>
          ChanceNode.Value.exact(bounds.terminalFor(rolled.activeColor), lossTainted = !maximizing)
        case Some(replies) if replies.isForcedPass =>
          forcedPassesSeen += 1
          ChanceNode.Value.exact(leaf(rolled.endTurn(), bounds.perspective).toDouble)
        case Some(replies) =>
          val scores = replies.states.map(leaf(_, bounds.perspective).toDouble)
          ChanceNode.Value.exact(if maximizing then scores.max else scores.min)

    override def probe(rolled: IArray[GameState]): Option[Array[ChanceNode.Value]] =
      probeCalls += 1
      if !probing then None
      else
        Some(Array.tabulate(rolled.length) { index =>
          val state = rolled(index)
          ChanceNode.Replies.generate(state, control) match
            case None                                  => ChanceNode.Value.incomplete(control)
            case Some(replies) if replies.capturesKing =>
              ChanceNode.Value.exact(bounds.terminalFor(state.activeColor), lossTainted = !maximizing)
            case Some(replies) if replies.isForcedPass =>
              ChanceNode.Value.exact(leaf(state.endTurn(), bounds.perspective).toDouble)
            case Some(replies) =>
              // The material-best reply for the mover bounds the class in the role's direction.
              val material = replies.states.map(Evaluator.evaluateMaterial(_, bounds.perspective))
              val pick     = if maximizing then material.indices.maxBy(material) else material.indices.minBy(material)
              val score    = leaf(replies.states(pick), bounds.perspective).toDouble
              if maximizing then ChanceNode.Value(SearchOutcome.LowerBound(score, 0L))
              else ChanceNode.Value(SearchOutcome.UpperBound(score, 0L))
        })

    def decide(rolled: GameState, classIndex: Int, alpha: Double, beta: Double): ChanceNode.Value =
      decideCalls += 1
      decidedClasses += classIndex
      val exact = exactValue(rolled)
      // A fail-soft bound is the exact value; a loose one is the widest bound still true, so the mechanism's open-window
      // re-search is exercised rather than the immediate cut.
      exact.outcome match
        case SearchOutcome.Exact(value, work) if failSoft && value < alpha =>
          ChanceNode.Value(
            SearchOutcome.UpperBound(if looseBounds then highestValue else value, work),
            exact.lossTainted
          )
        case SearchOutcome.Exact(value, work) if failSoft && value > beta =>
          ChanceNode.Value(
            SearchOutcome.LowerBound(if looseBounds then lowestValue else value, work),
            exact.lossTainted
          )
        case _ => exact
