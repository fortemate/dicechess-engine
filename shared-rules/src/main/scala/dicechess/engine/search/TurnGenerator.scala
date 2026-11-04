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
    * The callback returns `true` to continue traversal, or `false` to stop early. Discovery buffers packed paths before
    * replay; the replay cache retains at most two intermediate boards. Use [[SharedTraversal]] explicitly when several
    * dice classes share one board or terminal discovery should stop early.
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

  /** Work actually performed by one [[SharedTraversal]], cumulative across its classes. `generated` counts per-piece
    * geometry calls on cache misses, `examined` counts class-specific candidate edges (including rejected castling),
    * `transitions` counts `makeMove` calls during discovery and emission, `replayed` is its emission-only subset, and
    * `emitted` counts visitor calls. Final `endTurn()` calls equal non-terminal emissions. These are deterministic
    * operation counts, not time or heap bounds.
    */
  final case class TraversalWork(
      generated: Long,
      examined: Long,
      transitions: Long,
      emitted: Long,
      replayed: Long = 0L
  ) derives CanEqual

  /** No total is claimed when a visitor stops before generation finishes. */
  final case class SharedVisitSummary(emittedPaths: Int, stoppedEarly: Boolean) derives CanEqual

  /** Lazy canonical traversal shared across the retained rolls of one fresh-roll position.
    *
    * Own one instance per chance node and discard it with that node. Only ordered move prefixes are shared: their
    * dice-free boards, per-edge en-passant eligibility and lazily generated piece geometry. Different paths reaching
    * the same board remain distinct. Dice consumption, promotion-created piece availability and maximality are resolved
    * separately for each roll through the existing generator and `consumeDiceFor`; castling spends two dice.
    *
    * King captures are emitted as discovered, in canonical capture order, without `endTurn()`. Returning `false` is a
    * complete stopping proof and prevents further discovery. Normal leaves are buffered as packed paths until the
    * entire class proves its maximum dice consumption (including captures), then emitted in canonical order with
    * exactly one `endTurn()` each. A partially explored normal branch is never emitted. The borrowed three-slot moves
    * array is valid only during its callback; copy its used elements to retain a path.
    *
    * The optional budget admits one unit per geometry cache miss and per examined class edge. Cache hits and replay are
    * free of admissions; empty dice need none. This deliberately differs from the historical, unchanged
    * [[BudgetedRules.visitTurnStates]] contract. `Outcome` work is cumulative budget usage when supplied, otherwise
    * this instance's `generated + examined`. Cancellation is checked before geometry, edges and emissions and latches
    * for the instance. An incomplete visit may have emitted proven results; callers must discard their accumulation and
    * must never infer a forced pass or exact value from it. A callback stop does not cancel later class visits.
    *
    * Instances are single-threaded and non-reentrant; nested and concurrent traversals need independent instances. No
    * mutable state is global and the partitioner's immutable arrays are never modified. `initial` supplies the board,
    * side, rights and counters; its dice are ignored by [[visit]]. No evaluator, utility or class order is owned here.
    * Geometry calls are indivisible, so external time and memory supervision remains the host's responsibility.
    */
  final class SharedTraversal(
      initial: GameState,
      budget: Option[BudgetedRules.Budget] = None,
      cancelled: () => Boolean = () => false
  ):
    final private class Prefix(val state: GameState, val eligible: Bitboard):
      val geometry      = Array.fill[List[Move]](6)(Nil)
      var generatedMask = 0

    private val root        = new Prefix(initial.withDicePool(Nil), computeEligibleEpPawns(initial))
    private val prefixes    = scala.collection.mutable.LongMap.empty[Prefix]
    private var generating  = 0L
    private var examining   = 0L
    private var applying    = 0L
    private var emitting    = 0L
    private var replaying   = 0L
    private var interrupted = false
    private var visiting    = false

    def work: TraversalWork = TraversalWork(generating, examining, applying, emitting, replaying)

    private def used: Long = budget.fold(generating + examining)(_.used)

    private def live: Boolean =
      if !interrupted && (budget.exists(_.exhausted) || cancelled()) then interrupted = true
      !interrupted

    private def admit(): Boolean =
      val allowed = live && budget.forall(_.admit())
      if !allowed then interrupted = true
      allowed

    /** Visits one retained roll, preserving the ordinary canonical order and multiplicity. `Complete` with zero
      * emissions and no callback stop proves a forced pass. `Incomplete` proves neither absence nor maximality.
      */
    def visit(roll: CompactRoll)(
        f: (Array[Move], Int, GameState, Boolean) => Boolean
    ): BudgetedRules.Outcome[SharedVisitSummary] = visitFlags(roll.applyToState(root.state).flags)(f)

    private[search] def visitFlags(flags: GameFlags)(
        f: (Array[Move], Int, GameState, Boolean) => Boolean
    ): BudgetedRules.Outcome[SharedVisitSummary] =
      require(!visiting, "use an independent SharedTraversal for nested visits")
      visiting = true
      try
        val traversal = new ClassVisit(f)
        if live then traversal.discover(root, flags, 0, 0)
        if !traversal.stopped && live then traversal.replayNormals()
        if !traversal.stopped && !live then BudgetedRules.Outcome.Incomplete(used)
        else BudgetedRules.Outcome.Complete(SharedVisitSummary(traversal.emitted, traversal.stopped), used)
      finally visiting = false

    private def geometry(prefix: Prefix, face: Int): List[Move] =
      val bit = 1 << (face - 1)
      if (prefix.generatedMask & bit) == 0 && admit() then
        generating += 1
        prefix.geometry(face - 1) =
          MoveGenerator.generatePieceMoves(prefix.state, PieceType.fromDice(face).get, prefix.eligible)
        prefix.generatedMask |= bit
      prefix.geometry(face - 1)

    private def step(prefix: Prefix, move: Move): Prefix =
      applying += 1
      val eligible =
        if move.isEnPassant || prefix.eligible.contains(move.fromSquare) then prefix.eligible.remove(move.fromSquare)
        else prefix.eligible
      new Prefix(prefix.state.makeMove(move), eligible)

    private def child(prefix: Prefix, move: Move, packed: Long): Prefix =
      // Dice usage is deliberately absent from the key; length distinguishes leading zero move slots.
      val key = packed & 0x00ffffffffffffffL
      prefixes.getOrElseUpdate(key, step(prefix, move))

    final private class ClassVisit(f: (Array[Move], Int, GameState, Boolean) => Boolean):
      private val ctx     = new TurnGenContext
      private val path    = new Array[Move](3)
      private val outPath = new Array[Move](3)
      var emitted         = 0
      var stopped         = false

      /** True only after a legal completion was found; interruption never turns a prefix into a leaf. */
      def discover(prefix: Prefix, flags: GameFlags, depth: Int, consumed: Int): Boolean =
        var found = false
        var seen  = 0
        var slot  = 0
        while slot < 3 && !stopped && live do
          val face = if slot == 0 then flags.diceSlot1 else if slot == 1 then flags.diceSlot2 else flags.diceSlot3
          val bit  = 1 << face
          if face >= 1 && face <= 6 && (seen & bit) == 0 then
            seen |= bit
            if discoverFace(prefix, flags, depth, consumed, face) then found = true
          slot += 1
        found

      // Inline the helpers to keep each recursion level's control flow in one runtime method.
      private inline def discoverFace(prefix: Prefix, flags: GameFlags, depth: Int, consumed: Int, face: Int): Boolean =
        var found = false
        var moves = geometry(prefix, face)
        while moves.nonEmpty && !stopped do
          if admit() then
            if discoverMove(prefix, flags, depth, consumed, moves.head) then found = true
            moves = moves.tail
          else moves = Nil
        found

      private inline def discoverMove(
          prefix: Prefix,
          flags: GameFlags,
          depth: Int,
          consumed: Int,
          move: Move
      ): Boolean =
        examining += 1
        path(depth) = move
        val spent  = consumed + (if move.isCastling then 2 else 1)
        val packed = packPath(path, depth + 1, spent)
        if prefix.state.isKingCapture(move) then
          ctx.maxDice = math.max(ctx.maxDice, spent)
          emit(packed, prefix, move, true)
          true
        else discoverNormal(prefix, flags, depth, spent, move, packed)

      private inline def discoverNormal(
          prefix: Prefix,
          flags: GameFlags,
          depth: Int,
          spent: Int,
          move: Move,
          packed: Long
      ): Boolean =
        val remaining = flags.consumeDiceFor(move, prefix.state.mailbox(move.fromSquare).pieceType)
        if !remaining.isValid then false
        else
          if remaining.isDicePoolEmpty then ctx.addNormal(packed)
          else
            val next      = child(prefix, move, packed)
            val continued = discover(next, remaining, depth + 1, spent)
            if !continued && !stopped && live then ctx.addNormal(packed)
          true

      private def emit(packed: Long, prefix: Prefix, move: Move, isKing: Boolean): Unit =
        if live then
          val len = unpackPath(packed, outPath)
          // Discovery already built internal prefixes. Only an emitted leaf needs a final transition.
          val raw = prefixes.get(packed & 0x00ffffffffffffffL) match
            case Some(existing) => existing.state
            case None           =>
              applying += 1
              replaying += 1
              prefix.state.makeMove(move)
          val result = if isKing then raw else raw.endTurn()
          emitted += 1
          emitting += 1
          if !f(outPath, len, result, isKing) then stopped = true

      def replayNormals(): Unit =
        var index = 0
        while index < ctx.normalCount && !stopped && live do
          val packed = ctx.normalPaths(index)
          if ((packed >>> 56) & 0xffL).toInt == ctx.maxDice then
            val len    = unpackPath(packed, path)
            val parent =
              if len == 1 then root
              else prefixes(packPath(path, len - 1, 0))
            emit(packed, parent, path(len - 1), false)
          index += 1

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
