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
  * fingerprint selects a collision chain; exact equality, including the mailbox, still decides every hit. No tuple is
  * allocated for a lookup and the fingerprint is computed once, including on misses. Callers control the candidate/work
  * bound. There is no eviction or reuse across invocations. This is an exact evaluation cache, separate from
  * [[TranspositionTable]] search bounds (#397).
  *
  * FEN parsing and engine transitions keep the mailbox consistent with the bitboards. Arbitrary synthetic copies need
  * not preserve that invariant: mailbox-only variants deliberately share a scalar fingerprint but remain distinct by
  * equality. Ordinary index collisions can also occur between consistent states. A lookup scans its collision chain;
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
  sealed private trait Bucket derives CanEqual
  private case object Empty extends Bucket
  final private class Entry(
      val hash: Int,
      val state: GameState,
      val perspective: Color,
      var value: Int,
      var next: Bucket
  ) extends Bucket

  private var entries  = Array.fill[Bucket](16)(Empty)
  private var calls    = 0L
  private var hits     = 0L
  private var retained = 0

  /** Actual callback attempts, including those that throw. Hits do not increment this count. */
  def evaluatorCalls: Long = calls

  /** Successful lookups that reuse a previously computed value. Failed evaluations never count as hits. */
  def cacheHits: Long = hits

  /** Number of successful state/perspective pairs retained by this invocation. */
  def entryCount: Int = retained

  // The mailbox is deliberately omitted from the index, never from identity. This avoids its 64-entry hash walk;
  // even inconsistent synthetic states with identical scalar fields remain distinct through full equality below.
  // Neither a populated nor an unpopulated derived Zobrist key participates in this fingerprint.
  private def fingerprint(state: GameState, perspective: Color): Int =
    var hash = if perspective.isWhite then 17 else 19
    hash = hash * 31 + java.lang.Long.hashCode(state.whitePieces.value)
    hash = hash * 31 + java.lang.Long.hashCode(state.blackPieces.value)
    hash = hash * 31 + java.lang.Long.hashCode(state.pawns.value)
    hash = hash * 31 + java.lang.Long.hashCode(state.knights.value)
    hash = hash * 31 + java.lang.Long.hashCode(state.bishops.value)
    hash = hash * 31 + java.lang.Long.hashCode(state.rooks.value)
    hash = hash * 31 + java.lang.Long.hashCode(state.queens.value)
    hash = hash * 31 + java.lang.Long.hashCode(state.kings.value)
    hash = hash * 31 + state.flags.value
    hash = hash * 31 + java.lang.Long.hashCode(state.enPassant.value)
    hash = hash * 31 + state.fullMoveNumber
    hash ^ (hash >>> 16)

  private def bucket(hash: Int): Bucket = entries(hash & (entries.length - 1))

  // A resizable reference table stores only chain heads, with no boxed primitive keys or parallel key arrays.
  // Relinking retained entries on growth neither evaluates a state nor recomputes its fingerprint.
  private def grow(): Unit =
    val previous = entries
    entries = Array.fill[Bucket](previous.length * 2)(Empty)
    var index = 0
    while index < previous.length do
      var cursor   = previous(index)
      var scanning = true
      while scanning do
        cursor match
          case entry: Entry =>
            cursor = entry.next
            val slot = entry.hash & (entries.length - 1)
            entry.next = entries(slot)
            entries(slot) = entry
          case Empty => scanning = false
      index += 1

  private def find(bucket: Bucket, hash: Int, state: GameState, perspective: Color): Bucket =
    var cursor    = bucket
    var searching = true
    while searching do
      cursor match
        case entry: Entry =>
          if entry.hash == hash && entry.perspective == perspective && ((entry.state eq state) || entry.state == state)
          then searching = false
          else cursor = entry.next
        case Empty => searching = false
    cursor

  /** Returns a cached value or evaluates once. Exceptions propagate unchanged and never create an entry; a later access
    * retries the callback. Every returned `Int`, including sentinel-like extreme values, is cacheable.
    */
  def apply(state: GameState, perspective: Color): Int =
    val hash = fingerprint(state, perspective)
    find(bucket(hash), hash, state, perspective) match
      case entry: Entry =>
        hits += 1
        entry.value
      case Empty =>
        calls += 1
        val value = evalFn(state, perspective)
        // Re-read only the primitive index after the callback: a nested successful evaluation must survive, even
        // when it populated this collision chain or the same key. Failures reach neither mutation below.
        find(bucket(hash), hash, state, perspective) match
          case entry: Entry => entry.value = value
          case Empty        =>
            if retained >= entries.length - entries.length / 4 then grow()
            val slot = hash & (entries.length - 1)
            entries(slot) = new Entry(hash, state, perspective, value, entries(slot))
            retained += 1
        value
