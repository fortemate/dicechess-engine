package dicechess.engine.movegen

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*
import org.scalacheck.Gen
import dicechess.engine.domain.*
import dicechess.engine.search.TurnGenerator

/** Helper object simulating the legal moves filter API with explicit state and dice pool parameters.
  */
object MutableLegalMovesFilter:
  def filterMaximalMoves(state: GameState, dice: List[Int]): List[Move] =
    LegalMovesFilter.filterMaximalMoves(state.withDicePool(dice))

/** Property-based test suite asserting move generator purity, idempotency, side-effect freedom, and dice-pool
  * permutation invariance.
  */
class MoveGeneratorPuritySpec extends ScalaCheckSuite:

  private val domainProps = new dicechess.engine.domain.PropertySpec()

  // Benchmark FENs to cover known edge cases (castling, promotion, king captures)
  private val benchmarkFens = List(
    FenParser.InitialPosition,
    "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1", // Position 2
    "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",                            // Position 3
    "r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1",                   // Castling Position
    "k7/4P3/8/8/8/8/8/4K3 w - - 0 1",                                       // Promotion Position
    "8/8/8/2k5/8/1N6/2P5/K7 w - - 0 1",                                     // King-Capture available (Nb3xc5)
    "n1n5/PPPk4/8/8/8/8/4Kppp/5N1N b - - 0 1"                               // King-Capture available, Black to move
  )

  private def parseFen(fen: String): GameState =
    FenParser.parse(fen).getOrElse(sys.error(s"Failed to parse FEN: $fen"))

  /** Normalizes a GameState to satisfy physical board invariants (no pawns on ranks 1/8, valid castling/en-passant).
    */
  private def makeConsistentState(state: GameState): GameState =
    val mbArr     = state.mailbox.toArray
    var pawnsBB   = state.pawns
    var bishopsBB = state.bishops

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

  // Generator combining benchmark FENs and random consistent GameStates
  private val gameStateGen: Gen[GameState] =
    Gen.frequency(
      (4, Gen.oneOf(benchmarkFens).map(parseFen)),
      (6, domainProps.gameStateGen.map(makeConsistentState))
    )

  // Generator for dice pools of 1 to 3 dice (values 1 to 6)
  private val diceGen: Gen[List[Int]] =
    Gen.choose(1, 3).flatMap(Gen.listOfN(_, Gen.choose(1, 6)))

  // ── Property 1: Idempotency ───────────────────────────────────────────────────

  property(
    "Property 1: Consecutive invocations of MutableLegalMovesFilter.filterMaximalMoves on same position yield identical move sequences"
  ) {
    forAll(gameStateGen, diceGen) { (state, dice) =>
      val res1 = MutableLegalMovesFilter.filterMaximalMoves(state, dice)
      val res2 = MutableLegalMovesFilter.filterMaximalMoves(state, dice)
      val res3 = MutableLegalMovesFilter.filterMaximalMoves(state, dice)

      assertEquals(res1, res2, "2nd invocation must yield identical moves to 1st invocation")
      assertEquals(res2, res3, "3rd invocation must yield identical moves to 2nd invocation")
    }
  }

  property(
    "Property 1 (extended): Consecutive invocations of MoveGenerator.generateMoves and TurnGenerator.generateAllLegalTurnPaths are idempotent"
  ) {
    forAll(gameStateGen, diceGen) { (state, dice) =>
      val st = state.withDicePool(dice)

      val pseudo1 = MoveGenerator.generateMoves(st)
      val pseudo2 = MoveGenerator.generateMoves(st)
      assertEquals(pseudo1, pseudo2, "MoveGenerator.generateMoves must be idempotent")

      val paths1 = TurnGenerator.generateAllLegalTurnPaths(st)
      val paths2 = TurnGenerator.generateAllLegalTurnPaths(st)
      assertEquals(paths1, paths2, "TurnGenerator.generateAllLegalTurnPaths must be idempotent")
    }
  }

  // ── Property 2: Dice pool permutation invariance ───────────────────────────

  property(
    "Property 2: Calling move filtering with dice and any permutation of dice returns the exact same canonical set of legal turns"
  ) {
    forAll(gameStateGen, diceGen) { (state, dice) =>
      val baseMoves = MutableLegalMovesFilter.filterMaximalMoves(state, dice).map(_.toUci).sorted

      val permutations = dice.permutations.toList
      for p <- permutations do
        val permMoves = MutableLegalMovesFilter.filterMaximalMoves(state, p).map(_.toUci).sorted
        assertEquals(
          permMoves,
          baseMoves,
          s"Dice pool permutation $p produced different moves than base dice pool $dice"
        )
    }
  }

  property(
    "Property 2 (extended): TurnGenerator legal turn paths are invariant under dice pool permutations"
  ) {
    forAll(gameStateGen, diceGen) { (state, dice) =>
      val basePathStrings = TurnGenerator
        .generateAllLegalTurnPaths(state.withDicePool(dice))
        .map(_.map(_.toUci).mkString(","))
        .sorted

      val permutations = dice.permutations.toList
      for p <- permutations do
        val permPathStrings = TurnGenerator
          .generateAllLegalTurnPaths(state.withDicePool(p))
          .map(_.map(_.toUci).mkString(","))
          .sorted
        assertEquals(
          permPathStrings,
          basePathStrings,
          s"TurnGenerator paths for permutation $p differed from base dice pool $dice"
        )
    }
  }

  // ── Property 3: State immutability ─────────────────────────────────────────

  property("Property 3: Calling move generation does not alter state.zobristHash, state.mailbox, or state.flags") {
    forAll(gameStateGen, diceGen) { (state, dice) =>
      val st = state.withDicePool(dice)

      val zobristBefore   = st.zobristHash
      val mailboxBefore   = st.mailbox
      val flagsBefore     = st.flags
      val enPassantBefore = st.enPassant
      val whiteBefore     = st.whitePieces
      val blackBefore     = st.blackPieces

      // Perform move generation & filtering ops
      val _ = MoveGenerator.generateMoves(st)
      val _ = MoveGenerator.generateAllMoves(st)
      val _ = LegalMovesFilter.filterMaximalMoves(st)
      val _ = MutableLegalMovesFilter.filterMaximalMoves(st, dice)
      val _ = TurnGenerator.generateAllLegalTurnPaths(st)

      // Assert complete state immutability
      assertEquals(st.zobristHash, zobristBefore, "zobristHash must remain unchanged after move generation")
      assertEquals(st.mailbox, mailboxBefore, "mailbox must remain unchanged after move generation")
      assertEquals(st.flags, flagsBefore, "flags must remain unchanged after move generation")
      assertEquals(st.enPassant, enPassantBefore, "enPassant must remain unchanged after move generation")
      assertEquals(st.whitePieces, whiteBefore, "whitePieces must remain unchanged after move generation")
      assertEquals(st.blackPieces, blackBefore, "blackPieces must remain unchanged after move generation")
    }
  }
