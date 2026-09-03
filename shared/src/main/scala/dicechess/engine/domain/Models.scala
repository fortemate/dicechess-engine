package dicechess.engine.domain

opaque type Color = Int

/** Represents the player colors: White (0) or Black (1).
  *
  * Uses bitwise XOR for fast toggling between opponents.
  */
object Color:
  given CanEqual[Color, Color] = CanEqual.derived

  val White: Color = 0
  val Black: Color = 1

  /** Safe builder to validate boundary invariants.
    */
  def apply(value: Int): Color =
    require(value == 0 || value == 1, s"Invalid color ID: $value")
    value

  extension (color: Color)
    /** Returns the opposing color. Implemented as XOR for branch-free toggling. */
    inline def opponent: Color = color ^ 1

    /** Returns `true` when this color is White. */
    inline def isWhite: Boolean = color == Color.White

    /** Returns `true` when this color is Black. */
    inline def isBlack: Boolean = color == Color.Black

    /** Exposes the underlying `Int` value (`0` = White, `1` = Black). */
    inline def value: Int = color

opaque type PieceType = Int

/** Represents chess piece types corresponding to dice values.
  *
  * Values are 1: Pawn, 2: Knight, 3: Bishop, 4: Rook, 5: Queen, 6: King.
  */
object PieceType:
  given CanEqual[PieceType, PieceType] = CanEqual.derived

  val Pawn: PieceType   = 1
  val Knight: PieceType = 2
  val Bishop: PieceType = 3
  val Rook: PieceType   = 4
  val Queen: PieceType  = 5
  val King: PieceType   = 6

  /** All six piece types in dice-value order (Pawn → King). Useful for full-move generation. */
  val all: List[PieceType] = List(Pawn, Knight, Bishop, Rook, Queen, King)

  /** Directly constructs a [[PieceType]] from a raw integer without bounds checking.
    *
    * Precondition: `value` must be a valid dice slot in `[1, 6]`.
    */
  private[engine] inline def apply(value: Int): PieceType = value

  /** Converts a dice roll to the corresponding [[dicechess.engine.domain.PieceType]].
    *
    * @param value
    *   dice roll in `[1, 6]`
    * @return
    *   `Some(pieceType)` for valid rolls, `None` otherwise
    */
  def fromDice(value: Int): Option[PieceType] =
    if value >= 1 && value <= 6 then Some(value) else None

  extension (pt: PieceType)
    /** The dice roll value that selects this piece type (`1` = Pawn … `6` = King). */
    inline def diceValue: Int = pt

    /** Returns the lowercase FEN character for this piece type (e.g. `"n"` for Knight). */
    def asNotation: String = pt match
      case PieceType.Pawn   => "p"
      case PieceType.Knight => "n"
      case PieceType.Bishop => "b"
      case PieceType.Rook   => "r"
      case PieceType.Queen  => "q"
      case PieceType.King   => "k"

opaque type Piece = Int

/** A packed chess piece combining [[dicechess.engine.domain.Color]] and [[dicechess.engine.domain.PieceType]].
  *
  * Memory Layout (4 bits total):
  *   - Bit 3: Color (0 for White, 1 for Black)
  *   - Bits 0-2: PieceType (1-6)
  */
object Piece:
  given CanEqual[Piece, Piece] = CanEqual.derived

  val Empty: Piece = 0

  def apply(color: Color, pieceType: PieceType): Piece =
    (color << 3) | pieceType

  extension (piece: Piece)
    inline def color: Color         = piece >>> 3
    inline def pieceType: PieceType = piece & 7
    inline def isEmpty: Boolean     = piece == Piece.Empty

opaque type Square = Int

/** Represents a chess board square index.
  *
  * The index ranges from 0 (a1) to 63 (h8), mapped row by row (a1, b1... h8).
  */
