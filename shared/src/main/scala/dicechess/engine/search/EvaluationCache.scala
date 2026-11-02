// SPDX-License-Identifier: AGPL-3.0-only
package dicechess.engine.search

import dicechess.engine.domain.{Color, GameState}

import scala.collection.mutable

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
  * Memory grows with the number of unique evaluated state/perspective pairs in the invocation; hashing and comparing
  * full states includes walking the mailbox. Callers control the candidate/work bound. There is no eviction or reuse
  * across invocations. This is an exact evaluation cache, separate from search transposition-table bounds.
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
  * @param evalFn
  *   pure deterministic callback with immutable input and configuration for this invocation
  */
final class EvaluationCache(evalFn: (GameState, Color) => Int):
  private val entries = mutable.HashMap.empty[(GameState, Color), Int]
  private var calls   = 0L
  private var hits    = 0L

  /** Actual callback attempts, including those that throw. Hits do not increment this count. */
  def evaluatorCalls: Long = calls

  /** Successful lookups that reuse a previously computed value. Failed evaluations never count as hits. */
  def cacheHits: Long = hits

  /** Number of successful state/perspective pairs retained by this invocation. */
  def entryCount: Int = entries.size

  /** Returns a cached value or evaluates once. Exceptions propagate unchanged and never create an entry; a later access
    * retries the callback. Every returned `Int`, including sentinel-like extreme values, is cacheable.
    */
  def apply(state: GameState, perspective: Color): Int =
    val key = (state, perspective)
    entries.get(key) match
      case Some(value) =>
        hits += 1
        value
      case None =>
        calls += 1
        val value = evalFn(state, perspective)
        entries.update(key, value)
        value
