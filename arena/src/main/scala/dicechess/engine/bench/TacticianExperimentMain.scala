// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench

import dicechess.engine.domain.*
import dicechess.engine.search.*
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.collection.mutable.ListBuffer
import scala.util.Random

/** Private-output experimental comparison. All profile values, sample sizes and seeds are explicit CLI inputs. Uses the
  * existing arena's game application, terminal checks and draw rules. No defaults or registry changes.
  */
object TacticianExperimentMain:
  def main(args: Array[String]): Unit =
    require(args.length == 9, "expected: mode limit attackCp dangerCp hangingWeight count seed repeats outputDirectory")
    val mode    = args(0)
    val config  = TacticianConfig(args(1).toInt, args(2).toInt, args(3).toInt, args(4).toDouble)
    val count   = args(5).toInt
    val seed    = args(6).toLong
    val repeats = args(7).toInt
    val output  = Path.of(args(8))
    require(count > 0 && repeats > 0, "count and repeats must be positive")
    require(mode == "games" || mode == "latency", "mode must be games or latency")
    require(mode != "games" || count > 1, "a paired confidence interval requires at least two pairs")
    Files.createDirectories(output)
    val search = new TacticianSearch(config)
    mode match
      case "games"   => games(search, count, seed, output)
      case "latency" => latency(search, count, seed, repeats, output)
      case _         => sys.error("mode must be games or latency")

  private class Measured(delegate: SearchAlgorithm, seed: Long) extends SearchAlgorithm:
    val times                                                  = ListBuffer.empty[Long]
    private val rng                                            = new Random(seed)
    def findBestMove(state: GameState): Option[ScoredSequence] =
      val started = System.nanoTime()
      val result  = delegate.findBestMove(state, rng)
      times += System.nanoTime() - started
      result
    override def findBestMove(state: GameState, random: Random): Option[ScoredSequence] =
      val _ = random
      findBestMove(state)

  private def games(search: TacticianSearch, count: Int, seed: Long, output: Path): Unit =
    val writer         = Files.newBufferedWriter(output.resolve("games.csv"), StandardOpenOption.CREATE_NEW)
    val candidateTimes = ListBuffer.empty[Long]
    val baselineTimes  = ListBuffer.empty[Long]
    var wins           = 0
    var losses         = 0
    var draws          = 0
    val pairScores     = ListBuffer.empty[Double]
    try
      writer.write("pair,color,score,candidateMoves,baselineMoves,candidateNs,baselineNs\n")
      for pair <- 0 until count do
        var pairScore = 0.0
        for white <- List(true, false) do
          val candidate = new Measured(search, seed + pair + 1000000L)
          val baseline  = new Measured(AggressiveSearch, seed + pair + 2000000L)
          val outcome   = BotMatchRunner.simulateGame(
            if white then candidate else baseline,
            if white then baseline else candidate,
            new Random(seed + pair),
            new Random(0)
          )
          val score = outcome match
            case GameOutcome.Draw =>
              draws += 1
              0.5
            case GameOutcome.Win(color) if color.isWhite == white =>
              wins += 1
              1.0
            case GameOutcome.Win(_) =>
              losses += 1
              0.0
          pairScore += score / 2
          candidateTimes ++= candidate.times
          baselineTimes ++= baseline.times
          writer.write(
            s"$pair,$white,$score,${candidate.times.size},${baseline.times.size},${candidate.times.sum},${baseline.times.sum}\n"
          )
          writer.flush()
        pairScores += pairScore
        if (pair + 1) % 10 == 0 || pair + 1 == count then
          println(
            s"pairs=${pair + 1}/$count W=$wins D=$draws L=$losses score=${(wins + draws * 0.5) / ((pair + 1) * 2)}"
          )
      val mean = pairScores.sum / count
      val se   = if count <= 1 then Double.NaN
      else math.sqrt(pairScores.map(s => math.pow(s - mean, 2)).sum / (count - 1) / count)
      val summary =
        s"{\"pairs\":$count,\"wins\":$wins,\"draws\":$draws,\"losses\":$losses,\"score\":$mean,\"ci95HalfWidth\":${1.959964 * se},\"candidate\":${timings(candidateTimes.toList)},\"aggressive\":${timings(baselineTimes.toList)}}"
      write(output.resolve("games-summary.json"), summary)
      println(summary)
    finally writer.close()

  private def latency(search: TacticianSearch, count: Int, seed: Long, repeats: Int, output: Path): Unit =
    val roots     = ListBuffer.empty[GameState]
    val collector = new SearchAlgorithm:
      def findBestMove(state: GameState): Option[ScoredSequence] = findBestMove(state, new Random(0))
      override def findBestMove(state: GameState, random: Random): Option[ScoredSequence] =
        if roots.size < count then roots += state
        AggressiveSearch.findBestMove(state, random)
    var game = 0
    while roots.size < count do
      val _ = BotMatchRunner.simulateGame(collector, collector, new Random(seed + game), new Random(seed + game + 1))
      game += 1
    val corpus = roots.toList
    write(output.resolve("positions.dfen"), corpus.map(FenParser.serialize).mkString("\n") + "\n")
    for state <- corpus do
      val _ = search.findBestMove(state, new Random(0))
      val _ = AggressiveSearch.findBestMove(state, new Random(0))
    val writer         = Files.newBufferedWriter(output.resolve("latency.csv"), StandardOpenOption.CREATE_NEW)
    val candidateTimes = ListBuffer.empty[Long]
    val baselineTimes  = ListBuffer.empty[Long]
    try
      writer.write("repeat,position,algorithm,nanoseconds\n")
      for repeat <- 0 until repeats; (state, index) <- corpus.zipWithIndex do
        val methods = List(("tactician", search), ("aggressive", AggressiveSearch))
        val ordered = if (index + repeat) % 2 == 0 then methods else methods.reverse
        for (name, algorithm) <- ordered do
          val started = System.nanoTime()
          val _       = algorithm.findBestMove(state, new Random(seed + index))
          val elapsed = System.nanoTime() - started
          if name == "tactician" then candidateTimes += elapsed else baselineTimes += elapsed
          writer.write(s"$repeat,$index,$name,$elapsed\n")
      val summary =
        s"{\"positions\":$count,\"repeats\":$repeats,\"candidate\":${timings(candidateTimes.toList)},\"aggressive\":${timings(baselineTimes.toList)}}"
      write(output.resolve("latency-summary.json"), summary)
      println(summary)
    finally writer.close()

  private def timings(values: List[Long]): String =
    val sorted                        = values.sorted
    def percentile(p: Double): Double = sorted(math.max(0, math.ceil(p * sorted.size).toInt - 1)).toDouble / 1000000
    s"{\"moves\":${sorted.size},\"meanMs\":${sorted.map(_.toDouble).sum / sorted.size / 1000000},\"p50Ms\":${percentile(0.5)},\"p95Ms\":${percentile(0.95)},\"p99Ms\":${percentile(0.99)},\"maxMs\":${sorted.last.toDouble / 1000000}}"

  private def write(path: Path, text: String): Unit =
    val _ = Files.writeString(path, text + "\n", StandardOpenOption.CREATE_NEW)
