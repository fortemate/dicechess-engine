package dicechess.engine.bench

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.{DigestInputStream, MessageDigest}
import java.util.HexFormat
import java.util.zip.GZIPInputStream

import scala.collection.mutable.ArrayBuffer
import scala.io.Source
import scala.util.{Random, Using}

import cats.implicits.*
import com.monovore.decline.*

import dicechess.engine.domain.*
import dicechess.engine.search.{OnnxEvalSearch, PieceSafety}

/** Compares two ONNX leaf models on counterfactual queen-safety pairs sampled from a private corpus.
  *
  * A pair starts from a real post-turn position where the mover's queen is en prise under [[PieceSafety]]'s shared
  * attacked-and-undefended definition. The counterfactual moves only that queen one file towards `a`, requires the
  * destination to be empty, and keeps the pair only when the queen is no longer hanging there. Material is therefore
  * identical; any preference comes from positional features rather than a changed piece count.
  *
  * The whole gzip is scanned so an ordered corpus cannot bias the sample. A seeded reservoir holds at most `--pairs`
  * pairs, inference is batched, and neither positions nor game/player identifiers are written to stdout or JSON. Only
  * aggregate counts and score deltas leave the process.
  *
  * Usage:
  * {{{
  * sbt 'arena/runMain dicechess.engine.bench.OnnxQueenSafetyProbeMain challenger.onnx defender.onnx \
  *   corpus.csv.gz --pairs 100000 --features rich --seed 42 --json report.json'
  * }}}
  */
object OnnxQueenSafetyProbeMain:

  private val challengerModelOpt = Opts.argument[String](metavar = "challenger.onnx")
  private val defenderModelOpt   = Opts.argument[String](metavar = "defender.onnx")

  private val pairCountOpt: Opts[Int] =
    Opts
      .option[Int]("pairs", help = "Maximum number of queen-safety pairs to retain (default: 100000)")
      .withDefault(100000)
      .validate("pairs must be > 0")(_ > 0)

  def main(args: Array[String]): Unit =
    ArenaOptions.runCommand(command, args)

  private[bench] val command: Command[Unit] = Command(
    name = "OnnxQueenSafetyProbeMain",
    header = "Dice Chess ONNX queen-safety pair probe"
  ) {
    import ArenaOptions.*
    (
      challengerModelOpt,
      defenderModelOpt,
      corpusPathOpt,
      pairCountOpt,
      featuresOpt("rich"),
      seedOpt(),
      jsonPathOpt
    ).mapN(QueenSafetyProbeConfig.apply).map(run)
  }

  private def run(config: QueenSafetyProbeConfig): Unit =
    val sample = QueenSafetyProbe.sample(config.corpusPath, config.pairs, config.seed)
    if sample.pairs.isEmpty then sys.error("the corpus produced no eligible queen-safety pairs")

    println(
      s"Queen-safety probe: ${sample.pairs.size} sampled / ${sample.stats.eligiblePairs} eligible pairs " +
        s"from ${sample.stats.scannedRows} rows (features=${config.featureSet}, seed=${config.seed})"
    )

    val extract    = ArenaOptions.extractFeatures(config.featureSet)
    val challenger = QueenSafetyProbe.evaluate(config.challengerModel, extract, sample.pairs)
    val defender   = QueenSafetyProbe.evaluate(config.defenderModel, extract, sample.pairs)

    printSummary("challenger", challenger)
    printSummary("defender", defender)

    config.jsonPath.foreach { path =>
      BotMatchRunner.writeJsonReport(path, reportJson(config, sample.stats, challenger, defender))
    }

  private def printSummary(name: String, summary: QueenSafetyScoreSummary): Unit =
    println(
      f"$name%-10s safe preferred ${summary.safePreferred}%d/${summary.pairs}%d " +
        f"(${100.0 * summary.safePreferred / summary.pairs}%.2f%%), " +
        f"hanging preferred ${summary.hangingPreferred}%d, ties ${summary.ties}%d, " +
        f"mean safe-minus-hanging ${summary.meanDelta}%.2f"
    )

  private def reportJson(
      config: QueenSafetyProbeConfig,
      stats: QueenSafetySampleStats,
      challenger: QueenSafetyScoreSummary,
      defender: QueenSafetyScoreSummary
  ): Json =
    Json.obj(
      "kind"          -> Json.str("onnx_queen_safety_probe"),
      "schemaVersion" -> Json.int(1),
      "setup"         -> Json.obj(
        "pairDefinition" -> Json.str(
          "mover queen attacked and undefended; counterfactual shifts it one file towards a onto an empty square " +
            "and must remove the hang"
        ),
        "challengerModel"  -> Json.str(config.challengerModel),
        "challengerSha256" -> Json.str(QueenSafetyProbe.sha256(Path.of(config.challengerModel))),
        "defenderModel"    -> Json.str(config.defenderModel),
        "defenderSha256"   -> Json.str(QueenSafetyProbe.sha256(Path.of(config.defenderModel))),
        "featureSet"       -> Json.str(config.featureSet),
        "requestedPairs"   -> Json.int(config.pairs),
        "seed"             -> Json.int(config.seed)
      ),
      "sample" -> Json.obj(
        "corpusBytes"         -> Json.int(stats.corpusBytes),
        "corpusSha256"        -> Json.str(stats.corpusSha256),
        "scannedRows"         -> Json.int(stats.scannedRows),
        "parseableRows"       -> Json.int(stats.parseableRows),
        "hangingQueenRows"    -> Json.int(stats.hangingQueenRows),
        "eligiblePairs"       -> Json.int(stats.eligiblePairs),
        "retainedPairs"       -> Json.int(stats.retainedPairs),
        "rawPositionsEmitted" -> Json.bool(false)
      ),
      "challenger"                            -> scoreJson(challenger),
      "defender"                              -> scoreJson(defender),
      "challengerSafePreferenceMinusDefender" -> Json.num(
        challenger.safePreferenceRate - defender.safePreferenceRate
      )
    )

  private def scoreJson(summary: QueenSafetyScoreSummary): Json =
    Json.obj(
      "pairs"              -> Json.int(summary.pairs),
      "safePreferred"      -> Json.int(summary.safePreferred),
      "hangingPreferred"   -> Json.int(summary.hangingPreferred),
      "ties"               -> Json.int(summary.ties),
      "safePreferenceRate" -> Json.num(summary.safePreferenceRate),
      "meanDelta"          -> Json.num(summary.meanDelta),
      "deltaP10"           -> Json.int(summary.deltaP10),
      "deltaP50"           -> Json.int(summary.deltaP50),
      "deltaP90"           -> Json.int(summary.deltaP90)
    )

