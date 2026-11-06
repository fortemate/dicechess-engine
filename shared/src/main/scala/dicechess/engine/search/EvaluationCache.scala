// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.{Color, GameState}

/** Invocation-local memoization of a deterministic, side-effect-free evaluator.
  *
  * By default, keys use exact `(GameState, Color)` value equality, including mailbox contents, all flags, full
  * en-passant targets and counters. Hash collisions are resolved by equality; the derived `GameState.zobristKey` is not
  * part of identity. Inputs, including their mailboxes, must remain immutable for the lifetime of this cache.
  *
  * ===Consumer-Declared Valuation Equivalence===
  *
  * When an evaluator can prove that a coarser state projection preserves evaluation equality within a fixed evaluator,
  * perspective, configuration and model domain, callers may opt in by supplying an explicit `keyFn`.
  *
  * '''Valuation Equivalence Law:''' Within a fixed evaluator, configuration and perspective context, for any states
  * `s1` and `s2`:
  * ```text
  * keyFn(s1, p) == keyFn(s2, p) ==> evalFn(s1, p) == evalFn(s2, p)
  * ```
  * The consumer owns this proof; the engine cannot infer it from observed scores.
  *
  * Key laws:
  *   - '''Equivalence:''' `keyFn` must define a reflexive (`k == k`), symmetric (`k1 == k2 <=> k2 == k1`), and
  *     transitive (`k1 == k2 && k2 == k3 ==> k1 == k3`) equivalence relation.
  *   - '''Consistent Hash:''' if `k1 == k2` then `k1.## == k2.##`. Hash collisions are resolved by `equals`.
  *   - '''Original State Callback:''' on a cache miss, `evalFn` receives the original un-normalized
  *     [[dicechess.engine.domain.GameState]]. States passed to the callback are never normalized, and counters are
  *     never cleared.
  *   - '''Perspective Domain:''' `perspective` must remain an explicit component of the key domain. Evaluators from
  *     differing perspectives must not share cache entries.
  *   - '''Key Immutability:''' keys and evaluator configuration must remain immutable for the invocation lifetime.
  *   - '''Key Construction Failure:''' if `keyFn` throws an exception, no entry is created and the failure does not
  *     count as an evaluator call attempt.
  *
  * ===Re-entry and Concurrency===
  *
  * Bind one instance to one evaluator with stable configuration, owned by a single search/rescoring invocation. This
  * class is not thread-safe: do not share it between concurrent invocations, profiles, models, moves or games. Do not
  * wrap stochastic rollouts or callbacks depending on time, external history or changing configuration.
  *
  * During evaluation, callback re-entry (nested calls to [[apply]]) is supported. If nested calls insert entries or
  * cause the backing table to resize, the outer insertion resolves the key's table slot dynamically after the callback
  * returns, preventing stale slot corruption.
  *
  * ===Cancellation and Telemetry===
  *
  * Keep deadline/cancellation checks and fallback decisions in the caller, before every cache access, including hits. A
  * slow evaluator is not made interruptible and no hard deadline is guaranteed. Untimed deterministic selection retains
  * its scores, paths and RNG consumption when the caller preserves candidate multiplicity, ordering, shortlist
  * membership and tie-breaking. Timed searches may evaluate more candidates and select a different legal path.
  * Memoization must not become candidate deduplication.
  *
  * Memory grows with the number of unique evaluated state/perspective pairs in the invocation. A constant-work scalar
  * fingerprint selects a slot in a linear-probing reference table; exact equality, including the mailbox, still decides
  * every hit. References and primitive values are stored directly without per-entry objects. No tuple is allocated for
  * a lookup and the fingerprint is computed once, including on misses. Callers control the candidate/work bound. There
  * is no eviction or reuse across invocations. This is an exact evaluation cache, separate from [[TranspositionTable]]
  * search bounds (#397).
  *
  * FEN parsing and engine transitions keep the mailbox consistent with the bitboards. Arbitrary synthetic copies need
  * not preserve that invariant: mailbox-only variants deliberately share a scalar fingerprint but remain distinct by
  * equality. Ordinary index collisions can also occur between consistent states. A lookup scans its occupied cluster;
  * inserting many colliding values can require quadratic equality work. Table growth does not separate identical
  * fingerprints, and constant fingerprint cost is not a worst-case constant lookup guarantee.
  *
  * Pass [[apply]] to [[SearchScoring.scorePath]] so replay, `endTurn()` and the original mover's perspective remain
  * owned by that helper. Immediate king capture bypasses the evaluator and this cache entirely:
  *
  * ```scala
  * import dicechess.engine.domain.{Color, GameState, Move}
  * import dicechess.engine.search.{EvaluationCache, ScoredSequence, SearchScoring}
  *
  * def scoreCandidates(
  *     state: GameState,
  *     candidates: List[List[Move]],
  *     fullEval: (GameState, Color) => Int,
  *     mayContinue: () => Boolean
  * ): List[ScoredSequence] =
  *   val cachedEval = new EvaluationCache(fullEval)
  *   candidates.iterator
  *     .takeWhile(_ => mayContinue())
  *     .map { moves =>
  *       SearchScoring.scorePath(state, moves, cachedEval.apply)
  *     }
  *     .toList
  * ```
  *
  * The consumer retains evaluator configuration, deadline policy and fallback ownership; adopting this helper needs no
  * change to the engine's built-in bot defaults or facade contracts.
  *
  * Separately allocated equal mailboxes still share an entry, independently of the derived hash field:
  *
  * ```scala
  * import dicechess.engine.domain.{Color, FenParser, Mailbox}
  * import dicechess.engine.search.EvaluationCache
  *
  * val state = FenParser.parse("4k3/8/8/8/8/8/8/4K3 w - - 0 1").toOption.get
  * val equal = state.copy(mailbox = Mailbox.fromBuilder(state.mailbox.toArray), zobristKey = 123L)
  * val cache = new EvaluationCache((s, _) => s.fullMoveNumber)
  * assert(cache(state, Color.White) == cache(equal, Color.White))
  * assert(cache.evaluatorCalls == 1L && cache.cacheHits == 1L && cache.entryCount == 1)
  * ```
  *
  * Example with a consumer-declared valuation key:
  *
  * ```scala
  * import dicechess.engine.domain.{Color, GameState}
  * import dicechess.engine.search.EvaluationCache
  *
  * final case class SimpleValuationKey(
  *     white: Long,
  *     black: Long,
  *     active: Color,
  *     perspective: Color
  * )
  *
  * val invariantEval: (GameState, Color) => Int = (state, _) => state.whitePieces.value.toInt
  * val cache                                    = new EvaluationCache(
  *   invariantEval,
  *   (state, color) => SimpleValuationKey(state.whitePieces.value, state.blackPieces.value, state.activeColor, color)
  * )
  * ```
  *
  * Counter-sensitive evaluators must use the default exact cache (or include counters in the key):
  *
  * ```scala
  * import dicechess.engine.domain.{Color, GameState}
  * import dicechess.engine.search.EvaluationCache
  *
  * val clockSensitiveEval: (GameState, Color) => Int = (state, _) => state.fullMoveNumber
  * val exactCache                                    = new EvaluationCache(clockSensitiveEval)
  * ```
  *
  * @param evalFn
  *   pure deterministic callback with immutable input and configuration for this invocation
  * @param keyFn
  *   optional projection mapping `(GameState, Color)` to a valuation key with stable equality and hashing
  */
