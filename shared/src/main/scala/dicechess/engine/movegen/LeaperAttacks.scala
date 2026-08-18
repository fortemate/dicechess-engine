package dicechess.engine.movegen

import dicechess.engine.domain.{Square, Bitboard}

/** Precomputed attack tables for leaping pieces (Knights and Kings).
  *
  * Since leapers are not blocked by other pieces, their attacks can be completely pre-calculated at JVM startup for all
  * 64 squares.
  */
object LeaperAttacks:

  // File exclusion masks to prevent wrap-around bugs
  private val NotAFile: Bitboard  = Bitboard(0xfefefefefefefefeL)
  private val NotABFile: Bitboard = Bitboard(0xfcfcfcfcfcfcfcfcL)
  private val NotHFile: Bitboard  = Bitboard(0x7f7f7f7f7f7f7f7fL)
  private val NotGHFile: Bitboard = Bitboard(0x3f3f3f3f3f3f3f3fL)

  /** Precomputed attack bitboards for a Knight on any given square. */
  val knightAttacks: Array[Bitboard] = Array.tabulate(64)(computeKnightAttacks)

  /** Precomputed attack bitboards for a King on any given square. */
  val kingAttacks: Array[Bitboard] = Array.tabulate(64)(computeKingAttacks)

  /** Returns all squares attacked by a Knight on the given square.
    *
    * @param sq
    *   any valid board square
    * @return
    *   bitboard of all squares the Knight can jump to (1–8 squares)
    */
  inline def knightAttacksFor(sq: Square): Bitboard = knightAttacks(Square.index(sq))

  /** Returns all squares attacked by a King on the given square.
    *
    * @param sq
    *   any valid board square
    * @return
    *   bitboard of all squares adjacent to `sq` (1–8 squares)
    */
  inline def kingAttacksFor(sq: Square): Bitboard = kingAttacks(Square.index(sq))

  /** Computes the knight attack bitboard for a given square index.
    *
    * Uses file-exclusion masks (`NotAFile`, `NotHFile`, `NotABFile`, `NotGHFile`) and bitwise shifts to enumerate all 8
    * knight-jump destinations, preventing illegal wrap-around to the opposite file.
    *
    * @param sqIndex
    *   board square index in `[0, 63]` (LERF)
    * @return
    *   bitboard of all squares reachable by a Knight from `sqIndex`
    */
  private def computeKnightAttacks(sqIndex: Int): Bitboard =
    val b   = Bitboard.fromSquare(Square.fromIndex(sqIndex))
    val nne = (b & NotHFile) << 17
    val nnw = (b & NotAFile) << 15
    val ene = (b & NotGHFile) << 10
    val wnw = (b & NotABFile) << 6
    val ese = (b & NotGHFile) >>> 6
    val wsw = (b & NotABFile) >>> 10
    val sse = (b & NotHFile) >>> 15
    val ssw = (b & NotAFile) >>> 17

    nne | nnw | ene | wnw | ese | wsw | sse | ssw

  /** Computes the king attack bitboard for a given square index.
    *
    * Generates all 8 adjacent squares (N, S, E, W, NE, NW, SE, SW) with file-exclusion masks to prevent wrap-around
    * from the H-file to the A-file and vice versa.
    *
    * @param sqIndex
    *   board square index in `[0, 63]` (LERF)
    * @return
    *   bitboard of all squares adjacent to `sqIndex` (1 step in any direction)
    */
  private def computeKingAttacks(sqIndex: Int): Bitboard =
    val b  = Bitboard.fromSquare(Square.fromIndex(sqIndex))
    val n  = b << 8
    val s  = b >>> 8
    val e  = (b & NotHFile) << 1
    val w  = (b & NotAFile) >>> 1
    val ne = (b & NotHFile) << 9
    val nw = (b & NotAFile) << 7
    val se = (b & NotHFile) >>> 7
    val sw = (b & NotAFile) >>> 9

    n | s | e | w | ne | nw | se | sw
