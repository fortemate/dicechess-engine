package dicechess.engine.bench

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Locale
import scala.util.{Random, Try, Using}

import cats.syntax.all.*
import dicechess.engine.domain.{FenParser, GameState, Move}
import dicechess.engine.search.{BotInfo, BotRegistry, ScoredSequence, SearchAlgorithm, TurnGenerator}

private[bench] enum SearchScenarioCategory(val id: String) derives CanEqual:
  case Tactical   extends SearchScenarioCategory("tactical")
  case Defensive  extends SearchScenarioCategory("defensive")
  case Endgame    extends SearchScenarioCategory("endgame")
  case ForcedPass extends SearchScenarioCategory("forced-pass")

private[bench] object SearchScenarioCategory:
  def parse(id: String): Either[String, SearchScenarioCategory] =
    SearchScenarioCategory.values
      .find(_.id == id)
      .toRight(s"unknown category '$id' (expected: ${SearchScenarioCategory.values.map(_.id).mkString(", ")})")

private[bench] enum SearchExpectation derives CanEqual:
  case Pass
  case Turns(allowed: List[List[String]])

  def matches(decision: SearchDecision): Boolean = this match
    case SearchExpectation.Pass           => decision.passed && decision.legal
    case SearchExpectation.Turns(allowed) => !decision.passed && decision.legal && allowed.contains(decision.moves)

  def toJson: Json = this match
    case SearchExpectation.Pass           => Json.obj("kind" -> Json.str("pass"))
    case SearchExpectation.Turns(allowed) =>
      Json.obj(
        "kind"    -> Json.str("turns"),
        "allowed" -> Json.arr(allowed.map(turn => Json.arr(turn.map(Json.str)*))*)
      )

final private[bench] case class SearchScenario(
    id: String,
    category: SearchScenarioCategory,
    description: String,
    dfen: String,
    state: GameState,
    expectation: SearchExpectation
)

final private[bench] case class SearchFixtureSet(
    id: String,
    description: String,
    seedSetId: String,
    seeds: List[Long],
    scenarios: List[SearchScenario]
)

