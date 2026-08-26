package dicechess.engine.search

/** Metadata representing a Search Algorithm (Bot) in the engine.
  *
  * @param id
  *   Unique identifier (e.g., "random", "greedy")
  * @param name
  *   Human-readable name
  * @param description
  *   Brief description of the bot's behavior
  * @param difficulty
  *   Difficulty level from 1 to 10
  * @param isExperimental
  *   True if the bot is in beta or experimental phase
  */
case class BotInfo(
    id: String,
    name: String,
    description: String,
    difficulty: Int,
    isExperimental: Boolean
)

/** Central registry for all available search algorithms in the engine.
  */
object BotRegistry:

  /** A registered entry. `token` identifies *this particular registration* (a fresh object per [[registerCustomBot]]
    * call) — distinct from `algorithm` identity, since the same algorithm instance can be registered more than once
    * (e.g. under different ids, or re-registered under the same one).
    */
  final private case class Entry(info: BotInfo, algorithm: SearchAlgorithm, token: AnyRef)

  private val InitialBots: Map[String, Entry] = Map(
    "random" -> Entry(
      BotInfo(
        id = "random",
        name = "Random",
        description = "Makes a random valid move.",
        difficulty = 1,
        isExperimental = false
      ),
      RandomSearch,
      new Object
    ),
    "checkmate-aware" -> Entry(
      BotInfo(
        id = "checkmate-aware",
        name = "Checkmate Aware",
        description = "Prioritizes immediate checkmate and king safety, but remains material-blind.",
        difficulty = 2,
        isExperimental = false
      ),
      CheckmateAwareSearch,
      new Object
    ),
    "greedy" -> Entry(
      BotInfo(
        id = "greedy",
        name = "Greedy",
        description = "Always tries to capture the highest value piece without considering consequences.",
        difficulty = 3,
        isExperimental = false
      ),
      GreedySearch,
      new Object
    ),
    "greedy-v2" -> Entry(
      BotInfo(
        id = "greedy-v2",
        name = "Cautious Greedy",
        description =
          "Tries to capture the highest value piece, but avoids leaving the King exposed to immediate capture.",
        difficulty = 4,
        isExperimental = false
      ),
      GreedySearchV2,
      new Object
    ),
    "aggressive" -> Entry(
      BotInfo(
        id = "aggressive",
        name = "Aggressive",
        description = "Actively hunts your pieces and targets your king, pushing pawns forward aggressively.",
        difficulty = 5,
        isExperimental = false
      ),
      AggressiveSearch,
      new Object
    ),
    "monte-carlo" -> Entry(
      BotInfo(
        id = "monte-carlo",
        name = "Monte-Carlo",
        description =
          "Estimates the full-game win probability of each candidate turn with Rao-Blackwellized Monte-Carlo rollouts and plays the highest.",
        difficulty = 6,
        isExperimental = true
      ),
      MonteCarloSearch,
      new Object
    )
  )

  @volatile private var bots: Map[String, Entry] = InitialBots

  /** Resets the registry to its initial built-in bot state. Intended for test isolation across suites sharing the
    * process-wide singleton.
    */
  private[engine] def reset(): Unit = synchronized {
    bots = InitialBots
  }

  /** Returns all available bots sorted by difficulty, including any registered via [[registerCustomBot]]. */
  def availableBots: List[BotInfo] = bots.values.map(_.info).toList.sortBy(_.difficulty)

  /** Looks up a search algorithm by its bot ID.
    *
    * @param id
    *   The bot ID to lookup (case-insensitive)
    * @return
    *   The algorithm if found, or None.
    */
  def getAlgorithm(id: String): Option[SearchAlgorithm] =
    Option(id).flatMap(i => bots.get(i.toLowerCase)).map(_.algorithm)

  /** A live registration created by [[registerCustomBot]]. Closing it removes the entry from the registry and, if the
    * registered algorithm is itself [[java.lang.AutoCloseable]] (e.g. an ONNX bot holding a native onnxruntime
    * session), closes the algorithm too — pairing the two by construction instead of leaving the caller to remember
    * both halves (#589).
    *
    * Closing is a no-op on the registry side if `id` has since been replaced by another registration: a stale handle
    * carries its own registration token and can only remove the exact entry it created, never a newer one — even one
    * that happens to share both the id and the algorithm instance (e.g. the same singleton bot registered twice).
    * Removal is guarded by the same lock [[registerCustomBot]] writes under, so a concurrent registration and close
    * cannot race on `bots`. Closing more than once is safe: only the first call removes the entry or closes
    * `algorithm`.
    */
  final class Registration private[BotRegistry] (id: String, token: AnyRef, algorithm: SearchAlgorithm)
      extends AutoCloseable:
    @volatile private var closed = false

    def close(): Unit =
      val firstClose = synchronized {
        if closed then false
        else
          closed = true
          true
      }
      if firstClose then
        BotRegistry.synchronized {
          bots.get(id).foreach(entry => if entry.token eq token then bots = bots - id)
        }
        algorithm match
          case closeable: AutoCloseable => closeable.close()
          case _                        => ()

  /** Registers (or replaces) a bot at runtime under `info.id`, used to add decorator bots such as an [[OpeningBookBot]]
    * supplied by a host application (the JS API's `registerOpeningBookBot`).
    *
    * The registry is a process-wide singleton. Writes are `synchronized` and `bots` is `@volatile`, so a registration
    * is published safely to concurrent readers (`availableBots` / `getAlgorithm`) on the JVM; it is still intended for
    * host setup (e.g. a JS worker boot) rather than a high-churn write path.
    *
    * @param info
    *   metadata for the bot; `info.id` is the lookup key (lower-cased)
    * @param algorithm
    *   the search algorithm to register
    * @return
    *   a [[Registration]] handle; close it (e.g. via [[scala.util.Using]]) to remove the entry and close `algorithm` if
    *   it owns a closeable resource. Discarding the handle is equivalent to the old permanent-registration behavior.
    */
  def registerCustomBot(info: BotInfo, algorithm: SearchAlgorithm): Registration =
    val id    = info.id.toLowerCase
    val token = new Object
    synchronized {
      bots = bots + (id -> Entry(info, algorithm, token))
    }
    Registration(id, token, algorithm)

  /** Returns the default algorithm (Greedy). */
  def defaultAlgorithm: SearchAlgorithm = GreedySearch