final class EvaluationCache(
    evalFn: (GameState, Color) => Int,
    keyFn: (GameState, Color) => Any
):
  def this(evalFn: (GameState, Color) => Int) =
    this(evalFn, EvaluationCache.exactKey)

  private object Empty
  private var states   = Array.fill[AnyRef | Null](16)(Empty)
  private var hashes   = new Array[Int](16)
  private var values   = new Array[Int](16)
  private var calls    = 0L
  private var hits     = 0L
  private var retained = 0

  private val isExact: Boolean = keyFn.asInstanceOf[AnyRef] eq EvaluationCache.exactKey

  /** Actual callback attempts, including those that throw. Hits do not increment this count. */
  def evaluatorCalls: Long = calls

  /** Successful lookups that reuse a previously computed value. Failed evaluations never count as hits. */
  def cacheHits: Long = hits

  /** Number of successful state/perspective pairs retained by this invocation. */
  def entryCount: Int = retained

  // Mix both halves before folding: Long.hashCode would erase equal high/low changes before final mixing.
  // The mailbox is omitted only from the index. Exact equality below still includes all of its contents.
  private inline def field(value: Long): Int =
    val mixed = value * 0x9e3779b97f4a7c15L
    (mixed ^ (mixed >>> 32)).toInt

  private def fingerprint(state: GameState, perspective: Color): Int =
    var hash = field(state.whitePieces.value)
    hash = java.lang.Integer.rotateLeft(hash, 5) ^ field(state.blackPieces.value)
    hash = java.lang.Integer.rotateLeft(hash, 5) ^ field(state.pawns.value)
    hash = java.lang.Integer.rotateLeft(hash, 5) ^ field(state.knights.value)
    hash = java.lang.Integer.rotateLeft(hash, 5) ^ field(state.bishops.value)
    hash = java.lang.Integer.rotateLeft(hash, 5) ^ field(state.rooks.value)
    hash = java.lang.Integer.rotateLeft(hash, 5) ^ field(state.queens.value)
    hash = java.lang.Integer.rotateLeft(hash, 5) ^ field(state.kings.value)
    hash = java.lang.Integer.rotateLeft(hash, 5) ^ field(state.enPassant.value)
    hash = java.lang.Integer.rotateLeft(hash, 5) ^ (state.flags.value * 0x85ebca6b)
    hash = java.lang.Integer.rotateLeft(hash, 5) ^ (state.fullMoveNumber * 0xc2b2ae35)
    hash ^= hash >>> 16
    hash *= 0x85ebca6b
    hash ^= hash >>> 13
    // Reserve one bit for the exact perspective, so equal signatures imply equal colors.
    (hash & ~1) | perspective.value

  private def findExact(hash: Int, state: GameState): Int =
    val mask = states.length - 1
    var slot = (hash >>> 1) & mask
    while (states(slot) ne Empty) &&
      !(hashes(slot) == hash && ((states(slot) eq state) || states(slot).asInstanceOf[GameState] == state))
    do slot = (slot + 1) & mask
    slot

  private inline def keysMatch(stored: AnyRef | Null, key: Any | Null): Boolean =
    val storedRef = stored.asInstanceOf[AnyRef]
    val keyRef    = key.asInstanceOf[AnyRef]
    if storedRef eq keyRef then true
    else if (storedRef eq null) || (keyRef eq null) then false // scalafix:ok(DisableSyntax.null)
    else
      given CanEqual[Any, Any] = CanEqual.derived
      (stored: Any) == (key: Any)

  private def findKeyed(hash: Int, key: Any | Null): Int =
    val mask = states.length - 1
    var slot = (hash >>> 1) & mask
    while (states(slot) ne Empty) &&
      !(hashes(slot) == hash && keysMatch(states(slot), key))
    do slot = (slot + 1) & mask
    slot

  private def grow(): Unit =
    val oldStates = states
    val oldHashes = hashes
    val oldValues = values
    states = Array.fill[AnyRef | Null](oldStates.length * 2)(Empty)
    hashes = new Array[Int](states.length)
    values = new Array[Int](states.length)
    val mask  = states.length - 1
    var index = 0
    while index < oldStates.length do
      if oldStates(index) ne Empty then
        val hash = oldHashes(index)
        var slot = (hash >>> 1) & mask
        while states(slot) ne Empty do slot = (slot + 1) & mask
        states(slot) = oldStates(index)
        hashes(slot) = hash
        values(slot) = oldValues(index)
      index += 1

  /** Returns a cached value or evaluates once. Exceptions propagate unchanged and never create an entry; a later access
    * retries the callback. Every returned `Int`, including sentinel-like extreme values, is cacheable.
    */
  def apply(state: GameState, perspective: Color): Int =
    if isExact then applyExact(state, perspective)
    else applyKeyed(state, perspective)

  private def applyExact(state: GameState, perspective: Color): Int =
    val hash = fingerprint(state, perspective)
    var slot = findExact(hash, state)
    if states(slot) ne Empty then
      hits += 1
      values(slot)
    else
      val before = retained
      calls += 1
      val value = evalFn(state, perspective)
      if retained != before then slot = findExact(hash, state)
      if states(slot) eq Empty then
        if retained >= states.length - states.length / 4 then
          grow()
          slot = findExact(hash, state)
        hashes(slot) = hash
        states(slot) = state
        retained += 1
      values(slot) = value
      value

  private def applyKeyed(state: GameState, perspective: Color): Int =
    val key  = keyFn(state, perspective)
    val hash = if key.asInstanceOf[AnyRef] eq null then 0 else key.## // scalafix:ok(DisableSyntax.null)
    var slot = findKeyed(hash, key)
    if states(slot) ne Empty then
      hits += 1
      values(slot)
    else
      val before = retained
      calls += 1
      val value = evalFn(state, perspective)
      if retained != before then slot = findKeyed(hash, key)
      if states(slot) eq Empty then
        if retained >= states.length - states.length / 4 then
          grow()
          slot = findKeyed(hash, key)
        hashes(slot) = hash
        states(slot) = key.asInstanceOf[AnyRef | Null]
        retained += 1
      values(slot) = value
      value

object EvaluationCache:
  /** Default exact key projection: exact `(GameState, Color)` identity. */
  val exactKey: (GameState, Color) => Any = (state, perspective) => (state, perspective)

  /** Creates an exact-identity evaluation cache. */
  def exact(evalFn: (GameState, Color) => Int): EvaluationCache =
    new EvaluationCache(evalFn)

  /** Creates an evaluation cache with an explicit consumer-declared valuation key projection.
    *
    * The caller guarantees the valuation-equivalence law: for a fixed evaluator, configuration, perspective and model
    * domain, `keyFn(s1, p) == keyFn(s2, p)` implies `evalFn(s1, p) == evalFn(s2, p)`. The callback `evalFn` receives
    * the original un-normalized [[dicechess.engine.domain.GameState]].
    */
  def withValuationKey[K](evalFn: (GameState, Color) => Int)(keyFn: (GameState, Color) => K): EvaluationCache =
    new EvaluationCache(evalFn, keyFn)
