package dicechess.engine.domain

/** A highly optimized 16-bit encoded chess move.
  *
  * Designed to completely avoid garbage collection (GC) during the hot path of the search tree.
  *
  * Memory layout:
  *   - Bits 0-5 (6 bits): Destination square index (to)
  *   - Bits 6-11 (6 bits): Source square index (from)
  *   - Bits 12-15 (4 bits): Move Flags (Quiet, Capture, Promotion, etc.)
  */
opaque type Move = Int

/** Companion object for [[Move]]: smart constructors, flag constants, and extension methods.
  *
  * Flag constants follow the *Chess Programming Wiki* standard 4-bit encoding, extended with higher bits for
  * promotions. The encoding allows O(1) flag tests using bitwise arithmetic.
  */
object Move:

  // Shift offsets for bit packing
  private val FromShift  = 6
  private val FlagsShift = 12

  // Bitmasks for extracting move components
  private val ToMask: Int    = 0x3f
  private val FromMask: Int  = 0x3f << FromShift
  private val FlagsMask: Int = 0x0f << FlagsShift

  /** Standard 4-bit move flags — quiet and special moves. */
  val QuietMove: Int        = 0 // Bit pattern: 0000 — simple non-capture move
  val DoublePawnPush: Int   = 1 // Bit pattern: 0001 — sets the en-passant square
  val KingCastle: Int       = 2 // Bit pattern: 0010 — O-O (king-side)
  val QueenCastle: Int      = 3 // Bit pattern: 0011 — O-O-O (queen-side)
  val Capture: Int          = 4 // Bit pattern: 0100 — standard capture (Bit 2 set)
  val EnPassantCapture: Int = 5 // Bit pattern: 0101 — captures the pawn behind the destination

  /** Promotion flags — Bit 3 is set to distinguish promotions from normal moves. */
  val KnightPromotion: Int = 8  // Bit pattern: 1000
  val BishopPromotion: Int = 9  // Bit pattern: 1001
  val RookPromotion: Int   = 10 // Bit pattern: 1010
  val QueenPromotion: Int  = 11 // Bit pattern: 1011

  /** Promotion-capture flags — both Bit 3 and Bit 2 are set. */
  val KnightPromoCapture: Int = 12 // Bit pattern: 1100
  val BishopPromoCapture: Int = 13 // Bit pattern: 1101
  val RookPromoCapture: Int   = 14 // Bit pattern: 1110
  val QueenPromoCapture: Int  = 15 // Bit pattern: 1111

  /** Constructs a new encoded Move from origin, destination, and flags. */
  def apply(from: Square, to: Square, flags: Int): Move =
    (flags << FlagsShift) | (Square.index(from) << FromShift) | Square.index(to)

  /** Constructs a new quiet Move. */
  def apply(from: Square, to: Square): Move =
    apply(from, to, QuietMove)

  /** A sentinel move encoding a1→a1 with no flags. Used as a zero-value placeholder; never a legal move. */
  val empty: Move = 0

  extension (move: Move)
    /** Returns the starting square of the move. */
    def fromSquare: Square = Square.fromIndex((move & FromMask) >> FromShift)

    /** Returns the destination square of the move. */
    def toSquare: Square = Square.fromIndex(move & ToMask)

    /** Returns the 4-bit flag of the move. */
    def flags: Int = (move & FlagsMask) >> FlagsShift

    /** Returns true if the move is any type of capture. */
    def isCapture: Boolean = (flags & 4) != 0

    /** Returns true if the move is any type of promotion. */
    def isPromotion: Boolean = (flags & 8) != 0

    /** Decodes promotion flags to the promoted [[dicechess.engine.domain.PieceType]] for API/serialization consumers.
      *
      * Keep this mapping aligned with Position-level promotion decoding.
      */
    def promotionPieceType: Option[PieceType] =
      if !isPromotion then None
      else
        flags match
          case Move.KnightPromotion | Move.KnightPromoCapture => Some(PieceType.Knight)
          case Move.BishopPromotion | Move.BishopPromoCapture => Some(PieceType.Bishop)
          case Move.RookPromotion | Move.RookPromoCapture     => Some(PieceType.Rook)
          case _                                              => Some(PieceType.Queen)

    /** Returns true if the move is an en passant capture. */
    def isEnPassant: Boolean = flags == EnPassantCapture

    /** Returns true if the move is a castling move. */
    def isCastling: Boolean = flags == KingCastle || flags == QueenCastle

    /** Returns the raw integer representation of the move. */
    def toInt: Int = move

    /** Long algebraic (UCI) notation, e.g. `"e2e4"` or `"e7e8q"` for a queen promotion. */
    def toUci: String =
      import PieceType.asNotation
      val promStr = promotionPieceType.map(_.asNotation).getOrElse("")
      s"${move.fromSquare.toNotation}${move.toSquare.toNotation}$promStr"
