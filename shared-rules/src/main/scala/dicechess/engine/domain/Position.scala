// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.domain

import scala.annotation.targetName

/** Internal helpers for applying moves to a [[dicechess.engine.domain.GameState]].
  *
  * Encapsulates castling-rights, en-passant, and Zobrist-key helpers used during move application. Intentionally
  * `private` — all external callers go through the `makeMove` extension methods on
  * [[dicechess.engine.domain.GameState]].
  */
private object Position:

  private val SquareA1: Square = Square('a', 1)
  private val SquareD1: Square = Square('d', 1)
  private val SquareF1: Square = Square('f', 1)
  private val SquareH1: Square = Square('h', 1)
  private val SquareA8: Square = Square('a', 8)
  private val SquareD8: Square = Square('d', 8)
  private val SquareF8: Square = Square('f', 8)
  private val SquareH8: Square = Square('h', 8)

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

  /** Computes the new castling-rights mask after a move.
    *
    * Applies two independent revocations in sequence:
    *
    *   1. **Captured piece** — a rook taken on its home corner removes the corresponding right; a captured king removes
    *      both rights of its color (#591).
    *   2. **Moving piece** — a king move removes both rights for its color; a rook move from its home square removes
    *      only the matching right.
    *
    * @param rights
    *   current 4-bit castling-rights integer mask
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
    *   updated 4-bit castling-rights integer mask
    */
  def updatedCastlingRights(
      rights: Int,
      movingPiece: Piece,
      from: Square,
      targetPiece: Piece,
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
      if from == SquareA1 then rights & ~2
      else if from == SquareH1 then rights & ~1
      else rights
    else if from == SquareA8 then rights & ~8
    else if from == SquareH8 then rights & ~4
    else rights

  private def removeCapturedPieceRights(rights: Int, targetPiece: Piece, to: Square): Int =
    if targetPiece.isEmpty then rights
    else if targetPiece.pieceType == PieceType.Rook then removeCapturedRookRights(rights, to)
    else if targetPiece.pieceType == PieceType.King then removeCapturedKingRights(rights, targetPiece.color)
    else rights

  private def removeCapturedRookRights(rights: Int, to: Square): Int =
    if to == SquareA8 then rights & ~8
    else if to == SquareH8 then rights & ~4
    else if to == SquareA1 then rights & ~2
    else if to == SquareH1 then rights & ~1
    else rights

  /** Dice Chess ends by capturing the king — a move standard chess does not have, so no FEN rule covers it. A side
    * without a king can never castle, and leaving its letters in the terminal FEN produces a position that claims a
    * right no legal move could exercise (#591). Mirrors the king-*move* rule: a king that has left e1/e8 by any means,
    * capture included, forfeits both rights. Unlike a rook, a king can be taken anywhere, so `to` is irrelevant here.
    */
  private def removeCapturedKingRights(rights: Int, color: Color): Int =
    if color.isWhite then rights & ~3 else rights & ~12

  private[engine] inline def pawnRankOffset(isWhite: Boolean): Int =
    if isWhite then -8 else 8

  private[engine] inline def castlingRookFrom(flags: Int, isWhite: Boolean): Square =
    if flags == Move.KingCastle then if isWhite then SquareH1 else SquareH8
    else if isWhite then SquareA1
    else SquareA8

  private[engine] inline def castlingRookTo(flags: Int, isWhite: Boolean): Square =
    if flags == Move.KingCastle then if isWhite then SquareF1 else SquareF8
    else if isWhite then SquareD1
    else SquareD8

  private[engine] inline def colorAfter(color: Color, completeTurn: Boolean): Color =
    if completeTurn then color.opponent else color

  private[engine] inline def turnKeyToggle(completeTurn: Boolean): Long =
    if completeTurn then Zobrist.ActiveColorKey else 0L

  private[engine] inline def nextHalfMoveClock(current: Int, pieceType: PieceType, isCapture: Boolean): Int =
    if pieceType == PieceType.Pawn || isCapture then 0 else current + 1

  private[engine] inline def microMoveFinalPieceType(mv: MicroMove, defaultType: PieceType): PieceType =
    mv.promotion match
      case Some(pt) => pt
      case None     => defaultType

  private[engine] inline def isMicroMoveEnPassant(
      mover: Piece,
      from: Square,
      to: Square,
      target: Piece
  ): Boolean =
    mover.pieceType == PieceType.Pawn && from.file != to.file && target.isEmpty

  private[engine] inline def isMicroMoveCapture(target: Piece, isEnPassant: Boolean): Boolean =
    !target.isEmpty || isEnPassant

  private inline def clearEpIndexIfValid(ep: Long, idx: Int): Long =
    if idx >= 0 && idx < 64 then ep & ~(1L << idx) else ep

  /** Projects active en-passant squares onto the GameFlags 8-bit file mask field. */
  private[engine] inline def enPassantFiles(bb: Bitboard): Int =
    var epFiles = 0
    var ep      = bb.value
    while ep != 0L do {
      val fileIdx = java.lang.Long.numberOfTrailingZeros(ep) % 8
      epFiles |= (1 << fileIdx)
      ep &= ep - 1L
    }
    epFiles

  def computeMicroMoveEnPassant(
      movingPiece: Piece,
      from: Square,
      to: Square,
      rankOffset: Int,
      targetPiece: Piece,
      isEnPassantCapture: Boolean,
      currentEp: Bitboard
  ): Bitboard =
    val isDoublePush   = movingPiece.pieceType == PieceType.Pawn && math.abs(to.rank - from.rank) == 2
    var finalEnPassant = currentEp.remove(to)
    if isDoublePush then {
      finalEnPassant.add(Square.fromIndex(to.index + rankOffset))
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
        finalEnPassant.remove(to)
      } else if !targetPiece.isEmpty && targetPiece.pieceType == PieceType.Pawn then {
        finalEnPassant.remove(Square.fromIndex(to.index - rankOffset))
      } else {
        finalEnPassant
      }
    }

  def consumeDicePool(dicePool: List[Int], pieceType: PieceType): List[Int] = {
    val diceVal = pieceType.diceValue
    val idx     = dicePool.indexOf(diceVal)
    if idx >= 0 then dicePool.patch(idx, Nil, 1)
    else {
      require(dicePool.isEmpty, s"Active dice pool $dicePool does not contain piece type $diceVal")
      dicePool
    }
  }

  // Keep this body separate: inlining it makes makeMove(MicroMove) exceed Scala 3.10's coverage tree-size limit.
  private[engine] def computeMicroMoveZobristKey(
      state: GameState,
      mv: MicroMove,
      movingPiece: Piece,
      targetPiece: Piece,
      finalPieceType: PieceType,
      finalEnPassant: Bitboard,
      newFlags: GameFlags
  ): Long = {
    val from               = mv.from
    val to                 = mv.to
    val rankOffset         = pawnRankOffset(movingPiece.color.isWhite)
    val isEnPassantCapture = movingPiece.pieceType == PieceType.Pawn && from.file != to.file && targetPiece.isEmpty

    var key = state.zobristHash
    key ^= Zobrist.pieceKey(movingPiece.color, movingPiece.pieceType, from)

    if isEnPassantCapture then {
      val victimSq = Square.fromIndex(to.index + rankOffset)
      key ^= Zobrist.pieceKey(movingPiece.color.opponent, PieceType.Pawn, victimSq)
    } else if !targetPiece.isEmpty then {
      key ^= Zobrist.pieceKey(targetPiece.color, targetPiece.pieceType, to)
    }

    key ^= Zobrist.pieceKey(movingPiece.color, finalPieceType, to)

    var epDiff = state.enPassant.value ^ finalEnPassant.value
    while epDiff != 0L do {
      val sqIdx = java.lang.Long.numberOfTrailingZeros(epDiff)
      key ^= Zobrist.EnPassantTable(sqIdx)
      epDiff &= epDiff - 1L
    }

    if newFlags.castlingRights != state.flags.castlingRights then {
      key ^= Zobrist.CastlingTable(state.flags.castlingRights) ^ Zobrist.CastlingTable(newFlags.castlingRights)
    }

    key ^= Zobrist.dicePoolKey(state.flags) ^ Zobrist.dicePoolKey(newFlags)
    key
  }

  private[engine] inline def invalidateMoveEnPassant(
      ep: Bitboard,
      mv: Move,
      mover: Piece,
      target: Piece,
      rankOffset: Int
  ): Long = {
    var res = ep.value
    if res != 0L then
      if mv.flags == Move.EnPassantCapture then res &= ~(1L << mv.toSquare.index)
      if mv.flags != Move.DoublePawnPush && mover.pieceType == PieceType.Pawn then
        res = clearEpIndexIfValid(res, mv.fromSquare.index + rankOffset)
      if !target.isEmpty && target.pieceType == PieceType.Pawn then
        res = clearEpIndexIfValid(res, mv.toSquare.index - rankOffset)
    res
  }

  // Keep this body separate: inlining it makes makeMove exceed Scala 3.10's coverage tree-size limit.
  private[engine] def computeMoveZobristKey(
      state: GameState,
      mv: Move,
      mover: Piece,
      target: Piece,
      newEnPassant: Bitboard,
      newFlags: GameFlags
  ): Long = {
    val from       = mv.fromSquare
    val to         = mv.toSquare
    val color      = mover.color
    val isWhite    = color.isWhite
    val rankOffset = pawnRankOffset(isWhite)
    val flags      = mv.flags

    var key = state.zobristHash
    key ^= Zobrist.pieceKey(color, mover.pieceType, from)
    if !target.isEmpty then key ^= Zobrist.pieceKey(target.color, target.pieceType, to)

    flags match {
      case Move.DoublePawnPush =>
        key ^= Zobrist.pieceKey(color, PieceType.Pawn, to)

      case Move.EnPassantCapture =>
        val victimSq = Square.fromIndex(to.index + rankOffset)
        key ^= Zobrist.pieceKey(color.opponent, PieceType.Pawn, victimSq)
        key ^= Zobrist.pieceKey(color, PieceType.Pawn, to)

      case Move.KingCastle | Move.QueenCastle =>
        key ^= Zobrist.pieceKey(color, PieceType.King, to)
        val rFrom = castlingRookFrom(flags, isWhite)
        val rTo   = castlingRookTo(flags, isWhite)
        key ^= Zobrist.pieceKey(color, PieceType.Rook, rFrom) ^ Zobrist.pieceKey(color, PieceType.Rook, rTo)

      case _ if mv.isPromotion =>
        val promType = Position.promotionPieceType(flags)
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

    if newFlags.castlingRights != state.flags.castlingRights then {
      key ^= Zobrist.CastlingTable(state.flags.castlingRights) ^ Zobrist.CastlingTable(newFlags.castlingRights)
    }

    key ^= Zobrist.dicePoolKey(state.flags) ^ Zobrist.dicePoolKey(newFlags)
    key
  }

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
    val targetPiece = state.mailbox(to)
    val isWhite     = movingPiece.color.isWhite
    val fromBB      = 1L << from.index
    val toBB        = 1L << to.index
    val moveMask    = fromBB | toBB
    val rankOffset  = Position.pawnRankOffset(isWhite)

    val isEnPassantCapture = Position.isMicroMoveEnPassant(movingPiece, from, to, targetPiece)

    var white   = state.whitePieces.value
    var black   = state.blackPieces.value
    var pawns   = state.pawns.value
    var knights = state.knights.value
    var bishops = state.bishops.value
    var rooks   = state.rooks.value
    var queens  = state.queens.value
    var kings   = state.kings.value
    val mailbox = state.mailbox.toArray

    var capBB    = toBB
    var capPiece = targetPiece
    if isEnPassantCapture then {
      val victimSq = Square.fromIndex(to.index + rankOffset)
      capBB = 1L << victimSq.index
      capPiece = state.mailbox(victimSq)
      mailbox(victimSq.index) = Piece.Empty
    }

    var oppMask = 0L
    if !capPiece.isEmpty then {
      oppMask = capBB
      capPiece.pieceType match
        case PieceType.Pawn   => pawns &= ~capBB
        case PieceType.Knight => knights &= ~capBB
        case PieceType.Bishop => bishops &= ~capBB
        case PieceType.Rook   => rooks &= ~capBB
        case PieceType.Queen  => queens &= ~capBB
        case PieceType.King   => kings &= ~capBB
        case _                => ()
    }

    val finalPieceType = Position.microMoveFinalPieceType(mv, movingPiece.pieceType)
    if finalPieceType == movingPiece.pieceType then {
      movingPiece.pieceType match
        case PieceType.Pawn   => pawns = (pawns & ~fromBB) | toBB
        case PieceType.Knight => knights = (knights & ~fromBB) | toBB
        case PieceType.Bishop => bishops = (bishops & ~fromBB) | toBB
        case PieceType.Rook   => rooks = (rooks & ~fromBB) | toBB
        case PieceType.Queen  => queens = (queens & ~fromBB) | toBB
        case PieceType.King   => kings = (kings & ~fromBB) | toBB
        case _                => ()
    } else {
      movingPiece.pieceType match
        case PieceType.Pawn   => pawns &= ~fromBB
        case PieceType.Knight => knights &= ~fromBB
        case PieceType.Bishop => bishops &= ~fromBB
        case PieceType.Rook   => rooks &= ~fromBB
        case PieceType.Queen  => queens &= ~fromBB
        case PieceType.King   => kings &= ~fromBB
        case _                => ()
      finalPieceType match
        case PieceType.Pawn   => pawns |= toBB
        case PieceType.Knight => knights |= toBB
        case PieceType.Bishop => bishops |= toBB
        case PieceType.Rook   => rooks |= toBB
        case PieceType.Queen  => queens |= toBB
        case PieceType.King   => kings |= toBB
        case _                => ()
    }

    mailbox(from.index) = Piece.Empty
    mailbox(to.index) = Piece(movingPiece.color, finalPieceType)
    if isWhite then {
      white ^= moveMask
      black &= ~oppMask
    } else {
      black ^= moveMask
      white &= ~oppMask
    }

    val finalEnPassant = Position.computeMicroMoveEnPassant(
      movingPiece,
      from,
      to,
      rankOffset,
      targetPiece,
      isEnPassantCapture,
      state.enPassant
    )
    val nextDicePool      = Position.consumeDicePool(state.dicePool, movingPiece.pieceType)
    val newCastlingRights =
      Position.updatedCastlingRights(state.flags.castlingRights, movingPiece, from, targetPiece, to, isWhite)
    val newHalfMoveClock =
      Position.nextHalfMoveClock(
        state.flags.halfMoveClock,
        movingPiece.pieceType,
        Position.isMicroMoveCapture(targetPiece, isEnPassantCapture)
      )

    val newFlags = GameFlags.fromList(
      color = state.activeColor,
      castlingRights = newCastlingRights,
      enPassantFiles = Position.enPassantFiles(finalEnPassant),
      dicePool = nextDicePool,
      halfMoveClock = newHalfMoveClock
    )

    val key = Position.computeMicroMoveZobristKey(
      state,
      mv,
      movingPiece,
      targetPiece,
      finalPieceType,
      finalEnPassant,
      newFlags
    )

    new GameState(
      whitePieces = Bitboard(white),
      blackPieces = Bitboard(black),
      pawns = Bitboard(pawns),
      knights = Bitboard(knights),
      bishops = Bitboard(bishops),
      rooks = Bitboard(rooks),
      queens = Bitboard(queens),
      kings = Bitboard(kings),
      mailbox = Mailbox.fromBuilder(mailbox),
      flags = newFlags,
      enPassant = finalEnPassant,
      fullMoveNumber = state.fullMoveNumber,
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
  def makeMove(mv: Move): GameState = applyMove(mv, completeTurn = false)

  // Shared traversal already proved this is a normal final edge. Complete it without an intermediate GameState.
  private[engine] def makeMoveAndEndTurn(mv: Move): GameState = applyMove(mv, completeTurn = true)

  // Keep completion bookkeeping readable without adding calls or allocations to move application.
  private inline def moveEnPassantAtBoundary(inline moveEnPassant: Long, inline completeTurn: Boolean): Bitboard =
    val staleRankMask = if completeTurn then 0xffL << (if state.activeColor.isBlack then 16 else 40) else 0L
    Bitboard(moveEnPassant & ~staleRankMask)

  private inline def moveNumberAfter(inline completeTurn: Boolean): Int =
    state.fullMoveNumber + (if completeTurn && state.activeColor.isBlack then 1 else 0)

  private def applyMove(mv: Move, completeTurn: Boolean): GameState = {
    val from       = mv.fromSquare
    val to         = mv.toSquare
    val mover      = state.mailbox(from)
    val color      = mover.color
    val isWhite    = color.isWhite
    val fromBB     = 1L << from.index
    val toBB       = 1L << to.index
    val moveMask   = fromBB | toBB
    val rankOffset = Position.pawnRankOffset(isWhite)
    val flags      = mv.flags

    val target: Piece = if flags != Move.EnPassantCapture then state.mailbox(to) else Piece.Empty
    val hasTarget     = !target.isEmpty

    var white    = state.whitePieces.value
    var black    = state.blackPieces.value
    var pawns    = state.pawns.value
    var knights  = state.knights.value
    var bishops  = state.bishops.value
    var rooks    = state.rooks.value
    var queens   = state.queens.value
    var kings    = state.kings.value
    val mailbox  = state.mailbox.toArray
    var selfMask = moveMask
    var oppMask  = 0L

    mailbox(from.index) = Piece.Empty

    if hasTarget then {
      oppMask = toBB
      target.pieceType match
        case PieceType.Pawn   => pawns &= ~toBB
        case PieceType.Knight => knights &= ~toBB
        case PieceType.Bishop => bishops &= ~toBB
        case PieceType.Rook   => rooks &= ~toBB
        case PieceType.Queen  => queens &= ~toBB
        case PieceType.King   => kings &= ~toBB
        case _                => ()
    }

    var moveEnPassant = Position.invalidateMoveEnPassant(state.enPassant, mv, mover, target, rankOffset)

    flags match {
      case Move.DoublePawnPush =>
        pawns ^= moveMask
        mailbox(to.index) = Piece(color, PieceType.Pawn)
        moveEnPassant |= 1L << Square.fromIndex(to.index + rankOffset).index

      case Move.EnPassantCapture =>
        val victimSq = Square.fromIndex(to.index + rankOffset)
        val victimBB = 1L << victimSq.index
        oppMask = victimBB
        pawns = (pawns & ~fromBB & ~victimBB) | toBB
        mailbox(victimSq.index) = Piece.Empty
        mailbox(to.index) = Piece(color, PieceType.Pawn)

      case Move.KingCastle | Move.QueenCastle =>
        val rFrom  = Position.castlingRookFrom(flags, isWhite)
        val rTo    = Position.castlingRookTo(flags, isWhite)
        val rookBB = (1L << rFrom.index) | (1L << rTo.index)
        selfMask |= rookBB
        kings ^= moveMask
        rooks ^= rookBB
        mailbox(to.index) = Piece(color, PieceType.King)
        mailbox(rFrom.index) = Piece.Empty
        mailbox(rTo.index) = Piece(color, PieceType.Rook)

      case _ if mv.isPromotion =>
        val promType = Position.promotionPieceType(flags)
        pawns &= ~fromBB
        promType match
          case PieceType.Knight => knights |= toBB
          case PieceType.Bishop => bishops |= toBB
          case PieceType.Rook   => rooks |= toBB
          case _                => queens |= toBB
        mailbox(to.index) = Piece(color, promType)

      case _ =>
        mover.pieceType match
          case PieceType.Pawn   => pawns = (pawns & ~fromBB) | toBB
          case PieceType.Knight => knights = (knights & ~fromBB) | toBB
          case PieceType.Bishop => bishops = (bishops & ~fromBB) | toBB
          case PieceType.Rook   => rooks = (rooks & ~fromBB) | toBB
          case PieceType.Queen  => queens = (queens & ~fromBB) | toBB
          case PieceType.King   => kings = (kings & ~fromBB) | toBB
          case _                => ()
        mailbox(to.index) = Piece(color, mover.pieceType)
    }

    if isWhite then {
      white ^= selfMask
      black &= ~oppMask
    } else {
      black ^= selfMask
      white &= ~oppMask
    }

    val newCastlingRights = Position.updatedCastlingRights(state.flags.castlingRights, mover, from, target, to, isWhite)
    val isCap             = hasTarget || flags == Move.EnPassantCapture
    val newHalfMoveClock  = Position.nextHalfMoveClock(state.flags.halfMoveClock, mover.pieceType, isCap)

    val newFlags = GameFlags(
      color = Position.colorAfter(state.activeColor, completeTurn),
      castlingRights = newCastlingRights,
      enPassantFiles = Position.enPassantFiles(Bitboard(moveEnPassant)),
      dice1 = 0,
      dice2 = 0,
      dice3 = 0,
      halfMoveClock = newHalfMoveClock
    )

    // endTurn expires the incoming side's targets but deliberately preserves the pre-cleanup packed EP files.
    val newEnPassant = moveEnPassantAtBoundary(moveEnPassant, completeTurn)
    val key          = Position.computeMoveZobristKey(state, mv, mover, target, newEnPassant, newFlags) ^
      Position.turnKeyToggle(completeTurn)

    new GameState(
      whitePieces = Bitboard(white),
      blackPieces = Bitboard(black),
      pawns = Bitboard(pawns),
      knights = Bitboard(knights),
      bishops = Bitboard(bishops),
      rooks = Bitboard(rooks),
      queens = Bitboard(queens),
      kings = Bitboard(kings),
      mailbox = Mailbox.fromBuilder(mailbox),
      flags = newFlags,
      enPassant = newEnPassant,
      fullMoveNumber = moveNumberAfter(completeTurn),
      zobristKey = key
    )
  }