/** Parser and loader for the versioned, deterministic search-evaluation fixture catalog. */
private[bench] object SearchFixtureCatalog:
  val SchemaVersion   = 1
  val DefaultResource = "search-evaluation/core-v1.json"

  def load(path: Option[String]): Either[String, SearchFixtureSet] =
    val input = path match
      case Some(value) =>
        Try(Files.readString(Path.of(value))).toEither.leftMap(e =>
          s"failed to read fixture file '$value': ${e.getMessage}"
        )
      case None =>
        Option(getClass.getClassLoader.getResourceAsStream(DefaultResource))
          .toRight(s"bundled fixture resource '$DefaultResource' was not found")
          .flatMap(stream =>
            Try(Using.resource(stream)(in => new String(in.readAllBytes(), StandardCharsets.UTF_8))).toEither
              .leftMap(e => s"failed to read bundled fixture resource '$DefaultResource': ${e.getMessage}")
          )
    input.flatMap(parse)

  def parse(input: String): Either[String, SearchFixtureSet] =
    for
      json    <- Json.parse(input).leftMap(error => s"invalid fixture JSON: $error")
      root    <- objectFields(json, "root", Set("schemaVersion", "id", "description", "seedSet", "scenarios"))
      version <- requiredInt(root, "schemaVersion", "root")
      _       <- Either.cond(
        version == SchemaVersion,
        (),
        s"unsupported fixture schemaVersion $version (expected $SchemaVersion)"
      )
      id            <- requiredNonEmptyString(root, "id", "root")
      description   <- requiredNonEmptyString(root, "description", "root")
      seedSetJson   <- required(root, "seedSet", "root")
      seedSet       <- parseSeedSet(seedSetJson)
      scenariosJson <- required(root, "scenarios", "root")
      scenarios     <- parseScenarios(scenariosJson)
    yield SearchFixtureSet(id, description, seedSet._1, seedSet._2, scenarios)

  private def parseSeedSet(json: Json): Either[String, (String, List[Long])] =
    for
      fields     <- objectFields(json, "seedSet", Set("id", "values"))
      id         <- requiredNonEmptyString(fields, "id", "seedSet")
      valuesJson <- required(fields, "values", "seedSet")
      values     <- valuesJson match
        case Json.JArr(items) =>
          items.zipWithIndex.traverse { (item, index) =>
            item match
              case Json.JInt(value) => Right(value)
              case _                => Left(s"seedSet.values[$index] must be an integer")
          }
        case _ => Left("seedSet.values must be an array")
      _ <- Either.cond(values.nonEmpty, (), "seedSet.values must not be empty")
      _ <- Either.cond(values.distinct.size == values.size, (), "seedSet.values must not contain duplicates")
    yield (id, values)

  private def parseScenarios(json: Json): Either[String, List[SearchScenario]] =
    for
      scenarios <- json match
        case Json.JArr(items) => items.zipWithIndex.traverse((item, index) => parseScenario(item, index))
        case _                => Left("root.scenarios must be an array")
      _ <- Either.cond(scenarios.nonEmpty, (), "root.scenarios must not be empty")
      ids = scenarios.map(_.id)
      _ <- Either.cond(ids.distinct.size == ids.size, (), "scenario ids must be unique")
    yield scenarios

  private def parseScenario(json: Json, index: Int): Either[String, SearchScenario] =
    val context = s"scenarios[$index]"
    for
      fields <- objectFields(
        json,
        context,
        Set("id", "category", "description", "dfen", "expectedTurns", "expectPass")
      )
      id          <- requiredNonEmptyString(fields, "id", context)
      categoryId  <- requiredNonEmptyString(fields, "category", context)
      category    <- SearchScenarioCategory.parse(categoryId).leftMap(error => s"$context.category: $error")
      description <- requiredNonEmptyString(fields, "description", context)
      dfen        <- requiredNonEmptyString(fields, "dfen", context)
      state       <- FenParser.parse(dfen).leftMap(error => s"$context.dfen is invalid: $error")
      _           <- Either.cond(state.dicePool.nonEmpty, (), s"$context.dfen must include a non-empty dice pool")
      canonical = FenParser.serialize(state)
      _           <- Either.cond(canonical == dfen, (), s"$context.dfen is not canonical; use '$canonical'")
      expectation <- parseExpectation(fields, context)
      _           <- validateExpectation(expectation, state, context)
    yield SearchScenario(id, category, description, dfen, state, expectation)

  private def parseExpectation(fields: List[(String, Json)], context: String): Either[String, SearchExpectation] =
    val turns = fields.collectFirst { case ("expectedTurns", value) => value }
    val pass  = fields.collectFirst { case ("expectPass", value) => value }
    (turns, pass) match
      case (Some(_), Some(_))              => Left(s"$context must define exactly one of expectedTurns or expectPass")
      case (None, None)                    => Left(s"$context must define exactly one of expectedTurns or expectPass")
      case (None, Some(Json.JBool(true)))  => Right(SearchExpectation.Pass)
      case (None, Some(Json.JBool(false))) => Left(s"$context.expectPass must be true when present")
      case (None, Some(_))                 => Left(s"$context.expectPass must be a boolean")
      case (Some(Json.JArr(items)), None)  =>
        for
          allowed <- items.zipWithIndex.traverse { (item, turnIndex) =>
            item match
              case Json.JArr(moves) =>
                for
                  notation <- moves.zipWithIndex.traverse { (move, moveIndex) =>
                    move match
                      case Json.JStr(value) if value.nonEmpty => Right(value)
                      case Json.JStr(_) => Left(s"$context.expectedTurns[$turnIndex][$moveIndex] must not be empty")
                      case _            => Left(s"$context.expectedTurns[$turnIndex][$moveIndex] must be a string")
                  }
                  _ <- Either.cond(notation.nonEmpty, (), s"$context.expectedTurns[$turnIndex] must not be empty")
                yield notation
              case _ => Left(s"$context.expectedTurns[$turnIndex] must be an array")
          }
          _ <- Either.cond(allowed.nonEmpty, (), s"$context.expectedTurns must not be empty")
          _ <- Either.cond(
            allowed.distinct.size == allowed.size,
            (),
            s"$context.expectedTurns must not contain duplicates"
          )
        yield SearchExpectation.Turns(allowed)
      case (Some(_), None) => Left(s"$context.expectedTurns must be an array")

  private def validateExpectation(
      expectation: SearchExpectation,
      state: GameState,
      context: String
  ): Either[String, Unit] =
    val legalTurns = TurnGenerator.generateAllLegalTurnPaths(state).map(turnNotation)
    expectation match
      case SearchExpectation.Pass =>
        Either.cond(legalTurns.isEmpty, (), s"$context expects a pass but the position has legal turns")
      case SearchExpectation.Turns(allowed) =>
        val illegal = allowed.filterNot(legalTurns.contains)
        Either.cond(
          illegal.isEmpty,
          (),
          s"$context.expectedTurns contains illegal turns: ${illegal.map(renderTurn).mkString(", ")}"
        )

  private def objectFields(
      json: Json,
      context: String,
      allowed: Set[String]
  ): Either[String, List[(String, Json)]] = json match
    case Json.JObj(fields) =>
      val keys       = fields.map(_._1)
      val duplicates = keys.diff(keys.distinct).distinct
      val unknown    = keys.filterNot(allowed.contains).distinct
      if duplicates.nonEmpty then Left(s"$context contains duplicate fields: ${duplicates.mkString(", ")}")
      else if unknown.nonEmpty then Left(s"$context contains unknown fields: ${unknown.mkString(", ")}")
      else Right(fields)
    case _ => Left(s"$context must be an object")

  private def required(fields: List[(String, Json)], name: String, context: String): Either[String, Json] =
    fields.collectFirst { case (`name`, value) => value }.toRight(s"$context.$name is required")

  private def requiredNonEmptyString(
      fields: List[(String, Json)],
      name: String,
      context: String
  ): Either[String, String] =
    required(fields, name, context).flatMap {
      case Json.JStr(value) if value.nonEmpty => Right(value)
      case Json.JStr(_)                       => Left(s"$context.$name must not be empty")
      case _                                  => Left(s"$context.$name must be a string")
    }

  private def requiredInt(fields: List[(String, Json)], name: String, context: String): Either[String, Int] =
    required(fields, name, context).flatMap {
      case Json.JInt(value) if value.isValidInt => Right(value.toInt)
      case Json.JInt(_)                         => Left(s"$context.$name is outside the Int range")
      case _                                    => Left(s"$context.$name must be an integer")
    }

  private def turnNotation(moves: List[Move]): List[String] = moves.map(_.toUci)

  private def renderTurn(moves: List[String]): String = moves.mkString("[", " ", "]")

