package dicechess.engine.search

import dicechess.engine.domain.*

/** Deterministic Zobrist hashing for Dice Chess game states.
  *
  * Includes piece placement, active color, castling rights, en passant target squares, and canonical remaining dice
  * pool combinations.
  */
object Zobrist:

  private class SplitMix64(private var state: Long):
    def nextLong(): Long =
      state += 0x9e3779b97f4a7c15L
      var z = state
      z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L
      z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL
      z ^ (z >>> 31)

  private val prng = new SplitMix64(0x32a4b892f1c6d7e0L)

  /** Piece placement table: 12 piece types (6 White, 6 Black) x 64 squares */
  val PieceTable: Array[Array[Long]] = Array.fill(12, 64)(prng.nextLong())

  /** XOR key applied when active color is Black */
  val ActiveColorKey: Long = prng.nextLong()

  /** Castling rights table: 16 possible 4-bit combinations */
  val CastlingTable: Array[Long] = Array.fill(16)(prng.nextLong())

  /** En passant target squares table: 64 squares */
  val EnPassantTable: Array[Long] = Array.fill(64)(prng.nextLong())

  /** Dice pool multiset table: 343 combinations for sorted (slot1, slot2, slot3) */
  val DicePoolTable: Array[Long] = Array.fill(343)(prng.nextLong())

  /** Maps (color, pieceType) to an index in [0, 11] */
  inline def pieceIndex(color: Color, pieceType: PieceType): Int =
    if color.isWhite then pieceType.diceValue - 1
    else pieceType.diceValue - 1 + 6

  /** Returns the Zobrist key component for a piece on a square. */
  inline def pieceKey(color: Color, pieceType: PieceType, sq: Square): Long =
    PieceTable(pieceIndex(color, pieceType))(sq.index)

  /** Returns the canonical Zobrist key component for a dice pool.
    *
    * Multisets are canonicalized (sorted), ensuring `List(1, 3)` and `List(3, 1)` produce the exact same key.
    */
  def dicePoolKey(d1: Int, d2: Int, d3: Int): Long =
    var a = d1
    var b = d2
    var c = d3
    if a > b then { val t = a; a = b; b = t }
    if b > c then { val t = b; b = c; c = t }
    if a > b then { val t = a; a = b; b = t }
    val idx = a * 49 + b * 7 + c
    DicePoolTable(idx)

  inline def dicePoolKey(flags: GameFlags): Long =
    dicePoolKey(flags.diceSlot1, flags.diceSlot2, flags.diceSlot3)

  /** Computes the complete Zobrist key for `state` from scratch. */
  def computeKey(state: GameState): Long =
    var key = 0L

    // 1. Piece placement
    var i = 0
    while i < 64 do
      val piece = state.mailbox(Square.fromIndex(i))
      if !piece.isEmpty then key ^= PieceTable(pieceIndex(piece.color, piece.pieceType))(i)
      i += 1

    // 2. Active color
    if state.activeColor.isBlack then key ^= ActiveColorKey

    // 3. Castling rights
    key ^= CastlingTable(state.flags.castlingRights)

    // 4. En Passant target squares
    var ep = state.enPassant.value
    while ep != 0L do
      val sq = java.lang.Long.numberOfTrailingZeros(ep)
      key ^= EnPassantTable(sq)
      ep &= ep - 1L

    // 5. Dice pool
    key ^= dicePoolKey(state.flags)

    key
