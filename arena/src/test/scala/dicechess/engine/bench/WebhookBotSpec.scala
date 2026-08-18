package dicechess.engine.bench

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import dicechess.engine.domain.*
import dicechess.engine.search.*
import munit.FunSuite

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicInteger

/** Webhook opponents for the timed arena (#526): signing, envelope/tree shape, and the full delivery loop against a
  * mock endpoint that verifies signatures and answers from the `legalMoves` tree it was sent — so a regression in
  * either the signature or the tree surfaces as a forfeited match, not a green test.
  */
class WebhookBotSpec extends FunSuite:

  private val Secret = "test-secret"

  /** A minimal in-process bot endpoint speaking the platform webhook protocol, with switchable failure modes. */
  final private class MockEndpoint:
    enum Mode derives CanEqual:
      case FirstLegal, Illegal, ServerError, WrongNonce

    @volatile var mode: Mode = Mode.FirstLegal
    val turnDeliveries       = new AtomicInteger(0)

    private val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/api/webhook",
      new HttpHandler:
        override def handle(exchange: HttpExchange): Unit =
          val body               = new String(exchange.getRequestBody.readAllBytes(), UTF_8)
          val json               = Json.parse(body).getOrElse(Json.JNull)
          val kind               = json.field("type").flatMap(_.asStr).getOrElse("")
          val (status, response) = kind match
            case "verification" =>
              val nonce  = json.field("nonce").flatMap(_.asStr).getOrElse("")
              val echoed = mode match
                case Mode.WrongNonce => nonce + "-tampered"
                case _               => nonce
              (200, Json.render(Json.obj("nonce" -> Json.str(echoed))))
            case "yourTurn" =>
              turnDeliveries.incrementAndGet()
              val timestamp = Option(exchange.getRequestHeaders.getFirst("X-DiceChess-Timestamp"))
              val signature = Option(exchange.getRequestHeaders.getFirst("X-DiceChess-Signature"))
              val signedOk  = (for
                ts  <- timestamp.flatMap(_.toLongOption)
                sig <- signature
              yield WebhookBot.sign(Secret, ts, body) == sig).getOrElse(false)
              if !signedOk then (401, """{"error":"bad signature"}""")
              else
                mode match
                  case Mode.ServerError => (500, """{"error":"boom"}""")
                  case Mode.Illegal     => (200, """{"moves":["a1a1"]}""")
                  case _                =>
                    val tree = json.field("state").flatMap(_.field("legalMoves")).getOrElse(Json.JObj(Nil))
                    val path = firstPath(tree)
                    (200, Json.render(Json.obj("moves" -> Json.JArr(path.map(Json.str)))))
            case _ => (400, """{"error":"unrecognized type"}""")
          val bytes = response.getBytes(UTF_8)
          exchange.getResponseHeaders.set("Content-Type", "application/json")
          exchange.sendResponseHeaders(status, bytes.length.toLong)
          val out = exchange.getResponseBody
          out.write(bytes)
          out.close()
    )
    server.start()

    /** Walks the prefix tree taking the first branch at every level — always a complete legal turn (leaf = turn). */
    private def firstPath(tree: Json): List[String] = tree match
      case Json.JObj((move, sub) :: _) => move :: firstPath(sub)
      case _                           => Nil

    def url: String  = s"http://127.0.0.1:${server.getAddress.getPort}/api/webhook"
    def stop(): Unit = server.stop(0)

  private def withMockEndpoint(test: MockEndpoint => Unit): Unit =
    val endpoint = new MockEndpoint
    try test(endpoint)
    finally endpoint.stop()

  /** Runs a timed match through the algorithm-level overload — the process-wide [[BotRegistry]] singleton stays
    * untouched, since [[dicechess.engine.search.BotRegistrySpec]] asserts its exact contents.
    */
  private def runMockMatch(endpoint: MockEndpoint, gamesPerColor: Int): TimedMatchResult =
    val start = FenParser.parse("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1").toOption.get
    BotMatchRunner.runTimedMatch(
      new WebhookBot(endpoint.url, Secret),
      RandomSearch,
      // Generous budget: the assertions target protocol behaviour, not speed — every webhook turn pays for path
      // generation, JSON rendering, and a loopback HTTP round trip, and a tight clock would turn CI slowness into
      // a flag-fall and a red `botTimeouts == 0` assertion. Wall time is unaffected (both sides answer instantly).
      TimedMatchSetup(gamesPerColor, TimeControl.ofSeconds(120, 1), start, seed = 42L)
    )

  test("sign: matches the platform's HMAC-SHA256 signature byte-for-byte") {
    // Pinned vector, cross-checked with `openssl dgst -sha256 -hmac 'test-secret'` over "1752750000.{...}".
    assertEquals(
      WebhookBot.sign("test-secret", 1752750000L, """{"type":"yourTurn"}"""),
      "8b769c04ea3a291501b2ae20f4a6b1a3753a943fa49ab52844c88693cb2a1e78"
    )
  }

  test("movesTree: prefix tree with sorted keys, where a leaf is a complete legal turn") {
    val tree = WebhookBot.movesTree(List(List("e2e4", "g1f3"), List("e2e4", "b1c3"), List("a2a3")))
    assertEquals(Json.render(tree), """{"a2a3":{},"e2e4":{"b1c3":{},"g1f3":{}}}""")
  }

  test("handshake: succeeds against a well-behaved endpoint") {
    withMockEndpoint { endpoint =>
      assertEquals(new WebhookBot(endpoint.url, Secret).handshake(), Right(()))
    }
  }

  test("handshake: a tampered nonce echo and a dead endpoint both fail") {
    withMockEndpoint { endpoint =>
      endpoint.mode = endpoint.Mode.WrongNonce
      assert(new WebhookBot(endpoint.url, Secret).handshake().isLeft)
    }
    // Port 1 on loopback: nothing listens there, so the connection is refused.
    assert(new WebhookBot("http://127.0.0.1:1/api/webhook", Secret).handshake().isLeft)
  }

  test("a full timed match against a mock endpoint completes with legal play and webhook latency samples") {
    withMockEndpoint { endpoint =>
      val r = runMockMatch(endpoint, gamesPerColor = 2)
      assertEquals(r.totalGames, 4)
      assertEquals(r.wins + r.losses + r.draws, 4)
      assertEquals(r.botTimeouts, 0)
      assert(endpoint.turnDeliveries.get() > 0, "the endpoint was never consulted")
      assert(r.latency.count > 0, "webhook moves must contribute latency samples")
    }
  }

  test("an illegal response forfeits the game on time for the webhook side") {
    withMockEndpoint { endpoint =>
      endpoint.mode = endpoint.Mode.Illegal
      val r = runMockMatch(endpoint, gamesPerColor = 1)
      assertEquals(r.totalGames, 2)
      assertEquals((r.wins, r.losses, r.draws), (0, 2, 0))
      assertEquals(r.botTimeouts, 2)
    }
  }

  test("a non-200 response forfeits the game on time for the webhook side") {
    withMockEndpoint { endpoint =>
      endpoint.mode = endpoint.Mode.ServerError
      val r = runMockMatch(endpoint, gamesPerColor = 1)
      assertEquals((r.wins, r.losses, r.draws), (0, 2, 0))
      assertEquals(r.botTimeouts, 2)
    }
  }

  test("chooseTurn: a roll with no legal turn auto-passes without delivering") {
    withMockEndpoint { endpoint =>
      // Lone kings and three pawn dice: no pawn exists, so no die is playable — a forced pass.
      val state = FenParser.parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1").toOption.get.withDicePool(List(1, 1, 1))
      val bot   = new WebhookBot(endpoint.url, Secret)
      val turn  = bot.chooseTurn(state, "pass-test", Color.White, 5000L, 5000L, TimeControl.ofSeconds(6, 0))
      assertEquals(turn, Right(None))
      assertEquals(endpoint.turnDeliveries.get(), 0)
    }
  }

  test("fromEnv: builds from the env secret and fails fast when it is missing") {
    val bot = WebhookBot.fromEnv("http://example.invalid/hook", Map(WebhookBot.SecretEnvVar -> "s3cr3t"))
    // The clockless SearchAlgorithm entry point is deliberately unsupported for webhook opponents.
    intercept[RuntimeException](bot.findBestMove(FenParser.parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1").toOption.get))
    intercept[RuntimeException](WebhookBot.fromEnv("http://example.invalid/hook", Map.empty))
  }

  test("the untimed arena rejects webhook ids with a clear message") {
    val error = intercept[RuntimeException](
      BotMatchRunner.runArena("http://127.0.0.1:1/api/webhook", None, 1, "4k3/8/8/8/8/8/8/4K3 w - - 0 1")
    )
    assert(Option(error.getMessage).exists(_.contains("timed arena")))
  }