object Square:
  given CanEqual[Square, Square] = CanEqual.derived

  /** Builds a square from coordinate syntax.
    */
  def apply(file: Char, rank: Int): Square =
    require(file >= 'a' && file <= 'h', s"Invalid file: $file. Must be 'a'-'h'.")
    require(rank >= 1 && rank <= 8, s"Invalid rank: $rank. Must be 1-8.")
    ((rank - 1) * 8) + (file - 'a')

  /** Directly injects a pre-validated raw index.
    */
  def fromIndex(idx: Int): Square =
    require(idx >= 0 && idx < 64, s"Invalid square index: $idx")
    idx

  /** Parses an algebraic square notation string (e.g. `"e4"`) into a [[dicechess.engine.domain.Square]].
    *
    * @param notation
    *   two-character string: file letter `a`–`h` followed by rank digit `1`–`8`
    * @return
    *   `Some(square)` if the notation is valid, `None` otherwise
    */
  def fromNotation(notation: String): Option[Square] =
    if notation.length == 2 then
      val file     = notation.charAt(0)
      val rankChar = notation.charAt(1)
      if file >= 'a' && file <= 'h' && rankChar >= '1' && rankChar <= '8' then Some(Square(file, rankChar.asDigit))
      else None
    else None

  extension (sq: Square)
    /** File letter (`'a'`–`'h'`) derived from the square index. */
    inline def file: Char = ((sq % 8) + 'a').toChar

    /** Rank number (`1`–`8`) derived from the square index. */
    inline def rank: Int = (sq / 8) + 1

    /** Algebraic notation string, e.g. `"e4"`. */
    inline def toNotation: String = s"$file$rank"

    /** Raw LERF index in `[0, 63]` (`0` = a1, `63` = h8). */
    inline def index: Int = sq

opaque type MicroMove = Int

/** A high-performance 16-bit packed micro-move.
  *
  * Memory Layout:
  *   - Bits 12-15: Promotion PieceType (optional, 0 if none)
  *   - Bits 6-11: Target [[dicechess.engine.domain.Square]] (0-63)
  *   - Bits 0-5: Origin [[dicechess.engine.domain.Square]] (0-63)
  */
object MicroMove:
  given CanEqual[MicroMove, MicroMove] = CanEqual.derived

  def apply(from: Square, to: Square, promotion: Option[PieceType] = None): MicroMove =
    val promValue = promotion.getOrElse(0) // 0 indicates no promotion
    (promValue << 12) | (to << 6) | from

  extension (mv: MicroMove)
    /** Origin square (bits 0–5). */
    inline def from: Square = mv & 0x3f

    /** Destination square (bits 6–11). */
    inline def to: Square = (mv >>> 6) & 0x3f

    /** Promotion piece type (bits 12–15), or `None` if this is not a promotion move. */
    def promotion: Option[PieceType] =
      val prom = (mv >>> 12) & 0x0f
      if prom == 0 then None else Some(prom)

    /** Long algebraic notation string, e.g. `"e2e4"` or `"e7e8q"` for a queen promotion. */
    def toNotation: String =
      import PieceType.asNotation
      val promStr = promotion.map(_.asNotation).getOrElse("")
      s"${Square.toNotation(from)}${Square.toNotation(to)}$promStr"

opaque type Mailbox = IArray[Piece]

/** A fast, flat array representation of the 64 squares on the board.
  *
  * Gives O(1) piece lookup and near-instant cloning via System.arraycopy without GC allocations.
  */
object Mailbox:
  import Square.*
  import Piece.*

  /** Returns a completely empty mailbox. */
  def empty: Mailbox = IArray.fill(64)(Piece.Empty)

  /** Creates a mailbox directly from a mutated builder array.
    *
    * @note
    *   Mutating the `builder` after calling this method breaks the immutability guarantee.
    */
  inline def fromBuilder(builder: Array[Piece]): Mailbox = IArray.unsafeFromArray(builder)

  extension (mb: Mailbox)
    /** Fast O(1) retrieval of the piece at `sq`. Returns `Piece.Empty` if square is unoccupied. */
    inline def apply(sq: Square): Piece = mb.asInstanceOf[Array[Piece]](sq.index)

    /** Optional retrieval of the piece at `sq` (for legacy compatibility). */
    inline def get(sq: Square): Option[Piece] =
      val p = mb.asInstanceOf[Array[Piece]](sq.index)
      if p.isEmpty then None else Some(p)

    /** Exposes the underlying immutable array for cloning. */
    inline def toArray: Array[Piece] = mb.asInstanceOf[Array[Piece]].clone()

