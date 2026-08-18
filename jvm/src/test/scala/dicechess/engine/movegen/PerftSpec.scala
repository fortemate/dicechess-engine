package dicechess.engine.movegen

import munit.FunSuite
import dicechess.engine.domain.*
import io.circe.generic.auto.*
import io.circe.parser.decode
import scala.io.Source

case class PerftTestCase(
    title: String,
    fen: String,
    diceRoll: Int,
    depth: Int,
    expectedNodes: Long
)

class PerftSpec extends FunSuite:

  private def loadTestCases(resourceName: String): List[PerftTestCase] =
    val source = Option(getClass.getClassLoader.getResourceAsStream(resourceName))
      .map(Source.fromInputStream)
      .getOrElse(sys.error(s"Resource not found: $resourceName"))
    val jsonStr = try source.mkString
    finally source.close()
    decode[List[PerftTestCase]](jsonStr) match
      case Right(cases) => cases
      case Left(error)  => sys.error(s"Failed to parse $resourceName: $error")

  private val cases = try loadTestCases("movegen/perft_suite.json")
  catch
    case e: Exception =>
      test("Failed to load Perft suite") {
        fail("Could not load movegen/perft_suite.json", e)
      }
      Nil

  for tc <- cases do
    test(s"Perft: ${tc.title} | Dice: ${tc.diceRoll} | Depth: ${tc.depth}") {
      val state = FenParser.parse(tc.fen) match
        case Right(s)  => s
        case Left(err) => fail(s"Failed to parse FEN '${tc.fen}': $err")

      val actualNodes = Perft.countNodes(state.withDicePool(List(tc.diceRoll)), tc.depth)
      assertEquals(actualNodes, tc.expectedNodes)
    }
