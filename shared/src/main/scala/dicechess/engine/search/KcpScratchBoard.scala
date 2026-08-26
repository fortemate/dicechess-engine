package dicechess.engine.search

import dicechess.engine.domain.*

/** Undo information required to revert an in-place move on a [[KcpScratchBoard]].
  *
  * @param capturedPiece
  *   the piece that occupied the destination square (or victim square for en-passant) prior to the move, or
  *   [[Piece.Empty]] if none
  * @param prevFlags
  *   the full [[GameFlags]] prior to the move (retaining castling rights, EP files, dice slots, half-move clock, and
  *   active color)
  * @param prevEnPassant
  *   the raw [[Bitboard]] value of the en-passant target square(s) prior to the move
  */
final private[search] case class KcpUndoInfo(
    capturedPiece: Piece,
    prevFlags: GameFlags,
    prevEnPassant: Long
)

/** A search-package-private mutable scratch board used on the [[KingCaptureProbability]] hot path.
  *
  * Avoids heap allocations across DFS traversals by updating bitboards via bitwise XORs in place and maintaining a
  * single 64-element mailbox array whose modified slots are restored on `undoMove`.
  */
final private[search] class KcpScratchBoard(
    var whitePieces: Long,
    var blackPieces: Long,
    var pawns: Long,
    var knights: Long,
    var bishops: Long,
    var rooks: Long,
    var queens: Long,
    var kings: Long,
    var enPassant: Long,
    val mailbox: Array[Piece],
    var flags: GameFlags,
    var fullMoveNumber: Int
):

  /** Toggles the given bitboard mask in the piece-type specific bitboard. */
  private inline def togglePiece(pt: PieceType, bb: Long): Unit = pt match
    case PieceType.Pawn   => pawns ^= bb
    case PieceType.Knight => knights ^= bb
    case PieceType.Bishop => bishops ^= bb
    case PieceType.Rook   => rooks ^= bb
    case PieceType.Queen  => queens ^= bb
    case PieceType.King   => kings ^= bb
    case _                => ()

  private inline def promotionPieceType(flags: Int): PieceType = flags match
    case Move.KnightPromotion | Move.KnightPromoCapture => PieceType.Knight
    case Move.BishopPromotion | Move.BishopPromoCapture => PieceType.Bishop
    case Move.RookPromotion | Move.RookPromoCapture     => PieceType.Rook
    case _                                              => PieceType.Queen

  private def updatedCastlingRights(
      rights: Int,
      mover: Piece,
      from: Square,
      target: Piece,
      to: Square,
      isWhite: Boolean
  ): Int =
    var r = rights
    if !target.isEmpty then
      if target.pieceType == PieceType.Rook then
        if to == Square('a', 8) then r &= ~8
        else if to == Square('h', 8) then r &= ~4
        else if to == Square('a', 1) then r &= ~2
        else if to == Square('h', 1) then r &= ~1
      else if target.pieceType == PieceType.King then if target.color.isWhite then r &= ~3 else r &= ~12

    mover.pieceType match
      case PieceType.King =>
        if isWhite then r &= ~3 else r &= ~12
      case PieceType.Rook =>
        if isWhite then
          if from == Square('a', 1) then r &= ~2
          else if from == Square('h', 1) then r &= ~1
        else if from == Square('a', 8) then r &= ~8
        else if from == Square('h', 8) then r &= ~4
      case _ => ()
    r

  /** Applies a pseudo-legal move in place, returning the [[KcpUndoInfo]] needed to revert it.
    *
    * Replicates the exact bitboard, mailbox, castling rights, en-passant, and half-move clock transitions of
    * `GameState.makeMove(Move)`.
    */
  def makeMoveInPlace(mv: Move): KcpUndoInfo =
    val from       = mv.fromSquare
    val to         = mv.toSquare
    val fromIdx    = from.index
    val toIdx      = to.index
    val mover      = mailbox(fromIdx)
    val color      = mover.color
    val isWhite    = color.isWhite
    val fromBB     = 1L << fromIdx
    val toBB       = 1L << toIdx
    val rankOffset = if isWhite then -8 else 8

    val prevFlags     = flags
    val prevEnPassant = enPassant
    var capturedPiece = Piece.Empty
    var newEnPassant  = enPassant & ~toBB

    mv.flags match
      case Move.DoublePawnPush =>
        mailbox(fromIdx) = Piece.Empty
        mailbox(toIdx) = Piece(color, PieceType.Pawn)
        pawns ^= (fromBB | toBB)
        if isWhite then whitePieces ^= (fromBB | toBB) else blackPieces ^= (fromBB | toBB)
        newEnPassant = newEnPassant | (1L << (toIdx + rankOffset))

      case Move.EnPassantCapture =>
        val victimIdx = toIdx + rankOffset
        val victimBB  = 1L << victimIdx
        capturedPiece = mailbox(victimIdx)
        mailbox(fromIdx) = Piece.Empty
        mailbox(toIdx) = Piece(color, PieceType.Pawn)
        mailbox(victimIdx) = Piece.Empty
        pawns = (pawns & ~fromBB & ~victimBB) | toBB
        val moverBB = fromBB | toBB
        if isWhite then
          whitePieces ^= moverBB
          blackPieces &= ~victimBB
        else
          blackPieces ^= moverBB
          whitePieces &= ~victimBB

      case Move.KingCastle =>
        val (rFrom, rTo) = if isWhite then (Square('h', 1), Square('f', 1)) else (Square('h', 8), Square('f', 8))
        val rFromIdx     = rFrom.index
        val rToIdx       = rTo.index
        val rBB          = (1L << rFromIdx) | (1L << rToIdx)
        val kBB          = fromBB | toBB
        kings ^= kBB
        rooks ^= rBB
        val cBB = kBB | rBB
        if isWhite then whitePieces ^= cBB else blackPieces ^= cBB
        mailbox(fromIdx) = Piece.Empty
        mailbox(toIdx) = Piece(color, PieceType.King)
        mailbox(rFromIdx) = Piece.Empty
        mailbox(rToIdx) = Piece(color, PieceType.Rook)

      case Move.QueenCastle =>
        val (rFrom, rTo) = if isWhite then (Square('a', 1), Square('d', 1)) else (Square('a', 8), Square('d', 8))
        val rFromIdx     = rFrom.index
        val rToIdx       = rTo.index
        val rBB          = (1L << rFromIdx) | (1L << rToIdx)
        val kBB          = fromBB | toBB
        kings ^= kBB
        rooks ^= rBB
        val cBB = kBB | rBB
        if isWhite then whitePieces ^= cBB else blackPieces ^= cBB
        mailbox(fromIdx) = Piece.Empty
        mailbox(toIdx) = Piece(color, PieceType.King)
        mailbox(rFromIdx) = Piece.Empty
        mailbox(rToIdx) = Piece(color, PieceType.Rook)

      case _ if mv.isPromotion =>
        val promType = promotionPieceType(mv.flags)
        val target   = mailbox(toIdx)
        mailbox(fromIdx) = Piece.Empty
        mailbox(toIdx) = Piece(color, promType)
        pawns ^= fromBB
        togglePiece(promType, toBB)
        if isWhite then whitePieces ^= (fromBB | toBB) else blackPieces ^= (fromBB | toBB)
        if mover.pieceType == PieceType.Pawn then newEnPassant &= ~(1L << (fromIdx + rankOffset))
        if !target.isEmpty then
          capturedPiece = target
          val capBB = toBB
          if isWhite then blackPieces ^= capBB else whitePieces ^= capBB
          togglePiece(target.pieceType, capBB)
          if target.pieceType == PieceType.Pawn then newEnPassant &= ~(1L << (toIdx - rankOffset))

      case _ =>
        val target = mailbox(toIdx)
        mailbox(fromIdx) = Piece.Empty
        mailbox(toIdx) = mover
        togglePiece(mover.pieceType, fromBB | toBB)
        if isWhite then whitePieces ^= (fromBB | toBB) else blackPieces ^= (fromBB | toBB)
        if mover.pieceType == PieceType.Pawn then newEnPassant &= ~(1L << (fromIdx + rankOffset))
        if !target.isEmpty then
          capturedPiece = target
          val capBB = toBB
          if isWhite then blackPieces ^= capBB else whitePieces ^= capBB
          togglePiece(target.pieceType, capBB)
          if target.pieceType == PieceType.Pawn then newEnPassant &= ~(1L << (toIdx - rankOffset))

    val newCastlingRights = updatedCastlingRights(flags.castlingRights, mover, from, capturedPiece, to, isWhite)
    val isCap             = !capturedPiece.isEmpty || mv.flags == Move.EnPassantCapture
    val newHalfMoveClock  =
      if mover.pieceType == PieceType.Pawn || isCap then 0 else flags.halfMoveClock + 1

    var epFiles = 0
    var epV     = newEnPassant
    while epV != 0L do
      val fileIdx = java.lang.Long.numberOfTrailingZeros(epV) % 8
      epFiles |= (1 << fileIdx)
      epV &= epV - 1L

    enPassant = newEnPassant
    flags = GameFlags.fromList(
      color = flags.activeColor,
      castlingRights = newCastlingRights,
      enPassantFiles = epFiles,
      dicePool = Nil,
      halfMoveClock = newHalfMoveClock
    )

    KcpUndoInfo(capturedPiece, prevFlags, prevEnPassant)

  /** Reverts an in-place move using the recorded [[KcpUndoInfo]]. */
  def undoMove(mv: Move, undo: KcpUndoInfo): Unit =
    val from       = mv.fromSquare
    val to         = mv.toSquare
    val fromIdx    = from.index
    val toIdx      = to.index
    val isWhite    = undo.prevFlags.activeColor.isWhite
    val fromBB     = 1L << fromIdx
    val toBB       = 1L << toIdx
    val rankOffset = if isWhite then -8 else 8

    mv.flags match
      case Move.DoublePawnPush =>
        pawns ^= (fromBB | toBB)
        if isWhite then whitePieces ^= (fromBB | toBB) else blackPieces ^= (fromBB | toBB)
        mailbox(fromIdx) = Piece(undo.prevFlags.activeColor, PieceType.Pawn)
        mailbox(toIdx) = Piece.Empty

      case Move.EnPassantCapture =>
        val victimIdx = toIdx + rankOffset
        val victimBB  = 1L << victimIdx
        mailbox(fromIdx) = Piece(undo.prevFlags.activeColor, PieceType.Pawn)
        mailbox(toIdx) = Piece.Empty
        mailbox(victimIdx) = undo.capturedPiece
        pawns = (pawns & ~toBB) | fromBB
        val moverBB = fromBB | toBB
        if isWhite then
          whitePieces ^= moverBB
          if !undo.capturedPiece.isEmpty then
            blackPieces |= victimBB
            pawns |= victimBB
        else
          blackPieces ^= moverBB
          if !undo.capturedPiece.isEmpty then
            whitePieces |= victimBB
            pawns |= victimBB

      case Move.KingCastle =>
        val (rFrom, rTo) = if isWhite then (Square('h', 1), Square('f', 1)) else (Square('h', 8), Square('f', 8))
        val rFromIdx     = rFrom.index
        val rToIdx       = rTo.index
        val rBB          = (1L << rFromIdx) | (1L << rToIdx)
        val kBB          = fromBB | toBB
        kings ^= kBB
        rooks ^= rBB
        val cBB = kBB | rBB
        if isWhite then whitePieces ^= cBB else blackPieces ^= cBB
        mailbox(fromIdx) = Piece(undo.prevFlags.activeColor, PieceType.King)
        mailbox(toIdx) = Piece.Empty
        mailbox(rFromIdx) = Piece(undo.prevFlags.activeColor, PieceType.Rook)
        mailbox(rToIdx) = Piece.Empty

      case Move.QueenCastle =>
        val (rFrom, rTo) = if isWhite then (Square('a', 1), Square('d', 1)) else (Square('a', 8), Square('d', 8))
        val rFromIdx     = rFrom.index
        val rToIdx       = rTo.index
        val rBB          = (1L << rFromIdx) | (1L << rToIdx)
        val kBB          = fromBB | toBB
        kings ^= kBB
        rooks ^= rBB
        val cBB = kBB | rBB
        if isWhite then whitePieces ^= cBB else blackPieces ^= cBB
        mailbox(fromIdx) = Piece(undo.prevFlags.activeColor, PieceType.King)
        mailbox(toIdx) = Piece.Empty
        mailbox(rFromIdx) = Piece(undo.prevFlags.activeColor, PieceType.Rook)
        mailbox(rToIdx) = Piece.Empty

      case _ if mv.isPromotion =>
        val promType = promotionPieceType(mv.flags)
        pawns ^= fromBB
        togglePiece(promType, toBB)
        if isWhite then whitePieces ^= (fromBB | toBB) else blackPieces ^= (fromBB | toBB)
        if !undo.capturedPiece.isEmpty then
          val capBB = toBB
          if isWhite then blackPieces ^= capBB else whitePieces ^= capBB
          togglePiece(undo.capturedPiece.pieceType, capBB)
        mailbox(fromIdx) = Piece(undo.prevFlags.activeColor, PieceType.Pawn)
        mailbox(toIdx) = undo.capturedPiece

      case _ =>
        val mover = mailbox(toIdx)
        togglePiece(mover.pieceType, fromBB | toBB)
        if isWhite then whitePieces ^= (fromBB | toBB) else blackPieces ^= (fromBB | toBB)
        if !undo.capturedPiece.isEmpty then
          val capBB = toBB
          if isWhite then blackPieces ^= capBB else whitePieces ^= capBB
          togglePiece(undo.capturedPiece.pieceType, capBB)
        mailbox(fromIdx) = mover
        mailbox(toIdx) = undo.capturedPiece

    flags = undo.prevFlags
    enPassant = undo.prevEnPassant

  /** Constructs an immutable [[GameState]] without cloning the mailbox array (read-only view for move generation). */
  def toGameStateReadOnly: GameState =
    GameState(
      whitePieces = Bitboard(whitePieces),
      blackPieces = Bitboard(blackPieces),
      pawns = Bitboard(pawns),
      knights = Bitboard(knights),
      bishops = Bitboard(bishops),
      rooks = Bitboard(rooks),
      queens = Bitboard(queens),
      kings = Bitboard(kings),
      mailbox = Mailbox.fromBuilder(mailbox),
      flags = flags,
      enPassant = Bitboard(enPassant),
      fullMoveNumber = fullMoveNumber
    )

  /** Constructs an independent immutable [[GameState]] with a cloned mailbox array (for tests and diagnostics). */
  def toGameState: GameState =
    GameState(
      whitePieces = Bitboard(whitePieces),
      blackPieces = Bitboard(blackPieces),
      pawns = Bitboard(pawns),
      knights = Bitboard(knights),
      bishops = Bitboard(bishops),
      rooks = Bitboard(rooks),
      queens = Bitboard(queens),
      kings = Bitboard(kings),
      mailbox = Mailbox.fromBuilder(mailbox.clone()),
      flags = flags,
      enPassant = Bitboard(enPassant),
      fullMoveNumber = fullMoveNumber
    )

object KcpScratchBoard:
  /** Creates a [[KcpScratchBoard]] initialized with the contents of `state`. */
  def fromGameState(state: GameState): KcpScratchBoard =
    new KcpScratchBoard(
      whitePieces = state.whitePieces.value,
      blackPieces = state.blackPieces.value,
      pawns = state.pawns.value,
      knights = state.knights.value,
      bishops = state.bishops.value,
      rooks = state.rooks.value,
      queens = state.queens.value,
      kings = state.kings.value,
      enPassant = state.enPassant.value,
      mailbox = state.mailbox.toArray,
      flags = state.flags,
      fullMoveNumber = state.fullMoveNumber
    )
