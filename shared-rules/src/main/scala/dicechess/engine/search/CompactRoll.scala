// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*

/** A sorted sub-multiset of zero to three dice, packed into an `Int` without a wrapper allocation.
  *
  * Bits 0–5 contain the presence mask (bit `d - 1` for face `d`), bits 6–7 the number of dice, and bits 13–15, 16–18
  * and 19–21 the sorted faces. Unused slots and bits are zero. The face slots align with
  * [[dicechess.engine.domain.GameFlags]], allowing direct replacement of a state's dice without allocating a list.
  * Multiplicity is preserved.
  */
opaque type CompactRoll = Int

object CompactRoll:
  given CanEqual[CompactRoll, CompactRoll] = CanEqual.derived

  /** No retained dice; a consumer must still account for this class's probability mass (a forced pass). */
  val Empty: CompactRoll = 0

  /** Constructs a roll for diagnostics or callers that already have a sorted pool.
    *
    * Requires at most three faces in `1..6`, in non-decreasing order; rejects invalid input with
    * `IllegalArgumentException`. Chance-node loops should reuse [[ActiveDicePartitioner.partitionsFor]] instead.
    */
  def fromSortedList(dice: List[Int]): CompactRoll =
    require(dice.length <= 3, "A compact roll contains at most three dice")
    require(dice.forall(d => d >= 1 && d <= 6), "Die faces must be in 1..6")
    require(dice == dice.sorted, "Dice must be sorted")
    var packed = dice.length << 6
    var slot   = 13
    dice.foreach { d =>
      packed |= (1 << (d - 1)) | (d << slot)
      slot += 3
    }
    packed

  private[search] inline def fromPackedEntry(entry: Int): CompactRoll = entry & 0x3fffff

  extension (roll: CompactRoll)
    private[search] inline def raw: Int = roll

    /** Presence mask: Pawn, Knight, Bishop, Rook, Queen, King occupy bits 0 through 5. */
    inline def dieMask: Int = roll & 0x3f

    /** Number of retained dice, including repeated faces. */
    inline def arity: Int = (roll >>> 6) & 3

    /** Whether the roll retains no dice. */
    inline def isEmpty: Boolean = arity == 0

    /** Replaces only the dice slots, preserving the board, side, counters and rights and updating the Zobrist key. This
      * creates a new [[dicechess.engine.domain.GameState]], but does not allocate a dice list or end the turn.
      */
    inline def applyToState(state: GameState): GameState =
      state.withDiceSlotsOf(GameFlags(Color.White, 0, 0, (roll >>> 13) & 7, (roll >>> 16) & 7, (roll >>> 19) & 7, 0))

    /** Materializes the retained dice for inspection; avoid this allocation in search loops. */
    def toList: List[Int] =
      val first  = (roll >>> 13) & 7
      val second = (roll >>> 16) & 7
      val third  = (roll >>> 19) & 7
      arity match
        case 0 => Nil
        case 1 => List(first)
        case 2 => List(first, second)
        case _ => List(first, second, third)
