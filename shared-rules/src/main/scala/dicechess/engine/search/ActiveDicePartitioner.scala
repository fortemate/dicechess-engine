// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*

/** Active Dice Equivalence Partitioning (ADEP) for a fresh, unknown 3d6 roll.
  *
  * Removes dice for types the mover neither owns nor can create and use through promotion during this turn, then groups
  * identical retained multisets. Each class preserves the legal full-turn move paths of its members under
  * [[TurnGenerator]], including king captures and maximum dice consumption. After `endTurn()`, member rolls have
  * identical successor states; before that boundary, unused dice (and therefore state hashes) can differ.
  *
  * Promotion handling is conservative: a pawn on the penultimate rank enables N/B/R/Q dice if the roll has a Pawn die;
  * a pawn two ranks away requires two Pawn dice. Blockers are deliberately ignored. Thus the partition is sound but
  * need not be minimal: present but immobile pieces, for example, still retain their dice. This differs from
  * [[PlayableDice]], which inspects legal paths for an already known roll.
  *
  * The 64 presence masks times three promotion modes are precomputed once per runtime. Lookup performs no turn
  * enumeration. Returned arrays are immutable and reusable; each class has positive integer weight and the weights sum
  * to [[DiceRolls.totalOrderedRolls]]. No evaluation, pruning, time budget or move-ordering policy is applied.
  *
  * Consumers must handle terminal positions before expanding a chance node, include empty-roll classes as passes, and
  * evaluate full-turn outcomes rather than the discarded dice. Integer weighted sums preserve exact arithmetic;
  * regrouping floating-point summation can change rounding. This is not a sampler or a filter for an observed roll.
  *
  * ```scala
  * import dicechess.engine.domain.FenParser
  * import dicechess.engine.search.{ActiveDicePartitioner, DiceRolls}
  * val state   = FenParser.parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1").toOption.get
  * val classes = ActiveDicePartitioner.partitionsFor(state)
  * assert(classes.length == 4) // zero, one, two or three King dice
  * assert(classes.iterator.map(_.weight).sum == DiceRolls.totalOrderedRolls)
  * val rolled = classes(0).roll.applyToState(state)
  * ```
  */
object ActiveDicePartitioner:

  /** One class packed into an `Int`: bits 0–21 hold [[CompactRoll]], bits 22–29 hold weight `1..216`, and bits 30–31
    * are zero. Only the partitioner constructs entries, keeping their probability mass valid.
    */
  opaque type WeightedRoll = Int

  object WeightedRoll:
    extension (entry: WeightedRoll)
      /** Number of ordered 3d6 outcomes in this class, not a normalized probability. */
      inline def weight: Int = entry >>> 22

      /** The representative retained pool, which can contain fewer than three dice. */
      inline def roll: CompactRoll = CompactRoll.fromPackedEntry(entry)

  private val PromotionPieces = 0x1e // Knight, Bishop, Rook, Queen

  // Package-visible for exhaustive table tests, not part of the consumer API.
  private[search] val table: IArray[IArray[WeightedRoll]] = IArray.tabulate(192) { index =>
    val mask    = index & 63
    val mode    = index >>> 6
    val weights = scala.collection.mutable.Map.empty[CompactRoll, Int]
    DiceRolls.weighted.foreach { (dice, weight) =>
      val pawnDice      = dice.count(_ == 1)
      val effectiveMask =
        if (mode == 2 && pawnDice >= 1) || (mode == 1 && pawnDice >= 2) then mask | PromotionPieces
        else mask
      val roll = CompactRoll.fromSortedList(dice.filter(d => (effectiveMask & (1 << (d - 1))) != 0))
      weights.update(roll, weights.getOrElse(roll, 0) + weight)
    }
    // Stable order across JVM/JS/Wasm, independent of hash-map iteration order.
    IArray.from(
      weights.toSeq
        .sortBy { (roll, weight) =>
          val dice = roll.toList
          (-weight, -roll.arity, dice.headOption.getOrElse(0), dice.lift(1).getOrElse(0), dice.lift(2).getOrElse(0))
        }
        .map((roll, weight) => (weight << 22) | roll.raw)
    )
  }

  private[search] def partitionIndex(state: GameState): Int =
    val own   = if state.activeColor.isWhite then state.whitePieces.value else state.blackPieces.value
    val pawns = own & state.pawns.value
    inline def present(bits: Long): Int = ((-bits | bits) >>> 63).toInt
    val mask                            = present(pawns) | (present(own & state.knights.value) << 1) |
      (present(own & state.bishops.value) << 2) | (present(own & state.rooks.value) << 3) |
      (present(own & state.queens.value) << 4) | (present(own & state.kings.value) << 5)
    if pawns == 0L || (mask & PromotionPieces) == PromotionPieces then mask
    else
      val oneStep  = if state.activeColor.isWhite then 0x00ff000000000000L else 0x000000000000ff00L
      val twoSteps = if state.activeColor.isWhite then 0x0000ff0000000000L else 0x0000000000ff0000L
      if (pawns & oneStep) != 0L then 128 + mask
      else if (pawns & twoSteps) != 0L then 64 + mask
      else mask

  /** Returns the cached partition for `state.activeColor`, ordered by descending weight, descending retained dice
    * count, then lexicographically by faces. The existing dice pool is ignored: this describes the next fresh roll for
    * the active side, not a reroll of its remaining dice. After applying a full turn, call `endTurn()` first.
    */
  def partitionsFor(state: GameState): IArray[WeightedRoll] = table(partitionIndex(state))