opaque type Bitboard = Long

/** A 64-bit integer representing a set of squares on the chess board.
  *
  * Uses Little-Endian Rank-File (LERF) mapping, where bit 0 is a1 and bit 63 is h8.
  */
object Bitboard:
  given CanEqual[Bitboard, Bitboard] = CanEqual.derived

  /** An empty bitboard (no squares occupied). */
  val empty: Bitboard = 0L

  /** A full bitboard (all squares occupied). */
  val full: Bitboard = -1L

  /** Creates a bitboard with a single square occupied. */
  def fromSquare(sq: Square): Bitboard = 1L << Square.index(sq)

  /** Creates a bitboard from a raw 64-bit integer. */
  inline def apply(value: Long): Bitboard = value

  extension (bb: Bitboard)
    /** Bitwise AND (Intersection) */
    inline infix def &(other: Bitboard): Bitboard = bb & other

    /** Bitwise OR (Union) */
    inline infix def |(other: Bitboard): Bitboard = bb | other

    /** Bitwise XOR (Symmetric Difference) */
    inline infix def ^(other: Bitboard): Bitboard = bb ^ other

    /** Bitwise NOT (Complement) */
    inline def unary_~ : Bitboard = ~bb

    /** Bitwise Left Shift */
    inline infix def <<(n: Int): Bitboard = bb << n

    /** Logical Right Shift (Zero Fill) */
    inline infix def >>>(n: Int): Bitboard = bb >>> n

    /** Adds a square to the bitboard. */
    inline def add(sq: Square): Bitboard = bb | (1L << Square.index(sq))

    /** Removes a square from the bitboard. */
    inline def remove(sq: Square): Bitboard = bb & ~(1L << Square.index(sq))

    /** Checks if a square is occupied on this bitboard. */
    inline def contains(sq: Square): Boolean = (bb & (1L << Square.index(sq))) != 0L

    /** Returns the number of occupied squares (using JVM POPCNT intrinsic). */
    inline def count: Int = java.lang.Long.bitCount(bb)

    /** Returns true if the bitboard has no occupied squares. */
    inline def isEmpty: Boolean = bb == 0L

    /** Exposes the underlying Long value. */
    inline def value: Long = bb

/** A chess turn consisting of a dice outcome and a sequence of micro-moves.
  *
  * @param diceRoll
  *   The result of the dice (1-6).
  * @param microMoves
  *   The list of 1 to 3 moves executed within this turn.
  */
case class Turn(diceRoll: Int, microMoves: List[MicroMove]) derives CanEqual:
  require(microMoves.nonEmpty, "Turn must contain at least one micro-move")
  require(microMoves.length <= 3, "Turn cannot contain more than 3 micro-moves")

/** The complete snapshot of a Dice Chess game state.
  *
  * Uses a hybrid architecture combining fast Bitboards for move generation and a Map mailbox for constant-time piece
  * lookups. Game meta-state (active color, castling rights, dice pool, half-move clock) is packed into the [[flags]]
  * field via [[GameFlags]] to minimise heap allocations on the search hot-path.
  *
  * @param whitePieces
  *   Bitboard with a set bit for every square occupied by a White piece.
  * @param blackPieces
  *   Bitboard with a set bit for every square occupied by a Black piece.
  * @param pawns
  *   Bitboard for all Pawns (both colors).
  * @param knights
  *   Bitboard for all Knights (both colors).
  * @param bishops
  *   Bitboard for all Bishops (both colors).
  * @param rooks
  *   Bitboard for all Rooks (both colors).
  * @param queens
  *   Bitboard for all Queens (both colors).
  * @param kings
  *   Bitboard for all Kings (both colors).
  * @param mailbox
  *   Fast O(1) flat array from [[dicechess.engine.domain.Square]] to [[Piece]].
  * @param flags
  *   Packed 29-bit integer encoding active color, castling rights, en-passant file mask, dice pool (up to 3 dice), and
  *   the half-move clock. See [[GameFlags]] for the exact bit layout.
  * @param enPassant
  *   Bitboard of active en-passant target squares (one bit per valid EP capture destination). Stored separately from
  *   [[flags]] because the 8-bit file mask in [[GameFlags]] is lossy for positions with multiple EP targets.
  * @param fullMoveNumber
  *   Full-move counter; incremented after each Black move (starts at 1 per FEN convention).
  */