final private[bench] case class SearchDecision(
    passed: Boolean,
    moves: List[String],
    score: Option[Int],
    legal: Boolean
)

private[bench] enum SearchComparison(val id: String) derives CanEqual:
  case CandidateImproved  extends SearchComparison("candidate_improved")
  case CandidateRegressed extends SearchComparison("candidate_regressed")
  case BothMatched        extends SearchComparison("both_matched")
  case BothMissed         extends SearchComparison("both_missed")

final private[bench] case class SearchBotIdentity(id: String, name: String)

final private[bench] case class SearchEvaluationResult(
    scenario: SearchScenario,
    seed: Long,
    baseline: SearchDecision,
    candidate: SearchDecision,
    baselineMatched: Boolean,
    candidateMatched: Boolean,
    comparison: SearchComparison,
    sameDecision: Boolean
)

final private[bench] case class SearchEvaluationSummary(
    runs: Int,
    sameDecisions: Int,
    differentDecisions: Int,
    baselineExpectationHits: Int,
    candidateExpectationHits: Int,
    candidateImprovements: Int,
    candidateRegressions: Int,
    bothMatched: Int,
    bothMissed: Int,
    baselineIllegal: Int,
    candidateIllegal: Int
)

final private[bench] case class SearchEvaluationReport(
    fixtures: SearchFixtureSet,
    baseline: SearchBotIdentity,
    candidate: SearchBotIdentity,
    results: List[SearchEvaluationResult]
):
  lazy val summary: SearchEvaluationSummary =
    SearchEvaluationSummary(
      runs = results.size,
      sameDecisions = results.count(_.sameDecision),
      differentDecisions = results.count(!_.sameDecision),
      baselineExpectationHits = results.count(_.baselineMatched),
      candidateExpectationHits = results.count(_.candidateMatched),
      candidateImprovements = results.count(_.comparison == SearchComparison.CandidateImproved),
      candidateRegressions = results.count(_.comparison == SearchComparison.CandidateRegressed),
      bothMatched = results.count(_.comparison == SearchComparison.BothMatched),
      bothMissed = results.count(_.comparison == SearchComparison.BothMissed),
      baselineIllegal = results.count(!_.baseline.legal),
      candidateIllegal = results.count(!_.candidate.legal)
    )

