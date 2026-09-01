package dicechess.engine.movegen

import dicechess.engine.domain.*
import scala.collection.mutable
import scala.util.boundary, boundary.break

/** Unified move generator for Dice Chess. Coordinates between specialized generators for pawns, leapers, and sliding
  * pieces.
  */
object MoveGenerator {

  /** Generates all pseudo-legal moves for the active color based on the current dice pool.
    *
    * Maps each available dice roll in the pool to a [[dicechess.engine.domain.PieceType]] via
    * [[dicechess.engine.domain.PieceType.fromDice]] and delegates to [[generatePieceMoves]]. Returns a concatenated
    * list of all possible moves.
    *
    * @param state
    *   current game state (which includes the dice pool)
    * @return
    *   pseudo-legal moves for all available dice
    */
  def generateMoves(state: GameState): List[Move] =
    state.dicePool.distinct.flatMap { diceRoll =>
      PieceType
        .fromDice(diceRoll)
        .map { pt =>
          generatePieceMoves(state, pt)
        }
        .getOrElse(Nil)
    }

  /** Generates all pseudo-legal moves for all pieces of the active color (standard chess).
    *
    * Iterates over every [[dicechess.engine.domain.PieceType]] and concatenates the results of [[generatePieceMoves]].
    * Useful for perft testing and for positions where a dice roll is not applicable.
    *
    * @param state
    *   current game state
    * @return
    *   combined pseudo-legal move list for all piece types
    */
  def generateAllMoves(state: GameState): List[Move] = {
    val stateWithCastlingDice = state.withDicePool(List(PieceType.King.diceValue, PieceType.Rook.diceValue))
    PieceType.all.flatMap(pt => generatePieceMoves(stateWithCastlingDice, pt))
  }

  /** Dispatches move generation to the correct subsystem for `pieceType`.
    *
    * Note: generates pseudo-legal moves without validating dice-pool constraints or castling legality, which callers
    * (such as search or filtering layers) must enforce.
    */
  private[engine] def generatePieceMoves(state: GameState, pieceType: PieceType): List[Move] = {
    val color        = state.activeColor
    val isWhite      = color.isWhite
    val enemyPieces  = if isWhite then state.blackPieces else state.whitePieces
    val allPieces    = state.whitePieces | state.blackPieces
    val emptySquares = ~allPieces

    pieceType match {
      case PieceType.Pawn   => generatePawnMoves(state, color, emptySquares, enemyPieces)
      case PieceType.Knight => generateLeaperMoves(state, PieceType.Knight, color, enemyPieces)
      case PieceType.Bishop => generateSlidingMoves(state, PieceType.Bishop, color, allPieces, enemyPieces)
      case PieceType.Rook   => generateSlidingMoves(state, PieceType.Rook, color, allPieces, enemyPieces)
      case PieceType.Queen  => generateSlidingMoves(state, PieceType.Queen, color, allPieces, enemyPieces)
      case PieceType.King   => generateLeaperMoves(state, PieceType.King, color, enemyPieces)
      case _                => Nil
    }
  }

  /** Generates pseudo-legal pawn moves for the active side.
    *
    * Produces (per pawn):
    *
    *   - **Single push** — quiet move, or four promotion moves on the back rank.
    *   - **Double push** — only from the starting rank, sets the en-passant square flag.
    *   - **Diagonal captures** (east and west) — standard or promotion captures.
    *   - **En-passant capture** — when [[GameState.enPassant]] is set and the pawn can reach it.
    *
    * Returns early with `Nil` when the active side has no pawns.
    */
  private def generatePawnMoves(
      state: GameState,
      color: Color,
      emptySquares: Bitboard,
      enemies: Bitboard
  ): List[Move] = boundary {
    val isWhite = color.isWhite
    val myPawns = if isWhite then state.whitePieces & state.pawns else state.blackPieces & state.pawns
    if myPawns.isEmpty then break(Nil)

    val enemyKings = enemies & state.kings

    val moves = List.newBuilder[Move]
    var p     = myPawns.value
    while p != 0 do {
      val fromIdx = java.lang.Long.numberOfTrailingZeros(p)
      val from    = Square.fromIndex(fromIdx)
      val fromBB  = Bitboard.fromSquare(from)

      addPawnPushes(from, fromBB, emptySquares, color, moves)

      val east = PawnGeneration.eastCaptures(fromBB, enemies, color)
      val west = PawnGeneration.westCaptures(fromBB, enemies, color)
      addPawnCaptures(from, east, color, enemyKings, moves)
      addPawnCaptures(from, west, color, enemyKings, moves)

      addPawnEnPassant(from, fromBB, state.enPassant, color, moves)

      p &= p - 1
    }
    moves.result()
  }

