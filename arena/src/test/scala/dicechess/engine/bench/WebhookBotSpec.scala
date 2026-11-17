// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.bench

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import dicechess.engine.domain.*
import dicechess.engine.json.Json
import dicechess.engine.search.*
import munit.FunSuite

import java.net.InetSocketAddress
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.Locale

/** Webhook opponents for the timed arena (#526): signing, envelope/tree shape, and the full delivery loop against a
  * mock endpoint that verifies signatures and answers from the `legalMoves` tree it was sent — so a regression in
  * either the signature or the tree surfaces as a forfeited match, not a green test.
  */
class WebhookBotSpec extends FunSuite:

  private val Secret                                = "test-secret"
  private var createdHttpClient: Option[HttpClient] = None
  private lazy val Http: HttpClient                 =
    val client = HttpClient.newHttpClient()
    createdHttpClient = Some(client)
    client

  final private case class RawReply(status: Int, body: Array[Byte], contentType: String, contentLength: Long)

  override def afterAll(): Unit =
    try createdHttpClient.foreach(_.close())
    finally super.afterAll()

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
          val body = new String(exchange.getRequestBody.readAllBytes(), UTF_8)
          val json = Json.parse(body).getOrElse(Json.JNull)
          respondTo(exchange, json, body)
    )
    server.start()

    private def respondTo(exchange: HttpExchange, json: Json, body: String): Unit =
      val kind = json.field("type").flatMap(_.asStr).getOrElse("")
      kind match
        case "verification" => writeResponse(exchange, 200, verificationResponse(json))
        case "yourTurn"     => respondToTurn(exchange, json, body)
        case _              => writeResponse(exchange, 400, """{"error":"unrecognized type"}""")

    private def writeResponse(exchange: HttpExchange, status: Int, response: String): Unit =
      val bytes = response.getBytes(UTF_8)
      exchange.getResponseHeaders.set("Content-Type", "application/json")
      exchange.sendResponseHeaders(status, bytes.length.toLong)
      val out = exchange.getResponseBody
      out.write(bytes)
      out.close()

    private def verificationResponse(json: Json): String =
      val nonce  = json.field("nonce").flatMap(_.asStr).getOrElse("")
      val echoed = mode match
        case Mode.WrongNonce => nonce + "-tampered"
        case _               => nonce
      Json.render(Json.obj("nonce" -> Json.str(echoed)))

    private def respondToTurn(exchange: HttpExchange, json: Json, body: String): Unit =
      turnDeliveries.incrementAndGet()
      val timestamp = Option(exchange.getRequestHeaders.getFirst("X-DiceChess-Timestamp"))
      val signature = Option(exchange.getRequestHeaders.getFirst("X-DiceChess-Signature"))
      val signedOk  = (for
        ts  <- timestamp.flatMap(_.toLongOption)
        sig <- signature
      yield WebhookBot.sign(Secret, ts, body) == sig).getOrElse(false)
      if !signedOk then writeResponse(exchange, 401, """{"error":"bad signature"}""")
      else
        mode match
          case Mode.ServerError => writeResponse(exchange, 500, """{"error":"boom"}""")
          case Mode.Illegal     => writeResponse(exchange, 200, """{"moves":["a1a1"]}""")
          case _                =>
            val stateObj  = json.field("state")
            val versionOk = stateObj.flatMap(_.field("version")).exists {
              case Json.JInt(_) => true
              case _            => false
            }
            if !versionOk then writeResponse(exchange, 400, """{"error":"missing or invalid state.version"}""")
            else
              val tree = stateObj.flatMap(_.field("legalMoves")).getOrElse(Json.JObj(Nil))
              val path = firstPath(tree)
              writeResponse(exchange, 200, Json.render(Json.obj("moves" -> Json.JArr(path.map(Json.str)))))

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

  private def rawPost(
      endpoint: MockEndpoint,
      body: String,
      timestamp: Option[String] = None,
      signature: Option[String] = None
  ): RawReply =
    val builder = HttpRequest
      .newBuilder(URI.create(endpoint.url))
      .timeout(Duration.ofSeconds(5))
      .header("Content-Type", "application/json")
    timestamp.foreach(value => builder.header("X-DiceChess-Timestamp", value))
    signature.foreach(value => builder.header("X-DiceChess-Signature", value))
    val request  = builder.POST(HttpRequest.BodyPublishers.ofString(body, UTF_8)).build()
    val response = Http.send(request, HttpResponse.BodyHandlers.ofByteArray())
    RawReply(
      response.statusCode(),
      response.body(),
      response.headers().firstValue("Content-Type").orElse(""),
      response.headers().firstValue("Content-Length").orElse("-1").toLong
    )

  private def signedPost(endpoint: MockEndpoint, body: String, timestamp: Long = 42L): RawReply =
    rawPost(
      endpoint,
      body,
      Some(timestamp.toString),
      Some(WebhookBot.sign(Secret, timestamp, body))
    )

  private def assertReply(reply: RawReply, status: Int, body: String): Unit =
    val expectedBytes = body.getBytes(UTF_8)
    assertEquals(reply.status, status)
    assertEquals(new String(reply.body, UTF_8), body)
    assertEquals(reply.body.length, expectedBytes.length)
    assertEquals(reply.contentType, "application/json")
    assertEquals(reply.contentLength, expectedBytes.length.toLong)

  private val BadSignatureReply = """{"error":"bad signature"}"""
  private val UnknownTypeReply  = """{"error":"unrecognized type"}"""
  private val BadVersionReply   = """{"error":"missing or invalid state.version"}"""

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

  test("mock handler: malformed and unknown events return the same error without counting turns") {
    val bodies = List(
      "",
      "{not-json",
      "[]",
      "17",
      "{}",
      """{"type":17}""",
      """{"type":"other"}"""
    )
    withMockEndpoint { endpoint =>
      bodies.foreach { body =>
        assertReply(rawPost(endpoint, body), 400, UnknownTypeReply)
        assertEquals(endpoint.turnDeliveries.get(), 0)
      }
    }
  }

  test("mock handler: verification echoes or tampers with nonce without signing or turn delivery") {
    val verification  = """{"type":"verification","nonce":"n-7"}"""
    val emptyNonce    = """{"nonce":""}"""
    val echoedNonce   = """{"nonce":"n-7"}"""
    val tamperedNonce = """{"nonce":"n-7-tampered"}"""

    withMockEndpoint { endpoint =>
      List(endpoint.Mode.FirstLegal, endpoint.Mode.Illegal, endpoint.Mode.ServerError).foreach { mode =>
        endpoint.mode = mode
        assertReply(rawPost(endpoint, verification), 200, echoedNonce)
      }
      assertEquals(endpoint.turnDeliveries.get(), 0)
      assertReply(rawPost(endpoint, """{"type":"verification"}"""), 200, emptyNonce)
      assertReply(rawPost(endpoint, """{"type":"verification","nonce":3}"""), 200, emptyNonce)
      assertReply(
        rawPost(endpoint, """{"type":"verification","nonce":"Aé"}"""),
        200,
        """{"nonce":"Aé"}"""
      )
      endpoint.mode = endpoint.Mode.WrongNonce
      assertReply(rawPost(endpoint, verification), 200, tamperedNonce)
      assertEquals(endpoint.turnDeliveries.get(), 0)
    }
  }

  test("mock handler: timestamp and signature failures increment before auth and precede mode responses") {
    val body           = """{"type":"yourTurn","state":{"version":2}}"""
    val validSignature = WebhookBot.sign(Secret, 42L, body)
    val badSignature   = WebhookBot.sign("other-secret", 42L, body)
    val upperSignature = validSignature.toUpperCase(Locale.ROOT)
    assertNotEquals(upperSignature, validSignature)
    withMockEndpoint { endpoint =>
      val cases = List(
        (endpoint.Mode.Illegal, None, Some(validSignature)),
        (endpoint.Mode.ServerError, Some("not-a-number"), Some(validSignature)),
        (endpoint.Mode.FirstLegal, Some("9223372036854775808"), Some(validSignature)),
        (endpoint.Mode.FirstLegal, Some("42"), None),
        (endpoint.Mode.FirstLegal, Some("42"), Some(badSignature)),
        (endpoint.Mode.FirstLegal, Some("42"), Some(upperSignature))
      )
      cases.zipWithIndex.foreach { case ((mode, timestamp, signature), index) =>
        endpoint.mode = mode
        assertReply(rawPost(endpoint, body, timestamp, signature), 401, BadSignatureReply)
        assertEquals(endpoint.turnDeliveries.get(), index + 1)
      }
      val original  = """ { "type" : "yourTurn", "state" : { "version" : 2, "legalMoves" : { "e2e4" : {} } } } """
      val timestamp = 73L
      assertReply(signedPost(endpoint, original, timestamp), 200, """{"moves":["e2e4"]}""")
      val compact = Json.render(Json.parse(original).fold(fail(_), identity))
      assertNotEquals(original, compact)
      assertReply(
        rawPost(endpoint, original, Some(timestamp.toString), Some(WebhookBot.sign(Secret, timestamp, compact))),
        401,
        BadSignatureReply
      )
      val modified = original + " "
      assertReply(
        rawPost(endpoint, modified, Some(timestamp.toString), Some(WebhookBot.sign(Secret, timestamp, original))),
        401,
        BadSignatureReply
      )
      assertEquals(endpoint.turnDeliveries.get(), 9)
    }
  }

  test("mock handler: signed error modes run before state version validation") {
    val body = """{"type":"yourTurn"}"""
    withMockEndpoint { endpoint =>
      endpoint.mode = endpoint.Mode.ServerError
      assertReply(signedPost(endpoint, body), 500, """{"error":"boom"}""")
      assertEquals(endpoint.turnDeliveries.get(), 1)
      endpoint.mode = endpoint.Mode.Illegal
      assertReply(signedPost(endpoint, body), 200, """{"moves":["a1a1"]}""")
      assertEquals(endpoint.turnDeliveries.get(), 2)
    }
  }

  test("mock handler: only integer state versions pass and all signed turns are counted") {
    val invalidBodies = List(
      """{"type":"yourTurn"}""",
      """{"type":"yourTurn","state":null}""",
      """{"type":"yourTurn","state":false}""",
      """{"type":"yourTurn","state":1}""",
      """{"type":"yourTurn","state":2.5}""",
      """{"type":"yourTurn","state":[]}""",
      """{"type":"yourTurn","state":"bad"}""",
      """{"type":"yourTurn","state":{}}""",
      """{"type":"yourTurn","state":{"version":"2"}}""",
      """{"type":"yourTurn","state":{"version":2.5}}""",
      """{"type":"yourTurn","state":{"version":null}}""",
      """{"type":"yourTurn","state":{"version":true}}""",
      """{"type":"yourTurn","state":{"version":[]}}""",
      """{"type":"yourTurn","state":{"version":{}}}"""
    )
    withMockEndpoint { endpoint =>
      invalidBodies.zipWithIndex.foreach { case (body, index) =>
        assertReply(signedPost(endpoint, body), 400, BadVersionReply)
        assertEquals(endpoint.turnDeliveries.get(), index + 1)
      }
      val acceptedVersions = List(0L, -1L, 999L)
      acceptedVersions.zipWithIndex.foreach { case (version, index) =>
        val body =
          s"""{"type":"yourTurn","state":{"version":$version,"legalMoves":{"e2e4":{}}}}"""
        assertReply(signedPost(endpoint, body), 200, """{"moves":["e2e4"]}""")
        assertEquals(endpoint.turnDeliveries.get(), invalidBodies.length + index + 1)
      }
      assertEquals(endpoint.turnDeliveries.get(), invalidBodies.length + acceptedVersions.length)
    }
  }

  test("mock handler: legal move trees use the first field path and empty fallbacks") {
    val cases = List(
      ("""{"type":"yourTurn","state":{"version":2}}""", """{"moves":[]}"""),
      ("""{"type":"yourTurn","state":{"version":2,"legalMoves":{}}}""", """{"moves":[]}"""),
      ("""{"type":"yourTurn","state":{"version":2,"legalMoves":[]}}""", """{"moves":[]}"""),
      (
        """{"type":"yourTurn","state":{"version":2,"legalMoves":{"a2a3":{"b2b3":{},"c2c3":{}},"e2e4":{"g1f3":{}}}}}""",
        """{"moves":["a2a3","b2b3"]}"""
      ),
      (
        """{"type":"yourTurn","state":{"version":2,"legalMoves":{"e2e4":null}}}""",
        """{"moves":["e2e4"]}"""
      )
    )
    withMockEndpoint { endpoint =>
      cases.zipWithIndex.foreach { case ((body, expected), index) =>
        assertReply(signedPost(endpoint, body), 200, expected)
        assertEquals(endpoint.turnDeliveries.get(), index + 1)
      }
      endpoint.mode = endpoint.Mode.WrongNonce
      val fallback = """{"type":"yourTurn","state":{"version":2,"legalMoves":{"e2e4":{}}}}"""
      assertReply(signedPost(endpoint, fallback, timestamp = -7L), 200, """{"moves":["e2e4"]}""")
      assertEquals(endpoint.turnDeliveries.get(), cases.length + 1)
    }
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