case class GameState(
    whitePieces: Bitboard,
    blackPieces: Bitboard,
    pawns: Bitboard,
    knights: Bitboard,
    bishops: Bitboard,
    rooks: Bitboard,
    queens: Bitboard,
    kings: Bitboard,
    mailbox: Mailbox,
    flags: GameFlags,
    enPassant: Bitboard,
    fullMoveNumber: Int,
    zobristKey: Long = 0L
) derives CanEqual:
  inline def activeColor: Color = flags.activeColor

  /** Returns `true` when `move` captures the opponent's King from this state (win condition). */
  inline def isKingCapture(move: Move): Boolean =
    val p = mailbox(move.toSquare)
    p.pieceType == PieceType.King && p.color != activeColor

  /** Returns the precalculated Zobrist key, or computes it on demand if unpopulated (0L).
    *
    * ⚠️ Incremental-hash contract: position-relevant fields (boards, mailbox, flags, enPassant) must NEVER be mutated
    * via a raw `copy(...)` — that silently carries this cached key forward and every later consumer of the hash reads a
    * stale value. Mutate through the hash-aware paths (`makeMove`, `endTurn`, `withDicePool`, `withDiceSlotsOf`,
    * `withActiveColor`, `clearEnPassant`) or reset `zobristKey = 0L` in the copy. Known deliberate exception:
    * [[dicechess.engine.search.KingCaptureProbability]] mutates dice slots via raw copy inside its self-contained DFS,
    * which never consults the hash.
    *
    * The 0L sentinel means a position whose true key is genuinely zero recomputes on every call — a ~2^-64 harmless
    * performance quirk, accepted to keep the field allocation-free.
    */
  def zobristHash: Long =
    if zobristKey != 0L then zobristKey
    else Zobrist.computeKey(this)

  /** Removes all en-passant targets created by the specified color.
    *
    * White's double pawn pushes create targets on rank 3. Black's double pawn pushes create targets on rank 6.
    */
  def clearEnPassant(color: Color): GameState =
    val targetRank = if color.isWhite then 3 else 6
    var ep         = enPassant.value
    var newEp      = Bitboard.empty
    var key        = zobristHash
    while ep != 0 do {
      val sqIdx = java.lang.Long.numberOfTrailingZeros(ep)
      val sq    = Square.fromIndex(sqIdx)
      if sq.rank != targetRank then {
        newEp = newEp.add(sq)
      } else {
        key ^= Zobrist.EnPassantTable(sqIdx)
      }
      ep &= ep - 1
    }
    copy(enPassant = newEp, zobristKey = key)

  def castlingRights: String =
    val cr = flags.castlingRights
    if cr == 0 then "-"
    else
      val sb = new StringBuilder()
      if (cr & 1) != 0 then sb.append("K")
      if (cr & 2) != 0 then sb.append("Q")
      if (cr & 4) != 0 then sb.append("k")
      if (cr & 8) != 0 then sb.append("q")
      sb.toString()

  // enPassant is now a dedicated field because GameFlags (8 bits) is lossy

  inline def dicePool: List[Int] = flags.dicePool
  inline def halfMoveClock: Int  = flags.halfMoveClock

  /** Value equality over every field, mailbox included — hand-written rather than derived because `mailbox` is an
    * `IArray`, whose default equality is by reference; `java.util.Arrays.equals` compares it by content instead.
    *
    * Worth stating explicitly because the opposite was once assumed in `ExpectimaxSearch` (#514): two positions reached
    * by different move orders **do** compare equal, so `GameState` is usable as a `Map`/`Set` key directly. A separate
    * key type is only ever warranted on cost grounds — [[hashCode]] walks the 64-entry mailbox — never on correctness.
    */
  override def equals(obj: Any): Boolean = obj match
    case that: GameState =>
      this.whitePieces == that.whitePieces &&
      this.blackPieces == that.blackPieces &&
      this.pawns == that.pawns &&
      this.knights == that.knights &&
      this.bishops == that.bishops &&
      this.rooks == that.rooks &&
      this.queens == that.queens &&
      this.kings == that.kings &&
      java.util.Arrays.equals(this.mailbox.asInstanceOf[Array[Int]], that.mailbox.asInstanceOf[Array[Int]]) &&
      this.flags == that.flags &&
      this.enPassant == that.enPassant &&
      this.fullMoveNumber == that.fullMoveNumber
    case _ => false

  override def hashCode(): Int =
    var h = 17
    h = h * 31 + whitePieces.##
    h = h * 31 + blackPieces.##
    h = h * 31 + pawns.##
    h = h * 31 + knights.##
    h = h * 31 + bishops.##
    h = h * 31 + rooks.##
    h = h * 31 + queens.##
    h = h * 31 + kings.##
    h = h * 31 + java.util.Arrays.hashCode(mailbox.asInstanceOf[Array[Int]])
    h = h * 31 + flags.##
    h = h * 31 + enPassant.##
    h = h * 31 + fullMoveNumber.##
    h

  /** Explicitly ends the current player's turn.
    *
    * Toggles the active color to the opponent, increments the full-move number if the current player is Black, and
    * clears any stale en-passant targets left by the player whose turn is starting. Must be called manually by the
    * orchestrator after applying a sequence of micro-moves.
    *
    * @return
    *   a new [[dicechess.engine.domain.GameState]] with the active color flipped, EP targets cleaned, and full-move
    *   number updated
    */
  def endTurn(): GameState =
    val nextColor    = flags.activeColor.opponent
    val nextFullMove = if flags.activeColor.isBlack then fullMoveNumber + 1 else fullMoveNumber

    val cleanedState = clearEnPassant(nextColor)
    var key          = cleanedState.zobristHash ^ Zobrist.ActiveColorKey
    key ^= Zobrist.dicePoolKey(cleanedState.flags) ^ Zobrist.dicePoolKey(0, 0, 0)

    cleanedState.copy(
      flags = cleanedState.flags.withActiveColor(nextColor).withDicePool(Nil),
      fullMoveNumber = nextFullMove,
      zobristKey = key
    )

  /** Returns a copy of this state with the active color set to `c`.
    *
    * Used during move generation to explicitly override the active color.
    */
  def withActiveColor(c: Color): GameState =
    val oldKey = zobristHash
    val newKey = if c != activeColor then oldKey ^ Zobrist.ActiveColorKey else oldKey
    copy(flags = flags.withActiveColor(c), zobristKey = newKey)

  /** Returns a copy of this state with the dice pool replaced by `pool`.
    *
    * Used to track remaining dice during multi-micro-move turn enumeration.
    *
    * @param pool
    *   up to three die values; excess elements are silently ignored
    */
  def withDicePool(pool: List[Int]): GameState =
    val nextFlags = flags.withDicePool(pool)
    val key       = zobristHash ^ Zobrist.dicePoolKey(flags) ^ Zobrist.dicePoolKey(nextFlags)
    copy(flags = nextFlags, zobristKey = key)

  /** Overwrites only the dice-pool slots of this state's flags with those from `src`, leaving all other flag bits
    * (castling, EP, active color, HMC) untouched. Allocation-free alternative to [[withDicePool]] for hot paths.
    */
  inline def withDiceSlotsOf(src: GameFlags): GameState =
    val nextFlags = flags.withDiceSlotsOf(src)
    val key       = zobristHash ^ Zobrist.dicePoolKey(flags) ^ Zobrist.dicePoolKey(nextFlags)
    copy(flags = nextFlags, zobristKey = key)

  /** Consumes the dice required for `move` from this state's dice pool.
    *
    * Resolves the mover piece type from the mailbox and delegates to [[GameFlags.consumeDiceFor]]. Returns
    * [[GameFlags.Invalid]] if the required dice are not available in this state's pool.
    */
  inline def diceAfter(move: Move): GameFlags =
    val moverType = mailbox(move.fromSquare).pieceType
    flags.consumeDiceFor(move, moverType)
