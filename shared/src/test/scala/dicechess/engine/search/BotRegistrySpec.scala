package dicechess.engine.search

import dicechess.engine.domain.GameState
import munit.FunSuite

class BotRegistrySpec extends FunSuite:

  test("availableBots returns a list of configured bots sorted by difficulty") {
    val bots = BotRegistry.availableBots

    assertEquals(bots.size, 6)
    assertEquals(bots.head.id, "random")
    assertEquals(bots.head.difficulty, 1)

    assertEquals(bots(1).id, "checkmate-aware")
    assertEquals(bots(1).difficulty, 2)

    assertEquals(bots(2).id, "greedy")
    assertEquals(bots(2).difficulty, 3)

    assertEquals(bots(3).id, "greedy-v2")
    assertEquals(bots(3).difficulty, 4)

    assertEquals(bots(4).id, "aggressive")
    assertEquals(bots(4).difficulty, 5)

    assertEquals(bots(5).id, "monte-carlo")
    assertEquals(bots(5).difficulty, 6)
  }

  test("getAlgorithm returns the correct algorithm for a given id (case-insensitive)") {
    assertEquals(BotRegistry.getAlgorithm("random"), Some(RandomSearch))
    assertEquals(BotRegistry.getAlgorithm("RANDOM"), Some(RandomSearch))

    assertEquals(BotRegistry.getAlgorithm("checkmate-aware"), Some(CheckmateAwareSearch))
    assertEquals(BotRegistry.getAlgorithm("CHECKMATE-AWARE"), Some(CheckmateAwareSearch))

    assertEquals(BotRegistry.getAlgorithm("greedy"), Some(GreedySearch))
    assertEquals(BotRegistry.getAlgorithm("GrEeDy"), Some(GreedySearch))

    assertEquals(BotRegistry.getAlgorithm("greedy-v2"), Some(GreedySearchV2))

    assertEquals(BotRegistry.getAlgorithm("aggressive"), Some(AggressiveSearch))
    assertEquals(BotRegistry.getAlgorithm("AGGRESSIVE"), Some(AggressiveSearch))

    assertEquals(BotRegistry.getAlgorithm("monte-carlo"), Some(MonteCarloSearch))
    assertEquals(BotRegistry.getAlgorithm("MONTE-CARLO"), Some(MonteCarloSearch))

    assertEquals(BotRegistry.getAlgorithm("unknown"), None)
    assertEquals(BotRegistry.getAlgorithm(null.asInstanceOf[String]), None) // scalafix:ok(DisableSyntax.null)
  }
  test("defaultAlgorithm returns GreedySearch") {
    assertEquals(BotRegistry.defaultAlgorithm, GreedySearch)
  }

  // #589: a caller that owns a closeable algorithm must be able to make a registered id stop resolving again.
  test("registerCustomBot's Registration.close() removes the id and does not disturb built-in bots") {
    val info         = BotInfo("test-removable", "Test Removable", "regression fixture for #589", 5, true)
    val registration = BotRegistry.registerCustomBot(info, RandomSearch)
    assertEquals(BotRegistry.getAlgorithm("test-removable"), Some(RandomSearch))

    registration.close()

    assertEquals(BotRegistry.getAlgorithm("test-removable"), None)
    assertEquals(BotRegistry.getAlgorithm("greedy"), Some(GreedySearch))
    assertEquals(BotRegistry.availableBots.size, 6)
  }

  test("Registration.close() closes an AutoCloseable algorithm") {
    var closed       = false
    val closeableBot = new SearchAlgorithm with AutoCloseable:
      def findBestMove(state: GameState): Option[ScoredSequence] = RandomSearch.findBestMove(state)
      def close(): Unit                                          = closed = true

    val registration =
      BotRegistry.registerCustomBot(BotInfo("test-closeable", "Test Closeable", "#589", 5, true), closeableBot)
    registration.close()

    assert(closed, "closing the registration should close the AutoCloseable algorithm")
    assertEquals(BotRegistry.getAlgorithm("test-closeable"), None)
  }

  test("Registration.close() is a no-op on the registry when a newer registration replaced the id") {
    val id           = "test-replaced"
    val firstInfo    = BotInfo(id, "First", "#589", 5, true)
    val firstHandle  = BotRegistry.registerCustomBot(firstInfo, RandomSearch)
    val secondInfo   = BotInfo(id, "Second", "#589", 5, true)
    val secondHandle = BotRegistry.registerCustomBot(secondInfo, GreedySearch)
    try
      firstHandle.close() // a stale handle for the old registration under the same id

      assertEquals(BotRegistry.getAlgorithm(id), Some(GreedySearch))
    finally secondHandle.close() // keep the registry clean for other tests sharing this process-wide singleton
  }

  test(
    "Registration.close() is a no-op when the same algorithm instance was re-registered under the same id"
  ) {
    // Reproduces the case a plain `registered eq algorithm` check cannot distinguish: registering the same
    // singleton bot object twice under one id, then closing the FIRST handle after the second registration exists.
    val id           = "test-same-instance-reregistered"
    val info         = BotInfo(id, "Same Instance", "#589", 5, true)
    val firstHandle  = BotRegistry.registerCustomBot(info, RandomSearch)
    val secondHandle = BotRegistry.registerCustomBot(info, RandomSearch)
    try
      firstHandle.close()

      assertEquals(BotRegistry.getAlgorithm(id), Some(RandomSearch))
    finally secondHandle.close()
  }

  test("Registration.close() closes an AutoCloseable algorithm at most once") {
    var closeCount   = 0
    val closeableBot = new SearchAlgorithm with AutoCloseable:
      def findBestMove(state: GameState): Option[ScoredSequence] = RandomSearch.findBestMove(state)
      def close(): Unit                                          = closeCount += 1

    val registration =
      BotRegistry.registerCustomBot(BotInfo("test-idempotent-close", "Test Idempotent", "#589", 5, true), closeableBot)
    registration.close()
    registration.close()

    assertEquals(closeCount, 1)
  }
