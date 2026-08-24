package dicechess.engine.domain

import scala.annotation.targetName

/** Internal helpers for applying moves to a [[dicechess.engine.domain.GameState]].
  *
  * Encapsulates castling-rights bookkeeping and the mutable bitboard scratch-pad used during move application.
  * Intentionally `private` — all external callers go through the `makeMove` extension methods on
  * [[dicechess.engine.domain.GameState]].
  */
private object Position:

  /** Maps a promotion [[Move]] flag to the target [[dicechess.engine.domain.PieceType]].
    *
    * Defaults to `Queen` for any flag not explicitly listed (quiet move, double push, etc. will never reach this path
    * in practice).
    *
    * @param flags
    *   the 4-bit flag field from a [[Move]]
    * @return
    *   the piece type the pawn should be promoted to
    */
  def promotionPieceType(flags: Int): PieceType = flags match {
    case Move.KnightPromotion | Move.KnightPromoCapture => PieceType.Knight
    case Move.BishopPromotion | Move.BishopPromoCapture => PieceType.Bishop
    case Move.RookPromotion | Move.RookPromoCapture     => PieceType.Rook
    case _                                              => PieceType.Queen
  }

  /** Computes the new castling-rights string after a move.
    *
    * Applies two independent revocations in sequence:
    *
    *   1. **Captured piece** — a rook taken on its home corner removes the corresponding right; a captured king removes
    *      both rights of its color (#591).
    *   2. **Moving piece** — a king move removes both rights for its color; a rook move from its home square removes
    *      only the matching right.
    *
    * Returns `"-"` when no rights remain (FEN convention).
    *
    * @param rights
    *   current FEN castling-rights string (e.g. `"KQkq"`)
    * @param movingPiece
    *   the piece that is moving
    * @param from
    *   origin square of the move
    * @param targetPiece
    *   the captured piece, if any
    * @param to
    *   destination square of the move
    * @param isWhite
    *   `true` if the moving side is White
    * @return
    *   updated castling-rights string, or `"-"` if none remain
    */
  def updatedCastlingRights(
      rights: Int,
      movingPiece: Piece,
      from: Square,
      targetPiece: Option[Piece],
      to: Square,
      isWhite: Boolean
  ): Int =
    removeMoverRights(removeCapturedPieceRights(rights, targetPiece, to), movingPiece.pieceType, from, isWhite)

  private def removeMoverRights(rights: Int, pieceType: PieceType, from: Square, isWhite: Boolean): Int =
    pieceType match
      case PieceType.King =>
        if isWhite then rights & ~3
        else rights & ~12
      case PieceType.Rook => removeRookMoverRights(rights, from, isWhite)
      case _              => rights

  private def removeRookMoverRights(rights: Int, from: Square, isWhite: Boolean): Int =
    if isWhite then
      if from == Square('a', 1) then rights & ~2
      else if from == Square('h', 1) then rights & ~1
      else rights
    else if from == Square('a', 8) then rights & ~8
    else if from == Square('h', 8) then rights & ~4
    else rights

  private def removeCapturedPieceRights(rights: Int, targetPiece: Option[Piece], to: Square): Int =
    targetPiece match
      case Some(p) if p.pieceType == PieceType.Rook => removeCapturedRookRights(rights, to)
      case Some(p) if p.pieceType == PieceType.King => removeCapturedKingRights(rights, p.color)
      case _                                        => rights

  private def removeCapturedRookRights(rights: Int, to: Square): Int =
    if to == Square('a', 8) then rights & ~8
    else if to == Square('h', 8) then rights & ~4
    else if to == Square('a', 1) then rights & ~2
    else if to == Square('h', 1) then rights & ~1
    else rights

  /** Dice Chess ends by capturing the king — a move standard chess does not have, so no FEN rule covers it. A side
    * without a king can never castle, and leaving its letters in the terminal FEN produces a position that claims a
    * right no legal move could exercise (#591). Mirrors the king-*move* rule: a king that has left e1/e8 by any means,
    * capture included, forfeits both rights. Unlike a rook, a king can be taken anywhere, so `to` is irrelevant here.
    */
  private def removeCapturedKingRights(rights: Int, color: Color): Int =
    if color.isWhite then rights & ~3 else rights & ~12

  /** Mutable scratch-pad that mirrors a [[dicechess.engine.domain.GameState]]'s bitboards during move application.
    *
    * Created at the start of each `makeMove` call and discarded once the new [[dicechess.engine.domain.GameState]] is
    * constructed via `state.copy(...)`. Using an explicit class rather than local `def` closures keeps cognitive
    * complexity low and groups all board-mutation operations in one place.
    *
    * @param state
    *   the position from which initial bitboard values are copied
    */
  class BitboardMutator(state: GameState):
    var white: Bitboard       = state.whitePieces
    var black: Bitboard       = state.blackPieces
    var pawns: Bitboard       = state.pawns
    var knights: Bitboard     = state.knights
    var bishops: Bitboard     = state.bishops
    var rooks: Bitboard       = state.rooks
    var queens: Bitboard      = state.queens
    var kings: Bitboard       = state.kings
    val mailbox: Array[Piece] = state.mailbox.toArray

    /** Clears the bit for `sqBB` from the type-specific bitboard of `pt`. */
    def clearPiece(pt: PieceType, sqBB: Bitboard): Unit = pt match
      case PieceType.Pawn   => pawns = pawns & ~sqBB
      case PieceType.Knight => knights = knights & ~sqBB
      case PieceType.Bishop => bishops = bishops & ~sqBB
      case PieceType.Rook   => rooks = rooks & ~sqBB
      case PieceType.Queen  => queens = queens & ~sqBB
      case PieceType.King   => kings = kings & ~sqBB
      case _                => ()

    /** Sets the bit for `sqBB` in the type-specific bitboard of `pt`. */
    def setPiece(pt: PieceType, sqBB: Bitboard): Unit = pt match
      case PieceType.Pawn   => pawns |= sqBB
      case PieceType.Knight => knights |= sqBB
      case PieceType.Bishop => bishops |= sqBB
      case PieceType.Rook   => rooks |= sqBB
      case PieceType.Queen  => queens |= sqBB
      case PieceType.King   => kings |= sqBB
      case _                => ()

    /** Toggles `fromToBB` in the appropriate color bitboard (XOR clears origin, sets destination). */
    def moveColor(isWhite: Boolean, fromToBB: Bitboard): Unit =
      if isWhite then white ^= fromToBB else black ^= fromToBB

    /** Removes `sqBB` from the opponent's color bitboard (used when a piece is captured). */
    def removeCaptured(isWhite: Boolean, sqBB: Bitboard): Unit =
      if isWhite then black = black & ~sqBB else white = white & ~sqBB

    /** Moves the castling rook: updates the color bitboard, the rooks bitboard, and the mailbox atomically.
      *
      * @param isWhite
      *   `true` if the rook belongs to White
      * @param rookFrom
      *   home square of the rook (e.g. h1 for White king-side)
      * @param rookTo
      *   destination square of the rook (e.g. f1 for White king-side)
      * @param color
      *   color of the rook (used to populate the mailbox entry)
      */
    def moveRook(isWhite: Boolean, rookFrom: Square, rookTo: Square, color: Color): Unit =
      val rookBB = Bitboard.fromSquare(rookFrom) | Bitboard.fromSquare(rookTo)
      if isWhite then white ^= rookBB else black ^= rookBB
      rooks ^= rookBB
      mailbox(rookFrom.index) = Piece.Empty
      mailbox(rookTo.index) = Piece(color, PieceType.Rook)

