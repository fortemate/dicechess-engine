// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench

import cats.implicits.*
import com.monovore.decline.*
import dicechess.engine.domain.Color
import dicechess.engine.json.Json
import dicechess.engine.search.BotRegistry

final case class PositionMatchSummary(
    position: EndgamePosition,
    matchResult: MatchResult,
    candWinsAsAttacker: Int,
    candLossesAsAttacker: Int,
    candDrawsAsAttacker: Int,
    candWinsAsDefender: Int,
    candLossesAsDefender: Int,
    candDrawsAsDefender: Int,
    gamesAsAttacker: Int,
    gamesAsDefender: Int
):
  def candidateWins: Int = matchResult.winsAsWhite + matchResult.winsAsBlack
  def baselineWins: Int  = matchResult.lossesAsWhite + matchResult.lossesAsBlack
  def draws: Int         = matchResult.drawsAsWhite + matchResult.drawsAsBlack
  def totalGames: Int    = matchResult.totalGames

  def candAttackerWinRate: Double =
    if gamesAsAttacker > 0 then (candWinsAsAttacker.toDouble / gamesAsAttacker) * 100.0 else 0.0

  def candDefenderWinRate: Double =
    if gamesAsDefender > 0 then (candWinsAsDefender.toDouble / gamesAsDefender) * 100.0 else 0.0

  def baseAttackerWinRate: Double =
    if gamesAsDefender > 0 then (candLossesAsDefender.toDouble / gamesAsDefender) * 100.0 else 0.0

  def baseDefenderWinRate: Double =
    if gamesAsAttacker > 0 then (candLossesAsAttacker.toDouble / gamesAsAttacker) * 100.0 else 0.0

final case class CategorySummary(
    category: String,
    positionCount: Int,
    totalGames: Int,
    candidateWins: Int,
    baselineWins: Int,
    draws: Int,
    candAttackerWins: Int,
    candAttackerGames: Int,
    candDefenderWins: Int,
    candDefenderGames: Int
):
  def candAttackerWinRate: Double =
    if candAttackerGames > 0 then (candAttackerWins.toDouble / candAttackerGames) * 100.0 else 0.0

  def candDefenderWinRate: Double =
    if candDefenderGames > 0 then (candDefenderWins.toDouble / candDefenderGames) * 100.0 else 0.0

/** Executable runner for evaluating bots on the standardized Endgame Benchmark Suite.
  *
  * Measures attacker conversion efficiency and defender tenacity across canonical asymmetric endings (Lone King vs
  * K+Q/K+R/K+2P, K+P vs K, K+B+N vs K).
  *
  * Usage: `sbt 'arena/runMain dicechess.engine.bench.EndgameSuiteRunner --bot aggressive --baseline greedy --games 5'`
  */
