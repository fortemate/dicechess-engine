package dicechess.engine.search

import dicechess.engine.domain.*
import io.circe.generic.auto.*
import io.circe.parser.decode
import munit.FunSuite
import scala.io.Source

case class KingCaptureProbabilityTestCase(
    name: String,
    description: String,
    fen: String,
    expectedKingProbability: Double,
    tags: Option[List[String]] = None
)

case class KingCaptureProbabilityTestSuite(
    testCases: List[KingCaptureProbabilityTestCase]
)

class KingCaptureProbabilityJsonSpec extends FunSuite:

  private def loadTestCases(resourceName: String): List[KingCaptureProbabilityTestCase] =
    val source = Option(getClass.getClassLoader.getResourceAsStream(resourceName))
      .map(Source.fromInputStream)
      .getOrElse(sys.error(s"Resource not found: $resourceName"))
    val jsonStr = try source.mkString
    finally source.close()
    decode[KingCaptureProbabilityTestSuite](jsonStr) match
      case Right(suite) => suite.testCases
      case Left(error)  => sys.error(s"Failed to parse $resourceName: $error")

  private val testCases = loadTestCases("search/king_capture_probabilities.json")

  private val Epsilon = 0.0001

  for tc <- testCases do
    val testName = s"${tc.name}: ${tc.description}"

    test(testName) {
      val state = FenParser.parse(tc.fen) match
        case Right(s)  => s
        case Left(err) => fail(s"Failed to parse FEN '${tc.fen}': $err")

      // Determine which king to check based on active color
      // If it's White's turn, we check probability of capturing Black's king
      // If it's Black's turn, we check probability of capturing White's king
      val defenderColor = if state.activeColor.isWhite then Color.Black else Color.White

      val actualProbability = KingCaptureProbability.kingCaptureProbability(state, defenderColor)

      assertEqualsDouble(
        actualProbability,
        tc.expectedKingProbability,
        Epsilon,
        s"Expected probability ${tc.expectedKingProbability} for ${tc.name}, got $actualProbability"
      )
    }