  private def addPawnPushes(
      from: Square,
      fromBB: Bitboard,
      emptySquares: Bitboard,
      color: Color,
      moves: mutable.Builder[Move, List[Move]]
  ): Unit = {
    val push1     = PawnGeneration.singlePushes(fromBB, emptySquares, color)
    val push1Prom = PawnGeneration.promotionSquares(push1, color)
    val push1Std  = PawnGeneration.nonPromotionSquares(push1, color)

    if !push1Prom.isEmpty then {
      val to = Square.fromIndex(java.lang.Long.numberOfTrailingZeros(push1Prom.value))
      moves += Move(from, to, Move.QueenPromotion)
      moves += Move(from, to, Move.RookPromotion)
      moves += Move(from, to, Move.BishopPromotion)
      moves += Move(from, to, Move.KnightPromotion)
    }

    if !push1Std.isEmpty then {
      val to1 = Square.fromIndex(java.lang.Long.numberOfTrailingZeros(push1Std.value))
      moves += Move(from, to1, Move.QuietMove)

      val push2 = PawnGeneration.doublePushes(push1Std, emptySquares, color)
      if !push2.isEmpty then {
        val to2 = Square.fromIndex(java.lang.Long.numberOfTrailingZeros(push2.value))
        moves += Move(from, to2, Move.DoublePawnPush)
      }
    }
  }

  private def addPawnEnPassant(
      from: Square,
      fromBB: Bitboard,
      enPassant: Bitboard,
      color: Color,
      moves: mutable.Builder[Move, List[Move]]
  ): Unit = {
    val validEPRank = if color.isWhite then 6 else 3
    var ep          = enPassant.value
    while ep != 0 do {
      val epIdx    = java.lang.Long.numberOfTrailingZeros(ep)
      val epSquare = Square.fromIndex(epIdx)
      if epSquare.rank == validEPRank then {
        val epBB     = Bitboard.fromSquare(epSquare)
        val epEast   = PawnGeneration.eastCaptures(fromBB, epBB, color)
        val epWest   = PawnGeneration.westCaptures(fromBB, epBB, color)
        val epTarget = epEast | epWest
        if !epTarget.isEmpty then {
          moves += Move(from, epSquare, Move.EnPassantCapture)
        }
      }
      ep &= ep - 1
    }
  }

  /** Generates pseudo-legal moves for a leaper piece (Knight or King).
    *
    * Looks up precomputed attack masks from [[LeaperAttacks]], masks out friendly pieces, and emits quiet or capture
    * moves. When `pt` is `King`, also calls [[generateCastlingMoves]] after the normal attack enumeration.
    */
  private def generateLeaperMoves(state: GameState, pt: PieceType, color: Color, enemies: Bitboard): List[Move] = {
    val myPieces     = if color.isWhite then state.whitePieces else state.blackPieces
    val typeBB       = if pt == PieceType.Knight then state.knights else state.kings
    val activePieces = myPieces & typeBB

    val moves = List.newBuilder[Move]
    var p     = activePieces.value
    while p != 0 do {
      val fromIdx = java.lang.Long.numberOfTrailingZeros(p)
      val from    = Square.fromIndex(fromIdx)

      val attacks =
        if pt == PieceType.Knight then LeaperAttacks.knightAttacksFor(from) else LeaperAttacks.kingAttacksFor(from)
      val legal = attacks & ~myPieces

      var a = legal.value
      while a != 0 do {
        val toIdx = java.lang.Long.numberOfTrailingZeros(a)
        val to    = Square.fromIndex(toIdx)
        val flags = if enemies.contains(to) then Move.Capture else Move.QuietMove
        moves += Move(from, to, flags)
        a &= a - 1
      }

      // --- Castling (only for King) ---
      if pt == PieceType.King then {
        generateCastlingMoves(state, color, moves)
      }

      p &= p - 1
    }
    moves.result()
  }

