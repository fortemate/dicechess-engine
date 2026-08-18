package dicechess.engine.jvmapi

import dicechess.engine.domain.*
import dicechess.engine.search.{BotRegistry, Evaluator, TimeBudgetedSearch, TurnGenerator}

import java.util as ju
import scala.jdk.CollectionConverters.*
import scala.util.Random

/** A JVM-language-agnostic facade over the engine's Scala API — the JVM-row counterpart to the JS row's `EngineFacade`
  * (`js/.../EngineFacade.scala`). For a Java, Kotlin, or other JVM-language caller, the plain Scala API has three sharp
  * edges this facade exists to remove:
  *
  *   - `Either[String, GameState]` (`FenParser.parse`) has no idiomatic Java consumption; [[parseDfen]] converts the
  *     `Left` case to an exception.
  *   - `GameState.makeMove` is an extension method: it compiles onto a synthetic `$package` object, not a real class,
  *     and its JVM name is disambiguated by `@targetName` (an implementation detail no contract promises to keep).
  *     `GameState.activeColor` has the opposite problem — it is an ordinary `inline def` member, so it is erased at the
  *     call site and leaves no bytecode at all, not even one reachable by reflection. [[activeColor]] and
  *     [[legalTurns]] wrap the ones a caller actually needs behind ordinary static methods.
  *   - `Move` is an opaque `Int`: erasure makes a bare `List[List[Move]]` look, from Java, like an unchecked
  *     `List[List[Object]]` of boxed integers with no safe way to read it back. [[legalTurns]] decodes each path to its
  *     `java.util.List[String]` of UCI tokens (and the position after playing it) so the opaque type never crosses the
  *     boundary.
  *
  * '''What this deliberately does not do''': there is no `applyTurn(state, List[String])` that re-parses UCI tokens
  * back into moves. play-api's protocol treats UCI strings as opaque tokens matched by exact string equality against
  * engine-generated legal paths, never independently decoded (see `WebhookBot`'s Scaladoc) — a second decoder here
  * would be a fifth place that can drift from the encoder in `Move.toUci`. A caller that already holds a UCI move list
  * (e.g. from the platform's inline `legalMoves`) matches it against [[legalTurns]]'s own `uci` field to get the
  * corresponding [[Turn.finalState]], instead of decoding the strings itself.
  *
  * '''Two supported shapes of consumer'''. A ''webhook bot'' is handed a position and asked for one turn — it needs
  * only [[parseDfen]], [[activeColor]] and [[legalTurns]]. An ''autonomous bot'' drives the whole game itself and needs
  * the rest of the loop: read the pending dice ([[dicePool]]), roll and set the next ones ([[withDice]]), enumerate or
  * ask a built-in bot for a turn ([[legalTurns]] / [[bestTurn]]), hand the move over ([[endTurn]]), decide whether the
  * game is finished ([[isGameOver]] / [[winner]] / [[halfMoveClock]]), score a position ([[evaluate]]) and serialize it
  * back out ([[toDfen]]). Dice ''generation'' stays on the caller's side deliberately: an offline loop needs nothing
  * more than `java.util.Random`, and a platform game gets its dice from the platform.
  */
