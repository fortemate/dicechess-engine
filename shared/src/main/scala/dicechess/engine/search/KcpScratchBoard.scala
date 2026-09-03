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
  * Eliminates per-move board-copy allocations across DFS traversals by updating bitboards in place and maintaining a
  * single 64-element mailbox array whose modified slots are restored on `undoMove`. Allocation on this path is confined
  * to the [[KcpUndoInfo]] record produced by `makeMoveInPlace` and the zero-copy [[GameState]] wrapper returned by
  * `toGameStateReadOnly`.
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

  /** Toggles kings and rooks in both piece-type bitboards and the active side bitboard during castling. */
  private inline def toggleCastle(rFromIdx: Int, rToIdx: Int, kBB: Long, isWhite: Boolean): Unit =
    val rBB = (1L << rFromIdx) | (1L << rToIdx)
    kings ^= kBB
    rooks ^= rBB
    val cBB = kBB | rBB
    if isWhite then whitePieces ^= cBB else blackPieces ^= cBB

  private inline def promotionPieceType(flags: Int): PieceType = flags match
    case Move.KnightPromotion | Move.KnightPromoCapture => PieceType.Knight
    case Move.BishopPromotion | Move.BishopPromoCapture => PieceType.Bishop
    case Move.RookPromotion | Move.RookPromoCapture     => PieceType.Rook
    case _                                              => PieceType.Queen

  private inline def capturedPieceCastlingRights(rights: Int, target: Piece, to: Square): Int =
    if target.isEmpty then rights
    else if target.pieceType == PieceType.Rook then
      if to == Square('a', 8) then rights & ~8
      else if to == Square('h', 8) then rights & ~4
      else if to == Square('a', 1) then rights & ~2
      else if to == Square('h', 1) then rights & ~1
      else rights
    else if target.pieceType == PieceType.King then if target.color.isWhite then rights & ~3 else rights & ~12
    else rights

  private inline def moverCastlingRights(rights: Int, mover: Piece, from: Square, isWhite: Boolean): Int =
    mover.pieceType match
      case PieceType.King =>
        if isWhite then rights & ~3 else rights & ~12
      case PieceType.Rook =>
        if isWhite then
          if from == Square('a', 1) then rights & ~2
          else if from == Square('h', 1) then rights & ~1
          else rights
        else if from == Square('a', 8) then rights & ~8
        else if from == Square('h', 8) then rights & ~4
        else rights
      case _ => rights

  private def updatedCastlingRights(
      rights: Int,
      mover: Piece,
      from: Square,
      target: Piece,
      to: Square,
      isWhite: Boolean
  ): Int =
    val r1 = capturedPieceCastlingRights(rights, target, to)
    moverCastlingRights(r1, mover, from, isWhite)

  private inline def clearEpIfPassed(ep: Long, passedIdx: Int): Long =
    if passedIdx >= 0 && passedIdx < 64 then ep & ~(1L << passedIdx) else ep

  private inline def applyDoublePawnPush(fromIdx: Int, toIdx: Int, color: Color, ep: Long): Long =
    val fromBB     = 1L << fromIdx
    val toBB       = 1L << toIdx
    val isWhite    = color.isWhite
    val rankOffset = if isWhite then -8 else 8
    mailbox(fromIdx) = Piece.Empty
    mailbox(toIdx) = Piece(color, PieceType.Pawn)
    pawns ^= (fromBB | toBB)
    if isWhite then whitePieces ^= (fromBB | toBB) else blackPieces ^= (fromBB | toBB)
    ep | (1L << (toIdx + rankOffset))

  private inline def applyEnPassant(
      fromIdx: Int,
      toIdx: Int,
      fromBB: Long,
      toBB: Long,
      color: Color,
      isWhite: Boolean,
      rankOffset: Int
  ): Piece =
    val victimIdx = toIdx + rankOffset
    val victimBB  = 1L << victimIdx
    val captured  = mailbox(victimIdx)
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
    captured

  private inline def applyKingCastle(
      fromIdx: Int,
      toIdx: Int,
      fromBB: Long,
      toBB: Long,
      color: Color,
      isWhite: Boolean
  ): Unit =
    val rFromIdx = if isWhite then 7 else 63
    val rToIdx   = if isWhite then 5 else 61
    toggleCastle(rFromIdx, rToIdx, fromBB | toBB, isWhite)
    mailbox(fromIdx) = Piece.Empty
    mailbox(toIdx) = Piece(color, PieceType.King)
    mailbox(rFromIdx) = Piece.Empty
    mailbox(rToIdx) = Piece(color, PieceType.Rook)

  private inline def applyQueenCastle(
      fromIdx: Int,
      toIdx: Int,
      fromBB: Long,
      toBB: Long,
      color: Color,
      isWhite: Boolean
  ): Unit =
    val rFromIdx = if isWhite then 0 else 56
    val rToIdx   = if isWhite then 3 else 59
    toggleCastle(rFromIdx, rToIdx, fromBB | toBB, isWhite)
    mailbox(fromIdx) = Piece.Empty
    mailbox(toIdx) = Piece(color, PieceType.King)
    mailbox(rFromIdx) = Piece.Empty
    mailbox(rToIdx) = Piece(color, PieceType.Rook)

  private inline def applyStandard(mv: Move, mover: Piece, color: Color, target: Piece, ep: Long): Long =
    val fromIdx    = mv.fromSquare.index
    val toIdx      = mv.toSquare.index
    val fromBB     = 1L << fromIdx
    val toBB       = 1L << toIdx
    val isWhite    = color.isWhite
    val rankOffset = if isWhite then -8 else 8
    var newEp      = ep
    val isPromo    = mv.isPromotion
    val destType   = if isPromo then promotionPieceType(mv.flags) else mover.pieceType
    mailbox(fromIdx) = Piece.Empty
    mailbox(toIdx) = Piece(color, destType)
    if isPromo then
      pawns ^= fromBB
      togglePiece(destType, toBB)
    else togglePiece(destType, fromBB | toBB)
    if isWhite then whitePieces ^= (fromBB | toBB) else blackPieces ^= (fromBB | toBB)
    if mover.pieceType == PieceType.Pawn then newEp = clearEpIfPassed(newEp, fromIdx + rankOffset)
    if !target.isEmpty then
      val capBB = toBB
      if isWhite then blackPieces ^= capBB else whitePieces ^= capBB
      togglePiece(target.pieceType, capBB)
      if target.pieceType == PieceType.Pawn then newEp = clearEpIfPassed(newEp, toIdx - rankOffset)
    newEp

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
        newEnPassant = applyDoublePawnPush(fromIdx, toIdx, color, newEnPassant)

      case Move.EnPassantCapture =>
        capturedPiece = applyEnPassant(fromIdx, toIdx, fromBB, toBB, color, isWhite, rankOffset)

      case Move.KingCastle =>
        applyKingCastle(fromIdx, toIdx, fromBB, toBB, color, isWhite)

      case Move.QueenCastle =>
        applyQueenCastle(fromIdx, toIdx, fromBB, toBB, color, isWhite)

      case _ =>
        capturedPiece = mailbox(toIdx)
        newEnPassant = applyStandard(mv, mover, color, capturedPiece, newEnPassant)

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

  private inline def revertDoublePawnPush(
      fromIdx: Int,
      toIdx: Int,
      fromBB: Long,
      toBB: Long,
      activeColor: Color,
      isWhite: Boolean
  ): Unit =
    pawns ^= (fromBB | toBB)
    if isWhite then whitePieces ^= (fromBB | toBB) else blackPieces ^= (fromBB | toBB)
    mailbox(fromIdx) = Piece(activeColor, PieceType.Pawn)
    mailbox(toIdx) = Piece.Empty

  private inline def revertEnPassant(fromIdx: Int, toIdx: Int, activeColor: Color, capturedPiece: Piece): Unit =
    val fromBB     = 1L << fromIdx
    val toBB       = 1L << toIdx
    val isWhite    = activeColor.isWhite
    val rankOffset = if isWhite then -8 else 8
    val victimIdx  = toIdx + rankOffset
    val victimBB   = 1L << victimIdx
    mailbox(fromIdx) = Piece(activeColor, PieceType.Pawn)
    mailbox(toIdx) = Piece.Empty
    mailbox(victimIdx) = capturedPiece
    pawns = (pawns & ~toBB) | fromBB
    val moverBB = fromBB | toBB
    if isWhite then
      whitePieces ^= moverBB
      if !capturedPiece.isEmpty then
        blackPieces |= victimBB
        pawns |= victimBB
    else
      blackPieces ^= moverBB
      if !capturedPiece.isEmpty then
        whitePieces |= victimBB
        pawns |= victimBB

  private inline def revertKingCastle(
      fromIdx: Int,
      toIdx: Int,
      fromBB: Long,
      toBB: Long,
      activeColor: Color,
      isWhite: Boolean
  ): Unit =
    val rFromIdx = if isWhite then 7 else 63
    val rToIdx   = if isWhite then 5 else 61
    toggleCastle(rFromIdx, rToIdx, fromBB | toBB, isWhite)
    mailbox(fromIdx) = Piece(activeColor, PieceType.King)
    mailbox(toIdx) = Piece.Empty
    mailbox(rFromIdx) = Piece(activeColor, PieceType.Rook)
    mailbox(rToIdx) = Piece.Empty

  private inline def revertQueenCastle(
      fromIdx: Int,
      toIdx: Int,
      fromBB: Long,
      toBB: Long,
      activeColor: Color,
      isWhite: Boolean
  ): Unit =
    val rFromIdx = if isWhite then 0 else 56
    val rToIdx   = if isWhite then 3 else 59
    toggleCastle(rFromIdx, rToIdx, fromBB | toBB, isWhite)
    mailbox(fromIdx) = Piece(activeColor, PieceType.King)
    mailbox(toIdx) = Piece.Empty
    mailbox(rFromIdx) = Piece(activeColor, PieceType.Rook)
    mailbox(rToIdx) = Piece.Empty

  private inline def revertStandard(mv: Move, activeColor: Color, capturedPiece: Piece): Unit =
    val fromIdx = mv.fromSquare.index
    val toIdx   = mv.toSquare.index
    val fromBB  = 1L << fromIdx
    val toBB    = 1L << toIdx
    val isWhite = activeColor.isWhite
    val isPromo = mv.isPromotion
    if isPromo then
      val promType = promotionPieceType(mv.flags)
      pawns ^= fromBB
      togglePiece(promType, toBB)
      mailbox(fromIdx) = Piece(activeColor, PieceType.Pawn)
    else
      val mover = mailbox(toIdx)
      togglePiece(mover.pieceType, fromBB | toBB)
      mailbox(fromIdx) = mover

    if isWhite then whitePieces ^= (fromBB | toBB) else blackPieces ^= (fromBB | toBB)
    if !capturedPiece.isEmpty then
      val capBB = toBB
      if isWhite then blackPieces ^= capBB else whitePieces ^= capBB
      togglePiece(capturedPiece.pieceType, capBB)
    mailbox(toIdx) = capturedPiece

  /** Reverts an in-place move using the recorded [[KcpUndoInfo]]. */
  def undoMove(mv: Move, undo: KcpUndoInfo): Unit =
    val from        = mv.fromSquare
    val to          = mv.toSquare
    val fromIdx     = from.index
    val toIdx       = to.index
    val activeColor = undo.prevFlags.activeColor
    val isWhite     = activeColor.isWhite
    val fromBB      = 1L << fromIdx
    val toBB        = 1L << toIdx

    mv.flags match
      case Move.DoublePawnPush =>
        revertDoublePawnPush(fromIdx, toIdx, fromBB, toBB, activeColor, isWhite)

      case Move.EnPassantCapture =>
        revertEnPassant(fromIdx, toIdx, activeColor, undo.capturedPiece)

      case Move.KingCastle =>
        revertKingCastle(fromIdx, toIdx, fromBB, toBB, activeColor, isWhite)

      case Move.QueenCastle =>
        revertQueenCastle(fromIdx, toIdx, fromBB, toBB, activeColor, isWhite)

      case _ =>
        revertStandard(mv, activeColor, undo.capturedPiece)

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
