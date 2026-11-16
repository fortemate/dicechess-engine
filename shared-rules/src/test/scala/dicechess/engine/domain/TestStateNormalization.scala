// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.domain

// Repairs synthetic generator positions without changing the generators or their random consumption.
private[engine] object TestStateNormalization:

  def normalize(state: GameState): GameState =
    val mailboxBuilder = state.mailbox.toArray
    var pawns          = state.pawns
    var bishops        = state.bishops
    var index          = 0
    while index < 64 do
      val square = Square.fromIndex(index)
      if pawns.contains(square) && isBackRank(index) then
        mailboxBuilder(index) = Piece(mailboxBuilder(index).color, PieceType.Bishop)
        pawns = pawns.remove(square)
        bishops = bishops.add(square)
      index += 1

    val mailbox        = Mailbox.fromBuilder(mailboxBuilder)
    val castlingRights = validCastlingRights(mailbox, state.flags.castlingRights)
    val enPassant      = validEnPassant(state, mailbox)
    state.copy(
      pawns = pawns,
      bishops = bishops,
      mailbox = mailbox,
      flags = state.flags
        .withCastlingRights(castlingRights)
        .withEnPassantFiles(enPassantFiles(enPassant)),
      enPassant = enPassant
    )

  private def isBackRank(index: Int): Boolean =
    val rank = index / 8
    rank == 0 || rank == 7

  private def validCastlingRights(mailbox: Mailbox, rights: Int): Int =
    val whiteKing = isPiece(mailbox, 4, PieceType.King, Color.White)
    val blackKing = isPiece(mailbox, 60, PieceType.King, Color.Black)
    var result    = retainRight(mailbox, rights, 1, whiteKing, 7, Color.White)
    result = retainRight(mailbox, result, 2, whiteKing, 0, Color.White)
    result = retainRight(mailbox, result, 4, blackKing, 63, Color.Black)
    retainRight(mailbox, result, 8, blackKing, 56, Color.Black)

  private def retainRight(
      mailbox: Mailbox,
      rights: Int,
      right: Int,
      kingOnHome: Boolean,
      rook: Int,
      color: Color
  ): Int =
    if (rights & right) != 0 then
      val rookOnHome = isPiece(mailbox, rook, PieceType.Rook, color)
      if !kingOnHome || !rookOnHome then rights & ~right else rights
    else rights

  private def isPiece(mailbox: Mailbox, index: Int, pieceType: PieceType, color: Color): Boolean =
    val piece = mailbox(Square.fromIndex(index))
    piece.pieceType == pieceType && piece.color == color

  // A target must be empty, on the capturable rank and directly behind an enemy pawn.
  // Freely generated targets could otherwise capture friendly pieces or non-pawns in the move oracle.
  private def validEnPassant(state: GameState, mailbox: Mailbox): Bitboard =
    val isWhite      = state.activeColor.isWhite
    val rank         = if isWhite then 6 else 3
    val victimOffset = if isWhite then -8 else 8
    val enemy        = state.activeColor.opponent
    var result       = Bitboard.empty
    var remaining    = state.enPassant.value
    while remaining != 0L do
      val index = java.lang.Long.numberOfTrailingZeros(remaining)
      if validTarget(mailbox, index, rank, victimOffset, enemy) then result = result.add(Square.fromIndex(index))
      remaining &= remaining - 1
    result

  private def validTarget(mailbox: Mailbox, index: Int, rank: Int, victimOffset: Int, enemy: Color): Boolean =
    val square      = Square.fromIndex(index)
    val victimIndex = index + victimOffset
    if square.rank == rank && victimIndex >= 0 && victimIndex < 64 && mailbox(square).isEmpty then
      val victim = mailbox(Square.fromIndex(victimIndex))
      !victim.isEmpty && victim.pieceType == PieceType.Pawn && victim.color == enemy
    else false

  private def enPassantFiles(enPassant: Bitboard): Int =
    var files     = 0
    var remaining = enPassant.value
    while remaining != 0L do
      files |= 1 << (java.lang.Long.numberOfTrailingZeros(remaining) % 8)
      remaining &= remaining - 1
    files
