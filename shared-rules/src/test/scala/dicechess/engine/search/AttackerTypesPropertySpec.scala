// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.*
import dicechess.engine.movegen.MoveGenerator
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*

/** Property oracles for [[AttackerTypes]] and the roll-availability counts of [[DiceRolls]] (#395).
  *
  * The mask is checked against an independent mailbox scan — the piece actually standing on each attacker square —
  * rather than against the bitboard intersection it is implemented with, so a queen merged into a slider mask by
  * [[dicechess.engine.movegen.MoveGenerator.allAttackers]] would be caught setting a Bishop or Rook bit. The
  * availability count is checked against an exhaustive enumeration of all 216 ordered rolls for every one of the 64
  * type subsets.
  */
class AttackerTypesPropertySpec extends ScalaCheckSuite:

  private val domainProps  = new dicechess.engine.domain.PropertySpec()
  private val gameStateGen = domainProps.gameStateGen
  private val squareGen    = domainProps.squareGen
  private val bitboardGen  = domainProps.bitboardGen
  private val colorGen     = Gen.oneOf(Color.White, Color.Black)

  /** Independent reference: the types of the pieces standing on `squares`, read from the mailbox square by square. */
  private def referenceMask(state: GameState, squares: Bitboard): Int =
    var mask = 0
    var idx  = 0
    while idx < 64 do
      val sq = Square.fromIndex(idx)
      if squares.contains(sq) then
        val piece = state.mailbox(sq)
        if !piece.isEmpty then mask |= 1 << (piece.pieceType.diceValue - 1)
      idx += 1
    mask

  property("the attacker mask equals the mailbox scan of allAttackers, for every square and both colours"):
    forAll(gameStateGen, squareGen, colorGen) { (state: GameState, target: Square, attacker: Color) =>
      val attackers = MoveGenerator.allAttackers(state, target, attacker)
      val expected  = referenceMask(state, attackers)
      val actual    = AttackerTypes.mask(state, target, attacker)
      (actual == expected) :| s"target=${target.toNotation} attacker=$attacker expected=$expected actual=$actual"
    }

  property("the square-set mask equals the mailbox scan for arbitrary square sets"):
    forAll(gameStateGen, bitboardGen) { (state: GameState, squares: Bitboard) =>
      AttackerTypes.mask(state, squares) == referenceMask(state, squares)
    }

  property("a queen never contributes a Bishop or Rook bit: masking out real bishops and rooks removes those bits"):
    forAll(gameStateGen, squareGen, colorGen) { (state: GameState, target: Square, attacker: Color) =>
      val attackers  = MoveGenerator.allAttackers(state, target, attacker)
      val noSliders  = attackers & ~(state.bishops | state.rooks)
      val mask       = AttackerTypes.mask(state, noSliders)
      val sliderBits = AttackerTypes.bit(PieceType.Bishop) | AttackerTypes.bit(PieceType.Rook)
      (mask & sliderBits) == 0
    }

  property("count is the number of distinct types present and never exceeds the attacker count"):
    forAll(gameStateGen, squareGen, colorGen) { (state: GameState, target: Square, attacker: Color) =>
      val attackers = MoveGenerator.allAttackers(state, target, attacker)
      val mask      = AttackerTypes.mask(state, attackers)
      val distinct  = PieceType.all.count(pt => AttackerTypes.contains(mask, pt))
      AttackerTypes.count(mask) == distinct && distinct <= attackers.count
    }

  property("rollsShowingAnyOf matches an exhaustive enumeration of the 216 ordered rolls for all 64 type subsets"):
    forAll(Gen.choose(0, 63)) { (typeMask: Int) =>
      var served = 0
      for
        d1 <- 1 to 6
        d2 <- 1 to 6
        d3 <- 1 to 6
      do
        val faces = (1 << (d1 - 1)) | (1 << (d2 - 1)) | (1 << (d3 - 1))
        if (faces & typeMask) != 0 then served += 1
      val k = Integer.bitCount(typeMask)
      (AttackerTypes.availableRolls(typeMask) == served) :| s"mask=$typeMask k=$k enumerated=$served" &&
      (DiceRolls.rollsShowingAnyOf(k) == served) :| s"k=$k" &&
      (AttackerTypes.availabilityProbability(
        typeMask
      ) == served.toDouble / DiceRolls.totalOrderedRolls) :| "probability"
    }

  property("availability is monotone in k and bounded by 0 and 216"):
    forAll(Gen.choose(0, 5)) { (k: Int) =>
      val lower = DiceRolls.rollsShowingAnyOf(k)
      val upper = DiceRolls.rollsShowingAnyOf(k + 1)
      lower >= 0 && lower < upper && upper <= DiceRolls.totalOrderedRolls
    }