private[bench] object SearchEvaluation:
  val ReportSchemaVersion = 1

  def run(
      fixtures: SearchFixtureSet,
      baselineId: String,
      candidateId: String
  ): Either[String, SearchEvaluationReport] =
    for
      baseline  <- resolveBot(baselineId, "baseline")
      candidate <- resolveBot(candidateId, "candidate")
    yield evaluate(
      fixtures,
      SearchBotIdentity(baseline._1.id, baseline._1.name),
      baseline._2,
      SearchBotIdentity(candidate._1.id, candidate._1.name),
      candidate._2
    )

  private[bench] def evaluate(
      fixtures: SearchFixtureSet,
      baseline: SearchBotIdentity,
      baselineAlgorithm: SearchAlgorithm,
      candidate: SearchBotIdentity,
      candidateAlgorithm: SearchAlgorithm
  ): SearchEvaluationReport =
    val results = fixtures.scenarios.flatMap { scenario =>
      val legalTurns = TurnGenerator.generateAllLegalTurnPaths(scenario.state).map(_.map(_.toUci))
      fixtures.seeds.map { seed =>
        val baselineDecision  = decide(baselineAlgorithm, scenario.state, legalTurns, seed)
        val candidateDecision = decide(candidateAlgorithm, scenario.state, legalTurns, seed)
        val baselineMatched   = scenario.expectation.matches(baselineDecision)
        val candidateMatched  = scenario.expectation.matches(candidateDecision)
        val comparison        = (baselineMatched, candidateMatched) match
          case (false, true)  => SearchComparison.CandidateImproved
          case (true, false)  => SearchComparison.CandidateRegressed
          case (true, true)   => SearchComparison.BothMatched
          case (false, false) => SearchComparison.BothMissed
        SearchEvaluationResult(
          scenario,
          seed,
          baselineDecision,
          candidateDecision,
          baselineMatched,
          candidateMatched,
          comparison,
          sameDecision = baselineDecision.passed == candidateDecision.passed &&
            baselineDecision.moves == candidateDecision.moves
        )
      }
    }
    SearchEvaluationReport(fixtures, baseline, candidate, results)

  /** Builds the additive-stable machine-readable report. Existing v1 fields retain their names and types; future
    * extensions may add fields without invalidating archived reports.
    */
  def toJson(report: SearchEvaluationReport): Json =
    val summary = report.summary
    Json.obj(
      "kind"          -> Json.str("search_evaluation"),
      "schemaVersion" -> Json.int(ReportSchemaVersion),
      "fixtureSet"    -> Json.obj(
        "schemaVersion" -> Json.int(SearchFixtureCatalog.SchemaVersion),
        "id"            -> Json.str(report.fixtures.id),
        "description"   -> Json.str(report.fixtures.description),
        "scenarioCount" -> Json.int(report.fixtures.scenarios.size)
      ),
      "seedSet" -> Json.obj(
        "id"     -> Json.str(report.fixtures.seedSetId),
        "values" -> Json.arr(report.fixtures.seeds.map(Json.int)*)
      ),
      "baseline"  -> botJson(report.baseline),
      "candidate" -> botJson(report.candidate),
      "summary"   -> Json.obj(
        "runs"                     -> Json.int(summary.runs),
        "sameDecisions"            -> Json.int(summary.sameDecisions),
        "differentDecisions"       -> Json.int(summary.differentDecisions),
        "baselineExpectationHits"  -> Json.int(summary.baselineExpectationHits),
        "candidateExpectationHits" -> Json.int(summary.candidateExpectationHits),
        "candidateImprovements"    -> Json.int(summary.candidateImprovements),
        "candidateRegressions"     -> Json.int(summary.candidateRegressions),
        "bothMatched"              -> Json.int(summary.bothMatched),
        "bothMissed"               -> Json.int(summary.bothMissed),
        "baselineIllegal"          -> Json.int(summary.baselineIllegal),
        "candidateIllegal"         -> Json.int(summary.candidateIllegal)
      ),
      "results" -> Json.arr(report.results.map(resultJson)*)
    )

  def printHuman(report: SearchEvaluationReport): Unit =
    println(s"Search evaluation: ${report.candidate.id} vs ${report.baseline.id}")
    println(
      s"Fixture set: ${report.fixtures.id} (${report.fixtures.scenarios.size} scenarios), " +
        s"seed set: ${report.fixtures.seedSetId} (${report.fixtures.seeds.mkString(",")})"
    )
    println("Scenario                     | Category    | Seed | Baseline           | Candidate          | Result")
    println(
      "-----------------------------+-------------+------+--------------------+--------------------+--------------------"
    )
    report.results.foreach { result =>
      println(
        f"${clip(result.scenario.id, 28)}%-28s | ${result.scenario.category.id}%-11s | ${result.seed}%4d | " +
          f"${clip(renderDecision(result.baseline), 18)}%-18s | ${clip(renderDecision(result.candidate), 18)}%-18s | " +
          result.comparison.id
      )
    }
    val summary = report.summary
    println()
    println(
      s"Expectation hits: candidate ${summary.candidateExpectationHits}/${summary.runs}, " +
        s"baseline ${summary.baselineExpectationHits}/${summary.runs}"
    )
    println(
      s"Candidate improvements: ${summary.candidateImprovements}, regressions: ${summary.candidateRegressions}, " +
        s"same decisions: ${summary.sameDecisions}, different decisions: ${summary.differentDecisions}"
    )
    if summary.baselineIllegal > 0 || summary.candidateIllegal > 0 then
      println(s"Illegal decisions: candidate ${summary.candidateIllegal}, baseline ${summary.baselineIllegal}")

  private def resolveBot(id: String, role: String): Either[String, (BotInfo, SearchAlgorithm)] =
    val normalized = id.toLowerCase(Locale.ROOT)
    for
      algorithm <- BotRegistry.getAlgorithm(normalized).toRight(s"Unknown $role bot '$id'")
      info      <- BotRegistry.availableBots
        .find(_.id.toLowerCase(Locale.ROOT) == normalized)
        .toRight(s"Metadata for $role bot '$id' was not found")
    yield (info, algorithm)

  private def decide(
      algorithm: SearchAlgorithm,
      state: GameState,
      legalTurns: List[List[String]],
      seed: Long
  ): SearchDecision =
    algorithm.findBestMove(state, Random(seed)) match
      case None => SearchDecision(passed = true, moves = Nil, score = None, legal = legalTurns.isEmpty)
      case Some(ScoredSequence(moves, score)) =>
        val notation = moves.map(_.toUci)
        SearchDecision(passed = false, notation, Some(score), legalTurns.contains(notation))

  private def botJson(bot: SearchBotIdentity): Json =
    Json.obj("id" -> Json.str(bot.id), "name" -> Json.str(bot.name))

  private def resultJson(result: SearchEvaluationResult): Json =
    Json.obj(
      "scenarioId"   -> Json.str(result.scenario.id),
      "category"     -> Json.str(result.scenario.category.id),
      "description"  -> Json.str(result.scenario.description),
      "dfen"         -> Json.str(result.scenario.dfen),
      "seed"         -> Json.int(result.seed),
      "expectation"  -> result.scenario.expectation.toJson,
      "baseline"     -> decisionJson(result.baseline, result.baselineMatched),
      "candidate"    -> decisionJson(result.candidate, result.candidateMatched),
      "comparison"   -> Json.str(result.comparison.id),
      "sameDecision" -> Json.bool(result.sameDecision)
    )

  private def decisionJson(decision: SearchDecision, matched: Boolean): Json =
    Json.obj(
      "passed"             -> Json.bool(decision.passed),
      "moves"              -> Json.arr(decision.moves.map(Json.str)*),
      "score"              -> decision.score.map(value => Json.int(value)).getOrElse(Json.JNull),
      "legal"              -> Json.bool(decision.legal),
      "matchedExpectation" -> Json.bool(matched)
    )

  private def renderDecision(decision: SearchDecision): String =
    if decision.passed then "pass" else decision.moves.mkString(" ")

  private def clip(value: String, max: Int): String =
    if value.length <= max then value else value.take(max - 1) + "…"
