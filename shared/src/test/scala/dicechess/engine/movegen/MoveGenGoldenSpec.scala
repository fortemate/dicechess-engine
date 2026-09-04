package dicechess.engine.movegen

import munit.FunSuite
import dicechess.engine.domain.*

/** Cross-platform golden specification for move generation.
  *
  * Executes the 39 expert-vetted test cases defined in [[MoveGenFixtures]] across JVM, Scala.js, and WebAssembly.
  *
  * Ref: #123
  */
class MoveGenGoldenSpec extends FunSuite:

  private def filterMoves(state: GameState): List[Move] =
    LegalMovesFilter.filterMaximalMoves(state)

  // The suites below register whatever the catalog happens to hold, so a dropped suite or scenario would shrink
  // the golden net in silence. Pin the shape the 40 expert-vetted cases are expected to arrive in.
  test("the golden catalog carries every expert-vetted scenario") {
    assertEquals(MoveGenFixtures.allSuites.map(_._1), List("1-Die Scenarios", "2-Dice Scenarios", "3-Dice Scenarios"))
    assertEquals(MoveGenFixtures.allSuites.map(_._3.size), List(10, 19, 11))
    assertEquals(MoveGenFixtures.allSuites.map(_._3.size).sum, 40)
  }

  for (suiteName, _, cases) <- MoveGenFixtures.allSuites do
    for tc <- cases do
      val tcName = (tc.title, tc.description) match
        case (Some(t), Some(d)) => s"$t ($d)"
        case (Some(t), None)    => t
        case (None, Some(d))    => d
        case (None, None)       => "Unnamed Scenario"

      val testName                   = s"$suiteName: $tcName"
      val options: munit.TestOptions = testName

      test(options) {
        val state = FenParser.parse(tc.fen) match
          case Right(s)  => s
          case Left(err) => fail(s"Failed to parse FEN '${tc.fen}': $err")

        // A fixture whose 7th FEN field went missing still parses — as an empty dice pool, which generates no
        // moves. The one scenario that legitimately expects no moves would then pass for the wrong reason.
        assert(state.dicePool.nonEmpty, s"Fixture FEN carries no dice pool: '${tc.fen}'")

        val actualMoves = filterMoves(state)

        import ChessDsl.toNotation
        val actualNotations   = actualMoves.map(_.toNotation).sorted
        val expectedNotations = tc.expectedMoves.sorted

        assertEquals(actualNotations, expectedNotations)
      }