  /** Appends castling moves (king-side and queen-side) for `color` to `moves`.
    *
    * Unconditionally attempts castling for the King, delegating each side to [[tryCastle]], which checks castling
    * rights, king/rook placement, and path clearance. Note: Dice validation (checking that both King and Rook dice are
    * available) is the responsibility of the caller (e.g. [[TurnGenerator]] or [[LegalMovesFilter]]). In Dice Chess,
    * attacked transit/destination squares do not block castling.
    */
  private def generateCastlingMoves(state: GameState, color: Color, moves: mutable.Builder[Move, List[Move]]): Unit = {

    val allPieces        = state.whitePieces | state.blackPieces
    val myPieces         = if color.isWhite then state.whitePieces else state.blackPieces
    val rank             = if color.isWhite then 1 else 8
    val (kRight, qRight) = if color.isWhite then ('K', 'Q') else ('k', 'q')
    tryCastle(state, myPieces, allPieces, moves, kRight, rank, kingSide = true)
    tryCastle(state, myPieces, allPieces, moves, qRight, rank, kingSide = false)
  }

  /** Returns `true` when every square the rook traverses between king and rook is empty. */
  private def isCastlingPathClear(allPieces: Bitboard, rank: Int, kingSide: Boolean): Boolean =
    if kingSide then !allPieces.contains(Square('f', rank)) && !allPieces.contains(Square('g', rank))
    else
      !allPieces.contains(Square('b', rank)) &&
      !allPieces.contains(Square('c', rank)) &&
      !allPieces.contains(Square('d', rank))

  /** Appends a castling move if all three preconditions are satisfied:
    *
    *   1. The castling right character (`K`, `Q`, `k`, or `q`) is present in [[GameState.castlingRights]].
    *   2. The mover's king stands on its home square (`e1`/`e8`) and the mover's rook on the corresponding corner
    *      (`h`-file for king-side, `a`-file for queen-side). Castling is hard-coded to those squares, and a rights
    *      character that contradicts piece placement — impossible via legal play, but reachable through untrusted FEN
    *      input such as 180°-rotated scrapes (#594) — must not emit a move: `makeMove` would blindly relocate whatever
    *      the mailbox holds there, desyncing the mailbox from the bitboards.
    *   3. Every square on the rook's path between king and rook is empty.
    *
    * @param myPieces
    *   bitboard of the mover's pieces (used to verify the king and rook placement)
    * @param right
    *   castling-right character to check (e.g. `'K'` for White king-side)
    * @param rank
    *   back rank of the castling side (1 for White, 8 for Black)
    * @param kingSide
    *   `true` for king-side (O-O), `false` for queen-side (O-O-O)
    */
  private def tryCastle(
      state: GameState,
      myPieces: Bitboard,
      allPieces: Bitboard,
      moves: mutable.Builder[Move, List[Move]],
      right: Char,
      rank: Int,
      kingSide: Boolean
  ): Unit =
    if state.castlingRights.contains(right) then
      val kingHome     = Square('e', rank)
      val rookHome     = Square(if kingSide then 'h' else 'a', rank)
      val piecesPlaced =
        (myPieces & state.kings).contains(kingHome) && (myPieces & state.rooks).contains(rookHome)
      if piecesPlaced && isCastlingPathClear(allPieces, rank, kingSide) then
        val toSquare = if kingSide then Square('g', rank) else Square('c', rank)
        val flags    = if kingSide then Move.KingCastle else Move.QueenCastle
        moves += Move(kingHome, toSquare, flags)

