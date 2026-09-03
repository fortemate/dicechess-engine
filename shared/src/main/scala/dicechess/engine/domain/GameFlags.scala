package dicechess.engine.domain

opaque type GameFlags = Int

/** An ultra-compact 32-bit representation of the meta-state of a Dice Chess game.
  *
  * Memory Layout (29 bits total):
  *   - Bit 0 (1 bit) : Active Color (0 = White, 1 = Black)
  *   - Bits 1-4 (4 bits): Castling Rights (Bit 1=K, Bit 2=Q, Bit 3=k, Bit 4=q)
  *   - Bits 5-12 (8 bits): En-Passant Files (Bit N = File N has an EP target)
  *   - Bits 13-15 (3 bits): Dice Slot 1 (0 = empty, 1-6 = piece/die value)
  *   - Bits 16-18 (3 bits): Dice Slot 2
  *   - Bits 19-21 (3 bits): Dice Slot 3
  *   - Bits 22-28 (7 bits): Half Move Clock (0-127, for the 50-move rule)
  */
object GameFlags:
  given CanEqual[GameFlags, GameFlags] = CanEqual.derived

  /** A [[GameFlags]] with all fields zeroed: White to move, no castling rights, no en-passant, empty dice pool, zero
    * half-move clock.
    */
  val empty: GameFlags = 0

  /** Sentinel value indicating an invalid [[GameFlags]] representation.
    *
    * Valid [[GameFlags]] instances occupy bits 0–28, so their packed 32-bit signed integer value is always
    * non-negative. Negative values (specifically `-1`) serve as an allocation-free error sentinel on hot paths.
    */
  val Invalid: GameFlags = -1

  /** How many dice the layout can hold: three 3-bit slots at bits 13-21, matching the three dice a turn rolls.
    *
    * Published because the packing is lossy in one direction only — [[fromList]] keeps the first `DiceSlots` values and
    * drops the rest — so anything parsing external input has to check the length itself to avoid reporting success for
    * a pool it silently shortened. [[FenParser.parse]] does.
    */
  val DiceSlots: Int = 3

  /** The largest half-move clock the 7-bit field at bits 22-28 can hold.
    *
    * Published for the same reason as [[DiceSlots]]: the packing *clamps* rather than rejecting, so 200 arrives as 127
    * — a plausible number that hides how far out of range the input actually was. Callers accepting a clock from
    * outside must bound it themselves if they need to distinguish a legitimate ceiling value from bad input.
    */
  val MaxHalfMoveClock: Int = 0x7f

  /** Clamps a half-move clock value into the packed field's representable range `0`-[[MaxHalfMoveClock]], saturating
    * rather than wrapping when `clock` is out of range — the 127-move mark during play must read as "at least 127",
    * never wrap back to 0, and a negative value must not bleed sign bits into neighboring fields when shifted.
    */
  private def clampHalfMoveClock(clock: Int): Int =
    if clock < 0 then 0 else if clock > MaxHalfMoveClock then MaxHalfMoveClock else clock

  /** Builds a [[GameFlags]] integer from individual components.
    *
    * Every component other than `halfMoveClock` is masked to its allocated bit-width before packing; values that exceed
    * their field width are silently truncated. `halfMoveClock` is the exception: out-of-range values are clamped to
    * [[MaxHalfMoveClock]] instead (see [[withHalfMoveClock]]).
    *
    * @param color
    *   the active player color (`0` = White, `1` = Black)
    * @param castlingRights
    *   4-bit mask: Bit 0 = K (White king-side), Bit 1 = Q, Bit 2 = k, Bit 3 = q
    * @param enPassantFiles
    *   8-bit mask: Bit N is set when file N has an active en-passant target square
    * @param dice1
    *   first dice slot value (0 = empty, 1-6 = die face)
    * @param dice2
    *   second dice slot value
    * @param dice3
    *   third dice slot value
    * @param halfMoveClock
    *   half-move counter for the 50-move rule; clamped to `0`–[[MaxHalfMoveClock]], not masked
    * @return
    *   the packed [[GameFlags]] integer
    */
  def apply(
      color: Color,
      castlingRights: Int,
      enPassantFiles: Int,
      dice1: Int,
      dice2: Int,
      dice3: Int,
      halfMoveClock: Int
  ): GameFlags =
    val c        = color.value & 0x1
    val castling = (castlingRights & 0xf) << 1
    val ep       = (enPassantFiles & 0xff) << 5
    val d1       = (dice1 & 0x7) << 13
    val d2       = (dice2 & 0x7) << 16
    val d3       = (dice3 & 0x7) << 19
    val hmc      = clampHalfMoveClock(halfMoveClock) << 22
    c | castling | ep | d1 | d2 | d3 | hmc

  /** Convenience constructor that builds [[GameFlags]] from a dice pool list instead of individual slots.
    *
    * Missing slots default to `0` (empty). At most the first three elements of `dicePool` are used.
    *
    * @param color
    *   the active player color
    * @param castlingRights
    *   4-bit castling-rights mask (same as [[apply]])
    * @param enPassantFiles
    *   8-bit en-passant file mask (same as [[apply]])
    * @param dicePool
    *   up to three die values; excess elements are ignored
    * @param halfMoveClock
    *   7-bit half-move counter
    * @return
    *   the packed [[GameFlags]] integer
    */
  def fromList(
      color: Color,
      castlingRights: Int,
      enPassantFiles: Int,
      dicePool: List[Int],
      halfMoveClock: Int
  ): GameFlags =
    val d1 = dicePool.lift(0).getOrElse(0)
    val d2 = dicePool.lift(1).getOrElse(0)
    val d3 = dicePool.lift(2).getOrElse(0)
    apply(color, castlingRights, enPassantFiles, d1, d2, d3, halfMoveClock)

  extension (flags: GameFlags)
    /** Returns the active player's color. */
    inline def activeColor: Color = Color(flags & 0x1)

    /** Returns the active player's color flipped. */
    inline def toggleActiveColor: GameFlags = flags ^ 0x1

    /** Sets the active player's color, returning a new [[GameFlags]]. */
    inline def withActiveColor(c: Color): GameFlags =
      (flags & ~0x1) | (c.value & 0x1)

    /** Returns the 4-bit castling rights integer. */
    inline def castlingRights: Int = (flags >>> 1) & 0xf

    /** Sets the castling rights, returning a new [[GameFlags]]. */
    inline def withCastlingRights(rights: Int): GameFlags =
      (flags & ~(0xf << 1)) | ((rights & 0xf) << 1)

    /** Returns the 8-bit en-passant files mask. */
    inline def enPassantFiles: Int = (flags >>> 5) & 0xff

    /** Sets the en-passant files mask, returning a new [[GameFlags]]. */
    inline def withEnPassantFiles(filesMask: Int): GameFlags =
      (flags & ~(0xff << 5)) | ((filesMask & 0xff) << 5)

    /** Returns the raw value of dice slot 1 (`0` = empty, `1`–6 = die face). */
    inline def diceSlot1: Int = (flags >>> 13) & 0x7

    /** Returns the raw value of dice slot 2 (`0` = empty, `1`–6 = die face). */
    inline def diceSlot2: Int = (flags >>> 16) & 0x7

    /** Returns the raw value of dice slot 3 (`0` = empty, `1`–6 = die face). */
    inline def diceSlot3: Int = (flags >>> 19) & 0x7

    /** Extracts the available dice into a standard Scala List (useful for UI/FEN boundaries). Avoid using in hot-paths.
      */
    def dicePool: List[Int] =
      val pool = List.newBuilder[Int]
      val d1   = diceSlot1; if d1 != 0 then pool += d1
      val d2   = diceSlot2; if d2 != 0 then pool += d2
      val d3   = diceSlot3; if d3 != 0 then pool += d3
      pool.result()

    /** Adds a single die to the pool. Returns Left if the pool is already full or the die is invalid. */
    def addDie(die: Int): Either[String, GameFlags] =
      if die < 1 || die > 6 then Left(s"Invalid die: $die")
      else if diceSlot1 == 0 then Right((flags | (die << 13)): GameFlags)
      else if diceSlot2 == 0 then Right((flags | (die << 16)): GameFlags)
      else if diceSlot3 == 0 then Right((flags | (die << 19)): GameFlags)
      else Left(s"Dice pool is full: $diceSlot1, $diceSlot2, $diceSlot3")

    /** Removes a single occurrence of the specified die from the pool. Does nothing if the die is not present.
      */
    def removeDie(die: Int): GameFlags =
      if diceSlot1 == die then flags & ~(0x7 << 13)
      else if diceSlot2 == die then flags & ~(0x7 << 16)
      else if diceSlot3 == die then flags & ~(0x7 << 19)
      else flags

    /** Replaces this value's three dice slots with those of `src`, leaving every other flag bit untouched.
      *
      * Allocation-free alternative to [[withDicePool]] for hot paths: it copies the slot values verbatim, so an emptied
      * slot stays a hole rather than compacting. The [[dicePool]] getter and move generation skip empty slots, so the
      * observable multiset is identical to a compacted pool.
      */
    inline def withDiceSlotsOf(src: GameFlags): GameFlags =
      val diceMask = (0x7 << 13) | (0x7 << 16) | (0x7 << 19)
      (flags & ~diceMask) | (src & diceMask)

    /** True when all three dice slots are empty. */
    inline def isDicePoolEmpty: Boolean =
      (flags & ((0x7 << 13) | (0x7 << 16) | (0x7 << 19))) == 0

    /** True when the pool holds at least one die of value `die`. */
    inline def containsDie(die: Int): Boolean =
      diceSlot1 == die || diceSlot2 == die || diceSlot3 == die

    /** Replaces the entire dice pool. */
    def withDicePool(dice: List[Int]): GameFlags =
      val base = flags & ~((0x7 << 13) | (0x7 << 16) | (0x7 << 19))
      val d1   = dice.lift(0).getOrElse(0)
      val d2   = dice.lift(1).getOrElse(0)
      val d3   = dice.lift(2).getOrElse(0)
      base | ((d1 & 0x7) << 13) | ((d2 & 0x7) << 16) | ((d3 & 0x7) << 19)

    /** Returns the half-move clock counter. */
    inline def halfMoveClock: Int = (flags >>> 22) & 0x7f

    /** Sets the half-move clock, clamping to [[MaxHalfMoveClock]] rather than wrapping when `clock` exceeds the packed
      * field's range — a value of 200 becomes 127, not 72.
      */
    inline def withHalfMoveClock(clock: Int): GameFlags =
      (flags & ~(0x7f << 22)) | (clampHalfMoveClock(clock) << 22)

    inline def isValid: Boolean = flags >= 0

    /** Consumes the dice required to execute `move` by a piece of `moverType`.
      *
      * Under Dice Chess rules:
      *   - Castling requires and consumes both a King (6) and a Rook (4) die.
      *   - Standard moves consume one die matching the mover's piece type.
      *
      * Returns [[GameFlags.Invalid]] if the required dice are not available in this flags' dice pool. Enforces
      * `require(moverType.diceValue != 0)` defense-in-depth against moves from empty squares.
      *
      * @param move
      *   the move being executed
      * @param moverType
      *   the piece type of the moving piece
      * @return
      *   the updated [[GameFlags]] with consumed dice removed, or [[GameFlags.Invalid]] if required dice are missing
      */
    inline def consumeDiceFor(move: Move, moverType: PieceType): GameFlags =
      val dieValue = moverType.diceValue
      require(dieValue != 0, s"Move generated from an empty square: $move")
      if move.isCastling then
        if flags.containsDie(PieceType.King.diceValue) && flags.containsDie(PieceType.Rook.diceValue) then
          flags.removeDie(PieceType.King.diceValue).removeDie(PieceType.Rook.diceValue)
        else GameFlags.Invalid
      else
        val survived = flags.removeDie(dieValue)
        if survived == flags then GameFlags.Invalid else survived

    /** Exposes the underlying integer. */
    inline def value: Int = flags