extension (state: GameState)
  /** Applies a [[MicroMove]] (turn-based) to the current game state.
    *
    * Handles piece movement, captures, and basic bitboard updates. This is a _raw_ micro-move application. According to
    * Dice Chess micro-move semantics, this method **does not** flip the active color or increment the full-move number.
    * Turn transitions must be explicitly managed via [[GameState.endTurn()]].
    *
    * **Dice pool**: The die matching the moving piece type is automatically removed from `state.dicePool`. If the pool
    * is empty (e.g. when replaying from perft or search without dice context), the pool is left unchanged.
    *
    * The half-move clock is reset to 0 on pawn moves and captures; otherwise it is increased by 1.
    *
    * @param mv
    *   the micro-move to apply
    * @return
    *   a new [[dicechess.engine.domain.GameState]] reflecting the position after the micro-move, with dice pool
    *   decremented
    */
  def makeMove(mv: MicroMove): GameState = {
    val from        = mv.from
    val to          = mv.to
    val movingPiece = state.mailbox(from)
    val targetPiece = state.mailbox.get(to)
    val isWhite     = movingPiece.color.isWhite
    val fromBB      = Bitboard.fromSquare(from)
    val toBB        = Bitboard.fromSquare(to)
    val rankOffset  = if isWhite then -8 else 8
    val b           = new Position.BitboardMutator(state)

    val isEnPassantCapture = movingPiece.pieceType == PieceType.Pawn &&
      from.file != to.file &&
      state.mailbox(to).isEmpty

    if isEnPassantCapture then {
      val victimSq    = Square.fromIndex(to.index + rankOffset)
      val victimPiece = state.mailbox.get(victimSq)
      val victimBB    = Bitboard.fromSquare(victimSq)
      b.mailbox(victimSq.index) = Piece.Empty
      b.removeCaptured(isWhite, victimBB)
      victimPiece.foreach(p => b.clearPiece(p.pieceType, victimBB))
    }

    b.mailbox(from.index) = Piece.Empty
    b.moveColor(isWhite, fromBB | toBB)
    targetPiece.foreach(_ => b.removeCaptured(isWhite, toBB))
    b.clearPiece(movingPiece.pieceType, fromBB)
    targetPiece.foreach(p => b.clearPiece(p.pieceType, toBB))
    val finalPieceType = mv.promotion.getOrElse(movingPiece.pieceType)
    b.setPiece(finalPieceType, toBB)
    b.mailbox(to.index) = Piece(movingPiece.color, finalPieceType)

    val isDoublePush   = movingPiece.pieceType == PieceType.Pawn && math.abs(to.rank - from.rank) == 2
    var finalEnPassant = state.enPassant.remove(to)
    if isDoublePush then {
      finalEnPassant = finalEnPassant.add(Square.fromIndex(to.index + rankOffset))
    } else {
      // The inherited en-passant target is intentionally NOT expired here. A Dice Chess turn spans up
      // to three micro-moves and the opponent-created target stays capturable for the whole turn
      // (#351); that turn-boundary expiry runs once in GameState.endTurn (clearEnPassant). makeMove
      // only drops a target this move itself invalidates: a pawn leaving its post-double-push square,
      // the just-used ep target, or the captured pawn that owned the target.
      if movingPiece.pieceType == PieceType.Pawn then {
        finalEnPassant = finalEnPassant.remove(Square.fromIndex(from.index + rankOffset))
      }

      if isEnPassantCapture then {
        finalEnPassant = finalEnPassant.remove(to)
      } else {
        targetPiece.foreach { p =>
          if p.pieceType == PieceType.Pawn then {
            finalEnPassant = finalEnPassant.remove(Square.fromIndex(to.index - rankOffset))
          }
        }
      }
    }

    val diceVal      = movingPiece.pieceType.diceValue
    val idx          = state.dicePool.indexOf(diceVal)
    val nextDicePool = if idx >= 0 then {
      state.dicePool.patch(idx, Nil, 1)
    } else {
      require(state.dicePool.isEmpty, s"Active dice pool ${state.dicePool} does not contain piece type $diceVal")
      state.dicePool
    }

    val newCastlingRights =
      Position.updatedCastlingRights(state.flags.castlingRights, movingPiece, from, targetPiece, to, isWhite)
    val newHalfMoveClock =
      if movingPiece.pieceType == PieceType.Pawn || targetPiece.isDefined || isEnPassantCapture then 0
      else state.flags.halfMoveClock + 1

    var epFiles = 0
    var ep      = finalEnPassant.value
    while ep != 0 do {
      val fileIdx = java.lang.Long.numberOfTrailingZeros(ep) % 8
      epFiles |= (1 << fileIdx)
      ep &= ep - 1
    }

    var key = state.zobristHash
    key ^= Zobrist.pieceKey(movingPiece.color, movingPiece.pieceType, from)

    if isEnPassantCapture then {
      val victimSq = Square.fromIndex(to.index + rankOffset)
      key ^= Zobrist.pieceKey(movingPiece.color.opponent, PieceType.Pawn, victimSq)
    } else {
      targetPiece.foreach { p =>
        key ^= Zobrist.pieceKey(p.color, p.pieceType, to)
      }
    }

    key ^= Zobrist.pieceKey(movingPiece.color, finalPieceType, to)

    var epDiff = state.enPassant.value ^ finalEnPassant.value
    while epDiff != 0L do {
      val sqIdx = java.lang.Long.numberOfTrailingZeros(epDiff)
      key ^= Zobrist.EnPassantTable(sqIdx)
      epDiff &= epDiff - 1L
    }

    if newCastlingRights != state.flags.castlingRights then {
      key ^= Zobrist.CastlingTable(state.flags.castlingRights) ^ Zobrist.CastlingTable(newCastlingRights)
    }

    val newFlags = GameFlags.fromList(
      color = state.activeColor,
      castlingRights = newCastlingRights,
      enPassantFiles = epFiles,
      dicePool = nextDicePool,
      halfMoveClock = newHalfMoveClock
    )

    key ^= Zobrist.dicePoolKey(state.flags) ^ Zobrist.dicePoolKey(newFlags)

    state.copy(
      whitePieces = b.white,
      blackPieces = b.black,
      pawns = b.pawns,
      knights = b.knights,
      bishops = b.bishops,
      rooks = b.rooks,
      queens = b.queens,
      kings = b.kings,
      mailbox = Mailbox.fromBuilder(b.mailbox),
      flags = newFlags,
      enPassant = finalEnPassant,
      zobristKey = key
    )
  }

  /** Applies a [[Move]] (search-optimized) to the current game state.
    *
    * Handles all standard chess rules encoded in the move's 4-bit flag field:
    *
    *   - **Double pawn push** — sets the en-passant square on the passed square.
    *   - **En-passant capture** — removes the captured pawn from the rank behind the destination.
    *   - **King-side / queen-side castling** — moves both king and rook atomically.
    *   - **Promotions** — replaces the pawn with the promoted piece type.
    *   - **Quiet / capture** — standard piece relocation.
    *
    * According to Dice Chess micro-move semantics, this method **does not** flip the active color or increment the
    * full-move number. Turn transitions must be explicitly managed via [[GameState.endTurn()]]. An inherited en-passant
    * target is retained across intra-turn micro-moves (it is cleared only at the turn boundary by
    * [[GameState.endTurn()]]); this move drops only a target it directly invalidates. Castling rights are updated and
    * the half-move clock is maintained.
    *
    * @param mv
    *   the move to apply (must be pseudo-legal; legality is enforced by the search layer)
    * @return
    *   a new [[dicechess.engine.domain.GameState]] reflecting the position after the micro-move
    */
  @targetName("makeMove_Move")
  def makeMove(mv: Move): GameState = {
    val from       = mv.fromSquare
    val to         = mv.toSquare
    val mover      = state.mailbox(from)
    val color      = mover.color
    val isWhite    = color.isWhite
    val fromBB     = Bitboard.fromSquare(from)
    val toBB       = Bitboard.fromSquare(to)
    val rankOffset = if isWhite then -8 else 8
    val b          = new Position.BitboardMutator(state)

    b.mailbox(from.index) = Piece.Empty
    b.moveColor(isWhite, fromBB | toBB)
    b.clearPiece(mover.pieceType, fromBB)

    val target                 = if mv.flags != Move.EnPassantCapture then state.mailbox.get(to) else None
    var newEnPassant: Bitboard = state.enPassant.remove(to)
    // The inherited ep target is NOT expired here — in Dice Chess it stays capturable for the whole
    // turn and is cleared once at the turn boundary by GameState.endTurn (clearEnPassant), #351.
    // makeMove only drops a target this move itself invalidates: a pawn leaving its post-double-push
    // square, the just-used ep target (below), or a captured pawn that owned the target.
    if mv.flags != Move.DoublePawnPush && mover.pieceType == PieceType.Pawn then
      newEnPassant = newEnPassant.remove(Square.fromIndex(from.index + rankOffset))
    target.foreach { p =>
      b.removeCaptured(isWhite, toBB)
      b.clearPiece(p.pieceType, toBB)
      if p.pieceType == PieceType.Pawn then newEnPassant = newEnPassant.remove(Square.fromIndex(to.index - rankOffset))
    }

    mv.flags match {
      case Move.DoublePawnPush =>
        b.pawns |= toBB
        b.mailbox(to.index) = Piece(color, PieceType.Pawn)
        newEnPassant = newEnPassant.add(Square.fromIndex(to.index + rankOffset))

      case Move.EnPassantCapture =>
        val victimSq = Square.fromIndex(to.index + rankOffset)
        val victimBB = Bitboard.fromSquare(victimSq)
        b.removeCaptured(isWhite, victimBB)
        b.pawns = (b.pawns & ~victimBB) | toBB
        b.mailbox(victimSq.index) = Piece.Empty
        b.mailbox(to.index) = Piece(color, PieceType.Pawn)
        newEnPassant = newEnPassant.remove(to)

      case Move.KingCastle =>
        b.kings |= toBB
        b.mailbox(to.index) = Piece(color, PieceType.King)
        val (rFrom, rTo) = if isWhite then (Square('h', 1), Square('f', 1)) else (Square('h', 8), Square('f', 8))
        b.moveRook(isWhite, rFrom, rTo, color)

      case Move.QueenCastle =>
        b.kings |= toBB
        b.mailbox(to.index) = Piece(color, PieceType.King)
        val (rFrom, rTo) = if isWhite then (Square('a', 1), Square('d', 1)) else (Square('a', 8), Square('d', 8))
        b.moveRook(isWhite, rFrom, rTo, color)

      case _ if mv.isPromotion =>
        val promType = Position.promotionPieceType(mv.flags)
        b.setPiece(promType, toBB)
        b.mailbox(to.index) = Piece(color, promType)

      case _ =>
        b.setPiece(mover.pieceType, toBB)
        b.mailbox(to.index) = Piece(color, mover.pieceType)
    }

    val newCastlingRights = Position.updatedCastlingRights(state.flags.castlingRights, mover, from, target, to, isWhite)
    val isCap             = target.isDefined || mv.flags == Move.EnPassantCapture
    val newHalfMoveClock  =
      if mover.pieceType == PieceType.Pawn || isCap then 0 else state.flags.halfMoveClock + 1

    var epFiles = 0
    var epV     = newEnPassant.value
    while epV != 0L do {
      val fileIdx = java.lang.Long.numberOfTrailingZeros(epV) % 8
      epFiles |= (1 << fileIdx)
      epV &= epV - 1L
    }

    var key = state.zobristHash
    key ^= Zobrist.pieceKey(color, mover.pieceType, from)

    target.foreach { p =>
      key ^= Zobrist.pieceKey(p.color, p.pieceType, to)
    }

    mv.flags match {
      case Move.DoublePawnPush =>
        key ^= Zobrist.pieceKey(color, PieceType.Pawn, to)

      case Move.EnPassantCapture =>
        val victimSq = Square.fromIndex(to.index + rankOffset)
        key ^= Zobrist.pieceKey(color.opponent, PieceType.Pawn, victimSq)
        key ^= Zobrist.pieceKey(color, PieceType.Pawn, to)

      case Move.KingCastle =>
        key ^= Zobrist.pieceKey(color, PieceType.King, to)
        val (rFrom, rTo) = if isWhite then (Square('h', 1), Square('f', 1)) else (Square('h', 8), Square('f', 8))
        key ^= Zobrist.pieceKey(color, PieceType.Rook, rFrom) ^ Zobrist.pieceKey(color, PieceType.Rook, rTo)

      case Move.QueenCastle =>
        key ^= Zobrist.pieceKey(color, PieceType.King, to)
        val (rFrom, rTo) = if isWhite then (Square('a', 1), Square('d', 1)) else (Square('a', 8), Square('d', 8))
        key ^= Zobrist.pieceKey(color, PieceType.Rook, rFrom) ^ Zobrist.pieceKey(color, PieceType.Rook, rTo)

      case _ if mv.isPromotion =>
        val promType = Position.promotionPieceType(mv.flags)
        key ^= Zobrist.pieceKey(color, promType, to)

      case _ =>
        key ^= Zobrist.pieceKey(color, mover.pieceType, to)
    }

    var epDiff = state.enPassant.value ^ newEnPassant.value
    while epDiff != 0L do {
      val sqIdx = java.lang.Long.numberOfTrailingZeros(epDiff)
      key ^= Zobrist.EnPassantTable(sqIdx)
      epDiff &= epDiff - 1L
    }

    if newCastlingRights != state.flags.castlingRights then {
      key ^= Zobrist.CastlingTable(state.flags.castlingRights) ^ Zobrist.CastlingTable(newCastlingRights)
    }

    val newFlags = GameFlags.fromList(
      color = state.activeColor,
      castlingRights = newCastlingRights,
      enPassantFiles = epFiles,
      dicePool = Nil,
      halfMoveClock = newHalfMoveClock
    )

    key ^= Zobrist.dicePoolKey(state.flags) ^ Zobrist.dicePoolKey(newFlags)

    state.copy(
      whitePieces = b.white,
      blackPieces = b.black,
      pawns = b.pawns,
      knights = b.knights,
      bishops = b.bishops,
      rooks = b.rooks,
      queens = b.queens,
      kings = b.kings,
      mailbox = Mailbox.fromBuilder(b.mailbox),
      flags = newFlags,
      enPassant = newEnPassant,
      zobristKey = key
    )
  }
