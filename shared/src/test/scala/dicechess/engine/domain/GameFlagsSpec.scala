package dicechess.engine.domain

import munit.FunSuite

class GameFlagsSpec extends FunSuite {

  test("GameFlags empty initialization") {
    val flags = GameFlags.empty
    assertEquals(flags.activeColor, Color.White)
    assertEquals(flags.castlingRights, 0)
    assertEquals(flags.enPassantFiles, 0)
    assertEquals(flags.dicePool, Nil)
    assertEquals(flags.halfMoveClock, 0)
  }

  test("GameFlags pack and unpack all fields") {
    val flags = GameFlags(
      color = Color.Black,
      castlingRights = 10,  // 1010
      enPassantFiles = 129, // 10000001
      dice1 = 2,
      dice2 = 4,
      dice3 = 6,
      halfMoveClock = 85
    )

    assertEquals(flags.activeColor, Color.Black)
    assertEquals(flags.castlingRights, 10)
    assertEquals(flags.enPassantFiles, 129)
    assertEquals(flags.dicePool, List(2, 4, 6))
    assertEquals(flags.halfMoveClock, 85)
  }

  test("GameFlags modifiers should work independently") {
    val initial = GameFlags.empty

    val step1 = initial.toggleActiveColor
    assertEquals(step1.activeColor, Color.Black)
    assertEquals(step1.castlingRights, 0) // Unchanged

    val step2 = step1.withCastlingRights(15) // 1111
    assertEquals(step2.activeColor, Color.Black)
    assertEquals(step2.castlingRights, 15)
    assertEquals(step2.halfMoveClock, 0)

    val step3 = step2.withEnPassantFiles(255)
    assertEquals(step3.castlingRights, 15)
    assertEquals(step3.enPassantFiles, 255)

    val step4 = step3.withHalfMoveClock(100)
    assertEquals(step4.enPassantFiles, 255)
    assertEquals(step4.halfMoveClock, 100)

    val step4b = step3.withHalfMoveClock(GameFlags.MaxHalfMoveClock + 1)
    assertEquals(step4b.halfMoveClock, GameFlags.MaxHalfMoveClock)
    // saturating the clock leaves every other packed field untouched
    assertEquals(step4b.activeColor, step3.activeColor)
    assertEquals(step4b.castlingRights, step3.castlingRights)
    assertEquals(step4b.enPassantFiles, step3.enPassantFiles)
    assertEquals(step4b.dicePool, step3.dicePool)

    val step5 = step4.addDie(3).toOption.get
    assertEquals(step5.dicePool, List(3))
    assertEquals(step5.halfMoveClock, 100)
  }

  test("GameFlags half-move clock clamps a negative value to 0 rather than masking it (#586)") {
    val viaWith = GameFlags.empty.withHalfMoveClock(-1)
    assertEquals(viaWith.halfMoveClock, 0)

    val viaApply = GameFlags(Color.White, 0, 0, 0, 0, 0, halfMoveClock = -1)
    assertEquals(viaApply.halfMoveClock, 0)

    val viaFromList = GameFlags.fromList(Color.White, 0, 0, Nil, halfMoveClock = -1)
    assertEquals(viaFromList.halfMoveClock, 0)
  }

  test("GameFlags fromList") {
    val flags = GameFlags.fromList(
      color = Color.White,
      castlingRights = 0,
      enPassantFiles = 0,
      dicePool = List(1, 5),
      halfMoveClock = 0
    )

    assertEquals(flags.dicePool, List(1, 5))
  }

  test("GameFlags dice management") {
    var flags = GameFlags.empty
    assertEquals(flags.dicePool, Nil)

    flags = flags.addDie(6).toOption.get
    assertEquals(flags.dicePool, List(6))

    flags = flags.addDie(2).toOption.get
    assertEquals(flags.dicePool, List(6, 2))

    flags = flags.addDie(2).toOption.get
    assertEquals(flags.dicePool, List(6, 2, 2))

    assert(flags.addDie(7).isLeft)
    assert(flags.addDie(1).isLeft)

    flags = flags.removeDie(2)
    assertEquals(flags.dicePool, List(6, 2))

    flags = flags.removeDie(5) // Not present
    assertEquals(flags.dicePool, List(6, 2))

    flags = flags.removeDie(6)
    assertEquals(flags.dicePool, List(2))

    flags = flags.removeDie(2)
    assertEquals(flags.dicePool, Nil)
  }

  test("withDiceSlotsOf copies only the dice slots, preserving holes and other fields") {
    // Source pool [1, 4, 5] with the rook die (4) removed -> a hole in the middle slot.
    val src = GameFlags.empty.addDie(1).toOption.get.addDie(4).toOption.get.addDie(5).toOption.get.removeDie(4)
    assertEquals(src.diceSlot1, 1)
    assertEquals(src.diceSlot2, 0)
    assertEquals(src.diceSlot3, 5)

    // Destination carries unrelated state and a different pool.
    val dst = GameFlags.empty
      .withActiveColor(Color.Black)
      .withCastlingRights(0xf)
      .withHalfMoveClock(7)
      .addDie(2)
      .toOption
      .get

    val out = dst.withDiceSlotsOf(src)

    // Dice slots are taken verbatim from src (the hole is preserved).
    assertEquals(out.diceSlot1, 1)
    assertEquals(out.diceSlot2, 0)
    assertEquals(out.diceSlot3, 5)
    assertEquals(out.dicePool, List(1, 5)) // the getter skips the empty slot
    // Every non-dice field is the destination's, untouched.
    assertEquals(out.activeColor, Color.Black)
    assertEquals(out.castlingRights, 0xf)
    assertEquals(out.halfMoveClock, 7)

    // Slot-level queries read straight from the int bits.
    assert(!out.isDicePoolEmpty)
    assert(out.containsDie(1) && out.containsDie(5))
    assert(!out.containsDie(4))
    assert(GameFlags.empty.isDicePoolEmpty)
  }
}
