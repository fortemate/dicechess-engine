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
  * This object is used by [[SearchAlgorithm]] implementations to obtain the candidate set before scoring.
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
    val moves = MoveGenerator.generateMoves(state)
    if moves.nonEmpty then
      val ctx         = new TurnGenContext()
      val currentPath = new Array[Move](3)
      generatePathsSinglePass(state, currentPath, 0, 0, ctx, moves)

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
  private def packPath(path: Array[Move], len: Int, dice: Int): Long =
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
  private inline def unpackPath(p: Long, out: Array[Move]): Int =
    out(0) = (p & 0xffffL).toInt.asInstanceOf[Move]
    out(1) = ((p >>> 16) & 0xffffL).toInt.asInstanceOf[Move]
    out(2) = ((p >>> 32) & 0xffffL).toInt.asInstanceOf[Move]
    ((p >>> 48) & 0xffL).toInt

  private class TurnGenContext:
    var normalPaths      = new Array[Long](128)
    var normalCount      = 0
    var kingCaptures     = new Array[Long](32)
    var kingCaptureCount = 0
    var maxDice          = 0

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
      if kingCaptureCount >= kingCaptures.length then
        val next = new Array[Long](kingCaptures.length * 2)
        System.arraycopy(kingCaptures, 0, next, 0, kingCaptures.length)
        kingCaptures = next
      kingCaptures(kingCaptureCount) = p
      kingCaptureCount += 1

  private def generatePathsSinglePass(
      state: GameState,
      currentPath: Array[Move],
      depth: Int,
      diceConsumedSoFar: Int,
      ctx: TurnGenContext,
      moves: List[Move]
  ): Unit =
    var curr = moves
    while curr.nonEmpty do
      val move = curr.head
      currentPath(depth) = move
      processMove(state, currentPath, depth, diceConsumedSoFar, ctx, move)
      curr = curr.tail

  private inline def processMove(
      state: GameState,
      currentPath: Array[Move],
      depth: Int,
      diceConsumedSoFar: Int,
      ctx: TurnGenContext,
      move: Move
  ): Unit =
    if state.isKingCapture(move) then handleKingCapture(currentPath, depth, diceConsumedSoFar, ctx, move)
    else if move.isCastling then handleCastling(state, currentPath, depth, diceConsumedSoFar, ctx, move)
    else handleNormalMove(state, currentPath, depth, diceConsumedSoFar, ctx, move)

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

  private inline def handleCastling(
      state: GameState,
      currentPath: Array[Move],
      depth: Int,
      diceConsumedSoFar: Int,
      ctx: TurnGenContext,
      move: Move
  ): Unit =
    if state.flags.containsDie(PieceType.King.diceValue) && state.flags.containsDie(PieceType.Rook.diceValue) then
      val survived = state.flags.removeDie(PieceType.King.diceValue).removeDie(PieceType.Rook.diceValue)
      val next     = state.makeMove(move).withDiceSlotsOf(survived)
      val subMoves = MoveGenerator.generateMoves(next)
      if subMoves.isEmpty then
        val consumed = diceConsumedSoFar + 2
        val packed   = packPath(currentPath, depth + 1, consumed)
        ctx.addNormal(packed)
      else
        val normalBefore = ctx.normalCount
        val kingBefore   = ctx.kingCaptureCount
        generatePathsSinglePass(next, currentPath, depth + 1, diceConsumedSoFar + 2, ctx, subMoves)
        if ctx.normalCount == normalBefore && ctx.kingCaptureCount == kingBefore then
          val consumed = diceConsumedSoFar + 2
          val packed   = packPath(currentPath, depth + 1, consumed)
          ctx.addNormal(packed)

  private inline def handleNormalMove(
      state: GameState,
      currentPath: Array[Move],
      depth: Int,
      diceConsumedSoFar: Int,
      ctx: TurnGenContext,
      move: Move
  ): Unit =
    val moverType = state.mailbox(move.fromSquare).pieceType
    val dieValue  = moverType.diceValue
    require(
      state.flags.containsDie(dieValue),
      s"CRITICAL: Dice pool ${state.dicePool} does not contain die $dieValue! moverType=$moverType, move=${move.fromSquare.toNotation}${move.toSquare.toNotation}, state.activeColor=${state.activeColor}, state.mailbox(move.fromSquare)=${state.mailbox(move.fromSquare)}"
    )
    val survived = state.flags.removeDie(dieValue)
    val next     = state.makeMove(move).withDiceSlotsOf(survived)
    val subMoves = MoveGenerator.generateMoves(next)
    if subMoves.isEmpty then
      val consumed = diceConsumedSoFar + 1
      val packed   = packPath(currentPath, depth + 1, consumed)
      ctx.addNormal(packed)
    else
      val normalBefore = ctx.normalCount
      val kingBefore   = ctx.kingCaptureCount
      generatePathsSinglePass(next, currentPath, depth + 1, diceConsumedSoFar + 1, ctx, subMoves)
      if ctx.normalCount == normalBefore && ctx.kingCaptureCount == kingBefore then
        val consumed = diceConsumedSoFar + 1
        val packed   = packPath(currentPath, depth + 1, consumed)
        ctx.addNormal(packed)
