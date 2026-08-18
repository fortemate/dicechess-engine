package dicechess.engine.bench

import dicechess.engine.domain.*
import dicechess.engine.search.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.util.{Failure, Success, Try}

/** A timed-arena opponent living behind the platform's webhook protocol (#526): each turn is delivered as an
  * HMAC-signed `POST` and the endpoint's `{"moves": [...]}` response is the turn. The arena stays the referee — it owns
  * the dice RNG, the clocks, and (through [[dicechess.engine.search.TurnGenerator]]) the rules; the endpoint owns its
  * move choice and how it spends its clock.
  *
  * The delivery envelope carries the fields the platform bot runtime consumes:
  * ```json
  * {
  *   "type": "yourTurn",
  *   "gameId": "arena-white-3",
  *   "seat": "White",
  *   "state": {
  *     "dfen": "rnbqkbnr/... w KQkq - 0 1 PPN",
  *     "activeSeat": "White",
  *     "dicePending": true,
  *     "clocks": { "white": 179000, "black": 180000 },
  *     "timeControl": { "Fischer": { "initialSeconds": 180, "incrementSeconds": 2 } },
  *     "legalMoves": { "e2e4": { "g1f3": {} } }
  *   }
  * }
  * ```
  * `legalMoves` is the platform's prefix tree of UCI micro-moves — a node with no children is a complete legal turn.
  * The arena is local, so the tree is always present (no size cap / `null` case). Every delivery carries
  * `X-DiceChess-Timestamp` and `X-DiceChess-Signature` (hex `HMAC-SHA256(secret, "<timestamp>.<raw body>")`),
  * byte-compatible with the platform's signing.
  *
  * Two deliberate departures from a plain [[dicechess.engine.search.TimeBudgetedSearch]] opponent:
  *
  *   - '''The endpoint gets its full remaining clock''', not a [[dicechess.engine.search.TimeManager]] slice — the HTTP
  *     timeout is the remaining clock, mirroring the platform ("timeout = remaining clock"). Deployed bots budget their
  *     own thinking; slicing for them would measure the arena's clock policy instead of theirs.
  *   - '''Any delivery failure forfeits the game on time''' for the responding side — a non-200, a malformed body, an
  *     illegal turn, or a transport error. The platform delivers exactly once and never retries, so a bot that fails to
  *     answer inevitably flags; the arena just skips the dead wait. Failures are logged to stderr.
  *
  * When the roll has no legal turn the arena auto-passes '''without delivering''' — the platform likewise never
  * consults a bot on a forced pass.
  *
  * The response's UCI strings are matched byte-exactly against the engine-generated legal paths, never parsed as moves
  * — the engine is the rules oracle, and a protocol-honest bot replays strings from the `legalMoves` tree it was sent,
  * so a match is guaranteed for well-behaved endpoints.
  */
