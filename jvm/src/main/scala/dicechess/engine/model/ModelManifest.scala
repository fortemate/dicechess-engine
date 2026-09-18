// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.model

import dicechess.engine.json.Json

import java.io.InputStream
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.util.{Try, Using}

/** Calibration metadata of a probability-valued model. Advisory — the engine records it and never applies it, because
  * scaling a score the search only ever compares against other scores of the same model changes nothing.
  *
  * Field names and the `temperature` default mirror the deployed evaluation service's calibration metadata, so one
  * artifact's manifest is readable by both without a translation step.
  */
final case class ModelCalibration(
    temperature: Double = 1.0,
    brierScore: Option[Double] = None,
    logLoss: Option[Double] = None,
    calibratedOn: Option[String] = None
)

/** The versioned description of one trained ONNX artifact: what it is for, what it consumes, what it emits, which
  * engines may serve it, and which bytes it describes.
  *
  * Every field is here because its absence has a silent failure mode. A model in the wrong [[ModelRole]] answers a
  * different question; a model on the wrong `featureSchema` receives a vector in which every column means something
  * else; a model whose tensors are named or shaped differently either fails deep inside a JNI call or — worse — is fed
  * by index and scored anyway; a model served by an engine whose feature extractors have moved on is scored on
  * out-of-distribution inputs; and a model whose bytes no longer match the manifest is simply a different model. None
  * of those show up as a crash. They show up as a bot that plays worse than it measured, which is the most expensive
  * defect this project can ship.
  *
  * Validation is therefore fail-closed and happens before a session is created ([[ModelManifest.validate]],
  * [[ModelManifest.verifyDigest]]) and before any row reaches the model ([[OnnxModelContract.validate]]).
  *
  * ## Versions
  *
  *   - `1.0.0` — the manifest the evaluation service and the training repository's `kcp-13` serving contract already
  *     produce. It has no role, perspective or tensor-name fields; this engine reads such a manifest as
  *     [[ModelRole.PositionValue]] on the mover-perspective convention with the contract's `input`/`output` tensor
  *     names, which is exactly what those artifacts are.
  *   - `1.1.0` — adds `modelRole` and `perspective` as required fields and `inputName`/`outputName` as optional ones. A
  *     model for the search's chance-collapse or pre-ranking hooks can only be described by this version, since `1.0.0`
  *     cannot say which of them it is.
  *
  * Unknown fields are ignored on purpose: a newer producer may add metadata this engine has no use for, and refusing to
  * load over it would make every manifest addition a breaking change. A field a *declared* version does not define is
  * the opposite case and is refused: a `1.0.0` manifest carrying `modelRole` would mean one thing here and another to
  * every reader that only knows `1.0.0`.
  *
  * @param modelSha256
  *   64 hex characters over the ONNX file's bytes — the manifest describes exactly one file and can be pinned in a
  *   preregistered experiment record
  * @param engineCompatibility
  *   space-separated `MAJOR.MINOR.PATCH` comparators (`=`, `>`, `>=`, `<`, `<=`), AND semantics, a bare version meaning
  *   equality — e.g. `>=0.12.0 <1.0.0`. The syntax is the deployed evaluator's, deliberately not re-invented
  * @param perspective
  *   whose point of view the feature row and the output take; see [[ModelManifest.MoverPerspective]]
  * @param provenance
  *   free-form producer metadata (training run, dataset digest, commit); never interpreted here
  */
final case class ModelManifest(
    manifestVersion: String,
    modelId: String,
    modelSha256: String,
    modelRole: String,
    featureSchema: String,
    featureCount: Int,
    inputName: String,
    outputName: String,
    perspective: String,
    engineCompatibility: String,
    evaluationProfile: Option[String] = None,
    calibration: ModelCalibration = ModelCalibration(),
    provenance: Map[String, String] = Map.empty
)