  /** Generates pseudo-legal moves for a sliding piece (Bishop, Rook, or Queen).
    *
    * Uses [[MagicBitboards]] for O(1) attack-set lookup, then masks out friendly pieces and emits quiet or capture
    * moves.
    */
  private def generateSlidingMoves(
      state: GameState,
      pt: PieceType,
      color: Color,
      allPieces: Bitboard,
      enemies: Bitboard
  ): List[Move] = {
    val myPieces = if color.isWhite then state.whitePieces else state.blackPieces
    val typeBB   = pt match {
      case PieceType.Bishop => state.bishops
      case PieceType.Rook   => state.rooks
      case PieceType.Queen  => state.queens
      case _                => Bitboard.empty
    }
    val activePieces = myPieces & typeBB

    val moves = List.newBuilder[Move]
    var p     = activePieces.value
    while p != 0 do {
      val fromIdx = java.lang.Long.numberOfTrailingZeros(p)
      val from    = Square.fromIndex(fromIdx)

      val attacks = pt match {
        case PieceType.Bishop => MagicBitboards.bishopAttacks(from, allPieces)
        case PieceType.Rook   => MagicBitboards.rookAttacks(from, allPieces)
        case PieceType.Queen  => MagicBitboards.queenAttacks(from, allPieces)
        case _                => Bitboard.empty
      }
      val legal = attacks & ~myPieces

      var a = legal.value
      while a != 0 do {
        val toIdx = java.lang.Long.numberOfTrailingZeros(a)
        val to    = Square.fromIndex(toIdx)
        val flags = if enemies.contains(to) then Move.Capture else Move.QuietMove
        moves += Move(from, to, flags)
        a &= a - 1
      }
      p &= p - 1
    }
    moves.result()
  }

  /** Appends pawn capture moves from `from` to every set bit in `targets`.
    *
    * Promotion-rank captures expand to four moves each (Queen, Rook, Bishop, Knight). All other captures, including
    * captures of a King on the promotion rank (since capturing the King ends the game immediately), emit a single
    * `Capture` move.
    *
    * @param from
    *   square the capturing pawn is on
    * @param targets
    *   bitboard of reachable enemy squares
    * @param color
    *   color of the capturing pawn (used to identify the promotion rank)
    * @param enemyKings
    *   bitboard of enemy kings, used to prevent promotion logic on king captures
    * @param moves
    *   mutable builder to accumulate generated moves
    */
  private def addPawnCaptures(
      from: Square,
      targets: Bitboard,
      color: Color,
      enemyKings: Bitboard,
      moves: mutable.Builder[Move, List[Move]]
  ): Unit = {
    val kingTargets   = targets & enemyKings
    val normalTargets = targets & ~enemyKings

    val prom = PawnGeneration.promotionSquares(normalTargets, color)
    val std  = PawnGeneration.nonPromotionSquares(normalTargets, color) | kingTargets
    var cp   = prom.value
    while cp != 0 do {
      val to = Square.fromIndex(java.lang.Long.numberOfTrailingZeros(cp))
      moves += Move(from, to, Move.QueenPromoCapture)
      moves += Move(from, to, Move.RookPromoCapture)
      moves += Move(from, to, Move.BishopPromoCapture)
      moves += Move(from, to, Move.KnightPromoCapture)
      cp &= cp - 1
    }
    var cs = std.value
    while cs != 0 do {
      val to = Square.fromIndex(java.lang.Long.numberOfTrailingZeros(cs))
      moves += Move(from, to, Move.Capture)
      cs &= cs - 1
    }
  }