final class WebhookBot(url: String, secret: String, client: HttpClient = WebhookBot.defaultClient)
    extends SearchAlgorithm:

  /** Webhook opponents need clock context to build a faithful envelope — only the timed arena supplies it. */
  override def findBestMove(state: GameState): Option[ScoredSequence] =
    sys.error("webhook opponents are supported by the timed arena only (they need clock context)")

  /** One-time ownership handshake, also the readiness probe: the endpoint must echo the nonce with a 200. The arena
    * runs it once per resolved opponent and refuses to start a match against an endpoint that fails it.
    */
  def handshake(): Either[String, Unit] =
    val nonce = java.util.UUID.randomUUID().toString
    val body  = Json.render(Json.obj("type" -> Json.str("verification"), "nonce" -> Json.str(nonce)))
    post(body, WebhookBot.HandshakeTimeoutMs)
      .flatMap(Json.parse)
      .flatMap(_.field("nonce").flatMap(_.asStr).toRight("handshake response carries no nonce"))
      .flatMap(echo => if echo == nonce then Right(()) else Left("handshake nonce mismatch"))

  /** Delivers one turn and maps the response back to a legal turn path.
    *
    * `Right(None)` is a forced pass (no legal turn — nothing is delivered); `Right(Some(...))` is the endpoint's turn,
    * already validated against the engine's legal paths; `Left(reason)` is any delivery failure, which the arena
    * converts to a loss on time (see the class Scaladoc for why).
    */
  def chooseTurn(
      state: GameState,
      gameId: String,
      seat: Color,
      ownRemainingMs: Long,
      oppRemainingMs: Long,
      tc: TimeControl
  ): Either[String, Option[ScoredSequence]] =
    val paths = TurnGenerator.generateAllLegalTurnPaths(state)
    if paths.isEmpty then Right(None)
    else
      val body = envelope(state, gameId, seat, ownRemainingMs, oppRemainingMs, tc, paths)
      for
        responseBody <- post(body, ownRemainingMs)
        moves        <- parseMoves(responseBody)
        turn         <- paths
          .find(p => p.map(WebhookBot.uci).mkString(" ") == moves.mkString(" "))
          .toRight(s"response is not a legal turn for this roll: [${moves.mkString(", ")}]")
      yield Some(ScoredSequence(turn, 0))

  private def envelope(
      state: GameState,
      gameId: String,
      seat: Color,
      ownRemainingMs: Long,
      oppRemainingMs: Long,
      tc: TimeControl,
      paths: List[List[Move]]
  ): String =
    val (whiteMs, blackMs) = if seat.isWhite then (ownRemainingMs, oppRemainingMs) else (oppRemainingMs, ownRemainingMs)
    val seatName           = if seat.isWhite then "White" else "Black"
    Json.render(
      Json.obj(
        "type"   -> Json.str("yourTurn"),
        "gameId" -> Json.str(gameId),
        "seat"   -> Json.str(seatName),
        "state"  -> Json.obj(
          "dfen"        -> Json.str(FenParser.serialize(state)),
          "activeSeat"  -> Json.str(seatName),
          "dicePending" -> Json.bool(true),
          "clocks"      -> Json.obj("white" -> Json.int(whiteMs), "black" -> Json.int(blackMs)),
          "timeControl" -> Json.obj(
            "Fischer" -> Json.obj(
              "initialSeconds"   -> Json.int(tc.initialMs / 1000),
              "incrementSeconds" -> Json.int(tc.incrementMs / 1000)
            )
          ),
          "legalMoves" -> WebhookBot.movesTree(paths.map(_.map(WebhookBot.uci)))
        )
      )
    )

  private def parseMoves(body: String): Either[String, List[String]] =
    Json
      .parse(body)
      .flatMap(_.field("moves").flatMap(_.asArr).toRight("response carries no 'moves' array"))
      .flatMap { items =>
        items.foldRight(Right(List.empty[String]): Either[String, List[String]]) { (item, acc) =>
          for
            tail <- acc
            move <- item.asStr.toRight("'moves' contains a non-string entry")
          yield move :: tail
        }
      }

  private def post(body: String, timeoutMs: Long): Either[String, String] =
    val timestamp = System.currentTimeMillis() / 1000
    val request   = HttpRequest
      .newBuilder(URI.create(url))
      .timeout(Duration.ofMillis(math.max(1L, timeoutMs)))
      .header("Content-Type", "application/json")
      .header("X-DiceChess-Timestamp", timestamp.toString)
      .header("X-DiceChess-Signature", WebhookBot.sign(secret, timestamp, body))
      .POST(HttpRequest.BodyPublishers.ofString(body, UTF_8))
      .build()
    Try(client.send(request, HttpResponse.BodyHandlers.ofString())) match
      case Failure(e)                                => Left(s"delivery to $url failed: ${e.getMessage}")
      case Success(resp) if resp.statusCode() != 200 => Left(s"$url answered HTTP ${resp.statusCode()}")
      case Success(resp)                             => Right(resp.body())

object WebhookBot:

  /** The env var carrying the HMAC key for outbound deliveries — env rather than argv so the secret never lands in
    * shell history or process listings. Required whenever a webhook opponent is used: the platform bot runtime rejects
    * unsigned turn deliveries, so an unsigned arena could only ever measure a misconfigured endpoint.
    */
  val SecretEnvVar = "DICECHESS_WEBHOOK_SECRET"

  /** Generous fixed budget for the handshake — readiness, not strength, is being measured there. */
  private val HandshakeTimeoutMs = 10_000L

  private lazy val defaultClient: HttpClient =
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

  /** Whether an arena bot id designates a webhook endpoint rather than a [[dicechess.engine.search.BotRegistry]] entry.
    */
  def isWebhookId(id: String): Boolean = id.startsWith("http://") || id.startsWith("https://")

  /** Builds a bot for `url` with the secret from [[SecretEnvVar]]; fails fast when it is missing. */
  def fromEnv(url: String, env: Map[String, String] = sys.env): WebhookBot =
    val secret = env.getOrElse(SecretEnvVar, sys.error(s"$SecretEnvVar must be set to sign deliveries to $url"))
    new WebhookBot(url, secret)

  /** Long-algebraic notation of a micro-move including any promotion suffix (e.g. `"e2e4"`, `"e7e8q"`) — the token
    * vocabulary of the `legalMoves` tree and of endpoint responses.
    */
  private[bench] def uci(move: Move): String = move.toUci

  /** Hex HMAC-SHA256 of `"<timestampEpochSeconds>.<body>"` under `secret` — byte-compatible with the platform's
    * `X-DiceChess-Signature`.
    */
  private[bench] def sign(secret: String, timestampEpochSeconds: Long, body: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"))
    mac.doFinal(s"$timestampEpochSeconds.$body".getBytes(UTF_8)).map(b => f"${b & 0xff}%02x").mkString

  /** The platform's `legalMoves` prefix tree: nodes keyed by UCI micro-move, and a node with no children is a complete
    * legal turn. Keys are sorted so the rendered envelope is deterministic.
    */
  private[bench] def movesTree(paths: List[List[String]]): Json =
    Json.JObj(
      paths
        .filter(_.nonEmpty)
        .groupBy(_.head)
        .toList
        .sortBy(_._1)
        .map((move, group) => move -> movesTree(group.map(_.tail)))
    )
