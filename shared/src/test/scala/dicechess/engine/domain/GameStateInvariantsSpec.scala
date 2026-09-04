package dicechess.engine.domain

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*
import org.scalacheck.Gen
import org.scalacheck.Arbitrary
import dicechess.engine.search.TurnGenerator
import dicechess.engine.movegen.MoveGenerator

class GameStateInvariantsSpec extends ScalaCheckSuite:

  private val domainProps = new PropertySpec()

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

    if (rights & 1) != 0 then
      val rookOnHome =
        mailbox(Square.fromIndex(7)).pieceType == PieceType.Rook && mailbox(Square.fromIndex(7)).color == Color.White
      if !whiteKingOnHome || !rookOnHome then newRights &= ~1

    if (rights & 2) != 0 then
      val rookOnHome =
        mailbox(Square.fromIndex(0)).pieceType == PieceType.Rook && mailbox(Square.fromIndex(0)).color == Color.White
      if !whiteKingOnHome || !rookOnHome then newRights &= ~2

    if (rights & 4) != 0 then
      val rookOnHome =
        mailbox(Square.fromIndex(63)).pieceType == PieceType.Rook && mailbox(Square.fromIndex(63)).color == Color.Black
      if !blackKingOnHome || !rookOnHome then newRights &= ~4

    if (rights & 8) != 0 then
      val rookOnHome =
        mailbox(Square.fromIndex(56)).pieceType == PieceType.Rook && mailbox(Square.fromIndex(56)).color == Color.Black
      if !blackKingOnHome || !rookOnHome then newRights &= ~8

    // 3. Validate en passant targets
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

  val gameStateWithDiceGen: Gen[GameState] = for
    rawState <- domainProps.gameStateGen
    numDice  <- Gen.choose(1, 3)
    dice     <- Gen.listOfN(numDice, Gen.choose(1, 6))
  yield makeConsistentState(rawState.withDicePool(dice))

  given Arbitrary[GameState] = Arbitrary(gameStateWithDiceGen)

  // --- Invariant assertions ---

  private def assertDisjointSideBitboards(state: GameState): Unit =
    assertEquals(
      state.whitePieces & state.blackPieces,
      Bitboard.empty,
      "White and Black side bitboards must be disjoint"
    )

  private def assertExactPieceTypePartition(state: GameState): Unit =
    val sideUnion      = state.whitePieces | state.blackPieces
    val pieceTypeUnion =
      state.pawns | state.knights | state.bishops | state.rooks | state.queens | state.kings
    assertEquals(
      sideUnion,
      pieceTypeUnion,
      "Union of side bitboards must equal union of piece-type bitboards"
    )

    val types = List(
      ("pawns", state.pawns),
      ("knights", state.knights),
      ("bishops", state.bishops),
      ("rooks", state.rooks),
      ("queens", state.queens),
      ("kings", state.kings)
    )

    for i <- 0 until types.length do
      for j <- i + 1 until types.length do
        val (name1, bb1) = types(i)
        val (name2, bb2) = types(j)
        assertEquals(
          bb1 & bb2,
          Bitboard.empty,
          s"Piece-type bitboards $name1 and $name2 must be disjoint"
        )

  private def assertMailboxBitboardCorrespondence(state: GameState): Unit =
    var idx = 0
    while idx < 64 do
      val sq    = Square.fromIndex(idx)
      val piece = state.mailbox(sq)
      if piece.isEmpty then
        assert(!state.whitePieces.contains(sq), s"Square $sq is empty in mailbox but in whitePieces")
        assert(!state.blackPieces.contains(sq), s"Square $sq is empty in mailbox but in blackPieces")
        assert(!state.pawns.contains(sq), s"Square $sq is empty in mailbox but in pawns")
        assert(!state.knights.contains(sq), s"Square $sq is empty in mailbox but in knights")
        assert(!state.bishops.contains(sq), s"Square $sq is empty in mailbox but in bishops")
        assert(!state.rooks.contains(sq), s"Square $sq is empty in mailbox but in rooks")
        assert(!state.queens.contains(sq), s"Square $sq is empty in mailbox but in queens")
        assert(!state.kings.contains(sq), s"Square $sq is empty in mailbox but in kings")
      else
        val color = piece.color
        val pt    = piece.pieceType

        if color.isWhite then
          assert(state.whitePieces.contains(sq), s"Square $sq has White $pt but not in whitePieces")
          assert(!state.blackPieces.contains(sq), s"Square $sq has White $pt but in blackPieces")
        else
          assert(state.blackPieces.contains(sq), s"Square $sq has Black $pt but not in blackPieces")
          assert(!state.whitePieces.contains(sq), s"Square $sq has Black $pt but in whitePieces")

        val inExpectedBB = pt match
          case PieceType.Pawn   => state.pawns.contains(sq)
          case PieceType.Knight => state.knights.contains(sq)
          case PieceType.Bishop => state.bishops.contains(sq)
          case PieceType.Rook   => state.rooks.contains(sq)
          case PieceType.Queen  => state.queens.contains(sq)
          case PieceType.King   => state.kings.contains(sq)
        assert(inExpectedBB, s"Square $sq has $piece but not in $pt bitboard")

        if pt != PieceType.Pawn then assert(!state.pawns.contains(sq), s"Square $sq has $piece but in pawns")
        if pt != PieceType.Knight then assert(!state.knights.contains(sq), s"Square $sq has $piece but in knights")
        if pt != PieceType.Bishop then assert(!state.bishops.contains(sq), s"Square $sq has $piece but in bishops")
        if pt != PieceType.Rook then assert(!state.rooks.contains(sq), s"Square $sq has $piece but in rooks")
        if pt != PieceType.Queen then assert(!state.queens.contains(sq), s"Square $sq has $piece but in queens")
        if pt != PieceType.King then assert(!state.kings.contains(sq), s"Square $sq has $piece but in kings")

      idx += 1

  private def removeDice(pool: List[Int], required: List[Int]): List[Int] =
    required.foldLeft(pool) { (acc, d) =>
      val idx = acc.indexOf(d)
      assert(idx >= 0, s"Die $d was expected in pool $acc but was missing")
      acc.patch(idx, Nil, 1)
    }

  private def assertDiceConsumption(state: GameState, move: Move, nextState: GameState): Unit =
    val moverType    = state.mailbox(move.fromSquare).pieceType
    val requiredDice =
      if move.isCastling then List(PieceType.King.diceValue, PieceType.Rook.diceValue)
      else List(moverType.diceValue)

    val expectedPool = removeDice(state.dicePool, requiredDice)
    assertEquals(
      nextState.dicePool,
      expectedPool,
      s"Move $move by $moverType did not consume matching dice $requiredDice from pool ${state.dicePool}"
    )

  // --- Properties ---

  property(
    "Disjoint side bitboards — (state.whitePieces & state.blackPieces) == Bitboard.empty holds after every move"
  ) {
    forAll(gameStateWithDiceGen) { (state: GameState) =>
      assertDisjointSideBitboards(state)

      val turnPaths = TurnGenerator.generateAllLegalTurnPaths(state)
      for path <- turnPaths do
        var curr = state
        for move <- path do
          val survived = curr.diceAfter(move)
          val next     = curr.makeMove(move).withDiceSlotsOf(survived)
          assertDisjointSideBitboards(next)
          curr = next
        val ended = curr.endTurn()
        assertDisjointSideBitboards(ended)

      val pseudoMoves = MoveGenerator.generateMoves(state)
      for move <- pseudoMoves do
        val mm     = MicroMove(move.fromSquare, move.toSquare, move.promotionPieceType)
        val mmNext = state.makeMove(mm)
        assertDisjointSideBitboards(mmNext)
    }
  }

  property("Exact piece-type partition — Piece-type bitboards are pairwise disjoint, and union equals side union") {
    forAll(gameStateWithDiceGen) { (state: GameState) =>
      assertExactPieceTypePartition(state)

      val turnPaths = TurnGenerator.generateAllLegalTurnPaths(state)
      for path <- turnPaths do
        var curr = state
        for move <- path do
          val survived = curr.diceAfter(move)
          val next     = curr.makeMove(move).withDiceSlotsOf(survived)
          assertExactPieceTypePartition(next)
          curr = next
        val ended = curr.endTurn()
        assertExactPieceTypePartition(ended)

      val pseudoMoves = MoveGenerator.generateMoves(state)
      for move <- pseudoMoves do
        val mm     = MicroMove(move.fromSquare, move.toSquare, move.promotionPieceType)
        val mmNext = state.makeMove(mm)
        assertExactPieceTypePartition(mmNext)
    }
  }

  property("Mailbox-bitboard correspondence — For every square 0 <= sq < 64, mailbox(sq) reflects bitboard masks") {
    forAll(gameStateWithDiceGen) { (state: GameState) =>
      assertMailboxBitboardCorrespondence(state)

      val turnPaths = TurnGenerator.generateAllLegalTurnPaths(state)
      for path <- turnPaths do
        var curr = state
        for move <- path do
          val survived = curr.diceAfter(move)
          val next     = curr.makeMove(move).withDiceSlotsOf(survived)
          assertMailboxBitboardCorrespondence(next)
          curr = next
        val ended = curr.endTurn()
        assertMailboxBitboardCorrespondence(ended)

      val pseudoMoves = MoveGenerator.generateMoves(state)
      for move <- pseudoMoves do
        val mm     = MicroMove(move.fromSquare, move.toSquare, move.promotionPieceType)
        val mmNext = state.makeMove(mm)
        assertMailboxBitboardCorrespondence(mmNext)
    }
  }

  property("Dice consumption invariant — Any move made consumes valid matching dice from flags.dicePool") {
    forAll(gameStateWithDiceGen) { (state: GameState) =>
      val turnPaths = TurnGenerator.generateAllLegalTurnPaths(state)
      for path <- turnPaths do
        var curr = state
        for move <- path do
          val survived = curr.diceAfter(move)
          assert(survived.isValid, s"Move $move should produce valid survived flags from state $curr")
          val next = curr.makeMove(move).withDiceSlotsOf(survived)
          assertDiceConsumption(curr, move, next)
          curr = next

      val pseudoMoves = MoveGenerator.generateMoves(state)
      for move <- pseudoMoves do
        val survived = state.diceAfter(move)
        if survived.isValid then
          val next = state.makeMove(move).withDiceSlotsOf(survived)
          assertDiceConsumption(state, move, next)

          if !move.isCastling then
            val mm           = MicroMove(move.fromSquare, move.toSquare, move.promotionPieceType)
            val mmNext       = state.makeMove(mm)
            val moverType    = state.mailbox(move.fromSquare).pieceType
            val expectedPool = removeDice(state.dicePool, List(moverType.diceValue))
            assertEquals(
              mmNext.dicePool,
              expectedPool,
              s"MicroMove $mm by $moverType did not consume $moverType die from pool ${state.dicePool}"
            )
    }
  }
