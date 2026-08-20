package dicechess.engine.bench

import cats.implicits.*
import com.monovore.decline.*

/** Runs deterministic, position-level comparisons between two registered search algorithms.
  *
  * Unlike a full bot arena, this runner attributes every difference to a stable scenario and seed. The bundled fixture
  * set covers tactical, defensive, endgame, and forced-pass decisions; `--fixtures` can point at another catalog that
  * follows the same versioned schema.
  *
  * Usage:
  * `sbt 'arena/runMain dicechess.engine.bench.SearchEvaluationRunner --bot aggressive --baseline greedy --json report.json'`
  */
object SearchEvaluationRunner:
  private val fixturePathOpt: Opts[Option[String]] =
    Opts.option[String]("fixtures", help = "Path to a search-evaluation fixture JSON file").orNone

  def main(args: Array[String]): Unit =
    ArenaOptions.runCommand(command, args)

  private[bench] val command: Command[Unit] = Command(
    name = "SearchEvaluationRunner",
    header = "Dice Chess deterministic search scenario evaluation"
  ) {
    import ArenaOptions.*
    (botUnderTestOpt("aggressive"), baselineOpt("greedy"), fixturePathOpt, jsonPathOpt).mapN {
      (candidateId, baselineId, fixturePath, jsonPath) =>
        val fixtures = SearchFixtureCatalog.load(fixturePath).fold(error => sys.error(error), identity)
        val report   = SearchEvaluation.run(fixtures, baselineId, candidateId).fold(error => sys.error(error), identity)
        SearchEvaluation.printHuman(report)
        jsonPath.foreach(path => BotMatchRunner.writeJsonReport(path, SearchEvaluation.toJson(report)))
    }
  }