object ModelManifest:

  /** The manifest version this engine writes about in documentation and expects from a new artifact. */
  val CurrentVersion = "1.1.0"

  /** The version the evaluation service and the `kcp-13` serving contract emit, accepted in the
    * [[ModelRole.PositionValue]] role with this object's defaults filled in.
    */
  val LegacyVersion = "1.0.0"

  val SupportedVersions: Set[String] = Set(LegacyVersion, CurrentVersion)

  val DefaultInputName  = "input"
  val DefaultOutputName = "output"

  /** The only perspective this engine can consume: the feature row and the score are both stated for the color being
    * evaluated (own minus opponent), independent of which side happens to be to move in the position.
    *
    * Every extractor in [[FeatureSchema]] takes that color as an argument, and the search always passes the color it
    * wants the score for — so the engine never performs the `1 - p` flip the evaluation service's turn analysis needs
    * (it extracts for `state.activeColor` instead). Same vectors, same convention, one fewer transformation.
    */
  val MoverPerspective = "side-to-move"

  val SupportedPerspectives: Set[String] = Set(MoverPerspective)

  private val Sha256Pattern = "(?i)^[0-9a-f]{64}$".r

  /** Digest buffer: large enough that a hundred-megabyte network costs a few thousand reads, small enough to never
    * matter — the file is streamed rather than read into memory, which a 768-input network makes worth doing.
    */
  private val DigestBufferSize = 64 * 1024

  /** Parses manifest JSON, applying the per-version defaults described in [[ModelManifest]]. Structural only: the
    * values are checked by [[validate]], which a caller that assembled a manifest by hand still owes.
    */
  def parse(text: String): Either[String, ModelManifest] =
    for
      parsed  <- Json.parse(text).left.map(error => s"not valid JSON: $error")
      json    <- asObject(parsed)
      version <- ManifestFields.string(json, "manifestVersion")
      _       <- ensure(SupportedVersions.contains(version), unsupportedVersion(version))
      modelId <- ManifestFields.string(json, "modelId")
      sha256  <- ManifestFields.string(json, "modelSha256")
      role    <- versioned(json, "modelRole", version, ModelRole.PositionValue.id)
      schema  <- ManifestFields.string(json, "featureSchema")
      count   <- ManifestFields.int(json, "featureCount")
      input   <- ManifestFields.optionalString(json, "inputName")
      output  <- ManifestFields.optionalString(json, "outputName")
      view    <- versioned(json, "perspective", version, MoverPerspective)
      engines <- ManifestFields.string(json, "engineCompatibility")
      profile <- ManifestFields.optionalString(json, "evaluationProfile")
      calibr  <- ManifestFields.calibration(json)
      source  <- ManifestFields.stringMap(json, "provenance")
    yield ModelManifest(
      manifestVersion = version,
      modelId = modelId,
      modelSha256 = sha256,
      modelRole = role,
      featureSchema = schema,
      featureCount = count,
      inputName = input.getOrElse(DefaultInputName),
      outputName = output.getOrElse(DefaultOutputName),
      perspective = view,
      engineCompatibility = engines,
      evaluationProfile = profile,
      calibration = calibr,
      provenance = source
    )

  /** Reads and parses the manifest at `path`, tagging every message with the file it came from. */
  def load(path: Path): Either[String, ModelManifest] =
    val content =
      if !Files.isReadable(path) then Left(s"manifest file not readable")
      else Try(Files.readString(path)).toEither.left.map(error => s"cannot be read: ${error.getMessage}")
    content.flatMap(parse).left.map(error => s"$path: $error")

  /** Checks every field whose violation would mis-serve the model, first failure wins.
    *
    * `engineVersion` is the *host's* engine version, stated by the host rather than discovered: a library cannot
    * reliably read its own version (there is no jar manifest on a test or sbt classpath), and guessing it would turn a
    * compatibility gate into a coin flip. It must be a release `MAJOR.MINOR.PATCH` — a host running a `-SNAPSHOT`
    * passes the release it derives from, matching the comparator grammar the training and evaluation sides already
    * implement.
    */
  def validate(manifest: ModelManifest, engineVersion: String): Either[String, Unit] =
    for
      _ <- ensure(SupportedVersions.contains(manifest.manifestVersion), unsupportedVersion(manifest.manifestVersion))
      _ <- ensure(manifest.modelId.trim.nonEmpty, "modelId must not be blank")
      _ <- ensure(Sha256Pattern.matches(manifest.modelSha256), "modelSha256 must be exactly 64 hexadecimal characters")
      _ <- ModelRole.find(manifest.modelRole)
      schema <- FeatureSchema.find(manifest.featureSchema)
      _      <- ensure(
        manifest.featureCount == schema.featureCount,
        s"featureCount ${manifest.featureCount} does not match featureSchema '${schema.id}' " +
          s"(${schema.featureCount} columns)"
      )
      _ <- ensure(manifest.inputName.trim.nonEmpty, "inputName must not be blank")
      _ <- ensure(manifest.outputName.trim.nonEmpty, "outputName must not be blank")
      _ <- ensure(
        SupportedPerspectives.contains(manifest.perspective),
        s"unsupported perspective '${manifest.perspective}'; supported: ${SupportedPerspectives.toList.sorted.mkString(", ")}"
      )
      _ <- EngineCompatibility.validate(manifest.engineCompatibility, engineVersion)
    yield ()

  /** The SHA-256 of the file at `path`, lowercase hex, streamed rather than buffered whole. */
  def computeSha256(path: Path): Either[String, String] =
    Using(Files.newInputStream(path))(digestOf).toEither.left
      .map(error => s"cannot digest $path: ${error.getMessage}")

  /** Proves the manifest describes these exact bytes. A mismatch means the pair is not a package — either the model was
    * replaced after the manifest was written or the two come from different runs — and nothing about the manifest's
    * other promises can be trusted.
    */
  def verifyDigest(modelPath: Path, manifest: ModelManifest): Either[String, Unit] =
    computeSha256(modelPath).flatMap: actual =>
      ensure(
        actual.equalsIgnoreCase(manifest.modelSha256),
        s"model SHA-256 mismatch: manifest ${manifest.modelSha256}, file $actual"
      )

  private def digestOf(stream: InputStream): String =
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = new Array[Byte](DigestBufferSize)
    var read   = stream.read(buffer)
    while read > 0 do
      digest.update(buffer, 0, read)
      read = stream.read(buffer)
    digest.digest().map(byte => f"${byte & 0xff}%02x").mkString

  /** A field that `1.1.0` requires and `1.0.0` never defined.
    *
    * Three cases, and the third is the interesting one. Present under `1.1.0` wins; absent under `1.0.0` falls back to
    * the legacy default, so a current manifest cannot omit it and be quietly assumed. Present under `1.0.0` is
    * **refused**: every `1.0.0`-only reader — the evaluation service today, the training repository's Python contract —
    * ignores a field its version does not define, so honouring it here would make one file mean `chance-collapse` to
    * this engine and `position-value` to everything else. A manifest that wants to state a role declares `1.1.0`.
    */
  private def versioned(json: Json, name: String, version: String, legacyDefault: String): Either[String, String] =
    ManifestFields
      .optionalString(json, name)
      .flatMap:
        case Some(_) if version == LegacyVersion =>
          Left(s"manifestVersion $version does not define field '$name'")
        case Some(value)                      => Right(value)
        case None if version == LegacyVersion => Right(legacyDefault)
        case None                             => Left(s"manifestVersion $version requires field '$name'")

  private def asObject(json: Json): Either[String, Json] = json match
    case Json.JObj(_) => Right(json)
    case _            => Left("manifest must be a JSON object")

  private def unsupportedVersion(version: String): String =
    s"unsupported manifestVersion '$version'; expected one of ${SupportedVersions.toList.sorted.mkString(", ")}"

  private def ensure(condition: Boolean, error: => String): Either[String, Unit] =
    Either.cond(condition, (), error)