object EndgameSuiteRunner:

  def main(args: Array[String]): Unit =
    ArenaOptions.runCommand(command, args)

  val suitePathOpt: Opts[Option[String]] =
    Opts.option[String]("suite", help = "Path to an endgame suite JSON file").orNone

  val categoryOpt: Opts[Option[String]] =
    Opts.option[String]("category", help = "Optional category filter (e.g. lone-king-vs-kq)").orNone

  private[bench] val command: Command[Unit] = Command(
    name = "EndgameSuiteRunner",
    header = "Dice Chess Endgame Benchmark Suite Runner"
  ) {
    import ArenaOptions.*
    (
      botUnderTestOpt("aggressive"),
      baselineOpt("greedy"),
      gamesOpt(5),
      seedOpt(42L),
      suitePathOpt,
      categoryOpt,
      jsonPathOpt
    ).mapN { (candidateBotId, baselineBotId, gamesPerColor, seed, suitePath, categoryFilter, jsonPath) =>
      try runSuite(candidateBotId, baselineBotId, gamesPerColor, seed, suitePath, categoryFilter, jsonPath)
      catch
        case e: Exception =>
          System.err.println(s"EndgameSuiteRunner error: ${e.getMessage}")
          sys.exit(1)
    }
  }

  def runSuite(
      candidateBotId: String,
      baselineBotId: String,
      gamesPerColor: Int,
      seed: Long = 42L,
      suitePath: Option[String] = None,
      categoryFilter: Option[String] = None,
      jsonPath: Option[String] = None
  ): List[PositionMatchSummary] =
    val suite = EndgameBenchmarkCatalog.load(suitePath).fold(err => sys.error(err), identity)

    val candidateAlgo = BotRegistry
      .getAlgorithm(candidateBotId)
      .getOrElse(sys.error(s"Candidate bot '$candidateBotId' not found in BotRegistry!"))
    val baselineAlgo = BotRegistry
      .getAlgorithm(baselineBotId)
      .getOrElse(sys.error(s"Baseline bot '$baselineBotId' not found in BotRegistry!"))

    val candidateInfo = BotRegistry.availableBots
      .find(_.id.equalsIgnoreCase(candidateBotId))
      .getOrElse(sys.error(s"Candidate bot info '$candidateBotId' not found"))
    val baselineInfo = BotRegistry.availableBots
      .find(_.id.equalsIgnoreCase(baselineBotId))
      .getOrElse(sys.error(s"Baseline bot info '$baselineBotId' not found"))

    val positions = categoryFilter match
      case Some(cat) =>
        val filtered = suite.positions.filter(_.category.equalsIgnoreCase(cat))
        if filtered.isEmpty then sys.error(s"No positions matched category filter '$cat'")
        filtered
      case None => suite.positions

    println("=" * 95)
    println(s"🎲♟️  Dice Chess Endgame Benchmark Suite Runner")
    println(s"Candidate Bot : ${candidateInfo.name} (${candidateInfo.id})")
    println(s"Baseline Bot  : ${baselineInfo.name} (${baselineInfo.id})")
    println(s"Games/Position: ${gamesPerColor * 2} ($gamesPerColor per color)")
    println(s"Random Seed   : $seed")
    println(s"Suite ID      : ${suite.id} (${positions.size} positions)")
    categoryFilter.foreach(cat => println(s"Category Filter: $cat"))
    println("=" * 95)

    val summaries = for pos <- positions yield
      val matchResult = BotMatchRunner.runMatch(candidateAlgo, baselineAlgo, gamesPerColor, pos.state, seed)
      val summary     = computePositionSummary(pos, matchResult)
      summary

    printHumanTable(summaries)
    val categorySummaries = computeCategorySummaries(summaries)
    printCategoryTable(categorySummaries)
    printOverallSummary(summaries)

    jsonPath.foreach { path =>
      val report =
        buildJsonReport(suite, candidateInfo.id, baselineInfo.id, gamesPerColor, seed, summaries, categorySummaries)
      val target = java.nio.file.Path.of(path)
      Option(target.getParent).foreach(p => java.nio.file.Files.createDirectories(p))
      BotMatchRunner.writeJsonReport(path, report)
      println(s"Saved report to $path")
    }

    summaries

  private[bench] def computePositionSummary(pos: EndgamePosition, res: MatchResult): PositionMatchSummary =
    val isAttackerWhite                                          = pos.attackerColor == Color.White
    val (candAtkWins, candAtkLosses, candAtkDraws, candAtkGames) =
      if isAttackerWhite then
        (res.winsAsWhite, res.lossesAsWhite, res.drawsAsWhite, res.winsAsWhite + res.lossesAsWhite + res.drawsAsWhite)
      else
        (res.winsAsBlack, res.lossesAsBlack, res.drawsAsBlack, res.winsAsBlack + res.lossesAsBlack + res.drawsAsBlack)

    val (candDefWins, candDefLosses, candDefDraws, candDefGames) =
      if isAttackerWhite then
        (res.winsAsBlack, res.lossesAsBlack, res.drawsAsBlack, res.winsAsBlack + res.lossesAsBlack + res.drawsAsBlack)
      else
        (res.winsAsWhite, res.lossesAsWhite, res.drawsAsWhite, res.winsAsWhite + res.lossesAsWhite + res.drawsAsWhite)

    PositionMatchSummary(
      position = pos,
      matchResult = res,
      candWinsAsAttacker = candAtkWins,
      candLossesAsAttacker = candAtkLosses,
      candDrawsAsAttacker = candAtkDraws,
      candWinsAsDefender = candDefWins,
      candLossesAsDefender = candDefLosses,
      candDrawsAsDefender = candDefDraws,
      gamesAsAttacker = candAtkGames,
      gamesAsDefender = candDefGames
    )

  private[bench] def computeCategorySummaries(summaries: List[PositionMatchSummary]): List[CategorySummary] =
    summaries.groupBy(_.position.category).toList.sortBy(_._1).map { case (category, posList) =>
      CategorySummary(
        category = category,
        positionCount = posList.size,
        totalGames = posList.map(_.totalGames).sum,
        candidateWins = posList.map(_.candidateWins).sum,
        baselineWins = posList.map(_.baselineWins).sum,
        draws = posList.map(_.draws).sum,
        candAttackerWins = posList.map(_.candWinsAsAttacker).sum,
        candAttackerGames = posList.map(_.gamesAsAttacker).sum,
        candDefenderWins = posList.map(_.candWinsAsDefender).sum,
        candDefenderGames = posList.map(_.gamesAsDefender).sum
      )
    }

  private def printHumanTable(summaries: List[PositionMatchSummary]): Unit =
    println(
      f"${"Category"}%-18s | ${"Position ID"}%-24s | ${"Dist"}%4s | ${"Cand W/D/L"}%10s | ${"Atk Win%"}%9s | ${"Def Win%"}%9s | ${"Time"}%6s"
    )
    println("-" * 95)
    for s <- summaries do
      val distStr   = s"${s.position.kingChebyshevDistance}/${s.position.kingManhattanDistance}"
      val recordStr = s"${s.candidateWins}/${s.draws}/${s.baselineWins}"
      val durSec    = s.matchResult.durationMs / 1000.0
      println(
        f"${s.position.category}%-18s | ${s.position.id}%-24s | $distStr%4s | $recordStr%10s | ${s.candAttackerWinRate}%8.1f%% | ${s.candDefenderWinRate}%8.1f%% | ${durSec}%5.2fs"
      )
    println("-" * 95)

  private def printCategoryTable(categories: List[CategorySummary]): Unit =
    println("\n📊 CATEGORY BREAKDOWN:")
    println(
      f"${"Category"}%-20s | ${"Pos"}%3s | ${"Games"}%5s | ${"Cand Wins"}%9s | ${"Base Wins"}%9s | ${"Draws"}%5s | ${"Cand Atk%"}%9s | ${"Cand Def%"}%9s"
    )
    println("-" * 95)
    for c <- categories do
      println(
        f"${c.category}%-20s | ${c.positionCount}%3d | ${c.totalGames}%5d | ${c.candidateWins}%9d | ${c.baselineWins}%9d | ${c.draws}%5d | ${c.candAttackerWinRate}%8.1f%% | ${c.candDefenderWinRate}%8.1f%%"
      )
    println("-" * 95)

  private def printOverallSummary(summaries: List[PositionMatchSummary]): Unit =
    val totalGames   = summaries.map(_.totalGames).sum
    val totalCandW   = summaries.map(_.candidateWins).sum
    val totalBaseW   = summaries.map(_.baselineWins).sum
    val totalDraws   = summaries.map(_.draws).sum
    val totalAtkWins = summaries.map(_.candWinsAsAttacker).sum
    val totalAtkG    = summaries.map(_.gamesAsAttacker).sum
    val totalDefWins = summaries.map(_.candWinsAsDefender).sum
    val totalDefG    = summaries.map(_.gamesAsDefender).sum
    val totalTimeMs  = summaries.map(_.matchResult.durationMs).sum

    val candScorePct = if totalGames > 0 then (totalCandW.toDouble / totalGames) * 100.0 else 0.0
    val atkWinRate   = if totalAtkG > 0 then (totalAtkWins.toDouble / totalAtkG) * 100.0 else 0.0
    val defWinRate   = if totalDefG > 0 then (totalDefWins.toDouble / totalDefG) * 100.0 else 0.0

    println("\n🏆 OVERALL BENCHMARK RESULTS:")
    println(f"Total Positions : ${summaries.size}%d")
    println(f"Total Games     : $totalGames%d")
    println(f"Candidate Score : $totalCandW%d wins, $totalDraws%d draws, $totalBaseW%d losses ($candScorePct%.1f%%)")
    println(f"Candidate Atk %% : $atkWinRate%.1f%% ($totalAtkWins / $totalAtkG)")
    println(f"Candidate Def %% : $defWinRate%.1f%% ($totalDefWins / $totalDefG)")
    println(f"Total Time      : ${totalTimeMs / 1000.0}%.2fs")
    println("=" * 95)

  private[bench] def buildJsonReport(
      suite: EndgameSuite,
      candidateId: String,
      baselineId: String,
      gamesPerColor: Int,
      seed: Long,
      summaries: List[PositionMatchSummary],
      categories: List[CategorySummary]
  ): Json =
    Json.obj(
      "schemaVersion" -> Json.int(1),
      "suiteId"       -> Json.str(suite.id),
      "candidateId"   -> Json.str(candidateId),
      "baselineId"    -> Json.str(baselineId),
      "gamesPerColor" -> Json.int(gamesPerColor),
      "seed"          -> Json.int(seed.toInt),
      "positions"     -> Json.arr(summaries.map { s =>
        Json.obj(
          "id"                  -> Json.str(s.position.id),
          "category"            -> Json.str(s.position.category),
          "fen"                 -> Json.str(s.position.fen),
          "chebyshevDistance"   -> Json.int(s.position.kingChebyshevDistance),
          "manhattanDistance"   -> Json.int(s.position.kingManhattanDistance),
          "candidateWins"       -> Json.int(s.candidateWins),
          "baselineWins"        -> Json.int(s.baselineWins),
          "draws"               -> Json.int(s.draws),
          "candAttackerWinRate" -> Json.num(s.candAttackerWinRate),
          "candDefenderWinRate" -> Json.num(s.candDefenderWinRate),
          "baseAttackerWinRate" -> Json.num(s.baseAttackerWinRate),
          "baseDefenderWinRate" -> Json.num(s.baseDefenderWinRate),
          "durationMs"          -> Json.int(s.matchResult.durationMs.toInt)
        )
      }*),
      "categories" -> Json.arr(categories.map { c =>
        Json.obj(
          "category"            -> Json.str(c.category),
          "positionCount"       -> Json.int(c.positionCount),
          "totalGames"          -> Json.int(c.totalGames),
          "candidateWins"       -> Json.int(c.candidateWins),
          "baselineWins"        -> Json.int(c.baselineWins),
          "draws"               -> Json.int(c.draws),
          "candAttackerWinRate" -> Json.num(c.candAttackerWinRate),
          "candDefenderWinRate" -> Json.num(c.candDefenderWinRate)
        )
      }*),
      "summary" -> Json.obj(
        "totalPositions"      -> Json.int(summaries.size),
        "totalGames"          -> Json.int(summaries.map(_.totalGames).sum),
        "totalCandidateWins"  -> Json.int(summaries.map(_.candidateWins).sum),
        "totalBaselineWins"   -> Json.int(summaries.map(_.baselineWins).sum),
        "totalDraws"          -> Json.int(summaries.map(_.draws).sum),
        "candAttackerWinRate" -> Json.num(if summaries.map(_.gamesAsAttacker).sum > 0 then
          (summaries.map(_.candWinsAsAttacker).sum.toDouble / summaries.map(_.gamesAsAttacker).sum) * 100.0
        else 0.0),
        "candDefenderWinRate" -> Json.num(if summaries.map(_.gamesAsDefender).sum > 0 then
          (summaries.map(_.candWinsAsDefender).sum.toDouble / summaries.map(_.gamesAsDefender).sum) * 100.0
        else 0.0)
      )
    )
