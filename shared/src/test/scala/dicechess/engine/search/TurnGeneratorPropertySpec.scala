package dicechess.engine.search

import dicechess.engine.domain.*
import munit.ScalaCheckSuite
import org.scalacheck.Prop.*

class TurnGeneratorPropertySpec extends ScalaCheckSuite:

  private val domainProps  = new dicechess.engine.domain.PropertySpec()
  private val gameStateGen = domainProps.gameStateGen.map(makeConsistentState)

  private def makeConsistentState(state: GameState): GameState =
    val mbArr     = state.mailbox.toArray
    var pawnsBB   = state.pawns
    var bishopsBB = state.bishops

    // 1. Fix pawns on rank 1 or 8 by converting them to Bishops
    var idx = 0
    while idx < 64 do
      val sq = Square.fromIndex(idx)
      if pawnsBB.contains(sq) then
        val rank = idx / 8
        if rank == 0 || rank == 7 then
          val color = mbArr(idx).color
          mbArr(idx) = Piece(color, PieceType.Bishop)
          pawnsBB = pawnsBB.remove(sq)
          bishopsBB = bishopsBB.add(sq)
      idx += 1

    val mailbox = Mailbox.fromBuilder(mbArr)

    // 2. Validate castling rights and clear invalid ones
    val rights    = state.flags.castlingRights
    var newRights = rights

    val whiteKingOnHome =
      mailbox(Square.fromIndex(4)).pieceType == PieceType.King && mailbox(Square.fromIndex(4)).color == Color.White
    val blackKingOnHome =
      mailbox(Square.fromIndex(60)).pieceType == PieceType.King && mailbox(Square.fromIndex(60)).color == Color.Black

    // White King-side (1)
    if (rights & 1) != 0 then
      val rookOnHome =
        mailbox(Square.fromIndex(7)).pieceType == PieceType.Rook && mailbox(Square.fromIndex(7)).color == Color.White
      if !whiteKingOnHome || !rookOnHome then newRights &= ~1

    // White Queen-side (2)
    if (rights & 2) != 0 then
      val rookOnHome =
        mailbox(Square.fromIndex(0)).pieceType == PieceType.Rook && mailbox(Square.fromIndex(0)).color == Color.White
      if !whiteKingOnHome || !rookOnHome then newRights &= ~2

    // Black King-side (4)
    if (rights & 4) != 0 then
      val rookOnHome =
        mailbox(Square.fromIndex(63)).pieceType == PieceType.Rook && mailbox(Square.fromIndex(63)).color == Color.Black
      if !blackKingOnHome || !rookOnHome then newRights &= ~4

    // Black Queen-side (8)
    if (rights & 8) != 0 then
      val rookOnHome =
        mailbox(Square.fromIndex(56)).pieceType == PieceType.Rook && mailbox(Square.fromIndex(56)).color == Color.Black
      if !blackKingOnHome || !rookOnHome then newRights &= ~8

    // 3. Validate en passant. A real ep target only exists directly behind an enemy pawn that just
    // double-pushed; the engine maintains this invariant (a target is created by a double push and
    // dropped when that pawn leaves or is captured). gameStateGen places ep squares freely, which can
    // put a target behind a friendly piece or a non-pawn — MoveGenerator would then emit an
    // en-passant "capture" of that piece and desync makeMove. Keep only targets backed by a real
    // enemy pawn on the capturable rank, with the ep square itself empty.
    val activeColor  = state.activeColor
    val enemyColor   = activeColor.opponent
    val validEPRank  = if activeColor.isWhite then 6 else 3
    val victimOffset = if activeColor.isWhite then -8 else 8
    var newEP        = Bitboard.empty
    var epv          = state.enPassant.value
    while epv != 0 do
      val epIdx = java.lang.Long.numberOfTrailingZeros(epv)
      val epSq  = Square.fromIndex(epIdx)
      val vIdx  = epIdx + victimOffset
      if epSq.rank == validEPRank && vIdx >= 0 && vIdx < 64 && mailbox(epSq).isEmpty then
        val victim = mailbox(Square.fromIndex(vIdx))
        if !victim.isEmpty && victim.pieceType == PieceType.Pawn && victim.color == enemyColor then
          newEP = newEP.add(epSq)
      epv &= epv - 1

    var newEpFiles = 0
    var nv         = newEP.value
    while nv != 0 do
      newEpFiles |= (1 << (java.lang.Long.numberOfTrailingZeros(nv) % 8))
      nv &= nv - 1

    state.copy(
      pawns = pawnsBB,
      bishops = bishopsBB,
      mailbox = mailbox,
      flags = state.flags.withCastlingRights(newRights).withEnPassantFiles(newEpFiles),
      enPassant = newEP
    )

  // The old list-based implementation to verify against
  private def generateAllLegalTurnPathsOld(state: GameState): List[List[Move]] =
    val allPaths = generateAllPathsOld(state).filter(_.nonEmpty)
    if allPaths.isEmpty then Nil
    else
      val maxDice = allPaths.map(diceConsumedOld).maxOption.getOrElse(0)
      allPaths.filter(p => isKingCapturePathOld(state, p) || diceConsumedOld(p) == maxDice)

  private def diceConsumedOld(path: List[Move]): Int =
    path.foldLeft(0)((acc, move) => acc + (if move.isCastling then 2 else 1))

  private def isKingCaptureOld(state: GameState, move: Move): Boolean =
    state.mailbox
      .get(move.toSquare)
      .exists(p => p.pieceType == PieceType.King && p.color != state.activeColor)

  private def isKingCapturePathOld(initialState: GameState, path: List[Move]): Boolean =
    if path.isEmpty then false
    else
      val stateBeforeLast = path.init.foldLeft(initialState) { (s, m) =>
        s.makeMove(m)
      }
      isKingCaptureOld(stateBeforeLast, path.last)

  private def generateAllPathsOld(state: GameState): List[List[Move]] =
    if state.dicePool.isEmpty then List(Nil)
    else
      val branches = List.newBuilder[List[Move]]

      for move <- dicechess.engine.movegen.MoveGenerator.generateMoves(state) do
        val moverType = state.mailbox(move.fromSquare).pieceType
        if isKingCaptureOld(state, move) then branches += List(move)
        else if move.isCastling then
          if state.dicePool.contains(PieceType.King.diceValue) && state.dicePool.contains(PieceType.Rook.diceValue) then
            val afterCastle = state.dicePool.diff(List(PieceType.King.diceValue, PieceType.Rook.diceValue))
            val next        = state.makeMove(move).withDicePool(afterCastle)
            val subPaths    = generateAllPathsOld(next)
            if subPaths.isEmpty || subPaths == List(Nil) then branches += List(move)
            else for p <- subPaths if p.nonEmpty do branches += (move :: p)
        else
          val afterMove = state.dicePool.diff(List(moverType.diceValue))
          assert(
            afterMove.size < state.dicePool.size,
            s"CRITICAL: Dice pool ${state.dicePool} does not decrease! moverType=$moverType"
          )
          val next     = state.makeMove(move).withDicePool(afterMove)
          val subPaths = generateAllPathsOld(next)
          if subPaths.isEmpty || subPaths == List(Nil) then branches += List(move)
          else for p <- subPaths if p.nonEmpty do branches += (move :: p)

      val res = branches.result()
      if res.isEmpty then List(Nil) else res

  private def assertConsistent(state: GameState): Unit =
    val mailbox = state.mailbox
    val white   = state.whitePieces
    val black   = state.blackPieces

    // 1. Check union of all piece bitboards
    val allPiecesBB      = state.whitePieces | state.blackPieces
    val allPiecesTypesBB = state.pawns | state.knights | state.bishops | state.rooks | state.queens | state.kings
    assertEquals(allPiecesBB, allPiecesTypesBB, "Union of color bitboards must equal union of piece type bitboards")
    assertEquals(state.whitePieces & state.blackPieces, Bitboard.empty, "White and Black piece sets must be disjoint")

    // 2. Check mailbox/bitboards correspondence
    var idx = 0
    while idx < 64 do
      val sq    = Square.fromIndex(idx)
      val piece = mailbox(sq)
      if piece.isEmpty then
        assert(!white.contains(sq), s"Square $sq has empty piece but is in whitePieces")
        assert(!black.contains(sq), s"Square $sq has empty piece but is in blackPieces")
        assert(!state.pawns.contains(sq), s"Square $sq has empty piece but is in pawns")
        assert(!state.knights.contains(sq), s"Square $sq has empty piece but is in knights")
        assert(!state.bishops.contains(sq), s"Square $sq has empty piece but is in bishops")
        assert(!state.rooks.contains(sq), s"Square $sq has empty piece but is in rooks")
        assert(!state.queens.contains(sq), s"Square $sq has empty piece but is in queens")
        assert(!state.kings.contains(sq), s"Square $sq has empty piece but is in kings")
      else
        val pt  = piece.pieceType
        val col = piece.color
        if col.isWhite then
          assert(white.contains(sq), s"Square $sq has White $pt but is not in whitePieces")
          assert(!black.contains(sq), s"Square $sq has White $pt but is in blackPieces")
        else
          assert(black.contains(sq), s"Square $sq has Black $pt but is not in blackPieces")
          assert(!white.contains(sq), s"Square $sq has Black $pt but is in whitePieces")

        val inCorrectBB = pt match
          case PieceType.Pawn   => state.pawns.contains(sq)
          case PieceType.Knight => state.knights.contains(sq)
          case PieceType.Bishop => state.bishops.contains(sq)
          case PieceType.Rook   => state.rooks.contains(sq)
          case PieceType.Queen  => state.queens.contains(sq)
          case PieceType.King   => state.kings.contains(sq)
        assert(inCorrectBB, s"Square $sq has $piece but is not in $pt bitboard")

        // Check other bitboards are disjoint
        if pt != PieceType.Pawn then assert(!state.pawns.contains(sq), s"Square $sq has $piece but is in pawns")
        if pt != PieceType.Knight then assert(!state.knights.contains(sq), s"Square $sq has $piece but is in knights")
        if pt != PieceType.Bishop then assert(!state.bishops.contains(sq), s"Square $sq has $piece but is in bishops")
        if pt != PieceType.Rook then assert(!state.rooks.contains(sq), s"Square $sq has $piece but is in rooks")
        if pt != PieceType.Queen then assert(!state.queens.contains(sq), s"Square $sq has $piece but is in queens")
        if pt != PieceType.King then assert(!state.kings.contains(sq), s"Square $sq has $piece but is in kings")
      idx += 1

    // 3. Pawns cannot be on back ranks
    var pawnsVal = state.pawns.value
    while pawnsVal != 0 do
      val pIdx = java.lang.Long.numberOfTrailingZeros(pawnsVal)
      val rank = pIdx / 8
      assert(rank != 0 && rank != 7, s"Pawn on invalid rank $rank at index $pIdx")
      pawnsVal &= pawnsVal - 1

    // 4. Validate castling rights
    val rights          = state.flags.castlingRights
    val whiteKingOnHome =
      mailbox(Square.fromIndex(4)).pieceType == PieceType.King && mailbox(Square.fromIndex(4)).color == Color.White
    val blackKingOnHome =
      mailbox(Square.fromIndex(60)).pieceType == PieceType.King && mailbox(Square.fromIndex(60)).color == Color.Black

    if (rights & 1) != 0 then
      assert(whiteKingOnHome, "White King-side castling is allowed but White King is not on e1")
      val rook = mailbox(Square.fromIndex(7))
      assert(
        rook.pieceType == PieceType.Rook && rook.color == Color.White,
        "White King-side castling is allowed but White Rook is not on h1"
      )
    if (rights & 2) != 0 then
      assert(whiteKingOnHome, "White Queen-side castling is allowed but White King is not on e1")
      val rook = mailbox(Square.fromIndex(0))
      assert(
        rook.pieceType == PieceType.Rook && rook.color == Color.White,
        "White Queen-side castling is allowed but White Rook is not on a1"
      )
    if (rights & 4) != 0 then
      assert(blackKingOnHome, "Black King-side castling is allowed but Black King is not on e8")
      val rook = mailbox(Square.fromIndex(63))
      assert(
        rook.pieceType == PieceType.Rook && rook.color == Color.Black,
        "Black King-side castling is allowed but Black Rook is not on h8"
      )
    if (rights & 8) != 0 then
      assert(blackKingOnHome, "Black Queen-side castling is allowed but Black King is not on e8")
      val rook = mailbox(Square.fromIndex(56))
      assert(
        rook.pieceType == PieceType.Rook && rook.color == Color.Black,
        "Black Queen-side castling is allowed but Black Rook is not on a8"
      )

  property("new TurnGenerator visitor-based paths are identical to the old implementation") {
    forAll(gameStateGen) { (state: GameState) =>
      assertConsistent(state)
      val oldPaths = generateAllLegalTurnPathsOld(state).map(_.map(_.toString).mkString(",")).sorted
      val newPaths = TurnGenerator.generateAllLegalTurnPaths(state).map(_.map(_.toString).mkString(",")).sorted
      assertEquals(newPaths, oldPaths)
    }
  }