object JvmApi:

  /** How the [[winner]] encoding spells "nobody has won". Distinct from `0`/`1`, which are real colors — see
    * [[dicechess.engine.domain.Color]].
    */
  final val NoWinner: Int = -1

  /** Thinking time the two-argument [[bestTurn]] gives a bot that spends time rather than a fixed amount of work.
    *
    * One second is a compromise, not a tuned value: long enough that `monte-carlo` plays to something like its
    * strength, short enough to sit in a self-play loop without the caller wondering whether the process is stuck. A
    * consumer that cares picks its own with the three-argument [[bestTurn]].
    */
  final val DefaultTimeBudgetMs: Long = 1000L

  private val NanosPerMilli: Long = 1_000_000L

  /** One complete legal turn: its micro-moves as UCI tokens, in order (e.g. `["e2e4"]` or `["d2d4", "d4d5"]`), and the
    * position reached by playing all of them. `finalState` has '''not''' been passed through `GameState.endTurn()` —
    * the active color has not flipped and the dice pool has not been cleared — because nothing in [[legalTurns]]'s only
    * current use (scoring candidate turns) needs a turn-ended state, and guessing at that boundary on a caller's behalf
    * would be speculative.
    */
  final case class Turn(uci: ju.List[String], finalState: GameState)

  /** Parses a DFEN string (FEN extended with a 7th field for the pending dice pool) into a
    * [[dicechess.engine.domain.GameState]].
    *
    * @throws IllegalArgumentException
    *   if `dfen` is not a valid DFEN — the message is `FenParser`'s own parse error
    */
  def parseDfen(dfen: String): GameState =
    // The one deliberate exception to this repo's Either-only error convention: this method's entire purpose is
    // translating FenParser's Either into something a Java caller can catch, so IllegalArgumentException (not
    // sys.error's RuntimeException) is the point, not a shortcut.
    FenParser.parse(dfen).fold(msg => throw IllegalArgumentException(msg), identity) // scalafix:ok(DisableSyntax.throw)

  /** The color to move in `state` — `0` (White) or `1` (Black); see [[dicechess.engine.domain.Color]]. Exposed here
    * only because `GameState.activeColor` is an `inline def` member of the case class: it is erased at the call site
    * and leaves no bytecode a Java caller could bind to, reflection included.
    */
  def activeColor(state: GameState): Color = state.activeColor

  /** Every legal turn playable from `state`, each as its UCI micro-move sequence plus the resulting position — the
    * fallback for when the platform's inline `legalMoves` tree was elided by its size cap (`TurnContext.legalMoves` is
    * `null` in that case; see dicechess-bot-runtime). Empty when the roll has no legal turn (a forced pass).
    */
  def legalTurns(state: GameState): ju.List[Turn] =
    TurnGenerator
      .generateAllLegalTurnPaths(state)
      .map(path => Turn(path.map(_.toUci).asJava, path.foldLeft(state)(_.makeMove(_))))
      .asJava

  /** One legal turn plus the score the bot that chose it assigned to the resulting position — [[bestTurn]]'s result,
    * and the JVM counterpart of the JS `getBestMove`'s `{ moves, score }`.
    *
    * `uci` and `finalState` mean exactly what they do on [[Turn]], `finalState` included: it has '''not''' been passed
    * through [[endTurn]].
    *
    * '''`score` is on the scale of the bot that produced it, and on no other.''' Only the sign and the ordering are
    * common to all of them: higher is better for the side that played the turn, and `Integer.MAX_VALUE` means the turn
    * captures the opponent's king. The unit is not: the material-scoring bots (`greedy`, `greedy-v2`, `aggressive`,
    * `checkmate-aware`) return centipawns comparable with [[evaluate]], while `monte-carlo` returns an estimated win
    * probability scaled to `0`–`1000000`, where a level position sits near `500000`. Compare scores only among turns
    * from one call to one algorithm; reading a `monte-carlo` score as centipawns overstates the position by three
    * orders of magnitude.
    */
  final case class ScoredTurn(uci: ju.List[String], finalState: GameState, score: Int)

  /** The dice still available to the side to move, as a read-only list of 1–6 values — empty once the turn's dice are
    * spent (or before any have been rolled).
    *
    * Exposed because `GameState.dicePool` is an `inline def` on top of an extension method: it is erased at the call
    * site and leaves no bytecode a Java caller could bind to, so unlike a plain extension method it is not even
    * reachable by reflection.
    */
  def dicePool(state: GameState): ju.List[Integer] =
    state.dicePool.map(die => Integer.valueOf(die)).asJava

  /** The half-move clock: micro-moves played since the last pawn move or capture, saturating at 127 rather than
    * wrapping.
    *
    * The engine itself never ends a game on this counter — the only terminal condition in the rules is a captured king
    * (see [[isGameOver]]). A 50-move-style draw is a '''host policy''': the bot arena, for instance, calls a game drawn
    * at `halfMoveClock >= 100`. This accessor exists so an autonomous loop can apply such a policy of its own, and for
    * the same bytecode reason as [[dicePool]].
    */
  def halfMoveClock(state: GameState): Int = state.halfMoveClock

  /** Returns `state` with its dice pool replaced by `dice` — how an autonomous loop hands the engine the roll it made
    * for the side to move.
    *
    * '''Validated, not trusted''': `dice` may hold at most three values, each an integer from 1 to 6, and no `null`. A
    * violation throws [[IllegalArgumentException]] rather than being silently coerced, because the underlying packed
    * representation would otherwise absorb the mistake quietly — it stores three bits per die, so an out-of-range 8
    * would read back as "no die" and a fourth entry would simply vanish, turning a caller's bug into a position that
    * looks legal and plays wrong. An empty list is accepted and clears the pool.
    *
    * Dice generation is the caller's job (`java.util.Random` for a self-play loop, the platform's roll otherwise): the
    * engine has no opinion on where the numbers come from, and a fairness mechanism here would be one more thing to
    * keep in sync with the platform's.
    *
    * @throws IllegalArgumentException
    *   if `dice` is `null`, holds more than three values, or holds one that is `null` or outside 1–6
    */
  def withDice(state: GameState, dice: ju.List[Integer]): GameState =
    val supplied = Option(dice)
    require(supplied.isDefined, "dice must not be null; pass an empty list to clear the pool")
    val values = supplied.map(_.asScala.toList).getOrElse(Nil)
    require(values.sizeIs <= 3, s"A dice pool holds at most 3 dice, got ${values.size}")
    state.withDicePool(values.map(dieValue))

  /** Ends the current player's turn: flips the active color, clears the dice pool, advances the full-move number after
    * Black, and drops stale en-passant targets.
    *
    * This is the other half of [[legalTurns]]' documented caveat. `Turn.finalState` is deliberately pre-`endTurn` — the
    * facade does not guess when a caller considers the turn over — so an autonomous loop applies this itself before
    * rolling for the opponent. A turn that captured the king is the exception: the game is over ([[isGameOver]]) and
    * ending the turn serves no purpose.
    */
  def endTurn(state: GameState): GameState = state.endTurn()

  /** Whether the game has been decided, i.e. a king has been captured — the engine's only terminal condition.
    *
    * Draws are not represented: the engine has no stalemate (a side with no legal turn simply passes) and no move-count
    * rule of its own, so a draw is whatever the host decides, typically off [[halfMoveClock]]. A position with no legal
    * turn is '''not''' game over.
    */
  def isGameOver(state: GameState): Boolean =
    kings(state, Color.White).isEmpty || kings(state, Color.Black).isEmpty

  /** The winner as a color id in the same encoding as [[activeColor]] — `0` White, `1` Black — or [[NoWinner]] (`-1`)
    * when the game is undecided.
    *
    * The side whose king is gone loses, so this is [[isGameOver]]'s companion rather than an independent judgment.
    * [[NoWinner]] covers three cases a caller may want to tell apart via [[isGameOver]]: the game is still running, the
    * host declared a draw on its own policy, or — only reachable from a hand-written DFEN — both kings are missing,
    * which is not a position any legal game reaches and is deliberately not resolved in either side's favour.
    */
  def winner(state: GameState): Int =
    val whiteAlive = !kings(state, Color.White).isEmpty
    val blackAlive = !kings(state, Color.Black).isEmpty
    if whiteAlive == blackAlive then NoWinner
    else if blackAlive then Color.Black.value
    else Color.White.value

  /** Serializes `state` back to a DFEN string — the inverse of [[parseDfen]], and how an autonomous loop persists or
    * logs a position, or hands it to another process.
    *
    * The output is '''canonical, not verbatim''': the dice pool is written in ascending order, and omitted entirely
    * when empty (leaving a plain six-field FEN). So `parseDfen(toDfen(state))` restores the same position and the same
    * multiset of dice, but not necessarily the dice in the order they were supplied to [[withDice]] — which is
    * immaterial to play, since a turn may spend its dice in any order. Serializing twice is stable.
    */
  def toDfen(state: GameState): String = FenParser.serialize(state)

  /** Scores `state` from `color`'s point of view in centipawns: positive means `color` is ahead. Combines material
    * balance with a king-safety penalty, the same evaluation the built-in heuristic bots use.
    *
    * Returns `int`, not a floating-point value, because the engine's scale is integral centipawns throughout — a
    * `double` here would advertise resolution the evaluator does not have. Values are on the order of ±3000 in normal
    * positions and are not comparable across engine versions; use them to rank candidate turns, not as an absolute
    * verdict.
    *
    * @param color
    *   `0` for White or `1` for Black, as returned by [[activeColor]]
    * @throws IllegalArgumentException
    *   if `color` is neither `0` nor `1`
    */
  def evaluate(state: GameState, color: Int): Int =
    Evaluator.evaluate(state, Color(color))

  /** The ids of every bot currently registered, ordered from weakest to strongest — the valid arguments for
    * [[bestTurn]].
    *
    * The list is a snapshot: the registry is a process-wide singleton that a host can add to at runtime, so a bot
    * registered by other code in the same JVM appears here too.
    */
  def algorithms(): ju.List[String] =
    BotRegistry.availableBots.map(_.id).asJava

  /** Asks the built-in bot registered under `algorithmId` for its choice of turn in `state`, giving a search that
    * spends time rather than a fixed amount of work [[DefaultTimeBudgetMs]] to think — this is what lets a Java
    * consumer play against the engine's own bots without importing anything from `dicechess.engine.search`.
    *
    * The dice pool of `state` is the roll the bot plays: set it with [[withDice]] first. An empty result means the roll
    * leaves no legal turn at all, which in Dice Chess is a forced pass, not a loss — the caller answers it with
    * [[endTurn]].
    *
    * Bots are free to break ties randomly, so two calls on the same position may legitimately differ.
    *
    * @param algorithmId
    *   an id from [[algorithms]], case-insensitive
    * @throws IllegalArgumentException
    *   if no bot is registered under `algorithmId`
    */
  def bestTurn(state: GameState, algorithmId: String): ju.Optional[ScoredTurn] =
    bestTurn(state, algorithmId, DefaultTimeBudgetMs)

  /** [[bestTurn]] with an explicit thinking time in milliseconds, the counterpart of the JS `getBestMove`'s
    * `timeBudgetMs` option.
    *
    * '''The budget binds only the bots that can use it''' — currently `monte-carlo`, and any host-registered bot built
    * on `TimeBudgetedSearch`. Those honour it as a deadline and hold to an anytime contract: whatever the budget, they
    * return a legal turn, taking an immediate king capture for free. Every other built-in bot does a fixed amount of
    * work per position (one pass over the legal turns, microseconds) and ignores `timeBudgetMs` entirely; passing a
    * large value does not make them think longer, and passing a small one does not cut them short.
    *
    * Why the facade insists on a budget at all, rather than simply calling each bot's plain entry point: a
    * time-budgeted bot's untimed path scores '''every''' legal turn, and a Dice Chess roll routinely offers thousands.
    * Measured on an ordinary middlegame position, that is minutes of CPU for a single call, with no way for a Java
    * caller to interrupt it. Routing through the deadline instead makes the cost predictable, which is the difference
    * between a bot a consumer can put in a loop and one that appears to hang.
    *
    * @param timeBudgetMs
    *   thinking time in milliseconds; must be positive
    * @throws IllegalArgumentException
    *   if no bot is registered under `algorithmId`, or `timeBudgetMs` is not positive
    */
  def bestTurn(state: GameState, algorithmId: String, timeBudgetMs: Long): ju.Optional[ScoredTurn] =
    val algorithm = BotRegistry.getAlgorithm(algorithmId)
    // Not an Either: an unknown id is a caller bug (a typo, or a bot never registered), reported the way parseDfen
    // reports an unparsable DFEN — as an exception a Java caller can catch.
    require(
      algorithm.isDefined,
      s"Unknown algorithm id: $algorithmId. Registered: ${BotRegistry.availableBots.map(_.id).mkString(", ")}"
    )
    require(timeBudgetMs > 0, s"timeBudgetMs must be positive, got $timeBudgetMs")

    val chosen = algorithm.flatMap {
      case budgeted: TimeBudgetedSearch =>
        val deadlineNanos = System.nanoTime() + timeBudgetMs * NanosPerMilli
        budgeted.findBestMove(state, deadlineNanos, Random())
      case fixedCost => fixedCost.findBestMove(state)
    }

    chosen match
      case None         => ju.Optional.empty[ScoredTurn]
      case Some(scored) =>
        val finalState = scored.moves.foldLeft(state)(_.makeMove(_))
        ju.Optional.of(ScoredTurn(scored.moves.map(_.toUci).asJava, finalState, scored.score))

  /** Validates one die from a caller-supplied pool, converting `null` into the same [[IllegalArgumentException]] as an
    * out-of-range value so a Java caller never sees a bare `NullPointerException` from unboxing.
    */
  private def dieValue(die: Integer | Null): Int =
    val value = Option(die).map(_.intValue).getOrElse(0)
    require(value >= 1 && value <= 6, s"Each die must be an integer from 1 to 6, got: $die")
    value

  /** The `color` king's square as a bitboard — empty exactly when that king has been captured. */
  private def kings(state: GameState, color: Color): Bitboard =
    val ownPieces: Bitboard = if color.isWhite then state.whitePieces else state.blackPieces
    state.kings & ownPieces