final case class QueenSafetyProbeConfig(
    challengerModel: String,
    defenderModel: String,
    corpusPath: String,
    pairs: Int,
    featureSet: String,
    seed: Long,
    jsonPath: Option[String]
)

final private[bench] case class QueenSafetyPair(hanging: GameState, safe: GameState, mover: Color)

final private[bench] case class QueenSafetySampleStats(
    corpusBytes: Long,
    corpusSha256: String,
    scannedRows: Long,
    parseableRows: Long,
    hangingQueenRows: Long,
    eligiblePairs: Long,
    retainedPairs: Int
)

final private[bench] case class QueenSafetySample(
    pairs: Vector[QueenSafetyPair],
    stats: QueenSafetySampleStats
)

final private[bench] case class QueenSafetyScoreSummary(
    pairs: Int,
    safePreferred: Int,
    hangingPreferred: Int,
    ties: Int,
    meanDelta: Double,
    deltaP10: Int,
    deltaP50: Int,
    deltaP90: Int
):
  def safePreferenceRate: Double = safePreferred.toDouble / pairs

private[bench] object QueenSafetyProbe:

  private val BatchSize = 4096

  def sample(corpusPath: String, wanted: Int, seed: Long): QueenSafetySample =
    val path = Path.of(corpusPath)
    if !Files.exists(path) then sys.error(s"corpus not found: $corpusPath")

    val reservoir        = ArrayBuffer.empty[QueenSafetyPair]
    val random           = new Random(seed)
    var scannedRows      = 0L
    var parseableRows    = 0L
    var hangingQueenRows = 0L
    var eligiblePairs    = 0L
    val digest           = MessageDigest.getInstance("SHA-256")

    Using.resource(
      Source.fromInputStream(
        new GZIPInputStream(new DigestInputStream(Files.newInputStream(path), digest)),
        StandardCharsets.UTF_8.name
      )
    ) { source =>
      val lines = source.getLines()
      if !lines.hasNext then sys.error("corpus is empty")
      val header = lines.next().split(",", -1).toVector
      val fenAt  = header.indexOf("fen")
      val sideAt = header.indexOf("side")
      if fenAt < 0 || sideAt < 0 then sys.error(s"corpus must have 'fen' and 'side' columns, got: $header")

      lines.foreach { line =>
        scannedRows += 1
        val cells  = line.split(",", -1)
        val parsed = for
          fen   <- cells.lift(fenAt)
          side  <- cells.lift(sideAt)
          mover <- parseColor(side)
          state <- FenParser.parse(fen).toOption
        yield (state, mover)

        parsed.foreach { case (state, mover) =>
          parseableRows += 1
          val pairs = pairsFor(state, mover)
          if pairs.nonEmpty then hangingQueenRows += 1
          pairs.foreach { pair =>
            eligiblePairs += 1
            if reservoir.size < wanted then reservoir += pair
            else
              val replacement = random.nextLong(eligiblePairs)
              if replacement < wanted then reservoir(replacement.toInt) = pair
          }
        }
      }
    }

    QueenSafetySample(
      reservoir.toVector,
      QueenSafetySampleStats(
        Files.size(path),
        HexFormat.of().formatHex(digest.digest()),
        scannedRows,
        parseableRows,
        hangingQueenRows,
        eligiblePairs,
        reservoir.size
      )
    )

  private[bench] def pairsFor(state: GameState, mover: Color): List[QueenSafetyPair] =
    val own           = if mover.isWhite then state.whitePieces else state.blackPieces
    val hangingQueens = PieceSafety.hangingSquares(state, mover) & state.queens & own
    val pairs         = List.newBuilder[QueenSafetyPair]
    var remaining     = hangingQueens.value
    while remaining != 0L do
      val from = Square.fromIndex(java.lang.Long.numberOfTrailingZeros(remaining))
      shiftTowardsA(state, mover, from).foreach(safe => pairs += QueenSafetyPair(state, safe, mover))
      remaining &= remaining - 1
    pairs.result()

  private[bench] def shiftTowardsA(state: GameState, mover: Color, from: Square): Option[GameState] =
    Option
      .when(from.file > 'a') {
        val to = Square.fromIndex(from.index - 1)
        Option
          .when(state.mailbox(to).isEmpty) {
            val mailbox = state.mailbox.toArray
            mailbox(from.index) = Piece.Empty
            mailbox(to.index) = Piece(mover, PieceType.Queen)
            val own = if mover.isWhite then state.whitePieces else state.blackPieces
            state.copy(
              whitePieces = if mover.isWhite then own.remove(from).add(to) else state.whitePieces,
              blackPieces = if mover.isBlack then own.remove(from).add(to) else state.blackPieces,
              queens = state.queens.remove(from).add(to),
              mailbox = Mailbox.fromBuilder(mailbox),
              zobristKey = 0L
            )
          }
      }
      .flatten
      .filter { shifted =>
        val to = Square.fromIndex(from.index - 1)
        !(PieceSafety.hangingSquares(shifted, mover) & Bitboard.fromSquare(to)).contains(to)
      }

  def evaluate(
      modelPath: String,
      extract: (GameState, Color) => Array[Float],
      pairs: Vector[QueenSafetyPair]
  ): QueenSafetyScoreSummary =
    val deltas = ArrayBuffer.empty[Int]
    Using.resource(new OnnxEvalSearch(modelPath, extract)) { model =>
      pairs.grouped(BatchSize).foreach { batch =>
        evaluateSide(model, batch.filter(_.mover.isWhite), Color.White, deltas)
        evaluateSide(model, batch.filter(_.mover.isBlack), Color.Black, deltas)
      }
    }
    summarize(deltas.toArray)

  private def evaluateSide(
      model: OnnxEvalSearch,
      pairs: Seq[QueenSafetyPair],
      mover: Color,
      deltas: ArrayBuffer[Int]
  ): Unit =
    if pairs.nonEmpty then
      val hanging = model.onnxEvalBatch(pairs.map(_.hanging).toArray, mover)
      val safe    = model.onnxEvalBatch(pairs.map(_.safe).toArray, mover)
      appendDeltas(safe, hanging, pairs.length, deltas)

  private[bench] def appendDeltas(
      safe: Array[Int],
      hanging: Array[Int],
      expected: Int,
      deltas: ArrayBuffer[Int]
  ): Unit =
    if safe.length != expected || hanging.length != expected then
      sys.error(
        s"ONNX batch size mismatch: expected $expected, safe=${safe.length}, hanging=${hanging.length}"
      )
    safe.lazyZip(hanging).foreach((safeScore, hangingScore) => deltas += safeScore - hangingScore)

  private[bench] def summarize(deltas: Array[Int]): QueenSafetyScoreSummary =
    require(deltas.nonEmpty, "at least one score delta is required")
    val sorted                        = deltas.sorted
    def percentile(percent: Int): Int = sorted(((sorted.length - 1) * percent) / 100)
    QueenSafetyScoreSummary(
      pairs = deltas.length,
      safePreferred = deltas.count(_ > 0),
      hangingPreferred = deltas.count(_ < 0),
      ties = deltas.count(_ == 0),
      meanDelta = deltas.iterator.map(_.toLong).sum.toDouble / deltas.length,
      deltaP10 = percentile(10),
      deltaP50 = percentile(50),
      deltaP90 = percentile(90)
    )

  private[bench] def sha256(path: Path): String =
    val digest = MessageDigest.getInstance("SHA-256")
    Using.resource(new DigestInputStream(Files.newInputStream(path), digest)) { input =>
      input.transferTo(java.io.OutputStream.nullOutputStream())
    }
    HexFormat.of().formatHex(digest.digest())

  private def parseColor(side: String): Option[Color] = side.toLowerCase match
    case "w" => Some(Color.White)
    case "b" => Some(Color.Black)
    case _   => None
