// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.{Color, GameState}

/** Invocation-local memoization of a deterministic, side-effect-free evaluator.
  *
  * Keys use exact `(GameState, Color)` value equality, including mailbox contents, all flags, full en-passant targets
  * and counters. Hash collisions are resolved by equality; the derived `GameState.zobristKey` is not part of identity.
  * Inputs, including their mailboxes, must remain immutable for the lifetime of this cache.
  *
  * Bind one instance to one evaluator with stable configuration, owned by a single search/rescoring invocation. This
  * class is not thread-safe: do not share it between concurrent invocations, profiles, models, moves or games. Do not
  * wrap stochastic rollouts or callbacks depending on time, external history or changing configuration.
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
  * @param evalFn
  *   pure deterministic callback with immutable input and configuration for this invocation
  */
final class EvaluationCache(evalFn: (GameState, Color) => Int):
  private object Empty
  private var states   = Array.fill[AnyRef](16)(Empty)
  private var hashes   = new Array[Int](16)
  private var values   = new Array[Int](16)
  private var calls    = 0L
  private var hits     = 0L
  private var retained = 0

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

  // Linear probing stores references and primitive data directly, without a linked Entry per success.
  // Empty slots use a private sentinel, allowing every Int hash and evaluator result to be stored.
  private def find(hash: Int, state: GameState): Int =
    val mask = states.length - 1
    var slot = (hash >>> 1) & mask
    while (states(slot) ne Empty) &&
      !(hashes(slot) == hash && ((states(slot) eq state) || states(slot).asInstanceOf[GameState] == state))
    do slot = (slot + 1) & mask
    slot

  private def grow(): Unit =
    val oldStates = states
    val oldHashes = hashes
    val oldValues = values
    states = Array.fill[AnyRef](oldStates.length * 2)(Empty)
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
    val hash = fingerprint(state, perspective)
    var slot = find(hash, state)
    if states(slot) ne Empty then
      hits += 1
      values(slot)
    else
      val before = retained
      calls += 1
      val value = evalFn(state, perspective)
      // Without successful re-entry, neither the vacant slot nor its backing arrays can have changed.
      // A callback may insert this very key, grow the table, or fail after retaining other nested successes.
      if retained != before then slot = find(hash, state)
      if states(slot) eq Empty then
        if retained >= states.length - states.length / 4 then
          grow()
          slot = find(hash, state)
        hashes(slot) = hash
        states(slot) = state
        retained += 1
      values(slot) = value
      value
