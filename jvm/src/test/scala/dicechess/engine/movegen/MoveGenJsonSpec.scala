package dicechess.engine.movegen

import munit.FunSuite
import dicechess.engine.domain.*
import io.circe.generic.auto.*
import io.circe.parser.decode
import scala.io.Source

class MoveGenJsonSpec extends FunSuite:

  private def filterMoves(state: GameState): List[Move] =
    LegalMovesFilter.filterMaximalMoves(state)

  private def loadTestCases(resourceName: String): List[MoveGenTestCase] =
    val source = Option(getClass.getClassLoader.getResourceAsStream(resourceName))
      .map(Source.fromInputStream)
      .getOrElse(sys.error(s"Resource not found: $resourceName"))
    val jsonStr = try source.mkString
    finally source.close()
    decode[List[MoveGenTestCase]](jsonStr) match
      case Right(cases) => cases
      case Left(error)  => sys.error(s"Failed to parse $resourceName: $error")

  // Load JSON test suites
  private val testSuites = Map(
    "1-Die Scenarios"  -> "movegen/movegen_1_dice.json",
    "2-Dice Scenarios" -> "movegen/movegen_2_dice.json",
    "3-Dice Scenarios" -> "movegen/movegen_3_dice.json"
  )

  for (suiteName, resourcePath) <- testSuites do
    val cases = try loadTestCases(resourcePath)
    catch
      case e: Exception =>
        // Register a failing test if resource loading itself fails
        test(s"Failed to load suite $suiteName") {
          fail(s"Could not load $resourcePath", e)
        }
        Nil

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

        val actualMoves = filterMoves(state)

        import ChessDsl.toNotation
        val actualNotations   = actualMoves.map(_.toNotation).sorted
        val expectedNotations = tc.expectedMoves.sorted

        assertEquals(actualNotations, expectedNotations)
      }