  /** Returns the set of `attackerColor` pieces that attack `sq`, or an empty bitboard if none do.
    *
    * Checks attack patterns in priority order: pawns → knights → diagonal sliders (bishop/queen) → orthogonal sliders
    * (rook/queen) → king. Returns the first non-empty attacker set found, which is sufficient for legality checks.
    *
    * Used for castling path safety and king-in-check detection.
    *
    * **The early exit is a contract, not an optimization detail**: the result is the attackers of the first matching
    * type only, so `.count` on it is NOT the number of pieces attacking `sq`. Use [[allAttackers]] when the complete
    * set matters (counting defenders, counting distinct attacker types); use this one when the question is merely
    * whether the square is attacked at all, and stopping early is the point.
    *
    * @param state
    *   current game state
    * @param sq
    *   the square to test
    * @param attackerColor
    *   the color whose pieces may be attacking `sq`
    * @return
    *   bitboard of attacking pieces, or [[dicechess.engine.domain.Bitboard.empty]] if the square is safe
    */
  def isSquareAttacked(state: GameState, sq: Square, attackerColor: Color): Bitboard = boundary {
    val allPieces       = state.whitePieces | state.blackPieces
    val attackers       = if attackerColor.isWhite then state.whitePieces else state.blackPieces
    val attackerPawns   = attackers & state.pawns
    val attackerKnights = attackers & state.knights
    val attackerBishops = attackers & (state.bishops | state.queens)
    val attackerRooks   = attackers & (state.rooks | state.queens)
    val attackerKings   = attackers & state.kings

    // Pawn attacks (from the perspective of the square being attacked)
    val pawnAttacks = PawnGeneration.anyAttacks(Bitboard.fromSquare(sq), attackerColor.opponent) & attackerPawns
    if !pawnAttacks.isEmpty then break(pawnAttacks)

    // Knight attacks
    val knightAttacks = LeaperAttacks.knightAttacksFor(sq) & attackerKnights
    if !knightAttacks.isEmpty then break(knightAttacks)

    // Sliding attacks
    val bishopAttacks = MagicBitboards.bishopAttacks(sq, allPieces) & attackerBishops
    if !bishopAttacks.isEmpty then break(bishopAttacks)

    val rookAttacks = MagicBitboards.rookAttacks(sq, allPieces) & attackerRooks
    if !rookAttacks.isEmpty then break(rookAttacks)

    // King attacks
    val kingAttacks = LeaperAttacks.kingAttacksFor(sq) & attackerKings
    if !kingAttacks.isEmpty then break(kingAttacks)

    Bitboard.empty
  }

  /** Returns **every** `attackerColor` piece that attacks `sq` — the complete counterpart to [[isSquareAttacked]],
    * which stops at the first matching piece type.
    *
    * The result is a set of *occupied squares*, so every attacking piece contributes exactly one bit: `.count` is the
    * honest number of attacking pieces, and intersecting the result with the per-type bitboards (`state.pawns`,
    * `state.knights`, …) yields how many *distinct types* attack the square. The five per-type sets are combined with a
    * bitboard union rather than by summing counts, which is what makes that guarantee hold for queens — they belong to
    * both the diagonal and the orthogonal attacker mask, so only a union keeps them from being counted twice.
    *
    * Attack geometry only: whose turn it is does not matter, and neither does whether a capture would be a good idea.
    * Pins are deliberately not modelled — in Dice Chess the game ends by capturing the king, so a "pinned" piece may
    * legally move and its attacks are real (the same reasoning `PieceSafety` documents for its en-prise definition).
    *
    * Costs strictly more than [[isSquareAttacked]] on an attacked square, since no branch can be skipped; prefer that
    * one whenever a boolean answer is enough.
    *
    * @param state
    *   current game state
    * @param sq
    *   the square to test
    * @param attackerColor
    *   the color whose pieces may be attacking `sq`
    * @return
    *   bitboard of every attacking piece, or [[dicechess.engine.domain.Bitboard.empty]] if the square is not attacked
    */
  def allAttackers(state: GameState, sq: Square, attackerColor: Color): Bitboard = {
    val allPieces = state.whitePieces | state.blackPieces
    val attackers = if attackerColor.isWhite then state.whitePieces else state.blackPieces
    val target    = Bitboard.fromSquare(sq)

    val pawns   = PawnGeneration.anyAttacks(target, attackerColor.opponent) & attackers & state.pawns
    val knights = LeaperAttacks.knightAttacksFor(sq) & attackers & state.knights
    val bishops = MagicBitboards.bishopAttacks(sq, allPieces) & attackers & (state.bishops | state.queens)
    val rooks   = MagicBitboards.rookAttacks(sq, allPieces) & attackers & (state.rooks | state.queens)
    val kings   = LeaperAttacks.kingAttacksFor(sq) & attackers & state.kings

    pawns | knights | bishops | rooks | kings
  }
}
