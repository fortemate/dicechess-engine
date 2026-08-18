package dicechess.engine.movegen

import dicechess.engine.domain.{Bitboard, Square}

/** Magic Bitboards implementation for sliding pieces (Bishops, Rooks, and Queens).
  *
  * **Magic Bitboards** provide O(1) attack-set lookup for blocking sliding pieces by encoding the current board
  * occupancy into an index via a precomputed *magic number* multiplier:
  *
  * ```
  * index = ((occupancy & relevantMask) * magic) >>> (64 - relevantBits)
  * attack = table[offset + index]
  * ```
  *
  * At JVM / Scala.js startup, [[BishopTable]] (5248 entries) and [[RookTable]] (102400 entries) are populated for all
  * 64 squares using the slow [[bishopAttacksClassic]] / [[rookAttacksClassic]] functions. All subsequent attack lookups
  * use the fast O(1) hash path.
  *
  * The magic constants are a well-known standard set compatible with most chess engine implementations.
  */
object MagicBitboards:

  // --- Magic Constants (Standard sets used in many engines) ---

  private val BishopMagics: Array[Long] = Array(
    0x6102081001020a20L, 0x0010520604026008L, 0x8828220c18a1d030L, 0x0104041080800004L, 0x4101104008000000L,
    0x4524300808002001L, 0x00039a1802410080L, 0xa20200808288a010L, 0x0001400414140044L, 0x000004080a005210L,
    0x0a04111404004000L, 0x2000441042020000L, 0x0000020210108402L, 0x0000011008040004L, 0x2140218404424008L,
    0x0000010c02c40c00L, 0x022021d005100080L, 0x0010022002008108L, 0x800c208600240101L, 0x0084002202120004L,
    0x0000901404200020L, 0x6642000298040204L, 0x024080a400949000L, 0x0081008284052100L, 0xd0100c2130208608L,
    0x00100200651c0408L, 0x0001500008002040L, 0x140818002c220020L, 0x1c1100108100c001L, 0x0110008001080500L,
    0x0000860005211004L, 0x0024448104c40404L, 0x8301202200900400L, 0x0148045010021205L, 0x0288802480900400L,
    0x0008020080380082L, 0x0002920200140108L, 0x1600900104008080L, 0x0088880088010084L, 0x00a4008182320040L,
    0x0009012010282200L, 0xa211044104182001L, 0x0020201050002800L, 0x0842002031100800L, 0x0000110122000c00L,
    0x1022180119002808L, 0x4010824089050401L, 0x0822281041883100L, 0x0006480854502000L, 0x0000424208a00008L,
    0x0084194044100004L, 0x040000002a080201L, 0x8805012004240014L, 0x01080d3002020200L, 0x8420031408008100L,
    0x800c214801030000L, 0x0202008c00880404L, 0x02010104088e0860L, 0x1100628020a41000L, 0x0022000200420604L,
    0x0020002010820229L, 0x8a00c44821081081L, 0x040040a80a218201L, 0x1804082604042200L
  )

  private val RookMagics: Array[Long] = Array(
    0x1080028010400020L, 0x40c0021000200044L, 0x1c80100080082004L, 0x32000a0010a00c40L, 0x00800800810c0002L,
    0x0300020500080400L, 0x3280488001000a00L, 0x0200050032804204L, 0x0202800820400281L, 0x0001004000208902L,
    0x4011001100442000L, 0xa201002110010208L, 0x0c01000438010011L, 0x40c6002200441008L, 0x8012000104020008L,
    0x0801001200805100L, 0x20010100208000c3L, 0x0010004004402000L, 0x2102020041a18010L, 0x8041010020100088L,
    0x0008808008000400L, 0x0024008080040200L, 0x2100140008520110L, 0x8108020005a84b04L, 0xc8088a6080044000L,
    0x2002400040201000L, 0x8020004300102101L, 0xa010100100090020L, 0x1108000980240080L, 0x0502000200100904L,
    0x004a900c00030802L, 0x4000801080006100L, 0x080040012a800080L, 0x060081004a002202L, 0x0002002012004080L,
    0x4520800800801000L, 0x1038105501000800L, 0xa04a008002800400L, 0x0410880204003009L, 0x001400a3ca000104L,
    0x4000304000808008L, 0x0272820100260040L, 0x0108620040820032L, 0x0e02100008008080L, 0x440100a800110006L,
    0x0000040002008080L, 0x8882008814120011L, 0x005f040280420001L, 0x1400800468401280L, 0x0060004000882080L,
    0x0400144220010300L, 0xc000100220390100L, 0x8000053100080100L, 0x000a00802400c680L, 0x8800080201d00400L,
    0x000900020c418100L, 0x2124201080010841L, 0x2020850840006011L, 0x0022441811002001L, 0x008100c8100004a1L,
    0x1101000250880005L, 0x0281000804000201L, 0x0681000401a20001L, 0x0800040040810022L
  )

  /** Generates the candidate blocker mask for a Bishop on a given square.
    *
    * In Magic Bitboards, the edges of the board are excluded from the mask because blockers on the border cannot
    * prevent a sliding piece from reaching the border itself.
    *
    * @param sq
    *   the [[dicechess.engine.domain.Square]] position of the Bishop
    * @return
    *   a [[dicechess.engine.domain.Bitboard]] containing all candidate blocker squares (edges excluded)
    */
  def bishopMask(sq: Square): Bitboard =
    var mask = 0L
    val r    = sq.index / 8
    val f    = sq.index % 8

    // North-East
    for i <- 1.until(8) if r + i < 7 && f + i < 7 do mask |= 1L << ((r + i) * 8 + (f + i))
    // South-East
    for i <- 1.until(8) if r - i > 0 && f + i < 7 do mask |= 1L << ((r - i) * 8 + (f + i))
    // South-West
    for i <- 1.until(8) if r - i > 0 && f - i > 0 do mask |= 1L << ((r - i) * 8 + (f - i))
    // North-West
    for i <- 1.until(8) if r + i < 7 && f - i > 0 do mask |= 1L << ((r + i) * 8 + (f - i))

    Bitboard(mask)

  /** Generates the candidate blocker mask for a Rook on a given square.
    *
    * Excludes the outermost rank or file in each direction because a Rook's path cannot be blocked *beyond* the first
    * blocker, and a blocker on the final edge square cannot change what squares the Rook can reach.
    *
    * @param sq
    *   the [[dicechess.engine.domain.Square]] position of the Rook
    * @return
    *   a [[dicechess.engine.domain.Bitboard]] containing all candidate blocker squares (edges excluded)
    */
  def rookMask(sq: Square): Bitboard =
    var mask = 0L
    val r    = sq.index / 8
    val f    = sq.index % 8

    for i <- (r + 1).until(7) do mask |= 1L << (i * 8 + f)
    for i <- (r - 1).to(1).by(-1) do mask |= 1L << (i * 8 + f)
    for i <- (f + 1).until(7) do mask |= 1L << (r * 8 + i)
    for i <- (f - 1).to(1).by(-1) do mask |= 1L << (r * 8 + i)

    Bitboard(mask)

  /** Computes Bishop attacks step-by-step along its four diagonals.
    *
    * This slow, non-optimized method is used exclusively during engine startup to populate the [[BishopTable]]. It
    * slides in each direction, adding squares to the attack bitboard until it hits the board edge or another piece.
    *
    * @param sq
    *   the source [[dicechess.engine.domain.Square]] of the Bishop
    * @param occupancy
    *   the full board piece occupancy [[dicechess.engine.domain.Bitboard]]
    * @return
    *   a [[dicechess.engine.domain.Bitboard]] containing all squares the Bishop can attack or reach
    */
  def bishopAttacksClassic(sq: Square, occupancy: Bitboard): Bitboard =
    var attacks = 0L
    val r       = sq.index / 8
    val f       = sq.index % 8

    val dirs = Array((1, 1), (1, -1), (-1, 1), (-1, -1))
    for (dr, df) <- dirs do
      var nr      = r + dr
      var nf      = f + df
      var blocked = false
      while nr >= 0 && nr < 8 && nf >= 0 && nf < 8 && !blocked do
        val bit = 1L << (nr * 8 + nf)
        attacks |= bit
        if (occupancy & Bitboard(bit)) != Bitboard.empty then blocked = true
        nr += dr
        nf += df

    Bitboard(attacks)

  /** Computes Rook attacks step-by-step along its ranks and files.
    *
    * This slow, non-optimized method is used exclusively during engine startup to populate the [[RookTable]]. It slides
    * horizontally and vertically, adding squares until it hits the board edge or another piece.
    *
    * @param sq
    *   the source [[dicechess.engine.domain.Square]] of the Rook
    * @param occupancy
    *   the full board piece occupancy [[dicechess.engine.domain.Bitboard]]
    * @return
    *   a [[dicechess.engine.domain.Bitboard]] containing all squares the Rook can attack or reach
    */
  def rookAttacksClassic(sq: Square, occupancy: Bitboard): Bitboard =
    var attacks = 0L
    val r       = sq.index / 8
    val f       = sq.index % 8

    val dirs = Array((1, 0), (-1, 0), (0, 1), (0, -1))
    for (dr, df) <- dirs do
      var nr      = r + dr
      var nf      = f + df
      var blocked = false
      while nr >= 0 && nr < 8 && nf >= 0 && nf < 8 && !blocked do
        val bit = 1L << (nr * 8 + nf)
        attacks |= bit
        if (occupancy & Bitboard(bit)) != Bitboard.empty then blocked = true
        nr += dr
        nf += df

    Bitboard(attacks)

  /** Number of relevant occupancy bits per square for bishop attack masks.
    *
    * Ranges from 5 (corner squares, few diagonals) to 9 (central squares, long diagonals). Used to size the hash table
    * slice for each square.
    */
  val BishopRelevantBits: Array[Int] = Array(
    6, 5, 5, 5, 5, 5, 5, 6, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 7, 7, 7, 7, 5, 5, 5, 5, 7, 9, 9, 7, 5, 5, 5, 5, 7, 9, 9, 7, 5,
    5, 5, 5, 7, 7, 7, 7, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 6, 5, 5, 5, 5, 5, 5, 6
  )

  /** Number of relevant occupancy bits per square for rook attack masks.
    *
    * Ranges from 10 (non-corner edge squares) to 12 (corner squares, long files/ranks). Used to size the hash table
    * slice for each square.
    */
  val RookRelevantBits: Array[Int] = Array(
    12, 11, 11, 11, 11, 11, 11, 12, 11, 10, 10, 10, 10, 10, 10, 11, 11, 10, 10, 10, 10, 10, 10, 11, 11, 10, 10, 10, 10,
    10, 10, 11, 11, 10, 10, 10, 10, 10, 10, 11, 11, 10, 10, 10, 10, 10, 10, 11, 11, 10, 10, 10, 10, 10, 10, 11, 12, 11,
    11, 11, 11, 11, 11, 12
  )

  private val BishopTable = new Array[Bitboard](5248)
  private val RookTable   = new Array[Bitboard](102400)

  private val BishopOffsets = new Array[Int](64)
  private val RookOffsets   = new Array[Int](64)

  private val BishopMasks = Array.tabulate(64)(i => bishopMask(Square.fromIndex(i)))
  private val RookMasks   = Array.tabulate(64)(i => rookMask(Square.fromIndex(i)))

  // Initialization
  locally {
    var bishopOffset = 0
    var rookOffset   = 0
    for i <- 0 until 64 do
      val sq = Square.fromIndex(i)
      BishopOffsets(i) = bishopOffset
      RookOffsets(i) = rookOffset

      val bMask = BishopMasks(i)
      val bBits = BishopRelevantBits(i)
      for j <- 0 until (1 << bBits) do
        val occ   = setOccupancy(j, bBits, bMask)
        val index = ((occ.value * BishopMagics(i)) >>> (64 - bBits)).toInt
        BishopTable(bishopOffset + index) = bishopAttacksClassic(sq, occ)
      bishopOffset += (1 << bBits)

      val rMask = RookMasks(i)
      val rBits = RookRelevantBits(i)
      for j <- 0 until (1 << rBits) do
        val occ   = setOccupancy(j, rBits, rMask)
        val index = ((occ.value * RookMagics(i)) >>> (64 - rBits)).toInt
        RookTable(rookOffset + index) = rookAttacksClassic(sq, occ)
      rookOffset += (1 << rBits)
  }

  /** Maps an integer `index` (a subset enumerator) to an occupancy bitboard for the given `mask`.
    *
    * Iterates over bits set in `mask` and uses corresponding bits of `index` to decide whether each blocker is
    * "present" in the generated occupancy. This is used during table initialization to exhaustively enumerate all
    * possible blocker configurations for a given square.
    *
    * @param index
    *   a value in `[0, 2^bitsInMask)` selecting a subset of the mask bits
    * @param bitsInMask
    *   the number of set bits in `mask`
    * @param mask
    *   the occupancy mask (relevant squares only, edges excluded)
    * @return
    *   a bitboard representing one possible blocker configuration
    */
  private def setOccupancy(index: Int, bitsInMask: Int, mask: Bitboard): Bitboard =
    var occupancy = 0L
    var m         = mask.value
    for i <- 0 until bitsInMask do
      val square = java.lang.Long.numberOfTrailingZeros(m)
      m &= m - 1
      if (index & (1 << i)) != 0 then occupancy |= (1L << square)
    Bitboard(occupancy)

  /** Performs an O(1) Magic lookup for all squares attacked or reachable by a Bishop.
    *
    * Masks the board `occupancy`, hashes it to an index using [[BishopMagics]], and returns the precomputed attack
    * bitboard from [[BishopTable]].
    *
    * @param sq
    *   the source [[dicechess.engine.domain.Square]] of the Bishop
    * @param occupancy
    *   the full board piece occupancy [[dicechess.engine.domain.Bitboard]]
    * @return
    *   a [[dicechess.engine.domain.Bitboard]] of all attacked or reachable squares
    */
  def bishopAttacks(sq: Square, occupancy: Bitboard): Bitboard =
    val i     = sq.index
    val occ   = occupancy & BishopMasks(i)
    val index = ((occ.value * BishopMagics(i)) >>> (64 - BishopRelevantBits(i))).toInt
    BishopTable(BishopOffsets(i) + index)

  /** Performs an O(1) Magic lookup for all squares attacked or reachable by a Rook.
    *
    * Masks the board `occupancy`, hashes it to an index using [[RookMagics]], and returns the precomputed attack
    * bitboard from [[RookTable]].
    *
    * @param sq
    *   the source [[dicechess.engine.domain.Square]] of the Rook
    * @param occupancy
    *   the full board piece occupancy [[dicechess.engine.domain.Bitboard]]
    * @return
    *   a [[dicechess.engine.domain.Bitboard]] of all attacked or reachable squares
    */
  def rookAttacks(sq: Square, occupancy: Bitboard): Bitboard =
    val i     = sq.index
    val occ   = occupancy & RookMasks(i)
    val index = ((occ.value * RookMagics(i)) >>> (64 - RookRelevantBits(i))).toInt
    RookTable(RookOffsets(i) + index)

  /** Returns all squares attacked or reachable by a Queen by combining Rook and Bishop attacks.
    *
    * @param sq
    *   the source [[dicechess.engine.domain.Square]] of the Queen
    * @param occupancy
    *   the full board piece occupancy [[dicechess.engine.domain.Bitboard]]
    * @return
    *   a [[dicechess.engine.domain.Bitboard]] containing the union of Bishop and Rook attack paths
    */
  def queenAttacks(sq: Square, occupancy: Bitboard): Bitboard =
    bishopAttacks(sq, occupancy) | rookAttacks(sq, occupancy)
