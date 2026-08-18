package dicechess.engine.movegen

import dicechess.engine.domain.{Color, Bitboard}

/** Highly optimized pawn move generation using Bitboard arithmetic.
  *
  * Unlike leaping pieces which are generated per-square, pawn pushes and attacks can be calculated in parallel for all
  * pawns of a given color simultaneously using bitwise shifts.
  */
object PawnGeneration:

  // File exclusion masks to prevent wrap-around bugs during diagonal captures
  private val NotAFile: Bitboard = Bitboard(0xfefefefefefefefeL)
  private val NotHFile: Bitboard = Bitboard(0x7f7f7f7f7f7f7f7fL)

  // Rank masks to determine if a double-push is legal
  private val Rank3: Bitboard = Bitboard(0x0000000000ff0000L)
  private val Rank6: Bitboard = Bitboard(0x0000ff0000000000L)

  // Promotion target ranks: white promotes on rank 8 (bits 56-63), black on rank 1 (bits 0-7)
  private[movegen] val Rank1: Bitboard = Bitboard(0x00000000000000ffL)
  private[movegen] val Rank8: Bitboard = Bitboard(0xff00000000000000L)

  /** Computes the single forward push for all pawns.
    *
    * @param pawns
    *   Bitboard of all pawns of the given color.
    * @param emptySquares
    *   Bitboard of all currently empty squares.
    * @return
    *   A Bitboard of the squares the pawns will land on after a single push.
    */
  inline def singlePushes(pawns: Bitboard, emptySquares: Bitboard, color: Color): Bitboard =
    val pushes = if color.isWhite then pawns << 8 else pawns >>> 8
    pushes & emptySquares

  /** Computes the double forward push for all pawns starting from their initial rank.
    *
    * @param singlePushes
    *   The result of the `singlePushes` method.
    * @param emptySquares
    *   Bitboard of all currently empty squares.
    * @return
    *   A Bitboard of the squares the pawns will land on after a double push.
    */
  inline def doublePushes(singlePushes: Bitboard, emptySquares: Bitboard, color: Color): Bitboard =
    val pushes = if color.isWhite then (singlePushes & Rank3) << 8 else (singlePushes & Rank6) >>> 8
    pushes & emptySquares

  /** Computes all diagonal captures towards the East (H-file).
    *
    * @param pawns
    *   Bitboard of pawns.
    * @param enemies
    *   Bitboard of enemy pieces (or En Passant target).
    * @param color
    *   the color of the attacking pawns (determines the direction of the attack)
    * @return
    *   Bitboard of squares where a pawn can capture towards the H-file.
    */
  inline def eastCaptures(pawns: Bitboard, enemies: Bitboard, color: Color): Bitboard =
    val attacks = if color.isWhite then (pawns & NotHFile) << 9 else (pawns & NotHFile) >>> 7
    attacks & enemies

  /** Computes all diagonal captures towards the West (A-file).
    *
    * @param pawns
    *   Bitboard of pawns.
    * @param enemies
    *   Bitboard of enemy pieces (or En Passant target).
    * @param color
    *   the color of the attacking pawns (determines the direction of the attack)
    * @return
    *   Bitboard of squares where a pawn can capture towards the A-file.
    */
  inline def westCaptures(pawns: Bitboard, enemies: Bitboard, color: Color): Bitboard =
    val attacks = if color.isWhite then (pawns & NotAFile) << 7 else (pawns & NotAFile) >>> 9
    attacks & enemies

  /** Computes all attacked squares by pawns, regardless of whether there is an enemy there.
    *
    * Used primarily to determine if the King is in check or squares are unsafe for king placement. Unlike
    * [[eastCaptures]] and [[westCaptures]], this does not intersect with any enemy bitboard.
    *
    * @param pawns
    *   Bitboard of pawns to compute attacks for.
    * @param color
    *   the color of the attacking pawns (determines shift direction)
    * @return
    *   Bitboard of all squares attacked by the given pawns.
    */
  inline def anyAttacks(pawns: Bitboard, color: Color): Bitboard =
    val east = if color.isWhite then (pawns & NotHFile) << 9 else (pawns & NotHFile) >>> 7
    val west = if color.isWhite then (pawns & NotAFile) << 7 else (pawns & NotAFile) >>> 9
    east | west

  /** Filters a target bitboard, returning only the squares on the promotion rank for the given color.
    *
    * White promotes on Rank 8; Black promotes on Rank 1.
    *
    * @param targets
    *   Bitboard of candidate target squares (e.g. result of `singlePushes` or `eastCaptures`).
    * @param color
    *   The color of the moving pawns.
    * @return
    *   Bitboard of targets that land on the promotion rank.
    */
  inline def promotionSquares(targets: Bitboard, color: Color): Bitboard =
    if color.isWhite then targets & Rank8 else targets & Rank1

  /** Filters a target bitboard, returning only the squares that are **not** on the promotion rank for the given color.
    *
    * @param targets
    *   Bitboard of candidate target squares.
    * @param color
    *   The color of the moving pawns.
    * @return
    *   Bitboard of targets that do **not** land on the promotion rank.
    */
  inline def nonPromotionSquares(targets: Bitboard, color: Color): Bitboard =
    if color.isWhite then targets & ~Rank8 else targets & ~Rank1
