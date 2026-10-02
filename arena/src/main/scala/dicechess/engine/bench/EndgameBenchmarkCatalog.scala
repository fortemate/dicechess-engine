// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.{Try, Using}

import cats.syntax.all.*
import dicechess.engine.domain.{Color, FenParser, GameState, Square}
import dicechess.engine.json.Json

final case class EndgamePosition(
    id: String,
    category: String,
    name: String,
    description: String,
    fen: String,
    state: GameState,
    attackerColor: Color,
    defenderColor: Color,
    kingChebyshevDistance: Int,
    kingManhattanDistance: Int,
    theoreticalOutcome: String
)

final case class EndgameSuite(
    schemaVersion: Int,
    id: String,
    description: String,
    positions: List[EndgamePosition]
)

/** Parser and loader for standardized Dice Chess endgame benchmark suites. */
object EndgameBenchmarkCatalog:
  val SchemaVersion   = 1
  val DefaultResource = "endgame-benchmark/endgames-v1.json"

  def load(path: Option[String]): Either[String, EndgameSuite] =
    val input = path match
      case Some(value) =>
        Try(Files.readString(Path.of(value))).toEither.leftMap(e =>
          s"failed to read endgame suite file '$value': ${e.getMessage}"
        )
      case None =>
        Option(getClass.getClassLoader.getResourceAsStream(DefaultResource))
          .toRight(s"bundled endgame suite resource '$DefaultResource' was not found")
          .flatMap(stream =>
            Try(Using.resource(stream)(in => new String(in.readAllBytes(), StandardCharsets.UTF_8))).toEither
              .leftMap(e => s"failed to read bundled endgame suite resource '$DefaultResource': ${e.getMessage}")
          )
    input.flatMap(parse)

  def parse(input: String): Either[String, EndgameSuite] =
    for
      json    <- Json.parse(input).leftMap(error => s"invalid endgame suite JSON: $error")
      root    <- objectFields(json, "root", Set("schemaVersion", "id", "description", "positions"))
      version <- requiredInt(root, "schemaVersion", "root")
      _  <- Either.cond(version == SchemaVersion, (), s"unsupported schemaVersion $version (expected $SchemaVersion)")
      id <- requiredNonEmptyString(root, "id", "root")
      description <- requiredNonEmptyString(root, "description", "root")
      posJson     <- required(root, "positions", "root")
      positions   <- parsePositions(posJson)
    yield EndgameSuite(version, id, description, positions)

  private def parsePositions(json: Json): Either[String, List[EndgamePosition]] =
    for
      positions <- json match
        case Json.JArr(items) => items.zipWithIndex.traverse((item, idx) => parsePosition(item, idx))
        case _                => Left("root.positions must be an array")
      _ <- Either.cond(positions.nonEmpty, (), "root.positions must not be empty")
      ids = positions.map(_.id)
      _ <- Either.cond(ids.distinct.size == ids.size, (), "position ids must be unique")
    yield positions

  private def parsePosition(json: Json, index: Int): Either[String, EndgamePosition] =
    val ctx         = s"positions[$index]"
    val allowedKeys = Set(
      "id",
      "category",
      "name",
      "description",
      "fen",
      "attackerColor",
      "defenderColor",
      "kingChebyshevDistance",
      "kingManhattanDistance",
      "theoreticalOutcome"
    )
    for
      fields      <- objectFields(json, ctx, allowedKeys)
      id          <- requiredNonEmptyString(fields, "id", ctx)
      category    <- requiredNonEmptyString(fields, "category", ctx)
      name        <- requiredNonEmptyString(fields, "name", ctx)
      description <- requiredNonEmptyString(fields, "description", ctx)
      fen         <- requiredNonEmptyString(fields, "fen", ctx)
      state       <- FenParser.parse(fen).leftMap(err => s"$ctx.fen '$fen' is invalid: $err")
      _           <- Either.cond(!(state.kings & state.whitePieces).isEmpty, (), s"$ctx.fen missing white king")
      _           <- Either.cond(!(state.kings & state.blackPieces).isEmpty, (), s"$ctx.fen missing black king")
      attStr      <- requiredNonEmptyString(fields, "attackerColor", ctx)
      defStr      <- requiredNonEmptyString(fields, "defenderColor", ctx)
      attacker    <- parseColor(attStr, s"$ctx.attackerColor")
      defender    <- parseColor(defStr, s"$ctx.defenderColor")
      _           <- Either.cond(attacker != defender, (), s"$ctx: attackerColor and defenderColor must be opposite")
      chebyshev   <- requiredInt(fields, "kingChebyshevDistance", ctx)
      manhattan   <- requiredInt(fields, "kingManhattanDistance", ctx)
      outcome     <- requiredNonEmptyString(fields, "theoreticalOutcome", ctx)
      _           <- validateDistances(state, chebyshev, manhattan, ctx)
    yield EndgamePosition(
      id = id,
      category = category,
      name = name,
      description = description,
      fen = fen,
      state = state,
      attackerColor = attacker,
      defenderColor = defender,
      kingChebyshevDistance = chebyshev,
      kingManhattanDistance = manhattan,
      theoreticalOutcome = outcome
    )

  private def parseColor(str: String, ctx: String): Either[String, Color] =
    str.toLowerCase match
      case "white" | "w" => Right(Color.White)
      case "black" | "b" => Right(Color.Black)
      case other         => Left(s"$ctx must be 'white' or 'black', got '$other'")

  private def validateDistances(state: GameState, chebyshev: Int, manhattan: Int, ctx: String): Either[String, Unit] =
    val wk = Square.fromIndex(java.lang.Long.numberOfTrailingZeros((state.kings & state.whitePieces).value))
    val bk = Square.fromIndex(java.lang.Long.numberOfTrailingZeros((state.kings & state.blackPieces).value))
    val dx = math.abs(wk.file.toInt - bk.file.toInt)
    val dy = math.abs(wk.rank - bk.rank)
    val expectedChebyshev = math.max(dx, dy)
    val expectedManhattan = dx + dy
    for
      _ <- Either.cond(
        chebyshev == expectedChebyshev,
        (),
        s"$ctx.kingChebyshevDistance $chebyshev does not match actual board distance $expectedChebyshev"
      )
      _ <- Either.cond(
        manhattan == expectedManhattan,
        (),
        s"$ctx.kingManhattanDistance $manhattan does not match actual board distance $expectedManhattan"
      )
    yield ()

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
      case Json.JInt(value) if value >= Int.MinValue && value <= Int.MaxValue => Right(value.toInt)
      case Json.JInt(_) => Left(s"$context.$name is out of range for an integer")
      case _            => Left(s"$context.$name must be an integer")
    }
