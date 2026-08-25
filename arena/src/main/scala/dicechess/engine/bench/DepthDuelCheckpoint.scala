package dicechess.engine.bench

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{AtomicMoveNotSupportedException, Files, Path, StandardCopyOption}
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import scala.util.Try
import scala.util.Using

import dicechess.engine.domain.Color

final private[bench] case class DepthDuelCheckpointIdentity(
    modelPath: String,
    modelSha256: String,
    featureSet: String,
    candidateLimit: Int,
    challengerDepth: Int,
    defenderDepth: Int,
    seed: Long,
    timePolicyId: String,
    timeControl: TimeControl,
    sprtConfig: Option[SprtConfig]
)

/** Durable checkpoint codec for multi-day depth duels.
  *
  * The file is replaced atomically after every completed mirrored pair. It stores raw per-move latency samples rather
  * than only percentiles, so a resumed result is statistically identical to one uninterrupted process. Setup metadata
  * is validated before reuse: a checkpoint can never silently mix models, controls, seeds, or SPRT hypotheses.
  */
private[bench] object DepthDuelCheckpoint:
  private val SchemaVersion = 1

  def sha256(path: String): Either[String, String] =
    Try {
      val digest = MessageDigest.getInstance("SHA-256")
      Using.resource(Files.newInputStream(Path.of(path))) { input =>
        val buffer = new Array[Byte](8192)
        var read   = input.read(buffer)
        while read >= 0 do
          if read > 0 then digest.update(buffer, 0, read)
          read = input.read(buffer)
      }
      digest.digest().map(byte => f"${byte & 0xff}%02x").mkString
    }.toEither.left.map(error => s"failed to hash model '$path': ${error.getMessage}")

  def load(path: String, expected: DepthDuelCheckpointIdentity, gamesCap: Int): Either[String, TimedMatchResume] =
    val checkpointPath = Path.of(path)
    if !Files.exists(checkpointPath) then Right(TimedMatchResume.empty)
    else
      Try(Files.readString(checkpointPath)).toEither.left
        .map(error => s"failed to read checkpoint '$path': ${error.getMessage}")
        .flatMap(Json.parse)
        .flatMap(parse(_, expected, gamesCap))

  def save(
      path: String,
      identity: DepthDuelCheckpointIdentity,
      gamesCap: Int,
      resume: TimedMatchResume
  ): Either[String, Unit] =
    Try(writeAtomically(Path.of(path), Json.render(toJson(identity, gamesCap, resume)))).toEither.left.map { error =>
      s"failed to write checkpoint '$path': ${error.getMessage}"
    }

  private def writeAtomically(targetInput: Path, contents: String): Unit =
    val target = targetInput.toAbsolutePath.normalize
    val parent = Option(target.getParent).getOrElse(Path.of(".").toAbsolutePath.normalize)
    Files.createDirectories(parent)
    val temp = Files.createTempFile(parent, s".${target.getFileName.toString}.", ".tmp")
    try
      val bytes = ByteBuffer.wrap(contents.getBytes(StandardCharsets.UTF_8))
      Using.resource(FileChannel.open(temp, StandardOpenOption.WRITE)) { channel =>
        while bytes.hasRemaining do channel.write(bytes)
        channel.force(true)
      }
      try Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      catch
        case _: AtomicMoveNotSupportedException =>
          Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
    finally Files.deleteIfExists(temp)

  private def toJson(
      identity: DepthDuelCheckpointIdentity,
      gamesCap: Int,
      resume: TimedMatchResume
  ): Json =
    Json.obj(
      "kind"              -> Json.str("depth_duel_checkpoint"),
      "schemaVersion"     -> Json.int(SchemaVersion),
      "setup"             -> identityJson(identity),
      "gamesPerColorCap"  -> Json.int(gamesCap),
      "completedPairs"    -> Json.int(resume.observations.size),
      "durationMs"        -> Json.int(resume.durationMs),
      "pairObservations"  -> Json.arr(resume.observations.map(observationJson)*),
      "latencySampleUnit" -> Json.str("milliseconds")
    )

  private def identityJson(identity: DepthDuelCheckpointIdentity): Json =
    Json.obj(
      "modelPath"       -> Json.str(identity.modelPath),
      "modelSha256"     -> Json.str(identity.modelSha256),
      "features"        -> Json.str(identity.featureSet),
      "candidateLimit"  -> Json.int(identity.candidateLimit),
      "challengerDepth" -> Json.int(identity.challengerDepth),
      "defenderDepth"   -> Json.int(identity.defenderDepth),
      "seed"            -> Json.int(identity.seed),
      "timePolicy"      -> Json.str(identity.timePolicyId),
      "timeControl"     -> Json.obj(
        "initialMs"   -> Json.int(identity.timeControl.initialMs),
        "incrementMs" -> Json.int(identity.timeControl.incrementMs)
      ),
      "sprt" -> identity.sprtConfig.map(sprtJson).getOrElse(Json.JNull)
    )

  private def sprtJson(config: SprtConfig): Json =
    Json.obj(
      "elo0"  -> Json.num(config.elo0),
      "elo1"  -> Json.num(config.elo1),
      "alpha" -> Json.num(config.alpha),
      "beta"  -> Json.num(config.beta)
    )

  private def observationJson(observation: PairObservation): Json =
    Json.obj(
      "index"       -> Json.int(observation.index),
      "bin"         -> Json.int(observation.bin),
      "whiteScore"  -> Json.num(observation.whiteScore),
      "blackScore"  -> Json.num(observation.blackScore),
      "whiteResult" -> gameResultJson(observation.whiteGame),
      "blackResult" -> gameResultJson(observation.blackGame)
    )

  private def gameResultJson(result: TimedGameResult): Json =
    Json.obj(
      "outcome" -> Json.str(result.outcome match
        case GameOutcome.Draw       => "draw"
        case GameOutcome.Win(color) => if color.isWhite then "white-win" else "black-win"),
      "flaggedColor" -> result.flaggedColor.map(colorJson).getOrElse(Json.JNull),
      "latencies"    -> Json.arr(result.latenciesByColorMs.map { case (color, milliseconds) =>
        Json.obj("color" -> colorJson(color), "milliseconds" -> Json.int(milliseconds))
      }*)
    )

  private def colorJson(color: Color): Json = Json.str(if color.isWhite then "white" else "black")

  private def parse(
      json: Json,
      expected: DepthDuelCheckpointIdentity,
      gamesCap: Int
  ): Either[String, TimedMatchResume] =
    for
      kind <- requiredString(json, "kind", "root")
      _    <- Either.cond(
        kind == "depth_duel_checkpoint",
        (),
        s"checkpoint kind is '$kind', expected depth_duel_checkpoint"
      )
      version   <- requiredLong(json, "schemaVersion", "root")
      _         <- Either.cond(version == SchemaVersion, (), s"unsupported checkpoint schemaVersion $version")
      setupJson <- required(json, "setup", "root")
      actual    <- parseIdentity(setupJson)
      _         <- Either.cond(
        Json.render(identityJson(actual)) == Json.render(identityJson(expected)),
        (),
        "checkpoint setup does not match the requested depth duel"
      )
      storedCap        <- requiredLong(json, "gamesPerColorCap", "root")
      _                <- Either.cond(storedCap > 0, (), "checkpoint gamesPerColorCap must be positive")
      completed        <- requiredLong(json, "completedPairs", "root")
      duration         <- requiredLong(json, "durationMs", "root")
      _                <- Either.cond(duration >= 0, (), "checkpoint durationMs must be non-negative")
      observationsJson <- required(json, "pairObservations", "root")
      observations     <- parseObservations(observationsJson)
      _                <- Either.cond(
        completed == observations.size,
        (),
        s"checkpoint completedPairs=$completed but contains ${observations.size} observations"
      )
      _ <- Either.cond(
        observations.size <= gamesCap,
        (),
        s"checkpoint has ${observations.size} pairs, above the requested cap of $gamesCap"
      )
    yield TimedMatchResume(observations.toVector, duration)

  private def parseIdentity(json: Json): Either[String, DepthDuelCheckpointIdentity] =
    for
      modelPath       <- requiredString(json, "modelPath", "setup")
      modelSha256     <- requiredString(json, "modelSha256", "setup")
      featureSet      <- requiredString(json, "features", "setup")
      candidateLimit  <- requiredInt(json, "candidateLimit", "setup")
      challengerDepth <- requiredInt(json, "challengerDepth", "setup")
      defenderDepth   <- requiredInt(json, "defenderDepth", "setup")
      seed            <- requiredLong(json, "seed", "setup")
      timePolicyId    <- requiredString(json, "timePolicy", "setup")
      timeControlJson <- required(json, "timeControl", "setup")
      initialMs       <- requiredLong(timeControlJson, "initialMs", "setup.timeControl")
      incrementMs     <- requiredLong(timeControlJson, "incrementMs", "setup.timeControl")
      sprtJsonValue   <- required(json, "sprt", "setup")
      sprtConfig      <- parseSprt(sprtJsonValue)
    yield DepthDuelCheckpointIdentity(
      modelPath,
      modelSha256,
      featureSet,
      candidateLimit,
      challengerDepth,
      defenderDepth,
      seed,
      timePolicyId,
      TimeControl(initialMs, incrementMs),
      sprtConfig
    )

  private def parseSprt(json: Json): Either[String, Option[SprtConfig]] = json match
    case Json.JNull => Right(None)
    case _          =>
      for
        elo0  <- requiredDouble(json, "elo0", "setup.sprt")
        elo1  <- requiredDouble(json, "elo1", "setup.sprt")
        alpha <- requiredDouble(json, "alpha", "setup.sprt")
        beta  <- requiredDouble(json, "beta", "setup.sprt")
      yield Some(SprtConfig(elo0, elo1, alpha, beta))

  private def parseObservations(json: Json): Either[String, List[PairObservation]] = json match
    case Json.JArr(items) =>
      items.zipWithIndex.foldLeft[Either[String, List[PairObservation]]](Right(Nil)) { case (acc, (item, index)) =>
        for
          parsed <- acc
          next   <- parseObservation(item, index)
        yield parsed :+ next
      }
    case _ => Left("root.pairObservations must be an array")

  private def parseObservation(json: Json, position: Int): Either[String, PairObservation] =
    val context = s"pairObservations[$position]"
    for
      index       <- requiredInt(json, "index", context)
      _           <- Either.cond(index == position, (), s"$context.index is $index, expected $position")
      bin         <- requiredInt(json, "bin", context)
      _           <- Either.cond(bin >= 0 && bin <= 4, (), s"$context.bin must be in 0..4")
      whiteScore  <- requiredDouble(json, "whiteScore", context)
      blackScore  <- requiredDouble(json, "blackScore", context)
      whiteJson   <- required(json, "whiteResult", context)
      whiteResult <- parseGameResult(whiteJson, s"$context.whiteResult")
      blackJson   <- required(json, "blackResult", context)
      blackResult <- parseGameResult(blackJson, s"$context.blackResult")
      expectedWhite = score(whiteResult, Color.White)
      expectedBlack = score(blackResult, Color.Black)
      expectedBin   = math.round((expectedWhite + expectedBlack) * 2).toInt
      _ <- Either.cond(
        whiteScore == expectedWhite && blackScore == expectedBlack && bin == expectedBin,
        (),
        s"$context scores/bin do not match its game outcomes"
      )
    yield PairObservation(index, bin, whiteScore, blackScore, whiteResult, blackResult)

  private def parseGameResult(json: Json, context: String): Either[String, TimedGameResult] =
    for
      outcomeName <- requiredString(json, "outcome", context)
      outcome     <- outcomeName match
        case "draw"      => Right(GameOutcome.Draw)
        case "white-win" => Right(GameOutcome.Win(Color.White))
        case "black-win" => Right(GameOutcome.Win(Color.Black))
        case other       => Left(s"$context.outcome has unknown value '$other'")
      flaggedJson <- required(json, "flaggedColor", context)
      flagged     <- parseOptionalColor(flaggedJson, s"$context.flaggedColor")
      _           <- Either.cond(
        flagged.forall(color => outcome == GameOutcome.Win(color.opponent)),
        (),
        s"$context.flaggedColor does not match the losing side"
      )
      latenciesJson <- required(json, "latencies", context)
      latencies     <- parseLatencies(latenciesJson, s"$context.latencies")
    yield TimedGameResult(outcome, flagged, latencies)

  private def parseLatencies(json: Json, context: String): Either[String, List[(Color, Long)]] = json match
    case Json.JArr(items) =>
      items.zipWithIndex.foldLeft[Either[String, List[(Color, Long)]]](Right(Nil)) { case (acc, (item, index)) =>
        for
          parsed       <- acc
          colorName    <- requiredString(item, "color", s"$context[$index]")
          color        <- parseColor(colorName, s"$context[$index].color")
          milliseconds <- requiredLong(item, "milliseconds", s"$context[$index]")
          _            <- Either.cond(milliseconds >= 0, (), s"$context[$index].milliseconds must be non-negative")
        yield parsed :+ (color -> milliseconds)
      }
    case _ => Left(s"$context must be an array")

  private def parseOptionalColor(json: Json, context: String): Either[String, Option[Color]] = json match
    case Json.JNull       => Right(None)
    case Json.JStr(value) => parseColor(value, context).map(Some(_))
    case _                => Left(s"$context must be a color string or null")

  private def parseColor(value: String, context: String): Either[String, Color] = value match
    case "white" => Right(Color.White)
    case "black" => Right(Color.Black)
    case other   => Left(s"$context has unknown color '$other'")

  private def score(result: TimedGameResult, botColor: Color): Double = result.outcome match
    case GameOutcome.Draw                            => 0.5
    case GameOutcome.Win(color) if color == botColor => 1.0
    case GameOutcome.Win(_)                          => 0.0

  private def required(json: Json, name: String, context: String): Either[String, Json] =
    json.field(name).toRight(s"$context.$name is required")

  private def requiredString(json: Json, name: String, context: String): Either[String, String] =
    required(json, name, context).flatMap(_.asStr.toRight(s"$context.$name must be a string"))

  private def requiredLong(json: Json, name: String, context: String): Either[String, Long] =
    required(json, name, context).flatMap {
      case Json.JInt(value) => Right(value)
      case _                => Left(s"$context.$name must be an integer")
    }

  private def requiredInt(json: Json, name: String, context: String): Either[String, Int] =
    requiredLong(json, name, context).flatMap(value =>
      Either.cond(value.isValidInt, value.toInt, s"$context.$name is outside the Int range")
    )

  private def requiredDouble(json: Json, name: String, context: String): Either[String, Double] =
    required(json, name, context).flatMap(_.asNum.toRight(s"$context.$name must be numeric"))
