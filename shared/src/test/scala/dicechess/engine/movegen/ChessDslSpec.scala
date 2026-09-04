package dicechess.engine.movegen

import munit.FunSuite
import dicechess.engine.domain.*
import dicechess.engine.domain.PieceType.*
import dicechess.engine.movegen.ChessDsl.*

/** Contract for the [[ChessDsl]] fixture builders.
  *
  * Every builder has to hand [[dicechess.engine.domain.FenParser]] a FEN it actually accepts. The numeric overloads
  * used to interpolate the die value itself — `"... 0 1 1"` — and `FenParser` rejects that with
  * `Invalid dice-pool character '1'`, because the 7th field is piece letters. Nothing caught it: the fixtures reach the
  * builders through the raw-FEN form, so the only consumer of `withDice` was a doc example.
  *
  * Ref: #123
  */
class ChessDslSpec extends FunSuite:

  private val emptyBoard = "4k3/8/8/8/8/8/8/4K3 w - - 0 1"

  private def dicePoolOf(fen: String): List[Int] =
    FenParser.parse(fen) match
      case Right(state) => state.dicePool
      case Left(error)  => fail(s"FenParser rejected the FEN the DSL built: '$fen' ($error)")

  private def parseErrorOf(fen: String): String =
    FenParser.parse(fen) match
      case Left(error)  => error
      case Right(state) => fail(s"FenParser accepted '$fen' as dice ${state.dicePool}, expected a rejection")

  test("a single die is encoded as its piece letter") {
    assertEquals(dicePoolOf(emptyBoard.withDice(1).fen), List(1))
    assertEquals(dicePoolOf(emptyBoard.withDice(6).fen), List(6))
    assertEquals(dicePoolOf(emptyBoard.withDice(Pawn).fen), List(1))
    assertEquals(dicePoolOf(emptyBoard.withDice(King).fen), List(6))
  }

  test("a two-dice roll is encoded as two piece letters") {
    assertEquals(dicePoolOf(emptyBoard.withDice((1, 6)).fen), List(1, 6))
    assertEquals(dicePoolOf(emptyBoard.withDice((Pawn, King)).fen), List(1, 6))
  }

  test("a three-dice roll is encoded as three piece letters") {
    assertEquals(dicePoolOf(emptyBoard.withDice((1, 2, 3)).fen), List(1, 2, 3))
    assertEquals(dicePoolOf(emptyBoard.withDice((Pawn, Knight, Bishop)).fen), List(1, 2, 3))
  }

  test("a dice list and an already-lettered string reach the same pool") {
    assertEquals(dicePoolOf(emptyBoard.withDice(List(1, 2, 3)).fen), List(1, 2, 3))
    assertEquals(dicePoolOf(emptyBoard.withDice("PNB").fen), List(1, 2, 3))
  }

  test("a die outside 1 to 6 is reported by name rather than encoded as some other die") {
    assertEquals(parseErrorOf(emptyBoard.withDice(7).fen), "Invalid dice-pool character '7'")
    assertEquals(parseErrorOf(emptyBoard.withDice(0).fen), "Invalid dice-pool character '0'")
  }

  test("title and description chain in either order and land in the same test case") {
    val titleFirst = emptyBoard.withDice(Pawn).titled("A title").describedAs("A description").shouldYield("e1e2")
    val descFirst  = emptyBoard.withDice(Pawn).describedAs("A description").titled("A title").shouldYield("e1e2")
    assertEquals(titleFirst, descFirst)
    assertEquals(titleFirst.title, Some("A title"))
    assertEquals(titleFirst.description, Some("A description"))
    assertEquals(titleFirst.expectedMoves, List("e1e2"))
  }

  test("a FEN that already carries its dice pool skips the builder and yields the same case") {
    val viaBuilder = emptyBoard.withDice(Pawn).titled("A title").shouldYield("e1e2")
    val viaRawFen  = s"$emptyBoard P".titled("A title").shouldYield("e1e2")
    assertEquals(viaBuilder, viaRawFen)
  }