/** Typed reads of the manifest's flat JSON shape. Every accessor names the field it failed on: an operator debugging a
  * rejected artifact needs to know which line of the file is wrong, not that "parsing failed".
  */
private object ManifestFields:

  def string(json: Json, name: String): Either[String, String] =
    optionalString(json, name).flatMap(_.toRight(s"missing required field '$name'"))

  def optionalString(json: Json, name: String): Either[String, Option[String]] =
    json.field(name) match
      case None | Some(Json.JNull) => Right(None)
      case Some(value)             => value.asStr.map(Some(_)).toRight(s"field '$name' must be a string")

  /** A whole number, written either way: a producer that serialises `13` as `13.0` is describing the same width, and
    * the Python side of this contract compares numerically too — disagreeing about the spelling would reject a valid
    * artifact over JSON formatting.
    */
  def int(json: Json, name: String): Either[String, Int] =
    json.field(name) match
      case None | Some(Json.JNull)                     => Left(s"missing required field '$name'")
      case Some(Json.JInt(value)) if value.isValidInt  => Right(value.toInt)
      case Some(Json.JNum(value)) if isWholeInt(value) => Right(value.toInt)
      case Some(_)                                     => Left(s"field '$name' must be a whole number")

  private def isWholeInt(value: Double): Boolean =
    value.isWhole && value >= Int.MinValue.toDouble && value <= Int.MaxValue.toDouble

  /** Producer metadata: values are strings in the contract, but a number or boolean is rendered rather than rejected —
    * free-form provenance must never be the reason a valid model cannot be loaded.
    */
  def stringMap(json: Json, name: String): Either[String, Map[String, String]] =
    json.field(name) match
      case None | Some(Json.JNull) => Right(Map.empty)
      case Some(Json.JObj(fields)) =>
        fields.foldLeft[Either[String, Map[String, String]]](Right(Map.empty)): (result, field) =>
          val (key, value) = field
          for
            collected <- result
            rendered  <- scalar(value).toRight(s"field '$name.$key' must be a string, number or boolean")
          yield collected + (key -> rendered)
      case Some(_) => Left(s"field '$name' must be an object")

  def calibration(json: Json): Either[String, ModelCalibration] =
    json.field("calibration") match
      case None | Some(Json.JNull) => Right(ModelCalibration())
      case Some(nested: Json.JObj) =>
        for
          temperature  <- optionalDouble(nested, "temperature")
          brier        <- optionalDouble(nested, "brierScore")
          logLoss      <- optionalDouble(nested, "logLoss")
          calibratedOn <- optionalString(nested, "calibratedOn")
        yield ModelCalibration(temperature.getOrElse(1.0), brier, logLoss, calibratedOn)
      case Some(_) => Left("field 'calibration' must be an object")

  private def optionalDouble(json: Json, name: String): Either[String, Option[Double]] =
    json.field(name) match
      case None | Some(Json.JNull) => Right(None)
      case Some(value)             => value.asNum.map(Some(_)).toRight(s"field '$name' must be a number")

  private def scalar(value: Json): Option[String] = value match
    case Json.JStr(text)   => Some(text)
    case Json.JInt(number) => Some(number.toString)
    case Json.JNum(number) => Some(number.toString)
    case Json.JBool(flag)  => Some(flag.toString)
    case _                 => None

