// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.movegen.MoveGenerator

/** Exhaustive turn-path generator for Dice Chess.
  *
  * [[TurnGenerator]] performs a depth-first search over all micro-move sequences achievable with the given dice rolls,
  * and filters the result to only the *legal* paths under the **Maximum Micro-moves Rule**:
  *
  *   - A path is legal if it ends with a King capture (win condition), *or*
  *   - it consumes the maximum achievable number of dice (castling spends two dice in a single move).
  *
  * Search algorithms (the `SearchAlgorithm` implementations shipped in the `dicechess-engine` artifact) use this object
  * to obtain the candidate set before scoring; servers and clients use it for rule validation.
  *
  * @note
  *   Active color is kept constant throughout the turn — it is *not* toggled between micro-moves. `makeMove` preserves
  *   the side, so we can chain micro-moves directly. The turn is only flipped explicitly at the end by the caller.
  */
object TurnGenerator:

  /** Generates all legal full-turn paths (sequences of 1 to 3 moves) for the given state.
    *
    * A path is a `List[Move]` of 1–3 micro-moves. An empty list means no legal move exists (the player passes). The
    * filtering guarantees that the returned paths either end with a King capture or consume the maximum achievable
    * number of dice (castling counts as two).
    *
    * @param state
    *   the current [[dicechess.engine.domain.GameState]]; `state.activeColor` indicates who is moving
    * @return
    *   a (possibly empty) list of legal full-turn paths; each path contains 1–3 moves
    */
  def generateAllLegalTurnPaths(state: GameState): List[List[Move]] =
    val builder = List.newBuilder[List[Move]]
    forEachLegalTurnPath(state) { (moves, len) =>
      builder += moves.take(len).toList
    }
    builder.result()

  /** Computes the set of friendly pawns that are eligible to capture an en-passant target at turn start. Under strict
    * chess rules, only pawns that were already horizontally adjacent to the opponent pawn that double-pushed are
    * permitted to capture en passant.
    */
  def computeEligibleEpPawns(state: GameState): Bitboard =
    MoveGenerator.computeEligibleEpPawns(state)

  /** Traverses all legal turn paths (sequences of 1 to 3 moves) using a visitor/callback pattern.
    *
    * The callback `f` receives the path as an array of moves and the length of the path.
    *
    * @note
    *   To avoid garbage collection pressure, the array passed to the callback is mutable and reused across calls. If
    *   the callback needs to persist the path, it must copy the elements (e.g., using `moves.take(len).toList`).
    *
    * @param state
    *   the current [[dicechess.engine.domain.GameState]]
    * @param f
    *   the callback function invoked for each legal path
    */
  def forEachLegalTurnPath(state: GameState)(f: (Array[Move], Int) => Unit): Unit =
    val initialEligible = computeEligibleEpPawns(state)
    val moves           = MoveGenerator.generateMoves(state, initialEligible)
    if moves.nonEmpty then
      val ctx         = new TurnGenContext()
      val currentPath = new Array[Move](3)
      generatePathsSinglePass(state, currentPath, 0, 0, ctx, initialEligible, moves)

      if ctx.kingCaptureCount > 0 || ctx.normalCount > 0 then
        val outPath = new Array[Move](3)
        var i       = 0
        while i < ctx.kingCaptureCount do
          f(outPath, unpackPath(ctx.kingCaptures(i), outPath))
          i += 1

        i = 0
        val maxDice = ctx.maxDice
        while i < ctx.normalCount do
          val p    = ctx.normalPaths(i)
          val dice = ((p >>> 56) & 0xffL).toInt
          if dice == maxDice then f(outPath, unpackPath(p, outPath))
          i += 1

  /** Traverses all legal turn paths and their resulting game states using a visitor/callback pattern with prefix reuse.
    *
    * The callback `f` receives:
    *   - `moves`: a borrowed 3-slot array holding the turn path moves in slots `0 until len`
    *   - `len`: the number of moves in the path (1 to 3)
    *   - `resultingState`: the resulting game state after applying this turn. For King capture (`isKingCapture ==
    *     true`), this state is `path.foldLeft(state)(_.makeMove(_))` without calling `endTurn()` (`activeColor` remains
    *     `state.activeColor`, `dicePool == Nil`). For normal maximal-dice turns (`isKingCapture == false`), `endTurn()`
    *     is called once on the final step (`activeColor` flipped to opponent, stale opponent EP cleared, `dicePool ==
    *     Nil`, `fullMoveNumber` incremented if Black moved).
    *   - `isKingCapture`: `true` if this path ends with capturing the opponent King
    *
    * The callback returns `true` to continue traversal, or `false` to stop early.
    *
    * @note
    *   The `moves` array passed to the callback is mutable and reused across invocations. Callers must copy elements if
    *   persistence is required.
    *
    * @param state
    *   the current [[dicechess.engine.domain.GameState]]
    * @param f
    *   the callback function invoked for each legal path and its resulting state
    * @return
    *   the number of legal paths visited before completion or early stop
    */
  def forEachLegalTurnState(state: GameState)(
      f: (Array[Move], Int, GameState, Boolean) => Boolean
  ): Int =
    val initialEligible = computeEligibleEpPawns(state)
    val moves           = MoveGenerator.generateMoves(state, initialEligible)
    if moves.isEmpty then 0
    else
      val ctx         = new TurnGenContext()
      val currentPath = new Array[Move](3)
      generatePathsSinglePass(state, currentPath, 0, 0, ctx, initialEligible, moves)
      val (emitted, _) = replayTurnStates(state, ctx)(f)
      emitted

  /** Packs a path of up to 3 moves, its length, and the total dice consumed into a single 64-bit Long.
    *
    * The packing layout is:
    *   - Bits 0-15: Move 1 (16 bits)
    *   - Bits 16-31: Move 2 (16 bits)
    *   - Bits 32-47: Move 3 (16 bits)
    *   - Bits 48-55: Path length (8 bits)
    *   - Bits 56-63: Dice consumed (8 bits)
    *
    * This layout relies on [[Move]] being represented as a 16-bit encoded integer. The invariant is enforced
    * unconditionally via `require` so that corruption is caught in production, not only under `-ea`.
    */
  private[search] def packPath(path: Array[Move], len: Int, dice: Int): Long =
    val m0 = path(0).toInt
    val m1 = if len > 1 then path(1).toInt else Move.empty.toInt
    val m2 = if len > 2 then path(2).toInt else Move.empty.toInt

    // Verify moves fit in 16-bit encoding — unconditional guard against silent corruption
    // if the Move representation is ever widened beyond 16 bits.
    require((m0 & ~0xffff) == 0, s"Move exceeds 16-bit range: $m0")
    require((m1 & ~0xffff) == 0, s"Move exceeds 16-bit range: $m1")
    require((m2 & ~0xffff) == 0, s"Move exceeds 16-bit range: $m2")

    (m0.toLong & 0xffffL) |
      ((m1.toLong & 0xffffL) << 16) |
      ((m2.toLong & 0xffffL) << 32) |
      ((len.toLong & 0xffL) << 48) |
      ((dice.toLong & 0xffL) << 56)

  /** Unpacks a packed path `Long` into `out` and returns the path length.
    *
    * Extracts three 16-bit move slots and the 8-bit length field from the layout produced by [[packPath]]. The
    * `& 0xffffL` slicing is lossless because [[packPath]] guarantees each move fits within 16 bits.
    */
  private[search] inline def unpackPath(p: Long, out: Array[Move]): Int =
    out(0) = (p & 0xffffL).toInt.asInstanceOf[Move]
    out(1) = ((p >>> 16) & 0xffffL).toInt.asInstanceOf[Move]
    out(2) = ((p >>> 32) & 0xffffL).toInt.asInstanceOf[Move]
    ((p >>> 48) & 0xffL).toInt

  private[search] class TurnGenContext:
    var normalPaths      = new Array[Long](128)
    var normalCount      = 0
    var kingCaptures     = new Array[Long](32)
    var kingCaptureCount = 0
    var maxDice          = 0

    def totalLegalPaths: Int =
      var count = kingCaptureCount
      var i     = 0
      while i < normalCount do
        val dice = ((normalPaths(i) >>> 56) & 0xffL).toInt
        if dice == maxDice then count += 1
        i += 1
      count

    def addNormal(p: Long): Unit =
      val dice = ((p >>> 56) & 0xffL).toInt
      if dice > maxDice then maxDice = dice
      if normalCount >= normalPaths.length then
        val next = new Array[Long](normalPaths.length * 2)
        System.arraycopy(normalPaths, 0, next, 0, normalPaths.length)
        normalPaths = next
      normalPaths(normalCount) = p
      normalCount += 1

    def addKingCapture(p: Long): Unit =
      val dice = ((p >>> 56) & 0xffL).toInt
      if dice > maxDice then maxDice = dice
      if kingCaptureCount >= kingCaptures.length then
        val next = new Array[Long](kingCaptures.length * 2)
        System.arraycopy(kingCaptures, 0, next, 0, kingCaptures.length)
        kingCaptures = next
      kingCaptures(kingCaptureCount) = p
      kingCaptureCount += 1

  private[search] class TurnStateReplayer(state: GameState):
    private var hasS1  = false
    private var prevM0 = -1
    private var s1     = state
    private var hasS2  = false
    private var prevM1 = -1
    private var s2     = state

    def reset(): Unit =
      hasS1 = false
      prevM0 = -1
      hasS2 = false
      prevM1 = -1

    private inline def step1(m0: Move): Unit =
      val m0Int = m0.toInt
      if !hasS1 || m0Int != prevM0 then
        s1 = state.makeMove(m0)
        prevM0 = m0Int
        hasS1 = true
        hasS2 = false
        prevM1 = -1

    private inline def step2(m1: Move): GameState =
      val m1Int = m1.toInt
      if !hasS2 || m1Int != prevM1 then
        s2 = s1.makeMove(m1)
        prevM1 = m1Int
        hasS2 = true
      s2

    def replay(outPath: Array[Move], len: Int, isKingCapture: Boolean): GameState =
      step1(outPath(0))
      val rawState =
        if len == 1 then s1
        else if len == 2 then step2(outPath(1))
        else step2(outPath(1)).makeMove(outPath(2))

      if isKingCapture then rawState else rawState.endTurn()

  private[search] inline def replayTurnStates(
      state: GameState,
      ctx: TurnGenContext
  )(f: (Array[Move], Int, GameState, Boolean) => Boolean): (Int, Boolean) =
    val outPath  = new Array[Move](3)
    val replayer = new TurnStateReplayer(state)
    var emitted  = 0
    var stopped  = false

    // 1. King captures in MoveGenerator discovery order (endTurn is NOT called)
    var i = 0
    while i < ctx.kingCaptureCount && !stopped do
      val p        = ctx.kingCaptures(i)
      val len      = unpackPath(p, outPath)
      val resState = replayer.replay(outPath, len, isKingCapture = true)
      emitted += 1
      if !f(outPath, len, resState, true) then stopped = true
      i += 1

    // 2. Normal maximal-dice paths in MoveGenerator discovery order (endTurn IS called)
    if !stopped then
      replayer.reset()
      val maxDice = ctx.maxDice
      i = 0
      while i < ctx.normalCount && !stopped do
        val p    = ctx.normalPaths(i)
        val dice = ((p >>> 56) & 0xffL).toInt
        if dice == maxDice then
          val len      = unpackPath(p, outPath)
          val resState = replayer.replay(outPath, len, isKingCapture = false)
          emitted += 1
          if !f(outPath, len, resState, false) then stopped = true
        i += 1

    (emitted, stopped)

  private def generatePathsSinglePass(
      state: GameState,
      currentPath: Array[Move],
      depth: Int,
      diceConsumedSoFar: Int,
      ctx: TurnGenContext,
      eligibleEpPawns: Bitboard,
      moves: List[Move]
  ): Unit =
    var curr = moves
    while curr.nonEmpty do
      val move = curr.head
      currentPath(depth) = move
      val nextEligible =
        if move.isEnPassant || eligibleEpPawns.contains(move.fromSquare) then eligibleEpPawns.remove(move.fromSquare)
        else eligibleEpPawns
      processMove(state, currentPath, depth, diceConsumedSoFar, ctx, nextEligible, move)
      curr = curr.tail

  private inline def processMove(
      state: GameState,
      currentPath: Array[Move],
      depth: Int,
      diceConsumedSoFar: Int,
      ctx: TurnGenContext,
      eligibleEpPawns: Bitboard,
      move: Move
  ): Unit =
    if state.isKingCapture(move) then handleKingCapture(currentPath, depth, diceConsumedSoFar, ctx, move)
    else handleNonCapture(state, currentPath, depth, diceConsumedSoFar, ctx, eligibleEpPawns, move)

  private inline def handleKingCapture(
      currentPath: Array[Move],
      depth: Int,
      diceConsumedSoFar: Int,
      ctx: TurnGenContext,
      move: Move
  ): Unit =
    val consumed = diceConsumedSoFar + (if move.isCastling then 2 else 1)
    val packed   = packPath(currentPath, depth + 1, consumed)
    ctx.addKingCapture(packed)

  private inline def handleNonCapture(
      state: GameState,
      currentPath: Array[Move],
      depth: Int,
      diceConsumedSoFar: Int,
      ctx: TurnGenContext,
      eligibleEpPawns: Bitboard,
      move: Move
  ): Unit =
    val survived = state.diceAfter(move)
    if survived.isValid then
      val diceConsumed = if move.isCastling then 2 else 1
      val next         = state.makeMove(move).withDiceSlotsOf(survived)
      val subMoves     = MoveGenerator.generateMoves(next, eligibleEpPawns)
      val consumed     = diceConsumedSoFar + diceConsumed
      if subMoves.isEmpty then
        val packed = packPath(currentPath, depth + 1, consumed)
        ctx.addNormal(packed)
      else
        val normalBefore = ctx.normalCount
        val kingBefore   = ctx.kingCaptureCount
        generatePathsSinglePass(next, currentPath, depth + 1, consumed, ctx, eligibleEpPawns, subMoves)
        if ctx.normalCount == normalBefore && ctx.kingCaptureCount == kingBefore then
          val packed = packPath(currentPath, depth + 1, consumed)
          ctx.addNormal(packed)