/** The `engineCompatibility` comparator grammar, reimplemented here to match — not to extend — the one the evaluation
  * service enforces and the training repository mirrors in Python: space-separated `MAJOR.MINOR.PATCH` comparators with
  * AND semantics, a bare version meaning equality.
  *
  * Pre-release and build suffixes are rejected rather than tolerated. The three implementations of this grammar must
  * agree on every string, and "ignore everything after the dash" is precisely the kind of local convenience that makes
  * them disagree.
  */
private object EngineCompatibility:

  final private case class Version(major: Int, minor: Int, patch: Int)

  private val Operators = List(">=", "<=", ">", "<", "=")

  def validate(constraint: String, engineVersion: String): Either[String, Unit] =
    for
      current     <- parseVersion(engineVersion).left.map(error => s"invalid engine version: $error")
      comparators <- parseConstraint(constraint)
      _           <- Either.cond(
        comparators.forall((operator, required) => satisfied(current, operator, required)),
        (),
        s"engineCompatibility '$constraint' does not include engine version '$engineVersion'"
      )
    yield ()

  private def parseConstraint(constraint: String): Either[String, List[(String, Version)]] =
    val tokens = constraint.trim.split("\\s+").toList.filter(_.nonEmpty)
    if tokens.isEmpty then Left("engineCompatibility must not be blank")
    else
      tokens.foldLeft[Either[String, List[(String, Version)]]](Right(Nil)): (result, token) =>
        for
          parsed     <- result
          comparator <- parseComparator(constraint, token)
        yield parsed :+ comparator

  private def parseComparator(constraint: String, token: String): Either[String, (String, Version)] =
    val operator = Operators.find(token.startsWith).getOrElse("=")
    parseVersion(token.stripPrefix(operator))
      .map(operator -> _)
      .left
      .map(_ =>
        s"invalid engineCompatibility '$constraint'; expected space-separated comparators such as '>=0.12.0 <1.0.0'"
      )

  private def parseVersion(version: String): Either[String, Version] =
    version.split("\\.", -1).toList match
      case List(major, minor, patch) if List(major, minor, patch).forall(isAsciiDigits) =>
        (major.toIntOption, minor.toIntOption, patch.toIntOption) match
          case (Some(parsedMajor), Some(parsedMinor), Some(parsedPatch)) =>
            Right(Version(parsedMajor, parsedMinor, parsedPatch))
          case _ => Left(s"invalid semantic version '$version'; a component does not fit in an Int")
      case _ => Left(s"invalid semantic version '$version'; expected MAJOR.MINOR.PATCH")

  /** Whether a version component is a non-empty run of ASCII digits.
    *
    * Not `Char.isDigit`, which also accepts Arabic-Indic, Devanagari and every other Unicode decimal digit. The
    * evaluation service's Java regex `\d` accepts only ASCII, and a grammar whose three implementations answer
    * differently for any string is not the shared grammar this class claims to implement — so the narrower reading
    * wins. The range is checked by parsing rather than by capping the digit count, which is what the other
    * implementations do and what keeps a ten-digit component from being rejected here and accepted there.
    */
  private def isAsciiDigits(part: String): Boolean =
    part.nonEmpty && part.forall(character => character >= '0' && character <= '9')

  private def satisfied(current: Version, operator: String, required: Version): Boolean =
    val comparison =
      if current.major != required.major then Integer.compare(current.major, required.major)
      else if current.minor != required.minor then Integer.compare(current.minor, required.minor)
      else Integer.compare(current.patch, required.patch)
    operator match
      case ">=" => comparison >= 0
      case "<=" => comparison <= 0
      case ">"  => comparison > 0
      case "<"  => comparison < 0
      case _    => comparison == 0
